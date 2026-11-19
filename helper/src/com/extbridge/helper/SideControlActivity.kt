package com.extbridge.helper

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * In-app live mirror controller for Native Mirror's black-margin sidebar and system AppWidgets.
 * Allows direct finger tap/swipe/scroll control of the sidebar without external mouse or keyboard.
 */
class SideControlActivity : Activity(), TextureView.SurfaceTextureListener {

    private val mHandler = Handler(Looper.getMainLooper())
    private var mTouchExec: ExecutorService? = null
    private var mTextureView: TextureView? = null
    private var mMirrorStage: FrameLayout? = null
    private var mRootView: LinearLayout? = null
    private var mHeaderView: LinearLayout? = null
    private var mBarScroll: HorizontalScrollView? = null
    private var mTitleView: TextView? = null
    private var mTheme: BridgeClient.MirrorTheme = BridgeClient.resolveMirrorTheme("night", 0xFF0F172A.toInt())
    private var mMirrorCardFrame: FrameLayout? = null
    private var mRippleOverlay: TouchRippleOverlay? = null
    private var mTvInfo: TextView? = null
    private var mBtnWingLeft: TextView? = null
    private var mBtnWingRight: TextView? = null
    private var mWingSwitchRow: LinearLayout? = null
    private var mBtnZoomMode: TextView? = null
    private var mTvEmptyHint: TextView? = null

    private var mTexSurface: Surface? = null
    private var mTexW = 0
    private var mTexH = 0
    private var mWingSlot = 0
    private var mWideTouchBoost = true
    private var mRunning = false
    private var mUseSoftwareDraw = false
    private var mSwBmp: Bitmap? = null
    private var mSwCanvas: Canvas? = null
    private var mSwW = 0
    private var mSwH = 0

    private var mLastMetaX = 0
    private var mLastMetaY = 0
    private var mLastMetaW = 0
    private var mLastMetaH = 0
    private var mLastStageW = 0
    private var mLastStageH = 0
    private var mLastWideBoost = true
    private var mLastWingSlot = -1
    private var mSideTouchDown = false
    private var mLastSideTouchX = 0f
    private var mLastSideTouchY = 0f

    private fun ensureOnDisplay0(): Boolean {
        try {
            val d = display
            if (d != null && d.displayId != 0) {
                val i = Intent(this, SideControlActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                val opts = ActivityOptions.makeBasic()
                opts.setLaunchDisplayId(0)
                startActivity(i, opts.toBundle())
                finishAndRemoveTask()
                return true
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun cancelHeldSideTouchIfNeeded() {
        if (!mSideTouchDown) return
        mSideTouchDown = false
        val sx = mLastSideTouchX
        val sy = mLastSideTouchY
        mRippleOverlay?.setTouchPoint(-1f, -1f, false)
        Thread { BridgeClient.sendSideTouch(MotionEvent.ACTION_CANCEL, sx, sy) }.start()
    }

    private val mFrameTick = object : Runnable {
        override fun run() {
            if (!mRunning) return
            syncStageLayoutAndMeta()
            renderMirrorFrame()
            mHandler.postDelayed(this, 33L)
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    private fun makeBtn(text: String, bgColor: Int, iconKind: Int): TextView {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        if (iconKind > 0) {
            HyperUi.setStartIcon(tv, iconKind, Color.WHITE, 14f, 5f)
        }
        val gd = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = dp(13).toFloat()
            setStroke(dp(1), 0x24FFFFFF)
        }
        tv.background = gd
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(dp(4), 0, dp(4), 0)
        }
        tv.layoutParams = lp
        return tv
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ensureOnDisplay0()) return
        window.decorView.filterTouchesWhenObscured = true
        mTouchExec = Executors.newSingleThreadExecutor()

        mTheme = BridgeService.getActiveMirrorTheme(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(mTheme.bgColor)
            fitsSystemWindows = true
        }
        mRootView = root

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(14), dp(10))
            setBackgroundColor(mTheme.headerBgColor)
        }
        mHeaderView = header

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = TextView(this).apply {
            text = "副屏镜像直控"
            setTextColor(mTheme.titleColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            paint.isFakeBoldText = true
        }
        HyperUi.setStartIcon(title, HyperUi.ICON_SIDEBAR, HyperUi.HYPER_BLUE, 17f, 6f)
        mTitleView = title
        titleCol.addView(title)

        val tvInfo = TextView(this).apply {
            text = "正在连接原生镜像侧边栏…"
            setTextColor(mTheme.accentColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        mTvInfo = tvInfo
        titleCol.addView(tvInfo)
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val btnZoomMode = makeBtn("宽屏易触", HyperUi.HYPER_BLUE, HyperUi.ICON_ZOOM)
        btnZoomMode.setOnClickListener {
            mWideTouchBoost = !mWideTouchBoost
            btnZoomMode.text = if (mWideTouchBoost) "宽屏易触" else "原比例"
            HyperUi.setStartIcon(
                btnZoomMode,
                if (mWideTouchBoost) HyperUi.ICON_ZOOM else HyperUi.ICON_RATIO,
                Color.WHITE,
                14f,
                5f,
            )
            (btnZoomMode.background as? GradientDrawable)?.setColor(
                if (mWideTouchBoost) HyperUi.HYPER_BLUE else HyperUi.CHIP_INACTIVE,
            )
            mLastMetaW = 0
            syncStageLayoutAndMeta()
        }
        mBtnZoomMode = btnZoomMode
        header.addView(btnZoomMode)

        val btnClose = makeBtn("退出", HyperUi.CHIP_INACTIVE, HyperUi.ICON_CLOSE)
        btnClose.setOnClickListener { finish() }
        header.addView(btnClose)
        root.addView(header)

        // Dual-wing switcher row (shown only in dual-wing centered mode)
        val wingSwitchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(4))
            visibility = View.GONE
        }
        val btnWingLeft = makeBtn("左/上侧信息条", HyperUi.HYPER_BLUE, HyperUi.ICON_CHEVRON_LEFT)
        btnWingLeft.setOnClickListener {
            mWingSlot = 0
            updateWingBtns()
        }
        val btnWingRight = makeBtn("右/下侧信息条", HyperUi.CHIP_INACTIVE, HyperUi.ICON_CHEVRON_RIGHT)
        btnWingRight.setOnClickListener {
            mWingSlot = 1
            updateWingBtns()
        }
        mBtnWingLeft = btnWingLeft
        mBtnWingRight = btnWingRight
        wingSwitchRow.addView(btnWingLeft)
        wingSwitchRow.addView(btnWingRight)
        mWingSwitchRow = wingSwitchRow
        root.addView(wingSwitchRow)

        // Center Stage
        val mirrorStage = FrameLayout(this).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        mMirrorStage = mirrorStage

        val mirrorCardFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(0xFF0B1221.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), HyperUi.HYPER_BLUE)
            }
            clipToOutline = true
        }
        mMirrorCardFrame = mirrorCardFrame

        val textureView = TextureView(this).apply {
            surfaceTextureListener = this@SideControlActivity
            setOnTouchListener { _, ev -> handleMirrorTouch(ev) }
        }
        mTextureView = textureView
        mirrorCardFrame.addView(
            textureView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val rippleOverlay = TouchRippleOverlay(this)
        mRippleOverlay = rippleOverlay
        mirrorCardFrame.addView(
            rippleOverlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val cardLp = FrameLayout.LayoutParams(dp(260), dp(420), Gravity.CENTER)
        mirrorStage.addView(mirrorCardFrame, cardLp)

        val tvEmptyHint = TextView(this).apply {
            text = "当前未处于「原生镜像 + 融合边栏」模式\n请在控制台开启「原生镜像」并选择边栏对齐后使用"
            setTextColor(0xFF94A3B8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        mTvEmptyHint = tvEmptyHint
        mirrorStage.addView(
            tvEmptyHint,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )

        root.addView(mirrorStage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom quick bar
        val barScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(mTheme.headerBgColor)
        }
        mBarScroll = barScroll

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        val btnAlign = makeBtn("切换边栏方位", HyperUi.HYPER_BLUE, HyperUi.ICON_SWAP)
        btnAlign.setOnClickListener {
            mTouchExec?.execute {
                val cur = BridgeClient.getStatus().mirrorSideMode
                val next = if (cur == BridgeClient.MIRROR_SIDE_ALIGN_LEFT) {
                    BridgeClient.MIRROR_SIDE_ALIGN_RIGHT
                } else {
                    BridgeClient.MIRROR_SIDE_ALIGN_LEFT
                }
                BridgeClient.setMirrorSideMode(next)
                BridgeService.refreshFromReceiver(this)
            }
        }

        val btnMain = makeBtn("卡片与小组件配置", HyperUi.CHIP_INACTIVE, HyperUi.ICON_SLIDERS)
        btnMain.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }

        val btnTp = makeBtn("大触摸板", HyperUi.CHIP_INACTIVE, HyperUi.ICON_TOUCHPAD)
        btnTp.setOnClickListener {
            startActivity(
                Intent(this, TouchpadActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }

        val btnDex = makeBtn("切回 DeX", HyperUi.CHIP_INACTIVE, HyperUi.ICON_DEX)
        btnDex.setOnClickListener {
            mTouchExec?.execute {
                BridgeClient.setMirrorMode(BridgeClient.MIRROR_MODE_DEX)
                BridgeService.refreshFromReceiver(this)
                runOnUiThread {
                    Toast.makeText(this, "已切回 DeX 扩展桌面", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }

        bar.addView(btnAlign)
        bar.addView(btnMain)
        bar.addView(btnTp)
        bar.addView(btnDex)
        barScroll.addView(bar)
        root.addView(barScroll)

        setContentView(root)
    }

    private fun updateWingBtns() {
        (mBtnWingLeft?.background as? GradientDrawable)?.setColor(
            if (mWingSlot == 0) HyperUi.HYPER_BLUE else HyperUi.CHIP_INACTIVE,
        )
        (mBtnWingRight?.background as? GradientDrawable)?.setColor(
            if (mWingSlot == 1) HyperUi.HYPER_BLUE else HyperUi.CHIP_INACTIVE,
        )
        mLastMetaW = 0
        syncStageLayoutAndMeta()
    }

    private fun syncStageLayoutAndMeta() {
        val latestTheme = BridgeService.getActiveMirrorTheme(this)
        if (latestTheme.bgColor != mTheme.bgColor || latestTheme.titleColor != mTheme.titleColor) {
            mTheme = latestTheme
            mRootView?.setBackgroundColor(mTheme.bgColor)
            mHeaderView?.setBackgroundColor(mTheme.headerBgColor)
            mBarScroll?.setBackgroundColor(mTheme.headerBgColor)
            mTitleView?.setTextColor(mTheme.titleColor)
            mTvInfo?.setTextColor(mTheme.accentColor)
        }
        val frame = mMirrorCardFrame ?: return
        val emptyHint = mTvEmptyHint ?: return
        val wingRow = mWingSwitchRow ?: return
        val tvInfo = mTvInfo ?: return
        val stage = mMirrorStage ?: return

        val meta = BridgeService.getMirrorWingMeta(mWingSlot)
        if (meta == null) {
            frame.visibility = View.INVISIBLE
            emptyHint.visibility = View.VISIBLE
            wingRow.visibility = View.GONE
            tvInfo.text = "未检测到融合边栏（请先进入原生镜像模式）"
            return
        }
        frame.visibility = View.VISIBLE
        emptyHint.visibility = View.GONE

        val wx = meta[0]
        val wy = meta[1]
        val ww = meta[2]
        val wh = meta[3]
        val hasDual = meta[7]
        val sideMode = meta[8]
        wingRow.visibility = if (hasDual == 1) View.VISIBLE else View.GONE
        if (hasDual == 0 && mWingSlot != 0) mWingSlot = 0

        val availW = max(dp(120), stage.width - stage.paddingLeft - stage.paddingRight)
        val availH = max(dp(120), stage.height - stage.paddingTop - stage.paddingBottom)
        if (mLastMetaW == ww && mLastMetaH == wh && mLastMetaX == wx && mLastMetaY == wy &&
            mLastStageW == availW && mLastStageH == availH &&
            mLastWideBoost == mWideTouchBoost && mLastWingSlot == mWingSlot
        ) {
            return
        }
        mLastMetaX = wx
        mLastMetaY = wy
        mLastMetaW = ww
        mLastMetaH = wh
        mLastStageW = availW
        mLastStageH = availH
        mLastWideBoost = mWideTouchBoost
        mLastWingSlot = mWingSlot

        val fitScale = min(availW / ww.toFloat(), availH / wh.toFloat())
        var dstW = max(dp(80), (ww * fitScale).roundToInt())
        var dstH = max(dp(80), (wh * fitScale).roundToInt())
        if (mWideTouchBoost) {
            if (ww < wh) {
                dstW = min(availW, max(dstW, min(dp(340), (dstW * 1.55f).roundToInt())))
            } else if (ww > wh) {
                dstH = min(availH, max(dstH, min(dp(300), (dstH * 1.35f).roundToInt())))
            }
        }
        val lp = frame.layoutParams as FrameLayout.LayoutParams
        if (lp.width != dstW || lp.height != dstH) {
            lp.width = dstW
            lp.height = dstH
            lp.gravity = Gravity.CENTER
            frame.layoutParams = lp
        }
        tvInfo.text = "${BridgeClient.mirrorSideModeLabel(sideMode)} · ${ww}×${wh} @($wx,$wy) · 直接点按/滑动即控"
    }

    private fun renderMirrorFrame() {
        val surf = mTexSurface
        if (surf == null || !surf.isValid || mTexW <= 0 || mTexH <= 0) return
        val meta = BridgeService.getMirrorWingMeta(mWingSlot) ?: return
        if (!mUseSoftwareDraw) {
            var hw: Canvas? = null
            try {
                hw = surf.lockHardwareCanvas()
                hw.drawColor(mTheme.bgColor)
                BridgeService.renderMirrorWingToCanvas(hw, mTexW, mTexH, mWingSlot)
                surf.unlockCanvasAndPost(hw)
                hw = null
                return
            } catch (_: Throwable) {
                if (hw != null) {
                    try { surf.unlockCanvasAndPost(hw) } catch (_: Throwable) {}
                }
                mUseSoftwareDraw = true
            }
        }
        // Safe software fallback if cross-display RenderNode causes HW canvas conflict (capped for 4K/5K displays)
        try {
            val ww = meta[2]
            val wh = meta[3]
            val swScale = min(1f, min(900f / max(1, ww), 1440f / max(1, wh)))
            val swW = max(1, (ww * swScale).roundToInt())
            val swH = max(1, (wh * swScale).roundToInt())
            if (mSwBmp == null || mSwW != swW || mSwH != swH) {
                mSwBmp?.recycle()
                val bmp = Bitmap.createBitmap(swW, swH, Bitmap.Config.ARGB_8888)
                mSwBmp = bmp
                mSwCanvas = Canvas(bmp)
                mSwW = swW
                mSwH = swH
            }
            val bmp = mSwBmp ?: return
            val swCanvas = mSwCanvas ?: return
            bmp.eraseColor(mTheme.bgColor)
            BridgeService.renderMirrorWingToCanvas(swCanvas, swW, swH, mWingSlot)
            val c = surf.lockCanvas(null)
            if (c != null) {
                try {
                    c.drawBitmap(
                        bmp,
                        Rect(0, 0, swW, swH),
                        Rect(0, 0, mTexW, mTexH),
                        null,
                    )
                } finally {
                    surf.unlockCanvasAndPost(c)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun handleMirrorTouch(ev: MotionEvent?): Boolean {
        if (ev == null) return false
        val tv = mTextureView ?: return false
        val meta = BridgeService.getMirrorWingMeta(mWingSlot)
        if (meta == null || tv.width <= 0 || tv.height <= 0) return false
        val act = ev.actionMasked
        if (act != MotionEvent.ACTION_DOWN && act != MotionEvent.ACTION_MOVE &&
            act != MotionEvent.ACTION_UP && act != MotionEvent.ACTION_CANCEL
        ) {
            return true
        }
        val rx = (ev.x / tv.width.toFloat()).coerceIn(0f, 1f)
        val ry = (ev.y / tv.height.toFloat()).coerceIn(0f, 1f)
        val sx = meta[0] + rx * meta[2]
        val sy = meta[1] + ry * meta[3]
        mLastSideTouchX = sx
        mLastSideTouchY = sy
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
            mSideTouchDown = true
        } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            mSideTouchDown = false
        }
        val show = act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE
        mRippleOverlay?.setTouchPoint(ev.x, ev.y, show)
        mTouchExec?.execute { BridgeClient.sendSideTouch(act, sx, sy) }
        if (act == MotionEvent.ACTION_UP) {
            mHandler.postDelayed({ renderMirrorFrame() }, 60L)
            mHandler.postDelayed({ renderMirrorFrame() }, 180L)
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        mRunning = true
        mHandler.removeCallbacks(mFrameTick)
        mHandler.post(mFrameTick)
    }

    override fun onPause() {
        cancelHeldSideTouchIfNeeded()
        mRunning = false
        mHandler.removeCallbacks(mFrameTick)
        super.onPause()
    }

    override fun onDestroy() {
        cancelHeldSideTouchIfNeeded()
        mRunning = false
        mHandler.removeCallbacksAndMessages(null)
        mTexSurface?.release()
        mTexSurface = null
        mSwBmp?.recycle()
        mSwBmp = null
        mSwCanvas = null
        mTouchExec?.shutdownNow()
        mTouchExec = null
        super.onDestroy()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        mTexSurface?.release()
        mTexSurface = Surface(surface)
        mTexW = width
        mTexH = height
        renderMirrorFrame()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        mTexW = width
        mTexH = height
        renderMirrorFrame()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        mTexSurface?.release()
        mTexSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private class TouchRippleOverlay(ctx: Context) : View(ctx) {
        private val mPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x8838BDF8.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private var mX = -1f
        private var mY = -1f
        private var mVisible = false

        fun setTouchPoint(x: Float, y: Float, visible: Boolean) {
            mX = x
            mY = y
            mVisible = visible
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (mVisible && mX >= 0f && mY >= 0f) {
                canvas.drawCircle(mX, mY, 22f, mPaint)
            }
        }
    }
}

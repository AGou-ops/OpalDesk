package io.github.lengsukq.opaldesk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.*
import io.github.lengsukq.opaldesk.design.iosglass.CrossWindowBlurController
import io.github.lengsukq.opaldesk.design.iosglass.LiquidGlassDrawable
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.MirrorTheme
import io.github.lengsukq.opaldesk.prefs.MirrorThemeCatalog
import io.github.lengsukq.opaldesk.protocol.BridgeLabels
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.service.DisplayLaunchHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * In-app live mirror controller for Native Mirror's black-margin sidebar and system AppWidgets.
 */
class SideControlActivity : Activity(), TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "ExtSideControl"
        private const val FRAME_INTERVAL_MS = 33L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var touchExec: ExecutorService? = null

    private lateinit var rootView: LinearLayout
    private lateinit var headerView: LinearLayout
    private lateinit var barScroll: HorizontalScrollView
    private lateinit var titleView: TextView
    private lateinit var infoView: TextView
    private lateinit var wingSwitchRow: LinearLayout
    private lateinit var btnWingLeft: TextView
    private lateinit var btnWingRight: TextView
    private lateinit var mirrorStage: FrameLayout
    private lateinit var mirrorCardFrame: FrameLayout
    private lateinit var textureView: TextureView
    private lateinit var rippleOverlay: TouchRippleOverlay
    private lateinit var emptyHintView: TextView

    private var theme: MirrorTheme = MirrorThemeCatalog.resolveMirrorTheme("night", 0xFF0F172A.toInt())
    private var texSurface: Surface? = null
    private var texW = 0
    private var texH = 0
    private var wingSlot = 0
    private var wideTouchBoost = true
    private var running = false
    private var useSoftwareDraw = false

    private var swBmp: Bitmap? = null
    private var swCanvas: Canvas? = null
    private var swW = 0
    private var swH = 0
    private val swSrcRect = Rect()
    private val swDstRect = Rect()

    private var lastLayoutKey = Long.MIN_VALUE
    private var sideTouchDown = false
    private var lastSideTouchX = 0f
    private var lastSideTouchY = 0f

    private val frameTick = object : Runnable {
        override fun run() {
            if (!running) return
            syncStageLayoutAndMeta()
            renderMirrorFrame()
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    private fun dp(v: Int): Int = HyperUi.dp(this, v.toFloat())

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DisplayLaunchHelper.ensureOnDisplay0(this)) return
        window.decorView.filterTouchesWhenObscured = true
        CrossWindowBlurController.applyToActivityWindow(window)
        touchExec = Executors.newSingleThreadExecutor()
        theme = AppPrefs.getActiveMirrorTheme(this)

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.bgColor)
            fitsSystemWindows = true
            addView(buildHeader())
            addView(buildWingSwitchRow())
            addView(buildMirrorStage(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buildBottomBar())
        }
        setContentView(rootView)
    }

    private fun buildHeader(): LinearLayout {
        titleView = TextView(this).apply {
            text = "副屏镜像直控"; setTextColor(theme.titleColor); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            paint.isFakeBoldText = true
            HyperUi.setStartIcon(this, HyperUi.ICON_SIDEBAR, HyperUi.HYPER_BLUE, 17f, 6f)
        }
        infoView = TextView(this).apply {
            text = "正在连接原生镜像侧边栏…"; setTextColor(theme.accentColor); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(titleView); addView(infoView) }
        val btnZoom = pillButton("宽屏易触", HyperUi.HYPER_BLUE, HyperUi.ICON_ZOOM) { btn ->
            wideTouchBoost = !wideTouchBoost
            btn.text = if (wideTouchBoost) "宽屏易触" else "原比例"
            HyperUi.setStartIcon(btn, if (wideTouchBoost) HyperUi.ICON_ZOOM else HyperUi.ICON_RATIO, Color.WHITE, 14f, 5f)
            val fill = if (wideTouchBoost) HyperUi.HYPER_BLUE else 0x33FFFFFF
            (btn.background as? LiquidGlassDrawable)?.baseFillColor = fill
            (btn.background as? GradientDrawable)?.setColor(fill)
            lastLayoutKey = Long.MIN_VALUE
            syncStageLayoutAndMeta()
        }
        headerView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(14), dp(10)); setBackgroundColor(theme.headerBgColor)
            addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnZoom)
            addView(pillButton("退出", HyperUi.CHIP_INACTIVE, HyperUi.ICON_CLOSE) { finish() })
        }
        return headerView
    }

    private fun buildWingSwitchRow(): LinearLayout {
        btnWingLeft = pillButton("左/上侧信息条", HyperUi.HYPER_BLUE, HyperUi.ICON_CHEVRON_LEFT) { selectWing(0) }
        btnWingRight = pillButton("右/下侧信息条", HyperUi.CHIP_INACTIVE, HyperUi.ICON_CHEVRON_RIGHT) { selectWing(1) }
        wingSwitchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(4)); visibility = View.GONE
            addView(btnWingLeft); addView(btnWingRight)
        }
        return wingSwitchRow
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildMirrorStage(): FrameLayout {
        textureView = TextureView(this).apply {
            surfaceTextureListener = this@SideControlActivity
            setOnTouchListener { _, ev -> handleMirrorTouch(ev) }
        }
        rippleOverlay = TouchRippleOverlay(this)
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        mirrorCardFrame = FrameLayout(this).apply {
            LiquidGlassDrawable.applyToView(
                view = this,
                fillColor = 0xEE0B1221.toInt(),
                cornerRadiusPx = dp(24).toFloat(),
                strokeWidthPx = dp(1).toFloat(),
                darkMode = true,
                clipOutline = true,
            )
            clipToOutline = true
            addView(textureView, match)
            addView(rippleOverlay, FrameLayout.LayoutParams(match))
        }
        emptyHintView = TextView(this).apply {
            text = "当前未处于「原生镜像 + 融合边栏」模式\n请在控制台开启「原生镜像」并选择边栏对齐后使用"
            setTextColor(0xFF94A3B8.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER; visibility = View.GONE
        }
        mirrorStage = FrameLayout(this).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(mirrorCardFrame, FrameLayout.LayoutParams(dp(260), dp(420), Gravity.CENTER))
            addView(emptyHintView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        return mirrorStage
    }

    private fun buildBottomBar(): HorizontalScrollView {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            addView(pillButton("切换边栏方位", HyperUi.HYPER_BLUE, HyperUi.ICON_SWAP) {
                submitTouchTask {
                    val cur = BridgeClient.getStatus().mirrorSideMode
                    val next = if (cur == BridgeProtocol.MirrorSideMode.ALIGN_LEFT) {
                        BridgeProtocol.MirrorSideMode.ALIGN_RIGHT
                    } else BridgeProtocol.MirrorSideMode.ALIGN_LEFT
                    BridgeClient.setMirrorSideMode(next)
                    BridgeService.refreshFromReceiver(this@SideControlActivity)
                }
            })
            addView(pillButton("卡片与小组件配置", HyperUi.CHIP_INACTIVE, HyperUi.ICON_SLIDERS) { openActivity(MainActivity::class.java) })
            addView(pillButton("大触摸板", HyperUi.CHIP_INACTIVE, HyperUi.ICON_TOUCHPAD) { openActivity(TouchpadActivity::class.java) })
            addView(pillButton("切回 DeX", HyperUi.CHIP_INACTIVE, HyperUi.ICON_DEX) {
                submitTouchTask {
                    BridgeClient.setMirrorMode(BridgeProtocol.MirrorMode.DEX)
                    BridgeService.refreshFromReceiver(this@SideControlActivity)
                    runOnUiThread {
                        Toast.makeText(this@SideControlActivity, "已切回 DeX 扩展桌面", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            })
        }
        barScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; setBackgroundColor(theme.headerBgColor); addView(bar)
        }
        return barScroll
    }

    private fun pillButton(text: String, bgColor: Int, iconKind: Int, onClick: (TextView) -> Unit) =
        TextView(this).apply {
            this.text = text; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER_VERTICAL; setSingleLine(true); setPadding(dp(12), dp(8), dp(12), dp(8))
            if (iconKind > 0) HyperUi.setStartIcon(this, iconKind, Color.WHITE, 14f, 5f)
            LiquidGlassDrawable.applyToView(
                view = this,
                fillColor = if (bgColor == HyperUi.CHIP_INACTIVE) 0x33FFFFFF else bgColor,
                cornerRadiusPx = dp(16).toFloat(),
                strokeWidthPx = dp(1).toFloat(),
                darkMode = true,
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
            setOnClickListener { onClick(this) }
        }

    private fun openActivity(cls: Class<*>) =
        startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))

    private fun selectWing(slot: Int) {
        wingSlot = slot
        val leftFill = if (wingSlot == 0) HyperUi.HYPER_BLUE else 0x33FFFFFF
        val rightFill = if (wingSlot == 1) HyperUi.HYPER_BLUE else 0x33FFFFFF
        (btnWingLeft.background as? LiquidGlassDrawable)?.baseFillColor = leftFill
        (btnWingRight.background as? LiquidGlassDrawable)?.baseFillColor = rightFill
        lastLayoutKey = Long.MIN_VALUE
        syncStageLayoutAndMeta()
    }

    private fun refreshCachedTheme() {
        if (!::rootView.isInitialized) return
        val latest = AppPrefs.getActiveMirrorTheme(this)
        if (latest == theme) return
        theme = latest
        rootView.setBackgroundColor(theme.bgColor)
        headerView.setBackgroundColor(theme.headerBgColor)
        barScroll.setBackgroundColor(theme.headerBgColor)
        titleView.setTextColor(theme.titleColor)
        infoView.setTextColor(theme.accentColor)
    }

    private fun syncStageLayoutAndMeta() {
        if (!::mirrorCardFrame.isInitialized) return
        val meta = BridgeService.getMirrorWingMeta(wingSlot)
        if (meta == null) {
            mirrorCardFrame.visibility = View.INVISIBLE
            emptyHintView.visibility = View.VISIBLE
            wingSwitchRow.visibility = View.GONE
            infoView.text = "未检测到融合边栏（请先进入原生镜像模式）"
            return
        }
        mirrorCardFrame.visibility = View.VISIBLE
        emptyHintView.visibility = View.GONE
        wingSwitchRow.visibility = if (meta.hasDual) View.VISIBLE else View.GONE
        if (!meta.hasDual && wingSlot != 0) wingSlot = 0

        val availW = max(dp(120), mirrorStage.width - mirrorStage.paddingLeft - mirrorStage.paddingRight)
        val availH = max(dp(120), mirrorStage.height - mirrorStage.paddingTop - mirrorStage.paddingBottom)
        val layoutKey = (((meta.width.toLong() * 31 + meta.height) * 31 + meta.x) * 31 + meta.y) * 31 +
            ((availW.toLong() * 31 + availH) * 31 + (if (wideTouchBoost) 2 else 0) + wingSlot)
        if (layoutKey == lastLayoutKey) return
        lastLayoutKey = layoutKey

        val fitScale = min(availW / meta.width.toFloat(), availH / meta.height.toFloat())
        var dstW = max(dp(80), (meta.width * fitScale).roundToInt())
        var dstH = max(dp(80), (meta.height * fitScale).roundToInt())
        if (wideTouchBoost) {
            if (meta.width < meta.height) dstW = min(availW, max(dstW, min(dp(340), (dstW * 1.55f).roundToInt())))
            else if (meta.width > meta.height) dstH = min(availH, max(dstH, min(dp(300), (dstH * 1.35f).roundToInt())))
        }
        val lp = mirrorCardFrame.layoutParams as FrameLayout.LayoutParams
        if (lp.width != dstW || lp.height != dstH) {
            lp.width = dstW; lp.height = dstH; lp.gravity = Gravity.CENTER; mirrorCardFrame.layoutParams = lp
        }
        infoView.text = "${BridgeLabels.mirrorSideModeLabel(meta.sideMode)} · ${meta.width}×${meta.height} @(${meta.x},${meta.y}) · 直接点按/滑动即控"
    }

    private fun renderMirrorFrame() {
        val surf = texSurface ?: return
        if (!surf.isValid || texW <= 0 || texH <= 0) return
        val meta = BridgeService.getMirrorWingMeta(wingSlot) ?: return

        if (!useSoftwareDraw) {
            var hw: Canvas? = null
            try {
                hw = surf.lockHardwareCanvas()
                hw.drawColor(theme.bgColor)
                BridgeService.renderMirrorWingToCanvas(hw, texW, texH, wingSlot)
                surf.unlockCanvasAndPost(hw); hw = null; return
            } catch (t: Throwable) {
                hw?.let { try { surf.unlockCanvasAndPost(it) } catch (e: Throwable) { Log.w(TAG, "unlock failed", e) } }
                Log.w(TAG, "HW canvas failed on mirror reflection; switching to software fallback", t)
                useSoftwareDraw = true
            }
        }

        try {
            val swScale = min(1f, min(900f / max(1, meta.width), 1440f / max(1, meta.height)))
            val targetW = max(1, (meta.width * swScale).roundToInt())
            val targetH = max(1, (meta.height * swScale).roundToInt())
            if (swBmp == null || swW != targetW || swH != targetH) {
                swBmp?.recycle()
                swBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                swCanvas = Canvas(swBmp!!)
                swW = targetW; swH = targetH
            }
            val bmp = swBmp ?: return
            val canvas = swCanvas ?: return
            bmp.eraseColor(theme.bgColor)
            BridgeService.renderMirrorWingToCanvas(canvas, swW, swH, wingSlot)
            val surfaceCanvas = surf.lockCanvas(null) ?: return
            try {
                swSrcRect.set(0, 0, swW, swH); swDstRect.set(0, 0, texW, texH)
                surfaceCanvas.drawBitmap(bmp, swSrcRect, swDstRect, null)
            } finally {
                surf.unlockCanvasAndPost(surfaceCanvas)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Software mirror reflection draw failed", t)
        }
    }

    private fun handleMirrorTouch(ev: MotionEvent?): Boolean {
        if (ev == null) return false
        val meta = BridgeService.getMirrorWingMeta(wingSlot)
        if (meta == null || textureView.width <= 0 || textureView.height <= 0) return false
        val act = ev.actionMasked
        if (act != MotionEvent.ACTION_DOWN && act != MotionEvent.ACTION_MOVE &&
            act != MotionEvent.ACTION_UP && act != MotionEvent.ACTION_CANCEL
        ) return true

        val sx = meta.x + (ev.x / textureView.width.toFloat()).coerceIn(0f, 1f) * meta.width
        val sy = meta.y + (ev.y / textureView.height.toFloat()).coerceIn(0f, 1f) * meta.height
        lastSideTouchX = sx; lastSideTouchY = sy
        sideTouchDown = act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE
        rippleOverlay.setTouchPoint(ev.x, ev.y, sideTouchDown)
        submitTouchTask { BridgeClient.sendSideTouch(act, sx, sy) }
        if (act == MotionEvent.ACTION_UP) {
            handler.postDelayed({ renderMirrorFrame() }, 60L)
            handler.postDelayed({ renderMirrorFrame() }, 180L)
        }
        return true
    }

    private fun cancelHeldSideTouchIfNeeded() {
        if (!sideTouchDown) return
        sideTouchDown = false
        val sx = lastSideTouchX; val sy = lastSideTouchY
        rippleOverlay.setTouchPoint(-1f, -1f, false)
        submitTouchTask { BridgeClient.sendSideTouch(MotionEvent.ACTION_CANCEL, sx, sy) }
    }

    private fun submitTouchTask(block: () -> Unit) {
        val exec = touchExec ?: return
        if (exec.isShutdown) return
        try { exec.execute(block) } catch (t: Throwable) { Log.w(TAG, "Rejected side-control IPC task", t) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig); refreshCachedTheme(); lastLayoutKey = Long.MIN_VALUE
    }

    override fun onResume() {
        super.onResume(); refreshCachedTheme(); running = true
        handler.removeCallbacks(frameTick); handler.post(frameTick)
    }

    override fun onPause() {
        cancelHeldSideTouchIfNeeded(); running = false; handler.removeCallbacks(frameTick); super.onPause()
    }

    override fun onDestroy() {
        cancelHeldSideTouchIfNeeded(); running = false; handler.removeCallbacksAndMessages(null)
        texSurface?.release(); texSurface = null
        swBmp?.recycle(); swBmp = null; swCanvas = null
        touchExec?.shutdown(); touchExec = null
        super.onDestroy()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        texSurface?.release(); texSurface = Surface(surface); texW = width; texH = height; renderMirrorFrame()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        texW = width; texH = height; renderMirrorFrame()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        texSurface?.release(); texSurface = null; return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private class TouchRippleOverlay(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x8838BDF8.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f
        }
        private var touchX = -1f
        private var touchY = -1f
        private var visible = false

        fun setTouchPoint(x: Float, y: Float, visible: Boolean) {
            touchX = x; touchY = y; this.visible = visible; invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (visible && touchX >= 0f && touchY >= 0f) canvas.drawCircle(touchX, touchY, 22f, paint)
        }
    }
}

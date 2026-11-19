package com.extbridge.helper

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.icu.text.Transliterator
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.util.Collections
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class LauncherActivity : Activity() {

    companion object {
        private val TOKEN_SPLIT_REGEX = Regex("[\\s\\p{Punct}]+")
        private var sPendingQuery = ""

        @Volatile
        private var sResumedInstance: LauncherActivity? = null

        @JvmStatic
        fun shouldConsumeRightClickAt(screenX: Int, screenY: Int): Boolean {
            val act = sResumedInstance
            if (act == null || act.isFinishing || act.isDestroyed) return false
            val dlg = act.mOpenDialog
            if (dlg != null && dlg.isShowing) return false
            val grid = act.mGrid
            if (grid == null || grid.visibility != View.VISIBLE || grid.width <= 0 || grid.height <= 0) {
                return false
            }
            val loc = IntArray(2)
            grid.getLocationOnScreen(loc)
            val lx = screenX - loc[0]
            val ly = screenY - loc[1]
            if (lx < 0 || ly < 0 || lx >= grid.width || ly >= grid.height) return false
            val pos = grid.pointToPosition(lx, ly)
            return pos != AdapterView.INVALID_POSITION && pos >= 0 && pos < act.mApps.size
        }

        private fun buildSearchBlob(label: String?, pkg: String?, hanLatin: Transliterator?): String {
            val sb = StringBuilder()
            val lbl = label?.lowercase(Locale.ROOT) ?: ""
            sb.append(lbl).append(' ').append(pkg?.lowercase(Locale.ROOT) ?: "")
            if (hanLatin != null && lbl.isNotEmpty()) {
                try {
                    val py = hanLatin.transliterate(lbl).lowercase(Locale.ROOT)
                    sb.append(' ').append(py).append(' ').append(py.replace(" ", ""))
                    val initials = StringBuilder()
                    for (tok in py.split(TOKEN_SPLIT_REGEX)) {
                        if (tok.isNotEmpty()) initials.append(tok[0])
                    }
                    sb.append(' ').append(initials)
                } catch (_: Throwable) {}
            }
            return sb.toString()
        }
    }

    private class AppEntry(
        val label: String,
        val pkg: String,
        val cls: String,
        val searchBlob: String,
        val icon: Drawable?,
    )

    private val mAllApps = ArrayList<AppEntry>()
    private val mApps = ArrayList<AppEntry>()
    private var mRoot: FrameLayout? = null
    private var mOverlayHost: FrameLayout? = null
    private var mWallpaperView: ImageView? = null
    private var mContent: LinearLayout? = null
    private var mSearchInput: EditText? = null
    private var mBtnClear: TextView? = null
    private var mTvEmpty: TextView? = null
    private var mGrid: GridView? = null
    private var mTitleView: TextView? = null
    private var mBtnModeTip: TextView? = null
    private var mBtnWpRefresh: TextView? = null
    private var mHeaderInfo: TextView? = null
    private var mSplitBanner: TextView? = null
    private var mAdapter: AppAdapter? = null
    private var mQuery = ""
    private var mLastWpBitmap: Bitmap? = null
    private var mAppliedDensityDpi = 0
    private var mCompactSplitMode = false
    private var mLastLayoutW = 0
    private var mLastLayoutH = 0
    private var mOpenDialog: AlertDialog? = null
    private var mAppModes: Map<String, Int> = HashMap()
    private var mWasStopped = false

    @Volatile
    private var mCachedStatus: BridgeClient.Status = BridgeClient.Status()

    private fun refreshStatusAsync() {
        Thread({
            val st = BridgeClient.getStatus()
            mCachedStatus = st
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    adjustColumns()
                    refreshHeader()
                }
            }
        }, "ExtLauncherSync").start()
    }

    private val mSyncRx = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshStatusAsync()
            refreshAppModesAsync()
        }
    }

    private val mBinderReadyCb = Runnable {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                refreshStatusAsync()
                loadWallpaperAsync(false)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mAppliedDensityDpi = resources.configuration.densityDpi
        if (BridgeClient.getService() == null) {
            BridgeClient.requestBinder(this)
        }
        BridgeService.ensureStarted(this)
        buildUi()
        if (sPendingQuery.isNotEmpty() && mSearchInput != null) {
            mQuery = sPendingQuery
            sPendingQuery = ""
            mSearchInput?.setText(mQuery)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(mSyncRx, IntentFilter("com.extbridge.helper.STATE_SYNC"), RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(mSyncRx, IntentFilter("com.extbridge.helper.STATE_SYNC"))
            }
        } catch (_: Throwable) {}
        loadAppsAsync()
        loadWallpaperAsync(false)
        BridgeClient.setOnBinderReady(mBinderReadyCb)
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                ) { handleDesktopBack() }
            } catch (_: Throwable) {}
        }
    }

    private fun handleDesktopBack() {
        if (mQuery.isNotEmpty()) {
            clearSearch()
            return
        }
        val searchInput = mSearchInput
        if (searchInput != null && searchInput.hasFocus()) {
            searchInput.clearFocus()
            mRoot?.requestFocus()
        }
        // Root DeX desktop: never finish() LauncherActivity on Back
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        handleDesktopBack()
    }

    override fun onStart() {
        super.onStart()
        BridgeService.setDockHidden(false)
        BridgeService.setLauncherOverlayHost(mOverlayHost, display?.displayId ?: -1)
    }

    override fun onResume() {
        super.onResume()
        sResumedInstance = this
        val content = mContent
        if (mWasStopped && content != null) {
            mWasStopped = false
            content.animate().cancel()
            content.scaleX = 0.965f
            content.scaleY = 0.965f
            content.alpha = 0.84f
            content.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
        adjustColumns()
        refreshHeader()
        refreshStatusAsync()
        loadWallpaperAsync(false)
        refreshAppModesAsync()
    }

    override fun onStop() {
        mWasStopped = true
        BridgeService.clearLauncherOverlayHost(mOverlayHost)
        super.onStop()
    }

    override fun onDestroy() {
        if (sResumedInstance === this) sResumedInstance = null
        mOpenDialog?.let { dlg ->
            try { dlg.dismiss() } catch (_: Throwable) {}
        }
        mOpenDialog = null
        BridgeClient.removeOnBinderReady(mBinderReadyCb)
        BridgeService.clearLauncherOverlayHost(mOverlayHost)
        try { unregisterReceiver(mSyncRx) } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            BridgeService.dismissPanelOnHostTouch(ev.x, ev.y)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (BridgeClient.getService() == null) {
            BridgeClient.requestBinder(this)
        }
    }

    override fun onPause() {
        if (sResumedInstance === this) sResumedInstance = null
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.densityDpi != 0 && newConfig.densityDpi != mAppliedDensityDpi) {
            sPendingQuery = mQuery
            recreate()
            return
        }
        adjustColumns()
        refreshHeader()
        refreshStatusAsync()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && mQuery.isNotEmpty()) {
            clearSearch()
            return true
        }
        val searchInput = mSearchInput
        if (searchInput != null && !searchInput.hasFocus()) {
            val isSlash = keyCode == KeyEvent.KEYCODE_SLASH
            val isCtrlF = keyCode == KeyEvent.KEYCODE_F && event != null && event.isCtrlPressed
            val ch = event?.unicodeChar ?: 0
            if (isSlash || isCtrlF) {
                searchInput.requestFocus()
                return true
            }
            if (ch >= 0x20 && !Character.isISOControl(ch) && ch < 0xE000) {
                searchInput.requestFocus()
                searchInput.append(ch.toChar().toString())
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0B1120.toInt(), 0xFF16213E.toInt(), 0xFF0F172A.toInt()),
            )
        }
        mRoot = root

        // Layer 1: Original device wallpaper with GPU frosted-glass blur
        val wallpaperView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            scaleX = 1.08f
            scaleY = 1.08f
            if (Build.VERSION.SDK_INT >= 31) {
                setRenderEffect(RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP))
            }
        }
        mWallpaperView = wallpaperView
        root.addView(
            wallpaperView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        // Layer 2: Soft frosted dark scrim so icons & text remain high contrast on bright wallpapers
        val scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x480A101D, 0x5E0B1120, 0x78070B14),
            )
        }
        root.addView(
            scrim,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        // Layer 3: Foreground desktop layout
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(14), dp(26), dp(88))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        mContent = content

        // Top Bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(12))
        }

        val titleView = TextView(this).apply {
            text = "ExtBridge 扩展桌面"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            paint.isFakeBoldText = true
            setShadowLayer(dpF(4f), 0f, dpF(1.5f), 0xAA000000.toInt())
        }
        mTitleView = titleView
        top.addView(
            titleView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(12)
            },
        )

        val splitBanner = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(0xCC0D84FF.toInt())
                cornerRadius = dpF(15f)
                setStroke(1, 0x66FFFFFF)
            }
            visibility = View.GONE
        }
        HyperUi.setStartIcon(splitBanner, HyperUi.ICON_SPLIT, Color.WHITE, 13f, 5f)
        mSplitBanner = splitBanner
        top.addView(
            splitBanner,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            },
        )

        // Frosted glass Search box container (HyperOS capsule)
        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(0x72161922)
                cornerRadius = dpF(18f)
                setStroke(1, 0x38FFFFFF)
            }
        }

        val searchIcon = ImageView(this).apply {
            setImageDrawable(HyperUi.icon(this@LauncherActivity, HyperUi.ICON_SEARCH, 0xFFCBD5E1.toInt(), 14f))
        }
        searchBox.addView(
            searchIcon,
            LinearLayout.LayoutParams(dp(15), dp(15)).apply {
                rightMargin = dp(7)
            },
        )

        val searchInput = EditText(this).apply {
            hint = "搜索应用 / 拼音首字母 / 包名 (回车启动)"
            setHintTextColor(0xFFAAB8CC.toInt())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f)
            setSingleLine(true)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(2), dp(3), dp(6), dp(3))
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    applyFilter(s?.toString() ?: "")
                }
            })
            setOnEditorActionListener { _, actionId, ev ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_UP)
                ) {
                    if (mApps.isNotEmpty()) {
                        launchApp(mApps[0])
                    }
                    true
                } else {
                    false
                }
            }
        }
        mSearchInput = searchInput
        searchBox.addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val btnClear = TextView(this).apply {
            text = "清空"
            setTextColor(0xFFCBD5E1.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            visibility = View.GONE
            setOnClickListener { clearSearch() }
        }
        mBtnClear = btnClear
        searchBox.addView(btnClear)

        top.addView(
            searchBox,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(12)
            },
        )

        val btnModeTip = TextView(this).apply {
            text = "应用规则"
            setTextColor(0xFFE2E8F0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(5), dp(11), dp(5))
            background = GradientDrawable().apply {
                setColor(0x66161922)
                cornerRadius = dpF(16f)
                setStroke(1, 0x38FFFFFF)
            }
            setOnClickListener { showAllAppModesPicker() }
        }
        HyperUi.setStartIcon(btnModeTip, HyperUi.ICON_SLIDERS, 0xFF7AC0FF.toInt(), 13f, 5f)
        mBtnModeTip = btnModeTip
        top.addView(
            btnModeTip,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            },
        )

        val btnWpRefresh = TextView(this).apply {
            text = "同步壁纸"
            setTextColor(0xFFE2E8F0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(5), dp(11), dp(5))
            background = GradientDrawable().apply {
                setColor(0x66161922)
                cornerRadius = dpF(16f)
                setStroke(1, 0x38FFFFFF)
            }
            setOnClickListener {
                loadWallpaperAsync(true)
                Toast.makeText(this@LauncherActivity, "正在同步主屏壁纸…", Toast.LENGTH_SHORT).show()
            }
        }
        HyperUi.setStartIcon(btnWpRefresh, HyperUi.ICON_WALLPAPER, 0xFFE2E8F0.toInt(), 13f, 5f)
        mBtnWpRefresh = btnWpRefresh
        top.addView(
            btnWpRefresh,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(10)
            },
        )

        val headerInfo = TextView(this).apply {
            setTextColor(0xFFE0F2FE.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
            setPadding(dp(11), dp(5), dp(11), dp(5))
            background = GradientDrawable().apply {
                setColor(0x55121A2A)
                cornerRadius = dpF(15f)
                setStroke(1, 0x3AFFFFFF)
            }
        }
        mHeaderInfo = headerInfo
        top.addView(headerInfo)

        content.addView(top)

        val tvEmpty = TextView(this).apply {
            text = "未找到匹配的应用，可尝试输入中文、拼音首字母或英文包名"
            setTextColor(0xFFE2E8F0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f)
            setShadowLayer(dpF(3f), 0f, dpF(1.2f), 0xCC000000.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        mTvEmpty = tvEmpty
        content.addView(tvEmpty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val adapter = AppAdapter()
        mAdapter = adapter
        val grid = GridView(this).apply {
            verticalSpacing = dp(12)
            horizontalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ ->
                if (position in mApps.indices) {
                    launchApp(mApps[position])
                }
            }
            setOnItemLongClickListener { _, _, position, _ ->
                if (position in mApps.indices) {
                    showAppModeDialog(mApps[position])
                    true
                } else {
                    false
                }
            }
        }
        mGrid = grid
        content.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(
            content,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        // Layer 4: In-Activity host for Dock / Flyout / Cursor in Scheme A (immune to global Settings overlay hiding)
        val overlayHost = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        mOverlayHost = overlayHost
        root.addView(
            overlayHost,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (abs(w - mLastLayoutW) > 6 || abs(h - mLastLayoutH) > 6) {
                mLastLayoutW = w
                mLastLayoutH = h
                root.post {
                    adjustColumns()
                    refreshHeader()
                    refreshStatusAsync()
                }
            }
        }
        setContentView(root)
        content.requestFocus()
        adjustColumns()
        refreshHeader()
        refreshStatusAsync()
    }

    private fun loadWallpaperAsync(force: Boolean) {
        Thread {
            val bmp = BridgeClient.getWallpaperBitmap(force)
            if (bmp != null && (force || bmp !== mLastWpBitmap)) {
                mLastWpBitmap = bmp
                runOnUiThread {
                    val wpView = mWallpaperView
                    if (wpView != null && !bmp.isRecycled) {
                        wpView.setImageBitmap(bmp)
                    }
                }
            }
        }.start()
    }

    private fun clearSearch() {
        mSearchInput?.setText("")
        mRoot?.requestFocus()
    }

    private fun applyFilter(raw: String?) {
        mQuery = raw?.trim()?.lowercase(Locale.ROOT) ?: ""
        mBtnClear?.visibility = if (mQuery.isEmpty()) View.GONE else View.VISIBLE
        mApps.clear()
        if (mQuery.isEmpty()) {
            mApps.addAll(mAllApps)
        } else {
            for (e in mAllApps) {
                if (e.searchBlob.contains(mQuery)) {
                    mApps.add(e)
                }
            }
        }
        val empty = mQuery.isNotEmpty() && mApps.isEmpty()
        mTvEmpty?.visibility = if (empty) View.VISIBLE else View.GONE
        mGrid?.visibility = if (empty) View.GONE else View.VISIBLE
        mAdapter?.notifyDataSetChanged()
    }

    private fun adjustColumns() {
        val grid = mGrid ?: return
        val dm = resources.displayMetrics
        val root = mRoot
        val winW = if (root != null && root.width > 0) root.width else dm.widthPixels
        val winH = if (root != null && root.height > 0) root.height else dm.heightPixels
        val st = mCachedStatus
        val singleEmpty = st.connected && st.splitActive == 1 &&
            (st.splitSlot0Pkg.isEmpty() != st.splitSlot1Pkg.isEmpty())
        val compact = singleEmpty ||
            (st.connected && (winW < st.virtW * 0.88f || winH < st.virtH * 0.88f))
        val density = max(0.5f, dm.density)
        val dpW = winW / density - if (compact) 24f else 52f
        val cols = if (compact) {
            max(3, min(8, (dpW / 108f).roundToInt()))
        } else {
            max(5, min(14, (dpW / 165f).roundToInt()))
        }
        grid.numColumns = cols
        if (compact != mCompactSplitMode) {
            mCompactSplitMode = compact
            mContent?.setPadding(
                dp(if (compact) 12 else 26),
                dp(if (compact) 10 else 14),
                dp(if (compact) 12 else 26),
                dp(if (compact) 60 else 62),
            )
            grid.verticalSpacing = dp(if (compact) 8 else 12)
            grid.horizontalSpacing = dp(if (compact) 5 else 8)
            mAdapter?.notifyDataSetChanged()
        }
    }

    private fun refreshHeader() {
        val headerInfo = mHeaderInfo ?: return
        val st = mCachedStatus
        val dm = resources.displayMetrics
        val root = mRoot
        val winW = if (root != null && root.width > 0) root.width else dm.widthPixels
        val winH = if (root != null && root.height > 0) root.height else dm.heightPixels
        val singleEmpty = st.connected && st.splitActive == 1 &&
            (st.splitSlot0Pkg.isEmpty() != st.splitSlot1Pkg.isEmpty())
        val compact = singleEmpty ||
            (st.connected && (winW < st.virtW * 0.88f || winH < st.virtH * 0.88f))

        mTitleView?.let {
            it.text = if (compact) "DeX 桌面" else "ExtBridge 扩展桌面"
            it.setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (compact) 13f else 15f)
        }
        mBtnModeTip?.visibility = if (compact) View.GONE else View.VISIBLE
        mBtnWpRefresh?.visibility = if (compact) View.GONE else View.VISIBLE
        headerInfo.visibility = if (compact) View.GONE else View.VISIBLE

        mSplitBanner?.let { banner ->
            if (singleEmpty) {
                val port = st.virtH > st.virtW
                val slot0Empty = st.splitSlot0Pkg.isEmpty()
                val side = if (port) {
                    if (slot0Empty) "上屏" else "下屏"
                } else {
                    if (slot0Empty) "左屏" else "右屏"
                }
                banner.text = "点选填入$side"
                banner.visibility = View.VISIBLE
            } else {
                banner.visibility = View.GONE
            }
        }
        mSearchInput?.hint = if (compact) "搜索并点击加入分屏..." else "搜索应用名 / 拼音首字母 / 包名 (回车打开)"

        if (st.connected) {
            headerInfo.text = "${st.virtW}×${st.virtH} · ${st.virtDpi}dpi · " +
                "${st.virtFps.roundToInt()}Hz · ${if (st.inputToExt == 1) "扩展键鼠" else "平板键鼠"}"
        } else {
            headerInfo.text = "Display #${display?.displayId ?: 0}"
        }
    }

    private fun loadAppsAsync() {
        Thread {
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val ris = pm.queryIntentActivities(q, 0)
            Collections.sort(ris, ResolveInfo.DisplayNameComparator(pm))

            val hanLatin = try {
                Transliterator.getInstance("Han-Latin; NFD; [:Nonspacing Mark:] Remove; NFC")
            } catch (_: Throwable) {
                null
            }

            val loaded = ArrayList<AppEntry>(ris.size)
            for (ri in ris) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                val cls = ai.name
                if (packageName == pkg && LauncherActivity::class.java.name == cls) continue
                val label = ri.loadLabel(pm).toString()
                val searchBlob = buildSearchBlob(label, pkg, hanLatin)
                val icon = try {
                    ri.loadIcon(pm)
                } catch (_: Throwable) {
                    null
                }
                loaded.add(AppEntry(label, pkg, cls, searchBlob, icon))
            }
            runOnUiThread {
                mAllApps.clear()
                mAllApps.addAll(loaded)
                applyFilter(mQuery)
            }
        }.start()
    }

    private fun launchApp(e: AppEntry?) {
        if (e == null) return
        val dispId = display?.displayId ?: 0
        val routeSettingsToTablet = !NavAssistService.isRunning() &&
            ("com.android.settings" == e.pkg || "com.miui.securitycenter" == e.pkg)
        if (routeSettingsToTablet) {
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(e.pkg, e.cls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                val opts = ActivityOptions.makeBasic()
                opts.setLaunchDisplayId(0)
                Toast.makeText(this, "未开启无障碍：已在平板打开设置", Toast.LENGTH_SHORT).show()
                startActivity(intent, opts.toBundle())
            } catch (_: Throwable) {}
            return
        }
        Thread({
            try {
                if (dispId > 0) {
                    val st = BridgeClient.getStatus()
                    mCachedStatus = st
                    if (st.connected && st.splitActive == 1) {
                        val s0Empty = st.splitSlot0Pkg.isEmpty()
                        val s1Empty = st.splitSlot1Pkg.isEmpty()
                        if (s0Empty != s1Empty) {
                            BridgeService.performAssignSplitFromUi(if (s0Empty) 0 else 1, -1, e.pkg, e.cls)
                            return@Thread
                        }
                    }
                    // Prefer v4.1 adaptive single-app / per-app remembered window mode launch via daemon.
                    if (BridgeClient.launchInWindowMode(e.pkg, e.cls, BridgeClient.WIN_MODE_AUTO, 0, 0, 0, 0)) {
                        return@Thread
                    }
                    // Fallback to legacy daemon shell ATM launch on external VirtualDisplay.
                    if (BridgeClient.launchComponent(e.pkg, e.cls)) {
                        return@Thread
                    }
                    val mode = BridgeClient.prepareAppLaunch(e.pkg)
                    runOnUiThread {
                        try {
                            val intent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_LAUNCHER)
                                component = ComponentName(e.pkg, e.cls)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                            }
                            val opts = ActivityOptions.makeBasic()
                            opts.setLaunchDisplayId(dispId)
                            try {
                                if (mode == BridgeClient.APP_MODE_LAND_FULL) {
                                    opts.javaClass.getMethod("setLaunchWindowingMode", Int::class.java).invoke(opts, 1)
                                }
                            } catch (_: Throwable) {}
                            startActivity(intent, opts.toBundle())
                        } catch (_: Throwable) {
                            Thread { BridgeClient.launchComponent(e.pkg, e.cls) }.start()
                        }
                    }
                    return@Thread
                }
                runOnUiThread {
                    try {
                        val intent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            component = ComponentName(e.pkg, e.cls)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        }
                        startActivity(intent)
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {
                BridgeClient.launchComponent(e.pkg, e.cls)
            }
        }, "ExtAppLaunch").start()
    }

    private fun refreshAppModesAsync() {
        Thread {
            val modes = BridgeClient.getAllAppModes()
            runOnUiThread {
                mAppModes = modes
                mAdapter?.notifyDataSetChanged()
            }
        }.start()
    }

    private fun getAppModeLocal(pkg: String): Int =
        mAppModes[pkg] ?: BridgeClient.APP_MODE_DEFAULT

    private fun showAllAppModesPicker() {
        if (mAllApps.isEmpty()) {
            Toast.makeText(this, "应用列表加载中...", Toast.LENGTH_SHORT).show()
            return
        }
        val sorted = ArrayList(mAllApps)
        sorted.sortWith { a, b ->
            val ma = if (getAppModeLocal(a.pkg) > 0) 0 else 1
            val mb = if (getAppModeLocal(b.pkg) > 0) 0 else 1
            if (ma != mb) ma - mb else a.label.compareTo(b.label, ignoreCase = true)
        }
        val items = Array(sorted.size + 1) { idx ->
            if (idx == 0) {
                "全部恢复【跟随系统默认】（清除所有自定义兼容覆盖）"
            } else {
                val ae = sorted[idx - 1]
                val m = getAppModeLocal(ae.pkg)
                "${ae.label}  [${BridgeClient.appModeLabel(m)}]"
            }
        }
        mOpenDialog = AlertDialog.Builder(this)
            .setTitle("分应用显示规则（可在桌面图标长按或鼠标右键设置）")
            .setItems(items) { _, which ->
                if (which == 0) {
                    Thread {
                        BridgeClient.resetAllAppModes()
                        val latest = BridgeClient.getAllAppModes()
                        runOnUiThread {
                            mAppModes = latest
                            mAdapter?.notifyDataSetChanged()
                            Toast.makeText(this, "已全部恢复系统默认模式（重启对应应用生效）", Toast.LENGTH_SHORT).show()
                        }
                    }.start()
                } else if (which - 1 < sorted.size) {
                    showAppModeDialog(sorted[which - 1])
                }
            }
            .setNegativeButton("关闭", null)
            .setOnDismissListener { d ->
                if (mOpenDialog === d) mOpenDialog = null
            }
            .show()
    }

    private fun showAppModeDialog(e: AppEntry?) {
        if (e == null) return
        val cur = getAppModeLocal(e.pkg)
        val labels = arrayOf(
            "以居中宽幅打开（默认黄金比例）",
            "以自由窗口打开（可移动与自由缩放）",
            "加入 2/3/4 分屏…",
            "重载应用 DPI 并重启（修复字体偏大）",
            (if (cur == 0) "● " else "") + "显示规则：跟随系统默认（推荐微信 / 夸克）",
            (if (cur == 1) "● " else "") + "显示规则：强制横屏全屏（适合短剧/视频 App）",
            (if (cur == 2) "● " else "") + "显示规则：竖屏居中兼容（适合固定比例竖屏 App）",
        )
        mOpenDialog = AlertDialog.Builder(this)
            .setTitle("${e.label}\n${e.pkg}")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> {
                        Toast.makeText(this, "正在以居中宽幅打开：${e.label}", Toast.LENGTH_SHORT).show()
                        Thread {
                            val ok = BridgeClient.launchInWindowMode(
                                e.pkg,
                                e.cls,
                                BridgeClient.WIN_MODE_ADAPTIVE_SINGLE,
                                0, 0, 0, 0,
                            )
                            if (!ok) BridgeClient.launchComponent(e.pkg, e.cls)
                            BridgeService.refreshFromReceiver(this@LauncherActivity)
                        }.start()
                    }
                    1 -> {
                        Toast.makeText(this, "正在以自由窗口打开：${e.label}", Toast.LENGTH_SHORT).show()
                        Thread {
                            val ok = BridgeClient.launchInWindowMode(
                                e.pkg,
                                e.cls,
                                BridgeClient.WIN_MODE_FREEFORM,
                                0, 0, 0, 0,
                            )
                            if (!ok) BridgeClient.launchComponent(e.pkg, e.cls)
                            BridgeService.refreshFromReceiver(this@LauncherActivity)
                        }.start()
                    }
                    2 -> showJoinMultiSplitDialog(e)
                    3 -> {
                        Toast.makeText(this, "正在重载 DPI 并重启：${e.label}", Toast.LENGTH_SHORT).show()
                        Thread {
                            BridgeClient.windowLifecycleAction(-1, e.pkg, BridgeClient.WIN_ACTION_RELOAD_DPI)
                            BridgeService.refreshFromReceiver(this@LauncherActivity)
                        }.start()
                    }
                    else -> {
                        val chosen = (which - 4).coerceIn(0, 2)
                        Toast.makeText(this, "正在应用【${BridgeClient.appModeLabel(chosen)}】并重启应用...", Toast.LENGTH_SHORT).show()
                        Thread {
                            BridgeClient.setAppMode(e.pkg, chosen, true)
                            val latest = BridgeClient.getAllAppModes()
                            runOnUiThread {
                                mAppModes = latest
                                mAdapter?.notifyDataSetChanged()
                            }
                        }.start()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { d ->
                if (mOpenDialog === d) mOpenDialog = null
            }
            .show()
    }

    private fun showJoinMultiSplitDialog(e: AppEntry) {
        val dm = resources.displayMetrics
        val port = dm.heightPixels > dm.widthPixels
        val slot0Label = if (port) "2 分屏：放入上屏" else "2 分屏：放入左屏"
        val slot1Label = if (port) "2 分屏：放入下屏" else "2 分屏：放入右屏"
        val items = arrayOf(
            slot0Label,
            slot1Label,
            "1+2 三分屏（左主 + 右上下）",
            "三列分屏（左 / 中 / 右）",
            "2×2 四宫格（4 应用同屏）",
        )
        mOpenDialog = AlertDialog.Builder(this)
            .setTitle("加入分屏 · ${e.label}")
            .setItems(items) { _, which ->
                when (which) {
                    0, 1 -> {
                        val sideTxt = if (port) {
                            if (which == 0) "上屏" else "下屏"
                        } else {
                            if (which == 0) "左屏" else "右屏"
                        }
                        Toast.makeText(this, "已放入分屏【$sideTxt】：${e.label}", Toast.LENGTH_SHORT).show()
                        BridgeService.performAssignSplitFromUi(which, -1, e.pkg, e.cls)
                    }
                    2 -> joinMultiTilingTopology(e, BridgeClient.LAYOUT_SPLIT_3_MASTER, 3, listOf(50, 50))
                    3 -> joinMultiTilingTopology(e, BridgeClient.LAYOUT_SPLIT_3_COLS, 3, listOf(33, 34, 33))
                    4 -> joinMultiTilingTopology(e, BridgeClient.LAYOUT_GRID_2X2, 4, listOf(50, 50))
                }
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { d ->
                if (mOpenDialog === d) mOpenDialog = null
            }
            .show()
    }

    private fun joinMultiTilingTopology(
        e: AppEntry,
        topology: String,
        maxSlots: Int,
        defaultRatios: List<Int>,
    ) {
        Toast.makeText(
            this,
            "正在加入【${BridgeClient.layoutModeLabel(topology)}】：${e.label}",
            Toast.LENGTH_SHORT,
        ).show()
        Thread {
            val pkgs = LinkedHashSet<String>()
            val snap = BridgeClient.getManagedWindows()
            for (w in snap.windows) {
                if (!w.minimized && w.pkg.isNotBlank() && w.pkg != packageName && w.pkg != "android") {
                    pkgs.add(w.pkg)
                }
            }
            for (t in BridgeClient.getTasks()) {
                if (t.pkg.isNotBlank() && t.pkg != packageName && t.pkg != "android") {
                    pkgs.add(t.pkg)
                }
            }
            // Ensure chosen app is always included in the active slot list
            val ordered = ArrayList<String>(maxSlots)
            ordered.add(e.pkg)
            for (p in pkgs) {
                if (p != e.pkg && ordered.size < maxSlots) {
                    ordered.add(p)
                }
            }
            BridgeClient.setTilingLayout(topology, ordered, defaultRatios)
            BridgeService.refreshFromReceiver(this@LauncherActivity)
        }.start()
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = mApps.size
        override fun getItem(pos: Int): Any = mApps[pos]
        override fun getItemId(pos: Int): Long = pos.toLong()

        @SuppressLint("ClickableViewAccessibility")
        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val card: LinearLayout
            val iv: ImageView
            val tv: TextView
            val badge: TextView
            if (convertView is LinearLayout) {
                card = convertView
                iv = card.getChildAt(0) as ImageView
                tv = card.getChildAt(1) as TextView
                badge = card.getChildAt(2) as TextView
            } else {
                card = LinearLayout(this@LauncherActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                }
                val hoverBg = GradientDrawable().apply {
                    setColor(0x36FFFFFF)
                    cornerRadius = dpF(14f)
                }
                val sld = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), hoverBg)
                    addState(intArrayOf(android.R.attr.state_hovered), hoverBg)
                    addState(intArrayOf(android.R.attr.state_focused), hoverBg)
                    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                }
                card.background = sld

                iv = ImageView(this@LauncherActivity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                card.addView(
                    iv,
                    LinearLayout.LayoutParams(dp(54), dp(54)).apply {
                        bottomMargin = dp(6)
                    },
                )

                tv = TextView(this@LauncherActivity).apply {
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
                    setShadowLayer(dpF(4f), 0f, dpF(1.5f), 0xE6000000.toInt())
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                card.addView(
                    tv,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )

                badge = TextView(this@LauncherActivity).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9.5f)
                    setPadding(dp(5), dp(1), dp(5), dp(1))
                    gravity = Gravity.CENTER
                }
                card.addView(
                    badge,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(3)
                    },
                )
            }

            val e = mApps[pos]
            val compact = mCompactSplitMode
            val iconPx = dp(if (compact) 40 else 54)
            card.setPadding(
                dp(if (compact) 4 else 6),
                dp(if (compact) 5 else 8),
                dp(if (compact) 4 else 6),
                dp(if (compact) 5 else 8),
            )
            val curIvLp = iv.layoutParams
            if (curIvLp is LinearLayout.LayoutParams && curIvLp.width != iconPx) {
                curIvLp.width = iconPx
                curIvLp.height = iconPx
                curIvLp.bottomMargin = dp(if (compact) 4 else 6)
                iv.layoutParams = curIvLp
            }
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (compact) 10.5f else 11.5f)
            badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (compact) 8.5f else 9.5f)
            iv.setImageDrawable(e.icon)
            tv.text = e.label
            val mode = getAppModeLocal(e.pkg)
            if (mode > 0) {
                badge.visibility = View.VISIBLE
                badge.text = BridgeClient.appModeBadge(mode)
                val bgColor = when (mode) {
                    BridgeClient.APP_MODE_LAND_FULL -> 0xCC0284C7.toInt()
                    BridgeClient.APP_MODE_PORT_COMPAT -> 0xCCD97706.toInt()
                    else -> 0xCC7C3AED.toInt()
                }
                badge.background = GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = dpF(6f)
                }
                badge.setTextColor(Color.WHITE)
            } else {
                badge.visibility = View.GONE
            }
            card.setOnTouchListener { _, ev ->
                if (ev != null && ev.actionMasked == MotionEvent.ACTION_DOWN &&
                    (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
                ) {
                    val dlg = mOpenDialog
                    if (dlg == null || !dlg.isShowing) {
                        showAppModeDialog(e)
                    }
                    true
                } else {
                    false
                }
            }
            card.setOnGenericMotionListener { _, ev ->
                if (ev != null && ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                    ev.actionButton == MotionEvent.BUTTON_SECONDARY
                ) {
                    val dlg = mOpenDialog
                    if (dlg == null || !dlg.isShowing) {
                        showAppModeDialog(e)
                    }
                    true
                } else {
                    false
                }
            }
            return card
        }
    }
}

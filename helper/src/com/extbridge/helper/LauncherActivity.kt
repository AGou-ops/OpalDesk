package com.extbridge.helper

import android.app.Activity
import android.app.ActivityOptions
import android.content.*
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.*
import android.window.OnBackInvokedDispatcher
import com.extbridge.helper.launcher.LauncherAppDialogs
import com.extbridge.helper.launcher.LauncherAppEntry
import com.extbridge.helper.launcher.LauncherAppRepository
import com.extbridge.helper.launcher.LauncherGridAdapter
import com.extbridge.helper.protocol.BridgeProtocol.AppMode
import com.extbridge.helper.protocol.BridgeProtocol.HelperActions
import com.extbridge.helper.protocol.BridgeProtocol.WindowMode
import com.extbridge.helper.protocol.DaemonStatus
import com.extbridge.helper.service.DisplayLaunchHelper
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.math.*

class LauncherActivity : Activity() {

    companion object {
        private const val TAG = "ExtLauncher"
        private var sPendingQuery = ""

        @Volatile
        private var sResumedInstance: LauncherActivity? = null

        fun shouldConsumeRightClickAt(screenX: Int, screenY: Int): Boolean {
            val act = sResumedInstance
            if (act == null || act.isFinishing || act.isDestroyed || act.dialogs.isShowing) return false
            val grid = act.gridView
            if (grid.visibility != View.VISIBLE || grid.width <= 0 || grid.height <= 0) return false
            val loc = IntArray(2)
            grid.getLocationOnScreen(loc)
            val lx = screenX - loc[0]
            val ly = screenY - loc[1]
            if (lx < 0 || ly < 0 || lx >= grid.width || ly >= grid.height) return false
            val pos = grid.pointToPosition(lx, ly)
            return pos != AdapterView.INVALID_POSITION && pos in 0 until act.gridAdapter.itemCount
        }
    }

    private data class DesktopLayoutMode(val singleEmptySlot: Boolean, val compact: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var rootView: FrameLayout
    private lateinit var overlayHost: FrameLayout
    private lateinit var wallpaperView: ImageView
    private lateinit var contentView: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var btnClear: TextView
    private lateinit var emptyView: TextView
    private lateinit var gridView: GridView
    private lateinit var titleView: TextView
    private lateinit var btnModeTip: TextView
    private lateinit var btnWpRefresh: TextView
    private lateinit var headerInfo: TextView
    private lateinit var splitBanner: TextView
    private lateinit var gridAdapter: LauncherGridAdapter
    private lateinit var dialogs: LauncherAppDialogs

    private var allApps: List<LauncherAppEntry> = emptyList()
    private var appModes: Map<String, Int> = emptyMap()
    private var query = ""
    private var lastWpBitmap: Bitmap? = null
    private var appliedDensityDpi = 0
    private var compactSplitMode = false
    private var lastLayoutW = 0
    private var lastLayoutH = 0
    private var wasStopped = false

    @Volatile
    private var cachedStatus: DaemonStatus = DaemonStatus()

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshStatusAsync(); refreshAppModesAsync()
        }
    }

    private val binderReadyCb = Runnable {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) { refreshStatusAsync(); loadWallpaperAsync(false) }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun dpF(v: Float): Float = v * resources.displayMetrics.density
    private fun runIo(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try { block() } catch (t: Throwable) { Log.w(TAG, "Launcher IO task failed", t) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedDensityDpi = resources.configuration.densityDpi
        if (BridgeClient.getService() == null) BridgeClient.requestBinder(this)
        BridgeService.ensureStarted(this)

        dialogs = LauncherAppDialogs(this, ::runIo) { latest ->
            appModes = latest; gridAdapter.updateModes(latest)
        }
        buildUi()
        if (sPendingQuery.isNotEmpty()) {
            query = sPendingQuery; sPendingQuery = ""; searchInput.setText(query)
        }
        registerSyncReceiver()
        loadAppsAsync()
        loadWallpaperAsync(false)
        BridgeClient.setOnBinderReady(binderReadyCb)
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleDesktopBack() }
            } catch (t: Throwable) { Log.w(TAG, "Failed to register OnBackInvokedCallback", t) }
        }
    }

    private fun registerSyncReceiver() {
        try {
            val filter = IntentFilter(HelperActions.STATE_SYNC)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(syncReceiver, filter, RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(syncReceiver, filter)
        } catch (t: Throwable) { Log.w(TAG, "Failed to register STATE_SYNC receiver", t) }
    }

    private fun handleDesktopBack() {
        if (query.isNotEmpty()) { clearSearch(); return }
        if (searchInput.hasFocus()) { searchInput.clearFocus(); rootView.requestFocus() }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = handleDesktopBack()

    override fun onStart() {
        super.onStart()
        BridgeService.setDockHidden(false)
        BridgeService.setLauncherOverlayHost(overlayHost, display?.displayId ?: -1)
    }

    override fun onResume() {
        super.onResume()
        sResumedInstance = this
        if (wasStopped) {
            wasStopped = false
            contentView.animate().cancel()
            contentView.scaleX = 0.965f; contentView.scaleY = 0.965f; contentView.alpha = 0.84f
            contentView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220L)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
        syncDesktopLayout(); refreshStatusAsync(); loadWallpaperAsync(false); refreshAppModesAsync()
    }

    override fun onPause() { if (sResumedInstance === this) sResumedInstance = null; super.onPause() }
    override fun onStop() { wasStopped = true; BridgeService.clearLauncherOverlayHost(overlayHost); super.onStop() }

    override fun onDestroy() {
        if (sResumedInstance === this) sResumedInstance = null
        dialogs.dismissAll(); scope.cancel()
        BridgeClient.removeOnBinderReady(binderReadyCb)
        BridgeService.clearLauncherOverlayHost(overlayHost)
        try { unregisterReceiver(syncReceiver) } catch (t: Throwable) { Log.d(TAG, "unregister skipped: ${t.message}") }
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.actionMasked == MotionEvent.ACTION_DOWN) BridgeService.dismissPanelOnHostTouch(ev.x, ev.y)
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent); setIntent(intent)
        if (BridgeClient.getService() == null) BridgeClient.requestBinder(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.densityDpi != 0 && newConfig.densityDpi != appliedDensityDpi) {
            sPendingQuery = query; recreate(); return
        }
        syncDesktopLayout(); refreshStatusAsync()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && query.isNotEmpty()) { clearSearch(); return true }
        if (!searchInput.hasFocus()) {
            val ch = event?.unicodeChar ?: 0
            if (keyCode == KeyEvent.KEYCODE_SLASH || (keyCode == KeyEvent.KEYCODE_F && event?.isCtrlPressed == true)) {
                searchInput.requestFocus(); return true
            }
            if (ch >= 0x20 && !Character.isISOControl(ch) && ch < 0xE000) {
                searchInput.requestFocus(); searchInput.append(ch.toChar().toString()); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun glassBg(fillColor: Int, radiusDp: Float, strokeColor: Int = 0x38FFFFFF) =
        GradientDrawable().apply { setColor(fillColor); cornerRadius = dpF(radiusDp); setStroke(1, strokeColor) }

    private fun topPill(label: String, iconKind: Int, iconColor: Int, onClick: () -> Unit) =
        TextView(this).apply {
            text = label; setTextColor(0xFFE2E8F0.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(11), dp(5), dp(11), dp(5))
            background = glassBg(0x66161922, 16f)
            HyperUi.setStartIcon(this, iconKind, iconColor, 13f, 5f)
            setOnClickListener { onClick() }
        }

    private fun wrapEnd(endDp: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(endDp)
        }

    private fun buildUi() {
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        rootView = FrameLayout(this).apply {
            isFocusable = true; isFocusableInTouchMode = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0B1120.toInt(), 0xFF16213E.toInt(), 0xFF0F172A.toInt()),
            )
        }
        wallpaperView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; scaleX = 1.08f; scaleY = 1.08f
            if (Build.VERSION.SDK_INT >= 31) setRenderEffect(RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP))
        }
        val scrim = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x480A101D, 0x5E0B1120, 0x78070B14))
        }
        rootView.addView(wallpaperView, match)
        rootView.addView(scrim, FrameLayout.LayoutParams(match))

        contentView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(26), dp(14), dp(26), dp(88))
            isFocusable = true; isFocusableInTouchMode = true
            addView(buildTopBar())
            addView(buildEmptyView(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buildAppGrid(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        overlayHost = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        rootView.addView(contentView, FrameLayout.LayoutParams(match))
        rootView.addView(overlayHost, FrameLayout.LayoutParams(match))

        rootView.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l; val h = b - t
            if (abs(w - lastLayoutW) > 6 || abs(h - lastLayoutH) > 6) {
                lastLayoutW = w; lastLayoutH = h
                rootView.post { syncDesktopLayout(); refreshStatusAsync() }
            }
        }
        setContentView(rootView)
        contentView.requestFocus()
        syncDesktopLayout()
        refreshStatusAsync()
    }

    private fun buildTopBar(): LinearLayout {
        titleView = TextView(this).apply {
            text = "ExtBridge 扩展桌面"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            paint.isFakeBoldText = true; setShadowLayer(dpF(4f), 0f, dpF(1.5f), 0xAA000000.toInt())
        }
        splitBanner = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f); paint.isFakeBoldText = true
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(4), dp(10), dp(4))
            background = glassBg(0xCC0D84FF.toInt(), 15f, 0x66FFFFFF); visibility = View.GONE
            HyperUi.setStartIcon(this, HyperUi.ICON_SPLIT, Color.WHITE, 13f, 5f)
        }
        btnModeTip = topPill("应用规则", HyperUi.ICON_SLIDERS, 0xFF7AC0FF.toInt()) { dialogs.showAllAppModesPicker(allApps, appModes) }
        btnWpRefresh = topPill("同步壁纸", HyperUi.ICON_WALLPAPER, 0xFFE2E8F0.toInt()) {
            loadWallpaperAsync(true); Toast.makeText(this, "正在同步主屏壁纸…", Toast.LENGTH_SHORT).show()
        }
        headerInfo = TextView(this).apply {
            setTextColor(0xFFE0F2FE.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
            setPadding(dp(11), dp(5), dp(11), dp(5)); background = glassBg(0x55121A2A, 15f, 0x3AFFFFFF)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), dp(12))
            addView(titleView, wrapEnd(12))
            addView(splitBanner, wrapEnd(8))
            addView(buildSearchBox(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(12) })
            addView(btnModeTip, wrapEnd(8))
            addView(btnWpRefresh, wrapEnd(10))
            addView(headerInfo)
        }
    }

    private fun buildSearchBox(): LinearLayout {
        val searchIcon = ImageView(this).apply {
            setImageDrawable(HyperUi.icon(this@LauncherActivity, HyperUi.ICON_SEARCH, 0xFFCBD5E1.toInt(), 14f))
        }
        searchInput = EditText(this).apply {
            hint = "搜索应用 / 拼音首字母 / 包名 (回车启动)"; setHintTextColor(0xFFAAB8CC.toInt()); setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f); setSingleLine(true); setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(2), dp(3), dp(6), dp(3)); imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
            })
            setOnEditorActionListener { _, actionId, ev ->
                val isGo = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE || (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_UP)
                if (isGo) { gridAdapter.itemAt(0)?.let(::launchApp); true } else false
            }
        }
        btnClear = TextView(this).apply {
            text = "清空"; setTextColor(0xFFCBD5E1.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
            setPadding(dp(8), dp(2), dp(8), dp(2)); visibility = View.GONE; setOnClickListener { clearSearch() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(10), dp(4)); background = glassBg(0x72161922, 18f)
            addView(searchIcon, LinearLayout.LayoutParams(dp(15), dp(15)).apply { rightMargin = dp(7) })
            addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnClear)
        }
    }

    private fun buildEmptyView(): TextView = TextView(this).apply {
        text = "未找到匹配的应用，可尝试输入中文、拼音首字母或英文包名"
        setTextColor(0xFFE2E8F0.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f)
        setShadowLayer(dpF(3f), 0f, dpF(1.2f), 0xCC000000.toInt()); gravity = Gravity.CENTER; visibility = View.GONE
    }.also { emptyView = it }

    private fun buildAppGrid(): GridView {
        gridAdapter = LauncherGridAdapter(this) { entry ->
            if (!dialogs.isShowing) dialogs.showAppModeDialog(entry, appModes[entry.pkg] ?: AppMode.DEFAULT)
        }
        gridView = GridView(this).apply {
            verticalSpacing = dp(12); horizontalSpacing = dp(8); stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(Color.TRANSPARENT); clipToPadding = false
            adapter = gridAdapter
            setOnItemClickListener { _, _, position, _ -> gridAdapter.itemAt(position)?.let(::launchApp) }
            setOnItemLongClickListener { _, _, position, _ ->
                val entry = gridAdapter.itemAt(position) ?: return@setOnItemLongClickListener false
                dialogs.showAppModeDialog(entry, appModes[entry.pkg] ?: AppMode.DEFAULT); true
            }
        }
        return gridView
    }

    private fun computeLayoutMode(): DesktopLayoutMode {
        val dm = resources.displayMetrics
        val winW = if (rootView.width > 0) rootView.width else dm.widthPixels
        val winH = if (rootView.height > 0) rootView.height else dm.heightPixels
        val st = cachedStatus
        val singleEmpty = st.connected && st.splitActive == 1 && (st.splitSlot0Pkg.isEmpty() != st.splitSlot1Pkg.isEmpty())
        val compact = singleEmpty || (st.connected && (winW < st.virtW * 0.88f || winH < st.virtH * 0.88f))
        return DesktopLayoutMode(singleEmpty, compact)
    }

    private fun syncDesktopLayout() {
        val mode = computeLayoutMode()
        val dm = resources.displayMetrics
        val winW = if (rootView.width > 0) rootView.width else dm.widthPixels
        val dpW = winW / max(0.5f, dm.density) - if (mode.compact) 24f else 52f
        gridView.numColumns = if (mode.compact) max(3, min(8, (dpW / 108f).roundToInt())) else max(5, min(14, (dpW / 165f).roundToInt()))
        if (mode.compact != compactSplitMode) {
            compactSplitMode = mode.compact
            val hPad = dp(if (mode.compact) 12 else 26)
            contentView.setPadding(hPad, dp(if (mode.compact) 10 else 14), hPad, dp(if (mode.compact) 60 else 62))
            gridView.verticalSpacing = dp(if (mode.compact) 8 else 12)
            gridView.horizontalSpacing = dp(if (mode.compact) 5 else 8)
            gridAdapter.setCompactMode(mode.compact)
        }
        val st = cachedStatus
        titleView.text = if (mode.compact) "DeX 桌面" else "ExtBridge 扩展桌面"
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (mode.compact) 13f else 15f)
        val fullVis = if (mode.compact) View.GONE else View.VISIBLE
        btnModeTip.visibility = fullVis; btnWpRefresh.visibility = fullVis; headerInfo.visibility = fullVis

        if (mode.singleEmptySlot) {
            val port = st.virtH > st.virtW
            val side = if (port) (if (st.splitSlot0Pkg.isEmpty()) "上屏" else "下屏") else (if (st.splitSlot0Pkg.isEmpty()) "左屏" else "右屏")
            splitBanner.text = "点选填入$side"; splitBanner.visibility = View.VISIBLE
        } else {
            splitBanner.visibility = View.GONE
        }
        searchInput.hint = if (mode.compact) "搜索并点击加入分屏..." else "搜索应用名 / 拼音首字母 / 包名 (回车打开)"
        headerInfo.text = if (st.connected) {
            "${st.virtW}×${st.virtH} · ${st.virtDpi}dpi · ${st.virtFps.roundToInt()}Hz · ${if (st.inputToExt == 1) "扩展键鼠" else "平板键鼠"}"
        } else "Display #${display?.displayId ?: 0}"
    }

    private fun clearSearch() { searchInput.setText(""); rootView.requestFocus() }

    private fun applyFilter(raw: String) {
        query = raw.trim().lowercase(Locale.ROOT)
        btnClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
        val filtered = LauncherAppRepository.filterApps(allApps, query)
        gridAdapter.updateApps(filtered)
        val empty = query.isNotEmpty() && filtered.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        gridView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun refreshStatusAsync() = runIo {
        cachedStatus = BridgeClient.getStatus()
        withContext(Dispatchers.Main.immediate) { if (!isFinishing && !isDestroyed) syncDesktopLayout() }
    }

    private fun refreshAppModesAsync() = runIo {
        val modes = BridgeClient.getAllAppModes()
        withContext(Dispatchers.Main.immediate) {
            if (!isFinishing && !isDestroyed) { appModes = modes; gridAdapter.updateModes(modes) }
        }
    }

    private fun loadWallpaperAsync(force: Boolean) = runIo {
        val bmp = BridgeClient.getWallpaperBitmap(force)
        if (bmp != null && (force || bmp !== lastWpBitmap)) {
            lastWpBitmap = bmp
            withContext(Dispatchers.Main.immediate) {
                if (!isFinishing && !isDestroyed && !bmp.isRecycled) wallpaperView.setImageBitmap(bmp)
            }
        }
    }

    private fun loadAppsAsync() = runIo {
        val loaded = LauncherAppRepository.loadInstalledApps(this, LauncherActivity::class.java.name)
        withContext(Dispatchers.Main.immediate) {
            if (!isFinishing && !isDestroyed) { allApps = loaded; applyFilter(query) }
        }
    }

    private fun launchApp(entry: LauncherAppEntry) {
        val dispId = display?.displayId ?: 0
        if (!NavAssistService.isRunning() && (entry.pkg == "com.android.settings" || entry.pkg == "com.miui.securitycenter")) {
            Toast.makeText(this, "未开启无障碍：已在平板打开设置", Toast.LENGTH_SHORT).show()
            DisplayLaunchHelper.launchOnDisplay0(this, buildAppLaunchIntent(entry))
            return
        }
        runIo {
            if (dispId > 0) launchAppOnExternalDisplay(entry, dispId)
            else withContext(Dispatchers.Main.immediate) {
                try { startActivity(buildAppLaunchIntent(entry)) } catch (t: Throwable) { Log.w(TAG, "Local launch failed", t) }
            }
        }
    }

    private suspend fun launchAppOnExternalDisplay(entry: LauncherAppEntry, dispId: Int) {
        val st = BridgeClient.getStatus()
        cachedStatus = st
        if (st.connected && st.splitActive == 1 && (st.splitSlot0Pkg.isEmpty() != st.splitSlot1Pkg.isEmpty())) {
            BridgeService.performAssignSplitFromUi(if (st.splitSlot0Pkg.isEmpty()) 0 else 1, -1, entry.pkg, entry.cls)
            return
        }
        if (BridgeClient.launchInWindowMode(entry.pkg, entry.cls, WindowMode.AUTO, 0, 0, 0, 0)) return
        if (BridgeClient.launchComponent(entry.pkg, entry.cls)) return

        val mode = BridgeClient.prepareAppLaunch(entry.pkg)
        withContext(Dispatchers.Main.immediate) {
            try {
                val opts = ActivityOptions.makeBasic().apply { setLaunchDisplayId(dispId) }
                if (mode == AppMode.LAND_FULL) {
                    try { ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.java).invoke(opts, 1) }
                    catch (t: Throwable) { Log.d(TAG, "setLaunchWindowingMode unavailable: ${t.message}") }
                }
                startActivity(buildAppLaunchIntent(entry), opts.toBundle())
            } catch (t: Throwable) {
                Log.w(TAG, "Fallback startActivity failed for ${entry.pkg}; retrying daemon launch", t)
                runIo { BridgeClient.launchComponent(entry.pkg, entry.cls) }
            }
        }
    }

    private fun buildAppLaunchIntent(entry: LauncherAppEntry) = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        component = ComponentName(entry.pkg, entry.cls)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}

package com.extbridge.helper

import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import com.extbridge.helper.ipc.DaemonStateStore
import com.extbridge.helper.launcher.DesktopLayoutMode
import com.extbridge.helper.launcher.DesktopPresentationMode
import com.extbridge.helper.launcher.LauncherAppDialogs
import com.extbridge.helper.launcher.LauncherAppEntry
import com.extbridge.helper.launcher.LauncherAppRepository
import com.extbridge.helper.launcher.LauncherDesktopPolicy
import com.extbridge.helper.launcher.LauncherGridAdapter
import com.extbridge.helper.protocol.BridgeProtocol.AppMode
import com.extbridge.helper.protocol.BridgeProtocol.HelperActions
import com.extbridge.helper.protocol.BridgeProtocol.WindowMode
import com.extbridge.helper.protocol.DaemonStatus
import com.extbridge.helper.protocol.WindowStateSnapshot
import com.extbridge.helper.service.DisplayLaunchHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class LauncherActivity : Activity() {

    companion object {
        private const val TAG = "ExtLauncher"
        private const val FOCUS_FADE_OUT_MS = 160L
        private const val FOCUS_FADE_IN_MS = 220L
        private const val FOCUS_CONTENT_SCALE = 0.96f
        private const val FOCUS_WALLPAPER_SCALE = 1.03f
        private const val FOCUS_WALLPAPER_BLUR_PX = 18f
        private const val FOCUS_SCRIM_ALPHA = 0.66f
        private const val REMOTE_HINT_ALPHA = 0.45f
        private const val MENU_APP_MODES = 1
        private const val MENU_SYNC_WALLPAPER = 2

        private var sPendingQuery = ""

        @Volatile
        private var sResumedInstance: LauncherActivity? = null

        fun shouldConsumeRightClickAt(screenX: Int, screenY: Int): Boolean {
            val act = sResumedInstance
            if (act == null || act.isFinishing || act.isDestroyed ||
                act.isBackdropFocused || act.isRemoteDesktopMode || act.dialogs.isShowing
            ) {
                return false
            }
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var rootView: FrameLayout
    private lateinit var overlayHost: FrameLayout
    private lateinit var wallpaperView: ImageView
    private lateinit var focusScrimView: View
    private lateinit var contentView: FrameLayout
    private lateinit var remoteHintView: TextView
    private lateinit var physicalWorkspace: LinearLayout
    private lateinit var splitPickerHeader: TextView
    private lateinit var topBar: FrameLayout
    private lateinit var searchPill: LinearLayout
    private lateinit var menuButton: ImageView
    private lateinit var gridStage: FrameLayout
    private lateinit var searchInput: EditText
    private lateinit var btnClear: TextView
    private lateinit var emptyView: TextView
    private lateinit var gridView: GridView
    private lateinit var gridAdapter: LauncherGridAdapter
    private lateinit var dialogs: LauncherAppDialogs

    private var activePopupMenu: PopupMenu? = null
    private var allApps: List<LauncherAppEntry> = emptyList()
    private var appModes: Map<String, Int> = emptyMap()
    private var query = ""
    private var lastWpBitmap: Bitmap? = null
    private var appliedDensityDpi = 0
    private var compactModeApplied: Boolean? = null
    private var lastLayoutW = 0
    private var lastLayoutH = 0
    private var isActivityResumed = false
    private var isTopResumed = false
    private var hasResumedOnce = false

    @Volatile
    private var isBackdropFocused = false

    @Volatile
    private var isRemoteDesktopMode = false

    @Volatile
    private var cachedStatus: DaemonStatus = DaemonStateStore.status.value

    @Volatile
    private var cachedWindowSnapshot: WindowStateSnapshot = DaemonStateStore.windowSnapshot.value

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshStatusAsync()
            refreshAppModesAsync()
        }
    }

    private val binderReadyCb = Runnable {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                refreshStatusAsync()
                loadWallpaperAsync(false)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    private fun runIo(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "Launcher IO task failed", t)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedDensityDpi = resources.configuration.densityDpi
        if (BridgeClient.getService() == null) BridgeClient.requestBinder(this)
        BridgeService.ensureStarted(this)

        dialogs = LauncherAppDialogs(this, ::runIo) { latest ->
            appModes = latest
            gridAdapter.updateModes(latest)
        }
        buildUi()
        if (sPendingQuery.isNotEmpty()) {
            query = sPendingQuery
            sPendingQuery = ""
            searchInput.setText(query)
        }
        registerSyncReceiver()
        observeDaemonState()
        loadAppsAsync()
        loadWallpaperAsync(false)
        BridgeClient.setOnBinderReady(binderReadyCb)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                    handleDesktopBack()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to register OnBackInvokedCallback", t)
            }
        }
    }

    private fun observeDaemonState() {
        scope.launch {
            DaemonStateStore.status.collect { st ->
                if (!isFinishing && !isDestroyed) applyStatus(st, DaemonStateStore.windowSnapshot.value)
            }
        }
        scope.launch {
            DaemonStateStore.windowSnapshot.collect { snap ->
                if (!isFinishing && !isDestroyed) applyStatus(cachedStatus, snap)
            }
        }
    }

    private fun registerSyncReceiver() {
        try {
            val filter = IntentFilter(HelperActions.STATE_SYNC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(syncReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(syncReceiver, filter)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register STATE_SYNC receiver", t)
        }
    }

    private fun handleDesktopBack() {
        if (activePopupMenu != null) {
            dismissPopupMenu()
            return
        }
        if (query.isNotEmpty()) {
            clearSearch()
            return
        }
        if (searchInput.hasFocus()) {
            searchInput.clearFocus()
            rootView.requestFocus()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = handleDesktopBack()

    override fun onStart() {
        super.onStart()
        if (!cachedStatus.isRemoteStreaming) BridgeService.setDockHidden(false)
        BridgeService.setLauncherOverlayHost(overlayHost, display?.displayId ?: -1)
    }

    override fun onResume() {
        super.onResume()
        sResumedInstance = this
        hasResumedOnce = true
        isActivityResumed = true
        isTopResumed = true
        setBackdropFocusMode(false)
        syncDesktopLayout()
        refreshStatusAsync()
        loadWallpaperAsync(false)
        refreshAppModesAsync()
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        isTopResumed = isTopResumedActivity
        syncBackdropFocusMode()
        if (!isTopResumedActivity) refreshStatusAsync()
    }

    override fun onPause() {
        if (sResumedInstance === this) sResumedInstance = null
        isActivityResumed = false
        isTopResumed = false
        dismissPopupMenu()
        if (!computeDesktopLayoutMode().singleEmptySlot) {
            setBackdropFocusMode(true)
        }
        super.onPause()
    }

    override fun onStop() {
        BridgeService.clearLauncherOverlayHost(overlayHost)
        super.onStop()
    }

    override fun onDestroy() {
        if (sResumedInstance === this) sResumedInstance = null
        dismissPopupMenu()
        dialogs.dismissAll()
        scope.cancel()
        BridgeClient.removeOnBinderReady(binderReadyCb)
        BridgeService.clearLauncherOverlayHost(overlayHost)
        try {
            unregisterReceiver(syncReceiver)
        } catch (t: Throwable) {
            Log.d(TAG, "unregister skipped: ${t.message}")
        }
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.actionMasked == MotionEvent.ACTION_DOWN) BridgeService.dismissPanelOnHostTouch(ev.x, ev.y)
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (BridgeClient.getService() == null) BridgeClient.requestBinder(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.densityDpi != 0 && newConfig.densityDpi != appliedDensityDpi) {
            sPendingQuery = query
            recreate()
            return
        }
        syncDesktopLayout()
        refreshStatusAsync()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isRemoteDesktopMode || isBackdropFocused) return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && query.isNotEmpty()) {
            clearSearch()
            return true
        }
        if (!searchInput.hasFocus()) {
            val ch = event?.unicodeChar ?: 0
            if (keyCode == KeyEvent.KEYCODE_SLASH || (keyCode == KeyEvent.KEYCODE_F && event?.isCtrlPressed == true)) {
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

    private fun glassBg(fillColor: Int, radiusDp: Float, strokeColor: Int = 0x30FFFFFF) =
        GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dpF(radiusDp)
            setStroke(1, strokeColor)
        }

    private fun buildUi() {
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        rootView = FrameLayout(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0A0E17.toInt(), 0xFF11192B.toInt(), 0xFF0C1220.toInt()),
            )
        }
        wallpaperView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val vignetteView = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x3A080B12, 0x12080B12, 0x52080B12),
            )
        }
        focusScrimView = View(this).apply {
            setBackgroundColor(0xFF0B0E14.toInt())
            alpha = 0f
        }
        rootView.addView(wallpaperView, match)
        rootView.addView(vignetteView, FrameLayout.LayoutParams(match))
        rootView.addView(focusScrimView, FrameLayout.LayoutParams(match))

        contentView = FrameLayout(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            addView(buildPhysicalWorkspace(), FrameLayout.LayoutParams(match))
            addView(
                buildRemoteHintView(),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply { bottomMargin = dp(64) },
            )
        }
        overlayHost = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        rootView.addView(contentView, FrameLayout.LayoutParams(match))
        rootView.addView(overlayHost, FrameLayout.LayoutParams(match))

        rootView.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (abs(w - lastLayoutW) > 6 || abs(h - lastLayoutH) > 6) {
                lastLayoutW = w
                lastLayoutH = h
                rootView.post {
                    syncDesktopLayout()
                    refreshStatusAsync()
                }
            }
        }
        setContentView(rootView)
        contentView.requestFocus()
        syncDesktopLayout()
        refreshStatusAsync()
    }

    private fun buildRemoteHintView(): TextView = TextView(this).apply {
        text = "点击左下角应用图标或按 ⌘K 打开应用"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
        setShadowLayer(dpF(3f), 0f, dpF(1f), 0xB3000000.toInt())
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = glassBg(0x420B0E14, 14f, 0x1AFFFFFF)
        alpha = REMOTE_HINT_ALPHA
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }.also { remoteHintView = it }

    private fun buildPhysicalWorkspace(): LinearLayout {
        splitPickerHeader = TextView(this).apply {
            text = "选择要加入分屏的应用"
            setTextColor(0xFFF8FAFC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            paint.isFakeBoldText = true
            setShadowLayer(dpF(4f), 0f, dpF(1.2f), 0xCC000000.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }

        topBar = FrameLayout(this).apply {
            addView(
                buildSearchPill(),
                FrameLayout.LayoutParams(dp(LauncherDesktopPolicy.MAX_SEARCH_PILL_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
            addView(
                buildMenuButton(),
                FrameLayout.LayoutParams(dp(38), dp(38), Gravity.END or Gravity.CENTER_VERTICAL),
            )
        }

        gridStage = FrameLayout(this).apply {
            addView(buildEmptyView(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(buildAppGrid(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                splitPickerHeader,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(14)
                    bottomMargin = dp(8)
                    leftMargin = dp(14)
                    rightMargin = dp(14)
                },
            )
            addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                gridStage,
                LinearLayout.LayoutParams(dp(LauncherDesktopPolicy.MAX_DESKTOP_GRID_WIDTH_DP), 0, 1f).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                },
            )
        }.also { physicalWorkspace = it }
    }

    private fun buildSearchPill(): LinearLayout {
        val searchIcon = ImageView(this).apply {
            setImageDrawable(HyperUi.icon(this@LauncherActivity, HyperUi.ICON_SEARCH, 0xFFCBD5E1.toInt(), 15f))
        }
        searchInput = EditText(this).apply {
            hint = "搜索应用"
            setHintTextColor(0x99CBD5E1.toInt())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setSingleLine(true)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(2), dp(6), dp(6), dp(6))
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
            })
            setOnEditorActionListener { _, actionId, ev ->
                val isGo = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE || (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_UP)
                if (isGo) {
                    gridAdapter.itemAt(0)?.let(::launchApp)
                    true
                } else {
                    false
                }
            }
        }
        btnClear = TextView(this).apply {
            text = "×"
            setTextColor(0xFFCBD5E1.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(6), dp(2))
            visibility = View.GONE
            setOnClickListener { clearSearch() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = dpF(6f)
            setPadding(dp(14), dp(3), dp(10), dp(3))
            background = glassBg(0x7A131822, 22f, 0x32FFFFFF)
            addView(searchIcon, LinearLayout.LayoutParams(dp(15), dp(15)).apply { rightMargin = dp(8) })
            addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnClear)
        }.also { searchPill = it }
    }

    private fun buildMenuButton(): ImageView {
        val activeBg = glassBg(0x99252E3F.toInt(), 19f, 0x4CFFFFFF)
        val idleBg = glassBg(0x66131822, 19f, 0x2EFFFFFF)
        val bg = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), activeBg)
            addState(intArrayOf(android.R.attr.state_hovered), activeBg)
            addState(intArrayOf(android.R.attr.state_focused), activeBg)
            addState(intArrayOf(), idleBg)
        }
        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(HyperUi.icon(this@LauncherActivity, HyperUi.ICON_SETTINGS, 0xFFE2E8F0.toInt(), 17f))
            background = bg
            elevation = dpF(4f)
            contentDescription = "桌面选项"
            setOnClickListener { showDesktopMenu(it) }
        }.also { menuButton = it }
    }

    private fun showDesktopMenu(anchor: View) {
        dismissPopupMenu()
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.menu.add(Menu.NONE, MENU_APP_MODES, 0, "应用显示规则")
        popup.menu.add(Menu.NONE, MENU_SYNC_WALLPAPER, 1, "同步主屏壁纸")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_APP_MODES -> {
                    dialogs.showAllAppModesPicker(allApps, appModes)
                    true
                }
                MENU_SYNC_WALLPAPER -> {
                    loadWallpaperAsync(true)
                    Toast.makeText(this, "正在同步主屏壁纸…", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        popup.setOnDismissListener { if (activePopupMenu === popup) activePopupMenu = null }
        activePopupMenu = popup
        popup.show()
    }

    private fun dismissPopupMenu() {
        val popup = activePopupMenu
        activePopupMenu = null
        try {
            popup?.dismiss()
        } catch (t: Throwable) {
            Log.d(TAG, "PopupMenu dismiss skipped: ${t.message}")
        }
    }

    private fun buildEmptyView(): TextView = TextView(this).apply {
        text = "未找到匹配的应用"
        setTextColor(0xFFE2E8F0.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f)
        setShadowLayer(dpF(3f), 0f, dpF(1.2f), 0xCC000000.toInt())
        gravity = Gravity.CENTER
        visibility = View.GONE
    }.also { emptyView = it }

    private fun buildAppGrid(): GridView {
        gridAdapter = LauncherGridAdapter(this) { entry ->
            if (!dialogs.isShowing) dialogs.showAppModeDialog(entry, appModes[entry.pkg] ?: AppMode.DEFAULT)
        }
        gridView = GridView(this).apply {
            verticalSpacing = dp(18)
            horizontalSpacing = dp(14)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false
            adapter = gridAdapter
            setOnItemClickListener { _, _, position, _ -> gridAdapter.itemAt(position)?.let(::launchApp) }
            setOnItemLongClickListener { _, _, position, _ ->
                val entry = gridAdapter.itemAt(position) ?: return@setOnItemLongClickListener false
                dialogs.showAppModeDialog(entry, appModes[entry.pkg] ?: AppMode.DEFAULT)
                true
            }
        }
        return gridView
    }

    private fun computeDesktopLayoutMode(status: DaemonStatus = cachedStatus): DesktopLayoutMode {
        val dm = resources.displayMetrics
        val winW = if (rootView.width > 0) rootView.width else dm.widthPixels
        val winH = if (rootView.height > 0) rootView.height else dm.heightPixels
        return LauncherDesktopPolicy.computeDesktopLayoutMode(status, winW, winH)
    }

    private fun syncDesktopLayout(): DesktopLayoutMode {
        val mode = computeDesktopLayoutMode()
        isRemoteDesktopMode = mode.isRemoteMac

        if (mode.presentation == DesktopPresentationMode.REMOTE_MAC) {
            physicalWorkspace.visibility = View.GONE
            remoteHintView.visibility = View.VISIBLE
            dismissPopupMenu()
            if (searchInput.hasFocus()) {
                searchInput.clearFocus()
                rootView.requestFocus()
            }
            return mode
        }

        remoteHintView.visibility = View.GONE
        physicalWorkspace.visibility = View.VISIBLE

        val dm = resources.displayMetrics
        val winW = if (rootView.width > 0) rootView.width else dm.widthPixels
        val isSplitPicker = mode.presentation == DesktopPresentationMode.PHYSICAL_SPLIT_PICKER

        splitPickerHeader.visibility = if (isSplitPicker) View.VISIBLE else View.GONE
        menuButton.visibility = if (isSplitPicker) View.GONE else View.VISIBLE
        if (isSplitPicker) dismissPopupMenu()

        val topPadH = dp(if (isSplitPicker) 14 else 24)
        val topPadTop = dp(if (isSplitPicker) 2 else 20)
        val topPadBottom = dp(if (isSplitPicker) 10 else 18)
        topBar.setPadding(topPadH, topPadTop, topPadH, topPadBottom)

        val maxSearchPx = if (isSplitPicker) {
            min(dp(LauncherDesktopPolicy.MAX_PICKER_SEARCH_WIDTH_DP), max(dp(160), winW - topPadH * 2))
        } else {
            val sideReserve = topPadH * 2 + dp(38) * 2 + dp(24)
            min(dp(LauncherDesktopPolicy.MAX_SEARCH_PILL_WIDTH_DP), max(dp(180), winW - sideReserve))
        }
        (searchPill.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.width != maxSearchPx) {
                lp.width = maxSearchPx
                searchPill.layoutParams = lp
            }
        }

        val gridHorizMargin = dp(if (mode.compact) 16 else 48)
        val targetGridW = if (isSplitPicker) {
            max(dp(200), winW - gridHorizMargin)
        } else {
            min(dp(LauncherDesktopPolicy.MAX_DESKTOP_GRID_WIDTH_DP), max(dp(220), winW - gridHorizMargin))
        }
        (gridStage.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            if (lp.width != targetGridW) {
                lp.width = targetGridW
                gridStage.layoutParams = lp
            }
        }

        val gridDpW = targetGridW / max(0.5f, dm.density)
        gridView.numColumns = LauncherDesktopPolicy.computeGridColumns(gridDpW, mode.compact)

        if (compactModeApplied != mode.compact) {
            compactModeApplied = mode.compact
            val gridPadH = dp(if (mode.compact) 8 else 16)
            val gridPadBottom = dp(if (mode.compact) 56 else 84)
            gridView.setPadding(gridPadH, dp(4), gridPadH, gridPadBottom)
            gridView.horizontalSpacing = dp(if (mode.compact) 6 else 14)
            gridView.verticalSpacing = dp(if (mode.compact) 10 else 18)
            gridAdapter.setCompactMode(mode.compact)
        }
        return mode
    }

    private fun applyStatus(status: DaemonStatus, windowSnapshot: WindowStateSnapshot) {
        cachedStatus = status
        cachedWindowSnapshot = windowSnapshot
        val layoutMode = syncDesktopLayout()
        syncBackdropFocusMode(layoutMode)
    }

    private fun syncBackdropFocusMode(layoutMode: DesktopLayoutMode = computeDesktopLayoutMode()) {
        if (!hasResumedOnce) return
        val shouldDim = LauncherDesktopPolicy.shouldDimBackdrop(
            status = cachedStatus,
            windowSnapshot = cachedWindowSnapshot,
            isResumed = isActivityResumed,
            isTopResumed = isTopResumed,
            layoutMode = layoutMode,
        )
        setBackdropFocusMode(shouldDim)
    }

    /**
     * Smoothly fades desktop chrome out and dims/soft-blurs the wallpaper into a deep charcoal stage
     * backdrop whenever an app covers the desktop, preventing icons or hints from bleeding behind
     * centered apps, freeform windows, or 8px split seams.
     */
    private fun setBackdropFocusMode(dimmed: Boolean) {
        if (isBackdropFocused == dimmed) return
        isBackdropFocused = dimmed

        contentView.animate().cancel()
        focusScrimView.animate().cancel()
        wallpaperView.animate().cancel()

        if (dimmed) {
            dismissPopupMenu()
            if (searchInput.hasFocus()) {
                searchInput.clearFocus()
                rootView.requestFocus()
            }
            val interp = DecelerateInterpolator(1.4f)
            contentView.animate()
                .alpha(0f)
                .scaleX(FOCUS_CONTENT_SCALE)
                .scaleY(FOCUS_CONTENT_SCALE)
                .setDuration(FOCUS_FADE_OUT_MS)
                .setInterpolator(interp)
                .start()
            focusScrimView.animate()
                .alpha(FOCUS_SCRIM_ALPHA)
                .setDuration(FOCUS_FADE_OUT_MS)
                .setInterpolator(interp)
                .start()
            wallpaperView.animate()
                .scaleX(FOCUS_WALLPAPER_SCALE)
                .scaleY(FOCUS_WALLPAPER_SCALE)
                .setDuration(FOCUS_FADE_OUT_MS)
                .setInterpolator(interp)
                .start()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                wallpaperView.setRenderEffect(
                    RenderEffect.createBlurEffect(FOCUS_WALLPAPER_BLUR_PX, FOCUS_WALLPAPER_BLUR_PX, Shader.TileMode.CLAMP),
                )
            }
        } else {
            val interp = DecelerateInterpolator(1.6f)
            contentView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(FOCUS_FADE_IN_MS)
                .setInterpolator(interp)
                .start()
            focusScrimView.animate()
                .alpha(0f)
                .setDuration(FOCUS_FADE_IN_MS)
                .setInterpolator(interp)
                .start()
            wallpaperView.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(FOCUS_FADE_IN_MS)
                .setInterpolator(interp)
                .start()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                wallpaperView.setRenderEffect(null)
            }
        }
    }

    private fun clearSearch() {
        searchInput.setText("")
        rootView.requestFocus()
    }

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
        val status = BridgeClient.getStatus()
        val snap = if (status.connected && status.vdId > 0) BridgeClient.getManagedWindows() else WindowStateSnapshot()
        withContext(Dispatchers.Main.immediate) {
            if (!isFinishing && !isDestroyed) applyStatus(status, snap)
        }
    }

    private fun refreshAppModesAsync() = runIo {
        val modes = BridgeClient.getAllAppModes()
        withContext(Dispatchers.Main.immediate) {
            if (!isFinishing && !isDestroyed) {
                appModes = modes
                gridAdapter.updateModes(modes)
            }
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
            if (!isFinishing && !isDestroyed) {
                allApps = loaded
                applyFilter(query)
            }
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
            if (dispId > 0) {
                launchAppOnExternalDisplay(entry, dispId)
            } else {
                withContext(Dispatchers.Main.immediate) {
                    try {
                        startActivity(buildAppLaunchIntent(entry))
                    } catch (t: Throwable) {
                        Log.w(TAG, "Local launch failed", t)
                    }
                }
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
                    try {
                        ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.java).invoke(opts, 1)
                    } catch (t: Throwable) {
                        Log.d(TAG, "setLaunchWindowingMode unavailable: ${t.message}")
                    }
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

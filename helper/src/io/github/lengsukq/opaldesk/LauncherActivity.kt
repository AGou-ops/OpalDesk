package io.github.lengsukq.opaldesk

import android.annotation.SuppressLint
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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
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
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import io.github.lengsukq.opaldesk.ipc.DaemonStateStore
import io.github.lengsukq.opaldesk.launcher.DesktopBackdropState
import io.github.lengsukq.opaldesk.launcher.DesktopGridWorkspaceView
import io.github.lengsukq.opaldesk.launcher.DesktopLayoutMode
import io.github.lengsukq.opaldesk.launcher.DesktopPresentationMode
import io.github.lengsukq.opaldesk.launcher.DesktopWidgetBoardController
import io.github.lengsukq.opaldesk.launcher.LauncherAppDialogs
import io.github.lengsukq.opaldesk.launcher.LauncherAppEntry
import io.github.lengsukq.opaldesk.launcher.LauncherAppRepository
import io.github.lengsukq.opaldesk.launcher.LauncherDesktopPolicy
import io.github.lengsukq.opaldesk.launcher.LauncherGridAdapter
import io.github.lengsukq.opaldesk.launcher.MacMenuEntry
import io.github.lengsukq.opaldesk.launcher.MacMenuHeader
import io.github.lengsukq.opaldesk.launcher.MacSheetItem
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.AppMode
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.HelperActions
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.ShortcutAction
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.WindowMode
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.WindowStateSnapshot
import io.github.lengsukq.opaldesk.service.DisplayLaunchHelper
import io.github.lengsukq.opaldesk.ui.ExtThemePrefs
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

/**
 * Pure Samsung DeX external desktop wallpaper canvas hosting pinned app icons/folders
 * ([DesktopGridWorkspaceView]), desktop widgets ([DesktopWidgetBoardController]), and the
 * empty-slot split picker fallback.
 *
 * All global shell chrome (Taskbar, App Drawer, Quick Settings, Window Switcher, Window Headers)
 * lives permanently in WindowManager via [io.github.lengsukq.opaldesk.desktop.state.DesktopShellCoordinator].
 */
class LauncherActivity : Activity() {

    companion object {
        private const val TAG = "ExtLauncher"
        private const val FOCUS_FADE_OUT_MS = 160L
        private const val FOCUS_FADE_IN_MS = 220L
        private const val FOCUS_CONTENT_SCALE = 1.0f
        private const val FOCUS_WALLPAPER_SCALE = 1.0f
        private const val SOFT_SCRIM_ALPHA = 0.0f
        private const val FOCUS_SCRIM_ALPHA = 0.28f
        private const val MAX_INITIAL_PINNED_APPS = 8

        private var sPendingQuery = ""

        @Volatile
        private var sResumedInstance: LauncherActivity? = null

        fun notifyOverlayStateChanged() {
            val inst = sResumedInstance
            if (inst != null && !inst.isFinishing && !inst.isDestroyed) {
                inst.runOnUiThread { inst.syncDesktopLayout() }
            }
        }

        fun shouldConsumeRightClickAt(screenX: Int, screenY: Int): Boolean {
            val act = sResumedInstance
            if (act == null || act.isFinishing || act.isDestroyed ||
                act.backdropState == DesktopBackdropState.FULL_DIM || act.dialogs.isShowing
            ) {
                return false
            }
            if (act.desktopGridWorkspace.visibility == View.VISIBLE) {
                return true
            }
            return isPointInGridItem(act.gridView, act.gridAdapter, screenX, screenY)
        }

        private fun isPointInGridItem(
            grid: GridView,
            adapter: LauncherGridAdapter,
            screenX: Int,
            screenY: Int,
        ): Boolean {
            if (grid.visibility != View.VISIBLE || grid.width <= 0 || grid.height <= 0) return false
            val loc = IntArray(2)
            grid.getLocationOnScreen(loc)
            val lx = screenX - loc[0]
            val ly = screenY - loc[1]
            if (lx < 0 || ly < 0 || lx >= grid.width || ly >= grid.height) return false
            val pos = grid.pointToPosition(lx, ly)
            return pos != AdapterView.INVALID_POSITION && pos in 0 until adapter.itemCount
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var rootView: FrameLayout
    private lateinit var wallpaperView: ImageView
    private lateinit var focusScrimView: View
    private lateinit var contentView: FrameLayout
    private lateinit var physicalWorkspace: LinearLayout
    private lateinit var splitPickerHeader: TextView
    private lateinit var pickerSearchWrap: LinearLayout
    private lateinit var searchPill: LinearLayout
    private lateinit var workspaceColumnsRow: LinearLayout
    private lateinit var desktopLeftPane: LinearLayout
    private lateinit var gridStage: FrameLayout
    private lateinit var searchInput: EditText
    private lateinit var btnClear: TextView
    private lateinit var emptyView: TextView
    private lateinit var desktopGridWorkspace: DesktopGridWorkspaceView
    private lateinit var gridView: GridView
    private lateinit var gridAdapter: LauncherGridAdapter
    private lateinit var widgetBoard: DesktopWidgetBoardController
    private lateinit var dialogs: LauncherAppDialogs

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
    private var backdropState: DesktopBackdropState = DesktopBackdropState.ACTIVE

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

    private val desktopPrefsChangedHook: (Context) -> Unit = {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                if (::desktopGridWorkspace.isInitialized) desktopGridWorkspace.reloadFromPrefs()
                applyFilter(query)
                widgetBoard.rebuildBoard(force = false)
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
            if (::desktopGridWorkspace.isInitialized) desktopGridWorkspace.updateModes(latest)
            gridAdapter.updateModes(latest)
        }
        widgetBoard = DesktopWidgetBoardController(this, dialogs.presenter, ::runIo, onInlineTilesChanged = { tiles ->
            desktopGridWorkspace.setWidgetTiles(tiles, widgetBoard::showAddWidgetMenu)
            applyFilter(query)
        }) { pkg ->
            if (pkg.isNullOrBlank()) ""
            else allApps.firstOrNull { it.pkg == pkg }?.label ?: pkg
        }
        AppPrefs.onDesktopPrefsChanged = desktopPrefsChangedHook

        applyDesktopCaptionBarStyle()
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
        if (dialogs.isShowing) {
            dialogs.dismissAll()
            return
        }
        if (::desktopGridWorkspace.isInitialized && desktopGridWorkspace.isFolderOpen) {
            desktopGridWorkspace.closeFolder()
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
        BridgeService.setDockHidden(false)
        widgetBoard.startUpdates()
    }

    override fun onResume() {
        super.onResume()
        sResumedInstance = this
        hasResumedOnce = true
        isActivityResumed = true
        isTopResumed = true
        setBackdropState(DesktopBackdropState.ACTIVE)
        syncDesktopLayout()
        refreshStatusAsync()
        loadWallpaperAsync(false)
        refreshAppModesAsync()
        widgetBoard.startUpdates()
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
        dialogs.dismissAll()
        if (!computeDesktopLayoutMode().singleEmptySlot) {
            syncBackdropFocusMode()
        }
        super.onPause()
    }

    override fun onStop() {
        widgetBoard.stopUpdates()
        super.onStop()
    }

    override fun onDestroy() {
        if (sResumedInstance === this) sResumedInstance = null
        if (AppPrefs.onDesktopPrefsChanged === desktopPrefsChangedHook) AppPrefs.onDesktopPrefsChanged = null
        widgetBoard.release()
        dialogs.dismissAll()
        scope.cancel()
        BridgeClient.removeOnBinderReady(binderReadyCb)
        try {
            unregisterReceiver(syncReceiver)
        } catch (t: Throwable) {
            Log.d(TAG, "unregister skipped: ${t.message}")
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        widgetBoard.onActivityResult(requestCode, resultCode)
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

    private fun applyDesktopCaptionBarStyle() {
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                val controller = window.insetsController ?: return
                val cls = android.view.WindowInsetsController::class.java
                val transFlag = cls.getField("APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND").getInt(null)
                val lightFlag = cls.getField("APPEARANCE_LIGHT_CAPTION_BARS").getInt(null)
                controller.setSystemBarsAppearance(transFlag or lightFlag, transFlag or lightFlag)
            } catch (t: Throwable) {
                Log.d(TAG, "Desktop caption bar appearance unavailable: ${t.message}")
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (backdropState == DesktopBackdropState.FULL_DIM) return super.onKeyDown(keyCode, event)
        if (event != null) {
            val shortcut = NavAssistService.matchPhysicalShortcut(keyCode, event.metaState)
            if (shortcut != null && BridgeService.dispatchPhysicalShortcutFromA11y(shortcut)) {
                return true
            }
        }
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (dialogs.isShowing || query.isNotEmpty() || (::desktopGridWorkspace.isInitialized && desktopGridWorkspace.isFolderOpen)) {
                handleDesktopBack()
                return true
            }
        }
        val isSplitPicker = computeDesktopLayoutMode().presentation == DesktopPresentationMode.PHYSICAL_SPLIT_PICKER
        if (isSplitPicker && !searchInput.hasFocus()) {
            if (keyCode == KeyEvent.KEYCODE_SLASH || (keyCode == KeyEvent.KEYCODE_F && event?.isCtrlPressed == true)) {
                searchInput.requestFocus()
                return true
            }
        } else if (!isSplitPicker) {
            val ch = event?.unicodeChar ?: 0
            if (ch >= 0x20 && !Character.isISOControl(ch) && ch < 0xE000) {
                BridgeService.toggleAppDrawerFromUi(focusSearch = true)
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
        wallpaperView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val vignetteView = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x30080B12, 0x0A080B12, 0x46080B12),
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
        }
        rootView.addView(contentView, FrameLayout.LayoutParams(match))

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

    @SuppressLint("ClickableViewAccessibility")
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

        pickerSearchWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            visibility = View.GONE
            addView(buildSearchPill(), LinearLayout.LayoutParams(dp(LauncherDesktopPolicy.MAX_PICKER_SEARCH_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        desktopGridWorkspace = DesktopGridWorkspaceView(
            ctx = this,
            menuPresenter = dialogs.presenter,
            onLaunchApp = ::launchApp,
            onShowAppMenu = { entry, anchor ->
                if (!dialogs.isShowing) {
                    dialogs.showAppModeDialog(entry, appModes[entry.pkg] ?: AppMode.DEFAULT, anchorView = anchor)
                }
            },
            onShowDesktopMenu = { ax, ay ->
                if (computeDesktopLayoutMode().presentation == DesktopPresentationMode.PHYSICAL_DESKTOP) {
                    showDesktopMenu(anchorX = ax, anchorY = ay)
                }
            },
        )
        gridStage = FrameLayout(this).apply {
            addView(buildEmptyView(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(desktopGridWorkspace, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(buildAppGrid(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnLongClickListener {
                if (computeDesktopLayoutMode().presentation == DesktopPresentationMode.PHYSICAL_DESKTOP) {
                    showDesktopMenu()
                    true
                } else false
            }
            setOnTouchListener { _, ev ->
                if (ev?.actionMasked == MotionEvent.ACTION_DOWN && (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                    if (computeDesktopLayoutMode().presentation == DesktopPresentationMode.PHYSICAL_DESKTOP) {
                        showDesktopMenu(anchorX = ev.rawX.roundToInt(), anchorY = ev.rawY.roundToInt())
                        return@setOnTouchListener true
                    }
                }
                false
            }
            setOnGenericMotionListener { _, ev ->
                if (ev?.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && ev.actionButton == MotionEvent.BUTTON_SECONDARY) {
                    if (computeDesktopLayoutMode().presentation == DesktopPresentationMode.PHYSICAL_DESKTOP) {
                        showDesktopMenu(anchorX = ev.rawX.roundToInt(), anchorY = ev.rawY.roundToInt())
                        return@setOnGenericMotionListener true
                    }
                }
                false
            }
        }

        desktopLeftPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(gridStage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        val boardView = widgetBoard.buildView()
        workspaceColumnsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(desktopLeftPane, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(
                boardView,
                LinearLayout.LayoutParams(dp(336), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    leftMargin = dp(18)
                    bottomMargin = dp(18)
                },
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                splitPickerHeader,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(14)
                    bottomMargin = dp(6)
                    leftMargin = dp(14)
                    rightMargin = dp(14)
                },
            )
            addView(pickerSearchWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                workspaceColumnsRow,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    topMargin = dp(16)
                },
            )
        }.also { physicalWorkspace = it }
    }

    private fun buildSearchPill(): LinearLayout {
        val searchIcon = ImageView(this).apply {
            setImageDrawable(HyperUi.icon(this@LauncherActivity, HyperUi.ICON_SEARCH, HyperUi.TEXT_SECONDARY, 14f))
        }
        searchInput = EditText(this).apply {
            hint = "搜索要加入分屏的应用…"
            setHintTextColor(0x996E6E73.toInt())
            setTextColor(HyperUi.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f)
            setSingleLine(true)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(2), dp(5), dp(6), dp(5))
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
                } else false
            }
        }
        btnClear = TextView(this).apply {
            text = "×"
            setTextColor(HyperUi.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(2), dp(4), dp(2))
            visibility = View.GONE
            setOnClickListener { clearSearch() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(8), dp(2))
            background = glassBg(0xDDF5F7FA.toInt(), 18f, 0xCCFFFFFF.toInt())
            addView(searchIcon, LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(7) })
            addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnClear)
        }.also { searchPill = it }
    }

    private fun showDesktopMenu(anchorView: View? = null, anchorX: Int? = null, anchorY: Int? = null) {
        val nextThemeIdx = (ExtThemePrefs.getThemeModeIndex(this) + 1) % 3
        val curThemeLabel = when (ExtThemePrefs.getThemeModeIndex(this)) {
            ExtThemePrefs.MODE_LIGHT -> "冰透浅色"
            ExtThemePrefs.MODE_DARK -> "黑曜深色"
            else -> "跟随系统"
        }
        dialogs.presenter.showContextMenu(
            header = MacMenuHeader("OpalDesk DeX 桌面", "图标、小组件与玻璃外观"),
            entries = listOf(
                MacMenuEntry.Action(
                    label = "全部应用抽屉",
                    subtitle = "悬浮在窗口上方搜索并启动应用",
                    iconKind = HyperUi.ICON_APPS,
                    onClick = { BridgeService.toggleAppDrawerFromUi(focusSearch = false) },
                ),
                MacMenuEntry.Action(
                    label = "多任务窗口视图",
                    subtitle = "预览与切换所有运行中的窗口 (Alt+Tab)",
                    iconKind = HyperUi.ICON_WINDOW,
                    onClick = { BridgeService.toggleWindowSwitcherFromUi() },
                ),
                MacMenuEntry.Divider,
                MacMenuEntry.Action(
                    label = "管理桌面固定应用",
                    subtitle = "自定义常驻桌面图标",
                    iconKind = HyperUi.ICON_PIN,
                    onClick = { showManagePinnedAppsDialog() },
                ),
                MacMenuEntry.Action(
                    label = "管理桌面小组件",
                    subtitle = "添加系统小组件与信息卡片",
                    iconKind = HyperUi.ICON_SIDEBAR,
                    onClick = { widgetBoard.showAddWidgetMenu(anchorView) },
                ),
                MacMenuEntry.Divider,
                MacMenuEntry.Action(
                    label = "应用显示规则",
                    subtitle = "为单个应用设置横屏 / 竖屏兼容",
                    iconKind = HyperUi.ICON_SLIDERS,
                    onClick = { dialogs.showAllAppModesPicker(allApps, appModes) },
                ),
                MacMenuEntry.Action(
                    label = "切换毛玻璃外观（当前：$curThemeLabel）",
                    iconKind = HyperUi.ICON_MOON,
                    onClick = { ExtThemePrefs.setThemeModeIndex(this, nextThemeIdx) },
                ),
                MacMenuEntry.Action(
                    label = "同步主屏壁纸",
                    iconKind = HyperUi.ICON_WALLPAPER,
                    onClick = {
                        loadWallpaperAsync(true)
                        Toast.makeText(this, "正在同步主屏壁纸…", Toast.LENGTH_SHORT).show()
                    },
                ),
            ),
            anchorView = anchorView,
            anchorX = anchorX,
            anchorY = anchorY,
            widthDp = 264f,
            triggerHaptic = anchorView == null,
        )
    }

    private fun showManagePinnedAppsDialog() {
        if (allApps.isEmpty()) return
        val current = AppPrefs.getDesktopPinnedApps(this).toSet()
        val sheetItems = allApps.map { entry ->
            MacSheetItem(
                id = entry.pkg,
                title = entry.label,
                subtitle = entry.pkg,
                icon = entry.icon,
                selected = current.contains(entry.pkg),
            )
        }
        dialogs.presenter.showListSheet(
            title = "管理桌面固定应用",
            subtitle = "勾选常驻在桌面的应用图标",
            items = sheetItems,
            searchable = true,
            searchHint = "搜索应用名或包名…",
            multiSelect = true,
            onMultiConfirm = { selected ->
                val chosen = allApps.filter { selected.contains(it.pkg) }.map { it.pkg }
                AppPrefs.setDesktopPinnedApps(this, chosen, markInitialized = true)
            },
        )
    }

    private fun buildEmptyView(): TextView = TextView(this).apply {
        text = "桌面暂无固定应用 · 点此打开应用抽屉或右键桌面管理"
        setTextColor(0xFFE2E8F0.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f)
        setShadowLayer(dpF(3f), 0f, dpF(1.2f), 0xCC000000.toInt())
        gravity = Gravity.CENTER
        visibility = View.GONE
        setOnClickListener { BridgeService.toggleAppDrawerFromUi(focusSearch = false) }
    }.also { emptyView = it }

    private fun buildAppGrid(): GridView {
        gridAdapter = LauncherGridAdapter(this) { entry, anchor ->
            if (!dialogs.isShowing) {
                dialogs.showAppModeDialog(entry, appModes[entry.pkg] ?: AppMode.DEFAULT, anchorView = anchor)
            }
        }
        gridView = GridView(this).apply {
            verticalSpacing = dp(18)
            horizontalSpacing = dp(14)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false
            adapter = gridAdapter
            setOnItemClickListener { _, _, position, _ -> gridAdapter.itemAt(position)?.let(::launchApp) }
            setOnItemLongClickListener { _, itemView, position, _ ->
                val entry = gridAdapter.itemAt(position) ?: return@setOnItemLongClickListener false
                dialogs.showAppModeDialog(entry, appModes[entry.pkg] ?: AppMode.DEFAULT, anchorView = itemView)
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
        physicalWorkspace.visibility = View.VISIBLE

        val dm = resources.displayMetrics
        val winW = if (rootView.width > 0) rootView.width else dm.widthPixels
        val isSplitPicker = mode.presentation == DesktopPresentationMode.PHYSICAL_SPLIT_PICKER
        val wbOn = !isSplitPicker && AppPrefs.isWorkbenchEnabled(this)
        val showWidgetBoard = !isSplitPicker && !wbOn && !mode.compact && winW >= dp(640)
        val isNarrow = winW < dp(560)

        splitPickerHeader.visibility = if (isSplitPicker) View.VISIBLE else View.GONE
        pickerSearchWrap.visibility = if (isSplitPicker) View.VISIBLE else View.GONE
        widgetBoard.rootView.visibility = if (showWidgetBoard) View.VISIBLE else View.GONE
        if (isSplitPicker) dialogs.dismissAll()

        if (isSplitPicker) {
            val maxSearchPx = min(dp(LauncherDesktopPolicy.MAX_PICKER_SEARCH_WIDTH_DP), max(dp(160), winW - dp(28)))
            (searchPill.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                if (lp.width != maxSearchPx) {
                    lp.width = maxSearchPx
                    searchPill.layoutParams = lp
                }
            }
        }

        val horizPad = dp(if (isSplitPicker) 8 else if (isNarrow) 12 else 24)
        // Reserve clean clearance when Workbench Mode is docked on Left or Top so desktop icons
        // never sit cut in half behind the frosted Workbench rail.
        val wbPos = if (wbOn) AppPrefs.getWorkbenchPosition(this) else -1
        val leftPad = if (wbOn && wbPos == AppPrefs.WORKBENCH_POS_LEFT) {
            dp(218)
        } else {
            horizPad
        }
        val topPad = if (wbOn && wbPos == AppPrefs.WORKBENCH_POS_TOP) {
            dp(140)
        } else {
            0
        }
        // On the desktop (isDesktopClear == true) the floating taskbar is always visible even when
        // auto-hide is enabled, so always reserve bottom taskbar clearance for icons & widget board.
        val bottomPad = dp(if (isSplitPicker) 14 else 72)
        workspaceColumnsRow.setPadding(leftPad, topPad, horizPad, bottomPad)

        val boardW = if (showWidgetBoard) (winW * 0.30f).roundToInt().coerceIn(dp(276), dp(364)) else 0
        if (showWidgetBoard) {
            (widgetBoard.rootView.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                if (lp.width != boardW) {
                    lp.width = boardW
                    widgetBoard.rootView.layoutParams = lp
                }
            }
            widgetBoard.onAvailableWidthChanged(boardW)
        }

        val leftPaneW = max(dp(220), winW - leftPad - horizPad - (if (showWidgetBoard) boardW + dp(18) else 0))
        val gridDpW = leftPaneW / max(0.5f, dm.density)
        val cols = LauncherDesktopPolicy.computeGridColumns(gridDpW, mode.compact)
        gridView.numColumns = cols
        if (::desktopGridWorkspace.isInitialized) desktopGridWorkspace.setColumnsAndDensity(cols, mode.compact)
        if (!isSplitPicker) widgetBoard.setInlineMode(wbOn)

        if (compactModeApplied != mode.compact) {
            compactModeApplied = mode.compact
            val gridPadH = dp(if (mode.compact) 6 else 10)
            gridView.setPadding(gridPadH, dp(4), gridPadH, dp(16))
            gridView.horizontalSpacing = dp(if (mode.compact) 6 else 14)
            gridView.verticalSpacing = dp(if (mode.compact) 10 else 18)
            gridAdapter.setCompactMode(mode.compact)
        }
        applyFilter(query)
        return mode
    }

    private fun applyStatus(status: DaemonStatus, windowSnapshot: WindowStateSnapshot) {
        val localModeChanged = cachedStatus.isLocalDexActive != status.isLocalDexActive
        cachedStatus = status
        cachedWindowSnapshot = windowSnapshot
        val layoutMode = syncDesktopLayout()
        if (localModeChanged && ::widgetBoard.isInitialized) {
            widgetBoard.rebuildBoard(force = false)
        }
        syncBackdropFocusMode(layoutMode)
    }

    private fun syncBackdropFocusMode(layoutMode: DesktopLayoutMode = computeDesktopLayoutMode()) {
        if (!hasResumedOnce) return
        val next = LauncherDesktopPolicy.computeBackdropState(
            status = cachedStatus,
            windowSnapshot = cachedWindowSnapshot,
            isResumed = isActivityResumed,
            isTopResumed = isTopResumed,
            layoutMode = layoutMode,
        )
        setBackdropState(next)
    }

    private fun setBackdropState(next: DesktopBackdropState) {
        if (backdropState == next) return
        backdropState = next

        contentView.animate().cancel()
        focusScrimView.animate().cancel()
        wallpaperView.animate().cancel()

        when (next) {
            DesktopBackdropState.FULL_DIM -> {
                dialogs.dismissAll()
                if (searchInput.hasFocus()) {
                    searchInput.clearFocus()
                    rootView.requestFocus()
                }
                val interp = DecelerateInterpolator(1.4f)
                contentView.animate().alpha(0f).scaleX(FOCUS_CONTENT_SCALE).scaleY(FOCUS_CONTENT_SCALE)
                    .setDuration(FOCUS_FADE_OUT_MS).setInterpolator(interp).start()
                focusScrimView.animate().alpha(FOCUS_SCRIM_ALPHA)
                    .setDuration(FOCUS_FADE_OUT_MS).setInterpolator(interp).start()
                wallpaperView.animate().scaleX(FOCUS_WALLPAPER_SCALE).scaleY(FOCUS_WALLPAPER_SCALE)
                    .setDuration(FOCUS_FADE_OUT_MS).setInterpolator(interp).start()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) wallpaperView.setRenderEffect(null)
            }
            DesktopBackdropState.SOFT_BEHIND_FREEFORM -> {
                dialogs.dismissAll()
                val interp = DecelerateInterpolator(1.5f)
                contentView.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(FOCUS_FADE_IN_MS).setInterpolator(interp).start()
                focusScrimView.animate().alpha(SOFT_SCRIM_ALPHA)
                    .setDuration(FOCUS_FADE_IN_MS).setInterpolator(interp).start()
                wallpaperView.animate().scaleX(1f).scaleY(1f)
                    .setDuration(FOCUS_FADE_IN_MS).setInterpolator(interp).start()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) wallpaperView.setRenderEffect(null)
            }
            DesktopBackdropState.ACTIVE -> {
                val interp = DecelerateInterpolator(1.6f)
                contentView.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(FOCUS_FADE_IN_MS).setInterpolator(interp).start()
                focusScrimView.animate().alpha(0f)
                    .setDuration(FOCUS_FADE_IN_MS).setInterpolator(interp).start()
                wallpaperView.animate().scaleX(1f).scaleY(1f)
                    .setDuration(FOCUS_FADE_IN_MS).setInterpolator(interp).start()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) wallpaperView.setRenderEffect(null)
            }
        }
    }

    private fun clearSearch() {
        searchInput.setText("")
        rootView.requestFocus()
    }

    private fun resolveDesktopPinnedSource(): List<LauncherAppEntry> {
        val isSplitPicker = computeDesktopLayoutMode().presentation == DesktopPresentationMode.PHYSICAL_SPLIT_PICKER
        if (isSplitPicker || query.isNotEmpty()) return allApps
        val pinnedSet = AppPrefs.getDesktopPinnedApps(this).toHashSet()
        if (pinnedSet.isEmpty()) return emptyList()
        val byPkg = allApps.associateBy { it.pkg }
        return AppPrefs.getDesktopPinnedApps(this).mapNotNull { byPkg[it] }
    }

    private fun applyFilter(raw: String) {
        query = raw.trim().lowercase(Locale.ROOT)
        btnClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
        val isSplitPicker = computeDesktopLayoutMode().presentation == DesktopPresentationMode.PHYSICAL_SPLIT_PICKER
        val useFreeformDesktop = !isSplitPicker && query.isEmpty() && ::desktopGridWorkspace.isInitialized
        if (useFreeformDesktop) {
            val empty = !desktopGridWorkspace.hasItems()
            emptyView.text = "桌面暂无固定应用 · 点此打开应用抽屉或右键桌面管理"
            emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            desktopGridWorkspace.visibility = if (empty) View.GONE else View.VISIBLE
            gridView.visibility = View.GONE
            return
        }
        if (::desktopGridWorkspace.isInitialized) desktopGridWorkspace.visibility = View.GONE
        val base = resolveDesktopPinnedSource()
        val filtered = LauncherAppRepository.filterApps(base, query)
        gridAdapter.updateApps(filtered)
        val empty = filtered.isEmpty()
        emptyView.text = if (query.isNotEmpty()) "未找到匹配的应用" else "桌面暂无固定应用 · 点此打开应用抽屉或右键桌面管理"
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
                if (::desktopGridWorkspace.isInitialized) desktopGridWorkspace.updateModes(modes)
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
                if (!AppPrefs.isDesktopPinnedInitialized(this@LauncherActivity) && loaded.isNotEmpty()) {
                    val starter = loaded.filterNot { it.pkg == packageName }
                        .take(MAX_INITIAL_PINNED_APPS)
                        .map { it.pkg }
                    AppPrefs.setDesktopPinnedApps(this@LauncherActivity, starter, markInitialized = true)
                }
                if (::desktopGridWorkspace.isInitialized) desktopGridWorkspace.updateCatalog(loaded, appModes)
                applyFilter(query)
                widgetBoard.rebuildBoard(force = false)
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

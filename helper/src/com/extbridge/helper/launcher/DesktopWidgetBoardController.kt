package com.extbridge.helper.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.HyperUi
import com.extbridge.helper.ipc.DaemonStateStore
import com.extbridge.helper.overlay.MATCH
import com.extbridge.helper.overlay.WRAP
import com.extbridge.helper.overlay.dp
import com.extbridge.helper.overlay.dpF
import com.extbridge.helper.overlay.flp
import com.extbridge.helper.overlay.llp
import com.extbridge.helper.overlay.mirror.MirrorCard
import com.extbridge.helper.overlay.mirror.MirrorCardActions
import com.extbridge.helper.overlay.mirror.MirrorCardState
import com.extbridge.helper.overlay.mirror.MirrorMetricsSampler
import com.extbridge.helper.overlay.mirror.MirrorPomodoroTimer
import com.extbridge.helper.overlay.mirror.MirrorPresetCardFactory
import com.extbridge.helper.overlay.mirror.MirrorWingLayoutBuilder
import com.extbridge.helper.overlay.roundedBg
import com.extbridge.helper.overlay.setPadDp
import com.extbridge.helper.prefs.AppPrefs
import com.extbridge.helper.prefs.MirrorThemeCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Manages the PC-style Desktop Widget Board on the right side of [com.extbridge.helper.LauncherActivity].
 * Syncs Mirror-configured AppWidgets & preset cards and allows adding/removing desktop widgets.
 */
internal class DesktopWidgetBoardController(
    private val activity: Activity,
    private val presenter: MacContextMenuPresenter,
    private val runIo: (block: suspend () -> Unit) -> Unit,
    private val resolvePkgLabel: (String?) -> String,
) {
    companion object {
        private const val TAG = "ExtDesktopWidgets"
        const val REQ_BIND_DESKTOP_WIDGET = 4201
        const val REQ_CONF_DESKTOP_WIDGET = 4202
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val metricsSampler = MirrorMetricsSampler()
    private val pomodoroTimer = MirrorPomodoroTimer(mainHandler, { activity.isFinishing || activity.isDestroyed }) { refreshLiveStateAsync() }
    private val audioManager by lazy { activity.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }

    private var mirrorWidgetHost: AppWidgetHost? = null
    private var desktopWidgetHost: AppWidgetHost? = null
    private var appWidgetMgr: AppWidgetManager? = null
    private var listening = false
    private var lastBoardWidthPx = 0

    private var pendingWidgetId = -1
    private var structuralSignature = ""

    lateinit var rootView: LinearLayout
        private set
    private lateinit var syncChip: TextView
    private lateinit var contentContainer: LinearLayout
    private val activeCards = mutableListOf<MirrorCard>()

    private val liveTicker = object : Runnable {
        override fun run() {
            if (activity.isFinishing || activity.isDestroyed || rootView.visibility != View.VISIBLE) return
            refreshLiveStateAsync()
            mainHandler.postDelayed(this, 2200L)
        }
    }

    private val cardActions = object : MirrorCardActions {
        override fun adjustVolume(direction: Int) {
            runCatching {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                refreshLiveStateAsync()
            }.onFailure { Log.w(TAG, "adjustVolume failed: ${it.message}") }
        }

        override fun sendMediaKey(keyCode: Int) {
            runCatching {
                audioManager?.let {
                    it.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                    it.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                    mainHandler.postDelayed({ refreshLiveStateAsync() }, 300L)
                }
            }.onFailure { Log.w(TAG, "sendMediaKey failed: ${it.message}") }
        }

        override fun togglePomodoro() = pomodoroTimer.toggle()
        override fun resetPomodoro() = pomodoroTimer.reset()
        override fun toggleScreenDim() = runIo { BridgeClient.execSysAction(BridgeClient.SYS_SCREEN_DIM, -1, null) }
        override fun switchToDex() = Unit
        override fun cycleSideAlign() = Unit
        override fun toggleInput() = runIo { BridgeClient.toggleInput() }
    }

    fun buildView(): LinearLayout {
        val ctx = activity
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadDp(0f, 0f, 0f, 84f)
            contentContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            addView(contentContainer, FrameLayout.LayoutParams(MATCH, WRAP))
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadDp(4f, 0f, 4f, 10f)

            addView(TextView(ctx).apply {
                text = "小组件"
                setTextColor(HyperUi.TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                paint.isFakeBoldText = true
            }, ctx.llp(0, WRAP, 1f))

            syncChip = makeActionChip("同步") { _ ->
                val next = !AppPrefs.isDesktopSyncMirrorWidgets(ctx)
                AppPrefs.setDesktopSyncMirrorWidgets(ctx, next)
                updateSyncChip()
                rebuildBoard(force = true)
            }.also { addView(it, ctx.llp(rightDp = 6f)) }

            addView(makeActionChip("+ 编辑") { anchor -> showAddWidgetMenu(anchor) })
        }

        rootView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(0xCCF5F7FA.toInt(), ctx.dpF(24f), 1, 0xCCFFFFFF.toInt())
            elevation = ctx.dpF(10f)
            setPadDp(14f, 14f, 14f, 0f)
            addView(header, ctx.llp(MATCH, WRAP))
            addView(scroll, ctx.llp(MATCH, 0, 1f))
        }
        updateSyncChip()
        return rootView
    }

    private fun makeActionChip(label: String, onClick: (View) -> Unit): TextView = TextView(activity).apply {
        text = label
        setTextColor(HyperUi.TEXT_PRIMARY)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
        paint.isFakeBoldText = true
        setPadDp(10f, 5f)
        background = roundedBg(0x10000000, activity.dpF(13f), 1, 0x14000000)
        setOnClickListener { onClick(it) }
    }

    private fun updateSyncChip() {
        val isLocalDex = DaemonStateStore.status.value.isLocalDexActive
        syncChip.visibility = if (isLocalDex) View.GONE else View.VISIBLE
        if (isLocalDex) return
        val syncing = AppPrefs.isDesktopSyncMirrorWidgets(activity)
        syncChip.text = if (syncing) "同步开" else "仅桌面"
        syncChip.setTextColor(if (syncing) HyperUi.HYPER_BLUE else HyperUi.TEXT_PRIMARY)
        (syncChip.background as? GradientDrawable)?.setColor(
            if (syncing) 0x1A007AFF else 0x10000000,
        )
    }

    fun startUpdates() {
        ensureHosts()
        if (!listening) {
            runCatching { mirrorWidgetHost?.startListening() }
            runCatching { desktopWidgetHost?.startListening() }
            listening = true
        }
        rebuildBoard(force = false)
        mainHandler.removeCallbacks(liveTicker)
        mainHandler.post(liveTicker)
    }

    fun stopUpdates() {
        mainHandler.removeCallbacks(liveTicker)
        if (listening) {
            runCatching { mirrorWidgetHost?.stopListening() }
            runCatching { desktopWidgetHost?.stopListening() }
            listening = false
        }
    }

    fun release() {
        stopUpdates()
        pomodoroTimer.reset()
        activeCards.clear()
    }

    fun onAvailableWidthChanged(boardWidthPx: Int) {
        if (boardWidthPx > 0 && kotlin.math.abs(boardWidthPx - lastBoardWidthPx) > activity.dp(12f)) {
            lastBoardWidthPx = boardWidthPx
            rebuildBoard(force = true)
        }
    }

    fun rebuildBoard(force: Boolean = false) {
        if (activity.isFinishing || activity.isDestroyed) return
        ensureHosts()
        updateSyncChip()
        val isLocalDex = DaemonStateStore.status.value.isLocalDexActive
        val syncMirror = !isLocalDex && AppPrefs.isDesktopSyncMirrorWidgets(activity)
        val mirrorL = if (syncMirror) AppPrefs.getMirrorWidgetId(activity, 0) else -1
        val mirrorR = if (syncMirror) AppPrefs.getMirrorWidgetId(activity, 1) else -1
        val customIds = AppPrefs.getDesktopCustomWidgetIds(activity)
        val presets = AppPrefs.getDesktopPresetCards(activity)
        val theme = AppPrefs.getActiveMirrorTheme(activity)
        val desktopTheme = toDesktopGlassTheme(theme)

        val sig = "$isLocalDex|$syncMirror|$mirrorL,$mirrorR|${customIds.joinToString(",")}|${presets.joinToString(",")}|${theme.id}|$lastBoardWidthPx"
        if (!force && sig == structuralSignature) return
        structuralSignature = sig

        contentContainer.removeAllViews()
        activeCards.clear()

        val boardW = (if (lastBoardWidthPx > 0) lastBoardWidthPx else activity.dp(320f)) - activity.dp(24f)
        val mgr = appWidgetMgr

        // 1) Synced & Custom Android System AppWidgets
        val allSysWidgets = buildList {
            if (mirrorL > 0) add(mirrorL to true)
            if (mirrorR > 0 && mirrorR != mirrorL) add(mirrorR to true)
            customIds.forEach { if (it > 0 && it != mirrorL && it != mirrorR) add(it to false) }
        }
        if (allSysWidgets.isNotEmpty()) {
            allSysWidgets.forEach { (wid, fromMirror) ->
                val host = if (fromMirror) mirrorWidgetHost else desktopWidgetHost
                val box = FrameLayout(activity).apply {
                    background = roundedBg(desktopTheme.widgetBgColor, activity.dpF(14f), 1, desktopTheme.cardStrokeColor)
                    clipChildren = true
                    clipToPadding = true
                }
                val widgetH = activity.dp(168f)
                contentContainer.addView(box, activity.llp(MATCH, widgetH, bottomDp = 10f))
                MirrorWingLayoutBuilder.mountAppWidget(
                    ctx = activity,
                    box = box,
                    widgetId = wid,
                    slot = 0,
                    availW = boardW.coerceAtLeast(activity.dp(200f)),
                    availH = widgetH,
                    compact = true,
                    theme = desktopTheme,
                    mgr = mgr,
                    wHost = host,
                    ratioFn = { 4 },
                )
                if (!fromMirror) {
                    val removeBtn = TextView(activity).apply {
                        text = "×"
                        setTextColor(HyperUi.TEXT_PRIMARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
                        gravity = Gravity.CENTER
                        background = roundedBg(0xE6FFFFFF.toInt(), activity.dpF(11f), 1, 0x22000000)
                        setOnClickListener {
                            AppPrefs.removeDesktopCustomWidgetId(activity, wid)
                            rebuildBoard(force = true)
                        }
                    }
                    box.addView(
                        removeBtn,
                        FrameLayout.LayoutParams(activity.dp(22f), activity.dp(22f), Gravity.TOP or Gravity.END).apply {
                            topMargin = activity.dp(6f)
                            rightMargin = activity.dp(6f)
                        },
                    )
                }
            }
        } else {
            val hintCard = TextView(activity).apply {
                text = if (isLocalDex) {
                    "点击右上角【+ 编辑】添加系统天气 / 日历 / 音乐小组件"
                } else {
                    "点击右上角【+ 编辑】添加系统天气 / 日历 / 音乐小组件\n开启同步后将自动带出镜像模式绑定的小组件"
                }
                setTextColor(HyperUi.TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
                gravity = Gravity.CENTER
                setPadDp(12f, 14f)
                background = roundedBg(0xCCFFFFFF.toInt(), activity.dpF(18f), 1, 0x16000000)
                setOnClickListener { showSystemWidgetPicker() }
            }
            contentContainer.addView(hintCard, activity.llp(MATCH, WRAP, bottomDp = 10f))
        }

        // 2) Live Built-in Desktop Preset Cards
        presets.forEach { key ->
            val card = MirrorPresetCardFactory.build(activity, key, compact = true, theme = desktopTheme, actions = cardActions)
            activeCards += card
            contentContainer.addView(card.view, activity.llp(MATCH, WRAP, bottomDp = 10f))
        }
        refreshLiveStateAsync()
    }

    private fun toDesktopGlassTheme(src: com.extbridge.helper.prefs.MirrorTheme): BridgeClient.MirrorTheme =
        BridgeClient.MirrorTheme(
            id = src.id,
            name = src.name,
            bgColor = 0xCCF5F7FA.toInt(),
            headerBgColor = 0xE6FFFFFF.toInt(),
            cardBgColor = 0xE6FFFFFF.toInt(),
            cardStrokeColor = 0x16000000,
            widgetBgColor = 0xDCFFFFFF.toInt(),
            pillBgColor = 0x10000000,
            accentBtnColor = HyperUi.HYPER_BLUE,
            titleColor = HyperUi.TEXT_PRIMARY,
            subColor = HyperUi.TEXT_SECONDARY,
            accentColor = HyperUi.HYPER_BLUE,
            lightMode = true,
        )

    private fun refreshLiveStateAsync() {
        if (activeCards.isEmpty()) return
        runIo {
            val sys = BridgeClient.getSysStatus()
            val tel = BridgeClient.getMirrorTelemetry()
            metricsSampler.sample(activity.applicationContext)
            val metrics = metricsSampler.snapshot
            val snap = activeCards.toList()
            val theme = toDesktopGlassTheme(AppPrefs.getActiveMirrorTheme(activity))
            val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
            val curVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            val volPct = ((curVol * 100f) / maxVol).toInt().coerceIn(0, 100)
            val batt = activity.applicationContext.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val battLevel = batt?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: 100
            val battScale = batt?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
            val battPct = ((battLevel * 100f) / battScale).toInt().coerceIn(0, 100)
            val battStatus = batt?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = battStatus == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                battStatus == android.os.BatteryManager.BATTERY_STATUS_FULL

            val state = MirrorCardState(
                batteryPct = battPct,
                batteryCharging = charging,
                sys = sys,
                telemetry = tel,
                metrics = metrics,
                volPct = volPct,
                volLevel = curVol,
                memoText = AppPrefs.getMirrorMemoText(activity),
                pomoRemainSec = pomodoroTimer.remainSec,
                pomoRunning = pomodoroTimer.isRunning,
                theme = theme,
                resolvePkgLabel = resolvePkgLabel,
            )
            withContext(Dispatchers.Main.immediate) {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    snap.forEach { it.bind(state) }
                }
            }
        }
    }

    private fun ensureHosts() {
        if (appWidgetMgr == null) appWidgetMgr = AppWidgetManager.getInstance(activity.applicationContext)
        if (mirrorWidgetHost == null) {
            mirrorWidgetHost = AppWidgetHost(activity.applicationContext, MirrorThemeCatalog.MIRROR_WIDGET_HOST_ID)
        }
        if (desktopWidgetHost == null) {
            desktopWidgetHost = AppWidgetHost(activity.applicationContext, MirrorThemeCatalog.DESKTOP_WIDGET_HOST_ID)
        }
    }

    fun showAddWidgetMenu(anchorView: View? = null) {
        val syncing = AppPrefs.isDesktopSyncMirrorWidgets(activity)
        presenter.showContextMenu(
            header = MacMenuHeader("桌面小组件", "添加系统小组件或自定义信息卡片"),
            entries = listOf(
                MacMenuEntry.Action(
                    label = "添加系统小组件",
                    subtitle = "天气 / 日历 / 第三方 AppWidget",
                    iconKind = HyperUi.ICON_APPS,
                    onClick = { showSystemWidgetPicker() },
                ),
                MacMenuEntry.Action(
                    label = "配置内置信息卡片",
                    subtitle = "时钟 / 媒体 / 番茄钟 / 性能状态",
                    iconKind = HyperUi.ICON_SLIDERS,
                    onClick = { showBuiltInCardsPicker() },
                ),
                MacMenuEntry.Divider,
                MacMenuEntry.Action(
                    label = "同步镜像模式小组件",
                    iconKind = HyperUi.ICON_MIRROR,
                    checked = syncing,
                    onClick = {
                        AppPrefs.setDesktopSyncMirrorWidgets(activity, !syncing)
                        updateSyncChip()
                        rebuildBoard(force = true)
                    },
                ),
            ),
            anchorView = anchorView,
            widthDp = 256f,
        )
    }

    private fun showBuiltInCardsPicker() {
        val keys = MirrorThemeCatalog.MIRROR_PRESET_KEYS.filterNot { it == "quickctrl" || it == "screendim" }
        val current = AppPrefs.getDesktopPresetCards(activity).toSet()
        val sheetItems = keys.map { key ->
            MacSheetItem(
                id = key,
                title = MirrorThemeCatalog.mirrorPresetTitle(key),
                selected = current.contains(key),
            )
        }
        presenter.showListSheet(
            title = "内置桌面信息卡片",
            subtitle = "勾选要显示在桌面右侧小组件面板的卡片",
            items = sheetItems,
            searchable = false,
            multiSelect = true,
            onMultiConfirm = { selected ->
                val chosen = keys.filter { selected.contains(it) }
                AppPrefs.setDesktopPresetCards(activity, chosen)
                rebuildBoard(force = true)
            },
        )
    }

    private fun showSystemWidgetPicker() {
        Toast.makeText(activity, "正在读取系统小组件列表…", Toast.LENGTH_SHORT).show()
        runIo {
            BridgeClient.ensureAppWidgetPermission()
            val pm = activity.packageManager
            val providers = AppWidgetManager.getInstance(activity.applicationContext).installedProviders.orEmpty()
                .sortedBy { (it.loadLabel(pm) ?: it.provider.shortClassName).lowercase(Locale.ROOT) }
            val sheetItems = providers.mapIndexed { idx, info ->
                val title = info.loadLabel(pm) ?: info.provider.shortClassName
                val appLabel = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() }
                    .getOrDefault(info.provider.packageName)
                val appIcon = runCatching { pm.getApplicationIcon(info.provider.packageName) }.getOrNull()
                MacSheetItem(
                    id = idx.toString(),
                    title = title,
                    subtitle = "$appLabel · ${info.minWidth}×${info.minHeight}dp",
                    icon = appIcon,
                )
            }
            withContext(Dispatchers.Main.immediate) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                if (providers.isEmpty()) {
                    Toast.makeText(activity, "未找到可用的系统小组件", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                presenter.showListSheet(
                    title = "添加系统小组件",
                    subtitle = "点选要固定到桌面的系统或第三方小组件",
                    items = sheetItems,
                    searchable = true,
                    searchHint = "搜索小组件或应用名…",
                    onSingleSelect = { picked ->
                        picked.id.toIntOrNull()?.let { idx ->
                            if (idx in providers.indices) bindChosenWidget(providers[idx])
                        }
                    },
                )
            }
        }
    }

    private fun bindChosenWidget(info: AppWidgetProviderInfo) {
        ensureHosts()
        val host = desktopWidgetHost ?: return
        val id = host.allocateAppWidgetId()
        val bound = runCatching {
            AppWidgetManager.getInstance(activity).bindAppWidgetIdIfAllowed(id, info.provider)
        }.getOrDefault(false)
        if (bound) {
            finalizeOrConfigureWidget(id, info, host)
            return
        }
        pendingWidgetId = id
        try {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQ_BIND_DESKTOP_WIDGET)
        } catch (t: Throwable) {
            Log.w(TAG, "ACTION_APPWIDGET_BIND failed", t)
            deleteWidgetSafely(host, id)
            pendingWidgetId = -1
            Toast.makeText(activity, "绑定系统小组件失败，请检查权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun finalizeOrConfigureWidget(widgetId: Int, info: AppWidgetProviderInfo?, host: AppWidgetHost) {
        val optional = info != null && (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
        if (info?.configure != null && !optional) {
            pendingWidgetId = widgetId
            try {
                host.startAppWidgetConfigureActivityForResult(activity, widgetId, 0, REQ_CONF_DESKTOP_WIDGET, null)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "Widget configure activity failed; adding directly", t)
            }
        }
        AppPrefs.addDesktopCustomWidgetId(activity, widgetId)
        rebuildBoard(force = true)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int) {
        if ((requestCode != REQ_BIND_DESKTOP_WIDGET && requestCode != REQ_CONF_DESKTOP_WIDGET) || pendingWidgetId <= 0) return
        val wid = pendingWidgetId
        pendingWidgetId = -1
        ensureHosts()
        val host = desktopWidgetHost ?: return
        if (resultCode != Activity.RESULT_OK) {
            deleteWidgetSafely(host, wid)
            return
        }
        if (requestCode == REQ_BIND_DESKTOP_WIDGET) {
            finalizeOrConfigureWidget(wid, AppWidgetManager.getInstance(activity).getAppWidgetInfo(wid), host)
        } else {
            AppPrefs.addDesktopCustomWidgetId(activity, wid)
            rebuildBoard(force = true)
        }
    }

    private fun deleteWidgetSafely(host: AppWidgetHost, widgetId: Int) {
        runCatching { host.deleteAppWidgetId(widgetId) }
    }
}

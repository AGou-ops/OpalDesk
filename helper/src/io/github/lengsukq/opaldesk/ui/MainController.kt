package io.github.lengsukq.opaldesk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.*
import io.github.lengsukq.opaldesk.AdbBootstrap
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.LocalDexActivity
import io.github.lengsukq.opaldesk.adb.ShizukuBootstrap
import io.github.lengsukq.opaldesk.ipc.DaemonStateStore
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.*
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.InputBackend
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.MirrorMode
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.RemoteStream
import kotlinx.coroutines.*

/**
 * Plain Kotlin state holder and coroutine action coordinator for MainActivity.
 * Collects [DaemonStateStore.status] / [DaemonStateStore.sysStatus] and replaces raw Thread spawns
 * with structured coroutines cancelled cleanly on Activity destruction.
 */
class MainController(context: Context) {

    companion object {
        private const val TAG = "ExtMainCtrl"
        val RES_PRESETS = listOf(
            Triple(3840, 2160, "3840×2160 · 4K 超采"),
            Triple(3440, 1440, "3440×1440 · 21:9 准4K"),
            Triple(2560, 1600, "2560×1600 · 2.5K 16:10"),
            Triple(2560, 1440, "2560×1440 · 2K 原生推荐"),
            Triple(2560, 1080, "2560×1080 · 21:9 带鱼屏"),
            Triple(1920, 1200, "1920×1200 · 16:10 宽屏"),
            Triple(1920, 1080, "1920×1080 · 1080p 标准"),
            Triple(1600, 900, "1600×900 · 900p 轻量"),
            Triple(1280, 720, "1280×720 · 720p 省流稳帧"),
        )
        val RES_DROPDOWN_LABELS = RES_PRESETS.map { it.third } + "自定义宽高…"
        val ROT_LABELS = listOf("0° 正常横屏", "90° 顺时针竖屏", "180° 倒置横屏", "270° 逆时针竖屏")
        val DPI_QUICK_PRESETS = listOf(120, 160, 200, 240, 280)
        val LAUNCH_MODE_LABELS = listOf("自由小窗（DeX 推荐）", "居中黄金比例窗口", "沉浸全屏（隐藏标题栏）")
    }

    private val appCtx = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var themeModeIdx by mutableIntStateOf(ExtThemePrefs.getThemeModeIndex(appCtx))
    var navTab by mutableIntStateOf(0)
    var displaySubTab by mutableIntStateOf(0)

    var daemonStatus by mutableStateOf(DaemonStateStore.status.value)
        private set
    var screenDimmed by mutableStateOf(false)
        private set
    var dockAutoHide by mutableStateOf(false)
    var workbenchEnabled by mutableStateOf(AppPrefs.isWorkbenchEnabled(appCtx))
    var workbenchPosition by mutableIntStateOf(AppPrefs.getWorkbenchPosition(appCtx))

    // DeX canvas editing state
    var selW by mutableIntStateOf(2560)
    var selH by mutableIntStateOf(1440)
    var selDpi by mutableIntStateOf(240)
    private var localDexDpiDraft by mutableStateOf<Int?>(null)
    val localDexDpi: Int
        get() = localDexDpiDraft ?: daemonStatus.virtDpi.takeIf { it > 0 }?.coerceIn(160, 480) ?: 320
    val hasLocalDexDpiDraft: Boolean
        get() = localDexDpiDraft != null && localDexDpi != daemonStatus.virtDpi
    var localDexDpiApplying by mutableStateOf(false)
        private set
    var selFps by mutableFloatStateOf(60f)
    var selRot by mutableIntStateOf(0)
    var selPhysModeId by mutableIntStateOf(-1)
    var physNotice by mutableStateOf("")
        private set
    private var initedFromDaemon = false
    private var suppressDaemonSyncUntilMs = 0L
    private var lastPhysW = 0
    private var lastPhysH = 0
    private var lastPhysFps = 60f

    // Mirror sidebar state
    var mirrorSideMode by mutableIntStateOf(1)
    val mirrorPresets = mutableStateListOf<String>()
    var mirrorThemeId by mutableStateOf("night")
    var mirrorSpeedIdx by mutableIntStateOf(1)
    var mirrorMemoPreview by mutableStateOf("")
    var widgetSlot0Label by mutableStateOf<String?>(null)
    var widgetSlot1Label by mutableStateOf<String?>(null)
    var widgetSlot0Ratio by mutableIntStateOf(0)
    var widgetSlot1Ratio by mutableIntStateOf(0)

    // Overlay visibility
    var showDiagSheet by mutableStateOf(false)
    var showActivationSheet by mutableStateOf(false)
    var activationInitTab by mutableIntStateOf(1)
    var showCustomResDialog by mutableStateOf(false)
    var showEdidSheet by mutableStateOf(false)
    var showPresetMgrSheet by mutableStateOf(false)
    var showMemoDialog by mutableStateOf(false)
    var showCustomColorDialog by mutableStateOf(false)
    var showWidgetSheet by mutableStateOf(false)
    var activeWidgetSlot by mutableIntStateOf(0)
    var showRemoteMacSheet by mutableStateOf(false)

    // Mac / Wireless remote display state
    var localLanIp by mutableStateOf("127.0.0.1")
    var wifiServerBusy by mutableStateOf(false)
    var remoteCodecPref by mutableIntStateOf(RemoteStream.CODEC_AUTO_HEVC)
    var remoteMaxBitrateMbps by mutableIntStateOf(16)
    var remoteAudioEnabled by mutableStateOf(true)
    val trustedMacClients = mutableStateListOf<TrustedMacClient>()
    var trustedMacLoading by mutableStateOf(false)

    // Adaptive single-app, DeX behavior & input backend
    var adaptiveSingleEnabled by mutableStateOf(true)
    var defaultSingleWidthDp by mutableIntStateOf(DisplayGeometry.DEFAULT_SINGLE_WIDTH_DP)
    var dexDefaultLaunchMode by mutableIntStateOf(0)
    var rightClickPassthrough by mutableStateOf(true)
    var desktopDoubleClickLaunch by mutableStateOf(false)
    var inputBackendMode by mutableIntStateOf(InputBackend.AUTO)

    // Activation sheet live state
    var autoRootEnabled by mutableStateOf(false)
    var autoShizukuEnabled by mutableStateOf(false)
    var autoCloseWifiAdbEnabled by mutableStateOf(false)
    var hasSuHint by mutableStateOf(false)
    var shizukuInstalled by mutableStateOf(false)
    var shizukuAlive by mutableStateOf(false)
    var shizukuGranted by mutableStateOf(false)
    var shizukuSummary by mutableStateOf("")
    var mdnsHint by mutableStateOf("")
    var pairHost by mutableStateOf("")
    var pairPort by mutableStateOf("")
    var pairCode by mutableStateOf("")
    var connPort by mutableStateOf("")
    var actWorking by mutableStateOf(false)
    var actStatusText by mutableStateOf("")
    var actStatusColor by mutableStateOf(HyperBlue)

    // Per-app rules
    val appRuleItems = mutableStateListOf<AppRuleEntry>()
    var appRulesLoading by mutableStateOf(false)
    var appRulesLoadedOnce by mutableStateOf(false)

    private var hostEdited = false
    private var pairPortEdited = false
    private var connPortEdited = false
    private var userSkippedActivation = false
    private var autoRootTried = false
    private var autoShizukuTried = false
    private var pendingShizukuBootstrapAfterGrant = false
    private var pollJob: Job? = null
    private var autoPromptJob: Job? = null
    private var adaptivePushJob: Job? = null

    init {
        scope.launch { DaemonStateStore.status.collect { applyIncomingDaemonStatus(it, syncFromDaemon = false) } }
        scope.launch {
            DaemonStateStore.sysStatus.collect { sys ->
                screenDimmed = (sys.valid && sys.screenDimmed) || BridgeService.isBlackoutActive()
            }
        }
    }

    fun <T> runDaemonAction(
        label: String,
        refreshAfter: Boolean = false,
        onFinally: () -> Unit = {},
        block: () -> T,
        onResult: (T) -> Unit = {},
    ) {
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    try { Result.success(block()) }
                    catch (t: Throwable) { Log.w(TAG, "Daemon action failed: $label", t); Result.failure(t) }
                }
                outcome.onSuccess { value ->
                    onResult(value)
                    if (refreshAfter) {
                        BridgeService.refreshFromReceiver(appCtx)
                        refreshStatusNow(syncFromDaemon = false)
                    }
                }
            } finally {
                onFinally()
            }
        }
    }

    fun syncLocalPrefs(resolveWidgetLabel: (Int) -> String?) {
        themeModeIdx = ExtThemePrefs.getThemeModeIndex(appCtx)
        dockAutoHide = BridgeService.isDockAutoHideEnabled(appCtx)
        autoRootEnabled = AppPrefs.isAutoRootEnabled(appCtx)
        autoShizukuEnabled = AppPrefs.isAutoShizukuEnabled(appCtx)
        autoCloseWifiAdbEnabled = AppPrefs.isAutoCloseWifiAdbEnabled(appCtx)
        hasSuHint = AdbBootstrap.hasSuBinaryHint()
        refreshShizukuState()
        mirrorThemeId = AppPrefs.getMirrorThemeId(appCtx)
        mirrorSpeedIdx = AppPrefs.getMirrorCursorSpeedIdx(appCtx)
        mirrorMemoPreview = AppPrefs.getMirrorMemoText(appCtx)
        mirrorPresets.clear(); mirrorPresets.addAll(AppPrefs.getMirrorPresetSlots(appCtx))
        widgetSlot0Ratio = AppPrefs.getMirrorWidgetRatio(appCtx, 0)
        widgetSlot1Ratio = AppPrefs.getMirrorWidgetRatio(appCtx, 1)
        widgetSlot0Label = resolveWidgetLabel(0)
        widgetSlot1Label = resolveWidgetLabel(1)
        remoteCodecPref = AppPrefs.getRemoteCodecPref(appCtx)
        remoteMaxBitrateMbps = AppPrefs.getRemoteMaxBitrateMbps(appCtx)
        remoteAudioEnabled = AppPrefs.isRemoteAudioEnabled(appCtx)
        adaptiveSingleEnabled = AppPrefs.isAdaptiveSingleEnabled(appCtx)
        defaultSingleWidthDp = AppPrefs.getDefaultSingleWidthDp(appCtx)
        dexDefaultLaunchMode = AppPrefs.getDexDefaultLaunchMode(appCtx)
        rightClickPassthrough = AppPrefs.isRightClickPassthroughInApps(appCtx)
        desktopDoubleClickLaunch = AppPrefs.isDesktopDoubleClickLaunch(appCtx)
        inputBackendMode = AppPrefs.getInputBackendMode(appCtx)
        localLanIp = AdbBootstrap.getLocalIpv4()
    }

    fun startForegroundSync() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val hasBinder = BridgeClient.getService() != null
                if (!hasBinder) BridgeClient.requestBinder(appCtx)
                refreshStatusNow(syncFromDaemon = false)
                delay(if (hasBinder) 2000L else 1200L)
            }
        }
    }

    fun stopForegroundSync() {
        pollJob?.cancel(); pollJob = null
        autoPromptJob?.cancel(); autoPromptJob = null
    }

    fun scheduleAutoPrompt(delayMs: Long, isBlocked: () -> Boolean) {
        autoPromptJob?.cancel()
        autoPromptJob = scope.launch {
            delay(delayMs)
            if (isBlocked() || BridgeClient.getService() != null) return@launch
            if (!autoShizukuTried && AppPrefs.isAutoShizukuEnabled(appCtx) && ShizukuBootstrap.isReadyAndGranted()) {
                autoShizukuTried = true
                toast("正在尝试自动 Shizuku 激活 OpalDesk 服务…")
                AdbBootstrap.shizukuBootstrapAsync(appCtx, false, object : AdbBootstrap.ProgressListener {
                    override fun onStep(message: String?) = Unit
                    override fun onResult(success: Boolean, message: String?) {
                        refreshStatusNow(syncFromDaemon = true)
                        if (!success && !userSkippedActivation && !showActivationSheet) openActivationSheet()
                    }
                })
                return@launch
            }
            if (!autoRootTried && AppPrefs.isAutoRootEnabled(appCtx)) {
                autoRootTried = true
                toast("正在尝试自动 Root 激活 OpalDesk 服务…")
                AdbBootstrap.rootBootstrapAsync(appCtx, false, object : AdbBootstrap.ProgressListener {
                    override fun onStep(message: String?) = Unit
                    override fun onResult(success: Boolean, message: String?) {
                        refreshStatusNow(syncFromDaemon = true)
                        if (!success && !userSkippedActivation && !showActivationSheet) openActivationSheet()
                    }
                })
                return@launch
            }
            if (!userSkippedActivation && !showActivationSheet) openActivationSheet()
        }
    }

    fun refreshStatusNow(syncFromDaemon: Boolean) {
        dockAutoHide = BridgeService.isDockAutoHideEnabled(appCtx)
        workbenchEnabled = AppPrefs.isWorkbenchEnabled(appCtx)
        workbenchPosition = AppPrefs.getWorkbenchPosition(appCtx)
        runDaemonAction(
            label = "pollStatus",
            block = {
                val st = BridgeClient.getStatus()
                st to if (st.connected) BridgeClient.getSysStatus() else SysStatus()
            },
            onResult = { (st, sys) ->
                screenDimmed = (sys.valid && sys.screenDimmed) || BridgeService.isBlackoutActive()
                applyIncomingDaemonStatus(st, syncFromDaemon = syncFromDaemon)
            },
        )
    }

    private fun applyIncomingDaemonStatus(st: DaemonStatus, syncFromDaemon: Boolean) {
        val prevMode = daemonStatus.mirrorMode
        val wasConnected = daemonStatus.connected
        daemonStatus = st
        if (!st.connected) return
        if (!st.isLocalDexActive || st.virtDpi == localDexDpiDraft) localDexDpiDraft = null

        userSkippedActivation = false
        if (showActivationSheet) {
            showActivationSheet = false
            AdbBootstrap.stopDiscovery()
            AdbBootstrap.cancelPairingNotification(appCtx)
            toast("OpalDesk 核心服务已连接")
        }
        val syncAllowed = SystemClock.uptimeMillis() >= suppressDaemonSyncUntilMs
        if ((syncFromDaemon && !initedFromDaemon) || !wasConnected) {
            initedFromDaemon = true
            if (!st.isLocalDexActive) {
                selW = st.virtW; selH = st.virtH; selDpi = st.virtDpi; selFps = st.virtFps; selRot = st.virtRot
            }
            displaySubTab = if (st.mirrorMode == MirrorMode.MIRROR) 1 else 0
        } else if (syncAllowed && !st.isLocalDexActive && selRot != st.virtRot) {
            selW = st.virtW; selH = st.virtH; selRot = st.virtRot
        }
        if (prevMode != st.mirrorMode) displaySubTab = if (st.mirrorMode == MirrorMode.MIRROR) 1 else 0
        if (syncAllowed) {
            mirrorSideMode = st.mirrorSideMode
            adaptiveSingleEnabled = st.isAdaptiveSingleEnabled
            defaultSingleWidthDp = DisplayGeometry.clampUiSingleWidthDp(st.defaultSingleWidthDp)
        }
        if (st.physW > 0 && st.physH > 0) {
            lastPhysW = st.physW; lastPhysH = st.physH; lastPhysFps = if (st.physFps > 10f) st.physFps else 60f
        }
    }

    // --- Dashboard & Display Actions ---

    fun switchOutputMode(mode: Int) {
        if (daemonStatus.isLocalDexActive && mode == MirrorMode.MIRROR) return
        displaySubTab = if (mode == MirrorMode.MIRROR) 1 else 0
        runDaemonAction("setMirrorMode", refreshAfter = true, block = { BridgeClient.setMirrorMode(mode) }) { applied ->
            toast("已切换为：${BridgeLabels.mirrorModeLabel(if (applied >= 0) applied else mode)}")
        }
    }

    fun toggleInputRouting() {
        if (daemonStatus.isLocalDexActive) return
        runDaemonAction("toggleInput", refreshAfter = true, block = { BridgeClient.toggleInput() }) { next ->
            if (next >= 0) toast(if (next == 1) "键鼠已切至：扩展屏" else "键鼠已切至：本机内屏")
        }
    }

    fun rebuildRenderPipeline(toastMsg: String) = runDaemonAction("rebuild", refreshAfter = true, block = { BridgeClient.rebuild() }) { toast(toastMsg) }
    fun relaunchExtLauncher() = runDaemonAction("relaunchLauncher", block = { BridgeClient.relaunchLauncher() })
    fun stopLocalDex() = runDaemonAction(
        label = "stopLocalDex",
        refreshAfter = true,
        block = {
            LocalDexActivity.finishActiveIfAny()
            BridgeClient.stopLocalDex(releaseDisplay = true)
        },
    ) { ok ->
        toast(if (ok) "已退出本机 DeX 模式" else "当前未处于本机 DeX 模式")
    }
    fun setFullscreenPolicy(enabled: Boolean) = runDaemonAction("setFullscreenPolicy", refreshAfter = true, block = { BridgeClient.setFullscreenPolicy(if (enabled) 1 else 0) })

    fun toggleBlackout() {
        if (daemonStatus.isLocalDexActive) return
        BridgeService.toggleBlackoutFromUi(appCtx)
        scope.launch { delay(160L); refreshStatusNow(syncFromDaemon = false) }
    }

    fun selectResolutionPreset(idx: Int) {
        if (idx !in RES_PRESETS.indices) { showCustomResDialog = true; return }
        suppressDaemonSyncUntilMs = SystemClock.uptimeMillis() + 2500L
        val (pw, ph, _) = RES_PRESETS[idx]
        val portrait = (selRot and 1) == 1
        selW = if (portrait) ph else pw; selH = if (portrait) pw else ph; selPhysModeId = -1
    }

    fun selectRotation(nextRot: Int) {
        if (daemonStatus.isLocalDexActive) return
        suppressDaemonSyncUntilMs = SystemClock.uptimeMillis() + 2500L
        selRot = nextRot
        val wantPortrait = (nextRot and 1) == 1
        if ((wantPortrait && selW > selH) || (!wantPortrait && selH > selW)) { val tmp = selW; selW = selH; selH = tmp }
        if (BridgeClient.getService() != null) applyChosenDexConfig()
    }

    fun apply1080pPhonePreset() {
        selW = 1920; selH = 1080; selDpi = 240; selFps = 60f; selRot = 0; selPhysModeId = -1
        applyChosenDexConfig()
    }

    fun applyCustomResolution(w: Int, h: Int) {
        selW = w; selH = h; selPhysModeId = -1
        if (BridgeClient.getService() != null) applyChosenDexConfig()
    }

    fun selectTargetFps(fps: Float) {
        selFps = fps; selPhysModeId = -1
        if (BridgeClient.getService() != null) applyChosenDexConfig()
    }

    fun selectPhysicalEdidMode(modeId: Int, pw: Int, ph: Int, pfps: Float) {
        selPhysModeId = modeId; selFps = pfps
        val portrait = (selRot and 1) == 1
        selW = if (portrait) ph else pw; selH = if (portrait) pw else ph
        applyChosenDexConfig()
    }

    fun applyChosenDexConfig() {
        suppressDaemonSyncUntilMs = SystemClock.uptimeMillis() + 2500L
        val (w, h, dpi, fps) = listOf(selW, selH, selDpi, selFps)
        val physId = selPhysModeId; val rot = selRot
        val wi = w as Int; val hi = h as Int; val di = dpi as Int; val ff = fps as Float
        runDaemonAction("applyConfig", refreshAfter = true, block = { BridgeClient.applyConfigDetailed(wi, hi, di, ff, physId, rot) }) { res ->
            when {
                !res.ok -> { physNotice = ""; toast("应用失败：请先激活后台核心服务") }
                res.physResult == -1 && res.physW > 0 -> {
                    physNotice = "画布已切为 ${wi}×${hi}；显示器不支持 ${ff.toInt()}Hz，物理输出保持 ${res.physW}×${res.physH}@${res.physFps.toInt()}Hz。"
                    toast(physNotice, long = true)
                }
                (res.physResult == -2 || res.physResult == -3) && res.physW > 0 -> {
                    physNotice = "画布已切为 ${wi}×${hi}；系统固件锁定物理输出 ${res.physW}×${res.physH}@${res.physFps.toInt()}Hz，已自动硬件缩放铺满。"
                    toast(physNotice, long = true)
                }
                else -> {
                    physNotice = ""
                    val physNote = if (res.physW > 0) " · 物理 ${res.physW}×${res.physH}@${res.physFps.toInt()}Hz" else ""
                    toast("已应用：${wi}×${hi} · ${BridgeLabels.rotationLabel(rot)} · ${di}dpi$physNote")
                }
            }
        }
    }

    fun setLocalDexDpiDraft(dpi: Int) {
        if (localDexDpiApplying) return
        localDexDpiDraft = dpi.coerceIn(160, 480).takeUnless { it == daemonStatus.virtDpi }
    }

    fun applyLocalDexDpi(dpi: Int = localDexDpi) {
        val st = daemonStatus
        if (localDexDpiApplying || !st.isLocalDexActive || st.virtW <= 0 || st.virtH <= 0) return
        val clamped = dpi.coerceIn(160, 480)
        setLocalDexDpiDraft(clamped)
        localDexDpiApplying = true
        runDaemonAction(
            "applyLocalDexDpi",
            refreshAfter = true,
            onFinally = { localDexDpiApplying = false },
            block = { BridgeClient.applyConfigDetailed(st.virtW, st.virtH, clamped, st.virtFps, -1, 0) },
        ) { res ->
            if (res.ok) toast("已更新本机 DeX 缩放：${clamped} dpi")
            else toast("应用失败：请确认本机 DeX 处于运行状态")
        }
    }

    fun resetLocalDexRecommendedDpi() {
        val st = daemonStatus
        if (!st.isLocalDexActive || st.virtW <= 0 || st.virtH <= 0) return
        val nativeDpi = appCtx.resources.displayMetrics.densityDpi.coerceAtLeast(160)
        val rec = DisplayGeometry.computeLocalDexDpi(st.virtW, st.virtH, nativeDpi)
        applyLocalDexDpi(rec)
    }

    fun applyMonitorRecommendedDefault() {
        val best = DisplayGeometry.pickBestEdidMode(BridgeParsers.parseEdidModes(daemonStatus.modesRaw))
        val hasLast = lastPhysW >= 640 && lastPhysH >= 480
        selW = best?.width ?: if (hasLast) lastPhysW else 2560
        selH = best?.height ?: if (hasLast) lastPhysH else 1440
        selDpi = DisplayGeometry.recommendDpiForResolution(selW, selH)
        selFps = best?.fps ?: if (hasLast) lastPhysFps else 60f
        selRot = 0; selPhysModeId = best?.id ?: -1
        applyChosenDexConfig()
    }

    fun selectMirrorSideMode(mode: Int) {
        suppressDaemonSyncUntilMs = SystemClock.uptimeMillis() + 1800L
        mirrorSideMode = mode
        runDaemonAction("setMirrorSideMode", refreshAfter = true, block = { BridgeClient.setMirrorSideMode(mode) }) { applied ->
            val finalMode = if (applied >= 0) applied else mode
            mirrorSideMode = finalMode
            toast("镜像布局：${BridgeLabels.mirrorSideModeLabel(finalMode)}")
        }
    }

    fun applyAdaptiveSingleConfig(enabled: Boolean, widthDp: Int, debounce: Boolean, showToast: Boolean) {
        val clamped = DisplayGeometry.clampUiSingleWidthDp(widthDp)
        adaptiveSingleEnabled = enabled; defaultSingleWidthDp = clamped
        suppressDaemonSyncUntilMs = SystemClock.uptimeMillis() + 2500L
        AppPrefs.saveAdaptiveSingleConfig(appCtx, enabled, clamped)

        adaptivePushJob?.cancel()
        adaptivePushJob = scope.launch {
            if (debounce) delay(180L)
            if (!daemonStatus.connected) {
                if (showToast) toast("已保存本地偏好，激活服务后自动生效")
                return@launch
            }
            val ok = withContext(Dispatchers.IO) { BridgeClient.setAdaptiveSingleConfig(enabled, clamped) }
            if (showToast) {
                toast(
                    when {
                        !ok -> "同步单应用默认尺寸失败"
                        enabled -> "单应用默认黄金尺寸：${clamped}dp"
                        else -> "已关闭单应用默认居中（恢复全屏）"
                    },
                )
            }
            BridgeService.refreshFromReceiver(appCtx)
            refreshStatusNow(syncFromDaemon = false)
        }
    }

    fun applyInputBackendMode(mode: Int) {
        val safeMode = mode.coerceIn(InputBackend.AUTO, InputBackend.UHID)
        inputBackendMode = safeMode
        AppPrefs.setInputBackendMode(appCtx, safeMode)
        if (!daemonStatus.connected) {
            toast("已保存输入引擎偏好：${BridgeLabels.inputBackendLabel(safeMode)}")
            return
        }
        runDaemonAction("setInputBackend", refreshAfter = true, block = { BridgeClient.setInputBackend(safeMode) }) { active ->
            toast(
                when (active) {
                    InputBackend.INJECT_STATUS_UHID -> "UHID 硬件外设注入已激活"
                    InputBackend.INJECT_STATUS_SYSTEM -> "已切换：${BridgeLabels.inputBackendLabel(safeMode)}（系统注入正常）"
                    else -> "当前输入被系统拦截，建议选择「强制 UHID 虚拟键鼠」或开启 USB 安全调试"
                },
            )
        }
    }

    fun selectDexDefaultLaunchMode(mode: Int) {
        val clamped = mode.coerceIn(0, 2)
        dexDefaultLaunchMode = clamped
        adaptiveSingleEnabled = clamped != 2
        BridgeService.setDefaultLaunchModeFromUi(appCtx, clamped)
        toast("默认窗口模式：${LAUNCH_MODE_LABELS[clamped]}")
    }

    fun setRightClickPassthroughEnabled(enabled: Boolean) {
        rightClickPassthrough = enabled
        AppPrefs.setRightClickPassthroughInApps(appCtx, enabled)
    }

    fun setDesktopDoubleClickEnabled(enabled: Boolean) {
        desktopDoubleClickLaunch = enabled
        AppPrefs.setDesktopDoubleClickLaunch(appCtx, enabled)
    }

    fun selectGlassThemeMode(idx: Int) {
        val clamped = idx.coerceIn(0, 2)
        themeModeIdx = clamped
        ExtThemePrefs.setThemeModeIndex(appCtx, clamped)
    }

    // --- Mac / Remote Streaming ---

    fun openRemoteMacSheet() {
        localLanIp = AdbBootstrap.getLocalIpv4()
        showRemoteMacSheet = true
        refreshStatusNow(syncFromDaemon = false)
        if (daemonStatus.connected) loadTrustedMacClients()
    }

    fun toggleWifiRemoteServer(enable: Boolean) = updateWifiRemoteConfig(enable, regeneratePin = enable, isPinOnly = false)
    fun regenerateWifiPairingPin() = updateWifiRemoteConfig(enable = true, regeneratePin = true, isPinOnly = true)

    private fun updateWifiRemoteConfig(enable: Boolean, regeneratePin: Boolean, isPinOnly: Boolean) {
        if (wifiServerBusy) return
        wifiServerBusy = true
        val port = if (daemonStatus.wifiServerPort > 0) daemonStatus.wifiServerPort else RemoteStream.DEFAULT_WIFI_PORT
        runDaemonAction(
            label = if (isPinOnly) "regeneratePin" else "setWifiRemoteConfig",
            refreshAfter = true,
            onFinally = { wifiServerBusy = false },
            block = { BridgeClient.setWifiRemoteConfig(enable, port, regeneratePin) },
        ) { res ->
            when {
                !res.ok -> toast(if (isPinOnly) "刷新 PIN 码失败" else "设置无线大屏服务失败，请确认守护进程已更新并在线")
                isPinOnly -> toast("已生成新的 6 位配对 PIN 码")
                res.enabled -> { toast("无线大屏服务已开启 (端口 ${res.port})"); loadTrustedMacClients() }
                else -> toast("无线大屏服务已关闭")
            }
        }
    }

    fun saveAndPushRemoteStreamPrefs(showToast: Boolean) {
        val codec = remoteCodecPref.coerceIn(0, 1)
        val mbps = remoteMaxBitrateMbps.coerceIn(4, 32)
        val audio = remoteAudioEnabled
        AppPrefs.saveRemoteStreamPrefs(appCtx, codec, mbps, audio)
        if (!daemonStatus.connected) { if (showToast) toast("已保存本地偏好，激活服务后生效"); return }
        runDaemonAction("setRemoteStreamPrefs", block = { BridgeClient.setRemoteStreamPrefs(codec, mbps * 1000, audio) }) { ok ->
            if (showToast) {
                toast(
                    if (ok) "流媒体参数已同步：${if (codec == 0) "HEVC" else "H.264"} · ${mbps}Mbps · 音频${if (audio) "开" else "关"}"
                    else "应用流媒体参数失败",
                )
            }
            refreshStatusNow(syncFromDaemon = false)
        }
    }

    fun disconnectRemoteMac() = runDaemonAction("disconnectRemoteClient", refreshAfter = true, block = { BridgeClient.disconnectRemoteClient() }) { ok ->
        toast(if (ok) "已断开当前 Mac 大屏连接" else "当前无活跃的远端 Mac 连接")
    }

    fun loadTrustedMacClients() {
        if (trustedMacLoading) return
        trustedMacLoading = true
        runDaemonAction("listTrustedMacClients", onFinally = { trustedMacLoading = false }, block = { BridgeClient.listTrustedMacClients() }) { list ->
            trustedMacClients.clear(); trustedMacClients.addAll(list)
        }
    }

    fun revokeTrustedMacClient(clientId: String) = runDaemonAction(
        label = "revokeTrustedMacClient",
        block = { BridgeClient.revokeTrustedMacClient(clientId) to BridgeClient.listTrustedMacClients() },
    ) { (ok, updated) ->
        trustedMacClients.clear(); trustedMacClients.addAll(updated)
        toast(if (!ok) "撤销信任失败" else if (clientId == "*") "已撤销全部已信任 Mac 设备" else "已撤销该 Mac 设备的配对信任")
    }

    // --- Activation Sheet ---

    fun refreshShizukuState() {
        shizukuInstalled = ShizukuBootstrap.isShizukuInstalled(appCtx)
        shizukuAlive = ShizukuBootstrap.isBinderAlive()
        shizukuGranted = ShizukuBootstrap.isPermissionGranted()
        shizukuSummary = ShizukuBootstrap.buildStateSummary(appCtx)
    }

    fun bindShizukuCallbacks() {
        ShizukuBootstrap.bindUiCallbacks(
            onStateChanged = { refreshShizukuState() },
            onPermissionResult = { granted ->
                refreshShizukuState()
                if (granted && pendingShizukuBootstrapAfterGrant) {
                    pendingShizukuBootstrapAfterGrant = false
                    triggerShizukuActivation()
                } else if (!granted) {
                    pendingShizukuBootstrapAfterGrant = false
                    actStatusColor = HyperAmber
                    actStatusText = "未授予 Shizuku 权限，请允许授权或在 Shizuku 管理页手动勾选 OpalDesk。"
                }
            },
        )
    }

    fun unbindShizukuCallbacks() {
        pendingShizukuBootstrapAfterGrant = false
        ShizukuBootstrap.unbindUiCallbacks()
    }

    fun openActivationSheet() {
        userSkippedActivation = false; hostEdited = false; pairPortEdited = false; connPortEdited = false
        pairHost = AdbBootstrap.getLocalIpv4()
        hasSuHint = AdbBootstrap.hasSuBinaryHint()
        autoRootEnabled = AppPrefs.isAutoRootEnabled(appCtx)
        autoShizukuEnabled = AppPrefs.isAutoShizukuEnabled(appCtx)
        refreshShizukuState()
        activationInitTab = when {
            shizukuAlive || autoShizukuEnabled -> 1
            hasSuHint || autoRootEnabled -> 2
            else -> 0
        }
        actStatusText = ""; showActivationSheet = true
        if (activationInitTab == 0) startMdnsDiscovery()
    }

    fun onPairHostEdited(value: String) { pairHost = value; hostEdited = true }
    fun onPairPortEdited(value: String) { pairPort = value; pairPortEdited = true }
    fun onConnPortEdited(value: String) { connPort = value; connPortEdited = true }
    fun skipActivation() { userSkippedActivation = true; showActivationSheet = false; AdbBootstrap.stopDiscovery() }

    fun startMdnsDiscovery() {
        AdbBootstrap.startDiscovery(appCtx) { ds ->
            if (ds == null) return@startDiscovery
            val pStr = if (ds.pairingPort > 0) "${ds.pairingHost}:${ds.pairingPort}" else "待开启配对窗"
            val cStr = if (ds.connectPort > 0) "${ds.connectHost}:${ds.connectPort}" else "待检测"
            mdnsHint = "mDNS 自动发现 · 配对 [$pStr] · 连接 [$cStr]"
            if (!hostEdited) ds.pairingHost.ifBlank { ds.connectHost }.takeIf { it.isNotBlank() }?.let { pairHost = it }
            if (!pairPortEdited && ds.pairingPort > 0) pairPort = ds.pairingPort.toString()
            if (!connPortEdited && ds.connectPort > 0) connPort = ds.connectPort.toString()
        }
    }

    fun triggerShizukuActivation() {
        refreshShizukuState()
        if (!shizukuAlive) {
            ShizukuBootstrap.openShizukuAppOrWebsite(appCtx)
            return
        }
        if (ShizukuBootstrap.isPreV11()) {
            actStatusColor = HyperRed
            actStatusText = "当前 Shizuku 版本过低（需 v11 及以上），请先升级 Shizuku。"
            return
        }
        if (!shizukuGranted) {
            if (ShizukuBootstrap.shouldShowRationale()) {
                actStatusColor = HyperAmber
                actStatusText = "曾在弹窗中选择不再询问，正在打开 Shizuku 供手动勾选授权…"
                ShizukuBootstrap.openShizukuAppOrWebsite(appCtx)
                return
            }
            pendingShizukuBootstrapAfterGrant = true
            actStatusColor = HyperBlue
            actStatusText = "请在弹出的 Shizuku 权限窗口中点击允许…"
            if (!ShizukuBootstrap.requestPermissionIfPossible()) {
                pendingShizukuBootstrapAfterGrant = false
                actStatusColor = HyperRed
                actStatusText = "无法拉起 Shizuku 授权弹窗，请打开 Shizuku 手动授权。"
            }
            return
        }
        beginBootstrap("正在通过 Shizuku 激活守护进程…", markAutoShizukuOnOk = true) { l ->
            AdbBootstrap.shizukuBootstrapAsync(appCtx, true, l)
        }
    }

    fun openShizukuApp() {
        ShizukuBootstrap.openShizukuAppOrWebsite(appCtx)
    }

    fun triggerRootActivation() = beginBootstrap("正在请求 Root 权限并注册启动服务…", markAutoRootOnOk = true) { l ->
        AdbBootstrap.rootBootstrapAsync(appCtx, true, l)
    }

    fun triggerWirelessPair() = beginBootstrap("正在执行无线调试配对并启动守护进程…") { l ->
        val host = pairHost.trim()
        AdbBootstrap.pairAndBootstrapAsync(appCtx, host, pairPort.trim().toIntOrNull() ?: 0, pairCode.trim(), host, connPort.trim().toIntOrNull() ?: 0, l)
    }

    fun triggerWirelessConnect() = beginBootstrap("正在直连无线调试并启动守护进程…") { l ->
        AdbBootstrap.connectAndBootstrapAsync(appCtx, pairHost.trim(), connPort.trim().toIntOrNull() ?: 0, l)
    }

    private fun beginBootstrap(
        statusMsg: String,
        markAutoRootOnOk: Boolean = false,
        markAutoShizukuOnOk: Boolean = false,
        launch: (AdbBootstrap.ProgressListener) -> Unit,
    ) {
        if (actWorking) return
        actWorking = true; actStatusColor = HyperBlue; actStatusText = statusMsg
        launch(object : AdbBootstrap.ProgressListener {
            override fun onStep(message: String?) { actStatusColor = HyperBlue; actStatusText = message.orEmpty() }
            override fun onResult(success: Boolean, message: String?) {
                actWorking = false; actStatusColor = if (success) HyperGreen else HyperRed; actStatusText = message.orEmpty()
                if (success && markAutoRootOnOk) autoRootEnabled = true
                if (success && markAutoShizukuOnOk) autoShizukuEnabled = true
                refreshStatusNow(syncFromDaemon = true)
            }
        })
    }

    // --- Per-App Display Rules ---

    fun loadAppRules() {
        if (appRulesLoading) return
        appRulesLoading = true
        runDaemonAction("loadAppRules", onFinally = { appRulesLoading = false }, block = {
            val modes = BridgeClient.getAllAppModes()
            val pm = appCtx.packageManager
            val ris = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            val dedup = LinkedHashMap<String, AppRuleEntry>()
            for (ri in ris) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == appCtx.packageName || dedup.containsKey(pkg)) continue
                val label = ri.loadLabel(pm).toString().ifBlank { pkg }
                val iconBmp = drawableToImageBitmap(runCatching { ri.loadIcon(pm) }.getOrNull(), 88)
                dedup[pkg] = AppRuleEntry(pkg, label, iconBmp, modes[pkg] ?: 0)
            }
            dedup.values.sortedWith(compareBy<AppRuleEntry> { if (it.mode > 0) 0 else 1 }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        }) { sorted ->
            appRuleItems.clear(); appRuleItems.addAll(sorted); appRulesLoadedOnce = true
        }
    }

    fun updateAppRuleMode(item: AppRuleEntry, nextMode: Int) {
        if (nextMode == item.mode) return
        appRuleItems.indexOfFirst { it.pkg == item.pkg }.takeIf { it >= 0 }?.let { appRuleItems[it] = appRuleItems[it].copy(mode = nextMode) }
        runDaemonAction("setAppMode(${item.pkg})", block = { BridgeClient.setAppMode(item.pkg, nextMode, true) }) {
            BridgeService.refreshFromReceiver(appCtx)
            toast("${item.label}：${BridgeLabels.appModeLabel(nextMode)}")
        }
    }

    fun resetAllAppRules() = runDaemonAction("resetAllAppModes", block = { BridgeClient.resetAllAppModes() }) {
        for (i in appRuleItems.indices) if (appRuleItems[i].mode != 0) appRuleItems[i] = appRuleItems[i].copy(mode = 0)
        BridgeService.refreshFromReceiver(appCtx); toast("已恢复全部应用为系统默认")
    }

    // --- Sheet State/Action Builders & Utils ---

    fun toRemoteMacUiState() = RemoteMacUiState(
        showRemoteMacSheet, daemonStatus, localLanIp, wifiServerBusy,
        remoteCodecPref, remoteMaxBitrateMbps, remoteAudioEnabled,
        trustedMacClients.toList(), trustedMacLoading,
    )

    fun toRemoteMacActions() = RemoteMacActions(
        onToggleWifiServer = ::toggleWifiRemoteServer,
        onRegeneratePin = ::regenerateWifiPairingPin,
        onCopyText = { text, msg -> copyToClipboard("opaldesk_remote", text, msg) },
        onDisconnectRemote = ::disconnectRemoteMac,
        onCodecPrefChange = { remoteCodecPref = it; saveAndPushRemoteStreamPrefs(showToast = false) },
        onMaxBitrateChange = { remoteMaxBitrateMbps = it },
        onAudioEnabledChange = { remoteAudioEnabled = it; saveAndPushRemoteStreamPrefs(showToast = false) },
        onApplyStreamPrefs = { saveAndPushRemoteStreamPrefs(showToast = true) },
        onRefreshTrusted = ::loadTrustedMacClients,
        onRevokeClient = ::revokeTrustedMacClient,
        onRevokeAllClients = { revokeTrustedMacClient("*") },
        onDismiss = { showRemoteMacSheet = false },
    )

    fun toActivationUiState(pcAdbCmd: String) = ActivationUiState(
        show = showActivationSheet,
        initialTab = activationInitTab,
        hasSuHint = hasSuHint,
        autoRootEnabled = autoRootEnabled,
        shizukuInstalled = shizukuInstalled,
        shizukuAlive = shizukuAlive,
        shizukuGranted = shizukuGranted,
        shizukuSummary = shizukuSummary,
        autoShizukuEnabled = autoShizukuEnabled,
        mdnsHint = mdnsHint,
        pairHost = pairHost,
        pairPort = pairPort,
        pairCode = pairCode,
        connPort = connPort,
        statusText = actStatusText,
        statusColor = actStatusColor,
        working = actWorking,
        pcAdbCommand = pcAdbCmd,
    )

    fun toActivationActions(pcAdbCmd: String, onOpenDevOptions: () -> Unit, onSendPairNotif: () -> Unit) = ActivationActions(
        onAutoRootChange = { autoRootEnabled = it; AppPrefs.setAutoRootEnabled(appCtx, it) },
        onAutoShizukuChange = { autoShizukuEnabled = it; AppPrefs.setAutoShizukuEnabled(appCtx, it) },
        onPairHostChange = ::onPairHostEdited,
        onPairPortChange = ::onPairPortEdited,
        onPairCodeChange = { pairCode = it },
        onConnPortChange = ::onConnPortEdited,
        onSelectWirelessTab = ::startMdnsDiscovery,
        onSelectShizukuTab = ::refreshShizukuState,
        onRunShizuku = ::triggerShizukuActivation,
        onOpenShizuku = ::openShizukuApp,
        onRunRoot = ::triggerRootActivation,
        onOpenDevOptions = onOpenDevOptions,
        onSendPairNotif = onSendPairNotif,
        onRunWirelessPair = ::triggerWirelessPair,
        onRunWirelessConnect = ::triggerWirelessConnect,
        onCopyAdbCmd = { copyToClipboard("opaldesk_adb", pcAdbCmd, "已复制 ADB 激活命令到剪贴板") },
        onSkip = ::skipActivation,
    )

    fun copyToClipboard(label: String, text: String, toastMsg: String) {
        (appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(toastMsg)
    }

    fun toast(msg: String, long: Boolean = false) =
        Toast.makeText(appCtx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    fun dispose() { unbindShizukuCallbacks(); AdbBootstrap.stopDiscoveryIfIdle(); scope.cancel() }
}

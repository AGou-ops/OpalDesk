package io.github.lengsukq.opaldesk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.github.lengsukq.opaldesk.adb.ShizukuBootstrap
import io.github.lengsukq.opaldesk.cast.OpalPeerCastMutex
import io.github.lengsukq.opaldesk.cast.OpalPeerDiscovery
import io.github.lengsukq.opaldesk.cast.PeerCastViewerActivity
import io.github.lengsukq.opaldesk.cast.RemoteAppPickerModal
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.external.ExternalTrackpadLauncher
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
import io.github.lengsukq.opaldesk.service.DisplayLaunchHelper
import io.github.lengsukq.opaldesk.ui.ActivationModal
import io.github.lengsukq.opaldesk.ui.AppUpdateModal
import io.github.lengsukq.opaldesk.ui.ConsoleDashboardTab
import io.github.lengsukq.opaldesk.ui.ConsoleDisplaySettingsTab
import io.github.lengsukq.opaldesk.ui.ConsoleEngineSettingsTab
import io.github.lengsukq.opaldesk.ui.ConsoleLogsTab
import io.github.lengsukq.opaldesk.ui.OpalAmbientBackdrop
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalGlassCard
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalSegmentedRow
import io.github.lengsukq.opaldesk.ui.OpalStatusBadge
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.OpalTheme
import io.github.lengsukq.opaldesk.ui.PairingConsentModal
import io.github.lengsukq.opaldesk.update.AppUpdateManager

/**
 * OpalDesk v1.0.0 macOS Liquid Glass Control Console.
 *
 * Unifies:
 * 1. On-device Local DeX Workstation ([DesktopActivity])
 * 2. USB-C / HDMI Full-Bleed External Monitor & Precision Trackpad ([ExternalDesktopActivity])
 * 3. MacOS OpalCast v1.1 USB + Wi-Fi TLS Remote Streaming
 * 4. Display Resolution / EDID Refresh Rate / FPS / DPI / Auto-Adapt & Engine Pool Tuning
 */
class MainActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var localNetworkPermGranted by mutableStateOf(true)
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }
    private val localNetPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        localNetworkPermGranted = granted || OpalPeerDiscovery.hasLocalNetworkPermission(this)
        if (localNetworkPermGranted) {
            val r = BridgeClient.remoteStatusFlow.value
            OpalPeerDiscovery.syncAdvertise(this, r.wifiEnabled, r.wifiPort, r.shortCertFingerprint)
            OpalPeerDiscovery.startBrowsing(this, r.lanIp)
        }
    }
    private val wallpaperPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread({
            val saved = WallpaperStorage.importWallpaperFromUri(this, uri)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (saved != null) "已更换桌面壁纸" else "无法读取所选图片",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }, "OpalWallpaperImport").start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpalLogStore.init(applicationContext)
        io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.hydrate(applicationContext)
        BridgeClient.bindAppContext(applicationContext)
        OpalLogStore.i("App", "MainActivity started (API ${Build.VERSION.SDK_INT})")
        WindowCompat.setDecorFitsSystemWindows(window, false)

        requestNotificationPermissionIfNeeded()
        localNetworkPermGranted = OpalPeerDiscovery.hasLocalNetworkPermission(this)
        BridgeService.sUserStopping = false
        BridgeService.ensureStarted(this)
        BridgeClient.requestBinder(this)
        scheduleSilentAutoBootstrapIfConfigured()

        setContent {
            OpalTheme {
                val isDark = OpalPalette.isDark
                LaunchedEffect(isDark) {
                    val ctrl = WindowCompat.getInsetsController(window, window.decorView)
                    ctrl.isAppearanceLightStatusBars = !isDark
                    ctrl.isAppearanceLightNavigationBars = !isDark
                }
                OpalConsoleScreen(
                    needsLocalNetworkPerm = !localNetworkPermGranted,
                    onRequestLocalNetworkPerm = {
                        if (Build.VERSION.SDK_INT >= 37) {
                            localNetPermLauncher.launch(OpalPeerDiscovery.PERMISSION_ACCESS_LOCAL_NETWORK)
                        }
                    },
                    onEnterDesktop = ::launchDesktopWorkstation,
                    onOpenRemoteTrackpad = ::launchRemoteTrackpad,
                    onProjectExternalOrTrackpad = ::launchExternalOrTrackpad,
                    onConnectPeerCast = ::launchPeerCastViewer,
                    onPickCustomWallpaper = { wallpaperPicker.launch("image/*") },
                    onStopEngine = {
                        BridgeService.stopAllAndExit(this)
                    },
                    onCompleteExitApp = {
                        BridgeService.completeExitApp(this)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        BridgeClient.requestBinder(this)
        localNetworkPermGranted = OpalPeerDiscovery.hasLocalNetworkPermission(this)
        if (localNetworkPermGranted) {
            OpalPeerDiscovery.startBrowsing(this, BridgeClient.remoteStatusFlow.value.lanIp)
        }
        Thread({
            BridgeClient.refreshStatus()
            BridgeClient.refreshSceneSnapshot()
            val r = BridgeClient.refreshRemoteStatus()
            BridgeClient.refreshTrustedPeers()
            val pending = BridgeClient.refreshPendingPairing()
            BridgeService.updatePairingNotification(this, pending)
            OpalPeerCastMutex.syncDaemonSendingState(r.connected, r.peerName)
            val hasLocalNet = OpalPeerDiscovery.hasLocalNetworkPermission(this)
            runOnUiThread { localNetworkPermGranted = hasLocalNet }
            if (hasLocalNet) {
                OpalPeerDiscovery.syncAdvertise(this, r.wifiEnabled, r.wifiPort, r.shortCertFingerprint)
                OpalPeerDiscovery.startBrowsing(this, r.lanIp)
            }
        }, "OpalConsoleRefresh").start()
    }

    override fun onStop() {
        OpalPeerDiscovery.stopBrowsing()
        super.onStop()
    }

    private fun launchPeerCastViewer(host: String, port: Int, label: String) {
        val remote = BridgeClient.remoteStatusFlow.value
        val check = OpalPeerCastMutex.validateStartReceiving(
            daemonSending = remote.connected,
            sendingPeerName = remote.peerName,
            targetHost = host,
            selfLanIp = remote.lanIp,
        )
        if (!check.allowed) {
            Toast.makeText(this, check.reason, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(
            Intent(this, PeerCastViewerActivity::class.java).apply {
                putExtra(PeerCastViewerActivity.EXTRA_HOST, host)
                putExtra(PeerCastViewerActivity.EXTRA_PORT, port)
                putExtra(PeerCastViewerActivity.EXTRA_LABEL, label)
            },
        )
    }

    private fun launchDesktopWorkstation() {
        if (BridgeClient.remoteStatusFlow.value.connected) {
            Toast.makeText(
                this,
                "投屏进行中，无法进入本机桌面，请使用本机触控板或先断开投屏",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        startActivity(
            Intent(this, DesktopActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }

    private fun launchRemoteTrackpad() {
        if (!ExternalTrackpadLauncher.openPhoneTrackpad(this)) {
            Toast.makeText(this, "当前未处于外接屏或桌面投屏状态", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchExternalOrTrackpad(displayId: Int) {
        if (ExternalTrackpadBridge.sessionFlow.value.isPhysicalActive) {
            ExternalTrackpadLauncher.openPhoneTrackpad(this)
            return
        }
        val intent = Intent(this, ExternalDesktopActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("targetDisplayId", displayId)
        }
        DisplayLaunchHelper.launchOnDisplay(this, intent, displayId)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun scheduleSilentAutoBootstrapIfConfigured() {
        mainHandler.postDelayed({
            if (BridgeClient.statusFlow.value.connected || BridgeService.sUserStopping) return@postDelayed
            if (AppPrefs.isAutoShizukuEnabled(this) && ShizukuBootstrap.isReadyAndGranted()) {
                AdbBootstrap.shizukuBootstrapAsync(this, markAutoShizuku = true, listener = null)
            } else if (AppPrefs.isAutoRootEnabled(this) && AdbBootstrap.hasSuBinaryHint()) {
                AdbBootstrap.rootBootstrapAsync(
                    ctx = this,
                    markAutoRoot = true,
                    rootEnhance = AppPrefs.isRootEnhanceEnabled(this),
                    listener = null,
                )
            }
        }, 700L)
    }
}

@Composable
private fun OpalConsoleScreen(
    needsLocalNetworkPerm: Boolean,
    onRequestLocalNetworkPerm: () -> Unit,
    onEnterDesktop: () -> Unit,
    onOpenRemoteTrackpad: () -> Unit,
    onProjectExternalOrTrackpad: (Int) -> Unit,
    onConnectPeerCast: (String, Int, String) -> Unit,
    onPickCustomWallpaper: () -> Unit,
    onStopEngine: () -> Unit,
    onCompleteExitApp: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val status by BridgeClient.statusFlow.collectAsState()
    val scene by BridgeClient.sceneFlow.collectAsState()
    val remote by BridgeClient.remoteStatusFlow.collectAsState()
    val pendingPairing by BridgeClient.pendingPairingFlow.collectAsState()
    val extDisplay by BridgeService.externalDisplayFlow.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showActivationModal by remember { mutableStateOf(false) }
    var showUpdateModal by remember { mutableStateOf(false) }
    var showExitConfirmModal by remember { mutableStateOf(false) }
    var exitingApp by remember { mutableStateOf(false) }
    var floatingPickerTarget by remember { mutableStateOf<Triple<String, Int, String>?>(null) }
    val wallpaperPreset by AppPrefs.wallpaperPresetFlow.collectAsState()
    val updateState by AppUpdateManager.stateFlow.collectAsState()
    val currentVersion = remember(ctx) { AppUpdateManager.getCurrentVersionName(ctx) }

    LaunchedEffect(Unit) {
        AppUpdateManager.maybeSilentCheckOnStartup(ctx)
    }

    val availableReleaseTag = when (val st = updateState) {
        is AppUpdateManager.UpdateState.UpdateAvailable -> st.release.tagName
        is AppUpdateManager.UpdateState.Downloading -> st.release.tagName
        is AppUpdateManager.UpdateState.ReadyToInstall -> st.release.tagName
        else -> null
    }

    OpalAmbientBackdrop(modifier = Modifier.fillMaxSize(), wallpaperPreset = wallpaperPreset) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Clean Apple-style Top Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OpalText(text = "OpalDesk", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            OpalText(
                                text = "v$currentVersion",
                                fontSize = 12.sp,
                                color = OpalPalette.TextMuted,
                                modifier = Modifier.padding(bottom = 3.dp),
                            )
                        }
                        if (availableReleaseTag != null) {
                            val badgeColor = if (updateState is AppUpdateManager.UpdateState.ReadyToInstall) {
                                OpalPalette.AccentEmerald
                            } else {
                                OpalPalette.AccentBlue
                            }
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(badgeColor.copy(alpha = if (OpalPalette.isDark) 0.22f else 0.14f))
                                    .border(0.8.dp, badgeColor.copy(alpha = 0.42f), CircleShape)
                                    .clickable { showUpdateModal = true }
                                    .padding(horizontal = 9.dp, vertical = 3.5.dp),
                            ) {
                                OpalText(
                                    text = if (updateState is AppUpdateManager.UpdateState.ReadyToInstall) {
                                        "安装 $availableReleaseTag"
                                    } else {
                                        "新版本 $availableReleaseTag"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = badgeColor,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (status.connected && status.rootEnhanced) {
                            OpalStatusBadge(label = "Root 增强", color = OpalPalette.AccentEmerald)
                        }
                        OpalStatusBadge(
                            label = if (status.connected) "引擎就绪" else "未激活",
                            color = if (status.connected) OpalPalette.AccentEmerald else OpalPalette.AccentAmber,
                        )
                    }
                }

                // macOS Floating Glass Segmented Tab Switcher
                OpalSegmentedRow(
                    options = listOf("工作站", "显示外设", "设置", "日志"),
                    selectedIndex = selectedTab,
                    onSelect = { selectedTab = it },
                )

                when (selectedTab) {
                    0 -> ConsoleDashboardTab(
                        status = status,
                        scene = scene,
                        remote = remote,
                        extDisplay = extDisplay,
                        needsLocalNetworkPerm = needsLocalNetworkPerm,
                        onRequestLocalNetworkPerm = onRequestLocalNetworkPerm,
                        onEnterLocalDesktop = onEnterDesktop,
                        onOpenRemoteTrackpad = onOpenRemoteTrackpad,
                        onOpenExternalOrTrackpad = onProjectExternalOrTrackpad,
                        onConnectPeerCast = onConnectPeerCast,
                        onOpenFloatingAppPicker = { host, port, label ->
                            val check = OpalPeerCastMutex.validateStartReceiving(
                                daemonSending = remote.connected,
                                sendingPeerName = remote.peerName,
                                targetHost = host,
                                selfLanIp = remote.lanIp,
                            )
                            if (!check.allowed) {
                                Toast.makeText(ctx, check.reason, Toast.LENGTH_LONG).show()
                            } else {
                                floatingPickerTarget = Triple(host, port, label)
                            }
                        },
                        onOpenActivation = { showActivationModal = true },
                        onRequestCompleteExit = { showExitConfirmModal = true },
                    )
                    1 -> ConsoleDisplaySettingsTab(
                        status = status,
                        remote = remote,
                        extDisplay = extDisplay,
                        onPickCustomWallpaper = onPickCustomWallpaper,
                    )
                    2 -> ConsoleEngineSettingsTab(
                        status = status,
                        scene = scene,
                        onOpenActivation = { showActivationModal = true },
                        onOpenUpdateModal = { showUpdateModal = true },
                        onStopEngine = onStopEngine,
                    )
                    else -> ConsoleLogsTab(
                        status = status,
                        scene = scene,
                        remote = remote,
                    )
                }
            }
        }

        if (showActivationModal) {
            ActivationModal(
                onDismiss = { showActivationModal = false },
                onActivated = { showActivationModal = false },
            )
        }

        if (showUpdateModal) {
            AppUpdateModal(
                onDismiss = { showUpdateModal = false },
            )
        }

        if (showExitConfirmModal) {
            val detailText = buildString {
                append("将停止多窗引擎守护进程、投屏及后台服务并彻底退出应用。")
                if (scene.windows.isNotEmpty()) {
                    append("当前共有 ${scene.windows.size} 个窗口，可选择保存到下方槽位后再退出。")
                }
            }
            io.github.lengsukq.opaldesk.ui.ExitSaveSlotModal(
                title = "完全退出 OpalDesk",
                subtitle = detailText,
                confirmLabel = "保存并退出",
                openWindowCount = scene.windows.size,
                busy = exitingApp,
                onDismiss = { if (!exitingApp) showExitConfirmModal = false },
                onExitWithoutSaving = {
                    if (!exitingApp) {
                        exitingApp = true
                        onCompleteExitApp()
                    }
                },
                onSaveToSlotAndExit = { slotId, saveLayout ->
                    if (!exitingApp) {
                        exitingApp = true
                        Thread({
                            BridgeClient.pullDaemonWindowBoundsSync()
                            val curTarget = io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.resolveDisplayTarget(
                                isExternalMonitor = extDisplay != null,
                                remoteConnected = remote.connected,
                                remotePeerName = remote.peerName,
                                externalDisplayName = extDisplay?.name.orEmpty(),
                                externalWidth = extDisplay?.width ?: 0,
                                externalHeight = extDisplay?.height ?: 0,
                            )
                            io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.saveWorkingToSlot(
                                ctx = ctx,
                                targetSlotId = slotId,
                                scene = scene,
                                saveCurrentLayout = saveLayout,
                                bindTargetKey = curTarget.key,
                            )
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onCompleteExitApp()
                            }
                        }, "OpalSaveAndExit").start()
                    }
                },
            )
        }

        floatingPickerTarget?.let { (host, port, label) ->
            RemoteAppPickerModal(
                host = host,
                port = port,
                peerLabel = label,
                onDismiss = { floatingPickerTarget = null },
                onFloatingLaunched = { floatingPickerTarget = null },
            )
        }

        pendingPairing?.let { req ->
            PairingConsentModal(request = req)
        }
    }
}

@Composable
private fun ConfirmCompleteExitModal(
    connected: Boolean,
    openWindowCount: Int,
    casting: Boolean,
    exiting: Boolean,
    onDismiss: () -> Unit,
    onConfirmExit: () -> Unit,
) {
    val detailText = buildString {
        append("将停止多窗引擎守护进程、局域网/悬浮后台服务并彻底结束应用进程。")
        val activeItems = buildList {
            if (openWindowCount > 0) add("已打开的 $openWindowCount 个窗口")
            if (casting) add("进行中的投屏会话")
        }
        if (activeItems.isNotEmpty()) {
            append("\n当前")
            append(activeItems.joinToString("与"))
            append("将会一并关闭。")
        } else if (connected) {
            append("\n下次打开应用后需重新激活引擎（若已开启 Shizuku / Root 自动重连则会自动拉起）。")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OpalPalette.ScrimColor)
            .clickable(enabled = !exiting, onClick = onDismiss)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        OpalGlassCard(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures {} },
            cornerRadius = 24.dp,
            fillColor = OpalPalette.GlassPopover,
            contentPadding = PaddingValues(20.dp),
        ) {
            OpalText(text = "完全退出 OpalDesk？", fontSize = 17.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OpalText(
                text = detailText,
                fontSize = 13.sp,
                color = OpalPalette.TextSecondary,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OpalButton(
                    label = "取消",
                    filled = false,
                    enabled = !exiting,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                OpalButton(
                    label = if (exiting) "正在退出…" else "确认退出",
                    accent = OpalPalette.AccentDanger,
                    enabled = !exiting,
                    onClick = onConfirmExit,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

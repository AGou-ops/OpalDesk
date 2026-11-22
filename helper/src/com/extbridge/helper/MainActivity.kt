package com.extbridge.helper

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.extbridge.helper.design.iosglass.CrossWindowBlurController
import com.extbridge.helper.design.iosglass.LiquidGlassTheme
import com.extbridge.helper.design.iosglass.LiquidMeshBackground
import com.extbridge.helper.design.iosglass.LiquidShapes
import com.extbridge.helper.design.iosglass.SquircleShape
import com.extbridge.helper.design.iosglass.rememberAdaptiveViewportInfo
import com.extbridge.helper.prefs.AppPrefs
import com.extbridge.helper.protocol.BridgeLabels
import com.extbridge.helper.service.DisplayLaunchHelper
import com.extbridge.helper.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ExtMainActivity"
    }

    private lateinit var ctrl: MainController
    private lateinit var perms: MainPermissionHelper
    private lateinit var widgetBinder: MainWidgetBinder

    private val binderReadyCb = Runnable {
        if (::ctrl.isInitialized && !isFinishing && !isDestroyed) {
            ctrl.scope.launch { ctrl.refreshStatusNow(syncFromDaemon = true) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DisplayLaunchHelper.ensureOnDisplay0(this)) return
        enableEdgeToEdge()
        CrossWindowBlurController.applyToActivityWindow(window)

        ctrl = MainController(this)
        widgetBinder = MainWidgetBinder(this, ctrl.scope) { ctrl.syncLocalPrefs(widgetBinder::resolveBoundWidgetLabel) }
        perms = MainPermissionHelper(
            activity = this,
            onPermissionsChanged = { ctrl.refreshStatusNow(syncFromDaemon = false) },
            onStartupOnboardingFinished = { scheduleAutoPromptIfReady(200L) },
        )

        BridgeService.sUserStopping = false
        BridgeService.ensureStarted(this)
        ctrl.syncLocalPrefs(widgetBinder::resolveBoundWidgetLabel)
        perms.refreshSnapshot()
        ctrl.refreshStatusNow(syncFromDaemon = true)

        if (perms.shouldRunFirstLaunchOnboarding()) {
            perms.beginStartupOnboarding()
            ctrl.scope.launch { delay(220L); perms.startFirstLaunchPermissions() }
        }

        val versionName = try { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }
        catch (t: Throwable) { Log.w(TAG, "Failed to read package versionName", t); "" }

        setContent {
            ExtHyperTheme(themeModeIndex = ctrl.themeModeIdx) {
                val dark = isEffectiveDark(ctrl.themeModeIdx)
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
                    }
                }
                MainAppShell(versionName)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::ctrl.isInitialized) return
        BridgeClient.setOnBinderReady(binderReadyCb)
        if (BridgeClient.getService() == null) BridgeClient.requestBinder(this)
        BridgeService.sUserStopping = false
        BridgeService.ensureStarted(this)
        ctrl.syncLocalPrefs(widgetBinder::resolveBoundWidgetLabel)
        if (!perms.onResumeReturnIfNeeded()) ctrl.refreshStatusNow(syncFromDaemon = false)
        ctrl.startForegroundSync()
        if (!perms.startupPermInProgress) scheduleAutoPromptIfReady(450L)
    }

    override fun onPause() {
        super.onPause()
        BridgeClient.removeOnBinderReady(binderReadyCb)
        if (::ctrl.isInitialized) ctrl.stopForegroundSync()
    }

    override fun onDestroy() {
        if (::ctrl.isInitialized) ctrl.dispose()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (::widgetBinder.isInitialized) widgetBinder.onActivityResult(requestCode, resultCode)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        @Suppress("DEPRECATION") super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (::perms.isInitialized) perms.onRequestPermissionsResult(requestCode)
    }

    private fun scheduleAutoPromptIfReady(delayMs: Long) =
        ctrl.scheduleAutoPrompt(delayMs) { isFinishing || perms.startupPermInProgress || perms.showPermDialog }

    private fun openActivityOnDisplay0(cls: Class<*>) {
        if (!DisplayLaunchHelper.launchOnDisplay0(this, Intent(this, cls))) startActivity(Intent(this, cls))
    }

    private fun openWidgetSlot(slot: Int) {
        ctrl.activeWidgetSlot = slot
        ctrl.syncLocalPrefs(widgetBinder::resolveBoundWidgetLabel)
        ctrl.showWidgetSheet = true
        widgetBinder.ensureCandidatesLoaded()
    }

    @Composable
    private fun MainAppShell(versionName: String) {
        val navItems = remember {
            listOf(
                Triple("控制台", ExtVectorIcons.Dashboard, "ExtBridge"),
                Triple("显示与边栏", ExtVectorIcons.DisplayTuning, "显示与边栏"),
                Triple("应用规则", ExtVectorIcons.AppRules, "分应用显示规则"),
                Triple("设置", ExtVectorIcons.SettingsGear, "设置与服务"),
            )
        }
        val subtitle = remember(versionName, ctrl.daemonStatus, ctrl.localLanIp) {
            val short = BridgeLabels.classifyConnectionSummary(ctrl.daemonStatus, ctrl.localLanIp).shortSubtitle
            if (versionName.isNotBlank()) "v$versionName · $short" else short
        }

        LaunchedEffect(ctrl.navTab, ctrl.daemonStatus.connected) {
            if (ctrl.navTab == 2 && ctrl.daemonStatus.connected && !ctrl.appRulesLoadedOnce && !ctrl.appRulesLoading) {
                ctrl.loadAppRules()
            }
        }

        val viewport = rememberAdaptiveViewportInfo()
        val isWide = viewport.isTwoPaneCapable && viewport.widthDp >= 720
        LiquidMeshBackground(modifier = Modifier.fillMaxSize()) {
            val scrollBehavior = MiuixScrollBehavior()
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    if (!isWide) TopAppBar(navItems[ctrl.navTab].third, subtitle = subtitle, scrollBehavior = scrollBehavior, actions = { TopBarQuickActions() })
                },
                bottomBar = {
                    if (!isWide) {
                        IosFloatingGlassBottomBar(
                            items = navItems,
                            selectedIndex = ctrl.navTab,
                            onSelect = { ctrl.navTab = it },
                        )
                    }
                },
            ) { innerPadding ->
                if (isWide) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        NavigationRail(
                            header = {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp).size(40.dp).clip(SquircleShape(13.dp))
                                        .background(HyperBlue.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(ExtVectorIcons.MonitorDex, "ExtBridge", tint = HyperBlue, modifier = Modifier.size(20.dp)) }
                            },
                        ) {
                            navItems.forEachIndexed { idx, (label, icon, _) ->
                                NavigationRailItem(selected = ctrl.navTab == idx, onClick = { ctrl.navTab = idx }, icon = icon, label = label)
                            }
                        }
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            SmallTopAppBar(navItems[ctrl.navTab].third, subtitle = subtitle, actions = { TopBarQuickActions() })
                            Box(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                                ActiveTabContent(isWide = true, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp))
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
                        ActiveTabContent(
                            isWide = false,
                            contentPadding = PaddingValues(
                                start = 16.dp, end = 16.dp,
                                top = innerPadding.calculateTopPadding() + 8.dp,
                                bottom = innerPadding.calculateBottomPadding() + 24.dp,
                            ),
                        )
                    }
                }
                MainOverlays()
            }
        }
    }

    @Composable
    private fun IosFloatingGlassBottomBar(
        items: List<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, String>>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
    ) {
        val glass = LiquidGlassTheme.colors
        val rim = remember(glass) {
            Brush.linearGradient(listOf(glass.specularTopLeft, glass.specularMid, glass.specularBottomRight))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(LiquidShapes.Capsule)
                    .background(glass.glassThick)
                    .border(1.dp, rim, LiquidShapes.Capsule)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { idx, (label, icon, _) ->
                    val selected = idx == selectedIndex
                    val tint = if (selected) Color.White else glass.textSecondary
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(LiquidShapes.Capsule)
                            .background(if (selected) glass.accentBlue else Color.Transparent)
                            .clickable { onSelect(idx) }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, label, tint = tint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(label, fontSize = 11.5.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = tint, maxLines = 1)
                    }
                }
            }
        }
    }

    @Composable
    private fun TopBarQuickActions() {
        if (!ctrl.daemonStatus.connected) {
            IconButton(onClick = { ctrl.openActivationSheet() }) { Icon(ExtVectorIcons.BoltActivate, "激活服务", tint = HyperAmber) }
        }
        IconButton(onClick = { ctrl.showDiagSheet = true }) { Icon(ExtVectorIcons.Diagnostics, "连接诊断") }
    }

    @Composable
    private fun ActiveTabContent(isWide: Boolean, contentPadding: PaddingValues) {
        when (ctrl.navTab) {
            0 -> DashboardPage(
                ctrl, perms.allCoreGranted, isWide, contentPadding,
                onOpenPermDialog = { perms.openPermDialog(false) },
                onOpenLocalDex = {
                    when {
                        !ctrl.daemonStatus.connected -> ctrl.openActivationSheet()
                        ctrl.daemonStatus.isRemoteSink -> ctrl.toast("Mac 远程大屏运行中，请先断开 Mac 连接")
                        else -> openActivityOnDisplay0(LocalDexActivity::class.java)
                    }
                },
                onOpenTouchpad = { openActivityOnDisplay0(TouchpadActivity::class.java) },
                onOpenSideControl = { openActivityOnDisplay0(SideControlActivity::class.java) },
                onOpenWidgetSlot = ::openWidgetSlot,
            )
            1 -> DisplayAndSidebarPage(ctrl, contentPadding, { openActivityOnDisplay0(SideControlActivity::class.java) }, ::openWidgetSlot)
            2 -> AppRulesPage(ctrl, contentPadding)
            else -> SettingsPage(
                ctrl, perms.grantedCount, contentPadding,
                onOpenPermChecklist = { perms.openPermDialog(false) },
                onOpenRestrictedSettings = { perms.openRestrictedSettings(false) },
                onStopAllAndExit = { ctrl.toast("正在停止 ExtBridge 守护进程并退出…"); BridgeService.stopAllAndExit(this) },
            )
        }
    }

    @Composable
    private fun MainOverlays() {
        val pcAdbCmd = remember { AdbBootstrap.buildPcAdbCommand() }
        DiagnosticsSheet(ctrl.showDiagSheet, ctrl.daemonStatus, { ctrl.rebuildRenderPipeline("已发送渲染重建指令") }, { ctrl.disconnectRemoteMac() }) {
            ctrl.showDiagSheet = false
        }
        RemoteMacDisplaySheet(ctrl.toRemoteMacUiState(), ctrl.toRemoteMacActions())
        ActivationSheet(
            ctrl.toActivationUiState(pcAdbCmd),
            ctrl.toActivationActions(
                pcAdbCmd = pcAdbCmd,
                onOpenDevOptions = { perms.openDeveloperOptionsWithGate() },
                onSendPairNotif = {
                    if (perms.ensureNotifPermAndShowPairNotif()) ctrl.toast("已发送配对通知，可在系统弹窗上方下拉直接输入配对码", long = true)
                },
            ),
        )
        PermissionChecklistDialog(
            perms.showPermDialog, perms.permDialogIsDevGate, perms.notifGranted, perms.overlayGranted, perms.a11yGranted,
            { perms.onDialogGrantNotif() }, { perms.onDialogGrantOverlay() }, { perms.onDialogGrantA11y() },
            { perms.openRestrictedSettings(true) }, { perms.onDialogSkipOrContinue() },
        )
        DevOptionsGuideDialog(perms.showDevGuideDialog, { perms.confirmDevGuideAndLaunch() }) { perms.showDevGuideDialog = false }
        CustomResolutionDialog(ctrl.showCustomResDialog, ctrl.selW, ctrl.selH, ctrl::applyCustomResolution) { ctrl.showCustomResDialog = false }
        EdidAndRefreshSheet(ctrl.showEdidSheet, ctrl.selFps, ctrl.daemonStatus.modesRaw, ctrl.selPhysModeId, ctrl::selectTargetFps, ctrl::selectPhysicalEdidMode) {
            ctrl.showEdidSheet = false
        }
        MirrorPresetManagerSheet(
            show = ctrl.showPresetMgrSheet, slots = ctrl.mirrorPresets.toList(),
            onUpdateSlots = { next ->
                AppPrefs.setMirrorPresetSlots(this, next)
                ctrl.mirrorPresets.clear(); ctrl.mirrorPresets.addAll(AppPrefs.getMirrorPresetSlots(this))
            },
            onEditMemo = { ctrl.showMemoDialog = true },
            onResetDefault = {
                AppPrefs.setMirrorPresetSlots(this, null)
                ctrl.mirrorPresets.clear(); ctrl.mirrorPresets.addAll(AppPrefs.getMirrorPresetSlots(this))
                ctrl.toast("已恢复默认 7 张边栏卡片")
            },
            onDismiss = { ctrl.showPresetMgrSheet = false },
        )
        MirrorMemoDialog(ctrl.showMemoDialog, ctrl.mirrorMemoPreview, { txt ->
            AppPrefs.setMirrorMemoText(this, txt); ctrl.mirrorMemoPreview = AppPrefs.getMirrorMemoText(this)
            ctrl.toast("便签内容已同步至外接边栏")
        }) { ctrl.showMemoDialog = false }
        CustomMirrorColorDialog(ctrl.showCustomColorDialog, AppPrefs.getMirrorCustomBg(this), { colorInt ->
            AppPrefs.setMirrorCustomBg(this, colorInt); ctrl.mirrorThemeId = "custom"; ctrl.toast("已应用自定义副屏颜色")
        }) { ctrl.showCustomColorDialog = false }
        MirrorWidgetSlotSheet(
            show = ctrl.showWidgetSheet, slotIndex = ctrl.activeWidgetSlot,
            boundWidgetLabel = if (ctrl.activeWidgetSlot == 0) ctrl.widgetSlot0Label else ctrl.widgetSlot1Label,
            currentRatioIdx = if (ctrl.activeWidgetSlot == 0) ctrl.widgetSlot0Ratio else ctrl.widgetSlot1Ratio,
            candidates = widgetBinder.candidates.toList(), loading = widgetBinder.loading,
            onChangeRatio = { AppPrefs.setMirrorWidgetRatio(this, ctrl.activeWidgetSlot, it); ctrl.syncLocalPrefs(widgetBinder::resolveBoundWidgetLabel) },
            onClearWidget = {
                AppPrefs.setMirrorWidgetId(this, ctrl.activeWidgetSlot, -1); ctrl.syncLocalPrefs(widgetBinder::resolveBoundWidgetLabel)
                ctrl.toast("已清除槽位 ${ctrl.activeWidgetSlot + 1} 小组件")
            },
            onPickCandidate = { widgetBinder.bindChosenWidget(ctrl.activeWidgetSlot, it) },
            onDismiss = { ctrl.showWidgetSheet = false },
        )
    }
}

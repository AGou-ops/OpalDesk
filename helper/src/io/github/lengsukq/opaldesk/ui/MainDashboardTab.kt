package io.github.lengsukq.opaldesk.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.NavAssistService
import io.github.lengsukq.opaldesk.design.iosglass.GlassTier
import io.github.lengsukq.opaldesk.design.iosglass.LiquidGlassSegmentedTabs
import io.github.lengsukq.opaldesk.design.iosglass.LiquidGlassSurface
import io.github.lengsukq.opaldesk.design.iosglass.LiquidGlassTheme
import io.github.lengsukq.opaldesk.design.iosglass.SquircleShape
import io.github.lengsukq.opaldesk.protocol.BridgeLabels
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.InputBackend
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.MirrorMode
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.RemoteStream
import io.github.lengsukq.opaldesk.protocol.ConnectionKind
import io.github.lengsukq.opaldesk.protocol.ConnectionSummary
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun DashboardPage(
    ctrl: MainController,
    allPermsOk: Boolean,
    isWide: Boolean,
    contentPadding: PaddingValues,
    onOpenPermDialog: () -> Unit,
    onOpenLocalDex: () -> Unit,
    onOpenTouchpad: () -> Unit,
    onOpenSideControl: () -> Unit,
    onOpenWidgetSlot: (Int) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The rail already takes part of the window width. Choose the layout using the
        // remaining content width so a portrait tablet doesn't squeeze both columns.
        if (isWide && maxWidth >= 880.dp * fontScale) {
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds(),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    dashboardPrimaryItems(
                        ctrl, allPermsOk, showJumpCard = false,
                        onOpenPermDialog, onOpenLocalDex, onOpenTouchpad, onOpenSideControl,
                    )
                }
                LazyColumn(
                    modifier = Modifier.weight(1.08f).fillMaxHeight().clipToBounds(),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
                ) { dashboardConfigItem(ctrl, onOpenSideControl, onOpenWidgetSlot) }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                dashboardPrimaryItems(
                    ctrl, allPermsOk, showJumpCard = !isWide,
                    onOpenPermDialog, onOpenLocalDex, onOpenTouchpad, onOpenSideControl,
                )
                if (isWide) dashboardConfigItem(ctrl, onOpenSideControl, onOpenWidgetSlot)
            }
        }
    }
}

private fun LazyListScope.dashboardConfigItem(
    ctrl: MainController,
    onOpenSideControl: () -> Unit,
    onOpenWidgetSlot: (Int) -> Unit,
) {
    item(key = "displayConfig") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!ctrl.daemonStatus.isLocalDexActive && ctrl.daemonStatus.mirrorMode == MirrorMode.MIRROR) {
                MirrorConfigSection(ctrl, onOpenSideControl, onOpenWidgetSlot)
            } else {
                DexConfigSection(ctrl)
            }
        }
    }
}

private fun LazyListScope.dashboardPrimaryItems(
    ctrl: MainController,
    allPermsOk: Boolean,
    showJumpCard: Boolean,
    onOpenPermDialog: () -> Unit,
    onOpenLocalDex: () -> Unit,
    onOpenTouchpad: () -> Unit,
    onOpenSideControl: () -> Unit,
) {
    val st = ctrl.daemonStatus
    val connSummary = BridgeLabels.classifyConnectionSummary(st, ctrl.localLanIp)

    if (!allPermsOk) item(key = "permissions") { PermissionWarningCard(onOpenPermDialog) }
    if (st.connected && !st.isLocalDexActive && (st.vdId > 0 || st.extId > 0 || st.isRemoteSink)) {
        item(key = "trackpad") {
            ContinuityTrackpadBanner(
                targetLabel = if (st.isRemoteSink) st.remotePeerName.ifBlank { "Mac 远程屏" } else "USB-C 外接屏",
                resolution = "${st.virtW}×${st.virtH}",
                onOpenTouchpad = onOpenTouchpad,
            )
        }
    }

    item(key = "connection") {
        HeroConnectionCard(
            status = st, summary = connSummary,
            onOpenActivation = { ctrl.openActivationSheet() },
            onOpenDiag = { ctrl.showDiagSheet = true },
            onSwitchToUhid = { ctrl.applyInputBackendMode(InputBackend.UHID) },
            onDisconnectRemote = { ctrl.disconnectRemoteMac() },
            onOpenLocalDex = onOpenLocalDex,
            onStopLocalDex = { ctrl.stopLocalDex() },
            onRebuild = { ctrl.rebuildRenderPipeline("已重建扩展渲染管线") },
        )
    }

    if (!st.isLocalDexActive) {
        item(key = "localDexHeader") { SectionHeader("本机免外接屏") }
        item(key = "localDex") { LocalDexEntryCard(status = st, onClick = onOpenLocalDex) }
        item(key = "remoteHeader") { SectionHeader("Mac / 无线大屏") }
        item(key = "remote") { RemoteMacSummaryCard(st, connSummary, ctrl.localLanIp) { ctrl.openRemoteMacSheet() } }
        item(key = "outputHeader") { SectionHeader("输出模式") }
        item(key = "output") {
            LiquidGlassSegmentedTabs(
                items = listOf("电脑桌面模式", "融合边栏镜像"),
                selectedIndex = if (st.mirrorMode == MirrorMode.MIRROR) 1 else 0,
                onSelect = { ctrl.switchOutputMode(if (it == 1) MirrorMode.MIRROR else MirrorMode.DEX) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    item(key = "shortcutsHeader") { SectionHeader("快捷控制") }
    item(key = "shortcuts") {
        val context = LocalContext.current
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickControlBentoGrid(
                st, ctrl.screenDimmed, { ctrl.toggleInputRouting() }, onOpenTouchpad,
                { ctrl.toggleBlackout() }, onOpenSideControl, onOpenLocalDex, { ctrl.relaunchExtLauncher() },
                onOpenAllApps = {
                    when {
                        BridgeService.isDeXShellAttached(st.vdId) -> {
                            onOpenLocalDex()
                            BridgeService.openAppDrawerFromUi()
                        }
                        !NavAssistService.isRunning() && !Settings.canDrawOverlays(context) -> onOpenPermDialog()
                        else -> ctrl.toast("桌面控件正在连接，请稍后再试")
                    }
                },
            )
        }
    }

    if (showJumpCard) item(key = "settingsShortcut") {
        val isMirror = !st.isLocalDexActive && st.mirrorMode == MirrorMode.MIRROR
        LiquidGlassSurface(cornerRadius = 22.dp, contentPadding = PaddingValues(4.dp)) {
            ArrowPreference(
                title = when {
                    st.isLocalDexActive -> "调节本机 DeX 缩放密度与窗口偏好"
                    isMirror -> "配置镜像布局与融合边栏"
                    else -> "调节分辨率、DPI 与旋转方向"
                },
                summary = when {
                    st.isLocalDexActive -> "原生画布 ${st.virtW}×${st.virtH} · ${st.virtDpi}dpi · 窗口与任务栏偏好"
                    isMirror -> "${BridgeLabels.mirrorSideModeLabel(ctrl.mirrorSideMode)} · 已启用 ${ctrl.mirrorPresets.size} 张信息卡"
                    else -> "画布 ${ctrl.selW}×${ctrl.selH} · ${ctrl.selDpi}dpi · ${BridgeLabels.rotationLabel(ctrl.selRot)}"
                },
                onClick = { ctrl.displaySubTab = if (isMirror) 1 else 0; ctrl.navTab = 1 },
            )
        }
    }
}

@Composable
private fun ContinuityTrackpadBanner(
    targetLabel: String,
    resolution: String,
    onOpenTouchpad: () -> Unit,
) {
    val glass = LiquidGlassTheme.colors
    LiquidGlassSurface(
        cornerRadius = 24.dp,
        tier = GlassTier.Thick,
        tintOverlay = glass.accentBlue.copy(alpha = 0.14f),
        onClick = onOpenTouchpad,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.size(42.dp).clip(SquircleShape(14.dp)).background(glass.accentBlue),
                contentAlignment = Alignment.Center,
            ) {
                Icon(ExtVectorIcons.Touchpad, "Continuity 触控板", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Continuity 电脑触控板伴侣已就绪", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = glass.textPrimary, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text("$targetLabel ($resolution) · 点击化身多指手势触控板与快捷键键盘", fontSize = 12.sp, color = glass.textSecondary, maxLines = 2)
            }
            StatusPillBadge("进入伴侣台", glass.accentBlue)
        }
    }
}

@Composable
private fun PermissionWarningCard(onClick: () -> Unit) {
    val glass = LiquidGlassTheme.colors
    LiquidGlassSurface(
        cornerRadius = 22.dp,
        tintOverlay = HyperAmber.copy(alpha = 0.14f),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ExtVectorIcons.ShieldCheck, null, tint = HyperAmber, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("部分核心权限尚未开启", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = glass.textPrimary)
                Text("点击一键核查无障碍、悬浮窗与通知权限", fontSize = 12.sp, color = glass.textSecondary)
            }
            StatusPillBadge("去处理", HyperAmber)
        }
    }
}

@Composable
private fun HeroConnectionCard(
    status: DaemonStatus,
    summary: ConnectionSummary,
    onOpenActivation: () -> Unit,
    onOpenDiag: () -> Unit,
    onSwitchToUhid: () -> Unit,
    onDisconnectRemote: () -> Unit,
    onOpenLocalDex: () -> Unit,
    onStopLocalDex: () -> Unit,
    onRebuild: () -> Unit,
) {
    val glass = LiquidGlassTheme.colors
    LiquidGlassSurface(
        cornerRadius = 26.dp,
        tier = GlassTier.Thick,
        elevation = 14.dp,
        onClick = { if (!status.connected) onOpenActivation() else onOpenDiag() },
        contentPadding = PaddingValues(18.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                summary.headline, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                color = glass.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            StatusPillBadge(summary.badgeLabel, summary.tone.toColor())
        }
        Spacer(Modifier.height(6.dp))
        Text(summary.detail, fontSize = 13.sp, color = glass.textSecondary)

        if (status.connected && status.injectStatus == InputBackend.INJECT_STATUS_UHID) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(HyperGreen.copy(alpha = 0.13f)).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("UHID 硬件外设注入已激活", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = HyperGreen)
                StatusPillBadge("UHID 直通", HyperGreen)
            }
        } else if (status.connected && status.injectStatus == InputBackend.INJECT_STATUS_BLOCKED) {
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(HyperRed.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("系统点击注入被拦截：可开启「USB 调试（安全设置）」或切换 UHID 虚拟键鼠兜底", fontSize = 12.sp, color = HyperRed)
                TextButton(
                    "一键切换 UHID 虚拟键鼠", onSwitchToUhid, colors = ButtonDefaults.textButtonColorsPrimary(),
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp), modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        if (!status.connected) {
            TextButton("立即激活后台服务", onOpenActivation, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.fillMaxWidth())
        } else if (status.isLocalDexActive) {
            TextButton("返回 DeX", onOpenLocalDex, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DangerActionButton("退出 DeX", onStopLocalDex, Modifier.weight(1f))
                TextButton("诊断", onOpenDiag, modifier = Modifier.weight(1f))
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (status.isRemoteSink) DangerActionButton("断开 Mac", onDisconnectRemote, Modifier.weight(1f))
                TextButton("连接诊断", onOpenDiag, modifier = Modifier.weight(1f))
                TextButton("刷新渲染", onRebuild, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LocalDexEntryCard(status: DaemonStatus, onClick: () -> Unit) {
    val glass = LiquidGlassTheme.colors
    val (badgeLabel, badgeColor) = when {
        !status.connected -> "需激活" to HyperAmber
        status.isLocalDexActive -> "运行中" to HyperGreen
        status.isRemoteSink -> "Mac 占用" to HyperAmber
        else -> "直接进入" to HyperBlue
    }
    val detail = when {
        !status.connected -> "激活核心服务后，无需外接显示器即可在手机/平板屏幕上直接开启 DeX 多窗口桌面"
        status.isLocalDexActive -> "免外接屏运行中 · ${status.virtW}×${status.virtH} @ ${status.virtDpi}dpi · 点击立即返回桌面"
        status.isRemoteSink -> "当前正在投屏到 ${status.remotePeerName.ifBlank { "Mac" }}，断开后可切换至本机 DeX 模式"
        else -> "无需外接显示器或连接电脑，直接在当前设备上全屏体验多窗口 DeX 桌面"
    }
    LiquidGlassSurface(
        cornerRadius = 24.dp,
        tier = GlassTier.Thick,
        tintOverlay = if (status.isLocalDexActive) HyperGreen.copy(alpha = 0.12f) else glass.accentBlue.copy(alpha = 0.10f),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(42.dp).clip(SquircleShape(14.dp))
                    .background(if (status.isLocalDexActive) HyperGreen else glass.accentBlue),
                contentAlignment = Alignment.Center,
            ) { Icon(ExtVectorIcons.MonitorDex, "本机 DeX 模式", tint = Color.White, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    if (status.isLocalDexActive) "返回本机 DeX 桌面" else "直接打开本机 DeX 模式",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = glass.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(detail, fontSize = 12.sp, color = glass.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            StatusPillBadge(badgeLabel, badgeColor)
        }
    }
}

@Composable
private fun RemoteMacSummaryCard(status: DaemonStatus, summary: ConnectionSummary, localLanIp: String, onClick: () -> Unit) {
    val glass = LiquidGlassTheme.colors
    val (remoteBadge, remoteBadgeColor) = when {
        !status.connected -> "需激活" to HyperAmber
        status.isRemoteSink -> summary.badgeLabel to summary.tone.toColor()
        summary.kind == ConnectionKind.WIFI_LISTENING -> "监听中" to HyperBlue
        else -> "未开启" to MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val remoteDetail = when {
        !status.connected -> "激活核心服务后支持 USB 2.0/3.0 与 Wi-Fi 无线连接 Mac 大屏"
        status.isRemoteSink ->
            "${status.remotePeerName.ifBlank { "Mac" }} · ${BridgeLabels.formatStreamFps(status.streamFpsX100)} · " +
                "${BridgeLabels.formatBitrateMbps(status.streamBitrateKbps)} · 点击管理"
        status.isWifiServerEnabled -> {
            val port = if (status.wifiServerPort > 0) status.wifiServerPort else RemoteStream.DEFAULT_WIFI_PORT
            "局域网 $localLanIp:$port · 配对 PIN ${status.activePairingPin.ifBlank { "------" }} · 点击查看指纹与管理设备"
        }
        else -> "开启无线大屏 TLS 服务、获取 6 位配对 PIN 码、调节编码码率与已信任 Mac"
    }

    LiquidGlassSurface(
        cornerRadius = 24.dp,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(42.dp).clip(SquircleShape(14.dp)).background(HyperBlue.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(ExtVectorIcons.LaptopMac, "Mac / 无线大屏", tint = HyperBlue, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Mac 有线 / 无线大屏中心", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = glass.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(remoteDetail, fontSize = 12.sp, color = glass.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            StatusPillBadge(remoteBadge, remoteBadgeColor)
        }
    }
}

@Composable
private fun QuickControlBentoGrid(
    status: DaemonStatus,
    screenDimmed: Boolean,
    onToggleInput: () -> Unit,
    onOpenTouchpad: () -> Unit,
    onToggleBlackout: () -> Unit,
    onOpenSideControl: () -> Unit,
    onOpenLocalDex: () -> Unit,
    onRelaunchLauncher: () -> Unit,
    onOpenAllApps: () -> Unit,
) {
    if (status.isLocalDexActive) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HyperBentoTile(
                "显示桌面", "最小化所有窗口", ExtVectorIcons.MonitorDex, false,
                { onRelaunchLauncher(); onOpenLocalDex() }, Modifier.weight(1f), HyperBlue,
            )
            HyperBentoTile(
                "全部应用", "打开应用抽屉", ExtVectorIcons.AppRules, false,
                onOpenAllApps,
                Modifier.weight(1f), HyperPurple,
            )
        }
        return
    }
    val routeExt = status.connected && status.inputToExt == 1
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HyperBentoTile(
            "键鼠路由", if (!status.connected) "连接后切换" else if (routeExt) "当前：外接扩展屏" else "当前：本机内屏",
            ExtVectorIcons.RouteSwap, routeExt, onToggleInput, Modifier.weight(1f),
        )
        HyperBentoTile("大触摸板", "多指手势与键盘", ExtVectorIcons.Touchpad, false, onOpenTouchpad, Modifier.weight(1f))
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HyperBentoTile(
            if (screenDimmed) "恢复主屏亮屏" else "一键黑屏",
            if (screenDimmed) "双击主屏亦可唤醒" else "主屏压至最低亮度",
            ExtVectorIcons.MoonDim, screenDimmed, onToggleBlackout, Modifier.weight(1f),
        )
        if (status.mirrorMode == MirrorMode.MIRROR) {
            HyperBentoTile("副屏直控", "触控操作镜像边栏", ExtVectorIcons.SidePanel, false, onOpenSideControl, Modifier.weight(1f), HyperPurple)
        } else if (status.connected && status.vdId > 0) {
            HyperBentoTile("打开扩展桌面", "唤起外接屏启动器", ExtVectorIcons.MonitorDex, false, onRelaunchLauncher, Modifier.weight(1f))
        } else {
            HyperBentoTile("进入本机 DeX", "免外接屏直开桌面", ExtVectorIcons.MonitorDex, false, onOpenLocalDex, Modifier.weight(1f))
        }
    }
}

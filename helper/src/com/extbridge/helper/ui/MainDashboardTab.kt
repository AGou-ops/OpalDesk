package com.extbridge.helper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.extbridge.helper.protocol.BridgeLabels
import com.extbridge.helper.protocol.BridgeProtocol.InputBackend
import com.extbridge.helper.protocol.BridgeProtocol.MirrorMode
import com.extbridge.helper.protocol.BridgeProtocol.RemoteStream
import com.extbridge.helper.protocol.ConnectionKind
import com.extbridge.helper.protocol.ConnectionSummary
import com.extbridge.helper.protocol.DaemonStatus
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
    onOpenTouchpad: () -> Unit,
    onOpenSideControl: () -> Unit,
    onOpenWidgetSlot: (Int) -> Unit,
) {
    if (isWide) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds(),
                contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DashboardPrimaryColumn(ctrl, allPermsOk, showJumpCard = false, onOpenPermDialog, onOpenTouchpad, onOpenSideControl)
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1.08f).fillMaxHeight().clipToBounds(),
                contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (ctrl.daemonStatus.mirrorMode == MirrorMode.MIRROR) MirrorConfigSection(ctrl, onOpenSideControl, onOpenWidgetSlot)
                        else DexConfigSection(ctrl)
                    }
                }
            }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DashboardPrimaryColumn(ctrl, allPermsOk, showJumpCard = true, onOpenPermDialog, onOpenTouchpad, onOpenSideControl)
                }
            }
        }
    }
}

@Composable
private fun DashboardPrimaryColumn(
    ctrl: MainController,
    allPermsOk: Boolean,
    showJumpCard: Boolean,
    onOpenPermDialog: () -> Unit,
    onOpenTouchpad: () -> Unit,
    onOpenSideControl: () -> Unit,
) {
    val st = ctrl.daemonStatus
    val connSummary = BridgeLabels.classifyConnectionSummary(st, ctrl.localLanIp)

    if (!allPermsOk) PermissionWarningCard(onOpenPermDialog)

    HeroConnectionCard(
        status = st, summary = connSummary,
        onOpenActivation = { ctrl.openActivationSheet() },
        onOpenDiag = { ctrl.showDiagSheet = true },
        onSwitchToUhid = { ctrl.applyInputBackendMode(InputBackend.UHID) },
        onDisconnectRemote = { ctrl.disconnectRemoteMac() },
        onRebuild = { ctrl.rebuildRenderPipeline("已重建扩展渲染管线") },
    )

    SectionHeader("Mac / 无线大屏")
    RemoteMacSummaryCard(st, connSummary, ctrl.localLanIp) { ctrl.openRemoteMacSheet() }

    SectionHeader("输出模式")
    TabRowWithContour(
        tabs = listOf("DeX 扩展桌面", "原生镜像投屏"),
        selectedTabIndex = if (st.mirrorMode == MirrorMode.MIRROR) 1 else 0,
        onTabSelected = { ctrl.switchOutputMode(if (it == 1) MirrorMode.MIRROR else MirrorMode.DEX) },
        modifier = Modifier.fillMaxWidth(),
    )

    SectionHeader("快捷控制")
    QuickControlBentoGrid(
        st, ctrl.screenDimmed, { ctrl.toggleInputRouting() }, onOpenTouchpad,
        { ctrl.toggleBlackout() }, onOpenSideControl, { ctrl.relaunchExtLauncher() },
    )

    if (showJumpCard) {
        val isMirror = st.mirrorMode == MirrorMode.MIRROR
        Card {
            ArrowPreference(
                title = if (isMirror) "配置镜像布局与融合边栏" else "调节分辨率、DPI 与旋转方向",
                summary = if (isMirror) {
                    "${BridgeLabels.mirrorSideModeLabel(ctrl.mirrorSideMode)} · 已启用 ${ctrl.mirrorPresets.size} 张信息卡"
                } else {
                    "画布 ${ctrl.selW}×${ctrl.selH} · ${ctrl.selDpi}dpi · ${BridgeLabels.rotationLabel(ctrl.selRot)}"
                },
                onClick = { ctrl.displaySubTab = if (isMirror) 1 else 0; ctrl.navTab = 1 },
            )
        }
    }
}

@Composable
private fun PermissionWarningCard(onClick: () -> Unit) {
    Card(
        colors = CardDefaults.defaultColors(color = HyperAmber.copy(alpha = 0.14f)),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 13.dp),
        pressFeedbackType = PressFeedbackType.Sink, showIndication = true, onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ExtVectorIcons.ShieldCheck, null, tint = HyperAmber, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("部分核心权限尚未开启", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurfaceContainer)
                Text("点击一键核查无障碍、悬浮窗与通知权限", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
    onRebuild: () -> Unit,
) {
    Card(
        cornerRadius = 20.dp, insideMargin = PaddingValues(18.dp),
        pressFeedbackType = PressFeedbackType.Sink, showIndication = true,
        onClick = { if (!status.connected) onOpenActivation() else onOpenDiag() },
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                summary.headline, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            StatusPillBadge(summary.badgeLabel, summary.tone.toColor())
        }
        Spacer(Modifier.height(6.dp))
        Text(summary.detail, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)

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
private fun RemoteMacSummaryCard(status: DaemonStatus, summary: ConnectionSummary, localLanIp: String, onClick: () -> Unit) {
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

    Card(
        cornerRadius = 18.dp, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        pressFeedbackType = PressFeedbackType.Sink, showIndication = true, onClick = onClick,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(HyperBlue.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(ExtVectorIcons.LaptopMac, "Mac / 无线大屏", tint = HyperBlue, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Mac 有线 / 无线大屏中心", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(remoteDetail, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    onRelaunchLauncher: () -> Unit,
) {
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
        } else {
            HyperBentoTile("打开扩展桌面", "唤起外接屏启动器", ExtVectorIcons.MonitorDex, false, onRelaunchLauncher, Modifier.weight(1f))
        }
    }
}

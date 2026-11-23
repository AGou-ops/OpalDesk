package io.github.lengsukq.opaldesk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.ExternalDesktopActivity
import io.github.lengsukq.opaldesk.cast.OpalPeerCastMutex
import io.github.lengsukq.opaldesk.cast.OpalPeerDiscovery
import io.github.lengsukq.opaldesk.cast.PeerFloatingAppService
import io.github.lengsukq.opaldesk.compositor.applyWindowTilingLayout
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout
import java.util.Locale
import kotlin.math.abs

@Composable
fun MacGlassSectionCard(
    modifier: Modifier = Modifier,
    accentBorder: Color? = null,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val rimBrush = if (accentBorder != null) {
        Brush.verticalGradient(listOf(accentBorder.copy(alpha = 0.72f), accentBorder.copy(alpha = 0.22f)))
    } else {
        OpalPalette.LiquidGlassRim
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidDockFill,
                rim = rimBrush,
                elevation = if (OpalPalette.isDark) 18.dp else 14.dp,
            )
            .padding(contentPadding),
    ) {
        content()
    }
}

/**
 * Tab 1: Dashboard — Trackpad Quick Entry, Local/USB/Mac DeX Launchers, Quick Control Bento Grid,
 * and OpalCast v1.1 MacOS Wireless/USB Streaming Controls.
 */
@Composable
fun ConsoleDashboardTab(
    status: DaemonStatus,
    scene: CompositorScene,
    remote: RemoteStreamStatus,
    extDisplay: BridgeService.ExternalDisplayInfo?,
    onEnterLocalDesktop: () -> Unit,
    onOpenExternalOrTrackpad: (Int) -> Unit,
    onConnectPeerCast: (String, Int, String) -> Unit,
    onOpenFloatingAppPicker: (String, Int, String) -> Unit,
    onOpenActivation: () -> Unit,
) {
    val ctx = LocalContext.current
    var autoExtDisplay by remember { mutableStateOf(AppPrefs.isAutoExternalDisplayEnabled(ctx)) }
    val peerRole by OpalPeerCastMutex.roleFlow.collectAsState()
    val discoveredPeers by OpalPeerDiscovery.discoveredPeersFlow.collectAsState()
    val trustedPeers by BridgeClient.trustedPeersFlow.collectAsState()
    val activeFloating by PeerFloatingAppService.activeFlow.collectAsState()
    var wifiBusy by remember { mutableStateOf(false) }
    var peerHostText by remember { mutableStateOf("") }
    var peerLabelText by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. Unified Active External / Casting Output Banner (USB-C / HDMI / Wi-Fi / USB Cast)
        if (status.connected && (extDisplay != null || remote.connected || status.hostScreenOff)) {
            val headline = when {
                extDisplay != null && ExternalDesktopActivity.isPresentationActive ->
                    "USB-C / HDMI 独立满屏桌面运行中"
                extDisplay != null ->
                    "检测到外接显示器 · 点击开启满屏桌面与触控板"
                remote.connected ->
                    "正在通过 ${remote.channelLabel} 向外投屏"
                else ->
                    "本机物理屏幕处于息屏省电状态"
            }
            val targetTitle = when {
                extDisplay != null ->
                    "${extDisplay.name} · ${extDisplay.width}×${extDisplay.height} @ ${extDisplay.refreshRateHz}Hz"
                remote.connected ->
                    "接收端：${remote.peerName.ifBlank { "对端设备" }} · ${remote.width}×${remote.height} @ ${remote.fpsX100 / 100}Hz（画质与外接键鼠统一在「显示与画质」调节）"
                else ->
                    "点击右侧按钮可随时恢复点亮本机屏幕"
            }
            val bannerShape = RoundedCornerShape(24.dp)
            val isDark = OpalPalette.isDark
            val bannerBg = if (isDark) {
                Brush.horizontalGradient(listOf(Color(0xA8163558), Color(0x9E172942)))
            } else {
                Brush.horizontalGradient(listOf(Color(0xA6DDF0FF), Color(0x96EDF5FF)))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlassSurface(
                        shape = bannerShape,
                        fill = bannerBg,
                        rim = Brush.verticalGradient(
                            listOf(
                                OpalPalette.AccentBlue.copy(alpha = if (isDark) 0.70f else 0.48f),
                                OpalPalette.AccentCyan.copy(alpha = 0.24f),
                            ),
                        ),
                        elevation = if (isDark) 20.dp else 14.dp,
                    )
                    .clickable {
                        if (extDisplay != null) {
                            onOpenExternalOrTrackpad(extDisplay.displayId)
                        } else {
                            onEnterLocalDesktop()
                        }
                    }
                    .padding(horizontal = 18.dp, vertical = 15.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column {
                    OpalText(
                        text = headline,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = OpalPalette.TextPrimary,
                    )
                    Spacer(Modifier.height(3.dp))
                    OpalText(
                        text = targetTitle,
                        fontSize = 12.sp,
                        color = OpalPalette.AccentCyan,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (extDisplay != null) {
                        OpalButton(
                            label = "打开触控板",
                            compact = true,
                            onClick = { onOpenExternalOrTrackpad(extDisplay.displayId) },
                            modifier = Modifier.weight(1f),
                        )
                    } else if (remote.connected) {
                        OpalButton(
                            label = "本机查看桌面",
                            compact = true,
                            filled = false,
                            onClick = onEnterLocalDesktop,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OpalButton(
                        label = if (status.hostScreenOff) "点亮本机屏幕" else "熄灭本机屏幕（省电）",
                        compact = true,
                        filled = !status.hostScreenOff,
                        accent = if (status.hostScreenOff) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
                        onClick = { toggleHostScreenPowerWithToast(ctx, screenOn = status.hostScreenOff) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 2. Hero Card: Local DeX & Quick Auxiliary Bento Controls
        MacGlassSectionCard(
            accentBorder = if (status.connected) OpalPalette.AccentBlue else null,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OpalText(text = "OpalDesk 多窗合成工作站", fontSize = 17.5.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    OpalText(
                        text = if (status.connected) {
                            "已打开 ${scene.windows.size} 个窗口 · ${status.injectBackendLabel}"
                        } else {
                            "免 Root 独立全屏 VirtualDisplay 架构，macOS 液态玻璃质感 + DeX 操作逻辑"
                        },
                        fontSize = 12.sp,
                        color = if (status.connected) OpalPalette.AccentCyan else OpalPalette.TextSecondary,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            if (status.connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OpalButton(
                        label = "本机直启 DeX 桌面",
                        onClick = onEnterLocalDesktop,
                        modifier = Modifier.weight(1.3f),
                    )
                    if (extDisplay != null) {
                        OpalButton(
                            label = "外接屏触控板",
                            accent = OpalPalette.AccentEmerald,
                            onClick = { onOpenExternalOrTrackpad(extDisplay.displayId) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        OpalButton(
                            label = "重新激活",
                            filled = false,
                            onClick = onOpenActivation,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                // Quick Multi-Window Tiling & Auxiliary Desktop Control Bento Grid (2 rows)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    QuickBentoChip(
                        label = "左右二分",
                        modifier = Modifier.weight(1f),
                        onClick = { tileFromConsole(WindowTilingLayout.SPLIT_2_COLS, scene, status) },
                    )
                    QuickBentoChip(
                        label = "三列分屏",
                        modifier = Modifier.weight(1f),
                        onClick = { tileFromConsole(WindowTilingLayout.SPLIT_3_COLS, scene, status) },
                    )
                    QuickBentoChip(
                        label = "四列分屏",
                        modifier = Modifier.weight(1f),
                        onClick = { tileFromConsole(WindowTilingLayout.SPLIT_4_COLS, scene, status) },
                    )
                    QuickBentoChip(
                        label = "四宫格",
                        modifier = Modifier.weight(1f),
                        onClick = { tileFromConsole(WindowTilingLayout.SPLIT_4_GRID, scene, status) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    QuickBentoChip(
                        label = "主副 2:1",
                        modifier = Modifier.weight(1f),
                        onClick = { tileFromConsole(WindowTilingLayout.SPLIT_2_MAIN_SIDE, scene, status) },
                    )
                    QuickBentoChip(
                        label = "显示桌面",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scene.visibleWindows.forEach { win ->
                                BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.MINIMIZE)
                            }
                        },
                    )
                    QuickBentoChip(
                        label = "关闭全部",
                        tint = OpalPalette.AccentAmber,
                        modifier = Modifier.weight(1f),
                        onClick = { BridgeClient.closeAllWindowsAsync(removeTasks = true) },
                    )
                    QuickBentoChip(
                        label = "重新激活",
                        modifier = Modifier.weight(1f),
                        onClick = onOpenActivation,
                    )
                }
            } else {
                OpalButton(
                    label = "免 Root 一键激活多窗引擎",
                    onClick = onOpenActivation,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 3. Cross-Device Casting Card (MacOS + Android <-> Android One-Way Peer Casting)
        MacGlassSectionCard(
            accentBorder = when {
                remote.connected || peerRole.isReceiving -> OpalPalette.AccentEmerald
                else -> null
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OpalText(text = "跨设备互联投屏 (Mac / Android 互投)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    OpalText(
                        text = when {
                            remote.connected ->
                                "正在向外投屏至 ${remote.peerName.ifBlank { "对端设备" }} · ${remote.width}×${remote.height} @ ${remote.fpsX100 / 100}Hz（单向防循环已锁定接收端）"
                            peerRole.isReceiving ->
                                "正在接收 ${peerRole.peerName} 的投屏（单向防循环已锁定发射端）"
                            else ->
                                "支持 USB / Wi-Fi 投屏到 Mac，或两台已安装 OpalDesk 的安卓设备单向互投（自动防止双向互投死循环）"
                        },
                        fontSize = 12.sp,
                        color = if (remote.connected || peerRole.isReceiving) OpalPalette.AccentEmerald else OpalPalette.TextSecondary,
                    )
                }
                if (remote.connected) {
                    OpalStatusBadge(label = remote.channelLabel, color = OpalPalette.AccentEmerald)
                } else if (peerRole.isReceiving) {
                    OpalStatusBadge(label = "接收中", color = OpalPalette.AccentEmerald)
                }
            }

            if (remote.connected) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OpalButton(
                        label = if (status.hostScreenOff) "点亮本机屏幕" else "熄灭本机屏幕（省电）",
                        accent = if (status.hostScreenOff) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
                        filled = !status.hostScreenOff,
                        compact = true,
                        onClick = { toggleHostScreenPowerWithToast(ctx, screenOn = status.hostScreenOff) },
                        modifier = Modifier.weight(1f),
                    )
                    OpalButton(
                        label = "断开向外投屏",
                        accent = OpalPalette.AccentDanger,
                        filled = false,
                        compact = true,
                        onClick = { BridgeClient.disconnectRemoteStreamAsync() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            OpalText(text = "发射端：将本机投屏至 Mac 或另一台安卓", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OpalPalette.TextSecondary)
            OpalSwitchRow(
                title = "开启局域网无线投屏服务",
                subtitle = when {
                    peerRole.isReceiving -> "当前正在接收投屏，已锁定发射端以防止双向循环互投"
                    remote.wifiEnabled -> "已开启 TLS 加密服务并广播 _opaldesk._tcp.，另一台安卓或 Mac 可自动发现"
                    else -> "默认关闭；有线 USB 连接 Mac 无需开启，安卓互投时请在发射端开启"
                },
                checked = remote.wifiEnabled && !peerRole.isReceiving,
                onCheckedChange = { enable ->
                    if (!status.connected || wifiBusy) return@OpalSwitchRow
                    if (enable) {
                        val check = OpalPeerCastMutex.validateEnableSending(peerRole.isReceiving, peerRole.peerName)
                        if (!check.allowed) {
                            Toast.makeText(ctx, check.reason, Toast.LENGTH_LONG).show()
                            return@OpalSwitchRow
                        }
                    }
                    wifiBusy = true
                    BridgeClient.setWifiStreamEnabledAsync(enable, 19878) {
                        wifiBusy = false
                        val latest = BridgeClient.remoteStatusFlow.value
                        OpalPeerDiscovery.syncAdvertise(ctx, latest.wifiEnabled, latest.wifiPort, latest.shortCertFingerprint)
                    }
                },
            )

            if (status.connected && remote.wifiEnabled && !peerRole.isReceiving) {
                val port = if (remote.wifiPort > 0) remote.wifiPort else 19878
                val endpoint = if (remote.lanIp.isNotEmpty()) "${remote.lanIp}:$port" else ":$port"

                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(OpalPalette.ChipFill)
                        .border(0.8.dp, OpalPalette.BorderSubtle, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CopyableInfoLine(ctx, "局域网监听地址", endpoint)
                    if (remote.shortCertFingerprint.isNotEmpty()) {
                        CopyableInfoLine(ctx, "TLS 证书短指纹", remote.shortCertFingerprint)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(OpalPalette.AccentEmerald.copy(alpha = 0.12f))
                            .border(0.8.dp, OpalPalette.AccentEmerald.copy(alpha = 0.34f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            OpalText(
                                text = "免 PIN 实时确认授权已就绪",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = OpalPalette.AccentEmerald,
                            )
                            OpalText(
                                text = "对方发起连接时本机将弹窗确认，可选择「仅本次允许」或「永久允许」",
                                fontSize = 11.sp,
                                color = OpalPalette.TextSecondary,
                            )
                        }
                        if (remote.connected) {
                            Spacer(Modifier.width(8.dp))
                            OpalStatusBadge(
                                label = if (remote.activeSessionPermanent) "永久信任" else "本次临时",
                                color = if (remote.activeSessionPermanent) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
                            )
                        }
                    }
                }
            }

            // Permanently Trusted Devices with Instant Revocation
            if (status.connected && trustedPeers.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(OpalPalette.ChipFill)
                        .border(0.8.dp, OpalPalette.BorderSubtle, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        OpalText(
                            text = "已永久信任设备 (${trustedPeers.size})",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OpalButton(
                            label = "全部撤销",
                            accent = OpalPalette.AccentDanger,
                            filled = false,
                            compact = true,
                            onClick = {
                                BridgeClient.revokeTrustedPeerAsync("*") {
                                    Toast.makeText(ctx, "已撤销所有永久授权设备", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                    trustedPeers.forEach { peer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(OpalPalette.GlassPopover.copy(alpha = 0.5f))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                OpalText(text = peer.clientName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                OpalText(
                                    text = peer.clientId,
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = OpalPalette.TextMuted,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            OpalButton(
                                label = "撤销",
                                accent = OpalPalette.AccentDanger,
                                filled = false,
                                compact = true,
                                onClick = {
                                    BridgeClient.revokeTrustedPeerAsync(peer.clientId) {
                                        Toast.makeText(ctx, "已撤销「${peer.clientName}」的永久授权", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // Receiver section: Connect to another Android device running OpalDesk
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.8.dp)
                    .background(OpalPalette.DividerColor),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(
                    text = "接收端：连接另一台安卓设备",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextSecondary,
                )
                OpalText(
                    text = if (discoveredPeers.isNotEmpty()) "发现 ${discoveredPeers.size} 台设备" else "免 PIN 一键请求连接",
                    fontSize = 11.sp,
                    color = if (discoveredPeers.isNotEmpty()) OpalPalette.AccentCyan else OpalPalette.TextMuted,
                )
            }

            if (activeFloating != null) {
                val fl = activeFloating!!
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(OpalPalette.AccentBlue.copy(alpha = 0.14f))
                        .border(0.8.dp, OpalPalette.AccentBlue.copy(alpha = 0.36f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        OpalText(
                            text = "悬浮窗运行中：${fl.appLabel}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OpalPalette.AccentBlue,
                        )
                        OpalText(
                            text = "来自 ${fl.peerName} · 可切换至其他本机应用同时操作",
                            fontSize = 11.sp,
                            color = OpalPalette.TextSecondary,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OpalButton(
                        label = "关闭悬浮窗",
                        accent = OpalPalette.AccentDanger,
                        filled = false,
                        compact = true,
                        onClick = { PeerFloatingAppService.stop(ctx) },
                    )
                }
            }

            if (discoveredPeers.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    discoveredPeers.forEach { peer ->
                        val addr = "${peer.host}:${peer.port}"
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(OpalPalette.ChipFill)
                                .border(0.75.dp, OpalPalette.BorderSubtle, RoundedCornerShape(14.dp))
                                .clickable(enabled = !remote.connected) {
                                    peerHostText = addr
                                    peerLabelText = peer.displayName
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column {
                                OpalText(text = peer.displayName, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                OpalText(
                                    text = if (peer.shortFingerprint.isNotEmpty()) "$addr · ${peer.shortFingerprint}" else addr,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = OpalPalette.TextMuted,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OpalButton(
                                    label = "连接桌面",
                                    accent = OpalPalette.AccentEmerald,
                                    compact = true,
                                    enabled = !remote.connected,
                                    onClick = { onConnectPeerCast(peer.host, peer.port, peer.displayName) },
                                    modifier = Modifier.weight(1f),
                                )
                                OpalButton(
                                    label = "应用悬浮窗",
                                    accent = OpalPalette.AccentBlue,
                                    filled = false,
                                    compact = true,
                                    enabled = !remote.connected,
                                    onClick = { onOpenFloatingAppPicker(peer.host, peer.port, peer.displayName) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OpalTextInput(
                value = peerHostText,
                onValueChange = { peerHostText = it },
                placeholder = "或手动输入对方 IP:端口 (如 192.168.1.8:19878)",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OpalButton(
                    label = if (remote.connected) "单向投屏锁定中" else "连接对方桌面",
                    enabled = !remote.connected && peerHostText.isNotBlank(),
                    compact = true,
                    accent = OpalPalette.AccentEmerald,
                    onClick = {
                        val raw = peerHostText.trim()
                        val colon = raw.lastIndexOf(':')
                        val h = if (colon > 0) raw.substring(0, colon).trim() else raw
                        val p = if (colon > 0) raw.substring(colon + 1).trim().toIntOrNull() ?: 19878 else 19878
                        onConnectPeerCast(h, p, peerLabelText.ifBlank { h })
                    },
                    modifier = Modifier.weight(1f),
                )
                OpalButton(
                    label = "独立应用悬浮窗",
                    enabled = !remote.connected && peerHostText.isNotBlank(),
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentBlue,
                    onClick = {
                        val raw = peerHostText.trim()
                        val colon = raw.lastIndexOf(':')
                        val h = if (colon > 0) raw.substring(0, colon).trim() else raw
                        val p = if (colon > 0) raw.substring(colon + 1).trim().toIntOrNull() ?: 19878 else 19878
                        onOpenFloatingAppPicker(h, p, peerLabelText.ifBlank { h })
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 4. Physical USB-C / HDMI External Monitor Card
        MacGlassSectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OpalText(text = "USB-C / HDMI 外接显示器", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    OpalText(
                        text = if (extDisplay != null) {
                            "已连接：${extDisplay.name} (${extDisplay.width}×${extDisplay.height} @ ${extDisplay.refreshRateHz}Hz) · 独立满屏支持"
                        } else {
                            "暂未检测到外接显示器（接入便携屏/显示器后自动启用满屏独立工作站）"
                        },
                        fontSize = 12.sp,
                        color = if (extDisplay != null) OpalPalette.AccentEmerald else OpalPalette.TextSecondary,
                    )
                }
                if (extDisplay != null) {
                    Spacer(Modifier.width(10.dp))
                    OpalButton(
                        label = if (ExternalDesktopActivity.isPresentationActive) "进入触控板" else "满屏投射",
                        enabled = status.connected,
                        compact = true,
                        onClick = { onOpenExternalOrTrackpad(extDisplay.displayId) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OpalSwitchRow(
                title = "接入外接显示器时自动满屏投射",
                subtitle = "突破镜像黑边，外接屏原生分辨率满屏运行；显示画质、息屏省电与外接键鼠设置与投屏模式完全统一",
                checked = autoExtDisplay,
                onCheckedChange = {
                    autoExtDisplay = it
                    AppPrefs.setAutoExternalDisplayEnabled(ctx, it)
                },
            )
        }
    }
}

/**
 * Tab 2: Unified Display & Quality Settings — shares ONE identical control flow across USB-C / HDMI
 * external monitors AND Wi-Fi / USB remote casting (Auto-Adapt, Resolution & Refresh Rate, Target FPS,
 * Window DPI Scale, Output UI Scale, Host Physical Screen-Off, External Keyboard/Mouse Routing, Pointer
 * Speed, and Wallpapers).
 */
@Composable
fun ConsoleDisplaySettingsTab(
    status: DaemonStatus,
    remote: RemoteStreamStatus,
    extDisplay: BridgeService.ExternalDisplayInfo?,
    onPickCustomWallpaper: () -> Unit,
) {
    val ctx = LocalContext.current
    val themeMode by AppPrefs.themeModeFlow.collectAsState()
    val extUiScale by AppPrefs.externalUiScaleFlow.collectAsState()
    val trackpadSens by AppPrefs.trackpadSensitivityFlow.collectAsState()
    val wallpaperPreset by AppPrefs.wallpaperPresetFlow.collectAsState()
    val customWallpaperPath by AppPrefs.customWallpaperPathFlow.collectAsState()
    val mouseTarget by AppPrefs.mouseTargetFlow.collectAsState()
    val isDark = OpalPalette.isDark

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 0. Appearance Style (macOS Liquid Frosted Glass: Light / Follow System / Dark)
        MacGlassSectionCard {
            OpalText(text = "液态毛玻璃外观", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            OpalText(
                text = "整体采用 macOS 液态毛玻璃质感；默认通透浅色风格，支持跟随系统自动切换或常驻深色玻璃",
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            OpalSegmentedRow(
                options = listOf("浅色", "跟随系统", "深色"),
                selectedIndex = themeMode.coerceIn(AppPrefs.THEME_LIGHT, AppPrefs.THEME_DARK),
                onSelect = { AppPrefs.setThemeMode(ctx, it) },
            )
        }

        // 1. Smart Auto-Adapt Card (Unified across USB-C / HDMI / Wi-Fi / USB Casting / Local)
        MacGlassSectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OpalText(text = "智能自动适配（外接屏与投屏统一推荐）", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    OpalText(
                        text = "统一重置 USB-C / HDMI 外接屏与无线 / USB 投屏至最佳分辨率、最高帧率与清晰桌面 DPI",
                        fontSize = 12.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                OpalButton(
                    label = "一键自动",
                    compact = true,
                    onClick = {
                        AppPrefs.setExternalUiScale(ctx, 1.25f)
                        AppPrefs.setTrackpadSensitivity(ctx, AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
                        BridgeClient.setPointerSpeedAsync(AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
                        BridgeClient.updateDisplayPrefsAsync(targetFps = 0, windowDpiScalePct = 100)
                        if (extDisplay != null) {
                            val best = extDisplay.supportedModes.firstOrNull()
                            if (best != null) {
                                BridgeClient.setExternalDisplayModeAsync(
                                    extDisplay.displayId, best.width, best.height, best.refreshRateHz.toFloat(),
                                )
                            } else {
                                BridgeClient.setExternalDisplayModeAsync(extDisplay.displayId, 0, 0, 0f)
                            }
                        } else if (remote.connected) {
                            BridgeClient.setExternalDisplayModeAsync(-1, remote.width, remote.height, 60f)
                        }
                        Toast.makeText(ctx, "已恢复全自动最佳分辨率、帧率与 DPI 配置", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }

        // 2. Unified Output Resolution & Refresh Rate Switcher (USB-C / HDMI EDID & Wi-Fi / USB Cast)
        MacGlassSectionCard {
            OpalText(text = "输出分辨率与刷新率（USB-C / 投屏统一控制）", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            when {
                extDisplay != null -> {
                    OpalText(
                        text = "当前物理外接输出：${extDisplay.name} · ${extDisplay.width}×${extDisplay.height} @ ${extDisplay.refreshRateHz}Hz",
                        fontSize = 12.sp,
                        color = OpalPalette.AccentCyan,
                    )
                    Spacer(Modifier.height(10.dp))
                    val modes = extDisplay.supportedModes
                    if (modes.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            modes.chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    pair.forEach { m ->
                                        val isCurrent = m.width == extDisplay.width &&
                                            m.height == extDisplay.height &&
                                            m.refreshRateHz == extDisplay.refreshRateHz
                                        OpalButton(
                                            label = "${m.width}×${m.height} · ${m.refreshRateHz}Hz",
                                            compact = true,
                                            filled = isCurrent,
                                            accent = if (isCurrent) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                                            onClick = {
                                                BridgeClient.setExternalDisplayModeAsync(
                                                    extDisplay.displayId, m.width, m.height, m.refreshRateHz.toFloat(),
                                                ) { ok ->
                                                    val msg = if (ok) "已切换输出至 ${m.width}×${m.height}@${m.refreshRateHz}Hz"
                                                    else "当前显示器未接受该模式"
                                                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                remote.connected -> {
                    val curFps = (remote.fpsX100 / 100).coerceAtLeast(30)
                    OpalText(
                        text = "当前${remote.channelLabel}输出：${remote.peerName.ifBlank { "对端设备" }} · ${remote.width}×${remote.height} @ ${curFps}Hz",
                        fontSize = 12.sp,
                        color = OpalPalette.AccentCyan,
                    )
                    Spacer(Modifier.height(10.dp))
                    val castPresets = listOf(
                        Triple(1920, 1080, 60),
                        Triple(1920, 1080, 120),
                        Triple(2560, 1440, 60),
                        Triple(2560, 1440, 120),
                        Triple(2560, 1600, 60),
                        Triple(2560, 1600, 120),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        castPresets.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                pair.forEach { (w, h, fps) ->
                                    val isCurrent = remote.width == w && remote.height == h && abs(curFps - fps) <= 3
                                    OpalButton(
                                        label = "${w}×${h} · ${fps}Hz",
                                        compact = true,
                                        filled = isCurrent,
                                        accent = if (isCurrent) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                                        onClick = {
                                            BridgeClient.setExternalDisplayModeAsync(-1, w, h, fps.toFloat()) { ok ->
                                                val msg = if (ok) "已动态切换投屏至 ${w}×${h}@${fps}Hz"
                                                else "切换投屏分辨率失败"
                                                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                else -> {
                    OpalText(
                        text = "当前未连接 USB-C / HDMI 外接显示器或无线/USB 投屏；任一输出通道连接后，均可在此使用同一套界面直接切换分辨率与刷新率。",
                        fontSize = 12.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
            }
        }

        // 3. Target Frame Rate, Output UI Scale & App Window DPI Density (Unified for USB-C & Casting)
        MacGlassSectionCard {
            OpalText(text = "帧率、缩放与窗口密度（外接与投屏统一生效）", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))

            OpalText(text = "桌面与虚拟屏目标帧率（同步作用于 USB-C 外接屏与投屏流）", fontSize = 12.sp, color = OpalPalette.TextSecondary)
            Spacer(Modifier.height(6.dp))
            val fpsOptions = listOf(0, 60, 90, 100, 120)
            val fpsIdx = fpsOptions.indexOf(status.targetFps).let { if (it >= 0) it else 0 }
            OpalSegmentedRow(
                options = listOf("自动", "60Hz", "90Hz", "100Hz", "120Hz"),
                selectedIndex = fpsIdx,
                onSelect = { idx ->
                    BridgeClient.updateDisplayPrefsAsync(
                        targetFps = fpsOptions[idx],
                        windowDpiScalePct = status.windowDpiScalePct,
                    )
                },
            )

            Spacer(Modifier.height(14.dp))
            OpalText(text = "应用窗口内容密度 DPI（实时热刷新所有已打开窗口）", fontSize = 12.sp, color = OpalPalette.TextSecondary)
            Spacer(Modifier.height(6.dp))
            val dpiOptions = listOf(80, 100, 125)
            val dpiIdx = dpiOptions.indexOfFirst { abs(it - status.windowDpiScalePct) <= 8 }.let { if (it >= 0) it else 1 }
            OpalSegmentedRow(
                options = listOf("紧凑视野 (80%)", "自动标准 (100%)", "大号易读 (125%)"),
                selectedIndex = dpiIdx,
                onSelect = { idx ->
                    BridgeClient.updateDisplayPrefsAsync(
                        targetFps = status.targetFps,
                        windowDpiScalePct = dpiOptions[idx],
                    )
                },
            )

            Spacer(Modifier.height(14.dp))
            OpalText(text = "外接屏 / 投屏桌面界面缩放比例 (UI Scale)", fontSize = 12.sp, color = OpalPalette.TextSecondary)
            Spacer(Modifier.height(6.dp))
            val scaleOpts = listOf(1.0f, 1.25f, 1.5f)
            val scaleIdx = scaleOpts.indexOfFirst { abs(it - extUiScale) < 0.08f }.let { if (it >= 0) it else 1 }
            OpalSegmentedRow(
                options = listOf("100% 精细", "125% 标准", "150% 舒适"),
                selectedIndex = scaleIdx,
                onSelect = { idx ->
                    AppPrefs.setExternalUiScale(ctx, scaleOpts[idx])
                },
            )
        }

        // 4. Host Screen Off Power Control, Unified External Keyboard & Mouse Routing, Pointer Speed & Wallpapers
        MacGlassSectionCard {
            OpalText(text = "息屏省电、外接键鼠与桌面壁纸", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))

            if (status.connected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(15.dp))
                        .background(
                            if (status.hostScreenOff) OpalPalette.AccentEmerald.copy(alpha = 0.14f) else OpalPalette.ChipFill,
                        )
                        .border(
                            0.8.dp,
                            if (status.hostScreenOff) OpalPalette.AccentEmerald.copy(alpha = 0.42f) else OpalPalette.BorderSubtle,
                            RoundedCornerShape(15.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        OpalText(
                            text = if (status.hostScreenOff) "本机屏幕已物理熄屏（省电运行中）" else "本机物理息屏（外接 / 投屏省电）",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (status.hostScreenOff) OpalPalette.AccentEmerald else OpalPalette.TextPrimary,
                        )
                        Spacer(Modifier.height(2.dp))
                        OpalText(
                            text = "关闭手机本身屏幕避免亮屏耗电，支持双击手机屏幕随时亮屏；外接屏与投屏保持高刷运行，断开自动亮屏",
                            fontSize = 11.sp,
                            color = OpalPalette.TextSecondary,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    OpalButton(
                        label = if (status.hostScreenOff) "点亮屏幕" else "立即息屏",
                        compact = true,
                        filled = !status.hostScreenOff,
                        accent = if (status.hostScreenOff) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
                        onClick = { toggleHostScreenPowerWithToast(ctx, screenOn = status.hostScreenOff) },
                    )
                }
                Spacer(Modifier.height(14.dp))
            }

            OpalText(
                text = "外接键盘与物理鼠标输出目标（USB-C 与投屏统一生效，默认优先投屏端）",
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
            )
            Spacer(Modifier.height(3.dp))
            OpalText(
                text = when {
                    status.boundMouseCount > 0 ->
                        "已将 ${status.boundMouseCount} 个外接键鼠设备优先绑定至投屏/外接端（后台切出也生效）"
                    status.connectedMouseCount > 0 && mouseTarget == AppPrefs.MOUSE_TARGET_EXTERNAL ->
                        "已检测到 ${status.connectedMouseCount} 个外接键鼠设备；开启外接或投屏后自动接管至投屏端"
                    status.connectedMouseCount > 0 ->
                        "已检测到 ${status.connectedMouseCount} 个外接键鼠设备，当前作用于本机屏幕"
                    else ->
                        "连接蓝牙 / USB 外置键盘或鼠标后，投屏与外接屏时默认优先控制投屏端"
                },
                fontSize = 11.sp,
                color = if (status.boundMouseCount > 0) OpalPalette.AccentEmerald else OpalPalette.TextMuted,
            )
            Spacer(Modifier.height(6.dp))
            OpalSegmentedRow(
                options = listOf("跟随本机屏幕", "优先投屏 / 外接端"),
                selectedIndex = mouseTarget.coerceIn(AppPrefs.MOUSE_TARGET_LOCAL, AppPrefs.MOUSE_TARGET_EXTERNAL),
                onSelect = { nextMode ->
                    AppPrefs.setMouseTarget(ctx, nextMode)
                    val activeExtId = resolveActiveExternalDisplayId(extDisplay)
                    BridgeClient.setMouseTargetAsync(nextMode, activeExtId) { bound ->
                        val msg = when {
                            nextMode == AppPrefs.MOUSE_TARGET_LOCAL -> "外接键鼠已切回本机屏幕"
                            bound > 0 -> "已将外接键鼠优先路由至投屏/外接端"
                            else -> "已设为优先投屏端，接入外接键鼠或开始投屏后立即生效"
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    }
                },
            )

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(
                    text = "指针移动速度（无级调节 · 同步触控板与物理鼠标）",
                    fontSize = 12.sp,
                    color = OpalPalette.TextSecondary,
                )
                OpalText(
                    text = String.format(Locale.US, "%.2f× · 点此复位", trackpadSens),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = OpalPalette.AccentBlue,
                    modifier = Modifier.clickable {
                        AppPrefs.setTrackpadSensitivity(ctx, AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
                        BridgeClient.setPointerSpeedAsync(AppPrefs.DEFAULT_TRACKPAD_SENSITIVITY)
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            OpalGlassSlider(
                value = trackpadSens,
                valueRange = AppPrefs.MIN_TRACKPAD_SENSITIVITY..AppPrefs.MAX_TRACKPAD_SENSITIVITY,
                onValueChange = { next ->
                    AppPrefs.setTrackpadSensitivity(ctx, next)
                    BridgeClient.setPointerSpeedAsync(next)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(text = "0.50× 精细", fontSize = 10.5.sp, color = OpalPalette.TextMuted)
                OpalText(text = "1.45× 标准", fontSize = 10.5.sp, color = OpalPalette.TextMuted)
                OpalText(text = "3.00× 疾速", fontSize = 10.5.sp, color = OpalPalette.TextMuted)
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(
                    text = if (customWallpaperPath.isNotBlank()) "桌面壁纸（当前已启用相册自定义壁纸）" else "桌面壁纸（支持右键 / 长按桌面自由更换）",
                    fontSize = 12.sp,
                    color = OpalPalette.TextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (customWallpaperPath.isNotBlank()) {
                        OpalButton(
                            label = "恢复预设",
                            compact = true,
                            filled = false,
                            onClick = {
                                WallpaperStorage.clearCustomWallpaper(ctx)
                                Toast.makeText(ctx, "已恢复预设壁纸", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                    OpalButton(
                        label = "从相册选壁纸",
                        compact = true,
                        onClick = onPickCustomWallpaper,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val themes = if (isDark) {
                listOf(
                    "红杉深海" to listOf(Color(0xFF142842), Color(0xFF2A9D8F)),
                    "索诺玛暮色" to listOf(Color(0xFF2B2752), Color(0xFFE07A5F)),
                    "极地冰川" to listOf(Color(0xFF0E293D), Color(0xFF38BDF8)),
                    "曜石暗夜" to listOf(Color(0xFF1C1F26), Color(0xFF4B5563)),
                )
            } else {
                listOf(
                    "晴空云雾" to listOf(Color(0xFFC8DEFB), Color(0xFF60A5FA)),
                    "晨曦薄暮" to listOf(Color(0xFFFBDCE8), Color(0xFFF472B6)),
                    "冰川清露" to listOf(Color(0xFFBFE7F4), Color(0xFF38BDF8)),
                    "素白霜华" to listOf(Color(0xFFE9EEF6), Color(0xFFCBD5E1)),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                themes.forEachIndexed { idx, (name, grad) ->
                    val selected = customWallpaperPath.isBlank() && idx == wallpaperPreset
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                WallpaperStorage.clearCustomWallpaper(ctx)
                                AppPrefs.setWallpaperPreset(ctx, idx)
                            }
                            .padding(vertical = 3.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Brush.linearGradient(grad))
                                .border(
                                    width = if (selected) 1.8.dp else 0.8.dp,
                                    color = if (selected) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                                    shape = RoundedCornerShape(10.dp),
                                ),
                        )
                        Spacer(Modifier.height(4.dp))
                        OpalText(
                            text = name,
                            fontSize = 11.sp,
                            color = if (selected) OpalPalette.TextPrimary else OpalPalette.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tab 3: Engine & Advanced Settings — Input Backend Routing, VirtualDisplay Pool Quota, Shutdown.
 */
@Composable
fun ConsoleEngineSettingsTab(
    status: DaemonStatus,
    onStopEngine: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        MacGlassSectionCard {
            OpalText(text = "底层输入路由与虚拟屏池配额", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            OpalText(
                text = "默认走 AOSP InputManager 系统直通；若遇到 ROM 拦截跨屏触摸，可自动或手动切换至 Linux 内核 /dev/uhid 虚拟外设。",
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
            )
            Spacer(Modifier.height(12.dp))

            val inputIdx = when (status.inputBackendMode) {
                BridgeProtocol.InputBackend.SYSTEM -> 1
                BridgeProtocol.InputBackend.UHID -> 2
                else -> 0
            }
            OpalSegmentedRow(
                options = listOf("自动回退 (推荐)", "IInputManager 注入", "Linux /dev/uhid"),
                selectedIndex = inputIdx,
                onSelect = { idx ->
                    val mode = when (idx) {
                        1 -> BridgeProtocol.InputBackend.SYSTEM
                        2 -> BridgeProtocol.InputBackend.UHID
                        else -> BridgeProtocol.InputBackend.AUTO
                    }
                    BridgeClient.updateCompositorConfigAsync(inputBackendMode = mode)
                },
            )

            Spacer(Modifier.height(16.dp))
            OpalText(
                text = "活跃 VirtualDisplay 并发上限（超出上限的后台窗口自动冻结为 TaskSnapshot 零显存卡片，点击秒唤醒）",
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            val poolOptions = listOf(3, 4, 5, 6, 8)
            val curMaxIdx = poolOptions.indexOf(status.maxVdCount).let { if (it >= 0) it else 3 }
            OpalSegmentedRow(
                options = poolOptions.map { "$it 实时屏" },
                selectedIndex = curMaxIdx,
                onSelect = { idx ->
                    BridgeClient.updateCompositorConfigAsync(maxVdCount = poolOptions[idx])
                },
            )
        }

        if (status.connected) {
            MacGlassSectionCard {
                OpalText(text = "守护进程会话控制", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OpalButton(
                        label = "关闭所有窗口",
                        filled = false,
                        compact = true,
                        accent = OpalPalette.AccentAmber,
                        onClick = { BridgeClient.closeAllWindowsAsync(removeTasks = true) },
                        modifier = Modifier.weight(1f),
                    )
                    OpalButton(
                        label = "彻底停止守护进程",
                        filled = false,
                        compact = true,
                        accent = OpalPalette.AccentDanger,
                        onClick = onStopEngine,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickBentoChip(
    label: String,
    tint: Color = OpalPalette.TextPrimary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(OpalPalette.ChipFill)
            .border(0.7.dp, OpalPalette.BorderSubtle, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        OpalText(text = label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tint, maxLines = 1)
    }
}

@Composable
private fun CopyableInfoLine(ctx: Context, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            OpalText(text = label, fontSize = 11.sp, color = OpalPalette.TextMuted)
            OpalText(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(OpalPalette.ChipFill)
                .clickable { copyTextWithToast(ctx, value, "已复制 $label") }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            OpalText(text = "复制", fontSize = 11.sp, color = OpalPalette.AccentCyan, fontWeight = FontWeight.Medium)
        }
    }
}

private fun resolveActiveExternalDisplayId(extDisplay: BridgeService.ExternalDisplayInfo?): Int {
    val liveId = ExternalDesktopActivity.activeExternalDisplayId
    if (liveId > 0) return liveId
    return if (ExternalDesktopActivity.isPresentationActive && extDisplay != null) extDisplay.displayId else -1
}

private fun copyTextWithToast(ctx: Context, text: String, toastMsg: String) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("OpalDesk", text))
        Toast.makeText(ctx, toastMsg, Toast.LENGTH_SHORT).show()
    } catch (_: Throwable) {
    }
}

private fun toggleHostScreenPowerWithToast(ctx: Context, screenOn: Boolean) {
    BridgeClient.setHostScreenPowerAsync(screenOn) { ok ->
        val msg = when {
            !ok -> "当前设备暂不支持物理息屏控制"
            screenOn -> "已点亮本机屏幕"
            else -> "已物理熄灭本机屏幕（双击手机屏幕可随时亮屏，断开投屏也会自动亮屏）"
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}

private fun tileFromConsole(layout: WindowTilingLayout, scene: CompositorScene, status: DaemonStatus) {
    val density = (status.canvasDpi / 160f).coerceIn(0.8f, 3.5f)
    applyWindowTilingLayout(layout, scene, status.canvasWidth, status.canvasHeight, density)
}

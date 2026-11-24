package io.github.lengsukq.opaldesk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.cast.OpalPeerCastMutex
import io.github.lengsukq.opaldesk.cast.OpalPeerDiscovery
import io.github.lengsukq.opaldesk.cast.PeerFloatingAppService
import io.github.lengsukq.opaldesk.external.ExternalTrackpadBridge
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.RemoteStreamStatus
import io.github.lengsukq.opaldesk.update.AppUpdateLogic
import io.github.lengsukq.opaldesk.update.AppUpdateManager
import io.github.lengsukq.opaldesk.web.WebActivationPolicy
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
 * Tab 1: Workstation Home — Apple HIG inspired 3-card layout with one focal Hero CTA per state,
 * zero duplicate buttons, clean 2-mode Cross-Device Interconnect switcher, and progressive disclosure.
 */
@Composable
fun ConsoleDashboardTab(
    status: DaemonStatus,
    scene: CompositorScene,
    remote: RemoteStreamStatus,
    extDisplay: BridgeService.ExternalDisplayInfo?,
    needsLocalNetworkPerm: Boolean = false,
    onRequestLocalNetworkPerm: () -> Unit = {},
    onEnterLocalDesktop: () -> Unit,
    onOpenRemoteTrackpad: () -> Unit,
    onOpenExternalOrTrackpad: (Int) -> Unit,
    onConnectPeerCast: (String, Int, String) -> Unit,
    onOpenFloatingAppPicker: (String, Int, String) -> Unit,
    onOpenActivation: () -> Unit,
    onRequestCompleteExit: () -> Unit,
) {
    val ctx = LocalContext.current
    var autoExtDisplay by remember { mutableStateOf(AppPrefs.isAutoExternalDisplayEnabled(ctx)) }
    val autoShowPhoneTrackpad by AppPrefs.autoShowPhoneTrackpadFlow.collectAsState()
    val extSession by ExternalTrackpadBridge.sessionFlow.collectAsState()
    val peerRole by OpalPeerCastMutex.roleFlow.collectAsState()
    val discoveredPeers by OpalPeerDiscovery.discoveredPeersFlow.collectAsState()
    val trustedPeers by BridgeClient.trustedPeersFlow.collectAsState()
    val activeFloating by PeerFloatingAppService.activeFlow.collectAsState()

    var castModeTab by remember { mutableIntStateOf(if (remote.wifiEnabled || remote.connected) 1 else 0) }
    var showManualConnect by remember { mutableStateOf(false) }
    var showWebCastQr by remember { mutableStateOf(false) }
    var wifiBusy by remember { mutableStateOf(false) }
    var peerHostText by remember { mutableStateOf("") }
    var peerLabelText by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Card 1: Primary Workstation Hero Card — Single clear primary action per live state
        val physicalExtActive = extSession.isPhysicalActive
        val activeExtDisplayId = extSession.displayId.takeIf { it > 0 } ?: (extDisplay?.displayId ?: -1)
        val activeExtSummary = extSession.summary.ifBlank {
            extDisplay?.let { "${it.name} · ${it.width}×${it.height} @ ${it.refreshRateHz}Hz" }.orEmpty()
        }
        val heroAccent = when {
            !status.connected -> null
            remote.connected -> OpalPalette.AccentEmerald
            physicalExtActive -> OpalPalette.AccentBlue
            else -> OpalPalette.AccentBlue.copy(alpha = 0.55f)
        }
        MacGlassSectionCard(accentBorder = heroAccent) {
            when {
                !status.connected -> {
                    OpalText(text = "多窗合成工作站", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    OpalText(
                        text = "免 Root 独立虚拟屏架构 · 激活引擎后即可开启桌面或跨设备投屏",
                        fontSize = 12.5.sp,
                        color = OpalPalette.TextSecondary,
                    )
                    Spacer(Modifier.height(16.dp))
                    OpalButton(
                        label = "激活多窗引擎",
                        onClick = onOpenActivation,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                remote.connected -> {
                    val fps = (remote.fpsX100 / 100).coerceAtLeast(30)
                    val peerName = remote.peerName.ifBlank { "对端设备" }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(OpalPalette.AccentEmerald),
                                )
                                OpalText(
                                    text = if (remote.hasActiveDesktopCast) {
                                        "正在${remote.channelLabel}投屏"
                                    } else {
                                        "独立应用小窗投射中"
                                    },
                                    fontSize = 17.5.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            OpalText(
                                text = "$peerName · ${remote.width}×${remote.height} @ ${fps}Hz",
                                fontSize = 12.5.sp,
                                color = OpalPalette.AccentEmerald,
                            )
                        }
                        if (remote.activeSessionPermanent) {
                            OpalStatusBadge(label = "永久信任", color = OpalPalette.AccentEmerald)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    if (remote.hasActiveDesktopCast) {
                        OpalButton(
                            label = "打开本机触控板",
                            accent = OpalPalette.AccentBlue,
                            onClick = onOpenRemoteTrackpad,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OpalButton(
                            label = if (status.hostScreenOff) "点亮本机屏幕" else "物理息屏省电",
                            compact = true,
                            filled = status.hostScreenOff,
                            accent = if (status.hostScreenOff) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
                            onClick = { toggleHostScreenPowerWithToast(ctx, screenOn = status.hostScreenOff) },
                            modifier = Modifier.weight(1f),
                        )
                        OpalButton(
                            label = "断开投屏",
                            compact = true,
                            filled = false,
                            accent = OpalPalette.AccentDanger,
                            onClick = { BridgeClient.disconnectRemoteStreamAsync() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OpalText(
                        text = "投屏期间已锁定本机 DeX 桌面，避免双端画面冲突",
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextMuted,
                    )
                }

                physicalExtActive -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(OpalPalette.AccentBlue),
                                )
                                OpalText(text = "外接屏工作站运行中", fontSize = 17.5.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(4.dp))
                            OpalText(
                                text = activeExtSummary.ifBlank { "物理外接显示器已连接" },
                                fontSize = 12.5.sp,
                                color = OpalPalette.AccentCyan,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    OpalButton(
                        label = "打开本机触控板",
                        onClick = { onOpenExternalOrTrackpad(activeExtDisplayId) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OpalButton(
                            label = if (status.hostScreenOff) "点亮本机屏幕" else "物理息屏省电",
                            compact = true,
                            filled = status.hostScreenOff,
                            accent = if (status.hostScreenOff) OpalPalette.AccentEmerald else OpalPalette.AccentBlue,
                            onClick = { toggleHostScreenPowerWithToast(ctx, screenOn = status.hostScreenOff) },
                            modifier = Modifier.weight(1f),
                        )
                        OpalButton(
                            label = "进入本机桌面",
                            compact = true,
                            filled = false,
                            onClick = onEnterLocalDesktop,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            OpalText(text = "多窗合成工作站", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(3.dp))
                            OpalText(
                                text = if (scene.windows.isNotEmpty()) {
                                    "当前已打开 ${scene.windows.size} 个窗口 · ${status.injectBackendLabel}"
                                } else {
                                    "引擎已就绪 · 随时进入本机多窗桌面"
                                },
                                fontSize = 12.5.sp,
                                color = if (scene.windows.isNotEmpty()) OpalPalette.AccentCyan else OpalPalette.TextSecondary,
                            )
                        }
                    }

                    if (status.hostScreenOff) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(OpalPalette.AccentEmerald.copy(alpha = 0.14f))
                                .border(0.8.dp, OpalPalette.AccentEmerald.copy(alpha = 0.36f), RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            OpalText(
                                text = "本机物理屏幕处于息屏状态",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = OpalPalette.AccentEmerald,
                            )
                            OpalButton(
                                label = "点亮屏幕",
                                compact = true,
                                accent = OpalPalette.AccentEmerald,
                                onClick = { toggleHostScreenPowerWithToast(ctx, screenOn = true) },
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    QuickSlotSwitcherStrip(
                        compact = true,
                        showRestoreButton = false,
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OpalButton(
                            label = "进入本机 DeX 桌面",
                            onClick = onEnterLocalDesktop,
                            modifier = Modifier.weight(1.25f),
                        )
                        if (extDisplay != null) {
                            OpalButton(
                                label = "外接屏满屏",
                                accent = OpalPalette.AccentEmerald,
                                onClick = { onOpenExternalOrTrackpad(extDisplay.displayId) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    if (scene.windows.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            OpalButton(
                                label = "显示桌面",
                                compact = true,
                                filled = false,
                                onClick = {
                                    scene.visibleWindows.forEach { win ->
                                        BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.MINIMIZE)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            OpalButton(
                                label = "关闭全部窗口",
                                compact = true,
                                filled = false,
                                accent = OpalPalette.AccentAmber,
                                onClick = { BridgeClient.closeAllWindowsAsync(removeTasks = true) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        // Compact Touch Input Troubleshooting Tip (highlighted in amber when input injection is blocked)
        TouchInputTroubleshootingTipCard(
            ctx = ctx,
            inputBlocked = status.connected && status.injectStatus == BridgeProtocol.InputBackend.STATUS_BLOCKED,
        )

        // Card 2: Cross-Device Interconnect — Clean 2-Mode Switcher ("连接设备" / "共享本机")
        MacGlassSectionCard(
            accentBorder = when {
                peerRole.isReceiving || activeFloating != null -> OpalPalette.AccentBlue
                remote.wifiEnabled -> OpalPalette.AccentEmerald.copy(alpha = 0.45f)
                else -> null
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(text = "跨设备互联", fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold)
                when {
                    remote.connected -> OpalStatusBadge(label = remote.channelLabel, color = OpalPalette.AccentEmerald)
                    peerRole.isReceiving -> OpalStatusBadge(label = "接收中", color = OpalPalette.AccentBlue)
                    remote.wifiEnabled -> OpalStatusBadge(label = "无线共享已开", color = OpalPalette.AccentEmerald)
                }
            }

            if (activeFloating != null) {
                val fl = activeFloating!!
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(OpalPalette.AccentBlue.copy(alpha = 0.13f))
                        .border(0.8.dp, OpalPalette.AccentBlue.copy(alpha = 0.34f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        OpalText(
                            text = "小窗运行中：${fl.appLabel}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OpalPalette.AccentBlue,
                            maxLines = 1,
                        )
                        OpalText(
                            text = "来自 ${fl.peerName}",
                            fontSize = 11.sp,
                            color = OpalPalette.TextSecondary,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OpalButton(
                        label = "关闭",
                        accent = OpalPalette.AccentDanger,
                        filled = false,
                        compact = true,
                        onClick = { PeerFloatingAppService.stop(ctx) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            OpalSegmentedRow(
                options = listOf("连接设备", "共享本机"),
                selectedIndex = castModeTab,
                onSelect = { castModeTab = it },
            )

            Spacer(Modifier.height(14.dp))
            if (castModeTab == 0) {
                // Mode 0: Connect to another device (Desktop Cast or Standalone Floating Window)
                if (discoveredPeers.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        discoveredPeers.forEach { peer ->
                            val addr = "${peer.host}:${peer.port}"
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(OpalPalette.ChipFill)
                                    .border(0.75.dp, OpalPalette.BorderSubtle, RoundedCornerShape(16.dp))
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalArrangement = Arrangement.spacedBy(9.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        OpalText(
                                            text = peer.displayName,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                        )
                                        OpalText(
                                            text = if (peer.shortFingerprint.isNotEmpty()) "$addr · ${peer.shortFingerprint}" else addr,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = OpalPalette.TextMuted,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OpalButton(
                                        label = if (remote.connected) "投屏锁定中" else "投屏桌面",
                                        accent = OpalPalette.AccentEmerald,
                                        compact = true,
                                        enabled = !remote.connected,
                                        onClick = { onConnectPeerCast(peer.host, peer.port, peer.displayName) },
                                        modifier = Modifier.weight(1f),
                                    )
                                    OpalButton(
                                        label = "应用小窗",
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
                } else if (needsLocalNetworkPerm) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(OpalPalette.ChipFill)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        OpalText(
                            text = "授权本地网络权限后可自动发现同局域网设备",
                            fontSize = 12.sp,
                            color = OpalPalette.TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        OpalButton(
                            label = "开启发现",
                            compact = true,
                            onClick = onRequestLocalNetworkPerm,
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(OpalPalette.ChipFill)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        OpalText(
                            text = if (remote.connected) {
                                "本机正在向外投屏，单向防循环已暂时锁定接收端"
                            } else {
                                "正在自动发现同一局域网内已开启「共享本机」的设备…"
                            },
                            fontSize = 12.sp,
                            color = OpalPalette.TextSecondary,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showManualConnect = !showManualConnect }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OpalText(
                        text = if (showManualConnect) "收起手动输入 ▴" else "手动输入 IP 地址 ▾",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = OpalPalette.AccentBlue,
                    )
                }

                if (showManualConnect) {
                    Spacer(Modifier.height(8.dp))
                    OpalTextInput(
                        value = peerHostText,
                        onValueChange = { peerHostText = it },
                        placeholder = "输入对方 IP:端口 (如 192.168.1.8:19878)",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OpalButton(
                            label = if (remote.connected) "投屏锁定中" else "投屏桌面",
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
                            label = "应用小窗",
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
            } else {
                // Mode 1: Share this device to Mac or another Android device
                OpalSwitchRow(
                    title = "允许局域网设备连接本机",
                    subtitle = when {
                        peerRole.isReceiving -> "正在接收其他设备画面，已暂时锁定共享"
                        remote.wifiEnabled -> "TLS 加密服务运行中 · 免 PIN 实时弹窗确认授权"
                        else -> "有线 USB 连接 Mac 无需开启；无线连接时打开此开关"
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
                    val webCastUrl = if (remote.lanIp.isNotEmpty()) {
                        WebActivationPolicy.buildWebCastUrl(remote.lanIp, port)
                    } else {
                        WebActivationPolicy.DEFAULT_WEB_CAST_URL
                    }
                    Spacer(Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(15.dp))
                            .background(OpalPalette.ChipFill)
                            .border(0.8.dp, OpalPalette.BorderSubtle, RoundedCornerShape(15.dp))
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CopyableInfoLine(ctx, "局域网地址", endpoint)
                        if (remote.shortCertFingerprint.isNotEmpty()) {
                            CopyableInfoLine(ctx, "TLS 证书短指纹", remote.shortCertFingerprint)
                        }
                        CopyableInfoLine(ctx, "Web 浏览器投屏链接", webCastUrl)
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showWebCastQr = !showWebCastQr }
                                .padding(vertical = 3.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OpalText(
                                text = if (showWebCastQr) "收起 Web 扫码投屏二维码 ▴" else "展开 Web 扫码投屏二维码 ▾",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = OpalPalette.AccentBlue,
                            )
                        }
                        if (showWebCastQr) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                OpalQrCodeCard(content = webCastUrl, qrSize = 168.dp)
                            }
                            OpalText(
                                text = "使用电脑 / 平板 Chrome 或 Edge 扫码打开 GitHub Pages 即可直连投屏（免安装客户端）",
                                fontSize = 11.sp,
                                color = OpalPalette.TextMuted,
                            )
                        }
                    }
                }

                if (status.connected && trustedPeers.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(15.dp))
                            .background(OpalPalette.ChipFill)
                            .border(0.8.dp, OpalPalette.BorderSubtle, RoundedCornerShape(15.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            OpalText(
                                text = "已永久信任 (${trustedPeers.size})",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (trustedPeers.size >= 2) {
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
                        }
                        trustedPeers.forEach { peer ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(OpalPalette.GlassPopover.copy(alpha = 0.55f))
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    OpalText(text = peer.clientName, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                    OpalText(
                                        text = peer.clientId,
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = OpalPalette.TextMuted,
                                        maxLines = 1,
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
            }
        }

        // Card 3: Physical USB-C / HDMI External Monitor (Compact, Low-Noise)
        val hasPhysicalExt = extDisplay != null || physicalExtActive
        MacGlassSectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OpalText(text = "USB-C / HDMI 外接显示器", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    OpalText(
                        text = when {
                            physicalExtActive && activeExtSummary.isNotBlank() -> activeExtSummary
                            extDisplay != null ->
                                "${extDisplay.name} · ${extDisplay.width}×${extDisplay.height} @ ${extDisplay.refreshRateHz}Hz"
                            else -> "未检测到物理外接显示器"
                        },
                        fontSize = 12.sp,
                        color = if (hasPhysicalExt) OpalPalette.AccentEmerald else OpalPalette.TextSecondary,
                    )
                }
                if (hasPhysicalExt) {
                    Spacer(Modifier.width(10.dp))
                    OpalButton(
                        label = if (physicalExtActive) "触控板" else "满屏投射",
                        enabled = status.connected,
                        compact = true,
                        onClick = { onOpenExternalOrTrackpad(activeExtDisplayId) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OpalSwitchRow(
                title = "接入时自动满屏工作站",
                subtitle = "消除镜像黑边，按外接屏原生分辨率与高刷输出",
                checked = autoExtDisplay,
                onCheckedChange = {
                    autoExtDisplay = it
                    AppPrefs.setAutoExternalDisplayEnabled(ctx, it)
                },
            )
            OpalSwitchRow(
                title = "接入外接屏时自动显示手机触控板",
                subtitle = "连上外接屏启动工作站时自动在手机拉起触控板，亦可在外接屏控制中心随时开关",
                checked = autoShowPhoneTrackpad,
                onCheckedChange = { enabled ->
                    AppPrefs.setAutoShowPhoneTrackpadEnabled(ctx, enabled)
                },
            )
        }

        // Bottom Footer Action: Complete App & Daemon Exit
        MacGlassSectionCard(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
            OpalButton(
                label = "完全退出 OpalDesk",
                filled = false,
                accent = OpalPalette.AccentDanger,
                onClick = onRequestCompleteExit,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OpalText(
                text = "将关闭多窗引擎守护进程、投屏及后台常驻服务，并彻底退出应用",
                fontSize = 11.5.sp,
                color = OpalPalette.TextMuted,
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

        // 3. Target Frame Rate & Output UI Scale (Unified for USB-C & Casting; DPI is adjusted visually inside DeX)
        MacGlassSectionCard {
            OpalText(text = "帧率与界面缩放（外接与投屏统一生效）", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
            OpalText(text = "外接屏 / 投屏桌面界面缩放比例 (UI Scale)", fontSize = 12.sp, color = OpalPalette.TextSecondary)
            Spacer(Modifier.height(6.dp))
            val scaleOpts = listOf(1.0f, 1.25f, 1.5f)
            val scaleIdx = scaleOpts.indexOfFirst { abs(it - extUiScale) < 0.08f }.let { if (it >= 0) it else 1 }
            OpalSegmentedRow(
                options = listOf("100% 精细", "125% 标准", "150% 舒适"),
                selectedIndex = scaleIdx,
                onSelect = { idx ->
                    io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.setWorkingExternalUiScale(ctx, scaleOpts[idx])
                },
            )

            Spacer(Modifier.height(10.dp))
            OpalText(
                text = "应用窗口 DPI 请进入 DeX 桌面后，通过顶栏 DPI 入口实时可视化调节",
                fontSize = 11.5.sp,
                color = OpalPalette.TextMuted,
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
                    text = "指针移动速度（同步触控板与鼠标）",
                    fontSize = 12.sp,
                    color = OpalPalette.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                OpalText(
                    text = String.format(Locale.US, "%.2f× · 复位", trackpadSens),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = OpalPalette.AccentBlue,
                    maxLines = 1,
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
                    text = if (customWallpaperPath.isNotBlank()) "桌面壁纸（已启用相册壁纸）" else "桌面壁纸（支持右键 / 长按更换）",
                    fontSize = 12.sp,
                    color = OpalPalette.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
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
 * Tab 3: Engine & Advanced Settings — App Launcher Sorting, Input Backend Routing,
 * VirtualDisplay Pool Quota, Re-Activation & Daemon Shutdown.
 */
@Composable
fun ConsoleEngineSettingsTab(
    status: DaemonStatus,
    scene: CompositorScene,
    onOpenActivation: () -> Unit,
    onOpenUpdateModal: () -> Unit,
    onStopEngine: () -> Unit,
) {
    val ctx = LocalContext.current
    val sortByUsage by AppPrefs.sortAppsByUsageFlow.collectAsState()
    val autoCheckUpdate by AppPrefs.autoCheckUpdateFlow.collectAsState()
    val updateState by AppUpdateManager.stateFlow.collectAsState()
    val currentVersion = remember(ctx) { AppUpdateManager.getCurrentVersionName(ctx) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ConsoleAdvancedSettingsSection(status = status, scene = scene)
        MacGlassSectionCard {
            OpalText(text = "应用列表与启动器", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            OpalSwitchRow(
                title = "应用列表按打开次数排序",
                subtitle = "默认开启；同步作用于 DeX 应用抽屉与远程独立应用小窗，关闭后按名称排序",
                checked = sortByUsage,
                onCheckedChange = { enabled ->
                    AppPrefs.setSortAppsByUsage(ctx, enabled)
                },
            )
        }

        MacGlassSectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OpalText(text = "应用更新", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    OpalText(
                        text = "当前版本 v$currentVersion · GitHub Releases 直连校验",
                        fontSize = 12.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
                when (val st = updateState) {
                    is AppUpdateManager.UpdateState.UpdateAvailable ->
                        OpalStatusBadge(label = "新版本 ${st.release.tagName}", color = OpalPalette.AccentBlue)
                    is AppUpdateManager.UpdateState.ReadyToInstall ->
                        OpalStatusBadge(label = "待安装 ${st.release.tagName}", color = OpalPalette.AccentEmerald)
                    is AppUpdateManager.UpdateState.Downloading -> {
                        val pct = if (st.totalBytes > 0L) ((st.downloadedBytes * 100L) / st.totalBytes).toInt() else 0
                        OpalStatusBadge(label = "下载中 $pct%", color = OpalPalette.AccentBlue)
                    }
                    is AppUpdateManager.UpdateState.UpToDate ->
                        OpalStatusBadge(label = "已是最新", color = OpalPalette.AccentEmerald)
                    is AppUpdateManager.UpdateState.Checking ->
                        OpalStatusBadge(label = "检查中…", color = OpalPalette.AccentCyan)
                    else -> {}
                }
            }

            if (updateState is AppUpdateManager.UpdateState.Error) {
                Spacer(Modifier.height(8.dp))
                OpalText(
                    text = (updateState as AppUpdateManager.UpdateState.Error).message,
                    fontSize = 12.sp,
                    color = OpalPalette.AccentDanger,
                )
            }

            Spacer(Modifier.height(8.dp))
            OpalSwitchRow(
                title = "启动时自动检查新版本",
                subtitle = "每 6 小时静默检查一次 GitHub Releases，发现正式版更新后在顶栏轻提醒",
                checked = autoCheckUpdate,
                onCheckedChange = { enabled ->
                    AppPrefs.setAutoCheckUpdate(ctx, enabled)
                },
            )

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val primaryLabel = when (val st = updateState) {
                    is AppUpdateManager.UpdateState.Checking -> "正在检查更新…"
                    is AppUpdateManager.UpdateState.UpdateAvailable -> "更新至 ${st.release.tagName}"
                    is AppUpdateManager.UpdateState.Downloading -> "查看下载进度"
                    is AppUpdateManager.UpdateState.ReadyToInstall -> "立即安装 ${st.release.tagName}"
                    else -> "检查更新"
                }
                val primaryAccent = when (updateState) {
                    is AppUpdateManager.UpdateState.ReadyToInstall -> OpalPalette.AccentEmerald
                    else -> OpalPalette.AccentBlue
                }
                OpalButton(
                    label = primaryLabel,
                    compact = true,
                    filled = updateState is AppUpdateManager.UpdateState.UpdateAvailable ||
                        updateState is AppUpdateManager.UpdateState.ReadyToInstall,
                    enabled = updateState !is AppUpdateManager.UpdateState.Checking,
                    accent = primaryAccent,
                    onClick = {
                        when (updateState) {
                            is AppUpdateManager.UpdateState.UpdateAvailable,
                            is AppUpdateManager.UpdateState.Downloading,
                            is AppUpdateManager.UpdateState.ReadyToInstall,
                            -> onOpenUpdateModal()
                            else -> {
                                AppUpdateManager.checkForUpdates(ctx, userInitiated = true)
                                onOpenUpdateModal()
                            }
                        }
                    },
                    modifier = Modifier.weight(1.35f),
                )
                OpalButton(
                    label = "GitHub Releases ↗",
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentCyan,
                    onClick = {
                        AppUpdateManager.openReleaseInBrowser(ctx, AppUpdateLogic.ALL_RELEASES_WEB_URL)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

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
                    io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.setWorkingInputBackendMode(mode)
                    BridgeClient.updateCompositorConfigAsync(inputBackendMode = mode)
                },
            )

            Spacer(Modifier.height(16.dp))
            OpalText(
                text = "活跃 VirtualDisplay 并发上限（6 屏 / 8 屏布局建议设为 6–10；超出上限的后台窗口自动休眠为快照卡片）",
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            val poolOptions = listOf(3, 4, 5, 6, 8, 10)
            val curMaxIdx = poolOptions.indexOf(status.maxVdCount).let { if (it >= 0) it else 3 }
            OpalSegmentedRow(
                options = poolOptions.map { "$it 屏" },
                selectedIndex = curMaxIdx,
                onSelect = { idx ->
                    io.github.lengsukq.opaldesk.prefs.ProfileConfigStore.setWorkingMaxVdCount(poolOptions[idx])
                    BridgeClient.updateCompositorConfigAsync(maxVdCount = poolOptions[idx])
                },
            )
        }

        MacGlassSectionCard {
            OpalText(text = "引擎与守护进程控制", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OpalButton(
                    label = if (status.connected) "重新激活引擎" else "激活多窗引擎",
                    filled = !status.connected,
                    compact = true,
                    onClick = onOpenActivation,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (status.connected) {
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
    val session = ExternalTrackpadBridge.sessionFlow.value
    if (session.displayId > 0) return session.displayId
    return extDisplay?.displayId ?: -1
}

private fun copyTextWithToast(ctx: Context, text: String, toastMsg: String) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("OpalDesk", text))
        Toast.makeText(ctx, toastMsg, Toast.LENGTH_SHORT).show()
    } catch (_: Throwable) {
    }
}

private fun openDeveloperSettings(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    } catch (_: Throwable) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            Toast.makeText(ctx, "请手动打开系统「设置 → 开发者选项」", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun TouchInputTroubleshootingTipCard(
    ctx: Context,
    inputBlocked: Boolean,
) {
    val accent = if (inputBlocked) OpalPalette.AccentAmber else OpalPalette.AccentCyan
    MacGlassSectionCard(
        accentBorder = if (inputBlocked) OpalPalette.AccentAmber else null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                OpalText(
                    text = if (inputBlocked) "检测到触摸输入受限" else "触摸无反应排查提示",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (inputBlocked) OpalPalette.AccentAmber else OpalPalette.TextPrimary,
                )
                Spacer(Modifier.height(3.dp))
                OpalText(
                    text = "若进入桌面或投屏后无法触摸点击，请在系统「开发者选项」中开启「USB 调试」与「USB 调试（安全设置）」（部分机型为「禁止权限监控」），或在「设置」页将输入路由切换为 Linux /dev/uhid。",
                    fontSize = 11.5.sp,
                    color = OpalPalette.TextSecondary,
                    lineHeight = 16.5.sp,
                )
            }
            Spacer(Modifier.width(10.dp))
            OpalButton(
                label = "开发者选项",
                compact = true,
                filled = inputBlocked,
                accent = accent,
                onClick = { openDeveloperSettings(ctx) },
            )
        }
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

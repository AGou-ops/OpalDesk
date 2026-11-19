package com.extbridge.helper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.extbridge.helper.protocol.BridgeLabels
import com.extbridge.helper.protocol.BridgeProtocol.RemoteStream
import com.extbridge.helper.protocol.BridgeProtocol.SinkType
import com.extbridge.helper.protocol.DaemonStatus
import com.extbridge.helper.protocol.TrustedMacClient
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

data class RemoteMacUiState(
    val show: Boolean,
    val status: DaemonStatus,
    val localIpv4: String,
    val wifiBusy: Boolean,
    val codecPref: Int,
    val maxBitrateMbps: Int,
    val audioEnabled: Boolean,
    val trustedClients: List<TrustedMacClient>,
    val trustedLoading: Boolean,
)

data class RemoteMacActions(
    val onToggleWifiServer: (Boolean) -> Unit,
    val onRegeneratePin: () -> Unit,
    val onCopyText: (String, String) -> Unit,
    val onDisconnectRemote: () -> Unit,
    val onCodecPrefChange: (Int) -> Unit,
    val onMaxBitrateChange: (Int) -> Unit,
    val onAudioEnabledChange: (Boolean) -> Unit,
    val onApplyStreamPrefs: () -> Unit,
    val onRefreshTrusted: () -> Unit,
    val onRevokeClient: (String) -> Unit,
    val onRevokeAllClients: () -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
fun RemoteMacDisplaySheet(state: RemoteMacUiState, actions: RemoteMacActions) {
    OverlayBottomSheet(show = state.show, title = "Mac / 无线大屏", onDismissRequest = actions.onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 580.dp).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.status.connected) {
                Card(colors = CardDefaults.defaultColors(color = HyperAmber.copy(alpha = 0.14f)), insideMargin = PaddingValues(14.dp)) {
                    Text("请先激活 ExtBridge 核心守护进程后再启用无线大屏或管理已信任的 Mac 设备。", fontSize = 13.sp, color = HyperAmber)
                }
            }
            if (state.status.connected && state.status.isRemoteDisplayActive) {
                RemoteSessionBanner(state.status, actions.onDisconnectRemote)
            }
            SectionHeader("无线大屏服务（TLS 1.3 + Bonjour）", horizontalPadding = 4.dp)
            WifiPinCard(state, actions)
            SectionHeader("视频编码与音频回传偏好", horizontalPadding = 4.dp)
            StreamPrefsCard(state, actions)
            TrustedClientsSection(state, actions)
        }
    }
}

@Composable
private fun RemoteSessionBanner(status: DaemonStatus, onDisconnectRemote: () -> Unit) {
    val (channelBadge, accent) = when (status.sinkType) {
        SinkType.REMOTE_USB -> "USB 有线直连" to HyperGreen
        SinkType.REMOTE_WIFI_ADB -> "无线 ADB 隧道" to HyperBlue
        else -> "Wi-Fi 无线直连" to HyperPurple
    }
    Card(colors = CardDefaults.defaultColors(color = accent.copy(alpha = 0.13f)), insideMargin = PaddingValues(14.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(status.remotePeerName.ifBlank { "Mac 客户端" }, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${status.virtW}×${status.virtH} · ${BridgeLabels.formatStreamFps(status.streamFpsX100)} · ${BridgeLabels.formatBitrateMbps(status.streamBitrateKbps)}",
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            StatusPillBadge(channelBadge, accent)
        }
        Spacer(Modifier.height(10.dp))
        DangerActionButton("断开当前 Mac 连接", onDisconnectRemote, Modifier.fillMaxWidth())
    }
}

@Composable
private fun WifiPinCard(state: RemoteMacUiState, actions: RemoteMacActions) {
    val st = state.status
    Card {
        SwitchPreference(
            title = "开启局域网无线大屏服务",
            summary = if (st.isWifiServerEnabled) "已开启并广播 _extbridge._tcp.，同局域网 Mac 可自动发现"
            else "默认关闭以保护安全；有线 USB 连接 Mac 无需开启此开关",
            checked = st.isWifiServerEnabled, enabled = st.connected && !state.wifiBusy, onCheckedChange = actions.onToggleWifiServer,
        )
        if (!st.connected || !st.isWifiServerEnabled) return@Card

        val port = if (st.wifiServerPort > 0) st.wifiServerPort else RemoteStream.DEFAULT_WIFI_PORT
        val endpoint = "${state.localIpv4}:$port"
        val rawPin = st.activePairingPin.trim()
        val formattedPin = if (rawPin.length == 6) "${rawPin.substring(0, 3)}  ${rawPin.substring(3, 6)}" else rawPin.ifBlank { "------" }

        SectionDivider()
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CopyableEndpointRow("局域网监听地址", endpoint, "复制地址") { actions.onCopyText(endpoint, "已复制局域网地址 $endpoint") }
            CopyableEndpointRow(
                label = "TLS 证书指纹短码（首次配对核验）",
                value = st.shortCertFingerprint.ifBlank { "等待证书初始化…" },
                buttonText = if (st.wifiCertFingerprint.isNotBlank()) "复制指纹" else null,
                highlightValue = true,
            ) { actions.onCopyText(st.wifiCertFingerprint, "已复制完整 SHA-256 证书指纹") }

            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(HyperBlue.copy(alpha = 0.11f)).border(1.dp, HyperBlue.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("一次性 6 位配对 PIN 码", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.primary)
                    Text(formattedPin, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            if (state.wifiBusy) "刷新中…" else "刷新 PIN 码", actions.onRegeneratePin,
                            enabled = !state.wifiBusy, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.weight(1f),
                        )
                        if (rawPin.isNotEmpty()) {
                            TextButton("复制 PIN", { actions.onCopyText(rawPin, "已复制配对 PIN 码") }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyableEndpointRow(label: String, value: String, buttonText: String?, highlightValue: Boolean = false, onCopy: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(
                value, fontSize = if (highlightValue) 13.5.sp else 14.5.sp,
                fontWeight = if (highlightValue) FontWeight.Medium else FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = if (highlightValue) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceContainer,
            )
        }
        if (buttonText != null) TextButton(buttonText, onCopy, insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun StreamPrefsCard(state: RemoteMacUiState, actions: RemoteMacActions) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("编码器偏好", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            TabRowWithContour(
                tabs = listOf("自动 HEVC (推荐)", "兼容 H.264"),
                selectedTabIndex = state.codecPref.coerceIn(0, 1),
                onTabSelected = actions.onCodecPrefChange, modifier = Modifier.fillMaxWidth(),
            )
        }
        SectionDivider()
        SliderPreference(
            title = "视频流码率上限", summary = "建议 Wi-Fi 使用 12–20 Mbps，USB 有线可拉高至 24–32 Mbps",
            value = state.maxBitrateMbps.coerceIn(4, 32).toFloat(), valueRange = 4f..32f, valueText = "${state.maxBitrateMbps} Mbps",
            showKeyPoints = true, keyPoints = listOf(4f, 8f, 16f, 24f, 32f),
            onValueChange = { actions.onMaxBitrateChange(((it / 2f).roundToInt() * 2).coerceIn(4, 32)) },
        )
        SectionDivider()
        SwitchPreference(
            title = "同步回传系统音频到 Mac", summary = "通过 48kHz AAC-LC 低延迟推送播放音频至 Mac 扬声器",
            checked = state.audioEnabled, onCheckedChange = actions.onAudioEnabledChange,
        )
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            TextButton(
                "应用流媒体偏好", actions.onApplyStreamPrefs, enabled = state.status.connected,
                colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TrustedClientsSection(state: RemoteMacUiState, actions: RemoteMacActions) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SmallTitle("已信任的 Mac 设备 (${state.trustedClients.size})", insideMargin = PaddingValues(vertical = 2.dp))
        TextButton(
            if (state.trustedLoading) "读取中…" else "刷新列表", actions.onRefreshTrusted,
            enabled = state.status.connected && !state.trustedLoading, insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        )
    }
    Card(insideMargin = PaddingValues(14.dp)) {
        if (state.trustedClients.isEmpty()) {
            Text(
                if (state.trustedLoading) "正在读取守护进程信任列表…"
                else "暂无已信任的 Mac 设备。在 Mac 端使用上方 6 位 PIN 完成首次配对后，将自动保存信任令牌。",
                fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            return@Card
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.trustedClients.forEachIndexed { idx, client ->
                if (idx > 0) SectionDivider()
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(client.clientName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "ID: ${client.clientId.take(20)}", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DangerActionButton("撤销", { actions.onRevokeClient(client.clientId) }, insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            DangerActionButton("撤销全部已信任 Mac 设备", actions.onRevokeAllClients, Modifier.fillMaxWidth())
        }
    }
}

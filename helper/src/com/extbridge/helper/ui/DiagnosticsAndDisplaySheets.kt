package com.extbridge.helper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.extbridge.helper.protocol.BridgeLabels
import com.extbridge.helper.protocol.BridgeParsers
import com.extbridge.helper.protocol.BridgeProtocol.InputBackend
import com.extbridge.helper.protocol.DaemonStatus
import com.extbridge.helper.protocol.DisplayGeometry
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.abs

@Composable
fun DiagnosticsSheet(
    show: Boolean,
    status: DaemonStatus,
    onRebuild: () -> Unit,
    onDisconnectRemote: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    OverlayBottomSheet(show = show, title = "连接与渲染管线诊断", onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DiagnosticsMetricsCard(status)
            if (status.connected && status.injectStatus == InputBackend.INJECT_STATUS_BLOCKED) {
                Card(colors = CardDefaults.defaultColors(color = HyperRed.copy(alpha = 0.12f)), insideMargin = PaddingValues(14.dp)) {
                    Text(BridgeLabels.getOemInjectHint(), fontSize = 12.5.sp, color = HyperRed)
                }
            }
            if (status.connected && status.isRemoteSink) {
                DangerActionButton("断开当前 Mac 远端连接", { onDisconnectRemote(); onDismiss() }, Modifier.fillMaxWidth())
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (status.connected) TextButton("重建渲染管线", { onRebuild(); onDismiss() }, modifier = Modifier.weight(1f))
                TextButton("完成", onDismiss, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DiagnosticsMetricsCard(status: DaemonStatus) {
    val injectBlocked = status.connected && status.injectStatus == InputBackend.INJECT_STATUS_BLOCKED
    val rows = buildList {
        add(Triple("核心守护进程", if (status.connected) "在线 (Binder 已连接)" else "未激活", null))
        val sinkStr = if (!status.connected) "--" else {
            val base = BridgeLabels.sinkTypeLabel(status.sinkType)
            if (status.isRemoteSink && status.remotePeerName.isNotBlank()) "$base · ${status.remotePeerName}" else base
        }
        add(Triple("输出通道类型", sinkStr, null))
        add(Triple("当前输出模式", if (status.connected) BridgeLabels.mirrorModeLabel(status.mirrorMode) else "--", null))
        val layoutStr = if (!status.connected) "--" else {
            val base = BridgeLabels.layoutModeLabel(status.activeLayoutMode)
            if (status.isAdaptiveSingleEnabled) "$base · 黄金默认 ${status.defaultSingleWidthDp}dp" else base
        }
        add(Triple("当前窗口布局", layoutStr, null))
        if (status.connected && status.isRemoteSink) {
            add(Triple("实时视频流指标", "${BridgeLabels.formatStreamFps(status.streamFpsX100)} · ${BridgeLabels.formatBitrateMbps(status.streamBitrateKbps)}", HyperGreen))
        }
        add(Triple("无线大屏监听", if (!status.connected) "--" else if (status.isWifiServerEnabled) "已开启 · 端口 ${status.wifiServerPort}" else "已关闭", null))
        add(
            Triple(
                "物理外接显示器",
                if (status.connected && status.extId > 0) "Display #${status.extId} · ${status.physW}×${status.physH} @ ${String.format(Locale.US, "%.1f", status.physFps)}Hz"
                else "未检测到物理外接显示器",
                null,
            ),
        )
        add(
            Triple(
                "虚拟扩展画布",
                if (status.connected && status.vdId > 0) "Display #${status.vdId} · ${status.virtW}×${status.virtH} (${BridgeLabels.rotationLabel(status.virtRot)}) · ${status.virtDpi}dpi"
                else "预设 ${status.virtW}×${status.virtH} @ ${status.virtDpi}dpi",
                null,
            ),
        )
        add(Triple("合成管线与帧率", if (status.connected) "${status.renderMode} · 目标 ${status.virtFps.toInt()}Hz" else "--", null))
        add(Triple("键鼠输入路由", if (status.connected) "${if (status.inputToExt == 1) "外接扩展屏" else "本机内屏"} (外接设备 ${status.extInputCnt} 个)" else "--", null))
        add(
            Triple(
                "输入注入状态",
                if (!status.connected) "--" else BridgeLabels.injectStatusLabel(status.injectStatus),
                when { !status.connected -> MiuixTheme.colorScheme.onSurfaceVariantSummary; injectBlocked -> HyperRed; else -> HyperGreen },
            ),
        )
    }

    Card(insideMargin = PaddingValues(16.dp)) {
        rows.forEachIndexed { idx, (label, value, color) ->
            if (idx > 0) SectionDivider()
            DiagRow(label, value, color ?: MiuixTheme.colorScheme.onSurfaceContainer)
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(end = 12.dp))
        Text(value, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = valueColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CustomResolutionDialog(
    show: Boolean,
    initialW: Int,
    initialH: Int,
    onApply: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var wText by remember(show, initialW) { mutableStateOf(initialW.toString()) }
    var hText by remember(show, initialH) { mutableStateOf(initialH.toString()) }
    var errorMsg by remember(show) { mutableStateOf("") }
    val numKb = KeyboardOptions(keyboardType = KeyboardType.Number)

    OverlayDialog(show = show, title = "自定义渲染分辨率", summary = "范围 640×480 ~ 7680×4320（自动 16px 对齐，可随时对调宽高）", onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(wText, { wText = it; errorMsg = "" }, label = "宽度 (W)", singleLine = true, keyboardOptions = numKb, modifier = Modifier.weight(1f))
                Text("×", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                TextField(hText, { hText = it; errorMsg = "" }, label = "高度 (H)", singleLine = true, keyboardOptions = numKb, modifier = Modifier.weight(1f))
            }
            if (errorMsg.isNotEmpty()) Text(errorMsg, fontSize = 12.sp, color = HyperRed)
            TextButton("对调宽高 (W ⇄ H)", { val t = wText; wText = hText; hText = t; errorMsg = "" }, modifier = Modifier.fillMaxWidth())
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton("取消", onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = "应用", colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.weight(1f),
                    onClick = {
                        val cw = wText.trim().toIntOrNull()
                        val ch = hText.trim().toIntOrNull()
                        if (cw == null || ch == null || maxOf(cw, ch) !in 640..7680 || minOf(cw, ch) !in 480..4320) {
                            errorMsg = "请输入有效像素范围（长边 640–7680，短边 480–4320）"
                            return@TextButton
                        }
                        onApply(
                            DisplayGeometry.alignResolution16(cw, minVal = 480, maxVal = 7680),
                            DisplayGeometry.alignResolution16(ch, minVal = 480, maxVal = 7680),
                        )
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
fun EdidAndRefreshSheet(
    show: Boolean,
    selectedFps: Float,
    modesRaw: String,
    selectedPhysModeId: Int,
    onSelectFps: (Float) -> Unit,
    onSelectPhysMode: (Int, Int, Int, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val fpsPresets = listOf(60f, 75f, 90f, 120f, 144f, 165f)
    val parsedModes = remember(modesRaw) { BridgeParsers.parseEdidModes(modesRaw) }

    OverlayBottomSheet(show = show, title = "外接物理信号与目标刷新率", onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "注：部分 HyperOS 机型固件会锁定外接物理信号输出上限；向下限帧省电或支持 DP 模式切换的设备可在此指定档位。",
                fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            SectionHeader("目标渲染刷新率", horizontalPadding = 4.dp)
            fpsPresets.chunked(3).forEach { rowPresets ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowPresets.forEach { fps ->
                        val sel = abs(fps - selectedFps) < 1f && selectedPhysModeId == -1
                        TextButton(
                            "${fps.toInt()} Hz", { onSelectFps(fps) },
                            colors = if (sel) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            SectionHeader("显示器 EDID 硬件报告档位", horizontalPadding = 4.dp)
            if (parsedModes.isEmpty()) {
                Card(insideMargin = PaddingValues(14.dp)) {
                    Text("暂无外接显示器 EDID 模式数据（接入 Type-C 显示器后自动读取）", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            } else {
                Card(insideMargin = PaddingValues(vertical = 6.dp)) {
                    parsedModes.forEachIndexed { idx, mode ->
                        if (idx > 0) SectionDivider()
                        val sel = mode.id == selectedPhysModeId
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                onSelectPhysMode(mode.id, mode.width, mode.height, mode.fps); onDismiss()
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    "${mode.width} × ${mode.height} @ ${String.format(Locale.US, "%.1f", mode.fps)} Hz",
                                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    color = if (sel) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceContainer,
                                )
                                Text("硬件模式 ID #${mode.id}", fontSize = 11.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            if (sel) StatusPillBadge("已选", MiuixTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

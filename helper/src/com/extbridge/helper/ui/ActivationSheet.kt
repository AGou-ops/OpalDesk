package com.extbridge.helper.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class ActivationUiState(
    val show: Boolean,
    val initialTab: Int,
    val hasSuHint: Boolean,
    val autoRootEnabled: Boolean,
    val mdnsHint: String,
    val pairHost: String,
    val pairPort: String,
    val pairCode: String,
    val connPort: String,
    val statusText: String,
    val statusColor: Color,
    val working: Boolean,
    val pcAdbCommand: String,
)

data class ActivationActions(
    val onAutoRootChange: (Boolean) -> Unit,
    val onPairHostChange: (String) -> Unit,
    val onPairPortChange: (String) -> Unit,
    val onPairCodeChange: (String) -> Unit,
    val onConnPortChange: (String) -> Unit,
    val onSelectWirelessTab: () -> Unit,
    val onRunRoot: () -> Unit,
    val onOpenDevOptions: () -> Unit,
    val onSendPairNotif: () -> Unit,
    val onRunWirelessPair: () -> Unit,
    val onRunWirelessConnect: () -> Unit,
    val onCopyAdbCmd: () -> Unit,
    val onSkip: () -> Unit,
)

@Composable
fun ActivationSheet(state: ActivationUiState, actions: ActivationActions) {
    var tabIdx by remember(state.show, state.initialTab) { mutableIntStateOf(state.initialTab) }

    OverlayBottomSheet(show = state.show, title = "激活 ExtBridge 核心服务", onDismissRequest = actions.onSkip) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "首次安装或重启设备后需拉起一次后台守护进程（连接成功后窗口将自动关闭）。",
                fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            TabRowWithContour(
                tabs = listOf("Root 激活", "无线调试", "电脑 ADB"),
                selectedTabIndex = tabIdx,
                onTabSelected = { tabIdx = it; if (it == 1) actions.onSelectWirelessTab() },
                modifier = Modifier.fillMaxWidth(),
            )
            when (tabIdx) {
                0 -> RootActivationTab(state, actions)
                1 -> WirelessActivationTab(state, actions)
                else -> PcAdbActivationTab(state.pcAdbCommand, actions.onCopyAdbCmd)
            }
            if (state.statusText.isNotBlank()) {
                Card(insideMargin = PaddingValues(12.dp)) { Text(state.statusText, fontSize = 12.sp, color = state.statusColor) }
            }
            TextButton("暂不激活，先浏览控制台", actions.onSkip, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RootActivationTab(state: ActivationUiState, actions: ActivationActions) {
    Card(insideMargin = PaddingValues(14.dp)) {
        Text(
            text = if (state.hasSuHint) {
                "已检测到 su 环境（Magisk / KernelSU / APatch）。一键授权后自动降权至 shell 身份运行，无需开启无线调试。"
            } else {
                "适合已 Root 设备（Magisk / KernelSU / APatch）。若隐藏了 su 仍可直接点击下方按钮请求授权。"
            },
            fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(
            text = if (state.working) "正在请求 Root 授权…" else "一键 Root 注册并激活服务",
            enabled = !state.working, onClick = actions.onRunRoot,
            colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.fillMaxWidth(),
        )
    }
    Card {
        SwitchPreference(
            title = "启动软件时自动 Root 激活", summary = "进入应用发现守护进程未运行时静默拉起",
            checked = state.autoRootEnabled, onCheckedChange = actions.onAutoRootChange,
        )
    }
}

@Composable
private fun WirelessActivationTab(state: ActivationUiState, actions: ActivationActions) {
    val numKb = KeyboardOptions(keyboardType = KeyboardType.Number)
    Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)), insideMargin = PaddingValues(12.dp)) {
        Text(state.mdnsHint.ifBlank { "正在通过 mDNS 自动扫描本机无线调试配对与连接端口…" }, fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton("1. 开启无线调试", actions.onOpenDevOptions, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.weight(1f))
        TextButton("刷新配对通知", actions.onSendPairNotif, modifier = Modifier.weight(1f))
    }
    TextField(state.pairHost, actions.onPairHostChange, label = "本机 IP 地址 (如 192.168.31.20)", singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(state.pairPort, actions.onPairPortChange, label = "配对端口", singleLine = true, keyboardOptions = numKb, modifier = Modifier.weight(1f))
        TextField(state.pairCode, actions.onPairCodeChange, label = "6 位配对码", singleLine = true, keyboardOptions = numKb, modifier = Modifier.weight(1f))
    }
    TextField(state.connPort, actions.onConnPortChange, label = "连接端口 (已配对过仅填此项即可)", singleLine = true, keyboardOptions = numKb, modifier = Modifier.fillMaxWidth())
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton("配对并激活", actions.onRunWirelessPair, enabled = !state.working, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.weight(1f))
        TextButton("已配对·直连", actions.onRunWirelessConnect, enabled = !state.working, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PcAdbActivationTab(pcAdbCommand: String, onCopy: () -> Unit) {
    Text("电脑通过 USB 或无线 ADB 连接本机后，执行下方单行命令即可自动释放并启动守护进程：", fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer), insideMargin = PaddingValues(14.dp)) {
        Text(pcAdbCommand, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = MiuixTheme.colorScheme.onSurfaceContainer)
    }
    TextButton("复制 ADB 激活命令", onCopy, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.fillMaxWidth())
}

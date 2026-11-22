package io.github.lengsukq.opaldesk.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeLabels
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.InputBackend
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsPage(
    ctrl: MainController,
    grantedPermCount: Int,
    contentPadding: PaddingValues,
    onOpenPermChecklist: () -> Unit,
    onOpenRestrictedSettings: () -> Unit,
    onStopAllAndExit: () -> Unit,
) {
    val ctx = LocalContext.current
    val st = ctrl.daemonStatus

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader("界面外观")
                Card {
                    OverlayDropdownPreference(
                        title = "主题外观模式", summary = "切换 Xiaomi HyperOS 浅色或深色视觉风格",
                        items = ExtThemePrefs.THEME_LABELS, selectedIndex = ctrl.themeModeIdx,
                        onSelectedIndexChange = { ctrl.themeModeIdx = it; ExtThemePrefs.setThemeModeIndex(ctx, it) },
                    )
                }

                SectionHeader("输入注入引擎")
                InputEngineCard(ctrl.inputBackendMode, st.connected, st.injectStatus) { ctrl.applyInputBackendMode(it) }

                SectionHeader("扩展桌面行为")
                Card {
                    SwitchPreference(
                        title = "底部 DeX 任务栏自动隐藏", summary = "进入应用后自动收起底部任务栏，鼠标移至底边瞬时唤醒",
                        checked = ctrl.dockAutoHide,
                        onCheckedChange = { ctrl.dockAutoHide = it; BridgeService.setDockAutoHideEnabled(ctx, it) },
                    )
                    SectionDivider(16.dp)
                    SwitchPreference(
                        title = "DeX 默认横屏大屏策略", summary = "扩展桌面默认以平板横屏满幅窗口启动应用",
                        checked = st.fullscreenPolicy == 1, enabled = st.connected,
                        onCheckedChange = { ctrl.setFullscreenPolicy(it) },
                    )
                }

                SectionHeader("守护进程与权限健康")
                Card {
                    ArrowPreference(
                        title = "Mac / 无线大屏与信任设备管理",
                        summary = if (st.isWifiServerEnabled) "无线服务监听中 :${st.wifiServerPort} · 点击管理 PIN 与已信任 Mac"
                        else "配置无线大屏服务、6 位配对 PIN、编码码率与已信任 Mac 列表",
                        onClick = { ctrl.openRemoteMacSheet() },
                    )
                    SectionDivider(16.dp)
                    ArrowPreference(
                        title = "激活 / 重连核心守护进程",
                        summary = if (st.connected) "当前在线 · 点击查看无线调试 / Shizuku / Root / ADB 引导" else "当前未连接 · 点击立即激活",
                        onClick = { ctrl.openActivationSheet() },
                    )
                    SectionDivider(16.dp)
                    SwitchPreference(
                        title = "启动时自动尝试 Shizuku 激活", summary = "已授权且 Shizuku 在线时打开软件静默拉起守护进程",
                        checked = ctrl.autoShizukuEnabled,
                        onCheckedChange = { ctrl.autoShizukuEnabled = it; AppPrefs.setAutoShizukuEnabled(ctx, it) },
                    )
                    SectionDivider(16.dp)
                    SwitchPreference(
                        title = "启动时自动尝试 Root 激活", summary = "已授 Root 权限的设备打开软件时静默拉起守护进程",
                        checked = ctrl.autoRootEnabled,
                        onCheckedChange = { ctrl.autoRootEnabled = it; AppPrefs.setAutoRootEnabled(ctx, it) },
                    )
                    SectionDivider(16.dp)
                    SwitchPreference(
                        title = "无线激活后自动关闭无线调试", summary = "守护进程就绪后自动关闭系统无线调试开关以收敛攻击面",
                        checked = ctrl.autoCloseWifiAdbEnabled,
                        onCheckedChange = { ctrl.autoCloseWifiAdbEnabled = it; AppPrefs.setAutoCloseWifiAdbEnabled(ctx, it) },
                    )
                    SectionDivider(16.dp)
                    ArrowPreference(
                        title = "运行权限健康检查",
                        summary = if (grantedPermCount == 3) "通知、悬浮窗、无障碍 3/3 已全部就绪" else "已就绪 $grantedPermCount/3 项 · 点击补齐缺失权限",
                        onClick = onOpenPermChecklist,
                    )
                    SectionDivider(16.dp)
                    ArrowPreference("允许受限设置（解锁灰色无障碍开关）", summary = "前往系统应用详情页右上角菜单开启受限设置", onClick = onOpenRestrictedSettings)
                    SectionDivider(16.dp)
                    ArrowPreference(title = "连接与渲染诊断详情", summary = "查看物理/虚拟 Display ID、BBQ 管线与输入注入状态", onClick = { ctrl.showDiagSheet = true })
                }

                Spacer(Modifier.height(4.dp))
                DangerActionButton("停止后台服务并退出 OpalDesk", onStopAllAndExit, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun InputEngineCard(selectedBackendMode: Int, daemonConnected: Boolean, injectStatus: Int, onSelectBackendMode: (Int) -> Unit) {
    val modeOrder = remember { listOf(InputBackend.AUTO, InputBackend.UHID, InputBackend.INJECT_EVENTS) }
    val options = remember { modeOrder.map(BridgeLabels::inputBackendLabel) }
    val selectedIdx = modeOrder.indexOf(selectedBackendMode).coerceAtLeast(0)
    val (statusLabel, statusColor) = when {
        !daemonConnected -> "核心服务未连接" to MiuixTheme.colorScheme.onSurfaceVariantSummary
        injectStatus == InputBackend.INJECT_STATUS_BLOCKED -> "系统注入被拦截 · 建议切换 UHID" to HyperRed
        else -> BridgeLabels.injectStatusLabel(injectStatus) to HyperGreen
    }

    Card {
        OverlayDropdownPreference(
            title = "输入注入引擎",
            summary = "HyperOS 3 拦截系统注入时，自动或手动切换 UHID 虚拟键鼠即可流畅操控大屏",
            items = options, selectedIndex = selectedIdx,
            onSelectedIndexChange = { onSelectBackendMode(modeOrder[it]) },
        )
        SectionDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("当前运行状态", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            StatusPillBadge(statusLabel, statusColor)
        }
    }
}

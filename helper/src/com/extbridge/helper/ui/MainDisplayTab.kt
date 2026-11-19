package com.extbridge.helper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.extbridge.helper.prefs.AppPrefs
import com.extbridge.helper.prefs.MirrorThemeCatalog
import com.extbridge.helper.protocol.BridgeLabels
import com.extbridge.helper.protocol.DisplayGeometry
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun DisplayAndSidebarPage(
    ctrl: MainController,
    contentPadding: PaddingValues,
    onOpenSideControl: () -> Unit,
    onOpenWidgetSlot: (Int) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TabRowWithContour(
                    tabs = listOf("DeX 扩展参数", "原生镜像与边栏"),
                    selectedTabIndex = ctrl.displaySubTab,
                    onTabSelected = { ctrl.displaySubTab = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (ctrl.displaySubTab == 0) DexConfigSection(ctrl) else MirrorConfigSection(ctrl, onOpenSideControl, onOpenWidgetSlot)
            }
        }
    }
}

@Composable
fun DexConfigSection(ctrl: MainController) {
    val presetIdx = remember(ctrl.selW, ctrl.selH) {
        MainController.RES_PRESETS.indexOfFirst { (pw, ph, _) -> (pw == ctrl.selW && ph == ctrl.selH) || (pw == ctrl.selH && ph == ctrl.selW) }
            .takeIf { it >= 0 } ?: MainController.RES_DROPDOWN_LABELS.lastIndex
    }
    val swDp = DisplayGeometry.computeSmallestWidthDp(ctrl.selW, ctrl.selH, ctrl.selDpi)

    SectionHeader("桌面渲染画布与缩放")
    Card {
        OverlayDropdownPreference(
            title = "渲染分辨率预设",
            summary = "当前 ${ctrl.selW} × ${ctrl.selH}（4K 可超采锐化，720p/900p 降载稳帧）",
            items = MainController.RES_DROPDOWN_LABELS, selectedIndex = presetIdx,
            onSelectedIndexChange = { ctrl.selectResolutionPreset(it) },
        )
        SectionDivider(16.dp)
        ArrowPreference(title = "自定义宽高与横竖对调", summary = "${ctrl.selW} × ${ctrl.selH} px", onClick = { ctrl.showCustomResDialog = true })
        SectionDivider(16.dp)
        OverlayDropdownPreference(
            title = "输出桌面旋转方向", summary = "竖置显示器选 90° 或 270°",
            items = MainController.ROT_LABELS, selectedIndex = ctrl.selRot.coerceIn(0, 3),
            onSelectedIndexChange = { ctrl.selectRotation(it) },
        )
        SectionDivider(16.dp)
        SliderPreference(
            title = "界面缩放密度 (DPI)",
            summary = "等效最小宽度 sw${swDp}dp · ${if (swDp >= 600) "平板大屏布局 (sw≥600dp)" else "紧凑布局 (建议降低 DPI 触发大屏 UI)"}",
            value = ctrl.selDpi.coerceIn(100, 320).toFloat(), valueRange = 100f..320f, valueText = "${ctrl.selDpi} dpi",
            showKeyPoints = true, keyPoints = listOf(120f, 160f, 200f, 240f, 280f),
            onValueChange = { v -> ctrl.selDpi = ((v / 4f).roundToInt() * 4).coerceIn(100, 320) },
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MainController.DPI_QUICK_PRESETS.forEach { d ->
                TextButton(
                    text = "$d", onClick = { ctrl.selDpi = d },
                    colors = if (ctrl.selDpi == d) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                    insideMargin = PaddingValues(6.dp), modifier = Modifier.weight(1f),
                )
            }
        }
        if (ctrl.physNotice.isNotBlank()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp)).background(HyperAmber.copy(alpha = 0.14f)).padding(12.dp),
            ) { Text(ctrl.physNotice, fontSize = 12.sp, color = HyperAmber) }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton("应用显示参数", { ctrl.applyChosenDexConfig() }, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.weight(1.25f))
            TextButton("恢复推荐默认", { ctrl.applyMonitorRecommendedDefault() }, modifier = Modifier.weight(1f))
        }
    }

    SectionHeader("单应用默认黄金尺寸")
    AdaptiveSingleSizeCard(
        enabled = ctrl.adaptiveSingleEnabled, defaultWidthDp = ctrl.defaultSingleWidthDp,
        daemonConnected = ctrl.daemonStatus.connected,
        onEnabledChange = { ctrl.applyAdaptiveSingleConfig(it, ctrl.defaultSingleWidthDp, debounce = false, showToast = true) },
        onSelectPresetWidth = { ctrl.applyAdaptiveSingleConfig(ctrl.adaptiveSingleEnabled, it, debounce = false, showToast = true) },
        onSliderWidthChange = { ctrl.applyAdaptiveSingleConfig(ctrl.adaptiveSingleEnabled, it, debounce = true, showToast = false) },
    )

    SectionHeader("快捷预设与硬件信号")
    Card {
        ArrowPreference(
            title = "手机外接推荐：1080p 横屏平板预设",
            summary = "一键设为 1920×1080 @ 240dpi (sw540~600dp 轻量稳帧)",
            onClick = { ctrl.apply1080pPhonePreset() },
        )
        SectionDivider(16.dp)
        ArrowPreference(
            title = "外接物理信号与目标刷新率",
            summary = "目标 ${ctrl.selFps.toInt()} Hz · 查看显示器 EDID 硬件档位",
            onClick = { ctrl.showEdidSheet = true },
        )
    }
}

@Composable
fun MirrorConfigSection(ctrl: MainController, onOpenSideControl: () -> Unit, onOpenWidgetSlot: (Int) -> Unit) {
    val ctx = LocalContext.current
    SectionHeader("画面对齐与融合边栏布局")
    MirrorLayoutSelectorGrid(selectedMode = ctrl.mirrorSideMode, onSelectMode = { ctrl.selectMirrorSideMode(it) })

    SectionHeader("边栏小组件与信息卡片")
    Card {
        ArrowPreference(title = "打开副屏直控面板", summary = "在手机/平板屏幕上实时触控操作外接边栏与小组件", onClick = onOpenSideControl)
        SectionDivider(16.dp)
        ArrowPreference(title = "边栏系统小组件槽位 1", summary = ctrl.widgetSlot0Label ?: "未绑定 · 点击嵌入系统时钟/天气/音乐小组件", onClick = { onOpenWidgetSlot(0) })
        SectionDivider(16.dp)
        ArrowPreference(title = "边栏系统小组件槽位 2", summary = ctrl.widgetSlot1Label ?: "未绑定 · 点击嵌入第二个系统小组件", onClick = { onOpenWidgetSlot(1) })
        SectionDivider(16.dp)
        ArrowPreference(
            title = "边栏信息卡片管理",
            summary = "已启用 ${ctrl.mirrorPresets.size} 张（${ctrl.mirrorPresets.take(3).joinToString("、") { MirrorThemeCatalog.mirrorPresetTitle(it) }}…）",
            onClick = { ctrl.showPresetMgrSheet = true },
        )
        SectionDivider(16.dp)
        ArrowPreference(title = "编辑快捷桌面便签", summary = ctrl.mirrorMemoPreview.ifBlank { "点击填写外接屏边栏待办或备忘" }, onClick = { ctrl.showMemoDialog = true })
    }

    SectionHeader("副屏主题与鼠标手感")
    Card {
        OverlayDropdownPreference(
            title = "副屏背景与文字主题", summary = "即时切换融合边栏配色风格",
            items = MirrorThemeCatalog.MIRROR_THEME_TITLES.toList(),
            selectedIndex = MirrorThemeCatalog.MIRROR_THEME_KEYS.indexOf(ctrl.mirrorThemeId).coerceAtLeast(0),
            onSelectedIndexChange = { idx ->
                val key = MirrorThemeCatalog.MIRROR_THEME_KEYS[idx]
                if (key == "custom") ctrl.showCustomColorDialog = true
                else { AppPrefs.setMirrorThemeId(ctx, key); ctrl.mirrorThemeId = key }
            },
        )
        SectionDivider(16.dp)
        OverlayDropdownPreference(
            title = "副屏跨越鼠标灵敏度", summary = "调节外接鼠标滑入融合边栏时的移动速度",
            items = MirrorThemeCatalog.MIRROR_SPEED_LABELS.toList(),
            selectedIndex = ctrl.mirrorSpeedIdx.coerceIn(0, MirrorThemeCatalog.MIRROR_SPEED_LABELS.lastIndex),
            onSelectedIndexChange = { AppPrefs.setMirrorCursorSpeedIdx(ctx, it); ctrl.mirrorSpeedIdx = it },
        )
    }
}

@Composable
fun AdaptiveSingleSizeCard(
    enabled: Boolean,
    defaultWidthDp: Int,
    daemonConnected: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onSelectPresetWidth: (Int) -> Unit,
    onSliderWidthChange: (Int) -> Unit,
) {
    val minDp = DisplayGeometry.UI_MIN_SINGLE_WIDTH_DP
    val maxDp = DisplayGeometry.UI_MAX_SINGLE_WIDTH_DP
    val clampedDp = DisplayGeometry.clampUiSingleWidthDp(defaultWidthDp)
    val presets = remember { listOf(460 to "460dp 精致手机", 680 to "680dp 折叠屏", 900 to "900dp 平板双栏") }

    Card {
        SwitchPreference(
            title = "单应用默认黄金尺寸",
            summary = "打开单个应用自动居中精致比例，拖动窗口左右边缘实时重排并分应用记忆宽度",
            checked = enabled, onCheckedChange = onEnabledChange,
        )
        SectionDivider()
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("黄金宽度快捷预设", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceContainer)
                StatusPillBadge(
                    label = if (enabled) BridgeLabels.classifyWidthPresetLabel(clampedDp, false) else "已关闭 (默认全屏)",
                    dotColor = if (enabled) HyperBlue else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.forEach { (wDp, label) ->
                    val active = enabled && abs(clampedDp - wDp) <= 10
                    TextButton(
                        text = label, enabled = enabled, onClick = { onSelectPresetWidth(wDp) },
                        colors = if (active) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                        insideMargin = PaddingValues(horizontal = 6.dp, vertical = 7.dp), modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        SectionDivider()
        SliderPreference(
            title = "默认单应用宽度",
            summary = if (daemonConnected) "拖动实时同步守护进程（$minDp–${maxDp}dp，双击窗口边缘手柄可一键切换全屏）" else "本地已保存偏好，激活守护进程后自动应用",
            value = clampedDp.toFloat(), valueRange = minDp.toFloat()..maxDp.toFloat(), valueText = "$clampedDp dp",
            enabled = enabled, showKeyPoints = true, keyPoints = listOf(460f, 680f, 900f),
            onValueChange = { raw -> onSliderWidthChange(((raw / 10f).roundToInt() * 10).coerceIn(minDp, maxDp)) },
        )
    }
}

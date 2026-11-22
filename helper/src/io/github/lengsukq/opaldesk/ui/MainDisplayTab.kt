package io.github.lengsukq.opaldesk.ui

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
import io.github.lengsukq.opaldesk.BridgeService
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.MirrorThemeCatalog
import io.github.lengsukq.opaldesk.protocol.BridgeLabels
import io.github.lengsukq.opaldesk.protocol.DisplayGeometry
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
                if (ctrl.daemonStatus.isLocalDexActive) {
                    DexConfigSection(ctrl)
                } else {
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
}

@Composable
fun DexConfigSection(ctrl: MainController) {
    val st = ctrl.daemonStatus
    if (st.isLocalDexActive) {
        val dpi = ctrl.localDexDpi.coerceIn(160, 480)
        val swDp = DisplayGeometry.computeSmallestWidthDp(st.virtW, st.virtH, dpi)
        val localPresets = remember { listOf(220, 260, 300, 348, 400) }
        SectionHeader("本机 DeX 画面与缩放")
        Card {
            BasicComponent(
                title = "原生屏幕画布",
                summary = "${st.virtW} × ${st.virtH} · ${st.virtFps.toInt()} Hz（自动贴合本机屏幕比例，无需外接显示器）",
            )
            SectionDivider(16.dp)
            SliderPreference(
                title = "界面缩放密度 (DPI)",
                summary = "等效最小宽度 sw${swDp}dp · ${if (swDp >= 600) "平板大屏布局 (sw≥600dp)" else "紧凑布局（调低 DPI 可获得更大桌面视野）"}",
                value = dpi.toFloat(), valueRange = 160f..480f,
                valueText = if (ctrl.hasLocalDexDpiDraft) "$dpi dpi · 待应用" else "$dpi dpi",
                showKeyPoints = true, keyPoints = listOf(220f, 260f, 300f, 348f, 400f),
                onValueChange = { v -> ctrl.setLocalDexDpiDraft((v / 4f).roundToInt() * 4) },
            )
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                localPresets.forEach { d ->
                    TextButton(
                        text = "$d", onClick = { ctrl.setLocalDexDpiDraft(d) },
                        colors = if (dpi == d) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                        insideMargin = PaddingValues(6.dp), modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    if (ctrl.localDexDpiApplying) "正在应用…" else "应用缩放密度",
                    { ctrl.applyLocalDexDpi() }, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.weight(1.25f),
                )
                TextButton("恢复本机推荐", { ctrl.resetLocalDexRecommendedDpi() }, modifier = Modifier.weight(1f))
            }
        }

        SectionHeader("DeX 桌面与窗口偏好")
        DexDesktopBehaviorCard(ctrl)
        return
    }

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
        if (!ctrl.daemonStatus.isLocalDexActive) {
            SectionDivider(16.dp)
            OverlayDropdownPreference(
                title = "输出桌面旋转方向", summary = "竖置显示器选 90° 或 270°",
                items = MainController.ROT_LABELS, selectedIndex = ctrl.selRot.coerceIn(0, 3),
                onSelectedIndexChange = { ctrl.selectRotation(it) },
            )
        }
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

    SectionHeader("DeX 桌面与窗口偏好")
    DexDesktopBehaviorCard(ctrl)

    if (!ctrl.daemonStatus.isLocalDexActive) {
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
fun DexDesktopBehaviorCard(ctrl: MainController) {
    val ctx = LocalContext.current
    val minDp = DisplayGeometry.UI_MIN_SINGLE_WIDTH_DP
    val maxDp = DisplayGeometry.UI_MAX_SINGLE_WIDTH_DP
    val clampedDp = DisplayGeometry.clampUiSingleWidthDp(ctrl.defaultSingleWidthDp)
    val presets = remember { listOf(420 to "420dp 手机", 680 to "680dp 折叠", 900 to "900dp 平板") }

    Card {
        OverlayDropdownPreference(
            title = "默认窗口启动模式",
            summary = "控制从任务栏、桌面或应用抽屉打开新应用时的初始形态",
            items = MainController.LAUNCH_MODE_LABELS,
            selectedIndex = ctrl.dexDefaultLaunchMode.coerceIn(0, MainController.LAUNCH_MODE_LABELS.lastIndex),
            onSelectedIndexChange = { ctrl.selectDexDefaultLaunchMode(it) },
        )
        SectionDivider(16.dp)
        SwitchPreference(
            title = "底部 DeX 任务栏自动隐藏",
            summary = "打开应用时自动收起底部任务栏释放空间，鼠标滑至底边瞬时唤醒",
            checked = ctrl.dockAutoHide,
            onCheckedChange = { ctrl.dockAutoHide = it; BridgeService.setDockAutoHideEnabled(ctx, it) },
        )
        SectionDivider(16.dp)
        SwitchPreference(
            title = "工作台模式（应用实时卡片栏）",
            summary = "在左侧或顶部常驻展示已添加应用的实时预览卡片，随时查看状态并一键切换",
            checked = ctrl.workbenchEnabled,
            onCheckedChange = {
                ctrl.workbenchEnabled = it
                BridgeService.setWorkbenchEnabledFromUi(ctx, it)
            },
        )
        if (ctrl.workbenchEnabled) {
            SectionDivider(16.dp)
            OverlayDropdownPreference(
                title = "工作台卡片栏位置",
                summary = "选择左侧竖向卡片栏或顶部横向卡片栏（工作台内亦可一键切换）",
                items = listOf("左侧卡片栏（推荐）", "顶部卡片栏"),
                selectedIndex = ctrl.workbenchPosition.coerceIn(0, 1),
                onSelectedIndexChange = { pos ->
                    ctrl.workbenchPosition = pos
                    BridgeService.setWorkbenchPositionFromUi(ctx, pos)
                },
            )
        }
        SectionDivider(16.dp)
        OverlayDropdownPreference(
            title = "桌面毛玻璃外观",
            summary = "实时同步外接屏标题栏、任务栏、应用抽屉与快捷中心的冰透/黑曜质感",
            items = ExtThemePrefs.THEME_LABELS,
            selectedIndex = ctrl.themeModeIdx.coerceIn(0, ExtThemePrefs.THEME_LABELS.lastIndex),
            onSelectedIndexChange = { ctrl.selectGlassThemeMode(it) },
        )
        SectionDivider(16.dp)
        SwitchPreference(
            title = "应用内鼠标右键透传原生菜单",
            summary = "开启后在应用内右键触发原生上下文菜单（窗口左上角 ‹ 按钮可随时返回）；关闭后右键模拟返回键",
            checked = ctrl.rightClickPassthrough,
            onCheckedChange = { ctrl.setRightClickPassthroughEnabled(it) },
        )
        SectionDivider(16.dp)
        SwitchPreference(
            title = "桌面图标鼠标双击打开",
            summary = "开启后鼠标单击选中图标、双击启动应用（触控屏幕仍保持单击启动）",
            checked = ctrl.desktopDoubleClickLaunch,
            onCheckedChange = { ctrl.setDesktopDoubleClickEnabled(it) },
        )
        SectionDivider(16.dp)
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("新开小窗默认宽度预设", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceContainer)
                StatusPillBadge(
                    label = BridgeLabels.classifyWidthPresetLabel(clampedDp, false),
                    dotColor = HyperBlue,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.forEach { (wDp, label) ->
                    val active = abs(clampedDp - wDp) <= 10
                    TextButton(
                        text = label,
                        onClick = { ctrl.applyAdaptiveSingleConfig(ctrl.dexDefaultLaunchMode != 2, wDp, debounce = false, showToast = true) },
                        colors = if (active) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                        insideMargin = PaddingValues(horizontal = 6.dp, vertical = 7.dp), modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        SectionDivider(16.dp)
        SliderPreference(
            title = "自定义默认窗口宽度",
            summary = if (ctrl.daemonStatus.connected) "拖动实时同步守护进程（$minDp–${maxDp}dp，双击窗口标题栏可一键最大化/还原）" else "本地已保存偏好，激活守护进程后自动应用",
            value = clampedDp.toFloat(), valueRange = minDp.toFloat()..maxDp.toFloat(), valueText = "$clampedDp dp",
            showKeyPoints = true, keyPoints = listOf(420f, 680f, 900f),
            onValueChange = { raw ->
                val snapped = ((raw / 10f).roundToInt() * 10).coerceIn(minDp, maxDp)
                ctrl.applyAdaptiveSingleConfig(ctrl.dexDefaultLaunchMode != 2, snapped, debounce = true, showToast = false)
            },
        )
    }
}

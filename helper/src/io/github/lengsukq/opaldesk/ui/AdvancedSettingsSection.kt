package io.github.lengsukq.opaldesk.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.compositor.AppCatalogRepository
import io.github.lengsukq.opaldesk.compositor.InstalledAppEntry
import io.github.lengsukq.opaldesk.prefs.ProfileConfigStore
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import kotlin.math.roundToInt

/**
 * Advanced Settings section in Console "设置" tab:
 * - 5 persistent Android-side workspace configuration slots (load any slot for local / external
 *   monitor / casting / receiving device setups; runtime tweaks apply live without auto-overwriting
 *   saved slots until the user chooses a target slot on exit)
 * - Global custom DPI (Auto or exact 120–560 DPI)
 * - Per-app custom DPI overrides (with live hot-apply and searchable app picker)
 * - Layout restore & exit save defaults
 */
@Composable
fun ConsoleAdvancedSettingsSection(
    status: DaemonStatus,
    scene: CompositorScene,
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        ProfileConfigStore.hydrate(ctx)
        BridgeClient.bindAppContext(ctx)
    }

    val storeState by ProfileConfigStore.stateFlow.collectAsState()
    val working = storeState.workingConfig
    val activeSlot = storeState.activeSavedSlot

    var editingProfileName by remember(activeSlot.id, activeSlot.name) { mutableStateOf(activeSlot.name) }
    var showRenameRow by remember(activeSlot.id) { mutableStateOf(false) }
    var customDpiInput by remember(working.globalCustomDpi) {
        mutableStateOf(if (working.globalCustomDpi > 0) working.globalCustomDpi.toString() else "240")
    }
    var showAddAppPicker by remember { mutableStateOf(false) }
    var appSearchQuery by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }

    LaunchedEffect(showAddAppPicker, working.appCustomDpis.size) {
        if (installedApps.isEmpty() && (showAddAppPicker || working.appCustomDpis.isNotEmpty())) {
            installedApps = AppCatalogRepository.loadLaunchableApps(ctx)
        }
    }

    val appLabelByPkg = remember(installedApps) {
        installedApps.associate { it.pkg to it.label }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Card A: 5 Persistent Configuration Slots
        MacGlassSectionCard(accentBorder = OpalPalette.AccentBlue.copy(alpha = 0.45f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OpalText(text = "高级配置槽位（5 套独立持久化）", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    OpalText(
                        text = "分别应对本机、外接屏及不同投屏/被投屏端；运行中修改实时生效，主动退出时再统一选择保存到目标槽位",
                        fontSize = 12.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OpalStatusBadge(
                    label = if (storeState.hasUnsavedChanges) "槽位 ${storeState.activeSlotId + 1} · 未保存" else "槽位 ${storeState.activeSlotId + 1}/5",
                    color = if (storeState.hasUnsavedChanges) OpalPalette.AccentAmber else OpalPalette.AccentBlue,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                storeState.slots.forEach { slot ->
                    val selected = slot.id == storeState.activeSlotId
                    val shape = RoundedCornerShape(13.dp)
                    Column(
                        modifier = Modifier
                            .clip(shape)
                            .background(if (selected) OpalPalette.AccentBlue.copy(alpha = 0.18f) else OpalPalette.ChipFill)
                            .border(
                                width = if (selected) 1.2.dp else 0.75.dp,
                                color = if (selected) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                                shape = shape,
                            )
                            .clickable {
                                val loaded = ProfileConfigStore.loadSlot(ctx, slot.id)
                                BridgeClient.syncProfileToDaemonAsync(loaded) {
                                    Toast.makeText(ctx, "已载入槽位 ${slot.id + 1}「${loaded.name}」", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        OpalText(
                            text = "${slot.id + 1}. ${slot.name}",
                            fontSize = 12.5.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                            maxLines = 1,
                        )
                        val dpiBadge = if (slot.globalCustomDpi > 0) "${slot.globalCustomDpi} DPI" else "自动 DPI"
                        OpalText(
                            text = "$dpiBadge · ${slot.appCustomDpis.size} 应用 · ${slot.savedLayout.size} 窗",
                            fontSize = 10.sp,
                            color = OpalPalette.TextMuted,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OpalSwitchRow(
                title = "按被投屏端自动匹配槽位（默认开启）",
                subtitle = "进入本机、外接屏或不同投屏设备时自动载入已绑定或默认槽位；退出保存到新槽位会自动记住该设备",
                checked = storeState.autoMatchTargetSlot,
                onCheckedChange = { ProfileConfigStore.setAutoMatchTargetSlot(ctx, it) },
            )

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(
                    text = "当前载入：${activeSlot.name}（已绑 ${activeSlot.boundTargets.size} 台设备 · 记住 ${working.appWindowBounds.size} 窗位置）",
                    fontSize = 11.5.sp,
                    color = OpalPalette.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                OpalButton(
                    label = if (showRenameRow) "收起" else "重命名",
                    compact = true,
                    filled = false,
                    onClick = { showRenameRow = !showRenameRow },
                )
            }

            if (showRenameRow) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OpalTextInput(
                        value = editingProfileName,
                        onValueChange = { editingProfileName = it },
                        placeholder = "输入槽位名称（如：客厅 4K 屏 / Mac 投屏）",
                        modifier = Modifier.weight(1f),
                    )
                    OpalButton(
                        label = "保存名称",
                        compact = true,
                        onClick = {
                            ProfileConfigStore.renameSlot(ctx, activeSlot.id, editingProfileName)
                            showRenameRow = false
                            Toast.makeText(ctx, "已更新槽位名称", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }

        // Card B: Global Custom DPI & Per-App Custom DPI
        MacGlassSectionCard {
            OpalText(text = "自定义 DPI（全局与单应用独立覆盖）", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            OpalText(
                text = "DPI 越高界面元素越大，DPI 越低视野越宽广（120–560）；修改后实时热刷新，退出时可选择保存到指定槽位",
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
            )
            Spacer(Modifier.height(10.dp))

            val isCustomGlobal = working.globalCustomDpi > 0
            OpalSegmentedRow(
                options = listOf("自动适配 (推荐)", "自定义全局 DPI"),
                selectedIndex = if (isCustomGlobal) 1 else 0,
                onSelect = { idx ->
                    if (idx == 0) {
                        BridgeClient.setGlobalCustomDpiAsync(ctx, 0)
                    } else {
                        val parsed = customDpiInput.toIntOrNull()?.coerceIn(ProfileConfigStore.MIN_DPI, ProfileConfigStore.MAX_DPI) ?: 240
                        customDpiInput = parsed.toString()
                        BridgeClient.setGlobalCustomDpiAsync(ctx, parsed)
                    }
                },
            )

            if (isCustomGlobal) {
                var draftGlobalDpi by remember(working.globalCustomDpi) { mutableIntStateOf(working.globalCustomDpi) }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    OpalText(text = "全局固定 DPI 数值（松手生效）", fontSize = 12.sp, color = OpalPalette.TextSecondary)
                    OpalText(
                        text = "$draftGlobalDpi DPI",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = OpalPalette.AccentBlue,
                    )
                }
                Spacer(Modifier.height(4.dp))
                OpalGlassSlider(
                    value = draftGlobalDpi.toFloat(),
                    valueRange = ProfileConfigStore.MIN_DPI.toFloat()..ProfileConfigStore.MAX_DPI.toFloat(),
                    onValueChange = { v ->
                        val stepped = ((v / 10f).roundToInt() * 10).coerceIn(ProfileConfigStore.MIN_DPI, ProfileConfigStore.MAX_DPI)
                        draftGlobalDpi = stepped
                        customDpiInput = stepped.toString()
                    },
                    onValueChangeFinished = {
                        if (draftGlobalDpi != working.globalCustomDpi) {
                            BridgeClient.setGlobalCustomDpiAsync(ctx, draftGlobalDpi)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(160, 200, 240, 320, 400).forEach { preset ->
                        OpalButton(
                            label = "$preset",
                            compact = true,
                            filled = working.globalCustomDpi == preset,
                            accent = if (working.globalCustomDpi == preset) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                            onClick = {
                                customDpiInput = preset.toString()
                                BridgeClient.setGlobalCustomDpiAsync(ctx, preset)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OpalTextInput(
                        value = customDpiInput,
                        onValueChange = { txt -> customDpiInput = txt.filter { it.isDigit() }.take(3) },
                        placeholder = "精确输入 120–560",
                        modifier = Modifier.weight(1f),
                    )
                    OpalButton(
                        label = "应用 DPI",
                        compact = true,
                        onClick = {
                            val parsed = customDpiInput.toIntOrNull()
                            if (parsed == null || parsed !in ProfileConfigStore.MIN_DPI..ProfileConfigStore.MAX_DPI) {
                                Toast.makeText(ctx, "请输入 120 到 560 之间的 DPI 数值", Toast.LENGTH_SHORT).show()
                            } else {
                                BridgeClient.setGlobalCustomDpiAsync(ctx, parsed)
                                Toast.makeText(ctx, "全局 DPI 已设为 $parsed", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Box(modifier = Modifier.fillMaxWidth().height(0.8.dp).background(OpalPalette.DividerColor))
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OpalText(
                        text = "单应用自定义 DPI (${working.appCustomDpis.size})",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OpalText(
                        text = "优先级高于全局 DPI，也可在桌面右键任意应用图标随时调整",
                        fontSize = 11.5.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OpalButton(
                    label = if (showAddAppPicker) "收起列表" else "+ 添加应用",
                    compact = true,
                    onClick = { showAddAppPicker = !showAddAppPicker },
                )
            }

            if (working.appCustomDpis.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    working.appCustomDpis.entries.forEach { (pkg, dpi) ->
                        val label = appLabelByPkg[pkg] ?: pkg
                        val icon = remember(pkg) { AppCatalogRepository.getAppIcon(ctx, pkg) }
                        var draftAppDpi by remember(pkg, dpi) { mutableIntStateOf(dpi) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(OpalPalette.ChipFill)
                                .border(0.75.dp, OpalPalette.BorderSubtle, RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    if (icon != null) {
                                        Image(
                                            bitmap = icon,
                                            contentDescription = label,
                                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)),
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        OpalText(text = label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        OpalText(text = pkg, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = OpalPalette.TextMuted, maxLines = 1)
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    OpalText(
                                        text = "$draftAppDpi DPI",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = OpalPalette.AccentBlue,
                                    )
                                    OpalButton(
                                        label = "恢复默认",
                                        compact = true,
                                        filled = false,
                                        accent = OpalPalette.AccentDanger,
                                        onClick = { BridgeClient.setAppCustomDpiAsync(ctx, pkg, 0) },
                                    )
                                }
                            }
                            OpalGlassSlider(
                                value = draftAppDpi.toFloat(),
                                valueRange = ProfileConfigStore.MIN_DPI.toFloat()..ProfileConfigStore.MAX_DPI.toFloat(),
                                onValueChange = { v ->
                                    draftAppDpi = ((v / 10f).roundToInt() * 10).coerceIn(ProfileConfigStore.MIN_DPI, ProfileConfigStore.MAX_DPI)
                                },
                                onValueChangeFinished = {
                                    if (draftAppDpi != dpi) {
                                        BridgeClient.setAppCustomDpiAsync(ctx, pkg, draftAppDpi)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            if (showAddAppPicker) {
                Spacer(Modifier.height(10.dp))
                OpalTextInput(
                    value = appSearchQuery,
                    onValueChange = { appSearchQuery = it },
                    placeholder = "搜索已安装应用名称或包名…",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val q = appSearchQuery.trim().lowercase()
                val filtered = remember(installedApps, q, working.appCustomDpis) {
                    installedApps
                        .filter { !working.appCustomDpis.containsKey(it.pkg) }
                        .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
                        .take(24)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    filtered.forEach { app ->
                        val icon = remember(app.pkg) { AppCatalogRepository.getAppIcon(ctx, app.pkg) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(11.dp))
                                .background(OpalPalette.ChipFill)
                                .clickable {
                                    val defaultDpi = if (working.globalCustomDpi > 0) working.globalCustomDpi else 260
                                    BridgeClient.setAppCustomDpiAsync(ctx, app.pkg, defaultDpi)
                                    showAddAppPicker = false
                                    appSearchQuery = ""
                                    Toast.makeText(ctx, "已为「${app.label}」应用自定义 DPI ($defaultDpi)", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (icon != null) {
                                Image(
                                    bitmap = icon,
                                    contentDescription = app.label,
                                    modifier = Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                OpalText(text = app.label, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                OpalText(text = app.pkg, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = OpalPalette.TextMuted, maxLines = 1)
                            }
                            OpalText(text = "添加", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = OpalPalette.AccentBlue)
                        }
                    }
                }
            }
        }

        // Card C: Layout Restore & Window Memory Policy
        MacGlassSectionCard {
            OpalText(text = "布局恢复与窗口记忆", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            OpalText(
                text = "每个应用关闭时自动记住窗口大小与位置；主动退出 DeX 或应用时可选择将当前配置与布局保存到 1–5 号任意槽位",
                fontSize = 12.sp,
                color = OpalPalette.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))

            OpalSwitchRow(
                title = "退出弹窗默认勾选「保留当前窗口布局」",
                subtitle = "退出时可自由切换是否保存及选择目标槽位",
                checked = working.keepLayoutOnExit,
                onCheckedChange = { ProfileConfigStore.setWorkingKeepLayoutOnExit(it) },
            )
            OpalSwitchRow(
                title = "进入桌面时自动恢复当前槽位已存布局",
                subtitle = "当前无打开窗口时，进入桌面自动还原已保存的窗口排布",
                checked = working.autoRestoreLayout,
                onCheckedChange = { ProfileConfigStore.setWorkingAutoRestoreLayout(it) },
            )

            if (activeSlot.savedLayout.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                OpalButton(
                    label = "立即恢复槽位 ${activeSlot.id + 1} 已存布局 (${activeSlot.savedLayout.size}窗)",
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentEmerald,
                    enabled = status.connected,
                    onClick = {
                        BridgeClient.restoreSavedLayoutAsync(activeSlot, status.canvasWidth, status.canvasHeight) { count ->
                            Toast.makeText(ctx, "已恢复 $count 个窗口", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Compact horizontal 1–5 configuration slot quick-switcher strip shared by the Home Dashboard
 * and Desktop Control Center.
 */
@Composable
fun QuickSlotSwitcherStrip(
    compact: Boolean = false,
    showRestoreButton: Boolean = false,
    canvasW: Int = 1920,
    canvasH: Int = 1200,
    onSlotSelected: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val storeState by ProfileConfigStore.stateFlow.collectAsState()
    val activeSlot = storeState.activeSavedSlot

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OpalText(
                    text = "配置槽位",
                    fontSize = if (compact) 11.5.sp else 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextSecondary,
                )
                OpalText(
                    text = if (storeState.autoMatchTargetSlot) "自动匹配已开" else "手动模式",
                    fontSize = 10.sp,
                    color = if (storeState.autoMatchTargetSlot) OpalPalette.AccentEmerald else OpalPalette.TextMuted,
                )
            }
            if (showRestoreButton && activeSlot.savedLayout.isNotEmpty()) {
                OpalText(
                    text = "恢复布局 (${activeSlot.savedLayout.size}窗)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.AccentBlue,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            BridgeClient.restoreSavedLayoutAsync(activeSlot, canvasW, canvasH) { count ->
                                Toast.makeText(ctx, "已恢复 $count 个窗口", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            storeState.slots.forEach { slot ->
                val selected = slot.id == storeState.activeSlotId
                val pillShape = RoundedCornerShape(10.dp)
                Box(
                    modifier = Modifier
                        .clip(pillShape)
                        .background(if (selected) OpalPalette.AccentBlue.copy(alpha = 0.18f) else OpalPalette.ChipFill)
                        .border(
                            width = if (selected) 1.1.dp else 0.7.dp,
                            color = if (selected) OpalPalette.AccentBlue else OpalPalette.BorderHairline,
                            shape = pillShape,
                        )
                        .clickable {
                            val loaded = ProfileConfigStore.loadSlot(ctx, slot.id)
                            BridgeClient.syncProfileToDaemonAsync(loaded) {
                                Toast.makeText(ctx, "已切换至槽位 ${slot.id + 1}「${loaded.name}」", Toast.LENGTH_SHORT).show()
                            }
                            onSlotSelected?.invoke(slot.id)
                        }
                        .padding(horizontal = if (compact) 9.dp else 10.dp, vertical = if (compact) 5.dp else 6.dp),
                ) {
                    OpalText(
                        text = "${slot.id + 1}. ${slot.name}",
                        fontSize = if (compact) 11.sp else 11.5.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Unified Exit & Save-to-Slot confirmation modal used both when exiting Desktop ("退出 DeX")
 * and when completely exiting OpalDesk ("完全退出 OpalDesk").
 * Lets the user choose whether to save the current working config & layout and which of the 5 slots
 * (1..5) to save into, without forcing saves on every minor tweak during use.
 */
@Composable
fun ExitSaveSlotModal(
    title: String,
    subtitle: String,
    confirmLabel: String,
    openWindowCount: Int,
    busy: Boolean = false,
    onDismiss: () -> Unit,
    onExitWithoutSaving: () -> Unit,
    onSaveToSlotAndExit: (slotId: Int, saveLayout: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val storeState by ProfileConfigStore.stateFlow.collectAsState()
    var selectedSlotId by remember { mutableIntStateOf(storeState.activeSlotId) }
    var includeLayout by remember { mutableStateOf(storeState.workingConfig.keepLayoutOnExit) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OpalPalette.ScrimColor)
            .clickable(enabled = !busy, onClick = onDismiss)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        OpalGlassCard(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures {} },
            cornerRadius = 24.dp,
            fillColor = OpalPalette.GlassPopover,
            contentPadding = PaddingValues(20.dp),
        ) {
            OpalText(text = title, fontSize = 17.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            OpalText(
                text = subtitle,
                fontSize = 12.5.sp,
                color = OpalPalette.TextSecondary,
                lineHeight = 18.sp,
            )

            Spacer(Modifier.height(14.dp))
            OpalText(
                text = "选择要保存到的配置槽位（保存当前 DPI、应用窗口位置与设置）：",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                storeState.slots.forEach { slot ->
                    val selected = slot.id == selectedSlotId
                    val rowShape = RoundedCornerShape(12.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(rowShape)
                            .background(if (selected) OpalPalette.AccentBlue.copy(alpha = 0.16f) else OpalPalette.ChipFill)
                            .border(
                                width = if (selected) 1.2.dp else 0.7.dp,
                                color = if (selected) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                                shape = rowShape,
                            )
                            .clickable(enabled = !busy) { selectedSlotId = slot.id }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            OpalText(
                                text = "槽位 ${slot.id + 1} · ${slot.name}",
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) OpalPalette.AccentBlue else OpalPalette.TextPrimary,
                                maxLines = 1,
                            )
                            val dpiSummary = if (slot.globalCustomDpi > 0) "${slot.globalCustomDpi} DPI" else "自动 DPI"
                            OpalText(
                                text = "已存：$dpiSummary · ${slot.appCustomDpis.size} 个应用 DPI · ${slot.savedLayout.size} 窗布局",
                                fontSize = 10.5.sp,
                                color = OpalPalette.TextMuted,
                                maxLines = 1,
                            )
                        }
                        if (selected) {
                            OpalStatusBadge(label = "保存到此", color = OpalPalette.AccentBlue)
                        }
                    }
                }
            }

            if (openWindowCount > 0) {
                Spacer(Modifier.height(10.dp))
                OpalSwitchRow(
                    title = "同时保留当前窗口布局（$openWindowCount 个窗口）",
                    subtitle = "记录所有已打开窗口的位置、大小、贴边与最小化状态",
                    checked = includeLayout,
                    onCheckedChange = { includeLayout = it },
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OpalButton(
                    label = "取消",
                    compact = true,
                    filled = false,
                    enabled = !busy,
                    onClick = onDismiss,
                    modifier = Modifier.weight(0.75f),
                )
                OpalButton(
                    label = "不保存退出",
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentDanger,
                    enabled = !busy,
                    onClick = onExitWithoutSaving,
                    modifier = Modifier.weight(1f),
                )
                OpalButton(
                    label = if (busy) "正在保存…" else confirmLabel,
                    compact = true,
                    enabled = !busy,
                    onClick = { onSaveToSlotAndExit(selectedSlotId, includeLayout) },
                    modifier = Modifier.weight(1.3f),
                )
            }
        }
    }
}

/**
 * Compact modal for adjusting a single app's custom DPI directly from the Desktop Workspace
 * (App Context Menu). Applies live to the running session; saved permanently when user saves to a slot.
 */
@Composable
fun PerAppDpiEditorModal(
    pkg: String,
    appLabel: String,
    currentCustomDpi: Int,
    globalCustomDpi: Int,
    onApplyDpi: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dpiVal by remember(pkg, currentCustomDpi) {
        mutableStateOf(
            when {
                currentCustomDpi in ProfileConfigStore.MIN_DPI..ProfileConfigStore.MAX_DPI -> currentCustomDpi
                globalCustomDpi in ProfileConfigStore.MIN_DPI..ProfileConfigStore.MAX_DPI -> globalCustomDpi
                else -> 260
            },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OpalPalette.ScrimColor)
            .clickable(onClick = onDismiss)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        OpalGlassCard(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures {} },
            cornerRadius = 22.dp,
            fillColor = OpalPalette.GlassPopover,
            contentPadding = PaddingValues(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OpalText(text = "自定义应用 DPI", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    OpalText(text = "$appLabel · $pkg", fontSize = 11.sp, color = OpalPalette.TextSecondary, maxLines = 1)
                }
                OpalText(
                    text = "$dpiVal DPI",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = OpalPalette.AccentBlue,
                )
            }
            Spacer(Modifier.height(12.dp))
            OpalGlassSlider(
                value = dpiVal.toFloat(),
                valueRange = ProfileConfigStore.MIN_DPI.toFloat()..ProfileConfigStore.MAX_DPI.toFloat(),
                onValueChange = { v ->
                    dpiVal = ((v / 10f).roundToInt() * 10).coerceIn(ProfileConfigStore.MIN_DPI, ProfileConfigStore.MAX_DPI)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(160, 200, 240, 320, 400).forEach { preset ->
                    OpalButton(
                        label = "$preset",
                        compact = true,
                        filled = dpiVal == preset,
                        accent = if (dpiVal == preset) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                        onClick = { dpiVal = preset },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpalButton(
                    label = "跟随全局",
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentAmber,
                    onClick = {
                        onApplyDpi(0)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
                OpalButton(
                    label = "取消",
                    compact = true,
                    filled = false,
                    onClick = onDismiss,
                    modifier = Modifier.weight(0.8f),
                )
                OpalButton(
                    label = "立即应用",
                    compact = true,
                    onClick = {
                        onApplyDpi(dpiVal)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1.2f),
                )
            }
        }
    }
}

/**
 * In-DeX live-preview floating DPI panel (no opaque scrim so open windows remain visible while
 * adjusting Global DPI or Per-App DPI in real time). All edits mutate the active working profile
 * and are saved alongside the desktop layout into the user-selected slot (1..5) upon exit.
 */
@Composable
fun DexDpiSettingsPopover(
    openWindows: List<CompositorWindow>,
    allApps: List<InstalledAppEntry>,
    maxHeight: Dp = 600.dp,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val storeState by ProfileConfigStore.stateFlow.collectAsState()
    val working = storeState.workingConfig
    val activeSlot = storeState.activeSavedSlot

    var showOtherApps by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val appLabelByPkg = remember(allApps) { allApps.associate { it.pkg to it.label } }
    val openByPkg = remember(openWindows) { openWindows.associateBy { it.pkg } }

    val shape = RoundedCornerShape(24.dp)
    val cardShape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier
            .widthIn(max = 384.dp)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 28.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OpalText(text = "实时 DPI 设置", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    OpalStatusBadge(
                        label = if (storeState.hasUnsavedChanges) "槽位 ${activeSlot.id + 1} · 未保存" else "槽位 ${activeSlot.id + 1}",
                        color = if (storeState.hasUnsavedChanges) OpalPalette.AccentAmber else OpalPalette.AccentBlue,
                    )
                }
                Spacer(Modifier.height(2.dp))
                OpalText(
                    text = "拖动滑块即可实时看到窗口变化，退出时随布局保存到选定槽位",
                    fontSize = 10.5.sp,
                    color = OpalPalette.TextSecondary,
                )
            }
            Spacer(Modifier.width(8.dp))
            OpalButton(label = "完成", compact = true, filled = false, onClick = onDismiss)
        }

        // Section 1: Global Custom DPI (commit once on release to prevent VirtualDisplay.resize storms)
        val isCustomGlobal = working.globalCustomDpi > 0
        var draftGlobalDpi by remember(working.globalCustomDpi) { mutableIntStateOf(working.globalCustomDpi.coerceAtLeast(ProfileConfigStore.MIN_DPI)) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(OpalPalette.ChipFill)
                .border(0.75.dp, OpalPalette.BorderHairline, cardShape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(text = "全局自定义 DPI（松手生效）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                OpalText(
                    text = if (isCustomGlobal) "$draftGlobalDpi DPI" else "自动适配",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = OpalPalette.AccentBlue,
                )
            }
            OpalSegmentedRow(
                options = listOf("自动适配", "自定义全局 DPI"),
                selectedIndex = if (isCustomGlobal) 1 else 0,
                onSelect = { idx ->
                    if (idx == 0) {
                        BridgeClient.setGlobalCustomDpiAsync(ctx, 0)
                    } else {
                        val initDpi = if (working.globalCustomDpi > 0) working.globalCustomDpi else 240
                        draftGlobalDpi = initDpi
                        BridgeClient.setGlobalCustomDpiAsync(ctx, initDpi)
                    }
                },
            )
            if (isCustomGlobal) {
                OpalGlassSlider(
                    value = draftGlobalDpi.toFloat(),
                    valueRange = ProfileConfigStore.MIN_DPI.toFloat()..ProfileConfigStore.MAX_DPI.toFloat(),
                    onValueChange = { v ->
                        draftGlobalDpi = ((v / 10f).roundToInt() * 10).coerceIn(ProfileConfigStore.MIN_DPI, ProfileConfigStore.MAX_DPI)
                    },
                    onValueChangeFinished = {
                        if (draftGlobalDpi != working.globalCustomDpi) {
                            BridgeClient.setGlobalCustomDpiAsync(ctx, draftGlobalDpi)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(160, 200, 240, 320, 400).forEach { preset ->
                        OpalButton(
                            label = "$preset",
                            compact = true,
                            filled = draftGlobalDpi == preset,
                            accent = if (draftGlobalDpi == preset) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                            onClick = {
                                draftGlobalDpi = preset
                                BridgeClient.setGlobalCustomDpiAsync(ctx, preset)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // Section 2: Open Windows Per-App Custom DPI (live preview right on desktop)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpalText(
                    text = "当前窗口独立 DPI (${openWindows.size})",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextSecondary,
                )
                OpalText(
                    text = "已设 ${working.appCustomDpis.size} 个应用",
                    fontSize = 10.5.sp,
                    color = OpalPalette.AccentCyan,
                )
            }

            if (openWindows.isEmpty()) {
                OpalText(
                    text = "当前暂无打开的窗口，打开应用后可在此实时拖动滑块预览效果",
                    fontSize = 11.5.sp,
                    color = OpalPalette.TextMuted,
                )
            } else {
                openWindows.distinctBy { it.pkg }.forEach { win ->
                    val customDpi = working.appCustomDpis[win.pkg] ?: 0
                    val effectiveDpi = when {
                        customDpi > 0 -> customDpi
                        win.contentDpi in ProfileConfigStore.MIN_DPI..ProfileConfigStore.MAX_DPI -> win.contentDpi
                        working.globalCustomDpi > 0 -> working.globalCustomDpi
                        else -> 240
                    }
                    var draftWinDpi by remember(win.pkg, effectiveDpi) { mutableIntStateOf(effectiveDpi) }
                    val icon = remember(win.pkg) { AppCatalogRepository.getAppIcon(ctx, win.pkg) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .background(OpalPalette.ChipFill)
                            .border(
                                width = if (customDpi > 0) 1.dp else 0.7.dp,
                                color = if (customDpi > 0) OpalPalette.AccentBlue.copy(alpha = 0.55f) else OpalPalette.BorderHairline,
                                shape = cardShape,
                            )
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                if (icon != null) {
                                    Image(
                                        bitmap = icon,
                                        contentDescription = win.title,
                                        modifier = Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)),
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    OpalText(text = win.title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    OpalText(
                                        text = when {
                                            draftWinDpi != effectiveDpi -> "松手应用 · $draftWinDpi DPI"
                                            customDpi > 0 -> "独立覆盖 · $customDpi DPI"
                                            else -> "跟随全局 · 当前 ${win.contentDpi} DPI"
                                        },
                                        fontSize = 10.sp,
                                        color = if (customDpi > 0 || draftWinDpi != effectiveDpi) OpalPalette.AccentBlue else OpalPalette.TextMuted,
                                        maxLines = 1,
                                    )
                                }
                            }
                            if (customDpi > 0) {
                                OpalButton(
                                    label = "恢复默认",
                                    compact = true,
                                    filled = false,
                                    accent = OpalPalette.AccentAmber,
                                    onClick = { BridgeClient.setAppCustomDpiAsync(ctx, win.pkg, 0) },
                                )
                            }
                        }
                        OpalGlassSlider(
                            value = draftWinDpi.toFloat(),
                            valueRange = ProfileConfigStore.MIN_DPI.toFloat()..ProfileConfigStore.MAX_DPI.toFloat(),
                            onValueChange = { v ->
                                draftWinDpi = ((v / 10f).roundToInt() * 10).coerceIn(ProfileConfigStore.MIN_DPI, ProfileConfigStore.MAX_DPI)
                            },
                            onValueChangeFinished = {
                                if (draftWinDpi != customDpi) {
                                    BridgeClient.setAppCustomDpiAsync(ctx, win.pkg, draftWinDpi)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // Section 3: Other saved per-app overrides (not currently open) — directly adjustable without launching
        val closedOverrides = working.appCustomDpis.entries.filter { !openByPkg.containsKey(it.key) }
        if (closedOverrides.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OpalText(
                    text = "本方案其他已设应用 DPI (${closedOverrides.size})",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextSecondary,
                )
                closedOverrides.forEach { (pkg, dpi) ->
                    val label = appLabelByPkg[pkg] ?: pkg
                    val icon = remember(pkg) { AppCatalogRepository.getAppIcon(ctx, pkg) }
                    var draftClosedDpi by remember(pkg, dpi) { mutableIntStateOf(dpi) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .background(OpalPalette.ChipFill)
                            .border(0.8.dp, OpalPalette.AccentBlue.copy(alpha = 0.42f), cardShape)
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                if (icon != null) {
                                    Image(
                                        bitmap = icon,
                                        contentDescription = label,
                                        modifier = Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)),
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    OpalText(text = label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    OpalText(
                                        text = if (draftClosedDpi != dpi) "松手应用 · $draftClosedDpi DPI" else "独立覆盖 · $draftClosedDpi DPI",
                                        fontSize = 10.sp,
                                        color = OpalPalette.AccentBlue,
                                        maxLines = 1,
                                    )
                                }
                            }
                            OpalButton(
                                label = "移除",
                                compact = true,
                                filled = false,
                                accent = OpalPalette.AccentDanger,
                                onClick = { BridgeClient.setAppCustomDpiAsync(ctx, pkg, 0) },
                            )
                        }
                        OpalGlassSlider(
                            value = draftClosedDpi.toFloat(),
                            valueRange = ProfileConfigStore.MIN_DPI.toFloat()..ProfileConfigStore.MAX_DPI.toFloat(),
                            onValueChange = { v ->
                                draftClosedDpi = ((v / 10f).roundToInt() * 10).coerceIn(ProfileConfigStore.MIN_DPI, ProfileConfigStore.MAX_DPI)
                            },
                            onValueChangeFinished = {
                                if (draftClosedDpi != dpi) {
                                    BridgeClient.setAppCustomDpiAsync(ctx, pkg, draftClosedDpi)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf(160, 240, 320, 400).forEach { preset ->
                                OpalButton(
                                    label = "$preset",
                                    compact = true,
                                    filled = draftClosedDpi == preset,
                                    accent = if (draftClosedDpi == preset) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
                                    onClick = {
                                        draftClosedDpi = preset
                                        BridgeClient.setAppCustomDpiAsync(ctx, pkg, preset)
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(
                text = "未打开的应用也可预先设置",
                fontSize = 11.sp,
                color = OpalPalette.TextMuted,
            )
            OpalButton(
                label = if (showOtherApps) "收起列表" else "+ 选择其他应用",
                compact = true,
                filled = false,
                onClick = { showOtherApps = !showOtherApps },
            )
        }

        if (showOtherApps) {
            OpalTextInput(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "搜索应用名称或包名…",
                modifier = Modifier.fillMaxWidth(),
            )
            val q = searchQuery.trim().lowercase()
            val candidates = remember(allApps, q, working.appCustomDpis, openByPkg) {
                allApps
                    .filter { !working.appCustomDpis.containsKey(it.pkg) && !openByPkg.containsKey(it.pkg) }
                    .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
                    .take(18)
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                candidates.forEach { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(OpalPalette.ChipFill)
                            .clickable {
                                val initDpi = if (working.globalCustomDpi > 0) working.globalCustomDpi else 260
                                BridgeClient.setAppCustomDpiAsync(ctx, app.pkg, initDpi)
                                showOtherApps = false
                                searchQuery = ""
                                Toast.makeText(ctx, "已为「${app.label}」添加 $initDpi DPI（退出时随槽位保存）", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        OpalText(text = app.label, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        OpalText(text = "添加", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = OpalPalette.AccentBlue)
                    }
                }
            }
        }
    }
}

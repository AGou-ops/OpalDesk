package io.github.lengsukq.opaldesk.compositor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalSegmentedRow
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.OpalTextInput
import io.github.lengsukq.opaldesk.ui.TilingLayoutPreviewIcon
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import io.github.lengsukq.opaldesk.ui.onTapOrContextAction
import java.util.Locale

data class SystemTrayInfo(
    val clockText: String = "",
    val dateText: String = "",
    val batteryPct: Int = 100,
    val charging: Boolean = false,
    val wifiConnected: Boolean = true,
)

fun querySystemTrayStatus(ctx: Context, clockText: String, dateText: String): SystemTrayInfo {
    var pct = 100
    var charging = false
    try {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent != null) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            if (level >= 0) pct = ((level * 100f) / scale).toInt().coerceIn(0, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
    } catch (_: Throwable) {
    }

    var wifi = false
    try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
    } catch (_: Throwable) {
    }

    return SystemTrayInfo(
        clockText = clockText,
        dateText = dateText,
        batteryPct = pct,
        charging = charging,
        wifiConnected = wifi,
    )
}

/**
 * Refined 28dp macOS translucent Menu Bar at the top edge of the workspace.
 */
@Composable
fun MacDesktopMenuBar(
    focusedWindow: CompositorWindow?,
    activeVdCount: Int,
    maxVdCount: Int,
    isExternalMonitor: Boolean,
    externalMonitorSummary: String,
    showTilingMenu: Boolean,
    showControlCenter: Boolean,
    onToggleTilingMenu: () -> Unit,
    onToggleControlCenter: () -> Unit,
    onOpenConsole: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CompositorGeometry.TOP_BAR_DP.dp)
            .background(OpalPalette.MenuBarFill)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onOpenConsole)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.5.dp)
                    .clip(CircleShape)
                    .background(OpalPalette.AccentBlue),
            )
            Spacer(Modifier.width(6.dp))
            OpalText(
                text = "OpalDesk",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = OpalPalette.TextPrimary,
            )
        }

        Spacer(Modifier.width(12.dp))

        if (focusedWindow != null) {
            OpalText(
                text = focusedWindow.title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpalPalette.TextPrimary,
                maxLines = 1,
            )
            Spacer(Modifier.width(10.dp))
            OpalText(
                text = "${focusedWindow.contentWidth}×${focusedWindow.contentHeight} · ${focusedWindow.contentDpi}dpi",
                fontSize = 11.sp,
                color = OpalPalette.TextMuted,
                maxLines = 1,
            )
        } else {
            OpalText(
                text = if (isExternalMonitor) "外接桌面工作站" else "桌面工作站",
                fontSize = 11.5.sp,
                color = OpalPalette.TextSecondary,
                maxLines = 1,
            )
        }

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (showTilingMenu) OpalPalette.ChipActiveFill else Color.Transparent)
                .clickable(onClick = onToggleTilingMenu)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            OpalText(
                text = "分屏布局",
                fontSize = 11.sp,
                fontWeight = if (showTilingMenu) FontWeight.SemiBold else FontWeight.Medium,
                color = if (showTilingMenu) OpalPalette.AccentBlue else OpalPalette.TextSecondary,
            )
        }

        Spacer(Modifier.width(8.dp))

        if (isExternalMonitor && externalMonitorSummary.isNotEmpty()) {
            OpalText(
                text = externalMonitorSummary,
                fontSize = 11.sp,
                color = OpalPalette.AccentCyan,
                maxLines = 1,
            )
            Spacer(Modifier.width(10.dp))
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (showControlCenter) OpalPalette.ChipActiveFill else Color.Transparent)
                .clickable(onClick = onToggleControlCenter)
                .padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (activeVdCount > 0) OpalPalette.AccentEmerald else OpalPalette.TextMuted),
            )
            OpalText(
                text = "屏池 $activeVdCount/$maxVdCount",
                fontSize = 11.sp,
                color = OpalPalette.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

/**
 * Floating macOS Liquid Glass Dock (rounded large glass capsule with specular top rim & ambient shadow)
 * paired with a matching floating bottom-right Status Capsule, preserving Samsung DeX's intuitive
 * Launchpad + Running Apps + Back/Home/Recents + System Tray operation logic.
 */
@Composable
fun DexMacTaskbar(
    allApps: List<InstalledAppEntry>,
    dockPinnedPkgs: List<String>,
    windows: List<CompositorWindow>,
    tray: SystemTrayInfo,
    showAppDrawer: Boolean,
    showExpose: Boolean,
    showControlCenter: Boolean,
    onToggleAppDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onToggleExpose: () -> Unit,
    onToggleControlCenter: () -> Unit,
    onSendBackToFocused: () -> Unit,
    onShowDesktopToggle: () -> Unit,
    onLaunchOrToggleApp: (String, String) -> Unit,
    onContextApp: (String, CompositorWindow?, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val taskbarEntries = remember(dockPinnedPkgs, windows, allApps) {
        val installedByPkg = allApps.associateBy { it.pkg }
        val combined = LinkedHashSet<String>()
        dockPinnedPkgs
            .filter { installedByPkg.containsKey(it) }
            .take(AppPrefs.MAX_DOCK_PINNED)
            .forEach { combined.add(it) }
        windows.forEach { combined.add(it.pkg) }
        if (combined.isEmpty()) {
            allApps.take(5).forEach { combined.add(it.pkg) }
        }
        combined.take(AppPrefs.MAX_DOCK_PINNED + 6).map { pkg ->
            val info = installedByPkg[pkg]
            Triple(pkg, info?.activityClass.orEmpty(), info?.label ?: pkg)
        }
    }

    val dockShape = RoundedCornerShape(22.dp)
    val trayShape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
    ) {
        // Center Iconic Floating macOS Liquid Frosted Glass Dock
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 760.dp)
                .liquidGlassSurface(
                    shape = dockShape,
                    fill = OpalPalette.LiquidDockFill,
                    rim = OpalPalette.LiquidGlassRim,
                    elevation = 22.dp,
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // Left: Launchpad / App Drawer + Spotlight Search
            GlassDockButton(
                glyph = ChromeGlyph.APPS_GRID,
                active = showAppDrawer,
                size = 40.dp,
                onClick = onToggleAppDrawer,
            )
            GlassDockButton(
                glyph = ChromeGlyph.SEARCH,
                active = false,
                size = 38.dp,
                onClick = onOpenSearch,
            )

            GlassDockDivider()

            // Center: Pinned + Running Apps Strip (DeX click-to-toggle + long-press menu + macOS running dot)
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                taskbarEntries.forEach { (pkg, cls, label) ->
                    val icon = remember(pkg) { AppCatalogRepository.getAppIcon(ctx, pkg) }
                    val runningWin = windows.firstOrNull { it.pkg == pkg }
                    val isFocused = runningWin != null && runningWin.focused && !runningWin.isMinimized

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .size(width = 46.dp, height = 46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                when {
                                    isFocused -> OpalPalette.ChipActiveFill
                                    runningWin != null -> OpalPalette.ChipFill
                                    else -> Color.Transparent
                                },
                            )
                            .border(
                                width = if (isFocused) 0.85.dp else 0.dp,
                                color = if (isFocused) OpalPalette.BorderFocused else Color.Transparent,
                                shape = RoundedCornerShape(14.dp),
                            )
                            .onTapOrContextAction(
                                pkg, runningWin?.windowId, runningWin?.state, runningWin?.focused,
                                onPrimaryTap = { onLaunchOrToggleApp(pkg, cls) },
                                onContextAction = { rootPos -> onContextApp(pkg, runningWin, rootPos) },
                            ),
                    ) {
                        Box(
                            modifier = Modifier.size(34.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (icon != null) {
                                Image(
                                    bitmap = icon,
                                    contentDescription = label,
                                    alpha = if (runningWin?.isMinimized == true) 0.56f else 1f,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(9.dp)),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(OpalPalette.BgElevated),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    OpalText(text = label.take(1), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Spacer(Modifier.height(2.5.dp))
                        Box(
                            modifier = Modifier
                                .size(width = if (isFocused) 12.dp else 4.5.dp, height = 3.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        runningWin == null -> Color.Transparent
                                        runningWin.isMinimized -> OpalPalette.TextMuted
                                        runningWin.isHibernated -> OpalPalette.AccentAmber
                                        isFocused -> OpalPalette.AccentBlue
                                        else -> OpalPalette.AccentCyan
                                    },
                                ),
                        )
                    }
                }
            }

            GlassDockDivider()

            // Right of Dock: Compact Samsung DeX Navigation (Back / Show Desktop / Mission Control)
            GlassDockButton(
                glyph = ChromeGlyph.BACK,
                active = false,
                size = 34.dp,
                onClick = onSendBackToFocused,
            )
            GlassDockButton(
                glyph = ChromeGlyph.HOME_DESKTOP,
                active = false,
                size = 34.dp,
                onClick = onShowDesktopToggle,
            )
            GlassDockButton(
                glyph = ChromeGlyph.EXPOSE,
                active = showExpose,
                size = 34.dp,
                onClick = onToggleExpose,
            )
        }

        // Bottom-Right Floating macOS Liquid Frosted Glass Status Capsule (opens Control Center)
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .liquidGlassSurface(
                    shape = trayShape,
                    fill = OpalPalette.LiquidDockFill,
                    rim = if (showControlCenter) {
                        Brush.verticalGradient(listOf(OpalPalette.AccentBlue.copy(alpha = 0.85f), OpalPalette.AccentCyan.copy(alpha = 0.45f)))
                    } else {
                        OpalPalette.LiquidGlassRim
                    },
                    elevation = 18.dp,
                )
                .clickable(onClick = onToggleControlCenter)
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            OpalGlyphIcon(
                glyph = ChromeGlyph.WIFI,
                tint = if (tray.wifiConnected) OpalPalette.TextPrimary else OpalPalette.TextMuted,
                size = 14.dp,
            )
            OpalText(
                text = if (tray.charging) "⚡${tray.batteryPct}%" else "${tray.batteryPct}%",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = when {
                    tray.charging -> OpalPalette.AccentEmerald
                    tray.batteryPct <= 15 -> OpalPalette.AccentDanger
                    else -> OpalPalette.TextPrimary
                },
            )
            OpalGlyphIcon(
                glyph = ChromeGlyph.CONTROL_CENTER,
                tint = if (showControlCenter) OpalPalette.AccentCyan else OpalPalette.TextSecondary,
                size = 14.dp,
            )
            Column(horizontalAlignment = Alignment.End) {
                OpalText(
                    text = tray.clockText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OpalPalette.TextPrimary,
                )
                if (tray.dateText.isNotEmpty()) {
                    OpalText(
                        text = tray.dateText,
                        fontSize = 9.5.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassDockButton(
    glyph: ChromeGlyph,
    active: Boolean,
    size: Dp = 38.dp,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(if (active) OpalPalette.AccentBlue else OpalPalette.ChipFill)
            .border(
                width = 0.75.dp,
                color = if (active) OpalPalette.AccentBlue else OpalPalette.BorderHairline,
                shape = shape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        OpalGlyphIcon(
            glyph = glyph,
            tint = if (active) Color.White else OpalPalette.TextPrimary.copy(alpha = 0.86f),
            size = size * 0.46f,
        )
    }
}

@Composable
private fun GlassDockDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .width(1.dp)
            .height(26.dp)
            .background(OpalPalette.DividerColor),
    )
}

/**
 * Floating Rounded Liquid Glass App Launcher (macOS Launchpad × Samsung DeX Search & Profile Picker).
 */
@Composable
fun DexAppDrawerPopover(
    apps: List<InstalledAppEntry>,
    desktopPkgs: List<String>,
    dockPinnedPkgs: List<String>,
    onLaunch: (InstalledAppEntry, Int) -> Unit,
    onContextApp: (InstalledAppEntry, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selectedProfileIdx by remember { mutableIntStateOf(0) }

    val profileCode = when (selectedProfileIdx) {
        1 -> BridgeProtocol.Profile.DESKTOP
        2 -> BridgeProtocol.Profile.PORTRAIT
        3 -> BridgeProtocol.Profile.TABLET
        else -> BridgeProtocol.Profile.AUTO
    }

    val filtered = remember(apps, query) {
        if (query.isBlank()) {
            apps
        } else {
            val q = query.trim().lowercase(Locale.getDefault())
            apps.filter {
                it.label.lowercase(Locale.getDefault()).contains(q) ||
                    it.pkg.lowercase(Locale.ROOT).contains(q)
            }
        }
    }

    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .widthIn(min = 390.dp, max = 580.dp)
            .height(460.dp)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 28.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OpalTextInput(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索应用（点击启动 · 右键 / 长按添加到桌面或程序坞）",
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            OpalText(text = "${filtered.size}", fontSize = 12.sp, color = OpalPalette.TextSecondary)
        }
        Spacer(Modifier.height(10.dp))
        OpalSegmentedRow(
            options = listOf("自动记忆", "桌面横屏", "手机竖屏", "平板 4:3"),
            selectedIndex = selectedProfileIdx,
            onSelect = { selectedProfileIdx = it },
        )
        Spacer(Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 90.dp),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filtered, key = { it.pkg }) { app ->
                val icon = remember(app.pkg) { AppCatalogRepository.getAppIcon(ctx, app.pkg) }
                val onDesktop = desktopPkgs.contains(app.pkg)
                val onDock = dockPinnedPkgs.contains(app.pkg)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (onDesktop || onDock) OpalPalette.ChipFill else Color.Transparent)
                        .onTapOrContextAction(
                            app.pkg, profileCode,
                            onPrimaryTap = { onLaunch(app, profileCode) },
                            onContextAction = { rootPos -> onContextApp(app, rootPos) },
                        )
                        .padding(vertical = 8.dp, horizontal = 5.dp),
                ) {
                    if (icon != null) {
                        Image(
                            bitmap = icon,
                            contentDescription = app.label,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(OpalPalette.BgElevated),
                            contentAlignment = Alignment.Center,
                        ) {
                            OpalText(text = app.label.take(1), fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OpalText(
                        text = if (onDock || onDesktop) "★ ${app.label}" else app.label,
                        fontSize = 11.sp,
                        maxLines = 1,
                        color = OpalPalette.TextPrimary,
                    )
                }
            }
        }
    }
}

/**
 * Floating Liquid Frosted Glass Window Tiling Preset Popover anchored below the top Menu Bar.
 */
@Composable
fun WindowTilingPopover(
    windowCount: Int,
    onApplyLayout: (WindowTilingLayout) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    val layouts = listOf(
        WindowTilingLayout.SPLIT_2_COLS,
        WindowTilingLayout.SPLIT_2_MAIN_SIDE,
        WindowTilingLayout.SPLIT_3_COLS,
        WindowTilingLayout.SPLIT_3_MAIN_STACK,
        WindowTilingLayout.SPLIT_4_COLS,
        WindowTilingLayout.SPLIT_4_GRID,
        WindowTilingLayout.CASCADE,
    )
    Column(
        modifier = modifier
            .width(344.dp)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 26.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(text = "快速分屏布局", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            OpalText(
                text = if (windowCount > 0) "当前 $windowCount 窗可排列" else "打开应用后一键排列",
                fontSize = 11.sp,
                color = if (windowCount > 0) OpalPalette.AccentCyan else OpalPalette.TextMuted,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            layouts.chunked(3).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowItems.forEach { layout ->
                        TilingPresetTile(
                            layout = layout,
                            enabled = windowCount > 0,
                            onClick = { onApplyLayout(layout) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TilingPresetTile(
    layout: WindowTilingLayout,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileShape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(tileShape)
            .background(OpalPalette.ChipFill)
            .border(0.75.dp, OpalPalette.BorderHairline, tileShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TilingLayoutPreviewIcon(
            layout = layout,
            tint = if (enabled) OpalPalette.AccentBlue else OpalPalette.TextMuted,
            size = 22.dp,
        )
        OpalText(
            text = layout.shortLabel,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) OpalPalette.TextPrimary else OpalPalette.TextMuted,
            maxLines = 1,
        )
    }
}

/**
 * Floating Rounded Liquid Frosted Glass macOS Control Center Popover anchored above bottom-right Status Capsule.
 */
@Composable
fun MacControlCenterPopover(
    activeVdCount: Int,
    maxVdCount: Int,
    totalWindows: Int,
    inputBackendLabel: String,
    themeMode: Int,
    wallpaperPreset: Int,
    mouseTargetMode: Int,
    isExternalMonitor: Boolean,
    onSelectThemeMode: (Int) -> Unit,
    onSelectWallpaper: (Int) -> Unit,
    onPickCustomWallpaper: () -> Unit,
    onSelectMouseTarget: (Int) -> Unit,
    onApplyTilingLayout: (WindowTilingLayout) -> Unit,
    onMinimizeAll: () -> Unit,
    onCloseAll: () -> Unit,
    onOpenConsole: () -> Unit,
    onExitDesktop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = OpalPalette.isDark
    val wallpaperColors = if (isDark) {
        listOf(
            Pair("红杉深海", listOf(Color(0xFF142842), Color(0xFF2A9D8F))),
            Pair("索诺玛暮色", listOf(Color(0xFF2B2752), Color(0xFFE07A5F))),
            Pair("极地冰川", listOf(Color(0xFF0E293D), Color(0xFF38BDF8))),
            Pair("曜石暗夜", listOf(Color(0xFF1C1F26), Color(0xFF4B5563))),
        )
    } else {
        listOf(
            Pair("晴空云雾", listOf(Color(0xFFC8DEFB), Color(0xFF60A5FA))),
            Pair("晨曦薄暮", listOf(Color(0xFFFBDCE8), Color(0xFFF472B6))),
            Pair("冰川清露", listOf(Color(0xFFBFE7F4), Color(0xFF38BDF8))),
            Pair("素白霜华", listOf(Color(0xFFE9EEF6), Color(0xFFCBD5E1))),
        )
    }

    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .width(356.dp)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 28.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(text = "控制中心", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
            OpalText(
                text = "虚拟屏 $activeVdCount/$maxVdCount · $totalWindows 窗",
                fontSize = 11.sp,
                color = OpalPalette.AccentCyan,
            )
        }
        Spacer(Modifier.height(4.dp))
        OpalText(text = inputBackendLabel, fontSize = 11.5.sp, color = OpalPalette.TextSecondary)

        Spacer(Modifier.height(12.dp))
        OpalText(text = "液态毛玻璃外观", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = OpalPalette.TextSecondary)
        Spacer(Modifier.height(6.dp))
        OpalSegmentedRow(
            options = listOf("浅色", "跟随系统", "深色"),
            selectedIndex = themeMode.coerceIn(AppPrefs.THEME_LIGHT, AppPrefs.THEME_DARK),
            onSelect = onSelectThemeMode,
        )

        if (isExternalMonitor) {
            Spacer(Modifier.height(12.dp))
            OpalText(text = "物理鼠标输出位置", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = OpalPalette.TextSecondary)
            Spacer(Modifier.height(6.dp))
            OpalSegmentedRow(
                options = listOf("跟随本机", "外接投屏端"),
                selectedIndex = mouseTargetMode.coerceIn(AppPrefs.MOUSE_TARGET_LOCAL, AppPrefs.MOUSE_TARGET_EXTERNAL),
                onSelect = onSelectMouseTarget,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(text = "分屏布局", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = OpalPalette.TextSecondary)
            OpalText(
                text = "全部最小化",
                fontSize = 11.sp,
                color = OpalPalette.AccentBlue,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onMinimizeAll)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        val quickTiles = listOf(
            WindowTilingLayout.SPLIT_2_COLS,
            WindowTilingLayout.SPLIT_3_COLS,
            WindowTilingLayout.SPLIT_4_COLS,
            WindowTilingLayout.SPLIT_2_MAIN_SIDE,
            WindowTilingLayout.SPLIT_3_MAIN_STACK,
            WindowTilingLayout.SPLIT_4_GRID,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            quickTiles.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { layout ->
                        TilingPresetTile(
                            layout = layout,
                            enabled = totalWindows > 0,
                            onClick = { onApplyTilingLayout(layout) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpalText(text = "桌面壁纸", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = OpalPalette.TextSecondary)
            OpalText(
                text = "自定义相册壁纸…",
                fontSize = 11.sp,
                color = OpalPalette.AccentBlue,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onPickCustomWallpaper)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            wallpaperColors.forEachIndexed { idx, (name, grad) ->
                val selected = idx == wallpaperPreset
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectWallpaper(idx) }
                        .padding(vertical = 3.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Brush.linearGradient(grad))
                            .border(
                                width = if (selected) 1.6.dp else 0.7.dp,
                                color = if (selected) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                                shape = RoundedCornerShape(9.dp),
                            ),
                    )
                    Spacer(Modifier.height(3.dp))
                    OpalText(
                        text = name,
                        fontSize = 9.5.sp,
                        color = if (selected) OpalPalette.TextPrimary else OpalPalette.TextMuted,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OpalButton(
                label = "主控制台",
                compact = true,
                filled = false,
                onClick = onOpenConsole,
                modifier = Modifier.weight(1f),
            )
            if (totalWindows > 0) {
                OpalButton(
                    label = "关闭全部",
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentAmber,
                    onClick = onCloseAll,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!isExternalMonitor) {
                OpalButton(
                    label = "退出桌面",
                    compact = true,
                    filled = false,
                    accent = OpalPalette.AccentDanger,
                    onClick = onExitDesktop,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Floating Rounded Liquid Frosted Glass Context Menu when right-clicking or long-pressing
 * any app in the App Drawer, Desktop Shortcuts, or Bottom Dock.
 */
@Composable
fun TaskbarContextMenuPopover(
    pkg: String,
    label: String,
    window: CompositorWindow?,
    isOnDesktop: Boolean,
    isPinnedToDock: Boolean,
    dockPinnedCount: Int,
    onFocusOrLaunch: () -> Unit,
    onSnapLeft: () -> Unit,
    onSnapRight: () -> Unit,
    onMaximize: () -> Unit,
    onToggleDesktopShortcut: () -> Unit,
    onToggleDockPin: () -> Unit,
    onCloseWindow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    val dockFull = !isPinnedToDock && dockPinnedCount >= AppPrefs.MAX_DOCK_PINNED
    Column(
        modifier = modifier
            .width(244.dp)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 24.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(7.dp),
    ) {
        OpalText(
            text = label.ifBlank { pkg },
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = OpalPalette.TextSecondary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
        ContextMenuRow(if (window != null) "聚焦 / 恢复窗口" else "启动应用") {
            onFocusOrLaunch()
            onDismiss()
        }
        if (window != null) {
            ContextMenuRow("贴靠左半屏") {
                onSnapLeft()
                onDismiss()
            }
            ContextMenuRow("贴靠右半屏") {
                onSnapRight()
                onDismiss()
            }
            ContextMenuRow(if (window.isMaximized) "还原窗口大小" else "最大化窗口") {
                onMaximize()
                onDismiss()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 8.dp)
                .height(0.8.dp)
                .background(OpalPalette.DividerColor),
        )
        ContextMenuRow(if (isOnDesktop) "从桌面移除图标" else "添加到桌面") {
            onToggleDesktopShortcut()
            onDismiss()
        }
        ContextMenuRow(
            title = when {
                isPinnedToDock -> "从程序坞移除 ($dockPinnedCount/${AppPrefs.MAX_DOCK_PINNED})"
                dockFull -> "程序坞已满 (${AppPrefs.MAX_DOCK_PINNED}/${AppPrefs.MAX_DOCK_PINNED})"
                else -> "添加到底部程序坞 ($dockPinnedCount/${AppPrefs.MAX_DOCK_PINNED})"
            },
            textColor = if (dockFull) OpalPalette.TextMuted else OpalPalette.TextPrimary,
        ) {
            onToggleDockPin()
            onDismiss()
        }
        if (window != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp, horizontal = 8.dp)
                    .height(0.8.dp)
                    .background(OpalPalette.DividerColor),
            )
            ContextMenuRow("关闭窗口", textColor = OpalPalette.AccentDanger) {
                onCloseWindow()
                onDismiss()
            }
        }
    }
}

/**
 * macOS-style Desktop Wallpaper Right-Click / Long-Press Context Menu allowing free custom
 * gallery wallpaper selection, preset switching, quick tiling, and appearance toggling.
 */
@Composable
fun DesktopWallpaperContextMenu(
    wallpaperPreset: Int,
    hasCustomWallpaper: Boolean,
    onSelectPreset: (Int) -> Unit,
    onPickCustomWallpaper: () -> Unit,
    onClearCustomWallpaper: () -> Unit,
    onOpenTilingMenu: () -> Unit,
    onOpenAppDrawer: () -> Unit,
    onToggleLightDark: () -> Unit,
    onOpenConsole: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = OpalPalette.isDark
    val presets = if (isDark) {
        listOf(
            Color(0xFF142842) to Color(0xFF2A9D8F),
            Color(0xFF2B2752) to Color(0xFFE07A5F),
            Color(0xFF0E293D) to Color(0xFF38BDF8),
            Color(0xFF1C1F26) to Color(0xFF4B5563),
        )
    } else {
        listOf(
            Color(0xFFC8DEFB) to Color(0xFF60A5FA),
            Color(0xFFFBDCE8) to Color(0xFFF472B6),
            Color(0xFFBFE7F4) to Color(0xFF38BDF8),
            Color(0xFFE9EEF6) to Color(0xFFCBD5E1),
        )
    }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .width(248.dp)
            .liquidGlassSurface(
                shape = shape,
                fill = OpalPalette.LiquidPopoverFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 24.dp,
            )
            .pointerInput(Unit) { detectTapGestures {} }
            .padding(8.dp),
    ) {
        OpalText(
            text = "桌面与壁纸",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = OpalPalette.TextSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            presets.forEachIndexed { idx, (c1, c2) ->
                val selected = !hasCustomWallpaper && idx == wallpaperPreset
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.linearGradient(listOf(c1, c2)))
                        .border(
                            width = if (selected) 1.8.dp else 0.7.dp,
                            color = if (selected) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable {
                            onSelectPreset(idx)
                            onDismiss()
                        },
                )
            }
        }
        ContextMenuRow("自由更换桌面壁纸（从相册选择）…", textColor = OpalPalette.AccentBlue) {
            onDismiss()
            onPickCustomWallpaper()
        }
        if (hasCustomWallpaper) {
            ContextMenuRow("恢复默认预设壁纸") {
                onClearCustomWallpaper()
                onDismiss()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 8.dp)
                .height(0.8.dp)
                .background(OpalPalette.DividerColor),
        )
        ContextMenuRow("快速分屏布局（二 / 三 / 四列…）") {
            onDismiss()
            onOpenTilingMenu()
        }
        ContextMenuRow("打开应用抽屉") {
            onDismiss()
            onOpenAppDrawer()
        }
        ContextMenuRow(if (isDark) "切换为浅色液态毛玻璃" else "切换为深色液态毛玻璃") {
            onToggleLightDark()
            onDismiss()
        }
        ContextMenuRow("打开 OpalDesk 主控制台") {
            onDismiss()
            onOpenConsole()
        }
    }
}

@Composable
private fun ContextMenuRow(
    title: String,
    textColor: Color = OpalPalette.TextPrimary,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        OpalText(text = title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = textColor)
    }
}

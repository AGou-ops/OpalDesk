package io.github.lengsukq.opaldesk.compositor

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorScene
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.protocol.WindowRect
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.OpalAmbientBackdrop
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalGlassCard
import io.github.lengsukq.opaldesk.ui.OpalGlyphButton
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalStatusBadge
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import io.github.lengsukq.opaldesk.ui.onTapOrContextAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Single-source-of-truth multi-window tiling dispatcher shared by Top Menu Bar,
 * Control Center, Home Console Bento Grid, and physical keyboard shortcuts.
 */
fun applyWindowTilingLayout(
    layout: WindowTilingLayout,
    scene: CompositorScene,
    canvasW: Int,
    canvasH: Int,
    density: Float,
) {
    val candidates = scene.visibleWindows.sortedByDescending { it.zOrder }
        .ifEmpty { scene.windows.sortedByDescending { it.zOrder } }
    if (candidates.isEmpty()) return
    val frames = CompositorGeometry.computeTilingFrames(
        layout = layout,
        windowCount = candidates.size,
        canvasW = canvasW,
        canvasH = canvasH,
        density = density,
    )
    frames.forEachIndexed { idx, rect ->
        val win = candidates[idx]
        if (win.isMinimized) {
            BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.FOCUS)
        }
        BridgeClient.updateWindowFrameAsync(
            windowId = win.windowId,
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            presentation = BridgeProtocol.Presentation.FLOATING,
        )
    }
}

/**
 * Shared macOS-Styled + Samsung-DeX-Layout Multi-Window Compositor Workspace.
 *
 * Runs seamlessly inside [io.github.lengsukq.opaldesk.DesktopActivity] (on-device tablet/phone),
 * [io.github.lengsukq.opaldesk.ExternalDesktopActivity] (extended external display), and
 * [io.github.lengsukq.opaldesk.external.ExternalMonitorPresentation] (full-bleed native external monitor).
 */
@Composable
fun OpalCompositorWorkspace(
    isExternalMonitor: Boolean,
    externalMonitorSummary: String = "",
    virtualCursorPx: Offset? = null,
    onPickCustomWallpaper: () -> Unit = {},
    onOpenConsole: () -> Unit,
    onExitDesktop: () -> Unit,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val status by BridgeClient.statusFlow.collectAsState()
    val scene by BridgeClient.sceneFlow.collectAsState()

    var allApps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }
    val desktopPkgs by AppPrefs.desktopShortcutsFlow.collectAsState()
    val dockPinnedPkgs by AppPrefs.dockPinnedFlow.collectAsState()
    val wallpaperPreset by AppPrefs.wallpaperPresetFlow.collectAsState()
    val customWallpaperPath by AppPrefs.customWallpaperPathFlow.collectAsState()
    val themeMode by AppPrefs.themeModeFlow.collectAsState()
    val mouseTargetMode by AppPrefs.mouseTargetFlow.collectAsState()

    var showAppDrawer by remember { mutableStateOf(false) }
    var showExposeOverview by remember { mutableStateOf(false) }
    var showControlCenter by remember { mutableStateOf(false) }
    var showTilingMenu by remember { mutableStateOf(false) }
    var contextMenuTarget by remember { mutableStateOf<Triple<String, CompositorWindow?, Offset>?>(null) }
    var wallpaperMenuPos by remember { mutableStateOf<Offset?>(null) }
    var snapPreviewRect by remember { mutableStateOf<WindowRect?>(null) }
    var trayInfo by remember { mutableStateOf(SystemTrayInfo()) }

    fun dismissAllPopovers() {
        showAppDrawer = false
        showExposeOverview = false
        showControlCenter = false
        showTilingMenu = false
        contextMenuTarget = null
        wallpaperMenuPos = null
    }

    LaunchedEffect(Unit) {
        allApps = AppCatalogRepository.loadLaunchableApps(ctx)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFmt = SimpleDateFormat("M/d E", Locale.getDefault())
        while (true) {
            val now = Date()
            trayInfo = querySystemTrayStatus(ctx, timeFmt.format(now), dateFmt.format(now))
            delay(15_000L)
        }
    }

    OpalAmbientBackdrop(modifier = Modifier.fillMaxSize(), wallpaperPreset = wallpaperPreset) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val canvasW = constraints.maxWidth
            val canvasH = constraints.maxHeight
            val densityVal = density.density
            val dpiInt = (densityVal * 160f).roundToInt().coerceIn(120, 480)

            // Sync physical canvas dimensions & effective DPI with daemon compositor
            LaunchedEffect(status.connected, canvasW, canvasH, dpiInt) {
                if (status.connected && canvasW >= 640 && canvasH >= 480) {
                    BridgeClient.updateCompositorConfigAsync(
                        canvasW = canvasW,
                        canvasH = canvasH,
                        canvasDpi = dpiInt,
                    )
                }
            }

            fun applyTiling(layout: WindowTilingLayout) {
                dismissAllPopovers()
                applyWindowTilingLayout(layout, scene, canvasW, canvasH, densityVal)
            }

            fun minimizeAllWindows() {
                scene.visibleWindows.forEach { win ->
                    BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.MINIMIZE)
                }
            }

            // Desktop wallpaper: single tap dismisses popovers; right-click or touchscreen long-press opens desktop/wallpaper menu
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onTapOrContextAction(
                        showAppDrawer, showExposeOverview, showControlCenter, showTilingMenu,
                        contextMenuTarget, wallpaperMenuPos,
                        onPrimaryTap = { dismissAllPopovers() },
                        onContextAction = { pos ->
                            dismissAllPopovers()
                            wallpaperMenuPos = pos
                        },
                    ),
            )

            // Samsung DeX-style Left-Column Desktop App Shortcuts (always visible behind windows)
            if (status.connected && allApps.isNotEmpty()) {
                DesktopShortcutColumn(
                    allApps = allApps,
                    desktopPkgs = desktopPkgs,
                    onLaunch = { entry ->
                        dismissAllPopovers()
                        scope.launch(Dispatchers.IO) {
                            BridgeClient.openWindow(entry.pkg, entry.activityClass, BridgeProtocol.Profile.AUTO)
                        }
                    },
                    onContextApp = { pkg, rootPos ->
                        dismissAllPopovers()
                        val running = scene.windows.firstOrNull { it.pkg == pkg }
                        contextMenuTarget = Triple(pkg, running, rootPos)
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            top = (CompositorGeometry.TOP_BAR_DP + 14f).dp,
                            start = 16.dp,
                            bottom = (CompositorGeometry.DOCK_INSET_DP + 14f).dp,
                        ),
                )
            }

            // Subtle macOS-style resting hint pill when no windows are currently visible
            if (status.connected && scene.visibleWindows.isEmpty() && !showAppDrawer && !showExposeOverview) {
                EmptyDesktopPill(
                    isExternalMonitor = isExternalMonitor,
                    onOpenDrawer = {
                        dismissAllPopovers()
                        showAppDrawer = true
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 88.dp),
                )
            }

            // Edge/corner snap frosted-glass preview frame
            snapPreviewRect?.let { snapRect ->
                Box(
                    modifier = Modifier
                        .offset { IntOffset(snapRect.left, snapRect.top) }
                        .size(
                            width = with(density) { snapRect.width.toDp() },
                            height = with(density) { snapRect.height.toDp() },
                        )
                        .clip(RoundedCornerShape(14.dp))
                        .background(OpalPalette.AccentBlue.copy(alpha = 0.20f))
                        .border(1.5.dp, OpalPalette.AccentBlue.copy(alpha = 0.78f), RoundedCornerShape(14.dp)),
                )
            }

            // Live Z-ordered macOS Compositor Windows:
            // Keep composition order STABLE by windowId so AndroidView(TextureView) never detaches
            // or desyncs AndroidViewsHandler hit-testing on focus changes; drive draw & touch order via zIndex!
            val stableWindows = remember(scene.visibleWindows) {
                scene.visibleWindows.sortedBy { it.windowId }
            }
            for (win in stableWindows) {
                key(win.windowId) {
                    CompositorWindowFrameNode(
                        window = win,
                        canvasW = canvasW,
                        canvasH = canvasH,
                        onSnapPreviewChanged = { snapPreviewRect = it },
                        modifier = Modifier.zIndex(win.zOrder.toFloat()),
                    )
                }
            }

            // Top 28dp Translucent macOS Liquid Glass Menu Bar
            MacDesktopMenuBar(
                focusedWindow = scene.focusedWindow,
                activeVdCount = scene.activeVdCount,
                maxVdCount = scene.maxVdCount,
                isExternalMonitor = isExternalMonitor,
                externalMonitorSummary = externalMonitorSummary,
                showTilingMenu = showTilingMenu,
                showControlCenter = showControlCenter,
                onToggleTilingMenu = {
                    val next = !showTilingMenu
                    dismissAllPopovers()
                    showTilingMenu = next
                },
                onToggleControlCenter = {
                    val next = !showControlCenter
                    dismissAllPopovers()
                    showControlCenter = next
                },
                onOpenConsole = onOpenConsole,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(10_000f),
            )

            // Top-Right Floating Liquid Frosted Glass Multi-Window Tiling Preset Popover
            if (showTilingMenu) {
                WindowTilingPopover(
                    windowCount = scene.windows.size,
                    onApplyLayout = ::applyTiling,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = (CompositorGeometry.TOP_BAR_DP + 8f).dp, end = 16.dp)
                        .zIndex(10_020f),
                )
            }

            // Bottom Floating Liquid Frosted Glass Dock & Status Capsule
            DexMacTaskbar(
                allApps = allApps,
                dockPinnedPkgs = dockPinnedPkgs,
                windows = scene.windows,
                tray = trayInfo,
                showAppDrawer = showAppDrawer,
                showExpose = showExposeOverview,
                showControlCenter = showControlCenter,
                onToggleAppDrawer = {
                    val next = !showAppDrawer
                    dismissAllPopovers()
                    showAppDrawer = next
                },
                onOpenSearch = {
                    dismissAllPopovers()
                    showAppDrawer = true
                },
                onToggleExpose = {
                    val next = !showExposeOverview
                    dismissAllPopovers()
                    showExposeOverview = next
                },
                onToggleControlCenter = {
                    val next = !showControlCenter
                    dismissAllPopovers()
                    showControlCenter = next
                },
                onSendBackToFocused = {
                    val focused = scene.focusedWindow
                    if (focused != null && !focused.isMinimized) {
                        BridgeClient.sendWindowActionAsync(focused.windowId, BridgeProtocol.WinAction.SEND_BACK)
                    }
                },
                onShowDesktopToggle = {
                    dismissAllPopovers()
                    if (scene.visibleWindows.isNotEmpty()) {
                        minimizeAllWindows()
                    } else {
                        val lastMin = scene.windows.maxByOrNull { it.zOrder }
                        if (lastMin != null) {
                            BridgeClient.sendWindowActionAsync(lastMin.windowId, BridgeProtocol.WinAction.FOCUS)
                        }
                    }
                },
                onLaunchOrToggleApp = { pkg, cls ->
                    dismissAllPopovers()
                    val existing = scene.windows.firstOrNull { it.pkg == pkg }
                    if (existing != null && existing.focused && !existing.isMinimized) {
                        BridgeClient.sendWindowActionAsync(existing.windowId, BridgeProtocol.WinAction.MINIMIZE)
                    } else if (existing != null) {
                        BridgeClient.sendWindowActionAsync(existing.windowId, BridgeProtocol.WinAction.FOCUS)
                    } else {
                        scope.launch(Dispatchers.IO) {
                            BridgeClient.openWindow(pkg, cls, BridgeProtocol.Profile.AUTO)
                        }
                    }
                },
                onContextApp = { pkg, win, rootPos ->
                    dismissAllPopovers()
                    contextMenuTarget = Triple(pkg, win, rootPos)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(10_010f),
            )

            // Floating Rounded Glass App Launcher Popover above macOS Dock
            if (showAppDrawer) {
                DexAppDrawerPopover(
                    apps = allApps,
                    desktopPkgs = desktopPkgs,
                    dockPinnedPkgs = dockPinnedPkgs,
                    onLaunch = { entry, profileCode ->
                        showAppDrawer = false
                        scope.launch(Dispatchers.IO) {
                            BridgeClient.openWindow(entry.pkg, entry.activityClass, profileCode)
                        }
                    },
                    onContextApp = { entry, rootPos ->
                        val running = scene.windows.firstOrNull { it.pkg == entry.pkg }
                        contextMenuTarget = Triple(entry.pkg, running, rootPos)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 78.dp)
                        .zIndex(10_020f),
                )
            }

            // Bottom-Right macOS Control Center Popover
            if (showControlCenter) {
                MacControlCenterPopover(
                    activeVdCount = scene.activeVdCount,
                    maxVdCount = scene.maxVdCount,
                    totalWindows = scene.windows.size,
                    inputBackendLabel = status.injectBackendLabel,
                    themeMode = themeMode,
                    wallpaperPreset = wallpaperPreset,
                    mouseTargetMode = mouseTargetMode,
                    isExternalMonitor = isExternalMonitor,
                    onSelectThemeMode = { mode ->
                        AppPrefs.setThemeMode(ctx, mode)
                    },
                    onSelectWallpaper = { idx ->
                        WallpaperStorage.clearCustomWallpaper(ctx)
                        AppPrefs.setWallpaperPreset(ctx, idx)
                    },
                    onPickCustomWallpaper = {
                        showControlCenter = false
                        onPickCustomWallpaper()
                    },
                    onSelectMouseTarget = { mode ->
                        AppPrefs.setMouseTarget(ctx, mode)
                        val extId = io.github.lengsukq.opaldesk.ExternalDesktopActivity.activeExternalDisplayId
                        BridgeClient.setMouseTargetAsync(mode, if (extId > 0) extId else -1)
                    },
                    onApplyTilingLayout = ::applyTiling,
                    onMinimizeAll = {
                        showControlCenter = false
                        minimizeAllWindows()
                    },
                    onCloseAll = {
                        showControlCenter = false
                        BridgeClient.closeAllWindowsAsync(removeTasks = true)
                    },
                    onOpenConsole = {
                        showControlCenter = false
                        onOpenConsole()
                    },
                    onExitDesktop = {
                        showControlCenter = false
                        onExitDesktop()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 78.dp)
                        .zIndex(10_020f),
                )
            }

            // Desktop Wallpaper Right-Click / Long-Press Context Menu (anchored right at cursor)
            wallpaperMenuPos?.let { pos ->
                val isCurrentlyDark = OpalPalette.isDark
                val menuW = with(density) { 252.dp.toPx() }.roundToInt()
                val menuH = with(density) { (if (customWallpaperPath.isNotBlank()) 320.dp else 284.dp).toPx() }.roundToInt()
                val (xPx, yPx) = CompositorGeometry.computeAnchoredMenuPosition(
                    anchorX = pos.x.roundToInt(),
                    anchorY = pos.y.roundToInt(),
                    menuW = menuW,
                    menuH = menuH,
                    canvasW = canvasW,
                    canvasH = canvasH,
                    density = densityVal,
                )
                DesktopWallpaperContextMenu(
                    wallpaperPreset = wallpaperPreset,
                    hasCustomWallpaper = customWallpaperPath.isNotBlank(),
                    onSelectPreset = { idx ->
                        WallpaperStorage.clearCustomWallpaper(ctx)
                        AppPrefs.setWallpaperPreset(ctx, idx)
                    },
                    onPickCustomWallpaper = onPickCustomWallpaper,
                    onClearCustomWallpaper = {
                        WallpaperStorage.clearCustomWallpaper(ctx)
                        Toast.makeText(ctx, "已恢复默认预设壁纸", Toast.LENGTH_SHORT).show()
                    },
                    onOpenTilingMenu = { showTilingMenu = true },
                    onOpenAppDrawer = { showAppDrawer = true },
                    onToggleLightDark = {
                        val next = if (isCurrentlyDark) AppPrefs.THEME_LIGHT else AppPrefs.THEME_DARK
                        AppPrefs.setThemeMode(ctx, next)
                    },
                    onOpenConsole = onOpenConsole,
                    onDismiss = { wallpaperMenuPos = null },
                    modifier = Modifier
                        .offset { IntOffset(xPx, yPx) }
                        .zIndex(10_025f),
                )
            }

            // Unified App Context Menu anchored right at cursor / finger position (auto-flips near edges!)
            contextMenuTarget?.let { (pkg, win, anchorPos) ->
                val appEntry = allApps.firstOrNull { it.pkg == pkg }
                val menuW = with(density) { 248.dp.toPx() }.roundToInt()
                val menuH = with(density) { (if (win != null) 316.dp else 174.dp).toPx() }.roundToInt()
                val (xPx, yPx) = CompositorGeometry.computeAnchoredMenuPosition(
                    anchorX = anchorPos.x.roundToInt(),
                    anchorY = anchorPos.y.roundToInt(),
                    menuW = menuW,
                    menuH = menuH,
                    canvasW = canvasW,
                    canvasH = canvasH,
                    density = densityVal,
                )
                TaskbarContextMenuPopover(
                    pkg = pkg,
                    label = win?.title ?: appEntry?.label ?: pkg,
                    window = win,
                    isOnDesktop = desktopPkgs.contains(pkg),
                    isPinnedToDock = dockPinnedPkgs.contains(pkg),
                    dockPinnedCount = dockPinnedPkgs.size,
                    onFocusOrLaunch = {
                        showAppDrawer = false
                        if (win != null) {
                            BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.FOCUS)
                        } else {
                            scope.launch(Dispatchers.IO) {
                                BridgeClient.openWindow(pkg, appEntry?.activityClass.orEmpty(), BridgeProtocol.Profile.AUTO)
                            }
                        }
                    },
                    onSnapLeft = {
                        if (win != null) {
                            val r = CompositorGeometry.computeSnapPreviewRect(
                                BridgeProtocol.Presentation.SNAP_LEFT, canvasW, canvasH, densityVal,
                            )
                            BridgeClient.updateWindowFrameAsync(
                                win.windowId, r.left, r.top, r.right, r.bottom,
                                BridgeProtocol.Presentation.SNAP_LEFT,
                            )
                        }
                    },
                    onSnapRight = {
                        if (win != null) {
                            val r = CompositorGeometry.computeSnapPreviewRect(
                                BridgeProtocol.Presentation.SNAP_RIGHT, canvasW, canvasH, densityVal,
                            )
                            BridgeClient.updateWindowFrameAsync(
                                win.windowId, r.left, r.top, r.right, r.bottom,
                                BridgeProtocol.Presentation.SNAP_RIGHT,
                            )
                        }
                    },
                    onMaximize = {
                        if (win != null) {
                            BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.TOGGLE_MAXIMIZE)
                        }
                    },
                    onToggleDesktopShortcut = {
                        val added = AppPrefs.toggleDesktopShortcut(ctx, pkg)
                        Toast.makeText(
                            ctx,
                            if (added) "已添加到桌面" else "已从桌面移除",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onToggleDockPin = {
                        when (AppPrefs.toggleDockPin(ctx, pkg)) {
                            AppPrefs.DockPinResult.ADDED ->
                                Toast.makeText(ctx, "已添加到底部程序坞", Toast.LENGTH_SHORT).show()
                            AppPrefs.DockPinResult.REMOVED ->
                                Toast.makeText(ctx, "已从底部程序坞移除", Toast.LENGTH_SHORT).show()
                            AppPrefs.DockPinResult.LIMIT_REACHED ->
                                Toast.makeText(
                                    ctx,
                                    "程序坞最多固定 ${AppPrefs.MAX_DOCK_PINNED} 个应用，请先移除一个后再添加",
                                    Toast.LENGTH_SHORT,
                                ).show()
                        }
                    },
                    onCloseWindow = {
                        if (win != null) {
                            BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.CLOSE)
                        }
                    },
                    onDismiss = { contextMenuTarget = null },
                    modifier = Modifier
                        .offset { IntOffset(xPx, yPx) }
                        .zIndex(10_026f),
                )
            }

            // Mission Control / Exposé Multi-Window Overview Overlay
            if (showExposeOverview) {
                Box(modifier = Modifier.fillMaxSize().zIndex(10_030f)) {
                    ExposeWindowSwitcherOverlay(
                        windows = scene.windows,
                        onSelectWindow = { win ->
                            showExposeOverview = false
                            BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.FOCUS)
                        },
                        onCloseWindow = { win ->
                            BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.CLOSE)
                        },
                        onMinimizeAll = {
                            showExposeOverview = false
                            minimizeAllWindows()
                        },
                        onCloseAll = {
                            showExposeOverview = false
                            BridgeClient.closeAllWindowsAsync(removeTasks = true)
                        },
                        onDismiss = { showExposeOverview = false },
                    )
                }
            }

            // Offline guard if daemon disconnected
            if (!status.connected) {
                OfflineDaemonPromptCard(
                    onOpenConsole = onOpenConsole,
                    onExit = onExitDesktop,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .zIndex(10_040f),
                )
            }

            // Crisp macOS Arrow Cursor Overlay (for External Monitor Presentation Trackpad / Mouse mode)
            virtualCursorPx?.let { pos ->
                Box(modifier = Modifier.fillMaxSize().zIndex(10_050f)) {
                    MacVirtualPointerCursor(positionPx = pos)
                }
            }
        }
    }
}

@Composable
private fun DesktopShortcutColumn(
    allApps: List<InstalledAppEntry>,
    desktopPkgs: List<String>,
    onLaunch: (InstalledAppEntry) -> Unit,
    onContextApp: (String, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val shortcuts = remember(allApps, desktopPkgs) {
        val byPkg = allApps.associateBy { it.pkg }
        desktopPkgs.mapNotNull { byPkg[it] }.take(15)
    }

    // Wrap into vertical columns of 5 icons (classic DeX desktop layout)
    val columns = shortcuts.chunked(5)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        columns.forEach { colApps ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                colApps.forEach { app ->
                    val icon = remember(app.pkg) { AppCatalogRepository.getAppIcon(ctx, app.pkg) }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(74.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .onTapOrContextAction(
                                app.pkg,
                                onPrimaryTap = { onLaunch(app) },
                                onContextAction = { rootPos -> onContextApp(app.pkg, rootPos) },
                            )
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .shadow(8.dp, RoundedCornerShape(13.dp))
                                .clip(RoundedCornerShape(13.dp))
                                .background(OpalPalette.GlassSurface),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (icon != null) {
                                Image(
                                    bitmap = icon,
                                    contentDescription = app.label,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                OpalText(text = app.label.take(1), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(OpalPalette.GlassSurface.copy(alpha = if (OpalPalette.isDark) 0.52f else 0.72f))
                                .padding(horizontal = 6.dp, vertical = 1.5.dp),
                        ) {
                            OpalText(
                                text = app.label,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = OpalPalette.TextPrimary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDesktopPill(
    isExternalMonitor: Boolean,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .liquidGlassSurface(
                shape = CircleShape,
                fill = OpalPalette.LiquidDockFill,
                rim = OpalPalette.LiquidGlassRim,
                elevation = 16.dp,
            )
            .clickable(onClick = onOpenDrawer)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(OpalPalette.AccentEmerald),
        )
        OpalText(
            text = if (isExternalMonitor) {
                "外接满屏桌面已就绪 · 点击桌面图标或底部 Dock 应用抽屉启动应用"
            } else {
                "桌面已就绪 · 点击桌面图标或底部 Dock 应用抽屉开启多窗口"
            },
            fontSize = 12.sp,
            color = OpalPalette.TextSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun ExposeWindowSwitcherOverlay(
    windows: List<CompositorWindow>,
    onSelectWindow: (CompositorWindow) -> Unit,
    onCloseWindow: (CompositorWindow) -> Unit,
    onMinimizeAll: () -> Unit,
    onCloseAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OpalPalette.ScrimColor)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 32.dp, vertical = 44.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    OpalText(text = "调度中心", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    OpalText(
                        text = "共 ${windows.size} 个应用窗口 · 点按卡片立即切换或唤醒",
                        fontSize = 12.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
                if (windows.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OpalButton(
                            label = "全部最小化",
                            filled = false,
                            compact = true,
                            onClick = onMinimizeAll,
                        )
                        OpalButton(
                            label = "全部关闭",
                            accent = OpalPalette.AccentDanger,
                            filled = false,
                            compact = true,
                            onClick = onCloseAll,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (windows.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    OpalText(text = "当前没有打开的窗口", color = OpalPalette.TextSecondary, fontSize = 14.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 250.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(windows, key = { it.windowId }) { win ->
                        val icon = remember(win.pkg) { AppCatalogRepository.getAppIcon(ctx, win.pkg) }
                        var previewBmp by remember(win.windowId, win.snapshotVersion) { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(win.windowId, win.snapshotVersion) {
                            previewBmp = withContext(Dispatchers.IO) {
                                BridgeClient.fetchWindowSnapshotBitmap(win.windowId, win.snapshotVersion)
                            }
                        }

                        OpalGlassCard(
                            modifier = Modifier.clickable { onSelectWindow(win) },
                            cornerRadius = 16.dp,
                            fillColor = OpalPalette.GlassPopover,
                            borderColor = if (win.focused) OpalPalette.AccentBlue else OpalPalette.BorderSubtle,
                            contentPadding = PaddingValues(12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (icon != null) {
                                    Image(
                                        bitmap = icon,
                                        contentDescription = win.title,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    OpalText(text = win.title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    val stateLabel = when {
                                        win.isMinimized -> "已最小化"
                                        win.isHibernated -> "快照休眠"
                                        win.focused -> "当前活动"
                                        else -> "实时运行"
                                    }
                                    OpalText(
                                        text = "$stateLabel · ${win.contentWidth}×${win.contentHeight}",
                                        fontSize = 11.sp,
                                        color = OpalPalette.TextSecondary,
                                        maxLines = 1,
                                    )
                                }
                                OpalGlyphButton(glyph = ChromeGlyph.CLOSE, size = 24.dp, dangerHover = true) {
                                    onCloseWindow(win)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(124.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(OpalPalette.WindowCanvasBg)
                                    .border(0.6.dp, OpalPalette.BorderHairline, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                val bmp = previewBmp
                                if (bmp != null && !bmp.isRecycled) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = win.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    OpalText(
                                        text = if (win.isLive) "实时虚拟屏 #${win.displayId}" else "点击恢复窗口",
                                        fontSize = 11.5.sp,
                                        color = OpalPalette.TextMuted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineDaemonPromptCard(
    onOpenConsole: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpalGlassCard(
        modifier = modifier.width(420.dp),
        cornerRadius = 20.dp,
        fillColor = OpalPalette.GlassPopover,
        contentPadding = PaddingValues(24.dp),
    ) {
        OpalStatusBadge(label = "守护进程尚未连接", color = OpalPalette.AccentAmber)
        Spacer(Modifier.height(12.dp))
        OpalText(text = "请先激活免 Root 后台守护进程", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        OpalText(
            text = "支持本机无线调试（免电脑）、Shizuku 一键秒激活、电脑 ADB 或可选 Root 激活。",
            fontSize = 13.sp,
            color = OpalPalette.TextSecondary,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OpalButton(label = "前往控制台激活", onClick = onOpenConsole, modifier = Modifier.weight(1f))
            OpalButton(label = "返回", filled = false, onClick = onExit)
        }
    }
}

/**
 * Crisp macOS-style black-filled, white-outlined arrow pointer rendered at `(positionPx.x, positionPx.y)`
 * on top of the external monitor Presentation canvas.
 */
@Composable
private fun MacVirtualPointerCursor(positionPx: Offset) {
    Canvas(
        modifier = Modifier
            .offset { IntOffset(positionPx.x.roundToInt(), positionPx.y.roundToInt()) }
            .size(26.dp),
    ) {
        val w = size.width
        val h = size.height
        val arrow = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, h * 0.84f)
            lineTo(w * 0.24f, h * 0.64f)
            lineTo(w * 0.42f, h * 0.96f)
            lineTo(w * 0.56f, h * 0.89f)
            lineTo(w * 0.38f, h * 0.58f)
            lineTo(w * 0.68f, h * 0.58f)
            close()
        }
        drawPath(path = arrow, color = Color(0xFF0E1015))
        drawPath(
            path = arrow,
            color = Color.White,
            style = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round),
        )
    }
}

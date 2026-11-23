package io.github.lengsukq.opaldesk.compositor

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.protocol.ResizeEdge
import io.github.lengsukq.opaldesk.protocol.WindowRect
import io.github.lengsukq.opaldesk.ui.ChromeGlyph
import io.github.lengsukq.opaldesk.ui.MacTrafficLightButton
import io.github.lengsukq.opaldesk.ui.OpalGlyphIcon
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.TrafficLightType
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Unified Single-RenderNode macOS-styled Window Container:
 * Traffic-light header chrome, squircle-like rounded clip, layered elevation shadow,
 * 8-way live resize handles, and hardware `TextureView` move & resize in strict GPU lockstep.
 */
@Composable
fun CompositorWindowFrameNode(
    window: CompositorWindow,
    canvasW: Int,
    canvasH: Int,
    onSnapPreviewChanged: (WindowRect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val densityVal = density.density
    val currentWin by rememberUpdatedState(window)

    var isInteracting by remember(window.windowId) { mutableStateOf(false) }
    var liveFrame by remember(window.windowId) { mutableStateOf(window.frame) }
    var pendingCommitFrame by remember(window.windowId) { mutableStateOf<WindowRect?>(null) }

    // Keep displaying locally committed frame until daemon confirms it (eliminates drop flicker)
    LaunchedEffect(window.frame, isInteracting, pendingCommitFrame) {
        if (isInteracting) return@LaunchedEffect
        val pending = pendingCommitFrame
        if (pending == null || window.frame == pending) {
            pendingCommitFrame = null
            liveFrame = window.frame
        } else {
            delay(360L)
            pendingCommitFrame = null
            liveFrame = currentWin.frame
        }
    }

    val activeFrame = if (isInteracting || pendingCommitFrame != null) liveFrame else window.frame
    val isFullscreen = window.isFullscreen
    val winShape = RoundedCornerShape(
        when {
            isFullscreen -> 0.dp
            window.isMaximized -> 8.dp
            else -> 14.dp
        },
    )
    val borderColor = if (window.focused) OpalPalette.BorderFocused else OpalPalette.BorderHairline
    val headerBg = if (window.focused) OpalPalette.GlassHeaderFocused else OpalPalette.GlassHeaderUnfocused
    val appIcon = remember(window.pkg) { AppCatalogRepository.getAppIcon(ctx, window.pkg) }

    val frameWidthDp = with(density) { activeFrame.width.coerceAtLeast(220).toDp() }
    val frameHeightDp = with(density) { activeFrame.height.coerceAtLeast(220).toDp() }
    val headerHeightPx = if (isFullscreen) 0 else with(density) { CompositorGeometry.HEADER_HEIGHT_DP.dp.roundToPx() }
    val showCompactTools = frameWidthDp >= 430.dp

    Box(
        modifier = modifier
            .offset { IntOffset(activeFrame.left, activeFrame.top) }
            .size(width = frameWidthDp, height = frameHeightDp)
            // Focus immediately on initial press (zero double-tap delay) and absorb any
            // leftover unconsumed pointer event on the bottom-up Main pass (NEVER Final, which is
            // top-down and would cancel AndroidView TextureView ACTION_MOVE drags/scrolls!).
            .pointerInput(window.windowId, "focus_and_shield") {
                awaitEachGesture {
                    val down = awaitPointerEvent(PointerEventPass.Initial)
                    if (down.type == PointerEventType.Press && !currentWin.focused) {
                        val pt = down.changes.firstOrNull()?.position
                        val occluded = if (pt != null) {
                            CompositorGeometry.isPointOccludedByHigherWindow(
                                target = currentWin,
                                canvasX = currentWin.frame.left + pt.x,
                                canvasY = currentWin.frame.top + pt.y,
                                visibleWindows = BridgeClient.sceneFlow.value.visibleWindows,
                            )
                        } else {
                            false
                        }
                        if (!occluded) {
                            BridgeClient.sendWindowActionAsync(currentWin.windowId, BridgeProtocol.WinAction.FOCUS)
                        }
                    }
                    // Main pass runs bottom-up (children first, then parent): safely absorbs clicks
                    // on empty chrome without interfering with AndroidView(TextureView) scrolling!
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        ev.changes.forEach { if (!it.isConsumed) it.consume() }
                        if (ev.changes.all { !it.pressed }) break
                    }
                }
            },
    ) {
        if (isFullscreen) {
            WindowSurfaceHost(
                window = window,
                headerHeightPx = 0,
                onTouchFocusRequested = {
                    BridgeClient.sendWindowActionAsync(window.windowId, BridgeProtocol.WinAction.FOCUS)
                },
                modifier = Modifier.fillMaxSize(),
            )
            return@Box
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .shadow(
                    elevation = if (window.focused) 24.dp else 8.dp,
                    shape = winShape,
                    clip = false,
                )
                .clip(winShape)
                .background(OpalPalette.WindowCanvasBg)
                .border(0.9.dp, borderColor, winShape),
        ) {
            // macOS 36dp Title Bar Chrome — unified root-space drag + tap/double-tap gesture
            var titleCoords by remember(window.windowId) { mutableStateOf<LayoutCoordinates?>(null) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CompositorGeometry.HEADER_HEIGHT_DP.dp)
                    .background(headerBg)
                    .onGloballyPositioned { titleCoords = it }
                    .pointerInput(window.windowId, canvasW, canvasH) {
                        var lastTapMs = 0L
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            val startRoot = titleCoords?.localToRoot(down.position) ?: down.position
                            val slop = viewConfiguration.touchSlop
                            var dragging = false
                            var startFrame = currentWin.frame
                            var lastLive = startFrame
                            var activeSnap: String? = null
                            var committed = false

                            try {
                                while (true) {
                                    val ev = awaitPointerEvent(PointerEventPass.Main)
                                    val change = ev.changes.firstOrNull { it.id == down.id }
                                        ?: ev.changes.firstOrNull()
                                        ?: break
                                    val curRoot = titleCoords?.localToRoot(change.position) ?: change.position
                                    val dx = curRoot.x - startRoot.x
                                    val dy = curRoot.y - startRoot.y

                                    if (!dragging && change.pressed && hypot(dx, dy) >= slop) {
                                        dragging = true
                                        isInteracting = true
                                        pendingCommitFrame = null
                                        activeSnap = null
                                        startFrame = if (currentWin.presentation != BridgeProtocol.Presentation.FLOATING) {
                                            val portraitCanvas = canvasH > canvasW
                                            val w = (canvasW * (if (portraitCanvas) 0.90f else 0.56f))
                                                .roundToInt().coerceAtLeast(320)
                                            val h = (canvasH * (if (portraitCanvas) 0.54f else 0.68f))
                                                .roundToInt().coerceAtLeast(300)
                                            val l = (startRoot.x.roundToInt() - w / 2)
                                                .coerceIn(10, (canvasW - w - 10).coerceAtLeast(10))
                                            val t = (currentWin.frame.top + 6).coerceAtLeast(
                                                CompositorGeometry.dpToPx(CompositorGeometry.TOP_BAR_DP, densityVal),
                                            )
                                            WindowRect(l, t, l + w, t + h)
                                        } else {
                                            currentWin.frame
                                        }
                                        lastLive = startFrame
                                        liveFrame = startFrame
                                        if (!currentWin.focused) {
                                            BridgeClient.sendWindowActionAsync(
                                                currentWin.windowId,
                                                BridgeProtocol.WinAction.FOCUS,
                                            )
                                        }
                                    }

                                    if (dragging) {
                                        if (change.pressed) {
                                            change.consume()
                                            val next = CompositorGeometry.clampMove(
                                                startFrame,
                                                dx.roundToInt(),
                                                dy.roundToInt(),
                                                canvasW,
                                                canvasH,
                                                densityVal,
                                            )
                                            lastLive = next
                                            liveFrame = next

                                            val snap = CompositorGeometry.detectSnapPresentation(
                                                curRoot.x.roundToInt().coerceIn(0, canvasW),
                                                curRoot.y.roundToInt().coerceIn(0, canvasH),
                                                canvasW,
                                                canvasH,
                                                densityVal,
                                            )
                                            activeSnap = snap
                                            onSnapPreviewChanged(
                                                snap?.let {
                                                    CompositorGeometry.computeSnapPreviewRect(
                                                        it, canvasW, canvasH, densityVal,
                                                    )
                                                },
                                            )
                                        } else {
                                            change.consume()
                                            val snap = activeSnap
                                            val finalFrame = if (snap != null) {
                                                CompositorGeometry.computeSnapPreviewRect(
                                                    snap, canvasW, canvasH, densityVal,
                                                )
                                            } else {
                                                lastLive
                                            }
                                            liveFrame = finalFrame
                                            pendingCommitFrame = finalFrame
                                            committed = true
                                            BridgeClient.updateWindowFrameAsync(
                                                windowId = currentWin.windowId,
                                                left = finalFrame.left,
                                                top = finalFrame.top,
                                                right = finalFrame.right,
                                                bottom = finalFrame.bottom,
                                                presentation = snap ?: BridgeProtocol.Presentation.FLOATING,
                                            )
                                            break
                                        }
                                    } else if (!change.pressed) {
                                        if (!change.isConsumed) {
                                            val now = SystemClock.uptimeMillis()
                                            if (now - lastTapMs <= 280L) {
                                                lastTapMs = 0L
                                                BridgeClient.sendWindowActionAsync(
                                                    currentWin.windowId,
                                                    BridgeProtocol.WinAction.TOGGLE_MAXIMIZE,
                                                )
                                            } else {
                                                lastTapMs = now
                                                if (!currentWin.focused) {
                                                    BridgeClient.sendWindowActionAsync(
                                                        currentWin.windowId,
                                                        BridgeProtocol.WinAction.FOCUS,
                                                    )
                                                }
                                            }
                                        }
                                        break
                                    }
                                }
                            } finally {
                                onSnapPreviewChanged(null)
                                if (dragging && !committed) {
                                    pendingCommitFrame = null
                                    liveFrame = currentWin.frame
                                }
                                isInteracting = false
                            }
                        }
                    }
                    .padding(horizontal = 10.dp),
            ) {
                // Left Cluster: macOS Traffic Lights + Android Back
                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MacTrafficLightButton(TrafficLightType.CLOSE, window.focused) {
                        BridgeClient.sendWindowActionAsync(window.windowId, BridgeProtocol.WinAction.CLOSE)
                    }
                    MacTrafficLightButton(TrafficLightType.MINIMIZE, window.focused) {
                        BridgeClient.sendWindowActionAsync(window.windowId, BridgeProtocol.WinAction.MINIMIZE)
                    }
                    MacTrafficLightButton(TrafficLightType.ZOOM, window.focused) {
                        BridgeClient.sendWindowActionAsync(window.windowId, BridgeProtocol.WinAction.TOGGLE_MAXIMIZE)
                    }
                    Spacer(Modifier.width(5.dp))
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(14.dp)
                            .background(OpalPalette.DividerColor),
                    )
                    Spacer(Modifier.width(3.dp))
                    HeaderSubtleIconButton(glyph = ChromeGlyph.BACK, focused = window.focused) {
                        BridgeClient.sendWindowActionAsync(window.windowId, BridgeProtocol.WinAction.SEND_BACK)
                    }
                }

                // Centered App Icon + Title (macOS Style)
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(
                            horizontal = when {
                                showCompactTools -> 156.dp
                                frameWidthDp < 360.dp -> 88.dp
                                else -> 110.dp
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (appIcon != null) {
                        Image(
                            bitmap = appIcon,
                            contentDescription = window.title,
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    OpalText(
                        text = window.title,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (window.focused) OpalPalette.TextPrimary else OpalPalette.TextSecondary,
                        maxLines = 1,
                    )
                }

                // Right Cluster: Tile Left/Right, Profile Switcher (Desktop/Tablet/Portrait), Reload
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (showCompactTools) {
                        HeaderSubtleIconButton(glyph = ChromeGlyph.TILE_LEFT, focused = window.focused) {
                            val rect = CompositorGeometry.computeSnapPreviewRect(
                                BridgeProtocol.Presentation.SNAP_LEFT, canvasW, canvasH, densityVal,
                            )
                            BridgeClient.updateWindowFrameAsync(
                                window.windowId, rect.left, rect.top, rect.right, rect.bottom,
                                BridgeProtocol.Presentation.SNAP_LEFT,
                            )
                        }
                        HeaderSubtleIconButton(glyph = ChromeGlyph.TILE_RIGHT, focused = window.focused) {
                            val rect = CompositorGeometry.computeSnapPreviewRect(
                                BridgeProtocol.Presentation.SNAP_RIGHT, canvasW, canvasH, densityVal,
                            )
                            BridgeClient.updateWindowFrameAsync(
                                window.windowId, rect.left, rect.top, rect.right, rect.bottom,
                                BridgeProtocol.Presentation.SNAP_RIGHT,
                            )
                        }
                    }

                    val profileLabel = when (window.profileCode) {
                        BridgeProtocol.Profile.PORTRAIT -> "竖屏"
                        BridgeProtocol.Profile.TABLET -> "平板"
                        else -> "桌面"
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (window.focused) OpalPalette.ChipFill else Color.Transparent)
                            .border(0.6.dp, OpalPalette.BorderHairline, RoundedCornerShape(6.dp))
                            .clickable {
                                val nextAct = when (window.profileCode) {
                                    BridgeProtocol.Profile.DESKTOP -> BridgeProtocol.WinAction.SET_PROFILE_TABLET
                                    BridgeProtocol.Profile.TABLET -> BridgeProtocol.WinAction.SET_PROFILE_PORTRAIT
                                    else -> BridgeProtocol.WinAction.SET_PROFILE_DESKTOP
                                }
                                BridgeClient.sendWindowActionAsync(window.windowId, nextAct)
                            }
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    ) {
                        OpalText(
                            text = profileLabel,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (window.focused) OpalPalette.TextPrimary else OpalPalette.TextMuted,
                            maxLines = 1,
                        )
                    }

                    HeaderSubtleIconButton(glyph = ChromeGlyph.RELOAD, focused = window.focused) {
                        BridgeClient.sendWindowActionAsync(window.windowId, BridgeProtocol.WinAction.RELOAD_APP)
                    }
                }
            }

            // Hairline separator beneath macOS titlebar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.75.dp)
                    .background(OpalPalette.DividerColor),
            )

            // Live VirtualDisplay TextureView Surface Host
            WindowSurfaceHost(
                window = window,
                headerHeightPx = headerHeightPx,
                onTouchFocusRequested = {
                    BridgeClient.sendWindowActionAsync(window.windowId, BridgeProtocol.WinAction.FOCUS)
                },
                modifier = Modifier.weight(1f),
            )
        }

        // 8-Way Live GPU Resize Handles (pure GPU frame preview during drag; single atomic VirtualDisplay resize on commit)
        if (!window.isMaximized) {
            val onStart = {
                isInteracting = true
                pendingCommitFrame = null
            }
            val onCommit: (WindowRect) -> Unit = { committed ->
                liveFrame = committed
                pendingCommitFrame = committed
                isInteracting = false
            }
            val onCancel = {
                pendingCommitFrame = null
                isInteracting = false
                liveFrame = currentWin.frame
            }

            ResizeHandleZone(ResizeEdge.E, Alignment.CenterEnd, Modifier.width(14.dp).height((frameHeightDp - 68.dp).coerceAtLeast(36.dp)), currentWin, canvasW, canvasH, densityVal, onStart, { liveFrame = it }, onCommit, onCancel)
            ResizeHandleZone(ResizeEdge.W, Alignment.CenterStart, Modifier.width(14.dp).height((frameHeightDp - 68.dp).coerceAtLeast(36.dp)), currentWin, canvasW, canvasH, densityVal, onStart, { liveFrame = it }, onCommit, onCancel)
            ResizeHandleZone(ResizeEdge.S, Alignment.BottomCenter, Modifier.height(14.dp).width((frameWidthDp - 68.dp).coerceAtLeast(36.dp)), currentWin, canvasW, canvasH, densityVal, onStart, { liveFrame = it }, onCommit, onCancel)
            ResizeHandleZone(ResizeEdge.N, Alignment.TopCenter, Modifier.height(5.dp).width((frameWidthDp - 180.dp).coerceAtLeast(36.dp)), currentWin, canvasW, canvasH, densityVal, onStart, { liveFrame = it }, onCommit, onCancel)
            ResizeHandleZone(ResizeEdge.SE, Alignment.BottomEnd, Modifier.size(34.dp), currentWin, canvasW, canvasH, densityVal, onStart, { liveFrame = it }, onCommit, onCancel, showGrip = true)
            ResizeHandleZone(ResizeEdge.SW, Alignment.BottomStart, Modifier.size(34.dp), currentWin, canvasW, canvasH, densityVal, onStart, { liveFrame = it }, onCommit, onCancel)
            ResizeHandleZone(ResizeEdge.NE, Alignment.TopEnd, Modifier.size(16.dp), currentWin, canvasW, canvasH, densityVal, onStart, { liveFrame = it }, onCommit, onCancel)
            ResizeHandleZone(ResizeEdge.NW, Alignment.TopStart, Modifier.size(16.dp), currentWin, canvasW, canvasH, densityVal, onStart, { liveFrame = it }, onCommit, onCancel)
        }
    }
}

@Composable
private fun HeaderSubtleIconButton(
    glyph: ChromeGlyph,
    focused: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        OpalGlyphIcon(
            glyph = glyph,
            tint = if (focused) OpalPalette.TextSecondary else OpalPalette.TextMuted,
            size = 13.dp,
        )
    }
}

@Composable
private fun BoxScope.ResizeHandleZone(
    edge: ResizeEdge,
    alignment: Alignment,
    sizeModifier: Modifier,
    window: CompositorWindow,
    canvasW: Int,
    canvasH: Int,
    densityVal: Float,
    onDragStarted: () -> Unit,
    onLiveFrameChanged: (WindowRect) -> Unit,
    onCommitFrame: (WindowRect) -> Unit,
    onDragCancelled: () -> Unit,
    showGrip: Boolean = false,
) {
    val latestWin by rememberUpdatedState(window)
    val latestOnStart by rememberUpdatedState(onDragStarted)
    val latestOnLive by rememberUpdatedState(onLiveFrameChanged)
    val latestOnCommit by rememberUpdatedState(onCommitFrame)
    val latestOnCancel by rememberUpdatedState(onDragCancelled)
    var handleCoords by remember(window.windowId, edge) { mutableStateOf<LayoutCoordinates?>(null) }

    Box(
        modifier = Modifier
            .align(alignment)
            .zIndex(20f)
            .then(sizeModifier)
            .onGloballyPositioned { handleCoords = it }
            .pointerInput(window.windowId, edge, canvasW, canvasH) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Consume immediately so TextureView underneath never steals corner/edge resize gestures
                    down.consume()
                    val startRoot = handleCoords?.localToRoot(down.position) ?: down.position
                    val base = latestWin.frame
                    var cur = base
                    var moved = false
                    var committed = false

                    latestOnStart()
                    if (!latestWin.focused) {
                        BridgeClient.sendWindowActionAsync(latestWin.windowId, BridgeProtocol.WinAction.FOCUS)
                    }

                    try {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Main)
                            val change = ev.changes.firstOrNull { it.id == down.id }
                                ?: ev.changes.firstOrNull()
                                ?: break
                            if (change.pressed) {
                                change.consume()
                                val curRoot = handleCoords?.localToRoot(change.position) ?: change.position
                                val dx = (curRoot.x - startRoot.x).roundToInt()
                                val dy = (curRoot.y - startRoot.y).roundToInt()
                                if (!moved && hypot(dx.toDouble(), dy.toDouble()) >= 2.0) {
                                    moved = true
                                }
                                if (moved) {
                                    cur = CompositorGeometry.computeResize(
                                        base,
                                        edge,
                                        dx,
                                        dy,
                                        canvasW,
                                        canvasH,
                                        densityVal,
                                    )
                                    // Smooth 120fps client GPU frame scaling during drag;
                                    // avoid mid-drag VirtualDisplay.resize storms that desync SurfaceFlinger/WM.
                                    latestOnLive(cur)
                                }
                            } else {
                                change.consume()
                                if (moved && cur != base) {
                                    committed = true
                                    latestOnCommit(cur)
                                    BridgeClient.updateWindowFrameAsync(
                                        windowId = latestWin.windowId,
                                        left = cur.left,
                                        top = cur.top,
                                        right = cur.right,
                                        bottom = cur.bottom,
                                        presentation = BridgeProtocol.Presentation.FLOATING,
                                    )
                                }
                                break
                            }
                        }
                    } finally {
                        if (!committed) {
                            latestOnCancel()
                        }
                    }
                }
            },
        contentAlignment = Alignment.BottomEnd,
    ) {
        if (showGrip) {
            val gripColor = if (window.focused) {
                OpalPalette.TextSecondary.copy(alpha = 0.72f)
            } else {
                OpalPalette.TextMuted.copy(alpha = 0.42f)
            }
            Canvas(
                modifier = Modifier
                    .padding(6.dp)
                    .size(11.dp),
            ) {
                val stroke = 1.4.dp.toPx()
                drawLine(gripColor, Offset(size.width * 0.22f, size.height), Offset(size.width, size.height * 0.22f), stroke, StrokeCap.Round)
                drawLine(gripColor, Offset(size.width * 0.62f, size.height), Offset(size.width, size.height * 0.62f), stroke, StrokeCap.Round)
            }
        }
    }
}

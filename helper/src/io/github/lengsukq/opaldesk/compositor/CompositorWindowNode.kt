package io.github.lengsukq.opaldesk.compositor

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val winShape = RoundedCornerShape(if (window.isMaximized) 8.dp else 14.dp)
    val borderColor = if (window.focused) OpalPalette.BorderFocused else OpalPalette.BorderHairline
    val headerBg = if (window.focused) OpalPalette.GlassHeaderFocused else OpalPalette.GlassHeaderUnfocused
    val appIcon = remember(window.pkg) { AppCatalogRepository.getAppIcon(ctx, window.pkg) }

    val frameWidthDp = with(density) { activeFrame.width.coerceAtLeast(220).toDp() }
    val frameHeightDp = with(density) { activeFrame.height.coerceAtLeast(220).toDp() }
    val headerHeightPx = with(density) { CompositorGeometry.HEADER_HEIGHT_DP.dp.roundToPx() }
    val showCompactTools = frameWidthDp >= 430.dp

    Box(
        modifier = modifier
            .offset { IntOffset(activeFrame.left, activeFrame.top) }
            .size(width = frameWidthDp, height = frameHeightDp)
            // Focus immediately on initial press (zero double-tap delay) and absorb any
            // unconsumed pointer event on the bubble pass so clicks NEVER leak through to windows behind!
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
                    // Consume any unconsumed event on bubble (Final) pass so nothing leaks behind this window
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Final)
                        ev.changes.forEach { if (!it.isConsumed) it.consume() }
                        if (ev.changes.all { !it.pressed }) break
                    }
                }
            },
    ) {
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
            // macOS 36dp Title Bar Chrome
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CompositorGeometry.HEADER_HEIGHT_DP.dp)
                    .background(headerBg)
                    .pointerInput(window.windowId) {
                        detectTapGestures(
                            onTap = {
                                if (!currentWin.focused) {
                                    BridgeClient.sendWindowActionAsync(currentWin.windowId, BridgeProtocol.WinAction.FOCUS)
                                }
                            },
                            onDoubleTap = {
                                BridgeClient.sendWindowActionAsync(
                                    currentWin.windowId,
                                    BridgeProtocol.WinAction.TOGGLE_MAXIMIZE,
                                )
                            },
                        )
                    }
                    .pointerInput(window.windowId, canvasW, canvasH) {
                        var startFrame = currentWin.frame
                        var accDx = 0f
                        var accDy = 0f
                        var activeSnap: String? = null
                        var lastMoveX = 0
                        var lastMoveY = 0

                        detectDragGestures(
                            onDragStart = { startOffset ->
                                isInteracting = true
                                pendingCommitFrame = null
                                accDx = 0f
                                accDy = 0f
                                activeSnap = null
                                startFrame = if (currentWin.presentation != BridgeProtocol.Presentation.FLOATING) {
                                    val w = (canvasW * 0.56f).roundToInt().coerceAtLeast(480)
                                    val h = (canvasH * 0.68f).roundToInt().coerceAtLeast(380)
                                    val l = (currentWin.frame.left + startOffset.x.roundToInt() - w / 2)
                                        .coerceIn(10, (canvasW - w - 10).coerceAtLeast(10))
                                    val t = (currentWin.frame.top + 6).coerceAtLeast(
                                        CompositorGeometry.dpToPx(CompositorGeometry.TOP_BAR_DP, densityVal),
                                    )
                                    WindowRect(l, t, l + w, t + h)
                                } else {
                                    currentWin.frame
                                }
                                liveFrame = startFrame
                                lastMoveX = startFrame.left + startOffset.x.roundToInt()
                                lastMoveY = startFrame.top + startOffset.y.roundToInt()
                                if (!currentWin.focused) {
                                    BridgeClient.sendWindowActionAsync(currentWin.windowId, BridgeProtocol.WinAction.FOCUS)
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                accDx += dragAmount.x
                                accDy += dragAmount.y
                                val next = CompositorGeometry.clampMove(
                                    startFrame,
                                    accDx.roundToInt(),
                                    accDy.roundToInt(),
                                    canvasW,
                                    canvasH,
                                    densityVal,
                                )
                                liveFrame = next
                                lastMoveX = (next.left + change.position.x.roundToInt()).coerceIn(0, canvasW)
                                lastMoveY = (next.top + change.position.y.roundToInt()).coerceIn(0, canvasH)

                                val snap = CompositorGeometry.detectSnapPresentation(
                                    lastMoveX,
                                    lastMoveY,
                                    canvasW,
                                    canvasH,
                                    densityVal,
                                )
                                activeSnap = snap
                                onSnapPreviewChanged(
                                    snap?.let {
                                        CompositorGeometry.computeSnapPreviewRect(it, canvasW, canvasH, densityVal)
                                    },
                                )
                            },
                            onDragEnd = {
                                onSnapPreviewChanged(null)
                                val snap = activeSnap
                                val committed = if (snap != null) {
                                    CompositorGeometry.computeSnapPreviewRect(snap, canvasW, canvasH, densityVal)
                                } else {
                                    liveFrame
                                }
                                liveFrame = committed
                                pendingCommitFrame = committed
                                isInteracting = false
                                BridgeClient.updateWindowFrameAsync(
                                    windowId = currentWin.windowId,
                                    left = committed.left,
                                    top = committed.top,
                                    right = committed.right,
                                    bottom = committed.bottom,
                                    presentation = snap ?: BridgeProtocol.Presentation.FLOATING,
                                )
                            },
                            onDragCancel = {
                                onSnapPreviewChanged(null)
                                pendingCommitFrame = null
                                isInteracting = false
                                liveFrame = currentWin.frame
                            },
                        )
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
                        .padding(horizontal = if (showCompactTools) 156.dp else 110.dp),
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

        // 8-Way Live Resize Handles (placed cleanly along outer edges so header buttons stay unobstructed)
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

            ResizeHandleZone(ResizeEdge.E, Alignment.CenterEnd, Modifier.width(8.dp).height((frameHeightDp - 44.dp).coerceAtLeast(40.dp)), currentWin, canvasW, canvasH, densityVal, headerHeightPx, onStart, { liveFrame = it }, onCommit, onCancel)
            ResizeHandleZone(ResizeEdge.W, Alignment.CenterStart, Modifier.width(8.dp).height((frameHeightDp - 44.dp).coerceAtLeast(40.dp)), currentWin, canvasW, canvasH, densityVal, headerHeightPx, onStart, { liveFrame = it }, onCommit, onCancel)
            ResizeHandleZone(ResizeEdge.S, Alignment.BottomCenter, Modifier.height(9.dp).width((frameWidthDp - 44.dp).coerceAtLeast(40.dp)), currentWin, canvasW, canvasH, densityVal, headerHeightPx, onStart, { liveFrame = it }, onCommit, onCancel)
            ResizeHandleZone(ResizeEdge.N, Alignment.TopCenter, Modifier.height(5.dp).width((frameWidthDp - 160.dp).coerceAtLeast(40.dp)), currentWin, canvasW, canvasH, densityVal, headerHeightPx, onStart, { liveFrame = it }, onCommit, onCancel)
            ResizeHandleZone(ResizeEdge.SE, Alignment.BottomEnd, Modifier.size(22.dp), currentWin, canvasW, canvasH, densityVal, headerHeightPx, onStart, { liveFrame = it }, onCommit, onCancel, showGrip = window.focused)
            ResizeHandleZone(ResizeEdge.SW, Alignment.BottomStart, Modifier.size(22.dp), currentWin, canvasW, canvasH, densityVal, headerHeightPx, onStart, { liveFrame = it }, onCommit, onCancel)
            ResizeHandleZone(ResizeEdge.NE, Alignment.TopEnd, Modifier.size(9.dp), currentWin, canvasW, canvasH, densityVal, headerHeightPx, onStart, { liveFrame = it }, onCommit, onCancel)
            ResizeHandleZone(ResizeEdge.NW, Alignment.TopStart, Modifier.size(9.dp), currentWin, canvasW, canvasH, densityVal, headerHeightPx, onStart, { liveFrame = it }, onCommit, onCancel)
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
    headerHeightPx: Int,
    onDragStarted: () -> Unit,
    onLiveFrameChanged: (WindowRect) -> Unit,
    onCommitFrame: (WindowRect) -> Unit,
    onDragCancelled: () -> Unit,
    showGrip: Boolean = false,
) {
    val latestWin by rememberUpdatedState(window)

    Box(
        modifier = Modifier
            .align(alignment)
            .then(sizeModifier)
            .pointerInput(window.windowId, edge, canvasW, canvasH) {
                var base = latestWin.frame
                var cur = base
                var accX = 0f
                var accY = 0f
                var lastResizeRpcMs = 0L

                detectDragGestures(
                    onDragStart = {
                        base = latestWin.frame
                        cur = base
                        accX = 0f
                        accY = 0f
                        onDragStarted()
                        if (!latestWin.focused) {
                            BridgeClient.sendWindowActionAsync(latestWin.windowId, BridgeProtocol.WinAction.FOCUS)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accX += dragAmount.x
                        accY += dragAmount.y
                        cur = CompositorGeometry.computeResize(
                            base,
                            edge,
                            accX.roundToInt(),
                            accY.roundToInt(),
                            canvasW,
                            canvasH,
                            densityVal,
                        )
                        onLiveFrameChanged(cur)

                        val now = SystemClock.uptimeMillis()
                        if (now - lastResizeRpcMs >= 50L) {
                            lastResizeRpcMs = now
                            val cw = (cur.width and 1.inv()).coerceAtLeast(240)
                            val ch = ((cur.height - headerHeightPx) and 1.inv()).coerceAtLeast(200)
                            BridgeClient.resizeWindowDisplayOneway(latestWin.windowId, cw, ch)
                        }
                    },
                    onDragEnd = {
                        onCommitFrame(cur)
                        BridgeClient.updateWindowFrameAsync(
                            windowId = latestWin.windowId,
                            left = cur.left,
                            top = cur.top,
                            right = cur.right,
                            bottom = cur.bottom,
                            presentation = BridgeProtocol.Presentation.FLOATING,
                        )
                    },
                    onDragCancel = {
                        onDragCancelled()
                    },
                )
            },
        contentAlignment = Alignment.BottomEnd,
    ) {
        if (showGrip) {
            val gripColor = OpalPalette.TextMuted.copy(alpha = 0.55f)
            Canvas(
                modifier = Modifier
                    .padding(4.dp)
                    .size(10.dp),
            ) {
                val stroke = 1.2.dp.toPx()
                drawLine(gripColor, Offset(size.width * 0.30f, size.height), Offset(size.width, size.height * 0.30f), stroke, StrokeCap.Round)
                drawLine(gripColor, Offset(size.width * 0.65f, size.height), Offset(size.width, size.height * 0.65f), stroke, StrokeCap.Round)
            }
        }
    }
}

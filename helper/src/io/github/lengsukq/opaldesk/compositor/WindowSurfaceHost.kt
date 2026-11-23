package io.github.lengsukq.opaldesk.compositor

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.diagnostics.OpalLogStore
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.CompositorGeometry
import io.github.lengsukq.opaldesk.protocol.CompositorWindow
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Hardware-accelerated `TextureView` (`GL_TEXTURE_EXTERNAL_OES`) client surface host for a single
 * isolated VirtualDisplay window, paired with seamless LRU hibernation snapshot fallback.
 *
 * Because `TextureView` participates directly in the parent RenderNode tree (unlike `SurfaceView`
 * holes), moving, clipping, rounding corners, and scaling the window happens in strict 0-frame-lag
 * lockstep with the integrated macOS window chrome at native display refresh rate (60–165Hz).
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun WindowSurfaceHost(
    window: CompositorWindow,
    headerHeightPx: Int,
    onTouchFocusRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentWin by rememberUpdatedState(window)
    val currentHeaderPx by rememberUpdatedState(headerHeightPx)
    val currentFocusCb by rememberUpdatedState(onTouchFocusRequested)

    var hibernateBitmap by remember(window.windowId) { mutableStateOf<Bitmap?>(null) }
    var hasRenderedLiveFrame by remember(window.windowId) { mutableStateOf(false) }

    LaunchedEffect(window.windowId, window.isHibernated, window.snapshotVersion) {
        if (window.hasSnapshot) {
            val bmp = withContext(Dispatchers.IO) {
                BridgeClient.fetchWindowSnapshotBitmap(window.windowId, window.snapshotVersion)
            }
            if (bmp != null && !bmp.isRecycled) {
                hibernateBitmap = bmp
            }
        }
        if (window.isHibernated) {
            hasRenderedLiveFrame = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OpalPalette.WindowCanvasBg)
            // Intercept Compose PointerEventType.Scroll directly: AndroidComposeView routes
            // ACTION_SCROLL into Compose PointerInput rather than AndroidView.onGenericMotionListener
            // whenever ancestor pointerInput modifiers exist.
            .pointerInput(window.windowId, "mouse_wheel_scroll") {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.type == PointerEventType.Scroll) {
                            val change = ev.changes.firstOrNull() ?: continue
                            val delta = change.scrollDelta
                            val vScroll = -delta.y
                            val hScroll = delta.x
                            if (vScroll != 0f || hScroll != 0f) {
                                val win = currentWin
                                val canvasX = win.frame.left + change.position.x
                                val canvasY = win.frame.top + currentHeaderPx + change.position.y
                                val occluded = CompositorGeometry.isPointOccludedByHigherWindow(
                                    target = win,
                                    canvasX = canvasX,
                                    canvasY = canvasY,
                                    visibleWindows = BridgeClient.sceneFlow.value.visibleWindows,
                                )
                                if (!occluded && win.isLive && win.displayId > 0) {
                                    change.consume()
                                    val mappedX = CompositorGeometry.mapViewToDisplayCoord(
                                        change.position.x,
                                        size.width,
                                        win.contentWidth.coerceAtLeast(2),
                                    )
                                    val mappedY = CompositorGeometry.mapViewToDisplayCoord(
                                        change.position.y,
                                        size.height,
                                        win.contentHeight.coerceAtLeast(2),
                                    )
                                    val now = SystemClock.uptimeMillis()
                                    BridgeClient.injectWindowMotionOneway(
                                        windowId = win.windowId,
                                        action = MotionEvent.ACTION_SCROLL,
                                        downTime = now,
                                        eventTime = now,
                                        source = InputDevice.SOURCE_MOUSE,
                                        buttonState = 0,
                                        actionButton = 0,
                                        hScroll = hScroll,
                                        vScroll = vScroll,
                                        pointerIds = intArrayOf(0),
                                        toolTypes = intArrayOf(MotionEvent.TOOL_TYPE_MOUSE),
                                        xs = floatArrayOf(mappedX),
                                        ys = floatArrayOf(mappedY),
                                        pressures = floatArrayOf(0f),
                                    )
                                }
                            }
                        }
                    }
                }
            },
    ) {
        if (!window.isHibernated) {
            val holderState = remember(window.windowId) { SurfaceBindHolder(window.windowId) }

            DisposableEffect(window.windowId) {
                onDispose {
                    holderState.releaseAll(notifyDaemon = true)
                }
            }

            // Keep SurfaceTexture buffer size synchronized with daemon VirtualDisplay resolution
            LaunchedEffect(window.contentWidth, window.contentHeight) {
                holderState.updateBufferSize(
                    window.contentWidth.coerceAtLeast(240),
                    window.contentHeight.coerceAtLeast(200),
                )
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureView(ctx).apply {
                        isOpaque = false
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                val w = currentWin.contentWidth.coerceAtLeast(240)
                                val h = currentWin.contentHeight.coerceAtLeast(200)
                                val dpi = currentWin.contentDpi.coerceAtLeast(120)
                                val activeFps = resolveHostDisplayRefreshRate(this@apply)
                                st.setDefaultBufferSize(w, h)
                                holderState.attachAsync(st, w, h, dpi, activeFps)
                            }

                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                                // Keep buffer dimensions strictly synced with the daemon's committed VirtualDisplay
                                // resolution so transient GPU resize drags never fight SurfaceFlinger's BufferQueue.
                                val cw = currentWin.contentWidth.coerceAtLeast(240)
                                val ch = currentWin.contentHeight.coerceAtLeast(200)
                                holderState.updateBufferSize(cw, ch)
                            }

                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                OpalLogStore.i("Surface", "TextureView destroyed wid=${currentWin.windowId} hadFrame=$hasRenderedLiveFrame")
                                hasRenderedLiveFrame = false
                                holderState.releaseAll(notifyDaemon = false)
                                return true
                            }

                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                                if (!hasRenderedLiveFrame) {
                                    hasRenderedLiveFrame = true
                                    OpalLogStore.i("Surface", "First live frame rendered wid=${currentWin.windowId} pkg=${currentWin.pkg}")
                                }
                            }
                        }

                        setOnTouchListener { v, ev ->
                            if (ev?.actionMasked == MotionEvent.ACTION_DOWN) {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            forwardMotionEventToWindow(
                                v.width, v.height, ev, currentWin, currentHeaderPx, currentFocusCb,
                            )
                        }
                        setOnGenericMotionListener { v, ev ->
                            forwardMotionEventToWindow(
                                v.width, v.height, ev, currentWin, currentHeaderPx, currentFocusCb,
                            )
                        }
                    }
                },
                update = { textureView ->
                    // Keep native Android View Z-order strictly aligned with Compose zIndex
                    // so AndroidViewsHandler hit-testing never clicks through to lower windows.
                    val zVal = window.zOrder.toFloat()
                    textureView.translationZ = zVal
                    (textureView.parent as? View)?.let { holderView ->
                        holderView.translationZ = zVal
                        if (window.focused) {
                            holderView.bringToFront()
                        }
                    }
                },
            )

            // Seamless wake bridge: keep showing frozen snapshot until the live VirtualDisplay draws frame 1
            if (!hasRenderedLiveFrame) {
                val snap = hibernateBitmap
                if (snap != null && !snap.isRecycled) {
                    Image(
                        bitmap = snap.asImageBitmap(),
                        contentDescription = window.title,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        } else {
            // Hibernated window view: renders frozen TaskSnapshot with one-tap instant wake
            val snap = hibernateBitmap
            if (snap != null && !snap.isRecycled) {
                Image(
                    bitmap = snap.asImageBitmap(),
                    contentDescription = window.title,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66080A10))
                    .clickable {
                        BridgeClient.sendWindowActionAsync(window.windowId, BridgeProtocol.WinAction.WAKE)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(OpalPalette.GlassSurface)
                            .border(1.dp, OpalPalette.BorderFocused, CircleShape)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        OpalText(
                            text = "后台快照休眠 · 点击立即唤醒",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OpalPalette.TextPrimary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    OpalText(
                        text = "自动回收后台虚拟屏配额，唤醒保留应用现场",
                        fontSize = 11.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
            }
        }
    }
}

private fun resolveHostDisplayRefreshRate(view: TextureView): Float {
    val disp = view.display ?: return 60f
    val active = disp.mode?.refreshRate ?: disp.refreshRate
    return active.coerceIn(60f, 165f)
}

private class SurfaceBindHolder(private val windowId: Int) {
    private val generation = AtomicLong(0L)

    @Volatile
    private var surfaceTexture: SurfaceTexture? = null

    @Volatile
    private var boundSurface: Surface? = null

    fun attachAsync(st: SurfaceTexture, w: Int, h: Int, dpi: Int, fps: Float) {
        val gen = generation.incrementAndGet()
        surfaceTexture = st
        val surf = Surface(st)
        val prev = boundSurface
        boundSurface = surf
        try {
            prev?.release()
        } catch (_: Throwable) {
        }
        Thread({
            if (generation.get() != gen) {
                try {
                    surf.release()
                } catch (_: Throwable) {
                }
                return@Thread
            }
            BridgeClient.attachWindowSurface(windowId, surf, w, h, dpi, fps)
        }, "OpalSurfAttach-$windowId").start()
    }

    fun updateBufferSize(w: Int, h: Int) {
        try {
            surfaceTexture?.setDefaultBufferSize(w, h)
        } catch (_: Throwable) {
        }
    }

    fun releaseAll(notifyDaemon: Boolean) {
        val gen = generation.incrementAndGet()
        surfaceTexture = null
        val prev = boundSurface
        boundSurface = null
        if (notifyDaemon) {
            Thread({
                // Only detach if no newer Surface attached in the meantime
                if (generation.get() == gen) {
                    BridgeClient.detachWindowSurface(windowId, releaseDisplay = false)
                }
                try {
                    prev?.release()
                } catch (_: Throwable) {
                }
            }, "OpalSurfDetach-$windowId").start()
        } else {
            try {
                prev?.release()
            } catch (_: Throwable) {
            }
        }
    }
}

private fun forwardMotionEventToWindow(
    viewW: Int,
    viewH: Int,
    ev: MotionEvent?,
    win: CompositorWindow,
    headerHeightPx: Int,
    onFocusRequested: () -> Unit,
): Boolean {
    if (ev == null || viewW <= 0 || viewH <= 0) return false
    val masked = ev.actionMasked
    // Geometric Z-order occlusion guard: never accept clicks/hovers underneath a higher-Z floating window
    if (masked == MotionEvent.ACTION_DOWN ||
        masked == MotionEvent.ACTION_BUTTON_PRESS ||
        masked == MotionEvent.ACTION_HOVER_MOVE ||
        masked == MotionEvent.ACTION_SCROLL
    ) {
        val canvasX = win.frame.left + ev.x
        val canvasY = win.frame.top + headerHeightPx + ev.y
        if (CompositorGeometry.isPointOccludedByHigherWindow(
                target = win,
                canvasX = canvasX,
                canvasY = canvasY,
                visibleWindows = BridgeClient.sceneFlow.value.visibleWindows,
            )
        ) {
            return false
        }
    }
    if (!win.focused && (masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_BUTTON_PRESS)) {
        onFocusRequested()
    }
    if (!win.isLive || win.displayId <= 0) return true

    val count = ev.pointerCount.coerceIn(1, 10)
    val ids = IntArray(count)
    val toolTypes = IntArray(count)
    val xs = FloatArray(count)
    val ys = FloatArray(count)
    val pressures = FloatArray(count)

    val cw = win.contentWidth.coerceAtLeast(2)
    val ch = win.contentHeight.coerceAtLeast(2)
    for (i in 0 until count) {
        ids[i] = ev.getPointerId(i)
        toolTypes[i] = ev.getToolType(i)
        xs[i] = CompositorGeometry.mapViewToDisplayCoord(ev.getX(i), viewW, cw)
        ys[i] = CompositorGeometry.mapViewToDisplayCoord(ev.getY(i), viewH, ch)
        pressures[i] = ev.getPressure(i)
    }

    val isMouse = (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
        toolTypes[0] == MotionEvent.TOOL_TYPE_MOUSE
    val effSource = when {
        isMouse -> InputDevice.SOURCE_MOUSE
        toolTypes[0] == MotionEvent.TOOL_TYPE_STYLUS -> InputDevice.SOURCE_STYLUS
        else -> InputDevice.SOURCE_TOUCHSCREEN
    }
    val hScroll = if (masked == MotionEvent.ACTION_SCROLL) ev.getAxisValue(MotionEvent.AXIS_HSCROLL) else 0f
    val vScroll = if (masked == MotionEvent.ACTION_SCROLL) ev.getAxisValue(MotionEvent.AXIS_VSCROLL) else 0f
    val actionButton = if (masked == MotionEvent.ACTION_BUTTON_PRESS || masked == MotionEvent.ACTION_BUTTON_RELEASE) {
        ev.actionButton
    } else {
        0
    }
    if (masked == MotionEvent.ACTION_BUTTON_PRESS && actionButton == MotionEvent.BUTTON_BACK) {
        BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.SEND_BACK)
        return true
    }

    BridgeClient.injectWindowMotionOneway(
        windowId = win.windowId,
        action = ev.action,
        downTime = ev.downTime,
        eventTime = ev.eventTime,
        source = effSource,
        buttonState = ev.buttonState,
        actionButton = actionButton,
        hScroll = hScroll,
        vScroll = vScroll,
        pointerIds = ids,
        toolTypes = toolTypes,
        xs = xs,
        ys = ys,
        pressures = pressures,
    )
    return true
}

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
import androidx.compose.ui.input.pointer.PointerType
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
import java.util.concurrent.Executors
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
    val lastHoverEventTime = remember(window.windowId) { AtomicLong(-1L) }

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
            // Intercept Compose PointerEventType.Scroll AND unpressed Mouse Hover directly:
            // AndroidComposeView intercepts GenericMotionEvents (ACTION_SCROLL & ACTION_HOVER_MOVE)
            // into Compose PointerInput rather than AndroidView.onGenericMotionListener whenever
            // ancestor pointerInput modifiers exist. Forwarding hover here keeps the VirtualDisplay
            // cursor continuously synchronized before every USB mouse click!
            .pointerInput(window.windowId, "mouse_hover_and_scroll") {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val change = ev.changes.firstOrNull() ?: continue
                        if (ev.type == PointerEventType.Scroll) {
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
                        } else if (
                            change.type == PointerType.Mouse &&
                            !change.pressed &&
                            !change.previousPressed &&
                            (ev.type == PointerEventType.Move ||
                                ev.type == PointerEventType.Enter ||
                                ev.type == PointerEventType.Exit)
                        ) {
                            val win = currentWin
                            if (win.isLive && win.displayId > 0 && size.width > 0 && size.height > 0) {
                                val canvasX = win.frame.left + change.position.x
                                val canvasY = win.frame.top + currentHeaderPx + change.position.y
                                val occluded = ev.type != PointerEventType.Exit && CompositorGeometry.isPointOccludedByHigherWindow(
                                    target = win,
                                    canvasX = canvasX,
                                    canvasY = canvasY,
                                    visibleWindows = BridgeClient.sceneFlow.value.visibleWindows,
                                )
                                val evtTime = change.uptimeMillis.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
                                if (!occluded && lastHoverEventTime.getAndSet(evtTime) != evtTime) {
                                    val hoverAction = when (ev.type) {
                                        PointerEventType.Enter -> MotionEvent.ACTION_HOVER_ENTER
                                        PointerEventType.Exit -> MotionEvent.ACTION_HOVER_EXIT
                                        else -> MotionEvent.ACTION_HOVER_MOVE
                                    }
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
                                    BridgeClient.injectWindowMotionOneway(
                                        windowId = win.windowId,
                                        action = hoverAction,
                                        downTime = evtTime,
                                        eventTime = evtTime,
                                        source = InputDevice.SOURCE_MOUSE,
                                        buttonState = 0,
                                        actionButton = 0,
                                        hScroll = 0f,
                                        vScroll = 0f,
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
                    FixedBufferTextureView(ctx).apply {
                        isOpaque = false
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                val w = currentWin.contentWidth.coerceAtLeast(240)
                                val h = currentWin.contentHeight.coerceAtLeast(200)
                                val dpi = currentWin.contentDpi.coerceAtLeast(120)
                                val activeFps = resolveHostDisplayRefreshRate(this@apply)
                                syncBufferSize(w, h)
                                post { syncBufferSize(w, h) }
                                holderState.attachAsync(st, w, h, dpi, activeFps)
                            }

                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                                // Keep buffer dimensions strictly synced with the daemon's committed VirtualDisplay
                                // resolution so transient GPU resize drags or TextureView relayouts never clobber
                                // SurfaceFlinger's BufferQueue back to view bounds (which clips/zooms content).
                                val cw = currentWin.contentWidth.coerceAtLeast(240)
                                val ch = currentWin.contentHeight.coerceAtLeast(200)
                                syncBufferSize(cw, ch)
                                post { syncBufferSize(cw, ch) }
                                holderState.updateBufferSize(cw, ch)
                            }

                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                OpalLogStore.i("Surface", "TextureView destroyed wid=${currentWin.windowId} hadFrame=$hasRenderedLiveFrame")
                                hasRenderedLiveFrame = false
                                holderState.onTextureDestroyed(st)
                                // Return false so OpalSurfBind releases Surface BEFORE SurfaceTexture,
                                // preventing native BufferQueue/writeToParcel use-after-free races.
                                return false
                            }

                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                                if (!hasRenderedLiveFrame) {
                                    val cw = currentWin.contentWidth.coerceAtLeast(240)
                                    val ch = currentWin.contentHeight.coerceAtLeast(200)
                                    syncBufferSize(cw, ch)
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
                            handleGenericMotionForWindow(
                                v.width, v.height, ev, currentWin, currentHeaderPx, lastHoverEventTime, currentFocusCb,
                            )
                        }
                    }
                },
                update = { textureView ->
                    val cw = window.contentWidth.coerceAtLeast(240)
                    val ch = window.contentHeight.coerceAtLeast(200)
                    textureView.syncBufferSize(cw, ch)
                    // Keep native Android View Z-order strictly aligned with Compose zIndex via translationZ
                    // (never call bringToFront() here: mutating AndroidViewsHandler child array mid-click
                    // aborts the active touch target and requires a double-click on unfocused windows!).
                    val zVal = window.zOrder.toFloat()
                    textureView.translationZ = zVal
                    (textureView.parent as? View)?.let { holderView ->
                        holderView.translationZ = zVal
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

/**
 * `TextureView` subclass that preserves the committed VirtualDisplay `SurfaceTexture` buffer size
 * across Android visibility/layout/resume transitions (preventing Android/OEM `TextureView`
 * internals from silently resetting `defaultBufferSize` back to view pixel dimensions and zooming
 * or clipping VirtualDisplay content).
 */
private class FixedBufferTextureView(context: android.content.Context) : TextureView(context) {
    @Volatile
    private var targetBufferWidth: Int = 0

    @Volatile
    private var targetBufferHeight: Int = 0

    fun syncBufferSize(w: Int, h: Int) {
        targetBufferWidth = w
        targetBufferHeight = h
        if (w > 0 && h > 0) {
            surfaceTexture?.setDefaultBufferSize(w, h)
        }
    }

    private fun reapplyBufferSize() {
        val w = targetBufferWidth
        val h = targetBufferHeight
        if (w > 0 && h > 0) {
            surfaceTexture?.setDefaultBufferSize(w, h)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reapplyBufferSize()
        post { reapplyBufferSize() }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) {
            reapplyBufferSize()
            post { reapplyBufferSize() }
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            reapplyBufferSize()
            post { reapplyBufferSize() }
        }
    }
}

private fun resolveHostDisplayRefreshRate(view: TextureView): Float {
    val disp = view.display ?: return 60f
    val active = disp.mode?.refreshRate ?: disp.refreshRate
    return active.coerceIn(60f, 165f)
}

/**
 * Pure JVM-testable per-window Surface ownership & generation gate.
 * Prevents stale `onDispose` callbacks from detaching a newly mounted `TextureView` for the same
 * `windowId` and cancels queued attach tasks if the `SurfaceTexture` is destroyed first.
 */
internal class WindowSurfaceGenerationGate {
    private val lock = Any()
    private var nextOpGen = 0L
    private val activeHolderByWindow = HashMap<Int, Long>()
    private val latestOpGenByWindow = HashMap<Int, Long>()
    private val pendingDaemonDetachByWindow = HashMap<Int, Boolean>()

    fun onAttachRequested(windowId: Int, holderToken: Long): Long = synchronized(lock) {
        val gen = ++nextOpGen
        activeHolderByWindow[windowId] = holderToken
        latestOpGenByWindow[windowId] = gen
        pendingDaemonDetachByWindow[windowId] = true
        gen
    }

    fun isAttachStillValid(windowId: Int, holderToken: Long, opGen: Long): Boolean = synchronized(lock) {
        activeHolderByWindow[windowId] == holderToken && latestOpGenByWindow[windowId] == opGen
    }

    /**
     * Invalidates queued attaches for [holderToken] and returns `true` iff the daemon's
     * VirtualDisplay should detach its Surface immediately BEFORE `st.release()` abandons the
     * BufferQueue consumer.
     */
    fun onTextureDestroyed(windowId: Int, holderToken: Long): Boolean = synchronized(lock) {
        if (activeHolderByWindow[windowId] == holderToken) {
            latestOpGenByWindow[windowId] = ++nextOpGen
            pendingDaemonDetachByWindow.remove(windowId) == true
        } else {
            false
        }
    }

    /**
     * Returns `true` iff [holderToken] is still the active owner of [windowId] and has not yet
     * detached the daemon surface in [onTextureDestroyed].
     */
    fun onHolderDisposed(windowId: Int, holderToken: Long): Boolean = synchronized(lock) {
        if (activeHolderByWindow[windowId] == holderToken) {
            activeHolderByWindow.remove(windowId)
            latestOpGenByWindow[windowId] = ++nextOpGen
            pendingDaemonDetachByWindow.remove(windowId) == true
        } else {
            false
        }
    }
}

private object WindowSurfaceCoordinator {
    private val nextHolderToken = AtomicLong(1L)
    val gate = WindowSurfaceGenerationGate()
    private val bindExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OpalSurfBind").apply { isDaemon = true }
    }
    // Confined strictly to bindExec so Surface creation, Parcel marshaling, and release() never race.
    private val boundSurfaceByHolder = HashMap<Long, Surface>()

    fun allocateHolderToken(): Long = nextHolderToken.getAndIncrement()

    fun attachAsync(
        windowId: Int,
        holderToken: Long,
        st: SurfaceTexture,
        w: Int,
        h: Int,
        dpi: Int,
        fps: Float,
    ) {
        val opGen = gate.onAttachRequested(windowId, holderToken)
        bindExec.execute {
            if (!gate.isAttachStillValid(windowId, holderToken, opGen)) {
                return@execute
            }
            val surf = try {
                st.setDefaultBufferSize(w, h)
                Surface(st)
            } catch (_: Throwable) {
                return@execute
            }
            val prev = boundSurfaceByHolder.put(holderToken, surf)
            try {
                prev?.release()
            } catch (_: Throwable) {
            }
            if (!gate.isAttachStillValid(windowId, holderToken, opGen)) {
                boundSurfaceByHolder.remove(holderToken, surf)
                try {
                    surf.release()
                } catch (_: Throwable) {
                }
                return@execute
            }
            BridgeClient.attachWindowSurface(windowId, surf, w, h, dpi, fps)
        }
    }

    fun releaseDestroyedTexture(windowId: Int, holderToken: Long, st: SurfaceTexture) {
        val shouldDetachFirst = gate.onTextureDestroyed(windowId, holderToken)
        bindExec.execute {
            // Detach VirtualDisplay producer BEFORE releasing Surface & SurfaceTexture consumer
            // so high-FPS MediaCodec/GLES producers never hit an abandoned BufferQueue.
            if (shouldDetachFirst) {
                BridgeClient.detachWindowSurface(windowId, releaseDisplay = false)
            }
            val prev = boundSurfaceByHolder.remove(holderToken)
            try {
                prev?.release()
            } catch (_: Throwable) {
            }
            try {
                st.release()
            } catch (_: Throwable) {
            }
        }
    }

    fun disposeHolder(windowId: Int, holderToken: Long, notifyDaemon: Boolean) {
        val shouldDetach = gate.onHolderDisposed(windowId, holderToken) && notifyDaemon
        bindExec.execute {
            if (shouldDetach) {
                BridgeClient.detachWindowSurface(windowId, releaseDisplay = false)
            }
            val prev = boundSurfaceByHolder.remove(holderToken)
            try {
                prev?.release()
            } catch (_: Throwable) {
            }
        }
    }
}

private class SurfaceBindHolder(private val windowId: Int) {
    private val holderToken = WindowSurfaceCoordinator.allocateHolderToken()

    @Volatile
    private var surfaceTexture: SurfaceTexture? = null

    fun attachAsync(st: SurfaceTexture, w: Int, h: Int, dpi: Int, fps: Float) {
        surfaceTexture = st
        WindowSurfaceCoordinator.attachAsync(windowId, holderToken, st, w, h, dpi, fps)
    }

    fun updateBufferSize(w: Int, h: Int) {
        try {
            surfaceTexture?.setDefaultBufferSize(w, h)
        } catch (_: Throwable) {
        }
    }

    fun onTextureDestroyed(st: SurfaceTexture) {
        if (surfaceTexture === st) {
            surfaceTexture = null
        }
        WindowSurfaceCoordinator.releaseDestroyedTexture(windowId, holderToken, st)
    }

    fun releaseAll(notifyDaemon: Boolean) {
        surfaceTexture = null
        WindowSurfaceCoordinator.disposeHolder(windowId, holderToken, notifyDaemon)
    }
}

private fun isMouseMotionEvent(ev: MotionEvent): Boolean =
    (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
        (ev.pointerCount > 0 && ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE)

private fun handleGenericMotionForWindow(
    viewW: Int,
    viewH: Int,
    ev: MotionEvent?,
    win: CompositorWindow,
    headerHeightPx: Int,
    lastHoverEventTime: AtomicLong,
    onFocusRequested: () -> Unit,
): Boolean {
    if (ev == null || viewW <= 0 || viewH <= 0) return false
    val masked = ev.actionMasked
    if (masked == MotionEvent.ACTION_BUTTON_PRESS || masked == MotionEvent.ACTION_BUTTON_RELEASE) {
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
        if (masked == MotionEvent.ACTION_BUTTON_PRESS) {
            if (!win.focused) onFocusRequested()
            if (ev.actionButton == MotionEvent.BUTTON_BACK && win.isLive && win.displayId > 0) {
                BridgeClient.sendWindowActionAsync(win.windowId, BridgeProtocol.WinAction.SEND_BACK)
                return true
            }
        }
        // Consume mouse button generic events locally without injecting invalid ACTION_BUTTON_PRESS/RELEASE
        // into IInputManager (actual click press/release is delivered cleanly via onTouchListener ACTION_DOWN/UP).
        return isMouseMotionEvent(ev)
    }
    if (masked == MotionEvent.ACTION_HOVER_ENTER ||
        masked == MotionEvent.ACTION_HOVER_MOVE ||
        masked == MotionEvent.ACTION_HOVER_EXIT
    ) {
        if (lastHoverEventTime.getAndSet(ev.eventTime) == ev.eventTime) {
            return true
        }
        return forwardMotionEventToWindow(viewW, viewH, ev, win, headerHeightPx, onFocusRequested)
    }
    if (masked == MotionEvent.ACTION_SCROLL) {
        return forwardMotionEventToWindow(viewW, viewH, ev, win, headerHeightPx, onFocusRequested)
    }
    return false
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
        masked == MotionEvent.ACTION_HOVER_ENTER ||
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
    if (!win.focused && masked == MotionEvent.ACTION_DOWN) {
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

    val isMouse = isMouseMotionEvent(ev)
    val effSource = when {
        isMouse -> InputDevice.SOURCE_MOUSE
        toolTypes[0] == MotionEvent.TOOL_TYPE_STYLUS -> InputDevice.SOURCE_STYLUS
        else -> InputDevice.SOURCE_TOUCHSCREEN
    }
    val normButtonState = if (
        isMouse &&
        (masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_MOVE) &&
        ev.buttonState == 0
    ) {
        MotionEvent.BUTTON_PRIMARY
    } else {
        ev.buttonState
    }
    val hScroll = if (masked == MotionEvent.ACTION_SCROLL) ev.getAxisValue(MotionEvent.AXIS_HSCROLL) else 0f
    val vScroll = if (masked == MotionEvent.ACTION_SCROLL) ev.getAxisValue(MotionEvent.AXIS_VSCROLL) else 0f

    BridgeClient.injectWindowMotionOneway(
        windowId = win.windowId,
        action = ev.action,
        downTime = ev.downTime,
        eventTime = ev.eventTime,
        source = effSource,
        buttonState = normButtonState,
        actionButton = 0,
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

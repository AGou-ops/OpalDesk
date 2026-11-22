package io.github.lengsukq.opaldesk.overlay.mirror

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.SideMouseCaptureActivity
import io.github.lengsukq.opaldesk.overlay.OverlayDisplayContext
import io.github.lengsukq.opaldesk.overlay.OverlayHost
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.CapturedMouseAction
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Self-contained Display 0 physical mouse edge-crossing sensor + Android Pointer Capture +
 * coalescing relative-motion queue for Native Mirror sidebars.
 *
 * Uses [OverlayDisplayContext.resolveDefaultDisplay] and restores sensor LP in place on capture exit.
 */
internal class MirrorEdgeCaptureController(
    private val host: OverlayHost,
    private val physSizeProvider: () -> Pair<Int, Int>,
) {
    companion object {
        private const val TAG = "ExtMirrorEdgeCap"
        private const val SENSOR_STRIP_PX = 5
        private const val CAPTURE_FALLBACK_DELAY_MS = 280L
        private const val REACQUIRE_INITIAL_DELAY_MS = 18L
        private const val REACQUIRE_CONFIRM_DELAY_MS = 65L
        private val REACQUIRE_DELAYS_MS = longArrayOf(20L, 45L, 90L, 150L, 240L, 360L, 520L, 720L)

        private const val IDLE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        private const val ACTIVE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        private val moveLock = Any()
        private var pendingDx = 0f
        private var pendingDy = 0f
        private var moveInFlight = false

        @Volatile
        private var activeController: MirrorEdgeCaptureController? = null

        fun resetCapturedMouseMoveState() = synchronized(moveLock) {
            pendingDx = 0f; pendingDy = 0f; moveInFlight = false
        }

        fun handleCapturedPointerEventStatic(ctx: Context?, ev: MotionEvent?, exec: ExecutorService?, onExit: Runnable?): Boolean {
            if (ev == null || exec == null || exec.isShutdown) return false
            return handlePointerEvent(ctx, ev, { exec.isShutdown }, { exec.execute(it) }, onExit)
        }

        private fun handlePointerEvent(
            ctx: Context?, ev: MotionEvent?, isShutdown: () -> Boolean, dispatch: (Runnable) -> Unit, onExit: Runnable?,
        ): Boolean {
            if (ev == null || isShutdown()) return false
            activeController?.takeUnless { it.host.isDestroyed }?.let { c ->
                if (c.sensor1?.hasPointerCapture() == true) c.pointerCaptured = true
            }
            return when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> { enqueueCoalescedMove(ctx, ev, isShutdown, dispatch, onExit); true }
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> dispatchBtn(ev.actionButton, true, dispatch)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> dispatchBtn(ev.actionButton, false, dispatch)
                MotionEvent.ACTION_SCROLL -> {
                    val h = ev.getAxisValue(MotionEvent.AXIS_HSCROLL); val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    safeDispatch(dispatch) { BridgeClient.sendCapturedMirrorMouse(CapturedMouseAction.SCROLL, h, 0f, v) }; true
                }
                else -> false
            }
        }

        private fun enqueueCoalescedMove(
            ctx: Context?, ev: MotionEvent, isShutdown: () -> Boolean, dispatch: (Runnable) -> Unit, onExit: Runnable?,
        ) {
            var rx = ev.getAxisValue(MotionEvent.AXIS_RELATIVE_X); var ry = ev.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
            if (rx == 0f && ry == 0f && (ev.source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE) {
                rx = ev.x; ry = ev.y
            }
            if (rx == 0f && ry == 0f) return
            val launch = synchronized(moveLock) {
                pendingDx += rx; pendingDy += ry
                if (!moveInFlight) { moveInFlight = true; true } else false
            }
            if (!launch) return
            try {
                dispatch(Runnable {
                    try {
                        while (!isShutdown()) {
                            var dx = 0f; var dy = 0f
                            val done = synchronized(moveLock) {
                                dx = pendingDx; dy = pendingDy; pendingDx = 0f; pendingDy = 0f
                                if (abs(dx) < 0.01f && abs(dy) < 0.01f) { moveInFlight = false; true } else false
                            }
                            if (done) break
                            val cur = activeController?.takeUnless { it.host.isDestroyed }
                            val userMult = AppPrefs.getMirrorCursorSpeedMult(ctx ?: cur?.host?.serviceContext)
                            val (pw, ph) = cur?.physSizeProvider() ?: (0 to 0)
                            val resMult = if (pw > 0 && ph > 0) (min(pw, ph) / 1440f).coerceIn(0.85f, 2.2f) else 1f
                            val gain = userMult * resMult * (1f + min(1.45f, hypot(dx, dy) * 0.045f))
                            val res = BridgeClient.sendCapturedMirrorMouse(CapturedMouseAction.MOVE_REL, dx * gain, dy * gain, 0f)
                            if (res[0] == 0) { resetCapturedMouseMoveState(); onExit?.run(); break }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Coalesced captured mouse loop failed", t)
                    } finally {
                        synchronized(moveLock) { moveInFlight = false }
                    }
                })
            } catch (t: RejectedExecutionException) {
                Log.d(TAG, "Captured move rejected: ${t.message}")
                resetCapturedMouseMoveState()
            }
        }

        private fun dispatchBtn(btn: Int, down: Boolean, dispatch: (Runnable) -> Unit): Boolean = when (btn) {
            0, MotionEvent.BUTTON_PRIMARY -> {
                safeDispatch(dispatch) { BridgeClient.sendCapturedMirrorMouse(if (down) CapturedMouseAction.LEFT_DOWN else CapturedMouseAction.LEFT_UP, 0f, 0f, 0f) }; true
            }
            MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY -> {
                safeDispatch(dispatch) { BridgeClient.sendCapturedMirrorMouse(if (down) CapturedMouseAction.EXTRA_BUTTON_DOWN else CapturedMouseAction.EXTRA_BUTTON_UP, btn.toFloat(), 0f, 0f) }; true
            }
            else -> false
        }

        private fun safeDispatch(dispatch: (Runnable) -> Unit, block: () -> Unit) {
            try { dispatch(Runnable(block)) } catch (t: RejectedExecutionException) { Log.d(TAG, "Capture dispatch rejected: ${t.message}") }
        }
    }

    private var sensor1: FrameLayout? = null
    private var sensor2: FrameLayout? = null
    private var d0Wm: WindowManager? = null
    private var sensor1Lp: WindowManager.LayoutParams? = null
    private var sensor1Rect = Rect()
    private var capturing = false
    private var pointerCaptured = false
    private var overlayCaptureSupported = false
    private var reacquireAttempts = 0
    private val hoverInFlight = AtomicBoolean(false)
    private var sensorKey = ""

    private val fallbackTask = Runnable {
        if (capturing && !pointerCaptured && !overlayCaptureSupported) {
            revertOverlayToSensor()
            SideMouseCaptureActivity.launch(host.serviceContext)
        }
    }

    private val reacquireTask: Runnable = object : Runnable {
        override fun run() {
            val sv = sensor1; val wm = d0Wm; val lp = sensor1Lp
            if (host.isDestroyed || !capturing || sv == null || wm == null || lp == null) return
            if (sv.hasPointerCapture()) { markCaptured(); return }
            if (reacquireAttempts >= REACQUIRE_DELAYS_MS.size) { exitCapture(); return }

            val nextDelay = REACQUIRE_DELAYS_MS[reacquireAttempts++]
            host.safeInputExecute {
                if (BridgeClient.sendCapturedMirrorMouse(CapturedMouseAction.REASSERT_D0_FOCUS, 0f, 0f, 0f)[0] == 0) {
                    host.safePost { revertOverlayToSensor() }
                }
            }
            try {
                if (!sv.hasWindowFocus()) {
                    lp.flags = ACTIVE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    wm.updateViewLayout(sv, lp)
                }
                lp.flags = ACTIVE_FLAGS; wm.updateViewLayout(sv, lp)
                sv.isFocusable = true; sv.isFocusableInTouchMode = true; sv.requestFocus(); sv.requestPointerCapture()
                if (sv.hasPointerCapture()) { markCaptured(); return }
            } catch (t: Throwable) {
                Log.w(TAG, "Pointer capture reacquire failed", t)
            }
            if (capturing && !pointerCaptured) {
                host.handler.removeCallbacks(this); host.handler.postDelayed(this, nextDelay)
            }
        }
    }

    fun onEdgeCaptureFromDaemon(enter: Boolean) = host.safePost { if (enter) enterCapture() else exitCapture() }

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    fun sync(st: BridgeClient.Status) {
        activeController = this
        if (st.mirrorMode != 1 || st.sideVdId <= 0 || st.mirrorSideMode <= 0 || st.d0W <= 0 || st.d0H <= 0 || st.mirW <= 0 || st.mirH <= 0) {
            detach(); return
        }
        val (r1, r2) = computeSensorRects(st) ?: run { detach(); return }
        if (capturing) return

        val resolved = OverlayDisplayContext.resolveDefaultDisplay(host.serviceContext, host.displayManager, preferA11y = true)
            ?: run { detach(); return }
        val key = "${r1.left},${r1.top},${r1.width()},${r1.height()};${r2?.left ?: -1},${r2?.top ?: -1},${r2?.width() ?: 0},${r2?.height() ?: 0},${resolved.windowType}"
        if (sensor1 != null && key == sensorKey) return

        detach()
        try {
            val wm0 = resolved.windowManager
            val lp1 = buildSensorLp(r1, resolved.windowType, "ExtMirEdgeSensor")
            val sv1 = createPrimarySensor(resolved.uiContext)
            attachHover(sv1, lp1)
            d0Wm = wm0; sensor1Lp = lp1; sensor1Rect = Rect(r1); sensor1 = sv1
            wm0.addView(sv1, lp1)

            if (r2 != null) {
                val lp2 = buildSensorLp(r2, resolved.windowType, "ExtMirEdgeSensor2")
                val sv2 = FrameLayout(resolved.uiContext).apply { setBackgroundColor(Color.TRANSPARENT) }
                attachHover(sv2, lp2)
                sensor2 = sv2; wm0.addView(sv2, lp2)
            }
            sensorKey = key
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to attach Display 0 edge sensor", t)
            detach()
        }
    }

    private fun computeSensorRects(st: BridgeClient.Status): Pair<Rect, Rect?>? {
        val m = MirrorWingLayoutBuilder.MIN_WING_PX
        val hasL = st.mirX >= m; val hasR = (st.physW - (st.mirX + st.mirW)) >= m
        val hasT = (st.physW - st.mirW < m) && st.mirY >= m
        val hasB = (st.physW - st.mirW < m) && (st.physH - (st.mirY + st.mirH)) >= m
        if (!hasL && !hasR && !hasT && !hasB) return null
        val s = SENSOR_STRIP_PX
        return when {
            hasL && hasR -> Rect(0, 0, s, st.d0H) to Rect(max(0, st.d0W - s), 0, st.d0W, st.d0H)
            hasL -> Rect(0, 0, s, st.d0H) to null
            hasR -> Rect(max(0, st.d0W - s), 0, st.d0W, st.d0H) to null
            hasT && hasB -> Rect(0, 0, st.d0W, s) to Rect(0, max(0, st.d0H - s), st.d0W, st.d0H)
            hasT -> Rect(0, 0, st.d0W, s) to null
            else -> Rect(0, max(0, st.d0H - s), st.d0W, st.d0H) to null
        }
    }

    @Suppress("RtlHardcoded")
    private fun buildSensorLp(r: Rect, winType: Int, title: String) =
        WindowManager.LayoutParams(r.width(), r.height(), winType, IDLE_FLAGS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT; x = r.left; y = r.top; this.title = title
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun createPrimarySensor(ctx: Context): FrameLayout = object : FrameLayout(ctx) {
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            super.onWindowFocusChanged(hasFocus)
            if (hasFocus && capturing) {
                host.handler.removeCallbacks(reacquireTask)
                post {
                    if (!capturing) return@post
                    try { requestPointerCapture(); if (hasPointerCapture()) markCaptured() }
                    catch (t: Throwable) { Log.w(TAG, "requestPointerCapture on focus failed", t) }
                }
            } else if (!hasFocus && capturing) {
                pointerCaptured = false; scheduleReacquire()
            }
        }
        override fun onPointerCaptureChange(hasCapture: Boolean) {
            super.onPointerCaptureChange(hasCapture)
            if (hasCapture) {
                markCaptured(); host.handler.removeCallbacks(fallbackTask); host.handler.removeCallbacks(reacquireTask)
            } else if (capturing) {
                pointerCaptured = false; scheduleReacquire()
            }
        }
        override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
            if (capturing && event?.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_BACK)
            ) {
                host.safePost { exitCapture() }; return true
            }
            return super.dispatchKeyEvent(event)
        }
    }.apply {
        setBackgroundColor(Color.TRANSPARENT)
        setOnTouchListener { _, ev ->
            if (capturing && ev != null) {
                val outside = ev.actionMasked == MotionEvent.ACTION_OUTSIDE
                val finger = (ev.source and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN ||
                    (ev.pointerCount > 0 && ev.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE)
                if (outside || finger) exitCapture()
            }
            false
        }
        setOnCapturedPointerListener { _, ev ->
            handlePointerEvent(host.serviceContext, ev, { host.isDestroyed }, { host.safeInputExecute(it) }, Runnable { host.safePost { revertOverlayToSensor() } })
        }
    }

    private fun attachHover(view: View, lp: WindowManager.LayoutParams) {
        view.setOnGenericMotionListener { _, ev ->
            if (!host.isDestroyed && !capturing && ev?.actionMasked == MotionEvent.ACTION_HOVER_MOVE &&
                (ev.source and InputDevice.SOURCE_CLASS_POINTER) != 0 && hoverInFlight.compareAndSet(false, true)
            ) {
                val px = lp.x + ev.x; val py = lp.y + ev.y
                host.safeInputExecute {
                    try { if (BridgeClient.notifyMirrorEdgeHover(px, py)[0] == 1) host.safePost { enterCapture() } }
                    finally { hoverInFlight.set(false) }
                }
            }
            false
        }
    }

    private fun markCaptured() { pointerCaptured = true; overlayCaptureSupported = true; reacquireAttempts = 0 }

    private fun scheduleReacquire() {
        if (host.isDestroyed || !capturing) return
        reacquireAttempts = 0; host.handler.removeCallbacks(reacquireTask)
        host.handler.postDelayed(reacquireTask, REACQUIRE_INITIAL_DELAY_MS)
    }

    private fun notifyDaemonExitIfNeeded() {
        if (capturing || pointerCaptured) {
            host.safeInputExecute { BridgeClient.sendCapturedMirrorMouse(CapturedMouseAction.EXIT_CAPTURE, 0f, 0f, 0f) }
        }
    }

    private fun enterCapture() {
        if (host.isDestroyed || capturing) return
        val sv = sensor1; val wm = d0Wm; val lp = sensor1Lp
        if (sv == null || wm == null || lp == null) { SideMouseCaptureActivity.launch(host.serviceContext); return }
        capturing = true; pointerCaptured = false; reacquireAttempts = 0; host.handler.removeCallbacks(reacquireTask)
        try {
            lp.flags = ACTIVE_FLAGS; lp.width = 1; lp.height = 1; lp.x = 0; lp.y = 0
            wm.updateViewLayout(sv, lp)
            sv.isFocusable = true; sv.isFocusableInTouchMode = true; sv.requestFocus()
            sv.post {
                val cur = sensor1 ?: return@post
                if (!capturing) return@post
                try { cur.requestPointerCapture(); if (cur.hasPointerCapture()) markCaptured() }
                catch (t: Throwable) { Log.w(TAG, "Deferred requestPointerCapture failed", t) }
            }
            host.handler.removeCallbacks(fallbackTask)
            if (!overlayCaptureSupported) host.handler.postDelayed(fallbackTask, CAPTURE_FALLBACK_DELAY_MS)
            else host.handler.postDelayed(reacquireTask, REACQUIRE_CONFIRM_DELAY_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "enterCapture failed; falling back to activity", t)
            notifyDaemonExitIfNeeded(); revertOverlayToSensor(); SideMouseCaptureActivity.launch(host.serviceContext)
        }
    }

    /** Restores sensor flags & edge-strip coordinates IN PLACE via `updateViewLayout`. */
    private fun revertOverlayToSensor() {
        capturing = false; pointerCaptured = false; reacquireAttempts = 0
        resetCapturedMouseMoveState(); host.handler.removeCallbacks(fallbackTask); host.handler.removeCallbacks(reacquireTask)
        val sv = sensor1 ?: return; val wm = d0Wm ?: return; val lp = sensor1Lp ?: return
        try { sv.releasePointerCapture() } catch (t: Throwable) { Log.d(TAG, "releasePointerCapture failed: ${t.message}") }
        try {
            sv.isFocusable = false; sv.isFocusableInTouchMode = false
            lp.flags = IDLE_FLAGS; lp.x = sensor1Rect.left; lp.y = sensor1Rect.top
            lp.width = max(1, sensor1Rect.width()); lp.height = max(1, sensor1Rect.height())
            wm.updateViewLayout(sv, lp)
        } catch (t: Throwable) {
            Log.w(TAG, "In-place sensor restore failed; detaching for fresh sync", t)
            detach()
        }
    }

    private fun exitCapture() {
        notifyDaemonExitIfNeeded(); SideMouseCaptureActivity.finishActiveIfAny(); revertOverlayToSensor()
    }

    fun detach() {
        notifyDaemonExitIfNeeded(); SideMouseCaptureActivity.finishActiveIfAny(); resetCapturedMouseMoveState()
        capturing = false; pointerCaptured = false; reacquireAttempts = 0; sensorKey = ""; hoverInFlight.set(false)
        host.handler.removeCallbacks(fallbackTask); host.handler.removeCallbacks(reacquireTask)
        val wm = d0Wm
        sensor2?.let { try { wm?.removeViewImmediate(it) } catch (t: Throwable) { Log.d(TAG, "removeViewImmediate(sensor2) failed: ${t.message}") } }
        sensor1?.let { v ->
            try { v.releasePointerCapture() } catch (t: Throwable) { Log.d(TAG, "releasePointerCapture failed: ${t.message}") }
            try { wm?.removeViewImmediate(v) } catch (t: Throwable) { Log.d(TAG, "removeViewImmediate(sensor1) failed: ${t.message}") }
        }
        sensor1 = null; sensor2 = null; sensor1Lp = null; d0Wm = null
        if (host.isDestroyed && activeController === this) activeController = null
    }
}

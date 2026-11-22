package io.github.lengsukq.opaldesk.touchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Ultra-low-latency multi-touch trackpad canvas supporting relative pointer acceleration,
 * tap-to-click, double-tap-and-hold drag, 2-finger scroll/right-click, and a right-edge scroll rail.
 */
class NativeTrackpadView(context: Context) : View(context) {

    interface Listener {
        fun onMoveRel(dx: Float, dy: Float)
        fun onLeftClick()
        fun onLeftDown()
        fun onLeftUp()
        fun onScroll(hx: Float, vy: Float)
        fun onRightClickOrBack()
        fun onReleaseDragLock()
    }

    private enum class GestureMode {
        IDLE,
        SINGLE_FINGER,
        TWO_FINGER,
        ONE_FINGER_DRAG,
        RIGHT_EDGE_SCROLL,
    }

    var listener: Listener? = null
    var speedMultiplier: Float = 1.6f
    var virtualWidth: Int = 2560
    var isDragLocked: Boolean = false

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val railTickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgDrawable = GradientDrawable()

    private var mode = GestureMode.IDLE
    private var downTime = 0L
    private var twoFingerDownTime = 0L
    private var twoFingerScrolled = false
    private var lastTapUpTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var last2X = 0f
    private var last2Y = 0f
    private var totalMove = 0f
    private var scrollAccumV = 0f
    private var scrollAccumH = 0f
    private var touchVisX = -1f
    private var touchVisY = -1f

    private fun dpPx(v: Float): Float = v * resources.displayMetrics.density

    init {
        ringPaint.strokeWidth = dpPx(1.8f)
        railTickPaint.strokeWidth = dpPx(1.2f)
        background = bgDrawable
    }

    fun applyThemeColors(surfaceArgb: Int, strokeArgb: Int, accentArgb: Int, isDark: Boolean) {
        bgDrawable.setColor(surfaceArgb)
        bgDrawable.cornerRadius = dpPx(18f)
        bgDrawable.setStroke(dpPx(1f).toInt().coerceAtLeast(1), strokeArgb)

        val rgb = accentArgb and 0x00FFFFFF
        dotPaint.color = (if (isDark) 0x26000000 else 0x22000000) or rgb
        ringPaint.color = 0xBB000000.toInt() or rgb
        railPaint.color = (if (isDark) 0x16000000 else 0x12000000) or rgb
        railTickPaint.color = 0x44000000 or rgb
        invalidate()
    }

    fun resetTouchGestureState() {
        mode = GestureMode.IDLE
        touchVisX = -1f
        touchVisY = -1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        val railW = dpPx(26f).toInt()
        val step = dpPx(28f).toInt().coerceAtLeast(22)
        val dotRadius = dpPx(1.15f)
        var y = step
        while (y < h - step / 2) {
            var x = step
            while (x < w - railW - 8) {
                canvas.drawCircle(x.toFloat(), y.toFloat(), dotRadius, dotPaint)
                x += step
            }
            y += step
        }
        val railLeft = (w - railW).coerceAtLeast(0).toFloat()
        canvas.drawRoundRect(
            railLeft,
            dpPx(12f),
            w - dpPx(4f),
            h - dpPx(12f),
            dpPx(8f),
            dpPx(8f),
            railPaint,
        )
        var ty = dpPx(26f)
        while (ty < h - dpPx(26f)) {
            canvas.drawLine(railLeft + dpPx(6f), ty, w - dpPx(10f), ty, railTickPaint)
            ty += dpPx(15f)
        }
        if (touchVisX >= 0f && touchVisY >= 0f) {
            canvas.drawCircle(touchVisX, touchVisY, dpPx(20f), ringPaint)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val now = ev.eventTime
        val railW = dpPx(26f)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(ev, now, railW)
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(ev, now)
            MotionEvent.ACTION_MOVE -> handleMove(ev, railW)
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(now)
            MotionEvent.ACTION_UP -> handleUp(now)
            MotionEvent.ACTION_CANCEL -> handleCancel()
            else -> return super.onTouchEvent(ev)
        }
        return true
    }

    private fun handleDown(ev: MotionEvent, now: Long, railW: Float) {
        parent?.requestDisallowInterceptTouchEvent(true)
        downTime = now
        downX = ev.x
        downY = ev.y
        lastX = downX
        lastY = downY
        totalMove = 0f
        scrollAccumV = 0f
        scrollAccumH = 0f
        touchVisX = downX
        touchVisY = downY
        mode = when {
            !isDragLocked && (now - lastTapUpTime) < 240L -> {
                listener?.onLeftDown()
                GestureMode.ONE_FINGER_DRAG
            }
            downX >= width - railW && width > railW * 3f -> GestureMode.RIGHT_EDGE_SCROLL
            else -> GestureMode.SINGLE_FINGER
        }
        invalidate()
    }

    private fun handlePointerDown(ev: MotionEvent, now: Long) {
        if (ev.pointerCount != 2) return
        if (mode == GestureMode.ONE_FINGER_DRAG) listener?.onLeftUp()
        mode = GestureMode.TWO_FINGER
        twoFingerDownTime = now
        twoFingerScrolled = false
        last2X = (ev.getX(0) + ev.getX(1)) * 0.5f
        last2Y = (ev.getY(0) + ev.getY(1)) * 0.5f
        scrollAccumV = 0f
        scrollAccumH = 0f
        totalMove = 0f
    }

    private fun handleMove(ev: MotionEvent, railW: Float) {
        touchVisX = ev.getX(0)
        touchVisY = ev.getY(0)
        invalidate()

        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val scrollStepV = dpPx(16f)
        val scrollStepH = dpPx(20f)
        when (mode) {
            GestureMode.SINGLE_FINGER, GestureMode.ONE_FINGER_DRAG -> {
                val dx = ev.getX(0) - lastX
                val dy = ev.getY(0) - lastY
                lastX = ev.getX(0)
                lastY = ev.getY(0)
                val dist = hypot(dx, dy)
                totalMove += dist
                if (dist > dpPx(0.2f)) {
                    val padW = (width - railW).coerceAtLeast(320f)
                    val portraitGain = ((virtualWidth / padW) * 0.65f).coerceIn(1.0f, 2.1f)
                    val dpiNorm = 2.0f / density
                    val accel = speedMultiplier * dpiNorm * portraitGain *
                        (1.0f + dist.coerceAtMost(dpPx(18f)) * (0.045f / density))
                    listener?.onMoveRel(dx * accel, dy * accel)
                }
            }
            GestureMode.RIGHT_EDGE_SCROLL -> {
                val dx = ev.getX(0) - lastX
                val dy = ev.getY(0) - lastY
                lastX = ev.getX(0)
                lastY = ev.getY(0)
                totalMove += hypot(dx, dy)
                scrollAccumV += dy / scrollStepV
                if (abs(scrollAccumV) >= 0.25f) {
                    listener?.onScroll(0f, scrollAccumV)
                    scrollAccumV = 0f
                }
            }
            GestureMode.TWO_FINGER -> {
                if (ev.pointerCount < 2) return
                val cx = (ev.getX(0) + ev.getX(1)) * 0.5f
                val cy = (ev.getY(0) + ev.getY(1)) * 0.5f
                val dx = cx - last2X
                val dy = cy - last2Y
                last2X = cx
                last2Y = cy
                totalMove += hypot(dx, dy)
                scrollAccumV += dy / scrollStepV
                scrollAccumH += dx / scrollStepH
                if (abs(scrollAccumV) >= 0.30f || abs(scrollAccumH) >= 0.30f) {
                    twoFingerScrolled = true
                    listener?.onScroll(-scrollAccumH, scrollAccumV)
                    scrollAccumV = 0f
                    scrollAccumH = 0f
                }
            }
            GestureMode.IDLE -> Unit
        }
    }

    private fun handlePointerUp(now: Long) {
        if (mode == GestureMode.TWO_FINGER &&
            !twoFingerScrolled &&
            (now - twoFingerDownTime) < 360L &&
            totalMove < dpPx(20f)
        ) {
            listener?.onRightClickOrBack()
            mode = GestureMode.IDLE
        }
    }

    private fun handleUp(now: Long) {
        touchVisX = -1f
        touchVisY = -1f
        invalidate()
        when (mode) {
            GestureMode.ONE_FINGER_DRAG -> listener?.onLeftUp()
            GestureMode.SINGLE_FINGER, GestureMode.RIGHT_EDGE_SCROLL -> {
                if ((now - downTime) < 270L && totalMove < dpPx(8f)) {
                    if (isDragLocked) {
                        listener?.onReleaseDragLock()
                    } else {
                        listener?.onLeftClick()
                        lastTapUpTime = now
                    }
                }
            }
            else -> Unit
        }
        mode = GestureMode.IDLE
    }

    private fun handleCancel() {
        touchVisX = -1f
        touchVisY = -1f
        if (mode == GestureMode.ONE_FINGER_DRAG) listener?.onLeftUp()
        mode = GestureMode.IDLE
        invalidate()
    }
}

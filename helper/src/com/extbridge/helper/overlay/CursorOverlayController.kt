package com.extbridge.helper.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.extbridge.helper.BridgeClient
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Manages the topmost software cursor overlay on the external VirtualDisplay.
 * Automatically suppresses and hides the Android software cursor whenever the active
 * sink is a remote Mac display (`SINK_REMOTE_USB` / `SINK_REMOTE_WIFI_ADB` / `SINK_REMOTE_WIFI`)
 * so the user never sees a double pointer alongside macOS's native cursor.
 */
class CursorOverlayController(private val host: OverlayHost) {

    var cursorView: CursorView? = null
        private set
    var cursorLp: WindowManager.LayoutParams? = null
        private set
    var cursorVisible: Boolean = false
        private set

    private var suppressedForRemoteSink: Boolean = false

    private val hideCursorTask = Runnable { setCursorVisible(false) }

    fun buildCursorOverlay(ctx: Context, st: BridgeClient.Status) {
        suppressedForRemoteSink = st.isRemoteSink
        val view = CursorView(ctx)
        view.elevation = OverlayUiUtils.dp(ctx, 48f).toFloat()
        view.translationZ = OverlayUiUtils.dp(ctx, 48f).toFloat()
        val lp = WindowManager.LayoutParams(
            44,
            44,
            host.winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.x = st.cursorX - 6
        lp.y = st.cursorY - 4
        view.visibility = View.GONE
        cursorView = view
        cursorLp = lp
        cursorVisible = false
    }

    fun updateRemoteSuppression(st: BridgeClient.Status) {
        val shouldSuppress = st.isRemoteDisplayActive
        suppressedForRemoteSink = shouldSuppress
        if (shouldSuppress) {
            host.handler.removeCallbacks(hideCursorTask)
            setCursorVisible(false)
        }
    }

    fun bringCursorToFront() {
        val view = cursorView ?: return
        try {
            if (host.inLauncherHost) {
                view.bringToFront()
            } else if (host.extWm != null && view.parent != null) {
                view.elevation = OverlayUiUtils.dp(host.extCtx ?: host.serviceContext, 48f).toFloat()
            }
        } catch (_: Throwable) {}
    }

    fun updateCursorOnExt(x: Float, y: Float, pressing: Boolean) {
        val view = cursorView ?: return
        val lp = cursorLp ?: return
        if (suppressedForRemoteSink || host.lastStatus.isRemoteSink) {
            suppressedForRemoteSink = true
            host.handler.removeCallbacks(hideCursorTask)
            if (cursorVisible || view.visibility == View.VISIBLE) {
                setCursorVisible(false)
            }
            maybeWakeDockOnEdge(x, y)
            return
        }
        try {
            if (!cursorVisible) {
                cursorVisible = true
                view.visibility = View.VISIBLE
            }
            view.setPressing(pressing)
            lp.x = x.roundToInt() - 6
            lp.y = y.roundToInt() - 4
            if (host.inLauncherHost) {
                view.translationX = lp.x.toFloat()
                view.translationY = lp.y.toFloat()
                view.bringToFront()
            } else {
                host.extWm?.updateViewLayout(view, lp)
            }
            host.handler.removeCallbacks(hideCursorTask)
            host.handler.postDelayed(hideCursorTask, 7000L)
            maybeWakeDockOnEdge(x, y)
        } catch (_: Throwable) {}
    }

    private fun maybeWakeDockOnEdge(x: Float, y: Float) {
        val ctx = host.extCtx ?: return
        if (host.isDockAutoHide() && !host.isOnLauncherHome() && host.overlayH > 0) {
            val edgeZone = OverlayUiUtils.dp(ctx, 18f)
            val dockZone = OverlayUiUtils.dp(ctx, 82f)
            if (host.isDockHidden() &&
                y >= (host.overlayH - edgeZone) &&
                abs(x - host.overlayW * 0.5f) <= OverlayUiUtils.dp(ctx, 190f)
            ) {
                host.wakeDockFromEdge()
            } else if (!host.isDockHidden() && y >= (host.overlayH - dockZone)) {
                host.cancelAutoHideTimer()
                host.evaluateDockAutoHide(true)
            }
        }
    }

    fun setCursorVisible(vis: Boolean) {
        val effective = vis && !suppressedForRemoteSink
        cursorVisible = effective
        cursorView?.visibility = if (effective) View.VISIBLE else View.GONE
    }

    fun detach() {
        host.handler.removeCallbacks(hideCursorTask)
        host.removeOverlayView(cursorView)
        cursorView = null
        cursorLp = null
        cursorVisible = false
    }

    class CursorView(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.6f
            strokeJoin = Paint.Join.ROUND
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x9938BDF8.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        private val arrow = Path().apply {
            moveTo(6f, 4f)
            lineTo(6f, 34f)
            lineTo(13.5f, 26.5f)
            lineTo(19f, 38f)
            lineTo(23.5f, 36f)
            lineTo(18f, 24.5f)
            lineTo(28f, 24.5f)
            close()
        }
        private var pressing = false

        fun setPressing(p: Boolean) {
            if (pressing != p) {
                pressing = p
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (pressing) {
                canvas.drawCircle(6f, 6f, 10f, ring)
            }
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, stroke)
        }
    }
}

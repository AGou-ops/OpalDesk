package com.extbridge.helper.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.extbridge.helper.BridgeClient
import kotlin.math.roundToInt

/**
 * Manages the topmost software cursor overlay via [OverlaySurfaceHost] ([OverlayLayer.CURSOR]).
 * Suppresses itself when a remote Mac sink is active and forwards coordinates to `host.dock.onCursorMoved`.
 */
class CursorOverlayController(private val host: OverlayHost) {

    companion object {
        private const val TAG = "ExtCursorOverlay"
        private const val SURFACE_KEY = "cursor"
        private const val CURSOR_SIZE_PX = 44
        private const val HOTSPOT_OFFSET_X = 6
        private const val HOTSPOT_OFFSET_Y = 4
        private const val AUTO_HIDE_TIMEOUT_MS = 7000L
        private const val CURSOR_ELEVATION_DP = 48f
    }

    private var cursorView: CursorView? = null
    private var cursorLp: WindowManager.LayoutParams? = null
    private var cursorVisible = false
    private var suppressedForRemoteSink = false
    private val hideCursorTask = Runnable { setCursorVisible(false) }

    fun buildCursorOverlay(ctx: Context, st: BridgeClient.Status) {
        suppressedForRemoteSink = st.isRemoteSink || st.isLocalDexActive
        val view = CursorView(ctx).apply {
            isClickable = false; isFocusable = false
            elevation = ctx.dpF(CURSOR_ELEVATION_DP)
            translationZ = ctx.dpF(CURSOR_ELEVATION_DP)
            visibility = View.GONE
        }
        val lp = OverlayWindowParams.edgeStrip(
            CURSOR_SIZE_PX, CURSOR_SIZE_PX, host.winType,
            Gravity.TOP or Gravity.LEFT, st.cursorX - HOTSPOT_OFFSET_X, st.cursorY - HOTSPOT_OFFSET_Y, touchable = false,
        )
        cursorView = view; cursorLp = lp; cursorVisible = false

        host.surfaceHost.register(
            OverlaySurfaceHost.SurfaceEntry(
                key = SURFACE_KEY,
                layer = OverlayLayer.CURSOR,
                view = view,
                wmParams = lp,
                launcherParamsProvider = { ctx.flp(CURSOR_SIZE_PX, CURSOR_SIZE_PX, Gravity.TOP or Gravity.LEFT) },
                onAttachedToHost = { inLauncher ->
                    view.translationX = if (inLauncher) lp.x.toFloat() else 0f
                    view.translationY = if (inLauncher) lp.y.toFloat() else 0f
                    if (inLauncher) view.bringToFront()
                },
            ),
        )
    }

    fun updateRemoteSuppression(st: BridgeClient.Status) {
        suppressedForRemoteSink = st.isRemoteDisplayActive || st.isLocalDexActive
        if (suppressedForRemoteSink) {
            host.handler.removeCallbacks(hideCursorTask)
            setCursorVisible(false)
        }
    }

    fun bringCursorToFront() {
        if (cursorView == null) return
        try {
            host.surfaceHost.bringLayerToFront(OverlayLayer.CURSOR, inLauncher = false, wm = host.extWm)
        } catch (t: Throwable) {
            Log.w(TAG, "bringCursorToFront failed", t)
        }
    }

    fun updateCursorOnExt(x: Float, y: Float, pressing: Boolean) {
        val view = cursorView ?: return
        val lp = cursorLp ?: return
        if (suppressedForRemoteSink || host.lastStatus.isRemoteSink || host.lastStatus.isLocalDexActive) {
            suppressedForRemoteSink = true
            host.handler.removeCallbacks(hideCursorTask)
            if (cursorVisible || view.visibility == View.VISIBLE) setCursorVisible(false)
            host.onCursorMoved(x, y)
            return
        }
        try {
            if (!cursorVisible) { cursorVisible = true; view.visibility = View.VISIBLE }
            view.setPressing(pressing)
            lp.x = x.roundToInt() - HOTSPOT_OFFSET_X
            lp.y = y.roundToInt() - HOTSPOT_OFFSET_Y
            host.surfaceHost.updateWindowLayout(view, lp, host.extWm)
            host.handler.removeCallbacks(hideCursorTask)
            host.handler.postDelayed(hideCursorTask, AUTO_HIDE_TIMEOUT_MS)
            host.onCursorMoved(x, y)
        } catch (t: Throwable) {
            Log.w(TAG, "updateCursorOnExt failed", t)
        }
    }

    private fun setCursorVisible(vis: Boolean) {
        val effective = vis && !suppressedForRemoteSink
        cursorVisible = effective
        cursorView?.visibility = if (effective) View.VISIBLE else View.GONE
    }

    fun detach() {
        host.handler.removeCallbacks(hideCursorTask)
        host.surfaceHost.unregister(SURFACE_KEY, host.extWm)
        cursorView = null; cursorLp = null; cursorVisible = false
    }

    class CursorView(ctx: Context) : View(ctx) {
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38000000; style = Paint.Style.FILL }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1D1D1F.toInt(); style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.4f; strokeJoin = Paint.Join.ROUND
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99007AFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.4f
        }
        private val arrow = Path().apply {
            moveTo(6f, 4f); lineTo(6f, 34f); lineTo(13.5f, 26.5f); lineTo(19f, 38f)
            lineTo(23.5f, 36f); lineTo(18f, 24.5f); lineTo(28f, 24.5f); close()
        }
        private var pressing = false

        fun setPressing(p: Boolean) {
            if (pressing != p) {
                pressing = p
                animate().scaleX(if (p) 0.90f else 1f).scaleY(if (p) 0.90f else 1f).setDuration(70L).start()
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (pressing) canvas.drawCircle(6f, 6f, 10f, ring)
            canvas.save()
            canvas.translate(1.5f, 2f)
            canvas.drawPath(arrow, shadow)
            canvas.restore()
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, stroke)
        }
    }
}

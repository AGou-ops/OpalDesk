package com.extbridge.helper.overlay

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.extbridge.helper.BridgeClient

/**
 * Manages the Display 0 one-click power-saving blackout & accidental-touch shield overlay
 * while operating in DeX extended desktop mode.
 */
class BlackoutOverlayController(private val host: OverlayHost) {

    companion object {
        private const val TAG = "ExtBlackoutOverlay"
        private const val DOUBLE_TAP_EXIT_MS = 450L
        private const val HINT_INITIAL_HIDE_MS = 3200L
        private const val HINT_TAP_HIDE_MS = 2600L
    }

    @Volatile
    var isBlackoutActive: Boolean = false
        private set

    private var blackoutView: FrameLayout? = null
    private var blackoutWm: WindowManager? = null
    private var hideHintTask: Runnable? = null
    private var lastTapMs: Long = 0L

    fun toggleDexBlackout() {
        val next = !isBlackoutActive
        setDexBlackout(next)
        if (next) host.popovers.dismissAll()
    }

    fun setDexBlackout(on: Boolean) {
        if (on && isBlackoutActive && blackoutView != null) {
            host.panel.updateBlackoutUi()
            return
        }
        if (on) {
            val st = host.lastStatus
            if (!st.connected || st.mirrorMode != BridgeClient.MIRROR_MODE_DEX || st.vdId <= 0) {
                host.serviceContext.toast("仅在 DeX 扩展桌面已连接外接屏时可用")
                host.panel.updateBlackoutUi()
                return
            }
            if (!attachBlackoutOverlay()) {
                isBlackoutActive = false
                host.panel.updateBlackoutUi()
                host.serviceContext.toast("请开启无障碍或悬浮窗权限后使用一键黑屏")
                return
            }
            isBlackoutActive = true
        } else {
            isBlackoutActive = false
            removeBlackoutOverlay()
        }
        host.panel.updateBlackoutUi()
        val arg = if (on) 1 else 0
        host.runDaemonAndPoll { BridgeClient.execSysAction(BridgeClient.SYS_SCREEN_DIM, arg, null) }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("ClickableViewAccessibility")
    private fun attachBlackoutOverlay(): Boolean {
        if (blackoutView != null) return true
        val resolved = OverlayDisplayContext.resolveDefaultDisplay(host.serviceContext, host.displayManager, preferA11y = true)
            ?: return false
        val ctx0 = resolved.uiContext
        return try {
            val root = FrameLayout(ctx0).apply {
                setBackgroundColor(Color.BLACK)
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
            val hint = TextView(ctx0).apply {
                text = "主屏已进入省电黑屏（最低亮度）\n双击屏幕任意位置 · 或锁屏再解锁立即恢复"
                setTextColor(0x99FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setLineSpacing(ctx0.dpF(4f), 1f)
                setPadDp(24f, 16f)
            }
            root.addView(hint, ctx0.flp(WRAP, WRAP, Gravity.CENTER))

            hideHintTask?.let { host.handler.removeCallbacks(it) }
            val hideTask = Runnable { if (hint.parent != null) hint.visibility = View.GONE }
            hideHintTask = hideTask
            lastTapMs = 0L

            root.setOnTouchListener { _, ev ->
                if (ev != null && ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTapMs <= DOUBLE_TAP_EXIT_MS) {
                        host.handler.removeCallbacks(hideTask)
                        setDexBlackout(false)
                    } else {
                        lastTapMs = now
                        hint.visibility = View.VISIBLE
                        host.handler.removeCallbacks(hideTask)
                        host.handler.postDelayed(hideTask, HINT_TAP_HIDE_MS)
                    }
                }
                true
            }
            host.handler.postDelayed(hideTask, HINT_INITIAL_HIDE_MS)

            val lp = WindowManager.LayoutParams(
                MATCH, MATCH, resolved.windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                title = "ExtBlackout"
            }
            resolved.windowManager.addView(root, lp)
            blackoutView = root
            blackoutWm = resolved.windowManager
            true
        } catch (t: Throwable) {
            hideHintTask?.let { host.handler.removeCallbacks(it) }
            hideHintTask = null
            Log.w(TAG, "Failed to attach Display 0 blackout overlay (a11y=${resolved.isA11yOverlay})", t)
            false
        }
    }

    fun removeBlackoutOverlay() {
        hideHintTask?.let { host.handler.removeCallbacks(it) }
        hideHintTask = null
        val view = blackoutView
        val wm = blackoutWm
        if (view != null && wm != null) {
            try { wm.removeViewImmediate(view) } catch (t: Throwable) { Log.d(TAG, "removeViewImmediate(ExtBlackout) failed: ${t.message}") }
        }
        blackoutView = null
        blackoutWm = null
    }

    fun restoreAndDestroy() {
        if (isBlackoutActive) {
            isBlackoutActive = false
            host.safeBgExecute {
                try { BridgeClient.execSysAction(BridgeClient.SYS_SCREEN_DIM, 0, null) }
                catch (t: Throwable) { Log.w(TAG, "Failed to restore screen brightness on destroy", t) }
            }
        }
        removeBlackoutOverlay()
    }
}

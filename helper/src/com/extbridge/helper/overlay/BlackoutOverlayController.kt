package com.extbridge.helper.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.NavAssistService

/**
 * Manages the Display 0 one-click power-saving blackout & accidental-touch shield overlay
 * while operating in DeX extended desktop mode.
 */
class BlackoutOverlayController(private val host: OverlayHost) {

    @Volatile
    var isBlackoutActive: Boolean = false
        private set

    private var blackoutView: FrameLayout? = null
    private var blackoutWm: WindowManager? = null
    private var blackoutHideHintTask: Runnable? = null
    private var lastBlackoutTapMs: Long = 0L

    fun toggleDexBlackout() {
        val next = !isBlackoutActive
        setDexBlackout(next)
        if (next) {
            host.setPanelOpen(false)
        }
    }

    fun setDexBlackout(on: Boolean) {
        if (on && isBlackoutActive && blackoutView != null) {
            host.updateBlackoutUi()
            return
        }
        if (on) {
            val st = host.lastStatus
            if (!st.connected || st.mirrorMode != BridgeClient.MIRROR_MODE_DEX || st.vdId <= 0) {
                Toast.makeText(host.serviceContext, "仅在 DeX 扩展桌面已连接外接屏时可用", Toast.LENGTH_SHORT).show()
                host.updateBlackoutUi()
                return
            }
            if (!attachBlackoutOverlay()) {
                isBlackoutActive = false
                host.updateBlackoutUi()
                Toast.makeText(host.serviceContext, "请开启无障碍或悬浮窗权限后使用一键黑屏", Toast.LENGTH_SHORT).show()
                return
            }
            isBlackoutActive = true
        } else {
            isBlackoutActive = false
            removeBlackoutOverlay()
        }
        host.updateBlackoutUi()
        val arg = if (on) 1 else 0
        host.safeBgExecute {
            BridgeClient.execSysAction(BridgeClient.SYS_SCREEN_DIM, arg, null)
            host.safePost { host.pollRemoteStateAsync() }
        }
    }

    @Suppress("DEPRECATION")
    private fun attachBlackoutOverlay(): Boolean {
        if (blackoutView != null) return true
        try {
            val disp0 = host.displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
            val a11y = NavAssistService.getInstance()
            val type: Int
            val ctx0: Context
            if (a11y != null) {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                ctx0 = a11y.createDisplayContext(disp0)
            } else if (Settings.canDrawOverlays(host.serviceContext)) {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                ctx0 = host.serviceContext.createDisplayContext(disp0)
            } else {
                return false
            }
            var wm0 = ctx0.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm0 == null && a11y != null) {
                wm0 = a11y.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            }
            if (wm0 == null) {
                wm0 = host.serviceContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            }
            if (wm0 == null) return false

            val root = FrameLayout(ctx0)
            root.setBackgroundColor(Color.BLACK)
            root.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

            val hint = TextView(ctx0).apply {
                text = "主屏已进入省电黑屏（最低亮度）\n双击屏幕任意位置 · 或锁屏再解锁立即恢复"
                setTextColor(0x99FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setLineSpacing(OverlayUiUtils.dp(ctx0, 4f).toFloat(), 1f)
                setPadding(
                    OverlayUiUtils.dp(ctx0, 24f),
                    OverlayUiUtils.dp(ctx0, 16f),
                    OverlayUiUtils.dp(ctx0, 24f),
                    OverlayUiUtils.dp(ctx0, 16f),
                )
            }
            val hLp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
            root.addView(hint, hLp)

            blackoutHideHintTask?.let { host.handler.removeCallbacks(it) }
            val hideTask = Runnable {
                if (hint.parent != null) hint.visibility = View.GONE
            }
            blackoutHideHintTask = hideTask
            lastBlackoutTapMs = 0L
            root.setOnTouchListener { _, ev ->
                if (ev != null && ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastBlackoutTapMs <= 450L) {
                        blackoutHideHintTask?.let { host.handler.removeCallbacks(it) }
                        setDexBlackout(false)
                        return@setOnTouchListener true
                    }
                    lastBlackoutTapMs = now
                    hint.visibility = View.VISIBLE
                    blackoutHideHintTask?.let {
                        host.handler.removeCallbacks(it)
                        host.handler.postDelayed(it, 2600L)
                    }
                    return@setOnTouchListener true
                }
                true
            }
            host.handler.postDelayed(hideTask, 3200L)

            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                flags,
                PixelFormat.OPAQUE,
            )
            lp.gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            lp.title = "ExtBlackout"
            wm0.addView(root, lp)
            blackoutView = root
            blackoutWm = wm0
            return true
        } catch (t: Throwable) {
            blackoutHideHintTask?.let { host.handler.removeCallbacks(it) }
            blackoutHideHintTask = null
            t.printStackTrace()
            return false
        }
    }

    fun removeBlackoutOverlay() {
        blackoutHideHintTask?.let { host.handler.removeCallbacks(it) }
        blackoutHideHintTask = null
        val view = blackoutView
        val wm = blackoutWm
        if (view != null && wm != null) {
            try { wm.removeViewImmediate(view) } catch (_: Throwable) {}
        }
        blackoutView = null
        blackoutWm = null
    }

    fun restoreAndDestroy() {
        if (isBlackoutActive) {
            isBlackoutActive = false
            try {
                Thread({ BridgeClient.execSysAction(BridgeClient.SYS_SCREEN_DIM, 0, null) }, "ExtDimRestore").start()
            } catch (_: Throwable) {}
        }
        removeBlackoutOverlay()
    }
}

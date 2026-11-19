package com.extbridge.helper.overlay

import android.app.ActivityManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.SizeF
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.BridgeService
import com.extbridge.helper.HyperUi
import com.extbridge.helper.NavAssistService
import com.extbridge.helper.SideMouseCaptureActivity
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Manages the Native Mirror Mode telemetry + AppWidget sidebars (unified single-wing and dual-wing
 * layouts across horizontal and vertical monitor aspect ratios) as well as the Display 0 physical
 * mouse edge-crossing sensor and pointer capture pipeline.
 */
class MirrorSidebarController(private val host: OverlayHost) {

    companion object {
        const val MIRROR_WIDGET_HOST_ID = 0xEB01

        private const val PREF_NAME = "ext_prefs"
        private const val PREF_MIRROR_WIDGET_LEFT = "mirror_widget_left_id"
        private const val PREF_MIRROR_WIDGET_RIGHT = "mirror_widget_right_id"
        private const val PREF_MIRROR_PRESETS = "mirror_preset_slots"
        private const val PREF_MIRROR_MEMO = "mirror_memo_text"
        private const val PREF_MIRROR_THEME = "mirror_theme_id"
        private const val PREF_MIRROR_CUSTOM_BG = "mirror_custom_bg"
        private const val PREF_MIRROR_CURSOR_SPEED = "mirror_cursor_speed_idx"
        private const val PREF_MIRROR_WIDGET_RATIO_L = "mirror_widget_ratio_l"
        private const val PREF_MIRROR_WIDGET_RATIO_R = "mirror_widget_ratio_r"
        private const val DEFAULT_MIRROR_MEMO =
            "• 点击控制台可随时编辑便签文字\n• 支持虚拟触控板点按滑控小组件"

        private val EDGE_REACQUIRE_DELAYS_MS =
            longArrayOf(20L, 45L, 90L, 150L, 240L, 360L, 520L, 720L)

        private val sCapMoveLock = Any()
        private var sCapPendingDx = 0f
        private var sCapPendingDy = 0f
        private var sCapMoveInFlight = false

        @Volatile
        private var sActiveController: MirrorSidebarController? = null

        @JvmStatic
        fun getMirrorPresetSlots(ctx: Context?): List<String> {
            val raw = getMirrorPresetSlotsRaw(ctx)
            val out = ArrayList<String>()
            for (s in raw.split(",")) {
                val k = s.trim()
                if (k.isNotEmpty() && BridgeClient.mirrorPresetIndex(k) >= 0) {
                    out.add(k)
                }
            }
            if (out.isEmpty()) {
                out.addAll(BridgeClient.DEFAULT_MIRROR_PRESETS.split(","))
            }
            return out
        }

        @JvmStatic
        fun getMirrorPresetSlotsRaw(ctx: Context?): String {
            if (ctx == null) return BridgeClient.DEFAULT_MIRROR_PRESETS
            val sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            var v = sp.getString(PREF_MIRROR_PRESETS, BridgeClient.DEFAULT_MIRROR_PRESETS)
            if (!sp.getBoolean("migrated_screendim_v340", false)) {
                val cur = sp.getString(PREF_MIRROR_PRESETS, null)
                if (cur != null && cur.trim().isNotEmpty() &&
                    !cur.split(",").contains("screendim")
                ) {
                    v = "${cur.trim()},screendim"
                    sp.edit()
                        .putString(PREF_MIRROR_PRESETS, v)
                        .putBoolean("migrated_screendim_v340", true)
                        .apply()
                } else {
                    sp.edit().putBoolean("migrated_screendim_v340", true).apply()
                }
            }
            return if (v == null || v.trim().isEmpty()) BridgeClient.DEFAULT_MIRROR_PRESETS else v
        }

        @JvmStatic
        fun setMirrorPresetSlots(ctx: Context?, slots: List<String>?) {
            if (ctx == null) return
            val sb = StringBuilder()
            if (slots != null) {
                for (s in slots) {
                    val k = s.trim()
                    if (k.isNotEmpty() && BridgeClient.mirrorPresetIndex(k) >= 0) {
                        if (sb.isNotEmpty()) sb.append(",")
                        sb.append(k)
                    }
                }
            }
            val joined = if (sb.isNotEmpty()) sb.toString() else BridgeClient.DEFAULT_MIRROR_PRESETS
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_MIRROR_PRESETS, joined)
                .apply()
            BridgeService.refreshFromReceiver(ctx)
        }

        @JvmStatic
        fun getMirrorMemoText(ctx: Context?): String {
            if (ctx == null) return DEFAULT_MIRROR_MEMO
            val sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return sp.getString(PREF_MIRROR_MEMO, DEFAULT_MIRROR_MEMO) ?: ""
        }

        @JvmStatic
        fun setMirrorMemoText(ctx: Context?, txt: String?) {
            if (ctx == null) return
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_MIRROR_MEMO, txt ?: "")
                .apply()
            BridgeService.refreshFromReceiver(ctx)
        }

        @JvmStatic
        fun getMirrorWidgetId(ctx: Context?, slot: Int): Int {
            if (ctx == null) return -1
            val sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return sp.getInt(if (slot == 0) PREF_MIRROR_WIDGET_LEFT else PREF_MIRROR_WIDGET_RIGHT, -1)
        }

        @JvmStatic
        fun setMirrorWidgetId(ctx: Context?, slot: Int, widgetId: Int) {
            if (ctx == null) return
            val sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val key = if (slot == 0) PREF_MIRROR_WIDGET_LEFT else PREF_MIRROR_WIDGET_RIGHT
            val prevId = sp.getInt(key, -1)
            if (prevId > 0 && prevId != widgetId) {
                try {
                    val active = sActiveController?.takeUnless { it.host.isDestroyed }
                    val widgetHost = active?.appWidgetHost
                        ?: AppWidgetHost(ctx.applicationContext, MIRROR_WIDGET_HOST_ID)
                    widgetHost.deleteAppWidgetId(prevId)
                } catch (_: Throwable) {}
            }
            sp.edit().putInt(key, widgetId).apply()
            BridgeService.refreshFromReceiver(ctx)
        }

        @JvmStatic
        fun getMirrorThemeId(ctx: Context?): String {
            if (ctx == null) return "night"
            val id = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(PREF_MIRROR_THEME, "night")
            return if (!id.isNullOrEmpty()) id else "night"
        }

        @JvmStatic
        fun setMirrorThemeId(ctx: Context?, id: String?) {
            if (ctx == null) return
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_MIRROR_THEME, id ?: "night")
                .apply()
            BridgeService.refreshFromReceiver(ctx)
        }

        @JvmStatic
        fun getMirrorCustomBg(ctx: Context?): Int {
            if (ctx == null) return 0xFF1E293B.toInt()
            return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_MIRROR_CUSTOM_BG, 0xFF1E293B.toInt())
        }

        @JvmStatic
        fun setMirrorCustomBg(ctx: Context?, bgColor: Int) {
            if (ctx == null) return
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_MIRROR_THEME, "custom")
                .putInt(PREF_MIRROR_CUSTOM_BG, 0xFF000000.toInt() or (bgColor and 0x00FFFFFF))
                .apply()
            BridgeService.refreshFromReceiver(ctx)
        }

        @JvmStatic
        fun getActiveMirrorTheme(ctx: Context?): BridgeClient.MirrorTheme =
            BridgeClient.resolveMirrorTheme(getMirrorThemeId(ctx), getMirrorCustomBg(ctx))

        @JvmStatic
        fun getMirrorCursorSpeedIdx(ctx: Context?): Int {
            if (ctx == null) return 2
            val idx = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_MIRROR_CURSOR_SPEED, 2)
            return idx.coerceIn(0, BridgeClient.MIRROR_SPEED_MULTS.size - 1)
        }

        @JvmStatic
        fun getMirrorCursorSpeedMult(ctx: Context?): Float =
            BridgeClient.MIRROR_SPEED_MULTS[getMirrorCursorSpeedIdx(ctx)]

        @JvmStatic
        fun setMirrorCursorSpeedIdx(ctx: Context?, idx: Int) {
            if (ctx == null) return
            val clamped = idx.coerceIn(0, BridgeClient.MIRROR_SPEED_MULTS.size - 1)
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(PREF_MIRROR_CURSOR_SPEED, clamped)
                .apply()
        }

        @JvmStatic
        fun getMirrorWidgetRatio(ctx: Context?, slot: Int): Int {
            if (ctx == null) return 0
            val k = if (slot == 0) PREF_MIRROR_WIDGET_RATIO_L else PREF_MIRROR_WIDGET_RATIO_R
            val mode = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(k, 0)
            return mode.coerceIn(0, BridgeClient.MIRROR_WIDGET_RATIO_LABELS.size - 1)
        }

        @JvmStatic
        fun setMirrorWidgetRatio(ctx: Context?, slot: Int, mode: Int) {
            if (ctx == null) return
            val k = if (slot == 0) PREF_MIRROR_WIDGET_RATIO_L else PREF_MIRROR_WIDGET_RATIO_R
            val clamped = mode.coerceIn(0, BridgeClient.MIRROR_WIDGET_RATIO_LABELS.size - 1)
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(k, clamped)
                .apply()
            BridgeService.refreshFromReceiver(ctx)
        }

        @JvmStatic
        fun resetCapturedMouseMoveState() {
            synchronized(sCapMoveLock) {
                sCapPendingDx = 0f
                sCapPendingDy = 0f
                sCapMoveInFlight = false
            }
        }

        @JvmStatic
        fun handleCapturedPointerEventStatic(
            ctx: Context?,
            ev: MotionEvent?,
            exec: ExecutorService?,
            onExitCapture: Runnable?,
        ): Boolean {
            if (ev == null || exec == null || exec.isShutdown) return false
            return handleCapturedPointerEventInternal(
                ctx = ctx,
                ev = ev,
                isShutdown = { exec.isShutdown },
                dispatch = { task -> exec.execute(task) },
                onExitCapture = onExitCapture,
            )
        }

        private fun handleCapturedPointerEventInternal(
            ctx: Context?,
            ev: MotionEvent?,
            isShutdown: () -> Boolean,
            dispatch: (Runnable) -> Unit,
            onExitCapture: Runnable?,
        ): Boolean {
            if (ev == null || isShutdown()) return false
            val active = sActiveController?.takeUnless { it.host.isDestroyed }
            if (active != null && Build.VERSION.SDK_INT >= 26 && active.edgeSensorView?.hasPointerCapture() == true) {
                active.edgePointerCaptured = true
            }
            val act = ev.actionMasked
            if (act == MotionEvent.ACTION_MOVE || act == MotionEvent.ACTION_HOVER_MOVE) {
                var rx = ev.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                var ry = ev.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                if (rx == 0f && ry == 0f &&
                    (ev.source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                ) {
                    rx = ev.x
                    ry = ev.y
                }
                if (rx != 0f || ry != 0f) {
                    var needLaunch = false
                    synchronized(sCapMoveLock) {
                        sCapPendingDx += rx
                        sCapPendingDy += ry
                        if (!sCapMoveInFlight) {
                            sCapMoveInFlight = true
                            needLaunch = true
                        }
                    }
                    if (needLaunch) {
                        try {
                            dispatch(Runnable {
                                try {
                                    while (!isShutdown()) {
                                        var curDx: Float
                                        var curDy: Float
                                        synchronized(sCapMoveLock) {
                                            curDx = sCapPendingDx
                                            curDy = sCapPendingDy
                                            sCapPendingDx = 0f
                                            sCapPendingDy = 0f
                                            if (abs(curDx) < 0.01f && abs(curDy) < 0.01f) {
                                                sCapMoveInFlight = false
                                                break
                                            }
                                        }
                                        val curActive = sActiveController?.takeUnless { it.host.isDestroyed }
                                        val speedCtx = ctx ?: curActive?.host?.serviceContext
                                        val userMult = getMirrorCursorSpeedMult(speedCtx)
                                        var resMult = 1.0f
                                        val physW = curActive?.sidePhysW ?: 0
                                        val physH = curActive?.sidePhysH ?: 0
                                        if (physW > 0 && physH > 0) {
                                            resMult = (min(physW, physH) / 1440f).coerceIn(0.85f, 2.2f)
                                        }
                                        val mag = hypot(curDx, curDy)
                                        val accel = 1.0f + min(1.45f, mag * 0.045f)
                                        val gain = userMult * resMult * accel
                                        val res = BridgeClient.sendCapturedMirrorMouse(
                                            0, curDx * gain, curDy * gain, 0f,
                                        )
                                        if (res[0] == 0) {
                                            synchronized(sCapMoveLock) {
                                                sCapPendingDx = 0f
                                                sCapPendingDy = 0f
                                                sCapMoveInFlight = false
                                            }
                                            onExitCapture?.run()
                                            break
                                        }
                                    }
                                } catch (_: Throwable) {
                                } finally {
                                    synchronized(sCapMoveLock) {
                                        sCapMoveInFlight = false
                                    }
                                }
                            })
                        } catch (_: RejectedExecutionException) {
                            synchronized(sCapMoveLock) {
                                sCapPendingDx = 0f
                                sCapPendingDy = 0f
                                sCapMoveInFlight = false
                            }
                        }
                    }
                }
                return true
            }
            if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_BUTTON_PRESS) {
                val btn = ev.actionButton
                try {
                    if (btn == 0 || btn == MotionEvent.BUTTON_PRIMARY) {
                        dispatch(Runnable { BridgeClient.sendCapturedMirrorMouse(1, 0f, 0f, 0f) })
                        return true
                    } else if (btn == MotionEvent.BUTTON_SECONDARY || btn == MotionEvent.BUTTON_TERTIARY) {
                        dispatch(Runnable { BridgeClient.sendCapturedMirrorMouse(6, btn.toFloat(), 0f, 0f) })
                        return true
                    }
                } catch (_: RejectedExecutionException) {}
            }
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_BUTTON_RELEASE) {
                val btn = ev.actionButton
                try {
                    if (btn == 0 || btn == MotionEvent.BUTTON_PRIMARY) {
                        dispatch(Runnable { BridgeClient.sendCapturedMirrorMouse(2, 0f, 0f, 0f) })
                        return true
                    } else if (btn == MotionEvent.BUTTON_SECONDARY || btn == MotionEvent.BUTTON_TERTIARY) {
                        dispatch(Runnable { BridgeClient.sendCapturedMirrorMouse(7, btn.toFloat(), 0f, 0f) })
                        return true
                    }
                } catch (_: RejectedExecutionException) {}
            }
            if (act == MotionEvent.ACTION_SCROLL) {
                val h = ev.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
                try {
                    dispatch(Runnable { BridgeClient.sendCapturedMirrorMouse(4, h, 0f, v) })
                } catch (_: RejectedExecutionException) {}
                return true
            }
            return false
        }

        private fun formatSpeedRate(bytesPerSec: Double): String {
            if (bytesPerSec < 1024.0) return String.format(Locale.US, "%.0f B/s", max(0.0, bytesPerSec))
            if (bytesPerSec < 1024.0 * 1024.0) return String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
            return String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024.0 * 1024.0))
        }

        private fun formatCountdown(totalSec: Long): String {
            val s = max(0L, totalSec)
            val m = s / 60L
            val r = s % 60L
            return String.format(Locale.US, "%02d:%02d", m, r)
        }
    }

    init {
        sActiveController = this
    }

    private var appWidgetMgr: AppWidgetManager? = null
    private var appWidgetHost: AppWidgetHost? = null
    private var widgetHostListening = false

    @Volatile
    var sideAttachedVdId: Int = -1
        private set

    @Volatile
    private var sidePhysW = 0

    @Volatile
    private var sidePhysH = 0

    private var sideMirX = 0
    private var sideMirY = 0
    private var sideMirW = 0
    private var sideMirH = 0
    private var sideModeAttached = -1
    private var sideAttachedA11y = false
    private var sideAttachedLeftWidgetId = -1
    private var sideAttachedRightWidgetId = -1
    private var sidePresetSigAttached = ""
    private var sideMemoAttached = ""
    private var sideThemeSigAttached = ""
    private var sideWidgetRatioSigAttached = ""
    private var sideTheme: BridgeClient.MirrorTheme =
        BridgeClient.resolveMirrorTheme("night", 0xFF0F172A.toInt())

    private var sideCtx: Context? = null
    private var sideWm: WindowManager? = null
    private var mirLeftRoot: LinearLayout? = null
    private var mirRightRoot: LinearLayout? = null
    private var sideLeftX = 0
    private var sideLeftY = 0
    private var sideLeftW = 0
    private var sideLeftH = 0
    private var sideRightX = 0
    private var sideRightY = 0
    private var sideRightW = 0
    private var sideRightH = 0

    // Display 0 physical mouse edge sensor & pointer capture state
    private var edgeSensorView: FrameLayout? = null
    private var edgeSensorView2: FrameLayout? = null
    private var edgeD0Wm: WindowManager? = null
    private var edgeLp: WindowManager.LayoutParams? = null
    private var edgeLp2: WindowManager.LayoutParams? = null
    private var edgeCapturing = false
    private var edgePointerCaptured = false
    private var overlayPointerCaptureSupported = false
    private var edgeReacquireAttempts = 0
    private val edgeHoverInFlight = AtomicBoolean(false)
    private var edgeSensorKey = ""

    // Preset card child view references
    private var mirBattIcon: MiniBatteryView? = null
    private var mirBattMain: TextView? = null
    private var mirBattSub: TextView? = null
    private var mirWifiIcon: MiniIconView? = null
    private var mirWifiMain: TextView? = null
    private var mirWifiSub: TextView? = null
    private var mirBtIcon: MiniIconView? = null
    private var mirBtMain: TextView? = null
    private var mirBtSub: TextView? = null
    private var mirAudioIcon: MiniIconView? = null
    private var mirAudioRoute: TextView? = null
    private var mirAudioSub: TextView? = null
    private var mirVolProg: ProgressBar? = null
    private var mirMediaBadge: TextView? = null
    private var mirMediaTitle: TextView? = null
    private var mirMediaSub: TextView? = null
    private var mirCalBody: TextView? = null
    private var mirPerfMain: TextView? = null
    private var mirPerfSub: TextView? = null
    private var mirPerfProg: ProgressBar? = null
    private var mirNetMain: TextView? = null
    private var mirNetSub: TextView? = null
    private var lastNetSampleMs = 0L
    private var lastRxBytes = -1L
    private var lastTxBytes = -1L
    private var mirStorageMain: TextView? = null
    private var mirStorageSub: TextView? = null
    private var mirStorageProg: ProgressBar? = null
    private var mirPomoTime: TextView? = null
    private var mirPomoToggleBtn: TextView? = null
    private var pomoRunning = false
    private var pomoRemainSec = 25L * 60L
    private var pomoLastTickMs = 0L
    private var mirMemoBody: TextView? = null
    private var mirDimMain: TextView? = null
    private var mirDimBtn: TextView? = null
    private var mirQuickDimBtn: TextView? = null
    private var mirLeftWidgetBox: FrameLayout? = null
    private var mirRightWidgetBox: FrameLayout? = null

    // Local metric cache sampled on background thread
    @Volatile
    private var cachedMemAvailGb = 0.0

    @Volatile
    private var cachedMemTotalGb = 0.0

    @Volatile
    private var cachedMemPct = 50

    @Volatile
    private var cachedStorageAvailGb = 0.0

    @Volatile
    private var cachedStorageTotalGb = 0.0

    @Volatile
    private var cachedStoragePct = 50

    @Volatile
    private var cachedD0BrightPct = 50

    @Volatile
    private var cachedRxRate = 0.0

    @Volatile
    private var cachedTxRate = 0.0

    @Volatile
    private var cachedRxTotalMb = 0.0

    @Volatile
    private var mirrorMetricsValid = false

    private val pomoTickTask: Runnable = object : Runnable {
        override fun run() {
            if (host.isDestroyed || !pomoRunning) return
            if (mirPomoTime != null) {
                refreshMirrorSideViews()
            }
            if (pomoRunning && !host.isDestroyed) {
                host.handler.postDelayed(this, 1000L)
            }
        }
    }

    private val edgeCaptureFallbackTask = Runnable {
        if (edgeCapturing && !edgePointerCaptured && !overlayPointerCaptureSupported) {
            revertEdgeOverlayToSensor()
            SideMouseCaptureActivity.launch(host.serviceContext)
        }
    }

    private val edgeReacquireTask: Runnable = object : Runnable {
        override fun run() {
            val sensorView = edgeSensorView
            val d0Wm = edgeD0Wm
            val lp = edgeLp
            if (host.isDestroyed || !edgeCapturing || sensorView == null || d0Wm == null || lp == null) {
                return
            }
            if (Build.VERSION.SDK_INT >= 26 && sensorView.hasPointerCapture()) {
                edgePointerCaptured = true
                overlayPointerCaptureSupported = true
                edgeReacquireAttempts = 0
                return
            }
            if (edgeReacquireAttempts >= EDGE_REACQUIRE_DELAYS_MS.size) {
                exitSidePointerCapture()
                return
            }
            val nextDelay = EDGE_REACQUIRE_DELAYS_MS[edgeReacquireAttempts++]
            host.safeInputExecute {
                val res = BridgeClient.sendCapturedMirrorMouse(9, 0f, 0f, 0f)
                if (res[0] == 0) {
                    host.safePost { revertEdgeOverlayToSensor() }
                }
            }
            try {
                val activeFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                if (!sensorView.hasWindowFocus()) {
                    // Pulse FLAG_NOT_FOCUSABLE -> focusable so WMS re-evaluates top Z-order focus
                    // in favor of ExtMirEdgeSensor even after an Activity launch stole mFocusedApp!
                    lp.flags = activeFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    d0Wm.updateViewLayout(sensorView, lp)
                }
                lp.flags = activeFlags
                d0Wm.updateViewLayout(sensorView, lp)
                sensorView.isFocusable = true
                sensorView.isFocusableInTouchMode = true
                sensorView.requestFocus()
                if (Build.VERSION.SDK_INT >= 26) {
                    sensorView.requestPointerCapture()
                    if (sensorView.hasPointerCapture()) {
                        edgePointerCaptured = true
                        overlayPointerCaptureSupported = true
                        edgeReacquireAttempts = 0
                        return
                    }
                }
            } catch (_: Throwable) {}
            if (edgeCapturing && !edgePointerCaptured) {
                host.handler.removeCallbacks(this)
                host.handler.postDelayed(this, nextDelay)
            }
        }
    }

    private fun dp(ctx: Context, v: Float): Int = OverlayUiUtils.dp(ctx, v)

    fun sampleMirrorLocalMetrics() {
        val svcCtx = host.serviceContext
        try {
            val am = svcCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                val used = max(0L, mi.totalMem - mi.availMem)
                cachedMemPct = if (mi.totalMem > 0L) (used * 100f / mi.totalMem).roundToInt() else 50
                cachedMemAvailGb = mi.availMem / (1024.0 * 1024.0 * 1024.0)
                cachedMemTotalGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
            }
        } catch (_: Throwable) {}
        try {
            val nowMs = SystemClock.elapsedRealtime()
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            if (rx >= 0L && tx >= 0L) {
                if (lastNetSampleMs > 0L && lastRxBytes >= 0L && lastTxBytes >= 0L && nowMs > lastNetSampleMs) {
                    val sec = (nowMs - lastNetSampleMs) / 1000.0
                    cachedRxRate = max(0.0, (rx - lastRxBytes) / sec)
                    cachedTxRate = max(0.0, (tx - lastTxBytes) / sec)
                }
                cachedRxTotalMb = rx / (1024.0 * 1024.0)
                lastNetSampleMs = nowMs
                lastRxBytes = rx
                lastTxBytes = tx
            }
        } catch (_: Throwable) {}
        try {
            val sf = StatFs(Environment.getDataDirectory().path)
            val total = sf.totalBytes
            val avail = sf.availableBytes
            val used = max(0L, total - avail)
            cachedStoragePct = if (total > 0L) (used * 100f / total).roundToInt() else 50
            cachedStorageAvailGb = avail / (1024.0 * 1024.0 * 1024.0)
            cachedStorageTotalGb = total / (1024.0 * 1024.0 * 1024.0)
            val br = Settings.System.getInt(
                svcCtx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128,
            )
            cachedD0BrightPct = (br * 100f / 255f).roundToInt().coerceIn(1, 100)
        } catch (_: Throwable) {}
        mirrorMetricsValid = true
    }

    fun syncOnMirrorMode(st: BridgeClient.Status, a11yNow: Boolean, lastObservedA11y: Boolean) {
        sActiveController = this
        val svcCtx = host.serviceContext
        if ((a11yNow || Settings.canDrawOverlays(svcCtx)) &&
            st.sideVdId > 0 && st.mirrorSideMode > 0 && st.mirW > 0
        ) {
            val lw = getMirrorWidgetId(svcCtx, 0)
            val rw = getMirrorWidgetId(svcCtx, 1)
            val presetSig = getMirrorPresetSlotsRaw(svcCtx)
            val memoTxt = getMirrorMemoText(svcCtx)
            val themeSig = "${getMirrorThemeId(svcCtx)}:${Integer.toHexString(getMirrorCustomBg(svcCtx))}"
            val ratioSig = "${getMirrorWidgetRatio(svcCtx, 0)}:${getMirrorWidgetRatio(svcCtx, 1)}"
            val a11yChanged = if (sideAttachedVdId > 0) (a11yNow != sideAttachedA11y) else (a11yNow != lastObservedA11y)
            if ((mirLeftRoot == null && mirRightRoot == null) ||
                st.sideVdId != sideAttachedVdId ||
                st.physW != sidePhysW ||
                st.physH != sidePhysH ||
                st.mirX != sideMirX ||
                st.mirY != sideMirY ||
                st.mirW != sideMirW ||
                st.mirH != sideMirH ||
                st.mirrorSideMode != sideModeAttached ||
                a11yChanged ||
                lw != sideAttachedLeftWidgetId ||
                rw != sideAttachedRightWidgetId ||
                presetSig != sidePresetSigAttached ||
                memoTxt != sideMemoAttached ||
                themeSig != sideThemeSigAttached ||
                ratioSig != sideWidgetRatioSigAttached
            ) {
                attachMirrorSidebars(st, lw, rw)
            }
            refreshMirrorSideViews()
            syncMirrorEdgeSensor(st)
        } else {
            detachMirrorEdgeSensor()
            detachMirrorSidebars()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun refreshMirrorSideViewsIfAttached(st: BridgeClient.Status) {
        if (mirLeftRoot != null || mirRightRoot != null) {
            refreshMirrorSideViews()
        }
    }

    fun detachAll() {
        detachMirrorEdgeSensor()
        detachMirrorSidebars()
        if (host.isDestroyed && sActiveController === this) {
            sActiveController = null
        }
    }

    private fun ensureWidgetHost() {
        val svcCtx = host.serviceContext
        if (appWidgetMgr == null) {
            appWidgetMgr = AppWidgetManager.getInstance(svcCtx)
        }
        if (appWidgetHost == null) {
            appWidgetHost = AppWidgetHost(svcCtx.applicationContext, MIRROR_WIDGET_HOST_ID)
        }
    }

    private fun attachMirrorSidebars(st: BridgeClient.Status, leftWidgetId: Int, rightWidgetId: Int) {
        if (!attachMirrorSidebarsInternal(st, leftWidgetId, rightWidgetId, true) &&
            Settings.canDrawOverlays(host.serviceContext)
        ) {
            attachMirrorSidebarsInternal(st, leftWidgetId, rightWidgetId, false)
        }
    }

    @Suppress("RtlHardcoded")
    private fun attachMirrorSidebarsInternal(
        st: BridgeClient.Status,
        leftWidgetId: Int,
        rightWidgetId: Int,
        preferA11y: Boolean,
    ): Boolean {
        sActiveController = this
        val displays = host.displayManager?.displays ?: emptyArray()
        var target: Display? = null
        for (d in displays) {
            if (d != null && d.displayId == st.sideVdId) {
                target = d
                break
            }
        }
        if (target == null) {
            if (sideAttachedVdId != -1 && sideAttachedVdId != st.sideVdId) {
                detachMirrorSidebars()
            }
            return true
        }

        val leftW = max(0, st.mirX)
        val rightW = max(0, st.physW - (st.mirX + st.mirW))
        val topH = max(0, st.mirY)
        val bottomH = max(0, st.physH - (st.mirY + st.mirH))

        val minWing = 48
        val hasHoriz = leftW >= minWing || rightW >= minWing
        val hasVert = !hasHoriz && (topH >= minWing || bottomH >= minWing)
        if (!hasHoriz && !hasVert) {
            detachMirrorSidebars()
            return true
        }

        val singleWing = if (hasHoriz) {
            (leftW < minWing && rightW >= minWing) || (rightW < minWing && leftW >= minWing)
        } else {
            (topH < minWing && bottomH >= minWing) || (bottomH < minWing && topH >= minWing)
        }

        val svcCtx = host.serviceContext
        val a11y = NavAssistService.getInstance()
        val displayTrusted = (target.flags and 0x80 /* Display.FLAG_TRUSTED */) != 0
        val winType: Int
        val dispCtx: Context
        if (preferA11y) {
            if (!displayTrusted || a11y == null) {
                return false
            }
            winType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            dispCtx = a11y.createDisplayContext(target)
        } else if (Settings.canDrawOverlays(svcCtx)) {
            winType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            dispCtx = svcCtx.createDisplayContext(target)
        } else {
            return true
        }

        // Resolution-agnostic DPI scaling across 720p/1080p/2K/4K, 16:9/16:10/3:2/21:9 & portrait monitors
        val resScale = (min(st.physW, st.physH) / 1440f).coerceIn(0.50f, 1.65f)
        val baseDpi: Float = if (hasVert) {
            val refH = max(topH, bottomH)
            val normH = refH / resScale
            if (normH >= 700f) 265f else (normH * 190f / 400f).coerceIn(145f, 235f)
        } else {
            val refW = max(leftW, rightW)
            val normW = refW / resScale
            if (singleWing) {
                when {
                    normW >= 700f -> 270f
                    normW >= 380f -> 220f
                    else -> (normW * 164f / 256f).coerceIn(145f, 190f)
                }
            } else {
                if (normW >= 400f) 235f else (normW * 132f / 128f).coerceIn(115f, 155f)
            }
        }
        val sideDpi = (baseDpi * resScale).roundToInt().coerceIn(120, 360)

        var uiCtx = dispCtx
        try {
            val cfg = Configuration(dispCtx.resources.configuration)
            cfg.densityDpi = sideDpi
            cfg.fontScale = 1.0f
            uiCtx = dispCtx.createConfigurationContext(cfg)
        } catch (_: Throwable) {}

        val newWm = dispCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false

        val presetKeys = getMirrorPresetSlots(svcCtx)
        val presetSig = getMirrorPresetSlotsRaw(svcCtx)
        val memoTxt = getMirrorMemoText(svcCtx)
        val themeId = getMirrorThemeId(svcCtx)
        val customBg = getMirrorCustomBg(svcCtx)
        sideTheme = BridgeClient.resolveMirrorTheme(themeId, customBg)
        val themeSig = "$themeId:${Integer.toHexString(customBg)}"
        val ratioSig = "${getMirrorWidgetRatio(svcCtx, 0)}:${getMirrorWidgetRatio(svcCtx, 1)}"

        val oldLeftRoot = mirLeftRoot
        val oldRightRoot = mirRightRoot
        val oldWm = sideWm
        host.handler.removeCallbacks(pomoTickTask)
        resetMirrorSideChildRefs()

        ensureWidgetHost()
        if ((leftWidgetId > 0 || rightWidgetId > 0) && appWidgetHost != null && !widgetHostListening) {
            try {
                appWidgetHost?.startListening()
                widgetHostListening = true
            } catch (_: Throwable) {}
        }

        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        var newLeftRoot: LinearLayout? = null
        var newRightRoot: LinearLayout? = null
        var nLx = 0
        var nLy = 0
        var nLw = 0
        var nLh = 0
        var nRx = 0
        var nRy = 0
        var nRw = 0
        var nRh = 0

        try {
            if (singleWing) {
                val sx = if (hasHoriz) (if (leftW < minWing) st.mirX + st.mirW else 0) else 0
                val sy = if (hasVert) (if (topH < minWing) st.mirY + st.mirH else 0) else 0
                val sw = if (hasHoriz) (if (leftW < minWing) rightW else leftW) else st.physW
                val sh = if (hasVert) (if (topH < minWing) bottomH else topH) else st.physH
                newLeftRoot = buildMirrorUnifiedSidebar(uiCtx, sw, sh, leftWidgetId, rightWidgetId, presetKeys)
                nLx = sx
                nLy = sy
                nLw = sw
                nLh = sh
                val lpU = WindowManager.LayoutParams(sw, sh, winType, baseFlags, PixelFormat.OPAQUE)
                lpU.gravity = Gravity.TOP or Gravity.LEFT
                lpU.x = sx
                lpU.y = sy
                lpU.title = "ExtMirrorUnified"
                newWm.addView(newLeftRoot, lpU)
            } else {
                val mid = (presetKeys.size + 1) / 2
                val splitIdx = min(mid, presetKeys.size)
                val firstHalf = ArrayList(presetKeys.subList(0, splitIdx))
                val secondHalf = ArrayList(presetKeys.subList(splitIdx, presetKeys.size))
                if (hasHoriz) {
                    if (leftW >= minWing) {
                        newLeftRoot = buildMirrorDualWing(uiCtx, leftW, st.physH, leftWidgetId, true, firstHalf)
                        nLx = 0
                        nLy = 0
                        nLw = leftW
                        nLh = st.physH
                        val lpL = WindowManager.LayoutParams(leftW, st.physH, winType, baseFlags, PixelFormat.OPAQUE)
                        lpL.gravity = Gravity.TOP or Gravity.LEFT
                        lpL.x = 0
                        lpL.y = 0
                        lpL.title = "ExtMirrorLeft"
                        newWm.addView(newLeftRoot, lpL)
                    }
                    if (rightW >= minWing) {
                        newRightRoot = buildMirrorDualWing(uiCtx, rightW, st.physH, rightWidgetId, false, secondHalf)
                        nRx = st.mirX + st.mirW
                        nRy = 0
                        nRw = rightW
                        nRh = st.physH
                        val lpR = WindowManager.LayoutParams(rightW, st.physH, winType, baseFlags, PixelFormat.OPAQUE)
                        lpR.gravity = Gravity.TOP or Gravity.LEFT
                        lpR.x = st.mirX + st.mirW
                        lpR.y = 0
                        lpR.title = "ExtMirrorRight"
                        newWm.addView(newRightRoot, lpR)
                    }
                } else {
                    if (topH >= minWing) {
                        newLeftRoot = buildMirrorDualWing(uiCtx, st.physW, topH, leftWidgetId, true, firstHalf)
                        nLx = 0
                        nLy = 0
                        nLw = st.physW
                        nLh = topH
                        val lpT = WindowManager.LayoutParams(st.physW, topH, winType, baseFlags, PixelFormat.OPAQUE)
                        lpT.gravity = Gravity.TOP or Gravity.LEFT
                        lpT.x = 0
                        lpT.y = 0
                        lpT.title = "ExtMirrorTop"
                        newWm.addView(newLeftRoot, lpT)
                    }
                    if (bottomH >= minWing) {
                        newRightRoot = buildMirrorDualWing(uiCtx, st.physW, bottomH, rightWidgetId, false, secondHalf)
                        nRx = 0
                        nRy = st.mirY + st.mirH
                        nRw = st.physW
                        nRh = bottomH
                        val lpB = WindowManager.LayoutParams(st.physW, bottomH, winType, baseFlags, PixelFormat.OPAQUE)
                        lpB.gravity = Gravity.TOP or Gravity.LEFT
                        lpB.x = 0
                        lpB.y = st.mirY + st.mirH
                        lpB.title = "ExtMirrorBottom"
                        newWm.addView(newRightRoot, lpB)
                    }
                }
            }

            if (oldLeftRoot != null && oldWm != null) {
                try { oldWm.removeViewImmediate(oldLeftRoot) } catch (_: Throwable) {}
            }
            if (oldRightRoot != null && oldWm != null) {
                try { oldWm.removeViewImmediate(oldRightRoot) } catch (_: Throwable) {}
            }

            sideAttachedA11y = (a11y != null)
            sideCtx = uiCtx
            sideWm = newWm
            mirLeftRoot = newLeftRoot
            mirRightRoot = newRightRoot
            sideLeftX = nLx
            sideLeftY = nLy
            sideLeftW = nLw
            sideLeftH = nLh
            sideRightX = nRx
            sideRightY = nRy
            sideRightW = nRw
            sideRightH = nRh
            sideAttachedVdId = st.sideVdId
            sidePhysW = st.physW
            sidePhysH = st.physH
            sideMirX = st.mirX
            sideMirY = st.mirY
            sideMirW = st.mirW
            sideMirH = st.mirH
            sideModeAttached = st.mirrorSideMode
            sideAttachedLeftWidgetId = leftWidgetId
            sideAttachedRightWidgetId = rightWidgetId
            sidePresetSigAttached = presetSig
            sideMemoAttached = memoTxt
            sideThemeSigAttached = themeSig
            sideWidgetRatioSigAttached = ratioSig
            return true
        } catch (t: Throwable) {
            t.printStackTrace()
            if (newLeftRoot != null) {
                try { newWm.removeViewImmediate(newLeftRoot) } catch (_: Throwable) {}
            }
            if (newRightRoot != null) {
                try { newWm.removeViewImmediate(newRightRoot) } catch (_: Throwable) {}
            }
            if (!preferA11y) {
                detachMirrorSidebars()
                sideAttachedA11y = (a11y != null)
            }
            return false
        }
    }

    private fun makeMirrorCard(ctx: Context, compact: Boolean): LinearLayout {
        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(sideTheme.cardBgColor)
        bg.cornerRadius = dp(ctx, if (compact) 11f else 14f).toFloat()
        bg.setStroke(1, sideTheme.cardStrokeColor)
        card.background = bg
        val ph = dp(ctx, if (compact) 9f else 13f)
        val pv = dp(ctx, if (compact) 8f else 11f)
        card.setPadding(ph, pv, ph, pv)
        return card
    }

    private fun makeMirPillBtn(
        ctx: Context,
        text: String,
        bgColor: Int,
        fgColor: Int,
        compact: Boolean,
    ): TextView {
        val btn = TextView(ctx)
        btn.text = text
        btn.setTextColor(fgColor)
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 11.5f else 12.5f)
        btn.paint.isFakeBoldText = true
        btn.gravity = Gravity.CENTER
        btn.isSingleLine = true
        val bg = GradientDrawable()
        bg.setColor(bgColor)
        bg.cornerRadius = dp(ctx, 8f).toFloat()
        bg.setStroke(1, sideTheme.cardStrokeColor)
        btn.background = bg
        val ph = dp(ctx, if (compact) 7f else 10f)
        val pv = dp(ctx, if (compact) 4f else 6f)
        btn.setPadding(ph, pv, ph, pv)
        return btn
    }

    private fun setMirPillBg(btn: TextView?, bgColor: Int) {
        if (btn == null) return
        val d = btn.background
        if (d is GradientDrawable) {
            d.setColor(bgColor)
        }
    }

    private fun makeMirLabel(
        ctx: Context,
        text: String,
        sp: Float,
        color: Int,
        bold: Boolean,
    ): TextView {
        val tv = TextView(ctx)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        tv.setTextColor(color)
        if (bold) tv.paint.isFakeBoldText = true
        return tv
    }

    private fun buildMirPresetCard(ctx: Context, key: String, compact: Boolean): LinearLayout =
        when (key) {
            "battery" -> buildMirBattCard(ctx, compact)
            "wifi" -> buildMirWifiCard(ctx, compact)
            "bt" -> buildMirBtCard(ctx, compact)
            "audio" -> buildMirAudioCard(ctx, compact)
            "media" -> buildMirMediaCard(ctx, compact)
            "calendar" -> buildMirCalCard(ctx, compact)
            "perf" -> buildMirPerfCard(ctx, compact)
            "netspeed" -> buildMirNetCard(ctx, compact)
            "storage" -> buildMirStorageCard(ctx, compact)
            "pomodoro" -> buildMirPomodoroCard(ctx, compact)
            "memo" -> buildMirMemoCard(ctx, compact)
            "quickctrl" -> buildMirQuickCtrlCard(ctx, compact)
            "screendim" -> buildMirScreenDimCard(ctx, compact)
            else -> buildMirClockCard(ctx, compact)
        }

    @Suppress("RtlHardcoded")
    private fun buildMirClockCard(ctx: Context, compact: Boolean): LinearLayout {
        val clockCard = makeMirrorCard(ctx, compact)
        val clk = TextClock(ctx)
        clk.format24Hour = "HH:mm"
        clk.format12Hour = "HH:mm"
        clk.setTextColor(sideTheme.titleColor)
        clk.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 30f else 36f)
        clk.paint.isFakeBoldText = true
        clk.gravity = if (compact) Gravity.CENTER else Gravity.LEFT
        clockCard.addView(
            clk,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val dateClk = TextClock(ctx)
        dateClk.format24Hour = "M/d EEEE"
        dateClk.format12Hour = "M/d EEEE"
        dateClk.setTextColor(sideTheme.subColor)
        dateClk.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 12.5f else 14.5f)
        dateClk.gravity = if (compact) Gravity.CENTER else Gravity.LEFT
        clockCard.addView(
            dateClk,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return clockCard
    }

    private fun buildMirBattCard(ctx: Context, compact: Boolean): LinearLayout {
        val battCard = makeMirrorCard(ctx, compact)
        val battRow = LinearLayout(ctx)
        battRow.orientation = LinearLayout.HORIZONTAL
        battRow.gravity = Gravity.CENTER_VERTICAL
        val battIcon = MiniBatteryView(ctx)
        mirBattIcon = battIcon
        battRow.addView(
            battIcon,
            LinearLayout.LayoutParams(
                dp(ctx, if (compact) 24f else 28f),
                dp(ctx, if (compact) 14f else 16f),
            ),
        )
        val battMain = makeMirLabel(
            ctx,
            "100%",
            if (compact) 15.5f else 17.5f,
            if (sideTheme.lightMode) 0xFF16A34A.toInt() else 0xFF4ADE80.toInt(),
            true,
        )
        mirBattMain = battMain
        val bmLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        bmLp.leftMargin = dp(ctx, 7f)
        battRow.addView(battMain, bmLp)
        battCard.addView(battRow)

        val battSub = makeMirLabel(
            ctx,
            "电池状态读取中",
            if (compact) 11.5f else 13f,
            sideTheme.subColor,
            false,
        )
        battSub.maxLines = 2
        battSub.setPadding(0, dp(ctx, 3f), 0, 0)
        mirBattSub = battSub
        battCard.addView(battSub)
        return battCard
    }

    private fun buildMirWifiCard(ctx: Context, compact: Boolean): LinearLayout {
        val wifiCard = makeMirrorCard(ctx, compact)
        val wifiRow = LinearLayout(ctx)
        wifiRow.orientation = LinearLayout.HORIZONTAL
        wifiRow.gravity = Gravity.CENTER_VERTICAL
        val wifiIcon = MiniIconView(ctx, MiniIconView.KIND_WIFI, sideTheme.accentColor)
        mirWifiIcon = wifiIcon
        val iconSz = dp(ctx, if (compact) 18f else 20f)
        wifiRow.addView(wifiIcon, LinearLayout.LayoutParams(iconSz, iconSz))
        val wifiMain = makeMirLabel(
            ctx,
            "Wi-Fi",
            if (compact) 14f else 15.5f,
            sideTheme.titleColor,
            true,
        )
        wifiMain.isSingleLine = true
        wifiMain.ellipsize = TextUtils.TruncateAt.END
        mirWifiMain = wifiMain
        val wmLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        wmLp.leftMargin = dp(ctx, 7f)
        wifiRow.addView(wifiMain, wmLp)
        wifiCard.addView(wifiRow)

        val wifiSub = makeMirLabel(
            ctx,
            "检测中",
            if (compact) 11.5f else 13f,
            sideTheme.subColor,
            false,
        )
        wifiSub.maxLines = 2
        wifiSub.ellipsize = TextUtils.TruncateAt.END
        wifiSub.setPadding(0, dp(ctx, 3f), 0, 0)
        mirWifiSub = wifiSub
        wifiCard.addView(wifiSub)
        return wifiCard
    }

    private fun buildMirBtCard(ctx: Context, compact: Boolean): LinearLayout {
        val btCard = makeMirrorCard(ctx, compact)
        val btRow = LinearLayout(ctx)
        btRow.orientation = LinearLayout.HORIZONTAL
        btRow.gravity = Gravity.CENTER_VERTICAL
        val btIcon = MiniIconView(
            ctx,
            MiniIconView.KIND_BT,
            if (sideTheme.lightMode) 0xFF4F46E5.toInt() else 0xFF818CF8.toInt(),
        )
        mirBtIcon = btIcon
        val iconSz = dp(ctx, if (compact) 18f else 20f)
        btRow.addView(btIcon, LinearLayout.LayoutParams(iconSz, iconSz))
        val btMain = makeMirLabel(
            ctx,
            "蓝牙",
            if (compact) 14f else 15.5f,
            sideTheme.titleColor,
            true,
        )
        btMain.isSingleLine = true
        btMain.ellipsize = TextUtils.TruncateAt.END
        mirBtMain = btMain
        val btmLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        btmLp.leftMargin = dp(ctx, 7f)
        btRow.addView(btMain, btmLp)
        btCard.addView(btRow)

        val btSub = makeMirLabel(
            ctx,
            "无已连设备",
            if (compact) 11.5f else 13f,
            sideTheme.subColor,
            false,
        )
        btSub.maxLines = 2
        btSub.ellipsize = TextUtils.TruncateAt.END
        btSub.setPadding(0, dp(ctx, 3f), 0, 0)
        mirBtSub = btSub
        btCard.addView(btSub)
        return btCard
    }

    private fun buildMirAudioCard(ctx: Context, compact: Boolean): LinearLayout {
        val audCard = makeMirrorCard(ctx, compact)
        val audRow = LinearLayout(ctx)
        audRow.orientation = LinearLayout.HORIZONTAL
        audRow.gravity = Gravity.CENTER_VERTICAL
        val audioIcon = MiniIconView(ctx, MiniIconView.KIND_VOLUME, sideTheme.accentColor)
        mirAudioIcon = audioIcon
        val iconSz = dp(ctx, if (compact) 18f else 20f)
        audRow.addView(audioIcon, LinearLayout.LayoutParams(iconSz, iconSz))
        val audioRoute = makeMirLabel(
            ctx,
            "音频通道",
            if (compact) 13.5f else 15f,
            sideTheme.titleColor,
            true,
        )
        audioRoute.maxLines = 2
        audioRoute.ellipsize = TextUtils.TruncateAt.END
        mirAudioRoute = audioRoute
        val arLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        arLp.leftMargin = dp(ctx, 7f)
        audRow.addView(audioRoute, arLp)
        audCard.addView(audRow)

        val audioSub = makeMirLabel(
            ctx,
            "媒体音量",
            if (compact) 11.5f else 12.5f,
            sideTheme.subColor,
            false,
        )
        audioSub.setPadding(0, dp(ctx, 4f), 0, dp(ctx, 4f))
        mirAudioSub = audioSub
        audCard.addView(audioSub)

        val volProg = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
        volProg.max = 100
        volProg.progress = 50
        try {
            volProg.progressDrawable?.colorFilter =
                PorterDuffColorFilter(sideTheme.accentColor, PorterDuff.Mode.SRC_IN)
        } catch (_: Throwable) {}
        mirVolProg = volProg
        audCard.addView(
            volProg,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 6f)),
        )

        val ctrlRow = LinearLayout(ctx)
        ctrlRow.orientation = LinearLayout.HORIZONTAL
        ctrlRow.setPadding(0, dp(ctx, 6f), 0, 0)
        val btnVolDown = makeMirPillBtn(ctx, "音量 -", sideTheme.pillBgColor, sideTheme.titleColor, compact)
        btnVolDown.setOnClickListener { adjustMirVolume(AudioManager.ADJUST_LOWER) }
        val btnMute = makeMirPillBtn(ctx, "静音", sideTheme.pillBgColor, sideTheme.titleColor, compact)
        btnMute.setOnClickListener { adjustMirVolume(AudioManager.ADJUST_TOGGLE_MUTE) }
        val btnVolUp = makeMirPillBtn(ctx, "音量 +", sideTheme.pillBgColor, sideTheme.titleColor, compact)
        btnVolUp.setOnClickListener { adjustMirVolume(AudioManager.ADJUST_RAISE) }
        val bLp1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        bLp1.rightMargin = dp(ctx, 4f)
        val bLp2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        bLp2.rightMargin = dp(ctx, 4f)
        ctrlRow.addView(btnVolDown, bLp1)
        ctrlRow.addView(btnMute, bLp2)
        ctrlRow.addView(btnVolUp, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        audCard.addView(ctrlRow)
        return audCard
    }

    private fun adjustMirVolume(dir: Int) {
        try {
            val am = host.audioManager
            if (am != null) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
                refreshMirrorSideViews()
            }
        } catch (_: Throwable) {}
    }

    private fun buildMirMediaCard(ctx: Context, compact: Boolean): LinearLayout {
        val mediaCard = makeMirrorCard(ctx, compact)
        val mediaCol = if (sideTheme.lightMode) 0xFF7C3AED.toInt() else 0xFFA78BFA.toInt()
        val mediaBadge = makeMirLabel(ctx, "媒体播放", if (compact) 12f else 13.5f, mediaCol, true)
        HyperUi.setStartIcon(mediaBadge, HyperUi.ICON_MEDIA, mediaCol, 13f, 5f)
        mirMediaBadge = mediaBadge
        mediaCard.addView(mediaBadge)

        val mediaTitle = makeMirLabel(
            ctx,
            "当前暂无播放媒体",
            if (compact) 13.5f else 15f,
            sideTheme.titleColor,
            true,
        )
        mediaTitle.maxLines = 2
        mediaTitle.ellipsize = TextUtils.TruncateAt.END
        mediaTitle.setPadding(0, dp(ctx, 3f), 0, 0)
        mirMediaTitle = mediaTitle
        mediaCard.addView(mediaTitle)

        val mediaSub = makeMirLabel(
            ctx,
            "",
            if (compact) 11.5f else 12.5f,
            sideTheme.subColor,
            false,
        )
        mediaSub.maxLines = 2
        mediaSub.ellipsize = TextUtils.TruncateAt.END
        mediaSub.setPadding(0, dp(ctx, 2f), 0, 0)
        mirMediaSub = mediaSub
        mediaCard.addView(mediaSub)

        val mediaBtns = LinearLayout(ctx)
        mediaBtns.orientation = LinearLayout.HORIZONTAL
        mediaBtns.setPadding(0, dp(ctx, 6f), 0, 0)
        val btnPrev = makeMirPillBtn(ctx, "上一曲", sideTheme.pillBgColor, sideTheme.titleColor, compact)
        btnPrev.setOnClickListener { sendMirMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        val btnPlay = makeMirPillBtn(ctx, "播放/暂停", HyperUi.HYPER_BLUE, 0xFFF8FAFC.toInt(), compact)
        btnPlay.setOnClickListener { sendMirMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        val btnNext = makeMirPillBtn(ctx, "下一曲", sideTheme.pillBgColor, sideTheme.titleColor, compact)
        btnNext.setOnClickListener { sendMirMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
        val p1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        p1.rightMargin = dp(ctx, 4f)
        val p2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.25f)
        p2.rightMargin = dp(ctx, 4f)
        mediaBtns.addView(btnPrev, p1)
        mediaBtns.addView(btnPlay, p2)
        mediaBtns.addView(btnNext, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        mediaCard.addView(mediaBtns)
        return mediaCard
    }

    private fun sendMirMediaKey(keyCode: Int) {
        try {
            val am = host.audioManager
            if (am != null) {
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                host.handler.postDelayed({ host.pollRemoteStateAsync() }, 350L)
            }
        } catch (_: Throwable) {}
    }

    private fun buildMirCalCard(ctx: Context, compact: Boolean): LinearLayout {
        val calCard = makeMirrorCard(ctx, compact)
        val calCol = if (sideTheme.lightMode) 0xFF059669.toInt() else 0xFF34D399.toInt()
        val calHdr = makeMirLabel(ctx, "日程日历", if (compact) 12.5f else 14f, calCol, true)
        HyperUi.setStartIcon(calHdr, HyperUi.ICON_CALENDAR, calCol, 13f, 5f)
        calCard.addView(calHdr)
        val calBody = makeMirLabel(
            ctx,
            "近两日暂无日程",
            if (compact) 12f else 13f,
            sideTheme.subColor,
            false,
        )
        calBody.maxLines = if (compact) 3 else 4
        calBody.ellipsize = TextUtils.TruncateAt.END
        calBody.setPadding(0, dp(ctx, 3f), 0, 0)
        mirCalBody = calBody
        calCard.addView(calBody)
        return calCard
    }

    private fun buildMirPerfCard(ctx: Context, compact: Boolean): LinearLayout {
        val card = makeMirrorCard(ctx, compact)
        val hdr = makeMirLabel(ctx, "内存与系统负载", if (compact) 12f else 13.5f, sideTheme.accentColor, true)
        HyperUi.setStartIcon(hdr, HyperUi.ICON_CPU, sideTheme.accentColor, 13f, 5f)
        card.addView(hdr)
        val perfMain = makeMirLabel(ctx, "RAM 检测中…", if (compact) 14f else 15.5f, sideTheme.titleColor, true)
        perfMain.setPadding(0, dp(ctx, 3f), 0, 0)
        mirPerfMain = perfMain
        card.addView(perfMain)
        val perfSub = makeMirLabel(ctx, "CPU 核心状态", if (compact) 11.5f else 12.5f, sideTheme.subColor, false)
        perfSub.setPadding(0, dp(ctx, 2f), 0, dp(ctx, 4f))
        mirPerfSub = perfSub
        card.addView(perfSub)
        val perfProg = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
        perfProg.max = 100
        perfProg.progress = 50
        try {
            val barCol = if (sideTheme.lightMode) 0xFF16A34A.toInt() else 0xFF22C55E.toInt()
            perfProg.progressDrawable?.colorFilter = PorterDuffColorFilter(barCol, PorterDuff.Mode.SRC_IN)
        } catch (_: Throwable) {}
        mirPerfProg = perfProg
        card.addView(
            perfProg,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 6f)),
        )
        return card
    }

    private fun buildMirNetCard(ctx: Context, compact: Boolean): LinearLayout {
        val card = makeMirrorCard(ctx, compact)
        val hdr = makeMirLabel(ctx, "实时网速监控", if (compact) 12f else 13.5f, sideTheme.accentColor, true)
        HyperUi.setStartIcon(hdr, HyperUi.ICON_NET, sideTheme.accentColor, 13f, 5f)
        card.addView(hdr)
        val netMain = makeMirLabel(
            ctx,
            "↓ 0 KB/s · ↑ 0 KB/s",
            if (compact) 13.5f else 15f,
            sideTheme.titleColor,
            true,
        )
        netMain.setPadding(0, dp(ctx, 3f), 0, 0)
        mirNetMain = netMain
        card.addView(netMain)
        val netSub = makeMirLabel(
            ctx,
            "实时流量统计中",
            if (compact) 11.5f else 12.5f,
            sideTheme.subColor,
            false,
        )
        netSub.setPadding(0, dp(ctx, 2f), 0, 0)
        mirNetSub = netSub
        card.addView(netSub)
        return card
    }

    private fun buildMirStorageCard(ctx: Context, compact: Boolean): LinearLayout {
        val card = makeMirrorCard(ctx, compact)
        val stCol = if (sideTheme.lightMode) 0xFFD97706.toInt() else 0xFFFBBF24.toInt()
        val hdr = makeMirLabel(ctx, "存储与屏幕亮度", if (compact) 12f else 13.5f, stCol, true)
        HyperUi.setStartIcon(hdr, HyperUi.ICON_STORAGE, stCol, 13f, 5f)
        card.addView(hdr)
        val storageMain = makeMirLabel(ctx, "存储计算中…", if (compact) 14f else 15.5f, sideTheme.titleColor, true)
        storageMain.setPadding(0, dp(ctx, 3f), 0, 0)
        mirStorageMain = storageMain
        card.addView(storageMain)
        val storageSub = makeMirLabel(ctx, "亮度读取中", if (compact) 11.5f else 12.5f, sideTheme.subColor, false)
        storageSub.setPadding(0, dp(ctx, 2f), 0, dp(ctx, 4f))
        mirStorageSub = storageSub
        card.addView(storageSub)
        val storageProg = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
        storageProg.max = 100
        storageProg.progress = 40
        try {
            storageProg.progressDrawable?.colorFilter = PorterDuffColorFilter(stCol, PorterDuff.Mode.SRC_IN)
        } catch (_: Throwable) {}
        mirStorageProg = storageProg
        card.addView(
            storageProg,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 6f)),
        )
        return card
    }

    private fun buildMirPomodoroCard(ctx: Context, compact: Boolean): LinearLayout {
        val card = makeMirrorCard(ctx, compact)
        val pmCol = if (sideTheme.lightMode) 0xFFDB2777.toInt() else 0xFFF472B6.toInt()
        val hdr = makeMirLabel(ctx, "专注番茄钟", if (compact) 12f else 13.5f, pmCol, true)
        HyperUi.setStartIcon(hdr, HyperUi.ICON_TIMER, pmCol, 13f, 5f)
        card.addView(hdr)
        val pomoTime = makeMirLabel(
            ctx,
            formatCountdown(pomoRemainSec),
            if (compact) 24f else 28f,
            sideTheme.titleColor,
            true,
        )
        pomoTime.setPadding(0, dp(ctx, 2f), 0, dp(ctx, 4f))
        mirPomoTime = pomoTime
        card.addView(pomoTime)

        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        val pomoToggleBtn = makeMirPillBtn(
            ctx,
            if (pomoRunning) "暂停" else "开始专注",
            HyperUi.HYPER_BLUE,
            Color.WHITE,
            compact,
        )
        pomoToggleBtn.setOnClickListener {
            pomoRunning = !pomoRunning
            pomoLastTickMs = SystemClock.elapsedRealtime()
            host.handler.removeCallbacks(pomoTickTask)
            if (pomoRunning) host.handler.postDelayed(pomoTickTask, 1000L)
            refreshMirrorSideViews()
        }
        mirPomoToggleBtn = pomoToggleBtn
        val btnReset = makeMirPillBtn(ctx, "重置 25m", sideTheme.pillBgColor, sideTheme.titleColor, compact)
        btnReset.setOnClickListener {
            pomoRunning = false
            pomoRemainSec = 25L * 60L
            pomoLastTickMs = SystemClock.elapsedRealtime()
            host.handler.removeCallbacks(pomoTickTask)
            refreshMirrorSideViews()
        }
        if (pomoRunning) {
            host.handler.removeCallbacks(pomoTickTask)
            host.handler.postDelayed(pomoTickTask, 1000L)
        }
        val lp1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        lp1.rightMargin = dp(ctx, 5f)
        row.addView(pomoToggleBtn, lp1)
        row.addView(btnReset, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        return card
    }

    private fun buildMirMemoCard(ctx: Context, compact: Boolean): LinearLayout {
        val card = makeMirrorCard(ctx, compact)
        val mmCol = if (sideTheme.lightMode) 0xFF0D9488.toInt() else 0xFF2DD4BF.toInt()
        val hdr = makeMirLabel(ctx, "快捷桌面便签", if (compact) 12f else 13.5f, mmCol, true)
        HyperUi.setStartIcon(hdr, HyperUi.ICON_MEMO, mmCol, 13f, 5f)
        card.addView(hdr)
        val memoBody = makeMirLabel(
            ctx,
            getMirrorMemoText(host.serviceContext),
            if (compact) 12f else 13f,
            sideTheme.subColor,
            false,
        )
        memoBody.maxLines = 4
        memoBody.ellipsize = TextUtils.TruncateAt.END
        memoBody.setPadding(0, dp(ctx, 4f), 0, 0)
        mirMemoBody = memoBody
        card.addView(memoBody)
        return card
    }

    private fun toggleMirrorMainScreenDim() {
        host.execSysAndRefresh(BridgeClient.SYS_SCREEN_DIM, -1, null)
        host.handler.postDelayed({ host.pollRemoteStateAsync() }, 260L)
    }

    private fun buildMirScreenDimCard(ctx: Context, compact: Boolean): LinearLayout {
        val card = makeMirrorCard(ctx, compact)
        val dimmed = host.lastSysStatus.valid && host.lastSysStatus.screenDimmed
        val dmCol = if (sideTheme.lightMode) 0xFFB45309.toInt() else 0xFFFBBF24.toInt()
        val hdr = makeMirLabel(ctx, "主屏最低亮度", if (compact) 12f else 13.5f, dmCol, true)
        HyperUi.setStartIcon(hdr, HyperUi.ICON_MOON, dmCol, 13f, 5f)
        card.addView(hdr)

        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(ctx, 4f), 0, 0)

        val dimMain = makeMirLabel(
            ctx,
            if (dimmed) "主屏状态：最低亮度" else "主屏状态：正常亮度",
            if (compact) 11.5f else 12.5f,
            if (dimmed) (if (sideTheme.lightMode) 0xFFB45309.toInt() else 0xFFFDE047.toInt()) else sideTheme.subColor,
            false,
        )
        mirDimMain = dimMain
        row.addView(dimMain, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val dimBtn = makeMirPillBtn(
            ctx,
            if (dimmed) "恢复亮度" else "最低亮度",
            if (dimmed) HyperUi.HYPER_BLUE else 0xFF334155.toInt(),
            Color.WHITE,
            compact,
        )
        dimBtn.setOnClickListener { toggleMirrorMainScreenDim() }
        mirDimBtn = dimBtn
        val bLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        bLp.leftMargin = dp(ctx, 6f)
        row.addView(dimBtn, bLp)

        card.addView(row)
        return card
    }

    private fun buildMirQuickCtrlCard(ctx: Context, compact: Boolean): LinearLayout {
        val card = makeMirrorCard(ctx, compact)
        val hdr = makeMirLabel(ctx, "镜像快捷控制", if (compact) 12f else 13.5f, sideTheme.accentColor, true)
        HyperUi.setStartIcon(hdr, HyperUi.ICON_SLIDERS, sideTheme.accentColor, 13f, 5f)
        card.addView(hdr)
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(ctx, 6f), 0, 0)

        val bDex = makeMirPillBtn(ctx, "回 DeX", HyperUi.HYPER_BLUE, Color.WHITE, compact)
        bDex.setOnClickListener {
            host.safeBgExecute {
                BridgeClient.setMirrorMode(BridgeClient.MIRROR_MODE_DEX)
                host.safePost { host.pollRemoteStateAsync() }
            }
        }
        val bAlign = makeMirPillBtn(ctx, "换边", sideTheme.accentBtnColor, Color.WHITE, compact)
        bAlign.setOnClickListener {
            host.safeBgExecute {
                BridgeClient.setMirrorSideMode(-1)
                host.safePost { host.pollRemoteStateAsync() }
            }
        }
        val bInp = makeMirPillBtn(ctx, "切键鼠", 0xFF0F766E.toInt(), Color.WHITE, compact)
        bInp.setOnClickListener {
            host.safeBgExecute {
                BridgeClient.toggleInput()
                host.safePost { host.pollRemoteStateAsync() }
            }
        }

        val lp1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp1.rightMargin = dp(ctx, 4f)
        val lp2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp2.rightMargin = dp(ctx, 4f)
        row.addView(bDex, lp1)
        row.addView(bAlign, lp2)
        row.addView(bInp, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)

        val dimmed = host.lastSysStatus.valid && host.lastSysStatus.screenDimmed
        val quickDimBtn = makeMirPillBtn(
            ctx,
            if (dimmed) "主屏已最低亮度 · 点此恢复" else "主屏最低亮度（省电防刺眼）",
            if (dimmed) HyperUi.HYPER_BLUE else 0xFF334155.toInt(),
            Color.WHITE,
            compact,
        )
        quickDimBtn.setOnClickListener { toggleMirrorMainScreenDim() }
        mirQuickDimBtn = quickDimBtn
        val dimLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        dimLp.topMargin = dp(ctx, 5f)
        card.addView(quickDimBtn, dimLp)
        return card
    }

    private fun mirCardLp(ctx: Context, bottomMarginDp: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.bottomMargin = dp(ctx, bottomMarginDp.toFloat())
        return lp
    }

    private fun addPresetCardsGrid(
        ctx: Context,
        container: LinearLayout,
        keys: List<String>?,
        cols: Int,
        compact: Boolean,
        gapDp: Int,
    ) {
        if (keys.isNullOrEmpty()) return
        if (cols <= 1) {
            for (i in keys.indices) {
                val last = i == keys.size - 1
                container.addView(buildMirPresetCard(ctx, keys[i], compact), mirCardLp(ctx, if (last) 0 else gapDp))
            }
            return
        }
        var i = 0
        while (i < keys.size) {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.isMeasureWithLargestChildEnabled = true
            val remaining = keys.size - i
            if (remaining >= 2) {
                val c1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                c1.rightMargin = dp(ctx, gapDp.toFloat())
                row.addView(buildMirPresetCard(ctx, keys[i], compact), c1)
                row.addView(
                    buildMirPresetCard(ctx, keys[i + 1], compact),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
                )
                i += 2
            } else {
                row.addView(
                    buildMirPresetCard(ctx, keys[i], compact),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
                i += 1
            }
            container.addView(row, mirCardLp(ctx, if (i >= keys.size) 0 else gapDp))
        }
    }

    private fun buildMirrorUnifiedSidebar(
        ctx: Context,
        wingW: Int,
        wingH: Int,
        leftWidgetId: Int,
        rightWidgetId: Int,
        presets: List<String>,
    ): LinearLayout {
        val outer = LinearLayout(ctx)
        outer.setBackgroundColor(0xFF000000.toInt() or sideTheme.bgColor)
        outer.clipChildren = true
        outer.clipToPadding = true
        val density = max(0.5f, ctx.resources.displayMetrics.density)
        val dpW = wingW / density
        val dpH = wingH / density
        val wideBanner = dpW > dpH * 1.35f && dpH < 560f
        val wideBoard = dpW >= 460f
        val hasAnyWidget = leftWidgetId > 0 || rightWidgetId > 0

        if (wideBanner) {
            outer.orientation = LinearLayout.HORIZONTAL
            val pad = dp(ctx, 10f)
            outer.setPadding(pad, pad, pad, pad)

            val sv = ScrollView(ctx)
            sv.isVerticalScrollBarEnabled = false
            val cardsBox = LinearLayout(ctx)
            cardsBox.orientation = LinearLayout.VERTICAL
            addPresetCardsGrid(ctx, cardsBox, presets, if (dpW >= 680f) 2 else 1, true, 7)
            sv.addView(cardsBox)
            val cardsWFrac = if (hasAnyWidget) 0.52f else 0.76f
            val widgetsWFrac = 1.0f - cardsWFrac
            val svLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, cardsWFrac)
            svLp.rightMargin = dp(ctx, 10f)
            outer.addView(sv, svLp)

            val widgetRow = LinearLayout(ctx)
            widgetRow.orientation = LinearLayout.HORIZONTAL
            outer.addView(
                widgetRow,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, widgetsWFrac),
            )
            val both = if (leftWidgetId > 0 && rightWidgetId > 0) 2 else 1
            val widgetGap = if (both == 2) dp(ctx, 8f) else 0
            val availW = (((wingW - pad * 2 - dp(ctx, 10f)) * widgetsWFrac - widgetGap) / both.toFloat()).roundToInt()
            val availH = max(dp(ctx, 80f), wingH - pad * 2)
            appendUnifiedWidgetSlotsHorizontal(ctx, widgetRow, leftWidgetId, rightWidgetId, availW, availH, 8)
        } else if (wideBoard) {
            outer.orientation = LinearLayout.HORIZONTAL
            val pad = dp(ctx, 14f)
            outer.setPadding(pad, pad, pad, pad)

            val sv = ScrollView(ctx)
            sv.isVerticalScrollBarEnabled = false
            val infoCol = LinearLayout(ctx)
            infoCol.orientation = LinearLayout.VERTICAL
            addPresetCardsGrid(ctx, infoCol, presets, if (dpW >= 780f) 2 else 1, false, 9)
            sv.addView(infoCol)
            val infoFrac = if (hasAnyWidget) 0.47f else 0.76f
            val widgetFrac = 1.0f - infoFrac
            val infoLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, infoFrac)
            infoLp.rightMargin = dp(ctx, 12f)
            outer.addView(sv, infoLp)

            val widgetCol = LinearLayout(ctx)
            widgetCol.orientation = LinearLayout.VERTICAL
            outer.addView(
                widgetCol,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, widgetFrac),
            )

            val colAvailPx = ((wingW - pad * 2 - dp(ctx, 12f)) * widgetFrac).roundToInt()
            val colAvailH = max(dp(ctx, 120f), wingH - pad * 2)
            appendUnifiedWidgetSlots(ctx, widgetCol, leftWidgetId, rightWidgetId, colAvailPx, colAvailH, false, 10)
        } else {
            outer.orientation = LinearLayout.VERTICAL
            val padH = dp(ctx, if (dpW < 180f) 6f else 8f)
            val padV = dp(ctx, 10f)
            outer.setPadding(padH, padV, padH, padV)

            val compactCards = dpW < 290f
            val gap = if (dpW < 220f) 6 else 7
            val innerW = max(dp(ctx, 60f), wingW - padH * 2)
            val innerH = max(dp(ctx, 120f), wingH - padV * 2)

            val cardsBlock = LinearLayout(ctx)
            cardsBlock.orientation = LinearLayout.VERTICAL
            addPresetCardsGrid(ctx, cardsBlock, presets, 1, compactCards, gap)
            cardsBlock.measure(
                View.MeasureSpec.makeMeasureSpec(innerW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(innerH, View.MeasureSpec.AT_MOST),
            )
            val cardsMeasuredH = cardsBlock.measuredHeight
            val remainH = innerH - cardsMeasuredH - dp(ctx, 8f)
            val needScroll = dpH < 660f ||
                (hasAnyWidget && remainH < dp(ctx, if (leftWidgetId > 0 && rightWidgetId > 0) 260f else 170f)) ||
                (!hasAnyWidget && cardsMeasuredH > innerH)

            if (needScroll) {
                val sv = ScrollView(ctx)
                sv.isVerticalScrollBarEnabled = false
                sv.addView(cardsBlock)
                val cardsWeight = if (hasAnyWidget) 0.52f else 1.0f
                val svLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, cardsWeight)
                if (hasAnyWidget) svLp.bottomMargin = dp(ctx, 8f)
                outer.addView(sv, svLp)

                if (hasAnyWidget) {
                    val widgetHolder = LinearLayout(ctx)
                    widgetHolder.orientation = LinearLayout.VERTICAL
                    outer.addView(
                        widgetHolder,
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.48f),
                    )
                    val wAvailH = max(dp(ctx, 90f), ((innerH - dp(ctx, 8f)) * 0.48f).roundToInt())
                    appendUnifiedWidgetSlots(ctx, widgetHolder, leftWidgetId, rightWidgetId, innerW, wAvailH, compactCards, 8)
                }
            } else {
                outer.addView(
                    cardsBlock,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
                val spacer = View(ctx)
                outer.addView(spacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 8f)))
                val widgetHolder = LinearLayout(ctx)
                widgetHolder.orientation = LinearLayout.VERTICAL
                outer.addView(
                    widgetHolder,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
                )
                appendUnifiedWidgetSlots(
                    ctx,
                    widgetHolder,
                    leftWidgetId,
                    rightWidgetId,
                    innerW,
                    max(dp(ctx, 90f), remainH),
                    compactCards,
                    8,
                )
            }
        }
        return outer
    }

    private fun appendUnifiedWidgetSlots(
        ctx: Context,
        container: LinearLayout,
        leftWidgetId: Int,
        rightWidgetId: Int,
        availWpx: Int,
        availHpx: Int,
        compact: Boolean,
        gapDp: Int,
    ) {
        mirLeftWidgetBox = null
        mirRightWidgetBox = null
        val hasL = leftWidgetId > 0
        val hasR = rightWidgetId > 0
        val eachH = if (hasL && hasR) {
            max(dp(ctx, 72f), (availHpx - dp(ctx, gapDp.toFloat())) / 2)
        } else {
            max(dp(ctx, 72f), availHpx)
        }

        if (hasL || !hasR) {
            val lBox = FrameLayout(ctx)
            mirLeftWidgetBox = lBox
            val lLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            if (hasR) lLp.bottomMargin = dp(ctx, gapDp.toFloat())
            container.addView(lBox, lLp)
            mountSidebarAppWidget(ctx, lBox, leftWidgetId, 0, availWpx, eachH, compact)
        }
        if (hasR) {
            val rBox = FrameLayout(ctx)
            mirRightWidgetBox = rBox
            val rLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            container.addView(rBox, rLp)
            mountSidebarAppWidget(ctx, rBox, rightWidgetId, 1, availWpx, eachH, compact)
        }
    }

    private fun appendUnifiedWidgetSlotsHorizontal(
        ctx: Context,
        container: LinearLayout,
        leftWidgetId: Int,
        rightWidgetId: Int,
        availWpx: Int,
        availHpx: Int,
        gapDp: Int,
    ) {
        mirLeftWidgetBox = null
        mirRightWidgetBox = null
        val hasL = leftWidgetId > 0
        val hasR = rightWidgetId > 0
        if (hasL || !hasR) {
            val lBox = FrameLayout(ctx)
            mirLeftWidgetBox = lBox
            val lLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            if (hasR) lLp.rightMargin = dp(ctx, gapDp.toFloat())
            container.addView(lBox, lLp)
            mountSidebarAppWidget(ctx, lBox, leftWidgetId, 0, availWpx, availHpx, true)
        }
        if (hasR) {
            val rBox = FrameLayout(ctx)
            mirRightWidgetBox = rBox
            container.addView(
                rBox,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
            )
            mountSidebarAppWidget(ctx, rBox, rightWidgetId, 1, availWpx, availHpx, true)
        }
    }

    private fun buildMirrorDualWing(
        ctx: Context,
        wingW: Int,
        wingH: Int,
        widgetId: Int,
        isLeft: Boolean,
        presets: List<String>,
    ): LinearLayout {
        val density = max(0.5f, ctx.resources.displayMetrics.density)
        val dpW = wingW / density
        val horizWing = dpW > (wingH / density) * 1.4f
        val compact = dpW < 230f || horizWing
        val slot = if (isLeft) 0 else 1

        val outer = LinearLayout(ctx)
        outer.orientation = if (horizWing) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        outer.setBackgroundColor(0xFF000000.toInt() or sideTheme.bgColor)
        outer.clipChildren = true
        outer.clipToPadding = true
        val padH = dp(ctx, if (compact) 6f else 12f)
        val padV = dp(ctx, if (compact) 8f else 14f)
        outer.setPadding(padH, padV, padH, padV)

        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        val cardsCol = LinearLayout(ctx)
        cardsCol.orientation = LinearLayout.VERTICAL
        addPresetCardsGrid(
            ctx,
            cardsCol,
            presets,
            if (horizWing && dpW >= 540f) 2 else 1,
            compact,
            if (compact) 7 else 9,
        )
        sv.addView(cardsCol)

        val wBox = FrameLayout(ctx)
        if (isLeft) mirLeftWidgetBox = wBox else mirRightWidgetBox = wBox

        val innerW = max(dp(ctx, 56f), wingW - padH * 2)
        val innerH = max(dp(ctx, 80f), wingH - padV * 2)
        val cardsFrac = if (widgetId > 0) (if (horizWing) 0.55f else 0.54f) else 0.80f
        val widgetFrac = 1.0f - cardsFrac
        if (horizWing) {
            val svLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, cardsFrac)
            svLp.rightMargin = dp(ctx, 8f)
            outer.addView(sv, svLp)
            outer.addView(wBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, widgetFrac))
            val availW = (max(dp(ctx, 56f), innerW - dp(ctx, 8f)) * widgetFrac).roundToInt()
            mountSidebarAppWidget(ctx, wBox, widgetId, slot, availW, innerH, compact)
        } else {
            val svLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, cardsFrac)
            svLp.bottomMargin = dp(ctx, 8f)
            outer.addView(sv, svLp)
            outer.addView(wBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, widgetFrac))
            val availH = (max(dp(ctx, 72f), innerH - dp(ctx, 8f)) * widgetFrac).roundToInt()
            mountSidebarAppWidget(ctx, wBox, widgetId, slot, innerW, availH, compact)
        }
        return outer
    }

    private fun mountSidebarAppWidget(
        ctx: Context,
        box: FrameLayout?,
        widgetId: Int,
        slot: Int,
        availWpx: Int,
        availHpx: Int,
        compact: Boolean,
    ) {
        if (box == null) return
        box.removeAllViews()
        box.clipChildren = true
        box.clipToPadding = true
        if (widgetId > 0 && (availWpx < dp(ctx, 90f) || availHpx < dp(ctx, 72f))) {
            val smallCard = makeMirrorCard(ctx, true)
            smallCard.gravity = Gravity.CENTER
            val stv = makeMirLabel(ctx, "空间较窄\n未挂载小组件", 11f, sideTheme.subColor, false)
            stv.gravity = Gravity.CENTER
            smallCard.addView(stv)
            box.addView(
                smallCard,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP,
                ),
            )
            return
        }
        val mgr = appWidgetMgr
        val wHost = appWidgetHost
        if (widgetId > 0 && mgr != null && wHost != null) {
            try {
                val info = mgr.getAppWidgetInfo(widgetId)
                if (info != null) {
                    val hv = wHost.createView(ctx, widgetId, info)
                    hv.setAppWidget(widgetId, info)
                    val density = max(0.5f, ctx.resources.displayMetrics.density)
                    val ratioMode = getMirrorWidgetRatio(host.serviceContext, slot)
                    val ratio: Float = when (ratioMode) {
                        1 -> 1.0f
                        2 -> 0.75f
                        3 -> 16f / 9f
                        4 -> 0f // fill slot
                        else -> {
                            val rawRatio = when {
                                Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0 ->
                                    info.targetCellWidth / info.targetCellHeight.toFloat()
                                info.minWidth > 0 && info.minHeight > 0 ->
                                    info.minWidth / info.minHeight.toFloat()
                                else -> 1.15f
                            }
                            rawRatio.coerceIn(0.45f, 2.4f)
                        }
                    }

                    var fitW = availWpx
                    var fitH = availHpx
                    if (ratio > 0.01f) {
                        fitH = (fitW / ratio).roundToInt()
                        if (fitH > availHpx) {
                            fitH = availHpx
                            fitW = (fitH * ratio).roundToInt()
                        }
                        fitW = max(min(availWpx, dp(ctx, 88f)), min(availWpx, fitW))
                        fitH = max(min(availHpx, dp(ctx, 72f)), min(availHpx, fitH))
                    }

                    val dpFitW = max(60, (fitW / density).roundToInt())
                    val dpFitH = max(60, (fitH / density).roundToInt())
                    try {
                        val opts = Bundle()
                        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, dpFitW)
                        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, dpFitW)
                        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, dpFitH)
                        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, dpFitH)
                        if (Build.VERSION.SDK_INT >= 31) {
                            val sizes = ArrayList<SizeF>()
                            sizes.add(SizeF(dpFitW.toFloat(), dpFitH.toFloat()))
                            opts.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes)
                        }
                        hv.updateAppWidgetOptions(opts)
                    } catch (_: Throwable) {}
                    val wBg = GradientDrawable()
                    wBg.setColor(sideTheme.widgetBgColor)
                    wBg.cornerRadius = dp(ctx, 12f).toFloat()
                    wBg.setStroke(1, sideTheme.cardStrokeColor)
                    hv.background = wBg
                    hv.clipChildren = true
                    hv.clipToPadding = true
                    hv.clipToOutline = true
                    val p4 = dp(ctx, 4f)
                    hv.setPadding(p4, p4, p4, p4)
                    val hvLp = FrameLayout.LayoutParams(fitW, fitH, Gravity.CENTER)
                    box.addView(hv, hvLp)
                    return
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
        // Subtle placeholder card showing how to add system widgets
        val hint = makeMirrorCard(ctx, compact)
        hint.gravity = Gravity.CENTER
        val ht = makeMirLabel(
            ctx,
            if (compact) "系统小组件\n可在控制台绑定" else "暂无绑定系统小组件\n可在 ExtBridge 控制台\n添加天气/时钟/日历等小组件",
            if (compact) 11.5f else 13f,
            sideTheme.subColor,
            false,
        )
        ht.gravity = Gravity.CENTER
        hint.addView(ht)
        val hLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        )
        box.addView(hint, hLp)
    }

    private fun refreshMirrorSideViews() {
        val battPct = host.batteryPct
        val battCharging = host.batteryCharging
        val lastSys = host.lastSysStatus
        val lastMirTel = host.lastMirrorTelemetry

        mirBattIcon?.setBattery(battPct, battCharging)
        mirBattMain?.let { tv ->
            tv.text = "$battPct%${if (battCharging) " · 充电中" else ""}"
            val chgCol = if (sideTheme.lightMode) 0xFF16A34A.toInt() else 0xFF4ADE80.toInt()
            val lowCol = if (sideTheme.lightMode) 0xFFDC2626.toInt() else 0xFFF87171.toInt()
            tv.setTextColor(if (battCharging) chgCol else if (battPct <= 20) lowCol else sideTheme.titleColor)
        }
        mirBattSub?.let { tv ->
            val bd = if (lastMirTel.battDetail.isNotEmpty()) {
                lastMirTel.battDetail
            } else {
                if (battCharging) "充电中" else "电池供电"
            }
            tv.text = bd
        }
        mirWifiIcon?.setActiveState(lastSys.valid && lastSys.wifiOn)
        mirWifiMain?.let { tv ->
            if (!lastSys.valid || !lastSys.wifiOn) {
                tv.text = "Wi-Fi 已关闭"
            } else {
                tv.text = lastSys.wifiSsid.ifEmpty { "Wi-Fi 未连接" }
            }
        }
        mirWifiSub?.let { tv ->
            val wd = if (lastMirTel.wifiDetail.isNotEmpty()) {
                lastMirTel.wifiDetail
            } else if (lastSys.valid && lastSys.wifiOn) {
                "已连接"
            } else {
                "点击系统设置开启"
            }
            tv.text = wd
        }
        mirBtIcon?.setActiveState(lastSys.valid && lastSys.btOn)
        mirBtMain?.text = if (lastSys.valid && lastSys.btOn) "蓝牙已开启" else "蓝牙已关闭"
        mirBtSub?.let { tv ->
            if (!lastSys.valid || !lastSys.btOn) {
                tv.text = "未启用"
            } else {
                tv.text = lastSys.btName.ifEmpty { "暂未连接音频/外设" }
            }
        }

        mirAudioRoute?.text = lastMirTel.audioRoute.ifEmpty { "平板扬声器" }
        val am = host.audioManager
        val volProg = mirVolProg
        if (am != null && volProg != null) {
            val maxVol = max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val pct = (cur * 100f / maxVol).roundToInt()
            volProg.progress = pct
            mirAudioIcon?.setVolumeLevel(if (cur == 0) 0 else if (pct > 50) 2 else 1)
            mirAudioSub?.let { tv ->
                tv.text = lastMirTel.audioDetail.ifEmpty { "媒体音量 $pct%" }
            }
        }

        val mediaBadge = mirMediaBadge
        val mediaTitle = mirMediaTitle
        val mediaSub = mirMediaSub
        if (mediaBadge != null && mediaTitle != null && mediaSub != null) {
            if (lastMirTel.mediaTitle.isNotEmpty()) {
                val stTag = when (lastMirTel.mediaState) {
                    3 -> "正在播放"
                    2 -> "已暂停"
                    else -> "最近播放"
                }
                val appName = resolvePkgLabel(lastMirTel.mediaPkg)
                mediaBadge.text = if (appName.isEmpty()) stTag else "$stTag · $appName"
                mediaTitle.text = lastMirTel.mediaTitle
                mediaSub.text = lastMirTel.mediaSubtitle
                mediaSub.visibility = if (lastMirTel.mediaSubtitle.isEmpty()) View.GONE else View.VISIBLE
            } else {
                mediaBadge.text = "媒体播放"
                mediaTitle.text = "当前暂无媒体会话"
                mediaSub.visibility = View.GONE
            }
        }
        mirCalBody?.text = lastMirTel.calendarText.ifEmpty { "近两日暂无日历日程" }

        val perfMain = mirPerfMain
        val perfSub = mirPerfSub
        val perfProg = mirPerfProg
        if (perfMain != null && perfSub != null && perfProg != null && mirrorMetricsValid) {
            val upMin = SystemClock.elapsedRealtime() / 60000L
            perfMain.text = String.format(Locale.US, "可用 %.1f / %.0f GB", cachedMemAvailGb, cachedMemTotalGb)
            perfSub.text = String.format(
                Locale.US,
                "已用 %d%% · %d核心 · 运行 %dh%02dm",
                cachedMemPct,
                Runtime.getRuntime().availableProcessors(),
                upMin / 60L,
                upMin % 60L,
            )
            perfProg.progress = cachedMemPct
        }

        val netMain = mirNetMain
        val netSub = mirNetSub
        if (netMain != null && netSub != null && mirrorMetricsValid) {
            netMain.text = "↓ ${formatSpeedRate(cachedRxRate)}"
            netSub.text = "↑ ${formatSpeedRate(cachedTxRate)}" +
                String.format(Locale.US, " · 累计 %.1f MB", cachedRxTotalMb)
        }

        val storageMain = mirStorageMain
        val storageSub = mirStorageSub
        val storageProg = mirStorageProg
        if (storageMain != null && storageSub != null && storageProg != null && mirrorMetricsValid) {
            storageMain.text = String.format(
                Locale.US,
                "剩 %.1f GB (总 %.0fG)",
                cachedStorageAvailGb,
                cachedStorageTotalGb,
            )
            storageSub.text = "已用 $cachedStoragePct% · 主屏亮度 $cachedD0BrightPct%"
            storageProg.progress = cachedStoragePct
        }

        val pomoTime = mirPomoTime
        if (pomoTime != null) {
            if (pomoRunning) {
                val now = SystemClock.elapsedRealtime()
                if (pomoLastTickMs > 0L) {
                    val deltaSec = (now - pomoLastTickMs) / 1000L
                    if (deltaSec > 0L) {
                        pomoRemainSec = max(0L, pomoRemainSec - deltaSec)
                        pomoLastTickMs += deltaSec * 1000L
                        if (pomoRemainSec == 0L) {
                            pomoRunning = false
                            host.handler.removeCallbacks(pomoTickTask)
                        }
                    }
                } else {
                    pomoLastTickMs = now
                }
            }
            pomoTime.text = formatCountdown(pomoRemainSec)
            mirPomoToggleBtn?.text = if (pomoRunning) "暂停" else "开始专注"
        }

        mirMemoBody?.text = getMirrorMemoText(host.serviceContext)

        val dimmed = lastSys.valid && lastSys.screenDimmed
        mirDimMain?.let { tv ->
            tv.text = if (dimmed) "主屏状态：最低亮度" else "主屏状态：正常亮度"
            tv.setTextColor(
                if (dimmed) {
                    if (sideTheme.lightMode) 0xFFB45309.toInt() else 0xFFFDE047.toInt()
                } else {
                    sideTheme.subColor
                },
            )
        }
        mirDimBtn?.let { btn ->
            btn.text = if (dimmed) "恢复亮度" else "最低亮度"
            setMirPillBg(btn, if (dimmed) HyperUi.HYPER_BLUE else 0xFF334155.toInt())
        }
        mirQuickDimBtn?.let { btn ->
            btn.text = if (dimmed) "主屏已最低亮度 · 点此恢复" else "主屏最低亮度（省电防刺眼）"
            setMirPillBg(btn, if (dimmed) HyperUi.HYPER_BLUE else 0xFF334155.toInt())
        }
    }

    private fun resolvePkgLabel(pkg: String?): String {
        if (pkg.isNullOrEmpty()) return ""
        return try {
            host.getCachedLabel(host.serviceContext.packageManager, pkg)
        } catch (_: Throwable) {
            pkg
        }
    }

    private fun resetMirrorSideChildRefs() {
        mirBattIcon = null
        mirBattMain = null
        mirBattSub = null
        mirWifiIcon = null
        mirWifiMain = null
        mirWifiSub = null
        mirBtIcon = null
        mirBtMain = null
        mirBtSub = null
        mirAudioIcon = null
        mirAudioRoute = null
        mirAudioSub = null
        mirVolProg = null
        mirMediaBadge = null
        mirMediaTitle = null
        mirMediaSub = null
        mirCalBody = null
        mirPerfMain = null
        mirPerfSub = null
        mirPerfProg = null
        mirNetMain = null
        mirNetSub = null
        mirStorageMain = null
        mirStorageSub = null
        mirStorageProg = null
        mirPomoTime = null
        mirPomoToggleBtn = null
        mirMemoBody = null
        mirDimMain = null
        mirDimBtn = null
        mirQuickDimBtn = null
        mirLeftWidgetBox = null
        mirRightWidgetBox = null
    }

    fun detachMirrorSidebars() {
        host.handler.removeCallbacks(pomoTickTask)
        val wm = sideWm
        val left = mirLeftRoot
        if (left != null && wm != null) {
            try { wm.removeViewImmediate(left) } catch (_: Throwable) {}
        }
        val right = mirRightRoot
        if (right != null && wm != null) {
            try { wm.removeViewImmediate(right) } catch (_: Throwable) {}
        }
        if (widgetHostListening && appWidgetHost != null) {
            try { appWidgetHost?.stopListening() } catch (_: Throwable) {}
            widgetHostListening = false
        }
        mirLeftRoot = null
        mirRightRoot = null
        sideLeftX = 0
        sideLeftY = 0
        sideLeftW = 0
        sideLeftH = 0
        sideRightX = 0
        sideRightY = 0
        sideRightW = 0
        sideRightH = 0
        detachMirrorEdgeSensor()
        sideWm = null
        sideCtx = null
        sideAttachedVdId = -1
        sidePhysW = 0
        sidePhysH = 0
        sideMirX = 0
        sideMirY = 0
        sideMirW = 0
        sideMirH = 0
        sideModeAttached = -1
        sideAttachedA11y = false
        sideAttachedLeftWidgetId = -1
        sideAttachedRightWidgetId = -1
        sidePresetSigAttached = ""
        sideMemoAttached = ""
        sideThemeSigAttached = ""
        sideWidgetRatioSigAttached = ""
        resetMirrorSideChildRefs()
    }

    // ---- Live Mirror Sidebar Reflection & Touch Control API (used by SideControlActivity) ----

    fun getMirrorWingMeta(wingSlot: Int): IntArray? {
        if (sideAttachedVdId <= 0) return null
        val hasDual = mirLeftRoot != null && mirRightRoot != null && sideLeftW > 0 && sideRightW > 0
        val useRight = wingSlot == 1 && mirRightRoot != null && sideRightW > 0
        val wx = if (useRight) sideRightX else sideLeftX
        val wy = if (useRight) sideRightY else sideLeftY
        val ww = if (useRight) sideRightW else sideLeftW
        val wh = if (useRight) sideRightH else sideLeftH
        if (ww <= 0 || wh <= 0) return null
        return intArrayOf(
            wx, wy, ww, wh,
            sidePhysW, sidePhysH,
            sideAttachedVdId, if (hasDual) 1 else 0,
            sideModeAttached,
        )
    }

    fun renderMirrorWingToCanvas(canvas: Canvas?, dstW: Int, dstH: Int, wingSlot: Int): Boolean {
        if (canvas == null || dstW <= 0 || dstH <= 0) return false
        val useRight = wingSlot == 1 && mirRightRoot != null && sideRightW > 0
        val root = if (useRight) mirRightRoot else mirLeftRoot
        val ww = if (useRight) sideRightW else sideLeftW
        val wh = if (useRight) sideRightH else sideLeftH
        if (root == null || ww <= 0 || wh <= 0) return false
        val save = canvas.save()
        return try {
            canvas.scale(dstW / ww.toFloat(), dstH / wh.toFloat())
            root.draw(canvas)
            true
        } finally {
            canvas.restoreToCount(save)
        }
    }

    // ---- Physical Mouse Edge-Crossing Sensor & Pointer Capture on Display 0 ----

    fun onEdgeCaptureFromDaemon(enter: Boolean) {
        host.safePost {
            if (enter) enterSidePointerCapture()
            else exitSidePointerCapture()
        }
    }

    @Suppress("RtlHardcoded")
    private fun buildEdgeSensorLp(
        ex: Int,
        ey: Int,
        ew: Int,
        eh: Int,
        winType: Int,
        title: String,
    ): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val lp = WindowManager.LayoutParams(ew, eh, winType, flags, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.x = ex
        lp.y = ey
        lp.title = title
        return lp
    }

    private fun attachEdgeHoverListener(view: View, lp: WindowManager.LayoutParams) {
        view.setOnGenericMotionListener { _, ev ->
            if (!host.isDestroyed && !edgeCapturing && ev != null &&
                ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE &&
                (ev.source and InputDevice.SOURCE_CLASS_POINTER) != 0 &&
                edgeHoverInFlight.compareAndSet(false, true)
            ) {
                val px = lp.x + ev.x
                val py = lp.y + ev.y
                host.safeInputExecute {
                    try {
                        val res = BridgeClient.notifyMirrorEdgeHover(px, py)
                        if (res[0] == 1) {
                            host.safePost { enterSidePointerCapture() }
                        }
                    } finally {
                        edgeHoverInFlight.set(false)
                    }
                }
            }
            false
        }
    }

    private fun syncMirrorEdgeSensor(st: BridgeClient.Status?) {
        sActiveController = this
        if (st == null || st.mirrorMode != 1 || st.sideVdId <= 0 || st.mirrorSideMode <= 0 ||
            st.d0W <= 0 || st.d0H <= 0 || st.mirW <= 0 || st.mirH <= 0
        ) {
            detachMirrorEdgeSensor()
            return
        }
        val hasLeft = st.mirX >= 48
        val hasRight = (st.physW - (st.mirX + st.mirW)) >= 48
        val hasTop = (st.physW - st.mirW < 48) && st.mirY >= 48
        val hasBottom = (st.physW - st.mirW < 48) && (st.physH - (st.mirY + st.mirH)) >= 48
        if (!hasLeft && !hasRight && !hasTop && !hasBottom) {
            detachMirrorEdgeSensor()
            return
        }
        if (edgeCapturing) return

        val ex: Int
        val ey: Int
        val ew: Int
        val eh: Int
        var ex2 = -1
        var ey2 = -1
        var ew2 = 0
        var eh2 = 0
        if (hasLeft && hasRight) {
            ex = 0
            ey = 0
            ew = 5
            eh = st.d0H
            ex2 = max(0, st.d0W - 5)
            ey2 = 0
            ew2 = 5
            eh2 = st.d0H
        } else if (hasLeft) {
            ex = 0
            ey = 0
            ew = 5
            eh = st.d0H
        } else if (hasRight) {
            ex = max(0, st.d0W - 5)
            ey = 0
            ew = 5
            eh = st.d0H
        } else if (hasTop && hasBottom) {
            ex = 0
            ey = 0
            ew = st.d0W
            eh = 5
            ex2 = 0
            ey2 = max(0, st.d0H - 5)
            ew2 = st.d0W
            eh2 = 5
        } else if (hasTop) {
            ex = 0
            ey = 0
            ew = st.d0W
            eh = 5
        } else {
            ex = 0
            ey = max(0, st.d0H - 5)
            ew = st.d0W
            eh = 5
        }

        val svcCtx = host.serviceContext
        val a11y = NavAssistService.getInstance()
        val edgeWinType: Int
        val edgeCtx: Context
        if (a11y != null) {
            edgeWinType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            val d0 = host.displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            edgeCtx = if (d0 != null) a11y.createDisplayContext(d0) else a11y
        } else if (Settings.canDrawOverlays(svcCtx)) {
            edgeWinType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            edgeCtx = svcCtx
        } else {
            detachMirrorEdgeSensor()
            return
        }

        val key = "$ex,$ey,$ew,$eh;$ex2,$ey2,$ew2,$eh2,$edgeWinType"
        if (edgeSensorView != null && key == edgeSensorKey) return

        detachMirrorEdgeSensor()
        try {
            var wm0 = edgeCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm0 == null && a11y != null) {
                wm0 = a11y.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            }
            if (wm0 == null) {
                wm0 = svcCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            }
            if (wm0 == null) return
            edgeD0Wm = wm0
            val lp1 = buildEdgeSensorLp(ex, ey, ew, eh, edgeWinType, "ExtMirEdgeSensor")
            edgeLp = lp1

            val sensorView = object : FrameLayout(edgeCtx) {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    super.onWindowFocusChanged(hasFocus)
                    if (hasFocus && edgeCapturing && Build.VERSION.SDK_INT >= 26) {
                        host.handler.removeCallbacks(edgeReacquireTask)
                        post {
                            if (edgeCapturing) {
                                try {
                                    requestPointerCapture()
                                    if (hasPointerCapture()) {
                                        edgePointerCaptured = true
                                        overlayPointerCaptureSupported = true
                                        edgeReacquireAttempts = 0
                                    }
                                } catch (_: Throwable) {}
                            }
                        }
                    } else if (!hasFocus && edgeCapturing) {
                        // Transient focus blip (e.g. AppWidget trampoline Activity): seamlessly reacquire!
                        edgePointerCaptured = false
                        scheduleEdgePointerReacquire()
                    }
                }

                override fun onPointerCaptureChange(hasCapture: Boolean) {
                    super.onPointerCaptureChange(hasCapture)
                    if (hasCapture) {
                        edgePointerCaptured = true
                        overlayPointerCaptureSupported = true
                        edgeReacquireAttempts = 0
                        host.handler.removeCallbacks(edgeCaptureFallbackTask)
                        host.handler.removeCallbacks(edgeReacquireTask)
                    } else if (edgeCapturing) {
                        // Do not drop sidebar capture or jump cursor when an AppWidget steals focus momentarily
                        edgePointerCaptured = false
                        scheduleEdgePointerReacquire()
                    }
                }

                override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                    if (edgeCapturing && event != null && event.action == KeyEvent.ACTION_DOWN) {
                        val kc = event.keyCode
                        if (kc == KeyEvent.KEYCODE_ESCAPE || kc == KeyEvent.KEYCODE_BACK) {
                            host.safePost { exitSidePointerCapture() }
                            return true
                        }
                    }
                    return super.dispatchKeyEvent(event)
                }
            }
            sensorView.setBackgroundColor(Color.TRANSPARENT)
            sensorView.setOnTouchListener { _, ev ->
                if (edgeCapturing && ev != null) {
                    val act = ev.actionMasked
                    val outside = act == MotionEvent.ACTION_OUTSIDE
                    val finger = (ev.source and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN ||
                        (ev.pointerCount > 0 && ev.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE)
                    if (outside || finger) {
                        exitSidePointerCapture()
                        return@setOnTouchListener false
                    }
                }
                false
            }
            attachEdgeHoverListener(sensorView, lp1)
            if (Build.VERSION.SDK_INT >= 26) {
                sensorView.setOnCapturedPointerListener { _, ev ->
                    handleCapturedPointerEventInternal(
                        ctx = host.serviceContext,
                        ev = ev,
                        isShutdown = { host.isDestroyed },
                        dispatch = { task -> host.safeInputExecute(task) },
                        onExitCapture = Runnable { host.safePost { revertEdgeOverlayToSensor() } },
                    )
                }
            }
            edgeSensorView = sensorView
            wm0.addView(sensorView, lp1)

            if (ex2 >= 0 && ew2 > 0 && eh2 > 0) {
                val lp2 = buildEdgeSensorLp(ex2, ey2, ew2, eh2, edgeWinType, "ExtMirEdgeSensor2")
                val sensorView2 = FrameLayout(edgeCtx)
                sensorView2.setBackgroundColor(Color.TRANSPARENT)
                attachEdgeHoverListener(sensorView2, lp2)
                edgeLp2 = lp2
                edgeSensorView2 = sensorView2
                wm0.addView(sensorView2, lp2)
            }
            edgeSensorKey = key
        } catch (_: Throwable) {
            detachMirrorEdgeSensor()
        }
    }

    private fun scheduleEdgePointerReacquire() {
        if (host.isDestroyed || !edgeCapturing) return
        edgeReacquireAttempts = 0
        host.handler.removeCallbacks(edgeReacquireTask)
        host.handler.postDelayed(edgeReacquireTask, 18L)
    }

    private fun notifyDaemonCaptureExitIfNeeded() {
        if (edgeCapturing || edgePointerCaptured) {
            host.safeInputExecute { BridgeClient.sendCapturedMirrorMouse(5, 0f, 0f, 0f) }
        }
    }

    private fun enterSidePointerCapture() {
        if (host.isDestroyed || edgeCapturing) return
        val sensorView = edgeSensorView
        val d0Wm = edgeD0Wm
        val lp = edgeLp
        if (Build.VERSION.SDK_INT < 26 || sensorView == null || d0Wm == null || lp == null) {
            SideMouseCaptureActivity.launch(host.serviceContext)
            return
        }
        edgeCapturing = true
        edgePointerCaptured = false
        edgeReacquireAttempts = 0
        host.handler.removeCallbacks(edgeReacquireTask)
        try {
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            lp.width = 1
            lp.height = 1
            lp.x = 0
            lp.y = 0
            d0Wm.updateViewLayout(sensorView, lp)
            sensorView.isFocusable = true
            sensorView.isFocusableInTouchMode = true
            sensorView.requestFocus()
            sensorView.post {
                val curView = edgeSensorView
                if (!edgeCapturing || curView == null) return@post
                try {
                    curView.requestPointerCapture()
                    if (curView.hasPointerCapture()) {
                        edgePointerCaptured = true
                        overlayPointerCaptureSupported = true
                    }
                } catch (_: Throwable) {}
            }
            host.handler.removeCallbacks(edgeCaptureFallbackTask)
            if (!overlayPointerCaptureSupported) {
                host.handler.postDelayed(edgeCaptureFallbackTask, 280L)
            } else {
                host.handler.postDelayed(edgeReacquireTask, 65L)
            }
        } catch (_: Throwable) {
            notifyDaemonCaptureExitIfNeeded()
            revertEdgeOverlayToSensor()
            SideMouseCaptureActivity.launch(host.serviceContext)
        }
    }

    private fun revertEdgeOverlayToSensor() {
        edgeCapturing = false
        edgePointerCaptured = false
        edgeReacquireAttempts = 0
        resetCapturedMouseMoveState()
        host.handler.removeCallbacks(edgeCaptureFallbackTask)
        host.handler.removeCallbacks(edgeReacquireTask)
        val sensorView = edgeSensorView
        val d0Wm = edgeD0Wm
        val lp = edgeLp
        if (sensorView != null && d0Wm != null && lp != null) {
            try {
                if (Build.VERSION.SDK_INT >= 26) sensorView.releasePointerCapture()
            } catch (_: Throwable) {}
            try {
                sensorView.isFocusable = false
                sensorView.isFocusableInTouchMode = false
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                d0Wm.updateViewLayout(sensorView, lp)
            } catch (_: Throwable) {}
            try {
                edgeSensorKey = ""
                syncMirrorEdgeSensor(host.lastStatus)
            } catch (_: Throwable) {}
        }
    }

    private fun exitSidePointerCapture() {
        notifyDaemonCaptureExitIfNeeded()
        SideMouseCaptureActivity.finishActiveIfAny()
        revertEdgeOverlayToSensor()
    }

    fun detachMirrorEdgeSensor() {
        notifyDaemonCaptureExitIfNeeded()
        SideMouseCaptureActivity.finishActiveIfAny()
        resetCapturedMouseMoveState()
        edgeCapturing = false
        edgePointerCaptured = false
        edgeReacquireAttempts = 0
        edgeSensorKey = ""
        edgeHoverInFlight.set(false)
        host.handler.removeCallbacks(edgeCaptureFallbackTask)
        host.handler.removeCallbacks(edgeReacquireTask)
        val d0Wm = edgeD0Wm
        val view2 = edgeSensorView2
        if (view2 != null && d0Wm != null) {
            try { d0Wm.removeViewImmediate(view2) } catch (_: Throwable) {}
        }
        edgeSensorView2 = null
        edgeLp2 = null
        val view1 = edgeSensorView
        if (view1 != null && d0Wm != null) {
            try {
                if (Build.VERSION.SDK_INT >= 26) view1.releasePointerCapture()
            } catch (_: Throwable) {}
            try { d0Wm.removeViewImmediate(view1) } catch (_: Throwable) {}
        }
        edgeSensorView = null
        edgeLp = null
        edgeD0Wm = null
    }
}

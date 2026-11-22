package io.github.lengsukq.opaldesk.overlay

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.overlay.mirror.*
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.MirrorThemeCatalog
import io.github.lengsukq.opaldesk.protocol.MirrorWingMeta
import java.util.concurrent.ExecutorService
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Thin coordinator for Native Mirror Mode sidebars and Display 0 edge pointer capture.
 */
class MirrorSidebarController(private val host: OverlayHost) {

    companion object {
        private const val TAG = "ExtMirrorSidebar"
        const val MIRROR_WIDGET_HOST_ID = MirrorThemeCatalog.MIRROR_WIDGET_HOST_ID
        private const val WING_WM_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        @Volatile
        private var sActiveController: MirrorSidebarController? = null

        fun deleteAppWidgetId(ctx: Context, prevId: Int) {
            val active = sActiveController?.takeUnless { it.host.isDestroyed }
            (active?.appWidgetHost ?: AppWidgetHost(ctx.applicationContext, MIRROR_WIDGET_HOST_ID)).deleteAppWidgetId(prevId)
        }

        fun resetCapturedMouseMoveState() = MirrorEdgeCaptureController.resetCapturedMouseMoveState()

        fun handleCapturedPointerEventStatic(ctx: Context?, ev: MotionEvent?, exec: ExecutorService?, onExitCapture: Runnable?): Boolean =
            MirrorEdgeCaptureController.handleCapturedPointerEventStatic(ctx, ev, exec, onExitCapture)
    }

    private data class AttachedWing(val spec: WingSpec, val root: LinearLayout)

    private data class SidebarStructuralKey(
        val sideVdId: Int, val physW: Int, val physH: Int, val mirX: Int, val mirY: Int, val mirW: Int, val mirH: Int,
        val sideMode: Int, val leftWidgetId: Int, val rightWidgetId: Int,
        val presetSig: String, val themeSig: String, val ratioSig: String,
    )

    @Volatile var sideAttachedVdId: Int = -1; private set
    @Volatile private var sidePhysW = 0
    @Volatile private var sidePhysH = 0

    private var sideModeAttached = -1
    private var sideAttachedWithA11y = false
    private var lastObservedA11yOnAttach = false
    private var attachedStructuralKey: SidebarStructuralKey? = null
    private var sideTheme: BridgeClient.MirrorTheme = BridgeClient.resolveMirrorTheme("night", 0xFF0F172A.toInt())

    private var appWidgetMgr: AppWidgetManager? = null
    private var appWidgetHost: AppWidgetHost? = null
    private var widgetHostListening = false
    private var sideWm: WindowManager? = null
    private val attachedWings = mutableListOf<AttachedWing>()
    private val activeCards = mutableListOf<MirrorCard>()
    private val delayedPollTask = Runnable { host.pollRemoteStateAsync() }

    private val metricsSampler = MirrorMetricsSampler()
    private val pomodoroTimer = MirrorPomodoroTimer(host.handler, { host.isDestroyed }) { refreshMirrorSideViews() }
    private val edgeCapture = MirrorEdgeCaptureController(host) { sidePhysW to sidePhysH }

    private val cardActions = object : MirrorCardActions {
        override fun adjustVolume(direction: Int) {
            try { host.audioManager?.let { it.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0); refreshMirrorSideViews() } }
            catch (t: Throwable) { Log.w(TAG, "adjustVolume($direction) failed", t) }
        }
        override fun sendMediaKey(keyCode: Int) {
            try {
                host.audioManager?.let {
                    it.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                    it.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                    scheduleDelayedPoll(350L)
                }
            } catch (t: Throwable) { Log.w(TAG, "sendMediaKey($keyCode) failed", t) }
        }
        override fun togglePomodoro() = pomodoroTimer.toggle()
        override fun resetPomodoro() = pomodoroTimer.reset()
        override fun toggleScreenDim() { host.execSysAndRefresh(BridgeClient.SYS_SCREEN_DIM, -1, null); scheduleDelayedPoll(260L) }
        override fun switchToDex() = host.runDaemonAndPoll { BridgeClient.setMirrorMode(BridgeClient.MIRROR_MODE_DEX) }
        override fun cycleSideAlign() = host.runDaemonAndPoll { BridgeClient.setMirrorSideMode(-1) }
        override fun toggleInput() = host.runDaemonAndPoll { BridgeClient.toggleInput() }
    }

    init { sActiveController = this }

    fun sampleMirrorLocalMetrics() = metricsSampler.sample(host.serviceContext)
    fun refreshMirrorSideViewsIfAttached() { if (attachedWings.isNotEmpty()) refreshMirrorSideViews() }
    fun onEdgeCaptureFromDaemon(enter: Boolean) = edgeCapture.onEdgeCaptureFromDaemon(enter)

    fun syncOnMirrorMode(st: BridgeClient.Status, a11yNow: Boolean, lastObservedA11y: Boolean) {
        sActiveController = this
        val svcCtx = host.serviceContext
        if ((a11yNow || Settings.canDrawOverlays(svcCtx)) && st.sideVdId > 0 && st.mirrorSideMode > 0 && st.mirW > 0) {
            val key = SidebarStructuralKey(
                st.sideVdId, st.physW, st.physH, st.mirX, st.mirY, st.mirW, st.mirH, st.mirrorSideMode,
                AppPrefs.getMirrorWidgetId(svcCtx, 0), AppPrefs.getMirrorWidgetId(svcCtx, 1),
                AppPrefs.getMirrorPresetSlotsRaw(svcCtx),
                "${AppPrefs.getMirrorThemeId(svcCtx)}:${Integer.toHexString(AppPrefs.getMirrorCustomBg(svcCtx))}",
                "${AppPrefs.getMirrorWidgetRatio(svcCtx, 0)}:${AppPrefs.getMirrorWidgetRatio(svcCtx, 1)}",
            )
            // Memo text is excluded from SidebarStructuralKey so memo edits update in place without rebuilding AppWidgets.
            val a11yToggled = if (sideAttachedVdId > 0) (a11yNow != lastObservedA11yOnAttach) else (a11yNow != lastObservedA11y)
            val needA11yReattach = a11yToggled && (a11yNow || sideAttachedWithA11y)
            if (a11yToggled && !needA11yReattach) lastObservedA11yOnAttach = a11yNow
            if (attachedWings.isEmpty() || key != attachedStructuralKey || needA11yReattach) {
                if (!attachInternal(st, key, true, a11yNow) && Settings.canDrawOverlays(svcCtx)) {
                    attachInternal(st, key, false, a11yNow)
                }
            }
            refreshMirrorSideViews()
            edgeCapture.sync(st)
        } else {
            edgeCapture.detach(); detachMirrorSidebars()
        }
    }

    @Suppress("RtlHardcoded")
    private fun attachInternal(st: BridgeClient.Status, key: SidebarStructuralKey, preferA11y: Boolean, a11yNow: Boolean): Boolean {
        sActiveController = this
        val target = host.displayManager?.displays?.firstOrNull { it != null && it.displayId == st.sideVdId }
        if (target == null) {
            if (sideAttachedVdId != -1 && sideAttachedVdId != st.sideVdId) detachMirrorSidebars()
            return true
        }
        val svcCtx = host.serviceContext
        val plan = MirrorWingLayoutBuilder.computeLayoutPlan(st, AppPrefs.getMirrorPresetSlots(svcCtx), key.leftWidgetId, key.rightWidgetId)
            ?: run { detachMirrorSidebars(); return true }
        val resolved = OverlayDisplayContext.resolve(svcCtx, target, plan.sideDpi, preferA11y, requireTrustedDisplayForA11y = true)
            ?: return true

        sideTheme = AppPrefs.getActiveMirrorTheme(svcCtx)
        val oldWings = attachedWings.toList(); val oldWm = sideWm
        pomodoroTimer.stopCallbacks()
        ensureWidgetHost()
        if ((key.leftWidgetId > 0 || key.rightWidgetId > 0) && !widgetHostListening) {
            try { appWidgetHost?.startListening(); widgetHostListening = true }
            catch (t: Throwable) { Log.w(TAG, "AppWidgetHost.startListening failed", t) }
        }

        val newWm = resolved.windowManager
        val addedWings = mutableListOf<AttachedWing>(); val builtCards = mutableListOf<MirrorCard>()
        return try {
            for (spec in plan.specs) {
                val built = MirrorWingLayoutBuilder.buildWing(
                    resolved.uiContext, spec, sideTheme, cardActions, appWidgetMgr, appWidgetHost,
                ) { slot -> AppPrefs.getMirrorWidgetRatio(svcCtx, slot) }
                val lp = WindowManager.LayoutParams(spec.width, spec.height, resolved.windowType, WING_WM_FLAGS, PixelFormat.OPAQUE).apply {
                    gravity = Gravity.TOP or Gravity.LEFT; x = spec.x; y = spec.y; title = spec.title
                }
                newWm.addView(built.root, lp)
                addedWings.add(AttachedWing(spec, built.root))
                builtCards.addAll(built.cards)
            }
            removeWingsSafe(oldWings, oldWm)
            attachedWings.clear(); attachedWings.addAll(addedWings)
            activeCards.clear(); activeCards.addAll(builtCards)
            sideWm = newWm; sideAttachedWithA11y = resolved.isA11yOverlay; lastObservedA11yOnAttach = a11yNow
            sideAttachedVdId = st.sideVdId; sidePhysW = st.physW; sidePhysH = st.physH
            sideModeAttached = st.mirrorSideMode; attachedStructuralKey = key
            pomodoroTimer.syncSchedule(activeCards.any { it.key == "pomodoro" })
            true
        } catch (t: Throwable) {
            Log.w(TAG, "attachMirrorSidebars failed (a11y=${resolved.isA11yOverlay})", t)
            removeWingsSafe(addedWings, newWm)
            if (attachedWings.isEmpty() && widgetHostListening) stopWidgetListeningSafe()
            if (!resolved.isA11yOverlay) { detachMirrorSidebars(); lastObservedA11yOnAttach = a11yNow; true } else false
        }
    }

    private fun refreshMirrorSideViews() {
        if (activeCards.isEmpty()) return
        var volPct = 50; var volLevel = 1
        host.audioManager?.let { am ->
            val maxVol = max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            volPct = (cur * 100f / maxVol).roundToInt()
            volLevel = if (cur == 0) 0 else if (volPct > 50) 2 else 1
        }
        val state = MirrorCardState(
            host.batteryPct, host.batteryCharging, host.lastSysStatus, host.lastMirrorTelemetry,
            metricsSampler.snapshot, volPct, volLevel, AppPrefs.getMirrorMemoText(host.serviceContext),
            pomodoroTimer.remainSec, pomodoroTimer.isRunning, sideTheme,
        ) { pkg -> if (pkg.isNullOrEmpty()) "" else host.getCachedLabel(host.serviceContext.packageManager, pkg) }
        activeCards.forEach { it.bind(state) }
    }

    fun detachMirrorSidebars() {
        host.handler.removeCallbacks(delayedPollTask); pomodoroTimer.stopCallbacks()
        removeWingsSafe(attachedWings.toList(), sideWm)
        attachedWings.clear(); activeCards.clear()
        if (widgetHostListening) stopWidgetListeningSafe()
        edgeCapture.detach()
        sideWm = null; sideAttachedVdId = -1; sidePhysW = 0; sidePhysH = 0
        sideModeAttached = -1; sideAttachedWithA11y = false; lastObservedA11yOnAttach = false; attachedStructuralKey = null
    }

    fun detachAll() {
        edgeCapture.detach(); detachMirrorSidebars()
        if (host.isDestroyed && sActiveController === this) sActiveController = null
    }

    fun getMirrorWingMeta(wingSlot: Int): MirrorWingMeta? {
        if (sideAttachedVdId <= 0 || attachedWings.isEmpty()) return null
        val w = resolveWing(wingSlot) ?: return null
        if (w.spec.width <= 0 || w.spec.height <= 0) return null
        return MirrorWingMeta(w.spec.x, w.spec.y, w.spec.width, w.spec.height, sidePhysW, sidePhysH, sideAttachedVdId, attachedWings.size >= 2, sideModeAttached)
    }

    fun renderMirrorWingToCanvas(canvas: Canvas?, dstW: Int, dstH: Int, wingSlot: Int): Boolean {
        if (canvas == null || dstW <= 0 || dstH <= 0) return false
        val w = resolveWing(wingSlot) ?: return false
        if (w.spec.width <= 0 || w.spec.height <= 0) return false
        val save = canvas.save()
        return try {
            canvas.scale(dstW / w.spec.width.toFloat(), dstH / w.spec.height.toFloat())
            w.root.draw(canvas); true
        } finally { canvas.restoreToCount(save) }
    }

    private fun resolveWing(slot: Int) = attachedWings.firstOrNull { it.spec.slot == slot } ?: attachedWings.firstOrNull()

    private fun ensureWidgetHost() {
        if (appWidgetMgr == null) appWidgetMgr = AppWidgetManager.getInstance(host.serviceContext)
        if (appWidgetHost == null) appWidgetHost = AppWidgetHost(host.serviceContext.applicationContext, MIRROR_WIDGET_HOST_ID)
    }

    private fun stopWidgetListeningSafe() {
        try { appWidgetHost?.stopListening() } catch (t: Throwable) { Log.d(TAG, "stopListening failed: ${t.message}") }
        widgetHostListening = false
    }

    private fun removeWingsSafe(wings: List<AttachedWing>, wm: WindowManager?) {
        if (wm == null) return
        wings.forEach { try { wm.removeViewImmediate(it.root) } catch (t: Throwable) { Log.d(TAG, "removeViewImmediate(${it.spec.title}) failed: ${t.message}") } }
    }

    private fun scheduleDelayedPoll(delayMs: Long) {
        host.handler.removeCallbacks(delayedPollTask); host.handler.postDelayed(delayedPollTask, delayMs)
    }
}

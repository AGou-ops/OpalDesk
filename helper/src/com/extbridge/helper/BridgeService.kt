package com.extbridge.helper

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import com.extbridge.helper.overlay.*
import com.extbridge.helper.prefs.AppPrefs
import com.extbridge.helper.protocol.MirrorWingMeta
import com.extbridge.helper.service.BridgeNotificationBuilder
import com.extbridge.helper.service.DisplayLaunchHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Foreground service coordinating external display overlays, daemon Binder polling,
 * mDNS remote wireless discovery, and lifecycle delegation across modular overlay controllers.
 */
class BridgeService : Service(), OverlayHost {

    companion object {
        private const val TAG = "ExtBridgeService"
        private const val SURFACE_KEY_STAGE_ANIM = "stage_anim"
        private const val POLL_ACTIVE_MS = 2200L
        private const val POLL_IDLE_MS = 6000L
        private const val RIGHT_CLICK_SYNC_TIMEOUT_MS = 60L

        @Volatile private var sInstance: BridgeService? = null
        @Volatile var sUserStopping: Boolean = false
        private var sLauncherHost: ViewGroup? = null
        private var sLauncherHostDisplayId: Int = -1

        fun isBlackoutActive(): Boolean = sInstance?.blackout?.isBlackoutActive == true
        fun isDockAutoHideEnabled(ctx: Context?): Boolean = sInstance?.dock?.isDockAutoHide ?: AppPrefs.isDockAutoHide(ctx)
        fun dismissPanelOnHostTouch(winX: Float, winY: Float) { sInstance?.handleHostTouch(winX, winY) }
        fun getMirrorWingMeta(wingSlot: Int): MirrorWingMeta? = sInstance?.mirrorController?.getMirrorWingMeta(wingSlot)
        fun renderMirrorWingToCanvas(canvas: Canvas?, dstW: Int, dstH: Int, wingSlot: Int): Boolean =
            sInstance?.mirrorController?.renderMirrorWingToCanvas(canvas, dstW, dstH, wingSlot) ?: false
        fun onEdgeCaptureFromDaemon(enter: Boolean) { sInstance?.mirrorController?.onEdgeCaptureFromDaemon(enter) }
        fun resetCapturedMouseMoveState() = MirrorSidebarController.resetCapturedMouseMoveState()
        fun handleCapturedPointerEventStatic(ev: MotionEvent?, exec: ExecutorService?, onExitCapture: Runnable?): Boolean =
            MirrorSidebarController.handleCapturedPointerEventStatic(sInstance, ev, exec, onExitCapture)

        fun toggleBlackoutFromUi(ctx: Context?) {
            if (ctx != null) ensureStarted(ctx)
            val inst = sInstance
            if (inst == null) {
                Thread({ BridgeClient.execSysAction(BridgeClient.SYS_SCREEN_DIM, -1, null) }, "ExtDimToggle").start()
                return
            }
            inst.handler.post {
                val st = inst.lastStatus
                if (st.connected && st.mirrorMode == BridgeClient.MIRROR_MODE_MIRROR) {
                    if (inst.blackout.isBlackoutActive) inst.blackout.setDexBlackout(false)
                    inst.execSysAndRefresh(BridgeClient.SYS_SCREEN_DIM, -1, null)
                } else {
                    inst.blackout.toggleDexBlackout()
                }
            }
        }

        fun performAssignSplitFromUi(slot: Int, taskId: Int, pkg: String?, cls: String?) {
            val inst = sInstance
            if (inst != null && inst.stageAnimView != null) {
                val action = if (slot == 0) BridgeClient.SPLIT_ASSIGN_LEFT_TOP else BridgeClient.SPLIT_ASSIGN_RIGHT_BOTTOM
                inst.handler.post { inst.split.performEnterOrAssignSplitAnimated(action, taskId, pkg, cls) }
            } else {
                Thread({
                    BridgeClient.assignSplitSlot(slot, pkg, cls)
                    inst?.handler?.post { inst.pollRemoteStateAsync() }
                }, "ExtAssignSplit").start()
            }
        }

        fun setDockAutoHideEnabled(ctx: Context?, enabled: Boolean) {
            AppPrefs.setDockAutoHide(ctx, enabled)
            val svc = sInstance ?: return
            runOnMain(svc.handler) {
                svc.dock.isDockAutoHide = enabled
                svc.panel.updateDockAutoHideUi()
                if (!enabled) { svc.dock.cancelAutoHideTimer(); svc.dock.applyDockHiddenAnimated(false) }
                else svc.dock.evaluateDockAutoHide(false)
            }
        }

        fun ensureStarted(ctx: Context?) {
            if (sUserStopping || ctx == null) return
            val running = sInstance
            if (running != null && !running.isDestroyed) { refreshFromReceiver(ctx); return }
            try { ctx.startForegroundService(Intent(ctx, BridgeService::class.java)) }
            catch (t: Throwable) { Log.w(TAG, "Failed to startForegroundService(BridgeService)", t) }
        }

        fun refreshFromReceiver(@Suppress("UNUSED_PARAMETER") ctx: Context?) {
            if (sUserStopping) return
            sInstance?.let { it.handler.post { it.pollRemoteStateAsync() } }
        }

        fun moveCursor(x: Float, y: Float, pressing: Boolean) {
            sInstance?.let { svc -> runOnMain(svc.handler) { svc.cursor.updateCursorOnExt(x, y, pressing) } }
        }

        fun setDockHidden(hidden: Boolean) {
            sInstance?.let { svc -> runOnMain(svc.handler) { svc.dock.applyDockHidden(hidden) } }
        }

        fun setLauncherOverlayHost(host: ViewGroup?, displayId: Int) {
            sLauncherHost = host; sLauncherHostDisplayId = displayId
            sInstance?.let { svc -> runOnMain(svc.handler) { svc.syncOverlayHostSurface(false) } }
        }

        fun clearLauncherOverlayHost(host: ViewGroup?) {
            if (sLauncherHost !== host) return
            sLauncherHost = null; sLauncherHostDisplayId = -1
            sInstance?.let { svc -> runOnMain(svc.handler) { svc.syncOverlayHostSurface(false) } }
        }

        /** Thread-safe right-click target classifier: 1=handled locally, 2=dispatch secondary click, 0=Back. */
        fun classifyRightClickTarget(curX: Int, curY: Int): Int {
            val svc = sInstance ?: return 0
            return runOnMainSync(svc.handler, RIGHT_CLICK_SYNC_TIMEOUT_MS, 0) { svc.classifyRightClickOnMain(curX, curY) }
        }

        fun stopAllAndExit(ctx: Context?) {
            sUserStopping = true
            SideMouseCaptureActivity.finishActiveIfAny()
            val appCtx: Context? = ctx?.applicationContext ?: sInstance
            AdbBootstrap.killLocalAdbServerAsync(appCtx)
            val svc = sInstance
            if (svc != null) {
                svc.handler.removeCallbacksAndMessages(null)
                svc.handler.post {
                    runLogged("stop remoteDiscovery") { svc.remoteDiscovery.stop() }
                    runLogged("detachExtOverlays") { svc.detachExtOverlays(false) }
                    runLogged("mirrorController.detachAll") { svc.mirrorController.detachAll() }
                    runLogged("cancel notification") { svc.nm?.cancel(BridgeNotificationBuilder.NOTIFICATION_ID) }
                    runLogged("stopForeground") { svc.stopForeground(STOP_FOREGROUND_REMOVE) }
                }
            } else {
                runLogged("cancel notification") {
                    (appCtx?.getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(BridgeNotificationBuilder.NOTIFICATION_ID)
                }
            }
            runLogged("finish appTasks") {
                (appCtx?.getSystemService(ACTIVITY_SERVICE) as? ActivityManager)?.appTasks?.forEach {
                    runLogged("finishAndRemoveTask") { it.finishAndRemoveTask() }
                }
            }
            Thread({
                runLogged("shutdownDaemon") {
                    var i = 0
                    while (i++ < 8 && BridgeClient.getService() == null) Thread.sleep(200)
                    BridgeClient.shutdownDaemon(appCtx)
                }
                runLogged("exit pause 1") { Thread.sleep(160) }
                runLogged("stopSelf") { svc?.stopSelf() }
                runLogged("exit pause 2") { Thread.sleep(80) }
                Process.killProcess(Process.myPid())
                System.exit(0)
            }, "ExtBridgeExit").start()
        }

        private inline fun runOnMain(handler: Handler, crossinline block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { block() }
        }

        private fun <T> runOnMainSync(handler: Handler, timeoutMs: Long, defaultValue: T, block: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()
            val ref = AtomicReference(defaultValue)
            val latch = CountDownLatch(1)
            val posted = handler.post {
                try { ref.set(block()) }
                catch (t: Throwable) { Log.w(TAG, "Main-thread sync query failed", t) }
                finally { latch.countDown() }
            }
            if (!posted) return defaultValue
            try {
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) Log.d(TAG, "runOnMainSync timed out (${timeoutMs}ms)")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return ref.get()
        }

        private inline fun runLogged(step: String, block: () -> Unit) {
            try { block() } catch (t: Throwable) { Log.d(TAG, "$step failed during shutdown: ${t.message}") }
        }
    }

    // --- OverlayHost State & Coordinators ---
    override val serviceContext: Context get() = this
    override lateinit var handler: Handler; private set
    override var displayManager: DisplayManager? = null; private set
    override var audioManager: AudioManager? = null; private set
    @Volatile override var isDestroyed: Boolean = false; private set

    override val surfaceHost = OverlaySurfaceHost()
    override val popovers = PopoverCoordinator()
    override val stageChoreographer: StageChoreographer by lazy { StageChoreographer(this) }

    override lateinit var dock: DockOverlayController; private set
    override lateinit var panel: ControlPanelOverlayController; private set
    override lateinit var split: SplitScreenOverlayController; private set
    override lateinit var blackout: BlackoutOverlayController; private set
    override lateinit var cursor: CursorOverlayController; private set

    override var extCtx: Context? = null; private set
    override var extWm: WindowManager? = null; private set
    override var winType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY; private set
    override var inLauncherHost: Boolean = false; private set
    override var overlayW: Int = 0; private set
    override var overlayH: Int = 0; private set
    override var overlayDpi: Int = 0; private set
    override var stageAnimView: StageTransitionView? = null; private set
    override val launcherHost: ViewGroup? get() = sLauncherHost

    @Volatile override var lastStatus: BridgeClient.Status = BridgeClient.Status(); private set
    @Volatile override var lastWindowSnapshot: BridgeClient.WindowStateSnapshot = BridgeClient.WindowStateSnapshot(); private set
    @Volatile override var lastSysStatus: BridgeClient.SysStatus = BridgeClient.SysStatus(); private set
    @Volatile override var lastMirrorTelemetry: BridgeClient.MirrorTelemetry = BridgeClient.MirrorTelemetry(); private set
    override var batteryPct: Int = 100; private set
    override var batteryCharging: Boolean = false; private set

    // Internal service infrastructure
    private var usingA11yOverlay = false
    private var attachedVdId = -1
    private val bgExec: ExecutorService = Executors.newSingleThreadExecutor()
    private val inputBgExec: ExecutorService = Executors.newSingleThreadExecutor()
    private val pollSeq = AtomicLong(0)
    private var nm: NotificationManager? = null
    private lateinit var notifBuilder: BridgeNotificationBuilder
    private var lastObservedA11y = false
    private var stageAnimLp: WindowManager.LayoutParams? = null
    private var forceWindowOverlaysDuringAnim = false
    private val iconCache = LruCache<String, Drawable>(48)
    private val labelCache = LruCache<String, String>(64)

    private lateinit var remoteDiscovery: RemoteDiscoveryManager
    private lateinit var mirrorController: MirrorSidebarController
    private lateinit var windowChromeController: WindowChromeOverlayController

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = safePost { pollRemoteStateAsync() }
        override fun onDisplayChanged(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) {
            safePost {
                val st = lastStatus
                val singleLeft = (displayManager?.displays?.size ?: 0) <= 1
                if (displayId == attachedVdId || displayId == st.extId || displayId == st.vdId || singleLeft) {
                    if (blackout.isBlackoutActive) blackout.setDexBlackout(false)
                    if (displayId == attachedVdId || singleLeft) detachExtOverlays(false)
                }
                if (displayId == mirrorController.sideAttachedVdId || singleLeft) mirrorController.detachMirrorSidebars()
                pollRemoteStateAsync()
            }
        }
    }

    private val sysRx = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val act = intent?.action ?: return
            if (act == Intent.ACTION_SCREEN_OFF || act == Intent.ACTION_USER_PRESENT) {
                safePost { if (blackout.isBlackoutActive) blackout.setDexBlackout(false) }
                return
            }
            if (act == Intent.ACTION_BATTERY_CHANGED) readBatteryIntent(intent)
            handler.post {
                refreshLocalHardwareViews()
                if (act != Intent.ACTION_BATTERY_CHANGED) pollRemoteStateAsync()
            }
        }
    }

    private val pollTask = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            pollRemoteStateAsync()
            val st = lastStatus
            val delay = if (st.connected && (st.vdId > 0 || st.mirrorMode == 1 || st.isRemoteSink)) POLL_ACTIVE_MS else POLL_IDLE_MS
            handler.postDelayed(this, delay)
        }
    }

    // --- Service Lifecycle ---

    override fun onCreate() {
        super.onCreate()
        sInstance = this
        handler = Handler(Looper.getMainLooper())
        nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        notifBuilder = BridgeNotificationBuilder(this, nm)
        displayManager = getSystemService(DISPLAY_SERVICE) as? DisplayManager
        audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager

        AppPrefs.onMirrorPrefsChanged = { refreshFromReceiver(it) }
        AppPrefs.onDeleteAppWidgetId = { ctx, prevId -> MirrorSidebarController.deleteAppWidgetId(ctx, prevId) }

        remoteDiscovery = RemoteDiscoveryManager(this)
        dock = DockOverlayController(this).apply { isDockAutoHide = AppPrefs.isDockAutoHide(this@BridgeService) }
        panel = ControlPanelOverlayController(this)
        split = SplitScreenOverlayController(this)
        mirrorController = MirrorSidebarController(this)
        blackout = BlackoutOverlayController(this)
        cursor = CursorOverlayController(this)
        windowChromeController = WindowChromeOverlayController(this)

        notifBuilder.ensureChannel()
        enterForegroundNow(lastStatus)
        try { displayManager?.registerDisplayListener(displayListener, handler) }
        catch (t: Throwable) { Log.w(TAG, "registerDisplayListener failed", t) }

        val flt = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT); addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION); addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        val sticky = if (Build.VERSION.SDK_INT >= 33) registerReceiver(sysRx, flt, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(sysRx, flt)
        if (sticky != null) readBatteryIntent(sticky)

        NavAssistService.setOnStateChanged { safePost { detachExtOverlays(false); pollRemoteStateAsync() } }
        handler.post(pollTask)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForegroundNow(lastStatus)
        if (sUserStopping) { stopAllAndExit(this); return START_NOT_STICKY }
        val appCtx = applicationContext
        safeBgExecute { if (BridgeClient.getService() == null) BridgeClient.requestBinder(appCtx) }
        pollRemoteStateAsync()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        NavAssistService.setOnStateChanged(null)
        runLogged("unregisterDisplayListener") { displayManager?.unregisterDisplayListener(displayListener) }
        runLogged("unregisterReceiver") { unregisterReceiver(sysRx) }
        handler.removeCallbacksAndMessages(null)
        runLogged("remoteDiscovery.stop") { remoteDiscovery.stop() }
        val needDimRestore = blackout.isBlackoutActive
        blackout.restoreAndDestroy()
        isDestroyed = true; sInstance = null
        detachExtOverlays(false)
        mirrorController.detachAll()
        SideMouseCaptureActivity.finishActiveIfAny()
        MirrorSidebarController.resetCapturedMouseMoveState()
        inputBgExec.shutdownNow()
        if (needDimRestore) bgExec.shutdown() else bgExec.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForegroundNow(st: BridgeClient.Status) {
        try {
            val n = notifBuilder.build(st, usingA11yOverlay)
            if (Build.VERSION.SDK_INT >= 34) startForeground(BridgeNotificationBuilder.NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(BridgeNotificationBuilder.NOTIFICATION_ID, n)
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed", t)
        }
    }

    private fun readBatteryIntent(bi: Intent) {
        val lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (lvl >= 0 && scale > 0) batteryPct = (lvl * 100f / scale).roundToInt().coerceIn(0, 100)
        batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    // --- State Polling & Overlay Coordination ---

    override fun pollRemoteStateAsync() {
        if (isDestroyed) return
        val seq = pollSeq.incrementAndGet()
        safeBgExecute {
            val st = BridgeClient.getStatus()
            val dexActive = st.connected && st.mirrorMode == 0 && st.vdId > 0
            val tasks = if (dexActive) BridgeClient.getTasks() else null
            val winSnap = if (dexActive) BridgeClient.getManagedWindows() else BridgeClient.WindowStateSnapshot()
            val needSys = st.connected && (st.vdId > 0 || (st.mirrorMode == 1 && st.sideVdId > 0))
            val sys = if (needSys) BridgeClient.getSysStatus() else lastSysStatus
            val inMirrorSide = st.connected && st.mirrorMode == 1 && st.sideVdId > 0
            val mt = if (inMirrorSide) BridgeClient.getMirrorTelemetry() else lastMirrorTelemetry
            if (inMirrorSide) mirrorController.sampleMirrorLocalMetrics()
            safePost { if (seq == pollSeq.get()) applyStateOnUi(st, tasks, sys, mt, winSnap) }
        }
    }

    private fun applyStateOnUi(
        st: BridgeClient.Status, tasks: List<BridgeClient.TaskEntry>?,
        sys: BridgeClient.SysStatus?, mt: BridgeClient.MirrorTelemetry?, winSnap: BridgeClient.WindowStateSnapshot,
    ) {
        if (isDestroyed) return
        lastStatus = st; lastWindowSnapshot = winSnap
        if (sys != null && sys.valid) lastSysStatus = sys
        if (mt != null && mt.valid) lastMirrorTelemetry = mt

        remoteDiscovery.sync(st.connected && st.isWifiServerEnabled, st.wifiServerPort, st.wifiCertFingerprint)
        cursor.updateRemoteSuppression(st)
        notifBuilder.notifyIfChanged(st, usingA11yOverlay)

        val a11yNow = NavAssistService.isRunning()
        if (st.connected && st.mirrorMode == 1) {
            if (blackout.isBlackoutActive) blackout.setDexBlackout(false)
            detachExtOverlays(false)
            mirrorController.syncOnMirrorMode(st, a11yNow, lastObservedA11y)
            lastObservedA11y = a11yNow
            return
        }
        mirrorController.detachAll()
        if (!st.connected || st.vdId <= 0) {
            if (blackout.isBlackoutActive) blackout.setDexBlackout(false)
            detachExtOverlays(false); return
        }
        if (!a11yNow && !Settings.canDrawOverlays(this)) return

        val targetDpi = BridgeClient.computeOverlayDpi(st.virtW, st.virtH, st.virtDpi)
        if (st.vdId != attachedVdId || a11yNow != lastObservedA11y || targetDpi != overlayDpi) {
            attachExtOverlays(st)
        } else if (st.virtW != overlayW || st.virtH != overlayH) {
            handleOverlayBoundsChanged(st)
        }

        val topExt = tasks?.firstOrNull { it.isTopExt() }
        dock.isOnLauncherHome = inLauncherHost || (tasks != null && (topExt == null || OverlayConstants.HELPER_PKG == topExt.pkg))

        updateInputToggleUi(st.inputToExt)
        if (tasks != null) dock.updateTaskStrip(tasks)
        refreshLocalHardwareViews()
        refreshSysViews()
        panel.updateStatusChips(st)
        split.updateSplitUi(st)
        windowChromeController.updateFromState(st, winSnap, tasks)
        dock.evaluateDockAutoHide(false)
    }

    private fun handleOverlayBoundsChanged(st: BridgeClient.Status) {
        overlayW = st.virtW; overlayH = st.virtH; dock.clearTaskSignature()
        stageAnimLp?.let { lp ->
            lp.width = max(OverlayConstants.MIN_SURFACE_PX, overlayW)
            lp.height = max(OverlayConstants.MIN_SURFACE_PX, overlayH)
            surfaceHost.updateWindowLayout(stageAnimView, lp, extWm)
        }
        repositionInteractiveSurfaces()
        windowChromeController.onOverlayBoundsChanged()
    }

    private fun attachExtOverlays(st: BridgeClient.Status) {
        detachExtOverlays(true)
        val target = displayManager?.displays?.firstOrNull { it.displayId == st.vdId } ?: return
        val targetDpi = BridgeClient.computeOverlayDpi(st.virtW, st.virtH, st.virtDpi)
        lastObservedA11y = NavAssistService.isRunning()

        val primary = OverlayDisplayContext.resolve(this, target, targetDpi, preferA11y = true) ?: return
        if (!tryAttach(primary, st) && primary.isA11yOverlay && Settings.canDrawOverlays(this)) {
            OverlayDisplayContext.resolve(this, target, targetDpi, preferA11y = false)?.let { tryAttach(it, st) }
        }
    }

    private fun tryAttach(resolved: OverlayDisplayContext, st: BridgeClient.Status): Boolean = try {
        val uiCtx = resolved.uiContext
        extCtx = uiCtx; extWm = resolved.windowManager; winType = resolved.windowType
        usingA11yOverlay = resolved.isA11yOverlay; attachedVdId = st.vdId
        overlayW = st.virtW; overlayH = st.virtH; overlayDpi = resolved.targetDpi
        dock.clearTaskSignature()

        buildStageAnimOverlay(uiCtx, st)
        split.buildSplitOverlays(uiCtx)
        dock.buildDockOverlays(uiCtx, st)
        panel.buildPanelOverlay(uiCtx, st)
        windowChromeController.buildOverlays(uiCtx, st)
        cursor.buildCursorOverlay(uiCtx, st)
        syncOverlayHostSurface(true)
        notifBuilder.resetSignature(); notifBuilder.notifyIfChanged(st, usingA11yOverlay)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "attachExtOverlays failed (a11y=${resolved.isA11yOverlay})", t)
        detachExtOverlays(true)
        false
    }

    private fun buildStageAnimOverlay(ctx: Context, st: BridgeClient.Status) {
        val anim = StageTransitionView(ctx)
        val lp = OverlayWindowParams.fullscreenNonTouchable(
            max(OverlayConstants.MIN_SURFACE_PX, st.virtW), max(OverlayConstants.MIN_SURFACE_PX, st.virtH), winType,
        )
        stageAnimView = anim; stageAnimLp = lp
        surfaceHost.register(
            OverlaySurfaceHost.SurfaceEntry(SURFACE_KEY_STAGE_ANIM, OverlayLayer.STAGE_ANIM, anim, lp, canDetachForReorder = { !anim.isAnimating() }),
        )
    }

    private fun shouldHostInLauncher(): Boolean {
        val hasManagedFg = lastWindowSnapshot.windows.any { !it.minimized && !OverlayConstants.isSystemOrLauncherPkg(it.pkg) }
        return !forceWindowOverlaysDuringAnim &&
            stageAnimView?.isAnimating() != true &&
            !usingA11yOverlay && attachedVdId > 0 && sLauncherHost != null &&
            sLauncherHostDisplayId == attachedVdId && !lastStatus.isMultiTilingActive &&
            !BridgeClient.isTilingTopology(lastWindowSnapshot.tilingTopology) && !hasManagedFg
    }

    override fun syncOverlayHostSurface(forceAttach: Boolean) {
        val dockRoot = dock.dockRoot ?: return
        if (extCtx == null) return
        val wantHost = shouldHostInLauncher()
        if (!forceAttach && wantHost == inLauncherHost) {
            val p = dockRoot.parent
            if (if (wantHost) p === sLauncherHost else (p != null && p !is ViewGroup)) return
        }
        split.resetClosedVisualsBeforeHostSwap()
        dock.resetClosedVisualsBeforeHostSwap()
        panel.resetClosedVisualsBeforeHostSwap()

        inLauncherHost = wantHost
        surfaceHost.reattachAll(wantHost, sLauncherHost, extWm)
        repositionInteractiveSurfaces()
        if (wantHost) sLauncherHost?.post { if (inLauncherHost) repositionInteractiveSurfaces() }
        dock.evaluateDockAutoHide(false)
    }

    private fun repositionInteractiveSurfaces() {
        dock.syncDockEdgeVisibility()
        dock.repositionDock()
        split.repositionSplitDivider()
        split.repositionSplitMenu()
        dock.repositionTaskBubbleIfOpen()
        panel.repositionIfOpen()
    }

    private fun classifyRightClickOnMain(curX: Int, curY: Int): Int {
        if (popovers.isAnyOpen()) { popovers.dismissAll(); return 1 }
        val st = lastStatus
        if (st.connected && st.mirrorMode == BridgeClient.MIRROR_MODE_DEX) {
            val dockRoot = dock.dockRoot
            if (!dock.isDockHidden && dockRoot != null && dockRoot.visibility == View.VISIBLE) {
                if (OverlayUiUtils.isPointInViewOnScreen(dock.taskStrip, curX.toFloat(), curY.toFloat())) return 2
                if (OverlayUiUtils.isPointInViewOnScreen(dock.btnDockSplit, curX.toFloat(), curY.toFloat())) {
                    split.toggleSplitMenu(); return 1
                }
            }
            if ((dock.isOnLauncherHome || st.splitActive == 1) && LauncherActivity.shouldConsumeRightClickAt(curX, curY)) return 2
        }
        return 0
    }

    private fun handleHostTouch(winX: Float, winY: Float) {
        if (!inLauncherHost) return
        if (panel.isOpen && !panel.isPointInCard(winX, winY) && !OverlayUiUtils.isPointInView(dock.trayBtn, winX, winY)) panel.dismiss()
        if (dock.isTaskBubbleOpen() && !dock.isPointInTaskBubbleCard(winX, winY)) dock.dismissTaskBubble()
        windowChromeController.dismissMenus()
    }

    private fun detachExtOverlays(keepBlackout: Boolean) {
        dock.cancelAutoHideTimer()
        if (!keepBlackout) {
            if (blackout.isBlackoutActive) blackout.setDexBlackout(false) else blackout.removeBlackoutOverlay()
        }
        stageAnimView?.cancelActiveAnimation(); forceWindowOverlaysDuringAnim = false
        windowChromeController.detach(); split.detach(); dock.detach(); panel.detach(); cursor.detach()
        surfaceHost.detachAll(extWm); popovers.clear()

        inLauncherHost = false; extWm = null; extCtx = null; usingA11yOverlay = false
        winType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        stageAnimView = null; stageAnimLp = null; attachedVdId = -1; overlayW = 0; overlayH = 0; overlayDpi = 0
    }

    private fun computeVolumeIconLevel(): Int {
        var muted = false; var volPct = 50
        audioManager?.let { am ->
            val maxVol = max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            try { muted = am.isStreamMute(AudioManager.STREAM_MUSIC) || curVol == 0 }
            catch (t: Throwable) { Log.d(TAG, "isStreamMute unavailable: ${t.message}") }
            volPct = (curVol * 100f / maxVol).roundToInt()
        }
        return if (muted) 0 else if (volPct > 50) 2 else 1
    }

    private fun refreshLocalHardwareViews() {
        val lvl = computeVolumeIconLevel()
        dock.refreshLocalHardwareViews(lvl); panel.refreshLocalHardwareViews(lvl)
        mirrorController.refreshMirrorSideViewsIfAttached()
    }

    private fun refreshSysViews() {
        val sys = lastSysStatus
        if (sys.valid) { dock.refreshSysViews(sys); panel.refreshSysViews(sys) }
    }

    // --- OverlayHost Service Capabilities ---

    override fun safeBgExecute(r: Runnable) = executeSafe(bgExec, "bgExec", r)
    override fun safeInputExecute(r: Runnable) = executeSafe(inputBgExec, "inputBgExec", r)
    override fun safePost(r: Runnable) { if (!isDestroyed) handler.post { if (!isDestroyed) r.run() } }

    private fun executeSafe(exec: ExecutorService, name: String, r: Runnable) {
        if (isDestroyed || exec.isShutdown) return
        try { exec.execute { if (!isDestroyed) r.run() } }
        catch (t: RejectedExecutionException) { Log.d(TAG, "$name rejected task during shutdown: ${t.message}") }
    }

    override fun beginWindowOverlayStage() { forceWindowOverlaysDuringAnim = true; syncOverlayHostSurface(false); cursor.bringCursorToFront() }
    override fun endWindowOverlayStage() { forceWindowOverlaysDuringAnim = false; syncOverlayHostSurface(false); cursor.bringCursorToFront() }

    override fun cloneDrawable(d: Drawable?): Drawable? = try {
        d?.constantState?.newDrawable(resources)?.mutate() ?: d
    } catch (t: Throwable) { Log.d(TAG, "cloneDrawable fallback: ${t.message}"); d }

    override fun getCachedIcon(pm: PackageManager, pkg: String): Drawable? {
        iconCache.get(pkg)?.let { return cloneDrawable(it) }
        return try { pm.getApplicationIcon(pkg).also { iconCache.put(pkg, it) }.let(::cloneDrawable) }
        catch (t: Throwable) { Log.d(TAG, "getApplicationIcon($pkg) failed: ${t.message}"); null }
    }

    override fun getCachedLabel(pm: PackageManager, pkg: String): String {
        labelCache.get(pkg)?.let { return it }
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().also { if (it.isNotEmpty()) labelCache.put(pkg, it) }.ifEmpty { pkg }
        } catch (t: Throwable) { Log.d(TAG, "getApplicationLabel($pkg) failed: ${t.message}"); pkg }
    }

    override fun buildCardSpec(pkg: String?, snap: BridgeClient.TaskSnapshotData?, startR: Rect, endR: Rect) =
        StageTransitionView.CardSpec().apply {
            if (snap?.bitmap != null) { snapshot = snap.bitmap; ownSnapshot = snap.ownBitmap }
            if (!pkg.isNullOrEmpty() && OverlayConstants.HELPER_PKG != pkg) {
                icon = getCachedIcon(packageManager, pkg); label = getCachedLabel(packageManager, pkg)
            } else { label = "扩展桌面" }
            setRects(startR, endR)
        }

    override fun setDockAutoHideEnabled(enabled: Boolean) = setDockAutoHideEnabled(this, enabled)
    override fun updateSplitButtons(splitActive: Boolean) { dock.updateDockSplitButton(splitActive); panel.updateSplitButton(splitActive) }
    override fun updateInputToggleUi(inputToExt: Int) { dock.updateInputToggleUi(inputToExt); panel.updateInputToggleUi(inputToExt) }
    override fun onLocalHardwareRefreshRequested() = refreshLocalHardwareViews()
    override fun refreshTrayIconsOnly() { val lvl = computeVolumeIconLevel(); dock.refreshVolumeIconOnly(lvl); panel.refreshVolumeIconOnly(lvl) }

    override fun openExtSettings(action: String) {
        popovers.dismissAll()
        if (usingA11yOverlay) safeBgExecute { BridgeClient.execSysAction(BridgeClient.SYS_OPEN_SETTINGS, -1, action) }
        else {
            Toast.makeText(this, "未开启无障碍：已在平板打开系统设置", Toast.LENGTH_SHORT).show()
            safeBgExecute { BridgeClient.execSysAction(BridgeClient.SYS_OPEN_SETTINGS, 0, action) }
        }
    }

    override fun openTabletMain() {
        popovers.dismissAll()
        DisplayLaunchHelper.launchOnDisplay0(this, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun openTabletTouchpad() {
        popovers.dismissAll()
        DisplayLaunchHelper.launchOnDisplay0(this, Intent(this, TouchpadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun execSysAndRefresh(act: Int, arg: Int, str: String?) {
        safeBgExecute {
            BridgeClient.execSysAction(act, arg, str)
            safePost {
                handler.postDelayed({
                    safeBgExecute {
                        val sys = BridgeClient.getSysStatus()
                        safePost { if (sys.valid) lastSysStatus = sys; refreshSysViews(); mirrorController.refreshMirrorSideViewsIfAttached() }
                    }
                }, 220L)
            }
        }
    }
}

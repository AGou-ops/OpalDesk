package com.extbridge.helper

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
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
import android.util.LruCache
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.extbridge.helper.overlay.BlackoutOverlayController
import com.extbridge.helper.overlay.ControlPanelOverlayController
import com.extbridge.helper.overlay.CursorOverlayController
import com.extbridge.helper.overlay.DockOverlayController
import com.extbridge.helper.overlay.MirrorSidebarController
import com.extbridge.helper.overlay.OverlayHost
import com.extbridge.helper.overlay.OverlayUiUtils
import com.extbridge.helper.overlay.SplitScreenOverlayController
import com.extbridge.helper.overlay.WindowChromeOverlayController
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Foreground service coordinating external display overlays, daemon Binder status polling,
 * mDNS remote wireless discovery, and lifecycle delegation across modular overlay controllers.
 */
class BridgeService : Service(), OverlayHost {

    companion object {
        const val ACTION_EXIT_ALL = "com.extbridge.helper.EXIT_ALL"
        const val ACTION_DISCONNECT_REMOTE = "com.extbridge.helper.DISCONNECT_REMOTE"
        const val MIRROR_WIDGET_HOST_ID = MirrorSidebarController.MIRROR_WIDGET_HOST_ID

        private const val CH_ID = "extbridge_ctrl_v3"
        private const val NOTIF_ID = 301
        private const val PREF_NAME = "ext_prefs"
        private const val PREF_DOCK_AUTO_HIDE = "dock_auto_hide"

        @Volatile
        private var sInstance: BridgeService? = null

        @JvmField
        @Volatile
        var sUserStopping: Boolean = false

        private var sLauncherHost: ViewGroup? = null
        private var sLauncherHostDisplayId: Int = -1

        @JvmStatic
        fun isRunning(): Boolean = sInstance != null

        @JvmStatic
        fun isBlackoutActive(): Boolean = sInstance?.blackoutController?.isBlackoutActive == true

        @JvmStatic
        fun toggleBlackoutFromUi(ctx: Context?) {
            if (ctx != null) ensureStarted(ctx)
            val inst = sInstance
            if (inst != null) {
                inst.handler.post {
                    val st = inst.lastStatus
                    if (st.connected && st.mirrorMode == BridgeClient.MIRROR_MODE_MIRROR) {
                        if (inst.blackoutController.isBlackoutActive) {
                            inst.blackoutController.setDexBlackout(false)
                        }
                        inst.execSysAndRefresh(BridgeClient.SYS_SCREEN_DIM, -1, null)
                    } else {
                        inst.blackoutController.toggleDexBlackout()
                    }
                }
            } else {
                Thread { BridgeClient.execSysAction(BridgeClient.SYS_SCREEN_DIM, -1, null) }.start()
            }
        }

        @JvmStatic
        fun performAssignSplitFromUi(slot: Int, taskId: Int, pkg: String?, cls: String?) {
            val inst = sInstance
            if (inst != null && inst.stageAnimView != null) {
                val action = if (slot == 0) BridgeClient.SPLIT_ASSIGN_LEFT_TOP else BridgeClient.SPLIT_ASSIGN_RIGHT_BOTTOM
                inst.handler.post {
                    inst.splitController.performEnterOrAssignSplitAnimated(action, slot, taskId, pkg, cls)
                }
            } else {
                Thread {
                    BridgeClient.assignSplitSlot(slot, pkg, cls)
                    inst?.handler?.post { inst.pollRemoteStateAsync() }
                }.start()
            }
        }

        @JvmStatic
        fun isDockAutoHideEnabled(ctx: Context?): Boolean {
            sInstance?.let { return it.dockController.isDockAutoHide }
            if (ctx == null) return true
            return ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(PREF_DOCK_AUTO_HIDE, true)
        }

        @JvmStatic
        fun setDockAutoHideEnabled(ctx: Context?, enabled: Boolean) {
            ctx?.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                ?.edit()?.putBoolean(PREF_DOCK_AUTO_HIDE, enabled)?.apply()
            val svc = sInstance ?: return
            val task = Runnable {
                svc.dockController.isDockAutoHide = enabled
                svc.panelController.updateDockAutoHideUi()
                if (!enabled) {
                    svc.dockController.cancelAutoHideTimer()
                    svc.dockController.applyDockHiddenAnimated(false)
                } else {
                    svc.dockController.evaluateDockAutoHide(false)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) task.run() else svc.handler.post(task)
        }

        @JvmStatic
        fun ensureStarted(ctx: Context?) {
            if (sUserStopping || ctx == null) return
            val running = sInstance
            if (running != null && !running.isDestroyed) {
                refreshFromReceiver(ctx)
                return
            }
            try {
                val i = Intent(ctx, BridgeService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (_: Throwable) {}
        }

        @JvmStatic
        fun refreshFromReceiver(ctx: Context?) {
            if (sUserStopping) return
            val svc = sInstance ?: return
            svc.handler.post { svc.pollRemoteStateAsync() }
        }

        @JvmStatic
        fun stopAllAndExit(ctx: Context?) {
            sUserStopping = true
            SideMouseCaptureActivity.finishActiveIfAny()
            val appCtx: Context? = ctx?.applicationContext ?: sInstance
            AdbBootstrap.killLocalAdbServerAsync(appCtx)
            val svc = sInstance
            if (svc != null) {
                svc.handler.removeCallbacksAndMessages(null)
                svc.handler.post {
                    try { svc.remoteDiscovery.stop() } catch (_: Throwable) {}
                    try { svc.detachExtOverlays(false) } catch (_: Throwable) {}
                    try { svc.mirrorController.detachAll() } catch (_: Throwable) {}
                    try { svc.nm?.cancel(NOTIF_ID) } catch (_: Throwable) {}
                    try {
                        if (Build.VERSION.SDK_INT >= 24) {
                            svc.stopForeground(STOP_FOREGROUND_REMOVE)
                        } else {
                            @Suppress("DEPRECATION")
                            svc.stopForeground(true)
                        }
                    } catch (_: Throwable) {}
                }
            } else if (appCtx != null) {
                try {
                    (appCtx.getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTIF_ID)
                } catch (_: Throwable) {}
            }

            if (appCtx != null) {
                try {
                    val am = appCtx.getSystemService(ACTIVITY_SERVICE) as? ActivityManager
                    am?.appTasks?.forEach { t ->
                        try { t.finishAndRemoveTask() } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }

            Thread({
                try {
                    var i = 0
                    while (i < 8 && BridgeClient.getService() == null) {
                        Thread.sleep(200)
                        i++
                    }
                    BridgeClient.shutdownDaemon(appCtx)
                } catch (_: Throwable) {}
                try { Thread.sleep(160) } catch (_: Throwable) {}
                try { svc?.stopSelf() } catch (_: Throwable) {}
                try { Thread.sleep(80) } catch (_: Throwable) {}
                Process.killProcess(Process.myPid())
                System.exit(0)
            }, "ExtBridgeExit").start()
        }

        @JvmStatic
        fun moveCursor(x: Float, y: Float, pressing: Boolean) {
            val svc = sInstance ?: return
            if (Looper.myLooper() == svc.handler.looper) {
                svc.cursorController.updateCursorOnExt(x, y, pressing)
            } else {
                svc.handler.post { svc.cursorController.updateCursorOnExt(x, y, pressing) }
            }
        }

        @JvmStatic
        fun setDockHidden(hidden: Boolean) {
            val svc = sInstance ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                svc.dockController.applyDockHidden(hidden)
            } else {
                svc.handler.post { svc.dockController.applyDockHidden(hidden) }
            }
        }

        @JvmStatic
        fun setLauncherOverlayHost(host: ViewGroup?, displayId: Int) {
            sLauncherHost = host
            sLauncherHostDisplayId = displayId
            val svc = sInstance ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                svc.syncOverlayHostSurface(false)
            } else {
                svc.handler.post { svc.syncOverlayHostSurface(false) }
            }
        }

        @JvmStatic
        fun clearLauncherOverlayHost(host: ViewGroup?) {
            if (sLauncherHost === host) {
                sLauncherHost = null
                sLauncherHostDisplayId = -1
                val svc = sInstance ?: return
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    svc.syncOverlayHostSurface(false)
                } else {
                    svc.handler.post { svc.syncOverlayHostSurface(false) }
                }
            }
        }

        @JvmStatic
        fun dismissPanelOnHostTouch(winX: Float, winY: Float) {
            sInstance?.handleHostTouch(winX, winY)
        }

        /**
         * Classifies what virtual-touchpad right-click / 2-finger tap should do at (curX, curY):
         *   1 = handled locally (dismissed an open Dock popover or toggled Split Menu)
         *   2 = dispatch BUTTON_SECONDARY context-click (Dock task icon or DeX Desktop app icon)
         *   0 = navigate Back on external display
         */
        @JvmStatic
        fun classifyRightClickTarget(curX: Int, curY: Int): Int {
            val svc = sInstance ?: return 0
            if (svc.panelController.isPanelOpen ||
                svc.splitController.isSplitMenuOpen ||
                svc.dockController.isTaskBubbleOpen ||
                svc.windowChromeController.isAnyMenuOpen
            ) {
                val closeTask = Runnable {
                    svc.panelController.setPanelOpen(false)
                    svc.splitController.setSplitMenuOpen(false)
                    svc.dockController.setTaskBubbleOpen(false)
                    svc.windowChromeController.dismissMenus()
                }
                if (Looper.myLooper() == Looper.getMainLooper()) closeTask.run() else svc.handler.post(closeTask)
                return 1
            }
            val st = svc.lastStatus
            if (st.connected && st.mirrorMode == BridgeClient.MIRROR_MODE_DEX) {
                val dockRoot = svc.dockController.dockRoot
                if (!svc.dockController.isDockHidden && dockRoot != null && dockRoot.visibility == View.VISIBLE) {
                    if (OverlayUiUtils.isPointInViewOnScreen(svc.dockController.taskStrip, curX.toFloat(), curY.toFloat())) {
                        return 2
                    }
                    if (OverlayUiUtils.isPointInViewOnScreen(svc.dockController.btnDockSplit, curX.toFloat(), curY.toFloat())) {
                        val toggleSplit = Runnable {
                            svc.splitController.setSplitMenuOpen(!svc.splitController.isSplitMenuOpen)
                        }
                        if (Looper.myLooper() == Looper.getMainLooper()) toggleSplit.run() else svc.handler.post(toggleSplit)
                        return 1
                    }
                }
                if ((svc.dockController.isOnLauncherHome || st.splitActive == 1) &&
                    LauncherActivity.shouldConsumeRightClickAt(curX, curY)
                ) {
                    return 2
                }
            }
            return 0
        }

        // --- Mirror Sidebar Preferences & Live Reflection Static Delegates ---

        @JvmStatic
        fun getMirrorPresetSlots(ctx: Context?): List<String> =
            MirrorSidebarController.getMirrorPresetSlots(ctx)

        @JvmStatic
        fun getMirrorPresetSlotsRaw(ctx: Context?): String =
            MirrorSidebarController.getMirrorPresetSlotsRaw(ctx)

        @JvmStatic
        fun setMirrorPresetSlots(ctx: Context?, slots: List<String>?) =
            MirrorSidebarController.setMirrorPresetSlots(ctx, slots)

        @JvmStatic
        fun getMirrorMemoText(ctx: Context?): String =
            MirrorSidebarController.getMirrorMemoText(ctx)

        @JvmStatic
        fun setMirrorMemoText(ctx: Context?, txt: String?) =
            MirrorSidebarController.setMirrorMemoText(ctx, txt)

        @JvmStatic
        fun getMirrorWidgetId(ctx: Context?, slot: Int): Int =
            MirrorSidebarController.getMirrorWidgetId(ctx, slot)

        @JvmStatic
        fun setMirrorWidgetId(ctx: Context?, slot: Int, widgetId: Int) =
            MirrorSidebarController.setMirrorWidgetId(ctx, slot, widgetId)

        @JvmStatic
        fun getMirrorThemeId(ctx: Context?): String =
            MirrorSidebarController.getMirrorThemeId(ctx)

        @JvmStatic
        fun setMirrorThemeId(ctx: Context?, id: String?) =
            MirrorSidebarController.setMirrorThemeId(ctx, id)

        @JvmStatic
        fun getMirrorCustomBg(ctx: Context?): Int =
            MirrorSidebarController.getMirrorCustomBg(ctx)

        @JvmStatic
        fun setMirrorCustomBg(ctx: Context?, bgColor: Int) =
            MirrorSidebarController.setMirrorCustomBg(ctx, bgColor)

        @JvmStatic
        fun getActiveMirrorTheme(ctx: Context?): BridgeClient.MirrorTheme =
            MirrorSidebarController.getActiveMirrorTheme(ctx)

        @JvmStatic
        fun getMirrorCursorSpeedIdx(ctx: Context?): Int =
            MirrorSidebarController.getMirrorCursorSpeedIdx(ctx)

        @JvmStatic
        fun getMirrorCursorSpeedMult(ctx: Context?): Float =
            MirrorSidebarController.getMirrorCursorSpeedMult(ctx)

        @JvmStatic
        fun setMirrorCursorSpeedIdx(ctx: Context?, idx: Int) =
            MirrorSidebarController.setMirrorCursorSpeedIdx(ctx, idx)

        @JvmStatic
        fun getMirrorWidgetRatio(ctx: Context?, slot: Int): Int =
            MirrorSidebarController.getMirrorWidgetRatio(ctx, slot)

        @JvmStatic
        fun setMirrorWidgetRatio(ctx: Context?, slot: Int, mode: Int) =
            MirrorSidebarController.setMirrorWidgetRatio(ctx, slot, mode)

        @JvmStatic
        fun getMirrorWingMeta(wingSlot: Int): IntArray? =
            sInstance?.mirrorController?.getMirrorWingMeta(wingSlot)

        @JvmStatic
        fun renderMirrorWingToCanvas(canvas: Canvas?, dstW: Int, dstH: Int, wingSlot: Int): Boolean =
            sInstance?.mirrorController?.renderMirrorWingToCanvas(canvas, dstW, dstH, wingSlot) ?: false

        @JvmStatic
        fun onEdgeCaptureFromDaemon(enter: Boolean) {
            sInstance?.mirrorController?.onEdgeCaptureFromDaemon(enter)
        }

        @JvmStatic
        fun resetCapturedMouseMoveState() {
            MirrorSidebarController.resetCapturedMouseMoveState()
        }

        @JvmStatic
        fun handleCapturedPointerEventStatic(
            ev: MotionEvent?,
            exec: ExecutorService?,
            onExitCapture: Runnable?,
        ): Boolean = MirrorSidebarController.handleCapturedPointerEventStatic(sInstance, ev, exec, onExitCapture)

        private fun safeAddToHost(host: ViewGroup?, child: View?, lp: ViewGroup.LayoutParams) {
            if (host == null || child == null) return
            val parent = child.parent
            if (parent is ViewGroup) {
                parent.removeView(child)
            }
            if (child.parent == null) {
                host.addView(child, lp)
            }
        }
    }

    // --- OverlayHost State ---
    override val serviceContext: Context get() = this
    override lateinit var handler: Handler
        private set
    override var displayManager: DisplayManager? = null
        private set
    override var audioManager: AudioManager? = null
        private set
    @Volatile
    override var isDestroyed: Boolean = false
        private set

    override var extCtx: Context? = null
        private set
    override var extWm: WindowManager? = null
        private set
    override var winType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        private set
    override var usingA11yOverlay: Boolean = false
        private set
    override var inLauncherHost: Boolean = false
        private set
    override var attachedVdId: Int = -1
        private set
    override var overlayW: Int = 0
        private set
    override var overlayH: Int = 0
        private set
    override var overlayDpi: Int = 0
        private set
    override var stageAnimView: StageTransitionView? = null
        private set
    override val launcherHost: ViewGroup? get() = sLauncherHost

    @Volatile
    override var lastStatus: BridgeClient.Status = BridgeClient.Status()
        private set
    @Volatile
    override var lastWindowSnapshot: BridgeClient.WindowStateSnapshot = BridgeClient.WindowStateSnapshot()
        private set
    @Volatile
    override var lastSysStatus: BridgeClient.SysStatus = BridgeClient.SysStatus()
        private set
    @Volatile
    override var lastMirrorTelemetry: BridgeClient.MirrorTelemetry = BridgeClient.MirrorTelemetry()
        private set
    override var batteryPct: Int = 100
        private set
    override var batteryCharging: Boolean = false
        private set

    // Internal service infrastructure
    private val bgExec: ExecutorService = Executors.newSingleThreadExecutor()
    private val inputBgExec: ExecutorService = Executors.newSingleThreadExecutor()
    private val pollSeq = AtomicLong(0)
    private var lastNotifSig: String = ""
    private var nm: NotificationManager? = null
    private var lastObservedA11y: Boolean = false
    private var stageAnimLp: WindowManager.LayoutParams? = null
    private var forceWindowOverlaysDuringAnim: Boolean = false

    private val iconCache = LruCache<String, Drawable>(48)
    private val labelCache = LruCache<String, String>(64)

    // Modular Controllers + mDNS Discovery
    private lateinit var remoteDiscovery: RemoteDiscoveryManager
    private lateinit var dockController: DockOverlayController
    private lateinit var panelController: ControlPanelOverlayController
    private lateinit var splitController: SplitScreenOverlayController
    private lateinit var mirrorController: MirrorSidebarController
    private lateinit var blackoutController: BlackoutOverlayController
    private lateinit var cursorController: CursorOverlayController
    private lateinit var windowChromeController: WindowChromeOverlayController

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            safePost { pollRemoteStateAsync() }
        }

        override fun onDisplayRemoved(displayId: Int) {
            safePost {
                val st = lastStatus
                val singleLeft = (displayManager?.displays?.size ?: 0) <= 1
                if (displayId == attachedVdId || displayId == st.extId || displayId == st.vdId || singleLeft) {
                    if (blackoutController.isBlackoutActive) {
                        blackoutController.setDexBlackout(false)
                    }
                    if (displayId == attachedVdId || singleLeft) {
                        detachExtOverlays(false)
                    }
                }
                if (displayId == mirrorController.sideAttachedVdId || singleLeft) {
                    mirrorController.detachMirrorSidebars()
                }
                pollRemoteStateAsync()
            }
        }

        override fun onDisplayChanged(displayId: Int) = Unit
    }

    private val sysRx = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val act = intent?.action ?: return
            if (Intent.ACTION_SCREEN_OFF == act || Intent.ACTION_USER_PRESENT == act) {
                safePost {
                    if (blackoutController.isBlackoutActive) {
                        blackoutController.setDexBlackout(false)
                    }
                }
                return
            }
            if (Intent.ACTION_BATTERY_CHANGED == act) {
                readBatteryIntent(intent)
            }
            handler.post {
                refreshLocalHardwareViews()
                if (Intent.ACTION_BATTERY_CHANGED != act) {
                    pollRemoteStateAsync()
                }
            }
        }
    }

    private val pollTask = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            pollRemoteStateAsync()
            val st = lastStatus
            val delay = if (st.connected && (st.vdId > 0 || st.mirrorMode == 1 || st.isRemoteSink)) 2200L else 6000L
            handler.postDelayed(this, delay)
        }
    }

    // --- Service Lifecycle ---

    override fun onCreate() {
        super.onCreate()
        sInstance = this
        handler = Handler(Looper.getMainLooper())
        nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        displayManager = getSystemService(DISPLAY_SERVICE) as? DisplayManager
        audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager

        remoteDiscovery = RemoteDiscoveryManager(this)
        dockController = DockOverlayController(this).apply {
            isDockAutoHide = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(PREF_DOCK_AUTO_HIDE, true)
        }
        panelController = ControlPanelOverlayController(this)
        splitController = SplitScreenOverlayController(this)
        mirrorController = MirrorSidebarController(this)
        blackoutController = BlackoutOverlayController(this)
        cursorController = CursorOverlayController(this)
        windowChromeController = WindowChromeOverlayController(this)

        createNotificationChannel()
        enterForegroundNow(lastStatus)

        try { displayManager?.registerDisplayListener(displayListener, handler) } catch (_: Throwable) {}

        val flt = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        val sticky = if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sysRx, flt, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(sysRx, flt)
        }
        if (sticky != null) readBatteryIntent(sticky)

        NavAssistService.setOnStateChanged {
            safePost {
                detachExtOverlays(false)
                pollRemoteStateAsync()
            }
        }

        handler.post(pollTask)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForegroundNow(lastStatus)
        if (sUserStopping) {
            stopAllAndExit(this)
            return START_NOT_STICKY
        }
        val appCtx = applicationContext
        safeBgExecute {
            if (BridgeClient.getService() == null) {
                BridgeClient.requestBinder(appCtx)
            }
        }
        pollRemoteStateAsync()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isDestroyed = true
        sInstance = null
        NavAssistService.setOnStateChanged(null)
        try { displayManager?.unregisterDisplayListener(displayListener) } catch (_: Throwable) {}
        try { unregisterReceiver(sysRx) } catch (_: Throwable) {}
        handler.removeCallbacksAndMessages(null)
        try { remoteDiscovery.stop() } catch (_: Throwable) {}
        blackoutController.restoreAndDestroy()
        detachExtOverlays(false)
        mirrorController.detachAll()
        SideMouseCaptureActivity.finishActiveIfAny()
        MirrorSidebarController.resetCapturedMouseMoveState()
        inputBgExec.shutdownNow()
        bgExec.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForegroundNow(st: BridgeClient.Status) {
        try {
            val n = buildNotification(st)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (_: Throwable) {}
    }

    private fun readBatteryIntent(bi: Intent) {
        val lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (lvl >= 0 && scale > 0) {
            batteryPct = (lvl * 100f / scale).roundToInt().coerceIn(0, 100)
        }
        batteryCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH_ID, "ExtBridge 外接屏控制", NotificationManager.IMPORTANCE_LOW).apply {
                description = "通知栏一键切换平板/扩展屏键鼠、进入大触摸板及 Mac 无线大屏状态"
                setShowBadge(false)
            }
            nm?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(st: BridgeClient.Status): Notification {
        val optBundle0 = ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()

        val mainIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val piMain = PendingIntent.getActivity(
            this, 1, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, optBundle0,
        )

        val tpIntent = Intent(this, TouchpadActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val piTp = PendingIntent.getActivity(
            this, 2, tpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, optBundle0,
        )

        val sideCtrlIntent = Intent(this, SideControlActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val piSideCtrl = PendingIntent.getActivity(
            this, 7, sideCtrlIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, optBundle0,
        )

        val piToggle = PendingIntent.getBroadcast(
            this, 3,
            Intent(this, ControlReceiver.Internal::class.java).setAction("com.extbridge.helper.TOGGLE_INPUT"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val piExtHome = PendingIntent.getBroadcast(
            this, 4,
            Intent(this, ControlReceiver.Internal::class.java).setAction("com.extbridge.helper.LAUNCH_EXT_HOME"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val piMirror = PendingIntent.getBroadcast(
            this, 5,
            Intent(this, ControlReceiver.Internal::class.java)
                .setAction("com.extbridge.helper.TOGGLE_MIRROR").putExtra("mode", 0),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val piExit = PendingIntent.getBroadcast(
            this, 6,
            Intent(this, ControlReceiver.Internal::class.java).setAction(ACTION_EXIT_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val piDisconnectRemote = PendingIntent.getBroadcast(
            this, 8,
            Intent(this, ControlReceiver.Internal::class.java).setAction(ACTION_DISCONNECT_REMOTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val remoteActive = st.connected && st.isRemoteSink
        val mirrorActive = st.connected && st.mirrorMode == 1 && st.extId > 0 && !remoteActive
        val standbyOrDisconnected = !st.connected || (!remoteActive && !mirrorActive && st.vdId <= 0)

        val title: String
        val body: String
        val toggleLabel: String
        when {
            !st.connected -> {
                title = "ExtBridge 未连接后台服务"
                body = "点击打开控制台激活，或点下方「停止并退出」关闭常驻"
                toggleLabel = "⇄ 切换键鼠"
            }
            remoteActive -> {
                val peer = st.remotePeerName.ifBlank { "Mac" }
                val fpsStr = BridgeClient.formatStreamFps(st.streamFpsX100)
                val brStr = BridgeClient.formatBitrateMbps(st.streamBitrateKbps)
                val channel = when (st.sinkType) {
                    BridgeClient.SINK_REMOTE_USB -> "Mac 有线大屏 (USB)"
                    BridgeClient.SINK_REMOTE_WIFI_ADB -> "Mac 无线 ADB 大屏"
                    BridgeClient.SINK_REMOTE_WIFI -> "Mac 无线 Wi-Fi 大屏"
                    else -> "Mac 远端大屏"
                }
                title = "ExtBridge $channel · $peer"
                body = "$peer · $fpsStr · $brStr | ${BridgeClient.mirrorModeLabel(st.mirrorMode)} ${st.virtW}×${st.virtH}"
                toggleLabel = "断开 Mac"
            }
            mirrorActive -> {
                title = "ExtBridge 原生镜像模式 · 物理 Display #${st.extId}"
                body = "外接屏正在同步手机/平板主屏 | 点击右侧「切回 DeX」恢复独立大屏桌面"
                toggleLabel = if (st.inputToExt == 1) "⇄ 切到本机" else "⇄ 切回外接"
            }
            st.vdId <= 0 -> {
                if (st.isWifiServerEnabled) {
                    val port = if (st.wifiServerPort > 0) st.wifiServerPort else BridgeClient.DEFAULT_WIFI_REMOTE_PORT
                    title = "ExtBridge 无线大屏监听中 · :$port"
                    body = "局域网 mDNS 广播就绪，亦支持接入 Type-C / USB 连接 Mac"
                } else {
                    title = "ExtBridge 待机中 · 等待外接屏或 Mac 连接"
                    body = "预设 ${st.virtW}×${st.virtH} (${st.virtDpi}dpi) | 不用时可点「停止并退出」"
                }
                toggleLabel = if (st.inputToExt == 1) "⇄ 切到平板" else "⇄ 切回扩展屏"
            }
            else -> {
                val target = if (st.inputToExt == 1) "外接扩展屏" else "平板本机"
                val modeTag = if (usingA11yOverlay) "无障碍B" else "兼容A"
                title = "ExtBridge 已挂载($modeTag) · ${st.virtW}×${st.virtH} @${st.virtFps.roundToInt()}Hz"
                body = "键鼠控制：【$target】 | 点通知本体进控制台，右侧按钮一键回桌面"
                toggleLabel = if (st.inputToExt == 1) "键鼠切回本机" else "键鼠切至扩展屏"
            }
        }

        val nb = Notification.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_stat_extbridge)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(piMain)

        when {
            standbyOrDisconnected -> {
                nb.addAction(Notification.Action.Builder(null, "打开控制台", piMain).build())
                nb.addAction(Notification.Action.Builder(null, "停止并退出", piExit).build())
            }
            remoteActive -> {
                nb.addAction(Notification.Action.Builder(null, "断开 Mac", piDisconnectRemote).build())
                nb.addAction(Notification.Action.Builder(null, "控制台", piMain).build())
                val act3Label = if (st.mirrorMode == 1) "切回 DeX" else "回扩展桌面"
                val act3Pi = if (st.mirrorMode == 1) piMirror else piExtHome
                nb.addAction(Notification.Action.Builder(null, act3Label, act3Pi).build())
            }
            else -> {
                val act1Label = if (mirrorActive) "切回 DeX" else toggleLabel
                val act1Pi = if (mirrorActive) piMirror else piToggle
                val act3Label = if (mirrorActive) "副屏直控" else "回扩展桌面"
                val act3Pi = if (mirrorActive) piSideCtrl else piExtHome
                nb.addAction(Notification.Action.Builder(null, act1Label, act1Pi).build())
                nb.addAction(Notification.Action.Builder(null, "大触摸板", piTp).build())
                nb.addAction(Notification.Action.Builder(null, act3Label, act3Pi).build())
            }
        }
        return nb.build()
    }

    private fun notifyIfChanged(st: BridgeClient.Status) {
        val mgr = nm ?: return
        val brBucket = st.streamBitrateKbps / 500
        val fpsBucket = st.streamFpsX100 / 100
        val sig = "${if (st.connected) 1 else 0}:${st.sinkType}:${st.remotePeerName}:$brBucket:$fpsBucket" +
            ":${st.wifiServerEnabled}:${st.wifiServerPort}:${st.mirrorMode}:${st.extId}:${st.vdId}" +
            ":${st.virtW}x${st.virtH}@${st.virtDpi}:${st.virtFps.roundToInt()}:${st.inputToExt}" +
            ":${if (usingA11yOverlay) 1 else 0}"
        if (sig != lastNotifSig) {
            lastNotifSig = sig
            mgr.notify(NOTIF_ID, buildNotification(st))
        }
    }

    // --- State Polling & Coordination ---

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
            if (inMirrorSide) {
                mirrorController.sampleMirrorLocalMetrics()
            }
            safePost {
                if (seq == pollSeq.get()) {
                    applyStateOnUi(st, tasks, sys, mt, winSnap)
                }
            }
        }
    }

    private fun applyStateOnUi(
        st: BridgeClient.Status,
        tasks: List<BridgeClient.TaskEntry>?,
        sys: BridgeClient.SysStatus?,
        mt: BridgeClient.MirrorTelemetry?,
        winSnap: BridgeClient.WindowStateSnapshot,
    ) {
        if (isDestroyed) return
        lastStatus = st
        lastWindowSnapshot = winSnap
        if (sys != null && sys.valid) lastSysStatus = sys
        if (mt != null && mt.valid) lastMirrorTelemetry = mt

        // Sync LAN mDNS advertisement with daemon wireless remote server state
        remoteDiscovery.sync(
            enabled = st.connected && st.isWifiServerEnabled,
            port = st.wifiServerPort,
            certFingerprint = st.wifiCertFingerprint,
        )

        // Suppress Android software cursor whenever a remote Mac display owns the pointer
        cursorController.updateRemoteSuppression(st)

        notifyIfChanged(st)

        val a11yNow = NavAssistService.isRunning()
        if (st.connected && st.mirrorMode == 1) {
            if (blackoutController.isBlackoutActive) {
                blackoutController.setDexBlackout(false)
            }
            detachExtOverlays(false)
            mirrorController.syncOnMirrorMode(st, a11yNow, lastObservedA11y)
            lastObservedA11y = a11yNow
            return
        }

        mirrorController.detachAll()

        if (!st.connected || st.vdId <= 0) {
            if (blackoutController.isBlackoutActive) {
                blackoutController.setDexBlackout(false)
            }
            detachExtOverlays(false)
            return
        }
        if (!a11yNow && !Settings.canDrawOverlays(this)) return

        val targetOverlayDpi = BridgeClient.computeOverlayDpi(st.virtW, st.virtH, st.virtDpi)
        if (st.vdId != attachedVdId || a11yNow != lastObservedA11y || targetOverlayDpi != overlayDpi) {
            attachExtOverlays(st)
        } else if (st.virtW != overlayW || st.virtH != overlayH) {
            overlayW = st.virtW
            overlayH = st.virtH
            dockController.clearTaskSignature()
            val anim = stageAnimView
            val animLp = stageAnimLp
            if (anim != null && animLp != null && extWm != null) {
                animLp.width = overlayW
                animLp.height = overlayH
                try { extWm?.updateViewLayout(anim, animLp) } catch (_: Throwable) {}
            }
            dockController.repositionDock()
            panelController.repositionPanel()
            splitController.repositionSplitDivider()
            splitController.repositionSplitMenu()
            windowChromeController.onOverlayBoundsChanged()
        }

        var launcherTop = tasks != null
        if (tasks != null) {
            for (t in tasks) {
                if (t.isTopExt()) {
                    if ("com.extbridge.helper" != t.pkg) {
                        launcherTop = false
                    }
                    break
                }
            }
        }
        dockController.isOnLauncherHome = inLauncherHost || launcherTop

        updateInputToggleUi(st.inputToExt)
        if (tasks != null) dockController.updateTaskStrip(tasks)
        refreshLocalHardwareViews()
        refreshSysViews()
        panelController.updateStatusChips(st)
        splitController.updateSplitUi(st)
        windowChromeController.updateFromState(st, winSnap, tasks)
        dockController.evaluateDockAutoHide(false)
    }

    private fun attachExtOverlays(st: BridgeClient.Status) {
        detachExtOverlays(true)
        val target = displayManager?.displays?.firstOrNull { it.displayId == st.vdId } ?: return

        val a11y = NavAssistService.getInstance()
        lastObservedA11y = a11y != null
        if (a11y != null) {
            try {
                attachWithContext(
                    a11y.createDisplayContext(target),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    true,
                    st,
                )
                lastNotifSig = ""
                notifyIfChanged(st)
                return
            } catch (t: Throwable) {
                t.printStackTrace()
                detachExtOverlays(true)
                lastObservedA11y = true
            }
        }
        if (Settings.canDrawOverlays(this)) {
            try {
                attachWithContext(
                    createDisplayContext(target),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    false,
                    st,
                )
                lastNotifSig = ""
                notifyIfChanged(st)
            } catch (t: Throwable) {
                t.printStackTrace()
                detachExtOverlays(true)
            }
        }
    }

    private fun attachWithContext(dispCtx: Context, winType: Int, isA11y: Boolean, st: BridgeClient.Status) {
        val targetDpi = BridgeClient.computeOverlayDpi(st.virtW, st.virtH, st.virtDpi)
        var uiCtx = dispCtx
        try {
            val cfg = Configuration(dispCtx.resources.configuration)
            var needCfg = false
            if (cfg.densityDpi != targetDpi) {
                cfg.densityDpi = targetDpi
                needCfg = true
            }
            if (cfg.fontScale != 1.0f) {
                cfg.fontScale = 1.0f
                needCfg = true
            }
            if (needCfg) {
                uiCtx = dispCtx.createConfigurationContext(cfg)
            }
        } catch (_: Throwable) {}

        extCtx = uiCtx
        extWm = dispCtx.getSystemService(WINDOW_SERVICE) as? WindowManager
        this.winType = winType
        usingA11yOverlay = isA11y
        attachedVdId = st.vdId
        overlayW = st.virtW
        overlayH = st.virtH
        overlayDpi = targetDpi
        dockController.clearTaskSignature()

        buildStageAnimOverlay(uiCtx, st)
        splitController.buildSplitOverlays(uiCtx, st)
        dockController.buildDockEdgeTriggerOverlay(uiCtx)
        dockController.buildDockOverlay(uiCtx, st)
        dockController.buildTaskBubbleOverlay(uiCtx)
        panelController.buildPanelOverlay(uiCtx, st)
        windowChromeController.buildOverlays(uiCtx, st)
        cursorController.buildCursorOverlay(uiCtx, st)
        syncOverlayHostSurface(true)
    }

    private fun buildStageAnimOverlay(ctx: Context, st: BridgeClient.Status) {
        val animView = StageTransitionView(ctx)
        val lp = WindowManager.LayoutParams(
            max(600, st.virtW),
            max(600, st.virtH),
            winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 0
        }
        stageAnimView = animView
        stageAnimLp = lp
        extWm?.addView(animView, lp)
    }

    private fun shouldHostInLauncher(): Boolean {
        val anim = stageAnimView
        val hasManagedForeground = lastWindowSnapshot.windows.any {
            !it.minimized && it.pkg.isNotBlank() && it.pkg != "com.extbridge.helper" && it.pkg != "android"
        }
        return !forceWindowOverlaysDuringAnim &&
            (anim == null || !anim.isAnimating()) &&
            !usingA11yOverlay &&
            attachedVdId > 0 &&
            sLauncherHost != null &&
            sLauncherHostDisplayId == attachedVdId &&
            !lastStatus.isMultiTilingActive &&
            !BridgeClient.isTilingTopology(lastWindowSnapshot.tilingTopology) &&
            !hasManagedForeground
    }

    override fun removeOverlayView(v: View?) {
        if (v == null) return
        val parent = v.parent
        if (parent is ViewGroup) {
            parent.removeView(v)
        } else if (parent != null) {
            try { extWm?.removeViewImmediate(v) } catch (_: Throwable) {}
        }
    }

    override fun syncOverlayHostSurface(forceAttach: Boolean) {
        val dockRoot = dockController.dockRoot ?: return
        val panelRoot = panelController.panelRoot ?: return
        val cursorView = cursorController.cursorView ?: return
        val ctx = extCtx ?: return

        val wantHost = shouldHostInLauncher()
        if (!forceAttach && wantHost == inLauncherHost) {
            val p = dockRoot.parent
            val rightParent = if (wantHost) p === sLauncherHost else (p != null && p !is ViewGroup)
            if (rightParent) return
        }

        splitController.resetClosedVisualsBeforeHostSwap()
        dockController.resetClosedVisualsBeforeHostSwap()
        panelController.resetClosedVisualsBeforeHostSwap()

        removeOverlayView(splitController.splitDividerRoot)
        removeOverlayView(splitController.secondaryDividerRoot)
        removeOverlayView(dockController.dockEdgeTrigger)
        removeOverlayView(dockRoot)
        removeOverlayView(splitController.splitMenuRoot)
        removeOverlayView(dockController.taskBubbleRoot)
        removeOverlayView(panelRoot)
        removeOverlayView(cursorView)

        inLauncherHost = wantHost
        if (wantHost) {
            val hostGroup = sLauncherHost
            splitController.splitDividerRoot?.let { div ->
                val sdlp = FrameLayout.LayoutParams(
                    splitController.splitDividerLp?.width ?: OverlayUiUtils.dp(ctx, 40f),
                    splitController.splitDividerLp?.height ?: OverlayUiUtils.dp(ctx, 84f),
                    Gravity.TOP or Gravity.LEFT,
                )
                safeAddToHost(hostGroup, div, sdlp)
            }
            splitController.secondaryDividerRoot?.let { div2 ->
                val sdlp2 = FrameLayout.LayoutParams(
                    splitController.secondaryDividerLp?.width ?: OverlayUiUtils.dp(ctx, 80f),
                    splitController.secondaryDividerLp?.height ?: OverlayUiUtils.dp(ctx, 26f),
                    Gravity.TOP or Gravity.LEFT,
                )
                safeAddToHost(hostGroup, div2, sdlp2)
            }
            dockController.dockEdgeTrigger?.let { edge ->
                val elp = FrameLayout.LayoutParams(
                    OverlayUiUtils.dp(ctx, 220f),
                    OverlayUiUtils.dp(ctx, 16f),
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                )
                safeAddToHost(hostGroup, edge, elp)
            }
            val dlp = FrameLayout.LayoutParams(
                dockController.dockLp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = OverlayUiUtils.dp(ctx, 10f) }
            safeAddToHost(hostGroup, dockRoot, dlp)

            splitController.splitMenuRoot?.let { menu ->
                safeAddToHost(
                    hostGroup,
                    menu,
                    FrameLayout.LayoutParams(
                        OverlayUiUtils.dp(ctx, 312f),
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.LEFT,
                    ),
                )
            }
            dockController.taskBubbleRoot?.let { bubble ->
                safeAddToHost(
                    hostGroup,
                    bubble,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.LEFT,
                    ),
                )
            }

            val plp = FrameLayout.LayoutParams(
                OverlayUiUtils.dp(ctx, 354f),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.LEFT,
            ).apply {
                panelController.panelLp?.let {
                    leftMargin = it.x
                    topMargin = it.y
                }
            }
            safeAddToHost(hostGroup, panelRoot, plp)

            cursorView.isClickable = false
            cursorView.isFocusable = false
            cursorView.elevation = OverlayUiUtils.dp(ctx, 48f).toFloat()
            cursorView.translationZ = OverlayUiUtils.dp(ctx, 48f).toFloat()
            safeAddToHost(hostGroup, cursorView, FrameLayout.LayoutParams(44, 44, Gravity.TOP or Gravity.LEFT))
            cursorView.bringToFront()
            cursorController.cursorLp?.let { clp ->
                cursorView.translationX = clp.x.toFloat()
                cursorView.translationY = clp.y.toFloat()
            }
            hostGroup?.post {
                if (inLauncherHost) {
                    dockController.repositionDock()
                    splitController.repositionSplitDivider()
                    splitController.repositionSplitMenu()
                    if (dockController.isTaskBubbleOpen) dockController.repositionTaskBubble()
                    if (panelController.isPanelOpen) panelController.repositionPanel()
                }
            }
        } else if (extWm != null) {
            val wm = extWm!!
            cursorView.translationX = 0f
            cursorView.translationY = 0f
            val sDiv = splitController.splitDividerRoot
            val sDivLp = splitController.splitDividerLp
            if (sDiv != null && sDivLp != null) {
                try { wm.addView(sDiv, sDivLp) } catch (_: Throwable) {}
            }
            val sDiv2 = splitController.secondaryDividerRoot
            val sDiv2Lp = splitController.secondaryDividerLp
            if (sDiv2 != null && sDiv2Lp != null) {
                try { wm.addView(sDiv2, sDiv2Lp) } catch (_: Throwable) {}
            }
            val edge = dockController.dockEdgeTrigger
            val edgeLp = dockController.dockEdgeLp
            if (edge != null && edgeLp != null) {
                try { wm.addView(edge, edgeLp) } catch (_: Throwable) {}
            }
            dockController.dockLp?.let { try { wm.addView(dockRoot, it) } catch (_: Throwable) {} }
            val sMenu = splitController.splitMenuRoot
            val sMenuLp = splitController.splitMenuLp
            if (sMenu != null && sMenuLp != null) {
                try { wm.addView(sMenu, sMenuLp) } catch (_: Throwable) {}
            }
            val bubble = dockController.taskBubbleRoot
            val bubbleLp = dockController.taskBubbleLp
            if (bubble != null && bubbleLp != null) {
                try { wm.addView(bubble, bubbleLp) } catch (_: Throwable) {}
            }
            panelController.panelLp?.let { try { wm.addView(panelRoot, it) } catch (_: Throwable) {} }
            cursorController.cursorLp?.let { try { wm.addView(cursorView, it) } catch (_: Throwable) {} }
        }
        dockController.syncDockEdgeVisibility()
        dockController.repositionDock()
        splitController.repositionSplitDivider()
        splitController.repositionSplitMenu()
        if (dockController.isTaskBubbleOpen) dockController.repositionTaskBubble()
        if (panelController.isPanelOpen) panelController.repositionPanel()
        dockController.evaluateDockAutoHide(false)
    }

    private fun handleHostTouch(winX: Float, winY: Float) {
        if (!inLauncherHost) return
        if (panelController.isPanelOpen &&
            !OverlayUiUtils.isPointInView(panelController.panelCard, winX, winY) &&
            !OverlayUiUtils.isPointInView(dockController.trayBtn, winX, winY)
        ) {
            panelController.setPanelOpen(false)
        }
        if (dockController.isTaskBubbleOpen &&
            !OverlayUiUtils.isPointInView(dockController.taskBubbleCard, winX, winY)
        ) {
            dockController.setTaskBubbleOpen(false)
        }
        if (windowChromeController.isAnyMenuOpen) {
            windowChromeController.dismissMenus()
        }
    }

    private fun detachExtOverlays(keepBlackout: Boolean) {
        dockController.cancelAutoHideTimer()
        if (!keepBlackout) {
            if (blackoutController.isBlackoutActive) {
                blackoutController.setDexBlackout(false)
            } else {
                blackoutController.removeBlackoutOverlay()
            }
        }
        stageAnimView?.cancelActiveAnimation()
        forceWindowOverlaysDuringAnim = false
        removeOverlayView(stageAnimView)
        windowChromeController.detach()
        splitController.detach()
        dockController.detach()
        panelController.detach()
        cursorController.detach()

        inLauncherHost = false
        extWm = null
        extCtx = null
        usingA11yOverlay = false
        winType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        stageAnimView = null
        stageAnimLp = null
        attachedVdId = -1
        overlayW = 0
        overlayH = 0
        overlayDpi = 0
    }

    private fun computeVolumeIconLevel(): Int {
        var muted = false
        var volPct = 50
        audioManager?.let { am ->
            val maxVol = max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            try { muted = am.isStreamMute(AudioManager.STREAM_MUSIC) || curVol == 0 } catch (_: Throwable) {}
            volPct = (curVol * 100f / maxVol).roundToInt()
        }
        return if (muted) 0 else if (volPct > 50) 2 else 1
    }

    private fun refreshLocalHardwareViews() {
        val lvl = computeVolumeIconLevel()
        dockController.refreshLocalHardwareViews(lvl)
        panelController.refreshLocalHardwareViews(lvl)
        mirrorController.refreshMirrorSideViewsIfAttached(lastStatus)
    }

    private fun refreshSysViews() {
        val sys = lastSysStatus
        if (!sys.valid) return
        dockController.refreshSysViews(sys)
        panelController.refreshSysViews(sys)
    }

    // --- OverlayHost Implementation ---

    override fun safeBgExecute(r: Runnable) {
        if (isDestroyed || bgExec.isShutdown) return
        try {
            bgExec.execute { if (!isDestroyed) r.run() }
        } catch (_: RejectedExecutionException) {}
    }

    override fun safeInputExecute(r: Runnable) {
        if (isDestroyed || inputBgExec.isShutdown) return
        try {
            inputBgExec.execute { if (!isDestroyed) r.run() }
        } catch (_: RejectedExecutionException) {}
    }

    override fun safePost(r: Runnable) {
        if (isDestroyed) return
        handler.post { if (!isDestroyed) r.run() }
    }

    override fun beginWindowOverlayStage() {
        forceWindowOverlaysDuringAnim = true
        syncOverlayHostSurface(false)
        bringCursorToFront()
    }

    override fun endWindowOverlayStage() {
        forceWindowOverlaysDuringAnim = false
        syncOverlayHostSurface(false)
        bringCursorToFront()
    }

    override fun bringCursorToFront() = cursorController.bringCursorToFront()

    override fun cloneDrawable(d: Drawable?): Drawable? {
        if (d == null) return null
        try {
            val cs = d.constantState
            if (cs != null) return cs.newDrawable(resources).mutate()
        } catch (_: Throwable) {}
        return d
    }

    override fun getCachedIcon(pm: PackageManager, pkg: String): Drawable? {
        iconCache.get(pkg)?.let { return cloneDrawable(it) }
        return try {
            val d = pm.getApplicationIcon(pkg)
            iconCache.put(pkg, d)
            cloneDrawable(d)
        } catch (_: Throwable) {
            null
        }
    }

    override fun getCachedLabel(pm: PackageManager, pkg: String): String {
        labelCache.get(pkg)?.let { return it }
        return try {
            val ai = pm.getApplicationInfo(pkg, 0)
            val lbl = pm.getApplicationLabel(ai).toString()
            if (lbl.isNotEmpty()) {
                labelCache.put(pkg, lbl)
                return lbl
            }
            pkg
        } catch (_: Throwable) {
            pkg
        }
    }

    override fun buildCardSpec(
        pkg: String?,
        snap: BridgeClient.TaskSnapshotData?,
        startR: Rect,
        endR: Rect,
    ): StageTransitionView.CardSpec {
        val spec = StageTransitionView.CardSpec()
        if (snap?.bitmap != null) {
            spec.snapshot = snap.bitmap
            spec.ownSnapshot = snap.ownBitmap
        }
        if (!pkg.isNullOrEmpty() && "com.extbridge.helper" != pkg) {
            val pm = packageManager
            spec.icon = getCachedIcon(pm, pkg)
            spec.label = getCachedLabel(pm, pkg)
        } else {
            spec.label = "扩展桌面"
        }
        spec.setRects(startR, endR)
        return spec
    }

    override fun isBlackoutOverlayActive(): Boolean = blackoutController.isBlackoutActive
    override fun toggleDexBlackout() = blackoutController.toggleDexBlackout()
    override fun setDexBlackout(on: Boolean) = blackoutController.setDexBlackout(on)
    override fun isDockHidden(): Boolean = dockController.isDockHidden
    override fun isDockAutoHide(): Boolean = dockController.isDockAutoHide
    override fun isOnLauncherHome(): Boolean = dockController.isOnLauncherHome
    override fun isPanelOpen(): Boolean = panelController.isPanelOpen
    override fun isSplitMenuOpen(): Boolean = splitController.isSplitMenuOpen
    override fun isTaskBubbleOpen(): Boolean = dockController.isTaskBubbleOpen
    override fun setPanelOpen(open: Boolean) = panelController.setPanelOpen(open)
    override fun setSplitMenuOpen(open: Boolean) = splitController.setSplitMenuOpen(open)
    override fun setTaskBubbleOpen(open: Boolean) = dockController.setTaskBubbleOpen(open)
    override fun triggerQuickSplitOrMenu() = splitController.triggerQuickSplitOrMenu()
    override fun updateDockAutoHideUi() = panelController.updateDockAutoHideUi()
    override fun evaluateDockAutoHide(forceDelay: Boolean) = dockController.evaluateDockAutoHide(forceDelay)
    override fun cancelAutoHideTimer() = dockController.cancelAutoHideTimer()
    override fun wakeDockFromEdge() = dockController.wakeDockFromEdge()
    override fun applyDockHidden(hidden: Boolean) = dockController.applyDockHidden(hidden)
    override fun applyDockHiddenAnimated(hidden: Boolean) = dockController.applyDockHiddenAnimated(hidden)
    override fun setDockAutoHideEnabled(enabled: Boolean) = setDockAutoHideEnabled(this, enabled)
    override fun updateBlackoutUi() = panelController.updateBlackoutUi()

    override fun updateInputToggleUi(inputToExt: Int) {
        dockController.updateInputToggleUi(inputToExt)
        panelController.updateInputToggleUi(inputToExt)
    }

    override fun openExtSettings(action: String) {
        panelController.setPanelOpen(false)
        if (usingA11yOverlay) {
            safeBgExecute { BridgeClient.execSysAction(BridgeClient.SYS_OPEN_SETTINGS, -1, action) }
        } else {
            Toast.makeText(this, "未开启无障碍：已在平板打开系统设置", Toast.LENGTH_SHORT).show()
            safeBgExecute { BridgeClient.execSysAction(BridgeClient.SYS_OPEN_SETTINGS, 0, action) }
        }
    }

    override fun openTabletMain() {
        panelController.setPanelOpen(false)
        try {
            val mi = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val o0 = ActivityOptions.makeBasic().setLaunchDisplayId(0)
            startActivity(mi, o0.toBundle())
        } catch (_: Throwable) {}
    }

    override fun openTabletTouchpad() {
        panelController.setPanelOpen(false)
        try {
            val ti = Intent(this, TouchpadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val o0 = ActivityOptions.makeBasic().setLaunchDisplayId(0)
            startActivity(ti, o0.toBundle())
        } catch (_: Throwable) {}
    }

    override fun execSysAndRefresh(act: Int, arg: Int, str: String?) {
        safeBgExecute {
            BridgeClient.execSysAction(act, arg, str)
            safePost {
                handler.postDelayed({
                    safeBgExecute {
                        val sys = BridgeClient.getSysStatus()
                        safePost {
                            if (sys.valid) lastSysStatus = sys
                            refreshSysViews()
                            mirrorController.refreshMirrorSideViewsIfAttached(lastStatus)
                        }
                    }
                }, 220L)
            }
        }
    }

    override fun getDockTrayButton(): View? = dockController.trayBtn
    override fun getDockRoot(): View? = dockController.dockRoot
    override fun findDockSlotByPkg(pkg: String?): View? = dockController.findDockSlotByPkg(pkg)
    override fun resolveDockIconRectOnScreen(preferredSlot: View?, pkg: String?): RectF =
        dockController.resolveDockIconRectOnScreen(preferredSlot, pkg)
    override fun bounceDockSlot(pkg: String?, fallbackView: View?, downwardCushion: Boolean) =
        dockController.bounceDockSlot(pkg, fallbackView, downwardCushion)
    override fun performEnterOrAssignSplitAnimated(action: Int, slotHint: Int, taskId: Int, pkg: String?, cls: String?) =
        splitController.performEnterOrAssignSplitAnimated(action, slotHint, taskId, pkg, cls)
    override fun performAssignSplitAnimated(slot: Int, taskId: Int, pkg: String?, cls: String?) {
        val action = if (slot == 0) BridgeClient.SPLIT_ASSIGN_LEFT_TOP else BridgeClient.SPLIT_ASSIGN_RIGHT_BOTTOM
        splitController.performEnterOrAssignSplitAnimated(action, slot, taskId, pkg, cls)
    }
    override fun performExitSplitAnimated() = splitController.performExitSplitAnimated()
    override fun performExitSplitToHomeAnimated(anchorBtn: View?) = dockController.performExitSplitToHomeAnimated(anchorBtn)
    override fun displayedSplitRatio(): Float = splitController.displayedSplitRatio
    override fun onLocalHardwareRefreshRequested() = refreshLocalHardwareViews()
    override fun refreshTrayIconsOnly() {
        val lvl = computeVolumeIconLevel()
        dockController.refreshVolumeIconOnly(lvl)
        panelController.refreshVolumeIconOnly(lvl)
    }
    override fun updateTrayPillBackground(active: Boolean) = dockController.updateTrayPillBackground(active)
    override fun notifyTrayToggleTimestamp() = Unit
    override fun cancelPanelOutsideDismiss() = panelController.cancelOutsideDismiss()
    override fun isTrayPressed(): Boolean = dockController.isTrayPressed
    override fun lastTrayUpMs(): Long = dockController.lastTrayUpMs
    override fun lastTrayToggleMs(): Long = dockController.lastTrayToggleMs
    override fun updateDockSplitButton(splitActive: Boolean) = dockController.updateDockSplitButton(splitActive)
    override fun updatePanelSplitButton(splitActive: Boolean) = panelController.updateSplitButton(splitActive)
    override fun repositionPanelIfOpen() {
        if (panelController.isPanelOpen) panelController.repositionPanel()
    }
    override fun repositionDock() = dockController.repositionDock()
}

package com.extbridge.helper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.HyperUi
import com.extbridge.helper.StageTransitionView
import kotlin.math.max
import kotlin.math.min

/**
 * Manages the floating Dock bar, running-apps task strip, and minimize/restore choreography
 * via [StageChoreographer], [DockAutoHidePolicy], and [DockTaskBubbleController].
 */
class DockOverlayController(private val host: OverlayHost) {

    companion object {
        private const val TAG = "ExtDockOverlay"
        private const val SURFACE_KEY_DOCK = "dock_bar"
    }

    var dockRoot: LinearLayout? = null
        private set
    var trayBtn: LinearLayout? = null
        private set
    var taskStrip: LinearLayout? = null
        private set
    var btnDockSplit: TextView? = null
        private set

    private var dockLp: WindowManager.LayoutParams? = null
    private var btnToggle: TextView? = null
    private var taskDivider: View? = null
    private var trayWifiIcon: VectorIconHolder? = null
    private var trayVolIcon: VectorIconHolder? = null
    private var trayBattView: MiniBatteryView? = null
    private var trayBattPct: TextView? = null
    private var lastTaskSig = ""
    private var lastInputToExt = -1
    private var lastSplitActive: Boolean? = null

    private val autoHide = DockAutoHidePolicy(host, { dockRoot }) { repositionDock() }
    private val taskBubble = DockTaskBubbleController(host) { dockRoot }
    private val delayedPollTask = Runnable { host.pollRemoteStateAsync() }

    val isDockHidden: Boolean get() = autoHide.isDockHidden
    var isDockAutoHide: Boolean
        get() = autoHide.isDockAutoHide
        set(value) { autoHide.isDockAutoHide = value }
    var isOnLauncherHome: Boolean
        get() = autoHide.isOnLauncherHome
        set(value) { autoHide.isOnLauncherHome = value }

    fun isTrayInteractionRecent(): Boolean = autoHide.isTrayInteractionRecent()
    fun isPointInTaskBubbleCard(winX: Float, winY: Float): Boolean = taskBubble.isPointInCard(winX, winY)
    fun isTaskBubbleOpen(): Boolean = taskBubble.isOpen
    fun dismissTaskBubble() = taskBubble.dismiss()
    fun repositionTaskBubbleIfOpen() { if (taskBubble.isOpen) taskBubble.reposition() }
    fun clearTaskSignature() { lastTaskSig = "" }

    fun onCursorMoved(x: Float, y: Float) {
        if (!host.lastStatus.isRemoteStreaming) autoHide.onCursorMoved(x, y)
    }
    fun cancelAutoHideTimer() = autoHide.cancelTimer()
    fun evaluateDockAutoHide(forceDelay: Boolean) {
        if (host.lastStatus.isRemoteStreaming) {
            autoHide.applyHidden(true)
            return
        }
        autoHide.evaluate(forceDelay)
    }
    fun applyDockHidden(hidden: Boolean) = autoHide.applyHidden(if (host.lastStatus.isRemoteStreaming) true else hidden)
    fun applyDockHiddenAnimated(hidden: Boolean) = autoHide.applyHiddenAnimated(if (host.lastStatus.isRemoteStreaming) true else hidden)
    fun syncDockEdgeVisibility() = autoHide.syncEdgeVisibility()

    fun resetClosedVisualsBeforeHostSwap() {
        taskBubble.resetClosedVisuals()
        autoHide.resetClosedVisuals()
    }

    fun buildDockOverlays(ctx: Context, st: BridgeClient.Status) {
        if (st.isRemoteStreaming) return
        autoHide.buildEdgeTrigger(ctx)
        buildDockBar(ctx, st)
        taskBubble.build(ctx)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildDockBar(ctx: Context, st: BridgeClient.Status) {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false; setPadDp(10f, 5f); elevation = ctx.dpF(10f)
            background = roundedBg(HyperUi.DOCK_BG, ctx.dpF(22f), 1, HyperUi.DOCK_STROKE)
            setOnHoverListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> autoHide.onDockHoverChanged(true)
                    MotionEvent.ACTION_HOVER_EXIT -> autoHide.onDockHoverChanged(false)
                }
                false
            }
        }
        dockRoot = root

        root.addView(buildActionBtn(ctx, "桌面", 0x2AFFFFFF, HyperUi.ICON_APPS) {
            host.popovers.dismissAll()
            performHomeButtonAnimated(it)
        })
        root.addView(buildActionBtn(ctx, "返回", 0x22FFFFFF, HyperUi.ICON_CHEVRON_LEFT, padDp = 3f) {
            host.safeInputExecute { BridgeClient.sendBack() }
        })
        btnDockSplit = buildActionBtn(ctx, "分屏", 0x22FFFFFF, HyperUi.ICON_SPLIT) {
            host.split.triggerQuickSplitOrMenu()
        }.apply {
            setOnLongClickListener { host.split.toggleSplitMenu(); true }
        }.also(root::addView)

        btnToggle = buildActionBtn(
            ctx,
            if (st.inputToExt == 1) "外接" else "本机",
            if (st.inputToExt == 1) HyperUi.HYPER_BLUE else 0xCC3B4252.toInt(),
            HyperUi.ICON_SWAP,
        ) {
            host.safeInputExecute {
                val m = BridgeClient.toggleInput()
                host.safePost {
                    host.updateInputToggleUi(m)
                    host.serviceContext.toast(if (m == 1) "键鼠 -> 扩展屏" else "键鼠 -> 平板")
                }
            }
        }.also(root::addView)
        lastInputToExt = st.inputToExt; lastSplitActive = null

        taskDivider = OverlayUiUtils.makeVerticalDivider(ctx).apply { visibility = View.GONE }.also(root::addView)
        taskStrip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }.also(root::addView)
        root.addView(OverlayUiUtils.makeVerticalDivider(ctx))
        root.addView(buildSystemTray(ctx))

        val lp = OverlayWindowParams.floating(
            WRAP, WRAP, host.winType, x = 0, y = ctx.dp(10f),
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, touchModal = true,
        )
        dockLp = lp
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            if ((r - l) != (or2 - ol) || (b - t) != (ob - ot)) {
                repositionDock()
                host.panel.repositionIfOpen()
            }
        }
        host.surfaceHost.register(
            OverlaySurfaceHost.SurfaceEntry(
                SURFACE_KEY_DOCK, OverlayLayer.DOCK, root, lp,
                launcherParamsProvider = { ctx.flp(lp.width, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottomDp = 10f) },
            ),
        )
    }

    private fun buildActionBtn(ctx: Context, title: String, bgColor: Int, iconKind: Int, padDp: Float = 4f, onClick: (View) -> Unit): TextView =
        OverlayUiUtils.makeDockBtn(ctx, title, bgColor, Color.WHITE).apply {
            HyperUi.setStartIcon(this, iconKind, Color.WHITE, 12.5f, padDp)
            OverlayUiUtils.attachTactilePress(this, 0.90f)
            setOnClickListener { onClick(this) }
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildSystemTray(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadDp(9f, 4f)
        setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { autoHide.onTrayPressedChanged(true); host.panel.cancelOutsideDismiss() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> autoHide.onTrayPressedChanged(false)
            }
            false
        }
        setOnClickListener {
            host.panel.cancelOutsideDismiss()
            autoHide.markTrayToggled()
            host.panel.toggle()
        }
        trayBtn = this
        updateTrayPillBackground(false)

        trayWifiIcon = VectorIconHolder(ctx, HyperUi.ICON_WIFI, Color.WHITE, 16f).also {
            addView(it.view, ctx.llp(ctx.dp(16f), ctx.dp(16f), rightDp = 6f))
        }
        trayVolIcon = VectorIconHolder(ctx, HyperUi.ICON_VOLUME_HIGH, Color.WHITE, 16f).also {
            addView(it.view, ctx.llp(ctx.dp(16f), ctx.dp(16f), rightDp = 6f))
        }
        trayBattView = MiniBatteryView(ctx).also { addView(it, ctx.llp(ctx.dp(20f), ctx.dp(12f), rightDp = 4f)) }
        trayBattPct = TextView(ctx).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
            paint.isFakeBoldText = true; OverlayUiUtils.enforceSingleLine(this)
        }.also { addView(it, ctx.llp(rightDp = 9f)) }

        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(TextClock(ctx).apply {
                format24Hour = "HH:mm"; format12Hour = "HH:mm"
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
                paint.isFakeBoldText = true; OverlayUiUtils.enforceSingleLine(this)
            })
            addView(TextClock(ctx).apply {
                format24Hour = "yyyy/M/d"; format12Hour = "yyyy/M/d"
                setTextColor(0xFFCBD5E1.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9.5f)
                OverlayUiUtils.enforceSingleLine(this)
            })
        })
    }

    fun updateTrayPillBackground(active: Boolean) {
        val tray = trayBtn ?: return
        val ctx = host.extCtx ?: return
        tray.background = roundedBg(if (active) 0x45A6D8E8 else 0x1EFFFFFF, ctx.dpF(14f), if (active) 1 else 0, 0x80A6D8E8.toInt())
    }

    fun updateDockSplitButton(active: Boolean) {
        if (lastSplitActive == active) return
        lastSplitActive = active
        (btnDockSplit?.background as? GradientDrawable)?.setColor(if (active) HyperUi.HYPER_BLUE else 0x22FFFFFF)
    }

    fun updateInputToggleUi(inputToExt: Int) {
        if (lastInputToExt == inputToExt) return
        lastInputToExt = inputToExt
        btnToggle?.let { btn ->
            btn.text = if (inputToExt == 1) "外接" else "本机"
            (btn.background as? GradientDrawable)?.setColor(if (inputToExt == 1) HyperUi.HYPER_BLUE else 0xCC3B4252.toInt())
        }
    }

    fun refreshLocalHardwareViews(volLevel: Int) {
        refreshVolumeIconOnly(volLevel)
        trayBattView?.setBattery(host.batteryPct, host.batteryCharging)
        val txt = "${host.batteryPct}%"
        trayBattPct?.let { if (it.text?.toString() != txt) it.text = txt }
    }

    fun refreshVolumeIconOnly(volLevel: Int) { trayVolIcon?.update(kind = HyperUi.volumeIconKind(volLevel)) }

    fun refreshSysViews(sys: BridgeClient.SysStatus) {
        if (sys.valid) trayWifiIcon?.update(kind = if (sys.wifiOn) HyperUi.ICON_WIFI else HyperUi.ICON_WIFI_OFF)
    }

    fun repositionDock() {
        val dock = dockRoot ?: return
        val lp = dockLp ?: return
        val ctx = host.extCtx ?: return
        try {
            val maxW = max(200, host.surfaceWidth - ctx.dp(20f))
            val targetW = if (dock.width > maxW) maxW else WRAP
            val bottom = ctx.dp(10f)
            host.surfaceHost.updatePosition(
                dock, lp, host.inLauncherHost, host.extWm,
                x = 0, y = bottom, width = targetW, height = WRAP,
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottomMargin = bottom,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "repositionDock failed", t)
        }
    }

    fun updateTaskStrip(tasks: List<BridgeClient.TaskEntry>) {
        val strip = taskStrip ?: return
        val ctx = host.extCtx ?: return
        val availW = max(0, host.surfaceWidth - ctx.dp(28f) - ctx.dp(390f))
        val limit = min((availW / max(1, ctx.dp(42f))).coerceIn(2, 9), tasks.size)

        val sig = buildString {
            for (i in 0 until limit) {
                val t = tasks[i]; append(t.taskId).append(':').append(t.pkg).append(':').append(t.flags).append(';')
            }
        }
        if (sig == lastTaskSig) return
        lastTaskSig = sig

        strip.removeAllViews()
        taskDivider?.visibility = if (limit > 0) View.VISIBLE else View.GONE
        val pm = host.serviceContext.packageManager
        for (i in 0 until limit) {
            val t = tasks[i]
            strip.addView(buildTaskSlot(ctx, t, host.getCachedLabel(pm, t.pkg), host.getCachedIcon(pm, t.pkg)))
        }
    }

    private fun buildTaskSlot(ctx: Context, t: BridgeClient.TaskEntry, label: String, icon: Drawable?): LinearLayout =
        LinearLayout(ctx).apply {
            tag = t.pkg; orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadDp(6f, 3f)
            background = roundedBg(when { t.isTopExt() -> 0x36FFFFFF; t.onExt() -> 0x18FFFFFF; else -> Color.TRANSPARENT }, ctx.dpF(10f))
            layoutParams = ctx.llp(leftDp = 2f, rightDp = 2f)
            addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; if (icon != null) setImageDrawable(icon) }, ctx.llp(ctx.dp(28f), ctx.dp(28f)))
            addView(View(ctx).apply {
                background = roundedBg(when {
                    t.isTopExt() -> 0xFF38BDF8.toInt(); t.onExt() -> 0xFF94A3B8.toInt()
                    t.isRunning() -> 0x7794A3B8; else -> Color.TRANSPARENT
                }, ctx.dpF(2f))
            }, ctx.llp(ctx.dp(if (t.isTopExt()) 14f else 6f), ctx.dp(3f), topDp = 2f))

            setOnClickListener {
                host.popovers.dismissAll()
                if (t.isTopExt() && !t.isInSplit() && host.lastStatus.mirrorMode == BridgeClient.MIRROR_MODE_DEX) {
                    performMinimizeTaskAnimated(this, t, label, icon)
                } else {
                    performRestoreTaskAnimated(this, t, label, icon)
                }
            }
            OverlayUiUtils.attachRightClickListener(this, includeLongClick = true, pressedScale = 0.86f) {
                taskBubble.showForTask(this, t, label, icon)
            }
        }

    private fun findDockSlotByPkg(pkg: String?): View? {
        val strip = taskStrip ?: return null
        if (pkg.isNullOrEmpty()) return null
        return (0 until strip.childCount).mapNotNull { strip.getChildAt(it) }.firstOrNull { pkg == it.tag }
    }

    private fun resolveDockIconRectOnScreen(preferredSlot: View?, pkg: String?): RectF {
        val sw = max(OverlayConstants.MIN_SURFACE_PX, host.overlayW)
        val sh = max(OverlayConstants.MIN_SURFACE_PX, host.overlayH)
        val slot = preferredSlot ?: findDockSlotByPkg(pkg)
        val target = if (slot is ViewGroup && slot.childCount > 0) slot.getChildAt(0) else slot
        if (target != null && target.isShown && target.width > 0 && target.height > 0) {
            val loc = IntArray(2); target.getLocationOnScreen(loc)
            if (loc[0] in 0 until sw && loc[1] in 0 until sh) {
                return RectF(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + target.width).toFloat(), (loc[1] + target.height).toFloat())
            }
        }
        val sz = host.extCtx?.dp(28f) ?: 36
        val bottomPad = host.extCtx?.dp(18f) ?: 24
        val cx = sw * 0.5f; val cy = sh - bottomPad - sz * 0.5f
        return RectF(cx - sz * 0.5f, cy - sz * 0.5f, cx + sz * 0.5f, cy + sz * 0.5f)
    }

    private fun bounceDockSlot(pkg: String?, fallbackView: View?, downward: Boolean) {
        val target = findDockSlotByPkg(pkg) ?: fallbackView ?: return
        val ctx = host.extCtx ?: return
        val sc = if (downward) 0.85f else 1.12f
        target.animate().cancel()
        target.animate().translationY(if (downward) ctx.dpF(4.5f) else -ctx.dpF(4f)).scaleX(sc).scaleY(sc)
            .setDuration(85L).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                target.animate().translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(190L).setInterpolator(OvershootInterpolator(2.1f)).start()
            }.start()
    }

    private fun scheduleDelayedPoll(delayMs: Long) {
        host.handler.removeCallbacks(delayedPollTask)
        host.handler.postDelayed(delayedPollTask, delayMs)
    }

    private fun performMinimizeTaskAnimated(slotView: View?, t: BridgeClient.TaskEntry, label: String?, icon: Drawable?) {
        if (host.stageAnimView == null || host.extCtx == null) {
            host.runDaemonAndPoll { BridgeClient.minimizeToExtHome(t.taskId, t.pkg) }; return
        }
        val dockIconRect = resolveDockIconRectOnScreen(slotView, t.pkg)
        val fullRect = OverlayUiUtils.getFullStageRect(host.overlayW, host.overlayH)
        val pm = host.serviceContext.packageManager
        val animIcon = if (icon != null) host.cloneDrawable(icon) else host.getCachedIcon(pm, t.pkg)
        val animLabel = label?.takeIf { it.isNotEmpty() } ?: host.getCachedLabel(pm, t.pkg)

        host.stageChoreographer.runTransition(
            notifyBackendReady = false,
            prepareCards = {
                val snap = BridgeClient.captureTaskSnapshot(t.taskId, t.pkg, true)
                StageTransitionView.CardSpec().apply {
                    if (snap.bitmap != null) { snapshot = snap.bitmap; ownSnapshot = snap.ownBitmap }
                    this.icon = animIcon; this.label = animLabel
                    startRect.set(snap.bounds?.takeIf { it.width() > 60 } ?: fullRect)
                    endRect.set(dockIconRect)
                }
            },
            extractCards = { listOf(it) },
            startAnimation = { anim, card, onFinished -> anim.playMinimizeToDock(card, { bounceDockSlot(t.pkg, slotView, true) }, onFinished) },
            performBackend = { BridgeClient.minimizeToExtHome(t.taskId, t.pkg) },
            onBackendResultUi = { _, _ -> scheduleDelayedPoll(260L) },
        )
    }

    private fun performHomeButtonAnimated(homeBtn: View) {
        val st = host.lastStatus
        if (host.stageAnimView == null || host.extCtx == null || isOnLauncherHome || !st.connected || st.mirrorMode != BridgeClient.MIRROR_MODE_DEX) {
            host.runDaemonAndPoll { BridgeClient.relaunchLauncher() }; return
        }
        if (st.splitActive == 1) {
            if (st.splitSlot0Pkg.isEmpty() == st.splitSlot1Pkg.isEmpty()) {
                host.split.performExitSplitToHomeAnimated(resolveDockIconRectOnScreen(homeBtn, null))
            } else {
                host.runDaemonAndPoll { BridgeClient.relaunchLauncher() }
            }
            return
        }
        host.safeBgExecute {
            val topExt = BridgeClient.getTasks().firstOrNull { it.isTopExt() && OverlayConstants.HELPER_PKG != it.pkg }
            if (topExt == null) {
                BridgeClient.relaunchLauncher()
                host.safePost { host.pollRemoteStateAsync() }
                return@safeBgExecute
            }
            val pm = host.serviceContext.packageManager
            val icon = host.getCachedIcon(pm, topExt.pkg)
            val label = host.getCachedLabel(pm, topExt.pkg)
            host.safePost { performMinimizeTaskAnimated(findDockSlotByPkg(topExt.pkg) ?: homeBtn, topExt, label, icon) }
        }
    }

    private fun performRestoreTaskAnimated(slotView: View?, t: BridgeClient.TaskEntry, label: String?, icon: Drawable?) {
        if (host.stageAnimView == null || host.extCtx == null) {
            host.runDaemonAndPoll { BridgeClient.switchToTask(t.taskId, t.pkg) }; return
        }
        bounceDockSlot(t.pkg, slotView, false)
        val dockIconRect = resolveDockIconRectOnScreen(slotView, t.pkg)
        val st = host.lastStatus
        val splitSlot = if (st.splitActive == 1 && OverlayConstants.HELPER_PKG != t.pkg) {
            when {
                t.pkg == st.splitSlot0Pkg -> 0; t.pkg == st.splitSlot1Pkg -> 1
                st.splitSlot0Pkg.isEmpty() -> 0; st.splitSlot1Pkg.isEmpty() -> 1; else -> -1
            }
        } else -1
        val targetRect = if (splitSlot >= 0) {
            OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, splitSlot, host.split.displayedSplitRatio)
        } else {
            OverlayUiUtils.getFullStageRect(host.overlayW, host.overlayH)
        }
        val pm = host.serviceContext.packageManager
        val animIcon = if (icon != null) host.cloneDrawable(icon) else host.getCachedIcon(pm, t.pkg)
        val animLabel = label?.takeIf { it.isNotEmpty() } ?: host.getCachedLabel(pm, t.pkg)

        host.stageChoreographer.runTransition(
            prepareCards = {
                val snap = BridgeClient.captureTaskSnapshot(t.taskId, t.pkg, false)
                StageTransitionView.CardSpec().apply {
                    if (snap.bitmap != null) { snapshot = snap.bitmap; ownSnapshot = snap.ownBitmap }
                    this.icon = animIcon; this.label = animLabel
                    startRect.set(dockIconRect); endRect.set(targetRect)
                }
            },
            extractCards = { listOf(it) },
            startAnimation = { anim, card, onFinished -> anim.playRestoreFromDock(card, splitSlot >= 0, onFinished) },
            performBackend = { BridgeClient.switchToTask(t.taskId, t.pkg) },
            onBackendResultUi = { _, _ -> scheduleDelayedPoll(180L) },
        )
    }

    fun detach() {
        host.handler.removeCallbacks(delayedPollTask)
        taskBubble.detach()
        autoHide.detach()
        host.surfaceHost.unregister(SURFACE_KEY_DOCK, host.extWm)
        dockRoot = null; dockLp = null; btnDockSplit = null; btnToggle = null
        taskStrip = null; taskDivider = null; trayBtn = null
        trayWifiIcon = null; trayVolIcon = null; trayBattView = null; trayBattPct = null
        lastTaskSig = ""; lastInputToExt = -1; lastSplitActive = null
    }
}

package io.github.lengsukq.opaldesk.desktop.taskbar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.HyperUi
import io.github.lengsukq.opaldesk.desktop.model.DesktopGeometry
import io.github.lengsukq.opaldesk.desktop.model.DesktopState
import io.github.lengsukq.opaldesk.desktop.model.DesktopStateReducer
import io.github.lengsukq.opaldesk.desktop.model.GlassOverlayTokens
import io.github.lengsukq.opaldesk.desktop.model.TaskbarClickOutcome
import io.github.lengsukq.opaldesk.desktop.model.TaskbarItem
import io.github.lengsukq.opaldesk.desktop.state.DesktopShellHost
import io.github.lengsukq.opaldesk.overlay.MATCH
import io.github.lengsukq.opaldesk.overlay.MiniBatteryView
import io.github.lengsukq.opaldesk.overlay.OverlayLayer
import io.github.lengsukq.opaldesk.overlay.OverlaySurfaceHost
import io.github.lengsukq.opaldesk.overlay.OverlayUiUtils
import io.github.lengsukq.opaldesk.overlay.OverlayWindowParams
import io.github.lengsukq.opaldesk.overlay.VectorIconHolder
import io.github.lengsukq.opaldesk.overlay.WRAP
import io.github.lengsukq.opaldesk.overlay.dp
import io.github.lengsukq.opaldesk.overlay.dpF
import io.github.lengsukq.opaldesk.overlay.llp
import io.github.lengsukq.opaldesk.overlay.roundedBg
import io.github.lengsukq.opaldesk.overlay.setPadDp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Unified 56dp floating Squircle Liquid Glass Samsung DeX Taskbar replacing the redundant
 * dual MenuBar + Dock layout.
 *
 * Structure (Left -> Right):
 * 1. App Drawer trigger (opens floating glass drawer ABOVE windows) + compact DeX Nav (`Back`, `Home`, `Recents`)
 * 2. Horizontally scrollable Pinned + Running Task strip with active capsule indicators, 200ms hover snapshot preview, and unified right-click menu
 * 3. USB-C Input Routing badge (auto-hidden in Mac remote mode) + System Status Tray (Wi-Fi / BT / Volume / Battery / Clock -> Quick Settings) + Show Desktop button
 */
@SuppressLint("ClickableViewAccessibility")
internal class DeXTaskbarController(private val host: DesktopShellHost) {

    companion object {
        private const val SURFACE_KEY = "dex_taskbar"
    }

    var barRoot: LinearLayout? = null
        private set
    private var barLp: WindowManager.LayoutParams? = null
    private var btnApps: ImageView? = null
    private var btnBack: ImageView? = null
    private var btnHome: ImageView? = null
    private var btnRecents: ImageView? = null
    private var btnWorkbench: TextView? = null
    private var leftDivider: View? = null
    private var rightDivider: View? = null
    private var taskScroll: HorizontalScrollView? = null
    private var taskStrip: LinearLayout? = null
    private var inputPill: TextView? = null
    private var trayPill: LinearLayout? = null
    private var trayWifi: VectorIconHolder? = null
    private var trayBt: VectorIconHolder? = null
    private var trayVol: VectorIconHolder? = null
    private var trayBatt: MiniBatteryView? = null
    private var trayBattTxt: TextView? = null
    private var trayTime: TextView? = null
    private var trayDate: TextView? = null
    private var btnShowDesktop: ImageView? = null

    val autoHide = TaskbarAutoHideController(host) { barRoot }
    private val hoverPreview = TaskbarHoverPreviewCard(host)

    private var lastTaskSig = ""
    private var lastDark: Boolean? = null
    private var lastInputToExt = -1
    private var lastPillMode = -1 // 0=physical input pill, 1=remote hidden, 2=local dex exit pill
    private var lastWorkbenchOn: Boolean? = null
    private var quickSettingsActive = false

    fun build(ctx: Context) {
        detach()
        autoHide.buildWakeStrip(ctx)
        hoverPreview.build(ctx)

        val barH = ctx.dp(DesktopGeometry.TASKBAR_HEIGHT_DP.toFloat())
        val barW = computeBarWidth(ctx, host.state.displayW)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            setPadDp(10f, 6f, 10f, 6f)
            elevation = ctx.dpF(22f)
            setOnHoverListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> autoHide.onTaskbarHoverChanged(true)
                    MotionEvent.ACTION_HOVER_EXIT -> {
                        autoHide.onTaskbarHoverChanged(false)
                        hoverPreview.scheduleHide()
                    }
                }
                false
            }
        }
        barRoot = root

        // 1. Left Cluster: App Drawer + Compact DeX Navigation
        btnApps = makeBarIconBtn(ctx, "所有应用与搜索 (Meta+Space)") {
            hoverPreview.hideNow()
            host.toggleAppDrawer(focusSearch = false)
        }.also { root.addView(it, ctx.llp(ctx.dp(40f), ctx.dp(40f), rightDp = 6f)) }

        btnBack = makeBarIconBtn(ctx, "返回") { host.sendGlobalBack() }
            .also { root.addView(it, ctx.llp(ctx.dp(34f), ctx.dp(36f), rightDp = 2f)) }
        btnHome = makeBarIconBtn(ctx, "返回桌面 (Meta+D)") {
            hoverPreview.hideNow()
            host.toggleShowDesktop()
        }.also { root.addView(it, ctx.llp(ctx.dp(34f), ctx.dp(36f), rightDp = 2f)) }
        btnRecents = makeBarIconBtn(ctx, "窗口切换器 (Alt+Tab)") {
            hoverPreview.hideNow()
            host.toggleWindowSwitcher()
        }.also { root.addView(it, ctx.llp(ctx.dp(34f), ctx.dp(36f), rightDp = 4f)) }

        btnWorkbench = TextView(ctx).apply {
            text = "工作台"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            isSingleLine = true
            setPadDp(9f, 6f)
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tooltipText = "开关工作台应用卡片栏"
            OverlayUiUtils.attachTactilePress(this, 0.92f)
            setOnClickListener {
                hoverPreview.hideNow()
                host.toggleWorkbench()
            }
        }.also { root.addView(it, ctx.llp(WRAP, ctx.dp(34f), rightDp = 4f)) }

        leftDivider = View(ctx).also { root.addView(it, ctx.llp(ctx.dp(1f), ctx.dp(24f), leftDp = 4f, rightDp = 6f)) }

        // 2. Center Task Strip (Scrollable, unlimited icons)
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        taskStrip = strip
        taskScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(strip, LinearLayout.LayoutParams(WRAP, MATCH))
        }.also { root.addView(it, ctx.llp(0, MATCH, 1f)) }

        rightDivider = View(ctx).also { root.addView(it, ctx.llp(ctx.dp(1f), ctx.dp(24f), leftDp = 6f, rightDp = 6f)) }

        // 3. Right Cluster: USB-C Input Pill + System Status Tray + Show Desktop
        inputPill = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadDp(9f, 6f)
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tooltipText = "切换键鼠控制屏幕"
            OverlayUiUtils.attachTactilePress(this, 0.92f)
            setOnClickListener { host.toggleInputRouting() }
        }.also { root.addView(it, ctx.llp(WRAP, ctx.dp(34f), rightDp = 6f)) }

        trayPill = buildSystemTray(ctx).also { root.addView(it, ctx.llp(WRAP, ctx.dp(40f), rightDp = 6f)) }

        btnShowDesktop = makeBarIconBtn(ctx, "返回桌面") {
            hoverPreview.hideNow()
            host.toggleShowDesktop()
        }.also { root.addView(it, ctx.llp(ctx.dp(34f), ctx.dp(36f))) }

        val bottomPad = ctx.dp(DesktopGeometry.TASKBAR_BOTTOM_MARGIN_DP.toFloat())
        val lp = OverlayWindowParams.floating(
            barW, barH, host.winType,
            x = 0, y = bottomPad,
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            touchModal = false,
        )
        barLp = lp
        applyTheme(ctx, host.tokens)
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(SURFACE_KEY, OverlayLayer.DOCK, root, lp))
    }

    private fun makeBarIconBtn(ctx: Context, tooltip: String, onClick: () -> Unit): ImageView = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tooltipText = tooltip
        OverlayUiUtils.attachTactilePress(this, 0.88f)
        setOnClickListener { onClick() }
    }

    private fun buildSystemTray(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadDp(10f, 4f, 10f, 4f)
        pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tooltipText = "快捷设置与状态中心"
        setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> autoHide.onTrayPressedChanged(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> autoHide.onTrayPressedChanged(false)
            }
            false
        }
        setOnClickListener {
            hoverPreview.hideNow()
            autoHide.markTrayToggled()
            host.toggleQuickSettings()
        }

        trayWifi = VectorIconHolder(ctx, HyperUi.ICON_WIFI, host.tokens.textPrimary, 15f)
            .also { addView(it.view, ctx.llp(ctx.dp(15f), ctx.dp(15f), rightDp = 6f)) }
        trayBt = VectorIconHolder(ctx, HyperUi.ICON_BLUETOOTH, host.tokens.textSecondary, 14f)
            .also { addView(it.view, ctx.llp(ctx.dp(14f), ctx.dp(14f), rightDp = 6f)) }
        trayVol = VectorIconHolder(ctx, HyperUi.ICON_VOLUME_HIGH, host.tokens.textPrimary, 15f)
            .also { addView(it.view, ctx.llp(ctx.dp(15f), ctx.dp(15f), rightDp = 6f)) }
        trayBatt = MiniBatteryView(ctx).also { addView(it, ctx.llp(ctx.dp(20f), ctx.dp(11.5f), rightDp = 4f)) }
        trayBattTxt = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
            paint.isFakeBoldText = true
            isSingleLine = true
            ellipsize = null
        }.also { addView(it, ctx.llp(rightDp = 9f)) }

        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            trayTime = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
                paint.isFakeBoldText = true
                isSingleLine = true
                ellipsize = null
                minWidth = ctx.dp(36f)
                gravity = Gravity.END
            }.also(::addView)
            trayDate = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9.5f)
                isSingleLine = true
                ellipsize = null
                gravity = Gravity.END
            }.also(::addView)
        })
        updateClock()
    }

    fun render(ctx: Context, state: DesktopState, status: BridgeClient.Status, sys: BridgeClient.SysStatus, volLevel: Int, tokens: GlassOverlayTokens) {
        if (lastDark != tokens.isDark) {
            lastDark = tokens.isDark
            lastTaskSig = ""
            applyTheme(ctx, tokens)
        }
        autoHide.autoHidePreference = host.isTaskbarAutoHide
        autoHide.isDesktopClear = state.isDesktopClear
        autoHide.isImmersiveActive = state.activeImmersiveWindow != null

        updateWorkbenchBtn(ctx, tokens)
        updateInputPill(ctx, status, tokens)
        updateTaskStrip(ctx, state.taskbarItems, tokens)
        refreshHardware(volLevel, sys, tokens)
        autoHide.evaluate(forceDelay = false)
    }

    private fun updateWorkbenchBtn(ctx: Context, tokens: GlassOverlayTokens) {
        val btn = btnWorkbench ?: return
        val active = host.isWorkbenchEnabled
        if (lastWorkbenchOn == active && lastDark == tokens.isDark) return
        lastWorkbenchOn = active
        val fg = if (active) Color.WHITE else tokens.textPrimary
        btn.setTextColor(fg)
        btn.background = if (active) {
            roundedBg(tokens.accentBlue, ctx.dpF(13f), ctx.dp(0.8f), 0x55FFFFFF)
        } else {
            OverlayUiUtils.makeStateListBg(ctx, tokens.hoverChipFill, tokens.activeChipFill, 13f)
        }
        HyperUi.setStartIcon(btn, HyperUi.ICON_SIDEBAR, fg, 12.5f, 4f)
    }

    fun setQuickSettingsHighlighted(active: Boolean) {
        quickSettingsActive = active
        val ctx = host.extCtx ?: return
        styleTrayPill(ctx, host.tokens)
    }

    fun onDisplayBoundsChanged(ctx: Context, dispW: Int) {
        val root = barRoot ?: return
        val lp = barLp ?: return
        val newW = computeBarWidth(ctx, dispW)
        if (lp.width != newW) {
            lp.width = newW
            host.surfaceHost.updateWindowLayout(root, lp, host.extWm)
        }
    }

    fun isPointInTray(x: Float, y: Float): Boolean = OverlayUiUtils.isPointInViewOnScreen(trayPill, x, y)

    fun isPointInTaskStrip(x: Float, y: Float): Boolean =
        !autoHide.isHidden && OverlayUiUtils.isPointInViewOnScreen(taskScroll, x, y)

    fun onCursorMoved(y: Float) = autoHide.onCursorMoved(y)

    fun hideHoverPreview() = hoverPreview.hideNow()

    fun refreshHardware(volLevel: Int, sys: BridgeClient.SysStatus, tokens: GlassOverlayTokens) {
        trayVol?.update(kind = HyperUi.volumeIconKind(volLevel), tint = tokens.textPrimary)
        if (sys.valid) {
            trayWifi?.update(
                kind = if (sys.wifiOn) HyperUi.ICON_WIFI else HyperUi.ICON_WIFI_OFF,
                tint = if (sys.wifiOn) tokens.textPrimary else tokens.textTertiary,
            )
            trayBt?.update(
                kind = HyperUi.ICON_BLUETOOTH,
                tint = if (sys.btOn) tokens.accentBlue else tokens.textTertiary,
            )
        }
        trayBatt?.setBattery(host.batteryPct, host.batteryCharging, tokens.textPrimary)
        val bTxt = "${host.batteryPct}%"
        trayBattTxt?.let { if (it.text?.toString() != bTxt) it.text = bTxt }
        updateClock()
    }

    private fun updateClock() {
        val now = Date()
        val tStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val dStr = SimpleDateFormat("M/d", Locale.getDefault()).format(now)
        trayTime?.let { if (it.text?.toString() != tStr) it.text = tStr }
        trayDate?.let { if (it.text?.toString() != dStr) it.text = dStr }
    }

    private fun updateInputPill(ctx: Context, status: BridgeClient.Status, tokens: GlassOverlayTokens) {
        val pill = inputPill ?: return
        val mode = when {
            status.isRemoteSink -> 1
            status.isLocalDexActive -> 2
            else -> 0
        }
        val modeChanged = (lastPillMode != mode)
        if (modeChanged) {
            lastPillMode = mode
            pill.visibility = if (mode == 1) View.GONE else View.VISIBLE
            if (mode == 2) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) pill.tooltipText = "退出本机 DeX 模式"
                pill.setOnClickListener {
                    hoverPreview.hideNow()
                    host.exitLocalDex()
                }
            } else if (mode == 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) pill.tooltipText = "切换键鼠控制屏幕"
                pill.setOnClickListener { host.toggleInputRouting() }
            }
        }
        if (mode == 1) return
        if (mode == 2) {
            if (!modeChanged && lastDark == tokens.isDark && pill.text?.toString() == "退出") return
            pill.text = "退出"
            val fg = tokens.accentRed
            val fill = if (tokens.isDark) 0x30FF453A else 0x1BFF3B30
            val stroke = if (tokens.isDark) 0x48FF453A else 0x38FF3B30
            pill.setTextColor(fg)
            pill.background = roundedBg(fill, ctx.dpF(14f), ctx.dp(0.8f).coerceAtLeast(1), stroke)
            HyperUi.setStartIcon(pill, HyperUi.ICON_CLOSE, fg, 11.5f, 3.5f)
            return
        }
        if (!modeChanged && lastInputToExt == status.inputToExt && lastDark == tokens.isDark && pill.text.isNotEmpty()) return
        lastInputToExt = status.inputToExt
        val onExt = status.inputToExt == 1
        pill.text = if (onExt) "外接" else "本机"
        val fg = if (onExt) Color.WHITE else tokens.textPrimary
        pill.setTextColor(fg)
        pill.background = roundedBg(if (onExt) tokens.accentBlue else tokens.hoverChipFill, ctx.dpF(14f))
        HyperUi.setStartIcon(pill, HyperUi.ICON_SWAP, fg, 12f, 3.5f)
    }

    private fun updateTaskStrip(ctx: Context, items: List<TaskbarItem>, tokens: GlassOverlayTokens) {
        val strip = taskStrip ?: return
        val sig = buildString {
            for (it in items) {
                append(it.pkg).append(':').append(it.taskId).append(':')
                    .append(if (it.focused) 'F' else if (it.minimized) 'M' else if (it.running) 'R' else 'P')
                    .append(';')
            }
        }
        if (sig == lastTaskSig) return
        lastTaskSig = sig
        strip.removeAllViews()

        val pm = host.serviceContext.packageManager
        for (item in items) {
            strip.addView(buildTaskSlot(ctx, item, host.getCachedLabel(pm, item.pkg), host.getCachedIcon(pm, item.pkg), tokens))
        }
    }

    private fun buildTaskSlot(
        ctx: Context,
        item: TaskbarItem,
        label: String,
        icon: android.graphics.drawable.Drawable?,
        tokens: GlassOverlayTokens,
    ): LinearLayout = LinearLayout(ctx).apply {
        tag = item.pkg
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadDp(7f, 4f, 7f, 3f)
        pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tooltipText = label
        layoutParams = ctx.llp(leftDp = 2.5f, rightDp = 2.5f)
        background = OverlayUiUtils.makeStateListBg(
            ctx,
            if (item.focused) tokens.activeChipFill else if (item.running) tokens.hoverChipFill else Color.TRANSPARENT,
            tokens.activeChipFill,
            14f,
        )

        val iconImg = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (icon != null) setImageDrawable(icon)
            alpha = if (item.minimized) 0.78f else 1f
        }
        addView(iconImg, ctx.llp(ctx.dp(30f), ctx.dp(30f)))

        val indicatorW = ctx.dp(if (item.focused) 16f else if (item.running) 6f else 4f)
        val indicatorH = ctx.dp(3f)
        addView(
            View(ctx).apply {
                background = roundedBg(
                    when {
                        item.focused -> tokens.accentBlue
                        item.running -> tokens.textSecondary
                        else -> Color.TRANSPARENT
                    },
                    ctx.dpF(2f),
                )
            },
            ctx.llp(indicatorW, indicatorH, topDp = 3f),
        )

        setOnHoverListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    iconImg.animate().scaleX(1.14f).scaleY(1.14f).translationY(-ctx.dpF(2.5f))
                        .setDuration(110L).setInterpolator(DecelerateInterpolator()).start()
                    hoverPreview.scheduleShow(v, item, label)
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    iconImg.animate().scaleX(1f).scaleY(1f).translationY(0f)
                        .setDuration(120L).setInterpolator(DecelerateInterpolator()).start()
                    hoverPreview.scheduleHide()
                }
            }
            false
        }

        setOnClickListener {
            hoverPreview.hideNow()
            host.popovers.dismissAll()
            bounceSlot(ctx, this, downward = item.focused && !item.minimized)
            when (DesktopStateReducer.resolveTaskbarClick(item)) {
                TaskbarClickOutcome.LAUNCH_NEW -> host.launchApp(item.pkg)
                TaskbarClickOutcome.FOCUS_AND_RESTORE -> host.focusWindow(item.taskId, item.pkg)
                TaskbarClickOutcome.MINIMIZE_FOCUSED -> host.minimizeWindow(item.taskId, item.pkg)
            }
        }

        OverlayUiUtils.attachRightClickListener(this, includeLongClick = true, pressedScale = 0.88f) {
            hoverPreview.hideNow()
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            host.openTaskbarItemMenu(item, this, loc[0] + width / 2, loc[1] - ctx.dp(8f))
        }
    }

    private fun bounceSlot(ctx: Context, view: View, downward: Boolean) {
        val sc = if (downward) 0.88f else 1.10f
        view.animate().cancel()
        view.animate()
            .translationY(if (downward) ctx.dpF(3.5f) else -ctx.dpF(3.5f))
            .scaleX(sc).scaleY(sc).setDuration(80L).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.animate().translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(170L).setInterpolator(OvershootInterpolator(2.0f)).start()
            }
            .start()
    }

    private fun applyTheme(ctx: Context, tokens: GlassOverlayTokens) {
        barRoot?.let { tokens.applyGlassBackground(it, tokens.taskbarFill, cornerRadiusDp = 24f, strokeWidthDp = 1.2f) }
        btnApps?.setImageDrawable(HyperUi.icon(ctx, HyperUi.ICON_APPS, tokens.accentBlue, 19f))
        btnApps?.background = OverlayUiUtils.makeStateListBg(ctx, tokens.activeChipFill, tokens.hoverChipFill, 14f)

        btnBack?.setImageDrawable(HyperUi.icon(ctx, HyperUi.ICON_CHEVRON_LEFT, tokens.textPrimary, 16f))
        btnBack?.background = OverlayUiUtils.makeStateListBg(ctx, Color.TRANSPARENT, tokens.hoverChipFill, 12f)
        btnHome?.setImageDrawable(HyperUi.icon(ctx, HyperUi.ICON_HOME_NAV, tokens.textPrimary, 16f))
        btnHome?.background = OverlayUiUtils.makeStateListBg(ctx, Color.TRANSPARENT, tokens.hoverChipFill, 12f)
        btnRecents?.setImageDrawable(HyperUi.icon(ctx, HyperUi.ICON_RECENTS_NAV, tokens.textPrimary, 15.5f))
        btnRecents?.background = OverlayUiUtils.makeStateListBg(ctx, Color.TRANSPARENT, tokens.hoverChipFill, 12f)
        lastWorkbenchOn = null
        updateWorkbenchBtn(ctx, tokens)

        btnShowDesktop?.setImageDrawable(HyperUi.icon(ctx, HyperUi.ICON_SHOW_DESKTOP, tokens.textSecondary, 16f))
        btnShowDesktop?.background = OverlayUiUtils.makeStateListBg(ctx, Color.TRANSPARENT, tokens.hoverChipFill, 12f)

        leftDivider?.setBackgroundColor(tokens.divider)
        rightDivider?.setBackgroundColor(tokens.divider)
        trayBattTxt?.setTextColor(tokens.textPrimary)
        trayTime?.setTextColor(tokens.textPrimary)
        trayDate?.setTextColor(tokens.textSecondary)
        styleTrayPill(ctx, tokens)
        autoHide.applyTheme(ctx, tokens)
        hoverPreview.applyTheme(ctx, tokens)
    }

    private fun styleTrayPill(ctx: Context, tokens: GlassOverlayTokens) {
        trayPill?.background = roundedBg(
            fillColor = if (quickSettingsActive) tokens.activeChipFill else tokens.hoverChipFill,
            radiusPx = ctx.dpF(16f),
            strokeWidthPx = if (quickSettingsActive) ctx.dp(1f) else 0,
            strokeColor = tokens.accentBlue,
        )
    }

    private fun computeBarWidth(ctx: Context, displayW: Int): Int {
        val margin = ctx.dp(12f) * 2
        val maxW = ctx.dp(1280f)
        return max(ctx.dp(460f), (displayW - margin).coerceAtMost(maxW))
    }

    fun detach() {
        hoverPreview.detach()
        autoHide.detach()
        host.surfaceHost.unregister(SURFACE_KEY, host.extWm)
        barRoot = null; barLp = null
        btnApps = null; btnBack = null; btnHome = null; btnRecents = null; btnWorkbench = null
        leftDivider = null; rightDivider = null; taskScroll = null; taskStrip = null
        inputPill = null; trayPill = null; trayWifi = null; trayBt = null; trayVol = null
        trayBatt = null; trayBattTxt = null; trayTime = null; trayDate = null; btnShowDesktop = null
        lastTaskSig = ""; lastDark = null; lastInputToExt = -1; lastPillMode = -1; lastWorkbenchOn = null
    }
}

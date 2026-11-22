package com.extbridge.helper.desktop.popovers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.AudioManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.HyperUi
import com.extbridge.helper.desktop.model.GlassOverlayTokens
import com.extbridge.helper.desktop.state.DesktopShellHost
import com.extbridge.helper.overlay.MATCH
import com.extbridge.helper.overlay.MiniBatteryView
import com.extbridge.helper.overlay.OverlayLayer
import com.extbridge.helper.overlay.OverlayPopover
import com.extbridge.helper.overlay.OverlaySurfaceHost
import com.extbridge.helper.overlay.OverlayUiUtils
import com.extbridge.helper.overlay.OverlayWindowParams
import com.extbridge.helper.overlay.WRAP
import com.extbridge.helper.overlay.animatePopover
import com.extbridge.helper.overlay.dp
import com.extbridge.helper.overlay.dpF
import com.extbridge.helper.overlay.llp
import com.extbridge.helper.overlay.measureForMax
import com.extbridge.helper.overlay.resetPopoverVisuals
import com.extbridge.helper.overlay.roundedBg
import com.extbridge.helper.overlay.setPadDp
import com.extbridge.helper.ui.ExtThemePrefs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Light/Dark adaptive Liquid Glass Samsung DeX Quick Settings panel anchored above the bottom-right
 * system tray. Features 2×3 Bento connectivity tiles, dual vertical frosted glass pill sliders
 * (Volume & Main-Screen Dim), display mode / rotation / auto-hide / glass theme pills, and footer.
 */
@SuppressLint("ClickableViewAccessibility")
internal class DeXQuickSettingsOverlay(
    private val host: DesktopShellHost,
    private val isTrayInteractionRecent: () -> Boolean,
    private val onVisibilityChanged: (Boolean) -> Unit,
) : OverlayPopover {

    companion object {
        private const val TAG = "ExtDeXQuickSettings"
        private const val POPOVER_ID = "dex_quick_settings"
        private const val CARD_WIDTH_DP = 368f
        private const val OUTSIDE_DISMISS_DEBOUNCE_MS = 70L
    }

    override val id: String = POPOVER_ID
    override var isOpen: Boolean = false
        private set

    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var footerBar: LinearLayout? = null
    private var battView: MiniBatteryView? = null
    private var battText: TextView? = null
    private var btnConsole: TextView? = null
    private var btnSettings: TextView? = null
    private var btnExitLocalDex: TextView? = null

    private lateinit var tileWifi: GlassQuickTile
    private lateinit var tileBt: GlassQuickTile
    private lateinit var tileInput: GlassQuickTile
    private lateinit var tileTouchpad: GlassQuickTile
    private lateinit var tileAirplane: GlassQuickTile
    private lateinit var tilePowerSave: GlassQuickTile
    private lateinit var volSlider: VerticalGlassSlider
    private lateinit var dimSlider: VerticalGlassSlider
    private var bentoExtRow: LinearLayout? = null
    private var modeChipRow: LinearLayout? = null
    private var extChipRow: LinearLayout? = null

    private lateinit var btnDex: TextView
    private lateinit var btnMirror: TextView
    private lateinit var btnRotate: TextView
    private lateinit var btnWorkbench: TextView
    private lateinit var btnAutoHide: TextView
    private lateinit var btnTheme: TextView
    private lateinit var btnBlackout: TextView

    private var lastDark: Boolean? = null
    private val outsideDismissTask = Runnable {
        if (isOpen && !isTrayInteractionRecent()) dismiss()
    }

    fun build(ctx: Context) {
        detach()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            elevation = ctx.dpF(26f)
            visibility = View.GONE
            setOnTouchListener { _, ev ->
                if (ev != null && ev.actionMasked == MotionEvent.ACTION_OUTSIDE && isOpen && !isTrayInteractionRecent()) {
                    host.handler.removeCallbacks(outsideDismissTask)
                    host.handler.postDelayed(outsideDismissTask, OUTSIDE_DISMISS_DEBOUNCE_MS)
                }
                false
            }
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadDp(16f, 16f, 16f, 14f)
        }
        body.addView(buildBentoTop(ctx))
        buildModeChips(ctx, body)
        card.addView(body)
        card.addView(buildFooter(ctx))

        val wmLp = OverlayWindowParams.popover(ctx.dp(CARD_WIDTH_DP), WRAP, host.winType, ctx = ctx, wm = host.extWm)
        root = card; lp = wmLp
        applyTheme(ctx, host.tokens)
        host.popovers.register(this)
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(POPOVER_ID, OverlayLayer.POPOVER, card, wmLp))
    }

    private fun buildBentoTop(ctx: Context): LinearLayout {
        tileWifi = GlassQuickTile(ctx, HyperUi.ICON_WIFI, "Wi-Fi",
            onClick = { host.execSysAction(BridgeClient.SYS_WIFI) },
            onLongClick = { host.openExtSettings(Settings.ACTION_WIFI_SETTINGS) })
        tileBt = GlassQuickTile(ctx, HyperUi.ICON_BLUETOOTH, "蓝牙",
            onClick = { host.execSysAction(BridgeClient.SYS_BT) },
            onLongClick = { host.openExtSettings(Settings.ACTION_BLUETOOTH_SETTINGS) })
        tileInput = GlassQuickTile(ctx, HyperUi.ICON_DEX, "键鼠路由",
            onClick = { host.toggleInputRouting() })
        tileTouchpad = GlassQuickTile(ctx, HyperUi.ICON_TOUCHPAD, "触控板伴侣",
            onClick = { dismiss(); host.openTabletTouchpad() })
        tileAirplane = GlassQuickTile(ctx, HyperUi.ICON_AIRPLANE, "飞行模式",
            onClick = { host.execSysAction(BridgeClient.SYS_AIRPLANE) })
        tilePowerSave = GlassQuickTile(ctx, HyperUi.ICON_BATTERY_SAVER, "节能模式",
            onClick = { host.execSysAction(BridgeClient.SYS_POWER_SAVE) })

        val extRow = OverlayUiUtils.makeChipRow(ctx, 8f, bottomMarginDp = 8f, items = arrayOf(tileInput.root, tileTouchpad.root))
        bentoExtRow = extRow

        val leftGrid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(OverlayUiUtils.makeChipRow(ctx, 8f, bottomMarginDp = 8f, items = arrayOf(tileWifi.root, tileBt.root)))
            addView(extRow)
            addView(OverlayUiUtils.makeChipRow(ctx, 8f, bottomMarginDp = 0f, items = arrayOf(tileAirplane.root, tilePowerSave.root)))
        }

        volSlider = VerticalGlassSlider(ctx, HyperUi.ICON_VOLUME_HIGH, host.tokens.accentBlue, 0.55f) { ratio, finished ->
            host.audioManager?.let { am ->
                val maxV = max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                val nextV = (ratio * maxV).roundToInt().coerceIn(0, maxV)
                try { am.setStreamVolume(AudioManager.STREAM_MUSIC, nextV, 0) }
                catch (t: Throwable) { Log.w(TAG, "setStreamVolume failed", t) }
                if (finished) host.notifyHardwareChanged()
            }
        }
        dimSlider = VerticalGlassSlider(ctx, HyperUi.ICON_SUN, host.tokens.accentAmber, if (host.isBlackoutActive) 0.08f else 0.88f) { ratio, finished ->
            if (finished) {
                val wantBlackout = ratio < 0.35f
                if (wantBlackout != host.isBlackoutActive) host.toggleBlackout()
            }
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = ctx.llp(MATCH, WRAP, bottomDp = 12f)
            addView(leftGrid, ctx.llp(0, WRAP, 1f, rightDp = 10f))
            addView(volSlider, ctx.llp(ctx.dp(50f), ctx.dp(186f), rightDp = 8f))
            addView(dimSlider, ctx.llp(ctx.dp(50f), ctx.dp(186f)))
        }
    }

    private fun buildModeChips(ctx: Context, body: LinearLayout) {
        btnDex = makeChip(ctx) { dismiss(); host.switchMirrorMode(BridgeClient.MIRROR_MODE_DEX) }
        btnMirror = makeChip(ctx) { dismiss(); host.switchMirrorMode(BridgeClient.MIRROR_MODE_MIRROR) }
        modeChipRow = OverlayUiUtils.makeChipRow(ctx, 6f, items = arrayOf(btnDex, btnMirror)).also { body.addView(it) }

        btnWorkbench = makeChip(ctx) { host.toggleWorkbench() }
        btnAutoHide = makeChip(ctx) { host.setTaskbarAutoHide(!host.isTaskbarAutoHide) }
        btnTheme = makeChip(ctx) { host.cycleGlassTheme() }
        body.addView(OverlayUiUtils.makeChipRow(ctx, 6f, topMarginDp = 6f, items = arrayOf(btnWorkbench, btnAutoHide, btnTheme)))

        btnRotate = makeChip(ctx) { host.cycleRotation() }
        btnBlackout = makeChip(ctx) { host.toggleBlackout() }
        extChipRow = OverlayUiUtils.makeChipRow(ctx, 6f, topMarginDp = 6f, items = arrayOf(btnRotate, btnBlackout)).also { body.addView(it) }
    }

    private fun buildFooter(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadDp(16f, 10f, 14f, 12f)
        battView = MiniBatteryView(ctx).also { addView(it, ctx.llp(ctx.dp(22f), ctx.dp(13f), rightDp = 7f)) }
        battText = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            paint.isFakeBoldText = true
        }.also { addView(it, ctx.llp(0, WRAP, 1f)) }

        btnConsole = makeChip(ctx) { dismiss(); host.openTabletMain() }.also {
            it.text = "控制台"
            addView(it, ctx.llp(WRAP, WRAP, rightDp = 8f))
        }
        btnExitLocalDex = makeChip(ctx) { dismiss(); host.exitLocalDex() }.also {
            it.text = "退出 DeX"
            it.visibility = View.GONE
            addView(it, ctx.llp(WRAP, WRAP, rightDp = 8f))
        }
        btnSettings = makeChip(ctx) { dismiss(); host.openExtSettings(Settings.ACTION_SETTINGS) }.also {
            it.text = "设置"
            addView(it, ctx.llp(WRAP, WRAP))
        }
        footerBar = this
    }

    private fun makeChip(ctx: Context, onClick: () -> Unit): TextView = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
        paint.isFakeBoldText = true
        gravity = Gravity.CENTER
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setPadDp(10f, 8f)
        pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
        OverlayUiUtils.attachTactilePress(this, 0.94f)
        setOnClickListener { onClick() }
    }

    fun toggle() {
        host.handler.removeCallbacks(outsideDismissTask)
        if (isOpen) dismiss() else open()
    }

    fun open() {
        val ctx = host.extCtx ?: return
        val card = root ?: return
        val wmLp = lp ?: return
        host.handler.removeCallbacks(outsideDismissTask)
        host.popovers.dismissAllExcept(POPOVER_ID)
        isOpen = true
        onVisibilityChanged(true)
        syncState(ctx)

        val cardW = ctx.dp(CARD_WIDTH_DP)
        val dispW = max(600, host.state.displayW)
        val dispH = max(480, host.state.displayH)
        card.measureForMax(cardW, dispH, exactW = true)
        val cardH = max(ctx.dp(180f), card.measuredHeight)
        val margin = ctx.dp(14f)
        wmLp.x = max(margin, dispW - cardW - margin)
        wmLp.y = max(margin, dispH - ctx.dp(74f) - cardH)

        card.visibility = View.VISIBLE
        host.surfaceHost.updateWindowLayout(card, wmLp, host.extWm)
        card.animatePopover(show = true, shiftYDp = 16f, startScale = 0.94f, enterMs = 175L) {
            host.bringCursorToFront()
        }
        host.requestStateRefresh()
    }

    override fun dismiss() {
        host.handler.removeCallbacks(outsideDismissTask)
        val wasOpen = isOpen
        isOpen = false
        if (wasOpen) onVisibilityChanged(false)
        val card = root
        if (card != null && card.visibility == View.VISIBLE) {
            card.animatePopover(show = false, shiftYDp = 14f, startScale = 0.95f, exitMs = 125L) {
                if (!isOpen) {
                    card.visibility = View.GONE
                    card.resetPopoverVisuals()
                }
            }
        } else {
            card?.let { it.animate().cancel(); it.visibility = View.GONE }
        }
    }

    fun syncState(ctx: Context) {
        if (root == null) return
        val tokens = host.tokens
        if (lastDark != tokens.isDark) {
            lastDark = tokens.isDark
            applyTheme(ctx, tokens)
        }
        val st = host.lastStatus
        val sys = host.lastSysStatus
        val isLocalDex = st.isLocalDexActive
        val extVis = if (isLocalDex) View.GONE else View.VISIBLE
        bentoExtRow?.visibility = extVis
        dimSlider.visibility = extVis
        modeChipRow?.visibility = extVis
        extChipRow?.visibility = extVis
        (volSlider.layoutParams as? LinearLayout.LayoutParams)?.let { vlp ->
            val targetH = ctx.dp(if (isLocalDex) 122f else 186f)
            val targetRight = if (isLocalDex) 0 else ctx.dp(8f)
            if (vlp.height != targetH || vlp.rightMargin != targetRight) {
                vlp.height = targetH
                vlp.rightMargin = targetRight
                volSlider.layoutParams = vlp
            }
        }
        if (sys.valid) {
            tileWifi.bind(sys.wifiOn, if (sys.wifiOn) sys.wifiSsid.ifEmpty { "已开启" } else "Wi-Fi", tokens)
            tileBt.bind(sys.btOn, if (sys.btOn) sys.btName.ifEmpty { "已开启" } else "蓝牙", tokens)
            tileAirplane.bind(sys.airplaneOn, "飞行模式", tokens)
            tilePowerSave.bind(sys.powerSaveOn, "节能模式", tokens)
        } else {
            tileWifi.bind(false, "Wi-Fi", tokens)
            tileBt.bind(false, "蓝牙", tokens)
            tileAirplane.bind(false, "飞行模式", tokens)
            tilePowerSave.bind(false, "节能模式", tokens)
        }
        tileInput.bind(st.inputToExt == 1, if (st.inputToExt == 1) "外接扩展屏" else "本机内屏", tokens)
        tileTouchpad.bind(false, "触控板伴侣", tokens)

        host.audioManager?.takeIf { !volSlider.isDragging }?.let { am ->
            val maxV = max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            val curV = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val volLvl = if (curV == 0) 0 else if (curV * 2 > maxV) 2 else 1
            volSlider.update(curV.toFloat() / maxV, HyperUi.volumeIconKind(volLvl), tokens)
        }
        if (!dimSlider.isDragging) {
            dimSlider.update(
                if (host.isBlackoutActive) 0.08f else 0.88f,
                if (host.isBlackoutActive) HyperUi.ICON_MOON else HyperUi.ICON_SUN,
                tokens,
            )
        }

        styleChip(ctx, btnDex, st.mirrorMode == BridgeClient.MIRROR_MODE_DEX, HyperUi.ICON_DEX, "DeX 桌面", tokens)
        styleChip(ctx, btnMirror, st.mirrorMode == BridgeClient.MIRROR_MODE_MIRROR, HyperUi.ICON_MIRROR, "融合镜像", tokens)
        styleChip(ctx, btnRotate, st.virtRot != 0, HyperUi.ICON_ROTATE, "旋转 ${BridgeClient.rotationLabel(st.virtRot)}", tokens)
        styleChip(ctx, btnWorkbench, host.isWorkbenchEnabled, HyperUi.ICON_SIDEBAR, if (host.isWorkbenchEnabled) "工作台开" else "工作台", tokens)
        styleChip(ctx, btnAutoHide, host.isTaskbarAutoHide, HyperUi.ICON_PIN, if (host.isTaskbarAutoHide) "底栏隐藏" else "底栏固定", tokens)
        val themeLabel = when (ExtThemePrefs.getThemeModeIndex(host.serviceContext)) {
            ExtThemePrefs.MODE_DARK -> "黑曜毛玻璃"
            ExtThemePrefs.MODE_LIGHT -> "冰透毛玻璃"
            else -> "跟随系统"
        }
        styleChip(ctx, btnTheme, tokens.isDark, if (tokens.isDark) HyperUi.ICON_MOON else HyperUi.ICON_SUN, themeLabel, tokens)
        styleChip(ctx, btnBlackout, host.isBlackoutActive, if (host.isBlackoutActive) HyperUi.ICON_SUN else HyperUi.ICON_MOON,
            if (host.isBlackoutActive) "恢复主屏" else "主屏黑屏", tokens)

        battView?.setBattery(host.batteryPct, host.batteryCharging, tokens.textPrimary)
        battText?.text = "${host.batteryPct}%${if (host.batteryCharging) " · 充电中" else ""}"
        btnExitLocalDex?.let { btn ->
            val showExit = st.isLocalDexActive
            btn.visibility = if (showExit) View.VISIBLE else View.GONE
            if (showExit) {
                btn.text = "退出 DeX"
                val fg = Color.WHITE
                btn.setTextColor(fg)
                btn.background = roundedBg(tokens.accentRed, ctx.dpF(14f), ctx.dp(0.8f), 0x55FFFFFF)
                HyperUi.setStartIcon(btn, HyperUi.ICON_CLOSE, fg, 12.5f, 4.5f)
            }
        }
    }

    private fun applyTheme(ctx: Context, tokens: GlassOverlayTokens) {
        root?.let { tokens.applyGlassBackground(it, tokens.panelFill, cornerRadiusDp = 28f, strokeWidthDp = 1.25f, clipOutline = true) }
        footerBar?.setBackgroundColor(if (tokens.isDark) 0x22FFFFFF else 0x0C0F172A)
        battText?.setTextColor(tokens.textPrimary)
        btnConsole?.let { styleChip(ctx, it, false, HyperUi.ICON_SLIDERS, "控制台", tokens) }
        btnSettings?.let { styleChip(ctx, it, false, HyperUi.ICON_SETTINGS, "设置", tokens) }
    }

    private fun styleChip(ctx: Context, tv: TextView, active: Boolean, iconKind: Int, label: String, tokens: GlassOverlayTokens) {
        tv.text = label
        val fg = if (active) Color.WHITE else tokens.textPrimary
        val bg = if (active) tokens.accentBlue else tokens.cardInnerFill
        tv.setTextColor(fg)
        tv.background = roundedBg(bg, ctx.dpF(14f), ctx.dp(0.8f), if (active) 0x55FFFFFF else tokens.divider)
        HyperUi.setStartIcon(tv, iconKind, fg, 12.5f, 4.5f)
    }

    fun detach() {
        host.handler.removeCallbacks(outsideDismissTask)
        isOpen = false
        host.popovers.unregister(POPOVER_ID)
        host.surfaceHost.unregister(POPOVER_ID, host.extWm)
        root = null; lp = null; footerBar = null; battView = null; battText = null; btnConsole = null; btnSettings = null; btnExitLocalDex = null
        bentoExtRow = null; modeChipRow = null; extChipRow = null
    }

    private class GlassQuickTile(
        private val ctx: Context,
        private val iconKind: Int,
        initialTitle: String,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null,
    ) {
        private val iconView = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        private val labelView = TextView(ctx).apply {
            text = initialTitle
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
            paint.isFakeBoldText = true
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val root: LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadDp(10f, 12f, 10f, 12f)
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            OverlayUiUtils.attachTactilePress(this, 0.94f)
            addView(iconView, ctx.llp(ctx.dp(17f), ctx.dp(17f), rightDp = 8f))
            addView(labelView, ctx.llp(0, WRAP, 1f))
            setOnClickListener { onClick() }
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }

        fun bind(active: Boolean, title: String, tokens: GlassOverlayTokens) {
            labelView.text = title
            val fg = if (active) Color.WHITE else tokens.textPrimary
            labelView.setTextColor(fg)
            iconView.setImageDrawable(HyperUi.icon(ctx, iconKind, fg, 16f))
            root.background = roundedBg(
                if (active) tokens.accentBlue else tokens.cardInnerFill,
                ctx.dpF(16f), ctx.dp(0.8f), if (active) 0x55FFFFFF else tokens.divider,
            )
        }
    }

    private class VerticalGlassSlider(
        ctx: Context,
        private var iconKind: Int,
        private val accentColor: Int,
        initialProgress: Float,
        private val onChanged: (Float, Boolean) -> Unit,
    ) : View(ctx) {
        var isDragging = false
            private set
        private var progress = initialProgress.coerceIn(0f, 1f)
        private var tokens = GlassOverlayTokens.IceLight
        private val density = ctx.resources.displayMetrics.density
        private val clipPath = Path()
        private val rect = RectF()
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = accentColor }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.1f * density }

        fun update(value: Float, kind: Int, t: GlassOverlayTokens) {
            progress = value.coerceIn(0f, 1f)
            iconKind = kind
            tokens = t
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent?): Boolean {
            val ev = event ?: return false
            val h = height.coerceAtLeast(1).toFloat()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    progress = (1f - ev.y / h).coerceIn(0f, 1f)
                    invalidate()
                    onChanged(progress, false)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    progress = (1f - ev.y / h).coerceIn(0f, 1f)
                    isDragging = false
                    invalidate()
                    onChanged(progress, true)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val r = 18f * density
            trackPaint.color = tokens.cardInnerFill
            rimPaint.color = if (tokens.isDark) 0x44FFFFFF else 0xCCFFFFFF.toInt()
            rect.set(0f, 0f, w, h)
            clipPath.reset()
            clipPath.addRoundRect(rect, r, r, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawRect(rect, trackPaint)
            canvas.drawRect(0f, h * (1f - progress), w, h, fillPaint)
            val iconSz = (18f * density).roundToInt()
            val icon = HyperUi.icon(context, iconKind, if (progress > 0.22f) Color.WHITE else tokens.textPrimary, 18f)
            val ix = ((w - iconSz) * 0.5f).roundToInt()
            val iy = (h - iconSz - 13f * density).roundToInt()
            icon.setBounds(ix, iy, ix + iconSz, iy + iconSz)
            icon.draw(canvas)
            canvas.restoreToCount(save)
            val ins = rimPaint.strokeWidth * 0.5f
            rect.set(ins, ins, w - ins, h - ins)
            canvas.drawRoundRect(rect, r, r, rimPaint)
        }
    }
}

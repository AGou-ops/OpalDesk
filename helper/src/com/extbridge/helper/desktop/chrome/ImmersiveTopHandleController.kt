package com.extbridge.helper.desktop.chrome

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.extbridge.helper.HyperUi
import com.extbridge.helper.desktop.model.DesktopWindow
import com.extbridge.helper.desktop.model.GlassOverlayTokens
import com.extbridge.helper.desktop.state.DesktopShellHost
import com.extbridge.helper.overlay.OverlayLayer
import com.extbridge.helper.overlay.OverlaySurfaceHost
import com.extbridge.helper.overlay.OverlayUiUtils
import com.extbridge.helper.overlay.OverlayWindowParams
import com.extbridge.helper.overlay.WRAP
import com.extbridge.helper.overlay.dp
import com.extbridge.helper.overlay.dpF
import com.extbridge.helper.overlay.flp
import com.extbridge.helper.overlay.llp
import com.extbridge.helper.overlay.roundedBg
import com.extbridge.helper.overlay.setPadDp
import kotlin.math.max

/**
 * Minimal top-center frosted hover/tap handle shown ONLY when a window enters true
 * `IMMERSIVE` fullscreen mode (e.g., video/gaming), expanding into a sleek floating glass
 * capsule (`返回` / `退出全屏还原小窗` / `最小化` / `关闭`).
 */
@SuppressLint("ClickableViewAccessibility")
internal class ImmersiveTopHandleController(private val host: DesktopShellHost) {

    companion object {
        private const val SURFACE_KEY = "dex_immersive_handle"
        private const val AUTO_COLLAPSE_MS = 2800L
    }

    private var root: FrameLayout? = null
    private var idleBar: View? = null
    private var expandedRow: LinearLayout? = null
    private var titleLabel: TextView? = null
    private var btnBack: ImageView? = null
    private var btnRestore: TextView? = null
    private var btnMin: ImageView? = null
    private var btnClose: ImageView? = null
    private var lp: WindowManager.LayoutParams? = null

    private var activeWindow: DesktopWindow? = null
    private var expanded = false
    private val collapseTask = Runnable { setExpanded(false) }

    fun build(ctx: Context) {
        detach()
        val container = FrameLayout(ctx).apply {
            visibility = View.GONE
            setPadDp(8f, 3f, 8f, 6f)
        }
        val bar = View(ctx).apply {
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            setOnClickListener { setExpanded(true) }
            setOnHoverListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER) setExpanded(true)
                false
            }
        }
        idleBar = bar
        container.addView(bar, ctx.flp(ctx.dp(92f), ctx.dp(5f), Gravity.TOP or Gravity.CENTER_HORIZONTAL, topPx = ctx.dp(3f)))

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadDp(10f, 5f, 10f, 5f)
            elevation = ctx.dpF(18f)
            visibility = View.GONE
            setOnHoverListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> host.handler.removeCallbacks(collapseTask)
                    MotionEvent.ACTION_HOVER_EXIT -> scheduleAutoCollapse()
                }
                false
            }
        }
        btnBack = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            OverlayUiUtils.attachTactilePress(this, 0.88f)
            setOnClickListener { activeWindow?.let { host.sendBackToWindow(it.taskId, it.pkg) } }
        }.also { row.addView(it, ctx.llp(ctx.dp(26f), ctx.dp(26f), rightDp = 6f)) }

        titleLabel = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            paint.isFakeBoldText = true
            maxWidth = ctx.dp(150f)
            OverlayUiUtils.enforceSingleLine(this)
        }.also { row.addView(it, ctx.llp(rightDp = 10f)) }

        btnRestore = TextView(ctx).apply {
            text = "还原小窗"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
            paint.isFakeBoldText = true
            setPadDp(10f, 5f)
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            OverlayUiUtils.attachTactilePress(this, 0.92f)
            setOnClickListener {
                setExpanded(false)
                activeWindow?.let { host.exitImmersiveToFloating(it) }
            }
        }.also { row.addView(it, ctx.llp(rightDp = 6f)) }

        btnMin = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            OverlayUiUtils.attachTactilePress(this, 0.88f)
            setOnClickListener {
                setExpanded(false)
                activeWindow?.let { host.minimizeWindow(it.taskId, it.pkg) }
            }
        }.also { row.addView(it, ctx.llp(ctx.dp(26f), ctx.dp(26f), rightDp = 4f)) }

        btnClose = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            OverlayUiUtils.attachTactilePress(this, 0.88f)
            setOnClickListener {
                setExpanded(false)
                activeWindow?.let { host.closeWindow(it.taskId, it.pkg) }
            }
        }.also { row.addView(it, ctx.llp(ctx.dp(26f), ctx.dp(26f))) }

        expandedRow = row
        container.addView(row, ctx.flp(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        val wmLp = OverlayWindowParams.floating(
            WRAP, WRAP, host.winType, x = 0, y = 0,
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL, touchModal = false,
        )
        root = container; lp = wmLp
        applyTheme(ctx, host.tokens)
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(SURFACE_KEY, OverlayLayer.IMMERSIVE_HANDLE, container, wmLp))
    }

    fun update(ctx: Context, immersiveWindow: DesktopWindow?, tokens: GlassOverlayTokens) {
        activeWindow = immersiveWindow
        val container = root ?: return
        if (immersiveWindow == null) {
            setExpanded(false)
            if (container.visibility != View.GONE) container.visibility = View.GONE
            return
        }
        applyTheme(ctx, tokens)
        titleLabel?.text = host.getCachedLabel(host.serviceContext.packageManager, immersiveWindow.pkg)
        if (container.visibility != View.VISIBLE) container.visibility = View.VISIBLE
    }

    fun onCursorMoved(y: Float) {
        val ctx = host.extCtx ?: return
        if (activeWindow == null) return
        if (y <= ctx.dp(5f) && !expanded) {
            setExpanded(true)
        } else if (y > ctx.dp(54f) && expanded) {
            scheduleAutoCollapse()
        }
    }

    private fun setExpanded(open: Boolean) {
        host.handler.removeCallbacks(collapseTask)
        expanded = open
        idleBar?.visibility = if (open) View.GONE else View.VISIBLE
        expandedRow?.visibility = if (open) View.VISIBLE else View.GONE
        if (open) {
            scheduleAutoCollapse()
            host.bringCursorToFront()
        }
    }

    private fun scheduleAutoCollapse() {
        host.handler.removeCallbacks(collapseTask)
        host.handler.postDelayed(collapseTask, AUTO_COLLAPSE_MS)
    }

    private fun applyTheme(ctx: Context, tokens: GlassOverlayTokens) {
        idleBar?.background = roundedBg(tokens.handlePillIdle, ctx.dpF(3f), ctx.dp(1f), tokens.handlePillStroke)
        expandedRow?.let { tokens.applyGlassBackground(it, tokens.panelFill, cornerRadiusDp = 18f) }
        titleLabel?.setTextColor(tokens.textPrimary)
        btnBack?.setImageDrawable(HyperUi.icon(ctx, HyperUi.ICON_CHEVRON_LEFT, tokens.textPrimary, 14f))
        btnBack?.background = OverlayUiUtils.makeStateListBg(ctx, Color.TRANSPARENT, tokens.hoverChipFill, 10f)
        btnRestore?.setTextColor(Color.WHITE)
        btnRestore?.background = roundedBg(tokens.accentBlue, ctx.dpF(12f))
        btnMin?.setImageDrawable(HyperUi.icon(ctx, HyperUi.ICON_MINUS, tokens.textPrimary, 13f))
        btnMin?.background = OverlayUiUtils.makeStateListBg(ctx, Color.TRANSPARENT, tokens.hoverChipFill, 10f)
        btnClose?.setImageDrawable(HyperUi.icon(ctx, HyperUi.ICON_CLOSE, tokens.textPrimary, 13f))
        btnClose?.background = OverlayUiUtils.makeStateListBg(ctx, Color.TRANSPARENT, tokens.dangerHoverFill, 10f)
    }

    fun detach() {
        host.handler.removeCallbacks(collapseTask)
        expanded = false; activeWindow = null
        host.surfaceHost.unregister(SURFACE_KEY, host.extWm)
        root = null; idleBar = null; expandedRow = null; lp = null
    }
}

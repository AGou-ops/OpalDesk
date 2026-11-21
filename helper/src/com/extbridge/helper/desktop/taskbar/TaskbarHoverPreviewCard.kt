package com.extbridge.helper.desktop.taskbar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.text.TextUtils
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
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.HyperUi
import com.extbridge.helper.desktop.model.GlassOverlayTokens
import com.extbridge.helper.desktop.model.TaskbarItem
import com.extbridge.helper.desktop.state.DesktopShellHost
import com.extbridge.helper.overlay.MATCH
import com.extbridge.helper.overlay.OverlayLayer
import com.extbridge.helper.overlay.OverlaySurfaceHost
import com.extbridge.helper.overlay.OverlayUiUtils
import com.extbridge.helper.overlay.OverlayWindowParams
import com.extbridge.helper.overlay.WRAP
import com.extbridge.helper.overlay.animatePopover
import com.extbridge.helper.overlay.dp
import com.extbridge.helper.overlay.dpF
import com.extbridge.helper.overlay.llp
import com.extbridge.helper.overlay.setPadDp
import kotlin.math.max

/**
 * Interactive Samsung DeX Liquid Glass Taskbar hover preview card displaying a live
 * `TaskSnapshot` thumbnail above the hovered running icon, with direct click-to-focus
 * and an inline `×` quick-close button.
 */
@SuppressLint("ClickableViewAccessibility")
internal class TaskbarHoverPreviewCard(private val host: DesktopShellHost) {

    companion object {
        private const val SURFACE_KEY = "dex_taskbar_hover_preview"
        private const val SHOW_DELAY_MS = 200L
        private const val HIDE_DELAY_MS = 220L
    }

    private var card: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var iconView: ImageView? = null
    private var titleView: TextView? = null
    private var closeBtn: ImageView? = null
    private var thumbFrame: FrameLayout? = null
    private var thumbImg: ImageView? = null

    private var currentItem: TaskbarItem? = null
    private var activeBitmap: Bitmap? = null
    private var ownBitmap = false
    private var cardHovered = false
    private var pendingShow: Runnable? = null
    private var pendingHide: Runnable? = null

    fun build(ctx: Context) {
        detach()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadDp(10f, 8f, 10f, 10f)
            elevation = ctx.dpF(22f)
            visibility = View.GONE
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            setOnHoverListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                        cardHovered = true
                        cancelHide()
                    }
                    MotionEvent.ACTION_HOVER_EXIT -> {
                        cardHovered = false
                        scheduleHide()
                    }
                }
                false
            }
            setOnClickListener {
                val target = currentItem
                hideNow()
                if (target != null && target.taskId > 0) {
                    host.focusWindow(target.taskId, target.pkg)
                }
            }
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        iconView = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            .also { header.addView(it, ctx.llp(ctx.dp(15f), ctx.dp(15f), rightDp = 6f)) }

        titleView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
            paint.isFakeBoldText = true
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }.also { header.addView(it, ctx.llp(0, WRAP, 1f, rightDp = 6f)) }

        closeBtn = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            OverlayUiUtils.attachTactilePress(this, 0.86f)
            setOnClickListener {
                val target = currentItem
                hideNow()
                if (target != null && target.taskId > 0) {
                    host.closeWindow(target.taskId, target.pkg)
                }
            }
        }.also { header.addView(it, ctx.llp(ctx.dp(22f), ctx.dp(22f))) }
        root.addView(header, ctx.llp(MATCH, WRAP, bottomDp = 7f))

        thumbFrame = FrameLayout(ctx).apply {
            thumbImg = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }.also { addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
        }.also { root.addView(it, ctx.llp(ctx.dp(186f), ctx.dp(112f))) }

        val wmLp = OverlayWindowParams.floating(ctx.dp(206f), WRAP, host.winType, touchModal = false)
        card = root; lp = wmLp
        applyTheme(ctx, host.tokens)
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(SURFACE_KEY, OverlayLayer.POPOVER, root, wmLp))
    }

    fun applyTheme(ctx: Context, tokens: GlassOverlayTokens) {
        card?.let { tokens.applyGlassBackground(it, tokens.panelFill, cornerRadiusDp = 22f) }
        thumbFrame?.let { tokens.applyGlassBackground(it, tokens.cardInnerFill, cornerRadiusDp = 14f, strokeWidthDp = 0.7f, clipOutline = true) }
        titleView?.setTextColor(tokens.textPrimary)
        closeBtn?.setImageDrawable(HyperUi.icon(ctx, HyperUi.ICON_CLOSE, tokens.textSecondary, 11f))
        closeBtn?.background = OverlayUiUtils.makeStateListBg(ctx, Color.TRANSPARENT, tokens.dangerHoverFill, 9f)
    }

    fun scheduleShow(anchorView: View, item: TaskbarItem, label: String) {
        cancelShow()
        cancelHide()
        if (!item.running || item.taskId <= 0 || host.popovers.isAnyOpen()) return
        val task = Runnable {
            val ctx = host.extCtx ?: return@Runnable
            val root = card ?: return@Runnable
            val wmLp = lp ?: return@Runnable
            if (host.popovers.isAnyOpen()) return@Runnable
            currentItem = item
            applyTheme(ctx, host.tokens)
            val pm = host.serviceContext.packageManager
            iconView?.setImageDrawable(host.getCachedIcon(pm, item.pkg))
            titleView?.text = label

            host.safeBgExecute {
                val snap = BridgeClient.captureTaskSnapshot(item.taskId, item.pkg, true)
                val bmp = snap.bitmap
                host.safePost {
                    if (currentItem?.taskId != item.taskId || !anchorView.isAttachedToWindow || host.popovers.isAnyOpen()) {
                        if (snap.ownBitmap && bmp != null && !bmp.isRecycled) bmp.recycle()
                        return@safePost
                    }
                    recycleBitmap()
                    if (bmp != null && !bmp.isRecycled) {
                        activeBitmap = bmp
                        ownBitmap = snap.ownBitmap
                        thumbImg?.setImageBitmap(bmp)
                    } else {
                        thumbImg?.setImageDrawable(host.getCachedIcon(pm, item.pkg))
                    }
                    val loc = IntArray(2)
                    anchorView.getLocationOnScreen(loc)
                    val cardW = ctx.dp(206f)
                    val cardH = ctx.dp(154f)
                    val margin = ctx.dp(12f)
                    val x = (loc[0] + anchorView.width / 2 - cardW / 2)
                        .coerceIn(margin, max(margin, host.state.displayW - cardW - margin))
                    val y = max(margin, host.state.displayH - ctx.dp(74f) - cardH)
                    root.visibility = View.VISIBLE
                    wmLp.x = x; wmLp.y = y
                    host.surfaceHost.updateWindowLayout(root, wmLp, host.extWm)
                    root.animatePopover(show = true, shiftYDp = 8f, startScale = 0.94f, enterMs = 135L) {
                        host.bringCursorToFront()
                    }
                }
            }
        }
        pendingShow = task
        host.handler.postDelayed(task, SHOW_DELAY_MS)
    }

    fun scheduleHide() {
        cancelShow()
        cancelHide()
        val task = Runnable { if (!cardHovered) hideNow() }
        pendingHide = task
        host.handler.postDelayed(task, HIDE_DELAY_MS)
    }

    fun hideNow() {
        cancelShow()
        cancelHide()
        cardHovered = false
        currentItem = null
        card?.let { it.animate().cancel(); it.visibility = View.GONE }
        recycleBitmap()
    }

    private fun cancelShow() { pendingShow?.let { host.handler.removeCallbacks(it) }; pendingShow = null }
    private fun cancelHide() { pendingHide?.let { host.handler.removeCallbacks(it) }; pendingHide = null }

    private fun recycleBitmap() {
        thumbImg?.setImageDrawable(null)
        val prev = activeBitmap
        activeBitmap = null
        if (ownBitmap && prev != null && !prev.isRecycled) runCatching { prev.recycle() }
        ownBitmap = false
    }

    fun detach() {
        hideNow()
        host.surfaceHost.unregister(SURFACE_KEY, host.extWm)
        card = null; lp = null; iconView = null; titleView = null; closeBtn = null; thumbFrame = null; thumbImg = null
    }
}

package io.github.lengsukq.opaldesk.desktop.workbench

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.DecelerateInterpolator
import io.github.lengsukq.opaldesk.desktop.model.DesktopGeometry
import io.github.lengsukq.opaldesk.desktop.model.IntRect
import io.github.lengsukq.opaldesk.desktop.state.DesktopShellHost
import io.github.lengsukq.opaldesk.overlay.dpF

internal class WorkbenchDragPreview(ctx: Context, private val host: DesktopShellHost) : View(ctx) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val targetRect = RectF()
    private val targetStart = RectF()
    private val ghostRect = RectF()
    private val badgeRect = RectF()
    private val radius = ctx.dpF(16f)
    private val ghostOffset = ctx.dpF(16f)
    private val ghostWidth = ctx.dpF(156f)
    private val ghostHeight = ctx.dpF(44f)
    private val iconSize = ctx.dpF(22f).toInt()
    private val badgeHeight = ctx.dpF(34f)
    private val badgePadding = ctx.dpF(14f)
    private val strokeWidth = ctx.dpF(1.5f)
    private val textSizePx = ctx.dpF(13f)
    private var icon: Drawable? = null
    private var cardLabel: CharSequence? = null
    private var target: WorkbenchDropLayout? = null
    private var companionRect: IntRect? = null
    private var targetAlpha = 0f
    private val settle = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 160L
        interpolator = DecelerateInterpolator()
    }

    fun start(label: String, appIcon: Drawable?) {
        clear()
        icon = appIcon?.constantState?.newDrawable(resources)?.mutate()
        paint.textSize = textSizePx
        cardLabel = TextUtils.ellipsize(label, paint, ghostWidth - iconSize - badgePadding * 3, TextUtils.TruncateAt.END)
        visibility = VISIBLE
    }

    fun finish() {
        animate().alpha(0f).setDuration(135L).withEndAction { clear() }.start()
    }

    fun move(x: Float, y: Float, layout: WorkbenchDropLayout?) {
        val left = (x - ghostWidth / 2f).coerceIn(0f, (width - ghostWidth).coerceAtLeast(0f))
        val top = (y - ghostHeight - ghostOffset).coerceAtLeast(0f)
        ghostRect.set(left, top, left + ghostWidth, top + ghostHeight)
        if (target != layout) updateTarget(layout)
        postInvalidateOnAnimation()
    }

    private fun updateTarget(layout: WorkbenchDropLayout?) {
        settle.cancel()
        settle.removeAllUpdateListeners()
        if (layout != null) {
            val dpi = host.state.dpi
            val end = DesktopGeometry.computeOuterFrame(layout.bounds, dpi)
            companionRect = layout.companionBounds?.let { DesktopGeometry.computeOuterFrame(it, dpi) }
            if (target == null) targetRect.set(end.left.toFloat(), end.top.toFloat(), end.right.toFloat(), end.bottom.toFloat())
            targetStart.set(targetRect)
            val startAlpha = targetAlpha
            settle.addUpdateListener { animator ->
                val t = animator.animatedFraction
                targetRect.set(
                    targetStart.left + (end.left - targetStart.left) * t,
                    targetStart.top + (end.top - targetStart.top) * t,
                    targetStart.right + (end.right - targetStart.right) * t,
                    targetStart.bottom + (end.bottom - targetStart.bottom) * t,
                )
                targetAlpha = startAlpha + (1f - startAlpha) * t
                invalidate()
            }
            settle.start()
        } else {
            companionRect = null
            targetAlpha = 0f
        }
        target = layout
    }

    fun clear() {
        animate().withEndAction(null).cancel()
        settle.cancel()
        settle.removeAllUpdateListeners()
        icon = null
        cardLabel = null
        target = null
        companionRect = null
        targetAlpha = 0f
        alpha = 1f
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        val tokens = host.tokens
        val layout = target
        if (layout != null) {
            companionRect?.let {
                paint.color = tokens.accentBlue
                paint.alpha = (85 * targetAlpha).toInt()
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = strokeWidth
                canvas.drawRoundRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat(), radius, radius, paint)
            }
            paint.color = tokens.accentBlue
            paint.alpha = (47 * targetAlpha).toInt()
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(targetRect, radius, radius, paint)
            paint.alpha = (235 * targetAlpha).toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            canvas.drawRoundRect(targetRect, radius, radius, paint)
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = textSizePx
            val badgeWidth = paint.measureText(layout.label) + 2 * badgePadding
            val cx = targetRect.centerX()
            val top = targetRect.top + badgePadding
            badgeRect.set(cx - badgeWidth / 2, top, cx + badgeWidth / 2, top + badgeHeight)
            paint.color = tokens.hudFill
            paint.alpha = (247 * targetAlpha).toInt()
            canvas.drawRoundRect(badgeRect, badgeHeight / 2, badgeHeight / 2, paint)
            paint.color = tokens.textPrimary
            paint.alpha = (255 * targetAlpha).toInt()
            canvas.drawText(layout.label, cx, badgeRect.centerY() - (paint.ascent() + paint.descent()) / 2, paint)
        }
        cardLabel?.let { label ->
            paint.style = Paint.Style.FILL
            paint.color = tokens.hudFill
            canvas.drawRoundRect(ghostRect, radius, radius, paint)
            paint.color = tokens.accentBlue
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            canvas.drawRoundRect(ghostRect, radius, radius, paint)
            val x = (ghostRect.left + badgePadding).toInt()
            val y = (ghostRect.centerY() - iconSize / 2f).toInt()
            icon?.let { appIcon ->
                appIcon.setBounds(x, y, x + iconSize, y + iconSize)
                appIcon.draw(canvas)
            }
            paint.style = Paint.Style.FILL
            paint.color = tokens.textPrimary
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = textSizePx
            canvas.drawText(label, 0, label.length, x + iconSize + badgePadding, ghostRect.centerY() - (paint.ascent() + paint.descent()) / 2, paint)
        }
    }
}

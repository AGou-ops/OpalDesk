package io.github.lengsukq.opaldesk.design.iosglass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hardware-accelerated iOS Liquid Glass [Drawable] for Android [View]-based overlays
 * (Menu Bar, Dock, Control Center, Window Chrome, Mirror StandBy Cards).
 *
 * Renders:
 * 1. Continuous-curvature G2 superellipse ([SquircleMath]) body fill with automatic opacity boost
 *    when [CrossWindowBlurController.isBlurEnabled] is false;
 * 2. Top-edge inner luminance sheen (`rgba(255,255,255,0.34)` -> `0`);
 * 3. 45° diagonal refractive specular rim stroke (`top-left highlight` -> `mid soft` -> `bottom-right caustic`).
 */
class LiquidGlassDrawable(
    baseFillColor: Int,
    cornerRadiusPx: Float,
    strokeWidthPx: Float = 1.5f,
    darkMode: Boolean = false,
    smoothing: Float = SquircleMath.DEFAULT_SMOOTHING,
    private val adaptiveBlurFallback: Boolean = true,
) : Drawable() {

    var baseFillColor: Int = baseFillColor
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    var cornerRadii: SquircleMath.CornerRadiiPx = SquircleMath.CornerRadiiPx.uniform(cornerRadiusPx)
        set(value) {
            if (field != value) {
                field = value
                rebuildPath(bounds)
                invalidateSelf()
            }
        }

    var strokeWidthPx: Float = strokeWidthPx
        set(value) {
            if (field != value) {
                field = value
                strokePaint.strokeWidth = value
                rebuildPath(bounds)
                invalidateSelf()
            }
        }

    var darkMode: Boolean = darkMode
        set(value) {
            if (field != value) {
                field = value
                rebuildShaders(bounds)
                invalidateSelf()
            }
        }

    var smoothing: Float = smoothing
    var hasBoundedBackdropBlur: Boolean = false

    private val fillPath = Path()
    private val strokePath = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidthPx
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuildPath(bounds)
        rebuildShaders(bounds)
    }

    private fun rebuildPath(b: Rect) {
        if (b.isEmpty) {
            fillPath.reset()
            strokePath.reset()
            return
        }
        SquircleMath.buildAndroidPath(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            cornerRadii, smoothing, fillPath,
        )
        val inset = max(0.5f, strokeWidthPx * 0.5f)
        val insetRadii = SquircleMath.CornerRadiiPx(
            max(0f, cornerRadii.topLeft - inset),
            max(0f, cornerRadii.topRight - inset),
            max(0f, cornerRadii.bottomRight - inset),
            max(0f, cornerRadii.bottomLeft - inset),
        )
        SquircleMath.buildAndroidPath(
            b.left + inset, b.top + inset, b.right - inset, b.bottom - inset,
            insetRadii, smoothing, strokePath,
        )
    }

    private fun rebuildShaders(b: Rect) {
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val sheenTop = if (darkMode) 0x22FFFFFF else 0x52FFFFFF
        val sheenMid = if (darkMode) 0x06FFFFFF else 0x10FFFFFF
        sheenPaint.shader = LinearGradient(
            b.left.toFloat(), b.top.toFloat(),
            b.left.toFloat(), b.top + h * 0.62f,
            intArrayOf(sheenTop, sheenMid, Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        val specHi = if (darkMode) 0x58FFFFFF else 0xD8FFFFFF.toInt()
        val specMid = if (darkMode) 0x18FFFFFF else 0x42FFFFFF
        val specCaustic = if (darkMode) 0x34FFFFFF else 0x88FFFFFF.toInt()
        strokePaint.shader = LinearGradient(
            b.left.toFloat(), b.top.toFloat(),
            b.left + w, b.top + h,
            intArrayOf(specHi, specMid, specCaustic),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val baseA = Color.alpha(baseFillColor)
        val blurActiveOnView = hasBoundedBackdropBlur && CrossWindowBlurController.isBlurEnabled
        val effAlpha = if (adaptiveBlurFallback && !blurActiveOnView && baseA in 64..236) {
            min(246, (baseA + (255 - baseA) * 0.42f).roundToInt())
        } else {
            baseA
        }
        fillPaint.color = Color.argb(
            effAlpha,
            Color.red(baseFillColor),
            Color.green(baseFillColor),
            Color.blue(baseFillColor),
        )
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(fillPath, sheenPaint)
        if (strokeWidthPx > 0f) {
            canvas.drawPath(strokePath, strokePaint)
        }
    }

    override fun getOutline(outline: Outline) {
        if (bounds.isEmpty) return
        val r = min(
            min(bounds.width(), bounds.height()) * 0.5f,
            max(max(cornerRadii.topLeft, cornerRadii.topRight), max(cornerRadii.bottomLeft, cornerRadii.bottomRight)),
        )
        outline.setRoundRect(bounds, r)
        outline.alpha = (Color.alpha(baseFillColor) / 255f).coerceIn(0.18f, 0.92f)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        sheenPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        sheenPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        fun applyToView(
            view: View,
            fillColor: Int,
            cornerRadiusPx: Float,
            strokeWidthPx: Float = 1.4f,
            darkMode: Boolean = false,
            clipOutline: Boolean = false,
        ): LiquidGlassDrawable {
            val d = LiquidGlassDrawable(
                baseFillColor = fillColor,
                cornerRadiusPx = cornerRadiusPx,
                strokeWidthPx = strokeWidthPx,
                darkMode = darkMode,
            )
            view.background = d
            view.outlineProvider = ViewOutlineProvider.BACKGROUND
            view.clipToOutline = clipOutline
            d.hasBoundedBackdropBlur = CrossWindowBlurController.tryApplyViewBackdropBlur(view, cornerRadiusPx * 1.15f)
            return d
        }
    }
}

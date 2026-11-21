package com.extbridge.helper.design.iosglass

import android.graphics.Path as AndroidPath
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Pure G2 continuous-curvature superellipse (iOS / iPadOS / macOS Squircle) geometry math
 * shared across Jetpack Compose [SquircleShape] and Android View [LiquidGlassDrawable].
 */
object SquircleMath {
    const val DEFAULT_SMOOTHING: Float = 0.62f

    data class CornerRadiiPx(
        val topLeft: Float,
        val topRight: Float,
        val bottomRight: Float,
        val bottomLeft: Float,
    ) {
        companion object {
            fun uniform(r: Float) = CornerRadiiPx(r, r, r, r)
        }
    }

    fun clampRadii(width: Float, height: Float, raw: CornerRadiiPx): CornerRadiiPx {
        if (width <= 0f || height <= 0f) return CornerRadiiPx.uniform(0f)
        val halfMin = min(width, height) * 0.5f
        return CornerRadiiPx(
            topLeft = raw.topLeft.coerceIn(0f, halfMin),
            topRight = raw.topRight.coerceIn(0f, halfMin),
            bottomRight = raw.bottomRight.coerceIn(0f, halfMin),
            bottomLeft = raw.bottomLeft.coerceIn(0f, halfMin),
        )
    }

    /**
     * Populates a Compose [Path] with a G2 continuous-curvature squircle inside `[0, 0, width, height]`.
     */
    fun buildComposePath(
        width: Float,
        height: Float,
        radii: CornerRadiiPx,
        smoothing: Float = DEFAULT_SMOOTHING,
        target: Path = Path(),
    ): Path {
        target.reset()
        if (width <= 0f || height <= 0f) return target
        emitSegments(
            left = 0f,
            top = 0f,
            right = width,
            bottom = height,
            radii = clampRadii(width, height, radii),
            smoothing = smoothing.coerceIn(0f, 0.9f),
            moveTo = target::moveTo,
            lineTo = target::lineTo,
            cubicTo = target::cubicTo,
            close = target::close,
        )
        return target
    }

    /**
     * Populates an Android framework [AndroidPath] inside `[left, top, right, bottom]`.
     */
    fun buildAndroidPath(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radii: CornerRadiiPx,
        smoothing: Float = DEFAULT_SMOOTHING,
        target: AndroidPath = AndroidPath(),
    ): AndroidPath {
        target.reset()
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return target
        emitSegments(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            radii = clampRadii(w, h, radii),
            smoothing = smoothing.coerceIn(0f, 0.9f),
            moveTo = target::moveTo,
            lineTo = target::lineTo,
            cubicTo = target::cubicTo,
            close = target::close,
        )
        return target
    }

    private fun shoulder(r: Float, smoothing: Float, maxHalf: Float): Float =
        if (r <= 0.5f) 0f else min(maxHalf, r * (1f + smoothing * 0.56f))

    private inline fun emitSegments(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radii: CornerRadiiPx,
        smoothing: Float,
        moveTo: (Float, Float) -> Unit,
        lineTo: (Float, Float) -> Unit,
        cubicTo: (Float, Float, Float, Float, Float, Float) -> Unit,
        close: () -> Unit,
    ) {
        val w = right - left
        val h = bottom - top
        val maxHalfW = w * 0.5f
        val maxHalfH = h * 0.5f

        val pTlX = shoulder(radii.topLeft, smoothing, maxHalfW)
        val pTlY = shoulder(radii.topLeft, smoothing, maxHalfH)
        val pTrX = shoulder(radii.topRight, smoothing, maxHalfW)
        val pTrY = shoulder(radii.topRight, smoothing, maxHalfH)
        val pBrX = shoulder(radii.bottomRight, smoothing, maxHalfW)
        val pBrY = shoulder(radii.bottomRight, smoothing, maxHalfH)
        val pBlX = shoulder(radii.bottomLeft, smoothing, maxHalfW)
        val pBlY = shoulder(radii.bottomLeft, smoothing, maxHalfH)

        // Control-point pull factor blending circular kappa (0.55228) with superellipse G2 shoulder
        val kEdge = 0.63f + smoothing * 0.16f
        val kDiag = 0.12f + smoothing * 0.10f

        moveTo(left + pTlX, top)
        // Top edge -> Top-Right corner
        lineTo(right - pTrX, top)
        if (radii.topRight > 0.5f) {
            cubicTo(
                right - pTrX * (1f - kEdge), top,
                right, top + pTrY * kDiag,
                right, top + pTrY,
            )
        }
        // Right edge -> Bottom-Right corner
        lineTo(right, bottom - pBrY)
        if (radii.bottomRight > 0.5f) {
            cubicTo(
                right, bottom - pBrY * (1f - kEdge),
                right - pBrX * kDiag, bottom,
                right - pBrX, bottom,
            )
        }
        // Bottom edge -> Bottom-Left corner
        lineTo(left + pBlX, bottom)
        if (radii.bottomLeft > 0.5f) {
            cubicTo(
                left + pBlX * (1f - kEdge), bottom,
                left, bottom - pBlY * kDiag,
                left, bottom - pBlY,
            )
        }
        // Left edge -> Top-Left corner
        lineTo(left, top + pTlY)
        if (radii.topLeft > 0.5f) {
            cubicTo(
                left, top + pTlY * (1f - kEdge),
                left + pTlX * kDiag, top,
                left + pTlX, top,
            )
        }
        close()
    }
}

/**
 * Jetpack Compose continuous-curvature iOS Squircle [Shape].
 */
class SquircleShape(
    private val topStart: Dp,
    private val topEnd: Dp,
    private val bottomEnd: Dp,
    private val bottomStart: Dp,
    private val smoothing: Float = SquircleMath.DEFAULT_SMOOTHING,
) : Shape {
    constructor(radius: Dp, smoothing: Float = SquircleMath.DEFAULT_SMOOTHING) :
        this(radius, radius, radius, radius, smoothing)

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        if (size.width <= 0f || size.height <= 0f) return Outline.Rectangle(Rect.Zero)
        val isRtl = layoutDirection == LayoutDirection.Rtl
        val tl = with(density) { (if (isRtl) topEnd else topStart).toPx() }
        val tr = with(density) { (if (isRtl) topStart else topEnd).toPx() }
        val br = with(density) { (if (isRtl) bottomStart else bottomEnd).toPx() }
        val bl = with(density) { (if (isRtl) bottomEnd else bottomStart).toPx() }
        val radii = SquircleMath.CornerRadiiPx(tl, tr, br, bl)
        if (tl <= 0.5f && tr <= 0.5f && br <= 0.5f && bl <= 0.5f) {
            return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }
        return Outline.Generic(SquircleMath.buildComposePath(size.width, size.height, radii, smoothing))
    }
}

object LiquidShapes {
    val Dock = SquircleShape(28.dp)
    val Sheet = SquircleShape(32.dp)
    val Card = SquircleShape(24.dp)
    val Window = SquircleShape(20.dp)
    val Tile = SquircleShape(18.dp)
    val Control = SquircleShape(14.dp)
    val Capsule = SquircleShape(999.dp, smoothing = 0f)
}

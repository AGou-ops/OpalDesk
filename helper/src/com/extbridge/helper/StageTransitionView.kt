package com.extbridge.helper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import kotlin.math.*

/**
 * Hardware-accelerated compositor overlay on the external VirtualDisplay rendering 60–120fps
 * HyperOS-style window animations (Dock minimize/restore, split enter/morph/swap/exit).
 */
class StageTransitionView(context: Context) : View(context) {

    companion object {
        private const val TAG = "ExtStageTransition"

        const val MODE_NORMAL = 0
        const val MODE_MINIMIZE_TO_ICON = 1
        const val MODE_RESTORE_FROM_ICON = 2
        const val MODE_SWAP_OVER = 3
        const val MODE_SWAP_UNDER = 4
        const val MODE_SPLIT_RECEDE = 5

        private const val BACKEND_HOLD_TIMEOUT_MS = 240L
        private const val DISSOLVE_DURATION_MS = 85L

        private val INTERP_MINIMIZE: Interpolator = PathInterpolator(0.30f, 0.0f, 0.12f, 1.0f)
        private val INTERP_SPRING: Interpolator = PathInterpolator(0.18f, 0.96f, 0.26f, 1.0f)
        private val INTERP_SWAP: Interpolator = PathInterpolator(0.24f, 0.94f, 0.30f, 1.0f)
        private val INTERP_DISSOLVE: Interpolator = PathInterpolator(0.33f, 0.0f, 0.67f, 1.0f)

        private fun releaseCardResources(card: CardSpec?) {
            val bmp = card?.snapshot ?: return
            card.snapshot = null
            if (card.ownSnapshot && !bmp.isRecycled) {
                try { bmp.recycle() } catch (t: Throwable) { Log.d(TAG, "Recycle snapshot failed: ${t.message}") }
            }
        }

        private fun configureCenterCropMatrix(bw: Int, bh: Int, dst: RectF, out: Matrix) {
            val dw = dst.width()
            val dh = dst.height()
            val scale: Float
            var dx = dst.left
            if (bw * dh > dw * bh) {
                scale = dh / bh.toFloat()
                dx += (dw - bw * scale) * 0.5f
            } else {
                scale = dw / bw.toFloat()
            }
            out.reset()
            out.setScale(scale, scale)
            out.postTranslate(dx, dst.top)
        }

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
            if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }

    class CardSpec {
        var snapshot: Bitmap? = null
        var ownSnapshot: Boolean = false
        var icon: Drawable? = null
        var label: String? = null
        val startRect = RectF()
        val endRect = RectF()
        var startCornerPx: Float = 0f
        var endCornerPx: Float = 0f
        var startAlpha: Float = 1f
        var endAlpha: Float = 1f
        var mode: Int = MODE_NORMAL

        fun setRects(s: RectF?, e: RectF?): CardSpec = apply {
            if (s != null) startRect.set(s)
            if (e != null) endRect.set(e)
        }

        fun setRects(s: Rect?, e: Rect?): CardSpec = apply {
            if (s != null) startRect.set(s)
            if (e != null) endRect.set(e)
        }
    }

    private fun aaPaint(fill: Boolean = true, init: Paint.() -> Unit = {}) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
        init()
    }

    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = aaPaint(fill = false) { strokeWidth = dp(1.2f) }
    private val shadowPaint = aaPaint()
    private val iconPlatePaint = aaPaint()
    private val seamPaint = aaPaint()
    private val scrimPaint = aaPaint()
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF1F5F9.toInt()
        textSize = dp(13.5f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    // Pre-allocated unit vertical gradient transformed per card via Matrix to avoid per-frame allocations
    private val placeholderGradient = LinearGradient(
        0f, 0f, 0f, 1f, 0xFF182234.toInt(), 0xFF0E1420.toInt(), Shader.TileMode.CLAMP,
    )
    private val gradientMatrix = Matrix()
    private val clipPath = Path()
    private val curRect = RectF()
    private val shadowRect = RectF()
    private val iconPlateRect = RectF()
    private val bmpMatrix = Matrix()

    private var card0: CardSpec? = null
    private var card1: CardSpec? = null
    private var drawSplitSeam = false
    private var splitPortrait = false
    private var scrimMaxAlpha = 0f

    private val uiHandler = Handler(Looper.getMainLooper())
    private var mainAnimator: ValueAnimator? = null
    private var dissolveAnimator: ValueAnimator? = null
    private var animSeq = 0
    private var progress = 0f
    private var masterAlpha = 1f
    private var animating = false
    private var waitBackendBeforeDissolve = false
    private var backendReady = true
    private var motionDone = false
    private var bounceFired = false
    private var backendWaitStartMs = 0L
    private var onBounce: Runnable? = null
    private var onFinished: Runnable? = null

    private val holdWatchdog = object : Runnable {
        override fun run() {
            if (!animating || !motionDone) return
            if (backendReady || (SystemClock.uptimeMillis() - backendWaitStartMs) >= BACKEND_HOLD_TIMEOUT_MS) {
                startDissolveOut()
            } else {
                uiHandler.postDelayed(this, 16L)
            }
        }
    }

    init {
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
        visibility = GONE
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    fun isAnimating(): Boolean = animating
    fun getActiveToken(): Int = animSeq

    override fun onDetachedFromWindow() {
        cancelActiveAnimation()
        super.onDetachedFromWindow()
    }

    fun cancelActiveAnimation() {
        animSeq++
        mainAnimator?.apply { removeAllListeners(); cancel() }
        dissolveAnimator?.apply { removeAllListeners(); cancel() }
        finishAndCleanup()
    }

    fun notifyBackendReady() = notifyBackendReady(animSeq)

    fun notifyBackendReady(expectedToken: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) notifyBackendReadyOnUi(expectedToken)
        else uiHandler.post { notifyBackendReadyOnUi(expectedToken) }
    }

    private fun notifyBackendReadyOnUi(expectedToken: Int) {
        if (!animating || expectedToken != animSeq) return
        backendReady = true
        if (motionDone && waitBackendBeforeDissolve && dissolveAnimator == null) {
            uiHandler.removeCallbacks(holdWatchdog)
            uiHandler.postDelayed({ startDissolveOut() }, 20L)
        }
    }

    fun playMinimizeToDock(card: CardSpec?, onLandBounce: Runnable?, onFinished: Runnable?) {
        if (card == null) { onFinished?.run(); return }
        card.mode = MODE_MINIMIZE_TO_ICON
        card.startCornerPx = dp(2f)
        card.endCornerPx = dp(9f)
        startChoreography(card, null, 255L, INTERP_MINIMIZE, false, false, false, 0.10f, onLandBounce, onFinished)
    }

    fun playRestoreFromDock(card: CardSpec?, targetIsSplit: Boolean, onFinished: Runnable?) {
        if (card == null) { onFinished?.run(); return }
        card.mode = MODE_RESTORE_FROM_ICON
        card.startCornerPx = dp(10f)
        card.endCornerPx = if (targetIsSplit) dp(10f) else 0f
        startChoreography(card, null, 265L, INTERP_SPRING, true, false, false, 0.14f, null, onFinished)
    }

    fun playSplitTransition(
        card0: CardSpec?,
        card1: CardSpec?,
        portrait: Boolean,
        durationMs: Long,
        swapMode: Boolean,
        onFinished: Runnable?,
    ) {
        val interp = if (swapMode) INTERP_SWAP else INTERP_SPRING
        startChoreography(card0, card1, durationMs, interp, true, true, portrait, 0.18f, null, onFinished)
    }

    fun playSplitExit(expandingCard: CardSpec?, recedingCard: CardSpec?, onFinished: Runnable?) {
        expandingCard?.apply { mode = MODE_NORMAL; startCornerPx = dp(12f); endCornerPx = 0f }
        recedingCard?.apply { mode = MODE_SPLIT_RECEDE; startCornerPx = dp(12f); endCornerPx = dp(18f) }
        startChoreography(expandingCard, recedingCard, 250L, INTERP_SPRING, true, false, false, 0.12f, null, onFinished)
    }

    private fun startChoreography(
        c0: CardSpec?,
        c1: CardSpec?,
        durationMs: Long,
        interp: Interpolator,
        waitBackend: Boolean,
        splitSeam: Boolean,
        portrait: Boolean,
        scrimAlpha: Float,
        onBounceCb: Runnable?,
        onFinishedCb: Runnable?,
    ) {
        cancelActiveAnimation()
        val curToken = ++animSeq
        card0 = c0
        card1 = c1
        drawSplitSeam = splitSeam
        splitPortrait = portrait
        scrimMaxAlpha = scrimAlpha
        waitBackendBeforeDissolve = waitBackend
        backendReady = !waitBackend
        motionDone = false
        bounceFired = false
        progress = 0f
        masterAlpha = 1f
        onBounce = onBounceCb
        onFinished = onFinishedCb
        animating = true

        visibility = VISIBLE
        invalidate()

        mainAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = interp
            addUpdateListener { anim ->
                progress = anim.animatedValue as Float
                if (!bounceFired && progress >= 0.84f) fireBounceOnce()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!animating || mainAnimator !== animation) return
                    progress = 1f
                    motionDone = true
                    fireBounceOnce()
                    when {
                        !waitBackendBeforeDissolve -> finishAndCleanup()
                        backendReady -> uiHandler.postDelayed({ if (animating && animSeq == curToken) startDissolveOut() }, 18L)
                        else -> {
                            backendWaitStartMs = SystemClock.uptimeMillis()
                            uiHandler.postDelayed(holdWatchdog, 16L)
                        }
                    }
                }
            })
            start()
        }
    }

    private fun fireBounceOnce() {
        val cb = onBounce ?: return
        bounceFired = true
        onBounce = null
        cb.run()
    }

    private fun startDissolveOut() {
        if (!animating || dissolveAnimator != null) return
        dissolveAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = DISSOLVE_DURATION_MS
            interpolator = INTERP_DISSOLVE
            addUpdateListener { anim ->
                masterAlpha = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (dissolveAnimator === animation) finishAndCleanup()
                }
            })
            start()
        }
    }

    private fun finishAndCleanup() {
        uiHandler.removeCallbacksAndMessages(null)
        mainAnimator = null
        dissolveAnimator = null
        releaseCardResources(card0)
        releaseCardResources(card1)
        card0 = null
        card1 = null
        onBounce = null
        val finishCb = onFinished
        onFinished = null
        animating = false
        visibility = GONE
        finishCb?.run()
    }

    override fun onDraw(canvas: Canvas) {
        if (!animating) return
        val p = progress.coerceIn(0f, 1f)
        val master = masterAlpha.coerceIn(0f, 1f)
        if (master <= 0.005f) return

        val c0 = card0
        val c1 = card1
        drawBackdropScrim(canvas, c0, p, master)
        if (c1 != null && c0 != null && c0.mode == MODE_SWAP_OVER) {
            drawSingleCard(canvas, c1, p, master)
            drawSingleCard(canvas, c0, p, master)
        } else {
            c0?.let { drawSingleCard(canvas, it, p, master) }
            c1?.let { drawSingleCard(canvas, it, p, master) }
        }
        if (drawSplitSeam && c0 != null && c1 != null) drawSeamHighlight(canvas, c0, c1, p, master)
    }

    private fun drawBackdropScrim(canvas: Canvas, c0: CardSpec?, p: Float, master: Float) {
        if (scrimMaxAlpha <= 0f) return
        val bell = if (c0?.mode == MODE_MINIMIZE_TO_ICON) (1f - p) * scrimMaxAlpha
        else sin(min(1f, p * 1.15f) * Math.PI * 0.5).toFloat() * scrimMaxAlpha * master
        val sa = (bell.coerceIn(0f, 1f) * 255f).roundToInt()
        if (sa > 2) {
            scrimPaint.color = (sa shl 24) or 0x050811
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        }
    }

    private fun drawSingleCard(canvas: Canvas, card: CardSpec, p: Float, master: Float) {
        if (!computeCardRect(card, p)) return
        val cardAlpha = computeCardAlpha(card, p, master)
        if (cardAlpha <= 0.01f) return
        val corner = computeCardCorner(card, p)

        drawCardShadow(canvas, card, p, cardAlpha, corner)

        val save = canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(curRect, corner, corner, Path.Direction.CW)
        canvas.clipPath(clipPath)

        val iconMorph = computeIconMorph(card, p)
        drawCardBody(canvas, card, cardAlpha, iconMorph)
        card.icon?.takeIf { iconMorph > 0.01f }?.let { drawIconMorphOverlay(canvas, it, cardAlpha, iconMorph) }

        canvas.restoreToCount(save)
        drawCardBorder(canvas, card, p, cardAlpha, corner)
    }

    private fun computeCardRect(card: CardSpec, p: Float): Boolean {
        val px = when (card.mode) {
            MODE_MINIMIZE_TO_ICON -> p.toDouble().pow(0.84).toFloat()
            MODE_RESTORE_FROM_ICON -> 1f - (1f - p).toDouble().pow(1.15).toFloat()
            else -> p
        }
        curRect.set(
            lerp(card.startRect.left, card.endRect.left, px),
            lerp(card.startRect.top, card.endRect.top, p),
            lerp(card.startRect.right, card.endRect.right, px),
            lerp(card.startRect.bottom, card.endRect.bottom, p),
        )
        val scaleAdjust = when (card.mode) {
            MODE_SWAP_OVER -> 1f - 0.045f * sin(p * Math.PI).toFloat()
            MODE_SWAP_UNDER -> 1f - 0.105f * sin(p * Math.PI).toFloat()
            else -> 1f
        }
        if (scaleAdjust != 1f) {
            val cx = curRect.centerX()
            val cy = curRect.centerY()
            val hw = curRect.width() * 0.5f * scaleAdjust
            val hh = curRect.height() * 0.5f * scaleAdjust
            curRect.set(cx - hw, cy - hh, cx + hw, cy + hh)
        }
        return curRect.width() > 1f && curRect.height() > 1f
    }

    private fun computeCardAlpha(card: CardSpec, p: Float, master: Float): Float {
        var alpha = lerp(card.startAlpha, card.endAlpha, p) * master
        when (card.mode) {
            MODE_MINIMIZE_TO_ICON -> if (p > 0.76f) alpha *= max(0f, 1f - (p - 0.76f) / 0.24f)
            MODE_RESTORE_FROM_ICON -> if (p < 0.18f) alpha *= (0.45f + 0.55f * (p / 0.18f))
            MODE_SPLIT_RECEDE -> alpha *= max(0f, 1f - p * 1.18f)
        }
        return alpha
    }

    private fun computeCardCorner(card: CardSpec, p: Float): Float {
        var corner = lerp(card.startCornerPx, card.endCornerPx, p)
        if (card.mode == MODE_SWAP_OVER || card.mode == MODE_SWAP_UNDER) {
            corner = max(corner, dp(14f) * sin(p * Math.PI).toFloat())
        }
        return min(corner, min(curRect.width(), curRect.height()) * 0.30f)
    }

    private fun computeIconMorph(card: CardSpec, p: Float): Float {
        if (card.icon == null) return 0f
        return when (card.mode) {
            MODE_MINIMIZE_TO_ICON -> smoothStep(0.36f, 0.80f, p)
            MODE_RESTORE_FROM_ICON -> 1f - smoothStep(0.10f, 0.48f, p)
            else -> 0f
        }
    }

    private fun drawCardShadow(canvas: Canvas, card: CardSpec, p: Float, cardAlpha: Float, corner: Float) {
        val strength = if (card.mode == MODE_SWAP_OVER) 0.36f * sin(p * Math.PI).toFloat() + 0.12f else 0.22f * cardAlpha
        val shAlpha = ((strength * cardAlpha).coerceIn(0f, 1f) * 255f).roundToInt()
        if (shAlpha <= 4 || corner <= dp(1f)) return
        val spread = dp(if (card.mode == MODE_SWAP_OVER) 10f else 5f)
        shadowRect.set(curRect.left - spread * 0.5f, curRect.top + dp(2f), curRect.right + spread * 0.5f, curRect.bottom + spread)
        shadowPaint.color = shAlpha shl 24
        canvas.drawRoundRect(shadowRect, corner + spread * 0.5f, corner + spread * 0.5f, shadowPaint)
    }

    private fun drawCardBody(canvas: Canvas, card: CardSpec, cardAlpha: Float, iconMorph: Float) {
        val alphaInt = (cardAlpha * 255f).roundToInt()
        val bmp = card.snapshot?.takeIf {
            !it.isRecycled && it.width > 0 && it.height > 0 &&
                (it.config != Bitmap.Config.HARDWARE || canvas.isHardwareAccelerated)
        }

        if (bmp != null) {
            cardBgPaint.shader = null
            cardBgPaint.color = (alphaInt shl 24) or 0x101622
            canvas.drawRect(curRect, cardBgPaint)
            val contentAlpha = cardAlpha * (1f - iconMorph * 0.88f)
            if (contentAlpha > 0.02f) {
                bmpPaint.alpha = (contentAlpha * 255f).roundToInt()
                configureCenterCropMatrix(bmp.width, bmp.height, curRect, bmpMatrix)
                canvas.drawBitmap(bmp, bmpMatrix, bmpPaint)
            }
            return
        }

        gradientMatrix.reset()
        gradientMatrix.setScale(1f, max(1f, curRect.height()))
        gradientMatrix.postTranslate(0f, curRect.top)
        placeholderGradient.setLocalMatrix(gradientMatrix)
        cardBgPaint.shader = placeholderGradient
        cardBgPaint.alpha = alphaInt
        canvas.drawRect(curRect, cardBgPaint)
        cardBgPaint.shader = null

        card.icon?.takeIf { iconMorph < 0.99f }?.let { icon ->
            val baseIconSize = min(dp(64f), min(curRect.width(), curRect.height()) * 0.42f)
            drawCenteredAppIconAndLabel(canvas, icon, card.label, curRect, baseIconSize, cardAlpha * (1f - iconMorph))
        }
    }

    private fun drawIconMorphOverlay(canvas: Canvas, icon: Drawable, cardAlpha: Float, iconMorph: Float) {
        val morphAlpha = (cardAlpha * iconMorph * 255f).roundToInt()
        iconPlatePaint.color = (morphAlpha shl 24) or 0x1E293B
        canvas.drawRect(curRect, iconPlatePaint)
        val pad = min(curRect.width(), curRect.height()) * 0.12f
        val il = (curRect.left + pad).roundToInt()
        val it = (curRect.top + pad).roundToInt()
        val ir = (curRect.right - pad).roundToInt()
        val ib = (curRect.bottom - pad).roundToInt()
        if (ir > il && ib > it) {
            icon.setBounds(il, it, ir, ib)
            icon.alpha = morphAlpha
            icon.draw(canvas)
            icon.alpha = 255
        }
    }

    private fun drawCardBorder(canvas: Canvas, card: CardSpec, p: Float, cardAlpha: Float, corner: Float) {
        val borderVis = if (card.endCornerPx <= dp(1f) && p > 0.8f) 1f - (p - 0.8f) / 0.2f else 1f
        val strokeAlpha = (cardAlpha * borderVis * 0.42f * 255f).roundToInt()
        if (strokeAlpha > 4) {
            strokePaint.color = (strokeAlpha shl 24) or 0x38BDF8
            canvas.drawRoundRect(curRect, corner, corner, strokePaint)
        }
    }

    private fun drawCenteredAppIconAndLabel(
        canvas: Canvas, icon: Drawable, label: String?, rect: RectF, iconSize: Float, alpha: Float,
    ) {
        if (alpha <= 0.02f || iconSize <= 4f) return
        val a = (alpha * 255f).roundToInt()
        val showLabel = !label.isNullOrEmpty() && rect.width() > dp(150f) && rect.height() > dp(130f)
        val cx = rect.centerX()
        val cy = if (showLabel) rect.centerY() - dp(12f) else rect.centerY()
        val half = iconSize * 0.5f
        val plateHalf = half + dp(8f)

        iconPlateRect.set(cx - plateHalf, cy - plateHalf, cx + plateHalf, cy + plateHalf)
        iconPlatePaint.color = ((a * 0.35f).roundToInt() shl 24) or 0xFFFFFF
        canvas.drawRoundRect(iconPlateRect, dp(16f), dp(16f), iconPlatePaint)

        icon.setBounds((cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt())
        icon.alpha = a
        icon.draw(canvas)
        icon.alpha = 255

        if (showLabel) {
            labelPaint.alpha = (a * 0.92f).roundToInt()
            val ellip = TextUtils.ellipsize(label, labelPaint, rect.width() - dp(32f), TextUtils.TruncateAt.END)
            canvas.drawText(ellip, 0, ellip.length, cx, cy + plateHalf + dp(20f), labelPaint)
        }
    }

    private fun drawSeamHighlight(canvas: Canvas, c0: CardSpec, c1: CardSpec, p: Float, master: Float) {
        val a = (sin(min(1f, p * 1.1f) * Math.PI * 0.5).toFloat() * master * 0.65f).coerceIn(0f, 1f).let { (it * 255f).roundToInt() }
        if (a <= 4) return
        seamPaint.color = (a shl 24) or 0x0D84FF
        val halfThick = dp(1.5f)
        if (!splitPortrait) {
            val seamX = (lerp(c0.startRect.right, c0.endRect.right, p) + lerp(c1.startRect.left, c1.endRect.left, p)) * 0.5f
            val padY = height * 0.18f
            canvas.drawRoundRect(seamX - halfThick, padY, seamX + halfThick, height - padY, halfThick, halfThick, seamPaint)
        } else {
            val seamY = (lerp(c0.startRect.bottom, c0.endRect.bottom, p) + lerp(c1.startRect.top, c1.endRect.top, p)) * 0.5f
            val padX = width * 0.18f
            canvas.drawRoundRect(padX, seamY - halfThick, width - padX, seamY + halfThick, halfThick, halfThick, seamPaint)
        }
    }
}

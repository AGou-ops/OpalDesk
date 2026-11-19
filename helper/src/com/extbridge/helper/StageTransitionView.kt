package com.extbridge.helper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Hardware-accelerated compositor overlay on the external VirtualDisplay that renders
 * 60-120fps HyperOS-style window animations:
 *  - Dock Minimize (live TaskSnapshot morphing into the squircle Dock app icon)
 *  - Dock Restore / App Switch (squircle Dock icon expanding into live window)
 *  - Split-Screen Enter / Slot Assign / Ratio Morph / Slot Crossover Swap / Exit to Fullscreen
 */
class StageTransitionView(context: Context) : View(context) {

    companion object {
        const val MODE_NORMAL = 0
        const val MODE_MINIMIZE_TO_ICON = 1
        const val MODE_RESTORE_FROM_ICON = 2
        const val MODE_SWAP_OVER = 3
        const val MODE_SWAP_UNDER = 4
        const val MODE_SPLIT_RECEDE = 5

        private val INTERP_MINIMIZE: Interpolator = PathInterpolator(0.30f, 0.0f, 0.12f, 1.0f)
        private val INTERP_SPRING: Interpolator = PathInterpolator(0.18f, 0.96f, 0.26f, 1.0f)
        private val INTERP_SWAP: Interpolator = PathInterpolator(0.24f, 0.94f, 0.30f, 1.0f)
        private val INTERP_DISSOLVE: Interpolator = PathInterpolator(0.33f, 0.0f, 0.67f, 1.0f)

        private fun releaseCardResources(c: CardSpec?) {
            if (c == null) return
            val b = c.snapshot
            c.snapshot = null
            if (b != null && c.ownSnapshot && !b.isRecycled) {
                try { b.recycle() } catch (_: Throwable) {}
            }
        }

        private fun configureCenterCropMatrix(bw: Int, bh: Int, dst: RectF, out: Matrix) {
            val dw = dst.width()
            val dh = dst.height()
            val scale: Float
            var dx = dst.left
            val dy = dst.top
            if (bw * dh > dw * bh) {
                scale = dh / bh.toFloat()
                dx += (dw - bw * scale) * 0.5f
            } else {
                scale = dw / bw.toFloat()
            }
            out.reset()
            out.setScale(scale, scale)
            out.postTranslate(dx, dy)
        }

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
            if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }

    class CardSpec {
        @JvmField var snapshot: Bitmap? = null
        @JvmField var ownSnapshot: Boolean = false
        @JvmField var icon: Drawable? = null
        @JvmField var label: String? = null
        @JvmField val startRect = RectF()
        @JvmField val endRect = RectF()
        @JvmField var startCornerPx: Float = 0f
        @JvmField var endCornerPx: Float = 0f
        @JvmField var startAlpha: Float = 1f
        @JvmField var endAlpha: Float = 1f
        @JvmField var mode: Int = MODE_NORMAL

        fun setRects(s: RectF?, e: RectF?): CardSpec {
            if (s != null) startRect.set(s)
            if (e != null) endRect.set(e)
            return this
        }

        fun setRects(s: Rect?, e: Rect?): CardSpec {
            if (s != null) startRect.set(s)
            if (e != null) endRect.set(e)
            return this
        }
    }

    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconPlatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF1F5F9.toInt()
        textSize = dp(13.5f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

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
            if (backendReady || (SystemClock.uptimeMillis() - backendWaitStartMs) >= 240L) {
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
        uiHandler.removeCallbacksAndMessages(null)
        mainAnimator?.let {
            it.removeAllListeners()
            it.cancel()
        }
        mainAnimator = null
        dissolveAnimator?.let {
            it.removeAllListeners()
            it.cancel()
        }
        dissolveAnimator = null
        releaseCardResources(card0)
        releaseCardResources(card1)
        card0 = null
        card1 = null
        onBounce = null
        val prevFinish = onFinished
        onFinished = null
        animating = false
        visibility = GONE
        prevFinish?.run()
    }

    fun notifyBackendReady() {
        notifyBackendReady(animSeq)
    }

    fun notifyBackendReady(expectedToken: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            uiHandler.post { notifyBackendReadyOnUi(expectedToken) }
        } else {
            notifyBackendReadyOnUi(expectedToken)
        }
    }

    private fun notifyBackendReadyOnUi(expectedToken: Int) {
        if (!animating || expectedToken != animSeq) return
        backendReady = true
        if (motionDone && waitBackendBeforeDissolve && dissolveAnimator == null) {
            uiHandler.removeCallbacks(holdWatchdog)
            uiHandler.postDelayed({ startDissolveOut() }, 20L)
        }
    }

    fun playMinimizeToDock(
        card: CardSpec?,
        onLandBounce: Runnable?,
        onFinished: Runnable?,
    ) {
        if (card == null) {
            onFinished?.run()
            return
        }
        card.mode = MODE_MINIMIZE_TO_ICON
        card.startCornerPx = dp(2f)
        card.endCornerPx = dp(9f)
        startChoreography(card, null, 255L, INTERP_MINIMIZE, false, false, false, 0.10f, onLandBounce, onFinished)
    }

    fun playRestoreFromDock(
        card: CardSpec?,
        targetIsSplit: Boolean,
        onFinished: Runnable?,
    ) {
        if (card == null) {
            onFinished?.run()
            return
        }
        card.mode = MODE_RESTORE_FROM_ICON
        card.startCornerPx = dp(10f)
        card.endCornerPx = if (targetIsSplit) dp(10f) else dp(0f)
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

    fun playSplitExit(
        expandingCard: CardSpec?,
        recedingCard: CardSpec?,
        onFinished: Runnable?,
    ) {
        expandingCard?.let {
            it.mode = MODE_NORMAL
            it.startCornerPx = dp(12f)
            it.endCornerPx = dp(0f)
        }
        recedingCard?.let {
            it.mode = MODE_SPLIT_RECEDE
            it.startCornerPx = dp(12f)
            it.endCornerPx = dp(18f)
        }
        startChoreography(expandingCard, recedingCard, 250L, INTERP_SPRING, true, false, false, 0.12f, null, onFinished)
    }

    private fun startChoreography(
        c0: CardSpec?,
        c1: CardSpec?,
        durationMs: Long,
        interp: Interpolator,
        waitBackendBeforeDissolve: Boolean,
        drawSplitSeam: Boolean,
        splitPortrait: Boolean,
        scrimMaxAlpha: Float,
        onBounce: Runnable?,
        onFinished: Runnable?,
    ) {
        cancelActiveAnimation()
        val curToken = ++animSeq
        this.card0 = c0
        this.card1 = c1
        this.drawSplitSeam = drawSplitSeam
        this.splitPortrait = splitPortrait
        this.scrimMaxAlpha = scrimMaxAlpha
        this.waitBackendBeforeDissolve = waitBackendBeforeDissolve
        this.backendReady = !waitBackendBeforeDissolve
        this.motionDone = false
        this.bounceFired = false
        this.progress = 0f
        this.masterAlpha = 1f
        this.onBounce = onBounce
        this.onFinished = onFinished
        this.animating = true

        visibility = VISIBLE
        invalidate()

        val va = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = interp
            addUpdateListener { anim ->
                progress = anim.animatedValue as Float
                if (!bounceFired && this@StageTransitionView.onBounce != null && progress >= 0.84f) {
                    bounceFired = true
                    val b = this@StageTransitionView.onBounce
                    this@StageTransitionView.onBounce = null
                    b?.run()
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!animating || mainAnimator !== animation) return
                    progress = 1f
                    motionDone = true
                    if (!bounceFired && this@StageTransitionView.onBounce != null) {
                        bounceFired = true
                        val b = this@StageTransitionView.onBounce
                        this@StageTransitionView.onBounce = null
                        b?.run()
                    }
                    if (!this@StageTransitionView.waitBackendBeforeDissolve) {
                        finishAndCleanup()
                    } else if (backendReady) {
                        uiHandler.postDelayed({
                            if (animating && animSeq == curToken) startDissolveOut()
                        }, 18L)
                    } else {
                        backendWaitStartMs = SystemClock.uptimeMillis()
                        uiHandler.postDelayed(holdWatchdog, 16L)
                    }
                }
            })
        }
        mainAnimator = va
        va.start()
    }

    private fun startDissolveOut() {
        if (!animating || dissolveAnimator != null) return
        val dis = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 85L
            interpolator = INTERP_DISSOLVE
            addUpdateListener { anim ->
                masterAlpha = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (dissolveAnimator === animation) {
                        finishAndCleanup()
                    }
                }
            })
        }
        dissolveAnimator = dis
        dis.start()
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
        val fin = onFinished
        onFinished = null
        animating = false
        visibility = GONE
        fin?.run()
    }

    override fun onDraw(canvas: Canvas) {
        if (!animating) return
        val p = progress.coerceIn(0f, 1f)
        val master = masterAlpha.coerceIn(0f, 1f)
        if (master <= 0.005f) return

        val c0 = card0
        val c1 = card1
        if (scrimMaxAlpha > 0f) {
            val bell = if (c0 != null && c0.mode == MODE_MINIMIZE_TO_ICON) {
                (1f - p) * scrimMaxAlpha
            } else {
                sin(min(1f, p * 1.15f) * Math.PI * 0.5).toFloat() * scrimMaxAlpha * master
            }
            val sa = (bell.coerceIn(0f, 1f) * 255f).roundToInt()
            if (sa > 2) {
                scrimPaint.color = (sa shl 24) or 0x050811
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
            }
        }

        if (c1 != null && c0 != null && c0.mode == MODE_SWAP_OVER) {
            drawSingleCard(canvas, c1, p, master)
            drawSingleCard(canvas, c0, p, master)
        } else {
            if (c0 != null) drawSingleCard(canvas, c0, p, master)
            if (c1 != null) drawSingleCard(canvas, c1, p, master)
        }

        if (drawSplitSeam && c0 != null && c1 != null) {
            drawSeamHighlight(canvas, c0, c1, p, master)
        }
    }

    private fun drawSingleCard(canvas: Canvas, c: CardSpec, p: Float, master: Float) {
        var px = p
        val py = p
        if (c.mode == MODE_MINIMIZE_TO_ICON) {
            px = p.toDouble().pow(0.84).toFloat()
        } else if (c.mode == MODE_RESTORE_FROM_ICON) {
            px = 1f - (1f - p).toDouble().pow(1.15).toFloat()
        }

        curRect.set(
            lerp(c.startRect.left, c.endRect.left, px),
            lerp(c.startRect.top, c.endRect.top, py),
            lerp(c.startRect.right, c.endRect.right, px),
            lerp(c.startRect.bottom, c.endRect.bottom, py),
        )

        var scaleAdjust = 1f
        if (c.mode == MODE_SWAP_OVER) {
            scaleAdjust = 1f - 0.045f * sin(p * Math.PI).toFloat()
        } else if (c.mode == MODE_SWAP_UNDER) {
            scaleAdjust = 1f - 0.105f * sin(p * Math.PI).toFloat()
        }
        if (scaleAdjust != 1f) {
            val cx = curRect.centerX()
            val cy = curRect.centerY()
            val hw = curRect.width() * 0.5f * scaleAdjust
            val hh = curRect.height() * 0.5f * scaleAdjust
            curRect.set(cx - hw, cy - hh, cx + hw, cy + hh)
        }

        if (curRect.width() <= 1f || curRect.height() <= 1f) return

        var cardAlpha = lerp(c.startAlpha, c.endAlpha, p) * master
        when (c.mode) {
            MODE_MINIMIZE_TO_ICON -> {
                if (p > 0.76f) {
                    cardAlpha *= max(0f, 1f - (p - 0.76f) / 0.24f)
                }
            }
            MODE_RESTORE_FROM_ICON -> {
                if (p < 0.18f) {
                    cardAlpha *= (0.45f + 0.55f * (p / 0.18f))
                }
            }
            MODE_SPLIT_RECEDE -> {
                cardAlpha *= max(0f, 1f - p * 1.18f)
            }
        }
        if (cardAlpha <= 0.01f) return

        var corner = lerp(c.startCornerPx, c.endCornerPx, p)
        if (c.mode == MODE_SWAP_OVER || c.mode == MODE_SWAP_UNDER) {
            corner = max(corner, dp(14f) * sin(p * Math.PI).toFloat())
        }
        corner = min(corner, min(curRect.width(), curRect.height()) * 0.30f)

        val shadowStrength = if (c.mode == MODE_SWAP_OVER) {
            0.36f * sin(p * Math.PI).toFloat() + 0.12f
        } else {
            0.22f * cardAlpha
        }
        val shAlpha = ((shadowStrength * cardAlpha).coerceIn(0f, 1f) * 255f).roundToInt()
        if (shAlpha > 4 && corner > dp(1f)) {
            val spread = dp(if (c.mode == MODE_SWAP_OVER) 10f else 5f)
            shadowRect.set(
                curRect.left - spread * 0.5f,
                curRect.top + dp(2f),
                curRect.right + spread * 0.5f,
                curRect.bottom + spread,
            )
            shadowPaint.color = shAlpha shl 24
            canvas.drawRoundRect(shadowRect, corner + spread * 0.5f, corner + spread * 0.5f, shadowPaint)
        }

        val save = canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(curRect, corner, corner, Path.Direction.CW)
        canvas.clipPath(clipPath)

        val alphaInt = (cardAlpha * 255f).roundToInt()
        val bmp = c.snapshot
        var hasBmp = bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0
        if (hasBmp && Build.VERSION.SDK_INT >= 26 &&
            bmp!!.config == Bitmap.Config.HARDWARE && !canvas.isHardwareAccelerated
        ) {
            hasBmp = false
        }

        var iconMorph = 0f
        if (c.mode == MODE_MINIMIZE_TO_ICON && c.icon != null) {
            iconMorph = smoothStep(0.36f, 0.80f, p)
        } else if (c.mode == MODE_RESTORE_FROM_ICON && c.icon != null) {
            iconMorph = 1f - smoothStep(0.10f, 0.48f, p)
        }

        if (hasBmp && bmp != null) {
            cardBgPaint.shader = null
            cardBgPaint.color = (alphaInt shl 24) or 0x101622
            canvas.drawRect(curRect, cardBgPaint)

            val contentAlpha = cardAlpha * (1f - iconMorph * 0.88f)
            if (contentAlpha > 0.02f) {
                bmpPaint.alpha = (contentAlpha * 255f).roundToInt()
                configureCenterCropMatrix(bmp.width, bmp.height, curRect, bmpMatrix)
                canvas.drawBitmap(bmp, bmpMatrix, bmpPaint)
            }
        } else {
            cardBgPaint.shader = LinearGradient(
                curRect.left, curRect.top, curRect.left, curRect.bottom,
                (alphaInt shl 24) or 0x182234,
                (alphaInt shl 24) or 0x0E1420,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(curRect, cardBgPaint)
            cardBgPaint.shader = null

            val icon = c.icon
            if (icon != null && iconMorph < 0.99f) {
                val baseIconSize = min(dp(64f), min(curRect.width(), curRect.height()) * 0.42f)
                drawCenteredAppIconAndLabel(canvas, icon, c.label, curRect, baseIconSize, cardAlpha * (1f - iconMorph))
            }
        }

        val icon = c.icon
        if (iconMorph > 0.01f && icon != null) {
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

        canvas.restoreToCount(save)

        val borderVisibility = if (c.endCornerPx <= dp(1f) && p > 0.8f) {
            1f - (p - 0.8f) / 0.2f
        } else {
            1f
        }
        val strokeAlpha = (cardAlpha * borderVisibility * 0.42f * 255f).roundToInt()
        if (strokeAlpha > 4) {
            strokePaint.color = (strokeAlpha shl 24) or 0x38BDF8
            canvas.drawRoundRect(curRect, corner, corner, strokePaint)
        }
    }

    private fun drawCenteredAppIconAndLabel(
        canvas: Canvas,
        icon: Drawable,
        label: String?,
        rect: RectF,
        iconSize: Float,
        alpha: Float,
    ) {
        if (alpha <= 0.02f || iconSize <= 4f) return
        val a = (alpha * 255f).roundToInt()
        val showLabel = !label.isNullOrEmpty() && rect.width() > dp(150f) && rect.height() > dp(130f)
        val cy = if (showLabel) rect.centerY() - dp(12f) else rect.centerY()
        val cx = rect.centerX()
        val half = iconSize * 0.5f

        val plateHalf = half + dp(8f)
        iconPlateRect.set(cx - plateHalf, cy - plateHalf, cx + plateHalf, cy + plateHalf)
        iconPlatePaint.color = ((a * 0.35f).roundToInt() shl 24) or 0xFFFFFF
        canvas.drawRoundRect(iconPlateRect, dp(16f), dp(16f), iconPlatePaint)

        icon.setBounds(
            (cx - half).roundToInt(),
            (cy - half).roundToInt(),
            (cx + half).roundToInt(),
            (cy + half).roundToInt(),
        )
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
        val seamAlpha = sin(min(1f, p * 1.1f) * Math.PI * 0.5).toFloat() * master * 0.65f
        val a = (seamAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
        if (a <= 4) return
        seamPaint.color = (a shl 24) or 0x0D84FF
        if (!splitPortrait) {
            val seamX = (lerp(c0.startRect.right, c0.endRect.right, p) +
                lerp(c1.startRect.left, c1.endRect.left, p)) * 0.5f
            val padY = height * 0.18f
            canvas.drawRoundRect(
                seamX - dp(1.5f), padY, seamX + dp(1.5f), height - padY,
                dp(1.5f), dp(1.5f), seamPaint,
            )
        } else {
            val seamY = (lerp(c0.startRect.bottom, c0.endRect.bottom, p) +
                lerp(c1.startRect.top, c1.endRect.top, p)) * 0.5f
            val padX = width * 0.18f
            canvas.drawRoundRect(
                padX, seamY - dp(1.5f), width - padX, seamY + dp(1.5f),
                dp(1.5f), dp(1.5f), seamPaint,
            )
        }
    }
}

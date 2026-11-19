package com.extbridge.helper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.extbridge.helper.HyperUi
import com.extbridge.helper.StageTransitionView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "ExtOverlayWidgets"

internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

internal fun Context.llp(
    w: Int = WRAP,
    h: Int = WRAP,
    weight: Float = 0f,
    leftDp: Float = 0f,
    topDp: Float = 0f,
    rightDp: Float = 0f,
    bottomDp: Float = 0f,
): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight).apply {
    if (leftDp != 0f || topDp != 0f || rightDp != 0f || bottomDp != 0f) {
        setMargins(dp(leftDp), dp(topDp), dp(rightDp), dp(bottomDp))
    }
}

internal fun Context.flp(
    w: Int = WRAP,
    h: Int = WRAP,
    gravity: Int = Gravity.NO_GRAVITY,
    leftPx: Int = 0,
    topPx: Int = 0,
    bottomDp: Float = 0f,
): FrameLayout.LayoutParams = FrameLayout.LayoutParams(w, h, gravity).apply {
    leftMargin = leftPx
    topMargin = topPx
    if (bottomDp != 0f) bottomMargin = dp(bottomDp)
}

internal fun View.setPadDp(h: Float, v: Float = h) = setPadding(context.dp(h), context.dp(v), context.dp(h), context.dp(v))

internal fun View.setPadDp(l: Float, t: Float, r: Float, b: Float) =
    setPadding(context.dp(l), context.dp(t), context.dp(r), context.dp(b))

internal fun Context.toast(msg: String, long: Boolean = false) =
    Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

internal fun View.measureForMax(maxW: Int, maxH: Int, exactW: Boolean = false) = measure(
    View.MeasureSpec.makeMeasureSpec(maxW, if (exactW) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST),
    View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST),
)

internal fun View.resetPopoverVisuals() {
    animate().cancel()
    alpha = 1f; translationY = 0f; scaleX = 1f; scaleY = 1f
}

internal fun View.animatePopover(
    show: Boolean,
    shiftYDp: Float = 10f,
    startScale: Float = 0.93f,
    enterMs: Long = 165L,
    exitMs: Long = 130L,
    onEnd: (() -> Unit)? = null,
) {
    animate().cancel()
    if (show) {
        alpha = 0f
        translationY = context.dpF(shiftYDp)
        scaleX = startScale
        scaleY = startScale
        animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(enterMs).setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction { onEnd?.invoke() }.start()
    } else {
        animate().alpha(0f).translationY(context.dpF(shiftYDp * 0.8f)).scaleX(startScale).scaleY(startScale)
            .setDuration(exitMs).setInterpolator(AccelerateInterpolator(1.3f))
            .withEndAction { onEnd?.invoke() }.start()
    }
}

/**
 * Shared View helpers, dirty-checked HyperOS chip styling, hit-testing, and stage rect math.
 */
object OverlayUiUtils {

    private data class ChipStyleKey(val active: Boolean, val iconKind: Int, val text: String)

    fun makeVerticalDivider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(0x38FFFFFF)
        layoutParams = ctx.llp(1, ctx.dp(22f), leftDp = 7f, rightDp = 7f)
    }

    fun enforceSingleLine(tv: TextView) {
        tv.isSingleLine = true
        tv.maxLines = 1
        tv.includeFontPadding = false
        tv.ellipsize = TextUtils.TruncateAt.END
    }

    fun makeDockBtn(ctx: Context, txt: String, color: Int, textColor: Int = Color.WHITE): TextView =
        TextView(ctx).apply {
            text = txt
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
            setPadDp(10f, 5f)
            enforceSingleLine(this)
            background = roundedBg(color, ctx.dpF(13f))
            layoutParams = ctx.llp(leftDp = 3f, rightDp = 3f)
        }

    fun makeFooterChip(ctx: Context, txt: String, centered: Boolean = false): TextView =
        TextView(ctx).apply {
            text = txt
            setTextColor(0xFFD8DEE9.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = if (centered) Gravity.CENTER else Gravity.CENTER_VERTICAL
            isSingleLine = true
            setPadDp(11f, 6f)
            HyperUi.styleHyperChip(this, false)
        }

    fun makeChipRow(
        ctx: Context,
        gapDp: Float = 6f,
        topMarginDp: Float = 0f,
        bottomMarginDp: Float = 0f,
        vararg items: View,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = ctx.llp(MATCH, WRAP, topDp = topMarginDp, bottomDp = bottomMarginDp)
        items.forEachIndexed { idx, child ->
            addView(child, ctx.llp(0, WRAP, 1f, rightDp = if (idx < items.lastIndex) gapDp else 0f))
        }
    }

    /** Dirty-checked chip updater skipping redundant drawable allocations on 2.2s polls. */
    fun styleChipWithIcon(
        tv: TextView?,
        active: Boolean,
        iconKind: Int,
        text: String? = null,
        sizeDp: Float = 13f,
        padDp: Float = 5f,
    ) {
        if (tv == null) return
        val resolvedText = text ?: tv.text?.toString().orEmpty()
        val key = ChipStyleKey(active, iconKind, resolvedText)
        if (tv.tag == key) return
        tv.tag = key
        if (text != null) tv.text = text
        HyperUi.styleHyperChip(tv, active)
        if (iconKind > 0) {
            HyperUi.setStartIcon(tv, iconKind, if (active) Color.WHITE else 0xFFD8DEE9.toInt(), sizeDp, padDp)
        }
    }

    fun makeFlyoutCard(ctx: Context, cornerDp: Float = 18f, padHDp: Float = 12f, padVDp: Float = 10f, elevationDp: Float = 18f): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadDp(padHDp, padVDp)
            elevation = ctx.dpF(elevationDp)
            background = roundedBg(HyperUi.FLYOUT_BG, ctx.dpF(cornerDp), 1, HyperUi.FLYOUT_STROKE)
        }

    fun makeStateListBg(ctx: Context, normalColor: Int, activeColor: Int, radiusDp: Float = 10f): StateListDrawable {
        val hi = roundedBg(activeColor, ctx.dpF(radiusDp))
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), hi)
            addState(intArrayOf(android.R.attr.state_hovered), hi)
            addState(IntArray(0), roundedBg(normalColor, ctx.dpF(radiusDp)))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachTactilePress(v: View?, pressedScale: Float = 0.90f) {
        v?.setOnTouchListener { view, ev ->
            when (ev?.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().cancel().also {
                    view.animate().scaleX(pressedScale).scaleY(pressedScale).setDuration(70L).setInterpolator(DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().cancel().also {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(150L).setInterpolator(OvershootInterpolator(1.3f)).start()
                }
            }
            false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachRightClickListener(
        view: View,
        includeLongClick: Boolean = true,
        pressedScale: Float? = null,
        onSecondaryAction: () -> Unit,
    ) {
        var secondaryDown = false
        view.setOnTouchListener { v, ev ->
            val act = ev?.actionMasked ?: return@setOnTouchListener false
            if (act == MotionEvent.ACTION_DOWN && (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                secondaryDown = true
                onSecondaryAction()
                return@setOnTouchListener true
            }
            if (secondaryDown) {
                if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) secondaryDown = false
                return@setOnTouchListener true
            }
            if (pressedScale != null) {
                if (act == MotionEvent.ACTION_DOWN) {
                    v.animate().cancel()
                    v.animate().scaleX(pressedScale).scaleY(pressedScale).setDuration(80L).start()
                } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(170L).setInterpolator(OvershootInterpolator(1.9f)).start()
                }
            }
            false
        }
        view.setOnGenericMotionListener { _, ev ->
            if (ev != null && ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && ev.actionButton == MotionEvent.BUTTON_SECONDARY) {
                onSecondaryAction(); true
            } else false
        }
        view.setOnContextClickListener { onSecondaryAction(); true }
        if (includeLongClick) view.setOnLongClickListener { onSecondaryAction(); true }
    }

    fun isPointInView(v: View?, winX: Float, winY: Float): Boolean {
        if (v == null || v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) return false
        val loc = IntArray(2); v.getLocationInWindow(loc)
        return winX >= loc[0] && winX <= loc[0] + v.width && winY >= loc[1] && winY <= loc[1] + v.height
    }

    fun isPointInViewOnScreen(v: View?, screenX: Float, screenY: Float): Boolean {
        if (v == null || v.visibility != View.VISIBLE || !v.isShown || v.width <= 0 || v.height <= 0) return false
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        return screenX >= loc[0] && screenX <= loc[0] + v.width && screenY >= loc[1] && screenY <= loc[1] + v.height
    }

    fun getFullStageRect(overlayW: Int, overlayH: Int): Rect =
        Rect(0, 0, max(OverlayConstants.MIN_SURFACE_PX, overlayW), max(OverlayConstants.MIN_SURFACE_PX, overlayH))

    fun computeLocalSplitRect(overlayW: Int, overlayH: Int, slot: Int, ratio: Float): Rect {
        val sw = max(OverlayConstants.MIN_SURFACE_PX, overlayW)
        val sh = max(OverlayConstants.MIN_SURFACE_PX, overlayH)
        val r = ratio.coerceIn(0.25f, 0.75f)
        return if (sh <= sw) {
            val div = (sw * r).roundToInt().coerceIn((sw * 0.25f).roundToInt(), (sw * 0.75f).roundToInt())
            if (slot == 0) Rect(0, 0, max(240, div - 4), sh) else Rect(min(sw - 240, div + 4), 0, sw, sh)
        } else {
            val div = (sh * r).roundToInt().coerceIn((sh * 0.25f).roundToInt(), (sh * 0.75f).roundToInt())
            if (slot == 0) Rect(0, 0, sw, max(240, div - 4)) else Rect(0, min(sh - 240, div + 4), sw, sh)
        }
    }

    fun recycleUnusedCard(c: StageTransitionView.CardSpec?) {
        val b = c?.snapshot ?: return
        c.snapshot = null
        if (c.ownSnapshot && !b.isRecycled) {
            try { b.recycle() } catch (t: Throwable) { Log.w(TAG, "Failed to recycle CardSpec bitmap", t) }
        }
    }
}

/** Dirty-checked [ImageView] wrapper around [HyperUi.icon] replacing the legacy `MiniIconView`. */
class VectorIconHolder(ctx: Context, private var kind: Int, private var tint: Int, private val sizeDp: Float) {
    val view: ImageView = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }

    init { applyDrawable() }

    fun update(kind: Int = this.kind, tint: Int = this.tint) {
        if (this.kind == kind && this.tint == tint) return
        this.kind = kind; this.tint = tint
        applyDrawable()
    }

    private fun applyDrawable() = view.setImageDrawable(HyperUi.icon(view.context, kind, tint, sizeDp))
}

/** Compact HyperOS quick-settings tile backed by [VectorIconHolder] + dirty-checked background state. */
class QuickTile(
    private val ctx: Context,
    iconKind: Int,
    initialLabel: String,
    hasArrow: Boolean,
    onToggle: () -> Unit,
    onMore: (() -> Unit)? = null,
) {
    val root: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val box: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val iconHolder = VectorIconHolder(ctx, iconKind, Color.WHITE, 18f)
    private val splitSep: View?
    private val label: TextView
    private val bg = GradientDrawable()
    private var active = false
    private var bgInit = false

    init {
        root.addView(box, ctx.llp(MATCH, ctx.dp(44f), bottomDp = 5f))
        val leftWrap = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            setOnClickListener { onToggle() }
            addView(iconHolder.view, ctx.llp(ctx.dp(18f), ctx.dp(18f)))
        }
        box.addView(leftWrap, ctx.llp(0, MATCH, 1f))
        if (hasArrow) {
            splitSep = View(ctx).apply { setBackgroundColor(0x28FFFFFF) }
            box.addView(splitSep, ctx.llp(1, MATCH))
            box.addView(TextView(ctx).apply {
                text = "›"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = Gravity.CENTER
                setOnClickListener { onMore?.invoke() }
            }, ctx.llp(ctx.dp(32f), MATCH))
        } else {
            splitSep = null
        }
        label = TextView(ctx).apply {
            text = initialLabel; setTextColor(0xFFE2E8F0.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(label, ctx.llp(MATCH, WRAP))
        setActive(false, initialLabel)
    }

    fun setActive(on: Boolean, caption: String? = null) {
        if (caption != null && label.text?.toString() != caption) label.text = caption
        if (bgInit && on == active) return
        active = on; bgInit = true
        bg.setColor(if (on) HyperUi.HYPER_BLUE else HyperUi.TILE_INACTIVE)
        bg.cornerRadius = ctx.dpF(14f)
        bg.setStroke(1, if (on) 0x48FFFFFF else 0x1AFFFFFF)
        box.background = bg
        splitSep?.setBackgroundColor(if (on) 0x38FFFFFF else 0x22FFFFFF)
    }
}

class MiniBatteryView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var pct = 100
    private var charging = false

    fun setBattery(pct: Int, charging: Boolean) {
        if (this.pct != pct || this.charging != charging) {
            this.pct = pct; this.charging = charging
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val bodyR = w * 0.86f; val stroke = max(1.4f, h * 0.11f)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke; paint.color = 0xFFE2E8F0.toInt()
        rect.set(stroke, stroke, bodyR, h - stroke)
        c.drawRoundRect(rect, h * 0.18f, h * 0.18f, paint)
        paint.style = Paint.Style.FILL
        rect.set(bodyR + stroke * 0.5f, h * 0.30f, w - 1f, h * 0.70f)
        c.drawRoundRect(rect, 1.5f, 1.5f, paint)
        paint.color = when { charging -> 0xFF4ADE80.toInt(); pct <= 20 -> 0xFFF87171.toInt(); else -> 0xFFE2E8F0.toInt() }
        val pad = stroke * 1.6f
        rect.set(pad, pad, pad + max(0f, bodyR - pad * 2f) * (pct / 100f), h - pad)
        c.drawRoundRect(rect, h * 0.10f, h * 0.10f, paint)
    }
}

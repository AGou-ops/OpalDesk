package com.extbridge.helper.overlay

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Handler
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.HyperUi
import com.extbridge.helper.StageTransitionView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shared coordinator interface between [com.extbridge.helper.BridgeService] and
 * the modular overlay controllers in `com.extbridge.helper.overlay`.
 */
interface OverlayHost {
    val serviceContext: Context
    val handler: Handler
    val displayManager: DisplayManager?
    val audioManager: AudioManager?
    val isDestroyed: Boolean

    val extCtx: Context?
    val extWm: WindowManager?
    val winType: Int
    val usingA11yOverlay: Boolean
    val inLauncherHost: Boolean
    val attachedVdId: Int
    val overlayW: Int
    val overlayH: Int
    val overlayDpi: Int
    val stageAnimView: StageTransitionView?
    val launcherHost: ViewGroup?

    val lastStatus: BridgeClient.Status
    val lastWindowSnapshot: BridgeClient.WindowStateSnapshot
    val lastSysStatus: BridgeClient.SysStatus
    val lastMirrorTelemetry: BridgeClient.MirrorTelemetry
    val batteryPct: Int
    val batteryCharging: Boolean

    fun safeBgExecute(r: Runnable)
    fun safeInputExecute(r: Runnable)
    fun safePost(r: Runnable)
    fun pollRemoteStateAsync()
    fun beginWindowOverlayStage()
    fun endWindowOverlayStage()
    fun syncOverlayHostSurface(forceAttach: Boolean)
    fun removeOverlayView(v: View?)
    fun bringCursorToFront()

    fun getCachedIcon(pm: PackageManager, pkg: String): Drawable?
    fun getCachedLabel(pm: PackageManager, pkg: String): String
    fun cloneDrawable(d: Drawable?): Drawable?
    fun buildCardSpec(
        pkg: String?,
        snap: BridgeClient.TaskSnapshotData?,
        startR: Rect,
        endR: Rect,
    ): StageTransitionView.CardSpec

    fun isBlackoutOverlayActive(): Boolean
    fun toggleDexBlackout()
    fun setDexBlackout(on: Boolean)
    fun isDockHidden(): Boolean
    fun isDockAutoHide(): Boolean
    fun isOnLauncherHome(): Boolean
    fun isPanelOpen(): Boolean
    fun isSplitMenuOpen(): Boolean
    fun isTaskBubbleOpen(): Boolean
    fun setPanelOpen(open: Boolean)
    fun setSplitMenuOpen(open: Boolean)
    fun setTaskBubbleOpen(open: Boolean)
    fun triggerQuickSplitOrMenu()
    fun updateDockAutoHideUi()
    fun evaluateDockAutoHide(forceDelay: Boolean)
    fun cancelAutoHideTimer()
    fun wakeDockFromEdge()
    fun applyDockHidden(hidden: Boolean)
    fun applyDockHiddenAnimated(hidden: Boolean)
    fun setDockAutoHideEnabled(enabled: Boolean)
    fun updateBlackoutUi()
    fun updateInputToggleUi(inputToExt: Int)
    fun openExtSettings(action: String)
    fun openTabletMain()
    fun openTabletTouchpad()
    fun execSysAndRefresh(act: Int, arg: Int, str: String?)
    fun getDockTrayButton(): View?
    fun getDockRoot(): View?
    fun findDockSlotByPkg(pkg: String?): View?
    fun resolveDockIconRectOnScreen(preferredSlot: View?, pkg: String?): RectF
    fun bounceDockSlot(pkg: String?, fallbackView: View?, downwardCushion: Boolean)
    fun performEnterOrAssignSplitAnimated(action: Int, slotHint: Int, taskId: Int, pkg: String?, cls: String?)
    fun performAssignSplitAnimated(slot: Int, taskId: Int, pkg: String?, cls: String?)
    fun performExitSplitAnimated()
    fun performExitSplitToHomeAnimated(anchorBtn: View?)
    fun displayedSplitRatio(): Float
    fun onLocalHardwareRefreshRequested()
    fun refreshTrayIconsOnly()
    fun updateTrayPillBackground(active: Boolean)
    fun notifyTrayToggleTimestamp()
    fun cancelPanelOutsideDismiss()
    fun isTrayPressed(): Boolean
    fun lastTrayUpMs(): Long
    fun lastTrayToggleMs(): Long
    fun updateDockSplitButton(splitActive: Boolean)
    fun updatePanelSplitButton(splitActive: Boolean)
    fun repositionPanelIfOpen()
    fun repositionDock()
}

object OverlayUiUtils {
    fun dp(ctx: Context, dpVal: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dpVal,
            ctx.resources.displayMetrics,
        ).roundToInt()

    fun makeVerticalDivider(ctx: Context): View {
        val div = View(ctx)
        div.setBackgroundColor(0x38FFFFFF)
        val lp = LinearLayout.LayoutParams(1, dp(ctx, 22f))
        lp.leftMargin = dp(ctx, 7f)
        lp.rightMargin = dp(ctx, 7f)
        div.layoutParams = lp
        return div
    }

    fun enforceSingleLine(tv: TextView) {
        tv.isSingleLine = true
        tv.maxLines = 1
        tv.includeFontPadding = false
        tv.ellipsize = TextUtils.TruncateAt.END
    }

    fun makeDockBtn(ctx: Context, txt: String, color: Int, textColor: Int): TextView {
        val tv = TextView(ctx)
        tv.text = txt
        tv.setTextColor(textColor)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
        tv.setPadding(dp(ctx, 10f), dp(ctx, 5f), dp(ctx, 10f), dp(ctx, 5f))
        enforceSingleLine(tv)
        val bg = GradientDrawable()
        bg.setColor(color)
        bg.cornerRadius = dp(ctx, 13f).toFloat()
        tv.background = bg
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.setMargins(dp(ctx, 3f), 0, dp(ctx, 3f), 0)
        tv.layoutParams = lp
        return tv
    }

    fun attachTactilePress(v: View?, pressedScale: Float) {
        if (v == null) return
        v.setOnTouchListener { view, ev ->
            when (ev?.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().cancel()
                    view.animate().scaleX(pressedScale).scaleY(pressedScale)
                        .setDuration(70L).setInterpolator(DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().cancel()
                    view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(150L).setInterpolator(OvershootInterpolator(1.3f)).start()
                }
            }
            false
        }
    }

    fun isPointInView(v: View?, winX: Float, winY: Float): Boolean {
        if (v == null || v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) {
            return false
        }
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        return winX >= loc[0] && winX <= (loc[0] + v.width) &&
            winY >= loc[1] && winY <= (loc[1] + v.height)
    }

    fun isPointInViewOnScreen(v: View?, screenX: Float, screenY: Float): Boolean {
        if (v == null || v.visibility != View.VISIBLE || !v.isShown || v.width <= 0 || v.height <= 0) {
            return false
        }
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return screenX >= loc[0] && screenX <= (loc[0] + v.width) &&
            screenY >= loc[1] && screenY <= (loc[1] + v.height)
    }

    fun makeFooterChip(ctx: Context, txt: String): TextView {
        val tv = TextView(ctx)
        tv.text = txt
        tv.setTextColor(0xFFD8DEE9.toInt())
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        tv.gravity = Gravity.CENTER_VERTICAL
        tv.setPadding(dp(ctx, 11f), dp(ctx, 6f), dp(ctx, 11f), dp(ctx, 6f))
        HyperUi.styleHyperChip(tv, false)
        return tv
    }

    fun getFullStageRect(overlayW: Int, overlayH: Int): Rect {
        val sw = max(600, overlayW)
        val sh = max(600, overlayH)
        return Rect(0, 0, sw, sh)
    }

    fun computeLocalSplitRect(overlayW: Int, overlayH: Int, slot: Int, ratio: Float): Rect {
        val sw = max(600, overlayW)
        val sh = max(600, overlayH)
        val portrait = sh > sw
        val r = ratio.coerceIn(0.25f, 0.75f)
        val halfGap = 4
        return if (!portrait) {
            val div = (sw * r).roundToInt().coerceIn((sw * 0.25f).roundToInt(), (sw * 0.75f).roundToInt())
            if (slot == 0) Rect(0, 0, max(240, div - halfGap), sh)
            else Rect(min(sw - 240, div + halfGap), 0, sw, sh)
        } else {
            val div = (sh * r).roundToInt().coerceIn((sh * 0.25f).roundToInt(), (sh * 0.75f).roundToInt())
            if (slot == 0) Rect(0, 0, sw, max(240, div - halfGap))
            else Rect(0, min(sh - 240, div + halfGap), sw, sh)
        }
    }

    fun recycleUnusedCard(c: StageTransitionView.CardSpec?) {
        if (c == null) return
        val b = c.snapshot
        c.snapshot = null
        if (b != null && c.ownSnapshot && !b.isRecycled) {
            try { b.recycle() } catch (_: Throwable) {}
        }
    }
}

class QuickTile(
    private val ctx: Context,
    iconKind: Int,
    initialLabel: String,
    val hasArrow: Boolean,
    onToggle: Runnable?,
    onMore: Runnable?,
) {
    val root: LinearLayout = LinearLayout(ctx)
    val box: LinearLayout = LinearLayout(ctx)
    val leftBtn: View
    val rightBtn: View?
    val splitSep: View?
    val icon: MiniIconView
    val arrow: TextView?
    val label: TextView
    var active: Boolean = false
        private set

    private val bg = GradientDrawable()
    private var bgInit = false

    init {
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL

        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        val boxLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            OverlayUiUtils.dp(ctx, 44f),
        )
        boxLp.bottomMargin = OverlayUiUtils.dp(ctx, 5f)
        root.addView(box, boxLp)

        val leftWrap = LinearLayout(ctx)
        leftWrap.gravity = Gravity.CENTER
        leftWrap.setOnClickListener { onToggle?.run() }
        icon = MiniIconView(ctx, iconKind, Color.WHITE)
        leftWrap.addView(icon, LinearLayout.LayoutParams(OverlayUiUtils.dp(ctx, 18f), OverlayUiUtils.dp(ctx, 18f)))
        box.addView(leftWrap, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        leftBtn = leftWrap

        if (hasArrow) {
            splitSep = View(ctx)
            splitSep.setBackgroundColor(0x28FFFFFF)
            box.addView(splitSep, LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT))

            val arr = TextView(ctx)
            arr.text = "›"
            arr.setTextColor(Color.WHITE)
            arr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            arr.gravity = Gravity.CENTER
            arr.setOnClickListener { onMore?.run() }
            box.addView(arr, LinearLayout.LayoutParams(OverlayUiUtils.dp(ctx, 32f), LinearLayout.LayoutParams.MATCH_PARENT))
            arrow = arr
            rightBtn = arr
        } else {
            splitSep = null
            arrow = null
            rightBtn = null
        }

        label = TextView(ctx)
        label.text = initialLabel
        label.setTextColor(0xFFE2E8F0.toInt())
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        label.gravity = Gravity.CENTER
        label.isSingleLine = true
        label.ellipsize = TextUtils.TruncateAt.END
        root.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        setActive(false, initialLabel)
    }

    fun setActive(on: Boolean, caption: String?) {
        if (caption != null) label.text = caption
        if (bgInit && on == active) return
        active = on
        bgInit = true

        bg.setColor(if (on) HyperUi.HYPER_BLUE else HyperUi.TILE_INACTIVE)
        bg.cornerRadius = OverlayUiUtils.dp(ctx, 14f).toFloat()
        bg.setStroke(1, if (on) 0x48FFFFFF else 0x1AFFFFFF)
        box.background = bg

        val fg = Color.WHITE
        icon.setTint(fg)
        arrow?.setTextColor(fg)
        splitSep?.setBackgroundColor(if (on) 0x38FFFFFF else 0x22FFFFFF)
    }
}

class MiniIconView(
    ctx: Context,
    private val kind: Int,
    private var color: Int,
) : View(ctx) {

    companion object {
        const val KIND_WIFI = 1
        const val KIND_BT = 2
        const val KIND_VOLUME = 3
        const val KIND_AIRPLANE = 4
        const val KIND_BATTERY_SAVER = 5
        const val KIND_DISPLAY_SWITCH = 6
        const val KIND_TOUCHPAD = 7
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcRect = RectF()
    private val path = Path()
    private var volLevel = 2 // 0=mute, 1=low, 2=high
    private var activeState = true

    fun setTint(c: Int) {
        if (color != c) {
            color = c
            invalidate()
        }
    }

    fun setVolumeLevel(lvl: Int) {
        if (volLevel != lvl) {
            volLevel = lvl
            invalidate()
        }
    }

    fun setActiveState(a: Boolean) {
        if (activeState != a) {
            activeState = a
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        if (w <= 0f || h <= 0f) return
        c.save()
        c.translate(paddingLeft.toFloat(), paddingTop.toFloat())

        paint.color = if (activeState) color else (color and 0x66FFFFFF)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        val cx = w * 0.5f
        val cy = h * 0.5f
        val s = min(w, h)

        when (kind) {
            KIND_WIFI -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1.8f, s * 0.11f)
                val oy = h * 0.80f
                arcRect.set(cx - s * 0.44f, oy - s * 0.62f, cx + s * 0.44f, oy + s * 0.26f)
                c.drawArc(arcRect, -135f, 90f, false, paint)
                arcRect.set(cx - s * 0.28f, oy - s * 0.40f, cx + s * 0.28f, oy + s * 0.16f)
                c.drawArc(arcRect, -135f, 90f, false, paint)
                paint.style = Paint.Style.FILL
                c.drawCircle(cx, oy - s * 0.04f, max(1.5f, s * 0.09f), paint)
                if (!activeState) {
                    paint.color = color
                    paint.style = Paint.Style.STROKE
                    c.drawLine(w * 0.18f, h * 0.18f, w * 0.82f, h * 0.82f, paint)
                }
            }
            KIND_BT -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1.7f, s * 0.10f)
                path.reset()
                path.moveTo(cx - s * 0.16f, cy - s * 0.20f)
                path.lineTo(cx + s * 0.18f, cy + s * 0.16f)
                path.lineTo(cx, cy + s * 0.36f)
                path.lineTo(cx, cy - s * 0.36f)
                path.lineTo(cx + s * 0.18f, cy - s * 0.16f)
                path.lineTo(cx - s * 0.16f, cy + s * 0.20f)
                c.drawPath(path, paint)
            }
            KIND_VOLUME -> {
                paint.color = color
                paint.style = Paint.Style.FILL
                path.reset()
                path.moveTo(w * 0.14f, h * 0.38f)
                path.lineTo(w * 0.32f, h * 0.38f)
                path.lineTo(w * 0.54f, h * 0.20f)
                path.lineTo(w * 0.54f, h * 0.80f)
                path.lineTo(w * 0.32f, h * 0.62f)
                path.lineTo(w * 0.14f, h * 0.62f)
                path.close()
                c.drawPath(path, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1.6f, s * 0.10f)
                if (volLevel <= 0) {
                    c.drawLine(w * 0.64f, h * 0.36f, w * 0.86f, h * 0.64f, paint)
                    c.drawLine(w * 0.86f, h * 0.36f, w * 0.64f, h * 0.64f, paint)
                } else {
                    arcRect.set(w * 0.50f, h * 0.32f, w * 0.72f, h * 0.68f)
                    c.drawArc(arcRect, -50f, 100f, false, paint)
                    if (volLevel >= 2) {
                        arcRect.set(w * 0.52f, h * 0.18f, w * 0.88f, h * 0.82f)
                        c.drawArc(arcRect, -52f, 104f, false, paint)
                    }
                }
            }
            KIND_AIRPLANE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1.8f, s * 0.10f)
                c.drawLine(w * 0.15f, cy, w * 0.85f, cy, paint)
                c.drawLine(w * 0.42f, h * 0.16f, w * 0.56f, cy, paint)
                c.drawLine(w * 0.42f, h * 0.84f, w * 0.56f, cy, paint)
                c.drawLine(w * 0.18f, h * 0.34f, w * 0.26f, cy, paint)
                c.drawLine(w * 0.18f, h * 0.66f, w * 0.26f, cy, paint)
            }
            KIND_BATTERY_SAVER -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1.7f, s * 0.10f)
                arcRect.set(w * 0.16f, h * 0.26f, w * 0.82f, h * 0.74f)
                c.drawRoundRect(arcRect, s * 0.1f, s * 0.1f, paint)
                c.drawLine(w * 0.85f, h * 0.40f, w * 0.85f, h * 0.60f, paint)
                c.drawLine(w * 0.38f, h * 0.50f, w * 0.60f, h * 0.50f, paint)
                c.drawLine(w * 0.49f, h * 0.38f, w * 0.49f, h * 0.62f, paint)
            }
            KIND_DISPLAY_SWITCH -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1.7f, s * 0.10f)
                arcRect.set(w * 0.14f, h * 0.20f, w * 0.86f, h * 0.68f)
                c.drawRoundRect(arcRect, s * 0.1f, s * 0.1f, paint)
                c.drawLine(w * 0.34f, h * 0.82f, w * 0.66f, h * 0.82f, paint)
                c.drawLine(cx, h * 0.68f, cx, h * 0.82f, paint)
            }
            KIND_TOUCHPAD -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1.7f, s * 0.10f)
                arcRect.set(w * 0.16f, h * 0.18f, w * 0.84f, h * 0.82f)
                c.drawRoundRect(arcRect, s * 0.12f, s * 0.12f, paint)
                c.drawLine(w * 0.16f, h * 0.64f, w * 0.84f, h * 0.64f, paint)
                c.drawLine(cx, h * 0.64f, cx, h * 0.82f, paint)
            }
        }
        c.restore()
    }
}

class MiniBatteryView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var pct = 100
    private var charging = false

    fun setBattery(pct: Int, charging: Boolean) {
        if (this.pct != pct || this.charging != charging) {
            this.pct = pct
            this.charging = charging
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val bodyR = w * 0.86f
        val stroke = max(1.4f, h * 0.11f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = 0xFFE2E8F0.toInt()
        rect.set(stroke, stroke, bodyR, h - stroke)
        c.drawRoundRect(rect, h * 0.18f, h * 0.18f, paint)

        paint.style = Paint.Style.FILL
        rect.set(bodyR + stroke * 0.5f, h * 0.30f, w - 1f, h * 0.70f)
        c.drawRoundRect(rect, 1.5f, 1.5f, paint)

        val fillCol = when {
            charging -> 0xFF4ADE80.toInt()
            pct <= 20 -> 0xFFF87171.toInt()
            else -> 0xFFE2E8F0.toInt()
        }
        paint.color = fillCol
        val pad = stroke * 1.6f
        val avail = max(0f, bodyR - pad * 2f)
        rect.set(pad, pad, pad + avail * (pct / 100f), h - pad)
        c.drawRoundRect(rect, h * 0.10f, h * 0.10f, paint)
    }
}

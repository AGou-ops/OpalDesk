package io.github.lengsukq.opaldesk.overlay.mirror

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.overlay.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "ExtMirrorWingLayout"

internal data class WingSpec(
    val slot: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val title: String,
    val isUnified: Boolean,
    val leftWidgetId: Int,
    val rightWidgetId: Int,
    val presets: List<String>,
)

internal data class BuiltWing(val spec: WingSpec, val root: LinearLayout, val cards: List<MirrorCard>)

/**
 * Computes adaptive Native Mirror wing geometry, resolution-agnostic DPI, preset grids,
 * unified/dual-wing layouts, and `AppWidgetHost` mounting/sizing.
 */
internal object MirrorWingLayoutBuilder {

    const val MIN_WING_PX = 48

    data class WingLayoutPlan(val specs: List<WingSpec>, val sideDpi: Int)

    fun computeLayoutPlan(st: BridgeClient.Status, presetKeys: List<String>, leftWidgetId: Int, rightWidgetId: Int): WingLayoutPlan? {
        val leftW = max(0, st.mirX); val rightW = max(0, st.physW - (st.mirX + st.mirW))
        val topH = max(0, st.mirY); val bottomH = max(0, st.physH - (st.mirY + st.mirH))
        val hasHoriz = leftW >= MIN_WING_PX || rightW >= MIN_WING_PX
        val hasVert = !hasHoriz && (topH >= MIN_WING_PX || bottomH >= MIN_WING_PX)
        if (!hasHoriz && !hasVert) return null

        val singleWing = if (hasHoriz) (leftW < MIN_WING_PX) != (rightW < MIN_WING_PX) else (topH < MIN_WING_PX) != (bottomH < MIN_WING_PX)
        val resScale = (min(st.physW, st.physH) / 1440f).coerceIn(0.50f, 1.65f)
        val baseDpi = if (hasVert) {
            val nh = max(topH, bottomH) / resScale
            if (nh >= 700f) 265f else (nh * 190f / 400f).coerceIn(145f, 235f)
        } else {
            val nw = max(leftW, rightW) / resScale
            if (singleWing) when { nw >= 700f -> 270f; nw >= 380f -> 220f; else -> (nw * 164f / 256f).coerceIn(145f, 190f) }
            else if (nw >= 400f) 235f else (nw * 132f / 128f).coerceIn(115f, 155f)
        }
        val sideDpi = (baseDpi * resScale).roundToInt().coerceIn(120, 360)

        val specs = buildList {
            if (singleWing) {
                val sx = if (hasHoriz && leftW < MIN_WING_PX) st.mirX + st.mirW else 0
                val sy = if (hasVert && topH < MIN_WING_PX) st.mirY + st.mirH else 0
                val sw = if (hasHoriz) (if (leftW < MIN_WING_PX) rightW else leftW) else st.physW
                val sh = if (hasVert) (if (topH < MIN_WING_PX) bottomH else topH) else st.physH
                add(WingSpec(0, sx, sy, sw, sh, "ExtMirrorUnified", true, leftWidgetId, rightWidgetId, presetKeys))
            } else {
                val splitIdx = min((presetKeys.size + 1) / 2, presetKeys.size)
                val first = presetKeys.subList(0, splitIdx); val second = presetKeys.subList(splitIdx, presetKeys.size)
                if (hasHoriz) {
                    if (leftW >= MIN_WING_PX) add(WingSpec(0, 0, 0, leftW, st.physH, "ExtMirrorLeft", false, leftWidgetId, -1, first))
                    if (rightW >= MIN_WING_PX) add(WingSpec(1, st.mirX + st.mirW, 0, rightW, st.physH, "ExtMirrorRight", false, -1, rightWidgetId, second))
                } else {
                    if (topH >= MIN_WING_PX) add(WingSpec(0, 0, 0, st.physW, topH, "ExtMirrorTop", false, leftWidgetId, -1, first))
                    if (bottomH >= MIN_WING_PX) add(WingSpec(1, 0, st.mirY + st.mirH, st.physW, bottomH, "ExtMirrorBottom", false, -1, rightWidgetId, second))
                }
            }
        }
        return WingLayoutPlan(specs, sideDpi)
    }

    fun buildWing(
        ctx: Context, spec: WingSpec, theme: BridgeClient.MirrorTheme, actions: MirrorCardActions,
        appWidgetMgr: AppWidgetManager?, appWidgetHost: AppWidgetHost?, widgetRatioProvider: (Int) -> Int,
    ): BuiltWing {
        val cards = mutableListOf<MirrorCard>()
        val root = if (spec.isUnified) {
            buildUnifiedWing(ctx, spec, theme, actions, cards, appWidgetMgr, appWidgetHost, widgetRatioProvider)
        } else {
            buildDualWing(ctx, spec, if (spec.slot == 0) spec.leftWidgetId else spec.rightWidgetId, theme, actions, cards, appWidgetMgr, appWidgetHost, widgetRatioProvider)
        }
        return BuiltWing(spec, root, cards)
    }

    private fun buildUnifiedWing(
        ctx: Context, spec: WingSpec, theme: BridgeClient.MirrorTheme, actions: MirrorCardActions,
        cardsOut: MutableList<MirrorCard>, mgr: AppWidgetManager?, wHost: AppWidgetHost?, ratioFn: (Int) -> Int,
    ): LinearLayout {
        val outer = LinearLayout(ctx).apply {
            setBackgroundColor(0xFF000000.toInt() or theme.bgColor); clipChildren = true; clipToPadding = true
        }
        val density = max(0.5f, ctx.resources.displayMetrics.density)
        val dpW = spec.width / density; val dpH = spec.height / density
        val wideBanner = dpW > dpH * 1.35f && dpH < 560f
        val hasAnyWidget = spec.leftWidgetId > 0 || spec.rightWidgetId > 0

        if (wideBanner || dpW >= 460f) {
            outer.orientation = LinearLayout.HORIZONTAL
            val pad = ctx.dp(if (wideBanner) 10f else 14f); outer.setPadding(pad, pad, pad, pad)
            val gapDp = if (wideBanner) 10f else 12f
            val cols = if (dpW >= if (wideBanner) 680f else 780f) 2 else 1
            val cardsFrac = if (hasAnyWidget) (if (wideBanner) 0.52f else 0.47f) else 0.76f
            val widgetFrac = 1.0f - cardsFrac

            val cardsCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            addPresetCardsGrid(ctx, cardsCol, spec.presets, cols, wideBanner, if (wideBanner) 7 else 9, theme, actions, cardsOut)
            outer.addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(cardsCol) }, ctx.llp(0, MATCH, cardsFrac, rightDp = gapDp))

            val widgetBox = LinearLayout(ctx).apply { orientation = if (wideBanner) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
            outer.addView(widgetBox, ctx.llp(0, MATCH, widgetFrac))
            appendWidgetSlots(
                ctx, widgetBox, wideBanner, spec.leftWidgetId, spec.rightWidgetId,
                ((spec.width - pad * 2 - ctx.dp(gapDp)) * widgetFrac).roundToInt(),
                max(ctx.dp(if (wideBanner) 80f else 120f), spec.height - pad * 2),
                wideBanner, if (wideBanner) 8 else 10, theme, mgr, wHost, ratioFn,
            )
            return outer
        }

        outer.orientation = LinearLayout.VERTICAL
        val padH = ctx.dp(if (dpW < 180f) 6f else 8f); val padV = ctx.dp(10f); outer.setPadding(padH, padV, padH, padV)
        val compact = dpW < 290f
        val innerW = max(ctx.dp(60f), spec.width - padH * 2); val innerH = max(ctx.dp(120f), spec.height - padV * 2)
        val cardsBlock = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        addPresetCardsGrid(ctx, cardsBlock, spec.presets, 1, compact, if (dpW < 220f) 6 else 7, theme, actions, cardsOut)
        cardsBlock.measureForMax(innerW, innerH, exactW = true)
        val remainH = innerH - cardsBlock.measuredHeight - ctx.dp(8f)
        val needScroll = dpH < 660f || (hasAnyWidget && remainH < ctx.dp(if (spec.leftWidgetId > 0 && spec.rightWidgetId > 0) 260f else 170f)) || (!hasAnyWidget && cardsBlock.measuredHeight > innerH)

        if (needScroll) {
            outer.addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(cardsBlock) }, ctx.llp(MATCH, 0, if (hasAnyWidget) 0.52f else 1f, bottomDp = if (hasAnyWidget) 8f else 0f))
            if (hasAnyWidget) {
                val wh = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                outer.addView(wh, ctx.llp(MATCH, 0, 0.48f))
                appendWidgetSlots(ctx, wh, false, spec.leftWidgetId, spec.rightWidgetId, innerW, max(ctx.dp(90f), ((innerH - ctx.dp(8f)) * 0.48f).roundToInt()), compact, 8, theme, mgr, wHost, ratioFn)
            }
        } else {
            outer.addView(cardsBlock, ctx.llp(MATCH, WRAP))
            outer.addView(View(ctx), ctx.llp(MATCH, ctx.dp(8f)))
            val wh = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            outer.addView(wh, ctx.llp(MATCH, 0, 1f))
            appendWidgetSlots(ctx, wh, false, spec.leftWidgetId, spec.rightWidgetId, innerW, max(ctx.dp(90f), remainH), compact, 8, theme, mgr, wHost, ratioFn)
        }
        return outer
    }

    private fun buildDualWing(
        ctx: Context, spec: WingSpec, widgetId: Int, theme: BridgeClient.MirrorTheme, actions: MirrorCardActions,
        cardsOut: MutableList<MirrorCard>, mgr: AppWidgetManager?, wHost: AppWidgetHost?, ratioFn: (Int) -> Int,
    ): LinearLayout {
        val density = max(0.5f, ctx.resources.displayMetrics.density)
        val dpW = spec.width / density
        val horizWing = dpW > (spec.height / density) * 1.4f
        val compact = dpW < 230f || horizWing
        val padH = ctx.dp(if (compact) 6f else 12f); val padV = ctx.dp(if (compact) 8f else 14f)

        val outer = LinearLayout(ctx).apply {
            orientation = if (horizWing) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt() or theme.bgColor); clipChildren = true; clipToPadding = true
            setPadding(padH, padV, padH, padV)
        }
        val cardsCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        addPresetCardsGrid(ctx, cardsCol, spec.presets, if (horizWing && dpW >= 540f) 2 else 1, compact, if (compact) 7 else 9, theme, actions, cardsOut)
        val sv = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(cardsCol) }
        val wBox = FrameLayout(ctx)

        val innerW = max(ctx.dp(56f), spec.width - padH * 2); val innerH = max(ctx.dp(80f), spec.height - padV * 2)
        val cardsFrac = if (widgetId > 0) (if (horizWing) 0.55f else 0.54f) else 0.80f
        val widgetFrac = 1.0f - cardsFrac
        if (horizWing) {
            outer.addView(sv, ctx.llp(0, MATCH, cardsFrac, rightDp = 8f))
            outer.addView(wBox, ctx.llp(0, MATCH, widgetFrac))
            mountAppWidget(ctx, wBox, widgetId, spec.slot, (max(ctx.dp(56f), innerW - ctx.dp(8f)) * widgetFrac).roundToInt(), innerH, compact, theme, mgr, wHost, ratioFn)
        } else {
            outer.addView(sv, ctx.llp(MATCH, 0, cardsFrac, bottomDp = 8f))
            outer.addView(wBox, ctx.llp(MATCH, 0, widgetFrac))
            mountAppWidget(ctx, wBox, widgetId, spec.slot, innerW, (max(ctx.dp(72f), innerH - ctx.dp(8f)) * widgetFrac).roundToInt(), compact, theme, mgr, wHost, ratioFn)
        }
        return outer
    }

    private fun addPresetCardsGrid(
        ctx: Context, container: LinearLayout, keys: List<String>, cols: Int, compact: Boolean, gapDp: Int,
        theme: BridgeClient.MirrorTheme, actions: MirrorCardActions, cardsOut: MutableList<MirrorCard>,
    ) {
        if (keys.isEmpty()) return
        val g = gapDp.toFloat()
        if (cols <= 1) {
            keys.forEachIndexed { idx, key ->
                val c = MirrorPresetCardFactory.build(ctx, key, compact, theme, actions).also(cardsOut::add)
                container.addView(c.view, ctx.llp(MATCH, WRAP, bottomDp = if (idx == keys.lastIndex) 0f else g))
            }
            return
        }
        var i = 0
        while (i < keys.size) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; isMeasureWithLargestChildEnabled = true }
            if (keys.size - i >= 2) {
                row.addView(MirrorPresetCardFactory.build(ctx, keys[i], compact, theme, actions).also(cardsOut::add).view, ctx.llp(0, MATCH, 1f, rightDp = g))
                row.addView(MirrorPresetCardFactory.build(ctx, keys[i + 1], compact, theme, actions).also(cardsOut::add).view, ctx.llp(0, MATCH, 1f))
                i += 2
            } else {
                row.addView(MirrorPresetCardFactory.build(ctx, keys[i++], compact, theme, actions).also(cardsOut::add).view, ctx.llp(MATCH, WRAP))
            }
            container.addView(row, ctx.llp(MATCH, WRAP, bottomDp = if (i >= keys.size) 0f else g))
        }
    }

    private fun appendWidgetSlots(
        ctx: Context, container: LinearLayout, horizontal: Boolean,
        leftId: Int, rightId: Int, availW: Int, availH: Int, compact: Boolean, gapDp: Int,
        theme: BridgeClient.MirrorTheme, mgr: AppWidgetManager?, wHost: AppWidgetHost?, ratioFn: (Int) -> Int,
    ) {
        val hasL = leftId > 0; val hasR = rightId > 0; val both = hasL && hasR
        val gapPx = ctx.dp(gapDp.toFloat())
        val eachW = if (horizontal && both) max(ctx.dp(80f), (availW - gapPx) / 2) else availW
        val eachH = if (!horizontal && both) max(ctx.dp(72f), (availH - gapPx) / 2) else max(ctx.dp(72f), availH)

        fun addSlot(widgetId: Int, slot: Int, gap: Boolean) {
            val box = FrameLayout(ctx)
            val lp = if (horizontal) ctx.llp(0, MATCH, 1f, rightDp = if (gap) gapDp.toFloat() else 0f)
            else ctx.llp(MATCH, 0, 1f, bottomDp = if (gap) gapDp.toFloat() else 0f)
            container.addView(box, lp)
            mountAppWidget(ctx, box, widgetId, slot, eachW, eachH, compact, theme, mgr, wHost, ratioFn)
        }
        if (hasL || !hasR) addSlot(leftId, 0, gap = hasR)
        if (hasR) addSlot(rightId, 1, gap = false)
    }

    internal fun mountAppWidget(
        ctx: Context, box: FrameLayout, widgetId: Int, slot: Int, availW: Int, availH: Int, compact: Boolean,
        theme: BridgeClient.MirrorTheme, mgr: AppWidgetManager?, wHost: AppWidgetHost?, ratioFn: (Int) -> Int,
    ) {
        box.removeAllViews(); box.clipChildren = true; box.clipToPadding = true
        if (widgetId > 0 && (availW < ctx.dp(90f) || availH < ctx.dp(72f))) {
            addPlaceholder(ctx, box, theme, true, "空间较窄\n未挂载小组件", 11f); return
        }
        if (widgetId > 0 && mgr != null && wHost != null) {
            try {
                val info = mgr.getAppWidgetInfo(widgetId)
                if (info != null) {
                    val hv = wHost.createView(ctx, widgetId, info).apply { setAppWidget(widgetId, info) }
                    val density = max(0.5f, ctx.resources.displayMetrics.density)
                    val ratio = when (ratioFn(slot)) {
                        1 -> 1.0f; 2 -> 0.75f; 3 -> 16f / 9f; 4 -> 0f
                        else -> when {
                            Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0 ->
                                info.targetCellWidth / info.targetCellHeight.toFloat()
                            info.minWidth > 0 && info.minHeight > 0 -> info.minWidth / info.minHeight.toFloat()
                            else -> 1.15f
                        }.coerceIn(0.45f, 2.4f)
                    }
                    var fitW = availW; var fitH = availH
                    if (ratio > 0.01f) {
                        fitH = (fitW / ratio).roundToInt()
                        if (fitH > availH) { fitH = availH; fitW = (fitH * ratio).roundToInt() }
                        fitW = max(min(availW, ctx.dp(88f)), min(availW, fitW))
                        fitH = max(min(availH, ctx.dp(72f)), min(availH, fitH))
                    }
                    val dpFitW = max(60, (fitW / density).roundToInt()); val dpFitH = max(60, (fitH / density).roundToInt())
                    try {
                        val opts = Bundle().apply {
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, dpFitW)
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, dpFitW)
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, dpFitH)
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, dpFitH)
                            if (Build.VERSION.SDK_INT >= 31) putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(dpFitW.toFloat(), dpFitH.toFloat())))
                        }
                        hv.updateAppWidgetOptions(opts)
                    } catch (t: Throwable) {
                        Log.w(TAG, "updateAppWidgetOptions failed for widgetId=$widgetId", t)
                    }
                    hv.background = roundedBg(theme.widgetBgColor, ctx.dpF(12f), 1, theme.cardStrokeColor)
                    hv.clipChildren = true; hv.clipToPadding = true; hv.clipToOutline = true; hv.setPadDp(4f)
                    box.addView(hv, ctx.flp(fitW, fitH, Gravity.CENTER))
                    return
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to mount AppWidget id=$widgetId slot=$slot", t)
            }
        }
        addPlaceholder(
            ctx, box, theme, compact,
            if (compact) "系统小组件\n可在控制台绑定" else "暂无绑定系统小组件\n可在 OpalDesk 控制台\n添加天气/时钟/日历等小组件",
            if (compact) 11.5f else 13f,
        )
    }

    private fun addPlaceholder(ctx: Context, box: FrameLayout, theme: BridgeClient.MirrorTheme, compact: Boolean, text: String, sp: Float) {
        val card = MirrorPresetCardFactory.makeCardContainer(ctx, theme, compact).apply {
            gravity = Gravity.CENTER
            addView(MirrorPresetCardFactory.makeLabel(ctx, text, sp, theme.subColor).apply { gravity = Gravity.CENTER })
        }
        box.addView(card, ctx.flp(MATCH, WRAP, Gravity.TOP))
    }
}

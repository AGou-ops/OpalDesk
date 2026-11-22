package io.github.lengsukq.opaldesk.overlay.mirror

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.HyperUi
import io.github.lengsukq.opaldesk.design.iosglass.LiquidGlassDrawable
import io.github.lengsukq.opaldesk.overlay.*
import java.util.Locale

internal data class MirrorCardState(
    val batteryPct: Int,
    val batteryCharging: Boolean,
    val sys: BridgeClient.SysStatus,
    val telemetry: BridgeClient.MirrorTelemetry,
    val metrics: MirrorLocalMetrics,
    val volPct: Int,
    val volLevel: Int,
    val memoText: String,
    val pomoRemainSec: Long,
    val pomoRunning: Boolean,
    val theme: BridgeClient.MirrorTheme,
    val resolvePkgLabel: (String?) -> String,
)

internal interface MirrorCard {
    val key: String
    val view: View
    fun bind(state: MirrorCardState)
}

internal interface MirrorCardActions {
    fun adjustVolume(direction: Int)
    fun sendMediaKey(keyCode: Int)
    fun togglePomodoro()
    fun resetPomodoro()
    fun toggleScreenDim()
    fun switchToDex()
    fun cycleSideAlign()
    fun toggleInput()
}

/**
 * Apple-style Liquid Glass widget & telemetry card factory shared across the desktop grid
 * and Native Mirror wing panels.
 */
internal object MirrorPresetCardFactory {

    private fun tintAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    fun makeCardContainer(ctx: Context, theme: BridgeClient.MirrorTheme, compact: Boolean): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = ctx.dpF(if (compact) 8f else 10f)
            LiquidGlassDrawable.applyToView(
                view = this,
                fillColor = theme.cardBgColor,
                cornerRadiusPx = ctx.dpF(if (compact) 21f else 24f),
                strokeWidthPx = ctx.dpF(1.05f),
                darkMode = !theme.lightMode,
                clipOutline = true,
            )
            setPadDp(if (compact) 14f else 16f, if (compact) 11f else 14f)
        }

    fun makeLabel(ctx: Context, text: String, sp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) paint.isFakeBoldText = true
        }

    private fun makeIconMedallion(
        ctx: Context,
        theme: BridgeClient.MirrorTheme,
        iconKind: Int,
        tint: Int,
        sizeDp: Float,
        iconDp: Float,
        cornerDp: Float = sizeDp * 0.5f,
    ): Pair<FrameLayout, VectorIconHolder> {
        val holder = VectorIconHolder(ctx, iconKind, tint, iconDp)
        val fill = tintAlpha(tint, if (theme.lightMode) 0x22 else 0x34)
        val stroke = tintAlpha(tint, if (theme.lightMode) 0x30 else 0x48)
        val box = FrameLayout(ctx).apply {
            background = roundedBg(fill, ctx.dpF(cornerDp), ctx.dp(0.75f).coerceAtLeast(1), stroke)
            addView(holder.view, ctx.flp(ctx.dp(iconDp), ctx.dp(iconDp), Gravity.CENTER))
        }
        return box to holder
    }

    private fun makeStatusTag(ctx: Context, text: String, fgColor: Int, bgColor: Int): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(fgColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            paint.isFakeBoldText = true
            isSingleLine = true
            gravity = Gravity.CENTER
            setPadDp(7.5f, 2.5f)
            background = roundedBg(bgColor, ctx.dpF(9f))
        }

    private fun makePillBtn(
        ctx: Context,
        theme: BridgeClient.MirrorTheme,
        text: String,
        bgColor: Int,
        fgColor: Int,
        compact: Boolean,
        strokeColor: Int = if (theme.lightMode) 0x160F172A else 0x22FFFFFF,
        onClick: () -> Unit,
    ): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(fgColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 11.2f else 12.2f)
        paint.isFakeBoldText = true
        gravity = Gravity.CENTER
        isSingleLine = true
        background = roundedBg(bgColor, ctx.dpF(if (compact) 11f else 13f), ctx.dp(0.75f).coerceAtLeast(1), strokeColor)
        setPadDp(if (compact) 8f else 11f, if (compact) 5.5f else 7f)
        OverlayUiUtils.attachTactilePress(this, 0.93f)
        setOnClickListener { onClick() }
    }

    private fun setPillBg(btn: TextView, color: Int) {
        (btn.background as? LiquidGlassDrawable)?.baseFillColor = color
        (btn.background as? GradientDrawable)?.setColor(color)
    }

    private fun makeHeaderLabel(
        ctx: Context,
        title: String,
        iconKind: Int,
        color: Int,
        compact: Boolean,
        spC: Float = 11.5f,
        spF: Float = 13f,
    ): TextView = makeLabel(ctx, title, if (compact) spC else spF, color, bold = true).apply {
        HyperUi.setStartIcon(this, iconKind, color, 13f, 5f)
    }

    private fun makeButtonRow(
        ctx: Context,
        topPadDp: Float,
        gapDp: Float = 6f,
        buttons: List<Pair<TextView, Float>>,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadDp(0f, topPadDp, 0f, 0f)
        buttons.forEachIndexed { idx, (btn, w) ->
            addView(btn, ctx.llp(0, WRAP, w, rightDp = if (idx < buttons.lastIndex) gapDp else 0f))
        }
    }

    fun build(ctx: Context, key: String, compact: Boolean, theme: BridgeClient.MirrorTheme, actions: MirrorCardActions): MirrorCard =
        when (key) {
            "battery" -> buildBatteryCard(ctx, compact, theme)
            "wifi" -> buildWifiCard(ctx, compact, theme)
            "bt" -> buildBtCard(ctx, compact, theme)
            "audio" -> buildAudioCard(ctx, compact, theme, actions)
            "media" -> buildMediaCard(ctx, compact, theme, actions)
            "calendar" -> buildCalendarCard(ctx, compact, theme)
            "perf" -> buildPerfCard(ctx, compact, theme)
            "netspeed" -> buildNetCard(ctx, compact, theme)
            "storage" -> buildStorageCard(ctx, compact, theme)
            "pomodoro" -> buildPomodoroCard(ctx, compact, theme, actions)
            "memo" -> buildMemoCard(ctx, compact, theme)
            "quickctrl" -> buildQuickCtrlCard(ctx, compact, theme, actions)
            "screendim" -> buildScreenDimCard(ctx, compact, theme, actions)
            else -> buildClockCard(ctx, compact, theme)
        }

    // ---- 1. Clock (2×1 editorial display) ----

    @Suppress("RtlHardcoded")
    private fun buildClockCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme): MirrorCard {
        val card = makeCardContainer(ctx, theme, compact).apply {
            gravity = Gravity.CENTER
            addView(TextClock(ctx).apply {
                format24Hour = "HH:mm"
                format12Hour = "HH:mm"
                setTextColor(theme.titleColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 38f else 44f)
                paint.isFakeBoldText = true
                letterSpacing = -0.025f
                includeFontPadding = false
                gravity = Gravity.CENTER
            }, ctx.llp(MATCH, WRAP))
            addView(TextClock(ctx).apply {
                format24Hour = "M月d日 · EEEE"
                format12Hour = "M月d日 · EEEE"
                setTextColor(theme.subColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 11.8f else 13.5f)
                gravity = Gravity.CENTER
                setPadDp(0f, 3f, 0f, 0f)
            }, ctx.llp(MATCH, WRAP))
        }
        return BoundCard("clock", card) {}
    }

    // ---- 2. Battery (2×1 gauge + status chip) ----

    private fun buildBatteryCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme): MirrorCard {
        val healthyColor = if (theme.lightMode) 0xFF16A34A.toInt() else 0xFF30D158.toInt()
        val icon = MiniBatteryView(ctx)
        val pctLabel = makeLabel(ctx, "100%", if (compact) 22f else 25f, theme.titleColor, bold = true).apply {
            letterSpacing = -0.02f
            includeFontPadding = false
        }
        val statusPill = makeStatusTag(ctx, "供电中", healthyColor, tintAlpha(healthyColor, if (theme.lightMode) 0x1D else 0x30))
        val meter = SmoothMeterView(ctx, theme.lightMode, healthyColor, 100)
        val detail = makeLabel(ctx, "电池供电", if (compact) 11f else 12f, theme.subColor).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadDp(0f, 5f, 0f, 0f)
        }
        val card = makeCardContainer(ctx, theme, compact).apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(icon, ctx.llp(ctx.dp(24f), ctx.dp(13f), rightDp = 8f))
                addView(pctLabel, ctx.llp(0, WRAP, 1f))
                addView(statusPill, ctx.llp(WRAP, WRAP, leftDp = 6f))
            }, ctx.llp(MATCH, WRAP, bottomDp = 9f))
            addView(meter, ctx.llp(MATCH, ctx.dp(7f)))
            addView(detail, ctx.llp(MATCH, WRAP))
        }
        return BoundCard("battery", card) { st ->
            icon.setBattery(st.batteryPct, st.batteryCharging)
            pctLabel.text = "${st.batteryPct}%"
            val green = if (st.theme.lightMode) 0xFF16A34A.toInt() else 0xFF30D158.toInt()
            val amber = if (st.theme.lightMode) 0xFFD97706.toInt() else 0xFFFF9F0A.toInt()
            val red = if (st.theme.lightMode) 0xFFDC2626.toInt() else 0xFFFF453A.toInt()
            val accent = when {
                st.batteryCharging -> green
                st.batteryPct <= 20 -> red
                st.batteryPct <= 35 -> amber
                else -> green
            }
            meter.setState(st.batteryPct, accent)
            statusPill.text = when {
                st.batteryCharging -> "充电中"
                st.batteryPct <= 20 -> "低电量"
                else -> "电池供电"
            }
            statusPill.setTextColor(accent)
            (statusPill.background as? GradientDrawable)?.setColor(tintAlpha(accent, if (st.theme.lightMode) 0x1D else 0x30))
            detail.text = st.telemetry.battDetail.ifEmpty { if (st.batteryCharging) "电源已连接" else "健康供电中" }
        }
    }

    // ---- 3. Icon Stat Card (Wi-Fi / Bluetooth, 2×1 medallion layout) ----

    private fun buildMedallionStatCard(
        ctx: Context,
        key: String,
        compact: Boolean,
        theme: BridgeClient.MirrorTheme,
        iconKind: Int,
        iconTint: Int,
        initEyebrow: String,
        initMain: String,
        initSub: String,
        binder: (FrameLayout, VectorIconHolder, TextView, TextView, TextView, MirrorCardState) -> Unit,
    ): MirrorCard {
        val medalDp = if (compact) 38f else 42f
        val (badgeBox, iconHolder) = makeIconMedallion(ctx, theme, iconKind, iconTint, medalDp, if (compact) 18f else 20f)
        val eyebrow = makeLabel(ctx, initEyebrow, 10.5f, theme.subColor, bold = true).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val main = makeLabel(ctx, initMain, if (compact) 14.5f else 15.5f, theme.titleColor, bold = true).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadDp(0f, 1.5f, 0f, 0f)
        }
        val sub = makeLabel(ctx, initSub, if (compact) 11.2f else 12.2f, theme.subColor).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadDp(0f, 2f, 0f, 0f)
        }
        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(eyebrow, ctx.llp(MATCH, WRAP))
            addView(main, ctx.llp(MATCH, WRAP))
            addView(sub, ctx.llp(MATCH, WRAP))
        }
        val card = makeCardContainer(ctx, theme, compact).apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(badgeBox, ctx.llp(ctx.dp(medalDp), ctx.dp(medalDp), rightDp = 11f))
                addView(textCol, ctx.llp(0, WRAP, 1f))
            }, ctx.llp(MATCH, WRAP))
        }
        return BoundCard(key, card) { binder(badgeBox, iconHolder, eyebrow, main, sub, it) }
    }

    private fun buildWifiCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme) =
        buildMedallionStatCard(ctx, "wifi", compact, theme, HyperUi.ICON_WIFI, theme.accentColor, "Wi-Fi", "检测中…", "等待状态") { badge, icon, eyebrow, main, sub, st ->
            val on = st.sys.valid && st.sys.wifiOn
            val tint = if (on) st.theme.accentColor else st.theme.subColor
            icon.update(kind = if (on) HyperUi.ICON_WIFI else HyperUi.ICON_WIFI_OFF, tint = tint)
            (badge.background as? GradientDrawable)?.setColor(tintAlpha(tint, if (st.theme.lightMode) 0x20 else 0x30))
            eyebrow.text = if (on) "Wi-Fi · 已连接" else "Wi-Fi"
            main.text = if (!on) "已关闭" else st.sys.wifiSsid.ifEmpty { "未连接网络" }
            sub.text = st.telemetry.wifiDetail.ifEmpty { if (on) "信号稳定" else "在系统设置中开启" }
        }

    private fun buildBtCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme): MirrorCard {
        val activeTint = if (theme.lightMode) 0xFF4F46E5.toInt() else 0xFF818CF8.toInt()
        return buildMedallionStatCard(ctx, "bt", compact, theme, HyperUi.ICON_BLUETOOTH, activeTint, "蓝牙", "未启用", "无已连外设") { badge, icon, eyebrow, main, sub, st ->
            val on = st.sys.valid && st.sys.btOn
            val tint = if (on) activeTint else st.theme.subColor
            icon.update(tint = tint)
            (badge.background as? GradientDrawable)?.setColor(tintAlpha(tint, if (st.theme.lightMode) 0x1E else 0x2E))
            eyebrow.text = if (on) "蓝牙 · 已开启" else "蓝牙"
            main.text = if (!on) "已关闭" else st.sys.btName.ifEmpty { "等待外设连接" }
            sub.text = if (!on) "未启用" else if (st.sys.btName.isNotEmpty()) "音频 / 外设已就绪" else "可配对键盘、鼠标或耳机"
        }
    }

    // ---- 4. Audio Control (3×1 sleek Control Center capsule) ----

    private fun buildAudioCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme, actions: MirrorCardActions): MirrorCard {
        val (badgeBox, iconHolder) = makeIconMedallion(ctx, theme, HyperUi.ICON_VOLUME_HIGH, theme.accentColor, 28f, 15f)
        val route = makeLabel(ctx, "平板扬声器", if (compact) 13.5f else 14.5f, theme.titleColor, bold = true).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val pctPill = makeStatusTag(ctx, "50%", theme.accentColor, tintAlpha(theme.accentColor, if (theme.lightMode) 0x1A else 0x2E))
        val meter = SmoothMeterView(ctx, theme.lightMode, theme.accentColor, 50)
        val card = makeCardContainer(ctx, theme, compact).apply {
            setPadDp(if (compact) 14f else 16f, if (compact) 10f else 13f)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(badgeBox, ctx.llp(ctx.dp(26f), ctx.dp(26f), rightDp = 8f))
                addView(route, ctx.llp(0, WRAP, 1f))
                addView(pctPill, ctx.llp(WRAP, WRAP, leftDp = 6f))
            }, ctx.llp(MATCH, WRAP, bottomDp = 6f))
            addView(meter, ctx.llp(MATCH, ctx.dp(6.5f)))
            addView(makeButtonRow(ctx, 7f, gapDp = 6f, buttons = listOf(
                makePillBtn(ctx, theme, "− 减小", theme.pillBgColor, theme.titleColor, compact) { actions.adjustVolume(AudioManager.ADJUST_LOWER) } to 1f,
                makePillBtn(ctx, theme, "静音", theme.pillBgColor, theme.titleColor, compact) { actions.adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE) } to 0.85f,
                makePillBtn(ctx, theme, "+ 增大", theme.pillBgColor, theme.titleColor, compact) { actions.adjustVolume(AudioManager.ADJUST_RAISE) } to 1f,
            )))
        }
        return BoundCard("audio", card) { st ->
            route.text = st.telemetry.audioRoute.ifEmpty { "平板扬声器" }
            pctPill.text = "${st.volPct}%"
            meter.setState(st.volPct, st.theme.accentColor)
            iconHolder.update(kind = HyperUi.volumeIconKind(st.volLevel))
        }
    }

    // ---- 5. Media / Now Playing (3×1 Apple Now Playing widget) ----

    private fun buildMediaCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme, actions: MirrorCardActions): MirrorCard {
        val violet = if (theme.lightMode) 0xFF7C3AED.toInt() else 0xFFA78BFA.toInt()
        val (artBox, _) = makeIconMedallion(ctx, theme, HyperUi.ICON_MEDIA, violet, 36f, 18f, cornerDp = 11f)
        val title = makeLabel(ctx, "暂无正在播放的媒体", if (compact) 13.5f else 14.5f, theme.titleColor, bold = true).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val sub = makeLabel(ctx, "媒体控制已就绪", if (compact) 11f else 12f, theme.subColor).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadDp(0f, 1.5f, 0f, 0f)
        }
        val playBg = tintAlpha(theme.accentColor, if (theme.lightMode) 0x20 else 0x36)
        val playStroke = tintAlpha(theme.accentColor, if (theme.lightMode) 0x36 else 0x52)
        val playBtn = makePillBtn(ctx, theme, "播放 / 暂停", playBg, theme.accentColor, compact, playStroke) {
            actions.sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
        val card = makeCardContainer(ctx, theme, compact).apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(artBox, ctx.llp(ctx.dp(36f), ctx.dp(36f), rightDp = 10f))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title, ctx.llp(MATCH, WRAP))
                    addView(sub, ctx.llp(MATCH, WRAP))
                }, ctx.llp(0, WRAP, 1f))
            }, ctx.llp(MATCH, WRAP))
            addView(makeButtonRow(ctx, 9f, gapDp = 6f, buttons = listOf(
                makePillBtn(ctx, theme, "上一曲", theme.pillBgColor, theme.titleColor, compact) { actions.sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) } to 1f,
                playBtn to 1.25f,
                makePillBtn(ctx, theme, "下一曲", theme.pillBgColor, theme.titleColor, compact) { actions.sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) } to 1f,
            )))
        }
        return BoundCard("media", card) { st ->
            val mt = st.telemetry
            if (mt.mediaTitle.isNotEmpty()) {
                val tag = when (mt.mediaState) { 3 -> "正在播放"; 2 -> "已暂停"; else -> "最近播放" }
                val app = st.resolvePkgLabel(mt.mediaPkg)
                title.text = mt.mediaTitle
                val subParts = listOfNotNull(
                    mt.mediaSubtitle.takeIf { it.isNotEmpty() },
                    if (app.isNotEmpty()) "$tag · $app" else tag,
                )
                sub.text = subParts.joinToString(" · ")
                playBtn.text = if (mt.mediaState == 3) "暂停" else "播放"
            } else {
                title.text = "暂无媒体会话"
                sub.text = "播放音乐或视频后可在此快捷切歌"
                playBtn.text = "播放 / 暂停"
            }
        }
    }

    // ---- 6. Meter Cards (Performance / Storage) ----

    private fun buildMeterCard(
        ctx: Context,
        key: String,
        compact: Boolean,
        theme: BridgeClient.MirrorTheme,
        header: String,
        iconKind: Int,
        hdrColor: Int,
        barColor: Int,
        initMain: String,
        initSub: String,
        binder: (TextView, TextView, SmoothMeterView, MirrorCardState) -> Unit,
    ): MirrorCard {
        val (badgeBox, _) = makeIconMedallion(ctx, theme, iconKind, hdrColor, 26f, 14f)
        val hdr = makeLabel(ctx, header, 11.2f, theme.subColor, bold = true).apply { isSingleLine = true }
        val main = makeLabel(ctx, initMain, if (compact) 14.5f else 15.5f, theme.titleColor, bold = true).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadDp(0f, 4f, 0f, 2f)
        }
        val sub = makeLabel(ctx, initSub, if (compact) 11f else 12f, theme.subColor).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadDp(0f, 0f, 0f, 6f)
        }
        val meter = SmoothMeterView(ctx, theme.lightMode, barColor, 40)
        val card = makeCardContainer(ctx, theme, compact).apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(badgeBox, ctx.llp(ctx.dp(26f), ctx.dp(26f), rightDp = 7f))
                addView(hdr, ctx.llp(0, WRAP, 1f))
            }, ctx.llp(MATCH, WRAP))
            addView(main, ctx.llp(MATCH, WRAP))
            addView(sub, ctx.llp(MATCH, WRAP))
            addView(meter, ctx.llp(MATCH, ctx.dp(7f)))
        }
        return BoundCard(key, card) { binder(main, sub, meter, it) }
    }

    private fun buildPerfCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme) =
        buildMeterCard(
            ctx, "perf", compact, theme, "内存与系统负载", HyperUi.ICON_CPU, theme.accentColor,
            if (theme.lightMode) 0xFF16A34A.toInt() else 0xFF30D158.toInt(), "RAM 检测中…", "CPU 核心状态",
        ) { main, sub, meter, st ->
            if (!st.metrics.valid) return@buildMeterCard
            val upMin = SystemClock.elapsedRealtime() / 60000L
            main.text = String.format(Locale.US, "可用 %.1f / %.0f GB", st.metrics.memAvailGb, st.metrics.memTotalGb)
            sub.text = String.format(Locale.US, "已用 %d%% · %d核 · 运行 %dh%02dm", st.metrics.memPct, Runtime.getRuntime().availableProcessors(), upMin / 60L, upMin % 60L)
            meter.setState(st.metrics.memPct, if (st.theme.lightMode) 0xFF16A34A.toInt() else 0xFF30D158.toInt())
        }

    private fun buildStorageCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme): MirrorCard {
        val col = if (theme.lightMode) 0xFFD97706.toInt() else 0xFFFF9F0A.toInt()
        return buildMeterCard(ctx, "storage", compact, theme, "存储与亮度", HyperUi.ICON_STORAGE, col, col, "存储计算中…", "亮度读取中") { main, sub, meter, st ->
            if (!st.metrics.valid) return@buildMeterCard
            main.text = String.format(Locale.US, "剩余 %.1f / %.0f GB", st.metrics.storageAvailGb, st.metrics.storageTotalGb)
            sub.text = "已用 ${st.metrics.storagePct}% · 亮度 ${st.metrics.d0BrightPct}%"
            meter.setState(st.metrics.storagePct, col)
        }
    }

    // ---- 7. Network Speed / Calendar / Memo / Pomodoro / Mirror Utilities ----

    private fun buildNetCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme) =
        buildMedallionStatCard(ctx, "netspeed", compact, theme, HyperUi.ICON_NET, theme.accentColor, "实时网速", "↓ 0 KB/s", "↑ 0 KB/s") { _, _, _, main, sub, st ->
            if (!st.metrics.valid) return@buildMedallionStatCard
            main.text = "↓ ${MirrorMetricsSampler.formatSpeedRate(st.metrics.rxRateBytesPerSec)}"
            sub.text = "↑ ${MirrorMetricsSampler.formatSpeedRate(st.metrics.txRateBytesPerSec)}" + String.format(Locale.US, " · 累计 %.1f MB", st.metrics.rxTotalMb)
        }

    private fun buildCalendarCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme): MirrorCard {
        val col = if (theme.lightMode) 0xFF059669.toInt() else 0xFF34D399.toInt()
        val body = makeLabel(ctx, "近两日暂无日程", if (compact) 12.2f else 13f, theme.subColor).apply {
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadDp(0f, 6f, 0f, 0f)
        }
        val card = makeCardContainer(ctx, theme, compact).apply {
            gravity = Gravity.TOP
            addView(makeHeaderLabel(ctx, "日程日历", HyperUi.ICON_CALENDAR, col, compact, 12f, 13.5f))
            addView(body)
        }
        return BoundCard("calendar", card) { body.text = it.telemetry.calendarText.ifEmpty { "近两日暂无日历日程" } }
    }

    private fun buildMemoCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme): MirrorCard {
        val col = if (theme.lightMode) 0xFF0D9488.toInt() else 0xFF2DD4BF.toInt()
        val body = makeLabel(ctx, "暂无便签内容", if (compact) 12.2f else 13f, theme.subColor).apply {
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
            setPadDp(0f, 6f, 0f, 0f)
        }
        val card = makeCardContainer(ctx, theme, compact).apply {
            gravity = Gravity.TOP
            addView(makeHeaderLabel(ctx, "桌面便签", HyperUi.ICON_MEMO, col, compact))
            addView(body)
        }
        return BoundCard("memo", card) {
            val txt = it.memoText.ifEmpty { "可在控制台编辑快捷桌面便签" }
            if (body.text?.toString() != txt) body.text = txt
        }
    }

    private fun buildPomodoroCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme, actions: MirrorCardActions): MirrorCard {
        val col = if (theme.lightMode) 0xFFDB2777.toInt() else 0xFFF472B6.toInt()
        val timeTv = makeLabel(ctx, "25:00", if (compact) 26f else 30f, theme.titleColor, bold = true).apply {
            letterSpacing = -0.02f
            includeFontPadding = false
            setPadDp(0f, 2f, 0f, 6f)
        }
        val startBg = tintAlpha(col, if (theme.lightMode) 0x1E else 0x34)
        val toggleBtn = makePillBtn(ctx, theme, "开始专注", startBg, col, compact, tintAlpha(col, 0x38)) { actions.togglePomodoro() }
        val resetBtn = makePillBtn(ctx, theme, "重置", theme.pillBgColor, theme.titleColor, compact) { actions.resetPomodoro() }
        val card = makeCardContainer(ctx, theme, compact).apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(makeHeaderLabel(ctx, "专注番茄钟", HyperUi.ICON_TIMER, col, compact), ctx.llp(0, WRAP, 1f))
            })
            addView(timeTv)
            addView(makeButtonRow(ctx, 0f, 6f, listOf(toggleBtn to 1.25f, resetBtn to 0.9f)))
        }
        return BoundCard("pomodoro", card) { st ->
            timeTv.text = MirrorMetricsSampler.formatCountdown(st.pomoRemainSec)
            toggleBtn.text = if (st.pomoRunning) "暂停" else "开始专注"
        }
    }

    private fun buildScreenDimCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme, actions: MirrorCardActions): MirrorCard {
        val col = if (theme.lightMode) 0xFFB45309.toInt() else 0xFFFBBF24.toInt()
        val dimMain = makeLabel(ctx, "主屏状态：正常亮度", if (compact) 11.5f else 12.5f, theme.subColor)
        val dimBtn = makePillBtn(ctx, theme, "最低亮度", theme.pillBgColor, theme.titleColor, compact) { actions.toggleScreenDim() }
        val card = makeCardContainer(ctx, theme, compact).apply {
            addView(makeHeaderLabel(ctx, "主屏最低亮度", HyperUi.ICON_MOON, col, compact))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadDp(0f, 6f, 0f, 0f)
                addView(dimMain, ctx.llp(0, WRAP, 1f))
                addView(dimBtn, ctx.llp(leftDp = 6f))
            })
        }
        return BoundCard("screendim", card) { st ->
            val dimmed = st.sys.valid && st.sys.screenDimmed
            dimMain.text = if (dimmed) "主屏状态：最低亮度" else "主屏状态：正常亮度"
            dimMain.setTextColor(if (dimmed) col else st.theme.subColor)
            dimBtn.text = if (dimmed) "恢复亮度" else "最低亮度"
            setPillBg(dimBtn, if (dimmed) tintAlpha(st.theme.accentColor, 0x24) else st.theme.pillBgColor)
            dimBtn.setTextColor(if (dimmed) st.theme.accentColor else st.theme.titleColor)
        }
    }

    private fun buildQuickCtrlCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme, actions: MirrorCardActions): MirrorCard {
        val quickDimBtn = makePillBtn(ctx, theme, "主屏最低亮度（省电防刺眼）", theme.pillBgColor, theme.titleColor, compact) { actions.toggleScreenDim() }
        val card = makeCardContainer(ctx, theme, compact).apply {
            addView(makeHeaderLabel(ctx, "镜像快捷控制", HyperUi.ICON_SLIDERS, theme.accentColor, compact))
            addView(makeButtonRow(ctx, 7f, 6f, buttons = listOf(
                makePillBtn(ctx, theme, "回 DeX", tintAlpha(theme.accentColor, 0x22), theme.accentColor, compact) { actions.switchToDex() } to 1f,
                makePillBtn(ctx, theme, "换边", theme.pillBgColor, theme.titleColor, compact) { actions.cycleSideAlign() } to 1f,
                makePillBtn(ctx, theme, "切键鼠", theme.pillBgColor, theme.titleColor, compact) { actions.toggleInput() } to 1f,
            )))
            addView(quickDimBtn, ctx.llp(MATCH, WRAP, topDp = 6f))
        }
        return BoundCard("quickctrl", card) { st ->
            val dimmed = st.sys.valid && st.sys.screenDimmed
            quickDimBtn.text = if (dimmed) "主屏已最低亮度 · 点此恢复" else "主屏最低亮度（省电防刺眼）"
            setPillBg(quickDimBtn, if (dimmed) tintAlpha(st.theme.accentColor, 0x24) else st.theme.pillBgColor)
            quickDimBtn.setTextColor(if (dimmed) st.theme.accentColor else st.theme.titleColor)
        }
    }

    /** Custom Apple-style full-pill translucent progress gauge replacing the dated 2dp system ProgressBar. */
    private class SmoothMeterView(
        ctx: Context,
        private val lightMode: Boolean,
        initialFillColor: Int,
        initialProgress: Int,
    ) : View(ctx) {
        private var progressPct = initialProgress.coerceIn(0, 100)
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (lightMode) 0x1A0F172A else 0x26FFFFFF
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = initialFillColor
        }
        private val trackRect = RectF()
        private val fillRect = RectF()

        fun setState(pct: Int, fillColor: Int) {
            val clamped = pct.coerceIn(0, 100)
            if (clamped == progressPct && fillPaint.color == fillColor) return
            progressPct = clamped
            fillPaint.color = fillColor
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val r = h * 0.5f
            trackRect.set(0f, 0f, w, h)
            canvas.drawRoundRect(trackRect, r, r, trackPaint)
            if (progressPct > 0) {
                val fillW = (w * (progressPct / 100f)).coerceAtLeast(h)
                fillRect.set(0f, 0f, fillW.coerceAtMost(w), h)
                canvas.drawRoundRect(fillRect, r, r, fillPaint)
            }
        }
    }

    private class BoundCard(override val key: String, override val view: View, private val onBind: (MirrorCardState) -> Unit) : MirrorCard {
        override fun bind(state: MirrorCardState) = onBind(state)
    }
}

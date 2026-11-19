package com.extbridge.helper.overlay.mirror

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextClock
import android.widget.TextView
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.HyperUi
import com.extbridge.helper.overlay.*
import java.util.Locale

private const val TAG = "ExtMirrorCards"

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
 * Template-based Native Mirror telemetry card factory replacing 14 bespoke card builders,
 * 35 nullable view fields, `refreshMirrorSideViews()`, and `resetMirrorSideChildRefs()`.
 */
internal object MirrorPresetCardFactory {

    fun makeCardContainer(ctx: Context, theme: BridgeClient.MirrorTheme, compact: Boolean): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(theme.cardBgColor, ctx.dpF(if (compact) 11f else 14f), 1, theme.cardStrokeColor)
            setPadDp(if (compact) 9f else 13f, if (compact) 8f else 11f)
        }

    fun makeLabel(ctx: Context, text: String, sp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
            if (bold) paint.isFakeBoldText = true
        }

    private fun makePillBtn(ctx: Context, theme: BridgeClient.MirrorTheme, text: String, bgColor: Int, fgColor: Int, compact: Boolean, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text; setTextColor(fgColor); setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 11.5f else 12.5f)
            paint.isFakeBoldText = true; gravity = Gravity.CENTER; isSingleLine = true
            background = roundedBg(bgColor, ctx.dpF(8f), 1, theme.cardStrokeColor)
            setPadDp(if (compact) 7f else 10f, if (compact) 4f else 6f)
            setOnClickListener { onClick() }
        }

    private fun setPillBg(btn: TextView, color: Int) { (btn.background as? GradientDrawable)?.setColor(color) }

    private fun makeProgressBar(ctx: Context, tintColor: Int, initProgress: Int = 50): ProgressBar =
        ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = initProgress
            try {
                @Suppress("DEPRECATION")
                progressDrawable?.colorFilter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
            } catch (t: Throwable) {
                Log.d(TAG, "ProgressBar tint unavailable: ${t.message}")
            }
        }

    private fun makeHeaderLabel(ctx: Context, title: String, iconKind: Int, color: Int, compact: Boolean, spC: Float = 12f, spF: Float = 13.5f): TextView =
        makeLabel(ctx, title, if (compact) spC else spF, color, bold = true).apply {
            HyperUi.setStartIcon(this, iconKind, color, 13f, 5f)
        }

    private fun makeButtonRow(ctx: Context, topPadDp: Float, gapDp: Float = 4f, buttons: List<Pair<TextView, Float>>): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadDp(0f, topPadDp, 0f, 0f)
            buttons.forEachIndexed { idx, (btn, w) -> addView(btn, ctx.llp(0, WRAP, w, rightDp = if (idx < buttons.lastIndex) gapDp else 0f)) }
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

    @Suppress("RtlHardcoded")
    private fun buildClockCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme): MirrorCard {
        val card = makeCardContainer(ctx, theme, compact).apply {
            val g = if (compact) Gravity.CENTER else Gravity.LEFT
            addView(TextClock(ctx).apply {
                format24Hour = "HH:mm"; format12Hour = "HH:mm"; setTextColor(theme.titleColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 30f else 36f); paint.isFakeBoldText = true; gravity = g
            }, ctx.llp(MATCH, WRAP))
            addView(TextClock(ctx).apply {
                format24Hour = "M/d EEEE"; format12Hour = "M/d EEEE"; setTextColor(theme.subColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 12.5f else 14.5f); gravity = g
            }, ctx.llp(MATCH, WRAP))
        }
        return BoundCard("clock", card) {}
    }

    private fun buildBatteryCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme): MirrorCard {
        val icon = MiniBatteryView(ctx)
        val main = makeLabel(ctx, "100%", if (compact) 15.5f else 17.5f, if (theme.lightMode) 0xFF16A34A.toInt() else 0xFF4ADE80.toInt(), true)
        val sub = makeLabel(ctx, "电池状态读取中", if (compact) 11.5f else 13f, theme.subColor).apply { maxLines = 2; setPadDp(0f, 3f, 0f, 0f) }
        val card = makeCardContainer(ctx, theme, compact).apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(icon, ctx.llp(ctx.dp(if (compact) 24f else 28f), ctx.dp(if (compact) 14f else 16f)))
                addView(main, ctx.llp(leftDp = 7f))
            })
            addView(sub)
        }
        return BoundCard("battery", card) { st ->
            icon.setBattery(st.batteryPct, st.batteryCharging)
            main.text = "${st.batteryPct}%${if (st.batteryCharging) " · 充电中" else ""}"
            val chg = if (st.theme.lightMode) 0xFF16A34A.toInt() else 0xFF4ADE80.toInt()
            val low = if (st.theme.lightMode) 0xFFDC2626.toInt() else 0xFFF87171.toInt()
            main.setTextColor(if (st.batteryCharging) chg else if (st.batteryPct <= 20) low else st.theme.titleColor)
            sub.text = st.telemetry.battDetail.ifEmpty { if (st.batteryCharging) "充电中" else "电池供电" }
        }
    }

    private fun buildIconStatCard(
        ctx: Context, key: String, compact: Boolean, theme: BridgeClient.MirrorTheme,
        iconKind: Int, iconTint: Int, initMain: String, initSub: String,
        binder: (VectorIconHolder, TextView, TextView, MirrorCardState) -> Unit,
    ): MirrorCard {
        val iconDp = if (compact) 18f else 20f
        val iconHolder = VectorIconHolder(ctx, iconKind, iconTint, iconDp)
        val main = makeLabel(ctx, initMain, if (compact) 14f else 15.5f, theme.titleColor, true).apply {
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        }
        val sub = makeLabel(ctx, initSub, if (compact) 11.5f else 13f, theme.subColor).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadDp(0f, 3f, 0f, 0f)
        }
        val card = makeCardContainer(ctx, theme, compact).apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(iconHolder.view, ctx.llp(ctx.dp(iconDp), ctx.dp(iconDp)))
                addView(main, ctx.llp(0, WRAP, 1f, leftDp = 7f))
            })
            addView(sub)
        }
        return BoundCard(key, card) { binder(iconHolder, main, sub, it) }
    }

    private fun buildWifiCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme) =
        buildIconStatCard(ctx, "wifi", compact, theme, HyperUi.ICON_WIFI, theme.accentColor, "Wi-Fi", "检测中") { icon, main, sub, st ->
            val on = st.sys.valid && st.sys.wifiOn
            icon.update(kind = if (on) HyperUi.ICON_WIFI else HyperUi.ICON_WIFI_OFF)
            main.text = if (!on) "Wi-Fi 已关闭" else st.sys.wifiSsid.ifEmpty { "Wi-Fi 未连接" }
            sub.text = st.telemetry.wifiDetail.ifEmpty { if (on) "已连接" else "点击系统设置开启" }
        }

    private fun buildBtCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme): MirrorCard {
        val tint = if (theme.lightMode) 0xFF4F46E5.toInt() else 0xFF818CF8.toInt()
        return buildIconStatCard(ctx, "bt", compact, theme, HyperUi.ICON_BLUETOOTH, tint, "蓝牙", "无已连设备") { icon, main, sub, st ->
            val on = st.sys.valid && st.sys.btOn
            icon.update(tint = if (on) tint else (tint and 0x66FFFFFF))
            main.text = if (on) "蓝牙已开启" else "蓝牙已关闭"
            sub.text = if (!on) "未启用" else st.sys.btName.ifEmpty { "暂未连接音频/外设" }
        }
    }

    private fun buildAudioCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme, actions: MirrorCardActions): MirrorCard {
        val iconDp = if (compact) 18f else 20f
        val iconHolder = VectorIconHolder(ctx, HyperUi.ICON_VOLUME_HIGH, theme.accentColor, iconDp)
        val route = makeLabel(ctx, "音频通道", if (compact) 13.5f else 15f, theme.titleColor, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val sub = makeLabel(ctx, "媒体音量", if (compact) 11.5f else 12.5f, theme.subColor).apply { setPadDp(0f, 4f, 0f, 4f) }
        val prog = makeProgressBar(ctx, theme.accentColor)
        val card = makeCardContainer(ctx, theme, compact).apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(iconHolder.view, ctx.llp(ctx.dp(iconDp), ctx.dp(iconDp)))
                addView(route, ctx.llp(0, WRAP, 1f, leftDp = 7f))
            })
            addView(sub)
            addView(prog, ctx.llp(MATCH, ctx.dp(6f)))
            addView(makeButtonRow(ctx, 6f, buttons = listOf(
                makePillBtn(ctx, theme, "音量 -", theme.pillBgColor, theme.titleColor, compact) { actions.adjustVolume(AudioManager.ADJUST_LOWER) } to 1f,
                makePillBtn(ctx, theme, "静音", theme.pillBgColor, theme.titleColor, compact) { actions.adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE) } to 1f,
                makePillBtn(ctx, theme, "音量 +", theme.pillBgColor, theme.titleColor, compact) { actions.adjustVolume(AudioManager.ADJUST_RAISE) } to 1f,
            )))
        }
        return BoundCard("audio", card) { st ->
            route.text = st.telemetry.audioRoute.ifEmpty { "平板扬声器" }
            prog.progress = st.volPct
            iconHolder.update(kind = HyperUi.volumeIconKind(st.volLevel))
            sub.text = st.telemetry.audioDetail.ifEmpty { "媒体音量 ${st.volPct}%" }
        }
    }

    private fun buildMediaCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme, actions: MirrorCardActions): MirrorCard {
        val col = if (theme.lightMode) 0xFF7C3AED.toInt() else 0xFFA78BFA.toInt()
        val badge = makeHeaderLabel(ctx, "媒体播放", HyperUi.ICON_MEDIA, col, compact)
        val title = makeLabel(ctx, "当前暂无播放媒体", if (compact) 13.5f else 15f, theme.titleColor, true).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadDp(0f, 3f, 0f, 0f)
        }
        val sub = makeLabel(ctx, "", if (compact) 11.5f else 12.5f, theme.subColor).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadDp(0f, 2f, 0f, 0f)
        }
        val card = makeCardContainer(ctx, theme, compact).apply {
            addView(badge); addView(title); addView(sub)
            addView(makeButtonRow(ctx, 6f, buttons = listOf(
                makePillBtn(ctx, theme, "上一曲", theme.pillBgColor, theme.titleColor, compact) { actions.sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) } to 1f,
                makePillBtn(ctx, theme, "播放/暂停", HyperUi.HYPER_BLUE, 0xFFF8FAFC.toInt(), compact) { actions.sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) } to 1.25f,
                makePillBtn(ctx, theme, "下一曲", theme.pillBgColor, theme.titleColor, compact) { actions.sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) } to 1f,
            )))
        }
        return BoundCard("media", card) { st ->
            val mt = st.telemetry
            if (mt.mediaTitle.isNotEmpty()) {
                val tag = when (mt.mediaState) { 3 -> "正在播放"; 2 -> "已暂停"; else -> "最近播放" }
                val app = st.resolvePkgLabel(mt.mediaPkg)
                badge.text = if (app.isEmpty()) tag else "$tag · $app"
                title.text = mt.mediaTitle
                sub.text = mt.mediaSubtitle
                sub.visibility = if (mt.mediaSubtitle.isEmpty()) View.GONE else View.VISIBLE
            } else {
                badge.text = "媒体播放"; title.text = "当前暂无媒体会话"; sub.visibility = View.GONE
            }
        }
    }

    private fun buildMeterCard(
        ctx: Context, key: String, compact: Boolean, theme: BridgeClient.MirrorTheme,
        header: String, iconKind: Int, hdrColor: Int, barColor: Int, initMain: String, initSub: String,
        binder: (TextView, TextView, ProgressBar, MirrorCardState) -> Unit,
    ): MirrorCard {
        val main = makeLabel(ctx, initMain, if (compact) 14f else 15.5f, theme.titleColor, true).apply { setPadDp(0f, 3f, 0f, 0f) }
        val sub = makeLabel(ctx, initSub, if (compact) 11.5f else 12.5f, theme.subColor).apply { setPadDp(0f, 2f, 0f, 4f) }
        val prog = makeProgressBar(ctx, barColor, 40)
        val card = makeCardContainer(ctx, theme, compact).apply {
            addView(makeHeaderLabel(ctx, header, iconKind, hdrColor, compact))
            addView(main); addView(sub); addView(prog, ctx.llp(MATCH, ctx.dp(6f)))
        }
        return BoundCard(key, card) { binder(main, sub, prog, it) }
    }

    private fun buildPerfCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme) =
        buildMeterCard(
            ctx, "perf", compact, theme, "内存与系统负载", HyperUi.ICON_CPU, theme.accentColor,
            if (theme.lightMode) 0xFF16A34A.toInt() else 0xFF22C55E.toInt(), "RAM 检测中…", "CPU 核心状态",
        ) { main, sub, prog, st ->
            if (!st.metrics.valid) return@buildMeterCard
            val upMin = SystemClock.elapsedRealtime() / 60000L
            main.text = String.format(Locale.US, "可用 %.1f / %.0f GB", st.metrics.memAvailGb, st.metrics.memTotalGb)
            sub.text = String.format(Locale.US, "已用 %d%% · %d核心 · 运行 %dh%02dm", st.metrics.memPct, Runtime.getRuntime().availableProcessors(), upMin / 60L, upMin % 60L)
            prog.progress = st.metrics.memPct
        }

    private fun buildStorageCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme): MirrorCard {
        val col = if (theme.lightMode) 0xFFD97706.toInt() else 0xFFFBBF24.toInt()
        return buildMeterCard(ctx, "storage", compact, theme, "存储与屏幕亮度", HyperUi.ICON_STORAGE, col, col, "存储计算中…", "亮度读取中") { main, sub, prog, st ->
            if (!st.metrics.valid) return@buildMeterCard
            main.text = String.format(Locale.US, "剩 %.1f GB (总 %.0fG)", st.metrics.storageAvailGb, st.metrics.storageTotalGb)
            sub.text = "已用 ${st.metrics.storagePct}% · 主屏亮度 ${st.metrics.d0BrightPct}%"
            prog.progress = st.metrics.storagePct
        }
    }

    private fun buildNetCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme): MirrorCard {
        val main = makeLabel(ctx, "↓ 0 KB/s · ↑ 0 KB/s", if (compact) 13.5f else 15f, theme.titleColor, true).apply { setPadDp(0f, 3f, 0f, 0f) }
        val sub = makeLabel(ctx, "实时流量统计中", if (compact) 11.5f else 12.5f, theme.subColor).apply { setPadDp(0f, 2f, 0f, 0f) }
        val card = makeCardContainer(ctx, theme, compact).apply {
            addView(makeHeaderLabel(ctx, "实时网速监控", HyperUi.ICON_NET, theme.accentColor, compact)); addView(main); addView(sub)
        }
        return BoundCard("netspeed", card) { st ->
            if (!st.metrics.valid) return@BoundCard
            main.text = "↓ ${MirrorMetricsSampler.formatSpeedRate(st.metrics.rxRateBytesPerSec)}"
            sub.text = "↑ ${MirrorMetricsSampler.formatSpeedRate(st.metrics.txRateBytesPerSec)}" + String.format(Locale.US, " · 累计 %.1f MB", st.metrics.rxTotalMb)
        }
    }

    private fun buildCalendarCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme): MirrorCard {
        val col = if (theme.lightMode) 0xFF059669.toInt() else 0xFF34D399.toInt()
        val body = makeLabel(ctx, "近两日暂无日程", if (compact) 12f else 13f, theme.subColor).apply {
            maxLines = if (compact) 3 else 4; ellipsize = TextUtils.TruncateAt.END; setPadDp(0f, 3f, 0f, 0f)
        }
        val card = makeCardContainer(ctx, theme, compact).apply {
            addView(makeHeaderLabel(ctx, "日程日历", HyperUi.ICON_CALENDAR, col, compact, 12.5f, 14f)); addView(body)
        }
        return BoundCard("calendar", card) { body.text = it.telemetry.calendarText.ifEmpty { "近两日暂无日历日程" } }
    }

    private fun buildMemoCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme): MirrorCard {
        val col = if (theme.lightMode) 0xFF0D9488.toInt() else 0xFF2DD4BF.toInt()
        val body = makeLabel(ctx, "", if (compact) 12f else 13f, theme.subColor).apply {
            maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setPadDp(0f, 4f, 0f, 0f)
        }
        val card = makeCardContainer(ctx, theme, compact).apply {
            addView(makeHeaderLabel(ctx, "快捷桌面便签", HyperUi.ICON_MEMO, col, compact)); addView(body)
        }
        return BoundCard("memo", card) { if (body.text?.toString() != it.memoText) body.text = it.memoText }
    }

    private fun buildPomodoroCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme, actions: MirrorCardActions): MirrorCard {
        val col = if (theme.lightMode) 0xFFDB2777.toInt() else 0xFFF472B6.toInt()
        val timeTv = makeLabel(ctx, "25:00", if (compact) 24f else 28f, theme.titleColor, true).apply { setPadDp(0f, 2f, 0f, 4f) }
        val toggleBtn = makePillBtn(ctx, theme, "开始专注", HyperUi.HYPER_BLUE, Color.WHITE, compact) { actions.togglePomodoro() }
        val resetBtn = makePillBtn(ctx, theme, "重置 25m", theme.pillBgColor, theme.titleColor, compact) { actions.resetPomodoro() }
        val card = makeCardContainer(ctx, theme, compact).apply {
            addView(makeHeaderLabel(ctx, "专注番茄钟", HyperUi.ICON_TIMER, col, compact)); addView(timeTv)
            addView(makeButtonRow(ctx, 0f, 5f, listOf(toggleBtn to 1.2f, resetBtn to 1f)))
        }
        return BoundCard("pomodoro", card) { st ->
            timeTv.text = MirrorMetricsSampler.formatCountdown(st.pomoRemainSec)
            toggleBtn.text = if (st.pomoRunning) "暂停" else "开始专注"
        }
    }

    private fun buildScreenDimCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme, actions: MirrorCardActions): MirrorCard {
        val col = if (theme.lightMode) 0xFFB45309.toInt() else 0xFFFBBF24.toInt()
        val dimMain = makeLabel(ctx, "主屏状态：正常亮度", if (compact) 11.5f else 12.5f, theme.subColor)
        val dimBtn = makePillBtn(ctx, theme, "最低亮度", 0xFF334155.toInt(), Color.WHITE, compact) { actions.toggleScreenDim() }
        val card = makeCardContainer(ctx, theme, compact).apply {
            addView(makeHeaderLabel(ctx, "主屏最低亮度", HyperUi.ICON_MOON, col, compact))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadDp(0f, 4f, 0f, 0f)
                addView(dimMain, ctx.llp(0, WRAP, 1f)); addView(dimBtn, ctx.llp(leftDp = 6f))
            })
        }
        return BoundCard("screendim", card) { st ->
            val dimmed = st.sys.valid && st.sys.screenDimmed
            dimMain.text = if (dimmed) "主屏状态：最低亮度" else "主屏状态：正常亮度"
            dimMain.setTextColor(if (dimmed) (if (st.theme.lightMode) 0xFFB45309.toInt() else 0xFFFDE047.toInt()) else st.theme.subColor)
            dimBtn.text = if (dimmed) "恢复亮度" else "最低亮度"
            setPillBg(dimBtn, if (dimmed) HyperUi.HYPER_BLUE else 0xFF334155.toInt())
        }
    }

    private fun buildQuickCtrlCard(ctx: Context, compact: Boolean, theme: BridgeClient.MirrorTheme, actions: MirrorCardActions): MirrorCard {
        val quickDimBtn = makePillBtn(ctx, theme, "主屏最低亮度（省电防刺眼）", 0xFF334155.toInt(), Color.WHITE, compact) { actions.toggleScreenDim() }
        val card = makeCardContainer(ctx, theme, compact).apply {
            addView(makeHeaderLabel(ctx, "镜像快捷控制", HyperUi.ICON_SLIDERS, theme.accentColor, compact))
            addView(makeButtonRow(ctx, 6f, buttons = listOf(
                makePillBtn(ctx, theme, "回 DeX", HyperUi.HYPER_BLUE, Color.WHITE, compact) { actions.switchToDex() } to 1f,
                makePillBtn(ctx, theme, "换边", theme.accentBtnColor, Color.WHITE, compact) { actions.cycleSideAlign() } to 1f,
                makePillBtn(ctx, theme, "切键鼠", 0xFF0F766E.toInt(), Color.WHITE, compact) { actions.toggleInput() } to 1f,
            )))
            addView(quickDimBtn, ctx.llp(MATCH, WRAP, topDp = 5f))
        }
        return BoundCard("quickctrl", card) { st ->
            val dimmed = st.sys.valid && st.sys.screenDimmed
            quickDimBtn.text = if (dimmed) "主屏已最低亮度 · 点此恢复" else "主屏最低亮度（省电防刺眼）"
            setPillBg(quickDimBtn, if (dimmed) HyperUi.HYPER_BLUE else 0xFF334155.toInt())
        }
    }

    private class BoundCard(override val key: String, override val view: View, private val onBind: (MirrorCardState) -> Unit) : MirrorCard {
        override fun bind(state: MirrorCardState) = onBind(state)
    }
}

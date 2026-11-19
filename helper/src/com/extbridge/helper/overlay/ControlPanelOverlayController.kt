package com.extbridge.helper.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.HyperUi
import kotlin.math.max
import kotlin.math.min

/**
 * Manages the HyperOS / Win11-style Quick Settings Control Center flyout overlay
 * on the external VirtualDisplay (Wi-Fi/BT/Input/Airplane/Touchpad/PowerSave tiles,
 * volume slider, output mode chips, rotation/split/dock/blackout controls).
 */
class ControlPanelOverlayController(private val host: OverlayHost) {

    var panelRoot: FrameLayout? = null
        private set
    var panelCard: LinearLayout? = null
        private set
    var panelLp: WindowManager.LayoutParams? = null
        private set
    var isPanelOpen: Boolean = false
        private set

    private var tileWifi: QuickTile? = null
    private var tileBt: QuickTile? = null
    private var tileInput: QuickTile? = null
    private var tileAirplane: QuickTile? = null
    private var tileTouchpad: QuickTile? = null
    private var tilePowerSave: QuickTile? = null
    private var volSpeakerIcon: MiniIconView? = null
    private var volSeekBar: SeekBar? = null
    private var seekingVol: Boolean = false
    private var panelBattView: MiniBatteryView? = null
    private var panelBattText: TextView? = null
    private var btnPanelFullscreen: TextView? = null
    private var btnPanelDex: TextView? = null
    private var btnPanelMirror: TextView? = null
    private var btnPanelRotate: TextView? = null
    private var btnPanelSplit: TextView? = null
    private var btnPanelHideDock: TextView? = null
    private var btnPanelBlackout: TextView? = null

    private val outsideDismissTask = Runnable {
        val now = SystemClock.uptimeMillis()
        if (isPanelOpen && !host.isTrayPressed() &&
            (now - host.lastTrayUpMs()) > 220L &&
            (now - host.lastTrayToggleMs()) > 220L
        ) {
            setPanelOpen(false)
        }
    }

    fun cancelOutsideDismiss() {
        host.handler.removeCallbacks(outsideDismissTask)
    }

    fun resetClosedVisualsBeforeHostSwap() {
        if (!isPanelOpen) {
            panelCard?.let { card ->
                card.animate().cancel()
                card.alpha = 1f
                card.translationY = 0f
                card.scaleX = 1f
                card.scaleY = 1f
            }
            panelRoot?.visibility = View.GONE
        }
    }

    fun buildPanelOverlay(ctx: Context, st: BridgeClient.Status) {
        val root = FrameLayout(ctx).apply {
            clipToPadding = false
            clipChildren = false
            setPadding(
                OverlayUiUtils.dp(ctx, 8f),
                OverlayUiUtils.dp(ctx, 14f),
                OverlayUiUtils.dp(ctx, 8f),
                OverlayUiUtils.dp(ctx, 8f),
            )
        }
        panelRoot = root

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            elevation = OverlayUiUtils.dp(ctx, 18f).toFloat()
            val panelBg = GradientDrawable().apply {
                setColor(HyperUi.FLYOUT_BG)
                cornerRadius = OverlayUiUtils.dp(ctx, 22f).toFloat()
                setStroke(1, HyperUi.FLYOUT_STROKE)
            }
            background = panelBg
        }
        panelCard = card

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                OverlayUiUtils.dp(ctx, 16f),
                OverlayUiUtils.dp(ctx, 16f),
                OverlayUiUtils.dp(ctx, 16f),
                OverlayUiUtils.dp(ctx, 14f),
            )
        }

        // Row 1 of 3x2 grid: Wi-Fi, Bluetooth, Input Target
        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val rowLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = OverlayUiUtils.dp(ctx, 10f) }

        tileWifi = QuickTile(
            ctx, MiniIconView.KIND_WIFI, "Wi-Fi", true,
            { host.execSysAndRefresh(BridgeClient.SYS_WIFI, -1, null) },
            { host.openExtSettings(Settings.ACTION_WIFI_SETTINGS) },
        )
        tileBt = QuickTile(
            ctx, MiniIconView.KIND_BT, "蓝牙", true,
            { host.execSysAndRefresh(BridgeClient.SYS_BT, -1, null) },
            { host.openExtSettings(Settings.ACTION_BLUETOOTH_SETTINGS) },
        )
        tileInput = QuickTile(
            ctx, MiniIconView.KIND_DISPLAY_SWITCH, "外接控制", false,
            {
                host.safeBgExecute {
                    val m = BridgeClient.toggleInput()
                    host.safePost {
                        host.updateInputToggleUi(m)
                        host.pollRemoteStateAsync()
                    }
                }
            },
            null,
        )
        addGridTile(ctx, row1, tileWifi!!.root, true)
        addGridTile(ctx, row1, tileBt!!.root, true)
        addGridTile(ctx, row1, tileInput!!.root, false)
        body.addView(row1, rowLp)

        // Row 2 of 3x2 grid: Airplane, Touchpad, Battery Saver
        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val row2Lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = OverlayUiUtils.dp(ctx, 16f) }

        tileAirplane = QuickTile(
            ctx, MiniIconView.KIND_AIRPLANE, "飞行模式", false,
            { host.execSysAndRefresh(BridgeClient.SYS_AIRPLANE, -1, null) },
            null,
        )
        tileTouchpad = QuickTile(
            ctx, MiniIconView.KIND_TOUCHPAD, "大触摸板", true,
            { host.openTabletTouchpad() },
            { host.openTabletTouchpad() },
        )
        tilePowerSave = QuickTile(
            ctx, MiniIconView.KIND_BATTERY_SAVER, "节能模式", false,
            { host.execSysAndRefresh(BridgeClient.SYS_POWER_SAVE, -1, null) },
            null,
        )
        addGridTile(ctx, row2, tileAirplane!!.root, true)
        addGridTile(ctx, row2, tileTouchpad!!.root, true)
        addGridTile(ctx, row2, tilePowerSave!!.root, false)
        body.addView(row2, row2Lp)

        // Volume Slider row
        val volRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                OverlayUiUtils.dp(ctx, 2f),
                OverlayUiUtils.dp(ctx, 4f),
                OverlayUiUtils.dp(ctx, 2f),
                OverlayUiUtils.dp(ctx, 2f),
            )
        }

        val speakerIcon = MiniIconView(ctx, MiniIconView.KIND_VOLUME, Color.WHITE).apply {
            val pad = OverlayUiUtils.dp(ctx, 6f)
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                host.audioManager?.let { am ->
                    try {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
                    } catch (_: Throwable) {}
                    host.onLocalHardwareRefreshRequested()
                }
            }
        }
        volSpeakerIcon = speakerIcon
        volRow.addView(
            speakerIcon,
            LinearLayout.LayoutParams(OverlayUiUtils.dp(ctx, 28f), OverlayUiUtils.dp(ctx, 28f)),
        )

        val seekBar = SeekBar(ctx).apply {
            @Suppress("DEPRECATION")
            progressDrawable?.colorFilter = PorterDuffColorFilter(HyperUi.HYPER_BLUE, PorterDuff.Mode.SRC_IN)
            @Suppress("DEPRECATION")
            thumb?.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            host.audioManager?.let { am ->
                max = max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        host.audioManager?.let { am ->
                            try {
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                            } catch (_: Throwable) {}
                            host.refreshTrayIconsOnly()
                        }
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    seekingVol = true
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    seekingVol = false
                    host.onLocalHardwareRefreshRequested()
                }
            })
        }
        volSeekBar = seekBar
        val skLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = OverlayUiUtils.dp(ctx, 4f)
            rightMargin = OverlayUiUtils.dp(ctx, 4f)
        }
        volRow.addView(seekBar, skLp)

        val btnAudioMore = TextView(ctx).apply {
            text = "音效"
            setTextColor(0xFFCBD5E1.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                OverlayUiUtils.dp(ctx, 8f),
                OverlayUiUtils.dp(ctx, 4f),
                OverlayUiUtils.dp(ctx, 6f),
                OverlayUiUtils.dp(ctx, 4f),
            )
            HyperUi.setStartIcon(this, HyperUi.ICON_SLIDERS, 0xFFCBD5E1.toInt(), 13f, 4f)
            setOnClickListener { host.openExtSettings(Settings.ACTION_SOUND_SETTINGS) }
        }
        volRow.addView(btnAudioMore)
        body.addView(volRow)

        // Display output mode selector (DeX / Mirror)
        val modeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = OverlayUiUtils.dp(ctx, 10f) }
        }

        btnPanelDex = OverlayUiUtils.makeFooterChip(ctx, "DeX 桌面").apply {
            isSingleLine = true
            gravity = Gravity.CENTER
            setOnClickListener { switchPanelMode(BridgeClient.MIRROR_MODE_DEX) }
        }
        val mp1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = OverlayUiUtils.dp(ctx, 6f)
        }
        modeRow.addView(btnPanelDex, mp1)

        btnPanelMirror = OverlayUiUtils.makeFooterChip(ctx, "原生镜像").apply {
            isSingleLine = true
            gravity = Gravity.CENTER
            setOnClickListener { switchPanelMode(BridgeClient.MIRROR_MODE_MIRROR) }
        }
        modeRow.addView(btnPanelMirror, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        updatePanelModeChips(st.mirrorMode)
        body.addView(modeRow)

        // DeX policy & rotation row
        val dexRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = OverlayUiUtils.dp(ctx, 6f) }
        }

        val initFs = st.fullscreenPolicy == 1
        btnPanelFullscreen = OverlayUiUtils.makeFooterChip(ctx, if (initFs) "桌面横屏: 开" else "桌面横屏: 关").apply {
            isSingleLine = true
            gravity = Gravity.CENTER
            HyperUi.styleHyperChip(this, initFs)
            HyperUi.setStartIcon(this, HyperUi.ICON_FULLSCREEN, if (initFs) Color.WHITE else 0xFFD8DEE9.toInt(), 13f, 5f)
            setOnClickListener {
                host.safeBgExecute {
                    val next = BridgeClient.toggleFullscreenPolicy()
                    host.safePost {
                        Toast.makeText(
                            host.serviceContext,
                            if (next == 1) "已开启：DeX 默认横屏大屏桌面" else "已关闭：DeX 默认横屏锁定",
                            Toast.LENGTH_SHORT,
                        ).show()
                        host.pollRemoteStateAsync()
                    }
                }
            }
        }
        val dp1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = OverlayUiUtils.dp(ctx, 6f)
        }
        dexRow.addView(btnPanelFullscreen, dp1)

        val initRot = st.virtRot != 0
        btnPanelRotate = OverlayUiUtils.makeFooterChip(ctx, "旋转: ${BridgeClient.rotationLabel(st.virtRot)}").apply {
            isSingleLine = true
            gravity = Gravity.CENTER
            HyperUi.styleHyperChip(this, initRot)
            HyperUi.setStartIcon(this, HyperUi.ICON_ROTATE, if (initRot) Color.WHITE else 0xFFD8DEE9.toInt(), 13f, 5f)
            setOnClickListener {
                host.safeBgExecute {
                    val next = BridgeClient.cycleRotation()
                    host.safePost {
                        Toast.makeText(
                            host.serviceContext,
                            "输出旋转：${BridgeClient.rotationLabel(next)}",
                            Toast.LENGTH_SHORT,
                        ).show()
                        host.pollRemoteStateAsync()
                    }
                }
            }
        }
        dexRow.addView(btnPanelRotate, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(dexRow)

        // Split & Dock Auto-Hide row
        val dexRow2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = OverlayUiUtils.dp(ctx, 6f) }
        }

        val initSplit = st.isMultiTilingActive
        btnPanelSplit = OverlayUiUtils.makeFooterChip(ctx, if (initSplit) "退出分屏" else "一键分屏").apply {
            isSingleLine = true
            gravity = Gravity.CENTER
            HyperUi.styleHyperChip(this, initSplit)
            HyperUi.setStartIcon(this, HyperUi.ICON_SPLIT, if (initSplit) Color.WHITE else 0xFFD8DEE9.toInt(), 13f, 5f)
            setOnClickListener {
                setPanelOpen(false)
                if (host.lastStatus.isMultiTilingActive) {
                    host.performExitSplitAnimated()
                } else {
                    host.triggerQuickSplitOrMenu()
                }
            }
        }
        val dp3s = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = OverlayUiUtils.dp(ctx, 6f)
        }
        dexRow2.addView(btnPanelSplit, dp3s)

        val autoHideInit = host.isDockAutoHide()
        btnPanelHideDock = OverlayUiUtils.makeFooterChip(
            ctx,
            if (autoHideInit) "Dock 自动隐藏" else "Dock 固定显示",
        ).apply {
            isSingleLine = true
            gravity = Gravity.CENTER
            HyperUi.styleHyperChip(this, autoHideInit)
            HyperUi.setStartIcon(this, HyperUi.ICON_PIN, if (autoHideInit) Color.WHITE else 0xFFD8DEE9.toInt(), 13f, 5f)
            setOnClickListener {
                val next = !host.isDockAutoHide()
                host.setDockAutoHideEnabled(next)
                Toast.makeText(
                    host.serviceContext,
                    if (next) "已开启 Dock 自动隐藏（鼠标移至底边唤醒）" else "已关闭自动隐藏：Dock 固定显示",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            setOnLongClickListener {
                host.applyDockHidden(true)
                Toast.makeText(host.serviceContext, "Dock 已暂时收起，触碰底边触发条可唤醒", Toast.LENGTH_SHORT).show()
                true
            }
        }
        dexRow2.addView(btnPanelHideDock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(dexRow2)

        // Blackout row
        val dexRow3 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = OverlayUiUtils.dp(ctx, 6f) }
        }
        btnPanelBlackout = OverlayUiUtils.makeFooterChip(
            ctx,
            if (host.isBlackoutOverlayActive()) "主屏已黑屏 · 点此恢复亮屏" else "一键黑屏 (主屏最低亮度)",
        ).apply {
            isSingleLine = true
            gravity = Gravity.CENTER
            setOnClickListener { host.toggleDexBlackout() }
        }
        dexRow3.addView(
            btnPanelBlackout,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        updateBlackoutUi()
        body.addView(dexRow3)

        card.addView(body)

        // Footer bar
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                OverlayUiUtils.dp(ctx, 16f),
                OverlayUiUtils.dp(ctx, 10f),
                OverlayUiUtils.dp(ctx, 14f),
                OverlayUiUtils.dp(ctx, 11f),
            )
            val r = OverlayUiUtils.dp(ctx, 22f).toFloat()
            background = GradientDrawable().apply {
                setColor(0x38000000)
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
        }

        val battView = MiniBatteryView(ctx)
        panelBattView = battView
        val pbvLp = LinearLayout.LayoutParams(OverlayUiUtils.dp(ctx, 22f), OverlayUiUtils.dp(ctx, 13f)).apply {
            rightMargin = OverlayUiUtils.dp(ctx, 7f)
        }
        footer.addView(battView, pbvLp)

        val battText = TextView(ctx).apply {
            setTextColor(0xFFE2E8F0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        panelBattText = battText
        footer.addView(battText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val btnCtrl = OverlayUiUtils.makeFooterChip(ctx, "控制台").apply {
            HyperUi.setStartIcon(this, HyperUi.ICON_SLIDERS, 0xFFD8DEE9.toInt(), 13f, 4f)
            setOnClickListener { host.openTabletMain() }
        }
        val cLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = OverlayUiUtils.dp(ctx, 8f) }
        footer.addView(btnCtrl, cLp)

        val btnSysSet = OverlayUiUtils.makeFooterChip(ctx, "设置").apply {
            HyperUi.setStartIcon(this, HyperUi.ICON_SETTINGS, 0xFFD8DEE9.toInt(), 13f, 4f)
            setOnClickListener { host.openExtSettings(Settings.ACTION_SETTINGS) }
        }
        footer.addView(btnSysSet)

        card.addView(footer)
        root.addView(
            card,
            FrameLayout.LayoutParams(OverlayUiUtils.dp(ctx, 338f), FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        root.visibility = View.GONE
        isPanelOpen = false

        val lp = WindowManager.LayoutParams(
            OverlayUiUtils.dp(ctx, 354f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            host.winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = max(16, st.virtW - OverlayUiUtils.dp(ctx, 366f))
            y = max(16, st.virtH - OverlayUiUtils.dp(ctx, 410f))
        }
        panelLp = lp

        root.setOnTouchListener { _, ev ->
            if (ev != null && ev.actionMasked == MotionEvent.ACTION_OUTSIDE && isPanelOpen) {
                val now = SystemClock.uptimeMillis()
                if (!host.isTrayPressed() &&
                    (now - host.lastTrayUpMs()) > 220L &&
                    (now - host.lastTrayToggleMs()) > 220L
                ) {
                    host.handler.removeCallbacks(outsideDismissTask)
                    host.handler.postDelayed(outsideDismissTask, 70L)
                }
            }
            false
        }
        root.addOnLayoutChangeListener { _, l, t, rt, b, ol, ot, or2, ob ->
            if ((rt - l) != (or2 - ol) || (b - t) != (ob - ot)) {
                repositionPanel()
            }
        }
    }

    private fun addGridTile(ctx: Context, row: LinearLayout, tileRoot: View, marginRight: Boolean) {
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        if (marginRight) lp.rightMargin = OverlayUiUtils.dp(ctx, 10f)
        row.addView(tileRoot, lp)
    }

    private fun switchPanelMode(mode: Int) {
        setPanelOpen(false)
        Toast.makeText(
            host.serviceContext,
            "正在切换为【${BridgeClient.mirrorModeLabel(mode)}】...",
            Toast.LENGTH_SHORT,
        ).show()
        host.safeBgExecute {
            BridgeClient.setMirrorMode(mode)
            host.safePost { host.pollRemoteStateAsync() }
        }
    }

    fun updatePanelModeChips(mirrorMode: Int) {
        val isDex = mirrorMode == BridgeClient.MIRROR_MODE_DEX
        val isMir = mirrorMode == BridgeClient.MIRROR_MODE_MIRROR
        btnPanelDex?.let {
            HyperUi.styleHyperChip(it, isDex)
            HyperUi.setStartIcon(it, HyperUi.ICON_DEX, if (isDex) Color.WHITE else 0xFFD8DEE9.toInt(), 13f, 5f)
        }
        btnPanelMirror?.let {
            HyperUi.styleHyperChip(it, isMir)
            HyperUi.setStartIcon(it, HyperUi.ICON_MIRROR, if (isMir) Color.WHITE else 0xFFD8DEE9.toInt(), 13f, 5f)
        }
    }

    fun updateDockAutoHideUi() {
        val autoHide = host.isDockAutoHide()
        btnPanelHideDock?.let {
            it.text = if (autoHide) "Dock 自动隐藏" else "Dock 固定显示"
            HyperUi.styleHyperChip(it, autoHide)
            HyperUi.setStartIcon(it, HyperUi.ICON_PIN, if (autoHide) Color.WHITE else 0xFFD8DEE9.toInt(), 13f, 5f)
        }
    }

    fun updateBlackoutUi() {
        val active = host.isBlackoutOverlayActive()
        btnPanelBlackout?.let {
            it.text = if (active) "主屏已黑屏 · 点此恢复亮屏" else "一键黑屏 (主屏最低亮度)"
            HyperUi.styleHyperChip(it, active)
            HyperUi.setStartIcon(
                it,
                if (active) HyperUi.ICON_SUN else HyperUi.ICON_MOON,
                if (active) Color.WHITE else 0xFFD8DEE9.toInt(),
                13.5f,
                5f,
            )
        }
    }

    fun updateSplitButton(splitActive: Boolean) {
        btnPanelSplit?.let {
            it.text = if (splitActive) "退出分屏" else "一键分屏"
            HyperUi.styleHyperChip(it, splitActive)
            HyperUi.setStartIcon(it, HyperUi.ICON_SPLIT, if (splitActive) Color.WHITE else 0xFFD8DEE9.toInt(), 13f, 5f)
        }
    }

    fun updateStatusChips(st: BridgeClient.Status) {
        btnPanelFullscreen?.let {
            val fsOn = st.fullscreenPolicy == 1
            it.text = if (fsOn) "桌面横屏: 开" else "桌面横屏: 关"
            HyperUi.styleHyperChip(it, fsOn)
            HyperUi.setStartIcon(it, HyperUi.ICON_FULLSCREEN, if (fsOn) Color.WHITE else 0xFFD8DEE9.toInt(), 13f, 5f)
        }
        btnPanelRotate?.let {
            val rotActive = st.virtRot != 0
            it.text = "旋转: ${BridgeClient.rotationLabel(st.virtRot)}"
            HyperUi.styleHyperChip(it, rotActive)
            HyperUi.setStartIcon(it, HyperUi.ICON_ROTATE, if (rotActive) Color.WHITE else 0xFFD8DEE9.toInt(), 13f, 5f)
        }
        updatePanelModeChips(st.mirrorMode)
        updateDockAutoHideUi()
        updateBlackoutUi()
    }

    fun updateInputToggleUi(inputToExt: Int) {
        tileInput?.setActive(inputToExt == 1, if (inputToExt == 1) "外接扩展屏" else "本机内屏")
    }

    fun refreshLocalHardwareViews(volLevel: Int) {
        volSpeakerIcon?.setVolumeLevel(volLevel)
        val am = host.audioManager
        val sb = volSeekBar
        if (am != null && sb != null && !seekingVol) {
            val maxVol = max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (sb.max != maxVol) sb.max = maxVol
            sb.progress = curVol
        }
        panelBattView?.setBattery(host.batteryPct, host.batteryCharging)
        panelBattText?.text = "${host.batteryPct}%${if (host.batteryCharging) " · 充电中" else ""}"
    }

    fun refreshVolumeIconOnly(volLevel: Int) {
        volSpeakerIcon?.setVolumeLevel(volLevel)
    }

    fun refreshSysViews(sys: BridgeClient.SysStatus) {
        if (!sys.valid) return
        tileWifi?.let {
            val sub = if (sys.wifiOn) sys.wifiSsid.ifEmpty { "已开启" } else "Wi-Fi"
            it.setActive(sys.wifiOn, sub)
        }
        tileBt?.let {
            val sub = if (sys.btOn) sys.btName.ifEmpty { "未连接" } else "蓝牙"
            it.setActive(sys.btOn, sub)
        }
        tileAirplane?.setActive(sys.airplaneOn, "飞行模式")
        tilePowerSave?.setActive(sys.powerSaveOn, "节能模式")
    }

    fun setPanelOpen(open: Boolean) {
        host.handler.removeCallbacks(outsideDismissTask)
        val root = panelRoot
        if (isPanelOpen == open && (root == null || (open && root.visibility == View.VISIBLE))) {
            return
        }
        isPanelOpen = open
        host.updateTrayPillBackground(open)
        if (open) {
            host.setSplitMenuOpen(false)
            host.setTaskBubbleOpen(false)
            host.cancelAutoHideTimer()
            if (host.isDockHidden()) host.applyDockHiddenAnimated(false)
        } else {
            host.evaluateDockAutoHide(false)
        }
        val card = panelCard
        val ctx = host.extCtx
        if (root == null || card == null || ctx == null) return

        card.animate().cancel()
        if (open) {
            root.visibility = View.VISIBLE
            repositionPanel()
            host.onLocalHardwareRefreshRequested()
            host.pollRemoteStateAsync()
            card.alpha = 0f
            card.translationY = OverlayUiUtils.dp(ctx, 18f).toFloat()
            card.scaleX = 0.94f
            card.scaleY = 0.94f
            card.post {
                if (!isPanelOpen) return@post
                val w = card.width
                val h = card.height
                if (w > 0 && h > 0) {
                    card.pivotX = w * 0.85f
                    card.pivotY = h.toFloat()
                }
                card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200L)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .withEndAction { host.bringCursorToFront() }
                    .start()
            }
        } else {
            val w = card.width
            val h = card.height
            if (w > 0 && h > 0) {
                card.pivotX = w * 0.85f
                card.pivotY = h.toFloat()
            }
            card.animate()
                .alpha(0f)
                .translationY(OverlayUiUtils.dp(ctx, 14f).toFloat())
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(150L)
                .setInterpolator(AccelerateInterpolator(1.3f))
                .withEndAction {
                    if (!isPanelOpen) {
                        panelRoot?.visibility = View.GONE
                    }
                }
                .start()
        }
    }

    fun repositionPanel() {
        val root = panelRoot ?: return
        val lp = panelLp ?: return
        val ctx = host.extCtx ?: return
        try {
            var surfW = host.overlayW
            var surfH = host.overlayH
            val lHost = host.launcherHost
            if (host.inLauncherHost && lHost != null) {
                if (lHost.width > 0) surfW = lHost.width
                if (lHost.height > 0) surfH = lHost.height
            }
            val pw = if (root.width > 0) root.width else OverlayUiUtils.dp(ctx, 338f)
            val ph = if (root.height > 0) root.height else OverlayUiUtils.dp(ctx, 320f)

            val dock = host.getDockRoot()
            val dockW = if (dock != null && dock.width > 0) dock.width else OverlayUiUtils.dp(ctx, 520f)
            val dockH = if (dock != null && dock.height > 0) dock.height else OverlayUiUtils.dp(ctx, 46f)
            val dockBottomMargin = OverlayUiUtils.dp(ctx, 10f)
            val anchorRight = if (host.inLauncherHost && dock != null && dock.width > 0) {
                dock.right
            } else {
                (surfW + dockW) / 2
            }
            val anchorTop = if (host.inLauncherHost && dock != null && dock.height > 0) {
                dock.top
            } else {
                surfH - dockBottomMargin - dockH
            }

            val margin = OverlayUiUtils.dp(ctx, 14f)
            val x = min(surfW - pw - margin, max(margin, anchorRight - pw))
            val y = max(margin, anchorTop - ph - OverlayUiUtils.dp(ctx, 10f))
            lp.x = x
            lp.y = y
            if (host.inLauncherHost) {
                val flp = root.layoutParams as? FrameLayout.LayoutParams
                if (flp != null && (flp.leftMargin != x || flp.topMargin != y)) {
                    flp.gravity = Gravity.TOP or Gravity.LEFT
                    flp.leftMargin = x
                    flp.topMargin = y
                    root.layoutParams = flp
                }
            } else {
                host.extWm?.updateViewLayout(root, lp)
            }
        } catch (_: Throwable) {}
    }

    fun detach() {
        host.handler.removeCallbacks(outsideDismissTask)
        host.removeOverlayView(panelRoot)
        panelRoot = null
        panelCard = null
        panelLp = null
        isPanelOpen = false
        btnPanelFullscreen = null
        btnPanelDex = null
        btnPanelMirror = null
        btnPanelRotate = null
        btnPanelSplit = null
        btnPanelHideDock = null
        btnPanelBlackout = null
        tileWifi = null
        tileBt = null
        tileInput = null
        tileAirplane = null
        tileTouchpad = null
        tilePowerSave = null
        volSpeakerIcon = null
        volSeekBar = null
        panelBattView = null
        panelBattText = null
    }
}

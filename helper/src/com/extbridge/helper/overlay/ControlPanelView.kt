package com.extbridge.helper.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.HyperUi
import kotlin.math.max

/**
 * Pure View tree + dirty-checked state binder for the HyperOS Quick Settings Control Center flyout.
 */
internal class ControlPanelView(
    ctx: Context,
    private val host: OverlayHost,
    initialStatus: BridgeClient.Status,
    private val onRequestClose: () -> Unit,
) {
    companion object {
        private const val TAG = "ExtControlPanelView"
        const val CARD_WIDTH_DP = 338f
        const val ROOT_WIDTH_DP = 354f
    }

    val root: FrameLayout
    val card: LinearLayout

    private lateinit var tileWifi: QuickTile
    private lateinit var tileBt: QuickTile
    private lateinit var tileInput: QuickTile
    private lateinit var tileAirplane: QuickTile
    private lateinit var tileTouchpad: QuickTile
    private lateinit var tilePowerSave: QuickTile
    private lateinit var volSpeakerIcon: VectorIconHolder
    private lateinit var volSeekBar: SeekBar
    private var seekingVol = false
    private lateinit var battView: MiniBatteryView
    private lateinit var battText: TextView

    private lateinit var btnDex: TextView
    private lateinit var btnMirror: TextView
    private lateinit var btnFullscreen: TextView
    private lateinit var btnRotate: TextView
    private lateinit var btnSplit: TextView
    private lateinit var btnHideDock: TextView
    private lateinit var btnBlackout: TextView

    init {
        root = FrameLayout(ctx).apply {
            clipToPadding = false; clipChildren = false
            setPadDp(8f, 14f, 8f, 8f)
            visibility = View.GONE
        }
        card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            elevation = ctx.dpF(18f)
            background = roundedBg(HyperUi.FLYOUT_BG, ctx.dpF(22f), 1, HyperUi.FLYOUT_STROKE)
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadDp(16f, 16f, 16f, 14f)
        }
        buildQuickTileGrid(ctx, body)
        body.addView(buildVolumeRow(ctx))
        buildModeAndPolicyChips(ctx, body, initialStatus)
        card.addView(body)
        card.addView(buildFooter(ctx))

        root.addView(card, ctx.flp(ctx.dp(CARD_WIDTH_DP), WRAP))
        updateStatusChips(initialStatus)
        updateSplitButton(initialStatus.isMultiTilingActive)
        updateInputToggle(initialStatus.inputToExt)
    }

    private fun buildQuickTileGrid(ctx: Context, body: LinearLayout) {
        tileWifi = QuickTile(ctx, HyperUi.ICON_WIFI, "Wi-Fi", true,
            { host.execSysAndRefresh(BridgeClient.SYS_WIFI, -1, null) },
            { host.openExtSettings(Settings.ACTION_WIFI_SETTINGS) })
        tileBt = QuickTile(ctx, HyperUi.ICON_BLUETOOTH, "蓝牙", true,
            { host.execSysAndRefresh(BridgeClient.SYS_BT, -1, null) },
            { host.openExtSettings(Settings.ACTION_BLUETOOTH_SETTINGS) })
        tileInput = QuickTile(ctx, HyperUi.ICON_DEX, "外接控制", false, {
            host.safeBgExecute {
                val m = BridgeClient.toggleInput()
                host.safePost { host.updateInputToggleUi(m); host.pollRemoteStateAsync() }
            }
        })
        body.addView(OverlayUiUtils.makeChipRow(ctx, 10f, bottomMarginDp = 10f, items = arrayOf(tileWifi.root, tileBt.root, tileInput.root)))

        tileAirplane = QuickTile(ctx, HyperUi.ICON_AIRPLANE, "飞行模式", false,
            { host.execSysAndRefresh(BridgeClient.SYS_AIRPLANE, -1, null) })
        tileTouchpad = QuickTile(ctx, HyperUi.ICON_TOUCHPAD, "大触摸板", true,
            { host.openTabletTouchpad() }, { host.openTabletTouchpad() })
        tilePowerSave = QuickTile(ctx, HyperUi.ICON_BATTERY_SAVER, "节能模式", false,
            { host.execSysAndRefresh(BridgeClient.SYS_POWER_SAVE, -1, null) })
        body.addView(OverlayUiUtils.makeChipRow(ctx, 10f, bottomMarginDp = 16f, items = arrayOf(tileAirplane.root, tileTouchpad.root, tilePowerSave.root)))
    }

    private fun buildVolumeRow(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadDp(2f, 4f, 2f, 2f)

        volSpeakerIcon = VectorIconHolder(ctx, HyperUi.ICON_VOLUME_HIGH, Color.WHITE, 16f).apply {
            view.setPadDp(6f)
            view.setOnClickListener {
                host.audioManager?.let { am ->
                    try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0) }
                    catch (t: Throwable) { Log.w(TAG, "adjustStreamVolume toggle mute failed", t) }
                    host.onLocalHardwareRefreshRequested()
                }
            }
        }
        addView(volSpeakerIcon.view, ctx.llp(ctx.dp(28f), ctx.dp(28f)))

        volSeekBar = SeekBar(ctx).apply {
            @Suppress("DEPRECATION")
            progressDrawable?.colorFilter = PorterDuffColorFilter(HyperUi.HYPER_BLUE, PorterDuff.Mode.SRC_IN)
            @Suppress("DEPRECATION")
            thumb?.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            host.audioManager?.let { max = max(1, it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    host.audioManager?.let { am ->
                        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0) }
                        catch (t: Throwable) { Log.w(TAG, "setStreamVolume failed", t) }
                        host.refreshTrayIconsOnly()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { seekingVol = true }
                override fun onStopTrackingTouch(sb: SeekBar?) { seekingVol = false; host.onLocalHardwareRefreshRequested() }
            })
        }
        addView(volSeekBar, ctx.llp(0, WRAP, 1f, leftDp = 4f, rightDp = 4f))

        addView(TextView(ctx).apply {
            text = "音效"; setTextColor(0xFFCBD5E1.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER_VERTICAL; setPadDp(8f, 4f, 6f, 4f)
            HyperUi.setStartIcon(this, HyperUi.ICON_SLIDERS, 0xFFCBD5E1.toInt(), 13f, 4f)
            setOnClickListener { host.openExtSettings(Settings.ACTION_SOUND_SETTINGS) }
        })
    }

    private fun buildModeAndPolicyChips(ctx: Context, body: LinearLayout, st: BridgeClient.Status) {
        btnDex = OverlayUiUtils.makeFooterChip(ctx, "DeX 桌面", centered = true).apply {
            setOnClickListener { switchDisplayMode(BridgeClient.MIRROR_MODE_DEX) }
        }
        btnMirror = OverlayUiUtils.makeFooterChip(ctx, "原生镜像", centered = true).apply {
            setOnClickListener { switchDisplayMode(BridgeClient.MIRROR_MODE_MIRROR) }
        }
        body.addView(OverlayUiUtils.makeChipRow(ctx, 6f, topMarginDp = 10f, items = arrayOf(btnDex, btnMirror)))

        btnFullscreen = OverlayUiUtils.makeFooterChip(ctx, "", centered = true).apply {
            setOnClickListener {
                host.safeBgExecute {
                    val next = BridgeClient.toggleFullscreenPolicy()
                    host.safePost {
                        host.serviceContext.toast(if (next == 1) "已开启：DeX 默认横屏大屏桌面" else "已关闭：DeX 默认横屏锁定")
                        host.pollRemoteStateAsync()
                    }
                }
            }
        }
        btnRotate = OverlayUiUtils.makeFooterChip(ctx, "", centered = true).apply {
            setOnClickListener {
                host.safeBgExecute {
                    val next = BridgeClient.cycleRotation()
                    host.safePost {
                        host.serviceContext.toast("输出旋转：${BridgeClient.rotationLabel(next)}")
                        host.pollRemoteStateAsync()
                    }
                }
            }
        }
        body.addView(OverlayUiUtils.makeChipRow(ctx, 6f, topMarginDp = 6f, items = arrayOf(btnFullscreen, btnRotate)))

        btnSplit = OverlayUiUtils.makeFooterChip(ctx, "", centered = true).apply {
            setOnClickListener {
                onRequestClose()
                if (host.lastStatus.isMultiTilingActive) host.split.performExitSplitAnimated()
                else host.split.triggerQuickSplitOrMenu()
            }
        }
        btnHideDock = OverlayUiUtils.makeFooterChip(ctx, "", centered = true).apply {
            setOnClickListener {
                val next = !host.dock.isDockAutoHide
                host.setDockAutoHideEnabled(next)
                host.serviceContext.toast(if (next) "已开启 Dock 自动隐藏（鼠标移至底边唤醒）" else "已关闭自动隐藏：Dock 固定显示")
            }
            setOnLongClickListener {
                host.dock.applyDockHidden(true)
                host.serviceContext.toast("Dock 已暂时收起，触碰底边触发条可唤醒")
                true
            }
        }
        body.addView(OverlayUiUtils.makeChipRow(ctx, 6f, topMarginDp = 6f, items = arrayOf(btnSplit, btnHideDock)))

        btnBlackout = OverlayUiUtils.makeFooterChip(ctx, "", centered = true).apply {
            setOnClickListener { host.blackout.toggleDexBlackout() }
        }
        body.addView(OverlayUiUtils.makeChipRow(ctx, 0f, topMarginDp = 6f, items = arrayOf(btnBlackout)))
        updateModeChips(st.mirrorMode)
    }

    private fun buildFooter(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadDp(16f, 10f, 14f, 11f)
        val r = ctx.dpF(22f)
        background = GradientDrawable().apply { setColor(0x38000000); cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r) }

        battView = MiniBatteryView(ctx)
        addView(battView, ctx.llp(ctx.dp(22f), ctx.dp(13f), rightDp = 7f))

        battText = TextView(ctx).apply { setTextColor(0xFFE2E8F0.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
        addView(battText, ctx.llp(0, WRAP, 1f))

        addView(OverlayUiUtils.makeFooterChip(ctx, "控制台").apply {
            HyperUi.setStartIcon(this, HyperUi.ICON_SLIDERS, 0xFFD8DEE9.toInt(), 13f, 4f)
            setOnClickListener { host.openTabletMain() }
        }, ctx.llp(rightDp = 8f))
        addView(OverlayUiUtils.makeFooterChip(ctx, "设置").apply {
            HyperUi.setStartIcon(this, HyperUi.ICON_SETTINGS, 0xFFD8DEE9.toInt(), 13f, 4f)
            setOnClickListener { host.openExtSettings(Settings.ACTION_SETTINGS) }
        })
    }

    private fun switchDisplayMode(mode: Int) {
        onRequestClose()
        host.serviceContext.toast("正在切换为【${BridgeClient.mirrorModeLabel(mode)}】...")
        host.runDaemonAndPoll { BridgeClient.setMirrorMode(mode) }
    }

    fun updateModeChips(mirrorMode: Int) {
        OverlayUiUtils.styleChipWithIcon(btnDex, mirrorMode == BridgeClient.MIRROR_MODE_DEX, HyperUi.ICON_DEX, "DeX 桌面")
        OverlayUiUtils.styleChipWithIcon(btnMirror, mirrorMode == BridgeClient.MIRROR_MODE_MIRROR, HyperUi.ICON_MIRROR, "原生镜像")
    }

    fun updateDockAutoHide() {
        val autoHide = host.dock.isDockAutoHide
        OverlayUiUtils.styleChipWithIcon(btnHideDock, autoHide, HyperUi.ICON_PIN, if (autoHide) "Dock 自动隐藏" else "Dock 固定显示")
    }

    fun updateBlackout() {
        val active = host.blackout.isBlackoutActive
        OverlayUiUtils.styleChipWithIcon(
            btnBlackout, active, if (active) HyperUi.ICON_SUN else HyperUi.ICON_MOON,
            if (active) "主屏已黑屏 · 点此恢复亮屏" else "一键黑屏 (主屏最低亮度)", sizeDp = 13.5f,
        )
    }

    fun updateSplitButton(splitActive: Boolean) = OverlayUiUtils.styleChipWithIcon(
        btnSplit, splitActive, HyperUi.ICON_SPLIT, if (splitActive) "退出分屏" else "一键分屏",
    )

    fun updateStatusChips(st: BridgeClient.Status) {
        val fsOn = st.fullscreenPolicy == 1
        OverlayUiUtils.styleChipWithIcon(btnFullscreen, fsOn, HyperUi.ICON_FULLSCREEN, if (fsOn) "桌面横屏: 开" else "桌面横屏: 关")
        OverlayUiUtils.styleChipWithIcon(btnRotate, st.virtRot != 0, HyperUi.ICON_ROTATE, "旋转: ${BridgeClient.rotationLabel(st.virtRot)}")
        updateModeChips(st.mirrorMode)
        updateDockAutoHide()
        updateBlackout()
    }

    fun updateInputToggle(inputToExt: Int) = tileInput.setActive(inputToExt == 1, if (inputToExt == 1) "外接扩展屏" else "本机内屏")

    fun refreshLocalHardware(volLevel: Int) {
        refreshVolumeIconOnly(volLevel)
        host.audioManager?.takeIf { !seekingVol }?.let { am ->
            val maxVol = max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (volSeekBar.max != maxVol) volSeekBar.max = maxVol
            if (volSeekBar.progress != curVol) volSeekBar.progress = curVol
        }
        battView.setBattery(host.batteryPct, host.batteryCharging)
        val txt = "${host.batteryPct}%${if (host.batteryCharging) " · 充电中" else ""}"
        if (battText.text?.toString() != txt) battText.text = txt
    }

    fun refreshVolumeIconOnly(volLevel: Int) = volSpeakerIcon.update(kind = HyperUi.volumeIconKind(volLevel))

    fun refreshSysStatus(sys: BridgeClient.SysStatus) {
        if (!sys.valid) return
        tileWifi.setActive(sys.wifiOn, if (sys.wifiOn) sys.wifiSsid.ifEmpty { "已开启" } else "Wi-Fi")
        tileBt.setActive(sys.btOn, if (sys.btOn) sys.btName.ifEmpty { "未连接" } else "蓝牙")
        tileAirplane.setActive(sys.airplaneOn, "飞行模式")
        tilePowerSave.setActive(sys.powerSaveOn, "节能模式")
    }
}

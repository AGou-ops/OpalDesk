package io.github.lengsukq.opaldesk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import io.github.lengsukq.opaldesk.design.iosglass.CrossWindowBlurController
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.InputBackend
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.MouseAction
import io.github.lengsukq.opaldesk.service.DisplayLaunchHelper
import io.github.lengsukq.opaldesk.touchpad.*
import io.github.lengsukq.opaldesk.ui.ExtHyperTheme
import io.github.lengsukq.opaldesk.ui.ExtThemePrefs
import io.github.lengsukq.opaldesk.ui.isEffectiveDark

class TouchpadActivity : ComponentActivity() {

    companion object {
        private val SPEEDS = floatArrayOf(1.0f, 1.6f, 2.3f, 3.2f)
    }

    private lateinit var dispatcher: TrackpadInputDispatcher
    private var trackpadViewRef: NativeTrackpadView? = null
    private var injectWarnToasted = false

    private var themeModeIdx by mutableIntStateOf(0)
    private var speedIdx by mutableIntStateOf(1)
    private var useCtrlV by mutableStateOf(false)
    private var oneHandPortrait by mutableStateOf(false)
    private var imeVisible by mutableStateOf(false)
    private var imeText by mutableStateOf("")
    private var dragLocked by mutableStateOf(false)
    private var inputToExt by mutableStateOf(true)
    private var injectDenied by mutableStateOf(false)
    private var blackoutActive by mutableStateOf(false)
    private var virtW by mutableIntStateOf(2560)
    private var virtH by mutableIntStateOf(1440)
    private var curX by mutableIntStateOf(1280)
    private var curY by mutableIntStateOf(720)

    private val binderReadyCb = Runnable {
        if (::dispatcher.isInitialized) dispatcher.postDelayed(0L) { refreshStatus() }
    }

    private val trackpadListener = object : NativeTrackpadView.Listener {
        override fun onMoveRel(dx: Float, dy: Float) = dispatcher.sendMouse(MouseAction.MOVE_REL, dx, dy)
        override fun onLeftClick() = dispatcher.sendMouse(MouseAction.LEFT_CLICK)
        override fun onLeftDown() = dispatcher.sendMouse(MouseAction.LEFT_DOWN)
        override fun onLeftUp() = dispatcher.sendMouse(MouseAction.LEFT_UP)
        override fun onScroll(hx: Float, vy: Float) = dispatcher.sendMouse(MouseAction.SCROLL, hx, vy)
        override fun onRightClickOrBack() = performRightClickOrBack()
        override fun onReleaseDragLock() { dragLocked = false; dispatcher.sendMouse(MouseAction.LEFT_UP) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DisplayLaunchHelper.ensureOnDisplay0(this)) return
        window.decorView.filterTouchesWhenObscured = true
        enableEdgeToEdge()
        CrossWindowBlurController.applyToActivityWindow(window)

        dispatcher = TrackpadInputDispatcher { actionType, x, y, injectStatus ->
            curX = x; curY = y
            BridgeService.moveCursor(x.toFloat(), y.toFloat(), dragLocked || actionType == MouseAction.LEFT_DOWN)
            updateInjectState(injectStatus)
        }

        themeModeIdx = ExtThemePrefs.getThemeModeIndex(this)
        blackoutActive = BridgeService.isBlackoutActive()
        BridgeService.ensureStarted(this)
        refreshStatus()

        val actions = buildActions()
        setContent {
            ExtHyperTheme(themeModeIndex = themeModeIdx) {
                val dark = isEffectiveDark(themeModeIdx)
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
                    }
                }
                TouchpadScreen(
                    state = TouchpadUiState(
                        dark, curX, curY, virtW, virtH, inputToExt, injectDenied,
                        oneHandPortrait, imeVisible, imeText, useCtrlV, dragLocked, SPEEDS[speedIdx], blackoutActive,
                    ),
                    actions = actions,
                )
            }
        }
    }

    private fun buildActions() = TouchpadActions(
        onExit = { finish() },
        onToggleInput = {
            dispatcher.toggleInput { mode ->
                if (mode >= 0) inputToExt = (mode == 1)
                BridgeService.refreshFromReceiver(this)
            }
        },
        onToggleIme = {
            imeVisible = !imeVisible
            if (!imeVisible) {
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
            }
        },
        onDismissOneHand = { oneHandPortrait = false },
        onToggleOneHand = { oneHandPortrait = !oneHandPortrait },
        onLeftClick = {
            if (dragLocked) { dragLocked = false; dispatcher.sendMouse(MouseAction.LEFT_UP) }
            else dispatcher.sendMouse(MouseAction.LEFT_CLICK)
        },
        onToggleDragLock = {
            dragLocked = !dragLocked
            dispatcher.sendMouse(if (dragLocked) MouseAction.LEFT_DOWN else MouseAction.LEFT_UP)
        },
        onRightClickOrBack = ::performRightClickOrBack,
        onSendBack = { dispatcher.sendBack() },
        onRelaunchLauncher = {
            if (BridgeService.isDesktopActive()) BridgeService.toggleShowDesktopFromUi()
            else dispatcher.relaunchLauncher()
        },
        onOpenMissionControl = { BridgeService.toggleWindowSwitcherFromUi() },
        onCycleSpeed = { speedIdx = (speedIdx + 1) % SPEEDS.size },
        onToggleBlackout = {
            blackoutActive = !blackoutActive
            BridgeService.toggleBlackoutFromUi(this)
            dispatcher.postDelayed(180L) { blackoutActive = BridgeService.isBlackoutActive() }
        },
        onOpenSideControl = {
            startActivity(Intent(this, SideControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        },
        onSendKey = { kc, meta -> dispatcher.sendKey(kc, meta) },
        onImeTextChange = { imeText = it },
        onToggleUseCtrlV = { useCtrlV = !useCtrlV },
        onCommitImeText = ::commitImeText,
        trackpadListener = trackpadListener,
        onAttachTrackpadView = { trackpadViewRef = it },
    )

    override fun onResume() {
        super.onResume()
        if (!::dispatcher.isInitialized) return
        themeModeIdx = ExtThemePrefs.getThemeModeIndex(this)
        blackoutActive = BridgeService.isBlackoutActive()
        BridgeClient.setOnBinderReady(binderReadyCb)
        if (BridgeClient.getService() == null) BridgeClient.requestBinder(this)
        refreshStatus()
    }

    override fun onPause() { releaseHeldMouseIfNeeded(); super.onPause(); BridgeClient.removeOnBinderReady(binderReadyCb) }
    override fun onStop() { releaseHeldMouseIfNeeded(); super.onStop() }

    override fun onDestroy() {
        releaseHeldMouseIfNeeded(); trackpadViewRef = null
        BridgeClient.removeOnBinderReady(binderReadyCb)
        if (::dispatcher.isInitialized) dispatcher.shutdown()
        super.onDestroy()
    }

    private fun releaseHeldMouseIfNeeded() {
        trackpadViewRef?.resetTouchGestureState()
        val wasLocked = dragLocked
        if (dragLocked) dragLocked = false
        if (::dispatcher.isInitialized && dispatcher.releaseHeldMouseIfNeeded(wasLocked)) {
            BridgeService.moveCursor(curX.toFloat(), curY.toFloat(), false)
        }
    }

    private fun refreshStatus() = dispatcher.refreshStatus { st ->
        if (!st.connected) return@refreshStatus
        virtW = st.virtW; virtH = st.virtH; curX = st.cursorX; curY = st.cursorY
        inputToExt = (st.inputToExt == 1); updateInjectState(st.injectStatus)
    }

    private fun updateInjectState(statusVal: Int) {
        injectDenied = statusVal == InputBackend.INJECT_STATUS_BLOCKED
        if (injectDenied && !injectWarnToasted) {
            injectWarnToasted = true
            Toast.makeText(this, "系统拦截了外接屏点击注入，请开启「USB 调试（安全设置）」", Toast.LENGTH_LONG).show()
        }
    }

    private fun performRightClickOrBack() {
        when (BridgeService.classifyRightClickTarget(curX, curY)) {
            1 -> return
            2 -> dispatcher.sendMouse(MouseAction.RIGHT_CLICK, 1f, 0f)
            else -> dispatcher.sendMouse(MouseAction.RIGHT_CLICK, 0f, 0f)
        }
    }

    private fun commitImeText() {
        val txt = imeText
        if (txt.isEmpty()) { dispatcher.sendKey(KeyEvent.KEYCODE_ENTER, 0); return }
        if (txt.all { it.code in 0x20..0x7E }) {
            dispatcher.sendAscii(txt)
        } else {
            (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("opaldesk", txt))
            dispatcher.sendPasteDelayed(useCtrlV)
        }
        imeText = ""
    }
}

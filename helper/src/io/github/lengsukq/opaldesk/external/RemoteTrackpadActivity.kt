package io.github.lengsukq.opaldesk.external

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.core.view.WindowCompat
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.MainActivity
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.ui.OpalImeFocusTracker
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalTheme
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Display 0 Precision Multi-Touch Trackpad & Control Deck for active Wireless / USB OpalCast sessions.
 *
 * Lives on its own Display 0 task so it never collides with [io.github.lengsukq.opaldesk.ExternalDesktopActivity]
 * rendering on the remote `OpalDesk-Remote` VirtualDisplay. Injects 120Hz `FLAG_ONEWAY` pointer/scroll
 * events directly onto the remote desktop while rendering a zero-lag in-process cursor on the casted screen.
 */
class RemoteTrackpadActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cursorPosState = mutableStateOf(Offset(960f, 540f))
    private var isPrimaryPressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val initialRemote = BridgeClient.remoteStatusFlow.value
        if (!initialRemote.hasActiveDesktopCast) {
            Toast.makeText(this, "当前未处于桌面投屏状态", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val initW = initialRemote.width.coerceAtLeast(640)
        val initH = initialRemote.height.coerceAtLeast(480)
        val center = Offset(initW * 0.5f, initH * 0.5f)
        cursorPosState.value = center
        BridgeClient.updateRemoteTrackpadCursor(center)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    sendBackToFocusedWindow()
                }
            },
        )

        setContent {
            OpalTheme {
                val isDark = OpalPalette.isDark
                val remote by BridgeClient.remoteStatusFlow.collectAsState()
                val cursor = cursorPosState.value

                LaunchedEffect(isDark) {
                    val ctrl = WindowCompat.getInsetsController(window, window.decorView)
                    ctrl.isAppearanceLightStatusBars = !isDark
                    ctrl.isAppearanceLightNavigationBars = !isDark
                }

                LaunchedEffect(remote.hasActiveDesktopCast) {
                    if (!remote.hasActiveDesktopCast) {
                        finish()
                    }
                }

                val targetW = remote.width.coerceAtLeast(640)
                val targetH = remote.height.coerceAtLeast(480)
                val fps = (remote.fpsX100 / 100).coerceAtLeast(30)
                val summary = "${remote.peerName.ifBlank { "投屏端" }} · ${targetW}×${targetH} @ ${fps}Hz"

                DexTrackpadScreen(
                    monitorSummary = summary,
                    externalDisplayId = remote.desktopDisplayId,
                    cursorX = cursor.x.roundToInt(),
                    cursorY = cursor.y.roundToInt(),
                    badgePrefix = remote.channelLabel,
                    allowSwitchToLocalDesktop = false,
                    exitButtonLabel = "断开投屏",
                    onMoveCursor = { dx, dy -> moveCursorBy(dx, dy, targetW, targetH) },
                    onSetPrimaryButton = { down -> setPrimaryButton(down) },
                    onTapPrimary = { tapPrimary() },
                    onScroll = { h, v -> scrollAtCursor(h, v) },
                    onSecondaryAction = ::sendBackToFocusedWindow,
                    onSendKeyTap = ::sendKeyTapToFocusedWindow,
                    onCommitTextToFocusedWindow = ::commitTextToFocusedWindow,
                    onSwitchToLocalDesktop = { /* Locked while casting */ },
                    onOpenConsole = ::openConsole,
                    onExitExternal = {
                        BridgeClient.disconnectRemoteStreamAsync()
                        finish()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        BridgeClient.requestBinder(this)
        Thread({
            val latest = BridgeClient.refreshRemoteStatus()
            if (!latest.hasActiveDesktopCast) {
                runOnUiThread { finish() }
            }
        }, "OpalRemoteTrackpadSync").start()
    }

    override fun onDestroy() {
        BridgeClient.updateRemoteTrackpadCursor(null)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (WorkstationKeyDispatcher.handleGlobalShortcut(event)) return true
        if (OpalImeFocusTracker.localComposeTextFieldFocused && super.dispatchKeyEvent(event)) return true
        if (WorkstationKeyDispatcher.forwardToFocusedWindow(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun moveCursorBy(dx: Float, dy: Float, dispW: Int, dispH: Int) {
        val cur = cursorPosState.value
        val next = Offset(
            x = min(max(1f, (dispW - 1).toFloat()), max(0f, cur.x + dx)),
            y = min(max(1f, (dispH - 1).toFloat()), max(0f, cur.y + dy)),
        )
        cursorPosState.value = next
        BridgeClient.updateRemoteTrackpadCursor(next)
        if (isPrimaryPressed) {
            BridgeClient.injectRemoteTrackpadOneway(
                action = MotionEvent.ACTION_MOVE,
                x = next.x,
                y = next.y,
                buttonState = MotionEvent.BUTTON_PRIMARY,
            )
        } else {
            BridgeClient.injectRemoteTrackpadOneway(
                action = MotionEvent.ACTION_HOVER_MOVE,
                x = next.x,
                y = next.y,
                buttonState = 0,
            )
        }
    }

    private fun setPrimaryButton(pressed: Boolean) {
        val pos = cursorPosState.value
        if (pressed && !isPrimaryPressed) {
            isPrimaryPressed = true
            BridgeClient.injectRemoteTrackpadOneway(
                action = MotionEvent.ACTION_DOWN,
                x = pos.x,
                y = pos.y,
                buttonState = MotionEvent.BUTTON_PRIMARY,
            )
        } else if (!pressed && isPrimaryPressed) {
            isPrimaryPressed = false
            BridgeClient.injectRemoteTrackpadOneway(
                action = MotionEvent.ACTION_UP,
                x = pos.x,
                y = pos.y,
                buttonState = 0,
            )
        }
    }

    private fun tapPrimary() {
        val pos = cursorPosState.value
        isPrimaryPressed = false
        BridgeClient.injectRemoteTrackpadOneway(
            action = MotionEvent.ACTION_DOWN,
            x = pos.x,
            y = pos.y,
            buttonState = MotionEvent.BUTTON_PRIMARY,
        )
        mainHandler.postDelayed({
            BridgeClient.injectRemoteTrackpadOneway(
                action = MotionEvent.ACTION_UP,
                x = pos.x,
                y = pos.y,
                buttonState = 0,
            )
        }, 20L)
    }

    private fun scrollAtCursor(hScroll: Float, vScroll: Float) {
        val pos = cursorPosState.value
        BridgeClient.injectRemoteTrackpadOneway(
            action = MotionEvent.ACTION_SCROLL,
            x = pos.x,
            y = pos.y,
            hScroll = hScroll,
            vScroll = vScroll,
            buttonState = 0,
        )
    }

    private fun sendBackToFocusedWindow() {
        val focused = BridgeClient.sceneFlow.value.focusedWindow
        if (focused != null && !focused.isMinimized) {
            BridgeClient.sendWindowActionAsync(focused.windowId, BridgeProtocol.WinAction.SEND_BACK)
        }
    }

    private fun sendKeyTapToFocusedWindow(keyCode: Int, metaState: Int) {
        val focused = BridgeClient.sceneFlow.value.focusedWindow ?: return
        if (focused.isMinimized) return
        BridgeClient.injectWindowKeyAsync(focused.windowId, KeyEvent.ACTION_DOWN, keyCode, metaState)
        BridgeClient.injectWindowKeyAsync(focused.windowId, KeyEvent.ACTION_UP, keyCode, metaState)
    }

    private fun commitTextToFocusedWindow(text: String) {
        if (text.isEmpty()) return
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("OpalDesk", text))
        } catch (_: Throwable) {
        }
        val focused = BridgeClient.sceneFlow.value.focusedWindow ?: return
        if (focused.isMinimized) return
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        mainHandler.postDelayed({
            BridgeClient.injectWindowKeyAsync(focused.windowId, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, ctrl)
            BridgeClient.injectWindowKeyAsync(focused.windowId, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, ctrl)
        }, 45L)
    }

    private fun openConsole() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }
}

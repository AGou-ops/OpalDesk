package io.github.lengsukq.opaldesk

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.lengsukq.opaldesk.compositor.OpalCompositorWorkspace
import io.github.lengsukq.opaldesk.compositor.applyWindowTilingLayout
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.WindowTilingLayout
import io.github.lengsukq.opaldesk.ui.OpalTheme

/**
 * Primary on-device Fullscreen Immersive Multi-Window Workstation Host (Display 0).
 *
 * Hosts [OpalCompositorWorkspace] at native display refresh rate (60–144Hz), eliminates
 * `SYSTEM_ALERT_WINDOW` split-brain overlays, and routes physical keyboard shortcuts
 * (`Alt+Tab`, `Alt+2/3/4/G` tiling, `Ctrl+W`, `Back`, and typing) into the active `VirtualDisplay` window session.
 */
class DesktopActivity : ComponentActivity() {

    private val wallpaperPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread({
            val saved = WallpaperStorage.importWallpaperFromUri(this, uri)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (saved != null) "已更换桌面壁纸" else "无法读取所选图片",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }, "OpalWallpaperImport").start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        BridgeService.sUserStopping = false
        BridgeService.ensureStarted(this)
        BridgeClient.requestBinder(this)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val focused = BridgeClient.sceneFlow.value.focusedWindow
                    if (focused != null && !focused.isMinimized) {
                        BridgeClient.sendWindowActionAsync(focused.windowId, BridgeProtocol.WinAction.SEND_BACK)
                    } else {
                        openConsole()
                    }
                }
            },
        )

        setContent {
            OpalTheme {
                OpalCompositorWorkspace(
                    isExternalMonitor = false,
                    onPickCustomWallpaper = { wallpaperPicker.launch("image/*") },
                    onOpenConsole = ::openConsole,
                    onExitDesktop = { finish() },
                )
            }
        }
        applyImmersiveFullscreen()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveFullscreen()
        BridgeClient.requestBinder(this)
        Thread({
            BridgeClient.refreshStatus()
            BridgeClient.refreshSceneSnapshot()
        }, "OpalDesktopSync").start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val scene = BridgeClient.sceneFlow.value
        val focused = scene.focusedWindow

        // Global workstation shortcuts: Alt+Tab cycles windows, Ctrl+W closes focused window
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.isAltPressed && event.keyCode == KeyEvent.KEYCODE_TAB) {
                val candidates = scene.windows.filter { !it.isMinimized }
                if (candidates.size >= 2) {
                    val idx = candidates.indexOfFirst { it.focused }
                    val next = candidates[(idx + 1) % candidates.size]
                    BridgeClient.sendWindowActionAsync(next.windowId, BridgeProtocol.WinAction.FOCUS)
                }
                return true
            }
            if (event.isAltPressed) {
                val layout = when (event.keyCode) {
                    KeyEvent.KEYCODE_2 -> WindowTilingLayout.SPLIT_2_COLS
                    KeyEvent.KEYCODE_3 -> WindowTilingLayout.SPLIT_3_COLS
                    KeyEvent.KEYCODE_4 -> WindowTilingLayout.SPLIT_4_COLS
                    KeyEvent.KEYCODE_G -> WindowTilingLayout.SPLIT_4_GRID
                    else -> null
                }
                if (layout != null) {
                    val st = BridgeClient.statusFlow.value
                    applyWindowTilingLayout(
                        layout = layout,
                        scene = scene,
                        canvasW = st.canvasWidth,
                        canvasH = st.canvasHeight,
                        density = (st.canvasDpi / 160f).coerceIn(0.8f, 3.5f),
                    )
                    return true
                }
            }
            if (event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_W && focused != null) {
                BridgeClient.sendWindowActionAsync(focused.windowId, BridgeProtocol.WinAction.CLOSE)
                return true
            }
        }

        // Let active Compose inputs (e.g. App Drawer search box) consume keystrokes first
        if (super.dispatchKeyEvent(event)) {
            return true
        }

        // Forward physical/external keyboard keystrokes to the focused VirtualDisplay session
        if (focused != null && !focused.isMinimized && isForwardableKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                BridgeClient.injectWindowKeyAsync(
                    windowId = focused.windowId,
                    action = event.action,
                    keyCode = event.keyCode,
                    metaState = event.metaState,
                )
                return true
            }
        }
        return false
    }

    private fun isForwardableKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_SLEEP,
        KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_APP_SWITCH -> false
        else -> true
    }

    private fun openConsole() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }

    private fun applyImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val decor = window.decorView
        WindowCompat.getInsetsController(window, decor).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

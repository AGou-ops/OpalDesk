package io.github.lengsukq.opaldesk

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.lengsukq.opaldesk.compositor.OpalCompositorWorkspace
import io.github.lengsukq.opaldesk.external.DexTrackpadScreen
import io.github.lengsukq.opaldesk.external.ExternalMonitorPresentation
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.WallpaperStorage
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.ui.OpalImeFocusTracker
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalTheme
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher
import kotlin.math.roundToInt

/**
 * Universal External Monitor Workstation Host (USB-C DisplayPort Alt-Mode / HDMI / Mac Remote Display).
 *
 * Automatically supports BOTH external display modes across Android 11–17:
 * 1. **Direct Extended Display Mode** (`canHostTasks == true`, e.g. Pixel / AOSP Desktop / OpalCast Mac Remote VD):
 *    Renders [OpalCompositorWorkspace] natively directly inside this Activity on the external display.
 * 2. **Full-Bleed Presentation + Display 0 DeX Trackpad Mode** (`canHostTasks == false`, e.g. HyperOS / OEM mirror-default ROMs):
 *    Opens a fullscreen [ExternalMonitorPresentation] directly on the physical external display (stopping
 *    mirroring immediately for native edge-to-edge resolution & refresh rate with zero black bars) while
 *    Display 0 serves as a Samsung DeX-style precision multi-touch trackpad & monitor control deck.
 */
class ExternalDesktopActivity : ComponentActivity() {
    companion object {
        private const val TAG = "OpalExtDesktop"

        @Volatile
        var isPresentationActive: Boolean = false
            private set

        @Volatile
        var activeExternalDisplayId: Int = -1
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
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
    private var displayManager: DisplayManager? = null
    private var presentation: ExternalMonitorPresentation? = null
    private var targetDisplayId: Int = -1
    private var presentationSummary by mutableStateOf("")
    private var runningInPresentationMode by mutableStateOf(false)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) {
            if (targetDisplayId > Display.DEFAULT_DISPLAY && displayId == targetDisplayId) {
                dismissPresentation()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        BridgeService.sUserStopping = false
        BridgeService.ensureStarted(this)
        BridgeClient.requestBinder(this)

        displayManager = (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.also { dm ->
            dm.registerDisplayListener(displayListener, handler)
        }

        val currentDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY
        val requestedId = intent?.getIntExtra("targetDisplayId", -1) ?: -1
        val physicalExternal = findPhysicalExternalDisplay(requestedId)

        // If Android placed this Activity on Display 0 while a physical external monitor is attached
        // (mirror-mode / FLAG_PRESENTATION), launch native full-bleed Presentation on the monitor!
        val usePresentation = (currentDisplayId == Display.DEFAULT_DISPLAY) && (physicalExternal != null)
        runningInPresentationMode = usePresentation

        if (physicalExternal != null && currentDisplayId == Display.DEFAULT_DISPLAY) {
            targetDisplayId = physicalExternal.displayId
            val pres = ExternalMonitorPresentation(
                hostActivity = this,
                targetDisplay = physicalExternal,
                onPickCustomWallpaper = { wallpaperPicker.launch("image/*") },
                onOpenConsole = ::openConsoleOnPrimaryDisplay,
                onExitDesktop = { finish() },
            )
            try {
                pres.show()
                presentation = pres
                isPresentationActive = true
                activeExternalDisplayId = physicalExternal.displayId
                presentationSummary = pres.monitorSummary
                syncExternalMouseRouting(physicalExternal.displayId)
            } catch (t: Throwable) {
                Log.w(TAG, "Presentation.show failed, falling back to direct render: ${t.message}")
                runningInPresentationMode = false
                isPresentationActive = false
                activeExternalDisplayId = -1
            }
        } else if (currentDisplayId > Display.DEFAULT_DISPLAY) {
            targetDisplayId = currentDisplayId
            activeExternalDisplayId = currentDisplayId
            syncExternalMouseRouting(currentDisplayId)
        }

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
                if (runningInPresentationMode) {
                    LaunchedEffect(isDark) {
                        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
                        ctrl.isAppearanceLightStatusBars = !isDark
                        ctrl.isAppearanceLightNavigationBars = !isDark
                    }
                }
                if (runningInPresentationMode && presentation != null) {
                    val pres = presentation!!
                    val cursor = pres.cursorPosState.value
                    DexTrackpadScreen(
                        monitorSummary = presentationSummary,
                        externalDisplayId = targetDisplayId,
                        cursorX = cursor.x.roundToInt(),
                        cursorY = cursor.y.roundToInt(),
                        onMoveCursor = { dx, dy -> pres.moveCursorBy(dx, dy) },
                        onSetPrimaryButton = { down -> pres.setPrimaryButton(down) },
                        onTapPrimary = { pres.tapPrimary() },
                        onScroll = { h, v -> pres.scrollAtCursor(h, v) },
                        onSecondaryAction = ::sendBackToFocusedWindow,
                        onSendKeyTap = ::sendKeyTapToFocusedWindow,
                        onCommitTextToFocusedWindow = ::commitTextToFocusedWindow,
                        onSwitchToLocalDesktop = {
                            dismissPresentation()
                            startActivity(
                                Intent(this, DesktopActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                },
                            )
                            finish()
                        },
                        onOpenConsole = ::openConsoleOnPrimaryDisplay,
                        onExitExternal = { finish() },
                    )
                } else {
                    val uiScale by AppPrefs.externalUiScaleFlow.collectAsState()
                    val remoteCursor by BridgeClient.remoteTrackpadCursorFlow.collectAsState()
                    val baseDensity = LocalDensity.current
                    val effDensity = Density(
                        density = (baseDensity.density * uiScale).coerceIn(0.85f, 3.5f),
                        fontScale = baseDensity.fontScale,
                    )
                    val curMode = display?.mode
                    val summary = if (curMode != null) {
                        "${display?.name ?: "外接屏"} · ${curMode.physicalWidth}×${curMode.physicalHeight} @ ${curMode.refreshRate.toInt()}Hz"
                    } else {
                        ""
                    }
                    CompositionLocalProvider(LocalDensity provides effDensity) {
                        OpalCompositorWorkspace(
                            isExternalMonitor = true,
                            externalMonitorSummary = summary,
                            virtualCursorPx = remoteCursor,
                            onPickCustomWallpaper = { wallpaperPicker.launch("image/*") },
                            onOpenConsole = ::openConsoleOnPrimaryDisplay,
                            onExitDesktop = { finish() },
                        )
                    }
                }
            }
        }

        if (!runningInPresentationMode) {
            applyImmersiveFullscreen()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val currentDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY
        if (!runningInPresentationMode && currentDisplayId > Display.DEFAULT_DISPLAY) {
            targetDisplayId = currentDisplayId
            activeExternalDisplayId = currentDisplayId
            syncExternalMouseRouting(currentDisplayId)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!runningInPresentationMode) {
            applyImmersiveFullscreen()
        }
        BridgeClient.requestBinder(this)
        if (activeExternalDisplayId > Display.DEFAULT_DISPLAY) {
            syncExternalMouseRouting(activeExternalDisplayId)
        }
        Thread({
            BridgeClient.refreshStatus()
            BridgeClient.refreshSceneSnapshot()
        }, "OpalExtDesktopSync").start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !runningInPresentationMode) applyImmersiveFullscreen()
    }

    override fun onDestroy() {
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
        dismissPresentation()
        if (BridgeClient.statusFlow.value.hostScreenOff) {
            BridgeClient.setHostScreenPowerAsync(true)
        }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (WorkstationKeyDispatcher.handleGlobalShortcut(event)) return true

        // Allow Display 0 inline IME box or App Drawer search box to consume keys when explicitly focused
        if (OpalImeFocusTracker.localComposeTextFieldFocused) {
            if (super.dispatchKeyEvent(event)) return true
            if (presentation?.dispatchKeyEventToPresentation(event) == true) return true
        }

        // Prioritize forwarding physical / remote keyboard keystrokes directly to the focused app window
        if (WorkstationKeyDispatcher.forwardToFocusedWindow(event)) {
            return true
        }

        if (super.dispatchKeyEvent(event)) return true
        if (presentation?.dispatchKeyEventToPresentation(event) == true) return true
        return false
    }

    private fun findPhysicalExternalDisplay(preferredId: Int): Display? {
        val dm = displayManager ?: return null
        if (preferredId > Display.DEFAULT_DISPLAY) {
            dm.getDisplay(preferredId)?.takeIf { isPhysicalExternal(it) }?.let { return it }
        }
        return dm.displays.firstOrNull { isPhysicalExternal(it) }
    }

    private fun isPhysicalExternal(d: Display): Boolean {
        if (d.displayId <= Display.DEFAULT_DISPLAY || !d.isValid) return false
        val name = d.name.orEmpty()
        if (name.startsWith("OpalWin-") || name.startsWith("OpalDesk-")) return false
        return (d.flags and Display.FLAG_PRESENTATION) != 0
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
        handler.postDelayed({
            BridgeClient.injectWindowKeyAsync(focused.windowId, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, ctrl)
            BridgeClient.injectWindowKeyAsync(focused.windowId, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, ctrl)
        }, 45L)
    }

    private fun syncExternalMouseRouting(extDisplayId: Int) {
        if (extDisplayId <= Display.DEFAULT_DISPLAY) return
        val mode = AppPrefs.getMouseTarget(this)
        BridgeClient.setMouseTargetAsync(mode, extDisplayId)
    }

    private fun dismissPresentation() {
        isPresentationActive = false
        activeExternalDisplayId = -1
        BridgeClient.setMouseTargetAsync(BridgeProtocol.MouseTarget.LOCAL, -1)
        val p = presentation
        presentation = null
        if (p != null) {
            try {
                p.dismiss()
            } catch (_: Throwable) {
            }
        }
    }

    private fun openConsoleOnPrimaryDisplay() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            startActivity(intent, opts.toBundle())
        } catch (_: Throwable) {
            startActivity(intent)
        }
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

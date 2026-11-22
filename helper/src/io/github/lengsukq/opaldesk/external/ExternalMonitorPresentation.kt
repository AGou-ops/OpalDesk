package io.github.lengsukq.opaldesk.external

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.lengsukq.opaldesk.compositor.OpalCompositorWorkspace
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.ui.OpalTheme
import kotlin.math.max
import kotlin.math.min

/**
 * Full-bleed native external monitor compositor host built on standard public AOSP [Presentation].
 *
 * Uses its own independent [PresentationLifecycleOwner] so Display 4's Compose tree stays 100%
 * live and interactive at peak refresh rate (e.g. 2560×1440 @ 100Hz) even when Display 0 switches
 * to Home, OpalDesk Console, or other Android apps.
 *
 * Supports BOTH:
 * - Direct hardware mouse routed natively to Display 4 (auto-hides virtual cursor for zero double-cursor)
 * - Display 0 Precision Trackpad synthetic cursor/touch/scroll injection
 */
class ExternalMonitorPresentation(
    hostActivity: ComponentActivity,
    private val targetDisplay: Display,
    private val onPickCustomWallpaper: () -> Unit,
    private val onOpenConsole: () -> Unit,
    private val onExitDesktop: () -> Unit,
) : Presentation(hostActivity, targetDisplay, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {

    private val displayWidthPx: Int = targetDisplay.mode?.physicalWidth?.coerceAtLeast(640) ?: 1920
    private val displayHeightPx: Int = targetDisplay.mode?.physicalHeight?.coerceAtLeast(480) ?: 1080
    private val refreshRateHz: Int = resolvePeakRefreshRateMode(targetDisplay)?.refreshRate?.toInt()
        ?: (targetDisplay.mode?.refreshRate ?: 60f).toInt()

    private val ownLifecycle = PresentationLifecycleOwner()
    val cursorPosState = mutableStateOf(Offset(displayWidthPx * 0.5f, displayHeightPx * 0.5f))
    private val showVirtualCursorState = mutableStateOf(false)

    private var composeView: ComposeView? = null
    private var primaryDownTimeMs: Long = 0L
    private var isPrimaryPressed: Boolean = false

    val monitorSummary: String
        get() = "${targetDisplay.name ?: "外接屏"} · ${displayWidthPx}×${displayHeightPx} @ ${refreshRateHz}Hz"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ownLifecycle.onCreate()

        window?.let { win ->
            win.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            )
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

            // Prefer highest refresh-rate mode at native resolution (e.g. 2560×1440 @ 100Hz)
            val peakMode = resolvePeakRefreshRateMode(targetDisplay)
            if (peakMode != null) {
                val lp = win.attributes
                lp.preferredDisplayModeId = peakMode.modeId
                win.attributes = lp
            }

            WindowCompat.setDecorFitsSystemWindows(win, false)
            val decor = win.decorView
            decor.setViewTreeLifecycleOwner(ownLifecycle)
            decor.setViewTreeViewModelStoreOwner(ownLifecycle)
            decor.setViewTreeSavedStateRegistryOwner(ownLifecycle)
        }

        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setContent {
                OpalTheme {
                    val uiScale by AppPrefs.externalUiScaleFlow.collectAsState()
                    val baseDensity = LocalDensity.current
                    val effDensity = Density(
                        density = (baseDensity.density * uiScale).coerceIn(0.85f, 3.5f),
                        fontScale = baseDensity.fontScale,
                    )
                    CompositionLocalProvider(LocalDensity provides effDensity) {
                        OpalCompositorWorkspace(
                            isExternalMonitor = true,
                            externalMonitorSummary = monitorSummary,
                            virtualCursorPx = if (showVirtualCursorState.value) cursorPosState.value else null,
                            onPickCustomWallpaper = onPickCustomWallpaper,
                            onOpenConsole = onOpenConsole,
                            onExitDesktop = onExitDesktop,
                        )
                    }
                }
            }
        }
        composeView = view

        val rootContainer = HardwareMouseDetectingFrameLayout(context) {
            // Real hardware mouse arrived natively on Display 4 -> hide trackpad virtual cursor
            if (showVirtualCursorState.value) showVirtualCursorState.value = false
        }.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(view)
        }
        setContentView(rootContainer)

        window?.let { win ->
            WindowCompat.getInsetsController(win, win.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ownLifecycle.onResume()
    }

    override fun onStop() {
        ownLifecycle.onDestroy()
        super.onStop()
    }

    fun moveCursorBy(dxPx: Float, dyPx: Float) {
        if (!showVirtualCursorState.value) showVirtualCursorState.value = true
        val cur = cursorPosState.value
        val maxX = max(1f, (displayWidthPx - 1).toFloat())
        val maxY = max(1f, (displayHeightPx - 1).toFloat())
        val next = Offset(
            x = min(maxX, max(0f, cur.x + dxPx)),
            y = min(maxY, max(0f, cur.y + dyPx)),
        )
        cursorPosState.value = next
        if (isPrimaryPressed) {
            dispatchPointerTouch(MotionEvent.ACTION_MOVE, next.x, next.y)
        } else {
            dispatchPointerHover(next.x, next.y)
        }
    }

    fun setPrimaryButton(pressed: Boolean) {
        if (!showVirtualCursorState.value) showVirtualCursorState.value = true
        val pos = cursorPosState.value
        if (pressed && !isPrimaryPressed) {
            isPrimaryPressed = true
            primaryDownTimeMs = SystemClock.uptimeMillis()
            dispatchPointerTouch(MotionEvent.ACTION_DOWN, pos.x, pos.y)
        } else if (!pressed && isPrimaryPressed) {
            isPrimaryPressed = false
            dispatchPointerTouch(MotionEvent.ACTION_UP, pos.x, pos.y)
        }
    }

    fun tapPrimary() {
        if (!showVirtualCursorState.value) showVirtualCursorState.value = true
        val pos = cursorPosState.value
        val now = SystemClock.uptimeMillis()
        isPrimaryPressed = false
        primaryDownTimeMs = now
        dispatchPointerTouch(MotionEvent.ACTION_DOWN, pos.x, pos.y, now)
        dispatchPointerTouch(MotionEvent.ACTION_UP, pos.x, pos.y, now + 18L)
    }

    fun scrollAtCursor(hScroll: Float, vScroll: Float) {
        val target = composeView ?: return
        if (!showVirtualCursorState.value) showVirtualCursorState.value = true
        val pos = cursorPosState.value
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = pos.x
                y = pos.y
                setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
            },
        )
        val ev = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        try {
            target.dispatchGenericMotionEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    fun dispatchKeyEventToPresentation(event: KeyEvent): Boolean {
        val target = composeView ?: return false
        return target.dispatchKeyEvent(event)
    }

    private fun dispatchPointerTouch(action: Int, x: Float, y: Float, eventTimeMs: Long = SystemClock.uptimeMillis()) {
        val target = composeView ?: return
        val downTime = if (primaryDownTimeMs > 0L) primaryDownTimeMs else eventTimeMs
        val ev = MotionEvent.obtain(
            downTime,
            eventTimeMs,
            action,
            x,
            y,
            0,
        )
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            target.dispatchTouchEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    private fun dispatchPointerHover(x: Float, y: Float) {
        val target = composeView ?: return
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0)
        ev.source = InputDevice.SOURCE_MOUSE
        try {
            target.dispatchGenericMotionEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    private fun resolvePeakRefreshRateMode(display: Display): Display.Mode? {
        val cur = display.mode ?: return null
        val supported = display.supportedModes ?: return cur
        return supported
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?: cur
    }
}

private class HardwareMouseDetectingFrameLayout(
    ctx: Context,
    private val onHardwareMouseSeen: () -> Unit,
) : FrameLayout(ctx) {
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.deviceId > 0 && (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            onHardwareMouseSeen()
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.deviceId > 0 && (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            onHardwareMouseSeen()
        }
        return super.dispatchTouchEvent(ev)
    }
}

private class PresentationLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        store.clear()
    }
}

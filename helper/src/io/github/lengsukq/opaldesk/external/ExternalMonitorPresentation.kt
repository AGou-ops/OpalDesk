package io.github.lengsukq.opaldesk.external

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import io.github.lengsukq.opaldesk.ui.OpalImeFocusTracker
import io.github.lengsukq.opaldesk.ui.OpalTheme
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher

/**
 * Full-bleed native external monitor compositor host built on standard public AOSP [Presentation].
 *
 * Uses its own independent [PresentationLifecycleOwner] so the external display's Compose tree stays
 * 100% live and interactive at peak refresh rate (e.g. 2560×1440 @ 100Hz) even when Display 0
 * switches to Home, OpalDesk Console, or other Android apps.
 *
 * Supports BOTH:
 * - Direct hardware mouse routed natively to the external display (auto-hides virtual cursor)
 * - Display 0 Precision Trackpad synthetic cursor/touch/scroll injection via [ExternalPointerDispatcher]
 */
class ExternalMonitorPresentation(
    hostActivity: ComponentActivity,
    private val targetDisplay: Display,
    private val onPickCustomWallpaper: () -> Unit,
    private val onOpenConsole: () -> Unit,
    private val onExitDesktop: () -> Unit,
) : Presentation(hostActivity, targetDisplay, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {

    val displayWidthPx: Int = targetDisplay.mode?.physicalWidth?.coerceAtLeast(640) ?: 1920
    val displayHeightPx: Int = targetDisplay.mode?.physicalHeight?.coerceAtLeast(480) ?: 1080
    val refreshRateHz: Int = resolvePeakRefreshRateMode(targetDisplay)?.refreshRate?.toInt()
        ?: (targetDisplay.mode?.refreshRate ?: 60f).toInt()

    private val ownLifecycle = PresentationLifecycleOwner()
    private var composeView: ComposeView? = null
    private val pointerDispatcher = ExternalPointerDispatcher(
        defaultWidthPx = displayWidthPx,
        defaultHeightPx = displayHeightPx,
        targetViewProvider = { composeView },
    )

    val cursorPosState get() = pointerDispatcher.cursorPosState

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
                            virtualCursorPx = if (pointerDispatcher.showVirtualCursorState.value) {
                                pointerDispatcher.cursorPosState.value
                            } else {
                                null
                            },
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
            pointerDispatcher.hideCursor()
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

    fun moveCursorBy(dxPx: Float, dyPx: Float) = pointerDispatcher.moveCursorBy(dxPx, dyPx)

    fun setPrimaryButton(pressed: Boolean) = pointerDispatcher.setPrimaryButton(pressed)

    fun tapPrimary() = pointerDispatcher.tapPrimary()

    fun scrollAtCursor(hScroll: Float, vScroll: Float) = pointerDispatcher.scrollAtCursor(hScroll, vScroll)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (WorkstationKeyDispatcher.handleGlobalShortcut(event)) return true
        if (OpalImeFocusTracker.localComposeTextFieldFocused && super.dispatchKeyEvent(event)) return true
        if (WorkstationKeyDispatcher.forwardToFocusedWindow(event)) return true
        return super.dispatchKeyEvent(event)
    }

    fun dispatchKeyEventToPresentation(event: KeyEvent): Boolean {
        val target = composeView ?: return false
        return target.dispatchKeyEvent(event)
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

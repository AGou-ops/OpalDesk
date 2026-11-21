package com.extbridge.helper.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.extbridge.helper.NavAssistService
import com.extbridge.helper.design.iosglass.CrossWindowBlurController
import kotlin.math.roundToInt

private const val TAG = "ExtOverlaySurface"
private const val FLAG_DISPLAY_TRUSTED = 0x80 // Display.FLAG_TRUSTED

object OverlayConstants {
    const val MIN_SURFACE_PX = 600
    const val HELPER_PKG = "com.extbridge.helper"

    fun isSystemOrLauncherPkg(pkg: String?): Boolean =
        pkg.isNullOrBlank() || pkg == HELPER_PKG || pkg == "android" || pkg == "com.android.systemui"
}

fun Context.dp(value: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).roundToInt()

fun Context.dpF(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

fun roundedBg(fillColor: Int, radiusPx: Float, strokeWidthPx: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(fillColor)
        cornerRadius = radiusPx
        if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
    }

/**
 * Single source of truth for display-scoped overlay Context, normalized ConfigurationContext,
 * WindowManager, and matching window type. Never pairs `TYPE_ACCESSIBILITY_OVERLAY` with a
 * non-accessibility WindowManager (preventing Display 0 `BadTokenException` crashes).
 */
data class OverlayDisplayContext(
    val displayId: Int,
    val uiContext: Context,
    val windowManager: WindowManager,
    val windowType: Int,
    val isA11yOverlay: Boolean,
    val targetDpi: Int,
) {
    companion object {
        fun resolve(
            serviceCtx: Context,
            display: Display,
            targetDpi: Int = 0,
            preferA11y: Boolean = true,
            requireTrustedDisplayForA11y: Boolean = false,
        ): OverlayDisplayContext? {
            if (preferA11y) {
                tryResolveA11y(display, targetDpi, requireTrustedDisplayForA11y)?.let { return it }
            }
            if (Settings.canDrawOverlays(serviceCtx)) {
                tryResolveAppOverlay(serviceCtx, display, targetDpi)?.let { return it }
            }
            return null
        }

        fun resolveDefaultDisplay(
            serviceCtx: Context,
            displayManager: DisplayManager?,
            preferA11y: Boolean = true,
        ): OverlayDisplayContext? {
            val disp0 = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            return resolve(serviceCtx, disp0, targetDpi = 0, preferA11y = preferA11y, requireTrustedDisplayForA11y = false)
        }

        private fun tryResolveA11y(display: Display, targetDpi: Int, requireTrusted: Boolean): OverlayDisplayContext? {
            val a11y = NavAssistService.getInstance() ?: return null
            if (requireTrusted && (display.flags and FLAG_DISPLAY_TRUSTED) == 0) return null
            return try {
                val dispCtx = a11y.createDisplayContext(display)
                // Never cross-fallback to serviceCtx WindowManager for TYPE_ACCESSIBILITY_OVERLAY.
                val wm = (dispCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                    ?: (if (display.displayId == Display.DEFAULT_DISPLAY) a11y.getSystemService(Context.WINDOW_SERVICE) as? WindowManager else null)
                    ?: return null
                val uiCtx = applyOverlayConfig(dispCtx, targetDpi)
                OverlayDisplayContext(
                    display.displayId, uiCtx, wm,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, true,
                    if (targetDpi > 0) targetDpi else uiCtx.resources.configuration.densityDpi,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "A11y overlay context failed on display=${display.displayId}", t)
                null
            }
        }

        private fun tryResolveAppOverlay(serviceCtx: Context, display: Display, targetDpi: Int): OverlayDisplayContext? =
            try {
                val dispCtx = serviceCtx.createDisplayContext(display)
                val wm = dispCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
                val uiCtx = applyOverlayConfig(dispCtx, targetDpi)
                OverlayDisplayContext(
                    display.displayId, uiCtx, wm,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, false,
                    if (targetDpi > 0) targetDpi else uiCtx.resources.configuration.densityDpi,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "App overlay context failed on display=${display.displayId}", t)
                null
            }

        private fun applyOverlayConfig(dispCtx: Context, targetDpi: Int): Context = try {
            val cfg = Configuration(dispCtx.resources.configuration)
            var changed = false
            if (targetDpi > 0 && cfg.densityDpi != targetDpi) { cfg.densityDpi = targetDpi; changed = true }
            if (cfg.fontScale != 1.0f) { cfg.fontScale = 1.0f; changed = true }
            if (changed) dispCtx.createConfigurationContext(cfg) else dispCtx
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to apply overlay configuration dpi=$targetDpi", t)
            dispCtx
        }
    }
}

object OverlayWindowParams {
    private const val BASE_OVERLAY_FLAGS =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    fun floating(
        width: Int,
        height: Int,
        windowType: Int,
        x: Int = 0,
        y: Int = 0,
        gravity: Int = Gravity.TOP or Gravity.LEFT,
        watchOutsideTouch: Boolean = false,
        touchModal: Boolean = false,
        blurBehind: Boolean = false,
    ): WindowManager.LayoutParams {
        var flags = BASE_OVERLAY_FLAGS
        if (!touchModal) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (blurBehind) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        if (watchOutsideTouch) flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        return WindowManager.LayoutParams(width, height, windowType, flags, PixelFormat.TRANSLUCENT).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }
    }

    fun popover(
        width: Int,
        height: Int,
        windowType: Int,
        x: Int = 0,
        y: Int = 0,
        gravity: Int = Gravity.TOP or Gravity.LEFT,
        ctx: Context? = null,
        wm: WindowManager? = null,
    ): WindowManager.LayoutParams = floating(width, height, windowType, x, y, gravity, watchOutsideTouch = true).also { lp ->
        if (ctx != null) CrossWindowBlurController.applyToOverlayParams(ctx, wm, lp, blurBehindRadiusDp = 32f)
    }

    fun fullscreenNonTouchable(
        width: Int,
        height: Int,
        windowType: Int,
        hardwareAccelerated: Boolean = true,
    ): WindowManager.LayoutParams {
        var flags = BASE_OVERLAY_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (hardwareAccelerated) flags = flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        return WindowManager.LayoutParams(width, height, windowType, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
        }
    }

    fun edgeStrip(
        width: Int,
        height: Int,
        windowType: Int,
        gravity: Int,
        x: Int = 0,
        y: Int = 0,
        touchable: Boolean = true,
    ): WindowManager.LayoutParams {
        var flags = BASE_OVERLAY_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(width, height, windowType, flags, PixelFormat.TRANSLUCENT).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }
    }
}

enum class OverlayLayer(val zOrder: Int) {
    BASE_CHROME(10),
    SPLIT_DIVIDER(20),
    IMMERSIVE_HANDLE(25),
    DOCK(30),
    POPOVER(40),
    SWITCHER(45),
    CURSOR(60),
}

/**
 * Z-layered dual-host surface registry coordinating attach, layout update, Launcher vs WM migration,
 * dynamic Z-order maintenance (guaranteeing [OverlayLayer.CURSOR] stays topmost), and cleanup.
 */
class OverlaySurfaceHost {
    data class SurfaceEntry(
        val key: String,
        val layer: OverlayLayer,
        val view: View,
        val wmParams: WindowManager.LayoutParams,
        val launcherParamsProvider: (() -> FrameLayout.LayoutParams)? = null,
        val onAttachedToHost: ((inLauncher: Boolean) -> Unit)? = null,
        val canDetachForReorder: () -> Boolean = { true },
    )

    private val entries = LinkedHashMap<String, SurfaceEntry>()
    var onWmAddViewFailed: ((SurfaceEntry, Throwable) -> Unit)? = null

    fun register(entry: SurfaceEntry) { entries[entry.key] = entry }

    fun registerAndAttachBatch(
        newEntries: List<SurfaceEntry>,
        inLauncher: Boolean,
        launcherHost: ViewGroup?,
        wm: WindowManager?,
    ) {
        if (newEntries.isEmpty()) return
        var maxAddedZ = Int.MIN_VALUE
        var addedToWm = false
        for (entry in newEntries) {
            entries[entry.key] = entry
            removeViewSafe(entry.view, wm)
            if (attachSingle(entry, inLauncher, launcherHost, wm)) {
                addedToWm = true
                if (entry.layer.zOrder > maxAddedZ) maxAddedZ = entry.layer.zOrder
            }
        }
        if (addedToWm && wm != null) raiseHigherWmLayers(maxAddedZ, wm)
    }

    fun unregister(key: String, wm: WindowManager?) {
        entries.remove(key)?.let { removeViewSafe(it.view, wm) }
    }

    fun reattachAll(inLauncher: Boolean, launcherHost: ViewGroup?, wm: WindowManager?) {
        val ordered = entries.values.sortedBy { it.layer.zOrder }
        val pinned = HashSet<String>()
        for (entry in ordered) {
            if (!entry.canDetachForReorder() && entry.view.parent != null && entry.view.parent !is ViewGroup) {
                pinned.add(entry.key)
            } else {
                removeViewSafe(entry.view, wm)
            }
        }
        for (entry in ordered) {
            if (entry.key !in pinned) attachSingle(entry, inLauncher, launcherHost, wm)
        }
    }

    fun bringLayerToFront(layer: OverlayLayer, inLauncher: Boolean, wm: WindowManager?) {
        for (entry in entries.values) {
            if (entry.layer != layer) continue
            val view = entry.view
            if (inLauncher && entry.launcherParamsProvider != null) {
                try {
                    if (view.parent is ViewGroup) view.bringToFront()
                } catch (t: Throwable) {
                    Log.w(TAG, "bringToFront failed key=${entry.key}", t)
                }
            } else if (wm != null && view.parent != null && view.parent !is ViewGroup && entry.canDetachForReorder()) {
                removeViewSafe(view, wm)
                try {
                    wm.addView(view, entry.wmParams)
                    entry.onAttachedToHost?.invoke(false)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to raise WM layer key=${entry.key}", t)
                }
            }
        }
    }

    fun updatePosition(
        view: View?,
        lp: WindowManager.LayoutParams?,
        inLauncher: Boolean,
        wm: WindowManager?,
        x: Int = lp?.x ?: 0,
        y: Int = lp?.y ?: 0,
        width: Int = lp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
        height: Int = lp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
        gravity: Int = lp?.gravity ?: (Gravity.TOP or Gravity.LEFT),
        bottomMargin: Int = 0,
        force: Boolean = false,
    ) {
        if (view == null || lp == null) return
        val lpChanged = lp.x != x || lp.y != y || lp.width != width || lp.height != height || lp.gravity != gravity
        lp.x = x; lp.y = y; lp.width = width; lp.height = height; lp.gravity = gravity
        if (inLauncher) {
            val flp = view.layoutParams as? FrameLayout.LayoutParams ?: return
            val wantLeft = if ((gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.LEFT) x else 0
            val wantTop = if ((gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP) y else 0
            if (force || flp.width != width || flp.height != height || flp.gravity != gravity ||
                flp.leftMargin != wantLeft || flp.topMargin != wantTop || flp.bottomMargin != bottomMargin
            ) {
                flp.width = width; flp.height = height; flp.gravity = gravity
                flp.leftMargin = wantLeft; flp.topMargin = wantTop; flp.bottomMargin = bottomMargin
                view.layoutParams = flp
            }
        } else if (force || lpChanged) {
            updateWindowLayout(view, lp, wm)
        }
    }

    fun updateWindowLayout(view: View?, lp: WindowManager.LayoutParams?, wm: WindowManager?) {
        if (view == null || lp == null || wm == null) return
        if (!view.isAttachedToWindow) {
            if (view.parent == null) {
                view.post {
                    if (view.isAttachedToWindow) {
                        try {
                            wm.updateViewLayout(view, lp)
                        } catch (t: Throwable) {
                            Log.w(TAG, "Deferred updateViewLayout failed for ${view.javaClass.simpleName}", t)
                        }
                    }
                }
            }
            return
        }
        try {
            wm.updateViewLayout(view, lp)
        } catch (t: Throwable) {
            Log.w(TAG, "updateViewLayout failed for ${view.javaClass.simpleName}", t)
        }
    }

    fun removeViewSafe(view: View?, wm: WindowManager?) {
        val parent = view?.parent ?: return
        if (parent is ViewGroup) {
            try {
                parent.removeView(view)
            } catch (t: Throwable) {
                Log.w(TAG, "removeView failed for ${view.javaClass.simpleName}", t)
            }
        } else if (wm != null) {
            try {
                wm.removeViewImmediate(view)
            } catch (t: Throwable) {
                Log.w(TAG, "removeViewImmediate failed for ${view.javaClass.simpleName}", t)
            }
        }
    }

    fun detachAll(wm: WindowManager?) {
        entries.values.forEach { removeViewSafe(it.view, wm) }
        entries.clear()
    }

    private fun attachSingle(entry: SurfaceEntry, inLauncher: Boolean, launcherHost: ViewGroup?, wm: WindowManager?): Boolean {
        if (inLauncher && launcherHost != null && entry.launcherParamsProvider != null) {
            safeAddToGroup(launcherHost, entry.view, entry.launcherParamsProvider.invoke())
            entry.onAttachedToHost?.invoke(true)
            return false
        }
        if (wm != null) {
            try {
                wm.addView(entry.view, entry.wmParams)
                entry.onAttachedToHost?.invoke(false)
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to addView key=${entry.key}", t)
                onWmAddViewFailed?.invoke(entry, t)
            }
        }
        return false
    }

    private fun raiseHigherWmLayers(afterZOrder: Int, wm: WindowManager) {
        entries.values
            .filter { it.layer.zOrder > afterZOrder && it.view.parent != null && it.view.parent !is ViewGroup && it.canDetachForReorder() }
            .sortedBy { it.layer.zOrder }
            .forEach { entry ->
                removeViewSafe(entry.view, wm)
                try {
                    wm.addView(entry.view, entry.wmParams)
                    entry.onAttachedToHost?.invoke(false)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to re-raise WM view key=${entry.key}", t)
                }
            }
    }

    companion object {
        fun safeAddToGroup(host: ViewGroup?, child: View?, lp: ViewGroup.LayoutParams) {
            if (host == null || child == null) return
            (child.parent as? ViewGroup)?.removeView(child)
            if (child.parent == null) host.addView(child, lp)
        }
    }
}

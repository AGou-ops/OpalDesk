package io.github.lengsukq.opaldesk.design.iosglass

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import java.util.concurrent.CopyOnWriteArraySet
import java.util.function.Consumer

/**
 * Centralized Android 12–17 cross-window real-time blur & Android 17 QPR2 (SDK 37.2)
 * backdrop RenderEffect coordinator with automatic low-power / unsupported fallback.
 */
object CrossWindowBlurController {
    private const val TAG = "ExtCrossWinBlur"

    @Volatile
    var isBlurEnabled: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        private set

    private val backdropMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            View::class.java.methods.firstOrNull {
                it.name == "setBackdropRenderEffect" && it.parameterCount == 1
            }
        }.getOrNull()
    }

    val supportsBoundedViewBackdropBlur: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && backdropMethod != null

    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    @Volatile
    private var registeredWm: WindowManager? = null
    private var wmConsumer: Consumer<Boolean>? = null

    fun observe(wm: WindowManager?, onChanged: ((Boolean) -> Unit)? = null) {
        if (onChanged != null) listeners.add(onChanged)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || wm == null) {
            isBlurEnabled = false
            onChanged?.invoke(false)
            return
        }
        try {
            isBlurEnabled = wm.isCrossWindowBlurEnabled
            if (registeredWm !== wm) {
                val prev = registeredWm
                val prevConsumer = wmConsumer
                if (prev != null && prevConsumer != null) {
                    runCatching { prev.removeCrossWindowBlurEnabledListener(prevConsumer) }
                }
                val consumer = Consumer<Boolean> { enabled ->
                    val prevVal = isBlurEnabled
                    isBlurEnabled = enabled
                    if (prevVal != enabled) {
                        listeners.forEach { cb -> runCatching { cb(enabled) } }
                    }
                }
                wm.addCrossWindowBlurEnabledListener(consumer)
                registeredWm = wm
                wmConsumer = consumer
            }
            onChanged?.invoke(isBlurEnabled)
        } catch (t: Throwable) {
            Log.d(TAG, "Cross-window blur listener unavailable: ${t.message}")
            isBlurEnabled = false
            onChanged?.invoke(false)
        }
    }

    /**
     * Ensures persistent/popover overlay `WindowManager.LayoutParams` NEVER set `FLAG_BLUR_BEHIND`
     * (which in SurfaceFlinger blurs the entire display surface behind the overlay Z-layer rather
     * than just the card bounds). Bounded per-card blur is handled at the View/Window background layer.
     */
    fun applyToOverlayParams(
        ctx: Context,
        wm: WindowManager?,
        lp: WindowManager.LayoutParams,
        blurBehindRadiusDp: Float = 28f,
    ): WindowManager.LayoutParams {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return lp
        observe(wm)
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        runCatching { lp.blurBehindRadius = 0 }
        return lp
    }

    /**
     * Configures an Activity [Window] for bounded window-background blur (`setBackgroundBlurRadius`)
     * without `FLAG_BLUR_BEHIND`, preventing full-display blur behind non-fullscreen windows.
     */
    fun applyToActivityWindow(
        window: Window?,
        backgroundBlurRadiusDp: Float = 36f,
        blurBehindRadiusDp: Float = 24f,
    ) {
        if (window == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val density = window.context.resources.displayMetrics.density
        val bgPx = (backgroundBlurRadiusDp * density).toInt().coerceIn(10, 140)
        runCatching {
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 0 }
            window.setBackgroundBlurRadius(bgPx)
        }.onFailure { t ->
            Log.d(TAG, "Window blur setup skipped: ${t.message}")
        }
    }

    /**
     * Attempts to attach Android 17 QPR2 (SDK 37.2) `setBackdropRenderEffect` onto a hardware-accelerated
     * [View] clipped strictly to its outline, gracefully returning `false` on older SDKs.
     */
    fun tryApplyViewBackdropBlur(view: View?, radiusPx: Float): Boolean {
        val m = backdropMethod
        if (view == null || m == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || radiusPx <= 0f) return false
        return runCatching {
            val effect = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.MIRROR)
            view.clipToOutline = true
            m.invoke(view, effect)
            true
        }.getOrDefault(false)
    }
}

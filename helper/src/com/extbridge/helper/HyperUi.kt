package com.extbridge.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.widget.TextView
import com.extbridge.helper.design.iosglass.LiquidGlassDrawable
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Shared iOS Liquid Glass visual tokens and anti-aliased vector icon drawables
 * for Android View-based peripheral overlays (Menu Bar, Dock, Control Center,
 * Window Chrome, and Mirror StandBy Cards).
 */
object HyperUi {
    // iOS Liquid Glass Frosted Palette (冰透毛玻璃 + 连续曲率大圆角)
    const val HYPER_BLUE: Int = 0xFF007AFF.toInt()
    const val HYPER_RED: Int = 0xFFFF3B30.toInt()
    const val HYPER_GREEN: Int = 0xFF34C759.toInt()

    const val TEXT_PRIMARY: Int = 0xFF11141C.toInt()
    const val TEXT_SECONDARY: Int = 0xFF5B6272.toInt()
    const val DIVIDER: Int = 0x180F172A

    const val FLYOUT_BG: Int = 0xCCF9FBFF.toInt()
    const val FLYOUT_STROKE: Int = 0xDCFFFFFF.toInt()
    const val DOCK_BG: Int = 0xC2F8FAFF.toInt()
    const val DOCK_STROKE: Int = 0xDCFFFFFF.toInt()
    const val TILE_INACTIVE: Int = 0x8CF6F9FF.toInt()
    const val CHIP_INACTIVE: Int = 0x7AF4F7FE.toInt()
    const val CHIP_ACTIVE_BG: Int = 0xFF007AFF.toInt()

    // Vector Icon Kinds
    const val ICON_DEX = 1
    const val ICON_MIRROR = 2
    const val ICON_SPLIT = 3
    const val ICON_PIN = 4
    const val ICON_MOON = 5
    const val ICON_SUN = 6
    const val ICON_ROTATE = 7
    const val ICON_FULLSCREEN = 8
    const val ICON_SLIDERS = 9
    const val ICON_SETTINGS = 10
    const val ICON_SEARCH = 11
    const val ICON_WALLPAPER = 12
    const val ICON_APPS = 13
    const val ICON_SWAP = 14
    const val ICON_CLOSE = 15
    const val ICON_WINDOW = 16
    const val ICON_TOUCHPAD = 17
    const val ICON_SIDEBAR = 18
    const val ICON_MEDIA = 20
    const val ICON_CALENDAR = 21
    const val ICON_CPU = 22
    const val ICON_NET = 23
    const val ICON_STORAGE = 24
    const val ICON_TIMER = 25
    const val ICON_MEMO = 26
    const val ICON_ZOOM = 27
    const val ICON_RATIO = 28
    const val ICON_CHEVRON_LEFT = 29
    const val ICON_CHEVRON_RIGHT = 30
    const val ICON_MINUS = 33

    // System-tile & volume icons (unifying MiniIconView into HyperUi)
    const val ICON_WIFI = 40
    const val ICON_WIFI_OFF = 41
    const val ICON_BLUETOOTH = 42
    const val ICON_AIRPLANE = 43
    const val ICON_BATTERY_SAVER = 44
    const val ICON_VOLUME_OFF = 45
    const val ICON_VOLUME_LOW = 46
    const val ICON_VOLUME_HIGH = 47

    // Samsung DeX window chrome & taskbar vector icons
    const val ICON_MAXIMIZE = 50
    const val ICON_RESTORE = 51
    const val ICON_MORE = 52
    const val ICON_HOME_NAV = 53
    const val ICON_RECENTS_NAV = 54
    const val ICON_SHOW_DESKTOP = 55

    fun dp(ctx: Context, v: Float): Int = max(
        1,
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v,
            ctx.resources.displayMetrics,
        ).roundToInt(),
    )

    fun icon(ctx: Context, kind: Int, color: Int, sizeDp: Float): Drawable {
        val px = dp(ctx, sizeDp)
        return VectorIconDrawable(kind, color, px).apply {
            setBounds(0, 0, px, px)
        }
    }

    fun volumeIconKind(level: Int): Int = when {
        level <= 0 -> ICON_VOLUME_OFF
        level == 1 -> ICON_VOLUME_LOW
        else -> ICON_VOLUME_HIGH
    }

    fun setStartIcon(tv: TextView?, kind: Int, color: Int, sizeDp: Float, padDp: Float) {
        if (tv == null) return
        val ctx = tv.context
        val d = icon(ctx, kind, color, sizeDp)
        tv.setCompoundDrawablesRelative(d, null, null, null)
        tv.compoundDrawablePadding = dp(ctx, padDp)
    }

    fun styleHyperChip(tv: TextView?, active: Boolean) {
        if (tv == null) return
        val ctx = tv.context
        val radiusPx = dp(ctx, 16f).toFloat()
        val fill = if (active) CHIP_ACTIVE_BG else CHIP_INACTIVE
        val existing = tv.background as? LiquidGlassDrawable
        if (existing != null) {
            existing.baseFillColor = fill
            existing.darkMode = active
        } else {
            LiquidGlassDrawable.applyToView(
                view = tv,
                fillColor = fill,
                cornerRadiusPx = radiusPx,
                strokeWidthPx = dp(ctx, 1f).toFloat(),
                darkMode = active,
            )
        }
        tv.setTextColor(if (active) 0xFFFFFFFF.toInt() else TEXT_PRIMARY)
    }

    private class VectorIconDrawable(
        private val kind: Int,
        private val color: Int,
        private val sizePx: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val rect = RectF()
        private val path = Path()

        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun draw(c: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            val s = min(w, h)
            val sw = max(1.5f, s * 0.105f)

            c.save()
            c.translate(b.left.toFloat(), b.top.toFloat())
            paint.color = color
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = sw
            path.reset()

            val cx = w * 0.5f
            val cy = h * 0.5f

            when (kind) {
                ICON_DEX -> {
                    rect.set(w * 0.12f, h * 0.16f, w * 0.88f, h * 0.68f)
                    c.drawRoundRect(rect, s * 0.12f, s * 0.12f, paint)
                    c.drawLine(cx, h * 0.68f, cx, h * 0.84f, paint)
                    c.drawLine(w * 0.30f, h * 0.84f, w * 0.70f, h * 0.84f, paint)
                }
                ICON_MIRROR -> {
                    rect.set(w * 0.24f, h * 0.16f, w * 0.90f, h * 0.64f)
                    c.drawRoundRect(rect, s * 0.11f, s * 0.11f, paint)
                    rect.set(w * 0.10f, h * 0.34f, w * 0.66f, h * 0.84f)
                    c.drawRoundRect(rect, s * 0.11f, s * 0.11f, paint)
                }
                ICON_SPLIT -> {
                    rect.set(w * 0.12f, h * 0.18f, w * 0.88f, h * 0.82f)
                    c.drawRoundRect(rect, s * 0.14f, s * 0.14f, paint)
                    c.drawLine(cx, h * 0.18f, cx, h * 0.82f, paint)
                }
                ICON_PIN -> {
                    rect.set(w * 0.30f, h * 0.16f, w * 0.70f, h * 0.54f)
                    c.drawRoundRect(rect, s * 0.10f, s * 0.10f, paint)
                    c.drawLine(w * 0.22f, h * 0.56f, w * 0.78f, h * 0.56f, paint)
                    c.drawLine(cx, h * 0.56f, cx, h * 0.86f, paint)
                }
                ICON_MOON -> {
                    path.moveTo(w * 0.66f, h * 0.18f)
                    path.quadTo(w * 0.26f, h * 0.22f, w * 0.24f, h * 0.56f)
                    path.quadTo(w * 0.24f, h * 0.84f, w * 0.62f, h * 0.84f)
                    path.quadTo(w * 0.80f, h * 0.82f, w * 0.84f, h * 0.68f)
                    path.quadTo(w * 0.50f, h * 0.72f, w * 0.48f, h * 0.42f)
                    path.quadTo(w * 0.48f, h * 0.26f, w * 0.66f, h * 0.18f)
                    c.drawPath(path, paint)
                }
                ICON_SUN -> {
                    c.drawCircle(cx, cy, s * 0.20f, paint)
                    for (i in 0 until 8) {
                        val a = i * Math.PI * 0.25
                        val x1 = cx + cos(a).toFloat() * s * 0.30f
                        val y1 = cy + sin(a).toFloat() * s * 0.30f
                        val x2 = cx + cos(a).toFloat() * s * 0.41f
                        val y2 = cy + sin(a).toFloat() * s * 0.41f
                        c.drawLine(x1, y1, x2, y2, paint)
                    }
                }
                ICON_ROTATE -> {
                    rect.set(w * 0.18f, h * 0.18f, w * 0.82f, h * 0.82f)
                    c.drawArc(rect, -45f, 275f, false, paint)
                    path.moveTo(w * 0.62f, h * 0.14f)
                    path.lineTo(w * 0.78f, h * 0.26f)
                    path.lineTo(w * 0.60f, h * 0.34f)
                    c.drawPath(path, paint)
                }
                ICON_FULLSCREEN -> {
                    val m = w * 0.16f
                    val r = w * 0.84f
                    val t = h * 0.20f
                    val bt = h * 0.80f
                    val l = s * 0.18f
                    c.drawLine(m, t + l, m, t, paint); c.drawLine(m, t, m + l, t, paint)
                    c.drawLine(r - l, t, r, t, paint); c.drawLine(r, t, r, t + l, paint)
                    c.drawLine(m, bt - l, m, bt, paint); c.drawLine(m, bt, m + l, bt, paint)
                    c.drawLine(r - l, bt, r, bt, paint); c.drawLine(r, bt, r, bt - l, paint)
                }
                ICON_SLIDERS -> {
                    c.drawLine(w * 0.16f, h * 0.32f, w * 0.84f, h * 0.32f, paint)
                    c.drawLine(w * 0.16f, h * 0.68f, w * 0.84f, h * 0.68f, paint)
                    paint.style = Paint.Style.FILL
                    c.drawCircle(w * 0.38f, h * 0.32f, s * 0.11f, paint)
                    c.drawCircle(w * 0.64f, h * 0.68f, s * 0.11f, paint)
                }
                ICON_SETTINGS -> {
                    c.drawCircle(cx, cy, s * 0.17f, paint)
                    c.drawCircle(cx, cy, s * 0.36f, paint)
                }
                ICON_SEARCH -> {
                    c.drawCircle(w * 0.44f, h * 0.44f, s * 0.26f, paint)
                    c.drawLine(w * 0.63f, h * 0.63f, w * 0.84f, h * 0.84f, paint)
                }
                ICON_WALLPAPER -> {
                    rect.set(w * 0.14f, h * 0.18f, w * 0.86f, h * 0.82f)
                    c.drawRoundRect(rect, s * 0.13f, s * 0.13f, paint)
                    c.drawCircle(w * 0.36f, h * 0.38f, s * 0.07f, paint)
                    path.moveTo(w * 0.18f, h * 0.76f)
                    path.lineTo(w * 0.46f, h * 0.50f)
                    path.lineTo(w * 0.64f, h * 0.66f)
                    path.lineTo(w * 0.76f, h * 0.56f)
                    path.lineTo(w * 0.84f, h * 0.76f)
                    c.drawPath(path, paint)
                }
                ICON_APPS -> {
                    val g = s * 0.07f
                    rect.set(w * 0.16f, h * 0.16f, cx - g, cy - g)
                    c.drawRoundRect(rect, s * 0.08f, s * 0.08f, paint)
                    rect.set(cx + g, h * 0.16f, w * 0.84f, cy - g)
                    c.drawRoundRect(rect, s * 0.08f, s * 0.08f, paint)
                    rect.set(w * 0.16f, cy + g, cx - g, h * 0.84f)
                    c.drawRoundRect(rect, s * 0.08f, s * 0.08f, paint)
                    rect.set(cx + g, cy + g, w * 0.84f, h * 0.84f)
                    c.drawRoundRect(rect, s * 0.08f, s * 0.08f, paint)
                }
                ICON_SWAP -> {
                    c.drawLine(w * 0.16f, h * 0.36f, w * 0.82f, h * 0.36f, paint)
                    path.moveTo(w * 0.64f, h * 0.20f)
                    path.lineTo(w * 0.82f, h * 0.36f)
                    path.lineTo(w * 0.64f, h * 0.50f)
                    c.drawLine(w * 0.84f, h * 0.64f, w * 0.18f, h * 0.64f, paint)
                    path.moveTo(w * 0.36f, h * 0.50f)
                    path.lineTo(w * 0.18f, h * 0.64f)
                    path.lineTo(w * 0.36f, h * 0.80f)
                    c.drawPath(path, paint)
                }
                ICON_CLOSE -> {
                    c.drawLine(w * 0.24f, h * 0.24f, w * 0.76f, h * 0.76f, paint)
                    c.drawLine(w * 0.76f, h * 0.24f, w * 0.24f, h * 0.76f, paint)
                }
                ICON_WINDOW -> {
                    rect.set(w * 0.14f, h * 0.20f, w * 0.86f, h * 0.80f)
                    c.drawRoundRect(rect, s * 0.12f, s * 0.12f, paint)
                    c.drawLine(w * 0.14f, h * 0.38f, w * 0.86f, h * 0.38f, paint)
                }
                ICON_TOUCHPAD -> {
                    rect.set(w * 0.14f, h * 0.18f, w * 0.86f, h * 0.82f)
                    c.drawRoundRect(rect, s * 0.13f, s * 0.13f, paint)
                    c.drawLine(w * 0.14f, h * 0.64f, w * 0.86f, h * 0.64f, paint)
                    c.drawLine(cx, h * 0.64f, cx, h * 0.82f, paint)
                }
                ICON_SIDEBAR -> {
                    rect.set(w * 0.12f, h * 0.18f, w * 0.88f, h * 0.82f)
                    c.drawRoundRect(rect, s * 0.13f, s * 0.13f, paint)
                    c.drawLine(w * 0.62f, h * 0.18f, w * 0.62f, h * 0.82f, paint)
                }
                ICON_MEDIA -> {
                    c.drawCircle(w * 0.34f, h * 0.72f, s * 0.12f, paint)
                    c.drawCircle(w * 0.72f, h * 0.64f, s * 0.12f, paint)
                    c.drawLine(w * 0.46f, h * 0.72f, w * 0.46f, h * 0.24f, paint)
                    c.drawLine(w * 0.84f, h * 0.64f, w * 0.84f, h * 0.18f, paint)
                    c.drawLine(w * 0.46f, h * 0.24f, w * 0.84f, h * 0.18f, paint)
                }
                ICON_CALENDAR -> {
                    rect.set(w * 0.14f, h * 0.24f, w * 0.86f, h * 0.84f)
                    c.drawRoundRect(rect, s * 0.12f, s * 0.12f, paint)
                    c.drawLine(w * 0.14f, h * 0.42f, w * 0.86f, h * 0.42f, paint)
                    c.drawLine(w * 0.34f, h * 0.14f, w * 0.34f, h * 0.30f, paint)
                    c.drawLine(w * 0.66f, h * 0.14f, w * 0.66f, h * 0.30f, paint)
                }
                ICON_CPU -> {
                    rect.set(w * 0.24f, h * 0.24f, w * 0.76f, h * 0.76f)
                    c.drawRoundRect(rect, s * 0.10f, s * 0.10f, paint)
                    c.drawLine(cx, h * 0.12f, cx, h * 0.24f, paint)
                    c.drawLine(cx, h * 0.76f, cx, h * 0.88f, paint)
                    c.drawLine(w * 0.12f, cy, w * 0.24f, cy, paint)
                    c.drawLine(w * 0.76f, cy, w * 0.88f, cy, paint)
                }
                ICON_NET -> {
                    c.drawCircle(cx, cy, s * 0.36f, paint)
                    c.drawLine(w * 0.14f, cy, w * 0.86f, cy, paint)
                    rect.set(w * 0.34f, h * 0.14f, w * 0.66f, h * 0.86f)
                    c.drawOval(rect, paint)
                }
                ICON_STORAGE -> {
                    rect.set(w * 0.16f, h * 0.20f, w * 0.84f, h * 0.80f)
                    c.drawRoundRect(rect, s * 0.12f, s * 0.12f, paint)
                    c.drawLine(w * 0.16f, h * 0.56f, w * 0.84f, h * 0.56f, paint)
                    paint.style = Paint.Style.FILL
                    c.drawCircle(w * 0.68f, h * 0.68f, sw * 0.7f, paint)
                }
                ICON_TIMER -> {
                    c.drawCircle(cx, h * 0.54f, s * 0.32f, paint)
                    c.drawLine(w * 0.38f, h * 0.14f, w * 0.62f, h * 0.14f, paint)
                    c.drawLine(cx, h * 0.54f, cx, h * 0.36f, paint)
                    c.drawLine(cx, h * 0.54f, w * 0.64f, h * 0.54f, paint)
                }
                ICON_MEMO -> {
                    rect.set(w * 0.18f, h * 0.16f, w * 0.82f, h * 0.84f)
                    c.drawRoundRect(rect, s * 0.12f, s * 0.12f, paint)
                    c.drawLine(w * 0.32f, h * 0.38f, w * 0.68f, h * 0.38f, paint)
                    c.drawLine(w * 0.32f, h * 0.54f, w * 0.68f, h * 0.54f, paint)
                    c.drawLine(w * 0.32f, h * 0.70f, w * 0.54f, h * 0.70f, paint)
                }
                ICON_ZOOM -> {
                    c.drawCircle(w * 0.44f, h * 0.44f, s * 0.26f, paint)
                    c.drawLine(w * 0.63f, h * 0.63f, w * 0.84f, h * 0.84f, paint)
                    c.drawLine(w * 0.34f, h * 0.44f, w * 0.54f, h * 0.44f, paint)
                    c.drawLine(w * 0.44f, h * 0.34f, w * 0.44f, h * 0.54f, paint)
                }
                ICON_RATIO -> {
                    rect.set(w * 0.14f, h * 0.22f, w * 0.86f, h * 0.78f)
                    c.drawRoundRect(rect, s * 0.12f, s * 0.12f, paint)
                    c.drawLine(w * 0.28f, h * 0.64f, w * 0.72f, h * 0.36f, paint)
                }
                ICON_CHEVRON_LEFT -> {
                    path.moveTo(w * 0.62f, h * 0.22f)
                    path.lineTo(w * 0.34f, cy)
                    path.lineTo(w * 0.62f, h * 0.78f)
                    c.drawPath(path, paint)
                }
                ICON_CHEVRON_RIGHT -> {
                    path.moveTo(w * 0.38f, h * 0.22f)
                    path.lineTo(w * 0.66f, cy)
                    path.lineTo(w * 0.38f, h * 0.78f)
                    c.drawPath(path, paint)
                }
                ICON_MINUS -> {
                    c.drawLine(w * 0.24f, cy, w * 0.76f, cy, paint)
                }
                ICON_WIFI, ICON_WIFI_OFF -> {
                    if (kind == ICON_WIFI_OFF) paint.color = color and 0x66FFFFFF
                    paint.strokeWidth = max(1.8f, s * 0.11f)
                    val oy = h * 0.80f
                    rect.set(cx - s * 0.44f, oy - s * 0.62f, cx + s * 0.44f, oy + s * 0.26f)
                    c.drawArc(rect, -135f, 90f, false, paint)
                    rect.set(cx - s * 0.28f, oy - s * 0.40f, cx + s * 0.28f, oy + s * 0.16f)
                    c.drawArc(rect, -135f, 90f, false, paint)
                    paint.style = Paint.Style.FILL
                    c.drawCircle(cx, oy - s * 0.04f, max(1.5f, s * 0.09f), paint)
                    if (kind == ICON_WIFI_OFF) {
                        paint.color = color
                        paint.style = Paint.Style.STROKE
                        c.drawLine(w * 0.18f, h * 0.18f, w * 0.82f, h * 0.82f, paint)
                    }
                }
                ICON_BLUETOOTH -> {
                    paint.strokeWidth = max(1.7f, s * 0.10f)
                    path.moveTo(cx - s * 0.16f, cy - s * 0.20f)
                    path.lineTo(cx + s * 0.18f, cy + s * 0.16f)
                    path.lineTo(cx, cy + s * 0.36f)
                    path.lineTo(cx, cy - s * 0.36f)
                    path.lineTo(cx + s * 0.18f, cy - s * 0.16f)
                    path.lineTo(cx - s * 0.16f, cy + s * 0.20f)
                    c.drawPath(path, paint)
                }
                ICON_AIRPLANE -> {
                    paint.strokeWidth = max(1.8f, s * 0.10f)
                    c.drawLine(w * 0.15f, cy, w * 0.85f, cy, paint)
                    c.drawLine(w * 0.42f, h * 0.16f, w * 0.56f, cy, paint)
                    c.drawLine(w * 0.42f, h * 0.84f, w * 0.56f, cy, paint)
                    c.drawLine(w * 0.18f, h * 0.34f, w * 0.26f, cy, paint)
                    c.drawLine(w * 0.18f, h * 0.66f, w * 0.26f, cy, paint)
                }
                ICON_BATTERY_SAVER -> {
                    paint.strokeWidth = max(1.7f, s * 0.10f)
                    rect.set(w * 0.16f, h * 0.26f, w * 0.82f, h * 0.74f)
                    c.drawRoundRect(rect, s * 0.1f, s * 0.1f, paint)
                    c.drawLine(w * 0.85f, h * 0.40f, w * 0.85f, h * 0.60f, paint)
                    c.drawLine(w * 0.38f, h * 0.50f, w * 0.60f, h * 0.50f, paint)
                    c.drawLine(w * 0.49f, h * 0.38f, w * 0.49f, h * 0.62f, paint)
                }
                ICON_VOLUME_OFF, ICON_VOLUME_LOW, ICON_VOLUME_HIGH -> {
                    paint.style = Paint.Style.FILL
                    path.moveTo(w * 0.14f, h * 0.38f)
                    path.lineTo(w * 0.32f, h * 0.38f)
                    path.lineTo(w * 0.54f, h * 0.20f)
                    path.lineTo(w * 0.54f, h * 0.80f)
                    path.lineTo(w * 0.32f, h * 0.62f)
                    path.lineTo(w * 0.14f, h * 0.62f)
                    path.close()
                    c.drawPath(path, paint)

                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = max(1.6f, s * 0.10f)
                    if (kind == ICON_VOLUME_OFF) {
                        c.drawLine(w * 0.64f, h * 0.36f, w * 0.86f, h * 0.64f, paint)
                        c.drawLine(w * 0.86f, h * 0.36f, w * 0.64f, h * 0.64f, paint)
                    } else {
                        rect.set(w * 0.50f, h * 0.32f, w * 0.72f, h * 0.68f)
                        c.drawArc(rect, -50f, 100f, false, paint)
                        if (kind == ICON_VOLUME_HIGH) {
                            rect.set(w * 0.52f, h * 0.18f, w * 0.88f, h * 0.82f)
                            c.drawArc(rect, -52f, 104f, false, paint)
                        }
                    }
                }
                ICON_MAXIMIZE -> {
                    rect.set(w * 0.22f, h * 0.22f, w * 0.78f, h * 0.78f)
                    c.drawRoundRect(rect, s * 0.14f, s * 0.14f, paint)
                }
                ICON_RESTORE -> {
                    rect.set(w * 0.20f, h * 0.30f, w * 0.68f, h * 0.78f)
                    c.drawRoundRect(rect, s * 0.12f, s * 0.12f, paint)
                    path.moveTo(w * 0.34f, h * 0.28f)
                    path.lineTo(w * 0.34f, h * 0.20f)
                    path.lineTo(w * 0.80f, h * 0.20f)
                    path.lineTo(w * 0.80f, h * 0.66f)
                    path.lineTo(w * 0.70f, h * 0.66f)
                    c.drawPath(path, paint)
                }
                ICON_MORE -> {
                    paint.style = Paint.Style.FILL
                    val r = max(1.6f, s * 0.085f)
                    c.drawCircle(w * 0.26f, cy, r, paint)
                    c.drawCircle(cx, cy, r, paint)
                    c.drawCircle(w * 0.74f, cy, r, paint)
                }
                ICON_HOME_NAV -> {
                    c.drawCircle(cx, cy, s * 0.30f, paint)
                }
                ICON_RECENTS_NAV -> {
                    c.drawLine(w * 0.28f, h * 0.24f, w * 0.28f, h * 0.76f, paint)
                    c.drawLine(cx, h * 0.24f, cx, h * 0.76f, paint)
                    c.drawLine(w * 0.72f, h * 0.24f, w * 0.72f, h * 0.76f, paint)
                }
                ICON_SHOW_DESKTOP -> {
                    rect.set(w * 0.16f, h * 0.22f, w * 0.84f, h * 0.78f)
                    c.drawRoundRect(rect, s * 0.14f, s * 0.14f, paint)
                    c.drawLine(w * 0.16f, h * 0.64f, w * 0.84f, h * 0.64f, paint)
                }
            }
            c.restore()
        }
    }
}

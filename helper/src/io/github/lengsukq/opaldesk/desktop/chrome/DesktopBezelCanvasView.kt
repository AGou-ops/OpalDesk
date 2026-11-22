package io.github.lengsukq.opaldesk.desktop.chrome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.WindowManager
import io.github.lengsukq.opaldesk.design.iosglass.SquircleMath
import io.github.lengsukq.opaldesk.desktop.model.DesktopGeometry
import io.github.lengsukq.opaldesk.desktop.model.DesktopWindow
import io.github.lengsukq.opaldesk.desktop.model.GlassOverlayTokens
import io.github.lengsukq.opaldesk.desktop.model.IntRect
import io.github.lengsukq.opaldesk.desktop.model.PreviewOverlayState
import io.github.lengsukq.opaldesk.desktop.state.DesktopShellHost
import io.github.lengsukq.opaldesk.overlay.OverlayConstants
import io.github.lengsukq.opaldesk.overlay.OverlayLayer
import io.github.lengsukq.opaldesk.overlay.OverlaySurfaceHost
import io.github.lengsukq.opaldesk.overlay.OverlayWindowParams
import io.github.lengsukq.opaldesk.overlay.dpF
import kotlin.math.max
import kotlin.math.min

/**
 * Single hardware-accelerated fullscreen `FLAG_NOT_TOUCHABLE` canvas replacing per-window
 * bezel & preview WindowManager surfaces.
 *
 * Renders in one unified draw pass:
 * 1. Continuous G2 Squircle glass borders & focused glow for all visible windows in Z-order
 *    (clipping out upper windows' outer frames so lower borders never bleed on top);
 * 2. Live 60fps frosted glass snap / 8-way resize preview outline + real-time dp dimension HUD.
 */
internal class DesktopBezelCanvasController(private val host: DesktopShellHost) {

    companion object {
        private const val SURFACE_KEY = "dex_bezel_canvas"
    }

    private var canvasView: DesktopBezelCanvasView? = null
    private var lp: WindowManager.LayoutParams? = null

    fun build(ctx: Context) {
        detach()
        val w = max(OverlayConstants.MIN_SURFACE_PX, host.state.displayW)
        val h = max(OverlayConstants.MIN_SURFACE_PX, host.state.displayH)
        val view = DesktopBezelCanvasView(ctx, host.tokens)
        val wmLp = OverlayWindowParams.fullscreenNonTouchable(w, h, host.winType)
        canvasView = view
        lp = wmLp
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(SURFACE_KEY, OverlayLayer.BASE_CHROME, view, wmLp))
    }

    fun render(
        windows: List<DesktopWindow>,
        dpi: Int,
        draggingTaskId: Int,
        draggingRect: IntRect?,
        preview: PreviewOverlayState,
        secondaryPreviewRect: IntRect? = null,
        tokens: GlassOverlayTokens,
    ) {
        canvasView?.updateScene(windows, dpi, draggingTaskId, draggingRect, preview, secondaryPreviewRect, tokens)
    }

    fun onDisplayBoundsChanged(w: Int, h: Int) {
        val view = canvasView ?: return
        val wmLp = lp ?: return
        val nw = max(OverlayConstants.MIN_SURFACE_PX, w)
        val nh = max(OverlayConstants.MIN_SURFACE_PX, h)
        if (wmLp.width != nw || wmLp.height != nh) {
            wmLp.width = nw
            wmLp.height = nh
            host.surfaceHost.updateWindowLayout(view, wmLp, host.extWm)
        }
    }

    fun detach() {
        host.surfaceHost.unregister(SURFACE_KEY, host.extWm)
        canvasView = null
        lp = null
    }
}

private class DesktopBezelCanvasView(
    ctx: Context,
    private var tokens: GlassOverlayTokens,
) : View(ctx) {

    private data class DrawFrame(val outer: IntRect, val focused: Boolean)

    private val density = ctx.resources.displayMetrics.density
    private val cornerPx = ctx.dpF(DesktopGeometry.WINDOW_CORNER_RADIUS_DP)
    private val frames = ArrayList<DrawFrame>()
    private var preview = PreviewOverlayState()
    private var secondaryPreview: IntRect? = null

    private val outerPath = Path()
    private val innerPath = Path()
    private val previewPath = Path()
    private val hudRect = RectF()

    private val outerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
    }
    private val shadowRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val innerSpecPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.85f * density
    }
    private val scrimPaint = Paint().apply { style = Paint.Style.FILL }
    private val previewFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val previewStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.75f * density
    }
    private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hudStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
    }
    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12.5f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    fun updateScene(
        windows: List<DesktopWindow>,
        dpi: Int,
        draggingTaskId: Int,
        draggingRect: IntRect?,
        previewState: PreviewOverlayState,
        secondaryRect: IntRect?,
        newTokens: GlassOverlayTokens,
    ) {
        tokens = newTokens
        preview = previewState
        secondaryPreview = secondaryRect
        frames.clear()
        for (w in windows) {
            val taskRect = if (w.taskId == draggingTaskId && draggingRect != null && !draggingRect.isEmpty) {
                draggingRect
            } else {
                w.bounds
            }
            frames.add(DrawFrame(DesktopGeometry.computeOuterFrame(taskRect, dpi), w.focused || w.taskId == draggingTaskId))
        }
        val shouldShow = frames.isNotEmpty() || preview.active
        visibility = if (shouldShow) VISIBLE else GONE
        if (shouldShow) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return

        // 1. Draw window Squircle borders bottom -> top, clipping out all upper window frames
        for (i in frames.indices) {
            val f = frames[i]
            val r = f.outer
            if (r.isEmpty) continue

            val save = canvas.save()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                for (upperIdx in (i + 1) until frames.size) {
                    val u = frames[upperIdx].outer
                    if (!u.isEmpty && r.intersects(u)) {
                        canvas.clipOutRect(u.left.toFloat(), u.top.toFloat(), u.right.toFloat(), u.bottom.toFloat())
                    }
                }
            }
            val rad = min(cornerPx, min(r.width, r.height) * 0.35f)
            // Soft multi-pass ambient outer drop halo gives floating windows natural macOS depth
            if (f.focused) {
                shadowRingPaint.color = if (tokens.isDark) 0x26000000 else 0x180F172A
                shadowRingPaint.strokeWidth = 5.5f * density
                val sOff = 2.6f * density
                SquircleMath.buildAndroidPath(
                    r.left - sOff, r.top - sOff * 0.5f, r.right + sOff, r.bottom + sOff * 1.35f,
                    SquircleMath.CornerRadiiPx.uniform(rad + sOff), target = outerPath,
                )
                canvas.drawPath(outerPath, shadowRingPaint)
            }
            outerStrokePaint.color = if (f.focused) tokens.bezelFocusedOuter else tokens.bezelUnfocusedOuter
            outerStrokePaint.strokeWidth = (if (f.focused) 1.15f else 0.9f) * density
            val oIn = outerStrokePaint.strokeWidth * 0.5f
            SquircleMath.buildAndroidPath(
                r.left + oIn, r.top + oIn, r.right - oIn, r.bottom - oIn,
                SquircleMath.CornerRadiiPx.uniform(rad), target = outerPath,
            )
            canvas.drawPath(outerPath, outerStrokePaint)

            innerSpecPaint.color = tokens.bezelInnerSpec
            val iIn = outerStrokePaint.strokeWidth + innerSpecPaint.strokeWidth * 0.5f
            val innerRad = max(0f, rad - 1f)
            SquircleMath.buildAndroidPath(
                r.left + iIn, r.top + iIn, r.right - iIn, r.bottom - iIn,
                SquircleMath.CornerRadiiPx.uniform(innerRad), target = innerPath,
            )
            canvas.drawPath(innerPath, innerSpecPaint)
            canvas.restoreToCount(save)
        }

        // 2. Draw live 120fps frosted glass snap / move / resize preview ghost + dimension HUD
        if (preview.active && !preview.outerRect.isEmpty) {
            if (preview.isSnapped) {
                scrimPaint.color = tokens.previewScrim
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
            }

            drawPreviewBox(canvas, preview.outerRect, preview.isSnapped)
            secondaryPreview?.takeIf { !it.isEmpty }?.let { drawPreviewBox(canvas, it, preview.isSnapped) }

            if (preview.hudLabel.isNotEmpty()) {
                drawHudPill(canvas, preview.outerRect, preview.hudLabel, preview.isSnapped)
            }
        }
    }

    private fun drawPreviewBox(canvas: Canvas, rect: IntRect, snapped: Boolean) {
        val rad = min(cornerPx, min(rect.width, rect.height) * 0.35f)
        previewFillPaint.color = if (snapped) tokens.previewSnapFill else tokens.previewFill
        previewStrokePaint.color = if (snapped) tokens.accentGreen else tokens.accentBlue
        SquircleMath.buildAndroidPath(
            rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(),
            SquircleMath.CornerRadiiPx.uniform(rad), target = previewPath,
        )
        canvas.drawPath(previewPath, previewFillPaint)
        canvas.drawPath(previewPath, previewStrokePaint)
    }

    private fun drawHudPill(canvas: Canvas, anchorRect: IntRect, label: String, snapped: Boolean) {
        hudBgPaint.color = tokens.hudFill
        hudStrokePaint.color = if (snapped) tokens.accentGreen else tokens.accentBlue
        hudTextPaint.color = tokens.textPrimary
        val pillH = 34f * density
        val pillW = hudTextPaint.measureText(label) + 32f * density
        val cx = anchorRect.centerX.toFloat().coerceIn(pillW * 0.5f + 12f * density, width - pillW * 0.5f - 12f * density)
        val topY = (anchorRect.top + 18f * density).coerceIn(14f * density, max(14f * density, height - pillH - 20f * density))
        hudRect.set(cx - pillW * 0.5f, topY, cx + pillW * 0.5f, topY + pillH)
        canvas.drawRoundRect(hudRect, pillH * 0.5f, pillH * 0.5f, hudBgPaint)
        canvas.drawRoundRect(hudRect, pillH * 0.5f, pillH * 0.5f, hudStrokePaint)
        val fm = hudTextPaint.fontMetrics
        canvas.drawText(label, hudRect.centerX(), hudRect.centerY() - (fm.ascent + fm.descent) * 0.5f, hudTextPaint)
    }
}

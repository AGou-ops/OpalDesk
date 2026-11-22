package io.github.lengsukq.opaldesk.desktop.workbench

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.HyperUi
import io.github.lengsukq.opaldesk.desktop.model.DesktopGeometry
import io.github.lengsukq.opaldesk.desktop.model.DesktopState
import io.github.lengsukq.opaldesk.desktop.model.DesktopWindow
import io.github.lengsukq.opaldesk.desktop.model.GlassOverlayTokens
import io.github.lengsukq.opaldesk.desktop.model.IntRect
import io.github.lengsukq.opaldesk.desktop.state.DesktopShellHost
import io.github.lengsukq.opaldesk.overlay.MATCH
import io.github.lengsukq.opaldesk.overlay.OverlayLayer
import io.github.lengsukq.opaldesk.overlay.OverlaySurfaceHost
import io.github.lengsukq.opaldesk.overlay.OverlayUiUtils
import io.github.lengsukq.opaldesk.overlay.OverlayWindowParams
import io.github.lengsukq.opaldesk.overlay.WRAP
import io.github.lengsukq.opaldesk.overlay.dp
import io.github.lengsukq.opaldesk.overlay.dpF
import io.github.lengsukq.opaldesk.overlay.flp
import io.github.lengsukq.opaldesk.overlay.llp
import io.github.lengsukq.opaldesk.overlay.roundedBg
import io.github.lengsukq.opaldesk.overlay.setPadDp
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import kotlin.math.max

/**
 * Liquid Glass Workbench Mode (Stage Manager / Live App Card Rail) docked along either the
 * Left edge (`WORKBENCH_POS_LEFT`) or Top edge (`WORKBENCH_POS_TOP`) of the DeX desktop.
 *
 * Shows pinned Workbench apps plus active running apps as interactive status cards with
 * live `TaskSnapshot` visual previews, active/running/standby badges, one-tap Stage switching,
 * one-tap Left/Top rail toggle, and an in-DeX `+ 添加` app picker.
 */
@SuppressLint("ClickableViewAccessibility")
internal class DeXWorkbenchRailController(private val host: DesktopShellHost) {

    companion object {
        private const val SURFACE_KEY = "dex_workbench_rail"
        const val LEFT_RAIL_WIDTH_DP = 196f
        const val TOP_RAIL_HEIGHT_DP = 120f
        private const val RAIL_EDGE_MARGIN_DP = 10f
        private const val LOCAL_DEX_TOP_CLEARANCE_DP = 34f
        private const val SNAPSHOT_REFRESH_MS = 4500L
        private const val MAX_CACHED_SNAPSHOTS = 14
    }

    data class CardModel(
        val pkg: String,
        val taskId: Int,
        val pinned: Boolean,
        val running: Boolean,
        val focused: Boolean,
        val minimized: Boolean,
    )

    private data class CachedSnap(
        val bitmap: Bitmap,
        val ownBitmap: Boolean,
        val capturedAtMs: Long,
        val taskId: Int,
    )

    private var railShell: FrameLayout? = null
    private var railRoot: LinearLayout? = null
    private var railLp: WindowManager.LayoutParams? = null
    private var cardsContainer: LinearLayout? = null
    private var titleLabel: TextView? = null
    private var countBadge: TextView? = null
    private var btnFlipPos: TextView? = null
    private var btnAdd: TextView? = null
    private var btnClose: ImageView? = null

    private var lastBuiltPosition = -1
    private var lastBuiltDark: Boolean? = null
    private var lastCardSig = ""
    private var lastIsLocalDex = false
    private var lastVisible = false

    private val snapshotCache = LinkedHashMap<String, CachedSnap>()
    private val inFlightCaptures = HashSet<String>()
    private val cardThumbViews = HashMap<String, ImageView>()

    private val periodicSnapshotTask = object : Runnable {
        override fun run() {
            if (!lastVisible || !host.isWorkbenchEnabled) return
            refreshRunningSnapshots(forceAll = false)
            host.handler.postDelayed(this, SNAPSHOT_REFRESH_MS)
        }
    }

    fun build(ctx: Context) {
        detach()
        lastBuiltPosition = -1
        lastBuiltDark = null
        lastCardSig = ""
        val shell = FrameLayout(ctx).apply {
            visibility = View.GONE
            clipChildren = false
            clipToPadding = false
        }
        val lp = computeLayoutParams(ctx, host.workbenchPosition)
        railShell = shell
        railLp = lp
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(SURFACE_KEY, OverlayLayer.DOCK, shell, lp))
        rebuildInnerLayoutForPosition(ctx, host.workbenchPosition)
    }

    fun detach() {
        host.handler.removeCallbacks(periodicSnapshotTask)
        lastVisible = false
        host.surfaceHost.unregister(SURFACE_KEY, host.extWm)
        railShell = null
        railRoot = null
        railLp = null
        cardsContainer = null
        titleLabel = null
        countBadge = null
        btnFlipPos = null
        btnAdd = null
        btnClose = null
        cardThumbViews.clear()
        inFlightCaptures.clear()
        clearSnapshotCache()
    }

    fun onDisplayBoundsChanged(ctx: Context) {
        if (railShell == null) return
        updateLayoutGeometry(ctx)
    }

    fun invalidateCards() {
        lastCardSig = ""
    }

    /** Captures a fresh snapshot of a specific task (and optionally schedules a follow-up capture after UI settles). */
    fun captureSnapshotNow(taskId: Int, pkg: String, followUpDelayMs: Long = 0L) {
        if (taskId <= 0 || pkg.isBlank()) return
        requestSnapshotCapture(taskId, pkg, force = true)
        if (followUpDelayMs > 0L) {
            host.handler.postDelayed({
                if (lastVisible && host.isWorkbenchEnabled) {
                    requestSnapshotCapture(taskId, pkg, force = true)
                }
            }, followUpDelayMs)
        }
    }

    /** Computes the main Stage window bounds that leave the Workbench Rail completely unobstructed. */
    fun computeStageRect(
        displayW: Int,
        displayH: Int,
        dpi: Int,
        taskbarAutoHide: Boolean,
        isLocalDex: Boolean,
    ): IntRect {
        val wa = DesktopGeometry.computeWorkArea(displayW, displayH, dpi, taskbarAutoHide)
        val headerPx = DesktopGeometry.dpToPx(DesktopGeometry.WINDOW_HEADER_HEIGHT_DP, dpi)
        val gapPx = DesktopGeometry.dpToPx(10, dpi)
        return if (host.workbenchPosition == AppPrefs.WORKBENCH_POS_TOP) {
            val topMarginDp = if (isLocalDex) LOCAL_DEX_TOP_CLEARANCE_DP else RAIL_EDGE_MARGIN_DP
            val railBottomPx = DesktopGeometry.dpToPx((topMarginDp + TOP_RAIL_HEIGHT_DP).toInt(), dpi)
            val left = wa.left + gapPx / 2
            val right = max(left + 420, wa.right - gapPx / 2)
            val top = minOf(max(wa.top + headerPx, railBottomPx + gapPx + headerPx), max(wa.top + headerPx, wa.bottom - 260))
            IntRect(left, top, right, max(top + 240, wa.bottom - gapPx / 2))
        } else {
            val railRightPx = DesktopGeometry.dpToPx((RAIL_EDGE_MARGIN_DP + LEFT_RAIL_WIDTH_DP).toInt(), dpi)
            val left = minOf(railRightPx + gapPx, max(wa.left, wa.right - 460))
            val right = max(left + 420, wa.right - gapPx / 2)
            val top = wa.top + headerPx
            IntRect(left, top, right, max(top + 240, wa.bottom - gapPx / 2))
        }
    }

    fun render(
        ctx: Context,
        state: DesktopState,
        tokens: GlassOverlayTokens,
        isLocalDex: Boolean,
    ) {
        val shell = railShell ?: return
        val shouldShow = host.isWorkbenchEnabled && state.activeImmersiveWindow == null
        val pos = host.workbenchPosition
        val posChanged = (railRoot == null || lastBuiltPosition != pos)
        val localChanged = lastIsLocalDex != isLocalDex
        lastIsLocalDex = isLocalDex

        if (posChanged) {
            rebuildInnerLayoutForPosition(ctx, pos)
            updateLayoutGeometry(ctx)
        }

        if (!shouldShow) {
            if (lastVisible || shell.visibility != View.GONE) {
                lastVisible = false
                shell.visibility = View.GONE
                host.handler.removeCallbacks(periodicSnapshotTask)
            }
            return
        }

        val themeChanged = lastBuiltDark != tokens.isDark
        if (themeChanged) {
            lastBuiltDark = tokens.isDark
            lastCardSig = ""
            applyRailTheme(ctx, tokens)
        }
        if (localChanged || !lastVisible) {
            updateLayoutGeometry(ctx)
        }
        if (!lastVisible || shell.visibility != View.VISIBLE) {
            lastVisible = true
            shell.visibility = View.VISIBLE
            host.handler.removeCallbacks(periodicSnapshotTask)
            host.handler.postDelayed(periodicSnapshotTask, 650L)
        }

        val models = buildCardModels(state)
        val sig = buildString {
            append(pos).append('|').append(tokens.isDark).append('|')
            for (m in models) {
                append(m.pkg).append(':').append(m.taskId).append(':')
                    .append(if (m.pinned) 1 else 0)
                    .append(if (m.running) 1 else 0)
                    .append(if (m.focused) 1 else 0)
                    .append(if (m.minimized) 1 else 0)
                    .append(',')
            }
        }
        if (sig != lastCardSig) {
            lastCardSig = sig
            rebuildCards(ctx, models, tokens)
            refreshRunningSnapshots(forceAll = false)
        }
    }

    private fun buildCardModels(state: DesktopState): List<CardModel> {
        val pinned = AppPrefs.getWorkbenchApps(host.serviceContext)
        val byPkg = LinkedHashMap<String, CardModel>()
        val winByPkg = HashMap<String, DesktopWindow>()
        for (w in state.windows) {
            winByPkg[w.pkg] = w
        }
        for (pkg in pinned) {
            val w = winByPkg[pkg]
            byPkg[pkg] = CardModel(
                pkg = pkg,
                taskId = w?.taskId ?: -1,
                pinned = true,
                running = w != null && w.taskId > 0,
                focused = w != null && w.focused && !w.minimized,
                minimized = w?.minimized == true,
            )
        }
        // Also surface currently running DeX windows even if not yet pinned so users never lose an open window
        for (w in state.windows) {
            if (byPkg.containsKey(w.pkg)) continue
            if (w.pkg == host.serviceContext.packageName) continue
            byPkg[w.pkg] = CardModel(
                pkg = w.pkg,
                taskId = w.taskId,
                pinned = false,
                running = w.taskId > 0,
                focused = w.focused && !w.minimized,
                minimized = w.minimized,
            )
        }
        return byPkg.values.toList()
    }

    private fun rebuildInnerLayoutForPosition(ctx: Context, position: Int) {
        val shell = railShell ?: return
        shell.removeAllViews()
        cardThumbViews.clear()
        lastBuiltPosition = position
        lastBuiltDark = null
        lastCardSig = ""

        val isTop = position == AppPrefs.WORKBENCH_POS_TOP
        val root = LinearLayout(ctx).apply {
            orientation = if (isTop) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (isTop) Gravity.CENTER_VERTICAL else Gravity.TOP
            setPadDp(if (isTop) 12f else 10f, 10f, if (isTop) 12f else 10f, 10f)
            elevation = ctx.dpF(18f)
        }

        val header = buildControlsBar(ctx, isTop)
        if (isTop) {
            root.addView(header, ctx.llp(WRAP, MATCH, rightDp = 10f))
        } else {
            root.addView(header, ctx.llp(MATCH, WRAP, bottomDp = 8f))
        }

        val container = LinearLayout(ctx).apply {
            orientation = if (isTop) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (isTop) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
        }
        cardsContainer = container

        if (isTop) {
            val hsv = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(container, FrameLayout.LayoutParams(WRAP, MATCH))
            }
            root.addView(hsv, ctx.llp(0, MATCH, 1f))
        } else {
            val sv = ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(container, FrameLayout.LayoutParams(MATCH, WRAP))
            }
            root.addView(sv, ctx.llp(MATCH, 0, 1f))
        }

        railRoot = root
        applyRailTheme(ctx, host.tokens)
        shell.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun buildControlsBar(ctx: Context, isTop: Boolean): LinearLayout {
        val bar = LinearLayout(ctx).apply {
            orientation = if (isTop) LinearLayout.VERTICAL else LinearLayout.VERTICAL
            gravity = if (isTop) Gravity.CENTER_VERTICAL else Gravity.START
        }

        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleLabel = TextView(ctx).apply {
            text = "工作台"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f)
            paint.isFakeBoldText = true
            isSingleLine = true
        }.also { topRow.addView(it, ctx.llp(WRAP, WRAP, rightDp = 5f)) }

        countBadge = TextView(ctx).apply {
            text = "0"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadDp(6f, 1.5f)
        }.also { topRow.addView(it, ctx.llp(WRAP, WRAP)) }

        topRow.addView(View(ctx), ctx.llp(0, 1, 1f))

        btnClose = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tooltipText = "关闭工作台模式"
            OverlayUiUtils.attachTactilePress(this, 0.88f)
            setOnClickListener { host.setWorkbenchEnabled(false) }
        }.also { topRow.addView(it, ctx.llp(ctx.dp(24f), ctx.dp(24f), leftDp = 4f)) }

        bar.addView(topRow, ctx.llp(if (isTop) ctx.dp(112f) else MATCH, WRAP, bottomDp = 6f))

        val actionsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnFlipPos = TextView(ctx).apply {
            text = if (isTop) "切左侧" else "切顶部"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            isSingleLine = true
            setPadDp(8f, 5f)
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            OverlayUiUtils.attachTactilePress(this, 0.92f)
            setOnClickListener {
                val next = if (host.workbenchPosition == AppPrefs.WORKBENCH_POS_TOP) {
                    AppPrefs.WORKBENCH_POS_LEFT
                } else {
                    AppPrefs.WORKBENCH_POS_TOP
                }
                host.setWorkbenchPosition(next)
            }
        }.also { actionsRow.addView(it, ctx.llp(0, WRAP, 1f, rightDp = 5f)) }

        btnAdd = TextView(ctx).apply {
            text = "+ 添加"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            isSingleLine = true
            setPadDp(8f, 5f)
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            OverlayUiUtils.attachTactilePress(this, 0.92f)
            setOnClickListener { host.openWorkbenchAppPicker() }
        }.also { actionsRow.addView(it, ctx.llp(0, WRAP, 1f)) }

        bar.addView(actionsRow, ctx.llp(if (isTop) ctx.dp(112f) else MATCH, WRAP))
        return bar
    }

    private fun computeLayoutParams(ctx: Context, position: Int): WindowManager.LayoutParams {
        val dispW = max(600, host.state.displayW)
        val dispH = max(480, host.state.displayH)
        val marginPx = ctx.dp(RAIL_EDGE_MARGIN_DP)
        return if (position == AppPrefs.WORKBENCH_POS_TOP) {
            val topY = ctx.dp(if (lastIsLocalDex) LOCAL_DEX_TOP_CLEARANCE_DP else RAIL_EDGE_MARGIN_DP)
            val w = max(ctx.dp(360f), dispW - marginPx * 2)
            val h = ctx.dp(TOP_RAIL_HEIGHT_DP)
            OverlayWindowParams.floating(
                w, h, host.winType,
                x = 0, y = topY,
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                touchModal = false,
            )
        } else {
            val bottomClearPx = ctx.dp(DesktopGeometry.TASKBAR_RESERVED_INSET_DP.toFloat() + 6f)
            val w = ctx.dp(LEFT_RAIL_WIDTH_DP)
            val h = max(ctx.dp(260f), dispH - bottomClearPx - marginPx * 2)
            OverlayWindowParams.floating(
                w, h, host.winType,
                x = marginPx, y = marginPx,
                gravity = Gravity.TOP or Gravity.START,
                touchModal = false,
            )
        }
    }

    private fun updateLayoutGeometry(ctx: Context) {
        val shell = railShell ?: return
        val next = computeLayoutParams(ctx, host.workbenchPosition)
        railLp = next
        host.surfaceHost.updateWindowLayout(shell, next, host.extWm)
    }

    private fun applyRailTheme(ctx: Context, tokens: GlassOverlayTokens) {
        railRoot?.let { tokens.applyGlassBackground(it, tokens.taskbarFill, cornerRadiusDp = 24f, strokeWidthDp = 1f) }
        titleLabel?.setTextColor(tokens.textPrimary)
        countBadge?.setTextColor(tokens.accentBlue)
        countBadge?.background = roundedBg((tokens.accentBlue and 0x00FFFFFF) or 0x22000000, ctx.dpF(9f))
        btnFlipPos?.setTextColor(tokens.textPrimary)
        btnFlipPos?.background = OverlayUiUtils.makeStateListBg(ctx, tokens.cardInnerFill, tokens.activeChipFill, 10f)
        btnAdd?.setTextColor(Color.WHITE)
        btnAdd?.background = roundedBg(tokens.accentBlue, ctx.dpF(10f))
        btnClose?.setImageDrawable(HyperUi.icon(ctx, HyperUi.ICON_CLOSE, tokens.textSecondary, 12f))
        btnClose?.background = OverlayUiUtils.makeStateListBg(ctx, 0x00000000, tokens.cardInnerFill, 9f)
    }

    private fun rebuildCards(ctx: Context, models: List<CardModel>, tokens: GlassOverlayTokens) {
        val container = cardsContainer ?: return
        val isTop = host.workbenchPosition == AppPrefs.WORKBENCH_POS_TOP
        container.removeAllViews()
        cardThumbViews.clear()
        countBadge?.text = models.size.toString()

        if (models.isEmpty()) {
            val emptyCard = TextView(ctx).apply {
                text = "点击「+ 添加」加入常用应用\n随时查看状态并一键切换"
                setTextColor(tokens.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
                gravity = Gravity.CENTER
                setPadDp(12f, 18f)
                background = roundedBg(tokens.cardInnerFill, ctx.dpF(16f))
                setOnClickListener { host.openWorkbenchAppPicker() }
            }
            container.addView(
                emptyCard,
                if (isTop) ctx.llp(ctx.dp(220f), MATCH) else ctx.llp(MATCH, WRAP),
            )
            return
        }

        val pm = host.serviceContext.packageManager
        val cardW = if (isTop) ctx.dp(156f) else MATCH
        val cardH = if (isTop) MATCH else ctx.dp(112f)

        models.forEachIndexed { idx, model ->
            val card = buildAppCard(ctx, pm, model, tokens, isTop)
            val lp = if (isTop) {
                ctx.llp(cardW, cardH, rightDp = if (idx < models.lastIndex) 8f else 0f)
            } else {
                ctx.llp(cardW, cardH, bottomDp = if (idx < models.lastIndex) 8f else 0f)
            }
            container.addView(card, lp)
        }
    }

    private fun buildAppCard(
        ctx: Context,
        pm: android.content.pm.PackageManager,
        model: CardModel,
        tokens: GlassOverlayTokens,
        isTop: Boolean,
    ): LinearLayout {
        val label = host.getCachedLabel(pm, model.pkg)
        val icon = host.getCachedIcon(pm, model.pkg)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadDp(6f, 6f, 6f, 5f)
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            OverlayUiUtils.attachTactilePress(this, 0.96f)
            val strokeColor = if (model.focused) tokens.accentBlue else tokens.divider
            val fillColor = if (model.focused) {
                (tokens.accentBlue and 0x00FFFFFF) or 0x26000000
            } else {
                tokens.cardInnerFill
            }
            background = roundedBg(
                fillColor,
                ctx.dpF(16f),
                ctx.dp(if (model.focused) 1.8f else 0.8f).coerceAtLeast(1),
                strokeColor,
            )
            setOnClickListener { host.switchToWorkbenchApp(model.pkg, model.taskId) }
            setOnLongClickListener { v ->
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                host.openWorkbenchCardMenu(model.pkg, model.taskId, model.pinned, loc[0] + v.width / 2, loc[1] + v.height / 2)
                true
            }
            setOnTouchListener { v, ev ->
                if (ev?.actionMasked == MotionEvent.ACTION_DOWN && (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                    host.openWorkbenchCardMenu(model.pkg, model.taskId, model.pinned, ev.rawX.toInt(), ev.rawY.toInt())
                    return@setOnTouchListener true
                }
                false
            }
        }

        // Top Visual Preview Box (shows real live TaskSnapshot when running, or frosted icon card on standby)
        val previewBox = FrameLayout(ctx).apply {
            background = roundedBg(
                if (tokens.isDark) 0x55121826 else 0x88E8EEF6.toInt(),
                ctx.dpF(11f),
                1,
                if (tokens.isDark) 0x22FFFFFF else 0x24000000,
            )
            clipToOutline = true
        }

        val standbyIcon = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(icon)
            alpha = if (model.running) 0.40f else 0.92f
        }
        previewBox.addView(standbyIcon, ctx.flp(ctx.dp(32f), ctx.dp(32f), Gravity.CENTER))

        val thumbImg = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        previewBox.addView(thumbImg, FrameLayout.LayoutParams(MATCH, MATCH))
        cardThumbViews[model.pkg] = thumbImg

        val cached = snapshotCache[model.pkg]
        if (model.running && cached != null && !cached.bitmap.isRecycled) {
            thumbImg.setImageBitmap(cached.bitmap)
            thumbImg.visibility = View.VISIBLE
        }

        // Status Pill Badge ("● 当前" / "● 运行中" / "待命")
        val (statusText, statusBg) = when {
            model.focused -> "● 当前" to tokens.accentBlue
            model.running -> "● 运行中" to 0xDD1FA958.toInt()
            else -> "待命" to 0xAA334155.toInt()
        }
        val statusPill = TextView(ctx).apply {
            text = statusText
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 8.8f)
            paint.isFakeBoldText = true
            setPadDp(6f, 1.5f)
            background = roundedBg(statusBg, ctx.dpF(7f))
        }
        previewBox.addView(
            statusPill,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
                topMargin = ctx.dp(4f)
                rightMargin = ctx.dp(4f)
            },
        )

        card.addView(previewBox, ctx.llp(MATCH, 0, 1f, bottomDp = 4f))

        // Compact Footer: App Icon + Name + "···" menu button
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(
            ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageDrawable(icon) },
            ctx.llp(ctx.dp(16f), ctx.dp(16f), rightDp = 5f),
        )
        footer.addView(
            TextView(ctx).apply {
                text = label
                setTextColor(tokens.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (isTop) 10.5f else 11f)
                paint.isFakeBoldText = model.focused
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            },
            ctx.llp(0, WRAP, 1f),
        )
        footer.addView(
            TextView(ctx).apply {
                text = "···"
                setTextColor(tokens.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setPadDp(4f, 0f)
                setOnClickListener { v ->
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    host.openWorkbenchCardMenu(model.pkg, model.taskId, model.pinned, loc[0], loc[1])
                }
            },
            ctx.llp(WRAP, WRAP, leftDp = 2f),
        )
        card.addView(footer, ctx.llp(MATCH, WRAP))
        return card
    }

    private fun refreshRunningSnapshots(forceAll: Boolean) {
        val now = SystemClock.uptimeMillis()
        for (w in host.state.windows) {
            if (w.taskId <= 0 || !cardThumbViews.containsKey(w.pkg)) continue
            val cached = snapshotCache[w.pkg]
            val stale = forceAll || cached == null || cached.bitmap.isRecycled ||
                cached.taskId != w.taskId || (now - cached.capturedAtMs) >= SNAPSHOT_REFRESH_MS
            if (stale) {
                requestSnapshotCapture(w.taskId, w.pkg, force = forceAll)
            }
        }
    }

    private fun requestSnapshotCapture(taskId: Int, pkg: String, force: Boolean) {
        if (!force && inFlightCaptures.contains(pkg)) return
        inFlightCaptures.add(pkg)
        host.safeBgExecute {
            val snap = try {
                BridgeClient.captureTaskSnapshot(taskId, pkg, true)
            } catch (_: Throwable) {
                null
            }
            host.safePost {
                inFlightCaptures.remove(pkg)
                val bmp = snap?.bitmap
                if (bmp == null || bmp.isRecycled) return@safePost
                if (!lastVisible || railRoot == null) {
                    if (snap.ownBitmap) bmp.recycle()
                    return@safePost
                }
                val prev = snapshotCache.put(pkg, CachedSnap(bmp, snap.ownBitmap, SystemClock.uptimeMillis(), taskId))
                if (prev != null && prev.ownBitmap && prev.bitmap !== bmp && !prev.bitmap.isRecycled) {
                    prev.bitmap.recycle()
                }
                trimSnapshotCache()
                val iv = cardThumbViews[pkg]
                if (iv != null && iv.isAttachedToWindow) {
                    iv.setImageBitmap(bmp)
                    iv.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun trimSnapshotCache() {
        while (snapshotCache.size > MAX_CACHED_SNAPSHOTS) {
            val it = snapshotCache.entries.iterator()
            if (!it.hasNext()) break
            val oldest = it.next().value
            it.remove()
            if (oldest.ownBitmap && !oldest.bitmap.isRecycled) {
                oldest.bitmap.recycle()
            }
        }
    }

    private fun clearSnapshotCache() {
        for (entry in snapshotCache.values) {
            if (entry.ownBitmap && !entry.bitmap.isRecycled) {
                entry.bitmap.recycle()
            }
        }
        snapshotCache.clear()
    }
}

package com.extbridge.helper.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.StageTransitionView
import com.extbridge.helper.ipc.DaemonStateStore
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Coordinates 2/3/4-app split-screen dividers ([OverlaySurfaceHost]), topology/ratio commits,
 * [SplitControlMenuView], and [StageChoreographer] choreography (including exit-split-to-home).
 */
class SplitScreenOverlayController(private val host: OverlayHost) {

    companion object {
        private const val SURFACE_KEY_PRIMARY = "split_div_primary"
        private const val SURFACE_KEY_SECONDARY = "split_div_secondary"
    }

    private data class DividerHandle(val key: String, val root: FrameLayout, val pill: TextView, val lp: WindowManager.LayoutParams)

    private val state = SplitDividerState()
    private var primaryHandle: DividerHandle? = null
    private var secondaryHandle: DividerHandle? = null
    private var dividerAnimator: ValueAnimator? = null
    private var draggingPrimary = false
    private var draggingSecondary = false
    private val delayedPollTask = Runnable { host.pollRemoteStateAsync() }

    private val menuView = SplitControlMenuView(
        host, state,
        onSelectTopology = { applyTopologyChoice(it) },
        onSelectRatioPreset = { applyPrimaryRatioPreset(it) },
        onSwap = { performSplitSwapAnimated() },
        onExit = { performExitTilingOrSplit() },
    )

    val displayedSplitRatio: Float get() = state.primaryRatio

    fun toggleSplitMenu() = menuView.toggle()
    fun resetClosedVisualsBeforeHostSwap() = menuView.resetClosedVisuals()
    fun repositionSplitDivider() = positionAllDividers()
    fun repositionSplitMenu() = menuView.reposition()

    fun buildSplitOverlays(ctx: Context) {
        val portrait = host.overlayH > host.overlayW
        primaryHandle = createDividerHandle(
            ctx, SURFACE_KEY_PRIMARY, DividerAxis.PRIMARY,
            ctx.dp(if (portrait) 76f else 24f), ctx.dp(if (portrait) 24f else 76f),
            max(0, host.overlayW / 2 - ctx.dp(12f)), max(0, host.overlayH / 2 - ctx.dp(38f)),
        )
        secondaryHandle = createDividerHandle(
            ctx, SURFACE_KEY_SECONDARY, DividerAxis.SECONDARY,
            ctx.dp(76f), ctx.dp(24f),
            max(0, host.overlayW / 2 - ctx.dp(38f)), max(0, host.overlayH / 2 - ctx.dp(12f)),
        )
        menuView.build(ctx)
    }

    private fun createDividerHandle(
        ctx: Context, key: String, axis: DividerAxis, initW: Int, initH: Int, initX: Int, initY: Int,
    ): DividerHandle {
        val root = FrameLayout(ctx).apply { visibility = View.GONE }
        val pill = TextView(ctx).apply {
            setTextColor(0xFFF8FAFC.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            paint.isFakeBoldText = true; gravity = Gravity.CENTER; elevation = ctx.dpF(12f)
            background = roundedBg(0xE00F172A.toInt(), ctx.dpF(12f), ctx.dp(1f), 0x8838BDF8.toInt())
        }
        root.addView(pill, ctx.flp(MATCH, MATCH))
        SplitTopologyLayout.attachDividerDragListener(
            root, axis, ctx.dp(6f), state, { host.overlayW to host.overlayH },
            onDragStateChanged = { a, d -> if (a == DividerAxis.PRIMARY) draggingPrimary = d else draggingSecondary = d },
            onLiveUpdate = { positionAllDividers() },
            onTap = { menuView.toggle() },
            onDragCommit = { commitCurrentDividerRatios(fromDragRelease = true) },
        )
        val lp = OverlayWindowParams.floating(initW, initH, host.winType, initX, initY, touchModal = true)
        host.surfaceHost.register(
            OverlaySurfaceHost.SurfaceEntry(
                key, OverlayLayer.SPLIT_DIVIDER, root, lp,
                launcherParamsProvider = { ctx.flp(lp.width, lp.height, Gravity.TOP or Gravity.LEFT) },
            ),
        )
        return DividerHandle(key, root, pill, lp)
    }

    fun triggerQuickSplitOrMenu() {
        host.popovers.dismissAllExcept(menuView.id)
        if (host.lastStatus.isMultiTilingActive || BridgeClient.isTilingTopology(state.topology)) {
            menuView.toggle()
        } else {
            performEnterOrAssignSplitAnimated(BridgeClient.SPLIT_TOGGLE_QUICK, 0, null, null)
        }
    }

    fun updateSplitUi(st: BridgeClient.Status) {
        val snap = host.lastWindowSnapshot
        state.topology = SplitTopologyLayout.resolveActiveTopology(st, snap, host.overlayH > host.overlayW)
        val active = st.connected && st.vdId > 0 && st.mirrorMode != 1 &&
            (st.splitActive == 1 || BridgeClient.isTilingTopology(state.topology))

        host.syncOverlayHostSurface(false)
        host.updateSplitButtons(active)

        if (!draggingPrimary && !draggingSecondary && dividerAnimator?.isRunning != true) {
            SplitTopologyLayout.syncStateFromDaemon(state, st, snap)
        }
        menuView.updateSelection(active)

        val singleEmpty = st.splitSlot0Pkg.isEmpty() != st.splitSlot1Pkg.isEmpty()
        val isMulti = SplitTopologyLayout.isMultiAppTopology(state.topology)
        val showPrimary = active && (!host.dock.isOnLauncherHome || singleEmpty || isMulti)
        setDividerVisible(primaryHandle?.root, showPrimary)
        setDividerVisible(secondaryHandle?.root, showPrimary && isMulti)
        if (showPrimary) positionAllDividers()
        host.cursor.bringCursorToFront()
    }

    private fun positionAllDividers() {
        val ctx = host.extCtx ?: return
        val (primary, secondary) = SplitTopologyLayout.computePlacements(
            state, host.surfaceWidth, host.surfaceHeight, ctx.dp(26f), ctx.dp(80f), ctx.dp(80f), ctx.dp(26f),
        )
        applyPlacement(primaryHandle, ctx, primary, draggingPrimary)
        if (secondary != null) applyPlacement(secondaryHandle, ctx, secondary, draggingSecondary)
    }

    private fun applyPlacement(handle: DividerHandle?, ctx: Context, p: DividerPlacement, dragging: Boolean) {
        if (handle == null) return
        if (handle.pill.text?.toString() != p.label) handle.pill.text = p.label
        (handle.pill.background as? GradientDrawable)?.apply {
            setStroke(ctx.dp(if (dragging) 2f else 1f), if (dragging) 0xFF38BDF8.toInt() else 0x8838BDF8.toInt())
            setColor(if (dragging) 0xF50284C7.toInt() else 0xE00F172A.toInt())
        }
        host.surfaceHost.updatePosition(handle.root, handle.lp, host.inLauncherHost, host.extWm, p.x, p.y, p.width, p.height, Gravity.TOP or Gravity.LEFT)
    }

    private fun setDividerVisible(divider: FrameLayout?, show: Boolean) {
        if (divider == null) return
        if (show && divider.visibility != View.VISIBLE) {
            divider.animate().cancel()
            divider.visibility = View.VISIBLE; divider.alpha = 0f; divider.scaleX = 0.55f; divider.scaleY = 0.55f
            divider.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220L).setInterpolator(OvershootInterpolator(1.4f)).start()
        } else if (!show && divider.visibility == View.VISIBLE) {
            divider.resetPopoverVisuals(); divider.visibility = View.GONE
        }
    }

    private fun currentTilingPkgsList(): List<String> = buildSet {
        host.lastWindowSnapshot.windows.forEach { if (!it.minimized && !OverlayConstants.isSystemOrLauncherPkg(it.pkg)) add(it.pkg) }
        val st = host.lastStatus
        if (!OverlayConstants.isSystemOrLauncherPkg(st.splitSlot0Pkg)) add(st.splitSlot0Pkg)
        if (!OverlayConstants.isSystemOrLauncherPkg(st.splitSlot1Pkg)) add(st.splitSlot1Pkg)
    }.toList()

    private fun applyTopologyChoice(topology: String) {
        val slotLimit = when (topology) {
            BridgeClient.LAYOUT_GRID_2X2 -> 4
            BridgeClient.LAYOUT_SPLIT_3_MASTER, BridgeClient.LAYOUT_SPLIT_3_COLS -> 3
            else -> 2
        }
        val defaultRatios = if (topology == BridgeClient.LAYOUT_SPLIT_3_COLS) listOf(33, 34, 33) else listOf(50, 50)
        host.safeBgExecute {
            val pkgs = LinkedHashSet(currentTilingPkgsList())
            BridgeClient.getTasks().forEach { if (!OverlayConstants.isSystemOrLauncherPkg(it.pkg)) pkgs.add(it.pkg) }
            val ok = BridgeClient.setTilingLayout(topology, pkgs.take(slotLimit), defaultRatios)
            host.safePost {
                when {
                    ok -> host.serviceContext.toast("已切换：${BridgeClient.layoutModeLabel(topology)}")
                    slotLimit == 2 -> { performEnterOrAssignSplitAnimated(BridgeClient.SPLIT_TOGGLE_QUICK, 0, null, null); return@safePost }
                    else -> host.serviceContext.toast("请先打开应用，或在桌面长按图标选择「加入 2/3/4 分屏」", long = true)
                }
                host.pollRemoteStateAsync()
            }
        }
    }

    private fun applyPrimaryRatioPreset(pct: Int) {
        val clamped = pct.coerceIn(25, 75)
        when {
            state.topology == BridgeClient.LAYOUT_SPLIT_3_COLS -> {
                val c0 = clamped.coerceIn(20, 60)
                state.col0Pct = c0; state.col1Pct = ((100 - c0) / 2).coerceIn(20, 80 - c0)
                positionAllDividers(); commitCurrentDividerRatios(false)
            }
            SplitTopologyLayout.isMultiAppTopology(state.topology) -> {
                state.primaryRatio = clamped / 100f
                positionAllDividers(); commitCurrentDividerRatios(false)
            }
            else -> performSplitRatioAnimated(clamped, fromDragRelease = false)
        }
    }

    private fun commitCurrentDividerRatios(fromDragRelease: Boolean) {
        val topo = state.topology; val pkgs = currentTilingPkgsList()
        when (topo) {
            BridgeClient.LAYOUT_SPLIT_3_COLS -> {
                val c0 = state.col0Pct.coerceIn(20, 60); val c1 = state.col1Pct.coerceIn(20, 80 - c0); val c2 = (100 - c0 - c1).coerceAtLeast(20)
                host.runDaemonAndPoll("三列比例：$c0% : $c1% : $c2%") { BridgeClient.setTilingLayout(topo, pkgs, listOf(c0, c1, c2)) }
            }
            BridgeClient.LAYOUT_SPLIT_3_MASTER, BridgeClient.LAYOUT_GRID_2X2 -> {
                val p0 = (state.primaryRatio * 100f).roundToInt().coerceIn(25, 75); val p1 = (state.secondaryRatio * 100f).roundToInt().coerceIn(25, 75)
                host.runDaemonAndPoll("${BridgeClient.layoutModeLabel(topo)}比例：$p0% / $p1%") { BridgeClient.setTilingLayout(topo, pkgs, listOf(p0, p1)) }
            }
            BridgeClient.LAYOUT_SPLIT_2_H, BridgeClient.LAYOUT_SPLIT_2_V -> {
                val pct = (state.primaryRatio * 100f).roundToInt().coerceIn(25, 75)
                host.runDaemonAndPoll("分屏比例：$pct% : ${100 - pct}%") {
                    if (!BridgeClient.setTilingLayout(topo, pkgs, listOf(pct, 100 - pct))) BridgeClient.setSplitRatio(pct)
                }
            }
            else -> performSplitRatioAnimated((state.primaryRatio * 100f).roundToInt().coerceIn(25, 75), fromDragRelease)
        }
    }

    private fun performExitTilingOrSplit() {
        if (SplitTopologyLayout.isMultiAppTopology(state.topology)) {
            host.runDaemonAndPoll("已退出多应用分屏") {
                BridgeClient.setTilingLayout(BridgeClient.LAYOUT_EXIT, "", "")
                BridgeClient.exitSplitMode()
            }
        } else {
            performExitSplitAnimated()
        }
    }

    private fun animateSplitDividerTo(targetRatio: Float, durationMs: Long) {
        dividerAnimator?.cancel(); dividerAnimator = null
        val clamped = targetRatio.coerceIn(0.25f, 0.75f)
        if (abs(clamped - state.primaryRatio) < 0.005f || primaryHandle == null) {
            state.primaryRatio = clamped; positionAllDividers(); return
        }
        dividerAnimator = ValueAnimator.ofFloat(state.primaryRatio, clamped).apply {
            duration = durationMs; interpolator = PathInterpolator(0.20f, 0.96f, 0.28f, 1.0f)
            addUpdateListener { state.primaryRatio = (it.animatedValue as Float).coerceIn(0.25f, 0.75f); positionAllDividers() }
            start()
        }
    }

    // --- StageChoreographer-backed Split Transitions ---

    private fun captureLivePair(p0: String, p1: String): Pair<BridgeClient.TaskSnapshotData?, BridgeClient.TaskSnapshotData?> =
        p0.takeIf { it.isNotEmpty() }?.let { BridgeClient.captureTaskSnapshot(-1, it, true) } to
            p1.takeIf { it.isNotEmpty() }?.let { BridgeClient.captureTaskSnapshot(-1, it, true) }

    fun performEnterOrAssignSplitAnimated(action: Int, taskId: Int, pkg: String?, cls: String?) {
        val extCtx = host.extCtx
        if (host.stageAnimView == null || extCtx == null) {
            host.safeBgExecute {
                val sr = BridgeClient.splitActionDetailed(action, taskId, pkg, cls)
                host.safePost {
                    if (action == BridgeClient.SPLIT_TOGGLE_QUICK && !sr.ok) showSplitHintToast()
                    host.pollRemoteStateAsync()
                }
            }
            return
        }
        val dp16 = extCtx.dpF(16f); val dp8 = extCtx.dpF(8f); val shiftPx = extCtx.dp(56f)
        val portrait = host.overlayH > host.overlayW
        val fullRect = OverlayUiUtils.getFullStageRect(host.overlayW, host.overlayH)
        val preSt = host.lastStatus; val wasSplit = preSt.splitActive == 1
        val curRatio = if (wasSplit) state.primaryRatio else (if (preSt.splitRatioPct > 0) preSt.splitRatioPct / 100f else 0.50f)

        host.stageChoreographer.runTransition(
            backendReadyDelayMs = 130L,
            prepareCards = {
                val nonSys = BridgeClient.getTasks().filterNot { OverlayConstants.isSystemOrLauncherPkg(it.pkg) }
                val top = nonSys.firstOrNull { it.isTopExt() } ?: nonSys.firstOrNull()
                val second = nonSys.firstOrNull { it != top }
                if (action == BridgeClient.SPLIT_TOGGLE_QUICK && top == null && second == null) return@runTransition null

                val (pred0, pred1) = when (action) {
                    BridgeClient.SPLIT_TOGGLE_QUICK -> top?.pkg.orEmpty() to second?.pkg.orEmpty()
                    BridgeClient.SPLIT_ASSIGN_LEFT_TOP -> pkg.orEmpty() to (if (wasSplit && pkg != preSt.splitSlot1Pkg) preSt.splitSlot1Pkg else "")
                    else -> (if (wasSplit && pkg != preSt.splitSlot0Pkg) preSt.splitSlot0Pkg else "") to pkg.orEmpty()
                }
                val topPkg = top?.pkg.orEmpty()
                val snap0 = pred0.takeIf { it.isNotEmpty() }?.let {
                    BridgeClient.captureTaskSnapshot(if (it == topPkg) top?.taskId ?: -1 else -1, it, it == topPkg || (wasSplit && it == preSt.splitSlot0Pkg))
                }
                val snap1 = pred1.takeIf { it.isNotEmpty() }?.let {
                    BridgeClient.captureTaskSnapshot(if (it == topPkg) top?.taskId ?: -1 else second?.taskId ?: -1, it, it == topPkg || (wasSplit && it == preSt.splitSlot1Pkg))
                }
                val r = curRatio.coerceIn(0.25f, 0.75f)
                val end0 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 0, r)
                val end1 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 1, r)
                val start0 = resolveStartRect(pred0, preSt.splitSlot0Pkg, topPkg, wasSplit, fullRect, end0, curRatio, 0, portrait, shiftPx)
                val start1 = resolveStartRect(pred1, preSt.splitSlot1Pkg, topPkg, wasSplit, fullRect, end1, curRatio, 1, portrait, shiftPx)

                host.buildCardSpec(pred0, snap0, start0, end0).apply {
                    startCornerPx = if (start0 == fullRect) 0f else dp16; endCornerPx = dp8
                    startAlpha = if (start0 == fullRect || (wasSplit && pred0 == preSt.splitSlot0Pkg)) 1f else 0.30f
                } to host.buildCardSpec(pred1, snap1, start1, end1).apply {
                    startCornerPx = if (start1 == fullRect) 0f else dp16; endCornerPx = dp8
                    startAlpha = if (start1 == fullRect || (wasSplit && pred1 == preSt.splitSlot1Pkg)) 1f else 0.30f
                }
            },
            extractCards = { pair -> if (pair == null) emptyList() else listOf(pair.first, pair.second) },
            startAnimation = { anim, pair, done ->
                if (pair == null) done.run() else anim.playSplitTransition(pair.first, pair.second, portrait, 285L, false, done)
            },
            performBackend = { pair -> if (pair == null) null else BridgeClient.splitActionDetailed(action, taskId, pkg, cls) },
            onBackendResultUi = { pair, sr ->
                if (pair == null) { showSplitHintToast(); return@runTransition }
                if (action == BridgeClient.SPLIT_TOGGLE_QUICK) {
                    if (sr?.ok == true) host.serviceContext.toast(if (portrait) "已开启上下分屏（拖动分割条可调比例）" else "已开启左右分屏（拖动分割条可调比例）")
                    else showSplitHintToast()
                }
                host.pollRemoteStateAsync()
            },
        )
    }

    private fun resolveStartRect(
        pred: String, preSlot: String, topPkg: String, wasSplit: Boolean,
        fullRect: Rect, endRect: Rect, curRatio: Float, slotIdx: Int, portrait: Boolean, shiftPx: Int,
    ): Rect = when {
        wasSplit && pred == preSlot -> OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, slotIdx, curRatio)
        pred.isNotEmpty() && pred == topPkg -> fullRect
        else -> SplitTopologyLayout.offsetSlideInRect(endRect, portrait, slotIdx == 0, shiftPx)
    }

    private fun performSplitRatioAnimated(pct: Int, fromDragRelease: Boolean) {
        val clampedPct = pct.coerceIn(25, 75)
        val oldRatio = state.primaryRatio; val newRatio = clampedPct / 100f
        DaemonStateStore.transformStatus { it.copy(splitRatioPct = clampedPct) }
        val dur = if (fromDragRelease) 180L else 240L
        animateSplitDividerTo(newRatio, dur)

        val extCtx = host.extCtx
        if (host.stageAnimView == null || extCtx == null || abs(newRatio - oldRatio) < 0.015f || fromDragRelease) {
            host.runDaemonAndPoll("分屏比例：$clampedPct% : ${100 - clampedPct}%") { BridgeClient.setSplitRatio(clampedPct) }
            return
        }
        val dp8 = extCtx.dpF(8f); val dp6 = extCtx.dpF(6f)
        val p0 = host.lastStatus.splitSlot0Pkg; val p1 = host.lastStatus.splitSlot1Pkg
        val portrait = host.overlayH > host.overlayW
        val old0 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 0, oldRatio)
        val old1 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 1, oldRatio)
        val new0 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 0, newRatio)
        val new1 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 1, newRatio)

        host.stageChoreographer.runTransition(
            frame0TimeoutMs = 40L,
            prepareCards = {
                val (s0, s1) = captureLivePair(p0, p1)
                host.buildCardSpec(p0, s0, old0, new0).apply { startCornerPx = dp8; endCornerPx = dp6 } to
                    host.buildCardSpec(p1, s1, old1, new1).apply { startCornerPx = dp8; endCornerPx = dp6 }
            },
            extractCards = { listOf(it.first, it.second) },
            startAnimation = { anim, pair, done -> anim.playSplitTransition(pair.first, pair.second, portrait, dur, false, done) },
            performBackend = { BridgeClient.setSplitRatio(clampedPct) },
            onBackendResultUi = { _, _ -> host.serviceContext.toast("分屏比例：$clampedPct% : ${100 - clampedPct}%") },
        )
    }

    private fun performSplitSwapAnimated() {
        val extCtx = host.extCtx
        if (host.stageAnimView == null || extCtx == null) {
            host.runDaemonAndPoll { BridgeClient.swapSplitSlots() }; return
        }
        val dp8 = extCtx.dpF(8f)
        val p0 = host.lastStatus.splitSlot0Pkg; val p1 = host.lastStatus.splitSlot1Pkg
        val portrait = host.overlayH > host.overlayW
        val r0 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 0, state.primaryRatio)
        val r1 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 1, state.primaryRatio)

        primaryHandle?.pill?.let { pill ->
            pill.animate().cancel()
            pill.animate().scaleX(1.16f).scaleY(1.16f).setDuration(130L).withEndAction {
                primaryHandle?.pill?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(170L)?.setInterpolator(OvershootInterpolator(1.6f))?.start()
            }.start()
        }
        host.stageChoreographer.runTransition(
            prepareCards = {
                val (s0, s1) = captureLivePair(p0, p1)
                host.buildCardSpec(p0, s0, r0, r1).apply { mode = StageTransitionView.MODE_SWAP_OVER; startCornerPx = dp8; endCornerPx = dp8 } to
                    host.buildCardSpec(p1, s1, r1, r0).apply { mode = StageTransitionView.MODE_SWAP_UNDER; startCornerPx = dp8; endCornerPx = dp8 }
            },
            extractCards = { listOf(it.first, it.second) },
            startAnimation = { anim, pair, done -> anim.playSplitTransition(pair.first, pair.second, portrait, 300L, true, done) },
            performBackend = { BridgeClient.swapSplitSlots() },
        )
    }

    fun performExitSplitAnimated() {
        val extCtx = host.extCtx
        if (SplitTopologyLayout.isMultiAppTopology(state.topology) || host.stageAnimView == null || extCtx == null) {
            host.runDaemonAndPoll("已退出分屏") {
                BridgeClient.setTilingLayout(BridgeClient.LAYOUT_EXIT, "", ""); BridgeClient.exitSplitMode()
            }
            return
        }
        val p0 = host.lastStatus.splitSlot0Pkg; val p1 = host.lastStatus.splitSlot1Pkg
        val portrait = host.overlayH > host.overlayW; val shiftPx = extCtx.dp(56f)
        val r0 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 0, state.primaryRatio)
        val r1 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 1, state.primaryRatio)
        val fullRect = OverlayUiUtils.getFullStageRect(host.overlayW, host.overlayH)
        val expandSlot0 = p0.isNotEmpty() || p1.isEmpty()

        primaryHandle?.root?.takeIf { it.visibility == View.VISIBLE }?.let { div ->
            div.animate().cancel()
            div.animate().alpha(0f).scaleX(0.55f).scaleY(0.55f).setDuration(150L).withEndAction {
                primaryHandle?.root?.let { it.visibility = View.GONE; it.resetPopoverVisuals() }
            }.start()
        }
        host.stageChoreographer.runTransition(
            prepareCards = {
                val (s0, s1) = captureLivePair(p0, p1)
                if (expandSlot0) {
                    host.buildCardSpec(p0, s0, r0, fullRect) to
                        p1.takeIf { it.isNotEmpty() }?.let { host.buildCardSpec(it, s1, r1, SplitTopologyLayout.offsetSlideInRect(r1, portrait, false, shiftPx)) }
                } else {
                    host.buildCardSpec(p1, s1, r1, fullRect) to
                        p0.takeIf { it.isNotEmpty() }?.let { host.buildCardSpec(it, s0, r0, SplitTopologyLayout.offsetSlideInRect(r0, portrait, true, shiftPx)) }
                }
            },
            extractCards = { listOf(it.first, it.second) },
            startAnimation = { anim, pair, done -> anim.playSplitExit(pair.first, pair.second, done) },
            performBackend = { BridgeClient.exitSplitMode() },
            onBackendResultUi = { _, _ -> host.serviceContext.toast("已退出分屏") },
        )
    }

    fun performExitSplitToHomeAnimated(anchorRect: RectF) {
        val extCtx = host.extCtx
        if (host.stageAnimView == null || extCtx == null) {
            host.runDaemonAndPoll { BridgeClient.relaunchLauncher() }; return
        }
        val cornerStart = extCtx.dpF(10f); val cornerEnd = extCtx.dpF(9f)
        val anchorInt = Rect(anchorRect.left.roundToInt(), anchorRect.top.roundToInt(), anchorRect.right.roundToInt(), anchorRect.bottom.roundToInt())
        val fullRect = OverlayUiUtils.getFullStageRect(host.overlayW, host.overlayH)
        val p0 = host.lastStatus.splitSlot0Pkg; val p1 = host.lastStatus.splitSlot1Pkg
        val portrait = host.overlayH > host.overlayW; val ratio = state.primaryRatio

        host.stageChoreographer.runTransition(
            prepareCards = {
                val (s0, s1) = captureLivePair(p0, p1)
                val r0 = s0?.bounds ?: OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 0, ratio)
                val r1 = s1?.bounds ?: OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 1, ratio)
                fun minCard(p: String, s: BridgeClient.TaskSnapshotData?, r: Rect) = p.takeIf { it.isNotEmpty() }?.let {
                    host.buildCardSpec(it, s, r, anchorInt).apply {
                        mode = StageTransitionView.MODE_MINIMIZE_TO_ICON; startCornerPx = cornerStart; endCornerPx = cornerEnd
                    }
                }
                (minCard(p0, s0, r0) ?: host.buildCardSpec(null, null, fullRect, fullRect)) to minCard(p1, s1, r1)
            },
            extractCards = { listOf(it.first, it.second) },
            startAnimation = { anim, pair, done -> anim.playSplitTransition(pair.first, pair.second, portrait, 250L, false, done) },
            performBackend = { BridgeClient.relaunchLauncher() },
            onBackendResultUi = { _, _ ->
                host.handler.removeCallbacks(delayedPollTask)
                host.handler.postDelayed(delayedPollTask, 260L)
            },
        )
    }

    private fun showSplitHintToast() =
        host.serviceContext.toast("请先打开应用，或在桌面长按/右键图标选择「分屏：放入左/右屏」", long = true)

    fun detach() {
        host.handler.removeCallbacks(delayedPollTask)
        dividerAnimator?.cancel(); dividerAnimator = null
        menuView.detach()
        host.surfaceHost.unregister(SURFACE_KEY_PRIMARY, host.extWm)
        host.surfaceHost.unregister(SURFACE_KEY_SECONDARY, host.extWm)
        primaryHandle = null; secondaryHandle = null
        draggingPrimary = false; draggingSecondary = false
        state.topology = BridgeClient.LAYOUT_NONE
    }
}

package com.extbridge.helper.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.HyperUi
import com.extbridge.helper.StageTransitionView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Manages the macOS/HyperOS floating Dock bar, running-apps task strip, app shortcut bubble,
 * bottom-edge wake trigger, auto-hide timer, and Dock minimize/restore/home choreography.
 */
class DockOverlayController(private val host: OverlayHost) {

    companion object {
        private const val DOCK_AUTO_HIDE_DELAY_MS = 2600L
    }

    var dockRoot: LinearLayout? = null
        private set
    var dockLp: WindowManager.LayoutParams? = null
        private set
    var dockEdgeTrigger: FrameLayout? = null
        private set
    var dockEdgeLp: WindowManager.LayoutParams? = null
        private set
    var taskBubbleRoot: LinearLayout? = null
        private set
    var taskBubbleCard: LinearLayout? = null
        private set
    var taskBubbleLp: WindowManager.LayoutParams? = null
        private set
    var trayBtn: LinearLayout? = null
        private set
    var taskStrip: LinearLayout? = null
        private set
    var btnDockSplit: TextView? = null
        private set

    var isDockHidden: Boolean = false
        private set
    var isDockAutoHide: Boolean = true
    var isOnLauncherHome: Boolean = true
    var isTaskBubbleOpen: Boolean = false
        private set
    var isTrayPressed: Boolean = false
        private set
    var lastTrayUpMs: Long = 0L
        private set
    var lastTrayToggleMs: Long = 0L
        private set

    private var autoHideScheduled: Boolean = false
    private var dockHovered: Boolean = false
    private var dockEdgePill: View? = null
    private var btnToggle: TextView? = null
    private var taskDivider: View? = null
    private var trayWifiIcon: MiniIconView? = null
    private var trayVolIcon: MiniIconView? = null
    private var trayBattView: MiniBatteryView? = null
    private var trayBattPct: TextView? = null

    private var bubbleTriangle: View? = null
    private var lastBubbleOpenMs: Long = 0L
    private var bubbleIcon: ImageView? = null
    private var bubbleTitle: TextView? = null
    private var bubbleModeTag: TextView? = null
    private var bubbleFreeformBtn: TextView? = null
    private var bubbleSplit0Btn: TextView? = null
    private var bubbleSplit1Btn: TextView? = null
    private var bubbleCloseBtn: TextView? = null
    private var bubbleTask: BridgeClient.TaskEntry? = null
    private var bubbleLabel: String = ""
    private var bubbleAnchorCenterX: Int = 0

    private var lastTaskSig: String = ""

    private val autoHideDockTask = Runnable {
        autoHideScheduled = false
        if (isDockAutoHide && !isOnLauncherHome && !host.isPanelOpen() &&
            !host.isSplitMenuOpen() && !dockHovered && !isTrayPressed
        ) {
            applyDockHiddenAnimated(true)
        }
    }

    fun clearTaskSignature() {
        lastTaskSig = ""
    }

    fun resetClosedVisualsBeforeHostSwap() {
        if (!isTaskBubbleOpen) {
            taskBubbleCard?.animate()?.cancel()
            taskBubbleRoot?.let {
                it.animate().cancel()
                it.visibility = View.GONE
                it.alpha = 1f
            }
        }
        if (isDockHidden) {
            dockRoot?.let {
                it.animate().cancel()
                it.visibility = View.GONE
                it.translationY = 0f
                it.alpha = 1f
            }
        }
    }

    fun buildDockOverlay(ctx: Context, st: BridgeClient.Status) {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            setPadding(
                OverlayUiUtils.dp(ctx, 10f),
                OverlayUiUtils.dp(ctx, 5f),
                OverlayUiUtils.dp(ctx, 10f),
                OverlayUiUtils.dp(ctx, 5f),
            )
            elevation = OverlayUiUtils.dp(ctx, 10f).toFloat()
            background = GradientDrawable().apply {
                setColor(HyperUi.DOCK_BG)
                cornerRadius = OverlayUiUtils.dp(ctx, 22f).toFloat()
                setStroke(1, HyperUi.DOCK_STROKE)
            }
            setOnHoverListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                        dockHovered = true
                        cancelAutoHideTimer()
                    }
                    MotionEvent.ACTION_HOVER_EXIT -> {
                        dockHovered = false
                        evaluateDockAutoHide(false)
                    }
                }
                false
            }
        }
        dockRoot = root

        // Left: Home, Back, Split, Input Switch
        val btnHome = OverlayUiUtils.makeDockBtn(ctx, "桌面", 0x2AFFFFFF, Color.WHITE).apply {
            HyperUi.setStartIcon(this, HyperUi.ICON_APPS, Color.WHITE, 12.5f, 4f)
            OverlayUiUtils.attachTactilePress(this, 0.90f)
            setOnClickListener {
                host.setPanelOpen(false)
                host.setSplitMenuOpen(false)
                setTaskBubbleOpen(false)
                performHomeButtonAnimated(this)
            }
        }
        root.addView(btnHome)

        val btnBack = OverlayUiUtils.makeDockBtn(ctx, "返回", 0x22FFFFFF, Color.WHITE).apply {
            HyperUi.setStartIcon(this, HyperUi.ICON_CHEVRON_LEFT, Color.WHITE, 12.5f, 3f)
            OverlayUiUtils.attachTactilePress(this, 0.90f)
            setOnClickListener { host.safeInputExecute { BridgeClient.sendBack() } }
        }
        root.addView(btnBack)

        val splitBtn = OverlayUiUtils.makeDockBtn(ctx, "分屏", 0x22FFFFFF, Color.WHITE).apply {
            HyperUi.setStartIcon(this, HyperUi.ICON_SPLIT, Color.WHITE, 12.5f, 4f)
            OverlayUiUtils.attachTactilePress(this, 0.90f)
            setOnClickListener { host.triggerQuickSplitOrMenu() }
            setOnLongClickListener {
                host.setSplitMenuOpen(!host.isSplitMenuOpen())
                true
            }
        }
        btnDockSplit = splitBtn
        root.addView(splitBtn)

        val toggleBtn = OverlayUiUtils.makeDockBtn(
            ctx,
            if (st.inputToExt == 1) "外接" else "本机",
            if (st.inputToExt == 1) HyperUi.HYPER_BLUE else 0xCC3B4252.toInt(),
            Color.WHITE,
        ).apply {
            HyperUi.setStartIcon(this, HyperUi.ICON_SWAP, Color.WHITE, 12.5f, 4f)
            OverlayUiUtils.attachTactilePress(this, 0.90f)
            setOnClickListener {
                host.safeInputExecute {
                    val m = BridgeClient.toggleInput()
                    host.safePost {
                        host.updateInputToggleUi(m)
                        Toast.makeText(
                            host.serviceContext,
                            if (m == 1) "键鼠 -> 扩展屏" else "键鼠 -> 平板",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
        btnToggle = toggleBtn
        root.addView(toggleBtn)

        val divider = OverlayUiUtils.makeVerticalDivider(ctx).apply { visibility = View.GONE }
        taskDivider = divider
        root.addView(divider)

        // Center: Multitasking running/recent apps strip
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        taskStrip = strip
        root.addView(strip)

        root.addView(OverlayUiUtils.makeVerticalDivider(ctx))

        // Right: System Tray Pill
        val tray = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                OverlayUiUtils.dp(ctx, 9f),
                OverlayUiUtils.dp(ctx, 4f),
                OverlayUiUtils.dp(ctx, 9f),
                OverlayUiUtils.dp(ctx, 4f),
            )
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        isTrayPressed = true
                        host.cancelPanelOutsideDismiss()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isTrayPressed = false
                        lastTrayUpMs = SystemClock.uptimeMillis()
                    }
                }
                false
            }
            setOnClickListener {
                host.cancelPanelOutsideDismiss()
                lastTrayToggleMs = SystemClock.uptimeMillis()
                host.setPanelOpen(!host.isPanelOpen())
            }
        }
        trayBtn = tray
        updateTrayPillBackground(false)

        val wifiIcon = MiniIconView(ctx, MiniIconView.KIND_WIFI, Color.WHITE)
        trayWifiIcon = wifiIcon
        tray.addView(
            wifiIcon,
            LinearLayout.LayoutParams(OverlayUiUtils.dp(ctx, 16f), OverlayUiUtils.dp(ctx, 16f)).apply {
                rightMargin = OverlayUiUtils.dp(ctx, 6f)
            },
        )

        val volIcon = MiniIconView(ctx, MiniIconView.KIND_VOLUME, Color.WHITE)
        trayVolIcon = volIcon
        tray.addView(
            volIcon,
            LinearLayout.LayoutParams(OverlayUiUtils.dp(ctx, 16f), OverlayUiUtils.dp(ctx, 16f)).apply {
                rightMargin = OverlayUiUtils.dp(ctx, 6f)
            },
        )

        val battView = MiniBatteryView(ctx)
        trayBattView = battView
        tray.addView(
            battView,
            LinearLayout.LayoutParams(OverlayUiUtils.dp(ctx, 20f), OverlayUiUtils.dp(ctx, 12f)).apply {
                rightMargin = OverlayUiUtils.dp(ctx, 4f)
            },
        )

        val battPctTv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
            paint.isFakeBoldText = true
            OverlayUiUtils.enforceSingleLine(this)
        }
        trayBattPct = battPctTv
        tray.addView(
            battPctTv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = OverlayUiUtils.dp(ctx, 9f) },
        )

        val clockCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val tcTime = TextClock(ctx).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
            paint.isFakeBoldText = true
            OverlayUiUtils.enforceSingleLine(this)
        }
        clockCol.addView(tcTime)

        val tcDate = TextClock(ctx).apply {
            format24Hour = "yyyy/M/d"
            format12Hour = "yyyy/M/d"
            setTextColor(0xFFCBD5E1.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9.5f)
            OverlayUiUtils.enforceSingleLine(this)
        }
        clockCol.addView(tcDate)

        tray.addView(clockCol)
        root.addView(tray)

        dockLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            host.winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = OverlayUiUtils.dp(ctx, 10f)
        }

        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            if ((r - l) != (or2 - ol) || (b - t) != (ob - ot)) {
                repositionDock()
                host.repositionPanelIfOpen()
            }
        }
    }

    fun buildDockEdgeTriggerOverlay(ctx: Context) {
        val trigger = FrameLayout(ctx).apply {
            isClickable = true
            isFocusable = false
        }
        dockEdgeTrigger = trigger

        val pill = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(0x88FFFFFF.toInt())
                cornerRadius = OverlayUiUtils.dp(ctx, 2.5f).toFloat()
                setStroke(1, 0x44000000)
            }
        }
        dockEdgePill = pill
        val plp = FrameLayout.LayoutParams(
            OverlayUiUtils.dp(ctx, 108f),
            OverlayUiUtils.dp(ctx, 4f),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = OverlayUiUtils.dp(ctx, 4f) }
        trigger.addView(pill, plp)

        trigger.setOnHoverListener { _, ev ->
            val act = ev.actionMasked
            if (act == MotionEvent.ACTION_HOVER_ENTER || act == MotionEvent.ACTION_HOVER_MOVE) {
                wakeDockFromEdge()
            }
            false
        }
        trigger.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                wakeDockFromEdge()
                return@setOnTouchListener true
            }
            false
        }

        dockEdgeLp = WindowManager.LayoutParams(
            OverlayUiUtils.dp(ctx, 220f),
            OverlayUiUtils.dp(ctx, 16f),
            host.winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
        }
        trigger.visibility = View.GONE
    }

    fun buildTaskBubbleOverlay(ctx: Context) {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.LEFT
            clipChildren = false
            clipToPadding = false
            visibility = View.GONE
        }
        taskBubbleRoot = root

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = OverlayUiUtils.dp(ctx, 8f)
            setPadding(pad, pad, pad, pad)
            elevation = OverlayUiUtils.dp(ctx, 18f).toFloat()
            background = GradientDrawable().apply {
                setColor(0xF20F172A.toInt())
                cornerRadius = OverlayUiUtils.dp(ctx, 16f).toFloat()
                setStroke(1, 0x55FFFFFF)
            }
        }
        taskBubbleCard = card

        // Header: icon + app title + mode chip
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                OverlayUiUtils.dp(ctx, 6f),
                OverlayUiUtils.dp(ctx, 3f),
                OverlayUiUtils.dp(ctx, 6f),
                OverlayUiUtils.dp(ctx, 7f),
            )
        }

        val icView = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        bubbleIcon = icView
        header.addView(
            icView,
            LinearLayout.LayoutParams(OverlayUiUtils.dp(ctx, 20f), OverlayUiUtils.dp(ctx, 20f)).apply {
                rightMargin = OverlayUiUtils.dp(ctx, 8f)
            },
        )

        val titleTv = TextView(ctx).apply {
            setTextColor(0xFFF8FAFC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            paint.isFakeBoldText = true
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = OverlayUiUtils.dp(ctx, 130f)
        }
        bubbleTitle = titleTv
        header.addView(
            titleTv,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = OverlayUiUtils.dp(ctx, 6f)
            },
        )

        val modeTagTv = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            setTextColor(0xFFBAE6FD.toInt())
            setPadding(
                OverlayUiUtils.dp(ctx, 6f),
                OverlayUiUtils.dp(ctx, 2f),
                OverlayUiUtils.dp(ctx, 6f),
                OverlayUiUtils.dp(ctx, 2f),
            )
            background = GradientDrawable().apply {
                setColor(0x440284C7)
                cornerRadius = OverlayUiUtils.dp(ctx, 8f).toFloat()
            }
        }
        bubbleModeTag = modeTagTv
        header.addView(modeTagTv)
        card.addView(header)

        val sep = View(ctx).apply { setBackgroundColor(0x22FFFFFF) }
        card.addView(
            sep,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                bottomMargin = OverlayUiUtils.dp(ctx, 5f)
            },
        )

        val freeformBtn = makeBubbleRowBtn(ctx, "退出小窗（恢复全屏）", 0xFFE2E8F0.toInt(), HyperUi.CHIP_ACTIVE_BG).apply {
            HyperUi.setStartIcon(this, HyperUi.ICON_WINDOW, 0xFFE2E8F0.toInt(), 13f, 5f)
            visibility = View.GONE
            setOnClickListener {
                val target = bubbleTask
                val targetLbl = bubbleLabel
                setTaskBubbleOpen(false)
                if (target == null || !target.isFreeform()) return@setOnClickListener
                host.safeBgExecute {
                    val res = BridgeClient.toggleTaskFreeform(target.taskId, target.pkg, 0)
                    host.safePost {
                        Toast.makeText(
                            host.serviceContext,
                            if (res == 0) "已恢复全屏：$targetLbl" else "恢复全屏失败：$targetLbl",
                            Toast.LENGTH_SHORT,
                        ).show()
                        host.pollRemoteStateAsync()
                    }
                }
            }
        }
        bubbleFreeformBtn = freeformBtn
        card.addView(freeformBtn)

        val splitRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val srLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = OverlayUiUtils.dp(ctx, 4f) }

        val split0Btn = makeBubbleRowBtn(ctx, "放入左屏", 0xFFBAE6FD.toInt(), HyperUi.CHIP_ACTIVE_BG).apply {
            gravity = Gravity.CENTER
            setPadding(
                OverlayUiUtils.dp(ctx, 8f),
                OverlayUiUtils.dp(ctx, 6f),
                OverlayUiUtils.dp(ctx, 8f),
                OverlayUiUtils.dp(ctx, 6f),
            )
            HyperUi.setStartIcon(this, HyperUi.ICON_SPLIT, 0xFFBAE6FD.toInt(), 12.5f, 4f)
            setOnClickListener {
                val target = bubbleTask
                setTaskBubbleOpen(false)
                if (target != null) {
                    host.performEnterOrAssignSplitAnimated(
                        BridgeClient.SPLIT_ASSIGN_LEFT_TOP, 0, target.taskId, target.pkg, null,
                    )
                }
            }
        }
        bubbleSplit0Btn = split0Btn
        splitRow.addView(
            split0Btn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = OverlayUiUtils.dp(ctx, 5f)
            },
        )

        val split1Btn = makeBubbleRowBtn(ctx, "放入右屏", 0xFFBAE6FD.toInt(), HyperUi.CHIP_ACTIVE_BG).apply {
            gravity = Gravity.CENTER
            setPadding(
                OverlayUiUtils.dp(ctx, 8f),
                OverlayUiUtils.dp(ctx, 6f),
                OverlayUiUtils.dp(ctx, 8f),
                OverlayUiUtils.dp(ctx, 6f),
            )
            HyperUi.setStartIcon(this, HyperUi.ICON_SPLIT, 0xFFBAE6FD.toInt(), 12.5f, 4f)
            setOnClickListener {
                val target = bubbleTask
                setTaskBubbleOpen(false)
                if (target != null) {
                    host.performEnterOrAssignSplitAnimated(
                        BridgeClient.SPLIT_ASSIGN_RIGHT_BOTTOM, 1, target.taskId, target.pkg, null,
                    )
                }
            }
        }
        bubbleSplit1Btn = split1Btn
        splitRow.addView(split1Btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(splitRow, srLp)

        val closeBtn = makeBubbleRowBtn(ctx, "关闭应用", 0xFFFCA5A5.toInt(), 0x36EF4444).apply {
            HyperUi.setStartIcon(this, HyperUi.ICON_CLOSE, 0xFFFCA5A5.toInt(), 12.5f, 5f)
            setOnClickListener {
                val target = bubbleTask
                val targetLbl = bubbleLabel
                setTaskBubbleOpen(false)
                if (target == null) return@setOnClickListener
                host.safeBgExecute {
                    val ok = target.taskId > 0 && BridgeClient.removeTask(target.taskId)
                    host.safePost {
                        Toast.makeText(
                            host.serviceContext,
                            if (ok) "已关闭：$targetLbl" else "暂无运行任务：$targetLbl",
                            Toast.LENGTH_SHORT,
                        ).show()
                        host.pollRemoteStateAsync()
                    }
                }
            }
        }
        bubbleCloseBtn = closeBtn
        card.addView(
            closeBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = OverlayUiUtils.dp(ctx, 4f) },
        )

        root.addView(
            card,
            LinearLayout.LayoutParams(OverlayUiUtils.dp(ctx, 214f), LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        val triangle = object : View(ctx) {
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xF20F172A.toInt()
                style = Paint.Style.FILL
            }
            private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x55FFFFFF
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            }
            private val path = Path()

            override fun onDraw(canvas: Canvas) {
                val w = width.toFloat()
                val h = height.toFloat()
                path.reset()
                path.moveTo(0f, 0f)
                path.lineTo(w, 0f)
                path.lineTo(w * 0.5f, h)
                path.close()
                canvas.drawPath(path, fill)
                canvas.drawLine(0f, 0f, w * 0.5f, h, stroke)
                canvas.drawLine(w, 0f, w * 0.5f, h, stroke)
            }
        }
        bubbleTriangle = triangle
        root.addView(
            triangle,
            LinearLayout.LayoutParams(OverlayUiUtils.dp(ctx, 18f), OverlayUiUtils.dp(ctx, 9f)),
        )

        taskBubbleLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            host.winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }

        root.setOnTouchListener { _, ev ->
            if (ev != null && ev.actionMasked == MotionEvent.ACTION_OUTSIDE && isTaskBubbleOpen) {
                setTaskBubbleOpen(false)
            }
            false
        }
    }

    private fun makeBubbleRowBtn(ctx: Context, text: String, textColor: Int, hoverColor: Int): TextView {
        val tv = TextView(ctx)
        tv.text = text
        tv.setTextColor(textColor)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.isSingleLine = true
        tv.setPadding(
            OverlayUiUtils.dp(ctx, 10f),
            OverlayUiUtils.dp(ctx, 7f),
            OverlayUiUtils.dp(ctx, 10f),
            OverlayUiUtils.dp(ctx, 7f),
        )
        val hi = GradientDrawable().apply {
            setColor(hoverColor)
            cornerRadius = OverlayUiUtils.dp(ctx, 10f).toFloat()
        }
        val normal = GradientDrawable().apply {
            setColor(0x16FFFFFF)
            cornerRadius = OverlayUiUtils.dp(ctx, 10f).toFloat()
        }
        val sld = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), hi)
            addState(intArrayOf(android.R.attr.state_hovered), hi)
            addState(IntArray(0), normal)
        }
        tv.background = sld
        return tv
    }

    fun cancelAutoHideTimer() {
        host.handler.removeCallbacks(autoHideDockTask)
        autoHideScheduled = false
    }

    fun applyDockHidden(hidden: Boolean) {
        cancelAutoHideTimer()
        isDockHidden = hidden
        if (hidden) {
            host.setPanelOpen(false)
            host.setSplitMenuOpen(false)
            setTaskBubbleOpen(false)
        }
        dockRoot?.let { dock ->
            dock.animate().cancel()
            dock.alpha = 1f
            dock.translationY = 0f
            dock.visibility = if (hidden) View.GONE else View.VISIBLE
            if (!hidden) repositionDock()
        }
        syncDockEdgeVisibility()
        host.bringCursorToFront()
    }

    fun applyDockHiddenAnimated(hidden: Boolean) {
        val dock = dockRoot
        val ctx = host.extCtx
        if (dock == null || ctx == null) {
            applyDockHidden(hidden)
            return
        }
        if (hidden == isDockHidden && ((hidden && dock.visibility != View.VISIBLE) ||
                (!hidden && dock.visibility == View.VISIBLE))
        ) {
            syncDockEdgeVisibility()
            return
        }
        cancelAutoHideTimer()
        isDockHidden = hidden
        dock.animate().cancel()
        if (hidden) {
            host.setPanelOpen(false)
            host.setSplitMenuOpen(false)
            setTaskBubbleOpen(false)
            syncDockEdgeVisibility()
            val slideDist = OverlayUiUtils.dp(ctx, 74f).toFloat()
            dock.animate()
                .translationY(slideDist)
                .alpha(0f)
                .setDuration(200L)
                .setInterpolator(AccelerateInterpolator(1.3f))
                .withEndAction {
                    if (isDockHidden) {
                        dockRoot?.let {
                            it.visibility = View.GONE
                            it.translationY = 0f
                            it.alpha = 1f
                        }
                    }
                    host.bringCursorToFront()
                }
                .start()
        } else {
            syncDockEdgeVisibility()
            dock.visibility = View.VISIBLE
            repositionDock()
            dock.translationY = OverlayUiUtils.dp(ctx, 64f).toFloat()
            dock.alpha = 0f
            dock.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(200L)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .withEndAction { host.bringCursorToFront() }
                .start()
        }
    }

    fun syncDockEdgeVisibility() {
        dockEdgeTrigger?.visibility = if (isDockHidden) View.VISIBLE else View.GONE
    }

    fun wakeDockFromEdge() {
        if (isDockHidden) {
            applyDockHiddenAnimated(false)
        }
        evaluateDockAutoHide(true)
    }

    fun evaluateDockAutoHide(forceDelay: Boolean) {
        if (!isDockAutoHide || isOnLauncherHome) {
            host.handler.removeCallbacks(autoHideDockTask)
            autoHideScheduled = false
            if (isDockHidden) applyDockHiddenAnimated(false)
            return
        }
        if (host.isPanelOpen() || host.isSplitMenuOpen() || isTaskBubbleOpen || dockHovered || isTrayPressed) {
            host.handler.removeCallbacks(autoHideDockTask)
            autoHideScheduled = false
            return
        }
        if (isDockHidden && !forceDelay) return
        if (!forceDelay && autoHideScheduled) return
        host.handler.removeCallbacks(autoHideDockTask)
        autoHideScheduled = true
        host.handler.postDelayed(autoHideDockTask, DOCK_AUTO_HIDE_DELAY_MS)
    }

    fun updateTrayPillBackground(active: Boolean) {
        val tray = trayBtn ?: return
        val ctx = host.extCtx ?: return
        val bg = GradientDrawable().apply {
            setColor(if (active) 0x45A6D8E8 else 0x1EFFFFFF)
            cornerRadius = OverlayUiUtils.dp(ctx, 14f).toFloat()
            if (active) setStroke(1, 0x80A6D8E8.toInt())
        }
        tray.background = bg
    }

    fun updateDockSplitButton(active: Boolean) {
        btnDockSplit?.let { btn ->
            btn.text = "分屏"
            HyperUi.setStartIcon(btn, HyperUi.ICON_SPLIT, Color.WHITE, 12.5f, 4f)
            (btn.background as? GradientDrawable)?.setColor(if (active) HyperUi.HYPER_BLUE else 0x22FFFFFF)
        }
    }

    fun updateInputToggleUi(inputToExt: Int) {
        btnToggle?.let { btn ->
            btn.text = if (inputToExt == 1) "外接" else "本机"
            HyperUi.setStartIcon(btn, HyperUi.ICON_SWAP, Color.WHITE, 12.5f, 4f)
            (btn.background as? GradientDrawable)?.setColor(
                if (inputToExt == 1) HyperUi.HYPER_BLUE else 0xCC3B4252.toInt(),
            )
        }
    }

    fun refreshLocalHardwareViews(volLevel: Int) {
        trayVolIcon?.setVolumeLevel(volLevel)
        trayBattView?.setBattery(host.batteryPct, host.batteryCharging)
        trayBattPct?.text = "${host.batteryPct}%"
    }

    fun refreshVolumeIconOnly(volLevel: Int) {
        trayVolIcon?.setVolumeLevel(volLevel)
    }

    fun refreshSysViews(sys: BridgeClient.SysStatus) {
        if (!sys.valid) return
        trayWifiIcon?.setActiveState(sys.wifiOn)
    }

    fun repositionDock() {
        val dock = dockRoot ?: return
        val lp = dockLp ?: return
        val ctx = host.extCtx ?: return
        try {
            var surfW = host.overlayW
            val lHost = host.launcherHost
            if (host.inLauncherHost && lHost != null && lHost.width > 0) {
                surfW = lHost.width
            }
            val dw = dock.width
            val maxW = max(200, surfW - OverlayUiUtils.dp(ctx, 20f))
            val targetW = if (dw > maxW) maxW else WindowManager.LayoutParams.WRAP_CONTENT
            val bottomMargin = OverlayUiUtils.dp(ctx, 10f)
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.width = targetW
            lp.x = 0
            lp.y = bottomMargin
            if (host.inLauncherHost) {
                val flp = dock.layoutParams as? FrameLayout.LayoutParams
                val wantGravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                if (flp != null && (flp.width != targetW || flp.gravity != wantGravity ||
                        flp.leftMargin != 0 || flp.topMargin != 0 || flp.bottomMargin != bottomMargin)
                ) {
                    flp.gravity = wantGravity
                    flp.width = targetW
                    flp.leftMargin = 0
                    flp.topMargin = 0
                    flp.bottomMargin = bottomMargin
                    dock.layoutParams = flp
                }
            } else {
                host.extWm?.updateViewLayout(dock, lp)
            }
        } catch (_: Throwable) {}
    }

    private fun showTaskBubble(anchor: View?, t: BridgeClient.TaskEntry?, label: String?, icon: Drawable?) {
        val ctx = host.extCtx ?: return
        if (taskBubbleRoot == null || t == null) return
        lastBubbleOpenMs = SystemClock.uptimeMillis()
        bubbleTask = t
        bubbleLabel = if (!label.isNullOrEmpty()) label else t.pkg
        host.setPanelOpen(false)
        host.setSplitMenuOpen(false)

        bubbleIcon?.setImageDrawable(host.cloneDrawable(icon))
        bubbleTitle?.text = bubbleLabel
        bubbleModeTag?.text = when {
            t.isFreeform() -> "小窗"
            t.isInSplit() -> "分屏"
            t.isRunning() -> "运行中"
            else -> "后台"
        }
        bubbleFreeformBtn?.let {
            it.visibility = if (t.isFreeform()) View.VISIBLE else View.GONE
            it.text = "退出小窗（恢复全屏）"
        }
        val port = host.overlayH > host.overlayW
        bubbleSplit0Btn?.text = if (port) "放入上屏" else "放入左屏"
        bubbleSplit1Btn?.text = if (port) "放入下屏" else "放入右屏"
        bubbleCloseBtn?.let {
            it.isEnabled = t.taskId > 0
            it.alpha = if (t.taskId > 0) 1f else 0.45f
        }

        var surfW = max(600, host.overlayW)
        val lHost = host.launcherHost
        if (host.inLauncherHost && lHost != null && lHost.width > 0) {
            surfW = lHost.width
        }
        var anchorCx = surfW / 2
        val dock = dockRoot
        if (anchor != null && dock != null && dock.width > 0) {
            val dockLoc = IntArray(2)
            val slotLoc = IntArray(2)
            dock.getLocationOnScreen(dockLoc)
            anchor.getLocationOnScreen(slotLoc)
            val relX = slotLoc[0] - dockLoc[0]
            val dockLeft = if (host.inLauncherHost) dock.left else (surfW - dock.width) / 2
            anchorCx = dockLeft + relX + anchor.width / 2
        }
        bubbleAnchorCenterX = anchorCx
        setTaskBubbleOpen(true)
    }

    fun setTaskBubbleOpen(open: Boolean) {
        val wantOpen = if (open && bubbleTask == null) false else open
        val root = taskBubbleRoot
        if (isTaskBubbleOpen == wantOpen && (root == null || (wantOpen && root.visibility == View.VISIBLE))) {
            return
        }
        isTaskBubbleOpen = wantOpen
        if (root != null) {
            val ctx = host.extCtx
            val card = taskBubbleCard
            if (wantOpen) {
                root.visibility = View.VISIBLE
                repositionTaskBubble()
                cancelAutoHideTimer()
                if (card != null && ctx != null) {
                    card.animate().cancel()
                    card.alpha = 0f
                    card.translationY = OverlayUiUtils.dp(ctx, 10f).toFloat()
                    card.scaleX = 0.92f
                    card.scaleY = 0.92f
                    card.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(155L)
                        .setInterpolator(DecelerateInterpolator(1.5f))
                        .withEndAction { host.bringCursorToFront() }
                        .start()
                }
            } else {
                evaluateDockAutoHide(false)
                if (card != null && root.visibility == View.VISIBLE && ctx != null) {
                    card.animate().cancel()
                    card.animate()
                        .alpha(0f)
                        .translationY(OverlayUiUtils.dp(ctx, 8f).toFloat())
                        .scaleX(0.93f)
                        .scaleY(0.93f)
                        .setDuration(125L)
                        .setInterpolator(AccelerateInterpolator(1.3f))
                        .withEndAction {
                            if (!isTaskBubbleOpen) {
                                taskBubbleRoot?.visibility = View.GONE
                                taskBubbleCard?.let {
                                    it.alpha = 1f
                                    it.translationY = 0f
                                    it.scaleX = 1f
                                    it.scaleY = 1f
                                }
                            }
                        }
                        .start()
                } else {
                    root.visibility = View.GONE
                }
            }
        }
        host.bringCursorToFront()
    }

    fun repositionTaskBubble() {
        val root = taskBubbleRoot ?: return
        val lp = taskBubbleLp ?: return
        val ctx = host.extCtx ?: return
        var surfW = max(600, host.overlayW)
        var surfH = max(600, host.overlayH)
        val lHost = host.launcherHost
        if (host.inLauncherHost && lHost != null && lHost.width > 0 && lHost.height > 0) {
            surfW = lHost.width
            surfH = lHost.height
        }
        root.measure(
            View.MeasureSpec.makeMeasureSpec(surfW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(surfH, View.MeasureSpec.AT_MOST),
        )
        val bw = max(OverlayUiUtils.dp(ctx, 214f), root.measuredWidth)
        val bh = max(OverlayUiUtils.dp(ctx, 140f), root.measuredHeight)

        val dock = dockRoot
        val dockH = if (dock != null && dock.height > 0) dock.height else OverlayUiUtils.dp(ctx, 46f)
        val anchorTop = if (host.inLauncherHost && dock != null && dock.height > 0) {
            dock.top
        } else {
            surfH - OverlayUiUtils.dp(ctx, 10f) - dockH
        }

        val margin = OverlayUiUtils.dp(ctx, 12f)
        val x = (bubbleAnchorCenterX - bw / 2).coerceIn(margin, max(margin, surfW - bw - margin))
        val y = max(margin, anchorTop - bh - OverlayUiUtils.dp(ctx, 4f))

        bubbleTriangle?.let { tri ->
            val triW = OverlayUiUtils.dp(ctx, 18f)
            val pad16 = OverlayUiUtils.dp(ctx, 16f)
            val triX = ((bubbleAnchorCenterX - x) - triW / 2).coerceIn(pad16, max(pad16, bw - triW - pad16))
            tri.translationX = triX.toFloat()
        }

        lp.x = x
        lp.y = y
        if (host.inLauncherHost) {
            val flp = root.layoutParams as? FrameLayout.LayoutParams
            if (flp != null) {
                flp.gravity = Gravity.TOP or Gravity.LEFT
                flp.leftMargin = x
                flp.topMargin = y
                root.layoutParams = flp
            }
        } else {
            try { host.extWm?.updateViewLayout(root, lp) } catch (_: Throwable) {}
        }
    }

    fun updateTaskStrip(tasks: List<BridgeClient.TaskEntry>) {
        val strip = taskStrip ?: return
        val ctx = host.extCtx ?: return
        var surfW = host.overlayW
        val lHost = host.launcherHost
        if (host.inLauncherHost && lHost != null && lHost.width > 0) {
            surfW = lHost.width
        }
        val fixedW = OverlayUiUtils.dp(ctx, 390f)
        val slotW = max(1, OverlayUiUtils.dp(ctx, 42f))
        val availW = max(0, surfW - OverlayUiUtils.dp(ctx, 28f) - fixedW)
        val maxShown = (availW / slotW).coerceIn(2, 9)
        val limit = min(maxShown, tasks.size)

        val sig = StringBuilder()
        for (i in 0 until limit) {
            val t = tasks[i]
            sig.append(t.taskId).append(':').append(t.pkg).append(':').append(t.flags).append(';')
        }
        val s = sig.toString()
        if (s == lastTaskSig) return
        lastTaskSig = s

        strip.removeAllViews()
        taskDivider?.visibility = if (limit > 0) View.VISIBLE else View.GONE

        val pm = host.serviceContext.packageManager
        for (i in 0 until limit) {
            val t = tasks[i]
            val icon = host.getCachedIcon(pm, t.pkg)
            val label = host.getCachedLabel(pm, t.pkg)

            val slot = LinearLayout(ctx).apply {
                tag = t.pkg
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(
                    OverlayUiUtils.dp(ctx, 6f),
                    OverlayUiUtils.dp(ctx, 3f),
                    OverlayUiUtils.dp(ctx, 6f),
                    OverlayUiUtils.dp(ctx, 3f),
                )
                background = GradientDrawable().apply {
                    cornerRadius = OverlayUiUtils.dp(ctx, 10f).toFloat()
                    setColor(
                        when {
                            t.isTopExt() -> 0x36FFFFFF
                            t.onExt() -> 0x18FFFFFF
                            else -> Color.TRANSPARENT
                        },
                    )
                }
            }

            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (icon != null) setImageDrawable(icon)
            }
            slot.addView(
                iv,
                LinearLayout.LayoutParams(OverlayUiUtils.dp(ctx, 28f), OverlayUiUtils.dp(ctx, 28f)),
            )

            val dot = View(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = OverlayUiUtils.dp(ctx, 2f).toFloat()
                    setColor(
                        when {
                            t.isTopExt() -> 0xFF38BDF8.toInt()
                            t.onExt() -> 0xFF94A3B8.toInt()
                            t.isRunning() -> 0x7794A3B8
                            else -> Color.TRANSPARENT
                        },
                    )
                }
            }
            val dotLp = LinearLayout.LayoutParams(
                if (t.isTopExt()) OverlayUiUtils.dp(ctx, 14f) else OverlayUiUtils.dp(ctx, 6f),
                OverlayUiUtils.dp(ctx, 3f),
            ).apply { topMargin = OverlayUiUtils.dp(ctx, 2f) }
            slot.addView(dot, dotLp)

            val secDown = booleanArrayOf(false)
            val openBubble = Runnable {
                val now = SystemClock.uptimeMillis()
                if (now - lastBubbleOpenMs < 240L) return@Runnable
                showTaskBubble(slot, t, label, icon)
            }
            slot.setOnClickListener {
                host.setPanelOpen(false)
                setTaskBubbleOpen(false)
                val shouldMinimize = t.isTopExt() && !t.isInSplit() &&
                    host.lastStatus.mirrorMode == BridgeClient.MIRROR_MODE_DEX
                if (shouldMinimize) {
                    performMinimizeTaskAnimated(slot, t, label, icon)
                } else {
                    performRestoreTaskAnimated(slot, t, label, icon)
                }
            }
            slot.setOnTouchListener { v, ev ->
                if (ev == null) return@setOnTouchListener false
                val act = ev.actionMasked
                if (act == MotionEvent.ACTION_DOWN && (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                    secDown[0] = true
                    openBubble.run()
                    return@setOnTouchListener true
                }
                if (secDown[0]) {
                    if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                        secDown[0] = false
                    }
                    return@setOnTouchListener true
                }
                if (act == MotionEvent.ACTION_DOWN) {
                    v.animate().cancel()
                    v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80L).start()
                } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(170L)
                        .setInterpolator(OvershootInterpolator(1.9f)).start()
                }
                false
            }
            slot.setOnGenericMotionListener { _, ev ->
                if (ev != null && ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                    ev.actionButton == MotionEvent.BUTTON_SECONDARY
                ) {
                    openBubble.run()
                    return@setOnGenericMotionListener true
                }
                false
            }
            if (Build.VERSION.SDK_INT >= 23) {
                slot.setOnContextClickListener {
                    openBubble.run()
                    true
                }
            }
            slot.setOnLongClickListener {
                openBubble.run()
                true
            }

            val sLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = OverlayUiUtils.dp(ctx, 2f)
                rightMargin = OverlayUiUtils.dp(ctx, 2f)
            }
            strip.addView(slot, sLp)
        }
    }

    fun findDockSlotByPkg(pkg: String?): View? {
        val strip = taskStrip ?: return null
        if (pkg.isNullOrEmpty()) return null
        for (i in 0 until strip.childCount) {
            val child = strip.getChildAt(i)
            if (child != null && pkg == child.tag) return child
        }
        return null
    }

    fun resolveDockIconRectOnScreen(preferredSlot: View?, pkg: String?): RectF {
        val sw = max(600, host.overlayW)
        val sh = max(600, host.overlayH)
        val slot = preferredSlot ?: findDockSlotByPkg(pkg)
        val target = if (slot is ViewGroup && slot.childCount > 0) slot.getChildAt(0) else slot
        if (target != null && target.isShown && target.width > 0 && target.height > 0) {
            val loc = IntArray(2)
            target.getLocationOnScreen(loc)
            if (loc[0] in 0 until sw && loc[1] in 0 until sh) {
                return RectF(
                    loc[0].toFloat(),
                    loc[1].toFloat(),
                    (loc[0] + target.width).toFloat(),
                    (loc[1] + target.height).toFloat(),
                )
            }
        }
        val ctx = host.extCtx
        val sz = if (ctx != null) OverlayUiUtils.dp(ctx, 28f) else 36
        val bottomPad = if (ctx != null) OverlayUiUtils.dp(ctx, 18f) else 24
        val cx = sw * 0.5f
        val cy = sh - bottomPad - sz * 0.5f
        return RectF(cx - sz * 0.5f, cy - sz * 0.5f, cx + sz * 0.5f, cy + sz * 0.5f)
    }

    fun bounceDockSlot(pkg: String?, fallbackView: View?, downwardCushion: Boolean) {
        val target = findDockSlotByPkg(pkg) ?: fallbackView ?: return
        val ctx = host.extCtx ?: return
        val dy = if (downwardCushion) OverlayUiUtils.dp(ctx, 4.5f).toFloat() else -OverlayUiUtils.dp(ctx, 4f).toFloat()
        val sc = if (downwardCushion) 0.85f else 1.12f
        target.animate().cancel()
        target.animate()
            .translationY(dy)
            .scaleX(sc)
            .scaleY(sc)
            .setDuration(85L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                target.animate()
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(190L)
                    .setInterpolator(OvershootInterpolator(2.1f))
                    .start()
            }
            .start()
    }

    private fun performMinimizeTaskAnimated(
        slotView: View?,
        t: BridgeClient.TaskEntry?,
        label: String?,
        icon: Drawable?,
    ) {
        if (host.stageAnimView == null || host.extCtx == null || t == null) {
            host.safeBgExecute {
                BridgeClient.minimizeToExtHome(t?.taskId ?: -1, t?.pkg)
                host.safePost { host.pollRemoteStateAsync() }
            }
            return
        }
        val dockIconRect = resolveDockIconRectOnScreen(slotView, t.pkg)
        val fullRect = OverlayUiUtils.getFullStageRect(host.overlayW, host.overlayH)
        val pm = host.serviceContext.packageManager
        val animIcon = if (icon != null) host.cloneDrawable(icon) else host.getCachedIcon(pm, t.pkg)
        val animLabel = if (!label.isNullOrEmpty()) label else host.getCachedLabel(pm, t.pkg)

        host.safeBgExecute {
            val snap = BridgeClient.captureTaskSnapshot(t.taskId, t.pkg, true)
            val startBounds = if (snap.bounds != null && snap.bounds!!.width() > 60) snap.bounds!! else fullRect

            val card = StageTransitionView.CardSpec().apply {
                if (snap.bitmap != null) {
                    snapshot = snap.bitmap
                    ownSnapshot = snap.ownBitmap
                }
                this.icon = animIcon
                this.label = animLabel
                startRect.set(startBounds)
                endRect.set(dockIconRect)
            }

            val frame0Latch = CountDownLatch(1)
            host.safePost {
                val anim = host.stageAnimView
                if (anim == null || !anim.isAttachedToWindow || host.extCtx == null) {
                    OverlayUiUtils.recycleUnusedCard(card)
                    frame0Latch.countDown()
                    return@safePost
                }
                host.beginWindowOverlayStage()
                anim.playMinimizeToDock(
                    card,
                    { bounceDockSlot(t.pkg, slotView, true) },
                    {
                        host.endWindowOverlayStage()
                        host.pollRemoteStateAsync()
                    },
                )
                frame0Latch.countDown()
            }
            try { frame0Latch.await(45L, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}

            BridgeClient.minimizeToExtHome(t.taskId, t.pkg)
            host.handler.postDelayed({ host.pollRemoteStateAsync() }, 260L)
        }
    }

    private fun performHomeButtonAnimated(homeBtn: View) {
        val st = host.lastStatus
        val canAnimMinimize = host.stageAnimView != null && host.extCtx != null &&
            !isOnLauncherHome && st.connected && st.mirrorMode == BridgeClient.MIRROR_MODE_DEX
        if (!canAnimMinimize) {
            host.safeBgExecute {
                BridgeClient.relaunchLauncher()
                host.safePost { host.pollRemoteStateAsync() }
            }
            return
        }
        if (st.splitActive == 1) {
            val singleEmpty = st.splitSlot0Pkg.isEmpty() != st.splitSlot1Pkg.isEmpty()
            if (!singleEmpty) {
                performExitSplitToHomeAnimated(homeBtn)
                return
            }
            host.safeBgExecute {
                BridgeClient.relaunchLauncher()
                host.safePost { host.pollRemoteStateAsync() }
            }
            return
        }

        host.safeBgExecute {
            val tasks = BridgeClient.getTasks()
            val topExt = tasks.firstOrNull { it.isTopExt() && "com.extbridge.helper" != it.pkg }
            if (topExt == null) {
                BridgeClient.relaunchLauncher()
                host.safePost { host.pollRemoteStateAsync() }
                return@safeBgExecute
            }
            val pm = host.serviceContext.packageManager
            val icon = host.getCachedIcon(pm, topExt.pkg)
            val label = host.getCachedLabel(pm, topExt.pkg)
            host.safePost {
                val targetSlot = findDockSlotByPkg(topExt.pkg) ?: homeBtn
                performMinimizeTaskAnimated(targetSlot, topExt, label, icon)
            }
        }
    }

    fun performExitSplitToHomeAnimated(anchorBtn: View?) {
        val extCtx = host.extCtx
        if (host.stageAnimView == null || extCtx == null) {
            host.safeBgExecute {
                BridgeClient.relaunchLauncher()
                host.safePost { host.pollRemoteStateAsync() }
            }
            return
        }
        val cornerStart = OverlayUiUtils.dp(extCtx, 10f)
        val cornerEnd = OverlayUiUtils.dp(extCtx, 9f)
        val anchorRect = resolveDockIconRectOnScreen(anchorBtn, null)
        val anchorRectInt = Rect(
            anchorRect.left.roundToInt(),
            anchorRect.top.roundToInt(),
            anchorRect.right.roundToInt(),
            anchorRect.bottom.roundToInt(),
        )
        val fullRect = OverlayUiUtils.getFullStageRect(host.overlayW, host.overlayH)
        val p0 = host.lastStatus.splitSlot0Pkg
        val p1 = host.lastStatus.splitSlot1Pkg
        val ratio = host.displayedSplitRatio()

        host.safeBgExecute {
            val s0 = if (p0.isNotEmpty()) BridgeClient.captureTaskSnapshot(-1, p0, true) else null
            val s1 = if (p1.isNotEmpty()) BridgeClient.captureTaskSnapshot(-1, p1, true) else null
            val r0 = s0?.bounds ?: OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 0, ratio)
            val r1 = s1?.bounds ?: OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 1, ratio)

            val c0 = if (p0.isNotEmpty()) {
                host.buildCardSpec(p0, s0, r0, anchorRectInt).apply {
                    mode = StageTransitionView.MODE_MINIMIZE_TO_ICON
                    startCornerPx = cornerStart.toFloat()
                    endCornerPx = cornerEnd.toFloat()
                }
            } else null
            val c1 = if (p1.isNotEmpty()) {
                host.buildCardSpec(p1, s1, r1, anchorRectInt).apply {
                    mode = StageTransitionView.MODE_MINIMIZE_TO_ICON
                    startCornerPx = cornerStart.toFloat()
                    endCornerPx = cornerEnd.toFloat()
                }
            } else null
            val fc0 = c0 ?: host.buildCardSpec(null, null, fullRect, fullRect)

            val latch = CountDownLatch(1)
            host.safePost {
                val anim = host.stageAnimView
                if (anim == null || !anim.isAttachedToWindow || host.extCtx == null) {
                    OverlayUiUtils.recycleUnusedCard(fc0)
                    OverlayUiUtils.recycleUnusedCard(c1)
                    latch.countDown()
                    return@safePost
                }
                host.beginWindowOverlayStage()
                anim.playSplitTransition(fc0, c1, host.overlayH > host.overlayW, 250L, false) {
                    host.endWindowOverlayStage()
                    host.pollRemoteStateAsync()
                }
                anim.notifyBackendReady(anim.getActiveToken())
                latch.countDown()
            }
            try { latch.await(45L, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
            BridgeClient.relaunchLauncher()
            host.handler.postDelayed({ host.pollRemoteStateAsync() }, 260L)
        }
    }

    private fun performRestoreTaskAnimated(
        slotView: View?,
        t: BridgeClient.TaskEntry?,
        label: String?,
        icon: Drawable?,
    ) {
        if (host.stageAnimView == null || host.extCtx == null || t == null) {
            host.safeBgExecute {
                BridgeClient.switchToTask(t?.taskId ?: -1, t?.pkg)
                host.safePost { host.pollRemoteStateAsync() }
            }
            return
        }
        bounceDockSlot(t.pkg, slotView, false)
        val dockIconRect = resolveDockIconRectOnScreen(slotView, t.pkg)
        val st = host.lastStatus
        var splitTargetSlot = -1
        if (st.splitActive == 1 && "com.extbridge.helper" != t.pkg) {
            splitTargetSlot = when {
                t.pkg == st.splitSlot0Pkg -> 0
                t.pkg == st.splitSlot1Pkg -> 1
                st.splitSlot0Pkg.isEmpty() -> 0
                st.splitSlot1Pkg.isEmpty() -> 1
                else -> -1
            }
        }
        val targetIsSplit = splitTargetSlot >= 0
        val targetRect = if (targetIsSplit) {
            OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, splitTargetSlot, host.displayedSplitRatio())
        } else {
            OverlayUiUtils.getFullStageRect(host.overlayW, host.overlayH)
        }
        val pm = host.serviceContext.packageManager
        val animIcon = if (icon != null) host.cloneDrawable(icon) else host.getCachedIcon(pm, t.pkg)
        val animLabel = if (!label.isNullOrEmpty()) label else host.getCachedLabel(pm, t.pkg)

        host.safeBgExecute {
            val snap = BridgeClient.captureTaskSnapshot(t.taskId, t.pkg, false)
            val card = StageTransitionView.CardSpec().apply {
                if (snap.bitmap != null) {
                    snapshot = snap.bitmap
                    ownSnapshot = snap.ownBitmap
                }
                this.icon = animIcon
                this.label = animLabel
                startRect.set(dockIconRect)
                endRect.set(targetRect)
            }

            val tokenBox = intArrayOf(-1)
            val latch = CountDownLatch(1)
            host.safePost {
                val anim = host.stageAnimView
                if (anim == null || !anim.isAttachedToWindow || host.extCtx == null) {
                    OverlayUiUtils.recycleUnusedCard(card)
                    latch.countDown()
                    return@safePost
                }
                host.beginWindowOverlayStage()
                anim.playRestoreFromDock(card, targetIsSplit) {
                    host.endWindowOverlayStage()
                    host.pollRemoteStateAsync()
                }
                tokenBox[0] = anim.getActiveToken()
                latch.countDown()
            }
            try { latch.await(45L, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}

            BridgeClient.switchToTask(t.taskId, t.pkg)
            val animNow = host.stageAnimView
            if (animNow != null && tokenBox[0] > 0) animNow.notifyBackendReady(tokenBox[0])
            host.handler.postDelayed({ host.pollRemoteStateAsync() }, 180L)
        }
    }

    fun detach() {
        cancelAutoHideTimer()
        host.removeOverlayView(taskBubbleRoot)
        host.removeOverlayView(dockEdgeTrigger)
        host.removeOverlayView(dockRoot)
        dockRoot = null
        dockLp = null
        dockEdgeTrigger = null
        dockEdgePill = null
        dockEdgeLp = null
        isDockHidden = false
        dockHovered = false
        isTrayPressed = false
        taskBubbleRoot = null
        taskBubbleCard = null
        bubbleTriangle = null
        taskBubbleLp = null
        isTaskBubbleOpen = false
        bubbleIcon = null
        bubbleTitle = null
        bubbleModeTag = null
        bubbleFreeformBtn = null
        bubbleSplit0Btn = null
        bubbleSplit1Btn = null
        bubbleCloseBtn = null
        bubbleTask = null
        btnDockSplit = null
        btnToggle = null
        taskStrip = null
        taskDivider = null
        trayBtn = null
        trayWifiIcon = null
        trayVolIcon = null
        trayBattView = null
        trayBattPct = null
        lastTaskSig = ""
    }
}

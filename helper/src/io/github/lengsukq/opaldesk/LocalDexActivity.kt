package io.github.lengsukq.opaldesk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.lengsukq.opaldesk.design.iosglass.LiquidGlassDrawable
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.service.DisplayLaunchHelper
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen immersive on-device Samsung DeX host Activity on Display 0.
 *
 * Renders the daemon's standalone Local DeX {@code VirtualDisplay} directly onto a zero-copy
 * {@link SurfaceView}, forwarding multi-touch, stylus, mouse, hover, scroll, and hardware key
 * events directly into the DeX display without requiring an external monitor or Mac client.
 */
class LocalDexActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "ExtLocalDex"
        private const val HANDLE_DIM_DELAY_MS = 3500L
        private const val BAR_AUTO_COLLAPSE_MS = 5000L
        private const val DAEMON_WAIT_TIMEOUT_MS = 2200L

        @Volatile
        private var sActiveRef: WeakReference<LocalDexActivity>? = null

        fun isRunning(): Boolean {
            val act = sActiveRef?.get()
            return act != null && !act.isFinishing && !act.isDestroyed
        }

        fun finishActiveIfAny() {
            val act = sActiveRef?.get() ?: return
            act.runOnUiThread {
                if (!act.isFinishing && !act.isDestroyed) {
                    act.explicitExit = true
                    act.finishAndRemoveTask()
                }
            }
        }
    }

    private data class QueuedMotion(
        val action: Int,
        val downTime: Long,
        val eventTime: Long,
        val source: Int,
        val buttonState: Int,
        val actionButton: Int,
        val hScroll: Float,
        val vScroll: Float,
        val ids: IntArray,
        val toolTypes: IntArray,
        val xs: FloatArray,
        val ys: FloatArray,
        val pressures: FloatArray,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var ioExec: ExecutorService? = null
    private var inputExec: ExecutorService? = null

    private lateinit var rootFrame: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var topHandleWrap: FrameLayout
    private lateinit var topHandlePill: TextView
    private lateinit var controlBar: LinearLayout
    private lateinit var statusOverlay: FrameLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusBody: TextView
    private lateinit var statusActionsRow: LinearLayout
    private lateinit var btnPrimaryAction: TextView
    private lateinit var btnSecondaryAction: TextView

    @Volatile private var surfaceValid = false
    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0
    @Volatile private var activeVdId = -1
    @Volatile private var vdW = 0
    @Volatile private var vdH = 0
    @Volatile private var explicitExit = false

    private var attachInFlight = false
    private var attachPendingRetry = false
    private var autoRootTried = false
    private var controlBarExpanded = false
    private var backCallback: OnBackInvokedCallback? = null

    // Coalesced move/hover dispatch so 120Hz+ touch streams never back up Binder IPC.
    private val moveLock = Any()
    private var pendingMove: QueuedMotion? = null
    private val moveScheduled = AtomicBoolean(false)

    private val binderReadyCb = Runnable {
        runOnUiThread {
            if (!isFinishing && !isDestroyed && surfaceValid) {
                attachOrStartLocalDex()
            }
        }
    }

    private val dimHandleTask = Runnable {
        if (!controlBarExpanded && ::topHandleWrap.isInitialized) {
            topHandleWrap.animate().alpha(0.55f).setDuration(320L).start()
        }
    }

    private val collapseBarTask = Runnable { setControlBarExpanded(false) }

    private val daemonTimeoutTask = Runnable {
        if (isFinishing || isDestroyed || activeVdId > 0) return@Runnable
        if (BridgeClient.getService() == null) {
            showOfflinePrompt()
        }
    }

    private fun dp(v: Float): Int = HyperUi.dp(this, v)

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DisplayLaunchHelper.ensureOnDisplay0(this)) return

        sActiveRef = WeakReference(this)
        ioExec = Executors.newSingleThreadExecutor()
        inputExec = Executors.newSingleThreadExecutor()

        configureImmersiveWindow()
        BridgeService.sUserStopping = false
        BridgeService.ensureStarted(this)

        buildUi()
        setContentView(rootFrame)
        applySystemBarsHidden()
        registerBackHandler()

        BridgeClient.setOnBinderReady(binderReadyCb)
        if (BridgeClient.getService() == null) {
            BridgeClient.requestBinder(this)
            maybeTryAutoRootBootstrap()
            handler.postDelayed(daemonTimeoutTask, DAEMON_WAIT_TIMEOUT_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        sActiveRef = WeakReference(this)
        applySystemBarsHidden()
        BridgeService.ensureStarted(this)
        if (BridgeClient.getService() == null) {
            BridgeClient.requestBinder(this)
        } else if (surfaceValid && activeVdId <= 0) {
            attachOrStartLocalDex()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBarsHidden()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        BridgeClient.removeOnBinderReady(binderReadyCb)
        unregisterBackHandler()
        if (sActiveRef?.get() === this) sActiveRef = null

        val shouldRelease = explicitExit || isFinishing
        val exec = ioExec
        if (shouldRelease && activeVdId > 0) {
            val stopRunner = Runnable {
                BridgeClient.stopLocalDex(releaseDisplay = true)
                BridgeService.refreshFromReceiver(applicationContext)
            }
            if (exec != null && !exec.isShutdown) {
                exec.execute(stopRunner)
            } else {
                Thread(stopRunner, "ExtLocalDexStop").start()
            }
        }
        inputExec?.shutdownNow()
        inputExec = null
        ioExec?.shutdown()
        ioExec = null
        super.onDestroy()
    }

    // --- Window & UI Construction ---

    @Suppress("DEPRECATION")
    private fun configureImmersiveWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
            }
        }
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarsHidden() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Build.VERSION.SDK_INT < 35) {
                window.setDecorFitsSystemWindows(false)
            }
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@LocalDexActivity)
            setOnTouchListener { _, ev -> handleCanvasMotion(ev) }
            setOnGenericMotionListener { _, ev -> handleCanvasMotion(ev) }
        }
        rootFrame.addView(
            surfaceView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        // Subtle top-center Liquid Glass menu pill (always recognizable, auto-dims gently)
        topHandlePill = TextView(this).apply {
            text = "DeX 菜单"
            setTextColor(0xF0FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            paint.isFakeBoldText = true
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11f), dp(4f), dp(11f), dp(4f))
            HyperUi.setStartIcon(this, HyperUi.ICON_DEX, 0xF0FFFFFF.toInt(), 12f, 4f)
            LiquidGlassDrawable.applyToView(
                view = this,
                fillColor = 0xAA0F172A.toInt(),
                cornerRadiusPx = dp(14f).toFloat(),
                strokeWidthPx = dp(0.8f).toFloat(),
                darkMode = true,
            )
        }
        topHandleWrap = FrameLayout(this).apply {
            setPadding(dp(10f), dp(6f), dp(10f), dp(8f))
            addView(topHandlePill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            setOnClickListener { setControlBarExpanded(true) }
            visibility = View.GONE
        }
        rootFrame.addView(
            topHandleWrap,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL),
        )

        // Expandable compact Liquid Glass control capsule
        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            elevation = dp(16f).toFloat()
            LiquidGlassDrawable.applyToView(
                view = this,
                fillColor = 0xD8111827.toInt(),
                cornerRadiusPx = dp(20f).toFloat(),
                strokeWidthPx = dp(1f).toFloat(),
                darkMode = true,
                clipOutline = true,
            )
            addView(makeBarButton("桌面", HyperUi.ICON_HOME_NAV, false) {
                setControlBarExpanded(false)
                BridgeService.toggleShowDesktopFromUi()
            })
            addView(makeBarButton("工作台", HyperUi.ICON_SIDEBAR, false) {
                setControlBarExpanded(false)
                BridgeService.toggleWorkbenchFromUi()
            })
            addView(makeBarButton("任务栏", HyperUi.ICON_WINDOW, false) {
                setControlBarExpanded(false)
                BridgeService.wakeTaskbarFromUi()
            })
            addView(makeBarButton("应用", HyperUi.ICON_APPS, false) {
                setControlBarExpanded(false)
                BridgeService.toggleAppDrawerFromUi(false)
            })
            addView(makeBarButton("控制台", HyperUi.ICON_SLIDERS, false) {
                setControlBarExpanded(false)
                openMainConsole()
            })
            addView(makeBarButton("退出 DeX", HyperUi.ICON_CLOSE, true) {
                exitLocalDexAndFinish()
            })
            visibility = View.GONE
        }
        rootFrame.addView(
            controlBar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(8f)
            },
        )

        // Connecting / Activation / Error overlay card
        statusTitle = TextView(this).apply {
            text = "正在启动本机 DeX 桌面…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
        }
        statusBody = TextView(this).apply {
            text = "无需外接显示器，正在初始化独立多窗口虚拟画布"
            setTextColor(0xB8E2E8F0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            gravity = Gravity.CENTER
        }
        btnPrimaryAction = makeOverlayActionButton("前往控制台激活", primary = true) { openMainConsole() }
        btnSecondaryAction = makeOverlayActionButton("退出", primary = false) { exitLocalDexAndFinish() }
        statusActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(btnPrimaryAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(10f)
            })
            addView(btnSecondaryAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26f), dp(22f), dp(26f), dp(22f))
            LiquidGlassDrawable.applyToView(
                view = this,
                fillColor = 0xE60F172A.toInt(),
                cornerRadiusPx = dp(24f).toFloat(),
                strokeWidthPx = dp(1f).toFloat(),
                darkMode = true,
                clipOutline = true,
            )
            addView(statusTitle)
            addView(statusBody, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8f)
            })
            addView(statusActionsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16f)
            })
        }
        statusOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC060913.toInt())
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                leftMargin = dp(24f)
                rightMargin = dp(24f)
            })
        }
        rootFrame.addView(
            statusOverlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun makeBarButton(label: String, iconKind: Int, danger: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(if (danger) 0xFFFF6B6B.toInt() else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            paint.isFakeBoldText = true
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            HyperUi.setStartIcon(this, iconKind, if (danger) 0xFFFF6B6B.toInt() else Color.WHITE, 13f, 4.5f)
            LiquidGlassDrawable.applyToView(
                view = this,
                fillColor = if (danger) 0x30FF453A else 0x1FFFFFFF,
                cornerRadiusPx = dp(14f).toFloat(),
                strokeWidthPx = dp(0.75f).toFloat(),
                darkMode = true,
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(3f)
                rightMargin = dp(3f)
            }
            setOnClickListener { onClick() }
        }

    private fun makeOverlayActionButton(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            paint.isFakeBoldText = true
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
            LiquidGlassDrawable.applyToView(
                view = this,
                fillColor = if (primary) HyperUi.HYPER_BLUE else 0x33FFFFFF,
                cornerRadiusPx = dp(16f).toFloat(),
                strokeWidthPx = dp(1f).toFloat(),
                darkMode = true,
            )
            setOnClickListener { onClick() }
        }

    private fun setControlBarExpanded(expanded: Boolean) {
        controlBarExpanded = expanded
        handler.removeCallbacks(collapseBarTask)
        handler.removeCallbacks(dimHandleTask)
        if (expanded) {
            topHandleWrap.visibility = View.GONE
            controlBar.alpha = 0f
            controlBar.visibility = View.VISIBLE
            controlBar.animate().alpha(1f).setDuration(140L).start()
            handler.postDelayed(collapseBarTask, BAR_AUTO_COLLAPSE_MS)
        } else {
            controlBar.visibility = View.GONE
            if (activeVdId > 0) {
                topHandleWrap.alpha = 0.85f
                topHandleWrap.visibility = View.VISIBLE
                handler.postDelayed(dimHandleTask, HANDLE_DIM_DELAY_MS)
            }
        }
    }

    // --- SurfaceHolder.Callback & Local DeX Lifecycle ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceValid = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceValid = true
        surfaceW = width
        surfaceH = height
        if (width > 0 && height > 0) {
            attachOrStartLocalDex()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceValid = false
        if (!explicitExit && activeVdId > 0) {
            val exec = ioExec
            if (exec != null && !exec.isShutdown) {
                exec.execute { BridgeClient.stopLocalDex(releaseDisplay = false) }
            }
        }
    }

    private fun attachOrStartLocalDex() {
        if (isFinishing || isDestroyed || !surfaceValid) return
        val surf = surfaceView.holder.surface
        if (surf == null || !surf.isValid) return
        if (BridgeClient.getService() == null) {
            BridgeClient.requestBinder(this)
            return
        }
        if (attachInFlight) {
            attachPendingRetry = true
            return
        }

        val rawW = surfaceW
        val rawH = surfaceH
        if (rawW <= 0 || rawH <= 0) return

        val longEdge = max(rawW, rawH)
        val shortEdge = min(rawW, rawH)
        val scale = if (shortEdge < 480 || longEdge < 640) {
            max(480f / max(1, shortEdge), 640f / max(1, longEdge))
        } else {
            1f
        }
        val reqW = ((rawW * scale).toInt() and 1.inv()).coerceIn(480, 4096)
        val reqH = ((rawH * scale).toInt() and 1.inv()).coerceIn(480, 4096)
        val reqDpi = BridgeClient.computeLocalDexDpi(reqW, reqH, resources.configuration.smallestScreenWidthDp)
        val reqFps = (display?.refreshRate?.takeIf { it in 24f..240f } ?: 60f)

        attachInFlight = true
        attachPendingRetry = false
        val exec = ioExec ?: return
        exec.execute {
            val resultVd = try {
                BridgeClient.startLocalDex(reqW, reqH, reqDpi, reqFps, surf)
            } catch (t: Throwable) {
                Log.w(TAG, "startLocalDex IPC failed", t)
                -1
            }
            runOnUiThread {
                attachInFlight = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    resultVd > 0 -> {
                        handler.removeCallbacks(daemonTimeoutTask)
                        activeVdId = resultVd
                        vdW = reqW
                        vdH = reqH
                        statusOverlay.visibility = View.GONE
                        if (!controlBarExpanded) {
                            topHandleWrap.alpha = 0.85f
                            topHandleWrap.visibility = View.VISIBLE
                            handler.removeCallbacks(dimHandleTask)
                            handler.postDelayed(dimHandleTask, HANDLE_DIM_DELAY_MS)
                        }
                        BridgeService.refreshFromReceiver(this)
                        if (!NavAssistService.isRunning() && !Settings.canDrawOverlays(this)) {
                            Toast.makeText(
                                this,
                                "提示：授予「悬浮窗」或「无障碍」权限可显示 DeX 任务栏与窗口标题栏",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                    resultVd == -2 -> {
                        showErrorPrompt(
                            title = "Mac 远程大屏正在运行",
                            body = "请先在控制台断开 Mac 远程连接，再开启本机 DeX 模式。",
                            primaryLabel = "前往控制台",
                            onPrimary = { openMainConsole() },
                        )
                    }
                    else -> {
                        showErrorPrompt(
                            title = "本机 DeX 启动失败",
                            body = "未能创建本机独立虚拟画布，请确认核心服务已正常运行后重试。",
                            primaryLabel = "重试",
                            onPrimary = {
                                showLoadingState("正在重新启动本机 DeX…", "正在连接核心守护进程")
                                attachOrStartLocalDex()
                            },
                        )
                    }
                }
                if (attachPendingRetry && surfaceValid && !isFinishing && !isDestroyed) {
                    attachPendingRetry = false
                    attachOrStartLocalDex()
                }
            }
        }
    }

    private fun maybeTryAutoRootBootstrap() {
        if (autoRootTried || !AppPrefs.isAutoRootEnabled(this)) return
        autoRootTried = true
        showLoadingState("正在自动激活后台服务…", "检测到已开启 Root 自动激活，请稍候")
        AdbBootstrap.rootBootstrapAsync(this, false, object : AdbBootstrap.ProgressListener {
            override fun onStep(message: String?) = Unit
            override fun onResult(success: Boolean, message: String?) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (success) {
                        BridgeClient.requestBinder(this@LocalDexActivity)
                    } else if (BridgeClient.getService() == null) {
                        showOfflinePrompt()
                    }
                }
            }
        })
    }

    private fun showLoadingState(title: String, body: String) {
        statusTitle.text = title
        statusBody.text = body
        statusActionsRow.visibility = View.GONE
        statusOverlay.visibility = View.VISIBLE
    }

    private fun showOfflinePrompt() {
        showErrorPrompt(
            title = "核心守护进程尚未激活",
            body = "免外接屏本机 DeX 需要先激活后台服务（支持 Root 一键拉起或本机无线调试激活）。",
            primaryLabel = "前往控制台激活",
            onPrimary = { openMainConsole() },
        )
    }

    private fun showErrorPrompt(title: String, body: String, primaryLabel: String, onPrimary: () -> Unit) {
        statusTitle.text = title
        statusBody.text = body
        btnPrimaryAction.text = primaryLabel
        btnPrimaryAction.setOnClickListener { onPrimary() }
        statusActionsRow.visibility = View.VISIBLE
        statusOverlay.visibility = View.VISIBLE
    }

    private fun openMainConsole() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (!DisplayLaunchHelper.launchOnDisplay0(this, intent)) {
            startActivity(intent)
        }
    }

    private fun exitLocalDexAndFinish() {
        explicitExit = true
        val exec = ioExec
        val stopWork = Runnable {
            BridgeClient.stopLocalDex(releaseDisplay = true)
            BridgeService.refreshFromReceiver(applicationContext)
        }
        if (exec != null && !exec.isShutdown) {
            exec.execute(stopWork)
        } else {
            Thread(stopWork, "ExtLocalDexExit").start()
        }
        finishAndRemoveTask()
    }

    // --- Touch, Mouse, Hover & Keyboard Input Forwarding ---

    private fun handleCanvasMotion(ev: MotionEvent?): Boolean {
        if (ev == null || activeVdId <= 0) return false
        val vw = surfaceW
        val vh = surfaceH
        val dw = vdW
        val dh = vdH
        if (vw <= 0 || vh <= 0 || dw <= 0 || dh <= 0) return false

        val masked = ev.actionMasked
        if (controlBarExpanded && (masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_BUTTON_PRESS)) {
            setControlBarExpanded(false)
        }

        val count = ev.pointerCount.coerceIn(1, 10)
        val ids = IntArray(count)
        val toolTypes = IntArray(count)
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        val pressures = FloatArray(count)
        val scaleX = dw.toFloat() / vw.toFloat()
        val scaleY = dh.toFloat() / vh.toFloat()
        for (i in 0 until count) {
            ids[i] = ev.getPointerId(i)
            toolTypes[i] = ev.getToolType(i)
            xs[i] = (ev.getX(i) * scaleX).coerceIn(0f, (dw - 1).toFloat())
            ys[i] = (ev.getY(i) * scaleY).coerceIn(0f, (dh - 1).toFloat())
            pressures[i] = ev.getPressure(i)
        }

        val isMouse = (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            toolTypes[0] == MotionEvent.TOOL_TYPE_MOUSE
        val effSource = when {
            isMouse -> InputDevice.SOURCE_MOUSE
            toolTypes[0] == MotionEvent.TOOL_TYPE_STYLUS -> InputDevice.SOURCE_STYLUS
            else -> InputDevice.SOURCE_TOUCHSCREEN
        }
        val hScroll = if (masked == MotionEvent.ACTION_SCROLL) ev.getAxisValue(MotionEvent.AXIS_HSCROLL) else 0f
        val vScroll = if (masked == MotionEvent.ACTION_SCROLL) ev.getAxisValue(MotionEvent.AXIS_VSCROLL) else 0f
        val actionBtn = if (masked == MotionEvent.ACTION_BUTTON_PRESS || masked == MotionEvent.ACTION_BUTTON_RELEASE) {
            ev.actionButton
        } else {
            0
        }

        val packet = QueuedMotion(
            action = ev.action,
            downTime = ev.downTime,
            eventTime = ev.eventTime,
            source = effSource,
            buttonState = ev.buttonState,
            actionButton = actionBtn,
            hScroll = hScroll,
            vScroll = vScroll,
            ids = ids,
            toolTypes = toolTypes,
            xs = xs,
            ys = ys,
            pressures = pressures,
        )
        enqueueMotionPacket(packet, coalescable = (masked == MotionEvent.ACTION_MOVE || masked == MotionEvent.ACTION_HOVER_MOVE))
        return true
    }

    private fun enqueueMotionPacket(packet: QueuedMotion, coalescable: Boolean) {
        val exec = inputExec
        if (exec == null || exec.isShutdown) return
        if (coalescable) {
            synchronized(moveLock) { pendingMove = packet }
            if (moveScheduled.compareAndSet(false, true)) {
                exec.execute {
                    moveScheduled.set(false)
                    val latest = synchronized(moveLock) { pendingMove.also { pendingMove = null } }
                    if (latest != null) dispatchMotionBlocking(latest)
                }
            }
            return
        }
        // Flush any queued move before discrete DOWN/UP/POINTER/SCROLL transitions to preserve ordering.
        val flushed = synchronized(moveLock) { pendingMove.also { pendingMove = null } }
        exec.execute {
            if (flushed != null) dispatchMotionBlocking(flushed)
            dispatchMotionBlocking(packet)
        }
    }

    private fun dispatchMotionBlocking(m: QueuedMotion) {
        BridgeClient.sendLocalMotionEvent(
            action = m.action,
            downTime = m.downTime,
            eventTime = m.eventTime,
            source = m.source,
            buttonState = m.buttonState,
            actionButton = m.actionButton,
            hScroll = m.hScroll,
            vScroll = m.vScroll,
            ids = m.ids,
            toolTypes = m.toolTypes,
            xs = m.xs,
            ys = m.ys,
            pressures = m.pressures,
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (activeVdId <= 0) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP ||
            code == KeyEvent.KEYCODE_VOLUME_DOWN ||
            code == KeyEvent.KEYCODE_VOLUME_MUTE ||
            code == KeyEvent.KEYCODE_POWER
        ) {
            return super.dispatchKeyEvent(event)
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                inputExec?.execute { BridgeClient.sendBack() }
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val meta = event.metaState
            inputExec?.execute { BridgeClient.sendKey(code, meta) }
        }
        return true
    }

    private fun registerBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val cb = OnBackInvokedCallback {
                if (activeVdId > 0) {
                    inputExec?.execute { BridgeClient.sendBack() }
                } else {
                    exitLocalDexAndFinish()
                }
            }
            backCallback = cb
            try {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            } catch (t: Throwable) {
                Log.d(TAG, "registerOnBackInvokedCallback failed: ${t.message}")
            }
        }
    }

    private fun unregisterBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let { cb ->
                try {
                    onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
                } catch (_: Throwable) {
                }
            }
            backCallback = null
        }
    }
}

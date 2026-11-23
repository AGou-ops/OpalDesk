package io.github.lengsukq.opaldesk.cast

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.MainActivity
import io.github.lengsukq.opaldesk.R
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Receiver-side system floating window overlay ([WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY])
 * that streams a single standalone app (`SESSION_ROLE_STANDALONE_APP`) from a paired remote OpalDesk
 * device so the user can operate local apps simultaneously while interacting with the remote app.
 *
 * Strictly gated behind standard Android [Settings.canDrawOverlays] permission (zero OEM hacks).
 */
class PeerFloatingAppService : Service() {

    data class ActiveFloatingInfo(
        val host: String,
        val port: Int,
        val peerName: String,
        val appPackage: String,
        val appLabel: String,
    )

    companion object {
        private const val TAG = "OpalFloatingApp"
        private const val CHANNEL_ID = "opaldesk_floating_app"
        private const val NOTIF_ID = 3930
        private const val MUTEX_TAG = "floating_app"

        const val ACTION_START = "io.github.lengsukq.opaldesk.FLOATING_APP_START"
        const val ACTION_STOP = "io.github.lengsukq.opaldesk.FLOATING_APP_STOP"

        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PEER_LABEL = "peer_label"
        const val EXTRA_APP_PACKAGE = "app_pkg"
        const val EXTRA_APP_ACTIVITY = "app_cls"
        const val EXTRA_APP_LABEL = "app_label"
        const val EXTRA_HANDOVER_TOKEN = "handover_token"

        private val _activeFlow = MutableStateFlow<ActiveFloatingInfo?>(null)
        val activeFlow: StateFlow<ActiveFloatingInfo?> = _activeFlow.asStateFlow()

        fun start(
            ctx: Context,
            host: String,
            port: Int,
            peerLabel: String,
            appPackage: String,
            appActivity: String,
            appLabel: String,
            handoverToken: String = "",
        ): Boolean {
            if (!Settings.canDrawOverlays(ctx)) return false
            val intent = Intent(ctx.applicationContext, PeerFloatingAppService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_PEER_LABEL, peerLabel)
                putExtra(EXTRA_APP_PACKAGE, appPackage)
                putExtra(EXTRA_APP_ACTIVITY, appActivity)
                putExtra(EXTRA_APP_LABEL, appLabel)
                putExtra(EXTRA_HANDOVER_TOKEN, handoverToken)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.applicationContext.startForegroundService(intent)
            } else {
                ctx.applicationContext.startService(intent)
            }
            return true
        }

        fun stop(ctx: Context) {
            try {
                ctx.applicationContext.stopService(Intent(ctx.applicationContext, PeerFloatingAppService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var textureView: TextureView? = null
    private var activeSurface: Surface? = null
    private var statusDotView: View? = null
    private var titleTextView: TextView? = null
    private var overlayBanner: LinearLayout? = null
    private var overlayBannerText: TextView? = null
    private var overlayRetryBtn: TextView? = null
    private var imeInputBar: LinearLayout? = null
    private var imeEditText: EditText? = null

    private var client: OpalCastPeerClient? = null
    private var host: String = ""
    private var port: Int = 19878
    private var peerLabel: String = ""
    private var appPackage: String = ""
    private var appActivity: String = ""
    private var appLabel: String = ""
    private var handoverToken: String = ""
    private var isLandscape = false
    private var isCollapsed = false
    private var isImeBarVisible = false

    private var expandedW = 0
    private var expandedH = 0
    private var savedX = 48
    private var savedY = 140

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予 OpalDesk 悬浮窗权限", Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }

        val nextHost = intent?.getStringExtra(EXTRA_HOST).orEmpty().trim()
        val nextPort = (intent?.getIntExtra(EXTRA_PORT, 19878) ?: 19878).coerceIn(1, 65535)
        val nextPkg = intent?.getStringExtra(EXTRA_APP_PACKAGE).orEmpty().trim()
        val nextCls = intent?.getStringExtra(EXTRA_APP_ACTIVITY).orEmpty().trim()
        val nextAppLabel = intent?.getStringExtra(EXTRA_APP_LABEL).orEmpty().ifBlank { nextPkg }
        val nextPeerLabel = intent?.getStringExtra(EXTRA_PEER_LABEL).orEmpty().ifBlank { nextHost }
        val nextHandover = intent?.getStringExtra(EXTRA_HANDOVER_TOKEN).orEmpty().trim()

        if (nextHost.isEmpty() || nextPkg.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Strict one-way casting mutex guard
        val remoteSt = BridgeClient.remoteStatusFlow.value
        val check = OpalPeerCastMutex.validateStartReceiving(
            daemonSending = remoteSt.connected,
            sendingPeerName = remoteSt.peerName,
            targetHost = nextHost,
            selfLanIp = remoteSt.lanIp,
        )
        if (!check.allowed || !OpalPeerCastMutex.beginReceiving(nextHost, nextPort, nextPeerLabel, MUTEX_TAG)) {
            Toast.makeText(this, check.reason.ifBlank { "当前不可启动远程悬浮窗" }, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        OpalPeerDiscovery.stopAdvertise()
        if (remoteSt.wifiEnabled) {
            BridgeClient.setWifiStreamEnabledAsync(false)
        }

        host = nextHost
        port = nextPort
        peerLabel = nextPeerLabel
        appPackage = nextPkg
        appActivity = nextCls
        appLabel = nextAppLabel
        handoverToken = nextHandover

        startForegroundNotification(nextAppLabel, nextPeerLabel)
        _activeFlow.value = ActiveFloatingInfo(host, port, peerLabel, appPackage, appLabel)

        tearDownSessionOnly()
        ensureFloatingWindowAttached()
        connectRemoteStandaloneApp()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tearDownSessionOnly()
        removeAllOverlayViews()
        _activeFlow.value = null
        OpalPeerCastMutex.endReceiving(MUTEX_TAG)
        super.onDestroy()
    }

    private fun tearDownSessionOnly() {
        client?.stop()
        client = null
    }

    private fun connectRemoteStandaloneApp() {
        updateOverlayStatus("正在连接 $peerLabel…", dotColor = 0xFFF59E0B.toInt(), showRetry = false)
        val dm = resources.displayMetrics
        val (streamW, streamH) = computeStreamSizeForView(expandedW, expandedH)
        val reqDpi = if (isLandscape) 220 else 280
        val savedTok = AppPrefs.getPeerAuthToken(this, host)
        val tokenToUse = savedTok.ifBlank { handoverToken }
        handoverToken = "" // single-use in memory only

        val peerClient = OpalCastPeerClient(
            host = host,
            port = port,
            savedAuthToken = tokenToUse,
            clientId = AppPrefs.getOrCreatePeerClientId(this),
            reqWidth = streamW,
            reqHeight = streamH,
            reqDpi = reqDpi,
            reqFps = 60,
            sessionRole = OpalCastPeerClient.SESSION_ROLE_STANDALONE_APP,
            appPackage = appPackage,
            appActivity = appActivity,
            audioEnabled = false,
            listener = object : OpalCastPeerClient.Listener {
                override fun onWaitingForApproval() {
                    mainHandler.post {
                        updateOverlayStatus("等待对方设备点击允许…", dotColor = 0xFFF59E0B.toInt(), showRetry = false)
                    }
                }

                override fun onAuthenticated(
                    peerName: String,
                    authToken: String,
                    permanent: Boolean,
                    certFingerprint: String,
                ) {
                    if (permanent && authToken.isNotBlank()) {
                        AppPrefs.savePeerAuthToken(this@PeerFloatingAppService, host, authToken)
                    }
                    OpalPeerCastMutex.updateReceivingPeerName(peerName)
                    mainHandler.post {
                        peerLabel = peerName.ifBlank { peerLabel }
                        _activeFlow.value = ActiveFloatingInfo(host, port, peerLabel, appPackage, appLabel)
                        updateOverlayStatus("正在启动 $appLabel…", dotColor = 0xFF10B981.toInt(), showRetry = false)
                    }
                }

                override fun onVideoSizeChanged(width: Int, height: Int, fps: Int) {
                    mainHandler.post {
                        statusDotView?.background = circleDrawable(0xFF10B981.toInt())
                        overlayBanner?.visibility = View.GONE
                    }
                }

                override fun onError(message: String) {
                    mainHandler.post {
                        updateOverlayStatus(message, dotColor = 0xFFEF4444.toInt(), showRetry = true)
                    }
                }

                override fun onDisconnected(reason: String) {
                    mainHandler.post {
                        if (reason == "app_exited" || reason == "Session closed") {
                            stopSelf()
                            return@post
                        }
                        if (overlayBanner?.visibility != View.VISIBLE || overlayRetryBtn?.visibility != View.VISIBLE) {
                            updateOverlayStatus(reason.ifBlank { "连接已断开" }, dotColor = 0xFFEF4444.toInt(), showRetry = true)
                        }
                    }
                }
            },
        )
        client = peerClient
        activeSurface?.let { s ->
            if (s.isValid) peerClient.attachSurface(s)
        }
        peerClient.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureFloatingWindowAttached() {
        if (rootView != null) {
            titleTextView?.text = appLabel
            if (isCollapsed) expandFromBubble()
            return
        }
        val windowManager = wm ?: return
        val dm = resources.displayMetrics
        val minScreen = min(dm.widthPixels, dm.heightPixels)
        expandedW = (minScreen * 0.48f).roundToInt().coerceIn(dp(230), dp(360))
        expandedH = (expandedW * 1.72f).roundToInt().coerceIn(dp(340), (dm.heightPixels * 0.78f).roundToInt())
        savedX = dp(18)
        savedY = dp(90)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xEE141822.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x44FFFFFF)
            }
            elevation = dp(16).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(18).toFloat())
                }
            }
            clipToOutline = true
        }

        // ---- Integrated macOS-style Header Bar (Drag to move + quick controls) ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xE01A202E.toInt())
            setPadding(dp(10), dp(4), dp(6), dp(4))
        }

        val dot = View(this).apply {
            background = circleDrawable(0xFFF59E0B.toInt())
        }
        statusDotView = dot
        header.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(7) })

        val titleTv = TextView(this).apply {
            text = appLabel
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleTextView = titleTv
        header.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        header.addView(buildHeaderBtn("←", 0xFFE2E8F0.toInt()) {
            client?.sendSystemAction(OpalCastPeerClient.SYS_BACK)
        })
        header.addView(buildHeaderBtn("⌨", 0xFFE2E8F0.toInt()) {
            toggleImeBar()
        })
        header.addView(buildHeaderBtn("↻", 0xFFE2E8F0.toInt()) {
            toggleOrientation()
        })
        header.addView(buildHeaderBtn("—", 0xFFE2E8F0.toInt()) {
            collapseToBubble()
        })
        header.addView(buildHeaderBtn("×", 0xFFF87171.toInt()) {
            stopSelf()
        })

        installDragMoveListener(header)
        container.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))

        // ---- Optional Inline IME Input Bar (hidden by default so touches outside go to local apps) ----
        val imeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xF0111622.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
        }
        val edit = EditText(this).apply {
            hint = "输入文字发送到 $appLabel…"
            setHintTextColor(0x88CBD5E1.toInt())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundColor(Color.TRANSPARENT)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, ev ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
                ) {
                    sendImeBarText()
                    true
                } else {
                    false
                }
            }
        }
        imeEditText = edit
        val sendBtn = TextView(this).apply {
            text = "发送"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFF2563EB.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener { sendImeBarText() }
        }
        imeBar.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        imeBar.addView(sendBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(6)
        })
        imeInputBar = imeBar
        container.addView(imeBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- Live TextureView Content Area + Status Overlay + Corner Resize Handle ----
        val body = FrameLayout(this).apply {
            setBackgroundColor(0xFF0A0D14.toInt())
        }

        val tv = TextureView(this).apply {
            isOpaque = false
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    activeSurface?.release()
                    val surf = Surface(st)
                    activeSurface = surf
                    client?.attachSurface(surf)
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                    client?.requestKeyFrame()
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    client?.attachSurface(null)
                    activeSurface?.release()
                    activeSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    if (overlayBanner?.visibility == View.VISIBLE) {
                        overlayBanner?.visibility = View.GONE
                    }
                }
            }
            setOnTouchListener { v, ev ->
                val c = client ?: return@setOnTouchListener true
                if (c.isMouseMotion(ev)) c.sendMouseEvent(ev, v.width, v.height)
                else c.sendTouchEvent(ev, v.width, v.height)
                true
            }
            setOnGenericMotionListener { v, ev ->
                val c = client ?: return@setOnGenericMotionListener false
                if (c.isMouseMotion(ev)) {
                    c.sendMouseEvent(ev, v.width, v.height)
                    true
                } else {
                    false
                }
            }
        }
        textureView = tv
        body.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Status / approval / retry overlay
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC0D111A.toInt())
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val bannerTv = TextView(this).apply {
            text = "正在连接…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }
        val retryBtn = TextView(this).apply {
            text = "重新连接"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFF2563EB.toInt())
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(16), dp(7), dp(16), dp(7))
            visibility = View.GONE
            setOnClickListener {
                tearDownSessionOnly()
                connectRemoteStandaloneApp()
            }
        }
        overlayBanner = banner
        overlayBannerText = bannerTv
        overlayRetryBtn = retryBtn
        banner.addView(bannerTv)
        banner.addView(retryBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        body.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Bottom-right corner resize grip
        val resizeGrip = TextView(this).apply {
            text = "◢"
            setTextColor(0xAAFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(dp(4), dp(4), dp(4), dp(2))
        }
        installResizeListener(resizeGrip)
        body.addView(
            resizeGrip,
            FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.END),
        )

        container.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val lp = WindowManager.LayoutParams(
            expandedW,
            expandedH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        try {
            windowManager.addView(container, lp)
            rootView = container
            layoutParams = lp
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to attach floating overlay: ${t.message}")
            Toast.makeText(this, "无法创建悬浮窗：${t.message}", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun buildHeaderBtn(label: String, textColor: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = dp(28)
            minHeight = dp(28)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setOnClickListener { onClick() }
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDragMoveListener(handle: View) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, ev ->
            val lp = layoutParams ?: return@setOnTouchListener false
            val rv = rootView ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dm = resources.displayMetrics
                    lp.x = (startX + (ev.rawX - downRawX).roundToInt()).coerceIn(-lp.width / 2, dm.widthPixels - dp(48))
                    lp.y = (startY + (ev.rawY - downRawY).roundToInt()).coerceIn(dp(16), dm.heightPixels - dp(56))
                    savedX = lp.x
                    savedY = lp.y
                    safeUpdateViewLayout(rv, lp)
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installResizeListener(grip: View) {
        var downRawX = 0f
        var downRawY = 0f
        var startW = 0
        var startH = 0
        grip.setOnTouchListener { _, ev ->
            val lp = layoutParams ?: return@setOnTouchListener false
            val rv = rootView ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startW = lp.width
                    startH = lp.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dm = resources.displayMetrics
                    val minW = dp(210)
                    val maxW = (dm.widthPixels * 0.92f).roundToInt()
                    val minH = dp(260)
                    val maxH = (dm.heightPixels * 0.86f).roundToInt()
                    lp.width = (startW + (ev.rawX - downRawX).roundToInt()).coerceIn(minW, maxW)
                    lp.height = (startH + (ev.rawY - downRawY).roundToInt()).coerceIn(minH, maxH)
                    expandedW = lp.width
                    expandedH = lp.height
                    safeUpdateViewLayout(rv, lp)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    commitRemoteResize()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleOrientation() {
        val lp = layoutParams ?: return
        val rv = rootView ?: return
        val dm = resources.displayMetrics
        isLandscape = !isLandscape
        if (isLandscape) {
            expandedW = (dm.widthPixels * 0.84f).roundToInt().coerceIn(dp(320), dp(580))
            expandedH = (expandedW * 0.62f).roundToInt().coerceIn(dp(230), (dm.heightPixels * 0.60f).roundToInt())
        } else {
            val minScreen = min(dm.widthPixels, dm.heightPixels)
            expandedW = (minScreen * 0.48f).roundToInt().coerceIn(dp(230), dp(360))
            expandedH = (expandedW * 1.72f).roundToInt().coerceIn(dp(340), (dm.heightPixels * 0.78f).roundToInt())
        }
        lp.width = expandedW
        lp.height = expandedH
        lp.x = lp.x.coerceIn(dp(8), max(dp(8), dm.widthPixels - lp.width - dp(8)))
        lp.y = lp.y.coerceIn(dp(24), max(dp(24), dm.heightPixels - lp.height - dp(24)))
        savedX = lp.x
        savedY = lp.y
        safeUpdateViewLayout(rv, lp)
        commitRemoteResize()
    }

    private fun commitRemoteResize() {
        val (streamW, streamH) = computeStreamSizeForView(expandedW, expandedH)
        val dpi = if (streamW > streamH) 220 else 280
        client?.sendResizeConfig(streamW, streamH, dpi, 60, 9_000)
    }

    private fun computeStreamSizeForView(viewW: Int, viewH: Int): Pair<Int, Int> {
        val contentH = max(dp(180), viewH - dp(36))
        val ratio = viewW.toFloat() / contentH.toFloat()
        val targetLong = 1080
        val w: Int
        val h: Int
        if (ratio >= 1f) {
            w = targetLong
            h = (targetLong / ratio).roundToInt().coerceIn(540, 1080)
        } else {
            h = targetLong
            w = (targetLong * ratio).roundToInt().coerceIn(540, 1080)
        }
        return (w and 7.inv()).coerceAtLeast(360) to (h and 7.inv()).coerceAtLeast(360)
    }

    @Suppress("DEPRECATION")
    private fun toggleImeBar() {
        val lp = layoutParams ?: return
        val rv = rootView ?: return
        val bar = imeInputBar ?: return
        val edit = imeEditText ?: return
        isImeBarVisible = !isImeBarVisible
        if (isImeBarVisible) {
            bar.visibility = View.VISIBLE
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            safeUpdateViewLayout(rv, lp)
            edit.requestFocus()
            mainHandler.postDelayed({
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }, 60L)
        } else {
            hideImeBar()
        }
    }

    private fun hideImeBar() {
        isImeBarVisible = false
        val edit = imeEditText
        if (edit != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(edit.windowToken, 0)
            edit.clearFocus()
        }
        imeInputBar?.visibility = View.GONE
        val lp = layoutParams ?: return
        val rv = rootView ?: return
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        safeUpdateViewLayout(rv, lp)
    }

    private fun sendImeBarText() {
        val edit = imeEditText ?: return
        val txt = edit.text?.toString().orEmpty()
        if (txt.isNotEmpty()) {
            client?.sendTextEvent(txt, commitPaste = true)
            edit.setText("")
        }
        hideImeBar()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun collapseToBubble() {
        if (isCollapsed) return
        hideImeBar()
        isCollapsed = true
        rootView?.visibility = View.GONE

        val windowManager = wm ?: return
        val initial = appLabel.trim().take(1).ifEmpty { "浮" }
        val pill = TextView(this).apply {
            text = initial
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEE1D4ED8.toInt())
                setStroke(dp(2), 0xAAFFFFFF.toInt())
            }
            elevation = dp(12).toFloat()
        }
        val bLp = WindowManager.LayoutParams(
            dp(52),
            dp(52),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX.coerceAtLeast(dp(10))
            y = savedY.coerceAtLeast(dp(48))
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        pill.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = bLp.x
                    startY = bLp.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).roundToInt()
                    val dy = (ev.rawY - downY).roundToInt()
                    if (abs(dx) > slop || abs(dy) > slop) dragged = true
                    if (dragged) {
                        val dm = resources.displayMetrics
                        bLp.x = (startX + dx).coerceIn(0, dm.widthPixels - dp(52))
                        bLp.y = (startY + dy).coerceIn(dp(24), dm.heightPixels - dp(64))
                        safeUpdateViewLayout(pill, bLp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) expandFromBubble()
                    true
                }
                else -> false
            }
        }
        try {
            windowManager.addView(pill, bLp)
            bubbleView = pill
            bubbleParams = bLp
        } catch (_: Throwable) {
            expandFromBubble()
        }
    }

    private fun expandFromBubble() {
        isCollapsed = false
        bubbleView?.let { b ->
            try { wm?.removeView(b) } catch (_: Throwable) {}
        }
        bubbleView = null
        bubbleParams = null
        rootView?.visibility = View.VISIBLE
        client?.requestKeyFrame()
    }

    private fun updateOverlayStatus(text: String, dotColor: Int, showRetry: Boolean) {
        statusDotView?.background = circleDrawable(dotColor)
        overlayBannerText?.text = text
        overlayRetryBtn?.visibility = if (showRetry) View.VISIBLE else View.GONE
        overlayBanner?.visibility = View.VISIBLE
    }

    private fun removeAllOverlayViews() {
        bubbleView?.let { v ->
            try { wm?.removeView(v) } catch (_: Throwable) {}
        }
        bubbleView = null
        rootView?.let { v ->
            try { wm?.removeView(v) } catch (_: Throwable) {}
        }
        rootView = null
        activeSurface?.release()
        activeSurface = null
    }

    private fun safeUpdateViewLayout(view: View, params: WindowManager.LayoutParams) {
        try {
            wm?.updateViewLayout(view, params)
        } catch (_: Throwable) {
        }
    }

    private fun circleDrawable(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun startForegroundNotification(appTitle: String, hostName: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "远程应用悬浮窗",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持远程单应用悬浮窗在应用外运行"
        }
        nm.createNotificationChannel(ch)

        val openPi = PendingIntent.getActivity(
            this,
            3931,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPi = PendingIntent.getService(
            this,
            3932,
            Intent(this, PeerFloatingAppService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_opaldesk)
            .setContentTitle("悬浮窗：$appTitle")
            .setContentText("正在流式运行来自 $hostName 的应用")
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "关闭悬浮窗", stopPi).build())
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Floating foreground warning: ${t.message}")
        }
    }
}

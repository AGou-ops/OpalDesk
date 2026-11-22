package io.github.lengsukq.opaldesk.cast

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.OpalTheme
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface

/**
 * Fullscreen hardware-accelerated Android <-> Android OpalCast v1.1 Peer Receiver Workstation.
 *
 * Enforces strict one-way casting mutual exclusion ([OpalPeerCastMutex]): while receiving a remote
 * Android device's desktop, local Wi-Fi sending & NSD advertising are automatically paused so
 * simultaneous mutual casting (A <-> B) can never occur.
 */
class PeerCastViewerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_HOST = "peer_host"
        const val EXTRA_PORT = "peer_port"
        const val EXTRA_PIN = "peer_pin"
        const val EXTRA_LABEL = "peer_label"
    }

    private var client: OpalCastPeerClient? = null
    private var peerName by mutableStateOf("")
    private var statusText by mutableStateOf("正在建立 TLS 加密连接…")
    private var connected by mutableStateOf(false)
    private var videoW by mutableIntStateOf(1920)
    private var videoH by mutableIntStateOf(1080)
    private var videoFps by mutableIntStateOf(60)
    private var toolbarExpanded by mutableStateOf(true)
    private var errorText by mutableStateOf("")

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val host = intent.getStringExtra(EXTRA_HOST).orEmpty().trim()
        val port = intent.getIntExtra(EXTRA_PORT, 19878).coerceIn(1, 65535)
        val pin = intent.getStringExtra(EXTRA_PIN).orEmpty().trim()
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { host }
        peerName = label

        // Strict one-way guard: refuse if currently sending out or targeting self
        val remoteSt = BridgeClient.remoteStatusFlow.value
        val check = OpalPeerCastMutex.validateStartReceiving(
            daemonSending = remoteSt.connected,
            sendingPeerName = remoteSt.peerName,
            targetHost = host,
            selfLanIp = remoteSt.lanIp,
        )
        if (!check.allowed || !OpalPeerCastMutex.beginReceiving(host, port, label)) {
            Toast.makeText(this, check.reason.ifBlank { "当前不可建立反向投屏" }, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Auto-pause local Wi-Fi sender & mDNS broadcast so mutual simultaneous casting is impossible
        OpalPeerDiscovery.stopAdvertise()
        if (remoteSt.wifiEnabled) {
            BridgeClient.setWifiStreamEnabledAsync(false)
        }

        val dm = resources.displayMetrics
        val reqW = (dm.widthPixels.coerceAtLeast(dm.heightPixels) and 1.inv()).coerceIn(1280, 2560)
        val reqH = (dm.widthPixels.coerceAtMost(dm.heightPixels) and 1.inv()).coerceIn(720, 1600)
        val reqDpi = dm.densityDpi.coerceIn(160, 320)
        val reqFps = (display?.mode?.refreshRate?.toInt() ?: 60).coerceIn(60, 120)
        videoW = reqW
        videoH = reqH

        val peerClient = OpalCastPeerClient(
            host = host,
            port = port,
            pinCode = pin,
            savedAuthToken = AppPrefs.getPeerAuthToken(this, host),
            clientId = AppPrefs.getOrCreatePeerClientId(this),
            reqWidth = reqW,
            reqHeight = reqH,
            reqDpi = reqDpi,
            reqFps = reqFps,
            listener = object : OpalCastPeerClient.Listener {
                override fun onAuthenticated(peerName: String, authToken: String, certFingerprint: String) {
                    if (authToken.isNotBlank()) {
                        AppPrefs.savePeerAuthToken(this@PeerCastViewerActivity, host, authToken)
                    }
                    OpalPeerCastMutex.updateReceivingPeerName(peerName)
                    runOnUiThread {
                        this@PeerCastViewerActivity.peerName = peerName.ifBlank { label }
                        connected = true
                        errorText = ""
                        statusText = "已连接 · 等待画面关键帧"
                    }
                }

                override fun onVideoSizeChanged(width: Int, height: Int, fps: Int) {
                    runOnUiThread {
                        videoW = width.coerceAtLeast(2)
                        videoH = height.coerceAtLeast(2)
                        videoFps = fps
                        statusText = "${videoW}×${videoH} @ ${videoFps}Hz"
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        errorText = message
                        statusText = "连接中断：$message"
                    }
                }

                override fun onDisconnected(reason: String) {
                    runOnUiThread {
                        connected = false
                        if (errorText.isBlank()) statusText = reason
                    }
                }
            },
        )
        client = peerClient
        peerClient.start()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (connected) {
                        client?.sendSystemAction(OpalCastPeerClient.SYS_BACK)
                    } else {
                        finish()
                    }
                }
            },
        )

        setContent {
            OpalTheme {
                PeerCastViewerContent(
                    peerName = peerName,
                    statusText = statusText,
                    errorText = errorText,
                    connected = connected,
                    videoW = videoW,
                    videoH = videoH,
                    toolbarExpanded = toolbarExpanded,
                    onToggleToolbar = { toolbarExpanded = !toolbarExpanded },
                    onSurfaceCreated = { client?.attachSurface(it) },
                    onSurfaceDestroyed = { client?.attachSurface(null) },
                    onDispatchTouch = { ev, w, h ->
                        val c = client ?: return@PeerCastViewerContent
                        if (c.isMouseMotion(ev)) c.sendMouseEvent(ev, w, h) else c.sendTouchEvent(ev, w, h)
                    },
                    onDispatchGenericMotion = { ev, w, h ->
                        val c = client ?: return@PeerCastViewerContent
                        if (c.isMouseMotion(ev)) c.sendMouseEvent(ev, w, h)
                    },
                    onSendBack = { client?.sendSystemAction(OpalCastPeerClient.SYS_BACK) },
                    onSendHome = { client?.sendSystemAction(OpalCastPeerClient.SYS_HOME) },
                    onToggleMode = { client?.sendSystemAction(OpalCastPeerClient.SYS_TOGGLE_DISPLAY_MODE) },
                    onRequestIdr = { client?.requestKeyFrame() },
                    onDisconnect = { finish() },
                )
            }
        }
        applyImmersiveFullscreen()
    }

    override fun onDestroy() {
        client?.stop()
        client = null
        OpalPeerCastMutex.endReceiving()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (connected && (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
            if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
                event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
                event.keyCode != KeyEvent.KEYCODE_POWER
            ) {
                client?.sendKeyEvent(event.action, event.keyCode, event.metaState, event.repeatCount)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun applyImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun PeerCastViewerContent(
    peerName: String,
    statusText: String,
    errorText: String,
    connected: Boolean,
    videoW: Int,
    videoH: Int,
    toolbarExpanded: Boolean,
    onToggleToolbar: () -> Unit,
    onSurfaceCreated: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onDispatchTouch: (android.view.MotionEvent, Int, Int) -> Unit,
    onDispatchGenericMotion: (android.view.MotionEvent, Int, Int) -> Unit,
    onSendBack: () -> Unit,
    onSendHome: () -> Unit,
    onToggleMode: () -> Unit,
    onRequestIdr: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val ratio = (videoW.toFloat() / videoH.coerceAtLeast(1).toFloat()).coerceIn(0.45f, 3.0f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06080D)),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .aspectRatio(ratio, matchHeightConstraintsFirst = false),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    holder.addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                onSurfaceCreated(holder.surface)
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                onSurfaceCreated(holder.surface)
                            }
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                onSurfaceDestroyed()
                            }
                        },
                    )
                    setOnTouchListener { v, ev ->
                        onDispatchTouch(ev, v.width, v.height)
                        true
                    }
                    setOnGenericMotionListener { v, ev ->
                        onDispatchGenericMotion(ev, v.width, v.height)
                        true
                    }
                }
            },
        )

        if (!connected) {
            val cardShape = RoundedCornerShape(22.dp)
            Column(
                modifier = Modifier
                    .liquidGlassSurface(
                        shape = cardShape,
                        fill = OpalPalette.LiquidPopoverFill,
                        rim = OpalPalette.LiquidGlassRim,
                        elevation = 24.dp,
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OpalText(text = "正在连接 $peerName", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                OpalText(
                    text = if (errorText.isNotBlank()) errorText else statusText,
                    fontSize = 12.5.sp,
                    color = if (errorText.isNotBlank()) OpalPalette.AccentDanger else OpalPalette.TextSecondary,
                )
                Spacer(Modifier.height(4.dp))
                OpalButton(label = "返回主控制台", compact = true, onClick = onDisconnect)
            }
        }

        // Top floating Liquid Frosted Glass remote control pill
        if (toolbarExpanded) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .liquidGlassSurface(
                        shape = CircleShape,
                        fill = OpalPalette.LiquidDockFill,
                        rim = OpalPalette.LiquidGlassRim,
                        elevation = 18.dp,
                    )
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (connected) OpalPalette.AccentEmerald else OpalPalette.AccentAmber),
                )
                OpalText(
                    text = "$peerName · $statusText",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                PillChip("返回", onClick = onSendBack)
                PillChip("桌面", onClick = onSendHome)
                PillChip("镜像/桌面", onClick = onToggleMode)
                PillChip("刷新", onClick = onRequestIdr)
                PillChip("收起", onClick = onToggleToolbar)
                PillChip("断开", tint = OpalPalette.AccentDanger, onClick = onDisconnect)
            }
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 12.dp)
                    .liquidGlassSurface(
                        shape = CircleShape,
                        fill = OpalPalette.LiquidDockFill,
                        rim = OpalPalette.LiquidGlassRim,
                        elevation = 12.dp,
                    )
                    .clickable(onClick = onToggleToolbar)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                OpalText(text = "投屏控制", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PillChip(
    label: String,
    tint: Color = OpalPalette.TextPrimary,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(OpalPalette.ChipFill)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        OpalText(text = label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = tint, maxLines = 1)
    }
}

package io.github.lengsukq.opaldesk.cast

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.ui.OpalButton
import io.github.lengsukq.opaldesk.ui.OpalPalette
import io.github.lengsukq.opaldesk.ui.OpalStatusBadge
import io.github.lengsukq.opaldesk.ui.OpalText
import io.github.lengsukq.opaldesk.ui.OpalTextInput
import io.github.lengsukq.opaldesk.ui.liquidGlassSurface
import kotlinx.coroutines.delay

/**
 * Liquid Glass modal letting the receiver user browse installed apps on a paired remote Android
 * device and pop out any app as an independent system floating window ([PeerFloatingAppService]).
 */
@Composable
fun RemoteAppPickerModal(
    host: String,
    port: Int,
    peerLabel: String,
    preloadedApps: List<OpalCastPeerClient.RemoteAppEntry>? = null,
    onDismiss: () -> Unit,
    onFloatingLaunched: () -> Unit = onDismiss,
) {
    val ctx = LocalContext.current
    var remoteAppList by remember { mutableStateOf(preloadedApps ?: emptyList()) }
    var resolvedPeerName by remember { mutableStateOf(peerLabel.ifBlank { host }) }
    var statusText by remember { mutableStateOf(if (preloadedApps != null) "" else "正在连接 $peerLabel 获取应用列表…") }
    var errorText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var handoverToken by remember { mutableStateOf("") }
    var retryNonce by remember { mutableIntStateOf(0) }
    var hasOverlayPerm by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }

    // Re-check overlay permission cleanly when the user returns from Android Settings
    LaunchedEffect(hasOverlayPerm) {
        if (!hasOverlayPerm) {
            while (!hasOverlayPerm) {
                delay(650L)
                if (Settings.canDrawOverlays(ctx)) {
                    hasOverlayPerm = true
                }
            }
        }
    }

    // Fetch app catalog over a lightweight `app_catalog` TLS session when not preloaded
    if (preloadedApps == null) {
        DisposableEffect(host, port, retryNonce) {
            val mainHandler = Handler(Looper.getMainLooper())
            statusText = "正在连接 $peerLabel 获取应用列表…"
            errorText = ""
            var clientRef: OpalCastPeerClient? = null
            val client = OpalCastPeerClient(
                host = host,
                port = port,
                savedAuthToken = AppPrefs.getPeerAuthToken(ctx, host),
                clientId = AppPrefs.getOrCreatePeerClientId(ctx),
                reqWidth = 720,
                reqHeight = 1280,
                reqDpi = 240,
                reqFps = 60,
                sessionRole = OpalCastPeerClient.SESSION_ROLE_APP_CATALOG,
                audioEnabled = false,
                listener = object : OpalCastPeerClient.Listener {
                    override fun onWaitingForApproval() {
                        mainHandler.post {
                            statusText = "等待对方设备点击「允许」…"
                        }
                    }

                    override fun onAuthenticated(
                        peerName: String,
                        authToken: String,
                        permanent: Boolean,
                        certFingerprint: String,
                    ) {
                        if (permanent && authToken.isNotBlank()) {
                            AppPrefs.savePeerAuthToken(ctx, host, authToken)
                        }
                        mainHandler.post {
                            resolvedPeerName = peerName.ifBlank { peerLabel }
                            if (!permanent && authToken.isNotBlank()) {
                                // Single-use in-memory handover token for launching 1 chosen floating app
                                handoverToken = authToken
                            }
                            statusText = "已授权，正在读取应用列表…"
                        }
                        clientRef?.requestAppList()
                    }

                    override fun onAppListReceived(apps: List<OpalCastPeerClient.RemoteAppEntry>) {
                        mainHandler.post {
                            remoteAppList = apps
                            statusText = ""
                        }
                    }

                    override fun onVideoSizeChanged(width: Int, height: Int, fps: Int) = Unit

                    override fun onError(message: String) {
                        mainHandler.post {
                            errorText = message
                            statusText = ""
                        }
                    }

                    override fun onDisconnected(reason: String) {
                        mainHandler.post {
                            if (remoteAppList.isEmpty() && errorText.isBlank()) {
                                errorText = reason
                                statusText = ""
                            }
                        }
                    }
                },
            )
            clientRef = client
            client.start()
            onDispose {
                client.stop()
            }
        }
    } else {
        LaunchedEffect(preloadedApps) {
            remoteAppList = preloadedApps
        }
    }

    val filtered = remember(remoteAppList, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) remoteAppList
        else remoteAppList.filter { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
    }

    fun openOverlayPermissionSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(ctx, "请前往系统设置手动授予 OpalDesk 悬浮窗权限", Toast.LENGTH_LONG).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.48f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            )
            .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .liquidGlassSurface(
                    shape = RoundedCornerShape(24.dp),
                    fill = OpalPalette.LiquidPopoverFill,
                    rim = OpalPalette.LiquidGlassRim,
                    elevation = 28.dp,
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OpalText(text = "独立应用悬浮窗", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    OpalText(
                        text = "从「$resolvedPeerName」选择任意应用，在应用外以悬浮窗运行",
                        fontSize = 12.sp,
                        color = OpalPalette.TextSecondary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OpalButton(label = "关闭", compact = true, filled = false, onClick = onDismiss)
            }

            if (!hasOverlayPerm) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(OpalPalette.AccentAmber.copy(alpha = 0.14f))
                        .border(0.8.dp, OpalPalette.AccentAmber.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OpalStatusBadge(label = "需要悬浮窗权限", color = OpalPalette.AccentAmber)
                    OpalText(
                        text = "开启「显示在其他应用上层」后，即可一边使用本机应用，一边通过悬浮窗操作对方手机上的独立应用。",
                        fontSize = 12.sp,
                        color = OpalPalette.TextSecondary,
                    )
                    OpalButton(
                        label = "前往开启悬浮窗权限",
                        accent = OpalPalette.AccentBlue,
                        compact = true,
                        onClick = ::openOverlayPermissionSettings,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            OpalTextInput(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "搜索对方手机上的应用（如 微信、哔哩哔哩）…",
                modifier = Modifier.fillMaxWidth(),
            )

            when {
                errorText.isNotBlank() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OpalText(text = errorText, fontSize = 13.sp, color = OpalPalette.AccentDanger)
                        OpalButton(
                            label = "重新请求应用列表",
                            compact = true,
                            onClick = { retryNonce++ },
                        )
                    }
                }
                statusText.isNotBlank() && remoteAppList.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        OpalText(text = statusText, fontSize = 13.sp, color = OpalPalette.TextSecondary)
                    }
                }
                filtered.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        OpalText(text = "没有找到匹配的应用", fontSize = 13.sp, color = OpalPalette.TextMuted)
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(filtered, key = { it.pkg }) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(OpalPalette.ChipFill)
                                    .border(0.75.dp, OpalPalette.BorderSubtle, RoundedCornerShape(14.dp))
                                    .clickable {
                                        if (!Settings.canDrawOverlays(ctx)) {
                                            hasOverlayPerm = false
                                            openOverlayPermissionSettings()
                                            return@clickable
                                        }
                                        val started = PeerFloatingAppService.start(
                                            ctx = ctx,
                                            host = host,
                                            port = port,
                                            peerLabel = resolvedPeerName,
                                            appPackage = app.pkg,
                                            appActivity = app.cls,
                                            appLabel = app.label,
                                            handoverToken = handoverToken,
                                        )
                                        if (started) {
                                            Toast.makeText(ctx, "正在以悬浮窗打开「${app.label}」", Toast.LENGTH_SHORT).show()
                                            onFloatingLaunched()
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(OpalPalette.AccentBlue.copy(alpha = 0.18f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    OpalText(
                                        text = app.label.trim().take(1).ifEmpty { "A" },
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = OpalPalette.AccentBlue,
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    OpalText(
                                        text = app.label,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                    OpalText(
                                        text = app.pkg,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = OpalPalette.TextMuted,
                                        maxLines = 1,
                                    )
                                }
                                OpalText(
                                    text = "悬浮打开",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OpalPalette.AccentEmerald,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

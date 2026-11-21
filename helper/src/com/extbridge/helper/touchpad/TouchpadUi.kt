package com.extbridge.helper.touchpad

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.extbridge.helper.design.iosglass.GlassTier
import com.extbridge.helper.design.iosglass.LiquidGlassSurface
import com.extbridge.helper.design.iosglass.LiquidGlassTheme
import com.extbridge.helper.design.iosglass.LiquidMeshBackground
import com.extbridge.helper.design.iosglass.LiquidShapes
import com.extbridge.helper.design.iosglass.SquircleShape
import com.extbridge.helper.protocol.BridgeLabels
import com.extbridge.helper.ui.ExtVectorIcons
import com.extbridge.helper.ui.HyperAmber
import com.extbridge.helper.ui.HyperBlue
import com.extbridge.helper.ui.HyperRed
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class TouchpadUiState(
    val dark: Boolean,
    val curX: Int,
    val curY: Int,
    val virtW: Int,
    val virtH: Int,
    val inputToExt: Boolean,
    val injectDenied: Boolean,
    val oneHandPortrait: Boolean,
    val imeVisible: Boolean,
    val imeText: String,
    val useCtrlV: Boolean,
    val dragLocked: Boolean,
    val speedMultiplier: Float,
    val blackoutActive: Boolean,
)

data class TouchpadActions(
    val onExit: () -> Unit,
    val onToggleInput: () -> Unit,
    val onToggleIme: () -> Unit,
    val onDismissOneHand: () -> Unit,
    val onToggleOneHand: () -> Unit,
    val onLeftClick: () -> Unit,
    val onToggleDragLock: () -> Unit,
    val onRightClickOrBack: () -> Unit,
    val onSendBack: () -> Unit,
    val onRelaunchLauncher: () -> Unit,
    val onOpenMissionControl: () -> Unit = {},
    val onCycleSpeed: () -> Unit,
    val onToggleBlackout: () -> Unit,
    val onOpenSideControl: () -> Unit,
    val onSendKey: (Int, Int) -> Unit,
    val onImeTextChange: (String) -> Unit,
    val onToggleUseCtrlV: () -> Unit,
    val onCommitImeText: () -> Unit,
    val trackpadListener: NativeTrackpadView.Listener,
    val onAttachTrackpadView: (NativeTrackpadView) -> Unit,
)

private val CTRL_META = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
private val META_MOD = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
private val SHORTCUT_KEYS = listOf(
    Triple("Enter", KeyEvent.KEYCODE_ENTER, 0), Triple("退格", KeyEvent.KEYCODE_DEL, 0),
    Triple("空格", KeyEvent.KEYCODE_SPACE, 0), Triple("Esc", KeyEvent.KEYCODE_ESCAPE, 0),
    Triple("Tab", KeyEvent.KEYCODE_TAB, 0), Triple("Ctrl+A", KeyEvent.KEYCODE_A, CTRL_META),
    Triple("Ctrl+C", KeyEvent.KEYCODE_C, CTRL_META), Triple("Ctrl+V", KeyEvent.KEYCODE_V, CTRL_META),
    Triple("↑", KeyEvent.KEYCODE_DPAD_UP, 0), Triple("↓", KeyEvent.KEYCODE_DPAD_DOWN, 0),
    Triple("←", KeyEvent.KEYCODE_DPAD_LEFT, 0), Triple("→", KeyEvent.KEYCODE_DPAD_RIGHT, 0),
    Triple("Home", KeyEvent.KEYCODE_HOME, 0),
)

@Composable
fun TouchpadScreen(state: TouchpadUiState, actions: TouchpadActions) {
    LiquidMeshBackground(
        modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding(),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isPortrait = maxHeight > maxWidth
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TouchpadHeaderCard(state, actions)
                if (state.injectDenied) {
                    LiquidGlassSurface(
                        cornerRadius = 18.dp,
                        tintOverlay = HyperRed.copy(alpha = 0.14f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) { Text(text = BridgeLabels.getOemInjectHint(), fontSize = 11.5.sp, color = HyperRed) }
                }
                if (isPortrait && state.oneHandPortrait && !state.imeVisible) {
                    OneHandShelf(actions.onDismissOneHand, Modifier.fillMaxWidth().weight(0.62f))
                }
                TrackpadSurfaceBox(state, actions, Modifier.fillMaxWidth().weight(1f))
                ContinuityWindowDeckStrip(actions)
                TrackpadButtonRow(state.dragLocked, actions)
                BottomActionStrip(state, isPortrait, actions)
                AnimatedVisibility(visible = state.imeVisible) { RemoteImeDock(state.imeText, state.useCtrlV, actions) }
            }
        }
    }
}

@Composable
private fun TouchpadHeaderCard(state: TouchpadUiState, actions: TouchpadActions) {
    val glass = LiquidGlassTheme.colors
    LiquidGlassSurface(
        cornerRadius = 22.dp,
        tier = GlassTier.Thick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onExit, modifier = Modifier.size(34.dp)) {
                Icon(ExtVectorIcons.ArrowBack, "退出", modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Continuity 触控板伴侣", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = glass.textPrimary, maxLines = 1)
                Text(
                    text = "(${state.curX}, ${state.curY}) · ${state.virtW}×${state.virtH}",
                    fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                    color = glass.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            ActionPill(if (state.inputToExt) "扩展屏" else "本机屏", ExtVectorIcons.RouteSwap, state.inputToExt, actions.onToggleInput)
            Spacer(Modifier.width(6.dp))
            ActionPill("键盘", ExtVectorIcons.Keyboard, state.imeVisible, actions.onToggleIme)
        }
    }
}

@Composable
private fun ContinuityWindowDeckStrip(actions: TouchpadActions) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionPill("调度中心", ExtVectorIcons.MonitorDex, false, actions.onOpenMissionControl)
        ActionPill("⌘Space 搜索", null, false) { actions.onSendKey(KeyEvent.KEYCODE_SPACE, META_MOD) }
        ActionPill("贴左半屏", null, false) { actions.onSendKey(KeyEvent.KEYCODE_DPAD_LEFT, META_MOD) }
        ActionPill("贴右半屏", null, false) { actions.onSendKey(KeyEvent.KEYCODE_DPAD_RIGHT, META_MOD) }
        ActionPill("最小化 ⌘M", null, false) { actions.onSendKey(KeyEvent.KEYCODE_M, META_MOD) }
        ActionPill("关闭窗 ⌘W", null, false) { actions.onSendKey(KeyEvent.KEYCODE_W, META_MOD) }
    }
}

@Composable
private fun OneHandShelf(onClick: () -> Unit, modifier: Modifier) {
    val glass = LiquidGlassTheme.colors
    LiquidGlassSurface(
        modifier = modifier,
        cornerRadius = 24.dp,
        tier = GlassTier.UltraThin,
        onClick = onClick,
        contentPadding = PaddingValues(16.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("单手拇指热区模式", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = glass.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text("触控板与左右键已下移至底部 · 点此恢复全屏大触控板", fontSize = 12.sp, color = glass.textSecondary)
            }
        }
    }
}

@Composable
private fun TrackpadSurfaceBox(state: TouchpadUiState, actions: TouchpadActions, modifier: Modifier) {
    val glass = LiquidGlassTheme.colors
    val padSurface = glass.glassRegular.toArgb()
    val padStroke = glass.specularTopLeft.toArgb()
    val accentArgb = HyperBlue.toArgb()
    Box(modifier = modifier.clip(SquircleShape(26.dp))) {
        AndroidView(
            factory = { ctx -> NativeTrackpadView(ctx).also { bindTrackpadView(it, state, actions, padSurface, padStroke, accentArgb) } },
            update = { view -> bindTrackpadView(view, state, actions, padSurface, padStroke, accentArgb) },
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = "单指移动 · 轻触左键 · 双指轻触右键 · 右侧滑轨/双指滚轮",
            fontSize = 11.sp, color = glass.textSecondary.copy(alpha = 0.78f),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 12.dp),
        )
    }
}

private fun bindTrackpadView(
    view: NativeTrackpadView, state: TouchpadUiState, actions: TouchpadActions,
    surfaceArgb: Int, strokeArgb: Int, accentArgb: Int,
) {
    view.listener = actions.trackpadListener
    view.speedMultiplier = state.speedMultiplier
    view.virtualWidth = state.virtW
    view.isDragLocked = state.dragLocked
    actions.onAttachTrackpadView(view)
    view.applyThemeColors(surfaceArgb, strokeArgb, accentArgb, state.dark)
}

@Composable
private fun TrackpadButtonRow(dragLocked: Boolean, actions: TouchpadActions) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrackpadButton("左键点击", active = false, onClick = actions.onLeftClick, modifier = Modifier.weight(1.8f))
        TrackpadButton(
            text = if (dragLocked) "拖拽锁定" else "拖拽", active = dragLocked,
            activeColor = HyperAmber, onClick = actions.onToggleDragLock, modifier = Modifier.weight(1.2f),
        )
        TrackpadButton("右键 / 返回", active = false, onClick = actions.onRightClickOrBack, modifier = Modifier.weight(1.8f))
    }
}

@Composable
private fun BottomActionStrip(state: TouchpadUiState, isPortrait: Boolean, actions: TouchpadActions) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionPill("返回", ExtVectorIcons.ArrowBack, false, actions.onSendBack)
        ActionPill("扩展桌面", ExtVectorIcons.MonitorDex, false, actions.onRelaunchLauncher)
        ActionPill("${state.speedMultiplier}x 速度", null, false, actions.onCycleSpeed)
        if (isPortrait) ActionPill(if (state.oneHandPortrait) "单手" else "全屏", ExtVectorIcons.OneHand, state.oneHandPortrait, actions.onToggleOneHand)
        ActionPill("一键黑屏", ExtVectorIcons.MoonDim, state.blackoutActive, actions.onToggleBlackout)
        ActionPill("副屏直控", ExtVectorIcons.SidePanel, false, actions.onOpenSideControl)
    }
}

@Composable
private fun RemoteImeDock(imeText: String, useCtrlV: Boolean, actions: TouchpadActions) {
    Card(cornerRadius = 18.dp, insideMargin = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SHORTCUT_KEYS.forEach { (label, keyCode, meta) ->
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(MiuixTheme.colorScheme.secondaryContainer)
                            .clickable { actions.onSendKey(keyCode, meta) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = imeText, onValueChange = actions.onImeTextChange, label = "输入中英文直达外接屏…",
                    singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { actions.onCommitImeText() }), modifier = Modifier.weight(1f),
                )
                TextButton(if (useCtrlV) "Ctrl+V" else "PASTE", actions.onToggleUseCtrlV, insideMargin = PaddingValues(10.dp))
                TextButton("发送", actions.onCommitImeText, colors = ButtonDefaults.textButtonColorsPrimary(), insideMargin = PaddingValues(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun TrackpadButton(text: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, activeColor: Color = HyperBlue) {
    val glass = LiquidGlassTheme.colors
    val fg = if (active) Color.White else glass.textPrimary
    LiquidGlassSurface(
        modifier = modifier.height(48.dp),
        cornerRadius = 18.dp,
        tier = if (active) GlassTier.Thick else GlassTier.Regular,
        tintOverlay = if (active) activeColor.copy(alpha = 0.88f) else Color.Transparent,
        onClick = onClick,
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
        }
    }
}

@Composable
private fun ActionPill(label: String, icon: ImageVector?, active: Boolean, onClick: () -> Unit) {
    val glass = LiquidGlassTheme.colors
    val bg = if (active) glass.accentBlue else glass.glassRegular
    val fg = if (active) Color.White else glass.textPrimary
    Row(
        modifier = Modifier.clip(LiquidShapes.Capsule).background(bg)
            .border(0.9.dp, if (active) glass.accentBlue else glass.specularTopLeft, LiquidShapes.Capsule)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, label, tint = fg, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)) }
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1)
    }
}

package io.github.lengsukq.opaldesk.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.design.iosglass.GlassTier
import io.github.lengsukq.opaldesk.design.iosglass.LiquidGlassSurface
import io.github.lengsukq.opaldesk.design.iosglass.LiquidGlassTheme
import io.github.lengsukq.opaldesk.design.iosglass.ProvideLiquidGlassTheme
import io.github.lengsukq.opaldesk.design.iosglass.SquircleShape
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.ConnectionTone
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.*

object ExtThemePrefs {
    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    val THEME_LABELS = listOf("跟随系统外观", "iOS 冰透浅色", "iOS 曜石深色")
    fun getThemeModeIndex(ctx: Context): Int = AppPrefs.getUiThemeModeIndex(ctx)
    fun setThemeModeIndex(ctx: Context, idx: Int) = AppPrefs.setUiThemeModeIndex(ctx, idx)
    fun toColorSchemeMode(idx: Int): ColorSchemeMode = when (idx) {
        MODE_LIGHT -> ColorSchemeMode.Light
        MODE_DARK -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }
}

val HyperBlue = Color(0xFF007AFF)
val HyperGreen = Color(0xFF34C759)
val HyperAmber = Color(0xFFFF9F0A)
val HyperRed = Color(0xFFFF3B30)
val HyperPurple = Color(0xFF5856D6)

fun ConnectionTone.toColor(): Color = when (this) {
    ConnectionTone.ERROR -> HyperRed
    ConnectionTone.SUCCESS -> HyperGreen
    ConnectionTone.PRIMARY -> HyperBlue
    ConnectionTone.ACCENT -> HyperPurple
}

@Composable
fun SectionDivider(horizontalPadding: Dp = 0.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .height(0.6.dp)
            .background(LiquidGlassTheme.colors.hairlineDivider),
    )
}

@Composable
fun SectionHeader(title: String, horizontalPadding: Dp = 6.dp) {
    SmallTitle(text = title, insideMargin = PaddingValues(horizontal = horizontalPadding, vertical = 2.dp))
}

@Composable
fun DangerActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues? = null,
) {
    val colors = ButtonDefaults.textButtonColors(color = HyperRed.copy(alpha = 0.14f), textColor = HyperRed)
    if (insideMargin != null) TextButton(text, onClick, colors = colors, insideMargin = insideMargin, modifier = modifier)
    else TextButton(text, onClick, colors = colors, modifier = modifier)
}

@Composable
fun ExtHyperTheme(themeModeIndex: Int, content: @Composable () -> Unit) {
    val isDark = isEffectiveDark(themeModeIndex)
    val lightScheme = remember {
        lightColorScheme(
            primary = HyperBlue, primaryVariant = Color(0xFF0062CC),
            surface = Color(0xFFEEF3FB), background = Color(0xFFEEF3FB),
            surfaceContainer = Color(0xD6F9FBFF), surfaceVariant = Color(0xE8FFFFFF),
            secondaryContainer = Color(0xB8EEF2FA), secondaryVariant = Color(0xCCE8EEF8),
            dividerLine = Color(0x1A0F172A), outline = Color(0x88FFFFFF),
        )
    }
    val darkScheme = remember {
        darkColorScheme(
            primary = Color(0xFF0A84FF), primaryVariant = Color(0xFF409CFF),
            surface = Color(0xFF090C14), background = Color(0xFF090C14),
            surfaceContainer = Color(0xB8191F2E), surfaceVariant = Color(0xCC1E2536),
            secondaryContainer = Color(0x99232B3E), secondaryVariant = Color(0xB3283146),
            dividerLine = Color(0x24FFFFFF), outline = Color(0x44FFFFFF),
        )
    }
    val mode = ExtThemePrefs.toColorSchemeMode(themeModeIndex)
    val controller = remember(mode) { ThemeController(colorSchemeMode = mode, lightColors = lightScheme, darkColors = darkScheme) }
    ProvideLiquidGlassTheme(darkTheme = isDark) {
        MiuixTheme(controller = controller, content = content)
    }
}

@Composable
fun isEffectiveDark(themeModeIndex: Int): Boolean = when (themeModeIndex) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun StatusPillBadge(label: String, dotColor: Color, modifier: Modifier = Modifier) {
    val rimColor = dotColor.copy(alpha = 0.28f)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(dotColor.copy(alpha = 0.14f))
            .border(0.8.dp, rimColor, RoundedCornerShape(50))
            .padding(horizontal = 11.dp, vertical = 4.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = dotColor, maxLines = 1)
    }
}

/** iOS Control Center Liquid Glass quick-action Bento tile with continuous G2 squircle geometry. */
@Composable
fun HyperBentoTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = HyperBlue,
) {
    val glass = LiquidGlassTheme.colors
    val titleColor = if (active) Color.White else glass.textPrimary
    val subColor = if (active) Color.White.copy(alpha = 0.84f) else glass.textSecondary
    val iconBg = if (active) Color.White.copy(alpha = 0.24f) else activeColor.copy(alpha = 0.14f)
    val iconTint = if (active) Color.White else activeColor

    LiquidGlassSurface(
        modifier = modifier,
        cornerRadius = 22.dp,
        tier = if (active) GlassTier.Thick else GlassTier.Regular,
        tintOverlay = if (active) activeColor.copy(alpha = 0.86f) else Color.Transparent,
        elevation = if (active) 14.dp else 8.dp,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) {
                Icon(icon, title, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 11.5.sp, color = subColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 2x2 Visual Mirror Layout Selector Cards with custom Canvas mini-monitor illustrations. */
@Composable
fun MirrorLayoutSelectorGrid(selectedMode: Int, onSelectMode: (Int) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf(
        Triple(0, "纯黑居中", "无边栏 · 100% 纯画面"),
        Triple(1, "主屏靠左", "右侧融合宽边栏 · 推荐"),
        Triple(2, "主屏靠右", "左侧融合宽边栏"),
        Triple(3, "主屏居中", "左右对称双信息条"),
    )
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (modeVal, title, sub) ->
                    MirrorLayoutVisualCard(modeVal, title, sub, selectedMode == modeVal, { onSelectMode(modeVal) }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MirrorLayoutVisualCard(
    mode: Int, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val glass = LiquidGlassTheme.colors
    val accent = glass.accentBlue
    val shape = remember { SquircleShape(20.dp) }
    val selectedMod = if (selected) Modifier.border(1.8.dp, accent, shape) else Modifier

    LiquidGlassSurface(
        modifier = modifier.then(selectedMod),
        cornerRadius = 20.dp,
        tier = if (selected) GlassTier.Thick else GlassTier.Regular,
        tintOverlay = if (selected) accent.copy(alpha = 0.12f) else Color.Transparent,
        onClick = onClick,
        contentPadding = PaddingValues(13.dp),
    ) {
        val frameColor = glass.textSecondary.copy(alpha = 0.48f)
        val mainFill = if (selected) accent.copy(alpha = 0.88f) else glass.textSecondary.copy(alpha = 0.32f)
        val sideFill = if (selected) glass.accentIndigo.copy(alpha = 0.58f) else glass.glassPillInactive

        Canvas(modifier = Modifier.fillMaxWidth().height(46.dp)) {
            val mw = size.width * 0.78f; val mh = size.height * 0.88f
            val mx = (size.width - mw) * 0.5f; val my = (size.height - mh) * 0.5f
            val pad = 4.dp.toPx()
            drawRoundRect(frameColor, Offset(mx, my), Size(mw, mh), CornerRadius(6.dp.toPx()), Stroke(1.6.dp.toPx()))
            val iw = mw - pad * 2; val ih = mh - pad * 2; val ix = mx + pad; val iy = my + pad
            val r = CornerRadius(3.5.dp.toPx()); val gap = 3.dp.toPx()
            when (mode) {
                0 -> { val cw = iw * 0.62f; drawRoundRect(mainFill, Offset(ix + (iw - cw) * 0.5f, iy), Size(cw, ih), r) }
                1 -> { val lw = iw * 0.63f; drawRoundRect(mainFill, Offset(ix, iy), Size(lw, ih), r); drawRoundRect(sideFill, Offset(ix + lw + gap, iy), Size(iw - lw - gap, ih), r) }
                2 -> { val sw = iw * 0.34f; drawRoundRect(sideFill, Offset(ix, iy), Size(sw, ih), r); drawRoundRect(mainFill, Offset(ix + sw + gap, iy), Size(iw - sw - gap, ih), r) }
                else -> {
                    val wing = iw * 0.18f; val cw = iw - wing * 2 - gap * 2
                    drawRoundRect(sideFill, Offset(ix, iy), Size(wing, ih), r)
                    drawRoundRect(mainFill, Offset(ix + wing + gap, iy), Size(cw, ih), r)
                    drawRoundRect(sideFill, Offset(ix + wing + cw + gap * 2, iy), Size(wing, ih), r)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = if (selected) accent else glass.textPrimary, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, fontSize = 11.sp, color = glass.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Cursor-safe wrapper around Miuix [TextField] using the [TextFieldValue] overload.
 *
 * Miuix 0.9.4's `OverlayBottomSheet` / `OverlayDialog` (`MiuixPopupUtils.DialogLayout`) forwards its
 * content lambda to `MiuixPopupHost` inside a `SideEffect`, introducing a 1-frame delay for plain
 * `String` state hoisted outside the overlay. Keeping [TextFieldValue] locally inside the overlay
 * composition preserves cursor selection and IME composition spans while filtering stale echoes.
 */
@Composable
fun OpalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    resetKey: Any? = Unit,
) {
    var tfv by remember(resetKey) {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    var lastExternalValue by remember(resetKey) { mutableStateOf(value) }
    val pendingEchoes = remember(resetKey) { ArrayDeque<String>() }

    if (value != lastExternalValue) {
        lastExternalValue = value
        val echoIdx = pendingEchoes.indexOf(value)
        if (echoIdx >= 0) {
            repeat(echoIdx + 1) { pendingEchoes.removeFirst() }
        } else {
            pendingEchoes.clear()
            if (value != tfv.text) {
                tfv = TextFieldValue(text = value, selection = TextRange(value.length))
            }
        }
    }

    TextField(
        value = tfv,
        onValueChange = { next ->
            val prevText = tfv.text
            tfv = next
            if (next.text != prevText) {
                if (pendingEchoes.size >= 32) {
                    pendingEchoes.removeFirst()
                }
                pendingEchoes.addLast(next.text)
                onValueChange(next.text)
            }
        },
        modifier = modifier,
        label = label,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
    )
}

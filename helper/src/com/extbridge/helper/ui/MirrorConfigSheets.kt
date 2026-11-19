package com.extbridge.helper.ui

import android.appwidget.AppWidgetProviderInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.extbridge.helper.prefs.MirrorThemeCatalog
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

data class WidgetCandidateItem(
    val info: AppWidgetProviderInfo,
    val title: String,
    val subtitle: String,
    val iconBitmap: ImageBitmap?,
)

private val CUSTOM_COLOR_SWATCHES = listOf(
    "午夜藏青" to 0xFF0B132B.toInt(), "深海孔雀绿" to 0xFF072227.toInt(),
    "暗夜酒红" to 0xFF2B0B18.toInt(), "钛空深灰" to 0xFF1C1E26.toInt(),
    "抹茶雅白" to 0xFFE8F3EA.toInt(), "暖杏米白" to 0xFFFBF6EE.toInt(),
)
private val HEX_COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}$")

@Composable
fun MirrorPresetManagerSheet(
    show: Boolean,
    slots: List<String>,
    onUpdateSlots: (List<String>) -> Unit,
    onEditMemo: () -> Unit,
    onResetDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pickingSlotIdx by remember(show) { mutableStateOf<Int?>(null) }

    OverlayBottomSheet(
        show = show,
        title = when (pickingSlotIdx) { null -> "边栏信息卡片管理"; -1 -> "添加边栏信息卡"; else -> "替换信息卡" },
        onDismissRequest = { if (pickingSlotIdx != null) pickingSlotIdx = null else onDismiss() },
    ) {
        val targetIdx = pickingSlotIdx
        if (targetIdx != null) {
            PresetCandidatePicker(slots, targetIdx, { onUpdateSlots(it); pickingSlotIdx = null }) { pickingSlotIdx = null }
        } else {
            ActivePresetSlotList(slots, { pickingSlotIdx = it }, onUpdateSlots, onEditMemo, onResetDefault)
        }
    }
}

@Composable
private fun PresetCandidatePicker(slots: List<String>, targetIdx: Int, onSelect: (List<String>) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LazyColumn(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(MirrorThemeCatalog.MIRROR_PRESET_KEYS.indices.toList()) { i ->
                val key = MirrorThemeCatalog.MIRROR_PRESET_KEYS[i]
                Card(
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
                    onClick = {
                        val next = slots.toMutableList()
                        if (targetIdx in next.indices) next[targetIdx] = key else next.add(key)
                        onSelect(next)
                    },
                ) {
                    Text(MirrorThemeCatalog.MIRROR_PRESET_TITLES[i], fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(MirrorThemeCatalog.MIRROR_PRESET_DESCS[i], fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        TextButton("返回已启用列表", onBack, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ActivePresetSlotList(
    slots: List<String>,
    onPickSlot: (Int) -> Unit,
    onUpdateSlots: (List<String>) -> Unit,
    onEditMemo: () -> Unit,
    onResetDefault: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("已启用 ${slots.size} 张信息卡（点击名称可替换，右侧按钮调序或移除）：", fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        LazyColumn(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(slots.indices.toList()) { idx ->
                val key = slots[idx]
                Card(insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f).clickable { onPickSlot(idx) }) {
                            Text("${idx + 1}. ${MirrorThemeCatalog.mirrorPresetTitle(key)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            val pIdx = MirrorThemeCatalog.mirrorPresetIndex(key)
                            if (pIdx >= 0) {
                                Text(
                                    MirrorThemeCatalog.MIRROR_PRESET_DESCS[pIdx], fontSize = 11.5.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (key == "memo") {
                            TextButton("便签", onEditMemo, insideMargin = PaddingValues(horizontal = 10.dp, vertical = 5.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        IconButton(enabled = idx > 0, onClick = { onUpdateSlots(slots.toMutableList().apply { add(idx - 1, removeAt(idx)) }) }) {
                            Icon(ExtVectorIcons.ArrowUp, "上移", modifier = Modifier.size(18.dp))
                        }
                        IconButton(enabled = idx < slots.lastIndex, onClick = { onUpdateSlots(slots.toMutableList().apply { add(idx + 1, removeAt(idx)) }) }) {
                            Icon(ExtVectorIcons.ArrowDown, "下移", modifier = Modifier.size(18.dp))
                        }
                        if (slots.size > 1) {
                            IconButton(onClick = { onUpdateSlots(slots.toMutableList().apply { removeAt(idx) }) }) {
                                Icon(ExtVectorIcons.TrashDelete, "移除", tint = HyperRed, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton("添加信息卡", { onPickSlot(-1) }, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.weight(1f))
            TextButton("恢复默认", onResetDefault, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun MirrorMemoDialog(show: Boolean, initialText: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var memo by remember(show, initialText) { mutableStateOf(initialText) }
    OverlayDialog(show = show, title = "编辑边栏快捷便签", summary = "保存后即时同步至外接显示器融合边栏", onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(memo, { memo = it }, label = "输入待办、会议提醒或备忘文字…", minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton("取消", onDismiss, modifier = Modifier.weight(1f))
                TextButton("保存并同步", { onSave(memo.trim()); onDismiss() }, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun CustomMirrorColorDialog(show: Boolean, initialColorInt: Int, onApplyColor: (Int) -> Unit, onDismiss: () -> Unit) {
    var hex by remember(show, initialColorInt) { mutableStateOf(String.format(Locale.US, "#%06X", 0xFFFFFF and initialColorInt)) }
    var errorMsg by remember(show) { mutableStateOf("") }

    OverlayDialog(show = show, title = "自定义副屏背景色", summary = "输入 #RRGGBB 十六进制色值或点击下方预设色板（自动适配深浅文字）", onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(hex, { hex = it; errorMsg = "" }, label = "#RRGGBB", singleLine = true, modifier = Modifier.fillMaxWidth())
            if (errorMsg.isNotEmpty()) Text(errorMsg, fontSize = 12.sp, color = HyperRed)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CUSTOM_COLOR_SWATCHES.chunked(2).forEach { pair ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { (name, cInt) ->
                            Row(
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                    .background(MiuixTheme.colorScheme.secondaryContainer)
                                    .clickable { hex = String.format(Locale.US, "#%06X", 0xFFFFFF and cInt); errorMsg = "" }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(Color(cInt)).border(1.dp, Color.Gray.copy(alpha = 0.4f), CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(name, fontSize = 12.5.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton("取消", onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = "应用颜色", colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.weight(1f),
                    onClick = {
                        val trimmed = hex.trim()
                        val normalized = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
                        if (!HEX_COLOR_REGEX.matches(normalized)) {
                            errorMsg = "无效的色值格式，请输入 6 位十六进制颜色（如 #1E293B）"
                            return@TextButton
                        }
                        onApplyColor(android.graphics.Color.parseColor(normalized))
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
fun MirrorWidgetSlotSheet(
    show: Boolean,
    slotIndex: Int,
    boundWidgetLabel: String?,
    currentRatioIdx: Int,
    candidates: List<WidgetCandidateItem>,
    loading: Boolean,
    onChangeRatio: (Int) -> Unit,
    onClearWidget: () -> Unit,
    onPickCandidate: (AppWidgetProviderInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(show) { mutableStateOf("") }
    val filtered = remember(candidates, query) {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) candidates else candidates.filter { it.title.lowercase(Locale.ROOT).contains(q) || it.subtitle.lowercase(Locale.ROOT).contains(q) }
    }

    OverlayBottomSheet(show = show, title = "边栏系统小组件槽位 ${slotIndex + 1}", onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (boundWidgetLabel != null) {
                Card {
                    OverlayDropdownPreference(
                        title = "当前已绑定：$boundWidgetLabel", summary = "调整小组件长宽比例防止拉伸变形",
                        items = MirrorThemeCatalog.MIRROR_WIDGET_RATIO_LABELS.toList(),
                        selectedIndex = currentRatioIdx.coerceIn(0, MirrorThemeCatalog.MIRROR_WIDGET_RATIO_LABELS.lastIndex),
                        onSelectedIndexChange = onChangeRatio,
                    )
                }
                TextButton("清除此槽位小组件", { onClearWidget(); onDismiss() }, modifier = Modifier.fillMaxWidth())
            }
            TextField(query, { query = it }, label = "搜索系统小组件名称或所属应用…", singleLine = true, modifier = Modifier.fillMaxWidth())
            if (loading) {
                Text("正在加载已安装的系统小组件…", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(vertical = 16.dp))
            } else {
                LazyColumn(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered) { item ->
                        Card(insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp), onClick = { onDismiss(); onPickCandidate(item.info) }) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (item.iconBitmap != null) {
                                    Image(item.iconBitmap, item.title, modifier = Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)))
                                    Spacer(Modifier.width(12.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text(item.subtitle, fontSize = 11.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionChecklistDialog(
    show: Boolean,
    isDevGate: Boolean,
    notifOk: Boolean,
    overlayOk: Boolean,
    a11yOk: Boolean,
    onGrantNotif: () -> Unit,
    onGrantOverlay: () -> Unit,
    onGrantA11y: () -> Unit,
    onOpenRestricted: () -> Unit,
    onSkipOrContinue: () -> Unit,
) {
    val allOk = notifOk && overlayOk && a11yOk
    OverlayDialog(
        show = show,
        title = if (isDevGate) "前往开发者选项前 · 请补齐权限" else "运行权限健康检查",
        summary = if (isDevGate) "为确保无线调试顶部配对通知、外接鼠标准星与全局手势正常生效，建议先开启以下权限："
        else "外接桌面 Dock、鼠标浮层、全局返回手势及无线配对通知依赖以下三项核心权限：",
        onDismissRequest = onSkipOrContinue,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PermItemRow("通知栏常驻与配对框权限", notifOk, onGrantNotif)
            PermItemRow("悬浮窗与外接光标权限", overlayOk, onGrantOverlay)
            PermItemRow("无障碍手势与浮层权限 (必需)", a11yOk, onGrantA11y)
            if (!a11yOk) TextButton("无障碍开关呈灰色？进入应用详情允许受限设置", onOpenRestricted, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            TextButton(
                text = when { allOk -> "全部已就绪 · 完成"; isDevGate -> "暂不补齐，仍前往开发者选项"; else -> "稍后在设置页处理" },
                colors = if (allOk) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                onClick = onSkipOrContinue, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PermItemRow(title: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceContainer)
            Text(if (granted) "已授权" else "未开启", fontSize = 11.5.sp, color = if (granted) HyperGreen else HyperAmber)
        }
        if (!granted) {
            TextButton("去开启", onGrant, colors = ButtonDefaults.textButtonColorsPrimary(), insideMargin = PaddingValues(horizontal = 14.dp, vertical = 7.dp))
        } else {
            StatusPillBadge("就绪", HyperGreen)
        }
    }
}

@Composable
fun DevOptionsGuideDialog(show: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    OverlayDialog(show = show, title = "首次前往「开发者选项」指引", summary = "已发送无线调试配对通知至状态栏。进入开发者选项后请一并开启：", onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer), insideMargin = PaddingValues(14.dp)) {
                Text(
                    "1. 无线调试：点击「使用配对码配对设备」，下拉顶部通知即可直接填入 6 位配对码。\n\n" +
                        "2. USB 调试（安全设置）：Xiaomi HyperOS 必开项，否则外接屏鼠标与触控板点击会被系统拦截。",
                    fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceContainer,
                )
            }
            TextButton("我已知晓，立即打开开发者选项", onConfirm, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.fillMaxWidth())
        }
    }
}

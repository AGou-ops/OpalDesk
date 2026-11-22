package io.github.lengsukq.opaldesk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lengsukq.opaldesk.protocol.BridgeLabels
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.AppMode
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

data class AppRuleEntry(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap?,
    val mode: Int,
)

@Composable
fun AppRulesPage(ctrl: MainController, contentPadding: PaddingValues) {
    var searchQuery by remember { mutableStateOf("") }
    val modeOptions = remember {
        listOf(AppMode.DEFAULT, AppMode.LAND_FULL, AppMode.PORT_COMPAT).map(BridgeLabels::appModeLabel)
    }

    if (!ctrl.daemonStatus.connected) {
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(insideMargin = PaddingValues(18.dp)) {
                Text("需先激活 OpalDesk 核心服务", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("分应用横屏/竖屏兼容规则由守护进程实时维护并在扩展屏启动时生效。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(14.dp))
                TextButton("立即激活服务", { ctrl.openActivationSheet() }, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.fillMaxWidth())
            }
        }
        return
    }

    val allItems = ctrl.appRuleItems.toList()
    val customCount = allItems.count { it.mode > AppMode.DEFAULT }
    val filtered = remember(allItems, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) allItems else allItems.filter { it.label.lowercase(Locale.ROOT).contains(q) || it.pkg.lowercase(Locale.ROOT).contains(q) }
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            OpalTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                label = "搜索应用名称或包名（如：微信 / 夸克 / 红果）", singleLine = true,
                leadingIcon = { Icon(ExtVectorIcons.SearchLens, null, modifier = Modifier.padding(start = 12.dp).size(18.dp)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ctrl.appRulesLoading) "正在读取已安装应用…" else "共 ${allItems.size} 款应用 · 已自定义 $customCount 款",
                    fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (customCount > 0) {
                    TextButton("全部重置默认", { ctrl.resetAllAppRules() }, insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
        items(filtered, key = { it.pkg }) { item ->
            Card {
                OverlayDropdownPreference(
                    title = item.label, summary = item.pkg, items = modeOptions,
                    selectedIndex = item.mode.coerceIn(0, modeOptions.lastIndex),
                    startAction = item.icon?.let { bmp ->
                        { Image(bmp, item.label, modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))) }
                    },
                    onSelectedIndexChange = { ctrl.updateAppRuleMode(item, it) },
                )
            }
        }
    }
}

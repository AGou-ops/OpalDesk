package com.extbridge.helper.launcher

import android.app.Activity
import android.view.View
import android.widget.Toast
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.BridgeService
import com.extbridge.helper.HyperUi
import com.extbridge.helper.prefs.AppPrefs
import com.extbridge.helper.protocol.BridgeLabels
import com.extbridge.helper.protocol.BridgeProtocol.AppMode
import com.extbridge.helper.protocol.BridgeProtocol.LayoutMode
import com.extbridge.helper.protocol.BridgeProtocol.WindowAction
import com.extbridge.helper.protocol.BridgeProtocol.WindowMode

/**
 * Manages single-instance macOS/iOS-style frosted-glass context menus and sheets on the desktop
 * (per-app display mode, window launch, DPI reload, and 2/3/4-way multi-tiling assignment).
 */
class LauncherAppDialogs(
    private val activity: Activity,
    private val runIo: (block: suspend () -> Unit) -> Unit,
    private val onAppModesUpdated: (Map<String, Int>) -> Unit,
) {
    internal val presenter = MacContextMenuPresenter(activity)

    val isShowing: Boolean
        get() = presenter.isShowing

    fun dismissAll() {
        presenter.dismiss(animated = true)
    }

    fun showAllAppModesPicker(allApps: List<LauncherAppEntry>, appModes: Map<String, Int>) {
        if (allApps.isEmpty()) {
            Toast.makeText(activity, "应用列表加载中…", Toast.LENGTH_SHORT).show()
            return
        }
        val sorted = allApps.sortedWith(
            compareBy<LauncherAppEntry> { if ((appModes[it.pkg] ?: AppMode.DEFAULT) > 0) 0 else 1 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
        )
        val sheetItems = sorted.map { entry ->
            val mode = appModes[entry.pkg] ?: AppMode.DEFAULT
            MacSheetItem(
                id = entry.pkg,
                title = entry.label,
                subtitle = entry.pkg,
                badge = BridgeLabels.appModeLabel(mode),
                badgeColor = when (mode) {
                    AppMode.LAND_FULL -> 0xFF007AFF.toInt()
                    AppMode.PORT_COMPAT -> 0xFFFF9500.toInt()
                    else -> 0xFF8E8E93.toInt()
                },
                icon = entry.icon,
            )
        }
        val byPkg = sorted.associateBy { it.pkg }
        presenter.showListSheet(
            title = "应用显示规则",
            subtitle = "点选应用自定义横屏全屏或竖屏兼容，也可在桌面长按/右键图标设置",
            items = sheetItems,
            searchable = true,
            searchHint = "搜索应用名或包名…",
            topActionLabel = "全部恢复【跟随系统默认】",
            onTopAction = {
                runIo {
                    BridgeClient.resetAllAppModes()
                    val latest = BridgeClient.getAllAppModes()
                    activity.runOnUiThread {
                        onAppModesUpdated(latest)
                        Toast.makeText(activity, "已全部恢复系统默认模式（重启对应应用生效）", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onSingleSelect = { picked ->
                byPkg[picked.id]?.let { entry ->
                    showAppModeDialog(entry, appModes[entry.pkg] ?: AppMode.DEFAULT)
                }
            },
        )
    }

    fun showAppModeDialog(
        entry: LauncherAppEntry,
        currentMode: Int,
        anchorView: View? = null,
        anchorX: Int? = null,
        anchorY: Int? = null,
    ) {
        val header = MacMenuHeader(
            title = entry.label,
            subtitle = entry.pkg,
            icon = entry.icon,
        )
        val entries = buildAppMenuEntries(entry, currentMode)
        presenter.showContextMenu(
            header = header,
            entries = entries,
            anchorView = anchorView,
            anchorX = anchorX,
            anchorY = anchorY,
            widthDp = 276f,
            triggerHaptic = true,
        )
    }

    private fun buildAppMenuEntries(entry: LauncherAppEntry, currentMode: Int): List<MacMenuEntry> {
        val pinned = AppPrefs.isDesktopAppPinned(activity, entry.pkg)
        val dockPinned = AppPrefs.isDockAppPinned(activity, entry.pkg)
        return listOf(
            MacMenuEntry.Action(
                label = "以自由小窗打开",
                subtitle = "可自由拖拽移动与缩放",
                iconKind = HyperUi.ICON_WINDOW,
                onClick = { launchInMode(entry, WindowMode.FREEFORM, "正在以自由小窗打开：${entry.label}") },
            ),
            MacMenuEntry.Action(
                label = "以居中宽幅打开",
                iconKind = HyperUi.ICON_RATIO,
                onClick = { launchInMode(entry, WindowMode.ADAPTIVE_SINGLE, "正在以居中宽幅打开：${entry.label}") },
            ),
            MacMenuEntry.Action(
                label = "加入 2/3/4 分屏",
                iconKind = HyperUi.ICON_SPLIT,
                hasSubmenu = true,
                keepOpen = true,
                onClick = { showJoinMultiSplitSubmenu(entry, currentMode) },
            ),
            MacMenuEntry.Divider,
            MacMenuEntry.Action(
                label = if (dockPinned) "从任务栏取消固定" else "常驻固定到任务栏",
                iconKind = HyperUi.ICON_DEX,
                onClick = {
                    val nowPinned = AppPrefs.toggleDockPinnedApp(activity, entry.pkg)
                    Toast.makeText(activity, if (nowPinned) "已固定到任务栏：${entry.label}" else "已从任务栏取消固定：${entry.label}", Toast.LENGTH_SHORT).show()
                },
            ),
            MacMenuEntry.Action(
                label = if (pinned) "从桌面移出图标" else "固定到桌面",
                iconKind = HyperUi.ICON_PIN,
                destructive = pinned,
                onClick = {
                    if (pinned) {
                        AppPrefs.unpinDesktopApp(activity, entry.pkg)
                        Toast.makeText(activity, "已从桌面移出：${entry.label}", Toast.LENGTH_SHORT).show()
                    } else {
                        AppPrefs.pinDesktopApp(activity, entry.pkg)
                        Toast.makeText(activity, "已固定到桌面：${entry.label}", Toast.LENGTH_SHORT).show()
                    }
                },
            ),
            MacMenuEntry.Action(
                label = "重载应用 DPI 并重启",
                subtitle = "修复字体或排版偏大",
                iconKind = HyperUi.ICON_ROTATE,
                onClick = {
                    Toast.makeText(activity, "正在重载 DPI 并重启：${entry.label}", Toast.LENGTH_SHORT).show()
                    runIo {
                        BridgeClient.windowLifecycleAction(-1, entry.pkg, WindowAction.RELOAD_DPI)
                        BridgeService.refreshFromReceiver(activity)
                    }
                },
            ),
            MacMenuEntry.Divider,
            MacMenuEntry.SectionHeader("显示兼容规则"),
            MacMenuEntry.Action(
                label = "跟随系统默认",
                subtitle = "推荐微信 / 浏览器等日常应用",
                checked = currentMode == AppMode.DEFAULT,
                onClick = { applyAppMode(entry, AppMode.DEFAULT) },
            ),
            MacMenuEntry.Action(
                label = "强制横屏全屏",
                subtitle = "适合短剧 / 视频类应用",
                checked = currentMode == AppMode.LAND_FULL,
                onClick = { applyAppMode(entry, AppMode.LAND_FULL) },
            ),
            MacMenuEntry.Action(
                label = "竖屏居中兼容",
                subtitle = "适合固定竖屏比例应用",
                checked = currentMode == AppMode.PORT_COMPAT,
                onClick = { applyAppMode(entry, AppMode.PORT_COMPAT) },
            ),
        )
    }

    private fun showJoinMultiSplitSubmenu(entry: LauncherAppEntry, currentMode: Int) {
        val dm = activity.resources.displayMetrics
        val portrait = dm.heightPixels > dm.widthPixels
        val header = MacMenuHeader(
            title = "加入分屏 · ${entry.label}",
            subtitle = "选择目标分屏布局",
            icon = entry.icon,
        )
        val splitEntries = listOf(
            MacMenuEntry.Action(
                label = if (portrait) "2 分屏 · 放入上屏" else "2 分屏 · 放入左屏",
                iconKind = HyperUi.ICON_SPLIT,
                onClick = {
                    val side = if (portrait) "上屏" else "左屏"
                    Toast.makeText(activity, "已放入分屏【$side】：${entry.label}", Toast.LENGTH_SHORT).show()
                    BridgeService.performAssignSplitFromUi(0, -1, entry.pkg, entry.cls)
                },
            ),
            MacMenuEntry.Action(
                label = if (portrait) "2 分屏 · 放入下屏" else "2 分屏 · 放入右屏",
                iconKind = HyperUi.ICON_SPLIT,
                onClick = {
                    val side = if (portrait) "下屏" else "右屏"
                    Toast.makeText(activity, "已放入分屏【$side】：${entry.label}", Toast.LENGTH_SHORT).show()
                    BridgeService.performAssignSplitFromUi(1, -1, entry.pkg, entry.cls)
                },
            ),
            MacMenuEntry.Divider,
            MacMenuEntry.Action(
                label = "1+2 三分屏",
                subtitle = "左主屏 + 右上下双窗",
                iconKind = HyperUi.ICON_SIDEBAR,
                onClick = { joinMultiTilingTopology(entry, LayoutMode.SPLIT_3_MASTER, 3, listOf(50, 50)) },
            ),
            MacMenuEntry.Action(
                label = "三列分屏",
                subtitle = "左 / 中 / 右三窗并排",
                iconKind = HyperUi.ICON_SPLIT,
                onClick = { joinMultiTilingTopology(entry, LayoutMode.SPLIT_3_COLS, 3, listOf(33, 34, 33)) },
            ),
            MacMenuEntry.Action(
                label = "2×2 四宫格",
                subtitle = "4 应用同屏协作",
                iconKind = HyperUi.ICON_APPS,
                onClick = { joinMultiTilingTopology(entry, LayoutMode.GRID_2X2, 4, listOf(50, 50)) },
            ),
        )
        presenter.replaceContextMenu(
            header = header,
            entries = splitEntries,
            backAction = {
                presenter.replaceContextMenu(
                    header = MacMenuHeader(entry.label, entry.pkg, entry.icon),
                    entries = buildAppMenuEntries(entry, currentMode),
                )
            },
        )
    }

    private fun applyAppMode(entry: LauncherAppEntry, chosen: Int) {
        Toast.makeText(
            activity,
            "正在应用【${BridgeLabels.appModeLabel(chosen)}】并重启应用…",
            Toast.LENGTH_SHORT,
        ).show()
        runIo {
            BridgeClient.setAppMode(entry.pkg, chosen, true)
            val latest = BridgeClient.getAllAppModes()
            activity.runOnUiThread { onAppModesUpdated(latest) }
        }
    }

    private fun launchInMode(entry: LauncherAppEntry, mode: Int, toastMsg: String) {
        Toast.makeText(activity, toastMsg, Toast.LENGTH_SHORT).show()
        runIo {
            val ok = BridgeClient.launchInWindowMode(entry.pkg, entry.cls, mode, 0, 0, 0, 0)
            if (!ok) BridgeClient.launchComponent(entry.pkg, entry.cls)
            BridgeService.refreshFromReceiver(activity)
        }
    }

    private fun joinMultiTilingTopology(
        entry: LauncherAppEntry,
        topology: String,
        maxSlots: Int,
        defaultRatios: List<Int>,
    ) {
        Toast.makeText(
            activity,
            "正在加入【${BridgeLabels.layoutModeLabel(topology)}】：${entry.label}",
            Toast.LENGTH_SHORT,
        ).show()
        val selfPkg = activity.packageName
        runIo {
            val pkgs = LinkedHashSet<String>()
            val snap = BridgeClient.getManagedWindows()
            for (w in snap.windows) {
                if (!w.minimized && w.pkg.isNotBlank() && w.pkg != selfPkg && w.pkg != "android") {
                    pkgs.add(w.pkg)
                }
            }
            for (t in BridgeClient.getTasks()) {
                if (t.pkg.isNotBlank() && t.pkg != selfPkg && t.pkg != "android") {
                    pkgs.add(t.pkg)
                }
            }
            val ordered = ArrayList<String>(maxSlots).apply {
                add(entry.pkg)
                for (p in pkgs) {
                    if (p != entry.pkg && size < maxSlots) add(p)
                }
            }
            BridgeClient.setTilingLayout(topology, ordered, defaultRatios)
            BridgeService.refreshFromReceiver(activity)
        }
    }
}

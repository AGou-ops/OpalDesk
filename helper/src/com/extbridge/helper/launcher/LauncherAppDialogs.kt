package com.extbridge.helper.launcher

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.widget.Toast
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.BridgeService
import com.extbridge.helper.protocol.BridgeLabels
import com.extbridge.helper.protocol.BridgeProtocol.AppMode
import com.extbridge.helper.protocol.BridgeProtocol.LayoutMode
import com.extbridge.helper.protocol.BridgeProtocol.WindowAction
import com.extbridge.helper.protocol.BridgeProtocol.WindowMode

/**
 * Manages single-instance context dialogs on the DeX desktop (per-app display mode, window launch,
 * DPI reload, and 2/3/4-way multi-tiling assignment), always dismissing any prior dialog to prevent leaks.
 */
class LauncherAppDialogs(
    private val activity: Activity,
    private val runIo: (block: suspend () -> Unit) -> Unit,
    private val onAppModesUpdated: (Map<String, Int>) -> Unit,
) {
    companion object {
        private const val TAG = "ExtLauncherDlg"
    }

    private var activeDialog: AlertDialog? = null

    val isShowing: Boolean
        get() = activeDialog?.isShowing == true

    fun dismissAll() {
        val dlg = activeDialog
        activeDialog = null
        if (dlg != null && dlg.isShowing) {
            try {
                dlg.dismiss()
            } catch (t: Throwable) {
                Log.d(TAG, "Failed to dismiss active launcher dialog: ${t.message}")
            }
        }
    }

    private fun showManaged(builder: AlertDialog.Builder) {
        dismissAll()
        if (activity.isFinishing || activity.isDestroyed) return
        val dlg = builder.setOnDismissListener { d ->
            if (activeDialog === d) activeDialog = null
        }.show()
        activeDialog = dlg
    }

    fun showAllAppModesPicker(allApps: List<LauncherAppEntry>, appModes: Map<String, Int>) {
        if (allApps.isEmpty()) {
            Toast.makeText(activity, "应用列表加载中...", Toast.LENGTH_SHORT).show()
            return
        }
        val sorted = allApps.sortedWith(
            compareBy<LauncherAppEntry> { if ((appModes[it.pkg] ?: AppMode.DEFAULT) > 0) 0 else 1 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
        )
        val items = Array(sorted.size + 1) { idx ->
            if (idx == 0) {
                "全部恢复【跟随系统默认】（清除所有自定义兼容覆盖）"
            } else {
                val entry = sorted[idx - 1]
                val mode = appModes[entry.pkg] ?: AppMode.DEFAULT
                "${entry.label}  [${BridgeLabels.appModeLabel(mode)}]"
            }
        }
        showManaged(
            AlertDialog.Builder(activity)
                .setTitle("分应用显示规则（可在桌面图标长按或鼠标右键设置）")
                .setItems(items) { _, which ->
                    if (which == 0) {
                        runIo {
                            BridgeClient.resetAllAppModes()
                            val latest = BridgeClient.getAllAppModes()
                            activity.runOnUiThread {
                                onAppModesUpdated(latest)
                                Toast.makeText(activity, "已全部恢复系统默认模式（重启对应应用生效）", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else if (which - 1 in sorted.indices) {
                        val picked = sorted[which - 1]
                        showAppModeDialog(picked, appModes[picked.pkg] ?: AppMode.DEFAULT)
                    }
                }
                .setNegativeButton("关闭", null),
        )
    }

    fun showAppModeDialog(entry: LauncherAppEntry, currentMode: Int) {
        val labels = arrayOf(
            "以居中宽幅打开（默认黄金比例）",
            "以自由窗口打开（可移动与自由缩放）",
            "加入 2/3/4 分屏…",
            "重载应用 DPI 并重启（修复字体偏大）",
            (if (currentMode == AppMode.DEFAULT) "● " else "") + "显示规则：跟随系统默认（推荐微信 / 夸克）",
            (if (currentMode == AppMode.LAND_FULL) "● " else "") + "显示规则：强制横屏全屏（适合短剧/视频 App）",
            (if (currentMode == AppMode.PORT_COMPAT) "● " else "") + "显示规则：竖屏居中兼容（适合固定比例竖屏 App）",
        )
        showManaged(
            AlertDialog.Builder(activity)
                .setTitle("${entry.label}\n${entry.pkg}")
                .setItems(labels) { _, which -> handleAppModeChoice(entry, which) }
                .setNegativeButton("取消", null),
        )
    }

    private fun handleAppModeChoice(entry: LauncherAppEntry, which: Int) {
        when (which) {
            0 -> launchInMode(entry, WindowMode.ADAPTIVE_SINGLE, "正在以居中宽幅打开：${entry.label}")
            1 -> launchInMode(entry, WindowMode.FREEFORM, "正在以自由窗口打开：${entry.label}")
            2 -> showJoinMultiSplitDialog(entry)
            3 -> {
                Toast.makeText(activity, "正在重载 DPI 并重启：${entry.label}", Toast.LENGTH_SHORT).show()
                runIo {
                    BridgeClient.windowLifecycleAction(-1, entry.pkg, WindowAction.RELOAD_DPI)
                    BridgeService.refreshFromReceiver(activity)
                }
            }
            else -> {
                val chosen = (which - 4).coerceIn(AppMode.DEFAULT, AppMode.PORT_COMPAT)
                Toast.makeText(
                    activity,
                    "正在应用【${BridgeLabels.appModeLabel(chosen)}】并重启应用...",
                    Toast.LENGTH_SHORT,
                ).show()
                runIo {
                    BridgeClient.setAppMode(entry.pkg, chosen, true)
                    val latest = BridgeClient.getAllAppModes()
                    activity.runOnUiThread { onAppModesUpdated(latest) }
                }
            }
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

    private fun showJoinMultiSplitDialog(entry: LauncherAppEntry) {
        val dm = activity.resources.displayMetrics
        val portrait = dm.heightPixels > dm.widthPixels
        val items = arrayOf(
            if (portrait) "2 分屏：放入上屏" else "2 分屏：放入左屏",
            if (portrait) "2 分屏：放入下屏" else "2 分屏：放入右屏",
            "1+2 三分屏（左主 + 右上下）",
            "三列分屏（左 / 中 / 右）",
            "2×2 四宫格（4 应用同屏）",
        )
        showManaged(
            AlertDialog.Builder(activity)
                .setTitle("加入分屏 · ${entry.label}")
                .setItems(items) { _, which ->
                    when (which) {
                        0, 1 -> {
                            val side = if (portrait) {
                                if (which == 0) "上屏" else "下屏"
                            } else {
                                if (which == 0) "左屏" else "右屏"
                            }
                            Toast.makeText(activity, "已放入分屏【$side】：${entry.label}", Toast.LENGTH_SHORT).show()
                            BridgeService.performAssignSplitFromUi(which, -1, entry.pkg, entry.cls)
                        }
                        2 -> joinMultiTilingTopology(entry, LayoutMode.SPLIT_3_MASTER, 3, listOf(50, 50))
                        3 -> joinMultiTilingTopology(entry, LayoutMode.SPLIT_3_COLS, 3, listOf(33, 34, 33))
                        4 -> joinMultiTilingTopology(entry, LayoutMode.GRID_2X2, 4, listOf(50, 50))
                    }
                }
                .setNegativeButton("取消", null),
        )
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

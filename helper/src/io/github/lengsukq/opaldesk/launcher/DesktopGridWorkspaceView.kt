package io.github.lengsukq.opaldesk.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.lengsukq.opaldesk.HyperUi
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeLabels
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.AppMode
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Persistent item on the OpalDesk desktop grid: either a single 1×1 app shortcut or
 * a folder supporting seamless toggling between 1×1 compact folders and 2×2 large folders.
 */
sealed class DesktopItem {
    abstract val id: String
    abstract val spanX: Int
    abstract val spanY: Int

    data class AppShortcut(val pkg: String) : DesktopItem() {
        override val id: String get() = "app:$pkg"
        override val spanX: Int get() = 1
        override val spanY: Int get() = 1
    }

    data class AppFolder(
        override val id: String,
        val name: String,
        val large: Boolean,
        val pkgs: List<String>,
    ) : DesktopItem() {
        override val spanX: Int get() = if (large) 2 else 1
        override val spanY: Int get() = if (large) 2 else 1
    }
}

internal object DesktopLayoutStore {
    fun loadItems(ctx: Context, installedByPkg: Map<String, LauncherAppEntry>): MutableList<DesktopItem> {
        val raw = AppPrefs.getDesktopLayoutJson(ctx)
        val pinned = AppPrefs.getDesktopPinnedApps(ctx)
        val result = ArrayList<DesktopItem>()
        val seenPkgs = HashSet<String>()

        if (raw.isNotBlank()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    when (o.optString("t")) {
                        "app" -> {
                            val pkg = o.optString("pkg").trim()
                            if (pkg.isNotEmpty() && installedByPkg.containsKey(pkg) && seenPkgs.add(pkg)) {
                                result.add(DesktopItem.AppShortcut(pkg))
                            }
                        }
                        "folder" -> {
                            val id = o.optString("id").ifBlank { "f_${SystemClock.uptimeMillis()}_$i" }
                            val name = o.optString("name").ifBlank { "文件夹" }
                            val large = o.optBoolean("large", false)
                            val pArr = o.optJSONArray("pkgs")
                            val pkgs = ArrayList<String>()
                            if (pArr != null) {
                                for (j in 0 until pArr.length()) {
                                    val p = pArr.optString(j).trim()
                                    if (p.isNotEmpty() && installedByPkg.containsKey(p) && seenPkgs.add(p)) {
                                        pkgs.add(p)
                                    }
                                }
                            }
                            when {
                                pkgs.size >= 2 -> result.add(DesktopItem.AppFolder(id, name, large, pkgs))
                                pkgs.size == 1 -> result.add(DesktopItem.AppShortcut(pkgs[0]))
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                result.clear()
                seenPkgs.clear()
            }
        }

        // Sync any newly pinned apps (e.g., from "管理桌面固定应用" or Launchpad) into the layout
        for (pkg in pinned) {
            if (installedByPkg.containsKey(pkg) && seenPkgs.add(pkg)) {
                result.add(DesktopItem.AppShortcut(pkg))
            }
        }
        // Remove any apps that were explicitly unpinned from AppPrefs
        if (pinned.isNotEmpty() || AppPrefs.isDesktopPinnedInitialized(ctx)) {
            val pinnedSet = pinned.toHashSet()
            val cleaned = ArrayList<DesktopItem>()
            for (item in result) {
                when (item) {
                    is DesktopItem.AppShortcut -> if (pinnedSet.contains(item.pkg)) cleaned.add(item)
                    is DesktopItem.AppFolder -> {
                        val kept = item.pkgs.filter { pinnedSet.contains(it) }
                        when {
                            kept.size >= 2 -> cleaned.add(item.copy(pkgs = kept))
                            kept.size == 1 -> cleaned.add(DesktopItem.AppShortcut(kept[0]))
                        }
                    }
                }
            }
            return cleaned
        }
        return result
    }

    fun saveItems(ctx: Context, items: List<DesktopItem>) {
        val arr = JSONArray()
        val flatPkgs = ArrayList<String>()
        for (item in items) {
            when (item) {
                is DesktopItem.AppShortcut -> {
                    flatPkgs.add(item.pkg)
                    arr.put(JSONObject().put("t", "app").put("pkg", item.pkg))
                }
                is DesktopItem.AppFolder -> {
                    if (item.pkgs.isEmpty()) continue
                    val pArr = JSONArray()
                    for (p in item.pkgs) {
                        pArr.put(p)
                        flatPkgs.add(p)
                    }
                    arr.put(
                        JSONObject()
                            .put("t", "folder")
                            .put("id", item.id)
                            .put("name", item.name)
                            .put("large", item.large)
                            .put("pkgs", pArr),
                    )
                }
            }
        }
        AppPrefs.setDesktopLayoutJson(ctx, arr.toString(), notify = false)
        // Keep legacy pinned-app list in sync without triggering recursive reload
        val sp = ctx.applicationContext.getSharedPreferences(AppPrefs.FILE_EXT, Context.MODE_PRIVATE)
        sp.edit()
            .putString("desktop_pinned_apps", flatPkgs.distinct().joinToString(","))
            .putBoolean("desktop_pinned_initialized_v1", true)
            .apply()
    }
}

/**
 * Interactive macOS/HyperOS-style freeform desktop grid supporting:
 * - Animated drag-to-reorder with live spring reflow
 * - Drag-and-drop folder creation (hover app over app/folder to merge)
 * - 1×1 compact folder ↔ 2×2 large folder instant toggle (with direct 1-tap launch inside 2×2 folders)
 * - In-place frosted-glass expanded folder overlay with rename & remove-from-folder actions
 */
@SuppressLint("ClickableViewAccessibility")
internal class DesktopGridWorkspaceView(
    private val ctx: Context,
    private val menuPresenter: MacContextMenuPresenter,
    private val onLaunchApp: (LauncherAppEntry) -> Unit,
    private val onShowAppMenu: (LauncherAppEntry, View) -> Unit,
    private val onShowDesktopMenu: (Int, Int) -> Unit,
) : FrameLayout(ctx) {

    companion object {
        private const val LONG_PRESS_MS = 360L
        private const val REFLOW_ANIM_MS = 230L
        private const val MERGE_RATIO = 0.56f
        private const val DOUBLE_CLICK_MS = 420L
    }

    private data class PlacedCell(
        val item: DesktopItem,
        val col: Int,
        val row: Int,
        val rect: Rect,
    )

    private val scrollView: ScrollView
    private val canvasLayout: FrameLayout
    private val folderScrim: FrameLayout
    private val folderCard: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    private var installedByPkg: Map<String, LauncherAppEntry> = emptyMap()
    private var appModes: Map<String, Int> = emptyMap()
    private var items: MutableList<DesktopItem> = ArrayList()
    private val viewById = HashMap<String, View>()
    private var placedCells: List<PlacedCell> = emptyList()
    private var numColumns: Int = 5
    private var compactMode: Boolean = false
    private var lastMeasuredW: Int = 0

    // Active drag state
    private var draggingId: String? = null
    private var draggingView: View? = null
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragOrigViewX = 0f
    private var dragOrigViewY = 0f
    private var dragHoverMergeTargetId: String? = null
    private var lastSecondaryMs = 0L
    private var selectedItemId: String? = null
    private var lastClickItemId: String? = null
    private var lastClickMs = 0L

    // Active expanded folder state
    private var openFolderId: String? = null

    val isFolderOpen: Boolean get() = openFolderId != null

    init {
        clipChildren = false
        clipToPadding = false

        canvasLayout = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
            setOnLongClickListener {
                if (draggingId == null) {
                    onShowDesktopMenu(width / 2, height / 3)
                    true
                } else false
            }
            setOnTouchListener { _, ev ->
                if (ev?.actionMasked == MotionEvent.ACTION_DOWN && (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                    onShowDesktopMenu(ev.rawX.roundToInt(), ev.rawY.roundToInt())
                    true
                } else false
            }
        }
        scrollView = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_ALWAYS
            clipChildren = false
            clipToPadding = false
            addView(canvasLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        folderScrim = FrameLayout(ctx).apply {
            setBackgroundColor(0x40000000)
            visibility = View.GONE
            alpha = 0f
            isClickable = true
            setOnClickListener { closeFolder() }
        }
        folderCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dpF(24f)
            isClickable = true
            background = GradientDrawable().apply {
                setColor(0xF2F6F8FB.toInt())
                cornerRadius = dpF(28f)
                setStroke(1, 0xD9FFFFFF.toInt())
            }
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        folderScrim.addView(
            folderCard,
            LayoutParams(dp(420), LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        addView(folderScrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = r - l
            if (w > 0 && abs(w - lastMeasuredW) > 6) {
                lastMeasuredW = w
                post { layoutAllTiles(animate = false) }
            }
        }
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()
    private fun dpF(v: Float): Float = v * ctx.resources.displayMetrics.density

    fun setColumnsAndDensity(cols: Int, compact: Boolean) {
        val nextCols = cols.coerceIn(3, 7)
        if (numColumns != nextCols || compactMode != compact) {
            numColumns = nextCols
            compactMode = compact
            rebuildAllViews(animate = false)
        }
    }

    fun updateCatalog(allApps: List<LauncherAppEntry>, modes: Map<String, Int>) {
        installedByPkg = allApps.associateBy { it.pkg }
        appModes = modes
        items = DesktopLayoutStore.loadItems(ctx, installedByPkg)
        rebuildAllViews(animate = false)
        openFolderId?.let { fid ->
            val folder = items.filterIsInstance<DesktopItem.AppFolder>().firstOrNull { it.id == fid }
            if (folder != null) renderOpenFolderCard(folder) else closeFolder()
        }
    }

    fun updateModes(modes: Map<String, Int>) {
        appModes = modes
        rebuildAllViews(animate = false)
    }

    fun reloadFromPrefs() {
        if (installedByPkg.isEmpty()) return
        items = DesktopLayoutStore.loadItems(ctx, installedByPkg)
        rebuildAllViews(animate = true)
    }

    fun hasItems(): Boolean = items.isNotEmpty()

    fun isPointInTile(screenX: Int, screenY: Int): Boolean {
        if (visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        for (v in viewById.values) {
            if (v.visibility != View.VISIBLE) continue
            v.getLocationOnScreen(loc)
            if (screenX in loc[0]..(loc[0] + v.width) && screenY in loc[1]..(loc[1] + v.height)) {
                return true
            }
        }
        return false
    }

    fun closeFolder() {
        if (openFolderId == null) return
        openFolderId = null
        folderScrim.animate().cancel()
        folderCard.animate().cancel()
        folderScrim.animate().alpha(0f).setDuration(130L)
            .withEndAction { if (openFolderId == null) folderScrim.visibility = View.GONE }.start()
        folderCard.animate().scaleX(0.92f).scaleY(0.92f).alpha(0f).setDuration(120L).start()
    }

    private fun openFolder(folder: DesktopItem.AppFolder) {
        openFolderId = folder.id
        renderOpenFolderCard(folder)
        folderScrim.visibility = View.VISIBLE
        folderScrim.alpha = 0f
        folderCard.scaleX = 0.88f
        folderCard.scaleY = 0.88f
        folderCard.alpha = 0f
        folderScrim.animate().alpha(1f).setDuration(170L).setInterpolator(DecelerateInterpolator()).start()
        folderCard.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200L).setInterpolator(OvershootInterpolator(1.1f)).start()
    }

    private fun renderOpenFolderCard(folder: DesktopItem.AppFolder) {
        folderCard.removeAllViews()
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(14))
        }
        val titleBtn = TextView(ctx).apply {
            text = folder.name
            setTextColor(HyperUi.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f)
            paint.isFakeBoldText = true
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            setOnClickListener { showRenameFolderDialog(folder) }
        }
        header.addView(titleBtn, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val sizeToggle = TextView(ctx).apply {
            text = if (folder.large) "切为小文件夹" else "切为大文件夹"
            setTextColor(HyperUi.HYPER_BLUE)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            paint.isFakeBoldText = true
            setPadding(dp(10), dp(5), dp(10), dp(5))
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            background = GradientDrawable().apply {
                setColor(0x16007AFF)
                cornerRadius = dpF(12f)
            }
            setOnClickListener {
                closeFolder()
                toggleFolderSize(folder.id)
            }
        }
        header.addView(sizeToggle, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(8)
        })

        val closeBtn = TextView(ctx).apply {
            text = "×"
            setTextColor(HyperUi.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f)
            gravity = Gravity.CENTER
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            background = GradientDrawable().apply {
                setColor(0x10000000)
                cornerRadius = dpF(14f)
            }
            setOnClickListener { closeFolder() }
        }
        header.addView(closeBtn, LinearLayout.LayoutParams(dp(28), dp(28)))
        folderCard.addView(header)

        val entries = folder.pkgs.mapNotNull { installedByPkg[it] }
        val cols = 4
        var rowLayout: LinearLayout? = null
        entries.forEachIndexed { idx, entry ->
            if (idx % cols == 0) {
                rowLayout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START
                }
                folderCard.addView(
                    rowLayout,
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(10)
                    },
                )
            }
            val tile = buildFolderInnerAppTile(folder, entry)
            rowLayout?.addView(tile, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        val rem = entries.size % cols
        if (rem != 0 && rowLayout != null) {
            for (k in rem until cols) {
                rowLayout.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            }
        }
    }

    private fun buildFolderInnerAppTile(folder: DesktopItem.AppFolder, entry: LauncherAppEntry): LinearLayout {
        val iconSize = dp(48)
        val iconFrame = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(0xC8FFFFFF.toInt())
                cornerRadius = dpF(13f)
                setStroke(1, 0x16000000)
            }
            elevation = dpF(4f)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dpF(13f))
            }
            addView(
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(entry.icon)
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }
        val lbl = TextView(ctx).apply {
            text = entry.label
            setTextColor(HyperUi.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            addView(iconFrame, LinearLayout.LayoutParams(iconSize, iconSize).apply { bottomMargin = dp(5) })
            addView(lbl, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            setOnClickListener {
                closeFolder()
                onLaunchApp(entry)
            }
            setOnLongClickListener {
                showFolderInnerAppMenu(folder, entry, iconFrame)
                true
            }
            setOnGenericMotionListener { _, ev ->
                if (ev?.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && ev.actionButton == MotionEvent.BUTTON_SECONDARY) {
                    showFolderInnerAppMenu(folder, entry, iconFrame)
                    true
                } else false
            }
        }
    }

    private fun showFolderInnerAppMenu(folder: DesktopItem.AppFolder, entry: LauncherAppEntry, anchor: View) {
        menuPresenter.showContextMenu(
            header = MacMenuHeader(entry.label, "位于「${folder.name}」文件夹", entry.icon),
            entries = listOf(
                MacMenuEntry.Action("打开应用", iconKind = HyperUi.ICON_WINDOW) {
                    closeFolder()
                    onLaunchApp(entry)
                },
                MacMenuEntry.Action("移出文件夹到桌面", iconKind = HyperUi.ICON_SWAP) {
                    removeAppFromFolder(folder.id, entry.pkg)
                },
                MacMenuEntry.Divider,
                MacMenuEntry.Action("更多应用选项…", iconKind = HyperUi.ICON_SLIDERS) {
                    closeFolder()
                    onShowAppMenu(entry, anchor)
                },
            ),
            anchorView = anchor,
            widthDp = 240f,
            triggerHaptic = true,
        )
    }

    private fun showFolderContextMenu(folder: DesktopItem.AppFolder, anchor: View) {
        menuPresenter.showContextMenu(
            header = MacMenuHeader(folder.name, "${folder.pkgs.size} 个应用 · ${if (folder.large) "2×2 大文件夹" else "1×1 小文件夹"}"),
            entries = listOf(
                MacMenuEntry.Action(
                    label = if (folder.large) "切换为 1×1 小文件夹" else "切换为 2×2 大文件夹",
                    subtitle = if (folder.large) "节省桌面空间，点击展开查看" else "桌面直接点击前 3 个应用秒开",
                    iconKind = HyperUi.ICON_RATIO,
                    onClick = { toggleFolderSize(folder.id) },
                ),
                MacMenuEntry.Action("展开文件夹", iconKind = HyperUi.ICON_APPS) { openFolder(folder) },
                MacMenuEntry.Action("重命名文件夹", iconKind = HyperUi.ICON_MEMO) { showRenameFolderDialog(folder) },
                MacMenuEntry.Divider,
                MacMenuEntry.Action("解散文件夹", subtitle = "将全部应用释放回桌面", iconKind = HyperUi.ICON_CLOSE, destructive = true) {
                    dissolveFolder(folder.id)
                },
            ),
            anchorView = anchor,
            widthDp = 264f,
            triggerHaptic = true,
        )
    }

    private fun showRenameFolderDialog(folder: DesktopItem.AppFolder) {
        val input = EditText(ctx).apply {
            setText(folder.name)
            setSelection(folder.name.length)
            setTextColor(HyperUi.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(0x0E000000)
                cornerRadius = dpF(12f)
                setStroke(1, 0x22007AFF)
            }
        }
        menuPresenter.showCustomSheet(
            title = "重命名文件夹",
            subtitle = "输入新的文件夹名称",
            contentView = input,
            confirmLabel = "保存",
            onConfirm = {
                val nextName = input.text?.toString()?.trim().orEmpty().ifBlank { "文件夹" }
                val idx = items.indexOfFirst { it.id == folder.id }
                if (idx >= 0) {
                    val cur = items[idx] as? DesktopItem.AppFolder ?: return@showCustomSheet
                    val updated = cur.copy(name = nextName)
                    items[idx] = updated
                    DesktopLayoutStore.saveItems(ctx, items)
                    rebuildAllViews(animate = false)
                    if (openFolderId == folder.id) renderOpenFolderCard(updated)
                }
            },
        )
    }

    private fun toggleFolderSize(folderId: String) {
        val idx = items.indexOfFirst { it.id == folderId }
        if (idx < 0) return
        val folder = items[idx] as? DesktopItem.AppFolder ?: return
        items[idx] = folder.copy(large = !folder.large)
        DesktopLayoutStore.saveItems(ctx, items)
        rebuildAllViews(animate = true)
    }

    private fun dissolveFolder(folderId: String) {
        val idx = items.indexOfFirst { it.id == folderId }
        if (idx < 0) return
        val folder = items.removeAt(idx) as? DesktopItem.AppFolder ?: return
        val released = folder.pkgs.map { DesktopItem.AppShortcut(it) }
        items.addAll(idx, released)
        DesktopLayoutStore.saveItems(ctx, items)
        if (openFolderId == folderId) closeFolder()
        rebuildAllViews(animate = true)
    }

    private fun removeAppFromFolder(folderId: String, pkg: String) {
        val idx = items.indexOfFirst { it.id == folderId }
        if (idx < 0) return
        val folder = items[idx] as? DesktopItem.AppFolder ?: return
        val nextPkgs = folder.pkgs.filterNot { it == pkg }
        when {
            nextPkgs.size >= 2 -> {
                val updated = folder.copy(pkgs = nextPkgs)
                items[idx] = updated
                items.add(min(items.size, idx + 1), DesktopItem.AppShortcut(pkg))
                if (openFolderId == folderId) renderOpenFolderCard(updated)
            }
            nextPkgs.size == 1 -> {
                items[idx] = DesktopItem.AppShortcut(nextPkgs[0])
                items.add(min(items.size, idx + 1), DesktopItem.AppShortcut(pkg))
                if (openFolderId == folderId) closeFolder()
            }
            else -> {
                items.removeAt(idx)
                items.add(DesktopItem.AppShortcut(pkg))
                if (openFolderId == folderId) closeFolder()
            }
        }
        DesktopLayoutStore.saveItems(ctx, items)
        rebuildAllViews(animate = true)
    }

    // ---- Grid Packing & Animated Layout ----

    private fun computeCellMetrics(): Pair<Int, Int> {
        val padH = dp(if (compactMode) 6 else 10)
        val availW = max(dp(240), (if (width > 0) width else lastMeasuredW) - padH * 2)
        val gap = dp(if (compactMode) 8 else 14)
        val cellW = max(dp(68), (availW - gap * (numColumns - 1)) / numColumns)
        val cellH = dp(if (compactMode) 88 else 104)
        return cellW to cellH
    }

    private fun computePlacement(): Pair<List<PlacedCell>, Int> {
        val (cellW, cellH) = computeCellMetrics()
        val padH = dp(if (compactMode) 6 else 10)
        val padTop = dp(4)
        val gapX = dp(if (compactMode) 8 else 14)
        val gapY = dp(if (compactMode) 10 else 16)

        val occupied = HashSet<Long>()
        fun key(c: Int, r: Int) = (r.toLong() shl 16) or c.toLong()
        fun fits(c: Int, r: Int, sx: Int, sy: Int): Boolean {
            if (c + sx > numColumns) return false
            for (dr in 0 until sy) for (dc in 0 until sx) {
                if (occupied.contains(key(c + dc, r + dr))) return false
            }
            return true
        }

        val placed = ArrayList<PlacedCell>(items.size)
        var maxRowUsed = 0
        for (item in items) {
            val sx = min(item.spanX, numColumns)
            val sy = item.spanY
            var foundC = 0
            var foundR = 0
            outer@ for (r in 0..64) {
                for (c in 0 until numColumns) {
                    if (fits(c, r, sx, sy)) {
                        foundC = c; foundR = r
                        break@outer
                    }
                }
            }
            for (dr in 0 until sy) for (dc in 0 until sx) occupied.add(key(foundC + dc, foundR + dr))
            maxRowUsed = max(maxRowUsed, foundR + sy)
            val left = padH + foundC * (cellW + gapX)
            val top = padTop + foundR * (cellH + gapY)
            val w = sx * cellW + (sx - 1) * gapX
            val h = sy * cellH + (sy - 1) * gapY
            placed.add(PlacedCell(item, foundC, foundR, Rect(left, top, left + w, top + h)))
        }
        val totalH = padTop + max(1, maxRowUsed) * (cellH + gapY) + dp(if (compactMode) 64 else 96)
        return placed to totalH
    }

    private fun rebuildAllViews(animate: Boolean) {
        val activeIds = items.map { it.id }.toHashSet()
        val oldPositions = HashMap<String, Pair<Float, Float>>()
        viewById.forEach { (id, v) -> oldPositions[id] = v.x to v.y }

        canvasLayout.removeAllViews()
        viewById.clear()

        val (placed, totalH) = computePlacement()
        placedCells = placed
        canvasLayout.minimumHeight = totalH

        for (cell in placed) {
            val v = when (val item = cell.item) {
                is DesktopItem.AppShortcut -> buildAppTile(item)
                is DesktopItem.AppFolder -> if (item.large) buildLargeFolderTile(item) else buildSmallFolderTile(item)
            }
            viewById[cell.item.id] = v
            val lp = LayoutParams(cell.rect.width(), cell.rect.height())
            canvasLayout.addView(v, lp)
            val prev = oldPositions[cell.item.id]
            if (animate && prev != null && (abs(prev.first - cell.rect.left) > 2f || abs(prev.second - cell.rect.top) > 2f)) {
                v.x = prev.first
                v.y = prev.second
                v.animate().x(cell.rect.left.toFloat()).y(cell.rect.top.toFloat())
                    .setDuration(REFLOW_ANIM_MS).setInterpolator(DecelerateInterpolator(1.7f)).start()
            } else {
                v.x = cell.rect.left.toFloat()
                v.y = cell.rect.top.toFloat()
            }
        }
    }

    private fun layoutAllTiles(animate: Boolean) {
        val (placed, totalH) = computePlacement()
        placedCells = placed
        canvasLayout.minimumHeight = totalH
        for (cell in placed) {
            val v = viewById[cell.item.id] ?: continue
            if (cell.item.id == draggingId) continue
            val lp = v.layoutParams
            if (lp.width != cell.rect.width() || lp.height != cell.rect.height()) {
                lp.width = cell.rect.width()
                lp.height = cell.rect.height()
                v.layoutParams = lp
            }
            if (animate) {
                v.animate().cancel()
                v.animate().x(cell.rect.left.toFloat()).y(cell.rect.top.toFloat())
                    .setDuration(REFLOW_ANIM_MS).setInterpolator(DecelerateInterpolator(1.7f)).start()
            } else {
                v.x = cell.rect.left.toFloat()
                v.y = cell.rect.top.toFloat()
            }
        }
    }

    // ---- Tile Builders (1×1 App, 1×1 Small Folder, 2×2 Large Folder) ----

    private fun buildAppTile(item: DesktopItem.AppShortcut): View {
        val entry = installedByPkg[item.pkg]
        val iconPx = dp(if (compactMode) 44 else 56)
        val cornerPx = dpF(if (compactMode) 11.5f else 14.5f)
        val iconFrame = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(0x2EFFFFFF)
                cornerRadius = cornerPx
                setStroke(1, 0x48FFFFFF)
            }
            elevation = dpF(6f)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, cornerPx)
            }
            addView(
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    if (entry != null) setImageDrawable(entry.icon)
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }
        val label = TextView(ctx).apply {
            text = entry?.label ?: item.pkg
            setTextColor(0xFFF8FAFC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (compactMode) 11f else 12f)
            setShadowLayer(dpF(4f), 0f, dpF(1.3f), 0xD8000000.toInt())
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(4))
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            addView(iconFrame, LinearLayout.LayoutParams(iconPx, iconPx).apply { bottomMargin = dp(6) })
            addView(label, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val mode = appModes[item.pkg] ?: AppMode.DEFAULT
        if (mode > AppMode.DEFAULT) {
            root.addView(
                TextView(ctx).apply {
                    text = BridgeLabels.appModeBadge(mode)
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 8.5f)
                    setPadding(dp(5), dp(1), dp(5), dp(1))
                    background = GradientDrawable().apply {
                        cornerRadius = dpF(7f)
                        setColor(if (mode == AppMode.LAND_FULL) 0xCC007AFF.toInt() else 0xCCFF9500.toInt())
                    }
                },
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) },
            )
        }
        attachTileInteractions(
            tileView = root,
            hoverLiftTarget = iconFrame,
            item = item,
            onClick = { entry?.let(onLaunchApp) },
            onSecondary = { entry?.let { onShowAppMenu(it, iconFrame) } },
        )
        return root
    }

    private fun buildSmallFolderTile(folder: DesktopItem.AppFolder): View {
        val boxPx = dp(if (compactMode) 46 else 58)
        val cornerPx = dpF(if (compactMode) 13f else 16f)
        val miniGrid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply {
                setColor(0xD8F5F7FA.toInt())
                cornerRadius = cornerPx
                setStroke(1, 0xCCFFFFFF.toInt())
            }
            elevation = dpF(7f)
        }
        val previews = folder.pkgs.take(9).mapNotNull { installedByPkg[it] }
        val miniSz = dp(if (compactMode) 10 else 13)
        for (r in 0 until 3) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (c in 0 until 3) {
                val idx = r * 3 + c
                val img = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    previews.getOrNull(idx)?.icon?.let { setImageDrawable(it) }
                }
                row.addView(img, LinearLayout.LayoutParams(miniSz, miniSz).apply {
                    val m = dp(1)
                    setMargins(m, m, m, m)
                })
            }
            miniGrid.addView(row)
        }
        val label = TextView(ctx).apply {
            text = folder.name
            setTextColor(0xFFF8FAFC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (compactMode) 11f else 12f)
            setShadowLayer(dpF(4f), 0f, dpF(1.3f), 0xD8000000.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(4))
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            addView(miniGrid, LinearLayout.LayoutParams(boxPx, boxPx).apply { bottomMargin = dp(6) })
            addView(label, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        attachTileInteractions(
            tileView = root,
            hoverLiftTarget = miniGrid,
            item = folder,
            onClick = { openFolder(folder) },
            onSecondary = { showFolderContextMenu(folder, miniGrid) },
        )
        return root
    }

    private fun buildLargeFolderTile(folder: DesktopItem.AppFolder): View {
        val entries = folder.pkgs.mapNotNull { installedByPkg[it] }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xCCF5F7FA.toInt())
                cornerRadius = dpF(24f)
                setStroke(1, 0xD9FFFFFF.toInt())
            }
            elevation = dpF(10f)
        }

        val gridBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val iconPx = dp(if (compactMode) 44 else 52)
        val iconCorner = dpF(13f)

        fun makeDirectAppSlot(entry: LauncherAppEntry?): View {
            val frame = FrameLayout(ctx).apply {
                pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
                background = GradientDrawable().apply {
                    setColor(0xD8FFFFFF.toInt())
                    cornerRadius = iconCorner
                    setStroke(1, 0x16000000)
                }
                elevation = dpF(3f)
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, iconCorner)
                }
                if (entry != null) {
                    addView(
                        ImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setImageDrawable(entry.icon)
                        },
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
                    )
                    setOnClickListener { onLaunchApp(entry) }
                }
            }
            return frame
        }

        fun makeOverflowMiniGrid(): View {
            val miniWrap = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
                pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
                background = GradientDrawable().apply {
                    setColor(0x14000000)
                    cornerRadius = iconCorner
                }
                setOnClickListener { openFolder(folder) }
            }
            val extra = entries.drop(3).take(4)
            val mSz = dp(18)
            for (r in 0 until 2) {
                val rr = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                for (c in 0 until 2) {
                    val img = ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        extra.getOrNull(r * 2 + c)?.icon?.let { setImageDrawable(it) }
                    }
                    rr.addView(img, LinearLayout.LayoutParams(mSz, mSz).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
                }
                miniWrap.addView(rr)
            }
            return miniWrap
        }

        val row0 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(makeDirectAppSlot(entries.getOrNull(0)), LinearLayout.LayoutParams(iconPx, iconPx).apply { rightMargin = dp(10) })
            addView(makeDirectAppSlot(entries.getOrNull(1)), LinearLayout.LayoutParams(iconPx, iconPx))
        }
        val fourthView = if (entries.size > 4) makeOverflowMiniGrid() else makeDirectAppSlot(entries.getOrNull(3))
        val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(makeDirectAppSlot(entries.getOrNull(2)), LinearLayout.LayoutParams(iconPx, iconPx).apply { rightMargin = dp(10) })
            addView(fourthView, LinearLayout.LayoutParams(iconPx, iconPx))
        }
        gridBox.addView(row0, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        gridBox.addView(row1, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        card.addView(gridBox, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val label = TextView(ctx).apply {
            text = folder.name
            setTextColor(HyperUi.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(label, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val wrapper = FrameLayout(ctx).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        attachTileInteractions(
            tileView = wrapper,
            hoverLiftTarget = card,
            item = folder,
            onClick = { openFolder(folder) },
            onSecondary = { showFolderContextMenu(folder, card) },
        )
        return wrapper
    }

    // ---- Unified Mouse + Touch Drag, Live Reflow & Merge Interaction ----

    private fun attachTileInteractions(
        tileView: View,
        hoverLiftTarget: View,
        item: DesktopItem,
        onClick: () -> Unit,
        onSecondary: () -> Unit,
    ) {
        val touchSlop = dp(10)
        var downRawX = 0f
        var downRawY = 0f
        var isMouse = false
        var dragging = false
        var longPressFired = false
        val longPressRunnable = Runnable {
            if (!dragging) {
                longPressFired = true
                tileView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onSecondary()
            }
        }

        fun fireSecondaryOnce() {
            val now = SystemClock.uptimeMillis()
            if (now - lastSecondaryMs < 220L) return
            lastSecondaryMs = now
            onSecondary()
        }

        tileView.setOnHoverListener { _, ev ->
            if (draggingId != null) return@setOnHoverListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> hoverLiftTarget.animate().scaleX(1.06f).scaleY(1.06f).setDuration(110L).setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_HOVER_EXIT -> hoverLiftTarget.animate().scaleX(1f).scaleY(1f).setDuration(120L).setInterpolator(DecelerateInterpolator()).start()
            }
            false
        }

        tileView.setOnGenericMotionListener { _, ev ->
            if (ev?.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && ev.actionButton == MotionEvent.BUTTON_SECONDARY) {
                fireSecondaryOnce()
                true
            } else false
        }

        tileView.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if ((ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                        fireSecondaryOnce()
                        return@setOnTouchListener true
                    }
                    downRawX = ev.rawX; downRawY = ev.rawY
                    isMouse = ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
                    dragging = false; longPressFired = false
                    v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(75L).start()
                    if (!isMouse) handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        handler.removeCallbacks(longPressRunnable)
                        if (longPressFired) menuPresenter.dismiss(animated = false)
                        dragging = true
                        beginDrag(item, v, downRawX, downRawY)
                    }
                    if (dragging) {
                        scrollView.requestDisallowInterceptTouchEvent(true)
                        updateDrag(item, v, ev.rawX, ev.rawY)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    scrollView.requestDisallowInterceptTouchEvent(false)
                    if (dragging) {
                        endDrag(item, v, commit = ev.actionMasked == MotionEvent.ACTION_UP)
                    } else {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(150L).setInterpolator(OvershootInterpolator(1.3f)).start()
                        if (!longPressFired && ev.actionMasked == MotionEvent.ACTION_UP) {
                            if (isMouse && AppPrefs.isDesktopDoubleClickLaunch(ctx)) {
                                val now = SystemClock.uptimeMillis()
                                if (lastClickItemId == item.id && now - lastClickMs <= DOUBLE_CLICK_MS) {
                                    lastClickItemId = null
                                    lastClickMs = 0L
                                    setSelectedTile(null)
                                    onClick()
                                } else {
                                    lastClickItemId = item.id
                                    lastClickMs = now
                                    setSelectedTile(item.id)
                                }
                            } else {
                                setSelectedTile(null)
                                onClick()
                            }
                        }
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun beginDrag(item: DesktopItem, view: View, rawX: Float, rawY: Float) {
        draggingId = item.id
        draggingView = view
        dragStartRawX = rawX
        dragStartRawY = rawY
        dragOrigViewX = view.x
        dragOrigViewY = view.y
        dragHoverMergeTargetId = null
        view.bringToFront()
        view.animate().cancel()
        view.animate().scaleX(1.08f).scaleY(1.08f).alpha(0.90f).setDuration(110L).start()
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun updateDrag(item: DesktopItem, view: View, rawX: Float, rawY: Float) {
        val nx = dragOrigViewX + (rawX - dragStartRawX)
        val ny = dragOrigViewY + (rawY - dragStartRawY)
        view.x = nx
        view.y = ny

        val cx = (nx + view.width * 0.5f).roundToInt()
        val cy = (ny + view.height * 0.5f).roundToInt()

        // Check center hit on another item (merge into folder if dragging a single app)
        var mergeTarget: PlacedCell? = null
        var swapTargetIdx = -1
        for (idx in placedCells.indices) {
            val cell = placedCells[idx]
            if (cell.item.id == item.id) continue
            if (cell.rect.contains(cx, cy)) {
                val insetX = (cell.rect.width() * (1f - MERGE_RATIO) * 0.5f).roundToInt()
                val insetY = (cell.rect.height() * (1f - MERGE_RATIO) * 0.5f).roundToInt()
                val core = Rect(cell.rect.left + insetX, cell.rect.top + insetY, cell.rect.right - insetX, cell.rect.bottom - insetY)
                if (item is DesktopItem.AppShortcut && core.contains(cx, cy)) {
                    mergeTarget = cell
                } else {
                    swapTargetIdx = idx
                }
                break
            }
        }

        setMergeHighlight(mergeTarget?.item?.id)
        if (mergeTarget == null && swapTargetIdx >= 0) {
            val fromIdx = items.indexOfFirst { it.id == item.id }
            if (fromIdx >= 0 && fromIdx != swapTargetIdx) {
                val moved = items.removeAt(fromIdx)
                items.add(swapTargetIdx.coerceIn(0, items.size), moved)
                layoutAllTiles(animate = true)
            }
        }
    }

    private fun setSelectedTile(id: String?) {
        if (selectedItemId == id) return
        selectedItemId?.let { prev -> viewById[prev]?.background = null }
        selectedItemId = id
        id?.let { next ->
            viewById[next]?.background = GradientDrawable().apply {
                setColor(0x2EFFFFFF)
                cornerRadius = dpF(16f)
                setStroke(1, 0x66FFFFFF)
            }
        }
    }

    private fun setMergeHighlight(targetId: String?) {
        if (dragHoverMergeTargetId == targetId) return
        dragHoverMergeTargetId?.let { prev ->
            viewById[prev]?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(110L)?.start()
        }
        dragHoverMergeTargetId = targetId
        targetId?.let { next ->
            viewById[next]?.let { tv ->
                tv.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                tv.animate().scaleX(1.12f).scaleY(1.12f).setDuration(110L).start()
            }
        }
    }

    private fun endDrag(item: DesktopItem, view: View, commit: Boolean) {
        val mergeId = dragHoverMergeTargetId
        setMergeHighlight(null)
        draggingId = null
        draggingView = null
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150L).start()

        if (commit && mergeId != null && item is DesktopItem.AppShortcut) {
            val srcIdx = items.indexOfFirst { it.id == item.id }
            val dstIdx = items.indexOfFirst { it.id == mergeId }
            if (srcIdx >= 0 && dstIdx >= 0 && srcIdx != dstIdx) {
                val dst = items[dstIdx]
                val merged: DesktopItem.AppFolder? = when (dst) {
                    is DesktopItem.AppShortcut -> DesktopItem.AppFolder(
                        id = "f_${SystemClock.uptimeMillis()}",
                        name = "常用应用",
                        large = false,
                        pkgs = listOf(dst.pkg, item.pkg),
                    )
                    is DesktopItem.AppFolder -> if (!dst.pkgs.contains(item.pkg)) dst.copy(pkgs = dst.pkgs + item.pkg) else dst
                }
                if (merged != null) {
                    items[dstIdx] = merged
                    items.removeAt(srcIdx)
                    DesktopLayoutStore.saveItems(ctx, items)
                    rebuildAllViews(animate = true)
                    return
                }
            }
        }
        if (commit) DesktopLayoutStore.saveItems(ctx, items)
        layoutAllTiles(animate = true)
    }
}

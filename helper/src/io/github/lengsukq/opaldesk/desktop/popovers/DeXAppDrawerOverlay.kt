package io.github.lengsukq.opaldesk.desktop.popovers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lengsukq.opaldesk.HyperUi
import io.github.lengsukq.opaldesk.LauncherActivity
import io.github.lengsukq.opaldesk.desktop.model.GlassOverlayTokens
import io.github.lengsukq.opaldesk.desktop.model.SnapZone
import io.github.lengsukq.opaldesk.desktop.state.DesktopShellHost
import io.github.lengsukq.opaldesk.launcher.LauncherAppEntry
import io.github.lengsukq.opaldesk.launcher.LauncherAppRepository
import io.github.lengsukq.opaldesk.overlay.MATCH
import io.github.lengsukq.opaldesk.overlay.OverlayLayer
import io.github.lengsukq.opaldesk.overlay.OverlayPopover
import io.github.lengsukq.opaldesk.overlay.OverlaySurfaceHost
import io.github.lengsukq.opaldesk.overlay.OverlayUiUtils
import io.github.lengsukq.opaldesk.overlay.OverlayWindowParams
import io.github.lengsukq.opaldesk.overlay.WRAP
import io.github.lengsukq.opaldesk.overlay.animatePopover
import io.github.lengsukq.opaldesk.overlay.dp
import io.github.lengsukq.opaldesk.overlay.dpF
import io.github.lengsukq.opaldesk.overlay.llp
import io.github.lengsukq.opaldesk.overlay.resetPopoverVisuals
import io.github.lengsukq.opaldesk.overlay.roundedBg
import io.github.lengsukq.opaldesk.overlay.setPadDp
import io.github.lengsukq.opaldesk.overlay.toast
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * True floating Samsung DeX Liquid Glass App Drawer (`OverlayLayer.POPOVER`).
 *
 * Floats directly ABOVE active desktop windows without calling `relaunchLauncher()`, so
 * opening All Apps or searching NEVER hides or disrupts your open multi-window workspace.
 * Dynamically acquires keyboard focus when open for instant type-to-search & `Esc` dismiss.
 */
@SuppressLint("ClickableViewAccessibility")
internal class DeXAppDrawerOverlay(private val host: DesktopShellHost) : OverlayPopover {

    companion object {
        private const val POPOVER_ID = "dex_app_drawer"
    }

    override val id: String = POPOVER_ID
    override var isOpen: Boolean = false
        private set

    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var titleView: TextView? = null
    private var hintView: TextView? = null
    private var searchRow: LinearLayout? = null
    private var searchIcon: ImageView? = null
    private var searchInput: EditText? = null
    private var btnClear: TextView? = null
    private var emptyView: TextView? = null
    private var gridView: GridView? = null
    private var adapter: DrawerAppsAdapter? = null

    private var allApps: List<LauncherAppEntry> = emptyList()
    private var appsLoaded = false
    private var query = ""

    fun build(ctx: Context) {
        detach()
        val panel = object : LinearLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                if (event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (query.isNotEmpty()) searchInput?.setText("") else dismiss()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadDp(20f, 18f, 20f, 16f)
            elevation = ctx.dpF(28f)
            visibility = View.GONE
            setOnTouchListener { _, ev ->
                if (ev != null && ev.actionMasked == MotionEvent.ACTION_OUTSIDE && isOpen) dismiss()
                false
            }
        }

        // Header
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView = TextView(ctx).apply {
            text = "所有应用"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f)
            paint.isFakeBoldText = true
        }.also { header.addView(it, ctx.llp(0, WRAP, 1f)) }

        hintView = TextView(ctx).apply {
            text = "右键图标可分屏或固定 · Esc 关闭"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
        }.also { header.addView(it, ctx.llp(rightDp = 10f)) }

        panel.addView(header, ctx.llp(MATCH, WRAP, bottomDp = 12f))

        // Search box
        searchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadDp(12f, 4f, 10f, 4f)
            searchIcon = ImageView(ctx).also { addView(it, ctx.llp(ctx.dp(16f), ctx.dp(16f), rightDp = 8f)) }
            searchInput = EditText(ctx).apply {
                hint = "搜索应用名或包名（回车立即打开首项）…"
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                setSingleLine(true)
                setBackgroundColor(Color.TRANSPARENT)
                setPadDp(2f, 6f, 4f, 6f)
                imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
                })
                setOnEditorActionListener { _, actionId, ev ->
                    val go = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH ||
                        (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_UP)
                    if (go) {
                        adapter?.itemAt(0)?.let { entry ->
                            dismiss()
                            host.launchApp(entry.pkg, entry.cls)
                        }
                        true
                    } else false
                }
            }.also { addView(it, ctx.llp(0, WRAP, 1f)) }
            btnClear = TextView(ctx).apply {
                text = "×"
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
                gravity = Gravity.CENTER
                setPadDp(8f, 2f, 6f, 2f)
                visibility = View.GONE
                setOnClickListener { searchInput?.setText("") }
            }.also(::addView)
        }.also { panel.addView(it, ctx.llp(MATCH, WRAP, bottomDp = 12f)) }

        // App grid
        val stage = FrameLayout(ctx)
        emptyView = TextView(ctx).apply {
            text = "未找到匹配的应用"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }.also { stage.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }

        val appAdapter = DrawerAppsAdapter(ctx)
        adapter = appAdapter
        gridView = GridView(ctx).apply {
            numColumns = 6
            verticalSpacing = ctx.dp(14f)
            horizontalSpacing = ctx.dp(10f)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false
            setPadDp(2f, 6f, 2f, 12f)
            this.adapter = appAdapter
        }.also { stage.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }

        panel.addView(stage, ctx.llp(MATCH, 0, 1f))

        val wmLp = OverlayWindowParams.popover(ctx.dp(740f), ctx.dp(500f), host.winType, ctx = ctx, wm = host.extWm)
        root = panel; lp = wmLp
        applyTheme(ctx, host.tokens)
        host.popovers.register(this)
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(POPOVER_ID, OverlayLayer.POPOVER, panel, wmLp))
    }

    fun toggle(focusSearch: Boolean = false) {
        if (isOpen) dismiss() else open(focusSearch)
    }

    fun open(focusSearch: Boolean = false) {
        val ctx = host.extCtx ?: return
        val panel = root ?: return
        val wmLp = lp ?: return

        host.popovers.dismissAllExcept(POPOVER_ID)
        isOpen = true
        applyTheme(ctx, host.tokens)

        val dispW = max(600, host.state.displayW)
        val dispH = max(480, host.state.displayH)
        val w = (dispW * 0.56f).toInt().coerceIn(ctx.dp(420f), min(dispW - ctx.dp(24f), ctx.dp(780f)))
        val h = (dispH * 0.64f).toInt().coerceIn(ctx.dp(340f), min(dispH - ctx.dp(92f), ctx.dp(560f)))
        val margin = ctx.dp(14f)
        val bottomPad = ctx.dp(74f)

        wmLp.width = w
        wmLp.height = h
        wmLp.x = margin
        wmLp.y = max(margin, dispH - bottomPad - h)
        // Enable focus while open so physical & remote keyboards type directly into search
        wmLp.flags = wmLp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()

        val gridDp = (w - ctx.dp(44f)) / max(0.5f, ctx.resources.displayMetrics.density)
        gridView?.numColumns = (gridDp / 108f).toInt().coerceIn(4, 7)

        if (!appsLoaded) loadAppsAsync() else applyFilter(query)

        panel.visibility = View.VISIBLE
        host.surfaceHost.updateWindowLayout(panel, wmLp, host.extWm)
        panel.animatePopover(show = true, shiftYDp = 16f, startScale = 0.94f, enterMs = 175L) {
            host.bringCursorToFront()
            if (focusSearch) searchInput?.requestFocus()
        }
    }

    override fun dismiss() {
        val panel = root
        val wmLp = lp
        isOpen = false
        if (wmLp != null) {
            wmLp.flags = wmLp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        searchInput?.let {
            if (it.hasFocus()) it.clearFocus()
            if (query.isNotEmpty()) it.setText("")
        }
        if (panel != null && panel.visibility == View.VISIBLE) {
            if (wmLp != null) host.surfaceHost.updateWindowLayout(panel, wmLp, host.extWm)
            panel.animatePopover(show = false, shiftYDp = 12f, startScale = 0.95f, exitMs = 125L) {
                if (!isOpen) {
                    panel.visibility = View.GONE
                    panel.resetPopoverVisuals()
                }
            }
        } else {
            panel?.let { it.animate().cancel(); it.visibility = View.GONE }
        }
    }

    fun invalidateCatalog() {
        appsLoaded = false
    }

    private fun loadAppsAsync() {
        host.safeBgExecute {
            val loaded = LauncherAppRepository.loadInstalledApps(host.serviceContext, LauncherActivity::class.java.name)
            host.safePost {
                allApps = loaded
                appsLoaded = true
                applyFilter(query)
            }
        }
    }

    private fun applyFilter(raw: String) {
        query = raw.trim().lowercase(Locale.ROOT)
        btnClear?.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
        val filtered = LauncherAppRepository.filterApps(allApps, query)
        adapter?.submit(filtered, host.tokens)
        val empty = filtered.isEmpty()
        emptyView?.visibility = if (empty) View.VISIBLE else View.GONE
        gridView?.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun applyTheme(ctx: Context, tokens: GlassOverlayTokens) {
        root?.let { tokens.applyGlassBackground(it, tokens.panelFill, cornerRadiusDp = 28f, strokeWidthDp = 1.25f) }
        titleView?.setTextColor(tokens.textPrimary)
        hintView?.setTextColor(tokens.textSecondary)
        searchRow?.background = roundedBg(tokens.cardInnerFill, ctx.dpF(16f), ctx.dp(1f), tokens.divider)
        searchIcon?.setImageDrawable(HyperUi.icon(ctx, HyperUi.ICON_SEARCH, tokens.textSecondary, 15f))
        searchInput?.setTextColor(tokens.textPrimary)
        searchInput?.setHintTextColor(tokens.textTertiary)
        btnClear?.setTextColor(tokens.textSecondary)
        emptyView?.setTextColor(tokens.textSecondary)
        adapter?.updateTheme(tokens)
    }

    private fun showAppItemContextMenu(entry: LauncherAppEntry, anchor: View) {
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val pinnedTaskbar = AppPrefs.isDockAppPinned(host.serviceContext, entry.pkg)
        val pinnedDesktop = AppPrefs.getDesktopPinnedApps(host.serviceContext).contains(entry.pkg)
        val rows = listOf(
            GlassMenuRow.ChipPair(
                listOf(
                    GlassMenuRow.Action("左半屏打开", HyperUi.ICON_CHEVRON_LEFT) { dismiss(); host.launchApp(entry.pkg, entry.cls, SnapZone.LEFT_HALF) },
                    GlassMenuRow.Action("右半屏打开", HyperUi.ICON_CHEVRON_RIGHT) { dismiss(); host.launchApp(entry.pkg, entry.cls, SnapZone.RIGHT_HALF) },
                ),
            ),
            GlassMenuRow.Action("小窗打开", HyperUi.ICON_WINDOW) { dismiss(); host.launchApp(entry.pkg, entry.cls, SnapZone.NONE) },
            GlassMenuRow.Action("最大化打开", HyperUi.ICON_MAXIMIZE) { dismiss(); host.launchApp(entry.pkg, entry.cls, SnapZone.MAXIMIZE) },
            GlassMenuRow.Divider,
            GlassMenuRow.Action(
                if (pinnedTaskbar) "从任务栏取消固定" else "固定到任务栏",
                HyperUi.ICON_PIN,
            ) {
                val nowPinned = AppPrefs.toggleDockPinnedApp(host.serviceContext, entry.pkg)
                host.serviceContext.toast(if (nowPinned) "已固定到任务栏：${entry.label}" else "已取消固定：${entry.label}")
                host.updateTaskbarPinnedApps()
            },
            GlassMenuRow.Action(
                if (pinnedDesktop) "从桌面移除快捷方式" else "添加到桌面快捷方式",
                HyperUi.ICON_DEX,
            ) {
                val cur = AppPrefs.getDesktopPinnedApps(host.serviceContext).toMutableList()
                val added = if (cur.contains(entry.pkg)) { cur.remove(entry.pkg); false } else { cur.add(entry.pkg); true }
                AppPrefs.setDesktopPinnedApps(host.serviceContext, cur, markInitialized = true)
                host.serviceContext.toast(if (added) "已添加到桌面：${entry.label}" else "已从桌面移除：${entry.label}")
            },
        )
        // Open unified glass menu anchored at the clicked tile
        host.popovers.dismissAllExcept(POPOVER_ID)
        onOpenAppMenu?.invoke(entry, rows, loc[0] + anchor.width / 2, loc[1] + anchor.height)
    }

    var onOpenAppMenu: ((LauncherAppEntry, List<GlassMenuRow>, Int, Int) -> Unit)? = null

    fun detach() {
        isOpen = false
        host.popovers.unregister(POPOVER_ID)
        host.surfaceHost.unregister(POPOVER_ID, host.extWm)
        root = null; lp = null; titleView = null; hintView = null
        searchRow = null; searchIcon = null; searchInput = null; btnClear = null
        emptyView = null; gridView = null; adapter = null
    }

    private inner class DrawerAppsAdapter(private val ctx: Context) : BaseAdapter() {
        private var items: List<LauncherAppEntry> = emptyList()
        private var tokens: GlassOverlayTokens = host.tokens

        fun submit(list: List<LauncherAppEntry>, t: GlassOverlayTokens) {
            items = list
            tokens = t
            notifyDataSetChanged()
        }

        fun updateTheme(t: GlassOverlayTokens) {
            tokens = t
            notifyDataSetChanged()
        }

        fun itemAt(pos: Int): LauncherAppEntry? = items.getOrNull(pos)
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val entry = items[position]
            val root = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadDp(6f, 8f, 6f, 8f)
                pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
                addView(
                    ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                    ctx.llp(ctx.dp(46f), ctx.dp(46f), bottomDp = 6f),
                )
                addView(
                    TextView(ctx).apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
                        gravity = Gravity.CENTER
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    ctx.llp(MATCH, WRAP),
                )
            }
            root.background = OverlayUiUtils.makeStateListBg(ctx, Color.TRANSPARENT, tokens.hoverChipFill, 16f)
            val iv = root.getChildAt(0) as ImageView
            val tv = root.getChildAt(1) as TextView
            iv.setImageDrawable(entry.icon)
            tv.text = entry.label
            tv.setTextColor(tokens.textPrimary)

            root.setOnClickListener {
                dismiss()
                host.launchApp(entry.pkg, entry.cls)
            }
            OverlayUiUtils.attachRightClickListener(root, includeLongClick = true, pressedScale = 0.92f) {
                showAppItemContextMenu(entry, root)
            }
            return root
        }
    }
}

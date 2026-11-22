package io.github.lengsukq.opaldesk.desktop.workbench

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
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lengsukq.opaldesk.HyperUi
import io.github.lengsukq.opaldesk.LauncherActivity
import io.github.lengsukq.opaldesk.desktop.model.GlassOverlayTokens
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
 * Searchable Liquid Glass multi-select app picker popover for adding/removing apps
 * directly into the DeX Workbench Rail without leaving the desktop.
 */
@SuppressLint("ClickableViewAccessibility")
internal class DeXWorkbenchPickerOverlay(private val host: DesktopShellHost) : OverlayPopover {

    companion object {
        private const val POPOVER_ID = "dex_workbench_picker"
    }

    override val id: String = POPOVER_ID
    override var isOpen: Boolean = false
        private set

    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var titleView: TextView? = null
    private var countView: TextView? = null
    private var btnDone: TextView? = null
    private var searchRow: LinearLayout? = null
    private var searchIcon: ImageView? = null
    private var searchInput: EditText? = null
    private var btnClear: TextView? = null
    private var emptyView: TextView? = null
    private var gridView: GridView? = null
    private var adapter: PickerAppsAdapter? = null

    private var allApps: List<LauncherAppEntry> = emptyList()
    private var appsLoaded = false
    private var query = ""
    private val selectedPkgs = LinkedHashSet<String>()

    fun build(ctx: Context) {
        detach()
        val panel = object : LinearLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                if (event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    dismiss()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadDp(18f, 16f, 18f, 14f)
            elevation = ctx.dpF(26f)
            setOnTouchListener { _, ev ->
                if (ev?.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    dismiss()
                    return@setOnTouchListener true
                }
                false
            }
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titleView = TextView(ctx).apply {
            text = "管理工作台应用"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f)
            paint.isFakeBoldText = true
        }.also { titleCol.addView(it) }
        countView = TextView(ctx).apply {
            text = "点击图标勾选或移出工作台卡片栏（最多 ${AppPrefs.MAX_WORKBENCH_APPS} 个）"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
        }.also { titleCol.addView(it, ctx.llp(WRAP, WRAP, topDp = 2f)) }
        header.addView(titleCol, ctx.llp(0, WRAP, 1f))

        btnDone = TextView(ctx).apply {
            text = "完成"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadDp(14f, 7f)
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            OverlayUiUtils.attachTactilePress(this, 0.94f)
            setOnClickListener { dismiss() }
        }.also { header.addView(it, ctx.llp(WRAP, WRAP, leftDp = 10f)) }
        panel.addView(header, ctx.llp(MATCH, WRAP, bottomDp = 10f))

        val sRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadDp(12f, 4f, 10f, 4f)
        }
        searchIcon = ImageView(ctx).also {
            sRow.addView(it, ctx.llp(ctx.dp(15f), ctx.dp(15f), rightDp = 8f))
        }
        searchInput = EditText(ctx).apply {
            hint = "搜索应用名或包名…"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setSingleLine(true)
            setBackgroundColor(Color.TRANSPARENT)
            setPadDp(2f, 6f, 6f, 6f)
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
            })
        }.also { sRow.addView(it, ctx.llp(0, WRAP, 1f)) }

        btnClear = TextView(ctx).apply {
            text = "×"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            gravity = Gravity.CENTER
            setPadDp(6f, 2f, 4f, 2f)
            visibility = View.GONE
            setOnClickListener { searchInput?.setText("") }
        }.also { sRow.addView(it) }
        searchRow = sRow
        panel.addView(sRow, ctx.llp(MATCH, WRAP, bottomDp = 12f))

        emptyView = TextView(ctx).apply {
            text = "未找到匹配的应用"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            gravity = Gravity.CENTER
            setPadDp(16f, 36f)
            visibility = View.GONE
        }.also { panel.addView(it, ctx.llp(MATCH, WRAP)) }

        val adp = PickerAppsAdapter(ctx)
        adapter = adp
        gridView = GridView(ctx).apply {
            numColumns = 5
            horizontalSpacing = ctx.dp(8f)
            verticalSpacing = ctx.dp(10f)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false
            setPadDp(2f, 2f, 2f, 8f)
            this.adapter = adp
            setOnItemClickListener { _, _, position, _ ->
                adp.itemAt(position)?.let { toggleAppSelection(it) }
            }
        }.also { panel.addView(it, ctx.llp(MATCH, 0, 1f)) }

        val wmLp = OverlayWindowParams.popover(ctx.dp(560f), ctx.dp(440f), host.winType, ctx = ctx, wm = host.extWm)
        root = panel
        lp = wmLp
        applyTheme(ctx, host.tokens)
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(POPOVER_ID, OverlayLayer.POPOVER, panel, wmLp))
        host.popovers.register(this)
    }

    fun open() {
        val ctx = host.extCtx ?: return
        val panel = root ?: return
        val wmLp = lp ?: return
        host.popovers.dismissAllExcept(POPOVER_ID)
        isOpen = true
        applyTheme(ctx, host.tokens)

        selectedPkgs.clear()
        selectedPkgs.addAll(AppPrefs.getWorkbenchApps(host.serviceContext))
        updateCountLabel()

        val dispW = max(600, host.state.displayW)
        val dispH = max(480, host.state.displayH)
        val panelW = min(ctx.dp(600f), max(ctx.dp(380f), (dispW * 0.56f).toInt()))
        val panelH = min(ctx.dp(470f), max(ctx.dp(320f), (dispH * 0.66f).toInt()))
        wmLp.width = panelW
        wmLp.height = panelH
        wmLp.x = max(ctx.dp(16f), (dispW - panelW) / 2)
        wmLp.y = max(ctx.dp(24f), (dispH - panelH) / 2 - ctx.dp(16f))
        wmLp.flags = wmLp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()

        gridView?.numColumns = when {
            panelW >= ctx.dp(540f) -> 5
            panelW >= ctx.dp(420f) -> 4
            else -> 3
        }

        query = ""
        searchInput?.setText("")
        panel.visibility = View.VISIBLE
        host.surfaceHost.updateWindowLayout(panel, wmLp, host.extWm)
        panel.animatePopover(show = true, shiftYDp = 16f, startScale = 0.94f, enterMs = 175L) {
            host.bringCursorToFront()
        }
        ensureAppsLoaded()
        applyFilter("")
    }

    override fun dismiss() {
        isOpen = false
        val panel = root ?: return
        val wmLp = lp
        if (wmLp != null) {
            wmLp.flags = wmLp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            host.surfaceHost.updateWindowLayout(panel, wmLp, host.extWm)
        }
        if (panel.visibility == View.VISIBLE) {
            panel.animatePopover(show = false, shiftYDp = 14f, startScale = 0.95f, exitMs = 120L) {
                if (!isOpen) {
                    panel.visibility = View.GONE
                    panel.resetPopoverVisuals()
                }
            }
        }
    }

    fun detach() {
        isOpen = false
        host.popovers.unregister(POPOVER_ID)
        host.surfaceHost.unregister(POPOVER_ID, host.extWm)
        root = null; lp = null; titleView = null; countView = null; btnDone = null
        searchRow = null; searchIcon = null; searchInput = null; btnClear = null
        emptyView = null; gridView = null; adapter = null
    }

    private fun toggleAppSelection(entry: LauncherAppEntry) {
        if (selectedPkgs.contains(entry.pkg)) {
            selectedPkgs.remove(entry.pkg)
        } else {
            if (selectedPkgs.size >= AppPrefs.MAX_WORKBENCH_APPS) {
                host.serviceContext.toast("工作台最多添加 ${AppPrefs.MAX_WORKBENCH_APPS} 个应用")
                return
            }
            selectedPkgs.add(entry.pkg)
        }
        AppPrefs.setWorkbenchApps(host.serviceContext, selectedPkgs.toList())
        updateCountLabel()
        adapter?.notifyDataSetChanged()
        host.updateWorkbenchApps()
    }

    private fun updateCountLabel() {
        countView?.text = "已添加 ${selectedPkgs.size} / ${AppPrefs.MAX_WORKBENCH_APPS} 个应用 · 点击图标即可添加或移出"
    }

    private fun applyTheme(ctx: Context, tokens: GlassOverlayTokens) {
        root?.let { tokens.applyGlassBackground(it, tokens.panelFill, cornerRadiusDp = 24f, strokeWidthDp = 1f) }
        titleView?.setTextColor(tokens.textPrimary)
        countView?.setTextColor(tokens.textSecondary)
        btnDone?.background = roundedBg(tokens.accentBlue, ctx.dpF(12f))
        searchRow?.let { tokens.applyGlassBackground(it, tokens.cardInnerFill, cornerRadiusDp = 14f, strokeWidthDp = 0.8f) }
        searchIcon?.setImageDrawable(HyperUi.icon(ctx, HyperUi.ICON_SEARCH, tokens.textSecondary, 14f))
        searchInput?.setTextColor(tokens.textPrimary)
        searchInput?.setHintTextColor(tokens.textSecondary)
        btnClear?.setTextColor(tokens.textSecondary)
        emptyView?.setTextColor(tokens.textSecondary)
        adapter?.updateTokens(tokens)
    }

    private fun ensureAppsLoaded() {
        if (appsLoaded && allApps.isNotEmpty()) return
        host.safeBgExecute {
            val loaded = LauncherAppRepository.loadInstalledApps(host.serviceContext, LauncherActivity::class.java.name)
                .filterNot { it.pkg == host.serviceContext.packageName }
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
        val base = LauncherAppRepository.filterApps(allApps, query)
        // Sort already-selected apps to the top when not searching so managing is effortless
        val ordered = if (query.isEmpty()) {
            base.sortedByDescending { selectedPkgs.contains(it.pkg) }
        } else {
            base
        }
        adapter?.updateItems(ordered)
        val empty = appsLoaded && ordered.isEmpty()
        emptyView?.visibility = if (empty) View.VISIBLE else View.GONE
        gridView?.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private inner class PickerAppsAdapter(private val ctx: Context) : BaseAdapter() {
        private var items: List<LauncherAppEntry> = emptyList()
        private var tokens: GlassOverlayTokens = host.tokens

        fun updateItems(next: List<LauncherAppEntry>) {
            items = next
            notifyDataSetChanged()
        }

        fun updateTokens(t: GlassOverlayTokens) {
            tokens = t
            notifyDataSetChanged()
        }

        fun itemAt(pos: Int): LauncherAppEntry? = items.getOrNull(pos)

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val entry = items[position]
            val selected = selectedPkgs.contains(entry.pkg)
            val cell = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadDp(6f, 8f, 6f, 8f)
                pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
                addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, ctx.llp(ctx.dp(42f), ctx.dp(42f), bottomDp = 5f))
                addView(TextView(ctx).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, ctx.llp(MATCH, WRAP))
                addView(TextView(ctx).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9.5f)
                    paint.isFakeBoldText = true
                    gravity = Gravity.CENTER
                    setPadDp(6f, 1.5f)
                }, ctx.llp(WRAP, WRAP, topDp = 3f))
            }
            val icon = cell.getChildAt(0) as ImageView
            val label = cell.getChildAt(1) as TextView
            val badge = cell.getChildAt(2) as TextView
            icon.setImageDrawable(entry.icon)
            label.text = entry.label
            label.setTextColor(tokens.textPrimary)
            if (selected) {
                cell.background = roundedBg(
                    (tokens.accentBlue and 0x00FFFFFF) or 0x24000000,
                    ctx.dpF(14f),
                    ctx.dp(1.2f).coerceAtLeast(1),
                    tokens.accentBlue,
                )
                badge.text = "✓ 已添加"
                badge.setTextColor(Color.WHITE)
                badge.background = roundedBg(tokens.accentBlue, ctx.dpF(8f))
            } else {
                cell.background = OverlayUiUtils.makeStateListBg(ctx, 0x00000000, tokens.cardInnerFill, 14f)
                badge.text = "+ 添加"
                badge.setTextColor(tokens.textSecondary)
                badge.background = roundedBg(tokens.cardInnerFill, ctx.dpF(8f))
            }
            return cell
        }
    }
}

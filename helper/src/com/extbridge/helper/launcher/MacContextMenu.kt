package com.extbridge.helper.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.extbridge.helper.HyperUi
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * macOS / iOS-inspired frosted-glass context menu & adaptive sheet system for the ExtBridge desktop.
 * Replaces raw Android [android.app.AlertDialog] and [android.widget.PopupMenu] instances with
 * anchor-sprung popover menus on desktop and thumb-friendly sheets on compact/mobile displays.
 */
internal object MacOverlayPalette {
    const val SCRIM_SOFT = 0x22000000
    const val SCRIM_MODAL = 0x42000000
    const val GLASS_MENU_BG = 0xEDF7F8FA.toInt()
    const val GLASS_SHEET_BG = 0xF4F7F8FA.toInt()
    const val GLASS_STROKE = 0xD9FFFFFF.toInt()
    const val GLASS_ROW_HOVER = 0x12000000
    const val GLASS_ROW_PRESSED = 0x22007AFF
    const val GLASS_ROW_DANGER_PRESSED = 0x22FF3B30
    const val DIVIDER = 0x18000000
    const val TEXT_PRIMARY = 0xFF1D1D1F.toInt()
    const val TEXT_SECONDARY = 0xFF6E6E73.toInt()
    const val TEXT_MUTED = 0xFF8E8E93.toInt()
    const val ACCENT_BLUE = 0xFF007AFF.toInt()
    const val ACCENT_DANGER = 0xFFFF3B30.toInt()
    const val PILL_BG = 0x10000000
    const val PILL_STROKE = 0x16000000
}

internal sealed class MacMenuEntry {
    data class Action(
        val label: String,
        val subtitle: String? = null,
        val iconKind: Int = 0,
        val checked: Boolean = false,
        val destructive: Boolean = false,
        val hasSubmenu: Boolean = false,
        val keepOpen: Boolean = false,
        val onClick: () -> Unit,
    ) : MacMenuEntry()

    data class SectionHeader(val title: String) : MacMenuEntry()
    data object Divider : MacMenuEntry()
}

internal data class MacMenuHeader(
    val title: String,
    val subtitle: String? = null,
    val icon: Drawable? = null,
)

internal data class MacSheetItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val badge: String? = null,
    val badgeColor: Int = 0x440A84FF,
    val icon: Drawable? = null,
    val selected: Boolean = false,
)

/**
 * Single-instance overlay presenter attached to [Activity.getWindow]'s decor view.
 * Supports anchored macOS/iOS long-press context menus (with in-place submenu transitions)
 * and adaptive frosted-glass picker/multi-select sheets.
 */
internal class MacContextMenuPresenter(private val activity: Activity) {

    private var activeBackdrop: FrameLayout? = null
    private var activeCard: View? = null
    private var activeAnchorView: View? = null
    private var activeAnchorPoint: Pair<Int, Int>? = null
    private var activeMenuWidthDp: Float = 264f
    private var isAnimatingOut = false

    val isShowing: Boolean
        get() = activeBackdrop != null && !isAnimatingOut

    private fun dp(v: Float): Int = (v * activity.resources.displayMetrics.density).roundToInt()
    private fun dpF(v: Float): Float = v * activity.resources.displayMetrics.density

    fun dismiss(animated: Boolean = true) {
        val backdrop = activeBackdrop ?: return
        val card = activeCard
        if (!animated) {
            removeBackdropNow(backdrop)
            return
        }
        if (isAnimatingOut) return
        isAnimatingOut = true
        backdrop.animate().cancel()
        card?.animate()?.cancel()
        backdrop.animate()
            .alpha(0f)
            .setDuration(130L)
            .setInterpolator(AccelerateInterpolator(1.2f))
            .withEndAction { removeBackdropNow(backdrop) }
            .start()
        card?.animate()
            ?.scaleX(0.92f)
            ?.scaleY(0.92f)
            ?.alpha(0f)
            ?.setDuration(120L)
            ?.setInterpolator(AccelerateInterpolator(1.2f))
            ?.start()
    }

    private fun removeBackdropNow(backdrop: FrameLayout) {
        if (activeBackdrop === backdrop) {
            activeBackdrop = null
            activeCard = null
            activeAnchorView = null
            activeAnchorPoint = null
            isAnimatingOut = false
        }
        (backdrop.parent as? ViewGroup)?.removeView(backdrop)
    }

    /**
     * Shows an iOS/macOS frosted-glass context menu anchored to [anchorView] or ([anchorX], [anchorY]).
     * Automatically adapts to compact mobile screens by clamping cleanly inside viewport bounds.
     */
    fun showContextMenu(
        header: MacMenuHeader? = null,
        entries: List<MacMenuEntry>,
        anchorView: View? = null,
        anchorX: Int? = null,
        anchorY: Int? = null,
        widthDp: Float = 268f,
        triggerHaptic: Boolean = true,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        dismiss(animated = false)

        if (triggerHaptic) {
            (anchorView ?: decor).performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        val winW = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val winH = decor.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        val compactMobile = winW < dp(520f)
        val menuW = min(dp(widthDp), winW - dp(28f)).coerceAtLeast(dp(220f))

        activeAnchorView = anchorView
        activeAnchorPoint = if (anchorX != null && anchorY != null) anchorX to anchorY else null
        activeMenuWidthDp = widthDp

        val backdrop = FrameLayout(activity).apply {
            setBackgroundColor(MacOverlayPalette.SCRIM_SOFT)
            isClickable = true
            isFocusable = true
            alpha = 0f
            setOnClickListener { dismiss(animated = true) }
        }

        val card = buildContextMenuCard(header, entries, menuW, compactMobile)
        card.isClickable = true
        val maxMenuH = (winH * 0.78f).roundToInt()
        card.measure(
            View.MeasureSpec.makeMeasureSpec(menuW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxMenuH, View.MeasureSpec.AT_MOST),
        )
        val measuredH = card.measuredHeight.coerceAtMost(maxMenuH)

        val (posX, posY, pivotX, pivotY) = computeMenuPlacement(
            decor = decor,
            anchorView = anchorView,
            anchorPoint = activeAnchorPoint,
            menuW = menuW,
            menuH = measuredH,
            winW = winW,
            winH = winH,
            compactMobile = compactMobile,
        )

        val lp = FrameLayout.LayoutParams(menuW, measuredH, Gravity.TOP or Gravity.START).apply {
            leftMargin = posX
            topMargin = posY
        }
        backdrop.addView(card, lp)
        decor.addView(backdrop, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        activeBackdrop = backdrop
        activeCard = card
        isAnimatingOut = false

        card.pivotX = pivotX
        card.pivotY = pivotY
        card.scaleX = 0.86f
        card.scaleY = 0.86f
        card.alpha = 0f

        backdrop.animate().alpha(1f).setDuration(150L).setInterpolator(DecelerateInterpolator()).start()
        card.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(190L)
            .setInterpolator(OvershootInterpolator(1.05f))
            .start()
    }

    /**
     * Smoothly replaces the currently open context menu in-place (for macOS/iOS submenu drill-down).
     */
    fun replaceContextMenu(
        header: MacMenuHeader? = null,
        entries: List<MacMenuEntry>,
        backAction: (() -> Unit)? = null,
    ) {
        val backdrop = activeBackdrop ?: run {
            showContextMenu(header = header, entries = entries, triggerHaptic = false)
            return
        }
        val oldCard = activeCard ?: return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val winW = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val winH = decor.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        val compactMobile = winW < dp(520f)
        val menuW = min(dp(activeMenuWidthDp), winW - dp(28f)).coerceAtLeast(dp(220f))

        val combinedEntries = if (backAction != null) {
            buildList {
                add(
                    MacMenuEntry.Action(
                        label = "返回上级菜单",
                        iconKind = HyperUi.ICON_CHEVRON_LEFT,
                        keepOpen = true,
                        onClick = backAction,
                    ),
                )
                add(MacMenuEntry.Divider)
                addAll(entries)
            }
        } else {
            entries
        }

        val newCard = buildContextMenuCard(header, combinedEntries, menuW, compactMobile)
        newCard.isClickable = true
        val maxMenuH = (winH * 0.78f).roundToInt()
        newCard.measure(
            View.MeasureSpec.makeMeasureSpec(menuW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxMenuH, View.MeasureSpec.AT_MOST),
        )
        val measuredH = newCard.measuredHeight.coerceAtMost(maxMenuH)
        val (posX, posY, _, _) = computeMenuPlacement(
            decor = decor,
            anchorView = activeAnchorView,
            anchorPoint = activeAnchorPoint,
            menuW = menuW,
            menuH = measuredH,
            winW = winW,
            winH = winH,
            compactMobile = compactMobile,
        )

        backdrop.removeView(oldCard)
        val lp = FrameLayout.LayoutParams(menuW, measuredH, Gravity.TOP or Gravity.START).apply {
            leftMargin = posX
            topMargin = posY
        }
        backdrop.addView(newCard, lp)
        activeCard = newCard

        newCard.alpha = 0f
        newCard.translationX = dpF(10f)
        newCard.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    /**
     * Shows a compact macOS frosted-glass modal sheet wrapping a custom input or form view
     * (e.g., folder rename dialog) with Cancel / Confirm buttons.
     */
    fun showCustomSheet(
        title: String,
        subtitle: String? = null,
        contentView: View,
        confirmLabel: String = "确定",
        onConfirm: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        dismiss(animated = false)

        val winW = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val sheetW = min(dp(360f), winW - dp(32f)).coerceAtLeast(dp(260f))

        val backdrop = FrameLayout(activity).apply {
            setBackgroundColor(MacOverlayPalette.SCRIM_MODAL)
            isClickable = true
            isFocusable = true
            alpha = 0f
            setOnClickListener { dismiss(animated = true) }
        }
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            elevation = dpF(24f)
            background = GradientDrawable().apply {
                setColor(MacOverlayPalette.GLASS_SHEET_BG)
                cornerRadius = dpF(24f)
                setStroke(1, MacOverlayPalette.GLASS_STROKE)
            }
            setPadding(dp(20f), dp(18f), dp(20f), dp(18f))
        }
        sheet.addView(TextView(activity).apply {
            text = title
            setTextColor(MacOverlayPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f)
            paint.isFakeBoldText = true
        })
        if (!subtitle.isNullOrBlank()) {
            sheet.addView(TextView(activity).apply {
                text = subtitle
                setTextColor(MacOverlayPalette.TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3f)
            })
        }
        (contentView.parent as? ViewGroup)?.removeView(contentView)
        sheet.addView(contentView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14f)
            bottomMargin = dp(16f)
        })
        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val cancelBtn = TextView(activity).apply {
            text = "取消"
            setTextColor(MacOverlayPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            paint.isFakeBoldText = true
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            background = makeInteractivePillBg(0x0E000000, 0x1C000000, 12f, 0x14000000)
            setOnClickListener { dismiss(animated = true) }
        }
        val okBtn = TextView(activity).apply {
            text = confirmLabel
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            paint.isFakeBoldText = true
            setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
            background = makeInteractivePillBg(MacOverlayPalette.ACCENT_BLUE, 0xFF3395FF.toInt(), 12f, 0x33007AFF)
            setOnClickListener {
                dismiss(animated = true)
                onConfirm()
            }
        }
        footer.addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(8f)
        })
        footer.addView(okBtn)
        sheet.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        backdrop.addView(sheet, FrameLayout.LayoutParams(sheetW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        decor.addView(backdrop, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        activeBackdrop = backdrop
        activeCard = sheet
        isAnimatingOut = false

        backdrop.animate().alpha(1f).setDuration(150L).start()
        sheet.scaleX = 0.92f; sheet.scaleY = 0.92f; sheet.alpha = 0f
        sheet.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180L).setInterpolator(DecelerateInterpolator(1.5f)).start()
    }

    /**
     * Shows an adaptive macOS frosted-glass sheet for single-selection or multi-selection lists
     * with optional live search and top action button.
     */
    fun showListSheet(
        title: String,
        subtitle: String? = null,
        items: List<MacSheetItem>,
        searchable: Boolean = true,
        searchHint: String = "搜索…",
        multiSelect: Boolean = false,
        topActionLabel: String? = null,
        onTopAction: (() -> Unit)? = null,
        onSingleSelect: ((MacSheetItem) -> Unit)? = null,
        onMultiConfirm: ((Set<String>) -> Unit)? = null,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        dismiss(animated = false)

        val winW = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val winH = decor.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        val isMobileNarrow = winW < dp(560f)
        val sheetW = if (isMobileNarrow) winW - dp(16f) else min(dp(468f), winW - dp(40f))
        val maxSheetH = (winH * (if (isMobileNarrow) 0.82f else 0.76f)).roundToInt()

        val selectedIds = items.filter { it.selected }.mapTo(LinkedHashSet()) { it.id }

        val backdrop = FrameLayout(activity).apply {
            setBackgroundColor(MacOverlayPalette.SCRIM_MODAL)
            isClickable = true
            isFocusable = true
            alpha = 0f
            setOnClickListener { dismiss(animated = true) }
        }

        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            elevation = dpF(24f)
            background = GradientDrawable().apply {
                setColor(MacOverlayPalette.GLASS_SHEET_BG)
                cornerRadius = dpF(26f)
                setStroke(1, MacOverlayPalette.GLASS_STROKE)
            }
            setPadding(dp(18f), dp(if (isMobileNarrow) 10f else 18f), dp(18f), dp(18f))
        }

        // Mobile grabber pill
        if (isMobileNarrow) {
            val grabber = View(activity).apply {
                background = GradientDrawable().apply {
                    setColor(0x33000000)
                    cornerRadius = dpF(2f)
                }
            }
            sheet.addView(
                grabber,
                LinearLayout.LayoutParams(dp(38f), dp(4f)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(10f)
                },
            )
        }

        // Header row
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(activity).apply {
            text = title
            setTextColor(MacOverlayPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f)
            paint.isFakeBoldText = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (!subtitle.isNullOrBlank()) {
            titleCol.addView(TextView(activity).apply {
                text = subtitle
                setTextColor(MacOverlayPalette.TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2f)
            })
        }
        headerRow.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val closeBtn = TextView(activity).apply {
            text = "×"
            setTextColor(MacOverlayPalette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f)
            gravity = Gravity.CENTER
            background = makeInteractivePillBg(0x10000000, 0x20000000, 14f)
            setOnClickListener { dismiss(animated = true) }
        }
        headerRow.addView(closeBtn, LinearLayout.LayoutParams(dp(28f), dp(28f)).apply { leftMargin = dp(8f) })
        sheet.addView(headerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12f)
        })

        // Optional top action bar
        if (!topActionLabel.isNullOrBlank() && onTopAction != null) {
            val actionBanner = TextView(activity).apply {
                text = topActionLabel
                setTextColor(MacOverlayPalette.ACCENT_BLUE)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f)
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = makeInteractivePillBg(0x16007AFF, 0x2A007AFF, 14f, 0x33007AFF)
                HyperUi.setStartIcon(this, HyperUi.ICON_ROTATE, MacOverlayPalette.ACCENT_BLUE, 13.5f, 7f)
                setOnClickListener {
                    dismiss(animated = true)
                    onTopAction()
                }
            }
            sheet.addView(actionBanner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10f)
            })
        }

        // Optional live search bar
        val listContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val emptyLabel = TextView(activity).apply {
            text = "无匹配项"
            setTextColor(MacOverlayPalette.TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(24f), 0, dp(24f))
            visibility = View.GONE
        }

        var currentQuery = ""
        fun renderItems() {
            listContainer.removeAllViews()
            val q = currentQuery.trim().lowercase(Locale.ROOT)
            val filtered = if (q.isEmpty()) items else items.filter {
                it.title.lowercase(Locale.ROOT).contains(q) ||
                    (it.subtitle?.lowercase(Locale.ROOT)?.contains(q) == true)
            }
            emptyLabel.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            filtered.forEachIndexed { idx, item ->
                val row = buildSheetRow(
                    item = item,
                    multiSelect = multiSelect,
                    isSelected = selectedIds.contains(item.id),
                ) { checkView ->
                    if (multiSelect) {
                        val nowSelected = if (selectedIds.contains(item.id)) {
                            selectedIds.remove(item.id); false
                        } else {
                            selectedIds.add(item.id); true
                        }
                        updateCheckCircle(checkView, nowSelected)
                    } else {
                        dismiss(animated = true)
                        onSingleSelect?.invoke(item)
                    }
                }
                listContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                if (idx < filtered.lastIndex) {
                    listContainer.addView(View(activity).apply {
                        setBackgroundColor(MacOverlayPalette.DIVIDER)
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                        leftMargin = dp(if (item.icon != null) 46f else 12f)
                        rightMargin = dp(12f)
                    })
                }
            }
        }

        if (searchable && items.size > 6) {
            val searchRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(2f), dp(10f), dp(2f))
                background = GradientDrawable().apply {
                    setColor(0x0E000000)
                    cornerRadius = dpF(14f)
                    setStroke(1, 0x14000000)
                }
            }
            searchRow.addView(ImageView(activity).apply {
                setImageDrawable(HyperUi.icon(activity, HyperUi.ICON_SEARCH, MacOverlayPalette.TEXT_SECONDARY, 14f))
            }, LinearLayout.LayoutParams(dp(14f), dp(14f)).apply { rightMargin = dp(8f) })
            val input = EditText(activity).apply {
                hint = searchHint
                setHintTextColor(0x886E6E73.toInt())
                setTextColor(MacOverlayPalette.TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                setSingleLine(true)
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(0, dp(7f), 0, dp(7f))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        currentQuery = s?.toString().orEmpty()
                        renderItems()
                    }
                })
            }
            searchRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            sheet.addView(searchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10f)
            })
        }

        val scrollContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(emptyLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(listContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(scrollContent, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        renderItems()

        // Footer buttons for multi-select
        if (multiSelect) {
            val footer = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(12f), 0, 0)
            }
            val cancelBtn = TextView(activity).apply {
                text = "取消"
                setTextColor(MacOverlayPalette.TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setPadding(dp(18f), dp(9f), dp(18f), dp(9f))
                background = makeInteractivePillBg(0x0E000000, 0x1C000000, 14f, 0x14000000)
                setOnClickListener { dismiss(animated = true) }
            }
            val confirmBtn = TextView(activity).apply {
                text = "保存"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setPadding(dp(22f), dp(9f), dp(22f), dp(9f))
                background = makeInteractivePillBg(MacOverlayPalette.ACCENT_BLUE, 0xFF3395FF.toInt(), 14f, 0x33007AFF)
                setOnClickListener {
                    val result = selectedIds.toSet()
                    dismiss(animated = true)
                    onMultiConfirm?.invoke(result)
                }
            }
            footer.addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8f)
            })
            footer.addView(confirmBtn)
            sheet.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val sheetLp = if (isMobileNarrow) {
            FrameLayout.LayoutParams(sheetW, maxSheetH, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(10f)
            }
        } else {
            FrameLayout.LayoutParams(sheetW, maxSheetH, Gravity.CENTER)
        }
        backdrop.addView(sheet, sheetLp)
        decor.addView(backdrop, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        activeBackdrop = backdrop
        activeCard = sheet
        isAnimatingOut = false

        backdrop.animate().alpha(1f).setDuration(160L).setInterpolator(DecelerateInterpolator()).start()
        if (isMobileNarrow) {
            sheet.translationY = dpF(36f)
            sheet.alpha = 0f
            sheet.animate().translationY(0f).alpha(1f).setDuration(200L).setInterpolator(DecelerateInterpolator(1.6f)).start()
        } else {
            sheet.scaleX = 0.94f
            sheet.scaleY = 0.94f
            sheet.alpha = 0f
            sheet.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(190L).setInterpolator(DecelerateInterpolator(1.5f)).start()
        }
    }

    private fun buildContextMenuCard(
        header: MacMenuHeader?,
        entries: List<MacMenuEntry>,
        menuW: Int,
        compactMobile: Boolean,
    ): LinearLayout {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dpF(22f)
            background = GradientDrawable().apply {
                setColor(MacOverlayPalette.GLASS_MENU_BG)
                cornerRadius = dpF(20f)
                setStroke(1, MacOverlayPalette.GLASS_STROKE)
            }
            setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
        }

        if (header != null) {
            val headerBox = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f), dp(8f), dp(10f), dp(10f))
            }
            if (header.icon != null) {
                headerBox.addView(ImageView(activity).apply {
                    setImageDrawable(header.icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(32f), dp(32f)).apply { rightMargin = dp(10f) })
            }
            val textCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(TextView(activity).apply {
                text = header.title
                setTextColor(MacOverlayPalette.TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f)
                paint.isFakeBoldText = true
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (!header.subtitle.isNullOrBlank()) {
                textCol.addView(TextView(activity).apply {
                    text = header.subtitle
                    setTextColor(MacOverlayPalette.TEXT_SECONDARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(1f)
                })
            }
            headerBox.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            container.addView(headerBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            container.addView(View(activity).apply {
                setBackgroundColor(MacOverlayPalette.DIVIDER)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                leftMargin = dp(6f)
                rightMargin = dp(6f)
                bottomMargin = dp(4f)
            })
        }

        val itemsBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        entries.forEach { entry ->
            when (entry) {
                is MacMenuEntry.Divider -> {
                    itemsBox.addView(View(activity).apply {
                        setBackgroundColor(MacOverlayPalette.DIVIDER)
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                        leftMargin = dp(8f)
                        rightMargin = dp(8f)
                        topMargin = dp(4f)
                        bottomMargin = dp(4f)
                    })
                }
                is MacMenuEntry.SectionHeader -> {
                    itemsBox.addView(TextView(activity).apply {
                        text = entry.title
                        setTextColor(MacOverlayPalette.TEXT_MUTED)
                        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f)
                        paint.isFakeBoldText = true
                        setPadding(dp(10f), dp(6f), dp(10f), dp(3f))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                is MacMenuEntry.Action -> {
                    itemsBox.addView(buildActionRow(entry, compactMobile), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }
        }

        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(itemsBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        container.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return container
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildActionRow(action: MacMenuEntry.Action, compactMobile: Boolean): LinearLayout {
        val fgColor = when {
            action.destructive -> MacOverlayPalette.ACCENT_DANGER
            action.checked -> MacOverlayPalette.ACCENT_BLUE
            else -> MacOverlayPalette.TEXT_PRIMARY
        }
        val pressedColor = if (action.destructive) MacOverlayPalette.GLASS_ROW_DANGER_PRESSED else MacOverlayPalette.GLASS_ROW_PRESSED

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (compactMobile) 44f else 39f)
            setPadding(dp(10f), dp(if (compactMobile) 9f else 7.5f), dp(10f), dp(if (compactMobile) 9f else 7.5f))
            background = makeInteractivePillBg(Color.TRANSPARENT, pressedColor, 10f, hoverColor = MacOverlayPalette.GLASS_ROW_HOVER)
            setOnClickListener {
                if (!action.keepOpen) dismiss(animated = true)
                action.onClick()
            }
            setOnTouchListener { v, ev ->
                when (ev?.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(60L).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
                }
                false
            }
        }

        if (action.iconKind > 0) {
            row.addView(ImageView(activity).apply {
                setImageDrawable(HyperUi.icon(activity, action.iconKind, fgColor, 15f))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(16f), dp(16f)).apply { rightMargin = dp(10f) })
        }

        val textCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(activity).apply {
            text = action.label
            setTextColor(fgColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            paint.isFakeBoldText = action.checked
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (!action.subtitle.isNullOrBlank()) {
            textCol.addView(TextView(activity).apply {
                text = action.subtitle
                setTextColor(MacOverlayPalette.TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (action.checked) {
            row.addView(TextView(activity).apply {
                text = "✓"
                setTextColor(MacOverlayPalette.ACCENT_BLUE)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                paint.isFakeBoldText = true
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(8f)
            })
        } else if (action.hasSubmenu) {
            row.addView(ImageView(activity).apply {
                setImageDrawable(HyperUi.icon(activity, HyperUi.ICON_CHEVRON_RIGHT, MacOverlayPalette.TEXT_SECONDARY, 13f))
            }, LinearLayout.LayoutParams(dp(13f), dp(13f)).apply { leftMargin = dp(8f) })
        }
        return row
    }

    private fun buildSheetRow(
        item: MacSheetItem,
        multiSelect: Boolean,
        isSelected: Boolean,
        onTap: (TextView?) -> Unit,
    ): LinearLayout {
        var checkCircle: TextView? = null
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48f)
            setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
            background = makeInteractivePillBg(Color.TRANSPARENT, MacOverlayPalette.GLASS_ROW_PRESSED, 11f, hoverColor = MacOverlayPalette.GLASS_ROW_HOVER)
            setOnClickListener { onTap(checkCircle) }
        }

        if (item.icon != null) {
            row.addView(ImageView(activity).apply {
                setImageDrawable(item.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(28f), dp(28f)).apply { rightMargin = dp(10f) })
        }

        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(activity).apply {
            text = item.title
            setTextColor(MacOverlayPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (!item.subtitle.isNullOrBlank()) {
            col.addView(TextView(activity).apply {
                text = item.subtitle
                setTextColor(MacOverlayPalette.TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(1f)
            })
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (!item.badge.isNullOrBlank()) {
            row.addView(TextView(activity).apply {
                text = item.badge
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f)
                setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
                background = GradientDrawable().apply {
                    setColor(item.badgeColor)
                    cornerRadius = dpF(8f)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(8f)
            })
        }

        if (multiSelect) {
            checkCircle = TextView(activity).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
                paint.isFakeBoldText = true
                updateCheckCircle(this, isSelected)
            }
            row.addView(checkCircle, LinearLayout.LayoutParams(dp(22f), dp(22f)).apply { leftMargin = dp(10f) })
        }
        return row
    }

    private fun updateCheckCircle(view: TextView?, checked: Boolean) {
        if (view == null) return
        view.text = if (checked) "✓" else ""
        view.setTextColor(Color.WHITE)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (checked) {
                setColor(MacOverlayPalette.ACCENT_BLUE)
            } else {
                setColor(0x0A000000)
                setStroke(dp(1.2f), 0x38000000)
            }
        }
    }

    private fun makeInteractivePillBg(
        normalColor: Int,
        pressedColor: Int,
        radiusDp: Float,
        strokeColor: Int = 0,
        hoverColor: Int = pressedColor,
    ): StateListDrawable {
        fun makeShape(fill: Int) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dpF(radiusDp)
            if (strokeColor != 0) setStroke(1, strokeColor)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), makeShape(pressedColor))
            addState(intArrayOf(android.R.attr.state_hovered), makeShape(hoverColor))
            addState(intArrayOf(android.R.attr.state_focused), makeShape(hoverColor))
            addState(IntArray(0), makeShape(normalColor))
        }
    }

    private data class Placement(val x: Int, val y: Int, val pivotX: Float, val pivotY: Float)

    private fun computeMenuPlacement(
        decor: View,
        anchorView: View?,
        anchorPoint: Pair<Int, Int>?,
        menuW: Int,
        menuH: Int,
        winW: Int,
        winH: Int,
        compactMobile: Boolean,
    ): Placement {
        val margin = dp(14f)
        val dockSafeBottom = dp(72f)
        val decorLoc = IntArray(2)
        decor.getLocationOnScreen(decorLoc)

        if (anchorView != null && anchorView.isShown && anchorView.width > 0 && anchorView.height > 0) {
            val aLoc = IntArray(2)
            anchorView.getLocationOnScreen(aLoc)
            val aLeft = aLoc[0] - decorLoc[0]
            val aTop = aLoc[1] - decorLoc[1]
            val aRight = aLeft + anchorView.width
            val aBottom = aTop + anchorView.height
            val gap = dp(8f)

            val preferRight = aRight + gap + menuW <= winW - margin
            val preferLeft = aLeft - gap - menuW >= margin
            val x = when {
                compactMobile -> (aLeft + (anchorView.width - menuW) / 2).coerceIn(margin, max(margin, winW - menuW - margin))
                preferRight -> aRight + gap
                preferLeft -> aLeft - gap - menuW
                else -> (aLeft + (anchorView.width - menuW) / 2).coerceIn(margin, max(margin, winW - menuW - margin))
            }
            val preferBelow = aBottom + gap + menuH <= winH - dockSafeBottom
            val y = when {
                !compactMobile && (preferRight || preferLeft) -> aTop.coerceIn(margin, max(margin, winH - menuH - dockSafeBottom))
                preferBelow -> aBottom + gap
                aTop - gap - menuH >= margin -> aTop - gap - menuH
                else -> ((winH - menuH) / 2).coerceIn(margin, max(margin, winH - menuH - dockSafeBottom))
            }
            val px = ((aLeft + anchorView.width * 0.5f) - x).coerceIn(0f, menuW.toFloat())
            val py = ((aTop + anchorView.height * 0.5f) - y).coerceIn(0f, menuH.toFloat())
            return Placement(x, y, px, py)
        }

        if (anchorPoint != null) {
            val ax = anchorPoint.first - decorLoc[0]
            val ay = anchorPoint.second - decorLoc[1]
            val x = if (ax + menuW <= winW - margin) ax else (ax - menuW).coerceIn(margin, max(margin, winW - menuW - margin))
            val y = if (ay + menuH <= winH - dockSafeBottom) ay else (ay - menuH).coerceIn(margin, max(margin, winH - menuH - dockSafeBottom))
            val px = (ax - x).toFloat().coerceIn(0f, menuW.toFloat())
            val py = (ay - y).toFloat().coerceIn(0f, menuH.toFloat())
            return Placement(x, y, px, py)
        }

        val cx = ((winW - menuW) / 2).coerceIn(margin, max(margin, winW - menuW - margin))
        val cy = if (compactMobile) {
            (winH - menuH - dockSafeBottom).coerceIn(margin, max(margin, winH - menuH - margin))
        } else {
            ((winH - menuH) / 2).coerceIn(margin, max(margin, winH - menuH - dockSafeBottom))
        }
        return Placement(cx, cy, menuW * 0.5f, if (compactMobile) menuH.toFloat() else menuH * 0.5f)
    }
}

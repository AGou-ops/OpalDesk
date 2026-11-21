package com.extbridge.helper.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.extbridge.helper.protocol.BridgeLabels
import com.extbridge.helper.protocol.BridgeProtocol.AppMode
import kotlin.math.roundToInt

/**
 * ViewHolder-backed macOS-inspired desktop grid adapter with squircle icon containers,
 * subtle hover/press spring feedback, and balanced 2-line aligned labels.
 */
class LauncherGridAdapter(
    private val context: Context,
    private val darkInkOnLightSurface: Boolean = false,
    private val onSecondaryClick: (LauncherAppEntry, View) -> Unit,
) : BaseAdapter() {

    companion object {
        private const val TILE_RADIUS_DP = 16f
        private const val ICON_BOX_FULL_DP = 56
        private const val ICON_BOX_COMPACT_DP = 44
        private const val ICON_CORNER_FULL_DP = 14.5f
        private const val ICON_CORNER_COMPACT_DP = 11.5f
        private const val PRESSED_SCALE = 0.92f
        private const val PRESS_DOWN_MS = 75L
        private const val PRESS_UP_MS = 170L
    }

    private class ViewHolder(
        val card: LinearLayout,
        val iconFrame: FrameLayout,
        val iconFrameBg: GradientDrawable,
        val icon: ImageView,
        val label: TextView,
        val badge: TextView,
        val badgeBg: GradientDrawable,
    ) {
        var boundEntry: LauncherAppEntry? = null
        var lastCompact: Boolean? = null
        var iconCornerPx: Float = 0f
    }

    private var apps: List<LauncherAppEntry> = emptyList()
    private var appModes: Map<String, Int> = emptyMap()
    private var compactMode: Boolean = false
    private var lastSecondaryClickMs: Long = 0L

    val itemCount: Int get() = apps.size
    fun itemAt(position: Int): LauncherAppEntry? = apps.getOrNull(position)
    fun updateApps(next: List<LauncherAppEntry>) { apps = next; notifyDataSetChanged() }
    fun updateModes(next: Map<String, Int>) { appModes = next; notifyDataSetChanged() }
    fun setCompactMode(compact: Boolean) {
        if (compactMode != compact) {
            compactMode = compact
            notifyDataSetChanged()
        }
    }

    override fun getCount(): Int = apps.size
    override fun getItem(pos: Int): Any = apps[pos]
    override fun getItemId(pos: Int): Long = pos.toLong()

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).roundToInt()
    private fun dpF(v: Float): Float = v * context.resources.displayMetrics.density

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
        val holder = (convertView?.tag as? ViewHolder) ?: createHolder()
        val entry = apps[pos]
        holder.boundEntry = entry
        if (holder.lastCompact != compactMode) {
            holder.lastCompact = compactMode
            applyDensityStyle(holder, compactMode)
        }
        holder.icon.setImageDrawable(entry.icon)
        holder.label.text = entry.label

        val mode = appModes[entry.pkg] ?: AppMode.DEFAULT
        if (mode > AppMode.DEFAULT) {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = BridgeLabels.appModeBadge(mode)
            holder.badgeBg.setColor(
                when (mode) {
                    AppMode.LAND_FULL -> 0xCC0A84FF.toInt()
                    AppMode.PORT_COMPAT -> 0xCCFF9F0A.toInt()
                    else -> 0xCCBF5AF2.toInt()
                },
            )
        } else {
            holder.badge.visibility = View.GONE
        }
        return holder.card
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createHolder(): ViewHolder {
        val pressedBg = GradientDrawable().apply {
            setColor(if (darkInkOnLightSurface) 0x1A007AFF else 0x38FFFFFF)
            cornerRadius = dpF(TILE_RADIUS_DP)
            setStroke(1, if (darkInkOnLightSurface) 0x33007AFF else 0x55FFFFFF)
        }
        val hoverBg = GradientDrawable().apply {
            setColor(if (darkInkOnLightSurface) 0x0E000000 else 0x22FFFFFF)
            cornerRadius = dpF(TILE_RADIUS_DP)
            setStroke(1, if (darkInkOnLightSurface) 0x14000000 else 0x30FFFFFF)
        }
        val stateBg = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
            addState(intArrayOf(android.R.attr.state_hovered), hoverBg)
            addState(intArrayOf(android.R.attr.state_focused), hoverBg)
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }

        val iconFrameBg = GradientDrawable().apply {
            setColor(if (darkInkOnLightSurface) 0xB8FFFFFF.toInt() else 0x2EFFFFFF)
            cornerRadius = dpF(ICON_CORNER_FULL_DP)
            setStroke(1, if (darkInkOnLightSurface) 0x16000000 else 0x48FFFFFF)
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val iconFrame = FrameLayout(context).apply {
            background = iconFrameBg
            elevation = dpF(6f)
            clipToOutline = true
            addView(
                icon,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )
        }
        val label = TextView(context).apply {
            if (darkInkOnLightSurface) {
                setTextColor(0xFF1D1D1F.toInt())
            } else {
                setTextColor(0xFFF8FAFC.toInt())
                setShadowLayer(dpF(4f), 0f, dpF(1.3f), 0xD8000000.toInt())
            }
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            maxLines = 2
            minLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dpF(1.5f), 1f)
        }
        val badgeBg = GradientDrawable().apply { cornerRadius = dpF(8f) }
        val badge = TextView(context).apply {
            setTextColor(Color.WHITE)
            setPadding(dp(6), dp(1), dp(6), dp(1))
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            background = badgeBg
            visibility = View.GONE
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = stateBg
            addView(iconFrame, LinearLayout.LayoutParams(dp(ICON_BOX_FULL_DP), dp(ICON_BOX_FULL_DP)))
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                badge,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(3)
                },
            )
        }
        return ViewHolder(card, iconFrame, iconFrameBg, icon, label, badge, badgeBg).also { holder ->
            card.tag = holder
            card.pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HAND)
            iconFrame.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, holder.iconCornerPx)
                }
            }
            fun fireSecondaryOnce() {
                val now = SystemClock.uptimeMillis()
                if (now - lastSecondaryClickMs < 220L) return
                lastSecondaryClickMs = now
                holder.boundEntry?.let { onSecondaryClick(it, holder.iconFrame) }
            }
            card.setOnHoverListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER -> iconFrame.animate().scaleX(1.07f).scaleY(1.07f).setDuration(110L).setInterpolator(DecelerateInterpolator()).start()
                    MotionEvent.ACTION_HOVER_EXIT -> iconFrame.animate().scaleX(1f).scaleY(1f).setDuration(120L).setInterpolator(DecelerateInterpolator()).start()
                }
                false
            }
            card.setOnTouchListener { v, ev ->
                if (ev == null) return@setOnTouchListener false
                if (ev.actionMasked == MotionEvent.ACTION_DOWN && (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                    fireSecondaryOnce()
                    return@setOnTouchListener true
                }
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().cancel()
                        v.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE)
                            .setDuration(PRESS_DOWN_MS).setInterpolator(DecelerateInterpolator()).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate().cancel()
                        v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(PRESS_UP_MS).setInterpolator(OvershootInterpolator(1.4f)).start()
                    }
                }
                false
            }
            card.setOnGenericMotionListener { _, ev ->
                if (ev?.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && ev.actionButton == MotionEvent.BUTTON_SECONDARY) {
                    fireSecondaryOnce()
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun applyDensityStyle(holder: ViewHolder, compact: Boolean) {
        val hPad = dp(if (compact) 6 else 8)
        val vPad = dp(if (compact) 7 else 10)
        holder.card.setPadding(hPad, vPad, hPad, vPad)

        val boxPx = dp(if (compact) ICON_BOX_COMPACT_DP else ICON_BOX_FULL_DP)
        val iconPad = dp(if (compact) 2 else 3)
        val cornerPx = dpF(if (compact) ICON_CORNER_COMPACT_DP else ICON_CORNER_FULL_DP)
        holder.iconCornerPx = cornerPx
        holder.iconFrameBg.cornerRadius = cornerPx
        holder.iconFrame.setPadding(iconPad, iconPad, iconPad, iconPad)
        holder.iconFrame.invalidateOutline()
        (holder.iconFrame.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            val bottom = dp(if (compact) 5 else 7)
            if (lp.width != boxPx || lp.bottomMargin != bottom) {
                lp.width = boxPx
                lp.height = boxPx
                lp.bottomMargin = bottom
                holder.iconFrame.layoutParams = lp
            }
        }

        holder.label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (compact) 11f else 12f)
        holder.badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (compact) 8.5f else 9.5f)
    }
}

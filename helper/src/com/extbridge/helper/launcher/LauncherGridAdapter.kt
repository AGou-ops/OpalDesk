package com.extbridge.helper.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.*
import android.widget.*
import com.extbridge.helper.protocol.BridgeLabels
import com.extbridge.helper.protocol.BridgeProtocol.AppMode
import kotlin.math.roundToInt

/**
 * ViewHolder-backed desktop grid adapter avoiding per-item drawable and listener allocations.
 */
class LauncherGridAdapter(
    private val context: Context,
    private val onSecondaryClick: (LauncherAppEntry) -> Unit,
) : BaseAdapter() {

    private class ViewHolder(
        val card: LinearLayout,
        val icon: ImageView,
        val label: TextView,
        val badge: TextView,
        val badgeBg: GradientDrawable,
    ) {
        var boundEntry: LauncherAppEntry? = null
        var lastCompact: Boolean? = null
    }

    private var apps: List<LauncherAppEntry> = emptyList()
    private var appModes: Map<String, Int> = emptyMap()
    private var compactMode: Boolean = false

    val itemCount: Int get() = apps.size
    fun itemAt(position: Int): LauncherAppEntry? = apps.getOrNull(position)
    fun updateApps(next: List<LauncherAppEntry>) { apps = next; notifyDataSetChanged() }
    fun updateModes(next: Map<String, Int>) { appModes = next; notifyDataSetChanged() }
    fun setCompactMode(compact: Boolean) { if (compactMode != compact) { compactMode = compact; notifyDataSetChanged() } }

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
                    AppMode.LAND_FULL -> 0xCC0284C7.toInt()
                    AppMode.PORT_COMPAT -> 0xCCD97706.toInt()
                    else -> 0xCC7C3AED.toInt()
                },
            )
        } else {
            holder.badge.visibility = View.GONE
        }
        return holder.card
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createHolder(): ViewHolder {
        val hoverBg = GradientDrawable().apply { setColor(0x36FFFFFF); cornerRadius = dpF(14f) }
        val stateBg = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), hoverBg)
            addState(intArrayOf(android.R.attr.state_hovered), hoverBg)
            addState(intArrayOf(android.R.attr.state_focused), hoverBg)
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val label = TextView(context).apply {
            setTextColor(Color.WHITE); setShadowLayer(dpF(4f), 0f, dpF(1.5f), 0xE6000000.toInt())
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        val badgeBg = GradientDrawable().apply { cornerRadius = dpF(6f) }
        val badge = TextView(context).apply {
            setTextColor(Color.WHITE); setPadding(dp(5), dp(1), dp(5), dp(1)); gravity = Gravity.CENTER; background = badgeBg
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = stateBg
            addView(icon, LinearLayout.LayoutParams(dp(54), dp(54)).apply { bottomMargin = dp(6) })
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        }
        return ViewHolder(card, icon, label, badge, badgeBg).also { holder ->
            card.tag = holder
            card.setOnTouchListener { _, ev ->
                if (ev?.actionMasked == MotionEvent.ACTION_DOWN && (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                    holder.boundEntry?.let(onSecondaryClick); true
                } else false
            }
            card.setOnGenericMotionListener { _, ev ->
                if (ev?.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && ev.actionButton == MotionEvent.BUTTON_SECONDARY) {
                    holder.boundEntry?.let(onSecondaryClick); true
                } else false
            }
        }
    }

    private fun applyDensityStyle(holder: ViewHolder, compact: Boolean) {
        val hPad = dp(if (compact) 4 else 6)
        val vPad = dp(if (compact) 5 else 8)
        holder.card.setPadding(hPad, vPad, hPad, vPad)
        val iconPx = dp(if (compact) 40 else 54)
        (holder.icon.layoutParams as? LinearLayout.LayoutParams)?.takeIf { it.width != iconPx }?.let {
            it.width = iconPx; it.height = iconPx; it.bottomMargin = dp(if (compact) 4 else 6)
            holder.icon.layoutParams = it
        }
        holder.label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (compact) 10.5f else 11.5f)
        holder.badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (compact) 8.5f else 9.5f)
    }
}

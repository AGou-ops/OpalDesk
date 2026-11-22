package io.github.lengsukq.opaldesk.ui

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.prefs.MirrorThemeCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal fun drawableToImageBitmap(drawable: Drawable?, maxPx: Int): ImageBitmap? {
    if (drawable == null) return null
    return try {
        val w = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(maxPx) ?: maxPx
        val h = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(maxPx) ?: maxPx
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(Canvas(bmp))
        bmp.asImageBitmap()
    } catch (t: Throwable) {
        Log.d("ExtIconBitmap", "Failed to rasterize drawable: ${t.message}")
        null
    }
}

/**
 * Loads installed AppWidgets and handles bind/configure Activity results for Mirror Sidebar slots.
 */
class MainWidgetBinder(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val onWidgetsUpdated: () -> Unit,
) {
    companion object {
        private const val TAG = "ExtWidgetBinder"
        const val REQ_BIND_APPWIDGET = 4101
        const val REQ_CONF_APPWIDGET = 4102
    }

    val candidates = mutableStateListOf<WidgetCandidateItem>()
    var loading by mutableStateOf(false); private set

    private var pendingSlot = -1
    private var pendingWidgetId = -1

    fun resolveBoundWidgetLabel(slot: Int): String? {
        val wid = AppPrefs.getMirrorWidgetId(activity, slot)
        if (wid <= 0) return null
        return try {
            val info = AppWidgetManager.getInstance(activity).getAppWidgetInfo(wid) ?: return null
            val lbl = info.loadLabel(activity.packageManager) ?: info.provider.shortClassName
            val rIdx = AppPrefs.getMirrorWidgetRatio(activity, slot)
            val suffix = MirrorThemeCatalog.MIRROR_WIDGET_RATIO_LABELS.getOrNull(rIdx)?.takeIf { rIdx > 0 }?.let { " ($it)" }.orEmpty()
            lbl + suffix
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to resolve bound widget label for slot=$slot id=$wid", t); null
        }
    }

    fun ensureCandidatesLoaded() {
        if (candidates.isNotEmpty() || loading) return
        loading = true
        val appCtx = activity.applicationContext
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { loadInstalledWidgets(appCtx) }
            candidates.clear(); candidates.addAll(loaded); loading = false
        }
    }

    private fun loadInstalledWidgets(appCtx: Context): List<WidgetCandidateItem> {
        BridgeClient.ensureAppWidgetPermission()
        val pm = appCtx.packageManager
        return AppWidgetManager.getInstance(appCtx).installedProviders.orEmpty().map { info ->
            val title = info.loadLabel(pm) ?: info.provider.shortClassName
            val appLabel = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() }
                .getOrDefault(info.provider.packageName)
            val icon = runCatching {
                info.loadIcon(appCtx, DisplayMetrics.DENSITY_HIGH) ?: pm.getApplicationIcon(info.provider.packageName)
            }.getOrNull()
            WidgetCandidateItem(info, title, "$appLabel · ${info.minWidth}×${info.minHeight}dp", drawableToImageBitmap(icon, 96))
        }.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    fun bindChosenWidget(slot: Int, info: AppWidgetProviderInfo) {
        val host = AppWidgetHost(activity.applicationContext, MirrorThemeCatalog.MIRROR_WIDGET_HOST_ID)
        val id = host.allocateAppWidgetId()
        val bound = try { AppWidgetManager.getInstance(activity).bindAppWidgetIdIfAllowed(id, info.provider) }
        catch (t: Throwable) { Log.w(TAG, "bindAppWidgetIdIfAllowed threw", t); false }
        if (bound) { finalizeOrConfigureWidget(slot, id, info, host); return }

        pendingSlot = slot; pendingWidgetId = id
        try {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQ_BIND_APPWIDGET)
        } catch (t: Throwable) {
            Log.w(TAG, "ACTION_APPWIDGET_BIND failed", t)
            deleteWidgetSafely(host, id)
            Toast.makeText(activity, "系统小组件绑定失败（请先激活守护进程）", Toast.LENGTH_SHORT).show()
        }
    }

    private fun finalizeOrConfigureWidget(slot: Int, widgetId: Int, info: AppWidgetProviderInfo?, host: AppWidgetHost) {
        val optional = info != null && (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
        if (info?.configure != null && !optional) {
            pendingSlot = slot; pendingWidgetId = widgetId
            try { host.startAppWidgetConfigureActivityForResult(activity, widgetId, 0, REQ_CONF_APPWIDGET, null); return }
            catch (t: Throwable) { Log.w(TAG, "Widget configure activity failed; binding directly", t) }
        }
        AppPrefs.setMirrorWidgetId(activity, slot, widgetId)
        onWidgetsUpdated()
        Toast.makeText(activity, "已绑定至边栏槽位 ${slot + 1}", Toast.LENGTH_SHORT).show()
    }

    fun onActivityResult(requestCode: Int, resultCode: Int) {
        if ((requestCode != REQ_BIND_APPWIDGET && requestCode != REQ_CONF_APPWIDGET) || pendingSlot < 0) return
        val wid = pendingWidgetId; val slot = pendingSlot
        pendingSlot = -1; pendingWidgetId = -1
        val host = AppWidgetHost(activity.applicationContext, MirrorThemeCatalog.MIRROR_WIDGET_HOST_ID)
        if (resultCode != Activity.RESULT_OK || wid <= 0) { if (wid > 0) deleteWidgetSafely(host, wid); return }
        if (requestCode == REQ_BIND_APPWIDGET) {
            finalizeOrConfigureWidget(slot, wid, AppWidgetManager.getInstance(activity).getAppWidgetInfo(wid), host)
        } else {
            AppPrefs.setMirrorWidgetId(activity, slot, wid); onWidgetsUpdated()
        }
    }

    private fun deleteWidgetSafely(host: AppWidgetHost, widgetId: Int) {
        try { host.deleteAppWidgetId(widgetId) } catch (t: Throwable) { Log.d(TAG, "deleteAppWidgetId($widgetId) failed: ${t.message}") }
    }
}

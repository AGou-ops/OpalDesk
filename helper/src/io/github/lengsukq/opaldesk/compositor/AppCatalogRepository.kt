package io.github.lengsukq.opaldesk.compositor

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

data class InstalledAppEntry(
    val pkg: String,
    val activityClass: String,
    val label: String,
)

/**
 * Queries launchable installed Android apps and caches rasterized [ImageBitmap] icons for
 * zero-jank rendering inside window headers, dock slots, and the searchable app drawer.
 */
object AppCatalogRepository {
    private val iconCache = object : LruCache<String, ImageBitmap>(128) {}

    @Volatile
    private var cachedApps: List<InstalledAppEntry> = emptyList()

    suspend fun loadLaunchableApps(ctx: Context, forceReload: Boolean = false): List<InstalledAppEntry> {
        if (!forceReload && cachedApps.isNotEmpty()) return cachedApps
        return withContext(Dispatchers.IO) {
            val pm = ctx.applicationContext.packageManager
            val selfPkg = ctx.packageName
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(query, 0)
            val collator = Collator.getInstance(Locale.getDefault())
            val seen = HashSet<String>()
            val out = ArrayList<InstalledAppEntry>(resolved.size)

            for (ri in resolved) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName ?: continue
                if (pkg == selfPkg || pkg == "com.android.systemui" || !seen.add(pkg)) continue
                val label = ri.loadLabel(pm).toString().trim().ifEmpty { pkg }
                out.add(
                    InstalledAppEntry(
                        pkg = pkg,
                        activityClass = ai.name.orEmpty(),
                        label = label,
                    ),
                )
            }
            out.sortWith { a, b -> collator.compare(a.label, b.label) }
            cachedApps = out
            out
        }
    }

    fun getAppIcon(ctx: Context, pkg: String): ImageBitmap? {
        if (pkg.isEmpty()) return null
        iconCache.get(pkg)?.let { return it }
        return try {
            val drawable = ctx.applicationContext.packageManager.getApplicationIcon(pkg)
            drawableToImageBitmap(drawable, 96)?.also { iconCache.put(pkg, it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun drawableToImageBitmap(drawable: Drawable, targetPx: Int): ImageBitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
            return drawable.bitmap.asImageBitmap()
        }
        val w = drawable.intrinsicWidth.coerceIn(48, targetPx)
        val h = drawable.intrinsicHeight.coerceIn(48, targetPx)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bmp.asImageBitmap()
    }
}

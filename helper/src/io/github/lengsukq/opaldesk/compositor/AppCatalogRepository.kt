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
    private const val TARGET_ICON_PX = 112
    private val iconCache = object : LruCache<String, ImageBitmap>(512) {}

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

    /** O(1) non-blocking memory lookup safe to call directly during Compose scroll frames. */
    fun getCachedIcon(pkg: String): ImageBitmap? {
        if (pkg.isEmpty()) return null
        return iconCache.get(pkg)
    }

    /** Decodes and caches an app icon strictly on [Dispatchers.IO] without ever blocking UI frames. */
    suspend fun loadAppIconAsync(ctx: Context, pkg: String): ImageBitmap? {
        if (pkg.isEmpty()) return null
        iconCache.get(pkg)?.let { return it }
        return withContext(Dispatchers.IO) {
            getAppIcon(ctx, pkg)
        }
    }

    /** Background pre-warmer so opening & scrolling the App Drawer hits 100% RAM cache at 120fps. */
    suspend fun prewarmIcons(ctx: Context, apps: List<InstalledAppEntry>) = withContext(Dispatchers.IO) {
        val appCtx = ctx.applicationContext
        for (entry in apps) {
            if (iconCache.get(entry.pkg) == null) {
                getAppIcon(appCtx, entry.pkg)
            }
        }
    }

    fun getAppIcon(ctx: Context, pkg: String): ImageBitmap? {
        if (pkg.isEmpty()) return null
        iconCache.get(pkg)?.let { return it }
        return try {
            val drawable = ctx.applicationContext.packageManager.getApplicationIcon(pkg)
            drawableToImageBitmap(drawable, TARGET_ICON_PX)?.also { iconCache.put(pkg, it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun drawableToImageBitmap(drawable: Drawable, targetPx: Int): ImageBitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
            val src = drawable.bitmap
            if (src.width <= targetPx && src.height <= targetPx) {
                return src.asImageBitmap()
            }
            val scaled = Bitmap.createScaledBitmap(src, targetPx, targetPx, true)
            return scaled.asImageBitmap()
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

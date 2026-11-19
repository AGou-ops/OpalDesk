package com.extbridge.helper.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.icu.text.Transliterator
import android.util.Log
import java.util.Collections
import java.util.Locale

data class LauncherAppEntry(
    val label: String,
    val pkg: String,
    val cls: String,
    val searchBlob: String,
    val icon: Drawable?,
)

/**
 * Loads launchable Android apps with ICU Han-Latin pinyin & initials indexing for instant desktop search.
 */
object LauncherAppRepository {
    private const val TAG = "ExtLauncherRepo"
    private val TOKEN_SPLIT_REGEX = Regex("[\\s\\p{Punct}]+")

    fun loadInstalledApps(context: Context, excludeClassName: String): List<LauncherAppEntry> {
        val pm = context.packageManager
        val queryIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(queryIntent, 0)
        Collections.sort(resolved, ResolveInfo.DisplayNameComparator(pm))

        val hanLatin = try {
            Transliterator.getInstance("Han-Latin; NFD; [:Nonspacing Mark:] Remove; NFC")
        } catch (t: Throwable) {
            Log.w(TAG, "ICU Han-Latin transliterator unavailable; falling back to plain label/package search", t)
            null
        }

        val selfPkg = context.packageName
        return buildList(resolved.size) {
            for (ri in resolved) {
                val ai = ri.activityInfo ?: continue
                if (selfPkg == ai.packageName && excludeClassName == ai.name) continue
                val label = ri.loadLabel(pm).toString()
                val icon = try {
                    ri.loadIcon(pm)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to load launcher icon for ${ai.packageName}", t)
                    null
                }
                add(
                    LauncherAppEntry(
                        label = label,
                        pkg = ai.packageName,
                        cls = ai.name,
                        searchBlob = buildSearchBlob(label, ai.packageName, hanLatin),
                        icon = icon,
                    ),
                )
            }
        }
    }

    fun filterApps(all: List<LauncherAppEntry>, normalizedQuery: String): List<LauncherAppEntry> {
        if (normalizedQuery.isEmpty()) return all
        return all.filter { it.searchBlob.contains(normalizedQuery) }
    }

    fun buildSearchBlob(label: String?, pkg: String?, hanLatin: Transliterator?): String {
        val sb = StringBuilder()
        val lbl = label?.lowercase(Locale.ROOT).orEmpty()
        sb.append(lbl).append(' ').append(pkg?.lowercase(Locale.ROOT).orEmpty())
        if (hanLatin != null && lbl.isNotEmpty()) {
            try {
                val pinyin = hanLatin.transliterate(lbl).lowercase(Locale.ROOT)
                sb.append(' ').append(pinyin).append(' ').append(pinyin.replace(" ", ""))
                sb.append(' ')
                for (tok in pinyin.split(TOKEN_SPLIT_REGEX)) {
                    if (tok.isNotEmpty()) sb.append(tok[0])
                }
            } catch (t: Throwable) {
                Log.d(TAG, "Pinyin transliteration failed for '$lbl': ${t.message}")
            }
        }
        return sb.toString()
    }
}

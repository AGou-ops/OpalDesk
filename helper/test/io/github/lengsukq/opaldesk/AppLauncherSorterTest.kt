package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.ui.AppLauncherSorter
import io.github.lengsukq.opaldesk.ui.UnifiedAppItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppLauncherSorterTest {

    private val sampleApps = listOf(
        UnifiedAppItem(pkg = "com.alpha", activityClass = ".A", label = "Alpha", openCount = 1),
        UnifiedAppItem(pkg = "com.wechat", activityClass = ".W", label = "WeChat", openCount = 9),
        UnifiedAppItem(pkg = "com.bili", activityClass = ".B", label = "Bilibili", openCount = 4),
        UnifiedAppItem(pkg = "com.beta", activityClass = ".Be", label = "Beta", openCount = 1),
        UnifiedAppItem(pkg = "com.zero", activityClass = ".Z", label = "Zeta", openCount = 0),
    )

    @Test
    fun sortsByOpenCountDescendingWithAlphabeticalTieBreakWhenEnabled() {
        val sorted = AppLauncherSorter.filterAndSort(sampleApps, query = "", sortByUsage = true, locale = Locale.US)
        assertEquals(
            listOf("com.wechat", "com.bili", "com.alpha", "com.beta", "com.zero"),
            sorted.map { it.pkg },
        )
    }

    @Test
    fun sortsAlphabeticallyWhenUsageSortingIsDisabled() {
        val sorted = AppLauncherSorter.filterAndSort(sampleApps, query = "", sortByUsage = false, locale = Locale.US)
        assertEquals(
            listOf("com.alpha", "com.beta", "com.bili", "com.wechat", "com.zero"),
            sorted.map { it.pkg },
        )
    }

    @Test
    fun searchFilterRespectsActiveSortMode() {
        val usageFiltered = AppLauncherSorter.filterAndSort(sampleApps, query = "b", sortByUsage = true, locale = Locale.US)
        assertEquals(listOf("com.bili", "com.beta"), usageFiltered.map { it.pkg })

        val alphaFiltered = AppLauncherSorter.filterAndSort(sampleApps, query = "b", sortByUsage = false, locale = Locale.US)
        assertEquals(listOf("com.beta", "com.bili"), alphaFiltered.map { it.pkg })
    }

    @Test
    fun launchCountsRoundTripSerializationIgnoresMalformedLines() {
        val serialized = AppPrefs.serializeLaunchCounts(
            linkedMapOf("com.tencent.mm" to 7, "tv.danmaku.bili" to 3, "invalid" to 0),
        )
        val parsed = AppPrefs.parseLaunchCounts("$serialized\nbadline\n=9\ncom.foo=-2\n")
        assertEquals(mapOf("com.tencent.mm" to 7, "tv.danmaku.bili" to 3), parsed)
    }
}

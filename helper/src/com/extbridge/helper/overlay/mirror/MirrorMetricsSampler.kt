package com.extbridge.helper.overlay.mirror

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Immutable snapshot of local device metrics rendered by Native Mirror telemetry cards.
 * Replaces 11 separate `@Volatile` fields with a single atomic reference publication.
 */
data class MirrorLocalMetrics(
    val valid: Boolean = false,
    val memAvailGb: Double = 0.0,
    val memTotalGb: Double = 0.0,
    val memPct: Int = 50,
    val storageAvailGb: Double = 0.0,
    val storageTotalGb: Double = 0.0,
    val storagePct: Int = 50,
    val d0BrightPct: Int = 50,
    val rxRateBytesPerSec: Double = 0.0,
    val txRateBytesPerSec: Double = 0.0,
    val rxTotalMb: Double = 0.0,
)

/**
 * Background sampler for RAM, storage, screen brightness, and live network throughput
 * used by [MirrorSidebarController].
 */
internal class MirrorMetricsSampler {

    companion object {
        private const val TAG = "ExtMirrorMetrics"
        private const val BYTES_PER_KB = 1024.0
        private const val BYTES_PER_MB = 1024.0 * 1024.0
        private const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0

        fun formatSpeedRate(bytesPerSec: Double): String = when {
            bytesPerSec < BYTES_PER_KB -> String.format(Locale.US, "%.0f B/s", max(0.0, bytesPerSec))
            bytesPerSec < BYTES_PER_MB -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / BYTES_PER_KB)
            else -> String.format(Locale.US, "%.2f MB/s", bytesPerSec / BYTES_PER_MB)
        }

        fun formatCountdown(totalSec: Long): String {
            val s = max(0L, totalSec)
            return String.format(Locale.US, "%02d:%02d", s / 60L, s % 60L)
        }
    }

    @Volatile
    var snapshot: MirrorLocalMetrics = MirrorLocalMetrics()
        private set

    private var lastNetSampleMs: Long = 0L
    private var lastRxBytes: Long = -1L
    private var lastTxBytes: Long = -1L

    fun sample(ctx: Context) {
        val prev = snapshot
        var memAvailGb = prev.memAvailGb
        var memTotalGb = prev.memTotalGb
        var memPct = prev.memPct
        var storageAvailGb = prev.storageAvailGb
        var storageTotalGb = prev.storageTotalGb
        var storagePct = prev.storagePct
        var d0BrightPct = prev.d0BrightPct
        var rxRate = prev.rxRateBytesPerSec
        var txRate = prev.txRateBytesPerSec
        var rxTotalMb = prev.rxTotalMb

        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                val used = max(0L, mi.totalMem - mi.availMem)
                memPct = if (mi.totalMem > 0L) (used * 100f / mi.totalMem).roundToInt() else 50
                memAvailGb = mi.availMem / BYTES_PER_GB
                memTotalGb = mi.totalMem / BYTES_PER_GB
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to sample RAM metrics", t)
        }

        try {
            val nowMs = SystemClock.elapsedRealtime()
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            if (rx >= 0L && tx >= 0L) {
                if (lastNetSampleMs > 0L && lastRxBytes >= 0L && lastTxBytes >= 0L && nowMs > lastNetSampleMs) {
                    val sec = (nowMs - lastNetSampleMs) / 1000.0
                    rxRate = max(0.0, (rx - lastRxBytes) / sec)
                    txRate = max(0.0, (tx - lastTxBytes) / sec)
                }
                rxTotalMb = rx / BYTES_PER_MB
                lastNetSampleMs = nowMs
                lastRxBytes = rx
                lastTxBytes = tx
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to sample TrafficStats", t)
        }

        try {
            val sf = StatFs(Environment.getDataDirectory().path)
            val total = sf.totalBytes
            val avail = sf.availableBytes
            val used = max(0L, total - avail)
            storagePct = if (total > 0L) (used * 100f / total).roundToInt() else 50
            storageAvailGb = avail / BYTES_PER_GB
            storageTotalGb = total / BYTES_PER_GB
            val br = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            d0BrightPct = (br * 100f / 255f).roundToInt().coerceIn(1, 100)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to sample storage/brightness metrics", t)
        }

        snapshot = MirrorLocalMetrics(
            valid = true,
            memAvailGb = memAvailGb,
            memTotalGb = memTotalGb,
            memPct = memPct,
            storageAvailGb = storageAvailGb,
            storageTotalGb = storageTotalGb,
            storagePct = storagePct,
            d0BrightPct = d0BrightPct,
            rxRateBytesPerSec = rxRate,
            txRateBytesPerSec = txRate,
            rxTotalMb = rxTotalMb,
        )
    }
}

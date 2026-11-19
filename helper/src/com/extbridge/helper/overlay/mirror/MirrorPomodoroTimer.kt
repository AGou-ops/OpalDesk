package com.extbridge.helper.overlay.mirror

import android.os.Handler
import android.os.SystemClock
import kotlin.math.max

/**
 * Self-contained 25-minute Pomodoro countdown timer separated from sidebar View rendering.
 */
internal class MirrorPomodoroTimer(
    private val handler: Handler,
    private val isDestroyed: () -> Boolean,
    private val onStateChanged: () -> Unit,
) {
    companion object {
        private const val DEFAULT_DURATION_SEC = 25L * 60L
        private const val TICK_INTERVAL_MS = 1000L
    }

    var isRunning: Boolean = false
        private set
    var remainSec: Long = DEFAULT_DURATION_SEC
        private set

    private var lastTickMs: Long = 0L

    private val tickTask: Runnable = object : Runnable {
        override fun run() {
            if (isDestroyed() || !isRunning) return
            advanceElapsed()
            onStateChanged()
            if (isRunning && !isDestroyed()) {
                handler.postDelayed(this, TICK_INTERVAL_MS)
            }
        }
    }

    fun toggle() {
        if (isRunning) {
            advanceElapsed()
            isRunning = false
            handler.removeCallbacks(tickTask)
        } else {
            if (remainSec <= 0L) remainSec = DEFAULT_DURATION_SEC
            isRunning = true
            lastTickMs = SystemClock.elapsedRealtime()
            handler.removeCallbacks(tickTask)
            handler.postDelayed(tickTask, TICK_INTERVAL_MS)
        }
        onStateChanged()
    }

    fun reset() {
        isRunning = false
        remainSec = DEFAULT_DURATION_SEC
        lastTickMs = 0L
        handler.removeCallbacks(tickTask)
        onStateChanged()
    }

    fun syncSchedule(hasVisibleCard: Boolean) {
        handler.removeCallbacks(tickTask)
        if (hasVisibleCard && isRunning && !isDestroyed()) {
            advanceElapsed()
            if (isRunning) handler.postDelayed(tickTask, TICK_INTERVAL_MS)
        }
    }

    fun stopCallbacks() {
        handler.removeCallbacks(tickTask)
    }

    private fun advanceElapsed() {
        if (!isRunning) return
        val now = SystemClock.elapsedRealtime()
        if (lastTickMs <= 0L) {
            lastTickMs = now
            return
        }
        val deltaSec = (now - lastTickMs) / 1000L
        if (deltaSec > 0L) {
            remainSec = max(0L, remainSec - deltaSec)
            lastTickMs += deltaSec * 1000L
            if (remainSec == 0L) {
                isRunning = false
                handler.removeCallbacks(tickTask)
            }
        }
    }
}

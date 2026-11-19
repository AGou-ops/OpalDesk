package com.extbridge.helper.overlay

import android.util.Log
import com.extbridge.helper.StageTransitionView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reusable frame-0 snapshot transition choreographer for [StageTransitionView].
 *
 * Eliminates the non-atomic `intArrayOf(-1)` 45ms latch race: if the backend RPC finishes
 * before frame-0 posts on the main thread (or after the latch times out), [AtomicInteger]
 * + [AtomicBoolean] guarantee `notifyBackendReady(token)` is delivered exactly once without
 * stalling until the 240ms watchdog.
 */
class StageChoreographer(private val host: OverlayHost) {

    companion object {
        private const val TAG = "ExtStageChoreo"
        const val DEFAULT_FRAME0_TIMEOUT_MS = 45L
    }

    /**
     * Runs a complete frame-0 stage animation lifecycle on [host]:
     * 1. [prepareCards] runs on the background executor to capture live TaskSnapshots and build [StageTransitionView.CardSpec]s.
     * 2. Posts frame-0 animation start via [startAnimation] on the UI thread, acquiring the active token atomically.
     * 3. Awaits frame-0 up to [frame0TimeoutMs], executes [performBackend] on the background executor,
     *    and delivers `notifyBackendReady(token)` after [backendReadyDelayMs].
     * 4. Invokes [onBackendResultUi] on the UI thread with the result of [performBackend].
     *
     * Any unused [StageTransitionView.CardSpec] bitmaps are recycled automatically if the animation surface detaches.
     */
    fun <C, R> runTransition(
        frame0TimeoutMs: Long = DEFAULT_FRAME0_TIMEOUT_MS,
        backendReadyDelayMs: Long = 0L,
        notifyBackendReady: Boolean = true,
        prepareCards: () -> C,
        extractCards: (C) -> List<StageTransitionView.CardSpec?>,
        startAnimation: (anim: StageTransitionView, prepared: C, onFinished: Runnable) -> Unit,
        performBackend: (prepared: C) -> R,
        onBackendResultUi: ((prepared: C, result: R) -> Unit)? = null,
    ) {
        host.safeBgExecute {
            val prepared = prepareCards()
            val token = AtomicInteger(-1)
            val backendDone = AtomicBoolean(false)
            val readyDelivered = AtomicBoolean(false)
            val frame0Latch = CountDownLatch(1)

            fun deliverBackendReady(tok: Int) {
                if (!notifyBackendReady || tok <= 0 || !readyDelivered.compareAndSet(false, true)) return
                if (backendReadyDelayMs > 0L) {
                    host.safePost {
                        host.handler.postDelayed({
                            host.stageAnimView?.notifyBackendReady(tok)
                        }, backendReadyDelayMs)
                    }
                } else {
                    host.stageAnimView?.notifyBackendReady(tok)
                }
            }

            host.safePost {
                val anim = host.stageAnimView
                if (anim == null || !anim.isAttachedToWindow || host.extCtx == null) {
                    recycleCards(extractCards(prepared))
                    frame0Latch.countDown()
                    return@safePost
                }
                try {
                    host.beginWindowOverlayStage()
                    startAnimation(anim, prepared, Runnable {
                        host.endWindowOverlayStage()
                        host.pollRemoteStateAsync()
                    })
                    val tok = anim.getActiveToken()
                    token.set(tok)
                    if (backendDone.get()) {
                        deliverBackendReady(tok)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Frame-0 transition start failed", t)
                    recycleCards(extractCards(prepared))
                    host.endWindowOverlayStage()
                } finally {
                    frame0Latch.countDown()
                }
            }

            try {
                if (!frame0Latch.await(frame0TimeoutMs, TimeUnit.MILLISECONDS)) {
                    Log.d(TAG, "Frame-0 latch exceeded ${frame0TimeoutMs}ms; proceeding with backend RPC")
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            val result = performBackend(prepared)
            backendDone.set(true)
            val tok = token.get()
            if (tok > 0) {
                deliverBackendReady(tok)
            }

            if (onBackendResultUi != null) {
                host.safePost { onBackendResultUi(prepared, result) }
            }
        }
    }

    private fun recycleCards(cards: List<StageTransitionView.CardSpec?>) {
        for (card in cards) {
            OverlayUiUtils.recycleUnusedCard(card)
        }
    }
}

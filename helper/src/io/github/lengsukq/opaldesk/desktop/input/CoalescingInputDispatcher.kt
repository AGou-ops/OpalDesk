package io.github.lengsukq.opaldesk.desktop.input

import java.util.concurrent.Executor

/** Collapses consecutive moves while keeping their position relative to input transitions. */
internal class CoalescingInputDispatcher<T>(
    private val executor: Executor,
    private val dispatch: (T) -> Unit,
) {
    private class Pending<T>(val key: Int, var event: T)

    private val lock = Any()
    private var pending: Pending<T>? = null

    fun submit(event: T, coalescingKey: Int?) {
        if (coalescingKey == null) {
            submit { dispatch(event) }
            return
        }

        synchronized(lock) {
            val previous = pending
            if (previous != null && previous.key == coalescingKey) {
                previous.event = event
                return
            }

            val slot = Pending(coalescingKey, event)
            pending = slot
            executor.execute {
                val latest = synchronized(lock) {
                    if (pending === slot) pending = null
                    slot.event
                }
                dispatch(latest)
            }
        }
    }

    fun submit(action: () -> Unit) {
        synchronized(lock) {
            // The existing slot will still deliver its move, ahead of this action.
            pending = null
            executor.execute { action() }
        }
    }
}

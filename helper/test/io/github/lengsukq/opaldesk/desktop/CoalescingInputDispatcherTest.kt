package io.github.lengsukq.opaldesk.desktop

import io.github.lengsukq.opaldesk.desktop.input.CoalescingInputDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

class CoalescingInputDispatcherTest {
    @Test
    fun `moves cannot pass a touch or keyboard transition even if the worker is behind`() {
        val work = ArrayDeque<Runnable>()
        val received = mutableListOf<String>()
        val dispatcher = CoalescingInputDispatcher<String>({ work.addLast(it) }, { received.add(it) })

        dispatcher.submit("down", null)
        dispatcher.submit("stale move", 1)
        dispatcher.submit("latest move before up", 1)
        dispatcher.submit("up", null)
        dispatcher.submit("hover before key", 2)
        dispatcher.submit { received.add("key") }
        dispatcher.submit("hover after key", 2)
        dispatcher.submit("next drag", 1)
        while (work.isNotEmpty()) work.removeFirst().run()

        assertEquals(
            listOf("down", "latest move before up", "up", "hover before key", "key", "hover after key", "next drag"),
            received,
        )
    }
}

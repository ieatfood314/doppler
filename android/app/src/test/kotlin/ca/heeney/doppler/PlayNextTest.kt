package ca.heeney.doppler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayNextTest {
    private val order = listOf("a", "b", "c", "d")

    @Test fun laterTrackMovesDirectlyAfterCurrent() {
        val move = playNextMove(order, 1, "d")!!
        assertEquals(3, move.from)
        assertEquals(2, move.to)
        assertEquals(listOf("a", "b", "d", "c"), move.order)
        assertEquals("b", move.order[1])
    }

    @Test fun earlierTrackMovesWithoutLosingCurrent() {
        val move = playNextMove(order, 2, "a")!!
        assertEquals(0, move.from)
        assertEquals(2, move.to)
        assertEquals(listOf("b", "c", "a", "d"), move.order)
        assertEquals("c", move.order[1])
    }

    @Test fun currentNextMissingAndInvalidRunsStayUnchanged() {
        assertNull(playNextMove(order, 1, "b"))
        assertNull(playNextMove(order, 1, "c"))
        assertNull(playNextMove(order, 1, "missing"))
        assertNull(playNextMove(order, 4, "a"))
        assertNull(playNextMove(emptyList(), 0, "a"))
    }

    @Test fun lastChoiceBecomesImmediateNext() {
        val first = playNextMove(order, 0, "c")!!
        val second = playNextMove(first.order, 0, "d")!!
        assertEquals(listOf("a", "d", "c", "b"), second.order)
    }
}

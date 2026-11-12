package ca.heeney.doppler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CoreTest {
    private val tracks = listOf("1/a.flac", "1/b.flac", "1/c.flac", "2/x.mp3", "Album/Disc 1/01 - one.flac",
        "Album/Disc 1/02 - two.flac", "Album/Disc 2/10 - ten.flac", "Album/Disc 2/9 - nine.flac")

    @Test fun foldersNest() {
        val f = Core.folders(tracks)
        assertEquals(setOf("f:", "f:1", "f:2", "f:Album", "f:Album/Disc 1", "f:Album/Disc 2"), f.keys)
        assertEquals(4, f["f:Album"]!!.size)
    }

    @Test fun naturalSort() {
        assertEquals(listOf("Album/Disc 2/9 - nine.flac", "Album/Disc 2/10 - ten.flac"),
            Core.playlistTracks("f:Album/Disc 2", tracks, emptyMap()))
        assertEquals(3, Core.playlistTracks("f:1", tracks + "10/z.flac", emptyMap()).size)
    }

    @Test fun customDropsMissing() {
        val custom = mapOf("c:1" to CustomPlaylist("c:1", "Mine", listOf("2/x.mp3", "gone.flac", "1/a.flac")))
        assertEquals(listOf("2/x.mp3", "1/a.flac"), Core.playlistTracks("c:1", tracks, custom))
    }

    @Test fun slots() {
        assertEquals(mapOf("1" to "f:1", "2" to "f:2"), Core.defaultSlots(Core.folders(tracks).keys))
        assertEquals(mapOf("1" to "f:Jazz", "2" to "f:Rock"), Core.defaultSlots(setOf("f:", "f:Rock", "f:Jazz", "f:Jazz/Live")))
    }

    @Test fun hashMatchesPython() {
        // python3 -c 'from doppler import core; print(core.order_hash(["1/a.flac","1/b.flac"]))'
        assertEquals("8b0e2b2e4a8f".length, Core.orderHash(listOf("1/a.flac", "1/b.flac")).length)
        assertEquals(PY_HASH, Core.orderHash(listOf("1/a.flac", "1/b.flac")))
    }

    @Test fun titles() {
        assertEquals("Van Halen" to "Jump (2015 Remaster)", Core.splitTitle("1/Van Halen - Jump (2015 Remaster).flac"))
        assertEquals("" to "Californication", Core.splitTitle("x/07 - Californication.flac"))
        assertEquals("Vignette" to "Panacea", Core.splitTitle("x/01. Vignette - Panacea.flac"))
        assertEquals("" to "No Escape", Core.splitTitle("x/01 No Escape.mp3"))
        assertEquals("" to "1979", Core.splitTitle("x/1979.mp3"))
    }

    @Test fun reconcileKeepsSpotAndAddsAhead() {
        val list = (0 until 20).map { "1/$it.flac" }
        val rng = Random(7)
        val order = Core.newOrder(list, "shuffle", rng)
        assertNotEquals(list, order)
        val current = order[6]
        val fit = Core.reconcile(order, 6, list + "1/new.flac", "shuffle", rng)
        assertTrue(fit.sameTrack)
        assertEquals(current, fit.order[fit.index])
        assertTrue(fit.order.indexOf("1/new.flac") > fit.index)
        val gone = Core.reconcile(order, 6, list - order[6], "shuffle", rng)
        assertFalse(gone.sameTrack)
        assertEquals(order[7], gone.order[gone.index])
    }

    @Test fun reshuffleAvoidsRepeat() {
        repeat(50) { seed ->
            assertNotEquals("1/0.flac", Core.newOrder(listOf("1/0.flac", "1/1.flac", "1/2.flac"), "shuffle", Random(seed), avoidFirst = "1/0.flac")[0])
        }
    }

    @Test fun resuming() {
        assertEquals(42.0, Core.livePosition(30.0, true, "desk", 1000.0, 1012.0, "phone"), 0.001)
        assertEquals(30.0, Core.livePosition(30.0, true, "phone", 1000.0, 1012.0, "phone"), 0.001)
        assertEquals(30.0, Core.livePosition(30.0, true, "desk", 1000.0, 1100.0, "phone"), 0.001)
        assertEquals(1 to 0.0, Core.resumePoint(listOf("a", "b"), 0, 199.0, 200.0))
        assertEquals(0 to 50.0, Core.resumePoint(listOf("a", "b"), 0, 50.0, 200.0))
    }

    companion object {
        const val PY_HASH = "88196cdf38d7"
    }
}

package ca.heeney.doppler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.YearMonth

class YearHeardTest {
    private val heard = listOf(MonthHeard("2026-11", 600, 2, 2, emptyList(), emptyList()))

    @Test fun theStoryIsOutInDecemberAndJanuary() {
        assertEquals(2026, YearHeard.season(YearMonth.of(2026, 12)))
        assertEquals(2026, YearHeard.season(YearMonth.of(2027, 1)))
        assertNull(YearHeard.season(YearMonth.of(2026, 11)))
    }

    @Test fun listeningOffersAYearOnceItIsOver() {
        assertNull(YearHeard.storyFor(YearMonth.of(2026, 11), YearMonth.of(2026, 11), heard))
        assertEquals(2026, YearHeard.storyFor(YearMonth.of(2026, 11), YearMonth.of(2026, 12), heard))
        assertEquals(2026, YearHeard.storyFor(YearMonth.of(2027, 1), YearMonth.of(2027, 1), heard))
        assertEquals(2026, YearHeard.storyFor(YearMonth.of(2026, 3), YearMonth.of(2027, 6), heard))
        assertNull(YearHeard.storyFor(YearMonth.of(2025, 3), YearMonth.of(2027, 6), heard))
    }
}

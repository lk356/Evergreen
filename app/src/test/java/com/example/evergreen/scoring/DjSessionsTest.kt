package com.example.evergreen.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test 5 of `docs/PHASE3_PLAN.md` section 9: windows from commentary
 * timestamps; a play 19 minutes after commentary is inside, 21 minutes is
 * outside; no commentary means no windows.
 */
class DjSessionsTest {

    private val minute = 60_000L
    private val base = 1_789_128_000_000L

    private fun minutesAfterBase(minutes: Long) = base + minutes * minute

    @Test
    fun `no commentary means no windows`() {
        assertEquals(emptyList<DjWindow>(), DjSessions.infer(emptyList()))
        assertFalse(DjSessions.isDuringSession(emptyList(), base))
    }

    @Test
    fun `one commentary row opens one window`() {
        val windows = DjSessions.infer(listOf(base))

        assertEquals(1, windows.size)
        assertEquals(base, windows.single().startMs)
        assertEquals(base + DjSessions.WINDOW_MS, windows.single().endMs)
    }

    @Test
    fun `a play 19 minutes after commentary is inside the session`() {
        val windows = DjSessions.infer(listOf(base))

        assertTrue(DjSessions.isDuringSession(windows, minutesAfterBase(19)))
    }

    @Test
    fun `a play 21 minutes after commentary is outside it`() {
        val windows = DjSessions.infer(listOf(base))

        assertFalse(DjSessions.isDuringSession(windows, minutesAfterBase(21)))
    }

    @Test
    fun `a play before any commentary is outside`() {
        val windows = DjSessions.infer(listOf(base))

        assertFalse(DjSessions.isDuringSession(windows, base - minute))
    }

    /**
     * The DJ speaks every few songs, so a real session is a run of commentary
     * rows. They become one window rather than a pile of overlapping ones.
     */
    @Test
    fun `commentary every few minutes is one continuous session`() {
        val commentary = listOf(0L, 4, 9, 13, 18).map { minutesAfterBase(it) }

        val windows = DjSessions.infer(commentary)

        assertEquals(1, windows.size)
        assertEquals(base, windows.single().startMs)
        // Extended by the last commentary row, not by the first.
        assertEquals(minutesAfterBase(18) + DjSessions.WINDOW_MS, windows.single().endMs)
        assertTrue(DjSessions.isDuringSession(windows, minutesAfterBase(30)))
        assertFalse(DjSessions.isDuringSession(windows, minutesAfterBase(39)))
    }

    @Test
    fun `two sessions an afternoon apart stay separate`() {
        val commentary = listOf(minutesAfterBase(0), minutesAfterBase(240))

        val windows = DjSessions.infer(commentary)

        assertEquals(2, windows.size)
        assertTrue(DjSessions.isDuringSession(windows, minutesAfterBase(5)))
        assertFalse(DjSessions.isDuringSession(windows, minutesAfterBase(120)))
        assertTrue(DjSessions.isDuringSession(windows, minutesAfterBase(245)))
    }

    @Test
    fun `windows come back oldest first whatever order the rows arrive in`() {
        val windows = DjSessions.infer(
            listOf(minutesAfterBase(240), minutesAfterBase(0), minutesAfterBase(480)),
        )

        assertEquals(3, windows.size)
        assertEquals(windows.map { it.startMs }.sorted(), windows.map { it.startMs })
    }

    @Test
    fun `windows never overlap`() {
        val commentary = (0L..60 step 7).map { minutesAfterBase(it) } +
            (300L..360 step 3).map { minutesAfterBase(it) }

        val windows = DjSessions.infer(commentary)

        windows.zipWithNext().forEach { (earlier, later) ->
            assertTrue("$earlier overlaps $later", earlier.endMs < later.startMs)
        }
    }

    @Test
    fun `duplicate timestamps collapse into one window`() {
        val windows = DjSessions.infer(listOf(base, base, base))

        assertEquals(1, windows.size)
    }

    /** Judged on when the listen began, since that is when the choice was made. */
    @Test
    fun `a song begun inside the window counts even if it ends outside`() {
        val windows = DjSessions.infer(listOf(base))

        assertTrue(
            "started at 19 minutes, so the DJ chose it",
            DjSessions.isDuringSession(windows, minutesAfterBase(19)),
        )
    }

    @Test
    fun `the window length can be varied for testing`() {
        val windows = DjSessions.infer(listOf(base), windowMs = 5 * minute)

        assertTrue(DjSessions.isDuringSession(windows, minutesAfterBase(4)))
        assertFalse(DjSessions.isDuringSession(windows, minutesAfterBase(6)))
    }

    @Test
    fun `the default window is twenty minutes`() {
        assertEquals(20 * minute, DjSessions.WINDOW_MS)
    }
}

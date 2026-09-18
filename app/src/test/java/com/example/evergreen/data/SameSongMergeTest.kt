package com.example.evergreen.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Planning a same-song merge. Pure decisions, no database.
 */
class SameSongMergeTest {

    private fun candidate(
        isrc: String,
        trackId: Long,
        album: String,
        segments: Int = 0,
    ) = MergeCandidate(
        isrc = isrc,
        trackId = trackId,
        title = "Dracula",
        artist = "An Artist",
        album = album,
        segmentCount = segments,
    )

    /** The real pair from the user's library on 2026-09-17. */
    @Test
    fun aSingleAndAnAlbumReleaseCollapseIntoOne() {
        val plan = SameSongMerge.plan(
            listOf(
                candidate("USQX92504223", 1, "Deadbeat"),
                candidate("USQX92504223", 2, "Dracula"),
            ),
        )

        assertEquals(1, plan.size)
        assertEquals(1, plan.single().losers.size)
    }

    /**
     * The survivor is whichever holds the most listening history, so a merge
     * moves as few segments as it can.
     */
    @Test
    fun theTrackWithMoreListeningHistorySurvives() {
        val plan = SameSongMerge.plan(
            listOf(
                candidate("ISRC1", trackId = 1, album = "Single", segments = 2),
                candidate("ISRC1", trackId = 2, album = "Album", segments = 40),
            ),
        )

        assertEquals(2L, plan.single().survivor.trackId)
        assertEquals(listOf(1L), plan.single().losers.map { it.trackId })
        assertEquals(2, plan.single().segmentsToMove)
    }

    /** Ties go to the track seen first, so the plan is deterministic. */
    @Test
    fun aTieGoesToTheOlderTrack() {
        val plan = SameSongMerge.plan(
            listOf(
                candidate("ISRC1", trackId = 7, album = "B", segments = 3),
                candidate("ISRC1", trackId = 3, album = "A", segments = 3),
            ),
        )

        assertEquals(3L, plan.single().survivor.trackId)
    }

    /**
     * The same data must plan the same merge every time, whatever order the
     * rows arrive in -- otherwise a dry run says nothing about the real run.
     */
    @Test
    fun theSameDataAlwaysPlansTheSameMerge() {
        val rows = listOf(
            candidate("ISRC1", 1, "A", segments = 5),
            candidate("ISRC1", 2, "B", segments = 5),
            candidate("ISRC1", 3, "C", segments = 9),
        )

        val first = SameSongMerge.plan(rows)
        val shuffled = SameSongMerge.plan(rows.reversed())

        assertEquals(first.single().survivor.trackId, shuffled.single().survivor.trackId)
        assertEquals(
            first.single().losers.map { it.trackId },
            shuffled.single().losers.map { it.trackId },
        )
    }

    /** Three releases of one recording collapse to one, not to two merges. */
    @Test
    fun threeReleasesOfOneRecordingLeaveASingleSurvivor() {
        val plan = SameSongMerge.plan(
            listOf(
                candidate("ISRC1", 1, "Single"),
                candidate("ISRC1", 2, "Album"),
                candidate("ISRC1", 3, "Deluxe"),
            ),
        )

        assertEquals(1, plan.size)
        assertEquals(2, plan.single().losers.size)
    }

    @Test
    fun distinctRecordingsAreNeverPlannedForMerging() {
        val plan = SameSongMerge.plan(
            listOf(
                candidate("ISRC1", 1, "A"),
                candidate("ISRC2", 2, "B"),
            ),
        )

        assertTrue(plan.isEmpty())
    }

    /**
     * One canonical track can hold several service rows sharing an ISRC -- the
     * same recording reissued under two Spotify URIs but already unified. There
     * is nothing to merge.
     */
    @Test
    fun oneTrackWithTwoServiceRowsIsNotAMerge() {
        val plan = SameSongMerge.plan(
            listOf(
                candidate("ISRC1", trackId = 1, album = "A"),
                candidate("ISRC1", trackId = 1, album = "A"),
            ),
        )

        assertTrue(plan.isEmpty())
    }

    @Test
    fun nothingToDoIsAnEmptyPlan() {
        assertTrue(SameSongMerge.plan(emptyList()).isEmpty())
    }
}

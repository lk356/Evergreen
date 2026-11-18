package com.example.evergreen.ui

import com.example.evergreen.scoring.AdjustmentKind
import com.example.evergreen.scoring.ContributionKind
import com.example.evergreen.scoring.ContributionNote
import com.example.evergreen.scoring.ScoreAdjustment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The track screen's wording for the scoring-freshness notes and modifiers. */
class DescribeAdjustmentTest {

    @Test
    fun aStreakSaysWhichDayAndByHowMuch() {
        assertEquals(
            "skip day 3 in a row, ×4: -0.5 → -2",
            describeAdjustment(ScoreAdjustment(AdjustmentKind.SKIP_STREAK, -0.5, -2.0, streakDays = 3, multiplier = 4.0)),
        )
    }

    @Test
    fun theCapAndTheCeilingShowBeforeAndAfter() {
        assertEquals(
            "trimmed by the daily skip limit: -0.5 → 0",
            describeAdjustment(ScoreAdjustment(AdjustmentKind.SKIP_CAP, -0.5, 0.0)),
        )
        assertTrue(describeAdjustment(ScoreAdjustment(AdjustmentKind.CEILING, 1.0, 0.25)).endsWith("1 → 0.25"))
    }

    @Test
    fun theSummaryListsOnlyFreshnessSettingsThatAreOn() {
        assertEquals(emptyList<String>(), freshnessInForce(com.example.evergreen.scoring.ScoringParameters()).map { it.title })
        val some = com.example.evergreen.scoring.ScoringParameters(scoreCeiling = 8.0, rapidRunMinSkips = 3)
        // The ceiling and runs themselves; a flick's length and cost show too
        // once runs are on, but only when they differ from off's display.
        assertEquals(listOf("Ceiling on a song's score", "Flicking through songs"), freshnessInForce(some).map { it.title })
    }

    @Test
    fun everyNoteHasWords() {
        ContributionNote.entries.forEach { note ->
            assertTrue(note.name, describe(ContributionKind.LISTEN, note).length > 4)
        }
    }
}

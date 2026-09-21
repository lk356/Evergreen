package com.example.evergreen.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The statement schema v8 uses to fill `utcOffsetMinutes` on six years of
 * existing rows.
 *
 * Tested as a string rather than against a database, because what can go wrong
 * here is the *arithmetic of daylight saving*, not SQLite. The migration test
 * covers the execution.
 */
class OffsetBackfillTest {

    private val london = ZoneId.of("Europe/London")
    private val newYork = ZoneId.of("America/New_York")
    private val utc = ZoneId.of("UTC")
    private val kolkata = ZoneId.of("Asia/Kolkata")

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `a zone with no transitions needs no CASE at all`() {
        val sql = OffsetBackfill.statement(
            utc.rules,
            ms("2020-01-01T00:00:00Z"),
            ms("2026-09-15T00:00:00Z"),
        )

        assertFalse("CASE ELSE x END is not valid SQLite", sql.contains("CASE"))
        assertTrue(sql, sql.contains("SET `utcOffsetMinutes` = 0 "))
    }

    @Test
    fun `a half-hour zone is expressed in minutes, not hours`() {
        // India is UTC+5:30. A column of hours could not hold it, which is why
        // the column is minutes.
        val sql = OffsetBackfill.statement(
            kolkata.rules,
            ms("2020-01-01T00:00:00Z"),
            ms("2026-09-15T00:00:00Z"),
        )

        assertTrue(sql, sql.contains("= 330 "))
    }

    @Test
    fun `a DST zone produces one branch per transition in range`() {
        val sql = OffsetBackfill.statement(
            london.rules,
            ms("2020-01-01T00:00:00Z"),
            ms("2026-09-15T00:00:00Z"),
        )

        // Two transitions a year over six and a half years.
        val branches = sql.split("WHEN ").size - 1
        assertTrue("expected roughly 13 branches, got $branches in $sql", branches in 12..14)
        assertTrue("winter is UTC+0", sql.contains("THEN 0 "))
        assertTrue("summer is UTC+1", sql.contains("THEN 60 "))
    }

    @Test
    fun `the branch boundaries are the real transition instants`() {
        // British Summer Time began at 01:00 UTC on 2021-03-28 and ended at
        // 01:00 UTC on 2021-10-31 -- the clocks go back at 02:00 *local*, which
        // is 01:00 UTC, and writing the local time here is exactly the mistake
        // this test exists to catch. A row ending a second before a boundary is
        // on one side of it; a second after, the other. Getting it wrong by an
        // hour is the whole reason the zone's history is consulted rather than
        // today's offset being stamped on everything.
        val sql = OffsetBackfill.statement(
            london.rules,
            ms("2021-01-01T00:00:00Z"),
            ms("2021-12-31T00:00:00Z"),
        )

        val bstStart = ms("2021-03-28T01:00:00Z")
        val gmtReturn = ms("2021-10-31T01:00:00Z")
        assertTrue(sql, sql.contains("WHEN `endedAtMs` < $bstStart THEN 0"))
        assertTrue(sql, sql.contains("WHEN `endedAtMs` < $gmtReturn THEN 60"))
    }

    @Test
    fun `negative offsets survive the trip through the string`() {
        val sql = OffsetBackfill.statement(
            newYork.rules,
            ms("2024-01-01T00:00:00Z"),
            ms("2024-12-31T00:00:00Z"),
        )

        assertTrue("EST is UTC-5", sql.contains("THEN -300"))
        assertTrue("EDT is UTC-4", sql.contains("THEN -240"))
    }

    @Test
    fun `it only ever touches rows that have no offset`() {
        // The property that makes it idempotent, and that stops a guessed
        // offset overwriting a real one carried in from another device.
        listOf(utc, london, newYork).forEach { zone ->
            val sql = OffsetBackfill.statement(
                zone.rules,
                ms("2020-01-01T00:00:00Z"),
                ms("2026-09-15T00:00:00Z"),
            )
            assertTrue(sql, sql.endsWith("WHERE `utcOffsetMinutes` IS NULL"))
        }
    }

    @Test
    fun `an empty span still produces a usable statement`() {
        // One row, so from and to are the same instant and there is no
        // transition between them.
        val instant = ms("2026-06-01T12:00:00Z")
        val sql = OffsetBackfill.statement(london.rules, instant, instant)

        assertFalse(sql.contains("CASE"))
        assertTrue("June in London is UTC+1", sql.contains("= 60 "))
    }

    @Test
    fun `the branches are ordered oldest first, so the first match wins`() {
        // SQLite takes the first matching WHEN. Ascending boundaries are what
        // make "the earliest boundary I am below" the right period.
        val sql = OffsetBackfill.statement(
            london.rules,
            ms("2020-01-01T00:00:00Z"),
            ms("2026-09-15T00:00:00Z"),
        )

        val boundaries = Regex("WHEN `endedAtMs` < (\\d+)")
            .findAll(sql)
            .map { it.groupValues[1].toLong() }
            .toList()

        assertEquals(boundaries.sorted(), boundaries)
    }
}

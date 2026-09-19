package com.example.evergreen.ui

import com.example.evergreen.data.RankedTrackRow
import com.example.evergreen.data.SnapshotEntryEntity
import com.example.evergreen.scoring.ScoringParameters
import com.example.evergreen.spotify.SpotifyConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The home screen's rules about what counts as broken.
 *
 * The status line's whole value is that it is silent when nothing is wrong: a
 * warning that is always on screen is one nobody reads, and the point of this
 * phase is not having to open the app to know it is working. So these tests are
 * as much about what does *not* appear as what does.
 *
 * Pure JVM tests, because [buildStatus] deliberately takes plain values rather
 * than reading the database itself.
 */
class HomeStatusTest {

    /** 2026-09-18T12:00:00Z, a Friday in the middle of the month. */
    private val now = 1_789_214_400_000L
    private val day = TimeUnit.DAYS.toMillis(1)
    private val utc = ZoneId.of("UTC")

    private val connected = SpotifyConnectionState.Connected(
        authorizedAtMs = now - 30 * day,
        refreshTokenExpiresAtMs = now + 150 * day,
        grantedScopes = "user-library-read playlist-modify-private playlist-read-private",
    )

    /** A device where everything is working, as the Walkman is meant to be. */
    private fun healthy(
        accessGranted: Boolean = true,
        configured: Boolean = true,
        connection: SpotifyConnectionState? = connected,
        lastCapturedAtMs: Long? = now - 2 * TimeUnit.HOURS.toMillis(1),
        lastLikeSyncAtMs: Long? = now - 6 * TimeUnit.HOURS.toMillis(1),
        lastWrittenAtMs: Long? = now - 8 * TimeUnit.HOURS.toMillis(1),
        writeConfirmed: Boolean = true,
        isWriter: Boolean = true,
        backoffUntilMs: Long? = null,
        latestSnapshotMonthKey: String? = "2026-08",
    ) = buildStatus(
        nowMs = now,
        accessGranted = accessGranted,
        configured = configured,
        connection = connection,
        lastCapturedAtMs = lastCapturedAtMs,
        lastLikeSyncAtMs = lastLikeSyncAtMs,
        lastWrittenAtMs = lastWrittenAtMs,
        writeConfirmed = writeConfirmed,
        isWriter = isWriter,
        backoffUntilMs = backoffUntilMs,
        latestSnapshotMonthKey = latestSnapshotMonthKey,
        zone = utc,
    )

    @Test
    fun aWorkingDeviceSaysNothingAtAll() {
        assertTrue(
            "the status line must be silent when nothing is wrong",
            healthy().isEmpty(),
        )
    }

    @Test
    fun noNotificationAccessIsTheWorstThingThatCanBeWrong() {
        val problems = healthy(accessGranted = false)
        assertEquals(StatusProblem.Severity.BROKEN, problems.first().severity)
        assertTrue(problems.first().headline.contains("Nothing is being recorded"))
        assertEquals(StatusProblem.Action.NOTIFICATION_ACCESS, problems.first().action)
    }

    /**
     * The failure this phase exists to catch.
     *
     * A paused sync catches up when it is fixed; plays that were never recorded
     * are gone. Capture rests on an undocumented Spotify broadcast setting and
     * on media-session behaviour reverse-engineered on a device, so it can stop
     * while everything else keeps running.
     */
    @Test
    fun captureGoingQuietForAWeekIsBroken() {
        val problems = healthy(lastCapturedAtMs = now - 8 * day)
        val quiet = problems.single { it.headline == "Capture has gone quiet" }
        assertEquals(StatusProblem.Severity.BROKEN, quiet.severity)
    }

    @Test
    fun aQuietWeekendIsNotAFault() {
        assertTrue(
            "six days of silence must not raise anything -- a holiday is not a bug",
            healthy(lastCapturedAtMs = now - 6 * day).isEmpty(),
        )
    }

    @Test
    fun anExpiredLoginIsBrokenAndOffersAReconnect() {
        val problems = healthy(connection = SpotifyConnectionState.Expired(now - day))
        val expired = problems.single { it.headline == "Reconnect Spotify" }
        assertEquals(StatusProblem.Severity.BROKEN, expired.severity)
        assertEquals(StatusProblem.Action.RECONNECT, expired.action)
    }

    @Test
    fun anExpiringLoginIsOnlyAWarning() {
        val soon = SpotifyConnectionState.ExpiringSoon(
            authorizedAtMs = now - 160 * day,
            refreshTokenExpiresAtMs = now + 12 * day,
            grantedScopes = "user-library-read",
        )
        val problems = healthy(connection = soon)
        val warning = problems.single { it.headline == "Reconnect soon" }
        assertEquals(StatusProblem.Severity.WARNING, warning.severity)
        assertTrue("it should say how long is left", warning.detail.contains("12 days"))
    }

    /**
     * Only one device writes the playlist. On the other one a playlist that has
     * never been written is the correct state, not a fault — so saying so there
     * would be teaching the user to ignore the status line.
     */
    @Test
    fun aStalePlaylistWriteIsOnlyNewsOnTheWriterDevice() {
        val onWriter = healthy(isWriter = true, lastWrittenAtMs = now - 5 * day)
        assertTrue(onWriter.any { it.headline == "Evergreen has not been updated" })

        val onOther = healthy(isWriter = false, lastWrittenAtMs = null, writeConfirmed = false)
        assertTrue(
            "the non-writer device has nothing to report about the playlist",
            onOther.none { it.headline.contains("Evergreen") },
        )
    }

    @Test
    fun oneMissedDailyRunIsNotYetNews() {
        assertTrue(
            healthy(lastWrittenAtMs = now - 30 * TimeUnit.HOURS.toMillis(1)).isEmpty(),
        )
    }

    @Test
    fun aMissedMonthlySnapshotIsNoticed() {
        // now is mid-September, so August is the last completed month.
        assertTrue(healthy(latestSnapshotMonthKey = "2026-08").isEmpty())

        val problems = healthy(latestSnapshotMonthKey = "2026-07")
        assertTrue(problems.any { it.headline == "A monthly snapshot was missed" })
    }

    @Test
    fun anUnconfiguredBuildSaysSoRatherThanLookingLikeALostLogin() {
        val problems = healthy(configured = false, connection = null)
        val problem = problems.single { it.headline == "Spotify is not configured" }
        assertEquals(StatusProblem.Severity.BROKEN, problem.severity)
        assertTrue(
            "the worktree trap is the likely cause and should be named",
            problem.detail.contains("worktree"),
        )
    }

    @Test
    fun anOpenBackoffWindowIsReportedWithoutAlarm() {
        val problems = healthy(backoffUntilMs = now + TimeUnit.HOURS.toMillis(3))
        val problem = problems.single { it.headline == "Waiting on Spotify's rate limit" }
        assertEquals(StatusProblem.Severity.WARNING, problem.severity)
    }

    @Test
    fun brokenThingsAreListedBeforeWarnings() {
        val problems = healthy(
            accessGranted = false,
            lastWrittenAtMs = now - 5 * day,
            backoffUntilMs = now + TimeUnit.HOURS.toMillis(1),
        )
        assertTrue(problems.size >= 2)
        assertEquals(StatusProblem.Severity.BROKEN, problems.first().severity)
        assertTrue(
            problems.dropWhile { it.severity == StatusProblem.Severity.BROKEN }
                .all { it.severity == StatusProblem.Severity.WARNING },
        )
    }

    // --- Near the line -------------------------------------------------------

    private fun rankedRow(rank: Int, inPlaylist: Boolean) = RankedTrackRow(
        trackId = rank.toLong(),
        score = 10.0 - rank / 100.0,
        rank = rank,
        title = "Track $rank",
        artist = "Artist",
        durationMs = 200_000,
        inPlaylist = inPlaylist,
    )

    /**
     * Hysteresis makes the two sides asymmetric, and getting that wrong is the
     * whole risk here: measuring both against rank 100 would fill the list with
     * members in no danger at all and leave out the ones about to drop.
     */
    @Test
    fun nearTheLineRespectsHysteresis() {
        val parameters = ScoringParameters()
        val ranked = listOf(
            rankedRow(95, inPlaylist = true),
            rankedRow(101, inPlaylist = false),
            rankedRow(104, inPlaylist = false),
            rankedRow(120, inPlaylist = true),
            rankedRow(128, inPlaylist = true),
            rankedRow(140, inPlaylist = false),
        )

        val near = nearTheLine(ranked, parameters)
        val ids = near.map { it.row.rank }

        assertTrue("101 is one place off joining", 101 in ids)
        assertTrue("104 is four places off joining", 104 in ids)
        assertTrue("128 is three places off dropping past 130", 128 in ids)

        assertTrue("a member at 95 is nowhere near the leave rank of 130", 95 !in ids)
        assertTrue("a member at 120 is still eleven clear of dropping", 120 !in ids)
        assertTrue("a non-member at 140 is forty off joining", 140 !in ids)

        assertTrue(near.single { it.row.rank == 101 }.approachingJoin)
        assertTrue(!near.single { it.row.rank == 128 }.approachingJoin)
        assertEquals(1, near.single { it.row.rank == 101 }.distance)
        assertEquals(3, near.single { it.row.rank == 128 }.distance)
    }

    /**
     * Distance means the same thing on both sides: places still to move.
     *
     * The two rules are not written the same way — a track joins at the join
     * rank *or better*, but leaves only once it falls *below* the leave rank —
     * so reading the second as `leave - rank` gives zero for a member that is
     * still comfortably in the playlist. On screen that said "0 off dropping"
     * about a song at rank 130 that has not gone anywhere.
     */
    @Test
    fun aMemberSittingExactlyOnTheLeaveRankIsOnePlaceAwayNotZero() {
        val parameters = ScoringParameters()
        val near = nearTheLine(
            listOf(
                rankedRow(parameters.playlistLeaveRank, inPlaylist = true),
                rankedRow(parameters.playlistJoinRank + 1, inPlaylist = false),
            ),
            parameters,
        )

        // Rank 130 is still a member; it leaves at 131.
        assertEquals(1, near.single { !it.approachingJoin }.distance)
        // Rank 101 is not a member; it joins at 100. The mirror image.
        assertEquals(1, near.single { it.approachingJoin }.distance)
    }

    /**
     * A track sitting exactly on the join rank is in, and has nothing to
     * announce — it is not "0 off joining".
     */
    @Test
    fun aTrackOnTheJoinRankIsNotNearAnything() {
        val parameters = ScoringParameters()
        val near = nearTheLine(
            listOf(rankedRow(parameters.playlistJoinRank, inPlaylist = true)),
            parameters,
        )
        assertTrue(near.isEmpty())
    }

    @Test
    fun nearTheLineIsOrderedByHowCloseTheChangeIs() {
        val ranked = listOf(
            rankedRow(106, inPlaylist = false),
            rankedRow(103, inPlaylist = false),
            rankedRow(130, inPlaylist = true),
        )
        val near = nearTheLine(ranked, ScoringParameters())
        // 130 is one place off dropping, 103 three off joining, 106 six off.
        assertEquals(listOf(130, 103, 106), near.map { it.row.rank })
        assertEquals(listOf(1, 3, 6), near.map { it.distance })
    }

    // --- The month diff ------------------------------------------------------

    private fun entry(trackId: Long, rank: Int, inPlaylist: Boolean) = SnapshotEntryEntity(
        id = trackId * 1000 + rank,
        snapshotId = 1,
        trackId = trackId,
        rank = rank,
        score = 5.0,
        serviceTrackId = "spotify:track:$trackId",
        title = "Track $trackId",
        artist = "Artist",
        inPlaylist = inPlaylist,
    )

    @Test
    fun consecutiveSnapshotsSubtract() {
        val august = listOf(
            entry(1, 1, true),
            entry(2, 2, true),
            entry(3, 150, false),
        )
        val september = listOf(
            entry(2, 1, true),
            entry(3, 2, true),
            entry(1, 200, false),
        )

        val diff = monthDiff(current = september, previous = august)

        assertEquals("3 was ranked but out of the playlist in August", listOf(3L), diff.joined.map { it.trackId })
        assertEquals("1 fell out of the playlist", listOf(1L), diff.left.map { it.trackId })
        assertEquals(listOf(2L, 3L), diff.top.map { it.trackId })
    }

    @Test
    fun theFirstSnapshotHasNothingToSubtractFrom() {
        val first = listOf(entry(1, 1, true), entry(2, 2, true))
        val diff = monthDiff(current = first, previous = emptyList())
        assertEquals(listOf(1L, 2L), diff.joined.map { it.trackId })
        assertTrue(diff.left.isEmpty())
    }

    @Test
    fun anUnchangedMonthDiffsToNothing() {
        val entries = listOf(entry(1, 1, true), entry(2, 2, true))
        // Same tracks, different ranks and scores: the playlist did not change
        // even though everything underneath it moved.
        val later = listOf(entry(2, 1, true), entry(1, 2, true))
        val diff = monthDiff(current = later, previous = entries)
        assertTrue(diff.joined.isEmpty())
        assertTrue(diff.left.isEmpty())
    }

    // --- Ages ----------------------------------------------------------------

    @Test
    fun agesReadAsNumbersRatherThanTimestamps() {
        assertEquals("moments", describeAge(30_000))
        assertEquals("1 minute", describeAge(TimeUnit.MINUTES.toMillis(1)))
        assertEquals("40 minutes", describeAge(TimeUnit.MINUTES.toMillis(40)))
        assertEquals("1 hour", describeAge(TimeUnit.HOURS.toMillis(1)))
        assertEquals("3 days", describeAge(TimeUnit.DAYS.toMillis(3)))
        assertEquals("3 months", describeAge(TimeUnit.DAYS.toMillis(90)))
        // A clock that has gone backwards must not produce "-2 days".
        assertEquals("moments", describeAge(-5_000))
    }
}

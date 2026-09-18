package com.example.evergreen.scoring

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.evergreen.data.EndReason
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.ListeningSegmentEntity
import com.example.evergreen.data.MembershipReason
import com.example.evergreen.data.SERVICE_SPOTIFY
import com.example.evergreen.data.EvergreenMembershipEntity
import com.example.evergreen.data.trackKeyOf
import com.example.evergreen.spotify.ApiResult
import com.example.evergreen.spotify.ApiSource
import com.example.evergreen.spotify.PlaylistItemsPage
import com.example.evergreen.spotify.PlaylistPage
import com.example.evergreen.spotify.PlaylistSummary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * Test 16 of `docs/PHASE3_PLAN.md` section 9: a snapshot stores the parameters
 * JSON and the full ranking; export creates a playlist with the top 100 in
 * order.
 */
@RunWith(AndroidJUnit4::class)
class SnapshotTest {

    private class FakeApi : ApiSource {
        val contents = mutableMapOf<String, MutableList<String>>()
        var created: PlaylistSummary? = null
        var createCalls = 0

        override fun savedTracks(limit: Int, offset: Int) = error("not used")
        override fun savedTracksAt(url: String) = error("not used")
        override fun track(trackId: String) = error("not used")
        override fun myPlaylists(limit: Int, offset: Int): ApiResult<PlaylistPage> =
            ApiResult.Ok(PlaylistPage(emptyList(), 0, null))

        override fun createPlaylist(name: String, description: String): ApiResult<PlaylistSummary> {
            createCalls++
            val summary = PlaylistSummary("export_$createCalls", name, "me", isPublic = false)
            contents[summary.id] = mutableListOf()
            created = summary
            return ApiResult.Ok(summary)
        }

        override fun playlistItems(playlistId: String, offset: Int): ApiResult<PlaylistItemsPage> =
            ApiResult.Ok(PlaylistItemsPage(contents[playlistId].orEmpty(), 0, null))

        override fun addToPlaylist(playlistId: String, uris: List<String>): ApiResult<String> {
            contents.getOrPut(playlistId) { mutableListOf() }.addAll(uris)
            return ApiResult.Ok("snapshot")
        }

        override fun removeFromPlaylist(playlistId: String, uris: List<String>) =
            error("a snapshot export must never remove anything")
    }

    private lateinit var db: EvergreenDatabase
    private val api = FakeApi()
    private val zone = ZoneId.of("Europe/London")

    /** 2026-08-31T12:00:00Z — the last day of August. */
    private val lastDayOfAugust = 1_788_177_600_000L

    /** 2026-08-15T12:00:00Z — mid-month. */
    private val midAugust = 1_786_795_200_000L

    private var clock = lastDayOfAugust

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvergreenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = lastDayOfAugust
    }

    @After
    fun tearDown() = db.close()

    private fun snapshots() = Snapshots(db, zone = zone, now = { clock })

    /** Likes [count] tracks and marks the first [inPlaylist] as playlist members. */
    private suspend fun buildLibrary(count: Int, inPlaylist: Int) {
        repeat(count) { index ->
            val title = "Track %03d".format(index)
            val uri = "spotify:track:t%03d".format(index)
            val duration = 200_000L
            db.dao().recordSegment(
                trackKey = trackKeyOf(title, "Artist", "Album"),
                title = title,
                artist = "Artist",
                album = "Album",
                durationMs = duration,
                service = SERVICE_SPOTIFY,
                serviceTrackId = uri,
                segment = ListeningSegmentEntity(
                    trackId = 0,
                    serviceTrackRowId = null,
                    appPackage = "com.spotify.music",
                    deviceId = "test-device",
                    // Staggered so the ranking is a strict order rather than a
                    // pile of ties, which would make "in order" untestable.
                    startedAtMs = clock - index * 60_000L - duration,
                    endedAtMs = clock - index * 60_000L,
                    startPositionMs = 0,
                    endPositionMs = duration,
                    listenedMs = duration,
                    trackDurationMs = duration,
                    forwardSeekCount = 0,
                    seekedForwardMs = 0,
                    endReason = EndReason.TRACK_CHANGE,
                    endJumpFromMs = null,
                    endJumpToMs = null,
                    lastSampleAgeMs = 100,
                    createdAt = clock,
                ),
            )
            val rowId = db.spotifyDao().resolveServiceTrack(
                trackKey = trackKeyOf(title, "Artist", "Album"),
                title = title,
                artist = "Artist",
                album = "Album",
                durationMs = duration,
                service = SERVICE_SPOTIFY,
                serviceTrackId = uri,
                isrc = null,
                now = clock,
            )
            db.spotifyDao().recordLiked(rowId, serviceAddedAtMs = clock, now = clock)
        }

        val ranked = ScoreService(db).ranking(clock, force = true).tracks
        db.scoringDao().recordMembershipChanges(
            joined = ranked.take(inPlaylist)
                .map { Triple(it.trackId, MembershipReason.JOINED_BY_RANK, it.rank) },
            left = emptyList(),
            at = clock,
        )
    }

    // --- When one is taken -----------------------------------------------------

    @Test
    fun aSnapshotIsTakenOnTheLastDayOfTheMonth() = runBlocking {
        buildLibrary(count = 10, inPlaylist = 5)

        val result = snapshots().takeIfDue()

        assertTrue(result.toString(), result is SnapshotResult.Taken)
        assertEquals("2026-08", (result as SnapshotResult.Taken).monthKey)
        assertEquals("2026 August", result.label)
    }

    /** The daily worker must do nothing on the other thirty days. */
    @Test
    fun nothingHappensOnAnyOtherDay() = runBlocking {
        buildLibrary(count = 5, inPlaylist = 3)
        clock = midAugust

        assertEquals(SnapshotResult.NotDue, snapshots().takeIfDue())
        assertEquals(0, db.scoringDao().snapshotCount())
    }

    /** Running twice on the last day must not make two. */
    @Test
    fun aSecondRunInTheSameMonthDoesNothing() = runBlocking {
        buildLibrary(count = 5, inPlaylist = 3)
        snapshots().takeIfDue()

        val second = snapshots().takeIfDue()

        assertTrue(second is SnapshotResult.AlreadyExists)
        assertEquals(1, db.scoringDao().snapshotCount())
    }

    @Test
    fun aForcedSnapshotIgnoresTheDate() = runBlocking {
        buildLibrary(count = 5, inPlaylist = 3)
        clock = midAugust

        val result = snapshots().takeIfDue(force = true)

        assertTrue(result is SnapshotResult.Taken)
    }

    @Test
    fun anEmptyLibraryTakesNoSnapshot() = runBlocking {
        val result = snapshots().takeIfDue()

        assertTrue(result is SnapshotResult.Empty)
        assertEquals(0, db.scoringDao().snapshotCount())
    }

    // --- What it stores --------------------------------------------------------

    /**
     * A score means nothing without the settings that produced it. Without this
     * the first retune turns every earlier snapshot into a column of numbers on
     * a scale nobody can reconstruct.
     */
    @Test
    fun aSnapshotStoresTheParametersInForce() = runBlocking {
        val tuned = ScoringParameters(halfLifeDays = 60.0, likeWeight = 4.0)
        db.scoringDao().putScoringParameters(tuned)
        buildLibrary(count = 5, inPlaylist = 3)

        snapshots().takeIfDue()

        val stored = db.scoringDao().snapshotForMonth("2026-08")!!
        assertEquals(tuned.toJson(), stored.parametersJson)
        // And it reads back as the very same settings, years later.
        assertEquals(tuned, ScoringParameters.fromJson(stored.parametersJson))
    }

    /**
     * The whole ranking, not just the playlist. Keeping the top hundred would
     * answer "what was in Evergreen in August" and lose "what was still alive
     * in August" for good.
     */
    @Test
    fun aSnapshotStoresTheWholeRankingNotJustThePlaylist() = runBlocking {
        buildLibrary(count = 20, inPlaylist = 5)

        snapshots().takeIfDue()

        val stored = db.scoringDao().snapshotForMonth("2026-08")!!
        val entries = db.scoringDao().snapshotEntries(stored.id)

        assertEquals(20, entries.size)
        assertEquals(20, stored.trackCount)
        assertEquals(5, stored.playlistSize)
        assertEquals(5, entries.count { it.inPlaylist })
        assertEquals(15, entries.count { !it.inPlaylist })
    }

    @Test
    fun entriesAreStoredInRankOrderWithTheirScores() = runBlocking {
        buildLibrary(count = 10, inPlaylist = 4)

        snapshots().takeIfDue()

        val entries = db.scoringDao()
            .snapshotEntries(db.scoringDao().snapshotForMonth("2026-08")!!.id)

        assertEquals((1..10).toList(), entries.map { it.rank })
        assertEquals(entries.map { it.score }.sortedDescending(), entries.map { it.score })
        assertTrue(entries.all { it.score > 0 })
    }

    /**
     * The title, artist and URI are copied in rather than looked up later, so a
     * track that goes unavailable on Spotify — as several of the 380 already
     * have — stays visible in the month it was alive.
     */
    @Test
    fun eachEntryCarriesItsOwnTitleAndUri() = runBlocking {
        buildLibrary(count = 3, inPlaylist = 3)

        snapshots().takeIfDue()

        val entries = db.scoringDao()
            .snapshotEntries(db.scoringDao().snapshotForMonth("2026-08")!!.id)

        entries.forEach { entry ->
            assertNotNull("every entry needs a title", entry.title)
            assertEquals("Artist", entry.artist)
            assertTrue(
                entry.serviceTrackId.orEmpty(),
                entry.serviceTrackId.orEmpty().startsWith("spotify:track:"),
            )
        }
    }

    // --- Export ----------------------------------------------------------------

    @Test
    fun exportCreatesANewPlaylistWithThePlaylistEntriesInOrder() = runBlocking {
        buildLibrary(count = 20, inPlaylist = 6)
        snapshots().takeIfDue()
        val snapshot = db.scoringDao().snapshotForMonth("2026-08")!!

        val playlistId = snapshots().export(snapshot.id, api)

        assertNotNull(playlistId)
        assertEquals(1, api.createCalls)
        assertEquals("Evergreen — 2026 August", api.created!!.name)
        assertEquals("an export must never be public", false, api.created!!.isPublic)

        val exported = api.contents.getValue(playlistId!!)
        assertEquals("only the playlist entries, not the whole ranking", 6, exported.size)

        val expected = db.scoringDao().snapshotPlaylistEntries(snapshot.id)
            .mapNotNull { it.serviceTrackId }
        assertEquals("in rank order, best first", expected, exported)
    }

    @Test
    fun exportIsRecordedOnTheSnapshot() = runBlocking {
        buildLibrary(count = 5, inPlaylist = 3)
        snapshots().takeIfDue()
        val snapshot = db.scoringDao().snapshotForMonth("2026-08")!!

        snapshots().export(snapshot.id, api)

        val after = db.scoringDao().snapshotForMonth("2026-08")!!
        assertEquals(api.created!!.id, after.exportedPlaylistId)
        assertEquals(clock, after.exportedAt)
    }

    /**
     * A new playlist every time, never touched again. That is what makes an
     * export safe: it cannot overwrite anything, and the user can rename or
     * delete it without the app disagreeing.
     */
    @Test
    fun exportingTwiceCreatesTwoPlaylistsAndModifiesNeither() = runBlocking {
        buildLibrary(count = 5, inPlaylist = 3)
        snapshots().takeIfDue()
        val snapshot = db.scoringDao().snapshotForMonth("2026-08")!!

        val first = snapshots().export(snapshot.id, api)!!
        val second = snapshots().export(snapshot.id, api)!!

        assertEquals(2, api.createCalls)
        assertFalse(first == second)
        assertEquals(3, api.contents.getValue(first).size)
        assertEquals(3, api.contents.getValue(second).size)
    }

    @Test
    fun exportingAnUnknownSnapshotDoesNothing() = runBlocking {
        assertNull(snapshots().export(9999, api))
        assertEquals(0, api.createCalls)
    }

    @Test
    fun exportingASnapshotWithNoPlaylistEntriesDoesNothing() = runBlocking {
        buildLibrary(count = 5, inPlaylist = 0)
        snapshots().takeIfDue()
        val snapshot = db.scoringDao().snapshotForMonth("2026-08")!!

        assertNull(snapshots().export(snapshot.id, api))
        assertEquals(0, api.createCalls)
    }

    // --- Dates -----------------------------------------------------------------

    @Test
    fun theLastDayOfEveryMonthIsRecognised() {
        assertTrue(Snapshots.isLastDayOfMonth(LocalDate.of(2026, 1, 31)))
        assertTrue(Snapshots.isLastDayOfMonth(LocalDate.of(2026, 4, 30)))
        assertTrue(Snapshots.isLastDayOfMonth(LocalDate.of(2026, 2, 28)))
        // A leap year, which is exactly the case a hand-written schedule gets
        // wrong and a "does today end the month" check cannot.
        assertTrue(Snapshots.isLastDayOfMonth(LocalDate.of(2028, 2, 29)))
        assertFalse(Snapshots.isLastDayOfMonth(LocalDate.of(2028, 2, 28)))
        assertFalse(Snapshots.isLastDayOfMonth(LocalDate.of(2026, 1, 30)))
    }

    @Test
    fun monthKeysSortChronologicallyAndLabelsReadNaturally() {
        assertEquals("2026-08", Snapshots.monthKeyOf(LocalDate.of(2026, 8, 31)))
        assertEquals("2026-01", Snapshots.monthKeyOf(LocalDate.of(2026, 1, 31)))
        assertEquals("2026 August", Snapshots.labelOf(LocalDate.of(2026, 8, 31)))

        val keys = listOf("2026-10", "2026-02", "2025-12").sorted()
        assertEquals(listOf("2025-12", "2026-02", "2026-10"), keys)
    }
}

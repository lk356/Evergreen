package com.example.evergreen.spotify

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.evergreen.data.EndReason
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.ListeningSegmentEntity
import com.example.evergreen.data.SERVICE_SPOTIFY
import com.example.evergreen.data.SyncKeys
import com.example.evergreen.data.trackKeyOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test 15 of `docs/PHASE3_PLAN.md` section 9: a dry run writes nothing; the
 * first write creates the playlist private; the diff applies removes then
 * adds; a 429 backs off and the next run resumes; a second run within 24 h
 * writes nothing; unliking a member removes it.
 *
 * Run against a scripted API, because these are the paths most likely to be
 * wrong and least likely to be exercised by hand — nobody is going to arrange
 * a real quota block to see what happens.
 */
@RunWith(AndroidJUnit4::class)
class EvergreenSyncTest {

    /** A playlist that lives in memory, and answers however the test scripts. */
    private class FakeApi : ApiSource {
        /**
         * Every thread the API was called from.
         *
         * Recorded rather than asserted in place: an assertion thrown on a
         * background thread inside a coroutine can be swallowed or reported
         * against the wrong test, whereas a list the test inspects afterwards
         * always says what happened.
         */
        val callingThreads = mutableSetOf<String>()

        val playlists = mutableListOf<PlaylistSummary>()
        val contents = mutableMapOf<String, MutableList<String>>()
        var created: PlaylistSummary? = null
        var addCalls = 0
        var removeCalls = 0
        var createCalls = 0

        /** Queued failures, consumed one per write. Null means "succeed". */
        val addFailures = ArrayDeque<ApiResult<String>?>()

        /** URIs Spotify will refuse, as an unavailable track would be. */
        val unavailable = mutableSetOf<String>()

        private fun note() {
            callingThreads += Thread.currentThread().name
        }

        override fun savedTracks(limit: Int, offset: Int) = error("not used")
        override fun savedTracksAt(url: String) = error("not used")
        override fun track(trackId: String) = error("not used")

        override fun myPlaylists(limit: Int, offset: Int): ApiResult<PlaylistPage> {
            note()
            return ApiResult.Ok(
                PlaylistPage(items = playlists.toList(), total = playlists.size, next = null),
            )
        }

        override fun createPlaylist(name: String, description: String): ApiResult<PlaylistSummary> {
            note()
            createCalls++
            val summary = PlaylistSummary("pl_$name", name, ownerId = "me", isPublic = false)
            playlists += summary
            contents[summary.id] = mutableListOf()
            created = summary
            return ApiResult.Ok(summary)
        }

        override fun playlistItems(playlistId: String, offset: Int): ApiResult<PlaylistItemsPage> {
            note()
            val all = contents[playlistId] ?: return ApiResult.NotFound
            val page = all.drop(offset).take(SpotifyApi.MAX_PAGE)
            return ApiResult.Ok(
                PlaylistItemsPage(
                    uris = page,
                    total = all.size,
                    next = if (offset + page.size < all.size) "next" else null,
                ),
            )
        }

        override fun addToPlaylist(playlistId: String, uris: List<String>): ApiResult<String> {
            note()
            addCalls++
            addFailures.removeFirstOrNull()?.let { return it }
            if (uris.any { it in unavailable }) {
                // Spotify refuses the batch whole, exactly as it would for one
                // track that is unavailable in this region.
                return ApiResult.Failed(code = 400, retryable = false)
            }
            contents.getOrPut(playlistId) { mutableListOf() }.addAll(uris)
            return ApiResult.Ok("snapshot")
        }

        override fun removeFromPlaylist(playlistId: String, uris: List<String>): ApiResult<String> {
            note()
            removeCalls++
            contents[playlistId]?.removeAll { it in uris }
            return ApiResult.Ok("snapshot")
        }
    }

    private lateinit var db: EvergreenDatabase
    private val api = FakeApi()

    private val t0 = 1_789_128_000_000L
    private var clock = t0
    private val day = 24 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvergreenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = t0
    }

    @After
    fun tearDown() = db.close()

    private fun sync() = EvergreenSync(db = db, api = api, now = { clock })

    /** Likes [count] tracks, each with one full play so they score above zero. */
    private suspend fun likeTracks(count: Int, playedDaysAgo: Int = 0) {
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
                    startedAtMs = clock - playedDaysAgo * day - duration,
                    endedAtMs = clock - playedDaysAgo * day,
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
            db.spotifyDao().recordLiked(rowId, serviceAddedAtMs = clock - index * day, now = clock)
        }
    }

    private suspend fun beTheWriter() = db.importDao().setEvergreenWriter(true)

    private suspend fun confirmWrites() = sync().confirmFirstWrite()

    // --- Guards ----------------------------------------------------------------

    /** Decision 9: only the device the user listens on writes the playlist. */
    @Test
    fun aDeviceThatIsNotTheWriterDoesNothing() = runBlocking {
        likeTracks(3)
        confirmWrites()

        val result = sync().sync()

        assertEquals(EvergreenSyncResult.Outcome.NOT_THE_WRITER, result.outcome)
        assertEquals("no requests at all", 0, api.createCalls)
        assertEquals(0, api.addCalls)
    }

    /**
     * The first write is behind a confirmation, the same pattern as the
     * same-song merge. Until it is given, the diff is computed and shown and
     * nothing is written.
     */
    @Test
    fun theFirstWriteWaitsForConfirmation() = runBlocking {
        likeTracks(3)
        beTheWriter()

        val result = sync().sync()

        assertEquals(EvergreenSyncResult.Outcome.AWAITING_CONFIRMATION, result.outcome)
        assertEquals("the diff is computed and shown", 3, result.change.toAdd.size)
        assertEquals("but nothing is added", 0, api.addCalls)
        assertTrue("the playlist is empty", api.contents.values.all { it.isEmpty() })
    }

    /** A dry run must never leave anything behind, not even an empty playlist. */
    @Test
    fun aDryRunWritesNothingAndCreatesNothing() = runBlocking {
        likeTracks(3)
        beTheWriter()
        confirmWrites()

        val result = sync().dryRun()

        assertEquals(0, api.createCalls)
        assertEquals(0, api.addCalls)
        assertEquals(0, api.removeCalls)
        assertNull(db.scoringDao().syncStateText(SyncKeys.EVERGREEN_PLAYLIST_ID))
        assertTrue(result.message.orEmpty().contains("created on the first write"))
    }

    @Test
    fun aSecondRunWithinTwentyFourHoursWritesNothing() = runBlocking {
        likeTracks(3)
        beTheWriter()
        confirmWrites()
        assertEquals(EvergreenSyncResult.Outcome.OK, sync().sync().outcome)
        val addsAfterFirst = api.addCalls

        clock += 12 * 60 * 60 * 1000
        val second = sync().sync()

        assertEquals(EvergreenSyncResult.Outcome.TOO_SOON, second.outcome)
        assertEquals(addsAfterFirst, api.addCalls)
    }

    @Test
    fun aRunAfterTwentyFourHoursIsAllowed() = runBlocking {
        likeTracks(3)
        beTheWriter()
        confirmWrites()
        sync().sync()

        clock += day + 1
        val second = sync().sync()

        // Nothing has changed, so it is a no-op rather than an error.
        assertEquals(EvergreenSyncResult.Outcome.NO_CHANGES, second.outcome)
    }

    // --- The first write --------------------------------------------------------

    @Test
    fun theFirstWriteCreatesThePlaylistPrivateAndFillsIt() = runBlocking {
        likeTracks(5)
        beTheWriter()
        confirmWrites()

        val result = sync().sync()

        assertEquals(EvergreenSyncResult.Outcome.OK, result.outcome)
        assertEquals(1, api.createCalls)
        assertEquals(EvergreenSync.PLAYLIST_NAME, api.created!!.name)
        assertEquals("Evergreen must never be created public", false, api.created!!.isPublic)
        assertEquals(5, result.added)
        assertEquals(5, api.contents.getValue(api.created!!.id).size)
        // The id is remembered, so discovery by name never happens again.
        assertEquals(api.created!!.id, db.scoringDao().syncStateText(SyncKeys.EVERGREEN_PLAYLIST_ID))
    }

    /**
     * Decision 13: the app adopts an existing playlist by name only when it has
     * no stored id, and only from the user's own playlists.
     */
    @Test
    fun anExistingEvergreenPlaylistIsAdoptedRatherThanDuplicated() = runBlocking {
        api.playlists += PlaylistSummary("existing", "Evergreen", ownerId = "me", isPublic = false)
        api.contents["existing"] = mutableListOf()
        likeTracks(2)
        beTheWriter()
        confirmWrites()

        val result = sync().sync()

        assertEquals("no second playlist", 0, api.createCalls)
        assertEquals("existing", result.playlistId)
        assertEquals(2, api.contents.getValue("existing").size)
    }

    @Test
    fun aPlaylistWithAnotherNameIsLeftAlone() = runBlocking {
        api.playlists += PlaylistSummary("other", "My Mixtape", ownerId = "me", isPublic = false)
        api.contents["other"] = mutableListOf("spotify:track:precious")
        likeTracks(2)
        beTheWriter()
        confirmWrites()

        sync().sync()

        assertEquals(
            "someone else's playlist must be untouched",
            listOf("spotify:track:precious"),
            api.contents.getValue("other"),
        )
    }

    // --- Diffing ---------------------------------------------------------------

    @Test
    fun aSettledPlaylistIsANoOp() = runBlocking {
        likeTracks(3)
        beTheWriter()
        confirmWrites()
        sync().sync()
        val addsAfterFirst = api.addCalls

        clock += day + 1
        val second = sync().sync()

        assertEquals(EvergreenSyncResult.Outcome.NO_CHANGES, second.outcome)
        assertEquals("no writes for a playlist that is already right", addsAfterFirst, api.addCalls)
    }

    /** The playlist is app-owned: a hand-added track goes at the next sync. */
    @Test
    fun aTrackAddedByHandIsRemoved() = runBlocking {
        likeTracks(2)
        beTheWriter()
        confirmWrites()
        sync().sync()
        val playlistId = api.created!!.id
        api.contents.getValue(playlistId) += "spotify:track:addedByHand"

        clock += day + 1
        val result = sync().sync()

        assertEquals(1, result.removed)
        assertTrue("spotify:track:addedByHand" !in api.contents.getValue(playlistId))
    }

    /**
     * Unliking removes a track at the next sync, while its score and archive
     * entry are kept — so the cost of an accidental unlike is one playlist
     * write, not lost history.
     */
    @Test
    fun unlikingAMemberRemovesItFromThePlaylist() = runBlocking {
        likeTracks(3)
        beTheWriter()
        confirmWrites()
        sync().sync()
        val playlistId = api.created!!.id
        assertEquals(3, api.contents.getValue(playlistId).size)

        clock += day + 1
        // What an unlike really looks like: a later full sync sees the other
        // two as still liked and reconciles the one it did not find.
        db.dao().allServiceTracks()
            .filterNot { it.serviceTrackId.endsWith("t000") }
            .forEach { row -> db.spotifyDao().recordLiked(row.id, serviceAddedAtMs = null, now = clock) }
        db.spotifyDao().markUnlikedAfterFullSync(syncStartedAt = clock, now = clock)

        val result = sync().sync()

        assertEquals(1, result.removed)
        assertEquals(2, api.contents.getValue(playlistId).size)
        assertTrue("spotify:track:t000" !in api.contents.getValue(playlistId))
        // The archive keeps it.
        assertEquals(3, db.spotifyDao().archivedLikeCountOnce())
    }

    @Test
    fun removesAreAppliedBeforeAdds() = runBlocking {
        likeTracks(2)
        beTheWriter()
        confirmWrites()
        sync().sync()
        val playlistId = api.created!!.id
        api.contents.getValue(playlistId) += "spotify:track:stale"

        clock += day + 1
        api.addCalls = 0
        api.removeCalls = 0
        sync().sync()

        // Nothing to add here, but the remove definitely ran.
        assertEquals(1, api.removeCalls)
    }

    // --- Failure behaviour ------------------------------------------------------

    /**
     * A 429 stops the run, records the backoff, and does **not** record the
     * write time — which is what lets the next run finish the job, because the
     * diff is recomputed from the live playlist every time.
     */
    @Test
    fun aRateLimitBacksOffAndTheNextRunResumes() = runBlocking {
        likeTracks(150)
        // A playlist bigger than one batch, so there is a second request for
        // the rate limit to land on. With the default cutoff of 100 the first
        // write is a single batch and this path is never reached.
        db.scoringDao().putScoringParameters(
            com.example.evergreen.scoring.ScoringParameters(
                playlistJoinRank = 150,
                playlistLeaveRank = 200,
            ),
            clock,
        )
        beTheWriter()
        confirmWrites()
        // The first batch of 100 succeeds, the second is rate limited.
        api.addFailures += null
        api.addFailures += ApiResult.RateLimited(retryAfterMs = 60_000, quotaExceeded = false)

        val first = sync().sync()
        val playlistId = api.created!!.id

        assertEquals(EvergreenSyncResult.Outcome.BACKED_OFF, first.outcome)
        assertEquals("the first batch went in", 100, api.contents.getValue(playlistId).size)
        assertNull(
            "the write time must not be recorded, or the rest would wait a day",
            db.scoringDao().syncStateMs(SyncKeys.EVERGREEN_LAST_WRITTEN_AT),
        )

        // Past the backoff window, the next run recomputes the diff against the
        // live playlist and finishes the job.
        clock += 61_000
        val second = sync().sync()

        assertEquals(EvergreenSyncResult.Outcome.OK, second.outcome)
        assertEquals("only the 50 that were missing", 50, second.added)
        assertEquals(150, api.contents.getValue(playlistId).size)
        assertEquals(150, db.scoringDao().currentMemberTrackIds().size)
    }

    @Test
    fun aRunInsideTheBackoffWindowDoesNothing() = runBlocking {
        likeTracks(3)
        beTheWriter()
        confirmWrites()
        db.scoringDao().putSyncMs(SyncKeys.API_BACKOFF_UNTIL, clock + 60_000)

        val result = sync().sync()

        assertEquals(EvergreenSyncResult.Outcome.BACKED_OFF, result.outcome)
        assertEquals(0, api.addCalls)
    }

    @Test
    fun aQuotaBlockBacksOffForHours() = runBlocking {
        likeTracks(3)
        beTheWriter()
        confirmWrites()
        api.addFailures += ApiResult.RateLimited(
            retryAfterMs = Backoff.forRateLimit(30, quotaExceeded = true),
            quotaExceeded = true,
        )

        sync().sync()

        val until = db.scoringDao().syncStateMs(SyncKeys.API_BACKOFF_UNTIL)!!
        assertTrue("should back off for hours", until - clock > 60 * 60 * 1000)
    }

    /**
     * Decision 14: an unavailable track is skipped and counted, never fatal.
     * Spotify's desktop shows 380 liked songs and the phone 374; the difference
     * is tracks unavailable in this region.
     */
    @Test
    fun anUnavailableTrackIsSkippedAndTheRestGoIn() = runBlocking {
        likeTracks(5)
        beTheWriter()
        confirmWrites()
        api.unavailable += "spotify:track:t002"

        val result = sync().sync()

        assertEquals(EvergreenSyncResult.Outcome.OK, result.outcome)
        assertEquals("the other four went in", 4, result.added)
        assertEquals(1, result.refused)
        val contents = api.contents.getValue(api.created!!.id)
        assertEquals(4, contents.size)
        assertFalse("spotify:track:t002" in contents)
    }

    @Test
    fun needingAReconnectIsReportedRatherThanFailing() = runBlocking {
        likeTracks(3)
        beTheWriter()
        confirmWrites()
        api.addFailures += ApiResult.NeedsReconnect

        val result = sync().sync()

        assertEquals(EvergreenSyncResult.Outcome.NEEDS_RECONNECT, result.outcome)
        assertTrue(result.message.orEmpty().contains("Reconnect"))
    }

    // --- Threading ---------------------------------------------------------------

    /**
     * The regression test for the crash found during device verification.
     *
     * The debug screen drives this from `rememberCoroutineScope()`, which is
     * Compose's **main-thread** dispatcher. [ApiSource] is a plain blocking
     * interface, so touching it from there threw `NetworkOnMainThreadException`
     * and killed the app. Every existing test passed because they all call
     * through `runBlocking` on the instrumentation thread, and the scheduled
     * worker was fine because `CoroutineWorker` already runs off the main
     * thread — the bug lived only on the route nothing tested.
     *
     * So this drives the sync exactly as the UI does, on `Dispatchers.Main`,
     * and asserts the API was never reached from there. It fails without the
     * `withContext(Dispatchers.IO)` in `EvergreenSync`.
     */
    @Test
    fun theApiIsNeverCalledOnTheMainThread() {
        runBlocking { likeTracks(3) }

        // Main, like the debug screen. runBlocking holds the instrumentation
        // thread, so the main looper stays free to run the coroutine.
        runBlocking(Dispatchers.Main) { sync().dryRun() }

        assertTrue("the dry run should have reached the API", api.callingThreads.isNotEmpty())
        val mainThreadCalls = api.callingThreads.filter { it == "main" }
        assertTrue(
            "the Spotify API was called on the main thread: $mainThreadCalls",
            mainThreadCalls.isEmpty(),
        )
    }

    /** The same promise for a real write, which is the one that does more work. */
    @Test
    fun aWriteAlsoStaysOffTheMainThread() {
        runBlocking {
            likeTracks(3)
            beTheWriter()
            confirmWrites()
        }

        runBlocking(Dispatchers.Main) { sync().sync() }

        assertTrue(api.addCalls > 0)
        assertTrue(
            "the Spotify API was called on the main thread: ${api.callingThreads}",
            api.callingThreads.none { it == "main" },
        )
    }

    // --- Membership is recorded -------------------------------------------------

    @Test
    fun joiningAndLeavingAreRecordedWithReasons() = runBlocking {
        likeTracks(3)
        beTheWriter()
        confirmWrites()
        sync().sync()

        assertEquals(3, db.scoringDao().currentMemberTrackIds().size)
        val membership = db.scoringDao().currentMembership()
        assertTrue(membership.all { it.leftAt == null })
        assertTrue(membership.all { it.rankAtJoin in 1..3 })
    }
}

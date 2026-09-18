package com.example.evergreen.spotify

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.evergreen.data.EndReason
import com.example.evergreen.data.EvergreenDatabase
import com.example.evergreen.data.IsrcLookupError
import com.example.evergreen.data.LikeEventType
import com.example.evergreen.data.ListeningSegmentEntity
import com.example.evergreen.data.SERVICE_SPOTIFY
import com.example.evergreen.data.SyncKeys
import com.example.evergreen.data.trackKeyOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The like archive and the ISRC backfill, against a real database and a scripted
 * API.
 */
@RunWith(AndroidJUnit4::class)
class LikeSyncTest {

    private lateinit var db: EvergreenDatabase
    private val t0 = 1_700_000_000_000L
    private var clock = t0

    /** Serves pre-scripted pages and counts what was asked for. */
    private class FakeApi : ApiSource {
        val pages = mutableListOf<ApiResult<SavedTracksPage>>()
        val tracks = mutableMapOf<String, ApiResult<SpotifyTrack>>()
        var pageRequests = 0
        var trackRequests = 0

        override fun savedTracks(limit: Int, offset: Int): ApiResult<SavedTracksPage> = nextPage()
        override fun savedTracksAt(url: String): ApiResult<SavedTracksPage> = nextPage()

        private fun nextPage(): ApiResult<SavedTracksPage> {
            val page = pages.getOrElse(pageRequests) { ApiResult.Failed(500, retryable = true) }
            pageRequests++
            return page
        }

        override fun track(trackId: String): ApiResult<SpotifyTrack> {
            trackRequests++
            return tracks[trackId] ?: ApiResult.NotFound
        }

        // The like sync touches no playlist. These exist only to satisfy the
        // interface, and they fail loudly rather than returning something
        // plausible -- if the like sync ever starts calling one, the test
        // should say so rather than quietly pass.
        override fun myPlaylists(limit: Int, offset: Int): ApiResult<PlaylistPage> =
            error("the like sync must not read playlists")

        override fun createPlaylist(name: String, description: String): ApiResult<PlaylistSummary> =
            error("the like sync must not create a playlist")

        override fun playlistItems(playlistId: String, offset: Int): ApiResult<PlaylistItemsPage> =
            error("the like sync must not read a playlist")

        override fun addToPlaylist(playlistId: String, uris: List<String>): ApiResult<String> =
            error("the like sync must not write a playlist")

        override fun removeFromPlaylist(playlistId: String, uris: List<String>): ApiResult<String> =
            error("the like sync must not write a playlist")
    }

    private val api = FakeApi()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvergreenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = t0
        api.pages.clear()
        api.tracks.clear()
        api.pageRequests = 0
        api.trackRequests = 0
    }

    @After
    fun tearDown() = db.close()

    private fun sync() = LikeSync(
        db = db,
        api = api,
        now = { clock },
        // No real waiting in tests.
        pause = { },
    )

    private fun track(
        uri: String,
        title: String,
        isrc: String? = null,
        album: String = "An Album",
    ) = SpotifyTrack(
        uri = uri,
        title = title,
        artist = "An Artist",
        album = album,
        durationMs = 200_000,
        isrc = isrc,
    )

    private fun page(vararg tracks: SpotifyTrack, next: String? = null) =
        ApiResult.Ok(
            SavedTracksPage(
                items = tracks.map { SavedTrack(it, addedAtMs = t0 - 1_000_000) },
                total = tracks.size,
                next = next,
            ),
        )

    // --- The archive ---------------------------------------------------------

    @Test
    fun likedSongsAreRecordedWithTheirIsrc() = runBlocking {
        api.pages += page(track("spotify:track:aaa", "One", isrc = "GBAYE0601498"))

        val outcome = sync().syncLikes()

        assertTrue(outcome is SyncOutcome.Success)
        assertEquals(1, (outcome as SyncOutcome.Success).liked)

        val serviceTrack = db.dao().allServiceTracks().single()
        assertEquals("spotify:track:aaa", serviceTrack.serviceTrackId)
        assertEquals("ISRC arrives free with the like sync", "GBAYE0601498", serviceTrack.isrc)
        assertEquals(0, api.trackRequests)
    }

    /**
     * A liked song that has never been played on this phone still belongs in the
     * archive. Without this the archive would hold only the likes that happen to
     * overlap the listening history.
     */
    @Test
    fun aLikedSongNeverPlayedStillEntersTheArchive() = runBlocking {
        api.pages += page(track("spotify:track:unplayed", "Never Played"))

        sync().syncLikes()

        assertEquals(1, db.dao().allTracks().size)
        assertEquals(1, db.spotifyDao().likedNowCount().first())
        assertEquals(0, db.dao().allSegments().size)
    }

    /**
     * The archive's whole point: unliking must make a song historical, not
     * erase it.
     */
    @Test
    fun unlikingKeepsTheRecord() = runBlocking {
        api.pages += page(track("spotify:track:aaa", "One"))
        sync().syncLikes()
        assertEquals(1, db.spotifyDao().likedNowCount().first())

        // Next sync: the song is gone from Spotify's liked set.
        clock = t0 + 86_400_000
        api.pageRequests = 0
        api.pages.clear()
        api.pages += page()

        val outcome = sync().syncLikes()

        assertEquals(1, (outcome as SyncOutcome.Success).unliked)
        assertEquals("nothing is liked now", 0, db.spotifyDao().likedNowCount().first())
        assertEquals("but the archive still holds it", 1, db.spotifyDao().archivedLikeCount().first())
        assertEquals(
            "and both transitions are on record",
            2,
            db.spotifyDao().likeEventCount().first(),
        )
    }

    /** A like, an unlike and a re-like must all remain visible afterwards. */
    @Test
    fun aRelikeIsRecordedWithoutLosingTheEarlierHistory() = runBlocking {
        api.pages += page(track("spotify:track:aaa", "One"))
        sync().syncLikes()

        clock = t0 + 86_400_000
        api.pageRequests = 0; api.pages.clear(); api.pages += page()
        sync().syncLikes()

        clock = t0 + 2 * 86_400_000
        api.pageRequests = 0; api.pages.clear()
        api.pages += page(track("spotify:track:aaa", "One"))
        sync().syncLikes()

        assertEquals(1, db.spotifyDao().likedNowCount().first())
        assertEquals("liked, unliked, liked", 3, db.spotifyDao().likeEventCount().first())
        assertEquals("still one archive row", 1, db.spotifyDao().archivedLikeCount().first())
    }

    /**
     * A sync that still sees the same songs should not append an event per song
     * per run -- that would be thousands of rows a day describing nothing
     * happening.
     */
    @Test
    fun anUnchangedSyncWritesNoNewEvents() = runBlocking {
        api.pages += page(track("spotify:track:aaa", "One"))
        sync().syncLikes()
        assertEquals(1, db.spotifyDao().likeEventCount().first())

        clock = t0 + 86_400_000
        api.pageRequests = 0; api.pages.clear()
        api.pages += page(track("spotify:track:aaa", "One"))
        sync().syncLikes()

        assertEquals(1, db.spotifyDao().likeEventCount().first())
    }

    /**
     * The most dangerous failure this code could have.
     *
     * A sync cut short by a 429 has read only some of the library. If it then
     * reconciled, every song it had not reached would look unliked -- the app
     * would mass-unlike the archive because of a rate limit.
     */
    @Test
    fun aSyncInterruptedByARateLimitNeverMarksAnythingUnliked() = runBlocking {
        api.pages += page(track("spotify:track:aaa", "One"), next = "https://api.spotify.com/v1/me/tracks?offset=50")
        api.pages += page(track("spotify:track:bbb", "Two"), next = "https://api.spotify.com/v1/me/tracks?offset=100")
        sync().syncLikes()
        assertEquals(2, db.spotifyDao().likedNowCount().first())

        // A later sync gets one page, then a 429.
        clock = t0 + 86_400_000
        api.pageRequests = 0; api.pages.clear()
        api.pages += page(track("spotify:track:aaa", "One"), next = "https://api.spotify.com/v1/me/tracks?offset=50")
        api.pages += ApiResult.RateLimited(retryAfterMs = 60_000, quotaExceeded = false)

        val outcome = sync().syncLikes()

        assertTrue(outcome is SyncOutcome.BackedOff)
        assertEquals(
            "the unreached song must still be liked",
            2,
            db.spotifyDao().likedNowCount().first(),
        )
        assertEquals(2, db.spotifyDao().likeEventCount().first())
    }

    @Test
    fun aRateLimitRecordsWhenItIsSafeToTryAgain() = runBlocking {
        api.pages += ApiResult.RateLimited(retryAfterMs = 60_000, quotaExceeded = false)

        sync().syncLikes()

        val until = db.spotifyDao().syncState(SyncKeys.API_BACKOFF_UNTIL)?.valueMs
        assertEquals(t0 + 60_000, until)
    }

    /** While backed off, a scheduled run must not spend a request. */
    @Test
    fun whileBackedOffNoRequestIsMade() = runBlocking {
        api.pages += ApiResult.RateLimited(retryAfterMs = 600_000, quotaExceeded = true)
        sync().syncLikes()
        val requestsSoFar = api.pageRequests

        clock = t0 + 1000
        val outcome = sync().syncLikes()

        assertTrue(outcome is SyncOutcome.BackedOff)
        assertEquals("no further request", requestsSoFar, api.pageRequests)
    }

    @Test
    fun anExpiredConnectionStopsTheSyncCleanly() = runBlocking {
        api.pages += ApiResult.NeedsReconnect

        assertEquals(SyncOutcome.NeedsReconnect, sync().syncLikes())
        assertNull(db.spotifyDao().syncState(SyncKeys.LIKES_LAST_SYNCED_AT))
    }

    @Test
    fun aCompletedSyncRecordsWhenItRan() = runBlocking {
        api.pages += page(track("spotify:track:aaa", "One"))

        sync().syncLikes()

        assertEquals(t0, db.spotifyDao().syncState(SyncKeys.LIKES_LAST_SYNCED_AT)?.valueMs)
    }

    // --- ISRC backfill -------------------------------------------------------

    /**
     * A track in the listening history that is not liked gets no free ISRC, so
     * it is fetched individually -- the batch endpoint is gone.
     */
    @Test
    fun anUnlikedHistoryTrackGetsItsIsrcFetchedIndividually() = runBlocking {
        recordSegmentFor("spotify:track:played", "Played But Not Liked")
        api.tracks["played"] = ApiResult.Ok(track("spotify:track:played", "Played But Not Liked", isrc = "USRC17607839"))

        val outcome = sync().backfillIsrcs()

        assertEquals(1, (outcome as SyncOutcome.Success).isrcsFilled)
        assertEquals("USRC17607839", db.dao().allServiceTracks().single().isrc)
        assertEquals(1, api.trackRequests)
    }

    /** A filled ISRC must never be looked up twice. */
    @Test
    fun aTrackWithAnIsrcIsNotFetchedAgain() = runBlocking {
        recordSegmentFor("spotify:track:played", "Played")
        api.tracks["played"] = ApiResult.Ok(track("spotify:track:played", "Played", isrc = "USRC17607839"))
        sync().backfillIsrcs()
        val after = api.trackRequests

        sync().backfillIsrcs()

        assertEquals(after, api.trackRequests)
    }

    /**
     * A failure is recorded so the next run resumes rather than re-fetching
     * everything -- which now costs one request per track.
     */
    @Test
    fun aFailedLookupIsRecordedAndNotImmediatelyRetried() = runBlocking {
        recordSegmentFor("spotify:track:gone", "Gone")
        api.tracks["gone"] = ApiResult.NotFound

        sync().backfillIsrcs()

        val row = db.dao().allServiceTracks().single()
        assertEquals(IsrcLookupError.NOT_FOUND, row.isrcLookupError)
        assertEquals(1, row.isrcLookupAttempts)
        assertNotNull(row.isrcLookupAt)

        val before = api.trackRequests
        sync().backfillIsrcs()
        assertEquals("not retried straight away", before, api.trackRequests)
    }

    /** A track that simply has no ISRC is an answer, not a failure to retry. */
    @Test
    fun aTrackWithNoIsrcIsRecordedAsAbsent() = runBlocking {
        recordSegmentFor("spotify:track:noisrc", "No ISRC")
        api.tracks["noisrc"] = ApiResult.Ok(track("spotify:track:noisrc", "No ISRC", isrc = null))

        sync().backfillIsrcs()

        assertEquals(IsrcLookupError.ABSENT, db.dao().allServiceTracks().single().isrcLookupError)
    }

    /**
     * A non-track URI -- a podcast episode, DJ commentary -- never enters the
     * queue at all, so it costs neither a request nor an attempt.
     */
    @Test
    fun aNonTrackIdentifierIsNeverQueued() = runBlocking {
        recordSegmentFor("spotify:episode:podcast", "A Podcast")

        sync().backfillIsrcs()

        val row = db.dao().allServiceTracks().single()
        assertNull("never attempted", row.isrcLookupAt)
        assertEquals(0, row.isrcLookupAttempts)
        assertEquals("never asked Spotify", 0, api.trackRequests)
    }

    /**
     * A malformed *track* URI does reach the queue, because it looks like one.
     * It must be recorded rather than retried, so a permanently unusable row
     * cannot sit at the head of the queue forever.
     */
    @Test
    fun aMalformedTrackIdentifierIsRecordedRatherThanRetried() = runBlocking {
        recordSegmentFor("spotify:track:", "Malformed")

        sync().backfillIsrcs()

        assertEquals(
            IsrcLookupError.UNSUPPORTED_ID,
            db.dao().allServiceTracks().single().isrcLookupError,
        )
        assertEquals("never asked Spotify", 0, api.trackRequests)
    }

    @Test
    fun aRateLimitDuringBackfillStopsWithoutLosingProgress() = runBlocking {
        recordSegmentFor("spotify:track:one", "One")
        recordSegmentFor("spotify:track:two", "Two")
        api.tracks["one"] = ApiResult.Ok(track("spotify:track:one", "One", isrc = "AAA111111111"))
        api.tracks["two"] = ApiResult.RateLimited(retryAfterMs = 60_000, quotaExceeded = false)

        val outcome = sync().backfillIsrcs()

        assertTrue(outcome is SyncOutcome.BackedOff)
        val filled = db.dao().allServiceTracks().count { it.isrc != null }
        assertEquals("the one that succeeded is kept", 1, filled)
    }

    // --- Merge dry run -------------------------------------------------------

    /**
     * Two canonical tracks sharing an ISRC -- the single and the album release
     * of one song -- are reported, and nothing is changed.
     */
    @Test
    fun tracksSharingAnIsrcAreReportedButNotMerged() = runBlocking {
        api.pages += page(
            track("spotify:track:single", "Same Song", isrc = "GBAYE0601498", album = "Single"),
            track("spotify:track:album", "Same Song", isrc = "GBAYE0601498", album = "The Album"),
        )
        sync().syncLikes()

        val candidates = db.spotifyDao().sameIsrcMergeCandidates()

        assertEquals(2, candidates.size)
        assertEquals(setOf("GBAYE0601498"), candidates.map { it.isrc }.toSet())
        assertEquals(
            "still two canonical tracks -- nothing was merged",
            2,
            db.dao().allTracks().size,
        )
    }

    @Test
    fun distinctSongsAreNotReportedAsMergeCandidates() = runBlocking {
        api.pages += page(
            track("spotify:track:a", "One", isrc = "AAA111111111"),
            track("spotify:track:b", "Two", isrc = "BBB222222222", album = "Other"),
        )
        sync().syncLikes()

        assertTrue(db.spotifyDao().sameIsrcMergeCandidates().isEmpty())
    }

    // --- Spotify DJ ----------------------------------------------------------

    /**
     * Observed on a Pixel 10 Pro on 2026-09-17: DJ spoken commentary *is*
     * published as a media-session track, but carries `spotify:media:<id>`
     * rather than `spotify:track:<id>`, with a zero duration and the metadata
     * "Up next" / "DJ X" / "DJ".
     *
     * That scheme difference is the whole reason DJ listening can be filtered at
     * all, so it is pinned here. Commentary must never enter the ISRC queue: no
     * track endpoint will resolve it, and leaving it in would burn five attempts
     * over five weeks to rediscover what its identifier already says.
     */
    @Test
    fun djCommentaryIsNeverQueuedForAnIsrcLookup() = runBlocking {
        recordSegmentFor("spotify:media:2j7w4Q2yMxa9ekzCdGhKUc", "Up next")
        recordSegmentFor("spotify:track:realsong", "A Real Song")
        api.tracks["realsong"] = ApiResult.Ok(track("spotify:track:realsong", "A Real Song", isrc = "AAA111111111"))

        val outcome = sync().backfillIsrcs()

        assertEquals("only the real track was fetched", 1, api.trackRequests)
        assertEquals(1, (outcome as SyncOutcome.Success).isrcsFilled)

        val commentary = db.dao().allServiceTracks()
            .single { it.serviceTrackId.startsWith("spotify:media:") }
        assertNull("never attempted", commentary.isrcLookupAt)
        assertEquals(0, commentary.isrcLookupAttempts)
    }

    /**
     * Commentary is kept, not discarded -- it is a fact about what played, and
     * the project's rule is that capture stores facts for scoring to interpret
     * later. But it must not drag down ISRC coverage, which would make a
     * category that does not apply look like a backfill falling behind.
     */
    @Test
    fun djCommentaryIsCountedSeparatelyFromRealTracks() = runBlocking {
        recordSegmentFor("spotify:media:2j7w4Q2yMxa9ekzCdGhKUc", "Up next")
        api.pages += page(track("spotify:track:aaa", "One", isrc = "AAA111111111"))
        sync().syncLikes()

        assertEquals(
            "coverage counts real tracks only",
            1,
            db.spotifyDao().serviceTrackCount(SERVICE_SPOTIFY, SpotifyApi.TRACK_URI_PREFIX).first(),
        )
        assertEquals(
            1,
            db.spotifyDao()
                .serviceTracksWithIsrcCount(SERVICE_SPOTIFY, SpotifyApi.TRACK_URI_PREFIX).first(),
        )
        assertEquals(
            "but the commentary is still stored and visible",
            1,
            db.spotifyDao()
                .nonTrackServiceRowCount(SERVICE_SPOTIFY, SpotifyApi.TRACK_URI_PREFIX).first(),
        )
        assertEquals("and its segment was kept", 1, db.dao().allSegments().size)
    }

    private suspend fun recordSegmentFor(uri: String, title: String) {
        db.dao().recordSegment(
            trackKey = trackKeyOf(title, "An Artist", "An Album"),
            title = title,
            artist = "An Artist",
            album = "An Album",
            durationMs = 200_000,
            service = SERVICE_SPOTIFY,
            serviceTrackId = uri,
            segment = ListeningSegmentEntity(
                trackId = 0,
                serviceTrackRowId = null,
                appPackage = "com.spotify.music",
                deviceId = "test-device",
                startedAtMs = t0,
                endedAtMs = t0 + 200_000,
                startPositionMs = 0,
                endPositionMs = 200_000,
                listenedMs = 200_000,
                trackDurationMs = 200_000,
                forwardSeekCount = 0,
                seekedForwardMs = 0,
                endReason = EndReason.TRACK_CHANGE,
                endJumpFromMs = null,
                endJumpToMs = null,
                lastSampleAgeMs = 0,
                createdAt = t0,
            ),
        )
    }
}

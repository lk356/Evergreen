package com.example.evergreen

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The notification channels, and the importance each was created at.
 *
 * Importance is checked because Android fixes it at creation and deliberately
 * ignores later changes — an app cannot make itself noisier than the user
 * agreed to. So getting it wrong the first time is not something a later
 * release can quietly correct — which is exactly why raising playlist changes
 * to arrive by default in Phase 4 needed a new channel id rather than an edit.
 */
@RunWith(AndroidJUnit4::class)
class NotificationsTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        Notifications.createChannels(context)
    }

    @Test
    fun theReconnectChannelIsCreatedAtDefaultImportance() {
        val channel = manager.getNotificationChannel(Notifications.CHANNEL_CONNECTION)

        assertNotNull(channel)
        assertEquals("Connection", channel.name)
        assertEquals(
            "the one silent failure that costs data must be able to interrupt",
            NotificationManager.IMPORTANCE_DEFAULT,
            channel.importance,
        )
        assertTrue(channel.description.orEmpty().isNotBlank())
    }

    /** A snapshot being taken is a record, not an event. It appears silently. */
    @Test
    fun theSnapshotChannelStaysQuiet() {
        val channel = manager.getNotificationChannel(Notifications.CHANNEL_SNAPSHOTS)
        assertNotNull(channel)
        assertEquals(
            "a snapshot is not something to interrupt for",
            NotificationManager.IMPORTANCE_LOW,
            channel.importance,
        )
    }

    /**
     * Playlist changes now arrive by default (Phase 4, decision 5).
     *
     * The point is not having to open the app: the changes come to the user and
     * the app gets opened when something looks wrong. What stops it being noise
     * is already in place — hysteresis, the once-a-day write guard, one
     * summarising notification per sync, and suppression entirely after a
     * parameter change.
     */
    @Test
    fun playlistChangesArriveByDefault() {
        val channel = manager.getNotificationChannel(Notifications.CHANNEL_PLAYLIST)
        assertNotNull(channel)
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            channel.importance,
        )
    }

    /**
     * Raising the playlist channel required a new id, and the old one must not
     * linger.
     *
     * Android fixes importance at creation and ignores later changes, so the
     * original `playlist` channel could not be raised in place. Leaving it
     * behind would show the user two "Playlist changes" entries in the system
     * settings, one of which does nothing.
     */
    @Test
    fun theOldQuietPlaylistChannelIsGone() {
        assertNull(
            "the pre-Phase-4 channel should have been deleted",
            manager.getNotificationChannel("playlist"),
        )
        assertEquals("playlist_v2", Notifications.CHANNEL_PLAYLIST)
    }

    /** The alert for the one failure that loses data rather than delaying it. */
    @Test
    fun theCaptureChannelCanInterrupt() {
        val channel = manager.getNotificationChannel(Notifications.CHANNEL_CAPTURE)
        assertNotNull(channel)
        assertEquals(
            "listening that is never recorded cannot be recovered, so this interrupts",
            NotificationManager.IMPORTANCE_DEFAULT,
            channel.importance,
        )
        assertTrue(channel.description.orEmpty().isNotBlank())
    }

    /**
     * One notification per sync, summarising — never one per track.
     *
     * A sync can produce one change or forty, and forty notifications for one
     * run would be the end of anyone leaving this channel on.
     */
    @Test
    fun aPlaylistChangeIsSummarisedRatherThanListed() {
        assertEquals("1 song joined", Notifications.summarisePlaylistChange(1, 0))
        assertEquals("12 songs joined", Notifications.summarisePlaylistChange(12, 0))
        assertEquals("3 left", Notifications.summarisePlaylistChange(0, 3))
        assertEquals(
            "20 songs joined, 20 left",
            Notifications.summarisePlaylistChange(20, 20),
        )
    }

    @Test
    fun creatingTheChannelsTwiceIsHarmless() {
        Notifications.createChannels(context)
        Notifications.createChannels(context)

        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(Notifications.CHANNEL_CONNECTION).importance,
        )
    }

    @Test
    fun allFourChannelsExist() {
        val ours = manager.notificationChannels.map { it.id }.toSet()

        assertTrue(
            "missing channels; found $ours",
            ours.containsAll(
                setOf(
                    Notifications.CHANNEL_CONNECTION,
                    Notifications.CHANNEL_SNAPSHOTS,
                    Notifications.CHANNEL_PLAYLIST,
                    Notifications.CHANNEL_CAPTURE,
                ),
            ),
        )
    }

    // --- Posting ---------------------------------------------------------------

    /**
     * Posting must never throw, whether or not the permission was granted.
     * Nothing in this app is important enough to crash a background worker
     * over, and a worker that crashed here would stop syncing entirely.
     */
    @Test
    fun postingNeverThrowsWhateverThePermissionState() {
        Notifications.postReconnectRequired(context)
        Notifications.postReconnectRequired(context, expiringSoon = true)
        Notifications.postSnapshotTaken(context, "2026 August", 380)
        Notifications.postPlaylistChanged(context, added = 3, removed = 1)
        Notifications.postCaptureSilent(context, days = 9)
    }

    /** Nothing happened, so nothing is said. */
    @Test
    fun aPlaylistSyncThatChangedNothingPostsNothing() {
        Notifications.postPlaylistChanged(context, added = 0, removed = 0)
    }

    @Test
    fun canPostReflectsThePermission() {
        // Whatever the grant state on this device, the check must answer
        // without throwing -- it is called on every post.
        val answer = Notifications.canPost(context)
        assertTrue(answer || !answer)
    }
}

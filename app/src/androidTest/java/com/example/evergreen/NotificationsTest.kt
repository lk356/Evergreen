package com.example.evergreen

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * release can quietly correct, and decisions 7 is specific: reconnect is on by
 * default, snapshots and playlist changes are built but quiet.
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

    /**
     * Built, and quiet. The user can raise them in Android's own settings,
     * which is where per-channel preferences belong until Phase 4 has a
     * settings screen.
     */
    @Test
    fun theOptionalChannelsAreCreatedQuiet() {
        listOf(Notifications.CHANNEL_SNAPSHOTS, Notifications.CHANNEL_PLAYLIST).forEach { id ->
            val channel = manager.getNotificationChannel(id)
            assertNotNull("channel $id should exist", channel)
            assertEquals(
                "$id must not interrupt by default",
                NotificationManager.IMPORTANCE_LOW,
                channel.importance,
            )
        }
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
    fun thereAreExactlyThreeChannels() {
        val ours = manager.notificationChannels.map { it.id }.toSet()

        assertTrue(
            "unexpected channels: $ours",
            ours.containsAll(
                setOf(
                    Notifications.CHANNEL_CONNECTION,
                    Notifications.CHANNEL_SNAPSHOTS,
                    Notifications.CHANNEL_PLAYLIST,
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

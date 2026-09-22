package com.example.evergreen.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **A probe, not a rule.** Answers one question before anything is built on it:
 * does an ordinary, unprivileged app see the same active playback that
 * `dumpsys audio` shows?
 *
 * ### Why this exists
 *
 * Phase 5c step 7a measured `MediaController.PlaybackInfo.getPlaybackType()` —
 * the signal the step 2 phantom fix rests on — against ground truth, and it
 * caught **32%** of phantoms while discarding some real listening. The signal
 * that did separate the devices perfectly was whether the device is actually
 * producing media audio, read from `dumpsys audio`.
 *
 * `dumpsys` runs as shell. This app does not. `AudioManager` documents that
 * client UID and PID are anonymised for callers without
 * `MODIFY_AUDIO_ROUTING`, and it is not obvious from the API whether the
 * *configurations themselves* are visible, or whether
 * `AudioPlaybackConfiguration.getAudioDeviceInfo()` survives that redaction.
 * Building the fix on a redacted view would be the third time this phase that
 * a design was written against a signal nobody had checked.
 *
 * ### How to read it
 *
 * It asserts almost nothing on purpose — what it produces is a **logcat
 * report**, read with:
 *
 * ```
 * adb -s <serial> logcat -d | grep ActivePlaybackProbe
 * ```
 *
 * Run it twice, and the pair of readings is the whole answer:
 *
 * 1. with music playing **on another device** — this device should report
 *    **no** active media playback. If it reports some, the signal cannot tell a
 *    phantom from a real listen and the proposal is dead.
 * 2. with music playing **on this device** — it should report at least one
 *    configuration whose usage is `USAGE_MEDIA`. If it reports none, the app
 *    cannot see its own playback and the proposal is dead for the other reason.
 */
@RunWith(AndroidJUnit4::class)
class ActivePlaybackVisibilityTest {

    @Test
    fun whatAnUnprivilegedAppCanSeeOfActivePlayback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = context.getSystemService(AudioManager::class.java)
        assertNotNull("AudioManager must be available", audio)

        val configs = audio.activePlaybackConfigurations

        Log.i(TAG, "=== active playback, as this app sees it ===")
        Log.i(TAG, "total configurations: ${configs.size}")

        var media = 0
        configs.forEachIndexed { index, config ->
            val attrs = config.audioAttributes
            val isMedia = attrs.usage == AudioAttributes.USAGE_MEDIA
            if (isMedia) media++
            // getAudioDeviceInfo may be null for an unprivileged caller; that is
            // one of the things being measured, so it is reported rather than
            // assumed either way.
            val device = runCatching { config.audioDeviceInfo }.getOrNull()
            Log.i(
                TAG,
                "  [$index] usage=${attrs.usage} content=${attrs.contentType} " +
                    "isMedia=$isMedia deviceType=${device?.type} " +
                    "deviceName=${device?.productName}",
            )
        }
        Log.i(TAG, "media configurations: $media")
        Log.i(TAG, "=== end ===")
    }

    private companion object {
        const val TAG = "ActivePlaybackProbe"
    }
}

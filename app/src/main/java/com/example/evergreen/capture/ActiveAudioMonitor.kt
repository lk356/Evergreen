package com.example.evergreen.capture

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler

/**
 * Whether **this device** is producing media audio right now.
 *
 * ### Why this exists
 *
 * Phase 5c step 2 built the phantom fix on
 * `MediaController.PlaybackInfo.getPlaybackType()`, and step 7 measured it
 * against ground truth over 251 paired samples: it caught **32%** of phantoms
 * and discarded real listening in 14 of them. The Pixel called itself `LOCAL`
 * 103 times out of 103 while producing no sound at all.
 *
 * The signal that did separate the two devices is this one. Over the same 251
 * samples it identified exactly one owner in **249**, and the two exceptions
 * are single samples at handoff instants. An allowlisted session claiming
 * `PLAYING` while the device it is on produces no media audio whatsoever is a
 * phantom: the music is coming out of something else.
 *
 * ### What it can and cannot see
 *
 * `AudioManager.getActivePlaybackConfigurations()` is **not redacted** for an
 * ordinary app — verified in the app itself by `ActivePlaybackVisibilityTest`
 * before a line of this was written. What *is* redacted is
 * `AudioPlaybackConfiguration.getAudioDeviceInfo()`, which returns null type
 * and null name, and `getClientUid()`, which is absent entirely. So:
 *
 * - **knowable**: that this device is producing audio, and what it is *for*
 *   (`usage`, `contentType`);
 * - **not knowable**: which app is producing it, or where the sound is going.
 *
 * Not knowing the app is the honest limit of this signal and it is accepted
 * deliberately. Any app's media playback counts — a video, a game, another
 * music player. That direction of error **counts a listen that might not be
 * this app's** rather than discarding one that was, which is the direction
 * this phase has decided to fail in: under-counting is what cost 83% of a real
 * session, and over-counting is visible and recoverable.
 *
 * ### Live, not polled
 *
 * `registerAudioPlaybackCallback` fires whenever the set of active players
 * changes, so nothing here wakes up on a timer. The NW-A307 has little to
 * spare and a poll would be the wrong shape for a device that mostly sits with
 * one track playing for three minutes at a time.
 *
 * ### PRIVACY: why a device-wide signal does not widen what is recorded
 *
 * This is the one signal in `capture/` that is **not** scoped to an allowlisted
 * package, and that is worth being explicit about rather than leaving implicit.
 *
 * - **No microphone and no audio content.** This reads the framework's list of
 *   *active players*, not a sound. The app declares no `RECORD_AUDIO`
 *   permission and `registerAudioPlaybackCallback` requires none.
 * - **Nothing per-app is knowable.** `getClientUid()`/`getClientPid()` are not
 *   public API, and `getAudioDeviceInfo()` is redacted to null for a caller
 *   without `MODIFY_AUDIO_ROUTING` — verified on device. There is no package
 *   name, no app name, no title, no route to read here even if something wanted
 *   to.
 * - **Nothing per-configuration is kept.** The whole list is reduced to one
 *   boolean before it leaves this class. Nothing is stored or logged about any
 *   individual player.
 * - **It creates no rows.** The boolean is only ever written onto a segment
 *   that exists *because an allowlisted session was playing*. With no
 *   allowlisted session there is no segment, so there is nowhere for a reading
 *   about any other app to land — a video watched with Spotify closed is
 *   recorded nowhere, in the database or in the live log.
 * - **The live log is gated on an allowlisted session too**, in
 *   [CaptureNotificationListenerService], so a transition with nothing
 *   allowlisted open does not even reach the in-memory log or Logcat.
 *
 * What remains is one accuracy limit, not a privacy one: another app's media
 * audio makes this read true, so a phantom concurrent with a video would count.
 * That is the fail-safe direction and it is stated above.
 *
 * ### Nothing here is a conclusion
 *
 * This reports a reading. It does not decide that a segment is a phantom —
 * that needs the *other* device's facts, which only scoring has, and it is
 * decided there. Capture stores what was observed; see [SegmentTracker].
 */
class ActiveAudioMonitor(
    private val audio: AudioManager?,
    private val handler: Handler,
    private val onChanged: (Boolean) -> Unit,
) {

    /**
     * The most recent reading, or null before the first one.
     *
     * Null is "not observed" and never "no audio": a segment that opens before
     * the monitor has read anything carries null, and scoring treats that the
     * way it treats every row written before this column existed.
     */
    var isProducingMediaAudio: Boolean? = null
        private set

    private var registered = false

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            publish(hasMediaAudio(configs))
        }
    }

    fun start() {
        val manager = audio
        if (manager == null) {
            CaptureLog.add(EventSource.SYSTEM, "AudioManager unavailable; audio output not recorded")
            return
        }
        if (registered) return
        registered = true
        manager.registerAudioPlaybackCallback(callback, handler)
        // Prime from the current state. The callback only fires on a *change*,
        // so without this a device that was already playing when the listener
        // bound would look silent until the next track ended -- the same hole
        // the playback type had before it was primed in [SessionWatcher.start].
        publish(hasMediaAudio(manager.activePlaybackConfigurations))
    }

    fun stop() {
        if (!registered) return
        registered = false
        audio?.unregisterAudioPlaybackCallback(callback)
    }

    private fun publish(producing: Boolean) {
        if (isProducingMediaAudio == producing) return
        isProducingMediaAudio = producing
        // Deliberately not logged here. Whether this reading is about anything
        // Evergreen is entitled to observe depends on whether an allowlisted
        // session is open, which this class does not know and the caller does.
        onChanged(producing)
    }

    private companion object {
        /**
         * Counts only `USAGE_MEDIA`.
         *
         * A notification chime, a keyboard click or a navigation instruction is
         * audio this device produced and is not evidence that it is the one
         * playing the music. `contentType` is deliberately *not* filtered on:
         * a podcast or an audiobook reads as `CONTENT_TYPE_SPEECH` and is still
         * a listen.
         */
        fun hasMediaAudio(configs: List<AudioPlaybackConfiguration>): Boolean =
            configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA }
    }
}

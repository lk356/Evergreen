# Phase 0 prompt — paste this into Claude Code

Read CLAUDE.md and docs/PLAN.md first. We're starting Phase 0: a feasibility spike.
Work on a new git branch called `phase0-capture-spike`. Before writing code, show me
a short plan and wait for my approval.

Goal: prove we can reliably observe Spotify playback on my Android devices
(Pixel 10 Pro and Sony NW-A307, Android 12). This is throwaway-quality code for learning,
but it must still follow the privacy rules in CLAUDE.md.

Build:
1. A NotificationListenerService used only to gain access to MediaSessionManager.
   Only handle media sessions whose package is on an allowlist (start with
   `com.spotify.music`). Ignore and discard everything else immediately.
2. A MediaController.Callback that logs, for each change: timestamp, package,
   title, artist, album, duration, playback state, and position.
3. A BroadcastReceiver for Spotify's "Device Broadcast Status" intents
   (metadata changed, playback state changed). Log every extra they include,
   so we can see whether the Spotify track URI is available.
4. Simple skip detection: when the track changes, compute how much of the previous
   track was played (using positions and timestamps) and label it
   completed / skipped / unknown.
5. A single Compose screen that:
   - shows whether notification access is granted, with a button that opens the
     system settings page for it,
   - lists the last ~100 events in memory (no database yet),
   - has a "clear" button.
   Logs should also go to Logcat under the tag `EvergreenSpike`.

Then give me click-by-click instructions to:
- build and install the app on my Pixel over USB,
- grant notification access (including "Allow restricted settings" if Android blocks it),
- test: play a song to the end, skip a song early, pause/resume, seek,
  and switch between phone and Spotify Connect playback,
- repeat the install on the NW-A307.

When done, update docs/PLAN.md: record what we learned for each Open question,
mark Phase 0 status, and commit.

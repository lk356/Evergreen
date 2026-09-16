# Evergreen — plan and status

This file is the shared source of truth between Claude Code (building) and the
claude.ai "Playlist Manager" Project (planning). Keep it current.

## Goal
A "living" liked-songs playlist that works across devices and, eventually, across
streaming services:
- **Evergreen**: liked songs that are still being listened to. Songs decay out when
  they are skipped or not played.
- **Archive**: every song ever liked, kept in the app's database.
- **Snapshots**: a monthly copy of Evergreen, searchable ("2026 August"),
  exportable to a real playlist on demand.

## Key decisions
- Listening data is captured on-device (media sessions), not by polling service APIs.
  This works for any streaming app and avoids API quota limits.
- The app's own database is the source of truth. Streaming services are sync targets:
  likes are read from them, Evergreen is written to them.
- Likes keep using each service's own like button (synced periodically).
- Snapshots live in the app; they become real playlists only when exported.
- Android first (Pixel 10 Pro + Sony NW-A307), then other services, then desktop.
- Spotify development mode is fine for personal use (Premium required, 5 users max).
  A public release depends on each service's API rules at that time.
- Scoring is a hand-written formula, not a trained model.

## Devices
- Pixel 10 Pro (Android, primary)
- Sony NW-A307 (Android 14, build 3.06.00, kernel 4.19.157-perf+, low-powered)
- Windows and Linux desktops running Spotify (later phases)

## Phases
- [ ] **Phase 0 — Feasibility spike.** Log media-session events from Spotify on both
      Android devices. Confirm skip detection works and whether Spotify broadcasts
      include track URIs. _(in progress, branch `phase0-capture-spike`)_
- [ ] **Phase 1 — Capture MVP.** Allowlisted listener service, Room database of
      listening events, debug screen to verify events.
- [ ] **Phase 2 — Spotify connection.** PKCE login, encrypted token storage,
      like sync, track matching, canonical track table.
- [ ] **Phase 3 — Core feature.** Scoring, daily Evergreen playlist sync,
      monthly snapshots. Seed scores from Spotify extended streaming history.
- [ ] **Phase 4 — Usability.** Real UI, snapshot search, like widget,
      scoring settings, data export/backup.
- [ ] **Phase 5 — Cross-service.** Second service adapter (likely Tidal),
      ISRC/MusicBrainz matching.
- [ ] **Phase 6 — Cross-device.** Self-hosted sync backend, Windows agent (WinRT media
      session API), Linux agent (MPRIS).
- [ ] **Phase 7 — Public release evaluation.** Terms, quotas, privacy policy,
      delete-my-data.

## Preparation checklist
- [x] Android Studio, Git, Claude Code (+ JetBrains plugin) installed
- [x] Developer options + USB debugging on both devices
- [x] Spotify "Device Broadcast Status" enabled on both devices
- [x] Spotify developer app created, own account allowlisted
- [ ] Spotify extended streaming history requested (arrives later)
- [ ] GitHub CLI logged in, private repo created, first commit pushed

## Status
_Update at the end of each task: what was done, what's next, any blockers._

- Current phase: Phase 0 — spike **built, awaiting device testing**
- Last update: 2026-09-16, branch `phase0-capture-spike`

Built and compiling (debug APK builds clean, 14 skip-detector unit tests pass):
- `SpikeNotificationListenerService` — allowlisted to `com.spotify.music`;
  never reads notification content.
- `SessionWatcher` — logs metadata + playback state per session.
- `SpotifyBroadcastReceiver` — dumps every extra from Device Broadcast Status
  intents; registered at runtime, `RECEIVER_EXPORTED`.
- `SkipDetector` — COMPLETED / SKIPPED / UNKNOWN, pure Kotlin, unit tested.
- `SpikeScreen` — access status, last 100 events, clear.

**Next:** run the device test script (below) on the Pixel, then the NW-A307,
and fill in the Open questions from the results.

## Phase 0 device test script
Run on each device, watching the app screen or
`adb logcat -s EvergreenSpike`:
1. Play a song all the way to the end → expect `VERDICT ... COMPLETED`.
2. Skip a song after ~10s → expect `VERDICT ... SKIPPED`.
3. Pause, wait ~30s, resume → position must not advance while paused.
4. Seek forward and backward → estimated position should track the seek.
5. Play to a Spotify Connect device (speaker/other phone) → record whether
   any events arrive at all.
6. Lock the screen for one full track → check whether callbacks keep arriving
   or the verdict comes back UNKNOWN ("stale").

## Open questions
- **Does the NW-A307 allow notification-listener access for a sideloaded app?**
  Unresolved — needs the device. Now known to run Android 14, so Google's
  "restricted settings" block applies. Installing over USB (`adb install`)
  usually bypasses it; if not, App info → ⋮ → Allow restricted settings, or
  `adb shell cmd notification allow_listener <component>` as a fallback.
- **Do Spotify broadcast intents include the track URI on current app versions?**
  Unresolved — needs the device. The spike logs every extra, so the answer will
  be visible directly. Two candidate routes are instrumented: the broadcast `id`
  extra, and `METADATA_KEY_MEDIA_URI` / `METADATA_KEY_MEDIA_ID` on the media
  session. If both come back empty, Phase 2 must match by artist/title/duration.
- **How reliable is media-session position data for skip detection?**
  Partly answered by construction: Android reports position as a *snapshot* plus
  a monotonic timestamp, never a live value, so any consumer must extrapolate
  forward, scale by `playbackSpeed`, freeze while paused, and clamp to duration.
  All four are implemented and unit tested. What remains empirical is how often
  Spotify actually publishes updates — hence tests 3, 4 and 6. The 30s staleness
  threshold and 90% completion threshold are guesses awaiting calibration.
- **Known limitation found while building:** media-session metadata has no
  stable track ID, so the spike identifies tracks by title|artist|album. A track
  repeated back-to-back looks like one continuous play. Acceptable for Phase 0;
  Phase 1 needs a real identity story.
- Initial scoring parameters (decay half-life, skip penalty, grace period).
  Not started; belongs to Phase 3.

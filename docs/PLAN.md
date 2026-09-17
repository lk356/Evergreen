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
- [x] **Phase 0 — Feasibility spike.** Log media-session events from Spotify on both
      Android devices. Confirm skip detection works and whether Spotify broadcasts
      include track URIs. _(done 2026-09-17)_
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

- Current phase: **Phase 0 complete.** Verified on both devices 2026-09-17.
- Last update: 2026-09-17, branch `phase0-capture-spike`

Phase 0 proved the capture approach works. Every test passed on the Pixel 10
Pro and on the NW-A307, with no behavioural differences between them.

**Next:** Phase 1 (Capture MVP). Carry the spike's findings across — in
particular replay detection and event de-duplication, both of which are
correctness issues, not polish.

## Phase 0 results (2026-09-17)

Tested on Pixel 10 Pro and Sony NW-A307 (Android 14). Identical results on both.

| Test | Result |
|---|---|
| Song played to the end | COMPLETED ✅ |
| Skip after ~10s | SKIPPED ✅ |
| Pause 30s, resume | Position correct, did not advance while paused ✅ |
| Seek forward / back | Tracked correctly ✅ |
| Spotify Connect playback | Events captured ✅ (better than expected) |
| Screen locked for a full track | COMPLETED logged; callbacks kept arriving ✅ |
| NW-A307 sideload + notification access | Granted without trouble ✅ |
| NW-A307 physical buttons | Captured like any other transport control ✅ |

## Open questions — resolved in Phase 0

- **Does the NW-A307 allow notification-listener access for a sideloaded app?**
  **Yes.** Installed over adb, access granted normally, no "restricted settings"
  block encountered. The Walkman behaves like the Pixel in every test, including
  its physical transport buttons. It is not the special case we planned around.
- **Do Spotify broadcast intents include the track URI on current app versions?**
  **Yes — and the media session carries it too**, as `mediaId`
  (`spotify:track:...`) on the session metadata. The media-session route is the
  better one to depend on: it does not require the user to have enabled "Device
  Broadcast Status", and it keeps working for Spotify Connect playback. Phase 2
  can match on exact Spotify URI rather than guessing from artist/title.
- **How reliable is media-session position data for skip detection?**
  **Reliable enough.** Pause, resume, and seek all reported correct positions,
  and the 30s staleness guard never fired — not even with the screen locked for a
  whole track, which was the scenario we most expected to lose. Position handling
  must still extrapolate from the snapshot + monotonic timestamp (see Phase 0
  notes below); done naively it would be wrong.
- **Spotify Connect:** playback to a remote device **is** captured, including
  while Spotify is swiped away from recents. Force-stopping Spotify from Android
  settings ends capture, as expected. This removes a design constraint we had
  budgeted for.

## Carried into Phase 1 (found while testing)

- **Replays are invisible, and can be scored as skips.** Track identity is
  `title|artist|album`, so a track restarting from 0:00 is not seen as a new
  play. Pressing "previous" twice therefore restarts the track, then abandons it
  a second later, and the near-complete first listen is recorded as SKIPPED.
  This penalises exactly the songs a user likes enough to replay, so Phase 1
  needs a replay boundary: a large backwards position jump within the same
  track should close the current play segment and judge it, then start a new one.
- **Events must be de-duplicated before they reach the database.** A single
  track change emits roughly five or six log lines today: a media-session
  METADATA line, a VERDICT line, one or more STATE lines, plus separate
  BROADCAST lines for `metadatachanged`, `playbackstatechanged` and
  `queuechanged`. Spotify also republishes playback state frequently on its own.
  That is correct for a spike watching two sources deliberately, but written
  straight to Room it would balloon the event table. Phase 1 should pick the
  media session as the primary source and treat broadcasts as supplementary.
- **Position is a snapshot, not a live value.** Android reports position plus a
  monotonic timestamp. Any consumer must extrapolate forward, scale by
  `playbackSpeed`, freeze while paused, and clamp to the track duration. All four
  are implemented and unit tested in `SkipDetector`; keep them.
- **Thresholds still need calibration.** 90% completion and the 30s staleness
  guard were guesses. Both behaved correctly in testing but have not been tuned
  against a real listening history.

## Open questions — still open

- Initial scoring parameters (decay half-life, skip penalty, grace period).
  Belongs to Phase 3.
- How should a replay count towards a score — as a second play, or as strong
  positive signal in its own right? Raised by the replay finding above.

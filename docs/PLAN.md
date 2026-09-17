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
- **Capture records facts; scoring interprets them.** Phase 1 stores what
  happened, precisely enough that any later scoring rule can be recomputed from
  the stored rows. Phase 1 does not decide how replays, loops, seeks or short
  segments are scored — that is Phase 3 — but it must not lose or misrepresent
  any of them.

## Devices
- Pixel 10 Pro (Android, primary)
- Sony NW-A307 (Android 14, build 3.06.00, kernel 4.19.157-perf+, low-powered)
- Windows and Linux desktops running Spotify (later phases)

## Phases
- [x] **Phase 0 — Feasibility spike.** Log media-session events from Spotify on both
      Android devices. Confirm skip detection works and whether Spotify broadcasts
      include track URIs. _(done 2026-09-17)_
- [x] **Phase 1 — Capture MVP.** Allowlisted listener service, Room database of
      play segments, debug screen to verify them. _(done 2026-09-17)_
- [ ] **Phase 2 — Spotify connection.** PKCE login, encrypted token storage,
      like sync, track matching, canonical track table. Verify against the live
      Spotify Web API reference that track responses still include ISRC before
      relying on it.
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
- [x] GitHub CLI logged in, private repo created, first commit pushed

## Status
_Update at the end of each task: what was done, what's next, any blockers._

- Current phase: **Phase 1 complete.** Built and verified on the NW-A307
  2026-09-17, on branch `phase1-capture-mvp`.
- Last update: 2026-09-17.

Phase 1 replaced the spike with a real capture pipeline. Listening is now stored
as **play segments** in a Room database — one row per continuous listen, not one
per callback — and the replay, loop and seek-to-end bugs found after Phase 0 are
fixed. `com.example.evergreen.spike` is deleted.

**Next:** Phase 2 (Spotify connection). Before relying on ISRC, verify against
the live Spotify Web API reference that track responses still include it.

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

- **Restarts and loops are invisible, and are sometimes recorded as skips.**
  Track identity is `title|artist|album`, so any restart of the same track is not
  treated as a boundary. Further device testing after Phase 0:

  | Action | What was logged | What it actually means |
  |---|---|---|
  | Press "previous" once (restarts the same song) | no verdict | the restart is invisible; the first listen is lost when the track later ends |
  | Press "previous" twice (to an earlier song) | SKIPPED | the ~1s restarted fragment is judged a skip, and the first listen is lost |
  | Repeat-one mode | nothing ever logged as completed | every loop is invisible |
  | At the end of a song, press "previous" to restart | not completed | same as a restart: invisible |
  | Seek to near the end, let it auto-advance | COMPLETED | false positive — see the next bullet |

  This penalises exactly the songs the user likes enough to replay or loop. Phase
  1 needs a replay boundary: a large backwards position jump within the same
  track closes the current segment and opens a new one. See "Step 0 findings"
  below for a captured example.

- **Position is not listening time.** `SkipDetector` treats the final position as
  the amount played, so seeking forward to near the end of a track and letting it
  auto-advance is recorded as COMPLETED even though almost none of it was heard.
  The inverse also holds: seeking backwards deflates it. In the Step 0 capture one
  segment spanned 39.1s of position but only ~3.8s was actually heard. Phase 1
  must accumulate **listened ms** while playback is actually running, rather than
  reading it off the position at the end.
- **Events must be de-duplicated before they reach the database.** The media
  session republishes `onMetadataChanged` with byte-identical content up to ten
  times a second during transitions, each paired with a playback-state callback,
  and the broadcasts add more on top. De-duplication must compare content rather
  than trust the callback. Written straight to Room this would balloon the event
  table. Phase 1 uses the media session as the primary source and treats
  broadcasts as supplementary.
- **Position is a snapshot, not a live value.** Android reports position plus a
  monotonic timestamp. Any consumer must extrapolate forward, scale by
  `playbackSpeed`, freeze while paused, and clamp to the track duration. All four
  are implemented and unit tested in `SkipDetector`; keep them.
- **Thresholds still need calibration.** 90% completion and the 30s staleness
  guard were guesses. Both behaved correctly in testing but have not been tuned
  against a real listening history.

## Step 0 findings — captured 2026-09-17

A single Logcat capture on the Pixel covering a repeat-one loop, a forward seek,
a restart, and a skip to the next track. Track: *Metadata* — Willix,
`spotify:track:4h3K5he5ItFqJB1MVHMKJV`, 109000 ms.

| Time | Position | Event |
|---|---|---|
| 01:28:25.5 | 1:43 (103.2s) | playing, near the end |
| 01:28:30.0 | 1:47 (107.8s) | BUFFERING at end of track |
| 01:28:30.9 | 0:00 (1 ms) | **loop** — jumped back from 98.9%, no metadata change |
| 01:28:33.2 | 0:38 (38.5s) | **forward seek** — +36.5s beyond elapsed wall time |
| 01:28:35.1 | 0:00 (118 ms) | **restart** — jumped back from 35.9% |
| 01:28:39.1 | 0:04 (4.0s) | paused |
| 01:28:58.0 | — | next track; logged `SKIPPED — played 0:04 of 1:49 (3%)` |

What the user did: played the track to 98.9%, let it loop, seeked into the
middle, restarted it, listened four more seconds, then skipped on. Three separate
listens, one of them essentially complete. What was recorded: a single SKIP at
3% — the worst form of the bug, a deliberately looped song scored as a rejection.

What should have been recorded:

| Segment | Position span | Listened | Ended by | Reached |
|---|---|---|---|---|
| A | … → 107.8s | (began before the capture) | replay boundary | 98.9% |
| B | 0 → 39.1s | ~3.8s | replay boundary | 35.9% |
| C | 0 → 4.0s | ~4.0s | track change | 3.7% |

Observed facts, relied on by Phase 1:

1. **Repeat-one loops are observable** — a backwards position jump with no
   metadata change. Nothing has to be invented.
2. **A loop and a manual restart differ only in where the jump started**
   (98.9% vs 35.9% of duration).
3. **A loop and "previous" pressed at the very end of a track are
   indistinguishable**, and that is fine: in both cases the user heard ~99% of
   the track, so the stored facts are identical.
4. **Forward seeks are detectable** — position advanced 38.4s in 1.87s of wall
   clock; the excess is the seek.
5. **Backwards jitter of ~80 ms occurs with no user action**
   (`reported=128ms` then `reported=52ms`), so the replay threshold must sit far
   above it.
6. **`onMetadataChanged` re-fires with identical content** many times a second.
7. **The two sources disagree on duration**: 109000 ms from the media session
   (rounded to the second) against 108518 ms from the broadcast (exact). Do not
   mix them in one calculation.
8. **Broadcast `metadatachanged` fired only on the real track change**, never on
   the loop or the restart — cleaner semantics than the media session, but
   delivered late and in batches. Usable as a cross-check, not as primary.
9. **BUFFERING reports `speed=0.0`**, so it is already excluded from listened
   time by the existing position logic.

Still unknown after Phase 1 — see "Phase 1 results" for what was and was not
observed:
- what a natural end-of-track auto-advance looks like with repeat off,
- whether queue auto-advance is distinguishable from a manual "next",
- the exact shape of a session ending (Spotify force-stopped).

## Phase 1 results (2026-09-17)

Built on branch `phase1-capture-mvp`. 41 unit tests, all passing. Verified on the
Sony NW-A307 against live Spotify playback.

### What the data model stores

Three tables, designed for multiple services from day one:

- **`tracks`** — the canonical song (title, artist, album, duration). One row per
  song regardless of service. This is what Phase 3 scores.
- **`service_tracks`** — one row per service identifier for a song, so the album
  version and the single version can both point at one canonical track. The
  nullable `isrc` column lives here, because an ISRC is learned from a service
  and describes that service's recording. Null throughout Phase 1.
- **`listening_segments`** — one row per **play segment**: a continuous stretch
  of listening to one track. Not one row per callback.

Schema version 1 is exported to `app/schemas/` and committed, so Phase 2's
migration can be generated and tested rather than hand-written.

### The rule that keeps Phase 1 out of Phase 3's way

Segment rows store facts, never conclusions. There is **no outcome column** —
no COMPLETED, no SKIPPED. Anything Phase 3 wants to conclude is recomputable
from what is stored.

The end-reason set was cut down accordingly. The original plan had a `REPLAY`
value; that is a conclusion, not an observation. What capture can actually see
is that the position jumped backwards, so the value is **`POSITION_JUMP_BACK`**,
stored alongside the jump's `endJumpFromMs` and `endJumpToMs`. Every remaining
value — `TRACK_CHANGE`, `SESSION_END`, `STALE`, `UNKNOWN` — is something that
was observed.

**The replay threshold is not decided in Phase 1, and deliberately is not
stored.** Capture cuts a new segment at *every* backwards jump over 1000 ms —
about 12x the measured 80 ms jitter floor, so it catches every deliberate action
and no noise. That is a sensor setting, not a judgement about intent. Cutting
finely is reversible: `SegmentGrouping.merge()` folds adjacent rows back together
given any replay threshold, using the stored jump size. Cutting coarsely would
not be reversible — a jump never recorded cannot be recovered.

So "does skipping back ten seconds count as a replay?" is answered at read time,
can be answered differently later, and needs no listening to be recaptured. The
debug screen has a threshold selector (as recorded / 3s / 10s / 30s) so the
Phase 3 decision can be made by looking at real listening rather than guessed at.

### Listened time

`listenedMs` accumulates by one rule: take the **smaller** of what the clock says
was played (scaled by `playbackSpeed`) and what the position actually advanced.

| Situation | Smaller term | Effect |
|---|---|---|
| normal playback | they agree | correct |
| forward seek | the clock | the jumped-over part is excluded |
| playback stalls silently | the position | no time is invented |
| ~80 ms of backwards jitter | the position | loses up to 80 ms, never adds |

It always errs downwards. Under-counting is recoverable; invented plays are not.

### Verified on the NW-A307

| Check | Result |
|---|---|
| Room schema created, 3 tables | ✅ |
| Spotify URI captured as the service track id | ✅ `spotify:track:1xHutdgil2xlhdNLOL8Cr9` |
| Allowlist gate | ✅ ignored 1 non-allowlisted session, name not recorded |
| Metadata de-duplication | ✅ ~60 state callbacks produced 2 metadata rows |
| Segment written on track change | ✅ |
| `deviceId` is a random UUID, not a hardware id | ✅ |
| Duration sources disagree, as documented | ✅ session 172000 ms vs broadcast 171807 ms |

One stored row, read back off the device, showing capture recording only what it
actually saw: the app was reinstalled mid-track, so it observed the last 25 s of
a 2:52 song and stored exactly that — `listenedMs` 25106, `endPositionMs` 157013
(91%), `lastSampleAgeMs` 25106 — rather than claiming a full play because the
position was near the end.

### Learned while building

- **Steady playback emits no state callbacks at all.** Spotify publishes them on
  changes, not on a timer: 25 s and 158 s gaps were both observed mid-track with
  playback running normally. So `lastSampleAgeMs` is routinely large and is worth
  having as a column — it tells Phase 3 how much of a row's `listenedMs` rests on
  extrapolation. It also means the 30 s staleness guard sits closer to normal
  behaviour than Phase 0 suggested, and should not be tightened.
- **Bug found on device:** a finished segment is held briefly so it can still be
  corrected (see below), and it was only released once the *next* segment had
  accrued listening. When playback stops right after a track change, no further
  listening ever accrues and the finished segment was never written. Now released
  on elapsed time, driven by a timer, since callbacks stop when playback does.
- **A track change can arrive as a position reading before its metadata**, which
  is indistinguishable from a jump back to the start for that instant. Rather
  than guess, the closing segment is held for 2 s and the reason is corrected if
  the metadata change turns up. This doubles as the write batching — nothing
  touches storage on a callback.
- **Renaming the listener class invalidates the notification-access grant.**
  Android stores the grant against the full class name, so upgrading from the
  Phase 0 build leaves a stale entry pointing at the deleted spike class and
  capture silently does not run. Access has to be re-granted once.
- **AGP 9 needs an escape hatch for KSP.** Its built-in Kotlin support rejects
  the `kotlin.sourceSets` DSL that KSP uses to register generated code, so
  `android.disallowKotlinSourceSets=false` is set in `gradle.properties`.

### Still not observed

The three questions left open at the end of Phase 0 are still open. A manual
"next" now has a recorded shape — metadata change, then `BUFFERING` at position
~0 with `speed=0.0`, then `PLAYING` — but a natural end-of-track auto-advance was
never captured during this work, so the comparison that would distinguish the two
cannot be made yet. A session ending by force-stop was likewise not exercised.
Recorded as not observed rather than guessed at.

## Open questions — still open

- Initial scoring parameters (decay half-life, skip penalty, grace period).
  Belongs to Phase 3.
- Phase 3 scoring questions raised by the device testing above:
  - What backwards jump counts as a replay at all? Phase 1 stores every jump
    with its size and does not decide this; `SegmentGrouping.merge()` applies
    whatever answer Phase 3 picks, and the debug screen's threshold selector
    shows the effect on real listening history.
  - How does a restart or replay count — as a second play, or as a stronger
    signal than two ordinary plays?
  - Do repeat-one loops count per loop or once, and with what diminishing
    returns?
  - How do seek-skipped portions count — as time not listened, or as neutral?
  - What is the minimum listened time for a segment to count at all?

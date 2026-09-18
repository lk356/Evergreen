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
- [x] **Phase 2 — Spotify connection.** PKCE login, encrypted token storage,
      like sync, track matching, canonical track table. _(done 2026-09-17;
      merged to `main`, device-verified, same-song merge run on the Pixel)_
- [x] **Phase 3 — Core feature.** Scoring, daily Evergreen playlist sync,
      monthly snapshots. Seed scores from Spotify extended streaming history.
      _(done 2026-09-18; device-verified on the NW-A307 — 77k plays imported,
      380 tracks scored, Evergreen written, snapshot exported. 219 unit and
      183 instrumented tests.)_
- [~] **Phase 4 — Usability.** Real UI, snapshot search, like widget,
      scoring settings, data export. _(database backup already done in Phase 3;
      prompt in `docs/PHASE4_PROMPT.md`.)_
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
- [x] Spotify extended streaming history requested (arrives later)
- [x] GitHub CLI logged in, private repo created, first commit pushed

## Status
_Update at the end of each task: what was done, what's next, any blockers._

- Current phase: **Phase 3 done and merged.** Next is Phase 4 — see
  `docs/PHASE4_PROMPT.md`.
- Last update: 2026-09-18.

**Phase 3 is complete and device-verified on the NW-A307.** The whole
pipeline works end to end against a real Spotify account: six years of
streaming history imported, 380 liked tracks scored, the Evergreen playlist
written, and a snapshot exported. 219 unit tests and 183 instrumented tests
pass on the Pixel 10 Pro.

**Two questions came out of using it, and neither is settled.** Both are
recorded under "Open questions — still open" and carried into Phase 4: how
volatile a score should be when a track is skipped once in passing, and
whether a monthly snapshot should describe the last day of the month or the
month as a whole. Neither blocks anything, and the second one turns out not
to be urgent at all — see the note on why.

**Next:** paste `docs/PHASE4_PROMPT.md` into Claude Code to start Phase 4
(real UI, snapshot search, like widget, settings screen).

**Phase 2 is done.** Merged to `main`, device-verified on the Pixel 10 Pro
(login, 380/380 likes, 100% ISRC coverage, reconnect, DJ session), and the
same-song merge has been run: 2 audit rows in `track_merges`.

**Phase 3 planning (2026-09-17):**

- `docs/PHASE3_DECISIONS.md` is the handoff from the planning Project and
  answers every question in `docs/PHASE3_QUESTIONS.md`.
- **Check 0a:** Spotify DJ commentary rows are stored at capture and filtered
  only at read time. No capture change needed.
- **Check 0b, the calibration:** run over the extended streaming history
  (77,369 rows, 2020–2026) against the 380 synced likes. **Half-life 45
  days** (the liked-track return curve crosses 50% at 41–48 days under every
  definition tried; the fallback was 90). **Fraction-heard breakpoints 0.20
  and 0.85** (the lower one is sharp; the upper is a soft reading, 0.80
  defensible). Full tables in section 3.1 of the decisions. The analysis
  script was throwaway and is not in the repository.
- `docs/PHASE3_PLAN.md` is the implementation plan: 17 steps, each one
  commit, with the test list. Five playlist endpoints were verified against
  the live reference and **all differ from the pre-2026 tutorials**
  (`/items` not `/tracks`, `POST /me/playlists`, remove body uses `items`).
- Two schema migrations are proposed (v4: import columns on
  `listening_segments`; v5: `scores` cache, `evergreen_membership`,
  `snapshots`). Two new scopes (`playlist-modify-private`,
  `playlist-read-private`) and one new permission (`POST_NOTIFICATIONS`).

**Decisions answered 2026-09-17** (section 10 of the plan): all eight
approved as recommended, plus six raised afterwards: **the Walkman is the
writer device and the Pixel is the test bed**, an early database backup
button, instrumented tests never on the Walkman, import right after step 5,
never touch a playlist the app did not create, and unavailable tracks need no
special handling. Schema v4 and v5, the two playlist scopes and the notification
permission are pre-approved as described in the plan.

**Next:** merge `phase3-planning` into `main`, then paste
`docs/PHASE3_PROMPT.md` into Claude Code to start step 1 on branch
`phase3-scoring`.

Phase 1 replaced the spike with a real capture pipeline: listening is stored as
play segments in a Room database, one row per continuous listen, and the replay,
loop and seek-to-end bugs are fixed. Verified on the NW-A307 and the Pixel 10
Pro.

Follow-ups after review (branch `phase1-followups`, no schema change):
1. **Track identity now resolves by service ID before `trackKey`.** A known
   Spotify URI arriving with changed metadata used to fork the canonical track
   while the service row kept pointing at the original, leaving a segment's
   `trackId` and `serviceTrackRowId` disagreeing. Covered by instrumented DAO
   tests, confirmed to fail against the old order. Existing data checked:
   **0 mismatched segments.**
2. Wiping listening history now requires confirmation.
3. Outdated androidx dependencies refreshed.
4. This file updated.

**Next:** Phase 2 (Spotify connection), see `docs/PHASE2_PROMPT.md`. The
follow-ups are merged into `main`. Before designing the merge rule, verify
against the live Spotify Web API reference that track responses still return
ISRC — see "Phase 2 questions" under Open questions.

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

Answered in Phase 1 — see "Device verification" under "Phase 1 results":
- ~~what a natural end-of-track auto-advance looks like with repeat off~~ —
  observed: a plain `TRACK_CHANGE` ending within a percent or two of the
  duration, with no distinct callback signature.
- ~~whether queue auto-advance is distinguishable from a manual "next"~~ — **no**,
  not from any media-session signal. Only position reached separates them, and
  that alone is unsafe because a forward seek reaches the end too. Position
  reached plus listened ms together do separate them.

Still unknown:
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
- **Running instrumented tests uninstalls the app and destroys its database.**
  `./gradlew connectedDebugAndroidTest` (and Android Studio's equivalent)
  removes both APKs when the run finishes, taking the on-device listening
  history with it. Harmless for throwaway test data, but it will silently wipe
  real history later. Pull a copy first if the data matters, and prefer running
  instrumented tests on a device that is not being used to accumulate history.
- **Renaming the listener class invalidates the notification-access grant.**
  Android stores the grant against the full class name, so upgrading from the
  Phase 0 build leaves a stale entry pointing at the deleted spike class and
  capture silently does not run. Access has to be re-granted once.
- **AGP 9 needs an escape hatch for KSP.** Its built-in Kotlin support rejects
  the `kotlin.sourceSets` DSL that KSP uses to register generated code, so
  `android.disallowKotlinSourceSets=false` is set in `gradle.properties`.
  **This is a workaround on a flag AGP itself marks experimental, and a future
  AGP release may remove it.** If a version bump fails to configure with
  "Using kotlin.sourceSets DSL is not allowed with built-in Kotlin", that is
  this flag being withdrawn: the fix will be a newer KSP that registers its
  generated sources through `android.sourceSets` instead. Worth re-checking on
  every AGP upgrade rather than being surprised by it.

### Device verification (2026-09-17, Pixel 10 Pro, user-run)

Thirteen segments recorded during a live test session, read back off the device.
Each of the four checks is visible in the stored rows:

| # | Track | Stored | Reading |
|---|---|---|---|
| 10 | Afterthought | heard 4:00 of 4:02 (99%), `POSITION_JUMP_BACK` 4:00.54 → 0:00 | **repeat-one loop captured.** Phase 0 logged nothing at all here |
| 12 | Electro Swingity | reached 99%, **heard 8%**, 1 seek skipping 3:00 | **seek-to-end is not a completion.** Phase 0 called this COMPLETED |
| 8 → 9 | Vois Sur Ton Chemin | 31s, jump back 31.25s, then 11s | **replay makes two segments**, neither labelled a skip |
| 13 | bitkrushed | heard 1:32 of 1:34 (97%), one row | straight-through play is one segment, not several |
| 3, 4 | Crazy La Paint | two ~1.5s fragments with ~1.1s and ~1.6s jumps back | short fragments stored, not labelled |

### What this settled, and what it did not

**Natural end-of-track auto-advance was observed** (#6 at 98%, #13 at 98%). It
looks like an ordinary `TRACK_CHANGE` whose `endPositionMs` is within a percent
or two of the duration. There is no distinct callback signature.

**So queue auto-advance is _not_ distinguishable from a manual "next" by any
signal the media session provides** — only by position reached. And that
inference is not safe on its own: #12 also reached 99% and also auto-advanced,
but had been seeked there and was barely heard. Position reached says *where
playback got to*; only `listenedMs` says *whether it was heard*. Phase 3 needs
both, which is why they are separate columns.

**A session ending by force-stop is still not observed.**

### Worth watching

- Segments #3 and #4 are backwards jumps of 1.10s and 1.63s, a second or two
  into a track — just above the 1000 ms cut. These may be deliberate double
  presses of "previous", or they may be the player settling at track start. If
  jumps of 1–2 s near position zero turn out to be player noise, the cut is
  slightly too low. This costs nothing to get wrong: the jump size is stored on
  every row, so such segments can be merged away later with no recapture.
- Segment #8 closed with `lastSampleAgeMs` of 31.2s, just past the 30s staleness
  guard, and essentially its whole `listenedMs` came from that one
  extrapolation. The jump back then revealed the true position as 31.32s against
  an extrapolated 31.31s — the extrapolation was right to within 10 ms. Good
  evidence that the position logic is sound, and a reminder that long
  callback-free stretches are normal rather than exceptional.

## Phase 3 results (2026-09-18)

Built on branch `phase3-scoring`, 17 commits, one per step of
`docs/PHASE3_PLAN.md` section 11. 219 unit tests and 181 instrumented tests,
all passing on the Pixel 10 Pro. **Not yet exercised against a real Spotify
account, and the streaming history has not been imported.**

The `capture/` package is byte-for-byte unchanged.

### What was learned, and what the plan got slightly wrong

**The load window's error bound was stated per-contribution, not per-score.**
Plan 3.5 says the ten-half-life window "changes no score by more than 0.001".
That is true of one truncated event — ten half-lives back it retains 2^-10 —
but a score is a *sum*, and a track played daily for six years has 1,700
truncated plays whose tail adds up. Measured on such a track: the window
costs 0.063 of a score of 65.4, or 0.096%. The absolute error grows with how
much old listening a track has; the relative error does not.

It follows that windowing can reorder the ranking, which the plan did not
anticipate. Measuring *where* settles whether that matters: **every track the
window moves scores below the 0.1 record floor** — down in the tail the app
already considers not alive, where scores are packed densely enough that a
tiny absolute change reshuffles them. Nothing with recent listening moves. A
test asserts exactly that and fails if the error ever reaches a live track.

What actually prevents playlist churn is that the windowed ranking is
*deterministic*, not that it agrees with the full-history one. A track one
place from where six years of history would put it costs nothing; a track
flickering between runs costs a write a day.

**`org.json` is a stub in JVM unit tests.** Plan 3.4 assumed it could be used
for the parameters JSON because it is "already in use" — it is, but only on
the Android side. Verified: a JVM test using it throws
`RuntimeException: Stub!`. Since section 3 also requires the scoring package
to be pure Kotlin so all of it tests without a device, the two cannot both
hold. The parameters use a ~120-line hand-written flat-object codec instead;
no dependency was added. The same applies to `android.util.JsonReader`, so
the history parser's behavioural tests are instrumented while its structural
privacy guarantee stays a JVM test.

**`touchServiceTrack` never filled in a duration.** Found while writing the
test for "a track liked after the import starts scoring without a re-import".
The export carries no durations, so a service row created by the import has
`durationMs = 0`; before this fix, liking that track later left it at 0
forever and its imported plays stayed permanently unscoreable, because "how
much of it was heard" has no answer without a length. It now fills an unknown
duration and still never replaces a known one. This is what lets the import
run once, early, rather than be re-run after every new like.

**Playlist entries carry both `item` and `track`.** The 2026-09-18 re-check
turned up that `GET /playlists/{id}/items` returns the deprecated `track`
field alongside the current `item`. The code reads `item` and falls back to
`track`. The fallback earns its place: a playlist that silently read as empty
would look to the diff like every track needing to be added again.

**`rank` is a reserved word in SQLite** (the window function), so the score
cache's column has to be quoted in every query that names it.

**Ten parameters, not eight.** The plan counts eight; the playlist join and
leave ranks (100 and 130) are settings too. Section 3.6 already passes them
to `Membership.decide` through `params`, and a snapshot has to record its
cutoff or its ranking cannot be read back later. Defaults are unchanged.

### Found during device verification (2026-09-18)

**The Evergreen preview crashed both devices.**
`NetworkOnMainThreadException`. The debug screen launches the dry run from
`rememberCoroutineScope()`, which is Compose's *main-thread* dispatcher, and
`ApiSource` is a plain blocking interface. Every test passed because they all
drive it through `runBlocking` on the instrumentation thread, and the
scheduled worker was fine because `CoroutineWorker` already runs off the main
thread — so the same code was correct by one route and fatal by the other.

The lesson worth keeping: **a `suspend` function is not automatically off the
main thread.** It runs wherever its caller is unless it says otherwise. The
fix puts the dispatcher inside the functions that block rather than asking
callers to remember — `EvergreenSync.dryRun`/`sync` and `Snapshots.export` on
`Dispatchers.IO`, and `ScoreService.ranking`/`breakdown` on
`Dispatchers.Default`, that last one found before it bit: scoring 380 tracks
is seconds of CPU on the Walkman and would have frozen the UI rather than
crashing it.

`EvergreenSyncTest` now drives the sync on `Dispatchers.Main`, as the UI
does, and asserts the API is never reached from there.

**The Scores tab showed one row on the NW-A307.** Its screen is 339 × 602 dp
and the fixed header — two buttons, nine parameter rows, three counts — came
to about 330 dp, leaving the list whatever was left. None of the header could
be scrolled away because it sat outside the scrolling area. The tab is now a
single `LazyColumn` with the header as an item, and the parameter summary
collapses to one line. Worth remembering for every later screen: **the
Walkman has about 60% of the Pixel's usable height**, so a layout that looks
generous on the Pixel can leave nothing at all there.

**A worktree build has no Spotify client ID.** `local.properties` is
gitignored, so a build made from a git worktree reports "Not configured" and
cannot refresh a token, which looks exactly like an expired connection.
Copy the file into the worktree, or build from the main checkout.

### The mistake worth recording

**Instrumented tests were run on the Walkman by accident**, destroying that
device's database. The Pixel had been the only device attached when it was
checked; between that check and a later run the Pixel was unplugged and the
Walkman attached, and `connectedDebugAndroidTest` — a command that had been
safe minutes earlier — wiped it. Nothing irreplaceable was lost, because the
streaming history had not been imported yet. Next week it would have been.

The rule already existed, in a document. A document protects nothing when the
thing that changed is which cable is in the laptop, so **the rule now lives in
the build**: `connectedAndroidTest` queries `adb devices` first and fails,
naming the offending device, if a Walkman is attached. `-PallowWalkmanTests=true`
overrides it deliberately. See the comment in `app/build.gradle.kts`.

### Design notes worth keeping

- **A score is computed, never accumulated.** `RecomputeTest` was written
  before the score cache existed and still passes with it, which is what stops
  the cache quietly becoming a store of stale values. Cached rows carry the
  hash of the parameters that produced them; a row under different parameters
  is not a stale copy of the right answer, it is the right answer to a
  different question. Dropping the `scores` table is always a valid repair.
- **The fraction-heard curve is a sum of two ramps, not a five-way branch.**
  Both forms agree on the defaults, but the breakpoints are user-editable and
  can be set closer together than the ramps are wide; the branch form has
  overlapping cases there and could score a longer listen lower than a shorter
  one. The sum cannot.
- **"No duration known" is not "heard none of it".** DJ commentary and
  imported rows both carry zero durations; scoring either as a 0% listen would
  charge a rejection penalty for something never observed.
- **Imported rows leave null what the export cannot say** — position, seeks,
  the age of the last reading. Null means "not observed", which is a different
  statement from 0 ("observed at the start").
- **The backup uses SQLite's `VACUUM INTO`,** not a file copy. In WAL mode the
  recent writes live in the `-wal` log, so copying the main file alone would
  silently lose the most recent listening.
- **The importer has two independent de-duplication defences:** the SHA-256 of
  each completed file, and a per-row check. The second catches the same play
  arriving in two different files, which the first cannot.
- **Negatives are never capped.** Skipping a track ten times in one sitting is
  the clearest rejection there is and must not count for less the more
  emphatically it happened.

### Still not observable

- Whether a *song* was chosen by Spotify DJ. Only the commentary rows around
  it hint at it, which is why the inference is used for nothing but forgiving
  a penalty.
- Whether an add was refused because a track is unavailable or for some other
  reason. Spotify refuses the batch whole; the code retries one at a time to
  find which URI, and counts it either way.
- `offline_timestamp` in the export is not used; `ts` is the end of the stream
  in UTC. A known simplification, with a test naming it.

### Phase 3 device verification (NW-A307, 2026-09-18) — passed

Steps A to F of the checklist below were run on the Walkman against the real
Spotify account. Everything worked: Spotify connected with all three scopes,
380 likes synced, the same-song merge run, the full streaming history
imported, 380 tracks scored, the Evergreen playlist written after the dry run
and confirmation, and a snapshot taken and exported.

**Step G — leaving it a day to watch the scheduled worker fire — has not been
done**, for the obvious reason that it takes a day. The worker's logic is
covered by tests and the manual "Sync now" path exercises the same code, so
this is an observation still owed rather than a risk being carried.

**The user's own reading of the scores: "pretty accurate, especially to
recent listening history."** That is the judgement no test could make, and it
is what the calibration was ultimately for.

Two bugs were found and fixed on the way; they are described above.

### The verification checklist that was followed

Do these on the **NW-A307**, in order. Tick each as it passes. The app must be
built from a checkout that has `local.properties` with `spotify.clientId`;
without it the Spotify tab says "Not configured" and stops there.

**Before you start: unplug the Walkman when running any test.** The build now
refuses `connectedAndroidTest` while a Walkman is attached, but `installDebug`
is always safe.

**A. Install and connect** (Phase 2 was only ever verified on the Pixel)

1. Plug the Walkman in. On the computer, `adb devices` — accept the debugging
   prompt on the Walkman if one appears.
2. `./gradlew installDebug`
3. Open **Evergreen**. Allow notifications when asked (declining is fine; it
   only costs the reconnect warning).
4. If **Notification access: NOT GRANTED** shows at the top, tap **Grant
   access**, find **Evergreen**, turn it on, and come back.
5. **Spotify** tab → **Connect Spotify** → log in.
   - [ ] The consent screen appears and lists **more** than the app asks for —
         it mentions profile data. That is Spotify's wording, not a scope leak;
         the app has no profile endpoint at all. It should mention your
         **library** and your **private playlists**.
6. Tap **Sync now**. Wait a few seconds and pull down / reopen the tab.
   - [ ] **Liked now** reaches **380**
   - [ ] **With an ISRC** reaches 380 (100%)
7. Tap **Merge dry run**, read the plan, then confirm it.
   - [ ] 2 ISRCs over 4 tracks, as on the Pixel

**B. Set the writer device and take a backup — before anything else**

8. Under **This device**, turn on **This device writes Evergreen**.
   - [ ] The switch stays on after leaving and reopening the tab
9. Tap **Back up database** → choose **Downloads** → **Save**.
   - [ ] It says "Backed up NNN KB". The file is
         `Downloads/evergreen-backup-<date>.db` on the Walkman.

**C. Import the streaming history**

10. Copy the export zip to the Walkman:
    `adb push my_spotify_data.zip /sdcard/Download/`
11. **Import streaming history** → pick the zip.
12. Leave the screen open; it can take a few minutes.
    - [ ] **Rows read** ≈ 77,000
    - [ ] **Plays written** close to that
    - [ ] **Not tracks (podcasts etc.)** ≈ 27
    - [ ] **Covered by capture** 0 (the export ends before capture began)
13. Tap **Import streaming history** and pick the **same zip** again.
    - [ ] It reports **Files already imported: 1** and writes nothing

**D. Read the scores before anything is written to Spotify**

14. **Scores** tab → **Recompute**.
    - [ ] It says how many tracks were scored, and how long it took. If it
          takes more than about a minute on the Walkman, tell me.
    - [ ] **Liked tracks scored** ≈ 380
15. Scroll the list.
    - [ ] The top tracks are songs you genuinely still play. **This is the
          judgement only you can make.** If they are not, stop here and tell
          me what looks wrong before going any further.
    - [ ] A line marked **playlist cutoff — rank 100** appears after the 100th
16. Tap any track.
    - [ ] The breakdown lists events oldest first, with "raw", a decay
          multiplier, and what each is worth today
    - [ ] The values sum to the score shown at the top
    - [ ] Old 2019/2020 plays show a decay weight of essentially zero
17. Tap **Parameters** → change `"upperBreakpoint"` from `0.85` to `0.8` →
    **Save and rescore**.
    - [ ] It rescores and the ranking shifts slightly
    - [ ] Tap **Parameters** → **Reset to the calibrated defaults** → save
18. Take a second backup now that the import is in (step 9 again).

**E. The first Spotify write**

19. **Spotify** tab → **Preview Evergreen sync**.
    - [ ] It lists about 100 to add and 0 to remove
20. Tap **Confirm the first write** → read the dialog → **Write it**.
    - [ ] It reports how many were added
    - [ ] Any "unavailable" count is expected — desktop Spotify shows 380
          liked songs and mobile 374
21. Open Spotify on any device.
    - [ ] A **private** playlist called **Evergreen** exists with ~100 tracks
    - [ ] Your **Liked Songs** are unchanged
    - [ ] No other playlist was touched
22. Back in Evergreen, tap **Sync now**, wait, and reopen the tab.
    - [ ] **Last written** is today and nothing was written twice (the
          once-a-day guard)

**F. Snapshots**

23. **Take a snapshot now**.
    - [ ] It reports the month and a track count of ~380 — the whole ranking,
          not just the 100 in the playlist
24. Tap **Export** next to it.
    - [ ] A new playlist `Evergreen — 2026 September` appears in Spotify with
          ~100 tracks
    - [ ] The original **Evergreen** playlist is unchanged

**G. Leave it alone for a day**

25. The next day, check the Spotify tab.
    - [ ] **Last written** has moved on, or says nothing needed changing

_All of A to F passed on 2026-09-18. Kept as the procedure to repeat on any
fresh install or new device._



## Phase 2 results (2026-09-17)

Built on branch `phase2-spotify`. 54 instrumented + 68 unit tests, all passing
on the Pixel 10 Pro. **Not yet exercised against a real Spotify account.**

### The API was verified first, and three things had changed

`CLAUDE.md` says to check every endpoint against the live reference rather than
trusting training data. That paid for itself three times.

| Question | Answer | Where checked |
|---|---|---|
| Does the track object still return `isrc`? | **Yes** — but it was *removed* for development-mode apps in Feb 2026 and **reverted in March 2026**. Present on `GET /tracks/{id}` and inside `GET /me/tracks`, not deprecated. | changes/february-2026, changes/march-2026, reference/get-track |
| Current endpoint for reading likes? | **`GET /me/tracks`, unchanged.** The `/me/library` consolidation moved only the *writes* (`PUT`/`DELETE`). The read stayed put and is listed under "endpoints still available". | changes/february-2026, reference/get-users-saved-tracks |
| Scopes needed? | **`user-library-read` only.** | concepts/scopes |
| Redirect URI schemes? | HTTPS, loopback, or a custom scheme. `com.example.evergreen://auth` was **accepted by the dashboard**, despite reports of custom schemes being rejected on apps created after 9 April 2025. | blog/2025-02-12 security requirements, dashboard |
| Refresh token lifetime? | **Six months from the original authorization**, not extended by refreshing. Expiry is `400 {"error":"invalid_grant"}`; Spotify says discard and re-login, do not retry. | blog/2026-06-18 |
| Rate limits? | Rolling **30-second** window; 429 carries `Retry-After` **in seconds**; `QUOTA_EXCEEDED` is a `reason` field in the error body, enforced differently from rate limits and with no published numbers. | concepts/rate-limits, concepts/api-calls |

### ⚠️ The batch track endpoint is gone

**`GET /tracks?ids=` ("Get Several Tracks") was removed for development-mode apps
in February 2026.** Spotify's own migration guide says to *"fetch items
individually instead"*. The Phase 2 brief asked for batched ISRC lookups; that is
no longer possible, and this is the workaround:

| Source of a track | How its ISRC arrives | Cost |
|---|---|---|
| One of your liked songs | free, inside the `GET /me/tracks` like sync | ~1 request per **50** tracks |
| Listened to but not liked | `GET /tracks/{id}`, individually | 1 request per track |

So the bulk case stayed cheap by accident of the saved-track response carrying
full track objects. Only the unliked tail costs one request each — paced,
capped per run, and recorded so a failure resumes rather than re-fetching.

Also removed in Feb 2026 and worth knowing before Phase 3: **`POST
/users/{user_id}/playlists`**, replaced by **`POST /me/playlists`**.

### ⚠️ Spotify has no playlist folders, and never has

Checked because the original plan mentioned creating "playlists/folders". The
Web API cannot create, read or list folders. It is a long-standing gap, not a
scope we are missing. Phase 3/4 options, none needing folders:

- one live `Evergreen` playlist, rewritten as scores move (**recommended**);
- snapshots exported as `Evergreen — 2026 August`, which sort together and can
  be dragged into a folder by hand, once;
- snapshots kept in-app and searchable, exported only on request (already the
  plan).

### Schema v2

Generated by Room from the committed `schemas/1.json`, not hand-written.

**All three foreign keys became `RESTRICT`**, not just the one the brief named.
Fixing `listening_segments → tracks` alone leaves a hole: a merge re-points the
segments, then deletes the losing track, which cascades away its `service_tracks`
row — the Spotify URI and the ISRC — and the old `SET NULL` on
`serviceTrackRowId` then blanks the segments' link to it. The segments would
survive with their listened time intact and no record of *which recording* they
were. Same table rebuild either way.

Consequence worth remembering: **`deleteEverything()` can no longer rely on a
cascade.** It deletes children before parents explicitly, and there is a test
for it. Any future bulk delete has to do the same or it will hit a constraint
failure — which is the behaviour we asked for.

**The like archive is two tables**, following the Phase 1 rule that facts and
conclusions are stored separately:
- `like_events` — append-only, never deleted. Every like, unlike and re-like.
- `likes` — current state per service track; `unlikedAt IS NULL` is the only
  meaning of "liked now".

Events are written only on *transitions*. One row per liked song per sync would
be thousands of rows a day describing nothing having happened.

`service_tracks` gained `isrcLookupAt` / `isrcLookupAttempts` /
`isrcLookupError`, so a failed lookup is retryable without re-fetching — which
matters much more now that a re-fetch costs one request per track.

### The rule that stops a rate limit destroying the archive

The reconciliation that marks songs unliked **runs only after a sync that read
every page**. A run stopped by a 429, a dropped connection or a flat battery is,
from the database's side, indistinguishable from the user having unliked every
song it did not reach. Without the guard, one rate limit would mass-unlike the
archive. There is a test named for exactly that.

### Auth

Hand-written PKCE, ~200 lines, chosen over AppAuth. The reasoning shifted during
the phase and is worth recording: the user said they may not maintain this
project actively, which initially sounded like an argument *for* a library.
It is the opposite. OAuth 2.0 + PKCE is a settled standard with no reason to
rot, whereas an unmaintained app carrying AppAuth carries whatever CVE turns up
in AppAuth with nobody watching. What actually breaks is Spotify changing
endpoints — three times this year — and no library helps with that.

So the code is built to fail **loudly and legibly**: every endpoint carries the
reference URL and the date it was checked, all network access funnels through
one file, and refresh expiry is a visible state rather than a silent stall.

Security decisions, each with a test:
- `SecureRandom` verifier, `S256` only — there is no code path that can emit
  `plain`;
- the verifier never reaches the browser;
- single-use `state`, compared in constant time;
- **host allowlist in code** (`accounts.spotify.com`, `api.spotify.com`) — exact
  match, so `api.spotify.com.evil.test` is refused; Android has no manifest-level
  way to express this;
- cleartext refused even to an allowed host;
- no client secret in any request;
- AES-256-GCM with a non-extractable Android Keystore key, StrongBox where
  available; **not** `security-crypto`, which is deprecated;
- tokens live outside the Room database, so a future database export physically
  cannot contain a credential;
- nothing logs a token, code or verifier.

A custom-scheme redirect can in principle be hijacked by another app. That is
precisely the attack PKCE defends against: the intercepted code cannot be
redeemed without the verifier, which never leaves the process.

### Learned while building

- **Development-mode apps must allowlist even the owner.** The dashboard showed
  **0/5 users** although the prep checklist claimed the account was allowlisted.
  Spotify: *"Users may be able to log into a development mode app without having
  been allowlisted... However, API requests... will receive a 403."* Login looks
  fine and every request then fails — a confusing failure worth knowing the shape
  of. The API layer logs the likely cause on a 403.
- **`room-testing` 2.8.5 needs `kotlinx-serialization-core` 1.8.1.** The
  instrumented classpath pins `-core` to 1.7.3 `strictly` while `-json` resolves
  to 1.8.1, and `runMigrationsAndValidate` dies with `AbstractMethodError` on
  `FieldBundle$$serializer` — `GeneratedSerializer.typeParametersSerializers()`
  is defaulted in 1.8.x and still abstract in 1.7.x. Fixed with a `force` on the
  androidTest configurations. The matcher has to be
  `contains("AndroidTest", ignoreCase = true)`, because the configuration that
  feeds the test APK is `debugAndroidTestRuntimeClasspath` and does not start
  with "androidTest".
- **Room auto-migration handles a foreign-key change** by rebuilding the table,
  copying every row and running `foreign_key_check`. It only needed a
  `@ColumnInfo(defaultValue = "0")` on the new non-null column.
- **A "now" injected into one class is not enough.** `PendingAuthStore` had its
  own clock while `SpotifyAuth` had an injectable one, so a staleness check could
  disagree with the timestamp an attempt was saved with. Found by the tests.

### If the Spotify connection breaks, check these first

In rough order of likelihood:

1. **"Connection expired"** on the Spotify tab — the six-month refresh token
   died. Tap **Reconnect Spotify**. Expected roughly twice a year; not a bug.
2. **Everything 403s** — the account is no longer on the app's allowlist.
   Dashboard → the app → **User Management**.
3. **Login never returns** — the redirect URI in the dashboard no longer matches
   `SPOTIFY_REDIRECT_URI` in `app/build.gradle.kts`. They must match exactly,
   including case and trailing slash.
4. **"Not configured"** — `spotify.clientId` is missing from `local.properties`
   (gitignored, so it does not survive a fresh clone).
5. **A 404 or a changed field** — Spotify moved something again. Every endpoint
   in `SpotifyApi.kt` and `SpotifyAuth.kt` carries the reference URL and the date
   it was last verified. Re-check those pages.
6. **Development mode requires the owner to hold Spotify Premium.** If the
   subscription lapses, the app stops working.

## Open questions — still open

### Raised by using it (2026-09-18) — for Phase 4

**How volatile should a score be?** Observed in use: a single skip — "not in
the mood for this right now" rather than "I have gone off this song" — visibly
drags a score down, though it recovers after one play. That matches the
design: a skip is −0.5 and a full play +1.0, so one play more than undoes one
skip.

But there is an asymmetry worth naming, because it was deliberate and may be
wrong. **The daily cap applies only to positives.** One track can contribute
at most +2.0 in a day, while negatives are unbounded, so four skips in one
evening is −2.0 — more than a whole day of listening can win back. The
reasoning at the time was that repeated skipping is the clearest rejection
there is and should not be discounted. The counter-case the user actually hit
is that repeated skipping in one sitting is often one mood, not four
judgements.

Three levers, in increasing order of change:

1. **Lower `negativeWeight` from −0.5 to −0.25.** It is a setting, so this can
   be tried today on the Scores tab and reverted if the rankings get mushy.
2. **Cap negatives per day too**, symmetric with the +2.0 cap. This targets
   the "one bad evening" case exactly, without changing what a skip means. It
   needs a new parameter and a change to `TrackScorer.applyDailyCap`.
3. **Lower `lowerBreakpoint` from 0.20 to about 0.10**, so only a near-immediate
   skip counts as rejection at all. The calibration read 0.20 sharply, so this
   is the one to try last.

What would actually settle it is being able to *see* volatility rather than
feel it: a score-over-time line on the Scores tab, which is a Phase 4 UI job.

**Should a snapshot describe the last day of the month, or the month as a
whole?** Currently the last day. Genuinely open — and, usefully, **not urgent,
because a past ranking is recomputable.** A score is a pure function of
(segments, like events, parameters, now); segments and `like_events` are both
append-only and never deleted, and `evergreen_membership` records every join
and leave with timestamps. So:

- "Everything that was in Evergreen at any point in August" is **already a
  query** over existing data — no schema change, no decision needed.
- "The ranking as it stood on any past date" can be recomputed, with one code
  change: the liked set is currently read as current state
  (`likes.unlikedAt IS NULL`) and would have to be derived from `like_events`
  instead.
- The one real gap: **parameter changes are not recorded with timestamps.**
  Only the current value is in `sync_state`, and past values survive only
  inside snapshots. A small `parameter_history` table, or an append-only log,
  would close it and make any past date fully reconstructible. Cheap, and
  worth doing before the parameters get tuned much.

So the snapshot definition can be changed later, and even applied
retroactively. It does not need to be right now.

**The notification-access banner takes too much room**, especially on the
NW-A307's 602 dp screen, and is pure noise once access has been granted.
Deferred to Phase 4 by the user's own call; it should be the first small UI
fix, since it costs height on every tab of the device the app actually runs on.


- ~~Initial scoring parameters (decay half-life, skip penalty, grace period).~~
  Decided and calibrated 2026-09-17: see `docs/PHASE3_DECISIONS.md` section
  3. The skip penalty and grace period were replaced by the fraction-heard
  curve and the like event weight.
- ~~The eight Phase 3 build decisions in `docs/PHASE3_PLAN.md` section 10.~~
  All answered 2026-09-17 and built as answered.
- **Do the default parameters produce a ranking that matches what the user
  actually still listens to?** Unanswerable until the import has run on real
  history. Step D15 of the verification below is where it gets decided, and
  every parameter is a setting precisely so the answer can be "not quite".
- **Is the upper breakpoint 0.85 or 0.80?** The calibration read 0.85 under
  the decisions' definition and 0.80 counting only next-button skips. Left at
  0.85; the Scores tab makes the difference visible over real listening.
- **Does the replay threshold of 10 s match how the user actually replays?**
  The export cannot inform it — a replay appears there as two rows. Revisit
  once the app has captured a few months of its own replay data.
- **How long does a full rescore take on the NW-A307?** Measured on the Pixel
  only. The load window and the batched import exist for this, but the number
  is unknown until step D14.
### Phase 2 device verification (2026-09-17, Pixel 10 Pro, user-run)

| Check | Result |
|---|---|
| PKCE login via Custom Tab | ✅ |
| Granted scope | ✅ `user-library-read` — exactly what was requested |
| Likes synced | ✅ **380**, matching desktop Spotify's count |
| ISRC coverage | ✅ **380/380, 100%** — all from the like sync, zero individual fetches |
| Tokens encrypted at rest | ✅ one base64 blob, no readable token in `shared_prefs` |
| Reconnect state after revoke | ✅ red card, "Connection expired — reconnect Spotify" |
| Reconnect restores sync | ✅ |
| Merge dry run | ✅ 2 ISRCs over 4 tracks, no false positives |

**100% ISRC coverage from the like sync alone** is better than budgeted for. The
individual-fetch path (needed because the batch endpoint was removed) had nothing
to do for liked songs, and only ran for tracks played but not liked.

**Spotify's own counts disagree, and that is Spotify's doing.** Desktop reports
380 saved tracks, mobile reports 374. The user confirmed the difference is
unavailable tracks, which the mobile client hides and the desktop client does
not. The Web API returns the full set, which is what the archive wants: a track
going unavailable in one region should not silently vanish from listening
history.

**The consent screen asks for more than the scope does.** It lists "View your
Spotify account data — name, username, profile picture, followers, public
playlists" alongside the library line, despite only `user-library-read` being
requested. The token response confirms only `user-library-read` was *granted*,
so this is Spotify's consent copy being broader than the grant. Worth knowing so
it is not mistaken for a scope leak later. Independently: the app contains no
profile endpoint at all — the only four URLs it can reach are `/authorize`,
`/api/token`, `/v1/me/tracks` and `/v1/tracks/{id}`, enforced by a host
allowlist.

### Verifying expiry needs a local nudge

Revoking access at spotify.com/account/apps kills the **refresh** token but
leaves the existing **access** token working until it expires on its own, about
an hour later. A sync immediately after revoking therefore just succeeds —
observed on device:

```
09-17 18:18:48  I LikeSync: like sync complete: 380 liked, 0 newly unliked
```

That is correct behaviour, not a bug, but it means the reconnect path cannot be
tested by revoking alone without waiting an hour. The Spotify tab has a
**"Force token refresh (test reconnect)"** button that discards the access token
locally so the next sync must refresh. It fakes no state: the real refresh runs
against the real endpoint, and a revoked grant then returns `invalid_grant` for
real. Verified end to end.

Also fixed while verifying: the connection card only re-read its state when the
login flow returned, so a background expiry would have sat behind a stale
"Connected". It now re-reads on resume.

### Spotify DJ — examined 2026-09-17 (Pixel 10 Pro, ~8 minute session)

**Commentary is observable, and it is distinguishable. Filtering is possible.**

Captured live over adb while the user ran a DJ session: three commentary
segments, ~17 track changes, several skips and a natural auto-advance. Read from
two independent sources — Evergreen's own capture log (which records `mediaId`)
and `dumpsys media_session` (which does not filter anything).

**1. Does commentary surface as a media-session track?** **Yes.** Every spoken
segment produced a metadata callback and a stored segment, exactly like a song.

**2. What identifier does it carry?** This is the finding everything else rests
on:

```
[METADATA] Up next — DJ X | album=DJ duration=(unknown) id=spotify:media:2j7w4Q2yMxa9ekzCdGhKUc
[METADATA] Love me forever! — Ado [4:09] | album=Love me forever! duration=249000ms id=spotify:track:2j7w4Q2yMxa9ekzCdGhKUc
```

Commentary carries **`spotify:media:<id>`, not `spotify:track:<id>`**. A
different URI scheme, on the same `mediaId` field the capture already stores.

Note the two lines share the same opaque id — the commentary is keyed to the
track it announces, differing only in scheme. In two of the three observed cases
the id matched the *following* track ("Up next"); in the third it matched the
preceding one. **So commentary has no identity of its own**, and cannot be
looked up as an entity. It can only be recognised, which is all that is needed.
Because the two URIs are different strings, they occupy different
`service_tracks` rows and never collide.

**3. Is DJ playback distinguishable from normal playback?** **Yes, by three
independent signals**, any one of which is sufficient:

| Signal | Commentary | A real track |
|---|---|---|
| `serviceTrackId` scheme | `spotify:media:` | `spotify:track:` |
| duration | `0` (unknown) | real, in ms |
| title / artist / album | `Up next` / `DJ X` / `DJ` | the real values |

The scheme is the one to depend on. The metadata strings are user-visible text
and will be localised or reworded; the URI scheme is structural.

**What is NOT distinguishable:** a *song* the DJ chose looks exactly like a song
the user chose. `appPackage` is `com.spotify.music` either way, and the track
URI is an ordinary `spotify:track:`. Only the commentary rows around it reveal
that a DJ session was in progress. So "was this play DJ-selected?" is
answerable only by inference from neighbouring rows and their timestamps, not
from the row itself. Recording that as a known limit rather than inventing a
field for it.

**What this means for the stored data**

- All commentary collapses into **one canonical track** (`trackKey` =
  `up next|dj x|dj`), because the metadata is identical every time. Convenient:
  one row to filter, not thousands.
- But each commentary segment creates **its own `service_tracks` row**, since
  each carries a different `spotify:media:` id. Verified on device: 3 commentary
  segments produced 3 service rows and 1 canonical track. **This grows
  unboundedly with DJ use** and is worth watching.
- Commentary segments store `listenedMs = 0`, `endPositionMs = 0`,
  `trackDurationMs = 0`. Harmless to any scoring rule that requires listened
  time above zero.

**Changed in response**

Commentary is **kept, not discarded** — it is a fact about what played, and the
project's rule is that capture stores facts for scoring to interpret later.
Filtering it at capture time would bake in a conclusion. But two things were
fixed:

- Non-track URIs are **excluded from the ISRC backfill queue**. No track
  endpoint will resolve `spotify:media:`, and leaving it in would have burned
  five attempts over five weeks to rediscover what the identifier already says.
- ISRC coverage counts **real tracks only**, with commentary shown on its own
  line. Otherwise coverage would drift downwards the more the DJ is used, making
  a category that does not apply look like a backfill falling behind.

Both pinned by instrumented tests, so a later change cannot quietly start
treating commentary as a song.

**Phase 3 can now decide this from data already recorded.** Nothing needs
recapturing.

### Crossfade inflates position reached — found during the same session

DJ crossfades between tracks, and the transition shows up as a **large forward
seek on the outgoing track**:

```
City Cook — Caravan Palace  listened=0:24  reached=3:09 (92%)  seeks=1 skipped=2:44
Tokyo — Dwin, Kush Kush     listened=0:15  reached=2:05 (94%)  seeks=1 skipped=1:49
```

Twenty-four seconds heard, reported as 92% of the way through. Under the Phase 0
logic — which read the amount played off the final position — both of these
would have been recorded as near-complete plays of songs that were barely heard.

This is the seek-to-end bug arriving through a route Phase 1 did not anticipate:
not a user seeking, but the player's own crossfade. The Phase 1 design handles it
correctly and without modification, because `listenedMs` accumulates only while
playback actually runs and `endPositionMs` is stored separately. It is a third
independent confirmation that **position reached must never be used as a proxy
for how much was heard** — and a reminder that the two columns exist for a
reason if anyone is ever tempted to collapse them.

### Phase 2 questions — answered

- **Same-song merging. Rule approved 2026-09-17; not yet implemented, nothing
  merged.** ISRC is available (removed Feb 2026, reverted March 2026), and the
  dry run over 380 real tracks found **2 ISRCs covering 4 tracks, with no false
  positives**:

  | ISRC | Tracks |
  |---|---|
  | `QZES82519626` | CAN'T GET HIGH — album *RENO (ALL IN)* / CAN'T GET HIGH — album *CAN'T GET HIGH* |
  | `USQX92504223` | Dracula — album *Deadbeat* / Dracula — album *Dracula* |

  Both are the textbook case: one album release, one standalone single, same
  recording, split only because `trackKey` includes the album.

  **The rule:** two canonical tracks merge when their `service_tracks` rows
  share an ISRC. The survivor is whichever has more listening segments, ties
  broken by lowest id (first seen). Merging re-points the loser's segments *and*
  its service rows, then deletes the loser's `tracks` row. Every merge is logged
  to a `track_merges` table. Manual trigger after a dry run, never automatic
  during sync.

  **Likes are unaffected**, because they attach to `service_tracks` rather than
  to canonical tracks. Both the single and the album version stay in the archive
  as separate entries; only the song the scorer sees is unified.

  **Why merge at all:** unmerged, playing the single sometimes and the album
  version other times produces two half-fed scores, each decaying as though the
  song were half-abandoned.

  **Known limit:** a remaster gets a fresh ISRC and so will *not* merge. That is
  defensible — it is a different recording — but this rule catches same-recording
  pairs only, not every "same song" pair.

  **Implemented in schema v3** (which only adds the `track_merges` audit table;
  no existing table is touched). The order inside `mergeTracks` is load-bearing:
  segments and service rows are re-pointed *first*, and only then is the losing
  `tracks` row deleted. Under the v2 RESTRICT keys, getting that backwards does
  not quietly destroy history — it raises a constraint error and the transaction
  rolls back. The protection and the operation were designed together.

  Tested with listening history on **both** sides of the merge, which the live
  data does not yet have and which is the case where a mistake would cost
  something: 8 segments in, 8 segments out, one canonical track, both Spotify
  URIs still present and pointing at the survivor, both likes intact, no
  dangling references, and the whole thing recorded in `track_merges`.
- **Protect history from deletes.** Done, and widened: **all three** foreign keys
  are now `RESTRICT`, not only `listening_segments → tracks`. See "Schema v2"
  above for why fixing one leaves a hole.
- **Login design.** Hand-written PKCE, not AppAuth. `androidx.browser` added for
  a Custom Tab (RFC 8252's recommendation for native apps); no third-party
  dependency added. Tokens in an Android Keystore key, not `security-crypto`.
  Reconnect is a distinct visible state with a two-week advance warning. Redirect
  URI `com.example.evergreen://auth`, confirmed accepted by the dashboard.

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

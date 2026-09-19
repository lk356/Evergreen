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
- [x] **Phase 4 — Usability.** Real UI, a home screen, snapshot browsing,
      scoring settings, score over time, notifications, data export. _(built
      2026-09-18 on `phase4-usability`; all eight steps of the prompt done,
      **device-verified on the NW-A307 2026-09-19 and merged to `main` the
      same day** as pull request #2. The like widget was cut by the user — see
      below. Database backup was already done in Phase 3.)_
**`docs/SYNC_FOUNDATION.md`** sits across phases 5b, 7 and 9: what has to be
true of the *data* for the sync transport to stay a free choice, what is
already built, and the one hard prerequisite. Read it before designing
anything that moves facts between devices.

- [~] **Phase 5 — Cross-device (Android).** 5a: the Pixel as a read-only
      mirror of the Walkman, transported as a file. **Done 2026-09-19;
      device-verified on both devices and merged to `main` as pull request
      #3.** 5b: full peer sync, both devices capturing, segments merged —
      **not started**, and blocked on the phantom-segment question now
      answered under "Open questions". See `docs/PHASE5_QUESTIONS.md`.
- [ ] **Phase 6 — Cross-service.** Second service adapter, primarily the
      Sony Walkman player for local files on the NW-A307; ISRC/MusicBrainz
      matching. Rule to hold to: scoring merges across services, output is
      per-service.
- [ ] **Phase 7 — Desktop capture and sync backend.** Linux agent (MPRIS)
      first, self-hosted sync backend, Windows agent (WinRT media session
      API) after.
- [ ] **Phase 8 — Nice-to-haves.** Richer stats, per-artist figures in the
      style of the Spicetify stats app or ListenBrainz.
- [ ] **Phase 9 — Public release evaluation.** Terms, quotas, privacy
      policy, delete-my-data, and a security review by someone qualified to
      do one.

### Why the order changed (2026-09-19)

Phase 5 was "cross-service" and cross-device was Phase 6; they are now the
other way round. Listening happens on both the Walkman and the Pixel, but the
stats are only readable on the Walkman's small, slow screen — so a mirror on
the Pixel is the thing the user actually misses, and it is a fraction of the
work of a second service adapter. Desktop capture moved behind it for a
structural reason as well as a preference one: a Linux agent has nowhere to
send its facts except the same fact-shipping protocol that cross-device sync
needs underneath it, so building that protocol first is not a detour.

### A note beside Phase 8 — artists are not yet entities

Richer per-artist stats need artists as rows with ids, not the single artist
string `tracks` carries today: "A feat. B" fragments the moment anything is
counted per artist, and the same artist then appears under several spellings.
**Not to be built now** — recorded so it is not rediscovered as new. The one
thing worth knowing is that **the like sync already receives full artist
objects with Spotify ids** and throws them away; keeping them as they arrive
is far cheaper than backfilling them from 12,000 stored tracks later.

### A note beside Phase 9 — there is no hosted multi-user path *on Spotify*

> Spotify Development Mode is capped at five users per app and requires the
> app owner to hold an active Premium subscription. Extended quota access has
> since May 2025 required a registered business entity, an active launched
> service, and 250,000+ monthly active users. There is no path from a hobby
> project to a hosted multi-user Spotify integration. **A public version must
> therefore be self-hostable *for Spotify*, with each user supplying their own
> client ID.** Checked 2026-09-19; re-verify before acting on it.

**Corrected 2026-09-19, the same day it was written.** The first version of
this note drew a conclusion one size too large — that the whole app must be
single-tenant forever. That does not follow, and the reason is the phase
sitting right above it.

This is a limit on **one service's developer terms**, not on the app. Phase 6
makes Evergreen multi-service, and other services are believed to be less
restrictive — Deezer and Apple Music were named as likely examples, though
**neither has been checked and both should be before anything is built on
the assumption.** A hosted version offering every service except Spotify,
with Spotify available to anyone self-hosting or bringing their own client
id, is a perfectly coherent product and the terms of one service do not
forbid it.

So the architectural instruction is narrower than it first read:

- **Do not build multi-tenancy now.** It is far future, and building an
  account dimension into every table before there is a second user is the
  classic way to pay for something twice.
- **Do not build anything that makes multi-tenancy impossible either.** The
  practical form of that is in `docs/SYNC_FOUNDATION.md`: facts that can be
  named globally and ordered are what a future server would need, and they
  are the same thing cross-device sync needs, so the cost is already being
  paid for another reason.
- **Bring-your-own-client-id remains the right answer for Spotify
  specifically**, whatever else happens.

## Preparation checklist
- [x] Android Studio, Git, Claude Code (+ JetBrains plugin) installed
- [x] Developer options + USB debugging on both devices
- [x] Spotify "Device Broadcast Status" enabled on both devices
- [x] Spotify developer app created, own account allowlisted
- [x] Spotify extended streaming history requested (arrives later)
- [x] GitHub CLI logged in, private repo created, first commit pushed

## Status
_Update at the end of each task: what was done, what's next, any blockers._

- Current phase: **Phase 5a is done, and merged.** Its whole checklist passed
  on both devices on 2026-09-19 and it landed on `main` the same day as pull
  request #3. The daily write that had been outstanding since Phase 3 was
  diagnosed, fixed and seen to fire that afternoon.
- **Nothing is in progress.** The next move is the user's: live with the
  mirror for a while, then decide whether 5b is wanted.
  `docs/PHASE5_QUESTIONS.md` is where that gets decided, and it is
  deliberately not started. **`docs/SYNC_FOUNDATION.md` (new, 2026-09-19)**
  records what keeps the transport a free choice, so that research into
  Syncthing, a self-hosted server or anything else has something to be
  measured against.
- Last update: 2026-09-19.

**Phase 5a is built: the Pixel can hold a read-only mirror of the Walkman.**
Mirror mode lives in two `sync_state` rows and needed no schema change — the
database is still v6. A mirrored database refuses every Spotify write, says
on the home screen what it is and how old it is, and turns capture off.
272 unit tests pass; the instrumented suite has not been run, because it
needs the Pixel attached with the Walkman unplugged.

**The proof the phase was really for holds**: the same facts and parameters
give the same ranking whatever the device, whatever the order rows arrive in,
and whenever it is recomputed. The one exception is the device's timezone,
which is recorded under Phase 5a results as a Phase 5b decision rather than
fixed.

**Check 0a is answered, and the answer is the awkward one.** The Pixel
*does* capture phantom segments for music playing on the Walkman — three of
them in one short test, one of which would have scored as a full play. It
changes nothing about 5a, which replaces rather than merges, and it is
blocking for 5b. The good news arrived with it: `dumpsys media_session`
shows Spotify sets `volumeType=REMOTE` on the remote session, so 5b can drop
phantoms at the source rather than deduplicating after the fact. Both are
recorded under "Open questions".

**Phase 5a is verified on the devices.** A 21.0 MiB backup moved across, the
import was quick, and the two rankings matched when read off the two screens.
**A full recompute takes ~300 ms on the Pixel against ~2,400 ms on the
NW-A307** — about eight times faster, which is the phase's justification in
one number. The Walkman's own figure is within 4% of the Phase 4 baseline, so
the mirror work cost it nothing.

**The daily write is fixed, and Phase 3's step G is finally closed.** Before
today the Evergreen playlist had been written only once, manually, at
03:25 on the 18th. The cause is confirmed on the device: **the schedule
interval and the write guard are both 24 hours and have fallen out of phase**
— the scheduled run at ~01:08 on the 19th landed 21 h 43 min after the manual
write, inside the guard, and did nothing. The next attempt is ~01:25 on the
20th and would have written. The device being asleep was not the cause.
**Fixed the same day**: a write is now attempted every six hours against the
unchanged 24-hour guard, and the unique work was renamed so that devices
holding the old schedule pick the new one up. **The scheduled worker then ran
on its own at 16:08 and wrote two tracks**, which closes Phase 4's step J and
Phase 3's step G. See the two sections under Phase 5a results.

**The architecture notes are written down.** `docs/SYNC_FOUNDATION.md`
answers "what keeps every future sync option open", which is the user's
stated priority. Three findings worth knowing without reading it: the
expensive part is **already built** (`listening_segments.deviceId` has
existed since Phase 1, so a segment's global name is already
`(deviceId, id)`); the missing part is that **"what is new since X" is not a
question the schema can answer**, which is why a mirror ships 21 MiB for a
few hundred rows; and the **only hard ordering constraint** in the whole
design is that the phantom-segment fix must land in `capture/` before any
merge is built.

**Next: nothing, on purpose.** Phase 5a is meant to be used before anything
else is decided. Two things are waiting whenever the user wants them, and
neither blocks the other:

- **Decide whether 5b is wanted at all**, after living with the mirror.
  Check 0b — what fraction of listening actually happens on the Pixel — is
  the thing to keep a rough eye on in the meantime, because if it is small
  then a read-only mirror may be the whole feature.
- ~~**Run the instrumented suite on the Pixel.**~~ **Done 2026-09-19 after
  the merge: 225 tests, 0 failures.** It uninstalled the app and took the
  mirror with it, so the Pixel needs the app installed and the backup
  re-imported before it is a mirror again.

**Phase 4 is done, device-verified and merged.** It landed on `main` on
2026-09-19 as pull request #2. Steps G, I and J of its checklist were still
outstanding when it merged and are listed under "Still owed"; none of them
blocks anything.

**The roadmap changed on 2026-09-19.** Cross-device moved ahead of
cross-service, and desktop capture and the sync backend moved behind both.
The reasoning is under the roadmap. Phase 5 is now two halves: **5a**, a
read-only mirror moved as a file, which is what this branch builds, and
**5b**, full peer sync, which is not started and whose design questions are
open in `docs/PHASE5_QUESTIONS.md`.

**One Phase 5b question is now blocking**: whether a Spotify Connect remote
device captures phantom segments. It is recorded under "Open questions" with
the reason it was promoted.

**Phase 4 is built and has run on both devices.** All eight steps of
`docs/PHASE4_PROMPT.md`: the access banner, the parameter log, the
navigation shell and home screen, the real settings screen, score over time,
the snapshot screens, the notification changes, and the CSV export.
**259 unit tests and 213 instrumented tests pass** (instrumented on the
Pixel 10 Pro, with the Walkman unplugged as the build insists).

**The schema v6 migration has run on the real Walkman database** and lost
nothing — 77,518 segments and every other table intact, integrity check ok.
A copy was pulled over adb beforehand and is in `~/evergreen-backups/`
(outside the repo; it is listening history and must never be committed).

**A full recompute on the NW-A307 takes 2,309 ms** for 380 tracks.

**Three UI bugs were found on the Walkman and fixed** — a truncated tab
label, the changes list burying "near the line", and the chart disagreeing
with the score above it. All three are described under Phase 4 results.

**What is left is step J below** — leaving it a day to watch the scheduled
write fire unattended, which also closes Phase 3's step G. Everything else
on the checklist has been done, and the user's verdict on the build was
"pretty happy with everything"; UI polish and speed on the Walkman are
recorded under "Carried forward from Phase 4" as work for after the next
phase, at the user's own call.

**Step G of the Phase 3 verification is still pending** — the user
confirmed on 2026-09-18 that a day had not yet passed since A–F. It blocks
nothing.

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

~~**Next:** run the Phase 4 verification checklist on the NW-A307, then say so
and `phase4-usability` gets merged to `main`.~~ Done 2026-09-19.

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

## Phase 3 results (2026-09-18)

Built on branch `phase3-scoring`, 17 commits plus three of fixes, one per step
of `docs/PHASE3_PLAN.md` section 11. 219 unit tests and 183 instrumented
tests, all passing on the Pixel 10 Pro, and **device-verified end to end on
the NW-A307 against the real Spotify account** — see the verification section
below.

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



## Phase 4 results (2026-09-18)

Built on branch `phase4-usability`, eight commits, one per step of the
prompt. 257 unit tests (up from 219). The `capture/` package is unchanged
except for one new file beside it — `CaptureWatchWorker` — which reads the
database and touches nothing about how capture works.

**Device-verified on the NW-A307 on 2026-09-19 and merged to `main` the same
day** (pull request #2). See the checklist at the end of this section: A to F
and H passed, G and I were not run, and J is the unattended daily write, which
is a matter of waiting rather than of doing.

### What was built

- **The access banner disappears once access is granted.** It cost about
  100 dp of the Walkman's 602 on every tab and said nothing that was news.
  The full card, in both states, moved to the debug screen's Live log tab,
  which is where someone looks when capture has stopped.
- **`parameter_history` (schema v6).** Append-only, one row per change,
  carrying what came before as well as after.
- **A navigation shell**: Home, Scores, Snapshots, Settings, with the debug
  screen behind Settings. Hand-rolled — four destinations and two detail
  screens do not need a navigation graph, and `androidx.navigation` would be
  a dependency bought for nothing on a RAM-limited device.
- **A home screen**: a status line that is silent when everything works, and
  "what changed" underneath it.
- **A real settings screen**: one control per scoring parameter with its
  meaning in plain words. The JSON editor survives under Advanced.
- **Score over time**, recomputed on demand, on the track detail screen.
- **Snapshot screens**: months grouped by year, and a month view that leads
  with its diff against the month before.
- **Notification changes**: playlist changes on by default, one summarising
  notification per sync, suppression after a parameter change, and a new
  alert for capture having gone quiet.
- **CSV export** of the listening history and the rankings.

### What was learned, and what the plan got slightly wrong

**"Near the line" is not symmetric around rank 100, and the prompt's
wording would have made it so.** Decision 1 asks for tracks "within a few
ranks of 100 in either direction". But hysteresis means the two sides are
different questions: a track outside the playlist is waiting to reach rank
100, while a track inside it is safe until it falls past rank 130, thirty
places further down. Measuring both against one number would have filled
the list with members in no danger at all and left out precisely the ones
about to drop — the opposite of "where the next change is coming from". The
screen shows non-members approaching the join rank and members approaching
the leave rank, and a unit test pins the asymmetry.

**Score over time needed no new table, and could not have had one.** The
first instinct for a chart is to record scores as they are computed. That
would have been a derived table quietly becoming truth — what invariant 1a
exists to prevent — and worse, it would have been *wrong* the moment a
parameter changed, which is exactly when the chart is most wanted. Since a
score is a pure function of (segments, like events, parameters, now), a past
score is that function with a different `now`. Sixty samples of one track is
cheap; sixty samples of the whole ranking would be sixty full recomputes, and
that is why there is no line for the playlist cutoff.

**Two ways that recomputation can lie, both found while writing its tests.**
Decay weight is `2^(-(now - at)/halfLife)`, so an event dated *after* the
instant being asked about gets a positive exponent and counts for more than a
whole play — future events have to be filtered out, not merely decayed. And
the load window is ten half-lives measured from `now`; using today's window
for a point six months ago silently truncates the left-hand end, and the line
then slopes upward for a reason that is an artefact of the query rather than
anything about the listening.

**A notification channel's importance cannot be raised in place.** Decision
5 asks for joined/left notifications to flip to default on. Android fixes a
channel's importance at creation and deliberately ignores later changes, so
an app cannot make itself noisier than the user agreed to. The only way is a
new channel id, and the old one has to be deleted or the system settings show
two "Playlist changes" entries, one of which does nothing. The channel is now
`playlist_v2`.

**A stepper can drift where a text box cannot.** Repeatedly adding 0.05 to a
Double gives 0.25000000000000006, and the parameters' JSON is canonical — so
the drift changes the hash, rebuilds the whole score cache, and writes a
`parameter_history` row for a change the user believes they undid. Every step
rounds to three decimals, and a test steps each control up and back down and
asserts the hash is identical.

**The capture watchdog must ignore imported rows.** A Spotify export carries
real listening times, some of them recent. Counting them would let one import
silence the alert for as long as the export reaches — which is precisely the
window in which capture breaking would go unnoticed. `MAX(endedAtMs)` is
filtered to `source = 'CAPTURE'`, and that one clause has a test of its own.

**The material-icons artifact is not on the classpath**, so a bottom
navigation bar was not available without a new dependency. A text tab row was
used instead, which also costs about 48 dp against a navigation bar's 80 —
a good trade on a 602 dp screen.

### Design notes worth keeping

- **The status line says nothing when nothing is wrong.** A warning that is
  always on screen stops being a warning. The rules live in a pure function
  (`buildStatus`) so what counts as broken is testable without a device.
- **A stale playlist write is only news on the writer device.** On the other
  one it is the correct state, and reporting it there would teach the user to
  ignore the status line.
- **The score breakdown became a screen rather than a dialog.** A track with
  six years of history has hundreds of contributions, and a dialog on a
  339 × 602 dp screen gave that list a box inside a box with 260 dp to show it.
- **A month's diff is a set difference over rows already stored.** Consecutive
  snapshots subtract; "new this month" and "gone since last month" needed no
  schema change at all.
- **Every CSV field is quoted, unconditionally.** Album titles contain commas
  and track titles contain quotation marks; a rule that quotes conditionally
  has an exception waiting in somebody's library, and the failure shifts every
  column to its right with no error.
- **Blanks stay blank in the export.** An imported row's missing position, and
  a fraction-heard with no known track duration, are written empty rather than
  as a zero — "not observed" and "observed at zero" are different claims, and
  the file keeps them apart exactly as the database does.
- **The parameter log is the one derived-looking table that is not a cache.**
  `scores` can be dropped and rebuilt at any moment; a parameter change that
  was never recorded is gone. It is also what closes the last gap in
  recomputing a past ranking.

### Device verification (NW-A307, 2026-09-19)

**The v5 → v6 migration ran on the real database and lost nothing.** The
database was pulled over adb first, as a restorable copy, and read back
before and after:

| | before | after |
|---|---|---|
| schema version | 5 | **6** |
| integrity check | ok | **ok** |
| listening_segments | 77,518 | 77,518 |
| tracks / service_tracks | 12,321 / 12,749 | 12,321 / 12,749 |
| likes / like_events | 380 / 380 | 380 / 380 |
| scores | 380 | 380 |
| evergreen_membership | 100 | 100 |
| snapshots / entries | 1 / 380 | 1 / 380 |
| parameter_history | — | 0 (created) |

**A full recompute takes 2,309 ms on the NW-A307** — 380 tracks over 77,518
segments. That is the baseline the prompt asked for. It is fast because of
the 450-day load window; the segment table is the only thing here that grows
without bound, so if this number climbs the window is the first thing to
look at.

Every screen was checked on the device: Home, Scores, Months, Settings, the
track detail and the month view. **Three things were wrong and are fixed:**

- **The "Snapshots" tab rendered as "Snapsho".** Four tabs across 339 dp
  leaves about 85 dp each, which that word does not fit in. It is "Months"
  now — shortening the word beats shrinking the type, and the screen itself
  is headed "N months".
- **Forty joins buried "near the line".** The first-ever write moved forty
  tracks at once, and on a 602 dp screen that put the interesting section six
  screenfuls below the boring one. The list now shows six with "Show all 40"
  behind a tap. In the steady state the cap never bites.
- **The chart and the score above it disagreed**, 11.538 against 11.527. The
  line needs a longer reach than a single score does — its leftmost point is
  months back and carries its own ten-half-life window — so it loads more rows
  than the ranker ever would, and was *scoring* with all of them. Each point
  now applies the same load window relative to its own instant, so the end of
  the line is exactly the number shown above it.

### The export test was never testing what it said

Found when the suite was re-run on the Pixel after the Walkman work.
`DataExport.writeTo` took a `Context` and resolved `EvergreenDatabase.get`
itself, so a test could build a pristine in-memory database, export, and be
reading the *app's real* database instead. "An empty database writes only its
headers" had been passing purely because the device's database happened to be
empty, and it started failing the moment an earlier test left one row behind.

The lesson is the general one about seams: a function that reaches out for
its own dependencies cannot be tested against a different one, and the test
that appears to do so is asserting about something else entirely — silently,
and in the passing direction. `writeTo` now takes the database and a
`ContentResolver`; the public `Context` overload delegates to it. An
end-to-end test also exports a title containing commas and quotation marks and
checks the row has the same field count as the header, because the practical
way to break CSV escaping is not the escaping function but forgetting to route
a field through it.

### Still owed

- ~~**Step J / Phase 3's step G:** the unattended daily write.~~ **Closed
  2026-09-19.** It had not fired 36.5 hours after the manual first write,
  which turned out to be a real bug rather than a sleepy device: the schedule
  interval and the write guard were both 24 hours and had fallen out of
  phase. Diagnosed, fixed, and the scheduled worker seen to write at 16:08 on
  the 19th. Both sections are under Phase 5a results. The original note read: The next one cannot happen before the same time on the
  19th — `EvergreenSync` refuses any write inside 24 hours of the last — and
  will land whenever WorkManager's next 24-hour window falls with the Walkman
  awake and on Wi-Fi. On a portable player that is often asleep, "some time
  that day" is the honest expectation rather than a clock time.

### Phase 4 verification checklist

Do these on the devices named. Nothing here writes to Spotify except step J,
which is the ordinary daily sync.

**On the Pixel 10 Pro, with the Walkman unplugged:**

- [x] **A.** Run the instrumented suite: `./gradlew :app:connectedDebugAndroidTest`.
      The build refuses if a Walkman is attached; do not override it.

**On the NW-A307:**

- [x] **B.** Install the new build and open it. It should open on **Home**,
      not on the debug screen, and the notification-access card should be
      absent — access is granted, so it has nothing to say.
- [x] **C.** The status line should read "Everything is running." If it lists
      anything, read it: each entry is a real claim about the device.
- [x] **D.** Check every screen fits: Home, Scores, Months, Settings, and
      the two detail screens. This is the check that failed twice in Phase 3.
- [x] **E.** On Scores, press **Recompute** and note the milliseconds it
      prints. Put the number in this file.
- [x] **F.** Open a track from the Scores list. It should show the score
      breakdown, the six-month line, and — once there is more than one
      snapshot — a row of months.
- [ ] **G.** In Settings, change one scoring parameter, save, and check that
      the change appears under "Changes to the scoring" in the same words the
      control used. Then set it back and check that a second row appears
      (setting it back is a change too) and that the ranking returns exactly.
- [x] **H.** Open Months, open a month, and check the diff reads correctly.
      Only one snapshot exists so far, so it should say so rather than showing
      an empty diff.
- [ ] **I.** In Settings, export **Listening** and **Rankings**. Open both on
      a computer and check the columns line up — particularly for any track
      whose title contains a comma or an apostrophe.
- [ ] **J.** Leave it a day. The Evergreen write should fire on its own (this
      also closes Phase 3's step G), and the playlist-changes notification
      should arrive if anything moved.


## Phase 5a results (2026-09-19)

Built on branch `phase5a-mirror`, one commit per step of
`docs/PHASE5A_PROMPT.md`, plus three that came out of using it. **275 unit
tests** (up from 259) **and 225 instrumented tests** (up from 213), all
passing. `capture/` is unchanged; one new file sits beside it,
`CaptureSwitch`, exactly as `CaptureWatchWorker` did in Phase 4.

**Device-verified on both devices, and merged to `main`** as pull request #3
on 2026-09-19. The checklist is at the end of this section.

### What was built

- **Mirror mode**, two rows in `sync_state`. It is a key/value table, so this
  needed no schema change — the database is still v6.
- **The guard**: a mirrored database never writes to Spotify. Four places
  check it, and the check comes *first* in each.
- **"Import a mirror"**, next to the existing backup button, using the system
  open-document picker. It validates before replacing and neutralises before
  replacing.
- **The home screen says so**, in one line, whenever mirror mode is on.
- **A cross-device determinism proof**, `CrossDeviceRankingTest`.

### What was learned

**The writer flag was never going to be enough, and the reason is worth
stating plainly.** A mirror is the Walkman's database copied whole, so it
arrives with the Walkman's `sync_state`: writer flag set, playlist id
present, first write already confirmed. Every guard built in Phase 3 passes
on it. The new check therefore cannot be *one more* guard in the list — it
has to come before the others, including before the dry run, because every
path that writes begins by computing a diff. The test that pins this
deliberately leaves the writer flag on.

**Neutralise before replacing, not after.** The obvious shape is: copy the
file in, then fix up the device-specific rows. That leaves a window in which
the live database is one that believes it is the Walkman — short, but
precisely the state the whole feature exists to prevent, and a flat battery
inside it would make the window permanent. So the copy is staged beside the
live database, checked and edited there, and the swap is a rename. A process
death mid-swap leaves *no* database, which Room replaces with an empty one:
no writer flag, no confirmed write, nothing that can write to Spotify. That
is the right way to fail.

**A version check has to refuse in both directions, and for different
reasons.** A newer backup this build cannot read is obvious. An *older* one
is the dangerous case, because Room's instinct on opening an old file is to
migrate it forwards — so without an explicit refusal, "can I read last
year's backup" would have been answered "yes, and it is now a different
database". The check is `PRAGMA user_version`, read with plain SQLite before
Room is allowed near the file.

**"Capture off" should mean off, not "read and discard".** The cheap
implementation is a flag `SegmentWriter` consults, which is three lines — and
which changes `capture/` for a decision that is not capture's to make, and
leaves the listener bound, reading media sessions in order to throw the
results away. Disabling the listener component instead means nothing is read
at all. The notification-access grant survives a component disable (it is
cleaned up when the *package* goes, not the component), so turning capture
back on costs no trip through the system settings.

**The ranking survives every way two devices differ except one.** Pinned in
`CrossDeviceRankingTest`:

| What differs | Ranking |
|---|---|
| Nothing | identical, to the last decimal |
| The order rows arrive in | identical |
| `now` — recomputed 9 hours later | identical order, every score smaller |
| `now` — recomputed a fortnight later | identical order |
| The default locale | identical, and the parameters hash too |
| **The device's timezone** | **can differ** |

The `now` result is the one that matters and the reason is not obvious. A
decay weight is `2^(-(now - at)/halfLife)`, so shifting `now` multiplies
*every* contribution by the same constant: the scores all shrink and the
order is untouched. That holds only because the daily cap is applied *before*
decay, which was a Phase 3 ordering decision made for other reasons and turns
out to be what makes cross-device recomputation sound.

**The timezone is a real hazard, recorded rather than fixed.** The daily cap
buckets listening by *local* calendar day — a deliberate Phase 3 choice,
because "forty loops in one night" is a local idea. Two devices in different
timezones can therefore cap the same listening differently. It does not bite
today: both devices are in the same place, and a mirror carries no timezone
with it. It would bite the moment one travelled, and silently. Fixing it
means recording the zone a segment was captured in, which is a schema change
and a **Phase 5b decision**; scoring in UTC instead would change the ranking
on the device that already works.

**`String.format` localises `%d` and not `%x`.** Under `ar-SA-u-nu-arab`,
`String.format("%d", 255)` really is `٢٥٥`. The parameters fingerprint is
built with `%02x` and so is safe — but it is safe for a reason nobody chose,
and a hash that differed by device locale would have meant a score cache that
never matched and snapshots claiming settings the other device could not
reproduce. There is now a test.

### Design notes worth keeping

- **The status line gains one entry that appears when nothing is wrong**,
  which inverts the rule the rest of that file follows. A mirror silently
  going stale while looking exactly like live data is the one way this
  feature can mislead, and nothing else on the screen would give it away. It
  is text rather than a card, because a permanent card is the mistake the
  notification-access banner made and Phase 4's first step undid.
- **Every suppression on a mirror is explicit**, including the ones the
  import already makes impossible. The Evergreen-stale warning is gated on
  mirror *and* on the writer flag, although the import clears that flag —
  relying on it would be trusting one guard to keep another honest.
- **A refusal is not a failure.** The month export's return type became a
  small sealed result so that "this is a mirror" could be said in its own
  words rather than arriving as "export failed, check the connection", which
  would send the user to fix something that is not broken.
- **The device's name is stamped at backup time**, not at startup. That is
  the one moment the row is unambiguously true: it names the device the file
  came from, and the file is about to be written. `Build.MODEL` is a model
  name shared by every unit of that model, not an identifier.
- **The playlist id is not neutralised.** It names the playlist the
  *account* uses, so if the Pixel were ever promoted to writer it should keep
  that playlist up to date rather than create a second one. The scoring
  parameters and the parameter log stay for the same kind of reason: they
  belong to the data, and a mirror scored under different settings would not
  be a mirror of anything.
- **The UI restarts itself after an import.** Everything on screen is reading
  through a database handle that has just been closed and replaced;
  recreating the activity is how they all get back onto the new file at once,
  and it lands on Home, where the mirror announces itself — so the restart
  doubles as the receipt.

### The daily write has not fired on its own

**Status 2026-09-19, 15:55.** The Evergreen playlist has been written exactly
once, ever: the manual first write at **03:25 on the 18th**. That is
**36.5 hours** ago. This is Phase 4's step J and Phase 3's step G, and it has
now been outstanding long enough to stop being "wait and see".

Nothing in Phase 5a touched it — the mirror import runs on the Pixel and
cancels the Pixel's schedules; the Walkman was not changed.

**Confirmed on the device, 2026-09-19 16:03**, with the Walkman attached and
the Pixel unplugged. It is (A): **the schedule and the write guard are both
24 hours and they have fallen out of phase.** Neither of the two comfortable
explanations survives contact with the data.

Read from WorkManager's own bookkeeping (`no_backup/androidx.work.workdb`)
and from `dumpsys jobscheduler`:

| | |
|---|---|
| `evergreen-playlist-sync` state | ENQUEUED — the schedule exists |
| periods completed | **2** — it has run, twice |
| current period began | **01:08 on the 19th** |
| next attempt | **+9h21m, so ~01:25 on the 20th** |
| blocking constraint right now | `TIMING_DELAY` only |
| `CONNECTIVITY` | **satisfied** |
| `spotify-like-sync` last ran | 14:14 on the 19th |

So the sequence was:

1. **03:25 on the 18th** — the manual first write. This set the guard's clock
   and did not touch the schedule's.
2. **~01:08 on the 19th** — the scheduled run happened. It was
   **21 h 43 min** after the write, which is inside the 24-hour guard, so it
   returned `TOO_SOON` and wrote nothing — correctly, and reporting success,
   because nothing was wrong.
3. WorkManager then waited a **full further period**. The next attempt is
   ~01:25 on the 20th, at which point 46 hours will have passed and it will
   write.

**The device being asleep and off Wi-Fi is not the cause**, plausible as it
was. The like sync ran at 14:14 the same day on a 12-hour period, period
counts are incrementing normally, and connectivity is satisfied at this
moment with only the timer outstanding. WorkManager is doing its job.

**And the steady state is worse than a one-off.** If it writes at ~01:25 on
the 20th, the following attempt lands ~24 hours later — within minutes of the
guard boundary, on whichever side the drift falls. Any attempt that arrives
even a minute early silently costs another whole day, so this will oscillate
rather than settle.

**Fixed 2026-09-19**, on `phase5a-mirror` at the user's direction, as its own
commit. The two numbers are no longer equal: a write is **attempted every six
hours** and the unchanged 24-hour guard remains the only thing deciding
whether one happens. A write therefore becomes possible within six hours of
the guard expiring, whatever the phase, and it is still at most one a day.

A refused attempt is genuinely cheap: `TOO_SOON` is checked in step 1 of
`EvergreenSync.run`, **before the recompute and before any network request**,
so it costs an open of the database and two indexed reads. The
`NetworkType.CONNECTED` constraint means the device only wakes for it when it
already has a network, which is also the answer to "sync every day, but only
when it can" on a player that is often asleep and off Wi-Fi.

**The unique work had to be renamed, not updated** — `evergreen-playlist-sync`
became `evergreen-playlist-sync-v2`, and the old name is cancelled wherever it
is found. This is the same shape of problem as the notification channel in
Phase 4, and the same answer. `ExistingPeriodicWorkPolicy.KEEP` is what stops
opening the app restarting the interval, and it would equally have stopped any
device already holding the 24-hour schedule from ever seeing the six-hour one.
A new name settles it without depending on what WorkManager does with a
changed period on existing work.

**A test now pins the inequality**, in `EvergreenScheduleTest`. Both values are
compile-time constants, so it is a statement about the source rather than about
a running system — which is the point: nothing at runtime ever complained about
the old pair, and the only place this could have been caught is where someone
changes one of the two numbers.


### The scheduled write fired — 2026-09-19 16:08

**Phase 4's step J and Phase 3's step G are closed.** The new build was
installed on the NW-A307 and the app opened, which cancelled the old
24-hour schedule and enqueued the six-hour one. Being already overdue — 38.7
hours since the write of 03:25 on the 18th — WorkManager ran it immediately,
and it wrote:

```
EvergreenSync:       evergreen sync: +2 -0, 0 refused
EvergreenSyncWorker: evergreen sync: OK Added 2, removed 0.
EvergreenSyncWorker: suppressing the change notification: parameters changed
```

| | |
|---|---|
| `evergreen_last_written_at` | **2026-09-19 16:08:39** |
| tracks added / removed | 2 / 0 |
| members now | 102 |
| old `evergreen-playlist-sync` | **CANCELLED** |
| new `evergreen-playlist-sync-v2` | period 6 h, enqueued 16:08:37 |
| writer flag / mirror | `true` / not a mirror — the Walkman is unchanged |
| segments | 77,607 |

**This is the scheduled path, not the "Sync now" button.** The worker ran on
its own, computed its own diff, wrote two tracks, and recorded the write. The
whole pipeline has now been exercised unattended.

**The notification was suppressed, and that is correct.** `parameters_changed`
was still set from the settings change made during the Phase 4 checklist, so
the worker took and cleared the flag rather than reporting two joins as if
they were news about listening. It also means that flag has now been consumed
and the next write will notify normally.

**What is still not proven** is a full unattended cycle with nobody touching
the device: this run was triggered by the app being opened, which is what
re-armed the schedule. The next attempt is due ~22:08 tonight and will
correctly refuse as `TOO_SOON`; the first write that owes nothing to a human
is any time after 16:08 on the 20th. That is a matter of waiting rather than
of doing, and it no longer blocks anything.

### The instrumented suite, run after the merge (2026-09-19)

Run on the Pixel 10 Pro with the Walkman unplugged, as the build insists.
**225 tests, 0 failures**, up from 213 in Phase 4. The twelve new ones are the
mirror import (8), the Evergreen write guard on a mirror (3) and the month
export refusing on a mirror (1).

**It destroyed the mirror, exactly as advertised.** `connectedAndroidTest`
uninstalls the app when it finishes, so the Pixel is left with no Evergreen
and no database at all — not an empty one. Restoring the mirror means
installing the app again and re-importing the backup, which is a couple of
minutes and no loss, because the Walkman holds the original.

That is worth stating plainly because it is the standing cost of the Pixel
being both the test device and the mirror: **every instrumented run wipes the
mirror.** It is question 3 of Part 4 in `docs/PHASE5_QUESTIONS.md`, and the
answer in practice is "yes, acceptable" — but only while the mirror is
read-only and reconstructible from the Walkman. It would stop being
acceptable the moment the Pixel held facts of its own, which is another thing
5b would have to deal with.

### Moving the file between the devices

Three ways, in increasing order of faff. Any of them works; the file is an
ordinary `.db` of a few tens of megabytes.

**Option 1 — a file-sharing app you already have.** On the Walkman, back up
to somewhere you can reach from the other device (a cloud folder, or whatever
you already use to move files), then pick it on the Pixel. Easiest, and the
file leaves your devices, which is worth a thought: it is your listening
history, and it contains no passwords or tokens.

**Option 2 — USB, device to computer to device.**

1. Plug the **Walkman** into the computer with its USB cable.
2. On the Walkman, pull down the notification shade, tap the USB notification,
   and choose **File transfer** (it may be called **MTP** or **Transferring
   files**).
3. On the computer, open the Walkman's storage and find the backup file where
   you saved it — most likely under **Download**.
4. Copy it to the computer.
5. Unplug the Walkman, plug in the **Pixel**, and set it to **File transfer**
   the same way.
6. Copy the file into the Pixel's **Download** folder.
7. Unplug, and pick it in the app.

**Option 3 — adb, if the computer already has it.** No shade-pulling:

```
adb devices                                    # check which is which
adb -s <walkman-serial> pull /sdcard/Download/evergreen-backup-....db .
adb -s <pixel-serial>   push evergreen-backup-....db /sdcard/Download/
```

Both devices need USB debugging on, which they already have. `adb devices`
lists serials; run it with one plugged in at a time if it is not obvious
which is which.

### Phase 5a verification checklist

**Take a backup on *both* devices before you start.** This is the one
operation in the app that destroys a database, and the Pixel's own record is
what it destroys. Nothing here writes to Spotify at all.

**On the NW-A307 (the Walkman):**

- [x] **A.** Install the new build. Open **Settings** → **Back up database**
      and save the file. **Note the size it reports.**
- [x] **B.** Check the home screen is unchanged: it should say "Everything is
      running" and say nothing about mirrors.
- [x] **C.** On **Scores**, press **Recompute** and note the milliseconds.
      Compare it with the 2,309 ms baseline from Phase 4.
- [x] **D.** Write down the **top ten tracks** in order. You will compare
      them with the Pixel.

**Move the file to the Pixel**, by whichever of the three ways above suits.

**On the Pixel 10 Pro:**

- [x] **E.** Install the new build. Take a backup here too, if anything on
      this device matters.
- [x] **F.** **Settings** → **Mirror of another device** → **Import a
      mirror**. Pick the file. Read the confirmation: it should name
      **NW-A307**, when the backup was taken, and the counts on both sides.
      **Note how long the import takes.**
- [x] **G.** The app restarts itself and lands on **Home**. The first line
      should read **"Mirror of NW-A307, taken … ago"**. Check it fits on one
      line.
- [x] **H.** On **Scores**, press **Recompute** and note the milliseconds.
      **Then compare the top ten with what you wrote down in D — they should
      be the same tracks in the same order.** This is the phase's whole point.
- [x] **I.** Check there is nowhere left to write to Spotify, and that each
      place says why rather than failing: the **Evergreen playlist** section
      in Settings, the **Sync now** button under Likes, the **writer switch**,
      and **Export to Spotify** on any month.
- [x] **J.** Check capture is off: the debug screen's **Live log** should
      stop gaining entries, and playing something in Spotify on the Pixel
      should add no segment.
- [x] **K.** Try importing a **file that is not a backup** — any other file
      will do. It should refuse with a readable reason and change nothing.

**Back on the NW-A307:**

- [x] **L.** Nothing about it has changed: it still says "Everything is
      running", is still the writer, and still writes Evergreen on schedule.
      Give it a day and check the write happened (this also closes Phase 3's
      step G and Phase 4's step J).

### Phase 5a device verification (2026-09-19) — passed

Run by the user across both devices. **All of A to L passed.** The daily
write, which was the outstanding item from Phase 4's step J and Phase 3's
step G, was diagnosed, fixed and seen to fire the same afternoon — see the
two sections below.

| | value |
|---|---|
| backup file size | **21.0 MiB** |
| import time on the Pixel | quick — not worth timing |
| full recompute on the Pixel 10 Pro | **~300 ms** |
| full recompute on the NW-A307 | **~2,400 ms** (2,309 ms in Phase 4) |

**The rankings matched.** The top of the Pixel's ranking was the same as the
Walkman's, read off both screens — which is the cross-device determinism
proof holding on real data rather than in a fixture, on 21 MiB of it.

**The Pixel recomputes about eight times faster**, and that is the phase's
whole justification in one number: 2,400 ms is long enough to feel on every
tap that invalidates the cache, and 300 ms is not. The Walkman's figure is
within 4% of the Phase 4 baseline, which also says the mirror work cost it
nothing.

**21.0 MiB is the transport's real cost**, and it is small enough that none
of the three ways of moving the file is meaningfully better than the others.
Worth re-reading if it ever grows: `listening_segments` is the only table
here without a bound, and a backup is taken by hand.

## Open questions — still open

### Raised by using it (2026-09-18) — carried through Phase 4

~~**How volatile should a score be?**~~ **Settled 2026-09-19, at the current
parameters.** Score over time was built to answer this question; the user has
looked at the charts over their own listening and the reading was that the
scores behave well. None of the three levers below was pulled, and the
calibrated defaults stand. **Reopen only if a specific track the user still
loves visibly falls out of Evergreen** — that is the observation that would
mean the volatility is real rather than felt. The working is kept below
because the three levers, and the asymmetry they are about, are still the
right starting point if it ever is reopened.

Observed in use: a single skip — "not in
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

**Update 2026-09-18 — the tool is built; the question is still open, and is
now the user's to answer.** The line is on the track detail screen, one tap
from the Scores list (a per-track line cannot live on a list of 380 rows, and
a whole-ranking version would be sixty full recomputes). Six months at about
three days a sample, so a single skip shows as one step down rather than
being averaged away. Under it, the screen states the steepest fall between
two samples **as a fraction of the score it fell from**, which is the actual
question — 0.4 off a score of 1 and 0.4 off a score of 40 are different
judgements, and the eye is bad at that comparison on a chart whose height
changes per track.

**Do not retune until the user has looked at it.** The parameters were
calibrated against 77,369 real plays, and none of the three levers has been
pulled. Lever 1 (lowering `negativeWeight`) is now a two-tap change on the
settings screen rather than a JSON edit, so trying it and reverting it costs
nothing and is fully recorded in `parameter_history`.

**First reading off the device (2026-09-19), for whatever it is worth.** On
the top-ranked track — *Take Me to the Beach*, 50 plays, score 11.53 — the
steepest fall between two samples over six months was **0.848, or 12% of the
score at the time**. One track is not a finding, and the interesting cases
are the ones nearer the cutoff where 12% is the difference between staying
and going. But it is the first actual number this question has ever had,
against a year of describing it as "visibly drags the score down".

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
- ~~The one real gap: **parameter changes are not recorded with timestamps.**~~
  **Closed 2026-09-18.** Schema v6 adds `parameter_history`: one append-only
  row per change, with the settings before and after, the hash the score cache
  stamps its rows with, and when. `parametersInForceAt(atMs)` answers "what
  were the settings on this date", and the earliest row's `previousJson`
  records what was in force before the log began — the shipped defaults, a
  fact that existed nowhere else and stopped being true the instant the first
  change landed.

So every input to a past ranking is now recoverable. The remaining code change
for "the ranking as it stood on any past date" is still the one named above:
the liked set is read as current state (`likes.unlikedAt IS NULL`) and would
have to be derived from `like_events` instead.

So the snapshot definition can be changed later, and even applied
retroactively. It does not need to be right now.

~~**The notification-access banner takes too much room.**~~ **Fixed
2026-09-18**, as the first step of Phase 4. It renders nothing at all once
access is granted; the full card lives on the debug screen's Live log tab.

### Carried forward from Phase 4 — polish, deliberately deferred

The user's own reading after using the Phase 4 build on the Walkman
(2026-09-19): **"pretty happy with everything... UI probably could still use
some work, and could maybe be better optimized for faster operation on the
Walkman."** Both are explicitly *after* the next phase's big features, not
before them. Recorded here so they are not rediscovered as if new:

- **UI refinement.** No specific complaint attached; the screens work and fit.
  Worth revisiting once there is more than one month of snapshots and the
  validation phase has ended, because the steady-state shape of the app is
  not yet something anyone has lived with.
- **Speed on the NW-A307.** The measured baseline is 2,309 ms for a full
  recompute of 380 tracks. Nothing is known to be slow; this is a watching
  brief. The obvious levers if it ever matters, cheapest first: the Scores
  screen recomputes on first open when the cache is empty rather than showing
  the cache and refreshing behind it; the track screen computes a sixty-point
  line on open, which is sixty scorings of one track; and `allServiceTracks()`
  loads every service row on every ranking. None of these is worth touching on
  a guess — measure first, and the score-over-time line is now the tool for
  seeing whether a change helped.

### Phase 5b — blocking for the merge

**Does a Spotify Connect remote device capture phantom segments?** If music
plays on the Walkman while Spotify is open on the Pixel, does the Pixel's
capture record segments for a track it is not actually playing?

**Answered 2026-09-19, and the answer is yes** — see the measurement below.
The Pixel recorded three plays of music that was coming out of the NW-A307,
while nobody touched it. **Promoted the same day from a curiosity to a
blocker.** It was harmless while
each device had its own database and only the Walkman's was used. It stops
being harmless the moment the two captures are merged: if the Pixel records
segments for music playing on the Walkman, merging inflates play counts, and
it does so **invisibly** — the segments look exactly like real listening, and
nothing downstream can tell that one evening was counted twice. **Phase 5b
must not be built until this is answered.** Phase 5a (the read-only mirror)
does not depend on it, because a mirror replaces rather than merges.

Two things are already true and worth writing down with it:

- **`MediaController.PlaybackInfo` may distinguish remote from local
  playback**, which would let capture drop phantom segments at the source
  rather than deduplicating them afterwards.
- **Segments overlapping in wall-clock time are by definition at most one real
  listen**, since one person cannot listen to two things at once.

Segments have no device column, but every existing row came from one device
and can be stamped in bulk when that column is eventually added — so waiting
loses no provenance.

Phase 0 did record that Spotify Connect playback is captured (see the table
in "Step 0 findings"), but that was the *playing* device observing itself.
Whether the *remote* device also records it is the open part.

#### What the API offers, read in code 2026-09-19

Checked against `android.jar` for API 37 rather than from memory, because
this is the kind of thing tutorials get wrong.

`MediaController.PlaybackInfo` **does** carry the distinction:
`getPlaybackType()` returns `PLAYBACK_TYPE_LOCAL` (1) or `PLAYBACK_TYPE_REMOTE`
(2). It is reachable from a controller the app already holds —
`MediaController.getPlaybackInfo()` — and changes arrive through
`MediaController.Callback.onAudioInfoChanged(PlaybackInfo)`, a callback
`SessionWatcher` already extends the class for and does not override. So if
Spotify sets it, capture could drop a phantom at the source for the cost of
one field and one `if`, with no deduplication rule to design.

One caveat stands, and one was wrong:

- **`PLAYBACK_TYPE_REMOTE` is a statement about volume, not about location.**
  It is what a session sets through `MediaSession.setPlaybackToRemote()` to
  say "my volume is not this device's stream volume". An app routing audio to
  another device usually sets it, but nothing compels it to, and an app could
  leave a session local while showing "Playing on NW-A307" on screen.
- ~~**Whether Spotify actually sets it cannot be read out of the SDK.** The
  only way to get it is to log the value beside a real Connect session, which
  is a change inside `capture/`.~~ **Wrong, corrected 2026-09-19.** No code
  change is needed to find out: `adb shell dumpsys media_session` prints the
  value for every live session. Observed on the Pixel during a real Connect
  session with audio on the NW-A307:

  ```
  package=com.spotify.music
  active=true   state=PLAYING(3)   position=1161
  volumeType=REMOTE  controlType=FIXED  max=24  current=24
  volumeControlId=com.spotify.music/...SpotifyMediaRouteProviderService:510c…
  ```

  **Spotify does set it.** Every other session on the device read `LOCAL` at
  the same moment, and the `volumeControlId` names Spotify's own Connect
  media-route provider — a second, independent signal. So the phantom can be
  dropped at the source for the cost of one field and one `if`, and 5b needs
  no wall-clock deduplication rule for this case.

Also present and worth knowing: `getVolumeControlId()` (API 34+), which names
the volume control a remote session is using, and `getAudioAttributes()`.
Neither is a location either, but a non-null control id alongside
`PLAYBACK_TYPE_REMOTE` is a stronger reading than the type alone.

#### The measurement — procedure, to run on the devices

Counts and aggregates only; no row of the database goes in this file.

**Before you start.** Have the Pixel and the Walkman both to hand, Spotify
signed in on both, and Evergreen installed on both with notification access
granted. Nothing here writes to Spotify and nothing here changes the Walkman.

1. On the **Pixel**, open Evergreen → **Settings** → **Open the debug
   screens** → the **Segments** tab.
2. Read the line just under the chips — it says something like
   `1234 seg · 200 track · 180 listen`. **Write down the first number.** That
   is the segment count before the test.
3. Switch to the **Live log** tab and press **Clear**, so anything below is
   from this test only.
4. Open **Spotify on the Pixel** and leave it in the foreground, on any screen
   — do not press play on it.
5. On the **Walkman**, play **five tracks**: let two play all the way to the
   end, and skip three of them after a few seconds each.
6. Back on the **Pixel**, with Spotify still open, wait about **one minute**
   after the last track — a segment is only written when a listen ends, and
   a watcher waits a moment before flushing.
7. In Evergreen on the Pixel, go back to the debug screen → **Segments** and
   read the first number again. **Write down the difference.**
8. On the **Live log** tab, note whether any line says `watching media
   session` or `segment written` in that window. Read the lines; do not copy
   them here.
9. For completeness, do the same count on the **Walkman** — its segment count
   should have gone up by about five. That is the control: it says the test
   itself worked.

**How to read the answer.**

- Pixel difference **0**, and no `segment written` in the live log → no
  phantoms. The 5b merge does not need a deduplication rule, and the question
  closes.
- Pixel difference **greater than 0** → phantoms are real. Record how many,
  against five real plays, and 5b needs either the `PlaybackInfo` check above
  or the wall-clock overlap rule, and probably both.

#### Result — measured 2026-09-19. **Phantoms are real.**

Run on the Pixel 10 Pro with Spotify open on it and audio playing on the
NW-A307 over Spotify Connect. The Pixel was not touched during the test.
Counts read from its database over adb; no row is reproduced here.

| | |
|---|---|
| Capture segments before | 35 |
| Capture segments after | **38** |
| Written while only the Walkman was playing | **3** |
| Distinct tracks among them | 3 |
| "Listened" time they claim | 3.6 minutes |
| End reason | `TRACK_CHANGE` ×3 — indistinguishable from real listening |
| Fraction heard, lowest | 0.06 |
| Fraction heard, highest | 0.92 |

**They are not harmless fragments.** One of the three sits above the 0.85
upper breakpoint and would score as a **full play**; one sits below the 0.20
lower breakpoint and would score as a **rejection**. A phantom carries the
whole range of judgements, negative ones included, and nothing on the row
marks it as second-hand: same app package, same device id, same end reason as
a real listen.

**So the merge in 5b would double-count, and this confirms the question was
right to be promoted to blocking.** Two notes on what is and is not yet
established:

- The obvious fix now exists and is cheap: drop segments whose session reads
  `PLAYBACK_TYPE_REMOTE`, per the correction above. That is a `capture/`
  change and belongs to 5b.
- **Not yet established, and worth checking before designing anything:**
  whether the Pixel's view of a listen *agrees* with the Walkman's. If the
  remote session's position lags or truncates, a track played in full on the
  Walkman could be recorded on the Pixel as the 0.06 fragment above — which
  would make a phantom not merely a duplicate but an actively wrong negative
  judgement about a song the user was enjoying. Comparing the two devices'
  rows over the same minutes would settle it. Until then this is a
  possibility, not a finding.

### Still unknown — carried into Phase 6


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

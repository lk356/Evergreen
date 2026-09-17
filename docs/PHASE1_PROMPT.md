# Phase 1 prompt — paste this into Claude Code

Read `CLAUDE.md` and `docs/PLAN.md` first, including the **Phase 0 results** and
the **Carried into Phase 1** sections. We're starting Phase 1: the Capture MVP.

Work on a new git branch called `phase1-capture-mvp`. This phase introduces a
database schema, which `CLAUDE.md` says needs sign-off — so **propose the data
model and wait for my approval before writing code**. I'm new to development, so
explain the schema in plain language and say why each table exists.

## Goal
Turn the Phase 0 spike into a real capture pipeline: listening events persisted
to a Room database, replays handled correctly, and no duplicate rows.

## What Phase 0 already settled — don't re-derive or re-test these
- **Media sessions are the primary source.** Spotify's "Device Broadcast Status"
  intents are supplementary, carry the same information, and may be dropped
  entirely. Relying on them would also depend on a setting the user can turn off.
- **The Spotify track URI is available as `mediaId`** (`spotify:track:...`) on the
  media session metadata. Use it as the per-service track ID.
- **Position is a snapshot plus a monotonic timestamp, never a live value.** Any
  consumer must extrapolate forward, scale by `playbackSpeed`, freeze while
  paused, and clamp to the track duration. `spike/SkipDetector.kt` already does
  all four and has 14 passing unit tests — carry that logic and those tests over.
- **Both devices behave identically.** No NW-A307 special-casing is needed; its
  physical transport buttons look like any other control.
- **Spotify Connect playback and screen-locked playback are both captured.**
  Force-stopping Spotify ends capture, which is expected and fine.

## Step 0 — already done, read the findings
Step 0 (observing what Spotify actually emits) was completed on 2026-09-17 and is
written up in `docs/PLAN.md` under **Step 0 findings**. Read it before designing
the schema: it contains a captured loop, forward seek, restart and track change,
with the measured numbers the thresholds below are based on.

Do not re-derive it, and do not delete the spike until the new capture path
reproduces those cases.

Three things are still unknown and worth watching for while you work:
- what a natural end-of-track auto-advance looks like with repeat off,
- whether queue auto-advance is distinguishable from a manual "next",
- the exact shape of a session ending (Spotify force-stopped).

If something cannot be observed, record that it cannot. **Never invent plays.**
A scoring model built on invented events is worse than one built on gaps it
knows about.

## Build
1. **Room schema** — multi-service from day one, per `CLAUDE.md`:
   - a canonical track table (title, artist, album, duration),
   - a per-service ID table mapping canonical tracks to service track IDs
     (`spotify` + `spotify:track:...`), so a second service can be added later
     without a migration. One canonical track may map to many service IDs (album
     version vs single version). The nullable ISRC column lives here, on the
     per-service row, not on the canonical track — ISRC is learned from a service
     and describes that service's recording,
   - a listening-event table holding **one row per play segment**, not one row
     per callback,
   - a device identifier on events, so Phase 6 cross-device sync can tell them
     apart.
   Store only what the privacy rules permit: track, artist, album, duration,
   position, timestamps, app package, device.

2. **Play segments and boundaries.** The main correctness fix from Phase 0, and
   bigger than it first looked — see "Additions after Phase 0 review" below for
   the full set of cases and the rules that must hold. In short: a segment is a
   continuous stretch of listening to one track, and it ends at a track change, a
   replay (restart or loop), or the session ending.

3. **De-duplication.** One track change currently produces five or six log lines
   across two sources. Written straight to Room that would balloon the table.
   Pick the media session as the primary source and collapse repeated playback
   state callbacks; only write a row when a segment actually ends.

4. **Promote the spike.** Move the capture path into a real `capture` package and
   delete `com.example.evergreen.spike` entirely. Keep the allowlist gate, the
   empty notification callbacks, and the class-level comments explaining why they
   must stay empty.

5. **Debug screen** reading from Room instead of memory: recent segments with
   their end reason, listened ms and seek info, so it is possible to confirm at a
   glance that a replayed or looped track produced separate segments rather than
   a skip.

6. **Tests.** Carry over the `SkipDetector` tests, then add the cases listed in
   "Additions after Phase 0 review" item 8.

7. **Keep it light.** The NW-A307 has limited RAM and CPU — batch database writes
   rather than writing on every callback.

## Additions after Phase 0 review

Device testing found more broken cases than the original replay bug. The summary
table is in `docs/PLAN.md` under "Carried into Phase 1"; the captured evidence and
measured numbers are under "Step 0 findings". These rules follow from them.

1. **Segment rows store raw facts, not conclusions.** Each segment records: start
   and end wall-clock time, start and end position, **listened ms** (accumulated
   only while playback is actually running, scaled by `playbackSpeed`, and
   excluding jumps caused by seeking), forward-seek count, total ms skipped by
   seeking, and an **end reason**. Keep the end-reason set as small as what is
   actually detectable: `TRACK_CHANGE`, `REPLAY`, `SESSION_END`,
   `STALE`/`UNKNOWN`.

   On `REPLAY` rather than separate `LOOP` and `RESTART` values: a repeat-one loop
   and a manual restart are the same event with a different starting position, and
   a loop cannot be told apart from "previous" pressed at the very end of a track.
   The distinguishing fact is the **position reached**, which the row already
   stores. The debug screen can derive and display "looped" when the end position
   is within a few percent of the duration. Storing it as an enum value instead
   would bake a guess about user intent into the schema and invite Phase 3 to read
   meaning that is not there.

2. **Replay boundary.** A large backwards position jump within the same track —
   detected by comparing a new position sample against the extrapolated estimate —
   closes the current segment and opens a new one. Small scrub-backs must not.
   Measured backwards jitter with no user action is ~80 ms; start the named
   constant at 3000 ms, roughly 40x the noise floor.

3. **Any outcome column is provisional.** If one is kept it must be recomputable
   from the stored facts, and:
   - a segment closed by `REPLAY` is **never** labelled `SKIPPED`,
   - very short segments (a second or two, e.g. from pressing "previous" twice)
     are stored but not labelled `SKIPPED`,
   - seek-to-end is **not** labelled `COMPLETED` on final position alone —
     completion is judged on listened ms.

4. **Track mapping.** One canonical track can map to many service track IDs (album
   version vs single version). The nullable ISRC column goes on the per-service ID
   row, not on the canonical track.

5. **Room schema export.** Set `exportSchema = true` from version 1 and commit the
   generated schema JSON, so future migrations can be generated and tested rather
   than hand-written. This needs the `room.schemaLocation` annotation processor
   argument in `app/build.gradle.kts`.

6. **De-duplication, concretely.** `onMetadataChanged` re-fires with byte-identical
   content up to ten times a second during transitions, each paired with a state
   callback. Compare content, not callback count. Note also that the media session
   reports duration rounded to the second (109000 ms) while the broadcast reports
   it exactly (108518 ms) — do not mix sources in one calculation.

7. **Debug screen** shows end reason, listened ms, seek info and position reached
   per segment, and derives a "looped" hint from position reached.

8. **Tests**, in addition to the carried-over `SkipDetector` ones:
   - restart mid-track,
   - restart near the end of a track (a loop),
   - the "previous twice" sequence,
   - seek forward then auto-advance — listened ms must be small, and the segment
     must not be COMPLETED,
   - seek back by a few seconds — must **not** create a new segment,
   - ~80 ms of backwards jitter — must **not** create a new segment,
   - de-duplication of repeated identical metadata callbacks.

## Constraints
- No new third-party dependencies beyond Room without asking first.
- Still no `INTERNET` permission in this phase; capture stays local.
- The database must stay out of cloud backup and device transfer
  (`allowBackup="false"` and the extraction rules are already set — don't undo
  them).

## When done
Give me click-by-click instructions to test on device — in particular that a
replayed track produces separate segments and not a skip, that repeat-one is
captured, and that seeking to the end of a track does not record a completion.
Then update `docs/PLAN.md` (Status, Phase 1 checkbox, anything learned), and
commit.

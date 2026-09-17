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

## Build
1. **Room schema** — multi-service from day one, per `CLAUDE.md`:
   - a canonical track table (title, artist, album, duration, ISRC column
     reserved even though Spotify's media session doesn't supply one),
   - a per-service ID table mapping canonical tracks to service track IDs
     (`spotify` + `spotify:track:...`), so a second service can be added later
     without a migration,
   - a listening-event table holding **one row per play segment**, not one row
     per callback,
   - a device identifier on events, so Phase 6 cross-device sync can tell them
     apart.
   Store only what the privacy rules permit: track, artist, album, duration,
   position, timestamps, app package, device.

2. **Play segments and replay boundaries.** This is the correctness fix from
   Phase 0. A track restarting from 0:00 is currently invisible, so pressing
   "previous" twice records a near-complete listen as a SKIP — penalising exactly
   the songs the user likes most. A large backwards position jump within the same
   track must close the current segment, judge it, and open a new one.

3. **De-duplication.** One track change currently produces five or six log lines
   across two sources. Written straight to Room that would balloon the table.
   Pick the media session as the primary source and collapse repeated playback
   state callbacks; only write a row when a segment actually ends.

4. **Promote the spike.** Move the capture path into a real `capture` package and
   delete `com.example.evergreen.spike` entirely. Keep the allowlist gate, the
   empty notification callbacks, and the class-level comments explaining why they
   must stay empty.

5. **Debug screen** reading from Room instead of memory: recent events, their
   outcomes, and a way to confirm replays are recorded as separate plays.

6. **Tests.** Carry over the `SkipDetector` tests and add unit tests for replay
   boundary detection and de-duplication.

7. **Keep it light.** The NW-A307 has limited RAM and CPU — batch database writes
   rather than writing on every callback.

## Constraints
- No new third-party dependencies beyond Room without asking first.
- Still no `INTERNET` permission in this phase; capture stays local.
- The database must stay out of cloud backup and device transfer
  (`allowBackup="false"` and the extraction rules are already set — don't undo
  them).

## When done
Give me click-by-click instructions to test on device — in particular that a
replayed track produces two plays and not a skip. Then update `docs/PLAN.md`
(Status, Phase 1 checkbox, anything learned), and commit.

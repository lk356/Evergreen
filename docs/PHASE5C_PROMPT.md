# Phase 5c prompt — paste this into Claude Code

Read `CLAUDE.md` first, then `docs/PLAN.md` (the Status section, the Phase 5a
and 5b results, and everything under "Open questions — still open",
especially "Phase 5c — the phantom fix, which opens the merge"), then
`docs/SYNC_FOUNDATION.md`, `docs/SYNC_FORMAT.md` and
`docs/PHASE5_QUESTIONS.md`.

We're starting **Phase 5c: the merge.** Work on a new git branch called
`phase5c-merge`, branched from `main`. One commit per step, with that step's
tests passing, in the style of the existing history.

I'm new to development, and I found Phase 5b hard to follow. Explain what you
are doing and why in plain language, briefly, and define jargon the first
time it appears. At the end of each step, give me two or three sentences on
**what changed that I could notice**, even if the answer is "nothing yet,
and here is why this step was needed". When something has to be done on a
device, give me click-by-click instructions.

## What I want, decided 2026-09-21

- **The Pixel can do everything the Walkman can.** Capture, view, change
  settings, and write to Spotify. It stops being a mirror and becomes a peer.
- **Everything syncs both ways.** Each device's listening, likes and setting
  changes reach the other.
- **Moving files by hand is fine.** No network code, no new host, no
  automatic sync. That is Phase 7. The transport stays the file, over the
  same document pickers as today.
- **Connecting the Pixel to Spotify is fine.**
- **Check 0b is no longer a gate.** I want the merge whatever fraction of my
  listening happens on the Pixel. It is still worth counting, as a number
  rather than a decision (step 1).
- **I want edge cases handled**, including rare ones, as long as each one is
  measured before it is designed for. This project has already found once
  that the real problem differed from the expected one.

## The design, in short

**One writer role, handed over, never shared.** Both devices can do
everything, but only the device holding the **writer role** writes the
Evergreen playlist, takes the monthly snapshot, and exports a month to
Spotify. The role is moved with a button, and the move travels in the sync
file. Reading from Spotify (the likes sync) is allowed on every device.

The rule that makes this safe: **the current writer releases the role, and
the other device claims it only on importing that release.** So there can
briefly be no writer, which is harmless, but never two, which is not.

Keep the role tied to a **device id**, not to "an Android device". A
self-hosted server holding the role is a real possibility in Phase 7, and
maybe a cloud one later. **Do not build for either**, but don't make them
impossible.

## What is already built — don't rebuild or regress it

- **Capture is untouched since Phase 1.** This phase changes it, in step 2
  only. **Show me the diff and wait before committing it.**
- **Scoring is settled and calibrated.** A score is computed, never
  accumulated (invariant 1a). Segments store facts, never conclusions
  (invariant 1b). `RecomputeTest`, `ScoreCacheTest`,
  `CrossDeviceRankingTest` and `BatchSyncTest` must keep passing.
- **A segment's global name is `(deviceId, id)`**, and batches carry
  `originRowId`. Do not invent a second identity scheme.
- **The batch format is `docs/SYNC_FORMAT.md`**, with per-origin watermarks,
  refusal of gaps and missing baselines, one-transaction apply, and
  idempotence. Two-way sync extends it. It does not replace it.
- **`like_events` has no device column, deliberately**, and deduplicates on
  content. Keep it that way.
- **Restore from backup exists**, and so does the control that turns
  recording back on.
- **Spotify writes are behind guards**: one write a day, a dry run, a
  one-time confirmation, and the app never touches a playlist it did not
  create. The writer role *adds* to these guards and loosens none of them.
- **The database is at schema v7.** See step 3 for what is pre-approved.
- **The Walkman is my daily driver.** Its screen is 339 × 602 dp. Check
  every screen there, not only on the Pixel.
- **Instrumented tests destroy the database of the device they run on.** The
  build refuses to run them with a Walkman attached. Do not override that.

## Constraints that do not relax

- Every privacy rule in `CLAUDE.md`. **Never print, log or show a row of the
  database or of a batch file** in your messages to me. Counts and aggregates
  only; in measurements, reduce track identity to an index.
- Batch files and exports are listening history. They stay outside the repo
  and are never committed.
- Network access only to `accounts.spotify.com` and `api.spotify.com`. **This
  phase adds no network code and no new host.**
- No new third-party dependencies.
- Tokens stay outside the database.

## Pre-approved, with limits

- **Schema v8: one nullable column on `listening_segments` holding the UTC
  offset in minutes at the moment of capture**, as a hand-written migration
  in the style of v7 (add the column, don't rebuild the table), with a
  migration test on a populated v7 database.
- **A `formatVersion` bump in `docs/SYNC_FORMAT.md`** to carry that column
  and the writer role. A receiver must refuse a version it does not know, as
  it already does.

Anything else that changes the schema, the batch format or `capture/` beyond
what step 2 describes: stop, explain what it costs, and wait.

---

## Step 0 — Docs first

Commit on its own, before any code. In `docs/PLAN.md`: mark 5c started,
record the decisions under "What I want" above with today's date, and mark
check 0b as no longer a gate. Add this prompt to `docs/` as
`docs/PHASE5C_PROMPT.md` if it isn't there already.

---

## Step 1 — Measurements before the fix

**These have to happen before step 2.** Once the phantom fix lands, there are
no new phantoms left to measure.

**Read this before running a single command.** Every `adb` invocation carries
`-s <serial>`. Read both serials first, pin them in variables, and say which
is which. Take a backup on both devices before starting. Nothing in this step
writes to the Walkman's database.

**1a. The reverse phantom.** Check 0a showed the Pixel recording phantoms for
music playing on the Walkman. I believe the reverse also happens: the
Walkman, which always captures, recording phantoms for music playing on the
Pixel. Measure it the way step 2 of the Phase 5b plan measured check 0a:
clock skew first, then a handful of tracks played on the Pixel over Spotify
Connect with the Walkman awake, then a per-listen comparison table with
track identity reduced to an index. Confirm with `dumpsys media_session` on
the Walkman that the session reads `REMOTE`.

**1b. Old phantoms already in the Walkman's data.** If 1a says yes, the
Walkman's history may already contain phantom fragments from past Pixel
listening. The Spotify extended streaming history export has a per-play
platform field. Tell me whether it can identify them, and how many there
are, as a count. **Do not change or exclude anything yet.** Just tell me the
number and what you'd propose.

**1c. Check 0b, as a number.** From the same export: what fraction of my
listening happened on the Pixel versus the Walkman, over the last year and
overall. Counts and percentages only.

Afterwards, put the Pixel back as it was.

---

## Step 2 — The phantom fix

The plan says: drop segments whose media session reads `PLAYBACK_TYPE_REMOTE`,
one field and one `if`, in `capture/`.

**Before building it, tell me what it does to one case the plan doesn't
mention.** If I play from the Pixel to a speaker, TV or car over Spotify
Connect, the Pixel's session is remote, and **no device of mine is playing
locally**, so dropping remote sessions would lose a real listen that nothing
else records.

Two ways to handle it, and I want your recommendation between them:

- **Drop at source**, as planned, and accept that listens cast to a
  non-Evergreen speaker go uncounted.
- **Record it as a fact and exclude it at scoring time.** Store the playback
  type on the segment, the way `source` is already stored, and have scoring
  ignore remote segments by default. That matches invariant 1b and the
  "exclude at read time, never delete" rule, and keeps the decision
  reversible, at the cost of a column and storing the phantoms.

If you recommend recording, that is a schema change beyond what is
pre-approved. Propose it, and it can ride along with v8 in step 3.

Either way: **show me the diff to `capture/` and wait.** The fix applies on
both devices, so it also fixes the reverse case from 1a.

---

## Step 3 — The timezone column (schema v8)

**Why.** The daily cap buckets listening by *local* calendar day. Once two
devices both produce facts, a play needs to carry its own offset, or a device
in a different zone can cap the same listening differently. See "The
timezone is a real hazard" under Phase 5a results.

**Stamp every row, not just changes.** Every segment carries its own offset
(about 2 bytes a row). There is no timezone-history table.

**Backfill.** Existing rows get an offset computed from this device's
current zone rules *at each row's own timestamp*, so daylight saving comes
out right for free. Small errors in the old data are acceptable to me.

**Scoring** buckets each row by its own offset, falling back to the device's
current zone for any row that has none.

**The test I most want to see pass:** the Walkman's ranking is **identical**
before and after the migration. The backfilled offsets are the ones it was
already using, so nothing should move. If anything moves, stop and tell me.

**`offline_timestamp`.** `PLAN.md` says it was parked for the same reason as
the timezone: it only bites once two devices produce facts. Read what it
says, and tell me whether 5c has to decide it before building anything for
it.

---

## Step 4 — Two-way sync

- **The Pixel leaves mirror mode and captures.** Keep mirror mode in the
  code as an option: a read-only device is still a reasonable thing to be.
  But the Pixel stops being one.
- **Before the Pixel records its first play, confirm its device id is its
  own**, not the Walkman's carried over by the mirror import. Say how you
  checked.
- **Each device exports what it has that the other doesn't, and imports what
  the other sends.** The per-origin watermarks from 5b should already make
  this work. Tell me where they don't.
- **Setting changes** from either device merge through `parameter_history`,
  and the latest `changedAt` wins. Tell me how clock skew between the devices
  could bite here, and whether it matters at the sizes measured in step 1.
- **Scores are dropped and recomputed on import**, as today.

---

## Step 5 — The writer role

As described under "The design" above:

- the writer can **release** the role to the other device, by name; the
  release travels in the next batch; the other device **claims** it only on
  import;
- a device that is not the writer refuses every Spotify playlist write and
  snapshot export, saying why, exactly as a mirror does today;
- the playlist id is kept, so the new writer continues the same Evergreen
  playlist rather than creating a second one;
- only the writer takes the monthly snapshot;
- **a forced take-over** exists for when the writer device is lost or
  broken, behind a loud confirmation that says what can go wrong. Tell me
  what the failure modes are.

Snapshots and `evergreen_membership` cross from whichever device held the
role at the time. Tell me if the existing upsert rules in `SYNC_FORMAT.md`
§5 need anything to handle a role change mid-month.

---

## Step 6 — The overlap report

After every import, count segments from the two devices that overlap in wall
clock time for the same track, and **report the count**, on screen and in
the import result. **It does not exclude or change anything.** It is a
tripwire for the phantom fix failing (Spotify changing its behaviour, or a
different app), not a merge rule.

---

## Step 7 — Handoff, measured first

The rare case: a song starts on one device and playback moves to the other
mid-track. Both devices are genuinely playing, each records part of it, and
scoring may see two partial plays where there was one full one.

**7a. Measure it.** With both devices on adb and the fix from step 2 in
place: start a track on the Walkman, move playback to the Pixel partway
through, and let it finish. Do it a few times, at different points in the
track, and once in the other direction. I'll do the handoffs by hand in
Spotify's device picker. You watch `dumpsys media_session` and logcat on
both, and control playback over adb where that's reliable. Then show me a
per-listen table: which device recorded what fraction, the gap or overlap
between the two parts after clock correction, and track identity as an
index. **Stop and show me the table before designing anything.**

**7b. Then the rule.** Stitching happens **at scoring time, not in the
stored data.** Both segments stay as recorded, and scoring treats a matching
pair as one listen: same track, different devices, one ending about where
the other begins, positions continuous. Propose the thresholds from what 7a
actually showed.

---

## Step 8 — What the screens say

Short, and checked at 339 × 602 dp on the Walkman:

- which device holds the writer role, on both devices;
- when each device last received the other's plays. **The writer should warn
  before writing Evergreen if it hasn't heard from the other device in a
  while**, because it would be writing a ranking that misses listening;
- the overlap count from step 6, only when it isn't zero;
- the home screen's "Capture has gone quiet" message, which 5b left wrongly
  offering to fix notification access when capture is merely switched off.
  Fix that while you're here.

---

## Step 9 — Tests

Beyond each step's own:

- **Two-way idempotence and order independence.** Batches from both devices,
  applied repeatedly and in either order, give identical databases and
  identical rankings on both devices.
- **Never two writers.** No sequence of release, claim, import and repeated
  import leaves both devices holding the role. The forced take-over is the
  only exception, and it is tested separately.
- **The v8 migration** leaves the ranking identical (step 3).
- **Timezone.** Plays near midnight from two devices in different zones cap
  on their own local days.
- **Remote sessions** are dropped or excluded, per the decision in step 2.
- **Handoff stitching** turns a split full play into one full play, and
  leaves two genuinely separate plays of the same track alone.

---

## Step 10 — Device verification

Write the checklist into `docs/PLAN.md` as 5b's was, with an empty results
table for me to fill. At least:

- the Pixel captures, and its plays reach the Walkman;
- the Walkman's plays reach the Pixel;
- the rankings match on both screens after a round trip;
- a writer handover in each direction, and the non-writer refusing writes;
- a setting changed on the Pixel shows up on the Walkman;
- a Pixel-to-speaker listen, handled as step 2 decided;
- the overlap count stays at zero in normal use;
- the Walkman still writes Evergreen on schedule while it holds the role.

---

## When done

Update `docs/PLAN.md`: Status, the Phase 5 checkboxes, what was learned, and
anything that turned out not to be true. Commit. **Merge `phase5c-merge`
into `main` only when I say the device verification passed.**

If the phase turns out bigger than one branch should carry, say so early and
propose where to split it, rather than finding out at step 9.

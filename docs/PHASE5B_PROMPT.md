# Phase 5b prompt — paste this into Claude Code

Read `CLAUDE.md` first, then `docs/PLAN.md` (the Status section, the Phase 5a
results, and everything under "Open questions — still open"), then
`docs/SYNC_FOUNDATION.md` in full, then `docs/PHASE5_QUESTIONS.md`.

We're starting **Phase 5b: delta sync.** Work on a new git branch called
`phase5b-delta`, branched from `main`. One commit per step, with that step's
tests passing, in the style of the existing history.

I'm new to development. Explain what you are doing and why in plain language,
briefly, and define jargon the first time it appears. When something has to be
done on a device, give me click-by-click instructions.

## Why this phase exists, and what it is not

Phase 5a made the Pixel a readable mirror, moved as a whole 21 MiB file by
hand. That works, and it is not going to be automated as it stands: a daemon
shipping 21 MiB to replace a database wholesale is the wrong thing to
automate.

**5b makes "what is new since X" a question with an answer.** A documented
batch of facts, from one device, after a watermark, written to a file and read
back over the transport that already exists. A mirror update becomes kilobytes.

Per `docs/SYNC_FOUNDATION.md` Part 8, this is the one thing worth building
next, and it earns its place three times over: it is what every future
transport needs underneath it, it is what the Linux agent in Phase 7 will
speak, and it forces the identity rules in Part 4 to be settled by working
code rather than on paper. **If the merge is never built, almost none of this
is wasted.**

**This phase is not the merge.** Both devices capturing, facts flowing both
ways, segments deduplicated, writer arbitration — that is now **Phase 5c**,
and the phantom fix in `capture/` sits at the front of it as its hard
prerequisite. Do not build toward 5c, and do not ask me to settle its
questions now.

**The Pixel stays read-only throughout.** Mirror mode, capture off, every
Spotify write refused. A delta batch arriving does not change that, and every
guard Phase 5a added still applies to a database that has had deltas applied.

**Do not build a `SyncTransport` interface.** `docs/SYNC_FOUNDATION.md` opens
by explaining why, and nothing has changed. One transport, the file, the same
document pickers as today.

## What is already built — don't rebuild or regress it

- **Capture is untouched since Phase 1**, and stays untouched in this phase.
  `PLAYBACK_TYPE_REMOTE` belongs to 5c. If a change here has to touch
  `capture/`, stop and show me the diff first.
- **Scoring is settled and calibrated.** A score is computed, never
  accumulated (invariant 1a). `RecomputeTest`, `ScoreCacheTest` and
  `CrossDeviceRankingTest` must keep passing.
- **`listening_segments.deviceId` has existed since Phase 1** and is indexed.
  A segment's global name is already `(deviceId, id)`. Do not invent a second
  identity scheme.
- **Mirror mode is two rows in `sync_state`**, and four places check the guard
  before writing to Spotify. The check comes first in each. Do not loosen any
  of them.
- **The backup button** is SQLite `VACUUM INTO` through the system
  create-document picker. **Reuse it. Do not invent a third export path.**
- **The mirror import** validates before replacing and neutralises before
  replacing. The delta import is a *different* code path — it inserts, it does
  not replace — but it inherits the same validation discipline.
- **The database is at schema v6.** Schema changes need my approval; see
  step 3.
- **The Walkman is the writer device and my daily driver.** Its screen is
  339 × 602 dp. Check every screen there, not only on the Pixel.
- **Instrumented tests destroy the database of the device they run on.** The
  build refuses to run them with a Walkman attached; do not override that.

## Constraints that do not relax

- Every privacy rule in `CLAUDE.md`. **Never print, log or show a row of the
  database** in your messages to me — counts and aggregates only. This applies
  to the batch files too: a batch is listening history in a text format, and
  it is easier to paste by accident than a `.db` is.
- Batch files are listening history. They belong in `~/evergreen-backups/` or
  similar, **outside the repo**, and must never be committed. Add whatever
  gitignore entries that needs, in step 0.
- Network access only to `accounts.spotify.com` and `api.spotify.com`. **This
  phase adds no network code and no new host.** The transport is a file.
- Tokens stay outside the database.

---

## Step 0 — Docs first

Commit this on its own, before any code.

**0.1 Split Phase 5 in `docs/PLAN.md`.** The roadmap currently has 5a done and
"5b: full peer sync". Make it three:

- **5a — read-only mirror, whole-file transport.** Done 2026-09-19, PR #3.
- **5b — delta sync.** The fact batch and per-device watermarks, one way,
  writer to mirror, over the file transport. The Pixel stays read-only.
- **5c — the merge.** Both devices capturing, two-way, deduplicated. Opens
  with the phantom fix in `capture/`, which is its hard prerequisite per
  `docs/SYNC_FOUNDATION.md` 5.1. Gated on check 0b.

Record the reasoning in a sentence or two: the fact batch is needed by the
merge, by the Linux agent and by any future server, and it is useful on its
own, so it is a deliverable rather than the first half of one.

**0.2 Move the phantom fix to the front of 5c** wherever it is currently
described as belonging to 5b, and say why it does not block 5b: nothing
merges in 5b, and while the Pixel is a mirror its capture is off, so no
phantom rows are accumulating.

**0.3 Record the restore gap**, under Phase 5a results or beside the backup
button's description:

> The backup button produces a file, but until Phase 5b there was no
> operation that put it back on the writer device. "Import a mirror"
> neutralises before replacing, so importing a backup onto the NW-A307 would
> have made it a mirror of itself and stopped it writing Evergreen. Restore is
> a distinct operation and is built in 5b step 1.

**0.4 Correct check 0b's method** in `docs/PHASE5_QUESTIONS.md`. It is
currently "keep a rough count over the day-to-day use period", which mirror
mode made impossible — decision 3b turns the Pixel's capture off, so the Pixel
cannot count its own listening while it is a mirror. Record the alternative:
the Spotify extended streaming history export carries a per-row platform
field, so an export answers 0b exactly and retroactively, with no capture at
all. Note that the existing export runs to 2026-09-15 and may already answer
it. **Do not build anything for this.** It is a note.

**0.5 Gitignore** whatever is needed so a batch file or a `.db` cannot be
committed from the repo root by accident.

---

## Step 1 — Restore, before anything else touches anything

I am about to attach both devices to adb in step 2. Before that, my daily
driver needs a route back from a backup file.

**Build "Restore from backup"** as an operation distinct from "Import a
mirror". Same picker, same validation, same `VACUUM INTO` file format — but it
does **not** neutralise. It replaces the database and leaves the device as
what it was: writer stays writer, playlist id stays, capture stays on, mirror
mode stays off.

Because this is the one operation that can silently demote or promote a
device, make it say plainly what it is about to do before it does it, and name
the device the file came from. Refuse, with a readable reason, if the file's
originating device is not this device — restoring the Walkman's backup onto
the Pixel is "import a mirror", and the app should say so rather than doing
something surprising.

Tests: restoring on the writer leaves the writer flag set and mirror mode off;
importing the same file as a mirror still neutralises; a file from another
device is refused by restore.

**Then walk me through it on the Walkman:** take a fresh backup, and verify
the restore path against a copy on the Pixel rather than on the Walkman
itself. I want the route tested before I need it, not while I need it.

---

## Step 2 — Check 0a continued: does the Pixel's view of a listen agree?

A measurement, not a build. It informs 5c. Record the result in `docs/PLAN.md`
beside the existing phantom result.

Phase 5a established that the Pixel records phantom segments for music playing
on the Walkman over Spotify Connect — three of them, one scoring as a full
play and one as a rejection. **What is not established is whether the Pixel's
view of a listen agrees with the Walkman's or truncates it.** If it truncates,
a phantom is not merely a duplicate but an actively wrong negative judgement
about a song I was enjoying, and 5c needs the wall-clock overlap rule as well
as the source-level drop.

**Both devices attached to adb at once.** Read this before writing a single
command:

- **Instrumented tests were run on the Walkman by accident once already** and
  destroyed its database. The Gradle guard covers `connectedAndroidTest` and
  **nothing else** — it will not save us here. Every adb invocation in this
  step carries `-s <serial>`. Read both serials first, pin them in variables,
  and echo which is which before you run anything against either.
- **Nothing in this step writes to the Walkman.** It plays music; that is its
  whole role. Every command aimed at it is a read.
- I will have taken a backup in step 1.

**Procedure:**

1. Read both serials. Confirm with me which is which before proceeding.
2. **Measure clock skew**: `date +%s` on both devices, twice, a few seconds
   apart. Record the offset. Without this, a few seconds of drift reads as
   truncation.
3. Take the Pixel out of mirror mode and turn capture on. Its database is
   disposable; it is only ever a copy.
4. Note the segment count on both devices.
5. On the Walkman, play **six tracks over Spotify Connect** with Spotify open
   on the Pixel and untouched: three to the end, three skipped at varying
   points — one very early, one around a third, one around two thirds. Tell me
   when to do what.
6. Wait a minute after the last track, then read the new segments from both.
7. **Compare per listen, not in aggregate.** For each of the six, what did
   each device conclude? The output I want is a small table of
   `(index, fractionHeard on the Walkman, fractionHeard on the Pixel,
   listenedMs difference, start-time offset after clock correction)`, with
   track identity reduced to an index. **No titles, no artists, no raw rows.**
8. In the same window, run `dumpsys media_session` on the Pixel and confirm
   Spotify's Connect session reads `volumeType=REMOTE` while other sessions
   read `LOCAL`. This ties the 5c fix to the specific rows we just watched
   appear.

**How to read it.** Agreement across all six — say, fraction heard within a
few percent — means a phantom is a plain duplicate and the source-level drop
is sufficient for 5c. Systematic truncation on the Pixel means 5c needs the
overlap rule too, and it means the existing phantom sample's 0.06 was probably
truncation rather than a genuine early skip.

Afterwards, re-import a fresh mirror on the Pixel and leave it as a mirror.

---

## Step 3 — Schema v7: the originating row id

**Stop here and get my approval before writing the migration.** Show me the
column, the migration, and what happens to existing rows.

The problem, in plain terms: today a segment's global name is
`(deviceId, id)`, where `id` is the row id the originating device assigned. In
5a that survived because the whole file was replaced. When a batch is
*inserted* into a database that already has rows, the receiving device assigns
its own row ids, and the originating id is lost — so the same batch applied
twice would insert twice, and idempotence is gone.

The fix is a column holding the originating row id on imported rows, with the
pair `(deviceId, originating id)` uniquely indexed so a second application of
the same batch is a no-op rather than a duplicate. Locally captured rows carry
their own device id and their own row id, as now.

Propose the exact shape. State whether the unique index can be added without a
table rebuild on 77k rows, and how long the migration takes on the NW-A307 —
that device is low-powered and the migration runs on my real history.

---

## Step 4 — The batch format, written down

Create **`docs/SYNC_FORMAT.md`**. A batch is: these facts, from this device,
after this watermark. JSON Lines or similar — per `SYNC_FOUNDATION` 3.2 the
specific choice matters far less than that there is one and that it is not a
SQLite file, because a Linux agent cannot produce a Room database.

It needs a header naming the originating device, the format version, when it
was taken, and the watermark range it covers; then the fact rows.

**Which tables cross, and their identity rules, are already decided in
`docs/SYNC_FOUNDATION.md` Part 4.** Follow it rather than re-deriving it. In
particular: `listening_segments` is device-scoped; `like_events` is
content-addressed and deliberately has **no** device column, because two
devices seeing the same like are seeing one fact twice; `tracks` and
`service_tracks` are content-addressed with local row ids that must be
translated on import, not trusted; `scores` never crosses; `sync_state` never
crosses wholesale.

Write the document so that someone implementing a Linux agent in Phase 7 could
produce a valid batch from it without reading any Kotlin.

---

## Step 5 — Export a batch

On the writer device: **"Export changes since…"**, beside the existing backup
button, through the same create-document picker.

The query is *everything from this device after local id N*, which the schema
answers as it stands because segments are append-only with ascending
per-device ids. Where N comes from: for now, the device remembers the high
water mark of its last export, in `sync_state`, and the screen says what that
is and offers to export from the beginning instead.

Report counts, not rows, in the confirmation. Say how large the file is — the
point of the phase is that this number is small, and I want to see it next to
5a's 21.0 MiB.

---

## Step 6 — Import a batch

On the mirror: **"Import changes"**, through the open-document picker.

- It **inserts**; it does not replace. A delta arriving on top of a mirror
  extends it.
- It **refuses a batch with no baseline.** A delta is meaningless against an
  empty database or against a mirror of a different device; say which, and
  change nothing.
- It **refuses a batch that would leave a gap** — a batch starting after the
  watermark the receiver holds. Say what is missing.
- It is **idempotent.** The same batch applied twice changes nothing. This is
  the property the whole design rests on; test it directly.
- It **translates local row ids** for content-addressed tables through the
  existing `resolveServiceTrack` path rather than trusting them.
- It **drops the `scores` cache and recomputes**, as the mirror import does.
- Every Phase 5a guard still holds afterwards. A mirror that has had deltas
  applied is still a mirror: capture off, no Spotify writes, home screen still
  says so.

The receiver's state is one small thing: the highest originating id seen from
each device. That is all a watermark is.

---

## Step 7 — Tests

Beyond each step's own:

- **Idempotence.** Apply a batch twice; assert the database is identical.
  Apply it three times interleaved with another batch; same.
- **Order independence.** Two batches applied in either order give the same
  database.
- **Delta equals whole.** Build a database by full mirror import; build
  another by full mirror import of an older file plus the delta covering the
  difference. **Assert the rankings are identical.** This is the phase's
  equivalent of `CrossDeviceRankingTest` and it is the thing I most want to
  see pass.
- **Like events deduplicate.** The same like arriving in two batches from two
  devices collapses to one fact, per Part 4.
- **A gap is refused**, and a batch from an unknown device is refused.

---

## Step 8 — What the screens say

Small. The Pixel's home screen already says it is a mirror and how old it is;
"how old" should now mean the newest fact it holds, not the age of the last
whole-file import. On the writer, the export screen says what the watermark
is. Nothing else changes.

Check both on the Walkman at 339 × 602 dp, not only on the Pixel.

---

## Step 9 — Device verification

Write the checklist into `docs/PLAN.md` as Phase 5a's was, for me to run
across both devices, and leave the results table empty for me to fill. It
should cover, at least: restore on the writer; a first delta export and
import; the file size next to 21.0 MiB; the ranking matching after a delta;
the same batch imported twice changing nothing; a gapped batch refused
readably; every Spotify write still refused on the mirror; and the Walkman
unchanged and still writing Evergreen on schedule.

---

## Things to watch for

- **The first export has no watermark.** Decide what it does — probably offers
  the whole history as a batch, which is a useful test of the format at 77k
  segments even though the file transport already covers that case better.
  Report how long it takes on the NW-A307.
- **77k segments through a text format on a low-powered device.** Stream it,
  batch it, and say if it is slow on the Pixel, because it will be worse on
  the Walkman.
- **`offline_timestamp` and the timezone question stay parked.** The device
  timezone was recorded in 5a as a 5b decision; it belongs to 5c, because it
  only bites when two devices both produce facts. Leave it, and say so in the
  docs if you touch that section.
- **AGP and KSP.** `android.disallowKotlinSourceSets=false` in
  `gradle.properties` is a documented workaround; a "Using kotlin.sourceSets
  DSL is not allowed" configuration failure is that flag being withdrawn, not
  your change.
- **A worktree build has no Spotify client ID** — `local.properties` is
  gitignored, and a worktree build reports "Not configured" in a way that
  looks exactly like an expired connection.

If something cannot be observed or verified, say so. Never invent data to fill
a gap.

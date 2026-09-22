# Phase 5c, continued — the phantom fix, rebuilt

Read `CLAUDE.md` first, then **`docs/PHASE5C_STEP7_FINDINGS.md`** — that is the
handoff and it is the most important thing in this list. Then `docs/PLAN.md`
(the Status section and everything under "Phase 5c step 7a"), then
`docs/PHASE5C_PROMPT.md` (steps 7b, 9 and 10 only), `docs/SYNC_FORMAT.md` and
`docs/SYNC_FOUNDATION.md` §5.1.

Continue on the **existing branch `phase5c-merge`**. Do not branch again and do
not start a new phase — this is the gate on what that branch already contains.
Ten commits are already on it, 326 unit and 269 instrumented tests pass. One
commit per step, with that step's tests passing, in the style of the history.

I'm new to development. Explain what you are doing and why in plain language,
briefly, and define jargon the first time it appears. At the end of each step
give me two or three sentences on **what changed that I could notice**, even if
the answer is "nothing yet, and here is why this step was needed". When
something has to be done on a device, give me click-by-click instructions.

## Why this exists

The previous session built steps 0–6 and 8 of `docs/PHASE5C_PROMPT.md`, then
measured step 7 and found the step 2 phantom fix does not work:

- `PlaybackInfo.getPlaybackType()` catches **32% of phantoms** (66 of 205
  samples), and the Pixel called itself `LOCAL` **103 times out of 103** while
  producing no sound;
- it also **discards real listening** — 14 samples where the Walkman called
  itself remote while it was the device making the sound;
- stickiness within a segment cost **23.6 of 28.5 minutes (83%)** of a real
  session, scoring nowhere at all.

**`phase5c-merge` must not merge to `main` until this is fixed.**
`docs/SYNC_FOUNDATION.md` §5.1 makes the phantom fix the merge's one hard
prerequisite, and merging now would have the Pixel export every phantom to the
Walkman and inflate my play counts invisibly.

## The rule that is decided

> Exclude a segment only when **another device demonstrably produced the sound**
> for the same track at the same time. Otherwise count it — even if this device
> called itself remote.

It fails safe in the right direction: the current rule excludes on suspicion and
loses real listening; this one counts unless something better contradicts it.

**The evidence it rests on is verified**, in the app, not just in `dumpsys`:
`AudioManager.getActivePlaybackConfigurations()` is not redacted, and it
identified exactly one owner in **249 of 251** paired samples.

## What is already built — don't rebuild or regress it

- **Everything in steps 0–6 and 8 of `docs/PHASE5C_PROMPT.md`**: two-way sync,
  the writer role, schema v8, the overlap report, the screens. All committed
  and tested.
- **Schema v8 is on the real Walkman database** — 78,110 rows, zero wrong
  offsets, ranking verified unmoved. Do not migrate it again.
- **`playbackType` stays.** It is not useless (66 correct catches) and it is a
  fact that was observed. Add beside it; do not delete it.
- **Do not build 7b's stitching rule.** A handoff does not split a listen —
  both devices record it in full. Measured, twice.
- Scoring invariants 1a and 1b, the batch format at `formatVersion` 2, the
  writer role's release-then-claim discipline, and every Spotify write guard.
- **Instrumented tests destroy the database of the device they run on**, and
  the build refuses to run them with a Walkman attached. Do not override that.

## Constraints that do not relax

- Every privacy rule in `CLAUDE.md`. **Never print, log or show a row of the
  database or of a batch file.** Counts and aggregates only; in measurements,
  reduce track identity to an index.
- No new third-party dependencies. No new network hosts — this adds none.
- Batch files and exports stay outside the repo.
- **Measure before designing for it.** Three designs in this phase were written
  against signals nobody had checked and all three were wrong. Twice I was the
  one who caught it.

## Pre-approved, with limits

- **One nullable column on `listening_segments`** recording whether this device
  was producing media audio during the segment, as a hand-written migration in
  the style of v7 and v8, with a migration test on a populated v8 database.
- **A `formatVersion` bump in `docs/SYNC_FORMAT.md`** to carry it. A receiver
  must refuse a version it does not know, as it already does.

Anything else that changes the schema, the batch format or `capture/` beyond
what step 1 describes: stop, explain what it costs, and wait.

---

## Step 1 — capture records whether this device made the sound

Via `AudioManager.registerAudioPlaybackCallback`, so it is live rather than
polled. Beside `playbackType`, not replacing it.

**Drop the stickiness.** That is what turned a faithful signal into an 83%
loss. If you think capture should instead cut a new segment when the value
changes — the way it already cuts on a position jump — **say so and wait**,
because that changes how capture segments and is past what is approved here.

**Show me the diff to `capture/` and wait before committing it.**

## Step 2 — scoring applies the rule

Replace the flat "exclude `REMOTE`" with the rule above. It needs:

- a **deterministic tie-break**, so two devices holding the same facts cannot
  disagree about which listen counts;
- **tolerance of a ~2 second gap** — the measurements found one sample with
  both devices producing audio and one with neither while a session claimed
  `PLAYING`, both at handoff instants;
- the answer to depend only on **what has been imported**, which is correct:
  same facts, same ranking.

Tell me what it does to the cast-to-a-speaker case and to a third device.

## Step 3 — make the overlap tripwire able to see again

Step 6's report counts **0** against the real database, because it excludes
remote rows and both sides were wrongly marked. It is blind to the failure it
exists to catch. Fix it so it counts something still visible under the new
rule, and tell me what it would have reported for the session in the findings.

## Step 4 — the two open hazards

Both are measured facts with no rule behind them yet. **Measure before
designing, and show me before building.**

- **A listen nobody heard.** Handing playback to a sleeping Pixel recorded a
  listen, but no audio reached the speakers until I touched the fingerprint
  reader. Does the active-audio signal catch it?
- **Where the sound goes.** `getAudioDeviceInfo()` is redacted for this app, so
  whether Bluetooth or wired output perturbs the session is unanswered. Tell me
  whether it can be answered another way, or whether it stays open.

## Step 5 — tests

Rewrite step 9's list in `docs/PHASE5C_PROMPT.md` around the new rule rather
than the one it was written for. Beyond each step's own, I want:

- a phantom is not counted when another device demonstrably produced the sound;
- a listen **is** counted when nobody else claims it, even if marked remote;
- the same facts give the same ranking on both devices, whatever the import
  order;
- a handoff mid-track counts **once**, not twice and not zero times;
- the two-ambiguous-sample case does not lose a listen.

## Step 6 — device verification

The checklist in `docs/PHASE5C_PROMPT.md` step 10, plus: replay the handoff
session and confirm the 83% loss is gone. Write it into `docs/PLAN.md` with an
empty results table for me to fill.

## When done

Update `docs/PLAN.md`: Status, the Phase 5 checkboxes, what was learned, and
anything that turned out not to be true. Commit. **Merge `phase5c-merge` into
`main` only when I say the device verification passed.**

## Practical traps, already paid for

- **Re-read the adb serials every session.** The Pixel is on wireless debugging
  and its serial contains a colon, so `${pair##*:}` splits it wrongly and every
  `adb -s` call silently targets nothing. Use `name=serial` with `${pair#*=}`.
- **Clock skew** was ~1,109–1,128 ms, Pixel behind Walkman, drifting a few
  hundred ms a day. Measure it at the start of any cross-device comparison.
- **To run one instrumented test without wiping the device**, build with
  `assembleDebug assembleDebugAndroidTest`, `adb install -r` both APKs, and use
  `adb shell am instrument -w -e class <FQCN>
  com.example.evergreen.test/androidx.test.runner.AndroidJUnitRunner`.
- **The Walkman keeps playing while unplugged from USB**, which is what makes
  measuring the Pixel possible while the Walkman is the one making sound.

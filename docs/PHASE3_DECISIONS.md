# Phase 3 decisions — handoff from the planning Project

Answers to `docs/PHASE3_QUESTIONS.md`, decided 2026-09-17.
Companion to `docs/PLAN.md`. Part 1 and Part 2 of the questions document are
accepted as settled and are not re-opened here.

---

## 0. Blocking checks — do these before writing scoring code

**0a. Are Spotify DJ commentary rows stored?**
The questions document says commentary is "trivially excluded" because it
carries `spotify:media:…` with zero duration. Confirm whether it is excluded at
*capture* time or at *read* time. If capture drops it, the only evidence that a
DJ session was running is being destroyed, section 2f below becomes
unanswerable forever, and the "capture stores facts" principle is broken. If it
is dropped, fix capture first: store the row with a flag, exclude it when
scoring.

**0b. Run the calibration.**
The Spotify extended streaming history has arrived and is downloaded. Two of the
scoring parameters should be derived from it rather than guessed. Write a
throwaway analysis script (not app code), run it locally, and record the
resulting numbers in this file.

*Half-life.* For liked tracks, measure the return curve: given a track has gone
N days without a play, how often is it played again within the following 90
days? The half-life is the gap length at which that probability falls to about
50%. Rationale: a song's score should halve at the point where the odds of it
coming back have halved.

*Fraction-heard breakpoints.* Split plays by `reason_end` into completed
(`trackdone`) and cut short (`fwdbtn`, `endplay`, and similar). Plot
`ms_played` ÷ track duration for each group. The lower breakpoint is where
cut-short plays stop dominating; the upper is where completed plays take over.
Notes: `skipped` is frequently `null` and should not be trusted; durations are
not in the export and must come from the already-synced track data; a replay
appears as two rows, so the export cannot inform replay scoring at all.

If either curve is too noisy to read, use the fallbacks in section 3 and say so.

---

## 1. Architecture invariants

**1a. A score is computed, never accumulated.** A score is a pure function of
(listening segments, like events, parameters, now). There is no running total
updated as plays arrive. A `scores` table is permitted only as a cache that can
be dropped and rebuilt from scratch at any moment. Add a test: change a
parameter, recompute, and assert the result matches a from-scratch computation.

Reason: `docs/PHASE3_QUESTIONS.md` Part 5 requires parameters to be retunable
without recapturing anything. An accumulated score silently breaks that promise,
because old contributions were folded in under old parameters and cannot be
unwound.

**1b. Imported history shares the segment row shape.** Plays imported from the
extended streaming history land as the same rows the scorer already reads, with
the position, seek and jump fields null. There is no parallel import path and no
second scoring route. The scorer must tolerate rows that carry only a timestamp,
a listened duration and a track.

**1c. Imported plays decay by their own timestamps.** A 2019 play arrives
already faded. The failure mode this prevents is an Evergreen dominated by
whatever was played most in some past year.

**1d. Every parameter in section 3 is a stored setting, not a constant.**
Phase 4's settings screen edits these. Changing one triggers a recompute, not a
migration.

---

## 2. The scoring model

**2a. Plays are scored by fraction heard, on one curve.**
This replaces both "skip penalty" and "minimum listened time to count" from
question 3a — they were the same question asked twice. Each segment scores on
`listenedMs` ÷ duration, passed through a single curve: below the lower
breakpoint the contribution is negative (a rejection), between the breakpoints
it is approximately zero, above the upper breakpoint it is full positive credit.

This handles a 2-second fragment, a genuine mid-song skip, and a skip at 95%
(which is not a rejection) with one shape rather than a threshold plus a penalty
constant.

**2b. Time decay is exponential, with a half-life from 0b.**

**2c. A like is a scoring event.** It carries a weight expressed in
play-equivalents and decays like any other event. This replaces the grace period
from question 3a.3: a freshly liked song enters strong and fades only if
listening does not back it up, with no separate protected-window mechanism and
one fewer parameter.

**2d. Replays.** A backwards jump of 10 seconds or more counts as a replay;
below that is treated as player jitter rather than intent. A replay is worth the
listening time it represents, with no multiplier. Revisit after the app has
produced real replay data — the streaming history export cannot inform this.

**2e. Loops.** One track's contribution is capped per day at roughly two full
plays. This handles the repeat-one-overnight case with a cap rather than a
diminishing-returns curve, and needs no separate loop detection.

**2f. Seeks: no rule.** Answering question 3c: forward seeks are neither
penalised nor specially credited. A seek already reduces `listenedMs`, so under
2a it is priced in. An explicit seek penalty would also punish anyone using
crossfade, and the stored data cannot distinguish a crossfade from a manual
seek. This is a deliberate decision, not an omission.

**2g. Spotify DJ.** Answering question 3d. The inference "a DJ session was
running", drawn from neighbouring commentary rows, is used only where being
wrong is harmless:

- Negative signals during an inferred DJ session are suppressed. Skipping
  through DJ picks is browsing, not rejection.
- Positive signals count at full weight. Letting a DJ pick play through is real
  information regardless of who queued it.

A misfiring inference therefore costs a penalty that might have been wanted,
never a false rejection. Conditional on 0a.

---

## 3. Parameters

Calibrate 0b first. Fallbacks apply only if the analysis is inconclusive.

| Parameter | Source | Fallback |
|---|---|---|
| Decay half-life | Calibrated (0b) | 90 days |
| Fraction-heard lower breakpoint | Calibrated (0b) | 0.20 |
| Fraction-heard upper breakpoint | Calibrated (0b) | 0.70 |
| Negative weight below lower breakpoint | Chosen | −0.5 play |
| Like event weight | Chosen | 3 plays |
| Replay jump threshold | Chosen | 10 s |
| Per-track daily contribution cap | Chosen | 2 plays |

---

## 4. Membership

Answering question 3e. One score, two cutoffs.

**Evergreen — the playlist.** Top 100 tracks by score, written to Spotify. This
is a playlist meant to be shuffled.

**Hysteresis.** A track joins at rank 100 or better and leaves only when it
falls below rank 130. Required because neither direction of error was judged
worse than the other, so boundary churn cannot be resolved by leaning one way.

**The record — what is alive.** Every liked track scoring above a low floor,
kept in the app and searchable. Not written to Spotify. This is the "fuller
record behind it" half of the brief.

**Unlike behaviour.** Answering question 3f: immediate removal from the
Evergreen playlist. The score and the archive entry are retained, so re-liking
restores the track at full strength. The cost of an accidental unlike is one
playlist write, not lost history.

---

## 5. Snapshots

Answering question 3h.

- Taken on the last day of the month.
- Stored in-app; exported to a real playlist only on demand, named
  `Evergreen — 2026 August`. Confirmed still the intent.
- A snapshot stores the ranked track list, **the scores, and the parameter
  values in force at the time**. Without the parameters, every snapshot becomes
  unreadable the first time the scoring is retuned.
- No folder organisation — the Web API has none. Naming convention only.

---

## 6. Sync and safety

- **Dry run before the first write.** The first Evergreen sync shows the
  add/remove diff and requires confirmation. Same pattern as the same-song merge.
- **Write diffs, not replacements.** Add and remove specific tracks rather than
  replacing the playlist contents. Cheaper against the unpublished quota and it
  preserves added-at dates.
- **At most one Evergreen write per day**, per the Phase 2 quota constraint.
  `QUOTA_EXCEEDED` backs off for hours.
- **A new consent screen is expected** when `playlist-modify-private` is first
  requested. Not a bug.
- **Verify every playlist endpoint against the live reference** before building
  on it. `POST /users/{id}/playlists` was removed in February 2026.

---

## 7. Notifications

Answering question 4.4.

- **Reconnect required** (refresh token expired or expiring): notify. On by
  default. This is the only silent failure that costs data, since sync pauses
  until it is fixed.
- **Monthly snapshot taken**: build it, default off.
- **Songs joined or left Evergreen**: build it, default off.

---

## 8. Also required in Phase 3

**An explain-the-score view.** Per track: play count, fraction heard per play,
decayed contribution of each event, current score, current rank, and the
threshold. Seven parameters cannot be tuned without seeing the working, and the
existing debug screen is the natural place for it.

---

## 9. Out of scope for Phase 3

- Replay weighting beyond 2d. Revisit once the app has captured enough of its
  own replay data to judge.
- Any use of DJ inference beyond 2g.
- Second service adapters, cross-device sync, real UI. Phases 4 to 6.

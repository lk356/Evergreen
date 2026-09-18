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

> **Answered 2026-09-17: stored at capture, filtered at read.** The capture
> path (listener service → session watcher → segment tracker → segment writer
> → DAO) never inspects the URI or the duration; commentary lands as an
> ordinary segment row. The only filtering is in two read-time queries in
> `SpotifyDao` (the ISRC backfill queue and the coverage count), both pinned
> by instrumented tests, one of which asserts the commentary row is still
> stored. The "flag" is the URI scheme itself: `spotify:media:` on the service
> track row, with zero duration and zero listened time. No capture change
> was needed, and section 2g stands.

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

Calibration 0b was run on 2026-09-17. Results are in 3.1 below; the table
shows the values now in force. Fallbacks are kept for reference only.

| Parameter | Source | Value | Fallback |
|---|---|---|---|
| Decay half-life | Calibrated (0b) | **45 days** | 90 days |
| Fraction-heard lower breakpoint | Calibrated (0b) | **0.20** | 0.20 |
| Fraction-heard upper breakpoint | Calibrated (0b) | **0.85** | 0.70 |
| Negative weight below lower breakpoint | Chosen | −0.5 play | — |
| Like event weight | Chosen | 3 plays | — |
| Replay jump threshold | Chosen | 10 s | — |
| Per-track daily contribution cap | Chosen | 2 plays | — |

### 3.1 Calibration results (0b)

**What was analysed.** The Spotify extended streaming history export: 77,369
audio rows with a track URI, 2020-01-24 to 2026-09-15 (2,427 days), covering
12,710 distinct tracks. Liked tracks were taken from the app's database as
pulled from the Pixel the same day: 380 currently liked, of which 376 appear in
the export by URI, contributing 16,745 rows and about 498 hours of listening.
Durations came from the synced `service_tracks` rows (383 with a duration), as
the export has none. A throwaway standard-library Python script ran locally;
only the aggregates below left the machine. It is not app code and is not in
the repository.

**Half-life: 45 days.** The return curve for liked tracks — given N days
without a play, how often was the track played again in the next 90 days —
reads cleanly and gives the same answer under three definitions of "a play":

| Gap without a play | any row | rows ≥ 30 s | rows ≥ 30 s, tracks weighted equally |
|---|---|---|---|
| 1 day | 0.96 | 0.94 | 0.92 |
| 7 days | 0.91 | 0.90 | 0.87 |
| 14 days | 0.85 | 0.85 | 0.82 |
| 30 days | 0.64 | 0.69 | 0.66 |
| 45 days | 0.43 | 0.50 | 0.46 |
| 60 days | 0.31 | 0.37 | 0.32 |
| 90 days | 0.18 | 0.18 | 0.16 |
| 120 days | 0.12 | 0.11 | 0.09 |
| crosses 0.50 at | 41 days | 45 days | 42 days |
| halves from its day-1 value at | 42 days | 48 days | 45 days |

The two readings the questions document allowed for — the absolute 50% point
and the point where the odds have halved relative to day 1 — agree to within
a few days because the curve starts near 1.0. The middle column (plays of at
least 30 seconds) is the one adopted, since sub-30-second fragments are not
plays the scorer will credit either. About 700 observations sit at the 45-day
mark, so the crossing is well supported.

Two things worth knowing about the shape:

- The curve is not exponential. It stays above 0.85 for two weeks, then falls
  roughly linearly to about 0.18 at 90 days. Exponential decay with a 45-day
  half-life is therefore a little harsh in the first two weeks and a little
  generous after three months. That is acceptable for a first setting and is a
  parameter, not a migration, to revisit.
- Beyond 180 days the curve rises again (0.5 to 0.7 at 240 to 300 days). This
  is a selection effect, not a real pattern: the liked set is what is liked
  *now*, so tracks that were dropped for a year and later rediscovered are
  over-represented, and by that gap length only a handful of tracks remain in
  the sample. It says nothing about the half-life and was ignored.
- For comparison, the same curve over *all* tracks in the export, not just
  liked ones, crosses 0.50 at 23 days. Liked tracks come back about twice as
  readily as the library at large, which is reassuring about what a like means.

**Fraction-heard breakpoints: 0.20 and 0.85.** Plays of tracks with a synced
duration were split by `reason_end` into completed (`trackdone`, 8,347 rows)
and cut short (`fwdbtn`, `endplay`, `backbtn`; 7,686 rows). The `skipped` flag,
which the questions document said not to trust, in fact agreed with the codes
99% of the time in this export, so it was used only as a cross-check.

| fraction heard | completed | cut short | cut-short share | cut-short cumulative |
|---|---|---|---|---|
| 0.00–0.05 | 19 | 5,006 | 1.00 | 0.65 |
| 0.05–0.10 | 11 | 563 | 0.98 | 0.72 |
| 0.10–0.15 | 14 | 323 | 0.96 | 0.77 |
| 0.15–0.20 | 19 | 205 | 0.92 | 0.79 |
| 0.20–0.25 | 10 | 186 | 0.95 | 0.82 |
| 0.25–0.30 | 20 | 128 | 0.86 | 0.83 |
| 0.30–0.75 | ~12 per bin | ~95 per bin, flat | ~0.90 | 0.85 → 0.94 |
| 0.75–0.80 | 25 | 70 | 0.74 | 0.95 |
| 0.80–0.85 | 55 | 75 | 0.58 | 0.96 |
| 0.85–0.90 | 102 | 74 | 0.42 | 0.97 |
| 0.90–0.95 | 162 | 70 | 0.30 | 0.98 |
| 0.95–1.00 | 409 | 87 | 0.18 | 0.99 |
| 1.00 | 7,397 | 60 | 0.01 | 1.00 |

*Lower breakpoint, 0.20.* The cut-short distribution is a spike at zero with a
long flat tail: 65% of cut-short plays heard under 5% of the track, 79% under
20%, and from 25% onwards the count per bin settles at a flat 90 to 130. The
elbow between the spike and the plateau lies between 0.15 and 0.25. This
reading is clear, and it matches the fallback.

*Upper breakpoint, 0.85.* Completed plays outnumber cut-short plays from the
0.85–0.90 bin, and at 2% resolution the crossing is at 0.86. This reading is
**softer** than the lower one, and the honest description is that it is driven
by where completed plays *begin* (95% of them heard 91% or more) rather than
by a change in listener behaviour: cut-short plays are spread evenly from 0.25
to 0.95. Two sensitivities: counting only the next button (`fwdbtn`) against
completed plays moves the crossing down to 0.80, and a wider sample of all
tracks with durations estimated from their own completed plays gives 0.75 to
0.80 but is noisier. So 0.85 is the value under the definition the questions
document gave, with 0.80 defensible. Either way the scorer's curve should ramp
across each breakpoint rather than step at it, so a play at 84% and one at 86%
do not score wildly differently.

**What the export could not inform.** Replay behaviour (a replay is two rows,
indistinguishable from two plays), the like event weight, the daily cap and
the negative weight. Those remain chosen values.

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

# Scoring freshness prompt — paste this into Claude Code

Read `CLAUDE.md` first, then `docs/PLAN.md` (the Status section, the Phase 3
results, and "Open questions — still open", especially "How volatile should a
score be?"), then `docs/PHASE3_DECISIONS.md` and `docs/PHASE3_PLAN.md`, then
every file in `app/src/main/java/com/example/evergreen/scoring/`.

We're doing a **scoring change, before Phase 5c**. Work on a new git branch
called `scoring-freshness`, branched from `main`. One commit per step, with
that step's tests passing, in the style of the existing history.

> **Amended 2026-11-17, at the start of the work:** branched from
> `phase5c-merge`, not `main`. Both devices already run the 5c build
> (database schema v8), which a `main` build (schema v7) cannot open, and 5c
> changed the scorer itself. See "Scoring freshness" in `docs/PLAN.md`.

I'm new to development. Explain what you are doing and why in plain language,
briefly, and define jargon the first time it appears. End each step with one
or two sentences on what changed that I could actually notice. When something
has to be done on a device, give me click-by-click instructions.

## Why this exists

I've used the app as-is for about two months. The playlist is mostly good and
has plenty of recent favourites, so this is tuning, not an overhaul. The
scores are not badly inflated either (rank 1 is 11.7, rank 10 is 7, rank 20
is 5.8, rank 50 is 4). What I do see:

- A few songs have been sticking around after I stopped caring about them. A
  song that built up a big score has a long way to fall.
- A skip costs a flat half play, so a song at 12 barely notices being skipped.
- I use Spotify DJ a lot, and skips during DJ currently cost nothing at all,
  so a lot of my real skips are invisible.
- Flicking quickly past several songs (going back ten songs to find one, or
  skipping through a genre block in DJ) is browsing, but outside DJ each of
  those flicks is scored as a full rejection today.

This reopens "How volatile should a score be?" in `docs/PLAN.md`, for a
different reason than the one written there: the problem is not a loved song
falling out, it is stale songs not falling out.

## What is already built — don't rebuild or regress it

- **A score is computed from the stored facts, never accumulated** (decision
  1a). That stays true. The new formula walks a track's history in time order,
  but it is still a pure function of (segments, like events, parameters, now).
- **`capture/` stays untouched.** If anything here seems to need a change in
  `capture/`, stop and show me first.
- **No schema change is expected.** Parameters live as JSON in `sync_state`.
  If you think a migration is needed, stop and explain why before writing one.
- **Mirror mode guards, sync, batch import/export:** untouched.
- `RecomputeTest`, `ScoreCacheTest` and `CrossDeviceRankingTest` must keep
  passing, or be updated with a stated reason (see step 2).

## The one rule that protects old data

**A parameter key that is missing from stored JSON must mean "this feature is
off", forever.** Every snapshot and every `parameter_history` row stores the
parameters it was scored with, and none of them have the new keys. If a
missing key fell back to a new non-off default, every old snapshot would
silently be re-read under a formula it was never scored with.

So: the in-code defaults for every new parameter are the **off values** listed
below, and with all of them off the scorer must produce **exactly** today's
scores. The values I actually want are applied as an ordinary settings change
on the device, which `parameter_history` already records. Write a test that
pins "all new parameters off = identical scores to the current formula" over
a varied fixture, and write it **before** changing the scorer.

Check how the parameter hash and the canonical JSON treat the new keys, and
tell me if adding keys changes the hash of parameters that have not otherwise
changed. If it does, say what that means for the score cache and for old
`parameter_history` rows before going further.

## The four changes

All four are new parameters in `ScoringParameters`, editable on the scoring
settings screen with a plain-language description each, like the existing
ones.

### A. Saturating gains — parameter `scoreCeiling` (off = 0)

The higher a track's score already is, the less a new play adds. Walk the
track's events oldest to newest, keeping a running score that decays between
events with the existing half-life. For an event with a positive value `v`
(a listen after the daily cap, or a like):

    gain = v × clamp(1 − running / scoreCeiling, 0, 1)

Negative events subtract in full. The running score may go below zero; the
clamp stops that from making a play worth more than `v`.

The final score is still the sum of each contribution's adjusted value times
its decay to now, so `ScoreBreakdown` keeps its shape. Each contribution
should carry both its raw value and its adjusted value, and a note saying it
was reduced, so the track screen can show why.

Events at the same timestamp need a deterministic order that does **not**
depend on local row ids, which differ between devices. `CrossDeviceRankingTest`
exists to catch exactly this.

### B. Browsing runs — `rapidRunMinSkips` (off = 0), `rapidRunMaxListenMs`

A run of `rapidRunMinSkips` or more listens in a row, each with
`listenedMs ≤ rapidRunMaxListenMs`, is browsing. Every listen in the run
contributes 0 instead of a negative, with its own note.

This needs the whole listening timeline, not one track's: the songs I flick
past are often not liked and so are not scored. Work it out once per
recompute as a list of browsing windows and pass it into the scorer, the same
way `DjWindow`s are. Before building, **tell me what the ranker currently
loads** (liked tracks only, or everything in the load window) and what the
extra pass costs on the full database.

"In a row" means consecutive on one device's timeline with no longer listen
between them. Propose a rule for how big a time gap breaks a run and for how
imported history rows (`spotify-history-import`) are treated, and tell me the
rule before coding it.

### C. DJ skips count — `djSkipWeight` (default 0 = today's behaviour)

Today a negative inside a DJ window is set to 0. Change that to
`negative × djSkipWeight`. Keep the DJ window inference and the commentary
handling exactly as they are. Browsing runs (B) apply inside DJ sessions too,
and are applied first.

### D. Skip streaks — `skipStreakGrowth` (off = 1.0), `skipStreakMaxMultiplier`, `dailyCapSkips` (off = 0, meaning no cap)

Per track, classify each local calendar day after B and C are applied:

- **listen day**: at least one positive contribution. Resets the streak to 0.
- **skip day**: at least one negative and no positive. Streak goes up by 1.
- otherwise the streak is unchanged.

On a skip day, negatives are multiplied by
`min(skipStreakGrowth ^ (streak − 1), skipStreakMaxMultiplier)`.

The streak counts **days, not skips**, so one messy evening or one recording
glitch moves it by at most one step. For the same reason add `dailyCapSkips`:
the most a track can lose in one day, in skip-equivalents, before the streak
multiplier. This is lever 2 from the volatility question in `docs/PLAN.md`.
The existing comment on `applyDailyCap` argues against capping negatives;
update it to say why that changed (the streak now carries "repeated
rejection", across days, which is a cleaner signal than repeats in one
sitting).

Order of operations for one track: fold replays → fraction-heard curve →
browsing runs (B) → DJ weight (C) → daily caps → skip streak (D) → saturating
walk with decay (A) → sum.

## Steps

**Step 0 — docs.** Update `docs/PLAN.md`: reopen the volatility question with
the reason above; record that this scoring work now comes before Phase 5c;
record, as an option and not a decision, that 5c's hand-moved two-way merge
might be replaced by my always-on home server acting as the permanent writer,
with both phones uploading fact batches to it. Save this prompt as
`docs/SCORING_FRESHNESS_PROMPT.md`. Then show me a short plan for steps 1–5
and wait for my OK.

**Step 1 — pin today's behaviour.** The "all off = identical" test described
above, passing against the current code.

**Step 2 — build A to D**, one commit each, unit tests for each. Include
tests for: a song that is binged then abandoned; skip, skip, full listen,
skip (the streak resets); a ten-song flick-back; a genre block skipped inside
a DJ session; a lone DJ skip after 20 seconds; same-timestamp events arriving
in different orders. The load window is ten half-lives and `RecomputeTest`
pins that truncating there moves no score by more than 0.001. The saturating
walk makes old events influence later gains, so re-measure that bound with
the new parameters on and tell me the number rather than assuming it holds.

**Step 3 — settings and track screen.** The new settings on the scoring
settings screen. On the track detail screen, show the new notes (browsing,
DJ skip, streak ×N, reduced by ceiling) wherever contributions are listed.
Check that score-over-time still works and how long it takes.

**Step 4 — comparison on my real data. No guessing.** I will put a backup of
the Walkman's database on this computer (tell me exactly where; it must be
somewhere gitignored and **must never be committed**). Write a throwaway tool
that runs the real scoring code over it, in whatever way is least work (the
scoring package is plain Kotlin). Propose the approach first if it needs a new
dependency. Produce one readable report comparing today's settings against
three or four candidate sets. Start from:

| Setting | Candidate start |
|---|---|
| `scoreCeiling` | 8 (also try 6 and 10) |
| `skipStreakGrowth` / max | 2.0 / 4.0 |
| `dailyCapSkips` | 1.0 |
| `rapidRunMinSkips` / `rapidRunMaxListenMs` | 3 / 5000 |
| `djSkipWeight` | 0.5 (also try 1.0) |
| `halfLifeDays` | 35 (also keep 45) |

For each candidate the report should show, with song titles and artists:

- which songs join and leave the top 100 compared with today
- the top 20 with old and new scores
- ranks 101–200, so I can judge whether a bigger playlist would be good
- every song that reached a streak of 3 or more, with the dates
- how many listens were reclassified as browsing, and the ten largest runs
- how many DJ skips now count
- for the current top 10: how many days of silence until each would leave
- for the songs that have been in the playlist longest: when each was last
  played in full, and how often it has been skipped since, so I can see
  whether they stick because of a banked score or despite being skipped

One more section, separate from the candidates: **songs I keep playing but
have not liked.** I mostly let DJ autoplay in the background, so I rarely
like anything, and only liked songs can enter Evergreen. List the unliked
songs with the most full listens in the last 90 days. This is a report
section only; do not build an app feature for it.

Then stop. I choose the values by reading the report. If the report shows a
change makes little difference on my data (the score ceiling is the likeliest
one), say so plainly; leaving it off is a fine outcome.

**Step 5 — verify and hand over.** Run the unit tests. Run the instrumented
tests **on the Pixel only, with the Walkman unplugged** (the serial-pinning
rules from Phase 5b still apply; never run instrumented tests on the
Walkman). Measure a full recompute against the 2,309 ms Walkman baseline and
tell me the new figure. Write a device checklist for me: back up first,
install on the Walkman, enter the chosen values in settings, check the
joined/left notification is suppressed for a settings change, check a track
screen. Then the same for the Pixel, since a mirror on the old build would
ignore the new settings and show a different ranking. Update the Status
section of `docs/PLAN.md` and add a results section.

## Things to stop and ask me about

- any schema change, any new dependency, any change in `capture/`
- the gap rule and the imported-rows rule for browsing runs
- if "all off = identical" cannot be made exact
- if the recompute gets noticeably slower on the full database
- anything in this prompt that contradicts the code or `docs/PLAN.md`; the
  code and the recorded decisions win, and I'd rather hear about it than have
  it quietly reconciled

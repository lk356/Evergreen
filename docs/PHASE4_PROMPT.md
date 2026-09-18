# Phase 4 prompt — paste this into Claude Code

Read `CLAUDE.md` first, then `docs/PLAN.md` — the Status section, the **Phase
3 results**, the **Phase 3 device verification**, and everything under **Open
questions — still open**, which is where this phase's real questions live.

We're starting Phase 4: usability. Phase 3 made the app *work*; this phase
makes it something I'd want to open. Work on a new git branch called
`phase4-usability`, branched from `main`.

I'm new to development. Explain what you are doing and why in plain language,
briefly, and define jargon the first time it appears. When something has to be
done on the phone or in a web dashboard, give me click-by-click instructions.

## The honest state of things

The app works end to end on the Walkman: six years of listening imported, 380
tracks scored, the Evergreen playlist written daily, snapshots taken monthly.
The scores are good — my own reading after using it is that they match my
recent listening well.

What it does not have is a **user interface**. Everything is a debug screen
with four tabs, dense rows of `Stat("label", "value")`, and a JSON text box
for the scoring parameters. That was right for Phase 3, where I needed to see
the working. It is wrong for something I use daily on a Walkman.

## What is already built — don't rebuild or regress it

- **Capture is finished and untouched since Phase 1.** The `capture/` package
  was byte-for-byte unchanged through the whole of Phase 3. Keep it that way
  unless something is genuinely broken; if a Phase 4 change has to touch it,
  say so explicitly and show me the diff.
- **Scoring is settled and calibrated.** `TrackScorer`, `Ranker`,
  `ScoringCurve`, `Decay`, `Membership` and `DjSessions` are pure Kotlin with
  219 unit tests. **A score is computed, never accumulated** (invariant 1a) —
  `RecomputeTest` and `ScoreCacheTest` pin it, and both must keep passing.
- **The database is at schema v5** with migration tests over populated
  databases at every step. Schema changes still need my approval first.
- **Spotify writes are behind guards**: one device, one write a day, a dry run
  and a one-time confirmation, and the app never touches a playlist it did not
  create. Do not loosen any of those for convenience.
- **The backup button exists** (`DatabaseBackup`, SQLite `VACUUM INTO`). The
  "data export/backup" line in the Phase 4 plan is therefore half done — what
  is missing is *exporting readable data*, not backing up the database.
- **The scoring parameters are editable**, but only as raw JSON behind a
  dialog. Phase 4 owns the real settings screen.

## Decide these with me before building — they are taste, not engineering

Phase 3's decisions were mostly answerable from data. These are not, and I
should answer them rather than have them assumed. **Ask me, propose options,
and wait** before building the UI:

1. **What is the app's home screen?** Not the debug screen. Candidates: the
   Evergreen playlist as it stands; the ranked list; "what changed recently";
   something else. This decides everything else.
2. **What do I actually want to do day to day?** My honest answer may be
   "nothing, it should just run" — in which case the app is mostly a place to
   check on it, and that is a legitimate answer that changes the design.
3. **Does the debug screen survive?** I would keep it, behind something, since
   it is how anything gets diagnosed. But it should not be what opens.
4. **What does snapshot search look like?** The brief says I should be able to
   ask for "2026 August". Is that a search box, a list of months, a calendar?

## Build — but confirm the shape first

Roughly in this order, smallest and most annoying first:

1. **The notification-access banner.** It eats about 100 dp of the NW-A307's
   602 dp on every tab and says nothing once access is granted. Collapse it to
   a single line, or hide it entirely when granted with the full card only on
   the debug screen. This is a five-minute fix I deferred twice; do it first.
2. **The real settings screen**, replacing the JSON danger zone. One control
   per parameter with its meaning in plain words — "a skip counts as −0.5 of a
   play" rather than `negativeWeight`. Keep the JSON editor somewhere for
   emergencies. Changing a parameter must still trigger a recompute and never
   a migration (invariant 1d).
3. **Snapshot search**, per whatever we agree in question 4. The data shape is
   ready: `snapshots.monthKey` is `2026-08` and sortable, `label` is
   `2026 August`, and every entry carries its own title, artist and URI.
4. **The like widget.** A home-screen widget to like the currently playing
   track. Note the constraint: **this app has never had permission to modify
   likes** — it only ever reads them. This needs the `user-library-modify`
   scope, which is a new permission over my account and a deliberate decision,
   so propose it and wait. Check whether `PUT /me/library` is still the write
   endpoint; the February 2026 consolidation moved the writes there.
5. **Readable data export.** The database backup is not something I can read.
   CSV or JSON of the listening history and the rankings, through the same
   create-document picker the backup uses.
6. **Score volatility** — see below.

## The two questions Phase 3 left open

Both are written up with analysis under "Open questions — still open" in
`docs/PLAN.md`. Read that before proposing anything; the short version:

**Volatility.** A single skip drags a score down more than it should when I
was simply not in the mood. The specific asymmetry: the daily cap limits one
track to +2.0 a day, but negatives are uncapped, so four skips in an evening
is −2.0 — more than a day of listening can win back. Three levers are listed
in `PLAN.md`, in increasing order of change. **Start with the cheapest thing
that tells us the truth**: a score-over-time view, so I can see whether the
volatility is real or just noticeable. Do not retune the scoring on a hunch —
the parameters were calibrated against 77,369 real plays and the Scores tab
exists precisely so changes can be judged rather than guessed.

**Snapshot semantics.** Whether a monthly snapshot should describe the last
day of the month or the month as a whole. **This is not urgent and you should
tell me so if I push to decide it**: past rankings are recomputable, because
segments and `like_events` are append-only and `evergreen_membership` records
every join and leave with a timestamp. "Everything that was in Evergreen at
any point in August" is already a query over existing data.

The one real gap is that **parameter changes are not recorded with
timestamps** — only the current value lives in `sync_state`, and past values
survive only inside snapshots. A small append-only parameter history would
close it and make any past date fully reconstructible. It is cheap, and worth
doing *before* the settings screen makes tuning easy, or the record of what
the scores meant will have holes in it.

## Constraints that do not relax

- Every privacy rule in `CLAUDE.md` still holds. No analytics, no crash
  reporting, no third-party SDKs that phone home. Network access only to
  `accounts.spotify.com` and `api.spotify.com`, through the existing host
  allowlist.
- **Never print, log or show a row of the export or of the database** in
  messages to me. Counts and aggregates only.
- **No new third-party dependencies** without asking. Phase 3 added none.
- **Instrumented tests destroy the app's database.** The build now refuses to
  run them while a Walkman is attached; do not override that flag. Use the
  Pixel, and say which device before you run them.
- **The Walkman is the writer device and my daily driver.** Its screen is
  339 × 602 dp — about 60% of the Pixel's usable height. Check every screen
  there, not only on the Pixel. A layout that looks generous on the Pixel can
  leave nothing at all on the Walkman; that happened twice in Phase 3.
- `local.properties` is gitignored, so **a build made from a git worktree has
  no Spotify client ID** and will report "Not configured". Copy the file in or
  build from the main checkout.

## Still owed from Phase 3

Step G of the verification: leaving the app alone for a day and checking the
scheduled Evergreen write fired on its own. Not a blocker, but if it has not
happened by the time this phase starts, ask me.

## When done

Update `docs/PLAN.md` — Status, the Phase 4 checkbox, what was learned, what
turned out not to be observable — and commit. Give me click-by-click
instructions for anything I need to do on the device. Merge `phase4-usability`
into `main` only when I say the device verification passed.

# Phase 4 prompt — paste this into Claude Code

Read `CLAUDE.md` first, then `docs/PLAN.md` — the Status section, the **Phase
3 results**, the **Phase 3 device verification**, and everything under **Open
questions — still open**.

We're starting Phase 4: usability. Phase 3 made the app *work*; this phase
makes it something I'd want to open. Work on a new git branch called
`phase4-usability`, branched from `main`.

I'm new to development. Explain what you are doing and why in plain language,
briefly, and define jargon the first time it appears. When something has to be
done on the phone or in a web dashboard, give me click-by-click instructions.

> **This replaces the earlier version of this file.** The four design
> questions it asked me to answer before building are now answered — see
> "Decisions already made" below. Do not re-ask them. The build order has
> changed, and four requirements have been added: parameter history, the
> cache-discipline rule, notification batching, and a recompute baseline.

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
- **Hysteresis already exists**: a track joins the playlist at rank 100 and
  does not leave until it falls below 130. This is what stops boundary churn,
  and it matters to the notification work below.
- **The backup button exists** (`DatabaseBackup`, SQLite `VACUUM INTO`). What
  is missing is *exporting readable data*, not backing up the database.
- **The scoring parameters are editable**, but only as raw JSON behind a
  dialog. Phase 4 owns the real settings screen.

## Decisions already made — build to these, don't re-ask

Decided in the planning Project on 2026-09-18. The reasoning is recorded so
you can tell when a decision doesn't survive contact with the code; if one
doesn't, say so and stop rather than working around it.

**1. The home screen is a quiet status line with "what changed" underneath.**

The reasoning that settles it: **this app never plays music.** Spotify does.
So a browsable copy of the Evergreen playlist is a worse version of something
one tap away, on a 339 dp screen, with no play button. What's left is what
only this app knows — whether it ran, and what moved.

- **Status line at the top.** Last capture, last sync, connection health,
  snapshot due. Quiet when everything is fine; loud only when something is
  broken. It should not occupy a screen of its own — when all is well it has
  nothing to say, whereas a changes list with last night's movement on it *is*
  the proof the pipeline ran.
- **Changes below it.** Joined, left, and **near the line** — tracks within a
  few ranks of 100 in either direction. That third group is where the next
  change is coming from and the only place watching the scoring day to day
  tells me anything.

**2. There are two phases of use, and the app should be built for the second.**

Right now I need rankings, scores and changes in front of me so I can satisfy
myself the algorithm reflects my listening. **That job ends.** After it, I want
the app to run in the background, give me the playlist, and be an archive I can
check. Build the front door for the steady state; keep the validation surfaces
one tap away rather than on the home screen. "What changed" serves both, which
is why it wins — it is the diagnostic now and the interesting thing later.

**3. The debug screen survives, behind something, and is not what opens.**
It is how anything gets diagnosed. Don't delete it, don't gut it.

**4. Snapshot search is three features, and only one of them is a search.**

- **Browsing a month.** No search box. Twelve snapshots a year, sixty after
  five years — that is a list grouped by year, which beats a text field and a
  keyboard on a 339 dp screen. The design question is what a month *shows*:
  380 entries is a lot to scroll, so default to the hundred that were in the
  playlist (`inPlaylist`) with the full record behind a toggle, and lead with
  the **diff against the previous snapshot** — new this month, gone since last
  month, and what sat at the top. Consecutive snapshots subtract; the data is
  already there.
- **Following one song through time.** A row of months with the track's rank
  in each, on the **track detail screen** next to the score breakdown — not on
  a snapshot screen. Build it while you are in that code.
- **Text search across all snapshots.** "Did I ever have anything by this
  artist in Evergreen?" Every entry carries its own title and artist, copied
  in, so tracks that have since vanished from Spotify still appear. Real, but
  **not now** — it means little until there are enough months for it to
  return anything.

**5. Joined/left notifications flip to default on — with two safeguards.**

Previously default off. The point is not having to open the app: the changes
come to me, and I open it when something looks wrong. Hysteresis and the
once-a-day write guard already stop the obvious spam. What is not handled:

- **One notification per sync, summarising** — never one per track. A sync can
  produce one change or forty.
- **Suppress it entirely when the recompute was triggered by a parameter
  change** rather than by a day passing. Forty joins and leaves after I nudge
  the skip weight is not news about my listening, and the settings screen is
  about to make parameter changes easy. Build this *with* the settings screen,
  not after it.

**6. Add a notification for capture having gone silent.** New requirement, and
the most valuable one here.

Alert me when no segments have been captured for N days — a week is a sensible
starting value, generous enough that a quiet stretch does not nag. Default on.

The reasoning: a paused sync catches up when I fix it, but plays that were
never recorded are gone. Capture also rests on the least certain ground in the
app, an undocumented Spotify broadcast setting and media-session behaviour
that was reverse-engineered on a device. If Spotify changes the broadcast, or
an OS update drops the notification-listener permission after a reboot, the app
keeps running and silently records nothing. The whole point of this phase is
that I should not have to open the app to know it is working, and a status line
only helps someone who looks at it.

## Build — in this order

Deliberately reordered from the earlier draft. Reasoning for the two moves is
given inline; if either is wrong once you are in the code, say so.

1. **The notification-access banner.** It eats about 100 dp of the NW-A307's
   602 dp on every tab and says nothing once access is granted. Collapse it to
   a single line, or hide it entirely when granted with the full card only on
   the debug screen. Five-minute fix, deferred twice, do it first.

2. **Parameter history.** A small append-only log of parameter changes with
   timestamps. Currently only the current value lives in `sync_state` and past
   values survive only inside snapshots. **This has to land before the settings
   screen**, or the first easy retune puts holes in the record of what the
   scores meant. It is cheap now and awkward later.

3. **The navigation shell and the home screen**, per decision 1. Before the
   individual screens, so they hang off a structure rather than being rewired
   into one afterwards. The debug screen moves behind something here.

4. **The real settings screen**, replacing the JSON danger zone. One control
   per parameter with its meaning in plain words — "a skip counts as −0.5 of a
   play" rather than `negativeWeight`. Keep the JSON editor somewhere for
   emergencies. Changing a parameter must still trigger a recompute and never
   a migration (invariant 1d). Build the parameter-change notification
   suppression (decision 5) here.

5. **Score over time**, on the Scores tab. **Moved up from last place.** My
   current job is satisfying myself the algorithm reflects my listening, and a
   score-over-time line is the best tool for it — everything else in this
   phase is polish by comparison. It is also what settles the volatility
   question below, by showing whether the volatility is real or merely
   noticeable.

6. **Snapshots**, per decision 4: the month list, the month view with its
   diff, and the per-track month history on the track detail screen.

7. **The notification changes**, per decision 5: batching, joined/left on by
   default, and the capture-gone-silent alarm from decision 6.

8. **Readable data export.** CSV or JSON of the listening history and the
   rankings, through the same create-document picker the backup uses.

**The like widget is cut.** It was on the earlier plan; I have dropped it.
Spotify is the primary player and its own widget already has a like button, so
the feature would duplicate something I already have while costing a new
permission. The consequence is deliberate and should be preserved: **this app
stays read-only over my Liked Songs.** It never requests `user-library-modify`,
and its only writes remain to playlists it created itself. Do not propose the
widget, and do not add the scope for anything else without asking.

## The two questions Phase 3 left open

Both are written up with analysis under "Open questions — still open" in
`docs/PLAN.md`. Read that before proposing anything.

**Volatility.** A single skip drags a score down more than it should when I
was simply not in the mood. The specific asymmetry: the daily cap limits one
track to +2.0 a day, but negatives are uncapped, so four skips in an evening
is −2.0 — more than a day of listening can win back. Three levers are listed
in `PLAN.md`, in increasing order of change. **Do not retune on a hunch.** The
parameters were calibrated against 77,369 real plays. Build step 5 first, let
me look at it, and then we decide.

**Snapshot semantics.** Whether a monthly snapshot should describe the last
day of the month or the month as a whole. **Not urgent, and tell me so if I
push to decide it**: past rankings are recomputable, because segments and
`like_events` are append-only and `evergreen_membership` records every join
and leave with a timestamp. "Everything that was in Evergreen at any point in
August" is already a query over existing data.

## Constraints that do not relax

- Every privacy rule in `CLAUDE.md` still holds. No analytics, no crash
  reporting, no third-party SDKs that phone home. Network access only to
  `accounts.spotify.com` and `api.spotify.com`, through the existing host
  allowlist.
- **Never print, log or show a row of the export or of the database** in
  messages to me. Counts and aggregates only.
- **No new third-party dependencies** without asking. Phase 3 added none.
- **Any new derived table must be droppable and rebuildable**, exactly like
  `scores` with its parameters hash. A UI phase is the likeliest moment for
  someone to add a table so a screen loads faster, and that is how a cache
  quietly becomes truth. Computed-never-accumulated is also what will let two
  devices ever reconcile in Phase 6 — accumulated scores cannot be merged,
  because you cannot tell which device already counted a play.
- **Instrumented tests destroy the app's database.** The build refuses to run
  them while a Walkman is attached; do not override that flag. Use the Pixel,
  and say which device before you run them.
- **The Walkman is the writer device and my daily driver.** Its screen is
  339 × 602 dp — about 60% of the Pixel's usable height. Check every screen
  there, not only on the Pixel. A layout that looks generous on the Pixel can
  leave nothing at all on the Walkman; that happened twice in Phase 3.
  The long-term split is Walkman captures and writes, phone browses — but that
  is Phase 6, and until then the Walkman has to do everything.
- **Record the recompute time on the Walkman** the next time a full recompute
  runs, and put the number in `PLAN.md`. The segment table is the only thing
  in the app that grows without bound. The 450-day load window should keep it
  bounded in practice, but a baseline now is the difference between noticing a
  slowdown later and guessing at one.
- `local.properties` is gitignored, so **a build made from a git worktree has
  no Spotify client ID** and will report "Not configured". Copy the file in or
  build from the main checkout.

## Still owed from Phase 3

Step G of the device verification: leaving the app alone for a day and
checking the scheduled Evergreen write fired on its own. **A to F passed on
2026-09-18, so G has not had a day yet** — it is pending rather than failed.
Not a blocker for any of the work above, but the daily write firing unattended
is what makes this a living playlist rather than a manual tool, so ask me for
the result early rather than at the end.

## One observation to record, not to build on

Not Phase 4 work. Add it to "Still unknown" in `docs/PLAN.md` so it is waiting
when Phase 6 starts.

**Does a Spotify Connect remote device capture phantom segments?** If I play on
the Walkman with Spotify open on the Pixel, does the Pixel's capture record
segments for a track it is not actually playing? Today this is harmless — each
device has its own database and only the Walkman's is used — but it decides how
hard cross-device merge is. Worth ten minutes on the devices if it comes up
naturally; do not build anything around it yet.

If it does happen, two things are already true and worth writing down with it:
`MediaController.PlaybackInfo` may distinguish remote from local playback,
which would let capture drop phantom segments at the source rather than
deduplicate them later; and segments overlapping in wall-clock time are by
definition at most one real listen, since one person cannot listen to two
things at once. Segments have no device column, but every existing row came
from one device and can be stamped in bulk when that column is eventually
added — so no provenance is being lost by waiting.

## When done

Update `docs/PLAN.md` — Status, the Phase 4 checkbox, what was learned, what
turned out not to be observable — and commit. Give me click-by-click
instructions for anything I need to do on the device. Merge `phase4-usability`
into `main` only when I say the device verification passed.

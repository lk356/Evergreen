# Phase 3 prompt — paste this into Claude Code

Read `CLAUDE.md` first, then `docs/PLAN.md` (the Status section and the Phase 2
results), then `docs/PHASE3_DECISIONS.md`, then `docs/PHASE3_PLAN.md`. Do not
read `docs/PHASE3_QUESTIONS.md` except to settle a reference; its questions are
answered by the decisions document, and where they differ the decisions win.

We're starting Phase 3: scoring, the Evergreen playlist, snapshots. The plan is
written, the parameters are calibrated, the endpoints are verified, and the
decisions are made. Your job is to build it, one step at a time, in the order
`docs/PHASE3_PLAN.md` section 11 gives.

Work on a new git branch called `phase3-scoring`, branched from `main` after
`phase3-planning` has been merged into it. One commit per step, with that
step's tests passing, in the style of the existing history.

I'm new to development. Explain what you are doing and why in plain language,
briefly, and define jargon the first time it appears. When something has to be
done on the phone or in a web dashboard, give me click-by-click instructions.

## What is already decided — don't re-derive or re-open

- **Every question in Part 3 and Part 4 of the questions document** is
  answered in `docs/PHASE3_DECISIONS.md`. Sections 1 to 9 of that document are
  the specification. If you think one of them is wrong, say so in a sentence
  and build it anyway unless I say otherwise.
- **The two blocking checks are done.** 0a: DJ commentary rows are stored at
  capture and filtered at read time; no capture change is needed. 0b: the
  calibration gives a half-life of **45 days** and fraction-heard breakpoints
  of **0.20 and 0.85**; the working is in section 3.1 of the decisions. Use
  those as the defaults. The analysis script was throwaway and is not in the
  repository; do not recreate it.
- **The eight decisions in section 10 of the plan are answered**, and the
  five raised afterwards are adopted. In short: import the whole export; read
  the playlist back before writing; the playlist is app-owned; likes decay
  from Spotify's `added_at`; record floor 0.1; notifications and their
  permission are approved; 0.85 is the upper breakpoint; **schema v4 and v5
  are approved as described in sections 4.1 and 5.1 of the plan.** Only the
  Pixel writes Evergreen; a database backup button comes early; instrumented
  tests never run on the Pixel once history is imported; the import runs on
  the Pixel as soon as step 5 lands; the app never touches a playlist it did
  not create.
- **The playlist endpoints were verified against the live reference on
  2026-09-17** and are listed in section 2 of the plan. They are the `/items`
  endpoints and `POST /me/playlists`; the `/tracks` endpoints and
  `POST /users/{id}/playlists` were removed in February 2026. Re-verify each
  one on the day you build it, the way `SpotifyApi.kt` records a URL and a
  date on every method, and stop and tell me if anything has moved again.
- **Capture is finished and device-verified.** Do not change the capture
  path's behaviour. If a Phase 3 change has to touch it, say so explicitly and
  show me the diff.
- **Segments store facts, not conclusions.** There is no outcome column and
  there must not be one. The new `source` and `sourceEndReason` columns in v4
  are facts about provenance, which is why they are allowed.

## Pre-approved, with limits

`CLAUDE.md` says to propose and wait before any schema change, new dependency
or refactor. For this phase the following are approved in advance, **exactly
as described in the plan**:

- schema v4 (plan section 4.1) and schema v5 (plan section 5.1), both as Room
  auto-migrations generated against the committed `app/schemas/` JSON, each
  with a migration test that builds a populated previous-version database and
  asserts every segment survives;
- the scopes `playlist-modify-private` and `playlist-read-private`, added to
  `SpotifyScopes` with the scope test updated to the exact new set;
- the `POST_NOTIFICATIONS` permission, in step 16 and not before.

If building a step shows that one of these needs to differ from the plan's
description, that is a new question: stop, explain the difference and what
it costs, and wait. **No new third-party dependencies.** Everything in the
plan is buildable with what is already in `app/build.gradle.kts`; if you
believe otherwise, ask.

## Build order and the reasons for it

Follow section 11 of the plan. Three orderings are deliberate; keep them:

1. **The importer (steps 3 to 5) comes before the scorer.** As soon as step 5
   lands, walk me through importing the real export on the Pixel, so that
   every later step can be checked against six years of real listening.
   Step 5b, the backup button, follows immediately, and you tell me to take
   a backup before anything else touches the database.
2. **The explain-the-score tab (step 11) comes before any Spotify write
   (step 12 onwards).** I want to look at real scores, and adjust the
   parameters if they look wrong, before the app can change anything on
   Spotify.
3. **The first playlist write is behind a dry run and a confirmation** on the
   debug screen, the same pattern as the same-song merge. The scheduled
   worker shows the pending diff and writes nothing until I have confirmed
   once.

## Invariants to test, not just honour

- **A score is computed, never accumulated** (decisions 1a). Write the
  recompute test from plan section 9 item 4 before the cache exists, and keep
  it passing after: change a parameter, recompute through the cache path,
  assert equality with a from-scratch computation.
- **Imported rows share the segment shape** (1b). An imported row with null
  position fields must score identically to a captured row with the same
  timestamp, listened time and track. Test it.
- **Imported plays decay by their own timestamps** (1c). A 2019 play
  contributes under 0.001 at the default half-life. Test it.
- **Parameters are settings** (1d). Changing one triggers a cache rebuild and
  never a migration.

## Privacy and safety rules specific to this phase

- **The export contains my IP address in every row.** The parser reads only
  the fields listed in plan section 4.2 and skips every other key by name.
  Nothing else from the file is ever read into a variable, logged, or
  stored. Test that the parser's output type cannot hold anything else.
- **Never print, log or show a row of the export or of the database** in
  your messages to me. Counts and aggregates only.
- **The app writes only to the playlist whose id it stored.** No code path
  deletes a playlist. Discovery by name happens only when no id is stored,
  and only among playlists I own. Snapshot exports create a new playlist and
  never touch it again.
- **At most one Evergreen write per day.** Handle 429 with backoff and
  `Retry-After`; treat `QUOTA_EXCEEDED` as back-off-for-hours. The quota is
  per developer account since July 2026 and is not published.
- **Network access only to `accounts.spotify.com` and `api.spotify.com`**,
  through the existing host allowlist. No new hosts.
- **Running instrumented tests uninstalls the app and destroys its
  database.** Once the history is imported, never run them on the Pixel.
  Use the Walkman or an emulator, and say which before you run them.
- Tokens stay outside the database; the backup button copies the database
  only, so a backup can never contain a credential.

## Things to watch for

- **Unavailable tracks.** Spotify's desktop client shows 380 liked songs and
  the phone shows 374; the difference is tracks unavailable in this region.
  Adding one to a playlist may be refused. A refused URI must not abort the
  run: record it, skip it, carry on. Find out what the add endpoint actually
  does with such a URI and record the answer in `PLAN.md`.
- **The consent screen will change.** Requesting the two playlist scopes
  forces a reconnect and a consent screen that lists more than the scopes
  ask for; that was seen in Phase 2 and is Spotify's copy, not a scope leak.
  Confirm the granted scopes from the token response, as Phase 2 does.
- **`offline_timestamp` in the export** is not used; `ts` is the end of the
  stream in UTC. Note it as a known simplification.
- **Duration for imported rows** comes from the service row if known, else
  0, and the scorer falls back to the service row's duration at scoring
  time. This is what lets a track liked after the import start scoring with
  no re-import. Test it.
- **The NW-A307 is low-powered.** Stream the export with `JsonReader`, batch
  the import, and bound the scorer's working set with the ten-half-life
  window. If a step feels slow on the Pixel it will be unusable on the
  Walkman.
- **AGP and KSP.** `android.disallowKotlinSourceSets=false` is a documented
  workaround in `gradle.properties`; if a build fails to configure with
  "Using kotlin.sourceSets DSL is not allowed", that is the flag being
  withdrawn, not your change.

If something cannot be observed or verified, say so. Never invent data to
fill a gap.

## When done

Give me click-by-click instructions to:

- copy the export to the phone and import it, and read the import summary;
- take a database backup and where the file went;
- read a track's score breakdown on the Scores tab and change a parameter;
- reconnect Spotify to grant the new scopes, and what the consent screen
  will say;
- run the Evergreen dry run, read the diff, confirm the first write, and see
  the playlist in Spotify;
- take a snapshot by hand for this month and export it.

Then update `docs/PLAN.md` — Status, the Phase 3 checkbox, anything learned,
anything that turned out not to be observable — and commit. Merge
`phase3-scoring` into `main` only when I say the device verification passed.

# Phase 3 plan — scoring, the Evergreen playlist, snapshots

Written 2026-09-17, after the two blocking checks in `docs/PHASE3_DECISIONS.md`
were done. This is the implementation plan. It takes the decisions as given
and says how they become code, in steps small enough to build, test and commit
one at a time.

Read `docs/PHASE3_DECISIONS.md` first; section numbers below refer to it.
Where this plan proposes something the decisions left open, it is marked
**Decision needed** and collected in section 10.

---

## 1. What Phase 3 delivers

By the end of the phase the app will:

1. **Score** every liked track from the stored listening history, the like
   events and seven tunable parameters, with no running totals (invariant 1a).
2. **Import** the Spotify extended streaming history into the same segment
   table capture already writes to (invariant 1b), so scores start from six
   years of listening rather than a few weeks.
3. **Write** the top 100 tracks to a private `Evergreen` playlist on Spotify,
   once a day at most, as add/remove diffs, after a dry run the user confirms.
4. **Snapshot** the ranked list, scores and parameters on the last day of each
   month, in-app, exportable to a playlist on demand.
5. **Explain** any track's score on the debug screen: every event, what it
   contributed, how much it has decayed, the current rank and the cutoff.
6. **Notify** when the Spotify connection needs reconnecting.

Out of scope, per section 9: replay weighting beyond a threshold, DJ inference
beyond suppressing penalties, second services, cross-device sync, real UI.

---

## 2. Verified against the live Spotify reference (2026-09-17)

`CLAUDE.md` requires this before any endpoint is built on, and it changed the
plan: **every playlist-items endpoint the older tutorials use was removed in
February 2026.** The paths below are the ones the code will use. Each carries
the reference page it was checked against, the same convention `SpotifyApi.kt`
already follows.

| Purpose | Method and path | Scope | Notes | Reference page |
|---|---|---|---|---|
| Create the playlist | `POST /me/playlists` | `playlist-modify-private` | Body: `name`, `public: false`, `description`. `POST /users/{id}/playlists` is gone. | `reference/create-playlist` |
| List own playlists | `GET /me/playlists` | `playlist-read-private` | To find an existing `Evergreen` after a reinstall. 50 per page. | `reference/get-a-list-of-current-users-playlists` |
| Read playlist contents | `GET /playlists/{id}/items` | `playlist-read-private` | **`/items`, not `/tracks`.** 50 per page, `fields=` supported, each entry has `item.uri`. Only works for playlists the user owns, which is fine. | `reference/get-playlists-items` |
| Add tracks | `POST /playlists/{id}/items` | `playlist-modify-private` | Body `{"uris": [...]}`, max 100 per request, returns `snapshot_id`. | `reference/add-items-to-playlist` |
| Remove tracks | `DELETE /playlists/{id}/items` | `playlist-modify-private` | Body `{"items": [{"uri": ...}]}` — the field is **`items`**, the old `tracks` body is for the removed endpoint. Max 100. | `reference/remove-items-playlist` |

Also confirmed: the July 2026 change log makes development-mode quota
**per developer account**, and `QUOTA_EXCEEDED` still arrives as a 429 with a
`reason` field, which the existing backoff already handles. The playlist object
renamed `tracks` to `items` throughout; nothing in the app parses playlist
objects yet, so nothing needs changing.

**Scopes after Phase 3:** `user-library-read` (existing),
`playlist-modify-private` (write), `playlist-read-private` (read back the
playlist to compute the diff). The second and third trigger the new consent
screen section 6 warns about. See decision 10.2 on whether the read scope is
wanted.

---

## 3. The scoring module and its data flow

**Package:** `com.example.evergreen.scoring`. Pure Kotlin with no Android
types, like `SegmentTracker` and `SegmentGrouping`, so all of it runs as plain
JVM unit tests.

### 3.1 Inputs

For one canonical track, the scorer receives:

- its listening segments (captured and imported alike, invariant 1b), oldest
  first, already joined to the service row so the URI scheme is known;
- its like events (`like_events` rows for every service row of the track);
- the DJ session windows inferred for the whole history (3.3);
- the parameters;
- `now`.

It returns a `ScoreBreakdown`: a list of contributions, each saying which
event it came from, the fraction heard, the raw contribution in
play-equivalents, the decay weight, and the decayed value; plus the total.
The explain view (section 7) shows this list directly, so the scorer and the
explanation can never disagree.

### 3.2 The pipeline, in order

1. **Drop commentary.** Any segment whose service URI does not start with
   `spotify:track:` contributes nothing. (Check 0a: these rows are stored;
   this is where they are excluded.)
2. **Fold replays.** `SegmentGrouping.merge(rows, replayJumpThresholdMs)`, with
   the 10 s threshold from 2d. Imported rows carry no jump fields and are
   never folded. A folded listen sums `listenedMs` across its rows, so a
   replay is worth exactly the listening time it represents.
3. **Fraction heard** = `listenedMs / duration`. Duration is the segment's
   own `trackDurationMs` when it is above zero, else the service row's
   `durationMs`, else the canonical track's. Imported rows for tracks that
   were not yet known when imported get a duration later, when a like sync
   fills the service row, with no re-import. If no duration is known at all
   the listen contributes nothing and the breakdown says so.
4. **The curve** (2a). Fraction → play-equivalents:
   - below the lower breakpoint: the negative weight (−0.5);
   - between the breakpoints: 0;
   - above the upper breakpoint: +1;
   - with a **linear ramp of ±0.05 around each breakpoint** so that 84% and
     86% heard do not score wildly differently. The calibration in section
     3.1 of the decisions found the upper crossing is soft, which is the
     reason for the ramp.
5. **DJ suppression** (2g). If the listen falls inside a DJ session window,
   a negative contribution becomes 0. Positive ones are untouched.
6. **Daily cap** (2e). Group listens by local calendar day; the positive sum
   for a track on one day is capped at 2.0. Negatives are not capped.
7. **Decay** (2b). Each contribution is multiplied by
   `0.5 ^ ((now − endedAtMs) / halfLifeMs)`.
8. **Like events** (2c). Each `LIKED` event contributes the like weight (3),
   decayed from the like's time. `UNLIKED` events contribute nothing; unliking
   is handled by membership, not by the score. See decision 10.4 on which
   timestamp a like decays from.
9. **Sum.**

Every parameter above comes from `ScoringParameters`, never a constant.

### 3.3 DJ session inference

`DjSessions.infer(commentarySegments)` takes the timestamps of all
`spotify:media:` segments and returns time windows. A listen is "during a DJ
session" if a commentary row ended within **20 minutes** before it started.
That 20 minutes is a constant with a comment, not a setting: DJ speaks every
few songs, and being wrong only ever forgives a penalty (2g). It is the one
place where "was this play DJ-selected?" is guessed, and the guess is used for
nothing else.

### 3.4 Parameters as settings (invariant 1d)

`ScoringParameters` is a data class with the section 3 values as defaults. It
is stored as one JSON string in the existing `sync_state` key/value table
(`valueText`), under a key such as `scoring_parameters`. No new table, no new
dependency; `org.json` is already in use. Snapshots store the same JSON, so a
snapshot can always be read with the parameters that produced it (section 5).

An eighth value lives alongside them: the **record floor** from section 4
("every liked track scoring above a low floor"). Proposed default 0.1
play-equivalents. Decision 10.5.

### 3.5 Ranking and the cache

`Ranker` loads every currently liked canonical track, scores each, sorts by
score, and produces `(trackId, score, rank)`. To keep the NW-A307 comfortable
it loads only segments newer than **ten half-lives** (450 days at the
default), where the decay weight is below 0.1%; the recompute test proves this
changes no score by more than 0.001.

Results go into a `scores` table that is **only a cache** (1a): every row has
the hash of the parameters JSON that produced it, and the table is dropped and
rebuilt whenever the parameters change or the user asks. Nothing reads it as
truth; the sync worker and the debug screen read it for speed, and either can
trigger a rebuild.

### 3.6 Membership

`Membership.decide(currentMembers, ranked, params)` applies section 4:

- a track joins the playlist set at rank 100 or better;
- a member leaves when its rank falls below 130, or immediately when unliked;
- re-liking makes it eligible again at its current rank.

The current member set is stored in an `evergreen_membership` table
(`trackId`, `joinedAt`, `leftAt`, `reason`), append-style like the like
archive, so "when did this song leave and why" is always answerable. The
record (everything above the floor) is not stored; it is a query over the
cache.

---

## 4. Imported history in the segment table (invariant 1b)

### 4.1 Schema v4

`listening_segments` gains:

- `source` TEXT NOT NULL DEFAULT `CAPTURE`; imported rows carry
  `IMPORT_SPOTIFY_HISTORY`. This is what lets the debug screen, the import
  de-duplication and the tests tell the two apart without guessing from nulls.
- `sourceEndReason` TEXT nullable: the export's own `reason_end` code
  (`trackdone`, `fwdbtn`, …), kept because it is a fact about the play even
  though the scorer does not use it. Null on captured rows.
- `startPositionMs`, `endPositionMs`, `forwardSeekCount`, `seekedForwardMs`
  and `lastSampleAgeMs` become **nullable**. The export has none of them.
- `EndReason` gains `IMPORTED`: "the end was not observed by this app; it
  came from an import." An observation about provenance, not a conclusion.

Changing nullability makes SQLite rebuild the table, which Room's
auto-migration handles the same way v2 did. The migration test builds a
populated v3 database, migrates it, and asserts every segment survives with
`source = CAPTURE`. `MergedSegment` and the debug screen's segment row become
null-tolerant.

### 4.2 The importer

Entry point: a button on the Spotify debug tab, "Import streaming history",
which opens the Android file picker (the system "Open document" screen, so the
app needs no storage permission) accepting the zip or the individual JSON
files. Files are streamed with `android.util.JsonReader` rather than loaded
whole: the largest is 12 MB, and the NW-A307 should never hold it in memory.

**Fields read, and nothing else:** `ts`, `ms_played`, `spotify_track_uri`,
`master_metadata_track_name`, `master_metadata_album_artist_name`,
`master_metadata_album_album_name`, `reason_end`. The parser skips every other
key by name. In particular `ip_addr`, `conn_country`, `platform`,
`offline_timestamp` and the podcast/audiobook fields are never read into a
variable. This is the privacy rule ("playback metadata only") applied to a
file that contains more, and a unit test asserts the parser's output type has
no room for anything else.

Row mapping:

| Export | Segment row |
|---|---|
| `ts` (end of the stream, UTC) | `endedAtMs` |
| `ts − ms_played` | `startedAtMs` |
| `ms_played` | `listenedMs` |
| `spotify_track_uri` | service row (resolved through `recordSegment`, so a known URI joins its existing canonical track and an unknown one creates one) |
| name / artist / album | `trackKey` fallback and the new track's display fields |
| service row's `durationMs` if known, else 0 | `trackDurationMs` |
| `reason_end` | `sourceEndReason` |
| — | `endReason = IMPORTED`, `source = IMPORT_SPOTIFY_HISTORY`, `appPackage = com.spotify.music`, `deviceId = "spotify-history-import"`, position and seek fields null |

Rows without a track URI (podcasts, 27 in this export) are skipped and
counted.

**Which rows.** Recommendation: all of them, about 77,000 rows over 12,700
tracks, of which 376 are liked. See decision 10.1. If only liked tracks are
imported the same code runs with a filter.

**Idempotence** ("running it twice changes nothing"): the importer records
the SHA-256 of each file it has completed in `sync_state` and refuses to
re-run the same file; and within a run it skips any row for which an imported
segment with the same service row and `startedAtMs` already exists.

**Overlap with capture.** A row is skipped if a *captured* segment for the
same track overlaps its time span. Today the export ends 2026-09-15 and
capture began 2026-09-17, so nothing overlaps, but a re-export next year will
contain plays the phone also captured, and this rule keeps desktop plays
(which capture cannot see until Phase 6) while dropping the duplicates.

**Batching.** 500 rows per transaction, progress shown on the debug tab, safe
to interrupt (the file hash is only recorded at the end, and the per-row check
makes the rerun cheap).

**ISRC backfill.** With 12,000 never-liked tracks in `service_tracks`, the
existing backfill would spend one request per track, five attempts each,
against an unpublished quota. The backfill's work query is narrowed to service
rows that have a like or a captured segment. Imported-only rows get an ISRC
only if they are later liked, which is free inside the like sync.

---

## 5. The Evergreen sync worker

### 5.1 Schema v5

Three small tables, all added, nothing existing touched:

- `scores` — the cache (3.5): `trackId`, `score`, `rank`, `computedAt`,
  `parametersHash`.
- `evergreen_membership` — 3.6.
- `snapshots` and `snapshot_entries` — section 6 below.

Split from v4 so each migration is one idea and one test.

### 5.2 One sync run

1. **Guards.** Not connected → stop and cancel the schedule, as the like sync
   does. Inside a backoff window → stop. Last successful write less than 24 h
   ago → stop (section 6: at most one write per day).
2. **Recompute.** Rebuild the score cache if the parameters changed or the
   cache is empty; otherwise refresh it. Score computation never touches the
   network.
3. **Desired set.** `Membership.decide` over the ranking and the current
   like state.
4. **Current set.** `GET /playlists/{id}/items` with `fields=items(item(uri))`,
   50 per page — two requests for 100 tracks. The playlist id is stored in
   `sync_state`; if missing, `GET /me/playlists` looks for a playlist named
   `Evergreen` owned by the user, and only if none exists does
   `POST /me/playlists` create one, private, described as managed by
   Evergreen.
5. **Diff.** `PlaylistDiff.compute(current, desired)` → URIs to add, URIs to
   remove. If both are empty, record a successful no-op and stop. The URI
   written for a track is its liked service row's URI, so the playlist gets the
   version the user liked, not whichever version was played most.
6. **Apply.** Removes first, then adds, in batches of 100, through the same
   `ApiResult` / backoff path as Phase 2. A 429 or `QUOTA_EXCEEDED` sets the
   backoff window and stops; the next run re-reads the playlist and continues
   from wherever it got to, because the diff is recomputed each time.
7. **Record** the membership changes and the write time.

**Dry run.** The debug tab has "Preview Evergreen sync", which runs steps 2
to 5 and lists what would be added and removed, with scores and ranks. The
first real write is behind a confirmation on that screen, exactly the
same-song merge's pattern. Until the user has confirmed once, the scheduled
worker runs steps 1 to 5 only and shows the pending diff; after that, it
writes.

**Consequence worth stating.** The playlist is app-owned: anything added to it
by hand will be removed at the next sync, because the diff targets the desired
set. Decision 10.3.

### 5.3 Scheduling

`EvergreenSyncWorker`, a `CoroutineWorker` like `SpotifySyncWorker`, unique
periodic work every 24 h with a network constraint, plus a "sync now" for the
debug tab. Unliking removes a track at the next run rather than instantly; a
like sync runs every 12 h and the Evergreen sync daily, so "immediate" in
practice means within a day. The membership row records the reason
`UNLIKED`.

---

## 6. Snapshots

- `SnapshotWorker` runs daily and does nothing unless today is the last day
  of the month in the device's timezone and no snapshot exists for this month.
  A daily check that usually does nothing is simpler and more robust than
  computing "the last day of the month" as a schedule.
- A snapshot stores: `takenAt`, the parameters JSON, and one entry per ranked
  liked track (not only the top 100) with `rank`, `score`, `trackId`, the
  service URI at the time, and whether it was in the playlist. Storing the
  whole ranking is what makes "why was this in August" and "what was alive
  in August" both answerable later.
- The debug tab lists snapshots by month. "Export to Spotify" creates a
  playlist named `Evergreen — 2026 August` from the top-100 entries, private,
  one-off, never touched again by the app. No folders: the Web API has none.
- Search ("2026 August") is Phase 4's real UI; Phase 3 stores the data in a
  shape that makes it a query.

---

## 7. The explain-the-score view

A fourth debug tab, "Scores":

- **List:** every liked track ranked, with score, rank, and markers for
  "in playlist", "above the floor", and the two cutoffs drawn as lines at
  ranks 100 and 130. Reads the cache; a "Recompute" button rebuilds it.
- **Detail:** tapping a track shows the `ScoreBreakdown` from 3.1 — each
  event in date order with fraction heard, raw contribution, decay weight and
  decayed value; then the like events; then the total, the rank, and the
  scores at ranks 100 and 130 so the distance to the cutoff is visible.
- **Parameters:** shown read-only at the top of the tab, with the JSON
  editable behind a "Danger zone" expander, since Phase 4 owns the real
  settings screen. Editing triggers a rebuild, never a migration.

This tab is built *before* the sync worker (step 11 before step 13), so the
user can look at real scores over the imported history and adjust the
parameters before anything is written to Spotify.

---

## 8. Notifications (section 7)

- A notification channel "Connection" for the reconnect-required case,
  posted by the like sync and the Evergreen sync when they hit
  `NeedsReconnect`, and by the existing two-week advance warning. On by
  default.
- Channels "Snapshots" and "Playlist changes", built, off by default (the
  user turns them on in Android's notification settings for the app).
- Android 13+ requires the `POST_NOTIFICATIONS` runtime permission. This is a
  new permission in the manifest and a one-time prompt. Decision 10.6.

---

## 9. Test list

Unit tests (plain JVM, `app/src/test`):

1. `ScoringCurveTest` — fraction 0 → −0.5; 0.15 → −0.5; 0.20 → mid-ramp;
   0.5 → 0; 0.85 → mid-ramp; 0.95 → +1; 1.0 → +1; monotonic across [0, 1];
   unknown duration → 0 with a stated reason.
2. `DecayTest` — weight 1.0 at 0 days, 0.5 at 45, 0.25 at 90; a 2019
   play at the default half-life is below 0.001 (invariant 1c).
3. `TrackScorerTest` — one full play today = 1.0; a like today = 3.0; a
   like from two years ago ≈ 0; forty loops in one night = 2.0 not 40;
   two rows joined by a 5 s jump score as one play, by a 15 s jump as two;
   a commentary row contributes nothing; an imported row (nulls) scores the
   same as an identical captured row; an imported row with no duration
   contributes nothing until a duration is supplied; a 10% listen during a
   DJ window contributes 0, outside it −0.5; a full play during a DJ window
   contributes +1.
4. `RecomputeTest` (invariant 1a) — build a history; score with parameters
   A and fill the cache; change to B; recompute through the cache path;
   assert every score equals a from-scratch computation under B, and that
   dropping the cache and rebuilding gives the same again. Also: the
   ten-half-life load window changes no score by more than 0.001.
5. `DjSessionsTest` — windows from commentary timestamps; a play 19 min
   after commentary is inside, 21 min is outside; no commentary → no windows.
6. `MembershipTest` — joins at rank 100, not 101; a member at rank 120
   stays; at 131 leaves; unliked leaves regardless of rank; re-liked at
   rank 50 rejoins; reasons recorded.
7. `PlaylistDiffTest` — adds, removes, no-op when equal, order-insensitive,
   batches of 100, a manually added URI is scheduled for removal.
8. `HistoryParserTest` — on a synthetic JSON: reads only the listed fields;
   skips rows without a track URI; computes started/ended; a row with an
   `ip_addr` key produces an output with no such value anywhere.
9. `ScoringParametersTest` — JSON round trip; defaults equal section 3;
   unknown keys ignored; a changed value changes the hash.
10. `SpotifyApiTest` additions — playlist URLs are on the allowed host; the
    remove body uses `items`, not `tracks`; more than 100 URIs are split.
11. `SpotifyScopesTest` — exactly `user-library-read`,
    `playlist-modify-private`, `playlist-read-private`.

Instrumented tests (`app/src/androidTest`, on a device that is *not*
accumulating history — running them uninstalls the app):

12. `EvergreenMigrationTest` v3→v4 and v4→v5 — populated database, every
    segment survives, `source = CAPTURE`, positions intact.
13. `HistoryImportTest` — rows land as segments; second run is a no-op;
    an overlapping captured segment wins; a track liked after import gets a
    duration and starts scoring without re-import.
14. `IsrcBackfillScopeTest` — imported-only service rows are never queued.
15. `EvergreenSyncTest` against a scripted API — dry run writes nothing;
    first write creates the playlist private; diff applies removes then
    adds; 429 backs off and the next run resumes; a second run within 24 h
    writes nothing; unliking a member removes it.
16. `SnapshotTest` — a snapshot stores the parameters JSON and the full
    ranking; export creates a playlist with the top 100 in order.

---

## 10. Decisions needed before building

1. **Import everything, or liked tracks only?** Recommend everything (77,000
   rows, 12,700 tracks): the archive principle, and a track liked next year
   then has its history already. Cost is about 10 MB of database and the
   backfill narrowing in 4.2. Liked-only (17,000 rows) means keeping the
   export and re-importing for each new like.
2. **Read the playlist back before each write?** Recommend yes. It costs the
   `playlist-read-private` scope and two requests a day, and makes the sync
   self-healing when the playlist is edited elsewhere. The alternative is to
   trust the local membership table as the "current" side of the diff, with
   no way to notice drift.
3. **The playlist is app-owned.** Tracks added by hand are removed at the
   next sync. Accept, or keep hand-added tracks (which needs a "not ours"
   list and complicates the diff).
4. **Which timestamp does a like decay from?** Recommend Spotify's own
   `added_at` (when the user actually liked it), falling back to when the
   app first observed it. Under this, likes from years ago contribute almost
   nothing and the first ranking is driven by listening. The alternative,
   first-observed, would give all 380 likes a three-play boost dated
   2026-09-17 that fades together.
5. **Record floor.** Default 0.1 play-equivalents for "still alive" in the
   in-app record. Any low number works; it only affects the record, not the
   playlist.
6. **Notification permission.** `POST_NOTIFICATIONS` is a new manifest
   permission with a one-time prompt. Required for section 7 as decided;
   listed here because adding a permission is worth a deliberate yes.
7. **Upper breakpoint 0.85 or 0.80.** Calibrated at 0.85 under the decisions'
   definition; 0.80 if only next-button skips count. It is a setting and the
   explain view will show the effect, so this can be changed after seeing real
   scores. Default 0.85.
8. **Schema v4 and v5** as described in 4.1 and 5.1. `CLAUDE.md` requires
   approval for schema changes; the plan is the proposal.

### Answers, 2026-09-17

All eight approved as recommended: import everything; read the playlist back
(`playlist-read-private` granted); the playlist is app-owned; likes decay from
Spotify's `added_at`, falling back to first-observed; record floor 0.1; the
`POST_NOTIFICATIONS` permission is approved; upper breakpoint 0.85 as a
tunable; schema v4 and v5 approved as described in 4.1 and 5.1. If the
implementation needs to deviate from those descriptions, that is a new
question, not a granted one.

### Raised after the answers, adopted unless struck

9. **One device writes Evergreen.** Each device has its own database until
   Phase 6, so two devices running the sync would fight over one playlist
   with two different rankings. A per-device setting "This device writes
   Evergreen" (in `sync_state`, default off) is turned on once, on the Pixel.
   The Walkman captures only; its history reaches the Pixel in Phase 6. The
   import also runs only on the writer device.
10. **A database backup button now, not in Phase 4.** After the import, the
    database is the most valuable thing in the project, and running
    instrumented tests destroys it. "Back up database" on the debug screen
    checkpoints the write-ahead log and writes a copy of the file to a
    location the user picks in the system "create document" dialog. The
    database holds no tokens, so nothing secret leaves. One step, no schema
    change, no dependency.
11. **Instrumented tests never run on the Pixel once the history is
    imported.** They uninstall the app. Use the Walkman or an emulator.
12. **Import timing.** Build the importer early (steps 3 to 5) and run the
    real import on the Pixel as soon as step 5 lands, before scoring exists,
    so the explain view in step 11 shows six years of real listening rather
    than a fortnight.
13. **The app never touches a playlist it did not create.** It writes only to
    the playlist whose id it stored, looks a playlist up by name only when no
    id is stored and only among playlists the user owns, and has no code path
    that deletes a playlist. Snapshot exports create a new playlist each time
    and are never modified afterwards.

---

## 11. Steps

Each step is one commit on branch `phase3-scoring`, with its tests passing.
Steps marked **[approval]** touch the schema, the manifest or the scopes and
wait for a yes before the code is written.

| # | Step | Tests |
|---|---|---|
| 1 | `ScoringParameters` data class, defaults from section 3, JSON round trip, storage in `sync_state`. | 9 |
| 2 | `ScoringCurve` and `Decay` pure functions. | 1, 2 |
| 3 | **[approval]** Schema v4: `source`, `sourceEndReason`, nullable position fields, `EndReason.IMPORTED`; `MergedSegment` and the debug row null-tolerant. | 12 |
| 4 | History parser (streaming, field allowlist). | 8 |
| 5 | Importer: file picker, batching, idempotence, overlap rule, progress on the Spotify tab; backfill narrowing. Then the real import on the Pixel. | 13, 14 |
| 5b | "Back up database" button and the "This device writes Evergreen" setting (decisions 9 and 10). | manual, on device |
| 6 | `DjSessions`. | 5 |
| 7 | `TrackScorer` with `ScoreBreakdown`. | 3 |
| 8 | `Ranker` in memory, load window, and the recompute test. | 4 |
| 9 | **[approval]** Schema v5: `scores`, `evergreen_membership`, `snapshots`, `snapshot_entries`. | 12 |
| 10 | Score cache and `Membership`. | 6 |
| 11 | "Scores" debug tab: ranked list, breakdown detail, parameters. | manual, on device |
| 12 | **[approval]** Spotify API: five playlist endpoints, two new scopes, reconnect to grant them. | 10, 11 |
| 13 | `PlaylistDiff`, `EvergreenSync` with dry run and confirmation, once-per-day guard, backoff. | 7, 15 |
| 14 | `EvergreenSyncWorker` scheduling; unlike removal. | 15 |
| 15 | Snapshots: worker, storage, list on the debug tab, export on demand. | 16 |
| 16 | **[approval]** Notifications and `POST_NOTIFICATIONS`. | manual |
| 17 | Device verification on the Pixel: import the real export, review scores, run the dry run, confirm the first write, check the playlist in Spotify. Update `PLAN.md`. Merge. | — |

Step 11 before step 12 is deliberate: the user sees real scores before the
app can write anything to Spotify.

---

## 12. Things that could go wrong, and what is done about them

- **The import doubles up with capture on a re-export.** The overlap rule in
  4.2, tested.
- **The score cache drifts from the truth.** It carries the parameters hash
  and is rebuilt on mismatch; the recompute test pins the equivalence.
- **A 429 mid-write leaves the playlist half-updated.** The diff is
  recomputed from the live playlist each run, so the next run finishes the
  job; nothing is stored as "done" until the API says so.
- **Spotify moves the playlist endpoints again.** Every endpoint carries its
  reference URL and check date, and the API layer logs a 404 with that URL.
- **The refresh token expires and Evergreen silently stops.** The
  reconnect notification, on by default.
- **The NW-A307 struggles with 77,000 rows.** The load window (3.5) bounds
  the scorer's working set to about 450 days; the import streams the file;
  the debug screen already limits what it lists.

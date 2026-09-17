# Phase 2 prompt — paste this into Claude Code

Read `CLAUDE.md` and `docs/PLAN.md` first, including the **Phase 1 results**,
the **Device verification** section, and the **Phase 2 questions** under Open
questions. We're starting Phase 2: the Spotify connection.

Work on a new git branch called `phase2-spotify`. This phase adds a schema
migration, network access and a third-party login flow — all of which
`CLAUDE.md` says need sign-off. So **propose the migration plan and the auth
approach, and wait for my approval before writing code.** I'm new to
development: explain in plain language what the migration changes and why, and
what the login flow does to my account.

## Goal
Connect the app to Spotify so it can read my likes and match them to the
listening history Phase 1 already captures. No playlist writing yet — that is
Phase 3. By the end of this phase the app should know which of the songs I have
listened to are songs I have liked.

## What Phase 1 already settled — don't re-derive or re-test these
- **Capture works and is device-verified.** Play segments, replay boundaries,
  listened ms and de-duplication all behave correctly on the Pixel 10 Pro and
  the NW-A307. Don't revisit the capture path unless Phase 2 breaks it.
- **The Spotify track URI is already stored** on every segment that had one, in
  `service_tracks.serviceTrackId` as `spotify:track:...`. Matching in this phase
  starts from an exact ID; it does not need to guess from artist and title.
- **Track identity resolves by service ID first, then `trackKey`.** This is
  deliberate and tested — see `EvergreenDao.recordSegment` and
  `EvergreenDaoTest`. Don't reorder it.
- **Segments store facts, not conclusions.** There is no outcome column and
  there must not be one. Phase 2 adds no interpretation of listening either.
- **`isrc` already exists on `service_tracks`, nullable and always null.** It is
  waiting for this phase to fill it in. No schema change is needed to store it.
- **The database is the source of truth.** Spotify is a sync target: likes are
  read from it, Evergreen is written to it later. Never treat Spotify's state as
  authoritative over the local database.

## Before designing anything — verify the API
`CLAUDE.md` is explicit about this and it matters more in this phase than any
other. The Spotify Web API changed significantly between February and July 2026:
development-mode limits, removed endpoints, generic `/me/library` endpoints that
take Spotify URIs, a six-month refresh-token lifetime, and a per-account quota
that returns `QUOTA_EXCEEDED` 429s.

**Verify every endpoint against the live reference at developer.spotify.com
before using it.** Many tutorials and much training data are outdated. In
particular, confirm before relying on them:

1. **Does the track object still return `isrc` in `external_ids`?** The
   same-song merge rule depends on it. If it does not, say so and stop —
   do not invent a fallback matching rule without asking me.
2. **What is the current endpoint for reading liked songs**, and what does it
   return? The old `/me/tracks` may have moved under the generic `/me/library`
   endpoints.
3. **What scopes do those endpoints require now?** Request the minimum set and
   list exactly which ones you asked for and why.
4. **What redirect URI schemes does the dashboard currently accept?** Check this
   against my Spotify developer app before choosing one — I will read the
   dashboard for you if you tell me where to look.

If anything is unclear or the reference contradicts itself, say so and ask me to
check rather than guessing.

## Build

1. **Schema v2 migration.** Generate it against the committed
   `app/schemas/.../1.json` rather than hand-writing it, and test it with
   `room-testing` (already a dependency).
   - **Change the `listening_segments` → `tracks` foreign key from
     `ON DELETE CASCADE` to `RESTRICT`.** As it stands, deleting or merging a
     canonical track silently takes its listening history with it — the exact
     data the app exists to accumulate. `RESTRICT` forces any merge to re-point
     its segments deliberately instead.
   - Add whatever the like archive needs (below).
   - The migration must preserve existing rows. Test that explicitly: build a v1
     database, populate it, migrate, and assert the segments survive.

2. **Like archive.** Every song I have ever liked is kept, per the project goal —
   this is the archive, and unliking must not delete anything. At minimum record
   which service track was liked, when it was first seen as liked, and when it
   was last seen as liked or unliked. Design it so that a like that disappears
   from Spotify becomes *historical*, not *erased*.

3. **Auth: Authorization Code with PKCE.** No client secret in the app — that is
   not optional. Propose one of:
   - **AppAuth for Android** — a well-established library, but a **new
     third-party dependency that needs my approval before you add it**, or
   - **a hand-written PKCE flow** using a Custom Tab and the system browser.

   Recommend one and say why, including what each costs in code we have to
   maintain ourselves. Either way:
   - Store tokens encrypted with a key held in the **Android Keystore**. Do
     **not** use the deprecated `security-crypto` library.
   - Tokens and the database stay out of cloud backup and device transfer —
     `allowBackup="false"` and the extraction rules are already set, don't undo
     them.
   - The client ID goes in `local.properties`, which is gitignored. Never
     hardcode it, never print it, never commit it. Do not read `local.properties`
     yourself; tell me what to put in it.

4. **Refresh-token expiry is a visible state, not an error.** The refresh token
   lasts six months. When it expires, the app must show a clear "reconnect
   Spotify" prompt rather than failing silently or spamming retries. Treat
   "not connected" and "connection expired" as distinct, displayable states.

5. **Matching.** For each `service_tracks` row with a `spotify:track:` ID, fetch
   the track and fill in `isrc`. Batch the requests; don't fetch one at a time.
   Record when a lookup failed so it can be retried later without re-fetching
   everything.

6. **Same-song merging — propose, don't implement blind.** `trackKey` includes
   the album, so the single and the album version of a song are two canonical
   tracks today. Once ISRC is available, propose the merge rule and **wait for
   approval before running any merge over my data**. Whatever the rule, merging
   must re-point segments, never delete them — which is what item 1's `RESTRICT`
   is there to enforce. A dry-run that reports what *would* merge is the right
   first step.

7. **Sync scheduling with WorkManager.** Like sync runs periodically, not on
   every launch. Keep API usage light. Handle 429 with backoff and respect
   `Retry-After`; treat `QUOTA_EXCEEDED` as a back-off-and-try-much-later
   condition, not a crash.

8. **Spotify DJ investigation.** A short device session, before Phase 3 scores
   anything. Find out:
   - whether the DJ's spoken commentary surfaces as a media-session track,
   - what identifier it carries, if any — this decides whether it can be
     filtered at all,
   - whether DJ playback is distinguishable from normal playback.

   Record the findings in `docs/PLAN.md` including, explicitly, anything that
   turns out **not** to be observable. If commentary is indistinguishable from a
   real track, that is an important finding, not a failure.

9. **Extend the debug screen** to show connection state, when likes last synced,
   how many service tracks have an ISRC, and any failed lookups. Keep the
   existing segment view as it is.

10. **Tests.**
    - the v1 → v2 migration preserves existing segments,
    - `RESTRICT` actually blocks a track delete that would orphan segments,
    - the like archive keeps a record after an unlike,
    - token storage round-trips through the Keystore,
    - 429 handling backs off and respects `Retry-After`,
    - refresh-token expiry surfaces the reconnect state.

    Put anything that needs real SQLite in `androidTest`, as `EvergreenDaoTest`
    does. **Warning: running instrumented tests uninstalls the app and destroys
    its database** — if my device holds listening history I care about, tell me
    to back it up first, or run them on the other device.

## Constraints
- **`INTERNET` permission arrives in this phase** — it is expected and required.
  Nothing else. No analytics, advertising or crash-reporting SDKs, ever.
- Network access only to Spotify's API. No other hosts.
- No new third-party dependencies without asking first — including AppAuth.
- Don't change the capture path's behaviour. If a Phase 2 change touches it,
  say so explicitly and show me the diff.
- Don't add an outcome or score column to `listening_segments`. Scoring is
  Phase 3 and must stay recomputable from the stored facts.
- Never run a destructive or merging operation over my data without asking,
  even if the migration or the merge rule looks obviously correct.

## Still unknown, worth watching for
- **The exact shape of a session ending** (Spotify force-stopped) — the last of
  the three Phase 0 unknowns, still not observed.
- Whether backwards jumps of 1–2 seconds near position zero are deliberate
  double-presses of "previous" or the player settling at track start. If the
  latter, `POSITION_JUMP_BACK_CUT_MS` is slightly too low. Costs nothing to get
  wrong — the jump size is stored on every row, so those segments can be merged
  away later.

If something cannot be observed, record that it cannot. Never invent data to
fill a gap.

## When done
Give me click-by-click instructions to:
- connect my Spotify account and confirm the tokens are stored encrypted,
- verify likes synced and ISRCs were filled in,
- see what the app does when the connection expires (tell me how to simulate it).

Then update `docs/PLAN.md` — Status, the Phase 2 checkbox, the Phase 2 questions
now answered, the DJ findings, and anything learned — and commit.

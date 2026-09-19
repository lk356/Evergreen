# Phase 5a prompt — paste this into Claude Code

Read `CLAUDE.md` first, then `docs/PLAN.md` (the Status section, the Phase 4
results, and everything under "Open questions — still open"), then
`docs/PHASE5_QUESTIONS.md`.

We're starting **Phase 5a: the Pixel as a read-only mirror of the Walkman.**
Work on a new git branch called `phase5a-mirror`, branched from `main` after
`phase4-usability` has been merged into it. One commit per step, with that
step's tests passing, in the style of the existing history.

I'm new to development. Explain what you are doing and why in plain language,
briefly, and define jargon the first time it appears. When something has to be
done on a device, give me click-by-click instructions.

## Why this phase exists, and why it is small

The roadmap changed. Phase 5 was "cross-service"; it is now "cross-device,
Android to Android", because syncing between the Walkman and the Pixel is the
functionality I actually miss. Cross-service moves to Phase 6, and desktop
capture and the self-hosted backend to Phase 7. Step 0 below makes
`docs/PLAN.md` say so.

**This phase is deliberately not the full merge.** Full peer sync — both
devices capturing, facts flowing both ways, segments deduplicated — is Phase
5b, and its design questions are open in `docs/PHASE5_QUESTIONS.md`. Do not
build toward them, and do not ask me to answer them now.

What 5a delivers is the top item on my want list at a fraction of the cost:
**the Walkman's data, readable on the Pixel's faster screen.** It also proves
the thing 5b depends on — that shipping facts and recomputing locally gives
the same ranking on both devices. If that does not hold, I want to know now,
not after a merge has been built on top of it.

## What is already built — don't rebuild or regress it

- **Capture is untouched since Phase 1.** If a change here has to touch
  `capture/`, say so explicitly and show me the diff first.
- **Scoring is settled and calibrated**, with 259 unit tests. A score is
  computed, never accumulated (invariant 1a). `RecomputeTest` and
  `ScoreCacheTest` must keep passing.
- **The database is at schema v6.** Schema changes still need my approval.
- **The backup button exists** (`DatabaseBackup`, SQLite `VACUUM INTO`,
  through the system create-document picker). **This phase should reuse it
  rather than invent a second export path.**
- **Spotify writes are behind guards**: one device, one write a day, a dry run
  and a one-time confirmation, and the app never touches a playlist it did not
  create. Do not loosen any of those. This phase *adds* a guard.
- **The Walkman is the writer device and my daily driver.** Its screen is
  339 × 602 dp. Check every screen there, not only on the Pixel.
- **Instrumented tests destroy the database of the device they run on.** The
  build refuses to run them with a Walkman attached; do not override that.

## Constraints that do not relax

- Every privacy rule in `CLAUDE.md`. No analytics, no crash reporting, no
  third-party SDKs. **Network access stays limited to `accounts.spotify.com`
  and `api.spotify.com`** — this phase adds no network destination at all,
  because the transport is a file.
- **Never print, log or show a row of the database or of an export** in
  messages to me. Counts and aggregates only.
- **No new third-party dependencies.** Phases 3 and 4 added none.
- Any new derived table must be droppable and rebuildable.

---

## Step 0 — Bring the plan up to date

Bookkeeping from a planning session on 2026-09-19. Do this first, as its own
commit, before any code.

**0.1 Phase 4 is done.** Change its checkbox in the roadmap from `[~]` to
`[x]`, and replace "awaiting device verification on the NW-A307" with the fact
that it was device-verified and merged to `main`. Ask me for the date the
merge landed if the git history does not make it obvious.

**0.2 Replace the roadmap entries for Phases 5 to 7** with:

```
- [ ] **Phase 5 — Cross-device (Android).** 5a: the Pixel as a read-only
      mirror of the Walkman, transported as a file. 5b: full peer sync,
      both devices capturing, segments merged. See
      `docs/PHASE5_QUESTIONS.md`.
- [ ] **Phase 6 — Cross-service.** Second service adapter, primarily the
      Sony Walkman player for local files on the NW-A307; ISRC/MusicBrainz
      matching. Rule to hold to: scoring merges across services, output is
      per-service.
- [ ] **Phase 7 — Desktop capture and sync backend.** Linux agent (MPRIS)
      first, self-hosted sync backend, Windows agent (WinRT media session
      API) after.
- [ ] **Phase 8 — Nice-to-haves.** Richer stats, per-artist figures in the
      style of the Spicetify stats app or ListenBrainz.
- [ ] **Phase 9 — Public release evaluation.** Terms, quotas, privacy
      policy, delete-my-data, and a security review by someone qualified to
      do one.
```

**0.3 Record why the order changed**, near the roadmap, in a sentence or two:
cross-device came first because listening happens on the Walkman and the Pixel
but stats are only readable on the slower screen, and because desktop capture
needs the same fact-shipping protocol underneath it.

**0.4 Close the volatility question.** Under "Open questions — still open",
strike it through and record: settled at the current parameters on 2026-09-19.
Score over time was built to answer it, I have looked at the charts over my own
listening, and the scores behave well. Reopen only if a specific track I still
love visibly falls out of Evergreen.

**0.5 Promote the Spotify Connect phantom-segment question.** It currently sits
under "Still unknown — carried into Phase 6". Move it to Phase 5b and mark it
**blocking for the merge**: if the Pixel records segments for music actually
playing on the Walkman, merging the two devices' captures inflates play counts
invisibly. Keep the two notes already recorded with it — that
`MediaController.PlaybackInfo` may distinguish remote from local playback, and
that segments overlapping in wall-clock time are at most one real listen.

**0.6 Record the public-release finding**, under Phase 9 or in a note beside
it, because it changes what a public version can be:

> Spotify Development Mode is capped at five users per app and requires the
> app owner to hold an active Premium subscription. Extended quota access has
> since May 2025 required a registered business entity, an active launched
> service, and 250,000+ monthly active users. There is no path from a hobby
> project to a hosted multi-user Spotify integration. **A public version must
> therefore be self-hostable, with each user supplying their own client ID.**
> Checked 2026-09-19; re-verify before acting on it.

**0.7 Note the nice-to-have direction** under Phase 8, so it is not
rediscovered as new: richer stats need artists as entities rather than a
single artist string on `tracks`, because "A feat. B" fragments the moment
anything counts per artist. **Do not build this now.** Just record it, and
record that the like sync already receives full artist objects with ids, so
keeping them when they arrive is cheaper than backfilling later.

Commit step 0 on its own.

---

## Step 1 — Check 0a: does the Pixel capture phantom segments?

A measurement, not a build. It informs 5b, not this phase.

With capture running on the Pixel and Spotify open on it, I play several
tracks on the Walkman — some to the end, some skipped. Give me click-by-click
instructions for what to do and what to read off the debug screen. Then record
the result in `docs/PLAN.md` beside the question from step 0.5, in counts and
aggregates only.

While you are there, check in code whether `MediaController.PlaybackInfo`
exposes anything that distinguishes remote from local playback, and record
what you find. **Do not change `capture/` on the strength of it.**

---

## Step 2 — Mirror mode

A database can be a mirror: a copy of another device's data, not this
device's own record. `sync_state` is where that fact lives. If it is a
key-value table this needs no migration; if it is not, **stop and propose a
schema change rather than adding one.**

Mirror mode records what device the copy came from and when it was taken.

**The guard, which is the point of this step: a mirrored database never
writes to Spotify.** Not the Evergreen sync, not a manual write, not a
snapshot export that creates a playlist. The existing writer-device flag is
not enough on its own, because the imported copy *carries the Walkman's
`sync_state` with the writer flag set* — which is exactly the trap this guard
exists to close. Test it.

---

## Step 3 — Import a mirror

An "Import mirror" action on the Pixel, next to the existing backup button,
using the system open-document picker, taking a file produced by the existing
backup button on the Walkman.

It must:

1. **Validate before replacing.** Check the schema version matches, and refuse
   with a clear message rather than half-importing. A backup from a newer
   schema version is a refusal, not a migration.
2. **Warn plainly** that this replaces everything on this device, and require
   confirmation — the same pattern as wiping listening history.
3. **Neutralise device-specific state** after the copy lands: mirror mode on,
   writer flag off, all scheduled work for Evergreen writes and like sync
   cancelled, the score cache dropped so it rebuilds locally rather than being
   trusted from another device.
4. **Turn capture off on this device**, per question 3b of the questions
   document, because captures made into a mirrored database are discarded at
   the next import and leaving capture running would imply they counted.
   Turning capture back on drops the device out of mirror mode and requires
   confirmation.

Tell me to take a backup of both devices before I run this the first time.

---

## Step 4 — Say it on screen

The home screen's status line must say, whenever mirror mode is on, that this
is a mirror of the NW-A307 and when it was taken. Quiet is wrong here: a
mirror silently going stale while looking like live data is the one way this
feature can mislead me. On a 339 dp screen this has to be one short line.

Anywhere that would offer a Spotify write should say why it is unavailable
rather than failing when pressed.

---

## Step 5 — The proof, and the verification

This is what the phase is really for.

**In tests:** the same facts and the same parameters produce the same ranking,
independent of which device computed it. Build a populated database, recompute
it twice in conditions that differ the way two devices differ, and assert the
rankings are identical. This is invariant 1a holding across devices, and it is
the foundation 5b will stand on.

**On the devices**, a checklist for me to run, in the style of the Phase 3 and
Phase 4 ones:

- take a backup on the Walkman, note its size;
- import it on the Pixel;
- the Pixel shows mirror mode, with the Walkman's name and the timestamp;
- the top of the ranking on the Pixel matches the top of the ranking on the
  Walkman — I will read both screens and compare;
- the Pixel offers no Spotify write anywhere, and says why;
- capture is off on the Pixel;
- the Walkman is unchanged and still writes Evergreen on schedule;
- record how long the import and the first recompute take on each device, and
  put both numbers in `PLAN.md` beside the existing 2,309 ms baseline.

Give me click-by-click instructions for moving the file between devices, and
tell me the options — USB, adb, or a file-sharing app — rather than assuming
one.

---

## When done

Update `docs/PLAN.md`: Status, the Phase 5 checkbox, what was learned, and
anything that turned out not to be true. Commit. **Merge `phase5a-mirror` into
`main` only when I say the device verification passed.**

Then stop. I am going to use it day to day for a while before deciding
anything about 5b, and `docs/PHASE5_QUESTIONS.md` is where that decision gets
made. Do not start it.

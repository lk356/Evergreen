# Phase 5 questions — cross-device (Android to Android)

Written 2026-09-19, after Phase 4 was device-verified. Companion to
`docs/PLAN.md`. This document asks; `docs/PHASE5_DECISIONS.md` will answer.
Nothing here is decided yet.

**The roadmap changed.** Phase 5 was "cross-service, second service adapter";
it is now "cross-device, Android to Android". The reason is the user's own
ordering of what they want, in decreasing order:

1. sync between the Walkman and the Pixel, so stats can be read on a faster,
   nicer screen, and possibly managed from the Pixel;
2. capture on both devices, merged;
3. more services, primarily the Sony Walkman player for local files;
4. desktop capture, Linux first;
5. nice-to-haves beyond core functionality;
6. other players, services and operating systems, and a possible public
   release.

Cross-service moves to Phase 6. Desktop capture and the self-hosted backend
move to Phase 7. Everything from (5) onward is explicitly unplanned.

---

## Part 1 — What is settled going in, and is not reopened here

- **A score is computed, never accumulated** (invariant 1a). This is what
  makes cross-device sync tractable at all: two devices cannot merge running
  totals, because neither can tell which plays the other already counted.
  Shipping facts and recomputing locally has no such problem.
- **Segments store facts, never conclusions** (invariant 1b). No outcome
  column, now or ever.
- **Every fact table is append-only** — `listening_segments`, `like_events`,
  `evergreen_membership`, `parameter_history`. Derived tables (`scores`) are
  droppable and rebuildable.
- **The Walkman is the writer device** and the Pixel is the development and
  test device. `sync_state` already carries the per-device writer flag.
- **Privacy rules in `CLAUDE.md` do not relax.** No analytics, no crash
  reporting, no third-party SDKs, network access only to the two Spotify
  hosts. A sync transport that leaves the device is a new network
  destination and needs explicit approval.
- **The app is read-only over Liked Songs.** It never requests
  `user-library-modify`.
- **Instrumented tests destroy the database of the device they run on**, and
  the build refuses to run them with a Walkman attached.

### Closed since Phase 4

- **Volatility.** Settled at the current parameters. The score-over-time
  charts were built to answer it, the user has looked at them over their own
  listening, and the reading was that the scores behave well. Reopen only if
  a specific track the user still loves visibly falls out of Evergreen.

---

## Part 2 — Blocking checks, to do before any design is finalised

These are measurements, not builds. Both are cheap.

### Check 0a. Does a Spotify Connect remote device capture phantom segments?

Carried from Phase 3 as a curiosity. It is now on the critical path: if the
Pixel records segments for music actually playing on the Walkman, then
merging the two devices' captures inflates play counts, and the scoring is
wrong in a way that is invisible.

**Procedure.** Open Spotify on the Pixel with capture running. Play several
tracks on the Walkman, some to the end, some skipped. On the Pixel, check the
debug screen's live log and segment count for that window. Report counts and
aggregates only — never rows.

**Also check:** whether `MediaController.PlaybackInfo` distinguishes remote
from local playback. If it does, capture can drop phantoms at the source,
which is far better than deduplicating after the fact.

**If it does happen and `PlaybackInfo` cannot tell them apart**, the merge
can still catch it, because two segments overlapping in wall-clock time are
by definition at most one real listen. One person cannot listen to two things
at once. That makes this a deduplication rule rather than a dead end, but the
rule has to be designed rather than assumed.

### Check 0b. How much listening actually happens on the Pixel?

This decides whether full peer capture is worth building at all, or whether
a read-only mirror is the whole feature. Over the day-to-day use period
before Phase 5 starts, keep a rough count: what fraction of listening happens
on the Pixel rather than the Walkman? If it is small, question 1 below almost
answers itself.

---

## Part 3 — Design questions

### 3a. Mirror first, or full peer sync in one go?

**Option A — read-only mirror (recommended as 5a).** The Walkman exports its
database; the Pixel imports it and recomputes. The Pixel becomes a browsable,
fast copy that is as of the last import. No merge, no schema change, no
dependency on check 0a.

**Option B — full peer sync.** Both devices capture, facts flow both ways,
segments are merged. This needs segment identity, a device column, a
deduplication rule, and check 0a answered.

The case for A first: it delivers the single thing highest on the want list
(stats on a nicer screen) in a fraction of the work, and it proves the
foundation that B depends on — that shipping facts and recomputing locally
gives the same ranking on both devices. If that does not hold, it is much
better to find out before building a merge on top of it.

The case for B directly: A is throwaway if B lands soon after.

**Recommendation: A, then B as Phase 5b**, with the decision on B deferred
until A has been used for a while.

### 3b. In mirror mode, does the Pixel keep capturing?

If the Pixel's own captures are discarded at the next mirror import, leaving
capture running invites the belief that they counted for something.

**Recommendation: mirror mode turns capture off on that device**, visibly, and
turning it back on drops the device out of mirror mode. Revisit in 5b.

### 3c. Who writes Evergreen?

**Recommendation: the Walkman stays the sole writer**, unchanged. The
per-device flag in `sync_state` already exists for exactly this.

Consequence that must be built, not assumed: **a mirrored database must never
write to Spotify.** The imported copy carries the Walkman's `sync_state`,
including its writer flag and playlist id, so the import has to neutralise
device-specific state rather than adopt it wholesale.

### 3d. What is the transport?

**Option A — a file.** The Walkman writes a file through the system
create-document picker, exactly as the existing backup button does; it moves
to the Pixel by USB, adb, or any file-sharing app the user already has; the
Pixel imports it through the open-document picker. **No new network
destination, no new dependency, no server.**

**Option B — local network.** Devices talk directly over Wi-Fi. Faster to
use, meaningfully more code, and a new listening socket.

**Option C — a self-hosted server.** The eventual answer once Linux capture
exists, because a desktop agent has nowhere else to send its facts.

**Recommendation: A now, and design the sync *protocol* so the transport can
be swapped.** What crosses the wire should be defined independently of how it
crosses. Then the server in a later phase is a transport swap rather than a
rewrite.

### 3e. What crosses the wire?

**Recommendation: facts only**, with each device recomputing its own
conclusions. That means `tracks`, `service_tracks`, `track_merges`,
`listening_segments`, `likes`, `like_events` and `parameter_history` cross;
`scores` does not, because it is a cache with a parameters hash and rebuilding
it is the point.

**Open:** `snapshots` and `snapshot_entries`. They are historical records
taken at a moment, not recomputable exactly from facts alone (the liked set
is currently read as current state rather than derived from `like_events`).
**Recommendation: snapshots cross, one way, from the writer device only.**

**Open:** `evergreen_membership`. It records what the Evergreen playlist
actually did, which only the writer device knows. Same recommendation.

### 3f. Segment identity and a device column (5b, schema v7)

For a merge to be idempotent — importing the same batch twice changes nothing
— every segment needs an identity that is stable across devices. A row id is
not, because both devices number from 1.

**Recommendation: a device id column plus the existing local row id**, so
identity is the pair. Every existing row came from one device and can be
stamped in bulk, so nothing is lost by adding this later — but it is the one
change that is genuinely awkward once two databases have diverged, which
argues for doing it early even under option A.

**Open:** where the device id comes from. A random id generated once per
install and stored in `sync_state` is enough, and reveals nothing about the
device.

### 3g. Merge rules (5b)

- Two segments overlapping in wall-clock time are at most one real listen.
  Which one survives, and on what rule?
- `like_events` from two devices observing the same like: deduplicate on what
  key?
- Does a merge ever delete? **Recommendation: no.** Append-only means a
  phantom is excluded at read time, not removed, so the decision stays
  reversible — the same reasoning that kept the replay threshold out of
  capture.

### 3h. Direction

One-way Walkman to Pixel, or two-way? **Recommendation: one-way for 5a**,
which is what a mirror means, and revisit for 5b.

---

## Part 4 — Questions only the user can answer

1. **View, or manage?** Is the Pixel for reading stats, or do you also want to
   trigger a sync, edit parameters, and confirm playlist writes from it?
   Managing from the Pixel is a much bigger ask, because it means the Pixel's
   changes have to flow back.
2. **How stale can the mirror be?** A manual "export now / import now" pair is
   the least code. Automatic implies the network transport in 3d.
3. **Is it acceptable that running instrumented tests wipes the mirror?**
   They destroy the database of the device they run on, and the Pixel is the
   test device. Re-importing is cheap, but it will happen.
4. **Does the Pixel need Spotify connected at all?** If facts cross and the
   Pixel never writes, it does not. Not connecting it is one less place a
   refresh token lives.

---

## Part 5 — What the handoff should contain

For Phase 5b to be buildable, `docs/PHASE5_DECISIONS.md` needs:

- the result of check 0a, and of check 0b;
- an answer to 3a, and if it is "mirror first", whether 5b is still wanted
  after living with the mirror;
- the transport for 5b (3d);
- the exact table list for 3e, including the two open ones;
- the device-id scheme and schema v7 proposal (3f);
- the merge rules (3g);
- answers to all four questions in Part 4.

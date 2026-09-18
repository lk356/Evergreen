# Phase 3 briefing — what to decide before scoring is built

For the claude.ai "Playlist Manager" Project. Written at the end of Phase 2,
2026-09-17. Companion to `docs/PLAN.md`, which holds the full detail.

Phase 3 is the first phase that makes *judgements* about listening. Phases 0–2
deliberately made none: capture stores facts, and every question below was
pushed here on purpose. The data to answer them now exists.

---

## Part 1 — Settled. Please don't re-derive these.

**The data model works and is device-verified.** 380 likes synced, 100% ISRC
coverage, listening segments captured correctly on two devices including
replays, loops, seeks and crossfades.

**Segments store facts, not conclusions.** There is no outcome column and there
must not be one. Everything below is computed at read time from stored rows, so
any scoring rule can be changed later and recomputed over history already
captured. Nothing needs recapturing, ever.

**`listenedMs` and `endPositionMs` are different things and both matter.**
Position reached says where playback *got to*; listened-ms says how much was
*heard*. Three independent situations now confirm they must never be conflated:
a manual seek to the end, a DJ crossfade, and a seek-to-end auto-advance. In one
real row: 24 seconds heard, 92% position reached.

**Replay segmentation is reversible.** Capture cuts a new segment at *every*
backwards jump over 1000 ms and stores the jump size. `SegmentGrouping.merge()`
folds them back together at any threshold. So "what counts as a replay" is a
read-time decision, changeable forever, and the debug screen already shows the
effect of 3s / 10s / 30s on real history.

**Same-song merging is implemented.** Two canonical tracks merge when their
service rows share an ISRC. Found 2 pairs in 380 tracks, no false positives.
Likes are unaffected (they attach to service rows, not canonical tracks).

**The like archive keeps everything.** Unliking sets a timestamp and appends an
event; nothing is deleted. Like → unlike → re-like cycles stay fully visible.

---

## Part 2 — Hard constraints Phase 3 inherits

These are not preferences. They were verified against the live API in Phase 2.

| Constraint | Consequence for Phase 3 |
|---|---|
| **The Web API has no playlist folders.** Never has. | Snapshots cannot be organised into folders programmatically. Naming convention + one manual drag, or keep snapshots in-app. |
| `POST /users/{id}/playlists` was **removed** (Feb 2026). Use `POST /me/playlists`. | Verify every playlist endpoint against the live reference before building on it. |
| Writing playlists needs **`playlist-modify-private`**. | Phase 3 triggers a **new consent screen**. Expected, not a bug. |
| Development mode: **1 client ID, 5 users, owner must hold Premium.** | If the subscription lapses, the app stops working. |
| Quota is **unpublished** and enforced separately from rate limits. | Evergreen should be written at most daily. `QUOTA_EXCEEDED` backs off for hours. |
| Refresh token dies after **6 months**. | Already handled, but it means Evergreen sync silently pauses twice a year until reconnected. Worth deciding whether that needs a notification. |

---

## Part 3 — The decisions. This is what the discussion is for.

### 3a. What makes a song "still alive"? (the core formula)

The stated model is *plays add, skips subtract, time decays*. Four numbers are
undecided, and they interact:

1. **Decay half-life.** How long until an unplayed song halves in score? Weeks?
   Months? This single number largely defines what Evergreen *is*.
2. **Skip penalty.** How much does a skip subtract relative to what a play adds?
3. **Grace period for new likes.** How long is a freshly liked song protected
   before it can decay out? A song liked and not yet played must not
   immediately drop.
4. **Minimum listened time to count at all.** Real data includes 1–3 second
   fragments from rapid skipping. Do they count as skips, or as nothing?

**Useful framing:** a song listened to heavily for one week then never again —
should it survive six months later? The answer picks the half-life.

### 3b. How do replays count?

Phase 1 stores every backwards jump with its size and decides nothing.

- **What jump size counts as a replay at all?** Note: jumps of 1–2 s near
  position zero may be the player settling rather than a deliberate action. Real
  examples exist in the data and can be inspected.
- **Is a replay a second play, or a stronger signal than two ordinary plays?**
  Replaying is arguably the strongest positive signal a listener produces.
- **Repeat-one loops:** per loop, or once, with diminishing returns? A song
  looped 40 times overnight should probably not outscore everything else forever.

### 3c. How do seeks count?

A seek-skipped portion is time *not* listened. Should it be neutral, or a
negative signal (the listener actively avoided part of the song)?

**Complication:** DJ crossfade produces a forward seek that the *listener did not
perform*. Penalising forward seeks would penalise anyone using crossfade. The
stored data cannot tell a crossfade from a manual seek.

### 3d. Spotify DJ — now answerable, with one hard limit

Phase 2 examined this on the device. Two facts:

- **Commentary IS filterable.** It carries `spotify:media:…` rather than
  `spotify:track:…`, with zero duration. Trivially excluded.
- **A DJ-*chosen song* is NOT distinguishable from one the user chose.** Same
  package, ordinary track URI. Only the surrounding commentary rows and their
  timestamps reveal a DJ session was running.

**So the decision is:** a song the DJ picked was not picked by the user. Should
it count the same as a deliberate play, less, or not at all? And is "a DJ
session was running at this time" — inferred from neighbouring commentary rows —
reliable enough to act on, or is that too fragile to build scoring on?

DJ also auto-advances and invites heavy skipping, so **skip rates during DJ may
mean something different** from skips in a playlist the user built.

### 3e. What is Evergreen, as a playlist?

- **Membership rule:** a score threshold, a top-N, or both? 380 likes → how many
  songs should Evergreen contain? 50? 150? All that clear a bar?
- **One playlist, rewritten, or one per month?** Recommendation from Phase 2:
  one live `Evergreen` playlist kept current, with snapshots exported on demand
  as `Evergreen — 2026 August`.
- **Churn:** a song hovering at the threshold would join and leave repeatedly.
  Does it need hysteresis (harder to leave than to join)?
- **Is Evergreen meant to be played, or to be a record of what's alive?** These
  imply different sizes and different churn tolerances.

### 3f. What happens when a song is unliked in Spotify?

The archive keeps it forever — that is settled. But:

- Does it leave Evergreen **immediately**, or decay out normally?
- Immediate removal is the intuitive reading of "Evergreen = liked songs still
  being played", but it makes an accidental unlike destructive to the playlist.

### 3g. Seeding from extended streaming history

`PLAN.md` says the Spotify extended streaming history was requested and "arrives
later". **Has it arrived?** If so it changes Phase 3 substantially: scores could
start from years of real listening rather than from the few weeks this app has
captured. That would let the decay half-life be calibrated against actual data
rather than guessed.

If it has arrived, its format and date range are worth checking before designing
the scoring around it.

### 3h. Snapshots

- Monthly on a fixed day, or on the last day of the month?
- What exactly is stored — the track list, or the scores too? (Scores make
  "why was this in August" answerable later.)
- Stored in-app until explicitly exported: confirm that is still the intent.

---

## Part 4 — Questions only the user can answer

These are taste, not engineering. Everything in Part 3 depends on them.

1. **In your own words: what should it mean for a song to fall out of
   Evergreen?** Not played in N months? Skipped too often? Both?
2. **How big should Evergreen be?** A playlist you'd shuffle, or a complete
   record of what's currently alive?
3. **Should the app ever be wrong in one direction on purpose?** Keeping a song
   too long is a cluttered playlist; dropping it too early loses something you
   still liked. Which error is worse to you?
4. **Do you want to be told when things happen** — a song dropped out, the
   connection expired, a snapshot was taken — or should it stay silent?

---

## Part 5 — Suggested output of the discussion

For Phase 3 to be buildable, the handoff back should contain:

- The four numbers from 3a (half-life, skip penalty, grace period, minimum
  listened time) — even as rough starting values, since they will be tuned
  against real data.
- A decision on 3b, 3c and 3d.
- The membership rule from 3e, including target size.
- The unlike behaviour from 3f.
- Whether the extended streaming history has arrived (3g).

Scoring parameters should be **stored as settings, not constants**, so they can
be retuned without a migration and without recapturing anything. Phase 4 already
plans a settings screen for exactly this.

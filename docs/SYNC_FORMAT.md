# The Evergreen fact batch — wire format v1

Written 2026-09-20 for Phase 5b. Companion to `docs/SYNC_FOUNDATION.md`, which
argues *why* this exists; this document says *what it is*, precisely enough to
be implemented from scratch.

**A batch is: these facts, from this device, after this watermark.** Nothing
else. It is not a database, not a diff, and not a set of instructions — it is a
list of things that were observed, each one named in a way that two devices
cannot disagree about.

**It is deliberately not a SQLite file.** Shipping a `.db` works for Android to
Android and only for Android to Android. The Linux agent in Phase 7 reads MPRIS
and cannot produce a Room database; neither can a server written in anything
other than Kotlin. The moment the unit of exchange is a documented batch of
facts, the desktop agent becomes another client of the same protocol rather
than a special case. **Anything that can write UTF-8 text can produce a valid
batch, and this document is all it needs.**

> **A batch file is listening history.** It says what was played, when, and how
> much of it. It belongs outside the repository — `~/evergreen-backups/` or
> similar — and is covered by `.gitignore`. It holds no credentials: tokens
> live in encrypted storage outside the database entirely, so a batch
> physically cannot contain one.

---

## 1. The container

**JSON Lines.** One JSON object per line, UTF-8, `\n` line endings, no trailing
commas, no comments. The first line is the header; every line after it is one
fact.

```
{"t":"header", ...}
{"t":"seg", ...}
{"t":"seg", ...}
{"t":"like_event", ...}
```

Why this and not JSON, or CSV, or protobuf: a batch may hold 77,000 segments,
and the device producing it is a Sony NW-A307 with limited memory. JSON Lines
can be written and read a record at a time without either side ever holding the
whole thing, which a single JSON document cannot. It is also readable in a text
editor, which matters the first time something is wrong. Per
`docs/SYNC_FOUNDATION.md` 3.2 the specific choice matters far less than that
there *is* one.

**Reading rules.**

- A reader **must** ignore any field it does not recognise. That is what lets a
  later version add one without breaking an older reader.
- A reader **must** ignore any line whose `t` it does not recognise, and say how
  many it skipped. Same reason.
- A reader **must not** guess at a line it cannot parse as JSON. A malformed
  line stops the import; a batch is applied whole or not at all.
- Field order within a line is not significant. Line order *is*, for one type
  only: see `snap_entry` in §4.7.

**Suggested filename.** `evergreen-batch-<device>-<yyyy-MM-dd-HHmm>.jsonl`.
Nothing reads it; it is for humans sorting a folder.

---

## 2. The header

Exactly one, and it is the first line.

```json
{
  "t": "header",
  "format": "evergreen-batch",
  "formatVersion": 1,
  "schemaVersion": 7,
  "producerName": "NW-A307",
  "takenAtMs": 1789603200000,
  "origins": [
    {"deviceId": "6f1c9a3e-3c2f-4f1e-9a11-2d4e5b6c7d8e",
     "afterOriginId": 77518, "throughOriginId": 77602},
    {"deviceId": "spotify-history-import",
     "afterOriginId": 77369, "throughOriginId": 77369}
  ],
  "counts": {"seg": 84, "like_event": 2, "like": 380, "params": 0,
             "member": 3, "snap": 1, "snap_entry": 380}
}
```

| field | meaning |
|---|---|
| `format` | always the literal `"evergreen-batch"`. A reader refuses anything else rather than trying. |
| `formatVersion` | this document is version **1**. A reader refuses a version it does not know, in both directions, for the same reason the mirror import refuses a schema it does not know. |
| `schemaVersion` | the producer's database schema, **7** here. Informational: it explains a `t` a reader does not recognise. A reader does not refuse on it, because a batch is facts and not a table dump. |
| `producerName` | a model name such as `NW-A307`, for saying whose batch it is on a screen. Every unit of that model reports the same string, so it identifies a *kind* of device, not one. **Never used as identity.** |
| `takenAtMs` | when the batch was produced, epoch milliseconds. |
| `origins` | one entry per **originating device** whose segments the batch carries. See below. |
| `counts` | how many of each `t` follow. A reader compares and refuses a truncated file. |

### Why `origins` is a list

The obvious design is one batch, one device. It is wrong for a reason that is
easy to miss: **a device's database holds facts that originated on more than
one "device"**, and all of them are facts only that database has.

- Rows read from a Spotify **extended streaming history export** carry the
  fixed origin `spotify-history-import`, not the id of the phone that ran the
  import. That is deliberate and correct — an imported play was not observed by
  any device, and may have happened on a desktop this app has never run on.
  There are 77,369 of them today.
- A **reinstall** produces a new install id, so rows recorded before it carry
  the old one. They are still this database's to ship.

A batch keyed on one device would silently leave both behind, and "silently" is
the word that rules it out. So a batch enumerates its origins, and a single-
origin producer — a Linux agent, say — writes a list of one.

| field in an `origins` entry | meaning |
|---|---|
| `deviceId` | **the identity that matters.** For a real device, a random UUID generated once per install; not a hardware identifier, and it says nothing about the device or its owner. For imported history, the literal `spotify-history-import`. |
| `afterOriginId` | **the watermark this batch starts from for that device.** The batch contains no segment from it with an originating id at or below this number. `0` means "from the beginning". |
| `throughOriginId` | the highest originating id included for that device, or `null` if the batch carries none of its segments. |

**A producer ships only facts it originated.** In database terms that is
`originRowId IS NULL` — rows this database assigned the ids of, rather than
rows that arrived in someone else's batch. Passing on another device's facts
would mean two paths by which the same fact could arrive, which is a merge
question and belongs to Phase 5c.

A producer with no memory of a previous export writes `afterOriginId: 0` for
every origin and ships everything it has. That is a valid batch and the
receiver will take it; it is simply larger than it needs to be.

---

## 3. Identity — the part to get right

These rules come from `docs/SYNC_FOUNDATION.md` Part 4. They are **not the same
for every table**, because different kinds of fact are different kinds of claim,
and assuming otherwise breaks deduplication in ways that are invisible
afterwards.

**Never send a local row id and expect it to mean anything.** Every row id in
the producer's database is local to the producer. The only id that crosses is
`originRowId` on a segment, and it crosses *as data*, paired with the device
that assigned it.

| fact | identity | why |
|---|---|---|
| `seg` | `(deviceId, originRowId)` | A listen is something that happened *on a device*. Two devices recording the same minute are two independent observations, and which is real is a merge question — not this phase's. |
| `like_event` | `(serviceTrackId, event, observedAt, serviceAddedAtMs)` — **no device** | A like is not a local observation. It is an observation *of Spotify's state*, so two devices seeing the same like are seeing **one fact twice**. Giving it a device would make every like arrive twice and be impossible to collapse. |
| `like` | `serviceTrackId` | Current state, one row per service track. An upsert; applying it twice is applying it once. |
| `params` | `(changedAt, parametersHash)` | Append-only log. Two devices cannot produce the same pair by accident. |
| `member` | `(track identity, joinedAt)` | The writer's record of what its playlist did. Only the writer knows it, so nothing merges it. |
| `snap` | `monthKey` | One snapshot per month, already unique in the schema. |
| `snap_entry` | `(monthKey, rank)` | Belongs to the `snap` line above it. |

**Tracks are content-addressed and are never sent as rows.** There is no
`tracks` or `service_tracks` fact type. Instead every fact that refers to a
track carries a `track` object (§4.1), and the receiver resolves it through the
lookup it already uses for capture and for the streaming-history import:
service identifier first, `trackKey` only as a fallback. A producer therefore
does not need to know anything about the receiver's row ids, and a receiver
never trusts the producer's.

**`scores` never crosses.** It is a cache with a parameters hash. A score is
computed, never accumulated (invariant 1a), and the whole design rests on the
receiver recomputing rather than being told. A batch that contained scores
would hide exactly the disagreement worth finding.

**`sync_state` never crosses wholesale.** It is per-device state — the writer
flag, the playlist id, mirror mode, the like-sync cursor. The one part of it
that *is* about the data rather than the device is the scoring parameters, and
those cross as a `params` fact, not as a `sync_state` row.

---

## 4. The fact types

Unless a field is marked optional, it must be present. `null` is a value and
means "not observed", which is different from absent and very different from
zero.

### 4.1 The `track` object

Carried inside other facts; never a line of its own.

```json
"track": {
  "key": "electro swingity|willix|electro swingity",
  "title": "Electro Swingity",
  "artist": "Willix",
  "album": "Electro Swingity",
  "durationMs": 234000,
  "service": "spotify",
  "serviceTrackId": "spotify:track:4h3K5he5ItFqJB1MVHMKJV",
  "isrc": "GBKQU1600001"
}
```

- `key` is `title|artist|album`, each part trimmed and lower-cased, joined with
  `|`, with a missing part written as the empty string. A single derived key
  rather than three nullable columns, because SQLite treats two NULLs as
  different values and rows with a missing album would otherwise duplicate
  freely.
- `title`, `artist`, `album` may be `null`.
- `durationMs` is the length as the *producer* saw it, `0` if never reported.
  Sources disagree by a few hundred milliseconds — a media session rounds to
  the second, the Spotify broadcast is exact — so this is the duration
  belonging to this observation, not a global truth.
- `service` and `serviceTrackId` may both be `null`, for a play the producer
  could not attach to a service identifier. If one is present both must be.
- `isrc` is optional and may be `null`.

**How a receiver resolves it.** If `serviceTrackId` is known, adopt its
canonical track, whatever the metadata now says — a URI is exact and `key` is a
guess assembled from strings that change under you. Only when there is no
service id, or it has never been seen, fall back to `key`. Reversing that order
forks the same song into two canonical tracks depending on which path saw it
first; it is a bug this project has already had and fixed.

### 4.2 `seg` — a listening segment

One continuous stretch of listening to one track. Not one row per callback:
roughly one per song played.

```json
{"t":"seg","deviceId":"6f1c9a3e-...","originRowId":77519,"track":{...},
 "appPackage":"com.spotify.music",
 "startedAtMs":1789600000000,"endedAtMs":1789600180000,
 "startPositionMs":0,"endPositionMs":178000,
 "listenedMs":178000,"trackDurationMs":180000,
 "forwardSeekCount":0,"seekedForwardMs":0,
 "endReason":"TRACK_CHANGE","endJumpFromMs":null,"endJumpToMs":null,
 "lastSampleAgeMs":420,"createdAt":1789600180500,
 "source":"CAPTURE","sourceEndReason":null}
```

| field | notes |
|---|---|
| `deviceId` | which origin this segment belongs to. Must be one of the header's `origins`. |
| `originRowId` | the row id this segment has **on the device that recorded it**. Strictly ascending within one `deviceId`. Together with `deviceId` it is the segment's global name. |
| `appPackage` | the package that played it, always from the capture allowlist. |
| `startedAtMs`, `endedAtMs` | wall clock, epoch milliseconds. |
| `startPositionMs`, `endPositionMs` | position within the track. **Nullable.** Null means "not observed" — an imported row from a streaming-history export has no position at all — which is a different statement from `0`, "observed at the very start". |
| `listenedMs` | milliseconds actually heard: accumulated only while playback was running, scaled by playback speed, and **excluding** anything jumped over by seeking. Deliberately not the same as the position span, which is what stops a seek-to-the-end being mistaken for a completed play. |
| `trackDurationMs` | the duration as reported when this segment was captured, so any percentage from this row has a numerator and denominator from the same source. |
| `forwardSeekCount`, `seekedForwardMs` | nullable, for the same reason as the positions: "no seeks were seen" and "nobody was watching" are different. |
| `endReason` | one of `TRACK_CHANGE`, `POSITION_JUMP_BACK`, `SESSION_END`, `STALE`, `UNKNOWN`, `IMPORTED`. Every value is something *observed*. There is deliberately no `REPLAY`: what capture can see is that the position jumped backwards, and whether that means a replay is a judgement that belongs to scoring. |
| `endJumpFromMs`, `endJumpToMs` | for `POSITION_JUMP_BACK`, where the position jumped from and to; null otherwise. This pair is what makes the segmentation reversible — a later rule can add two rows back together, but it can never recover a jump that was not recorded. |
| `lastSampleAgeMs` | how old the last position reading was when the segment closed. Nullable. A large value means `listenedMs` leans on extrapolation rather than on something observed. |
| `createdAt` | when the producer wrote the row. |
| `source` | `CAPTURE` or `IMPORT_SPOTIFY_HISTORY`. Provenance is a fact, which is why it is allowed here. |
| `sourceEndReason` | the streaming-history export's own code (`trackdone`, `fwdbtn`, `endplay`, …) for an imported row; null on captured rows. |

**There is no outcome field, now or ever** (invariant 1b). No `COMPLETED`, no
`SKIPPED`. Those are conclusions, they depend on thresholds that are still
being tuned, and anything a scorer wants to conclude is recomputable from what
is here. A producer that invents one is producing a different format.

A new producer — a Linux agent, say — that has never exported before numbers
its segments from 1 and writes `afterOriginId: 0`. It must then number them
consistently forever after, because that number is its half of every segment's
name.

### 4.3 `like_event` — an observed change in like state

Append-only, written only on a *transition*. "Still liked, as it was yesterday"
is not an event; it is carried by `like.lastSeenLikedAt`.

```json
{"t":"like_event","track":{...},"event":"LIKED",
 "observedAt":1789500000000,"serviceAddedAtMs":1600000000000}
```

- `event` is `LIKED` or `UNLIKED`.
- `observedAt` is when the producer **noticed**, not when it happened. An
  unlike is noticed at the next sync, so this is an upper bound on the real
  time. Recording the observation rather than guessing the event time is the
  same discipline capture follows.
- `serviceAddedAtMs` is Spotify's own `added_at` where known, `null`
  otherwise — generally years earlier than `observedAt`.

**No device field, deliberately.** See §3.

### 4.4 `like` — current like state

One per service track the producer knows a like for. An upsert.

```json
{"t":"like","track":{...},"serviceAddedAtMs":1600000000000,
 "firstSeenLikedAt":1789000000000,"lastSeenLikedAt":1789600000000,
 "unlikedAt":null}
```

`unlikedAt` is null if and only if the track is liked now. Unliking never
deletes anything: the row stays, gains a timestamp, and the archive keeps the
round trip.

This type exists alongside `like_event` because the ranking reads current state
directly rather than deriving it from the event log. Sending both means a
receiver never has to infer, and an inference rule that drifted from the
producer's would be a silent disagreement about which songs are liked.

### 4.5 `params` — a scoring parameter change

```json
{"t":"params","changedAt":1789200000000,
 "parametersJson":"{\"halfLifeDays\":45.0,...}",
 "parametersHash":"a1b2c3d4",
 "previousJson":null,
 "current":true}
```

`parametersJson` is stored verbatim, byte for byte, because a snapshot stores
the same string and the two have to match for a snapshot to be readable under
the settings that produced it. `previousJson` is null only for the first entry
ever written.

`current: true` marks the entry in force when the batch was taken; **at most
one line in a batch carries it.** A receiver that applies the facts but not the
parameters would compute a different ranking from the same history, which is
the one outcome this phase exists to rule out.

### 4.6 `member` — Evergreen playlist membership

The writer's record of what its playlist actually did. Only the writer knows
it, so nothing merges it.

```json
{"t":"member","track":{...},"joinedAt":1789000000000,"leftAt":null,
 "joinReason":"JOINED_BY_RANK","leaveReason":null,
 "rankAtJoin":42,"rankAtLeave":null}
```

Reasons are `JOINED_BY_RANK`, `LEFT_BY_RANK`, `LEFT_UNLIKED`,
`REJOINED_BY_LIKE`.

**This is one of the fact types that changes after it is written**: a row is
opened at join and closed later by setting `leftAt`. There is therefore no
ascending id that could watermark it, so **a batch carries every membership
row** and the receiver upserts on `(track, joinedAt)`.

That is the general rule for everything except segments, and §5 says why.

### 4.7 `snap` and `snap_entry` — a monthly snapshot

```json
{"t":"snap","monthKey":"2026-08","label":"2026 August",
 "takenAt":1788000000000,"parametersJson":"{...}",
 "playlistSize":100,"trackCount":380,
 "exportedPlaylistId":null,"exportedAt":null}
{"t":"snap_entry","monthKey":"2026-08","track":{...},"rank":1,"score":18.4,
 "serviceTrackId":"spotify:track:aaa","title":"...","artist":"...",
 "inPlaylist":true}
```

**A batch carries every snapshot and every entry**, for the reason in §5. Today
that is a few hundred lines; it grows by roughly one line per liked track per
month, so a batch taken years from now will be mostly snapshot entries. When
that starts to matter, the fix is a per-type watermark in the header, which a
version 1 reader will ignore exactly as §1 requires.

Every `snap_entry` names its `monthKey`, so strictly the order does not matter
— but a producer **should** write a `snap` immediately followed by its entries,
because a receiver streaming the file can then insert them without holding the
whole ranking in memory.

The **whole** ranking is stored, not only the tracks that were in the playlist.
That is what makes both "why was this in August" and "what was still alive in
August" answerable later. `serviceTrackId`, `title` and `artist` are copied in
rather than looked up, so a track that has since gone unavailable is still
visible in the month it was alive.

`parametersJson` matters for the same reason as in §4.5: a snapshot records
scores, and a score means nothing without the settings that produced it.

---

## 5. What a receiver does

In order, and the order is the whole of the safety argument.

1. **Read the header.** Refuse an unknown `format` or `formatVersion`, saying
   which. Nothing has been changed.

2. **Refuse a batch with no baseline**, checked per origin. If an origin's
   `afterOriginId > 0` and this database holds no segment from that
   `deviceId`, the batch is a delta against something that is not here. Say so,
   naming the device, and say that a whole mirror import has to come first. An
   origin with `afterOriginId == 0` is self-contained and needs no baseline.

3. **Refuse a batch that would leave a gap**, also per origin. Compute this
   database's watermark for each:

   ```sql
   SELECT MAX(COALESCE(originRowId, id))
     FROM listening_segments
    WHERE deviceId = :deviceId
   ```

   `COALESCE` and not plain `id`, because rows that arrived in a **whole-file**
   mirror import carry the producer's own ids in `id` with `originRowId` null,
   while rows that arrived in a batch carry the producer's id in `originRowId`
   and a local one in `id`. The expression gives the right answer for both, and
   that single fact is what lets 5a's transport and 5b's coexist.

   If `afterOriginId > watermark`, refuse: the batch begins after what is held
   and the segments in between would be lost silently. Say what is missing —
   "this batch starts at 77,600 and this device holds up to 77,518; 81 plays
   are missing".

   If `afterOriginId <= watermark`, accept. The overlap is not a problem; step
   4 discards it.

   **One origin failing refuses the whole batch.** A batch is applied whole or
   not at all, and applying the parts that happened to fit would leave a
   database nobody could describe.

4. **Apply the facts, in one transaction.** Either the whole batch lands or
   none of it does. Half a batch is a watermark that lies.

   - `seg`: skip any whose `originRowId` is at or below that origin's
     watermark — that is where idempotence actually comes from. Insert the rest
     with the line's own `deviceId` and `originRowId`. The unique index over
     `(deviceId, originRowId)` is the backstop if the watermark is ever wrong.
   - `like_event`: insert unless an identical one is already present, on the
     identity in §3.
   - `like`: upsert on the resolved service track.
   - `params`: insert unless `(changedAt, parametersHash)` is present. Apply
     the one marked `current` as the settings in force.
   - `member`: upsert on `(trackId, joinedAt)`.
   - `snap` / `snap_entry`: upsert the snapshot on `monthKey`; replace its
     entries.

5. **Drop the score cache.** Not "update" — drop. The facts underneath it have
   changed, and a cache that survives a change to its inputs is the
   accumulation invariant 1a forbids. The next read recomputes, which is the
   point: the same facts and the same parameters must give the same ranking on
   either device.

6. **Do not adopt anything about the producer as a device.** Not its writer
   flag, not its playlist id, not its mirror state. A mirror that has had
   deltas applied is still a mirror.

### Why only segments are watermarked

Every other fact type is sent **in full, every time**. That is a deliberate
choice and not an oversight.

A watermark is only safe when the **receiver** can check it, and the receiver
can only check something it can compute from what it holds — which for
segments is `MAX(COALESCE(originRowId, id))` per device. There is no equivalent
for the rest:

- `like` and `member` rows **change in place** after they are written, so no
  ascending id could describe "what is new".
- A watermark the *producer* remembers instead — "everything since my last
  export" — cannot be checked by anyone. If a batch went somewhere else in
  between, or a whole-file import landed in between, the receiver would be
  missing facts and nothing would say so. Silent staleness is the one failure
  mode this design will not accept.

So the rest is sent whole and deduplicated on arrival, which is always correct
and costs file size. Today that floor is a few hundred lines — mostly one
`like` per liked track. It grows with snapshots, and §4.7 says what to do when
it matters.

### Two properties that follow

**Applying a batch twice changes nothing.** After the first application each
origin's watermark has moved past its `throughOriginId`, so the second
application skips every segment; the other types deduplicate on the identities
in §3. This is the property the whole design rests on, and it is what makes a
transport that might deliver a file twice — a folder re-synced, a copy
repeated, a connection retried — safe rather than a corruption risk.

**Applying two batches in either order gives the same database.** Segments are
keyed on `(deviceId, originRowId)` and not on arrival; everything else is
content-addressed or an upsert. Nothing in the format depends on when a line
was read.

---

## 6. What is deliberately not in version 1

- **No transport.** A batch is a file. `docs/SYNC_FOUNDATION.md` opens by
  explaining why there is no `SyncTransport` interface, and nothing has
  changed: the payload is identical whether it is written to a folder, POSTed
  to a server, or pushed down a socket, and that is what "swappable" means.
- **No deletes.** Every fact table in Evergreen is append-only, and a merge
  never deletes. A phantom segment is excluded at read time, not removed, so
  the decision stays reversible.
- **No merge.** A batch says what one device observed. Deciding what to do when
  two devices observed overlapping things is Phase 5c, and it is gated on the
  phantom fix in `capture/`.
- **No timezone.** A segment carries wall-clock milliseconds and no zone. The
  daily cap buckets listening by *local* calendar day, so two devices in
  different places could cap the same listening differently — a real hazard,
  recorded rather than fixed, and a Phase 5c decision because it only bites
  when two devices both produce facts. Adding a zone later is a new optional
  field, which a version 1 reader will ignore exactly as §1 requires.
- **No compression.** A batch of a few hundred segments is a few hundred
  kilobytes. If it ever matters, gzip is a property of the file and not of the
  format.

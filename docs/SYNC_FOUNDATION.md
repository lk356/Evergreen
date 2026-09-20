# Sync foundation — what keeps the options open

Written 2026-09-19, after Phase 5a was device-verified and merged. Companion
to `docs/PLAN.md`; `docs/PHASE5_QUESTIONS.md` is where 5b's design gets
decided and this does not decide it.

**Why this exists.** The stated goal is to keep every future option open —
Syncthing, a self-hosted server, a cloud service, peer-to-peer, and a hosted
multi-user version in the far future — without building any of them now. That
is the right instinct, and it is worth writing down precisely what does and
does not serve it, because the obvious answer is the wrong one.

**The obvious answer is wrong.** Writing a `SyncTransport` interface with one
implementation does not keep options open. It guesses what four unbuilt things
will need, from the one that exists. It is the textbook shape of speculative
generality: the abstraction gets built around the file transport's
assumptions, and the first real server breaks it anyway.

---

## Part 1 — What actually makes a transport swappable

Three properties of the **data**, not of the code that moves it. If they hold,
every transport is a dumb pipe and the choice is genuinely reversible. If they
do not, no interface rescues it.

1. **A fact can be named globally.** Two devices must not be able to invent
   the same name for different facts, or different names for the same fact.
2. **Facts can be ordered.** "Everything after X" must be a question with an
   answer, or every sync is a full dump.
3. **Applying a batch twice changes nothing.** Idempotence is what makes an
   unreliable transport — a dropped connection, a file copied twice, a folder
   re-synced — safe rather than a corruption risk.

With those, the transports differ only in who carries the bytes:

| transport | what it does with a batch of facts |
|---|---|
| a file | writes it |
| Syncthing | writes it into a synced folder |
| self-hosted server | `POST`s it |
| a hosted service, far future | the same `POST`, a different host |
| peer-to-peer | opens a socket and sends it |

The payload is identical in all five. That is what "swappable" means, and it
is the whole of the design.

---

## Part 2 — What is already built

More than expected, and the expensive part is done.

**Segments already carry the device that recorded them.** Since Phase 1,
`listening_segments.deviceId` exists and is indexed, and its own comment says
what it is for. So a segment's global name is already the pair
`(deviceId, id)` — a locally-assigned row id, made unique by the device that
assigned it.

This matters more than it looks. `docs/PHASE5_QUESTIONS.md` 3f calls a device
column *"the one change that is genuinely awkward once two databases have
diverged"*, and recommends doing it early even under the mirror. It was
already done, three phases early, by a decision made for a different reason.

**The device id is already right.** `capture/DeviceId.kt` generates a random
UUID once per install, stored in SharedPreferences, excluded from cloud backup
and device transfer. It is not a hardware identifier and reveals nothing about
the device or its owner. A reinstall produces a new one, which is correct: a
fresh install has no history to attribute.

**A score is computed, never accumulated** (invariant 1a). This is what makes
sync tractable at all, and Phase 5a proved it holds across devices — same
facts and parameters, same ranking, whatever the row order and whenever it is
recomputed (`CrossDeviceRankingTest`). Two devices cannot merge running
totals, because neither can tell which plays the other already counted.
Shipping facts and recomputing locally has no such problem.

**Every fact table is append-only** — `listening_segments`, `like_events`,
`evergreen_membership`, `parameter_history`. Nothing is updated in place and
nothing is deleted, so a row's meaning never changes after it is written.
That is what allows a batch to be applied twice harmlessly.

**Derived tables are droppable.** `scores` can be emptied at any moment and
rebuilt; the mirror import does exactly that. Nothing derived ever has to
cross.

---

## Part 3 — What is missing

Three things, in the order they should be built.

### 3.1 "What is new since X" is not yet a question with an answer

Today the only answer to *what does the other device not have* is **the whole
database** — which is why a mirror ships 21 MiB for what is usually a few
hundred new rows.

Making it answerable is nearly free, because segments are append-only with
ascending per-device ids. *"Everything from device D after local id N"* is a
query that can be written against the schema as it stands. The receiving side
needs one small piece of state: the highest id it has seen from each device.

That is the single change that converts the transport question from a
commitment into a preference.

### 3.2 There is no wire format that is not a SQLite file

**Written down 2026-09-20 in `docs/SYNC_FORMAT.md`, and built in Phase 5b.**
The rest of this section is the argument that produced it, kept as written.

Shipping a `.db` file works for Android to Android and **only** for Android to
Android. It is a ceiling rather than a foundation, and the thing it forbids is
already on the roadmap: a Linux agent reading MPRIS (Phase 7) cannot produce a
Room database. Neither can a server written in anything other than Kotlin.

The moment the unit of exchange is a documented batch of facts — JSON lines or
similar, the specific choice matters far less than that there is one — the
desktop agent becomes another client of the same protocol rather than a
special case, and a future server can be written in whatever suits it.

### 3.3 Identity rules are not written down per table

They are not the same for every table, and assuming they are would break
deduplication. See Part 4.

---

## Part 4 — Identity, table by table

The instinct to add `deviceId` everywhere for consistency would be a mistake.
Different kinds of fact have different identities, because they are different
kinds of claim.

**`listening_segments` — device-scoped.** Identity is `(deviceId, id)`. A
listen is something that happened *on a device*; two devices recording the
same minute are two independent observations, and which of them is real is a
merge question (see the phantom problem below). Already built.

**`like_events` — content-addressed, and deliberately has no device column.**
A like event is not a local observation. It is an observation *of Spotify's
state*, and two devices seeing the same like are seeing **one fact twice**,
not two facts. Its identity therefore has to come from its content —
something along the lines of `(serviceTrackRowId, event, serviceAddedAtMs)` —
so that the second device's copy deduplicates against the first. Adding
`deviceId` here would make every like arrive twice and be impossible to
collapse.

**`tracks`, `service_tracks` — already content-addressed.** `trackKey` and
`(service, serviceTrackId)` are stable across devices by construction. Row ids
are local and must be translated on import rather than trusted, which the
existing `resolveServiceTrack` path already does.

**`evergreen_membership`, `snapshots`, `snapshot_entries` — one-way, from the
writer only.** They record what the playlist actually did and what the ranking
was at a moment, which only the writer device knows. Nothing merges them.

**`scores` — never crosses.** It is a cache with a parameters hash, and
rebuilding it locally is the point.

**`sync_state` — never crosses wholesale.** It is per-device state, and Phase
5a exists partly to prove that: a mirror arrives carrying the writer device's
own `sync_state` and has to be neutralised before it lands.

---

## Part 5 — Ordering constraints

Two things genuinely have to happen in a particular order. Everything else can
be reordered freely.

### 5.1 The phantom fix must land in `capture/` before any merge

Measured 2026-09-19 (see `docs/PLAN.md`): with Spotify open on the Pixel and
audio playing on the NW-A307, the Pixel recorded **three segments for music it
was not playing**, one of which would have scored as a full play and one as a
rejection. Nothing on the row marks them as second-hand.

Any merge built before this is fixed inherits inflated counts, invisibly, and
the rows it inherits are ones nobody will later be able to identify. The fix
is known and cheap — `MediaController.PlaybackInfo.getPlaybackType()` returns
`PLAYBACK_TYPE_REMOTE` for these, confirmed set by Spotify via
`dumpsys media_session` — but it is a `capture/` change, and it has to come
first.

**This is the only hard prerequisite in the whole design.**

### 5.2 Identity before divergence — already satisfied

A device column is cheap to add while every row came from one device, and
awkward afterwards. That window is still open today and it is already used.
Nothing further is owed here, but it is worth knowing that if `like_events`
ever *does* need per-device provenance, the same window logic applies to it.

---

## Part 6 — What the transports each need

Given Part 1, briefly, and none of this needs deciding now.

**Syncthing on the backup file — works today, no code.** One-way is safe in a
way bidirectional never is, because the Walkman is the only writer: send-only
on the Walkman, receive-only on the Pixel, and the conflict problem does not
arise rather than being managed. **Never point any file syncer at the live
`evergreen.db`** — in write-ahead-logging mode it is three files and a copy
taken mid-write is torn. The backup file is `VACUUM INTO` output, a
consistent self-contained snapshot, safe to copy by construction. That is why
the backup button works the way it does.

What it does not automate is *taking* the backup, which is still a button. A
contained feature would close that: pick a folder once, and a worker writes a
dated backup there. No network code in the app at all.

**A self-hosted server** is the eventual answer regardless, because the Linux
agent has nowhere else to send facts. Building it for cross-device first makes
the desktop agent a client of something that already exists, rather than the
reason to build it.

**Peer-to-peer** is the most work for the least gain: discovery, NAT
traversal, auth and encryption, to solve a problem that is only easy when both
devices are on one network — which is the case where every other option is
also easy.

**A hosted service** is the same POST as the self-hosted one. If Parts 1 to 4
hold, it is a deployment question rather than an architecture question.

---

## Part 7 — Multi-tenancy, and what is actually forced

**Corrected 2026-09-19**, after the first version of this argument overreached.

The Spotify finding under Phase 9 is real: dev mode caps at five users,
extended quota needs a registered business and 250,000+ monthly active users,
so there is no path from a hobby project to a hosted multi-user *Spotify*
integration. **That is a limit on one service's developer terms, not on the
app.** Phase 6 makes Evergreen multi-service, and other services are believed
to be less restrictive — Deezer and Apple Music were named as likely
examples. **Neither has been checked. Both must be, before anything is built
on the assumption.**

So a hosted version offering every service except Spotify, with Spotify
available to anyone self-hosting or supplying their own client id, is
coherent. Bring-your-own-client-id remains the right answer for Spotify
specifically.

What that means in practice:

- **Do not build multi-tenancy.** It is far future, and threading an account
  dimension through every table before a second user exists is paying twice.
- **Do not make it impossible either.** The practical form of that is Parts 1
  to 4 — facts that can be named globally, ordered and applied twice. A future
  server needs exactly that, and so does cross-device sync, so the cost is
  already being paid for a reason that stands on its own.
- **Bring-your-own-client-id is not a fallback.** For Spotify it is the
  design, whatever else happens.

---

## Part 8 — What to build next, and what not to

**Next, if anything: the fact batch, exercised over the file transport that
already exists.** Not a transport abstraction. A documented format for "these
facts, from this device, after this watermark", written to a file and read
back.

It earns its place three times over: a mirror update becomes kilobytes rather
than 21 MiB; it is the thing all five transports need; and it forces the
identity and deduplication rules in Part 4 to be settled by a working
implementation rather than on paper. If 5b is never built, almost nothing is
wasted.

**Not to build:**

- A `SyncTransport` interface with one implementation. See the top of this
  document.
- A merge, before the phantom fix. See 5.1.
- Multi-tenancy. See Part 7.
- Automatic *import* on the mirror. Automating the transport is reversible
  and inspectable; a daemon silently replacing a database on a schedule is
  not, and it would cost the property that makes the Pixel pleasant to test
  against — that its copy is disposable, because it is only ever a copy.

**And the cheapest thing on the list remains check 0b**: what fraction of
listening actually happens on the Pixel. It is one number, gathered by using
both devices for a few weeks, and it collapses most of the decision. If the
answer is "almost none", a read-only mirror is the whole feature and only the
transport wants automating.

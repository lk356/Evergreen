# Phase 5c step 7 — what the measurements found, and what to build

Written 2026-09-22, at the end of a long session, to hand the remaining work to
a fresh one. **Everything here is measured, not reasoned.** Full figures are in
`docs/PLAN.md` under "Phase 5c step 7a"; this is the summary a builder needs.

> **Read this before touching the phantom fix.** Three designs have now been
> written against signals nobody had checked, and all three were wrong. The
> rule for this step is the user's: *measure it before designing for it.*

---

## 1. Where the branch stands

`phase5c-merge`, ten commits, **326 unit tests and 269 instrumented tests
passing**. Steps 0–6 and 8 of `docs/PHASE5C_PROMPT.md` are built and committed:

- two-way sync, with two real holes found and fixed (a mirror re-exporting
  77,816 rows it did not originate; an older settings change rolling back a
  newer one);
- the writer role, released and claimed, never shared;
- schema v8, verified on the real 78,110-row database with **zero** wrong
  offsets;
- the overlap tripwire;
- the screens.

**It must not merge to `main` yet.** `docs/SYNC_FOUNDATION.md` §5.1 makes the
phantom fix the one hard prerequisite for the merge, and as built the fix does
not work — see below. Merging now would do exactly the harm the prerequisite
exists to prevent: the Pixel marked **all 18** of its segments `LOCAL` in the
final test, so it would export every phantom to the Walkman and inflate play
counts invisibly.

---

## 2. The step 2 fix does not work, and here is the evidence

`MediaController.PlaybackInfo.getPlaybackType()` was measured against ground
truth — which device actually had an active media player — over 251 paired
samples from both devices during a nine-handoff session.

| device | truth | its own `volumeType` | samples | |
|---|---|---|---|---|
| Pixel | not playing | `LOCAL` | 103 | ✗ missed every phantom |
| Walkman | not playing | `LOCAL` | 36 | ✗ missed |
| Walkman | not playing | `REMOTE` | 66 | ✓ caught |
| Pixel | playing | `LOCAL` | 102 | ✓ |
| Walkman | playing | `LOCAL` | 89 | ✓ |
| Walkman | playing | `REMOTE` | 14 | ✗ would discard real listening |

- **It catches 32% of phantoms** (66 of 205). The Pixel called itself `LOCAL`
  **103 times out of 103** while producing no sound.
- **It also discards real listening**: 14 samples where the Walkman called
  itself remote while it was the device making the sound.
- `volumeControlId`, held out as a second independent signal, was `null` on
  both devices throughout. It is not a backup.

**Two failure modes at once**, confirmed at the segment level: all 18 Pixel
segments stored `LOCAL` (it would count every phantom), while 5 of the
Walkman's 7 stored `REMOTE` (it would discard real listens).

### The decision to record rather than drop is vindicated

Step 2 chose to store the observation and exclude at scoring time rather than
drop the segment in `capture/`. That was the right call for a stronger reason
than the one argued at the time: had it dropped at source on this signal, it
would have **permanently destroyed 14 samples of real listening and still kept
139 samples of phantoms**. Recording is the only reason this is recoverable.

---

## 3. Two things that turned out not to be true

**A handoff does not split a listen.** Step 7's whole premise was that a track
moved mid-play leaves two partial plays where there was one full one. It does
not: Spotify Connect syncs position to every device holding the session, so
**both devices record the whole listen**. On one track the Walkman recorded
0.93 and the Pixel 0.93, ending at the identical position (254,678 ms).
**There is nothing to stitch, and 7b's stitching rule should not be built.**

**The flag is not noisy; the rule on top of it was.** The first reading of the
data was that `volumeType` oscillates unreliably. The user pushed back — a flag
moving local→remote over minutes is a faithful record of the handoffs they
performed. Counting transitions settled it: nine handoffs, nine Pixel
transitions, minutes apart. The damage came from `PlaybackType` being **sticky
within a segment**: a handoff happens mid-track, so a segment spans a local
stretch and a remote stretch, and stickiness condemned the whole row —
including on the device that played most of it. That cost **23.6 of 28.5
minutes (83%)** of a real session, scoring nowhere at all.

**And the step 6 tripwire is blind to it.** Run against the real database after
that session it reports **0**, because it excludes `REMOTE` rows and both sides
were wrongly marked. The check meant to catch the phantom fix failing cannot
see its worst failure.

---

## 4. The signal that does work, verified in the app

Whether the device is **actually producing media audio**. Over the same 251
paired samples it identified exactly one owner in **249 (99.2%)**; the two
exceptions are single 2-second samples at handoff instants.

Verified against `android.jar` for API 37, and then **verified in the app** by
`ActivePlaybackVisibilityTest` (kept in the tree; it is a probe, not a rule):

| scenario | its own session | its audio output | what the app sees |
|---|---|---|---|
| other device playing — phantom | `PLAYING`, `vol=LOCAL` | 0 players | **0 configurations** |
| this device playing — real | `PLAYING`, `vol=LOCAL` | 1 player | **1**, `usage=USAGE_MEDIA` |

Available to an unprivileged app:

- `AudioManager.getActivePlaybackConfigurations()` — **not redacted**
- `AudioManager.registerAudioPlaybackCallback(…)` — live, so no polling
- `AudioPlaybackConfiguration.getAudioAttributes()` — gives `usage`/`contentType`

**Redacted, and it costs something:** `getAudioDeviceInfo()` returns **null**
for type and name. The app can know *that* it is producing media audio, not
*where the sound is going*. **The user's Bluetooth/wired question therefore
stays open** — it cannot be recorded as a fact this way.

An app cannot see which app owns a configuration, and does not need to: if an
allowlisted session claims `PLAYING` while this device produces no media audio
at all, that is a phantom.

---

## 5. What to build, and what it costs

**The rule is the user's, and it is better than what is there.** Instead of
"exclude a remote segment", it is:

> Exclude a segment only when **another device demonstrably produced the sound**
> for the same track at the same time. Otherwise count it — even if this device
> called itself remote.

It fails safe in the right direction. The current rule excludes on suspicion
and loses real listening; this one counts unless something better contradicts
it. On the measured session that is the difference between losing 83% and
losing none. It also preserves the cast-to-a-speaker case that step 2's whole
argument rested on: play to a laptop with both phones open, both record
phantoms, and the listen counts **once** rather than never.

**Three parts:**

1. **`capture/` records whether this device was producing media audio** during
   the segment, via `registerAudioPlaybackCallback`. Alongside `playbackType`
   rather than replacing it — that field is not useless (66 correct catches)
   and it is a fact that was observed.
2. **Scoring applies the rule above**, replacing the flat "exclude `REMOTE`".
3. **Drop the stickiness**, which is what turned a faithful signal into an 83%
   loss. Consider instead cutting a new segment when the value changes, the way
   capture already cuts on a position jump — but **measure before building
   that**, since it changes how capture segments.

**Needs approval before building:** this is a change to `capture/` beyond what
step 2 was approved for, and it revisits a decision already taken. Show the
diff and wait, as step 2 did.

**Also to revisit:** step 6's tripwire needs to count something it can still
see once both sides may be wrongly marked — otherwise it stays blind to the
failure it exists to catch.

---

## 6. Open, and not to be forgotten

- **Where the sound goes.** `getAudioDeviceInfo()` is redacted, so whether
  Bluetooth or wired output perturbs the session is unanswered. The Walkman did
  have wired-headphone connect/disconnect events inside one test window; the
  Pixel had none and its flag still moved, so routing is a contributor and not
  the cause.
- **A device that records a listen nobody heard.** Handing playback to a
  sleeping Pixel recorded a listen, but no audio reached the speakers until the
  user touched the fingerprint reader. No rule here catches that; the
  active-audio signal is the only candidate that would.
- **Two ambiguous samples per session** at handoff instants — one with both
  devices producing audio, one with neither while a session claimed `PLAYING`.
  Any rule must tolerate a ~2 s gap rather than treat it as "nobody played
  this".
- **Steps 9 and 10** of `docs/PHASE5C_PROMPT.md` — the cross-cutting tests and
  the device-verification checklist — are not started, and step 9's list needs
  rewriting around the rule above rather than the one it was written for.

---

## 7. Useful mechanics for whoever picks this up

- **Serials change.** The Pixel is on wireless debugging; its serial contains a
  colon, so `${pair##*:}` splits it wrongly. Use `${pair#*=}` with `name=serial`
  pairs. Re-read serials rather than trusting any written down.
- **Clock skew** was **Pixel ~1,109–1,128 ms behind the Walkman**, stable
  across USB and wireless, and it drifts by a few hundred ms a day. Measure it
  at the start of every session and correct for it.
- **Instrumented tests uninstall the app**, taking notification access and the
  device id with them. To run one without that, build with
  `assembleDebug assembleDebugAndroidTest`, `adb install -r` both APKs, and
  drive it with `adb shell am instrument -w -e class <class>
  com.example.evergreen.test/androidx.test.runner.AndroidJUnitRunner`.
- **The Gradle guard refuses instrumented tests with a Walkman attached.** Do
  not override it. The Walkman keeps playing while unplugged from USB, which is
  what made the probe possible.
- Notification access can be granted over adb:
  `adb shell cmd notification allow_listener com.example.evergreen/com.example.evergreen.capture.CaptureNotificationListenerService`
- **Privacy, unchanged:** counts and aggregates only, track identity reduced to
  an index, no row of the database or of a batch file in any message.

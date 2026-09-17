# Evergreen — project instructions for Claude Code

## What this is
Evergreen is a personal Android app that keeps a "living" liked-songs playlist.
Songs the user likes enter an Evergreen playlist; songs that stop getting played
(or get skipped) decay out of it, while the full like history is kept as an archive.
Monthly snapshots of Evergreen are stored and searchable (e.g. "2026 August").

The roadmap, current phase, and decision log live in `docs/PLAN.md`.
Read it at the start of every session. Update its Status section at the end of every task.

## Working with this user
- The user is new to software development. Explain what you are doing and why,
  in plain language, briefly. Define jargon the first time it appears.
- Prefer small, working steps. Commit after each working step with a clear message.
- Before any large change (new module, new dependency, schema change, refactor),
  propose a plan and wait for approval.
- When something needs to be done on a physical device or in a web dashboard,
  give exact click-by-click instructions.

## Architecture (target)
1. **Capture**: an Android service reads media sessions (NotificationListenerService +
   MediaSessionManager) and the Spotify "Device Broadcast Status" intents, and records
   listening events.
2. **Local database** (Room): canonical tracks, per-service track IDs, listening events,
   likes, scores, Evergreen membership, snapshots.
3. **Scoring**: plays add, skips subtract, time decay, grace period for new likes.
   A hand-written formula — never a trained ML model (Spotify Developer Terms).
4. **Service adapters**: one interface (`MusicService`) with implementations per
   streaming service. Spotify first. Adapters only read likes and write playlists.
5. **Scheduling**: WorkManager for like sync, Evergreen sync, monthly snapshots.
Later phases add a self-hosted sync backend and Windows/Linux desktop agents.

## Stack and conventions
- Kotlin, Jetpack Compose, Room, WorkManager, Kotlin coroutines/Flow.
- minSdk 31 (kept as a floor; the Sony NW-A307 runs Android 14, build 3.06.00).
  Also tested on a Pixel 10 Pro.
- Keep the app lightweight: the NW-A307 has limited RAM and CPU.
- Design the data model to support multiple services from day one
  (canonical track + per-service IDs, ISRC where available).
- Write unit tests for scoring logic and skip detection.

## Privacy and security rules (non-negotiable)
- Only process media sessions from an explicit allowlist of app package names.
  Discard every other notification immediately. Never store or log notification
  content from non-allowlisted apps.
- Record playback metadata only: track, artist, album, duration, position,
  timestamps, app package, device. No location, contacts, or message content.
- No analytics, advertising, or crash-reporting SDKs that send data to third parties.
- Network access only to music service APIs (and later, the user's own backend).
- Spotify auth: Authorization Code with PKCE. No client secret in the app.
- Store tokens encrypted with a key held in the Android Keystore.
- Request the minimum OAuth scopes needed. Create playlists as private.
- Exclude the database and tokens from Android cloud backup
  (dataExtractionRules / fullBackupContent).
- Secrets (client IDs, keys) go in `local.properties`, which is gitignored.
  Never hardcode secrets, never print them, never commit them.
- Do not read `local.properties`, `.env`, or keystore files.

## Spotify API notes
- The Web API changed significantly in February–July 2026 (development-mode limits,
  removed endpoints, generic `/me/library` endpoints that take Spotify URIs,
  six-month refresh-token lifetime, per-account quota with `QUOTA_EXCEEDED` 429s).
- Many tutorials are outdated. Verify every endpoint against the live reference at
  developer.spotify.com before using it. If unsure, say so and ask the user to check.
- Keep API usage light: sync likes periodically, write Evergreen at most daily.
- Handle 429 responses with backoff and respect `Retry-After`.

## Git workflow
- Repo is private. Small commits with descriptive messages.
- Use a branch per phase or feature (e.g. `phase0-capture-spike`), merge when working.
- Never commit build outputs, `local.properties`, keystores, or exported user data.

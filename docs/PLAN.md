# Evergreen — plan and status

This file is the shared source of truth between Claude Code (building) and the
claude.ai "Playlist Manager" Project (planning). Keep it current.

## Goal
A "living" liked-songs playlist that works across devices and, eventually, across
streaming services:
- **Evergreen**: liked songs that are still being listened to. Songs decay out when
  they are skipped or not played.
- **Archive**: every song ever liked, kept in the app's database.
- **Snapshots**: a monthly copy of Evergreen, searchable ("2026 August"),
  exportable to a real playlist on demand.

## Key decisions
- Listening data is captured on-device (media sessions), not by polling service APIs.
  This works for any streaming app and avoids API quota limits.
- The app's own database is the source of truth. Streaming services are sync targets:
  likes are read from them, Evergreen is written to them.
- Likes keep using each service's own like button (synced periodically).
- Snapshots live in the app; they become real playlists only when exported.
- Android first (Pixel 10 Pro + Sony NW-A307), then other services, then desktop.
- Spotify development mode is fine for personal use (Premium required, 5 users max).
  A public release depends on each service's API rules at that time.
- Scoring is a hand-written formula, not a trained model.

## Devices
- Pixel 10 Pro (Android, primary)
- Sony NW-A307 (Android 12, low-powered)
- Windows and Linux desktops running Spotify (later phases)

## Phases
- [ ] **Phase 0 — Feasibility spike.** Log media-session events from Spotify on both
      Android devices. Confirm skip detection works and whether Spotify broadcasts
      include track URIs.
- [ ] **Phase 1 — Capture MVP.** Allowlisted listener service, Room database of
      listening events, debug screen to verify events.
- [ ] **Phase 2 — Spotify connection.** PKCE login, encrypted token storage,
      like sync, track matching, canonical track table.
- [ ] **Phase 3 — Core feature.** Scoring, daily Evergreen playlist sync,
      monthly snapshots. Seed scores from Spotify extended streaming history.
- [ ] **Phase 4 — Usability.** Real UI, snapshot search, like widget,
      scoring settings, data export/backup.
- [ ] **Phase 5 — Cross-service.** Second service adapter (likely Tidal),
      ISRC/MusicBrainz matching.
- [ ] **Phase 6 — Cross-device.** Self-hosted sync backend, Windows agent (WinRT media
      session API), Linux agent (MPRIS).
- [ ] **Phase 7 — Public release evaluation.** Terms, quotas, privacy policy,
      delete-my-data.

## Preparation checklist
- [x] Android Studio, Git, Claude Code (+ JetBrains plugin) installed
- [x] Developer options + USB debugging on both devices
- [x] Spotify "Device Broadcast Status" enabled on both devices
- [x] Spotify developer app created, own account allowlisted
- [ ] Spotify extended streaming history requested (arrives later)
- [ ] GitHub CLI logged in, private repo created, first commit pushed

## Status
_Update at the end of each task: what was done, what's next, any blockers._

- Current phase: Phase 0 (not started)
- Last update: project setup

## Open questions
- Does the NW-A307 allow notification-listener access for a sideloaded app?
- Do Spotify broadcast intents include the track URI on current app versions?
- How reliable is media-session position data for skip detection?
- Initial scoring parameters (decay half-life, skip penalty, grace period).

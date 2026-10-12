# Docs

This folder is the working record of the project, not polished documentation.
It was written while building, mostly as hand-off notes between me, a separate
AI planning chat, and the AI coding assistant. It is long and has some dead ends in it; the
dead ends are left in on purpose, because the corrections are the interesting
part. File names stay as they are because code comments refer to them by path.

## Start here

| File | What it is |
|---|---|
| [`PLAN.md`](PLAN.md) | Roadmap, current status, and the results and findings of each phase, newest decisions near the top. It is long (about 3,800 lines). Read "Goal", "Phases" and "Status", then dip into the phase results. |
| [`SYNC_FORMAT.md`](SYNC_FORMAT.md) | Exact specification of the fact-batch text format used to move listening data between devices. |
| [`SYNC_FOUNDATION.md`](SYNC_FOUNDATION.md) | Why the data is shaped the way it is, so that the sync method (files now, maybe a server later) stays a free choice. |

## Prompts and briefs, by phase

These are the instructions I gave Claude Code at the start of each phase, plus
the questions and decisions that came out of planning. They show how the work
was directed.

| Phase | Files |
|---|---|
| 0, capture feasibility spike | [`PHASE0_PROMPT.md`](PHASE0_PROMPT.md) |
| 1, capture MVP | [`PHASE1_PROMPT.md`](PHASE1_PROMPT.md) |
| 2, Spotify connection | [`PHASE2_PROMPT.md`](PHASE2_PROMPT.md) |
| 3, scoring and playlist sync | [`PHASE3_QUESTIONS.md`](PHASE3_QUESTIONS.md), [`PHASE3_DECISIONS.md`](PHASE3_DECISIONS.md), [`PHASE3_PLAN.md`](PHASE3_PLAN.md), [`PHASE3_PROMPT.md`](PHASE3_PROMPT.md) |
| 4, usability | [`PHASE4_PROMPT.md`](PHASE4_PROMPT.md) |
| 5, cross-device | [`PHASE5_QUESTIONS.md`](PHASE5_QUESTIONS.md), [`PHASE5A_PROMPT.md`](PHASE5A_PROMPT.md), [`PHASE5B_PROMPT.md`](PHASE5B_PROMPT.md) |

Phase 5c (the two-way merge) is in progress on its own branch and has its own
prompt and findings files there.

## Screenshots

`screenshots/` holds the images used in the main README. They are taken from the
Sony NW-A307 running against my own library.

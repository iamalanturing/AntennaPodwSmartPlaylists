# Read this first

**This branch is not the Smart Playlists fork.** It is a clean branch from upstream
`AntennaPod/AntennaPod` `develop` @ `07b74753`, carrying **manual multiple queues** only — the
feature upstream decided it wants but nobody has built. It contains none of the fork's
smart-playlist code, and must not acquire any.

The Smart Playlists work lives on `claude/continue-previous-3hqkf6`, along with `context.md` and
`FORK.md`. Nothing here should be merged into that branch or vice versa.

## Why this exists

Upstream's 19 Nov 2025 `Needs: Decision` meeting concluded they want multiple queues, liked the UX
approach of PR #8066, and would prefer a skilled human to implement it "to minimise the review
overhead". Two attempts — #8066 (too large, and faulted for reading like AI code written without
looking at the existing codebase) and #8070 (stalled, closed for inactivity Feb 2026) — both died.
The slot has been open since.

`CONTRIBUTING.md` forbids working on an issue labelled `Needs: Decision`. **#2648 (multiple queues)
is clean and eligible. #307 (smart queues) still carries the label and cannot be started by
anyone.** That is why this branch is manual queues only.

## The design

`UPSTREAM_PHASE1.md` is the real document — five study passes over the existing queue code, what
each found, and one wrong claim struck through rather than quietly deleted. **Read it before
touching anything.** The short version:

- Extend the existing `Queue` table with a `queue` column; do not build a new membership table.
- `Queue.ID` doubles as the position. It only has to increase *within* a queue.
- Leave `is_in_queue` unscoped — it means "in any queue", and that is what stops auto-delete
  eating episodes sitting in a non-active queue. **Load-bearing, not an oversight.**
- Two operation classes: "remove from everywhere" (system-initiated: deletion, episode ended, sync,
  download cancelled) versus "edit this queue" (user-initiated). Conflating them is the biggest
  correctness trap in the feature.

## Rules for this branch

- **Scope is the whole game.** #8066 was cut for being too large. No colour picker, no icon picker,
  no per-queue sort, no add-time dialog, no smart-queue rules. Do not unify `Queue` and `Favorites`
  even though they share a shape.
- Reuse existing patterns. `DBWriter`'s single shared executor via `runOnDbThread` — **never** a new
  `ExecutorService`. ViewBinding in UI. Validation in the UI, not exceptions from the model.
- English strings only, in `ui/i18n/src/main/res/values/strings.xml`. Never touch `values-*/`.
- Base branch for any PR is `develop`.

## Building

There is no local Gradle build — the container's proxy blocks `dl.google.com` and
`maven.google.com`. **CI is the only compiler.** Push and read the run.

`.github/workflows/fork-checks.yml` and this file and `UPSTREAM_PHASE1.md` are all fork-only
scaffolding and **must be dropped before any upstream PR**. They are kept in commits labelled as
such so `git rebase --onto` can remove them without touching the real work.

Upstream's own gate, wider than the fork CI, before any PR:
`./gradlew checkstyle lint spotbugsPlayDebug spotbugsDebug`

## AI provenance

This branch is AI-assisted. Every commit carries a `Co-Authored-By: Claude Opus 5` trailer, and the
work is declared under [AIPM 1.1](https://aipmq.org):

<https://aipmq.org/1.1/aipm/#v=1.1&model=Claude+Opus+5.0&role=prompted%2Breviewed&date=2026-07-29T12%3A04-04%3A00&ctx=Add+Multiple+queues+functionality+to+AntennaPod+as+the+first+phase+of+improvements+necessary+to+eventually+implement+smart+queues.&show=1&src=https%3A%2F%2Fgithub.com%2Fiamalanturing%2FAntennaPodwSmartPlaylists%2Ftree%2Fmultiple-queues&doc=https%3A%2F%2Fgithub.com%2Fiamalanturing%2FAntennaPodwSmartPlaylists%2Fblob%2Fmultiple-queues%2FUPSTREAM_PHASE1.md>

## Standing instruction

**Nothing is posted upstream — no issue, no comment, no PR — without asking first.** There are five
open questions in `UPSTREAM_PHASE1.md` that belong in a comment on #2648 before the code is
finished, but that comment needs approval before it goes anywhere.

# Read this first

Manual multiple queues (upstream issue #2648), rebuilt fresh on upstream `develop`.
Base: `3d105d598dfa582c6cb22ba40b56d11780366890` (`PodDBAdapter.VERSION` 3110000).
This is not the Smart Playlists fork and must not acquire any of its code.

## Standing rule

Nothing is posted upstream (no issue, comment or PR) without asking the owner first.
The owner decides about #2648 being locked to collaborators and about disclosing AI involvement.

## Fork-only commits (bottom of the branch, all start with `Fork-only:`)

1. CI mirror: `.github/workflows/fork-checks.yml`
2. Debug signing key: `app/fork-debug.keystore`, `app/build.gradle`, `.gitignore`
3. MQ launcher badge: `app/src/debug/res/`
4. This file

Drop them for a PR: `git rebase --onto upstream/develop <last fork-only commit>`.

## Decisions

- One queue per episode (UNIQUE index on `Queue.feeditem`).
- Adds go to the active queue, no add-time dialog. A "Move to queue..." action appears with 2+ queues.
- Deleting a queue removes its episodes from all queues, so they lose auto-delete protection.
- Plain `VERSION = 3120000` migration, no fork schema machinery.
- Stock `de.danoeh.antennapod.debug` application id. Smart Playlists is `.smart.debug`.
- Out of scope: Android Auto, per-queue sort, colours, icons, rules.

## Building

The container cannot compile (Google Maven is blocked). CI is the only compiler: push to a
`claude/**` branch and read the run. Each phase must be CI-green before the next.
Reference: `archive/multiple-queues-impl-m2jnxy-20260817`.

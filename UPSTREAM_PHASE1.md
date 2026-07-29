# Upstream phase 1: multiple queues

A separate effort from the Smart Playlists fork. The goal is a small, focused, idiomatic PR against
upstream `AntennaPod/AntennaPod` implementing **manual multiple queues** — the feature upstream has
already decided it wants — with none of this fork's smart-playlist code in it.

Read `context.md` § "Upstream's own plans for playlists" first for how upstream reached that
decision and why three previous attempts failed.

## Why this is eligible and smart queues are not

`CONTRIBUTING.md` requires that an issue exist **without** `Needs: Triage` or `Needs: Decision`
before anyone writes code, and that you comment on the issue to claim it. Unapproved work risks
rejection.

- **#2648 Multiple / User Definable Queues** — labels `Functionality: Queue(s)`, `Type: Feature
  request`. No `Needs: Decision`, and the 19 Nov 2025 meeting settled the design. **Eligible.**
- **#307 Automatic / Smart queues** — still carries `Needs: Decision`. **Not eligible**, for anyone.

So the phasing is forced by upstream's rules, not chosen. Smart queues cannot be started yet.

Nothing has been posted upstream. Do not post without asking.

## What upstream asked for

From ByteHamster's review of #8070 and his scope cuts on #8066:

- One level of navigation. Switching queue changes what the **existing** fragment shows and is
  remembered next launch — not a new fragment pushed on top.
- An **active queue**, persisted. Adding an episode anywhere sends it to the active queue. The
  add-time dialog was explicitly cut; it is reserved for automation later.
- Create, rename, delete. Nothing else.
- **Not** in scope: colour picker, icon picker, per-queue sort order, add-time dialog, rules.
- Reuse existing patterns. ViewBinding in UI. Validation in the UI, not exceptions from the model.
- English strings only (`ui/i18n/src/main/res/values/strings.xml`). Weblate owns the rest.
- Base branch `develop`. Unit tests where possible.
- Their check command is wider than this fork's:
  `./gradlew checkstyle lint spotbugsPlayDebug spotbugsDebug`.

## How the existing queue actually works

Read before designing anything. All references are upstream `develop` @ `07b74753`.

**One table, three columns** (`PodDBAdapter.java:216`):

```sql
CREATE TABLE Queue(ID INTEGER PRIMARY KEY, FeedItem INTEGER, Feed INTEGER)
```

**`ID` is the position, not an identity.** `setQueue` (`PodDBAdapter.java:891`) deletes every row
and reinserts with `values.put(KEY_ID, i)` — the loop index. Every read orders by it:
`getQueueCursor`, `getQueueIDCursor`, `getNextInQueue`, `getPausedQueueCursor` all
`ORDER BY Queue.ID`. Reordering the queue rewrites the whole table inside one transaction.

This single fact is why both previous attempts created brand-new tables instead of extending this
one: `KEY_ID = i` collides the moment two queues both have a position 0.

**`is_in_queue` is a correlated subquery over the whole table** (`PodDBAdapter.java:292`), surfaced
as `FeedItem.TAG_QUEUE` and consumed in exactly one place —
`EpisodeItemViewHolder.java:106`, which shows a badge. Nothing else reads it.

**Writes go through `DBWriter`'s single shared executor** (`DBWriter.java:64`): a
`newSingleThreadExecutor` named `DatabaseExecutor` at `MIN_PRIORITY`. The house pattern is a public
`static Future<?> foo(...)` that wraps `runOnDbThread(() -> ...)`, plus a private
`fooSynchronous(...)` for internal composition. **Do not add another `ExecutorService`** — that was
the first specific fault ByteHamster named in #8066.

**Migrations are plain `if (oldVersion < N)` blocks** of `execSQL` in `DBUpgrader.upgrade`, with no
framework. `PodDBAdapter.VERSION` is **`3110000`** today. Note #8066 used `3090000`, which is now
below the current version and would never run — pick a version above `3110000`.

**`QueueEvent`** (`event/.../QueueEvent.java`) is a closed set of factory methods —
`added/setQueue/removed/cleared/sorted/moved`. It carries no queue identity, so multi-queue needs
either a queue id on the event or a separate switch event.

## The design this suggests

A smaller change than either previous attempt, because it extends the existing table instead of
replacing it:

1. New `Queues` table: `ID INTEGER PRIMARY KEY, Name TEXT`. Migration inserts one default row.
2. `ALTER TABLE Queue ADD COLUMN queue INTEGER DEFAULT <default id>` — existing rows all land in
   the default queue, so no row migration and no risk to existing user data.
3. Keep `ID` as the ordering column. It only ever needs to be ordered *within* a queue, so absolute
   values are irrelevant — `setQueue` for one queue deletes that queue's rows and reinserts above
   the current `MAX(ID)`. Ordering semantics are unchanged everywhere.
4. Add `WHERE queue = ?` to the four read queries and to `clearQueue`.
5. Active queue id in `UserPreferences`, read by the add-to-queue paths.
6. `is_in_queue` needs **no change** — "in any queue" is the right meaning for the badge.

The attraction is the blast radius: no new membership table, no data migration, no change to the
ordering mechanism, and one untouched query that would otherwise need rethinking.

**This is a hypothesis, not a verdict.** It has not been compiled or tested, and the UI side is not
yet studied. Check it against `QueueFragment`, `ItemEnqueuePositionCalculator`, and the swipe
actions before committing to it.

## Still to study

- `QueueFragment` and `QueueRecyclerAdapter` — how the list loads and what a queue switch must
  redraw.
- `ItemEnqueuePositionCalculator` — enqueue position rules, which are preference-driven.
- `UserPreferences` — the idiom for a new persisted value.
- Where `getNextInQueue` is consumed by playback, and what "next" means once a queue can be empty.
- `APQueueCleanupAlgorithm` and the auto-download paths that assume one queue.

## Constraints on doing this at all

- **No local compiler.** The proxy blocks `dl.google.com` and `maven.google.com`; CI is the only
  build. Any prototype needs a branch on this fork carrying `fork-checks.yml` to compile at all.
- **Provenance is the real barrier**, not quality. The 19 Nov 2025 meeting concluded upstream wants
  a *skilled human* implementer to minimise review overhead. `CONTRIBUTING.md` has no AI policy, so
  this is a stated preference rather than a rule — but it was stated in the same breath as approving
  the design, and they identified #8066 as agentic on their own. Disclose; do not let it be
  discovered.
- **The slot is still open.** No multiple-queues PR since #8070 closed in Feb 2026 — checked
  through July 2026.

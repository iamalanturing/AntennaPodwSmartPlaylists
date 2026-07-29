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

**This is a hypothesis, not a verdict.** It has not been compiled or tested. The storage side of it
survived the second pass below; the semantic side did not come through as cleanly.

## Second pass: what the write path and callers actually look like

**`ItemEnqueuePositionCalculator` needs no changes at all.** It already takes the queue as a
parameter — `calcPosition(@NonNull List<FeedItem> curQueue, @Nullable Playable currentPlaying)` —
and its javadoc already says "inserted to the **named** queue". Whoever wrote it left the door
open.

One behavioural wrinkle: with `AFTER_CURRENTLY_PLAYING`, `getCurrentlyPlayingPosition` returns `-1`
when the playing episode is not in the target queue, so the insert lands at the front rather than
after anything. Adding to a queue you are not currently playing therefore silently means "add to
front". Defensible, but it is a behaviour change and a reviewer will ask.

**The write path is already whole-queue rewrite.** `DBWriter.addQueueItem` (line 378) reads
`DBReader.getQueue()`, mutates the `List<FeedItem>`, then calls `adapter.setQueue(queue)` to
rewrite the table. Every other queue mutation follows the same read-mutate-write shape. So scoping
writes to one queue is a *parameter*, not a redesign — which is the strongest evidence yet for
extending the existing table rather than replacing it.

**Keep-sorted is global.** `applySortOrder` consults `UserPreferences.isQueueKeepSorted()` and a
single stored `SortOrder`. With per-queue sort out of scope, every queue shares one keep-sorted
setting. That is a real user-visible consequence of the agreed scope and should be stated in the PR
rather than discovered in review.

**`UserPreferences` idiom** for the active queue: a `public static final String PREF_*` constant
plus static getter/setter over `prefs`, exactly as `PREF_ENQUEUE_LOCATION` does at line 99/380.

### The actual difficulty is `DBReader.getQueue()`, not the schema

21 production call sites, and they do **not** all mean the same thing. They split in two, and the
split is the whole design problem:

**A — "the queue the user is looking at or editing."** Becomes the active queue, or takes an
explicit id. Mechanical.
`QueueFragment:535`, `RemoveFromQueueSwipeAction:44`, `NavDrawerFragment:322` (badge count),
and seven call sites inside `DBWriter` (add, remove, move, reorder).

**B — "the queue playback follows," and background algorithms.** Genuinely ambiguous once more
than one queue exists, and no amount of careful coding decides it for you:
`PlaybackService:371, 480, 1087`, `PlaybackService.getNextInQueue:1069`,
`Media3PlaybackService:639`, `LocalPSMP:714`, `CastPsmp`,
`MediaLibrarySessionCallback:441` (the Android Auto "Queue" browse node),
`AutomaticDownloadAlgorithm:66`.

The questions category B forces, none of which appear in either previous PR's discussion:

- When an episode finishes, which queue supplies the next one? The active queue, or the queue the
  finished episode belonged to? An episode can be in several.
- Does auto-download consider the active queue, or the union of all queues? Downloading only the
  active one silently breaks the "prepare my commute queue overnight" use case that motivates the
  feature in the first place.
- Android Auto exposes one `MEDIA_ID_QUEUE` node. Does it show the active queue, or gain a level?
- `APQueueCleanupAlgorithm` decides what may be deleted based on queue membership. Union is almost
  certainly right, or episodes in a non-active queue become eligible for cleanup.

**Revising the earlier optimism.** The storage change really is small — smaller than either prior
attempt. But "small diff" and "small feature" are not the same thing, and the paragraph above is
where a phase 1 PR would actually get stuck. The honest read is that category B needs an explicit,
stated decision *in the issue* before code, not a choice quietly baked into an implementation.
That is also the strongest argument for commenting on #2648 first: these are exactly the questions
a maintainer should answer, and getting them wrong silently is how a PR earns twenty review rounds.

Provisional answers worth proposing, all chosen to minimise behaviour change for single-queue users:
playback follows the queue containing the current episode, falling back to the active queue;
auto-download and cleanup use the union of all queues; Android Auto shows the active queue.

## Third pass: the category B question is cheaper to answer than it looked

**"Next episode" already resolves relative to the current episode's row**, not to any global notion
of the queue. `PodDBAdapter.getNextInQueue` is:

```sql
WHERE Queue.ID > (SELECT Queue.ID FROM Queue WHERE Queue.FeedItem = <id>) ORDER BY Queue.ID LIMIT 1
```

Add the column and the natural extension is one more predicate —
`AND Queue.queue = (SELECT queue FROM Queue WHERE FeedItem = <id>)` — at which point **playback
follows the queue containing the episode being played**, with no new concept and no new state. The
answer that seemed most sensible is also the cheapest, which is the good case.

The one genuine ambiguity: an episode in several queues makes that subquery return several rows.
Resolve it deterministically — prefer the active queue when the item is in it, otherwise the lowest
queue id — and say so in the PR rather than letting SQLite pick.

**Correcting a guess from the second pass.** I suggested `UserPreferences.isFollowQueue()` at
`PlaybackService:1099` might be the hook for this decision. It is not. It is the global
continuous-playback toggle — whether to advance at all — and is orthogonal to which queue supplies
the next episode. No help here.

**`QueueFragment` is already shaped for what ByteHamster asked for.** The entire screen loads
through one `loadItems()` (line 525) that calls `DBReader.getQueue()`, and roughly eight
`@Subscribe` handlers do nothing but call it again. So "change what is shown in the same fragment
and remember it for next time" is: set the active-queue preference, call `loadItems()`. No fragment
stack, no navigation work, no ViewModel. The requirement that sank #8070's UX is close to free.

**Ruled out: `SynchronizationQueue`** in `:net:sync` is the gpodder sync-*event* queue
(`enqueueEpisodeAction`, `enqueueFeedAdded`, `sync()`). Pure name collision. Ignore it.

**Still a category B consumer: `WearListenerService`** (`app/src/play`, line 105) answers
`WearDataPaths.QUEUE` with `DBReader.getQueue()` and ships it to the watch. Active queue is the
obvious answer. Note it is play-flavour only, so a free-flavour build will not catch a break here.

## Still to study

- `QueueRecyclerAdapter` and `queue.xml` menu — where a queue switcher control would live.
- `APQueueCleanupAlgorithm` in full, to confirm union-of-queues is right for cleanup.
- `AutomaticDownloadAlgorithm:66` in full, same question for downloads.
- The `Favorites` table, which has the identical `(ID, FeedItem, Feed)` shape — worth checking
  whether a shared helper already exists before writing a second one.

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

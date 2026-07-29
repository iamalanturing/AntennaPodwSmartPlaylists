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

## Fourth pass: cleanup and auto-download, and a refactor not to do

**Auto-delete needs no changes at all, and this is the strongest argument for the design.**
`APQueueCleanupAlgorithm.getCandidates()` protects an episode with
`!item.isTagged(FeedItem.TAG_QUEUE)` — and `TAG_QUEUE` comes from the `is_in_queue` correlated
subquery, which is unscoped. Leave that query alone and it means "in **any** queue", so episodes
sitting in a non-active queue are automatically protected from cleanup. Union semantics, for free,
in the one place where getting it wrong would silently delete a user's downloads. Keeping
`is_in_queue` unscoped is therefore load-bearing, not merely harmless — write that down in the PR,
because it looks like an oversight to a reviewer who has not traced it.

**Auto-download is a one-line change.** `AutomaticDownloadAlgorithm` (~line 66) adds queue contents
to the candidate list only when `UserPreferences.isEnableAutodownloadQueue()`. Swapping
`DBReader.getQueue()` for a union-of-all-queues read is the whole edit, and union is clearly right:
downloading only the active queue would break the "fill my commute queue overnight" case that
motivates the feature.

**~~Item deletion already works across queues.~~ Wrong — see the fifth pass below.** This section
originally claimed deletion cleans membership rows by `FeedItem` at `PodDBAdapter:941` "and its
`Queue` counterpart". Line 941 deletes from `Favorites`, and there is no `Queue` counterpart. The
claim came from misreading a grep hit and is corrected below.

**A refactor to deliberately not do.** `Favorites` is `(ID, FeedItem, Feed)` — the same shape as
`Queue` — with its own parallel `setFavorites` / `addFavoriteItems` / `removeFavoriteItems`
(`PodDBAdapter:849–890`) and its own unscoped `is_favorite` subquery. There is **no shared helper**;
upstream simply duplicated the pattern. It is tempting to unify them while adding a third variant.
Don't. ByteHamster already cut #8066 for scope once, and an unsolicited refactor of favourites is
exactly the sort of thing that turns a reviewable diff into an unreviewable one. Follow the
duplication.

## Where the study lands

Four passes in, the design holds. Category B — the part that looked like the blocker — collapsed to
three small edits and one non-edit:

| Consumer | Change |
|---|---|
| Auto-delete / cleanup | **none** — inherits union via unscoped `is_in_queue` |
| Auto-download | one line — union read |
| Next episode | one SQL predicate — follows the current episode's queue |
| Android Auto, Wear | active queue |
| Item deletion | all-queues removal — **corrected in the fifth pass**, it is not free |
| Enqueue position | **none** — already takes the queue as a parameter |
| Queue screen | set preference, call `loadItems()` |

Storage: one new `Queues` table, one column on `Queue`, `WHERE queue = ?` on four reads, migration
above `3110000`. No new membership table, no row migration, no new executor, no ViewModel.

**Open decisions for the issue, not for the code:** which queue wins when an episode is in several
(propose: active, else lowest id); whether keep-sorted staying global is acceptable; and whether
"add to a queue you are not playing" landing at the front is the intended behaviour.

**Still unstudied:** `QueueRecyclerAdapter` and `res/menu/queue.xml`, where a switcher control would
live. That is UI placement rather than architecture, and is better settled with a maintainer than
guessed at.

## Fifth pass: hunting for holes rather than confirmation

The first four passes each set out to check something and largely found it. This one set out to
break the design, and did.

**The deletion claim above was wrong.** Deleting episodes runs through
`DBWriter.deleteFeedItemsSynchronous:213`, which does the same `DBReader.getQueue()` → mutate list
→ `setQueue` dance as everything else. It is therefore fully exposed to scoping. Scoped to the
active queue, deleting a feed's episodes would strand orphan rows in every other queue — exactly
the bug the `3080000` migration had to patch for `Favorites`.

**`removeQueueItem` has two callers with two different correct answers.** Eight production call
sites:

- *System-initiated, must clear **all** queues:* `DBWriter:113` (media file deleted),
  `PlaybackService:1216` and `Media3PlaybackService:519` (episode ended), `SyncService:302`
  (gpodder says removed remotely), `DownloadServiceInterfaceImpl:87` (download cancelled).
- *User-initiated, the queue in front of them:* `RemoveFromQueueSwipeAction:53`.
- *Ambiguous, but "all queues" reads better:* `FeedItemMenuHandler:201` and
  `EpisodeMultiSelectActionHandler:88` — both fire from generic episode lists, where the user is
  looking at an episode rather than at a position in a queue.

Scope this to the active queue and deleted or finished episodes sit forever in the others. **This
is the single biggest correctness trap in the feature**, and it is invisible from the schema.

**`Queue.Feed` is a dead column.** Written by `setQueue`, declared in `CREATE TABLE`, never
selected or filtered on by any query. Do not carry it into new code — and do not remove it either,
which would be a migration for no benefit and precisely the scope creep that got #8066 cut.

### The framing this forces

Not "add `WHERE queue = ?` to the reads" but **two operation classes**:

- **Class A, "remove this episode from everywhere."** System-initiated. A direct
  `DELETE FROM Queue WHERE FeedItem IN (...)`, still posting `QueueEvent.removed` per item. This is
  *simpler* than the read-mutate-write it replaces and is correct for any number of queues by
  construction.
- **Class B, "edit this queue."** User-initiated — add, reorder, move, swipe-remove, clear. Keeps
  read-mutate-write, with the queue id threaded through.

Everything else from passes two to four survives unchanged. The lesson worth keeping: four passes
of reading confirmed a storage design that was basically right, and one pass of trying to break it
found the thing that would actually have shipped a bug.

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

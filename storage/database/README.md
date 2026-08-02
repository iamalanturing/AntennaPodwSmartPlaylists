# :storage:database

AntennaPod's main database, containing subscriptions and playback state (but not user settings).
Uses raw SQLite via `PodDBAdapter` (not Room); writes go through async `DBWriter`; reads use cursor-to-object mappers in the `mapper/` package.

## Queues

`Queue.ID` doubles as the position: `setQueue` rewrites a queue's rows in order and every read
orders by it. Values only have to increase *within* one queue, so they are not comparable between
queues and are not absolute positions. `Queue.queue` names the owning queue; `Queues` lists them,
and `QUEUE_ID_DEFAULT` always exists.

`is_in_queue` (surfaced as `FeedItem.TAG_QUEUE`) is deliberately **not** scoped to one queue — it
means "in any queue". Auto-delete relies on this to protect episodes sitting in a queue the user is
not currently viewing, so scoping it would silently make them eligible for deletion.

`DBReader.getQueue()` and friends operate on the **active queue** — `UserPreferences.getActiveQueue()`,
falling back to `QUEUE_ID_DEFAULT` until the user first switches. Pass an explicit id to the
`getQueue(long)` overloads when a caller means a specific queue rather than whichever one the user is
looking at. `setQueue` rewrites one queue's rows and appends them above the current `MAX(Queue.ID)`,
since that column is the position and is shared across queues; the gaps this leaves are harmless
because order only ever has to hold within a queue.

An episode belongs to **exactly one queue**. A unique index on `Queue.feeditem` enforces it, so
adding an episode to another queue moves it rather than copying it. This is what keeps "which queue
is this episode in" a question with one answer, and it is why looking up the queue of an episode
never needs a tie-break rule.

Removing an episode splits in two. System-initiated removals — the file was deleted, playback
ended, sync said so — must clear the episode from *every* queue. Only a user acting on the queue
screen removes it from one.

Migrations are plain `if (oldVersion < N)` blocks in `DBUpgrader`, and `DBUpgraderTest` covers them
by building the old schema by hand, calling `DBUpgrader.upgrade` and asserting on the result. Add a
case there for any migration that touches user data: no other test exercises the upgrade path,
since a fresh install runs `onCreate` instead.

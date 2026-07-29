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

Removing an episode splits in two. System-initiated removals — the file was deleted, playback
ended, sync said so — must clear the episode from *every* queue. Only a user acting on the queue
screen removes it from one.

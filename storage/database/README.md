# :storage:database

AntennaPod's main database, containing subscriptions and playback state (but not user settings).
Uses raw SQLite via `PodDBAdapter` (not Room); writes go through async `DBWriter`; reads use cursor-to-object mappers in the `mapper/` package.

## Queues

`Queue.ID` doubles as the position: a queue's rows are rewritten in order and every read orders by it.
Values only have to increase *within* one queue, so they are not comparable between queues.
`Queue.queue` names the owning queue, `Queues` lists them, and `QUEUE_ID_DEFAULT` always exists.

An episode belongs to exactly one queue, enforced by a unique index on `Queue.feeditem`.
Adding an episode to another queue moves it rather than copying it.

`is_in_queue` (surfaced as `FeedItem.TAG_QUEUE`) is deliberately not scoped to one queue: it means "in any queue".
Auto-delete relies on this to protect episodes in a queue that is not currently shown.

Migrations are plain `if (oldVersion < N)` blocks in `DBUpgrader`. `DBUpgraderTest` covers them by building
the old schema by hand and calling `DBUpgrader.upgrade`. Add a case there for any migration that touches user data,
because a fresh install runs `onCreate` instead.

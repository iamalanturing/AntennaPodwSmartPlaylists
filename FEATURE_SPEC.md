# Feature Specification: Smart Queues for AntennaPod

This document describes the Smart Queue feature in enough detail for a developer
(human or AI) to reimplement it from scratch on any future version of AntennaPod.
It captures behavior, rationale, and gotchas — not current file paths or line numbers.

## Overview

Smart Queues are rule-based playlists inspired by BeyondPod's "SmartPlay" feature.
Users define filter rules (e.g., "unplayed episodes from these 3 podcasts, newest first,
limit 20") and the app generates an ordered episode list. Pressing play starts sequential
playback through the entire queue, similar to AntennaPod's built-in queue but dynamically
generated from rules.

The name "Smart Queue" was chosen over "Smart Playlist" because AntennaPod's playback
model is queue-based, and the feature integrates with the queue playback system.

## User-Facing Behavior

### Creating a Smart Queue
1. User navigates to Smart Queues screen (accessible from home section or navigation drawer)
2. Taps "Create Smart Queue"
3. Names the queue
4. Adds one or more filter rules (each narrows the episode set)
5. Sets sort order (newest, oldest, shortest, longest, random)
6. Sets episode limit (optional, default: no limit)
7. Toggles "auto-rebuild" (regenerate when last episode finishes)
8. Taps "Save" — queue is created and episodes are generated immediately

### Viewing and Playing
1. User taps a smart queue card on home screen or list screen
2. Detail screen shows the generated episode list
3. Pressing play starts the first episode (or resumes an in-progress one)
4. Episodes play sequentially through the queue
5. When an episode finishes, the next one in the queue starts automatically
6. If auto-rebuild is on and the queue is exhausted, it regenerates and continues
7. If auto-rebuild is off and the queue is exhausted, playback stops

### Regenerating
- Manual: user taps "Regenerate" from the detail screen menu
- Automatic: when last episode finishes and auto-rebuild is enabled
- Regeneration re-evaluates all rules against current episodes and rebuilds the list

### Editing and Deleting
- Edit: modify name, rules, sort, limit, auto-rebuild from detail screen menu
- Delete: removes queue and all its rules and generated episodes

## Data Model

### SmartPlaylist
| Field | Type | Description |
|-------|------|-------------|
| id | long (auto-increment) | Primary key |
| name | String | User-visible queue name |
| sortOrder | String | One of: `NEWEST`, `OLDEST`, `SHORTEST`, `LONGEST`, `RANDOM` |
| episodeLimit | int | Max episodes (0 = no limit) |
| autoRegenerate | boolean | Whether to rebuild when queue is exhausted during playback |

### SmartPlaylistRule
| Field | Type | Description |
|-------|------|-------------|
| id | long (auto-increment) | Primary key |
| playlistId | long (FK) | References SmartPlaylist.id |
| field | String | What to filter on (see Rule Fields below) |
| operator | String | How to compare (see Operators below) |
| value | String | The comparison value |

### SmartPlaylistEpisodes (generated, ephemeral)
| Field | Type | Description |
|-------|------|-------------|
| id | long (auto-increment) | Primary key |
| playlistId | long (FK) | References SmartPlaylist.id |
| feedItemId | long (FK) | References FeedItems.id |
| position | int | Order in the queue (0-based) |

The Episodes table is regenerated on demand. Only SmartPlaylists and SmartPlaylistRules
are essential for backup/restore.

## Rule Fields and Operators

Rules are AND-combined: every rule must match for an episode to be included.

| Field | Valid Operators | Value Format | Description |
|-------|----------------|--------------|-------------|
| `FEED` | `EQUALS` | Feed ID (long as string) | Episode belongs to specific podcast |
| `TAG` | `EQUALS` | Tag name string | Episode's podcast has this tag |
| `STATUS` | `EQUALS` | `UNPLAYED`, `PLAYED`, `ANY` | Playback completion state |
| `DOWNLOADED` | `EQUALS` | `YES`, `NO`, `ANY` | Whether episode media is downloaded |
| `FAVORITED` | `EQUALS` | `YES`, `NO`, `ANY` | Whether episode is favorited |
| `MEDIA_TYPE` | `EQUALS` | `AUDIO`, `VIDEO`, `ANY` | Audio vs video episodes |
| `DURATION_MIN` | `GREATER_THAN` | Minutes (int as string) | Minimum episode duration |
| `DURATION_MAX` | `LESS_THAN` | Minutes (int as string) | Maximum episode duration |
| `AGE` | `LESS_THAN` | Days (int as string) | Maximum age in days from publication |
| `LIMIT` | `LESS_THAN` | Count (int as string) | Per-rule episode limit |

### How rules translate to SQL

Each rule generates a WHERE clause fragment against the FeedItems/FeedMedia tables:
- `FEED` / `EQUALS` → `FeedItems.feed = ?`
- `TAG` / `EQUALS` → `FeedItems.feed IN (SELECT feed_id FROM FeedTags WHERE tag = ?)`
- `STATUS` → `FeedItems.read = 0` (unplayed) or `FeedItems.read = 1` (played)
- `DOWNLOADED` → join on FeedMedia, check `file_url IS NOT NULL`
- `FAVORITED` → `FeedItems.id IN (SELECT feeditem FROM Favorites)` or NOT IN
- `MEDIA_TYPE` → check FeedMedia.mime_type LIKE 'audio/%' or 'video/%'
- `DURATION_MIN/MAX` → `FeedMedia.duration > ?` or `< ?` (value in milliseconds)
- `AGE` → `FeedItems.pubDate > ?` (calculated as current time minus days)
- `LIMIT` → applied as SQL LIMIT clause

**Important:** LIKE values in tag queries are sanitized to prevent SQL injection —
`%`, `_`, and `'` characters are escaped.

### Sort order SQL mapping
| Sort | SQL |
|------|-----|
| `NEWEST` | `FeedItems.pubDate DESC` |
| `OLDEST` | `FeedItems.pubDate ASC` |
| `SHORTEST` | `FeedMedia.duration ASC` |
| `LONGEST` | `FeedMedia.duration DESC` |
| `RANDOM` | `RANDOM()` |

## Playback Integration

### Starting playback from a Smart Queue
When the user presses play on the Smart Queue detail screen:
1. Find an episode with a saved position (in-progress), or fall back to first episode
2. Write the smart queue ID to preferences as the "active smart queue"
3. Start playback of that episode via `PlaybackServiceStarter`

### Sequential playback
The Media3PlaybackService `startNextInQueue()` method handles what happens when an
episode finishes:

1. Check if there's an active smart queue ID in preferences
2. If yes:
   a. Validate the current episode is actually in that smart queue (it might have been
      removed by a regeneration or the user might have manually started a different episode)
   b. If valid, get the next episode in the queue (by position order)
   c. If no next episode and auto-regenerate is on: regenerate the queue synchronously,
      then start the first episode of the new queue
   d. If no next episode and auto-regenerate is off: clear the active smart queue ID
3. If no active smart queue (or validation failed): fall back to AntennaPod's normal
   queue behavior (`DBReader.getNextInQueue`)

### Clearing smart queue mode
The active smart queue ID is cleared when:
- The queue is exhausted and auto-regenerate is off
- The current episode is not found in the smart queue (user switched to a different episode)
- The user manually starts playing something outside the smart queue

### Key design decision: SharedPreferences, not service state
The active smart queue ID is stored in `SharedPreferences`, not as a field on the
playback service. This survives service restarts and process death. The preference
key is `currently_active_smart_queue_id` (long, default 0 = no active smart queue).

## UI Architecture

### Screens
1. **Home section** — horizontal scrolling cards showing each smart queue with episode count.
   Tapping a card opens the detail screen. An "add" card at the end opens the edit screen.
2. **List screen** — vertical list of all smart queues, accessible from navigation drawer.
   Each item shows name and episode count.
3. **Detail screen** — shows episodes in the queue using AntennaPod's standard
   `EpisodeItemListAdapter`. Toolbar has edit, regenerate, and delete actions.
   An "auto-rebuild" toggle switch is shown below the toolbar.
4. **Edit screen** — form with name field, rule list, sort order spinner, limit spinner.
   Each rule is shown as a summary string. Tapping a rule opens the rule edit dialog.
5. **Rule edit dialog** — dropdown for field type, then contextual controls for the
   operator and value (e.g., feed picker, tag picker, duration input, etc.).

### Navigation
- Home → tap card → Detail (with auto-play option)
- Home → tap "add" card → Edit (create mode)
- List → tap item → Detail
- List → FAB → Edit (create mode)
- Detail → menu → Edit
- Detail → menu → Delete (with confirmation dialog)

### Home section tag
The Smart Queues section is registered in the home screen sections array
(`home_section_titles` in `arrays.xml`) and uses the tag `SmartPlaylistsSection.TAG`.
It appears first in the home screen by default.

## Other Fork Features

### Bluetooth AVRCP Media Browser Registration
**Problem:** AntennaPod's `MediaLibraryService` only starts when playback begins.
Car Bluetooth media browsers (e.g., Toyota Prius) enumerate available media apps at
connection time. If AntennaPod hasn't played anything yet, it doesn't appear in the list.

**Solution:** On app launch (`PodcastApp.onCreate`), briefly connect a `MediaController`
to the `Media3PlaybackService` via `SessionToken` + `MediaController.Builder.buildAsync()`.
This registers the service with Android's media session framework. The controller is
immediately released — it's just a registration ping.

**Key detail:** The service needs the `android.media.browse.MediaBrowserService` intent
filter in its manifest entry alongside the Media3 intent filters.

### Auto-Rewind After Transient Audio Focus Loss
**Problem:** When a phone call or navigation prompt interrupts playback and then ends,
playback resumes but the user missed the last few seconds. Android's audio focus
mechanism suppresses playback rather than pausing it, so the standard pause-resume
rewind doesn't trigger.

**Solution:** Monitor `onPlaybackSuppressionReasonChanged` in the player listener.
When suppression reason changes to `TRANSIENT_AUDIO_FOCUS_LOSS`, set a flag. When it
changes back to `NONE`, apply `RewindAfterPauseUtils.calculatePositionWithRewind()`.

Also added a `MINIMUM_REWIND` constant (800ms) to `RewindAfterPauseUtils` so that even
very brief pauses get a small rewind, improving the listening experience.

### Continue Listening Browse Node
The Bluetooth/Android Auto media browser browse tree includes a "Continue Listening"
node that shows the 8 most recently paused episodes (via `DBReader.getPausedQueue(8)`),
rather than just the single currently-playing episode. This gives car users more to
choose from.

### Batch Event Optimization
`FeedItemEvent` was extended with an `unreadStatusChanged` flag and a constructor that
accepts a list of items. This allows batch operations (mark multiple played, batch
favorite) to post a single event instead of one per item, reducing UI thrashing.

## Gotchas and Lessons Learned

1. **SQLite thread safety:** Use `Schedulers.computation()` not `Schedulers.io()` for
   all database operations. SQLite allows only one writer at a time, and `io()` has
   unbounded threads that cause lock contention ("Too many inflation attempts" crashes).

2. **DB adapter try-finally:** Always wrap `PodDBAdapter` operations in try-finally to
   ensure `adapter.close()` is called, even on exceptions.

3. **Auto-regenerate must be synchronous:** When the queue is exhausted during playback
   and auto-regenerate fires, `generateSmartPlaylistSync()` must complete before
   `getSmartPlaylistEpisodes()` is called. This is why there's a synchronous variant
   of the generate method.

4. **Smart queue validation:** Before getting the next episode from a smart queue, always
   validate that the current episode is actually in the queue. The user might have started
   a different episode manually, which should exit smart queue mode.

5. **Glide cache-only for media session artwork:** When loading artwork for the media
   session (car displays, notification), use `onlyRetrieveFromCache(true)` with Glide.
   Network requests on the media session thread cause ANRs and timeouts.

6. **Media3 browse tree pagination:** `onGetChildren` receives a `pageSizeRequest` that
   can be absurdly large. Cap it with `Math.min(100, pageSizeRequest)`.

7. **Sleep timer preferences vs command args:** The sleep timer value should be read from
   `SleepTimerPreferences.timerMillisOrEpisodes()` in the service, not passed through
   the `SessionCommand` args. The args approach broke because the custom command bundle
   gets lost in some service restart scenarios.

8. **Position save guard:** Only save playback position when `player.getPlayWhenReady()`
   is true and state is READY. Saving during buffering or when paused causes spurious
   entries in playback history.

## Testing Checklist

When reimplementing, verify:
- [ ] Create queue with multiple rules → episodes match all rules (AND logic)
- [ ] Sort orders produce correct episode ordering
- [ ] Episode limit is respected
- [ ] Playing through queue advances to next episode automatically
- [ ] Auto-rebuild regenerates and continues when queue exhausted
- [ ] Manual regeneration from detail screen works
- [ ] Starting a non-queue episode exits smart queue mode
- [ ] Queue survives app restart (preference-based, not in-memory)
- [ ] Delete removes queue, rules, and generated episodes
- [ ] Home section shows queues with correct episode counts
- [ ] Smart queue appears in car Bluetooth browser after fresh app install
- [ ] Backup/restore preserves smart queue definitions and rules

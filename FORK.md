# Fork: AntennaPod with Smart Queues

This is a fork of [AntennaPod](https://github.com/AntennaPod/AntennaPod) that adds
BeyondPod-style Smart Queue functionality — rule-based playlists that auto-generate
episode lists and play sequentially through the Media3 playback service.

**Fork point:** AntennaPod `develop` branch, commit `5de8d4b` (March 22, 2026)  
**Branch:** `claude/add-smart-playlists-fqUHX`  
**DB version:** 3120000 (adds SmartPlaylists, SmartPlaylistRules, SmartPlaylistEpisodes tables)

## What this fork adds

### Smart Queue feature
Rule-based playlists that filter episodes by podcast, playback state, download status,
favorites, duration, and tags. Episodes are generated into an ordered queue and played
sequentially through the Media3 service, with optional auto-regeneration at end of queue.

### Bluetooth AVRCP media browser registration
Registers the `MediaLibraryService` on app launch so the app appears in car Bluetooth
media browsers (e.g., Toyota Prius) without requiring playback to start first.

### Auto-rewind after transient audio focus loss
Brief rewind when playback resumes after interruptions (phone calls, navigation prompts)
using `RewindAfterPauseUtils` in `onPlaybackSuppressionReasonChanged`.

## New files (zero conflict risk on rebase)

### Model
- `model/.../SmartPlaylist.java` — playlist model with name, sort order, limit, auto-regenerate
- `model/.../SmartPlaylistRule.java` — individual filter rule (field, operator, value)

### Database
- `storage/database/.../mapper/SmartPlaylistCursor.java` — cursor → SmartPlaylist mapper
- `storage/database/.../mapper/SmartPlaylistRuleCursor.java` — cursor → SmartPlaylistRule mapper
- `storage/database/.../mapper/SmartPlaylistRuleQuery.java` — rule → SQL WHERE clause builder

### UI - Fragments
- `app/.../smartplaylist/SmartPlaylistListFragment.java` — list of all smart queues
- `app/.../smartplaylist/SmartPlaylistEditFragment.java` — create/edit smart queue rules
- `app/.../smartplaylist/SmartPlaylistDetailFragment.java` — view queue episodes, play, regenerate
- `app/.../smartplaylist/SmartPlaylistRuleEditDialog.java` — dialog for editing individual rules
- `app/.../home/sections/SmartPlaylistsSection.java` — home screen card section

### UI - Adapters
- `app/.../smartplaylist/SmartPlaylistListAdapter.java` — RecyclerView adapter for list screen
- `app/.../smartplaylist/SmartPlaylistCardAdapter.java` — card adapter for home section
- `app/.../smartplaylist/SmartPlaylistRuleAdapter.java` — adapter for rule list in edit screen

### UI - Layouts
- `app/.../layout/fragment_smart_playlist_list.xml`
- `app/.../layout/fragment_smart_playlist_edit.xml`
- `app/.../layout/fragment_smart_playlist_detail.xml`
- `app/.../layout/dialog_smart_playlist_rule_edit.xml`
- `app/.../layout/item_smart_playlist_card.xml`
- `app/.../layout/item_smart_playlist_add_card.xml`
- `app/.../layout/item_smart_playlist_list.xml`
- `app/.../layout/item_smart_playlist_rule.xml`
- `app/.../menu/smart_playlist_detail.xml`

### UI - Drawables
- `ui/common/.../drawable/ic_smart_playlist.xml`
- `ui/common/.../drawable/ic_chevron_right.xml`
- `ui/common/.../drawable/ic_drag_handle.xml`
- `ui/common/.../drawable/ic_edit.xml`

## Modified upstream files (conflict risk on rebase)

### High conflict risk — significant fork-specific logic

These files contain substantial smart queue logic interleaved with upstream code.
Look for `// FORK:` comments at insertion points when rebasing.

| File | What changed |
|------|-------------|
| `storage/database/.../PodDBAdapter.java` | CREATE TABLE statements for SmartPlaylists/Rules/Episodes; CRUD methods |
| `storage/database/.../DBReader.java` | `getSmartPlaylist()`, `getSmartPlaylistEpisodes()`, `getNextInSmartQueue()`, `isItemInSmartQueue()`, `getPausedQueue()` |
| `storage/database/.../DBWriter.java` | `createSmartPlaylist()`, `updateSmartPlaylist()`, `deleteSmartPlaylist()`, `generateSmartPlaylist()`, `generateSmartPlaylistSync()`, batch favorites/played methods |
| `storage/database/.../DBUpgrader.java` | Migration block at version 3120000 |
| `playback/service/.../Media3PlaybackService.java` | Smart queue logic in `startNextInQueue()`, auto-rewind in `play()` and `onPlaybackSuppressionReasonChanged`, `wasTemporarilySuspended` field |
| `storage/preferences/.../PlaybackPreferences.java` | `writeActiveSmartQueueId()`, `getActiveSmartQueueId()`, `clearActiveSmartQueueId()` |

### Medium conflict risk — small insertions into upstream code

| File | What changed |
|------|-------------|
| `app/.../PodcastApp.java` | Added `registerMediaBrowserService()` in `onCreate()` |
| `app/.../home/HomeFragment.java` | Added `SmartPlaylistsSection` to home sections list |
| `playback/service/.../MediaLibrarySessionCallback.java` | `CONTINUE_LISTENING` browse node, `enrichMediaItems()` helper, `onPlaybackResumption` fallback, `refreshNotification()` |
| `playback/base/.../MediaItemAdapter.java` | `fromFeed(Context, Feed)` with cached artwork, `KEY_AUTHORIZATION_HEADER`, auth header in `fromPlayable()` |
| `playback/base/.../RewindAfterPauseUtils.java` | Added `MINIMUM_REWIND` constant and 0ms elapsed branch |
| `event/.../FeedItemEvent.java` | Added `unreadStatusChanged` flag and constructor overload |
| `ui/i18n/.../values/strings.xml` | ~30 new string resources for smart queue UI |
| `ui/preferences/.../values/arrays.xml` | Smart queue sort order entries |

### Low conflict risk — upstream sync changes only (no fork logic)

These files were modified only to incorporate upstream bug fixes. They contain no
fork-specific logic and can be safely overwritten during a rebase.

| File | Upstream PRs applied |
|------|---------------------|
| `playback/service/.../internal/ExoPlayerUtils.java` | #8493 (BT disconnect), #8430 (auth streams) |
| `playback/service/.../PlaybackServiceStarter.java` | #8429 (getId fix) |
| `playback/base/build.gradle` | #8430 (net:common dependency) |
| `app/.../SleepTimerDialog.java` | #8499 (Bundle.EMPTY) |
| `app/.../chapter/ChaptersFragment.java` | #8488 (Media3 API) |
| `app/.../playback/TranscriptDialogFragment.java` | #8488 (Media3 API) |
| `app/.../playback/audio/CoverFragment.java` | #8488, #8497 (Media3 API, cover click) |
| `app/.../preferences/UserInterfacePreferencesFragment.java` | Hide persistent notification on Android 11+ |
| `storage/preferences/.../UserPreferences.java` | `PREF_PERSISTENT_NOTIFICATION` made public |
| `ui/preferences/.../xml/preferences_playback.xml` | #8433 (remove dead focus loss pref) |
| `app/src/androidTest/.../PreferencesTest.java` | #8433 (remove dead test) |
| 14 fragment files | #8439 (viewBinding cleanup in onDestroyView) |

## Upstream sync log

| Date | Upstream ref | Commit | PRs applied |
|------|-------------|--------|-------------|
| 2026-06-03 | develop ~May 31 | `b89024a` | #8493, #8429, #8502, #8453, #8476, #8495, #8430, #8499, #8439, #8488, #8497, #8433 |
| 2026-06-03 | develop ~Apr 19 | `55d5eee` | #8365, #8358, #8346, #8317, #8321, #8332 + Bluetooth registration |
| 2026-06-02 | develop ~Apr 10 | `4f181fb` | Schedulers.computation(), auto-rewind, sleep timer fix |
| 2026-03-22 | develop `5de8d4b` | — | Initial fork point |

## Upstream PRs not yet applied

These were reviewed but skipped for specific reasons:

| PR | Title | Reason skipped |
|----|-------|---------------|
| #8419 | Search queue support | Requires DBReader/FeedItemFilter API changes across many files |
| #8443 | Gradle version catalogs migration | Massive structural change, high conflict risk |
| #8436/#8501 | Redirect caching in ResolvingDataSource | Partially applied (auth header yes, caching no) |
| #8281 | Parental Control | New feature, not relevant to fork goals |
| #8396 | WearOS app | New module, not relevant to fork goals |
| #8463 | Echo 2026 bringup | Version-specific, not relevant |

## Rebase checklist

When rebasing on a new upstream release:

1. **Check DB version** — if upstream bumps past 3120000, our migration block may need adjustment
2. **Check `Media3PlaybackService.java`** — our `startNextInQueue()` method has the most fork-specific code; look for upstream changes to `onPlaybackStateChanged`, `onIsPlayingChanged`, `startNextInQueue`
3. **Check `PodDBAdapter.java`** — any new upstream tables or schema changes need to coexist with our 3 tables
4. **Check `DBReader.java` / `DBWriter.java`** — our methods are additive (new methods only) but imports and class structure may shift
5. **Check `MediaLibrarySessionCallback.java`** — our browse tree changes (`CONTINUE_LISTENING`, `enrichMediaItems`) may conflict with upstream browse tree modifications
6. **Check `PlaybackPreferences.java`** — our smart queue ID methods are additive
7. **Run `grep -r "FORK:" .`** to find all marked insertion points

## Backup and restore

Smart queue data lives in these DB tables:
- `SmartPlaylists` — queue definitions (name, sort, limit, auto-regenerate)
- `SmartPlaylistRules` — filter rules per queue
- `SmartPlaylistEpisodes` — generated episode lists (ephemeral, regenerated on demand)

Standard AntennaPod database export/import includes these tables. The `SmartPlaylistEpisodes`
table is regenerated automatically, so only `SmartPlaylists` and `SmartPlaylistRules` are
essential for backup.

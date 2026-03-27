# Smart Playlists for AntennaPod - Implementation Plan

## Context

AntennaPod lacks dynamic/smart playlist functionality that apps like BeyondPod, Pocket Casts, and Overcast provide. Users want to create rule-based playlists that automatically populate with matching episodes (e.g., "all unplayed tech podcasts shorter than 30 min"). This is a long-requested feature (AntennaPod/AntennaPod#2648). We're building this in a fork named "AntennaPodSmartPlaylist".

## Requirements Summary

- **Filter criteria**: Feed name, played/unplayed/new, downloaded, date published, duration, starred/favorited, media type (audio/video), keyword search (title/description), episode age limit
- **Rule priority ordering**: Each playlist has ordered rules; episodes matching rule 1 appear first, then rule 2, etc.
- **Snapshot playlists**: When a playlist is generated, it creates a concrete episode list. New podcast updates do NOT modify an existing playlist — it stays as-is until it ends and is regenerated.
- **Home page cards**: Smart playlists appear as tappable cards on the Home screen, above "Continue listening". Tapping a card navigates to the playlist and starts playback.
- **Auto-regenerate on completion**: When all episodes in a playlist are played, option (default: ON) to regenerate from rules with fresh episodes.
- **No "Play All" / "Add to Queue" buttons** — the card tap handles playback directly.
- **No episode limit**, no auto-download
- **App rename**: "AntennaPod" -> "AntennaPodSmartPlaylist"

---

## Phase 1: Web Interactive Prototype

Build an HTML/JS/CSS prototype at `prototype/smart-playlist.html` to demonstrate the UI flow before Android implementation. **User must review and approve the prototype before any Android code is written.**

**Screens to mock:**
1. **Home page** - Shows smart playlist cards in a horizontal scroll above "Continue listening"
   - Each card: playlist name, episode count, tap-to-play visual
   - "+" card to create a new playlist
2. **Playlist detail** - Episode list (the snapshot), grouped by rule priority
   - Shows which rule matched each episode group
   - "Regenerate" button to rebuild the snapshot
   - Auto-regenerate toggle
3. **Create/Edit playlist** - Rule builder with:
   - Playlist name field
   - Auto-regenerate toggle (default: ON)
   - Ordered list of rules (drag to reorder)
   - Each rule has all filter criteria (see below)
   - "Add Rule" button
   - "Generate Playlist" button to create snapshot
   - Preview of matching episodes grouped by rule

**Rule builder UI concept:**
- Rule = a set of filter criteria that match episodes
- Multiple rules per playlist, each with a priority (position in list)
- Within a rule: all criteria are AND'd together (e.g., "Feed = TechPod AND Status = Unplayed AND Duration < 30min")
- Between rules: episodes from higher-priority rules appear first in the playlist
- **Snapshot model**: generating a playlist creates a concrete, frozen episode list

**Filter criteria per rule:**
- Feed selection: individual feeds OR by tag/category (e.g., "Security" tag selects all feeds in that category)
- Played status (played / unplayed — where "unplayed" includes both NEW and UNPLAYED states)
- Downloaded status (downloaded / not downloaded)
- Favorited (yes / no)
- Media type (audio / video / any)
- Duration range (min and max minutes)
- Episode age limit (max days old)
- Keyword search (title and/or description)
- **Episode count per rule** (e.g., "1 episode" or "all" — allows "give me 1 random Security episode")
- **Sort/pick mode per rule**: newest first, oldest first (catch-up mode), shortest, longest, or random

---

## Phase 2: Data Model & Database

### New Model Classes

**`model/src/main/java/de/danoeh/antennapod/model/feed/SmartPlaylist.java`**
```java
public class SmartPlaylist {
    private long id;
    private String name;
    private boolean autoRegenerate;  // regenerate when all episodes played (default: true)
    private long createdAt;  // epoch ms
    private long updatedAt;  // epoch ms
    private List<SmartPlaylistRule> rules;  // ordered by position (the template)
    private List<Long> generatedEpisodeIds;  // snapshot: concrete episode list, in order
    private long generatedAt;  // when the snapshot was last generated
}
```

**`model/src/main/java/de/danoeh/antennapod/model/feed/SmartPlaylistRule.java`**
```java
public class SmartPlaylistRule {
    private long id;
    private long playlistId;
    private int position;  // determines priority ordering
    // Filter criteria (all AND'd together within this rule)
    private String filterProperties;     // reuses FeedItemFilter format: "unplayed,downloaded,..."
    private String feedIds;              // comma-separated feed IDs, empty = all feeds
    private String feedTags;             // comma-separated tag names (e.g., "Security,Tech"), empty = any
    private String titleKeyword;         // keyword search in episode title
    private String descriptionKeyword;   // keyword search in episode description
    private int maxAgeDays;              // 0 = no age limit
    private int minDurationMs;           // 0 = no minimum
    private int maxDurationMs;           // 0 = no maximum
    private String mediaType;            // "audio", "video", or "" for any
    private int episodeLimit;            // 0 = no limit, N = pick N episodes from matches
    private String sortOrder;            // "NEWEST", "OLDEST" (catch-up), "SHORTEST", "LONGEST", "RANDOM"
}
```

### Database Schema Changes

**File**: `storage/database/src/main/java/de/danoeh/antennapod/storage/database/PodDBAdapter.java`

New tables:
```sql
CREATE TABLE SmartPlaylists (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    auto_regenerate INTEGER NOT NULL DEFAULT 1,
    generated_at INTEGER DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- Snapshot: the concrete episode list for a playlist (order matters)
CREATE TABLE SmartPlaylistEpisodes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    playlist_id INTEGER NOT NULL,
    episode_id INTEGER NOT NULL,
    position INTEGER NOT NULL,
    FOREIGN KEY (playlist_id) REFERENCES SmartPlaylists(id) ON DELETE CASCADE
);

CREATE TABLE SmartPlaylistRules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    playlist_id INTEGER NOT NULL,
    position INTEGER NOT NULL,
    filter_properties TEXT DEFAULT '',
    feed_ids TEXT DEFAULT '',
    feed_tags TEXT DEFAULT '',
    title_keyword TEXT DEFAULT '',
    description_keyword TEXT DEFAULT '',
    max_age_days INTEGER DEFAULT 0,
    min_duration_ms INTEGER DEFAULT 0,
    max_duration_ms INTEGER DEFAULT 0,
    media_type TEXT DEFAULT '',
    episode_limit INTEGER DEFAULT 0,
    sort_order TEXT DEFAULT 'NEWEST',
    FOREIGN KEY (playlist_id) REFERENCES SmartPlaylists(id) ON DELETE CASCADE
);
```

**DB Version**: Increment from 3110000 to 3120000

**File**: `storage/database/src/main/java/de/danoeh/antennapod/storage/database/DBUpgrader.java`
- Add upgrade case for 3120000 that creates the two new tables

### New Cursor Mappers

**`storage/database/src/main/java/de/danoeh/antennapod/storage/database/mapper/SmartPlaylistCursor.java`**
- Maps cursor to SmartPlaylist object

**`storage/database/src/main/java/de/danoeh/antennapod/storage/database/mapper/SmartPlaylistRuleCursor.java`**
- Maps cursor to SmartPlaylistRule object

### DBReader Methods (new)

**File**: `storage/database/src/main/java/de/danoeh/antennapod/storage/database/DBReader.java`

- `getSmartPlaylists()` -> `List<SmartPlaylist>` (all playlists with their rules)
- `getSmartPlaylist(long id)` -> `SmartPlaylist` (single playlist with rules)
- `getSmartPlaylistEpisodes(SmartPlaylist playlist)` -> `List<FeedItem>` (episodes matching all rules, ordered by rule priority then sort within rule)

### DBWriter Methods (new)

**File**: `storage/database/src/main/java/de/danoeh/antennapod/storage/database/DBWriter.java`

- `createSmartPlaylist(SmartPlaylist)` -> `Future`
- `updateSmartPlaylist(SmartPlaylist)` -> `Future`
- `deleteSmartPlaylist(long playlistId)` -> `Future`

### Query Engine

**New file**: `storage/database/src/main/java/de/danoeh/antennapod/storage/database/mapper/SmartPlaylistRuleQuery.java`

For each rule, build a SQL query:
```sql
SELECT FeedItems.*, FeedMedia.* FROM FeedItems
LEFT JOIN FeedMedia ON FeedItems.id = FeedMedia.feeditem
WHERE
  <FeedItemFilterQuery conditions from rule.filterProperties>
  AND (rule.feedIds IS EMPTY OR FeedItems.feed IN (feedId1, feedId2, ...))
  AND (rule.titleKeyword IS EMPTY OR FeedItems.title LIKE '%keyword%')
  AND (rule.descriptionKeyword IS EMPTY OR FeedItems.description LIKE '%keyword%')
  AND (rule.maxAgeDays = 0 OR FeedItems.pubDate > (now - maxAgeDays*86400000))
  AND (rule.minDurationMs = 0 OR FeedMedia.duration >= minDurationMs)
  AND (rule.maxDurationMs = 0 OR FeedMedia.duration <= maxDurationMs)
  AND (rule.mediaType IS EMPTY OR FeedMedia.mime_type LIKE 'audio/%' or 'video/%')
ORDER BY <rule.sortOrder>
```

Execute each rule query in priority order, collect results, deduplicate (episode matched by earlier rule stays in that position).

---

## Phase 3: Android UI

### Home Page Integration

The home page uses a section-based pattern. Each section extends `HomeSection` (which extends Fragment) and provides:
- `getSectionTitle()`, `getMoreLinkTitle()`, `handleMoreClick()`
- RxJava data loading → adapter update
- EventBus for reactive updates

Sections are registered in `HomeFragment.getSection(tag)` switch statement and ordered via `HomePreferences`.

**Files to modify:**
- `app/src/main/java/de/danoeh/antennapod/ui/screen/home/HomeFragment.java` - Add SmartPlaylistsSection to `getSection()` switch, ensure it appears first
- `ui/preferences/src/main/res/values/arrays.xml` - Add smart_playlists tag to `home_section_tags` and title to `home_section_titles`
- `app/src/main/java/de/danoeh/antennapod/ui/screen/home/settingsdialog/HomePreferences.java` - Register new section

### New Files

1. **Home section**: `app/src/main/java/de/danoeh/antennapod/ui/screen/home/sections/SmartPlaylistsSection.java`
   - Extends `HomeSection`
   - Horizontal RecyclerView of playlist cards (like `SubscriptionsSection` pattern)
   - Uses `SmartPlaylistCardAdapter`
   - Loads via `DBReader.getSmartPlaylists()`
   - Card tap → navigate to `SmartPlaylistDetailFragment` with auto-play flag
   - "+" card at end to create new playlist

2. **Fragment**: `app/src/main/java/de/danoeh/antennapod/ui/screen/smartplaylist/SmartPlaylistDetailFragment.java`
   - Shows episodes in a playlist's snapshot
   - Auto-starts playback when navigated from home card tap
   - When all episodes played: if autoRegenerate is ON, regenerate and show new list
   - Toolbar: edit, delete, regenerate actions

3. **Fragment**: `app/src/main/java/de/danoeh/antennapod/ui/screen/smartplaylist/SmartPlaylistEditFragment.java`
   - Create/edit a smart playlist
   - Name field, auto-regenerate toggle
   - RecyclerView of rules (drag-reorderable via ItemTouchHelper)
   - Each rule row expands to show filter criteria
   - "Generate Playlist" button to create snapshot from rules

4. **Dialog**: `app/src/main/java/de/danoeh/antennapod/ui/screen/smartplaylist/SmartPlaylistRuleEditDialog.java`
   - Edit a single rule's criteria
   - Feed selector (multi-select from subscriptions)
   - Status filters (played/unplayed/new checkboxes)
   - Downloaded filter
   - Favorited filter
   - Duration range (min/max sliders)
   - Age limit (days input)
   - Keyword search (title, description text fields)
   - Media type (audio/video/any radio)
   - Sort order within this rule

5. **Adapter**: `app/src/main/java/de/danoeh/antennapod/ui/screen/smartplaylist/SmartPlaylistCardAdapter.java`
   - RecyclerView adapter for home page cards

6. **Adapter**: `app/src/main/java/de/danoeh/antennapod/ui/screen/smartplaylist/SmartPlaylistRuleAdapter.java`
   - RecyclerView adapter for rules within the edit screen

### Layout Files

- `app/src/main/res/layout/section_smart_playlists.xml` - Home page section with horizontal cards
- `app/src/main/res/layout/item_smart_playlist_card.xml` - Individual card on home page
- `app/src/main/res/layout/fragment_smart_playlist_detail.xml` - Episode list for a playlist
- `app/src/main/res/layout/fragment_smart_playlist_edit.xml` - Edit/create playlist
- `app/src/main/res/layout/item_smart_playlist_rule.xml` - Rule row in edit screen
- `app/src/main/res/layout/dialog_smart_playlist_rule_edit.xml` - Rule edit dialog

### String Resources

**File**: `app/src/main/res/values/strings.xml` - Add new strings:
- `smart_playlists_label`, `smart_playlists_label_short`
- `create_smart_playlist`, `edit_smart_playlist`, `delete_smart_playlist`
- `add_rule`, `edit_rule`, `rule_feeds`, `rule_status`, etc.
- `regenerate_playlist`, `auto_regenerate`, `auto_regenerate_summary`

### Drawable Resources

- Add `ic_smart_playlist.xml` (vector drawable, e.g., playlist icon with a filter/magic wand)

---

## Phase 4: Navigation Integration

### Files to Modify

1. **`app/src/main/java/de/danoeh/antennapod/ui/screen/drawer/NavigationNames.java`**
   - Add `SmartPlaylistsFragment.TAG` cases to all switch statements (getDrawable, getLabel, getShortLabel, getBottomNavigationItemId, getBottomNavigationFragmentTag)

2. **`app/src/main/res/menu/nav_bottom.xml`** (or equivalent)
   - Add smart playlists menu item

3. **`app/src/main/java/de/danoeh/antennapod/ui/screen/drawer/NavDrawerFragment.java`**
   - Include smart playlists in drawer items

4. **`app/src/main/java/de/danoeh/antennapod/activity/MainActivity.java`**
   - Handle navigation to SmartPlaylistsFragment and SmartPlaylistDetailFragment

---

## Phase 5: App Rename

**File**: `common.gradle` (lines 22-26)
```groovy
release {
    resValue "string", "app_name", "AntennaPodSmartPlaylist"
}
debug {
    resValue "string", "app_name", "AntennaPodSmartPlaylist Debug"
}
```

---

## Implementation Order

1. **Web prototype** (`prototype/smart-playlist.html`) - for user review & approval before Android work
2. **App rename** in `common.gradle`
3. **Model classes** (SmartPlaylist, SmartPlaylistRule)
4. **Database schema** (PodDBAdapter tables, DBUpgrader, cursor mappers)
5. **Query engine** (SmartPlaylistRuleQuery) + snapshot generation
6. **DBReader/DBWriter methods** (including generate/regenerate)
7. **Home page section** (SmartPlaylistsSection cards)
8. **Detail fragment** (episode list, auto-play, auto-regenerate)
9. **Edit fragment + rule dialog** (create/edit playlist with rule builder)
10. **Navigation integration** (NavigationNames, drawer, MainActivity)
11. **String/drawable resources**

## Progress

- [x] Phase 0: Requirements gathering & plan design
- [ ] Phase 1: Web interactive prototype
- [ ] Phase 2: Data model & database
- [ ] Phase 3: Android UI
- [ ] Phase 4: Navigation integration
- [ ] Phase 5: App rename

## Verification

1. **Web prototype**: Open `prototype/smart-playlist.html` in a browser, verify:
   - Rule builder UI works (add/remove/reorder rules)
   - Each rule has all filter criteria
   - Preview shows episodes grouped by rule priority
   - Home page card mockup looks right
2. **Build**: `./gradlew assembleDebug` should compile without errors
3. **Unit tests**: Test SmartPlaylistRuleQuery SQL generation with various filter combinations
4. **Manual testing**: Install debug APK on device, verify:
   - Home page shows smart playlist cards above "Continue listening"
   - Tapping a card opens playlist and starts playback
   - Can create/edit/delete smart playlists via edit screen
   - Rules filter episodes correctly with rule priority ordering
   - Snapshot is stable (new episodes don't change existing playlist)
   - Auto-regenerate works when all episodes are played
   - App name shows as "AntennaPodSmartPlaylist"

# Smart Playlist Project - Status Checkpoint
## Last Updated: Session 018dS7hkuhocYuNSH4vFLerw

## Current State
- **Branch**: `claude/add-smart-playlists-fqUHX` (based off `develop`)
- **Latest commit**: `67ef5be` - Web interactive prototype pushed to GitHub
- **Phase**: Waiting for user review of Phase 1 (web prototype) before starting Phase 2+

## Completed
1. Requirements gathered from user
2. Implementation plan written and pushed (`SMART_PLAYLIST_PLAN.md`)
3. Web prototype built and pushed (`prototype/smart-playlist.html`)
   - 3 screens: Home (cards), Detail (episode list by rule), Edit (rule builder)
   - All filter criteria: tags, feeds, played status, downloaded, favorited, media type, duration, age, keyword, episode limit, sort order
   - Sample data with 10 feeds across 6 tags, ~80 episodes
   - Snapshot-based generation with rule priority ordering
   - 2 example playlists: "Morning Drive" (3 rules) and "Kids Time" (1 rule)

## Next Steps (after prototype approval)
1. Rename app in `common.gradle` ("AntennaPod" -> "AntennaPodSmartPlaylist")
2. Create model classes: `SmartPlaylist.java`, `SmartPlaylistRule.java` in `model/src/main/java/de/danoeh/antennapod/model/feed/`
3. Database schema: new tables in `PodDBAdapter.java`, upgrade in `DBUpgrader.java`
4. Cursor mappers: `SmartPlaylistCursor.java`, `SmartPlaylistRuleCursor.java`
5. Query engine: `SmartPlaylistRuleQuery.java`
6. DBReader/DBWriter methods
7. Home page section: `SmartPlaylistsSection.java` (extends HomeSection)
8. Detail fragment, Edit fragment, Rule edit dialog
9. Navigation integration

## Key Architecture Notes
- Database: Raw SQLite via SQLiteOpenHelper (NOT Room), DB version 3110000
- Navigation: Fragment-based with manual FragmentManager
- Home sections: extend `HomeSection`, registered in `HomeFragment.getSection()` switch
- Existing filters: `FeedItemFilter` + `FeedItemFilterQuery` for SQL generation
- Feed tags: `FeedPreferences.getTags()` for categories like "Security"
- Queue: separate table, smart playlists are independent of queue

## Key User Requirements
- Snapshot playlists (frozen episode list, not dynamic)
- Auto-regenerate when all episodes played (default: ON)
- Home page cards above "Continue Listening" (tap to play)
- No "Play All" / "Add to Queue" buttons
- Rule priority ordering (rule 1 episodes first, then rule 2, etc.)
- Per-rule episode limit and sort order (newest, oldest/catch-up, random, etc.)
- Feed selection by individual feed OR by tag/category

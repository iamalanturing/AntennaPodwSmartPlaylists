# Fork notes: Smart Queue

This fork of AntennaPod adds **Smart Queues** — rule-based playlists that populate themselves
from filters (feeds, tags, age, duration, media type) instead of being filled by hand.

- **Branch:** `claude/add-smart-playlists-v2`
- **Upstream base:** `b7ee12c` (2026-07-21) from `upstream/develop`, plus `b25adc2` from
  `upstream/master` (the 3.12.0-beta line, 20 commits)
- **Modified upstream files:** 11 — the rest of the fork is new files

An earlier attempt lives on `claude/add-smart-playlists-fqUHX`. It touched 137 files and
modified 40+ upstream ones, including breaking API changes. This branch is a cleaner
reimplementation and is the one to work from; treat the old branch as reference only.

---

## Read this before merging upstream

### The database version number is the sharp edge

The fork sets `PodDBAdapter.VERSION = 3120000` to carry its tables. **Upstream had not reached
that number** — as of this writing its `VERSION` is still `3110000` and its highest migration
block is `3080000`. The fork took a number out of upstream's future.

Upstream's migration chain is a series of `if (oldVersion < N)` blocks. If a database is already
stamped `3120000` and upstream later adds a migration numbered at or below that, the block never
runs. Nothing throws — upstream's new columns are simply missing, and it surfaces later as
unrelated-looking crashes.

So the upstream chain is **not** driven by the version stamp. `DBUpgrader.upgrade` reads a
separately recorded level from the fork-owned `ForkSchema` table.

**When you merge upstream:**

1. Raise `PodDBAdapter.UPSTREAM_SCHEMA_LEVEL` to upstream's current `VERSION`, **in the same
   commit as the merge**. This is what makes newly merged migrations run once on existing
   installs. Forgetting it silently reintroduces the bug.
2. Keep `UPSTREAM_SCHEMA_LEVEL` strictly below `VERSION`. `SmartQueueSchemaMigrationTest`
   enforces this — if upstream's numbering reaches the fork's stamp, raise `VERSION` above it.
3. **Never edit `LEGACY_FORK_STAMP` (3120000) or `LEGACY_FORK_UPSTREAM_LEVEL` (3110000).** They
   are frozen historical facts about databases already on people's phones — the mapping used to
   place a pre-bookkeeping database correctly on the upstream chain. They are deliberately not
   derived from `VERSION`: bumping `VERSION` during a merge would then make those devices look
   like genuine upstream installs and skip the very migration being merged.

Two related constraints worth knowing before touching `VERSION`:

- `DatabaseExporter` requires an **exact** version match to export, and refuses to import any
  backup whose version is **above** `VERSION`. Lowering `VERSION` would break export and
  permanently reject backups users already hold.
- Fork DDL is `IF NOT EXISTS` and applied unconditionally via `PodDBAdapter.createForkSchema`,
  not behind a version gate, so the tables exist whatever stamp a database arrives with.
- `ForkSchema` is intentionally **not** in `ALL_TABLES`, so clearing user content via
  `deleteDatabase()` does not discard the migration record.

### Column names differ from the older branch

This branch prefixes every Smart Queue column with `sp_` — `sp_name`, `sp_playlist_id` and so
on. The older `fqUHX` branch did not. A database written by that branch, including any backup
restored from one, therefore carries the unprefixed names, and every query here would miss:
the feature comes up empty rather than failing loudly.

`PodDBAdapter.migrateLegacySmartQueueSchema` converts them, recreate-and-copy rather than
`ALTER TABLE ... RENAME COLUMN` because that needs SQLite 3.25 and `minSdk` is 23. It runs
before `createForkSchema`, since those statements are `IF NOT EXISTS` and would otherwise leave
a legacy table untouched. Detection keys off a legacy column being present, so it is a no-op on
current databases and safe on every upgrade.

`VERSION` is 3120001 specifically to make this reachable: a restored legacy backup is stamped
3120000, and without a higher version no upgrade fires and the conversion never happens.
`LegacySmartQueueMigrationTest` covers it, using DDL copied verbatim from the older branch.

### The home section is the part that breaks

The fork registers `SmartPlaylistsSection` in the parallel arrays `home_section_tags` /
`home_section_titles` (`ui/preferences/src/main/res/values/arrays.xml`) and adds a case to
`HomeFragment.getSection`. Upstream #8611 then added a second lookup,
`HomeFragment.getSectionContainerId`, mapping each tag to a fixed view id — **and its default
branch throws `IllegalArgumentException`**.

Any tag present in the arrays but missing from *either* switch now crashes the home screen the
moment it opens. So a section needs three things kept in step, and the compiler checks none of
them:

1. an entry in `home_section_tags` / `home_section_titles` (order-sensitive, same index)
2. a case in `HomeFragment.getSection`
3. a case in `HomeFragment.getSectionContainerId` **and** a matching
   `home_section_*` id in `app/src/main/res/values/ids.xml`

After merging anything that touches home sections, open the home screen and confirm the Smart
Queue card is there.

### `master` and `develop` are not interchangeable bases

The fork sits on `develop`. Files arriving from `master` can be written against the older
conventions that line still uses, and nothing about the merge flags it — Gradle only fails at
configuration time, and only when that script is applied.

The 3.12.0-beta merge brought in `mockitoAgent.gradle` referencing `$mockitoVersion`, an ext
property `develop` had already replaced with the version catalog. Same for the test dependencies
in `playback/service/build.gradle`, which arrived as `"junit:junit:$junitVersion"` strings. Both
were converted to `libs.` accessors. After merging `master`, grep the tree for `$…Version` in
`*.gradle` — the catalog is the only supported form on this branch.

### Also re-check

`.github/workflows/fork-checks.yml` is fork-owned and deliberately separate from upstream's
`checks.yml` so it never conflicts. The cost is drift: compare the two during each sync and
adopt anything worth having.

### Dependencies: track upstream, do not get ahead of it

Every third-party version comes from upstream's `gradle/libs.versions.toml`, and several are
old — jsoup 1.15.1, okhttp 4.12.0, guava 31.0.1, rxjava 3.1.5, commons-io 2.5, media3 1.9.0.
Leave them alone.

They were audited and **no CVE is currently reachable from this codebase**: Guava's
CVE-2023-2976 needs `FileBackedOutputStream` and jsoup's CVE-2022-36033 needs the
`SafeList`/`Cleaner` API, neither of which AntennaPod uses — it only calls `Jsoup.parse`, and
the resulting HTML goes to a WebView with JavaScript disabled and no JS bridge. commons-io is
pinned deliberately: newer versions break Android 6, and `minSdk` is 23.

Bumping any of them would put the fork ahead of upstream in a file upstream edits regularly,
buying a merge conflict for no reachable benefit. Same reasoning for the dependency lint checks
disabled at `common.gradle:58` (`GradleDependency`, `OutdatedLibrary`,
`AndroidGradlePluginVersion`) — re-enabling them would fail CI on upstream's own choices, which
is noise rather than signal for a fork.

Revisit only if upstream bumps them, or if a CVE becomes reachable because this fork starts
using one of the affected APIs.

---

## What the fork adds

**Model** — `SmartPlaylist`, `SmartPlaylistRule` (`model/.../feed/`).

**Storage** (`storage/database/.../`) — `mapper/SmartPlaylistCursor`,
`mapper/SmartPlaylistRuleCursor`, `mapper/SmartPlaylistRuleQuery` (compiles a rule into a SQL
WHERE/ORDER BY clause), plus tests `SmartQueueSchemaMigrationTest` and
`mapper/SmartPlaylistRuleQueryTest`.

**UI** (`app/.../ui/screen/smartplaylist/`) — list, detail and edit fragments, three adapters,
the rule edit dialog, and `ui/screen/home/sections/SmartPlaylistsSection` for the home card.
Layouts and menus under `app/src/main/res/`.

**Three tables**, created by `PodDBAdapter.createForkSchema`: `SmartPlaylists`,
`SmartPlaylistRules`, `SmartPlaylistEpisodes` (generated membership cache), plus the
`ForkSchema` bookkeeping table.

## Upstream files modified

| File | Change |
|---|---|
| `storage/database/.../PodDBAdapter.java` | `VERSION` bump, fork constants, table/index DDL, Smart Queue CRUD, `createForkSchema` / `readUpstreamSchemaLevel` / `writeUpstreamSchemaLevel`, `SmartPlaylistEpisodes` cleanup in `removeFeedItems` |
| `storage/database/.../DBUpgrader.java` | Drives the upstream chain off the recorded level; applies fork DDL unconditionally |
| `storage/database/.../DBReader.java` | Smart Queue read methods |
| `storage/database/.../DBWriter.java` | Smart Queue CRUD, transactional regeneration, clears the active queue id on delete |
| `storage/preferences/.../PlaybackPreferences.java` | Active smart queue id |
| `playback/service/.../Media3PlaybackService.java` | Advances within a smart queue; auto-regenerates at end of queue |
| `app/.../PodcastApp.java` | Registers the media browser service at startup for Bluetooth/AVRCP |
| `app/.../MainActivity.java`, `.../home/HomeFragment.java` | Fragment and home-section wiring |
| `ui/i18n/.../values/strings.xml`, `ui/preferences/.../values/arrays.xml` | Strings and home-section registration |

Note this branch does **not** touch `PlaybackService.java` (the legacy service),
`MediaItemAdapter`, or the launcher icons — all of which the older fqUHX branch did. Rebasing
is correspondingly less painful.

Fork insertions in upstream files are marked `// FORK:`. Treat that as a hint, not an
inventory — the markers do not cover every touched line.

## Verification

`.github/workflows/fork-checks.yml` runs on every push to `claude/**`: `assemblePlayDebug`,
then `testPlayDebugUnitTest testDebugUnitTest` (the second is what covers the library modules
where the Smart Queue tests live), plus `checkstyle lint`.

After a merge, beyond a green CI run, check on a device with a **backed-up** database:
upgrading over an existing install keeps both playlists and their episode counts; a fresh
install creates the tables; deleting a smart queue mid-playback falls back to the normal queue;
and the home screen still renders.

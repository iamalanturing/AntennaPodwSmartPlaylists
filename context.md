# Working context

Where this fork stands and what to pick up next. `FORK.md` covers how the fork is built and
what must not be broken when merging upstream; this file is the current state.

**Branch:** `claude/add-smart-playlists-v2` — the canonical one. `claude/add-smart-playlists-fqUHX`
is an earlier attempt kept only for reference; do not build from it. It is still worth reading when a
v2 screen looks wrong: v2's UI was written fresh rather than ported, so details fqUHX got right
(window insets, for one) were silently dropped.

**Upstream base:** `b7ee12c` (2026-07-21) from `upstream/develop`, plus `b25adc2` from
`upstream/master` (3.12.0-beta, 20 commits). 47 commits on top.

**Tip:** `e35486c` (2026-07-28). The side branches this work passed through
(`claude/merge-upstream-master`, `claude/status-bar-overlap-fix-dxla5j`) were folded into v2 and
deleted; `fqUHX` stays.

---

## Pick up here

The backlog in **Outstanding** is empty — the two remaining UX gaps were closed. The media-type
dropdown and the list empty state are pushed but **not yet exercised on a device**: everything
above them in *Verified on a real device* has been, these two have not. Check them on the phone
before treating them as done, and add them to that list when you have.

Read `FORK.md` first if the work touches a screen or an upstream merge.

The APK for a green run is its `app-play-debug` artifact:
`https://github.com/iamalanturing/AntennaPodwSmartPlaylists/actions/runs/<run id>/artifacts/<artifact id>`
— list them with the GitHub Actions tooling rather than guessing ids. Always give the user the
link; they cannot build either.

## Verified on a real device

The branch had never been compiled or run until recently — everything below is confirmed
working on a Pixel 10 (API 36), not merely green in CI:

- App builds, installs and launches
- Both Smart Queues survived the database restore with rules intact
- Downloads working (bulk multi-select, and auto-download on Wi-Fi)
- **Android Auto works.** The app was invisible there purely because Auto ignores apps not
  installed from Play unless *Developer settings → Unknown sources* is enabled. Enabling it
  fixed both the missing app entry and the steering-wheel behaviour. BeyondPod being installed
  was never involved — an early theory of mine that turned out to be wrong.
- Smart queue screens sit below the status bar, and the queue's Play button starts at the first
  unplayed episode
- A queue advances to its next episode, including with continuous playback off, while a normal
  queue still stops; Play/Pause toggles; playing an episode from inside a queue continues from
  there; an exhausted queue rebuilds and plays something new
- The detail screen refreshes when a queue is rebuilt, an episode finishes, rules change or a
  queue is deleted
- The rule editor: podcast picker, tag picker, and a rule list that scrolls to every rule (a queue
  here has fourteen)
- Dragging a rule several positions in one gesture, with the list auto-scrolling at the edges and
  each count staying with its own rule
- Deleting a queue mid-playback: the episode keeps playing and the normal queue takes over
- Playback speed carries to the next episode, and streaming over mobile data asks first (both
  from the `upstream/master` merge)
- Per-rule episode counts are accurate; rules reading 0 were correct, their episodes simply were
  not downloaded

Everything the fork does has now been exercised on a device at least once.

## Building

CI does everything; no local Android SDK is needed, and this container cannot build one
(`dl.google.com` and `maven.google.com` are blocked by the egress proxy).

`.github/workflows/fork-checks.yml` runs on every push to `claude/**`: `assemblePlayDebug`,
then `testPlayDebugUnitTest testDebugUnitTest`, then `checkstyle lint`. Each green run attaches
an installable APK as the `app-play-debug` artifact.

It is fork-owned and deliberately separate from upstream's `checks.yml` so it never conflicts on
merge; re-check it against upstream's during each sync.

Two behaviours worth knowing:

- **Lint prints only its first error.** The workflow dumps the full report on failure, otherwise
  fixing a batch costs one CI round trip per error.
- **The APK signer is asserted, not just printed.** CI fails if the APK is not signed with the
  fork key (`d4f2eaa3…`). This exists because a wrong key is otherwise only discovered after an
  uninstall has already destroyed the database and downloads.

### Signing

Debug builds are signed with a stable fork key so each CI build installs over the last. The key
lives in repo secrets `DEBUG_KEYSTORE_BASE64` / `DEBUG_KEYSTORE_PASSWORD`, never in git
(`*.keystore` is gitignored). The user holds the only backup of `fork-debug.keystore`; losing it
means another uninstall-and-restore cycle.

Signing properties must go in **`gradle.properties`**, not `local.properties` — nothing in this
build reads the latter, and writing there leaves the config silently unregistered.

## Database

`VERSION = 3120001`. Two mechanisms matter and both are easy to break:

1. **The upstream migration chain runs off a recorded level, not the version stamp**
   (`readUpstreamSchemaLevel` / `ForkSchema` table). The fork took 3120000 before upstream got
   there, so the stamp lies about what upstream schema a database has. Raise
   `UPSTREAM_SCHEMA_LEVEL` to upstream's `VERSION` in the same commit as any upstream merge.
   Never edit `LEGACY_FORK_STAMP` / `LEGACY_FORK_UPSTREAM_LEVEL` — frozen historical facts.
2. **Legacy column conversion.** This branch prefixes Smart Queue columns with `sp_`; fqUHX did
   not. `migrateLegacySmartQueueSchema` converts databases from the older branch. The `VERSION`
   bump to 3120001 is what makes it run at all — a restored legacy backup is stamped 3120000.

## Tests

- `LegacySmartQueueMigrationTest` — legacy conversion, using DDL copied verbatim from fqUHX
- `SmartQueueSchemaMigrationTest` — schema bootstrap, idempotency, upstream-level bookkeeping
- `SmartPlaylistRuleMatchCountTest` — what a single rule matches, and rule order surviving a save
  and reload, which is what the drag handle in the editor depends on
- `SmartPlaylistRuleQueryTest` — pins the SQL-injection defences (allowlisted ORDER BY, escaping,
  numeric validation). Injection is currently closed; these stop a refactor reopening it
- `HomeSectionSubscriberTest` — every `HomeSection` subclass must declare `@Subscribe`

Robolectric runs real SQLite, so the migration tests genuinely exercise the upgrade path rather
than mocking it.

## The episode cache gates auto-download

`AutomaticDownloadAlgorithm` computes `episodeSpaceLeft = cacheSize - (downloaded - deleted)` and
downloads that many candidates. The episode cache defaults to 20 and episode cleanup defaults to
`EPISODE_CLEANUP_NULL`, which deletes nothing — so once the number of downloaded episodes reaches
the cache size, auto-download quietly stops. Past it, `episodeSpaceLeft` goes negative and the
`candidates.subList(0, episodeSpaceLeft)` call throws inside the executor's Runnable, killing the
run with nothing user-visible.

This is upstream behaviour, not fork-specific, but it matters more here: a Smart Queue filtered on
`downloaded` depends entirely on auto-download to keep filling. Hand-downloading a backlog inflates
the downloaded count and can park the app on the wrong side of that limit. Set the episode cache to
unlimited, or well above the backlog.

## Outstanding

Nothing. Both UX gaps are closed: the rule editor now has a media-type dropdown (Any/Audio/Video,
shown in the rule summary too), and the list screen has an empty state built on `EmptyViewHandler`.

**Do not propose auto-download awareness of Smart Queues.** The user was asked directly and does
not want it — "I may never want it". The mechanism, for reference only: auto-download selects from
episodes marked NEW plus the regular queue, so a queue filtered on `downloaded` only fills as new
episodes arrive. Watch the episode cache instead — see above. This is a settled decision, not a
backlog item.

## Decisions worth not relitigating

- **Track upstream's dependency pins.** jsoup 1.15.1, okhttp 4.12.0, guava 31.0.1 and the rest
  are old, but no CVE is reachable from this codebase (verified at the usage level, not by
  version number) and getting ahead of upstream buys recurring merge conflicts for nothing.
  The dependency lint checks stay disabled for the same reason.
- **v2 over fqUHX.** v2 touches 11 upstream files where fqUHX touched 40+, and avoids fqUHX's
  breaking `MediaItemAdapter` signature change and rebranded launcher binaries.

## Lessons that cost real time

- **Verify against the branch you are actually on.** Early in this work the container was
  checked out on fqUHX; reading column names there and applying them to v2 produced the `sp_`
  prefix mismatch that later needed a migration to unpick.
- **Green CI is not a working app.** The home screen crashed on every launch because
  `SmartPlaylistsSection` had no `@Subscribe` method — invisible to the compiler, invisible to
  lint, fatal at runtime. Several defects only appeared once the thing was built and run.
- **Silent fallbacks hide failures.** The signing key was ignored for a whole build because
  properties went to the wrong file, and nothing failed. Assert, do not print. The same mistake
  reappeared in `getSmartPlaylistRuleMatchCount`, which caught every exception and returned zero —
  indistinguishable from a rule that matches nothing, and it cost several CI rounds before the
  catch was removed and the real cause showed up.
- **Read the defaults before asserting on them.** A new `SmartPlaylistRule` filters on
  `unplayed,downloaded`. Tests written assuming a bare rule matches everything failed against
  fixtures whose episodes were never downloaded, and the production code was right all along.
- **Never notify a whole dataset while a gesture is running.** `notifyDataSetChanged` during a
  drag ends the drag. Rebind in place; do structural refreshes when the finger lifts.
- **A screen with little data proves nothing.** The rule list clipped at seven rows and the add
  button looked inert, both only visible once content exceeded the display. `FORK.md` carries the
  full list of UI traps.

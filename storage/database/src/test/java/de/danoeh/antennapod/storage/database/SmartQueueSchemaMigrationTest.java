package de.danoeh.antennapod.storage.database;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The fork stamps the database with a VERSION that upstream had not reached, which makes the
 * migration path the easiest place in this codebase to lose user data. These tests cover the two
 * things that must hold: the Smart Queue schema can be applied repeatedly without destroying
 * anything, and a database already carrying the fork's stamp still upgrades cleanly.
 */
@RunWith(RobolectricTestRunner.class)
public class SmartQueueSchemaMigrationTest {
    private SQLiteDatabase db;

    @Before
    public void setUp() {
        db = SQLiteDatabase.create(null);
    }

    @After
    public void tearDown() {
        db.close();
    }

    private boolean exists(String type, String name) {
        try (Cursor cursor = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type=? AND name=?", new String[]{type, name})) {
            return cursor.moveToFirst();
        }
    }

    private int rowCount(String table) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    private void assertSmartQueueSchemaPresent() {
        assertTrue("SmartPlaylists missing", exists("table", PodDBAdapter.TABLE_NAME_SMART_PLAYLISTS));
        assertTrue("SmartPlaylistRules missing",
                exists("table", PodDBAdapter.TABLE_NAME_SMART_PLAYLIST_RULES));
        assertTrue("SmartPlaylistEpisodes missing",
                exists("table", PodDBAdapter.TABLE_NAME_SMART_PLAYLIST_EPISODES));
        assertTrue("rules index missing",
                exists("index", PodDBAdapter.TABLE_NAME_SMART_PLAYLIST_RULES + "_playlist"));
        assertTrue("episodes index missing",
                exists("index", PodDBAdapter.TABLE_NAME_SMART_PLAYLIST_EPISODES + "_playlist"));
        assertTrue("QueueStash missing", exists("table", PodDBAdapter.TABLE_NAME_QUEUE_STASH));
    }

    @Test
    public void bootstrapCreatesEverything() {
        PodDBAdapter.createForkSchema(db);
        assertSmartQueueSchemaPresent();
    }

    @Test
    public void bootstrapIsIdempotent() {
        // It now runs on every upgrade rather than behind a version gate, so re-running it must
        // not throw "table already exists".
        PodDBAdapter.createForkSchema(db);
        PodDBAdapter.createForkSchema(db);
        PodDBAdapter.createForkSchema(db);
        assertSmartQueueSchemaPresent();
    }

    @Test
    public void reapplyingSchemaKeepsExistingPlaylists() {
        // The whole point of IF NOT EXISTS: an existing playlist must survive a re-run rather
        // than being silently replaced by a fresh empty table.
        PodDBAdapter.createForkSchema(db);
        db.execSQL("INSERT INTO " + PodDBAdapter.TABLE_NAME_SMART_PLAYLISTS
                + " (" + PodDBAdapter.KEY_SMART_PLAYLIST_NAME
                + ", " + PodDBAdapter.KEY_SMART_PLAYLIST_CREATED_AT
                + ", " + PodDBAdapter.KEY_SMART_PLAYLIST_UPDATED_AT + ") VALUES ('Kids', 1, 1)");

        PodDBAdapter.createForkSchema(db);

        assertEquals(1, rowCount(PodDBAdapter.TABLE_NAME_SMART_PLAYLISTS));
    }

    @Test
    public void upgradeFromForkStampIsSafeAndCreatesSchema() {
        // Reproduces a device that already ran the fork's original migration and is stamped
        // 3120000. No upstream migration in the current chain applies at that level, so this
        // must complete without touching upstream tables that a real device would already have.
        DBUpgrader.upgrade(db, PodDBAdapter.VERSION, PodDBAdapter.VERSION);
        assertSmartQueueSchemaPresent();
    }

    @Test
    public void upgradeFromPreviousVersionAddsQueueStash() {
        // A device already stamped 3120001 (the fork's previous VERSION, before QueueStash
        // existed) must pick up the new table on its next open. This is what actually proves the
        // VERSION bump matters -- createForkSchema()'s IF NOT EXISTS alone would not run again on
        // a device Android does not consider due for onUpgrade().
        DBUpgrader.upgrade(db, 3120001, PodDBAdapter.VERSION);
        assertTrue("QueueStash missing", exists("table", PodDBAdapter.TABLE_NAME_QUEUE_STASH));
    }

    @Test
    public void upgradeIsRepeatableForAForkStampedDatabase() {
        DBUpgrader.upgrade(db, PodDBAdapter.VERSION, PodDBAdapter.VERSION);
        db.execSQL("INSERT INTO " + PodDBAdapter.TABLE_NAME_SMART_PLAYLISTS
                + " (" + PodDBAdapter.KEY_SMART_PLAYLIST_NAME
                + ", " + PodDBAdapter.KEY_SMART_PLAYLIST_CREATED_AT
                + ", " + PodDBAdapter.KEY_SMART_PLAYLIST_UPDATED_AT + ") VALUES ('Regular', 1, 1)");

        DBUpgrader.upgrade(db, PodDBAdapter.VERSION, PodDBAdapter.VERSION);

        assertSmartQueueSchemaPresent();
        assertEquals("user data must survive a repeated upgrade", 1,
                rowCount(PodDBAdapter.TABLE_NAME_SMART_PLAYLISTS));
    }

    @Test
    public void forkStampWithoutARecordIsTranslatedNotTrusted() {
        // The upgrade path for every device already out there: it carries the fork's 3120000
        // stamp but predates this bookkeeping, so the level has to be inferred. Trusting the
        // stamp here is exactly the bug this guards against.
        //
        // Asserted against the literal 3110000 rather than UPSTREAM_SCHEMA_LEVEL on purpose.
        // This mapping is a historical fact about databases already in the wild and must not
        // drift when UPSTREAM_SCHEMA_LEVEL is raised during a merge -- if the fallback were
        // keyed off a moving constant, bumping VERSION would make these devices look like
        // genuine upstream installs and skip the very migration being merged in.
        PodDBAdapter.createForkSchema(db);
        assertEquals(3110000, PodDBAdapter.readUpstreamSchemaLevel(db, 3120000));
    }

    @Test
    public void genuineUpstreamStampIsTrustedAsIs() {
        // A database that never met the fork -- for instance an older upstream backup being
        // restored -- must keep its real version so the migrations it still needs actually run.
        PodDBAdapter.createForkSchema(db);
        assertEquals(3080000, PodDBAdapter.readUpstreamSchemaLevel(db, 3080000));
    }

    @Test
    public void recordedLevelWinsOverTheStamp() {
        PodDBAdapter.createForkSchema(db);
        PodDBAdapter.writeUpstreamSchemaLevel(db, 3130000);
        assertEquals(3130000, PodDBAdapter.readUpstreamSchemaLevel(db, PodDBAdapter.VERSION));
    }

    @Test
    public void upgradeRecordsTheLevelSoItIsNotReplayed() {
        // Without this the chain would re-run on the next upgrade, and re-running an
        // ALTER TABLE ADD COLUMN fails hard enough to stop the app opening at all.
        DBUpgrader.upgrade(db, PodDBAdapter.VERSION, PodDBAdapter.VERSION);
        assertEquals(PodDBAdapter.UPSTREAM_SCHEMA_LEVEL,
                PodDBAdapter.readUpstreamSchemaLevel(db, PodDBAdapter.VERSION));
    }

    @Test
    public void writingTheLevelTwiceUpdatesRatherThanDuplicating() {
        PodDBAdapter.createForkSchema(db);
        PodDBAdapter.writeUpstreamSchemaLevel(db, 3110000);
        PodDBAdapter.writeUpstreamSchemaLevel(db, 3130000);
        assertEquals("primary key conflict must replace, not accumulate", 1,
                rowCount(PodDBAdapter.TABLE_NAME_FORK_SCHEMA));
        assertEquals(3130000, PodDBAdapter.readUpstreamSchemaLevel(db, PodDBAdapter.VERSION));
    }

    @Test
    public void upstreamBaseLeavesRoomBelowTheForkStamp() {
        // The clamp in DBUpgrader only helps while the recorded upstream level is genuinely
        // below the fork's stamp. If someone raises UPSTREAM_SCHEMA_LEVEL to meet VERSION while
        // merging upstream without also raising VERSION, upstream migrations numbered in that
        // range start being skipped again -- silently. Fail here instead.
        assertTrue("UPSTREAM_SCHEMA_LEVEL must stay below VERSION; raise VERSION when merging"
                        + " an upstream release that reaches it",
                PodDBAdapter.UPSTREAM_SCHEMA_LEVEL < PodDBAdapter.VERSION);
    }
}

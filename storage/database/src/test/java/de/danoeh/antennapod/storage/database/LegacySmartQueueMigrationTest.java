package de.danoeh.antennapod.storage.database;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The earlier fork branch named the Smart Queue columns without the {@code sp_} prefix this one
 * uses. Any database it wrote — including a restored backup — is therefore unreadable here until
 * converted, and the failure is quiet: the queries ask for columns that do not exist and the
 * feature simply comes up empty.
 *
 * <p>The legacy DDL below is copied verbatim from the older branch rather than reconstructed, so
 * these fixtures match what real databases actually contain.
 */
@RunWith(RobolectricTestRunner.class)
public class LegacySmartQueueMigrationTest {
    /** Exact statements from the earlier fork branch. Do not "tidy" these. */
    private static final String LEGACY_PLAYLISTS =
            "CREATE TABLE SmartPlaylists(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,"
            + "auto_regenerate INTEGER NOT NULL DEFAULT 1,generated_at INTEGER DEFAULT 0,"
            + "created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)";
    private static final String LEGACY_RULES =
            "CREATE TABLE SmartPlaylistRules(id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "playlist_id INTEGER NOT NULL,position INTEGER NOT NULL,"
            + "filter_properties TEXT DEFAULT '',feed_ids TEXT DEFAULT '',feed_tags TEXT DEFAULT '',"
            + "max_age_days INTEGER DEFAULT 0,min_duration_ms INTEGER DEFAULT 0,"
            + "max_duration_ms INTEGER DEFAULT 0,media_type TEXT DEFAULT '',"
            + "episode_limit INTEGER DEFAULT 0,sort_order TEXT DEFAULT 'NEWEST',"
            + "FOREIGN KEY (playlist_id) REFERENCES SmartPlaylists(id) ON DELETE CASCADE)";
    private static final String LEGACY_EPISODES =
            "CREATE TABLE SmartPlaylistEpisodes(id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "playlist_id INTEGER NOT NULL,episode_id INTEGER NOT NULL,position INTEGER NOT NULL,"
            + "FOREIGN KEY (playlist_id) REFERENCES SmartPlaylists(id) ON DELETE CASCADE)";

    /** The stamp the earlier branch wrote, and what a restored backup from it carries. */
    private static final int LEGACY_STAMP = 3120000;

    private SQLiteDatabase db;

    @Before
    public void setUp() {
        db = SQLiteDatabase.create(null);
    }

    @After
    public void tearDown() {
        db.close();
    }

    private void createLegacyDatabaseWithData() {
        db.execSQL(LEGACY_PLAYLISTS);
        db.execSQL(LEGACY_RULES);
        db.execSQL(LEGACY_EPISODES);
        db.execSQL("CREATE INDEX SmartPlaylistRules_playlist_id ON SmartPlaylistRules (playlist_id)");
        db.execSQL("INSERT INTO SmartPlaylists (id,name,auto_regenerate,generated_at,created_at,"
                + "updated_at) VALUES (1,'SmartPlay Regular',1,111,222,333)");
        db.execSQL("INSERT INTO SmartPlaylists (id,name,auto_regenerate,generated_at,created_at,"
                + "updated_at) VALUES (2,'Smart play Kids',0,0,444,555)");
        db.execSQL("INSERT INTO SmartPlaylistRules (id,playlist_id,position,filter_properties,"
                + "feed_ids,feed_tags,max_age_days,min_duration_ms,max_duration_ms,media_type,"
                + "episode_limit,sort_order) VALUES (1,1,0,'unplayed,downloaded','12','Farming',"
                + "7,60000,600000,'audio',5,'OLDEST')");
        db.execSQL("INSERT INTO SmartPlaylistEpisodes (id,playlist_id,episode_id,position) "
                + "VALUES (1,1,4242,3)");
    }

    private String queryString(String sql) {
        try (Cursor cursor = db.rawQuery(sql, null)) {
            cursor.moveToFirst();
            return cursor.getString(0);
        }
    }

    private int queryInt(String sql) {
        try (Cursor cursor = db.rawQuery(sql, null)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    private boolean hasColumn(String table, String column) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameColumn = cursor.getColumnIndex("name");
            while (cursor.moveToNext()) {
                if (column.equals(cursor.getString(nameColumn))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    public void legacyColumnsAreRenamed() {
        createLegacyDatabaseWithData();
        DBUpgrader.upgrade(db, LEGACY_STAMP, PodDBAdapter.VERSION);

        assertTrue(hasColumn("SmartPlaylists", "sp_name"));
        assertFalse("legacy column must be gone", hasColumn("SmartPlaylists", "name"));
        assertTrue(hasColumn("SmartPlaylistRules", "sp_playlist_id"));
        assertFalse(hasColumn("SmartPlaylistRules", "playlist_id"));
        assertTrue(hasColumn("SmartPlaylistEpisodes", "sp_episode_id"));
        assertFalse(hasColumn("SmartPlaylistEpisodes", "episode_id"));
    }

    @Test
    public void playlistDataSurvivesTheConversion() {
        createLegacyDatabaseWithData();
        DBUpgrader.upgrade(db, LEGACY_STAMP, PodDBAdapter.VERSION);

        assertEquals(2, queryInt("SELECT COUNT(*) FROM SmartPlaylists"));
        assertEquals("SmartPlay Regular",
                queryString("SELECT sp_name FROM SmartPlaylists WHERE id=1"));
        assertEquals("Smart play Kids",
                queryString("SELECT sp_name FROM SmartPlaylists WHERE id=2"));
        assertEquals(1, queryInt("SELECT sp_auto_regenerate FROM SmartPlaylists WHERE id=1"));
        assertEquals(0, queryInt("SELECT sp_auto_regenerate FROM SmartPlaylists WHERE id=2"));
        assertEquals(222, queryInt("SELECT sp_created_at FROM SmartPlaylists WHERE id=1"));
    }

    @Test
    public void everyRuleFieldIsCarriedAcross() {
        // A rule silently losing its filters would leave a queue that looks fine and matches the
        // wrong episodes, so check each column rather than just the row count.
        createLegacyDatabaseWithData();
        DBUpgrader.upgrade(db, LEGACY_STAMP, PodDBAdapter.VERSION);

        assertEquals(1, queryInt("SELECT COUNT(*) FROM SmartPlaylistRules"));
        assertEquals(1, queryInt("SELECT sp_playlist_id FROM SmartPlaylistRules WHERE id=1"));
        assertEquals("unplayed,downloaded",
                queryString("SELECT sp_filter_properties FROM SmartPlaylistRules WHERE id=1"));
        assertEquals("12", queryString("SELECT sp_feed_ids FROM SmartPlaylistRules WHERE id=1"));
        assertEquals("Farming", queryString("SELECT sp_feed_tags FROM SmartPlaylistRules WHERE id=1"));
        assertEquals(7, queryInt("SELECT sp_max_age_days FROM SmartPlaylistRules WHERE id=1"));
        assertEquals(60000, queryInt("SELECT sp_min_duration_ms FROM SmartPlaylistRules WHERE id=1"));
        assertEquals(600000, queryInt("SELECT sp_max_duration_ms FROM SmartPlaylistRules WHERE id=1"));
        assertEquals("audio", queryString("SELECT sp_media_type FROM SmartPlaylistRules WHERE id=1"));
        assertEquals(5, queryInt("SELECT sp_episode_limit FROM SmartPlaylistRules WHERE id=1"));
        assertEquals("OLDEST", queryString("SELECT sp_sort_order FROM SmartPlaylistRules WHERE id=1"));
    }

    @Test
    public void episodeMembershipAndOrderSurvive() {
        createLegacyDatabaseWithData();
        DBUpgrader.upgrade(db, LEGACY_STAMP, PodDBAdapter.VERSION);

        assertEquals(4242, queryInt("SELECT sp_episode_id FROM SmartPlaylistEpisodes WHERE id=1"));
        assertEquals(3, queryInt("SELECT sp_position FROM SmartPlaylistEpisodes WHERE id=1"));
    }

    @Test
    public void conversionIsIdempotent() {
        // It runs on every upgrade, so a second pass must not double-convert or drop rows.
        createLegacyDatabaseWithData();
        DBUpgrader.upgrade(db, LEGACY_STAMP, PodDBAdapter.VERSION);
        DBUpgrader.upgrade(db, PodDBAdapter.VERSION, PodDBAdapter.VERSION);

        assertEquals(2, queryInt("SELECT COUNT(*) FROM SmartPlaylists"));
        assertEquals("SmartPlay Regular",
                queryString("SELECT sp_name FROM SmartPlaylists WHERE id=1"));
        assertEquals(0, queryInt("SELECT COUNT(*) FROM sqlite_master WHERE type='table'"
                + " AND name LIKE '%\\_legacy' ESCAPE '\\'"));
    }

    @Test
    public void aCurrentDatabaseIsLeftAlone() {
        // The conversion keys off a legacy column being present; on a database already using the
        // current names it must not fire.
        PodDBAdapter.createForkSchema(db);
        db.execSQL("INSERT INTO SmartPlaylists (sp_name,sp_created_at,sp_updated_at) "
                + "VALUES ('Existing',1,1)");

        DBUpgrader.upgrade(db, PodDBAdapter.VERSION, PodDBAdapter.VERSION);

        assertEquals(1, queryInt("SELECT COUNT(*) FROM SmartPlaylists"));
        assertEquals("Existing", queryString("SELECT sp_name FROM SmartPlaylists WHERE id=1"));
    }

    @Test
    public void versionIsAboveTheLegacyStampSoTheUpgradeActuallyFires() {
        // A restored legacy backup is stamped 3120000. If VERSION were not higher, no upgrade
        // would run and the conversion would never happen -- the whole mechanism depends on this.
        assertTrue("VERSION must exceed the legacy stamp for the conversion to be reachable",
                PodDBAdapter.VERSION > LEGACY_STAMP);
    }
}

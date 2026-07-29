package de.danoeh.antennapod.storage.database;

import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
public class DBUpgraderTest {
    private static final int VERSION_BEFORE_MULTIPLE_QUEUES = 3110000;
    private static final long FEED_ID = 42;

    private SQLiteDatabase db;

    @Before
    public void setUp() {
        db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE " + PodDBAdapter.TABLE_NAME_QUEUE + "("
                + PodDBAdapter.KEY_ID + " INTEGER PRIMARY KEY,"
                + PodDBAdapter.KEY_FEEDITEM + " INTEGER,"
                + PodDBAdapter.KEY_FEED + " INTEGER)");
        db.execSQL(PodDBAdapter.CREATE_INDEX_QUEUE_FEEDITEM);
    }

    @After
    public void tearDown() {
        db.close();
    }

    private void insertLegacyQueueRow(long position, long feedItemId) {
        db.execSQL("INSERT INTO " + PodDBAdapter.TABLE_NAME_QUEUE + " (" + PodDBAdapter.KEY_ID + ", "
                + PodDBAdapter.KEY_FEEDITEM + ", " + PodDBAdapter.KEY_FEED + ") VALUES (?, ?, ?)",
                new Object[] {position, feedItemId, FEED_ID});
    }

    private void upgrade() {
        DBUpgrader.upgrade(db, VERSION_BEFORE_MULTIPLE_QUEUES, PodDBAdapter.VERSION);
    }

    private List<Long> queuedFeedItemsInOrder() {
        List<Long> feedItemIds = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT " + PodDBAdapter.KEY_FEEDITEM + " FROM "
                + PodDBAdapter.TABLE_NAME_QUEUE + " ORDER BY " + PodDBAdapter.KEY_ID, null)) {
            while (cursor.moveToNext()) {
                feedItemIds.add(cursor.getLong(0));
            }
        }
        return feedItemIds;
    }

    private long count(String query) {
        try (Cursor cursor = db.rawQuery(query, null)) {
            cursor.moveToFirst();
            return cursor.getLong(0);
        }
    }

    @Test
    public void keepsEveryQueuedEpisodeInItsOriginalOrder() {
        insertLegacyQueueRow(0, 100);
        insertLegacyQueueRow(1, 200);
        insertLegacyQueueRow(2, 300);

        upgrade();

        assertEquals(Arrays.asList(100L, 200L, 300L), queuedFeedItemsInOrder());
    }

    @Test
    public void movesEveryExistingEpisodeIntoTheDefaultQueue() {
        insertLegacyQueueRow(0, 100);
        insertLegacyQueueRow(1, 200);

        upgrade();

        assertEquals(2, count("SELECT COUNT(*) FROM " + PodDBAdapter.TABLE_NAME_QUEUE + " WHERE "
                + PodDBAdapter.KEY_QUEUE + " = " + PodDBAdapter.QUEUE_ID_DEFAULT));
    }

    @Test
    public void createsExactlyOneQueueWithAnUntranslatedName() {
        upgrade();

        try (Cursor cursor = db.rawQuery("SELECT " + PodDBAdapter.KEY_ID + ", " + PodDBAdapter.KEY_NAME
                + " FROM " + PodDBAdapter.TABLE_NAME_QUEUES, null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            assertEquals(PodDBAdapter.QUEUE_ID_DEFAULT, cursor.getLong(0));
            assertTrue(cursor.isNull(1));
        }
    }

    @Test
    public void upgradesAnEmptyQueue() {
        upgrade();

        assertEquals(Collections.emptyList(), queuedFeedItemsInOrder());
        assertEquals(1, count("SELECT COUNT(*) FROM " + PodDBAdapter.TABLE_NAME_QUEUES));
    }

    @Test
    public void keepsTheEarliestPositionWhenTheOldQueueAlreadyHadDuplicates() {
        insertLegacyQueueRow(0, 100);
        insertLegacyQueueRow(1, 200);
        insertLegacyQueueRow(2, 100);

        upgrade();

        assertEquals(Arrays.asList(100L, 200L), queuedFeedItemsInOrder());
    }

    @Test(expected = SQLiteConstraintException.class)
    public void refusesToPutOneEpisodeInTwoQueues() {
        insertLegacyQueueRow(0, 100);
        upgrade();

        db.execSQL("INSERT INTO " + PodDBAdapter.TABLE_NAME_QUEUES + " (" + PodDBAdapter.KEY_ID + ", "
                + PodDBAdapter.KEY_NAME + ") VALUES (2, 'Commute')");
        db.execSQL("INSERT INTO " + PodDBAdapter.TABLE_NAME_QUEUE + " (" + PodDBAdapter.KEY_ID + ", "
                + PodDBAdapter.KEY_FEEDITEM + ", " + PodDBAdapter.KEY_FEED + ", "
                + PodDBAdapter.KEY_QUEUE + ") VALUES (1, 100, " + FEED_ID + ", 2)");
    }

    @Test
    public void leavesAUniqueIndexOnQueuedEpisodes() {
        upgrade();

        try (Cursor cursor = db.rawQuery("PRAGMA index_list("
                + PodDBAdapter.TABLE_NAME_QUEUE + ")", null)) {
            while (cursor.moveToNext()) {
                String name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                if (PodDBAdapter.INDEX_NAME_QUEUE_FEEDITEM.equals(name)) {
                    assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("unique")));
                    return;
                }
            }
        }
        fail("Expected a unique index named " + PodDBAdapter.INDEX_NAME_QUEUE_FEEDITEM);
    }
}

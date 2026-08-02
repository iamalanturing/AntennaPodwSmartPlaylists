package de.danoeh.antennapod.storage.database;

import android.content.ContentValues;
import android.content.Context;

import de.danoeh.antennapod.model.feed.Queue;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
public class QueuesTest {
    private PodDBAdapter adapter;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        UserPreferences.init(context);
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        adapter = PodDBAdapter.getInstance();
        adapter.open();
    }

    @After
    public void tearDown() {
        adapter.close();
        PodDBAdapter.tearDownTests();
    }

    private void queueEpisode(long position, long feedItemId, long queueId) {
        ContentValues values = new ContentValues();
        values.put(PodDBAdapter.KEY_ID, position);
        values.put(PodDBAdapter.KEY_FEEDITEM, feedItemId);
        values.put(PodDBAdapter.KEY_FEED, 1);
        values.put(PodDBAdapter.KEY_QUEUE, queueId);
        adapter.insertTestData(PodDBAdapter.TABLE_NAME_QUEUE, values);
    }

    @Test
    public void aNewDatabaseHasOnlyTheDefaultQueue() {
        List<Queue> queues = DBReader.getQueues();

        assertEquals(1, queues.size());
        assertEquals(PodDBAdapter.QUEUE_ID_DEFAULT, queues.get(0).getId());
        assertNull(queues.get(0).getName());
    }

    @Test
    public void createdQueuesGetTheirOwnId() {
        long commute = adapter.createQueue("Commute");
        long workout = adapter.createQueue("Workout");

        assertNotEquals(PodDBAdapter.QUEUE_ID_DEFAULT, commute);
        assertNotEquals(commute, workout);

        List<Queue> queues = DBReader.getQueues();
        assertEquals(3, queues.size());
        assertEquals("Commute", queues.get(1).getName());
        assertEquals("Workout", queues.get(2).getName());
    }

    @Test
    public void renamingAQueueLeavesTheOthersAlone() {
        long commute = adapter.createQueue("Commute");
        adapter.createQueue("Workout");

        adapter.renameQueue(commute, "Bus");

        List<Queue> queues = DBReader.getQueues();
        assertEquals("Bus", queues.get(1).getName());
        assertEquals("Workout", queues.get(2).getName());
    }

    @Test
    public void deletingAQueueRemovesItAndItsEpisodes() {
        long commute = adapter.createQueue("Commute");
        queueEpisode(5, 100, commute);
        queueEpisode(6, 200, PodDBAdapter.QUEUE_ID_DEFAULT);

        adapter.deleteQueue(commute);

        assertEquals(1, DBReader.getQueues().size());
        assertEquals(0, adapter.getQueueSize(commute));
        assertEquals(1, adapter.getQueueSize(PodDBAdapter.QUEUE_ID_DEFAULT));
    }

    @Test
    public void theActiveQueueIsTheDefaultOneUntilItIsChanged() {
        assertEquals(PodDBAdapter.QUEUE_ID_DEFAULT, DBReader.getActiveQueue());

        long commute = adapter.createQueue("Commute");
        UserPreferences.setActiveQueue(commute);

        assertEquals(commute, DBReader.getActiveQueue());
    }
}

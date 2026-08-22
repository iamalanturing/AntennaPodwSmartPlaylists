package de.danoeh.antennapod.storage.database;

import android.content.Context;
import com.google.common.util.concurrent.Futures;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.net.download.serviceinterface.AutoDownloadManager;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueueStub;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Activating a Smart Queue loads its matches into the real queue so Android Auto (and everything
 * else) sees them, stashing whatever was there first so it is never lost. These tests pin the
 * round trip that promise depends on: stash-then-replace, restore-exactly, switching between two
 * active queues without clobbering the original stash, deleting the active playlist, and
 * resuming an already-active queue without rebuilding it.
 */
@RunWith(RobolectricTestRunner.class)
public class ActiveSmartQueueTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        UserPreferences.init(context);
        PlaybackPreferences.init(context);
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.close();
        SynchronizationQueue.setInstance(new SynchronizationQueueStub());
        AutoDownloadManager.setInstance(new AutoDownloadManager() {
            @Override
            public Future<?> autodownloadUndownloadedItems(Context context) {
                return Futures.immediateFuture(null);
            }

            @Override
            public void performAutoCleanup(Context context) {
            }
        });
    }

    @After
    public void tearDown() {
        PodDBAdapter.deleteDatabase();
        PodDBAdapter.tearDownTests();
    }

    @Test
    public void activateStashesManualQueueAndReplacesWithMatches() throws Exception {
        Feed manual = storeFeed("manual", 2, true);
        DBWriter.addQueueItem(context, manual.getItems().toArray(new FeedItem[0])).get();
        Feed smart = storeFeed("smart", 2, true);
        SmartPlaylist playlist = createPlaylist("Queue", smart);

        List<FeedItem> result = DBWriter.activateSmartQueue(context, playlist).get();

        // Set comparison, not list: the rule query's own sort order (not this test) decides the
        // exact order episodes match in, and items created in the same test run can tie on
        // pubDate down to the millisecond.
        assertSameIds(idsOf(smart.getItems()), idsOf(result));
        assertSameIds(idsOf(smart.getItems()), idsOf(DBReader.getQueue()));
        assertEquals(playlist.getId(), PlaybackPreferences.getActiveSmartQueueId());
    }

    @Test
    public void stopRestoresOriginalManualQueueExactly() throws Exception {
        Feed manual = storeFeed("manual", 2, true);
        DBWriter.addQueueItem(context, manual.getItems().toArray(new FeedItem[0])).get();
        List<Long> originalQueueIds = idsOf(DBReader.getQueue());
        Feed smart = storeFeed("smart", 2, true);
        SmartPlaylist playlist = createPlaylist("Queue", smart);
        DBWriter.activateSmartQueue(context, playlist).get();

        DBWriter.stopActiveSmartQueue().get();

        assertIds(originalQueueIds, idsOf(DBReader.getQueue()));
        assertEquals(0, PlaybackPreferences.getActiveSmartQueueId());
    }

    @Test
    public void appendRegeneratedEpisodesAppendsOnlyNewMatches() throws Exception {
        Feed smart = storeFeed("smart", 3, true);
        SmartPlaylist playlist = createPlaylist("Queue", smart);
        DBWriter.activateSmartQueue(context, playlist).get();
        FeedItem removed = smart.getItems().get(1);
        DBWriter.removeQueueItem(context, false, removed).get();
        List<Long> queueBeforeRegenerate = idsOf(DBReader.getQueue());

        DBWriter.appendRegeneratedSmartQueueEpisodesSync(playlist);

        List<Long> queueAfterRegenerate = idsOf(DBReader.getQueue());
        assertEquals(queueBeforeRegenerate.size() + 1, queueAfterRegenerate.size());
        assertEquals("existing rows must keep their order", queueBeforeRegenerate,
                queueAfterRegenerate.subList(0, queueBeforeRegenerate.size()));
        assertEquals("the removed episode reappears at the end, not its old position",
                removed.getId(), (long) queueAfterRegenerate.get(queueAfterRegenerate.size() - 1));
    }

    @Test
    public void activatingASecondSmartQueueDoesNotReStashTheFirst() throws Exception {
        Feed manual = storeFeed("manual", 2, true);
        DBWriter.addQueueItem(context, manual.getItems().toArray(new FeedItem[0])).get();
        List<Long> originalQueueIds = idsOf(DBReader.getQueue());
        Feed smartA = storeFeed("smartA", 2, true);
        Feed smartB = storeFeed("smartB", 2, true);
        SmartPlaylist playlistA = createPlaylist("A", smartA);
        SmartPlaylist playlistB = createPlaylist("B", smartB);

        DBWriter.activateSmartQueue(context, playlistA).get();
        DBWriter.activateSmartQueue(context, playlistB).get();
        assertSameIds(idsOf(smartB.getItems()), idsOf(DBReader.getQueue()));

        DBWriter.stopActiveSmartQueue().get();

        assertIds("stopping must restore the original manual queue, not playlist A's episodes",
                originalQueueIds, idsOf(DBReader.getQueue()));
    }

    @Test
    public void deletingTheActivePlaylistRestoresTheStash() throws Exception {
        Feed manual = storeFeed("manual", 2, true);
        DBWriter.addQueueItem(context, manual.getItems().toArray(new FeedItem[0])).get();
        List<Long> originalQueueIds = idsOf(DBReader.getQueue());
        Feed smart = storeFeed("smart", 2, true);
        SmartPlaylist playlist = createPlaylist("Queue", smart);
        DBWriter.activateSmartQueue(context, playlist).get();

        DBWriter.deleteSmartPlaylist(playlist.getId()).get();

        assertIds(originalQueueIds, idsOf(DBReader.getQueue()));
        assertEquals(0, PlaybackPreferences.getActiveSmartQueueId());
    }

    @Test
    public void reactivatingTheAlreadyActivePlaylistDoesNotRebuild() throws Exception {
        Feed smart = storeFeed("smart", 2, true);
        SmartPlaylist playlist = createPlaylist("Queue", smart);
        DBWriter.activateSmartQueue(context, playlist).get();
        DBWriter.moveQueueItem(0, 1, true).get();
        List<Long> queueAfterReorder = idsOf(DBReader.getQueue());

        Feed other = storeFeed("other", 1, true);
        playlist.getRules().get(0).setFeedIds(smart.getId() + "," + other.getId());

        List<FeedItem> result = DBWriter.activateSmartQueue(context, playlist).get();

        assertIds("re-activating the same playlist must leave the queue exactly as it was",
                queueAfterReorder, idsOf(result));
        assertIds(queueAfterReorder, idsOf(DBReader.getQueue()));
        assertFalse("a newly-matching episode must not appear without an explicit regenerate",
                idsOf(DBReader.getQueue()).contains(other.getItems().get(0).getId()));
    }

    @Test
    public void staleActiveIdWithoutAStashStillActivates() throws Exception {
        // Reproduces upgrading from a build that used the old parallel smart-queue mechanism:
        // PREF_ACTIVE_SMART_QUEUE_ID is the same preference key that mechanism used, and it
        // survives an app update. If activateSmartQueue trusted that preference alone, it would
        // wrongly treat this as "already active" and hand back the pre-existing manual queue
        // untouched instead of ever loading the playlist's matches.
        Feed manual = storeFeed("manual", 2, true);
        DBWriter.addQueueItem(context, manual.getItems().toArray(new FeedItem[0])).get();
        Feed smart = storeFeed("smart", 2, true);
        SmartPlaylist playlist = createPlaylist("Queue", smart);
        PlaybackPreferences.writeActiveSmartQueueId(playlist.getId());

        List<FeedItem> result = DBWriter.activateSmartQueue(context, playlist).get();

        assertSameIds(idsOf(smart.getItems()), idsOf(result));
        assertSameIds(idsOf(smart.getItems()), idsOf(DBReader.getQueue()));
    }

    private SmartPlaylist createPlaylist(String name, Feed... matchedFeeds) throws Exception {
        StringBuilder feedIds = new StringBuilder();
        for (Feed feed : matchedFeeds) {
            if (feedIds.length() > 0) {
                feedIds.append(',');
            }
            feedIds.append(feed.getId());
        }
        SmartPlaylist playlist = new SmartPlaylist();
        playlist.setName(name);
        SmartPlaylistRule rule = new SmartPlaylistRule();
        rule.setFeedIds(feedIds.toString());
        playlist.getRules().add(rule);
        DBWriter.createSmartPlaylist(playlist, context).get();
        return playlist;
    }

    private static List<Long> idsOf(List<FeedItem> items) {
        List<Long> ids = new ArrayList<>();
        for (FeedItem item : items) {
            ids.add(item.getId());
        }
        return ids;
    }

    private static void assertIds(List<Long> expected, List<Long> actual) {
        assertEquals(expected, actual);
    }

    private static void assertIds(String message, List<Long> expected, List<Long> actual) {
        assertEquals(message, expected, actual);
    }

    private static void assertSameIds(List<Long> expected, List<Long> actual) {
        assertEquals(expected.size(), actual.size());
        assertEquals(new HashSet<>(expected), new HashSet<>(actual));
    }

    private Feed storeFeed(String identifier, int itemCount, boolean downloaded) throws Exception {
        Feed feed = new Feed(0, null, "Feed " + identifier, "http://example.com/" + identifier,
                "description", null, "author", "en", null, "http://example.com/" + identifier,
                null, null, "http://example.com/" + identifier, System.currentTimeMillis());
        feed.setItems(new ArrayList<>());
        for (int i = 0; i < itemCount; i++) {
            FeedItem item = new FeedItem(0, "Item " + i, identifier + "-item-" + i,
                    "http://example.com/" + identifier + "/" + i, new Date(), FeedItem.UNPLAYED, feed);
            FeedMedia media = new FeedMedia(item, "http://example.com/" + identifier + "/" + i + ".mp3",
                    1234, "audio/mpeg");
            if (downloaded) {
                media.setDownloaded(true, System.currentTimeMillis());
                media.setLocalFileUrl("/local/" + identifier + "/" + i + ".mp3");
            }
            item.setMedia(media);
            feed.getItems().add(item);
        }
        DBWriter.setCompleteFeed(feed).get();
        assertTrue(feed.getId() != 0);
        assertEquals(itemCount, DBReader.getFeedItemList(feed, FeedItemFilter.unfiltered(),
                SortOrder.DATE_NEW_OLD, 0, Integer.MAX_VALUE).size());
        return feed;
    }
}

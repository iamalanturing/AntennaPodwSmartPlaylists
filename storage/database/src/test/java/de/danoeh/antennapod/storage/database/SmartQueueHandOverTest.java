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
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * "When this queue runs out" can hand over to another smart queue. The target resumes from what it
 * has left instead of being rebuilt, is rebuilt only when nothing is left, and when even that
 * finds nothing the target's own setting decides where playback goes next.
 */
@RunWith(RobolectricTestRunner.class)
public class SmartQueueHandOverTest {
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
    public void targetResumesFromWhatItHasLeftWithoutBeingRebuilt() throws Exception {
        Feed catchUpFeed = storeFeed("catchup", 1);
        Feed regularFeed = storeFeed("regular", 2);
        Feed laterFeed = storeFeed("later", 1);
        SmartPlaylist regular = createPlaylist("Regular", regularFeed);
        SmartPlaylist catchUp = createPlaylist("Catch-up", catchUpFeed);
        setHandOver(catchUp, regular);
        DBWriter.activateSmartQueue(context, regular).get();
        markPlayed(regularFeed.getItems().get(0));
        DBWriter.activateSmartQueue(context, catchUp).get();
        // A rebuild would now pick this up as well, which is how the test tells one from the other
        addFeedToRule(regular, laterFeed);

        List<FeedItem> result = DBWriter.handOverToNextSmartQueue(
                context, catchUp, catchUpFeed.getItems().get(0).getId()).get();

        assertEquals(idsOf(regularFeed.getItems().subList(1, 2)), idsOf(result));
        assertEquals(idsOf(regularFeed.getItems().subList(1, 2)), idsOf(DBReader.getQueue()));
        assertEquals(regular.getId(), PlaybackPreferences.getActiveSmartQueueId());
    }

    @Test
    public void targetIsRebuiltWhenNothingIsLeft() throws Exception {
        Feed catchUpFeed = storeFeed("catchup", 1);
        Feed regularFeed = storeFeed("regular", 2);
        Feed laterFeed = storeFeed("later", 1);
        SmartPlaylist regular = createPlaylist("Regular", regularFeed);
        SmartPlaylist catchUp = createPlaylist("Catch-up", catchUpFeed);
        setHandOver(catchUp, regular);
        DBWriter.activateSmartQueue(context, regular).get();
        markPlayed(regularFeed.getItems().get(0));
        markPlayed(regularFeed.getItems().get(1));
        addFeedToRule(regular, laterFeed);

        List<FeedItem> result = DBWriter.handOverToNextSmartQueue(
                context, catchUp, catchUpFeed.getItems().get(0).getId()).get();

        assertEquals(idsOf(laterFeed.getItems()), idsOf(result));
        assertEquals(regular.getId(), PlaybackPreferences.getActiveSmartQueueId());
    }

    @Test
    public void emptyTargetPassesOnToItsOwnTarget() throws Exception {
        Feed firstFeed = storeFeed("first", 1);
        Feed emptyFeed = storeFeed("empty", 1);
        Feed lastFeed = storeFeed("last", 1);
        SmartPlaylist first = createPlaylist("First", firstFeed);
        SmartPlaylist empty = createPlaylist("Empty", emptyFeed);
        SmartPlaylist last = createPlaylist("Last", lastFeed);
        setHandOver(first, empty);
        setHandOver(empty, last);
        markPlayed(emptyFeed.getItems().get(0));

        List<FeedItem> result = DBWriter.handOverToNextSmartQueue(
                context, first, firstFeed.getItems().get(0).getId()).get();

        assertEquals(idsOf(lastFeed.getItems()), idsOf(result));
        assertEquals(last.getId(), PlaybackPreferences.getActiveSmartQueueId());
    }

    @Test
    public void aLoopOfEmptyQueuesStopsInsteadOfSpinning() throws Exception {
        Feed firstFeed = storeFeed("first", 1);
        Feed secondFeed = storeFeed("second", 1);
        SmartPlaylist first = createPlaylist("First", firstFeed);
        SmartPlaylist second = createPlaylist("Second", secondFeed);
        setHandOver(first, second);
        setHandOver(second, first);
        markPlayed(firstFeed.getItems().get(0));
        markPlayed(secondFeed.getItems().get(0));

        List<FeedItem> result = DBWriter.handOverToNextSmartQueue(
                context, first, firstFeed.getItems().get(0).getId()).get();

        assertTrue(result.isEmpty());
    }

    @Test
    public void twoQueuesCanHandBackAndForthRebuildingEachTimeTheyRunOut() throws Exception {
        Feed catchUpFeed = storeFeed("catchup", 1);
        Feed regularFeed = storeFeed("regular", 1);
        Feed newCatchUpFeed = storeFeed("newcatchup", 1);
        SmartPlaylist catchUp = createPlaylist("Catch-up", catchUpFeed);
        SmartPlaylist regular = createPlaylist("Regular", regularFeed);
        setHandOver(catchUp, regular);
        setHandOver(regular, catchUp);
        DBWriter.activateSmartQueue(context, catchUp).get();
        markPlayed(catchUpFeed.getItems().get(0));

        List<FeedItem> toRegular = DBWriter.handOverToNextSmartQueue(
                context, catchUp, catchUpFeed.getItems().get(0).getId()).get();
        assertEquals(idsOf(regularFeed.getItems()), idsOf(toRegular));
        markPlayed(regularFeed.getItems().get(0));
        addFeedToRule(catchUp, newCatchUpFeed);

        List<FeedItem> backToCatchUp = DBWriter.handOverToNextSmartQueue(
                context, regular, regularFeed.getItems().get(0).getId()).get();

        assertEquals(idsOf(newCatchUpFeed.getItems()), idsOf(backToCatchUp));
        assertEquals(catchUp.getId(), PlaybackPreferences.getActiveSmartQueueId());
    }

    @Test
    public void theEpisodeThatJustFinishedIsNotPlayedAgain() throws Exception {
        Feed finishedFeed = storeFeed("finished", 1);
        Feed otherFeed = storeFeed("other", 1);
        SmartPlaylist first = createPlaylist("First", finishedFeed);
        SmartPlaylist target = createPlaylist("Target", finishedFeed, otherFeed);
        setHandOver(first, target);
        DBWriter.activateSmartQueue(context, target).get();
        DBWriter.activateSmartQueue(context, first).get();

        List<FeedItem> result = DBWriter.handOverToNextSmartQueue(
                context, first, finishedFeed.getItems().get(0).getId()).get();

        assertEquals(idsOf(otherFeed.getItems()), idsOf(result));
    }

    @Test
    public void startingAnEmptyQueueGoesStraightToItsTarget() throws Exception {
        Feed catchUpFeed = storeFeed("catchup", 1);
        Feed regularFeed = storeFeed("regular", 1);
        SmartPlaylist catchUp = createPlaylist("Catch-up", catchUpFeed);
        SmartPlaylist regular = createPlaylist("Regular", regularFeed);
        setHandOver(catchUp, regular);
        markPlayed(catchUpFeed.getItems().get(0));

        List<FeedItem> result = DBWriter.activateSmartQueue(context, catchUp).get();

        assertEquals(idsOf(regularFeed.getItems()), idsOf(result));
        assertEquals(regular.getId(), PlaybackPreferences.getActiveSmartQueueId());
    }

    @Test
    public void startingAnEmptyQueueWhoseTargetIsAlsoEmptyStaysOnIt() throws Exception {
        Feed catchUpFeed = storeFeed("catchup", 1);
        Feed regularFeed = storeFeed("regular", 1);
        SmartPlaylist catchUp = createPlaylist("Catch-up", catchUpFeed);
        SmartPlaylist regular = createPlaylist("Regular", regularFeed);
        setHandOver(catchUp, regular);
        markPlayed(catchUpFeed.getItems().get(0));
        markPlayed(regularFeed.getItems().get(0));

        List<FeedItem> result = DBWriter.activateSmartQueue(context, catchUp).get();

        assertTrue(result.isEmpty());
        assertEquals(catchUp.getId(), PlaybackPreferences.getActiveSmartQueueId());
    }

    @Test
    public void deletingTheTargetClearsTheHandOver() throws Exception {
        SmartPlaylist catchUp = createPlaylist("Catch-up", storeFeed("catchup", 1));
        SmartPlaylist regular = createPlaylist("Regular", storeFeed("regular", 1));
        setHandOver(catchUp, regular);
        assertEquals(regular.getId(), DBReader.getSmartPlaylist(catchUp.getId()).getNextPlaylistId());
        assertEquals("Regular", DBReader.getSmartPlaylist(catchUp.getId()).getNextPlaylistName());

        DBWriter.deleteSmartPlaylist(regular.getId()).get();

        assertEquals(0, DBReader.getSmartPlaylist(catchUp.getId()).getNextPlaylistId());
    }

    private void setHandOver(SmartPlaylist from, SmartPlaylist to) throws Exception {
        from.setAutoRegenerate(false);
        from.setNextPlaylistId(to.getId());
        DBWriter.updateSmartPlaylist(from, context).get();
    }

    private void addFeedToRule(SmartPlaylist playlist, Feed feed) throws Exception {
        SmartPlaylist stored = DBReader.getSmartPlaylist(playlist.getId());
        SmartPlaylistRule rule = stored.getRules().get(0);
        rule.setFeedIds(rule.getFeedIds() + "," + feed.getId());
        DBWriter.updateSmartPlaylist(stored, context).get();
    }

    private void markPlayed(FeedItem item) throws Exception {
        DBWriter.markItemsPlayed(FeedItem.PLAYED, true, Collections.singletonList(item)).get();
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

    private Feed storeFeed(String identifier, int itemCount) throws Exception {
        Feed feed = new Feed(0, null, "Feed " + identifier, "http://example.com/" + identifier,
                "description", null, "author", "en", null, "http://example.com/" + identifier,
                null, null, "http://example.com/" + identifier, System.currentTimeMillis());
        feed.setItems(new ArrayList<>());
        for (int i = 0; i < itemCount; i++) {
            FeedItem item = new FeedItem(0, "Item " + i, identifier + "-item-" + i,
                    "http://example.com/" + identifier + "/" + i, new Date(), FeedItem.UNPLAYED, feed);
            FeedMedia media = new FeedMedia(item, "http://example.com/" + identifier + "/" + i + ".mp3",
                    1234, "audio/mpeg");
            media.setDownloaded(true, System.currentTimeMillis());
            media.setLocalFileUrl("/local/" + identifier + "/" + i + ".mp3");
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

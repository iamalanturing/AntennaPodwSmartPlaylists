package de.danoeh.antennapod.storage.database;

import android.content.Context;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Rule-driven downloads fetch what a smart queue's next rebuild would pick if every episode were
 * on the device, treating the episodes already in the queue as about to be played. These tests pin
 * that choice for newest-first and oldest-first rules, the limits, declined episodes, RANDOM
 * rules, and the bookkeeping the stale-prefetch cleanup relies on.
 */
@RunWith(RobolectricTestRunner.class)
public class SmartQueueDownloadTargetsTest {
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long BASE_TIME = 1_700_000_000_000L;

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
    }

    @After
    public void tearDown() {
        PodDBAdapter.deleteDatabase();
        PodDBAdapter.tearDownTests();
    }

    @Test
    public void newestRuleTargetsTheNewestEpisodeWhateverItsDownloadState() throws Exception {
        Feed feed = storeFeed("feed", 3, 0);
        SmartPlaylist playlist = createPlaylist(rule(feed, "NEWEST", 1));

        assertEquals(idsOf(item(feed, 2)), idsOf(DBReader.getSmartPlaylistDownloadTargets(playlist.getId())));
    }

    @Test
    public void newestRuleWithTheNewestQueuedTargetsTheSecondNewest() throws Exception {
        Feed feed = storeFeed("feed", 3, 0);
        SmartPlaylist playlist = createPlaylist(rule(feed, "NEWEST", 1));
        setGeneratedList(playlist, item(feed, 2));

        assertEquals(idsOf(item(feed, 1)), idsOf(DBReader.getSmartPlaylistDownloadTargets(playlist.getId())));
    }

    @Test
    public void oldestRuleTargetsTheEpisodeAfterTheQueuedOneEvenWhenNewOnesArrive() throws Exception {
        Feed feed = storeFeed("feed", 3, 0);
        SmartPlaylist playlist = createPlaylist(rule(feed, "OLDEST", 1));
        setGeneratedList(playlist, item(feed, 0));

        assertEquals(idsOf(item(feed, 1)), idsOf(DBReader.getSmartPlaylistDownloadTargets(playlist.getId())));

        storeFeed("other", 2, 0);
        addItems(feed, 3, 2);
        assertEquals(idsOf(item(feed, 1)), idsOf(DBReader.getSmartPlaylistDownloadTargets(playlist.getId())));
    }

    @Test
    public void episodesInTheRealQueueOfTheActiveSmartQueueAreTreatedAsQueued() throws Exception {
        Feed feed = storeFeed("feed", 3, 0);
        SmartPlaylist playlist = createPlaylist(rule(feed, "NEWEST", 1));
        PlaybackPreferences.writeActiveSmartQueueId(playlist.getId());
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.setQueue(Collections.singletonList(item(feed, 2)));
        adapter.close();

        assertEquals(idsOf(item(feed, 1)), idsOf(DBReader.getSmartPlaylistDownloadTargets(playlist.getId())));
    }

    @Test
    public void declinedEpisodeIsSkippedAndTheNextMatchTakesItsPlace() throws Exception {
        Feed feed = storeFeed("feed", 3, 0);
        SmartPlaylist playlist = createPlaylist(rule(feed, "NEWEST", 1));
        DBWriter.markDeclined(item(feed, 2).getId()).get();

        assertEquals(idsOf(item(feed, 1)), idsOf(DBReader.getSmartPlaylistDownloadTargets(playlist.getId())));
    }

    @Test
    public void anEpisodeTwoRulesMatchIsClaimedByTheFirstOnly() throws Exception {
        Feed feed = storeFeed("feed", 3, 0);
        SmartPlaylist playlist = createPlaylist(rule(feed, "NEWEST", 1), rule(feed, "NEWEST", 1));

        assertEquals(idsOf(item(feed, 2), item(feed, 1)),
                idsOf(DBReader.getSmartPlaylistDownloadTargets(playlist.getId())));
    }

    @Test
    public void aRuleWithoutALimitTargetsTheDefaultNumber() throws Exception {
        Feed feed = storeFeed("feed", 5, 0);
        SmartPlaylist playlist = createPlaylist(rule(feed, "NEWEST", 0));

        assertEquals(DBReader.DEFAULT_DOWNLOAD_RULE_LIMIT,
                DBReader.getSmartPlaylistDownloadTargets(playlist.getId()).size());
    }

    @Test
    public void randomRuleKeepsWhatIsDownloadedAndOnlyTopsUpTheShortfall() throws Exception {
        Feed feed = storeFeed("feed", 4, 1);
        SmartPlaylist playlist = createPlaylist(rule(feed, "RANDOM", 2));

        List<Long> targets = idsOf(DBReader.getSmartPlaylistDownloadTargets(playlist.getId()));
        assertEquals(2, targets.size());
        assertTrue(targets.contains(item(feed, 0).getId()));
    }

    @Test
    public void rebuildPicksFollowTheRulesAsTheyRunDuringARebuild() throws Exception {
        Feed feed = storeFeed("feed", 3, 2);
        SmartPlaylist playlist = createPlaylist(rule(feed, "NEWEST", 1));

        assertEquals(Collections.singleton(item(feed, 1).getId()),
                DBReader.getSmartPlaylistRebuildPicks(playlist.getId()));
    }

    @Test
    public void deletingAnUnplayedEpisodeByHandMarksItDeclined() throws Exception {
        Feed feed = storeFeed("feed", 2, 2);
        item(feed, 1).setPlayed(true);
        DBWriter.setFeedItem(item(feed, 1), false).get();

        DBWriter.deleteFeedMediaOfItem(context, item(feed, 0).getMedia()).get();
        DBWriter.deleteFeedMediaOfItem(context, item(feed, 1).getMedia()).get();

        assertTrue(DBReader.getDeclinedIds().contains(item(feed, 0).getId()));
        assertFalse(DBReader.getDeclinedIds().contains(item(feed, 1).getId()));
    }

    @Test
    public void prefetchMarkersAreListedOldestFirstAndCanBeRemoved() throws Exception {
        Feed feed = storeFeed("feed", 2, 0);
        DBWriter.markPrefetched(Collections.singletonList(item(feed, 1).getId())).get();
        Thread.sleep(5);
        DBWriter.markPrefetched(Collections.singletonList(item(feed, 0).getId())).get();

        assertEquals(idsOf(item(feed, 1), item(feed, 0)), DBReader.getPrefetchedIds());

        DBWriter.removePrefetchMarker(item(feed, 1).getId()).get();
        assertEquals(idsOf(item(feed, 0)), DBReader.getPrefetchedIds());
    }

    @Test
    public void declinedEpisodesAreNotListedAsPrefetched() throws Exception {
        Feed feed = storeFeed("feed", 1, 0);
        DBWriter.markPrefetched(Collections.singletonList(item(feed, 0).getId())).get();
        DBWriter.markDeclined(item(feed, 0).getId()).get();

        assertTrue(DBReader.getPrefetchedIds().isEmpty());
    }

    @Test
    public void downloadRulesSettingSurvivesARoundTrip() throws Exception {
        Feed feed = storeFeed("feed", 1, 0);
        SmartPlaylist playlist = new SmartPlaylist();
        playlist.setName("Queue");
        playlist.setDownloadRules(true);
        playlist.getRules().add(rule(feed, "NEWEST", 1));
        DBWriter.createSmartPlaylist(playlist, context).get();

        assertTrue(DBReader.getSmartPlaylist(playlist.getId()).isDownloadRules());
        assertTrue(DBReader.getSmartPlaylists().get(0).isDownloadRules());
    }

    private SmartPlaylist createPlaylist(SmartPlaylistRule... rules) throws Exception {
        SmartPlaylist playlist = new SmartPlaylist();
        playlist.setName("Queue");
        playlist.setDownloadRules(true);
        playlist.getRules().addAll(Arrays.asList(rules));
        DBWriter.createSmartPlaylist(playlist, context).get();
        return playlist;
    }

    private SmartPlaylistRule rule(Feed feed, String sortOrder, int limit) {
        SmartPlaylistRule rule = new SmartPlaylistRule();
        rule.setFeedIds(String.valueOf(feed.getId()));
        rule.setSortOrder(sortOrder);
        rule.setEpisodeLimit(limit);
        return rule;
    }

    private void setGeneratedList(SmartPlaylist playlist, FeedItem... items) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.replaceSmartPlaylistEpisodes(playlist.getId(), idsOf(items));
        adapter.close();
    }

    private static FeedItem item(Feed feed, int index) {
        return feed.getItems().get(index);
    }

    private static List<Long> idsOf(FeedItem... items) {
        return idsOf(Arrays.asList(items));
    }

    private static List<Long> idsOf(List<FeedItem> items) {
        List<Long> ids = new ArrayList<>();
        for (FeedItem item : items) {
            ids.add(item.getId());
        }
        return ids;
    }

    /**
     * Stores a feed whose item {@code i} is published {@code i} days after the first, so a higher
     * index is newer. The first {@code downloadedCount} items are marked downloaded.
     */
    private Feed storeFeed(String identifier, int itemCount, int downloadedCount) throws Exception {
        Feed feed = new Feed(0, null, "Feed " + identifier, "http://example.com/" + identifier,
                "description", null, "author", "en", null, "http://example.com/" + identifier,
                null, null, "http://example.com/" + identifier, System.currentTimeMillis());
        feed.setItems(new ArrayList<>());
        for (int i = 0; i < itemCount; i++) {
            feed.getItems().add(newItem(feed, i, i < downloadedCount));
        }
        DBWriter.setCompleteFeed(feed).get();
        return feed;
    }

    private void addItems(Feed feed, int firstIndex, int count) throws Exception {
        for (int i = firstIndex; i < firstIndex + count; i++) {
            feed.getItems().add(newItem(feed, i, false));
        }
        DBWriter.setCompleteFeed(feed).get();
    }

    private static FeedItem newItem(Feed feed, int index, boolean downloaded) {
        String identifier = feed.getDownloadUrl();
        FeedItem item = new FeedItem(0, "Item " + index, identifier + "-item-" + index,
                identifier + "/" + index, new Date(BASE_TIME + index * DAY), FeedItem.UNPLAYED, feed);
        FeedMedia media = new FeedMedia(item, identifier + "/" + index + ".mp3", 1234, "audio/mpeg");
        if (downloaded) {
            media.setDownloaded(true, System.currentTimeMillis());
            media.setLocalFileUrl("/local/" + index + ".mp3");
        }
        item.setMedia(media);
        return item;
    }
}

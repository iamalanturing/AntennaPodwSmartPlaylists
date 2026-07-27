package de.danoeh.antennapod.storage.database;

import android.content.Context;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueueStub;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Date;

import static org.junit.Assert.assertEquals;

/**
 * The rule editor tells the user how many episodes each rule matches on its own, and that count is
 * the only signal distinguishing a working rule from one that quietly matches nothing. These tests
 * pin the count, the effect of a new rule's default filters, and the rule ordering the editor lets
 * the user drag around: rules are applied in order, so a round trip has to preserve it.
 */
@RunWith(RobolectricTestRunner.class)
public class SmartPlaylistRuleMatchCountTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        UserPreferences.init(context);
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.close();
        SynchronizationQueue.setInstance(new SynchronizationQueueStub());
    }

    @After
    public void tearDown() {
        // Feeds left behind here are read by other tests in this module through the shared adapter
        PodDBAdapter.deleteDatabase();
        PodDBAdapter.tearDownTests();
    }

    @Test
    public void countsEpisodesMatchingTheRule() throws Exception {
        Feed feed = storeFeed("feed-a", 3, true);
        SmartPlaylistRule rule = new SmartPlaylistRule();
        rule.setFeedIds(String.valueOf(feed.getId()));

        assertEquals(3, DBReader.getSmartPlaylistRuleMatchCount(rule));
    }

    @Test
    public void countsNothingWhenTheRuleMatchesNoFeed() throws Exception {
        storeFeed("feed-a", 3, true);
        SmartPlaylistRule rule = new SmartPlaylistRule();
        rule.setFeedIds("123456");

        assertEquals(0, DBReader.getSmartPlaylistRuleMatchCount(rule));
    }

    /**
     * A new rule filters on "unplayed,downloaded", so an episode that has not been downloaded is no
     * match. This is the usual reason a rule the user believes in reports nothing.
     */
    @Test
    public void defaultRuleSkipsEpisodesThatAreNotDownloaded() throws Exception {
        Feed feed = storeFeed("feed-a", 3, false);
        SmartPlaylistRule rule = new SmartPlaylistRule();
        rule.setFeedIds(String.valueOf(feed.getId()));

        assertEquals(0, DBReader.getSmartPlaylistRuleMatchCount(rule));

        rule.setFilterProperties("unplayed");
        assertEquals(3, DBReader.getSmartPlaylistRuleMatchCount(rule));
    }

    @Test
    public void countRespectsTheEpisodeLimit() throws Exception {
        Feed feed = storeFeed("feed-a", 5, true);
        SmartPlaylistRule rule = new SmartPlaylistRule();
        rule.setFeedIds(String.valueOf(feed.getId()));
        rule.setEpisodeLimit(2);

        assertEquals(2, DBReader.getSmartPlaylistRuleMatchCount(rule));
    }

    @Test
    public void countCoversEveryFeedTheRuleSelects() throws Exception {
        Feed first = storeFeed("feed-a", 2, true);
        Feed second = storeFeed("feed-b", 3, true);
        SmartPlaylistRule rule = new SmartPlaylistRule();
        rule.setFeedIds(first.getId() + "," + second.getId());

        assertEquals(5, DBReader.getSmartPlaylistRuleMatchCount(rule));
    }

    @Test
    public void ruleOrderSurvivesARoundTrip() throws Exception {
        Feed feed = storeFeed("feed-a", 1, true);
        SmartPlaylist playlist = new SmartPlaylist();
        playlist.setName("Queue");
        playlist.getRules().add(ruleFor(feed, "NEWEST"));
        playlist.getRules().add(ruleFor(feed, "OLDEST"));
        playlist.getRules().add(ruleFor(feed, "RANDOM"));
        DBWriter.createSmartPlaylist(playlist, context).get();

        SmartPlaylist stored = DBReader.getSmartPlaylist(playlist.getId());
        assertEquals(3, stored.getRules().size());
        assertEquals("NEWEST", stored.getRules().get(0).getSortOrder());
        assertEquals("OLDEST", stored.getRules().get(1).getSortOrder());
        assertEquals("RANDOM", stored.getRules().get(2).getSortOrder());

        // Reordering is what the drag handle does: move the first rule to the end and store again
        SmartPlaylistRule first = stored.getRules().remove(0);
        stored.getRules().add(first);
        DBWriter.updateSmartPlaylist(stored, context).get();

        SmartPlaylist reordered = DBReader.getSmartPlaylist(playlist.getId());
        assertEquals("OLDEST", reordered.getRules().get(0).getSortOrder());
        assertEquals("RANDOM", reordered.getRules().get(1).getSortOrder());
        assertEquals("NEWEST", reordered.getRules().get(2).getSortOrder());
    }

    private SmartPlaylistRule ruleFor(Feed feed, String sortOrder) {
        SmartPlaylistRule rule = new SmartPlaylistRule();
        rule.setFeedIds(String.valueOf(feed.getId()));
        rule.setSortOrder(sortOrder);
        return rule;
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
        assertEquals(itemCount, DBReader.getFeedItemList(feed, FeedItemFilter.unfiltered(),
                SortOrder.DATE_NEW_OLD, 0, Integer.MAX_VALUE).size());
        return feed;
    }
}

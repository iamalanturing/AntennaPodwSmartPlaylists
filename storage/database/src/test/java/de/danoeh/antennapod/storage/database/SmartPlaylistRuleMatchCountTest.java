package de.danoeh.antennapod.storage.database;

import android.content.Context;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueueStub;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;

/**
 * The rule editor tells the user how many episodes each rule matches on its own, and that count is
 * the only signal distinguishing a working rule from one that quietly matches nothing. These tests
 * pin the count, and the rule ordering the editor now lets the user drag around: rules are applied
 * in order, so a round trip through the database has to preserve it.
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

    @Test
    public void countsEpisodesMatchingTheRule() {
        Feed feed = storeFeed("feed-a", 3);
        SmartPlaylistRule rule = new SmartPlaylistRule();
        rule.setFeedIds(String.valueOf(feed.getId()));

        assertEquals(3, DBReader.getSmartPlaylistRuleMatchCount(rule));
    }

    @Test
    public void countsNothingWhenTheRuleMatchesNoFeed() {
        storeFeed("feed-a", 3);
        SmartPlaylistRule rule = new SmartPlaylistRule();
        rule.setFeedIds("123456");

        assertEquals(0, DBReader.getSmartPlaylistRuleMatchCount(rule));
    }

    @Test
    public void countRespectsTheEpisodeLimit() {
        Feed feed = storeFeed("feed-a", 5);
        SmartPlaylistRule rule = new SmartPlaylistRule();
        rule.setFeedIds(String.valueOf(feed.getId()));
        rule.setEpisodeLimit(2);

        assertEquals(2, DBReader.getSmartPlaylistRuleMatchCount(rule));
    }

    @Test
    public void countCoversEveryFeedTheRuleSelects() {
        Feed first = storeFeed("feed-a", 2);
        Feed second = storeFeed("feed-b", 3);
        SmartPlaylistRule rule = new SmartPlaylistRule();
        rule.setFeedIds(first.getId() + "," + second.getId());

        assertEquals(5, DBReader.getSmartPlaylistRuleMatchCount(rule));
    }

    @Test
    public void ruleOrderSurvivesARoundTrip() throws Exception {
        Feed feed = storeFeed("feed-a", 1);
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

        // Reordering is what the drag handle does: swap the ends and store again
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

    private Feed storeFeed(String identifier, int itemCount) {
        Feed feed = new Feed("url-" + identifier, null, "Feed " + identifier);
        feed.setItems(new ArrayList<>());
        for (int i = 0; i < itemCount; i++) {
            FeedItem item = new FeedItem();
            item.setItemIdentifier(identifier + "-item-" + i);
            item.setTitle("Item " + i);
            item.setMedia(new FeedMedia(item, "url-" + identifier + "-" + i, 2, "mime"));
            item.setFeed(feed);
            feed.getItems().add(item);
        }
        return FeedDatabaseWriter.updateFeed(context, feed, false);
    }

}

package de.danoeh.antennapod.net.download.service.episode.autodownload;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterfaceStub;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueueStub;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.SynchronizationSettings;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Smart queues with "Download episodes for this queue" on fetch what their rules would pick next,
 * and episodes fetched that way are removed again once nothing wants them, but only while the
 * device is short of space or over the episode limit. These tests pin what is fetched, what is
 * never removed, and when removal happens.
 */
@RunWith(RobolectricTestRunner.class)
public class SmartQueueDownloadsTest {
    private static final long PLENTY = Long.MAX_VALUE;

    private Context context;
    private File destFolder;
    private final List<Long> requested = new ArrayList<>();

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        destFolder = new File(context.getCacheDir(), "SmartQueueDownloadsTest");
        //noinspection ResultOfMethodCallIgnored
        destFolder.mkdir();

        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.close();

        setEpisodeLimit(UserPreferences.EPISODE_CACHE_SIZE_UNLIMITED);
        UserPreferences.init(context);
        PlaybackPreferences.init(context);
        SynchronizationSettings.init(context);
        SynchronizationQueue.setInstance(new SynchronizationQueueStub());
        DownloadServiceInterface.setImpl(new DownloadServiceInterfaceStub() {
            @Override
            public void download(Context context, FeedItem item) {
                requested.add(item.getId());
            }
        });
        SmartQueueDownloads.freeSpaceForTest = PLENTY;
    }

    @After
    public void tearDown() {
        SmartQueueDownloads.freeSpaceForTest = null;
        //noinspection ConstantConditions
        for (File f : destFolder.listFiles()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        destFolder.delete();
        DBWriter.tearDownTests();
        PodDBAdapter.tearDownTests();
    }

    @Test
    public void anEnabledQueueRequestsItsNextPickAndMarksItPrefetched() throws Exception {
        List<FeedItem> items = storeItems(3, 0);
        createPlaylist(true, rule("NEWEST", 1, items));

        SmartQueueDownloads.run(context);

        assertEquals(Collections.singletonList(items.get(2).getId()), requested);
        assertEquals(Collections.singletonList(items.get(2).getId()), DBReader.getPrefetchedIds());
    }

    @Test
    public void aQueueWithTheSwitchOffRequestsNothing() throws Exception {
        List<FeedItem> items = storeItems(3, 0);
        createPlaylist(false, rule("NEWEST", 1, items));

        SmartQueueDownloads.run(context);

        assertTrue(requested.isEmpty());
    }

    @Test
    public void anAlreadyDownloadedTargetIsNotRequestedAgain() throws Exception {
        List<FeedItem> items = storeItems(1, 1);
        createPlaylist(true, rule("NEWEST", 1, items));

        SmartQueueDownloads.run(context);

        assertTrue(requested.isEmpty());
    }

    @Test
    public void aTargetIsRequestedEvenAfterAnEarlierDownloadSwitchedOffItsAutoDownloadFlag() throws Exception {
        List<FeedItem> items = storeItems(1, 0);
        items.get(0).disableAutoDownload();
        DBWriter.setFeedItem(items.get(0), false).get();
        createPlaylist(true, rule("NEWEST", 1, items));

        SmartQueueDownloads.run(context);

        assertEquals(Collections.singletonList(items.get(0).getId()), requested);
    }

    @Test
    public void nothingIsRequestedWhileFreeSpaceIsBelowTheThreshold() throws Exception {
        List<FeedItem> items = storeItems(3, 0);
        createPlaylist(true, rule("NEWEST", 1, items));
        SmartQueueDownloads.freeSpaceForTest = 0L;

        SmartQueueDownloads.run(context);

        assertTrue(requested.isEmpty());
    }

    @Test
    public void stalePrefetchesStayWithoutPressure() throws Exception {
        List<FeedItem> items = storeItems(2, 2);
        markPrefetched(items);

        SmartQueueDownloads.run(context);

        assertFileExists(items.get(0), true);
        assertFileExists(items.get(1), true);
    }

    @Test
    public void overTheEpisodeLimitTheOldestPrefetchGoesFirstUntilBackUnder() throws Exception {
        List<FeedItem> items = storeItems(4, 4);
        markPrefetched(items);
        setEpisodeLimit(2);

        SmartQueueDownloads.run(context);

        assertFileExists(items.get(0), false);
        assertFileExists(items.get(1), false);
        assertFileExists(items.get(2), true);
        assertFileExists(items.get(3), true);
        assertFalse(DBReader.getDeclinedIds().contains(items.get(0).getId()));
        assertEquals(Arrays.asList(items.get(2).getId(), items.get(3).getId()), DBReader.getPrefetchedIds());
    }

    @Test
    public void lowSpaceNeverRemovesQueuedFavoriteStartedPlayedOrUnmarkedEpisodes() throws Exception {
        List<FeedItem> items = storeItems(6, 6);
        markPrefetched(items.subList(0, 5));
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.setQueue(Collections.singletonList(items.get(0)));
        adapter.setFavorites(Collections.singletonList(items.get(1)));
        adapter.close();
        items.get(2).getMedia().setPosition(1000);
        DBWriter.setFeedMediaPlaybackInformation(items.get(2).getMedia()).get();
        items.get(3).setPlayed(true);
        DBWriter.setFeedItem(items.get(3), false).get();
        SmartQueueDownloads.freeSpaceForTest = 0L;

        SmartQueueDownloads.run(context);

        assertFileExists(items.get(0), true);
        assertFileExists(items.get(1), true);
        assertFileExists(items.get(2), true);
        assertFileExists(items.get(3), true);
        assertFileExists(items.get(4), false);
        assertFileExists(items.get(5), true);
    }

    @Test
    public void targetsAndWhatARebuildWouldPickNowAreKept() throws Exception {
        List<FeedItem> items = storeItems(4, 4);
        markPrefetched(items);
        SmartPlaylist newest = createPlaylist(true, rule("NEWEST", 1, items));
        setGeneratedList(newest, items.get(3));
        createPlaylist(false, rule("OLDEST", 1, items));
        SmartQueueDownloads.freeSpaceForTest = 0L;

        SmartQueueDownloads.run(context);

        assertFileExists(items.get(0), true);
        assertFileExists(items.get(1), false);
        assertFileExists(items.get(2), true);
        assertFileExists(items.get(3), true);
    }

    private void assertFileExists(FeedItem item, boolean expected) {
        assertEquals("file of " + item.getTitle(), expected,
                new File(destFolder, fileName(item)).exists());
    }

    private static String fileName(FeedItem item) {
        return "file-" + item.getItemIdentifier();
    }

    private void setEpisodeLimit(int limit) {
        SharedPreferences.Editor prefEdit = PreferenceManager
                .getDefaultSharedPreferences(context.getApplicationContext()).edit();
        prefEdit.putString(UserPreferences.PREF_EPISODE_CACHE_SIZE, Integer.toString(limit));
        prefEdit.commit();
    }

    private void markPrefetched(List<FeedItem> items) throws Exception {
        for (FeedItem item : items) {
            DBWriter.markPrefetched(Collections.singletonList(item.getId())).get();
            Thread.sleep(5);
        }
    }

    private SmartPlaylist createPlaylist(boolean downloadRules, SmartPlaylistRule rule) throws Exception {
        SmartPlaylist playlist = new SmartPlaylist();
        playlist.setName("Queue");
        playlist.setDownloadRules(downloadRules);
        playlist.getRules().add(rule);
        DBWriter.createSmartPlaylist(playlist, context).get();
        return playlist;
    }

    private SmartPlaylistRule rule(String sortOrder, int limit, List<FeedItem> items) {
        SmartPlaylistRule rule = new SmartPlaylistRule();
        rule.setFeedIds(String.valueOf(items.get(0).getFeed().getId()));
        rule.setSortOrder(sortOrder);
        rule.setEpisodeLimit(limit);
        return rule;
    }

    private void setGeneratedList(SmartPlaylist playlist, FeedItem item) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.replaceSmartPlaylistEpisodes(playlist.getId(), Collections.singletonList(item.getId()));
        adapter.close();
    }

    /**
     * Item {@code i} is published {@code i} days after the first, so a higher index is newer. The
     * first {@code downloadedCount} items have a real file on disk.
     */
    private List<FeedItem> storeItems(int count, int downloadedCount) throws IOException {
        Feed feed = new Feed("url", null, "title");
        List<FeedItem> items = new ArrayList<>();
        feed.setItems(items);
        for (int i = 0; i < count; i++) {
            Date pubDate = new Date(1_700_000_000_000L + i * 24L * 60 * 60 * 1000);
            FeedItem item = new FeedItem(0, "title " + i, "id" + i, "link", pubDate, FeedItem.UNPLAYED, feed);
            String localFile = null;
            long downloadDate = 0;
            if (i < downloadedCount) {
                File file = new File(destFolder, fileName(item));
                assertTrue(file.createNewFile());
                localFile = file.getAbsolutePath();
                downloadDate = System.currentTimeMillis();
            }
            item.setMedia(new FeedMedia(0, item, 1, 0, 1L, "audio/mpeg",
                    localFile, "http://example.com/" + i + ".mp3", downloadDate, null, 0, 0));
            items.add(item);
        }
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.setCompleteFeed(feed);
        adapter.close();
        return items;
    }
}

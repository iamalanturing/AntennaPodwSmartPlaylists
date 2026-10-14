package de.danoeh.antennapod.storage.database;

import android.content.Context;
import com.google.common.util.concurrent.Futures;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.download.serviceinterface.AutoDownloadManager;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
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

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A restored database keeps the "downloaded" flag but not the files. These tests pin what counts as
 * a missing file, that it is repaired in the database, and what is queued for download.
 */
@RunWith(RobolectricTestRunner.class)
public class DownloadVerifierTest {
    private Context context;
    private final Set<String> downloading = new HashSet<>();
    private final List<FeedItem> requested = new ArrayList<>();

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
        DownloadServiceInterface.setImpl(new DownloadServiceInterface() {
            @Override
            public void downloadNow(Context context, FeedItem item, boolean ignoreConstraints) {
            }

            @Override
            public void download(Context context, FeedItem item) {
                requested.add(item);
            }

            @Override
            public void cancel(Context context, FeedMedia media) {
            }

            @Override
            public void cancelAll(Context context) {
            }

            @Override
            public boolean isDownloadingEpisode(String url) {
                return downloading.contains(url);
            }

            @Override
            public int getNumberOfActiveDownloads(Context context) {
                return 0;
            }
        });
    }

    @After
    public void tearDown() {
        DownloadServiceInterface.setImpl(null);
        PodDBAdapter.deleteDatabase();
        PodDBAdapter.tearDownTests();
    }

    @Test
    public void aPresentFileIsLeftAlone() throws Exception {
        FeedItem item = storeEpisode("present", true, writeFile("present.mp3", "audio"), 6);

        DownloadVerifier.Result result = DownloadVerifier.verify(Collections.singletonList(item));

        assertEquals(1, result.getChecked());
        assertTrue(result.getRepaired().isEmpty());
        assertTrue(result.getToDownload().isEmpty());
        assertTrue(DBReader.getFeedMedia(item.getMedia().getId()).isDownloaded());
    }

    @Test
    public void aMissingFileIsRepairedInTheDatabaseAndQueuedForDownload() throws Exception {
        File gone = new File(context.getCacheDir(), "gone.mp3");
        FeedItem item = storeEpisode("gone", true, gone, 1234);

        DownloadVerifier.Result result = DownloadVerifier.verify(Collections.singletonList(item));

        assertEquals(Collections.singletonList(item), result.getRepaired());
        assertEquals(Collections.singletonList(item), result.getToDownload());
        assertFalse(DBReader.getFeedMedia(item.getMedia().getId()).isDownloaded());
    }

    @Test
    public void anEmptyFileCountsAsMissing() throws Exception {
        FeedItem item = storeEpisode("empty", true, writeFile("empty.mp3", ""), 1234);

        DownloadVerifier.Result result = DownloadVerifier.verify(Collections.singletonList(item));

        assertEquals(1, result.getRepaired().size());
        assertEquals(1, result.getToDownload().size());
    }

    @Test
    public void aFileUnderHalfTheReportedSizeCountsAsMissing() throws Exception {
        FeedItem item = storeEpisode("truncated", true, writeFile("truncated.mp3", "audio"), 1000);

        DownloadVerifier.Result result = DownloadVerifier.verify(Collections.singletonList(item));

        assertEquals(1, result.getRepaired().size());
        assertEquals(1, result.getToDownload().size());
        assertFalse(DBReader.getFeedMedia(item.getMedia().getId()).isDownloaded());
    }

    @Test
    public void aFileAtExactlyHalfTheReportedSizeIsKept() throws Exception {
        FeedItem item = storeEpisode("half", true, writeFile("half.mp3", "audio"), 10);

        DownloadVerifier.Result result = DownloadVerifier.verify(Collections.singletonList(item));

        assertTrue(result.getRepaired().isEmpty());
        assertTrue(result.getToDownload().isEmpty());
    }

    @Test
    public void anUnknownSizeNeverTriggersTheSizeCheck() throws Exception {
        FeedItem item = storeEpisode("unknownsize", true, writeFile("unknownsize.mp3", "audio"), 0);

        DownloadVerifier.Result result = DownloadVerifier.verify(Collections.singletonList(item));

        assertTrue(result.getRepaired().isEmpty());
        assertTrue(result.getToDownload().isEmpty());
    }

    @Test
    public void anEpisodeNeverDownloadedIsQueuedButNotReportedAsRepaired() throws Exception {
        FeedItem item = storeEpisode("never", false, null, 1234);

        DownloadVerifier.Result result = DownloadVerifier.verify(Collections.singletonList(item));

        assertTrue(result.getRepaired().isEmpty());
        assertEquals(Collections.singletonList(item), result.getToDownload());
    }

    @Test
    public void anEpisodeAlreadyDownloadingIsNotQueuedAgain() throws Exception {
        FeedItem item = storeEpisode("busy", false, null, 1234);
        downloading.add(item.getMedia().getDownloadUrl());

        DownloadVerifier.Result result = DownloadVerifier.verify(Collections.singletonList(item));

        assertTrue(result.getToDownload().isEmpty());
    }

    @Test
    public void theTotalSizeCountsOnlyEpisodesThatReportOne() throws Exception {
        FeedItem sized = storeEpisode("sized", false, null, 100);
        FeedItem unsized = storeEpisode("unsized", false, null, 0);

        DownloadVerifier.Result result = DownloadVerifier.verify(Arrays.asList(sized, unsized));

        assertEquals(2, result.getToDownload().size());
        assertEquals(100, result.getTotalBytes());
    }

    @Test
    public void downloadingQueuesEveryEpisodeAndSaysHowMany() throws Exception {
        FeedItem first = storeEpisode("first", false, null, 1);
        FeedItem second = storeEpisode("second", false, null, 1);

        int queued = DownloadVerifier.download(context, Arrays.asList(first, second));

        assertEquals(2, queued);
        assertEquals(Arrays.asList(first, second), requested);
    }

    @Test
    public void anEpisodeWithoutMediaIsIgnored() throws Exception {
        FeedItem item = storeEpisode("nomedia", false, null, 0, false);

        DownloadVerifier.Result result = DownloadVerifier.verify(Collections.singletonList(item));

        assertEquals(0, result.getChecked());
        assertTrue(result.getToDownload().isEmpty());
    }

    private File writeFile(String name, String content) throws Exception {
        File file = new File(context.getCacheDir(), name);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
        return file;
    }

    private FeedItem storeEpisode(String identifier, boolean downloaded, File file, long size) throws Exception {
        return storeEpisode(identifier, downloaded, file, size, true);
    }

    private FeedItem storeEpisode(String identifier, boolean downloaded, File file, long size, boolean withMedia)
            throws Exception {
        Feed feed = new Feed(0, null, "Feed " + identifier, "http://example.com/" + identifier,
                "description", null, "author", "en", null, "http://example.com/" + identifier,
                null, null, "http://example.com/" + identifier, System.currentTimeMillis());
        feed.setItems(new ArrayList<>());
        FeedItem item = new FeedItem(0, "Item " + identifier, identifier + "-guid",
                "http://example.com/" + identifier + "/1", new Date(), FeedItem.UNPLAYED, feed);
        if (withMedia) {
            FeedMedia media = new FeedMedia(item, "http://example.com/" + identifier + ".mp3", size, "audio/mpeg");
            if (downloaded) {
                media.setDownloaded(true, System.currentTimeMillis());
                media.setLocalFileUrl(file.getAbsolutePath());
            }
            item.setMedia(media);
        }
        feed.getItems().add(item);
        DBWriter.setCompleteFeed(feed).get();
        assertTrue(item.getId() != 0);
        return item;
    }
}

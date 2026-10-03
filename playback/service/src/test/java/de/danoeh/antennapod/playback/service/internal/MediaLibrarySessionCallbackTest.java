package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;
import de.danoeh.antennapod.net.download.serviceinterface.AutoDownloadManager;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueueStub;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;
import de.danoeh.antennapod.playback.service.R;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.FeedDatabaseWriter;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
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
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

@RunWith(RobolectricTestRunner.class)
public class MediaLibrarySessionCallbackTest {
    private static final String EPISODE_TITLE = "Episode Title";
    private Context context;
    private MediaLibrarySessionCallback callback;
    private final MediaSession session = mock(MediaSession.class);
    private final MediaLibraryService.MediaLibrarySession librarySession =
            mock(MediaLibraryService.MediaLibrarySession.class);
    private final MediaSession.ControllerInfo controllerInfo = mock(MediaSession.ControllerInfo.class);

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        UserPreferences.init(context);
        PlaybackPreferences.init(context);
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
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
        callback = new MediaLibrarySessionCallback(context);
    }

    @After
    public void tearDown() {
        PodDBAdapter.tearDownTests();
    }

    @Test
    public void onSetMediaItemsStub() throws Exception {
        long mediaId = seedEpisode().getId();
        MediaItem browseItem = MediaItemAdapter.fromMediaIdStub(mediaId);
        MediaSession.MediaItemsWithStartPosition result = callback.onSetMediaItems(
                session, controllerInfo, Collections.singletonList(browseItem), C.INDEX_UNSET, C.TIME_UNSET)
                .get(5, TimeUnit.SECONDS);
        assertEquals(1, result.mediaItems.size());
        assertEquals(String.valueOf(mediaId), result.mediaItems.get(0).mediaId);
        assertEquals(EPISODE_TITLE, result.mediaItems.get(0).mediaMetadata.title);
    }

    @Test
    public void onPlaybackResumption() throws Exception {
        FeedMedia media = seedEpisode();
        PlaybackPreferences.writeMediaPlaying(media);
        MediaSession.MediaItemsWithStartPosition result = callback.onPlaybackResumption(session, controllerInfo)
                .get(5, TimeUnit.SECONDS);
        assertEquals(1, result.mediaItems.size());
        assertEquals(String.valueOf(media.getId()), result.mediaItems.get(0).mediaId);
    }

    @Test
    public void onAndroidAutoVoiceSearchQuery() throws Exception {
        long mediaId = seedEpisode().getId();
        MediaItem searchItem = MediaItem.EMPTY.buildUpon()
                .setRequestMetadata(new MediaItem.RequestMetadata.Builder().setSearchQuery(EPISODE_TITLE).build())
                .build();
        MediaSession.MediaItemsWithStartPosition result = callback.onSetMediaItems(session, controllerInfo,
                Collections.singletonList(searchItem), C.INDEX_UNSET, C.TIME_UNSET).get(5, TimeUnit.SECONDS);
        assertEquals(1, result.mediaItems.size());
        assertEquals(String.valueOf(mediaId), result.mediaItems.get(0).mediaId);

        // No match: nothing to play
        searchItem = MediaItem.EMPTY.buildUpon()
                .setRequestMetadata(new MediaItem.RequestMetadata.Builder().setSearchQuery("Unrelated").build())
                .build();
        result = callback.onSetMediaItems(session, controllerInfo,
                Collections.singletonList(searchItem), C.INDEX_UNSET, C.TIME_UNSET).get(5, TimeUnit.SECONDS);
        assertEquals(0, result.mediaItems.size());

        // Empty query ("play something"): fall back to playing something rather than nothing, per
        // Android Auto/Assistant voice action guidelines.
        searchItem = MediaItem.EMPTY.buildUpon()
                .setRequestMetadata(new MediaItem.RequestMetadata.Builder().setSearchQuery("").build())
                .build();
        result = callback.onSetMediaItems(session, controllerInfo,
                Collections.singletonList(searchItem), C.INDEX_UNSET, C.TIME_UNSET).get(5, TimeUnit.SECONDS);
        assertEquals(1, result.mediaItems.size());
        assertEquals(String.valueOf(mediaId), result.mediaItems.get(0).mediaId);
    }

    @Test
    public void rootHidesSmartQueuesFolderWhenThereAreNone() throws Exception {
        assertFalse(childIds("root").contains("smart_queues"));
    }

    @Test
    public void rootShowsSmartQueuesFolderWhenOneExists() throws Exception {
        createSmartPlaylist("Morning", seedDownloadedFeed());
        assertTrue(childIds("root").contains("smart_queues"));
    }

    @Test
    public void smartQueuesFolderListsEachQueueAsPlayable() throws Exception {
        Feed feed = seedDownloadedFeed();
        SmartPlaylist first = createSmartPlaylist("Morning", feed);
        SmartPlaylist second = createSmartPlaylist("Evening", feed);

        List<MediaItem> children = children("smart_queues");

        assertEquals(2, children.size());
        for (MediaItem child : children) {
            assertTrue(child.mediaMetadata.isPlayable);
            assertFalse(child.mediaMetadata.isBrowsable);
        }
        assertEquals("Morning", findSmartQueue(children, first).mediaMetadata.title);
        assertEquals("Evening", findSmartQueue(children, second).mediaMetadata.title);
    }

    @Test
    public void smartQueuesFolderMarksOnlyTheActiveQueue() throws Exception {
        Feed feed = seedDownloadedFeed();
        SmartPlaylist first = createSmartPlaylist("Morning", feed);
        SmartPlaylist second = createSmartPlaylist("Evening", feed);
        PlaybackPreferences.writeActiveSmartQueueId(first.getId());
        String marker = context.getString(R.string.smart_queue_active_subtitle, "").trim();

        List<MediaItem> children = children("smart_queues");

        assertTrue(findSmartQueue(children, first).mediaMetadata.subtitle.toString().startsWith(marker));
        assertFalse(findSmartQueue(children, second).mediaMetadata.subtitle.toString().startsWith(marker));
    }

    @Test
    public void selectingASmartQueueActivatesItAndPlaysAnEpisode() throws Exception {
        Feed feed = seedDownloadedFeed();
        SmartPlaylist playlist = createSmartPlaylist("Morning", feed);
        MediaItem choice = MediaItemAdapter.fromSmartQueue(context, playlist.getId(), "Morning",
                R.drawable.ic_playlist_play_black, null);

        MediaSession.MediaItemsWithStartPosition result = callback.onSetMediaItems(session, controllerInfo,
                Collections.singletonList(choice), C.INDEX_UNSET, C.TIME_UNSET).get(10, TimeUnit.SECONDS);

        assertEquals(1, result.mediaItems.size());
        assertEquals(String.valueOf(feed.getItems().get(0).getMedia().getId()), result.mediaItems.get(0).mediaId);
        assertEquals(playlist.getId(), PlaybackPreferences.getActiveSmartQueueId());
    }

    @Test
    public void selectingAnUnknownSmartQueuePlaysNothing() throws Exception {
        MediaItem choice = MediaItemAdapter.fromSmartQueue(context, 12345, "Gone",
                R.drawable.ic_playlist_play_black, null);

        MediaSession.MediaItemsWithStartPosition result = callback.onSetMediaItems(session, controllerInfo,
                Collections.singletonList(choice), C.INDEX_UNSET, C.TIME_UNSET).get(10, TimeUnit.SECONDS);

        assertEquals(0, result.mediaItems.size());
        assertEquals(0, PlaybackPreferences.getActiveSmartQueueId());
    }

    private MediaItem findSmartQueue(List<MediaItem> children, SmartPlaylist playlist) {
        for (MediaItem child : children) {
            if ((MediaItemAdapter.MEDIA_ID_SMART_QUEUE_PREFIX + playlist.getId()).equals(child.mediaId)) {
                return child;
            }
        }
        throw new AssertionError("Smart queue " + playlist.getId() + " not listed");
    }

    private List<MediaItem> children(String parentId) throws Exception {
        LibraryResult<ImmutableList<MediaItem>> result = callback.onGetChildren(
                librarySession, controllerInfo, parentId, 0, 100, null).get(10, TimeUnit.SECONDS);
        return result.value;
    }

    private List<String> childIds(String parentId) throws Exception {
        List<String> ids = new ArrayList<>();
        for (MediaItem item : children(parentId)) {
            ids.add(item.mediaId);
        }
        return ids;
    }

    private SmartPlaylist createSmartPlaylist(String name, Feed feed) throws Exception {
        SmartPlaylist playlist = new SmartPlaylist();
        playlist.setName(name);
        SmartPlaylistRule rule = new SmartPlaylistRule();
        rule.setFeedIds(String.valueOf(feed.getId()));
        playlist.getRules().add(rule);
        DBWriter.createSmartPlaylist(playlist, context).get();
        return playlist;
    }

    private Feed seedDownloadedFeed() throws Exception {
        Feed feed = new Feed(0, null, "Feed", "http://example.com/feed", "description", null, "author", "en",
                null, "http://example.com/feed", null, null, "http://example.com/feed", System.currentTimeMillis());
        feed.setItems(new ArrayList<>());
        FeedItem item = new FeedItem(0, EPISODE_TITLE, "guid", "http://example.com/feed/1", new Date(),
                FeedItem.UNPLAYED, feed);
        FeedMedia media = new FeedMedia(item, "http://example.com/feed/1.mp3", 1234, "audio/mpeg");
        media.setDownloaded(true, System.currentTimeMillis());
        media.setLocalFileUrl("/local/feed/1.mp3");
        item.setMedia(media);
        feed.getItems().add(item);
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.setCompleteFeed(feed);
        adapter.close();
        return feed;
    }

    private FeedMedia seedEpisode() {
        Feed feed = new Feed("url", null, null);
        feed.setItems(new ArrayList<>());
        FeedItem item = new FeedItem();
        item.setItemIdentifier("id");
        item.setTitle(EPISODE_TITLE);
        item.setMedia(new FeedMedia(item, "http://example.com", 2, "mime"));
        item.setFeed(feed);
        feed.getItems().add(item);
        return FeedDatabaseWriter.updateFeed(context, feed, false).getItems().get(0).getMedia();
    }
}

package de.danoeh.antennapod.net.download.service.episode.autodownload;

import android.content.Context;
import android.os.StatFs;
import android.util.Log;
import androidx.annotation.VisibleForTesting;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

/**
 * FORK: downloads what the rules of smart queues with "Download episodes for this queue" on would
 * pick next, and removes episodes fetched that way which are no longer wanted.
 *
 * <p>Runs from {@link AutomaticDownloadAlgorithm} once its network and power checks pass. Stale
 * prefetches are only removed when free space is below the user's threshold or the downloaded
 * episode count is over the episode limit. See FORK.md, "Rule-driven downloads".
 */
public class SmartQueueDownloads {
    private static final String TAG = "SmartQueueDownloads";

    @VisibleForTesting
    static Long freeSpaceForTest = null;

    public static void run(Context context) {
        List<FeedItem> targets = new ArrayList<>();
        for (SmartPlaylist playlist : DBReader.getSmartPlaylists()) {
            if (playlist.isDownloadRules()) {
                targets.addAll(DBReader.getSmartPlaylistDownloadTargets(playlist.getId()));
            }
        }
        Set<Long> targetIds = new HashSet<>();
        for (FeedItem item : targets) {
            targetIds.add(item.getId());
        }

        cleanUpStalePrefetches(context, targetIds);
        if (getFreeSpace() < UserPreferences.getSmartQueueKeepFreeSpaceBytes()) {
            Log.d(TAG, "Not enough free space for smart queue downloads");
            return;
        }

        List<Long> requested = new ArrayList<>();
        for (FeedItem item : targets) {
            if (!requested.contains(item.getId()) && shouldDownload(item)) {
                DownloadServiceInterface.get().download(context, item);
                requested.add(item.getId());
            }
        }
        if (!requested.isEmpty()) {
            Log.d(TAG, "Requested " + requested.size() + " smart queue downloads");
            try {
                DBWriter.markPrefetched(requested).get();
            } catch (InterruptedException | ExecutionException e) {
                Log.e(TAG, "Could not record smart queue downloads", e);
            }
        }
    }

    private static boolean shouldDownload(FeedItem item) {
        FeedMedia media = item.getMedia();
        if (media == null || media.isDownloaded() || item.getFeed() == null || item.getFeed().isLocalFeed()) {
            return false;
        }
        String url = media.getDownloadUrl();
        return !DownloadServiceInterface.get().isDownloadingEpisode(url)
                && !DownloadServiceInterface.get().isEpisodeQueued(url);
    }

    /**
     * Removes downloads that rule-driven downloads fetched ahead and nothing wants any more, oldest
     * fetch first, until the device is no longer short of space or over the episode limit.
     */
    static void cleanUpStalePrefetches(Context context, Set<Long> targetIds) {
        if (!underPressure()) {
            return;
        }
        List<Long> prefetched = DBReader.getPrefetchedIds();
        if (prefetched.isEmpty()) {
            return;
        }
        Set<Long> keep = protectedIds(targetIds);
        long playingMediaId = PlaybackPreferences.getCurrentlyPlayingFeedMediaId();
        for (long id : prefetched) {
            if (keep.contains(id)) {
                continue;
            }
            FeedItem item = DBReader.getFeedItem(id);
            if (item == null || item.getMedia() == null || item.isPlayed()) {
                DBWriter.removePrefetchMarker(id);
                continue;
            }
            FeedMedia media = item.getMedia();
            if (!media.isDownloaded() || item.isTagged(FeedItem.TAG_FAVORITE)
                    || media.getPosition() > 0 || media.getId() == playingMediaId) {
                continue;
            }
            try {
                DBWriter.deleteFeedMediaOfItem(context, media).get();
                DBWriter.removePrefetchMarker(id).get();
            } catch (InterruptedException | ExecutionException e) {
                Log.e(TAG, "Could not remove stale prefetch " + id, e);
                return;
            }
            Log.d(TAG, "Removed stale prefetch " + id);
            if (!underPressure()) {
                return;
            }
        }
    }

    private static Set<Long> protectedIds(Set<Long> targetIds) {
        Set<Long> keep = new HashSet<>(targetIds);
        for (long id : DBReader.getQueueIDList().toArray()) {
            keep.add(id);
        }
        for (long id : DBReader.getQueueStashIDList().toArray()) {
            keep.add(id);
        }
        for (SmartPlaylist playlist : DBReader.getSmartPlaylists()) {
            keep.addAll(DBReader.getSmartPlaylistEpisodeIds(playlist.getId()));
            keep.addAll(DBReader.getSmartPlaylistRebuildPicks(playlist.getId()));
        }
        return keep;
    }

    private static boolean underPressure() {
        if (getFreeSpace() < UserPreferences.getSmartQueueKeepFreeSpaceBytes()) {
            return true;
        }
        int cacheSize = UserPreferences.getEpisodeCacheSize();
        return cacheSize != UserPreferences.EPISODE_CACHE_SIZE_UNLIMITED
                && DBReader.getTotalEpisodeCount(new FeedItemFilter(FeedItemFilter.DOWNLOADED)) > cacheSize;
    }

    private static long getFreeSpace() {
        if (freeSpaceForTest != null) {
            return freeSpaceForTest;
        }
        File dataFolder = UserPreferences.getDataFolder(null);
        if (dataFolder == null) {
            return Long.MAX_VALUE;
        }
        StatFs stat = new StatFs(dataFolder.getAbsolutePath());
        return stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
    }
}

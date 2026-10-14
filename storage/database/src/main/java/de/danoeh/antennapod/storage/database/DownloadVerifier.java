package de.danoeh.antennapod.storage.database;

import android.content.Context;
import androidx.annotation.WorkerThread;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * FORK: checks that episodes marked as downloaded really are on this device.
 *
 * <p>After a database is restored onto another device the "downloaded" flag survives but the files
 * do not, and nothing else repairs that until the episode is played. A file counts as present when
 * it exists, is not empty, and is not smaller than half the size recorded for the episode. Half
 * rather than the full size because feeds often report wrong sizes, and a false alarm only costs a
 * needless download.
 */
public final class DownloadVerifier {
    private static final int MIN_SIZE_FRACTION_DENOMINATOR = 2;

    private DownloadVerifier() {
    }

    public static class Result {
        private final int checked;
        private final List<FeedItem> repaired;
        private final List<FeedItem> toDownload;
        private final long totalBytes;

        Result(int checked, List<FeedItem> repaired, List<FeedItem> toDownload, long totalBytes) {
            this.checked = checked;
            this.repaired = repaired;
            this.toDownload = toDownload;
            this.totalBytes = totalBytes;
        }

        public int getChecked() {
            return checked;
        }

        /**
         * Episodes that were marked downloaded but whose file was missing. They are now marked as
         * not downloaded.
         */
        public List<FeedItem> getRepaired() {
            return repaired;
        }

        /**
         * Everything that is not on the device and not already downloading, repaired episodes included.
         */
        public List<FeedItem> getToDownload() {
            return toDownload;
        }

        /**
         * The reported size of {@link #getToDownload()}, counting only episodes that report one.
         */
        public long getTotalBytes() {
            return totalBytes;
        }
    }

    /**
     * Repairs the downloaded flag of every episode in {@code items} whose file is gone and works out
     * what still has to be downloaded. Blocks on database writes, so not for the main thread.
     */
    @WorkerThread
    public static Result verify(List<FeedItem> items) throws ExecutionException, InterruptedException {
        int checked = 0;
        long totalBytes = 0;
        List<FeedItem> repaired = new ArrayList<>();
        List<FeedItem> toDownload = new ArrayList<>();
        List<Future<?>> writes = new ArrayList<>();
        for (FeedItem item : items) {
            FeedMedia media = item.getMedia();
            if (media == null || (item.getFeed() != null && item.getFeed().isLocalFeed())) {
                continue;
            }
            checked++;
            if (media.isDownloaded() && !fileIsPresent(media)) {
                media.setDownloaded(false, 0);
                media.setLocalFileUrl(null);
                writes.add(DBWriter.setMediaDownloadInformation(media));
                repaired.add(item);
            }
            if (!media.isDownloaded() && !isDownloading(media)) {
                toDownload.add(item);
                if (media.getSize() > 0) {
                    totalBytes += media.getSize();
                }
            }
        }
        for (Future<?> write : writes) {
            write.get();
        }
        if (!repaired.isEmpty()) {
            EventBus.getDefault().post(new FeedItemEvent(repaired, false));
        }
        return new Result(checked, repaired, toDownload, totalBytes);
    }

    /**
     * Queues the episodes the way the app does after a normal download request, so the user's
     * network settings still decide when they start.
     *
     * @return how many were queued
     */
    public static int download(Context context, List<FeedItem> items) {
        DownloadServiceInterface service = DownloadServiceInterface.get();
        if (service == null) {
            return 0;
        }
        for (FeedItem item : items) {
            service.download(context, item);
        }
        return items.size();
    }

    private static boolean fileIsPresent(FeedMedia media) {
        String url = media.getLocalFileUrl();
        if (url == null) {
            return false;
        }
        File file = new File(url);
        if (!file.isFile() || file.length() == 0) {
            return false;
        }
        return media.getSize() <= 0 || file.length() * MIN_SIZE_FRACTION_DENOMINATOR >= media.getSize();
    }

    private static boolean isDownloading(FeedMedia media) {
        DownloadServiceInterface service = DownloadServiceInterface.get();
        return service != null && media.getDownloadUrl() != null
                && service.isDownloadingEpisode(media.getDownloadUrl());
    }
}

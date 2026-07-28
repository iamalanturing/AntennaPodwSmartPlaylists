package de.danoeh.antennapod.playback.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.ui.widget.SmartQueuePlayStarter;

import java.util.List;

/**
 * Starts a named Smart Queue from its home screen widget.
 *
 * <p>The player widget's button is a plain media button, which toggles whatever happens to be
 * playing and cannot start a particular queue. Starting one means picking the episode the queue
 * detail screen would pick and recording the queue as active, so the playback service advances
 * within it afterwards. That needs the database, hence a receiver rather than a direct intent.
 */
public class SmartQueueWidgetPlayReceiver extends BroadcastReceiver {
    private static final String TAG = "SmartQueueWidgetPlay";

    @Override
    public void onReceive(Context context, Intent intent) {
        long playlistId = intent.getLongExtra(SmartQueuePlayStarter.EXTRA_PLAYLIST_ID, 0);
        if (playlistId == 0) {
            return;
        }

        // Only ever starts or resumes. Pausing is the widget's own business: when the queue is
        // already the active one it wires its button straight to the media button instead, which
        // reaches whatever is playing without touching the database.
        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                final FeedMedia media = findStartingEpisode(playlistId);
                if (media == null) {
                    return;
                }
                PlaybackPreferences.writeActiveSmartQueue(playlistId, media.getId());
                // Starting playback builds a MediaController, which needs a Looper, so it cannot
                // happen on this thread. The database work above must not happen on the main one.
                new Handler(Looper.getMainLooper()).post(() ->
                        new PlaybackServiceStarter(context, media).callEvenIfRunning(true).start());
            } catch (Exception e) {
                Log.e(TAG, "Failed to start smart queue " + playlistId, e);
            } finally {
                result.finish();
            }
        }, TAG).start();
    }

    /**
     * Mirrors what the queue detail screen's play button does: resume the episode already in
     * progress, otherwise the first unplayed one, otherwise start again at the top.
     */
    private FeedMedia findStartingEpisode(long playlistId) {
        List<FeedItem> episodes = DBReader.getSmartPlaylistEpisodes(playlistId);
        if (episodes.isEmpty()) {
            return null;
        }
        FeedItem startItem = null;
        for (FeedItem episode : episodes) {
            if (episode.getMedia() == null || episode.isPlayed()) {
                continue;
            }
            if (episode.getMedia().getPosition() > 0) {
                startItem = episode;
                break;
            }
            if (startItem == null) {
                startItem = episode;
            }
        }
        if (startItem == null) {
            startItem = episodes.get(0);
        }
        return startItem.getMedia();
    }
}

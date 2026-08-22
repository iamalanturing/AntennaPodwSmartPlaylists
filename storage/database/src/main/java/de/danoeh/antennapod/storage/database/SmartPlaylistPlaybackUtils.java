package de.danoeh.antennapod.storage.database;

import androidx.annotation.Nullable;

import de.danoeh.antennapod.model.feed.FeedItem;

import java.util.List;

public class SmartPlaylistPlaybackUtils {

    private SmartPlaylistPlaybackUtils() {
    }

    /**
     * Picks where to resume: the episode already in progress, else the first unplayed one, else
     * the first episode in the list.
     */
    @Nullable
    public static FeedItem pickStartEpisode(List<FeedItem> episodes) {
        FeedItem startItem = null;
        for (FeedItem episode : episodes) {
            if (episode.getMedia() == null || episode.isPlayed()) {
                continue;
            }
            if (episode.getMedia().getPosition() > 0) {
                return episode;
            }
            if (startItem == null) {
                startItem = episode;
            }
        }
        if (startItem == null && !episodes.isEmpty()) {
            startItem = episodes.get(0);
        }
        if (startItem == null || startItem.getMedia() == null) {
            return null;
        }
        return startItem;
    }
}

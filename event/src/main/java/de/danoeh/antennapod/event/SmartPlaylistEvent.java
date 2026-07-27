package de.danoeh.antennapod.event;

/**
 * FORK: Smart Queue — posted whenever a smart queue is created, edited, deleted or regenerated,
 * including the regeneration the playback service runs when a queue is exhausted. Screens showing
 * a queue subscribe to this instead of assuming the episodes they loaded are still current.
 */
public class SmartPlaylistEvent {
    private final long playlistId;

    public SmartPlaylistEvent(long playlistId) {
        this.playlistId = playlistId;
    }

    public boolean affects(long otherPlaylistId) {
        return playlistId == otherPlaylistId;
    }
}

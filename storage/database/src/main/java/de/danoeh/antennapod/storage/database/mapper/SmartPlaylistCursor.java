package de.danoeh.antennapod.storage.database.mapper;

import android.database.Cursor;
import android.database.CursorWrapper;
import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.storage.database.PodDBAdapter;

public class SmartPlaylistCursor extends CursorWrapper {
    private final int indexId;
    private final int indexName;
    private final int indexAutoRegenerate;
    private final int indexGeneratedAt;
    private final int indexCreatedAt;
    private final int indexUpdatedAt;

    public SmartPlaylistCursor(Cursor cursor) {
        super(cursor);
        indexId = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_ID);
        indexName = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_NAME);
        indexAutoRegenerate = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_AUTO_REGENERATE);
        indexGeneratedAt = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_GENERATED_AT);
        indexCreatedAt = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_CREATED_AT);
        indexUpdatedAt = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_UPDATED_AT);
    }

    public SmartPlaylist extractSmartPlaylist() {
        SmartPlaylist playlist = new SmartPlaylist();
        playlist.setId(getLong(indexId));
        playlist.setName(getString(indexName));
        playlist.setAutoRegenerate(getInt(indexAutoRegenerate) == 1);
        playlist.setGeneratedAt(getLong(indexGeneratedAt));
        playlist.setCreatedAt(getLong(indexCreatedAt));
        playlist.setUpdatedAt(getLong(indexUpdatedAt));
        return playlist;
    }
}

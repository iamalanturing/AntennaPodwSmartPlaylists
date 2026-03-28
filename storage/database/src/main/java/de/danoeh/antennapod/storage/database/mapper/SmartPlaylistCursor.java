package de.danoeh.antennapod.storage.database.mapper;

import android.database.Cursor;
import androidx.annotation.NonNull;
import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.storage.database.PodDBAdapter;

public abstract class SmartPlaylistCursor {
    @NonNull
    public static SmartPlaylist convert(@NonNull Cursor cursor) {
        SmartPlaylist playlist = new SmartPlaylist();
        int idIdx = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_ID);
        int nameIdx = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_NAME);
        int autoRegenIdx = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_AUTO_REGENERATE);
        int genAtIdx = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_GENERATED_AT);
        int createdIdx = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_CREATED_AT);
        int updatedIdx = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_UPDATED_AT);

        playlist.setId(cursor.getLong(idIdx));
        playlist.setName(cursor.getString(nameIdx));
        playlist.setAutoRegenerate(cursor.getInt(autoRegenIdx) == 1);
        playlist.setGeneratedAt(cursor.getLong(genAtIdx));
        playlist.setCreatedAt(cursor.getLong(createdIdx));
        playlist.setUpdatedAt(cursor.getLong(updatedIdx));
        return playlist;
    }
}

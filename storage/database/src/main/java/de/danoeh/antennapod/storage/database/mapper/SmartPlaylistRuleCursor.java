package de.danoeh.antennapod.storage.database.mapper;

import android.database.Cursor;
import androidx.annotation.NonNull;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;
import de.danoeh.antennapod.storage.database.PodDBAdapter;

public abstract class SmartPlaylistRuleCursor {
    @NonNull
    public static SmartPlaylistRule convert(@NonNull Cursor cursor) {
        SmartPlaylistRule rule = new SmartPlaylistRule();
        rule.setId(cursor.getLong(cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_ID)));
        rule.setPlaylistId(cursor.getLong(cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_ID)));
        rule.setPosition(cursor.getInt(cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_POSITION)));
        rule.setFilterProperties(cursor.getString(cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_FILTER_PROPERTIES)));
        rule.setFeedIds(cursor.getString(cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_FEED_IDS)));
        rule.setFeedTags(cursor.getString(cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_FEED_TAGS)));
        rule.setMaxAgeDays(cursor.getInt(cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_MAX_AGE_DAYS)));
        rule.setMinDurationMs(cursor.getInt(cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_MIN_DURATION_MS)));
        rule.setMaxDurationMs(cursor.getInt(cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_MAX_DURATION_MS)));
        rule.setMediaType(cursor.getString(cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_MEDIA_TYPE)));
        rule.setEpisodeLimit(cursor.getInt(cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_EPISODE_LIMIT)));
        rule.setSortOrder(cursor.getString(cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_SORT_ORDER)));
        return rule;
    }
}

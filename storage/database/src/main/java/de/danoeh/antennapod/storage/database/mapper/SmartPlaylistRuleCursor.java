package de.danoeh.antennapod.storage.database.mapper;

import android.database.Cursor;
import android.database.CursorWrapper;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;
import de.danoeh.antennapod.storage.database.PodDBAdapter;

public class SmartPlaylistRuleCursor extends CursorWrapper {
    private final int indexId;
    private final int indexPlaylistId;
    private final int indexPosition;
    private final int indexFilterProperties;
    private final int indexFeedIds;
    private final int indexFeedTags;
    private final int indexMaxAgeDays;
    private final int indexMinDurationMs;
    private final int indexMaxDurationMs;
    private final int indexMediaType;
    private final int indexEpisodeLimit;
    private final int indexSortOrder;

    public SmartPlaylistRuleCursor(Cursor cursor) {
        super(cursor);
        indexId = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_ID);
        indexPlaylistId = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_ID);
        indexPosition = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_POSITION);
        indexFilterProperties = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_FILTER_PROPERTIES);
        indexFeedIds = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_FEED_IDS);
        indexFeedTags = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_FEED_TAGS);
        indexMaxAgeDays = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_MAX_AGE_DAYS);
        indexMinDurationMs = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_MIN_DURATION_MS);
        indexMaxDurationMs = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_MAX_DURATION_MS);
        indexMediaType = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_MEDIA_TYPE);
        indexEpisodeLimit = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_EPISODE_LIMIT);
        indexSortOrder = cursor.getColumnIndexOrThrow(PodDBAdapter.KEY_SMART_PLAYLIST_SORT_ORDER);
    }

    public SmartPlaylistRule extractRule() {
        SmartPlaylistRule rule = new SmartPlaylistRule();
        rule.setId(getLong(indexId));
        rule.setPlaylistId(getLong(indexPlaylistId));
        rule.setPosition(getInt(indexPosition));
        rule.setFilterProperties(getString(indexFilterProperties));
        rule.setFeedIds(getString(indexFeedIds));
        rule.setFeedTags(getString(indexFeedTags));
        rule.setMaxAgeDays(getInt(indexMaxAgeDays));
        rule.setMinDurationMs(getInt(indexMinDurationMs));
        rule.setMaxDurationMs(getInt(indexMaxDurationMs));
        rule.setMediaType(getString(indexMediaType));
        rule.setEpisodeLimit(getInt(indexEpisodeLimit));
        rule.setSortOrder(getString(indexSortOrder));
        return rule;
    }
}

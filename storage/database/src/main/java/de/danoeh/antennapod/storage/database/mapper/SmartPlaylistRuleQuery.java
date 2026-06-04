package de.danoeh.antennapod.storage.database.mapper;

import android.text.TextUtils;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;
import de.danoeh.antennapod.storage.database.PodDBAdapter;

import java.util.ArrayList;
import java.util.List;

public class SmartPlaylistRuleQuery {
    private SmartPlaylistRuleQuery() {
        // Must not be instantiated
    }

    public static String generateWhereClause(SmartPlaylistRule rule) {
        List<String> conditions = new ArrayList<>();

        // Reuse FeedItemFilter for standard filter properties (played, downloaded, favorited, etc.)
        String filterProps = rule.getFilterProperties();
        if (!TextUtils.isEmpty(filterProps)) {
            FeedItemFilter filter = new FeedItemFilter(filterProps);
            String filterQuery = FeedItemFilterQuery.generateFrom(filter);
            if (!TextUtils.isEmpty(filterQuery)) {
                conditions.add(filterQuery);
            }
        }

        // Feed IDs — validate numeric before embedding to prevent injection
        String feedIds = rule.getFeedIds();
        if (!TextUtils.isEmpty(feedIds)) {
            String[] ids = feedIds.split(",");
            List<String> validatedIds = new ArrayList<>();
            for (String id : ids) {
                try {
                    Long.parseLong(id.trim());
                    validatedIds.add(id.trim());
                } catch (NumberFormatException e) {
                    // Skip non-numeric values
                }
            }
            if (!validatedIds.isEmpty()) {
                conditions.add(PodDBAdapter.TABLE_NAME_FEED_ITEMS + "." + PodDBAdapter.KEY_FEED
                        + " IN (" + TextUtils.join(",", validatedIds) + ")");
            }
        }

        // Feed tags — LIKE search on the Feeds.tags column (comma-separated tag string).
        // Escape LIKE wildcards (%, _) and backslash so user-supplied tags are treated literally.
        String feedTags = rule.getFeedTags();
        if (!TextUtils.isEmpty(feedTags)) {
            String[] tags = feedTags.split(",");
            List<String> tagConditions = new ArrayList<>();
            for (String tag : tags) {
                String trimmed = tag.trim();
                if (!trimmed.isEmpty()) {
                    String escaped = trimmed.replace("\\", "\\\\")
                            .replace("%", "\\%").replace("_", "\\_");
                    String sanitized = android.database.DatabaseUtils.sqlEscapeString(
                            "%" + escaped + "%");
                    tagConditions.add(PodDBAdapter.TABLE_NAME_FEEDS + "."
                            + PodDBAdapter.KEY_FEED_TAGS + " LIKE " + sanitized
                            + " ESCAPE '\\'");
                }
            }
            if (!tagConditions.isEmpty()) {
                conditions.add("(" + TextUtils.join(" OR ", tagConditions) + ")");
            }
        }

        // Max age in days — episode must be newer than cutoff
        if (rule.getMaxAgeDays() > 0) {
            long cutoff = System.currentTimeMillis() - ((long) rule.getMaxAgeDays() * 86400000L);
            conditions.add(PodDBAdapter.TABLE_NAME_FEED_ITEMS + "." + PodDBAdapter.KEY_PUBDATE
                    + " > " + cutoff);
        }

        // Duration filters (stored in milliseconds)
        if (rule.getMinDurationMs() > 0) {
            conditions.add(PodDBAdapter.TABLE_NAME_FEED_MEDIA + "." + PodDBAdapter.KEY_DURATION
                    + " >= " + rule.getMinDurationMs());
        }
        if (rule.getMaxDurationMs() > 0) {
            conditions.add(PodDBAdapter.TABLE_NAME_FEED_MEDIA + "." + PodDBAdapter.KEY_DURATION
                    + " <= " + rule.getMaxDurationMs());
        }

        // Media type filter — "audio" or "video". Escape wildcards before appending "/%".
        String mediaType = rule.getMediaType();
        if (!TextUtils.isEmpty(mediaType)) {
            String escapedType = mediaType.replace("\\", "\\\\")
                    .replace("%", "\\%").replace("_", "\\_");
            String sanitizedMediaType = android.database.DatabaseUtils.sqlEscapeString(
                    escapedType + "/%");
            conditions.add(PodDBAdapter.TABLE_NAME_FEED_MEDIA + "." + PodDBAdapter.KEY_MIME_TYPE
                    + " LIKE " + sanitizedMediaType + " ESCAPE '\\'");
        }

        if (conditions.isEmpty()) {
            return "";
        }

        StringBuilder query = new StringBuilder(" (");
        query.append(conditions.get(0));
        for (int i = 1; i < conditions.size(); i++) {
            query.append(" AND ");
            query.append(conditions.get(i));
        }
        query.append(") ");
        return query.toString();
    }

    public static String generateOrderClause(SmartPlaylistRule rule) {
        String sortOrder = rule.getSortOrder();
        if (sortOrder == null) {
            sortOrder = "NEWEST";
        }
        switch (sortOrder) {
            case "OLDEST":
                return PodDBAdapter.TABLE_NAME_FEED_ITEMS + "." + PodDBAdapter.KEY_PUBDATE + " ASC";
            case "SHORTEST":
                return PodDBAdapter.TABLE_NAME_FEED_MEDIA + "." + PodDBAdapter.KEY_DURATION + " ASC";
            case "LONGEST":
                return PodDBAdapter.TABLE_NAME_FEED_MEDIA + "." + PodDBAdapter.KEY_DURATION + " DESC";
            case "RANDOM":
                return "RANDOM()";
            case "NEWEST":
            default:
                return PodDBAdapter.TABLE_NAME_FEED_ITEMS + "." + PodDBAdapter.KEY_PUBDATE + " DESC";
        }
    }
}

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

        // Reuse FeedItemFilter for standard filter properties
        String filterProps = rule.getFilterProperties();
        if (!TextUtils.isEmpty(filterProps)) {
            FeedItemFilter filter = new FeedItemFilter(filterProps);
            String filterQuery = FeedItemFilterQuery.generateFrom(filter);
            if (!TextUtils.isEmpty(filterQuery)) {
                conditions.add(filterQuery);
            }
        }

        // Feed IDs filter
        String feedIds = rule.getFeedIds();
        if (!TextUtils.isEmpty(feedIds)) {
            conditions.add(PodDBAdapter.TABLE_NAME_FEED_ITEMS + "." + PodDBAdapter.KEY_FEED
                    + " IN (" + feedIds + ")");
        }

        // Feed tags filter — requires joining with Feeds table to check tags
        String feedTags = rule.getFeedTags();
        if (!TextUtils.isEmpty(feedTags)) {
            String[] tags = feedTags.split(",");
            List<String> tagConditions = new ArrayList<>();
            for (String tag : tags) {
                String trimmed = tag.trim();
                if (!trimmed.isEmpty()) {
                    tagConditions.add(PodDBAdapter.TABLE_NAME_FEEDS + "."
                            + PodDBAdapter.KEY_FEED_TAGS + " LIKE '%" + trimmed + "%'");
                }
            }
            if (!tagConditions.isEmpty()) {
                // If both feedIds and feedTags are set, feedIds are already applied above,
                // and tags are an additional OR condition on the feed
                if (!TextUtils.isEmpty(feedIds)) {
                    // Already filtered by feedIds, tags are additive
                } else {
                    conditions.add("(" + TextUtils.join(" OR ", tagConditions) + ")");
                }
            }
        }

        // Max age days
        if (rule.getMaxAgeDays() > 0) {
            long cutoff = System.currentTimeMillis() - ((long) rule.getMaxAgeDays() * 86400000L);
            conditions.add(PodDBAdapter.TABLE_NAME_FEED_ITEMS + "." + PodDBAdapter.KEY_PUBDATE
                    + " > " + cutoff);
        }

        // Duration filters
        if (rule.getMinDurationMs() > 0) {
            conditions.add(PodDBAdapter.TABLE_NAME_FEED_MEDIA + "." + PodDBAdapter.KEY_DURATION
                    + " >= " + rule.getMinDurationMs());
        }
        if (rule.getMaxDurationMs() > 0) {
            conditions.add(PodDBAdapter.TABLE_NAME_FEED_MEDIA + "." + PodDBAdapter.KEY_DURATION
                    + " <= " + rule.getMaxDurationMs());
        }

        // Media type filter
        String mediaType = rule.getMediaType();
        if (!TextUtils.isEmpty(mediaType)) {
            conditions.add(PodDBAdapter.TABLE_NAME_FEED_MEDIA + "." + PodDBAdapter.KEY_MIME_TYPE
                    + " LIKE '" + mediaType + "/%'");
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

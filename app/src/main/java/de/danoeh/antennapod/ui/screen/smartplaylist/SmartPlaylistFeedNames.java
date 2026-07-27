package de.danoeh.antennapod.ui.screen.smartplaylist;

import android.content.Context;
import android.text.TextUtils;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.Feed;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * FORK: Smart Queue — turns a rule's comma-separated feed ids into something readable. Rules are
 * listed by summary alone, so without the podcast names every rule with the same filters reads
 * identically.
 */
public final class SmartPlaylistFeedNames {
    private static final int MAX_NAMES = 2;

    private SmartPlaylistFeedNames() {
    }

    public static Set<Long> parseFeedIds(String feedIds) {
        Set<Long> ids = new LinkedHashSet<>();
        if (TextUtils.isEmpty(feedIds)) {
            return ids;
        }
        for (String id : feedIds.split(",")) {
            try {
                ids.add(Long.parseLong(id.trim()));
            } catch (NumberFormatException e) {
                // Skip non-numeric values, same as the rule compiler does
            }
        }
        return ids;
    }

    public static String joinFeedIds(Set<Long> feedIds) {
        List<String> ids = new ArrayList<>();
        for (Long id : feedIds) {
            ids.add(String.valueOf(id));
        }
        return TextUtils.join(",", ids);
    }

    /**
     * Names the selected podcasts, falling back to a count once there are too many to fit on one
     * line. An empty selection means the rule is not restricted to any podcast.
     */
    public static String describe(Context context, Set<Long> feedIds, List<Feed> feeds) {
        if (feedIds.isEmpty()) {
            return context.getString(R.string.smart_queue_rule_feeds_all);
        }
        List<String> names = new ArrayList<>();
        for (Feed feed : feeds) {
            if (feedIds.contains(feed.getId()) && !TextUtils.isEmpty(feed.getTitle())) {
                names.add(feed.getTitle());
            }
        }
        if (names.isEmpty() || names.size() > MAX_NAMES) {
            int count = names.isEmpty() ? feedIds.size() : names.size();
            return context.getResources().getQuantityString(
                    R.plurals.smart_queue_rule_feeds_count, count, count);
        }
        return TextUtils.join(", ", names);
    }
}

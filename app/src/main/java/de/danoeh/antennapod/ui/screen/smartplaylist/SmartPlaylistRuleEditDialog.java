package de.danoeh.antennapod.ui.screen.smartplaylist;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class SmartPlaylistRuleEditDialog {

    public interface OnRuleChangedListener {
        void onRuleChanged();
    }

    public static void show(Context context, SmartPlaylistRule rule, List<Feed> feeds,
                            OnRuleChangedListener listener) {
        View dialogView = LayoutInflater.from(context).inflate(
                R.layout.dialog_smart_playlist_rule_edit, null);

        // Podcast selection
        Set<Long> selectedFeedIds = SmartPlaylistFeedNames.parseFeedIds(rule.getFeedIds());
        Button feedsButton = dialogView.findViewById(R.id.rule_feeds_button);
        feedsButton.setText(SmartPlaylistFeedNames.describe(context, selectedFeedIds, feeds));
        feedsButton.setOnClickListener(v -> showFeedPicker(context, feeds, selectedFeedIds,
                () -> feedsButton.setText(
                        SmartPlaylistFeedNames.describe(context, selectedFeedIds, feeds))));

        // Playback state chips
        ChipGroup filterChips = dialogView.findViewById(R.id.filter_chip_group);
        Set<String> activeFilters = new HashSet<>(
                Arrays.asList(rule.getFilterProperties().split(",")));
        setupFilterChips(filterChips, activeFilters, rule);

        // Tag selection, offering only tags that are actually in use
        List<String> allTags = collectTags(feeds);
        Set<String> selectedTags = splitTags(rule.getFeedTags());
        Button tagsButton = dialogView.findViewById(R.id.rule_tags_button);
        tagsButton.setText(describeTags(context, selectedTags));
        tagsButton.setOnClickListener(v -> showTagPicker(context, allTags, selectedTags,
                () -> tagsButton.setText(describeTags(context, selectedTags))));

        // Max age
        EditText maxAgeEdit = dialogView.findViewById(R.id.rule_max_age_edit);
        if (rule.getMaxAgeDays() > 0) {
            maxAgeEdit.setText(String.valueOf(rule.getMaxAgeDays()));
        }

        // Min/max duration (display in minutes)
        EditText minDurEdit = dialogView.findViewById(R.id.rule_min_duration_edit);
        EditText maxDurEdit = dialogView.findViewById(R.id.rule_max_duration_edit);
        if (rule.getMinDurationMs() > 0) {
            minDurEdit.setText(String.valueOf(rule.getMinDurationMs() / 60000));
        }
        if (rule.getMaxDurationMs() > 0) {
            maxDurEdit.setText(String.valueOf(rule.getMaxDurationMs() / 60000));
        }

        // Media type
        AutoCompleteTextView mediaTypeInput = dialogView.findViewById(R.id.rule_media_type_spinner);
        String[] mediaTypeValues = {"", "audio", "video"};
        String[] mediaTypeLabels = context.getResources().getStringArray(R.array.smart_queue_media_type_entries);
        mediaTypeInput.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, mediaTypeLabels));
        int storedMediaTypeIdx = Arrays.asList(mediaTypeValues).indexOf(rule.getMediaType());
        final int[] mediaTypeIdx = {storedMediaTypeIdx >= 0 ? storedMediaTypeIdx : 0};
        mediaTypeInput.setText(mediaTypeLabels[mediaTypeIdx[0]], false);
        mediaTypeInput.setOnItemClickListener((parent, v, position, id) -> mediaTypeIdx[0] = position);

        // Sort order
        AutoCompleteTextView sortInput = dialogView.findViewById(R.id.rule_sort_spinner);
        String[] sortValues = {"NEWEST", "OLDEST", "SHORTEST", "LONGEST", "RANDOM"};
        String[] sortLabels = context.getResources().getStringArray(R.array.smart_queue_sort_order_entries);
        sortInput.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, sortLabels));
        int storedSortIdx = Arrays.asList(sortValues).indexOf(rule.getSortOrder());
        final int[] sortIdx = {storedSortIdx >= 0 ? storedSortIdx : 0};
        sortInput.setText(sortLabels[sortIdx[0]], false);
        sortInput.setOnItemClickListener((parent, v, position, id) -> sortIdx[0] = position);

        // Episode limit
        EditText limitEdit = dialogView.findViewById(R.id.rule_episode_limit_edit);
        if (rule.getEpisodeLimit() > 0) {
            limitEdit.setText(String.valueOf(rule.getEpisodeLimit()));
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.smart_queue_rule_filter)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    // Collect filter chips
                    List<String> props = new ArrayList<>();
                    for (String p : activeFilters) {
                        if (!p.isEmpty()) {
                            props.add(p);
                        }
                    }
                    rule.setFilterProperties(String.join(",", props));
                    rule.setFeedIds(SmartPlaylistFeedNames.joinFeedIds(selectedFeedIds));
                    rule.setFeedTags(TextUtils.join(",", selectedTags));

                    String maxAgeStr = maxAgeEdit.getText().toString().trim();
                    rule.setMaxAgeDays(maxAgeStr.isEmpty() ? 0 : parseInt(maxAgeStr));

                    String minDurStr = minDurEdit.getText().toString().trim();
                    rule.setMinDurationMs(minutesToMillis(minDurStr));

                    String maxDurStr = maxDurEdit.getText().toString().trim();
                    rule.setMaxDurationMs(minutesToMillis(maxDurStr));

                    rule.setMediaType(mediaTypeValues[mediaTypeIdx[0]]);

                    rule.setSortOrder(sortValues[sortIdx[0]]);

                    String limitStr = limitEdit.getText().toString().trim();
                    rule.setEpisodeLimit(limitStr.isEmpty() ? 0 : parseInt(limitStr));

                    listener.onRuleChanged();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    private static void showFeedPicker(Context context, List<Feed> feeds, Set<Long> selectedFeedIds,
                                       Runnable onPicked) {
        String[] titles = new String[feeds.size()];
        boolean[] checked = new boolean[feeds.size()];
        for (int i = 0; i < feeds.size(); i++) {
            String title = feeds.get(i).getTitle();
            titles[i] = title != null ? title : "";
            checked[i] = selectedFeedIds.contains(feeds.get(i).getId());
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.smart_queue_rule_feeds)
                .setMultiChoiceItems(titles, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    selectedFeedIds.clear();
                    for (int i = 0; i < feeds.size(); i++) {
                        if (checked[i]) {
                            selectedFeedIds.add(feeds.get(i).getId());
                        }
                    }
                    onPicked.run();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .setNeutralButton(R.string.smart_queue_rule_feeds_all, (d, w) -> {
                    selectedFeedIds.clear();
                    onPicked.run();
                })
                .show();
    }

    private static List<String> collectTags(List<Feed> feeds) {
        Set<String> tags = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Feed feed : feeds) {
            if (feed.getPreferences() != null && feed.getPreferences().getTags() != null) {
                for (String tag : feed.getPreferences().getTags()) {
                    if (!TextUtils.isEmpty(tag) && !FeedPreferences.TAG_ROOT.equals(tag)) {
                        tags.add(tag);
                    }
                }
            }
        }
        return new ArrayList<>(tags);
    }

    private static Set<String> splitTags(String feedTags) {
        Set<String> tags = new LinkedHashSet<>();
        if (TextUtils.isEmpty(feedTags)) {
            return tags;
        }
        for (String tag : feedTags.split(",")) {
            if (!tag.trim().isEmpty()) {
                tags.add(tag.trim());
            }
        }
        return tags;
    }

    private static String describeTags(Context context, Set<String> selectedTags) {
        if (selectedTags.isEmpty()) {
            return context.getString(R.string.smart_queue_rule_tags_all);
        }
        return TextUtils.join(", ", selectedTags);
    }

    private static void showTagPicker(Context context, List<String> allTags,
                                      Set<String> selectedTags, Runnable onPicked) {
        String[] titles = allTags.toArray(new String[0]);
        boolean[] checked = new boolean[allTags.size()];
        for (int i = 0; i < allTags.size(); i++) {
            checked[i] = selectedTags.contains(allTags.get(i));
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.smart_queue_rule_tags)
                .setMultiChoiceItems(titles, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    selectedTags.clear();
                    for (int i = 0; i < allTags.size(); i++) {
                        if (checked[i]) {
                            selectedTags.add(allTags.get(i));
                        }
                    }
                    onPicked.run();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .setNeutralButton(R.string.smart_queue_rule_tags_all, (d, w) -> {
                    selectedTags.clear();
                    onPicked.run();
                })
                .show();
    }

    private static void setupFilterChips(ChipGroup group, Set<String> activeFilters,
                                         SmartPlaylistRule rule) {
        String[][] filterOptions = {
                {FeedItemFilter.UNPLAYED, "Unplayed"},
                {FeedItemFilter.PLAYED, "Played"},
                {FeedItemFilter.DOWNLOADED, "Downloaded"},
                {FeedItemFilter.NOT_DOWNLOADED, "Not downloaded"},
                {FeedItemFilter.IS_FAVORITE, "Favorite"},
        };
        for (String[] option : filterOptions) {
            Chip chip = (Chip) LayoutInflater.from(group.getContext()).inflate(
                    R.layout.item_smart_playlist_filter_chip, group, false);
            chip.setText(option[1]);
            chip.setChecked(activeFilters.contains(option[0]));
            chip.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) {
                    activeFilters.add(option[0]);
                    // Remove conflicting state
                    if (option[0].equals(FeedItemFilter.UNPLAYED)) {
                        activeFilters.remove(FeedItemFilter.PLAYED);
                    }
                    if (option[0].equals(FeedItemFilter.PLAYED)) {
                        activeFilters.remove(FeedItemFilter.UNPLAYED);
                    }
                    if (option[0].equals(FeedItemFilter.DOWNLOADED)) {
                        activeFilters.remove(FeedItemFilter.NOT_DOWNLOADED);
                    }
                    if (option[0].equals(FeedItemFilter.NOT_DOWNLOADED)) {
                        activeFilters.remove(FeedItemFilter.DOWNLOADED);
                    }
                } else {
                    activeFilters.remove(option[0]);
                }
            });
            group.addView(chip);
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Converts a minutes field to milliseconds. Multiplying in {@code int} overflowed for
     * anything from about 35 792 minutes upwards, wrapping negative; the rule compiler only
     * emits a duration condition when the value is positive, so the filter silently vanished
     * instead of being applied. Widen to {@code long} and saturate at {@link Integer#MAX_VALUE},
     * which is the largest value the rule model can hold (~24 days) and far beyond any episode.
     */
    private static int minutesToMillis(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        long millis = (long) parseInt(s) * 60000L;
        return (int) Math.max(0, Math.min(millis, Integer.MAX_VALUE));
    }
}

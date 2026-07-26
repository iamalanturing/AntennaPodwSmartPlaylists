package de.danoeh.antennapod.ui.screen.smartplaylist;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SmartPlaylistRuleEditDialog {

    public interface OnRuleChangedListener {
        void onRuleChanged();
    }

    public static void show(Context context, SmartPlaylistRule rule, OnRuleChangedListener listener) {
        View dialogView = LayoutInflater.from(context).inflate(
                R.layout.dialog_smart_playlist_rule_edit, null);

        // Playback state chips
        ChipGroup filterChips = dialogView.findViewById(R.id.filter_chip_group);
        Set<String> activeFilters = new HashSet<>(
                Arrays.asList(rule.getFilterProperties().split(",")));
        setupFilterChips(filterChips, activeFilters, rule);

        // Tags field
        EditText tagsEdit = dialogView.findViewById(R.id.rule_tags_edit);
        tagsEdit.setText(rule.getFeedTags());

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

        // Sort order spinner
        Spinner sortSpinner = dialogView.findViewById(R.id.rule_sort_spinner);
        String[] sortValues = {"NEWEST", "OLDEST", "SHORTEST", "LONGEST", "RANDOM"};
        String[] sortLabels = context.getResources().getStringArray(R.array.smart_queue_sort_order_entries);
        ArrayAdapter<String> sortAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, sortLabels);
        sortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sortSpinner.setAdapter(sortAdapter);
        int sortIdx = Arrays.asList(sortValues).indexOf(rule.getSortOrder());
        if (sortIdx >= 0) {
            sortSpinner.setSelection(sortIdx);
        }

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
                    rule.setFeedTags(tagsEdit.getText().toString().trim());

                    String maxAgeStr = maxAgeEdit.getText().toString().trim();
                    rule.setMaxAgeDays(maxAgeStr.isEmpty() ? 0 : parseInt(maxAgeStr));

                    String minDurStr = minDurEdit.getText().toString().trim();
                    rule.setMinDurationMs(minutesToMillis(minDurStr));

                    String maxDurStr = maxDurEdit.getText().toString().trim();
                    rule.setMaxDurationMs(minutesToMillis(maxDurStr));

                    rule.setSortOrder(sortValues[sortSpinner.getSelectedItemPosition()]);

                    String limitStr = limitEdit.getText().toString().trim();
                    rule.setEpisodeLimit(limitStr.isEmpty() ? 0 : parseInt(limitStr));

                    listener.onRuleChanged();
                })
                .setNegativeButton(R.string.cancel_label, null)
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
            Chip chip = new Chip(group.getContext());
            chip.setText(option[1]);
            chip.setCheckable(true);
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

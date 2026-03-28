package de.danoeh.antennapod.ui.screen.smartplaylist;

import android.app.Dialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;
import de.danoeh.antennapod.storage.database.DBReader;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SmartPlaylistRuleEditDialog extends DialogFragment {
    private SmartPlaylistRule rule;
    private OnRuleSavedListener listener;

    private Spinner feedsSpinner;
    private Spinner tagsSpinner;
    private Spinner statusSpinner;
    private Spinner downloadedSpinner;
    private Spinner favoritedSpinner;
    private Spinner mediaTypeSpinner;
    private Spinner sortOrderSpinner;
    private EditText minDurationEdit;
    private EditText maxDurationEdit;
    private EditText maxAgeEdit;
    private EditText episodeLimitEdit;

    private List<Feed> allFeeds = new ArrayList<>();
    private List<String> allTags = new ArrayList<>();

    public interface OnRuleSavedListener {
        void onRuleSaved(SmartPlaylistRule rule);
    }

    public static SmartPlaylistRuleEditDialog newInstance(SmartPlaylistRule rule) {
        SmartPlaylistRuleEditDialog dialog = new SmartPlaylistRuleEditDialog();
        dialog.rule = rule != null ? rule : new SmartPlaylistRule();
        return dialog;
    }

    public void setOnRuleSavedListener(OnRuleSavedListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_smart_playlist_rule_edit, null);

        feedsSpinner = view.findViewById(R.id.feedsSpinner);
        tagsSpinner = view.findViewById(R.id.tagsSpinner);
        statusSpinner = view.findViewById(R.id.statusSpinner);
        downloadedSpinner = view.findViewById(R.id.downloadedSpinner);
        favoritedSpinner = view.findViewById(R.id.favoritedSpinner);
        mediaTypeSpinner = view.findViewById(R.id.mediaTypeSpinner);
        sortOrderSpinner = view.findViewById(R.id.sortOrderSpinner);
        minDurationEdit = view.findViewById(R.id.minDurationEdit);
        maxDurationEdit = view.findViewById(R.id.maxDurationEdit);
        maxAgeEdit = view.findViewById(R.id.maxAgeEdit);
        episodeLimitEdit = view.findViewById(R.id.episodeLimitEdit);

        setupStaticSpinners();
        loadFeedsAndTags();
        populateFromRule();

        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.smart_playlist_edit_rule)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> saveRule())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }

    private void setupStaticSpinners() {
        // Status: Any, Unplayed, Played
        String[] statusOptions = {
                getString(R.string.smart_playlist_status_any),
                getString(R.string.smart_playlist_status_unplayed),
                getString(R.string.smart_playlist_status_played)
        };
        statusSpinner.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, statusOptions));

        // Downloaded: Any, Downloaded, Not downloaded
        String[] downloadOptions = {
                getString(R.string.smart_playlist_downloaded_any),
                getString(R.string.smart_playlist_downloaded_yes),
                getString(R.string.smart_playlist_downloaded_no)
        };
        downloadedSpinner.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, downloadOptions));

        // Favorited: Any, Favorited, Not favorited
        String[] favoritedOptions = {
                getString(R.string.smart_playlist_favorited_any),
                getString(R.string.smart_playlist_favorited_yes),
                getString(R.string.smart_playlist_favorited_no)
        };
        favoritedSpinner.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, favoritedOptions));

        // Media type: Any, Audio, Video
        String[] mediaOptions = {
                getString(R.string.smart_playlist_media_any),
                getString(R.string.smart_playlist_media_audio),
                getString(R.string.smart_playlist_media_video)
        };
        mediaTypeSpinner.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, mediaOptions));

        // Sort order
        String[] sortOptions = {
                getString(R.string.smart_playlist_sort_newest),
                getString(R.string.smart_playlist_sort_oldest),
                getString(R.string.smart_playlist_sort_shortest),
                getString(R.string.smart_playlist_sort_longest),
                getString(R.string.smart_playlist_sort_random)
        };
        sortOrderSpinner.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, sortOptions));
    }

    private void loadFeedsAndTags() {
        Observable.fromCallable(() -> {
            List<Feed> feeds = DBReader.getFeedList();
            Set<String> tags = new LinkedHashSet<>();
            for (Feed feed : feeds) {
                if (feed.getPreferences() != null && feed.getPreferences().getTags() != null) {
                    for (String tag : feed.getPreferences().getTags()) {
                        if (!tag.startsWith("#")) { // Skip internal tags like #root, #untagged
                            tags.add(tag);
                        }
                    }
                }
            }
            List<String> sortedTags = new ArrayList<>(tags);
            Collections.sort(sortedTags, String.CASE_INSENSITIVE_ORDER);
            Collections.sort(feeds, (a, b) -> {
                String nameA = a.getTitle() != null ? a.getTitle() : "";
                String nameB = b.getTitle() != null ? b.getTitle() : "";
                return nameA.compareToIgnoreCase(nameB);
            });
            return new Object[]{feeds, sortedTags};
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    @SuppressWarnings("unchecked")
                    List<Feed> feeds = (List<Feed>) result[0];
                    @SuppressWarnings("unchecked")
                    List<String> tags = (List<String>) result[1];

                    allFeeds = feeds;
                    allTags = tags;

                    // Feeds spinner: "All feeds" + individual feeds
                    List<String> feedNames = new ArrayList<>();
                    feedNames.add(getString(R.string.smart_playlist_all_feeds));
                    for (Feed feed : feeds) {
                        feedNames.add(feed.getTitle() != null ? feed.getTitle() : "Unknown");
                    }
                    feedsSpinner.setAdapter(new ArrayAdapter<>(requireContext(),
                            android.R.layout.simple_spinner_dropdown_item, feedNames));

                    // Tags spinner: "All tags" + individual tags
                    List<String> tagNames = new ArrayList<>();
                    tagNames.add(getString(R.string.smart_playlist_all_tags));
                    tagNames.addAll(tags);
                    tagsSpinner.setAdapter(new ArrayAdapter<>(requireContext(),
                            android.R.layout.simple_spinner_dropdown_item, tagNames));

                    // Reapply selection after data loaded
                    selectCurrentFeed();
                    selectCurrentTag();
                });
    }

    private void populateFromRule() {
        if (rule == null) return;

        // Status from filterProperties
        String filter = rule.getFilterProperties();
        if (filter != null && filter.contains("played")) {
            statusSpinner.setSelection(2); // Played
        } else if (filter != null && filter.contains("unplayed")) {
            statusSpinner.setSelection(1); // Unplayed
        } else {
            statusSpinner.setSelection(0); // Any
        }

        // Downloaded
        if (filter != null && filter.contains("downloaded")) {
            downloadedSpinner.setSelection(1); // Downloaded
        } else if (filter != null && filter.contains("not_downloaded")) {
            downloadedSpinner.setSelection(2); // Not downloaded
        } else {
            downloadedSpinner.setSelection(0); // Any
        }

        // Favorited
        if (filter != null && filter.contains("is_favorite")) {
            favoritedSpinner.setSelection(1); // Favorited
        } else if (filter != null && filter.contains("not_favorite")) {
            favoritedSpinner.setSelection(2); // Not favorited
        } else {
            favoritedSpinner.setSelection(0); // Any
        }

        // Media type
        if ("audio".equals(rule.getMediaType())) {
            mediaTypeSpinner.setSelection(1);
        } else if ("video".equals(rule.getMediaType())) {
            mediaTypeSpinner.setSelection(2);
        } else {
            mediaTypeSpinner.setSelection(0);
        }

        // Duration
        if (rule.getMinDurationMs() > 0) {
            minDurationEdit.setText(String.valueOf(rule.getMinDurationMs() / 60000));
        }
        if (rule.getMaxDurationMs() > 0) {
            maxDurationEdit.setText(String.valueOf(rule.getMaxDurationMs() / 60000));
        }

        // Max age
        if (rule.getMaxAgeDays() > 0) {
            maxAgeEdit.setText(String.valueOf(rule.getMaxAgeDays()));
        }

        // Episode limit
        if (rule.getEpisodeLimit() > 0) {
            episodeLimitEdit.setText(String.valueOf(rule.getEpisodeLimit()));
        }

        // Sort order
        String sortOrder = rule.getSortOrder();
        if (sortOrder != null) {
            switch (sortOrder) {
                case "OLDEST": sortOrderSpinner.setSelection(1); break;
                case "SHORTEST": sortOrderSpinner.setSelection(2); break;
                case "LONGEST": sortOrderSpinner.setSelection(3); break;
                case "RANDOM": sortOrderSpinner.setSelection(4); break;
                default: sortOrderSpinner.setSelection(0); break;
            }
        }
    }

    private void selectCurrentFeed() {
        if (rule == null || TextUtils.isEmpty(rule.getFeedIds()) || allFeeds.isEmpty()) return;
        // Simple: select first matching feed (single feed for now)
        try {
            long feedId = Long.parseLong(rule.getFeedIds().split(",")[0].trim());
            for (int i = 0; i < allFeeds.size(); i++) {
                if (allFeeds.get(i).getId() == feedId) {
                    feedsSpinner.setSelection(i + 1); // +1 for "All feeds"
                    break;
                }
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void selectCurrentTag() {
        if (rule == null || TextUtils.isEmpty(rule.getFeedTags()) || allTags.isEmpty()) return;
        String firstTag = rule.getFeedTags().split(",")[0].trim();
        for (int i = 0; i < allTags.size(); i++) {
            if (allTags.get(i).equals(firstTag)) {
                tagsSpinner.setSelection(i + 1); // +1 for "All tags"
                break;
            }
        }
    }

    private void saveRule() {
        if (rule == null) {
            rule = new SmartPlaylistRule();
        }

        // Build filter properties
        List<String> filterParts = new ArrayList<>();
        int statusPos = statusSpinner.getSelectedItemPosition();
        if (statusPos == 1) filterParts.add("unplayed");
        else if (statusPos == 2) filterParts.add("played");

        int downloadedPos = downloadedSpinner.getSelectedItemPosition();
        if (downloadedPos == 1) filterParts.add("downloaded");
        else if (downloadedPos == 2) filterParts.add("not_downloaded");

        int favoritedPos = favoritedSpinner.getSelectedItemPosition();
        if (favoritedPos == 1) filterParts.add("is_favorite");
        else if (favoritedPos == 2) filterParts.add("not_favorite");

        rule.setFilterProperties(TextUtils.join(",", filterParts));

        // Feed selection
        int feedPos = feedsSpinner.getSelectedItemPosition();
        if (feedPos > 0 && feedPos <= allFeeds.size()) {
            rule.setFeedIds(String.valueOf(allFeeds.get(feedPos - 1).getId()));
        } else {
            rule.setFeedIds("");
        }

        // Tag selection
        int tagPos = tagsSpinner.getSelectedItemPosition();
        if (tagPos > 0 && tagPos <= allTags.size()) {
            rule.setFeedTags(allTags.get(tagPos - 1));
        } else {
            rule.setFeedTags("");
        }

        // Media type
        int mediaPos = mediaTypeSpinner.getSelectedItemPosition();
        if (mediaPos == 1) rule.setMediaType("audio");
        else if (mediaPos == 2) rule.setMediaType("video");
        else rule.setMediaType("");

        // Duration (convert minutes to ms)
        rule.setMinDurationMs(parseIntSafe(minDurationEdit.getText().toString()) * 60000);
        rule.setMaxDurationMs(parseIntSafe(maxDurationEdit.getText().toString()) * 60000);

        // Max age
        rule.setMaxAgeDays(parseIntSafe(maxAgeEdit.getText().toString()));

        // Episode limit
        rule.setEpisodeLimit(parseIntSafe(episodeLimitEdit.getText().toString()));

        // Sort order
        String[] sortValues = {"NEWEST", "OLDEST", "SHORTEST", "LONGEST", "RANDOM"};
        int sortPos = sortOrderSpinner.getSelectedItemPosition();
        rule.setSortOrder(sortPos >= 0 && sortPos < sortValues.length ? sortValues[sortPos] : "NEWEST");

        if (listener != null) {
            listener.onRuleSaved(rule);
        }
    }

    private int parseIntSafe(String text) {
        if (TextUtils.isEmpty(text)) return 0;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

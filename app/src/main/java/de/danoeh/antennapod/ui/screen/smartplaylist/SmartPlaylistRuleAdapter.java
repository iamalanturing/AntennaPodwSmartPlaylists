package de.danoeh.antennapod.ui.screen.smartplaylist;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SmartPlaylistRuleAdapter extends RecyclerView.Adapter<SmartPlaylistRuleAdapter.RuleViewHolder> {
    private final List<SmartPlaylistRule> rules = new ArrayList<>();
    private final Map<Long, String> feedNameMap = new HashMap<>();
    private OnRuleActionListener listener;

    public interface OnRuleActionListener {
        void onEditRule(int position, SmartPlaylistRule rule);
        void onDeleteRule(int position);
    }

    public void setOnRuleActionListener(OnRuleActionListener listener) {
        this.listener = listener;
    }

    public void setFeedNameMap(Map<Long, String> map) {
        feedNameMap.clear();
        feedNameMap.putAll(map);
        notifyDataSetChanged();
    }

    public void setRules(List<SmartPlaylistRule> newRules) {
        rules.clear();
        rules.addAll(newRules);
        notifyDataSetChanged();
    }

    public List<SmartPlaylistRule> getRules() {
        return new ArrayList<>(rules);
    }

    public void addRule(SmartPlaylistRule rule) {
        rules.add(rule);
        notifyItemInserted(rules.size() - 1);
    }

    public void updateRule(int position, SmartPlaylistRule rule) {
        if (position >= 0 && position < rules.size()) {
            rules.set(position, rule);
            notifyItemChanged(position);
        }
    }

    public void removeRule(int position) {
        if (position >= 0 && position < rules.size()) {
            rules.remove(position);
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, rules.size() - position);
        }
    }

    public void swapRules(int from, int to) {
        if (from < to) {
            for (int i = from; i < to; i++) {
                Collections.swap(rules, i, i + 1);
            }
        } else {
            for (int i = from; i > to; i--) {
                Collections.swap(rules, i, i - 1);
            }
        }
        notifyItemMoved(from, to);
    }

    @Override
    public int getItemCount() {
        return rules.size();
    }

    @NonNull
    @Override
    public RuleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_smart_playlist_rule, parent, false);
        return new RuleViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RuleViewHolder holder, int position) {
        SmartPlaylistRule rule = rules.get(position);
        holder.ruleTitle.setText(holder.itemView.getContext().getString(
                R.string.smart_playlist_rule, position + 1));
        holder.ruleSummary.setText(buildRuleSummary(holder, rule));
        holder.editButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onEditRule(holder.getAdapterPosition(), rule);
            }
        });
        holder.deleteButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDeleteRule(holder.getAdapterPosition());
            }
        });
    }

    private String buildRuleSummary(RuleViewHolder holder, SmartPlaylistRule rule) {
        android.content.Context ctx = holder.itemView.getContext();
        List<String> parts = new ArrayList<>();

        // Show feed name if a specific feed is selected
        if (!TextUtils.isEmpty(rule.getFeedIds())) {
            try {
                long feedId = Long.parseLong(rule.getFeedIds().split(",")[0].trim());
                String feedName = feedNameMap.get(feedId);
                if (feedName != null) {
                    parts.add(feedName);
                } else {
                    parts.add(ctx.getString(R.string.smart_playlist_rule_feed_unknown, feedId));
                }
            } catch (NumberFormatException ignored) {
            }
        }

        // Show tag name if a tag is selected
        if (!TextUtils.isEmpty(rule.getFeedTags())) {
            parts.add(ctx.getString(R.string.smart_playlist_rule_tag_prefix, rule.getFeedTags()));
        }

        // Show "All feeds" if neither feed nor tag is specified
        if (TextUtils.isEmpty(rule.getFeedIds()) && TextUtils.isEmpty(rule.getFeedTags())) {
            parts.add(ctx.getString(R.string.smart_playlist_all_feeds));
        }

        if (rule.getEpisodeLimit() > 0) {
            parts.add(ctx.getString(R.string.smart_playlist_rule_limit_prefix, rule.getEpisodeLimit()));
        }
        if (rule.getSortOrder() != null) {
            parts.add(rule.getSortOrder());
        }
        return TextUtils.join(" | ", parts);
    }

    static class RuleViewHolder extends RecyclerView.ViewHolder {
        final TextView ruleTitle;
        final TextView ruleSummary;
        final ImageButton editButton;
        final ImageButton deleteButton;

        RuleViewHolder(View itemView) {
            super(itemView);
            ruleTitle = itemView.findViewById(R.id.ruleTitle);
            ruleSummary = itemView.findViewById(R.id.ruleSummary);
            editButton = itemView.findViewById(R.id.editButton);
            deleteButton = itemView.findViewById(R.id.deleteButton);
        }
    }
}

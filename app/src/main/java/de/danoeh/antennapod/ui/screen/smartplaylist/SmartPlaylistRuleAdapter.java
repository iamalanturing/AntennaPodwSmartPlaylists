package de.danoeh.antennapod.ui.screen.smartplaylist;

import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SmartPlaylistRuleAdapter extends RecyclerView.Adapter<SmartPlaylistRuleAdapter.ViewHolder> {
    private List<SmartPlaylistRule> rules;
    private List<Feed> feeds = new ArrayList<>();
    private List<Integer> matchCounts = new ArrayList<>();
    private final OnRuleClickListener listener;
    private OnStartDragListener dragListener;
    private Runnable onRulesChanged;

    public interface OnRuleClickListener {
        void onRuleClicked(SmartPlaylistRule rule);
    }

    public interface OnStartDragListener {
        void onStartDrag(RecyclerView.ViewHolder holder);
    }

    public SmartPlaylistRuleAdapter(List<SmartPlaylistRule> rules, OnRuleClickListener listener) {
        this.rules = rules;
        this.listener = listener;
    }

    public void setRules(List<SmartPlaylistRule> rules) {
        this.rules = rules;
        notifyDataSetChanged();
    }

    public void setFeeds(List<Feed> feeds) {
        this.feeds = feeds;
        notifyDataSetChanged();
    }

    public void setMatchCounts(List<Integer> matchCounts) {
        this.matchCounts = matchCounts;
        notifyDataSetChanged();
    }

    public void setOnStartDragListener(OnStartDragListener dragListener) {
        this.dragListener = dragListener;
    }

    public void setOnRulesChangedListener(Runnable onRulesChanged) {
        this.onRulesChanged = onRulesChanged;
    }

    /**
     * Rules are applied in list order and the earlier one wins an episode's place in the queue,
     * so the order is part of what the user is editing.
     */
    public void moveRule(int from, int to) {
        if (from < 0 || to < 0 || from >= rules.size() || to >= rules.size()) {
            return;
        }
        Collections.swap(rules, from, to);
        notifyItemMoved(from, to);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_smart_playlist_rule, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SmartPlaylistRule rule = rules.get(position);
        holder.summaryView.setText(buildSummary(rule, holder));
        if (position < matchCounts.size()) {
            int count = matchCounts.get(position);
            holder.countView.setVisibility(View.VISIBLE);
            holder.countView.setText(holder.itemView.getContext().getResources()
                    .getQuantityString(R.plurals.smart_queue_n_episodes_plural, count, count));
        } else {
            holder.countView.setVisibility(View.GONE);
        }
        holder.itemView.setOnClickListener(v -> listener.onRuleClicked(rule));
        holder.dragHandle.setOnTouchListener((v, event) -> {
            if (dragListener != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                dragListener.onStartDrag(holder);
            }
            return false;
        });
        holder.deleteButton.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos >= 0 && pos < rules.size()) {
                rules.remove(pos);
                notifyItemRemoved(pos);
                if (onRulesChanged != null) {
                    onRulesChanged.run();
                }
            }
        });
    }

    private String buildSummary(SmartPlaylistRule rule, ViewHolder holder) {
        StringBuilder sb = new StringBuilder();
        if (!rule.getFilterProperties().isEmpty()) {
            sb.append(rule.getFilterProperties().replace(",", ", "));
        }
        if (!rule.getFeedIds().isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(SmartPlaylistFeedNames.describe(holder.itemView.getContext(),
                    SmartPlaylistFeedNames.parseFeedIds(rule.getFeedIds()), feeds));
        }
        if (!rule.getFeedTags().isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("Tags: ").append(rule.getFeedTags());
        }
        if (sb.length() == 0) {
            sb.append(holder.itemView.getContext().getString(R.string.smart_queue_rule_filter));
        }
        return sb.toString();
    }

    @Override
    public int getItemCount() {
        return rules.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView summaryView;
        TextView countView;
        ImageButton deleteButton;
        ImageView dragHandle;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            summaryView = itemView.findViewById(R.id.rule_summary);
            countView = itemView.findViewById(R.id.rule_episode_count);
            deleteButton = itemView.findViewById(R.id.rule_delete_button);
            dragHandle = itemView.findViewById(R.id.rule_drag_handle);
        }
    }
}

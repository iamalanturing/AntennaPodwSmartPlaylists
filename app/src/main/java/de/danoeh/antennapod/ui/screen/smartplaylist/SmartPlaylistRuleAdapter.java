package de.danoeh.antennapod.ui.screen.smartplaylist;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;

import java.util.List;

public class SmartPlaylistRuleAdapter extends RecyclerView.Adapter<SmartPlaylistRuleAdapter.ViewHolder> {
    private List<SmartPlaylistRule> rules;
    private final OnRuleClickListener listener;

    public interface OnRuleClickListener {
        void onRuleClicked(SmartPlaylistRule rule);
    }

    public SmartPlaylistRuleAdapter(List<SmartPlaylistRule> rules, OnRuleClickListener listener) {
        this.rules = rules;
        this.listener = listener;
    }

    public void setRules(List<SmartPlaylistRule> rules) {
        this.rules = rules;
        notifyDataSetChanged();
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
        holder.itemView.setOnClickListener(v -> listener.onRuleClicked(rule));
        holder.deleteButton.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos >= 0 && pos < rules.size()) {
                rules.remove(pos);
                notifyItemRemoved(pos);
            }
        });
    }

    private String buildSummary(SmartPlaylistRule rule, ViewHolder holder) {
        StringBuilder sb = new StringBuilder();
        if (!rule.getFilterProperties().isEmpty()) {
            sb.append(rule.getFilterProperties().replace(",", ", "));
        }
        if (!rule.getFeedIds().isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(holder.itemView.getContext().getString(R.string.smart_queue_rule_feeds));
        }
        if (!rule.getFeedTags().isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
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
        ImageButton deleteButton;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            summaryView = itemView.findViewById(R.id.rule_summary);
            deleteButton = itemView.findViewById(R.id.rule_delete_button);
        }
    }
}

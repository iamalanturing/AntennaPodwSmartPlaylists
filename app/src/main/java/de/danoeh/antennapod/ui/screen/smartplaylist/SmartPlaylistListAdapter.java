package de.danoeh.antennapod.ui.screen.smartplaylist;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.SmartPlaylist;

import java.util.ArrayList;
import java.util.List;

public class SmartPlaylistListAdapter extends RecyclerView.Adapter<SmartPlaylistListAdapter.ViewHolder> {
    private final List<SmartPlaylist> playlists = new ArrayList<>();
    private OnPlaylistClickListener listener;

    public interface OnPlaylistClickListener {
        void onPlaylistClick(SmartPlaylist playlist);
    }

    public void setOnPlaylistClickListener(OnPlaylistClickListener listener) {
        this.listener = listener;
    }

    public void updateData(List<SmartPlaylist> newPlaylists) {
        playlists.clear();
        playlists.addAll(newPlaylists);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return playlists.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_smart_playlist_list, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SmartPlaylist playlist = playlists.get(position);
        holder.playlistName.setText(playlist.getName());

        int ruleCount = playlist.getRules() != null ? playlist.getRules().size() : 0;
        String info = ruleCount + (ruleCount == 1 ? " rule" : " rules");
        if (playlist.isAutoRegenerate()) {
            info += " \u00B7 Auto-rebuild";
        }
        holder.playlistInfo.setText(info);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPlaylistClick(playlist);
            }
        });
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView playlistName;
        final TextView playlistInfo;

        ViewHolder(View itemView) {
            super(itemView);
            playlistName = itemView.findViewById(R.id.playlistName);
            playlistInfo = itemView.findViewById(R.id.playlistInfo);
        }
    }
}

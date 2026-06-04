package de.danoeh.antennapod.ui.screen.smartplaylist;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.SmartPlaylist;

import java.util.List;

public class SmartPlaylistListAdapter extends RecyclerView.Adapter<SmartPlaylistListAdapter.ViewHolder> {
    private final List<SmartPlaylist> playlists;
    private final OnPlaylistClickListener listener;

    public interface OnPlaylistClickListener {
        void onPlaylistClicked(SmartPlaylist playlist);
    }

    public SmartPlaylistListAdapter(List<SmartPlaylist> playlists, OnPlaylistClickListener listener) {
        this.playlists = playlists;
        this.listener = listener;
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
        holder.nameView.setText(playlist.getName());
        holder.countView.setText(holder.itemView.getContext().getResources()
                .getQuantityString(R.plurals.smart_queue_n_episodes_plural,
                        playlist.getEpisodeCount(), playlist.getEpisodeCount()));
        holder.itemView.setOnClickListener(v -> listener.onPlaylistClicked(playlist));
    }

    @Override
    public int getItemCount() {
        return playlists.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView nameView;
        TextView countView;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            nameView = itemView.findViewById(R.id.smart_playlist_name);
            countView = itemView.findViewById(R.id.smart_playlist_episode_count);
        }
    }
}

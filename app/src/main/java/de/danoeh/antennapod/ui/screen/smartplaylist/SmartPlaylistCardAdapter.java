package de.danoeh.antennapod.ui.screen.smartplaylist;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.SmartPlaylist;

import java.util.ArrayList;
import java.util.List;

public class SmartPlaylistCardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int VIEW_TYPE_PLAYLIST = 0;
    private static final int VIEW_TYPE_ADD = 1;

    private final List<SmartPlaylist> playlists = new ArrayList<>();
    private OnPlaylistClickListener clickListener;
    private OnCreateClickListener createListener;

    public interface OnPlaylistClickListener {
        void onPlaylistClick(SmartPlaylist playlist);
    }

    public interface OnCreateClickListener {
        void onCreateClick();
    }

    public void setOnPlaylistClickListener(OnPlaylistClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnCreateClickListener(OnCreateClickListener listener) {
        this.createListener = listener;
    }

    public void updateData(List<SmartPlaylist> newPlaylists) {
        playlists.clear();
        playlists.addAll(newPlaylists);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return playlists.size() + 1; // +1 for the "add" card
    }

    @Override
    public int getItemViewType(int position) {
        if (position == playlists.size()) {
            return VIEW_TYPE_ADD;
        }
        return VIEW_TYPE_PLAYLIST;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_ADD) {
            View view = inflater.inflate(R.layout.item_smart_playlist_add_card, parent, false);
            return new AddViewHolder(view);
        }
        View view = inflater.inflate(R.layout.item_smart_playlist_card, parent, false);
        return new PlaylistViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof PlaylistViewHolder) {
            SmartPlaylist playlist = playlists.get(position);
            PlaylistViewHolder pvh = (PlaylistViewHolder) holder;
            pvh.playlistName.setText(playlist.getName());
            String countText = holder.itemView.getContext().getString(
                    R.string.smart_playlist_episodes_count, 0);
            pvh.episodeCount.setText(countText);
            pvh.itemView.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onPlaylistClick(playlist);
                }
            });
        } else if (holder instanceof AddViewHolder) {
            holder.itemView.setOnClickListener(v -> {
                if (createListener != null) {
                    createListener.onCreateClick();
                }
            });
        }
    }

    static class PlaylistViewHolder extends RecyclerView.ViewHolder {
        final TextView playlistName;
        final TextView episodeCount;
        final ImageView playlistIcon;

        PlaylistViewHolder(View itemView) {
            super(itemView);
            playlistName = itemView.findViewById(R.id.playlistName);
            episodeCount = itemView.findViewById(R.id.episodeCount);
            playlistIcon = itemView.findViewById(R.id.playlistIcon);
        }
    }

    static class AddViewHolder extends RecyclerView.ViewHolder {
        AddViewHolder(View itemView) {
            super(itemView);
        }
    }
}

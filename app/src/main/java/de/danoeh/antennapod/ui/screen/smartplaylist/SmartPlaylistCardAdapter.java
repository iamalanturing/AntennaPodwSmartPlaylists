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

public class SmartPlaylistCardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int VIEW_TYPE_CARD = 0;
    private static final int VIEW_TYPE_ADD = 1;

    private final List<SmartPlaylist> playlists;
    private final OnPlaylistClickListener playlistListener;
    private final Runnable addListener;

    public interface OnPlaylistClickListener {
        void onPlaylistClicked(SmartPlaylist playlist);
    }

    public SmartPlaylistCardAdapter(List<SmartPlaylist> playlists,
                                    OnPlaylistClickListener playlistListener,
                                    Runnable addListener) {
        this.playlists = playlists;
        this.playlistListener = playlistListener;
        this.addListener = addListener;
    }

    @Override
    public int getItemViewType(int position) {
        return position < playlists.size() ? VIEW_TYPE_CARD : VIEW_TYPE_ADD;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_ADD) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_smart_playlist_add_card, parent, false);
            return new AddCardViewHolder(view);
        }
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_smart_playlist_card, parent, false);
        return new CardViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof AddCardViewHolder) {
            holder.itemView.setOnClickListener(v -> addListener.run());
        } else {
            SmartPlaylist playlist = playlists.get(position);
            CardViewHolder cardHolder = (CardViewHolder) holder;
            cardHolder.nameView.setText(playlist.getName());
            cardHolder.countView.setText(
                    holder.itemView.getContext().getResources()
                            .getQuantityString(R.plurals.smart_queue_n_episodes_plural,
                                    playlist.getEpisodeCount(), playlist.getEpisodeCount()));
            holder.itemView.setOnClickListener(v -> playlistListener.onPlaylistClicked(playlist));
        }
    }

    @Override
    public int getItemCount() {
        return playlists.size() + 1; // +1 for add card
    }

    static class CardViewHolder extends RecyclerView.ViewHolder {
        TextView nameView;
        TextView countView;

        CardViewHolder(@NonNull View itemView) {
            super(itemView);
            nameView = itemView.findViewById(R.id.smart_playlist_card_name);
            countView = itemView.findViewById(R.id.smart_playlist_card_count);
        }
    }

    static class AddCardViewHolder extends RecyclerView.ViewHolder {
        AddCardViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }
}

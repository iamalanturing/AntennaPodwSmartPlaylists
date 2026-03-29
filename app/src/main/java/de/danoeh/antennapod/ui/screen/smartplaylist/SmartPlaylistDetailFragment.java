package de.danoeh.antennapod.ui.screen.smartplaylist;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.event.FeedListUpdateEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.playback.service.PlaybackServiceStarter;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.ui.episodeslist.EpisodeItemListAdapter;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

public class SmartPlaylistDetailFragment extends Fragment {
    private static final String TAG = "SmartPlaylistDetail";
    private static final String ARG_PLAYLIST_ID = "playlist_id";
    private static final String ARG_PLAYLIST_NAME = "playlist_name";
    private static final String ARG_AUTO_PLAY = "auto_play";

    private long playlistId;
    private boolean autoPlay;
    private boolean hasAutoPlayed;
    private SmartPlaylist playlist;
    private EpisodeItemListAdapter episodeAdapter;
    private Disposable disposable;

    private RecyclerView recyclerView;
    private TextView emptyLabel;
    private SwitchMaterial autoRegenerateSwitch;
    private MaterialToolbar toolbar;

    public static SmartPlaylistDetailFragment newInstance(long playlistId, String name, boolean autoPlay) {
        SmartPlaylistDetailFragment fragment = new SmartPlaylistDetailFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_PLAYLIST_ID, playlistId);
        args.putString(ARG_PLAYLIST_NAME, name);
        args.putBoolean(ARG_AUTO_PLAY, autoPlay);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_smart_playlist_detail, container, false);

        playlistId = getArguments() != null ? getArguments().getLong(ARG_PLAYLIST_ID) : 0;
        autoPlay = getArguments() != null && getArguments().getBoolean(ARG_AUTO_PLAY, false);
        String playlistName = getArguments() != null ? getArguments().getString(ARG_PLAYLIST_NAME, "") : "";

        toolbar = view.findViewById(R.id.toolbar);
        toolbar.setTitle(playlistName);
        toolbar.setNavigationOnClickListener(v -> {
            if (getActivity() != null) {
                getActivity().onBackPressed();
            }
        });
        toolbar.inflateMenu(R.menu.smart_playlist_detail);
        toolbar.setOnMenuItemClickListener(this::onMenuItemClick);

        autoRegenerateSwitch = view.findViewById(R.id.autoRegenerateSwitch);
        autoRegenerateSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (playlist != null) {
                playlist.setAutoRegenerate(isChecked);
                DBWriter.updateSmartPlaylist(playlist);
            }
        });

        recyclerView = view.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        episodeAdapter = new EpisodeItemListAdapter((MainActivity) getActivity());
        recyclerView.setAdapter(episodeAdapter);

        emptyLabel = view.findViewById(R.id.emptyLabel);

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        loadPlaylist();
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
        if (disposable != null) {
            disposable.dispose();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFeedListChanged(FeedListUpdateEvent event) {
        loadPlaylist();
    }

    private boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_edit_playlist) {
            SmartPlaylistEditFragment editFragment = SmartPlaylistEditFragment.newInstance(playlistId);
            ((MainActivity) requireActivity()).loadChildFragment(editFragment);
            return true;
        } else if (id == R.id.action_regenerate_playlist) {
            if (playlist != null) {
                DBWriter.generateSmartPlaylist(playlist);
                Toast.makeText(getContext(), R.string.smart_playlist_regenerate, Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (id == R.id.action_delete_playlist) {
            confirmDelete();
            return true;
        }
        return false;
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_smart_playlist)
                .setMessage(R.string.delete_smart_playlist_confirm)
                .setPositiveButton(R.string.delete_label, (dialog, which) -> {
                    DBWriter.deleteSmartPlaylist(playlistId);
                    Toast.makeText(getContext(), R.string.smart_playlist_deleted, Toast.LENGTH_SHORT).show();
                    if (getActivity() != null) {
                        getActivity().onBackPressed();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startPlayback(List<FeedItem> episodes) {
        // Find an episode with a saved position (in-progress), or fall back to the first episode
        FeedItem toPlay = null;
        for (FeedItem item : episodes) {
            if (item.getMedia() != null && item.getMedia().getPosition() > 0) {
                toPlay = item;
                break;
            }
        }
        if (toPlay == null) {
            toPlay = episodes.get(0);
        }

        FeedMedia media = toPlay.getMedia();
        if (media == null) {
            return;
        }

        boolean shouldStream = !media.isDownloaded();
        new PlaybackServiceStarter(requireContext(), media)
                .callEvenIfRunning(true)
                .shouldStreamThisTime(shouldStream)
                .start();
    }

    private void loadPlaylist() {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Observable.fromCallable(() -> {
            SmartPlaylist p = DBReader.getSmartPlaylist(playlistId);
            List<FeedItem> episodes = DBReader.getSmartPlaylistEpisodes(playlistId);
            return new Object[]{p, episodes};
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    playlist = (SmartPlaylist) result[0];
                    @SuppressWarnings("unchecked")
                    List<FeedItem> episodes = (List<FeedItem>) result[1];

                    if (playlist != null) {
                        toolbar.setTitle(playlist.getName());
                        autoRegenerateSwitch.setChecked(playlist.isAutoRegenerate());
                    }

                    if (episodes == null || episodes.isEmpty()) {
                        emptyLabel.setVisibility(View.VISIBLE);
                        recyclerView.setVisibility(View.GONE);
                    } else {
                        emptyLabel.setVisibility(View.GONE);
                        recyclerView.setVisibility(View.VISIBLE);
                        episodeAdapter.updateItems(episodes);

                        if (autoPlay && !hasAutoPlayed) {
                            hasAutoPlayed = true;
                            startPlayback(episodes);
                        }
                    }
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }
}

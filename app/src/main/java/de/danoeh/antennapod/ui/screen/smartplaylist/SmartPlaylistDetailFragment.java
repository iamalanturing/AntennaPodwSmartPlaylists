package de.danoeh.antennapod.ui.screen.smartplaylist;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.actionbutton.ItemActionButton;
import de.danoeh.antennapod.actionbutton.PlayActionButton;
import de.danoeh.antennapod.actionbutton.PlayLocalActionButton;
import de.danoeh.antennapod.actionbutton.StreamActionButton;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.ui.common.ThemeUtils;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.event.SmartPlaylistEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.playback.base.BuildConfig;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.playback.service.PlaybackServiceStarter;
import de.danoeh.antennapod.playback.service.PlaybackStatus;
import de.danoeh.antennapod.ui.appstartintent.MediaButtonStarter;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.ui.episodeslist.EpisodeItemListAdapter;
import de.danoeh.antennapod.ui.episodeslist.EpisodeItemViewHolder;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SmartPlaylistDetailFragment extends Fragment {
    public static final String TAG = "SmartPlaylistDetailFrag";
    private static final String ARG_PLAYLIST_ID = "playlistId";

    private long playlistId;
    private SmartPlaylist playlist;
    private List<FeedItem> episodes = new ArrayList<>();
    private EpisodeItemListAdapter episodeAdapter;
    private Disposable disposable;
    private TextView emptyView;
    private Button playButton;

    public static SmartPlaylistDetailFragment newInstance(long playlistId) {
        SmartPlaylistDetailFragment fragment = new SmartPlaylistDetailFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_PLAYLIST_ID, playlistId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        playlistId = requireArguments().getLong(ARG_PLAYLIST_ID);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_smart_playlist_detail, container, false);

        Toolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.smart_playlist_detail);
        toolbar.setNavigationIcon(ThemeUtils.getDrawableFromAttr(requireContext(), R.attr.homeAsUpIndicator));
        toolbar.setNavigationOnClickListener(v -> requireActivity().onBackPressed());
        toolbar.setOnMenuItemClickListener(this::onMenuItemClick);

        SwitchCompat autoRebuildSwitch = view.findViewById(R.id.auto_rebuild_switch);
        autoRebuildSwitch.setOnCheckedChangeListener((CompoundButton btn, boolean checked) -> {
            if (playlist != null) {
                playlist.setAutoRegenerate(checked);
                DBWriter.updateSmartPlaylist(playlist, requireContext());
            }
        });

        RecyclerView recyclerView = view.findViewById(R.id.episodes_recycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        episodeAdapter = new EpisodeItemListAdapter((MainActivity) requireActivity()) {
            @Override
            public boolean onContextItemSelected(@NonNull MenuItem item) {
                return super.onContextItemSelected(item);
            }

            @Override
            protected void afterBindViewHolder(EpisodeItemViewHolder holder, int pos) {
                super.afterBindViewHolder(holder, pos);
                if (inActionMode() || pos >= episodes.size()) {
                    return;
                }
                FeedItem episode = episodes.get(pos);
                if (episode.getMedia() == null) {
                    return;
                }
                ItemActionButton action = ItemActionButton.forItem(episode);
                if (action instanceof PlayActionButton || action instanceof PlayLocalActionButton
                        || action instanceof StreamActionButton) {
                    holder.secondaryActionButton.setOnClickListener(v -> {
                        PlaybackPreferences.writeActiveSmartQueue(playlistId, episode.getMedia().getId());
                        action.onClick(requireContext());
                    });
                }
            }
        };
        episodeAdapter.setDummyViews(3);
        recyclerView.setAdapter(episodeAdapter);

        emptyView = view.findViewById(R.id.empty_view);

        playButton = view.findViewById(R.id.play_button);
        playButton.setOnClickListener(v -> {
            if (playingEpisode() != null) {
                pausePlayback();
            } else {
                startPlayback();
            }
        });
        updatePlayButton();

        loadData(view);
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        updatePlayButton();
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusChanged(PlayerStatusEvent event) {
        updatePlayButton();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSmartPlaylistChanged(SmartPlaylistEvent event) {
        if (event.affects(playlistId)) {
            loadData();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFeedItemsChanged(FeedItemEvent event) {
        loadData();
    }

    private FeedItem playingEpisode() {
        for (FeedItem ep : episodes) {
            if (ep.getMedia() != null && PlaybackStatus.isCurrentlyPlaying(ep.getMedia())) {
                return ep;
            }
        }
        return null;
    }

    private void updatePlayButton() {
        if (playButton != null) {
            playButton.setText(playingEpisode() != null ? R.string.pause_label : R.string.play_label);
        }
    }

    private void pausePlayback() {
        if (BuildConfig.USE_MEDIA3_PLAYBACK_SERVICE) {
            PlaybackController.bindToMedia3Service(requireContext(), controller -> controller.pause());
            return;
        }
        requireContext().sendBroadcast(
                MediaButtonStarter.createIntent(requireContext(), KeyEvent.KEYCODE_MEDIA_PAUSE));
    }

    private void loadData() {
        View view = getView();
        if (view != null) {
            loadData(view);
        }
    }

    private void loadData(View view) {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Observable.fromCallable(() -> {
            SmartPlaylist pl = DBReader.getSmartPlaylist(playlistId);
            List<FeedItem> eps = DBReader.getSmartPlaylistEpisodes(playlistId);
            return new Object[]{pl, eps};
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    playlist = (SmartPlaylist) result[0];
                    if (playlist == null) {
                        return;
                    }
                    view.<Toolbar>findViewById(R.id.toolbar).setTitle(playlist.getName());
                    SwitchCompat sw = view.findViewById(R.id.auto_rebuild_switch);
                    sw.setChecked(playlist.isAutoRegenerate());

                    @SuppressWarnings("unchecked")
                    List<FeedItem> eps = (List<FeedItem>) result[1];
                    episodes.clear();
                    episodes.addAll(eps);
                    episodeAdapter.setDummyViews(0);
                    episodeAdapter.updateItems(episodes);
                    emptyView.setVisibility(episodes.isEmpty() ? View.VISIBLE : View.GONE);
                    updatePlayButton();
                }, error -> { });
    }

    private boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.action_edit_smart_playlist) {
            ((MainActivity) requireActivity()).loadChildFragment(
                    SmartPlaylistEditFragment.newInstance(playlistId));
            return true;
        } else if (item.getItemId() == R.id.action_regenerate_smart_playlist) {
            regenerate();
            return true;
        } else if (item.getItemId() == R.id.action_delete_smart_playlist) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.smart_queue_delete_confirm)
                    .setPositiveButton(R.string.delete_label, (d, w) -> {
                        DBWriter.deleteSmartPlaylist(playlistId);
                        requireActivity().onBackPressed();
                    })
                    .setNegativeButton(R.string.cancel_label, null)
                    .show();
            return true;
        }
        return false;
    }

    private void regenerate() {
        if (playlist == null) {
            return;
        }
        // Reloading is driven by the SmartPlaylistEvent this posts once the rebuild finishes
        DBWriter.generateSmartPlaylist(playlist);
    }

    private void startPlayback() {
        if (episodes.isEmpty()) {
            return;
        }
        // Find first in-progress episode, otherwise the first one that has not been played yet
        FeedItem startItem = null;
        for (FeedItem ep : episodes) {
            if (ep.getMedia() == null || ep.isPlayed()) {
                continue;
            }
            if (ep.getMedia().getPosition() > 0) {
                startItem = ep;
                break;
            }
            if (startItem == null) {
                startItem = ep;
            }
        }
        if (startItem == null) {
            // Everything has been played already: start over at the top of the queue
            startItem = episodes.get(0);
            if (startItem.getMedia() == null) {
                return;
            }
        }
        FeedMedia media = startItem.getMedia();
        if (media.localFileAvailable() && !media.fileExists()) {
            media.setDownloaded(false, 0);
            media.setLocalFileUrl(null);
            DBWriter.setMediaDownloadInformation(media);
            EventBus.getDefault().post(new FeedItemEvent(Collections.singletonList(startItem), false));
            EventBus.getDefault().post(new MessageEvent(getString(R.string.error_file_not_found)));
            return;
        }
        // FORK: Hand the queue ownership of this episode so the playback service keeps advancing
        // within it for as long as it is the one playing
        PlaybackPreferences.writeActiveSmartQueue(playlistId, media.getId());
        new PlaybackServiceStarter(requireContext(), media)
                .callEvenIfRunning(true)
                .start();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (disposable != null) {
            disposable.dispose();
        }
    }
}

package de.danoeh.antennapod.ui.screen.smartplaylist;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
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
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.ui.common.ThemeUtils;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.playback.service.PlaybackServiceStarter;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.ui.episodeslist.EpisodeItemListAdapter;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;

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
        };
        episodeAdapter.setDummyViews(3);
        recyclerView.setAdapter(episodeAdapter);

        emptyView = view.findViewById(R.id.empty_view);

        view.findViewById(R.id.play_button).setOnClickListener(v -> startPlayback());

        loadData(view);
        return view;
    }

    private void loadData(View view) {
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
        disposable = Observable.fromCallable(() -> {
            DBWriter.generateSmartPlaylistSync(playlist);
            return DBReader.getSmartPlaylistEpisodes(playlistId);
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    episodes.clear();
                    episodes.addAll(result);
                    episodeAdapter.updateItems(episodes);
                    emptyView.setVisibility(episodes.isEmpty() ? View.VISIBLE : View.GONE);
                }, error -> { });
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
        // FORK: Set active smart queue so playback service knows to advance within this queue
        PlaybackPreferences.writeActiveSmartQueueId(playlistId);
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

package de.danoeh.antennapod.ui.screen.home.sections;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.event.FeedListUpdateEvent;
import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.ui.screen.home.HomeSection;
import de.danoeh.antennapod.ui.screen.smartplaylist.SmartPlaylistCardAdapter;
import de.danoeh.antennapod.ui.screen.smartplaylist.SmartPlaylistDetailFragment;
import de.danoeh.antennapod.ui.screen.smartplaylist.SmartPlaylistEditFragment;
import de.danoeh.antennapod.ui.screen.smartplaylist.SmartPlaylistListFragment;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class SmartPlaylistsSection extends HomeSection {
    public static final String TAG = "SmartPlaylistsSection";
    private SmartPlaylistCardAdapter cardAdapter;
    private Disposable disposable;
    private List<SmartPlaylist> playlists = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        final View view = super.onCreateView(inflater, container, savedInstanceState);
        cardAdapter = new SmartPlaylistCardAdapter(playlists,
                playlist -> ((MainActivity) requireActivity()).loadChildFragment(
                        SmartPlaylistDetailFragment.newInstance(playlist.getId())),
                () -> ((MainActivity) requireActivity()).loadChildFragment(
                        SmartPlaylistEditFragment.newInstance(0)));

        viewBinding.recyclerView.setLayoutManager(
                new LinearLayoutManager(getContext(), RecyclerView.HORIZONTAL, false));
        viewBinding.recyclerView.setAdapter(cardAdapter);
        loadData();
        return view;
    }

    private void loadData() {
        disposable = Observable.fromCallable(DBReader::getSmartPlaylists)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    playlists.clear();
                    playlists.addAll(result);
                    cardAdapter.notifyDataSetChanged();
                }, error -> { });
    }

    /**
     * Refreshes the cards when the library changes, since a smart queue's contents and episode
     * count are derived from the feeds and episodes present.
     *
     * <p>This also has to exist for the section to work at all: {@link HomeSection#onStart}
     * registers every section with EventBus, and EventBus throws when a subscriber declares no
     * {@code @Subscribe} method. Without one, opening the home screen crashes the app.
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFeedListChanged(FeedListUpdateEvent event) {
        loadData();
    }

    @Override
    protected String getSectionTitle() {
        return getString(R.string.smart_queue_home_section_title);
    }

    @Override
    protected String getMoreLinkTitle() {
        return getString(R.string.smart_queue_home_more);
    }

    @Override
    protected void handleMoreClick() {
        ((MainActivity) requireActivity()).loadChildFragment(new SmartPlaylistListFragment());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (disposable != null) {
            disposable.dispose();
        }
    }
}

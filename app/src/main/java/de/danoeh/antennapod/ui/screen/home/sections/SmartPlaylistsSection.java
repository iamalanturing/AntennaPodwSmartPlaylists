package de.danoeh.antennapod.ui.screen.home.sections;

import android.os.Bundle;
import android.util.Log;
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

import java.util.List;

public class SmartPlaylistsSection extends HomeSection {
    public static final String TAG = "SmartPlaylistsSection";
    private SmartPlaylistCardAdapter adapter;
    private Disposable disposable;
    private boolean hasPlaylists = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        final View view = super.onCreateView(inflater, container, savedInstanceState);
        viewBinding.recyclerView.setLayoutManager(
                new LinearLayoutManager(getActivity(), RecyclerView.HORIZONTAL, false));
        adapter = new SmartPlaylistCardAdapter();
        adapter.setOnPlaylistClickListener(playlist -> {
            SmartPlaylistDetailFragment fragment = SmartPlaylistDetailFragment.newInstance(
                    playlist.getId(), playlist.getName(), true);
            ((MainActivity) requireActivity()).loadChildFragment(fragment);
        });
        adapter.setOnCreateClickListener(() -> {
            SmartPlaylistEditFragment fragment = SmartPlaylistEditFragment.newInstance(0);
            ((MainActivity) requireActivity()).loadChildFragment(fragment);
        });
        viewBinding.recyclerView.setAdapter(adapter);
        int paddingHorizontal = (int) (12 * getResources().getDisplayMetrics().density);
        viewBinding.recyclerView.setPadding(paddingHorizontal, 0, paddingHorizontal, 0);
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        loadItems();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (disposable != null) {
            disposable.dispose();
        }
    }

    @Override
    protected void handleMoreClick() {
        if (hasPlaylists) {
            ((MainActivity) requireActivity()).loadChildFragment(new SmartPlaylistListFragment());
        } else {
            SmartPlaylistEditFragment fragment = SmartPlaylistEditFragment.newInstance(0);
            ((MainActivity) requireActivity()).loadChildFragment(fragment);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFeedListChanged(FeedListUpdateEvent event) {
        loadItems();
    }

    @Override
    protected String getSectionTitle() {
        return getString(R.string.home_smart_playlists_title);
    }

    @Override
    protected String getMoreLinkTitle() {
        return getString(R.string.smart_playlists_label_more);
    }

    private void loadItems() {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Observable.fromCallable(DBReader::getSmartPlaylists)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(playlists -> {
                    hasPlaylists = !playlists.isEmpty();
                    adapter.updateData(playlists);
                    viewBinding.emptyLabel.setVisibility(
                            playlists.isEmpty() ? View.VISIBLE : View.GONE);
                    viewBinding.emptyLabel.setText(R.string.home_smart_playlists_empty_text);
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }
}

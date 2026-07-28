package de.danoeh.antennapod.ui.screen.smartplaylist;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.event.SmartPlaylistEvent;
import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.ui.view.EmptyViewHandler;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class SmartPlaylistListFragment extends Fragment {
    public static final String TAG = "SmartPlaylistListFragment";

    private RecyclerView recyclerView;
    private SmartPlaylistListAdapter adapter;
    private EmptyViewHandler emptyView;
    private Disposable disposable;
    private List<SmartPlaylist> playlists = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_smart_playlist_list, container, false);

        Toolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> requireActivity().onBackPressed());

        recyclerView = view.findViewById(R.id.smart_playlist_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        adapter = new SmartPlaylistListAdapter(playlists, playlist -> {
            ((MainActivity) requireActivity()).loadChildFragment(
                    SmartPlaylistDetailFragment.newInstance(playlist.getId()));
        });
        recyclerView.setAdapter(adapter);

        emptyView = new EmptyViewHandler(getContext());
        emptyView.setIcon(R.drawable.ic_playlist_play);
        emptyView.setTitle(R.string.smart_queue_list_empty_title);
        emptyView.setMessage(R.string.smart_queue_list_empty_message);
        emptyView.attachToRecyclerView(recyclerView);

        FloatingActionButton fab = view.findViewById(R.id.smart_playlist_fab);
        fab.setOnClickListener(v ->
                ((MainActivity) requireActivity()).loadChildFragment(
                        SmartPlaylistEditFragment.newInstance(0)));

        loadPlaylists();
        return view;
    }

    private void loadPlaylists() {
        // Keep the empty state hidden while the read is in flight, so it does not flash up
        // in front of queues that are about to arrive
        emptyView.hide();
        disposable = Observable.fromCallable(DBReader::getSmartPlaylists)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    playlists.clear();
                    playlists.addAll(result);
                    adapter.notifyDataSetChanged();
                }, error -> { });
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSmartPlaylistChanged(SmartPlaylistEvent event) {
        loadPlaylists();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        // loadChildFragment hides (does not destroy) this fragment, so onCreateView does not re-run
        // when returning from create/edit/detail. Reload here so new or deleted playlists are reflected.
        if (!hidden) {
            loadPlaylists();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (disposable != null) {
            disposable.dispose();
        }
    }
}

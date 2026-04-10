package de.danoeh.antennapod.ui.screen.smartplaylist;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.event.FeedListUpdateEvent;
import de.danoeh.antennapod.storage.database.DBReader;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class SmartPlaylistListFragment extends Fragment {
    private static final String TAG = "SmartPlaylistList";

    private SmartPlaylistListAdapter adapter;
    private Disposable disposable;
    private RecyclerView recyclerView;
    private TextView emptyLabel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_smart_playlist_list, container, false);

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> {
            if (getActivity() != null) {
                getActivity().onBackPressed();
            }
        });

        recyclerView = view.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new SmartPlaylistListAdapter();
        adapter.setOnPlaylistClickListener(playlist -> {
            SmartPlaylistDetailFragment fragment = SmartPlaylistDetailFragment.newInstance(
                    playlist.getId(), playlist.getName(), false);
            ((MainActivity) requireActivity()).loadChildFragment(fragment);
        });
        recyclerView.setAdapter(adapter);

        emptyLabel = view.findViewById(R.id.emptyLabel);

        FloatingActionButton fab = view.findViewById(R.id.fab);
        fab.setOnClickListener(v -> {
            SmartPlaylistEditFragment fragment = SmartPlaylistEditFragment.newInstance(0);
            ((MainActivity) requireActivity()).loadChildFragment(fragment);
        });

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        loadPlaylists();
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
        loadPlaylists();
    }

    private void loadPlaylists() {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Observable.fromCallable(DBReader::getSmartPlaylists)
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(playlists -> {
                    adapter.updateData(playlists);
                    emptyLabel.setVisibility(playlists.isEmpty() ? View.VISIBLE : View.GONE);
                    recyclerView.setVisibility(playlists.isEmpty() ? View.GONE : View.VISIBLE);
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }
}

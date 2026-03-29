package de.danoeh.antennapod.ui.screen.smartplaylist;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SmartPlaylistEditFragment extends Fragment {
    private static final String TAG = "SmartPlaylistEdit";
    private static final String ARG_PLAYLIST_ID = "playlist_id";

    private long playlistId;
    private SmartPlaylist playlist;
    private SmartPlaylistRuleAdapter ruleAdapter;
    private Disposable disposable;

    private TextInputEditText playlistNameEdit;
    private SwitchMaterial autoRegenerateSwitch;
    private MaterialButton generateButton;

    public static SmartPlaylistEditFragment newInstance(long playlistId) {
        SmartPlaylistEditFragment fragment = new SmartPlaylistEditFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_PLAYLIST_ID, playlistId);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_smart_playlist_edit, container, false);

        playlistId = getArguments() != null ? getArguments().getLong(ARG_PLAYLIST_ID, 0) : 0;

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setTitle(playlistId == 0
                ? getString(R.string.create_smart_playlist)
                : getString(R.string.edit_smart_playlist));
        toolbar.setNavigationOnClickListener(v -> {
            if (getActivity() != null) {
                getActivity().onBackPressed();
            }
        });

        playlistNameEdit = view.findViewById(R.id.playlistNameEdit);
        autoRegenerateSwitch = view.findViewById(R.id.autoRegenerateSwitch);

        RecyclerView rulesRecyclerView = view.findViewById(R.id.rulesRecyclerView);
        rulesRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        ruleAdapter = new SmartPlaylistRuleAdapter();
        ruleAdapter.setOnRuleActionListener(new SmartPlaylistRuleAdapter.OnRuleActionListener() {
            @Override
            public void onEditRule(int position, SmartPlaylistRule rule) {
                showRuleEditDialog(position, rule);
            }

            @Override
            public void onDeleteRule(int position) {
                ruleAdapter.removeRule(position);
            }
        });
        rulesRecyclerView.setAdapter(ruleAdapter);

        // Drag to reorder
        ItemTouchHelper touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                ruleAdapter.swapRules(viewHolder.getAdapterPosition(), target.getAdapterPosition());
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // No swipe
            }
        });
        touchHelper.attachToRecyclerView(rulesRecyclerView);

        MaterialButton addRuleButton = view.findViewById(R.id.addRuleButton);
        addRuleButton.setOnClickListener(v -> {
            SmartPlaylistRule newRule = new SmartPlaylistRule();
            showRuleEditDialog(-1, newRule);
        });

        generateButton = view.findViewById(R.id.generateButton);
        generateButton.setText(playlistId == 0
                ? getString(R.string.smart_playlist_generate)
                : getString(R.string.smart_playlist_save));
        generateButton.setOnClickListener(v -> savePlaylist());

        loadFeedNames();

        if (playlistId > 0) {
            loadPlaylist();
        }

        return view;
    }

    @Override
    public void onStop() {
        super.onStop();
        if (disposable != null) {
            disposable.dispose();
        }
    }

    private void loadFeedNames() {
        Observable.fromCallable(() -> {
            List<Feed> feeds = DBReader.getFeedList();
            Map<Long, String> map = new HashMap<>();
            for (Feed feed : feeds) {
                map.put(feed.getId(), feed.getTitle() != null ? feed.getTitle() : "Unknown");
            }
            return map;
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(map -> ruleAdapter.setFeedNameMap(map),
                        error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private void loadPlaylist() {
        disposable = Observable.fromCallable(() -> DBReader.getSmartPlaylist(playlistId))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (result != null) {
                        playlist = result;
                        playlistNameEdit.setText(playlist.getName());
                        autoRegenerateSwitch.setChecked(playlist.isAutoRegenerate());
                        if (playlist.getRules() != null) {
                            ruleAdapter.setRules(playlist.getRules());
                        }
                    }
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private void showRuleEditDialog(int position, SmartPlaylistRule rule) {
        SmartPlaylistRuleEditDialog dialog = SmartPlaylistRuleEditDialog.newInstance(rule);
        dialog.setOnRuleSavedListener(savedRule -> {
            if (position < 0) {
                ruleAdapter.addRule(savedRule);
            } else {
                ruleAdapter.updateRule(position, savedRule);
            }
        });
        dialog.show(getChildFragmentManager(), "RuleEditDialog");
    }

    private void savePlaylist() {
        String name = playlistNameEdit.getText() != null
                ? playlistNameEdit.getText().toString().trim() : "";
        if (name.isEmpty()) {
            playlistNameEdit.setError(getString(R.string.smart_playlist_name));
            return;
        }

        if (playlist == null) {
            playlist = new SmartPlaylist();
        }
        playlist.setName(name);
        playlist.setAutoRegenerate(autoRegenerateSwitch.isChecked());
        playlist.setRules(ruleAdapter.getRules());

        generateButton.setEnabled(false);

        if (playlistId == 0) {
            // New playlist: create then generate
            Observable.fromCallable(() -> {
                DBWriter.createSmartPlaylist(playlist).get();
                DBWriter.generateSmartPlaylist(playlist).get();
                return playlist;
            })
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(result -> {
                        Toast.makeText(getContext(), R.string.smart_playlist_created, Toast.LENGTH_SHORT).show();
                        if (getActivity() != null) {
                            getActivity().onBackPressed();
                        }
                    }, error -> {
                        Log.e(TAG, Log.getStackTraceString(error));
                        generateButton.setEnabled(true);
                    });
        } else {
            // Existing playlist: update then regenerate
            Observable.fromCallable(() -> {
                DBWriter.updateSmartPlaylist(playlist).get();
                DBWriter.generateSmartPlaylist(playlist).get();
                return playlist;
            })
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(result -> {
                        Toast.makeText(getContext(), R.string.smart_playlist_updated, Toast.LENGTH_SHORT).show();
                        if (getActivity() != null) {
                            getActivity().onBackPressed();
                        }
                    }, error -> {
                        Log.e(TAG, Log.getStackTraceString(error));
                        generateButton.setEnabled(true);
                    });
        }
    }
}

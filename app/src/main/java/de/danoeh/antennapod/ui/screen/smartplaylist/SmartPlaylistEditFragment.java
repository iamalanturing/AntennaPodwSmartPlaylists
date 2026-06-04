package de.danoeh.antennapod.ui.screen.smartplaylist;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.ui.common.ThemeUtils;
import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.ArrayList;

public class SmartPlaylistEditFragment extends Fragment {
    public static final String TAG = "SmartPlaylistEditFrag";
    private static final String ARG_PLAYLIST_ID = "playlistId";

    private long playlistId;
    private SmartPlaylist playlist;
    private SmartPlaylistRuleAdapter ruleAdapter;
    private Disposable disposable;
    private EditText nameEdit;

    public static SmartPlaylistEditFragment newInstance(long playlistId) {
        SmartPlaylistEditFragment fragment = new SmartPlaylistEditFragment();
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
        View view = inflater.inflate(R.layout.fragment_smart_playlist_edit, container, false);

        Toolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setTitle(playlistId == 0 ? R.string.smart_queue_create : R.string.smart_queue_edit);
        toolbar.setNavigationIcon(ThemeUtils.getDrawableFromAttr(requireContext(), R.attr.homeAsUpIndicator));
        toolbar.setNavigationOnClickListener(v -> requireActivity().onBackPressed());
        toolbar.inflateMenu(R.menu.smart_playlist_edit);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_save_smart_playlist) {
                savePlaylist();
                return true;
            }
            return false;
        });

        nameEdit = view.findViewById(R.id.smart_playlist_name_edit);
        nameEdit.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable s) {
                if (playlist != null) {
                    playlist.setName(s.toString().trim());
                }
            }
        });

        RecyclerView rulesRecycler = view.findViewById(R.id.rules_recycler);
        rulesRecycler.setLayoutManager(new LinearLayoutManager(getContext()));

        playlist = new SmartPlaylist();
        playlist.getRules().add(new SmartPlaylistRule());
        ruleAdapter = new SmartPlaylistRuleAdapter(playlist.getRules(), rule ->
                SmartPlaylistRuleEditDialog.show(requireContext(), rule, () ->
                        ruleAdapter.notifyDataSetChanged()));
        rulesRecycler.setAdapter(ruleAdapter);

        view.findViewById(R.id.add_rule_button).setOnClickListener(v -> {
            playlist.getRules().add(new SmartPlaylistRule());
            ruleAdapter.notifyItemInserted(playlist.getRules().size() - 1);
        });

        if (playlistId != 0) {
            loadExistingPlaylist();
        }
        return view;
    }

    private void loadExistingPlaylist() {
        disposable = Observable.fromCallable(() -> DBReader.getSmartPlaylist(playlistId))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (result != null) {
                        playlist = result;
                        nameEdit.setText(playlist.getName());
                        if (ruleAdapter != null) {
                            ruleAdapter.setRules(playlist.getRules());
                        }
                    }
                }, error -> {});
    }

    private void savePlaylist() {
        String name = nameEdit.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), R.string.smart_queue_name_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        playlist.setName(name);

        if (playlistId == 0) {
            DBWriter.createSmartPlaylist(playlist, requireContext());
            DBWriter.generateSmartPlaylist(playlist);
        } else {
            DBWriter.updateSmartPlaylist(playlist, requireContext());
            DBWriter.generateSmartPlaylist(playlist);
        }
        requireActivity().onBackPressed();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (disposable != null) {
            disposable.dispose();
        }
    }
}

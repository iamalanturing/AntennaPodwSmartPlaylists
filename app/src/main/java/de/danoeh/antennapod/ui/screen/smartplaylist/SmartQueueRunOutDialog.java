package de.danoeh.antennapod.ui.screen.smartplaylist;

import android.content.Context;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.storage.database.DBReader;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.ArrayList;
import java.util.List;

/**
 * FORK: the "When this queue runs out" choice -- stop, rebuild, or hand over to another smart queue.
 */
public final class SmartQueueRunOutDialog {
    private SmartQueueRunOutDialog() { }

    public static String describe(Context context, SmartPlaylist playlist) {
        String setting;
        if (playlist.getNextPlaylistId() != 0 && playlist.getNextPlaylistName() != null) {
            setting = context.getString(R.string.smart_queue_run_out_switch_to, playlist.getNextPlaylistName());
        } else if (playlist.isAutoRegenerate()) {
            setting = context.getString(R.string.smart_queue_run_out_rebuild);
        } else {
            setting = context.getString(R.string.smart_queue_run_out_stop);
        }
        return context.getString(R.string.smart_queue_run_out_summary, setting);
    }

    /**
     * Changes the playlist object in memory and calls {@code onChanged}; saving is up to the caller.
     */
    public static void show(Context context, SmartPlaylist playlist, Runnable onChanged) {
        Single.fromCallable(() -> {
            List<SmartPlaylist> others = new ArrayList<>();
            for (SmartPlaylist candidate : DBReader.getSmartPlaylists()) {
                if (candidate.getId() != playlist.getId()) {
                    others.add(candidate);
                }
            }
            return others;
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(others -> showChoices(context, playlist, others, onChanged), error -> { });
    }

    private static void showChoices(Context context, SmartPlaylist playlist, List<SmartPlaylist> others,
                                    Runnable onChanged) {
        List<String> labels = new ArrayList<>();
        labels.add(context.getString(R.string.smart_queue_run_out_stop));
        labels.add(context.getString(R.string.smart_queue_run_out_rebuild));
        if (!others.isEmpty()) {
            labels.add(context.getString(R.string.smart_queue_run_out_switch));
        }
        int checked = 0;
        if (playlist.getNextPlaylistId() != 0 && !others.isEmpty()) {
            checked = 2;
        } else if (playlist.isAutoRegenerate()) {
            checked = 1;
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.smart_queue_run_out_title)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 2) {
                        showTargets(context, playlist, others, onChanged);
                        return;
                    }
                    playlist.setAutoRegenerate(which == 1);
                    playlist.setNextPlaylistId(0);
                    playlist.setNextPlaylistName(null);
                    onChanged.run();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    private static void showTargets(Context context, SmartPlaylist playlist, List<SmartPlaylist> others,
                                    Runnable onChanged) {
        String[] names = new String[others.size()];
        int checked = -1;
        for (int i = 0; i < others.size(); i++) {
            names[i] = others.get(i).getName();
            if (others.get(i).getId() == playlist.getNextPlaylistId()) {
                checked = i;
            }
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.smart_queue_run_out_pick_target)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    dialog.dismiss();
                    playlist.setAutoRegenerate(false);
                    playlist.setNextPlaylistId(others.get(which).getId());
                    playlist.setNextPlaylistName(others.get(which).getName());
                    onChanged.run();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }
}

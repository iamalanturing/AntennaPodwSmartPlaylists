package de.danoeh.antennapod.ui.screen.download;

import android.content.Context;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.net.download.serviceinterface.AutoDownloadManager;
import de.danoeh.antennapod.storage.database.DownloadVerifier;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * FORK: checks that downloaded episodes are really on the device and offers to download the rest.
 */
public final class VerifyDownloadsDialog {
    private static final String TAG = "VerifyDownloadsDialog";

    private VerifyDownloadsDialog() {
    }

    /**
     * @param scope             the episodes to check, loaded off the main thread
     * @param alsoAutoDownload  also run the automatic download algorithm afterwards
     */
    public static void show(Context context, Callable<List<FeedItem>> scope, boolean alsoAutoDownload) {
        AlertDialog progress = new MaterialAlertDialogBuilder(context)
                .setMessage(R.string.verify_downloads_checking)
                .setCancelable(false)
                .show();
        Single.fromCallable(() -> DownloadVerifier.verify(scope.call()))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    progress.dismiss();
                    showResult(context, result, alsoAutoDownload);
                }, error -> {
                    progress.dismiss();
                    Log.e(TAG, "Verifying downloads failed", error);
                    Toast.makeText(context, context.getString(R.string.error_msg_prefix) + " "
                            + error.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    private static void showResult(Context context, DownloadVerifier.Result result, boolean alsoAutoDownload) {
        List<FeedItem> toDownload = result.getToDownload();
        if (toDownload.isEmpty()) {
            if (alsoAutoDownload) {
                AutoDownloadManager.getInstance().autodownloadUndownloadedItems(context);
                Toast.makeText(context, R.string.verify_downloads_auto_only, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(context, context.getString(R.string.verify_downloads_all_present,
                        result.getChecked()), Toast.LENGTH_LONG).show();
            }
            return;
        }
        String message = result.getTotalBytes() > 0
                ? context.getString(R.string.verify_downloads_missing_message, toDownload.size(),
                        Formatter.formatShortFileSize(context, result.getTotalBytes()))
                : context.getString(R.string.verify_downloads_missing_message_no_size, toDownload.size());
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.verify_downloads_missing_title)
                .setMessage(message)
                .setPositiveButton(R.string.verify_downloads_download_action, (dialog, which) -> {
                    int queued = DownloadVerifier.download(context, toDownload);
                    if (alsoAutoDownload) {
                        AutoDownloadManager.getInstance().autodownloadUndownloadedItems(context);
                    }
                    Toast.makeText(context, context.getString(R.string.verify_downloads_started, queued),
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }
}

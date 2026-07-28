package de.danoeh.antennapod.ui.widget;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class SmartQueueWidgetWorker extends Worker {
    private static final String TAG = "SmartQueueWidgetWorker";

    public SmartQueueWidgetWorker(@NonNull final Context context,
                                  @NonNull final WorkerParameters workerParams) {
        super(context, workerParams);
    }

    public static void enqueueWork(final Context context) {
        final OneTimeWorkRequest workRequest =
                new OneTimeWorkRequest.Builder(SmartQueueWidgetWorker.class).build();
        WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, workRequest);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            SmartQueueWidgetUpdater.updateWidgets(getApplicationContext());
        } catch (final Exception e) {
            Log.d(TAG, "Failed to update smart queue widgets: ", e);
            return Result.failure();
        }
        return Result.success();
    }
}

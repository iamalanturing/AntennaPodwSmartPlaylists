package de.danoeh.antennapod.ui.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * A widget bound to one Smart Queue. Unlike {@link PlayerWidget} there can be several of these,
 * one per queue, so everything it shows is stored per {@code appWidgetId}.
 */
public class SmartQueueWidget extends AppWidgetProvider {
    public static final String PREFS_NAME = "SmartQueueWidgetPrefs";
    public static final String KEY_PLAYLIST_ID = "smart_queue_widget_playlist_id";
    public static final String KEY_COLOR = "smart_queue_widget_color";
    public static final String KEY_INITIALS = "smart_queue_widget_initials";
    public static final int DEFAULT_COLOR = 0xff262C31;

    /**
     * A widget's tap targets are pending intents baked into the views the launcher holds, so they
     * only change when the widget is redrawn. Deferring that to a background job means an app
     * update can leave a widget wired to the previous build for an unbounded time — tapping it
     * then does whatever the old code did, or nothing at all if that code is gone. Redraw on a
     * thread of our own so an update takes effect at once.
     */
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        final Context appContext = context.getApplicationContext();
        new Thread(() -> SmartQueueWidgetUpdater.updateWidgets(appContext), "SmartQueueWidgetUpdate").start();
        SmartQueueWidgetWorker.enqueueWork(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        SmartQueueWidgetWorker.enqueueWork(context);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        for (int appWidgetId : appWidgetIds) {
            editor.remove(KEY_PLAYLIST_ID + appWidgetId);
            editor.remove(KEY_COLOR + appWidgetId);
            editor.remove(KEY_INITIALS + appWidgetId);
        }
        editor.apply();
        super.onDeleted(context, appWidgetIds);
    }

    public static long getPlaylistId(Context context, int appWidgetId) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_PLAYLIST_ID + appWidgetId, 0);
    }
}

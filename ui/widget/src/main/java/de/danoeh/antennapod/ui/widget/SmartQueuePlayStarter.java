package de.danoeh.antennapod.ui.widget;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * Builds the intent behind a smart queue widget's play button.
 *
 * <p>Starting a named queue needs the database and the playback service, and {@code :ui:widget}
 * cannot depend on {@code :playback:service} because that module already depends on this one. The
 * receiver is therefore addressed by name, the same way {@code MediaButtonStarter} reaches the
 * playback service from {@code :ui:app-start-intent}.
 */
public abstract class SmartQueuePlayStarter {
    private static final String PLAY_RECEIVER =
            "de.danoeh.antennapod.playback.service.SmartQueueWidgetPlayReceiver";
    public static final String EXTRA_PLAYLIST_ID = "smart_queue_playlist_id";

    public static PendingIntent createPendingIntent(Context context, int widgetId, long playlistId) {
        Intent intent = new Intent()
                .setComponent(new ComponentName(context, PLAY_RECEIVER))
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_PLAYLIST_ID, playlistId);
        // The widget id keeps one widget's button from replacing another's pending intent
        return PendingIntent.getBroadcast(context, widgetId, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}

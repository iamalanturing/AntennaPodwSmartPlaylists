package de.danoeh.antennapod.ui.widget;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/**
 * Builds the intent behind a smart queue widget's play button.
 *
 * <p>This starts the playback service directly, which is the only arrangement that works. Tapping
 * a widget grants a short-lived exemption from the background start restrictions, and it has to be
 * spent on the thing that needs it. Going through a broadcast receiver first — read the database,
 * hop threads, then bind a controller — spends the exemption on the broadcast and asks for playback
 * once it has lapsed, so the button flashes and nothing plays. {@code MediaButtonStarter} reaches
 * the same service the same way, and its buttons have always worked.
 *
 * <p>The service is addressed by name because {@code :ui:widget} cannot depend on
 * {@code :playback:service}: that module already depends on this one.
 */
public abstract class SmartQueuePlayStarter {
    private static final String PLAYBACK_SERVICE =
            "de.danoeh.antennapod.playback.service.Media3PlaybackService";
    public static final String EXTRA_PLAYLIST_ID = "smart_queue_playlist_id";

    public static PendingIntent createPendingIntent(Context context, int widgetId, long playlistId) {
        // Carries a media button as well as the queue id. The working button sends
        // ACTION_MEDIA_BUTTON, and an intent with no action at all appears to be one media3 does
        // not treat as a reason to keep the service alive, so the queue lookup completes into a
        // service that has already gone. The override reads the queue id before delegating.
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(new ComponentName(context, PLAYBACK_SERVICE))
                .putExtra(Intent.EXTRA_KEY_EVENT,
                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                .putExtra(EXTRA_PLAYLIST_ID, playlistId);
        // The widget id keeps one widget's button from replacing another's pending intent
        return PendingIntent.getService(context, widgetId, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}

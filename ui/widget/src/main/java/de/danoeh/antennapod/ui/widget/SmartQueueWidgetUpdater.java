package de.danoeh.antennapod.ui.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import android.util.SizeF;
import android.widget.RemoteViews;

import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.ui.appstartintent.MainActivityStarter;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws every {@link SmartQueueWidget} instance. Must be called from a background thread: it reads
 * the database, and may rebuild an exhausted queue before counting it.
 */
public class SmartQueueWidgetUpdater {
    private static final String TAG = "SmartQueueWidgetUpdater";
    private static final String DETAIL_FRAGMENT_TAG = "SmartPlaylistDetailFrag";
    private static final String DETAIL_FRAGMENT_ARG = "playlistId";
    private static final String LIST_FRAGMENT_TAG = "SmartPlaylistListFragment";
    private static final int MAX_DISPLAYED_COUNT = 99;
    /** Full strength against a little over a third: enough of a gap to read without comparing. */
    private static final int ALPHA_PLAYING = 255;
    private static final int ALPHA_IDLE = 95;
    /**
     * Launchers report a widget's width as roughly {@code 70n - 30} dp, so one cell is 40, two is
     * 110, three is 180. The wide layout needs three: at two cells the name has almost no room
     * left once the count and the button have taken theirs.
     */
    private static final float SMALL_WIDTH_DP = 40f;
    private static final float SMALL_HEIGHT_DP = 40f;
    private static final float WIDE_WIDTH_DP = 180f;

    /**
     * A rebuild posts a SmartPlaylistEvent, which brings us straight back here. Refusing to
     * rebuild a queue that was generated moments ago is what stops that becoming a loop, and it
     * also spares a queue whose rules match nothing from being regenerated on every refresh.
     */
    private static final long REBUILD_MIN_INTERVAL_MS = 5 * 60 * 1000L;

    private SmartQueueWidgetUpdater() {
        // Must not be instantiated
    }

    public static void updateWidgets(Context context) {
        updateWidgets(context, true);
    }

    /**
     * @param healExhaustedQueues whether an exhausted queue may be rebuilt while counting. Redraws
     *     caused by playback starting or stopping pass false: they happen often, they only need to
     *     flip an icon, and rebuilding a queue on each one would put a regenerate in the way of the
     *     episode the user is waiting to hear.
     */
    public static void updateWidgets(Context context, boolean healExhaustedQueues) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] widgetIds = manager.getAppWidgetIds(new ComponentName(context, SmartQueueWidget.class));
        for (int widgetId : widgetIds) {
            try {
                updateWidget(context, manager, widgetId, healExhaustedQueues);
            } catch (Exception e) {
                // One broken widget must not stop the others being drawn
                Log.e(TAG, "Failed to update smart queue widget " + widgetId, e);
            }
        }
    }

    private static void updateWidget(Context context, AppWidgetManager manager, int widgetId,
                                     boolean healExhaustedQueues) {
        long playlistId = SmartQueueWidget.getPlaylistId(context, widgetId);
        SmartPlaylist playlist = playlistId == 0 ? null : DBReader.getSmartPlaylist(playlistId);
        int unplayed = 0;
        if (playlist != null) {
            unplayed = healExhaustedQueues ? countAfterHealing(playlist)
                    : DBReader.getSmartPlaylistUnplayedCount(playlistId);
        }

        manager.updateAppWidget(widgetId,
                buildForAllSizes(context, manager, widgetId, playlist, unplayed));
    }

    /**
     * Hands the launcher one layout per size where the platform supports it, so resizing switches
     * between them immediately. Redrawing in response to a resize event is too late: the widget
     * keeps the layout it was given until the background refresh lands, which is why a widget
     * shrunk to one cell used to keep showing the wide one until it was reconfigured.
     */
    private static RemoteViews buildForAllSizes(Context context, AppWidgetManager manager,
                                                int widgetId, SmartPlaylist playlist, int unplayed) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Map<SizeF, RemoteViews> bySize = new HashMap<>();
            bySize.put(new SizeF(SMALL_WIDTH_DP, SMALL_HEIGHT_DP),
                    buildViews(context, widgetId, playlist, unplayed, true));
            bySize.put(new SizeF(WIDE_WIDTH_DP, SMALL_HEIGHT_DP),
                    buildViews(context, widgetId, playlist, unplayed, false));
            return new RemoteViews(bySize);
        }
        return buildViews(context, widgetId, playlist, unplayed, isSingleCell(manager, widgetId));
    }

    private static RemoteViews buildViews(Context context, int widgetId,
                                          SmartPlaylist playlist, int unplayed, boolean small) {
        RemoteViews views = new RemoteViews(context.getPackageName(),
                small ? R.layout.smart_queue_widget_small : R.layout.smart_queue_widget);

        SharedPreferences prefs = context.getSharedPreferences(
                SmartQueueWidget.PREFS_NAME, Context.MODE_PRIVATE);
        views.setInt(R.id.widgetLayout, "setBackgroundColor",
                prefs.getInt(SmartQueueWidget.KEY_COLOR + widgetId, SmartQueueWidget.DEFAULT_COLOR));

        if (playlist == null) {
            showMissingQueue(context, views, small);
            views.setOnClickPendingIntent(R.id.widgetLayout, perWidgetIntent(context, widgetId,
                    new MainActivityStarter(context).withFragmentLoaded(LIST_FRAGMENT_TAG).getIntent()));
            return views;
        }
        long playlistId = playlist.getId();

        String countText = unplayed > MAX_DISPLAYED_COUNT
                ? MAX_DISPLAYED_COUNT + "+" : String.valueOf(unplayed);
        String episodes = context.getResources().getQuantityString(
                R.plurals.smart_queue_n_episodes_plural, unplayed, unplayed);

        views.setTextViewText(R.id.txtvCount, countText);
        views.setContentDescription(R.id.widgetLayout, playlist.getName() + ", " + episodes);

        // One intent whatever the state. The service decides between starting and pausing, because
        // it knows what is playing right now, whereas this only knows what was true when the widget
        // was last drawn -- and a button that waits for a redraw to learn it should pause will not
        // pause when it is pressed.
        boolean playing = PlaybackPreferences.getActiveSmartQueueId() == playlistId
                && PlaybackPreferences.getCurrentPlayerStatus() == PlaybackPreferences.PLAYER_STATUS_PLAYING;
        PendingIntent play = SmartQueuePlayStarter.createPendingIntent(context, widgetId, playlistId);

        if (small) {
            views.setTextViewText(R.id.txtvInitials, prefs.getString(
                    SmartQueueWidget.KEY_INITIALS + widgetId, deriveInitials(playlist.getName())));
            // At one cell this glyph is the only thing saying which queue is the one playing, and
            // so which one to press to stop it. Brighten it as well as changing it: a 16dp shape
            // at a glance reads more by weight than by outline.
            views.setImageViewResource(R.id.imgvPlayHint,
                    playing ? R.drawable.ic_widget_pause : R.drawable.ic_widget_play);
            views.setInt(R.id.imgvPlayHint, "setImageAlpha", playing ? ALPHA_PLAYING : ALPHA_IDLE);
            views.setContentDescription(R.id.widgetLayout, playlist.getName() + ", " + episodes
                    + ", " + context.getString(playing ? R.string.pause_label : R.string.play_label));
            // No room for a separate button, so the whole face starts the queue
            views.setOnClickPendingIntent(R.id.widgetLayout, play);
        } else {
            views.setTextViewText(R.id.txtvName, playlist.getName());
            views.setTextViewText(R.id.txtvSubtitle, episodes);
            views.setOnClickPendingIntent(R.id.widgetLayout, perWidgetIntent(context, widgetId,
                    new MainActivityStarter(context)
                            .withFragmentLoaded(DETAIL_FRAGMENT_TAG)
                            .withFragmentArgs(DETAIL_FRAGMENT_ARG, playlistId)
                            .getIntent()));
            views.setOnClickPendingIntent(R.id.butPlay, play);
            views.setImageViewResource(R.id.butPlay,
                    playing ? R.drawable.ic_widget_pause : R.drawable.ic_widget_play);
            views.setContentDescription(R.id.butPlay,
                    context.getString(playing ? R.string.pause_label : R.string.play_label));
        }

        return views;
    }

    /**
     * {@code MainActivityStarter.getPendingIntent} uses one fixed request code, so with several
     * queue widgets on screen they would all share a single pending intent and every one of them
     * would open whichever queue was drawn last. The widget id keeps them apart.
     */
    private static PendingIntent perWidgetIntent(Context context, int widgetId, Intent intent) {
        return PendingIntent.getActivity(context, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The queue was deleted while its widget stayed on the home screen. */
    private static void showMissingQueue(Context context, RemoteViews views, boolean small) {
        String missing = context.getString(R.string.smart_queue_widget_missing);
        views.setTextViewText(R.id.txtvCount, "–");
        views.setContentDescription(R.id.widgetLayout, missing);
        if (small) {
            views.setTextViewText(R.id.txtvInitials, "");
        } else {
            views.setTextViewText(R.id.txtvName, missing);
            views.setTextViewText(R.id.txtvSubtitle, "");
        }
    }

    /**
     * Counts what is left to listen to, rebuilding first if the queue is exhausted and set to
     * rebuild itself. Without this the count reaches zero and stays there, because nothing else
     * refills a queue unless playback happens to run off the end of it.
     */
    private static int countAfterHealing(SmartPlaylist playlist) {
        int unplayed = DBReader.getSmartPlaylistUnplayedCount(playlist.getId());
        if (unplayed > 0 || !playlist.isAutoRegenerate()) {
            return unplayed;
        }
        if (PlaybackPreferences.getActiveSmartQueueId() == playlist.getId()) {
            // The playback service rebuilds this one itself when it runs off the end. Doing it
            // here as well would reshuffle the queue under whoever is listening to it.
            return unplayed;
        }
        if (System.currentTimeMillis() - playlist.getGeneratedAt() < REBUILD_MIN_INTERVAL_MS) {
            return unplayed;
        }
        try {
            DBWriter.generateSmartPlaylist(playlist).get();
        } catch (Exception e) {
            Log.e(TAG, "Failed to rebuild exhausted queue " + playlist.getId(), e);
            return unplayed;
        }
        return DBReader.getSmartPlaylistUnplayedCount(playlist.getId());
    }

    /**
     * Two letters taken from the queue name, for where there is no room to print the name. The
     * config screen can override this, because names collide readily once abbreviated.
     */
    public static String deriveInitials(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        StringBuilder initials = new StringBuilder();
        for (String word : trimmed.split("\\s+")) {
            if (!word.isEmpty() && initials.length() < 2) {
                initials.append(Character.toUpperCase(word.charAt(0)));
            }
        }
        if (initials.length() == 1 && trimmed.length() > 1) {
            initials.append(Character.toUpperCase(trimmed.charAt(1)));
        }
        return initials.toString();
    }

    private static boolean isSingleCell(AppWidgetManager manager, int widgetId) {
        int minWidth = manager.getAppWidgetOptions(widgetId)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
        // Must agree with WIDE_WIDTH_DP above. A zero means the host never reported a size, in
        // which case assume the default width rather than the cramped layout.
        return minWidth > 0 && minWidth < WIDE_WIDTH_DP;
    }
}

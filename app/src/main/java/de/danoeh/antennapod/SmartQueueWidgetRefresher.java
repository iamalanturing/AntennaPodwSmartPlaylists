package de.danoeh.antennapod;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.event.SmartPlaylistEvent;
import de.danoeh.antennapod.ui.widget.SmartQueueWidgetUpdater;
import de.danoeh.antennapod.ui.widget.SmartQueueWidgetWorker;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * Keeps Smart Queue widgets in step with the app.
 *
 * <p>A queue's contents change when it is rebuilt, when its rules change and when an episode is
 * finished, and at one cell the play glyph is the only thing saying which queue is the one playing.
 * Nothing else would refresh a widget on any of that. Lives for the life of the application, so the
 * widgets keep up whether or not a screen is open.
 */
public class SmartQueueWidgetRefresher {
    /**
     * Starting playback emits a burst of status events, and each redraw reads the database once per
     * widget. Coalescing them keeps that work off the moment the user is waiting to hear something.
     */
    private static final long STATUS_DEBOUNCE_MS = 300;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusRedraw = this::redrawForStatus;

    public SmartQueueWidgetRefresher(Context context) {
        this.context = context;
    }

    public void register() {
        EventBus.getDefault().register(this);
    }

    /**
     * Contents changed, so the count may be wrong and an exhausted queue may want rebuilding.
     * Goes through the worker because it is not urgent and may do real work.
     */
    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onSmartPlaylistChanged(SmartPlaylistEvent event) {
        SmartQueueWidgetWorker.enqueueWork(context);
    }

    /**
     * Only the play glyph changes, and it needs to change promptly to be worth anything, so this
     * redraws directly rather than waiting on a scheduled job.
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusChanged(PlayerStatusEvent event) {
        handler.removeCallbacks(statusRedraw);
        handler.postDelayed(statusRedraw, STATUS_DEBOUNCE_MS);
    }

    private void redrawForStatus() {
        new Thread(() -> SmartQueueWidgetUpdater.updateWidgets(context, false),
                "SmartQueueWidgetStatus").start();
    }
}

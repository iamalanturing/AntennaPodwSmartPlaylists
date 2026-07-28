package de.danoeh.antennapod;

import android.content.Context;

import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.event.SmartPlaylistEvent;
import de.danoeh.antennapod.ui.widget.SmartQueueWidgetWorker;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * Keeps Smart Queue widgets in step with the app.
 *
 * <p>A queue's contents change when it is rebuilt, when its rules change and when an episode is
 * finished, and the widget's play button depends on what is playing. Nothing else would refresh a
 * widget on those, so its count would drift away from the truth until Android happened to redraw
 * it. Lives for the life of the application, so the widgets update whether or not a screen is open.
 */
public class SmartQueueWidgetRefresher {
    private final Context context;

    public SmartQueueWidgetRefresher(Context context) {
        this.context = context;
    }

    public void register() {
        EventBus.getDefault().register(this);
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onSmartPlaylistChanged(SmartPlaylistEvent event) {
        SmartQueueWidgetWorker.enqueueWork(context);
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onPlayerStatusChanged(PlayerStatusEvent event) {
        SmartQueueWidgetWorker.enqueueWork(context);
    }
}

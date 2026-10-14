package de.danoeh.antennapod.playback.service.internal;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.util.Consumer;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.FlagSet;
import androidx.media3.common.util.UnstableApi;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * FORK: reports the real queue as the player's timeline.
 *
 * <p>The playback service holds one episode in the player at a time, so the timeline Android Auto
 * builds its Now Playing "Queue" list from has one entry. This sits under the service's player and
 * presents the current episode followed by the episodes queued after it, while the wrapped player
 * is left alone. Choosing a row plays that episode through {@code playQueueItem}.
 */
@UnstableApi
public class QueueTimelinePlayer extends ForwardingPlayer {
    private static final String TAG = "QueueTimelinePlayer";
    static final int MAX_QUEUE_ITEMS = 50;

    private final Consumer<String> playQueueItem;
    private final Map<Player.Listener, Player.Listener> listeners = new IdentityHashMap<>();
    private List<MediaItem> queueItems = Collections.emptyList();

    public QueueTimelinePlayer(Player player, Consumer<String> playQueueItem) {
        super(player);
        this.playQueueItem = playQueueItem;
    }

    public void setQueue(List<MediaItem> items) {
        if (sameMediaIds(queueItems, items)) {
            queueItems = items;
            return;
        }
        queueItems = items;
        Player.Events events = new Player.Events(
                new FlagSet.Builder().add(Player.EVENT_TIMELINE_CHANGED).build());
        for (Player.Listener listener : new ArrayList<>(listeners.keySet())) {
            listener.onTimelineChanged(getCurrentTimeline(), Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED);
            listener.onEvents(this, events);
        }
    }

    private static boolean sameMediaIds(List<MediaItem> first, List<MediaItem> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int i = 0; i < first.size(); i++) {
            if (!first.get(i).mediaId.equals(second.get(i).mediaId)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The queue from the current episode onwards, or an empty list when the queue view does not
     * apply (nothing playing, or what is playing is not in the queue).
     */
    private List<MediaItem> visibleItems() {
        MediaItem current = super.getCurrentMediaItem();
        if (current == null || queueItems.isEmpty()) {
            return Collections.emptyList();
        }
        int currentIndex = -1;
        for (int i = 0; i < queueItems.size(); i++) {
            if (queueItems.get(i).mediaId.equals(current.mediaId)) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex < 0) {
            return Collections.emptyList();
        }
        List<MediaItem> visible = new ArrayList<>(
                queueItems.subList(currentIndex, Math.min(queueItems.size(), currentIndex + MAX_QUEUE_ITEMS)));
        visible.set(0, current);
        return visible;
    }

    private boolean queueViewActive() {
        return !visibleItems().isEmpty();
    }

    @Override
    @NonNull
    public Timeline getCurrentTimeline() {
        List<MediaItem> visible = visibleItems();
        return visible.isEmpty() ? super.getCurrentTimeline() : new QueueTimeline(visible);
    }

    @Override
    public int getCurrentMediaItemIndex() {
        return queueViewActive() ? 0 : super.getCurrentMediaItemIndex();
    }

    @Override
    public int getCurrentPeriodIndex() {
        return queueViewActive() ? 0 : super.getCurrentPeriodIndex();
    }

    @Override
    public int getMediaItemCount() {
        List<MediaItem> visible = visibleItems();
        return visible.isEmpty() ? super.getMediaItemCount() : visible.size();
    }

    @Override
    @NonNull
    public MediaItem getMediaItemAt(int index) {
        List<MediaItem> visible = visibleItems();
        return visible.isEmpty() ? super.getMediaItemAt(index) : visible.get(index);
    }

    @Override
    @NonNull
    public Player.Commands getAvailableCommands() {
        return super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_GET_TIMELINE)
                .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                .build();
    }

    @Override
    public boolean isCommandAvailable(int command) {
        return getAvailableCommands().contains(command);
    }

    @Override
    public void seekToDefaultPosition(int mediaItemIndex) {
        seekToQueueRow(mediaItemIndex, C.TIME_UNSET);
    }

    @Override
    public void seekTo(int mediaItemIndex, long positionMs) {
        seekToQueueRow(mediaItemIndex, positionMs);
    }

    private void seekToQueueRow(int index, long positionMs) {
        List<MediaItem> visible = visibleItems();
        if (visible.isEmpty()) {
            if (positionMs == C.TIME_UNSET) {
                super.seekToDefaultPosition(index);
            } else {
                super.seekTo(index, positionMs);
            }
        } else if (index == 0) {
            if (positionMs == C.TIME_UNSET) {
                super.seekToDefaultPosition();
            } else {
                super.seekTo(positionMs);
            }
        } else if (index > 0 && index < visible.size()) {
            playQueueItem.accept(visible.get(index).mediaId);
        }
    }

    @Override
    public void addMediaItem(int index, @NonNull MediaItem mediaItem) {
        if (!ignoredWhileQueueView("addMediaItem")) {
            super.addMediaItem(index, mediaItem);
        }
    }

    @Override
    public void addMediaItems(int index, @NonNull List<MediaItem> mediaItems) {
        if (!ignoredWhileQueueView("addMediaItems")) {
            super.addMediaItems(index, mediaItems);
        }
    }

    @Override
    public void removeMediaItem(int index) {
        if (!ignoredWhileQueueView("removeMediaItem")) {
            super.removeMediaItem(index);
        }
    }

    @Override
    public void removeMediaItems(int fromIndex, int toIndex) {
        if (!ignoredWhileQueueView("removeMediaItems")) {
            super.removeMediaItems(fromIndex, toIndex);
        }
    }

    @Override
    public void moveMediaItem(int currentIndex, int newIndex) {
        if (!ignoredWhileQueueView("moveMediaItem")) {
            super.moveMediaItem(currentIndex, newIndex);
        }
    }

    @Override
    public void moveMediaItems(int fromIndex, int toIndex, int newIndex) {
        if (!ignoredWhileQueueView("moveMediaItems")) {
            super.moveMediaItems(fromIndex, toIndex, newIndex);
        }
    }

    @Override
    public void replaceMediaItem(int index, @NonNull MediaItem mediaItem) {
        if (!ignoredWhileQueueView("replaceMediaItem")) {
            super.replaceMediaItem(index, mediaItem);
        }
    }

    @Override
    public void replaceMediaItems(int fromIndex, int toIndex, @NonNull List<MediaItem> mediaItems) {
        if (!ignoredWhileQueueView("replaceMediaItems")) {
            super.replaceMediaItems(fromIndex, toIndex, mediaItems);
        }
    }

    private boolean ignoredWhileQueueView(String call) {
        if (queueViewActive()) {
            Log.w(TAG, "Ignoring " + call + ": its indices refer to the queue view, not the player");
            return true;
        }
        return false;
    }

    @Override
    public void addListener(@NonNull Player.Listener listener) {
        Player.Listener wrapper = timelineSubstitutingListener(listener);
        listeners.put(listener, wrapper);
        super.addListener(wrapper);
    }

    @Override
    public void removeListener(@NonNull Player.Listener listener) {
        Player.Listener wrapper = listeners.remove(listener);
        super.removeListener(wrapper != null ? wrapper : listener);
    }

    /**
     * Forwards every callback to {@code listener}, handing it the queue view in place of the wrapped
     * player's own timeline. A proxy, so that no callback can be forgotten.
     */
    private Player.Listener timelineSubstitutingListener(Player.Listener listener) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "TimelineSubstitutingListener(" + listener + ")";
                default:
                    break;
            }
            Object[] forwardedArgs = args;
            if ("onTimelineChanged".equals(method.getName()) && args != null && args.length == 2) {
                forwardedArgs = new Object[] {getCurrentTimeline(), args[1]};
            }
            try {
                return method.invoke(listener, forwardedArgs);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        };
        return (Player.Listener) Proxy.newProxyInstance(
                Player.Listener.class.getClassLoader(), new Class<?>[] {Player.Listener.class}, handler);
    }

    private static class QueueTimeline extends Timeline {
        private final List<MediaItem> items;

        QueueTimeline(List<MediaItem> items) {
            this.items = items;
        }

        @Override
        public int getWindowCount() {
            return items.size();
        }

        @Override
        @NonNull
        public Window getWindow(int windowIndex, @NonNull Window window, long defaultPositionProjectionUs) {
            MediaItem item = items.get(windowIndex);
            window.set(item.mediaId, item, /* manifest= */ null,
                    /* presentationStartTimeMs= */ C.TIME_UNSET, /* windowStartTimeMs= */ C.TIME_UNSET,
                    /* elapsedRealtimeEpochOffsetMs= */ C.TIME_UNSET, /* isSeekable= */ true,
                    /* isDynamic= */ false, /* liveConfiguration= */ null, /* defaultPositionUs= */ 0,
                    /* durationUs= */ C.TIME_UNSET, /* firstPeriodIndex= */ windowIndex,
                    /* lastPeriodIndex= */ windowIndex, /* positionInFirstPeriodUs= */ 0);
            return window;
        }

        @Override
        public int getPeriodCount() {
            return items.size();
        }

        @Override
        @NonNull
        public Period getPeriod(int periodIndex, @NonNull Period period, boolean setIds) {
            Object uid = items.get(periodIndex).mediaId;
            period.set(setIds ? uid : null, setIds ? uid : null, /* windowIndex= */ periodIndex,
                    /* durationUs= */ C.TIME_UNSET, /* positionInWindowUs= */ 0);
            return period;
        }

        @Override
        public int getIndexOfPeriod(@NonNull Object uid) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).mediaId.equals(uid)) {
                    return i;
                }
            }
            return C.INDEX_UNSET;
        }

        @Override
        @NonNull
        public Object getUidOfPeriod(int periodIndex) {
            return items.get(periodIndex).mediaId;
        }
    }
}

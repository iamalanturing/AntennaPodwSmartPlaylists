package de.danoeh.antennapod.playback.service.internal;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@UnstableApi
@RunWith(RobolectricTestRunner.class)
public class QueueTimelinePlayerTest {
    private final Player wrapped = mock(Player.class);
    private final MediaItem current = item("5");
    private final List<String> played = new ArrayList<>();
    private QueueTimelinePlayer player;

    @Before
    public void setUp() {
        when(wrapped.getCurrentMediaItem()).thenReturn(current);
        when(wrapped.getCurrentTimeline()).thenReturn(Timeline.EMPTY);
        when(wrapped.getAvailableCommands()).thenReturn(Player.Commands.EMPTY);
        player = new QueueTimelinePlayer(wrapped, played::add);
    }

    private static MediaItem item(String mediaId) {
        return new MediaItem.Builder().setMediaId(mediaId).build();
    }

    private static MediaItem windowItem(Timeline timeline, int index) {
        return timeline.getWindow(index, new Timeline.Window()).mediaItem;
    }

    @Test
    public void emptyQueueLeavesTheWrappedTimelineAlone() {
        assertSame(Timeline.EMPTY, player.getCurrentTimeline());
    }

    @Test
    public void currentEpisodeIsFollowedByTheRestOfTheQueue() {
        player.setQueue(Arrays.asList(item("3"), item("5"), item("7")));

        Timeline timeline = player.getCurrentTimeline();

        assertEquals(2, timeline.getWindowCount());
        assertSame(current, windowItem(timeline, 0));
        assertEquals("7", windowItem(timeline, 1).mediaId);
        assertEquals(0, player.getCurrentMediaItemIndex());
        assertEquals(2, player.getMediaItemCount());
    }

    @Test
    public void somethingNotInTheQueueLeavesTheWrappedTimelineAlone() {
        player.setQueue(Arrays.asList(item("3"), item("7")));

        assertSame(Timeline.EMPTY, player.getCurrentTimeline());
    }

    @Test
    public void choosingALaterRowPlaysThatEpisodeAndTheCurrentRowDoesNot() {
        player.setQueue(Arrays.asList(item("5"), item("7")));

        player.seekToDefaultPosition(1);
        assertEquals(Arrays.asList("7"), played);

        player.seekToDefaultPosition(0);
        verify(wrapped).seekToDefaultPosition();
        assertEquals(1, played.size());
    }

    @Test
    public void aRowOutsideTheListIsIgnored() {
        player.setQueue(Arrays.asList(item("5"), item("7")));

        player.seekToDefaultPosition(9);

        assertTrue(played.isEmpty());
        verify(wrapped, never()).seekToDefaultPosition(9);
    }

    @Test
    public void aLongQueueIsCappedAtFiftyRows() {
        List<MediaItem> queue = new ArrayList<>();
        for (int i = 5; i < 125; i++) {
            queue.add(item(String.valueOf(i)));
        }
        player.setQueue(queue);

        assertEquals(QueueTimelinePlayer.MAX_QUEUE_ITEMS, player.getCurrentTimeline().getWindowCount());
    }

    @Test
    public void listenersSeeTheQueueWhenItChangesAndWhenThePlayerReportsATimeline() {
        List<Timeline> seen = new ArrayList<>();
        Player.Listener listener = new Player.Listener() {
            @Override
            public void onTimelineChanged(Timeline timeline, int reason) {
                seen.add(timeline);
            }
        };
        player.addListener(listener);
        ArgumentCaptor<Player.Listener> registered = ArgumentCaptor.forClass(Player.Listener.class);
        verify(wrapped).addListener(registered.capture());

        player.setQueue(Arrays.asList(item("5"), item("7")));
        assertEquals(1, seen.size());
        assertEquals(2, seen.get(0).getWindowCount());

        registered.getValue().onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED);
        assertEquals(2, seen.size());
        assertEquals(2, seen.get(1).getWindowCount());
    }

    @Test
    public void aRemovedListenerHearsNothingMore() {
        List<Timeline> seen = new ArrayList<>();
        Player.Listener listener = new Player.Listener() {
            @Override
            public void onTimelineChanged(Timeline timeline, int reason) {
                seen.add(timeline);
            }
        };
        player.addListener(listener);
        ArgumentCaptor<Player.Listener> registered = ArgumentCaptor.forClass(Player.Listener.class);
        verify(wrapped).addListener(registered.capture());

        player.removeListener(listener);
        player.setQueue(Arrays.asList(item("5"), item("7")));

        verify(wrapped).removeListener(registered.getValue());
        assertTrue(seen.isEmpty());
    }

    @Test
    public void theQueueCanBeReadAndPickedFrom() {
        Player.Commands commands = player.getAvailableCommands();

        assertTrue(commands.contains(Player.COMMAND_GET_TIMELINE));
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_MEDIA_ITEM));
    }
}

package de.danoeh.antennapod.ui.screen.home;

import de.danoeh.antennapod.ui.screen.home.sections.DownloadsSection;
import de.danoeh.antennapod.ui.screen.home.sections.EpisodesSurpriseSection;
import de.danoeh.antennapod.ui.screen.home.sections.InboxSection;
import de.danoeh.antennapod.ui.screen.home.sections.QueueSection;
import de.danoeh.antennapod.ui.screen.home.sections.SmartPlaylistsSection;
import de.danoeh.antennapod.ui.screen.home.sections.SubscriptionsSection;
import org.greenrobot.eventbus.Subscribe;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertTrue;

/**
 * {@link HomeSection#onStart} registers every section with EventBus, and EventBus throws
 * {@code EventBusException} when a subscriber class declares no {@code @Subscribe} method. A
 * section missing one therefore crashes the app as soon as the home screen opens -- which is
 * exactly what shipped, because nothing exercised it.
 *
 * <p>Nothing about that is visible at compile time, so assert it here instead. Add new
 * HomeSection subclasses to this list.
 */
public class HomeSectionSubscriberTest {

    private static final Class<?>[] SECTIONS = {
        SmartPlaylistsSection.class,
        QueueSection.class,
        InboxSection.class,
        EpisodesSurpriseSection.class,
        SubscriptionsSection.class,
        DownloadsSection.class,
    };

    @Test
    public void everyHomeSectionDeclaresAnEventBusSubscriber() {
        for (Class<?> section : SECTIONS) {
            assertTrue(section.getSimpleName() + " extends HomeSection but is not a HomeSection",
                    HomeSection.class.isAssignableFrom(section));
            assertTrue(section.getSimpleName() + " has no @Subscribe method; HomeSection.onStart"
                            + " registers it with EventBus, which throws without one",
                    hasSubscribeMethod(section));
        }
    }

    private boolean hasSubscribeMethod(Class<?> section) {
        for (Class<?> type = section; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Subscribe.class)) {
                    return true;
                }
            }
        }
        return false;
    }
}

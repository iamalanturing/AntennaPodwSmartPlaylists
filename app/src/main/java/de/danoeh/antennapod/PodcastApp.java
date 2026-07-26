package de.danoeh.antennapod;

import android.app.Application;
import android.content.ComponentName;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.android.material.color.DynamicColors;
import com.google.common.util.concurrent.ListenableFuture;

import de.danoeh.antennapod.playback.service.Media3PlaybackService;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.EventBusException;

/** Main application class. */
public class PodcastApp extends Application {
    private static final String TAG = "PodcastApp";

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler(new CrashReportExceptionHandler());
        RxJavaErrorHandlerSetup.setupRxJavaErrorHandler();

        try {
            // Robolectric calls onCreate for every test, which causes problems with static members
            EventBus.builder()
                    .logNoSubscriberMessages(false)
                    .sendNoSubscriberEvent(false)
                    .installDefaultEventBus();
        } catch (EventBusException e) {
            Log.d(TAG, e.getMessage());
        }

        DynamicColors.applyToActivitiesIfAvailable(this);
        ClientConfigurator.initialize(this);
        PreferenceUpgrader.checkUpgrades(this);
        registerMediaBrowserService(); // FORK: Bluetooth registration
    }

    // FORK: Eagerly register the media browser service so the app appears in car Bluetooth
    // media browsers (e.g., Toyota Prius) at connection time, before any playback has started.
    private void registerMediaBrowserService() {
        try {
            SessionToken token = new SessionToken(this,
                    new ComponentName(this, Media3PlaybackService.class));
            ListenableFuture<MediaController> future = new MediaController.Builder(this, token).buildAsync();
            future.addListener(() -> {
                try {
                    MediaController controller = future.get();
                    controller.release();
                } catch (Exception e) {
                    Log.d(TAG, "Media service registration: " + e.getMessage());
                }
            }, ContextCompat.getMainExecutor(this));
        } catch (Exception e) {
            Log.d(TAG, "Could not register media browser service: " + e.getMessage());
        }
    }
}

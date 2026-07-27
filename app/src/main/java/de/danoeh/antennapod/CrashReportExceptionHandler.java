package de.danoeh.antennapod;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.annotation.NonNull;

import de.danoeh.antennapod.system.CrashReportWriter;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public class CrashReportExceptionHandler implements Thread.UncaughtExceptionHandler {

    private final Thread.UncaughtExceptionHandler defaultUncaughtExceptionHandler;
    private final Context context;

    public CrashReportExceptionHandler(Context context) {
        this.context = context.getApplicationContext();
        defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
        CrashReportWriter.write(throwable);
        exportToDownloads(throwable);
        defaultUncaughtExceptionHandler.uncaughtException(thread, throwable);
    }

    /**
     * FORK: also drop the stack trace into Downloads.
     *
     * <p>{@link CrashReportWriter} writes into the app's external files directory, which
     * Android 11 and later hide from file managers, so on a device without adb access a crash
     * is effectively unreadable. Downloads is reachable, and MediaStore allows writing there
     * without holding any permission.
     *
     * <p>Diagnostic aid rather than a feature. Remove once the startup crash is understood.
     */
    private void exportToDownloads(Throwable throwable) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        try {
            StringWriter stackTrace = new StringWriter();
            throwable.printStackTrace(new PrintWriter(stackTrace));
            String body = "AntennaPodSmartPlaylist crash report\n"
                    + new Date() + "\n"
                    + "Android API " + Build.VERSION.SDK_INT + " / " + Build.MODEL + "\n\n"
                    + stackTrace;

            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, "antennapod-crash.txt");
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            Uri uri = context.getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return;
            }
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out != null) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable ignored) {
            // A failure to record the crash must never replace the crash itself.
        }
    }
}

package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.mp3.Mp3Extractor;
import de.danoeh.antennapod.net.common.NetworkUtils;
import de.danoeh.antennapod.net.common.UserAgentInterceptor;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;
import de.danoeh.antennapod.playback.service.R;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import java.util.Collections;

public class ExoPlayerUtils {
    @OptIn(markerClass = UnstableApi.class)
    public static ExoPlayer buildPlayer(Context context) {
        return new ExoPlayer.Builder(context)
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                                (int) (UserPreferences.getFastForwardSecs() * 1000L),
                                Math.max(DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                                        (int) (UserPreferences.getFastForwardSecs() * 1000L)),
                                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                        .setBackBuffer((int) (3 * UserPreferences.getRewindSecs() * 1000L), true)
                        .build())
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(), true)
                .setHandleAudioBecomingNoisy(UserPreferences.isPauseOnHeadsetDisconnect())
                .setMediaSourceFactory(new ApMediaSourceFactory(context))
                .setSeekParameters(SeekParameters.EXACT)
                .build();
    }

    public static String translateErrorReason(@NonNull PlaybackException error, Context context) {
        if (NetworkUtils.wasDownloadBlocked(error)) {
            return context.getString(R.string.download_error_blocked);
        }

        Throwable cause = error.getCause();
        if (cause instanceof HttpDataSource.HttpDataSourceException) {
            if (cause.getCause() != null) {
                cause = cause.getCause();
            }
        }
        if (cause != null && "Source error".equals(cause.getMessage())) {
            cause = cause.getCause();
        }
        if (cause != null && cause.getMessage() != null) {
            return cause.getMessage();
        } else if (error.getMessage() != null && cause != null) {
            return error.getMessage() + ": " + cause.getClass().getSimpleName();
        } else {
            return "Unknown error";
        }
    }

    @UnstableApi
    private static class ApMediaSourceFactory implements MediaSource.Factory {
        private final Context context;
        private DrmSessionManagerProvider drmSessionManagerProvider;
        private LoadErrorHandlingPolicy loadErrorHandlingPolicy;

        ApMediaSourceFactory(Context context) {
            this.context = context;
        }

        @NonNull
        @Override
        public MediaSource.Factory setDrmSessionManagerProvider(
                @NonNull DrmSessionManagerProvider drmSessionManagerProvider) {
            this.drmSessionManagerProvider = drmSessionManagerProvider;
            return this;
        }

        @NonNull
        @Override
        public MediaSource.Factory setLoadErrorHandlingPolicy(
                @NonNull LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
            this.loadErrorHandlingPolicy = loadErrorHandlingPolicy;
            return this;
        }

        @NonNull
        @Override
        public int[] getSupportedTypes() {
            return new int[]{C.CONTENT_TYPE_OTHER};
        }

        @NonNull
        @Override
        public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
            final DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory();
            httpDataSourceFactory.setUserAgent(UserAgentInterceptor.USER_AGENT);
            httpDataSourceFactory.setAllowCrossProtocolRedirects(true);
            httpDataSourceFactory.setKeepPostFor302Redirects(true);

            if (mediaItem.requestMetadata != null && mediaItem.requestMetadata.extras != null) {
                Bundle extras = mediaItem.requestMetadata.extras;
                String authHeader = extras.getString(MediaItemAdapter.KEY_AUTHORIZATION_HEADER);
                if (authHeader != null) {
                    httpDataSourceFactory.setDefaultRequestProperties(
                            Collections.singletonMap("Authorization", authHeader));
                }
            }

            DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, httpDataSourceFactory);

            final DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory();
            extractorsFactory.setConstantBitrateSeekingEnabled(true);
            extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_DISABLE_ID3_METADATA);

            ProgressiveMediaSource.Factory factory = new ProgressiveMediaSource.Factory(
                    dataSourceFactory, extractorsFactory);
            if (drmSessionManagerProvider != null) {
                factory.setDrmSessionManagerProvider(drmSessionManagerProvider);
            }
            if (loadErrorHandlingPolicy != null) {
                factory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
            }
            return factory.createMediaSource(mediaItem);
        }
    }
}

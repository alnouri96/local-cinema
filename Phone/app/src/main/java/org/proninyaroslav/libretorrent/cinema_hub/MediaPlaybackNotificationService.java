package org.proninyaroslav.libretorrent.cinema_hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media.VolumeProviderCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import org.proninyaroslav.libretorrent.R;

/**
 * Official MediaSessionCompat + MediaStyle Foreground Notification Service for Local Cinema casting.
 * Displays track/movie title, artwork, casting status, and direct transport actions (Previous/-10s,
 * Play/Pause, Next/+10s, Stop) in the notification shade & lock screen, and routes volume keys.
 */
public final class MediaPlaybackNotificationService extends Service {
    public static final int NOTIFICATION_ID = 2002;
    private static final String CHANNEL_ID = "cinema_cast";

    public static final String ACTION_UPDATE = "com.meedx.localcinema.manager.MEDIA_UPDATE";
    public static final String ACTION_PREV = "com.meedx.localcinema.manager.MEDIA_PREV";
    public static final String ACTION_TOGGLE = "com.meedx.localcinema.manager.MEDIA_TOGGLE";
    public static final String ACTION_NEXT = "com.meedx.localcinema.manager.MEDIA_NEXT";
    public static final String ACTION_STOP = "com.meedx.localcinema.manager.MEDIA_STOP";

    private MediaSessionCompat mediaSession;
    private VolumeProviderCompat volumeProvider;
    private Bitmap fallbackArtwork;
    private android.os.PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;

    private synchronized void acquireLocks() {
        try {
            if (wakeLock == null) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "LocalCinema:CastWakeLock");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                // The service owns the lock until playback stops or the service is destroyed.
                wakeLock.acquire();
            }
        } catch (Throwable e) {
            android.util.Log.w("CinemaMedia", "Failed to acquire wake lock: " + e.getMessage());
        }

        try {
            if (wifiLock == null) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LocalCinema:CastWifiLock");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
            }
        } catch (Throwable e) {
            android.util.Log.w("CinemaMedia", "Failed to acquire Wi-Fi lock: " + e.getMessage());
        }
    }

    private synchronized void releaseLocks() {
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
            } catch (Throwable ignored) {}
            wakeLock = null;
        }
        if (wifiLock != null) {
            try {
                if (wifiLock.isHeld()) wifiLock.release();
            } catch (Throwable ignored) {}
            wifiLock = null;
        }
    }



    public static void syncFromBridge(Context context) {
        if (context == null) return;
        if (!CinemaRemoteBridge.isCastActive()) {
            stop(context);
            return;
        }
        Intent intent = new Intent(context, MediaPlaybackNotificationService.class).setAction(ACTION_UPDATE);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (IllegalStateException e) {
            try {
                context.startService(intent);
            } catch (Exception ignored) {}
        }
    }

    public static void stop(Context context) {
        if (context == null) return;
        context.stopService(new Intent(context, MediaPlaybackNotificationService.class));
        NotificationManager nm = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        ensureMediaSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_UPDATE;
        if (ACTION_PREV.equals(action) || ACTION_TOGGLE.equals(action)
                || ACTION_NEXT.equals(action) || ACTION_STOP.equals(action)) {
            CinemaRemoteBridge.handleNotificationAction(getApplicationContext(), action);
            if (ACTION_STOP.equals(action)) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        if (!CinemaRemoteBridge.isCastActive()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        acquireLocks();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Local Cinema Casting", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void ensureMediaSession() {
        if (mediaSession != null) return;
        mediaSession = new MediaSessionCompat(this, "LocalCinemaCast");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() {
                if (!CinemaRemoteBridge.isCastPlaying()) CinemaRemoteBridge.handleNotificationAction(getApplicationContext(), ACTION_TOGGLE);
            }
            @Override public void onPause() {
                if (CinemaRemoteBridge.isCastPlaying()) CinemaRemoteBridge.handleNotificationAction(getApplicationContext(), ACTION_TOGGLE);
            }
            @Override public void onSkipToPrevious() {
                CinemaRemoteBridge.handleNotificationAction(getApplicationContext(), ACTION_PREV);
            }
            @Override public void onSkipToNext() {
                CinemaRemoteBridge.handleNotificationAction(getApplicationContext(), ACTION_NEXT);
            }
            @Override public void onSeekTo(long pos) {
                CinemaRemoteBridge.sendSeek(getApplicationContext(), pos);
            }
            @Override public void onCustomAction(String action, android.os.Bundle extras) {
                if (ACTION_STOP.equals(action)) onStop();
            }

            @Override public void onStop() {
                CinemaRemoteBridge.handleNotificationAction(getApplicationContext(), ACTION_STOP);
            }
        });
        volumeProvider = new VolumeProviderCompat(
                VolumeProviderCompat.VOLUME_CONTROL_RELATIVE, 100, CinemaRemoteBridge.getVolumePercent()) {
            @Override
            public void onAdjustVolume(int direction) {
                if (direction != 0) {
                    CinemaRemoteBridge.adjustTvVolume(getApplicationContext(), direction, null);
                }
            }
        };
        mediaSession.setPlaybackToRemote(volumeProvider);
        PendingIntent openPending = buildOpenIntent();
        mediaSession.setSessionActivity(openPending);
        mediaSession.setActive(true);
    }

    private Notification buildNotification() {
        ensureMediaSession();

        boolean audio = "audio".equals(CinemaRemoteBridge.getCastKind());
        boolean photo = "image".equals(CinemaRemoteBridge.getCastKind());
        boolean playing = CinemaRemoteBridge.isCastPlaying();
        String title = CinemaRemoteBridge.getCastTitle();
        if (title.isBlank()) title = "Local Cinema";
        String subtitle = CinemaRemoteBridge.getCastSubtitle();
        if (subtitle.isBlank()) subtitle = "Casting to TV";

        Bitmap art = CinemaRemoteBridge.getCastArt();
        if (art == null) {
            if (fallbackArtwork == null) {
                try {
                    var icon = getPackageManager().getApplicationIcon(getApplicationInfo());
                    fallbackArtwork = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
                    icon.setBounds(0, 0, 256, 256);
                    icon.draw(new Canvas(fallbackArtwork));
                } catch (Exception ignored) {}
            }
            art = fallbackArtwork;
        }

        if (volumeProvider != null) {
            volumeProvider.setCurrentVolume(CinemaRemoteBridge.getVolumePercent());
        }

        mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, photo ? 10000L : CinemaRemoteBridge.getCastDurMs())
                .build());

        long actionsMask = PlaybackStateCompat.ACTION_STOP;
        if (!photo) {
            actionsMask |= PlaybackStateCompat.ACTION_PLAY_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY
                    | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_SEEK_TO
                    | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
        }
        int pbState = (photo || playing) ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actionsMask)
                .addCustomAction(ACTION_STOP, "Stop", R.drawable.cinema_control_stop)
                .setState(pbState, CinemaRemoteBridge.getCastPosMs(), playing ? 1f : 0f)
                .build());

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app_notification)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setLargeIcon(art)
                .setContentIntent(buildOpenIntent())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT);

        if (!photo) {
            int prevIcon = audio ? R.drawable.ic_skip_previous_24 : R.drawable.ic_replay_10_24;
            String prevTitle = audio ? "Previous" : "-10s";
            int toggleIcon = playing ? R.drawable.cinema_control_pause : R.drawable.cinema_control_play;
            String toggleTitle = playing ? "Pause" : "Play";
            int nextIcon = audio ? R.drawable.ic_skip_next_24 : R.drawable.ic_forward_10_24;
            String nextTitle = audio ? "Next" : "+10s";

            b.addAction(prevIcon, prevTitle, servicePending(101, ACTION_PREV));
            b.addAction(toggleIcon, toggleTitle, servicePending(102, ACTION_TOGGLE));
            b.addAction(nextIcon, nextTitle, servicePending(103, ACTION_NEXT));
            b.addAction(R.drawable.cinema_control_stop, "Stop", servicePending(104, ACTION_STOP));
            b.setStyle(new MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        } else {
            b.addAction(R.drawable.cinema_control_stop, "Stop", servicePending(104, ACTION_STOP));
            b.setStyle(new MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0));
        }
        return b.build();
    }

    private PendingIntent buildOpenIntent() {
        Intent open = new Intent(this, TvHubActivity.class)
                .setAction("com.meedx.localcinema.manager.OPEN_CAST_CONTROLS")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return PendingIntent.getActivity(this, 8767, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent servicePending(int code, String action) {
        Intent i = new Intent(this, MediaPlaybackNotificationService.class).setAction(action);
        return PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}

package com.archos.mediacenter.video.leanback.cinema;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public final class TvBackgroundConnectionService extends Service {
    private android.net.wifi.WifiManager.WifiLock wifiLock;

    @Override public void onCreate() {
        super.onCreate();
        var wifi = getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
        if (wifi == null) return;
        try {
            wifiLock = wifi.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LocalCinema:remote");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (SecurityException error) {
            android.util.Log.w("TV_REMOTE_RX", "Wi-Fi lock unavailable");
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String channel = "cinema_phone_connection";
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(channel, "Local Cinema connection", NotificationManager.IMPORTANCE_LOW));
            builder = new Notification.Builder(this, channel);
        } else {
            builder = new Notification.Builder(this);
        }
        CinemaLinkServer.startFor(getApplication());
        try {
            startForeground(8765, builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("Local Cinema").setContentText("Ready for your paired phone")
                    .setOngoing(true).build());
        } catch (IllegalStateException | SecurityException restricted) {
            android.util.Log.w("TV_REMOTE_RX", "Android restricted foreground promotion", restricted);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        TvRemotePermissionKeeper.ensureAccessibilityEnabled(getApplicationContext());
        TvLocalAdbBridge.reconnectIfEnabledAsync(getApplicationContext());
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (!TvRemoteAccessibilityService.available()) CinemaLinkServer.stopServer();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

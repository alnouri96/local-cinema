package com.archos.mediacenter.video.leanback.cinema;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.content.ContextCompat;

public final class TvBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
                && !"android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) return;
        if (!CinemaLinkServer.backgroundEnabled(context)) return;
        CinemaLinkServer.startFor((android.app.Application) context.getApplicationContext());
        try {
            ContextCompat.startForegroundService(context, new Intent(context, TvBackgroundConnectionService.class));
        } catch (IllegalStateException | SecurityException e) {
            android.util.Log.w("TV_REMOTE_RX", "Could not start receiver for " + intent.getAction(), e);
        }
    }
}

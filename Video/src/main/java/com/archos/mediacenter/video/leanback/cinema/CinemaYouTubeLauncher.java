package com.archos.mediacenter.video.leanback.cinema;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;

import com.meedx.providers.OpenTrailersProvider;

/**
 * Launches trailers directly inside installed Android TV YouTube apps (or SmartTube / standard
 * YouTube / browser), automatically falling back to an official trailer search when ytId is absent.
 */
public final class CinemaYouTubeLauncher {
    private static final String[] YOUTUBE_PACKAGES = {
            "com.google.android.youtube.tv",
            "com.teamsmart.videomanager.tv",
            "com.liskovsoft.smarttubetv",
            "com.google.android.youtube"
    };

    private CinemaYouTubeLauncher() {}

    public static void watchTrailer(Context context, String ytId, String title, int year) {
        if (context == null) return;
        String cleanYt = ytId == null ? "" : ytId.trim();
        Uri target = !cleanYt.isEmpty()
                ? Uri.parse(OpenTrailersProvider.buildYouTubeWatchUrl(cleanYt))
                : Uri.parse(OpenTrailersProvider.buildYouTubeSearchUrl(title, year));
        launchUri(context, target);
    }

    public static void openUriOrSearch(Context context, Uri trailerUri, String title, int year) {
        if (context == null) return;
        Uri target = trailerUri != null
                ? trailerUri
                : Uri.parse(OpenTrailersProvider.buildYouTubeSearchUrl(title, year));
        launchUri(context, target);
    }

    public static void launchUri(Context context, Uri uri) {
        if (context == null || uri == null) return;
        PackageManager pm = context.getPackageManager();
        for (String pkg : YOUTUBE_PACKAGES) {
            try {
                Intent targeted = new Intent(Intent.ACTION_VIEW, uri)
                        .setPackage(pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (targeted.resolveActivity(pm) != null) {
                    context.startActivity(targeted);
                    return;
                }
            } catch (Exception ignored) {}
        }
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(context,
                    context.getString(com.archos.mediacenter.video.R.string.cinema_no_application_can_open_youtube),
                    Toast.LENGTH_LONG).show();
        }
    }
}

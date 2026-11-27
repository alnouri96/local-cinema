package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;

import androidx.preference.PreferenceManager;

import com.archos.mediacenter.video.R;

public enum CinemaPlaybackOption {
    FAMILY_WATCH("cinema_family_watch", R.string.cinema_family_watch, R.drawable.cinema_ic_shield),
    SKIP_ADS("cinema_skip_ads", R.string.cinema_skip_ads, R.drawable.cinema_play_movie);

    public final String key;
    public final int title;
    public final int icon;

    CinemaPlaybackOption(String key, int title, int icon) {
        this.key = key;
        this.title = title;
        this.icon = icon;
    }

    public boolean available() { return true; }

    public boolean enabled(Context context) {
        if (!available()) return false;
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(key, false);
    }

    public void toggle(Context context) {
        if (!available()) return;
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(key, !enabled(context)).apply();
    }

    public String state(Context context) {
        if (!available()) return CinemaUi.label(context, "قريباً", "Coming Soon");
        return enabled(context)
                ? "✓ " + context.getString(R.string.cinema_playback_option_on)
                : context.getString(R.string.cinema_off);
    }
}

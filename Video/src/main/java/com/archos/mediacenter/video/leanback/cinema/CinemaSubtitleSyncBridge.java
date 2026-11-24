package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import androidx.preference.PreferenceManager;

import com.archos.mediacenter.video.player.PlayerActivity;
import com.meedx.providers.UnifiedSubtitleConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps TV legacy SharedPreferences (PlayerActivity, CinemaHlsActivity, CinemaLanguagePreferences)
 * in lockstep with {@link UnifiedSubtitleConfig} and pushes changes to connected Phone peers.
 */
public final class CinemaSubtitleSyncBridge {
    private CinemaSubtitleSyncBridge() {}

    /** One-time bootstrap from TV legacy prefs if UnifiedSubtitleConfig has never been saved yet. */
    public static void ensureBootstrapped(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (UnifiedSubtitleConfig.updatedAt(app) > 0L) return;
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(app);
        List<String> langs = new ArrayList<>();
        String p = sp.getString(CinemaLanguagePreferences.KEY_PRIMARY_SUB, "arabic");
        String s = sp.getString(CinemaLanguagePreferences.KEY_SECONDARY_SUB, "english");
        if (p != null && !p.isBlank()) langs.add(p);
        if (s != null && !s.isBlank() && !s.equalsIgnoreCase(p)) langs.add(s);
        if (!langs.isEmpty()) UnifiedSubtitleConfig.setLanguagePriorities(app, langs);
        UnifiedSubtitleConfig.setSecondaryLanguage(app, s != null ? s : "");
        UnifiedSubtitleConfig.setAutoSubtitlesEnabled(app, sp.getBoolean("subsource_enabled", true));
        UnifiedSubtitleConfig.setDualSubtitlesEnabled(app, sp.getBoolean(CinemaUi.DUAL_SUBS_ENABLED, false));
        UnifiedSubtitleConfig.setDualSubPosition(app, sp.getInt("pref_dual_sub_position", 1));
        int size = sp.getInt(PlayerActivity.KEY_SUBTITLE_SIZE, 36);
        int vpos = sp.getInt(PlayerActivity.KEY_SUBTITLE_VPOS, 64);
        int color = sp.getInt(PlayerActivity.KEY_SUBTITLE_COLOR, Color.WHITE);
        boolean outline = sp.getBoolean(PlayerActivity.KEY_SUBTITLE_OUTLINE, false);
        UnifiedSubtitleConfig.saveAppearance(app, size, vpos, color, outline, 0L);
    }

    /** Mirrors UnifiedSubtitleConfig into TV legacy preference keys read by players & loaders. */
    public static void applyUnifiedToTvPrefs(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(app);
        sp.edit()
                .putString(CinemaLanguagePreferences.KEY_PRIMARY_SUB, UnifiedSubtitleConfig.primaryLanguage(app))
                .putString(CinemaLanguagePreferences.KEY_SECONDARY_SUB, UnifiedSubtitleConfig.secondaryLanguage(app))
                .putBoolean("subsource_enabled", UnifiedSubtitleConfig.autoSubtitlesEnabled(app))
                .putBoolean(CinemaUi.DUAL_SUBS_ENABLED, UnifiedSubtitleConfig.dualSubtitlesEnabled(app))
                .putInt("pref_dual_sub_position", UnifiedSubtitleConfig.dualSubPosition(app))
                .putInt(PlayerActivity.KEY_SUBTITLE_SIZE, UnifiedSubtitleConfig.textSize(app))
                .putInt(PlayerActivity.KEY_SUBTITLE_VPOS, UnifiedSubtitleConfig.vpos(app))
                .putInt(PlayerActivity.KEY_SUBTITLE_COLOR, UnifiedSubtitleConfig.textColor(app))
                .putBoolean(PlayerActivity.KEY_SUBTITLE_OUTLINE, UnifiedSubtitleConfig.opaqueBg(app))
                .apply();
    }

    /** Call after any TV subtitle settings mutation to sync legacy prefs and push to connected Phones. */
    public static void onTvSubtitleConfigChanged(Context context) {
        if (context == null) return;
        applyUnifiedToTvPrefs(context);
        CinemaLinkServer.broadcastSubtitleConfig(context.getApplicationContext());
    }
}

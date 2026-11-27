package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.meedx.providers.UnifiedSubtitleConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps TV legacy SharedPreferences (isolated via {@link CinemaNovaLegacyPrefs})
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
        var legacy = CinemaNovaLegacyPrefs.readLegacySubtitleSnapshot(sp);
        UnifiedSubtitleConfig.setDualSubPosition(app, legacy.dualSubPosition());
        UnifiedSubtitleConfig.saveAppearance(
                app,
                legacy.textSize(),
                legacy.vpos(),
                legacy.textColor(),
                legacy.opaqueBg(),
                legacy.bgOpacity(),
                legacy.outlineLevel(),
                legacy.outlineColor(),
                0L);
    }

    /** Mirrors UnifiedSubtitleConfig into TV legacy preference keys read by players & loaders. */
    public static void applyUnifiedToTvPrefs(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(app);
        CinemaNovaLegacyPrefs.mirrorUnifiedSubtitlesToLegacyPlayer(app, sp);
    }

    /** Call after any TV subtitle settings mutation to sync legacy prefs and push to connected Phones. */
    public static void onTvSubtitleConfigChanged(Context context) {
        if (context == null) return;
        applyUnifiedToTvPrefs(context);
        CinemaLinkServer.broadcastSubtitleConfig(context.getApplicationContext());
    }
}

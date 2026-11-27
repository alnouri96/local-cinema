package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;

import androidx.preference.PreferenceManager;

import com.archos.mediacenter.video.leanback.VideosByListActivity;
import com.archos.mediacenter.video.leanback.settings.VideoSettingsActivity;
import com.archos.mediacenter.video.player.PlayerActivity;
import com.archos.mediacenter.video.utils.VideoPreferencesCommon;
import com.meedx.providers.UnifiedSubtitleConfig;

/**
 * Explicit quarantine & compatibility bridge for legacy Nova preferences and screens.
 *
 * <p>All modern Local Cinema settings live in {@link CinemaSettingsActivity},
 * {@link UnifiedSubtitleConfig}, {@link HomePreferences}, and {@link CinemaEngineBoundary}.
 * Legacy Nova keys required by {@code MediaLib}, {@code FileCoreLibrary}, or {@code PlayerActivity}
 * are isolated here so {@code engine-v2} can retire them cleanly without touching modern UI code.
 */
public final class CinemaNovaLegacyPrefs {
    /** Preserved dual-subtitle vertical placement key shared across players and Phone sync. */
    public static final String KEY_DUAL_SUB_POSITION = "pref_dual_sub_position";

    /** Legacy Nova keys quarantined behind helper methods. */
    public static final String KEY_LEGACY_DYNAMIC_AUDIO_DELAY = VideoPreferencesCommon.KEY_ENABLE_DYNAMIC_AUDIO_DELAY;
    public static final String KEY_LEGACY_UI_LANG = VideoPreferencesCommon.KEY_UI_LANG;
    public static final String VALUE_LEGACY_UI_LANG_SYSTEM = VideoPreferencesCommon.KEY_UI_LANG_SYSTEM;
    public static final String KEY_LEGACY_PLAYBACK_SPEED = VideoPreferencesCommon.KEY_PLAYBACK_SPEED;
    public static final String KEY_LEGACY_SMBJ = VideoPreferencesCommon.KEY_SMBJ;

    private CinemaNovaLegacyPrefs() {}

    public record LegacySubtitleSnapshot(
            int textSize,
            int vpos,
            int textColor,
            boolean opaqueBg,
            int bgOpacity,
            int outlineLevel,
            int outlineColor,
            int dualSubPosition) {}

    public static String getUiLanguageRaw(SharedPreferences sp) {
        if (sp == null) return "";
        return sp.getString(KEY_LEGACY_UI_LANG, "");
    }

    public static boolean isSystemUiLanguage(String uiLang) {
        return uiLang == null || uiLang.isBlank() || VALUE_LEGACY_UI_LANG_SYSTEM.equalsIgnoreCase(uiLang);
    }

    public static void writeUiLanguage(SharedPreferences.Editor editor, String localeTag) {
        if (editor == null) return;
        editor.putString(KEY_LEGACY_UI_LANG,
                (localeTag == null || localeTag.isEmpty()) ? VALUE_LEGACY_UI_LANG_SYSTEM : localeTag);
    }

    public static boolean isDynamicAudioDelayEnabled(Context context) {
        if (context == null) return true;
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(KEY_LEGACY_DYNAMIC_AUDIO_DELAY, true);
    }

    public static void setDynamicAudioDelayEnabled(Context context, boolean enabled) {
        if (context == null) return;
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(KEY_LEGACY_DYNAMIC_AUDIO_DELAY, enabled)
                .apply();
    }

    public static boolean isLegacySmbjKey(String key) {
        return KEY_LEGACY_SMBJ.equals(key);
    }

    public static void enforceStableSmbTransport(SharedPreferences.Editor editor) {
        if (editor != null) {
            editor.putBoolean(KEY_LEGACY_SMBJ, false);
        }
    }

    public static void enableLegacyPlaybackSpeedPref(SharedPreferences.Editor editor) {
        if (editor != null) {
            editor.putBoolean(KEY_LEGACY_PLAYBACK_SPEED, true);
        }
    }

    /** Reads legacy PlayerActivity subtitle keys once during initial UnifiedSubtitleConfig bootstrap. */
    public static LegacySubtitleSnapshot readLegacySubtitleSnapshot(SharedPreferences sp) {
        int size = sp.getInt(PlayerActivity.KEY_SUBTITLE_SIZE, 36);
        int vpos = sp.getInt(PlayerActivity.KEY_SUBTITLE_VPOS, 64);
        int color = sp.getInt(PlayerActivity.KEY_SUBTITLE_COLOR, Color.WHITE);
        boolean bg = sp.getBoolean(PlayerActivity.KEY_SUBTITLE_BG, false);
        int bgOp = sp.getInt(PlayerActivity.KEY_SUBTITLE_BG_OPACITY, 75);
        int outlineLvl = sp.getInt(PlayerActivity.KEY_SUBTITLE_OUTLINE_LEVEL,
                sp.getBoolean(PlayerActivity.KEY_SUBTITLE_OUTLINE, false) ? 2 : 0);
        int outlineCol = sp.getInt(PlayerActivity.KEY_SUBTITLE_OUTLINE_COLOR, Color.BLACK);
        int dualPos = sp.getInt(KEY_DUAL_SUB_POSITION, 1);
        return new LegacySubtitleSnapshot(size, vpos, color, bg, bgOp, outlineLvl, outlineCol, dualPos);
    }

    /** Mirrors {@link UnifiedSubtitleConfig} into legacy Nova player SharedPreferences keys. */
    public static void mirrorUnifiedSubtitlesToLegacyPlayer(Context appContext, SharedPreferences sp) {
        if (appContext == null || sp == null) return;
        int outlineLvl = UnifiedSubtitleConfig.outlineLevel(appContext);
        sp.edit()
                .putString(CinemaLanguagePreferences.KEY_PRIMARY_SUB, UnifiedSubtitleConfig.primaryLanguage(appContext))
                .putString(CinemaLanguagePreferences.KEY_SECONDARY_SUB, UnifiedSubtitleConfig.secondaryLanguage(appContext))
                .putBoolean("subsource_enabled", UnifiedSubtitleConfig.autoSubtitlesEnabled(appContext))
                .putBoolean(CinemaUi.DUAL_SUBS_ENABLED, UnifiedSubtitleConfig.dualSubtitlesEnabled(appContext))
                .putInt(KEY_DUAL_SUB_POSITION, UnifiedSubtitleConfig.dualSubPosition(appContext))
                .putInt(PlayerActivity.KEY_SUBTITLE_SIZE, UnifiedSubtitleConfig.textSize(appContext))
                .putInt(PlayerActivity.KEY_SUBTITLE_VPOS, UnifiedSubtitleConfig.vpos(appContext))
                .putInt(PlayerActivity.KEY_SUBTITLE_COLOR, UnifiedSubtitleConfig.textColor(appContext))
                .putBoolean(PlayerActivity.KEY_SUBTITLE_BG, UnifiedSubtitleConfig.opaqueBg(appContext))
                .putInt(PlayerActivity.KEY_SUBTITLE_BG_OPACITY, UnifiedSubtitleConfig.bgOpacity(appContext))
                .putInt(PlayerActivity.KEY_SUBTITLE_OUTLINE_LEVEL, outlineLvl)
                .putInt(PlayerActivity.KEY_SUBTITLE_OUTLINE_COLOR, UnifiedSubtitleConfig.outlineColor(appContext))
                .putBoolean(PlayerActivity.KEY_SUBTITLE_OUTLINE, outlineLvl > 0)
                .apply();
    }

    /** Opens legacy Nova decoder / passthrough / A-V preferences screen (isolated for Engine v2 removal). */
    public static void openLegacyAdvancedPlayerSettings(Context context) {
        if (context == null) return;
        context.startActivity(new Intent(context, VideoSettingsActivity.class));
    }

    /** Opens legacy custom video lists screen. */
    public static void openLegacyCustomVideoLists(Context context) {
        if (context == null) return;
        context.startActivity(new Intent(context, VideosByListActivity.class));
    }
}

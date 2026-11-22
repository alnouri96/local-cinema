package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import androidx.core.os.ConfigurationCompat;
import androidx.preference.PreferenceManager;
import com.archos.mediacenter.video.CustomApplication;
import com.archos.mediacenter.video.utils.VideoPreferencesCommon;

public final class MetadataLanguage {
    public static final String PREF = "cinema_metadata_language";

    private MetadataLanguage() {}

    public static String resolve(Context context) {
        String stored = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF, "");
        if ("ar-SA".equals(stored) || "en-US".equals(stored)) return stored;
        String uiLang = CustomApplication.getUiLocale(context);
        if (uiLang != null && !uiLang.isEmpty() && !VideoPreferencesCommon.KEY_UI_LANG_SYSTEM.equalsIgnoreCase(uiLang)) {
            return uiLang.toLowerCase(java.util.Locale.ROOT).startsWith("ar") ? "ar-SA" : "en-US";
        }
        var locale = ConfigurationCompat.getLocales(context.getResources().getConfiguration()).get(0);
        return locale != null && "ar".equals(locale.getLanguage()) ? "ar-SA" : "en-US";
    }

    static String images(String language) {
        return "ar,en,null";
    }
}

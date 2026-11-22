package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.net.Uri;
import androidx.preference.PreferenceManager;

/** Explicit per-file choices bridge the details UI to NOVA's existing track selection. */
public final class CinemaTrackChoices {
    private static String key(Uri uri){return SubtitleStorage.preferenceKey(uri);}
    public static int audio(Context c,Uri uri){return uri==null?-1:PreferenceManager.getDefaultSharedPreferences(c).getInt(key(uri)+"_audio",-1);}
    public static void audio(Context c,Uri uri,int track){PreferenceManager.getDefaultSharedPreferences(c).edit().putInt(key(uri)+"_audio",track).apply();}
    /** -2 automatic, -1 off, nonnegative embedded-track ordinal. */
    public static int embedded(Context c,Uri uri){return uri==null?-2:PreferenceManager.getDefaultSharedPreferences(c).getInt(key(uri)+"_embedded",-2);}
    public static void embedded(Context c,Uri uri,int ordinal){PreferenceManager.getDefaultSharedPreferences(c).edit().putInt(key(uri)+"_embedded",ordinal).putBoolean(key(uri)+"_manual",ordinal!=-2).remove(key(uri)+"_name").apply();}
    public static void clearEmbedded(Context c,Uri uri){if(uri!=null)PreferenceManager.getDefaultSharedPreferences(c).edit().remove(key(uri)+"_embedded").apply();}
}

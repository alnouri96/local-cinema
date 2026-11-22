package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;

import androidx.media3.common.C;
import androidx.media3.common.Tracks;
import androidx.preference.PreferenceManager;

import com.archos.mediacenter.video.R;

import java.util.Locale;

final class CinemaPreferredAudio {
    private static final String KEY = "cinema_addon_audio_language";
    private static final String[] TAGS = {"en", "ar", "hi", "fr", "es", "tr", "de", "ja"};
    private static final String[] ISO3 = {"eng", "ara", "hin", "fra", "spa", "tur", "deu", "jpn"};
    private static final int[] LABELS = {R.string.addon_audio_english, R.string.addon_audio_arabic,
            R.string.addon_audio_hindi, R.string.addon_audio_french, R.string.addon_audio_spanish,
            R.string.addon_audio_turkish, R.string.addon_audio_german, R.string.addon_audio_japanese};

    private CinemaPreferredAudio() {}

    static String language(Context context) { return TAGS[selection(context)]; }

    static String label(Context context) { return context.getString(LABELS[selection(context)]); }

    private static int selection(Context context) {
        String stored = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, TAGS[0]);
        for (int i = 0; i < TAGS.length; i++) if (TAGS[i].equals(stored)) return i;
        return 0;
    }

    static void choose(Context context, Runnable onChange) {
        String[] labels = new String[LABELS.length];
        for (int i = 0; i < LABELS.length; i++) labels[i] = context.getString(LABELS[i]);
        CinemaUi.dialog(context).setTitle(R.string.addon_preferred_audio)
                .setSingleChoiceItems(labels, selection(context), (dialog, which) -> {
                    PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY, TAGS[which]).apply();
                    dialog.dismiss();
                    onChange.run();
                }).setNegativeButton(R.string.addon_back, null).show();
    }

    static String[] languages(Context context) {
        int preferred = selection(context);
        return switch (TAGS[preferred]) {
            case "fr" -> new String[]{TAGS[preferred], ISO3[preferred], "fre"};
            case "de" -> new String[]{TAGS[preferred], ISO3[preferred], "ger"};
            default -> new String[]{TAGS[preferred], ISO3[preferred]};
        };
    }

    static boolean knownUnavailable(Tracks tracks, String[] preferred) {
        boolean known = false;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int index = 0; index < group.length; index++) {
                if (!group.isTrackSupported(index)) continue;
                String language = group.getTrackFormat(index).language;
                if (language == null || language.isBlank()) return false;
                String base = language.toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
                if ("und".equals(base) || "mul".equals(base)) return false;
                for (String tag : preferred) if (tag.equals(base)) return false;
                known = true;
            }
        }
        return known;
    }
}

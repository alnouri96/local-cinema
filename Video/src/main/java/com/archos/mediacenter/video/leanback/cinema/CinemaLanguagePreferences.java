package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.archos.mediacenter.video.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Modular Audio & Dual-Subtitle language priority preferences manager.
 * Manages preferred audio track language, primary subtitle language, and secondary subtitle language.
 */
public final class CinemaLanguagePreferences {
    public static final String KEY_PRIMARY_SUB = "subsource_language";
    public static final String KEY_SECONDARY_SUB = "subsource_secondary_language";

    private CinemaLanguagePreferences() {}

    public static String primarySubtitleLanguage(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(KEY_PRIMARY_SUB, "arabic");
    }

    public static String secondarySubtitleLanguage(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(KEY_SECONDARY_SUB, "english");
    }

    public static void setPrimarySubtitleLanguage(Context context, String lang) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putString(KEY_PRIMARY_SUB, lang == null ? "arabic" : lang).apply();
    }

    public static void setSecondarySubtitleLanguage(Context context, String lang) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putString(KEY_SECONDARY_SUB, lang == null ? "" : lang).apply();
    }

    public static String toIso2(String lang) {
        if (lang == null || lang.isBlank()) return "";
        return switch (lang.trim().toLowerCase(Locale.ROOT)) {
            case "ar", "ara", "arabic" -> "ar";
            case "en", "eng", "english" -> "en";
            case "fr", "fre", "fra", "french" -> "fr";
            case "es", "spa", "spanish" -> "es";
            case "de", "ger", "deu", "german" -> "de";
            case "tr", "tur", "turkish" -> "tr";
            case "fa", "per", "fas", "persian" -> "fa";
            case "ku", "kur", "kurdish" -> "ku";
            case "zh", "chi", "zho", "chinese" -> "zh";
            case "ja", "jpn", "japanese" -> "ja";
            case "ko", "kor", "korean" -> "ko";
            case "hi", "hin", "hindi" -> "hi";
            case "ru", "rus", "russian" -> "ru";
            case "it", "ita", "italian" -> "it";
            case "pt", "por", "portuguese" -> "pt";
            default -> lang.length() >= 2 ? lang.substring(0, 2).toLowerCase(Locale.ROOT) : lang.toLowerCase(Locale.ROOT);
        };
    }

    public static String localizedSubtitleLabel(Context context, String value) {
        if (value == null || value.isBlank()) {
            return context.getString(R.string.cinema_lang_none);
        }
        return CinemaAutoSubtitles.languageLabel(value);
    }

    public static void showDialog(Context context, Runnable onChanged) {
        var panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutDirection(CinemaUi.arabic(context) ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        int pad = CinemaUi.dp(context, 18);
        panel.setPaddingRelative(pad, CinemaUi.dp(context, 12), pad, CinemaUi.dp(context, 12));

        TextView[] labels = new TextView[3];
        Runnable refresh = () -> {
            if (labels[0] != null) {
                labels[0].setText(context.getString(R.string.cinema_pref_audio_lang) + "  ·  "
                        + CinemaPreferredAudio.label(context));
            }
            if (labels[1] != null) {
                labels[1].setText(context.getString(R.string.cinema_pref_primary_sub_lang) + "  ·  "
                        + localizedSubtitleLabel(context, primarySubtitleLanguage(context)));
            }
            if (labels[2] != null) {
                labels[2].setText(context.getString(R.string.cinema_pref_secondary_sub_lang) + "  ·  "
                        + localizedSubtitleLabel(context, secondarySubtitleLanguage(context)));
            }
        };

        labels[0] = addRow(panel, () -> CinemaPreferredAudio.choose(context, () -> {
            refresh.run();
            if (onChanged != null) onChanged.run();
        }));
        labels[1] = addRow(panel, () -> CinemaAutoSubtitles.chooseLanguage(context,
                primarySubtitleLanguage(context), false, chosen -> {
                    setPrimarySubtitleLanguage(context, chosen);
                    refresh.run();
                    if (onChanged != null) onChanged.run();
                }));
        labels[2] = addRow(panel, () -> chooseSecondaryLanguage(context, chosen -> {
            setSecondarySubtitleLanguage(context, chosen);
            refresh.run();
            if (onChanged != null) onChanged.run();
        }));
        refresh.run();

        var dialog = CinemaUi.dialog(context)
                .setTitle(R.string.cinema_pref_languages_title)
                .setView(panel)
                .setNegativeButton(R.string.addon_back, null)
                .create();
        dialog.show();
        CinemaUi.focusDialogControl(dialog, labels[0]);
    }

    private static void chooseSecondaryLanguage(Context context, java.util.function.Consumer<String> onPick) {
        List<String> values = new ArrayList<>(List.of(
                "", "english", "arabic", "french", "spanish", "german",
                "turkish", "persian", "kurdish", "chinese", "japanese", "korean"));
        String current = secondarySubtitleLanguage(context);
        if (!values.contains(current)) values.add(current);
        String[] names = new String[values.size()];
        for (int i = 0; i < values.size(); i++) {
            names[i] = localizedSubtitleLabel(context, values.get(i));
        }
        int checked = Math.max(0, values.indexOf(current));
        CinemaUi.dialog(context)
                .setTitle(R.string.cinema_pref_secondary_sub_lang)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    dialog.dismiss();
                    onPick.accept(values.get(which));
                })
                .setNegativeButton(R.string.addon_back, null)
                .show();
    }

    private static TextView addRow(LinearLayout parent, Runnable click) {
        Context context = parent.getContext();
        TextView row = CinemaUi.text(context, 16, true);
        row.setTextColor(Color.WHITE);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        row.setPaddingRelative(CinemaUi.dp(context, 16), CinemaUi.dp(context, 12),
                CinemaUi.dp(context, 16), CinemaUi.dp(context, 12));
        row.setMinHeight(CinemaUi.dp(context, 52));
        CinemaUi.focusable(row);
        row.setOnFocusChangeListener((v, focused) -> row.setTextColor(focused ? CinemaTokens.ON_FOCUS : Color.WHITE));
        row.setOnClickListener(v -> click.run());
        var lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = CinemaUi.dp(context, 10);
        parent.addView(row, lp);
        return row;
    }
}

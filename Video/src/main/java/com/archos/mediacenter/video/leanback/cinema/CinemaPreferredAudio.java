package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.utils.VideoMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ordered audio language priority manager (1, 2, 3...).
 * Applies sequential track selection across Movies and Series in both
 * the native player (PlayerService) and ExoPlayer (CinemaHlsActivity).
 */
public final class CinemaPreferredAudio {
    private static final String KEY_LEGACY = "cinema_addon_audio_language";
    private static final String KEY_PRIORITY_LIST = "cinema_audio_language_priority";

    private record LangSpec(String tag, String ar, String en, List<String> codes, List<String> nameTokens) {}

    private static final Map<String, LangSpec> CATALOG = new LinkedHashMap<>();
    static {
        register("ar", "العربية", "Arabic", List.of("ar", "ara"), List.of("arabic", "العربية", "عربي"));
        register("en", "الإنجليزية", "English", List.of("en", "eng"), List.of("english", "الإنجليزية", "انجليزي"));
        register("fr", "الفرنسية", "French", List.of("fr", "fra", "fre"), List.of("french", "français", "الفرنسية"));
        register("es", "الإسبانية", "Spanish", List.of("es", "spa"), List.of("spanish", "español", "الإسبانية"));
        register("de", "الألمانية", "German", List.of("de", "deu", "ger"), List.of("german", "deutsch", "الألمانية"));
        register("tr", "التركية", "Turkish", List.of("tr", "tur"), List.of("turkish", "türkçe", "التركية"));
        register("hi", "الهندية", "Hindi", List.of("hi", "hin"), List.of("hindi", "الهندية"));
        register("ja", "اليابانية", "Japanese", List.of("ja", "jpn"), List.of("japanese", "اليابانية"));
        register("ko", "الكورية", "Korean", List.of("ko", "kor"), List.of("korean", "الكورية"));
        register("it", "الإيطالية", "Italian", List.of("it", "ita"), List.of("italian", "italiano", "الإيطالية"));
        register("ru", "الروسية", "Russian", List.of("ru", "rus"), List.of("russian", "русский", "الروسية"));
        register("pt", "البرتغالية", "Portuguese", List.of("pt", "por"), List.of("portuguese", "português", "البرتغالية"));
        register("fa", "الفارسية", "Persian", List.of("fa", "fas", "per"), List.of("persian", "farsi", "الفارسية"));
        register("ku", "الكردية", "Kurdish", List.of("ku", "kur"), List.of("kurdish", "الكردية"));
        register("zh", "الصينية", "Chinese", List.of("zh", "zho", "chi"), List.of("chinese", "mandarin", "cantonese", "الصينية"));
    }

    private static void register(String tag, String ar, String en, List<String> codes, List<String> tokens) {
        CATALOG.put(tag, new LangSpec(tag, ar, en, codes, tokens));
    }

    private CinemaPreferredAudio() {}

    public static List<String> priorityList(Context context) {
        var prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String raw = prefs.getString(KEY_PRIORITY_LIST, null);
        var out = new LinkedHashSet<String>();
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split("[,;\\s]+")) {
                String tag = normalizeTag(part);
                if (!tag.isEmpty()) out.add(tag);
            }
        }
        if (out.isEmpty()) {
            String legacy = normalizeTag(prefs.getString(KEY_LEGACY, ""));
            if (!legacy.isEmpty()) {
                out.add(legacy);
                if (!"en".equals(legacy)) out.add("en");
                else out.add("ar");
            } else if (CinemaUi.arabic(context)) {
                out.add("ar");
                out.add("en");
            } else {
                out.add("en");
                out.add("ar");
            }
        }
        return new ArrayList<>(out);
    }

    public static void savePriorityList(Context context, List<String> orderedTags) {
        var clean = new LinkedHashSet<String>();
        if (orderedTags != null) {
            for (String t : orderedTags) {
                String tag = normalizeTag(t);
                if (!tag.isEmpty()) clean.add(tag);
            }
        }
        if (clean.isEmpty()) clean.add("en");
        List<String> finalList = new ArrayList<>(clean);
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(KEY_PRIORITY_LIST, String.join(",", finalList))
                .putString(KEY_LEGACY, finalList.get(0))
                .apply();
    }

    public static String language(Context context) {
        return priorityList(context).get(0);
    }

    public static String displayName(Context context, String tag) {
        String resolved = com.meedx.providers.UnifiedSubtitleConfig.languageDisplay(tag, CinemaUi.arabic(context));
        if (resolved != null && !resolved.isBlank()) return resolved;
        return tag == null ? "" : tag.toUpperCase(Locale.ROOT);
    }

    public static String label(Context context) {
        List<String> list = priorityList(context);
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(list.size(), 3);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(" · ");
            sb.append(i + 1).append(". ").append(displayName(context, list.get(i)));
        }
        if (list.size() > limit) sb.append(" (+").append(list.size() - limit).append(")");
        return sb.toString();
    }

    /** Returns ordered ISO-2 and ISO-3 language tags for ExoPlayer setPreferredAudioLanguages. */
    public static String[] languages(Context context) {
        var orderedCodes = new LinkedHashSet<String>();
        for (String tag : priorityList(context)) {
            LangSpec spec = CATALOG.get(tag);
            if (spec != null) orderedCodes.addAll(spec.codes());
            else orderedCodes.add(tag);
        }
        return orderedCodes.toArray(new String[0]);
    }

    /**
     * Picks the best supported native audio track index by walking the ordered priority list (1, 2, 3...).
     * Returns -1 if no prioritized language matches any supported track.
     */
    public static int bestTrackIndex(Context context, VideoMetadata metadata) {
        if (context == null || metadata == null) return -1;
        int nb = metadata.getAudioTrackNb();
        if (nb <= 0) return -1;
        List<String> priority = priorityList(context);
        for (String tag : priority) {
            LangSpec spec = CATALOG.get(tag);
            if (spec == null) continue;
            for (int i = 0; i < nb; i++) {
                VideoMetadata.AudioTrack track = metadata.getAudioTrack(i);
                if (track == null || !track.supported) continue;
                if (matchesTrack(spec, track.language, track.name)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean matchesTrack(LangSpec spec, String rawLang, String rawName) {
        if (rawLang != null && !rawLang.isBlank()) {
            String base = rawLang.trim().toLowerCase(Locale.ROOT).split("[-_\\s]", 2)[0];
            if (spec.codes().contains(base)) return true;
            String lowerLang = rawLang.trim().toLowerCase(Locale.ROOT);
            for (String token : spec.nameTokens()) {
                if (lowerLang.contains(token)) return true;
            }
        }
        if (rawName != null && !rawName.isBlank()) {
            String lowerName = rawName.trim().toLowerCase(Locale.ROOT);
            for (String token : spec.nameTokens()) {
                if (lowerName.contains(token)) return true;
            }
        }
        return false;
    }

    /** Interactive TV dialog allowing users to order audio languages sequentially (1, 2, 3...), add, or remove. */
    public static void choose(Context context, Runnable onChange) {
        var ordered = new ArrayList<>(priorityList(context));
        boolean rtl = CinemaUi.arabic(context);

        var container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        container.setClipChildren(false);
        container.setClipToPadding(false);
        int pad = CinemaUi.dp(context, 18);
        container.setPaddingRelative(pad, CinemaUi.dp(context, 10), pad, CinemaUi.dp(context, 12));

        var hint = CinemaUi.text(context, 13, false);
        hint.setTextColor(0xffc9beaa);
        hint.setText(CinemaUi.label(context,
                "رتّب لغات الصوت بالتسلسل (1، 2، 3…). عند التشغيل سيختار التطبيق أول لغة متوفرة تلقائياً في الأفلام والمسلسلات.",
                "Order audio languages sequentially (1, 2, 3…). Playback automatically picks the first available match for movies and series."));
        var hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.bottomMargin = CinemaUi.dp(context, 10);
        container.addView(hint, hintLp);

        var rowsHost = new LinearLayout(context);
        rowsHost.setOrientation(LinearLayout.VERTICAL);
        rowsHost.setClipChildren(false);
        rowsHost.setClipToPadding(false);
        container.addView(rowsHost, new LinearLayout.LayoutParams(-1, -2));

        var scroll = new ScrollView(context);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.addView(container, new ScrollView.LayoutParams(-1, -2));

        var dialogRef = new androidx.appcompat.app.AlertDialog[1];
        Runnable[] render = new Runnable[1];
        int[] focusTargetIndex = {0};

        render[0] = () -> {
            rowsHost.removeAllViews();
            for (int i = 0; i < ordered.size(); i++) {
                final int index = i;
                String tag = ordered.get(i);
                var row = createPriorityRow(context, index + 1, displayName(context, tag), () -> {
                    var options = new ArrayList<String>();
                    var actions = new ArrayList<Runnable>();
                    if (index > 0) {
                        options.add("▲  " + context.getString(R.string.cinema_move_up));
                        actions.add(() -> {
                            Collections.swap(ordered, index, index - 1);
                            savePriorityList(context, ordered);
                            focusTargetIndex[0] = index - 1;
                            render[0].run();
                            if (onChange != null) onChange.run();
                        });
                    }
                    if (index < ordered.size() - 1) {
                        options.add("▼  " + context.getString(R.string.cinema_move_down));
                        actions.add(() -> {
                            Collections.swap(ordered, index, index + 1);
                            savePriorityList(context, ordered);
                            focusTargetIndex[0] = index + 1;
                            render[0].run();
                            if (onChange != null) onChange.run();
                        });
                    }
                    if (ordered.size() > 1) {
                        options.add("✕  " + CinemaUi.label(context, "إزالة من الأولوية", "Remove from priority"));
                        actions.add(() -> {
                            ordered.remove(index);
                            savePriorityList(context, ordered);
                            focusTargetIndex[0] = Math.min(index, ordered.size() - 1);
                            render[0].run();
                            if (onChange != null) onChange.run();
                        });
                    }
                    if (options.isEmpty()) return;
                    CinemaUi.dialog(context)
                            .setTitle((index + 1) + ". " + displayName(context, tag))
                            .setItems(options.toArray(new String[0]), (d, which) -> actions.get(which).run())
                            .setNegativeButton(R.string.addon_back, null)
                            .show();
                });
                rowsHost.addView(row);
            }

            // "+ Add language" row if any language remains available
            var remaining = new ArrayList<String>();
            for (String tag : CATALOG.keySet()) {
                if (!ordered.contains(tag)) remaining.add(tag);
            }
            if (!remaining.isEmpty()) {
                var addBtn = createAddLanguageRow(context, () -> {
                    String[] names = new String[remaining.size()];
                    for (int n = 0; n < remaining.size(); n++) {
                        names[n] = displayName(context, remaining.get(n));
                    }
                    CinemaUi.dialog(context)
                            .setTitle(CinemaUi.label(context, "إضافة لغة للأولوية", "Add Language to Priority"))
                            .setItems(names, (d, which) -> {
                                ordered.add(remaining.get(which));
                                savePriorityList(context, ordered);
                                focusTargetIndex[0] = ordered.size() - 1;
                                render[0].run();
                                if (onChange != null) onChange.run();
                            })
                            .setNegativeButton(R.string.addon_back, null)
                            .show();
                });
                rowsHost.addView(addBtn);
            }

            if (dialogRef[0] != null && dialogRef[0].isShowing() && rowsHost.getChildCount() > 0) {
                int idx = Math.max(0, Math.min(focusTargetIndex[0], rowsHost.getChildCount() - 1));
                rowsHost.getChildAt(idx).requestFocus();
            }
        };

        render[0].run();
        var dialog = CinemaUi.dialog(context)
                .setTitle(R.string.addon_preferred_audio)
                .setView(scroll)
                .setNegativeButton(R.string.addon_back, null)
                .create();
        dialogRef[0] = dialog;
        dialog.show();
        if (rowsHost.getChildCount() > 0) {
            CinemaUi.focusDialogControl(dialog, rowsHost.getChildAt(0));
        }
    }

    private static View createPriorityRow(Context context, int rank, String languageName, Runnable onClick) {
        var row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(CinemaUi.dp(context, 54));
        row.setPaddingRelative(CinemaUi.dp(context, 16), CinemaUi.dp(context, 8),
                CinemaUi.dp(context, 16), CinemaUi.dp(context, 8));
        row.setFocusable(true);
        row.setClickable(true);
        var bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused},
                CinemaUi.rounded(0xff392b18, CinemaUi.dp(context, 12), CinemaTokens.GOLD));
        bg.addState(new int[]{},
                CinemaUi.rounded(0xff24211d, CinemaUi.dp(context, 12), 0xff54432c));
        row.setBackground(bg);

        var badge = CinemaUi.text(context, 14, true);
        badge.setText(String.valueOf(rank));
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(0xff141517);
        badge.setBackground(CinemaUi.rounded(CinemaTokens.GOLD, CinemaUi.dp(context, 14), 0));
        int sz = CinemaUi.dp(context, 28);
        var badgeLp = new LinearLayout.LayoutParams(sz, sz);
        badgeLp.setMarginEnd(CinemaUi.dp(context, 14));
        row.addView(badge, badgeLp);

        var title = CinemaUi.text(context, 16, true);
        title.setText(languageName);
        title.setTextColor(Color.WHITE);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        var reorderHint = CinemaUi.text(context, 13, false);
        reorderHint.setText("⇅");
        reorderHint.setTextColor(CinemaTokens.GOLD);
        row.addView(reorderHint, new LinearLayout.LayoutParams(-2, -2));

        row.setOnClickListener(v -> onClick.run());
        var lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = CinemaUi.dp(context, 8);
        row.setLayoutParams(lp);
        return row;
    }

    private static View createAddLanguageRow(Context context, Runnable onClick) {
        var btn = CinemaUi.text(context, 15, true);
        btn.setText("+  " + CinemaUi.label(context, "إضافة لغة أخرى بالتسلسل", "Add Another Language"));
        btn.setTextColor(CinemaTokens.GOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setMinimumHeight(CinemaUi.dp(context, 50));
        btn.setPaddingRelative(CinemaUi.dp(context, 16), CinemaUi.dp(context, 8),
                CinemaUi.dp(context, 16), CinemaUi.dp(context, 8));
        btn.setFocusable(true);
        btn.setClickable(true);
        var bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused},
                CinemaUi.rounded(0xff392b18, CinemaUi.dp(context, 12), CinemaTokens.GOLD));
        bg.addState(new int[]{},
                CinemaUi.rounded(0xff1f1c18, CinemaUi.dp(context, 12), 0xff6e5730));
        btn.setBackground(bg);
        btn.setOnClickListener(v -> onClick.run());
        var lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = CinemaUi.dp(context, 4);
        btn.setLayoutParams(lp);
        return btn;
    }

    private static String normalizeTag(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (CATALOG.containsKey(lower)) return lower;
        for (LangSpec spec : CATALOG.values()) {
            if (spec.codes().contains(lower)) return spec.tag();
        }
        return "";
    }
}

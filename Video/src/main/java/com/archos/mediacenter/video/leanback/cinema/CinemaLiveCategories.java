package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;

import com.meedx.providers.LiveTvPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Splits compound M3U group-title values (e.g. "Culture;Music;Religious"),
 * normalizes and translates categories to Arabic/English, and persists the
 * last selected category per Live TV / IPTV screen.
 */
final class CinemaLiveCategories {
    static final String ALL = "";
    static final String UNDEFINED = "undefined";
    private static final String PREF_LIVE_CATEGORY = "live_tv_selected_category";
    private static final String PREF_IPTV_CATEGORY = "iptv_selected_category";

    private record Label(String ar, String en) {}

    private static final Map<String, Label> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("general", new Label("عام", "General"));
        LABELS.put("news", new Label("أخبار", "News"));
        LABELS.put("sports", new Label("رياضة", "Sports"));
        LABELS.put("entertainment", new Label("ترفيه", "Entertainment"));
        LABELS.put("kids", new Label("أطفال", "Kids"));
        LABELS.put("documentary", new Label("وثائقي", "Documentary"));
        LABELS.put("movies", new Label("أفلام", "Movies"));
        LABELS.put("music", new Label("موسيقى", "Music"));
        LABELS.put("religious", new Label("ديني", "Religious"));
        LABELS.put("culture", new Label("ثقافة", "Culture"));
        LABELS.put("family", new Label("عائلي", "Family"));
        LABELS.put("education", new Label("تعليمي", "Education"));
        LABELS.put("business", new Label("أعمال", "Business"));
        LABELS.put("comedy", new Label("كوميديا", "Comedy"));
        LABELS.put("lifestyle", new Label("لايف ستايل", "Lifestyle"));
        LABELS.put("science", new Label("علوم", "Science"));
        LABELS.put("series", new Label("مسلسلات", "Series"));
        LABELS.put("classic", new Label("كلاسيكي", "Classic"));
        LABELS.put("cooking", new Label("طبخ", "Cooking"));
        LABELS.put("travel", new Label("سفر", "Travel"));
        LABELS.put("weather", new Label("طقس", "Weather"));
        LABELS.put("animation", new Label("أنمي وكرتون", "Animation"));
        LABELS.put("legislative", new Label("برلماني", "Legislative"));
        LABELS.put("shop", new Label("تسوق", "Shop"));
        LABELS.put("outdoor", new Label("طبيعة ورحلات", "Outdoor"));
        LABELS.put("auto", new Label("سيارات", "Auto"));
        LABELS.put("public", new Label("حكومي ورسمي", "Public"));
        LABELS.put("relax", new Label("استرخاء", "Relax"));
        LABELS.put(UNDEFINED, new Label("غير مصنّف", "Undefined"));
    }

    private CinemaLiveCategories() {}

    static List<String> parseKeys(String rawGroup) {
        if (rawGroup == null || rawGroup.isBlank()) {
            return List.of(UNDEFINED);
        }
        var keys = new LinkedHashSet<String>();
        for (String part : rawGroup.split("[;,|/]+")) {
            String key = canonicalKey(part);
            if (!key.isEmpty()) keys.add(key);
        }
        if (keys.isEmpty()) keys.add(UNDEFINED);
        return new ArrayList<>(keys);
    }

    static String canonicalKey(String raw) {
        if (raw == null) return UNDEFINED;
        String clean = raw.trim().replaceAll("\\s+", " ");
        if (clean.isEmpty()) return "";
        String lower = clean.toLowerCase(Locale.ROOT);
        if (LABELS.containsKey(lower)) return lower;
        return switch (lower) {
            case "sport", "sports & fitness", "football", "soccer" -> "sports";
            case "movie", "film", "films", "cinema" -> "movies";
            case "kid", "children", "child", "junior", "youth" -> "kids";
            case "anime", "cartoon", "cartoons", "animated" -> "animation";
            case "doc", "docs", "documentaries", "history" -> "documentary";
            case "religion", "islamic", "christian", "faith", "spiritual", "quran" -> "religious";
            case "edu", "educational", "learning", "school" -> "education";
            case "finance", "economy", "economic", "markets" -> "business";
            case "food", "culinary", "kitchen" -> "cooking";
            case "drama", "tv shows", "shows", "soap" -> "series";
            case "shopping", "store", "home shopping" -> "shop";
            case "nature", "outdoors", "wildlife", "hunting", "fishing" -> "outdoor";
            case "cars", "automotive", "motor", "motorsport" -> "auto";
            case "parliament", "government", "politics" -> "legislative";
            case "cultural", "arts", "art" -> "culture";
            case "sci-fi", "technology", "tech", "scientific" -> "science";
            case "classics", "retro", "nostalgia" -> "classic";
            case "other", "misc", "miscellaneous", "uncategorized", "unknown", "none", "n/a", "unclassified" -> UNDEFINED;
            default -> lower;
        };
    }

    static boolean matches(String rawGroup, String selectedKey) {
        if (selectedKey == null || selectedKey.isEmpty()) return true;
        return parseKeys(rawGroup).contains(selectedKey);
    }

    static String displayName(Context context, String canonicalKey) {
        boolean ar = CinemaUi.arabic(context);
        if (canonicalKey == null || canonicalKey.isEmpty()) {
            return ar ? "الكل" : "All";
        }
        Label known = LABELS.get(canonicalKey);
        if (known != null) {
            return ar ? known.ar() : known.en();
        }
        return titleCase(canonicalKey);
    }

    static String savedCategory(Context context, boolean custom) {
        String key = LiveTvPreferences.store(context)
                .getString(custom ? PREF_IPTV_CATEGORY : PREF_LIVE_CATEGORY, ALL);
        return key == null ? ALL : key.trim().toLowerCase(Locale.ROOT);
    }

    static void saveCategory(Context context, boolean custom, String key) {
        String value = key == null ? ALL : key.trim().toLowerCase(Locale.ROOT);
        LiveTvPreferences.store(context).edit()
                .putString(custom ? PREF_IPTV_CATEGORY : PREF_LIVE_CATEGORY, value)
                .apply();
    }

    private static String titleCase(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder(value.length());
        boolean capitalizeNext = true;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch) || ch == '-' || ch == '_') {
                out.append(ch == '_' ? ' ' : ch);
                capitalizeNext = true;
            } else if (capitalizeNext) {
                out.append(Character.toTitleCase(ch));
                capitalizeNext = false;
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }
}

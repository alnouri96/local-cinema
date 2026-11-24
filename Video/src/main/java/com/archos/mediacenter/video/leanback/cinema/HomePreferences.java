package com.archos.mediacenter.video.leanback.cinema;

import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Validate persisted home row & section order so upgrades never silently lose a row. */
public final class HomePreferences {
    public static final String ORDER = "cinema_home_order";
    public static final String SECTION_ORDER = "cinema_home_section_order";
    public static final String KNOWN_LIB_SECTIONS = "cinema_home_known_libs";
    public static final String HAS_NON_MOBILE_LIB = "cinema_home_has_non_mobile_lib";
    public static final String COUNT = "cinema_poster_columns";
    public static final String SIZE = "cinema_poster_size";
    public static final String CLOCK = "cinema_clock";
    public static final String CLOCK_SIZE = "cinema_clock_size";
    public static final String CLOCK_OPACITY = "cinema_clock_opacity";
    public static final String CLOCK_GREETING = "cinema_clock_greeting";
    public static final String CLOCK_WEATHER = "cinema_clock_weather";
    public static final String MOTION = "cinema_motion";
    public static final String BACKDROP = "cinema_backdrop_quality";

    public static final String KEY_CONTINUE = "row:1";
    public static final String KEY_RECENT_MOVIES = "row:3";
    public static final String KEY_RECENT_SHOWS = "row:4";
    public static final String KEY_TRAILER_TRENDING = "trailer:0";
    public static final String KEY_TRAILER_NEW = "trailer:1";
    public static final String KEY_TRAILER_COMING = "trailer:2";
    public static final String LIB_PREFIX = "lib:";
    public static final String VIS_PREFIX = "cinema_section_vis_";

    private HomePreferences() {}

    public static List<Integer> order(SharedPreferences prefs) {
        List<Integer> result = new ArrayList<>();
        if (!prefs.getBoolean("cinema_watched_row_v6", false)) {
            prefs.edit()
                    .putString(ORDER, "1,3,4")
                    .putBoolean("cinema_row_1", true)
                    .putBoolean("cinema_row_6", false)
                    .putBoolean("cinema_row_3", true)
                    .putBoolean("cinema_row_4", true)
                    .putBoolean("cinema_watched_row_v6", true)
                    .apply();
        }
        for (String token : prefs.getString(ORDER, "1,3,4").split(",")) {
            try {
                int id = Integer.parseInt(token);
                if ((id == 1 || id == 3 || id == 4) && !result.contains(id)) result.add(id);
            } catch (NumberFormatException ignored) {}
        }
        for (int id : new int[]{1, 3, 4}) if (!result.contains(id)) result.add(id);
        return result;
    }

    public static void saveOrder(SharedPreferences prefs, List<Integer> ids) {
        StringBuilder s = new StringBuilder();
        for (int id : ids) {
            if (s.length() > 0) s.append(',');
            s.append(id);
        }
        prefs.edit().putString(ORDER, s.toString()).apply();
    }

    public static String coreSectionKey(int rowId) {
        return "row:" + rowId;
    }

    public static String libSectionKey(String name) {
        String clean = name == null ? "" : name.trim();
        return LIB_PREFIX + clean;
    }

    public static String trailerSectionKey(int index) {
        return "trailer:" + index;
    }

    public static boolean isTrailerKey(String key) {
        return key != null && key.startsWith("trailer:");
    }

    public static int trailerIndex(String key) {
        if (!isTrailerKey(key)) return -1;
        try {
            return Integer.parseInt(key.substring("trailer:".length()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public static boolean isLibKey(String key) {
        return key != null && key.startsWith(LIB_PREFIX);
    }

    public static String libNameFromKey(String key) {
        return isLibKey(key) ? key.substring(LIB_PREFIX.length()) : "";
    }

    public static boolean isSectionVisible(SharedPreferences prefs, String sectionKey) {
        if (sectionKey == null || sectionKey.isBlank()) return true;
        if (sectionKey.startsWith("row:")) {
            return prefs.getBoolean("cinema_row_" + sectionKey.substring(4), true);
        }
        return prefs.getBoolean(VIS_PREFIX + sectionKey, true);
    }

    public static void setSectionVisible(SharedPreferences prefs, String sectionKey, boolean visible) {
        if (sectionKey == null || sectionKey.isBlank()) return;
        if (sectionKey.startsWith("row:")) {
            prefs.edit().putBoolean("cinema_row_" + sectionKey.substring(4), visible).apply();
        } else {
            prefs.edit().putBoolean(VIS_PREFIX + sectionKey, visible).apply();
        }
    }

    public static void saveKnownLibrarySections(SharedPreferences prefs, List<String> names, boolean hasNonMobileLibrary) {
        JSONArray arr = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        if (names != null) {
            for (String n : names) {
                if (n != null && !n.trim().isEmpty() && seen.add(n.trim())) {
                    arr.put(n.trim());
                }
            }
        }
        prefs.edit()
                .putString(KNOWN_LIB_SECTIONS, arr.toString())
                .putBoolean(HAS_NON_MOBILE_LIB, hasNonMobileLibrary)
                .apply();
    }

    public static List<String> knownLibrarySections(SharedPreferences prefs) {
        List<String> out = new ArrayList<>();
        String raw = prefs.getString(KNOWN_LIB_SECTIONS, null);
        if (raw == null || raw.isBlank()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty() && !out.contains(s)) out.add(s);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static boolean hasNonMobileLibrary(SharedPreferences prefs) {
        return prefs.getBoolean(HAS_NON_MOBILE_LIB, false);
    }

    /**
     * Returns the default list of available section keys for settings UI, preserving the current
     * Home Screen default layout (Continue Watching -> Movies/Series or Library Folders -> Trailers).
     */
    public static List<String> defaultAvailableKeys(SharedPreferences prefs) {
        List<String> keys = new ArrayList<>();
        boolean hasNonMobile = hasNonMobileLibrary(prefs);
        List<String> libs = knownLibrarySections(prefs);
        keys.add(KEY_CONTINUE);
        if (!hasNonMobile) {
            keys.add(KEY_RECENT_MOVIES);
            keys.add(KEY_RECENT_SHOWS);
        }
        for (String lib : libs) {
            String lk = libSectionKey(lib);
            if (!keys.contains(lk)) keys.add(lk);
        }
        keys.add(KEY_TRAILER_TRENDING);
        keys.add(KEY_TRAILER_NEW);
        keys.add(KEY_TRAILER_COMING);
        return keys;
    }

    /**
     * Resolves persisted section priority order across all currently available sections.
     * Preserves exact default ordering when untouched; never drops newly added folders or sections.
     */
    public static List<String> sectionOrder(SharedPreferences prefs, List<String> availableKeys) {
        List<String> result = new ArrayList<>();
        Set<String> availableSet = new LinkedHashSet<>(availableKeys);
        String raw = prefs.getString(SECTION_ORDER, null);
        if (raw != null && !raw.isBlank()) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    String k = arr.optString(i, "");
                    if (availableSet.contains(k) && !result.contains(k)) {
                        result.add(k);
                    }
                }
            } catch (Exception ignored) {}
        } else {
            // Migrate legacy integer core order (1,3,4) cleanly before appending remaining defaults.
            for (int id : order(prefs)) {
                String k = coreSectionKey(id);
                if (availableSet.contains(k) && !result.contains(k)) {
                    result.add(k);
                }
            }
        }
        for (String k : availableKeys) {
            if (!result.contains(k)) result.add(k);
        }
        return result;
    }

    public static void saveSectionOrder(SharedPreferences prefs, List<String> orderedKeys) {
        JSONArray arr = new JSONArray();
        List<Integer> coreIds = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (orderedKeys != null) {
            for (String k : orderedKeys) {
                if (k == null || k.isBlank() || !seen.add(k)) continue;
                arr.put(k);
                if (k.startsWith("row:")) {
                    try {
                        int id = Integer.parseInt(k.substring(4));
                        if ((id == 1 || id == 3 || id == 4) && !coreIds.contains(id)) coreIds.add(id);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        SharedPreferences.Editor ed = prefs.edit().putString(SECTION_ORDER, arr.toString());
        if (!coreIds.isEmpty()) {
            for (int id : new int[]{1, 3, 4}) if (!coreIds.contains(id)) coreIds.add(id);
            StringBuilder s = new StringBuilder();
            for (int id : coreIds) {
                if (s.length() > 0) s.append(',');
                s.append(id);
            }
            ed.putString(ORDER, s.toString());
        }
        ed.apply();
    }

    public static String layoutSignature(SharedPreferences prefs) {
        List<String> keys = defaultAvailableKeys(prefs);
        List<String> ord = sectionOrder(prefs, keys);
        StringBuilder sb = new StringBuilder();
        sb.append(ord.toString());
        for (String k : ord) {
            sb.append('|').append(k).append('=').append(isSectionVisible(prefs, k));
        }
        sb.append('|').append(prefs.getInt(COUNT, 6)).append('|').append(prefs.getString(SIZE, "medium"));
        return sb.toString();
    }

    public static void reset(SharedPreferences prefs) {
        SharedPreferences.Editor e = prefs.edit()
                .remove(ORDER)
                .remove(SECTION_ORDER)
                .remove(COUNT)
                .remove(SIZE);
        for (int id : new int[]{1, 2, 3, 4, 6}) e.remove("cinema_row_" + id);
        for (String key : prefs.getAll().keySet()) {
            if (key != null && key.startsWith(VIS_PREFIX)) {
                e.remove(key);
            }
        }
        e.putBoolean("cinema_row_6", false).putBoolean("cinema_watched_row_v6", true).apply();
    }
}

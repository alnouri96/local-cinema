package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Persists custom user tile order for the 6-column Add-ons hub grid. */
final class CinemaAddonsOrderStore {
    private static final String PREFS = "cinema_addons_order";
    private static final String KEY_ORDER = "tile_order_json";

    private CinemaAddonsOrderStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<String> load(Context context) {
        var result = new ArrayList<String>();
        try {
            var arr = new JSONArray(prefs(context).getString(KEY_ORDER, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String key = arr.optString(i, "");
                if (!key.isBlank() && !result.contains(key)) result.add(key);
            }
        } catch (Exception ignored) {}
        return result;
    }

    static void save(Context context, List<String> keys) {
        var arr = new JSONArray();
        for (String key : keys) {
            if (key != null && !key.isBlank()) arr.put(key);
        }
        prefs(context).edit().putString(KEY_ORDER, arr.toString()).apply();
    }

    static <T> List<T> applyOrder(Context context, List<T> items, Function<T, String> keyOf) {
        List<String> saved = load(context);
        if (saved.isEmpty()) return new ArrayList<>(items);
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < saved.size(); i++) rank.put(saved.get(i), i);
        List<T> ordered = new ArrayList<>(items);
        ordered.sort(Comparator.comparingInt(item -> rank.getOrDefault(keyOf.apply(item), Integer.MAX_VALUE)));
        return ordered;
    }
}

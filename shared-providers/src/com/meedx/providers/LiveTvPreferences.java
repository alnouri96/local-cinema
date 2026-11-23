package com.meedx.providers;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

public final class LiveTvPreferences {
    public static final String STORE = "cinema_live_channels";
    public static final String COUNTRY_FIELD = "live_tv_country";
    public static final String FAVORITE_COUNTRIES_FIELD = "favorite_countries";
    public static final String PREFS_PACKET = "LIVE_TV_COUNTRIES_SYNC";
    public static final String DEFAULT_COUNTRY = "IQ";
    private static final String[] COUNTRIES = Locale.getISOCountries();

    private LiveTvPreferences() {}

    public static SharedPreferences store(Context context) {
        return context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    public static String country(Context context) {
        String stored = store(context).getString(COUNTRY_FIELD, DEFAULT_COUNTRY);
        return valid(stored) ? stored : DEFAULT_COUNTRY;
    }

    public static boolean saveCountry(Context context, String code) {
        if (!valid(code)) return false;
        store(context).edit().putString(COUNTRY_FIELD, code).apply();
        return true;
    }

    public static java.util.Set<String> favoriteCountries(Context context) {
        var raw = store(context).getStringSet(FAVORITE_COUNTRIES_FIELD, null);
        var out = new java.util.LinkedHashSet<String>();
        if (raw != null) {
            for (String code : raw) {
                if (valid(code)) out.add(code);
            }
        }
        if (out.isEmpty()) out.add(country(context));
        return out;
    }

    public static void saveFavoriteCountries(Context context, java.util.Collection<String> codes) {
        var clean = new java.util.LinkedHashSet<String>();
        if (codes != null) {
            for (String code : codes) {
                if (valid(code)) clean.add(code);
            }
        }
        var editor = store(context).edit().putStringSet(FAVORITE_COUNTRIES_FIELD, clean);
        if (!clean.isEmpty() && !clean.contains(country(context))) {
            editor.putString(COUNTRY_FIELD, clean.iterator().next());
        }
        editor.apply();
    }

    public static org.json.JSONArray exportFavoriteCountriesJson(Context context) {
        var arr = new org.json.JSONArray();
        for (String code : favoriteCountries(context)) arr.put(code);
        return arr;
    }

    public static boolean importFavoriteCountriesJson(Context context, org.json.JSONArray arr) {
        if (context == null || arr == null) return false;
        var clean = new java.util.LinkedHashSet<String>();
        for (int i = 0; i < arr.length(); i++) {
            String code = arr.optString(i, "").trim().toUpperCase(Locale.ROOT);
            if (valid(code)) clean.add(code);
        }
        if (clean.isEmpty()) return false;
        saveFavoriteCountries(context, clean);
        return true;
    }

    private static boolean valid(String code) {
        return Arrays.asList(COUNTRIES).contains(code);
    }

    public static String countryName(Context context, String code) {
        Locale language = context.getResources().getConfiguration().getLocales().get(0);
        return new Locale("", code).getDisplayCountry(language);
    }

    public static String[] countryCodes(Context context) {
        var copy = COUNTRIES.clone();
        Locale language = context.getResources().getConfiguration().getLocales().get(0);
        var order = java.text.Collator.getInstance(language);
        Arrays.sort(copy, Comparator.comparing(code -> countryName(context, code), order));
        var choices = new java.util.LinkedHashSet<>(java.util.List.of(
                country(context), DEFAULT_COUNTRY, "SA", "AE", "EG", "JO", "KW", "LB", "QA", "OM", "BH"));
        choices.addAll(Arrays.asList(copy));
        return choices.toArray(new String[0]);
    }
}

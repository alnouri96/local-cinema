package com.meedx.providers;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

public final class LiveTvPreferences {
    public static final String STORE = "cinema_live_channels";
    public static final String COUNTRY_FIELD = "live_tv_country";
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

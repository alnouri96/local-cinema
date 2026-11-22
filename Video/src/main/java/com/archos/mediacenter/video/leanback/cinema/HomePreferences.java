package com.archos.mediacenter.video.leanback.cinema;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/** Validate persisted order so upgrades never silently lose a row. */
public final class HomePreferences {
    public static final String ORDER = "cinema_home_order";
    public static final String COUNT = "cinema_poster_columns";
    public static final String SIZE = "cinema_poster_size";
    public static final String CLOCK = "cinema_clock";
    public static final String CLOCK_SIZE = "cinema_clock_size";
    public static final String CLOCK_OPACITY = "cinema_clock_opacity";
    public static final String CLOCK_GREETING = "cinema_clock_greeting";
    public static final String CLOCK_WEATHER = "cinema_clock_weather";
    public static final String MOTION = "cinema_motion";
    public static final String BACKDROP = "cinema_backdrop_quality";
    private HomePreferences() {}
    public static List<Integer> order(SharedPreferences prefs) {
        List<Integer> result = new ArrayList<>();
        if(!prefs.getBoolean("cinema_watched_row_v6",false))prefs.edit().putString(ORDER,"1,3,4").putBoolean("cinema_row_1",true).putBoolean("cinema_row_6",false).putBoolean("cinema_row_3",true).putBoolean("cinema_row_4",true).putBoolean("cinema_watched_row_v6",true).apply();
        for (String token : prefs.getString(ORDER, "1,3,4").split(",")) {
            try { int id = Integer.parseInt(token); if ((id==1||id==3||id==4) && !result.contains(id)) result.add(id); }
            catch (NumberFormatException ignored) { }
        }
        for (int id : new int[]{1,3,4}) if (!result.contains(id)) result.add(id);
        return result;
    }
    public static void saveOrder(SharedPreferences prefs, List<Integer> ids) {
        StringBuilder s = new StringBuilder();
        for (int id : ids) { if (s.length() > 0) s.append(','); s.append(id); }
        prefs.edit().putString(ORDER, s.toString()).apply();
    }
    public static void reset(SharedPreferences prefs) {
        SharedPreferences.Editor e = prefs.edit().remove(ORDER).remove(COUNT).remove(SIZE);
        for (int id : new int[]{1,2,3,4,6}) e.remove("cinema_row_" + id);
        e.putBoolean("cinema_row_6", false).putBoolean("cinema_watched_row_v6", true).apply();
    }
}

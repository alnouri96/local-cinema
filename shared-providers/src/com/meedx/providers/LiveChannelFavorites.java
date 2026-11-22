package com.meedx.providers;

import android.content.Context;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class LiveChannelFavorites {
    public static final String FIELD = "favorite_channels";
    public static final String PACKET = "FAVORITE_CHANNELS";
    private static final int MAX_RECORDS = 2048;
    private static final long MAX_REVISION = 253402300799999L;
    private static final Pattern CHANNEL_KEY = Pattern.compile("[A-Za-z0-9_-]{43}");

    private LiveChannelFavorites() {}

    /** Sync opaque IDs, even for private playlists with credentials in their URLs. */
    public static String key(String tvgId, String url) {
        int qualifier = tvgId.indexOf('@');
        String id = (qualifier < 0 ? tvgId : tvgId.substring(0, qualifier)).strip();
        String identity = id.isEmpty() ? "url:" + url : "tvg:" + id.toLowerCase(java.util.Locale.ROOT);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static synchronized JSONObject snapshot(Context context) {
        try {
            return new JSONObject(LiveTvPreferences.store(context).getString(FIELD, "{}"));
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    public static Map<String, Long> active(Context context) {
        JSONObject records = snapshot(context);
        var favorites = new HashMap<String, Long>();
        var keys = records.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            long revision = records.optLong(id);
            if (revision > 0) favorites.put(id, revision);
        }
        return favorites;
    }

    public static synchronized void set(Context context, String key, boolean favorite) {
        JSONObject records = snapshot(context);
        long next = Math.min(MAX_REVISION, Math.max(System.currentTimeMillis(), Math.abs(records.optLong(key)) + 1));
        try {
            records.put(key, favorite ? next : -next);
            save(context, records);
        } catch (JSONException ignored) {}
    }

    /** Negative revisions retain removals so stale snapshots cannot restore a removed favorite. */
    public static synchronized boolean merge(Context context, JSONObject incoming) {
        if (incoming == null || incoming.length() == 0 || incoming.length() > MAX_RECORDS) return false;
        JSONObject records = snapshot(context);
        boolean changed = false;
        var keys = incoming.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            long candidate = incoming.optLong(id);
            if (!CHANNEL_KEY.matcher(id).matches() || candidate == 0
                    || candidate < -MAX_REVISION || candidate > MAX_REVISION) continue;
            long current = records.optLong(id);
            long candidateTime = Math.abs(candidate);
            long currentTime = Math.abs(current);
            if (candidateTime > currentTime || (candidateTime == currentTime && candidate < current)) {
                try {
                    records.put(id, candidate);
                    changed = true;
                } catch (JSONException ignored) {}
            }
        }
        if (changed) save(context, records);
        return changed;
    }

    private static void save(Context context, JSONObject records) {
        if (records.length() > MAX_RECORDS) {
            String[] keys = new String[records.length()];
            var entries = records.keys();
            for (int i = 0; i < keys.length; i++) keys[i] = entries.next();
            Arrays.sort(keys, java.util.Comparator.<String>comparingLong(id -> Math.abs(records.optLong(id)))
                    .reversed().thenComparing(id -> id));
            for (int i = MAX_RECORDS; i < keys.length; i++) records.remove(keys[i]);
        }
        LiveTvPreferences.store(context).edit().putString(FIELD, records.toString()).apply();
    }
}

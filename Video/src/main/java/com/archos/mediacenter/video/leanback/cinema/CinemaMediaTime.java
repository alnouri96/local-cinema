package com.archos.mediacenter.video.leanback.cinema;

import android.net.Uri;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** File milliseconds remain distinct from provider runtime minutes; also caches live playback progress before async DB writes finish. */
public final class CinemaMediaTime {
    private static final Map<String, Long> MEASURED = new ConcurrentHashMap<>();
    private static final Map<Long, Long> DURATION_BY_ID = new ConcurrentHashMap<>();
    private static final Map<String, Long> RESUME_BY_PATH = new ConcurrentHashMap<>();
    private static final Map<Long, Long> RESUME_BY_ID = new ConcurrentHashMap<>();

    private static String normalize(String path) {
        if (path == null || path.isEmpty()) return null;
        if (path.startsWith("file://")) {
            String decoded = Uri.parse(path).getPath();
            if (decoded != null && !decoded.isEmpty()) return decoded;
        }
        return path;
    }

    public static void remember(String path, long milliseconds) {
        String key = normalize(path);
        if (key != null && milliseconds > 0) {
            if (MEASURED.size() >= 256) MEASURED.clear();
            MEASURED.put(key, milliseconds);
        }
    }

    public static void rememberState(long id, Uri uri, long durationMs, long resumeMs) {
        String path = uri == null ? null : (uri.getPath() != null ? uri.getPath() : uri.toString());
        if (durationMs > 0) {
            remember(path, durationMs);
            if (id > 0) {
                if (DURATION_BY_ID.size() >= 256) DURATION_BY_ID.clear();
                DURATION_BY_ID.put(id, durationMs);
            }
        }
        String key = normalize(path);
        if (key != null) {
            if (RESUME_BY_PATH.size() >= 256) RESUME_BY_PATH.clear();
            RESUME_BY_PATH.put(key, resumeMs);
        }
        if (id > 0) {
            if (RESUME_BY_ID.size() >= 256) RESUME_BY_ID.clear();
            RESUME_BY_ID.put(id, resumeMs);
        }
    }

    public static void clearResume(long id, String path, Uri uri) {
        if (id > 0) {
            RESUME_BY_ID.put(id, -1L);
        }
        String k1 = normalize(path);
        if (k1 != null) RESUME_BY_PATH.put(k1, -1L);
        if (uri != null) {
            String k2 = normalize(uri.getPath() != null ? uri.getPath() : uri.toString());
            if (k2 != null) RESUME_BY_PATH.put(k2, -1L);
        }
    }

    public static long known(String path, long fallback) {
        String key = normalize(path);
        if (key != null) {
            Long v = MEASURED.get(key);
            if (v != null && v > 0) return v;
        }
        return fallback;
    }

    public static long known(long id, String path, long fallback) {
        if (id > 0) {
            Long v = DURATION_BY_ID.get(id);
            if (v != null && v > 0) return v;
        }
        return known(path, fallback);
    }

    public static long knownResume(long id, String path, long fallback) {
        if (id > 0) {
            Long v = RESUME_BY_ID.get(id);
            if (v != null) return v;
        }
        String key = normalize(path);
        if (key != null) {
            Long v = RESUME_BY_PATH.get(key);
            if (v != null) return v;
        }
        return fallback;
    }

    public static String format(long milliseconds) {
        if (milliseconds <= 0) return "";
        long seconds = milliseconds / 1000, hours = seconds / 3600, minutes = seconds / 60 % 60, remainder = seconds % 60;
        return hours > 0
                ? String.format(Locale.US, "%dh %02dm %02ds", hours, minutes, remainder)
                : String.format(Locale.US, "%dm %02ds", minutes, remainder);
    }

    public static String formatRemaining(long totalMs, long resumeMs) {
        if (resumeMs <= 0 || totalMs <= 0) return null;
        long left = totalMs - resumeMs;
        if (left <= 0) return null;
        long mins = Math.max(1, left / 60000);
        return mins >= 60
                ? String.format(Locale.US, "%dh %dm", mins / 60, mins % 60)
                : String.format(Locale.US, "%dm", mins);
    }
}

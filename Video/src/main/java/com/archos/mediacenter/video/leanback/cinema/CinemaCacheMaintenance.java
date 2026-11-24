package com.archos.mediacenter.video.leanback.cinema;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.archos.mediacenter.video.BuildConfig;
import com.meedx.updates.AppUpdates;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TV Safe Cache Cleaner & Smart Adaptive Self-Maintenance engine.
 *
 * Root causes addressed:
 * - Orphaned multi-GB {@code addon-torrent-*} stream folders left in cacheDir after crashes/force-stops.
 * - Accumulated downloaded update APKs in {@code cacheDir/updates/}.
 * - Unbounded poster/image HTTP caches ({@code cinema_posters_http_cache}, {@code image_manager_disk_cache},
 *   {@code picasso-cache}) and temp subtitle/track files.
 *
 * SAFETY CONTRACT: Never touches SharedPreferences, SQLite watch history/progress DBs,
 * Phone Link pairing/auth, Stremio add-ons, Trakt tokens, or active streaming buffers.
 */
public final class CinemaCacheMaintenance {
    private static final String TAG = "CinemaCacheMaint";
    public static final String PREF_SMART_ENABLED = "cinema_smart_cache_maintenance";
    public static final String PREF_LAST_RUN_MS = "cinema_smart_cache_last_run_ms";
    public static final String PREF_LAST_FREED_BYTES = "cinema_smart_cache_last_freed_bytes";

    private static final long MIN_INTERVAL_MS = 6L * 60L * 60L * 1000L; // 6 hours
    private static final long STALE_TEMP_MS = 12L * 60L * 60L * 1000L;  // 12 hours

    private static final Set<String> IMAGE_CACHE_DIRS = Set.of(
            "cinema_posters_http_cache",
            "image_manager_disk_cache",
            "picasso-cache",
            "okhttp"
    );

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cinema-smart-maintenance");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    public record Breakdown(
            long torrentTempBytes,
            long updateApkBytes,
            long postersAndImagesBytes,
            long miscTempBytes) {
        public long totalBytes() {
            return Math.max(0L, torrentTempBytes)
                    + Math.max(0L, updateApkBytes)
                    + Math.max(0L, postersAndImagesBytes)
                    + Math.max(0L, miscTempBytes);
        }
    }

    public record AdaptiveProfile(long imageCapBytes, long freeStorageBytes, int ramClassMb) {}

    private CinemaCacheMaintenance() {}

    public static boolean isSmartEnabled(Context context) {
        if (context == null) return true;
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
                .getBoolean(PREF_SMART_ENABLED, true);
    }

    public static void setSmartEnabled(Context context, boolean enabled) {
        if (context == null) return;
        PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
                .edit().putBoolean(PREF_SMART_ENABLED, enabled).apply();
    }

    public static long lastRunMs(Context context) {
        if (context == null) return 0L;
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
                .getLong(PREF_LAST_RUN_MS, 0L);
    }

    public static long lastFreedBytes(Context context) {
        if (context == null) return 0L;
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
                .getLong(PREF_LAST_FREED_BYTES, 0L);
    }

    /** Computes an adaptive disk cap based on the TV's real free storage and RAM class. */
    public static AdaptiveProfile adaptiveProfile(Context context) {
        long freeBytes = 0L;
        int ramMb = 128;
        try {
            File cache = context.getCacheDir();
            if (cache != null) {
                StatFs stat = new StatFs(cache.getAbsolutePath());
                freeBytes = stat.getAvailableBytes();
            }
        } catch (Exception ignored) {}
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) ramMb = Math.max(64, am.getMemoryClass());
        } catch (Exception ignored) {}

        long capBytes;
        if (freeBytes > 0L && freeBytes < 1500L * 1024L * 1024L) {
            capBytes = 80L * 1024L * 1024L;   // Low-storage TV (<1.5 GB free): strict 80 MB cap
        } else if (freeBytes > 0L && freeBytes < 4000L * 1024L * 1024L) {
            capBytes = 140L * 1024L * 1024L;  // Mid-storage TV (1.5-4 GB free): 140 MB cap
        } else {
            capBytes = ramMb <= 128 ? 140L * 1024L * 1024L : 220L * 1024L * 1024L;
        }
        return new AdaptiveProfile(capBytes, freeBytes, ramMb);
    }

    /** Measures safe reclaimable cache categories without touching user data or active streams. */
    public static Breakdown inspectBreakdown(Context context) {
        if (context == null) return new Breakdown(0L, 0L, 0L, 0L);
        Context app = context.getApplicationContext();
        long torrentBytes = 0L;
        long updateBytes = 0L;
        long imageBytes = 0L;
        long miscBytes = 0L;

        File postersDir = new File(app.getFilesDir(), "posters");
        imageBytes += dirSize(postersDir);

        for (File cacheRoot : collectCacheRoots(app)) {
            File[] children = cacheRoot.listFiles();
            if (children == null) continue;
            for (File child : children) {
                String name = child.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (child.isDirectory() && name.startsWith("addon-torrent-")) {
                    if (!CinemaTorrentStream.isActiveDirectory(child)) {
                        torrentBytes += dirSize(child);
                    }
                } else if (child.isDirectory() && "updates".equals(lower)) {
                    updateBytes += dirSize(child);
                } else if (child.isDirectory() && (IMAGE_CACHE_DIRS.contains(lower) || lower.contains("image") || lower.contains("poster") || lower.contains("picasso"))) {
                    imageBytes += dirSize(child);
                } else {
                    miscBytes += dirSize(child);
                }
            }
        }
        return new Breakdown(torrentBytes, updateBytes, imageBytes, miscBytes);
    }

    /** Non-blocking startup/resume smart maintenance hook (safe, throttled, low-priority). */
    public static void maybeRunSmartMaintenanceAsync(Context context) {
        if (context == null || !isSmartEnabled(context)) return;
        final Context app = context.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) return;
        IO.execute(() -> {
            try {
                boolean hasOrphanTorrents = hasOrphanedTorrentFolders(app);
                long elapsed = System.currentTimeMillis() - lastRunMs(app);
                if (hasOrphanTorrents || elapsed >= MIN_INTERVAL_MS) {
                    runSmartMaintenanceBlocking(app, false);
                }
            } catch (Throwable ignored) {
            } finally {
                RUNNING.set(false);
            }
        });
    }

    /**
     * Performs smart self-maintenance:
     * 1) Always purges orphaned {@code addon-torrent-*} stream folders.
     * 2) Prunes stale/already-installed update APKs in {@code cacheDir/updates/}.
     * 3) Cleans stale temp media/subtitle files older than 12 hours.
     * 4) Trims oversized image/poster caches down to 75% of the device's adaptive cap (LRU).
     */
    public static long runSmartMaintenanceBlocking(Context context, boolean force) {
        if (context == null) return 0L;
        Context app = context.getApplicationContext();
        long freed = 0L;
        try {
            // 1. Orphaned torrent buffers (main cause of multi-GB cache bloat!)
            freed += CinemaTorrentStream.purgeOrphanedCaches(app);

            // 2. Stale downloaded update APKs
            freed += AppUpdates.pruneStaleUpdateApks(app, BuildConfig.VERSION_CODE);

            // 3. Stale transient temp files (media tracks, cast_subs, temp imdb downloads)
            long staleCutoff = System.currentTimeMillis() - (force ? 0L : STALE_TEMP_MS);
            for (File root : collectCacheRoots(app)) {
                File[] children = root.listFiles();
                if (children == null) continue;
                for (File child : children) {
                    String name = child.getName();
                    if (name.startsWith("addon-torrent-") || "updates".equalsIgnoreCase(name)) continue;
                    if (name.startsWith("cinema-track-") || name.endsWith(".tmp")
                            || "cast_subs".equalsIgnoreCase(name)) {
                        if (child.lastModified() <= staleCutoff) {
                            freed += dirSize(child);
                            deleteRecursive(child);
                        }
                    }
                }
            }

            // 4. Adaptive LRU trim for image/poster disk caches
            AdaptiveProfile profile = adaptiveProfile(app);
            List<File> imageCacheDirs = new ArrayList<>();
            for (File root : collectCacheRoots(app)) {
                File[] children = root.listFiles();
                if (children == null) continue;
                for (File child : children) {
                    String lower = child.getName().toLowerCase(Locale.ROOT);
                    if (child.isDirectory() && (IMAGE_CACHE_DIRS.contains(lower)
                            || lower.contains("image") || lower.contains("poster") || lower.contains("picasso"))) {
                        imageCacheDirs.add(child);
                    }
                }
            }
            long currentImageCache = 0L;
            for (File d : imageCacheDirs) currentImageCache += dirSize(d);
            if (currentImageCache > profile.imageCapBytes()) {
                long target = (profile.imageCapBytes() * 3L) / 4L;
                freed += pruneDirectoriesToLimit(imageCacheDirs, target);
            }

            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(app);
            sp.edit()
                    .putLong(PREF_LAST_RUN_MS, System.currentTimeMillis())
                    .putLong(PREF_LAST_FREED_BYTES, freed)
                    .apply();
            if (freed > 0L) {
                Log.i(TAG, "Smart maintenance freed " + formatSize(freed)
                        + " (cap=" + formatSize(profile.imageCapBytes()) + ")");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Smart maintenance error", t);
        }
        return freed;
    }

    /**
     * Safe one-click cache cleaner for TV Settings:
     * Clears orphaned torrent temp folders, cached update APKs, poster/image HTTP caches,
     * metadata JSON caches, and misc temp files ONLY.
     * Preserves watch progress, history, settings, Stremio add-ons, Phone Link, and Trakt.
     */
    public static long clearSafeCacheBlocking(Context context) {
        if (context == null) return 0L;
        Context app = context.getApplicationContext();
        Breakdown before = inspectBreakdown(app);
        try {
            CinemaTorrentStream.purgeOrphanedCaches(app);
            AppUpdates.pruneStaleUpdateApks(app, Integer.MAX_VALUE);
            CinemaMetadataPrefetcher.clearPostersAndMetadataCache(app);

            for (File root : collectCacheRoots(app)) {
                File[] children = root.listFiles();
                if (children == null) continue;
                for (File child : children) {
                    if (child.isDirectory() && child.getName().startsWith("addon-torrent-")
                            && CinemaTorrentStream.isActiveDirectory(child)) {
                        continue;
                    }
                    deleteRecursive(child);
                }
            }
        } catch (Throwable ignored) {}
        Breakdown after = inspectBreakdown(app);
        long freed = Math.max(0L, before.totalBytes() - after.totalBytes());
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(app);
        sp.edit()
                .putLong(PREF_LAST_RUN_MS, System.currentTimeMillis())
                .putLong(PREF_LAST_FREED_BYTES, freed)
                .apply();
        return freed;
    }

    public static String formatSize(long bytes) {
        long safe = Math.max(0L, bytes);
        if (safe >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0));
        }
        if (safe >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0));
        }
        if (safe >= 1024L) {
            return String.format(Locale.US, "%.0f KB", safe / 1024.0);
        }
        return safe + " B";
    }

    private static boolean hasOrphanedTorrentFolders(Context app) {
        for (File root : collectCacheRoots(app)) {
            File[] children = root.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child.isDirectory() && child.getName().startsWith("addon-torrent-")
                        && !CinemaTorrentStream.isActiveDirectory(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<File> collectCacheRoots(Context app) {
        List<File> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addUniqueDir(out, seen, app.getCacheDir());
        try {
            File[] externals = app.getExternalCacheDirs();
            if (externals != null) {
                for (File ext : externals) addUniqueDir(out, seen, ext);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void addUniqueDir(List<File> out, Set<String> seen, File dir) {
        if (dir == null || !dir.isDirectory()) return;
        String path = dir.getAbsolutePath();
        if (seen.add(path)) out.add(dir);
    }

    private static long dirSize(File f) {
        if (f == null || !f.exists()) return 0L;
        if (f.isFile()) return Math.max(0L, f.length());
        long total = 0L;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) total += dirSize(c);
        }
        return total;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (Exception ignored) {}
    }

    private static long pruneDirectoriesToLimit(List<File> dirs, long targetMaxBytes) {
        List<File> allFiles = new ArrayList<>();
        for (File d : dirs) collectLeafFiles(d, allFiles);
        long total = 0L;
        for (File f : allFiles) total += Math.max(0L, f.length());
        if (total <= targetMaxBytes) return 0L;
        allFiles.sort(Comparator.comparingLong(File::lastModified));
        long freed = 0L;
        for (File f : allFiles) {
            if (total <= targetMaxBytes) break;
            long len = Math.max(0L, f.length());
            if (f.delete()) {
                total -= len;
                freed += len;
            }
        }
        return freed;
    }

    private static void collectLeafFiles(File f, List<File> out) {
        if (f == null || !f.exists()) return;
        if (f.isFile()) {
            out.add(f);
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) collectLeafFiles(c, out);
        }
    }
}

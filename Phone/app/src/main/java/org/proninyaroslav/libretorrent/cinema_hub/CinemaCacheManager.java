package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Centralized storage & cache manager for Local Cinema Manager (Phone).
 * Enforces automatic caps on GeckoView disk cache (100 MB via config YAML + 120 MB disk prune)
 * and app/image caches (60 MB), purges orphaned temporary torrent cast buffers ("torrent-casts"),
 * and powers the "Storage & Cache" breakdown & one-tap "Clear Cache" in Settings.
 *
 * SAFETY CONTRACT: Never deletes SharedPreferences, Trakt OAuth tokens, saved API keys,
 * browser bookmarks, browser history, bookmark_favicons, or installed GeckoView extensions.
 */
public final class CinemaCacheManager {
    private static final long MAX_BROWSER_CACHE_BYTES = 120L * 1024L * 1024L; // 120 MB hard cap
    private static final long MAX_APP_CACHE_BYTES = 60L * 1024L * 1024L;      // 60 MB hard cap
    private static final String GECKO_CONFIG_FILENAME = "geckoview-config.yaml";

    private static final Set<String> GECKO_CACHE_DIR_NAMES = Set.of(
            "cache2",
            "startupcache",
            "shader-cache",
            "thumbnails",
            "minidumps",
            "crashes",
            "offlinecache",
            "jumplistcache",
            "safebrowsing"
    );

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cinema-cache-manager");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public record StorageBreakdown(
            long appImageCacheBytes,
            long browserCacheBytes,
            long tempStreamBytes) {
        public long totalBytes() {
            return Math.max(0L, appImageCacheBytes)
                    + Math.max(0L, browserCacheBytes)
                    + Math.max(0L, tempStreamBytes);
        }
    }

    private CinemaCacheManager() {}

    /**
     * Writes a GeckoView runtime configuration file capping HTTP disk cache to 100 MB
     * and disabling smart-size 1 GB ballooning.
     */
    public static String ensureGeckoConfigFile(Context context) {
        return PhoneModuleBridges.browser().ensureRuntimeConfigFile(context);
    }

    public static String formatBytes(long bytes) {
        long safe = Math.max(0L, bytes);
        if (safe < 1024L) return safe + " B";
        if (safe < 1048576L) return String.format(Locale.US, "%.1f KB", safe / 1024.0);
        if (safe < 1073741824L) return String.format(Locale.US, "%.1f MB", safe / 1048576.0);
        return String.format(Locale.US, "%.2f GB", safe / 1073741824.0);
    }

    /**
     * Runs non-blocking startup maintenance:
     * 1) Purges orphaned temporary torrent cast chunks if no cast is active.
     * 2) Cleans temp `.torrent` & `.xpi` staging files and orphaned partial downloads.
     * 3) Prunes GeckoView disk cache and app/image cache if they exceed their size caps.
     */
    public static void performStartupMaintenance(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                ensureGeckoConfigFile(app);
                CinemaStorage.migrateLegacyDownloadingFolder(app);
                boolean castActive = CinemaRemoteBridge.isCastActive() || PhoneModuleBridges.tvHub().isTempTorrentCastActive();
                if (!castActive) {
                    PhoneModuleBridges.torrentCast().purgeOrphanedTemporaryCastsAsync();
                    deleteChildren(app.getExternalFilesDir("torrent-casts"));
                    PhoneVideoServer.clearIdleBuffers();
                }
                File extRoot = app.getExternalFilesDir(null);
                if (extRoot != null) {
                    deleteChildren(new File(extRoot, "temp"));
                }
                pruneStaleExtensionTempFiles(app.getCacheDir());
                com.meedx.updates.AppUpdates.pruneStaleUpdateApks(app, org.proninyaroslav.libretorrent.BuildConfig.VERSION_CODE);
                PhoneModuleBridges.browser().purgeOrphanedDownloadStaging(app);

                // Enforce Browser Cache cap (120 MB)
                List<File> geckoCacheDirs = collectGeckoCacheDirs(app);
                long browserBytes = 0L;
                for (File d : geckoCacheDirs) browserBytes += dirSize(d);
                if (browserBytes > MAX_BROWSER_CACHE_BYTES) {
                    pruneDirectoriesToLimit(geckoCacheDirs, MAX_BROWSER_CACHE_BYTES * 3L / 4L);
                }

                // Enforce App & Image Cache cap (60 MB)
                List<File> appCacheDirs = collectAppCacheDirs(app);
                long appBytes = 0L;
                for (File d : appCacheDirs) appBytes += dirSizeExcluded(d, geckoCacheDirs);
                if (appBytes > MAX_APP_CACHE_BYTES) {
                    pruneDirectoriesToLimit(appCacheDirs, MAX_APP_CACHE_BYTES * 3L / 4L);
                }
            } catch (Throwable ignored) {}
        });
    }

    public static void computeBreakdownAsync(Context context, Consumer<StorageBreakdown> callback) {
        if (context == null || callback == null) return;
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            StorageBreakdown b = computeBreakdownBlocking(app);
            MAIN.post(() -> callback.accept(b));
        });
    }

    public static StorageBreakdown computeBreakdownBlocking(Context app) {
        List<File> geckoDirs = collectGeckoCacheDirs(app);
        long browserCache = 0L;
        for (File d : geckoDirs) {
            browserCache += dirSize(d);
        }

        long appImageCache = 0L;
        for (File d : collectAppCacheDirs(app)) {
            appImageCache += dirSizeExcluded(d, geckoDirs);
        }

        long tempStreams = 0L;
        File torrentCasts = app.getExternalFilesDir("torrent-casts");
        if (torrentCasts != null) {
            tempStreams += dirSize(torrentCasts);
        }
        File extRoot = app.getExternalFilesDir(null);
        if (extRoot != null) {
            tempStreams += dirSize(new File(extRoot, "temp"));
        }
        tempStreams += PhoneModuleBridges.browser().computeOrphanedDownloadStagingBytes(app);
        tempStreams += PhoneVideoServer.estimatedMemoryBufferBytes();

        return new StorageBreakdown(appImageCache, browserCache, tempStreams);
    }

    /**
     * Clears App & Image Cache, Browser Cache, and Temp Stream Files ONLY.
     * Preserves Trakt auth, bookmarks, history, extensions (uBlock Origin / .xpi), API keys, and settings.
     */
    public static void clearAllCachesAsync(Context context, Consumer<Long> onDone) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        MAIN.post(() -> PhoneModuleBridges.browser().clearBrowserCacheOnly());
        IO.execute(() -> {
            StorageBreakdown before = computeBreakdownBlocking(app);
            try {
                // 1. Browser Cache folders only (never extensions, cookies, or site storage)
                for (File d : collectGeckoCacheDirs(app)) {
                    deleteChildren(d);
                }

                // 2. App & Image Cache (cacheDir + externalCacheDir)
                for (File d : collectAppCacheDirs(app)) {
                    deleteChildren(d);
                }

                // 3. Temp Stream Files (torrent-casts, temp, orphaned download staging remnants, proxy buffers)
                boolean activeTempCast = PhoneModuleBridges.tvHub().isTempTorrentCastActive();
                if (!activeTempCast) {
                    PhoneModuleBridges.torrentCast().purgeOrphanedTemporaryCastsAsync();
                    deleteChildren(app.getExternalFilesDir("torrent-casts"));
                }
                File extRoot = app.getExternalFilesDir(null);
                if (extRoot != null) {
                    deleteChildren(new File(extRoot, "temp"));
                }
                PhoneModuleBridges.browser().purgeOrphanedDownloadStaging(app);
                if (!CinemaRemoteBridge.isCastActive()) {
                    PhoneVideoServer.clearIdleBuffers();
                }
            } catch (Throwable ignored) {}
            StorageBreakdown after = computeBreakdownBlocking(app);
            long freed = Math.max(0L, before.totalBytes() - after.totalBytes());
            if (onDone != null) {
                MAIN.post(() -> onDone.accept(freed));
            }
        });
    }

    private static List<File> collectAppCacheDirs(Context app) {
        List<File> list = new ArrayList<>(2);
        File internal = app.getCacheDir();
        if (internal != null) list.add(internal);
        File external = app.getExternalCacheDir();
        if (external != null) list.add(external);
        return list;
    }

    private static List<File> collectGeckoCacheDirs(Context app) {
        List<File> dirs = new ArrayList<>();
        File cacheDir = app.getCacheDir();
        if (cacheDir != null) {
            File gvCache = new File(cacheDir, "org.mozilla.geckoview");
            if (gvCache.exists()) dirs.add(gvCache);
            File mozCache = new File(cacheDir, "mozilla");
            if (mozCache.exists()) dirs.add(mozCache);
        }
        File filesDir = app.getFilesDir();
        if (filesDir != null) {
            File mozilla = new File(filesDir, "mozilla");
            collectGeckoProfileCacheSubdirs(mozilla, 0, dirs);
        }
        return dirs;
    }

    private static void collectGeckoProfileCacheSubdirs(File dir, int depth, List<File> out) {
        if (dir == null || !dir.isDirectory() || depth > 3) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            String lower = child.getName().toLowerCase(Locale.ROOT);
            // NEVER enter or touch extensions/ or storage/ inside Gecko profile
            if ("extensions".equals(lower) || "storage".equals(lower) || "bookmark_favicons".equals(lower)) {
                continue;
            }
            if (GECKO_CACHE_DIR_NAMES.contains(lower)) {
                out.add(child);
            } else {
                collectGeckoProfileCacheSubdirs(child, depth + 1, out);
            }
        }
    }

    private static void pruneStaleExtensionTempFiles(File cacheDir) {
        if (cacheDir == null || !cacheDir.isDirectory()) return;
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && f.getName().startsWith("browser-extension-") && f.getName().endsWith(".xpi")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
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

    private static long dirSizeExcluded(File f, List<File> excludedRoots) {
        if (f == null || !f.exists()) return 0L;
        for (File ex : excludedRoots) {
            if (ex != null && f.getAbsolutePath().equals(ex.getAbsolutePath())) return 0L;
        }
        if (f.isFile()) return Math.max(0L, f.length());
        long total = 0L;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) total += dirSizeExcluded(c, excludedRoots);
        }
        return total;
    }

    private static void deleteChildren(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            deleteRecursive(c);
        }
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

    private static void pruneDirectoriesToLimit(List<File> dirs, long targetMaxBytes) {
        List<File> allFiles = new ArrayList<>();
        for (File d : dirs) collectLeafFiles(d, allFiles);
        long total = 0L;
        for (File f : allFiles) total += f.length();
        if (total <= targetMaxBytes) return;
        allFiles.sort(Comparator.comparingLong(File::lastModified));
        for (File f : allFiles) {
            if (total <= targetMaxBytes) break;
            long len = f.length();
            if (f.delete()) {
                total -= len;
            }
        }
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

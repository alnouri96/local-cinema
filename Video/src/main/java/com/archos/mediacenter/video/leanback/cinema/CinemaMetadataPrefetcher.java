package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.preference.PreferenceManager;

import com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper;
import com.archos.mediacenter.video.browser.adapters.object.Episode;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Tvshow;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.browser.loader.VideoLoader;
import com.archos.mediaprovider.video.VideoStore;
import com.meedx.providers.MultiRatingsProvider;
import com.meedx.providers.OpenMetadata;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Low-priority background indexer/pre-fetcher that populates persistent disk caches
 * for metadata, posters, cast photos, and multi-source ratings without stalling the TV UI.
 */
public final class CinemaMetadataPrefetcher {
    public static final String PREF_AUTO_REFRESH_DAYS = "cinema_auto_refresh_days";
    public static final String PREF_LAST_AUTO_REFRESH_MS = "cinema_last_auto_refresh_ms";
    private static final String PREF_INITIAL_PREFETCH_DONE = "cinema_initial_prefetch_done_v1";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_ITEMS_PER_PASS = 120;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "CinemaPrefetch");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private CinemaMetadataPrefetcher() {}

    /** Clears localized text/ratings/actor caches without deleting saved posters on disk. */
    public static void clearLanguageTextCachesOnly(Context context) {
        if (context == null) return;
        OpenMetadata.clearCache(context, false);
        MultiRatingsProvider.clearCache(context);
        CinemaActorActivity.clearCache(context);
        com.meedx.providers.OpenTrailersProvider.clearCache(context);
    }

    /** Clears saved poster files, HTTP poster cache, and TMDB/OpenMetadata JSON caches only. */
    public static void clearPostersAndMetadataCache(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        clearLanguageTextCachesOnly(app);
        CinemaArtwork.clearAllCachedArtwork(app);
        deleteDirContents(com.archos.mediacenter.video.utils.TrustingOkHttp3Downloader.getPosterHttpCacheDir(app), false);
    }

    /** Clears all temporary app cache files while preserving DB watch history, settings, and Trakt/Phone pairing. */
    public static void clearAllAppCache(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        clearPostersAndMetadataCache(app);
        deleteDirContents(app.getCacheDir(), false);
        try { deleteDirContents(app.getCodeCacheDir(), false); } catch (Exception ignored) {}
        try {
            java.io.File ext = app.getExternalCacheDir();
            if (ext != null) deleteDirContents(ext, false);
        } catch (Exception ignored) {}
    }

    /** Returns [postersAndMetaBytes, totalAppCacheBytes] on disk. */
    public static long[] calculateCacheSizesBytes(Context context) {
        if (context == null) return new long[]{0L, 0L};
        Context app = context.getApplicationContext();
        long postersFiles = dirSize(new java.io.File(app.getFilesDir(), "posters"));
        long httpPosters = dirSize(com.archos.mediacenter.video.utils.TrustingOkHttp3Downloader.getPosterHttpCacheDir(app));
        long metaCache = dirSize(new java.io.File(app.getCacheDir(), "cinema_meta_cache"))
                + dirSize(new java.io.File(app.getCacheDir(), "cinema-metadata-v4"));
        long postersAndMeta = postersFiles + httpPosters + metaCache;
        long totalCache = postersFiles + dirSize(app.getCacheDir());
        try {
            java.io.File ext = app.getExternalCacheDir();
            if (ext != null) totalCache += dirSize(ext);
        } catch (Exception ignored) {}
        return new long[]{postersAndMeta, totalCache};
    }

    private static long dirSize(java.io.File dir) {
        if (dir == null || !dir.exists()) return 0L;
        if (dir.isFile()) return dir.length();
        java.io.File[] children = dir.listFiles();
        if (children == null) return 0L;
        long total = 0L;
        for (java.io.File child : children) total += dirSize(child);
        return total;
    }

    private static void deleteDirContents(java.io.File dir, boolean deleteSelf) {
        if (dir == null || !dir.exists()) return;
        if (dir.isDirectory()) {
            java.io.File[] children = dir.listFiles();
            if (children != null) {
                for (java.io.File child : children) deleteDirContents(child, true);
            }
        }
        if (deleteSelf) {
            try { dir.delete(); } catch (Exception ignored) {}
        }
    }

    /** Clears all text/ratings/actor and artwork caches cleanly. */
    public static void clearAllMetadataCaches(Context context) {
        clearPostersAndMetadataCache(context);
    }

    /** Called on Home startup/resume; honors auto-refresh interval timer and first-run warm-up. */
    public static void maybeRunOnStartup(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        int days = prefs.getInt(PREF_AUTO_REFRESH_DAYS, 0);
        long last = prefs.getLong(PREF_LAST_AUTO_REFRESH_MS, 0L);
        long now = System.currentTimeMillis();

        if (days > 0 && (last == 0L || now - last >= days * DAY_MS)) {
            prefs.edit().putLong(PREF_LAST_AUTO_REFRESH_MS, now).apply();
            clearAllMetadataCaches(app);
            runAsync(app, true);
            return;
        }
        if (!prefs.getBoolean(PREF_INITIAL_PREFETCH_DONE, false)) {
            prefs.edit().putBoolean(PREF_INITIAL_PREFETCH_DONE, true).apply();
            runAsync(app, false);
        }
    }

    /** Called quietly after library scan completes so newly discovered files open instantaneously. */
    public static void triggerAfterScan(Context context) {
        if (context != null) {
            runAsync(context.getApplicationContext(), false);
        }
    }

    public static void runAsync(Context context, boolean forceRefresh) {
        if (context == null || !RUNNING.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        POOL.execute(() -> {
            try {
                prefetchLibrary(app, forceRefresh);
            } catch (Throwable ignored) {
            } finally {
                RUNNING.set(false);
            }
        });
    }

    private static void prefetchLibrary(Context app, boolean forceRefresh) {
        if (!OpenMetadata.enabled(app)) return;
        String sel = VideoStore.Video.VideoColumns.ARCHOS_HIDDEN_BY_USER + "=0";
        Set<String> seenKeys = new HashSet<>();
        int count = 0;

        try (Cursor cursor = app.getContentResolver().query(
                VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                VideoLoader.mProjection,
                sel,
                null,
                VideoStore.Video.VideoColumns._ID + " DESC LIMIT 180")) {
            if (cursor == null) return;
            VideoCursorMapper mapper = new VideoCursorMapper();
            mapper.bindColumns(cursor);
            int showCol = cursor.getColumnIndex(VideoStore.Video.VideoColumns.SCRAPER_SHOW_ID);

            while (cursor.moveToNext() && count < MAX_ITEMS_PER_PASS) {
                Video v = (Video) mapper.bind(cursor);
                if (v == null) continue;

                boolean isShow = v instanceof Episode;
                long showId = (isShow && showCol >= 0) ? cursor.getLong(showCol) : 0L;
                CinemaPosters.Query cq;
                String posterKey;
                Uri existingPoster;

                if (v instanceof Episode ep) {
                    if (showId > 0) {
                        posterKey = "s:" + showId;
                        Tvshow showObj = new Tvshow(showId, ep.getShowName(), ep.getPosterUri(), 0, 0, 0, ep.getFilePath());
                        cq = CinemaPosters.query(showObj);
                        existingPoster = ep.getPosterUri();
                    } else {
                        cq = CinemaPosters.query(v);
                        posterKey = "v:" + v.getId();
                        existingPoster = v.getPosterUri();
                    }
                } else {
                    cq = CinemaPosters.query(v);
                    posterKey = "v:" + v.getId();
                    existingPoster = v.getPosterUri();
                }

                if (cq == null || cq.title() == null || cq.title().isBlank()) continue;
                String dedupKey = (cq.show() ? "s:" : "m:") + cq.title().toLowerCase() + ":" + cq.year();
                if (!seenKeys.add(dedupKey)) continue;
                count++;

                OpenMetadata.Query oq = new OpenMetadata.Query(cq.title(), cq.year(), cq.show());
                String fileUri = v.getFileUri() != null ? v.getFileUri().toString() : null;
                OpenMetadata.Item item = forceRefresh
                        ? OpenMetadata.refresh(app, oq, null, fileUri, 0, 0)
                        : OpenMetadata.getCachedOnly(app, oq, null);

                boolean fetchedNow = false;
                if (item == null) {
                    item = OpenMetadata.lookup(app, oq, null, fileUri, 0, 0);
                    if (item == null && "ar".equals(OpenMetadata.resolveLang(app))) {
                        item = OpenMetadata.lookup(app, oq, null, fileUri, 0, 0, "en");
                    }
                    fetchedNow = true;
                }

                if (item != null && item.imdb() != null && !item.imdb().isBlank()) {
                    var cachedMulti = forceRefresh
                            ? null
                            : MultiRatingsProvider.getCached(app, item.imdb(), cq.show(), null, null);
                    if (cachedMulti == null) {
                        Double knownImdb = "IMDb".equals(item.ratingSource()) ? item.score() : null;
                        Double knownMal = "MAL".equals(item.ratingSource()) ? item.score() : null;
                        MultiRatingsProvider.fetch(app, item.imdb(), cq.show(), knownImdb, knownMal);
                        fetchedNow = true;
                    }
                }

                // Warm up local poster file if missing or force refreshing
                Uri cachedPoster = forceRefresh ? null : CinemaArtwork.getCachedPoster(app, posterKey, existingPoster);
                if (cachedPoster == null) {
                    long onlineId = v instanceof Movie m ? m.getOnlineId() : 0L;
                    try {
                        List<CinemaPosters.Choice> choices = CinemaPosters.search(app, cq, onlineId, false);
                        if (!choices.isEmpty()) {
                            CinemaPosters.save(app, posterKey, choices.get(0));
                            fetchedNow = true;
                        }
                    } catch (Exception ignored) {}
                }

                if (fetchedNow) {
                    try {
                        Thread.sleep(120);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}

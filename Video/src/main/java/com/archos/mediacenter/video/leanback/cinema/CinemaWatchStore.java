package com.archos.mediacenter.video.leanback.cinema;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import com.archos.mediacenter.utils.trakt.Trakt;
import com.archos.mediacenter.video.browser.adapters.object.Base;
import com.archos.mediacenter.video.browser.adapters.object.Episode;
import com.archos.mediacenter.video.browser.loader.VideoLoader;
import com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Tvshow;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.player.PlayerActivity;
import com.archos.mediaprovider.video.ScraperStore;
import com.archos.mediaprovider.video.VideoStore;
import com.archos.mediascraper.BaseTags;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Persistent SQLite store for Zero-Config global IDs, HLS/torrent stream progress, Up Next, and LAN WATCH_SYNC. */
public final class CinemaWatchStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "cinema_watch_store.db";
    private static final int DB_VERSION = 7;
    private static final String PREF_TRAKT_WATCHLIST_PATHS = "cinema_trakt_watchlist_paths";
    private static volatile CinemaWatchStore instance;

    public record Identity(
            String mediaKey,
            String fileUri,
            String mediaType,
            String imdbId,
            long tmdbId,
            int season,
            int episode,
            String title,
            int year,
            String posterUrl,
            String backdropUrl,
            String provider,
            boolean pendingTraktSync,
            long updatedAt
    ) {}

    public record StreamEntry(
            String streamKey,
            String streamUri,
            String format,
            String title,
            String mediaType,
            String imdbId,
            long tmdbId,
            int season,
            int episode,
            long positionMs,
            long durationMs,
            boolean watched,
            boolean removedFromContinue,
            boolean pendingTraktSync,
            long updatedAt
    ) {}

    public record PlaybackDescriptor(
            String canonicalKey,
            String streamKey,
            String mediaType,
            String imdbId,
            long tmdbId,
            int season,
            int episode,
            boolean watched
    ) {}

    public record TraktItem(
            String listType,
            String itemKey,
            String mediaType,
            String title,
            String imdbId,
            long tmdbId,
            int season,
            int episode,
            int year,
            double progressPct,
            long positionMs,
            long durationMs,
            long updatedAt
    ) {}

    private CinemaWatchStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    public static CinemaWatchStore get(Context context) {
        if (instance == null) {
            synchronized (CinemaWatchStore.class) {
                if (instance == null) {
                    instance = new CinemaWatchStore(context);
                }
            }
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS media_identities ("
                + "media_key TEXT PRIMARY KEY, "
                + "canonical_key TEXT, "
                + "file_uri TEXT, "
                + "media_type TEXT, "
                + "imdb_id TEXT, "
                + "tmdb_id INTEGER DEFAULT 0, "
                + "season INTEGER DEFAULT 0, "
                + "episode INTEGER DEFAULT 0, "
                + "title TEXT, "
                + "year INTEGER DEFAULT 0, "
                + "poster_url TEXT, "
                + "backdrop_url TEXT, "
                + "provider TEXT, "
                + "pending_trakt_sync INTEGER DEFAULT 1, "
                + "updated_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_identities_uri ON media_identities(file_uri)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_identities_canonical ON media_identities(canonical_key)");

        db.execSQL("CREATE TABLE IF NOT EXISTS stream_progress ("
                + "stream_key TEXT PRIMARY KEY, "
                + "canonical_key TEXT, "
                + "stream_uri TEXT, "
                + "format TEXT, "
                + "title TEXT, "
                + "media_type TEXT, "
                + "imdb_id TEXT, "
                + "tmdb_id INTEGER DEFAULT 0, "
                + "season INTEGER DEFAULT 0, "
                + "episode INTEGER DEFAULT 0, "
                + "position_ms INTEGER DEFAULT 0, "
                + "duration_ms INTEGER DEFAULT 0, "
                + "watched INTEGER DEFAULT 0, "
                + "removed_from_continue INTEGER DEFAULT 0, "
                + "pending_trakt_sync INTEGER DEFAULT 1, "
                + "updated_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_stream_canonical ON stream_progress(canonical_key)");

        db.execSQL("CREATE TABLE IF NOT EXISTS trakt_outbox ("
                + "local_key TEXT PRIMARY KEY, "
                + "media_type TEXT, "
                + "imdb_id TEXT, "
                + "tmdb_id INTEGER DEFAULT 0, "
                + "season INTEGER DEFAULT 0, "
                + "episode INTEGER DEFAULT 0, "
                + "position_ms INTEGER DEFAULT 0, "
                + "duration_ms INTEGER DEFAULT 0, "
                + "watched INTEGER DEFAULT 0, "
                + "removed INTEGER DEFAULT 0, "
                + "updated_at INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE IF NOT EXISTS trakt_watchlist ("
                + "item_key TEXT PRIMARY KEY, "
                + "media_type TEXT, "
                + "title TEXT, "
                + "imdb_id TEXT, "
                + "tmdb_id INTEGER DEFAULT 0, "
                + "year INTEGER DEFAULT 0, "
                + "updated_at INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE IF NOT EXISTS trakt_items ("
                + "list_type TEXT, "
                + "item_key TEXT, "
                + "media_type TEXT, "
                + "title TEXT, "
                + "imdb_id TEXT, "
                + "tmdb_id INTEGER DEFAULT 0, "
                + "season INTEGER DEFAULT 0, "
                + "episode INTEGER DEFAULT 0, "
                + "year INTEGER DEFAULT 0, "
                + "progress_pct REAL DEFAULT 0, "
                + "position_ms INTEGER DEFAULT 0, "
                + "duration_ms INTEGER DEFAULT 0, "
                + "updated_at INTEGER DEFAULT 0, "
                + "PRIMARY KEY(list_type, item_key))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE media_identities ADD COLUMN canonical_key TEXT"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE stream_progress ADD COLUMN canonical_key TEXT"); } catch (Exception ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_identities_canonical ON media_identities(canonical_key)"); } catch (Exception ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_stream_canonical ON stream_progress(canonical_key)"); } catch (Exception ignored) {}
        }
        onCreate(db);
        if (oldVersion < 7) {
            db.execSQL("DROP TABLE IF EXISTS up_next_episodes");
            db.delete("trakt_items", "list_type='continue' AND (position_ms<=1 OR media_type IN ('episode','show') OR episode>0)", null);
            db.delete("stream_progress", "watched=0 AND removed_from_continue=0 AND position_ms<=1", null);
        }
    }

    public static String normalizeTitle(String title) {
        if (title == null) return "";
        String cleaned = title.replaceFirst("(?i)\\.(mp4|mkv|avi|mov|m4v|webm|ts|wmv)$", "")
                .replaceAll("(?i)\\b(?:[0-9]{3,4}p|4k|uhd|web[- ]?(?:dl|rip)|bluray|brrip|hdr(?:10)?|[xh]26[45]|hevc|aac|dub|sub|repack)\\b", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        return cleaned.replaceAll("\\s+", " ");
    }

    public static String canonicalKey(String mediaType, String imdbId, long tmdbId,
                                      int season, int episode, String streamKey, String title) {
        String imdb = imdbId == null ? "" : imdbId.trim().toLowerCase(Locale.ROOT);
        boolean hasImdb = imdb.matches("tt[0-9]+");
        boolean isEpisode = "episode".equalsIgnoreCase(mediaType) || season > 0 || episode > 0;
        if (isEpisode) {
            if (hasImdb) return imdb + ":S" + Math.max(0, season) + "E" + Math.max(0, episode);
            if (tmdbId > 0) return "tmdb:" + tmdbId + ":S" + Math.max(0, season) + "E" + Math.max(0, episode);
        } else {
            if (hasImdb) return imdb;
            if (tmdbId > 0) return "tmdb:" + tmdbId;
        }
        if (streamKey != null && !streamKey.isBlank()) return "key:" + streamKey.trim();
        String norm = normalizeTitle(title);
        return norm.isEmpty() ? "" : "title:" + norm;
    }

    public static void saveIdentity(Context context, String mediaKey, String fileUri, String mediaType,
                                    String imdbId, long tmdbId, int season, int episode,
                                    String title, int year, String posterUrl, String backdropUrl, String provider) {
        if (context == null || mediaKey == null || mediaKey.isBlank()) return;
        boolean hasImdb = imdbId != null && imdbId.matches("tt[0-9]+");
        if (!hasImdb && tmdbId <= 0 && (title == null || title.isBlank())) return;
        String canonical = canonicalKey(mediaType, imdbId, tmdbId, season, episode, mediaKey, title);
        try {
            SQLiteDatabase db = get(context).getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("media_key", mediaKey);
            cv.put("canonical_key", canonical);
            if (fileUri != null) cv.put("file_uri", fileUri);
            cv.put("media_type", mediaType == null ? "movie" : mediaType);
            cv.put("imdb_id", hasImdb ? imdbId : "");
            cv.put("tmdb_id", Math.max(0L, tmdbId));
            cv.put("season", Math.max(0, season));
            cv.put("episode", Math.max(0, episode));
            cv.put("title", title == null ? "" : title);
            cv.put("year", Math.max(0, year));
            cv.put("poster_url", posterUrl == null ? "" : posterUrl);
            cv.put("backdrop_url", backdropUrl == null ? "" : backdropUrl);
            cv.put("provider", provider == null ? "" : provider);
            cv.put("pending_trakt_sync", 1);
            cv.put("updated_at", System.currentTimeMillis());
            db.insertWithOnConflict("media_identities", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception ignored) {}
    }

    public static void saveForBase(Context context, Base item, String imdbId, long tmdbId,
                                   String title, int year, String posterUrl, String backdropUrl, String provider) {
        if (context == null || item == null) return;
        String fileUri = item instanceof Video v && v.getFileUri() != null ? v.getFileUri().toString() : null;
        String mediaType = (item instanceof Tvshow || item instanceof Episode) ? "episode" : "movie";
        int season = item instanceof Episode e ? Math.max(0, e.getSeasonNumber()) : 0;
        int episode = item instanceof Episode e ? Math.max(0, e.getEpisodeNumber()) : 0;
        String key = item instanceof Video v ? "v:" + v.getId()
                : item instanceof Tvshow s ? "s:" + s.getTvshowId()
                : mediaType + ":" + (title == null ? "" : title) + ":" + year;
        saveIdentity(context, key, fileUri, mediaType, imdbId, tmdbId, season, episode,
                title, year, posterUrl, backdropUrl, provider);
        syncScraperImdbIfAvailable(context, item, imdbId, tmdbId);
    }

    private static void syncScraperImdbIfAvailable(Context context, Base item, String imdbId, long tmdbId) {
        boolean hasImdb = imdbId != null && imdbId.matches("tt[0-9]+");
        if (!hasImdb && tmdbId <= 0) return;
        try {
            ContentResolver cr = context.getContentResolver();
            if (item instanceof Movie m && m.getMovieId() > 0) {
                ContentValues cv = new ContentValues();
                if (hasImdb) cv.put(ScraperStore.Movie.IMDB_ID, imdbId);
                if (tmdbId > 0 && m.getOnlineId() <= 0) cv.put(ScraperStore.Movie.ONLINE_ID, tmdbId);
                if (cv.size() > 0) {
                    cr.update(ScraperStore.Movie.URI.BASE, cv, ScraperStore.Movie.ID + "=?",
                            new String[]{Long.toString(m.getMovieId())});
                }
            } else if (item instanceof Episode e) {
                var epTags = e.getFullScraperTags(context);
                if (epTags != null && epTags.getId() > 0) {
                    ContentValues cv = new ContentValues();
                    if (hasImdb) cv.put(ScraperStore.Episode.IMDB_ID, imdbId);
                    if (tmdbId > 0 && e.getOnlineId() <= 0) cv.put(ScraperStore.Episode.ONLINE_ID, tmdbId);
                    if (cv.size() > 0) {
                        cr.update(ScraperStore.Episode.URI.BASE, cv, ScraperStore.Episode.ID + "=?",
                                new String[]{Long.toString(epTags.getId())});
                    }
                }
            } else if (item instanceof Tvshow s && s.getTvshowId() > 0) {
                ContentValues cv = new ContentValues();
                if (hasImdb) cv.put(ScraperStore.Show.IMDB_ID, imdbId);
                if (tmdbId > 0) cv.put(ScraperStore.Show.ONLINE_ID, tmdbId);
                if (cv.size() > 0) {
                    cr.update(ScraperStore.Show.URI.BASE, cv, ScraperStore.Show.ID + "=?",
                            new String[]{Long.toString(s.getTvshowId())});
                }
            }
        } catch (Exception ignored) {}
    }

    public static Identity getIdentity(Context context, String mediaKey) {
        if (context == null || mediaKey == null || mediaKey.isBlank()) return null;
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT media_key,file_uri,media_type,imdb_id,tmdb_id,season,episode,title,year,poster_url,backdrop_url,provider,pending_trakt_sync,updated_at "
                        + "FROM media_identities WHERE media_key=? LIMIT 1", new String[]{mediaKey})) {
            if (c.moveToFirst()) return readIdentity(c);
        } catch (Exception ignored) {}
        return null;
    }

    public static Identity getIdentityByUri(Context context, String fileUri) {
        if (context == null || fileUri == null || fileUri.isBlank()) return null;
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT media_key,file_uri,media_type,imdb_id,tmdb_id,season,episode,title,year,poster_url,backdrop_url,provider,pending_trakt_sync,updated_at "
                        + "FROM media_identities WHERE file_uri=? ORDER BY updated_at DESC LIMIT 1", new String[]{fileUri})) {
            if (c.moveToFirst()) return readIdentity(c);
        } catch (Exception ignored) {}
        return null;
    }

    private static Identity readIdentity(Cursor c) {
        return new Identity(c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                c.getLong(4), c.getInt(5), c.getInt(6), c.getString(7), c.getInt(8),
                c.getString(9), c.getString(10), c.getString(11), c.getInt(12) == 1, c.getLong(13));
    }

    private static final java.util.regex.Pattern TEMP_CAPTURE_NAME = java.util.regex.Pattern.compile(
            "(?i)^(?:screen[-_ ]?recording.*|\\d{8}[_-]\\d{6}.*|(?:vid|img|pxl)[_-]\\d+.*|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*|https?://.*)");

    public static boolean isNonLibraryOrPhoneCast(String streamKey, String streamUri, String format,
                                                  String title, String mediaType, String imdbId, long tmdbId) {
        String f = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        if ("phone_local_media".equals(f) || "local_media".equals(f) || "portrait_cast".equals(f)
                || "web".equals(f) || "audio".equals(f) || "photo".equals(f) || "browser".equals(f)) {
            return true;
        }
        String k = streamKey == null ? "" : streamKey.trim().toLowerCase(Locale.ROOT);
        String u = streamUri == null ? "" : streamUri.trim().toLowerCase(Locale.ROOT);
        if (k.startsWith("web:") || k.startsWith("local:")
                || ((k.startsWith("lib:/storage/") || k.startsWith("lib:content://")) && !k.contains("/local cinema/"))
                || u.contains(":8766/") || u.contains("/video/")
                || u.contains("/proxy/") || u.contains("/local/")
                || u.startsWith("content://")
                || u.startsWith("http://10.") || u.startsWith("http://192.168.") || u.startsWith("http://172.")) {
            return true;
        }
        String t = title == null ? "" : title.trim();
        if (!t.isEmpty() && TEMP_CAPTURE_NAME.matcher(t).matches()) return true;
        String leaf = extractLeafName(!u.isEmpty() ? u : k);
        if (!leaf.isEmpty() && TEMP_CAPTURE_NAME.matcher(leaf).matches()) return true;
        return u.startsWith("http") && isEmpty(imdbId) && tmdbId <= 0;
    }

    public static boolean isNonLibraryVideoStoreItem(String filePath, String title,
                                                      java.util.Collection<String> indexedPrefixes) {
        String p = filePath == null ? "" : filePath.trim();
        String lp = p.toLowerCase(Locale.ROOT);
        String t = title == null ? "" : title.trim();
        if (lp.contains(":8766/") || lp.contains("/video/") || lp.contains("/proxy/") || lp.contains("/local/")
                || lp.startsWith("content://")
                || lp.startsWith("http://10.") || lp.startsWith("http://192.168.") || lp.startsWith("http://172.")) {
            return true;
        }
        if (!t.isEmpty() && TEMP_CAPTURE_NAME.matcher(t).matches()) return true;
        String leaf = extractLeafName(p);
        if (!leaf.isEmpty() && TEMP_CAPTURE_NAME.matcher(leaf).matches()) return true;
        if (indexedPrefixes != null && !indexedPrefixes.isEmpty() && !lp.isEmpty()) {
            String decoded = Uri.decode(lp);
            for (String prefix : indexedPrefixes) {
                if (prefix == null || prefix.isEmpty()) continue;
                String lowPrefix = prefix.toLowerCase(Locale.ROOT);
                String decodedPrefix = Uri.decode(lowPrefix);
                if (lp.startsWith(lowPrefix) || lp.startsWith(decodedPrefix)
                        || decoded.startsWith(lowPrefix) || decoded.startsWith(decodedPrefix)) {
                    return false;
                }
            }
            if (lp.contains("/local cinema/") || decoded.contains("/local cinema/")) {
                return false;
            }
            // Outside every configured TV Library folder
            return true;
        }
        return false;
    }

    private static String extractLeafName(String pathOrUri) {
        if (pathOrUri == null || pathOrUri.isEmpty()) return "";
        String clean = pathOrUri;
        int pipe = clean.indexOf('|');
        if (pipe > 0) clean = clean.substring(0, pipe);
        int q = clean.indexOf('?');
        if (q >= 0) clean = clean.substring(0, q);
        int slash = clean.lastIndexOf('/');
        return slash >= 0 && slash < clean.length() - 1 ? clean.substring(slash + 1) : clean;
    }

    public static boolean isEphemeralLiveCast(String streamUri, String title, String mediaType, String imdbId, long tmdbId) {
        if (isNonLibraryOrPhoneCast("", streamUri, "", title, mediaType, imdbId, tmdbId)) return true;
        if ((imdbId != null && !imdbId.isBlank()) || tmdbId > 0) return false;
        String u = streamUri == null ? "" : streamUri.trim().toLowerCase(Locale.ROOT);
        String t = title == null ? "" : title.trim();
        if (u.startsWith("http://10.") || u.startsWith("http://192.168.") || u.startsWith("http://172.")
                || u.contains(":8766/video/")) return true;
        if (t.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*")) return true;
        if (u.matches(".*(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*")) return true;
        return t.startsWith("http://") || t.startsWith("https://");
    }

    public static void purgeEphemeralLiveCasts(Context context) {
        purgeNonLibraryContinueEntries(context);
    }

    public static void purgeNonLibraryContinueEntries(Context context) {
        if (context == null) return;
        purgeLegacyEpisodeBookmarks(context);
        try {
            var db = get(context).getWritableDatabase();
            var toDelete = new ArrayList<String>();
            try (Cursor c = db.rawQuery(
                    "SELECT stream_key,stream_uri,format,title,media_type,imdb_id,tmdb_id FROM stream_progress", null)) {
                while (c.moveToNext()) {
                    String sk = c.getString(0);
                    String su = c.getString(1);
                    String fmt = c.getString(2);
                    String ttl = c.getString(3);
                    String mt = c.getString(4);
                    String im = c.getString(5);
                    long tm = c.getLong(6);
                    if (isEmpty(su) || isNonLibraryOrPhoneCast(sk, su, fmt, ttl, mt, im, tm)
                            || isEphemeralLiveCast(su, ttl, mt, im, tm)) {
                        toDelete.add(sk);
                    }
                }
            }
            if (!toDelete.isEmpty()) {
                db.beginTransaction();
                try {
                    for (String k : toDelete) {
                        db.delete("stream_progress", "stream_key=?", new String[]{k});
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
        } catch (Exception ignored) {}
    }

    private static void purgeLegacyEpisodeBookmarks(Context context) {
        // Older candidates saved a 1 ms placeholder for the next episode as if it were played.
        String bookmark = VideoStore.Video.VideoColumns.BOOKMARK;
        String selection = bookmark + "=1 AND " + VideoStore.Video.VideoColumns.SCRAPER_E_EPISODE + ">0";
        var resolver = context.getContentResolver();
        var uri = VideoStore.Video.Media.EXTERNAL_CONTENT_URI;
        try (Cursor rows = resolver.query(uri, new String[]{VideoStore.Video.VideoColumns._ID}, selection, null, null)) {
            if (rows == null || !rows.moveToFirst()) return;
        } catch (RuntimeException ignored) {
            return;
        }
        try {
            ContentValues values = new ContentValues();
            values.put(bookmark, PlayerActivity.LAST_POSITION_UNKNOWN);
            resolver.update(uri, values, selection, null);
        } catch (RuntimeException ignored) {}
    }

    public static void saveStreamProgress(Context context, String streamKey, String streamUri, String format,
                                          String title, String mediaType, String imdbId, long tmdbId,
                                          int season, int episode, long positionMs, long durationMs) {
        if (context == null || streamKey == null || streamKey.isBlank()) return;
        if (durationMs <= 0 || positionMs <= 1) return;
        if (isNonLibraryOrPhoneCast(streamKey, streamUri, format, title, mediaType, imdbId, tmdbId)
                || isEphemeralLiveCast(streamUri, title, mediaType, imdbId, tmdbId)) return;
        boolean watched = positionMs * 100L >= durationMs * 85L;
        long savedPos = watched ? 0L : positionMs;
        String resolvedType = mediaType == null || mediaType.isBlank() ? (season > 0 || episode > 0 ? "episode" : "movie") : mediaType;
        String canonical = canonicalKey(resolvedType, imdbId, tmdbId, season, episode, streamKey, title);
        try {
            SQLiteDatabase db = get(context).getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("stream_key", streamKey);
            cv.put("canonical_key", canonical);
            cv.put("stream_uri", streamUri == null ? "" : streamUri);
            cv.put("format", format == null ? "" : format);
            cv.put("title", title == null ? "" : title);
            cv.put("media_type", resolvedType);
            cv.put("imdb_id", imdbId == null ? "" : imdbId);
            cv.put("tmdb_id", Math.max(0L, tmdbId));
            cv.put("season", Math.max(0, season));
            cv.put("episode", Math.max(0, episode));
            cv.put("position_ms", savedPos);
            cv.put("duration_ms", durationMs);
            cv.put("watched", watched ? 1 : 0);
            cv.put("removed_from_continue", 0);
            cv.put("pending_trakt_sync", 1);
            cv.put("updated_at", System.currentTimeMillis());
            db.insertWithOnConflict("stream_progress", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            CinemaMediaTime.rememberState(-1L, streamUri == null ? null : Uri.parse(streamUri),
                    durationMs, watched ? -2L : savedPos);
        } catch (Exception ignored) {}
    }

    public static long findBestStreamResumeMs(Context context, String streamKey, String mediaType,
                                              String imdbId, long tmdbId, int season, int episode, String title) {
        if (context == null) return 0L;
        StreamEntry exact = getStream(context, streamKey);
        if (exact != null && !exact.watched() && !exact.removedFromContinue() && exact.positionMs() > 0) {
            return exact.positionMs();
        }
        String canonical = canonicalKey(mediaType, imdbId, tmdbId, season, episode, streamKey, title);
        if (!canonical.isEmpty()) {
            try (Cursor c = get(context).getReadableDatabase().rawQuery(
                    "SELECT position_ms,watched,removed_from_continue FROM stream_progress "
                            + "WHERE canonical_key=? ORDER BY updated_at DESC LIMIT 1", new String[]{canonical})) {
                if (c.moveToFirst() && c.getInt(1) == 0 && c.getInt(2) == 0 && c.getLong(0) > 0) {
                    return c.getLong(0);
                }
            } catch (Exception ignored) {}
        }
        String norm = normalizeTitle(title);
        if (!norm.isEmpty()) {
            try (Cursor c = get(context).getReadableDatabase().rawQuery(
                    "SELECT title,position_ms,watched,removed_from_continue FROM stream_progress "
                            + "WHERE watched=0 AND removed_from_continue=0 AND position_ms>0 ORDER BY updated_at DESC LIMIT 50", null)) {
                while (c.moveToNext()) {
                    if (norm.equals(normalizeTitle(c.getString(0)))) return c.getLong(1);
                }
            } catch (Exception ignored) {}
        }
        return 0L;
    }

    public static void markStreamWatched(Context context, String streamKey, boolean watched) {
        if (context == null || streamKey == null || streamKey.isBlank()) return;
        try {
            ContentValues cv = new ContentValues();
            cv.put("watched", watched ? 1 : 0);
            cv.put("position_ms", 0L);
            cv.put("removed_from_continue", watched ? 0 : 1);
            cv.put("pending_trakt_sync", 1);
            cv.put("updated_at", System.currentTimeMillis());
            get(context).getWritableDatabase().update("stream_progress", cv, "stream_key=?", new String[]{streamKey});
        } catch (Exception ignored) {}
    }

    public static void removeStreamFromContinue(Context context, String streamKey) {
        if (context == null || streamKey == null || streamKey.isBlank()) return;
        try {
            ContentValues cv = new ContentValues();
            cv.put("removed_from_continue", 1);
            cv.put("position_ms", 0L);
            cv.put("updated_at", System.currentTimeMillis());
            get(context).getWritableDatabase().update("stream_progress", cv, "stream_key=?", new String[]{streamKey});
        } catch (Exception ignored) {}
    }

    public static StreamEntry getStream(Context context, String streamKey) {
        if (context == null || streamKey == null || streamKey.isBlank()) return null;
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT stream_key,stream_uri,format,title,media_type,imdb_id,tmdb_id,season,episode,position_ms,duration_ms,watched,removed_from_continue,pending_trakt_sync,updated_at "
                        + "FROM stream_progress WHERE stream_key=? LIMIT 1", new String[]{streamKey})) {
            if (c.moveToFirst()) return readStream(c);
        } catch (Exception ignored) {}
        return null;
    }

    private static final String ADDON_CONTINUE = "pref_addon_continue_watching";

    public static boolean includeAddonContinue(Context context) {
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(ADDON_CONTINUE, true);
    }

    public static void setIncludeAddonContinue(Context context, boolean enabled) {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putBoolean(ADDON_CONTINUE, enabled).apply();
        context.sendBroadcast(new android.content.Intent(CinemaLinkServer.ACTION_WATCH_SYNC_UPDATED)
                .setPackage(context.getPackageName()));
    }

    public static List<StreamEntry> listContinueStreams(Context context) {
        List<StreamEntry> out = new ArrayList<>();
        if (context == null) return out;
        try {
            SQLiteDatabase db = get(context).getWritableDatabase();
            db.execSQL("UPDATE stream_progress SET watched=1,position_ms=0,pending_trakt_sync=1,updated_at="
                    + System.currentTimeMillis()
                    + " WHERE watched=0 AND duration_ms>0 AND (position_ms*100 >= duration_ms*85)");
        } catch (Exception ignored) {}
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT stream_key,stream_uri,format,title,media_type,imdb_id,tmdb_id,season,episode,position_ms,duration_ms,watched,removed_from_continue,pending_trakt_sync,updated_at "
                        + "FROM stream_progress WHERE watched=0 AND removed_from_continue=0 AND duration_ms>0 "
                        + "AND position_ms>1 AND (position_ms*100 < duration_ms*85) "
                        + (includeAddonContinue(context)
                           ? "AND (stream_key NOT LIKE 'addon:%' OR position_ms >= 1500) "
                           : "AND stream_key NOT LIKE 'addon:%' ")
                        + "ORDER BY updated_at DESC LIMIT 30", null)) {
            while (c.moveToNext()) {
                StreamEntry e = readStream(c);
                if (!isEmpty(e.streamUri())
                        && !isNonLibraryOrPhoneCast(e.streamKey(), e.streamUri(), e.format(), e.title(), e.mediaType(), e.imdbId(), e.tmdbId())
                        && !isEphemeralLiveCast(e.streamUri(), e.title(), e.mediaType(), e.imdbId(), e.tmdbId())) {
                    out.add(e);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static List<StreamEntry> listWatchedStreams(Context context) {
        List<StreamEntry> out = new ArrayList<>();
        if (context == null) return out;
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT stream_key,stream_uri,format,title,media_type,imdb_id,tmdb_id,season,episode,position_ms,duration_ms,watched,removed_from_continue,pending_trakt_sync,updated_at "
                        + "FROM stream_progress WHERE watched=1 ORDER BY updated_at DESC LIMIT 40", null)) {
            while (c.moveToNext()) out.add(readStream(c));
        } catch (Exception ignored) {}
        return out;
    }

    private static StreamEntry readStream(Cursor c) {
        return new StreamEntry(c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                c.getString(4), c.getString(5), c.getLong(6), c.getInt(7), c.getInt(8),
                c.getLong(9), c.getLong(10), c.getInt(11) == 1, c.getInt(12) == 1,
                c.getInt(13) == 1, c.getLong(14));
    }

    /** Resolves lightweight playback metadata for PLAYBACK JSON and WATCH_SYNC broadcasts. */
    public static PlaybackDescriptor resolvePlaybackDescriptor(Context context, long videoId, String uri, String title) {
        if (context == null) return new PlaybackDescriptor("", "", "movie", "", 0L, 0, 0, false);
        String streamKey = videoId > 0 ? "v:" + videoId : (uri == null ? "" : uri);
        String mediaType = "movie";
        String imdbId = "";
        long tmdbId = 0L;
        int season = 0;
        int episode = 0;
        boolean watched = false;

        long showId = 0L;
        boolean indexedMovie = false;
        String showName = "";
        if (videoId > 0) {
            String[] proj = {
                    VideoStore.Video.VideoColumns.ARCHOS_MEDIA_SCRAPER_TYPE,
                    VideoStore.Video.VideoColumns.SCRAPER_M_IMDB_ID,
                    VideoStore.Video.VideoColumns.SCRAPER_S_IMDB_ID,
                    VideoStore.Video.VideoColumns.SCRAPER_E_IMDB_ID,
                    VideoStore.Video.VideoColumns.SCRAPER_M_ONLINE_ID,
                    VideoStore.Video.VideoColumns.SCRAPER_S_ONLINE_ID,
                    VideoStore.Video.VideoColumns.SCRAPER_E_SEASON,
                    VideoStore.Video.VideoColumns.SCRAPER_E_EPISODE,
                    VideoStore.Video.VideoColumns.BOOKMARK,
                    VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN,
                    VideoStore.Video.VideoColumns.SCRAPER_SHOW_ID,
                    VideoStore.Video.VideoColumns.SCRAPER_S_NAME
            };
            try (Cursor c = context.getContentResolver().query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
                    VideoStore.Video.VideoColumns._ID + "=?", new String[]{Long.toString(videoId)}, null)) {
                if (c != null && c.moveToFirst()) {
                    int type = c.getInt(0);
                    indexedMovie = type == BaseTags.MOVIE;
                    boolean isEp = type == BaseTags.TV_SHOW;
                    mediaType = isEp ? "episode" : "movie";
                    String mImdb = c.getString(1);
                    String sImdb = c.getString(2);
                    String eImdb = c.getString(3);
                    imdbId = isEp ? (!isEmpty(sImdb) ? sImdb : (!isEmpty(eImdb) ? eImdb : "")) : (!isEmpty(mImdb) ? mImdb : "");
                    tmdbId = isEp ? c.getLong(5) : c.getLong(4);
                    season = isEp ? Math.max(0, c.getInt(6)) : 0;
                    episode = isEp ? Math.max(0, c.getInt(7)) : 0;
                    watched = c.getInt(8) == PlayerActivity.LAST_POSITION_END || c.getInt(9) == 1;
                    showId = c.getLong(10);
                    showName = c.getString(11) != null ? c.getString(11) : "";
                }
            } catch (Exception ignored) {}
        }

        Identity id = videoId > 0 ? getIdentity(context, "v:" + videoId) : getIdentityByUri(context, uri);
        if (id == null && showId > 0) {
            id = getIdentity(context, "s:" + showId);
        }
        if (id == null) {
            String lookupTitle = !isEmpty(showName) ? showName : title;
            id = findIdentityByTitle(context, lookupTitle);
        }
        if (id != null && (!indexedMovie || "movie".equalsIgnoreCase(id.mediaType()))) {
            if (isEmpty(imdbId) && !isEmpty(id.imdbId())) imdbId = id.imdbId();
            if (tmdbId <= 0 && id.tmdbId() > 0) tmdbId = id.tmdbId();
            if (!isEmpty(id.mediaType())) mediaType = id.mediaType();
            if (season <= 0 && id.season() > 0) season = id.season();
            if (episode <= 0 && id.episode() > 0) episode = id.episode();
        }
        if (isEmpty(imdbId) && tmdbId <= 0
                && android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            String candidateTitle = !isEmpty(showName) ? showName : title;
            if (!isEmpty(candidateTitle) && !isEphemeralLiveCast(uri, candidateTitle, mediaType, "", 0L)) {
                try {
                    boolean isEp = "episode".equalsIgnoreCase(mediaType) || season > 0 || episode > 0 || showId > 0;
                    String cleanQ = normalizeTitle(candidateTitle);
                    if (!cleanQ.isEmpty()) {
                        var oq = new com.meedx.providers.OpenMetadata.Query(cleanQ, 0, isEp);
                        var meta = com.meedx.providers.OpenMetadata.getCachedOnly(context.getApplicationContext(), oq, null);
                        if (meta == null && com.meedx.providers.TraktSyncClient.isConnected(context)) {
                            meta = com.meedx.providers.OpenMetadata.lookup(
                                    context.getApplicationContext(), oq, null, uri, season, episode);
                        }
                        if (meta != null && !isEmpty(meta.imdb())) {
                            imdbId = meta.imdb();
                            int parsedY = 0;
                            try { if (!isEmpty(meta.year())) parsedY = Integer.parseInt(meta.year().replaceAll("[^0-9]", "")); }
                            catch (Exception ignored) {}
                            saveIdentity(context, streamKey, uri, mediaType, imdbId, tmdbId, season, episode,
                                    meta.title(), parsedY, meta.poster(), meta.backdrop(), meta.attribution());
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        String canonical = canonicalKey(mediaType, imdbId, tmdbId, season, episode, streamKey, !isEmpty(showName) ? showName : title);
        return new PlaybackDescriptor(canonical, streamKey, mediaType, imdbId, tmdbId, season, episode, watched);
    }

    private static Identity findIdentityByTitle(Context context, String rawTitle) {
        String norm = normalizeTitle(rawTitle);
        if (context == null || norm.isEmpty()) return null;
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT media_key,file_uri,media_type,imdb_id,tmdb_id,season,episode,title,year,poster_url,backdrop_url,provider,pending_trakt_sync,updated_at "
                        + "FROM media_identities WHERE imdb_id!='' OR tmdb_id>0 ORDER BY updated_at DESC LIMIT 80", null)) {
            while (c.moveToNext()) {
                Identity cand = readIdentity(c);
                if (norm.equals(normalizeTitle(cand.title()))) return cand;
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static JSONObject buildSyncRecordJson(String canonicalKey, String streamKey, String kind, String title,
                                                 String mediaType, String imdbId, long tmdbId, int season, int episode,
                                                 long positionMs, long durationMs, boolean watched,
                                                 boolean removedFromContinue, long updatedAt) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("canonical_key", canonicalKey == null || canonicalKey.isBlank()
                    ? canonicalKey(mediaType, imdbId, tmdbId, season, episode, streamKey, title) : canonicalKey);
            obj.put("stream_key", streamKey == null ? "" : streamKey);
            obj.put("kind", kind == null ? "tv" : kind);
            obj.put("title", title == null ? "" : title);
            obj.put("media_type", mediaType == null ? "movie" : mediaType);
            obj.put("imdb_id", imdbId == null ? "" : imdbId);
            obj.put("tmdb_id", Math.max(0L, tmdbId));
            obj.put("season", Math.max(0, season));
            obj.put("episode", Math.max(0, episode));
            obj.put("position_ms", Math.max(0L, positionMs));
            obj.put("duration_ms", Math.max(0L, durationMs));
            obj.put("watched", watched);
            obj.put("removed_from_continue", removedFromContinue);
            obj.put("updated_at", updatedAt > 0 ? updatedAt : System.currentTimeMillis());
        } catch (Exception ignored) {}
        return obj;
    }

    public static JSONObject buildSyncRecordForVideo(Context context, Video video, long positionMs, boolean watched, boolean removed) {
        if (context == null || video == null) return null;
        PlaybackDescriptor desc = resolvePlaybackDescriptor(context, video.getId(),
                video.getFileUri() != null ? video.getFileUri().toString() : video.getFilePath(), video.getName());
        long dur = CinemaMediaTime.known(video.getId(), video.getFilePath(), video.getDurationMs());
        long ts = Math.max(video.getLastPlayed() > 0 ? video.getLastPlayed() * 1000L : 0L, System.currentTimeMillis());
        if (!isEmpty(desc.imdbId()) || desc.tmdbId() > 0) {
            try {
                ContentValues cv = new ContentValues();
                cv.put("local_key", "trakt:" + desc.canonicalKey());
                cv.put("media_type", desc.mediaType());
                cv.put("imdb_id", desc.imdbId() == null ? "" : desc.imdbId());
                cv.put("tmdb_id", Math.max(0L, desc.tmdbId()));
                cv.put("season", Math.max(0, desc.season()));
                cv.put("episode", Math.max(0, desc.episode()));
                cv.put("position_ms", watched ? 0L : Math.max(0L, positionMs));
                cv.put("duration_ms", Math.max(0L, dur));
                cv.put("watched", watched ? 1 : 0);
                cv.put("removed", removed ? 1 : 0);
                cv.put("updated_at", ts);
                get(context).getWritableDatabase().insertWithOnConflict("trakt_outbox", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            } catch (Exception ignored) {}
        }
        return buildSyncRecordJson(desc.canonicalKey(), desc.streamKey(), "tv", video.getName(),
                desc.mediaType(), desc.imdbId(), desc.tmdbId(), desc.season(), desc.episode(),
                watched ? 0L : Math.max(0L, positionMs), Math.max(0L, dur), watched, removed, ts);
    }

    public static JSONObject buildSyncRecordForStream(StreamEntry s) {
        if (s == null) return null;
        return buildSyncRecordJson("", s.streamKey(), isEmpty(s.format()) ? "web" : s.format(), s.title(),
                s.mediaType(), s.imdbId(), s.tmdbId(), s.season(), s.episode(),
                s.positionMs(), s.durationMs(), s.watched(), s.removedFromContinue(), s.updatedAt());
    }

    /** Export up to [limit] most recent watch records across stream_progress and indexed VideoStore. */
    public static JSONArray exportRecentSyncRecords(Context context, int limit) {
        JSONArray arr = new JSONArray();
        if (context == null || limit <= 0) return arr;
        java.util.LinkedHashMap<String, JSONObject> byKey = new java.util.LinkedHashMap<>();
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT stream_key,stream_uri,format,title,media_type,imdb_id,tmdb_id,season,episode,position_ms,duration_ms,watched,removed_from_continue,pending_trakt_sync,updated_at "
                        + "FROM stream_progress ORDER BY updated_at DESC LIMIT ?", new String[]{Integer.toString(limit)})) {
            while (c.moveToNext()) {
                StreamEntry s = readStream(c);
                if (isEmpty(s.streamUri()) || isNonLibraryOrPhoneCast(s.streamKey(), s.streamUri(), s.format(), s.title(), s.mediaType(), s.imdbId(), s.tmdbId())) {
                    continue;
                }
                JSONObject obj = buildSyncRecordForStream(s);
                if (obj != null) {
                    String k = obj.optString("canonical_key", s.streamKey());
                    byKey.putIfAbsent(k, obj);
                }
            }
        } catch (Exception ignored) {}

        String[] proj = {
                VideoStore.Video.VideoColumns._ID,
                VideoStore.MediaColumns.DATA,
                VideoStore.Video.VideoColumns.SCRAPER_TITLE,
                VideoStore.MediaColumns.TITLE,
                VideoStore.Video.VideoColumns.DURATION,
                VideoStore.Video.VideoColumns.BOOKMARK,
                VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN,
                VideoStore.Video.VideoColumns.ARCHOS_LAST_TIME_PLAYED
        };
        String sel = VideoStore.Video.VideoColumns.BOOKMARK + ">1 OR "
                + VideoStore.Video.VideoColumns.BOOKMARK + "=" + PlayerActivity.LAST_POSITION_END + " OR "
                + VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN + "=1";
        try (Cursor c = context.getContentResolver().query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
                sel, null, VideoStore.Video.VideoColumns.ARCHOS_LAST_TIME_PLAYED + " DESC LIMIT " + limit)) {
            if (c != null) {
                while (c.moveToNext() && byKey.size() < limit) {
                    long vid = c.getLong(0);
                    String data = c.getString(1);
                    String title = !isEmpty(c.getString(2)) ? c.getString(2) : c.getString(3);
                    if (isNonLibraryVideoStoreItem(data, title, null)) continue;
                    long dur = Math.max(0L, c.getLong(4));
                    int bm = c.getInt(5);
                    boolean watched = bm == PlayerActivity.LAST_POSITION_END || c.getInt(6) == 1;
                    long lastPlayedMs = Math.max(0L, c.getLong(7)) * 1000L;
                    PlaybackDescriptor desc = resolvePlaybackDescriptor(context, vid, data, title);
                    JSONObject obj = buildSyncRecordJson(desc.canonicalKey(), desc.streamKey(), "tv", title,
                            desc.mediaType(), desc.imdbId(), desc.tmdbId(), desc.season(), desc.episode(),
                            watched ? 0L : Math.max(0L, bm), dur, watched, false,
                            lastPlayedMs > 0 ? lastPlayedMs : System.currentTimeMillis());
                    String k = obj.optString("canonical_key", desc.streamKey());
                    if (!k.isEmpty()) byKey.putIfAbsent(k, obj);
                }
            }
        } catch (Exception ignored) {}

        for (JSONObject obj : byKey.values()) {
            if (arr.length() >= limit) break;
            arr.put(obj);
        }
        return arr;
    }

    /** Apply one remote WATCH_SYNC record using Last-Write-Wins (remote.updated_at > localUpdatedAt). */
    public static boolean applyRemoteSyncRecord(Context context, JSONObject rec) {
        return applyRemoteSyncRecord(context, rec, true);
    }

    /** Apply a remote or Trakt sync record using Last-Write-Wins. */
    public static boolean applyRemoteSyncRecord(Context context, JSONObject rec, boolean markPendingTrakt) {
        if (context == null || rec == null) return false;
        String title = rec.optString("title", "");
        String mediaType = rec.optString("media_type", "movie");
        String imdbId = rec.optString("imdb_id", "");
        long tmdbId = rec.optLong("tmdb_id", 0L);
        int season = rec.optInt("season", 0);
        int episode = rec.optInt("episode", 0);
        String streamKey = rec.optString("stream_key", "");
        String kind = rec.optString("kind", "web");
        String canonical = rec.optString("canonical_key", "");
        if (canonical.isBlank()) canonical = canonicalKey(mediaType, imdbId, tmdbId, season, episode, streamKey, title);
        long posMs = Math.max(0L, rec.optLong("position_ms", 0L));
        long durMs = Math.max(0L, rec.optLong("duration_ms", 0L));
        boolean watched = rec.optBoolean("watched", false) || (durMs > 0 && posMs * 100L >= durMs * 85L);
        boolean removed = rec.optBoolean("removed_from_continue", false);
        if (!watched && !removed && "episode".equalsIgnoreCase(mediaType)
                && !isEpisodePlayback(season, episode, posMs, durMs)) return false;
        long remoteUpdatedAt = rec.optLong("updated_at", 0L);
        if (remoteUpdatedAt <= 0) remoteUpdatedAt = System.currentTimeMillis();
        if (watched) posMs = 0L;

        boolean changed = false;
        String sourceUri = rec.optString("source_uri", "");
        if ("phone_local_media".equalsIgnoreCase(kind) || "local_media".equalsIgnoreCase(kind)
                || isNonLibraryOrPhoneCast(streamKey, sourceUri, kind, title, mediaType, imdbId, tmdbId)) {
            return false;
        }
        boolean phoneNonStream = "library".equalsIgnoreCase(kind) || "tv".equalsIgnoreCase(kind)
                || isEmpty(sourceUri);
        boolean isTraktSource = "trakt".equalsIgnoreCase(kind);
        if (isTraktSource && !watched && !removed
                && (episode > 0 || "episode".equalsIgnoreCase(mediaType) || "show".equalsIgnoreCase(mediaType))) return false;
        double progressPct = rec.optDouble("progress_pct", durMs > 0 && posMs > 0 ? (posMs * 100.0 / durMs) : -1.0);
        // 1. Only update existing stream_progress row or insert valid external non-library streams (never create gray empty-URI rows for Library sync!)
        String matchedStreamKey = findStreamKey(context, streamKey, canonical);
        long localStreamTs = getStreamUpdatedAt(context, matchedStreamKey);
        if ((matchedStreamKey != null || (!phoneNonStream && !isEmpty(sourceUri)))
                && remoteUpdatedAt > localStreamTs) {
            String targetKey = matchedStreamKey != null ? matchedStreamKey
                    : (!streamKey.isBlank() ? streamKey : canonical);
            if (!targetKey.isBlank()) {
                try {
                    SQLiteDatabase db = get(context).getWritableDatabase();
                    StreamEntry prev = getStream(context, targetKey);
                    long streamDurMs = durMs > 0 ? durMs : (prev != null ? prev.durationMs() : 0L);
                    long streamPosMs = posMs;
                    boolean allowOverwrite = true;
                    if (isTraktSource && !watched && !removed && prev != null && !prev.watched() && prev.positionMs() > 0) {
                        double localPct = prev.durationMs() > 0 ? (prev.positionMs() * 100.0 / prev.durationMs()) : -1.0;
                        boolean significantlyNewer = remoteUpdatedAt > localStreamTs + 120_000L
                                && (localPct < 0 || progressPct <= 0 || Math.abs(progressPct - localPct) > 3.0);
                        if (!significantlyNewer) {
                            allowOverwrite = false;
                        } else if (prev.durationMs() > 0 && progressPct > 0) {
                            streamPosMs = Math.round(prev.durationMs() * progressPct / 100.0);
                            streamDurMs = prev.durationMs();
                        }
                    }
                    if (allowOverwrite) {
                        ContentValues cv = new ContentValues();
                        cv.put("stream_key", targetKey);
                        cv.put("canonical_key", canonical);
                        cv.put("stream_uri", prev != null ? prev.streamUri() : rec.optString("source_uri", ""));
                        cv.put("format", prev != null ? prev.format() : kind);
                        cv.put("title", !title.isBlank() ? title : (prev != null ? prev.title() : ""));
                        cv.put("media_type", mediaType);
                        cv.put("imdb_id", imdbId);
                        cv.put("tmdb_id", tmdbId);
                        cv.put("season", season);
                        cv.put("episode", episode);
                        cv.put("position_ms", streamPosMs);
                        cv.put("duration_ms", streamDurMs);
                        cv.put("watched", watched ? 1 : 0);
                        cv.put("removed_from_continue", removed ? 1 : 0);
                        cv.put("pending_trakt_sync", markPendingTrakt ? 1 : 0);
                        cv.put("updated_at", remoteUpdatedAt);
                        db.insertWithOnConflict("stream_progress", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                        changed = true;
                    }
                } catch (Exception ignored) {}
            }
        }

        // 2. Update any matching indexed VideoStore rows (files table) if remoteUpdatedAt is newer
        List<Long> matchedVideoIds = findMatchingIndexedVideoIds(context, streamKey, mediaType, imdbId, tmdbId, season, episode, canonical);
        for (long vid : matchedVideoIds) {
            long[] localInfo = getVideoPlaybackInfo(context, vid); // [lastPlayedMs, bookmarkMs, durationMs]
            long localTs = localInfo[0];
            long localBookmarkMs = localInfo[1];
            long localDurMs = localInfo[2];
            if (remoteUpdatedAt <= localTs) continue;
            long targetPosMs = posMs;
            if (isTraktSource && !watched && !removed) {
                if (localBookmarkMs > 0) {
                    double localPct = localDurMs > 0 ? (localBookmarkMs * 100.0 / localDurMs) : -1.0;
                    boolean traktClearlyNewer = remoteUpdatedAt > localTs + 120_000L
                            && (localPct < 0 || progressPct <= 0 || Math.abs(progressPct - localPct) > 3.0);
                    if (!traktClearlyNewer) {
                        continue; // Preserve exact local millisecond resumePositionMs over coarse Trakt %
                    }
                }
                if (localDurMs > 0 && progressPct > 0) {
                    targetPosMs = Math.round(localDurMs * progressPct / 100.0);
                }
            }
            if (applyWatchStateToIndexedVideo(context, vid, targetPosMs, watched, removed, remoteUpdatedAt)) {
                changed = true;
            }
        }
        return changed;
    }

    private static String findStreamKey(Context context, String streamKey, String canonical) {
        if (!isEmpty(streamKey) && getStream(context, streamKey) != null) return streamKey;
        if (!isEmpty(canonical)) {
            try (Cursor c = get(context).getReadableDatabase().rawQuery(
                    "SELECT stream_key FROM stream_progress WHERE canonical_key=? ORDER BY updated_at DESC LIMIT 1",
                    new String[]{canonical})) {
                if (c.moveToFirst()) return c.getString(0);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static long getStreamUpdatedAt(Context context, String streamKey) {
        if (isEmpty(streamKey)) return 0L;
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT updated_at FROM stream_progress WHERE stream_key=? LIMIT 1", new String[]{streamKey})) {
            if (c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) {}
        return 0L;
    }

    private static List<Long> findMatchingIndexedVideoIds(Context context, String streamKey, String mediaType,
                                                          String imdbId, long tmdbId, int season, int episode, String canonical) {
        List<Long> ids = new ArrayList<>();
        if (!isEmpty(streamKey) && streamKey.startsWith("v:")) {
            try {
                long id = Long.parseLong(streamKey.substring(2));
                if (id > 0) ids.add(id);
            } catch (NumberFormatException ignored) {}
        }
        if (ids.isEmpty() && !isEmpty(canonical)) {
            try (Cursor c = get(context).getReadableDatabase().rawQuery(
                    "SELECT media_key FROM media_identities WHERE canonical_key=? AND media_key LIKE 'v:%'",
                    new String[]{canonical})) {
                while (c.moveToNext()) {
                    try {
                        long id = Long.parseLong(c.getString(0).substring(2));
                        if (id > 0 && !ids.contains(id)) ids.add(id);
                    } catch (NumberFormatException ignored) {}
                }
            } catch (Exception ignored) {}
        }
        boolean isEp = "episode".equalsIgnoreCase(mediaType) || season > 0 || episode > 0;
        if (ids.isEmpty() && !isEmpty(imdbId)) {
            String sel = isEp
                    ? "(" + VideoStore.Video.VideoColumns.SCRAPER_E_IMDB_ID + "=? OR " + VideoStore.Video.VideoColumns.SCRAPER_S_IMDB_ID + "=?) AND "
                    + VideoStore.Video.VideoColumns.SCRAPER_E_SEASON + "=? AND " + VideoStore.Video.VideoColumns.SCRAPER_E_EPISODE + "=?"
                    : VideoStore.Video.VideoColumns.SCRAPER_M_IMDB_ID + "=?";
            String[] args = isEp
                    ? new String[]{imdbId, imdbId, Integer.toString(season), Integer.toString(episode)}
                    : new String[]{imdbId};
            try (Cursor c = context.getContentResolver().query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[]{VideoStore.Video.VideoColumns._ID}, sel, args, null)) {
                if (c != null) while (c.moveToNext()) ids.add(c.getLong(0));
            } catch (Exception ignored) {}
        }
        if (ids.isEmpty() && tmdbId > 0) {
            String sel = isEp
                    ? VideoStore.Video.VideoColumns.SCRAPER_S_ONLINE_ID + "=? AND "
                    + VideoStore.Video.VideoColumns.SCRAPER_E_SEASON + "=? AND " + VideoStore.Video.VideoColumns.SCRAPER_E_EPISODE + "=?"
                    : VideoStore.Video.VideoColumns.SCRAPER_M_ONLINE_ID + "=?";
            String[] args = isEp
                    ? new String[]{Long.toString(tmdbId), Integer.toString(season), Integer.toString(episode)}
                    : new String[]{Long.toString(tmdbId)};
            try (Cursor c = context.getContentResolver().query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[]{VideoStore.Video.VideoColumns._ID}, sel, args, null)) {
                if (c != null) while (c.moveToNext()) ids.add(c.getLong(0));
            } catch (Exception ignored) {}
        }
        String normTarget = canonical != null && canonical.startsWith("title:")
                ? canonical.substring(6) : "";
        if (ids.isEmpty() && !normTarget.isEmpty()) {
            String[] proj = {
                    VideoStore.Video.VideoColumns._ID,
                    VideoStore.Video.VideoColumns.ARCHOS_MEDIA_SCRAPER_TYPE,
                    VideoStore.Video.VideoColumns.SCRAPER_S_NAME,
                    VideoStore.Video.VideoColumns.SCRAPER_TITLE,
                    VideoStore.MediaColumns.TITLE,
                    VideoStore.Video.VideoColumns.SCRAPER_E_SEASON,
                    VideoStore.Video.VideoColumns.SCRAPER_E_EPISODE
            };
            String sel = isEp
                    ? VideoStore.Video.VideoColumns.SCRAPER_E_SEASON + "=? AND " + VideoStore.Video.VideoColumns.SCRAPER_E_EPISODE + "=?"
                    : null;
            String[] args = isEp ? new String[]{Integer.toString(season), Integer.toString(episode)} : null;
            try (Cursor c = context.getContentResolver().query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                    proj, sel, args, null)) {
                if (c != null) while (c.moveToNext()) {
                    boolean rowEp = c.getInt(1) == BaseTags.TV_SHOW;
                    if (isEp != rowEp) continue;
                    String rowName = rowEp ? c.getString(2) : (!isEmpty(c.getString(3)) ? c.getString(3) : c.getString(4));
                    if (normTarget.equals(normalizeTitle(rowName))) {
                        ids.add(c.getLong(0));
                    }
                }
            } catch (Exception ignored) {}
        }
        return ids;
    }

    static boolean isEpisodePlayback(int season, int episode, long positionMs, long durationMs) {
        return season >= 0 && episode > 0 && positionMs > 1
                && (durationMs <= 0 || positionMs < durationMs * 0.85);
    }

    static Video findLocalEpisode(Context context, String title, String imdb, long tmdb, int season, int episode) {
        if (season < 0 || episode <= 0) return null;
        String key = canonicalKey("episode", imdb, tmdb, season, episode, "", title);
        List<Long> ids = findMatchingIndexedVideoIds(context, "", "episode", imdb, tmdb, season, episode, key);
        boolean titleOnly = ids.isEmpty();
        if (titleOnly && !normalizeTitle(title).isEmpty() && !key.startsWith("title:")) {
            ids = findMatchingIndexedVideoIds(context, "", "episode", "", 0, season, episode,
                    "title:" + normalizeTitle(title));
        }
        // A title fallback must resolve to exactly one indexed episode.
        if (titleOnly && ids.size() != 1) return null;
        for (long id : ids) {
            try (Cursor c = context.getContentResolver().query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                    VideoLoader.mProjection, VideoStore.Video.VideoColumns._ID + "=?",
                    new String[]{Long.toString(id)}, null)) {
                if (c == null || !c.moveToFirst()) continue;
                if (titleOnly && conflictingShowIdentity(context, id, imdb, tmdb)) continue;
                var mapper = new VideoCursorMapper();
                mapper.bindColumns(c);
                if (!(mapper.bind(c) instanceof Episode match)
                        || match.getSeasonNumber() != season || match.getEpisodeNumber() != episode
                        || match.getFileUri() == null || match.isUserHidden()
                        || isNonLibraryVideoStoreItem(match.getFilePath(), match.getName(), null)) continue;
                String scheme = match.getFileUri().getScheme();
                if (scheme == null || "file".equals(scheme) || "smb".equals(scheme) || "content".equals(scheme)) {
                    return match;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static boolean conflictingShowIdentity(Context context, long id, String imdb, long tmdb) {
        try (Cursor cursor = context.getContentResolver().query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{VideoStore.Video.VideoColumns.SCRAPER_S_IMDB_ID, VideoStore.Video.VideoColumns.SCRAPER_S_ONLINE_ID},
                VideoStore.Video.VideoColumns._ID + "=?", new String[]{Long.toString(id)}, null)) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            String localImdb = cursor.getString(0);
            long localTmdb = cursor.getLong(1);
            return (!isEmpty(imdb) && !isEmpty(localImdb) && !imdb.equalsIgnoreCase(localImdb))
                    || (tmdb > 0 && localTmdb > 0 && tmdb != localTmdb);
        } catch (Exception ignored) {
            return true;
        }
    }

    private static long[] getVideoPlaybackInfo(Context context, long videoId) {
        try (Cursor c = context.getContentResolver().query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{
                        VideoStore.Video.VideoColumns.ARCHOS_LAST_TIME_PLAYED,
                        VideoStore.Video.VideoColumns.BOOKMARK,
                        VideoStore.Video.VideoColumns.DURATION
                },
                VideoStore.Video.VideoColumns._ID + "=?", new String[]{Long.toString(videoId)}, null)) {
            if (c != null && c.moveToFirst()) {
                return new long[]{
                        Math.max(0L, c.getLong(0)) * 1000L,
                        c.getLong(1),
                        Math.max(0L, c.getLong(2))
                };
            }
        } catch (Exception ignored) {}
        return new long[]{0L, 0L, 0L};
    }

    private static boolean applyWatchStateToIndexedVideo(Context context, long videoId, long posMs,
                                                         boolean watched, boolean removed, long updatedAtMs) {
        try {
            ContentValues cv = new ContentValues();
            if (watched) {
                cv.put(VideoStore.Video.VideoColumns.BOOKMARK, PlayerActivity.LAST_POSITION_END);
                cv.put(VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN, Trakt.TRAKT_DB_MARKED);
                cv.put(VideoStore.Video.VideoColumns.ARCHOS_LAST_TIME_PLAYED, updatedAtMs / 1000L);
            } else if (removed || posMs <= 0) {
                cv.put(VideoStore.Video.VideoColumns.BOOKMARK, PlayerActivity.LAST_POSITION_UNKNOWN);
                cv.put(VideoStore.Video.VideoColumns.ARCHOS_BOOKMARK, 0);
                cv.put(VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN, Trakt.TRAKT_DB_UNMARK);
                cv.put(VideoStore.Video.VideoColumns.ARCHOS_LAST_TIME_PLAYED, 0);
            } else {
                cv.put(VideoStore.Video.VideoColumns.BOOKMARK, (int) Math.min(Integer.MAX_VALUE, posMs));
                cv.put(VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN, 0);
                cv.put(VideoStore.Video.VideoColumns.ARCHOS_LAST_TIME_PLAYED, updatedAtMs / 1000L);
            }
            int updated = context.getContentResolver().update(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, cv,
                    VideoStore.Video.VideoColumns._ID + "=?", new String[]{Long.toString(videoId)});
            if (updated > 0) {
                if (watched) {
                    CinemaMediaTime.rememberState(videoId, null, 0L, PlayerActivity.LAST_POSITION_END);
                } else {
                    CinemaMediaTime.clearResume(videoId, null, null);
                    if (!removed && posMs > 0) CinemaMediaTime.rememberState(videoId, null, 0L, posMs);
                }
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static List<com.meedx.providers.TraktSyncClient.SyncItem> listPendingTraktItems(Context context) {
        List<com.meedx.providers.TraktSyncClient.SyncItem> out = new ArrayList<>();
        if (context == null) return out;
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT stream_key,media_type,imdb_id,tmdb_id,season,episode,position_ms,duration_ms,watched,removed_from_continue,updated_at "
                        + "FROM stream_progress WHERE pending_trakt_sync=1 AND (imdb_id!='' OR tmdb_id>0) "
                        + "ORDER BY updated_at DESC LIMIT 80", null)) {
            while (c.moveToNext()) {
                out.add(new com.meedx.providers.TraktSyncClient.SyncItem(
                        c.getString(0), c.getString(1), c.getString(2), c.getLong(3),
                        c.getInt(4), c.getInt(5), c.getLong(6), c.getLong(7),
                        c.getInt(8) == 1, c.getInt(9) == 1, c.getLong(10)));
            }
        } catch (Exception ignored) {}
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT local_key,media_type,imdb_id,tmdb_id,season,episode,position_ms,duration_ms,watched,removed,updated_at "
                        + "FROM trakt_outbox ORDER BY updated_at DESC LIMIT 80", null)) {
            while (c.moveToNext()) {
                out.add(new com.meedx.providers.TraktSyncClient.SyncItem(
                        c.getString(0), c.getString(1), c.getString(2), c.getLong(3),
                        c.getInt(4), c.getInt(5), c.getLong(6), c.getLong(7),
                        c.getInt(8) == 1, c.getInt(9) == 1, c.getLong(10)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void clearPendingTraktSync(Context context, List<String> keys) {
        if (context == null || keys == null || keys.isEmpty()) return;
        try {
            SQLiteDatabase db = get(context).getWritableDatabase();
            db.beginTransaction();
            try {
                ContentValues cv = new ContentValues();
                cv.put("pending_trakt_sync", 0);
                for (String k : keys) {
                    if (k == null || k.isBlank()) continue;
                    if (k.startsWith("trakt:")) {
                        db.delete("trakt_outbox", "local_key=?", new String[]{k});
                    } else {
                        db.update("stream_progress", cv, "stream_key=?", new String[]{k});
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception ignored) {}
    }

    /** Push pending watch changes and pull Trakt watched/playback/watchlist state into local store + LAN peers. */
    public static int syncWithTrakt(Context context) throws Exception {
        if (context == null || !com.meedx.providers.TraktSyncClient.isConnected(context)) return 0;
        Context app = context.getApplicationContext();
        List<com.meedx.providers.TraktSyncClient.SyncItem> pending = listPendingTraktItems(app);
        List<String> pushed = com.meedx.providers.TraktSyncClient.pushPending(app, pending);
        clearPendingTraktSync(app, pushed);
        JSONArray pulled = com.meedx.providers.TraktSyncClient.pullRecords(app);
        int applied = 0;
        SQLiteDatabase db = get(app).getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("trakt_items", "list_type IN ('continue','watched')", null);
            for (int i = 0; i < pulled.length(); i++) {
                JSONObject rec = pulled.optJSONObject(i);
                if (rec == null) continue;
                String lt = rec.optBoolean("watched", false) ? "watched" : "continue";
                String mt = rec.optString("media_type", "movie");
                String imdb = rec.optString("imdb_id", "");
                long tmdb = rec.optLong("tmdb_id", 0L);
                int season = rec.optInt("season", 0);
                int ep = rec.optInt("episode", 0);
                String title = rec.optString("title", "");
                if ("continue".equals(lt) && (rec.optLong("position_ms") <= 1
                        || ep > 0 || "episode".equalsIgnoreCase(mt) || "show".equalsIgnoreCase(mt))) continue;
                String key = canonicalKey(mt, imdb, tmdb, season, ep, "", title);
                if (!key.isBlank()) {
                    ContentValues cv = new ContentValues();
                    cv.put("list_type", lt);
                    cv.put("item_key", key);
                    cv.put("media_type", mt);
                    cv.put("title", title);
                    cv.put("imdb_id", imdb);
                    cv.put("tmdb_id", tmdb);
                    cv.put("season", season);
                    cv.put("episode", ep);
                    cv.put("year", rec.optInt("year", 0));
                    cv.put("progress_pct", rec.optDouble("progress_pct", 0.0));
                    cv.put("position_ms", rec.optLong("position_ms", 0L));
                    cv.put("duration_ms", rec.optLong("duration_ms", 0L));
                    cv.put("updated_at", rec.optLong("updated_at", System.currentTimeMillis()));
                    db.insertWithOnConflict("trakt_items", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        for (int i = 0; i < pulled.length(); i++) {
            JSONObject rec = pulled.optJSONObject(i);
            if (rec != null && applyRemoteSyncRecord(app, rec, false)) {
                applied++;
            }
        }
        int wlCount = syncTraktWatchlist(app);
        CinemaLinkServer.broadcastWatchSync(app);
        app.sendBroadcast(new android.content.Intent(CinemaLinkServer.ACTION_WATCH_SYNC_UPDATED)
                .setPackage(app.getPackageName()));
        return pushed.size() + Math.max(applied, pulled.length()) + wlCount;
    }

    public static void applyTraktItemsFromSyncRecords(Context context, JSONArray watchRecords) {
        if (context == null || watchRecords == null || watchRecords.length() == 0) return;
        try {
            SQLiteDatabase db = get(context.getApplicationContext()).getWritableDatabase();
            db.beginTransaction();
            try {
                for (int i = 0; i < watchRecords.length(); i++) {
                    JSONObject rec = watchRecords.optJSONObject(i);
                    if (rec == null) continue;
                    String kind = rec.optString("kind", "");
                    String imdb = rec.optString("imdb_id", "");
                    long tmdb = rec.optLong("tmdb_id", 0L);
                    String title = rec.optString("title", "");
                    if (!"trakt".equalsIgnoreCase(kind) && imdb.isBlank() && tmdb <= 0) continue;
                    boolean watched = rec.optBoolean("watched", false);
                    boolean removed = rec.optBoolean("removed_from_continue", false);
                    String mt = rec.optString("media_type", "movie");
                    int season = rec.optInt("season", 0);
                    int ep = rec.optInt("episode", 0);
                    String key = canonicalKey(mt, imdb, tmdb, season, ep, "", title);
                    if (key.isBlank()) continue;
                    if (removed && !watched) {
                        db.delete("trakt_items", "list_type='continue' AND item_key=?", new String[]{key});
                        continue;
                    }
                    String lt = watched ? "watched" : "continue";
                    long posMs = rec.optLong("position_ms", 0L);
                    long durMs = rec.optLong("duration_ms", 0L);
                    if (!watched && (durMs <= 0 || posMs <= 1
                            || ("show".equalsIgnoreCase(mt))
                            || ((ep > 0 || "episode".equalsIgnoreCase(mt))
                            && ("trakt".equalsIgnoreCase(kind) || !isEpisodePlayback(season, ep, posMs, durMs))))) continue;
                    double pct = rec.optDouble("progress_pct", durMs > 0 ? (posMs * 100.0 / durMs) : 0.0);
                    ContentValues cv = new ContentValues();
                    cv.put("list_type", lt);
                    cv.put("item_key", key);
                    cv.put("media_type", mt);
                    cv.put("title", title);
                    cv.put("imdb_id", imdb);
                    cv.put("tmdb_id", tmdb);
                    cv.put("season", season);
                    cv.put("episode", ep);
                    cv.put("year", rec.optInt("year", 0));
                    cv.put("progress_pct", pct);
                    cv.put("position_ms", posMs);
                    cv.put("duration_ms", durMs);
                    cv.put("updated_at", rec.optLong("updated_at", System.currentTimeMillis()));
                    db.insertWithOnConflict("trakt_items", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception ignored) {}
    }

    public static boolean isInTraktWatchlist(Context context, Video video) {
        if (context == null || video == null) return false;
        String rawTitle = video instanceof Episode ep && !isEmpty(ep.getShowName()) ? ep.getShowName() : video.getName();
        String normTitle = normalizeTitle(rawTitle);
        String fileUri = video.getFileUri() != null ? video.getFileUri().toString() : video.getFilePath();
        Identity id = video.getId() > 0 ? getIdentity(context, "v:" + video.getId()) : getIdentityByUri(context, fileUri);
        String imdb = id != null ? id.imdbId() : "";
        long tmdb = id != null ? id.tmdbId() : (video instanceof Movie m ? m.getOnlineId() : 0L);
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT imdb_id,tmdb_id,title FROM trakt_watchlist", null)) {
            while (c.moveToNext()) {
                String wImdb = c.getString(0);
                long wTmdb = c.getLong(1);
                String wTitle = c.getString(2);
                if (!isEmpty(imdb) && imdb.equalsIgnoreCase(wImdb)) return true;
                if (tmdb > 0 && tmdb == wTmdb) return true;
                if (!normTitle.isEmpty() && normTitle.equals(normalizeTitle(wTitle))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static void updateLocalTraktWatchlistRow(Context context, boolean isShow, String title,
                                                    String imdbId, long tmdbId, int year, boolean added) {
        if (context == null) return;
        String mt = isShow ? "show" : "movie";
        String imdb = imdbId == null ? "" : imdbId.trim();
        String cleanTitle = title == null ? "" : title.trim();
        String norm = normalizeTitle(cleanTitle);
        String key = !imdb.isEmpty() ? mt + ":" + imdb
                : (tmdbId > 0 ? mt + ":tmdb:" + tmdbId : mt + ":t:" + norm);
        try {
            SQLiteDatabase db = get(context.getApplicationContext()).getWritableDatabase();
            if (added) {
                long now = System.currentTimeMillis();
                ContentValues cv = new ContentValues();
                cv.put("item_key", key);
                cv.put("media_type", mt);
                cv.put("title", cleanTitle);
                cv.put("imdb_id", imdb);
                cv.put("tmdb_id", Math.max(0L, tmdbId));
                cv.put("year", Math.max(0, year));
                cv.put("updated_at", now);
                db.insertWithOnConflict("trakt_watchlist", null, cv, SQLiteDatabase.CONFLICT_REPLACE);

                ContentValues tv = new ContentValues();
                tv.put("list_type", "watchlist");
                tv.put("item_key", key);
                tv.put("media_type", mt);
                tv.put("title", cleanTitle);
                tv.put("imdb_id", imdb);
                tv.put("tmdb_id", Math.max(0L, tmdbId));
                tv.put("season", 0);
                tv.put("episode", 0);
                tv.put("year", Math.max(0, year));
                tv.put("progress_pct", 0.0);
                tv.put("position_ms", 0L);
                tv.put("duration_ms", 0L);
                tv.put("updated_at", now);
                db.insertWithOnConflict("trakt_items", null, tv, SQLiteDatabase.CONFLICT_REPLACE);
            } else {
                db.delete("trakt_watchlist", "item_key=? OR (imdb_id!='' AND imdb_id=?)", new String[]{key, imdb});
                db.delete("trakt_items", "list_type='watchlist' AND (item_key=? OR (imdb_id!='' AND imdb_id=?))", new String[]{key, imdb});
                if (!norm.isEmpty()) {
                    try (Cursor c = db.rawQuery("SELECT item_key,title FROM trakt_watchlist", null)) {
                        while (c.moveToNext()) {
                            if (norm.equals(normalizeTitle(c.getString(1)))) {
                                String k = c.getString(0);
                                db.delete("trakt_watchlist", "item_key=?", new String[]{k});
                                db.delete("trakt_items", "list_type='watchlist' AND item_key=?", new String[]{k});
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public static JSONArray exportWatchlistJson(Context context) {
        JSONArray arr = new JSONArray();
        if (context == null) return arr;
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT item_key,media_type,title,imdb_id,tmdb_id,year,updated_at FROM trakt_watchlist ORDER BY updated_at DESC LIMIT 80", null)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("item_key", c.getString(0));
                o.put("media_type", c.getString(1));
                o.put("title", c.getString(2));
                o.put("imdb_id", c.getString(3));
                o.put("tmdb_id", c.getLong(4));
                o.put("year", c.getInt(5));
                o.put("updated_at", c.getLong(6));
                arr.put(o);
            }
        } catch (Exception ignored) {}
        return arr;
    }

    private static int syncTraktWatchlist(Context context) {
        try {
            JSONArray wl = com.meedx.providers.TraktSyncClient.pullWatchlist(context);
            return applySyncedWatchlistFromPhone(context, wl);
        } catch (Exception ignored) {
            return 0;
        }
    }

    public static int applySyncedWatchlistFromPhone(Context context, JSONArray wl) {
        if (context == null || wl == null) return 0;
        try {
            Context app = context.getApplicationContext();
            SQLiteDatabase db = get(app).getWritableDatabase();
            var prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(app);
            var prevTraktPaths = new java.util.HashSet<>(prefs.getStringSet(PREF_TRAKT_WATCHLIST_PATHS, java.util.Set.of()));
            var saved = new java.util.HashSet<>(prefs.getStringSet(CinemaWatchlistActivity.KEY, java.util.Set.of()));
            saved.removeAll(prevTraktPaths);
            var nextTraktPaths = new java.util.HashSet<String>();
            db.beginTransaction();
            try {
                db.delete("trakt_watchlist", null, null);
                db.delete("trakt_items", "list_type='watchlist'", null);
                for (int i = 0; i < wl.length(); i++) {
                    JSONObject item = wl.optJSONObject(i);
                    if (item == null) continue;
                    String mt = item.optString("media_type", "movie");
                    String imdb = item.optString("imdb_id", "");
                    long tmdb = item.optLong("tmdb_id", 0L);
                    String title = item.optString("title", "");
                    int year = item.optInt("year", 0);
                    long ts = item.optLong("updated_at", System.currentTimeMillis());
                    String key = !imdb.isEmpty() ? mt + ":" + imdb
                            : (tmdb > 0 ? mt + ":tmdb:" + tmdb : mt + ":t:" + normalizeTitle(title));
                    ContentValues cv = new ContentValues();
                    cv.put("item_key", key);
                    cv.put("media_type", mt);
                    cv.put("title", title);
                    cv.put("imdb_id", imdb);
                    cv.put("tmdb_id", tmdb);
                    cv.put("year", year);
                    cv.put("updated_at", ts);
                    db.insertWithOnConflict("trakt_watchlist", null, cv, SQLiteDatabase.CONFLICT_REPLACE);

                    ContentValues tv = new ContentValues();
                    tv.put("list_type", "watchlist");
                    tv.put("item_key", key);
                    tv.put("media_type", mt);
                    tv.put("title", title);
                    tv.put("imdb_id", imdb);
                    tv.put("tmdb_id", tmdb);
                    tv.put("season", 0);
                    tv.put("episode", 0);
                    tv.put("year", year);
                    tv.put("progress_pct", 0.0);
                    tv.put("position_ms", 0L);
                    tv.put("duration_ms", 0L);
                    tv.put("updated_at", ts);
                    db.insertWithOnConflict("trakt_items", null, tv, SQLiteDatabase.CONFLICT_REPLACE);

                    // Match against indexed VideoStore files (IMDb -> TMDb -> Normalized Title)
                    boolean foundById = false;
                    if (!imdb.isEmpty()) {
                        String sel = "show".equals(mt)
                                ? VideoStore.Video.VideoColumns.SCRAPER_S_IMDB_ID + "=?"
                                : VideoStore.Video.VideoColumns.SCRAPER_M_IMDB_ID + "=?";
                        try (Cursor c = app.getContentResolver().query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                                new String[]{VideoStore.MediaColumns.DATA}, sel, new String[]{imdb}, null)) {
                            if (c != null) while (c.moveToNext()) {
                                String path = c.getString(0);
                                if (path != null && !path.isBlank()) {
                                    saved.add(path);
                                    nextTraktPaths.add(path);
                                    foundById = true;
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    if (!foundById && tmdb > 0) {
                        String sel = "show".equals(mt)
                                ? VideoStore.Video.VideoColumns.SCRAPER_S_ONLINE_ID + "=?"
                                : VideoStore.Video.VideoColumns.SCRAPER_M_ONLINE_ID + "=?";
                        try (Cursor c = app.getContentResolver().query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                                new String[]{VideoStore.MediaColumns.DATA}, sel, new String[]{Long.toString(tmdb)}, null)) {
                            if (c != null) while (c.moveToNext()) {
                                String path = c.getString(0);
                                if (path != null && !path.isBlank()) {
                                    saved.add(path);
                                    nextTraktPaths.add(path);
                                    foundById = true;
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    if (!foundById && !title.isBlank()) {
                        String normTarget = normalizeTitle(title);
                        try (Cursor c = app.getContentResolver().query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                                new String[]{
                                        VideoStore.MediaColumns.DATA,
                                        VideoStore.Video.VideoColumns.ARCHOS_MEDIA_SCRAPER_TYPE,
                                        VideoStore.Video.VideoColumns.SCRAPER_S_NAME,
                                        VideoStore.Video.VideoColumns.SCRAPER_TITLE,
                                        VideoStore.MediaColumns.TITLE
                                }, null, null, null)) {
                            if (c != null) while (c.moveToNext()) {
                                boolean rowShow = c.getInt(1) == BaseTags.TV_SHOW;
                                if ("show".equals(mt) != rowShow) continue;
                                String rowName = rowShow ? c.getString(2) : (!isEmpty(c.getString(3)) ? c.getString(3) : c.getString(4));
                                if (normTarget.equals(normalizeTitle(rowName))) {
                                    String path = c.getString(0);
                                    if (path != null && !path.isBlank()) {
                                        saved.add(path);
                                        nextTraktPaths.add(path);
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            prefs.edit()
                    .putStringSet(CinemaWatchlistActivity.KEY, saved)
                    .putStringSet(PREF_TRAKT_WATCHLIST_PATHS, nextTraktPaths)
                    .commit();
            return wl.length();
        } catch (Exception ignored) {
            return 0;
        }
    }

    static void removeTraktContinue(Context context, TraktItem item) {
        get(context).getWritableDatabase().delete("trakt_items",
                "list_type='continue' AND item_key=?", new String[]{item.itemKey()});
        clearMatchingContinue(context, item.imdbId(), item.tmdbId(), item.season(), item.episode());
        TRAKT_PUSH_IO.execute(() -> com.meedx.providers.TraktSyncClient.removePlaybackProgress(
                context, item.imdbId(), item.tmdbId(), item.season(), item.episode()));
    }

    static void clearMatchingContinue(Context context, String imdb, long tmdb, int season, int episode) {
        var db = get(context).getWritableDatabase();
        String match = "season=? AND episode=? AND ((imdb_id != '' AND imdb_id=?) OR (tmdb_id > 0 AND tmdb_id=?))";
        imdb = imdb == null ? "" : imdb;
        String[] args = {Integer.toString(season), Integer.toString(episode), imdb, Long.toString(tmdb)};
        db.delete("trakt_items", "list_type='continue' AND " + match, args);
        var values = new ContentValues();
        values.put("removed_from_continue", 1);
        values.put("position_ms", 0);
        db.update("stream_progress", values, match, args);
    }

    public static List<TraktItem> listTraktItems(Context context, String listType) {
        List<TraktItem> out = new ArrayList<>();
        if (context == null || listType == null) return out;
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT list_type,item_key,media_type,title,imdb_id,tmdb_id,season,episode,year,progress_pct,position_ms,duration_ms,updated_at "
                        + "FROM trakt_items WHERE list_type=? ORDER BY updated_at DESC LIMIT 80",
                new String[]{listType})) {
            while (c.moveToNext()) {
                out.add(new TraktItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getString(4), c.getLong(5), c.getInt(6), c.getInt(7), c.getInt(8),
                        c.getDouble(9), c.getLong(10), c.getLong(11), c.getLong(12)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void pushVideoWatchedToTraktAsync(Context context, Video video, boolean watched) {
        if (context == null || video == null) return;
        Context app = context.getApplicationContext();
        String uri = video.getFileUri() != null ? video.getFileUri().toString() : video.getFilePath();
        String name = video instanceof Episode ep && !isEmpty(ep.getShowName()) ? ep.getShowName() : video.getName();
        long vid = video.getId();
        boolean isEp = video instanceof Episode;
        int epSeason = video instanceof Episode ep ? Math.max(0, ep.getSeasonNumber()) : 0;
        int epNum = video instanceof Episode ep ? Math.max(0, ep.getEpisodeNumber()) : 0;
        TRAKT_PUSH_IO.execute(() -> {
            PlaybackDescriptor desc = resolvePlaybackDescriptor(app, vid, uri, name);
            if (desc == null || (isEmpty(desc.imdbId()) && desc.tmdbId() <= 0)) return;
            int s = desc.season() > 0 ? desc.season() : epSeason;
            int e = desc.episode() > 0 ? desc.episode() : epNum;
            boolean ok = com.meedx.providers.TraktSyncClient.syncHistoryItem(
                    app, isEp || "episode".equalsIgnoreCase(desc.mediaType()),
                    desc.imdbId(), desc.tmdbId(), s, e, watched);
            if (ok) {
                clearPendingTraktSync(app, java.util.List.of("trakt:" + desc.canonicalKey()));
                try { syncWithTrakt(app); } catch (Exception ignored) {}
            }
        });
    }

    public static void removePlaybackProgressFromTraktAsync(Context context, long videoId, String uri, String title,
                                                            String imdbId, long tmdbId, int season, int episode) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        TRAKT_PUSH_IO.execute(() -> {
            String efImdb = imdbId == null ? "" : imdbId;
            long efTmdb = tmdbId;
            int efS = season;
            int efE = episode;
            String efMt = (season > 0 || episode > 0) ? "episode" : "movie";
            if ((isEmpty(efImdb) && efTmdb <= 0) && (videoId > 0 || !isEmpty(uri) || !isEmpty(title))) {
                PlaybackDescriptor desc = resolvePlaybackDescriptor(app, videoId, uri, title);
                if (desc != null) {
                    efImdb = desc.imdbId();
                    efTmdb = desc.tmdbId();
                    if (efS <= 0) efS = desc.season();
                    if (efE <= 0) efE = desc.episode();
                    efMt = desc.mediaType();
                }
            }
            String key = canonicalKey(efMt, efImdb, efTmdb, efS, efE, "", title);
            if (!key.isBlank()) {
                try {
                    get(app).getWritableDatabase().delete("trakt_items",
                            "list_type='continue' AND item_key=?", new String[]{key});
                } catch (Exception ignored) {}
            }
            if (com.meedx.providers.TraktSyncClient.isConnected(app) && (!isEmpty(efImdb) || efTmdb > 0)) {
                com.meedx.providers.TraktSyncClient.removePlaybackProgress(app, efImdb, efTmdb, efS, efE);
            }
        });
    }

    public static void pushPlaybackScrobbleAsync(Context context, long videoId, String uri, String title,
                                                String mediaType, String imdbId, long tmdbId,
                                                int season, int episode, long posMs, long durMs, boolean completed) {
        if (context == null || !com.meedx.providers.TraktSyncClient.isConnected(context)) return;
        if (isEphemeralLiveCast(uri, title, mediaType, imdbId, tmdbId)) return;
        if (durMs <= 0 || posMs < 0) return;
        Context app = context.getApplicationContext();
        TRAKT_PUSH_IO.execute(() -> {
            String efImdb = imdbId == null ? "" : imdbId;
            long efTmdb = tmdbId;
            int efS = season;
            int efE = episode;
            String efMt = mediaType;
            if ((efImdb.isBlank() && efTmdb <= 0) && (videoId > 0 || !isEmpty(uri))) {
                PlaybackDescriptor desc = resolvePlaybackDescriptor(app, videoId, uri, title);
                if (desc != null) {
                    efImdb = desc.imdbId();
                    efTmdb = desc.tmdbId();
                    efS = desc.season();
                    efE = desc.episode();
                    efMt = desc.mediaType();
                }
            }
            if (isEmpty(efImdb) && efTmdb <= 0) return;
            double pct = completed ? 100.0 : Math.max(1.0, Math.min(89.9, (posMs * 100.0) / durMs));
            boolean isEp = "episode".equalsIgnoreCase(efMt) || efS > 0 || efE > 0;
            com.meedx.providers.TraktSyncClient.scrobble(
                    app, completed ? "stop" : "pause", isEp, efImdb, efTmdb, efS, efE, pct);
        });
    }

    private static final java.util.concurrent.ExecutorService TRAKT_PUSH_IO =
            java.util.concurrent.Executors.newCachedThreadPool();

    public static void syncWithTraktAsync(Context context) {
        if (context == null || !com.meedx.providers.TraktSyncClient.isConnected(context)) return;
        Context app = context.getApplicationContext();
        TRAKT_PUSH_IO.execute(() -> {
            try { syncWithTrakt(app); } catch (Exception ignored) {}
        });
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isBlank();
    }
}

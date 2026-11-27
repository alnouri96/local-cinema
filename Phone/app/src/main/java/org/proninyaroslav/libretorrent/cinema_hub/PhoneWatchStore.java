package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local SQLite store on Phone strictly for Phone Local Cinema library resume/watched state (zero Trakt list caching). */
public final class PhoneWatchStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "phone_watch_store.db";
    private static final int DB_VERSION = 4;
    private static volatile PhoneWatchStore instance;

    public static final class Entry {
        public final String itemKey;
        public final String canonicalKey;
        public final String kind;
        public final String title;
        public final String sourceUri;
        public final String torrentId;
        public final int fileIndex;
        public final String format;
        public final String mediaType;
        public final String imdbId;
        public final long tmdbId;
        public final int season;
        public final int episode;
        public final long positionMs;
        public final long durationMs;
        public final boolean watched;
        public final boolean removedFromContinue;
        public final boolean pendingTraktSync;
        public final long updatedAt;

        public Entry(String itemKey, String canonicalKey, String kind, String title, String sourceUri,
                     String torrentId, int fileIndex, String format, String mediaType, String imdbId,
                     long tmdbId, int season, int episode, long positionMs, long durationMs,
                     boolean watched, boolean removedFromContinue, boolean pendingTraktSync, long updatedAt) {
            this.itemKey = itemKey;
            this.canonicalKey = canonicalKey;
            this.kind = kind;
            this.title = title;
            this.sourceUri = sourceUri;
            this.torrentId = torrentId;
            this.fileIndex = fileIndex;
            this.format = format;
            this.mediaType = mediaType;
            this.imdbId = imdbId;
            this.tmdbId = tmdbId;
            this.season = season;
            this.episode = episode;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.watched = watched;
            this.removedFromContinue = removedFromContinue;
            this.pendingTraktSync = pendingTraktSync;
            this.updatedAt = updatedAt;
        }
    }

    private PhoneWatchStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    public static PhoneWatchStore get(Context context) {
        if (instance == null) {
            synchronized (PhoneWatchStore.class) {
                if (instance == null) {
                    instance = new PhoneWatchStore(context);
                }
            }
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS watch_entries ("
                + "item_key TEXT PRIMARY KEY, "
                + "canonical_key TEXT, "
                + "kind TEXT, "
                + "title TEXT, "
                + "source_uri TEXT, "
                + "torrent_id TEXT, "
                + "file_index INTEGER DEFAULT 0, "
                + "format TEXT, "
                + "media_type TEXT, "
                + "imdb_id TEXT, "
                + "tmdb_id INTEGER DEFAULT 0, "
                + "season INTEGER DEFAULT 0, "
                + "episode INTEGER DEFAULT 0, "
                + "position_ms INTEGER DEFAULT 0, "
                + "duration_ms INTEGER DEFAULT 0, "
                + "watched INTEGER DEFAULT 0, "
                + "removed_from_continue INTEGER DEFAULT 0, "
                + "pending_trakt_sync INTEGER DEFAULT 0, "
                + "updated_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_watch_canonical ON watch_entries(canonical_key)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE watch_entries ADD COLUMN canonical_key TEXT"); } catch (Exception ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_watch_canonical ON watch_entries(canonical_key)"); } catch (Exception ignored) {}
        }
        onCreate(db);
        if (oldVersion < 4) {
            try { db.execSQL("DROP TABLE IF EXISTS trakt_watchlist"); } catch (Exception ignored) {}
            try { db.delete("watch_entries", "kind IN ('trakt','tv')", null); } catch (Exception ignored) {}
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

    private static final java.util.regex.Pattern TEMP_CAPTURE_NAME = java.util.regex.Pattern.compile(
            "(?i)^(?:screen[-_ ]?recording.*|\\d{8}[_-]\\d{6}.*|(?:vid|img|pxl)[_-]\\d+.*|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*|https?://.*)");

    public static boolean isExcludedNonLibrary(String itemKey, String kind, String title, String sourceUri) {
        String k = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        if (!"library".equals(k)) {
            return true;
        }
        String keyStr = itemKey == null ? "" : itemKey.trim();
        String uriStr = sourceUri == null ? "" : sourceUri.trim();
        String lowKey = keyStr.toLowerCase(Locale.ROOT);
        String lowUri = uriStr.toLowerCase(Locale.ROOT);
        if (lowKey.startsWith("web:") || lowKey.startsWith("local:")
                || lowKey.contains("phone_local") || lowUri.contains("phone_local")
                || lowUri.contains(":8766/") || lowUri.contains("/video/")
                || lowUri.contains("/proxy/") || lowUri.contains("/local/")
                || lowUri.startsWith("content://")
                || lowUri.startsWith("http://10.") || lowUri.startsWith("http://192.168.") || lowUri.startsWith("http://172.")) {
            return true;
        }
        if ("library".equals(k) && (lowKey.contains("/storage/") || lowUri.contains("/storage/"))
                && !lowKey.contains("/local cinema/") && !lowUri.contains("/local cinema/")) {
            return true;
        }
        String t = title == null ? "" : title.trim();
        if (!t.isEmpty() && TEMP_CAPTURE_NAME.matcher(t).matches()) return true;
        String fileLeaf = extractLeafName(!uriStr.isEmpty() ? uriStr : keyStr);
        return !fileLeaf.isEmpty() && TEMP_CAPTURE_NAME.matcher(fileLeaf).matches();
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

    public static void purgeNonLibraryEntries(Context context) {
        if (context == null) return;
        try {
            SQLiteDatabase db = get(context).getWritableDatabase();
            var toDelete = new ArrayList<String>();
            try (Cursor c = db.rawQuery("SELECT item_key,kind,title,source_uri FROM watch_entries", null)) {
                while (c.moveToNext()) {
                    if (isExcludedNonLibrary(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) {
                        toDelete.add(c.getString(0));
                    }
                }
            }
            if (!toDelete.isEmpty()) {
                db.beginTransaction();
                try {
                    for (String k : toDelete) {
                        db.delete("watch_entries", "item_key=?", new String[]{k});
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
        } catch (Exception ignored) {}
    }

    public static void saveProgress(Context context, String itemKey, String kind, String title,
                                    String sourceUri, String torrentId, int fileIndex, String format,
                                    String mediaType, String imdbId, long tmdbId, int season, int episode,
                                    long positionMs, long durationMs) {
        saveProgress(context, itemKey, kind, title, sourceUri, torrentId, fileIndex, format,
                mediaType, imdbId, tmdbId, season, episode, positionMs, durationMs, false);
    }

    public static void saveProgress(Context context, String itemKey, String kind, String title,
                                    String sourceUri, String torrentId, int fileIndex, String format,
                                    String mediaType, String imdbId, long tmdbId, int season, int episode,
                                    long positionMs, long durationMs, boolean forceWatched) {
        if (context == null || itemKey == null || itemKey.isBlank()) return;
        if (isExcludedNonLibrary(itemKey, kind, title, sourceUri)) return;
        if (!forceWatched && (durationMs <= 0 || positionMs < 0)) return;
        boolean watched = forceWatched || (durationMs > 0 && positionMs * 100L >= durationMs * 85L);
        long savedPos = watched ? 0L : Math.max(0L, positionMs);
        String resolvedType = mediaType == null || mediaType.isBlank()
                ? (season > 0 || episode > 0 ? "episode" : "movie") : mediaType;
        String canonical = canonicalKey(resolvedType, imdbId, tmdbId, season, episode, itemKey, title);
        try {
            ContentValues cv = new ContentValues();
            cv.put("item_key", itemKey);
            cv.put("canonical_key", canonical);
            cv.put("kind", kind == null ? "library" : kind);
            cv.put("title", title == null ? "" : title);
            cv.put("source_uri", sourceUri == null ? "" : sourceUri);
            cv.put("torrent_id", torrentId == null ? "" : torrentId);
            cv.put("file_index", fileIndex);
            cv.put("format", format == null ? "" : format);
            cv.put("media_type", resolvedType);
            cv.put("imdb_id", imdbId == null ? "" : imdbId);
            cv.put("tmdb_id", Math.max(0L, tmdbId));
            cv.put("season", Math.max(0, season));
            cv.put("episode", Math.max(0, episode));
            cv.put("position_ms", savedPos);
            cv.put("duration_ms", Math.max(0L, durationMs));
            cv.put("watched", watched ? 1 : 0);
            cv.put("removed_from_continue", 0);
            cv.put("pending_trakt_sync", 1);
            cv.put("updated_at", System.currentTimeMillis());
            get(context).getWritableDatabase().insertWithOnConflict("watch_entries", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception ignored) {}
    }

    public static Entry markWatched(Context context, String itemKey, String kind, String title,
                                    String sourceUri, boolean watched) {
        if (context == null || itemKey == null || itemKey.isBlank()) return null;
        if (isExcludedNonLibrary(itemKey, kind, title, sourceUri)) return null;
        long now = System.currentTimeMillis();
        try {
            SQLiteDatabase db = get(context).getWritableDatabase();
            Entry existing = getEntry(context, itemKey);
            if (existing == null) {
                String canonical = canonicalKey("movie", "", 0L, 0, 0, itemKey, title);
                ContentValues cv = new ContentValues();
                cv.put("item_key", itemKey);
                cv.put("canonical_key", canonical);
                cv.put("kind", kind == null ? "library" : kind);
                cv.put("title", title == null ? "" : title);
                cv.put("source_uri", sourceUri == null ? "" : sourceUri);
                cv.put("media_type", "movie");
                cv.put("position_ms", 0L);
                cv.put("duration_ms", 0L);
                cv.put("watched", watched ? 1 : 0);
                cv.put("removed_from_continue", watched ? 0 : 1);
                cv.put("pending_trakt_sync", 1);
                cv.put("updated_at", now);
                db.insertWithOnConflict("watch_entries", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            } else {
                ContentValues cv = new ContentValues();
                cv.put("watched", watched ? 1 : 0);
                cv.put("position_ms", 0L);
                cv.put("removed_from_continue", watched ? 0 : 1);
                cv.put("pending_trakt_sync", 1);
                cv.put("updated_at", now);
                db.update("watch_entries", cv, "item_key=?", new String[]{itemKey});
            }
            return getEntry(context, itemKey);
        } catch (Exception ignored) {}
        return null;
    }

    static void refreshLibraryFiles(Context context, java.io.File root, java.io.File from, java.io.File to) {
        if (!root.isDirectory() || !root.canRead()) return;
        String prefix = root.getAbsolutePath() + java.io.File.separator;
        SQLiteDatabase db = get(context).getWritableDatabase();
        db.beginTransaction();
        try (var cursor = db.query("watch_entries", new String[]{"item_key", "canonical_key", "source_uri"},
                "kind=? AND item_key LIKE ?", new String[]{"library", "lib:%"}, null, null, null)) {
            while (cursor.moveToNext()) {
                String key = cursor.getString(0);
                String source = cursor.getString(2);
                if (source == null || !source.startsWith("file:")) continue;
                String path = android.net.Uri.parse(source).getPath();
                if (path == null || !path.startsWith(prefix)) continue;
                if (from != null && (path.equals(from.getAbsolutePath())
                        || path.startsWith(from.getAbsolutePath() + java.io.File.separator))) {
                    var destination = new java.io.File(to.getAbsolutePath() + path.substring(from.getAbsolutePath().length()));
                    if (destination.isFile()) {
                        String updatedKey = "lib:" + TvHubLibraryController.libraryKey(destination);
                        ContentValues updated = new ContentValues();
                        updated.put("item_key", updatedKey);
                        if (key.equals(cursor.getString(1))) updated.put("canonical_key", updatedKey);
                        updated.put("source_uri", android.net.Uri.fromFile(destination).toString());
                        updated.put("title", destination.getName());
                        db.updateWithOnConflict("watch_entries", updated, "item_key=?", new String[]{key},
                                SQLiteDatabase.CONFLICT_REPLACE);
                        continue;
                    }
                }
                if (java.nio.file.Files.notExists(java.nio.file.Paths.get(path))) {
                    db.delete("watch_entries", "item_key=?", new String[]{key});
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public static Entry removeFromContinue(Context context, String itemKey) {
        if (context == null || itemKey == null || itemKey.isBlank()) return null;
        try {
            ContentValues cv = new ContentValues();
            cv.put("removed_from_continue", 1);
            cv.put("position_ms", 0L);
            cv.put("updated_at", System.currentTimeMillis());
            get(context).getWritableDatabase().update("watch_entries", cv, "item_key=?", new String[]{itemKey});
            return getEntry(context, itemKey);
        } catch (Exception ignored) {}
        return null;
    }

    public static Entry markRemoteWatched(Context context, Entry entry, boolean watched) {
        if (context == null || entry == null || entry.itemKey == null || entry.itemKey.isBlank()) {
            return null;
        }
        return markWatched(context, entry.itemKey, entry.kind, entry.title, entry.sourceUri, watched);
    }

    public static Entry getEntry(Context context, String itemKey) {
        if (context == null || itemKey == null || itemKey.isBlank()) return null;
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT item_key,canonical_key,kind,title,source_uri,torrent_id,file_index,format,media_type,imdb_id,tmdb_id,season,episode,position_ms,duration_ms,watched,removed_from_continue,pending_trakt_sync,updated_at "
                        + "FROM watch_entries WHERE item_key=? LIMIT 1", new String[]{itemKey})) {
            if (c.moveToFirst()) return read(c);
        } catch (Exception ignored) {}
        return null;
    }

    public static Entry findBestEntry(Context context, String itemKey, String mediaType,
                                      String imdbId, long tmdbId, int season, int episode, String title) {
        if (context == null) return null;
        Entry exact = getEntry(context, itemKey);
        if (exact != null) return exact;
        String canonical = canonicalKey(mediaType, imdbId, tmdbId, season, episode, itemKey, title);
        if (!canonical.isEmpty()) {
            try (Cursor c = get(context).getReadableDatabase().rawQuery(
                    "SELECT item_key,canonical_key,kind,title,source_uri,torrent_id,file_index,format,media_type,imdb_id,tmdb_id,season,episode,position_ms,duration_ms,watched,removed_from_continue,pending_trakt_sync,updated_at "
                            + "FROM watch_entries WHERE canonical_key=? ORDER BY updated_at DESC LIMIT 1", new String[]{canonical})) {
                if (c.moveToFirst()) return read(c);
            } catch (Exception ignored) {}
        }
        String norm = normalizeTitle(title);
        if (!norm.isEmpty()) {
            try (Cursor c = get(context).getReadableDatabase().rawQuery(
                    "SELECT item_key,canonical_key,kind,title,source_uri,torrent_id,file_index,format,media_type,imdb_id,tmdb_id,season,episode,position_ms,duration_ms,watched,removed_from_continue,pending_trakt_sync,updated_at "
                            + "FROM watch_entries ORDER BY updated_at DESC LIMIT 60", null)) {
                while (c.moveToNext()) {
                    Entry e = read(c);
                    if (norm.equals(normalizeTitle(e.title))) return e;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static JSONObject entryToJson(Entry e) {
        if (e == null) return null;
        JSONObject obj = new JSONObject();
        try {
            obj.put("canonical_key", e.canonicalKey == null || e.canonicalKey.isBlank()
                    ? canonicalKey(e.mediaType, e.imdbId, e.tmdbId, e.season, e.episode, e.itemKey, e.title) : e.canonicalKey);
            obj.put("stream_key", e.itemKey);
            obj.put("kind", e.kind == null ? "library" : e.kind);
            obj.put("title", e.title == null ? "" : e.title);
            obj.put("media_type", e.mediaType == null ? "movie" : e.mediaType);
            obj.put("imdb_id", e.imdbId == null ? "" : e.imdbId);
            obj.put("tmdb_id", Math.max(0L, e.tmdbId));
            obj.put("season", Math.max(0, e.season));
            obj.put("episode", Math.max(0, e.episode));
            obj.put("position_ms", Math.max(0L, e.positionMs));
            obj.put("duration_ms", Math.max(0L, e.durationMs));
            obj.put("watched", e.watched);
            obj.put("removed_from_continue", e.removedFromContinue);
            obj.put("updated_at", e.updatedAt);
        } catch (Exception ignored) {}
        return obj;
    }

    public static JSONArray exportRecentSyncRecords(Context context, int limit) {
        JSONArray arr = new JSONArray();
        if (context == null || limit <= 0) return arr;
        purgeNonLibraryEntries(context);
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT item_key,canonical_key,kind,title,source_uri,torrent_id,file_index,format,media_type,imdb_id,tmdb_id,season,episode,position_ms,duration_ms,watched,removed_from_continue,pending_trakt_sync,updated_at "
                        + "FROM watch_entries ORDER BY updated_at DESC LIMIT ?", new String[]{Integer.toString(limit)})) {
            while (c.moveToNext()) {
                Entry e = read(c);
                if (isExcludedNonLibrary(e.itemKey, e.kind, e.title, e.sourceUri)) continue;
                JSONObject obj = entryToJson(e);
                if (obj != null) arr.put(obj);
            }
        } catch (Exception ignored) {}
        return arr;
    }

    /** Apply one remote WATCH_SYNC record using Last-Write-Wins (remote.updated_at > local.updated_at). */
    public static boolean applyRemoteSyncRecord(Context context, JSONObject rec) {
        return applyRemoteSyncRecord(context, rec, true);
    }

    public static boolean applyRemoteSyncRecord(Context context, JSONObject rec, boolean markPendingTrakt) {
        if (context == null || rec == null) return false;
        String title = rec.optString("title", "");
        String mediaType = rec.optString("media_type", "movie");
        String imdbId = rec.optString("imdb_id", "");
        long tmdbId = rec.optLong("tmdb_id", 0L);
        int season = rec.optInt("season", 0);
        int episode = rec.optInt("episode", 0);
        String streamKey = rec.optString("stream_key", "");
        String kind = rec.optString("kind", "tv");
        String sourceUri = rec.optString("source_uri", "");
        if (isExcludedNonLibrary(streamKey, kind, title, sourceUri)) return false;
        String canonical = rec.optString("canonical_key", "");
        if (canonical.isBlank()) canonical = canonicalKey(mediaType, imdbId, tmdbId, season, episode, streamKey, title);
        long posMs = Math.max(0L, rec.optLong("position_ms", 0L));
        long durMs = Math.max(0L, rec.optLong("duration_ms", 0L));
        double progressPct = rec.optDouble("progress_pct", 0.0);
        if (durMs <= 0 && progressPct > 0.0) {
            durMs = 3_600_000L;
            posMs = Math.round(durMs * Math.min(99.0, Math.max(1.0, progressPct)) / 100.0);
        }
        boolean watched = rec.optBoolean("watched", false) || (durMs > 0 && posMs * 100L >= durMs * 85L);
        boolean removed = rec.optBoolean("removed_from_continue", false);
        long remoteUpdatedAt = rec.optLong("updated_at", 0L);
        if (remoteUpdatedAt <= 0) remoteUpdatedAt = System.currentTimeMillis();
        if (watched) posMs = 0L;

        Entry existing = findBestEntry(context, streamKey, mediaType, imdbId, tmdbId, season, episode, title);
        if (existing != null && remoteUpdatedAt <= existing.updatedAt) {
            return false;
        }
        String targetKey = existing != null ? existing.itemKey
                : (!streamKey.isBlank() ? streamKey : canonical);
        if (targetKey == null || targetKey.isBlank()) return false;
        try {
            ContentValues cv = new ContentValues();
            cv.put("item_key", targetKey);
            cv.put("canonical_key", canonical);
            cv.put("kind", existing != null ? existing.kind : kind);
            cv.put("title", !title.isBlank() ? title : (existing != null ? existing.title : ""));
            cv.put("source_uri", existing != null ? existing.sourceUri : "");
            cv.put("torrent_id", existing != null ? existing.torrentId : "");
            cv.put("file_index", existing != null ? existing.fileIndex : 0);
            cv.put("format", existing != null ? existing.format : "");
            cv.put("media_type", mediaType);
            cv.put("imdb_id", !imdbId.isBlank() ? imdbId : (existing != null ? existing.imdbId : ""));
            cv.put("tmdb_id", tmdbId > 0 ? tmdbId : (existing != null ? existing.tmdbId : 0L));
            cv.put("season", season > 0 ? season : (existing != null ? existing.season : 0));
            cv.put("episode", episode > 0 ? episode : (existing != null ? existing.episode : 0));
            cv.put("position_ms", posMs);
            cv.put("duration_ms", durMs > 0 ? durMs : (existing != null ? existing.durationMs : 0L));
            cv.put("watched", watched ? 1 : 0);
            cv.put("removed_from_continue", removed ? 1 : 0);
            cv.put("pending_trakt_sync", markPendingTrakt ? 1 : 0);
            cv.put("updated_at", remoteUpdatedAt);
            get(context).getWritableDatabase().insertWithOnConflict("watch_entries", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            return true;
        } catch (Exception ignored) {}
        return false;
    }

    public static List<Entry> listContinueWatching(Context context) {
        List<Entry> out = new ArrayList<>();
        if (context == null) return out;
        purgeNonLibraryEntries(context);
        var seen = new java.util.HashSet<String>();
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT item_key,canonical_key,kind,title,source_uri,torrent_id,file_index,format,media_type,imdb_id,tmdb_id,season,episode,position_ms,duration_ms,watched,removed_from_continue,pending_trakt_sync,updated_at "
                        + "FROM watch_entries WHERE watched=0 AND removed_from_continue=0 AND duration_ms>0 "
                        + "AND (position_ms*100 >= duration_ms*3) AND (position_ms*100 < duration_ms*85) "
                        + "ORDER BY updated_at DESC LIMIT 40", null)) {
            while (c.moveToNext()) {
                Entry e = read(c);
                if (isExcludedNonLibrary(e.itemKey, e.kind, e.title, e.sourceUri)) continue;
                String dedupe = (e.canonicalKey != null && !e.canonicalKey.isBlank() && !e.canonicalKey.startsWith("key:"))
                        ? e.canonicalKey : ("title:" + normalizeTitle(e.title));
                if (!dedupe.isBlank() && !seen.add(dedupe)) continue;
                out.add(e);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static List<Entry> listWatched(Context context) {
        List<Entry> out = new ArrayList<>();
        if (context == null) return out;
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT item_key,canonical_key,kind,title,source_uri,torrent_id,file_index,format,media_type,imdb_id,tmdb_id,season,episode,position_ms,duration_ms,watched,removed_from_continue,pending_trakt_sync,updated_at "
                        + "FROM watch_entries WHERE watched=1 ORDER BY updated_at DESC LIMIT 50", null)) {
            while (c.moveToNext()) out.add(read(c));
        } catch (Exception ignored) {}
        return out;
    }

    public static List<com.meedx.providers.TraktSyncClient.SyncItem> listPendingTraktItems(Context context) {
        List<com.meedx.providers.TraktSyncClient.SyncItem> out = new ArrayList<>();
        if (context == null) return out;
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT item_key,media_type,imdb_id,tmdb_id,season,episode,position_ms,duration_ms,watched,removed_from_continue,updated_at "
                        + "FROM watch_entries WHERE pending_trakt_sync=1 AND (imdb_id!='' OR tmdb_id>0) "
                        + "ORDER BY updated_at DESC LIMIT 100", null)) {
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
                    if (k != null && !k.isBlank()) {
                        db.update("watch_entries", cv, "item_key=?", new String[]{k});
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception ignored) {}
    }

    public static int applyTraktPullRecords(Context context, JSONArray records) {
        if (context == null || records == null) return 0;
        try {
            get(context).getWritableDatabase().delete("watch_entries",
                    "kind='trakt' AND pending_trakt_sync=0", null);
        } catch (Exception ignored) {}
        int applied = 0;
        for (int i = 0; i < records.length(); i++) {
            JSONObject rec = records.optJSONObject(i);
            if (rec != null && applyRemoteSyncRecord(context, rec, false)) {
                applied++;
            }
        }
        return applied;
    }

    public static final class WatchlistEntry {
        public final String itemKey;
        public final String mediaType;
        public final String title;
        public final String imdbId;
        public final long tmdbId;
        public final int year;
        public final long updatedAt;

        public WatchlistEntry(String itemKey, String mediaType, String title, String imdbId,
                              long tmdbId, int year, long updatedAt) {
            this.itemKey = itemKey;
            this.mediaType = mediaType;
            this.title = title;
            this.imdbId = imdbId;
            this.tmdbId = tmdbId;
            this.year = year;
            this.updatedAt = updatedAt;
        }
    }

    public static void saveWatchlist(Context context, JSONArray items) {
        if (context == null || items == null) return;
        try {
            SQLiteDatabase db = get(context).getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete("trakt_watchlist", null, null);
                for (int i = 0; i < items.length(); i++) {
                    JSONObject obj = items.optJSONObject(i);
                    if (obj == null) continue;
                    String mt = obj.optString("media_type", "movie");
                    String imdb = obj.optString("imdb_id", "");
                    long tmdb = obj.optLong("tmdb_id", 0L);
                    String title = obj.optString("title", "");
                    int year = obj.optInt("year", 0);
                    long ts = obj.optLong("updated_at", System.currentTimeMillis());
                    String key = !imdb.isEmpty() ? mt + ":" + imdb
                            : (tmdb > 0 ? mt + ":tmdb:" + tmdb : mt + ":" + normalizeTitle(title));
                    ContentValues cv = new ContentValues();
                    cv.put("item_key", key);
                    cv.put("media_type", mt);
                    cv.put("title", title);
                    cv.put("imdb_id", imdb);
                    cv.put("tmdb_id", tmdb);
                    cv.put("year", year);
                    cv.put("updated_at", ts);
                    db.insertWithOnConflict("trakt_watchlist", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception ignored) {}
    }

    public static List<WatchlistEntry> listWatchlist(Context context) {
        List<WatchlistEntry> out = new ArrayList<>();
        if (context == null) return out;
        try (Cursor c = get(context).getReadableDatabase().rawQuery(
                "SELECT item_key,media_type,title,imdb_id,tmdb_id,year,updated_at "
                        + "FROM trakt_watchlist ORDER BY updated_at DESC LIMIT 80", null)) {
            while (c.moveToNext()) {
                out.add(new WatchlistEntry(c.getString(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getLong(4), c.getInt(5), c.getLong(6)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static Entry read(Cursor c) {
        return new Entry(
                c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                c.getString(5), c.getInt(6), c.getString(7), c.getString(8),
                c.getString(9), c.getLong(10), c.getInt(11), c.getInt(12),
                c.getLong(13), c.getLong(14), c.getInt(15) == 1, c.getInt(16) == 1,
                c.getInt(17) == 1, c.getLong(18));
    }
}

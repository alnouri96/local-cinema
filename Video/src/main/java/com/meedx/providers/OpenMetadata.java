package com.meedx.providers;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.Html;

import com.archos.mediacenter.video.leanback.cinema.MetadataLanguage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Keyless matches are accepted by IMDb ID, or a unique exact title and matching year. */
public final class OpenMetadata {
    public static final String PRIVATE_TMDB = "cinema_private_tmdb";
    private static final long DISK_TTL_MS = 14L * 24 * 60 * 60 * 1000;
    public record Query(String title, int year, boolean show) {}
    public record CastMember(String name, String role, String image, String personId) {}
    public record EpisodeMeta(int season, int episode, String title, String overview, String thumbnail,
                              Double rating, Integer runtimeMinutes, String ratingSource, int year) {
        public EpisodeMeta(int season, int episode, String title, String overview, String thumbnail) {
            this(season, episode, title, overview, thumbnail, null, null, null, 0);
        }
        public EpisodeMeta(int season, int episode, String title, String overview, String thumbnail,
                           Double rating, Integer runtimeMinutes, String ratingSource) {
            this(season, episode, title, overview, thumbnail, rating, runtimeMinutes, ratingSource, 0);
        }
    }
    public record Item(String title, String year, String poster, String backdrop, String plot,
                String imdb, Double score, String ratingSource, String attribution, List<CastMember> cast,
                String logo, String genres) {
        public Item(String title, String year, String poster, String backdrop, String plot,
                    String imdb, Double score, String ratingSource, String attribution, List<CastMember> cast) {
            this(title, year, poster, backdrop, plot, imdb, score, ratingSource, attribution, cast, null, "");
        }
        public Item(String title, String year, String poster, String backdrop, String plot,
                    String imdb, Double score, String ratingSource, String attribution) {
            this(title, year, poster, backdrop, plot, imdb, score, ratingSource, attribution, List.of(), null, "");
        }
    }
    private static final Map<String, Item> cache = new LinkedHashMap<>();
    private static final Map<String, String> personPhotoCache = new LinkedHashMap<>();
    private static boolean personPhotosLoaded = false;
    private static final ExecutorService PHOTO_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "OpenPersonPhoto");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });
    private OpenMetadata() {}

    public static boolean enabled(Context context) {
        return !PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PRIVATE_TMDB, false);
    }

    public static String resolveLang(Context context) {
        if (context == null) return "en";
        return MetadataLanguage.resolve(context).startsWith("ar") ? "ar" : "en";
    }

    public static boolean containsArabic(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch >= 0x0600 && ch <= 0x06FF) || (ch >= 0x0750 && ch <= 0x077F)
                    || (ch >= 0x08A0 && ch <= 0x08FF) || (ch >= 0xFB50 && ch <= 0xFDFF)
                    || (ch >= 0xFE70 && ch <= 0xFEFF)) {
                return true;
            }
        }
        return false;
    }

    /** Cleans display decorations like "(Character)" or " · Director" from an actor name. */
    public static String cleanPersonName(String raw) {
        if (raw == null) return "";
        String cleaned = raw
                .replaceAll("\\s*\\([^)]*\\)", " ")
                .replaceAll("\\s*（[^）]*）", " ")
                .replaceAll("\\s*\\[[^]]*\\]", " ")
                .replaceAll("\\s*[·•—–|]\\s*.*$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned;
    }

    /** Loads all episode thumbnails, titles, synopses, ratings, and runtimes via Cinemeta + TVmaze. */
    public static List<EpisodeMeta> fetchCinemetaEpisodes(Context context, String imdbId) {
        List<EpisodeMeta> out = new ArrayList<>();
        if (context == null || imdbId == null || !imdbId.matches("tt[0-9]+")) return out;
        Map<String, EpisodeMeta> merged = new LinkedHashMap<>();
        try {
            File cacheDir = new File(context.getCacheDir(), "open_cinemeta_ep");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File cacheFile = new File(cacheDir, imdbId + ".json");
            String body = null;
            if (cacheFile.isFile() && (System.currentTimeMillis() - cacheFile.lastModified()) < DISK_TTL_MS) {
                body = new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8);
            }
            if (body == null || body.isBlank()) {
                HttpURLConnection conn = (HttpURLConnection) new URL(
                        "https://v3-cinemeta.strem.io/meta/series/" + imdbId + ".json").openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "LocalCinema/1.0");
                try (var in = conn.getInputStream(); var baos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1 && baos.size() < 2 * 1024 * 1024) {
                        baos.write(buf, 0, n);
                    }
                    body = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                    if (body.contains("\"videos\"")) {
                        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                            fos.write(baos.toByteArray());
                        } catch (Exception ignored) {}
                    }
                } finally {
                    conn.disconnect();
                }
            }
            if (body != null && !body.isBlank()) {
                JSONObject root = new JSONObject(body);
                JSONObject meta = root.optJSONObject("meta");
                JSONArray videos = meta != null ? meta.optJSONArray("videos") : null;
                if (videos != null) {
                    for (int i = 0; i < videos.length(); i++) {
                        JSONObject v = videos.optJSONObject(i);
                        if (v == null) continue;
                        int s = v.optInt("season", -1);
                        int e = v.has("episode") ? v.optInt("episode", -1) : v.optInt("number", -1);
                        if (s < 0 || e <= 0) continue;
                        String title = v.optString("name", v.optString("title", "")).trim();
                        String overview = v.optString("overview", v.optString("description", "")).trim();
                        String thumb = v.optString("thumbnail", "").trim();
                        if (thumb.isEmpty()) {
                            thumb = "https://episodes.metahub.space/" + imdbId + "/" + s + "/" + e + "/w780.jpg";
                        }
                        Double rating = parseEpisodeScore(v.opt("rating"));
                        if (rating == null) rating = parseEpisodeScore(v.opt("imdbRating"));
                        Integer rt = parsePositiveInt(v.opt("runtime"));
                        int epYear = parseAirYear(v.optString("released", v.optString("firstAired", "")));
                        String key = s + ":" + e;
                        merged.put(key, new EpisodeMeta(s, e, title, overview, thumb,
                                rating, rt, rating != null ? "IMDb" : null, epYear));
                    }
                }
            }
        } catch (Exception ignored) {}
        try {
            File cacheDir = new File(context.getCacheDir(), "open_cinemeta_ep");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File tvFile = new File(cacheDir, imdbId + "_tvmaze.json");
            String tvBody = null;
            if (tvFile.isFile() && (System.currentTimeMillis() - tvFile.lastModified()) < DISK_TTL_MS) {
                tvBody = new String(Files.readAllBytes(tvFile.toPath()), StandardCharsets.UTF_8);
            }
            if (tvBody == null || tvBody.isBlank()) {
                JSONObject showObj = new JSONObject(fetchTvMaze("https://api.tvmaze.com/lookup/shows?imdb=" + Uri.encode(imdbId)));
                long tvId = showObj.optLong("id", 0L);
                if (tvId > 0) {
                    tvBody = fetchTvMaze("https://api.tvmaze.com/shows/" + tvId + "/episodes");
                    if (tvBody != null && tvBody.startsWith("[")) {
                        try (FileOutputStream fos = new FileOutputStream(tvFile)) {
                            fos.write(tvBody.getBytes(StandardCharsets.UTF_8));
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (tvBody != null && tvBody.startsWith("[")) {
                JSONArray eps = new JSONArray(tvBody);
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject ep = eps.optJSONObject(i);
                    if (ep == null) continue;
                    int s = ep.optInt("season", -1);
                    int e = ep.optInt("number", -1);
                    if (s < 0 || e <= 0) continue;
                    String tvTitle = ep.optString("name", "").trim();
                    String rawSummary = ep.optString("summary", "");
                    String tvOverview = rawSummary.isEmpty() ? ""
                            : Html.fromHtml(rawSummary, Html.FROM_HTML_MODE_LEGACY).toString().strip();
                    JSONObject imgObj = ep.optJSONObject("image");
                    String tvThumb = imgObj != null ? castImage(imgObj.optString("original", imgObj.optString("medium", ""))) : null;
                    JSONObject ratingObj = ep.optJSONObject("rating");
                    Double tvRating = ratingObj != null ? score(ratingObj.optDouble("average", 0.0)) : null;
                    Integer tvRt = parsePositiveInt(ep.opt("runtime"));
                    int tvYear = parseAirYear(ep.optString("airdate", ""));
                    String key = s + ":" + e;
                    EpisodeMeta prev = merged.get(key);
                    if (prev == null) {
                        merged.put(key, new EpisodeMeta(s, e, tvTitle, tvOverview,
                                tvThumb != null ? tvThumb : "https://episodes.metahub.space/" + imdbId + "/" + s + "/" + e + "/w780.jpg",
                                tvRating, tvRt, tvRating != null ? "TVmaze" : null, tvYear));
                    } else {
                        String mergedTitle = !prev.title().isEmpty() ? prev.title() : tvTitle;
                        String mergedOverview = !prev.overview().isEmpty() ? prev.overview() : tvOverview;
                        String mergedThumb = !prev.thumbnail().isEmpty() ? prev.thumbnail() : (tvThumb != null ? tvThumb : "");
                        Double mergedRating = prev.rating() != null ? prev.rating() : tvRating;
                        String mergedSource = prev.rating() != null ? prev.ratingSource() : (tvRating != null ? "TVmaze" : null);
                        Integer mergedRt = prev.runtimeMinutes() != null ? prev.runtimeMinutes() : tvRt;
                        int mergedYear = tvYear > 0 ? tvYear : prev.year();
                        merged.put(key, new EpisodeMeta(s, e, mergedTitle, mergedOverview, mergedThumb,
                                mergedRating, mergedRt, mergedSource, mergedYear));
                    }
                }
            }
        } catch (Exception ignored) {}
        out.addAll(merged.values());
        return out;
    }

    /** Loads per-season poster URLs via TVmaze (/shows/{id}/seasons) using the IMDb ID, cached on disk. */
    public static Map<Integer, String> fetchSeasonPosters(Context context, String imdbId) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (context == null || imdbId == null || !imdbId.matches("tt[0-9]+")) return out;
        try {
            File cacheDir = new File(context.getCacheDir(), "open_cinemeta_ep");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File seasonsFile = new File(cacheDir, imdbId + "_tvmaze_seasons.json");
            String body = null;
            if (seasonsFile.isFile() && (System.currentTimeMillis() - seasonsFile.lastModified()) < DISK_TTL_MS) {
                body = new String(Files.readAllBytes(seasonsFile.toPath()), StandardCharsets.UTF_8);
            }
            if (body == null || body.isBlank()) {
                JSONObject showObj = new JSONObject(fetchTvMaze("https://api.tvmaze.com/lookup/shows?imdb=" + Uri.encode(imdbId)));
                long tvId = showObj.optLong("id", 0L);
                if (tvId > 0) {
                    body = fetchTvMaze("https://api.tvmaze.com/shows/" + tvId + "/seasons");
                    if (body != null && body.startsWith("[")) {
                        try (FileOutputStream fos = new FileOutputStream(seasonsFile)) {
                            fos.write(body.getBytes(StandardCharsets.UTF_8));
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (body != null && body.startsWith("[")) {
                JSONArray arr = new JSONArray(body);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj == null) continue;
                    int number = obj.optInt("number", -1);
                    if (number < 0) continue;
                    JSONObject imgObj = obj.optJSONObject("image");
                    String url = imgObj != null ? castImage(imgObj.optString("original", imgObj.optString("medium", ""))) : null;
                    if (url != null && !url.isBlank()) out.put(number, url);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static int parseAirYear(String rawDate) {
        if (rawDate == null || rawDate.length() < 4) return 0;
        String sub = rawDate.trim().substring(0, 4);
        if (sub.matches("(?:19|20)[0-9]{2}")) {
            try { return Integer.parseInt(sub); } catch (Exception ignored) {}
        }
        return 0;
    }

    private static Double parseEpisodeScore(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return null;
        try {
            double v = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(raw.toString().trim());
            return score(v);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer parsePositiveInt(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return null;
        try {
            int v = raw instanceof Number n ? n.intValue() : Integer.parseInt(raw.toString().trim());
            return v > 0 && v < 600 ? v : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String fetchTvMaze(String url) throws Exception {
        String current = url;
        for (int hop = 0; hop < 3; hop++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setConnectTimeout(4500);
            connection.setReadTimeout(6000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "LocalCinema/1.0 (https://github.com/alnouri96/local-cinema)");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String next = connection.getHeaderField("Location");
                    if (next == null || next.isBlank()) throw new IOException("Missing redirect location");
                    Uri parsed = Uri.parse(next);
                    String host = parsed.getHost();
                    if (!"https".equals(parsed.getScheme()) || !"api.tvmaze.com".equals(host)) {
                        throw new IOException("Unexpected redirect host");
                    }
                    current = next;
                    continue;
                }
                try (var input = connection.getInputStream(); var data = new ByteArrayOutputStream()) {
                    byte[] block = new byte[8192];
                    int read;
                    while ((read = input.read(block)) != -1) {
                        if (data.size() + read > 2 * 1024 * 1024) throw new IOException("Metadata exceeds limit");
                        data.write(block, 0, read);
                    }
                    return new String(data.toByteArray(), StandardCharsets.UTF_8);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    public static void clearCache(Context context, boolean clearPersonPhotos) {
        synchronized (cache) {
            cache.clear();
        }
        if (context != null) {
            deleteDirContents(new File(context.getCacheDir(), "open_metadata_v2"));
            if (clearPersonPhotos) {
                synchronized (personPhotoCache) {
                    personPhotoCache.clear();
                    personPhotosLoaded = false;
                }
                deleteDirContents(new File(context.getCacheDir(), "open_person_photos_v1"));
            }
        }
    }

    private static void deleteDirContents(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) deleteDirContents(f);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** Instant 0ms memory + disk cache lookup (never touches the network). */
    public static Item getCachedOnly(Context context, Query query, String imdb) {
        return getCachedOnly(context, query, imdb, resolveLang(context));
    }

    public static Item getCachedOnly(Context context, Query query, String imdb, String langCode) {
        if (query == null || query.title() == null || query.title().isBlank()) return null;
        String lang = "ar".equalsIgnoreCase(langCode) ? "ar" : "en";
        String key = buildCacheKey(query, imdb, lang);
        synchronized (cache) {
            if (cache.containsKey(key)) {
                Item mem = cache.get(key);
                if ("en".equals(lang) && mem != null && (containsArabic(mem.title()) || containsArabic(mem.plot()))) {
                    cache.remove(key);
                } else {
                    return mem;
                }
            }
        }
        Item disk = readDiskItem(context, key);
        if (disk != null) {
            if ("en".equals(lang) && (containsArabic(disk.title()) || containsArabic(disk.plot()))) {
                try { itemCacheFile(context, key).delete(); } catch (Exception ignored) {}
                return null;
            }
            putMemItem(key, disk);
        }
        return disk;
    }

    public static Item lookup(Context context, Query query, String imdb, String fileUri, int season, int episode) {
        return lookup(context, query, imdb, fileUri, season, episode, resolveLang(context));
    }

    public static Item lookup(Context context, Query query, String imdb, String fileUri, int season, int episode, String langCode) {
        Item item = lookupInternal(context, query, imdb, langCode, false);
        if (context != null && item != null) {
            int parsedYear = 0;
            try { if (item.year() != null && !item.year().isBlank()) parsedYear = Integer.parseInt(item.year()); }
            catch (NumberFormatException ignored) {}
            String type = query.show() ? "episode" : "movie";
            String key = (fileUri != null && !fileUri.isBlank()) ? "uri:" + fileUri
                    : type + ":" + (item.imdb() != null && !item.imdb().isBlank() ? item.imdb() : query.title() + ":" + parsedYear);
            com.archos.mediacenter.video.leanback.cinema.CinemaWatchStore.saveIdentity(
                    context, key, fileUri, type, item.imdb(), 0L, season, episode,
                    item.title(), parsedYear, item.poster(), item.backdrop(), item.attribution());
        }
        return item;
    }

    public static Item lookup(Query query, String imdb) {
        return lookupInternal(null, query, imdb, "en", false);
    }

    public static Item refresh(Context context, Query query, String imdb, String fileUri, int season, int episode) {
        Item item = lookupInternal(context, query, imdb, resolveLang(context), true);
        if (context != null && item != null) {
            int parsedYear = 0;
            try { if (item.year() != null && !item.year().isBlank()) parsedYear = Integer.parseInt(item.year()); }
            catch (NumberFormatException ignored) {}
            String type = query.show() ? "episode" : "movie";
            String key = (fileUri != null && !fileUri.isBlank()) ? "uri:" + fileUri
                    : type + ":" + (item.imdb() != null && !item.imdb().isBlank() ? item.imdb() : query.title() + ":" + parsedYear);
            com.archos.mediacenter.video.leanback.cinema.CinemaWatchStore.saveIdentity(
                    context, key, fileUri, type, item.imdb(), 0L, season, episode,
                    item.title(), parsedYear, item.poster(), item.backdrop(), item.attribution());
        }
        return item;
    }

    private static String buildCacheKey(Query query, String imdb, String lang) {
        String type = query.show() ? "series" : "movie";
        boolean known = imdb != null && imdb.matches("tt[0-9]+");
        return type + ":" + (known ? imdb : normalized(query.title()) + ":" + query.year()) + ":" + lang;
    }

    private static void putMemItem(String key, Item item) {
        synchronized (cache) {
            if (cache.size() >= 160) cache.remove(cache.keySet().iterator().next());
            cache.put(key, item);
        }
    }

    private static Item lookupInternal(Context context, Query query, String imdb, String langCode, boolean forceRefresh) {
        if (query == null || query.title() == null || query.title().isBlank()) return null;
        String lang = "ar".equalsIgnoreCase(langCode) ? "ar" : "en";
        if (containsArabic(query.title())) {
            String resolvedEn = resolveEnglishTitleFromArabic(query.title());
            if (resolvedEn != null && !resolvedEn.isBlank()) {
                query = new Query(resolvedEn, query.year(), query.show());
            }
        }
        String type = query.show() ? "series" : "movie";
        boolean known = imdb != null && imdb.matches("tt[0-9]+");
        String key = buildCacheKey(query, imdb, lang);
        if (!forceRefresh) {
            synchronized (cache) {
                if (cache.containsKey(key)) {
                    Item mem = cache.get(key);
                    if ("en".equals(lang) && mem != null && (containsArabic(mem.title()) || containsArabic(mem.plot()))) {
                        cache.remove(key);
                    } else {
                        return mem;
                    }
                }
            }
            Item disk = readDiskItem(context, key);
            if (disk != null) {
                if ("en".equals(lang) && (containsArabic(disk.title()) || containsArabic(disk.plot()))) {
                    try { itemCacheFile(context, key).delete(); } catch (Exception ignored) {}
                } else {
                    putMemItem(key, disk);
                    return disk;
                }
            }
        }
        String endpoint = "https://v3-cinemeta.strem.io/";
        Item item = null;
        try {
            if (known) {
                JSONObject meta = json(endpoint + "meta/" + type + "/" + imdb + ".json").optJSONObject("meta");
                if (meta != null && imdb.equals(id(meta))) item = fromCinemeta(context, meta, query.show());
            } else {
                JSONArray matches = json(endpoint + "catalog/" + type + "/top/search="
                        + Uri.encode(query.title()) + ".json").optJSONArray("metas");
                JSONObject match = unique(matches, query, "name", "releaseInfo", null);
                if (match == null && query.year() > 0) {
                    match = unique(matches, new Query(query.title(), 0, query.show()), "name", "releaseInfo", null);
                }
                if (match != null && id(match).matches("tt[0-9]+")) {
                    String matchedId = id(match);
                    try {
                        JSONObject meta = json(endpoint + "meta/" + type + "/" + matchedId + ".json").optJSONObject("meta");
                        if (meta != null && matchedId.equals(id(meta))) match = meta;
                    } catch (Exception ignored) {}
                    item = fromCinemeta(context, match, query.show());
                }
            }
        } catch (Exception ignored) {}
        if (item == null && query.show() && !known) item = tvMaze(context, query);
        if (item == null && !known) item = jikan(query);
        if (item == null && context != null) item = tmdbLookupFallback(context, query, imdb, lang);
        if (item != null && "ar".equals(lang)) {
            item = arabic(item, query.show());
        }
        if (item != null) {
            putMemItem(key, item);
            writeDiskItem(context, key, item);
        }
        return item;
    }

    private static Item tmdbLookupFallback(Context context, Query query, String imdb, String lang) {
        try {
            String apiKey = ProviderKeys.get(context, ProviderKeys.TMDB);
            if (apiKey == null || apiKey.isBlank()) return null;
            String tmdbType = query.show() ? "tv" : "movie";
            String[] langs = "ar".equals(lang) ? new String[]{"ar-SA", "en-US"} : new String[]{"en-US"};
            for (String l : langs) {
                long tmdbId = 0L;
                if (imdb != null && imdb.matches("tt[0-9]+")) {
                    JSONObject found = json("https://api.themoviedb.org/3/find/" + Uri.encode(imdb)
                            + "?api_key=" + Uri.encode(apiKey) + "&external_source=imdb_id&language=" + l);
                    JSONArray res = found != null ? found.optJSONArray(query.show() ? "tv_results" : "movie_results") : null;
                    if (res != null && res.length() > 0 && res.optJSONObject(0) != null) {
                        tmdbId = res.optJSONObject(0).optLong("id", 0L);
                    }
                }
                if (tmdbId <= 0) {
                    String yearParam = query.year() > 0 ? ("&" + (query.show() ? "first_air_date_year=" : "year=") + query.year()) : "";
                    JSONObject sr = json("https://api.themoviedb.org/3/search/" + tmdbType
                            + "?api_key=" + Uri.encode(apiKey) + "&language=" + l
                            + "&query=" + Uri.encode(query.title()) + yearParam);
                    JSONArray results = sr != null ? sr.optJSONArray("results") : null;
                    JSONObject pick = (results != null && results.length() > 0)
                            ? unique(results, query, query.show() ? "name" : "title",
                                    query.show() ? "first_air_date" : "release_date", null)
                            : null;
                    if (pick == null && results != null && results.length() > 0) pick = results.optJSONObject(0);
                    if (pick != null) tmdbId = pick.optLong("id", 0L);
                }
                if (tmdbId > 0) {
                    com.archos.mediacenter.video.leanback.cinema.CinemaArtwork.Art art =
                            com.archos.mediacenter.video.leanback.cinema.CinemaArtwork.load(context, tmdbId, query.show(), l);
                    if (art != null && (art.poster() != null || (art.overview() != null && !art.overview().isBlank()))) {
                        List<CastMember> cast = new ArrayList<>();
                        if (art.cast() != null) {
                            for (var p : art.cast()) cast.add(new CastMember(p.name(), p.role(), p.image(), ""));
                        }
                        return new Item(art.title() != null ? art.title() : query.title(),
                                art.year() > 0 ? Integer.toString(art.year()) : (query.year() > 0 ? Integer.toString(query.year()) : ""),
                                art.poster(), art.backdrop(), art.overview(), art.imdbId() == null ? "" : art.imdbId(),
                                art.rating() > 0 ? art.rating() : null, "TMDb", "TMDb", cast, art.logo(), art.genres());
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String resolveEnglishTitleFromArabic(String arabicTitle) {
        if (arabicTitle == null || arabicTitle.isBlank()) return null;
        try {
            JSONObject resp = json("https://ar.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&redirects=1"
                    + "&prop=langlinks&lllang=en&titles=" + Uri.encode(arabicTitle.trim()));
            JSONArray links = page(resp).optJSONArray("langlinks");
            if (links != null && links.length() > 0) {
                String en = links.getJSONObject(0).optString("title", "").replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
                if (!en.isEmpty()) return en;
            }
            JSONObject searchResp = json("https://ar.wikipedia.org/w/api.php?action=query&format=json&formatversion=2"
                    + "&list=search&srlimit=1&srsearch=" + Uri.encode(arabicTitle.trim()));
            JSONArray hits = searchResp.getJSONObject("query").optJSONArray("search");
            if (hits != null && hits.length() > 0) {
                String pageTitle = hits.getJSONObject(0).optString("title", "");
                if (!pageTitle.isEmpty()) {
                    JSONObject resp2 = json("https://ar.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&redirects=1"
                            + "&prop=langlinks&lllang=en&titles=" + Uri.encode(pageTitle));
                    JSONArray links2 = page(resp2).optJSONArray("langlinks");
                    if (links2 != null && links2.length() > 0) {
                        String en = links2.getJSONObject(0).optString("title", "").replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
                        if (!en.isEmpty()) return en;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String id(JSONObject meta) {
        return meta.optString("imdb_id", meta.optString("id"));
    }

    private static Item fromCinemeta(Context context, JSONObject meta, boolean isShow) {
        String imdbId = id(meta);
        List<CastMember> cast = parseCinemetaCast(meta);
        if (isShow && !imdbId.isBlank() && (cast.isEmpty() || missingAllPhotos(cast))) {
            List<CastMember> tvCast = fetchTvMazeCastByImdb(imdbId);
            if (!tvCast.isEmpty()) cast = tvCast;
        }
        if (!cast.isEmpty() && missingSomePhotos(cast)) {
            cast = enrichMissingCastPhotos(context, cast);
        }
        StringBuilder genres = new StringBuilder();
        JSONArray gArr = meta.optJSONArray("genres");
        if (gArr == null) gArr = meta.optJSONArray("genre");
        if (gArr != null) {
            for (int i = 0; i < Math.min(3, gArr.length()); i++) {
                String g = gArr.optString(i, "").trim();
                if (!g.isEmpty()) {
                    if (genres.length() > 0) genres.append(", ");
                    genres.append(g);
                }
            }
        }
        return new Item(meta.optString("name"), year(meta.optString("releaseInfo", meta.optString("year"))),
                image(meta, "poster"), image(meta, "background"), meta.optString("description"),
                imdbId, score(meta.optDouble("imdbRating")), "IMDb", "Cinemeta", cast,
                image(meta, "logo"), genres.toString());
    }

    private static List<CastMember> parseCinemetaCast(JSONObject meta) {
        List<CastMember> out = new ArrayList<>();
        if (meta == null) return out;
        JSONObject extras = meta.optJSONObject("app_extras");
        JSONArray richCast = extras != null ? extras.optJSONArray("cast") : null;
        if (richCast == null) richCast = meta.optJSONArray("credits_cast");
        if (richCast != null) {
            for (int i = 0; i < Math.min(14, richCast.length()); i++) {
                JSONObject obj = richCast.optJSONObject(i);
                if (obj == null) continue;
                String name = obj.optString("name", "").strip();
                if (name.isEmpty()) continue;
                String role = obj.optString("character", obj.optString("role", "")).strip();
                String photo = castImage(obj.optString("photo", obj.optString("image", "")));
                String pid = obj.optString("id", obj.optString("imdb_id", ""));
                out.add(new CastMember(name, role, photo, pid));
            }
        }
        if (out.isEmpty()) {
            JSONArray plain = meta.optJSONArray("cast");
            if (plain != null) {
                for (int i = 0; i < Math.min(12, plain.length()); i++) {
                    Object raw = plain.opt(i);
                    if (raw instanceof JSONObject obj) {
                        String name = obj.optString("name", "").strip();
                        if (!name.isEmpty()) {
                            out.add(new CastMember(name, obj.optString("character", ""),
                                    castImage(obj.optString("photo", "")), obj.optString("id", "")));
                        }
                    } else if (raw != null) {
                        String name = raw.toString().strip();
                        if (!name.isEmpty()) out.add(new CastMember(name, "", null, ""));
                    }
                }
            }
        }
        return out;
    }

    private static boolean missingAllPhotos(List<CastMember> list) {
        for (CastMember c : list) if (c.image() != null && !c.image().isBlank()) return false;
        return true;
    }

    private static boolean missingSomePhotos(List<CastMember> list) {
        int checked = Math.min(8, list.size());
        for (int i = 0; i < checked; i++) if (list.get(i).image() == null) return true;
        return false;
    }

    private static List<CastMember> fetchTvMazeCastByImdb(String imdbId) {
        try {
            JSONObject show = new JSONObject(fetchTvMaze("https://api.tvmaze.com/lookup/shows?imdb=" + Uri.encode(imdbId)));
            long showId = show.optLong("id", 0L);
            if (showId <= 0) return List.of();
            return fetchTvMazeShowCast(showId);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static List<CastMember> fetchTvMazeShowCast(long showId) {
        List<CastMember> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(fetch("https://api.tvmaze.com/shows/" + showId + "/cast"));
            for (int i = 0; i < Math.min(14, arr.length()); i++) {
                JSONObject row = arr.optJSONObject(i);
                if (row == null) continue;
                JSONObject p = row.optJSONObject("person");
                JSONObject ch = row.optJSONObject("character");
                if (p == null) continue;
                String name = p.optString("name", "").strip();
                if (name.isEmpty()) continue;
                String role = ch != null ? ch.optString("name", "").strip() : "";
                JSONObject pImg = p.optJSONObject("image");
                String photo = pImg != null ? castImage(pImg.optString("medium", pImg.optString("original", ""))) : null;
                if (photo == null && ch != null && ch.optJSONObject("image") != null) {
                    photo = castImage(ch.optJSONObject("image").optString("medium", ""));
                }
                long pid = p.optLong("id", 0L);
                out.add(new CastMember(name, role, photo, pid > 0 ? Long.toString(pid) : ""));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static List<CastMember> enrichMissingCastPhotos(Context context, List<CastMember> list) {
        List<CastMember> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CastMember c = list.get(i);
            String cleanName = cleanPersonName(c.name());
            if (i < 10 && (c.image() == null || c.image().isBlank()) && !cleanName.isEmpty()) {
                String resolved = lookupPersonPhoto(context, cleanName);
                if (resolved != null && !resolved.isEmpty()) {
                    out.add(new CastMember(c.name(), c.role(), resolved, c.personId()));
                    continue;
                }
            }
            out.add(c);
        }
        return out;
    }

    public static void lookupPersonPhotoAsync(Context context, String rawName, Consumer<String> onPhoto) {
        if (onPhoto == null) return;
        String clean = cleanPersonName(rawName);
        if (clean.isEmpty()) return;
        Context app = context != null ? context.getApplicationContext() : null;
        String key = normalized(clean);
        ensurePersonPhotosLoaded(app);
        synchronized (personPhotoCache) {
            String cached = personPhotoCache.get(key);
            if (cached != null && !cached.isEmpty()) {
                onPhoto.accept(cached);
                return;
            }
        }
        Handler main = new Handler(Looper.getMainLooper());
        PHOTO_POOL.execute(() -> {
            String resolved = lookupPersonPhoto(app, clean);
            if (resolved != null && !resolved.isEmpty()) {
                main.post(() -> onPhoto.accept(resolved));
            }
        });
    }

    public static String lookupPersonPhoto(Context context, String rawName) {
        String clean = cleanPersonName(rawName);
        if (clean.isEmpty()) return null;
        String key = normalized(clean);
        ensurePersonPhotosLoaded(context);
        synchronized (personPhotoCache) {
            if (personPhotoCache.containsKey(key)) {
                String val = personPhotoCache.get(key);
                if (val != null && !val.isEmpty()) return val;
                if ("".equals(val)) return null;
            }
        }
        String resolved = lookupTvMazePersonPhoto(clean);
        if (resolved == null) {
            resolved = lookupWikipediaPersonPhoto(clean);
        }
        if (resolved == null && context != null) {
            resolved = lookupTmdbPersonPhoto(context, clean);
        }
        synchronized (personPhotoCache) {
            if (personPhotoCache.size() >= 600) personPhotoCache.remove(personPhotoCache.keySet().iterator().next());
            personPhotoCache.put(key, resolved == null ? "" : resolved);
        }
        if (resolved != null && !resolved.isEmpty()) {
            savePersonPhotos(context);
        }
        return resolved;
    }

    private static String lookupTvMazePersonPhoto(String name) {
        try {
            JSONArray arr = new JSONArray(fetch("https://api.tvmaze.com/search/people?q=" + Uri.encode(name)));
            String target = normalized(name);
            String fallback = null;
            for (int i = 0; i < Math.min(4, arr.length()); i++) {
                JSONObject row = arr.optJSONObject(i);
                JSONObject p = row == null ? null : row.optJSONObject("person");
                if (p == null) continue;
                JSONObject img = p.optJSONObject("image");
                if (img == null) continue;
                String u = castImage(img.optString("medium", img.optString("original", "")));
                if (u == null) continue;
                if (target.equals(normalized(p.optString("name", "")))) return u;
                if (fallback == null) fallback = u;
            }
            return fallback;
        } catch (Exception ignored) {}
        return null;
    }

    private static String lookupWikipediaPersonPhoto(String name) {
        try {
            String slug = name.trim().replace(' ', '_');
            JSONObject obj = json("https://en.wikipedia.org/api/rest_v1/page/summary/" + Uri.encode(slug));
            String type = obj.optString("type", "");
            if ("disambiguation".equalsIgnoreCase(type)) return null;
            JSONObject thumb = obj.optJSONObject("thumbnail");
            if (thumb != null) {
                String src = castImage(thumb.optString("source", ""));
                if (src != null) return src;
            }
            JSONObject orig = obj.optJSONObject("originalimage");
            if (orig != null) {
                String src = castImage(orig.optString("source", ""));
                if (src != null) return src;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String lookupTmdbPersonPhoto(Context context, String name) {
        try {
            String key = ProviderKeys.get(context, ProviderKeys.TMDB);
            if (key == null || key.isBlank()) return null;
            JSONArray results = json("https://api.themoviedb.org/3/search/person?api_key="
                    + Uri.encode(key) + "&query=" + Uri.encode(name)).optJSONArray("results");
            if (results != null && results.length() > 0) {
                JSONObject first = results.optJSONObject(0);
                if (first != null) {
                    String path = first.optString("profile_path", "");
                    if (!path.isBlank() && !"null".equals(path)) {
                        return "https://image.tmdb.org/t/p/w342" + path;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String castImage(String url) {
        if (url == null || url.isBlank() || "null".equals(url)) return null;
        Uri uri = Uri.parse(url.trim());
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null) return null;
        return url.trim();
    }

    private static Item tvMaze(Context context, Query query) {
        try {
            JSONArray matches = new JSONArray(fetch("https://api.tvmaze.com/search/shows?q=" + Uri.encode(query.title())));
            JSONObject show = unique(matches, query, "name", "premiered", "show");
            if (show == null) return null;
            JSONObject artwork = show.optJSONObject("image");
            JSONObject external = show.optJSONObject("externals");
            String plot = Html.fromHtml(show.optString("summary"), Html.FROM_HTML_MODE_LEGACY).toString().strip();
            long showId = show.optLong("id", 0L);
            List<CastMember> cast = showId > 0 ? fetchTvMazeShowCast(showId) : List.of();
            if (!cast.isEmpty() && missingSomePhotos(cast)) {
                cast = enrichMissingCastPhotos(context, cast);
            }
            return new Item(show.optString("name"), year(show.optString("premiered")),
                    image(artwork, "original"), null, plot, external == null ? "" : external.optString("imdb"),
                    null, "", "TVmaze", cast);
        } catch (Exception ignored) { return null; }
    }

    private static Item jikan(Query query) {
        try {
            JSONArray matches = json("https://api.jikan.moe/v4/anime?limit=12&q=" + Uri.encode(query.title())).optJSONArray("data");
            JSONObject anime = unique(matches, query, "title", "year", null);
            if (anime == null) anime = unique(matches, query, "title_english", "year", null);
            if (anime == null || (!query.show() && !"Movie".equals(anime.optString("type")))) return null;
            JSONObject artwork = anime.optJSONObject("images");
            return new Item(anime.optString("title"), year(anime.optString("year")),
                    image(artwork == null ? null : artwork.optJSONObject("jpg"), "large_image_url"),
                    null, anime.optString("synopsis"), "", score(anime.optDouble("score")), "MAL", "Jikan / MyAnimeList");
        } catch (Exception ignored) { return null; }
    }

    private static JSONObject unique(JSONArray input, Query query, String title, String date, String wrapper) {
        if (input == null) return null;
        String targetNorm = normalized(query.title());
        if (targetNorm.isEmpty()) return null;
        JSONObject best = null;
        double bestRank = -1.0;
        for (int i = 0; i < input.length(); i++) {
            JSONObject candidate = input.optJSONObject(i);
            if (wrapper != null && candidate != null) candidate = candidate.optJSONObject(wrapper);
            if (candidate == null) continue;
            String candNorm = normalized(candidate.optString(title));
            if (!targetNorm.equals(candNorm)) continue;
            String release = year(candidate.optString(date, candidate.optString("year")));
            if (query.year() > 0 && !Integer.toString(query.year()).equals(release)) continue;
            double rank = 0.0;
            String poster = candidate.optString("poster", candidate.optString("poster_path", ""));
            if (!poster.isBlank() && !"null".equals(poster)) rank += 1000.0;
            try {
                String rs = candidate.optString("imdbRating", "");
                if (!rs.isBlank()) rank += Double.parseDouble(rs) * 10.0;
            } catch (Exception ignored) {}
            rank += candidate.optDouble("vote_count", 0.0) * 0.01;
            if (!release.isEmpty()) {
                try { rank += Integer.parseInt(release) * 0.1; } catch (Exception ignored) {}
            }
            if (rank > bestRank) {
                bestRank = rank;
                best = candidate;
            }
        }
        return best;
    }

    public static Item arabic(Item item, boolean show) {
        if (item == null || item.year().isBlank() || item.title().isBlank()) return item;
        if (item.attribution() != null && item.attribution().contains("Wikipedia")) return item;
        try {
            String english = item.title() + " (" + item.year() + (show ? " TV series)" : " film)");
            String request = "https://en.wikipedia.org/w/api.php?action=query&format=json&formatversion=2"
                    + "&prop=langlinks&lllang=ar&titles=" + Uri.encode(english);
            JSONObject page = page(json(request));
            JSONArray links = page.optJSONArray("langlinks");
            if (links == null || links.length() != 1) return item;
            String title = links.getJSONObject(0).getString("title");
            JSONObject arabic = page(json("https://ar.wikipedia.org/w/api.php?action=query&format=json&formatversion=2"
                    + "&prop=extracts&exintro=1&explaintext=1&titles=" + Uri.encode(title)));
            String plot = arabic.optString("extract").strip();
            return new Item(title, item.year(), item.poster(), item.backdrop(), plot.isBlank() ? item.plot() : plot,
                    item.imdb(), item.score(), item.ratingSource(), item.attribution() + " · Wikipedia", item.cast(),
                    item.logo(), item.genres());
        } catch (Exception ignored) { return item; }
    }

    private static File itemCacheFile(Context context, String key) {
        String safe = key.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.length() > 110) safe = Integer.toHexString(safe.hashCode()) + "_" + safe.substring(0, 60);
        return new File(new File(context.getCacheDir(), "open_metadata_v2"), safe + ".json");
    }

    private static Item readDiskItem(Context context, String key) {
        if (context == null) return null;
        try {
            File file = itemCacheFile(context, key);
            if (!file.isFile() || file.lastModified() < System.currentTimeMillis() - DISK_TTL_MS) return null;
            byte[] bytes = Files.readAllBytes(file.toPath());
            return itemFromJson(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeDiskItem(Context context, String key, Item item) {
        if (context == null || item == null) return;
        try {
            File file = itemCacheFile(context, key);
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(itemToJson(item).toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private static JSONObject itemToJson(Item item) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("title", item.title() == null ? "" : item.title());
            obj.put("year", item.year() == null ? "" : item.year());
            obj.put("poster", item.poster() == null ? "" : item.poster());
            obj.put("backdrop", item.backdrop() == null ? "" : item.backdrop());
            obj.put("plot", item.plot() == null ? "" : item.plot());
            obj.put("imdb", item.imdb() == null ? "" : item.imdb());
            if (item.score() != null) obj.put("score", item.score());
            obj.put("ratingSource", item.ratingSource() == null ? "" : item.ratingSource());
            obj.put("attribution", item.attribution() == null ? "" : item.attribution());
            obj.put("logo", item.logo() == null ? "" : item.logo());
            obj.put("genres", item.genres() == null ? "" : item.genres());
            JSONArray arr = new JSONArray();
            if (item.cast() != null) {
                for (CastMember c : item.cast()) {
                    JSONObject co = new JSONObject();
                    co.put("name", c.name() == null ? "" : c.name());
                    co.put("role", c.role() == null ? "" : c.role());
                    co.put("image", c.image() == null ? "" : c.image());
                    co.put("personId", c.personId() == null ? "" : c.personId());
                    arr.put(co);
                }
            }
            obj.put("cast", arr);
            obj.put("ts", System.currentTimeMillis());
        } catch (Exception ignored) {}
        return obj;
    }

    private static Item itemFromJson(JSONObject obj) {
        if (obj == null) return null;
        String title = obj.optString("title", "");
        if (title.isEmpty()) return null;
        String year = obj.optString("year", "");
        String poster = obj.optString("poster", "");
        if (poster.isEmpty()) poster = null;
        String backdrop = obj.optString("backdrop", "");
        if (backdrop.isEmpty()) backdrop = null;
        String plot = obj.optString("plot", "");
        String imdb = obj.optString("imdb", "");
        Double score = obj.has("score") ? score(obj.optDouble("score", 0.0)) : null;
        String ratingSource = obj.optString("ratingSource", "");
        String attribution = obj.optString("attribution", "");
        String logo = obj.optString("logo", "");
        if (logo.isEmpty()) logo = null;
        String genres = obj.optString("genres", "");
        List<CastMember> cast = new ArrayList<>();
        JSONArray arr = obj.optJSONArray("cast");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject co = arr.optJSONObject(i);
                if (co == null) continue;
                String cname = co.optString("name", "").trim();
                if (cname.isEmpty()) continue;
                String cimg = co.optString("image", "").trim();
                cast.add(new CastMember(cname, co.optString("role", ""), cimg.isEmpty() ? null : cimg, co.optString("personId", "")));
            }
        }
        return new Item(title, year, poster, backdrop, plot, imdb, score, ratingSource, attribution, cast, logo, genres);
    }

    private static void ensurePersonPhotosLoaded(Context context) {
        if (context == null) return;
        synchronized (personPhotoCache) {
            if (personPhotosLoaded) return;
            personPhotosLoaded = true;
            try {
                File f = new File(new File(context.getCacheDir(), "open_person_photos_v1"), "photos.json");
                if (f.isFile()) {
                    JSONObject obj = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                    Iterator<String> keys = obj.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        String v = obj.optString(k, "");
                        if (!v.isEmpty()) personPhotoCache.put(k, v);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private static void savePersonPhotos(Context context) {
        if (context == null) return;
        try {
            JSONObject obj = new JSONObject();
            synchronized (personPhotoCache) {
                for (Map.Entry<String, String> entry : personPhotoCache.entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                        obj.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            File f = new File(new File(context.getCacheDir(), "open_person_photos_v1"), "photos.json");
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(obj.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private static JSONObject page(JSONObject obj) throws Exception {
        return obj.getJSONObject("query").getJSONArray("pages").getJSONObject(0);
    }

    private static String year(String text) {
        return text.matches("(?:18|19|20)[0-9]{2}.*") ? text.substring(0, 4) : "";
    }

    private static String normalized(String title) {
        return title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static Double score(double value) { return value > 0 && value <= 10 ? value : null; }

    private static String image(JSONObject value, String name) {
        String url = value == null ? "" : value.optString(name);
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null) return null;
        return java.util.Set.of("images.metahub.space", "image.tmdb.org",
                "static.tvmaze.com", "cdn.myanimelist.net").contains(host) ? url : null;
    }

    private static JSONObject json(String url) throws Exception { return new JSONObject(fetch(url)); }

    private static String fetch(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "LocalCinema/1.0 (https://github.com/alnouri96/local-cinema)");
        try (var input = connection.getInputStream(); var data = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192];
            int read;
            while ((read = input.read(block)) != -1) {
                if (data.size() + read > 2 * 1024 * 1024) throw new IOException("Metadata exceeds limit");
                data.write(block, 0, read);
            }
            return new String(data.toByteArray(), StandardCharsets.UTF_8);
        } finally { connection.disconnect(); }
    }
}

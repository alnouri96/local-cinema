package com.meedx.providers;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Zero-account daily-refreshed trailers & upcoming movies/shows/anime provider.
 * Uses open Cinemeta catalogs + Jikan v4 (MyAnimeList) + optional user TMDb key,
 * backed by a 24-hour persistent disk cache.
 */
public final class OpenTrailersProvider {
    public static final long DAILY_TTL_MS = 12L * 60L * 60L * 1000L;
    private static final int CACHE_SCHEMA_VERSION = 5;
    private static final String CACHE_DIR = "open_trailers_v1";
    private static final String PREF_NAME = "open_trailers_prefs";
    private static final String KEY_LAST_SYNC_PREFIX = "last_trailers_sync_v5_";
    private static final int MODE_TRENDING = 0;
    private static final int MODE_NEW_RECENT = 1;
    private static final int MODE_COMING_SOON = 2;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OpenTrailersSync");
        t.setDaemon(true);
        return t;
    });

    public static final class TrailerItem {
        public final String title;
        public final String year;
        public final String mediaType;
        public final String imdbId;
        public final String ytId;
        public final String trailerUrl;
        public final String backdropUrl;
        public final String thumbnailUrl;
        public final String logoUrl;
        public final String releaseDate;
        public final String runtime;
        public final String genres;
        public final String ageRating;
        public final String description;
        public final String cast;
        public final Double rating;
        public final String isoDate;

        public TrailerItem(String title, String year, String mediaType, String imdbId,
                           String ytId, String trailerUrl, String backdropUrl,
                           String thumbnailUrl, String logoUrl, String releaseDate,
                           String runtime, String genres, String ageRating,
                           String description, String cast, Double rating) {
            this(title, year, mediaType, imdbId, ytId, trailerUrl, backdropUrl, thumbnailUrl,
                    logoUrl, releaseDate, runtime, genres, ageRating, description, cast, rating, "");
        }

        public TrailerItem(String title, String year, String mediaType, String imdbId,
                           String ytId, String trailerUrl, String backdropUrl,
                           String thumbnailUrl, String logoUrl, String releaseDate,
                           String runtime, String genres, String ageRating,
                           String description, String cast, Double rating, String isoDate) {
            this.title = nonNull(title);
            this.year = nonNull(year);
            this.mediaType = nonNull(mediaType);
            this.imdbId = nonNull(imdbId);
            this.ytId = nonNull(ytId);
            this.trailerUrl = nonNull(trailerUrl);
            this.backdropUrl = nonNull(backdropUrl);
            this.thumbnailUrl = nonNull(thumbnailUrl);
            this.logoUrl = nonNull(logoUrl);
            this.releaseDate = nonNull(releaseDate);
            this.runtime = nonNull(runtime);
            this.genres = nonNull(genres);
            this.ageRating = nonNull(ageRating);
            this.description = nonNull(description);
            this.cast = nonNull(cast);
            this.rating = rating;
            this.isoDate = nonNull(isoDate);
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("title", title);
                obj.put("year", year);
                obj.put("mediaType", mediaType);
                obj.put("imdbId", imdbId);
                obj.put("ytId", ytId);
                obj.put("trailerUrl", trailerUrl);
                obj.put("backdropUrl", backdropUrl);
                obj.put("thumbnailUrl", thumbnailUrl);
                obj.put("logoUrl", logoUrl);
                obj.put("releaseDate", releaseDate);
                obj.put("runtime", runtime);
                obj.put("genres", genres);
                obj.put("ageRating", ageRating);
                obj.put("description", description);
                obj.put("cast", cast);
                if (rating != null) obj.put("rating", rating);
                obj.put("isoDate", isoDate);
            } catch (Exception ignored) {}
            return obj;
        }

        public static TrailerItem fromJson(JSONObject obj) {
            if (obj == null) return null;
            String title = obj.optString("title", "").trim();
            if (title.isEmpty()) return null;
            Double rating = obj.has("rating") ? obj.optDouble("rating") : null;
            if (rating != null && rating <= 0) rating = null;
            return new TrailerItem(
                    title,
                    obj.optString("year", ""),
                    obj.optString("mediaType", "movie"),
                    obj.optString("imdbId", ""),
                    obj.optString("ytId", ""),
                    obj.optString("trailerUrl", ""),
                    obj.optString("backdropUrl", ""),
                    obj.optString("thumbnailUrl", ""),
                    obj.optString("logoUrl", ""),
                    obj.optString("releaseDate", ""),
                    obj.optString("runtime", ""),
                    obj.optString("genres", ""),
                    obj.optString("ageRating", ""),
                    obj.optString("description", ""),
                    obj.optString("cast", ""),
                    rating,
                    obj.optString("isoDate", ""));
        }
    }

    public static final class TrailersFeed {
        public final List<TrailerItem> trending;
        public final List<TrailerItem> fresh;
        public final List<TrailerItem> comingSoon;
        public final long lastSyncMs;

        public TrailersFeed(List<TrailerItem> trending, List<TrailerItem> fresh,
                            List<TrailerItem> comingSoon, long lastSyncMs) {
            this.trending = trending == null ? List.of() : trending;
            this.fresh = fresh == null ? List.of() : fresh;
            this.comingSoon = comingSoon == null ? List.of() : comingSoon;
            this.lastSyncMs = lastSyncMs;
        }

        public boolean isEmpty() {
            return trending.isEmpty() && fresh.isEmpty() && comingSoon.isEmpty();
        }
    }

    private OpenTrailersProvider() {}

    public static void clearCache(Context context) {
        if (context == null) return;
        try {
            File dir = new File(context.getCacheDir(), CACHE_DIR);
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Exception ignored) {}
    }

    public static TrailersFeed getCached(Context context, boolean arabic) {
        if (context == null) return null;
        String lang = arabic ? "ar" : "en";
        File file = cacheFile(context, lang);
        if (!file.isFile()) return null;
        try {
            String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(raw);
            if (obj.optInt("schema_version", 0) != CACHE_SCHEMA_VERSION) {
                // Purge legacy cache lacking strict date filtering
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return null;
            }
            java.time.LocalDate today = java.time.LocalDate.now();
            List<TrailerItem> trending = filterAndSortForMode(parseItemArray(obj.optJSONArray("trending")), MODE_TRENDING, today);
            List<TrailerItem> fresh = filterAndSortForMode(parseItemArray(obj.optJSONArray("fresh")), MODE_NEW_RECENT, today);
            List<TrailerItem> coming = filterAndSortForMode(parseItemArray(obj.optJSONArray("comingSoon")), MODE_COMING_SOON, today);
            long ts = obj.optLong("last_trailers_sync_ms", file.lastModified());
            TrailersFeed feed = new TrailersFeed(trending, fresh, coming, ts);
            return (fresh.isEmpty() || coming.isEmpty()) ? null : feed;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean needsDailyRefresh(Context context, boolean arabic) {
        if (context == null) return true;
        String lang = arabic ? "ar" : "en";
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        long last = prefs.getLong(KEY_LAST_SYNC_PREFIX + lang, 0L);
        if (last <= 0L) return true;
        if (System.currentTimeMillis() - last >= DAILY_TTL_MS) return true;
        return getCached(context, arabic) == null;
    }

    /** Background 24-hour daily prefetch safe to invoke from Home startup. */
    public static void prefetchDailyIfNeeded(Context context, boolean arabic) {
        if (context == null) return;
        if (!needsDailyRefresh(context, arabic)) return;
        refreshAsync(context.getApplicationContext(), arabic, false, null);
    }

    public static void refreshAsync(Context context, boolean arabic, boolean force,
                                    Consumer<TrailersFeed> onComplete) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        POOL.execute(() -> {
            if (!force && !needsDailyRefresh(app, arabic)) {
                TrailersFeed cached = getCached(app, arabic);
                if (cached != null) {
                    postResult(onComplete, cached);
                    return;
                }
            }
            TrailersFeed live = fetchLiveFeed(app, arabic);
            if (live != null && !live.isEmpty()) {
                writeCache(app, arabic ? "ar" : "en", live);
                postResult(onComplete, live);
            } else {
                TrailersFeed fallback = getCached(app, arabic);
                postResult(onComplete, fallback);
            }
        });
    }

    /** Resolves an official YouTube watch URL (`ytId` or null when search fallback should run). */
    public static String resolveTrailerUrl(Context context, String title, int year,
                                           String imdbId, boolean isShow) {
        if (context != null) {
            // 1. Check cached trailer feeds first for zero-latency match
            for (boolean ar : new boolean[]{true, false}) {
                TrailersFeed cached = getCached(context, ar);
                if (cached == null) continue;
                TrailerItem hit = findMatching(cached, imdbId, title);
                if (hit != null && !hit.ytId.isEmpty()) {
                    return "https://www.youtube.com/watch?v=" + hit.ytId;
                }
            }
        }
        // 2. Query open Cinemeta /meta endpoint when IMDb ID is known
        if (imdbId != null && imdbId.matches("tt[0-9]+")) {
            String type = isShow ? "series" : "movie";
            JSONObject doc = getJson("https://v3-cinemeta.strem.io/meta/" + type + "/" + imdbId + ".json", 4500);
            JSONObject meta = doc != null ? doc.optJSONObject("meta") : null;
            if (meta != null) {
                String yt = extractCinemetaYtId(meta);
                if (!yt.isEmpty()) return "https://www.youtube.com/watch?v=" + yt;
            }
        }
        // 3. Optional TMDb boost if user entered a key
        if (context != null) {
            String tmdbKey = ProviderKeys.get(context, ProviderKeys.TMDB);
            if (!tmdbKey.isBlank() && imdbId != null && imdbId.matches("tt[0-9]+")) {
                String findUrl = "https://api.themoviedb.org/3/find/" + Uri.encode(imdbId)
                        + "?api_key=" + Uri.encode(tmdbKey) + "&external_source=imdb_id";
                JSONObject found = getJson(findUrl, 4500);
                if (found != null) {
                    JSONArray arr = found.optJSONArray(isShow ? "tv_results" : "movie_results");
                    if (arr != null && arr.length() > 0 && arr.optJSONObject(0) != null) {
                        long tmdbId = arr.optJSONObject(0).optLong("id", 0L);
                        if (tmdbId > 0) {
                            String vidsUrl = "https://api.themoviedb.org/3/" + (isShow ? "tv/" : "movie/")
                                    + tmdbId + "/videos?api_key=" + Uri.encode(tmdbKey);
                            JSONObject vids = getJson(vidsUrl, 4500);
                            String yt = extractTmdbYtId(vids);
                            if (!yt.isEmpty()) return "https://www.youtube.com/watch?v=" + yt;
                        }
                    }
                }
            }
        }
        // 4. Jikan Anime lookup if title exists
        if (title != null && !title.isBlank()) {
            try {
                String q = URLEncoder.encode(title.trim(), "UTF-8");
                JSONObject jikan = getJson("https://api.jikan.moe/v4/anime?q=" + q + "&limit=3", 4000);
                JSONArray data = jikan != null ? jikan.optJSONArray("data") : null;
                if (data != null && data.length() > 0) {
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject row = data.optJSONObject(i);
                        JSONObject tr = row != null ? row.optJSONObject("trailer") : null;
                        String yt = tr != null ? tr.optString("youtube_id", "").trim() : "";
                        if (!yt.isEmpty() && titleMatchesLoose(title, row.optString("title"), row.optString("title_english"))) {
                            return "https://www.youtube.com/watch?v=" + yt;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static String buildYouTubeWatchUrl(String ytId) {
        return "https://www.youtube.com/watch?v=" + ytId;
    }

    public static String buildYouTubeSearchUrl(String title, int year) {
        String cleanTitle = nonNull(title).trim();
        String q = cleanTitle + (year > 1900 ? " " + year : "") + " official trailer";
        return "https://www.youtube.com/results?search_query=" + Uri.encode(q.trim());
    }

    private static java.time.LocalDate parseIsoLocalDate(String raw) {
        String s = nonNull(raw).trim();
        if (s.length() >= 10 && s.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            try {
                return java.time.LocalDate.parse(s.substring(0, 10));
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static int parseYearInt(String yearStr) {
        String s = extractYear(yearStr, "");
        if (!s.isEmpty()) {
            try { return Integer.parseInt(s); } catch (Exception ignored) {}
        }
        return 0;
    }

    private static boolean isExplicitUpcomingLabel(String releaseDate) {
        if (releaseDate == null) return false;
        return releaseDate.contains("قادم قريباً") || releaseDate.contains("Coming Soon")
                || releaseDate.startsWith("يُعرض") || releaseDate.startsWith("Releases");
    }

    private static boolean isValidForMode(int mode, java.time.LocalDate isoDate, int yearNum, java.time.LocalDate today,
                                          boolean trustedUpcoming, String releaseDate) {
        return isValidForMode(mode, isoDate, yearNum, today, trustedUpcoming, releaseDate, 120);
    }

    private static boolean isValidForMode(int mode, java.time.LocalDate isoDate, int yearNum, java.time.LocalDate today,
                                          boolean trustedUpcoming, String releaseDate, int recentDaysWindow) {
        int currentYear = today.getYear();
        if (mode == MODE_NEW_RECENT) {
            if (isoDate != null) {
                return !isoDate.isAfter(today) && !isoDate.isBefore(today.minusDays(recentDaysWindow));
            }
            return trustedUpcoming && (yearNum == 0 || yearNum >= currentYear - 1);
        } else if (mode == MODE_COMING_SOON) {
            // Strictly future releases (releaseDate > today), future year, or confirmed TBA upcoming entries
            if (isoDate != null) {
                return isoDate.isAfter(today);
            }
            if (yearNum > currentYear) return true;
            if (yearNum > 0 && yearNum < currentYear) return false;
            return trustedUpcoming || (yearNum == 0 && isExplicitUpcomingLabel(releaseDate));
        } else {
            // MODE_TRENDING: current or recent releases only, no vintage catalog entries
            if (yearNum > 0 && yearNum < currentYear - 2) return false;
            return true;
        }
    }

    private static String normalizeDedupeTitle(String title) {
        if (title == null) return "";
        return title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static boolean addIfUnique(TrailerItem item, List<TrailerItem> target, Set<String> seenIds,
                                       Set<String> seenTitles, String idKey) {
        if (item == null || item.title.isEmpty()) return false;
        String tyKey = normalizeDedupeTitle(item.title) + "|" + item.year;
        if ((idKey != null && !idKey.isEmpty() && seenIds.contains(idKey))
                || (!tyKey.equals("|") && seenTitles.contains(tyKey))) {
            return false;
        }
        if (idKey != null && !idKey.isEmpty()) seenIds.add(idKey);
        seenTitles.add(tyKey);
        target.add(item);
        return true;
    }

    private static List<TrailerItem> filterAndSortForMode(List<TrailerItem> input, int mode, java.time.LocalDate today) {
        List<TrailerItem> filtered = new ArrayList<>();
        Set<String> seenTitles = new LinkedHashSet<>();
        if (input == null) return filtered;
        int window = 120;
        for (TrailerItem it : input) {
            java.time.LocalDate d = parseIsoLocalDate(it.isoDate);
            int y = d != null ? d.getYear() : parseYearInt(it.year);
            if (isValidForMode(mode, d, y, today, !it.ytId.isEmpty() && mode != MODE_NEW_RECENT, it.releaseDate, window)) {
                String tyKey = normalizeDedupeTitle(it.title) + "|" + it.year;
                if (seenTitles.add(tyKey)) filtered.add(it);
            }
        }
        if (mode == MODE_NEW_RECENT && filtered.size() < 15) {
            for (TrailerItem it : input) {
                if (filtered.size() >= 25) break;
                java.time.LocalDate d = parseIsoLocalDate(it.isoDate);
                int y = d != null ? d.getYear() : parseYearInt(it.year);
                if (isValidForMode(mode, d, y, today, true, it.releaseDate, 365)) {
                    String tyKey = normalizeDedupeTitle(it.title) + "|" + it.year;
                    if (seenTitles.add(tyKey)) filtered.add(it);
                }
            }
        }
        if (mode == MODE_NEW_RECENT) {
            // Newest release first (descending by isoDate, items with YouTube trailer prioritized on tie)
            filtered.sort((a, b) -> {
                int c = b.isoDate.compareTo(a.isoDate);
                if (c != 0) return c;
                return Boolean.compare(b.ytId.isEmpty(), a.ytId.isEmpty());
            });
        } else if (mode == MODE_COMING_SOON) {
            // Soonest upcoming release first (dated future items first, then next-year items, then TBA)
            filtered.sort((a, b) -> {
                boolean aHas = !a.isoDate.isEmpty();
                boolean bHas = !b.isoDate.isEmpty();
                if (aHas && bHas) return a.isoDate.compareTo(b.isoDate);
                if (aHas) return -1;
                if (bHas) return 1;
                boolean aYear = !a.year.isEmpty();
                boolean bYear = !b.year.isEmpty();
                if (aYear && bYear) return a.year.compareTo(b.year);
                if (aYear) return -1;
                if (bYear) return 1;
                return Boolean.compare(b.ytId.isEmpty(), a.ytId.isEmpty());
            });
        } else {
            // Trending: prioritize entries with official YouTube trailers
            filtered.sort((a, b) -> Boolean.compare(b.ytId.isEmpty(), a.ytId.isEmpty()));
        }
        if (filtered.size() > 30) {
            return new ArrayList<>(filtered.subList(0, 30));
        }
        return filtered;
    }

    private static TrailersFeed fetchLiveFeed(Context context, boolean arabic) {
        java.time.LocalDate today = java.time.LocalDate.now();
        int currentYear = today.getYear();
        List<TrailerItem> trending = new ArrayList<>();
        List<TrailerItem> fresh = new ArrayList<>();
        List<TrailerItem> comingSoon = new ArrayList<>();
        Set<String> seenTrendingIds = new LinkedHashSet<>();
        Set<String> seenTrendingTitles = new LinkedHashSet<>();
        Set<String> seenNewIds = new LinkedHashSet<>();
        Set<String> seenNewTitles = new LinkedHashSet<>();
        Set<String> seenSoonIds = new LinkedHashSet<>();
        Set<String> seenSoonTitles = new LinkedHashSet<>();

        java.util.concurrent.ConcurrentHashMap<String, JSONArray> catalogCache = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ConcurrentHashMap<String, JSONObject> metaCache = new java.util.concurrent.ConcurrentHashMap<>();

        String tmdbKey = ProviderKeys.get(context, ProviderKeys.TMDB);
        if (!tmdbKey.isBlank()) {
            appendTmdbList("https://api.themoviedb.org/3/trending/all/day?api_key=" + Uri.encode(tmdbKey)
                            + "&language=" + (arabic ? "ar-SA" : "en-US"),
                    tmdbKey, "", trending, seenTrendingIds, seenTrendingTitles, 16, arabic, MODE_TRENDING, today);
            appendTmdbList("https://api.themoviedb.org/3/movie/now_playing?api_key=" + Uri.encode(tmdbKey)
                            + "&language=" + (arabic ? "ar-SA" : "en-US"),
                    tmdbKey, "movie", fresh, seenNewIds, seenNewTitles, 14, arabic, MODE_NEW_RECENT, today);
            appendTmdbList("https://api.themoviedb.org/3/tv/on_the_air?api_key=" + Uri.encode(tmdbKey)
                            + "&language=" + (arabic ? "ar-SA" : "en-US"),
                    tmdbKey, "tv", fresh, seenNewIds, seenNewTitles, 10, arabic, MODE_NEW_RECENT, today);
            appendTmdbList("https://api.themoviedb.org/3/movie/upcoming?api_key=" + Uri.encode(tmdbKey)
                            + "&language=" + (arabic ? "ar-SA" : "en-US") + "&page=1",
                    tmdbKey, "movie", comingSoon, seenSoonIds, seenSoonTitles, 16, arabic, MODE_COMING_SOON, today);
            appendTmdbList("https://api.themoviedb.org/3/movie/upcoming?api_key=" + Uri.encode(tmdbKey)
                            + "&language=" + (arabic ? "ar-SA" : "en-US") + "&page=2",
                    tmdbKey, "movie", comingSoon, seenSoonIds, seenSoonTitles, 12, arabic, MODE_COMING_SOON, today);
        }

        // Open Jikan v4 Promos (free direct YouTube promotional trailers for popular & latest releases)
        appendJikanPromos("https://api.jikan.moe/v4/watch/promos/popular",
                trending, seenTrendingIds, seenTrendingTitles, 14, arabic);
        appendJikanPromos("https://api.jikan.moe/v4/watch/promos",
                fresh, seenNewIds, seenNewTitles, 14, arabic);

        // Open Cinemeta parallel metadata & trailer extraction (80-120 items across recent/upcoming years)
        appendCinemetaCatalog("https://v3-cinemeta.strem.io/catalog/movie/top.json",
                "movie", trending, seenTrendingIds, seenTrendingTitles, 14, arabic, MODE_TRENDING, today, catalogCache, metaCache);
        appendCinemetaCatalog("https://v3-cinemeta.strem.io/catalog/series/top.json",
                "series", trending, seenTrendingIds, seenTrendingTitles, 12, arabic, MODE_TRENDING, today, catalogCache, metaCache);

        appendCinemetaCatalog("https://v3-cinemeta.strem.io/catalog/movie/year.json",
                "movie", fresh, seenNewIds, seenNewTitles, 18, arabic, MODE_NEW_RECENT, today, catalogCache, metaCache);
        appendCinemetaCatalog("https://v3-cinemeta.strem.io/catalog/movie/year/genre=" + currentYear + ".json",
                "movie", fresh, seenNewIds, seenNewTitles, 16, arabic, MODE_NEW_RECENT, today, catalogCache, metaCache);
        appendCinemetaCatalog("https://v3-cinemeta.strem.io/catalog/series/year/genre=" + currentYear + ".json",
                "series", fresh, seenNewIds, seenNewTitles, 12, arabic, MODE_NEW_RECENT, today, catalogCache, metaCache);
        appendCinemetaCatalog("https://v3-cinemeta.strem.io/catalog/movie/top.json",
                "movie", fresh, seenNewIds, seenNewTitles, 12, arabic, MODE_NEW_RECENT, today, catalogCache, metaCache);

        appendCinemetaCatalog("https://v3-cinemeta.strem.io/catalog/movie/year/genre=" + (currentYear + 1) + ".json",
                "movie", comingSoon, seenSoonIds, seenSoonTitles, 14, arabic, MODE_COMING_SOON, today, catalogCache, metaCache);
        appendCinemetaCatalog("https://v3-cinemeta.strem.io/catalog/series/year/genre=" + (currentYear + 1) + ".json",
                "series", comingSoon, seenSoonIds, seenSoonTitles, 8, arabic, MODE_COMING_SOON, today, catalogCache, metaCache);
        appendCinemetaCatalog("https://v3-cinemeta.strem.io/catalog/movie/year/genre=" + currentYear + ".json",
                "movie", comingSoon, seenSoonIds, seenSoonTitles, 14, arabic, MODE_COMING_SOON, today, catalogCache, metaCache);
        appendCinemetaCatalog("https://v3-cinemeta.strem.io/catalog/series/year/genre=" + currentYear + ".json",
                "series", comingSoon, seenSoonIds, seenSoonTitles, 10, arabic, MODE_COMING_SOON, today, catalogCache, metaCache);
        appendCinemetaCatalog("https://v3-cinemeta.strem.io/catalog/movie/top.json",
                "movie", comingSoon, seenSoonIds, seenSoonTitles, 12, arabic, MODE_COMING_SOON, today, catalogCache, metaCache);

        // Open Jikan v4 Seasons & Upcoming (Now & Coming Soon — multiple sources for 15-30 items per row)
        appendJikanCatalog("https://api.jikan.moe/v4/seasons/now?limit=25",
                fresh, seenNewIds, seenNewTitles, 10, arabic, MODE_NEW_RECENT, today, false);
        appendJikanCatalog("https://api.jikan.moe/v4/seasons/upcoming?limit=25",
                comingSoon, seenSoonIds, seenSoonTitles, 16, arabic, MODE_COMING_SOON, today, true);
        if (comingSoon.size() < 24) {
            appendJikanCatalog("https://api.jikan.moe/v4/anime?status=upcoming&order_by=members&sort=desc&limit=25",
                    comingSoon, seenSoonIds, seenSoonTitles, 28 - comingSoon.size(), arabic, MODE_COMING_SOON, today, true);
        }
        if (comingSoon.size() < 22) {
            appendJikanCatalog("https://api.jikan.moe/v4/seasons/upcoming?page=2&limit=25",
                    comingSoon, seenSoonIds, seenSoonTitles, 24 - comingSoon.size(), arabic, MODE_COMING_SOON, today, true);
        }

        trending = filterAndSortForMode(trending, MODE_TRENDING, today);
        fresh = filterAndSortForMode(fresh, MODE_NEW_RECENT, today);
        comingSoon = filterAndSortForMode(comingSoon, MODE_COMING_SOON, today);

        return new TrailersFeed(trending, fresh, comingSoon, System.currentTimeMillis());
    }

    private static void appendJikanPromos(String url, List<TrailerItem> target,
                                          Set<String> seenIds, Set<String> seenTitles,
                                          int maxAdd, boolean arabic) {
        JSONObject root = getJson(url, 6500);
        JSONArray data = root != null ? root.optJSONArray("data") : null;
        if (data == null) return;
        String currentYearStr = Integer.toString(java.time.LocalDate.now().getYear());
        int added = 0;
        for (int i = 0; i < data.length() && added < maxAdd; i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null) continue;
            JSONObject entry = row.optJSONObject("entry");
            JSONObject trailer = row.optJSONObject("trailer");
            if (entry == null || trailer == null) continue;
            String ytId = trailer.optString("youtube_id", "").trim();
            if (ytId.isEmpty()) continue;
            String title = entry.optString("title", "").trim();
            if (title.isEmpty()) continue;
            long malId = entry.optLong("mal_id", 0L);
            String dedupeKey = malId > 0 ? "mal:" + malId : "";
            String trailerThumb = "";
            if (trailer.optJSONObject("images") != null) {
                JSONObject imgs = trailer.optJSONObject("images");
                trailerThumb = imgs.optString("maximum_image_url",
                        imgs.optString("large_image_url", imgs.optString("medium_image_url", ""))).trim();
            }
            if (trailerThumb.isEmpty()) trailerThumb = "https://i.ytimg.com/vi/" + ytId + "/hqdefault.jpg";
            String posterBg = "";
            if (entry.optJSONObject("images") != null && entry.optJSONObject("images").optJSONObject("jpg") != null) {
                posterBg = entry.optJSONObject("images").optJSONObject("jpg").optString("large_image_url", "").trim();
            }
            String promoDesc = row.optString("title", "").trim();
            String genres = arabic ? "أنمي · إعلان رسمي" : "Anime · Official PV";
            TrailerItem item = new TrailerItem(title, currentYearStr, "anime", "", ytId,
                    buildYouTubeWatchUrl(ytId),
                    !trailerThumb.isEmpty() ? trailerThumb : posterBg,
                    trailerThumb, "", arabic ? "إعلان رسمي جديد" : "Official Promo",
                    "", genres, "PG-13", promoDesc, "MyAnimeList / Studio", null, "");
            if (addIfUnique(item, target, seenIds, seenTitles, dedupeKey)) added++;
        }
    }

    private static void appendCinemetaCatalog(String url, String type, List<TrailerItem> target,
                                              Set<String> seenIds, Set<String> seenTitles,
                                              int maxAdd, boolean arabic,
                                              int mode, java.time.LocalDate today,
                                              java.util.concurrent.ConcurrentHashMap<String, JSONArray> catalogCache,
                                              java.util.concurrent.ConcurrentHashMap<String, JSONObject> metaCache) {
        JSONArray metas = catalogCache.get(url);
        if (metas == null) {
            JSONObject root = getJson(url, 6500);
            metas = root != null ? root.optJSONArray("metas") : null;
            if (metas == null) return;
            catalogCache.put(url, metas);
        }
        int scanLimit = Math.min(metas.length(), 45);
        List<JSONObject> batch = new ArrayList<>(scanLimit);
        List<String> idsToEnrich = new ArrayList<>();
        for (int i = 0; i < scanLimit; i++) {
            JSONObject m = metas.optJSONObject(i);
            if (m == null) continue;
            batch.add(m);
            String imdb = m.optString("imdb_id", m.optString("id", "")).trim();
            String metaKey = type + ":" + imdb;
            boolean needsEnrich = imdb.startsWith("tt") && !metaCache.containsKey(metaKey)
                    && (extractCinemetaYtId(m).isEmpty() || parseIsoLocalDate(m.optString("released", "")) == null);
            if (needsEnrich) {
                idsToEnrich.add(imdb);
            }
        }
        if (!idsToEnrich.isEmpty()) {
            ExecutorService metaPool = Executors.newFixedThreadPool(8);
            try {
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>(idsToEnrich.size());
                for (String imdb : idsToEnrich) {
                    futures.add(metaPool.submit(() -> {
                        String metaKey = type + ":" + imdb;
                        if (metaCache.containsKey(metaKey)) return;
                        JSONObject doc = getJson("https://v3-cinemeta.strem.io/meta/" + type + "/" + imdb + ".json", 4000);
                        JSONObject full = doc != null ? doc.optJSONObject("meta") : null;
                        if (full != null) metaCache.put(metaKey, full);
                    }));
                }
                for (var f : futures) {
                    try { f.get(4500, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Exception ignored) {}
                }
            } finally {
                metaPool.shutdownNow();
            }
        }
        int added = 0;
        for (JSONObject raw : batch) {
            if (added >= maxAdd) break;
            String imdb = raw.optString("imdb_id", raw.optString("id", "")).trim();
            JSONObject m = metaCache.getOrDefault(type + ":" + imdb, raw);
            String name = m.optString("name", raw.optString("name", "")).trim();
            if (name.isEmpty()) continue;
            String releasedRaw = m.optString("released", raw.optString("released", "")).trim();
            String releaseInfo = m.optString("releaseInfo", m.optString("year", raw.optString("releaseInfo", ""))).trim();
            java.time.LocalDate isoDate = parseIsoLocalDate(releasedRaw);
            String isoStr = isoDate != null ? isoDate.toString() : "";
            String year = isoDate != null ? Integer.toString(isoDate.getYear()) : extractYear(releaseInfo, releasedRaw);
            int yearNum = parseYearInt(year);
            if (!isValidForMode(mode, isoDate, yearNum, today, false, "", 240)) continue;

            String ytId = extractCinemetaYtId(m);
            if (ytId.isEmpty()) ytId = extractCinemetaYtId(raw);
            String bg = m.optString("background", raw.optString("background", "")).trim();
            if (bg.isEmpty() && imdb.startsWith("tt")) {
                bg = "https://images.metahub.space/background/medium/" + imdb + "/img";
            }
            String thumb = !ytId.isEmpty()
                    ? "https://i.ytimg.com/vi/" + ytId + "/hqdefault.jpg"
                    : (!bg.isEmpty() ? bg : m.optString("poster", raw.optString("poster", "")).trim());
            if (thumb.isEmpty()) continue;

            String logo = m.optString("logo", raw.optString("logo", "")).trim();
            if (logo.isEmpty() && imdb.startsWith("tt")) {
                logo = "https://images.metahub.space/logo/medium/" + imdb + "/img";
            }
            String rel = formatReleaseDate(isoStr, year, arabic);
            String runtime = m.optString("runtime", "").trim();
            if (arabic && runtime.endsWith(" min")) {
                runtime = runtime.replace(" min", " د");
            }
            String genres = translateGenres(joinArray(m.optJSONArray("genres"), m.optJSONArray("genre"), 3), arabic);
            String cast = joinArray(m.optJSONArray("cast"), null, 4);
            String age = m.optString("certification", m.optString("ageRating", "")).trim();
            if (age.isEmpty()) age = "series".equals(type) ? "TV-14" : "PG-13";
            String desc = m.optString("description", raw.optString("description", "")).trim();
            Double score = null;
            try {
                String rs = m.optString("imdbRating", "");
                if (!rs.isBlank()) score = Double.parseDouble(rs);
            } catch (Exception ignored) {}

            TrailerItem item = new TrailerItem(name, year, type, imdb, ytId,
                    ytId.isEmpty() ? "" : buildYouTubeWatchUrl(ytId),
                    !bg.isEmpty() ? bg : thumb, thumb, logo,
                    rel, runtime, genres, age, desc, cast, score, isoStr);
            if (addIfUnique(item, target, seenIds, seenTitles, imdb)) added++;
        }
    }

    private static void appendJikanCatalog(String url, List<TrailerItem> target,
                                           Set<String> seenIds, Set<String> seenTitles,
                                           int maxAdd, boolean arabic,
                                           int mode, java.time.LocalDate today,
                                           boolean upcomingEndpoint) {
        JSONObject root = getJson(url, 6500);
        JSONArray data = root != null ? root.optJSONArray("data") : null;
        if (data == null) return;
        int added = 0;
        for (int pass = 0; pass < 2 && added < maxAdd; pass++) {
            for (int i = 0; i < data.length() && added < maxAdd; i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                JSONObject trailer = item.optJSONObject("trailer");
                String ytId = trailer != null ? trailer.optString("youtube_id", "").trim() : "";
                if (pass == 0 && ytId.isEmpty()) continue;
                if (pass == 1 && !ytId.isEmpty()) continue;
                String eng = item.optString("title_english", "").trim();
                String title = !eng.isEmpty() ? eng : item.optString("title", "").trim();
                if (title.isEmpty()) continue;

                String airedFrom = item.optJSONObject("aired") != null
                        ? item.optJSONObject("aired").optString("from", "") : "";
                String airedStr = item.optJSONObject("aired") != null
                        ? item.optJSONObject("aired").optString("string", "") : "";
                java.time.LocalDate isoDate = parseIsoLocalDate(airedFrom);
                String isoStr = isoDate != null ? isoDate.toString() : "";
                String year = isoDate != null ? Integer.toString(isoDate.getYear()) : extractYear(airedStr, airedFrom);
                int yearNum = parseYearInt(year);
                if (!isValidForMode(mode, isoDate, yearNum, today, upcomingEndpoint, "", 240)) continue;

                String dedupeKey = "mal:" + item.optLong("mal_id", 0L);

                String trailerThumb = "";
                if (trailer != null && trailer.optJSONObject("images") != null) {
                    JSONObject imgs = trailer.optJSONObject("images");
                    trailerThumb = imgs.optString("maximum_image_url",
                            imgs.optString("large_image_url", imgs.optString("medium_image_url", ""))).trim();
                }
                if (trailerThumb.isEmpty() && !ytId.isEmpty()) {
                    trailerThumb = "https://i.ytimg.com/vi/" + ytId + "/hqdefault.jpg";
                }
                String posterBg = "";
                if (item.optJSONObject("images") != null && item.optJSONObject("images").optJSONObject("jpg") != null) {
                    posterBg = item.optJSONObject("images").optJSONObject("jpg").optString("large_image_url", "").trim();
                }
                String thumb = !trailerThumb.isEmpty() ? trailerThumb : posterBg;
                String bg = !trailerThumb.isEmpty() ? trailerThumb : posterBg;
                if (thumb.isEmpty()) continue;

                String rel = formatReleaseDate(isoStr, year, arabic);
                if (mode == MODE_COMING_SOON && rel.isEmpty()) rel = arabic ? "قادم قريباً" : "Coming Soon";
                String runtime = item.optString("duration", "").replace(" per ep", "").trim();
                if (arabic && runtime.endsWith(" min")) runtime = runtime.replace(" min", " د");
                String genres = translateGenres(joinObjectNames(item.optJSONArray("genres"), 3), arabic);
                if (genres.isEmpty()) genres = arabic ? "أنمي" : "Anime";
                String ratingRaw = item.optString("rating", "").trim();
                String age = ratingRaw.contains(" - ") ? ratingRaw.substring(0, ratingRaw.indexOf(" - ")).trim() : "PG-13";
                String desc = item.optString("synopsis", "").trim();
                Double score = item.has("score") && !item.isNull("score") ? item.optDouble("score") : null;

                TrailerItem ti = new TrailerItem(title, year, "anime", "", ytId,
                        ytId.isEmpty() ? "" : buildYouTubeWatchUrl(ytId),
                        bg, thumb, "", rel, runtime, genres, age, desc, "MyAnimeList / Studio", score, isoStr);
                if (addIfUnique(ti, target, seenIds, seenTitles, dedupeKey)) added++;
            }
        }
    }

    private static void appendTmdbList(String url, String key, String forcedType, List<TrailerItem> target,
                                       Set<String> seenIds, Set<String> seenTitles,
                                       int maxAdd, boolean arabic,
                                       int mode, java.time.LocalDate today) {
        JSONObject root = getJson(url, 6000);
        JSONArray results = root != null ? root.optJSONArray("results") : null;
        if (results == null) return;
        int added = 0;
        for (int i = 0; i < results.length() && added < maxAdd; i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String mediaType = !forcedType.isEmpty() ? forcedType : r.optString("media_type", "movie");
            if ("person".equals(mediaType)) continue;
            long id = r.optLong("id", 0L);
            String title = r.optString("title", r.optString("name", "")).trim();
            if (title.isEmpty() || id <= 0) continue;
            String dateStr = r.optString("release_date", r.optString("first_air_date", "")).trim();
            java.time.LocalDate isoDate = parseIsoLocalDate(dateStr);
            String isoStr = isoDate != null ? isoDate.toString() : "";
            String year = isoDate != null ? Integer.toString(isoDate.getYear()) : (dateStr.length() >= 4 ? dateStr.substring(0, 4) : "");
            int yearNum = parseYearInt(year);
            if (!isValidForMode(mode, isoDate, yearNum, today, false, "", 240)) continue;

            String backdropPath = r.optString("backdrop_path", "").trim();
            if (backdropPath.isEmpty() || "null".equals(backdropPath)) continue;
            String dedupeKey = mediaType + ":" + id;

            String vidsUrl = "https://api.themoviedb.org/3/" + ("tv".equals(mediaType) ? "tv/" : "movie/")
                    + id + "/videos?api_key=" + Uri.encode(key);
            String ytId = extractTmdbYtId(getJson(vidsUrl, 3500));
            String bg = "https://image.tmdb.org/t/p/w1280" + backdropPath;
            String thumb = !ytId.isEmpty()
                    ? "https://i.ytimg.com/vi/" + ytId + "/hqdefault.jpg"
                    : "https://image.tmdb.org/t/p/w780" + backdropPath;
            String rel = formatReleaseDate(isoStr, year, arabic);
            String desc = r.optString("overview", "").trim();
            Double score = r.has("vote_average") ? r.optDouble("vote_average") : null;
            String genres = "tv".equals(mediaType) ? (arabic ? "مسلسل" : "Series") : (arabic ? "فيلم" : "Movie");

            TrailerItem ti = new TrailerItem(title, year, "tv".equals(mediaType) ? "series" : "movie", "", ytId,
                    ytId.isEmpty() ? "" : buildYouTubeWatchUrl(ytId),
                    bg, thumb, "", rel, "", genres, "tv".equals(mediaType) ? "TV-14" : "PG-13", desc, "", score, isoStr);
            if (addIfUnique(ti, target, seenIds, seenTitles, dedupeKey)) added++;
        }
    }

    private static String translateGenres(String genres, boolean arabic) {
        if (!arabic || genres == null || genres.isBlank()) return nonNull(genres);
        String[] parts = genres.split("\\s*,\\s*");
        List<String> mapped = new ArrayList<>(parts.length);
        for (String p : parts) {
            String t = switch (p.trim().toLowerCase(Locale.ROOT)) {
                case "action" -> "أكشن";
                case "adventure" -> "مغامرة";
                case "animation" -> "رسوم متحركة";
                case "comedy" -> "كوميديا";
                case "crime" -> "جريمة";
                case "documentary" -> "وثائقي";
                case "drama" -> "دراما";
                case "family" -> "عائلي";
                case "fantasy" -> "خيال";
                case "history" -> "تاريخي";
                case "horror" -> "رعب";
                case "music", "musical" -> "موسيقي";
                case "mystery" -> "غموض";
                case "romance" -> "رومانسي";
                case "sci-fi", "science fiction" -> "خيال علمي";
                case "thriller" -> "إثارة";
                case "war" -> "حرب";
                case "western" -> "غربي";
                case "biography" -> "سيرة ذاتية";
                case "sport", "sports" -> "رياضي";
                default -> p.trim();
            };
            if (!t.isEmpty()) mapped.add(t);
        }
        return String.join("، ", mapped);
    }

    private static String extractCinemetaYtId(JSONObject m) {
        JSONArray streams = m.optJSONArray("trailerStreams");
        if (streams != null) {
            for (int i = 0; i < streams.length(); i++) {
                JSONObject o = streams.optJSONObject(i);
                String id = o != null ? o.optString("ytId", "").trim() : "";
                if (!id.isEmpty()) return id;
            }
        }
        JSONArray trailers = m.optJSONArray("trailers");
        if (trailers != null) {
            for (int i = 0; i < trailers.length(); i++) {
                JSONObject o = trailers.optJSONObject(i);
                String src = o != null ? o.optString("source", "").trim() : "";
                if (!src.isEmpty() && !src.contains("/")) return src;
            }
        }
        return "";
    }

    private static String extractTmdbYtId(JSONObject vids) {
        JSONArray results = vids != null ? vids.optJSONArray("results") : null;
        if (results == null) return "";
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject v = results.optJSONObject(i);
                if (v == null || !"YouTube".equalsIgnoreCase(v.optString("site"))) continue;
                String type = v.optString("type", "");
                if (pass == 0 && !"Trailer".equalsIgnoreCase(type)) continue;
                String k = v.optString("key", "").trim();
                if (!k.isEmpty()) return k;
            }
        }
        return "";
    }

    private static TrailerItem findMatching(TrailersFeed feed, String imdbId, String title) {
        List<List<TrailerItem>> all = List.of(feed.trending, feed.fresh, feed.comingSoon);
        for (List<TrailerItem> row : all) {
            for (TrailerItem it : row) {
                if (imdbId != null && !imdbId.isBlank() && imdbId.equalsIgnoreCase(it.imdbId)) return it;
                if (title != null && !title.isBlank() && title.equalsIgnoreCase(it.title)) return it;
            }
        }
        return null;
    }

    private static boolean titleMatchesLoose(String query, String t1, String t2) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        return (t1 != null && t1.toLowerCase(Locale.ROOT).contains(q))
                || (t2 != null && t2.toLowerCase(Locale.ROOT).contains(q));
    }

    private static String formatReleaseDate(String iso, String fallback, boolean arabic) {
        String raw = nonNull(iso).trim();
        if (raw.length() >= 10 && raw.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            try {
                java.time.LocalDate date = java.time.LocalDate.parse(raw.substring(0, 10));
                boolean future = date.isAfter(java.time.LocalDate.now());
                if (arabic) {
                    String[] arMonths = {"يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
                            "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"};
                    String monthName = arMonths[date.getMonthValue() - 1];
                    return (future ? "يُعرض " : "عُرض ") + date.getDayOfMonth() + " " + monthName;
                } else {
                    String monthName = date.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);
                    return (future ? "Releases " : "Released ") + monthName + " " + date.getDayOfMonth();
                }
            } catch (Exception ignored) {
                raw = raw.substring(0, 10);
            }
        } else {
            raw = nonNull(fallback).trim();
        }
        if (raw.isEmpty()) return "";
        return (arabic ? "يُعرض " : "Releases ") + raw;
    }

    private static String extractYear(String s1, String s2) {
        for (String s : new String[]{nonNull(s1), nonNull(s2)}) {
            var m = java.util.regex.Pattern.compile("(19|20)\\d{2}").matcher(s);
            if (m.find()) return m.group();
        }
        return "";
    }

    private static String joinArray(JSONArray a, JSONArray b, int limit) {
        JSONArray src = (a != null && a.length() > 0) ? a : b;
        if (src == null) return "";
        List<String> out = new ArrayList<>();
        for (int i = 0; i < src.length() && out.size() < limit; i++) {
            String s = src.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return String.join(", ", out);
    }

    private static String joinObjectNames(JSONArray arr, int limit) {
        if (arr == null) return "";
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.length() && out.size() < limit; i++) {
            JSONObject o = arr.optJSONObject(i);
            String n = o != null ? o.optString("name", "").trim() : "";
            if (!n.isEmpty()) out.add(n);
        }
        return String.join(", ", out);
    }

    private static List<TrailerItem> parseItemArray(JSONArray arr) {
        List<TrailerItem> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            TrailerItem it = TrailerItem.fromJson(arr.optJSONObject(i));
            if (it != null) out.add(it);
        }
        return out;
    }

    private static void writeCache(Context context, String lang, TrailersFeed feed) {
        try {
            File file = cacheFile(context, lang);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            JSONObject obj = new JSONObject();
            obj.put("schema_version", CACHE_SCHEMA_VERSION);
            obj.put("last_trailers_sync_ms", feed.lastSyncMs);
            obj.put("trending", toJsonArray(feed.trending));
            obj.put("fresh", toJsonArray(feed.fresh));
            obj.put("comingSoon", toJsonArray(feed.comingSoon));
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(obj.toString().getBytes(StandardCharsets.UTF_8));
            }
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_LAST_SYNC_PREFIX + lang, feed.lastSyncMs)
                    .apply();
        } catch (Exception ignored) {}
    }

    private static JSONArray toJsonArray(List<TrailerItem> items) {
        JSONArray arr = new JSONArray();
        for (TrailerItem it : items) arr.put(it.toJson());
        return arr;
    }

    private static File cacheFile(Context context, String lang) {
        return new File(new File(context.getCacheDir(), CACHE_DIR), "feed_" + lang + ".json");
    }

    private static void postResult(Consumer<TrailersFeed> cb, TrailersFeed feed) {
        if (cb != null) MAIN.post(() -> cb.accept(feed));
    }

    private static JSONObject getJson(String urlStr, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "LocalCinema/1.0");
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) return null;
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                String body = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
                return body.startsWith("{") ? new JSONObject(body) : null;
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}

package com.meedx.providers;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fast zero-secret multi-source ratings aggregator: IMDb (Cinemeta), TMDb (user key),
 * TVmaze (series rating), Trakt (user Client ID), and MyAnimeList (Jikan).
 * Slow Wikidata SPARQL queries are intentionally disabled.
 */
public final class MultiRatingsProvider {
    private static final long TTL_MS = 24L * 60 * 60 * 1000;
    private static final Map<String, Ratings> CACHE = new LinkedHashMap<>();

    public static final class Ratings {
        public final Double imdb;
        public final Double tmdb;
        public final Double tvmaze;
        public final Integer rottenTomatoes;
        public final Integer metacritic;
        public final Double trakt;
        public final Double mal;

        public Ratings(Double imdb, Integer rottenTomatoes, Integer metacritic, Double trakt, Double mal) {
            this(imdb, null, null, rottenTomatoes, metacritic, trakt, mal);
        }

        public Ratings(Double imdb, Double tmdb, Integer rottenTomatoes, Integer metacritic, Double trakt, Double mal) {
            this(imdb, tmdb, null, rottenTomatoes, metacritic, trakt, mal);
        }

        public Ratings(Double imdb, Double tmdb, Double tvmaze, Integer rottenTomatoes, Integer metacritic, Double trakt, Double mal) {
            this.imdb = imdb;
            this.tmdb = tmdb;
            this.tvmaze = tvmaze;
            this.rottenTomatoes = rottenTomatoes;
            this.metacritic = metacritic;
            this.trakt = trakt;
            this.mal = mal;
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                if (imdb != null) obj.put("imdb", imdb);
                if (tmdb != null) obj.put("tmdb", tmdb);
                if (tvmaze != null) obj.put("tvmaze", tvmaze);
                if (rottenTomatoes != null) obj.put("rt", rottenTomatoes);
                if (metacritic != null) obj.put("mc", metacritic);
                if (trakt != null) obj.put("trakt", trakt);
                if (mal != null) obj.put("mal", mal);
                obj.put("ts", System.currentTimeMillis());
            } catch (Exception ignored) {}
            return obj;
        }

        public static Ratings fromJson(JSONObject obj) {
            if (obj == null) return null;
            Double imdb = obj.has("imdb") ? obj.optDouble("imdb") : null;
            Double tmdb = obj.has("tmdb") ? obj.optDouble("tmdb") : null;
            Double tvmaze = obj.has("tvmaze") ? obj.optDouble("tvmaze") : null;
            Integer rt = obj.has("rt") ? obj.optInt("rt") : null;
            Integer mc = obj.has("mc") ? obj.optInt("mc") : null;
            Double trakt = obj.has("trakt") ? obj.optDouble("trakt") : null;
            Double mal = obj.has("mal") ? obj.optDouble("mal") : null;
            return new Ratings(
                    imdb != null && imdb > 0 ? imdb : null,
                    tmdb != null && tmdb > 0 && tmdb <= 10.0 ? tmdb : null,
                    tvmaze != null && tvmaze > 0 && tvmaze <= 10.0 ? tvmaze : null,
                    rt != null && rt >= 0 && rt <= 100 ? rt : null,
                    mc != null && mc >= 0 && mc <= 100 ? mc : null,
                    trakt != null && trakt > 0 ? trakt : null,
                    mal != null && mal > 0 ? mal : null);
        }
    }

    private MultiRatingsProvider() {}

    /** Instant 0ms memory + disk cache lookup without blocking on the network. */
    public static Ratings getCached(Context context, String imdbId, boolean isShow, Double knownImdb, Double knownMal) {
        boolean hasImdb = imdbId != null && imdbId.matches("tt[0-9]+");
        String cacheKey = (hasImdb ? imdbId : "none") + ":" + (isShow ? "tv" : "mv");
        synchronized (CACHE) {
            if (CACHE.containsKey(cacheKey)) {
                Ratings hit = CACHE.get(cacheKey);
                if (hit != null) return mergeKnown(hit, knownImdb, knownMal);
            }
        }
        Ratings disk = readDiskCache(context, cacheKey);
        if (disk != null) {
            putMem(cacheKey, disk);
            return mergeKnown(disk, knownImdb, knownMal);
        }
        if (knownImdb != null || knownMal != null) {
            return new Ratings(knownImdb != null && knownImdb > 0 ? knownImdb : null, null, null, null,
                    knownMal != null && knownMal > 0 ? knownMal : null);
        }
        return null;
    }

    public static void clearCache(Context context) {
        synchronized (CACHE) {
            CACHE.clear();
        }
        if (context != null) {
            File dir = new File(context.getCacheDir(), "multi_ratings_v1");
            if (dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
        }
    }

    public static Ratings fetch(Context context, String imdbId, boolean isShow, Double knownImdb, Double knownMal) {
        boolean hasImdb = imdbId != null && imdbId.matches("tt[0-9]+");
        String cacheKey = (hasImdb ? imdbId : "none") + ":" + (isShow ? "tv" : "mv");
        Ratings cached = null;
        synchronized (CACHE) {
            if (CACHE.containsKey(cacheKey)) {
                cached = CACHE.get(cacheKey);
            }
        }
        if (cached == null) {
            cached = readDiskCache(context, cacheKey);
        }
        if (cached != null) {
            boolean updated = false;
            Double cTmdb = cached.tmdb;
            if (cTmdb == null && hasImdb && context != null && !ProviderKeys.get(context, ProviderKeys.TMDB).isBlank()) {
                cTmdb = fetchTmdbRating(context, imdbId, isShow);
                if (cTmdb != null) updated = true;
            }
            Double cTvmaze = cached.tvmaze;
            if (isShow && cTvmaze == null && hasImdb) {
                cTvmaze = fetchTvMazeRating(imdbId);
                if (cTvmaze != null) updated = true;
            }
            Double cTrakt = cached.trakt;
            if (cTrakt == null && hasImdb && context != null && !ProviderKeys.get(context, ProviderKeys.TRAKT_ID).isBlank()) {
                cTrakt = TraktSyncClient.fetchPublicRating(context, imdbId, isShow);
                if (cTrakt != null) updated = true;
            }
            if (updated) {
                cached = new Ratings(cached.imdb, cTmdb, cTvmaze, cached.rottenTomatoes, cached.metacritic, cTrakt, cached.mal);
                writeDiskCache(context, cacheKey, cached);
            }
            putMem(cacheKey, cached);
            return mergeKnown(cached, knownImdb, knownMal);
        }

        Double imdb = knownImdb != null && knownImdb > 0 ? knownImdb : null;
        Double tmdb = null;
        Double tvmaze = null;
        Integer rt = null;
        Integer mc = null;
        Double trakt = null;
        Double mal = knownMal != null && knownMal > 0 ? knownMal : null;

        if (hasImdb) {
            final Double[] resImdb = new Double[]{imdb};
            final Double[] resTmdb = new Double[1];
            final Double[] resTvmaze = new Double[1];
            final Double[] resTrakt = new Double[1];
            Thread t1 = resImdb[0] == null ? new Thread(() -> {
                try {
                    String type = isShow ? "series" : "movie";
                    JSONObject meta = new JSONObject(get("https://v3-cinemeta.strem.io/meta/" + type + "/" + imdbId + ".json"))
                            .optJSONObject("meta");
                    if (meta != null) {
                        double r = meta.optDouble("imdbRating", 0.0);
                        if (r > 0 && r <= 10.0) resImdb[0] = r;
                    }
                } catch (Exception ignored) {}
            }) : null;
            Thread t2 = (context != null && !ProviderKeys.get(context, ProviderKeys.TMDB).isBlank())
                    ? new Thread(() -> resTmdb[0] = fetchTmdbRating(context, imdbId, isShow)) : null;
            Thread t3 = isShow ? new Thread(() -> resTvmaze[0] = fetchTvMazeRating(imdbId)) : null;
            Thread t4 = (context != null && !ProviderKeys.get(context, ProviderKeys.TRAKT_ID).isBlank())
                    ? new Thread(() -> resTrakt[0] = TraktSyncClient.fetchPublicRating(context, imdbId, isShow)) : null;
            for (Thread t : new Thread[]{t1, t2, t3, t4}) if (t != null) t.start();
            for (Thread t : new Thread[]{t1, t2, t3, t4}) {
                if (t != null) {
                    try { t.join(5000); } catch (InterruptedException ignored) {}
                }
            }
            imdb = resImdb[0];
            tmdb = resTmdb[0];
            tvmaze = resTvmaze[0];
            trakt = resTrakt[0];
        }

        Ratings built = new Ratings(imdb, tmdb, tvmaze, rt, mc, trakt, mal);
        putMem(cacheKey, built);
        writeDiskCache(context, cacheKey, built);
        return mergeKnown(built, knownImdb, knownMal);
    }

    private static Ratings mergeKnown(Ratings r, Double knownImdb, Double knownMal) {
        Double imdb = r.imdb != null ? r.imdb : (knownImdb != null && knownImdb > 0 ? knownImdb : null);
        Double mal = r.mal != null ? r.mal : (knownMal != null && knownMal > 0 ? knownMal : null);
        return new Ratings(imdb, r.tmdb, r.tvmaze, r.rottenTomatoes, r.metacritic, r.trakt, mal);
    }

    private static Double fetchTvMazeRating(String imdbId) {
        try {
            String current = "https://api.tvmaze.com/lookup/shows?imdb=" + URLEncoder.encode(imdbId, "UTF-8");
            for (int hop = 0; hop < 3; hop++) {
                HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setConnectTimeout(3500);
                conn.setReadTimeout(4500);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "LocalCinema/1.0 (https://github.com/alnouri96/meedx)");
                try {
                    int code = conn.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String next = conn.getHeaderField("Location");
                        if (next == null || !next.startsWith("https://api.tvmaze.com/")) return null;
                        current = next;
                        continue;
                    }
                    if (code != 200) return null;
                    try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            if (out.size() + n > 512 * 1024) return null;
                            out.write(buf, 0, n);
                        }
                        JSONObject obj = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
                        JSONObject rating = obj.optJSONObject("rating");
                        if (rating != null) {
                            double avg = rating.optDouble("average", 0.0);
                            if (avg > 0 && avg <= 10.0) return avg;
                        }
                        return null;
                    }
                } finally {
                    conn.disconnect();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Double fetchTmdbRating(Context context, String imdbId, boolean isShow) {
        try {
            String key = ProviderKeys.get(context, ProviderKeys.TMDB);
            if (key == null || key.isBlank()) return null;
            String url = "https://api.themoviedb.org/3/find/" + URLEncoder.encode(imdbId, "UTF-8")
                    + "?api_key=" + URLEncoder.encode(key, "UTF-8") + "&external_source=imdb_id";
            JSONObject obj = new JSONObject(get(url));
            JSONArray list = obj.optJSONArray(isShow ? "tv_results" : "movie_results");
            if ((list == null || list.length() == 0)) {
                list = obj.optJSONArray(isShow ? "movie_results" : "tv_results");
            }
            if (list != null && list.length() > 0) {
                double vote = list.getJSONObject(0).optDouble("vote_average", 0.0);
                if (vote > 0 && vote <= 10.0) return vote;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void putMem(String k, Ratings v) {
        synchronized (CACHE) {
            if (CACHE.size() >= 96) CACHE.remove(CACHE.keySet().iterator().next());
            CACHE.put(k, v);
        }
    }

    private static Ratings readDiskCache(Context context, String key) {
        if (context == null) return null;
        try {
            File file = cacheFile(context, key);
            if (!file.isFile() || file.lastModified() < System.currentTimeMillis() - TTL_MS) return null;
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return Ratings.fromJson(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeDiskCache(Context context, String key, Ratings ratings) {
        if (context == null || ratings == null) return;
        try {
            File file = cacheFile(context, key);
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(ratings.toJson().toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private static File cacheFile(Context context, String key) {
        String safe = key.replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(new File(context.getCacheDir(), "multi_ratings_v1"), safe + ".json");
    }

    private static String get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(4500);
        conn.setReadTimeout(5500);
        conn.setRequestProperty("User-Agent", "LocalCinema/1.0 (https://github.com/alnouri96/meedx)");
        conn.setRequestProperty("Accept", "application/json");
        try {
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > 1024 * 1024) throw new IOException("Response too large");
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }
}

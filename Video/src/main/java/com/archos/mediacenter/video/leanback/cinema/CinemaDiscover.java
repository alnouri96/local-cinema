package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "New Trailers" and "Coming Soon" home sections.
 * Data comes from the free community-maintained TMDB API (the same source the app already uses for
 * metadata) and is refreshed at most once every 24 hours; the last good copy is served when offline.
 * All network access happens in {@link #load}, which must be called from a worker thread.
 */
final class CinemaDiscover {
    private static final long DAY = 86400000L;
    private static final String CACHE_FILE = "cinema-discover-v2.json";

    static final class Entry {
        final long id;
        final boolean show;
        final String title, overview, backdrop, poster, date, trailer;
        Entry(long id, boolean show, String title, String overview, String backdrop, String poster, String date, String trailer) {
            this.id = id; this.show = show; this.title = title; this.overview = overview;
            this.backdrop = backdrop; this.poster = poster; this.date = date; this.trailer = trailer;
        }
        /** Human readable premiere line such as "Premiered Oct 14" or "Releases Nov 20". */
        String dateLabel(boolean arabic) {
            try {
                Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date);
                String text = new SimpleDateFormat("MMM d", arabic ? new Locale("ar") : Locale.US).format(d);
                boolean past = d.getTime() <= System.currentTimeMillis();
                return arabic ? (past ? "عُرض " : "يُعرض ") + text : (past ? "Premiered " : "Releases ") + text;
            } catch (Exception e) { return ""; }
        }
    }

    static final class Result {
        final List<Entry> trailers, comingSoon;
        Result(List<Entry> trailers, List<Entry> comingSoon) { this.trailers = trailers; this.comingSoon = comingSoon; }
    }

    static Result load(Context c, String language) {
        File file = new File(c.getCacheDir(), CACHE_FILE);
        JSONObject cached = readFile(file);
        boolean sameLanguage = cached != null && language.equals(cached.optString("language"));
        if (sameLanguage && file.lastModified() > System.currentTimeMillis() - DAY) return parse(cached);
        try {
            JSONObject fresh = fetch(c, language);
            fresh.put("language", language);
            AtomicFile atomic = new AtomicFile(file);
            FileOutputStream out = null;
            try { out = atomic.startWrite(); out.write(fresh.toString().getBytes(StandardCharsets.UTF_8)); atomic.finishWrite(out); }
            catch (IOException e) { if (out != null) atomic.failWrite(out); }
            return parse(fresh);
        } catch (Exception failure) {
            return cached != null ? parse(cached) : new Result(new ArrayList<>(), new ArrayList<>());
        }
    }

    private static JSONObject fetch(Context c, String language) throws Exception {
        JSONObject root = new JSONObject();
        root.put("trailers", fetchTrailerPage(c,language,1));
        JSONArray soon = new JSONArray();
        JSONObject upcoming = get(c, "movie/upcoming", language, "&region=US");
        JSONArray list = upcoming.optJSONArray("results");
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        for (int n = 0; list != null && n < list.length() && soon.length() < 20; n++) {
            JSONObject item = list.getJSONObject(n);
            String poster = item.optString("poster_path", "");
            if (poster.isEmpty() || poster.equals("null") || item.optString("release_date", "").compareTo(today) < 0) continue;
            soon.put(entry(item, false, item.optString("backdrop_path", ""), null));
        }
        root.put("comingSoon", soon);
        return root;
    }

    private static JSONObject entry(JSONObject item, boolean show, String backdrop, String trailer) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", item.optLong("id"));
        o.put("show", show);
        o.put("title", item.optString(show ? "name" : "title", item.optString("title", item.optString("name"))));
        o.put("overview", item.optString("overview"));
        o.put("backdrop", backdrop == null || backdrop.equals("null") ? "" : backdrop);
        o.put("poster", item.optString("poster_path", ""));
        o.put("date", item.optString(show ? "first_air_date" : "release_date", item.optString("release_date", item.optString("first_air_date"))));
        if (trailer != null) o.put("trailer", trailer);
        return o;
    }

    static List<Entry> loadTrailerPage(Context c,String language,int page) throws Exception {
        return entries(fetchTrailerPage(c,language,page));
    }

    private static JSONArray fetchTrailerPage(Context c,String language,int page) throws Exception {
        JSONArray trailers = new JSONArray();
        JSONObject trending = get(c, "trending/all/day", language, "&page="+page);
        JSONArray results = trending.optJSONArray("results");
        for (int n = 0; results != null && n < results.length(); n++) {
            JSONObject item = results.getJSONObject(n);
            String type = item.optString("media_type");
            if (!type.equals("movie") && !type.equals("tv")) continue;
            long id = item.optLong("id");
            String backdrop = item.optString("backdrop_path", "");
            if (id <= 0 || backdrop.isEmpty() || backdrop.equals("null")) continue;
            JSONObject detail;
            try { detail = get(c, type + "/" + id, language, "&append_to_response=videos&include_video_language=" + Uri.encode(language) + ",en"); }
            catch (Exception skip) { continue; }
            JSONObject videos=detail.optJSONObject("videos");
            String url=CinemaArtwork.officialTrailer(videos==null?null:videos.optJSONArray("results"));
            if(url==null&&!"en".equals(language)){
                try{
                    JSONObject english=get(c,type+"/"+id,"en","&append_to_response=videos");
                    JSONObject englishVideos=english.optJSONObject("videos");
                    url=CinemaArtwork.officialTrailer(englishVideos==null?null:englishVideos.optJSONArray("results"));
                }catch(Exception ignored){/* Continue with the remaining titles. */}
            }
            String key=url==null?null:Uri.parse(url).getQueryParameter("v");
            if (key == null) continue;
            trailers.put(entry(item, type.equals("tv"), backdrop, key));
        }
        return trailers;
    }

    private static Result parse(JSONObject root) {
        return new Result(entries(root.optJSONArray("trailers")), entries(root.optJSONArray("comingSoon")));
    }

    private static List<Entry> entries(JSONArray array) {
        List<Entry> out = new ArrayList<>();
        for (int n = 0; array != null && n < array.length(); n++) {
            JSONObject o = array.optJSONObject(n);
            if (o == null) continue;
            out.add(new Entry(o.optLong("id"), o.optBoolean("show"), o.optString("title"), o.optString("overview"),
                    image("w1280", o.optString("backdrop")), image("w342", o.optString("poster")),
                    o.optString("date"), o.has("trailer") ? "https://www.youtube.com/watch?v=" + o.optString("trailer") : null));
        }
        return out;
    }

    private static String image(String size, String path) {
        return path == null || path.isEmpty() || path.equals("null") ? null : "https://image.tmdb.org/t/p/" + size + path;
    }

    private static JSONObject get(Context c, String path, String language, String extra) throws Exception {
        String url = "https://api.themoviedb.org/3/" + path + "?api_key=" + Uri.encode(com.meedx.providers.ProviderKeys.require(c, com.meedx.providers.ProviderKeys.TMDB))
                + "&language=" + Uri.encode(language) + extra;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("TMDB " + connection.getResponseCode());
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 2 * 1024 * 1024) throw new IOException("Response too large");
                    out.write(buffer, 0, n);
                }
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }

    private static JSONObject readFile(File file) {
        if (!file.isFile()) return null;
        try (InputStream in = new AtomicFile(file).openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) { return null; }
    }
}

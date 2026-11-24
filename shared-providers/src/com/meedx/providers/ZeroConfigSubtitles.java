package com.meedx.providers;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keyless open-source subtitle provider using OpenSubtitles Stremio v3 public catalog
 * and Wyzie public subtitle index. Zero API keys or accounts required.
 */
public final class ZeroConfigSubtitles {
    public static final class Item {
        public final String id;
        public final String release;
        public final String language;
        public final String url;
        public final String source;

        public Item(String id, String release, String language, String url, String source) {
            this.id = id;
            this.release = release;
            this.language = language;
            this.url = url;
            this.source = source;
        }
    }

    private ZeroConfigSubtitles() {}

    public static List<Item> search(String imdbId, boolean isSeries, int season, int episode, String preferredLanguage) {
        List<Item> out = new ArrayList<>();
        if (imdbId == null || !imdbId.matches("tt[0-9]+")) return out;
        String wantedIso = normalizeLangCode(preferredLanguage);
        Map<String, Item> dedup = new LinkedHashMap<>();

        // 1. OpenSubtitles v3 Stremio public endpoint (zero API key)
        try {
            String mediaId = isSeries && season > 0 && episode > 0
                    ? imdbId + ":" + season + ":" + episode
                    : imdbId;
            String type = isSeries ? "series" : "movie";
            String url = "https://opensubtitles-v3.strem.io/subtitles/" + type + "/" + Uri.encode(mediaId) + ".json";
            JSONObject obj = new JSONObject(get(url));
            JSONArray subs = obj.optJSONArray("subtitles");
            if (subs != null) {
                for (int i = 0; i < subs.length(); i++) {
                    JSONObject s = subs.optJSONObject(i);
                    if (s == null) continue;
                    String subUrl = s.optString("url", "").trim();
                    if (!subUrl.startsWith("https://") && !subUrl.startsWith("http://")) continue;
                    String lang = s.optString("lang", "").trim().toLowerCase(Locale.ROOT);
                    if (!wantedIso.isEmpty() && !langMatches(lang, wantedIso, preferredLanguage)) continue;
                    String fileName = s.optString("SubFileName", s.optString("id", "OpenSubtitles Free"));
                    String id = s.optString("id", Integer.toString(i));
                    dedup.putIfAbsent(subUrl, new Item(id, fileName, lang.isEmpty() ? wantedIso : lang, subUrl, "OpenSubtitles Free"));
                    if (dedup.size() >= 30) break;
                }
            }
        } catch (Exception ignored) {}

        // 2. OpenSubtitles Legacy REST keyless fallback (replaces discontinued Wyzie mirror)
        if (dedup.isEmpty()) {
            try {
                StringBuilder u = new StringBuilder("https://rest.opensubtitles.org/search");
                if (isSeries && episode > 0) u.append("/episode-").append(episode);
                u.append("/imdbid-").append(imdbId.substring(2));
                if (isSeries && season > 0) u.append("/season-").append(season);
                if (!wantedIso.isEmpty()) u.append("/sublanguageid-").append(Uri.encode(wantedIso));
                HttpURLConnection conn = (HttpURLConnection) new URL(u.toString()).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(9000);
                conn.setRequestProperty("User-Agent", "TemporaryUserAgent");
                try {
                    if (conn.getResponseCode() == 200) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        try (InputStream in = conn.getInputStream()) {
                            byte[] b = new byte[8192];
                            int n;
                            while ((n = in.read(b)) != -1 && bos.size() < 2 * 1024 * 1024) bos.write(b, 0, n);
                        }
                        String expectedImdbDigits = imdbId.substring(2).replaceFirst("^0+", "");
                        JSONArray arr = new JSONArray(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject s = arr.optJSONObject(i);
                            if (s == null) continue;
                            String rowImdb = s.optString("IDMovieImdb", "").trim().replaceFirst("^0+", "");
                            if (!rowImdb.isEmpty() && !rowImdb.equals(expectedImdbDigits)) continue;
                            String subUrl = s.optString("SubDownloadLink", "").trim();
                            if (!subUrl.startsWith("http")) continue;
                            String lang = s.optString("SubLanguageID", wantedIso).trim().toLowerCase(Locale.ROOT);
                            String rel = s.optString("SubFileName", "OpenSubtitles Free");
                            String id = s.optString("IDSubtitleFile", Integer.toString(i));
                            dedup.putIfAbsent(subUrl, new Item(id, rel, lang, subUrl, "OpenSubtitles Free"));
                            if (dedup.size() >= 25) break;
                        }
                    }
                } finally {
                    conn.disconnect();
                }
            } catch (Exception ignored) {}
        }

        out.addAll(dedup.values());
        return out;
    }

    public static byte[] download(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(9000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "LocalCinema/1.0 (https://github.com/alnouri96/local-cinema)");
        try {
            if (conn.getResponseCode() != 200) throw new IOException("Subtitle HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > 4 * 1024 * 1024) throw new IOException("Subtitle too large");
                    out.write(buf, 0, n);
                }
                byte[] bytes = out.toByteArray();
                if (bytes.length >= 2 && bytes[0] == (byte) 0x1f && bytes[1] == (byte) 0x8b) {
                    try (java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes));
                         ByteArrayOutputStream unz = new ByteArrayOutputStream()) {
                        while ((n = gz.read(buf)) != -1 && unz.size() < 4 * 1024 * 1024) unz.write(buf, 0, n);
                        return unz.toByteArray();
                    }
                }
                return bytes;
            }
        } finally {
            conn.disconnect();
        }
    }

    private static boolean langMatches(String candidate, String wantedIso, String wantedFull) {
        if (wantedIso.isEmpty()) return true;
        if (candidate.equals(wantedIso) || candidate.equals(toTwoLetter(wantedIso))) return true;
        if (wantedFull != null && !wantedFull.isBlank() && candidate.contains(wantedFull.toLowerCase(Locale.ROOT))) return true;
        if ("ara".equals(wantedIso)) return candidate.startsWith("ar");
        if ("eng".equals(wantedIso)) return candidate.startsWith("en");
        if ("fre".equals(wantedIso)) return candidate.startsWith("fr") || candidate.equals("fra");
        return false;
    }

    private static String normalizeLangCode(String pref) {
        if (pref == null || pref.isBlank()) return "";
        return switch (pref.trim().toLowerCase(Locale.ROOT)) {
            case "ar", "ara", "arabic" -> "ara";
            case "en", "eng", "english" -> "eng";
            case "fr", "fre", "fra", "french" -> "fre";
            case "es", "spa", "spanish" -> "spa";
            case "de", "ger", "deu", "german" -> "ger";
            case "tr", "tur", "turkish" -> "tur";
            case "fa", "per", "fas", "persian" -> "per";
            case "ku", "kur", "kurdish" -> "kur";
            case "zh", "chi", "zho", "chinese" -> "chi";
            case "ja", "jpn", "japanese" -> "jpn";
            case "ko", "kor", "korean" -> "kor";
            default -> pref.trim().toLowerCase(Locale.ROOT);
        };
    }

    private static String toTwoLetter(String iso3) {
        return switch (iso3) {
            case "ara" -> "ar";
            case "eng" -> "en";
            case "fre" -> "fr";
            case "spa" -> "es";
            case "ger" -> "de";
            case "tur" -> "tr";
            case "per" -> "fa";
            case "kur" -> "ku";
            case "chi" -> "zh";
            case "jpn" -> "ja";
            case "kor" -> "ko";
            default -> iso3.length() >= 2 ? iso3.substring(0, 2) : iso3;
        };
    }

    private static String get(String url) throws IOException {
        return new String(download(url), StandardCharsets.UTF_8);
    }
}

package com.archos.mediacenter.video.leanback.cinema;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class OpenSubtitleSearch {
    record Match(String id, String name, String language) {}
    private OpenSubtitleSearch() {}

    private static String languageCode(String language) {
        if (language.matches("[a-z]{2}")) return language;
        for (String code : java.util.Locale.getISOLanguages()) {
            if (new java.util.Locale(code).getDisplayLanguage(java.util.Locale.ENGLISH).equalsIgnoreCase(language)) {
                return code;
            }
        }
        return "";
    }

    static List<Match> search(String key, String title, String imdb, int season, int episode, String language)
            throws Exception {
        Uri.Builder uri = Uri.parse("https://api.opensubtitles.com/api/v1/subtitles").buildUpon();
        if (imdb != null && imdb.matches("tt[0-9]+")) {
            uri.appendQueryParameter(season >= 0 ? "parent_imdb_id" : "imdb_id", imdb.substring(2));
        } else {
            uri.appendQueryParameter("query", title);
        }
        if (season >= 0) uri.appendQueryParameter("season_number", Integer.toString(season));
        if (episode >= 0) uri.appendQueryParameter("episode_number", Integer.toString(episode));
        String code = languageCode(language);
        if (!code.isEmpty()) uri.appendQueryParameter("languages", code);
        uri.appendQueryParameter("order_by", "download_count").appendQueryParameter("order_direction", "desc");

        HttpURLConnection connection = (HttpURLConnection) new URL(uri.build().toString()).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(6000);
        connection.setRequestProperty("Api-Key", key);
        connection.setRequestProperty("User-Agent", "LocalCinema v1.0.0-beta.2");
        try (var input = connection.getInputStream(); var body = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192];
            int len;
            while ((len = input.read(block)) != -1) {
                if (body.size() + len > 1024 * 1024) throw new IOException("Subtitle response too large");
                body.write(block, 0, len);
            }
            JSONArray entries = new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8)).getJSONArray("data");
            List<Match> matches = new ArrayList<>();
            for (int i = 0; i < entries.length() && matches.size() < 24; i++) {
                JSONObject attributes = entries.getJSONObject(i).optJSONObject("attributes");
                if (attributes == null) continue;
                JSONObject feature = attributes.optJSONObject("feature_details");
                if (episode >= 0 && (feature == null || feature.optInt("episode_number", -1) != episode
                        || feature.optInt("season_number", -1) != season)) continue;
                JSONArray files = attributes.optJSONArray("files");
                if (files == null) continue;
                for (int n = 0; n < files.length() && matches.size() < 24; n++) {
                    JSONObject file = files.getJSONObject(n);
                    String id = file.optString("file_id");
                    if (!id.matches("[0-9]+")) continue;
                    String name = file.optString("file_name", id);
                    matches.add(new Match(id, name, attributes.optString("language")));
                }
            }
            matches.sort(Comparator.comparing(match -> !"ar".equals(match.language())));
            return matches;
        } finally { connection.disconnect(); }
    }
}

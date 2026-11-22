package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.meedx.providers.LiveTvPreferences;
import com.meedx.providers.LiveChannelFavorites;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CinemaLivePlaylists {
    private static final String IPTV_ORG = "https://iptv-org.github.io/iptv/";
    private static final String ARABIC_URL = IPTV_ORG + "languages/ara.m3u";
    static final String CHANNEL_PREFERENCES = LiveTvPreferences.STORE;
    private static final String KEY = "playlists";
    private static final int MAX_LINES = 100_000;
    private static final int MAX_CHARS = 12 * 1024 * 1024;
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");

    record Source(String name, String url, boolean enabled) {}
    record Channel(String title, String group, String country, String logo, String url, boolean hls,
                   String userAgent, String referer, String favoriteKey) {}

    static final long IRAQ_REFRESH_INTERVAL = java.util.concurrent.TimeUnit.MINUTES.toMillis(30);
    static final String IRAQ_SOURCE_URL =
            "https://nexonic-technologies.github.io/nexonic-iptv/playlists/countries/iq.m3u";
    private static final String IRAQ_ENABLED = "nexonic_iraq_enabled";
    private static final String IRAQ_AUTO_REFRESH = "nexonic_iraq_auto_refresh";

    record IraqSnapshot(List<Channel> channels, long updatedAt, long attemptedAt, boolean failed) {}

    static boolean iraqEnabled(Context context) {
        return LiveTvPreferences.store(context).getBoolean(IRAQ_ENABLED, true);
    }

    static void setIraqEnabled(Context context, boolean enabled) {
        LiveTvPreferences.store(context).edit().putBoolean(IRAQ_ENABLED, enabled).apply();
    }

    static boolean iraqAutoRefresh(Context context) {
        return LiveTvPreferences.store(context).getBoolean(IRAQ_AUTO_REFRESH, true);
    }

    static void setIraqAutoRefresh(Context context, boolean enabled) {
        LiveTvPreferences.store(context).edit().putBoolean(IRAQ_AUTO_REFRESH, enabled).apply();
    }

    private static SharedPreferences iraqCache(Context context) {
        return context.getSharedPreferences("cinema_nexonic_iraq_cache", Context.MODE_PRIVATE);
    }

    static IraqSnapshot cachedIraq(Context context) {
        var cache = iraqCache(context);
        var channels = new ArrayList<Channel>();
        try {
            var entries = new JSONArray(cache.getString("channels", "[]"));
            for (int i = 0; i < entries.length(); i++) {
                var row = entries.getJSONArray(i);
                channels.add(new Channel(row.getString(0), row.getString(1), "IQ",
                        row.getString(2), row.getString(3), row.getBoolean(4), row.getString(5),
                        row.getString(6), row.getString(7)));
            }
        } catch (org.json.JSONException ignored) {
            channels.clear();
        }
        return new IraqSnapshot(List.copyOf(channels), channels.isEmpty() ? 0 : cache.getLong("updated", 0),
                cache.getLong("attempted", 0), cache.getBoolean("failed", false));
    }

    static IraqSnapshot refreshIraq(Context context) {
        var cache = iraqCache(context);
        long attempted = System.currentTimeMillis();
        try {
            var loaded = load(new Source(context.getString(
                    com.archos.mediacenter.video.R.string.addon_live_iraq_source), IRAQ_SOURCE_URL, true));
            if (!loaded.isEmpty() && loaded.stream().noneMatch(channel -> IRAQ_SOURCE_URL.equals(channel.url()))) {
                var entries = new JSONArray();
                for (var channel : loaded) {
                    entries.put(new JSONArray(List.of(channel.title(), channel.group(), channel.logo(), channel.url(),
                            channel.hls(), channel.userAgent(), channel.referer(), channel.favoriteKey())));
                }
                cache.edit().putString("channels", entries.toString()).putLong("updated", System.currentTimeMillis())
                        .putLong("attempted", attempted).putBoolean("failed", false).apply();
                return cachedIraq(context);
            }
        } catch (java.io.InterruptedIOException failure) {
            if (Thread.currentThread().isInterrupted()) return cachedIraq(context);
        } catch (IOException | IllegalArgumentException ignored) {}
        cache.edit().putLong("attempted", attempted).putBoolean("failed", true).apply();
        return cachedIraq(context);
    }

    private CinemaLivePlaylists() {}

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("cinema_tv_playlists", Context.MODE_PRIVATE);
    }

    static List<Source> sources(Context context, boolean custom) {
        String country = LiveTvPreferences.country(context);
        var publicSettings = LiveTvPreferences.store(context);
        String countryLabel = LiveTvPreferences.countryName(context, country);
        String selectedUrl = countryUrl(country);
        if (!custom) return List.of(new Source(context.getString(com.archos.mediacenter.video.R.string.addon_live_country_source, countryLabel),
                selectedUrl, publicSettings.getBoolean("country_enabled",
                        preferences(context).getBoolean("public_enabled", true))));
        var sources = new ArrayList<Source>();
        sources.add(new Source(context.getString(com.archos.mediacenter.video.R.string.addon_live_country_source, countryLabel),
                selectedUrl, publicSettings.getBoolean("iptv_country_enabled", false)));
        sources.add(new Source(context.getString(com.archos.mediacenter.video.R.string.addon_live_arabic_playlist),
                ARABIC_URL, publicSettings.getBoolean("iptv_arabic_enabled", false)));
        try {
            JSONArray entries = new JSONArray(preferences(context).getString(KEY, "[]"));
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                String url = entry.getString("url");
                if (!builtIn(context, url)) sources.add(new Source(entry.getString("name"), url, entry.optBoolean("enabled", true)));
            }
        } catch (org.json.JSONException ignored) {}
        return sources;
    }

    static void save(Context context, boolean custom, List<Source> sources) {
        var publicSettings = LiveTvPreferences.store(context).edit();
        if (!custom) {
            publicSettings.putBoolean("country_enabled", sources.get(0).enabled()).apply();
            return;
        }
        JSONArray entries = new JSONArray();
        for (Source source : sources) {
            if (builtIn(context, source.url())) {
                publicSettings.putBoolean(ARABIC_URL.equals(source.url()) ? "iptv_arabic_enabled" : "iptv_country_enabled",
                        source.enabled());
                continue;
            }
            try {
                entries.put(new JSONObject().put("name", source.name()).put("url", source.url())
                        .put("enabled", source.enabled()));
            } catch (org.json.JSONException ignored) {}
        }
        publicSettings.apply();
        preferences(context).edit().putString(KEY, entries.toString()).apply();
    }

    private static String countryUrl(String code) {
        return IPTV_ORG + "countries/" + code.toLowerCase(Locale.ROOT) + ".m3u";
    }

    static boolean builtIn(Context context, String url) {
        return ARABIC_URL.equals(url) || countryUrl(LiveTvPreferences.country(context)).equals(url);
    }

    static String playlistUrl(String input) {
        try {
            URI uri = URI.create(input.trim());
            if (uri.getHost() != null && uri.getUserInfo() == null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                return uri.toASCIIString();
            }
        } catch (IllegalArgumentException ignored) {}
        return "";
    }

    static String xtreamUrl(String address, String username, String password) {
        String host = playlistUrl(address.contains("://") ? address : "https://" + address);
        if (host.isBlank() || username.isBlank() || password.isBlank()) return "";
        return Uri.parse(host).buildUpon().clearQuery().fragment(null).encodedPath("/get.php")
                .appendQueryParameter("username", username).appendQueryParameter("password", password)
                .appendQueryParameter("type", "m3u_plus").appendQueryParameter("output", "m3u8")
                .build().toString();
    }

    static List<Channel> load(Source source) throws IOException {
        var connection = (HttpURLConnection) URI.create(source.url()).toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(6500);
        connection.setRequestProperty("Accept", "application/vnd.apple.mpegurl, audio/x-mpegurl, */*");
        long deadline = android.os.SystemClock.elapsedRealtime() + 18_000;
        try (var reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null || !header.replace("\uFEFF", "").strip().startsWith("#EXTM3U")) return List.of();
            var channels = new LinkedHashMap<String, Channel>();
            String line;
            String metadata = "";
            String userAgent = "";
            String referer = "";
            int lines = 0;
            int chars = 0;
            URI base = URI.create(connection.getURL().toString());
            while ((line = reader.readLine()) != null) {
                chars += line.length();
                if (++lines > MAX_LINES || chars > MAX_CHARS) break;
                if (Thread.currentThread().isInterrupted() || android.os.SystemClock.elapsedRealtime() > deadline) {
                    throw new java.io.InterruptedIOException();
                }
                line = line.replace("\uFEFF", "").trim();
                if (line.startsWith("#EXT-X-")) {
                    return List.of(new Channel(source.name(), "", "", "", source.url(), true, "", "",
                            LiveChannelFavorites.key("", source.url())));
                }
                if (line.startsWith("#EXTINF:")) {
                    metadata = line;
                    userAgent = "";
                    referer = "";
                } else if (line.startsWith("#EXTVLCOPT:http-user-agent=")) {
                    userAgent = line.substring("#EXTVLCOPT:http-user-agent=".length());
                } else if (line.startsWith("#EXTVLCOPT:http-referrer=")) {
                    referer = line.substring("#EXTVLCOPT:http-referrer=".length());
                } else if (!line.startsWith("#") && !line.isBlank()) {
                    String url = resolve(base, line);
                    if (!url.isBlank()) {
                        Channel channel = channel(source.name(), base, metadata, url, userAgent, referer);
                        channels.putIfAbsent(channel.url(), channel);
                    }
                    metadata = "";
                    userAgent = "";
                    referer = "";
                }
            }
            return List.copyOf(channels.values());
        } finally {
            connection.disconnect();
        }
    }

    private static Channel channel(String fallbackTitle, URI base, String metadata, String url,
                                   String userAgent, String referer) {
        String title = fallbackTitle;
        String group = "";
        String country = "";
        String id = "";
        String logo = "";
        Matcher fields = ATTRIBUTE.matcher(metadata);
        int nameStart = 0;
        while (fields.find()) {
            nameStart = fields.end();
            String value = fields.group(2);
            switch (fields.group(1).toLowerCase(Locale.ROOT)) {
                case "tvg-id" -> id = value;
                case "tvg-name" -> title = value;
                case "group-title" -> group = value;
                case "tvg-country" -> country = value;
                case "tvg-logo" -> logo = resolve(base, value);
                case "http-user-agent" -> userAgent = value;
                case "http-referrer", "http-referer" -> referer = value;
                default -> {}
            }
        }
        int comma = metadata.indexOf(',', nameStart);
        if (comma >= 0 && !metadata.substring(comma + 1).isBlank()) title = metadata.substring(comma + 1).trim();
        if (country.isBlank()) {
            String channelId = id.split("@", 2)[0];
            int dot = channelId.lastIndexOf('.');
            if (dot >= 0) country = channelId.substring(dot + 1);
        }
        country = country.trim().toUpperCase(Locale.ROOT);
        boolean hls = Uri.parse(url).getPath() != null
                && Uri.parse(url).getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8");
        return new Channel(title, group, country, logo, url, hls, headerValue(userAgent), playlistUrl(referer),
                LiveChannelFavorites.key(id, url));
    }

    private static String headerValue(String value) {
        return value.length() <= 512 && !value.contains("\r") && !value.contains("\n") ? value : "";
    }

    private static String resolve(URI base, String value) {
        if (value.isBlank()) return "";
        try {
            return playlistUrl(base.resolve(value).toString());
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }
}

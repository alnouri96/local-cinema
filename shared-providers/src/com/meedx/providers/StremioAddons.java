package com.meedx.providers;

import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stremio / Nuvio compatible external addon manager and stream resolver. */
public final class StremioAddons {
    private static final String PREFS = "meedx_stremio_addons";
    private static final String KEY_ADDONS = "addons_json";
    private static final Pattern MAGNET_HASH = Pattern.compile("(?i)(?:[?&])xt=urn:btih:(?:[a-f0-9]{40}|[a-z2-7]{32})(?:&|$)");
    private static final Pattern SEED_COUNT = Pattern.compile(
            "(?i)(?:\\b(?:seeders?|seeds?)\\s*[:=]?\\s*|\\bs\\s*[:=]\\s*|👤\\s*)([0-9]+(?:[,.][0-9]{3})*)"
                    + "|([0-9]+(?:[,.][0-9]{3})*)\\s+(?:seeders?|seeds?)\\b");
    private static final Pattern DISPLAY_SIZE = Pattern.compile("(?i)\\b([0-9]+(?:[.,][0-9]+)?)\\s*(kib|mib|gib|tib|kb|mb|gb|tb)\\b");
    private static final int STREAMS = 1;
    private static final int SUBTITLES = 2;
    private static final int CATALOGS = 4;
    private static final int META = 8;

    public record BuiltIn(String id, String name, String manifestUrl, String logo,
                          String artwork, int accent, int resources) {}

    private static final List<BuiltIn> BUILT_INS = List.of(
            new BuiltIn("com.esmaeli.persianstremio", "Persian Stremio", "https://pstream.ir/manifest.json",
                    "https://raw.githubusercontent.com/MrMohebi/stremio-ir-providers/refs/heads/master/logo.png",
                    "ic_addon_persian_stremio", 0xff244671, STREAMS | CATALOGS | META | SUBTITLES),
            new BuiltIn("com.stremio.torrentio.addon", "Torrentio", "https://torrentio.strem.fun/manifest.json",
                    "https://torrentio.strem.fun/images/logo_v1.png",
                    "ic_addon_torrentio", 0xff2d2350, STREAMS),
            new BuiltIn("com.keopps.peerflix", "Peerflix", "https://peerflix.mov/manifest.json",
                    "https://peerflix.mov/static/logo.png",
                    "ic_addon_peerflix", 0xff1e3a4c, STREAMS),
            new BuiltIn("org.stremio.pubdomainmovies", "Public Domain Movies",
                    "https://caching.stremio.net/publicdomainmovies.now.sh/manifest.json",
                    "https://johnserv531.github.io/gh-images/1586344884.jpg",
                    "ic_addon_public_domain", 0xff473422, STREAMS | CATALOGS),
            new BuiltIn("com.linvo.cinemeta", "Cinemeta", "https://v3-cinemeta.strem.io/manifest.json",
                    "https://raw.githubusercontent.com/Stremio/stremio-web/development/assets/images/stremio_symbol.png",
                    "ic_addon_cinemeta", 0xff3b275c, CATALOGS | META),
            new BuiltIn("anime.kitsu", "Anime Kitsu", "https://anime-kitsu.strem.fun/manifest.json",
                    "https://i.imgur.com/7N6XGoO.png",
                    "ic_addon_kitsu", 0xff5a2c24, CATALOGS | META),
            new BuiltIn("pw.ers.netflix-catalog", "Streaming Catalogs",
                    "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/manifest.json",
                    "https://play-lh.googleusercontent.com/TBRwjS_qfJCSj1m7zZB93FnpJM5fSpMA_wUlFDLxWAb45T9RmwBvQd5cWR5viJJOhkI",
                    "ic_addon_streaming_catalogs", 0xff4c1d24, CATALOGS),
            new BuiltIn("org.stremio.opensubtitlesv3", "OpenSubtitles v3",
                    "https://opensubtitles-v3.strem.io/manifest.json",
                    "https://www.strem.io/images/addons/opensubtitles-logo.png",
                    "ic_addon_opensubtitles", 0xff1f402e, SUBTITLES));

    public static BuiltIn builtIn(Addon addon) {
        return BUILT_INS.stream().filter(item -> item.manifestUrl().equalsIgnoreCase(addon.manifestUrl))
                .findFirst().orElse(null);
    }

    private static boolean hasBuiltIns(Context context) {
        return "com.meedx.localcinema.tv".equals(context.getPackageName());
    }
    private static final String CURATED_URL = "https://meedx.pages.dev/api/addons.json";

    public static final class CuratedAddon {
        public final String name;
        public final String description;
        public final String manifestUrl;
        public final String category;

        public CuratedAddon(String name, String description, String manifestUrl, String category) {
            this.name = name;
            this.description = description;
            this.manifestUrl = manifestUrl;
            this.category = category;
        }
    }
    private static final Pattern QUALITY_PATTERN = Pattern.compile(
            "(?i)\\b(2160p|4k|uhd|1080p|720p|480p|hdr10\\+?|hdr|dolby\\s*vision|dovi|dv|hevc|h\\.?265|x265|x264|remux|blu[- ]?ray|web[- ]?dl|web[- ]?rip|mkv|mp4|av1)\\b");

    public static final class Addon {
        public final String manifestUrl;
        public final String baseUrl;
        public final String id;
        public final String name;
        public final String description;
        public final String version;
        public final boolean enabled;
        public final long updatedAt;
        public final int resources;
        public final String logo;

        public Addon(String manifestUrl, String baseUrl, String id, String name, String description,
                     String version, boolean enabled, long updatedAt) {
            this(manifestUrl, baseUrl, id, name, description, version, enabled, updatedAt, -1);
        }

        Addon(String manifestUrl, String baseUrl, String id, String name, String description,
              String version, boolean enabled, long updatedAt, int resources) {
            this(manifestUrl, baseUrl, id, name, description, version, enabled, updatedAt, resources, "");
        }

        Addon(String manifestUrl, String baseUrl, String id, String name, String description,
              String version, boolean enabled, long updatedAt, int resources, String logo) {
            this.resources = resources;
            this.logo = logo;
            this.manifestUrl = manifestUrl;
            this.baseUrl = baseUrl;
            this.id = id;
            this.name = name;
            this.description = description;
            this.version = version;
            this.enabled = enabled;
            this.updatedAt = updatedAt;
        }
    }

    public static final class Stream {
        public final String addonName;
        public final String title;
        public final String quality;
        public final String url;
        public final String format;
        public final int fileIndex;
        public final long sizeBytes;
        public final int seeds;
        public final List<String> languages;

        public Stream(String addonName, String title, String quality, String url, String format) {
            this(addonName, title, quality, url, format, -1, 0, -1);
        }

        Stream(String addonName, String title, String quality, String url, String format, int fileIndex, long sizeBytes, int seeds) {
            this(addonName, title, quality, url, format, fileIndex, sizeBytes, seeds, List.of());
        }

        Stream(String addonName, String title, String quality, String url, String format, int fileIndex,
               long sizeBytes, int seeds, List<String> languages) {
            this.languages = languages;
            this.seeds = seeds;
            this.fileIndex = fileIndex;
            this.sizeBytes = sizeBytes;
            this.addonName = addonName;
            this.title = title;
            this.quality = quality;
            this.url = url;
            this.format = format;
        }

        public boolean torrent() { return "torrent".equals(format); }

        public String displayLabel() {
            return displayLabel(null);
        }

        public String displayLabel(Context context) {
            StringBuilder sb = new StringBuilder();
            if (addonName != null && !addonName.isBlank()) sb.append("[").append(addonName).append("] ");
            if (quality != null && !quality.isBlank()) sb.append(quality).append(" · ");
            if (sizeBytes > 0 && context != null) {
                sb.append(android.text.format.Formatter.formatShortFileSize(context, sizeBytes)).append(" · ");
            }
            if (title != null && !title.isBlank()) sb.append(title);
            return sb.toString();
        }
    }

    private static final int REQUEST_BUDGET_MS = 4500;
    private static final ThreadLocal<Long> providerDeadline = new ThreadLocal<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> streamDenied = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> unreachable = new java.util.concurrent.ConcurrentHashMap<>();

    private StremioAddons() {}

    private static long elapsedMillis() { return System.nanoTime() / 1_000_000L; }

    private static <T> void parallel(List<java.util.concurrent.Callable<T>> tasks, java.util.function.Consumer<T> consume) {
        Long inherited = providerDeadline.get();
        long deadline = elapsedMillis() + REQUEST_BUDGET_MS;
        parallel(tasks, consume, inherited == null ? deadline : Math.min(deadline, inherited));
    }

    private static <T> void parallel(List<java.util.concurrent.Callable<T>> tasks, java.util.function.Consumer<T> consume,
                                     long deadline) {
        if (tasks.isEmpty() || elapsedMillis() >= deadline) return;
        int remaining = tasks.size();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(remaining);
        var completed = new java.util.concurrent.ExecutorCompletionService<T>(pool);
        try {
            for (var task : tasks) completed.submit(() -> {
                providerDeadline.set(deadline);
                try { return task.call(); }
                finally { providerDeadline.remove(); }
            });
            while (remaining-- > 0) {
                var result = completed.poll(Math.max(0, deadline - elapsedMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
                if (result == null) break;
                try { consume.accept(result.get()); }
                catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException ignored) {}
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String normalizeManifestUrl(String rawUrl) throws IOException {
        if (rawUrl == null || rawUrl.isBlank()) throw new IOException("Empty addon URL");
        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("stremio://")) {
            trimmed = "https://" + trimmed.substring("stremio://".length());
        }
        Uri uri = Uri.parse(trimmed);
        String scheme = uri.getScheme();
        if ((!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IOException("Invalid addon URL");
        }
        String path = uri.getEncodedPath();
        path = path == null ? "" : path.replaceFirst("/+$", "");
        if (!path.endsWith("/manifest.json")) path += "/manifest.json";
        return uri.buildUpon().encodedPath(path).fragment(null).build().toString();
    }

    public static String baseUrlFromManifest(String manifestUrl) {
        Uri uri = Uri.parse(manifestUrl);
        String path = uri.getEncodedPath();
        if (path == null) return manifestUrl;
        return uri.buildUpon().encodedPath(path.replaceFirst("/manifest\\.json$", ""))
                .clearQuery().fragment(null).build().toString();
    }

    private static String resourceUrl(Addon addon, String resource) {
        String query = Uri.parse(addon.manifestUrl).getEncodedQuery();
        return addon.baseUrl + resource + (query == null || query.isEmpty() ? "" : "?" + query);
    }

    public static Addon fetchAndAdd(Context context, String rawUrl) throws Exception {
        Addon addon = readManifest(context, rawUrl);
        saveAddon(context, addon);
        return addon;
    }

    private static Addon readManifest(Context context, String rawUrl) throws Exception {
        String manifestUrl = normalizeManifestUrl(rawUrl);
        JSONObject manifest = new JSONObject(fetchText(manifestUrl));
        String id = manifest.optString("id", manifestUrl);
        String name = manifest.optString("name", Uri.parse(manifestUrl).getHost());
        String description = localizedDescription(manifest.opt("description"), context);
        String version = manifest.optString("version", "1.0.0");
        if (name == null || name.isBlank()) name = "External Addon";
        manifestRules.put(manifestUrl, manifest);
        return new Addon(manifestUrl, baseUrlFromManifest(manifestUrl), id, name, description,
                version, true, System.currentTimeMillis(), resourceMask(manifest), readLogo(manifestUrl, manifest));
    }

    public static void replaceManifest(Context context, Addon original, String url) throws Exception {
        Addon replacement = readManifest(context, url);
        synchronized (StremioAddons.class) {
            var installed = new ArrayList<>(list(context));
            int index = -1;
            for (int i = 0; i < installed.size(); i++) {
                Addon saved = installed.get(i);
                if (saved.manifestUrl.equals(original.manifestUrl)) index = i;
                else if (saved.manifestUrl.equals(replacement.manifestUrl)) throw new IOException("Duplicate manifest");
            }
            if (index < 0) throw new IOException("Add-on removed");
            installed.set(index, new Addon(replacement.manifestUrl, replacement.baseUrl,
                    replacement.id, replacement.name, replacement.description, replacement.version,
                    installed.get(index).enabled, System.currentTimeMillis(), replacement.resources, replacement.logo));
            persist(context, installed);
        }
    }

    public static String configurationPage(Addon addon) {
        return Uri.parse(addon.manifestUrl).buildUpon().encodedPath("/configure")
                .clearQuery().fragment(null).build().toString();
    }


    public static synchronized void saveAddon(Context context, Addon addon) {
        List<Addon> current = new ArrayList<>(list(context));
        boolean replaced = false;
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).manifestUrl.equalsIgnoreCase(addon.manifestUrl)) {
                current.set(i, addon);
                replaced = true;
                break;
            }
        }
        if (!replaced) current.add(addon);
        persist(context, current);
    }

    public static synchronized List<Addon> list(Context context) {
        if (context == null) return Collections.emptyList();
        String raw = prefs(context).getString(KEY_ADDONS, "[]");
        List<Addon> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String mUrl = o.optString("manifestUrl", "");
                if (mUrl.isBlank()) continue;
                int mask = o.optInt("resources_mask", -1);
                String logo = o.optString("logo", "");
                BuiltIn bi = BUILT_INS.stream().filter(b -> b.manifestUrl().equalsIgnoreCase(mUrl)).findFirst().orElse(null);
                if (bi != null) {
                    if (mask < 0) mask = bi.resources();
                    if (logo.isBlank()) logo = bi.logo();
                }
                out.add(new Addon(
                        mUrl,
                        baseUrlFromManifest(mUrl),
                        o.optString("id", mUrl),
                        o.optString("name", "Addon"),
                        o.optString("description", ""),
                        o.optString("version", ""),
                        o.optBoolean("enabled", true),
                        o.optLong("updatedAt", 0L), mask, logo));
            }
        } catch (Exception ignored) {}
        if (hasBuiltIns(context)) {
            for (BuiltIn item : BUILT_INS) {
                if (out.stream().noneMatch(a -> item.manifestUrl().equalsIgnoreCase(a.manifestUrl))) {
                    out.add(new Addon(item.manifestUrl(), baseUrlFromManifest(item.manifestUrl()), item.id(),
                            item.name(), "", "", true, 0L, item.resources(), item.logo()));
                }
            }
        }
        return out;
    }

    public static synchronized boolean hasEnabled(Context context) {
        for (Addon a : list(context)) {
            if (a.enabled) return true;
        }
        return false;
    }

    public static void setEnabled(Context context, String manifestUrl, boolean enabled) {
        setEnabled(context, Map.of(manifestUrl, enabled));
    }

    public static synchronized void setEnabled(Context context, Map<String, Boolean> updates) {
        if (updates.isEmpty()) return;
        List<Addon> current = new ArrayList<>(list(context));
        boolean changed = false;
        for (int i = 0; i < current.size(); i++) {
            Addon a = current.get(i);
            Boolean enabled = updates.get(a.manifestUrl);
            if (enabled == null || a.enabled == enabled) continue;
            current.set(i, new Addon(a.manifestUrl, a.baseUrl, a.id, a.name, a.description,
                    a.version, enabled, System.currentTimeMillis(), a.resources, a.logo));
            changed = true;
        }
        if (changed) persist(context, current);
    }

    public static synchronized void remove(Context context, String manifestUrl) {
        List<Addon> current = new ArrayList<>(list(context));
        current.removeIf(a -> a.manifestUrl.equalsIgnoreCase(manifestUrl) && (!hasBuiltIns(context) || builtIn(a) == null));
        persist(context, current);
    }

    public static boolean isInstalled(Context context, String rawUrl) {
        try {
            String normalized = normalizeManifestUrl(rawUrl);
            for (Addon a : list(context)) {
                if (a.manifestUrl.equalsIgnoreCase(normalized)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Fetches curated open addons from meedx.pages.dev/api/addons.json, with a built-in
     * open-source fallback list when offline or unreachable.
     */
    public static List<CuratedAddon> fetchCurated(Context context) {
        List<CuratedAddon> out = new ArrayList<>();
        try {
            String body = fetchText(CURATED_URL).trim();
            JSONArray arr = body.startsWith("[") ? new JSONArray(body) : new JSONObject(body).optJSONArray("addons");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String mUrl = o.optString("manifestUrl", o.optString("url", "")).trim();
                    String name = o.optString("name", "").trim();
                    if (mUrl.isEmpty() || name.isEmpty()) continue;
                    out.add(new CuratedAddon(
                            name,
                            localizedDescription(o.opt("description"), context),
                            mUrl,
                            o.optString("category", o.optString("badge", "Open")).trim()));
                }
            }
        } catch (Exception ignored) {}
        if (out.isEmpty()) {
            boolean ar = "ar".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
            out.add(new CuratedAddon(
                    "OpenSubtitles v3 (Free)",
                    ar ? "ترجمات مجانية بعدة لغات دون حساب أو مفتاح API" : "Free multi-language subtitles with zero account or API key",
                    "https://opensubtitles-v3.strem.io/manifest.json",
                    "Subtitles"));
            out.add(new CuratedAddon(
                    "WatchHub",
                    ar ? "البحث عن روابط البث الرسمية باستخدام معرّف IMDb" : "Official streaming links lookup via IMDb",
                    "https://watchhub.strem.io/manifest.json",
                    "Streams"));
            out.add(new CuratedAddon(
                    "Public Domain Movies",
                    ar ? "أفلام كلاسيكية من الملكية العامة متاحة للبث" : "Classic public domain films ready to stream",
                    "https://caching.stremio.net/publicdomainmovies.now.sh/manifest.json",
                    "Streams"));
            out.add(new CuratedAddon(
                    "Cinemeta Catalog",
                    ar ? "كتالوج IMDb مفتوح ودليل للحلقات" : "Open IMDb metadata catalog & episode guide",
                    "https://v3-cinemeta.strem.io/manifest.json",
                    "Metadata"));
        }
        var urls = new java.util.HashSet<String>();
        out.removeIf(item -> retiredRecommendation(item.name, item.manifestUrl));
        out.forEach(addon -> urls.add(addon.manifestUrl));
        boolean arabic = "ar".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
        String[][] recommended = {
                {"Cinemeta", "https://v3-cinemeta.strem.io/manifest.json", "كتالوج أفلام ومسلسلات", "Movie and series catalog"},
                {"AIOMetadata", "https://aiometadata.elfhosted.com/manifest.json", "كتالوج وبيانات متعددة المصادر", "Catalog and metadata from multiple providers"},
                {"MediaFusion | ElfHosted", "https://mediafusion.elfhosted.com/manifest.json", "مصادر بث وتورنت", "Streaming and torrent sources"},
                {"Peerflix", "https://peerflix.mov/manifest.json", "مصادر تورنت للأفلام والمسلسلات", "Torrent sources for movies and series"},
                {"Torrentio", "https://torrentio.strem.fun/manifest.json", "مصادر تورنت", "Torrent sources"},
                {"Public Domain Movies", "https://caching.stremio.net/publicdomainmovies.now.sh/manifest.json",
                        "أفلام من الملكية العامة", "Public domain films"}
        };
        for (String[] item : recommended) {
            if (urls.add(item[1])) out.add(new CuratedAddon(item[0], arabic ? item[2] : item[3], item[1],
                    arabic ? (item[0].equals("Cinemeta") || item[0].equals("AIOMetadata") ? "كتالوج" : "بث")
                            : (item[0].equals("Cinemeta") || item[0].equals("AIOMetadata") ? "Catalog" : "Streams")));
        }
        return out;
    }

    private static boolean retiredRecommendation(String name, String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host == null) return true;
        host = host.toLowerCase(Locale.ROOT);
        return unsupportedMovieProvider(name) || java.util.Set.of("cyberflix.elfhosted.com",
                "thepiratebay-plus.strem.fun", "publicdomainmovies.onrender.com", "watchhub.stremio.io",
                "watchhub.strem.io", "comet.elfhosted.com", "webstreamr.hayd.uk").contains(host);
    }

    private static boolean unsupportedMovieProvider(String name) {
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return key.equals("besttorrents") || key.startsWith("fayatv") || key.equals("watchhub");
    }

    public static String localizedDescription(Object raw, Context context) {
        if (raw == null || raw == JSONObject.NULL) return "";
        boolean arabic = "ar".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
        if (raw instanceof String encoded && encoded.trim().startsWith("\"")) {
            try {
                Object decoded = new JSONArray("[" + encoded + "]").opt(0);
                if (decoded instanceof String text && !text.equals(encoded) && text.trim().startsWith("{")) {
                    raw = text;
                }
            } catch (org.json.JSONException ignored) { return ""; }
        }
        JSONObject translations = raw instanceof JSONObject obj ? obj
                : raw instanceof String text && text.trim().startsWith("{") ? parseObject(text) : null;
        if (translations != null) {
            return translations.optString(arabic ? "ar" : "en",
                    translations.optString(arabic ? "en" : "ar", "")).trim();
        }
        return raw instanceof String text && !text.trim().startsWith("{") ? text.trim() : "";
    }

    private static JSONObject parseObject(String text) {
        try { return new JSONObject(text); } catch (org.json.JSONException ignored) { return null; }
    }

    public static synchronized JSONArray exportJson(Context context) {
        JSONArray arr = new JSONArray();
        for (Addon a : list(context)) {
            if (a.updatedAt == 0L && builtIn(a) != null) continue;
            try {
                arr.put(new JSONObject()
                        .put("manifestUrl", a.manifestUrl)
                        .put("baseUrl", a.baseUrl)
                        .put("id", a.id)
                        .put("name", a.name)
                        .put("description", a.description)
                        .put("version", a.version)
                        .put("enabled", a.enabled).put("resources_mask", a.resources)
                        .put("logo", a.logo).put("updatedAt", a.updatedAt));
            } catch (Exception ignored) {}
        }
        return arr;
    }

    public static synchronized boolean mergeRemoteJson(Context context, JSONArray remote) {
        if (context == null || remote == null) return false;
        Map<String, Addon> byUrl = new LinkedHashMap<>();
        for (Addon local : list(context)) {
            byUrl.put(local.manifestUrl.toLowerCase(Locale.ROOT), local);
        }
        boolean changed = false;
        for (int i = 0; i < remote.length(); i++) {
            JSONObject o = remote.optJSONObject(i);
            if (o == null) continue;
            String mUrl = o.optString("manifestUrl", "");
            if (mUrl.isBlank()) continue;
            String key = mUrl.toLowerCase(Locale.ROOT);
            long remoteTs = o.optLong("updatedAt", 0L);
            Addon existing = byUrl.get(key);
            if (existing == null || remoteTs > existing.updatedAt) {
                byUrl.put(key, new Addon(
                        mUrl,
                        baseUrlFromManifest(mUrl),
                        o.optString("id", mUrl),
                        o.optString("name", "Addon"),
                        o.optString("description", ""),
                        o.optString("version", ""),
                        o.optBoolean("enabled", true),
                        remoteTs > 0 ? remoteTs : System.currentTimeMillis(), o.optInt("resources_mask", -1), o.optString("logo")));
                changed = true;
            }
        }
        if (changed) persist(context, new ArrayList<>(byUrl.values()));
        return changed;
    }

    public static synchronized boolean replaceAuthoritativeJson(Context context, JSONArray remote) {
        if (context == null || remote == null) return false;
        List<Addon> next = new ArrayList<>();
        for (int i = 0; i < remote.length(); i++) {
            JSONObject o = remote.optJSONObject(i);
            if (o == null) continue;
            String mUrl = o.optString("manifestUrl", "");
            if (mUrl.isBlank()) continue;
            long remoteTs = o.optLong("updatedAt", 0L);
            next.add(new Addon(
                    mUrl,
                    baseUrlFromManifest(mUrl),
                    o.optString("id", mUrl),
                    o.optString("name", "Addon"),
                    o.optString("description", ""),
                    o.optString("version", ""),
                    o.optBoolean("enabled", true),
                    remoteTs > 0 ? remoteTs : System.currentTimeMillis(), o.optInt("resources_mask", -1), o.optString("logo")));
        }
        if (hasBuiltIns(context)) {
            for (Addon local : list(context)) {
                if (builtIn(local) != null && next.stream().noneMatch(a -> a.manifestUrl.equalsIgnoreCase(local.manifestUrl)))
                    next.add(local);
            }
        }
        persist(context, next);
        return true;
    }

    private static void persist(Context context, List<Addon> addons) {
        JSONArray arr = new JSONArray();
        for (Addon a : addons) {
            try {
                arr.put(new JSONObject()
                        .put("manifestUrl", a.manifestUrl)
                        .put("baseUrl", a.baseUrl)
                        .put("id", a.id)
                        .put("name", a.name)
                        .put("description", a.description)
                        .put("version", a.version)
                        .put("enabled", a.enabled).put("resources_mask", a.resources)
                        .put("logo", a.logo).put("updatedAt", a.updatedAt));
            } catch (Exception ignored) {}
        }
        prefs(context).edit().putString(KEY_ADDONS, arr.toString()).commit();
    }

    public record Poster(String id, String type, String title, String image, String backdrop, String description, String year, double rating, String released, JSONArray genres, String imdb, long tmdb) {}
    public record EpisodeChoice(int season, int episode, String title, String streamId) {
        public EpisodeChoice(int season, int episode, String title) { this(season, episode, title, ""); }
    }
    public record Capabilities(boolean stream, boolean subtitles, JSONArray catalogs) {}

    private static int resourceMask(JSONObject manifest) {
        int mask = 0;
        JSONArray resources = manifest.optJSONArray("resources");
        for (int i = 0; resources != null && i < resources.length(); i++) {
            JSONObject item = resources.optJSONObject(i);
            String name = item == null ? resources.optString(i) : item.optString("name");
            if ("stream".equals(name)) mask |= STREAMS;
            else if ("subtitles".equals(name)) mask |= SUBTITLES;
            else if ("catalog".equals(name)) mask |= CATALOGS;
            else if ("meta".equals(name)) mask |= META;
        }
        return mask;
    }

    public static boolean subtitleOnly(Addon addon) {
        if (addon.resources >= 0) return (addon.resources & STREAMS) == 0 && (addon.resources & SUBTITLES) != 0;
        String identity = (addon.id + " " + addon.name + " " + Uri.parse(addon.manifestUrl).getHost()).toLowerCase(Locale.ROOT);
        return identity.contains("opensubtitles") || identity.contains("subtitle");
    }

    public static boolean mayCatalog(Addon addon) {
        if (addon == null) return false;
        if (addon.resources >= 0) return (addon.resources & CATALOGS) != 0;
        BuiltIn bi = builtIn(addon);
        if (bi != null) return (bi.resources() & CATALOGS) != 0;
        JSONObject cached = manifestRules.get(addon.manifestUrl);
        return cached != null && (resourceMask(cached) & CATALOGS) != 0;
    }

    public static String manifestLogo(Addon addon) {
        if (addon == null) return "";
        if (addon.logo != null && !addon.logo.isBlank()) return addon.logo;
        BuiltIn bi = builtIn(addon);
        if (bi != null && !bi.logo().isBlank()) return bi.logo();
        try {
            return readLogo(addon.manifestUrl, new JSONObject(fetchText(addon.manifestUrl)));
        } catch (Exception ignored) { return ""; }
    }

    private static String readLogo(String manifestUrl, JSONObject manifest) {
        try {
            String raw = optStr(manifest, "logo");
            if (raw.isEmpty()) return "";
            String logo = new URL(new URL(manifestUrl), raw).toString();
            if (logo.startsWith("http://www.strem.io/")) logo = "https://" + logo.substring("http://".length());
            return isHttpUrl(logo) ? logo : "";
        } catch (Exception ignored) { return ""; }
    }

    private static String optStr(JSONObject obj, String key) {
        if (obj == null || obj.isNull(key)) return "";
        String value = obj.optString(key, "");
        return "null".equals(value) ? "" : value;
    }

    public static boolean mayStream(Addon addon) {
        return !unsupportedMovieProvider(addon.name)
                && (addon.resources >= 0 ? (addon.resources & STREAMS) != 0 : !subtitleOnly(addon));
    }

    public static Capabilities capabilities(Addon addon) {
        try {
            JSONObject manifest = new JSONObject(fetchText(addon.manifestUrl));
            int resources = resourceMask(manifest);
            manifestRules.put(addon.manifestUrl, manifest);
            return new Capabilities((resources & STREAMS) != 0, (resources & SUBTITLES) != 0,
                    manifest.optJSONArray("catalogs"));
        } catch (Exception ignored) {
            return new Capabilities(addon.resources >= 0 && mayStream(addon), subtitleOnly(addon), null);
        }
    }

    private static void searchAddonCatalogs(Context context, String query, Map<String, Poster> posters) {
        var enabled = list(context).stream().filter(source -> source.enabled && (query.isBlank()
                ? mayCatalog(source) && !"v3-cinemeta.strem.io".equalsIgnoreCase(Uri.parse(source.manifestUrl).getHost())
                : mayStream(source) || mayCatalog(source))).limit(12).collect(java.util.stream.Collectors.toList());
        var searches = enabled.stream().<java.util.concurrent.Callable<Map<String, Poster>>>map(source -> () -> {
            var found = new LinkedHashMap<String, Poster>();
            var caps = capabilities(source);
            if (caps.catalogs() != null) readAddonCatalog(found, source, caps, query, null);
            return found;
        }).collect(java.util.stream.Collectors.toList());
        parallel(searches, found -> found.forEach(posters::putIfAbsent));
    }

    public static List<Poster> browse(Context context, Addon addon, Capabilities capability, String query) {
        Map<String, Poster> posters = new LinkedHashMap<>();
        if (addon != null) {
            readAddonCatalog(posters, addon, capability, query, null);
        } else if (!query.isBlank()) {
            searchAddonCatalogs(context, query, posters);
            if (Thread.currentThread().isInterrupted()) return List.of();
        }
        if (posters.isEmpty()) appendTmdb(context, query, posters);
        if (posters.isEmpty()) {
            String filter = query.isBlank() ? "" : "/search=" + Uri.encode(query);
            for (String type : List.of("movie", "series")) {
                appendCatalog(posters, "https://v3-cinemeta.strem.io/catalog/" + type + "/top" + filter + ".json",
                        type, query, true);
            }
        }
        if (addon == null && query.isBlank()) {
            return java.util.stream.Stream.concat(
                    posters.values().stream().filter(item -> "movie".equals(item.type())).limit(30),
                    posters.values().stream().filter(item -> "series".equals(item.type())).limit(30)).collect(java.util.stream.Collectors.toList());
        }
        return posters.values().stream().limit(60).collect(java.util.stream.Collectors.toList());
    }

    public record CatalogFilter(String type, String genre, int movieGenre, int tvGenre,
                                int firstYear, int lastYear, double minRating, boolean highestRated) {}

    public record CatalogPage(List<Poster> posters, boolean hasMore, boolean retry) {}

    /** Keeps provider offsets and buffered results for one TV catalog or search. */
    public static final class CatalogPager {
        private static final int BATCH_SIZE = 40;
        private final Context context;
        private final String query;
        private final String tmdbQuery;
        private final CatalogFilter filter;
        private final java.time.LocalDate today = java.time.LocalDate.now();
        private final java.util.Comparator<Poster> order;
        private final List<CatalogFeed> feeds;
        private final java.util.ArrayDeque<Poster> pending = new java.util.ArrayDeque<>();
        private final java.util.HashSet<String> seen = new java.util.HashSet<>();
        private final Map<String, Boolean> tmdbByTitle = new java.util.HashMap<>();
        private boolean first = true;

        public CatalogPager(Context context, String query) {
            this(context, query, new CatalogFilter("", "", 0, 0, 1920, 9999, 0, false));
        }

        public CatalogPager(Context context, String query, CatalogFilter filter) {
            this.filter = filter;
            var newest = java.util.Comparator.comparing(Poster::year).thenComparing(Poster::released).reversed();
            order = filter.highestRated() ? java.util.Comparator.comparingDouble(Poster::rating).reversed().thenComparing(newest) : newest;
            this.context = context.getApplicationContext();
            this.query = query;
            String key = ProviderKeys.get(context, ProviderKeys.TMDB);
            tmdbQuery = key.isBlank() ? "" : tmdbOptions(context, key);
            feeds = List.of("movie", "series").stream().filter(type -> filter.type().isEmpty() || filter.type().equals(type))
                    .map(CatalogFeed::new).collect(java.util.stream.Collectors.toList());
        }

        public CatalogPage next() {
            long deadline = elapsedMillis() + REQUEST_BUDGET_MS;
            boolean breakAfterBatch = false;
            for (int round = 0; round < 3 && pending.size() < BATCH_SIZE
                    && !breakAfterBatch && !Thread.currentThread().isInterrupted(); round++) {
                var fetched = new ArrayList<List<Poster>>();
                var tasks = new ArrayList<java.util.concurrent.Callable<List<Poster>>>();
                for (var feed : feeds) {
                    if (feed.exhausted) continue;
                    tasks.add(feed::read);
                }
                if (first) {
                    tasks.add(() -> {
                        var found = new LinkedHashMap<String, Poster>();
                        searchAddonCatalogs(context, query, found);
                        return found.values().stream().filter(poster -> !query.isBlank()
                                || !poster.year().isEmpty() && Integer.parseInt(poster.year()) == Math.min(today.getYear(), filter.lastYear()))
                                .collect(java.util.stream.Collectors.toList());
                    });
                    first = false;
                }
                parallel(tasks, fetched::add, deadline);
                if (fetched.size() < tasks.size()) breakAfterBatch = true;
                if (fetched.isEmpty()) break;
                int largest = fetched.stream().mapToInt(List::size).max().orElse(0);
                for (int i = 0; i < largest; i++) {
                    for (var page : fetched) {
                        if (i < page.size()) enqueue(page.get(i));
                    }
                }
            }
            var ordered = new ArrayList<>(pending);
            ordered.sort(order);
            pending.clear();
            pending.addAll(ordered);
            var result = new ArrayList<Poster>();
            while (result.size() < BATCH_SIZE && !pending.isEmpty()) result.add(pending.removeFirst());
            boolean more = !pending.isEmpty() || feeds.stream().anyMatch(feed -> !feed.exhausted);
            return new CatalogPage(List.copyOf(result), more, result.isEmpty() && breakAfterBatch);
        }

        private void enqueue(Poster poster) {
            if (!filter.type().isEmpty() && !filter.type().equals(poster.type())) return;
            int year = poster.year().isEmpty() ? 0 : Integer.parseInt(poster.year());
            if (year < filter.firstYear() || year > Math.min(today.getYear(), filter.lastYear())) return;
            if (poster.released().isEmpty() ? year == today.getYear() : poster.released().compareTo(today.toString()) > 0) return;
            if (poster.rating() < (filter.minRating() > 0 ? filter.minRating() : query.isBlank() ? 1 : 0)) return;
            if (!filter.genre().isEmpty() && !genreMatches(poster)) return;
            String prefix = poster.type() + ':';
            if (seen.contains(prefix + poster.id()) || (!poster.imdb().isBlank() && seen.contains(prefix + poster.imdb()))
                    || (poster.tmdb() > 0 && seen.contains(prefix + "tmdb:" + poster.tmdb()))) return;
            seen.add(prefix + poster.id());
            if (!poster.imdb().isBlank()) seen.add(prefix + poster.imdb());
            if (poster.tmdb() > 0) seen.add(prefix + "tmdb:" + poster.tmdb());
            if (!poster.year().isBlank()) {
                String title = poster.type() + ':' + poster.title().strip().toLowerCase(Locale.ROOT)
                        + ':' + poster.year();
                boolean tmdb = poster.id().startsWith("tmdb:");
                Boolean previous = tmdbByTitle.putIfAbsent(title, tmdb);
                if (previous != null && previous != tmdb) return;
            }
            pending.add(poster);
        }

        private boolean genreMatches(Poster poster) {
            JSONArray genres = poster.genres();
            int genreId = "series".equals(poster.type()) ? filter.tvGenre() : filter.movieGenre();
            for (int i = 0; genres != null && i < genres.length(); i++) {
                if (filter.genre().equalsIgnoreCase(genres.optString(i))
                        || (genreId > 0 && genres.optInt(i) == genreId)) return true;
            }
            return false;
        }

        private final class CatalogFeed {
            private final String type;
            private final java.util.HashSet<String> providerIds = new java.util.HashSet<>();
            private boolean useTmdb = !tmdbQuery.isEmpty();
            private int page = 1;
            private int skip;
            private int catalogYear = Math.min(today.getYear(), filter.lastYear());
            private int emptyPages;
            private boolean exhausted;

            CatalogFeed(String type) {
                this.type = type;
                if ("series".equals(type) && !filter.genre().isEmpty() && filter.tvGenre() == 0) useTmdb = false;
            }

            synchronized List<Poster> read() throws IOException, org.json.JSONException {
                checkBudget();
                var found = new LinkedHashMap<String, Poster>();
                if (useTmdb) {
                    String tmdbType = "series".equals(type) ? "tv" : "movie";
                    String field = "tv".equals(tmdbType) ? "first_air_date" : "primary_release_date";
                    int genre = "tv".equals(tmdbType) ? filter.tvGenre() : filter.movieGenre();
                    String end = catalogYear == today.getYear() ? today.toString() : catalogYear + "-12-31";
                    String path = query.isBlank() ? "/discover/" + tmdbType + "?sort_by="
                            + (filter.highestRated() ? "vote_average.desc" : field + ".desc")
                            + "&vote_count.gte=" + (filter.highestRated() ? 30 : 5)
                            + "&vote_average.gte=" + Math.max(1, filter.minRating())
                            + "&" + field + ".gte=" + filter.firstYear() + "-01-01"
                            + "&" + field + ".lte=" + end + (genre > 0 ? "&with_genres=" + genre : "")
                            : "/search/" + tmdbType + "?query=" + Uri.encode(query);
                    try {
                        var json = new JSONObject(fetchText("https://api.themoviedb.org/3" + path
                                + "&include_adult=false&page=" + page + "&" + tmdbQuery));
                        JSONArray rows = json.getJSONArray("results");
                        readTmdbRows(found, rows, tmdbType);
                        if (page > 1 || !found.isEmpty()) {
                            checkBudget();
                            exhausted = rows.length() == 0 || page >= Math.min(500, json.optInt("total_pages", page));
                            page++;
                            return fresh(found);
                        }
                    } catch (IOException | org.json.JSONException failure) {
                        if (page > 1 || Thread.currentThread().isInterrupted()) throw failure;
                    }
                    useTmdb = false;
                }
                boolean byYear = query.isBlank() && !filter.highestRated() && filter.genre().isEmpty();
                String catalog = byYear ? "year" : filter.highestRated() && query.isBlank() ? "imdbRating" : "top";
                String extra = byYear ? "genre=" + catalogYear : query.isBlank()
                        ? (filter.genre().isEmpty() ? "" : "genre=" + Uri.encode(filter.genre()))
                        : "search=" + Uri.encode(query);
                if (skip > 0) extra += (extra.isEmpty() ? "" : "&") + "skip=" + skip;
                String url = "https://v3-cinemeta.strem.io/catalog/" + type + "/" + catalog
                        + (extra.isEmpty() ? "" : "/" + extra) + ".json";
                JSONArray rows = new JSONObject(fetchText(url)).getJSONArray("metas");
                readCatalogRows(found, rows, type, "", rows.length());
                checkBudget();
                skip += rows.length();
                var added = fresh(found);
                if (byYear && (rows.length() == 0 || exhausted)) {
                    catalogYear--;
                    skip = 0;
                    emptyPages = 0;
                    exhausted = catalogYear < filter.firstYear();
                } else if (rows.length() == 0) {
                    exhausted = true;
                }
                return added;
            }

            private List<Poster> fresh(Map<String, Poster> found) {
                var added = new ArrayList<Poster>();
                found.forEach((id, poster) -> {
                    if (providerIds.add(id)) added.add(poster);
                });
                emptyPages = added.isEmpty() ? emptyPages + 1 : 0;
                if (emptyPages >= 2) exhausted = true;
                return added;
            }
        }
    }

    public static List<Poster> browseCatalog(Addon addon, String catalogId, String query) {
        return browseCatalog(addon, null, catalogId, query);
    }

    public static List<Poster> browseCatalog(Addon addon, String catalogType, String catalogId, String query) {
        var posters = new LinkedHashMap<String, Poster>();
        readAddonCatalog(posters, addon, capabilities(addon), query, catalogType, catalogId);
        return new ArrayList<>(posters.values());
    }

    private static void readAddonCatalog(Map<String, Poster> posters, Addon addon, Capabilities capability,
                                         String query, String catalogId) {
        readAddonCatalog(posters, addon, capability, query, null, catalogId);
    }

    private static void readAddonCatalog(Map<String, Poster> posters, Addon addon, Capabilities capability,
                                         String query, String catalogType, String catalogId) {
        if (addon != null && capability != null && capability.catalogs() != null) {
            JSONArray catalogs = capability.catalogs();
            for (int i = 0; !Thread.currentThread().isInterrupted() && i < (catalogId == null ? Math.min(catalogs.length(), 8) : catalogs.length()); i++) {
                JSONObject catalog = catalogs.optJSONObject(i);
                if (catalog == null) continue;
                String id = optStr(catalog, "id");
                String type = optStr(catalog, "type");
                if (id.isBlank()) continue;
                if (catalogId != null && !catalogId.equals(id)) continue;
                if (catalogType != null && !catalogType.isBlank() && !catalogType.equals(type)) continue;
                if (catalogId == null && !supportedType(type)) continue;
                JSONArray extra = catalog.optJSONArray("extra");
                boolean searchable = false;
                boolean missingRequired = false;
                for (int n = 0; extra != null && n < extra.length(); n++) {
                    JSONObject arg = extra.optJSONObject(n);
                    if (arg == null) continue;
                    boolean search = "search".equals(arg.optString("name"));
                    searchable |= search;
                    missingRequired |= arg.optBoolean("isRequired") && (!search || query.isBlank());
                }
                if (missingRequired) continue;
                String route = addon.baseUrl + "/catalog/" + Uri.encode(type) + "/" + Uri.encode(id);
                if (!query.isBlank() && searchable) route += "/search=" + Uri.encode(query);
                appendCatalog(posters, route + ".json", type, query, searchable);
            }
        }
    }

    private static void appendCatalog(Map<String, Poster> out, String url, String type, String query, boolean searched) {
        try {
            JSONArray rows = new JSONObject(fetchText(url)).optJSONArray("metas");
            if (rows != null) readCatalogRows(out, rows, type, searched ? "" : query, Math.min(rows.length(), 100));
        } catch (Exception ignored) {}
    }

    private static void readCatalogRows(Map<String, Poster> out, JSONArray rows, String type, String filter, int count) {
        String needle = filter.toLowerCase(Locale.ROOT);
        for (int i = 0; i < count; i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String id = optStr(row, "id");
            String title = optStr(row, "name");
            if (title.isBlank()) title = optStr(row, "title");
            String rowType = optStr(row, "type");
            String effectiveType = rowType.isBlank() ? type : rowType;
            if (id.isBlank() || title.isBlank() || (!needle.isBlank() && !title.toLowerCase(Locale.ROOT).contains(needle))) continue;
            String poster = optStr(row, "poster");
            if (poster.isBlank()) poster = optStr(row, "logo");
            String imdb = id.matches("tt[0-9]+") ? id : optStr(row, "imdb_id");
            out.putIfAbsent(effectiveType + ":" + id, new Poster(id, effectiveType, title, poster,
                    optStr(row, "background"), optStr(row, "description"),
                    year(optStr(row, "releaseInfo").isBlank() ? optStr(row, "year") : optStr(row, "releaseInfo")),
                    rating(row.optDouble("imdbRating", 0.0)), date(optStr(row, "released")), row.optJSONArray("genres"),
                    imdb, row.optLong("tmdb_id", 0)));
        }
    }

    private static String tmdbOptions(Context context, String key) {
        boolean ar = "ar".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
        return "api_key=" + Uri.encode(key) + "&language=" + (ar ? "ar-SA" : "en-US");
    }

    private static void appendTmdb(Context context, String query, Map<String, Poster> out) {
        String key = ProviderKeys.get(context, ProviderKeys.TMDB);
        if (key.isBlank()) return;
        String path = query.isBlank() ? "/trending/all/day?" : "/search/multi?query=" + Uri.encode(query) + "&";
        try {
            JSONArray rows = new JSONObject(fetchText("https://api.themoviedb.org/3" + path
                    + tmdbOptions(context, key))).getJSONArray("results");
            readTmdbRows(out, rows, "");
        } catch (Exception ignored) {}
    }

    private static void readTmdbRows(Map<String, Poster> out, JSONArray rows, String defaultType) {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || row.optBoolean("adult") || row.optInt("id") <= 0) continue;
            String type = row.optString("media_type", defaultType);
            boolean tv = "tv".equals(type);
            if (!tv && !"movie".equals(type)) continue;
            String title = row.optString(tv ? "name" : "title");
            if (title.isBlank()) continue;
            String posterPath = row.optString("poster_path");
            String backdropPath = row.optString("backdrop_path");
            String id = "tmdb:" + row.optInt("id");
            String mediaType = tv ? "series" : "movie";
            out.put(mediaType + ":" + id, new Poster(id, mediaType, title,
                    posterPath.startsWith("/") ? "https://image.tmdb.org/t/p/w342" + posterPath : "",
                    backdropPath.startsWith("/") ? "https://image.tmdb.org/t/p/w780" + backdropPath : "",
                    row.optString("overview"), year(row.optString(tv ? "first_air_date" : "release_date")),
                    rating(row.optDouble("vote_average", 0.0)), date(row.optString(tv ? "first_air_date" : "release_date")),
                    row.optJSONArray("genre_ids"), row.optString("imdb_id", ""), row.optLong("id")));
        }
    }

    private static String date(String value) {
        return value.length() >= 10 && value.substring(0, 10).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                ? value.substring(0, 10) : "";
    }

    private static String year(String value) {
        return value.length() >= 4 && value.substring(0, 4).matches("[0-9]{4}") ? value.substring(0, 4) : "";
    }

    private static double rating(double value) {
        return Double.isFinite(value) && value > 0 && value <= 10 ? value : 0;
    }

    public static String streamId(Context context, Poster item) {
        if (item.imdb().matches("tt[0-9]+")) return item.imdb();
        return canonicalStreamId(context, item.type(), item.id(), item.tmdb());
    }

    public static String canonicalStreamId(Context context, String mediaType, String rawId, long tmdb) {
        if (rawId == null) rawId = "";
        if (rawId.matches("tt[0-9]+")) return rawId;
        boolean tmdbId = rawId.matches("(?i)(?:tmdb:)?[0-9]{1,12}");
        if (!tmdbId && !rawId.isBlank()) return rawId;
        if (tmdbId) tmdb = Long.parseLong(rawId.substring(rawId.lastIndexOf(':') + 1));
        if (tmdb <= 0) return "";
        boolean series = "series".equalsIgnoreCase(mediaType) || "episode".equalsIgnoreCase(mediaType);
        String key = ProviderKeys.get(context, ProviderKeys.TMDB);
        if (key.isBlank()) return "";
        try {
            var ids = new JSONObject(fetchText("https://api.themoviedb.org/3/" + (series ? "tv/" : "movie/")
                    + tmdb + "/external_ids?api_key=" + Uri.encode(key)));
            String imdb = ids.optString("imdb_id");
            return imdb.matches("tt[0-9]+") ? imdb : "";
        } catch (Exception ignored) { return ""; }
    }

    public static List<EpisodeChoice> episodes(Addon addon, String id) {
        return episodes(addon, "series", id);
    }

    public static List<EpisodeChoice> episodes(Addon addon, String type, String id) {
        String metaType = (type == null || type.isBlank()) ? "series" : type;
        var endpoints = new ArrayList<String>();
        if (addon != null) endpoints.add(addon.baseUrl);
        if (id.matches("tt[0-9]+") && !endpoints.contains("https://v3-cinemeta.strem.io")) {
            endpoints.add("https://v3-cinemeta.strem.io");
        }
        for (String endpoint : endpoints) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                JSONObject meta = new JSONObject(fetchText(endpoint + "/meta/" + Uri.encode(metaType) + "/" + Uri.encode(id) + ".json"))
                        .optJSONObject("meta");
                var episodes = parseEpisodes(meta == null ? null : meta.optJSONArray("videos"));
                if (!episodes.isEmpty()) return episodes;
            } catch (Exception ignored) {}
        }
        return List.of();
    }

    private static List<EpisodeChoice> parseEpisodes(JSONArray videos) {
        var episodes = new java.util.TreeMap<Long, EpisodeChoice>();
        for (int i = 0; videos != null && i < videos.length(); i++) {
            JSONObject row = videos.optJSONObject(i);
            if (row == null) continue;
            int season = row.optInt("season", -1);
            int episode = row.optInt("episode", -1);
            if (season < 1 || episode < 1) continue;
            String title = row.isNull("title") ? "" : row.optString("title").strip();
            if (title.isBlank() && !row.isNull("name")) title = row.optString("name").strip();
            long key = ((long) season << 32) | episode;
            var existing = episodes.get(key);
            if (existing == null || (existing.title().isBlank() && !title.isBlank())) {
                episodes.put(key, new EpisodeChoice(season, episode, title, row.optString("id")));
            }
        }
        return List.copyOf(episodes.values());
    }

    public static List<String> arabicSubtitleUrls(Context context, String imdb, int season, int episode) {
        if (imdb == null || !imdb.matches("tt[0-9]+")) return List.of();
        var urls = new java.util.LinkedHashSet<String>();
        String resource = episode > 0 ? "/subtitles/series/" + imdb + ":" + season + ":" + episode
                : "/subtitles/movie/" + imdb;
        for (Addon addon : list(context).stream().filter(item -> item.enabled && (item.resources < 0 || (item.resources & SUBTITLES) != 0)).limit(6).collect(java.util.stream.Collectors.toList())) {
            if (Thread.currentThread().isInterrupted()) return List.of();
            if (!capabilities(addon).subtitles()) continue;
            try {
                JSONArray rows = new JSONObject(fetchText(addon.baseUrl + resource + ".json")).optJSONArray("subtitles");
                for (int i = 0; rows != null && i < rows.length() && urls.size() < 5; i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    String language = row.optString("lang").toLowerCase(Locale.ROOT);
                    String url = row.optString("url");
                    if ((language.equals("ar") || language.equals("ara") || language.equals("arabic")
                            || language.startsWith("ar-")) && isHttpUrl(url)) urls.add(url);
                }
            } catch (Exception ignored) {}
        }
        if (urls.isEmpty() && !Thread.currentThread().isInterrupted()) {
            ZeroConfigSubtitles.search(imdb, episode > 0, season, episode, "ar").stream().limit(5)
                    .map(subtitle -> subtitle.url).filter(StremioAddons::isHttpUrl).forEach(urls::add);
        }
        return List.copyOf(urls);
    }

    private static boolean supportedType(String type) {
        return "movie".equals(type) || "series".equals(type);
    }

    public static List<Stream> resolveStreams(Context context, String mediaType, String id, int season, int episode) {
        return resolveStreams(context, mediaType, id, season, episode, null);
    }

    public static List<Stream> resolveStreams(Context context, String mediaType, String id, int season, int episode, Addon only) {
        return resolveStreams(context, mediaType, id, season, episode, only, batch -> {});
    }

    public static List<Stream> resolveStreams(Context context, String mediaType, String id, int season, int episode,
                                               Addon only, java.util.function.Consumer<List<Stream>> partial) {
        if (context == null || id == null || id.isBlank() || id.length() > 200) return List.of();
        boolean series = "series".equalsIgnoreCase(mediaType) || "episode".equalsIgnoreCase(mediaType)
                || ("anime".equalsIgnoreCase(mediaType) && episode > 0) || episode > 0;
        String type;
        if ("episode".equalsIgnoreCase(mediaType)) type = "series";
        else if (mediaType != null && !mediaType.isBlank()) type = mediaType.toLowerCase(Locale.ROOT);
        else type = series ? "series" : "movie";
        String suffix = ":" + Math.max(0, season) + ":" + Math.max(1, episode);
        boolean fullEpisodeId = id.endsWith(suffix)
                || id.endsWith("___" + suffix.substring(1))
                || id.matches("(?i)kitsu:[0-9]+:[0-9]+");
        String path = "/stream/" + Uri.encode(type) + "/" + Uri.encode(id, ":")
                + (series && !fullEpisodeId ? suffix : "") + ".json";
        Addon targetOnly = (only != null && mayStream(only)) ? only : null;
        var addons = list(context).stream().filter(item -> item.enabled && mayStream(item)
                && (targetOnly == null || targetOnly.manifestUrl.equalsIgnoreCase(item.manifestUrl))
                && acceptsStream(item, type, id))
                .collect(java.util.stream.Collectors.toList());
        var statuses = new java.util.concurrent.ConcurrentHashMap<String, StreamHealth>();
        var tasks = addons.stream().<java.util.concurrent.Callable<List<Stream>>>map(addon -> () -> {
            long started = elapsedMillis();
            var batch = streamsFrom(addon, path);
            var issue = connectionIssue(addon);
            var state = new StreamHealth(addon.name, elapsedMillis() - started, batch.size(), issue);
            statuses.put(addon.manifestUrl, state);
            streamHealth.put(addon.manifestUrl, state);
            return batch;
        }).collect(java.util.stream.Collectors.toList());
        var results = new ArrayList<Stream>();
        parallel(tasks, batch -> {
            if (batch.isEmpty()) return;
            results.addAll(batch);
            partial.accept(interleaveProviders(results));
        }, elapsedMillis() + STREAM_BUDGET_MS);
        if (!Thread.currentThread().isInterrupted()) {
            for (Addon addon : addons) {
                if (!statuses.containsKey(addon.manifestUrl)) {
                    streamHealth.put(addon.manifestUrl, new StreamHealth(addon.name, STREAM_BUDGET_MS, 0, true));
                }
            }
        }
        return interleaveProviders(results);
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, JSONObject> manifestRules =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean acceptsStream(Addon addon, String type, String id) {
        JSONObject manifest = manifestRules.get(addon.manifestUrl);
        if (manifest == null) return true;
        JSONArray resources = manifest.optJSONArray("resources");
        for (int i = 0; resources != null && i < resources.length(); i++) {
            JSONObject resource = resources.optJSONObject(i);
            if (!"stream".equals(resource == null ? resources.optString(i) : resource.optString("name"))) continue;
            JSONArray types = resource != null && resource.has("types") ? resource.optJSONArray("types") : manifest.optJSONArray("types");
            JSONArray prefixes = resource != null && resource.has("idPrefixes")
                    ? resource.optJSONArray("idPrefixes") : manifest.optJSONArray("idPrefixes");
            if (matchesManifest(types, type, false) && matchesManifest(prefixes, id, true)) return true;
        }
        return false;
    }

    private static boolean matchesManifest(JSONArray values, String value, boolean prefix) {
        if (values == null || values.length() == 0) return true;
        for (int i = 0; i < values.length(); i++) {
            String rule = values.optString(i);
            if (prefix ? value.startsWith(rule) : value.equals(rule)) return true;
        }
        return false;
    }

    public static StreamHealth testMovie(Addon addon) {
        long started = elapsedMillis();
        if (!mayStream(addon)) return new StreamHealth(addon.name, 0, 0, false);
        providerDeadline.set(started + STREAM_BUDGET_MS);
        try {
            var links = streamsFrom(addon, "/stream/movie/tt0063350.json");
            var result = new StreamHealth(addon.name, elapsedMillis() - started, links.size(), connectionIssue(addon));
            streamHealth.put(addon.manifestUrl, result);
            return result;
        } finally {
            providerDeadline.remove();
        }
    }

    private static final int STREAM_BUDGET_MS = 10_000;
    public record StreamHealth(String name, long millis, int results, boolean unavailable) {}
    private static final java.util.concurrent.ConcurrentHashMap<String, StreamHealth> streamHealth =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static StreamHealth getStreamHealth(Addon addon) { return streamHealth.get(addon.manifestUrl); }

    private static List<Stream> interleaveProviders(List<Stream> found) {
        var sorted = new ArrayList<>(found);
        sorted.sort(java.util.Comparator.comparingInt((Stream stream) -> stream.seeds).reversed());
        var featured = new ArrayList<Stream>(sorted.size());
        var shown = new java.util.HashSet<String>();
        for (Stream stream : sorted) {
            if (shown.add(stream.addonName)) featured.add(stream);
        }
        sorted.removeAll(featured);
        featured.addAll(sorted);
        return List.copyOf(featured);
    }

    private static List<Stream> streamsFrom(Addon addon, String path) {
        var results = new ArrayList<Stream>();
        try {
            JSONArray streams = new JSONObject(fetchText(resourceUrl(addon, path))).optJSONArray("streams");
            streamDenied.remove(addon.manifestUrl);
            unreachable.remove(addon.manifestUrl);
            for (int i = 0; streams != null && i < Math.min(streams.length(), 80); i++) {
                JSONObject row = streams.optJSONObject(i);
                if (row == null) continue;
                String url = row.optString("url");
                if (url.isBlank() && isTorrentMagnet(row.optString("externalUrl"))) url = row.optString("externalUrl");
                String hash = row.optString("infoHash");
                if (url.isBlank() && hash.matches("(?i)[a-f0-9]{40}")) {
                    var magnet = new StringBuilder("magnet:?xt=urn:btih:").append(hash);
                    JSONArray sources = row.optJSONArray("sources");
                    for (int n = 0; sources != null && n < Math.min(sources.length(), 12); n++) {
                        String source = sources.optString(n);
                        if (source.startsWith("tracker:")) magnet.append("&tr=").append(Uri.encode(source.substring(8)));
                        else if (source.matches("peer:[0-9.]+:[0-9]{1,5}")) {
                            magnet.append("&x.pe=").append(Uri.encode(source.substring(5)));
                        }
                    }
                    url = magnet.toString();
                }
                String format = isTorrentMagnet(url) ? "torrent" : guessFormat(url);
                if (!"torrent".equals(format)) {
                    if (!isHttpUrl(url)) continue;
                    String filePath = Uri.parse(url).getPath();
                    if (filePath != null && filePath.toLowerCase(Locale.ROOT).matches(".*\\.(zip|rar|srt|vtt|nfo|html?)$")) continue;
                }
                String title = (row.optString("name") + " " + row.optString("title", row.optString("description")))
                        .replace('\n', ' ').trim();
                JSONObject hints = row.optJSONObject("behaviorHints");
                long size = row.optLong("size", hints == null ? 0 : hints.optLong("videoSize"));
                if (size <= 0) size = displaySize(row.optString("size") + " " + title + " " + (hints == null ? "" : hints.optString("filename")));
                String metadata = title + " " + (hints == null ? "" : hints.optString("filename"));
                String languages = row.optString("language", row.optString("languages", ""));
                results.add(new Stream(addon.name, title, extractQuality(metadata), url, format,
                        Math.max(-1, row.optInt("fileIdx", -1)), Math.max(0, size), seeds(row, hints, title), extractLanguages(languages + " " + metadata)));
            }
        } catch (HttpFailure failure) {
            if (failure.code == 401 || failure.code == 403 || failure.code == 410 || failure.code == 429) {
                streamDenied.put(addon.manifestUrl, elapsedMillis() + 300_000);
            }
        } catch (java.net.UnknownHostException | java.net.ConnectException unreachableHost) {
            unreachable.put(addon.manifestUrl, elapsedMillis() + 300_000);
        } catch (Exception failed) {
            unreachable.put(addon.manifestUrl, elapsedMillis() + 60_000);
        }
        return results;
    }

    public static boolean connectionIssue(Addon addon) {
        long now = elapsedMillis();
        return now < unreachable.getOrDefault(addon.manifestUrl, 0L)
                || now < streamDenied.getOrDefault(addon.manifestUrl, 0L);
    }

    public record AddonTiming(String name, boolean ok, long millis) {}

    public static List<AddonTiming> probeAddons(List<Addon> addons) {
        var report = new java.util.LinkedHashMap<Addon, AddonTiming>();
        long started = elapsedMillis();
        var tasks = addons.stream().<java.util.concurrent.Callable<Map.Entry<Addon, AddonTiming>>>map(addon -> () -> {
            boolean ok;
            try {
                manifestRules.put(addon.manifestUrl, new JSONObject(fetchText(addon.manifestUrl)));
                ok = true;
                unreachable.remove(addon.manifestUrl);
            } catch (Exception failure) {
                ok = false;
                if (failure instanceof java.net.UnknownHostException || failure instanceof java.net.ConnectException
                        || failure instanceof HttpFailure) {
                    unreachable.put(addon.manifestUrl, elapsedMillis() + 300_000);
                }
            }
            return new java.util.AbstractMap.SimpleImmutableEntry<>(addon, new AddonTiming(addon.name, ok, elapsedMillis() - started));
        }).collect(java.util.stream.Collectors.toList());
        parallel(tasks, result -> report.put(result.getKey(), result.getValue()));
        return addons.stream().map(addon -> report.getOrDefault(addon, new AddonTiming(addon.name, false, REQUEST_BUDGET_MS)))
                .collect(java.util.stream.Collectors.toList());
    }

    private static List<String> extractLanguages(String input) {
        String text = input.toLowerCase(Locale.ROOT);
        var result = new ArrayList<String>();
        if (text.matches("(?s).*(?:\\b(?:arabic|ara|ar)\\b|العربية|🇸🇦|🇮🇶).*")) result.add("ar");
        if (text.matches("(?s).*(?:\\b(?:english|eng|en)\\b|🇬🇧|🇺🇸).*")) result.add("en");
        if (text.matches("(?s).*\\b(?:multi(?:[ -]?audio)?|dual[ -]?audio)\\b.*")) result.add("multi");
        for (String[] lang : new String[][]{{"es","spanish|spa|español"},{"fr","french|fre|fra"},{"pt","portuguese|por"},
                {"de","german|ger|deu"},{"it","italian|ita"},{"hi","hindi|hin"},{"ja","japanese|jpn"},
                {"ko","korean|kor"},{"tr","turkish|tur"},{"ru","russian|rus"}}) {
            if (text.matches("(?s).*\\b(?:" + lang[1] + ")\\b.*")) result.add(lang[0]);
        }
        return List.copyOf(result);
    }

    private static long displaySize(String label) {
        Matcher match = DISPLAY_SIZE.matcher(label);
        if (!match.find()) return 0;
        try {
            double size = Double.parseDouble(match.group(1).replace(',', '.'));
            String unit = match.group(2).toLowerCase(Locale.ROOT);
            int power = "kmgt".indexOf(unit.charAt(0)) + 1;
            return Math.round(size * Math.pow(unit.length() == 3 ? 1024 : 1000, power));
        } catch (NumberFormatException ignored) { return 0; }
    }

    private static int seeds(JSONObject row, JSONObject hints, String title) {
        int reported = row.optInt("seeders", row.optInt("seeds", hints == null ? -1
                : hints.optInt("seeders", hints.optInt("seeds", -1))));
        if (reported >= 0) return reported;
        Matcher match = SEED_COUNT.matcher(title);
        if (!match.find()) return -1;
        String number = match.group(1) != null ? match.group(1) : match.group(2);
        try { return Integer.parseInt(number.replaceAll("[^0-9]", "")); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    public static boolean isPlayableDirectStream(Stream stream) {
        if (stream == null || stream.torrent() || !isHttpUrl(stream.url)) return false;
        String current = stream.url;
        boolean playlist = "hls".equals(stream.format) || "dash".equals(stream.format)
                || current.toLowerCase(Locale.ROOT).contains(".m3u8")
                || current.toLowerCase(Locale.ROOT).contains(".mpd");
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(current).openConnection();
                connection.setConnectTimeout(4500);
                connection.setReadTimeout(4500);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                if (!playlist) connection.setRequestProperty("Range", "bytes=0-1023");
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String next = connection.getHeaderField("Location");
                    if (next == null || next.isBlank()) return false;
                    current = new URL(new URL(current), next).toString();
                    if (!isHttpUrl(current)) return false;
                    continue;
                }
                if (status != 200 && status != 206) {
                    return playlist && status == 405;
                }
                String mime = connection.getContentType();
                if (mime == null) return true;
                mime = mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                return !mime.equals("text/html") && !mime.equals("application/xhtml+xml")
                        && !mime.equals("application/json") && !mime.endsWith("+json")
                        && !mime.startsWith("image/") && !mime.startsWith("audio/")
                        && !mime.equals("application/zip") && !mime.equals("application/x-bittorrent");
            } catch (IOException | IllegalArgumentException failure) {
                return playlist && hop > 0;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return false;
    }

    private static boolean isHttpUrl(String url) {
        Uri uri = Uri.parse(url);
        return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null;
    }

    public static boolean isTorrentMagnet(String url) {
        if (url == null || url.length() > 8192 || !url.startsWith("magnet:?")) return false;
        return MAGNET_HASH.matcher(url).find();
    }

    private static String extractQuality(String text) {
        if (text == null || text.isBlank()) return "";
        Matcher m = QUALITY_PATTERN.matcher(text);
        List<String> tags = new ArrayList<>();
        while (m.find() && tags.size() < 8) {
            String tag = m.group(1).toUpperCase(Locale.ROOT);
            if (!tags.contains(tag)) tags.add(tag);
        }
        return String.join(" ", tags);
    }

    private static String guessFormat(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".m3u8")) return "hls";
        if (lower.contains(".mpd")) return "dash";
        return "addon";
    }

    private static int checkBudget() throws java.io.InterruptedIOException {
        Long deadline = providerDeadline.get();
        long remaining = deadline == null ? REQUEST_BUDGET_MS : deadline - elapsedMillis();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
        return (int) remaining;
    }

    private static final class HttpFailure extends IOException {
        final int code;

        HttpFailure(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private static String fetchText(String url) throws IOException {
        String current = url;
        for (int hop = 0; hop < 5; hop++) {
            int timeout = checkBudget();
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            try {
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank()) throw new HttpFailure(code);
                    current = new URL(new URL(current), location).toString();
                    if (!isHttpUrl(current)) throw new IOException("Invalid redirect");
                    continue;
                }
                if (code != 200) throw new HttpFailure(code);
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        checkBudget();
                        if (out.size() + n > 2 * 1024 * 1024) throw new IOException("Addon response too large");
                        out.write(buf, 0, n);
                    }
                    checkBudget();
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }
}

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
        public final String nameAr;
        public final String nameEn;
        public final String description;
        public final String descriptionAr;
        public final String descriptionEn;
        public final String manifestUrl;
        public final String configureUrl;
        public final String category;
        public final String categoryAr;
        public final String categoryEn;

        public CuratedAddon(String name, String description, String manifestUrl, String category) {
            this(name, name, name, description, description, description, manifestUrl, "", category, category, category);
        }

        public CuratedAddon(String name, String nameAr, String nameEn,
                            String description, String descriptionAr, String descriptionEn,
                            String manifestUrl, String configureUrl,
                            String category, String categoryAr, String categoryEn) {
            this.name = name == null ? "" : name.trim();
            this.nameAr = (nameAr == null || nameAr.isBlank()) ? this.name : nameAr.trim();
            this.nameEn = (nameEn == null || nameEn.isBlank()) ? this.name : nameEn.trim();
            this.description = description == null ? "" : description.trim();
            this.descriptionAr = (descriptionAr == null || descriptionAr.isBlank()) ? this.description : descriptionAr.trim();
            this.descriptionEn = (descriptionEn == null || descriptionEn.isBlank()) ? this.description : descriptionEn.trim();
            this.manifestUrl = manifestUrl == null ? "" : manifestUrl.trim();
            this.configureUrl = configureUrl == null ? "" : configureUrl.trim();
            this.category = category == null ? "" : category.trim();
            this.categoryAr = (categoryAr == null || categoryAr.isBlank()) ? this.category : categoryAr.trim();
            this.categoryEn = (categoryEn == null || categoryEn.isBlank()) ? this.category : categoryEn.trim();
        }

        public boolean requiresConfigure() {
            return manifestUrl.isEmpty() && !configureUrl.isEmpty();
        }
    }

    public enum AddonHealthState {
        UNKNOWN,
        HEALTHY,
        DEGRADED,
        OFFLINE
    }

    public record AddonHealthReport(AddonHealthState state, long millis, String detail, long checkedAt) {}
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

    public record StreamSubtitle(String id, String url, String lang) {}

    public record AddonMetaHint(String titleEn, String titleFa, String imdbId, int year) {}

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
        public final String userAgent;
        public final String referer;
        public final Map<String, String> headers;
        public final List<StreamSubtitle> subtitles;
        public final String filename;
        public final String videoHash;

        public Stream(String addonName, String title, String quality, String url, String format) {
            this(addonName, title, quality, url, format, -1, 0, -1);
        }

        Stream(String addonName, String title, String quality, String url, String format, int fileIndex, long sizeBytes, int seeds) {
            this(addonName, title, quality, url, format, fileIndex, sizeBytes, seeds, List.of(), "", "", Map.of(), List.of(), "", "");
        }

        Stream(String addonName, String title, String quality, String url, String format, int fileIndex,
               long sizeBytes, int seeds, List<String> languages) {
            this(addonName, title, quality, url, format, fileIndex, sizeBytes, seeds, languages, "", "", Map.of(), List.of(), "", "");
        }

        Stream(String addonName, String title, String quality, String url, String format, int fileIndex,
               long sizeBytes, int seeds, List<String> languages, String userAgent, String referer) {
            this(addonName, title, quality, url, format, fileIndex, sizeBytes, seeds, languages, userAgent, referer, Map.of(), List.of(), "", "");
        }

        Stream(String addonName, String title, String quality, String url, String format, int fileIndex,
               long sizeBytes, int seeds, List<String> languages, String userAgent, String referer,
               Map<String, String> headers) {
            this(addonName, title, quality, url, format, fileIndex, sizeBytes, seeds, languages, userAgent, referer, headers, List.of(), "", "");
        }

        Stream(String addonName, String title, String quality, String url, String format, int fileIndex,
               long sizeBytes, int seeds, List<String> languages, String userAgent, String referer,
               Map<String, String> headers, List<StreamSubtitle> subtitles) {
            this(addonName, title, quality, url, format, fileIndex, sizeBytes, seeds, languages, userAgent, referer, headers, subtitles, "", "");
        }

        Stream(String addonName, String title, String quality, String url, String format, int fileIndex,
               long sizeBytes, int seeds, List<String> languages, String userAgent, String referer,
               Map<String, String> headers, List<StreamSubtitle> subtitles, String filename, String videoHash) {
            this.languages = languages;
            this.seeds = seeds;
            this.fileIndex = fileIndex;
            this.sizeBytes = sizeBytes;
            this.addonName = addonName;
            this.title = title;
            this.quality = quality;
            this.url = url;
            this.format = format;
            this.userAgent = userAgent == null ? "" : userAgent;
            this.referer = referer == null ? "" : referer;
            this.headers = headers == null || headers.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            this.subtitles = subtitles == null || subtitles.isEmpty() ? List.of() : List.copyOf(subtitles);
            this.filename = filename == null ? "" : filename;
            this.videoHash = videoHash == null ? "" : videoHash;
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
        if (trimmed.regionMatches(true, 0, "stremios://", 0, "stremios://".length())) {
            trimmed = trimmed.substring("stremios://".length());
        } else if (trimmed.regionMatches(true, 0, "stremio://", 0, "stremio://".length())) {
            trimmed = trimmed.substring("stremio://".length());
        }
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.regionMatches(true, 0, "https%3a%2f%2f", 0, 14)
                || trimmed.regionMatches(true, 0, "http%3a%2f%2f", 0, 13)) {
            trimmed = Uri.decode(trimmed);
            while (trimmed.startsWith("/")) {
                trimmed = trimmed.substring(1);
            }
        }
        if (!trimmed.regionMatches(true, 0, "https://", 0, 8)
                && !trimmed.regionMatches(true, 0, "http://", 0, 7)) {
            trimmed = "https://" + trimmed;
        }
        Uri uri = Uri.parse(trimmed);
        String scheme = uri.getScheme();
        if ((!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
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

    public static String resourceUrl(Addon addon, String resource) {
        if (addon == null) return resource;
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
     * Fetches curated open addons (combining active built-in presets, new Direct/Configure presets,
     * and non-duplicate remote entries from meedx.pages.dev/api/addons.json while filtering broken sources).
     */
    public static List<CuratedAddon> fetchCurated(Context context) {
        boolean arabic = context != null && "ar".equals(
                context.getResources().getConfiguration().getLocales().get(0).getLanguage());
        List<CuratedAddon> presets = buildCuratedPresets(arabic);
        var seenManifests = new java.util.HashSet<String>();
        var seenConfigs = new java.util.HashSet<String>();
        var seenNames = new java.util.HashSet<String>();
        List<CuratedAddon> out = new ArrayList<>();
        for (CuratedAddon preset : presets) {
            if (!preset.manifestUrl.isEmpty()) seenManifests.add(preset.manifestUrl.toLowerCase(Locale.ROOT));
            if (!preset.configureUrl.isEmpty()) seenConfigs.add(preset.configureUrl.toLowerCase(Locale.ROOT));
            seenNames.add(normalizePresetKey(preset.nameEn));
            out.add(preset);
        }
        try {
            String body = fetchText(CURATED_URL).trim();
            JSONArray arr = body.startsWith("[") ? new JSONArray(body) : new JSONObject(body).optJSONArray("addons");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String mUrl = o.optString("manifestUrl", o.optString("url", "")).trim();
                    String cfgUrl = o.optString("configureUrl", "").trim();
                    String name = o.optString("name", "").trim();
                    if ((mUrl.isEmpty() && cfgUrl.isEmpty()) || name.isEmpty()) continue;
                    if (!mUrl.isEmpty() && retiredRecommendation(name, mUrl)) continue;
                    if (isRetiredBrokenAddon("", name, mUrl)) continue;
                    String normKey = normalizePresetKey(name);
                    if (seenNames.contains(normKey)) continue;
                    if (!mUrl.isEmpty() && !seenManifests.add(mUrl.toLowerCase(Locale.ROOT))) continue;
                    if (!cfgUrl.isEmpty() && !seenConfigs.add(cfgUrl.toLowerCase(Locale.ROOT))) continue;
                    seenNames.add(normKey);
                    String desc = localizedDescription(o.opt("description"), context);
                    String catEn = o.optString("category", o.optString("badge", "Streams")).trim();
                    String catAr = translateCategoryToAr(catEn);
                    out.add(new CuratedAddon(
                            name, o.optString("nameAr", name).trim(), name,
                            desc, desc, desc,
                            mUrl, cfgUrl,
                            arabic ? catAr : catEn, catAr, catEn));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String normalizePresetKey(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (lower.startsWith("opensubtitles")) return "opensubtitlesv3";
        if (lower.startsWith("mediafusion")) return "mediafusion";
        if (lower.startsWith("aiometadata")) return "aiometadata";
        if (lower.startsWith("cinemeta")) return "cinemeta";
        if (lower.startsWith("ultimatesmartsubs")) return "ultimatesmartsubs";
        return lower;
    }

    private static String translateCategoryToAr(String catEn) {
        if (catEn == null) return "إضافات";
        return switch (catEn.trim().toLowerCase(Locale.ROOT)) {
            case "subtitles", "subtitle" -> "ترجمات";
            case "catalog", "catalogs", "metadata" -> "كتالوج وبيانات";
            case "direct http streams", "http", "direct" -> "بث HTTP مباشر";
            case "live tv & sports", "live tv", "sports", "iptv" -> "بث مباشر ورياضة";
            case "torrent backup", "backup" -> "تورنت احتياطي";
            default -> "مصادر بث وتورنت";
        };
    }

    private static CuratedAddon preset(boolean arabic, String nameEn, String nameAr,
                                       String descEn, String descAr,
                                       String manifestUrl, String configureUrl,
                                       String catEn, String catAr) {
        return new CuratedAddon(
                arabic ? nameAr : nameEn, nameAr, nameEn,
                arabic ? descAr : descEn, descAr, descEn,
                manifestUrl, configureUrl,
                arabic ? catAr : catEn, catAr, catEn);
    }

    private static List<CuratedAddon> buildCuratedPresets(boolean arabic) {
        List<CuratedAddon> list = new ArrayList<>();
        // --- Active Direct-Manifest Presets ---
        list.add(preset(arabic,
                "Torrentio", "Torrentio · تورينتيو",
                "Fast multi-tracker torrent streams for movies, series & anime",
                "مصادر تورنت سريعة للأفلام والمسلسلات والأنمي بعدة جودات",
                "https://torrentio.strem.fun/manifest.json", "",
                "Torrent Streams", "بث وتورنت"));
        list.add(preset(arabic,
                "Comet", "Comet · كوميت",
                "High-speed ElfHosted torrent & stream indexer",
                "فهرس بث وتورنت فائق السرعة للأفلام والمسلسلات",
                "https://comet.elfhosted.com/manifest.json", "",
                "Torrent Streams", "بث وتورنت"));
        list.add(preset(arabic,
                "MediaFusion", "MediaFusion · ميديا فيوجن",
                "Global movies, series & live sports stream provider",
                "مصادر بث وتورنت شاملة للأفلام والمسلسلات والرياضة",
                "https://mediafusion.elfhosted.com/manifest.json", "",
                "Streams & Live", "بث ورياضة"));
        list.add(preset(arabic,
                "Peerflix", "Peerflix · بيرفليكس",
                "Clean torrent streams for movies and TV episodes",
                "روابط تورنت مرتبة للأفلام وحلقات المسلسلات",
                "https://peerflix.mov/manifest.json", "",
                "Torrent Streams", "بث وتورنت"));
        list.add(preset(arabic,
                "SubSense", "SubSense · ساب سينس",
                "Smart multi-language subtitles for IMDb & Kitsu anime",
                "ترجمات ذكية متعددة اللغات للأفلام والمسلسلات والأنمي",
                "https://subsense.nepiraw.com/manifest.json", "",
                "Subtitles", "ترجمات"));
        list.add(preset(arabic,
                "OpenSubtitles v3", "OpenSubtitles v3 · أوبن سبتايتلز",
                "Official zero-config multi-language subtitle add-on",
                "الترجمات الرسمية المجانية بعدة لغات دون حساب أو مفتاح",
                "https://opensubtitles-v3.strem.io/manifest.json", "",
                "Subtitles", "ترجمات"));
        list.add(preset(arabic,
                "Cinemeta", "Cinemeta · سينيميتا",
                "Official IMDb movies & series catalog and episode guide",
                "الكتالوج الرسمي للأفلام والمسلسلات ودليل الحلقات",
                "https://v3-cinemeta.strem.io/manifest.json", "",
                "Catalog & Metadata", "كتالوج وبيانات"));
        list.add(preset(arabic,
                "Streaming Catalogs", "Streaming Catalogs · كتالوج المنصات",
                "Trending catalogs from Netflix, HBO, Apple TV+, Disney+ & Prime",
                "قوائم أبرز أعمال المنصات العالمية للأفلام والمسلسلات",
                "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/manifest.json", "",
                "Catalogs", "كتالوج المنصات"));
        list.add(preset(arabic,
                "AIO Metadata", "AIO Metadata · بيانات شاملة",
                "All-in-one rich metadata and multi-provider catalogs",
                "بيانات تفصيلية وكتالوجات موحّدة من عدة مصادر",
                "https://aiometadata.elfhosted.com/manifest.json", "",
                "Catalog & Metadata", "كتالوج وبيانات"));
        list.add(preset(arabic,
                "Anime Kitsu", "Anime Kitsu · أنمي كيتسو",
                "Complete anime catalog, seasons, ratings & episode guide",
                "كتالوج الأنمي الشامل مع الحلقات والتصنيفات والتقييمات",
                "https://anime-kitsu.strem.fun/manifest.json", "",
                "Anime Catalog", "كتالوج أنمي"));
        list.add(preset(arabic,
                "Public Domain Movies", "Public Domain · أفلام كلاسيكية",
                "Direct HTTP streams for classic public domain films",
                "روابط بث HTTP مباشرة لأشهر الأفلام الكلاسيكية المفتوحة",
                "https://caching.stremio.net/publicdomainmovies.now.sh/manifest.json", "",
                "Direct HTTP Streams", "بث HTTP مباشر"));

        // --- Configure-in-Browser Presets (Captured via stremio:// -> Install on TV) ---
        list.add(preset(arabic,
                "WebStreamr", "WebStreamr · بث ويب مباشر",
                "Multi-host direct HTTP streams without torrents (configure & install)",
                "روابط بث HTTP مباشرة متعددة السيرفرات بدون تورنت (يُضبط من المتصفح)",
                "", "https://stremio-addons.net/addons/webstreamr",
                "Direct HTTP Streams", "بث HTTP مباشر"));
        list.add(preset(arabic,
                "NoTorrent", "NoTorrent · بث بدون تورنت",
                "Direct HTTP/HLS video streams without P2P swarming",
                "روابط فيديو HTTP و HLS مباشرة سريعة بدون تورنت",
                "", "https://stremio-addons.net/addons/notorrent",
                "Direct HTTP Streams", "بث HTTP مباشر"));
        list.add(preset(arabic,
                "TorrentsDB", "TorrentsDB · قاعدة التورنت",
                "Reliable backup torrent indexer across major public trackers",
                "مصدر تورنت احتياطي قوي للأفلام والمسلسلات بجودات عالية",
                "", "https://stremio-addons.net/addons/torrentsdb",
                "Torrent Backup", "تورنت احتياطي"));
        list.add(preset(arabic,
                "Ultimate Smart Subs (USS)", "Ultimate Smart Subs · الترجمة الذكية",
                "Configurable multi-source & AI-assisted subtitle add-on",
                "إضافة ترجمة متقدمة متعددة المصادر مع دعم العربية الكامل",
                "https://uss.ezerche.com/manifest.json", "https://stremio-addons.net/addons/ultimate-smart-subs",
                "Subtitles", "ترجمات"));
        list.add(preset(arabic,
                "TvVoo", "TvVoo · قنوات ورياضة مباشرة",
                "Live TV channels and sports streams (configure & install on TV)",
                "قنوات تلفزيونية وبث رياضي مباشر (افتح الإعداد للتثبيت على الشاشة)",
                "", "https://stremio-addons.net/addons/tvvoo",
                "Live TV & Sports", "بث مباشر ورياضة"));
        list.add(preset(arabic,
                "Live Sports Directory", "Live Sports · دليل إضافات الرياضة",
                "Browse verified live sports & TV add-ons on stremio-addons.net",
                "تصفّح أحدث إضافات المباريات والقنوات الرياضية لتثبيتها على الشاشة",
                "", "https://stremio-addons.net/?search=sports",
                "Live TV & Sports", "بث مباشر ورياضة"));
        return list;
    }

    private static boolean retiredRecommendation(String name, String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host == null) return true;
        host = host.toLowerCase(Locale.ROOT);
        return unsupportedMovieProvider(name) || isRetiredBrokenAddon("", name, url) || java.util.Set.of("cyberflix.elfhosted.com",
                "thepiratebay-plus.strem.fun", "publicdomainmovies.onrender.com", "watchhub.stremio.io",
                "watchhub.strem.io", "webstreamr.hayd.uk", "fun.rfnx.workers.dev").contains(host);
    }

    public static boolean isRetiredBrokenAddon(String id, String name, String url) {
        String lowerId = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String host = "";
        if (url != null && !url.isBlank()) {
            try {
                String h = Uri.parse(url).getHost();
                if (h != null) host = h.toLowerCase(Locale.ROOT);
            } catch (Exception ignored) {}
        }
        return "fun.rfnx.workers.dev".equals(host)
                || "webstreamr.hayd.uk".equals(host)
                || "thepiratebay-plus.strem.fun".equals(host)
                || "cyberflix.elfhosted.com".equals(host)
                || "publicdomainmovies.onrender.com".equals(host)
                || "best.torrents.stremio.add-on".equals(lowerId)
                || "besttorrents".equals(lowerName);
    }

    public static boolean isLiveChannelType(String type) {
        if (type == null || type.isBlank()) return false;
        return switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "tv", "channel", "channels", "sport", "sports", "live", "event", "events", "iptv", "radio" -> true;
            default -> false;
        };
    }

    public static boolean isLiveChannelAddon(Addon addon) {
        if (addon == null) return false;
        JSONObject manifest = manifestRules.get(addon.manifestUrl);
        if (manifest != null) {
            JSONArray types = manifest.optJSONArray("types");
            if (types != null && types.length() > 0) {
                boolean hasLive = false;
                boolean hasVod = false;
                for (int i = 0; i < types.length(); i++) {
                    String t = types.optString(i);
                    if (isLiveChannelType(t)) hasLive = true;
                    else if ("movie".equalsIgnoreCase(t) || "series".equalsIgnoreCase(t) || "anime".equalsIgnoreCase(t)) hasVod = true;
                }
                if (hasLive && !hasVod) return true;
                if (hasVod && !hasLive) return false;
            }
        }
        String hay = (addon.id + " " + addon.name + " " + addon.manifestUrl).toLowerCase(Locale.ROOT);
        return hay.contains("fayatv") || hay.contains("fayallc") || hay.contains("usatv")
                || hay.contains("tvvoo") || hay.contains("iptv") || hay.contains("livetv");
    }

    private static boolean unsupportedMovieProvider(String name) {
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return key.equals("watchhub");
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

    /** Exports every installed add-on (including built-ins' enabled/disabled state & full configured manifest URLs) for complete snapshots. */
    public static synchronized JSONArray exportFullSnapshotJson(Context context) {
        JSONArray arr = new JSONArray();
        for (Addon a : list(context)) {
            if (a == null || a.manifestUrl == null || a.manifestUrl.isBlank()) continue;
            try {
                arr.put(new JSONObject()
                        .put("manifestUrl", a.manifestUrl)
                        .put("baseUrl", a.baseUrl)
                        .put("id", a.id)
                        .put("name", a.name)
                        .put("description", a.description)
                        .put("version", a.version)
                        .put("enabled", a.enabled)
                        .put("resources_mask", a.resources)
                        .put("logo", a.logo)
                        .put("updatedAt", a.updatedAt));
            } catch (Exception ignored) {}
        }
        return arr;
    }

    /** Restores an authoritative full add-on snapshot (including configured manifest URLs and built-in toggle states). */
    public static synchronized boolean restoreFullSnapshotJson(Context context, JSONArray snapshot) {
        if (context == null || snapshot == null || snapshot.length() == 0) return false;
        List<Addon> next = new ArrayList<>();
        var seen = new java.util.HashSet<String>();
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject o = snapshot.optJSONObject(i);
            if (o == null) continue;
            String mUrl = o.optString("manifestUrl", "").trim();
            if (mUrl.isEmpty() || !seen.add(mUrl.toLowerCase(Locale.ROOT))) continue;
            long ts = o.optLong("updatedAt", 0L);
            boolean enabled = o.optBoolean("enabled", true);
            BuiltIn bi = BUILT_INS.stream().filter(b -> b.manifestUrl().equalsIgnoreCase(mUrl)).findFirst().orElse(null);
            if (bi != null && !enabled && ts == 0L) ts = 1L;
            next.add(new Addon(
                    mUrl,
                    baseUrlFromManifest(mUrl),
                    o.optString("id", mUrl),
                    o.optString("name", bi != null ? bi.name() : "Addon"),
                    o.optString("description", ""),
                    o.optString("version", ""),
                    enabled,
                    ts,
                    o.optInt("resources_mask", bi != null ? bi.resources() : -1),
                    o.optString("logo", bi != null ? bi.logo() : "")));
        }
        if (hasBuiltIns(context)) {
            for (BuiltIn bi : BUILT_INS) {
                if (seen.add(bi.manifestUrl().toLowerCase(Locale.ROOT))) {
                    next.add(new Addon(bi.manifestUrl(), baseUrlFromManifest(bi.manifestUrl()), bi.id(),
                            bi.name(), "", "", true, 0L, bi.resources(), bi.logo()));
                }
            }
        }
        persist(context, next);
        return true;
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

    public record Poster(String id, String type, String title, String image, String backdrop, String description, String year, double rating, String released, JSONArray genres, String imdb, long tmdb, String posterShape) {
        public Poster(String id, String type, String title, String image, String backdrop, String description, String year, double rating, String released, JSONArray genres, String imdb, long tmdb) {
            this(id, type, title, image, backdrop, description, year, rating, released, genres, imdb, tmdb, "");
        }
    }
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
        if (addon == null) return false;
        if (addon.resources >= 0) {
            return (addon.resources & SUBTITLES) != 0
                    && (addon.resources & STREAMS) == 0
                    && (addon.resources & CATALOGS) == 0;
        }
        BuiltIn bi = builtIn(addon);
        if (bi != null) {
            return (bi.resources() & SUBTITLES) != 0
                    && (bi.resources() & STREAMS) == 0
                    && (bi.resources() & CATALOGS) == 0;
        }
        JSONObject cached = manifestRules.get(addon.manifestUrl);
        if (cached != null) {
            int mask = resourceMask(cached);
            return (mask & SUBTITLES) != 0 && (mask & STREAMS) == 0 && (mask & CATALOGS) == 0;
        }
        return isDedicatedSubtitleDescriptor(addon.id, addon.manifestUrl, addon.name, -1, false);
    }

    /** True only for dedicated subtitle add-ons (excludes movie/catalog/streaming add-ons like Persian Stremio). */
    public static boolean isDedicatedSubtitleAddon(Addon addon) {
        if (addon == null) return false;
        if (isNonSubtitleCatalogOrStreamName(addon.id, addon.manifestUrl, addon.name)) return false;
        return subtitleOnly(addon);
    }

    /** Evaluates synced JSON descriptors for dedicated subtitle add-ons only. */
    public static boolean isDedicatedSubtitleDescriptor(String id, String manifestUrl, String name,
                                                        int resourcesMask, boolean providesSubHint) {
        if (isNonSubtitleCatalogOrStreamName(id, manifestUrl, name)) return false;
        if (resourcesMask >= 0) {
            return (resourcesMask & SUBTITLES) != 0
                    && (resourcesMask & STREAMS) == 0
                    && (resourcesMask & CATALOGS) == 0;
        }
        String host = "";
        try {
            String h = manifestUrl == null ? null : Uri.parse(manifestUrl).getHost();
            if (h != null) host = h;
        } catch (Exception ignored) {}
        String identity = ((id == null ? "" : id) + " "
                + (name == null ? "" : name) + " " + host + " "
                + (manifestUrl == null ? "" : manifestUrl)).toLowerCase(Locale.ROOT);
        if (identity.contains("opensubtitles") || identity.contains("subtitle")
                || identity.contains("subsense") || identity.contains("smartsubs")
                || identity.contains("ezerche") || identity.contains("turkcealtyazi")
                || identity.contains("ultimate smart subs") || identity.contains("uss.")
                || identity.contains("subdl") || identity.contains("subsource")) {
            return true;
        }
        return providesSubHint && resourcesMask < 0;
    }

    private static boolean isNonSubtitleCatalogOrStreamName(String id, String manifestUrl, String name) {
        String hay = ((id == null ? "" : id) + " "
                + (name == null ? "" : name) + " "
                + (manifestUrl == null ? "" : manifestUrl)).toLowerCase(Locale.ROOT);
        return hay.contains("persianstremio") || hay.contains("persian stremio")
                || hay.contains("pstream.ir") || hay.contains("torrentio")
                || hay.contains("comet") || hay.contains("mediafusion")
                || hay.contains("peerflix") || hay.contains("notorrent")
                || hay.contains("webstreamr") || hay.contains("nuvio")
                || hay.contains("fayatv") || hay.contains("tvvoo")
                || hay.contains("cinemeta") || hay.contains("kitsu")
                || hay.contains("publicdomain") || hay.contains("torrentsdb");
    }

    public static boolean providesSubtitles(Addon addon) {
        return isDedicatedSubtitleAddon(addon);
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
                String route = "/catalog/" + Uri.encode(type) + "/" + Uri.encode(id);
                if (!query.isBlank() && searchable) route += "/search=" + Uri.encode(query);
                appendCatalog(posters, resourceUrl(addon, route + ".json"), type, query, searchable);
            }
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, JSONArray> inlineCatalogStreams =
            new java.util.concurrent.ConcurrentHashMap<>();

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
            String rawImdb = optStr(row, "imdb_id");
            long tmdbNum = row.optLong("tmdb_id", row.optLong("moviedb_id", 0L));
            if (id.isBlank()) {
                if (!rawImdb.isBlank()) {
                    id = rawImdb;
                } else {
                    String kitsuId = optStr(row, "kitsu_id");
                    if (!kitsuId.isBlank()) id = kitsuId.startsWith("kitsu:") ? kitsuId : "kitsu:" + kitsuId;
                    else if (tmdbNum > 0) id = "tmdb:" + tmdbNum;
                }
            }
            if (tmdbNum <= 0 && id.matches("(?i)tmdb:[0-9]{1,12}")) {
                try { tmdbNum = Long.parseLong(id.substring(5)); } catch (NumberFormatException ignored) {}
            }
            String title = optStr(row, "name");
            if (title.isBlank()) title = optStr(row, "title");
            String rowType = optStr(row, "type");
            String effectiveType = rowType.isBlank() ? type : rowType;
            if (id.isBlank() || title.isBlank() || (!needle.isBlank() && !title.toLowerCase(Locale.ROOT).contains(needle))) continue;
            if (isNonPlayableCatalogEntry(effectiveType, id, title)) continue;
            JSONArray embeddedStreams = row.optJSONArray("streams");
            if (embeddedStreams != null && embeddedStreams.length() > 0 && inlineCatalogStreams.size() < 512) {
                inlineCatalogStreams.put(effectiveType.toLowerCase(Locale.ROOT) + ":" + id, embeddedStreams);
                inlineCatalogStreams.put(id, embeddedStreams);
            }
            String poster = optStr(row, "poster");
            if (poster.isBlank()) poster = optStr(row, "logo");
            if (poster.isBlank()) poster = optStr(row, "thumbnail");
            String posterShape = optStr(row, "posterShape");
            String imdb = id.matches("tt[0-9]+") ? id : rawImdb;
            out.putIfAbsent(effectiveType + ":" + id, new Poster(id, effectiveType, title, poster,
                    optStr(row, "background"), optStr(row, "description"),
                    year(optStr(row, "releaseInfo").isBlank() ? optStr(row, "year") : optStr(row, "releaseInfo")),
                    rating(row.optDouble("imdbRating", 0.0)), date(optStr(row, "released")), row.optJSONArray("genres"),
                    imdb, tmdbNum, posterShape));
        }
    }

    private static boolean isNonPlayableCatalogEntry(String type, String id, String title) {
        String cleanTitle = title == null ? "" : title.trim();
        String lowerTitle = cleanTitle.toLowerCase(Locale.ROOT);
        String lowerId = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (lowerId.contains("premium") && lowerTitle.contains("upgrade to premium")) return true;
        if (lowerTitle.startsWith("upgrade to premium") || lowerTitle.contains("go premium")) return true;
        if (isLiveChannelType(type)) {
            // Skip section separator headers such as "-=Iran =-" or "--- Sports ---"
            if (cleanTitle.matches("^[-=_*•·~#\\s]{2,}.*[-=_*•·~#\\s]{2,}$")) return true;
        }
        return false;
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
        var typesToTry = new ArrayList<String>();
        typesToTry.add(metaType);
        if (!"series".equalsIgnoreCase(metaType)) typesToTry.add("series");
        if ("series".equalsIgnoreCase(metaType) && id != null && id.startsWith("kitsu:")) typesToTry.add("anime");

        for (String tryType : typesToTry) {
            String metaPath = "/meta/" + Uri.encode(tryType) + "/" + Uri.encode(id, ":") + ".json";
            var urls = new ArrayList<String>();
            if (addon != null) urls.add(resourceUrl(addon, metaPath));
            if (id != null && id.startsWith("kitsu:")) {
                String kitsuUrl = "https://anime-kitsu.strem.fun" + metaPath;
                if (!urls.contains(kitsuUrl)) urls.add(kitsuUrl);
            }
            if (id != null && id.matches("tt[0-9]+")) {
                String cineUrl = "https://v3-cinemeta.strem.io" + metaPath;
                if (!urls.contains(cineUrl)) urls.add(cineUrl);
            }
            for (String url : urls) {
                if (Thread.currentThread().isInterrupted()) return List.of();
                try {
                    JSONObject meta = new JSONObject(fetchText(url)).optJSONObject("meta");
                    var episodes = parseEpisodes(meta == null ? null : meta.optJSONArray("videos"));
                    if (!episodes.isEmpty()) return episodes;
                } catch (Exception ignored) {}
            }
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

    private static List<StreamSubtitle> parseInlineSubtitles(JSONArray arr) {
        if (arr == null || arr.length() == 0) return List.of();
        var out = new ArrayList<StreamSubtitle>();
        for (int i = 0; i < Math.min(arr.length(), 24); i++) {
            JSONObject row = arr.optJSONObject(i);
            if (row == null) continue;
            String url = optStr(row, "url");
            if (!isHttpUrl(url)) continue;
            out.add(new StreamSubtitle(optStr(row, "id"), url, optStr(row, "lang")));
        }
        return out;
    }

    /**
     * Fetches English/original title, IMDb ID, and release year hints from a Stremio add-on's
     * /meta/{type}/{id}.json endpoint (e.g. Persian Stremio returns title_en, title_fa, releaseInfo).
     * Falls back cleanly to extracting any Latin slug from custom IDs (e.g. iplocal_catalog___safar-be-limoonia).
     */
    public static AddonMetaHint fetchAddonMetaHint(Addon addon, String mediaType, String id) {
        String titleEn = "";
        String titleFa = "";
        String imdbId = (id != null && id.matches("tt[0-9]+")) ? id : "";
        int year = -1;
        String baseMetaId = stripEpisodeSuffixFromId(id);
        if (id != null && !id.isBlank()) {
            String metaType = (mediaType == null || mediaType.isBlank()) ? "movie" : mediaType;
            var metaUrls = new ArrayList<String>();
            if (addon != null && addon.baseUrl != null && !addon.baseUrl.isBlank()) {
                metaUrls.add(resourceUrl(addon, "/meta/" + Uri.encode(metaType) + "/" + Uri.encode(baseMetaId, ":") + ".json"));
                if ("anime".equalsIgnoreCase(metaType)) {
                    metaUrls.add(resourceUrl(addon, "/meta/series/" + Uri.encode(baseMetaId, ":") + ".json"));
                }
            }
            if (baseMetaId.startsWith("kitsu:")) {
                metaUrls.add("https://anime-kitsu.strem.fun/meta/anime/" + Uri.encode(baseMetaId, ":") + ".json");
                metaUrls.add("https://anime-kitsu.strem.fun/meta/series/" + Uri.encode(baseMetaId, ":") + ".json");
            }
            for (String metaUrl : metaUrls) {
                try {
                    JSONObject meta = new JSONObject(fetchText(metaUrl)).optJSONObject("meta");
                    if (meta != null) {
                        titleEn = optStr(meta, "title_en");
                        titleFa = optStr(meta, "title_fa");
                        String metaName = optStr(meta, "name");
                        if (titleEn.isBlank() && !metaName.isBlank() && !UnifiedSubtitleEngine.containsNonLatin(metaName)) {
                            titleEn = metaName;
                        }
                        String foundImdb = optStr(meta, "imdb_id");
                        if (foundImdb.matches("tt[0-9]+")) imdbId = foundImdb;
                        String rel = optStr(meta, "releaseInfo");
                        if (rel.isBlank()) rel = optStr(meta, "year");
                        var ym = Pattern.compile("(19|20)\\d{2}").matcher(rel);
                        if (ym.find()) {
                            try { year = Integer.parseInt(ym.group()); } catch (Exception ignored) {}
                        }
                        if (!imdbId.isBlank() || !titleEn.isBlank()) break;
                    }
                } catch (Exception ignored) {}
            }
        }
        if (titleEn.isBlank() && id != null && id.contains("___")) {
            String[] segs = id.split("___");
            if (segs.length >= 2) {
                String slug = segs[1].replaceAll("[-_]+", " ").trim();
                if (!slug.isBlank() && !UnifiedSubtitleEngine.containsNonLatin(slug)) {
                    titleEn = slug;
                }
            }
        }
        return new AddonMetaHint(titleEn, titleFa, imdbId, year);
    }

    private static String stripEpisodeSuffixFromId(String id) {
        if (id == null) return "";
        if (id.matches("(?i)kitsu:[0-9]+:[0-9]+")) {
            int lastColon = id.lastIndexOf(':');
            return id.substring(0, lastColon);
        }
        if (id.matches("(?i)(?:tt[0-9]+|tmdb:[0-9]+):[0-9]+:[0-9]+")) {
            int last = id.lastIndexOf(':');
            int prev = id.lastIndexOf(':', last - 1);
            return prev > 0 ? id.substring(0, prev) : id;
        }
        return id;
    }

    public static String buildSubtitleExtraSegment(String filename, long videoSize, String videoHash) {
        var parts = new ArrayList<String>(3);
        if (videoHash != null && videoHash.trim().matches("(?i)[0-9a-f]{16}")) {
            parts.add("videoHash=" + videoHash.trim().toLowerCase(Locale.ROOT));
        }
        if (videoSize > 0L) {
            parts.add("videoSize=" + videoSize);
        }
        if (filename != null && !filename.isBlank()) {
            String leaf = filename.trim().replace('\\', '/');
            int slash = leaf.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < leaf.length()) leaf = leaf.substring(slash + 1);
            if (!leaf.isBlank() && leaf.length() <= 240) {
                parts.add("filename=" + Uri.encode(leaf));
            }
        }
        return String.join("&", parts);
    }

    /**
     * Queries a specific Stremio add-on's /subtitles/{type}/{itemId}[/{extra}].json directly, supporting
     * custom idPrefixes (such as Persian Stremio's "ip..." IDs or Kitsu IDs) as well as standard IMDb IDs.
     */
    public static List<StreamSubtitle> fetchAddonSubtitles(String addonBaseUrl, String mediaType, String itemId) {
        return fetchAddonSubtitles(addonBaseUrl, mediaType, itemId, "", 0L, "");
    }

    public static List<StreamSubtitle> fetchAddonSubtitles(String addonBaseUrl, String mediaType, String itemId,
                                                           String filename, long videoSize, String videoHash) {
        if (addonBaseUrl == null || addonBaseUrl.isBlank() || itemId == null || itemId.isBlank()) return List.of();
        Uri parsed = Uri.parse(addonBaseUrl);
        String query = parsed.getEncodedQuery();
        String pathPart = parsed.getEncodedPath();
        if (pathPart != null && pathPart.endsWith("/manifest.json")) {
            pathPart = pathPart.substring(0, pathPart.length() - "/manifest.json".length());
        } else if (pathPart != null && pathPart.endsWith("/")) {
            pathPart = pathPart.substring(0, pathPart.length() - 1);
        }
        String base = parsed.buildUpon().encodedPath(pathPart == null ? "" : pathPart).clearQuery().fragment(null).build().toString();
        String querySuffix = (query == null || query.isEmpty()) ? "" : "?" + query;
        String type = ("series".equalsIgnoreCase(mediaType) || "episode".equalsIgnoreCase(mediaType)
                || "anime".equalsIgnoreCase(mediaType)) ? "series" : "movie";
        String extra = buildSubtitleExtraSegment(filename, videoSize, videoHash);
        String encodedId = Uri.encode(itemId, ":");
        if (!extra.isEmpty()) {
            try {
                String urlWithExtra = base + "/subtitles/" + type + "/" + encodedId + "/" + extra + ".json" + querySuffix;
                var withExtra = parseInlineSubtitles(new JSONObject(fetchText(urlWithExtra)).optJSONArray("subtitles"));
                if (!withExtra.isEmpty()) return withExtra;
            } catch (Exception ignored) {}
        }
        try {
            String plainUrl = base + "/subtitles/" + type + "/" + encodedId + ".json" + querySuffix;
            return parseInlineSubtitles(new JSONObject(fetchText(plainUrl)).optJSONArray("subtitles"));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public static List<String> arabicSubtitleUrls(Context context, String imdb, int season, int episode) {
        return subtitleUrls(context, imdb, season, episode, "ar", "", 0L, "");
    }

    public static List<String> subtitleUrls(Context context, String imdb, int season, int episode, String languageCode) {
        return subtitleUrls(context, imdb, season, episode, languageCode, "", 0L, "");
    }

    public static List<String> subtitleUrls(Context context, String imdb, int season, int episode, String languageCode,
                                            String filename, long videoSize, String videoHash) {
        if (imdb == null || (!imdb.matches("tt[0-9]+") && !imdb.startsWith("kitsu:"))) return List.of();
        String target = languageCode == null || languageCode.isBlank() ? "ar" : languageCode.trim().toLowerCase(Locale.ROOT);
        var urls = new java.util.LinkedHashSet<String>();
        String mediaId;
        if (imdb.startsWith("kitsu:")) {
            String baseKitsu = stripEpisodeSuffixFromId(imdb);
            mediaId = episode > 0 ? baseKitsu + ":" + episode : baseKitsu;
        } else {
            mediaId = episode > 0 ? imdb + ":" + Math.max(1, season) + ":" + episode : imdb;
        }
        String type = episode > 0 ? "series" : "movie";
        String extra = buildSubtitleExtraSegment(filename, videoSize, videoHash);
        for (Addon addon : list(context).stream().filter(item -> item.enabled && providesSubtitles(item)).limit(8).collect(java.util.stream.Collectors.toList())) {
            if (Thread.currentThread().isInterrupted()) return List.of();
            if (!capabilities(addon).subtitles()) continue;
            JSONArray rows = queryAddonSubtitlesRows(addon, type, mediaId, extra);
            for (int i = 0; rows != null && i < rows.length() && urls.size() < 8; i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String language = row.optString("lang").toLowerCase(Locale.ROOT);
                String url = row.optString("url");
                if (subtitleLangMatches(language, target) && isHttpUrl(url)) urls.add(url);
            }
        }
        if (urls.isEmpty() && imdb.matches("tt[0-9]+") && !Thread.currentThread().isInterrupted()) {
            ZeroConfigSubtitles.search(imdb, episode > 0, season, episode, target).stream().limit(6)
                    .map(subtitle -> subtitle.url).filter(StremioAddons::isHttpUrl).forEach(urls::add);
        }
        return List.copyOf(urls);
    }

    public static JSONArray queryAddonSubtitlesRows(Addon addon, String type, String mediaId, String extraSegment) {
        return queryAddonSubtitlesRowsFast(addon, type, mediaId, extraSegment, 3800, 4500,
                System.currentTimeMillis() + 5200L);
    }

    public static JSONArray queryAddonSubtitlesRowsFast(Addon addon, String type, String mediaId, String extraSegment,
                                                        int connectMs, int readMs, long deadlineEpochMs) {
        if (addon == null || mediaId == null || mediaId.isBlank()) return null;
        String encodedId = Uri.encode(mediaId, ":");
        if (extraSegment != null && !extraSegment.isEmpty() && System.currentTimeMillis() < deadlineEpochMs - 300L) {
            try {
                int rem = (int) Math.max(800L, Math.min(readMs, deadlineEpochMs - System.currentTimeMillis()));
                String pathWithExtra = "/subtitles/" + type + "/" + encodedId + "/" + extraSegment + ".json";
                JSONArray arr = new JSONObject(fetchSubtitleEndpointText(
                        resourceUrl(addon, pathWithExtra), Math.min(connectMs, rem), rem)).optJSONArray("subtitles");
                if (arr != null && arr.length() > 0) return arr;
            } catch (Throwable ignored) {}
        }
        if (System.currentTimeMillis() >= deadlineEpochMs - 200L) return null;
        try {
            int rem = (int) Math.max(800L, Math.min(readMs, deadlineEpochMs - System.currentTimeMillis()));
            String plainPath = "/subtitles/" + type + "/" + encodedId + ".json";
            return new JSONObject(fetchSubtitleEndpointText(
                    resourceUrl(addon, plainPath), Math.min(connectMs, rem), rem)).optJSONArray("subtitles");
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static JSONArray querySubtitleEndpointByUrlFast(String manifestUrl, String baseUrl, String type,
                                                           String mediaId, String extraSegment,
                                                           int connectMs, int readMs, long deadlineEpochMs) {
        if ((manifestUrl == null || manifestUrl.isBlank()) && (baseUrl == null || baseUrl.isBlank())) return null;
        String resolvedBase = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : baseUrlFromManifest(manifestUrl);
        Addon temp = new Addon(manifestUrl != null ? manifestUrl : (resolvedBase + "/manifest.json"),
                resolvedBase, "", "Stremio Subtitle Add-on", "", "1.0", true, 0L, SUBTITLES, "");
        return queryAddonSubtitlesRowsFast(temp, type, mediaId, extraSegment, connectMs, readMs, deadlineEpochMs);
    }

    private static String fetchSubtitleEndpointText(String rawUrl, int connectMs, int readMs) throws IOException {
        String current = rawUrl;
        for (int hop = 0; hop < 4; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setConnectTimeout(Math.max(800, connectMs));
            conn.setReadTimeout(Math.max(800, readMs));
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) LocalCinema/1.0");
            conn.setRequestProperty("Accept", "application/json");
            try {
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isBlank()) throw new IOException("HTTP " + code);
                    current = new URL(new URL(current), loc.trim()).toString();
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);
                try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (out.size() + n > 2 * 1024 * 1024) throw new IOException("Subtitle JSON too large");
                        out.write(buf, 0, n);
                    }
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private static boolean subtitleLangMatches(String lang, String target) {
        if (lang == null || lang.isBlank()) return false;
        if (target.isEmpty()) return true;
        if (lang.equals(target) || lang.startsWith(target + "-") || lang.startsWith(target + "_")) return true;
        return switch (target) {
            case "ar", "ara", "arabic" -> lang.equals("ar") || lang.equals("ara") || lang.equals("arabic") || lang.startsWith("ar-");
            case "en", "eng", "english" -> lang.equals("en") || lang.equals("eng") || lang.equals("english") || lang.startsWith("en-");
            case "fr", "fre", "fra", "french" -> lang.equals("fr") || lang.equals("fre") || lang.equals("fra") || lang.equals("french") || lang.startsWith("fr-");
            case "es", "spa", "spanish" -> lang.equals("es") || lang.equals("spa") || lang.equals("spanish") || lang.startsWith("es-");
            case "de", "ger", "deu", "german" -> lang.equals("de") || lang.equals("ger") || lang.equals("deu") || lang.equals("german") || lang.startsWith("de-");
            case "tr", "tur", "turkish" -> lang.equals("tr") || lang.equals("tur") || lang.equals("turkish") || lang.startsWith("tr-");
            case "fa", "per", "fas", "persian" -> lang.equals("fa") || lang.equals("per") || lang.equals("fas") || lang.equals("persian") || lang.startsWith("fa-");
            case "ku", "kur", "kurdish" -> lang.equals("ku") || lang.equals("kur") || lang.equals("kurdish") || lang.startsWith("ku-");
            default -> lang.contains(target);
        };
    }

    private static boolean supportedType(String type) {
        return "movie".equalsIgnoreCase(type) || "series".equalsIgnoreCase(type)
                || "anime".equalsIgnoreCase(type) || isLiveChannelType(type);
    }

    public static List<Stream> resolveStreams(Context context, String mediaType, String id, int season, int episode) {
        return resolveStreams(context, mediaType, id, "", season, episode, null, batch -> {});
    }

    public static List<Stream> resolveStreams(Context context, String mediaType, String id, int season, int episode, Addon only) {
        return resolveStreams(context, mediaType, id, "", season, episode, only, batch -> {});
    }

    public static List<Stream> resolveStreams(Context context, String mediaType, String id, int season, int episode,
                                               Addon only, java.util.function.Consumer<List<Stream>> partial) {
        return resolveStreams(context, mediaType, id, "", season, episode, only, partial);
    }

    public static List<Stream> resolveStreams(Context context, String mediaType, String id, String fallbackImdbId,
                                               int season, int episode, Addon only,
                                               java.util.function.Consumer<List<Stream>> partial) {
        if (context == null || id == null || id.isBlank() || id.length() > 240) return List.of();
        boolean liveType = isLiveChannelType(mediaType) || (only != null && isLiveChannelAddon(only));
        boolean series = !liveType && ("series".equalsIgnoreCase(mediaType) || "episode".equalsIgnoreCase(mediaType)
                || ("anime".equalsIgnoreCase(mediaType) && episode > 0) || episode > 0);
        String type;
        if (liveType) {
            type = (mediaType != null && isLiveChannelType(mediaType)) ? mediaType.toLowerCase(Locale.ROOT) : "tv";
        } else if ("episode".equalsIgnoreCase(mediaType)) {
            type = "series";
        } else if (mediaType != null && !mediaType.isBlank()) {
            type = mediaType.toLowerCase(Locale.ROOT);
        } else {
            type = series ? "series" : "movie";
        }

        String primaryStreamId = buildEpisodeStreamId(id, series, season, episode);
        String primaryPath = "/stream/" + Uri.encode(type) + "/" + Uri.encode(primaryStreamId, ":") + ".json";
        String rawCustomPath = (series && !primaryStreamId.equals(id))
                ? ("/stream/" + Uri.encode(type) + "/" + Uri.encode(id, ":") + ".json") : null;

        String effectiveImdb = (fallbackImdbId != null && fallbackImdbId.matches("tt[0-9]+")) ? fallbackImdbId
                : (id.matches("tt[0-9]+") ? id : "");
        if (effectiveImdb.isEmpty() && !liveType && id.startsWith("kitsu:")) {
            effectiveImdb = fetchAddonMetaHint(only, type, id).imdbId();
        }
        String imdbType = series ? "series" : "movie";
        String imdbStreamId = effectiveImdb.isEmpty() ? "" : buildEpisodeStreamId(effectiveImdb, series, season, episode);
        String imdbPath = imdbStreamId.isEmpty() ? ""
                : ("/stream/" + Uri.encode(imdbType) + "/" + Uri.encode(imdbStreamId, ":") + ".json");

        Addon targetOnly = (only != null && (mayStream(only) || liveType)) ? only : null;
        List<Addon> candidates;
        if (liveType && targetOnly != null) {
            candidates = List.of(targetOnly);
        } else {
            candidates = list(context).stream().filter(item -> item.enabled
                    && !isRetiredBrokenAddon(item.id, item.name, item.manifestUrl)
                    && (liveType ? (isLiveChannelAddon(item) || mayStream(item)) : mayStream(item))
                    && (targetOnly == null || targetOnly.manifestUrl.equalsIgnoreCase(item.manifestUrl)))
                    .collect(java.util.stream.Collectors.toList());
        }

        record AddonQuery(Addon addon, String type, String streamId, String path, String altPath) {}
        var queries = new ArrayList<AddonQuery>();
        for (Addon addon : candidates) {
            if (liveType) {
                if (targetOnly != null || isLiveChannelAddon(addon)
                        || acceptsStream(addon, type, primaryStreamId)
                        || acceptsStream(addon, "tv", primaryStreamId)
                        || acceptsStream(addon, "channel", primaryStreamId)) {
                    queries.add(new AddonQuery(addon, type, primaryStreamId, primaryPath, null));
                }
                continue;
            }
            // Ensure manifest rules are known when bridging non-IMDb IDs (e.g. kitsu:, tmdb:, custom)
            if (!primaryStreamId.startsWith("tt") && !manifestRules.containsKey(addon.manifestUrl)) {
                capabilities(addon);
            }
            boolean acceptsPrimary = acceptsStream(addon, type, primaryStreamId)
                    || ("anime".equalsIgnoreCase(type) && acceptsStream(addon, series ? "series" : "movie", primaryStreamId));
            if (acceptsPrimary) {
                String usePath = !"anime".equalsIgnoreCase(type) || acceptsStream(addon, "anime", primaryStreamId)
                        ? primaryPath
                        : ("/stream/" + (series ? "series" : "movie") + "/" + Uri.encode(primaryStreamId, ":") + ".json");
                queries.add(new AddonQuery(addon, type, primaryStreamId, usePath, rawCustomPath));
            } else if (!imdbPath.isEmpty() && acceptsStream(addon, imdbType, imdbStreamId)) {
                queries.add(new AddonQuery(addon, imdbType, imdbStreamId, imdbPath, null));
            } else if (targetOnly != null) {
                queries.add(new AddonQuery(addon, type, primaryStreamId, primaryPath, rawCustomPath));
            }
        }

        var statuses = new java.util.concurrent.ConcurrentHashMap<String, StreamHealth>();
        var tasks = queries.stream().<java.util.concurrent.Callable<List<Stream>>>map(q -> () -> {
            long started = elapsedMillis();
            var batch = streamsFrom(q.addon(), q.type(), q.streamId(), q.path(), q.altPath());
            var issue = connectionIssue(q.addon());
            var state = new StreamHealth(q.addon().name, elapsedMillis() - started, batch.size(), issue);
            statuses.put(q.addon().manifestUrl, state);
            streamHealth.put(q.addon().manifestUrl, state);
            return batch;
        }).collect(java.util.stream.Collectors.toList());
        var results = new ArrayList<Stream>();
        parallel(tasks, batch -> {
            if (batch.isEmpty()) return;
            results.addAll(batch);
            partial.accept(interleaveProviders(results));
        }, elapsedMillis() + STREAM_BUDGET_MS);
        if (!Thread.currentThread().isInterrupted()) {
            for (AddonQuery q : queries) {
                if (!statuses.containsKey(q.addon().manifestUrl)) {
                    streamHealth.put(q.addon().manifestUrl, new StreamHealth(q.addon().name, STREAM_BUDGET_MS, 0, true));
                }
            }
        }
        return interleaveProviders(results);
    }

    private static String buildEpisodeStreamId(String id, boolean series, int season, int episode) {
        if (!series || id == null || id.isBlank()) return id == null ? "" : id;
        int s = Math.max(1, season);
        int e = Math.max(1, episode);
        String seSuffix = ":" + s + ":" + e;
        if (id.endsWith(seSuffix) || id.endsWith("___" + s + ":" + e)) return id;
        if (id.matches("(?i)kitsu:[0-9]+:[0-9]+")) return id;
        if (id.matches("(?i)kitsu:[0-9]+")) return id + ":" + e;
        if (id.matches("(?i)(?:tt[0-9]+|tmdb:[0-9]+):[0-9]+:[0-9]+")) return id;
        if (id.matches("tt[0-9]+") || id.matches("(?i)tmdb:[0-9]+")) return id + seSuffix;
        // Custom add-on video IDs: if they already end with :<ep> or have an explicit episode suffix, keep as-is
        if (id.matches(".*:[0-9]+:[0-9]+$") || id.matches("(?i).*(?:_ep|-ep|:ep)[0-9]+$")) return id;
        return id + seSuffix;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, JSONObject> manifestRules =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, AddonHealthReport> healthCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long HEALTH_TTL_MS = 180_000L;

    private static boolean acceptsStream(Addon addon, String type, String id) {
        JSONObject manifest = manifestRules.get(addon.manifestUrl);
        if (manifest == null) return true;
        JSONArray resources = manifest.optJSONArray("resources");
        for (int i = 0; resources != null && i < resources.length(); i++) {
            JSONObject resource = resources.optJSONObject(i);
            String rName = resource == null ? resources.optString(i) : resource.optString("name");
            if (!"stream".equals(rName)) continue;
            JSONArray types = resource != null && resource.has("types") ? resource.optJSONArray("types") : manifest.optJSONArray("types");
            JSONArray prefixes = resource != null && resource.has("idPrefixes")
                    ? resource.optJSONArray("idPrefixes") : manifest.optJSONArray("idPrefixes");
            if (matchesManifest(types, type, false) && matchesManifest(prefixes, id, true)) return true;
        }
        return false;
    }

    private static boolean matchesManifest(JSONArray values, String value, boolean prefix) {
        if (values == null || values.length() == 0) return true;
        String lowerVal = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (int i = 0; i < values.length(); i++) {
            String rule = values.optString(i);
            if (rule == null || rule.isBlank()) continue;
            String lowerRule = rule.toLowerCase(Locale.ROOT);
            if (prefix ? lowerVal.startsWith(lowerRule) : lowerVal.equals(lowerRule)) return true;
        }
        return false;
    }

    public static StreamHealth testMovie(Addon addon) {
        long started = elapsedMillis();
        if (!mayStream(addon)) return new StreamHealth(addon.name, 0, 0, false);
        providerDeadline.set(started + STREAM_BUDGET_MS);
        try {
            var links = streamsFrom(addon, "movie", "tt0063350", "/stream/movie/tt0063350.json", null);
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

    private static int streamSortScore(Stream s) {
        if (!s.torrent()) {
            // Direct HTTP/HLS streams get first-class priority above unseeded/low-seed torrents
            int bonus = 250;
            if (s.quality != null) {
                String q = s.quality.toUpperCase(Locale.ROOT);
                if (q.contains("2160P") || q.contains("4K")) bonus += 40;
                else if (q.contains("1080P")) bonus += 30;
                else if (q.contains("720P")) bonus += 15;
            }
            return bonus;
        }
        return s.seeds;
    }

    private static List<Stream> interleaveProviders(List<Stream> found) {
        var sorted = new ArrayList<>(found);
        sorted.sort(java.util.Comparator.comparingInt(StremioAddons::streamSortScore).reversed());
        var featured = new ArrayList<Stream>(sorted.size());
        var shown = new java.util.HashSet<String>();
        for (Stream stream : sorted) {
            if (shown.add(stream.addonName)) featured.add(stream);
        }
        sorted.removeAll(featured);
        featured.addAll(sorted);
        return List.copyOf(featured);
    }

    private static List<Stream> streamsFrom(Addon addon, String type, String itemId, String path, String altPath) {
        var results = new ArrayList<Stream>();
        if (isRetiredBrokenAddon(addon.id, addon.name, addon.manifestUrl)) {
            streamDenied.put(addon.manifestUrl, elapsedMillis() + 300_000);
            return results;
        }
        JSONArray streams = fetchStreamsJsonArray(addon, path);
        if ((streams == null || streams.length() == 0) && altPath != null && !altPath.equals(path)) {
            streams = fetchStreamsJsonArray(addon, altPath);
        }
        if ((streams == null || streams.length() == 0) && isLiveChannelType(type)) {
            String altLiveType = "tv".equalsIgnoreCase(type) ? "channel" : "tv";
            streams = fetchStreamsJsonArray(addon, "/stream/" + altLiveType + "/" + Uri.encode(itemId, ":") + ".json");
            if (streams == null || streams.length() == 0) {
                streams = fetchMetaEmbeddedStreams(addon, type, itemId);
            }
            if (streams == null || streams.length() == 0) {
                streams = fetchMetaEmbeddedStreams(addon, altLiveType, itemId);
            }
            if (streams == null || streams.length() == 0) {
                streams = inlineCatalogStreams.get((type == null ? "" : type.toLowerCase(Locale.ROOT)) + ":" + itemId);
                if (streams == null) streams = inlineCatalogStreams.get(itemId);
            }
        }
        parseStreamsArray(addon, streams, results);
        return results;
    }

    private static JSONArray fetchStreamsJsonArray(Addon addon, String path) {
        try {
            JSONArray streams = new JSONObject(fetchText(resourceUrl(addon, path))).optJSONArray("streams");
            streamDenied.remove(addon.manifestUrl);
            unreachable.remove(addon.manifestUrl);
            return streams;
        } catch (HttpFailure failure) {
            if (failure.code == 401 || failure.code == 403 || failure.code == 410 || failure.code == 429) {
                streamDenied.put(addon.manifestUrl, elapsedMillis() + 300_000);
            }
        } catch (java.net.UnknownHostException | java.net.ConnectException unreachableHost) {
            unreachable.put(addon.manifestUrl, elapsedMillis() + 300_000);
        } catch (Exception failed) {
            if (failed instanceof java.io.InterruptedIOException) return null;
            unreachable.put(addon.manifestUrl, elapsedMillis() + 60_000);
        }
        return null;
    }

    private static JSONArray fetchMetaEmbeddedStreams(Addon addon, String type, String itemId) {
        try {
            JSONObject meta = new JSONObject(fetchText(resourceUrl(addon,
                    "/meta/" + Uri.encode(type) + "/" + Uri.encode(itemId, ":") + ".json"))).optJSONObject("meta");
            if (meta == null) return null;
            JSONArray direct = meta.optJSONArray("streams");
            if (direct != null && direct.length() > 0) return direct;
            JSONObject singleStream = meta.optJSONObject("stream");
            if (singleStream != null) return new JSONArray().put(singleStream);
            JSONArray videos = meta.optJSONArray("videos");
            if (videos != null) {
                JSONArray combined = new JSONArray();
                for (int i = 0; i < Math.min(videos.length(), 20); i++) {
                    JSONObject v = videos.optJSONObject(i);
                    if (v == null) continue;
                    JSONArray vs = v.optJSONArray("streams");
                    if (vs != null) {
                        for (int j = 0; j < vs.length(); j++) combined.put(vs.opt(j));
                    } else if (!optStr(v, "url").isBlank() || !optStr(v, "streamUrl").isBlank()) {
                        combined.put(v);
                    }
                }
                if (combined.length() > 0) return combined;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void parseStreamsArray(Addon addon, JSONArray streams, List<Stream> results) {
        for (int i = 0; streams != null && i < Math.min(streams.length(), 80); i++) {
            JSONObject row = streams.optJSONObject(i);
            if (row == null) continue;
            String url = optStr(row, "url");
            if (url.isBlank()) url = optStr(row, "streamUrl");
            if (url.isBlank()) {
                String ext = optStr(row, "externalUrl");
                if (isTorrentMagnet(ext) || isDirectMediaExternalUrl(ext)) {
                    url = ext;
                }
            }
            var extraHeaders = new LinkedHashMap<String, String>();
            String streamUa = "";
            String streamReferer = "";

            // Support Kodi / IPTV pipe-appended HTTP headers: https://host/live.m3u8|User-Agent=...&Referer=...
            if (!url.isBlank() && !url.startsWith("magnet:?") && url.contains("|")) {
                int pipeIdx = url.indexOf('|');
                String pipeParams = url.substring(pipeIdx + 1).trim();
                url = url.substring(0, pipeIdx).trim();
                for (String pair : pipeParams.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) continue;
                    String hk = Uri.decode(pair.substring(0, eq).trim());
                    String hv = Uri.decode(pair.substring(eq + 1).trim());
                    if (hk.isEmpty() || hv.isEmpty() || hk.contains("\r") || hk.contains("\n")
                            || hv.contains("\r") || hv.contains("\n")) continue;
                    extraHeaders.put(hk, hv);
                    if ("user-agent".equalsIgnoreCase(hk)) streamUa = hv;
                    else if ("referer".equalsIgnoreCase(hk) || "referrer".equalsIgnoreCase(hk)) streamReferer = hv;
                }
            }

            String hash = row.optString("infoHash").trim();
            if (url.isBlank() && hash.matches("(?i)[a-f0-9]{40}|[a-z2-7]{32}")) {
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
                if (filePath != null && filePath.toLowerCase(Locale.ROOT).matches(".*/(health|configure|status)$")) continue;
            }
            String rawName = row.optString("name");
            String rawDesc = row.optString("title", row.optString("description"));
            String title = (rawName + " " + rawDesc).replace('\n', ' ').trim();
            String lowerTitle = title.toLowerCase(Locale.ROOT);
            if (!"torrent".equals(format) && (lowerTitle.startsWith("tip:") || lowerTitle.contains("use an external player")
                    || lowerTitle.contains("unlock premium") || lowerTitle.contains("go premium"))) {
                continue;
            }
            JSONObject hints = row.optJSONObject("behaviorHints");
            collectJsonHeaders(row.optJSONObject("headers"), extraHeaders);
            if (hints != null) {
                collectJsonHeaders(hints.optJSONObject("headers"), extraHeaders);
                JSONObject proxyHeaders = hints.optJSONObject("proxyHeaders");
                if (proxyHeaders != null) {
                    collectJsonHeaders(proxyHeaders.optJSONObject("request"), extraHeaders);
                }
                String hintUa = optStr(hints, "userAgent");
                if (!hintUa.isBlank()) extraHeaders.putIfAbsent("User-Agent", hintUa);
                String hintRef = optStr(hints, "referer");
                if (!hintRef.isBlank()) extraHeaders.putIfAbsent("Referer", hintRef);
            }
            String rowUa = optStr(row, "userAgent");
            if (!rowUa.isBlank()) extraHeaders.putIfAbsent("User-Agent", rowUa);
            String rowRef = optStr(row, "referer");
            if (!rowRef.isBlank()) extraHeaders.putIfAbsent("Referer", rowRef);
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if ("user-agent".equalsIgnoreCase(e.getKey())) streamUa = e.getValue();
                else if ("referer".equalsIgnoreCase(e.getKey()) || "referrer".equalsIgnoreCase(e.getKey())) streamReferer = e.getValue();
            }

            String filename = hints == null ? "" : optStr(hints, "filename");
            String videoHash = hints == null ? "" : optStr(hints, "videoHash");
            long size = row.optLong("size", hints == null ? 0 : hints.optLong("videoSize"));
            if (size <= 0) size = displaySize(row.optString("size") + " " + title + " " + filename);
            String metadata = title + " " + filename;
            String languages = row.optString("language", row.optString("languages", ""));
            List<StreamSubtitle> streamSubs = parseInlineSubtitles(row.optJSONArray("subtitles"));
            results.add(new Stream(addon.name, title, extractQuality(metadata), url, format,
                    Math.max(-1, row.optInt("fileIdx", -1)), Math.max(0, size), seeds(row, hints, title),
                    extractLanguages(languages + " " + metadata), streamUa, streamReferer, extraHeaders,
                    streamSubs, filename, videoHash));
        }
    }

    private static boolean isDirectMediaExternalUrl(String ext) {
        if (!isHttpUrl(ext)) return false;
        String lower = ext.toLowerCase(Locale.ROOT);
        if (lower.contains("/configure") || lower.endsWith(".html") || lower.endsWith(".htm")) return false;
        return lower.matches(".*\\.(m3u8|mpd|ts|mp4|mkv|webm|avi|mov|m4v)(\\?.*)?$")
                || lower.contains("archive.org/download/");
    }

    private static void collectJsonHeaders(JSONObject obj, Map<String, String> out) {
        if (obj == null) return;
        var keys = obj.keys();
        while (keys.hasNext() && out.size() < 24) {
            String k = keys.next();
            if (k == null || k.isBlank() || k.contains("\r") || k.contains("\n")) continue;
            String v = optStr(obj, k);
            if (v.isBlank() || v.length() > 2048 || v.contains("\r") || v.contains("\n")) continue;
            out.put(k.trim(), v.trim());
        }
    }

    public static boolean connectionIssue(Addon addon) {
        if (addon == null) return false;
        if (isRetiredBrokenAddon(addon.id, addon.name, addon.manifestUrl)) return true;
        AddonHealthReport cached = healthCache.get(addon.manifestUrl);
        if (cached != null && cached.state() == AddonHealthState.OFFLINE) return true;
        long now = elapsedMillis();
        return now < unreachable.getOrDefault(addon.manifestUrl, 0L)
                || now < streamDenied.getOrDefault(addon.manifestUrl, 0L);
    }

    public static AddonHealthState cachedHealthState(Addon addon) {
        if (addon == null) return AddonHealthState.UNKNOWN;
        if (isRetiredBrokenAddon(addon.id, addon.name, addon.manifestUrl)) return AddonHealthState.OFFLINE;
        AddonHealthReport report = healthCache.get(addon.manifestUrl);
        if (report != null && (elapsedMillis() - report.checkedAt()) < HEALTH_TTL_MS) {
            return report.state();
        }
        long now = elapsedMillis();
        if (now < unreachable.getOrDefault(addon.manifestUrl, 0L)) return AddonHealthState.OFFLINE;
        if (now < streamDenied.getOrDefault(addon.manifestUrl, 0L)) return AddonHealthState.DEGRADED;
        return report != null ? report.state() : AddonHealthState.UNKNOWN;
    }

    public static AddonHealthReport probeAddonHealth(Addon addon, boolean force) {
        if (addon == null) return new AddonHealthReport(AddonHealthState.UNKNOWN, 0L, "", elapsedMillis());
        long now = elapsedMillis();
        if (isRetiredBrokenAddon(addon.id, addon.name, addon.manifestUrl)) {
            var dead = new AddonHealthReport(AddonHealthState.OFFLINE, 0L, "Discontinued at source", now);
            healthCache.put(addon.manifestUrl, dead);
            return dead;
        }
        if (!force) {
            AddonHealthReport cached = healthCache.get(addon.manifestUrl);
            if (cached != null && (now - cached.checkedAt()) < HEALTH_TTL_MS) return cached;
        }
        long started = elapsedMillis();
        Long prevDeadline = providerDeadline.get();
        providerDeadline.set(started + 5500L);
        try {
            String raw = fetchText(addon.manifestUrl);
            JSONObject manifest = new JSONObject(raw);
            String mId = manifest.optString("id", "").trim();
            int mask = resourceMask(manifest);
            if (mId.isEmpty() && mask == 0) {
                throw new IOException("Invalid Stremio manifest");
            }
            manifestRules.put(addon.manifestUrl, manifest);
            unreachable.remove(addon.manifestUrl);
            long elapsed = elapsedMillis() - started;
            AddonHealthState state = (mask == 0 || elapsedMillis() < streamDenied.getOrDefault(addon.manifestUrl, 0L))
                    ? AddonHealthState.DEGRADED : AddonHealthState.HEALTHY;
            var okReport = new AddonHealthReport(state, elapsed, "OK", elapsedMillis());
            healthCache.put(addon.manifestUrl, okReport);
            return okReport;
        } catch (HttpFailure httpErr) {
            long elapsed = elapsedMillis() - started;
            AddonHealthState state = (httpErr.code == 429 || httpErr.code == 401 || httpErr.code == 403)
                    ? AddonHealthState.DEGRADED : AddonHealthState.OFFLINE;
            if (state == AddonHealthState.OFFLINE) {
                unreachable.put(addon.manifestUrl, elapsedMillis() + 300_000);
            }
            var errReport = new AddonHealthReport(state, elapsed, "HTTP " + httpErr.code, elapsedMillis());
            healthCache.put(addon.manifestUrl, errReport);
            return errReport;
        } catch (Exception failure) {
            long elapsed = elapsedMillis() - started;
            unreachable.put(addon.manifestUrl, elapsedMillis() + 180_000);
            var errReport = new AddonHealthReport(AddonHealthState.OFFLINE, elapsed,
                    failure.getClass().getSimpleName(), elapsedMillis());
            healthCache.put(addon.manifestUrl, errReport);
            return errReport;
        } finally {
            if (prevDeadline == null) providerDeadline.remove();
            else providerDeadline.set(prevDeadline);
        }
    }

    public static Map<String, AddonHealthState> probeAllAddonsHealth(List<Addon> addons, boolean force) {
        var result = new java.util.concurrent.ConcurrentHashMap<String, AddonHealthState>();
        if (addons == null || addons.isEmpty()) return result;
        var enabled = addons.stream().filter(a -> a != null && a.enabled).collect(java.util.stream.Collectors.toList());
        if (enabled.isEmpty()) return result;
        var tasks = enabled.stream().<java.util.concurrent.Callable<Map.Entry<String, AddonHealthState>>>map(addon -> () -> {
            AddonHealthReport r = probeAddonHealth(addon, force);
            return new java.util.AbstractMap.SimpleImmutableEntry<>(addon.manifestUrl, r.state());
        }).collect(java.util.stream.Collectors.toList());
        parallel(tasks, entry -> result.put(entry.getKey(), entry.getValue()), elapsedMillis() + 6500L);
        for (Addon addon : enabled) {
            result.putIfAbsent(addon.manifestUrl, cachedHealthState(addon) == AddonHealthState.UNKNOWN
                    ? AddonHealthState.DEGRADED : cachedHealthState(addon));
        }
        return result;
    }

    public record AddonTiming(String name, boolean ok, long millis) {}

    public static List<AddonTiming> probeAddons(List<Addon> addons) {
        var report = new java.util.LinkedHashMap<Addon, AddonTiming>();
        var tasks = addons.stream().<java.util.concurrent.Callable<Map.Entry<Addon, AddonTiming>>>map(addon -> () -> {
            AddonHealthReport r = probeAddonHealth(addon, true);
            boolean ok = r.state() == AddonHealthState.HEALTHY;
            return new java.util.AbstractMap.SimpleImmutableEntry<>(addon, new AddonTiming(addon.name, ok, r.millis()));
        }).collect(java.util.stream.Collectors.toList());
        parallel(tasks, result -> report.put(result.getKey(), result.getValue()), elapsedMillis() + 6500L);
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
        String lowerInitial = current.toLowerCase(Locale.ROOT);
        boolean playlist = "hls".equals(stream.format) || "dash".equals(stream.format)
                || lowerInitial.contains(".m3u8") || lowerInitial.contains(".mpd");
        boolean knownDirectMedia = playlist
                || lowerInitial.matches(".*\\.(mp4|mkv|webm|avi|mov|m4v|ts)(\\?.*)?$")
                || lowerInitial.contains("archive.org/download/");
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(current).openConnection();
                connection.setConnectTimeout(5500);
                connection.setReadTimeout(5500);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent",
                        stream.userAgent != null && !stream.userAgent.isBlank() ? stream.userAgent : USER_AGENT);
                if (stream.referer != null && !stream.referer.isBlank()) {
                    connection.setRequestProperty("Referer", stream.referer);
                }
                if (stream.headers != null) {
                    for (Map.Entry<String, String> entry : stream.headers.entrySet()) {
                        String k = entry.getKey();
                        String v = entry.getValue();
                        if (k == null || v == null || k.isBlank() || v.isBlank()) continue;
                        if ("user-agent".equalsIgnoreCase(k) || "referer".equalsIgnoreCase(k) || "referrer".equalsIgnoreCase(k)) continue;
                        connection.setRequestProperty(k, v);
                    }
                }
                if (!playlist) connection.setRequestProperty("Range", "bytes=0-1023");
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String next = connection.getHeaderField("Location");
                    if (next == null || next.isBlank()) return knownDirectMedia && hop > 0;
                    current = new URL(new URL(current), next).toString();
                    if (!isHttpUrl(current)) return false;
                    String lowerNext = current.toLowerCase(Locale.ROOT);
                    if (lowerNext.contains(".m3u8") || lowerNext.contains(".mpd")) playlist = true;
                    if (lowerNext.matches(".*\\.(mp4|mkv|webm|avi|mov|m4v|ts)(\\?.*)?$")) knownDirectMedia = true;
                    continue;
                }
                if (status == 404 || status == 410 || status >= 500) {
                    return false;
                }
                if (status != 200 && status != 206) {
                    return (playlist || knownDirectMedia) && (status == 405 || status == 416 || status == 400);
                }
                String mime = connection.getContentType();
                if (mime == null) return true;
                mime = mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (mime.contains("mpegurl") || mime.contains("dash+xml") || mime.equals("video/mp2t")) return true;
                return !mime.equals("text/html") && !mime.equals("application/xhtml+xml")
                        && !mime.equals("application/json") && !mime.endsWith("+json")
                        && !mime.startsWith("image/") && !mime.startsWith("audio/")
                        && !mime.equals("application/zip") && !mime.equals("application/x-bittorrent");
            } catch (java.net.SocketTimeoutException timeout) {
                return playlist || knownDirectMedia;
            } catch (IOException | IllegalArgumentException failure) {
                return (playlist || knownDirectMedia) && hop > 0;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return knownDirectMedia;
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
            connection.setRequestProperty("Accept", "application/json, application/xml, text/xml, */*;q=0.8");
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

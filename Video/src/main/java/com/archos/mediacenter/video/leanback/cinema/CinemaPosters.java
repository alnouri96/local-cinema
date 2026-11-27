package com.archos.mediacenter.video.leanback.cinema;

import com.meedx.providers.OpenMetadata;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.AtomicFile;
import android.util.Log;

import com.archos.mediacenter.video.browser.adapters.object.Base;
import com.archos.mediacenter.video.browser.adapters.object.Episode;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Tvshow;
import com.archos.mediacenter.video.browser.adapters.object.Video;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** Repairs missing library artwork on a serial queue shared by all poster screens. */
final class CinemaPosters {
    static final String TAG = "CinemaPosters";
    static final String REFRESH = "cinema_poster_refresh";
    static final ExecutorService QUEUE = Executors.newFixedThreadPool(5);
    private static final String IMAGE = "https://image.tmdb.org/t/p/";
    private static final Pattern RELEASE_TAG = Pattern.compile(
            "(?i)\\b(?:[0-9]{3,4}p|4k|uhd|fhd|hd|sd|web[- ]?(?:dl|rip)?|bluray|bdrip|hdrip|brrip|remux|hybrid|dovi|dv|dolby|vision|hdr(?:10)?(?:\\+)?|[xh]\\.?26[45]|hevc|avc|av1|xvid|divx|aac|truehd|dts(?:-?hd)?|ma|flac|ddp?5?\\.?1|ac3|atmos|amzn|nf|dsnp|atvp|hmax|pcok|criterion|10bit|8bit|extended|unrated|theatrical|imax|multi|proper|dub|sub|dubbed|subbed|repack|rerip|internal|limited|dual|eng|ara|arabic|ita)\\b");
    private static final Pattern EPISODE_TAG = Pattern.compile(
            "(?i)\\b(?:S\\d{1,2}\\s*E\\d{1,3}(?:[-E]\\d{1,3})?|S\\d{1,2}\\b|\\d{1,2}x\\d{1,3}|Season\\s*\\d+|Episode\\s*\\d+|Ep\\.?\\s*\\d+|الموسم\\s*\\d*|الحلقة\\s*\\d*)");
    private static final Pattern YEAR = Pattern.compile("(?<![\\p{L}\\d])((?:18|19|20)\\d{2})(?![\\p{L}\\d])");
    private static final android.util.LruCache<String, Query> PARSE_CACHE = new android.util.LruCache<>(2048);
    private static final android.util.LruCache<String, Query> URI_QUERY_CACHE = new android.util.LruCache<>(2048);
    private static final android.util.LruCache<String, String> CLEAN_TITLE_CACHE = new android.util.LruCache<>(2048);
    private static final android.util.LruCache<String, int[]> SE_CACHE = new android.util.LruCache<>(2048);
    private static long nextRequest;

    record Query(String title, int year, boolean show) {}
    record Choice(long id, String title, String year, String path, boolean matches,
                  String imdbId, String backdrop, String provider, int parsedYear) {
        Choice(long id, String title, String year, String path, boolean matches) {
            this(id, title, year, path, matches, "", null, "TMDb", parseYear(year));
        }
        private static int parseYear(String y) {
            try { return y != null && y.length() >= 4 ? Integer.parseInt(y.substring(0, 4)) : 0; }
            catch (NumberFormatException ignored) { return 0; }
        }
        String preview() { return path.startsWith("https://") ? path : IMAGE + "w185" + path; }
    }

    private CinemaPosters() {}

    static Query query(Base item) {
        return query(item, item instanceof Tvshow || item instanceof Episode
                || CinemaHomeFragment.isSeriesOrEpisodeFile(item, null));
    }

    static Query query(Base item, boolean show) {
        String name = item instanceof Episode episode ? episode.getShowName() : item.getName();
        int year = item instanceof Movie movie ? movie.getYear() : item instanceof Tvshow series ? series.getYear() : 0;
        Uri fileUri = item instanceof Video video ? video.getFileUri()
                : (item instanceof Tvshow tvshow ? tvshow.getFileUri() : null);
        if (fileUri != null) {
            Query file = parseFromUri(fileUri, year, show);
            if (year <= 0) year = file.year();
            if (name == null || name.isBlank()
                    || show
                    || (OpenMetadata.containsArabic(name) && !file.title().isBlank() && !OpenMetadata.containsArabic(file.title()))) {
                if (!file.title().isBlank()) name = file.title();
            }
        }
        return parse(name, year, show);
    }

    private static final java.util.regex.Pattern SEASON_DIR_ONLY =
            java.util.regex.Pattern.compile("(?i)^(?:season|s|series|الموسم)\\s*0*(\\d{1,2})$");
    private static final java.util.regex.Pattern EPISODE_OR_RELEASE_DIR =
            java.util.regex.Pattern.compile("(?i)(?:\\bS\\d{1,2}[._\\s-]*E\\d{1,3}\\b|\\b\\d{1,2}x\\d{1,3}\\b|\\b(?:Episode|Ep)\\.?\\s*\\d+\\b|\\b(?:2160|1440|1080|720|480)p\\b|\\bWEB[- .]?DL\\b|\\bWEBRip\\b|\\bBlu[- .]?Ray\\b|\\bHEVC\\b|[HX][. ]?26[45]|DoVi|Atmos|AMZN)");
    private static final java.util.regex.Pattern EP_NUMBER_ONLY =
            java.util.regex.Pattern.compile("(?i)(?:\\bS(\\d{1,2})[ ._x-]*E(\\d{1,3})\\b|\\b(\\d{1,2})x(\\d{1,3})\\b|\\b(?:Episode|Ep)\\.?\\s*0*(\\d{1,3})\\b|الحلقة\\s*0*(\\d{1,3}))");
    private static final java.util.regex.Pattern GENERIC_LIBRARY_DIR =
            java.util.regex.Pattern.compile("(?i)^(?:tv\\s*shows?|series|shows|movies?|cartoons?|anime|animation|video|downloads?|المسلسلات|مسلسلات|الأفلام|الكارتون)$");

    static int[] extractSeasonEpisode(String filePath, String name) {
        int[] found = findSeasonEpisode(filePath, name);
        return new int[]{Math.max(1, found[0]), Math.max(1, found[1])};
    }

    static int[] findSeasonEpisode(String filePath, String name) {
        String cacheKey = (filePath == null ? "" : filePath) + "\n" + (name == null ? "" : name);
        int[] cached = SE_CACHE.get(cacheKey);
        if (cached != null) return cached;
        int season = 0;
        int episode = 0;
        String parentSeg = null;
        if (filePath != null) {
            List<String> segs = Uri.parse(filePath).getPathSegments();
            if (segs != null && segs.size() >= 2) {
                parentSeg = Uri.decode(segs.get(segs.size() - 2));
            }
        }
        String[] candidates = { name, parentSeg, filePath != null ? Uri.decode(filePath) : null };
        for (String c : candidates) {
            if (c == null) continue;
            var m = EP_NUMBER_ONLY.matcher(c);
            if (m.find()) {
                if (m.group(1) != null && m.group(2) != null) {
                    season = Integer.parseInt(m.group(1));
                    episode = Integer.parseInt(m.group(2));
                    break;
                } else if (m.group(3) != null && m.group(4) != null) {
                    season = Integer.parseInt(m.group(3));
                    episode = Integer.parseInt(m.group(4));
                    break;
                } else if (m.group(5) != null && episode <= 0) {
                    episode = Integer.parseInt(m.group(5));
                } else if (m.group(6) != null && episode <= 0) {
                    episode = Integer.parseInt(m.group(6));
                }
            }
        }
        if (season <= 0 && filePath != null) {
            List<String> segs = Uri.parse(filePath).getPathSegments();
            if (segs != null) {
                for (int i = segs.size() - 2; i >= 0; i--) {
                    String seg = Uri.decode(segs.get(i));
                    var sm = SEASON_DIR_ONLY.matcher(seg.trim());
                    if (sm.matches()) {
                        season = Integer.parseInt(sm.group(1));
                        break;
                    }
                }
            }
        }
        int[] resolved = new int[]{season, episode};
        SE_CACHE.put(cacheKey, resolved);
        return resolved;
    }

    static String seriesRootPath(String filePath) {
        if (filePath == null || filePath.isBlank()) return null;
        int lastSlash = filePath.lastIndexOf('/');
        if (lastSlash <= 0) return null;
        String dir = filePath.substring(0, lastSlash);
        while (true) {
            int prevSlash = dir.lastIndexOf('/');
            if (prevSlash <= 0) break;
            String dirName = Uri.decode(dir.substring(prevSlash + 1)).trim();
            if (dirName.isBlank()) break;
            if (GENERIC_LIBRARY_DIR.matcher(dirName).matches()) return null;
            if (SEASON_DIR_ONLY.matcher(dirName).matches() || EPISODE_OR_RELEASE_DIR.matcher(dirName).find()) {
                dir = dir.substring(0, prevSlash);
                continue;
            }
            return dir.endsWith("/") ? dir : dir + "/";
        }
        return null;
    }

    static String cleanDisplayTitle(Base item, boolean arabicMode) {
        if (item == null) return "";
        String raw = item.getName() == null ? "" : item.getName();
        Uri fileUri = item instanceof Video v ? v.getFileUri() : (item instanceof Tvshow s ? s.getFileUri() : null);
        String cacheKey = (arabicMode ? "1|" : "0|")
                + (item instanceof Tvshow ? "S|" : (item instanceof Episode ? "E|" : "V|"))
                + raw + "|" + (fileUri != null ? fileUri.toString() : "");
        String cached = CLEAN_TITLE_CACHE.get(cacheKey);
        if (cached != null) return cached;
        String parsed = query(item).title();
        String result = raw;
        if (!arabicMode && OpenMetadata.containsArabic(raw)) {
            if (!parsed.isBlank() && !OpenMetadata.containsArabic(parsed)) result = parsed;
        } else if (!parsed.isBlank() && (raw.contains("..") || RELEASE_TAG.matcher(raw.replace('.', ' ')).find()
                || raw.matches(".*\\.(mp4|mkv|avi|mov|m4v|webm|ts|wmv)$"))) {
            result = parsed;
        }
        CLEAN_TITLE_CACHE.put(cacheKey, result);
        return result;
    }

    private static Query parseFromUri(Uri uri, int year, boolean show) {
        if (uri == null) return new Query("", year, show);
        String cacheKey = (show ? "1|" : "0|") + year + "|" + uri.toString();
        Query cached = URI_QUERY_CACHE.get(cacheKey);
        if (cached != null) return cached;
        List<String> segments = uri.getPathSegments();
        Query last = parse(uri.getLastPathSegment(), year, show);
        if (!last.title().isBlank() && !last.title().matches("(?i)^(?:e?p?\\s*\\d+|s\\d+.*)$") && segments != null) {
            URI_QUERY_CACHE.put(cacheKey, last);
            return last;
        }
        if (segments != null) {
            for (int i = segments.size() - 2; i >= 0; i--) {
                String seg = segments.get(i);
                if (seg == null || seg.matches("(?i)^(?:season\\s*\\d+|s\\d+|movies?|tv\\s*shows?|series|cartoon|anime|الأفلام|المسلسلات|الكارتون)$")) {
                    continue;
                }
                Query parent = parse(seg, year, show);
                if (!parent.title().isBlank()) {
                    URI_QUERY_CACHE.put(cacheKey, parent);
                    return parent;
                }
            }
        }
        URI_QUERY_CACHE.put(cacheKey, last);
        return last;
    }

    static Query parse(String name, int year, boolean show) {
        String cacheKey = (show ? "1|" : "0|") + year + "|" + (name == null ? "" : name);
        Query cached = PARSE_CACHE.get(cacheKey);
        if (cached != null) return cached;
        String baseName = com.meedx.providers.UnifiedSubtitleEngine.normalizePipesAndBidi(name == null ? "" : name);
        if (baseName.contains("|")) {
            var datesInRaw = YEAR.matcher(baseName);
            if (year <= 0 && datesInRaw.find()) {
                try { year = Integer.parseInt(datesInRaw.group(1)); } catch (Exception ignored) {}
            }
            List<String> pipeSegs = com.meedx.providers.UnifiedSubtitleEngine.extractCleanPipeSegments(baseName);
            if (!pipeSegs.isEmpty()) {
                String chosen = pipeSegs.get(0);
                for (String s : pipeSegs) {
                    if (!OpenMetadata.containsArabic(s)) { chosen = s; break; }
                }
                baseName = chosen;
            }
        }
        String title = baseName.replaceFirst("(?i)\\.(mp4|mkv|avi|mov|m4v|webm|ts|wmv)$", "")
                .replaceAll("\\[[^\\]]*\\]|\\{[^}]*\\}", " ")
                .replaceAll("[._]+", " ").strip();
        var epMatch = EPISODE_TAG.matcher(title);
        if (epMatch.find() && epMatch.start() > 0) {
            title = title.substring(0, epMatch.start()).strip();
        }
        var release = RELEASE_TAG.matcher(title);
        if (release.find() && release.start() > 0) title = title.substring(0, release.start()).strip();
        var dates = YEAR.matcher(title);
        int start = -1, end = -1;
        while (dates.find()) {
            String before = title.substring(0, dates.start()).replaceAll("[\\s(\\[-]+$", "");
            String after = title.substring(dates.end()).trim();
            if (!before.isBlank() && (after.matches("[\\s)\\]-]*") || RELEASE_TAG.matcher(after).find())) {
                start = dates.start();
                end = dates.end();
            }
        }
        if (start >= 0) {
            if (year <= 0) year = Integer.parseInt(title.substring(start, end));
            title = title.substring(0, start);
        }
        var release2 = RELEASE_TAG.matcher(title);
        if (release2.find() && release2.start() > 0) title = title.substring(0, release2.start()).strip();
        title = title.replaceAll("[\\s(\\[-]+$", "").replaceAll("\\s+", " ").strip();
        Query parsed = new Query(title, year, show);
        PARSE_CACHE.put(cacheKey, parsed);
        return parsed;
    }

    static boolean valid(Uri uri) {
        if (uri == null || !"file".equals(uri.getScheme()) || uri.getPath() == null) return false;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(uri.getPath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return false;
        options.inJustDecodeBounds = false;
        options.inSampleSize = 8;
        Bitmap bitmap = BitmapFactory.decodeFile(uri.getPath(), options);
        if (bitmap == null) return false;
        bitmap.recycle();
        return true;
    }

    static List<Choice> search(Context context, Query query, long onlineId, boolean manual) throws Exception {
        if (query.title().isBlank()) return List.of();
        if (OpenMetadata.enabled(context)) {
            OpenMetadata.Item item = OpenMetadata.lookup(context, new OpenMetadata.Query(query.title(), query.year(), query.show()), null, null, 0, 0);
            if ((item == null || item.poster() == null) && "ar".equals(OpenMetadata.resolveLang(context))) {
                item = OpenMetadata.lookup(context, new OpenMetadata.Query(query.title(), query.year(), query.show()), null, null, 0, 0, "en");
            }
            if (item != null && item.poster() != null) {
                int y = 0;
                try { if (item.year() != null && !item.year().isBlank()) y = Integer.parseInt(item.year()); } catch (NumberFormatException ignored) {}
                return List.of(new Choice(0, item.title(), item.year(), item.poster(), true, item.imdb(), item.backdrop(), item.attribution(), y));
            }
            if (com.meedx.providers.ProviderKeys.get(context, com.meedx.providers.ProviderKeys.TMDB).isBlank()) {
                return List.of();
            }
        }
        String type = query.show() ? "tv" : "movie";
        List<Choice> choices = new ArrayList<>();
        if (onlineId > 0) {
            try {
                JSONObject details = json(context, type + "/" + onlineId, "");
                add(choices, details, query, true);
                if (manual) {
                    JSONArray images = json(context, type + "/" + onlineId + "/images", "&include_image_language="+MetadataLanguage.images(MetadataLanguage.resolve(context))).optJSONArray("posters");
                    if (images != null) for (int i = 0; i < images.length() && choices.size() < 8; i++) {
                        JSONObject image = images.optJSONObject(i);
                        if (image == null) continue;
                        String path = image.optString("file_path");
                        if (imagePath(path) && choices.stream().noneMatch(choice -> choice.path().equals(path))) {
                            choices.add(new Choice(onlineId, query.title(), query.year() > 0
                                    ? Integer.toString(query.year()) : "", path, true));
                        }
                    }
                }
            } catch (HttpFailure error) {
                if (error.status != 404) throw error;
            }
            if (!manual && !choices.isEmpty()) return choices;
        }
        if (query.year() > 0) {
            search(context, query, type, query.title(), query.year(), choices);
            if (!manual && choices.stream().anyMatch(Choice::matches)) return choices;
        }
        search(context, query, type, query.title(), 0, choices);
        String[] words = query.title().split(" ");
        if (words.length > 2 && choices.stream().noneMatch(Choice::matches)) {
            for (int count = Math.min(3, words.length - 1); count >= 2; count--) {
                search(context, query, type, String.join(" ", java.util.Arrays.copyOf(words, count)), 0, choices);
                if (choices.stream().anyMatch(Choice::matches)) break;
            }
        }
        choices.sort(Comparator.comparing(Choice::matches).reversed()
                .thenComparing(choice -> !choice.year().equals(Integer.toString(query.year()))));
        return choices;
    }

    private static void search(Context c, Query query, String type, String title, int year, List<Choice> choices)
            throws Exception {
        String yearArg = query.show() ? "first_air_date_year" : "year";
        JSONArray results = json(c, "search/" + type, "&query=" + Uri.encode(title)
                + (year > 0 ? "&" + yearArg + "=" + year : "")).optJSONArray("results");
        if (results != null) for (int i = 0; i < results.length(); i++) {
            JSONObject result = results.optJSONObject(i);
            if (result != null) add(choices, result, query, false);
        }
    }

    private static void add(List<Choice> choices, JSONObject result, Query query, boolean trusted) {
        String path = result.optString("poster_path");
        if (!imagePath(path) || choices.stream().anyMatch(choice -> choice.path().equals(path))) return;
        String title = result.optString(query.show() ? "name" : "title");
        String original = result.optString(query.show() ? "original_name" : "original_title");
        String date = result.optString(query.show() ? "first_air_date" : "release_date");
        String normalized = normalize(query.title());
        choices.add(new Choice(result.optLong("id"), title, date.length() >= 4 ? date.substring(0, 4) : "",
                path, trusted || normalized.equals(normalize(title)) || normalized.equals(normalize(original))));
    }

    private static String normalize(String title) {
        return title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static boolean imagePath(String path) {
        return path.matches("/[a-zA-Z0-9_-]+\\.(jpg|jpeg|png)");
    }

    static Uri save(Context context, String key, Uri source) throws Exception {
        byte[] bytes = request(source.toString(), 10 * 1024 * 1024);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while ((bounds.outWidth / sample) > 600 || (bounds.outHeight / sample) > 900) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (decoded == null) throw new IOException("Invalid poster image");
        Bitmap bitmap = decoded;
        if (decoded.getWidth() > 300 || decoded.getHeight() > 450) {
            float scale = Math.min(300f / decoded.getWidth(), 450f / decoded.getHeight());
            int tw = Math.max(1, Math.round(decoded.getWidth() * scale));
            int th = Math.max(1, Math.round(decoded.getHeight() * scale));
            bitmap = Bitmap.createScaledBitmap(decoded, tw, th, true);
            if (bitmap != decoded) decoded.recycle();
        }
        try {
            File dir = new File(context.getFilesDir(), "posters");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create poster directory");
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte value : hash) name.append(String.format(Locale.ROOT, "%02x", value & 255));
            File file = new File(dir, name + ".jpg");
            AtomicFile output = new AtomicFile(file);
            FileOutputStream stream = output.startWrite();
            try {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)) throw new IOException("Poster encode failed");
                output.finishWrite(stream);
            } catch (IOException | RuntimeException error) {
                output.failWrite(stream);
                throw error;
            }
            Uri result = Uri.fromFile(file);
            CinemaArtwork.putCachedPoster(context, key, result);
            return result;
        } finally {
            bitmap.recycle();
        }
    }

    static Uri save(Context context, String key, Choice choice) throws Exception {
        return save(context, key, Uri.parse(choice.path().startsWith("https://") ? choice.path() : IMAGE + "w500" + choice.path()));
    }

    private static JSONObject json(Context c, String path, String query) throws Exception {
        String key = com.meedx.providers.ProviderKeys.require(c, com.meedx.providers.ProviderKeys.TMDB);
        return new JSONObject(new String(request("https://api.themoviedb.org/3/" + path
                + "?api_key=" + Uri.encode(key) + "&language="+Uri.encode(MetadataLanguage.resolve(c)) + query, 2 * 1024 * 1024), StandardCharsets.UTF_8));
    }

    private static byte[] request(String url, int limit) throws Exception {
        for (int attempt = 0; ; attempt++) {
            long waitMs;
            synchronized (CinemaPosters.class) {
                long now = android.os.SystemClock.elapsedRealtime();
                waitMs = Math.max(0, nextRequest - now);
                nextRequest = Math.max(now, nextRequest) + 50;
            }
            if (waitMs > 0) Thread.sleep(waitMs);
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(7000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Local-Cinema/1.0");
            long delay = 500L << attempt;
            try {
                int status = connection.getResponseCode();
                if (status != 200) throw new HttpFailure(status);
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    for (int n; (n = in.read(buffer)) != -1; ) {
                        if (out.size() + n > limit) throw new IOException("Poster response too large");
                        out.write(buffer, 0, n);
                    }
                    return out.toByteArray();
                }
            } catch (SocketTimeoutException | HttpFailure error) {
                if (attempt == 2 || error instanceof HttpFailure failure
                        && failure.status != 429 && failure.status < 500) throw error;
                if (error instanceof HttpFailure failure && failure.status == 429) {
                    long now = System.currentTimeMillis();
                    delay = Math.max(delay, SubsourceClient.parseRetryAfter(connection.getHeaderField("Retry-After"), now) - now);
                    nextRequest = Math.max(nextRequest, android.os.SystemClock.elapsedRealtime() + delay);
                }
                Log.i(TAG, "Retrying poster request, attempt " + (attempt + 2));
            } finally {
                connection.disconnect();
            }
            Thread.sleep(delay);
        }
    }

    private static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }
}

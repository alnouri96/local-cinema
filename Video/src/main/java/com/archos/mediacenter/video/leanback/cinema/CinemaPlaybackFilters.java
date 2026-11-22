package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;

import org.json.JSONObject;

import com.archos.mediaprovider.video.VideoStore;
import com.archos.mediascraper.BaseTags;
import com.archos.mediacenter.video.utils.PlayUtils;
import androidx.media3.common.Metadata;
import androidx.media3.extractor.metadata.id3.ChapterFrame;
import androidx.media3.extractor.metadata.id3.TextInformationFrame;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.preference.PreferenceManager;

import com.archos.filecorelibrary.FileEditorFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

/** Resolves skip timings for the current media and applies the enabled playback filters. */
public final class CinemaPlaybackFilters {
    private static final int MAX_BYTES = 128 * 1024;
    private static final java.util.concurrent.ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final String TIMESTAMP = "(\\d{2,3}:[0-5]\\d:[0-5]\\d\\.\\d{3})";
    private static final Pattern MCF_CUE = Pattern.compile("(?m)^" + TIMESTAMP + " +--> +" + TIMESTAMP
            + " *\\n[\\w-]+=(?:high|medium|low)(?:=(?:both|audio|video))?(?: +#[^\\n]*)? *$");

    private record Segment(int start, int end) {}
    private record Identity(long videoId, String imdbId, String episodeId, boolean episode, int season, int number, long tmdbId) {}
    private record Timeline(List<Segment> scenes, List<Segment> breaks, long runtimeMs) {}
    private record MediaIds(String imdb, String episodeImdb, boolean episode, int season, int number) {}

    private static final String SKIP_DB = "https://api.skipdb.tv/api/segments";
    private static final String INTRO_DB = "https://api.theintrodb.org/v3/media";
    private static final long CACHE_WEEK_MS = 7 * 24 * 60 * 60 * 1000L;
    private static final long CACHE_HOUR_MS = 60 * 60 * 1000L;
    private static final Pattern BREAK_CHAPTER = Pattern.compile(
            "^(?:ad(?:s|vert(?:s|isements?)?)?(?: break)?|commercials?(?: break)?|sponsor(?:ed)?(?: segment| break)?"
                    + "|intermission|promos?|preview|next time|intro|opening(?: credits)?)(?: [0-9]+)?$");

    private static final String CLEANSTREAM = "https://cleanstream.elfhosted.com/api/filters/";
    private static final String MOVIE_HAVEN =
            "https://raw.githubusercontent.com/arman-kh/MovieHavenDB/master/movies/";
    private static final Pattern SUBTITLE_TIME = Pattern.compile(
            "(?m)^((?:\\d{1,3}:)?[0-5]\\d:[0-5]\\d[.,]\\d{3})[ \\t]+-->[ \\t]+"
                    + "((?:\\d{1,3}:)?[0-5]\\d:[0-5]\\d[.,]\\d{3})[^\\n]*\\n");
    private static final Pattern MATURE_ANNOTATION = Pattern.compile(
            "\\b(?:kiss(?:es|ing)|kissing sounds|passionate kiss|making love|having sex|"
                    + "sexual moan(?:s|ing)?|moan(?:s|ing)? (?:in pleasure|passionately)|sexual activity|"
                    + "clothes (?:are )?removed|(?:visibly |fully )?naked)\\b"
                    + "|(?:^|\\s)(?:يتبادلان القبل|يتبادلون القبل|يقبلها|تقبله|صوت قبلات|أصوات قبلات"
                    + "|أنين جنسي|أنين من اللذة|يمارسان الجنس|عري)(?=\\s|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ANNOTATION = Pattern.compile("[\\[(]([^\\]\\)\\r\\n]{2,100})[\\])]");
    private static final Timeline EMPTY = new Timeline(List.of(), List.of(), 0);
    private static final int MAX_CACHE_ENTRIES = 2048;

    private final Context context;
    private final SharedPreferences prefs;
    private final SharedPreferences timingCache;
    private final IntSupplier position;
    private final IntSupplier duration;
    private final BooleanSupplier playing;
    private final IntConsumer seek;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable check = this::checkPosition;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged = (store, key) -> {
        if (CinemaPlaybackOption.FAMILY_WATCH.key.equals(key) || CinemaPlaybackOption.SKIP_ADS.key.equals(key)) reload();
    };
    private Uri media;
    private Identity identity;
    private Timeline online = EMPTY;
    private Timeline movieHaven = EMPTY;
    private List<Segment> subtitleFile = List.of();
    private final List<Segment> observedCues = new ArrayList<>();
    private int lastUntimedCueAt = -10_000;
    private MediaIds resolvedIds;
    private boolean needsDurationLookup;
    private List<Segment> skipDb = List.of();
    private List<CinemaEmbeddedChapters.Chapter> fileChapters = List.of();
    private final List<Segment> timedChapters = new ArrayList<>();
    private boolean resumed;
    private int generation;
    private int lastSkipEnd = -1;
    private List<Segment> family = List.of();
    private List<Segment> ads = List.of();

    public CinemaPlaybackFilters(Context context, IntSupplier position, IntSupplier duration,
                                 BooleanSupplier playing, IntConsumer seek) {
        this.context = context.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        timingCache = this.context.getSharedPreferences("cinema_timing_cache", Context.MODE_PRIVATE);
        this.position = position;
        this.duration = duration;
        this.playing = playing;
        this.seek = seek;
    }

    public void setMedia(Uri uri, long videoId, Intent intent) {
        int season = intent == null ? 0 : intent.getIntExtra("season", 0);
        int episode = intent == null ? 0 : intent.getIntExtra("episode", 0);
        String imdb = intent == null ? null : intent.getStringExtra("imdb_id");
        String episodeImdb = intent == null ? null : intent.getStringExtra("episode_imdb_id");
        if (intent != null) {
            if (!validImdbId(imdb)) imdb = intent.getStringExtra(PlayUtils.EXTRA_EPISODE_SHOW_IMDB);
            int index = intent.getIntExtra(PlayUtils.EXTRA_EPISODE_INDEX, -1);
            int[] seasons = intent.getIntArrayExtra(PlayUtils.EXTRA_EPISODE_SEASONS);
            int[] episodes = intent.getIntArrayExtra(PlayUtils.EXTRA_EPISODE_NUMBERS);
            if (index >= 0 && seasons != null && episodes != null && index < seasons.length && index < episodes.length) {
                season = seasons[index];
                episode = episodes[index];
            }
        }
        boolean series = episode > 0 || intent != null && "episode".equals(intent.getStringExtra("media_type"));
        var key = new Identity(videoId, imdb, episodeImdb, series, season, episode,
                intent == null ? 0 : intent.getLongExtra("tmdb_id", 0));
        if (Objects.equals(media, uri) && key.equals(identity)) return;
        if (!Objects.equals(media, uri)) {
            timedChapters.clear();
            observedCues.clear();
            lastUntimedCueAt = -10_000;
        }
        media = uri;
        identity = key;
        reload();
    }

    public void resume() {
        resumed = true;
        prefs.registerOnSharedPreferenceChangeListener(settingsChanged);
        reload();
    }

    public void pause() {
        resumed = false;
        prefs.unregisterOnSharedPreferenceChangeListener(settingsChanged);
        reload();
    }

    public void addChapterMetadata(Metadata metadata) {
        if (metadata == null) return;
        for (int i = 0; i < metadata.length(); i++) {
            if (!(metadata.get(i) instanceof ChapterFrame chapter)) continue;
            for (int j = 0; j < chapter.getSubFrameCount(); j++) {
                if (chapter.getSubFrame(j) instanceof TextInformationFrame title
                        && ("TIT2".equals(title.id) || "TT2".equals(title.id))
                        && title.values.stream().anyMatch(CinemaPlaybackFilters::isBreakChapter)
                        && chapter.startTimeMs >= 0 && chapter.endTimeMs > chapter.startTimeMs) {
                    Segment segment = new Segment(chapter.startTimeMs, chapter.endTimeMs);
                    if (!timedChapters.contains(segment) && timedChapters.size() < 256) timedChapters.add(segment);
                }
            }
        }
        scheduleCheck();
    }

    /** Uses explicit scene descriptions from captions, never the ordinary spoken dialogue. */
    public void observeSubtitle(CharSequence text, long startMs, long endMs) {
        if (!resumed || !CinemaPlaybackOption.FAMILY_WATCH.enabled(context) || !describesMatureScene(text)) return;
        boolean estimated = startMs < 0 || endMs <= startMs;
        if (estimated) {
            int now = position.getAsInt();
            if (Math.abs(now - lastUntimedCueAt) < 4500) return;
            lastUntimedCueAt = now;
            startMs = now;
            endMs = Math.min((long) duration.getAsInt(), now + 2500L);
        }
        Segment cue = subtitleSegment(startMs, endMs);
        if (cue == null || observedCues.contains(cue)) return;
        if (observedCues.size() >= 256) observedCues.remove(0);
        observedCues.add(cue);
        scheduleCheck();
    }

    private static boolean describesMatureScene(CharSequence text) {
        if (text == null || text.length() == 0 || text.length() > 1000) return false;
        var descriptions = ANNOTATION.matcher(text);
        while (descriptions.find()) {
            if (MATURE_ANNOTATION.matcher(descriptions.group(1)).find()) return true;
        }
        return false;
    }

    private static Segment subtitleSegment(long start, long end) {
        if (start < 0 || end <= start || end > Integer.MAX_VALUE || end - start > 30_000) return null;
        return new Segment((int) Math.max(0, start - 400), (int) Math.min(Integer.MAX_VALUE, end + 500));
    }

    private static List<Segment> parseSubtitleScenes(String subtitle) {
        var result = new ArrayList<Segment>();
        for (String block : subtitle.replace("\r", "").split("\\n[ \\t]*\\n")) {
            if (result.size() >= 256) break;
            var cues = SUBTITLE_TIME.matcher(block);
            if (!cues.find() || !describesMatureScene(block.substring(cues.end()))) continue;
            long start = subtitleTimeMs(cues.group(1));
            long end = subtitleTimeMs(cues.group(2));
            Segment cue = subtitleSegment(start, end);
            if (cue != null) result.add(cue);
        }
        return result;
    }

    private static long subtitleTimeMs(String time) {
        String[] parts = time.replace(',', '.').split(":");
        return timestampMs(parts.length == 2 ? "00:" + time.replace(',', '.') : time.replace(',', '.'));
    }

    private void reload() {
        int ticket = ++generation;
        main.removeCallbacks(check);
        lastSkipEnd = -1;
        family = List.of();
        ads = List.of();
        online = EMPTY;
        movieHaven = EMPTY;
        subtitleFile = List.of();
        skipDb = List.of();
        fileChapters = List.of();
        resolvedIds = null;
        needsDurationLookup = false;
        boolean useFamily = CinemaPlaybackOption.FAMILY_WATCH.enabled(context);
        boolean useAds = CinemaPlaybackOption.SKIP_ADS.enabled(context);
        Uri source = media;
        Identity key = identity;
        if (!resumed || source == null || (!useFamily && !useAds)) return;
        IO.execute(() -> {
            MediaIds ids = resolveIds(key);
            main.post(() -> {
                if (generation != ticket) return;
                resolvedIds = ids;
                needsDurationLookup = useAds && validImdbId(ids.imdb)
                        && (!ids.episode || ids.season >= 0 && ids.number > 0);
                scheduleCheck();
            });
            String id = timingId(key, ids);
            Timeline cached = fetchCleanStream(id, true);
            Timeline cachedAlternate = useFamily && !ids.episode ? fetchMovieHaven(id, true) : EMPTY;
            main.post(() -> {
                if (generation != ticket) return;
                online = cached;
                movieHaven = cachedAlternate;
                scheduleCheck();
            });
            Timeline timings = fetchCleanStream(id, false);
            main.post(() -> {
                if (generation != ticket) return;
                online = timings;
                scheduleCheck();
            });
            if (useFamily && !ids.episode) {
                Timeline additional = fetchMovieHaven(id, false);
                main.post(() -> {
                    if (generation != ticket) return;
                    movieHaven = additional;
                    scheduleCheck();
                });
            }
        });
        IO.execute(() -> {
            if (useFamily) {
                List<Segment> cached = localFamily(source, true);
                main.post(() -> {
                    if (generation != ticket) return;
                    family = cached;
                    scheduleCheck();
                });
            }
            List<Segment> scenes = useFamily ? localFamily(source, false) : List.of();
            List<Segment> breaks = useAds ? parseEdl(readSidecar(source, ".edl")) : List.of();
            main.post(() -> {
                if (generation != ticket) return;
                family = scenes;
                ads = breaks;
                scheduleCheck();
            });
            if (useFamily) {
                List<Segment> detected = cachedSubtitleScenes(readSidecar(source, ".srt"));
                if (detected.isEmpty()) detected = cachedSubtitleScenes(readSidecar(source, ".vtt"));
                List<Segment> result = detected;
                main.post(() -> {
                    if (generation != ticket) return;
                    subtitleFile = result;
                    scheduleCheck();
                });
            }
            if (useAds) {
                List<CinemaEmbeddedChapters.Chapter> chapters = CinemaEmbeddedChapters.read(context, source);
                main.post(() -> {
                    if (generation != ticket) return;
                    fileChapters = chapters;
                    scheduleCheck();
                });
            }
        });
    }

    private void scheduleCheck() {
        main.removeCallbacks(check);
        if (resumed && (needsDurationLookup || !family.isEmpty() || !ads.isEmpty()
                || !online.scenes.isEmpty() || !online.breaks.isEmpty() || !skipDb.isEmpty()
                || !fileChapters.isEmpty() || !timedChapters.isEmpty() || !movieHaven.scenes.isEmpty()
                || !subtitleFile.isEmpty() || !observedCues.isEmpty())) main.post(check);
    }

    void warmLibraryEntry(long videoId, Uri uri) {
        Identity key = new Identity(videoId, null, null, false, 0, 0, 0);
        MediaIds ids = resolveIds(key);
        long tmdb = tmdbId(key, ids.episode);
        String stamp = "scan/" + videoId + "/" + ids.imdb + "/" + ids.episodeImdb
                + "/" + ids.season + "/" + ids.number + "/" + tmdb + "/" + uri.hashCode();
        JSONObject previous = readCache(stamp);
        if (previous != null && System.currentTimeMillis() - previous.optLong("at") < CACHE_HOUR_MS) return;
        localFamily(uri, false);
        String id = timingId(key, ids);
        fetchCleanStream(id, false);
        if (!ids.episode) fetchMovieHaven(id, false);
        try { writeCache(stamp, new JSONObject()); } catch (org.json.JSONException ignored) {}
    }

    private List<Segment> localFamily(Uri uri, boolean cacheOnly) {
        if (!List.of("file", "smb").contains(Objects.toString(uri.getScheme(), ""))) {
            return cacheOnly ? List.of() : parseMcf(readSidecar(uri, ".mcf"));
        }
        String key = "mcf/" + java.util.UUID.nameUUIDFromBytes(uri.toString().getBytes(StandardCharsets.UTF_8));
        if (!cacheOnly) {
            String mcf = readSidecar(uri, ".mcf");
            if (!mcf.isEmpty()) {
                try { writeCache(key, new JSONObject().put("mcf", mcf)); } catch (org.json.JSONException ignored) {}
                return parseMcf(mcf);
            }
            // Keep previously verified timing data if the share is temporarily unavailable.
        }
        JSONObject entry = readCache(key);
        JSONObject cached = entry == null ? null : entry.optJSONObject("data");
        return cached == null ? List.of() : parseMcf(cached.optString("mcf"));
    }

    private long tmdbId(Identity key, boolean episode) {
        if (key == null || key.tmdbId > 0 || key.videoId <= 0 || key.videoId >= 900_000_000L)
            return key == null ? 0 : key.tmdbId;
        String column = episode ? VideoStore.Video.VideoColumns.SCRAPER_S_ONLINE_ID
                : VideoStore.Video.VideoColumns.SCRAPER_M_ONLINE_ID;
        try (var cursor = context.getContentResolver().query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{column}, "_id=?", new String[]{Long.toString(key.videoId)}, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getLong(0) : 0;
        } catch (RuntimeException unavailable) { return 0; }
    }

    private String timingId(Identity key, MediaIds ids) {
        String id = ids.episode ? resolveEpisodeId(ids) : ids.imdb;
        if (validImdbId(id)) return id;
        long tmdb = tmdbId(key, ids.episode);
        if (tmdb <= 0 || ids.episode && (ids.season < 0 || ids.number <= 0)) return "";
        String keyValue = "tmdb/" + (ids.episode ? "tv/" + tmdb + "/" + ids.season + "/" + ids.number : "movie/" + tmdb);
        JSONObject entry = readCache(keyValue);
        JSONObject cached = entry == null ? null : entry.optJSONObject("data");
        if (cached != null && validImdbId(cached.optString("imdb_id"))) return cached.optString("imdb_id");
        String apiKey = com.meedx.providers.ProviderKeys.get(context, com.meedx.providers.ProviderKeys.TMDB);
        if (apiKey.isBlank()) return "";
        String path = ids.episode ? "tv/" + tmdb + "/season/" + ids.season + "/episode/" + ids.number : "movie/" + tmdb;
        JSONObject result = cachedResponse(keyValue, Uri.parse("https://api.themoviedb.org/3/" + path + "/external_ids")
                .buildUpon().appendQueryParameter("api_key", apiKey).build(), value -> value.has("imdb_id"));
        return result == null ? "" : result.optString("imdb_id");
    }

    private MediaIds resolveIds(Identity key) {
        if (key == null) return new MediaIds(null, null, false, 0, 0);
        var fallback = new MediaIds(key.imdbId, key.episodeId, key.episode, key.season, key.number);
        if (key.videoId <= 0 || key.videoId >= 900_000_000L) return fallback;
        var columns = new String[]{VideoStore.Video.VideoColumns.ARCHOS_MEDIA_SCRAPER_TYPE,
                VideoStore.Video.VideoColumns.SCRAPER_M_IMDB_ID, VideoStore.Video.VideoColumns.SCRAPER_E_IMDB_ID,
                VideoStore.Video.VideoColumns.SCRAPER_S_IMDB_ID, VideoStore.Video.VideoColumns.SCRAPER_E_SEASON,
                VideoStore.Video.VideoColumns.SCRAPER_E_EPISODE, VideoStore.Video.VideoColumns.SCRAPER_SHOW_ID};
        try (var cursor = context.getContentResolver().query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                columns, "_id=?", new String[]{Long.toString(key.videoId)}, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                if (cursor.getInt(0) == BaseTags.TV_SHOW && cursor.getInt(5) > 0) {
                    String showImdb = firstImdb(cursor.getString(3), key.imdbId);
                    if (!validImdbId(showImdb) && cursor.getLong(6) > 0) {
                        var show = CinemaWatchStore.getIdentity(context, "s:" + cursor.getLong(6));
                        if (show != null) showImdb = show.imdbId();
                    }
                    return new MediaIds(showImdb,
                            firstImdb(cursor.getString(2), key.episodeId), true, cursor.getInt(4), cursor.getInt(5));
                }
                if (!key.episode && cursor.getInt(0) == BaseTags.MOVIE) {
                    fallback = new MediaIds(firstImdb(cursor.getString(1), key.imdbId), null, false, 0, 0);
                }
            }
        } catch (RuntimeException ignored) {}
        if (validImdbId(fallback.imdb)) return fallback;
        var saved = CinemaWatchStore.getIdentity(context, "v:" + key.videoId);
        if (saved == null || !validImdbId(saved.imdbId())) return fallback;
        boolean episode = fallback.episode || "episode".equals(saved.mediaType());
        return new MediaIds(saved.imdbId(), fallback.episodeImdb, episode,
                fallback.episode ? fallback.season : saved.season(),
                fallback.episode ? fallback.number : saved.episode());
    }

    private String resolveEpisodeId(MediaIds ids) {
        if (validImdbId(ids.episodeImdb) && !ids.episodeImdb.equals(ids.imdb)) return ids.episodeImdb;
        if (!validImdbId(ids.imdb) || ids.season < 0 || ids.number <= 0) return "";
        String key = "episode/" + ids.imdb + "/" + ids.season + "/" + ids.number;
        JSONObject cached = readCache(key);
        JSONObject saved = cached == null ? null : cached.optJSONObject("data");
        if (saved != null && validImdbId(saved.optString("id"))) return saved.optString("id");
        String id = com.meedx.providers.TraktSyncClient.findEpisodeImdbId(context, ids.imdb, ids.season, ids.number);
        if (validImdbId(id) && !id.equals(ids.imdb)) {
            try { writeCache(key, new JSONObject().put("id", id)); } catch (org.json.JSONException ignored) {}
            return id;
        }
        return "";
    }

    private static String firstImdb(String primary, String fallback) {
        return validImdbId(primary) ? primary : fallback;
    }

    private static boolean validImdbId(String id) {
        return id != null && id.matches("tt[0-9]{5,12}");
    }

    private JSONObject readCache(String key) {
        try {
            return new JSONObject(timingCache.getString(key, ""));
        } catch (org.json.JSONException ignored) {
            return null;
        }
    }

    private List<Segment> cachedSubtitleScenes(String subtitle) {
        if (subtitle.isEmpty()) return List.of();
        try {
            byte[] fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(subtitle.getBytes(StandardCharsets.UTF_8));
            String key = "captions/v1/" + android.util.Base64.encodeToString(fingerprint, android.util.Base64.NO_WRAP);
            JSONObject cached = readCache(key);
            JSONObject value = cached == null ? null : cached.optJSONObject("data");
            org.json.JSONArray indices = value == null ? null : value.optJSONArray("segments");
            if (indices != null) {
                var result = new ArrayList<Segment>();
                for (int i = 0; i < indices.length(); i++) {
                    var item = indices.optJSONArray(i);
                    if (item != null) add(result, item.optLong(0, -1), item.optLong(1, -1));
                }
                return result;
            }
            List<Segment> result = parseSubtitleScenes(subtitle);
            indices = new org.json.JSONArray();
            for (Segment segment : result) {
                indices.put(new org.json.JSONArray().put(segment.start).put(segment.end));
            }
            writeCache(key, new JSONObject().put("segments", indices));
            return result;
        } catch (java.security.NoSuchAlgorithmException | org.json.JSONException unavailable) {
            return parseSubtitleScenes(subtitle);
        }
    }

    private void writeCache(String key, JSONObject payload) throws org.json.JSONException {
        JSONObject entry = new JSONObject().put("at", System.currentTimeMillis()).put("data", payload);
        synchronized (timingCache) {
            var editor = timingCache.edit().putString(key, entry.toString());
            var entries = timingCache.getAll();
            if (entries.size() >= MAX_CACHE_ENTRIES && !entries.containsKey(key)) {
                String oldest = null;
                long earliest = Long.MAX_VALUE;
                for (String candidate : entries.keySet()) {
                    JSONObject saved = readCache(candidate);
                    long timestamp = saved == null ? 0 : saved.optLong("at");
                    if (timestamp < earliest) {
                        oldest = candidate;
                        earliest = timestamp;
                    }
                }
                if (oldest != null) editor.remove(oldest);
            }
            editor.apply();
        }
    }

    private JSONObject cachedResponse(String key, Uri url,
                                       java.util.function.Predicate<JSONObject> valid) {
        return cachedResponse(key, url, valid, false);
    }

    private JSONObject cachedResponse(String key, Uri url,
                                       java.util.function.Predicate<JSONObject> valid, boolean cacheOnly) {
        JSONObject entry = readCache(key);
        JSONObject cached = entry == null ? null : entry.optJSONObject("data");
        if (cacheOnly) return cached;
        long age = entry == null ? Long.MAX_VALUE : System.currentTimeMillis() - entry.optLong("at");
        if (cached != null && age >= 0 && age < (cached.length() == 0 ? CACHE_HOUR_MS : CACHE_WEEK_MS)) return cached;
        try {
            JSONObject response = new JSONObject(readHttp(url, true));
            if (response.length() != 0 && !valid.test(response)) return cached;
            writeCache(key, response);
            return response;
        } catch (Exception unavailable) {
            return cached;
        }
    }

    private Timeline fetchCleanStream(String imdbId, boolean cacheOnly) {
        if (!validImdbId(imdbId)) return EMPTY;
        JSONObject response = cachedResponse("clean/" + imdbId, Uri.parse(CLEANSTREAM + imdbId),
                value -> imdbId.equals(value.optString("imdbId")) && value.optJSONArray("segments") != null, cacheOnly);
        if (response == null) return EMPTY;
        var segments = response.optJSONArray("segments");
        if (segments == null) return EMPTY;
        var scenes = new ArrayList<Segment>();
        var breaks = new ArrayList<Segment>();
        for (int i = 0; i < segments.length(); i++) {
            var item = segments.optJSONObject(i);
            if (item == null || (!item.isNull("releaseId") && !item.optString("releaseId").isEmpty())) continue;
            var votes = item.optJSONObject("votes");
            if (votes != null && votes.optInt("down") > votes.optInt("up")) continue;
            List<Segment> destination = switch (item.optString("category")) {
                case "sex", "nudity", "violence", "language", "drugs", "fear", "discrimination" -> scenes;
                case "commercial" -> breaks;
                default -> null;
            };
            if (destination != null) add(destination, item.optLong("startMs", -1), item.optLong("endMs", -1));
        }
        return new Timeline(scenes, breaks, Math.max(0, response.optLong("runtime", 0)));
    }

    private Timeline fetchMovieHaven(String imdbId, boolean cacheOnly) {
        if (!validImdbId(imdbId)) return EMPTY;
        JSONObject response = cachedResponse("haven/" + imdbId, Uri.parse(MOVIE_HAVEN + imdbId + ".json"),
                value -> imdbId.equals(value.optString("imdb_id")) && value.optJSONArray("scenes") != null, cacheOnly);
        if (response == null) return EMPTY;
        var entries = response.optJSONArray("scenes");
        double seconds = response.optDouble("duration", 0);
        if (entries == null || !Double.isFinite(seconds) || seconds <= 0) return EMPTY;
        long runtimeMs = Math.round(seconds * 1000);
        var scenes = new ArrayList<Segment>();
        for (int i = 0; i < entries.length(); i++) {
            var item = entries.optJSONObject(i);
            // Family Watch uses seeking for all explicitly marked actions in this database.
            if (item == null || !(item.optBoolean("skip") || item.optBoolean("mute") || item.optBoolean("blur"))) continue;
            double start = item.optDouble("start", -1), end = item.optDouble("end", -1);
            if (Double.isFinite(start) && Double.isFinite(end) && start >= 0 && end <= seconds) {
                add(scenes, Math.round(start * 1000), Math.round(end * 1000));
            }
        }
        return new Timeline(scenes, List.of(), runtimeMs);
    }

    private List<Segment> fetchInterludes(MediaIds ids, int durationMs) {
        int seconds = Math.round(durationMs / 1000f);
        int season = ids.episode ? ids.season : 0;
        int number = ids.episode ? ids.number : 0;
        var url = Uri.parse(SKIP_DB).buildUpon().appendQueryParameter("imdb_id", ids.imdb)
                .appendQueryParameter("season", Integer.toString(season))
                .appendQueryParameter("episode", Integer.toString(number))
                .appendQueryParameter("duration", Integer.toString(seconds)).build();
        String key = "skip/" + ids.imdb + "/" + season + "/" + number + "/" + seconds;
        JSONObject response = cachedResponse(key, url, value -> ids.imdb.equals(value.optString("imdb_id"))
                && season == value.optInt("season", -1) && number == value.optInt("episode", -1)
                && value.optJSONObject("segments") != null);
        var markers = response == null ? null : response.optJSONObject("segments");
        List<Segment> breaks = new ArrayList<>();
        JSONObject introDb = null;
        boolean triedIntroDb = false;
        for (String type : List.of("intro", "preview")) {
            JSONObject marker = markers == null ? null : markers.optJSONObject(type);
            if (marker != null && marker.optDouble("confidence", 0) >= 0.75
                    && marker.optLong("end_ms", -1) <= durationMs) {
                int previousSize = breaks.size();
                add(breaks, marker.optLong("start_ms", -1), marker.optLong("end_ms", -1));
                if (breaks.size() > previousSize) continue;
            }
            if (!triedIntroDb) {
                introDb = fetchIntroDb(ids, seconds);
                triedIntroDb = true;
            }
            var segments = introDb == null ? null : introDb.optJSONArray(type);
            if (segments == null) continue;
            for (int i = 0; i < segments.length(); i++) {
                var candidate = segments.optJSONObject(i);
                if (candidate != null && candidate.optLong("end_ms", -1) <= durationMs) {
                    add(breaks, candidate.optLong("start_ms", -1), candidate.optLong("end_ms", -1));
                }
            }
        }
        return breaks;
    }

    private JSONObject fetchIntroDb(MediaIds ids, int seconds) {
        var url = Uri.parse(INTRO_DB).buildUpon()
                .appendQueryParameter("imdb_id", ids.imdb)
                .appendQueryParameter("duration_ms", Long.toString(seconds * 1000L));
        if (ids.episode) {
            url.appendQueryParameter("season", Integer.toString(ids.season))
                    .appendQueryParameter("episode", Integer.toString(ids.number));
        }
        String key = "intro/" + ids.imdb + "/" + ids.season + "/" + ids.number + "/" + seconds;
        return cachedResponse(key, url.build(), value -> ids.episode
                ? "tv".equals(value.optString("type")) && ids.season == value.optInt("season", -1)
                        && ids.number == value.optInt("episode", -1)
                : "movie".equals(value.optString("type")));
    }

    private void checkPosition() {
        if (!resumed) return;
        int limit = duration.getAsInt();
        if (needsDurationLookup && limit > 0) {
            needsDurationLookup = false;
            int ticket = generation;
            MediaIds ids = resolvedIds;
            IO.execute(() -> {
                List<Segment> markers = fetchInterludes(ids, limit);
                main.post(() -> {
                    if (generation != ticket) return;
                    skipDb = markers;
                    scheduleCheck();
                });
            });
        }
        boolean usingFamily = CinemaPlaybackOption.FAMILY_WATCH.enabled(context);
        boolean usingAds = CinemaPlaybackOption.SKIP_ADS.enabled(context);
        boolean compatible = online.runtimeMs == 0 || Math.abs(online.runtimeMs - limit) <= 20_000;
        boolean havenCompatible = Math.abs(movieHaven.runtimeMs - limit) <= 2000;
        boolean hasFamily = usingFamily && (!family.isEmpty() || !online.scenes.isEmpty()
                || !movieHaven.scenes.isEmpty() || !subtitleFile.isEmpty() || !observedCues.isEmpty());
        boolean hasAds = usingAds && (!ads.isEmpty() || !online.breaks.isEmpty() || !skipDb.isEmpty()
                || !fileChapters.isEmpty() || !timedChapters.isEmpty());
        if (hasFamily || hasAds) {
            if (limit > 0 && playing.getAsBoolean()) {
                int at = position.getAsInt();
                int end = -1;
                if (hasFamily) {
                    List<Segment> curated = !family.isEmpty() ? family
                            : compatible && !online.scenes.isEmpty() ? online.scenes
                            : havenCompatible ? movieHaven.scenes : List.of();
                    end = skipEnd(curated, at, limit);
                    if (curated.isEmpty()) {
                        end = Math.max(skipEnd(subtitleFile, at, limit), skipEnd(observedCues, at, limit));
                    }
                }
                if (hasAds) {
                    int adEnd = skipEnd(ads, at, limit);
                    if (adEnd < 0) {
                        adEnd = Math.max(adEnd, skipEnd(timedChapters, at, limit));
                        adEnd = Math.max(adEnd, chapterEnd(at, limit));
                        if (adEnd < 0) {
                            adEnd = Math.max(skipEnd(skipDb, at, limit),
                                    compatible ? skipEnd(online.breaks, at, limit) : -1);
                        }
                    }
                    end = Math.max(end, adEnd);
                }
                if (end < 0) lastSkipEnd = -1;
                else if (lastSkipEnd != end) {
                    lastSkipEnd = end;
                    seek.accept(end);
                }
            }
            main.postDelayed(check, 150);
        } else if (needsDurationLookup) {
            main.postDelayed(check, 350);
        }
    }

    private int chapterEnd(int position, int duration) {
        for (int i = 0; i < fileChapters.size(); i++) {
            var chapter = fileChapters.get(i);
            long end = chapter.end() > chapter.start() ? chapter.end()
                    : i + 1 < fileChapters.size() ? fileChapters.get(i + 1).start() : duration;
            if (chapter.start() <= position && position < end && end <= duration
                    && isBreakChapter(chapter.title())) return (int) end;
        }
        return -1;
    }

    private static boolean isBreakChapter(String title) {
        String label = title.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("[_-]+", " ");
        return BREAK_CHAPTER.matcher(label).matches()
                || label.matches("إعلانات(?: [0-9]+)?|إعلان(?: [0-9]+)?|فاصل إعلاني|مقدمة");
    }

    private static int skipEnd(List<Segment> segments, int position, int duration) {
        int end = -1;
        for (Segment segment : segments) {
            if (segment.start <= position && position < segment.end && segment.end <= duration) {
                end = Math.max(end, segment.end);
            }
        }
        return end;
    }

    private String readSidecar(Uri source, String extension) {
        String path = source.getEncodedPath();
        if (path == null || path.isEmpty() || path.endsWith("/")) return "";
        int suffix = path.lastIndexOf('.');
        String base = suffix > path.lastIndexOf('/') ? path.substring(0, suffix) : path;
        Uri sidecar = source.buildUpon().encodedPath(base + extension).fragment(null).build();
        try {
            return switch (Objects.toString(sidecar.getScheme(), "")) {
                case "file" -> read(new java.io.FileInputStream(sidecar.getPath()));
                case "smb" -> read(FileEditorFactory.getFileEditorForUrl(sidecar, context).getInputStream());
                case "http", "https" -> readHttp(sidecar);
                default -> "";
            };
        } catch (Exception unavailable) {
            return "";
        }
    }

    private static String readHttp(Uri sidecar) throws Exception {
        return readHttp(sidecar, false);
    }

    private static String readHttp(Uri uri, boolean cacheNotFound) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setConnectTimeout(1800);
        connection.setReadTimeout(1800);
        connection.setInstanceFollowRedirects(false);
        try {
            int status = connection.getResponseCode();
            if (status == 404 && cacheNotFound) return "{}";
            return status == 200 ? read(connection.getInputStream()) : "";
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream stream) throws java.io.IOException {
        try (InputStream input = stream; var output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int n; (n = input.read(buffer)) != -1; ) {
                if (output.size() + n > MAX_BYTES) return "";
                output.write(buffer, 0, n);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static List<Segment> parseMcf(String text) {
        if (!text.startsWith("WEBVTT MovieContentFilter ")) return List.of();
        List<Segment> segments = new ArrayList<>();
        var cues = MCF_CUE.matcher(text.replace("\r\n", "\n"));
        while (cues.find()) add(segments, timestampMs(cues.group(1)), timestampMs(cues.group(2)));
        return segments;
    }

    private static long timestampMs(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 3_600_000L
                + Integer.parseInt(parts[1]) * 60_000L + Math.round(Double.parseDouble(parts[2]) * 1000);
    }

    private static List<Segment> parseEdl(String text) {
        List<Segment> segments = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 3 || !"3".equals(parts[2])) continue;
            try {
                double start = Double.parseDouble(parts[0]);
                double end = Double.parseDouble(parts[1]);
                if (Double.isFinite(start) && Double.isFinite(end) && start >= 0 && end <= Integer.MAX_VALUE / 1000.0) {
                    add(segments, (int) Math.round(start * 1000), (int) Math.round(end * 1000));
                }
            } catch (NumberFormatException ignored) {}
        }
        return segments;
    }

    private static void add(List<Segment> segments, long start, long end) {
        if (start >= 0 && end > start && end <= Integer.MAX_VALUE) {
            segments.add(new Segment((int) start, (int) end));
        }
    }
}

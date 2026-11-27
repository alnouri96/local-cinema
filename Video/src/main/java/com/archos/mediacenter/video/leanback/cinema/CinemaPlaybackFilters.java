package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.Metadata;
import androidx.media3.extractor.metadata.id3.ChapterFrame;
import androidx.media3.extractor.metadata.id3.TextInformationFrame;
import androidx.preference.PreferenceManager;

import com.archos.filecorelibrary.FileEditorFactory;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.utils.PlayUtils;
import com.archos.mediaprovider.video.VideoStore;
import com.archos.mediascraper.BaseTags;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves skip/mute timings for the current movie or episode (via IMDb, TMDb, Cinemeta, TVmaze,
 * installed Stremio add-ons, filenames, chapters, sidecars, and subtitles) and applies
 * Family Watch & Skip Ads with subtle non-intrusive visual feedback.
 */
public final class CinemaPlaybackFilters {
    private static final int MAX_BYTES = 256 * 1024;
    private static final java.util.concurrent.ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final String TIMESTAMP = "(\\d{2,3}:[0-5]\\d:[0-5]\\d\\.\\d{3})";
    private static final Pattern MCF_CUE = Pattern.compile("(?m)^" + TIMESTAMP + " +--> +" + TIMESTAMP
            + " *\\n([\\w-]+)=(?:high|medium|low)(?:=(both|audio|video))?(?: +#[^\\n]*)? *$");
    private static final Pattern ADDON_ID_SE = Pattern.compile("(?i)(tt\\d{5,12})(?::(\\d{1,2}):(\\d{1,3}))?");
    private static final Pattern TMDB_ID_SE = Pattern.compile("(?i)tmdb:(\\d+)(?::(\\d{1,2}):(\\d{1,3}))?");
    private static final Pattern YEAR_TOKEN = Pattern.compile("(?:^|[\\s._(-])((?:19|20)\\d{2})(?=$|[\\s._)-])");

    public enum EventKind {
        SKIP_ADS,
        FAMILY_SKIP,
        FAMILY_MUTE
    }

    private enum Action {
        SKIP,
        MUTE
    }

    private record Segment(int start, int end, Action action) {
        Segment(int start, int end) {
            this(start, end, Action.SKIP);
        }
    }

    private record Identity(
            long videoId,
            String imdbId,
            String episodeId,
            boolean episode,
            int season,
            int number,
            long tmdbId,
            String title,
            String englishTitle,
            int year,
            String addonItemId,
            String streamKey,
            String fileName) {}

    private record Timeline(List<Segment> scenes, List<Segment> breaks, long runtimeMs) {}
    private record MediaIds(String imdb, String episodeImdb, boolean episode, int season, int number, long tmdbId) {}

    private static final String SKIP_DB = "https://api.skipdb.tv/api/segments";
    private static final String INTRO_DB = "https://api.theintrodb.org/v3/media";
    private static final String CINEMETA_SERIES = "https://v3-cinemeta.strem.io/meta/series/";
    private static final String TVMAZE_LOOKUP = "https://api.tvmaze.com/lookup/shows?imdb=";
    private static final long CACHE_WEEK_MS = 7 * 24 * 60 * 60 * 1000L;
    private static final long CACHE_HOUR_MS = 60 * 60 * 1000L;
    private static final Pattern BREAK_CHAPTER = Pattern.compile(
            "^(?:ad(?:s|vert(?:s|isements?)?)?(?: break)?|commercials?(?: break)?|sponsor(?:ed)?(?: segment| break)?"
                    + "|intermission|promos?|preview|next (?:time|episode|on)|previously(?: on)?|recap"
                    + "|intro(?:duction)?|op|opening(?: credits| theme| song)?|credits|outro|ending(?: credits)?)(?: [0-9]+)?$");

    private static final String CLEANSTREAM = "https://cleanstream.elfhosted.com/api/filters/";
    private static final String MOVIE_HAVEN =
            "https://raw.githubusercontent.com/arman-kh/MovieHavenDB/master/movies/";
    private static final Pattern SUBTITLE_TIME = Pattern.compile(
            "(?m)^((?:\\d{1,3}:)?[0-5]\\d:[0-5]\\d[.,]\\d{3})[ \\t]+-->[ \\t]+"
                    + "((?:\\d{1,3}:)?[0-5]\\d:[0-5]\\d[.,]\\d{3})[^\\n]*\\n");
    private static final Pattern MATURE_ANNOTATION = Pattern.compile(
            "\\b(?:kiss(?:es|ing)|kissing sounds|passionate kiss|making love|having sex|sex scene|"
                    + "sexual moan(?:s|ing)?|moan(?:s|ing)? (?:in pleasure|passionately)|sexual activity|"
                    + "clothes (?:are )?removed|(?:visibly |fully )?naked|nudity|undress(?:es|ing))\\b"
                    + "|(?:^|\\s)(?:يتبادلان القبل|يتبادلون القبل|يقبلها|تقبله|صوت قبلات|أصوات قبلات"
                    + "|أنين جنسي|أنين من اللذة|يمارسان الجنس|مشهد جنسي|عري)(?=\\s|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ANNOTATION = Pattern.compile("[\\[(\\u266a]([^\\]\\)\\r\\n]{2,110})[\\])\\u266a]");
    private static final Timeline EMPTY = new Timeline(List.of(), List.of(), 0);
    private static final int MAX_CACHE_ENTRIES = 2048;

    private final Context context;
    private final WeakReference<Activity> hostRef;
    private final SharedPreferences prefs;
    private final SharedPreferences timingCache;
    private final IntSupplier position;
    private final IntSupplier duration;
    private final BooleanSupplier playing;
    private final IntConsumer seek;
    private final Consumer<Boolean> muteHandler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable check = this::checkPosition;
    private LinearLayout pillBanner;
    private ImageView pillIcon;
    private TextView pillText;
    private final Runnable hidePill = () -> {
        if (pillBanner != null) {
            pillBanner.animate().alpha(0f).setDuration(220).withEndAction(() -> {
                if (pillBanner != null) pillBanner.setVisibility(View.GONE);
            }).start();
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged = (store, key) -> {
        if (CinemaPlaybackOption.FAMILY_WATCH.key.equals(key) || CinemaPlaybackOption.SKIP_ADS.key.equals(key)) reload();
    };
    private Uri media;
    private Identity identity;
    private Timeline online = EMPTY;
    private Timeline movieHaven = EMPTY;
    private List<Segment> subtitleFile = List.of();
    private List<Segment> loadedSubtitleScenes = List.of();
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
    private int lastMuteSegmentStart = -1;
    private boolean currentlyFilterMuted;
    private List<Segment> family = List.of();
    private List<Segment> ads = List.of();

    public CinemaPlaybackFilters(Context context, IntSupplier position, IntSupplier duration,
                                 BooleanSupplier playing, IntConsumer seek) {
        this(context, position, duration, playing, seek, null);
    }

    public CinemaPlaybackFilters(Context context, IntSupplier position, IntSupplier duration,
                                 BooleanSupplier playing, IntConsumer seek, Consumer<Boolean> muteHandler) {
        this.context = context.getApplicationContext();
        this.hostRef = context instanceof Activity act ? new WeakReference<>(act) : new WeakReference<>(null);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        timingCache = this.context.getSharedPreferences("cinema_timing_cache", Context.MODE_PRIVATE);
        this.position = position;
        this.duration = duration;
        this.playing = playing;
        this.seek = seek;
        this.muteHandler = muteHandler;
    }

    public void setMedia(Uri uri, long videoId, Intent intent) {
        int season = intent == null ? 0 : intent.getIntExtra("season", 0);
        int episode = intent == null ? 0 : intent.getIntExtra("episode", 0);
        String imdb = intent == null ? null : intent.getStringExtra("imdb_id");
        String episodeImdb = intent == null ? null : intent.getStringExtra("episode_imdb_id");
        long tmdb = intent == null ? 0L : intent.getLongExtra("tmdb_id", 0L);
        String title = intent == null ? "" : Objects.toString(intent.getStringExtra("title"), "");
        String englishTitle = intent == null ? "" : Objects.toString(intent.getStringExtra("english_title"), "");
        String heading = intent == null ? "" : Objects.toString(intent.getStringExtra("addon_heading"), "");
        if (title.isBlank() && !heading.isBlank()) title = heading;
        int year = intent == null ? 0 : Math.max(0, intent.getIntExtra("release_year", 0));
        String addonItemId = intent == null ? "" : Objects.toString(intent.getStringExtra("addon_item_id"), "");
        String streamKey = intent == null ? "" : Objects.toString(intent.getStringExtra("stream_key"), "");
        String fileName = intent == null ? "" : Objects.toString(intent.getStringExtra("video_filename"), "");

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
        for (String composite : new String[]{addonItemId, streamKey}) {
            if (composite == null || composite.isBlank()) continue;
            Matcher m = ADDON_ID_SE.matcher(composite);
            if (m.find()) {
                if (!validImdbId(imdb)) imdb = m.group(1);
                if (season <= 0 && m.group(2) != null) season = safeInt(m.group(2));
                if (episode <= 0 && m.group(3) != null) episode = safeInt(m.group(3));
            }
            Matcher tm = TMDB_ID_SE.matcher(composite);
            if (tm.find()) {
                if (tmdb <= 0L) tmdb = safeLong(tm.group(1));
                if (season <= 0 && tm.group(2) != null) season = safeInt(tm.group(2));
                if (episode <= 0 && tm.group(3) != null) episode = safeInt(tm.group(3));
            }
        }
        String pathHint = !fileName.isBlank() ? fileName : (uri != null ? Uri.decode(uri.toString()) : "");
        if (episode <= 0 && (!pathHint.isBlank() || !title.isBlank())) {
            int[] se = CinemaPosters.findSeasonEpisode(pathHint, title);
            if (se[1] > 0) {
                if (season <= 0) season = Math.max(1, se[0]);
                episode = se[1];
            }
        }
        if (year <= 0) {
            year = extractYear(title);
            if (year <= 0) year = extractYear(englishTitle);
            if (year <= 0) year = extractYear(pathHint);
        }
        String mediaType = intent == null ? "" : Objects.toString(intent.getStringExtra("media_type"), "");
        boolean series = episode > 0 || season > 0
                || "episode".equalsIgnoreCase(mediaType) || "series".equalsIgnoreCase(mediaType) || "show".equalsIgnoreCase(mediaType);
        var key = new Identity(videoId, imdb, episodeImdb, series, season, episode, tmdb,
                title, englishTitle, year, addonItemId, streamKey, fileName);
        if (Objects.equals(media, uri) && key.equals(identity)) return;
        if (!Objects.equals(media, uri)) {
            timedChapters.clear();
            observedCues.clear();
            loadedSubtitleScenes = List.of();
            lastUntimedCueAt = -10_000;
        }
        media = uri;
        identity = key;
        reload();
    }

    public void setLoadedSubtitleBytes(byte[] utf8Bytes) {
        if (utf8Bytes == null || utf8Bytes.length == 0 || utf8Bytes.length > MAX_BYTES * 4) return;
        int ticket = generation;
        IO.execute(() -> {
            String content = new String(utf8Bytes, StandardCharsets.UTF_8);
            List<Segment> detected = cachedSubtitleScenes(content);
            if (detected.isEmpty()) return;
            main.post(() -> {
                if (generation != ticket) return;
                loadedSubtitleScenes = detected;
                scheduleCheck();
            });
        });
    }

    public void resume() {
        resumed = true;
        prefs.registerOnSharedPreferenceChangeListener(settingsChanged);
        reload();
    }

    public void pause() {
        resumed = false;
        setMutedInternal(false);
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
                    Segment segment = new Segment(chapter.startTimeMs, chapter.endTimeMs, Action.SKIP);
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
        return new Segment((int) Math.max(0, start - 400), (int) Math.min(Integer.MAX_VALUE, end + 500), Action.SKIP);
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
        lastMuteSegmentStart = -1;
        setMutedInternal(false);
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
            MediaIds ids = resolveIds(key, source);
            main.post(() -> {
                if (generation != ticket) return;
                resolvedIds = ids;
                needsDurationLookup = useAds && validImdbId(ids.imdb)
                        && (!ids.episode || (ids.season >= 0 && ids.number > 0));
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
            List<Segment> breaks = (useAds || useFamily) ? parseEdl(readSidecar(source, ".edl"), useAds, useFamily) : List.of();
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
                || !subtitleFile.isEmpty() || !loadedSubtitleScenes.isEmpty() || !observedCues.isEmpty()
                || currentlyFilterMuted)) {
            main.post(check);
        }
    }

    void warmLibraryEntry(long videoId, Uri uri) {
        Identity key = new Identity(videoId, null, null, false, 0, 0, 0, "", "", 0, "", "", "");
        MediaIds ids = resolveIds(key, uri);
        long tmdb = ids.tmdbId > 0 ? ids.tmdbId : tmdbId(key, ids.episode);
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
        if (!List.of("file", "smb", "smbj").contains(Objects.toString(uri.getScheme(), ""))) {
            return cacheOnly ? List.of() : parseMcf(readSidecar(uri, ".mcf"));
        }
        String key = "mcf/" + java.util.UUID.nameUUIDFromBytes(uri.toString().getBytes(StandardCharsets.UTF_8));
        if (!cacheOnly) {
            String mcf = readSidecar(uri, ".mcf");
            if (!mcf.isEmpty()) {
                try { writeCache(key, new JSONObject().put("mcf", mcf)); } catch (org.json.JSONException ignored) {}
                return parseMcf(mcf);
            }
        }
        JSONObject entry = readCache(key);
        JSONObject cached = entry == null ? null : entry.optJSONObject("data");
        return cached == null ? List.of() : parseMcf(cached.optString("mcf"));
    }

    private long tmdbId(Identity key, boolean episode) {
        if (key != null && key.tmdbId > 0) return key.tmdbId;
        if (key == null || key.videoId <= 0 || key.videoId >= 900_000_000L) return 0;
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
        long tmdb = ids.tmdbId > 0 ? ids.tmdbId : tmdbId(key, ids.episode);
        String apiKey = com.meedx.providers.ProviderKeys.get(context, com.meedx.providers.ProviderKeys.TMDB);
        if (tmdb <= 0 && validImdbId(ids.imdb) && !apiKey.isBlank()) {
            tmdb = resolveTmdbFromImdb(ids.imdb, ids.episode, apiKey);
        }
        if (tmdb <= 0 || (ids.episode && (ids.season < 0 || ids.number <= 0))) return "";
        String keyValue = "tmdb/" + (ids.episode ? "tv/" + tmdb + "/" + ids.season + "/" + ids.number : "movie/" + tmdb);
        JSONObject entry = readCache(keyValue);
        JSONObject cached = entry == null ? null : entry.optJSONObject("data");
        if (cached != null && validImdbId(cached.optString("imdb_id"))) return cached.optString("imdb_id");
        if (apiKey.isBlank()) return "";
        String path = ids.episode ? "tv/" + tmdb + "/season/" + ids.season + "/episode/" + ids.number : "movie/" + tmdb;
        JSONObject result = cachedResponse(keyValue, Uri.parse("https://api.themoviedb.org/3/" + path + "/external_ids")
                .buildUpon().appendQueryParameter("api_key", apiKey).build(), value -> value.has("imdb_id"));
        return result == null ? "" : result.optString("imdb_id");
    }

    private long resolveTmdbFromImdb(String imdbId, boolean series, String apiKey) {
        if (!validImdbId(imdbId) || apiKey == null || apiKey.isBlank()) return 0L;
        String ck = "tmdb_find/" + imdbId;
        JSONObject cached = readCache(ck);
        JSONObject saved = cached == null ? null : cached.optJSONObject("data");
        if (saved != null && saved.optLong("id") > 0L) return saved.optLong("id");
        Uri u = Uri.parse("https://api.themoviedb.org/3/find/" + imdbId)
                .buildUpon()
                .appendQueryParameter("api_key", apiKey)
                .appendQueryParameter("external_source", "imdb_id")
                .build();
        try {
            JSONObject resp = new JSONObject(readHttp(u, false));
            JSONArray arr = resp.optJSONArray(series ? "tv_results" : "movie_results");
            if (arr != null && arr.length() > 0) {
                long found = arr.getJSONObject(0).optLong("id", 0L);
                if (found > 0L) {
                    writeCache(ck, new JSONObject().put("id", found));
                    return found;
                }
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    private MediaIds resolveIds(Identity key, Uri source) {
        if (key == null) return new MediaIds(null, null, false, 0, 0, 0L);
        String imdb = validImdbId(key.imdbId) ? key.imdbId : "";
        String epImdb = validImdbId(key.episodeId) ? key.episodeId : "";
        boolean isEp = key.episode;
        int season = key.season;
        int number = key.number;
        long tmdb = key.tmdbId;
        String rawTitle = key.title == null ? "" : key.title;
        String englishTitle = key.englishTitle == null ? "" : key.englishTitle;
        int year = key.year;
        String dataPath = key.fileName == null ? "" : key.fileName;

        // 1. Query VideoStore for indexed local/SMB files (both scraped IDs and raw filename/title)
        if (key.videoId > 0 && key.videoId < 900_000_000L) {
            var columns = new String[]{
                    VideoStore.Video.VideoColumns.ARCHOS_MEDIA_SCRAPER_TYPE,
                    VideoStore.Video.VideoColumns.SCRAPER_M_IMDB_ID,
                    VideoStore.Video.VideoColumns.SCRAPER_E_IMDB_ID,
                    VideoStore.Video.VideoColumns.SCRAPER_S_IMDB_ID,
                    VideoStore.Video.VideoColumns.SCRAPER_E_SEASON,
                    VideoStore.Video.VideoColumns.SCRAPER_E_EPISODE,
                    VideoStore.Video.VideoColumns.SCRAPER_SHOW_ID,
                    VideoStore.MediaColumns.TITLE,
                    VideoStore.MediaColumns.DATA,
                    VideoStore.Video.VideoColumns.SCRAPER_M_ONLINE_ID,
                    VideoStore.Video.VideoColumns.SCRAPER_S_ONLINE_ID,
                    VideoStore.Video.VideoColumns.SCRAPER_M_YEAR
            };
            try (var cursor = context.getContentResolver().query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                    columns, "_id=?", new String[]{Long.toString(key.videoId)}, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int scraperType = cursor.getInt(0);
                    String dbTitle = cursor.getString(7);
                    String dbData = cursor.getString(8);
                    if (rawTitle.isBlank() && dbTitle != null) rawTitle = dbTitle;
                    if (dataPath.isBlank() && dbData != null) dataPath = dbData;
                    if (scraperType == BaseTags.TV_SHOW && cursor.getInt(5) > 0) {
                        isEp = true;
                        imdb = firstImdb(cursor.getString(3), imdb);
                        epImdb = firstImdb(cursor.getString(2), epImdb);
                        if (season <= 0) season = cursor.getInt(4);
                        if (number <= 0) number = cursor.getInt(5);
                        if (tmdb <= 0L) tmdb = cursor.getLong(10);
                        if (!validImdbId(imdb) && cursor.getLong(6) > 0) {
                            var show = CinemaWatchStore.getIdentity(context, "s:" + cursor.getLong(6));
                            if (show != null && validImdbId(show.imdbId())) imdb = show.imdbId();
                        }
                    } else if (!isEp && scraperType == BaseTags.MOVIE) {
                        imdb = firstImdb(cursor.getString(1), imdb);
                        if (tmdb <= 0L) tmdb = cursor.getLong(9);
                        if (year <= 0) year = cursor.getInt(11);
                    }
                }
            } catch (RuntimeException ignored) {}
        }

        // 2. Check CinemaWatchStore descriptors (covers Phone Casts, Stremio Streams, Poster Cache & Unscraped files)
        String sourceUriStr = source != null ? source.toString() : "";
        if (!validImdbId(imdb) || (isEp && number <= 0)) {
            if (key.streamKey != null && !key.streamKey.isBlank()) {
                var se = CinemaWatchStore.getStream(context, key.streamKey);
                if (se != null) {
                    if (!validImdbId(imdb) && validImdbId(se.imdbId())) imdb = se.imdbId();
                    if (tmdb <= 0L && se.tmdbId() > 0L) tmdb = se.tmdbId();
                    if (season <= 0 && se.season() > 0) season = se.season();
                    if (number <= 0 && se.episode() > 0) { number = se.episode(); isEp = true; }
                    if (rawTitle.isBlank() && se.title() != null) rawTitle = se.title();
                }
            }
            var desc = CinemaWatchStore.resolvePlaybackDescriptor(context, key.videoId,
                    dataPath.isBlank() ? sourceUriStr : dataPath, rawTitle);
            if (desc != null) {
                if (!validImdbId(imdb) && validImdbId(desc.imdbId())) imdb = desc.imdbId();
                if (tmdb <= 0L && desc.tmdbId() > 0L) tmdb = desc.tmdbId();
                if (season <= 0 && desc.season() > 0) season = desc.season();
                if (number <= 0 && desc.episode() > 0) { number = desc.episode(); isEp = true; }
            }
        }

        // 3. Extract Season/Episode & Release Year from filename/URI/title (handles brand-new unscraped episodes)
        String fullPathHint = !dataPath.isBlank() ? dataPath : Uri.decode(sourceUriStr);
        if (number <= 0 && (!fullPathHint.isBlank() || !rawTitle.isBlank())) {
            int[] se = CinemaPosters.findSeasonEpisode(fullPathHint, rawTitle);
            if (se[1] > 0) {
                isEp = true;
                if (season <= 0) season = Math.max(1, se[0]);
                number = se[1];
            }
        }
        if (rawTitle.isBlank() && source != null && source.getLastPathSegment() != null) {
            rawTitle = Uri.decode(source.getLastPathSegment());
        }
        if (year <= 0) {
            year = extractYear(rawTitle);
            if (year <= 0) year = extractYear(fullPathHint);
        }

        // 4. Flexible multi-source metadata resolution (UnifiedSubtitleEngine + OpenMetadata/Cinemeta/TVmaze + Stremio Add-ons)
        if (!validImdbId(imdb) && (!rawTitle.isBlank() || !englishTitle.isBlank() || tmdb > 0L)) {
            String resolveCacheKey = "resolve/v2/" + rawTitle.trim().toLowerCase(Locale.ROOT)
                    + "|" + englishTitle.trim().toLowerCase(Locale.ROOT)
                    + "|" + year + "|" + isEp + "|" + season + "|" + number + "|" + tmdb;
            JSONObject cachedEntry = readCache(resolveCacheKey);
            JSONObject cachedData = cachedEntry == null ? null : cachedEntry.optJSONObject("data");
            if (cachedData != null && validImdbId(cachedData.optString("imdb"))) {
                imdb = cachedData.optString("imdb");
                if (season <= 0 && cachedData.optInt("season") > 0) season = cachedData.optInt("season");
                if (number <= 0 && cachedData.optInt("episode") > 0) {
                    number = cachedData.optInt("episode");
                    isEp = true;
                }
            } else {
                try {
                    var rq = com.meedx.providers.UnifiedSubtitleEngine.resolveQuery(
                            context, rawTitle, englishTitle, null, imdb, tmdb, year, isEp, season, number, false);
                    if (rq != null) {
                        if (validImdbId(rq.imdbId)) imdb = rq.imdbId;
                        if (season <= 0 && rq.season > 0) season = rq.season;
                        if (number <= 0 && rq.episode > 0) {
                            number = rq.episode;
                            isEp = true;
                        }
                        if (englishTitle.isBlank() && rq.englishTitle != null) englishTitle = rq.englishTitle;
                        if (year <= 0 && rq.year > 0) year = rq.year;
                    }
                } catch (Exception ignored) {}

                if (!validImdbId(imdb)) {
                    String lookupTitle = !englishTitle.isBlank() ? englishTitle
                            : com.meedx.providers.UnifiedSubtitleEngine.cleanRawTitle(rawTitle);
                    if (!lookupTitle.isBlank()) {
                        try {
                            var om = com.meedx.providers.OpenMetadata.lookup(
                                    context,
                                    new com.meedx.providers.OpenMetadata.Query(lookupTitle, year, isEp),
                                    "", "", Math.max(0, season), Math.max(0, number), "en");
                            if (om != null && validImdbId(om.imdb())) {
                                imdb = om.imdb();
                            }
                        } catch (Exception ignored) {}
                    }
                }

                if (!validImdbId(imdb) && key.addonItemId != null && !key.addonItemId.isBlank()) {
                    imdb = resolveFromInstalledStremioAddons(key.addonItemId, isEp);
                }

                if (validImdbId(imdb)) {
                    try {
                        writeCache(resolveCacheKey, new JSONObject()
                                .put("imdb", imdb)
                                .put("season", season)
                                .put("episode", number));
                    } catch (Exception ignored) {}
                }
            }
        }

        return new MediaIds(imdb, epImdb, isEp, season, number, tmdb);
    }

    private String resolveFromInstalledStremioAddons(String addonItemId, boolean series) {
        try {
            String type = series ? "series" : "movie";
            String baseItemId = addonItemId;
            // Strip trailing :season:episode if composite non-tt ID
            String[] parts = addonItemId.split(":");
            if (parts.length >= 4) {
                baseItemId = parts[0] + ":" + parts[1];
            }
            for (var addon : com.meedx.providers.StremioAddons.list(context)) {
                if (addon == null || !addon.enabled || addon.baseUrl == null || addon.baseUrl.isBlank()) continue;
                Uri metaUri = Uri.parse(addon.baseUrl + "/meta/" + type + "/" + Uri.encode(baseItemId) + ".json");
                String raw = readHttp(metaUri, false);
                if (raw.isEmpty()) continue;
                JSONObject meta = new JSONObject(raw).optJSONObject("meta");
                if (meta == null) continue;
                String candidate = meta.optString("imdb_id", "").trim();
                if (!validImdbId(candidate)) candidate = meta.optString("id", "").trim();
                if (validImdbId(candidate)) return candidate;
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String resolveEpisodeId(MediaIds ids) {
        if (validImdbId(ids.episodeImdb) && !ids.episodeImdb.equals(ids.imdb)) return ids.episodeImdb;
        if (!validImdbId(ids.imdb) || ids.season < 0 || ids.number <= 0) return "";
        String key = "episode/v2/" + ids.imdb + "/" + ids.season + "/" + ids.number;
        JSONObject cached = readCache(key);
        JSONObject saved = cached == null ? null : cached.optJSONObject("data");
        if (saved != null && validImdbId(saved.optString("id"))) return saved.optString("id");

        // 1. Trakt lookup
        String id = com.meedx.providers.TraktSyncClient.findEpisodeImdbId(context, ids.imdb, ids.season, ids.number);
        if (validImdbId(id) && !id.equals(ids.imdb)) {
            cacheEpisodeId(key, id);
            return id;
        }

        // 2. Cinemeta series videos array (fast & updated promptly for new episodes)
        try {
            JSONObject resp = cachedResponse("cinemeta_ep/" + ids.imdb,
                    Uri.parse(CINEMETA_SERIES + ids.imdb + ".json"),
                    v -> v.optJSONObject("meta") != null);
            JSONObject meta = resp == null ? null : resp.optJSONObject("meta");
            JSONArray videos = meta == null ? null : meta.optJSONArray("videos");
            if (videos != null) {
                for (int i = 0; i < videos.length(); i++) {
                    JSONObject v = videos.optJSONObject(i);
                    if (v == null) continue;
                    int s = v.optInt("season", -1);
                    int ep = v.optInt("episode", v.optInt("number", -1));
                    if (s == ids.season && ep == ids.number) {
                        String epImdb = v.optString("imdb_id", "").trim();
                        if (validImdbId(epImdb) && !epImdb.equals(ids.imdb)) {
                            cacheEpisodeId(key, epImdb);
                            return epImdb;
                        }
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}

        // 3. TVmaze episode lookup (tracks brand-new episodes released 1–2 days ago)
        try {
            String showJson = readHttp(Uri.parse(TVMAZE_LOOKUP + ids.imdb), false);
            if (!showJson.isEmpty()) {
                long tvmazeId = new JSONObject(showJson).optLong("id", 0L);
                if (tvmazeId > 0L) {
                    String epJson = readHttp(Uri.parse("https://api.tvmaze.com/shows/" + tvmazeId
                            + "/episodebynumber?season=" + ids.season + "&number=" + ids.number), false);
                    if (!epJson.isEmpty()) {
                        JSONObject ext = new JSONObject(epJson).optJSONObject("externals");
                        String epImdb = ext != null ? ext.optString("imdb", "").trim() : "";
                        if (validImdbId(epImdb) && !epImdb.equals(ids.imdb)) {
                            cacheEpisodeId(key, epImdb);
                            return epImdb;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return "";
    }

    private void cacheEpisodeId(String key, String id) {
        try { writeCache(key, new JSONObject().put("id", id)); } catch (org.json.JSONException ignored) {}
    }

    private static int extractYear(String text) {
        if (text == null || text.isBlank()) return 0;
        Matcher m = YEAR_TOKEN.matcher(text);
        if (m.find()) return safeInt(m.group(1));
        return 0;
    }

    private static int safeInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static long safeLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
    }

    private static String firstImdb(String primary, String fallback) {
        return validImdbId(primary) ? primary : (fallback == null ? "" : fallback);
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
        if (subtitle == null || subtitle.isEmpty()) return List.of();
        try {
            byte[] fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(subtitle.getBytes(StandardCharsets.UTF_8));
            String key = "captions/v2/" + android.util.Base64.encodeToString(fingerprint, android.util.Base64.NO_WRAP);
            JSONObject cached = readCache(key);
            JSONObject value = cached == null ? null : cached.optJSONObject("data");
            JSONArray indices = value == null ? null : value.optJSONArray("segments");
            if (indices != null) {
                var result = new ArrayList<Segment>();
                for (int i = 0; i < indices.length(); i++) {
                    var item = indices.optJSONArray(i);
                    if (item != null) add(result, item.optLong(0, -1), item.optLong(1, -1), Action.SKIP);
                }
                return result;
            }
            List<Segment> result = parseSubtitleScenes(subtitle);
            indices = new JSONArray();
            for (Segment segment : result) {
                indices.put(new JSONArray().put(segment.start).put(segment.end));
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
        var genericScenes = new ArrayList<Segment>();
        var fallbackReleaseScenes = new ArrayList<Segment>();
        var breaks = new ArrayList<Segment>();
        for (int i = 0; i < segments.length(); i++) {
            var item = segments.optJSONObject(i);
            if (item == null) continue;
            var votes = item.optJSONObject("votes");
            if (votes != null && votes.optInt("down") > votes.optInt("up")) continue;
            boolean hasReleaseId = !item.isNull("releaseId") && !item.optString("releaseId").isEmpty();
            String cat = item.optString("category", "").toLowerCase(Locale.ROOT);
            long sMs = item.optLong("startMs", -1);
            long eMs = item.optLong("endMs", -1);
            switch (cat) {
                case "sex", "nudity", "violence", "drugs", "fear", "discrimination" ->
                        add(hasReleaseId ? fallbackReleaseScenes : genericScenes, sMs, eMs, Action.SKIP);
                case "language" ->
                        add(hasReleaseId ? fallbackReleaseScenes : genericScenes, sMs, eMs, Action.MUTE);
                case "commercial", "intro", "recap", "outro", "credits", "sponsor" ->
                        add(breaks, sMs, eMs, Action.SKIP);
            }
        }
        List<Segment> scenes = !genericScenes.isEmpty() ? genericScenes : fallbackReleaseScenes;
        long rawRuntime = Math.max(0, response.optLong("runtime", 0));
        long runtimeMs = (rawRuntime > 0 && rawRuntime < 30_000) ? rawRuntime * 1000L : rawRuntime;
        return new Timeline(scenes, breaks, runtimeMs);
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
            if (item == null) continue;
            boolean doSkip = item.optBoolean("skip") || item.optBoolean("blur");
            boolean doMute = item.optBoolean("mute");
            if (!doSkip && !doMute) continue;
            double start = item.optDouble("start", -1), end = item.optDouble("end", -1);
            if (Double.isFinite(start) && Double.isFinite(end) && start >= 0 && end > start && end <= seconds + 5) {
                add(scenes, Math.round(start * 1000), Math.round(end * 1000), doSkip ? Action.SKIP : Action.MUTE);
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
        String key = "skip/v2/" + ids.imdb + "/" + season + "/" + number + "/" + (seconds / 10);
        JSONObject response = cachedResponse(key, url, value -> ids.imdb.equals(value.optString("imdb_id"))
                && value.optJSONObject("segments") != null);
        var markers = response == null ? null : response.optJSONObject("segments");
        List<Segment> breaks = new ArrayList<>();
        JSONObject introDb = null;
        boolean triedIntroDb = false;
        for (String type : List.of("intro", "recap", "preview", "commercial", "sponsor")) {
            JSONObject marker = markers == null ? null : markers.optJSONObject(type);
            if (marker != null && marker.optDouble("confidence", 0) >= 0.55) {
                long sMs = marker.optLong("start_ms", -1);
                long eMs = Math.min(durationMs, marker.optLong("end_ms", -1));
                if (sMs >= 0 && eMs > sMs && marker.optLong("end_ms", -1) <= durationMs + 5000L) {
                    int previousSize = breaks.size();
                    add(breaks, sMs, eMs, Action.SKIP);
                    if (breaks.size() > previousSize) continue;
                }
            }
            if (!triedIntroDb) {
                introDb = fetchIntroDb(ids, seconds);
                triedIntroDb = true;
            }
            if (introDb == null) continue;
            var segArr = introDb.optJSONArray(type);
            if (segArr != null) {
                for (int i = 0; i < segArr.length(); i++) {
                    var candidate = segArr.optJSONObject(i);
                    if (candidate != null) {
                        long sMs = candidate.optLong("start_ms", -1);
                        long eMs = Math.min(durationMs, candidate.optLong("end_ms", -1));
                        if (sMs >= 0 && eMs > sMs && candidate.optLong("end_ms", -1) <= durationMs + 5000L) {
                            add(breaks, sMs, eMs, Action.SKIP);
                        }
                    }
                }
            } else {
                var segObj = introDb.optJSONObject(type);
                if (segObj != null) {
                    long sMs = segObj.optLong("start_ms", -1);
                    long eMs = Math.min(durationMs, segObj.optLong("end_ms", -1));
                    if (sMs >= 0 && eMs > sMs && segObj.optLong("end_ms", -1) <= durationMs + 5000L) {
                        add(breaks, sMs, eMs, Action.SKIP);
                    }
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
        String key = "intro/v2/" + ids.imdb + "/" + ids.season + "/" + ids.number + "/" + (seconds / 10);
        return cachedResponse(key, url.build(), value -> value.length() > 0);
    }

    private static boolean isRuntimeCompatible(long referenceMs, int playerDurationMs) {
        if (referenceMs <= 0 || playerDurationMs <= 0) return true;
        long tolerance = Math.max(120_000L, playerDurationMs / 15L);
        return Math.abs(referenceMs - playerDurationMs) <= tolerance;
    }

    private void checkPosition() {
        if (!resumed) return;
        int limit = duration.getAsInt();
        if (needsDurationLookup && limit > 0 && resolvedIds != null) {
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
        if (!usingFamily && !usingAds) {
            setMutedInternal(false);
            return;
        }
        boolean compatible = isRuntimeCompatible(online.runtimeMs, limit);
        boolean havenCompatible = isRuntimeCompatible(movieHaven.runtimeMs, limit);
        boolean hasFamily = usingFamily && (!family.isEmpty() || !online.scenes.isEmpty()
                || !movieHaven.scenes.isEmpty() || !subtitleFile.isEmpty()
                || !loadedSubtitleScenes.isEmpty() || !observedCues.isEmpty());
        boolean hasAds = usingAds && (!ads.isEmpty() || !online.breaks.isEmpty() || !skipDb.isEmpty()
                || !fileChapters.isEmpty() || !timedChapters.isEmpty());

        if (hasFamily || hasAds) {
            if (limit > 0 && playing.getAsBoolean()) {
                int at = position.getAsInt();
                int familySkipEnd = -1;
                Segment activeMuteSegment = null;

                if (hasFamily) {
                    List<Segment> curated = !family.isEmpty() ? family
                            : (compatible && !online.scenes.isEmpty()) ? online.scenes
                            : (havenCompatible && !movieHaven.scenes.isEmpty()) ? movieHaven.scenes
                            : List.of();
                    familySkipEnd = actionEnd(curated, at, limit, Action.SKIP);
                    activeMuteSegment = findActiveSegment(curated, at, limit, Action.MUTE);
                    if (curated.isEmpty()) {
                        familySkipEnd = Math.max(
                                actionEnd(subtitleFile, at, limit, Action.SKIP),
                                Math.max(actionEnd(loadedSubtitleScenes, at, limit, Action.SKIP),
                                        actionEnd(observedCues, at, limit, Action.SKIP)));
                    }
                    if (activeMuteSegment == null) {
                        activeMuteSegment = findActiveSegment(ads, at, limit, Action.MUTE);
                    }
                }

                int adSkipEnd = -1;
                if (hasAds) {
                    adSkipEnd = actionEnd(ads, at, limit, Action.SKIP);
                    if (adSkipEnd < 0) {
                        adSkipEnd = Math.max(adSkipEnd, actionEnd(timedChapters, at, limit, Action.SKIP));
                        adSkipEnd = Math.max(adSkipEnd, chapterEnd(at, limit));
                        if (adSkipEnd < 0) {
                            adSkipEnd = Math.max(actionEnd(skipDb, at, limit, Action.SKIP),
                                    compatible ? actionEnd(online.breaks, at, limit, Action.SKIP) : -1);
                        }
                    }
                }

                int targetSkipEnd = Math.max(familySkipEnd, adSkipEnd);
                if (targetSkipEnd >= 0) {
                    setMutedInternal(false);
                    if (lastSkipEnd != targetSkipEnd) {
                        lastSkipEnd = targetSkipEnd;
                        seek.accept(targetSkipEnd);
                        notifyVisualEvent(familySkipEnd >= adSkipEnd ? EventKind.FAMILY_SKIP : EventKind.SKIP_ADS);
                    }
                } else {
                    lastSkipEnd = -1;
                    if (activeMuteSegment != null) {
                        setMutedInternal(true);
                        if (lastMuteSegmentStart != activeMuteSegment.start) {
                            lastMuteSegmentStart = activeMuteSegment.start;
                            notifyVisualEvent(EventKind.FAMILY_MUTE);
                        }
                    } else {
                        lastMuteSegmentStart = -1;
                        setMutedInternal(false);
                    }
                }
            } else {
                setMutedInternal(false);
            }
            main.postDelayed(check, 150);
        } else {
            setMutedInternal(false);
            if (needsDurationLookup) {
                main.postDelayed(check, 350);
            }
        }
    }

    private void setMutedInternal(boolean muted) {
        if (currentlyFilterMuted == muted) return;
        currentlyFilterMuted = muted;
        if (muteHandler != null) {
            try { muteHandler.accept(muted); } catch (Exception ignored) {}
        }
    }

    private void notifyVisualEvent(EventKind kind) {
        Activity host = hostRef.get();
        if (host == null || host.isFinishing() || host.isDestroyed()) return;
        if (!(host.getWindow().getDecorView() instanceof FrameLayout decor)) return;
        boolean ar = CinemaUi.arabic(host);
        if (pillBanner == null || pillBanner.getParent() != decor) {
            if (pillBanner != null && pillBanner.getParent() instanceof ViewGroup vg) {
                vg.removeView(pillBanner);
            }
            pillBanner = new LinearLayout(host);
            pillBanner.setOrientation(LinearLayout.HORIZONTAL);
            pillBanner.setGravity(Gravity.CENTER_VERTICAL);
            pillBanner.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            pillBanner.setFocusable(false);
            pillBanner.setClickable(false);
            pillBanner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            pillBanner.setBackground(CinemaUi.rounded(0xEB171717, CinemaUi.dp(host, 14), CinemaTokens.GOLD));
            int hPad = CinemaUi.dp(host, 14);
            int vPad = CinemaUi.dp(host, 8);
            pillBanner.setPadding(hPad, vPad, hPad, vPad);

            pillIcon = new ImageView(host);
            pillIcon.setImageTintList(ColorStateList.valueOf(CinemaTokens.GOLD));
            LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(CinemaUi.dp(host, 18), CinemaUi.dp(host, 18));
            icLp.setMarginEnd(CinemaUi.dp(host, 8));
            pillBanner.addView(pillIcon, icLp);

            pillText = CinemaUi.text(host, 13, true);
            pillText.setTextColor(0xFFF5F5F7);
            pillBanner.addView(pillText, new LinearLayout.LayoutParams(-2, -2));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            lp.topMargin = CinemaUi.dp(host, 28);
            lp.setMarginEnd(CinemaUi.dp(host, 28));
            pillBanner.setAlpha(0f);
            pillBanner.setVisibility(View.GONE);
            decor.addView(pillBanner, lp);
        }
        int iconRes = kind == EventKind.SKIP_ADS ? R.drawable.cinema_play_movie : R.drawable.cinema_ic_shield;
        String msg = switch (kind) {
            case SKIP_ADS -> ar ? "تخطّي تلقائي · الفاصل / المقدمة" : "Skip Ads · Intro / Break Skipped";
            case FAMILY_SKIP -> ar ? "المشاهدة العائلية · تم تخطّي مشهد" : "Family Watch · Scene Skipped";
            case FAMILY_MUTE -> ar ? "المشاهدة العائلية · كتم صوتي مؤقت" : "Family Watch · Audio Muted";
        };
        pillIcon.setImageResource(iconRes);
        pillText.setText(msg);
        pillBanner.bringToFront();
        pillBanner.setVisibility(View.VISIBLE);
        pillBanner.animate().cancel();
        pillBanner.animate().alpha(1f).setDuration(150).start();
        main.removeCallbacks(hidePill);
        main.postDelayed(hidePill, 2300L);
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
        if (title == null) return false;
        String label = title.strip().toLowerCase(Locale.ROOT).replaceAll("[_-]+", " ");
        return BREAK_CHAPTER.matcher(label).matches()
                || label.matches("(?:إعلانات|إعلان|فاصل(?: إعلاني)?|مقدمة|شارة البداية|ملخص(?: ما سبق)?)(?: [0-9]+)?");
    }

    private static int actionEnd(List<Segment> segments, int position, int duration, Action wanted) {
        int end = -1;
        for (Segment segment : segments) {
            if (segment.action == wanted && segment.start <= position && position < segment.end) {
                int clampedEnd = Math.min(duration, segment.end);
                if (clampedEnd > position) end = Math.max(end, clampedEnd);
            }
        }
        return end;
    }

    private static Segment findActiveSegment(List<Segment> segments, int position, int duration, Action wanted) {
        for (Segment segment : segments) {
            int clampedEnd = Math.min(duration, segment.end);
            if (segment.action == wanted && segment.start <= position && position < clampedEnd) {
                return segment;
            }
        }
        return null;
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
                case "smb", "smbj" -> read(FileEditorFactory.getFileEditorForUrl(sidecar, context).getInputStream());
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
        String current = uri.toString();
        for (int redirect = 0; redirect <= 3; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(4000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "LocalCinema/1.0");
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            try {
                int status = connection.getResponseCode();
                if ((status == 301 || status == 302 || status == 303 || status == 307 || status == 308) && redirect < 3) {
                    String location = connection.getHeaderField("Location");
                    if (location != null && !location.isBlank()) {
                        URL nextUrl = new URL(new URL(current), location);
                        String proto = nextUrl.getProtocol();
                        if ("http".equalsIgnoreCase(proto) || "https".equalsIgnoreCase(proto)) {
                            current = nextUrl.toString();
                            continue;
                        }
                    }
                }
                if (status == 404 && cacheNotFound) return "{}";
                return status == 200 ? read(connection.getInputStream()) : "";
            } finally {
                connection.disconnect();
            }
        }
        return "";
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
        if (text == null || !text.startsWith("WEBVTT MovieContentFilter")) return List.of();
        List<Segment> segments = new ArrayList<>();
        var cues = MCF_CUE.matcher(text.replace("\r\n", "\n"));
        while (cues.find()) {
            String mode = cues.group(4);
            Action action = "audio".equalsIgnoreCase(mode) ? Action.MUTE : Action.SKIP;
            add(segments, timestampMs(cues.group(1)), timestampMs(cues.group(2)), action);
        }
        return segments;
    }

    private static long timestampMs(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 3_600_000L
                + Integer.parseInt(parts[1]) * 60_000L + Math.round(Double.parseDouble(parts[2]) * 1000);
    }

    private static List<Segment> parseEdl(String text, boolean includeSkips, boolean includeMutes) {
        if (text == null || text.isBlank()) return List.of();
        List<Segment> segments = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 3) continue;
            Action action = switch (parts[2]) {
                case "0", "3" -> includeSkips ? Action.SKIP : null;
                case "1" -> includeMutes ? Action.MUTE : null;
                default -> null;
            };
            if (action == null) continue;
            try {
                double start = Double.parseDouble(parts[0]);
                double end = Double.parseDouble(parts[1]);
                if (Double.isFinite(start) && Double.isFinite(end) && start >= 0 && end <= Integer.MAX_VALUE / 1000.0) {
                    add(segments, Math.round(start * 1000), Math.round(end * 1000), action);
                }
            } catch (NumberFormatException ignored) {}
        }
        return segments;
    }

    private static void add(List<Segment> segments, long start, long end, Action action) {
        if (start >= 0 && end > start && end <= Integer.MAX_VALUE) {
            segments.add(new Segment((int) start, (int) end, action));
        }
    }
}

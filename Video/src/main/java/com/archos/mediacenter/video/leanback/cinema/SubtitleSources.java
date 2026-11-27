package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.preference.PreferenceManager;

import com.archos.mediacenter.video.browser.adapters.object.Episode;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.leanback.details.VideoDetailsFragment;
import com.archos.mediacenter.video.utils.SubtitlesDownloaderActivity2;
import com.archos.mediascraper.BaseTags;
import com.archos.mediascraper.EpisodeTags;
import com.archos.mediascraper.MovieTags;
import com.archos.mediascraper.ShowTags;
import com.archos.mediascraper.TagsFactory;
import com.meedx.providers.OpenMetadata;
import com.meedx.providers.ProviderKeys;
import com.meedx.providers.UnifiedSubtitleEngine;

public final class SubtitleSources {
    static final String MODE = "cinema_subtitle_source";
    public static final String EXTRA_POSTER_TITLE = "cinema_poster_title";
    public static final String EXTRA_ALT_DB_TITLE = "cinema_alt_db_title";
    public static final String EXTRA_CLEAN_FILENAME = "cinema_clean_filename";
    public static final String EXTRA_HAS_INDEXED_META = "cinema_has_indexed_meta";
    public static final String EXTRA_TMDB_ID = "tmdb_id";

    /**
     * Strict 3-tier subtitle search seed resolved from MediaDB / Poster metadata:
     * 1. {@code primaryPosterTitle} — Clean scraped poster/TMDb English or original title (Priority 1, auto-selected).
     * 2. {@code alternateDbTitle} — Alternate/localized MediaDB title cleaned of '|' & quality tags (Priority 2).
     * 3. {@code cleanedFilename} — Cleaned raw filename (Priority 3 fallback ONLY when {@code hasIndexedRecord} is false;
     *    otherwise shown as a secondary clickable candidate chip).
     */
    public record MediaSearchSeed(
            String primaryPosterTitle,
            String englishTitleHint,
            String alternateDbTitle,
            String cleanedFilename,
            String imdbId,
            long tmdbId,
            int year,
            boolean isSeries,
            int season,
            int episode,
            boolean hasIndexedRecord) {}

    private SubtitleSources() {}

    public static MediaSearchSeed resolveSeed(
            Context context,
            Video video,
            String posterTitleHint,
            String englishHint,
            String imdbHint,
            long tmdbHint,
            int yearHint) {
        Context app = context != null ? context.getApplicationContext() : null;
        boolean isSeries = video instanceof Episode;
        int season = video instanceof Episode ep ? ep.getSeasonNumber() : -1;
        int episode = video instanceof Episode ep ? ep.getEpisodeNumber() : -1;
        int year = yearHint > 0 ? yearHint : (video instanceof Movie m ? m.getYear() : -1);
        long tmdbId = tmdbHint > 0L ? tmdbHint : (video instanceof Movie m ? m.getOnlineId() : 0L);
        String imdb = validImdb(imdbHint) ? imdbHint : "";

        String rawFileLeaf = "";
        if (video != null) {
            if (video.getFilenameNonCryptic() != null && !video.getFilenameNonCryptic().isBlank()) {
                rawFileLeaf = video.getFilenameNonCryptic();
            } else if (video.getFileUri() != null && video.getFileUri().getLastPathSegment() != null) {
                rawFileLeaf = video.getFileUri().getLastPathSegment();
            } else if (video.getFilePath() != null && !video.getFilePath().isBlank()) {
                String fp = video.getFilePath();
                int sl = Math.max(fp.lastIndexOf('/'), fp.lastIndexOf('\\'));
                rawFileLeaf = sl >= 0 ? fp.substring(sl + 1) : fp;
            } else if (video.getName() != null) {
                rawFileLeaf = video.getName();
            }
        }
        String cleanedFilename = UnifiedSubtitleEngine.cleanRawTitle(rawFileLeaf);
        String pipeAltFromFile = UnifiedSubtitleEngine.extractAlternatePipeTitle(rawFileLeaf);

        String dbScrapedTitle = "";
        String tmdbEnglishTitle = cleanMetaTitle(englishHint);
        String explicitPoster = cleanMetaTitle(posterTitleHint);
        boolean indexedRecord = false;

        // 1. NOVA MediaDB Scraper Tags (MovieTags / EpisodeTags / ShowTags)
        if (app != null && video != null) {
            try {
                BaseTags tags = video.getFullScraperTags(app);
                if (tags instanceof EpisodeTags epTags) {
                    isSeries = true;
                    if (season <= 0) season = epTags.getSeason();
                    if (episode <= 0) episode = epTags.getEpisode();
                    ShowTags show = TagsFactory.buildShowTags(app, epTags.getShowId());
                    if (show != null) {
                        indexedRecord = true;
                        String st = cleanMetaTitle(show.getTitle());
                        if (!st.isEmpty()) dbScrapedTitle = st;
                        if (imdb.isEmpty() && validImdb(show.getImdbId())) imdb = show.getImdbId();
                        if (tmdbId <= 0L && show.getOnlineId() > 0L) tmdbId = show.getOnlineId();
                    }
                } else if (tags != null) {
                    indexedRecord = true;
                    String mtTitle = cleanMetaTitle(tags.getTitle());
                    if (!mtTitle.isEmpty()) dbScrapedTitle = mtTitle;
                    if (imdb.isEmpty() && validImdb(tags.getImdbId())) imdb = tags.getImdbId();
                    if (tmdbId <= 0L && tags.getOnlineId() > 0L) tmdbId = tags.getOnlineId();
                    if (year <= 0 && tags instanceof MovieTags mt && mt.getYear() > 0) year = mt.getYear();
                }
            } catch (Exception ignored) {}
        }

        // 2. CinemaWatchStore saved poster identity (populated by OpenMetadata / CinemaPosters)
        if (app != null && video != null && video.getId() > 0) {
            try {
                CinemaWatchStore.Identity idRec = CinemaWatchStore.getIdentity(app, "v:" + video.getId());
                if (idRec != null) {
                    String savedTitle = cleanMetaTitle(idRec.title());
                    if (!savedTitle.isEmpty()) {
                        indexedRecord = true;
                        if (dbScrapedTitle.isEmpty()) dbScrapedTitle = savedTitle;
                        else if (UnifiedSubtitleEngine.containsNonLatin(dbScrapedTitle)
                                && !UnifiedSubtitleEngine.containsNonLatin(savedTitle)) {
                            if (tmdbEnglishTitle.isEmpty()) tmdbEnglishTitle = savedTitle;
                        }
                    }
                    if (imdb.isEmpty() && validImdb(idRec.imdbId())) { imdb = idRec.imdbId(); indexedRecord = true; }
                    if (tmdbId <= 0L && idRec.tmdbId() > 0L) { tmdbId = idRec.tmdbId(); indexedRecord = true; }
                    if (year <= 0 && idRec.year() > 0) year = idRec.year();
                }
            } catch (Exception ignored) {}
        }

        // 3. Cached OpenMetadata poster item (zero network blocking if already cached)
        if (app != null && video != null) {
            try {
                CinemaPosters.Query q = CinemaPosters.query(video, isSeries);
                if (year <= 0 && q.year() > 0) year = q.year();
                OpenMetadata.Item cached = OpenMetadata.getCachedOnly(
                        app, new OpenMetadata.Query(q.title(), q.year(), isSeries), imdb.isEmpty() ? null : imdb);
                if (cached != null) {
                    indexedRecord = true;
                    String omTitle = cleanMetaTitle(cached.title());
                    if (!omTitle.isEmpty()) {
                        if (!UnifiedSubtitleEngine.containsNonLatin(omTitle) && tmdbEnglishTitle.isEmpty()) {
                            tmdbEnglishTitle = omTitle;
                        }
                        if (dbScrapedTitle.isEmpty()) dbScrapedTitle = omTitle;
                    }
                    if (imdb.isEmpty() && validImdb(cached.imdb())) imdb = cached.imdb();
                    if (year <= 0 && cached.year() != null && cached.year().matches("\\d{4}")) {
                        year = Integer.parseInt(cached.year());
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3b. Cached CinemaArtwork hero (populated by Home poster repair for PersianMovies / SMB files)
        if (app != null && video != null && video.getId() > 0) {
            for (String langKey : new String[]{"en", MetadataLanguage.resolve(app)}) {
                try {
                    CinemaArtwork.Art hero = CinemaArtwork.getCachedHero(app, langKey + ":v:" + video.getId());
                    if (hero != null) {
                        String ht = cleanMetaTitle(hero.title());
                        if (!ht.isEmpty()) {
                            indexedRecord = true;
                            if (!UnifiedSubtitleEngine.containsNonLatin(ht) && tmdbEnglishTitle.isEmpty()) {
                                tmdbEnglishTitle = ht;
                            }
                            if (dbScrapedTitle.isEmpty()) dbScrapedTitle = ht;
                        }
                        if (imdb.isEmpty() && validImdb(hero.imdbId())) { imdb = hero.imdbId(); indexedRecord = true; }
                        if (year <= 0 && hero.year() > 0) year = hero.year();
                    }
                } catch (Exception ignored) {}
            }
        }

        // 4. TMDb English/original title via CinemaArtwork when tmdbId > 0
        if (app != null && tmdbId > 0L && (tmdbEnglishTitle.isEmpty() || imdb.isEmpty())) {
            try {
                CinemaArtwork.Art enArt = CinemaArtwork.load(app, tmdbId, isSeries, "en");
                if (enArt != null) {
                    indexedRecord = true;
                    String enTitle = cleanMetaTitle(enArt.title());
                    if (!enTitle.isEmpty() && !UnifiedSubtitleEngine.containsNonLatin(enTitle)) {
                        tmdbEnglishTitle = enTitle;
                    }
                    if (imdb.isEmpty() && validImdb(enArt.imdbId())) imdb = enArt.imdbId();
                    if (year <= 0 && enArt.year() > 0) year = enArt.year();
                }
            } catch (Exception ignored) {}
        }

        if (!explicitPoster.isEmpty()) {
            // Guard against raw pipe filename (including Unicode '│') accidentally passed as posterTitleHint
            String normHint = UnifiedSubtitleEngine.normalizePipesAndBidi(posterTitleHint);
            boolean looksRawFilename = !normHint.isEmpty()
                    && (normHint.contains("|") || normHint.matches("(?i).*\\.(mp4|mkv|avi|webm)$"));
            if (!looksRawFilename) {
                indexedRecord = true;
                if (!UnifiedSubtitleEngine.containsNonLatin(explicitPoster) && tmdbEnglishTitle.isEmpty()) {
                    tmdbEnglishTitle = explicitPoster;
                }
                if (dbScrapedTitle.isEmpty()) dbScrapedTitle = explicitPoster;
            }
        }
        if (!imdb.isEmpty() || tmdbId > 0L) indexedRecord = true;

        // Assemble Priority 1 (Clean English/Poster DB Title) & Priority 2 (Alternate/Localized DB Title)
        String priority1 = "";
        String priority2 = "";
        if (indexedRecord) {
            if (!tmdbEnglishTitle.isEmpty() && !UnifiedSubtitleEngine.containsNonLatin(tmdbEnglishTitle)) {
                priority1 = tmdbEnglishTitle;
                if (!dbScrapedTitle.isEmpty() && !dbScrapedTitle.equalsIgnoreCase(priority1)) {
                    priority2 = dbScrapedTitle;
                } else if (!explicitPoster.isEmpty() && !explicitPoster.equalsIgnoreCase(priority1)) {
                    priority2 = explicitPoster;
                }
            } else if (!dbScrapedTitle.isEmpty()) {
                priority1 = dbScrapedTitle;
                if (!explicitPoster.isEmpty() && !explicitPoster.equalsIgnoreCase(priority1)) {
                    priority2 = explicitPoster;
                }
            } else if (!explicitPoster.isEmpty()) {
                priority1 = explicitPoster;
            }
            if (priority2.isEmpty() && !pipeAltFromFile.isEmpty() && !pipeAltFromFile.equalsIgnoreCase(priority1)) {
                priority2 = pipeAltFromFile;
            }
        } else {
            priority1 = cleanedFilename;
            if (!pipeAltFromFile.isEmpty() && !pipeAltFromFile.equalsIgnoreCase(priority1)) {
                priority2 = pipeAltFromFile;
            }
        }
        if (priority1.isEmpty()) priority1 = cleanedFilename;

        String bestEnglishHint = !tmdbEnglishTitle.isEmpty() ? tmdbEnglishTitle
                : (!UnifiedSubtitleEngine.containsNonLatin(priority1) ? priority1 : "");

        return new MediaSearchSeed(
                priority1,
                bestEnglishHint,
                priority2,
                cleanedFilename,
                imdb,
                tmdbId,
                year,
                isSeries,
                season,
                episode,
                indexedRecord);
    }

    private static boolean validImdb(String id) {
        return id != null && id.trim().matches("tt[0-9]+");
    }

    private static String cleanMetaTitle(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String t = raw.replaceAll("(?i)[\\s·-]+S\\d{1,2}\\s*E\\d{1,3}.*$", "").trim();
        return UnifiedSubtitleEngine.cleanRawTitle(t);
    }

    public static int selected(Context context) {
        int value = PreferenceManager.getDefaultSharedPreferences(context).getInt(MODE, 0);
        return value >= 0 && value <= 2 ? value : 0;
    }

    static String title(Context context) {
        return CinemaUi.label(context, "إعدادات الترجمة", "Subtitle Settings");
    }

    static String label(Context context) {
        boolean ar = CinemaUi.arabic(context);
        return (ar ? "بحث ذكي · " : "Smart Search · ")
                + com.meedx.providers.UnifiedSubtitleConfig.searchSelectionSummary(context, ar);
    }

    static void settings(Activity activity) {
        activity.startActivity(new Intent(activity, SubsourceSettingsActivity.class));
    }

    static void search(Activity activity, Video video) {
        search(activity, video, null, null, null, 0L, -1);
    }

    static void search(Activity activity, Video video, String posterTitle, String englishHint,
                       String imdbId, long tmdbId, int year) {
        Intent intent = new Intent(activity, SubsourceSearchActivity.class);
        if (video != null) intent.putExtra(VideoDetailsFragment.EXTRA_VIDEO, video);
        if (posterTitle != null && !posterTitle.isBlank()) intent.putExtra(EXTRA_POSTER_TITLE, posterTitle);
        if (englishHint != null && !englishHint.isBlank()) intent.putExtra("english_title", englishHint);
        if (imdbId != null && imdbId.matches("tt[0-9]+")) intent.putExtra("imdb_id", imdbId);
        if (tmdbId > 0L) intent.putExtra(EXTRA_TMDB_ID, tmdbId);
        if (year > 0) intent.putExtra("release_year", year);
        intent.putExtra("both_providers", true);
        activity.startActivity(intent);
    }

    private static void start(Activity activity, Video video, boolean openSubtitles) {
        if (openSubtitles && ProviderKeys.get(activity, ProviderKeys.OPEN_SUBTITLES).isEmpty()) {
            activity.startActivity(new Intent(activity, SubsourceSearchActivity.class)
                    .putExtra(VideoDetailsFragment.EXTRA_VIDEO, video).putExtra("both_providers", true));
            return;
        }
        Intent intent;
        if (openSubtitles) {
            intent = new Intent(activity, SubtitlesDownloaderActivity2.class)
                    .putExtra(SubtitlesDownloaderActivity2.FILE_URL, video.getFilePath());
        } else {
            intent = new Intent(activity, SubsourceSearchActivity.class)
                    .putExtra(VideoDetailsFragment.EXTRA_VIDEO, video);
        }
        activity.startActivity(intent);
    }

    static void missing(Activity activity, String source) {
        CinemaUi.dialog(activity).setTitle(source)
                .setMessage(CinemaUi.label(activity, "أضف مفتاح API لهذا المصدر من إعدادات الترجمة.",
                        "Add this provider's API key in subtitle settings."))
                .setPositiveButton(title(activity), (d, w) -> settings(activity))
                .setNegativeButton(android.R.string.cancel, null).show();
    }
}

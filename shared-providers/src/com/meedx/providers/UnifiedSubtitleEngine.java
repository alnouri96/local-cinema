package com.meedx.providers;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Shared multi-source subtitle search, English/original title resolver, and downloader
 * used by both TV (Video) and Phone (Phone).
 */
public final class UnifiedSubtitleEngine {
    private static final String UA = "LocalCinema/1.0 (https://github.com/alnouri96/local-cinema)";
    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final Pattern YEAR_PATTERN = Pattern.compile("(?:^|[\\s._(-])((?:19|20)\\d{2})(?=$|[\\s._)-])");
    private static final Pattern SE_PATTERN = Pattern.compile("(?i)S(\\d{1,2})[\\s._-]*E(\\d{1,3})");
    private static final Pattern NON_LATIN = Pattern.compile("[\\p{InArabic}\\p{InCJK_Unified_Ideographs}\\p{InHangul_Syllables}\\p{InHiragana}\\p{InKatakana}\\p{InCyrillic}\\p{InHebrew}\\p{InDevanagari}]");
    private static final Pattern YIFY_ROW = Pattern.compile(
            "data-id=\"(\\d+)\"[\\s\\S]{0,650}?<span class=\"sub-lang\">([^<]+)</span>[\\s\\S]{0,420}?href=\"(/subtitles/[^\"]+)\"[\\s\\S]{0,220}?subtitle\\s*</span>\\s*([^<]+)<");

    public record TitleCandidate(String title, String imdbId, int year, String source, int confidence) {}

    public static final class ResolvedQuery {
        public final String displayTitle;
        public final String englishTitle;
        public final String imdbId;
        public final int year;
        public final boolean isSeries;
        public final int season;
        public final int episode;
        public final boolean resolvedFromNonEnglish;
        public final List<TitleCandidate> candidates;

        public ResolvedQuery(String displayTitle, String englishTitle, String imdbId, int year,
                             boolean isSeries, int season, int episode, boolean resolvedFromNonEnglish) {
            this(displayTitle, englishTitle, imdbId, year, isSeries, season, episode, resolvedFromNonEnglish, List.of());
        }

        public ResolvedQuery(String displayTitle, String englishTitle, String imdbId, int year,
                             boolean isSeries, int season, int episode, boolean resolvedFromNonEnglish,
                             List<TitleCandidate> candidates) {
            this.displayTitle = displayTitle == null ? "" : displayTitle.trim();
            this.englishTitle = (englishTitle == null || englishTitle.isBlank()) ? this.displayTitle : englishTitle.trim();
            this.imdbId = imdbId == null ? "" : imdbId.trim();
            this.year = year;
            this.isSeries = isSeries;
            this.season = season;
            this.episode = episode;
            this.resolvedFromNonEnglish = resolvedFromNonEnglish;
            this.candidates = candidates == null || candidates.isEmpty() ? List.of() : List.copyOf(candidates);
        }
    }

    public static final class Candidate {
        public final String sourceId;
        public final String sourceBadge;
        public final String languageCanon;
        public final String releaseName;
        public final String format;
        public final String downloadUrl;
        public final String referer;
        public final String downloadKind; // "plain", "gzip", "zip", "subsource", "opensubtitles_api"
        public final String externalId;
        public final int score;
        public final SubtitleMatch.Confidence confidence;

        public Candidate(String sourceId, String sourceBadge, String languageCanon, String releaseName,
                         String format, String downloadUrl, String referer, String downloadKind,
                         String externalId, int score, SubtitleMatch.Confidence confidence) {
            this.sourceId = sourceId;
            this.sourceBadge = sourceBadge;
            this.languageCanon = languageCanon;
            this.releaseName = releaseName == null ? "" : releaseName.trim();
            this.format = format == null || format.isBlank() ? "SRT" : format.toUpperCase(Locale.ROOT);
            this.downloadUrl = downloadUrl == null ? "" : downloadUrl;
            this.referer = referer == null ? "" : referer;
            this.downloadKind = downloadKind == null ? "plain" : downloadKind;
            this.externalId = externalId == null ? "" : externalId;
            this.score = score;
            this.confidence = confidence == null ? SubtitleMatch.Confidence.MEDIUM : confidence;
        }
    }

    public static final class SubtitleFile {
        public final String name;
        public final String extension;
        public final byte[] utf8Bytes;

        public SubtitleFile(String name, String extension, byte[] utf8Bytes) {
            this.name = name;
            this.extension = extension;
            this.utf8Bytes = utf8Bytes;
        }
    }

    public static final class ProviderTestResult {
        public final boolean ok;
        public final int resultCount;
        public final long latencyMs;
        public final String arMessage;
        public final String enMessage;

        public ProviderTestResult(boolean ok, int resultCount, long latencyMs, String arMessage, String enMessage) {
            this.ok = ok;
            this.resultCount = resultCount;
            this.latencyMs = latencyMs;
            this.arMessage = arMessage;
            this.enMessage = enMessage;
        }
    }

    private UnifiedSubtitleEngine() {}

    public static boolean containsNonLatin(String text) {
        return text != null && NON_LATIN.matcher(text).find();
    }

    private static final Pattern PIPE_CHARS = Pattern.compile("[|\\u2502\\u2503\\uff5c\\u00a6\\u2758]");
    private static final Pattern INVISIBLE_BIDI = Pattern.compile("[\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]");
    private static final Pattern PIPE_COUNTRY_NOISE = Pattern.compile(
            "(?iu)^(?:ايرانى|ایرانی|ايراني|فارسی|فارسى|عربي|عربى|مترجم|مدبلج|فيلم|فیلم|مسلسل|سریال|iranian|persian|movie|film|series|hd|fhd|uhd|4k)$");

    /** Normalizes box-drawing/fullwidth vertical bars (e.g. U+2502 '│') to '|' and strips invisible bidi marks. */
    public static String normalizePipesAndBidi(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String stripped = INVISIBLE_BIDI.matcher(raw).replaceAll("");
        return PIPE_CHARS.matcher(stripped).replaceAll("|");
    }

    public static String cleanRawTitle(String raw) {
        if (raw == null) return "";
        String norm = normalizePipesAndBidi(raw);
        List<String> segs = extractCleanPipeSegments(norm);
        if (!segs.isEmpty()) {
            for (String s : segs) {
                if (!containsNonLatin(s) && containsLatinLetters(s)) return s;
            }
            return segs.get(0);
        }
        return cleanSingleSegmentTitle(norm, false);
    }

    /** Extracts the secondary localized title from pipe-delimited names (e.g. "Saye Haye Roshan │ سایه های روشن │ 2025"). */
    public static String extractAlternatePipeTitle(String raw) {
        if (raw == null) return "";
        String norm = normalizePipesAndBidi(raw);
        if (!norm.contains("|")) return "";
        List<String> segs = extractCleanPipeSegments(norm);
        if (segs.size() < 2) return "";
        String primary = cleanRawTitle(norm);
        for (String s : segs) {
            if (!s.equalsIgnoreCase(primary)) return s;
        }
        return "";
    }

    public static List<String> extractCleanPipeSegments(String raw) {
        if (raw == null) return List.of();
        String norm = normalizePipesAndBidi(raw);
        if (!norm.contains("|")) return List.of();
        String withoutExt = norm.trim().replaceFirst("(?i)\\.(mp4|mkv|avi|webm|mov|m4v|ts|wmv)$", "");
        String[] parts = withoutExt.split("\\|");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String cleaned = cleanSingleSegmentTitle(part, true);
            if (cleaned.isEmpty()) continue;
            if (cleaned.matches("(?:19|20)\\d{2}")) continue;
            if (PIPE_COUNTRY_NOISE.matcher(cleaned).matches()) continue;
            boolean dup = false;
            for (String ex : out) {
                if (ex.equalsIgnoreCase(cleaned)) { dup = true; break; }
            }
            if (!dup) out.add(cleaned);
        }
        return out;
    }

    private static String cleanSingleSegmentTitle(String raw, boolean fromPipe) {
        if (raw == null) return "";
        String t = normalizePipesAndBidi(raw).trim()
                .replaceFirst("(?i)\\.(mp4|mkv|avi|webm|mov|m4v|ts|wmv)$", "")
                .replaceAll("\\[[^\\]]*\\]|\\{[^}]*\\}", " ")
                .replaceAll("\\((?:فيلم|مسلسل|انمي|أنمي|movie|series|film)?\\s*(?:19|20)\\d{2}\\)", " ")
                .replaceAll("(?i)(?:^|\\s|\\b)(?:2160p|1440p|1080p|720p|480p|360p|4K|8K|WEB[- .]?DL|WEBRip|Blu[- .]?Ray|BDRip|HDRip|HDTV|DVDRip|BRRip|HEVC|AVC|H\\.?26[45]|X\\.?26[45]|AV1|10bit|AAC|AC3|DTS|HDR10\\+?|HDR|SDR)(?:\\b|\\s|$).*", "")
                .replaceAll("^(?:فيلم|فیلم|مسلسل|سریال|أنمي|انمي)\\s+", "")
                .replace('.', ' ')
                .replace('_', ' ')
                .replace('|', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (fromPipe) {
            t = t.replaceFirst("(?i)\\s+(?:Movie|Film|Series)$", "").trim();
            t = t.replaceFirst("\\s+(?:19|20)\\d{2}$", "").trim();
        }
        return t;
    }

    public static ResolvedQuery resolveQuery(Context context, String rawTitle, String knownImdb,
                                             int knownYear, boolean knownSeries, int knownSeason, int knownEpisode) {
        return resolveQuery(context, rawTitle, null, null, knownImdb, 0L, knownYear, knownSeries, knownSeason, knownEpisode, false);
    }

    public static ResolvedQuery resolveQuery(Context context, String rawTitle, String preferredEnglishHint,
                                             String knownImdb, int knownYear, boolean knownSeries,
                                             int knownSeason, int knownEpisode) {
        return resolveQuery(context, rawTitle, preferredEnglishHint, null, knownImdb, 0L,
                knownYear, knownSeries, knownSeason, knownEpisode, false);
    }

    /**
     * Resolves an Arabic/Persian/non-English, MediaDB-indexed, or raw filename title into verified
     * English/original title candidates, release year, and IMDb ID.
     * When {@code authoritativePosterTitle} is true (indexed MediaDB/poster item), the clean poster/TMDb
     * title is strictly preserved as Priority #1, followed by {@code alternateDbTitle} as Priority #2.
     */
    public static ResolvedQuery resolveQuery(Context context, String rawTitle, String preferredEnglishHint,
                                             String alternateDbTitle, String knownImdb, long knownTmdbId,
                                             int knownYear, boolean knownSeries, int knownSeason,
                                             int knownEpisode, boolean authoritativePosterTitle) {
        String display = normalizePipesAndBidi(rawTitle == null ? "" : rawTitle.trim());
        String englishHint = cleanRawTitle(preferredEnglishHint == null ? "" : preferredEnglishHint);
        String altDbClean = cleanRawTitle(alternateDbTitle == null ? "" : alternateDbTitle);
        if (altDbClean.isEmpty() && display.contains("|")) {
            altDbClean = extractAlternatePipeTitle(display);
        }
        int season = knownSeason;
        int episode = knownEpisode;
        if (display.length() > 0 && (season <= 0 || episode <= 0)) {
            Matcher se = SE_PATTERN.matcher(display);
            if (se.find()) {
                if (season <= 0) season = Integer.parseInt(se.group(1));
                if (episode <= 0) episode = Integer.parseInt(se.group(2));
            }
        }
        boolean isSeries = knownSeries || season > 0 || episode > 0;
        int year = knownYear;
        if (year <= 0 && !display.isEmpty()) {
            Matcher ym = YEAR_PATTERN.matcher(display);
            if (ym.find()) {
                try { year = Integer.parseInt(ym.group(1)); } catch (Exception ignored) {}
            }
        }
        String cleaned = cleanRawTitle(display.replaceAll("(?i)S\\d{1,2}[\\s._-]*E\\d{1,3}.*$", "").trim());
        String imdb = (knownImdb != null && knownImdb.matches("tt[0-9]+")) ? knownImdb : "";
        String englishTitle = !englishHint.isBlank() && !containsNonLatin(englishHint) ? englishHint : cleaned;
        boolean translated = false;
        List<TitleCandidate> candidates = new ArrayList<>();

        // Step 0: Direct TMDb ID lookup when MediaDB provides tmdbId (resolves clean English/original title & IMDb ID)
        if (context != null && knownTmdbId > 0L) {
            String tmdbKey = ProviderKeys.get(context, ProviderKeys.TMDB).trim();
            if (!tmdbKey.isEmpty()) {
                try {
                    String epType = isSeries ? "tv" : "movie";
                    String u = "https://api.themoviedb.org/3/" + epType + "/" + knownTmdbId
                            + "?api_key=" + encode(tmdbKey) + "&language=en-US&append_to_response=external_ids";
                    JSONObject detail = new JSONObject(httpGet(u, UA, null, 4500, 5500));
                    String tmdbEn = cleanRawTitle(detail.optString(isSeries ? "name" : "title", ""));
                    String tmdbOrig = cleanRawTitle(detail.optString(isSeries ? "original_name" : "original_title", ""));
                    JSONObject ext = detail.optJSONObject("external_ids");
                    String extImdb = ext != null ? ext.optString("imdb_id", "").trim() : detail.optString("imdb_id", "").trim();
                    if (imdb.isEmpty() && extImdb.matches("tt[0-9]+")) imdb = extImdb;
                    if (year <= 0) {
                        String rd = detail.optString(isSeries ? "first_air_date" : "release_date", "");
                        if (rd.length() >= 4 && rd.substring(0, 4).matches("\\d{4}")) {
                            year = Integer.parseInt(rd.substring(0, 4));
                        }
                    }
                    String bestTmdbTitle = (!tmdbEn.isEmpty() && !containsNonLatin(tmdbEn)) ? tmdbEn
                            : ((!tmdbOrig.isEmpty() && !containsNonLatin(tmdbOrig)) ? tmdbOrig : tmdbEn);
                    if (!bestTmdbTitle.isEmpty()) {
                        translated = containsNonLatin(cleaned) && !containsNonLatin(bestTmdbTitle);
                        englishTitle = bestTmdbTitle;
                        addTitleCandidate(candidates, new TitleCandidate(bestTmdbTitle, imdb, year, "TMDb", 100));
                    }
                    if (!tmdbOrig.isEmpty() && !tmdbOrig.equalsIgnoreCase(bestTmdbTitle)) {
                        addTitleCandidate(candidates, new TitleCandidate(tmdbOrig, imdb, year, "TMDb Original", 96));
                    }
                } catch (Exception ignored) {}
            }
        }

        // Priority 1 seed: If caller passed an authoritative scraped MediaDB/Poster title, lock it in as Candidate #1
        if (authoritativePosterTitle) {
            String p1 = (!englishHint.isBlank() && !containsNonLatin(englishHint)) ? englishHint
                    : (!containsNonLatin(englishTitle) && !englishTitle.isBlank() ? englishTitle : cleaned);
            if (!p1.isBlank()) {
                addTitleCandidate(candidates, new TitleCandidate(p1, imdb, year, "MediaDB", 100));
                englishTitle = p1;
            }
        }

        // Step 1: If we already have a valid IMDb ID, pull exact English title & year from Cinemeta (~240ms)
        if (!imdb.isEmpty()) {
            try {
                String type = isSeries ? "series" : "movie";
                JSONObject metaObj = new JSONObject(httpGet(
                        "https://v3-cinemeta.strem.io/meta/" + type + "/" + encode(imdb) + ".json",
                        UA, null, 5000, 6000)).optJSONObject("meta");
                if (metaObj != null) {
                    String name = metaObj.optString("name", "").trim();
                    if (!name.isEmpty()) {
                        translated = containsNonLatin(cleaned) && !containsNonLatin(name);
                        englishTitle = name;
                    }
                    if (year <= 0) {
                        Matcher ym = YEAR_PATTERN.matcher(metaObj.optString("releaseInfo", ""));
                        if (ym.find()) year = Integer.parseInt(ym.group(1));
                    }
                    addTitleCandidate(candidates, new TitleCandidate(englishTitle, imdb, year, "Cinemeta", 100));
                }
            } catch (Exception ignored) {}
            if (!altDbClean.isBlank() && !altDbClean.equalsIgnoreCase(englishTitle)) {
                addTitleCandidate(candidates, new TitleCandidate(altDbClean, imdb, year, "MediaDB Alt", 94));
            }
            if (!containsNonLatin(englishTitle) && !englishTitle.isBlank()) {
                return new ResolvedQuery(cleaned.isEmpty() ? englishTitle : cleaned, englishTitle, imdb, year,
                        isSeries, season, episode, translated, candidates);
            }
        }

        // Step 2: Explicit English/Latin hint (e.g. Persian Stremio meta.title_en / slug) -> IMDb Suggestion + Cinemeta
        if (!englishHint.isBlank() && !containsNonLatin(englishHint)) {
            collectImdbAndCinemetaCandidates(englishHint, year, isSeries, candidates);
            if (candidates.isEmpty()) {
                addTitleCandidate(candidates, new TitleCandidate(englishHint, imdb, year, "Addon Meta", 75));
            }
        }

        // Step 3: TMDb search (if user has TMDb key — natively understands Persian, Arabic, CJK & original titles)
        if (context != null && !cleaned.isEmpty() && (candidates.isEmpty() || imdb.isEmpty())) {
            String tmdbKey = ProviderKeys.get(context, ProviderKeys.TMDB).trim();
            if (!tmdbKey.isEmpty()) {
                String tmdbQuery = !englishHint.isBlank() ? englishHint : cleaned;
                collectTmdbCandidates(tmdbKey, tmdbQuery, cleaned, year, isSeries, candidates);
            }
        }

        // Step 4: Latin query -> IMDb Suggestion + Cinemeta
        if (!cleaned.isEmpty() && !containsNonLatin(cleaned) && candidates.isEmpty()) {
            collectImdbAndCinemetaCandidates(cleaned, year, isSeries, candidates);
        }

        // Step 5: Strict high-confidence Wikidata & Wikipedia lookup for non-Latin titles (ONLY when similarity >= 0.68)
        if (!cleaned.isEmpty() && containsNonLatin(cleaned) && candidates.isEmpty()) {
            List<String> wikiLangs = detectWikiLanguages(cleaned);
            for (String searchLang : wikiLangs) {
                if (!candidates.isEmpty()) break;
                try {
                    String wdUrl = "https://www.wikidata.org/w/api.php?action=wbsearchentities&search="
                            + encode(cleaned) + "&language=" + searchLang
                            + "&uselang=en&type=item&limit=6&format=json";
                    JSONArray searchArr = new JSONObject(httpGet(wdUrl, UA, null, 5000, 6000)).optJSONArray("search");
                    if (searchArr != null) {
                        for (int i = 0; i < searchArr.length() && candidates.size() < 3; i++) {
                            JSONObject item = searchArr.optJSONObject(i);
                            if (item == null) continue;
                            String qid = item.optString("id", "");
                            String lbl = item.optString("label", "").trim();
                            String desc = item.optString("description", "").toLowerCase(Locale.ROOT);
                            JSONObject matchObj = item.optJSONObject("match");
                            String matchedText = matchObj != null ? matchObj.optString("text", "") : "";
                            if (qid.isEmpty() || lbl.isEmpty()) continue;
                            boolean looksMedia = desc.contains("film") || desc.contains("movie")
                                    || desc.contains("television") || desc.contains("series")
                                    || desc.contains("drama") || desc.contains("anime");
                            if (!looksMedia) continue;
                            double sim = Math.max(titleSimilarity(cleaned, matchedText), titleSimilarity(cleaned, lbl));
                            if (sim < 0.68) continue;
                            String[] wdInfo = fetchWikidataEnglishAndImdb(qid);
                            String enName = (wdInfo[0] != null && !wdInfo[0].isBlank() && !containsNonLatin(wdInfo[0]))
                                    ? wdInfo[0] : (!containsNonLatin(lbl) ? lbl : "");
                            String foundImdb = (wdInfo[1] != null && wdInfo[1].matches("tt[0-9]+")) ? wdInfo[1] : "";
                            if (!enName.isEmpty() || !foundImdb.isEmpty()) {
                                int conf = (int) Math.round(sim * 90) + (foundImdb.isEmpty() ? 0 : 8);
                                addTitleCandidate(candidates, new TitleCandidate(
                                        enName.isEmpty() ? cleaned : enName, foundImdb, year, "Wikidata", Math.min(98, conf)));
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (candidates.isEmpty()) {
                for (String wikiLang : wikiLangs) {
                    if ("en".equals(wikiLang) || !candidates.isEmpty()) continue;
                    try {
                        String suffix = isSeries ? ("fa".equals(wikiLang) ? " سریال" : " مسلسل")
                                : ("fa".equals(wikiLang) ? " فیلم" : " فيلم");
                        String wpUrl = "https://" + wikiLang + ".wikipedia.org/w/api.php?action=query&generator=search&gsrsearch="
                                + encode(cleaned + suffix) + "&gsrlimit=5&prop=pageprops|langlinks&lllang=en&format=json";
                        JSONObject pages = new JSONObject(httpGet(wpUrl, UA, null, 5000, 6000))
                                .optJSONObject("query");
                        JSONObject pageMap = pages != null ? pages.optJSONObject("pages") : null;
                        if (pageMap != null) {
                            var keys = pageMap.keys();
                            while (keys.hasNext() && candidates.size() < 3) {
                                JSONObject pg = pageMap.optJSONObject(keys.next());
                                if (pg == null) continue;
                                // CRITICAL: verify the native Wikipedia article title ACTUALLY matches the user's title!
                                String nativePageTitle = pg.optString("title", "")
                                        .replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
                                double sim = titleSimilarity(cleaned, nativePageTitle);
                                if (sim < 0.68) continue;

                                JSONObject pp = pg.optJSONObject("pageprops");
                                String qid = pp != null ? pp.optString("wikibase_item", "") : "";
                                JSONArray ll = pg.optJSONArray("langlinks");
                                String enLink = (ll != null && ll.length() > 0)
                                        ? ll.getJSONObject(0).optString("*", "").replaceAll("\\s*\\([^)]*\\)$", "").trim()
                                        : "";
                                String foundImdb = "";
                                if (!qid.isEmpty()) {
                                    String[] wdInfo = fetchWikidataEnglishAndImdb(qid);
                                    if (wdInfo[0] != null && !wdInfo[0].isBlank() && !containsNonLatin(wdInfo[0])) {
                                        enLink = wdInfo[0];
                                    }
                                    if (wdInfo[1] != null && wdInfo[1].matches("tt[0-9]+")) {
                                        foundImdb = wdInfo[1];
                                    }
                                }
                                if (!enLink.isEmpty() && !containsNonLatin(enLink)) {
                                    int conf = (int) Math.round(sim * 88) + (foundImdb.isEmpty() ? 0 : 8);
                                    addTitleCandidate(candidates, new TitleCandidate(enLink, foundImdb, year, "Wikipedia", Math.min(96, conf)));
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        // Priority 2: Alternate/localized MediaDB title (cleaned of '|', quality tags, and extensions)
        if (!altDbClean.isBlank()) {
            addTitleCandidate(candidates, new TitleCandidate(altDbClean, imdb, year, "MediaDB Alt", 93));
        }

        // Rank candidates by confidence (descending) and pick top verified match ONLY if high confidence
        candidates.sort((a, b) -> Integer.compare(b.confidence(), a.confidence()));
        if (candidates.size() > 4) {
            candidates = new ArrayList<>(candidates.subList(0, 4));
        }

        if (!candidates.isEmpty()) {
            TitleCandidate top = candidates.get(0);
            if (!top.title().isBlank()) {
                translated = containsNonLatin(cleaned) && !containsNonLatin(top.title());
                englishTitle = top.title();
            }
            if (imdb.isEmpty() && !top.imdbId().isBlank()) imdb = top.imdbId();
            if (year <= 0 && top.year() > 0) year = top.year();
        }

        return new ResolvedQuery(
                cleaned.isEmpty() ? englishTitle : cleaned,
                englishTitle,
                imdb,
                year,
                isSeries,
                season,
                episode,
                translated,
                candidates
        );
    }

    private static void addTitleCandidate(List<TitleCandidate> list, TitleCandidate cand) {
        if (cand == null || cand.title().isBlank()) return;
        for (int i = 0; i < list.size(); i++) {
            TitleCandidate ex = list.get(i);
            boolean sameImdb = !cand.imdbId().isEmpty() && cand.imdbId().equalsIgnoreCase(ex.imdbId());
            boolean sameTitle = normalizeForCompare(cand.title()).equals(normalizeForCompare(ex.title()))
                    && (cand.year() <= 0 || ex.year() <= 0 || cand.year() == ex.year());
            if (sameImdb || sameTitle) {
                if (cand.confidence() > ex.confidence() || (ex.imdbId().isEmpty() && !cand.imdbId().isEmpty())) {
                    list.set(i, new TitleCandidate(
                            cand.title(),
                            !cand.imdbId().isEmpty() ? cand.imdbId() : ex.imdbId(),
                            cand.year() > 0 ? cand.year() : ex.year(),
                            cand.source(),
                            Math.max(cand.confidence(), ex.confidence())));
                }
                return;
            }
        }
        list.add(cand);
    }

    private static void collectImdbAndCinemetaCandidates(String queryText, int knownYear, boolean isSeries,
                                                         List<TitleCandidate> out) {
        // 1. Keyless IMDb public suggestion endpoint (fast & supports transliterated Iranian/foreign titles!)
        try {
            String imdbUrl = "https://v3.sg.media-imdb.com/suggestion/x/" + encode(queryText) + ".json";
            JSONArray d = new JSONObject(httpGet(imdbUrl, BROWSER_UA, null, 4500, 5500)).optJSONArray("d");
            if (d != null) {
                for (int i = 0; i < Math.min(d.length(), 8); i++) {
                    JSONObject item = d.optJSONObject(i);
                    if (item == null) continue;
                    String id = item.optString("id", "").trim();
                    String label = item.optString("l", "").trim();
                    String qid = item.optString("qid", "").toLowerCase(Locale.ROOT);
                    int y = item.optInt("y", -1);
                    if (!id.matches("tt[0-9]+") || label.isEmpty()) continue;
                    boolean itemIsSeries = qid.contains("series") || qid.contains("mini");
                    if (isSeries && !itemIsSeries && !qid.isEmpty()) continue;
                    if (!isSeries && itemIsSeries) continue;
                    double sim = titleSimilarity(queryText, label);
                    boolean yearMatch = knownYear > 0 && y > 0 && Math.abs(knownYear - y) <= 1;
                    if (sim < 0.60 && !(sim >= 0.45 && yearMatch)) continue;
                    int conf = (int) Math.round(sim * 85) + (yearMatch ? 12 : 0) + (i == 0 ? 3 : 0);
                    addTitleCandidate(out, new TitleCandidate(label, id, y, "IMDb", Math.min(99, conf)));
                }
            }
        } catch (Exception ignored) {}

        // 2. Keyless Cinemeta search
        try {
            String type = isSeries ? "series" : "movie";
            String url = "https://v3-cinemeta.strem.io/catalog/" + type + "/top/search=" + encode(queryText) + ".json";
            JSONArray metas = new JSONObject(httpGet(url, UA, null, 4500, 5500)).optJSONArray("metas");
            if (metas != null) {
                for (int i = 0; i < Math.min(metas.length(), 6); i++) {
                    JSONObject cand = metas.optJSONObject(i);
                    if (cand == null) continue;
                    String foundImdb = cand.optString("imdb_id", cand.optString("id", "")).trim();
                    String foundName = cand.optString("name", "").trim();
                    if (!foundImdb.matches("tt[0-9]+") || foundName.isEmpty()) continue;
                    int y = -1;
                    Matcher ym = YEAR_PATTERN.matcher(cand.optString("releaseInfo", ""));
                    if (ym.find()) {
                        try { y = Integer.parseInt(ym.group(1)); } catch (Exception ignored) {}
                    }
                    double sim = titleSimilarity(queryText, foundName);
                    boolean yearMatch = knownYear > 0 && y > 0 && Math.abs(knownYear - y) <= 1;
                    if (sim < 0.62 && !(sim >= 0.48 && yearMatch)) continue;
                    int conf = (int) Math.round(sim * 84) + (yearMatch ? 12 : 0);
                    addTitleCandidate(out, new TitleCandidate(foundName, foundImdb, y, "Cinemeta", Math.min(98, conf)));
                }
            }
        } catch (Exception ignored) {}
    }

    private static void collectTmdbCandidates(String tmdbKey, String queryText, String originalInput,
                                              int knownYear, boolean isSeries, List<TitleCandidate> out) {
        try {
            String endpoint = isSeries ? "tv" : "movie";
            StringBuilder u = new StringBuilder("https://api.themoviedb.org/3/search/")
                    .append(endpoint)
                    .append("?api_key=").append(encode(tmdbKey))
                    .append("&language=en-US&query=").append(encode(queryText));
            if (knownYear > 0) u.append(isSeries ? "&first_air_date_year=" : "&year=").append(knownYear);
            JSONArray arr = new JSONObject(httpGet(u.toString(), UA, null, 5000, 6000)).optJSONArray("results");
            if (arr == null) return;
            for (int i = 0; i < Math.min(arr.length(), 4); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String enName = item.optString(isSeries ? "name" : "title", "").trim();
                String origName = item.optString(isSeries ? "original_name" : "original_title", "").trim();
                String picked = !enName.isEmpty() ? enName : origName;
                if (picked.isEmpty()) continue;
                int y = -1;
                String rd = item.optString(isSeries ? "first_air_date" : "release_date", "");
                if (rd.length() >= 4) {
                    try { y = Integer.parseInt(rd.substring(0, 4)); } catch (Exception ignored) {}
                }
                double sim = Math.max(
                        Math.max(titleSimilarity(queryText, enName), titleSimilarity(queryText, origName)),
                        Math.max(titleSimilarity(originalInput, enName), titleSimilarity(originalInput, origName)));
                boolean yearMatch = knownYear > 0 && y > 0 && Math.abs(knownYear - y) <= 1;
                if (sim < 0.58 && !(i == 0 && yearMatch && sim >= 0.40)) continue;
                String foundImdb = "";
                int tmdbId = item.optInt("id", 0);
                if (tmdbId > 0 && i < 2) {
                    try {
                        JSONObject ext = new JSONObject(httpGet(
                                "https://api.themoviedb.org/3/" + endpoint + "/" + tmdbId
                                        + "/external_ids?api_key=" + encode(tmdbKey),
                                UA, null, 4000, 4500));
                        String id = ext.optString("imdb_id", "").trim();
                        if (id.matches("tt[0-9]+")) foundImdb = id;
                    } catch (Exception ignored) {}
                }
                int conf = (int) Math.round(sim * 86) + (yearMatch ? 10 : 0) + (foundImdb.isEmpty() ? 0 : 4);
                addTitleCandidate(out, new TitleCandidate(picked, foundImdb, y, "TMDb", Math.min(99, conf)));
            }
        } catch (Exception ignored) {}
    }

    /** Normalized similarity (0.0..1.0) across Latin, Arabic, and Persian titles. */
    private static double titleSimilarity(String a, String b) {
        String na = normalizeForCompare(a);
        String nb = normalizeForCompare(b);
        if (na.isEmpty() || nb.isEmpty()) return 0.0;
        if (na.equals(nb)) return 1.0;

        String[] ta = na.split(" ");
        String[] tb = nb.split(" ");
        Set<String> sa = new LinkedHashSet<>();
        Set<String> sb = new LinkedHashSet<>();
        for (String s : ta) if (!s.isEmpty()) sa.add(s);
        for (String s : tb) if (!s.isEmpty()) sb.add(s);
        int inter = 0;
        for (String s : sa) if (sb.contains(s)) inter++;
        int union = sa.size() + sb.size() - inter;
        double jaccard = union > 0 ? ((double) inter / union) : 0.0;

        int dist = levenshtein(na, nb);
        int maxLen = Math.max(na.length(), nb.length());
        double levSim = maxLen > 0 ? (1.0 - ((double) dist / maxLen)) : 0.0;
        return Math.max(jaccard, levSim);
    }

    private static String normalizeForCompare(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT)
                .replace('\u06CC', '\u064A') // Persian yeh -> Arabic yeh
                .replace('\u06A9', '\u0643') // Persian kaf -> Arabic kaf
                .replace('\u0629', '\u0647') // Teh marbuta -> Heh
                .replace('\u0623', '\u0627').replace('\u0625', '\u0627').replace('\u0622', '\u0627')
                .replaceAll("[\\u064B-\\u065F\\u0670\\u200C]", "") // diacritics + ZWNJ
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return t;
    }

    private static int levenshtein(String a, String b) {
        int m = a.length();
        int n = b.length();
        int[] prev = new int[n + 1];
        int[] cur = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= n; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[n];
    }

    private static List<String> detectWikiLanguages(String text) {
        List<String> langs = new ArrayList<>();
        if (text == null || !containsNonLatin(text)) {
            langs.add("en");
            return langs;
        }
        boolean hasPersianSpecific = text.matches(".*[پچژگکی].*");
        boolean hasArabicScript = text.matches(".*[\\p{InArabic}].*");
        if (hasPersianSpecific) {
            langs.add("fa");
            langs.add("ar");
        } else if (hasArabicScript) {
            langs.add("ar");
            langs.add("fa");
        } else if (text.matches(".*[\\p{InCyrillic}].*")) {
            langs.add("ru");
        } else if (text.matches(".*[\\p{InHangul_Syllables}].*")) {
            langs.add("ko");
        } else if (text.matches(".*[\\p{InHiragana}\\p{InKatakana}].*")) {
            langs.add("ja");
        } else if (text.matches(".*[\\p{InCJK_Unified_Ideographs}].*")) {
            langs.add("zh");
        } else if (text.matches(".*[\\p{InDevanagari}].*")) {
            langs.add("hi");
        } else {
            langs.add("en");
        }
        return langs;
    }

    private static String[] fetchWikidataEnglishAndImdb(String qid) {
        String[] res = new String[]{"", ""};
        if (qid == null || qid.isBlank()) return res;
        try {
            JSONObject ent = new JSONObject(httpGet(
                    "https://www.wikidata.org/w/api.php?action=wbgetentities&ids="
                            + encode(qid) + "&props=labels|claims&languages=en&format=json",
                    UA, null, 4500, 5000)).optJSONObject("entities");
            JSONObject qObj = ent != null ? ent.optJSONObject(qid) : null;
            if (qObj != null) {
                JSONObject labels = qObj.optJSONObject("labels");
                JSONObject enLbl = labels != null ? labels.optJSONObject("en") : null;
                if (enLbl != null && !enLbl.optString("value", "").isBlank()) {
                    res[0] = enLbl.optString("value", "").trim();
                }
                JSONObject claims = qObj.optJSONObject("claims");
                JSONArray p345 = claims != null ? claims.optJSONArray("P345") : null;
                if (p345 != null && p345.length() > 0) {
                    JSONObject mainsnak = p345.optJSONObject(0).optJSONObject("mainsnak");
                    JSONObject dv = mainsnak != null ? mainsnak.optJSONObject("datavalue") : null;
                    String foundImdb = dv != null ? dv.optString("value", "").trim() : "";
                    if (foundImdb.matches("tt[0-9]+")) res[1] = foundImdb;
                }
            }
        } catch (Exception ignored) {}
        return res;
    }

    public record VideoParams(String filename, long videoSize, String videoHash, String addonItemId) {
        public static final VideoParams NONE = new VideoParams("", 0L, "", "");

        public VideoParams(String filename, long videoSize, String videoHash) {
            this(filename, videoSize, videoHash, "");
        }
    }

    /** Searches all requested subtitle sources concurrently and returns ranked, deduplicated results. */
    public static List<Candidate> search(Context context, ResolvedQuery query, List<String> wantedLanguages,
                                         Set<String> requestedSources, String videoFileName) {
        return search(context, query, wantedLanguages, requestedSources,
                new VideoParams(videoFileName == null ? "" : videoFileName, 0L, "", ""));
    }

    public static List<Candidate> search(Context context, ResolvedQuery query, List<String> wantedLanguages,
                                         Set<String> requestedSources, VideoParams params) {
        if (context == null || query == null) return Collections.emptyList();
        VideoParams vp = params == null ? VideoParams.NONE : params;
        Set<String> sources = (requestedSources == null || requestedSources.isEmpty())
                ? UnifiedSubtitleConfig.selectedSearchSources(context)
                : requestedSources;
        Set<String> runnable = UnifiedSubtitleConfig.runnableSources(context, sources);
        if (runnable.isEmpty()) return Collections.emptyList();

        List<String> langs = new ArrayList<>();
        if (wantedLanguages != null) {
            for (String l : wantedLanguages) {
                String c = UnifiedSubtitleConfig.canonicalLangName(l);
                if (!c.isEmpty() && !langs.contains(c)) langs.add(c);
            }
        }
        if (langs.isEmpty()) langs.addAll(UnifiedSubtitleConfig.languagePriorities(context));

        String releaseRef = (vp.filename() != null && !vp.filename().isBlank()) ? vp.filename() : query.englishTitle;
        long searchStartMs = System.currentTimeMillis();
        long addonDeadlineMs = searchStartMs + 5000L;     // Strict 5.0s max wall-clock budget for Stremio subtitle add-ons
        long standardDeadlineMs = searchStartMs + 11000L; // Shared wall-clock budget for built-in/API sources
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(12, Math.max(2, runnable.size())));
        record RunningSource(String srcId, Future<List<Candidate>> future) {}
        List<RunningSource> running = new ArrayList<>(runnable.size());
        for (String srcId : runnable) {
            running.add(new RunningSource(srcId, pool.submit(
                    taskForSource(context.getApplicationContext(), srcId, query, langs, releaseRef, vp, addonDeadlineMs))));
        }

        Map<String, Candidate> dedup = new LinkedHashMap<>();
        for (RunningSource rs : running) {
            boolean isAddonSource = UnifiedSubtitleConfig.isStremioSubProviderId(rs.srcId())
                    || UnifiedSubtitleConfig.SRC_STREMIO_ADDONS.equals(rs.srcId());
            long deadline = isAddonSource ? addonDeadlineMs : standardDeadlineMs;
            long waitMs = Math.max(40L, deadline - System.currentTimeMillis());
            try {
                List<Candidate> batch = rs.future().get(waitMs, TimeUnit.MILLISECONDS);
                if (batch != null) {
                    for (Candidate c : batch) {
                        String key = c.sourceId + "|" + c.downloadUrl + "|" + c.externalId + "|" + c.releaseName;
                        dedup.putIfAbsent(key, c);
                    }
                }
            } catch (Throwable ignored) {
                rs.future().cancel(true);
            }
        }
        pool.shutdownNow();

        List<Candidate> ranked = new ArrayList<>(dedup.values());
        ranked.sort(Comparator
                .comparingInt((Candidate c) -> languagePriorityIndex(c.languageCanon, langs))
                .thenComparing(Comparator.comparingInt((Candidate c) -> c.score).reversed()));
        return ranked;
    }

    private static int languagePriorityIndex(String canon, List<String> langs) {
        int idx = langs.indexOf(canon);
        return idx >= 0 ? idx : langs.size() + 1;
    }

    private static Callable<List<Candidate>> taskForSource(Context app, String srcId, ResolvedQuery q,
                                                           List<String> langs, String releaseRef, VideoParams vp,
                                                           long addonDeadlineMs) {
        return () -> {
            if (UnifiedSubtitleConfig.isStremioSubProviderId(srcId)) {
                return searchSingleStremioSubtitleAddon(app, srcId, q, langs, releaseRef, vp, addonDeadlineMs);
            }
            return switch (srcId) {
                case UnifiedSubtitleConfig.SRC_OPENSUBTITLES_V3 -> searchOpenSubtitlesV3(q, langs, releaseRef, vp);
                case UnifiedSubtitleConfig.SRC_OPENSUBTITLES_FREE -> searchOpenSubtitlesFree(q, langs, releaseRef, vp);
                case UnifiedSubtitleConfig.SRC_YIFY -> searchYify(q, langs, releaseRef);
                case UnifiedSubtitleConfig.SRC_GESTDOWN -> searchGestdown(q, langs, releaseRef);
                case UnifiedSubtitleConfig.SRC_STREMIO_ADDONS -> searchStremioSubtitleAddons(app, q, langs, releaseRef, vp, addonDeadlineMs);
                case UnifiedSubtitleConfig.SRC_SUBSOURCE -> searchSubsource(app, q, langs, releaseRef);
                case UnifiedSubtitleConfig.SRC_SUBDL -> searchSubdl(app, q, langs, releaseRef);
                case UnifiedSubtitleConfig.SRC_OPENSUBTITLES_API -> searchOpenSubtitlesApi(app, q, langs, releaseRef, vp);
                default -> Collections.emptyList();
            };
        };
    }

    // ---- 1. OpenSubtitles v3 (Stremio keyless) ----
    private static List<Candidate> searchOpenSubtitlesV3(ResolvedQuery q, List<String> langs,
                                                         String releaseRef, VideoParams vp) {
        List<Candidate> out = new ArrayList<>();
        if (q.imdbId.isEmpty()) return out;
        try {
            String mediaId = q.isSeries && q.season > 0 && q.episode > 0
                    ? q.imdbId + ":" + q.season + ":" + q.episode : q.imdbId;
            String type = q.isSeries ? "series" : "movie";
            String extra = StremioAddons.buildSubtitleExtraSegment(vp.filename(), vp.videoSize(), vp.videoHash());
            JSONArray subs = null;
            if (!extra.isEmpty()) {
                try {
                    String extraUrl = "https://opensubtitles-v3.strem.io/subtitles/" + type + "/"
                            + Uri.encode(mediaId, ":") + "/" + extra + ".json";
                    subs = new JSONObject(httpGet(extraUrl, UA, null, 6000, 9000)).optJSONArray("subtitles");
                } catch (Exception ignored) {}
            }
            if (subs == null || subs.length() == 0) {
                String plainUrl = "https://opensubtitles-v3.strem.io/subtitles/" + type + "/"
                        + Uri.encode(mediaId, ":") + ".json";
                subs = new JSONObject(httpGet(plainUrl, UA, null, 6000, 9000)).optJSONArray("subtitles");
            }
            if (subs == null) return out;
            boolean hasHash = vp.videoHash() != null && vp.videoHash().trim().matches("(?i)[0-9a-f]{16}");
            for (int i = 0; i < subs.length() && out.size() < 30; i++) {
                JSONObject s = subs.optJSONObject(i);
                if (s == null) continue;
                String subUrl = s.optString("url", "").trim();
                if (!subUrl.startsWith("http")) continue;
                String langCanon = UnifiedSubtitleConfig.canonicalLangName(s.optString("lang", ""));
                if (!matchesWantedLanguages(langCanon, langs)) continue;
                String rel = s.optString("SubFileName", s.optString("title", s.optString("id", q.englishTitle)));
                SubtitleMatch.Result r = rankCandidate(releaseRef, rel, true, q);
                if (r.episodeConflict()) continue;
                int hashBonus = (hasHash && i < 3) ? 8 : 0;
                out.add(new Candidate(UnifiedSubtitleConfig.SRC_OPENSUBTITLES_V3, "OpenSubtitles v3",
                        langCanon, rel, detectFormat(rel, subUrl), subUrl, "", "plain",
                        s.optString("id", ""), r.score() + 12 + hashBonus, r.confidence()));
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ---- 2. OpenSubtitles Legacy REST (Keyless, works by IMDb OR verified Latin text query) ----
    private static List<Candidate> searchOpenSubtitlesFree(ResolvedQuery q, List<String> langs,
                                                           String releaseRef, VideoParams vp) {
        List<Candidate> out = new ArrayList<>();
        // Rule 1: Never send pure non-Latin text queries without IMDb ID (rest.opensubtitles.org returns trending junk)
        boolean hasImdb = !q.imdbId.isEmpty() && q.imdbId.matches("tt[0-9]+");
        if (!hasImdb && (q.englishTitle.isBlank() || !containsLatinLetters(q.englishTitle))) {
            return out;
        }
        try {
            StringBuilder langIds = new StringBuilder();
            for (String l : langs) {
                String iso3 = UnifiedSubtitleConfig.toIso3(l);
                if (!iso3.isEmpty()) {
                    if (langIds.length() > 0) langIds.append(',');
                    langIds.append(iso3);
                }
            }
            if (langIds.length() == 0) langIds.append("ara,eng");

            StringBuilder u = new StringBuilder("https://rest.opensubtitles.org/search");
            if (q.isSeries && q.episode > 0) u.append("/episode-").append(q.episode);
            if (hasImdb) {
                u.append("/imdbid-").append(q.imdbId.substring(2));
            } else {
                u.append("/query-").append(encode(q.englishTitle));
            }
            if (q.isSeries && q.season > 0) u.append("/season-").append(q.season);
            u.append("/sublanguageid-").append(langIds);

            JSONArray arr = new JSONArray(httpGet(u.toString(), "TemporaryUserAgent", null, 7000, 10000));
            for (int i = 0; i < arr.length() && out.size() < 30; i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String dlUrl = item.optString("SubDownloadLink", "").trim();
                if (!dlUrl.startsWith("http")) continue;
                String langCanon = UnifiedSubtitleConfig.canonicalLangName(
                        item.optString("SubLanguageID", item.optString("LanguageName", "")));
                if (!matchesWantedLanguages(langCanon, langs)) continue;

                String rowImdb = item.optString("IDMovieImdb", "").trim();
                String rowMovieName = item.optString("MovieName", "").trim();
                String rel = item.optString("SubFileName", item.optString("MovieReleaseName", q.englishTitle));

                // Rule 2: Strict relevance filter — reject unrelated/trending movies completely
                if (!matchesSearchTarget(q, rowImdb, rowMovieName, rel)) continue;

                boolean idOk = hasImdb && imdbDigitsMatch(q.imdbId, rowImdb);
                SubtitleMatch.Result r = rankCandidate(releaseRef, rel, idOk, q);
                if (r.episodeConflict()) continue;
                String kind = dlUrl.toLowerCase(Locale.ROOT).endsWith(".gz") ? "gzip" : "plain";
                out.add(new Candidate(UnifiedSubtitleConfig.SRC_OPENSUBTITLES_FREE, "OpenSubtitles Free",
                        langCanon, rel, detectFormat(rel, dlUrl), dlUrl, "", kind,
                        item.optString("IDSubtitleFile", ""), r.score() + (idOk ? 10 : 4), r.confidence()));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static boolean containsLatinLetters(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) return true;
        }
        return false;
    }

    private static boolean imdbDigitsMatch(String queryImdb, String rowImdb) {
        if (queryImdb == null || rowImdb == null) return false;
        String qd = queryImdb.replaceFirst("(?i)^tt", "").replaceFirst("^0+", "").trim();
        String rd = rowImdb.replaceFirst("(?i)^tt", "").replaceFirst("^0+", "").trim();
        return !qd.isEmpty() && qd.equals(rd);
    }

    /**
     * Strict relevance gate shared across all text-based subtitle providers:
     * - When both query and result have IMDb IDs, requires exact match.
     * - Otherwise verifies genuine title token overlap or similarity against English/original title
     *   so trending/unrelated movies (e.g. The Odyssey 2026) never leak into search results.
     */
    private static boolean matchesSearchTarget(ResolvedQuery q, String resultImdb,
                                               String resultMovieName, String resultRelease) {
        if (q == null) return false;
        boolean qHasImdb = !q.imdbId.isEmpty() && q.imdbId.matches("tt[0-9]+");
        String rImdbClean = resultImdb == null ? "" : resultImdb.trim();
        if (qHasImdb && !rImdbClean.isEmpty()) {
            return imdbDigitsMatch(q.imdbId, rImdbClean);
        }
        if (qHasImdb && rImdbClean.isEmpty()) {
            // If provider already searched strictly by IMDb ID and returned no row IMDb, verify text isn't conflicting
            return true;
        }
        String movie = resultMovieName == null ? "" : resultMovieName.replaceAll("\"([^\"]+)\"", "$1").trim();
        String combined = (movie + " " + (resultRelease == null ? "" : resultRelease)).trim();
        if (combined.isEmpty()) return false;

        return matchesTitleString(q.englishTitle, movie, combined)
                || (!q.displayTitle.equalsIgnoreCase(q.englishTitle)
                        && matchesTitleString(q.displayTitle, movie, combined));
    }

    private static boolean matchesTitleString(String targetTitle, String resultMovie, String resultCombined) {
        String normTarget = normalizeForCompare(targetTitle);
        if (normTarget.isEmpty()) return false;
        if (!resultMovie.isEmpty() && titleSimilarity(normTarget, resultMovie) >= 0.55) {
            return true;
        }
        String normCombined = normalizeForCompare(resultCombined);
        if (normCombined.contains(normTarget)) return true;

        String[] rawTokens = normTarget.split(" ");
        List<String> sigTokens = new ArrayList<>();
        for (String tok : rawTokens) {
            if (tok.length() >= 3) sigTokens.add(tok);
        }
        if (sigTokens.isEmpty()) {
            for (String tok : rawTokens) if (!tok.isEmpty()) sigTokens.add(tok);
        }
        if (sigTokens.isEmpty()) return false;

        Set<String> hayTokens = new LinkedHashSet<>();
        for (String h : normCombined.split(" ")) {
            if (!h.isEmpty()) hayTokens.add(h);
        }
        int matched = 0;
        for (String tok : sigTokens) {
            if (hayTokens.contains(tok)) {
                matched++;
            } else {
                // Allow single-char transliteration spelling variation (e.g. limoonia vs limonia)
                for (String h : hayTokens) {
                    if (tok.length() >= 4 && h.length() >= 4 && titleSimilarity(tok, h) >= 0.78) {
                        matched++;
                        break;
                    }
                }
            }
        }
        double ratio = (double) matched / sigTokens.size();
        return sigTokens.size() == 1 ? matched == 1 : ratio >= 0.60;
    }

    // ---- 3. YIFY Subtitles (Keyless Movies) ----
    private static List<Candidate> searchYify(ResolvedQuery q, List<String> langs, String releaseRef) {
        List<Candidate> out = new ArrayList<>();
        if (q.isSeries || q.imdbId.isEmpty()) return out;
        try {
            String pageUrl = "https://yifysubtitles.ch/movie-imdb/" + encode(q.imdbId);
            String html = httpGet(pageUrl, BROWSER_UA, null, 7000, 10000);
            Matcher m = YIFY_ROW.matcher(html);
            while (m.find() && out.size() < 25) {
                String id = m.group(1);
                String langCanon = UnifiedSubtitleConfig.canonicalLangName(m.group(2));
                if (!matchesWantedLanguages(langCanon, langs)) continue;
                String href = m.group(3);
                String rel = m.group(4).replaceAll("\\s+", " ").trim();
                String slug = href.replaceFirst("^/subtitles/", "");
                String zipUrl = "https://yifysubtitles.ch/subtitle/" + slug + ".zip";
                SubtitleMatch.Result r = rankCandidate(releaseRef, rel, true, q);
                out.add(new Candidate(UnifiedSubtitleConfig.SRC_YIFY, "YIFY Subtitles",
                        langCanon, rel.isEmpty() ? q.englishTitle : rel, "SRT",
                        zipUrl, "https://yifysubtitles.ch" + href, "zip",
                        id, r.score() + 8, r.confidence()));
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ---- 4. Gestdown / Addic7ed Open API (Keyless TV Series) ----
    private static List<Candidate> searchGestdown(ResolvedQuery q, List<String> langs, String releaseRef) {
        List<Candidate> out = new ArrayList<>();
        if (!q.isSeries || q.season <= 0 || q.episode <= 0
                || q.englishTitle.isBlank() || !containsLatinLetters(q.englishTitle)) return out;
        try {
            String searchUrl = "https://api.gestdown.info/shows/search/" + encode(q.englishTitle);
            JSONArray shows = new JSONObject(httpGet(searchUrl, UA, null, 6000, 8000)).optJSONArray("shows");
            if (shows == null || shows.length() == 0) return out;
            String showId = "";
            for (int si = 0; si < Math.min(shows.length(), 5); si++) {
                JSONObject sh = shows.optJSONObject(si);
                if (sh == null) continue;
                String candId = sh.optString("id", "").trim();
                String candName = sh.optString("name", "").trim();
                if (!candId.isEmpty() && matchesTitleString(q.englishTitle, candName, candName)) {
                    showId = candId;
                    break;
                }
            }
            if (showId.isEmpty()) return out;
            for (String lang : langs) {
                if (out.size() >= 20) break;
                String iso2 = UnifiedSubtitleConfig.toIso2(lang);
                if (iso2.isEmpty()) continue;
                try {
                    String epUrl = "https://api.gestdown.info/subtitles/get/" + encode(showId)
                            + "/" + q.season + "/" + q.episode + "/" + encode(iso2);
                    JSONArray subs = new JSONObject(httpGet(epUrl, UA, null, 6000, 8000)).optJSONArray("matchingSubtitles");
                    if (subs == null) continue;
                    for (int i = 0; i < subs.length() && out.size() < 20; i++) {
                        JSONObject s = subs.optJSONObject(i);
                        if (s == null || !s.optBoolean("completed", true)) continue;
                        String dlUri = s.optString("downloadUri", "");
                        if (dlUri.isEmpty()) continue;
                        String version = s.optString("version", q.englishTitle + " S" + q.season + "E" + q.episode);
                        SubtitleMatch.Result r = rankCandidate(releaseRef, version, true, q);
                        out.add(new Candidate(UnifiedSubtitleConfig.SRC_GESTDOWN, "Gestdown (Addic7ed)",
                                lang, version, "SRT", "https://api.gestdown.info" + dlUri, "", "plain",
                                s.optString("subtitleId", ""), r.score() + 10, r.confidence()));
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ---- 5. Installed Stremio Subtitle Add-ons (Ultimate Smart Subs (USS), SubSense, Persian Stremio, etc.) ----
    private static List<Candidate> searchSingleStremioSubtitleAddon(Context app, String providerId, ResolvedQuery q,
                                                                    List<String> langs, String releaseRef,
                                                                    VideoParams vp, long deadlineEpochMs) {
        List<Candidate> out = new ArrayList<>();
        try {
            UnifiedSubtitleConfig.ProviderInfo pInfo = UnifiedSubtitleConfig.providerById(app, providerId);
            if (pInfo == null || !pInfo.isStremioAddon) return out;
            String imdbBase = (q.imdbId != null && q.imdbId.matches("tt[0-9]+")) ? q.imdbId : "";
            String customId = vp.addonItemId() == null ? "" : vp.addonItemId().trim();
            if (imdbBase.isEmpty() && customId.isEmpty()) return out;
            String imdbMediaId = imdbBase.isEmpty() ? ""
                    : (q.isSeries && q.season > 0 && q.episode > 0
                            ? imdbBase + ":" + q.season + ":" + q.episode : imdbBase);
            String customMediaId = "";
            if (!customId.isEmpty()) {
                if (customId.startsWith("kitsu:") && !customId.matches("(?i)kitsu:[0-9]+:[0-9]+") && q.isSeries && q.episode > 0) {
                    customMediaId = customId + ":" + q.episode;
                } else {
                    customMediaId = customId;
                }
            }
            String type = q.isSeries ? "series" : "movie";
            String extra = StremioAddons.buildSubtitleExtraSegment(vp.filename(), vp.videoSize(), vp.videoHash());
            JSONArray subs = null;
            if (!imdbMediaId.isEmpty()) {
                subs = StremioAddons.querySubtitleEndpointByUrlFast(
                        pInfo.addonManifestUrl, pInfo.addonBaseUrl, type, imdbMediaId, extra,
                        3500, 4200, deadlineEpochMs);
            }
            if ((subs == null || subs.length() == 0) && !customMediaId.isEmpty() && !customMediaId.equals(imdbMediaId)) {
                subs = StremioAddons.querySubtitleEndpointByUrlFast(
                        pInfo.addonManifestUrl, pInfo.addonBaseUrl, type, customMediaId, extra,
                        3500, 4200, deadlineEpochMs);
            }
            appendStremioSubtitleRows(subs, providerId, pInfo.name, q, langs, releaseRef, out);
        } catch (Throwable ignored) {}
        return out;
    }

    private static List<Candidate> searchStremioSubtitleAddons(Context app, ResolvedQuery q,
                                                               List<String> langs, String releaseRef,
                                                               VideoParams vp, long deadlineEpochMs) {
        List<Candidate> out = new ArrayList<>();
        try {
            for (UnifiedSubtitleConfig.ProviderInfo p : UnifiedSubtitleConfig.installedStremioSubtitleProviders(app)) {
                if (System.currentTimeMillis() >= deadlineEpochMs) break;
                out.addAll(searchSingleStremioSubtitleAddon(app, p.id, q, langs, releaseRef, vp, deadlineEpochMs));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static void appendStremioSubtitleRows(JSONArray subs, String sourceId, String badgeName,
                                                  ResolvedQuery q, List<String> langs,
                                                  String releaseRef, List<Candidate> out) {
        if (subs == null) return;
        String badge = (badgeName != null && !badgeName.isBlank()) ? badgeName : "Stremio Add-on";
        for (int i = 0; i < subs.length() && out.size() < 30; i++) {
            JSONObject s = subs.optJSONObject(i);
            if (s == null) continue;
            String subUrl = s.optString("url", "").trim();
            if (!subUrl.startsWith("http")) continue;
            String rawLang = s.optString("lang", s.optString("language", ""));
            String langCanon = UnifiedSubtitleConfig.canonicalLangName(rawLang);
            if (!matchesWantedLanguages(langCanon, langs)) continue;
            String rel = s.optString("SubFileName",
                    s.optString("title", s.optString("name", s.optString("id", badge))));
            SubtitleMatch.Result r = rankCandidate(releaseRef, rel, true, q);
            if (r.episodeConflict()) continue;
            out.add(new Candidate(sourceId, badge,
                    langCanon, rel, detectFormat(rel, subUrl), subUrl, "", "plain",
                    s.optString("id", ""), r.score() + 11, r.confidence()));
        }
    }

    // ---- 6. Subsource API (Optional Key) ----
    private static List<Candidate> searchSubsource(Context app, ResolvedQuery q, List<String> langs, String releaseRef) {
        List<Candidate> out = new ArrayList<>();
        String key = UnifiedSubtitleConfig.resolveSubsourceKey(app);
        if (key.isEmpty()) return out;
        try {
            boolean useImdb = !q.imdbId.isEmpty() && q.imdbId.matches("tt[0-9]+");
            if (!useImdb && (q.englishTitle.isBlank() || !containsLatinLetters(q.englishTitle))) {
                return out;
            }
            String path = useImdb
                    ? "/movies/search?searchType=imdb&imdb=" + encode(q.imdbId)
                    : "/movies/search?searchType=text&q=" + encode(q.englishTitle);
            if (!useImdb && q.year > 0) path += "&year=" + q.year;
            if (q.isSeries && q.season > 0) path += "&season=" + q.season;
            JSONObject foundObj = new JSONObject(httpGet("https://api.subsource.net/api/v1" + path, UA,
                    Map.of("X-API-Key", key, "Accept", "application/json"), 8000, 10000));
            JSONArray found = foundObj.optJSONArray("data");
            if (found == null || found.length() == 0) return out;
            long movieId = -1;
            for (int i = 0; i < found.length(); i++) {
                JSONObject m = found.optJSONObject(i);
                if (m == null) continue;
                long id = m.optLong("movieId", -1);
                if (id <= 0) continue;
                String mImdb = m.optString("imdbId", "").trim();
                String mTitle = m.optString("title", "").trim();
                if (!matchesSearchTarget(q, mImdb, mTitle, mTitle)) continue;
                if (movieId < 0) movieId = id;
                boolean rightSeason = !q.isSeries || q.season <= 0 || m.optInt("season", -1) == q.season;
                boolean rightImdb = !useImdb || imdbDigitsMatch(q.imdbId, mImdb);
                if (rightImdb && rightSeason) { movieId = id; break; }
            }
            if (movieId <= 0) return out;

            for (String lang : langs) {
                if (out.size() >= 30) break;
                try {
                    String subPath = "/subtitles?movieId=" + movieId + "&language=" + encode(lang) + "&limit=40&sort=rating";
                    JSONArray subs = new JSONObject(httpGet("https://api.subsource.net/api/v1" + subPath, UA,
                            Map.of("X-API-Key", key, "Accept", "application/json"), 8000, 10000)).optJSONArray("data");
                    if (subs == null) continue;
                    for (int i = 0; i < subs.length() && out.size() < 30; i++) {
                        JSONObject s = subs.optJSONObject(i);
                        if (s == null) continue;
                        long sid = s.optLong("subtitleId", -1);
                        if (sid <= 0) continue;
                        String resLang = UnifiedSubtitleConfig.canonicalLangName(s.optString("language", lang));
                        JSONArray releases = s.optJSONArray("releaseInfo");
                        if (releases == null) releases = new JSONArray().put(s.optString("releaseInfo", q.englishTitle));
                        String bestRel = "";
                        SubtitleMatch.Result bestRank = null;
                        for (int j = 0; j < releases.length(); j++) {
                            String rel = releases.optString(j, "");
                            SubtitleMatch.Result r = rankCandidate(releaseRef, rel, true, q);
                            if (!r.episodeConflict() && (bestRank == null || r.score() > bestRank.score())) {
                                bestRank = r;
                                bestRel = rel;
                            }
                        }
                        if (bestRank != null) {
                            out.add(new Candidate(UnifiedSubtitleConfig.SRC_SUBSOURCE, "Subsource",
                                    resLang, bestRel.isEmpty() ? q.englishTitle : bestRel, "SRT",
                                    "https://api.subsource.net/api/v1/subtitles/" + sid + "/download",
                                    "", "subsource", String.valueOf(sid),
                                    bestRank.score() + 18, bestRank.confidence()));
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ---- 7. SubDL API (Optional Key) ----
    private static List<Candidate> searchSubdl(Context app, ResolvedQuery q, List<String> langs, String releaseRef) {
        List<Candidate> out = new ArrayList<>();
        String key = ProviderKeys.get(app, ProviderKeys.SUBDL).trim();
        if (key.isEmpty()) return out;
        boolean hasImdb = !q.imdbId.isEmpty() && q.imdbId.matches("tt[0-9]+");
        if (!hasImdb && (q.englishTitle.isBlank() || !containsLatinLetters(q.englishTitle))) {
            return out;
        }
        try {
            StringBuilder langCodes = new StringBuilder();
            for (String l : langs) {
                String iso2 = UnifiedSubtitleConfig.toIso2(l).toUpperCase(Locale.ROOT);
                if (!iso2.isEmpty()) {
                    if (langCodes.length() > 0) langCodes.append(',');
                    langCodes.append(iso2);
                }
            }
            Uri.Builder ub = Uri.parse("https://api.subdl.com/api/v1/subtitles").buildUpon()
                    .appendQueryParameter("api_key", key)
                    .appendQueryParameter("type", q.isSeries ? "tv" : "movie");
            if (hasImdb) ub.appendQueryParameter("imdb_id", q.imdbId);
            else ub.appendQueryParameter("film_name", q.englishTitle);
            if (q.year > 0) ub.appendQueryParameter("year", String.valueOf(q.year));
            if (q.isSeries && q.season > 0) ub.appendQueryParameter("season_number", String.valueOf(q.season));
            if (q.isSeries && q.episode > 0) ub.appendQueryParameter("episode_number", String.valueOf(q.episode));
            if (langCodes.length() > 0) ub.appendQueryParameter("languages", langCodes.toString());

            JSONObject resp = new JSONObject(httpGet(ub.build().toString(), UA, null, 7000, 10000));
            if (!hasImdb) {
                JSONArray resultsArr = resp.optJSONArray("results");
                if (resultsArr != null && resultsArr.length() > 0) {
                    boolean anyResultMatched = false;
                    for (int ri = 0; ri < resultsArr.length(); ri++) {
                        JSONObject rObj = resultsArr.optJSONObject(ri);
                        if (rObj == null) continue;
                        String rImdb = rObj.optString("imdb_id", "").trim();
                        String rName = rObj.optString("name", "").trim();
                        if (matchesSearchTarget(q, rImdb, rName, rName)) {
                            anyResultMatched = true;
                            break;
                        }
                    }
                    if (!anyResultMatched) return out;
                }
            }
            JSONArray subs = resp.optJSONArray("subtitles");
            if (subs == null) return out;
            for (int i = 0; i < subs.length() && out.size() < 25; i++) {
                JSONObject s = subs.optJSONObject(i);
                if (s == null) continue;
                String relUrl = s.optString("url", "").trim();
                if (relUrl.isEmpty()) continue;
                String zipUrl = relUrl.startsWith("http") ? relUrl : ("https://dl.subdl.com" + relUrl);
                String langCanon = UnifiedSubtitleConfig.canonicalLangName(s.optString("language", s.optString("lang", "")));
                String rel = s.optString("release_name", s.optString("name", q.englishTitle));
                if (!hasImdb && !matchesSearchTarget(q, "", s.optString("name", ""), rel)) continue;
                SubtitleMatch.Result r = rankCandidate(releaseRef, rel, hasImdb, q);
                if (r.episodeConflict()) continue;
                out.add(new Candidate(UnifiedSubtitleConfig.SRC_SUBDL, "SubDL",
                        langCanon, rel, "SRT", zipUrl, "", "zip",
                        s.optString("sd_id", String.valueOf(i)), r.score() + 14, r.confidence()));
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ---- 8. OpenSubtitles.com REST API (Optional Key) ----
    private static List<Candidate> searchOpenSubtitlesApi(Context app, ResolvedQuery q, List<String> langs,
                                                          String releaseRef, VideoParams vp) {
        List<Candidate> out = new ArrayList<>();
        String key = ProviderKeys.get(app, ProviderKeys.OPEN_SUBTITLES).trim();
        if (key.isEmpty()) return out;
        boolean hasImdb = !q.imdbId.isEmpty() && q.imdbId.matches("tt[0-9]+");
        if (!hasImdb && (q.englishTitle.isBlank() || !containsLatinLetters(q.englishTitle))) {
            return out;
        }
        try {
            StringBuilder langCodes = new StringBuilder();
            for (String l : langs) {
                String iso2 = UnifiedSubtitleConfig.toIso2(l);
                if (!iso2.isEmpty()) {
                    if (langCodes.length() > 0) langCodes.append(',');
                    langCodes.append(iso2);
                }
            }
            Uri.Builder ub = Uri.parse("https://api.opensubtitles.com/api/v1/subtitles").buildUpon();
            if (hasImdb) {
                ub.appendQueryParameter(q.isSeries ? "parent_imdb_id" : "imdb_id", q.imdbId.substring(2));
            } else {
                ub.appendQueryParameter("query", q.englishTitle);
            }
            if (vp.videoHash() != null && vp.videoHash().trim().matches("(?i)[0-9a-f]{16}")) {
                ub.appendQueryParameter("moviehash", vp.videoHash().trim().toLowerCase(Locale.ROOT));
            }
            if (q.isSeries && q.season > 0) ub.appendQueryParameter("season_number", String.valueOf(q.season));
            if (q.isSeries && q.episode > 0) ub.appendQueryParameter("episode_number", String.valueOf(q.episode));
            if (langCodes.length() > 0) ub.appendQueryParameter("languages", langCodes.toString());
            ub.appendQueryParameter("order_by", "download_count").appendQueryParameter("order_direction", "desc");

            JSONArray data = new JSONObject(httpGet(ub.build().toString(), "LocalCinema v1.0.0",
                    Map.of("Api-Key", key), 6000, 9000)).optJSONArray("data");
            if (data == null) return out;
            for (int i = 0; i < data.length() && out.size() < 25; i++) {
                JSONObject attr = data.getJSONObject(i).optJSONObject("attributes");
                if (attr == null) continue;
                String langCanon = UnifiedSubtitleConfig.canonicalLangName(attr.optString("language", ""));
                JSONArray files = attr.optJSONArray("files");
                if (files == null || files.length() == 0) continue;
                JSONObject f = files.getJSONObject(0);
                String fileId = f.optString("file_id", "");
                if (!fileId.matches("[0-9]+")) continue;
                String rel = f.optString("file_name", attr.optString("release", q.englishTitle));
                JSONObject fd = attr.optJSONObject("feature_details");
                String rowImdb = fd != null ? fd.optString("imdb_id", "") : "";
                String rowMovie = fd != null ? fd.optString("movie_name", fd.optString("title", "")) : "";
                if (!matchesSearchTarget(q, rowImdb, rowMovie, rel)) continue;
                SubtitleMatch.Result r = rankCandidate(releaseRef, rel, hasImdb, q);
                if (r.episodeConflict()) continue;
                out.add(new Candidate(UnifiedSubtitleConfig.SRC_OPENSUBTITLES_API, "OpenSubtitles API",
                        langCanon, rel, detectFormat(rel, ""), "", "", "opensubtitles_api",
                        fileId, r.score() + 15, r.confidence()));
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Downloads and unpacks any {@link Candidate} into one or more clean UTF-8 subtitle files. */
    public static List<SubtitleFile> download(Context context, Candidate candidate, int season, int episode) throws Exception {
        if (candidate == null) throw new IOException("No subtitle selected");
        byte[] raw;
        if ("subsource".equals(candidate.downloadKind)) {
            String key = UnifiedSubtitleConfig.resolveSubsourceKey(context);
            if (key.isEmpty()) throw new IOException("Subsource API key missing");
            raw = httpGetBytes(candidate.downloadUrl, UA, Map.of("X-API-Key", key, "Accept", "application/zip"), 12000, 20000, 20 * 1024 * 1024);
            return unpackZip(raw, season, episode);
        }
        if ("opensubtitles_api".equals(candidate.downloadKind)) {
            String key = ProviderKeys.get(context, ProviderKeys.OPEN_SUBTITLES).trim();
            if (key.isEmpty()) throw new IOException("OpenSubtitles API key missing");
            String body = new JSONObject().put("file_id", Long.parseLong(candidate.externalId)).toString();
            JSONObject resp = new JSONObject(httpPostJson("https://api.opensubtitles.com/api/v1/download", body,
                    "LocalCinema v1.0.0", Map.of("Api-Key", key)));
            String link = resp.optString("link", "").trim();
            if (!link.startsWith("http")) throw new IOException("No download link returned");
            raw = httpGetBytes(link, "LocalCinema v1.0.0", null, 10000, 15000, 4 * 1024 * 1024);
            return List.of(toSubtitleFile(candidate.releaseName, raw));
        }
        if ("zip".equals(candidate.downloadKind)) {
            Map<String, String> headers = candidate.referer.isEmpty() ? null : Map.of("Referer", candidate.referer);
            raw = httpGetBytes(candidate.downloadUrl, BROWSER_UA, headers, 10000, 18000, 20 * 1024 * 1024);
            if (raw.length >= 2 && raw[0] == 'P' && raw[1] == 'K') {
                return unpackZip(raw, season, episode);
            }
            return List.of(toSubtitleFile(candidate.releaseName, raw));
        }
        if ("gzip".equals(candidate.downloadKind)) {
            raw = httpGetBytes(candidate.downloadUrl, "TemporaryUserAgent", null, 10000, 15000, 4 * 1024 * 1024);
            byte[] unzipped = gunzip(raw);
            return List.of(toSubtitleFile(candidate.releaseName, unzipped));
        }
        raw = httpGetBytes(candidate.downloadUrl, UA, null, 8000, 15000, 4 * 1024 * 1024);
        if (raw.length >= 2 && raw[0] == 'P' && raw[1] == 'K') {
            return unpackZip(raw, season, episode);
        }
        return List.of(toSubtitleFile(candidate.releaseName, raw));
    }

    /** Live-tests a single provider against a known title and returns latency & live count. */
    public static ProviderTestResult testProvider(Context context, String sourceId) {
        UnifiedSubtitleConfig.ProviderInfo info = UnifiedSubtitleConfig.providerById(context, sourceId);
        if (info == null) {
            return new ProviderTestResult(false, 0, 0, "مصدر غير معروف", "Unknown source");
        }
        if (!info.keyless && !UnifiedSubtitleConfig.hasKey(context, info)) {
            return new ProviderTestResult(false, 0, 0,
                    "يحتاج مفتاح API — أضف المفتاح أولاً", "API key required — add your key first");
        }
        long t0 = System.currentTimeMillis();
        try {
            ResolvedQuery probe = UnifiedSubtitleConfig.SRC_GESTDOWN.equals(sourceId)
                    ? new ResolvedQuery("Breaking Bad", "Breaking Bad", "tt0903747", 2008, true, 1, 1, false)
                    : new ResolvedQuery("The Matrix", "The Matrix", "tt0133093", 1999, false, -1, -1, false);
            List<String> probeLangs = UnifiedSubtitleConfig.SRC_GESTDOWN.equals(sourceId)
                    ? List.of("english", "arabic") : List.of("arabic", "english");
            List<Candidate> found = search(context, probe, probeLangs, Set.of(sourceId), probe.englishTitle);
            long ms = System.currentTimeMillis() - t0;
            if (!found.isEmpty()) {
                return new ProviderTestResult(true, found.size(), ms,
                        "شغّال ✓ · " + found.size() + " نتيجة خلال " + ms + " ms",
                        "Online ✓ · " + found.size() + " results in " + ms + " ms");
            }
            return new ProviderTestResult(false, 0, ms,
                    "تم الاتصال لكن لم ترد نتائج (" + ms + " ms)",
                    "Reachable, 0 results returned (" + ms + " ms)");
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            return new ProviderTestResult(false, 0, ms,
                    "فشل الاتصال (" + ms + " ms)",
                    "Connection failed (" + ms + " ms)");
        }
    }

    private static SubtitleMatch.Result rankCandidate(String releaseRef, String candidateRel, boolean imdbVerified, ResolvedQuery q) {
        return SubtitleMatch.rank(releaseRef, candidateRel, imdbVerified,
                q.isSeries ? q.season : -1, q.isSeries ? q.episode : -1,
                null, null, null, null, 0, 0);
    }

    private static boolean matchesWantedLanguages(String candCanon, List<String> wanted) {
        if (wanted == null || wanted.isEmpty()) return true;
        if (candCanon == null || candCanon.isBlank()) return false;
        return wanted.contains(candCanon);
    }

    private static String detectFormat(String name, String url) {
        String lower = ((name == null ? "" : name) + " " + (url == null ? "" : url)).toLowerCase(Locale.ROOT);
        if (lower.contains(".vtt")) return "VTT";
        if (lower.contains(".ass")) return "ASS";
        if (lower.contains(".ssa")) return "SSA";
        return "SRT";
    }

    public static byte[] normalizeUtf8(byte[] bytes) throws IOException {
        String text;
        if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
            text = new String(bytes, StandardCharsets.UTF_16LE);
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
            text = new String(bytes, StandardCharsets.UTF_16BE);
        } else {
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                text = new String(bytes, Charset.forName("windows-1256"));
            }
        }
        text = text.replace("\uFEFF", "");
        if (!text.contains("-->") && !text.contains("Dialogue:") && !text.contains("[Script Info]")) {
            throw new IOException("Invalid text subtitle");
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static SubtitleFile toSubtitleFile(String name, byte[] raw) throws IOException {
        byte[] clean = normalizeUtf8(raw);
        String leaf = (name == null || name.isBlank()) ? "subtitle.srt" : name.replace('\\', '/');
        int slash = leaf.lastIndexOf('/');
        if (slash >= 0) leaf = leaf.substring(slash + 1);
        String ext = "srt";
        String lower = leaf.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".vtt")) ext = "vtt";
        else if (lower.endsWith(".ass")) ext = "ass";
        else if (lower.endsWith(".ssa")) ext = "ssa";
        else if (!lower.endsWith(".srt")) leaf = leaf + ".srt";
        return new SubtitleFile(leaf, ext, clean);
    }

    private static List<SubtitleFile> unpackZip(byte[] archive, int season, int episode) throws IOException {
        List<SubtitleFile> all = new ArrayList<>();
        List<SubtitleFile> matchedEp = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null && ++count <= 256) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../")) continue;
                String leaf = name.substring(name.lastIndexOf('/') + 1);
                String lower = leaf.toLowerCase(Locale.ROOT);
                if (!lower.matches(".*\\.(srt|vtt|ass|ssa)$")) continue;
                byte[] bytes = readAll(zip, 4 * 1024 * 1024);
                try {
                    SubtitleFile sf = toSubtitleFile(leaf, bytes);
                    all.add(sf);
                    if (season > 0 && episode > 0) {
                        Matcher m = SE_PATTERN.matcher(leaf);
                        if (m.find() && Integer.parseInt(m.group(1)) == season && Integer.parseInt(m.group(2)) == episode) {
                            matchedEp.add(sf);
                        }
                    }
                } catch (IOException ignored) {}
            }
        }
        List<SubtitleFile> result = !matchedEp.isEmpty() ? matchedEp : all;
        if (result.isEmpty()) throw new IOException("No supported subtitle files in archive");
        return result;
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        if (data.length >= 2 && data[0] == (byte) 0x1f && data[1] == (byte) 0x8b) {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
                return readAll(gz, 4 * 1024 * 1024);
            }
        }
        return data;
    }

    private static String encode(String v) {
        try { return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8.name()); }
        catch (Exception ignored) { return ""; }
    }

    private static String httpGet(String url, String userAgent, Map<String, String> headers,
                                  int connectMs, int readMs) throws IOException {
        return new String(httpGetBytes(url, userAgent, headers, connectMs, readMs, 4 * 1024 * 1024), StandardCharsets.UTF_8);
    }

    private static byte[] httpGetBytes(String url, String userAgent, Map<String, String> headers,
                                       int connectMs, int readMs, int maxBytes) throws IOException {
        String current = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setInstanceFollowRedirects(false);
            if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            try {
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null || loc.isBlank()) throw new IOException("HTTP " + code);
                    current = new URL(new URL(current), loc.trim()).toString();
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);
                try (InputStream in = conn.getInputStream()) {
                    return readAll(in, maxBytes);
                }
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private static String httpPostJson(String url, String jsonBody, String userAgent,
                                       Map<String, String> headers) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                return new String(readAll(in, 1024 * 1024), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > maxBytes) throw new IOException("Response too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}

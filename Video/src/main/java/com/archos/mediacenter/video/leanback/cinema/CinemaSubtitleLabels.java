package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.browser.subtitlesmanager.SubtitleManager;
import com.archos.mediacenter.video.utils.VideoMetadata;
import com.meedx.providers.UnifiedSubtitleConfig;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Resolves ISO-639-1/2(B/T) language codes into native Endonyms, cleans embedded/external
 * subtitle track labels with exact storage/source provenance, deduplicates external subtitle
 * files, formats audio tracks with Endonyms, and sorts embedded tracks by language priority.
 */
public final class CinemaSubtitleLabels {
    private static final String PROV_PREFS = "cinema_subtitle_provenance";
    private static final Pattern FORMAT_NOISE = Pattern.compile(
            "(?i)\\b(text|srt|subrip|vtt|webvtt|ass|ssa|sub|idx|smi|utf-?8|pgs|hdmv|vobsub|dvd|subtitle|subtitles|track|tracks)\\b");

    public record ExternalTrackChoice(int trackIndex, String label, String langCanon, String path, boolean selected) {}

    private CinemaSubtitleLabels() {}

    public static void recordSavedSource(Context context, Uri subUri, String sourceName,
                                         String languageCanon, String releaseName) {
        recordSavedSource(context, subUri, sourceName, languageCanon, releaseName, false);
    }

    public static void recordSavedSource(Context context, Uri subUri, String sourceName,
                                         String languageCanon, String releaseName, boolean besideVideo) {
        if (context == null || subUri == null) return;
        SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PROV_PREFS, Context.MODE_PRIVATE);
        String key = normKey(subUri.toString());
        String leafKey = subUri.getLastPathSegment() != null ? normKey(subUri.getLastPathSegment()) : "";
        String loc = besideVideo ? "beside" : "app";
        String payload = (sourceName == null ? "" : sourceName.trim()) + "|"
                + (languageCanon == null ? "" : UnifiedSubtitleConfig.canonicalLangName(languageCanon)) + "|"
                + (releaseName == null ? "" : releaseName.trim()) + "|"
                + loc;
        SharedPreferences.Editor ed = sp.edit().putString("u:" + key, payload);
        if (!leafKey.isEmpty()) ed.putString("f:" + leafKey, payload);
        ed.apply();
    }

    private static String[] lookupProvenance(Context context, String subPath) {
        if (context == null || subPath == null || subPath.isBlank()) return null;
        SharedPreferences sp = context.getApplicationContext().getSharedPreferences(PROV_PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString("u:" + normKey(subPath), null);
        if (raw == null) {
            String leaf = leafName(subPath);
            if (!leaf.isEmpty()) raw = sp.getString("f:" + normKey(leaf), null);
        }
        if (raw == null || raw.isBlank()) return null;
        return raw.split("\\|", -1);
    }

    /** Resolves canonical language for an embedded subtitle track from its language code and title. */
    public static String resolveEmbeddedLanguageCanonical(String rawLanguage, String rawTitle) {
        String langCanon = UnifiedSubtitleConfig.canonicalLangName(rawLanguage);
        if (langCanon.isEmpty()) langCanon = inferLanguageFromText(rawTitle);
        return langCanon;
    }

    /** Resolves canonical language for an external subtitle track (provenance -> filename -> content peek). */
    public static String resolveExternalLanguageCanonical(Context context, VideoMetadata.SubtitleTrack track, String videoPath) {
        if (context == null || track == null) return "";
        if (!track.isExternal) return resolveEmbeddedLanguageCanonical(track.language, track.name);
        String path = track.path == null ? "" : track.path;
        String leaf = leafName(path);
        String[] prov = lookupProvenance(context, path);
        String provLang = prov != null && prov.length > 1 ? prov[1].trim() : "";
        return resolveExternalLanguage(context, path, videoPath, leaf, provLang);
    }

    /**
     * Returns ONLY the base language Endonym (e.g. "English", "العربية", "Español")
     * for short status summaries like "الترجمات الداخلية · English".
     */
    public static String formatBaseLanguageEndonym(Context context, VideoMetadata.SubtitleTrack track) {
        if (context == null || track == null) return "";
        return formatBaseLanguageEndonym(context, track.language, track.name);
    }

    public static String formatBaseLanguageEndonym(Context context, String rawLanguage, String rawTitle) {
        if (context == null) return "";
        boolean ar = CinemaUi.arabic(context);
        String langCanon = resolveEmbeddedLanguageCanonical(rawLanguage, rawTitle);
        return langCanon.isEmpty()
                ? (ar ? "لغة غير محددة" : "Unknown Language")
                : UnifiedSubtitleConfig.languageDisplay(langCanon, ar);
    }

    /**
     * Clean embedded subtitle label for the dedicated Embedded Subtitles dropdown:
     * native language Endonym + meaningful qualifiers (e.g. SDH, Latin America, Forced) only —
     * never repeats "Embedded" / "مدمجة داخل الفيديو" or "(TEXT)".
     */
    public static String formatEmbeddedLabel(Context context, VideoMetadata.SubtitleTrack track) {
        if (context == null || track == null) return "";
        return formatEmbeddedLabel(context, track.language, track.name);
    }

    public static String formatEmbeddedLabel(Context context, String rawLanguage, String rawTitle) {
        if (context == null) return "";
        boolean ar = CinemaUi.arabic(context);
        String langCanon = resolveEmbeddedLanguageCanonical(rawLanguage, rawTitle);
        String langLabel = langCanon.isEmpty()
                ? (ar ? "لغة غير محددة" : "Unknown Language")
                : UnifiedSubtitleConfig.languageDisplay(langCanon, ar);

        List<String> tags = extractEmbeddedQualifiers(rawLanguage, rawTitle, langCanon, ar);
        if (tags.isEmpty()) {
            String extra = cleanRemainingEmbeddedExtra(rawLanguage, rawTitle, langCanon, langLabel);
            if (!extra.isEmpty()) {
                return langLabel + " · " + extra;
            }
            return langLabel;
        }
        StringBuilder sb = new StringBuilder(langLabel);
        for (String tag : tags) {
            if (!tag.isEmpty()) sb.append(" · ").append(tag);
        }
        return sb.toString();
    }

    /**
     * Formats an audio track cleanly with native language Endonym + codec/channel details.
     */
    public static String formatAudioTrackLabel(Context context, VideoMetadata.AudioTrack track, int index) {
        if (context == null || track == null) return "";
        boolean ar = CinemaUi.arabic(context);
        String langCanon = resolveEmbeddedLanguageCanonical(track.language, track.name);
        String langLabel = langCanon.isEmpty()
                ? ((track.name != null && !track.name.isBlank()) ? track.name.trim()
                : (context.getString(R.string.player_track) + " " + (index + 1)))
                : UnifiedSubtitleConfig.languageDisplay(langCanon, ar);
        String fmt = track.format == null ? "" : track.format.trim();
        String ch = track.channels == null ? "" : track.channels.trim();
        StringBuilder sb = new StringBuilder(langLabel);
        if (!fmt.isEmpty()) sb.append(" · ").append(fmt);
        if (!ch.isEmpty() && !fmt.contains(ch)) sb.append(" · ").append(ch);
        return sb.toString();
    }

    /**
     * Clear external subtitle label identifying exact language Endonym, storage location,
     * and provider provenance (e.g. "العربية · في مصدر الفيديو", "العربية · في التطبيق (Subsource)",
     * "العربية · أُضيفت لمصدر الفيديو (Subsource)", "العربية · من الهاتف").
     */
    public static String formatExternalCompactLabel(Context context, VideoMetadata.SubtitleTrack track, String videoPath) {
        if (context == null || track == null) return "";
        boolean ar = CinemaUi.arabic(context);
        if (!track.isExternal) {
            return formatEmbeddedLabel(context, track);
        }

        String path = track.path == null ? "" : track.path;
        String leaf = leafName(path);
        String[] prov = lookupProvenance(context, path);
        String provSource = prov != null && prov.length > 0 ? prov[0].trim() : "";
        String provLang = prov != null && prov.length > 1 ? prov[1].trim() : "";
        String provLoc = prov != null && prov.length > 3 ? prov[3].trim() : "";

        String langCanon = resolveExternalLanguage(context, path, videoPath, leaf, provLang);
        String langLabel = langCanon.isEmpty()
                ? (ar ? "ترجمة خارجية" : "External")
                : UnifiedSubtitleConfig.languageDisplay(langCanon, ar);

        String sourcePart = resolveExternalSourceAndLocation(context, path, leaf, provSource, provLoc);
        return langLabel + " · " + sourcePart;
    }

    /**
     * Builds a deduplicated, cleanly disambiguated list of external subtitle tracks for a video.
     * Removes exact path duplicates and identical-content cached copies while always keeping the
     * active track index if present in a duplicate group.
     */
    public static List<ExternalTrackChoice> buildDeduplicatedExternalTracks(
            Context context, VideoMetadata meta, String videoPath, int activeTrackIndex, boolean hasCastPrimary) {
        List<ExternalTrackChoice> result = new ArrayList<>();
        if (context == null || meta == null) return result;

        int nbTrack = meta.getSubtitleTrackNb();
        Map<String, ExternalTrackChoice> byKey = new LinkedHashMap<>();

        for (int i = 0; i < nbTrack; i++) {
            VideoMetadata.SubtitleTrack track = meta.getSubtitleTrack(i);
            if (track == null || !track.isExternal) continue;
            String path = track.path == null ? "" : track.path;
            String label = formatExternalCompactLabel(context, track, videoPath);
            String langCanon = resolveExternalLanguageCanonical(context, track, videoPath);
            boolean active = !hasCastPrimary && activeTrackIndex == i;

            String dedupKey = computeExternalFingerprint(path, langCanon);
            ExternalTrackChoice existing = byKey.get(dedupKey);
            if (existing == null) {
                byKey.put(dedupKey, new ExternalTrackChoice(i, label, langCanon, path, active));
            } else {
                // Keep whichever entry is active, or has richer provenance (not plain "Saved in App")
                boolean replace = active && !existing.selected();
                if (!replace && !existing.selected()) {
                    boolean newHasProvider = label.contains("(");
                    boolean oldHasProvider = existing.label().contains("(");
                    if (newHasProvider && !oldHasProvider) replace = true;
                }
                if (replace) {
                    byKey.put(dedupKey, new ExternalTrackChoice(i, label, langCanon, path, active));
                }
            }
        }

        List<ExternalTrackChoice> unique = new ArrayList<>(byKey.values());
        // Disambiguate distinct files that happen to produce identical labels
        Map<String, Integer> counts = new HashMap<>();
        for (ExternalTrackChoice item : unique) {
            counts.put(item.label(), counts.getOrDefault(item.label(), 0) + 1);
        }
        Map<String, Integer> seen = new HashMap<>();
        for (ExternalTrackChoice item : unique) {
            int total = counts.getOrDefault(item.label(), 0);
            if (total <= 1) {
                result.add(item);
            } else {
                int num = seen.getOrDefault(item.label(), 0) + 1;
                seen.put(item.label(), num);
                String tag = extractShortDifferentiator(context, item.path(), num);
                result.add(new ExternalTrackChoice(
                        item.trackIndex(),
                        item.label() + " · " + tag,
                        item.langCanon(),
                        item.path(),
                        item.selected()));
            }
        }
        return result;
    }

    /** Formats a VideoMetadata.SubtitleTrack for general / flat lists (such as Phone JSON). */
    public static String formatTrackLabel(Context context, VideoMetadata.SubtitleTrack track, String videoPath) {
        if (context == null || track == null) return "";
        if (!track.isExternal) {
            return formatEmbeddedLabel(context, track);
        }
        return formatExternalCompactLabel(context, track, videoPath);
    }

    /** Formats a standalone external subtitle name/source for ExoPlayer/HLS Hub display. */
    public static String formatStreamSubtitleLabel(Context context, String rawTitle, String sourceHint, String langHint, String format) {
        boolean ar = CinemaUi.arabic(context);
        String langCanon = UnifiedSubtitleConfig.canonicalLangName(langHint);
        if (langCanon.isEmpty()) langCanon = inferLanguageFromText(rawTitle);
        String langLabel = langCanon.isEmpty()
                ? (ar ? "ترجمة" : "Subtitle")
                : UnifiedSubtitleConfig.languageDisplay(langCanon, ar);
        String src = (sourceHint != null && !sourceHint.isBlank())
                ? sourceHint.trim()
                : context.getString(R.string.cinema_sub_active_primary);
        return langLabel + " · " + src;
    }

    /**
     * Sorts embedded subtitle items placing user-configured priority languages (e.g. العربية, English)
     * first, followed by remaining known languages alphabetically, and unknown tracks last.
     */
    public static <T> List<T> sortEmbeddedTracks(
            Context context,
            List<T> items,
            Function<T, String> langCanonExtractor,
            Function<T, String> labelExtractor) {
        if (items == null || items.size() <= 1) return items == null ? new ArrayList<>() : new ArrayList<>(items);
        CinemaSubtitleSyncBridge.ensureBootstrapped(context);
        List<String> priorities = UnifiedSubtitleConfig.languagePriorities(context);
        Map<String, Integer> prioMap = new HashMap<>();
        for (int i = 0; i < priorities.size(); i++) {
            String canon = UnifiedSubtitleConfig.canonicalLangName(priorities.get(i));
            if (!canon.isEmpty() && !prioMap.containsKey(canon)) {
                prioMap.put(canon, i);
            }
        }
        Collator collator = Collator.getInstance(Locale.ENGLISH);
        collator.setStrength(Collator.PRIMARY);

        List<T> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> {
            String langA = UnifiedSubtitleConfig.canonicalLangName(langCanonExtractor.apply(a));
            String langB = UnifiedSubtitleConfig.canonicalLangName(langCanonExtractor.apply(b));
            int rankA = prioMap.getOrDefault(langA, Integer.MAX_VALUE);
            int rankB = prioMap.getOrDefault(langB, Integer.MAX_VALUE);
            if (rankA != rankB) return Integer.compare(rankA, rankB);

            boolean unkA = langA.isEmpty();
            boolean unkB = langB.isEmpty();
            if (unkA != unkB) return Boolean.compare(unkA, unkB);

            String lblA = labelExtractor.apply(a);
            String lblB = labelExtractor.apply(b);
            if (lblA == null) lblA = "";
            if (lblB == null) lblB = "";

            String baseA = baseSegment(lblA);
            String baseB = baseSegment(lblB);
            int baseCmp = collator.compare(baseA, baseB);
            if (baseCmp != 0) return baseCmp;

            boolean varA = lblA.contains("·");
            boolean varB = lblB.contains("·");
            if (varA != varB) return Boolean.compare(varA, varB);

            return collator.compare(lblA, lblB);
        });
        return sorted;
    }

    /** Appends clean numbering "(2)", "(3)" ONLY when multiple embedded tracks share identical labels. */
    public static List<String> disambiguateLabels(List<String> labels) {
        Map<String, Integer> totals = new HashMap<>();
        for (String l : labels) {
            totals.put(l, totals.getOrDefault(l, 0) + 1);
        }
        Map<String, Integer> seen = new HashMap<>();
        List<String> out = new ArrayList<>(labels.size());
        for (String l : labels) {
            int count = totals.getOrDefault(l, 0);
            if (count <= 1) {
                out.add(l);
            } else {
                int idx = seen.getOrDefault(l, 0) + 1;
                seen.put(l, idx);
                out.add(idx == 1 ? l : (l + " (" + idx + ")"));
            }
        }
        return out;
    }

    private static String computeExternalFingerprint(String rawPath, String langCanon) {
        String normalizedPath = normKey(rawPath);
        File local = resolveLocalFile(rawPath);
        if (local != null && local.isFile() && local.canRead()) {
            long len = local.length();
            byte[] buf = new byte[(int) Math.min(512L, len)];
            try (FileInputStream in = new FileInputStream(local)) {
                int read = in.read(buf);
                if (read > 0) {
                    int hash = Arrays.hashCode(read == buf.length ? buf : Arrays.copyOf(buf, read));
                    return "file:" + len + ":" + hash;
                }
            } catch (Exception ignored) {}
            return "file:" + len + ":" + normKey(local.getAbsolutePath());
        }
        return "uri:" + normalizedPath + ":" + langCanon;
    }

    private static String extractShortDifferentiator(Context context, String rawPath, int index) {
        String[] prov = lookupProvenance(context, rawPath);
        String release = prov != null && prov.length > 2 ? prov[2].trim() : "";
        String candidate = !release.isEmpty() ? release : leafName(rawPath);
        candidate = candidate.replaceFirst("(?i)\\.(srt|vtt|ass|ssa|sub)$", "");
        candidate = candidate.replaceAll("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "").trim();
        if (candidate.matches(".*(?i)\\b(web-?dl|webrip|bluray|bdrip|remux|hdr|sdh|forced)\\b.*")) {
            var m = Pattern.compile("(?i)\\b(WEB-?DL|WEBRip|BluRay|BDRip|REMUX|HDR|SDH|Forced)\\b").matcher(candidate);
            if (m.find()) return m.group(1) + " #" + index;
        }
        return "#" + index;
    }

    private static String resolveExternalSourceAndLocation(Context context, String path, String leaf,
                                                           String provSource, String provLoc) {
        String lowerPath = path.toLowerCase(Locale.ROOT);
        String lowerLeaf = leaf.toLowerCase(Locale.ROOT);

        // 1. Phone / Browser cast subtitle
        if (lowerLeaf.startsWith("phone-subtitle") || lowerPath.contains("cast_subs")
                || isPhoneSourceName(provSource)) {
            return context.getString(R.string.cinema_sub_loc_from_phone);
        }

        // Detect known online provider name from provenance or filename
        String provider = detectProviderName(provSource, lowerLeaf);

        // Detect storage location: App private/cache vs Video source folder
        boolean inAppStorage = lowerPath.contains("/files/subtitles/")
                || lowerPath.contains("/cache/subtitles")
                || lowerPath.contains("/subtitles/") && lowerPath.contains(context.getPackageName().toLowerCase(Locale.ROOT))
                || lowerPath.contains("/android/data/" + context.getPackageName().toLowerCase(Locale.ROOT))
                || "app".equals(provLoc);

        if (inAppStorage) {
            if (!provider.isEmpty()) {
                return context.getString(R.string.cinema_sub_loc_in_app_provider, provider);
            }
            return context.getString(R.string.cinema_sub_loc_in_app);
        }

        // File sits in the video source folder (local disk / SMB / network share)
        boolean addedByApp = "beside".equals(provLoc)
                || !provider.isEmpty()
                || lowerLeaf.matches(".*\\.[a-z0-9]+-\\d+-[0-9a-f]{8}-.*");
        if (addedByApp) {
            if (!provider.isEmpty()) {
                return context.getString(R.string.cinema_sub_loc_added_source_provider, provider);
            }
            return context.getString(R.string.cinema_sub_loc_added_source);
        }

        return context.getString(R.string.cinema_sub_loc_video_source);
    }

    private static boolean isPhoneSourceName(String src) {
        if (src == null || src.isBlank()) return false;
        String l = src.toLowerCase(Locale.ROOT);
        return l.contains("phone") || l.contains("browser") || l.contains("الهاتف") || l.contains("المتصفح");
    }

    private static String detectProviderName(String provSource, String lowerLeaf) {
        String hay = ((provSource == null ? "" : provSource) + " " + (lowerLeaf == null ? "" : lowerLeaf))
                .toLowerCase(Locale.ROOT);
        if (hay.contains("subsource")) return "Subsource";
        if (hay.contains("opensubtitles")) return "OpenSubtitles";
        if (hay.contains("yify")) return "YIFY";
        if (hay.contains("subdl")) return "SubDL";
        if (hay.contains("gestdown") || hay.contains("addic7ed")) return "Gestdown";
        if (hay.contains("stremio")) return "Stremio";
        if (provSource != null && !provSource.isBlank()
                && !provSource.toLowerCase(Locale.ROOT).contains("local")
                && !provSource.contains("محلي")) {
            return provSource.length() > 16 ? provSource.substring(0, 16) + "…" : provSource;
        }
        return "";
    }

    private static String baseSegment(String label) {
        int sep = label.indexOf('·');
        return (sep >= 0 ? label.substring(0, sep) : label).trim();
    }

    private static String resolveExternalLanguage(Context context, String path, String videoPath,
                                                  String leaf, String provLang) {
        String langCanon = UnifiedSubtitleConfig.canonicalLangName(provLang);
        if (langCanon.isEmpty()) {
            String fromNova = SubtitleManager.getSubLanguageFromSubPathAndVideoPath(context, path, videoPath);
            if (fromNova != null && !isRawFormatToken(fromNova)) {
                langCanon = UnifiedSubtitleConfig.canonicalLangName(fromNova);
                if (langCanon.isEmpty()) langCanon = inferLanguageFromText(fromNova);
            }
        }
        if (langCanon.isEmpty()) langCanon = inferLanguageFromText(leaf);
        if (langCanon.isEmpty()) langCanon = peekLocalFileLanguage(path);
        return langCanon;
    }

    private static List<String> extractEmbeddedQualifiers(String rawLanguage, String rawTitle,
                                                          String langCanon, boolean ar) {
        List<String> tags = new ArrayList<>();
        String combined = ((rawLanguage == null ? "" : rawLanguage) + " " + (rawTitle == null ? "" : rawTitle))
                .toLowerCase(Locale.ROOT);
        String normLang = rawLanguage == null ? "" : rawLanguage.trim().toLowerCase(Locale.ROOT).replace('_', '-');

        if (normLang.endsWith("-419") || normLang.endsWith("-la") || normLang.endsWith("-mx")
                || combined.contains("latin america") || combined.contains("latin american")
                || combined.contains("latino") || combined.matches(".*\\b(es-419|es-la|es-mx|latam)\\b.*")) {
            addUnique(tags, "Latinoamérica");
        } else if ("spanish".equals(langCanon) && (combined.contains("castilian") || combined.contains("spain")
                || combined.contains("european") || combined.contains("españa") || normLang.endsWith("-es"))) {
            addUnique(tags, "España");
        }

        if (normLang.endsWith("-br") || normLang.equals("pob") || normLang.equals("pb")
                || combined.contains("brazil") || combined.contains("brasil")) {
            addUnique(tags, "Brasil");
        } else if ("portuguese".equals(langCanon) && (combined.contains("european") || normLang.endsWith("-pt"))) {
            addUnique(tags, "Portugal");
        }

        if ("french".equals(langCanon) && (normLang.endsWith("-ca") || combined.contains("canadian")
                || combined.contains("quebec") || combined.contains("canada"))) {
            addUnique(tags, "Canada");
        }

        if ("chinese".equals(langCanon)) {
            if (normLang.contains("hans") || normLang.endsWith("-cn") || normLang.equals("zhs")
                    || combined.contains("simplified") || combined.contains("简体") || combined.matches(".*\\bchs\\b.*")) {
                addUnique(tags, "简体");
            } else if (normLang.contains("hant") || normLang.endsWith("-tw") || normLang.endsWith("-hk") || normLang.equals("zht")
                    || combined.contains("traditional") || combined.contains("繁體") || combined.matches(".*\\bcht\\b.*")) {
                addUnique(tags, "繁體");
            }
        }

        if (combined.matches(".*(?:^|[^a-z])sdh(?:$|[^a-z]).*")
                || combined.contains("hearing impaired")
                || combined.matches(".*\\[(?:hi)\\].*")) {
            addUnique(tags, "SDH");
        } else if (combined.matches(".*(?:^|[^a-z])cc(?:$|[^a-z]).*") || combined.contains("closed caption")) {
            addUnique(tags, "CC");
        }

        if (combined.matches(".*\\bforced\\b.*") || combined.contains("signs & songs") || combined.contains("signs/songs")) {
            addUnique(tags, ar ? "للنصوص" : "Forced");
        }

        if (combined.contains("commentary")) {
            addUnique(tags, ar ? "تعليق" : "Commentary");
        }

        return tags;
    }

    private static String cleanRemainingEmbeddedExtra(String rawLanguage, String rawTitle,
                                                      String langCanon, String langLabel) {
        if (rawTitle == null || rawTitle.isBlank()) return "";
        String t = rawTitle.trim();
        if (isRawFormatToken(t)) return "";

        t = FORMAT_NOISE.matcher(t).replaceAll(" ");
        t = stripTokenIgnoreCase(t, langLabel);
        if (!langCanon.isEmpty()) {
            for (String alias : UnifiedSubtitleConfig.languageAliases(langCanon)) {
                t = stripTokenIgnoreCase(t, alias);
            }
        }
        if (rawLanguage != null && !rawLanguage.isBlank()) {
            t = stripTokenIgnoreCase(t, rawLanguage.trim());
        }
        t = t.replaceAll("(?i)\\b(full|regular|standard|default|main|bokm[aå]l|nynorsk|bahasa|european)\\b", " ");
        t = t.replaceAll("[()\\[\\]{}·•|\\-–—:,/]+", " ").replaceAll("\\s+", " ").trim();

        if (t.length() <= 1 || isRawFormatToken(t)) return "";
        if (!langCanon.isEmpty() && langCanon.equals(UnifiedSubtitleConfig.canonicalLangName(t))) return "";
        if (t.length() > 24) t = t.substring(0, 24) + "…";
        return t;
    }

    private static String stripTokenIgnoreCase(String input, String token) {
        if (input == null || input.isEmpty() || token == null || token.isBlank()) return input;
        String trimmed = token.trim();
        if (trimmed.length() < 2) return input;
        return input.replaceAll("(?iu)(?:^|[\\s(\\[\\-–—·:,/])" + Pattern.quote(trimmed) + "(?:$|[\\s)\\]\\-–—·:,/])", " ");
    }

    private static void addUnique(List<String> list, String value) {
        if (value != null && !value.isEmpty() && !list.contains(value)) list.add(value);
    }

    private static boolean isRawFormatToken(String s) {
        if (s == null) return true;
        String t = s.trim().toUpperCase(Locale.ROOT);
        return t.isEmpty() || t.equals("TEXT") || t.equals("SRT") || t.equals("VTT") || t.equals("ASS") || t.equals("SSA")
                || t.equals("SUB") || t.equals("IDX") || t.equals("SMI") || t.equals("UTF") || t.equals("UTF8")
                || t.equals("UTF-8") || t.equals("UND") || t.equals("SUBRIP") || t.equals("WEBVTT");
    }

    private static String inferLanguageFromText(String text) {
        if (text == null || text.isBlank()) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("عرب") || lower.matches(".*(?:^|[._\\-\\s(\\[])(ar|ara|arb|arabic)(?:$|[._\\-\\s)\\]]).*")) return "arabic";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(en|eng|english)(?:$|[._\\-\\s)\\]]).*")) return "english";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(fr|fre|fra|french|fran[çc]ais)(?:$|[._\\-\\s)\\]]).*")) return "french";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(es|spa|spanish|espa[ñn]ol|castilian)(?:$|[._\\-\\s)\\]]).*")) return "spanish";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(de|ger|deu|german|deutsch)(?:$|[._\\-\\s)\\]]).*")) return "german";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(tr|tur|turkish|t[üu]rk[çc]e)(?:$|[._\\-\\s)\\]]).*")) return "turkish";
        if (lower.contains("فارس") || lower.matches(".*(?:^|[._\\-\\s(\\[])(fa|per|fas|persian|farsi)(?:$|[._\\-\\s)\\]]).*")) return "persian";
        if (lower.contains("کورد") || lower.matches(".*(?:^|[._\\-\\s(\\[])(ku|kur|kurdish)(?:$|[._\\-\\s)\\]]).*")) return "kurdish";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(it|ita|italian|italiano)(?:$|[._\\-\\s)\\]]).*")) return "italian";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(pt|por|pob|portuguese|portugu[êe]s)(?:$|[._\\-\\s)\\]]).*")) return "portuguese";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(ru|rus|russian)(?:$|[._\\-\\s)\\]]).*")) return "russian";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(ja|jpn|japanese)(?:$|[._\\-\\s)\\]]).*")) return "japanese";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(ko|kor|korean)(?:$|[._\\-\\s)\\]]).*")) return "korean";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(zh|chi|zho|chinese)(?:$|[._\\-\\s)\\]]).*")) return "chinese";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(nl|dut|nld|dutch|nederlands)(?:$|[._\\-\\s)\\]]).*")) return "nl";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(pl|pol|polish|polski)(?:$|[._\\-\\s)\\]]).*")) return "pl";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(sv|swe|swedish|svenska)(?:$|[._\\-\\s)\\]]).*")) return "sv";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(da|dan|danish|dansk)(?:$|[._\\-\\s)\\]]).*")) return "da";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(fi|fin|finnish|suomi)(?:$|[._\\-\\s)\\]]).*")) return "fi";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(nb|no|nob|nor|norwegian|norsk)(?:$|[._\\-\\s)\\]]).*")) return "nb";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(el|gre|ell|greek)(?:$|[._\\-\\s)\\]]).*")) return "el";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(cs|cze|ces|czech)(?:$|[._\\-\\s)\\]]).*")) return "cs";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(ro|rum|ron|romanian)(?:$|[._\\-\\s)\\]]).*")) return "ro";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(hu|hun|hungarian|magyar)(?:$|[._\\-\\s)\\]]).*")) return "hu";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(he|heb|hebrew)(?:$|[._\\-\\s)\\]]).*")) return "he";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(hr|hrv|croatian|hrvatski)(?:$|[._\\-\\s)\\]]).*")) return "hr";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(uk|ukr|ukrainian)(?:$|[._\\-\\s)\\]]).*")) return "uk";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(th|tha|thai)(?:$|[._\\-\\s)\\]]).*")) return "th";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(id|ind|indonesian)(?:$|[._\\-\\s)\\]]).*")) return "id";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(ms|may|msa|malay)(?:$|[._\\-\\s)\\]]).*")) return "ms";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(fil|filipino|tagalog)(?:$|[._\\-\\s)\\]]).*")) return "fil";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(ca|cat|catalan|catal[àa])(?:$|[._\\-\\s)\\]]).*")) return "ca";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(eu|baq|eus|basque|euskara)(?:$|[._\\-\\s)\\]]).*")) return "eu";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(gl|glg|galician|galego)(?:$|[._\\-\\s)\\]]).*")) return "gl";
        if (lower.matches(".*(?:^|[._\\-\\s(\\[])(vi|vie|vietnamese)(?:$|[._\\-\\s)\\]]).*")) return "vi";
        return "";
    }

    private static File resolveLocalFile(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return null;
        String filePath = rawPath;
        if (filePath.startsWith("file://")) {
            try { filePath = Uri.parse(filePath).getPath(); } catch (Exception ignored) { return null; }
        }
        if (filePath == null || !filePath.startsWith("/")) return null;
        return new File(filePath);
    }

    private static String peekLocalFileLanguage(String rawPath) {
        File f = resolveLocalFile(rawPath);
        if (f == null || !f.isFile() || !f.canRead()) return "";
        byte[] buf = new byte[2048];
        int read;
        try (FileInputStream in = new FileInputStream(f)) {
            read = in.read(buf);
        } catch (Exception ignored) {
            return "";
        }
        if (read <= 0) return "";
        String sample = new String(buf, 0, read, StandardCharsets.UTF_8);
        int arabicChars = 0;
        int persianSpecific = 0;
        int latinChars = 0;
        for (int i = 0; i < sample.length(); i++) {
            char ch = sample.charAt(i);
            if (ch == 'پ' || ch == 'چ' || ch == 'ژ' || ch == 'گ' || ch == 'ک' || ch == 'ی') persianSpecific++;
            if (ch >= 0x0600 && ch <= 0x06FF) arabicChars++;
            else if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) latinChars++;
        }
        if (persianSpecific >= 6 && arabicChars >= 12) return "persian";
        if (arabicChars >= 6) return "arabic";
        if (arabicChars == 0) {
            int cp1256Arabic = 0;
            for (int i = 0; i < read; i++) {
                int b = buf[i] & 0xFF;
                if (b >= 0xC1 && b <= 0xED) cp1256Arabic++;
            }
            if (cp1256Arabic >= 12) return "arabic";
        }
        if (latinChars >= 24) return "english";
        return "";
    }

    private static String leafName(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int slash = p.lastIndexOf('/');
        String leaf = slash >= 0 ? p.substring(slash + 1) : p;
        try { return Uri.decode(leaf); } catch (Exception ignored) { return leaf; }
    }

    private static String normKey(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}

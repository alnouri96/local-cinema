package org.proninyaroslav.libretorrent.cinema_hub;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;
import org.proninyaroslav.libretorrent.R;

/**
 * Companion controller for CinemaBrowser's video/subtitle discovery bridge,
 * HLS/direct/subtitle probing & canonical deduplication, quality selection and
 * dual subtitle sheets, and casting handoff to TvHubActivity.
 */
final class CinemaBrowserMediaBridge {
    private static final String VIDEO_EXTENSION = "video-discovery@localcinema";
    private static final java.util.regex.Pattern STORYBOARD_SUB_HINT_PATTERN = java.util.regex.Pattern.compile(
            "(?i)(?:#xywh=\\s*\\d+|\\.(?:jpe?g|png|webp|gif|bmp|avif|svg)(?:$|[?#])|(?:^|[/_.-])(?:storyboard|sprites?|thumbnails?|thumbs|trickplay|preview[_-]?vtt|bif)(?:$|[/_.-]))");
    private static final java.util.regex.Pattern IMAGE_CUE_LINE_PATTERN = java.util.regex.Pattern.compile(
            "(?i)^(?:https?://\\S+|/?[^\\s<>\"'{}|^`\\\\]+)\\.(?:jpe?g|png|webp|gif|bmp|avif|svg)(?:[?#]\\S*)?$");
    private static final java.util.regex.Pattern AD_STREAM_HOST_PATTERN = java.util.regex.Pattern.compile(
            "(?i)(?:^|\\.)(doubleclick\\.net|googlesyndication\\.com|googleadservices\\.com|imasdk\\.googleapis\\.com|2mdn\\.net|adnxs\\.com|rubiconproject\\.com|pubmatic\\.com|criteo\\.com|taboola\\.com|outbrain\\.com|exoclick\\.com|exosrv\\.com|magsrv\\.com|tsyndicate\\.com|propellerads\\.com|popads\\.net|popcash\\.net|juicyads\\.com|a-ads\\.com|adsterra\\.com|hilltopads\\.net|clickadu\\.com|galaksion\\.com|bidgear\\.com|springserve\\.com|spotxchange\\.com|innovid\\.com|flashtalking\\.com|teads\\.tv|smartadserver\\.com|adform\\.net|vidoomy\\.com|aniview\\.com|connatix\\.com)$");
    private static final java.util.regex.Pattern AD_STREAM_PATH_PATTERN = java.util.regex.Pattern.compile(
            "(?i)(?:/|^)(?:vast|preroll|midroll|postroll|adserver|adbreak|ad_break|advert|ads_video|ima3)(?:/|\\.|\\?|$)");
    private static final java.util.regex.Pattern CLOAKED_WRAPPER_HOST_PATTERN = java.util.regex.Pattern.compile(
            "(?i)(?:^|\\.)(elif\\.news)$");
    private static final java.util.regex.Pattern AD_TITLE_SPAM_PATTERN = java.util.regex.Pattern.compile(
            "(?i)(?:\\b(?:forex(?:\\s+trading)?|make\\s+money(?:\\s+from\\s+home|\\s+online)?|work\\s+from\\s+home|passive\\s+income|earn\\s+money(?:\\s+online)?|binary\\s+options?|day\\s+trading|stock\\s+(?:market|trading)|crypto(?:currency)?\\s*(?:trading|exchange|invest\\w*)?|bitcoin\\s+trading|online\\s+casino|casino\\s+(?:bonus|games|online)|sports?\\s+betting|betting\\s+odds|slot\\s+machines?|(?:business|personal|student|auto|home|payday|small\\s+business)\\s+loans?|small\\s+business\\s+funding|mortgage(?:\\s+rates?|\\s+loans?|\\s+refinanc\\w*)?|refinanc(?:e|ing)\\s+your\\s+(?:home|loan|mortgage)|insurance\\s+(?:quotes?|plans?|policies|policy)|health\\s+insurance|life\\s+insurance|car\\s+insurance|auto\\s+insurance|credit\\s+cards?(?:\\s+rewards?|\\s+offers?)?|credit\\s+scores?|investment\\s+(?:tips|strategies|guide)|wealth\\s+management|retirement\\s+planning|tax\\s+deductions?|tips\\s+for\\s+a\\s+successful|sponsored(?:\\s+ad|\\s+content)?|advertisement|skip\\s+ads?|1xbet|melbet|mostbet|linebet|betwinner|olymp\\s*trade|iq\\s*option|binomo|attention\\s+required|just\\s+a\\s+moment|cloudflare)\\b|\\b\\d+\\s+tips\\s+(?:for|to)\\s+(?:a\\s+)?(?:successful|better|grow|boost|improve|get|choose|find)\\b)");
    private static final java.util.Set<String> GENERIC_MEDIA_SEGMENTS = java.util.Set.of(
            "master", "index", "playlist", "chunklist", "manifest", "video", "stream", "hls", "dash",
            "live", "media", "play", "embed", "player", "watch", "movie", "movies", "series", "tv",
            "episode", "episodes", "files", "cdn", "storage", "uploads", "videos", "content", "v", "e",
            "2160p", "1440p", "1080p", "720p", "480p", "360p", "240p", "hd", "fhd", "uhd", "4k", "sd", "auto");

    private final CinemaBrowser host;

    private final java.util.Map<GeckoSession, org.json.JSONArray> videos = new java.util.HashMap<>();
    private WebExtension discovery;
    private com.google.android.material.bottomsheet.BottomSheetDialog videoSheetDialog;
    private LinearLayout videoSheetCards;
    private TextView videoSheetStatus;
    private TextView videoSheetSubSummary;
    private androidx.core.widget.NestedScrollView videoSheetScroll;
    private final java.util.List<SheetCardHolder> videoSheetHolders = new java.util.ArrayList<>();
    private String renderedSheetSignature = "";

    private final java.util.Map<GeckoSession, org.mozilla.geckoview.MediaSession> mediaSessions =
            new java.util.HashMap<>();
    private final java.util.Set<GeckoSession> mediaSessionPlaying = new java.util.HashSet<>();
    private final java.util.Map<GeckoSession, java.util.Set<String>> audibleFrames =
            new java.util.HashMap<>();
    private final java.util.Map<GeckoSession, String> sessionCleanTitles = new java.util.HashMap<>();
    private final java.util.Map<GeckoSession, String> lastVerifiedSessionTitles = new java.util.HashMap<>();
    private final java.util.Set<GeckoSession> topFrameTitledSessions = new java.util.HashSet<>();
    private final java.util.Set<GeckoSession> allowBackgroundAudioTabs = new java.util.HashSet<>();
    private GeckoSession lastCastSession;
    private String lastBroadcastTitle = "";
    private String lastCastCanonicalUrl = "";
    private boolean lastCastWasAdaptive = false;
    private long lastCastTimestampMs = 0L;

    private final java.util.Map<GeckoSession, java.util.Map<String, org.json.JSONArray>> frameSnapshots =
            new java.util.HashMap<>();
    private final java.util.Map<GeckoSession, java.util.Map<String, org.json.JSONArray>> probedHlsCache =
            new java.util.HashMap<>();
    private final java.util.Set<String> hlsProbeInFlight =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final java.util.concurrent.ExecutorService hlsProbeExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(2);

    private final java.util.Map<GeckoSession, java.util.Map<String, org.json.JSONArray>> frameSubtitleSnapshots =
            new java.util.HashMap<>();
    private final java.util.Map<GeckoSession, java.util.Map<String, org.json.JSONArray>> frameTorrentSnapshots =
            new java.util.HashMap<>();
    private final java.util.Map<GeckoSession, java.util.List<TorrentGrabOption>> sessionTorrents =
            new java.util.HashMap<>();
    private final java.util.Set<GeckoSession> hardcodedSubSessions = new java.util.HashSet<>();
    private final java.util.Map<GeckoSession, java.util.Map<String, org.json.JSONArray>> probedHlsSubtitles =
            new java.util.HashMap<>();
    private final java.util.Map<GeckoSession, java.util.List<SubtitleOption>> sessionSubtitles =
            new java.util.HashMap<>();
    private final java.util.Map<GeckoSession, SubtitleOption> selectedPrimarySubs =
            new java.util.HashMap<>();
    private final java.util.Map<GeckoSession, SubtitleOption> selectedSecondarySubs =
            new java.util.HashMap<>();

    private final java.util.Map<String, Long> headSizeCache =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());
    private final java.util.Set<String> headSizeInFlight =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final java.util.Map<String, SubProbeMeta> subProbeCache =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());
    private final java.util.Set<String> subProbeInFlight =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final java.util.concurrent.ExecutorService headProbeExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(4);

    private final java.util.Map<GeckoSession, java.util.Set<WebExtension.Port>> mediaPorts =
            new java.util.WeakHashMap<>();

    private record SheetCardHolder(
            LinearLayout card,
            TextView formatView,
            TextView subBtn,
            View dlBtn) {}

    private record SubProbeMeta(long sizeBytes, String language, boolean isDual) {}

    record SubtitleOption(
            String url, String mime, String language, String label,
            long sizeBytes, String content, boolean isDual,
            String referer, String origin, String userAgent, String cookie, String authorization) {}

    record TorrentGrabOption(
            String key, String hash, String magnetUri, String torrentUrl, String uri,
            String kind, String title, String quality, String sizeText, long sizeBytes,
            int seeds, String referer, String userAgent, String cookie) {}

    private static final SubtitleOption SUBTITLE_OFF =
            new SubtitleOption("", "", "", "", 0L, "", false, "", "", "", "", "");

    private record HlsProbeFetch(java.net.URL finalUrl, String body) {}

    private record DetectedLang(String language, boolean isDual) {}

    record VideoOption(String url, String title, String format, int height, boolean adaptive,
                       String kind, String audioUrl, int bitrate, int fps, boolean live,
                       boolean activeStream,
                       String subtitleUrl, String subtitleMime, String subtitleLanguage,
                       String subtitleReferer, String subtitleOrigin, String subtitleUserAgent,
                       String subtitleCookie, String subtitleAuthorization,
                       long sizeBytes, String referer, String origin, String userAgent,
                       String cookie, String authorization) {
        String quality() {
            if ("audio".equals(kind)) return "Audio Only";
            if (live) return height > 0 ? height + "p LIVE" : "LIVE";
            if (fps >= 50 && height > 0) return height + "p " + fps + "fps";
            return switch (height) {
                case 2160 -> "2160p 4K";
                case 1440 -> "1440p 2K";
                case 1080 -> "1080p FHD";
                case 720 -> "720p HD";
                case 480 -> "480p SD";
                case 0 -> adaptive ? "AUTO" : "Video";
                default -> height + "p";
            };
        }
    }

    CinemaBrowserMediaBridge(CinemaBrowser host) {
        this.host = host;
    }

    void installDiscovery() {
        var rt = host.runtime();
        if (rt == null) return;
        rt.getWebExtensionController().ensureBuiltIn(
                "resource://android/assets/cinema-video/", VIDEO_EXTENSION).accept(extension -> {
            discovery = extension;
            for (var tab : host.tabs) registerDiscovery(tab);
        }, error -> android.util.Log.w("CinemaBrowser", "Video discovery unavailable", error));
    }

    void attachMediaSessionDelegate(GeckoSession tab) {
        tab.setMediaSessionDelegate(new org.mozilla.geckoview.MediaSession.Delegate() {
            @Override
            public void onActivated(GeckoSession session, org.mozilla.geckoview.MediaSession media) {
                mediaSessions.put(session, media);
            }

            @Override
            public void onDeactivated(GeckoSession session, org.mozilla.geckoview.MediaSession media) {
                mediaSessions.remove(session);
                mediaSessionPlaying.remove(session);
                updateDiscovery();
            }

            @Override
            public void onPlay(GeckoSession session, org.mozilla.geckoview.MediaSession media) {
                mediaSessions.put(session, media);
                mediaSessionPlaying.add(session);
                updateDiscovery();
            }

            @Override
            public void onPause(GeckoSession session, org.mozilla.geckoview.MediaSession media) {
                mediaSessionPlaying.remove(session);
                updateDiscovery();
            }

            @Override
            public void onStop(GeckoSession session, org.mozilla.geckoview.MediaSession media) {
                mediaSessionPlaying.remove(session);
                updateDiscovery();
            }
        });
    }

    void sendZoomToPort(WebExtension.Port port, int percent) {
        if (port == null) return;
        try {
            port.postMessage(new org.json.JSONObject()
                    .put("action", "setZoom")
                    .put("percent", percent));
        } catch (org.json.JSONException ignored) {}
    }

    void sendZoomToSession(GeckoSession session, int percent) {
        if (session == null) return;
        var ports = mediaPorts.get(session);
        if (ports == null || ports.isEmpty()) return;
        for (var port : java.util.List.copyOf(ports)) {
            sendZoomToPort(port, percent);
        }
    }

    void broadcastZoom(int percent) {
        for (var ports : mediaPorts.values()) {
            if (ports == null) continue;
            for (var port : java.util.List.copyOf(ports)) {
                sendZoomToPort(port, percent);
            }
        }
    }

    void pauseIfBackgroundNotAllowed(GeckoSession source) {
        if (source != null && !allowBackgroundAudioTabs.contains(source)) {
            pauseCastSource(source);
        }
    }

    void pauseCastSource(GeckoSession source) {
        var media = mediaSessions.get(source);
        if (media != null) media.pause();
        var ports = mediaPorts.get(source);
        if (ports == null) return;
        try {
            var command = new org.json.JSONObject().put("action", "pause");
            for (var port : java.util.List.copyOf(ports)) port.postMessage(command);
            android.util.Log.d("CinemaBrowser", "Pause requested for " + ports.size() + " frame(s)");
        } catch (org.json.JSONException ignored) {}
    }

    boolean isTabAudible(GeckoSession tab) {
        if (tab == null) return false;
        if (mediaSessionPlaying.contains(tab)) return true;
        var frames = audibleFrames.get(tab);
        return frames != null && !frames.isEmpty();
    }

    void muteAndPauseTab(GeckoSession target) {
        if (target == null) return;
        allowBackgroundAudioTabs.remove(target);
        mediaSessionPlaying.remove(target);
        audibleFrames.remove(target);
        var media = mediaSessions.get(target);
        if (media != null) media.pause();
        var ports = mediaPorts.get(target);
        if (ports != null) {
            try {
                var muteCmd = new org.json.JSONObject().put("action", "mute");
                for (var port : java.util.List.copyOf(ports)) port.postMessage(muteCmd);
            } catch (org.json.JSONException ignored) {}
        }
        updateDiscovery();
    }

    String getSessionCleanTitle(GeckoSession tab) {
        return sessionCleanTitles.getOrDefault(tab, "");
    }

    String getLastVerifiedSessionTitle(GeckoSession tab) {
        return lastVerifiedSessionTitles.getOrDefault(tab, "");
    }

    String resolveActiveSessionTitle(GeckoSession tab) {
        if (tab == null) return "";
        return resolveBestSessionTitle(tab, videoOptions(tab), torrentOptions(tab));
    }

    static boolean isCloakedWrapperUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return false;
        try {
            String h = Uri.parse(rawUrl.trim()).getHost();
            return h != null && CLOAKED_WRAPPER_HOST_PATTERN.matcher(h).find();
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isOptionDownloadable(VideoOption option) {
        if (option == null) return false;
        String u = option.url();
        if (u == null || !u.startsWith("http")) return false;
        String k = option.kind() == null ? "" : option.kind().toLowerCase(java.util.Locale.ROOT);
        return !"dash".equals(k) && !"drm".equals(k) && !"embedded".equals(k);
    }

    void onTitleChanged(GeckoSession session, String title) {
        String pageUrl = session != null ? java.util.Objects.toString(host.locations.get(session), "") : "";
        if (isCloakedWrapperUrl(pageUrl)) return;
        String cleaned = cleanVideoTitle(title);
        if (!cleaned.isBlank() && !topFrameTitledSessions.contains(session)) {
            sessionCleanTitles.put(session, cleaned);
            lastVerifiedSessionTitles.put(session, cleaned);
        }
        if (session == lastCastSession && !cleaned.isBlank() && !cleaned.equals(lastBroadcastTitle)) {
            lastBroadcastTitle = cleaned;
            PhoneModuleBridges.tvHub().updateActiveCastTitle(host.context, cleaned);
        }
    }

    boolean isSheetShowing() {
        return videoSheetDialog != null && videoSheetDialog.isShowing();
    }

    static boolean isStoryboardOrImageSubtitle(String url, String label, String content) {
        if (url != null && STORYBOARD_SUB_HINT_PATTERN.matcher(url).find()) return true;
        if (label != null && STORYBOARD_SUB_HINT_PATTERN.matcher(label).find()) return true;
        return content != null && !content.isBlank() && isStoryboardVttBody(content);
    }

    static boolean isStoryboardVttBody(String rawText) {
        if (rawText == null || rawText.isBlank()) return false;
        boolean sawStoryboard = false;
        boolean sawDialogue = false;
        for (String raw : rawText.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            String up = t.toUpperCase(java.util.Locale.ROOT);
            if (up.startsWith("WEBVTT") || up.startsWith("NOTE") || up.startsWith("STYLE")
                    || up.startsWith("REGION") || t.matches("^\\d+$") || t.contains("-->")) {
                continue;
            }
            String stripped = t.replaceAll("<[^>]+>", "").trim();
            if (stripped.toLowerCase(java.util.Locale.ROOT).contains("#xywh=")
                    || IMAGE_CUE_LINE_PATTERN.matcher(stripped).matches()) {
                sawStoryboard = true;
            } else if (!stripped.isEmpty()) {
                sawDialogue = true;
                break;
            }
        }
        return sawStoryboard && !sawDialogue;
    }

    static boolean isAdMediaStreamUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return false;
        try {
            Uri u = Uri.parse(rawUrl.trim());
            String h = u.getHost();
            if (h != null && AD_STREAM_HOST_PATTERN.matcher(h).find()) return true;
            String p = u.getPath();
            if (p != null && AD_STREAM_PATH_PATTERN.matcher(p).find()) return true;
            String q = u.getEncodedQuery();
            return q != null && q.matches("(?i).*(?:^|&)(?:ad_tag|adtag|vast|preroll|ad_url|ad_type)=.*");
        } catch (Exception ignored) {
            return false;
        }
    }

    static String deriveOriginFromReferer(String referer) {
        if (referer == null || referer.isBlank()) return "";
        try {
            Uri u = Uri.parse(referer.trim());
            String scheme = u.getScheme();
            String auth = u.getEncodedAuthority();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && auth != null && !auth.isBlank() && u.getUserInfo() == null) {
                return scheme.toLowerCase(java.util.Locale.ROOT) + "://" + auth;
            }
        } catch (Exception ignored) {}
        return "";
    }

    void clearSessionDiscoveryState(GeckoSession target) {
        if (target == null) return;
        frameSnapshots.remove(target);
        probedHlsCache.remove(target);
        videos.remove(target);
        frameSubtitleSnapshots.remove(target);
        frameTorrentSnapshots.remove(target);
        sessionTorrents.remove(target);
        hardcodedSubSessions.remove(target);
        probedHlsSubtitles.remove(target);
        sessionSubtitles.remove(target);
        selectedPrimarySubs.remove(target);
        selectedSecondarySubs.remove(target);
        mediaSessionPlaying.remove(target);
        audibleFrames.remove(target);
        sessionCleanTitles.remove(target);
        topFrameTitledSessions.remove(target);
        String prefix = System.identityHashCode(target) + "|";
        synchronized (hlsProbeInFlight) {
            hlsProbeInFlight.removeIf(k -> k.startsWith(prefix));
        }
        synchronized (headSizeInFlight) {
            headSizeInFlight.removeIf(k -> k.startsWith(prefix));
        }
        synchronized (subProbeInFlight) {
            subProbeInFlight.removeIf(k -> k.startsWith(prefix));
        }
    }

    void removeSession(GeckoSession tab) {
        if (tab == null) return;
        videos.remove(tab);
        frameSnapshots.remove(tab);
        probedHlsCache.remove(tab);
        frameSubtitleSnapshots.remove(tab);
        frameTorrentSnapshots.remove(tab);
        sessionTorrents.remove(tab);
        hardcodedSubSessions.remove(tab);
        probedHlsSubtitles.remove(tab);
        sessionSubtitles.remove(tab);
        selectedPrimarySubs.remove(tab);
        selectedSecondarySubs.remove(tab);
        mediaPorts.remove(tab);
        mediaSessions.remove(tab);
        mediaSessionPlaying.remove(tab);
        audibleFrames.remove(tab);
        sessionCleanTitles.remove(tab);
        lastVerifiedSessionTitles.remove(tab);
        topFrameTitledSessions.remove(tab);
        allowBackgroundAudioTabs.remove(tab);
        if (lastCastSession == tab) {
            lastCastSession = null;
            lastCastCanonicalUrl = "";
            lastCastTimestampMs = 0L;
        }
    }

    void clearAllOnDispose() {
        if (videoSheetDialog != null && videoSheetDialog.isShowing()) {
            videoSheetDialog.dismiss();
        }
        videoSheetDialog = null;
        videoSheetCards = null;
        videoSheetStatus = null;
        videoSheetSubSummary = null;
        videoSheetScroll = null;
        videoSheetHolders.clear();
        renderedSheetSignature = "";
        videos.clear();
        frameSnapshots.clear();
        probedHlsCache.clear();
        hlsProbeInFlight.clear();
        frameSubtitleSnapshots.clear();
        frameTorrentSnapshots.clear();
        sessionTorrents.clear();
        hardcodedSubSessions.clear();
        probedHlsSubtitles.clear();
        sessionSubtitles.clear();
        selectedPrimarySubs.clear();
        selectedSecondarySubs.clear();
        headSizeInFlight.clear();
        subProbeInFlight.clear();
        mediaPorts.clear();
        mediaSessions.clear();
        mediaSessionPlaying.clear();
        audibleFrames.clear();
        sessionCleanTitles.clear();
        lastVerifiedSessionTitles.clear();
        topFrameTitledSessions.clear();
        allowBackgroundAudioTabs.clear();
        lastCastSession = null;
        lastBroadcastTitle = "";
    }

    void refreshVideoSheetIfShowing(GeckoSession session) {
        if (videoSheetDialog != null && videoSheetDialog.isShowing()) {
            renderVideoSheetCards(session);
        }
    }

    void rescanActiveSession() {
        var target = host.active;
        if (target == null || target == host.homeTab) return;
        clearSessionDiscoveryState(target);
        updateDiscovery();
        if (videoSheetDialog != null && videoSheetDialog.isShowing()) {
            renderVideoSheetCards(target);
        }
        var ports = mediaPorts.get(target);
        if (ports == null || ports.isEmpty()) return;
        try {
            var command = new org.json.JSONObject().put("action", "rescan");
            for (var port : java.util.List.copyOf(ports)) port.postMessage(command);
        } catch (org.json.JSONException ignored) {}
    }

    void registerDiscovery(GeckoSession tab) {
        if (discovery == null) return;
        tab.getWebExtensionController().setMessageDelegate(discovery, new WebExtension.MessageDelegate() {
            @Override
            public void onConnect(WebExtension.Port port) {
                var ports = mediaPorts.computeIfAbsent(tab, ignored -> new java.util.HashSet<>());
                ports.add(port);
                sendZoomToPort(port, host.getBrowserZoomPercent());
                port.setDelegate(new WebExtension.PortDelegate() {
                    @Override
                    public void onDisconnect(WebExtension.Port disconnected) {
                        ports.remove(disconnected);
                    }
                });
            }

            @Override
            public GeckoResult<Object> onMessage(String app, Object message,
                                                 WebExtension.MessageSender sender) {
                if (!(message instanceof String json) || json.length() > 1900000) return null;
                final String frameBase = "frame:" + java.util.Objects.toString(sender.url, "");
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!host.tabs.contains(sender.session)) return;
                    try {
                        String frameKey = frameBase;
                        org.json.JSONArray incoming;
                        org.json.JSONArray incomingSubs = null;
                        org.json.JSONArray incomingTorrents = null;
                        String trimmed = json.trim();
                        if (trimmed.startsWith("{")) {
                            var obj = new org.json.JSONObject(trimmed);
                            String act = obj.optString("action", "");
                            if ("openVideoSheet".equals(act)) {
                                if (sender.session == host.active) showVideos();
                                return;
                            }
                            if ("scrollState".equals(act)) {
                                return;
                            }
                            if (obj.optBoolean("hardcodedSubtitles", false)) {
                                hardcodedSubSessions.add(sender.session);
                            }
                            String tok = obj.optString("frameToken", "");
                            if (!tok.isEmpty()) frameKey = "tok:" + tok;
                            boolean frameAudible = obj.optBoolean("audioPlaying", false);
                            var audSet = audibleFrames.computeIfAbsent(sender.session, ignored -> new java.util.HashSet<>());
                            if (frameAudible) audSet.add(frameKey);
                            else audSet.remove(frameKey);

                            incoming = obj.optJSONArray("items");
                            if (incoming == null) incoming = new org.json.JSONArray();
                            incomingSubs = obj.optJSONArray("subtitles");
                            incomingTorrents = obj.optJSONArray("torrents");

                            boolean isTopFrame = obj.optBoolean("isTopFrame", false);
                            boolean topCloaked = obj.optBoolean("topCloaked", false);
                            String topPageUrl = java.util.Objects.toString(host.locations.get(sender.session), "");
                            boolean hostCloaked = isCloakedWrapperUrl(topPageUrl)
                                    || (isTopFrame && isCloakedWrapperUrl(sender.url));
                            String metaTitle = cleanVideoTitle(obj.optString("metaTitle", ""));
                            if (isTopFrame) {
                                if (!metaTitle.isBlank() && !topCloaked && !hostCloaked) {
                                    sessionCleanTitles.put(sender.session, metaTitle);
                                    lastVerifiedSessionTitles.put(sender.session, metaTitle);
                                    topFrameTitledSessions.add(sender.session);
                                    if (sender.session == lastCastSession && !metaTitle.equals(lastBroadcastTitle)) {
                                        lastBroadcastTitle = metaTitle;
                                        PhoneModuleBridges.tvHub().updateActiveCastTitle(host.context, metaTitle);
                                    }
                                } else {
                                    topFrameTitledSessions.remove(sender.session);
                                    if (!metaTitle.isBlank() && !hostCloaked) {
                                        sessionCleanTitles.put(sender.session, metaTitle);
                                        if (sender.session == lastCastSession && !metaTitle.equals(lastBroadcastTitle)) {
                                            lastBroadcastTitle = metaTitle;
                                            PhoneModuleBridges.tvHub().updateActiveCastTitle(host.context, metaTitle);
                                        }
                                    }
                                }
                            } else if (!obj.optBoolean("adPlaying", false)
                                    && !metaTitle.isBlank()
                                    && (incoming.length() > 0 || frameAudible)) {
                                String curClean = sessionCleanTitles.getOrDefault(sender.session, "");
                                boolean childHasRichEpisodeTitle = metaTitle.matches("(?s).*[\\u0600-\\u06FF]{2,}.*")
                                        || metaTitle.matches("(?i).*\\bS\\d{1,2}E\\d{1,3}\\b.*");
                                boolean curIsWeakLatin = curClean.isBlank()
                                        || (!curClean.matches("(?s).*[\\u0600-\\u06FF].*") && childHasRichEpisodeTitle);
                                if (!topFrameTitledSessions.contains(sender.session) || hostCloaked || curIsWeakLatin) {
                                    sessionCleanTitles.put(sender.session, metaTitle);
                                    lastVerifiedSessionTitles.put(sender.session, metaTitle);
                                    if (sender.session == lastCastSession && !metaTitle.equals(lastBroadcastTitle)) {
                                        lastBroadcastTitle = metaTitle;
                                        PhoneModuleBridges.tvHub().updateActiveCastTitle(host.context, metaTitle);
                                    }
                                }
                            }
                        } else {
                            incoming = new org.json.JSONArray(trimmed);
                        }

                        var tabFrames = frameSnapshots.computeIfAbsent(sender.session,
                                ignored -> new java.util.LinkedHashMap<>());
                        if (incoming.length() == 0) {
                            tabFrames.remove(frameKey);
                        } else {
                            var bounded = new org.json.JSONArray();
                            for (int i = 0; i < incoming.length() && i < 40; i++) {
                                var item = incoming.optJSONObject(i);
                                if (item == null) continue;
                                bounded.put(item);
                                String fmt = item.optString("format");
                                if ("hls".equalsIgnoreCase(fmt)
                                        && !item.optBoolean("manifest")
                                        && !item.optBoolean("masterQuality")
                                        && item.optInt("height") == 0) {
                                    probeHlsManifestIfNeeded(sender.session, item);
                                } else if (!"hls".equalsIgnoreCase(fmt)
                                        && !"dash".equalsIgnoreCase(fmt)
                                        && !"embedded".equalsIgnoreCase(fmt)
                                        && !"drm".equalsIgnoreCase(fmt)
                                        && item.optLong("size", 0L) <= 0L) {
                                    probeDirectStreamSizeIfNeeded(sender.session, item);
                                }
                            }
                            tabFrames.put(frameKey, bounded);
                        }

                        var tabSubFrames = frameSubtitleSnapshots.computeIfAbsent(sender.session,
                                ignored -> new java.util.LinkedHashMap<>());
                        if (incomingSubs == null || incomingSubs.length() == 0) {
                            tabSubFrames.remove(frameKey);
                        } else {
                            var boundedSubs = new org.json.JSONArray();
                            for (int i = 0; i < incomingSubs.length() && i < 30; i++) {
                                var sub = incomingSubs.optJSONObject(i);
                                if (sub == null) continue;
                                boundedSubs.put(sub);
                                probeSubtitleMetadataIfNeeded(sender.session, sub);
                            }
                            tabSubFrames.put(frameKey, boundedSubs);
                        }

                        var tabTorFrames = frameTorrentSnapshots.computeIfAbsent(sender.session,
                                ignored -> new java.util.LinkedHashMap<>());
                        if (incomingTorrents == null || incomingTorrents.length() == 0) {
                            tabTorFrames.remove(frameKey);
                        } else {
                            var boundedTorrents = new org.json.JSONArray();
                            for (int i = 0; i < incomingTorrents.length() && i < 35; i++) {
                                var tor = incomingTorrents.optJSONObject(i);
                                if (tor != null) boundedTorrents.put(tor);
                            }
                            tabTorFrames.put(frameKey, boundedTorrents);
                        }

                        rebuildSessionSubtitles(sender.session);
                        rebuildSessionTorrents(sender.session);
                        rebuildSessionVideos(sender.session);
                        maybeAutoSwitchPostPrerollCast(sender.session);
                        updateDiscovery();
                        if (sender.session == host.active) {
                            if (videoSheetDialog != null && videoSheetDialog.isShowing()) {
                                renderVideoSheetCards(sender.session);
                            }
                            if (host.downloadsDialog != null && host.downloadsDialog.isShowing()) {
                                host.renderDownloadsContainer();
                            }
                        }
                    } catch (org.json.JSONException ignored) {}
                });
                return GeckoResult.fromValue(false);
            }
        }, "cinemaVideo");
    }

    private void rebuildSessionSubtitles(GeckoSession session) {
        var combined = new java.util.LinkedHashMap<String, SubtitleOption>();
        var tabSubFrames = frameSubtitleSnapshots.get(session);
        if (tabSubFrames != null) {
            for (var arr : tabSubFrames.values()) {
                collectSubtitleArrayInto(arr, combined);
            }
        }
        var hlsSubs = probedHlsSubtitles.get(session);
        if (hlsSubs != null) {
            for (var arr : hlsSubs.values()) {
                collectSubtitleArrayInto(arr, combined);
            }
        }
        if (combined.isEmpty()) sessionSubtitles.remove(session);
        else sessionSubtitles.put(session, new java.util.ArrayList<>(combined.values()));
    }

    static boolean isHardcodedSubtitleUrl(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String low = raw.toLowerCase(java.util.Locale.ROOT);
        return low.contains("cinemana.shabakaty.com")
                || (low.contains("shabakaty.com") && low.contains("cinemana"));
    }

    boolean isHardcodedSubtitleSession(GeckoSession session, String streamUrl, String referer) {
        if (session != null && hardcodedSubSessions.contains(session)) return true;
        String loc = session != null ? java.util.Objects.toString(host.locations.get(session), "") : "";
        return isHardcodedSubtitleUrl(loc) || isHardcodedSubtitleUrl(referer) || isHardcodedSubtitleUrl(streamUrl);
    }

    private void rebuildSessionTorrents(GeckoSession session) {
        var combined = new java.util.LinkedHashMap<String, TorrentGrabOption>();
        var tabTorFrames = frameTorrentSnapshots.get(session);
        if (tabTorFrames != null) {
            for (var arr : tabTorFrames.values()) {
                for (int i = 0; i < arr.length() && combined.size() < 40; i++) {
                    var item = arr.optJSONObject(i);
                    if (item == null) continue;
                    String magnet = item.optString("magnetUri", "").trim();
                    String torUrl = item.optString("torrentUrl", "").trim();
                    String uri = !magnet.isEmpty() ? magnet : (!torUrl.isEmpty() ? torUrl : item.optString("uri", "").trim());
                    if (uri.isEmpty()) continue;
                    String hash = item.optString("hash", "").trim().toLowerCase(java.util.Locale.ROOT);
                    String key = !hash.isEmpty() ? ("btih:" + hash) : item.optString("key", uri);
                    TorrentGrabOption prev = combined.get(key);
                    if (prev != null) {
                        if (magnet.isEmpty()) magnet = prev.magnetUri();
                        if (torUrl.isEmpty()) torUrl = prev.torrentUrl();
                        uri = !magnet.isEmpty() ? magnet : torUrl;
                    }
                    String rawTitle = item.optString("title", prev != null ? prev.title() : "");
                    String quality = item.optString("quality", prev != null ? prev.quality() : "");
                    String sizeText = item.optString("sizeText", prev != null ? prev.sizeText() : "");
                    long sizeBytes = Math.max(item.optLong("size", 0L), prev != null ? prev.sizeBytes() : 0L);
                    int seeds = Math.max(item.optInt("seeds", 0), prev != null ? prev.seeds() : 0);
                    combined.put(key, new TorrentGrabOption(
                            key,
                            hash,
                            magnet,
                            torUrl,
                            uri,
                            !magnet.isEmpty() ? "magnet" : "torrent",
                            rawTitle,
                            quality,
                            sizeText,
                            sizeBytes,
                            seeds,
                            item.optString("referer", prev != null ? prev.referer() : ""),
                            item.optString("userAgent", prev != null ? prev.userAgent() : ""),
                            item.optString("cookie", prev != null ? prev.cookie() : "")));
                }
            }
        }
        if (combined.isEmpty()) sessionTorrents.remove(session);
        else sessionTorrents.put(session, new java.util.ArrayList<>(combined.values()));
    }

    java.util.List<TorrentGrabOption> torrentOptions(GeckoSession tab) {
        if (tab == null) return java.util.List.of();
        return sessionTorrents.getOrDefault(tab, java.util.List.of());
    }

    private void collectSubtitleArrayInto(org.json.JSONArray arr, java.util.Map<String, SubtitleOption> combined) {
        for (int i = 0; i < arr.length() && combined.size() < 40; i++) {
            var item = arr.optJSONObject(i);
            if (item == null) continue;
            String url = item.optString("url", "");
            String content = item.optString("content", "");
            if (url.isBlank() && content.isBlank()) continue;
            String mime = item.optString("mime", "");
            String lang = item.optString("language", "");
            String label = item.optString("label", "");
            if (isStoryboardOrImageSubtitle(url, label, content)) continue;
            long size = item.optLong("size", 0L);
            boolean isDual = item.optBoolean("isDual", false) || "dual".equalsIgnoreCase(lang);
            if (!content.isEmpty()) {
                if (size <= 0L) size = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (lang.isBlank()) {
                    var detected = detectSubtitleLanguage(content);
                    lang = detected.language();
                    isDual = detected.isDual();
                }
            } else if (!url.isBlank()) {
                SubProbeMeta meta = subProbeCache.get(url);
                if (meta != null) {
                    if ("__storyboard__".equals(meta.language())) continue;
                    if (size <= 0L && meta.sizeBytes() > 0L) size = meta.sizeBytes();
                    if (lang.isBlank() && !meta.language().isBlank()) lang = meta.language();
                    if (meta.isDual()) isDual = true;
                }
            }
            String key = canonicalSubtitleDedupKey(url, content);
            SubtitleOption existing = combined.get(key);
            if (existing == null && size > 0L) {
                String normLang = normalizeBrowserSubLang(lang, label);
                for (var entry : combined.entrySet()) {
                    SubtitleOption cur = entry.getValue();
                    String curLang = normalizeBrowserSubLang(cur.language(), cur.label());
                    if (cur.sizeBytes() == size && !normLang.isEmpty() && normLang.equals(curLang)) {
                        key = entry.getKey();
                        existing = cur;
                        break;
                    }
                }
            }
            if (existing != null) {
                if (url.isBlank() || (!existing.url().isBlank() && existing.url().length() < url.length())) {
                    if (!existing.url().isBlank()) url = existing.url();
                }
                if (size <= 0L) size = existing.sizeBytes();
                if (lang.isBlank()) lang = existing.language();
                if (label.isBlank()) label = existing.label();
                if (content.isBlank()) content = existing.content();
                if (!isDual) isDual = existing.isDual();
            }
            combined.put(key, new SubtitleOption(
                    url, mime, lang, label, size, content, isDual,
                    item.optString("referer", ""),
                    item.optString("origin", ""),
                    item.optString("userAgent", ""),
                    item.optString("cookie", ""),
                    item.optString("authorization", "")));
        }
    }

    private static String canonicalSubtitleDedupKey(String url, String content) {
        if (url == null || url.isBlank()) {
            return "mem:" + Integer.toHexString(content == null ? 0 : content.hashCode());
        }
        try {
            Uri u = Uri.parse(url.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
            String hostName = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
            String path = u.getPath() == null ? "" : u.getPath();
            var builder = new Uri.Builder().scheme(scheme)
                    .encodedAuthority(u.getPort() > 0 ? hostName + ":" + u.getPort() : hostName)
                    .path(path);
            for (String q : u.getQueryParameterNames()) {
                String ql = q.toLowerCase(java.util.Locale.ROOT);
                if (ql.equals("token") || ql.equals("expires") || ql.equals("exp") || ql.equals("sig")
                        || ql.equals("signature") || ql.equals("t") || ql.equals("_") || ql.equals("rand")
                        || ql.equals("cb") || ql.equals("ts") || ql.equals("nonce") || ql.equals("v")
                        || ql.equals("wmsauthsign") || ql.equals("hdnts")) {
                    continue;
                }
                String val = u.getQueryParameter(q);
                if (val != null) builder.appendQueryParameter(q, val);
            }
            return builder.build().toString();
        } catch (Exception ignored) {
            return url.trim();
        }
    }

    private static String normalizeBrowserSubLang(String lang, String label) {
        String s = ((lang == null ? "" : lang) + " " + (label == null ? "" : label)).toLowerCase(java.util.Locale.ROOT);
        if (s.contains("عرب") || s.matches(".*\\b(ar|ara|arabic)\\b.*")) return "ar";
        if (s.matches(".*\\b(en|eng|english)\\b.*")) return "en";
        if (s.matches(".*\\b(fr|fra|fre|french)\\b.*")) return "fr";
        if (s.matches(".*\\b(es|spa|spanish)\\b.*")) return "es";
        if (s.matches(".*\\b(tr|tur|turkish)\\b.*")) return "tr";
        return lang == null ? "" : lang.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void rebuildSessionVideos(GeckoSession session) {
        var tabFrames = frameSnapshots.get(session);
        var combined = new java.util.LinkedHashMap<String, org.json.JSONObject>();
        var probes = probedHlsCache.get(session);
        if (tabFrames != null) {
            for (var frameArr : tabFrames.values()) {
                for (int i = 0; i < frameArr.length() && combined.size() < 80; i++) {
                    var item = frameArr.optJSONObject(i);
                    if (item == null) continue;
                    String itemUrl = item.optString("url", "");
                    if (probes != null && probes.containsKey(itemUrl)) continue;
                    combined.put(item.optString("id", itemUrl), item);
                }
            }
        }
        if (probes != null) {
            for (var probeArr : probes.values()) {
                for (int i = 0; i < probeArr.length() && combined.size() < 80; i++) {
                    var item = probeArr.optJSONObject(i);
                    if (item == null) continue;
                    combined.put(item.optString("id", item.optString("url", "")), item);
                }
            }
        }
        if (combined.isEmpty()) videos.remove(session);
        else videos.put(session, new org.json.JSONArray(combined.values()));
    }

    private void probeHlsManifestIfNeeded(GeckoSession session, org.json.JSONObject baseItem) {
        String url = baseItem.optString("url", "");
        if (url.isBlank()) return;
        String probeKey = System.identityHashCode(session) + "|" + url;
        var existingProbes = probedHlsCache.get(session);
        if ((existingProbes != null && existingProbes.containsKey(url)) || !hlsProbeInFlight.add(probeKey)) {
            return;
        }
        final String referer = baseItem.optString("referer", "");
        final String origin = baseItem.optString("origin", "");
        final String userAgent = baseItem.optString("userAgent", "");
        final String cookie = baseItem.optString("cookie", "");
        final String auth = baseItem.optString("authorization", "");
        final String title = baseItem.optString("title", "");

        hlsProbeExecutor.execute(() -> {
            try {
                HlsProbeFetch fetch = fetchSmallManifestWithHeaders(url, referer, origin, userAgent, cookie, auth);
                if (fetch == null || fetch.body == null) return;
                String body = fetch.body.startsWith("\uFEFF") ? fetch.body.substring(1) : fetch.body;
                if (!body.trim().startsWith("#EXTM3U")) return;

                String[] lines = body.split("\\r?\\n");
                boolean isMaster = false;
                for (String ln : lines) {
                    if (ln.startsWith("#EXT-X-STREAM-INF:")) {
                        isMaster = true;
                        break;
                    }
                }
                boolean hasVod = body.contains("#EXT-X-ENDLIST")
                        || body.toUpperCase(java.util.Locale.ROOT).contains("#EXT-X-PLAYLIST-TYPE:VOD");
                boolean isLive = !isMaster && (body.contains("#EXTINF:") || body.contains("#EXT-X-PART:")) && !hasVod;

                String bestSubUrl = baseItem.optString("subtitleUrl", "");
                String bestSubLang = baseItem.optString("subtitleLanguage", "");
                String bestSubMime = baseItem.optString("subtitleMime", "");
                var hlsSubArray = new org.json.JSONArray();
                if (isMaster) {
                    int bestSubScore = -1;
                    var uriPat = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"");
                    var langPat = java.util.regex.Pattern.compile("LANGUAGE=\"([^\"]+)\"");
                    var namePat = java.util.regex.Pattern.compile("NAME=\"([^\"]+)\"");
                    for (String ln : lines) {
                        if (!ln.startsWith("#EXT-X-MEDIA:")
                                || !ln.toUpperCase(java.util.Locale.ROOT).contains("TYPE=SUBTITLES")) continue;
                        var um = uriPat.matcher(ln);
                        if (!um.find()) continue;
                        try {
                            String absSub = new java.net.URL(fetch.finalUrl, um.group(1)).toString();
                            var lm = langPat.matcher(ln);
                            var nm = namePat.matcher(ln);
                            String lang = lm.find() ? lm.group(1) : "";
                            String name = nm.find() ? nm.group(1) : "";
                            if (isStoryboardOrImageSubtitle(absSub, name, null)) continue;
                            String mime = absSub.toLowerCase(java.util.Locale.ROOT).contains(".m3u8")
                                    ? "application/vnd.apple.mpegurl" : "text/vtt";
                            var subObj = new org.json.JSONObject()
                                    .put("url", absSub)
                                    .put("mime", mime)
                                    .put("language", lang)
                                    .put("label", name)
                                    .put("referer", referer)
                                    .put("origin", origin)
                                    .put("userAgent", userAgent)
                                    .put("cookie", cookie)
                                    .put("authorization", auth);
                            hlsSubArray.put(subObj);
                            int score = 10;
                            if (ln.toUpperCase(java.util.Locale.ROOT).contains("DEFAULT=YES")) score += 20;
                            String low = (lang + " " + name + " " + absSub).toLowerCase(java.util.Locale.ROOT);
                            if (low.contains("ar") || low.contains("عرب")) score += 30;
                            if (score > bestSubScore) {
                                bestSubScore = score;
                                bestSubUrl = absSub;
                                bestSubLang = !lang.isEmpty() ? lang : name;
                                bestSubMime = mime;
                            }
                        } catch (Exception ignored) {}
                    }

                    for (int i = 0; i < lines.length - 1; i++) {
                        if (!lines[i].startsWith("#EXT-X-STREAM-INF:")) continue;
                        String variantLine = lines[i + 1].trim();
                        if (variantLine.isEmpty() || variantLine.startsWith("#")) continue;
                        try {
                            String childUrl = new java.net.URL(fetch.finalUrl, variantLine).toString();
                            HlsProbeFetch childFetch = fetchSmallManifestWithHeaders(childUrl, referer, origin, userAgent, cookie, auth);
                            if (childFetch != null && childFetch.body != null && childFetch.body.contains("#EXTM3U")) {
                                String cb = childFetch.body;
                                boolean childVod = cb.contains("#EXT-X-ENDLIST")
                                        || cb.toUpperCase(java.util.Locale.ROOT).contains("#EXT-X-PLAYLIST-TYPE:VOD");
                                boolean childSegs = cb.contains("#EXTINF:") || cb.contains("#EXT-X-PART:");
                                isLive = childSegs && !childVod;
                            }
                        } catch (Exception ignored) {}
                        break;
                    }
                }

                var result = new org.json.JSONArray();
                var autoItem = new org.json.JSONObject();
                autoItem.put("id", url);
                autoItem.put("url", url);
                autoItem.put("title", title);
                autoItem.put("format", "hls");
                autoItem.put("manifest", isMaster);
                autoItem.put("masterQuality", false);
                autoItem.put("height", 0);
                autoItem.put("live", isLive);
                autoItem.put("active", baseItem.optBoolean("active", false));
                autoItem.put("referer", referer);
                autoItem.put("origin", origin);
                autoItem.put("userAgent", userAgent);
                autoItem.put("cookie", cookie);
                autoItem.put("authorization", auth);
                if (!bestSubUrl.isEmpty() && !isHardcodedSubtitleSession(session, url, referer)) {
                    autoItem.put("subtitleUrl", bestSubUrl);
                    autoItem.put("subtitleMime", bestSubMime);
                    autoItem.put("subtitleLanguage", bestSubLang);
                    autoItem.put("subtitleReferer", referer);
                    autoItem.put("subtitleOrigin", origin);
                    autoItem.put("subtitleUserAgent", userAgent);
                    autoItem.put("subtitleCookie", cookie);
                    autoItem.put("subtitleAuthorization", auth);
                }
                result.put(autoItem);

                if (isMaster) {
                    var resPat = java.util.regex.Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
                    var bwPat = java.util.regex.Pattern.compile("(?:[:,])BANDWIDTH=(\\d+)");
                    for (int i = 0; i < lines.length - 1 && result.length() < 20; i++) {
                        if (!lines[i].startsWith("#EXT-X-STREAM-INF:")) continue;
                        var rm = resPat.matcher(lines[i]);
                        int w = rm.find() ? Integer.parseInt(rm.group(1)) : 0;
                        int h = w > 0 ? normalizeResolutionHeight(w, Integer.parseInt(rm.group(2))) : 0;
                        String variantLine = lines[i + 1].trim();
                        if (h <= 0 || variantLine.isEmpty() || variantLine.startsWith("#")) continue;
                        var qItem = new org.json.JSONObject(autoItem.toString());
                        qItem.put("id", url + "#height=" + h);
                        qItem.put("height", h);
                        qItem.put("masterQuality", true);
                        var bm = bwPat.matcher(lines[i]);
                        if (bm.find()) qItem.put("bitrate", Integer.parseInt(bm.group(1)));
                        result.put(qItem);
                    }
                }

                final var foundSubs = hlsSubArray;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!host.tabs.contains(session)) return;
                    probedHlsCache.computeIfAbsent(session, ignored -> new java.util.LinkedHashMap<>()).put(url, result);
                    if (foundSubs.length() > 0) {
                        probedHlsSubtitles.computeIfAbsent(session, ignored -> new java.util.LinkedHashMap<>()).put(url, foundSubs);
                        for (int sIdx = 0; sIdx < foundSubs.length(); sIdx++) {
                            var subObj = foundSubs.optJSONObject(sIdx);
                            if (subObj != null) probeSubtitleMetadataIfNeeded(session, subObj);
                        }
                        rebuildSessionSubtitles(session);
                    }
                    rebuildSessionVideos(session);
                    if (session == host.active) {
                        updateDiscovery();
                        if (videoSheetDialog != null && videoSheetDialog.isShowing()) {
                            renderVideoSheetCards(session);
                        }
                    }
                });
            } catch (Exception ignored) {
            } finally {
                hlsProbeInFlight.remove(probeKey);
            }
        });
    }

    private static HlsProbeFetch fetchSmallManifestWithHeaders(String urlStr, String referer, String origin,
                                                               String userAgent, String cookie, String auth) {
        String current = urlStr;
        for (int hop = 0; hop < 5; hop++) {
            java.net.HttpURLConnection conn = null;
            try {
                java.net.URL u = new java.net.URL(current);
                conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(4500);
                conn.setReadTimeout(6000);
                if (!referer.isEmpty()) conn.setRequestProperty("Referer", referer);
                if (!origin.isEmpty()) conn.setRequestProperty("Origin", origin);
                if (!userAgent.isEmpty()) conn.setRequestProperty("User-Agent", userAgent);
                if (!cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
                if (!auth.isEmpty()) conn.setRequestProperty("Authorization", auth);
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null || loc.isBlank()) return null;
                    current = new java.net.URL(u, loc.trim()).toString();
                    continue;
                }
                if (code < 200 || code >= 300) {
                    conn.disconnect();
                    return null;
                }
                try (var in = conn.getInputStream()) {
                    var baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (baos.size() + n > 262144) break;
                        baos.write(buf, 0, n);
                    }
                    java.net.URL resolvedUrl = conn.getURL();
                    conn.disconnect();
                    return new HlsProbeFetch(resolvedUrl, new String(baos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
                if (conn != null) conn.disconnect();
                return null;
            }
        }
        return null;
    }

    private static int normalizeResolutionHeight(int w, int h) {
        if (w <= 0 && h <= 0) return 0;
        if (w >= 3800) return 2160;
        if (w >= 2500) return 1440;
        if (w >= 1900) return 1080;
        if (w >= 1260) return 720;
        if (w >= 840) return 480;
        if (w >= 620) return 360;
        if (w >= 400) return 240;
        int[] std = new int[]{2160, 1440, 1080, 720, 480, 360, 240, 144};
        for (int s : std) {
            if (Math.abs(h - s) <= 24) return s;
        }
        return h;
    }

    private static int extractItagHeight(String url) {
        if (url == null || url.isEmpty()) return 0;
        var m = java.util.regex.Pattern.compile("(?:[?&]|/)itag(?:=|/)(\\d+)").matcher(url);
        if (!m.find()) return 0;
        return switch (m.group(1)) {
            case "313", "401", "315", "337", "266" -> 2160;
            case "271", "400", "308", "336" -> 1440;
            case "137", "248", "399", "299", "303", "335", "356" -> 1080;
            case "136", "247", "398", "298", "302", "334", "22" -> 720;
            case "135", "244", "397" -> 480;
            case "134", "243", "396", "18" -> 360;
            case "133", "242", "395" -> 240;
            case "160", "278" -> 144;
            default -> 0;
        };
    }

    private static long extractClenSize(String url) {
        if (url == null || url.isEmpty()) return 0L;
        var m = java.util.regex.Pattern.compile("[?&]clen=(\\d+)").matcher(url);
        if (!m.find()) return 0L;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void probeDirectStreamSizeIfNeeded(GeckoSession session, org.json.JSONObject item) {
        String url = item.optString("url", "");
        if (!url.startsWith("http")) return;
        if (extractClenSize(url) > 0L) return;
        if (headSizeCache.containsKey(url)) return;
        String key = System.identityHashCode(session) + "|" + url;
        if (!headSizeInFlight.add(key)) return;
        final String referer = item.optString("referer", "");
        final String origin = item.optString("origin", "");
        final String ua = item.optString("userAgent", "");
        final String cookie = item.optString("cookie", "");
        final String auth = item.optString("authorization", "");
        headProbeExecutor.execute(() -> {
            try {
                long bytes = fetchContentLengthFast(url, referer, origin, ua, cookie, auth);
                if (bytes > 0L) {
                    headSizeCache.put(url, bytes);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!host.tabs.contains(session) || session != host.active) return;
                        if (videoSheetDialog != null && videoSheetDialog.isShowing()) {
                            renderVideoSheetCards(session);
                        }
                    });
                }
            } finally {
                headSizeInFlight.remove(key);
            }
        });
    }

    private static long fetchContentLengthFast(String urlStr, String referer, String origin,
                                               String ua, String cookie, String auth) {
        for (String method : new String[]{"HEAD", "GET"}) {
            String current = urlStr;
            for (int hop = 0; hop < 4; hop++) {
                java.net.HttpURLConnection conn = null;
                try {
                    java.net.URL u = new java.net.URL(current);
                    conn = (java.net.HttpURLConnection) u.openConnection();
                    conn.setRequestMethod(method);
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(3500);
                    conn.setReadTimeout(4500);
                    if ("GET".equals(method)) conn.setRequestProperty("Range", "bytes=0-0");
                    if (!referer.isEmpty()) conn.setRequestProperty("Referer", referer);
                    if (!origin.isEmpty()) conn.setRequestProperty("Origin", origin);
                    if (!ua.isEmpty()) conn.setRequestProperty("User-Agent", ua);
                    if (!cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
                    if (!auth.isEmpty()) conn.setRequestProperty("Authorization", auth);
                    int code = conn.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String loc = conn.getHeaderField("Location");
                        conn.disconnect();
                        if (loc == null || loc.isBlank()) break;
                        current = new java.net.URL(u, loc.trim()).toString();
                        continue;
                    }
                    if (code == 206) {
                        String cr = conn.getHeaderField("Content-Range");
                        conn.disconnect();
                        if (cr != null) {
                            int slash = cr.lastIndexOf('/');
                            if (slash > 0 && slash < cr.length() - 1) {
                                String tot = cr.substring(slash + 1).trim();
                                if (!"*".equals(tot)) return Long.parseLong(tot);
                            }
                        }
                        break;
                    }
                    if (code >= 200 && code < 300) {
                        long len = conn.getContentLengthLong();
                        conn.disconnect();
                        if (len > 0L) return len;
                        break;
                    }
                    conn.disconnect();
                    break;
                } catch (Exception ignored) {
                    if (conn != null) conn.disconnect();
                    break;
                }
            }
        }
        return -1L;
    }

    private void probeSubtitleMetadataIfNeeded(GeckoSession session, org.json.JSONObject subItem) {
        String url = subItem.optString("url", "");
        String content = subItem.optString("content", "");
        if (!content.isEmpty() || !url.startsWith("http")) return;
        if (url.toLowerCase(java.util.Locale.ROOT).contains(".m3u8")) return;
        if (subProbeCache.containsKey(url)) return;
        if (subItem.optLong("size", 0L) > 0L && !subItem.optString("language", "").isBlank()) return;
        String key = System.identityHashCode(session) + "|sub|" + url;
        if (!subProbeInFlight.add(key)) return;
        final String referer = subItem.optString("referer", "");
        final String origin = subItem.optString("origin", "");
        final String ua = subItem.optString("userAgent", "");
        final String cookie = subItem.optString("cookie", "");
        final String auth = subItem.optString("authorization", "");
        headProbeExecutor.execute(() -> {
            try {
                SubProbeMeta meta = fetchSubtitleMetaFast(url, referer, origin, ua, cookie, auth);
                if (meta != null) {
                    subProbeCache.put(url, meta);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!host.tabs.contains(session)) return;
                        rebuildSessionSubtitles(session);
                        if (session == host.active && videoSheetDialog != null && videoSheetDialog.isShowing()) {
                            renderVideoSheetCards(session);
                        }
                    });
                }
            } finally {
                subProbeInFlight.remove(key);
            }
        });
    }

    private static SubProbeMeta fetchSubtitleMetaFast(String urlStr, String referer, String origin,
                                                      String ua, String cookie, String auth) {
        String current = urlStr;
        for (int hop = 0; hop < 4; hop++) {
            java.net.HttpURLConnection conn = null;
            try {
                java.net.URL u = new java.net.URL(current);
                conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("Range", "bytes=0-6143");
                if (!referer.isEmpty()) conn.setRequestProperty("Referer", referer);
                if (!origin.isEmpty()) conn.setRequestProperty("Origin", origin);
                if (!ua.isEmpty()) conn.setRequestProperty("User-Agent", ua);
                if (!cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
                if (!auth.isEmpty()) conn.setRequestProperty("Authorization", auth);
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null || loc.isBlank()) return null;
                    current = new java.net.URL(u, loc.trim()).toString();
                    continue;
                }
                if (code < 200 || code >= 300) {
                    conn.disconnect();
                    return null;
                }
                long totalBytes = -1L;
                if (code == 206) {
                    String cr = conn.getHeaderField("Content-Range");
                    if (cr != null) {
                        int slash = cr.lastIndexOf('/');
                        if (slash > 0 && slash < cr.length() - 1) {
                            String tot = cr.substring(slash + 1).trim();
                            if (!"*".equals(tot)) totalBytes = Long.parseLong(tot);
                        }
                    }
                } else {
                    totalBytes = conn.getContentLengthLong();
                }
                var sampleBuf = new java.io.ByteArrayOutputStream();
                long readTotal = 0L;
                try (var in = conn.getInputStream()) {
                    byte[] buf = new byte[2048];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (sampleBuf.size() < 6144) {
                            sampleBuf.write(buf, 0, Math.min(n, 6144 - sampleBuf.size()));
                        }
                        readTotal += n;
                        if (totalBytes > 0 && sampleBuf.size() >= 6144) break;
                        if (readTotal > 2097152) break;
                    }
                }
                conn.disconnect();
                if (totalBytes <= 0L && code == 200 && readTotal > 0L) {
                    totalBytes = readTotal;
                }
                String sample = new String(sampleBuf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                if (isStoryboardVttBody(sample)) {
                    return new SubProbeMeta(-1L, "__storyboard__", false);
                }
                DetectedLang dl = detectSubtitleLanguage(sample);
                return new SubProbeMeta(Math.max(0L, totalBytes), dl.language(), dl.isDual());
            } catch (Exception ignored) {
                if (conn != null) conn.disconnect();
                return null;
            }
        }
        return null;
    }

    private static DetectedLang detectSubtitleLanguage(String sample) {
        if (sample == null || sample.isBlank()) return new DetectedLang("", false);
        var cueLines = new StringBuilder();
        int linesPicked = 0;
        for (String rawLine : sample.split("\\r?\\n")) {
            String t = rawLine.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("WEBVTT") || t.startsWith("NOTE") || t.startsWith("STYLE")
                    || t.startsWith("[Script Info]") || t.startsWith("Format:") || t.startsWith("Style:")
                    || t.startsWith("Kind:") || t.startsWith("Language:")
                    || t.matches("^\\d+$") || t.contains("-->")) {
                continue;
            }
            String clean = t.replaceAll("<[^>]+>", " ").replaceAll("\\{[^}]+\\}", " ").trim();
            if (clean.isEmpty()) continue;
            cueLines.append(' ').append(clean);
            if (++linesPicked >= 8) break;
        }
        String check = cueLines.length() > 0 ? cueLines.toString() : sample;
        boolean hasAr = check.matches("(?s).*[\\u0600-\\u06FF].*");
        boolean hasEn = check.matches("(?s).*[A-Za-z]{2,}.*");
        if (hasAr && hasEn) return new DetectedLang("dual", true);
        if (hasAr) return new DetectedLang("ar", false);
        if (hasEn) return new DetectedLang("en", false);
        return new DetectedLang("", false);
    }

    private String formatSubtitleChipLabel(SubtitleOption sub, int fallbackIndex) {
        String lang = sub.language() == null ? "" : sub.language().trim().toLowerCase(java.util.Locale.ROOT);
        String label = sub.label() == null ? "" : sub.label().trim();
        String name;
        if (sub.isDual() || "dual".equals(lang) || label.toLowerCase(java.util.Locale.ROOT).contains("en & ar")) {
            name = "En & Ar";
        } else if (lang.startsWith("ar")) {
            name = "العربية";
        } else if (lang.startsWith("en")) {
            name = "English";
        } else if (!label.isEmpty()) {
            name = label;
        } else if (!lang.isEmpty()) {
            name = lang.toUpperCase(java.util.Locale.ROOT);
        } else {
            name = host.l("ترجمة " + fallbackIndex, "Subtitle " + fallbackIndex);
        }
        if (sub.sizeBytes() > 0L) {
            return name + " · " + BrowserDownloadManager.formatBytes(sub.sizeBytes());
        }
        return name;
    }

    java.util.List<VideoOption> videoOptions(GeckoSession tab) {
        var entries = videos.get(tab);
        if (entries == null) return java.util.List.of();
        var options = new java.util.ArrayList<VideoOption>();
        var urls = new java.util.HashSet<String>();
        var dimensions = java.util.regex.Pattern.compile("(?i)(?:[0-9]{3,5}x([0-9]{3,4})|(?:^|[^a-z0-9])(2160|1440|1080|720|480|360|240)p(?![a-z0-9]))");
        var fallback = new java.util.ArrayList<VideoOption>();
        for (int i = 0; i < entries.length(); i++) {
            var entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String url = entry.optString("url");
            if (isAdMediaStreamUrl(url)) continue;
            var uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if ((!"https".equals(scheme) && !"http".equals(scheme)) || uri.getHost() == null || uri.getUserInfo() != null
                    || !urls.add(uri.buildUpon().fragment(null).build().toString()
                    + (entry.optBoolean("masterQuality") ? "#" + entry.optInt("height") : ""))) continue;
            String filename = java.util.Objects.toString(uri.getLastPathSegment(), "").toLowerCase(java.util.Locale.ROOT);
            if (filename.endsWith(".ts") || filename.endsWith(".m4s") || filename.endsWith(".aac")) continue;
            int rawW = entry.optInt("width", 0);
            int rawH = entry.optInt("height", 0);
            int height = normalizeResolutionHeight(rawW, rawH);
            int itagH = extractItagHeight(url);
            if (itagH > 0) height = itagH;
            String fullPath = java.util.Objects.toString(uri.getPath(), "");
            var match = dimensions.matcher(filename);
            if (!match.find()) match = dimensions.matcher(fullPath);
            if (match.find() && !entry.optBoolean("masterQuality")) {
                int urlH = Integer.parseInt(match.group(1) != null ? match.group(1) : match.group(2));
                if (urlH > 0) height = normalizeResolutionHeight(0, urlH);
            }
            String kind = entry.optString("format");
            if ("embedded".equals(kind) || "drm".equals(kind)) continue;
            boolean hls = filename.endsWith(".m3u8") || "hls".equals(kind);
            if (hls && (kind == null || kind.isBlank() || "video".equalsIgnoreCase(kind))) {
                kind = "hls";
            }
            boolean adaptive = (hls && !entry.optBoolean("masterQuality")
                    && (filename.contains("master") || entry.optBoolean("manifest"))) || "dash".equals(kind);
            if (adaptive) height = 0;
            String format = hls ? "HLS Stream · M3U8" : filename.endsWith(".mp4") ? "MP4 Direct" : "Direct stream";
            format = switch (kind) {
                case "dash" -> "DASH Stream · MPD";
                case "split" -> "Video + audio";
                case "audio" -> "Audio stream";
                default -> format;
            };
            String audioUrl = entry.optString("audioUrl");
            long sizeBytes = entry.optLong("size", 0L);
            if (sizeBytes <= 0L) sizeBytes = extractClenSize(url);
            if (sizeBytes <= 0L) {
                Long cached = headSizeCache.get(url);
                if (cached != null && cached > 0L) sizeBytes = cached;
            }
            if (sizeBytes > 0L && !audioUrl.isEmpty()) {
                long aClen = extractClenSize(audioUrl);
                if (aClen > 0L) sizeBytes += aClen;
            }
            String pageUrl = java.util.Objects.toString(host.locations.get(tab), "");
            String referer = entry.optString("referer");
            if (referer.isBlank()) referer = pageUrl;
            String origin = entry.optString("origin");
            if (origin.isBlank()) origin = deriveOriginFromReferer(referer);
            String ua = entry.optString("userAgent");
            String cookie = entry.optString("cookie");
            String auth = entry.optString("authorization");
            String itemSubUrl = entry.optString("subtitleUrl");
            SubProbeMeta itemSubMeta = !itemSubUrl.isBlank() ? subProbeCache.get(itemSubUrl) : null;
            if (!itemSubUrl.isBlank() && (isStoryboardOrImageSubtitle(itemSubUrl, "", null)
                    || (itemSubMeta != null && "__storyboard__".equals(itemSubMeta.language())))) {
                itemSubUrl = "";
            }
            var option = new VideoOption(
                    url,
                    cleanVideoTitle(entry.optString("title")),
                    format,
                    height,
                    adaptive,
                    kind,
                    audioUrl,
                    entry.optInt("bitrate"),
                    entry.optInt("fps"),
                    entry.optBoolean("live"),
                    entry.optBoolean("active", false),
                    itemSubUrl,
                    itemSubUrl.isEmpty() ? "" : entry.optString("subtitleMime"),
                    itemSubUrl.isEmpty() ? "" : entry.optString("subtitleLanguage"),
                    entry.optString("subtitleReferer", referer),
                    entry.optString("subtitleOrigin", origin),
                    entry.optString("subtitleUserAgent", ua),
                    entry.optString("subtitleCookie", cookie),
                    entry.optString("subtitleAuthorization", auth),
                    sizeBytes,
                    referer,
                    origin,
                    ua,
                    cookie,
                    auth);
            if (hls && filename.matches(".*(?:-v[0-9]+|-a[0-9]+).*")) fallback.add(option);
            else options.add(option);
        }
        if (options.stream().noneMatch(option -> option.adaptive() || option.height() > 0)) options.addAll(fallback);
        boolean hasHlsMaster = options.stream().anyMatch(option -> "hls".equals(option.kind()) && option.adaptive());
        if (hasHlsMaster) options.removeIf(option -> "hls".equals(option.kind()) && !option.adaptive() && option.height() == 0);
        options.sort(java.util.Comparator.comparing(VideoOption::activeStream).reversed()
                .thenComparing(VideoOption::adaptive)
                .thenComparing(java.util.Comparator.comparingInt(VideoOption::height).reversed())
                .thenComparing(java.util.Comparator.comparingInt(VideoOption::bitrate).reversed()));
        var qualities = new java.util.HashSet<String>();
        options.removeIf(option -> (option.height() > 0 || option.adaptive())
                && !qualities.add(option.kind() + "|" + option.quality()));
        return options;
    }

    static String cleanVideoTitle(String title) {
        if (title == null) return "";
        if (AD_TITLE_SPAM_PATTERN.matcher(title).find()) return "";
        String t = title.replaceAll("\\s+", " ").trim()
                .replaceFirst("(?i)^(?:watch|مشاهدة(?:\\s+و\\s*تحميل)?|تحميل(?:\\s+و\\s*مشاهدة)?|فيلم|مسلسل|انمي|أنمي)\\s+", "")
                .replaceFirst("(?i)\\s*(?:[|–—•·:\\-]\\s*)+(?:shabakaty\\s*)?(?:cinemana|shabakaty|youtube|egybest|faselhd|mycima|wecima|e?\\.?cimalight(?:\\.co)?|arabseed|akoam|شبكتي|سينمانا|سيما\\s*لايت|ايجي\\s*بست|فاصل\\s*إعلاني|ماي\\s*سيما|اون\\s*لاين|أون\\s*لاين).*$", "")
                .replaceFirst("(?i)^(?:shabakaty\\s*)?(?:cinemana|shabakaty|youtube|egybest|faselhd|mycima|wecima|شبكتي|سينمانا)\\s*(?:[|–—•·:\\-]\\s*)+", "")
                .replaceAll("(?i)\\s*\\b(?:watch\\s+online|full\\s+movie|official\\s+trailer|مترجم(?:\\s+أون\\s*لاين|\\s+اون\\s*لاين|\\s+كامل)?|مدبلج|أون\\s*لاين|اون\\s*لاين|اونلاين|HD|FHD|UHD|4K)\\b\\s*$", "")
                .replaceAll("^[\\s\\-–—|:•·]+|[\\s\\-–—|:•·]+$", "")
                .strip();
        String low = t.toLowerCase(java.util.Locale.ROOT);
        if (low.equals("cinemana") || low.equals("shabakaty") || low.equals("youtube")
                || low.equals("shabakaty cinemana") || low.equals("سينمانا") || low.equals("شبكتي")
                || low.equals("video player") || low.equals("player") || low.equals("embed")
                || low.equals("jw player") || low.equals("jwplayer") || low.equals("plyr")
                || low.equals("elif.news") || low.equals("elif news") || low.equals("untitled")
                || low.equals("advertisement") || low.equals("sponsored") || low.equals("home")
                || AD_TITLE_SPAM_PATTERN.matcher(t).find()) {
            return "";
        }
        return t;
    }

    private static boolean isLikelyRandomSlug(String seg) {
        if (seg == null || seg.length() < 3) return true;
        if (seg.matches("(?s).*[\\u0600-\\u06FF]{2,}.*")) return false;
        if (seg.matches("(?i).*\\bS\\d{1,2}E\\d{1,3}\\b.*")) return false;
        if (seg.matches("(?i)^[0-9a-f]{8,}$") || seg.matches("^\\d+$")) return true;
        if (!seg.matches(".*[\\s._\\-].*") && seg.matches(".*[0-9].*") && seg.matches("(?i).*[a-z].*") && seg.length() >= 8) {
            return true;
        }
        String letters = seg.replaceAll("[^A-Za-z]", "");
        if (!seg.matches(".*[\\s._\\-].*") && letters.length() >= 8) {
            int vowels = letters.replaceAll("(?i)[^aeiouy]", "").length();
            if ((vowels * 100) / letters.length() < 18) return true;
        }
        return false;
    }

    static String extractTitleFromUrlHint(String rawUrl, boolean allowPageSlug) {
        if (rawUrl == null || rawUrl.isBlank()) return "";
        try {
            Uri u = Uri.parse(rawUrl.trim());
            for (String key : new String[]{"title", "name", "video_title", "filename", "dn", "movie", "series"}) {
                String val = u.getQueryParameter(key);
                if (val != null && !val.isBlank()) {
                    String cleaned = cleanVideoTitle(val.replaceAll("(?i)\\.(?:m3u8|mp4|mkv|webm|mov|mpd|vtt|srt)$", "")
                            .replaceAll("[._]+", " "));
                    if (!cleaned.isBlank() && !isLikelyRandomSlug(cleaned)) return cleaned;
                }
            }
            var segs = u.getPathSegments();
            if (segs != null) {
                for (int i = segs.size() - 1; i >= 0; i--) {
                    String seg = Uri.decode(segs.get(i));
                    if (seg == null) continue;
                    seg = seg.replaceAll("(?i)\\.(?:m3u8|mp4|mkv|webm|mov|mpd|vtt|srt|html?|php)$", "").trim();
                    if (seg.isEmpty() || GENERIC_MEDIA_SEGMENTS.contains(seg.toLowerCase(java.util.Locale.ROOT))
                            || isLikelyRandomSlug(seg)) {
                        continue;
                    }
                    String humanized = seg.replaceAll("[._\\-]+", " ")
                            .replaceAll("(?i)\\b(?:2160p|1440p|1080p|720p|480p|360p|web[\\s-]?dl|webrip|bluray|bdrip|brrip|hdrip|x264|x265|h264|h265|hevc|aac|ac3|10bit|hls)\\b", " ")
                            .replaceAll("\\s+", " ")
                            .trim();
                    String cleaned = cleanVideoTitle(humanized);
                    if (cleaned.length() >= 3 && (allowPageSlug
                            || cleaned.contains(" ")
                            || cleaned.matches("(?s).*[\\u0600-\\u06FF].*")
                            || cleaned.matches("(?i).*\\bS\\d{1,2}E\\d{1,3}\\b.*"))) {
                        return cleaned;
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    void updateDiscovery() {
        if (host.videoList == null || host.tabsButton == null) return;
        int count = videoOptions(host.active).size() + torrentOptions(host.active).size();
        host.videoList.setEnabled(count > 0);
        host.videoList.setText(count > 0 ? Integer.toString(count) : "");
        host.stylePillIconButton(host.videoList, R.drawable.ic_cinema_cast_play, count > 0);
        if (count <= 0) {
            host.videoList.setIconTint(android.content.res.ColorStateList.valueOf(0xff6f727b));
        }
        boolean anyAudible = false;
        for (var t : host.tabs) {
            if (isTabAudible(t)) {
                anyAudible = true;
                break;
            }
        }
        String tabCount = Integer.toString(Math.max(1, host.tabs.size()));
        host.tabsButton.setText(anyAudible ? tabCount + " 🔊" : tabCount);
    }

    private SubtitleOption resolveEffectivePrimarySub(GeckoSession source, VideoOption option) {
        SubtitleOption manual = selectedPrimarySubs.get(source);
        if (manual == SUBTITLE_OFF) return null;
        if (manual != null && (!manual.url().isBlank() || !manual.content().isBlank())) return manual;
        if (isHardcodedSubtitleSession(source,
                option != null ? option.url() : "",
                option != null ? option.referer() : "")) {
            return null;
        }
        if (option != null && option.subtitleUrl() != null && !option.subtitleUrl().isBlank()) {
            return new SubtitleOption(
                    option.subtitleUrl(), option.subtitleMime(), option.subtitleLanguage(), "",
                    0L, "", false,
                    option.subtitleReferer(), option.subtitleOrigin(), option.subtitleUserAgent(),
                    option.subtitleCookie(), option.subtitleAuthorization());
        }
        var subs = sessionSubtitles.get(source);
        if (subs == null || subs.isEmpty()) return null;
        SubtitleOption best = null;
        int bestScore = -1;
        for (var s : subs) {
            int score = 10;
            String low = (s.language() + " " + s.label()).toLowerCase(java.util.Locale.ROOT);
            if (s.isDual() || "dual".equals(low)) score += 35;
            else if (low.startsWith("ar") || low.contains("عرب")) score += 30;
            else if (low.startsWith("en")) score += 20;
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private SubtitleOption resolveEffectiveSecondarySub(GeckoSession source) {
        SubtitleOption manual = selectedSecondarySubs.get(source);
        if (manual != null) return manual;
        if (isHardcodedSubtitleSession(source, "", "") && selectedPrimarySubs.get(source) == null) {
            return null;
        }
        if (!PhoneModuleBridges.tvHub().isDualSubtitlesEnabled(host.context)) return null;
        var subs = sessionSubtitles.getOrDefault(source, java.util.List.of());
        var options = videoOptions(source);
        var primary = options.isEmpty() ? selectedPrimarySubs.get(source) : resolveEffectivePrimarySub(source, options.get(0));
        if (primary == null || primary == SUBTITLE_OFF || primary.isDual()) return null;
        return subs.stream().filter(sub -> !sub.isDual() && (!sub.url().equals(primary.url())
                        || sub.url().isBlank() && !sub.content().equals(primary.content())))
                .min(java.util.Comparator.comparingInt(sub -> sub.language().equalsIgnoreCase(primary.language()) ? 1 : 0))
                .orElse(null);
    }

    void showVideos() {
        var source = host.active;
        if (source == null) return;
        var options = videoOptions(source);
        var torrents = torrentOptions(source);
        if (options.isEmpty() && torrents.isEmpty()) {
            rescanActiveSession();
            CinemaSnackBanner.show(host.context, host.l("جاري البحث عن روابط الفيديو والتورنت في الصفحة...",
                    "Scanning page for video & magnet links..."));
            return;
        }
        for (var option : options) {
            if (!"hls".equalsIgnoreCase(option.kind()) && !"dash".equalsIgnoreCase(option.kind())
                    && option.sizeBytes() <= 0L && option.url().startsWith("http")) {
                var synthetic = new org.json.JSONObject();
                try {
                    synthetic.put("url", option.url());
                    synthetic.put("referer", option.referer());
                    synthetic.put("origin", option.origin());
                    synthetic.put("userAgent", option.userAgent());
                    synthetic.put("cookie", option.cookie());
                    synthetic.put("authorization", option.authorization());
                    probeDirectStreamSizeIfNeeded(source, synthetic);
                } catch (org.json.JSONException ignored) {}
            }
        }
        String title = resolveBestSessionTitle(source, options, torrents);
        String hostName = Uri.parse(java.util.Objects.toString(host.locations.get(source), "")).getHost();
        var dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(host.context);
        videoSheetDialog = dialog;
        var panel = new LinearLayout(host.context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(host.dp(16), host.dp(10), host.dp(16), host.dp(16));
        var shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(0xff18181b);
        float radius = host.dp(24);
        shape.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        panel.setBackground(shape);
        var grip = new View(host.context);
        grip.setBackground(host.surface(0xff48484e, 3));
        var gripLayout = new LinearLayout.LayoutParams(host.dp(38), host.dp(4));
        gripLayout.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        panel.addView(grip, gripLayout);

        var header = new LinearLayout(host.context);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        var heading = new LinearLayout(host.context);
        heading.setOrientation(LinearLayout.VERTICAL);
        var titleView = host.historyText(title, 18, 0xfffafafa, true);
        titleView.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        heading.addView(titleView);
        if (hostName != null) heading.addView(host.historyText(hostName, 12, 0xffc5a566, false));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

        var refreshBtn = new com.google.android.material.button.MaterialButton(host.context);
        refreshBtn.setAllCaps(false);
        refreshBtn.setText(host.l("تحديث البحث", "Refresh"));
        refreshBtn.setTextSize(12);
        refreshBtn.setTextColor(0xffe5b84b);
        refreshBtn.setCornerRadius(host.dp(14));
        refreshBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff25252b));
        refreshBtn.setStrokeColor(android.content.res.ColorStateList.valueOf(0xffe5b84b));
        refreshBtn.setStrokeWidth(host.dp(1));
        refreshBtn.setIconResource(R.drawable.ic_refresh_24px);
        refreshBtn.setIconTint(android.content.res.ColorStateList.valueOf(0xffe5b84b));
        refreshBtn.setIconSize(host.dp(16));
        refreshBtn.setIconPadding(host.dp(4));
        refreshBtn.setPadding(host.dp(10), 0, host.dp(10), 0);
        refreshBtn.setMinHeight(0);
        refreshBtn.setMinimumHeight(0);
        refreshBtn.setOnClickListener(v -> {
            if (videoSheetStatus != null) {
                videoSheetStatus.setVisibility(View.VISIBLE);
                videoSheetStatus.setText(host.l("جارٍ إعادة فحص جودات البث بالصفحة...",
                        "Rescanning active page for updated stream qualities..."));
            }
            rescanActiveSession();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (videoSheetDialog == dialog && dialog.isShowing()) {
                    renderVideoSheetCards(source);
                    if (videoSheetStatus != null) {
                        int foundTotal = videoOptions(source).size() + torrentOptions(source).size();
                        videoSheetStatus.setText(host.l("تم تحديث الروابط (" + foundTotal + ")",
                                "Updated (" + foundTotal + " links)"));
                    }
                }
            }, 900);
        });
        var refLp = new LinearLayout.LayoutParams(-2, host.dp(36));
        refLp.setMarginEnd(host.dp(4));
        header.addView(refreshBtn, refLp);

        var close = host.historyAction("×", 0xfffafafa, dialog::dismiss);
        close.setContentDescription(close.getContext().getString(org.proninyaroslav.libretorrent.R.string.cinema_browser_close_videos));
        header.addView(close, new LinearLayout.LayoutParams(host.dp(44), host.dp(44)));
        panel.addView(header);

        videoSheetStatus = host.historyText("", 11, 0xffc5a566, false);
        videoSheetStatus.setPadding(0, host.dp(4), 0, host.dp(4));
        videoSheetStatus.setVisibility(View.GONE);
        panel.addView(videoSheetStatus);

        videoSheetSubSummary = host.historyText("", 12, 0xffe5b84b, false);
        videoSheetSubSummary.setBackground(host.surface(0xff232329, 10));
        videoSheetSubSummary.setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(8));
        videoSheetSubSummary.setVisibility(View.GONE);
        videoSheetSubSummary.setOnClickListener(v -> showDualSubtitlePickerDialog(source));
        var subSumLp = new LinearLayout.LayoutParams(-1, -2);
        subSumLp.topMargin = host.dp(4);
        subSumLp.bottomMargin = host.dp(2);
        panel.addView(videoSheetSubSummary, subSumLp);

        var scroll = new androidx.core.widget.NestedScrollView(host.context);
        videoSheetScroll = scroll;
        scroll.setNestedScrollingEnabled(true);
        scroll.setFillViewport(false);
        var cards = new LinearLayout(host.context);
        cards.setOrientation(LinearLayout.VERTICAL);
        videoSheetCards = cards;
        videoSheetHolders.clear();
        renderedSheetSignature = "";
        scroll.addView(cards);
        scroll.setOnTouchListener((v, ev) -> {
            if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
            dialog.getBehavior().setDraggable(false);
            return false;
        });
        header.setOnTouchListener((v, ev) -> {
            dialog.getBehavior().setDraggable(videoSheetScroll == null || !videoSheetScroll.canScrollVertically(-1));
            return false;
        });
        panel.setOnTouchListener((v, ev) -> {
            if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
        panel.addView(scroll, new LinearLayout.LayoutParams(-1,
                Math.min(host.dp(Math.max(3, options.size() + torrents.size()) * 92),
                        (int) (host.context.getResources().getDisplayMetrics().heightPixels * 0.62f))));

        dialog.setOnDismissListener(d -> {
            if (videoSheetDialog == dialog) {
                videoSheetDialog = null;
                videoSheetCards = null;
                videoSheetStatus = null;
                videoSheetSubSummary = null;
                videoSheetScroll = null;
                videoSheetHolders.clear();
                renderedSheetSignature = "";
            }
            host.resumeBrowserRendering();
        });
        renderVideoSheetCards(source);
        dialog.setContentView(panel);
        dialog.show();
        if (panel.getParent() instanceof View parent) parent.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setDraggable(false);
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
    }

    private void updateSubtitleSummaryView(GeckoSession source, java.util.List<VideoOption> options) {
        if (videoSheetSubSummary == null) return;
        var subs = sessionSubtitles.getOrDefault(source, java.util.List.of());
        VideoOption sampleOpt = options.isEmpty() ? null : options.get(0);
        SubtitleOption primary = sampleOpt != null ? resolveEffectivePrimarySub(source, sampleOpt) : null;
        SubtitleOption secondary = resolveEffectiveSecondarySub(source);
        if (subs.isEmpty() && primary == null && secondary == null) {
            videoSheetSubSummary.setVisibility(View.GONE);
            return;
        }
        StringBuilder sb = new StringBuilder("CC · ");
        if (primary == null) {
            if (!subs.isEmpty() && isHardcodedSubtitleSession(source, "", "")) {
                sb.append(host.l("متوقفة تلقائياً لمنع التكرار (اضغط للتفعيل يدوياً)",
                        "Auto-Off: Burned-in subs (Tap to enable manually)"));
            } else {
                sb.append(host.l("بدون ترجمة أساسية", "Primary: Off"));
            }
        } else {
            int pIdx = Math.max(1, subs.indexOf(primary) + 1);
            sb.append(formatSubtitleChipLabel(primary, pIdx));
        }
        if (secondary != null) {
            int sIdx = Math.max(1, subs.indexOf(secondary) + 1);
            sb.append(" + ").append(formatSubtitleChipLabel(secondary, sIdx));
        } else if (subs.size() >= 2) {
            sb.append("  (").append(host.l("اضغط لاختيار ترجمة مزدوجة", "Tap for Dual Subtitles")).append(")");
        }
        videoSheetSubSummary.setText(sb.toString());
        videoSheetSubSummary.setVisibility(View.VISIBLE);
    }

    private void renderVideoSheetCards(GeckoSession source) {
        if (videoSheetCards == null || source == null) return;
        var options = videoOptions(source);
        var torrents = torrentOptions(source);
        updateSubtitleSummaryView(source, options);
        var detectedSubs = sessionSubtitles.getOrDefault(source, java.util.List.of());
        String title = resolveBestSessionTitle(source, options, torrents);

        StringBuilder sigBuilder = new StringBuilder();
        for (var o : options) {
            boolean hasDl = isOptionDownloadable(o);
            sigBuilder.append(o.activeStream()).append('|').append(o.kind()).append('|')
                    .append(o.height()).append('|').append(hasDl).append('|').append(o.url()).append('\n');
        }
        for (var t : torrents) {
            sigBuilder.append("tor|").append(t.key()).append('|').append(t.quality()).append('|')
                    .append(t.sizeText()).append('|').append(t.seeds()).append('|').append(t.uri()).append('\n');
        }
        String newSig = sigBuilder.toString();
        if (torrents.isEmpty()
                && newSig.equals(renderedSheetSignature)
                && videoSheetHolders.size() == options.size()
                && videoSheetCards.getChildCount() == options.size()) {
            for (int i = 0; i < options.size(); i++) {
                bindSheetCard(videoSheetHolders.get(i), options.get(i), source, title, detectedSubs);
            }
            return;
        }

        int savedScrollY = videoSheetScroll != null ? videoSheetScroll.getScrollY() : 0;
        videoSheetCards.removeAllViews();
        videoSheetHolders.clear();
        renderedSheetSignature = newSig;

        if (!options.isEmpty() && !torrents.isEmpty()) {
            var vidHdr = host.historyText(
                    host.l("روابط الفيديو المباشرة (" + options.size() + ")", "Direct Video Streams (" + options.size() + ")"),
                    12.5f, 0xffe5b84b, true);
            vidHdr.setPadding(host.dp(2), host.dp(6), host.dp(2), host.dp(2));
            videoSheetCards.addView(vidHdr);
        }

        for (var option : options) {
            var card = new LinearLayout(host.context);
            card.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            card.setGravity(android.view.Gravity.CENTER_VERTICAL);
            card.setBackground(host.surface(option.activeStream() ? 0xff222824 : 0xff222226, 14));
            card.setPadding(host.dp(12), host.dp(12), host.dp(12), host.dp(12));

            var badgeCol = new LinearLayout(host.context);
            badgeCol.setOrientation(LinearLayout.VERTICAL);
            var badge = host.historyText(option.quality(), 13, 0xff18181b, true);
            badge.setBackground(host.surface(0xffe5b84b, 8));
            badge.setPadding(host.dp(8), host.dp(4), host.dp(8), host.dp(4));
            badgeCol.addView(badge, new LinearLayout.LayoutParams(-2, -2));
            if (option.activeStream()) {
                var activePill = host.historyText(host.l("قيد التشغيل الآن", "Active"), 10, 0xff09090b, true);
                activePill.setBackground(host.surface(0xff4ade80, 6));
                activePill.setPadding(host.dp(6), host.dp(2), host.dp(6), host.dp(2));
                var apLp = new LinearLayout.LayoutParams(-2, -2);
                apLp.topMargin = host.dp(4);
                badgeCol.addView(activePill, apLp);
            }
            card.addView(badgeCol, new LinearLayout.LayoutParams(-2, -2));

            var format = host.historyText("", 13, 0xffededee, false);
            format.setPadding(host.dp(10), 0, host.dp(6), 0);
            card.addView(format, new LinearLayout.LayoutParams(0, -2, 1));

            View dlBtn = null;
            if (isOptionDownloadable(option)) {
                var dlIcon = new android.widget.ImageView(host.context);
                dlIcon.setImageResource(R.drawable.ic_cinema_dl);
                dlIcon.setImageTintList(android.content.res.ColorStateList.valueOf(0xffe5a93b));
                dlIcon.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
                int padH = host.dp(7);
                int padV = host.dp(6);
                dlIcon.setPadding(padH, padV, padH, padV);
                var dlBg = host.surface(0xff2a2417, 9);
                dlBg.setStroke(Math.max(1, host.dp(1)), 0x77e5a93b);
                dlIcon.setBackground(new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x33e5a93b), dlBg, host.surface(0xffffffff, 9)));
                dlIcon.setContentDescription(host.l("تحميل الفيديو", "Download video"));
                dlIcon.setFocusable(true);
                dlIcon.setClickable(true);
                var dlLp = new LinearLayout.LayoutParams(host.dp(34), host.dp(32));
                dlLp.setMarginEnd(host.dp(8));
                card.addView(dlIcon, dlLp);
                dlBtn = dlIcon;
            }

            var subBtn = host.historyText("CC", 11, 0xff8c8c96, true);
            subBtn.setPadding(host.dp(9), host.dp(7), host.dp(9), host.dp(7));
            subBtn.setContentDescription(host.l("خيارات الترجمة", "Subtitle options"));
            var sbLp = new LinearLayout.LayoutParams(-2, -2);
            sbLp.setMarginEnd(host.dp(8));
            card.addView(subBtn, sbLp);

            card.addView(host.historyText("▶", 17, 0xffe5b84b, true));
            card.setContentDescription(option.quality() + ", " + option.format() + ", Cast to TV");
            card.setFocusable(true);

            var holder = new SheetCardHolder(card, format, subBtn, dlBtn);
            bindSheetCard(holder, option, source, title, detectedSubs);
            videoSheetHolders.add(holder);

            var row = new LinearLayout.LayoutParams(-1, -2);
            row.topMargin = host.dp(8);
            videoSheetCards.addView(card, row);
        }
        if (!torrents.isEmpty()) {
            var torHdr = host.historyText(
                    host.l("روابط التورنت والمغناطيس (" + torrents.size() + ")", "Torrents & Magnet Links (" + torrents.size() + ")"),
                    12.5f, 0xffe5b84b, true);
            torHdr.setPadding(host.dp(2), host.dp(options.isEmpty() ? 4 : 12), host.dp(2), host.dp(2));
            videoSheetCards.addView(torHdr);
            for (var tor : torrents) {
                videoSheetCards.addView(buildTorrentGrabCard(tor, title));
            }
        }
        if (videoSheetScroll != null && savedScrollY > 0) {
            final int targetY = savedScrollY;
            videoSheetScroll.post(() -> {
                if (videoSheetScroll != null) videoSheetScroll.scrollTo(0, targetY);
            });
        }
    }

    private View buildTorrentGrabCard(TorrentGrabOption tor, String fallbackTitle) {
        var card = new LinearLayout(host.context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(host.isArabic() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        var cardBg = host.surface(0xff202127, 14);
        cardBg.setStroke(Math.max(1, host.dp(1)), 0xff493d2b);
        card.setBackground(cardBg);
        card.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10));

        var topRow = new LinearLayout(host.context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        String badgeLabel = (tor.quality() != null && !tor.quality().isBlank())
                ? tor.quality()
                : ("magnet".equals(tor.kind()) ? "MAGNET" : "TORRENT");
        var badge = host.historyText(badgeLabel, 11.5f, 0xff18181b, true);
        badge.setBackground(host.surface(0xffe5b84b, 8));
        badge.setPadding(host.dp(8), host.dp(3), host.dp(8), host.dp(3));
        topRow.addView(badge, new LinearLayout.LayoutParams(-2, -2));

        String kindChipText = "magnet".equals(tor.kind()) ? "🧲 Magnet" : "📦 .torrent";
        var kindChip = host.historyText(kindChipText, 11f, 0xff93c5fd, true);
        kindChip.setBackground(host.surface(0xff1e293b, 7));
        kindChip.setPadding(host.dp(7), host.dp(3), host.dp(7), host.dp(3));
        var kLp = new LinearLayout.LayoutParams(-2, -2);
        kLp.setMarginStart(host.dp(6));
        topRow.addView(kindChip, kLp);

        if (tor.seeds() > 0) {
            var seedChip = host.historyText("▲ " + tor.seeds(), 11f, 0xff6ee7b7, true);
            seedChip.setBackground(host.surface(0xff143126, 7));
            seedChip.setPadding(host.dp(7), host.dp(3), host.dp(7), host.dp(3));
            var sLp = new LinearLayout.LayoutParams(-2, -2);
            sLp.setMarginStart(host.dp(6));
            topRow.addView(seedChip, sLp);
        }

        String sizeInfo = !tor.sizeText().isBlank() ? tor.sizeText()
                : (tor.sizeBytes() > 0 ? BrowserDownloadManager.formatBytes(tor.sizeBytes()) : "");
        if (!sizeInfo.isEmpty()) {
            topRow.addView(new View(host.context), new LinearLayout.LayoutParams(0, 1, 1f));
            var sizeView = host.historyText(sizeInfo, 11.5f, 0xffd4cfc7, true);
            topRow.addView(sizeView, new LinearLayout.LayoutParams(-2, -2));
        }
        card.addView(topRow, new LinearLayout.LayoutParams(-1, -2));

        String itemTitle = !tor.title().isBlank() ? tor.title() : fallbackTitle;
        var titleTv = host.historyText(itemTitle, 13f, 0xfff4f4f6, false);
        titleTv.setMaxLines(2);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleTv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        var tLp = new LinearLayout.LayoutParams(-1, -2);
        tLp.topMargin = host.dp(6);
        card.addView(titleTv, tLp);

        var actionsRow = new LinearLayout(host.context);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        var actLp = new LinearLayout.LayoutParams(-1, -2);
        actLp.topMargin = host.dp(8);

        var dlBtn = new com.google.android.material.button.MaterialButton(
                host.context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        dlBtn.setAllCaps(false);
        dlBtn.setSingleLine(true);
        dlBtn.setText("⬇ " + host.l("تحميل مباشر", "Download"));
        dlBtn.setTextSize(12.5f);
        dlBtn.setTextColor(0xfff8e5b0);
        dlBtn.setCornerRadius(host.dp(11));
        dlBtn.setStrokeColor(android.content.res.ColorStateList.valueOf(0xffe5b84b));
        dlBtn.setStrokeWidth(Math.max(1, host.dp(1)));
        dlBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff26231c));
        dlBtn.setInsetTop(0);
        dlBtn.setInsetBottom(0);
        dlBtn.setMinHeight(host.dp(38));
        dlBtn.setMinimumHeight(host.dp(38));
        dlBtn.setOnClickListener(v -> {
            if (videoSheetDialog != null) videoSheetDialog.dismiss();
            host.downloadCapturedTorrent(tor.magnetUri(), tor.torrentUrl(), tor.referer(), tor.userAgent(), tor.cookie());
        });
        actionsRow.addView(dlBtn, new LinearLayout.LayoutParams(0, host.dp(38), 1f));

        var tvBtn = new com.google.android.material.button.MaterialButton(host.context);
        tvBtn.setAllCaps(false);
        tvBtn.setSingleLine(true);
        tvBtn.setText("▶ " + host.l("إرسال للتلفاز", "Send to TV"));
        tvBtn.setTextSize(12.5f);
        tvBtn.setTextColor(0xff171717);
        tvBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvBtn.setCornerRadius(host.dp(11));
        tvBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffe5b84b));
        tvBtn.setInsetTop(0);
        tvBtn.setInsetBottom(0);
        tvBtn.setMinHeight(host.dp(38));
        tvBtn.setMinimumHeight(host.dp(38));
        tvBtn.setOnClickListener(v -> {
            if (videoSheetDialog != null) videoSheetDialog.dismiss();
            PhoneModuleBridges.tvHub().castCapturedTorrent(
                    host.context, tor.magnetUri(), tor.torrentUrl(), itemTitle,
                    tor.referer(), tor.userAgent(), tor.cookie());
        });
        var tvLp = new LinearLayout.LayoutParams(0, host.dp(38), 1f);
        tvLp.setMarginStart(host.dp(8));
        actionsRow.addView(tvBtn, tvLp);

        card.addView(actionsRow, actLp);
        card.setOnLongClickListener(v -> {
            host.context.getSystemService(android.content.ClipboardManager.class)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Torrent link", tor.uri()));
            CinemaSnackBanner.show(host.context, host.l("تم نسخ رابط المغناطيس/التورنت ✓", "Copied magnet/torrent link ✓"));
            return true;
        });

        var lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = host.dp(8);
        card.setLayoutParams(lp);
        return card;
    }

    private void bindSheetCard(SheetCardHolder holder, VideoOption option, GeckoSession source,
                               String title, java.util.List<SubtitleOption> detectedSubs) {
        String sizePart = option.sizeBytes() > 0 ? " · " + BrowserDownloadManager.formatBytes(option.sizeBytes()) : "";
        String brPart = option.bitrate() > 0 ? " · " + option.bitrate() / 1000 + " kbps" : "";
        String nextText = option.format() + brPart + sizePart;
        if (!nextText.contentEquals(holder.formatView().getText())) {
            holder.formatView().setText(nextText);
        }
        if (holder.dlBtn() != null) {
            holder.dlBtn().setOnClickListener(v -> {
                if (videoSheetDialog != null) videoSheetDialog.dismiss();
                host.enqueueVideoOptionDownload(option, title);
            });
        }
        SubtitleOption primarySub = resolveEffectivePrimarySub(source, option);
        SubtitleOption secondarySub = resolveEffectiveSecondarySub(source);
        boolean hasSub = !detectedSubs.isEmpty() || primarySub != null || secondarySub != null;
        int ccCount = detectedSubs.size();
        String ccText = secondarySub != null ? "CC ×2" : (ccCount > 1 ? "CC · " + ccCount : "CC");
        if (!ccText.contentEquals(holder.subBtn().getText())) {
            holder.subBtn().setText(ccText);
        }
        holder.subBtn().setTextColor(hasSub ? 0xffe5b84b : 0xff8c8c96);
        holder.subBtn().setBackground(host.surface(secondarySub != null ? 0xff3b3218 : (hasSub ? 0xff2c291f : 0xff2b2b31), 8));
        holder.subBtn().setOnClickListener(v -> showDualSubtitlePickerDialog(source));

        holder.card().setOnClickListener(view -> {
            if (videoSheetDialog != null) videoSheetDialog.dismiss();
            dispatchBrowserCast(source, option, title, detectedSubs);
        });
        holder.card().setOnLongClickListener(view -> {
            host.context.getSystemService(android.content.ClipboardManager.class)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Video link", option.url()));
            CinemaSnackBanner.show(host.context, host.l("تم نسخ رابط البث ✓", "Copied stream URL ✓"));
            return true;
        });
    }

    private static String canonicalStreamKey(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return "";
        try {
            Uri u = Uri.parse(rawUrl.trim());
            return u.buildUpon().fragment(null).build().toString();
        } catch (Exception ignored) {
            return rawUrl.trim();
        }
    }

    private String resolveBestSessionTitle(GeckoSession source, java.util.List<VideoOption> options) {
        return resolveBestSessionTitle(source, options, torrentOptions(source));
    }

    private String resolveBestSessionTitle(GeckoSession source,
                                           java.util.List<VideoOption> options,
                                           java.util.List<TorrentGrabOption> torrents) {
        String pageUrl = source != null ? java.util.Objects.toString(host.locations.get(source), "") : "";
        boolean cloakedHost = isCloakedWrapperUrl(pageUrl);
        String cleanDomTitle = sessionCleanTitles.getOrDefault(source, "");
        if (!cleanDomTitle.isBlank()) return cleanDomTitle;
        for (var opt : options) {
            String t = cleanVideoTitle(opt.title());
            if (!t.isBlank()) return t;
        }
        if (torrents != null) {
            for (var tor : torrents) {
                String t = cleanVideoTitle(tor.title());
                if (!t.isBlank()) return t;
            }
        }
        if (!cloakedHost) {
            int tabIdx = host.tabs.indexOf(source);
            String tabTitle = tabIdx >= 0 ? cleanVideoTitle(host.titles.get(tabIdx)) : "";
            if (!tabTitle.isBlank()) return tabTitle;
        }
        for (var opt : options) {
            String fromStream = extractTitleFromUrlHint(opt.url(), false);
            if (!fromStream.isBlank()) return fromStream;
            if (!isCloakedWrapperUrl(opt.referer())) {
                String fromRef = extractTitleFromUrlHint(opt.referer(), false);
                if (!fromRef.isBlank()) return fromRef;
            }
        }
        var subs = sessionSubtitles.getOrDefault(source, java.util.List.of());
        for (var sub : subs) {
            String fromSub = extractTitleFromUrlHint(sub.url(), false);
            if (!fromSub.isBlank()) return fromSub;
        }
        String openerTitle = host.getOpenerCleanTitle(source);
        if (!openerTitle.isBlank()) return openerTitle;
        if (cloakedHost) {
            String prevVerified = lastVerifiedSessionTitles.getOrDefault(source, "");
            if (!prevVerified.isBlank()) return prevVerified;
        }
        if (!cloakedHost) {
            String fromPage = extractTitleFromUrlHint(pageUrl, true);
            if (!fromPage.isBlank()) return fromPage;
        }
        String prevAny = lastVerifiedSessionTitles.getOrDefault(source, "");
        if (!prevAny.isBlank()) return prevAny;
        return host.context.getString(R.string.cinema_browser_video);
    }

    private void maybeAutoSwitchPostPrerollCast(GeckoSession session) {
        if (session == null || session != lastCastSession || lastCastCanonicalUrl.isEmpty()) return;
        long elapsed = android.os.SystemClock.elapsedRealtime() - lastCastTimestampMs;
        if (elapsed < 1200L || elapsed > 4 * 60_000L) return;
        var options = videoOptions(session);
        if (options.isEmpty()) return;
        boolean previousStillPresent = options.stream()
                .anyMatch(o -> canonicalStreamKey(o.url()).equals(lastCastCanonicalUrl));
        VideoOption activeMain = options.stream()
                .filter(o -> o.activeStream() && !"audio".equals(o.kind()))
                .findFirst().orElse(null);
        if (activeMain == null) return;
        String nextKey = canonicalStreamKey(activeMain.url());
        if (nextKey.isEmpty() || nextKey.equals(lastCastCanonicalUrl)) return;
        // Auto-promote when the initial cast clip was a pre-roll that finished/disappeared,
        // or when a short progressive pre-roll transitions to the real HLS/DASH/main feature stream.
        boolean upgradedFromShortClip = !previousStillPresent
                || (!lastCastWasAdaptive && ("hls".equals(activeMain.kind()) || "dash".equals(activeMain.kind())
                        || activeMain.sizeBytes() >= 25L * 1024L * 1024L));
        if (!upgradedFromShortClip) return;
        var detectedSubs = sessionSubtitles.getOrDefault(session, java.util.List.of());
        String resolvedTitle = resolveBestSessionTitle(session, options);
        dispatchBrowserCast(session, activeMain, resolvedTitle, detectedSubs);
    }

    private void dispatchBrowserCast(GeckoSession source, VideoOption option,
                                     String title, java.util.List<SubtitleOption> detectedSubs) {
        lastCastSession = source;
        lastBroadcastTitle = title;
        lastCastCanonicalUrl = canonicalStreamKey(option.url());
        lastCastWasAdaptive = "hls".equals(option.kind()) || "dash".equals(option.kind()) || option.adaptive();
        lastCastTimestampMs = android.os.SystemClock.elapsedRealtime();

        SubtitleOption pSub = resolveEffectivePrimarySub(source, option);
        SubtitleOption sSub = resolveEffectiveSecondarySub(source);
        var catalog = new org.json.JSONArray();
        for (int si = 0; si < detectedSubs.size(); si++) {
            var subOpt = detectedSubs.get(si);
            if (isStoryboardOrImageSubtitle(subOpt.url(), subOpt.label(), subOpt.content())) continue;
            try {
                String memPath = (subOpt.content() != null && !subOpt.content().isBlank())
                        ? PhoneModuleBridges.tvHub().registerInMemorySubtitle(subOpt.content(), subOpt.mime()) : "";
                catalog.put(new org.json.JSONObject()
                        .put("url", subOpt.url())
                        .put("memPath", memPath)
                        .put("mime", subOpt.mime())
                        .put("language", subOpt.language())
                        .put("label", formatSubtitleChipLabel(subOpt, si + 1))
                        .put("isDual", subOpt.isDual())
                        .put("referer", subOpt.referer())
                        .put("origin", subOpt.origin().isBlank() ? deriveOriginFromReferer(subOpt.referer()) : subOpt.origin())
                        .put("userAgent", subOpt.userAgent())
                        .put("cookie", subOpt.cookie())
                        .put("authorization", subOpt.authorization()));
            } catch (org.json.JSONException ignored) {}
        }
        String effectiveOrigin = option.origin().isBlank() ? deriveOriginFromReferer(option.referer()) : option.origin();
        PhoneModuleBridges.tvHub().castBrowserMedia(host.context, new PhoneModuleBridges.BrowserCastPayload(
                option.url(),
                option.audioUrl(),
                option.kind(),
                title,
                "hls".equals(option.kind()) ? option.height() : 0,
                option.live(),
                option.referer(),
                effectiveOrigin,
                option.userAgent(),
                option.cookie(),
                option.authorization(),
                pSub != null ? pSub.url() : "",
                pSub != null ? pSub.mime() : "",
                pSub != null ? pSub.language() : "",
                pSub != null ? pSub.content() : "",
                pSub != null ? pSub.referer() : "",
                pSub != null ? (pSub.origin().isBlank() ? deriveOriginFromReferer(pSub.referer()) : pSub.origin()) : "",
                pSub != null ? pSub.userAgent() : "",
                pSub != null ? pSub.cookie() : "",
                pSub != null ? pSub.authorization() : "",
                sSub != null ? sSub.url() : "",
                sSub != null ? sSub.mime() : "",
                sSub != null ? sSub.language() : "",
                sSub != null ? sSub.content() : "",
                sSub != null ? sSub.referer() : "",
                sSub != null ? (sSub.origin().isBlank() ? deriveOriginFromReferer(sSub.referer()) : sSub.origin()) : "",
                sSub != null ? sSub.userAgent() : "",
                sSub != null ? sSub.cookie() : "",
                sSub != null ? sSub.authorization() : "",
                catalog.toString(),
                pSub == null && isHardcodedSubtitleSession(source, option.url(), option.referer()),
                () -> pauseCastSource(source)));
    }

    private void showDualSubtitlePickerDialog(GeckoSession source) {
        if (source == null) return;
        var subs = sessionSubtitles.getOrDefault(source, java.util.List.of());
        var root = new LinearLayout(host.context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(host.dp(18), host.dp(14), host.dp(18), host.dp(10));

        if (subs.isEmpty()) {
            var emptyNote = host.historyText(
                    host.l("لم تُلتقط ترجمات خارجية من الصفحة أو الكاش بعد. يمكنك تشغيل الفيديو أو الضغط على إعادة الفحص.",
                            "No external subtitle files captured from page or cache yet."),
                    13, 0xffd0d0d5, false);
            root.addView(emptyNote);
            CinemaBrowser.styleDarkDialog(new AlertDialog.Builder(host.context)
                    .setTitle(host.l("إعدادات الترجمة (CC)", "Subtitles (CC)"))
                    .setView(root)
                    .setNeutralButton(host.l("إعادة فحص", "Rescan"), (d, w) -> {
                        rescanActiveSession();
                        CinemaSnackBanner.show(host.context, host.l("جارٍ البحث عن الترجمات...", "Scanning for subtitles..."));
                    })
                    .setPositiveButton(android.R.string.ok, null)
                    .show());
            return;
        }

        final SubtitleOption[] pickedPrimary = new SubtitleOption[]{selectedPrimarySubs.get(source)};
        if (pickedPrimary[0] == null && !subs.isEmpty()) {
            if (isHardcodedSubtitleSession(source, "", "")) {
                pickedPrimary[0] = SUBTITLE_OFF;
            } else {
                SubtitleOption best = null;
                int bestScore = -1;
                for (var s : subs) {
                    int score = 10;
                    String low = (s.language() + " " + s.label()).toLowerCase(java.util.Locale.ROOT);
                    if (s.isDual() || "dual".equals(low)) score += 35;
                    else if (low.startsWith("ar") || low.contains("عرب")) score += 30;
                    else if (low.startsWith("en")) score += 20;
                    if (score > bestScore) {
                        bestScore = score;
                        best = s;
                    }
                }
                pickedPrimary[0] = best;
            }
        }
        final SubtitleOption[] pickedSecondary = new SubtitleOption[]{selectedSecondarySubs.get(source)};

        var scroll = new android.widget.ScrollView(host.context);
        var content = new LinearLayout(host.context);
        content.setOrientation(LinearLayout.VERTICAL);

        content.addView(host.historyText(host.l("1. الترجمة الأساسية (أسفل الشاشة)", "1. Primary Subtitle (Bottom)"),
                14, 0xffe5b84b, true));
        var primaryGroup = new android.widget.RadioGroup(host.context);
        primaryGroup.setPadding(0, host.dp(4), 0, host.dp(10));
        var offRadio = new android.widget.RadioButton(host.context);
        offRadio.setId(View.generateViewId());
        offRadio.setText(host.l("بدون ترجمة", "Off (No subtitle)"));
        offRadio.setTextColor(0xfffafafa);
        offRadio.setChecked(pickedPrimary[0] == SUBTITLE_OFF);
        primaryGroup.addView(offRadio);

        var primaryBtnMap = new java.util.HashMap<Integer, SubtitleOption>();
        primaryBtnMap.put(offRadio.getId(), SUBTITLE_OFF);
        for (int i = 0; i < subs.size(); i++) {
            var s = subs.get(i);
            var rb = new android.widget.RadioButton(host.context);
            rb.setId(View.generateViewId());
            String txt = formatSubtitleChipLabel(s, i + 1) + (s.isDual() ? host.l(" [مزدوجة جاهزة]", " [Dual]") : "");
            rb.setText(txt);
            rb.setTextColor(0xfffafafa);
            rb.setSingleLine(true);
            rb.setEllipsize(android.text.TextUtils.TruncateAt.END);
            rb.setChecked(pickedPrimary[0] != SUBTITLE_OFF && pickedPrimary[0] != null
                    && java.util.Objects.equals(pickedPrimary[0].url(), s.url())
                    && java.util.Objects.equals(pickedPrimary[0].content(), s.content()));
            primaryGroup.addView(rb);
            primaryBtnMap.put(rb.getId(), s);
        }
        primaryGroup.setOnCheckedChangeListener((g, id) -> pickedPrimary[0] = primaryBtnMap.get(id));
        content.addView(primaryGroup);

        content.addView(host.historyText(host.l("2. الترجمة الثانوية (اختيارية للترجمة المزدوجة)", "2. Secondary Subtitle (Optional Dual Mode)"),
                14, 0xff93c5fd, true));
        var secGroup = new android.widget.RadioGroup(host.context);
        secGroup.setPadding(0, host.dp(4), 0, host.dp(6));
        var noneSecRadio = new android.widget.RadioButton(host.context);
        noneSecRadio.setId(View.generateViewId());
        noneSecRadio.setText(host.l("بدون ترجمة ثانوية", "None (Single subtitle only)"));
        noneSecRadio.setTextColor(0xfffafafa);
        noneSecRadio.setChecked(pickedSecondary[0] == null);
        secGroup.addView(noneSecRadio);

        var secBtnMap = new java.util.HashMap<Integer, SubtitleOption>();
        secBtnMap.put(noneSecRadio.getId(), null);
        for (int i = 0; i < subs.size(); i++) {
            var s = subs.get(i);
            var rb = new android.widget.RadioButton(host.context);
            rb.setId(View.generateViewId());
            rb.setText(formatSubtitleChipLabel(s, i + 1));
            rb.setTextColor(0xfffafafa);
            rb.setSingleLine(true);
            rb.setEllipsize(android.text.TextUtils.TruncateAt.END);
            rb.setChecked(pickedSecondary[0] != null
                    && java.util.Objects.equals(pickedSecondary[0].url(), s.url())
                    && java.util.Objects.equals(pickedSecondary[0].content(), s.content()));
            secGroup.addView(rb);
            secBtnMap.put(rb.getId(), s);
        }
        secGroup.setOnCheckedChangeListener((g, id) -> pickedSecondary[0] = secBtnMap.get(id));
        content.addView(secGroup);

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, host.dp(320)));

        var builder = new AlertDialog.Builder(host.context)
                .setTitle(host.l("اختيار الترجمة (ترجمة مفردة / مزدوجة)", "Choose Subtitles (Single / Dual)"))
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(host.l("تطبيق", "Apply"), (d, w) -> {
                    if (pickedPrimary[0] == SUBTITLE_OFF) selectedPrimarySubs.put(source, SUBTITLE_OFF);
                    else if (pickedPrimary[0] != null) selectedPrimarySubs.put(source, pickedPrimary[0]);
                    else selectedPrimarySubs.remove(source);

                    if (pickedSecondary[0] != null && pickedSecondary[0] != pickedPrimary[0]) {
                        selectedSecondarySubs.put(source, pickedSecondary[0]);
                    } else {
                        selectedSecondarySubs.remove(source);
                    }
                    renderVideoSheetCards(source);
                });

        boolean enabled = PhoneModuleBridges.tvHub().isDualSubtitlesEnabled(host.context);
        builder.setNeutralButton(enabled ? host.l("مزدوجة: مفعّلة ✓", "Dual Subs: ON ✓")
                        : host.l("تفعيل الترجمة المزدوجة", "Enable Dual Subs"), (dialog, which) -> {
            PhoneModuleBridges.tvHub().setDualSubtitlesEnabled(host.context, !enabled);
            if (!enabled && pickedPrimary[0] != null && pickedPrimary[0] != SUBTITLE_OFF) {
                selectedPrimarySubs.put(source, pickedPrimary[0]);
            }
            if (enabled) selectedSecondarySubs.remove(source);
            renderVideoSheetCards(source);
        });
        var dialog = CinemaBrowser.styleDarkDialog(builder.show());
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(
                Math.round(host.context.getResources().getDisplayMetrics().widthPixels * 0.96f), -2);
    }
}

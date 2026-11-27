package org.proninyaroslav.libretorrent.cinema_hub;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Neutral v2-ready boundary contracts decoupling the three Phone software modules:
 * 1) Browser ({@code CinemaBrowser*}, {@code Browser*}, {@code SiteFaviconManager})
 * 2) TV Hub ({@code TvHub*}, servers, subtitles, library, music, floating remote, backup, cache)
 * 3) Host Shell & Optional Download Engine ({@code LibreTorrent} in v1; standalone shell in v2)
 *
 * Neither Browser nor TV Hub imports any LibreTorrent class; cross-module calls route strictly
 * through these ports registered at application startup.
 */
public final class PhoneModuleBridges {

    private PhoneModuleBridges() {}

    public enum HostTab {
        DOWNLOADS,
        TV_HUB,
        BROWSER,
        SETTINGS
    }

    public record BrowserCastPayload(
            String url,
            String audioUrl,
            String format,
            String title,
            int height,
            boolean live,
            String referer,
            String origin,
            String userAgent,
            String cookie,
            String authorization,
            String subtitleUrl,
            String subtitleMime,
            String subtitleLanguage,
            String subtitleContent,
            String subtitleReferer,
            String subtitleOrigin,
            String subtitleUserAgent,
            String subtitleCookie,
            String subtitleAuthorization,
            String secondarySubtitleUrl,
            String secondarySubtitleMime,
            String secondarySubtitleLanguage,
            String secondarySubtitleContent,
            String secondarySubtitleReferer,
            String secondarySubtitleOrigin,
            String secondarySubtitleUserAgent,
            String secondarySubtitleCookie,
            String secondarySubtitleAuthorization,
            String subtitleCatalogJson,
            boolean suppressAutoSubtitles,
            Runnable pauseCallback) {}

    public interface ByteRangeStreamSource {
        String streamId();
        int fileIndex();
        long fileSize();
        InputStream openRange(long start, long length) throws IOException;
        void releaseIfTemporary();
    }

    public record PreparedAddonTorrent(String id, int fileIndex, boolean temporary) {}

    public interface HostShellPort {
        void openHostTab(Context context, HostTab tab, String optionalUrl);
        void openHostTabById(Context context, int navTabId);
        boolean isHostShellActivity(Activity activity);
        int resolveReturnTabForContext(Context context);
        int browserReturnTabId();
        void applyWindowTransitions(Activity activity);
        default boolean openGlobalTorrentSearch(Context context) { return false; }
    }

    public interface TorrentCastPort {
        boolean isAvailable();
        void ensureStarted();
        PreparedAddonTorrent openAddonTorrent(String magnetUri, int fileIndex) throws Exception;
        int prepareBufferPercent(String torrentId, int fileIndex);
        ByteRangeStreamSource resolveReadyStream(String torrentId, int fileIndex, boolean temporary);
        void deleteTemporaryCast(String torrentId);
        void purgeOrphanedTemporaryCastsAsync();
        String parseTorrentFileToMagnet(File torrentFile);
    }

    public interface BrowserModulePort {
        String ensureRuntimeConfigFile(Context context);
        void clearBrowserCacheOnly();
        void purgeOrphanedDownloadStaging(Context context);
        long computeOrphanedDownloadStagingBytes(Context context);
        void notifyBookmarksUpdated();
        File fetchTorrentFileWithHeaders(Context context, String url, String referer, String userAgent, String cookie);
    }

    public interface TvHubModulePort {
        void openStremioAddonInstall(Context context, Uri stremioUri);
        void castBrowserMedia(Context context, BrowserCastPayload payload);
        void castDirectStreamUrl(Context context, String url, String format, String title,
                                 String referer, String userAgent, String cookie);
        void castLocalVideoFile(Context context, String filePath, String title);
        void castCapturedTorrent(Context context, String magnetUri, String torrentUrl,
                                 String title, String referer, String userAgent, String cookie);
        void updateActiveCastTitle(Context context, String title);
        String registerInMemorySubtitle(String content, String mime);
        boolean isDualSubtitlesEnabled(Context context);
        void setDualSubtitlesEnabled(Context context, boolean enabled);
        boolean isTempTorrentCastActive();
        void notifyAudioSettingsChanged(Context context);
        default void notifyLibraryDownloadUpdated(Context context, String filePath) {}
    }

    private static final HostShellPort NOOP_HOST_SHELL = new HostShellPort() {
        @Override public void openHostTab(Context context, HostTab tab, String optionalUrl) {}
        @Override public void openHostTabById(Context context, int navTabId) {}
        @Override public boolean isHostShellActivity(Activity activity) { return false; }
        @Override public int resolveReturnTabForContext(Context context) { return 0; }
        @Override public int browserReturnTabId() { return 0; }
        @Override public void applyWindowTransitions(Activity activity) {}
    };

    private static final TorrentCastPort NOOP_TORRENT_CAST = new TorrentCastPort() {
        @Override public boolean isAvailable() { return false; }
        @Override public void ensureStarted() {}
        @Override public PreparedAddonTorrent openAddonTorrent(String magnetUri, int fileIndex) { return null; }
        @Override public int prepareBufferPercent(String torrentId, int fileIndex) { return -1; }
        @Override public ByteRangeStreamSource resolveReadyStream(String torrentId, int fileIndex, boolean temporary) { return null; }
        @Override public void deleteTemporaryCast(String torrentId) {}
        @Override public void purgeOrphanedTemporaryCastsAsync() {}
        @Override public String parseTorrentFileToMagnet(File torrentFile) { return ""; }
    };

    private static final BrowserModulePort NOOP_BROWSER = new BrowserModulePort() {
        @Override public String ensureRuntimeConfigFile(Context context) { return null; }
        @Override public void clearBrowserCacheOnly() {}
        @Override public void purgeOrphanedDownloadStaging(Context context) {}
        @Override public long computeOrphanedDownloadStagingBytes(Context context) { return 0L; }
        @Override public void notifyBookmarksUpdated() {}
        @Override public File fetchTorrentFileWithHeaders(Context context, String url, String referer, String userAgent, String cookie) { return null; }
    };

    private static final TvHubModulePort NOOP_TV_HUB = new TvHubModulePort() {
        @Override public void openStremioAddonInstall(Context context, Uri stremioUri) {}
        @Override public void castBrowserMedia(Context context, BrowserCastPayload payload) {}
        @Override public void castDirectStreamUrl(Context context, String url, String format, String title, String referer, String userAgent, String cookie) {}
        @Override public void castLocalVideoFile(Context context, String filePath, String title) {}
        @Override public void castCapturedTorrent(Context context, String magnetUri, String torrentUrl, String title, String referer, String userAgent, String cookie) {}
        @Override public void updateActiveCastTitle(Context context, String title) {}
        @Override public String registerInMemorySubtitle(String content, String mime) { return ""; }
        @Override public boolean isDualSubtitlesEnabled(Context context) { return false; }
        @Override public void setDualSubtitlesEnabled(Context context, boolean enabled) {}
        @Override public boolean isTempTorrentCastActive() { return false; }
        @Override public void notifyAudioSettingsChanged(Context context) {}
    };

    private static volatile HostShellPort hostShellPort = NOOP_HOST_SHELL;
    private static volatile TorrentCastPort torrentCastPort = NOOP_TORRENT_CAST;
    private static volatile BrowserModulePort browserPort = NOOP_BROWSER;
    private static volatile TvHubModulePort tvHubPort = NOOP_TV_HUB;

    public static void registerHostShell(HostShellPort port) {
        hostShellPort = port != null ? port : NOOP_HOST_SHELL;
    }

    public static void registerTorrentCast(TorrentCastPort port) {
        torrentCastPort = port != null ? port : NOOP_TORRENT_CAST;
    }

    public static void registerBrowser(BrowserModulePort port) {
        browserPort = port != null ? port : NOOP_BROWSER;
    }

    public static void registerTvHub(TvHubModulePort port) {
        tvHubPort = port != null ? port : NOOP_TV_HUB;
    }

    public static HostShellPort hostShell() {
        return hostShellPort;
    }

    public static TorrentCastPort torrentCast() {
        return torrentCastPort;
    }

    public static BrowserModulePort browser() {
        return browserPort;
    }

    public static TvHubModulePort tvHub() {
        return tvHubPort;
    }
}

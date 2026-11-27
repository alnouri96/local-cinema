package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.KeyEvent;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.zxing.integration.android.IntentIntegrator;

public final class TvHubActivity extends AppCompatActivity {
    static final int GOLD = 0xfff0bf62;
    final TvHubMusicController music = new TvHubMusicController(this);
    final TvHubSubtitlesController subtitles = new TvHubSubtitlesController(this);
    final TvHubLibraryController library = new TvHubLibraryController(this);
    final TvHubConnectionController connection = new TvHubConnectionController(this);

    private EditText message;
    String connectedTvName = "";
    private MaterialButton send;
    org.java_websocket.client.WebSocketClient socket;
    boolean connected;
    private boolean scrubbing;
    private TextView playbackTime;
    private View goLive;
    private View playbackJumps;
    private boolean webLive;
    private RemoteJoystick joystick;
    private TextView playbackTitle;
    private final androidx.activity.result.ActivityResultLauncher<String[]> subtitlePicker =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) subtitles.onSubtitlePicked(uri); });
    void launchSubtitlePicker() { subtitlePicker.launch(new String[]{"*/*"}); }

    private boolean userSelectedTab;
    private static final int LIBRARY_TAB = 0;
    static final int CONNECTION_TAB = 1;
    private static final int LOCAL_MEDIA_TAB = 2;
    private static final int TOP_TAB_COUNT = 3;
    private int lastContentTabIndex = CONNECTION_TAB;
    LinearLayout hub;
    private LinearLayout tabSections;
    ScrollView playbackScreen;
    LinearLayout playbackShortcut;
    private TextView playbackShortcutTitle;
    private LinearLayout volumeCard;
    private android.widget.SeekBar volumeSlider;
    private MaterialButton volumeBadge;
    private MaterialButton muteButton;
    int volumePercent;
    int lastAudibleVolume = 25;
    boolean volumeMuted;
    boolean volumeAvailable;
    boolean adjustingVolume;
    private long volumeRequestedAt;
    final Runnable sendVolume = this::sendVolumeRequest;
    private MaterialButton playPause;
    boolean playbackOpened;
    String pendingCastTitle;
    volatile int torrentCastGeneration;
    boolean pendingTorrentCast;
    private boolean fetchingAddonTorrent;
    private volatile String activeTempTorrentId = "";
    String activeWatchKey;
    String activeWatchKind;
    boolean activeWatchExcluded;
    private boolean lastCastFromLocalMedia;
    String activeWatchTitle;
    String activeWatchUri;
    String activeWatchTorrentId;
    int activeWatchFileIndex;
    String activeWatchFormat;
    String activeWatchImdbId;
    long activeWatchTmdbId;
    int activeWatchSeason;
    int activeWatchEpisode;
    private int resumePosition;
    android.net.Uri resumableVideo;
    int lastCastPosition;
    int remoteDuration;
    Runnable reconnectPlay;
    private long resumeSentAt;
    private android.widget.SeekBar timeline;
    private TextView playbackDuration;
    final java.util.concurrent.ExecutorService watchSyncIo = java.util.concurrent.Executors.newCachedThreadPool();
    boolean hubForeground = false;
    private boolean tvPlaybackActive = false;
    private boolean catalogChannelPlaying;
    private boolean tvCastPlaybackObserved = false;
    private boolean tvPhotoObserved;
    private long castStartedAtMs = 0L;
    private PhotoCastViewer activePhotoViewer;
    boolean pendingQrScanLaunch = false;
    boolean suppressNextResumeReconnect = false;
    private boolean idleSleeping = false;
    private boolean screenReceiverRegistered = false;
    private boolean reconnecting;
    private int reconnectAttempts;
    private Runnable statusDeadline;

    boolean isReconnecting() { return reconnecting; }

    public static boolean isTempTorrentCastActive() {
        var inst = activeInstance;
        return inst != null && inst.activeTempTorrentId != null && !inst.activeTempTorrentId.isEmpty();
    }

    public static void registerModulePort() {
        PhoneModuleBridges.registerTvHub(new PhoneModuleBridges.TvHubModulePort() {
            @Override
            public void openStremioAddonInstall(Context context, android.net.Uri stremioUri) {
                if (context == null || stremioUri == null) return;
                Intent intent = new Intent(Intent.ACTION_VIEW, stremioUri, context, TvHubActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                if (!(context instanceof android.app.Activity)) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(intent);
                if (context instanceof android.app.Activity act) {
                    act.overridePendingTransition(0, 0);
                }
            }

            @Override
            public void castBrowserMedia(Context context, PhoneModuleBridges.BrowserCastPayload p) {
                if (context == null || p == null) return;
                castFromBrowser(context, new BrowserCastRequest(
                        p.url(), p.audioUrl(), p.format(), p.title(), p.height(), p.live(),
                        p.referer(), p.origin(), p.userAgent(), p.cookie(), p.authorization(),
                        p.subtitleUrl(), p.subtitleMime(), p.subtitleLanguage(), p.subtitleContent(),
                        p.subtitleReferer(), p.subtitleOrigin(), p.subtitleUserAgent(),
                        p.subtitleCookie(), p.subtitleAuthorization(),
                        p.secondarySubtitleUrl(), p.secondarySubtitleMime(), p.secondarySubtitleLanguage(),
                        p.secondarySubtitleContent(),
                        p.secondarySubtitleReferer(), p.secondarySubtitleOrigin(), p.secondarySubtitleUserAgent(),
                        p.secondarySubtitleCookie(), p.secondarySubtitleAuthorization(),
                        p.subtitleCatalogJson(), p.suppressAutoSubtitles(),
                        PhoneModuleBridges.hostShell().browserReturnTabId(),
                        p.pauseCallback()));
            }

            @Override
            public void castDirectStreamUrl(Context context, String url, String format, String title,
                                            String referer, String userAgent, String cookie) {
                if (context == null) return;
                castFromBrowser(context, url, null, format, title, 0,
                        null, referer, "", userAgent, cookie, null);
            }

            @Override
            public void castLocalVideoFile(Context context, String filePath, String title) {
                castDownloadedFile(context, filePath, title);
            }

            @Override
            public void castCapturedTorrent(Context context, String magnetUri, String torrentUrl,
                                            String title, String referer, String userAgent, String cookie) {
                castTorrentFromBrowser(context, magnetUri, torrentUrl, title, referer, userAgent, cookie);
            }

            @Override
            public void updateActiveCastTitle(Context context, String title) {
                updateActiveBrowserCastTitle(context, title);
            }

            @Override
            public String registerInMemorySubtitle(String content, String mime) {
                String path = PhoneVideoServer.registerInMemorySubtitle(content, mime);
                return path != null ? path : "";
            }

            @Override
            public boolean isDualSubtitlesEnabled(Context context) {
                return TvHubSubtitlesController.dualEnabled(context);
            }

            @Override
            public void setDualSubtitlesEnabled(Context context, boolean enabled) {
                TvHubSubtitlesController.setDualEnabled(context, enabled);
            }

            @Override
            public boolean isTempTorrentCastActive() {
                return TvHubActivity.isTempTorrentCastActive();
            }

            @Override
            public void notifyAudioSettingsChanged(Context context) {
                TvHubActivity.notifyAudioSettingsChanged(context);
            }

            @Override
            public void notifyLibraryDownloadUpdated(Context context, String filePath) {
                if (context != null) {
                    CinemaStorage.migrateLegacyDownloadingFolder(context);
                }
                var inst = activeInstance;
                if (inst != null && !inst.isDestroyed()) {
                    inst.main.post(() -> inst.library.onExternalDownloadCompleted(filePath));
                }
            }
        });
    }

    boolean hasActiveCastSession() {
        boolean videoActive = (videoServer != null && videoServer.isAlive() && !videoServer.isAudioServer())
                || pendingTorrentCast
                || fetchingAddonTorrent
                || webVideo != null
                || "video".equals(castKind)
                || tvPlaybackActive;
        boolean musicActive = music.hasActiveSession();
        boolean photoActive = photoPath != null
                || "image".equals(castKind)
                || (activePhotoViewer != null && activePhotoViewer.isCasting());
        return videoActive || musicActive || photoActive;
    }

    private boolean isScreenLockedOrOff() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return true;
        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    private boolean hasValidPairedTvEndpoint() {
        String pairing = getSharedPreferences("cinema_link", 0).getString("pairing", null);
        return pairing != null && !pairing.isBlank();
    }

    void syncCastKeepAliveService() {
        if (!hasActiveCastSession()) MediaPlaybackNotificationService.stop(this);
    }

    private void enterIdleSleep() {
        if (hasActiveCastSession()) {
            syncCastKeepAliveService();
            return;
        }
        idleSleeping = true;
        reconnecting = false;
        main.removeCallbacks(playbackPoll);
        main.removeCallbacks(reconnect);
        clearPairingTimeout();
        clearStatusDeadline();
        if (videoServer != null && !videoServer.isTorrentStream()) {
            videoServer.stop();
            videoServer = null;
        }
        if (socket != null || connected) {
            var previous = socket;
            socket = null;
            if (previous != null) {
                try {
                    if (previous.isOpen()) previous.close(1000, "Idle sleep");
                    else if (previous.getSocket() != null) previous.getSocket().close();
                } catch (Exception ignored) {}
            }
            setConnected(false, getString(org.proninyaroslav.libretorrent.R.string.cinema_disconnected));
        }
        CinemaRemoteBridge.enterIdleSleep();
        MediaPlaybackNotificationService.stop(this);
    }

    private void connectSavedPairing(boolean lightweight) {
        String pairing = getSharedPreferences("cinema_link", 0).getString("pairing", null);
        if (pairing != null && !pairing.isBlank()) {
            connect(pairing, false, lightweight);
        }
    }

    void reconnectIfNeededOnForeground() {
        if (isDestroyed() || isFinishing()) return;
        if (scanningPairing || pendingQrScanLaunch || suppressNextResumeReconnect) return;
        if (isScreenLockedOrOff() && !hasActiveCastSession()) return;
        idleSleeping = false;
        var current = socket;
        if (connected && current != null && current.isOpen()) {
            if (!hubForeground && !hasActiveCastSession()) {
                command("PLAYBACK", "ms", 0);
                return;
            }
            if (statusDeadline == null) {
                statusDeadline = () -> {
                    statusDeadline = null;
                    if (socket != current || isDestroyed() || idleSleeping || scanningPairing) return;
                    reconnectAttempts = 0;
                    connectSavedPairing(true);
                };
                main.postDelayed(statusDeadline, 1800);
            }
            main.removeCallbacks(playbackPoll);
            main.post(playbackPoll);
            return;
        }
        if (pairingTimeout != null || reconnecting) return;
        reconnectAttempts = 0;
        if (pendingPairing != null) {
            connect(pendingPairing, true, false);
        } else if (hasValidPairedTvEndpoint()) {
            connectSavedPairing(true);
        }
    }

    private final android.content.BroadcastReceiver screenStateReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || isDestroyed()) return;
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if (hasActiveCastSession()) {
                    syncCastKeepAliveService();
                } else {
                    enterIdleSleep();
                }
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                idleSleeping = false;
                if (scanningPairing || pendingQrScanLaunch || suppressNextResumeReconnect) return;
                android.app.Activity fg = FloatingTvDpadController.currentForegroundActivity();
                boolean appInForeground = hubForeground || (fg != null && !fg.isFinishing() && !fg.isDestroyed());
                if (appInForeground || hasActiveCastSession()) {
                    reconnectIfNeededOnForeground();
                }
            }
        }
    };

    private final Runnable playbackPoll = new Runnable() {
        @Override public void run() {
            if (!connected) return;
            boolean activeMedia = hasActiveCastSession();
            if (!hubForeground && !activeMedia) {
                return;
            }
            command("PLAYBACK", "ms", 0);
            long nextDelayMs = !hubForeground ? 2500L : (activeMedia ? 750L : 2000L);
            main.postDelayed(this, nextDelayMs);
        }
    };
    static PhoneVideoServer videoServer;
    private final java.util.List<MaterialButton> linkControls = new java.util.ArrayList<>();
    private final androidx.activity.result.ActivityResultLauncher<String[]> videoPicker =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) castVideo(uri, -1, true); });
    final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    boolean scanningPairing;
    private Runnable pairingTimeout;
    private String pendingPairing;
    final Runnable reconnect = () -> {
        if (scanningPairing || pendingQrScanLaunch) return;
        if (isScreenLockedOrOff() && !hasActiveCastSession()) return;
        if (pendingPairing != null) {
            connect(pendingPairing, true, false);
            return;
        }
        connectSavedPairing(true);
    };
    private MaterialButton videoSpeedBtn;
    private MaterialButton videoRotateBtn;
    int videoSpeedPercent = 100;
    boolean rememberVideoSpeed = false;
    private int manualVideoRotation = 0;

    private com.google.android.material.bottomnavigation.BottomNavigationView bottomNavView;
    private boolean suppressBottomNavCallback;

    private final androidx.activity.result.ActivityResultLauncher<String> backupCreateLocalLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
                    connection::onLocalBackupSaveUri);
    void launchBackupCreateLocal(String filename) { backupCreateLocalLauncher.launch(filename); }

    private final androidx.activity.result.ActivityResultLauncher<String[]> backupOpenLocalLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    connection::onLocalBackupOpenUri);
    void launchBackupOpenLocal(String[] mimeTypes) { backupOpenLocalLauncher.launch(mimeTypes); }

    private final androidx.activity.result.ActivityResultLauncher<Intent> driveFallbackCreateLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    res -> {
                        if (res.getResultCode() == RESULT_OK && res.getData() != null && res.getData().getData() != null) {
                            connection.onLocalBackupSaveUri(res.getData().getData());
                        }
                    });
    void launchDriveFallbackCreate(Intent intent) { driveFallbackCreateLauncher.launch(intent); }

    private final androidx.activity.result.ActivityResultLauncher<Intent> driveFallbackOpenLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    res -> {
                        if (res.getResultCode() == RESULT_OK && res.getData() != null && res.getData().getData() != null) {
                            connection.onLocalBackupOpenUri(res.getData().getData());
                        }
                    });
    void launchDriveFallbackOpen(Intent intent) { driveFallbackOpenLauncher.launch(intent); }

    private final androidx.activity.result.ActivityResultLauncher<Intent> googleSignInLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    connection::onGoogleSignInResult);
    void launchGoogleSignIn(Intent intent) { googleSignInLauncher.launch(intent); }

    private final androidx.activity.result.ActivityResultLauncher<Intent> googleConsentLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    connection::onGoogleConsentResult);
    void launchGoogleConsent(Intent intent) { googleConsentLauncher.launch(intent); }

    String l(String ar, String en) {
        return "ar".equals(androidx.core.os.ConfigurationCompat.getLocales(
                getResources().getConfiguration()).get(0).getLanguage()) ? ar : en;
    }

    void openBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            notifyStatus(l("تعذّر فتح المتصفح", "Could not open browser"));
        }
    }

    void openInAppBrowser(String url) {
        if (url == null || url.isBlank()) return;
        PhoneModuleBridges.hostShell().openHostTab(this, PhoneModuleBridges.HostTab.BROWSER, url);
    }

    private String webVideo;
    private String webAudio;
    private String webFormat;
    private String webSubtitle;
    private String webSubtitleMime;
    private String webSubtitleLang;
    private String webSubtitleMemPath;
    private String webSubtitleReferer;
    private String webSubtitleOrigin;
    private String webSubtitleUserAgent;
    private String webSubtitleCookie;
    private String webSubtitleAuthorization;
    private String webSecondarySubtitle;
    private String webSecondarySubtitleMime;
    private String webSecondarySubtitleLang;
    private String webSecondarySubtitleMemPath;
    private String webSecondarySubtitleReferer;
    private String webSecondarySubtitleOrigin;
    private String webSecondarySubtitleUserAgent;
    private String webSecondarySubtitleCookie;
    private String webSecondarySubtitleAuthorization;
    private String webReferer;
    private String webOrigin;

    private String webUserAgent;
    private String webCookie;
    private String webAuthorization;
    private boolean webCastLive;
    private boolean webSuppressAutoSubs;
    private int webHeight;
    String activeCastId = "";
    String browserCastTitle;
    static Runnable browserPause;
    int returnToNavTab = 0;

    record BrowserCastRequest(
            String url, String audioUrl, String format, String title, int height, boolean live,
            String referer, String origin, String userAgent, String cookie, String authorization,
            String subtitleUrl, String subtitleMime, String subtitleLanguage, String subtitleContent,
            String subtitleReferer, String subtitleOrigin, String subtitleUserAgent,
            String subtitleCookie, String subtitleAuthorization,
            String secondarySubtitleUrl, String secondarySubtitleMime, String secondarySubtitleLanguage,
            String secondarySubtitleContent,
            String secondarySubtitleReferer, String secondarySubtitleOrigin, String secondarySubtitleUserAgent,
            String secondarySubtitleCookie, String secondarySubtitleAuthorization,
            String subtitleCatalogJson, boolean suppressAutoSubtitles, int returnTabId,
            Runnable pause) {
        BrowserCastRequest(
                String url, String audioUrl, String format, String title, int height, boolean live,
                String referer, String origin, String userAgent, String cookie, String authorization,
                String subtitleUrl, String subtitleMime, String subtitleLanguage, String subtitleContent,
                String subtitleReferer, String subtitleOrigin, String subtitleUserAgent,
                String subtitleCookie, String subtitleAuthorization,
                String secondarySubtitleUrl, String secondarySubtitleMime, String secondarySubtitleLanguage,
                String secondarySubtitleContent,
                String secondarySubtitleReferer, String secondarySubtitleOrigin, String secondarySubtitleUserAgent,
                String secondarySubtitleCookie, String secondarySubtitleAuthorization,
                String subtitleCatalogJson, int returnTabId,
                Runnable pause) {
            this(url, audioUrl, format, title, height, live,
                    referer, origin, userAgent, cookie, authorization,
                    subtitleUrl, subtitleMime, subtitleLanguage, subtitleContent,
                    subtitleReferer, subtitleOrigin, subtitleUserAgent, subtitleCookie, subtitleAuthorization,
                    secondarySubtitleUrl, secondarySubtitleMime, secondarySubtitleLanguage, secondarySubtitleContent,
                    secondarySubtitleReferer, secondarySubtitleOrigin, secondarySubtitleUserAgent,
                    secondarySubtitleCookie, secondarySubtitleAuthorization,
                    subtitleCatalogJson, false, returnTabId, pause);
        }

        BrowserCastRequest(
                String url, String audioUrl, String format, String title, int height, boolean live,
                String referer, String origin, String userAgent, String cookie, String authorization,
                String subtitleUrl, String subtitleMime, String subtitleLanguage, String subtitleContent,
                String subtitleReferer, String subtitleOrigin, String subtitleUserAgent,
                String subtitleCookie, String subtitleAuthorization,
                String secondarySubtitleUrl, String secondarySubtitleMime, String secondarySubtitleLanguage,
                String secondarySubtitleContent,
                String secondarySubtitleReferer, String secondarySubtitleOrigin, String secondarySubtitleUserAgent,
                String secondarySubtitleCookie, String secondarySubtitleAuthorization,
                Runnable pause) {
            this(url, audioUrl, format, title, height, live,
                    referer, origin, userAgent, cookie, authorization,
                    subtitleUrl, subtitleMime, subtitleLanguage, subtitleContent,
                    subtitleReferer, subtitleOrigin, subtitleUserAgent, subtitleCookie, subtitleAuthorization,
                    secondarySubtitleUrl, secondarySubtitleMime, secondarySubtitleLanguage, secondarySubtitleContent,
                    secondarySubtitleReferer, secondarySubtitleOrigin, secondarySubtitleUserAgent,
                    secondarySubtitleCookie, secondarySubtitleAuthorization,
                    "", false, 0, pause);
        }
    }

    static void castFromBrowser(Context context, String url, String audio, String format, String title, int height, Runnable pause) {
        castFromBrowser(context, url, audio, format, title, height, null, null, null, null, null, pause);
    }

    static void castFromBrowser(Context context, String url, String audio, String format, String title, int height, String subtitleUrl, Runnable pause) {
        castFromBrowser(context, url, audio, format, title, height, subtitleUrl, null, null, null, null, pause);
    }

    static void castFromBrowser(Context context, String url, String audio, String format, String title, int height,
                                String subtitleUrl, String referer, String origin, String userAgent, String cookie,
                                Runnable pause) {
        castFromBrowser(context, new BrowserCastRequest(
                url, audio, format, title, height, false,
                referer, origin, userAgent, cookie, null,
                subtitleUrl, null, null, null,
                referer, origin, userAgent, cookie, null,
                null, null, null, null,
                null, null, null, null, null,
                pause));
    }

    static void castFromBrowser(Context context, BrowserCastRequest req) {
        if (req == null) return;
        browserPause = req.pause();
        String memSubPath = (req.subtitleContent() != null && !req.subtitleContent().isBlank())
                ? PhoneVideoServer.registerInMemorySubtitle(req.subtitleContent(), req.subtitleMime()) : "";
        String memSecSubPath = (req.secondarySubtitleContent() != null && !req.secondarySubtitleContent().isBlank())
                ? PhoneVideoServer.registerInMemorySubtitle(req.secondarySubtitleContent(), req.secondarySubtitleMime()) : "";
        context.startActivity(new Intent(context, TvHubActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("web_video", req.url())
                .putExtra("web_title", cleanCastTitle(req.title()))
                .putExtra("web_audio", req.audioUrl())
                .putExtra("web_format", req.format())
                .putExtra("web_height", req.height())
                .putExtra("web_live", req.live())
                .putExtra("web_referer", req.referer())
                .putExtra("web_origin", req.origin())
                .putExtra("web_ua", req.userAgent())
                .putExtra("web_cookie", req.cookie())
                .putExtra("web_auth", req.authorization())
                .putExtra("web_subtitle", req.subtitleUrl())
                .putExtra("web_sub_mime", req.subtitleMime())
                .putExtra("web_sub_lang", req.subtitleLanguage())
                .putExtra("web_sub_mem", memSubPath)
                .putExtra("web_sub_ref", req.subtitleReferer())
                .putExtra("web_sub_origin", req.subtitleOrigin())
                .putExtra("web_sub_ua", req.subtitleUserAgent())
                .putExtra("web_sub_cookie", req.subtitleCookie())
                .putExtra("web_sub_auth", req.subtitleAuthorization())
                .putExtra("web_sub2", req.secondarySubtitleUrl())
                .putExtra("web_sub2_mime", req.secondarySubtitleMime())
                .putExtra("web_sub2_lang", req.secondarySubtitleLanguage())
                .putExtra("web_sub2_mem", memSecSubPath)
                .putExtra("web_sub2_ref", req.secondarySubtitleReferer())
                .putExtra("web_sub2_origin", req.secondarySubtitleOrigin())
                .putExtra("web_sub2_ua", req.secondarySubtitleUserAgent())
                .putExtra("web_sub2_cookie", req.secondarySubtitleCookie())
                .putExtra("web_sub2_auth", req.secondarySubtitleAuthorization())
                .putExtra("web_sub_catalog", req.subtitleCatalogJson() == null ? "" : req.subtitleCatalogJson())
                .putExtra("web_suppress_auto_subs", req.suppressAutoSubtitles())
                .putExtra("return_to_nav_tab", req.returnTabId()));
    }

    static void castTorrentFromBrowser(Context context, String magnetUri, String torrentUrl,
                                       String title, String referer, String userAgent, String cookie) {
        if (context == null) return;
        context.startActivity(new Intent(context, TvHubActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("browser_magnet_cast", magnetUri == null ? "" : magnetUri)
                .putExtra("browser_torrent_url_cast", torrentUrl == null ? "" : torrentUrl)
                .putExtra("browser_torrent_title", cleanCastTitle(title))
                .putExtra("browser_torrent_referer", referer == null ? "" : referer)
                .putExtra("browser_torrent_ua", userAgent == null ? "" : userAgent)
                .putExtra("browser_torrent_cookie", cookie == null ? "" : cookie)
                .putExtra("return_to_nav_tab", PhoneModuleBridges.hostShell().browserReturnTabId()));
        if (context instanceof android.app.Activity act) {
            act.overridePendingTransition(0, 0);
        }
    }

    public static void openPlaybackFromTab(Context context, int navTabId) {
        if (context == null) return;
        context.startActivity(new Intent(context, TvHubActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("open_playback_controls", true)
                .putExtra("return_to_nav_tab", navTabId));
    }

    static void updateActiveBrowserCastTitle(Context context, String rawTitle) {
        String cleaned = cleanCastTitle(rawTitle);
        if (cleaned.isBlank()) return;
        var hub = activeInstance;
        if (hub != null && !hub.isDestroyed()) {
            hub.main.post(() -> hub.applyLiveCastTitleUpdate(cleaned));
        }
    }

    private static volatile TvHubActivity activeInstance;

    private static final java.util.regex.Pattern CAST_DECOY_SPAM_RE = java.util.regex.Pattern.compile(
            "(?i)\\b(?:business\\s+loans?|personal\\s+loans?|student\\s+loans?|auto\\s+loans?|home\\s+loans?|payday\\s+loans?|small\\s+business\\s+funding|mortgages?|refinanc(?:e|ing)|insurance|credit\\s+cards?|credit\\s+scores?|tips\\s+for\\s+a\\s+successful|\\d+\\s+tips\\s+(?:for|to)\\s+|investment|investing|wealth\\s+management|retirement\\s+planning|tax\\s+deductions?|forex|make\\s+money|earn\\s+money|work\\s+from\\s+home|passive\\s+income|binary\\s+options?|day\\s+trading|stock\\s+(?:market|trading)|crypto(?:currency)?|bitcoin|casino|betting|sportsbook|slot\\s+machines?|sponsored(?:\\s+ad|\\s+content)?|advertisement|skip\\s+ads?|1xbet|melbet|mostbet|linebet|betwinner|olymp\\s*trade|iq\\s*option|binomo|attention\\s+required|just\\s+a\\s+moment|cloudflare)\\b");

    static String cleanCastTitle(String raw) {
        if (raw == null) return "";
        if (CAST_DECOY_SPAM_RE.matcher(raw).find()) return "";
        String t = raw.replaceAll("\\s+", " ").trim()
                .replaceFirst("(?i)^(?:watch|مشاهدة(?:\\s+و\\s*تحميل)?|تحميل(?:\\s+و\\s*مشاهدة)?|فيلم|مسلسل|انمي|أنمي)\\s+", "")
                .replaceFirst("(?i)\\s*(?:[|–—•·:\\-]\\s*)+(?:shabakaty\\s*)?(?:cinemana|shabakaty|youtube|egybest|faselhd|mycima|wecima|e?\\.?cimalight(?:\\.co)?|arabseed|akoam|شبكتي|سينمانا|سيما\\s*لايت|ايجي\\s*بست|فاصل\\s*إعلاني|ماي\\s*سيما|اون\\s*لاين|أون\\s*لاين).*$", "")
                .replaceFirst("(?i)^(?:shabakaty\\s*)?(?:cinemana|shabakaty|youtube|egybest|faselhd|mycima|wecima|شبكتي|سينمانا)\\s*(?:[|–—•·:\\-]\\s*)+", "")
                .replaceAll("(?i)\\s*\\b(?:watch\\s+online|full\\s+movie|official\\s+trailer|مترجم(?:\\s+أون\\s*لاين|\\s+اون\\s*لاين|\\s+كامل)?|مدبلج|أون\\s*لاين|اون\\s*لاين|اونلاين|HD|FHD|UHD|4K)\\b\\s*$", "")
                .replaceAll("^[\\s\\-–—|:•·]+|[\\s\\-–—|:•·]+$", "")
                .trim();
        String low = t.toLowerCase(java.util.Locale.ROOT);
        if (low.equals("cinemana") || low.equals("shabakaty") || low.equals("youtube")
                || low.equals("shabakaty cinemana") || low.equals("سينمانا") || low.equals("شبكتي")
                || low.equals("elif.news") || low.equals("elif news")
                || low.equals("browser video") || low.equals("video on tv")
                || CAST_DECOY_SPAM_RE.matcher(t).find()) {
            return "";
        }
        return t;
    }

    private void applyLiveCastTitleUpdate(String cleaned) {
        if (cleaned == null || cleaned.isBlank() || isDestroyed()) return;
        browserCastTitle = cleaned;
        activeWatchTitle = cleaned;
        subtitles.subtitleTitle = cleaned;
        if (playbackTitle != null) playbackTitle.setText(cleaned);
        if (playbackShortcutTitle != null) playbackShortcutTitle.setText(cleaned);
        if (CinemaRemoteBridge.isCastActive()) {
            showCastNotification(cleaned, castKind == null ? "video" : castKind);
        }
        var ws = socket;
        if (ws != null && ws.isOpen()) {
            watchSyncIo.execute(() -> {
                try {
                    if (ws.isOpen()) {
                        ws.send(new org.json.JSONObject()
                                .put("type", "UPDATE_CAST_METADATA")
                                .put("title", cleaned).toString());
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    public static void open(Context context) {
        context.startActivity(new Intent(context, TvHubActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        if (context instanceof android.app.Activity act) {
            act.overridePendingTransition(0, 0);
        }
    }

    private static final String OPEN_SYNC_SETTINGS = "open_sync_settings";
    private static final String OPEN_UNIFIED_SUBTITLES = "open_unified_subtitles";

    public static void openSyncSettings(Context context) {
        context.startActivity(new Intent(context, TvHubActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(OPEN_SYNC_SETTINGS, true));
    }

    public static void openUnifiedSubtitleSettings(Context context) {
        context.startActivity(new Intent(context, TvHubActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(OPEN_UNIFIED_SUBTITLES, true));
    }

    private void handleSyncSettingsIntent(Intent intent) {
        if (intent.getBooleanExtra(OPEN_UNIFIED_SUBTITLES, false)) {
            intent.removeExtra(OPEN_UNIFIED_SUBTITLES);
            if (tabLayout != null) tabLayout.selectTab(tabLayout.getTabAt(CONNECTION_TAB));
            main.post(() -> { if (!isDestroyed() && subtitles != null) subtitles.showUnifiedSubtitleSettingsSheet(); });
            return;
        }
        if (!intent.getBooleanExtra(OPEN_SYNC_SETTINGS, false)) return;
        intent.removeExtra(OPEN_SYNC_SETTINGS);
        if (tabLayout != null) tabLayout.selectTab(tabLayout.getTabAt(CONNECTION_TAB));
        main.post(connection::showLiveCountrySync);
    }

    public static void castDownloadedFile(Context context, String path, String title) {
        context.startActivity(new Intent(context, TvHubActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("local_video_path", path)
                .putExtra("web_title", title));
        if (context instanceof android.app.Activity act) {
            act.overridePendingTransition(0, 0);
        }
    }

    public static boolean isTorrentVideo(String path) {
        return path != null && path.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(mp4|mkv|avi|webm|mov|ts)$");
    }

    public static void castTorrent(Context context, String id, int index, String title) {
        castTorrent(context, id, index, title, false);
    }

    public static void castTorrent(Context context, String id, int index, String title, boolean temporary) {
        context.startActivity(new Intent(context, TvHubActivity.class)
                .putExtra("torrent_cast_id", id).putExtra("torrent_cast_index", index)
                .putExtra("torrent_cast_title", title).putExtra("torrent_cast_temporary", temporary)
                .putExtra("return_to_nav_tab",
                        PhoneModuleBridges.hostShell().resolveReturnTabForContext(context)));
    }

    @Override
    protected void onCreate(Bundle state) {
        activeInstance = this;
        super.onCreate(state);
        PhoneModuleBridges.hostShell().applyWindowTransitions(this);
        CinemaRemoteBridge.attachHub(this);
        var screenFilter = new android.content.IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        androidx.core.content.ContextCompat.registerReceiver(this, screenStateReceiver, screenFilter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        screenReceiverRegistered = true;
        music.loadAudioPrefs();
        com.meedx.providers.TraktSyncClient.migrateAndCleanCredentials(this);
        CinemaCacheManager.performStartupMaintenance(this);
        LocalCinemaBackupManager.initAutoSync(this);
        connection.subscribeTrakt();
        getWindow().getDecorView().setLayoutDirection(getResources().getConfiguration().getLayoutDirection());
        var scroll = new TabScrollView();
        var page = column();
        page.setBackgroundColor(0xff101113);
        page.setPadding(dp(16), dp(12), dp(16), dp(16));
        var shell = column();
        shell.setBackgroundColor(0xff101113);
        hub = shell;
        var root = new android.widget.FrameLayout(this);
        root.addView(hub);
        playbackScreen = new PlaybackScrollView(() -> showPlayback(false), () -> volumeCard, () -> timeline, () -> volumeSlider);
        playbackScreen.setFillViewport(true);
        playbackScreen.setBackgroundColor(0xff101113);
        playbackScreen.setVisibility(android.view.View.GONE);
        root.addView(playbackScreen);
        music.musicPlaybackScreen = column();
        music.musicPlaybackScreen.setBackgroundColor(0xff0d0e13);
        music.musicPlaybackScreen.setVisibility(android.view.View.GONE);
        root.addView(music.musicPlaybackScreen, new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (music.musicPlaybackOpened) {
                    if (music.musicQueueMode) music.setMusicQueueMode(false);
                    else music.showMusicSheet(false);
                } else if (playbackOpened) {
                    showPlayback(false);
                } else {
                    finish();
                }
            }
        });
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(shell, (view, insets) -> {
            var bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            shell.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            playbackScreen.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            playbackScreen.setClipToPadding(false);
            music.musicPlaybackScreen.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        var tabs = new com.google.android.material.tabs.TabLayout(this);
        tabs.setTabMode(com.google.android.material.tabs.TabLayout.MODE_FIXED);
        tabs.setTabGravity(com.google.android.material.tabs.TabLayout.GRAVITY_FILL);
        tabs.setPaddingRelative(dp(4), 0, dp(4), 0);
        tabs.setHorizontalScrollBarEnabled(false);
        tabs.setSelectedTabIndicatorColor(GOLD);
        tabs.setTabTextColors(0xffc9c7c4, GOLD);
        shell.addView(tabs, new LinearLayout.LayoutParams(-1, dp(52)));
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        scroll.setFillViewport(true);
        playbackShortcut = createMiniCastingBar();
        playbackShortcut.setVisibility(android.view.View.GONE);
        shell.addView(playbackShortcut, miniCastLayoutParams());
        music.musicShortcut = music.createMusicMiniBar();
        music.musicShortcut.setVisibility(android.view.View.GONE);
        var musicBarLp = miniCastLayoutParams();
        musicBarLp.height = dp(56);
        shell.addView(music.musicShortcut, musicBarLp);

        bottomNavView = (com.google.android.material.bottomnavigation.BottomNavigationView)
                getLayoutInflater().inflate(org.proninyaroslav.libretorrent.R.layout.cinema_bottom_navigation, shell, false);
        bottomNavView.getMenu().removeItem(org.proninyaroslav.libretorrent.R.id.feed_nav);
        bottomNavView.setBackgroundColor(0xff1d1e21);
        bottomNavView.setItemHorizontalTranslationEnabled(false);
        // The hub shell already pads for system bars.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(bottomNavView, (view, insets) -> insets);
        bottomNavView.setLabelVisibilityMode(com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED);
        var navStates = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        var navColors = new int[]{GOLD, 0xff9ea4ae};
        var navTint = new android.content.res.ColorStateList(navStates, navColors);
        bottomNavView.setItemIconTintList(navTint);
        bottomNavView.setItemTextColor(navTint);
        suppressBottomNavCallback = true;
        bottomNavView.setSelectedItemId(org.proninyaroslav.libretorrent.R.id.cinema_hub_nav);
        suppressBottomNavCallback = false;
        bottomNavView.setOnItemSelectedListener(item -> {
            if (suppressBottomNavCallback) return true;
            int id = item.getItemId();
            if (id == org.proninyaroslav.libretorrent.R.id.cinema_hub_nav) return true;
            PhoneModuleBridges.hostShell().openHostTabById(this, id);
            return false;
        });
        shell.addView(bottomNavView, new LinearLayout.LayoutParams(-1, -2));
        var sections = column();
        tabSections = sections;
        sections.setClipToPadding(false);
        updateTabBottomPadding();
        scroll.addView(sections);
        library.libraryPage = section(sections);
        sections.addView(page);
        var remotePage = column();
        remotePage.setPadding(dp(16), dp(8), dp(16), dp(24));
        playbackScreen.addView(remotePage, new ScrollView.LayoutParams(-1, -2));

        var mediaPage = section(sections);
        connection.buildStream(page);
        buildRemote(remotePage);
        music.buildMusicSheet(music.musicPlaybackScreen);

        buildMedia(mediaPage);
        var panels = new LinearLayout[]{library.libraryPage, page, mediaPage};
        String[] tabTitles = new String[]{
                getString(org.proninyaroslav.libretorrent.R.string.cinema_library),
                getString(org.proninyaroslav.libretorrent.R.string.cinema_connection),
                getString(org.proninyaroslav.libretorrent.R.string.cinema_local_media)
        };
        for (String title : tabTitles) {
            var tab = tabs.newTab().setText(title);
            TextView tabLabel = label(title, 14);
            tabLabel.setTextSize(14f);
            tabLabel.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            tabLabel.setGravity(Gravity.CENTER);
            tabLabel.setSingleLine(true);
            tabLabel.setMaxLines(1);
            tabLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tabLabel.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            tab.view.setMinimumWidth(0);
            tab.view.setPaddingRelative(dp(6), 0, dp(6), 0);
            tabLabel.setTextColor(new android.content.res.ColorStateList(
                    new int[][]{{android.R.attr.state_selected}, {}}, new int[]{GOLD, 0xffc9c7c4}));
            tab.setCustomView(tabLabel);
            tabs.addTab(tab);
        }
        tabs.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                int pos = tab.getPosition();
                if (pos < 0 || pos >= panels.length) return;
                lastContentTabIndex = pos;
                for (int i = 0; i < panels.length; i++) panels[i].setVisibility(i == pos
                        ? android.view.View.VISIBLE : android.view.View.GONE);
                userSelectedTab = true;
                if (pos == LIBRARY_TAB) library.showLibrary();
                if (pos == CONNECTION_TAB) {
                    reconnectIfNeededOnForeground();
                    connection.refreshAddonsAndTraktCard();
                }
                if (pos == LOCAL_MEDIA_TAB && localMedia != null) localMedia.refresh();
                music.revealMusicMiniBar();
                scroll.scrollTo(0, 0);
            }
            @Override public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
            @Override public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
        });
        for (int i = 1; i < panels.length; i++) panels[i].setVisibility(android.view.View.GONE);
        int initialContentTab = state == null ? CONNECTION_TAB
                : Math.min(panels.length - 1, Math.max(0, state.getInt("hub_tab", CONNECTION_TAB)));
        lastContentTabIndex = initialContentTab;
        tabs.selectTab(tabs.getTabAt(initialContentTab));
        userSelectedTab = state != null;
        library.showLibrary();
        tabs.setId(android.view.View.generateViewId());
        tabLayout = tabs;
        pendingTorrentCast = getIntent().hasExtra("torrent_cast_id");
        if (pendingTorrentCast) {
            pendingCastTitle = getIntent().getStringExtra("torrent_cast_title");
            playbackTitle.setText(getString(org.proninyaroslav.libretorrent.R.string.cinema_connecting_to_tv));
            showPlayback(true);
        }
        webVideo = getIntent().getStringExtra("web_video");
        if (webVideo != null) {
            readWebCastExtras(getIntent());
            showPlayback(true);
            browserCastTitle = cleanCastTitle(getIntent().getStringExtra("web_title"));
            String shownTitle = browserCastTitle.isBlank() ? l("البث على الشاشة", "Video on TV") : browserCastTitle;
            playbackTitle.setText(shownTitle);
            if (playbackShortcutTitle != null) playbackShortcutTitle.setText(shownTitle);
            subtitles.subtitleTitle = browserCastTitle;
        } else if (getIntent().hasExtra("return_to_nav_tab")) {
            returnToNavTab = getIntent().getIntExtra("return_to_nav_tab", 0);
        }
        receiveSharedMedia();
        if ("com.meedx.localcinema.manager.STOP_CAST_NOTIFICATION".equals(getIntent().getAction())) stopCasting();
        boolean startQrScan = getIntent().getBooleanExtra("start_qr_scan", false);
        if (startQrScan) {
            getIntent().removeExtra("start_qr_scan");
            pendingQrScanLaunch = true;
            suppressNextResumeReconnect = true;
        } else {
            reconnectIfNeededOnForeground();
        }
        handleLocalVideoIntent(getIntent());
        handleBrowserTorrentCastIntent(getIntent());
        main.post(() -> PhonePermissionsWizardDialog.maybeShowFirstLaunch(this));
        if (webVideo != null && socket == null) notifyStatus(l("امسح رمز QR الخاص بالشاشة أولاً", "Scan the TV pairing QR first"));
        if (startQrScan) {
            main.post(connection::scanTv);
        }
        if (getIntent().getBooleanExtra("open_playback_controls", false)) {
            getIntent().removeExtra("open_playback_controls");
            if (getIntent().hasExtra("return_to_nav_tab")) {
                returnToNavTab = getIntent().getIntExtra("return_to_nav_tab", 0);
            }
            if (music.audioMode) music.showMusicSheet(true);
            else showPlayback(true);
        }
        if (getIntent().getBooleanExtra("open_subtitle_picker", false)) {
            getIntent().removeExtra("open_subtitle_picker");
            String subHint = getIntent().getStringExtra("subtitle_title");
            if (subHint != null && !subHint.isBlank()) subtitles.subtitleTitle = subHint;
            main.post(this::launchSubtitlePicker);
        }
        connection.handleTraktPkceIntent(getIntent());
        connection.handleAddonIntent(getIntent());
        handleSyncSettingsIntent(getIntent());
    }

    private LinearLayout createMiniCastingBar() {
        var bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(6), dp(12), dp(6));
        bar.setClickable(true);
        bar.setFocusable(true);
        bar.setBackground(CinemaGlassUi.glassPill(this, CinemaGlassUi.Accent.CONNECTION_ONLINE, 16));
        bar.setContentDescription(l("أدوات البث الحالي", "Open playback controls"));

        var castIcon = new android.widget.ImageView(this);
        castIcon.setImageResource(org.proninyaroslav.libretorrent.R.drawable.ic_cinema_cast_play);
        castIcon.setColorFilter(GOLD);
        var iconLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        iconLp.setMarginEnd(dp(10));
        bar.addView(castIcon, iconLp);

        playbackShortcutTitle = new TextView(this);
        playbackShortcutTitle.setText(l("جاري البث على الشاشة", "Now Casting"));
        playbackShortcutTitle.setTextColor(0xfffafafa);
        playbackShortcutTitle.setTextSize(13);
        playbackShortcutTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        playbackShortcutTitle.setSingleLine(true);
        playbackShortcutTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(playbackShortcutTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        var upChevron = new android.widget.ImageView(this);
        upChevron.setImageResource(org.proninyaroslav.libretorrent.R.drawable.ic_remote_casting_up);
        upChevron.setColorFilter(GOLD);
        var upLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        upLp.setMarginStart(dp(8));
        bar.addView(upChevron, upLp);

        bar.setOnClickListener(v -> showPlayback(true));
        return bar;
    }

    private LinearLayout.LayoutParams miniCastLayoutParams() {
        boolean orbRight = getSharedPreferences("cinema_remote_floating", MODE_PRIVATE).getBoolean("edge_right", true);
        var lp = new LinearLayout.LayoutParams(-1, dp(44));
        lp.setMargins(orbRight ? dp(16) : dp(72), dp(4), orbRight ? dp(72) : dp(16), dp(6));
        return lp;
    }

    void setPlaybackShortcutVisible(boolean visible) {
        if (playbackShortcut == null) return;
        visible = visible && !catalogChannelPlaying;
        int target = visible ? android.view.View.VISIBLE : android.view.View.GONE;
        if (visible && music.musicShortcut != null) {
            main.removeCallbacks(music.musicMiniAutoHideRunnable);
            music.musicShortcut.setVisibility(android.view.View.GONE);
        }
        if (playbackShortcut.getVisibility() != target) {
            playbackShortcut.setVisibility(target);
            updateTabBottomPadding();
        }
    }

    public static void notifyAudioSettingsChanged(Context context) {
        var inst = activeInstance;
        if (inst == null || inst.isDestroyed()) return;
        inst.main.post(() -> {
            if (inst.isDestroyed()) return;
            inst.music.onAudioSettingsChanged();
        });
    }

    void updateTabBottomPadding() {
        if (tabSections == null) return;
        boolean miniVisible = (playbackShortcut != null && playbackShortcut.getVisibility() == android.view.View.VISIBLE)
                || (music.musicShortcut != null && music.musicShortcut.getVisibility() == android.view.View.VISIBLE);
        tabSections.setPadding(0, 0, 0, miniVisible ? dp(76) : dp(16));
    }

    private final class TabScrollView extends ScrollView {
        private float startX;
        private float startY;
        private boolean canSwipe;
        private int dragAxis; // 0 = undecided, 1 = vertical, 2 = horizontal
        private boolean sentCancel;
        private final int touchSlop;

        TabScrollView() {
            super(TvHubActivity.this);
            touchSlop = android.view.ViewConfiguration.get(TvHubActivity.this).getScaledTouchSlop();
            setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        }

        @Override protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            if (music.audioMode && !music.musicPlaybackOpened && music.musicShortcut != null && music.isAutoHideMiniPlayerEnabled()) {
                if (t - oldt > dp(6)) {
                    main.removeCallbacks(music.musicMiniAutoHideRunnable);
                    if (music.musicShortcut.getVisibility() == android.view.View.VISIBLE) {
                        music.musicShortcut.setVisibility(android.view.View.GONE);
                        updateTabBottomPadding();
                    }
                } else if (oldt - t > dp(6)) {
                    music.revealMusicMiniBar();
                }
            }
        }

        private boolean hasHorizontalChildAt(android.view.View view, float rawX, float rawY) {
            if (view == null || !view.isShown()) return false;
            var bounds = new android.graphics.Rect();
            if (!view.getGlobalVisibleRect(bounds) || !bounds.contains((int) rawX, (int) rawY)) return false;
            if (view instanceof android.widget.HorizontalScrollView
                    || view instanceof android.widget.SeekBar
                    || view instanceof androidx.viewpager2.widget.ViewPager2) {
                return true;
            }
            if (view instanceof android.view.ViewGroup group) {
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    if (hasHorizontalChildAt(group.getChildAt(i), rawX, rawY)) return true;
                }
            }
            return false;
        }

        @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.getX();
                    startY = event.getY();
                    dragAxis = 0;
                    sentCancel = false;
                    var bounds = new android.graphics.Rect();
                    boolean onTimeline = timeline != null && timeline.isShown()
                            && timeline.getGlobalVisibleRect(bounds)
                            && bounds.contains((int) event.getRawX(), (int) event.getRawY());
                    canSwipe = !onTimeline && !hasHorizontalChildAt(this, event.getRawX(), event.getRawY());
                }
                case android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    canSwipe = false;
                    dragAxis = 1;
                }
                case android.view.MotionEvent.ACTION_MOVE -> {
                    float dx = Math.abs(event.getX() - startX);
                    float dy = Math.abs(event.getY() - startY);
                    if (dragAxis == 0) {
                        if (dy > touchSlop && dy >= dx) {
                            dragAxis = 1;
                            canSwipe = false;
                        } else if (canSwipe && dx > touchSlop && dx > 1.6f * dy) {
                            dragAxis = 2;
                        }
                    }
                    if (dragAxis == 2) {
                        if (!sentCancel) {
                            sentCancel = true;
                            var cancel = android.view.MotionEvent.obtain(event);
                            cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
                            super.dispatchTouchEvent(cancel);
                            cancel.recycle();
                        }
                        return true;
                    }
                }
                case android.view.MotionEvent.ACTION_UP -> {
                    float distance = event.getX() - startX;
                    if (canSwipe && dragAxis == 2 && Math.abs(distance) >= dp(56)
                            && Math.abs(distance) > 1.5f * Math.abs(event.getY() - startY)) {
                        int direction = distance < 0 ? 1 : -1;
                        if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) direction = -direction;
                        int next = tabLayout.getSelectedTabPosition() + direction;
                        if (next >= 0 && next < TOP_TAB_COUNT) {
                            tabLayout.selectTab(tabLayout.getTabAt(next));
                        }
                        dragAxis = 0;
                        canSwipe = false;
                        return true;
                    }
                    dragAxis = 0;
                    canSwipe = false;
                }
                case android.view.MotionEvent.ACTION_CANCEL -> {
                    dragAxis = 0;
                    canSwipe = false;
                }
            }
            return super.dispatchTouchEvent(event);
        }
    }

    com.google.android.material.tabs.TabLayout tabLayout;

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        int selected = tabLayout != null ? tabLayout.getSelectedTabPosition() : lastContentTabIndex;
        state.putInt("hub_tab", Math.min(TOP_TAB_COUNT - 1, Math.max(0, selected)));
    }

    @Override protected void onResume() {
        super.onResume();
        overridePendingTransition(0, 0);
        if (bottomNavView != null) {
            suppressBottomNavCallback = true;
            bottomNavView.setSelectedItemId(org.proninyaroslav.libretorrent.R.id.cinema_hub_nav);
            suppressBottomNavCallback = false;
        }
        FloatingTvDpadController.attach(this);
        FloatingTvDpadController.updateActiveSocket(connected ? socket : null, connected);
        connection.onResume();
        if (localMedia != null) localMedia.refreshOnResume();
        if (suppressNextResumeReconnect && !scanningPairing && !pendingQrScanLaunch) {
            suppressNextResumeReconnect = false;
        } else if (!scanningPairing && !pendingQrScanLaunch) {
            reconnectIfNeededOnForeground();
        }
    }

    @Override protected void onPause() {
        FloatingTvDpadController.detach(this);
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) connection.checkSmartClipboard();
    }

    private LinearLayout section(LinearLayout parent) {
        var panel = column();
        panel.setPadding(dp(16), dp(12), dp(16), dp(16));
        parent.addView(panel);
        return panel;
    }

    private void buildRemote(LinearLayout page) {
        var handle = new android.view.View(this);
        var pill = new GradientDrawable();
        pill.setColor(0xff77736c);
        pill.setCornerRadius(dp(2));
        handle.setBackground(pill);
        var handleLayout = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleLayout.gravity = Gravity.CENTER_HORIZONTAL;
        page.addView(handle, handleLayout);
        var header = row(page);
        var collapse = playbackIcon("", "Minimize playback controls", org.proninyaroslav.libretorrent.R.drawable.cinema_control_down, () -> showPlayback(false), false);
        collapse.setContentDescription(getString(org.proninyaroslav.libretorrent.R.string.cinema_playback_minimize));
        header.addView(collapse, new LinearLayout.LayoutParams(dp(52), dp(48)));
        playbackTitle = label(getString(org.proninyaroslav.libretorrent.R.string.cinema_no_active_video), 16);
        playbackTitle.setMaxLines(2);
        playbackTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(playbackTitle, new LinearLayout.LayoutParams(0, -2, 1));
        var artwork = new PlaybackArtworkView(this, 2f / 3f);
        videoPoster = new android.widget.ImageView(this);
        videoPoster.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        videoPoster.setImageResource(org.proninyaroslav.libretorrent.R.drawable.ic_cinema_cast_play);
        artwork.addArtwork(videoPoster);
        var artworkSize = new LinearLayout.LayoutParams(-1, dp(116), 1f);
        artworkSize.topMargin = dp(6);
        artworkSize.bottomMargin = dp(12);
        page.addView(artwork, artworkSize);

        var seek = column();
        page.addView(seek);
        buildVolumeCard(seek);
        var times = row(seek);
        times.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        playbackTime = label("00:00", 14);
        playbackDuration = label("00:00", 14);
        playbackTime.setFontFeatureSettings("tnum");
        playbackTime.setTextDirection(View.TEXT_DIRECTION_LTR);
        playbackTime.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        playbackDuration.setFontFeatureSettings("tnum");
        playbackDuration.setGravity(Gravity.END);
        times.addView(playbackTime, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(playbackDuration);
        timeline = new PlaybackSeekBar(this);
        timeline.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) { scrubbing = true; }
            @Override public void onProgressChanged(android.widget.SeekBar bar, int value, boolean user) {
                if (user) displayTime(value, bar.getMax());
            }
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {
                command("SEEK", "ms", bar.getProgress());
                scrubbing = false;
            }
        });
        timeline.setContentDescription(getString(org.proninyaroslav.libretorrent.R.string.cinema_playback_position));
        timeline.setEnabled(false);
        var seekSpacing = new LinearLayout.LayoutParams(-1, dp(48));
        seekSpacing.topMargin = dp(4);
        seekSpacing.bottomMargin = dp(6);
        seek.addView(timeline, seekSpacing);
        var tools = row(seek);
        addPlaybackTool(tools, getString(org.proninyaroslav.libretorrent.R.string.cinema_subtitles), org.proninyaroslav.libretorrent.R.drawable.cinema_control_cc, subtitles::showSubtitleSheet);
        addPlaybackTool(tools, l("مسار الصوت", "Audio Track"), org.proninyaroslav.libretorrent.R.drawable.ic_audiotrack_24, this::cycleTvAudioTrack);
        addPlaybackTool(tools, getString(org.proninyaroslav.libretorrent.R.string.cinema_volume), org.proninyaroslav.libretorrent.R.drawable.cinema_control_volume, this::showPlaybackVolume);
        videoSpeedBtn = addPlaybackTool(tools, getString(org.proninyaroslav.libretorrent.R.string.cinema_speed), org.proninyaroslav.libretorrent.R.drawable.cinema_control_speed, this::showSpeed);
        videoRotateBtn = addPlaybackTool(tools, l("تدوير", "Rotate"), org.proninyaroslav.libretorrent.R.drawable.ic_refresh_24px, this::cycleVideoRotation);
        videoRotateBtn.setOnLongClickListener(v -> {
            showVideoRotationDialog();
            return true;
        });
        updateVideoSpeedButtonLabel();
        updateVideoRotateButtonLabel();
        videoTools = tools;
        var jumps = row(seek);
        playbackJumps = jumps;
        goLive = button("LIVE • مباشر     Go Live", () -> command("GO_LIVE", "ms", 0), false);
        goLive.setVisibility(View.GONE);
        seek.addView(goLive);
        linked(jumps, "−60s", "Rewind sixty seconds", () -> command("JUMP", "ms", -60000));
        linked(jumps, "−10s", "Rewind ten seconds", () -> command("JUMP", "ms", -10000));
        linked(jumps, "+10s", "Advance ten seconds", () -> command("JUMP", "ms", 10000));
        linked(jumps, "+60s", "Advance sixty seconds", () -> command("JUMP", "ms", 60000));
        longBack = jumps.getChildAt(0);
        longForward = jumps.getChildAt(3);
        nextEpisodeButton = button(l("الحلقة التالية ⏭", "Next Episode ⏭"), () -> command("KEY", "key", 87), false);
        nextEpisodeButton.setVisibility(View.GONE);
        var epLp = new LinearLayout.LayoutParams(-1, -2);
        epLp.topMargin = dp(8);
        seek.addView(nextEpisodeButton, epLp);
        var transport = row(seek);
        playPause = playbackIcon("", "Play", org.proninyaroslav.libretorrent.R.drawable.cinema_control_play, this::castAndResumeFromCurrentPosition, true);
        playPause.setContentDescription(getString(org.proninyaroslav.libretorrent.R.string.cinema_playback_play));
        playPause.setEnabled(false);
        linkControls.add(playPause);
        transport.addView(playPause, new LinearLayout.LayoutParams(dp(64), dp(64)));
        transport.addView(playbackIcon("", "Stop", org.proninyaroslav.libretorrent.R.drawable.cinema_control_stop,
                this::stopCasting, false), new LinearLayout.LayoutParams(dp(56), dp(56)));
    }

    private final class PlaybackScrollView extends ScrollView {
        private final Runnable onDismissSheet;
        private final java.util.function.Supplier<View> volCardSup;
        private final java.util.function.Supplier<android.widget.SeekBar> timelineSup;
        private final java.util.function.Supplier<android.widget.SeekBar> volSliderSup;
        private float startX, startY;
        private boolean dragging;
        private boolean volumeGesture;
        private boolean sliderGesture;

        PlaybackScrollView(Runnable onDismissSheet,
                           java.util.function.Supplier<View> volCardSup,
                           java.util.function.Supplier<android.widget.SeekBar> timelineSup,
                           java.util.function.Supplier<android.widget.SeekBar> volSliderSup) {
            super(TvHubActivity.this);
            this.onDismissSheet = onDismissSheet;
            this.volCardSup = volCardSup;
            this.timelineSup = timelineSup;
            this.volSliderSup = volSliderSup;
        }

        @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
            float dy = event.getRawY() - startY;
            View vCard = volCardSup.get();
            android.widget.SeekBar tl = timelineSup.get();
            android.widget.SeekBar vs = volSliderSup.get();
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.getRawX();
                    startY = event.getRawY();
                    dragging = false;
                    volumeGesture = vCard != null && vCard.isShown();
                    var bounds = new android.graphics.Rect();
                    sliderGesture = (vs != null && vs.isShown() && vs.getGlobalVisibleRect(bounds)
                            && bounds.contains((int) startX, (int) startY))
                            || (tl != null && tl.getGlobalVisibleRect(bounds)
                            && bounds.contains((int) startX, (int) startY));
                }
                case android.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging && !sliderGesture && (volumeGesture || event.getY() < getHeight() * .70f) && getScrollY() == 0
                            && dy > dp(18) && dy > Math.abs(event.getRawX() - startX) * 1.5f) {
                        var cancel = android.view.MotionEvent.obtain(event);
                        cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel);
                        cancel.recycle();
                        dragging = true;
                    }
                    if (dragging) {
                        if (!volumeGesture) setTranslationY(Math.max(0, dy));
                        return true;
                    }
                }
                case android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false;
                        if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP && dy > dp(95)) {
                            if (volumeGesture && vCard != null) {
                                vCard.animate().alpha(0).setDuration(130).withEndAction(() ->
                                        vCard.setVisibility(android.view.View.GONE)).start();
                                return true;
                            }
                            animate().translationY(getHeight()).setDuration(160).withEndAction(() -> {
                                onDismissSheet.run();
                                setTranslationY(0);
                            }).start();
                        } else animate().translationY(0).setDuration(120).start();
                        return true;
                    }
                }
            }
            return super.dispatchTouchEvent(event);
        }
    }

    MaterialButton playbackIcon(String text, String description, int icon, Runnable action, boolean primary) {
        var control = button(text, action, primary);
        control.setContentDescription(description);
        control.setPadding(0, 0, 0, 0);
        control.setIconResource(icon);
        control.setIconSize(dp(28));
        control.setIconPadding(0);
        control.setIconGravity(text.isEmpty() ? MaterialButton.ICON_GRAVITY_TEXT_START : MaterialButton.ICON_GRAVITY_TEXT_TOP);
        control.setIconTint(android.content.res.ColorStateList.valueOf(primary ? 0xff101113 : GOLD));
        control.setSingleLine(true);
        return control;
    }

    private MaterialButton addPlaybackTool(LinearLayout target, String title, int icon, Runnable action) {
        var control = playbackIcon(title, title, icon, action, false);
        control.setTextSize(11);
        target.addView(control, new LinearLayout.LayoutParams(0, dp(64), 1));
        return control;
    }

    private void updateVideoSpeedButtonLabel() {
        if (videoSpeedBtn == null) return;
        String base = getString(org.proninyaroslav.libretorrent.R.string.cinema_speed);
        videoSpeedBtn.setText(videoSpeedPercent == 100
                ? base
                : String.format(java.util.Locale.US, "%s×",
                        (videoSpeedPercent % 10 == 0) ? String.format(java.util.Locale.US, "%.1f", videoSpeedPercent / 100f)
                                : String.format(java.util.Locale.US, "%.2f", videoSpeedPercent / 100f)));
    }

    private void updateVideoRotateButtonLabel() {
        if (videoRotateBtn == null) return;
        videoRotateBtn.setText(manualVideoRotation == 0
                ? l("تدوير", "Rotate")
                : (manualVideoRotation + "°"));
    }

    private void cycleVideoRotation() {
        int next = (manualVideoRotation + 90) % 360;
        applyVideoRotation(next);
    }

    private void applyVideoRotation(int degrees) {
        int norm = ((degrees % 360) + 360) % 360;
        manualVideoRotation = (norm == 90 || norm == 180 || norm == 270) ? norm : 0;
        updateVideoRotateButtonLabel();
        command("ROTATE_VIDEO", "degrees", manualVideoRotation);
        notifyStatus(l("تدوير الفيديو على الشاشة: " + manualVideoRotation + "°",
                "TV video rotation: " + manualVideoRotation + "°"));
    }

    private void showVideoRotationDialog() {
        int[] angles = {0, 90, 180, 270};
        String[] labels = {
                l("0° · الاتجاه الأصلي (Auto)", "0° · Original Orientation (Auto)"),
                "90°",
                "180°",
                "270°"
        };
        int checked = 0;
        for (int i = 0; i < angles.length; i++) {
            if (angles[i] == manualVideoRotation) checked = i;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(l("تدوير الفيديو يدوياً", "Manual Video Rotation"))
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    applyVideoRotation(angles[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void sendVideoSpeedToTv(int pct, boolean remember) {
        try {
            var ws = socket;
            if (ws != null && ws.isOpen()) {
                ws.send(new org.json.JSONObject()
                        .put("type", "SPEED")
                        .put("percent", pct)
                        .put("remember", remember)
                        .toString());
            }
        } catch (Exception ignored) {}
    }

    void showSpeed() {
        int[] speeds = {50, 75, 100, 110, 125, 150, 175, 200};
        String[] labels = {"0.5×", "0.75×", "1.0×", "1.1×", "1.25×", "1.5×", "1.75×", "2.0×"};
        int currentPct = music.audioMode ? music.audioSpeedPercent : videoSpeedPercent;
        int checked = 2;
        for (int i = 0; i < speeds.length; i++) {
            if (speeds[i] == currentPct) checked = i;
        }
        var box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(4));
        var rememberSwitch = new MaterialSwitch(this);
        rememberSwitch.setText(l("حفظ كسرعة مفضلة ومزامنتها مع الشاشة",
                "Save as preferred speed & sync with TV"));
        rememberSwitch.setChecked(music.audioMode || rememberVideoSpeed);
        if (music.audioMode) {
            rememberSwitch.setVisibility(View.GONE);
        } else {
            rememberSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                rememberVideoSpeed = isChecked;
                if (!isChecked) {
                    getSharedPreferences("cinema_music", MODE_PRIVATE).edit()
                            .putBoolean("pref_remember_video_speed", false)
                            .putInt("pref_video_speed", 100)
                            .apply();
                } else {
                    music.saveAudioPrefs();
                }
                sendVideoSpeedToTv(videoSpeedPercent, rememberVideoSpeed);
                connection.sendFullSyncStateToTv(false);
            });
            box.addView(rememberSwitch, new LinearLayout.LayoutParams(-1, -2));
        }
        var builder = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(l("سرعة التشغيل (0.5× – 2.0×)", "Playback Speed (0.5× – 2.0×)"));
        if (!music.audioMode) {
            builder.setView(box);
        }
        builder.setSingleChoiceItems(labels, checked, (dialog, index) -> {
                    int pct = speeds[index];
                    if (music.audioMode) {
                        music.audioSpeedPercent = pct;
                        music.saveAudioPrefs();
                        music.applyAudioSpeed(pct);
                    } else {
                        videoSpeedPercent = pct;
                        rememberVideoSpeed = rememberSwitch.isChecked();
                        music.saveAudioPrefs();
                        updateVideoSpeedButtonLabel();
                        sendVideoSpeedToTv(pct, rememberVideoSpeed);
                        if (rememberVideoSpeed) {
                            connection.sendFullSyncStateToTv(false);
                        }
                    }
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void cycleTvAudioTrack() {
        command("CYCLE_AUDIO_TRACK", "ms", 0);
        notifyStatus(l("تم تبديل مسار الصوت على الشاشة", "Switched TV audio track"));
    }

    void showPlayback(boolean open) {
        if (open && music.audioMode) {
            music.showMusicSheet(true);
            return;
        }
        if (open && catalogChannelPlaying) return;
        playbackOpened = open;
        if (open && music.musicPlaybackOpened) {
            music.musicPlaybackOpened = false;
            if (music.musicPlaybackScreen != null) music.musicPlaybackScreen.setVisibility(android.view.View.GONE);
        }
        if (!open && returnToNavTab != 0) {
            int backTab = returnToNavTab;
            returnToNavTab = 0;
            hub.setVisibility(android.view.View.VISIBLE);
            playbackScreen.setVisibility(android.view.View.GONE);
            updateTabBottomPadding();
            PhoneModuleBridges.hostShell().openHostTabById(this, backTab);
            return;
        }
        hub.setVisibility(open ? android.view.View.GONE : android.view.View.VISIBLE);
        playbackScreen.setVisibility(open ? android.view.View.VISIBLE : android.view.View.GONE);
        if (open) {
            setPlaybackShortcutVisible(true);
            long duration = CinemaRemoteBridge.getCastDurMs();
            if (CinemaRemoteBridge.isCastActive() && duration > 0 && remoteDuration <= 0) {
                int pos = (int) Math.min(Integer.MAX_VALUE, CinemaRemoteBridge.getCastPosMs());
                int dur = (int) Math.min(Integer.MAX_VALUE, duration);
                timeline.setMax(dur);
                timeline.setProgress(pos);
                displayTime(pos, dur);
            }
            reconnectIfNeededOnForeground();
        } else {
            updateTabBottomPadding();
            if (tabLayout != null && tabLayout.getSelectedTabPosition() == LIBRARY_TAB) library.showLibrary();
        }
    }

    void cleanupPreviousTemporaryTorrent(String keepId) {
        String pendingTemp = getIntent().getBooleanExtra("torrent_cast_temporary", false)
                ? getIntent().getStringExtra("torrent_cast_id") : null;
        String old = activeTempTorrentId;
        if (old != null && !old.isEmpty() && !old.equals(keepId)) {
            activeTempTorrentId = "";
            PhoneModuleBridges.torrentCast().deleteTemporaryCast(old);
        }
        if (pendingTemp != null && !pendingTemp.isEmpty() && !pendingTemp.equals(keepId)
                && !pendingTemp.equals(old)) {
            PhoneModuleBridges.torrentCast().deleteTemporaryCast(pendingTemp);
        }
    }

    private void showPlaybackVolume() {
        if (volumeCard.isShown()) {
            hideVolumeCard();
            return;
        }
        volumeCard.animate().cancel();
        volumeCard.setAlpha(0);
        volumeCard.setVisibility(android.view.View.VISIBLE);
        volumeCard.animate().alpha(1).setDuration(150).start();
        command("PLAYBACK", "ms", 0);
    }

    private void hideVolumeCard() {
        volumeCard.animate().alpha(0).setDuration(130).withEndAction(() ->
                volumeCard.setVisibility(android.view.View.GONE)).start();
    }

    private void buildVolumeCard(LinearLayout parent) {
        volumeCard = column();
        var surface = new GradientDrawable();
        surface.setColor(0xff1d1e21);
        surface.setCornerRadius(dp(16));
        surface.setStroke(dp(1), 0x88e5b84b);
        volumeCard.setBackground(surface);
        volumeCard.setPadding(dp(8), dp(6), dp(8), dp(6));
        var placement = new LinearLayout.LayoutParams(-1, -2);
        placement.bottomMargin = dp(12);
        parent.addView(volumeCard, placement);
        var controls = row(volumeCard);
        muteButton = playbackIcon("", getString(org.proninyaroslav.libretorrent.R.string.cinema_mute), org.proninyaroslav.libretorrent.R.drawable.cinema_control_volume,
                () -> setVolume(volumeMuted && volumePercent == 0 ? lastAudibleVolume : volumePercent, !volumeMuted), false);
        controls.addView(muteButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        volumeSlider = new android.widget.SeekBar(this);
        volumeSlider.setMax(100);
        volumeSlider.setContentDescription(getString(org.proninyaroslav.libretorrent.R.string.cinema_playback_tv_volume));
        var gold = android.content.res.ColorStateList.valueOf(GOLD);
        volumeSlider.setProgressTintList(gold);
        volumeSlider.setThumbTintList(gold);
        controls.addView(volumeSlider, new LinearLayout.LayoutParams(0, dp(48), 1));
        volumeBadge = button("—", this::editVolume, true);
        volumeBadge.setContentDescription(getString(org.proninyaroslav.libretorrent.R.string.cinema_playback_tv_volume_exact));
        volumeBadge.setPadding(0, 0, 0, 0);
        controls.addView(volumeBadge, new LinearLayout.LayoutParams(dp(60), dp(48)));
        var presets = row(volumeCard);
        for (int value : new int[]{0, 25, 50, 75, 100}) {
            var preset = button(value == 0 ? getString(org.proninyaroslav.libretorrent.R.string.cinema_mute) : value + "%", () -> setVolume(value, value == 0), false);
            preset.setTextSize(12);
            preset.setSingleLine(true);
            preset.setPadding(0, 0, 0, 0);
            presets.addView(preset, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        volumeSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) {
                adjustingVolume = true;
                bar.getParent().requestDisallowInterceptTouchEvent(true);
            }
            @Override public void onProgressChanged(android.widget.SeekBar bar, int value, boolean user) {
                if (user) setVolume(value, value == 0);
            }
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {
                adjustingVolume = false;
                main.removeCallbacks(sendVolume);
                sendVolumeRequest();
            }
        });
        volumeCard.setVisibility(android.view.View.GONE);
        renderVolume();
    }

    private void editVolume() {
        if (!volumeAvailable) return;
        var body = column();
        body.setPadding(dp(16), 0, dp(16), 0);
        var input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setSelectAllOnFocus(true);
        input.setText(Integer.toString(volumeMuted ? 0 : volumePercent));
        body.addView(input);
        var adjustments = row(body);
        for (int delta : new int[]{-5, -1, 1, 5}) {
            var step = button((delta > 0 ? "+" : "") + delta + "%", () -> {
                int value = parseVolume(input, volumePercent);
                input.setText(Integer.toString(Math.max(0, Math.min(100, value + delta))));
            }, false);
            step.setTextSize(12);
            step.setPadding(0, 0, 0, 0);
            adjustments.addView(step, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        var dialog = new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(org.proninyaroslav.libretorrent.R.string.cinema_playback_volume_range)
                .setView(body).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(org.proninyaroslav.libretorrent.R.string.cinema_action_apply, null).show();
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            int value = parseVolume(input, -1);
            if (value < 0 || value > 100) {
                input.setError("Enter a number from 0 to 100");
                return;
            }
            setVolume(value, value == 0);
            dialog.dismiss();
        });
    }

    private static int parseVolume(EditText input, int fallback) {
        try { return Integer.parseInt(input.getText().toString().trim()); }
        catch (NumberFormatException error) { return fallback; }
    }

    void setVolume(int value, boolean muted) {
        if (!volumeAvailable) return;
        volumePercent = value;
        volumeMuted = muted;
        if (value > 0 && !muted) lastAudibleVolume = value;
        volumeRequestedAt = android.os.SystemClock.elapsedRealtime();
        CinemaRemoteBridge.updateVolumeState(volumePercent, volumeMuted, volumeAvailable);
        if (music.audioMode) music.saveAudioPrefs();
        renderVolume();
        main.removeCallbacks(sendVolume);
        main.postDelayed(sendVolume, 50);
    }

    void sendVolumeRequest() {
        if (!volumeAvailable || !connected || socket == null || !socket.isOpen()) return;
        volumeRequestedAt = android.os.SystemClock.elapsedRealtime();
        try {
            socket.send(new org.json.JSONObject().put("type", "SET_VOLUME").put("percentage", volumePercent)
                    .put("muted", volumeMuted).toString());
        } catch (org.json.JSONException | org.java_websocket.exceptions.WebsocketNotConnectedException error) {
            setConnected(false, getString(org.proninyaroslav.libretorrent.R.string.cinema_disconnected));
        }
    }

    private void updateVolume(org.json.JSONObject response) {
        int value = response.optInt("volume", -1);
        volumeAvailable = value >= 0 && !response.optBoolean("volumeFixed");
        if (value >= 0 && !adjustingVolume
                && android.os.SystemClock.elapsedRealtime() - volumeRequestedAt > 300) {
            volumePercent = value;
            volumeMuted = response.optBoolean("muted");
            if (value > 0 && !volumeMuted) lastAudibleVolume = value;
        }
        CinemaRemoteBridge.updateVolumeState(volumePercent, volumeMuted, volumeAvailable);
        renderVolume();
        music.syncMusicVolumeUi();
    }

    private void renderVolume() {
        if (remoteVolumeProvider != null) remoteVolumeProvider.setCurrentVolume(volumeMuted ? 0 : volumePercent);
        volumeSlider.setEnabled(volumeAvailable);
        muteButton.setEnabled(volumeAvailable);
        volumeBadge.setEnabled(volumeAvailable);
        if (!adjustingVolume) volumeSlider.setProgress(volumeMuted ? 0 : volumePercent);
        volumeBadge.setText(volumeAvailable ? (volumeMuted ? "0%" : volumePercent + "%") : "—");
        muteButton.setIconResource(volumeMuted
                ? org.proninyaroslav.libretorrent.R.drawable.cinema_control_volume_off
                : org.proninyaroslav.libretorrent.R.drawable.cinema_control_volume);
        muteButton.setContentDescription(volumeMuted ? "Unmute" : getString(org.proninyaroslav.libretorrent.R.string.cinema_mute));
        var presets = (LinearLayout) volumeCard.getChildAt(1);
        for (int i = 0; i < presets.getChildCount(); i++) presets.getChildAt(i).setEnabled(volumeAvailable);
    }

    private boolean clearingRemoteText;
    private String submittedText;
    private boolean temporaryVolumeShown;
    private final Runnable hideHardwareVolume = () -> {
        temporaryVolumeShown = false;
        if (volumeCard != null) hideVolumeCard();
    };
    private android.media.session.MediaSession remoteVolumeSession;
    private android.media.session.MediaSession castMediaSession;
    private String castKind;
    private String castTitle;
    private static final int CAST_NOTIFICATION_ID = 2002;
    android.graphics.Bitmap castArtwork;
    private android.media.VolumeProvider remoteVolumeProvider;

    void syncRemoteVolume(boolean active, boolean playing) {
        active = active && castMediaSession == null;
        if (!active) {
            if (remoteVolumeSession != null) remoteVolumeSession.release();
            remoteVolumeSession = null;
            remoteVolumeProvider = null;
            return;
        }
        if (remoteVolumeSession == null) {
            remoteVolumeSession = new android.media.session.MediaSession(this, "Cinema TV");
            remoteVolumeProvider = new android.media.VolumeProvider(
                    android.media.VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, volumeMuted ? 0 : volumePercent) {
                @Override public void onAdjustVolume(int direction) {
                    if (direction != 0) main.post(() -> {
                        if (!isDestroyed() && connected && remoteVolumeProvider == this) {
                            adjustTvVolume(Integer.signum(direction));
                        }
                    });
                }
            };
            remoteVolumeSession.setPlaybackToRemote(remoteVolumeProvider);
            remoteVolumeSession.setActive(true);
        }
        int state = playing ? android.media.session.PlaybackState.STATE_PLAYING
                : android.media.session.PlaybackState.STATE_PAUSED;
        remoteVolumeSession.setPlaybackState(new android.media.session.PlaybackState.Builder()
                .setState(state, android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        playing ? 1f : 0f).build());
    }

    private boolean routeVolumeKey(android.view.KeyEvent event) {
        int key = event.getKeyCode();
        if (key != 24 && key != 25 || !connected || socket == null || !socket.isOpen()) return false;
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) adjustTvVolume(key == 24 ? 1 : -1);
        return true;
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        return routeVolumeKey(event) || CinemaRemoteBridge.dispatchHardwareVolumeKey(this, event) || super.dispatchKeyEvent(event);
    }

    void adjustTvVolumeFromBridge(int direction) {
        adjustTvVolume(direction);
    }

    void commandFromBridge(String type, String field, Object val) {
        command(type, field, val);
    }

    void handleNotificationBridgeAction(String action) {
        switch (action) {
            case MediaPlaybackNotificationService.ACTION_PREV -> castAction("previous");
            case MediaPlaybackNotificationService.ACTION_TOGGLE -> castAction("toggle");
            case MediaPlaybackNotificationService.ACTION_NEXT -> castAction("next");
            case MediaPlaybackNotificationService.ACTION_STOP -> stopCasting();
        }
    }

    private void adjustTvVolume(int direction) {
        if (!volumeAvailable) {
            command("PLAYBACK", "ms", 0);
            return;
        }
        setVolume(Math.max(0, Math.min(100, (volumeMuted ? 0 : volumePercent) + direction * 5)), false);
        if (playbackOpened) {
            boolean revealed = volumeCard.getVisibility() != android.view.View.VISIBLE;
            if (revealed) showPlaybackVolume();
            if (revealed || temporaryVolumeShown) {
                temporaryVolumeShown = true;
                main.removeCallbacks(hideHardwareVolume);
                main.postDelayed(hideHardwareVolume, 2000);
            }
        } else {
            CinemaRemoteBridge.adjustTvVolume(this, 0, this);
        }
    }

    private void stopCasting() {
        if (music.audioMode) {
            music.stopMusicPlayback();
            return;
        }
        torrentCastGeneration++;
        pendingTorrentCast = false;
        tvPlaybackActive = false;
        tvCastPlaybackObserved = false;
        cleanupPreviousTemporaryTorrent("");
        pendingCastTitle = null;
        webVideo = null;
        if (videoServer != null) {
            videoServer.stop();
            videoServer = null;
        }
        resumableVideo = null;
        browserCastTitle = null;
        browserPause = null;
        reconnectPlay = null;
        photoPath = null;
        if (activePhotoViewer != null) activePhotoViewer.onTvPhotoEnded();
        music.stopMusicForVideo(true);
        command("STOP_CAST", "ms", 0);
        setPlaybackShortcutVisible(false);
        showPlayback(false);
        clearCastNotification();
    }

    void clearCastNotification() {
        castKind = null;
        photoPath = null;
        tvCastPlaybackObserved = false;
        setWebLive(false);
        CinemaRemoteBridge.updatePlaybackState(false, "", "", "", false, 0L, 0L, null);
        MediaPlaybackNotificationService.stop(this);
        if (castMediaSession != null) {
            castMediaSession.setActive(false);
            castMediaSession.release();
            castMediaSession = null;
        }
        ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(CAST_NOTIFICATION_ID);
        syncCastKeepAliveService();
    }

    void showCastNotification(String title, String kind) {
        castTitle = title;
        castKind = kind;
        if ("audio".equals(kind)) {
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED
                    && !getPreferences(MODE_PRIVATE).getBoolean("audio_notifications_requested", false)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("audio_notifications_requested", true).apply();
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 752);
            }
        }
        if (castArtwork == null) {
            var icon = getPackageManager().getApplicationIcon(getApplicationInfo());
            castArtwork = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, 256, 256);
            icon.draw(new android.graphics.Canvas(castArtwork));
        }
        String subtitle = "audio".equals(kind) && !music.audioArtistName.isBlank() ? music.audioArtistName : castDestination();
        long dur = "audio".equals(kind) ? music.sourceAudioDuration : remoteDuration;
        CinemaRemoteBridge.updatePlaybackState(true, kind, webLive ? "LIVE · " + title : title,
                subtitle, castPlaying, timeline != null ? timeline.getProgress() : 0L, dur, castArtwork);
        MediaPlaybackNotificationService.syncFromBridge(this);
        syncCastKeepAliveService();
    }

    boolean castPlaying;

    private String castDestination() {
        return getString(org.proninyaroslav.libretorrent.R.string.cinema_casting_to, connectedTvName);
    }

    void updateCastSession(int position, int duration, boolean playing) {
        if (castKind == null) return;
        boolean audio = "audio".equals(castKind);
        boolean stateOrTrackChanged = castPlaying != playing;
        castPlaying = playing;
        String subtitle = audio && !music.audioArtistName.isBlank() ? music.audioArtistName : castDestination();
        String title = webLive ? "LIVE · " + castTitle : castTitle;
        CinemaRemoteBridge.updatePlaybackState(true, castKind, title, subtitle, playing, position, duration, castArtwork);
        if (stateOrTrackChanged) {
            MediaPlaybackNotificationService.syncFromBridge(this);
        }
    }

    private void castAction(String action) {
        if (castKind == null) return;
        switch (action) {
            case "stop" -> stopCasting();
            case "toggle" -> {
                if ("audio".equals(castKind) || music.audioMode) music.toggleCurrentAudioPlayPause();
                else command("TOGGLE_PLAY", "ms", 0);
            }
            case "rotate" -> command("ROTATE_IMAGE", "ms", 0);
            case "previous" -> {
                if ("audio".equals(castKind) || music.audioMode) music.previousAudio();
                else command("JUMP", "ms", -10000);
            }
            case "next" -> {
                if ("audio".equals(castKind) || music.audioMode) music.advanceAudio(false);
                else command("JUMP", "ms", 10000);
            }
        }
    }

    private void addRemoteText(LinearLayout remote) {
        var inputRow = row(remote);
        var surface = new GradientDrawable();
        surface.setColor(0xff121212);
        surface.setCornerRadius(dp(24));
        surface.setStroke(dp(1), 0xff38342a);
        inputRow.setBackground(surface);
        inputRow.setPadding(dp(12), 0, dp(4), 0);
        inputRow.addView(label("⌨", 19));
        message = new EditText(this);
        message.setHint("اضغط للكتابة المباشرة...");
        message.setTextSize(14);
        message.setSingleLine(true);
        message.setSaveEnabled(false);
        message.setBackground(null);
        message.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        message.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});
        message.setEnabled(connected);
        message.setOnFocusChangeListener((v, focused) -> {
            surface.setStroke(dp(1), focused ? GOLD : 0xff38342a);
            inputRow.invalidate();
        });
        inputRow.addView(message, new LinearLayout.LayoutParams(0, dp(48), 1));
        send = button("➤", this::sendText, true);
        send.setContentDescription(getString(org.proninyaroslav.libretorrent.R.string.cinema_remote_submit_search));
        send.setPadding(0, 0, 0, 0);
        send.setCornerRadius(dp(24));
        send.setEnabled(connected);
        var erase = button("⌫", () -> {
            if (message != null && message.length() > 0) {
                int start = Math.max(0, Math.min(message.getSelectionStart(), message.getSelectionEnd()));
                int end = Math.max(start, Math.max(message.getSelectionStart(), message.getSelectionEnd()));
                if (start == end && start > 0) start = Character.offsetByCodePoints(message.getText(), start, -1);
                if (start != end) message.getText().delete(start, end);
            } else command("KEY", "key", 67);
        }, false);
        erase.setContentDescription(getString(org.proninyaroslav.libretorrent.R.string.cinema_remote_backspace));
        erase.setPadding(0, 0, 0, 0);
        inputRow.addView(erase, new LinearLayout.LayoutParams(dp(42), dp(48)));
        inputRow.addView(send, new LinearLayout.LayoutParams(dp(48), dp(48)));
        message.setOnEditorActionListener((v, id, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (enter && event.getAction() != KeyEvent.ACTION_UP) return true;
            if (!enter && id != android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                    && id != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            sendText();
            return true;
        });
        message.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (!clearingRemoteText) command("TYPE_TEXT", "text", text.toString());
            }
            @Override public void afterTextChanged(android.text.Editable text) {}
        });
    }

    private void showJoystick() {
        var remote = column();
        remote.setPadding(dp(16), dp(8), dp(16), dp(12));
        var title = label(getString(org.proninyaroslav.libretorrent.R.string.cinema_tv_touchpad), 17);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(GOLD);
        remote.addView(title);
        var header = row(remote);
        int firstControl = linkControls.size();
        linked(header, getString(org.proninyaroslav.libretorrent.R.string.cinema_apps), "TV apps", () -> command("TV_APPS", "query", ""));
        joystick = new RemoteJoystick(this, key -> {
            if (joystick.isPointer()) sendPointer("click", 0, 0);
            else if (key == -23) sendKeyHold();
            else command("KEY", "key", key);
        });
        joystick.setEnabled(connected);
        addRemoteText(remote);
        var system = row(remote);
        linked(system, getString(org.proninyaroslav.libretorrent.R.string.cinema_back), getString(org.proninyaroslav.libretorrent.R.string.cinema_back), () -> sendRemoteKey(4));
        linked(system, getString(org.proninyaroslav.libretorrent.R.string.cinema_home), "TV Home", () -> sendRemoteKey(3));
        linked(system, getString(org.proninyaroslav.libretorrent.R.string.cinema_recents), "Recent TV apps", () -> sendRemoteKey(187));
        linked(system, getString(org.proninyaroslav.libretorrent.R.string.cinema_sleep), "Lock TV screen", () -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(getString(org.proninyaroslav.libretorrent.R.string.cinema_remote_lock_confirm)).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> sendRemoteKey(223)).show());
        var audio = row(remote);
        linked(audio, getString(org.proninyaroslav.libretorrent.R.string.cinema_volume_down), "Lower volume", () -> sendRemoteKey(25));
        linked(audio, getString(org.proninyaroslav.libretorrent.R.string.cinema_mute), "Toggle TV mute", () -> sendRemoteKey(164));
        linked(audio, getString(org.proninyaroslav.libretorrent.R.string.cinema_volume_up), "Raise volume", () -> sendRemoteKey(24));
        var playback = row(remote);
        linked(playback, "−10s", "Rewind", () -> sendRemoteKey(89));
        linked(playback, getString(org.proninyaroslav.libretorrent.R.string.cinema_play_pause), "Play or pause", () -> sendRemoteKey(85));
        linked(playback, "+10s", "Fast forward", () -> sendRemoteKey(90));
        var controls = java.util.List.copyOf(linkControls.subList(firstControl, linkControls.size()));
        var stick = row(remote);
        stick.setPadding(0, dp(16), 0, dp(12));
        var pad = new android.widget.FrameLayout(this);
        stick.addView(pad, new LinearLayout.LayoutParams(-1, dp(196)));
        var padLayout = new android.widget.FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        pad.addView(joystick, padLayout);
        var hint = label("", 13);
        remote.addView(hint);
        var mode = button("", () -> {}, false);
        var pointer = button("🖱", () -> {
            boolean enabled = !joystick.isPointer();
            joystick.setPointer(enabled, (dx, dy) -> sendPointer("move", dx, dy));
            sendPointer(enabled ? "show" : "hide", 0, 0);
        }, false);
        pointer.setContentDescription(getString(org.proninyaroslav.libretorrent.R.string.cinema_remote_pointer_toggle));
        java.util.function.Consumer<Boolean> showMode = touchpad -> {
            if (joystick.isPointer()) sendPointer("hide", 0, 0);
            joystick.setPointer(false, null);
            pointer.setVisibility(touchpad ? View.VISIBLE : View.GONE);
            title.setText(touchpad ? getString(org.proninyaroslav.libretorrent.R.string.cinema_tv_touchpad) : getString(org.proninyaroslav.libretorrent.R.string.cinema_tv_d_pad));
            joystick.setTouchpad(touchpad);
            padLayout.width = touchpad ? -1 : dp(196);
            joystick.setLayoutParams(padLayout);
            mode.setText(touchpad ? getString(org.proninyaroslav.libretorrent.R.string.cinema_d_pad) : getString(org.proninyaroslav.libretorrent.R.string.cinema_touchpad));
            mode.setContentDescription(touchpad ? "Switch to Classic D-Pad" : "Switch to Smart Touchpad");
            hint.setText(touchpad ? getString(org.proninyaroslav.libretorrent.R.string.cinema_swipe_to_move_tap_for_ok_hold_for_options)
                    : getString(org.proninyaroslav.libretorrent.R.string.cinema_tap_ok_to_select_hold_an_arrow_to_repeat));
        };
        header.addView(mode);
        pointer.setPadding(0, 0, 0, 0);
        var pointerLayout = new android.widget.FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP | Gravity.END);
        pointerLayout.topMargin = dp(8);
        pointerLayout.setMarginEnd(dp(8));
        pad.addView(pointer, pointerLayout);
        showMode.accept(true);
        mode.setOnClickListener(view -> {
            showMode.accept(!joystick.isTouchpad());
        });
        var dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        dialog.setOnKeyListener((d, key, event) -> routeVolumeKey(event));
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        remote.setFocusableInTouchMode(true);
        remote.requestFocus();
        header.addView(button(getString(org.proninyaroslav.libretorrent.R.string.cinema_close), dialog::dismiss, false));
        var scroll = new androidx.core.widget.NestedScrollView(this);
        scroll.setFillViewport(true);
        dialog.getBehavior().setSkipCollapsed(true);
        scroll.addView(remote);
        dialog.setContentView(scroll);
        var sheet = (android.widget.FrameLayout) dialog.findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            var surface = new GradientDrawable();
            surface.setColor(0xff1d1e21);
            int radius = dp(20);
            surface.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
            sheet.setBackground(surface);
            dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        }
        dialog.setOnDismissListener(window -> {
            linkControls.removeAll(controls);
            if (joystick.isPointer()) sendPointer("hide", 0, 0);
            joystick = null;
            submittedText = null;
            message = null;
            send = null;
        });
        dialog.show();
    }

    private void sendPointer(String action, double dx, double dy) {
        var current = socket;
        if (!connected || current == null || !current.isOpen()) return;
        try {
            current.send(new org.json.JSONObject().put("type", "REMOTE_POINTER")
                    .put("action", action).put("dx", dx).put("dy", dy).toString());
        } catch (org.json.JSONException | org.java_websocket.exceptions.WebsocketNotConnectedException ignored) {}
    }

    LocalMediaPanel localMedia;
    private boolean pendingShare;

    private void receiveSharedMedia() {
        var intent = getIntent();
        String action = intent.getAction();
        pendingShare = Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action);
    }

    private void openSharedMedia() {
        if (!pendingShare) return;
        pendingShare = false;
        var intent = getIntent();
        String mime = intent.getType();
        if (mime == null || !mime.contains("/")) return;
        String type = mime.substring(0, mime.indexOf('/'));
        if (!java.util.List.of("video", "audio", "image").contains(type)) return;
        var files = new java.util.ArrayList<android.net.Uri>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            java.util.ArrayList<android.net.Uri> shared = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (shared != null) files.addAll(shared);
        } else {
            android.net.Uri file = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (file != null) files.add(file);
        }
        files.removeIf(uri -> uri == null || !"content".equals(uri.getScheme()));
        if (files.size() == 1) music.castLocalMedia(files.get(0), type);
        else if (!files.isEmpty()) {
            String[] names = files.stream().map(this::videoTitle).toArray(String[]::new);
            new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(getString(org.proninyaroslav.libretorrent.R.string.cinema_cast_choose_media))
                    .setItems(names, (dialog, which) -> music.castLocalMedia(files.get(which), type)).show();
        }
    }

    private void buildMedia(LinearLayout page) {
        var enabled = new MaterialSwitch(this);
        enabled.setContentDescription(getString(org.proninyaroslav.libretorrent.R.string.cinema_phone_usb_storage));
        enabled.setMinHeight(dp(48));
        localMedia = new LocalMediaPanel(this, card(page, getString(org.proninyaroslav.libretorrent.R.string.cinema_phone_usb_storage), enabled, CinemaGlassUi.Accent.MUSIC), enabled, (uri, type) -> {
            if ("image".equals(type)) openPhotos(java.util.List.of(uri), 0);
            else music.castLocalMedia(uri, type);
        });
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == LocalMediaPanel.PERMISSION_REQUEST && localMedia != null) localMedia.refresh();
    }

    void openPhotos(java.util.List<android.net.Uri> photos, int index) {
        activePhotoViewer = new PhotoCastViewer(this, photos, index);
        activePhotoViewer.show();
        syncCastKeepAliveService();
    }

    void onPhotoViewerDismissed(PhotoCastViewer viewer) {
        if (activePhotoViewer == viewer) {
            activePhotoViewer = null;
        }
        syncCastKeepAliveService();
    }

    volatile String photoPath;
    private volatile String photoTransform;

    boolean castPhoto(android.net.Uri uri) {
        if (!connected || socket == null || !socket.isOpen()) {
            notifyStatus("Connect to the TV first");
            return false;
        }
        music.stopMusicForVideo(true);
        cleanupPreviousTemporaryTorrent("");
        var peer = socket;
        activeWatchExcluded = true;
        activeWatchKind = "phone_local_media";
        activeWatchKey = null;
        showCastNotification(videoTitle(uri), "image");
        String path = "/video/" + java.util.UUID.randomUUID().toString().replace("-", "");
        photoTransform = null;
        photoPath = path;
        tvPhotoObserved = false;
        new Thread(() -> {
            try {
                byte[] bytes = PhoneVideoServer.jpeg(getContentResolver(), uri);
                if (bytes.length > 16 * 1024 * 1024) throw new java.io.IOException("Photo too large");
                for (int offset = 0; offset < bytes.length; offset += 8192) {
                    if (!path.equals(photoPath) || !peer.isOpen()) return;
                    int end = Math.min(bytes.length, offset + 8192);
                    String encoded = android.util.Base64.encodeToString(
                            bytes, offset, end - offset, android.util.Base64.NO_WRAP);
                    peer.send(new org.json.JSONObject().put("type", "PHOTO_DATA").put("path", path)
                            .put("start", offset == 0).put("end", end == bytes.length).put("data", encoded).toString());
                }
                main.post(() -> {
                    if (path.equals(photoPath) && photoTransform != null && peer.isOpen()) peer.send(photoTransform);
                });
            } catch (java.io.IOException | org.json.JSONException | RuntimeException error) {
                main.post(() -> {
                    if (!path.equals(photoPath)) return;
                    clearCastNotification();
                    notifyStatus("Could not send this photo");
                });
            }
        }, "cinema-photo-cast").start();
        return true;
    }

    void photoCommand(String type, float scale, float x, float y, float angle, boolean fill) {
        String path = photoPath;
        if (!connected || socket == null || !socket.isOpen() || path == null) return;
        try {
            String command = new org.json.JSONObject().put("type", type).put("path", path)
                    .put("scale", scale).put("offsetX", x).put("offsetY", y)
                    .put("rotation", angle).put("fill", fill).toString();
            if ("STOP_PHOTO".equals(type)) {
                photoPath = null;
                syncCastKeepAliveService();
            } else {
                photoTransform = command;
            }
            socket.send(command);
        } catch (org.json.JSONException | org.java_websocket.exceptions.WebsocketNotConnectedException ignored) {}
    }
    private android.widget.ImageView videoPoster;
    private String videoPosterUrl = "";

    private void showTvPoster(String url, String imdbId) {
        if (videoPoster == null) return;
        String fallback = imdbId.matches("tt[0-9]+")
                ? "https://images.metahub.space/poster/medium/" + imdbId + "/img" : "";
        if (!url.startsWith("https://") && !url.startsWith("http://")) url = fallback;
        if (url.equals(videoPosterUrl)) return;
        videoPosterUrl = url;
        int placeholder = org.proninyaroslav.libretorrent.R.drawable.ic_cinema_cast_play;
        var glide = com.bumptech.glide.Glide.with(this);
        if (url.isBlank()) {
            glide.clear(videoPoster);
            videoPoster.setImageResource(placeholder);
        } else {
            int width = Math.min(getResources().getDisplayMetrics().widthPixels, 720);
            int height = width * 3 / 2;
            var request = glide.load(url).override(width, height).fitCenter().placeholder(placeholder);
            if (fallback.isBlank() || fallback.equals(url)) request.error(placeholder);
            else request.error(glide.load(fallback).override(width, height).fitCenter().error(placeholder));
            request.into(videoPoster);
        }
    }
    private MaterialButton nextEpisodeButton;
    private android.view.View videoTools, longBack, longForward;

    android.net.Uri activeAudioUri() { return music.activeAudioUri(); }
    boolean isAudioPlaying() { return music.isAudioPlaying(); }
    void openAudio(java.util.List<LocalMediaPanel.Item> tracks, int index) { music.openAudio(tracks, index); }
    void toggleOrOpenAudio(java.util.List<LocalMediaPanel.Item> tracks, int index) { music.toggleOrOpenAudio(tracks, index); }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        var scan = IntentIntegrator.parseActivityResult(request, result, data);
        if (scan == null) {
            super.onActivityResult(request, result, data);
        } else if (scanningPairing) {
            scanningPairing = false;
            String contents = scan.getContents();
            if (contents != null && !contents.isBlank()) {
                suppressNextResumeReconnect = false;
                connect(contents, true, false);
            } else {
                // Dismissing the QR scanner without a scan must NOT trigger a reconnect side-effect.
                suppressNextResumeReconnect = true;
                main.removeCallbacks(reconnect);
            }
        }
    }

    private static boolean isGooglevideoUrl(String url) {
        if (url == null) return false;
        String host = android.net.Uri.parse(url).getHost();
        return host != null && (host.equalsIgnoreCase("googlevideo.com")
                || host.toLowerCase(java.util.Locale.ROOT).endsWith(".googlevideo.com"));
    }

    private String maybeProxyUrl(String targetUrl, String referer, String origin, String ua,
                                 String cookie, String auth, boolean isLive) {
        if (targetUrl == null || targetUrl.isBlank()) return targetUrl;
        String lower = targetUrl.toLowerCase(java.util.Locale.ROOT);
        boolean isGoogle = isGooglevideoUrl(targetUrl);
        boolean needsProxy = isGoogle
                || targetUrl.startsWith("http://")
                || (referer != null && !referer.isBlank())
                || (origin != null && !origin.isBlank())
                || (ua != null && !ua.isBlank())
                || (cookie != null && !cookie.isBlank())
                || (auth != null && !auth.isBlank())
                || lower.contains(".m3u8")
                || lower.contains(".mpd");
        if (!needsProxy || socket == null || !socket.isOpen()) return targetUrl;
        try {
            String host = ensurePhoneVideoServerHost();
            if (host == null) return targetUrl;
            String proxyPath = PhoneVideoServer.registerProxySession(targetUrl, referer, origin, ua, cookie, auth, isLive);
            if (proxyPath != null) return "http://" + host + ":8766" + proxyPath;
        } catch (Exception e) {
            android.util.Log.w("CinemaProxy", "Fallback direct URL for web cast", e);
        }
        return targetUrl;
    }

    private String ensurePhoneVideoServerHost() throws java.io.IOException {
        if (socket == null || !socket.isOpen()) return null;
        String host = socket.getLocalSocketAddress().getAddress().getHostAddress();
        if (videoServer == null || !videoServer.isAlive()) {
            if (videoServer != null) videoServer.stop();
            videoServer = new PhoneVideoServer(host, null, null);
            videoServer.start();
        }
        return host;
    }

    String resolveSubtitleUrlForCast(String url, String memPath, String ref, String origin,
                                     String ua, String cookie, String auth) {
        if (memPath != null && !memPath.isBlank()) {
            try {
                String host = ensurePhoneVideoServerHost();
                if (host != null) return "http://" + host + ":8766" + memPath;
            } catch (Exception e) {
                android.util.Log.w("CinemaProxy", "Failed to host in-memory subtitle", e);
            }
        }
        if (url == null || url.isBlank()) return "";
        return maybeProxyUrl(url, ref, origin, ua, cookie, auth, false);
    }

    private void readWebCastExtras(Intent intent) {
        webAudio = intent.getStringExtra("web_audio");
        webFormat = intent.getStringExtra("web_format");
        webSubtitle = intent.getStringExtra("web_subtitle");
        webSubtitleMime = intent.getStringExtra("web_sub_mime");
        webSubtitleLang = intent.getStringExtra("web_sub_lang");
        webSubtitleMemPath = intent.getStringExtra("web_sub_mem");
        webSubtitleReferer = intent.getStringExtra("web_sub_ref");
        webSubtitleOrigin = intent.getStringExtra("web_sub_origin");
        webSubtitleUserAgent = intent.getStringExtra("web_sub_ua");
        webSubtitleCookie = intent.getStringExtra("web_sub_cookie");
        webSubtitleAuthorization = intent.getStringExtra("web_sub_auth");
        webSecondarySubtitle = intent.getStringExtra("web_sub2");
        webSecondarySubtitleMime = intent.getStringExtra("web_sub2_mime");
        webSecondarySubtitleLang = intent.getStringExtra("web_sub2_lang");
        webSecondarySubtitleMemPath = intent.getStringExtra("web_sub2_mem");
        webSecondarySubtitleReferer = intent.getStringExtra("web_sub2_ref");
        webSecondarySubtitleOrigin = intent.getStringExtra("web_sub2_origin");
        webSecondarySubtitleUserAgent = intent.getStringExtra("web_sub2_ua");
        webSecondarySubtitleCookie = intent.getStringExtra("web_sub2_cookie");
        webSecondarySubtitleAuthorization = intent.getStringExtra("web_sub2_auth");
        webReferer = intent.getStringExtra("web_referer");
        webOrigin = intent.getStringExtra("web_origin");
        webUserAgent = intent.getStringExtra("web_ua");
        webCookie = intent.getStringExtra("web_cookie");
        webAuthorization = intent.getStringExtra("web_auth");
        webCastLive = intent.getBooleanExtra("web_live", false);
        webSuppressAutoSubs = intent.getBooleanExtra("web_suppress_auto_subs", false);
        setWebLive(webCastLive);
        webHeight = intent.getIntExtra("web_height", 0);
        returnToNavTab = intent.getIntExtra("return_to_nav_tab", 0);
        subtitles.resetForWebCast(webSubtitle, webSubtitleMemPath,
                webSecondarySubtitle, webSecondarySubtitleMemPath,
                intent.getStringExtra("web_sub_catalog"));
    }

    static String firstNonBlank(String primary, String fallback) {
        return (primary != null && !primary.isBlank()) ? primary : fallback;
    }

    private void dispatchWebCast() {
        if (webVideo == null || socket == null || !socket.isOpen()) return;
        music.stopMusicForVideo(true);
        cleanupPreviousTemporaryTorrent("");
        try {
            activeWatchExcluded = true;
            activeWatchKind = "web";
            activeWatchKey = "web:" + webVideo;
            activeWatchTitle = browserCastTitle == null ? "Web Video" : browserCastTitle;
            activeWatchUri = webVideo;
            activeWatchTorrentId = "";
            activeWatchFileIndex = 0;
            activeWatchFormat = webFormat == null ? "" : webFormat;
            activeWatchImdbId = "";
            activeWatchTmdbId = 0L;
            activeWatchSeason = 0;
            activeWatchEpisode = 0;
            String castId = java.util.UUID.randomUUID().toString();
            activeCastId = castId;
            var savedWeb = PhoneWatchStore.findBestEntry(TvHubActivity.this, activeWatchKey,
                    "movie", activeWatchImdbId, activeWatchTmdbId, 0, 0, activeWatchTitle);
            if (savedWeb != null) {
                if (activeWatchImdbId.isEmpty() && savedWeb.imdbId != null) activeWatchImdbId = savedWeb.imdbId;
                if (activeWatchTmdbId <= 0) activeWatchTmdbId = savedWeb.tmdbId;
            }
            long webResume = (webCastLive || savedWeb == null || savedWeb.watched) ? 0L : savedWeb.positionMs;
            String castUrl, castAudioUrl, castSubUrl, castSub2Url;
            castUrl = maybeProxyUrl(webVideo, webReferer, webOrigin, webUserAgent, webCookie, webAuthorization, webCastLive);
            castAudioUrl = (webAudio == null || webAudio.isBlank()) ? ""
                    : maybeProxyUrl(webAudio, webReferer, webOrigin, webUserAgent, webCookie, webAuthorization, webCastLive);
            castSubUrl = resolveSubtitleUrlForCast(
                    webSubtitle, webSubtitleMemPath,
                    firstNonBlank(webSubtitleReferer, webReferer),
                    firstNonBlank(webSubtitleOrigin, webOrigin),
                    firstNonBlank(webSubtitleUserAgent, webUserAgent),
                    firstNonBlank(webSubtitleCookie, webCookie),
                    firstNonBlank(webSubtitleAuthorization, webAuthorization));
            castSub2Url = resolveSubtitleUrlForCast(
                    webSecondarySubtitle, webSecondarySubtitleMemPath,
                    firstNonBlank(webSecondarySubtitleReferer, webReferer),
                    firstNonBlank(webSecondarySubtitleOrigin, webOrigin),
                    firstNonBlank(webSecondarySubtitleUserAgent, webUserAgent),
                    firstNonBlank(webSecondarySubtitleCookie, webCookie),
                    firstNonBlank(webSecondarySubtitleAuthorization, webAuthorization));
            String castPayload = new org.json.JSONObject().put("type", "CAST_WEB")
                    .put("url", castUrl)
                    .put("audio_url", castAudioUrl)
                    .put("subtitle_url", castSubUrl)
                    .put("subtitle_mime", webSubtitleMime != null ? webSubtitleMime : "")
                    .put("subtitle_language", webSubtitleLang != null ? webSubtitleLang : "")
                    .put("secondary_subtitle_url", castSub2Url)
                    .put("secondary_subtitle_mime", webSecondarySubtitleMime != null ? webSecondarySubtitleMime : "")
                    .put("secondary_subtitle_language", webSecondarySubtitleLang != null ? webSecondarySubtitleLang : "")
                    .put("suppress_auto_subtitles", webSuppressAutoSubs)
                    .put("format", webFormat).put("height", webHeight)
                    .put("is_live", webCastLive)
                    .put("exclude_from_history", true)
                    .put("source_type", "web")
                    .put("cast_id", castId)
                    .put("title", activeWatchTitle)
                    .put("stream_key", activeWatchKey)
                    .put("media_type", "movie")
                    .put("imdb_id", activeWatchImdbId)
                    .put("tmdb_id", activeWatchTmdbId)
                    .put("season", 0)
                    .put("episode", 0)
                    .put("resume_ms", webResume).toString();
            if (safeSend(socket, castPayload)) {
                castStartedAtMs = android.os.SystemClock.elapsedRealtime();
                tvCastPlaybackObserved = false;
                syncCastKeepAliveService();
                webVideo = null;
                webSubtitle = null;
                webSubtitleMemPath = null;
                webSecondarySubtitle = null;
                webSecondarySubtitleMemPath = null;
            } else {
                notifyStatus(l("جاري إعادة الاتصال بالشاشة للبث...", "Reconnecting to TV to cast..."));
            }
        } catch (org.json.JSONException e) {
            notifyStatus(l("تعذّر تجهيز بيانات البث", "Could not prepare web cast"));
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if ("com.meedx.localcinema.manager.STOP_CAST_NOTIFICATION".equals(intent.getAction())) stopCasting();
        else if ("com.meedx.localcinema.manager.OPEN_CAST_CONTROLS".equals(intent.getAction())) {
            if (music.audioMode) music.showMusicSheet(true);
            else if (castKind != null && !"image".equals(castKind)) showPlayback(true);
        }
        if (intent.getBooleanExtra("start_qr_scan", false)) {
            intent.removeExtra("start_qr_scan");
            pendingQrScanLaunch = true;
            suppressNextResumeReconnect = true;
            main.removeCallbacks(reconnect);
            main.post(connection::scanTv);
        }
        if (intent.getBooleanExtra("open_playback_controls", false)) {
            intent.removeExtra("open_playback_controls");
            if (intent.hasExtra("return_to_nav_tab")) {
                returnToNavTab = intent.getIntExtra("return_to_nav_tab", 0);
            }
            if (music.audioMode) music.showMusicSheet(true);
            else showPlayback(true);
        }
        if (intent.hasExtra("web_video")) {
            webVideo = intent.getStringExtra("web_video");
            readWebCastExtras(intent);
            browserCastTitle = cleanCastTitle(intent.getStringExtra("web_title"));
            String shownTitle = browserCastTitle.isBlank() ? l("البث على الشاشة", "Video on TV") : browserCastTitle;
            playbackTitle.setText(shownTitle);
            if (playbackShortcutTitle != null) playbackShortcutTitle.setText(shownTitle);
            subtitles.subtitleTitle = browserCastTitle;
            showPlayback(true);
            if (connected && socket != null && socket.isOpen()) dispatchWebCast();
        } else if (intent.hasExtra("torrent_cast_id")) {
            returnToNavTab = intent.getIntExtra("return_to_nav_tab", 0);
        }
        if (intent.getBooleanExtra("open_subtitle_picker", false)) {
            intent.removeExtra("open_subtitle_picker");
            String subHint = intent.getStringExtra("subtitle_title");
            if (subHint != null && !subHint.isBlank()) subtitles.subtitleTitle = subHint;
            main.post(this::launchSubtitlePicker);
        }
        handleLocalVideoIntent(intent);
        handleBrowserTorrentCastIntent(intent);
        connection.handleTraktPkceIntent(intent);
        if (connection.handleAddonIntent(intent) && tabLayout != null) tabLayout.selectTab(tabLayout.getTabAt(CONNECTION_TAB));
        handleSyncSettingsIntent(intent);
    }

    private void handleBrowserTorrentCastIntent(Intent intent) {
        if (intent == null) return;
        String magnet = intent.getStringExtra("browser_magnet_cast");
        String torUrl = intent.getStringExtra("browser_torrent_url_cast");
        if ((magnet == null || magnet.isBlank()) && (torUrl == null || torUrl.isBlank())) return;
        String title = intent.getStringExtra("browser_torrent_title");
        String referer = intent.getStringExtra("browser_torrent_referer");
        String ua = intent.getStringExtra("browser_torrent_ua");
        String cookie = intent.getStringExtra("browser_torrent_cookie");
        intent.removeExtra("browser_magnet_cast");
        intent.removeExtra("browser_torrent_url_cast");
        intent.removeExtra("browser_torrent_title");
        intent.removeExtra("browser_torrent_referer");
        intent.removeExtra("browser_torrent_ua");
        intent.removeExtra("browser_torrent_cookie");
        if (intent.hasExtra("return_to_nav_tab")) {
            returnToNavTab = intent.getIntExtra("return_to_nav_tab", 0);
        }
        String shownTitle = (title != null && !title.isBlank()) ? title : l("بث تورنت على الشاشة", "Torrent on TV");
        pendingCastTitle = shownTitle;
        playbackTitle.setText(l("جارٍ تحضير بث التورنت للشاشة…", "Preparing torrent stream for TV…"));
        showPlayback(true);
        Runnable startTask = () -> watchSyncIo.execute(() -> {
            String resolvedMagnet = (magnet != null && magnet.startsWith("magnet:?")) ? magnet.trim() : "";
            if (resolvedMagnet.isEmpty() && torUrl != null && torUrl.startsWith("http")) {
                java.io.File fetched = PhoneModuleBridges.browser().fetchTorrentFileWithHeaders(
                        TvHubActivity.this, torUrl, referer, ua, cookie);
                if (fetched != null && fetched.isFile() && fetched.length() > 32) {
                    resolvedMagnet = PhoneModuleBridges.torrentCast().parseTorrentFileToMagnet(fetched);
                }
            }
            final String finalMagnet = resolvedMagnet;
            main.post(() -> {
                if (isDestroyed()) return;
                if (finalMagnet == null || !com.meedx.providers.StremioAddons.isTorrentMagnet(finalMagnet)) {
                    notifyStatus(getString(org.proninyaroslav.libretorrent.R.string.addon_torrent_failed));
                    return;
                }
                playAddonTorrent(finalMagnet, -1, shownTitle, "", 0, 0);
            });
        });
        if (connected && socket != null && socket.isOpen()) {
            startTask.run();
        } else if (hasValidPairedTvEndpoint()) {
            reconnectPlay = startTask;
            reconnectIfNeededOnForeground();
        } else {
            notifyStatus(l("امسح رمز QR الخاص بالشاشة أولاً للبث", "Scan the TV pairing QR first"));
        }
    }

    private void handleLocalVideoIntent(Intent intent) {
        if (intent == null || !intent.hasExtra("local_video_path")) return;
        String localPath = intent.getStringExtra("local_video_path");
        String title = intent.getStringExtra("web_title");
        intent.removeExtra("local_video_path");
        if (localPath == null || localPath.isBlank()) return;
        if (localPath.startsWith("content://")) {
            var uri = android.net.Uri.parse(localPath);
            if (connected && socket != null && socket.isOpen()) {
                castVideo(uri, -1, true);
            } else {
                notifyStatus(l("الشاشة غير متصلة الآن", "TV is not connected right now"));
            }
            return;
        }
        java.io.File file = new java.io.File(localPath);
        if (!file.isFile()) {
            notifyStatus(l("الملف غير موجود على الجهاز:\n", "File no longer exists on device:\n") + localPath);
            return;
        }
        var uri = android.net.Uri.fromFile(file);
        boolean isLibraryFile = localPath.toLowerCase(java.util.Locale.ROOT).contains("/local cinema/");
        if (connected && socket != null && socket.isOpen()) {
            castVideo(uri, -1, !isLibraryFile);
        } else {
            CinemaSnackBanner.show(this,
                    l("الشاشة غير متصلة الآن — يمكنك تشغيل الفيديو على الهاتف أو مسح QR للبث: ",
                            "TV is not connected right now — play on phone or pair TV to cast: ")
                            + (title != null ? title : file.getName()),
                    l("تشغيل هنا", "Play on Phone"),
                    () -> {
                        try {
                            android.net.Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                                    this, getPackageName() + ".provider", file);
                            Intent play = new Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(contentUri, "video/*")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(play);
                        } catch (Exception e) {
                            notifyStatus(l("تعذّر فتح مشغل الفيديو الخارجي.", "No external video player found."));
                        }
                    });
        }
    }

    void connect(String payload, boolean scanned) {
        connect(payload, scanned, false);
    }

    void connect(String payload, boolean scanned, boolean lightweightReconnect) {
        if (isDestroyed()) return;
        try {
            if (payload.trim().startsWith("ws://")) {
                var endpoint = java.net.URI.create(payload.trim());
                if (endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                        || (!endpoint.getPath().isEmpty() && !"/".equals(endpoint.getPath()))) {
                    throw new IllegalArgumentException();
                }
                String saved = getSharedPreferences("cinema_link", 0).getString("pairing", null);
                if (saved == null) {
                    notifyStatus("Scan the full TV pairing QR first");
                    return;
                }
                var known = new org.json.JSONObject(saved);
                if (!known.optString("ip", known.optString("tv_ip")).equals(endpoint.getHost())
                        || known.optInt("port", known.optInt("ws_port")) != endpoint.getPort()) {
                    notifyStatus("Scan the full QR for this TV");
                    return;
                }
                payload = saved;
            }
            final String pairingPayload = payload;
            var config = new org.json.JSONObject(pairingPayload);
            String host = config.optString("ip", config.optString("tv_ip"));
            int port = config.optInt("port", config.optInt("ws_port"));
            if (config.has("app") && !"LocalCinemaTV".equals(config.optString("app"))) throw new IllegalArgumentException();
            if (!host.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}") ||
                    !java.net.InetAddress.getByName(host).isSiteLocalAddress() ||
                    (port < 1024 || port > 65535)) throw new IllegalArgumentException();
            String token = config.getString("token");
            if (android.util.Base64.decode(token, android.util.Base64.URL_SAFE).length != 32) {
                throw new IllegalArgumentException();
            }
            resetPairingAttempt();
            idleSleeping = false;
            if (scanned) pendingPairing = pairingPayload;
            reconnecting = lightweightReconnect;
            setConnected(false, lightweightReconnect ? l("إعادة الاتصال…", "Reconnecting…")
                    : getString(org.proninyaroslav.libretorrent.R.string.cinema_connecting_to_tv));
            var preferences = getSharedPreferences("cinema_link", 0);
            String phoneId = preferences.getString("phone_id", null);
            if (phoneId == null) {
                phoneId = java.util.UUID.randomUUID().toString();
                preferences.edit().putString("phone_id", phoneId).apply();
            }
            String model = android.os.Build.MODEL;
            String name = model.startsWith("SM-S938") ? "Samsung Galaxy S25 Ultra" : model;
            var helloObj = new org.json.JSONObject().put("type", "REGISTER_PHONE").put("token", token)
                    .put("phone_id", phoneId).put("phone_name", name).put("scanned", scanned
                            || !config.has("app"));
            if (lightweightReconnect) {
                helloObj.put("lightweight_reconnect", true);
            }
            String hello = helloObj.toString();
            var client = new org.java_websocket.client.WebSocketClient(java.net.URI.create("ws://" + host + ":" + port), new org.java_websocket.drafts.Draft_6455(), null, 5000) {
                private Exception connectionError;

                @Override public void onOpen(org.java_websocket.handshake.ServerHandshake response) {
                    send(hello);
                }
                @Override public void onMessage(String message) {
                    main.post(() -> {
                        if (socket != this || isDestroyed()) return;
                        try {
                            var response = new org.json.JSONObject(message);
                            switch (response.getString("type")) {
                                case "CONNECTED" -> {
                                    clearPairingTimeout();
                                    pendingPairing = null;
                                    reconnecting = false;
                                    reconnectAttempts = 0;
                                    android.util.Log.i("CinemaPairing", "Connected to " + host + ":" + port);
                                    String tvName = response.optString("name", "TV");
                                    boolean controller = response.optBoolean("controller", true);
                                    connectedTvName = tvName;
                                    setConnected(true, getString(org.proninyaroslav.libretorrent.R.string.cinema_connected_to, tvName));
                                    CinemaRemoteBridge.updateRemoteCapabilities(
                                            response.optBoolean("adb_enabled", false),
                                            response.optBoolean("web_remote_active", true));
                                    if (controller
                                            && !response.optBoolean("accessibility_enabled", true)
                                            && !response.optBoolean("adb_enabled", false)) {
                                        remoteAccessibilityNotice();
                                    }
                                    var settings = getSharedPreferences("cinema_link", MODE_PRIVATE);
                                    if (!lightweightReconnect && settings.contains("audio_hud_timeout"))
                                        command("SET_AUDIO_HUD_TIMEOUT", "timeout_ms", settings.getInt("audio_hud_timeout", 5000));
                                    if (!lightweightReconnect && !userSelectedTab && webVideo == null) {
                                        tabLayout.selectTab(tabLayout.getTabAt(LIBRARY_TAB));
                                    }
                                    var linkPrefs = getSharedPreferences("cinema_link", 0);
                                    boolean newPairing = !String.valueOf(pairingPayload).equals(linkPrefs.getString("pairing", ""));
                                    if (newPairing) {
                                        connection.markFullSyncDirty(true); // new/different TV needs the full state once
                                    }
                                    linkPrefs.edit().putString("pairing", pairingPayload).apply();
                                    if (!lightweightReconnect) library.syncPublishedFolders();
                                    else library.resumePublishedFolders();
                                    LocalCinemaBackupManager.onTvHandshakeConnected(
                                            TvHubActivity.this,
                                            this,
                                            response.optLong("tv_settings_updated_at", 0L),
                                            response.optString("tv_settings_hash", ""));
                                    Runnable deferredPlay = reconnectPlay;
                                    reconnectPlay = null;
                                    if (deferredPlay != null) deferredPlay.run();
                                    openSharedMedia();
                                    if (pendingTorrentCast) prepareTorrentCast();
                                    if (webVideo != null) dispatchWebCast();
                                }
                                case "WATCH_SYNC" -> { /* TV is authoritative for watch/Trakt lists; Phone does not cache them */ }
                                case "ADDONS_SYNC" -> connection.handleAddonsSync(response.optJSONArray("addons"));
                                case "TV_ADDONS_LIST" -> connection.handleTvAddonsList(response.optJSONArray("addons"));
                                case "REQUEST_ADDONS_SYNC" -> connection.sendAddonsSyncSnapshot();
                                case "ADDONS_ACK" -> connection.onAddonsAck(response.optInt("count"));
                                case "ADDON_TV_INSTALLED" -> connection.onAddonTvInstalled(response);
                                case "ADDON_ENABLED" -> connection.onTvAddonEnabled(response.optString("manifest_url"),
                                        response.optBoolean("enabled"));
                                case "ADDON_REMOVED" -> connection.onTvAddonRemoved(response.optString("manifest_url"));
                                case "PLAY_ADDON_TORRENT" -> playAddonTorrent(response.optString("magnet"),
                                        response.optInt("file_index", -1), response.optString("title"),
                                        response.optString("imdb_id"), response.optInt("season"), response.optInt("episode"));
                                case "API_KEYS_SYNC" -> connection.handleApiKeysSyncFromTv(response);
                                case "SUBTITLE_CONFIG_SYNC" -> connection.handleSubtitleConfigSyncFromTv(response);
                                case "FULL_SYNC_ACK" -> connection.onFullSyncAck(response);
                                case com.meedx.providers.LiveChannelFavorites.PACKET ->
                                        connection.onFavoriteChannels(response.optJSONObject(com.meedx.providers.LiveChannelFavorites.FIELD));
                                case com.meedx.providers.LiveTvPreferences.PREFS_PACKET ->
                                        connection.onLiveTvPreferencesSynced(response);
                                case "TV_SETTINGS_CHANGED" -> LocalCinemaBackupManager.onTvSettingsChangedNotification(
                                        TvHubActivity.this,
                                        this,
                                        response.optLong("tv_settings_updated_at", 0L),
                                        response.optString("tv_settings_hash", ""));
                                case "TV_BACKUP_DATA" -> connection.onTvBackupData(this, response);
                                case "TV_BACKUP_RESTORED" -> connection.onTvBackupRestored(response);
                                case "REMOTE_STATUS" -> CinemaRemoteBridge.updateRemoteCapabilities(
                                        response.optBoolean("adb_enabled", false),
                                        response.optBoolean("web_remote_active", true));
                                case "REQUEST_FULL_SYNC" -> connection.sendFullSyncStateToTv(false);
                                case "TRACK_ENDED" -> music.onAudioEnded(response.optString("path"));
                                case "PLAYBACK" -> {
                                    clearStatusDeadline();
                                    updateVolume(response);
                                    boolean musicMsg = response.optBoolean("audio");
                                    if (musicMsg) {
                                        music.onPlaybackAudioMessage(response);
                                        break;
                                    }
                                    boolean tvVideo = response.optBoolean("active",
                                            response.optInt("durationMs", response.optInt("duration")) > 0);
                                    boolean wasCatalogChannel = catalogChannelPlaying;
                                    catalogChannelPlaying = response.optBoolean("live_channel");
                                    if (music.audioMode && !tvVideo) break;
                                    if (music.audioMode && tvVideo) {
                                        boolean replaceFullPlayer = music.musicPlaybackOpened;
                                        music.stopMusicForVideo(true);
                                        castKind = null;
                                        if (replaceFullPlayer) showPlayback(true);
                                    }
                                    if (catalogChannelPlaying) {
                                        if (!wasCatalogChannel) {
                                            tvPlaybackActive = false;
                                            remoteDuration = 0;
                                            setPlaybackShortcutVisible(false);
                                            if ("video".equals(castKind)) clearCastNotification();
                                            if (playbackOpened) showPlayback(false);
                                        }
                                        break;
                                    }
                                    if ("image".equals(castKind)) {
                                        if (response.optBoolean("photo")) tvPhotoObserved = true;
                                        else if (tvPhotoObserved) {
                                            if (activePhotoViewer != null) activePhotoViewer.onTvPhotoEnded();
                                            clearCastNotification();
                                        }
                                    }
                                    int duration = response.optInt("durationMs", response.optInt("duration"));
                                    setWebLive(response.optBoolean("live"));
                                    boolean playing = response.optBoolean("isPlaying", response.optBoolean("playing"));
                                    int pos = response.optInt("positionMs", response.optInt("ms"));
                                    boolean activeVideo = response.optBoolean("active", duration > 0 || playing || webLive);
                                    boolean prevTvActive = tvPlaybackActive;
                                    tvPlaybackActive = activeVideo;
                                    if (activeVideo && "video".equals(castKind)) {
                                        tvCastPlaybackObserved = true;
                                    } else if (!activeVideo && "video".equals(castKind) && !pendingTorrentCast && webVideo == null
                                            && (tvCastPlaybackObserved || android.os.SystemClock.elapsedRealtime() - castStartedAtMs > 15000L)) {
                                        tvCastPlaybackObserved = false;
                                        if (videoServer != null) {
                                            videoServer.stop();
                                            videoServer = null;
                                        }
                                        cleanupPreviousTemporaryTorrent("");
                                        browserCastTitle = null;
                                        pendingCastTitle = null;
                                        setPlaybackShortcutVisible(false);
                                        clearCastNotification();
                                    } else if (prevTvActive != tvPlaybackActive) {
                                        syncCastKeepAliveService();
                                    }
                                    int tvSpeedPct = response.optInt("speed_percent", 0);
                                    if (tvSpeedPct >= 50 && tvSpeedPct <= 200 && tvSpeedPct != videoSpeedPercent) {
                                        videoSpeedPercent = tvSpeedPct;
                                        updateVideoSpeedButtonLabel();
                                    }
                                    int tvRotDeg = response.optInt("rotation_degrees", -1);
                                    if (tvRotDeg >= 0 && tvRotDeg != manualVideoRotation) {
                                        manualVideoRotation = tvRotDeg;
                                        updateVideoRotateButtonLabel();
                                    }
                                    if (activeVideo && duration <= 0 && prevTvActive) {
                                        duration = remoteDuration;
                                        if (pos <= 0) pos = timeline.getProgress();
                                    }
                                    remoteDuration = duration;
                                    if (duration > 0 && response.optString("path").equals(library.libraryPlaybackPath))
                                        lastCastPosition = pos;
                                    rememberPlaybackState(response, duration);
                                    String rawTitle = response.optString("title").trim();
                                    if (rawTitle.matches("(?i)[a-f0-9]{32}")) rawTitle = "";
                                    String title = cleanCastTitle(rawTitle);
                                    subtitles.updateTvSubtitleTracks(response.optJSONArray("subtitle_tracks"));
                                    boolean localCast = library.libraryPlaybackPath != null
                                            && library.libraryPlaybackPath.equals(response.optString("path"));
                                    if (library.libraryPlaybackPath != null && activeVideo && !localCast
                                            && pendingCastTitle == null) library.libraryPlaybackKey = null;
                                    timeline.setEnabled(duration > 0);
                                    if (!"image".equals(castKind) && (duration > 0 || webLive))
                                        updateCastSession(pos, duration, playing);
                                    syncRemoteVolume(duration > 0 && volumeAvailable, playing);
                                    playPause.setIconResource(playing
                                            ? org.proninyaroslav.libretorrent.R.drawable.cinema_control_pause
                                            : org.proninyaroslav.libretorrent.R.drawable.cinema_control_play);
                                    playPause.setContentDescription(playing ? "Pause" : "Play");
                                    playPause.setEnabled(playing || duration > 0 || resumableVideo != null);
                                    if (activeVideo) {
                                        String tvTitle = title.isBlank() ? l("البث على الشاشة", "Video on TV") : title;
                                        if (!"video".equals(castKind) || (!title.isBlank() && !tvTitle.equals(castTitle))) {
                                            castPlaying = playing;
                                            showCastNotification(tvTitle, "video");
                                            updateCastSession(pos, duration, playing);
                                            MediaPlaybackNotificationService.syncFromBridge(TvHubActivity.this);
                                        }
                                        setPlaybackShortcutVisible(true);
                                    }
                                    else if (prevTvActive && resumableVideo == null && castKind == null) setPlaybackShortcutVisible(false);
                                    if (activeVideo && !pendingTorrentCast) {
                                        if (localCast && pendingCastTitle != null) {
                                            title = cleanCastTitle(pendingCastTitle);
                                            pendingCastTitle = null;
                                        } else if (localCast && castTitle != null) {
                                            String cc = cleanCastTitle(castTitle);
                                            if (!cc.isBlank()) title = cc;
                                        } else if (title.isEmpty() && browserCastTitle != null) {
                                            title = cleanCastTitle(browserCastTitle);
                                        }
                                        subtitles.subtitleTitle = title;
                                        String displayT = title.isBlank() ? l("البث على الشاشة", "Video on TV") : title;
                                        playbackTitle.setText(displayT);
                                        showTvPoster(response.optString("poster_url"), response.optString("imdb_id"));
                                        if (playbackShortcutTitle != null && !title.isBlank()) playbackShortcutTitle.setText(title);
                                        if (nextEpisodeButton != null) {
                                            boolean isSeries = activeWatchSeason > 0 || activeWatchEpisode > 0;
                                            nextEpisodeButton.setVisibility(isSeries ? View.VISIBLE : View.GONE);
                                        }
                                    } else if (duration <= 0 && pendingCastTitle == null && resumableVideo == null && browserCastTitle == null) {
                                        subtitles.subtitleTitle = "";
                                        showTvPoster("", "");
                                        playbackTitle.setText(getString(org.proninyaroslav.libretorrent.R.string.cinema_no_active_video));
                                        if (playbackShortcutTitle != null) playbackShortcutTitle.setText(l("جاري البث على الشاشة", "Now Casting"));
                                    }
                                    if (!scrubbing && (duration > 0 || resumableVideo == null)) {
                                        timeline.setMax(Math.max(1, duration));
                                        timeline.setProgress(pos);
                                        displayTime(pos, duration);
                                    }
                                }
                                case "WEB_CAST_STARTED" -> {
                                    if (browserCastTitle == null) break;
                                    castStartedAtMs = android.os.SystemClock.elapsedRealtime();
                                    tvCastPlaybackObserved = false;
                                    playbackTitle.setText(browserCastTitle);
                                    if (playbackShortcutTitle != null) playbackShortcutTitle.setText(browserCastTitle);
                                    showCastNotification(browserCastTitle, "video");
                                    command("PLAYBACK", "ms", 0);
                                    var pause = browserPause;
                                    browserPause = null;
                                    if (pause != null) pause.run();
                                }
                                case "WEB_CAST_FAILED" -> {
                                    browserPause = null;
                                    browserCastTitle = null;
                                    clearCastNotification();
                                    playbackTitle.setText(getString(org.proninyaroslav.libretorrent.R.string.cinema_cast_link_failed));
                                }
                                case "TV_APPS" -> {
                                    var appsArr = response.optJSONArray("apps");
                                    android.app.Activity fg = FloatingTvDpadController.currentForegroundActivity();
                                    if (fg != null && fg != TvHubActivity.this && !fg.isFinishing()) {
                                        FloatingTvDpadController.showQuickTvApps(appsArr);
                                    } else {
                                        showTvApps(appsArr);
                                    }
                                }
                                case "REQUEST_PHONE_SUBTITLE" -> {
                                    String subTitle = response.optString("title", "");
                                    if (!subTitle.isBlank()) subtitles.subtitleTitle = subTitle;
                                    notifyStatus(l("اختر ملف الترجمة (.srt/.vtt/.ass) للبث على الشاشة", "Choose subtitle file (.srt/.vtt/.ass) to send to TV"));
                                    main.post(TvHubActivity.this::launchSubtitlePicker);
                                }
                                case "TV_APP_UNAVAILABLE" -> notifyStatus("The TV could not open that app");
                                case "SUBMIT_UNAVAILABLE" -> notifyStatus("The TV did not accept Search. Use its on-screen Search key.");
                                case "PASTED", "SUBMITTED" -> {
                                    if (message != null && submittedText != null
                                            && submittedText.contentEquals(TvHubActivity.this.message.getText())) {
                                        clearingRemoteText = true;
                                        TvHubActivity.this.message.setText("");
                                        clearingRemoteText = false;
                                    }
                                    submittedText = null;
                                }
                                case "NOT_CONTROLLER" -> {}
                                case "REMOTE_ACCESSIBILITY_REQUIRED" -> remoteAccessibilityNotice();
                                case "NO_FIELD" -> notifyStatus("Open a text field on the TV first");
                            }
                        } catch (org.json.JSONException ignored) {}
                    });
                }
                @Override public void onClose(int code, String reason, boolean remote) {
                    main.post(() -> {
                        if (socket != this || isDestroyed()) return;
                        clearPairingTimeout();
                        boolean wasConnected = connected;
                        String status = (code == 1008 && (reason == null || !reason.contains("Replaced")))
                                ? "Pairing rejected · Scan the current TV QR"
                                : connectionError != null ? pairingError(connectionError)
                                : wasConnected ? getString(org.proninyaroslav.libretorrent.R.string.cinema_disconnected) : getString(org.proninyaroslav.libretorrent.R.string.cinema_tv_did_not_complete_pairing);
                        android.util.Log.i("CinemaPairing", "Connection closed: " + code + " (" + reason + ") · " + status);
                        if (code == 1008 && (reason == null || !reason.contains("Replaced"))) {
                            pendingPairing = null;
                            reconnecting = false;
                            setConnected(false, status);
                            if (wasConnected && hasActiveCastSession()) stopCasting();
                        } else {
                            scheduleReconnect(status);
                        }
                    });
                }

                @Override public void onError(Exception error) {
                    main.post(() -> {
                        if (socket != this || isDestroyed()) return;
                        connectionError = error;
                        android.util.Log.w("CinemaPairing", "Connection to " + host + ":" + port + " failed: "
                                + error.getClass().getSimpleName() + ": " + error.getMessage());
                    });
                }
            };
            client.setConnectionLostTimeout(12);
            var wifi = CinemaRemoteBridge.pairingWifi(this, java.net.InetAddress.getByName(host));
            if (wifi != null) client.setSocketFactory(wifi.getSocketFactory());
            android.util.Log.i("CinemaPairing", "Connecting to " + host + ":" + port
                    + (wifi == null ? " via default network" : " via Wi-Fi"));
            socket = client;
            pairingTimeout = () -> {
                if (socket != client || connected || isDestroyed()) return;
                resetPairingAttempt();
                scheduleReconnect(getString(org.proninyaroslav.libretorrent.R.string.cinema_disconnected));
            };
            main.postDelayed(pairingTimeout, lightweightReconnect ? 3500 : 7500);
            client.connect();
        } catch (Exception invalid) {
            if (scanned) {
                android.util.Log.w("CinemaPairing", "Invalid pairing payload");
                notifyStatus("Invalid Local Cinema pairing code");
            }
        }
    }

    private void clearPairingTimeout() {
        if (pairingTimeout != null) main.removeCallbacks(pairingTimeout);
        pairingTimeout = null;
    }

    private void clearStatusDeadline() {
        if (statusDeadline != null) main.removeCallbacks(statusDeadline);
        statusDeadline = null;
    }

    private void resetPairingAttempt() {
        main.removeCallbacks(reconnect);
        clearPairingTimeout();
        clearStatusDeadline();
        var previous = socket;
        socket = null;
        if (previous == null) return;
        if (previous.isOpen()) previous.close();
        else if (previous.getSocket() != null) {
            try { previous.getSocket().close(); } catch (java.io.IOException ignored) {}
        }
    }

    private void scheduleReconnect(String status) {
        main.removeCallbacks(reconnect);
        boolean active = hasActiveCastSession();
        boolean allowed = !scanningPairing && !pendingQrScanLaunch && !suppressNextResumeReconnect
                && !idleSleeping && (active || (hubForeground && !isScreenLockedOrOff()));
        reconnecting = allowed && hasValidPairedTvEndpoint() && (active || reconnectAttempts < 2);
        setConnected(false, reconnecting ? l("إعادة الاتصال…", "Reconnecting…") : status);
        if (reconnecting) {
            main.postDelayed(reconnect, reconnectAttempts < 2 ? 500L * ++reconnectAttempts : 5000L);
        }
    }

    private static String pairingError(Exception error) {
        String detail = java.util.Objects.toString(error.getMessage(), "");
        if (error instanceof java.net.NoRouteToHostException || detail.contains("EHOSTUNREACH")
                || detail.contains("ENETUNREACH"))
            return "TV unreachable · Wi-Fi cannot reach the TV";
        if (error instanceof java.net.SocketTimeoutException || detail.contains("ETIMEDOUT"))
            return "TV connection timed out · Check the local network";
        if (error instanceof java.net.ConnectException)
            return "TV port unavailable · Open Link Phone on the TV";
        return "TV unreachable · Check Wi-Fi and scan again";
    }

    @Override protected void onStart() {
        super.onStart();
        hubForeground = true;
        var filter = new android.content.IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addDataScheme("file");
        androidx.core.content.ContextCompat.registerReceiver(this, library.storageChanges, filter,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
        library.refreshIfReady();
        if (connected && socket != null && socket.isOpen()) {
            main.removeCallbacks(playbackPoll);
            main.post(playbackPoll);
        } else if (!scanningPairing && !pendingQrScanLaunch && !suppressNextResumeReconnect
                && !isScreenLockedOrOff() && socket == null) {
            main.removeCallbacks(reconnect);
            main.post(reconnect);
        }
    }

    @Override protected void onStop() {
        hubForeground = false;
        library.stopObserving();
        unregisterReceiver(library.storageChanges);
        if (hasActiveCastSession()) {
            syncCastKeepAliveService();
        } else {
            main.removeCallbacks(playbackPoll);
            if (!scanningPairing && !pendingQrScanLaunch) {
                main.postDelayed(() -> {
                    if (!isDestroyed() && !hubForeground && !hasActiveCastSession()
                            && (isScreenLockedOrOff() || FloatingTvDpadController.currentForegroundActivity() == null)) {
                        enterIdleSleep();
                    }
                }, 1000);
            }
        }
        super.onStop();
    }

    private void rememberPlaybackState(org.json.JSONObject response, int duration) {
        if (response == null || duration <= 0) return;
        String path = response.optString("path");
        int position = response.optInt("ms");
        if (position < 0) return;
        if (activeWatchExcluded || "phone_local_media".equals(activeWatchKind)
                || response.optBoolean("exclude_from_history", false)
                || "phone_local_media".equals(response.optString("source_type", ""))) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        boolean isLibraryMatch = path != null && path.equals(library.libraryPlaybackPath);
        if (isLibraryMatch && "library".equals(activeWatchKind) && activeWatchUri != null
                && activeWatchUri.startsWith("file:")) {
            String localPath = android.net.Uri.parse(activeWatchUri).getPath();
            if (localPath != null && java.nio.file.Files.notExists(java.nio.file.Paths.get(localPath))) return;
        }
        if (isLibraryMatch && resumePosition > 0) {
            int target = resumePosition;
            resumePosition = 0;
            if (target < duration - 1000 && Math.abs(position - target) > 1500) {
                resumeSentAt = now;
                command("SEEK", "ms", target);
                return;
            }
        }
        if (now - resumeSentAt < 3000) return;
        if (isLibraryMatch && library.libraryPlaybackKey != null) {
            int saved = duration - position <= 1500 || position < 1000 || (long) position * 100L >= (long) duration * 85L ? 0 : position;
            var progress = library.libraryProgress();
            if (progress.getInt(library.libraryPlaybackKey, 0) != saved) {
                progress.edit().putInt(library.libraryPlaybackKey, saved).apply();
            }
        }
        if (webLive || response.optBoolean("audio")) return;
        String remoteKey = response.optString("stream_key", "");
        String remoteTitle = response.optString("title", "").trim();
        String remoteType = response.optString("media_type", "movie");
        String remoteImdb = response.optString("imdb_id", "");
        long remoteTmdb = response.optLong("tmdb_id", 0L);
        int remoteSeason = response.optInt("season", 0);
        int remoteEpisode = response.optInt("episode", 0);
        boolean remoteWatched = response.optBoolean("watched", false);

        String targetKey = null;
        String targetKind = "tv";
        String targetTitle = remoteTitle;
        String targetUri = "";
        String targetTorrentId = "";
        int targetFileIdx = 0;
        String targetFormat = "";
        if (activeWatchKey != null && ("library".equals(activeWatchKind) && isLibraryMatch)) {
            targetKey = activeWatchKey;
            targetKind = "library";
            targetTitle = !remoteTitle.isEmpty() ? remoteTitle : activeWatchTitle;
            targetUri = activeWatchUri;
            targetTorrentId = activeWatchTorrentId;
            targetFileIdx = activeWatchFileIndex;
            targetFormat = activeWatchFormat;
            if (!remoteImdb.isEmpty()) activeWatchImdbId = remoteImdb;
            if (remoteTmdb > 0) activeWatchTmdbId = remoteTmdb;
            if (remoteSeason > 0) activeWatchSeason = remoteSeason;
            if (remoteEpisode > 0) activeWatchEpisode = remoteEpisode;
        } else if ("web".equals(activeWatchKind) || ("torrent".equals(activeWatchKind) && isLibraryMatch)) {
            // Only indexed Library items belong in Continue Watching
            return;
        } else if (!remoteKey.isEmpty() || !remoteImdb.isEmpty() || !remoteTitle.isEmpty()) {
            if (!remoteKey.isEmpty() && !remoteKey.startsWith("v:")
                    && PhoneWatchStore.isExcludedNonLibrary(remoteKey, "tv", remoteTitle, "")) {
                return;
            }
            targetKey = !remoteKey.isEmpty() ? remoteKey
                    : PhoneWatchStore.canonicalKey(remoteType, remoteImdb, remoteTmdb, remoteSeason, remoteEpisode, "", remoteTitle);
        }
        if (targetKey == null || targetKey.isEmpty()
                || PhoneWatchStore.isExcludedNonLibrary(targetKey, targetKind, targetTitle, targetUri)) {
            return;
        }
        PhoneWatchStore.saveProgress(this, targetKey, targetKind, targetTitle, targetUri,
                targetTorrentId, targetFileIdx, targetFormat, remoteType,
                !remoteImdb.isEmpty() ? remoteImdb : activeWatchImdbId,
                remoteTmdb > 0 ? remoteTmdb : activeWatchTmdbId,
                remoteSeason > 0 ? remoteSeason : activeWatchSeason,
                remoteEpisode > 0 ? remoteEpisode : activeWatchEpisode,
                position, duration, remoteWatched);
    }

    private void pickVideo() {
        videoPicker.launch(new String[]{"video/*"});
    }

    private void castVideo(android.net.Uri uri) {
        castVideo(uri, -1, false);
    }

    private void castAndResumeFromCurrentPosition() {
        if (!connected || socket == null || !socket.isOpen()) {
            if (resumableVideo != null || music.activeAudioUri != null) {
                reconnectPlay = this::castAndResumeFromCurrentPosition;
                reconnect.run();
            }
            return;
        }
        if (remoteDuration <= 0 && resumableVideo != null && !music.audioMode) {
            castVideo(resumableVideo, lastCastPosition, lastCastFromLocalMedia);
        } else if (remoteDuration <= 0 && music.audioMode && music.activeAudioUri != null) {
            music.audioResumePosition = lastCastPosition;
            music.startAudioTrack(music.activeAudioUri, music.musicPlaybackOpened);
        } else {
            command("TOGGLE_PLAY", "ms", 0);
        }
    }

    void castVideo(android.net.Uri uri, int position, boolean fromLocalMedia) {
        if (!connected || socket == null) {
            notifyStatus("Connect to the TV first");
            return;
        }
        music.stopMusicForVideo(true);
        cleanupPreviousTemporaryTorrent("");
        try {
            String host = socket.getLocalSocketAddress().getAddress().getHostAddress();
            if (videoServer != null) videoServer.stop();
            videoServer = new PhoneVideoServer(host, getContentResolver(), uri);
            videoServer.start();
            resumableVideo = uri;
            lastCastFromLocalMedia = fromLocalMedia;
            boolean isExcluded = fromLocalMedia;
            activeWatchExcluded = isExcluded;
            if (isExcluded) {
                library.libraryPlaybackKey = null;
                library.libraryPlaybackPath = videoServer.path;
                activeWatchKey = null;
                activeWatchKind = "phone_local_media";
                activeWatchTitle = videoTitle(uri);
                activeWatchUri = "";
                activeWatchTorrentId = "";
                activeWatchFileIndex = 0;
                activeWatchFormat = "";
                activeWatchImdbId = "";
                activeWatchTmdbId = 0L;
                activeWatchSeason = 0;
                activeWatchEpisode = 0;
                resumePosition = 0;
            } else {
                library.libraryPlaybackKey = "file".equals(uri.getScheme())
                        ? TvHubLibraryController.libraryKey(new java.io.File(uri.getPath())) : uri.toString();
                library.libraryPlaybackPath = videoServer.path;
                activeWatchKey = library.libraryPlaybackKey != null ? "lib:" + library.libraryPlaybackKey : "lib:" + uri.toString();
                activeWatchKind = "library";
                activeWatchTitle = videoTitle(uri);
                activeWatchUri = uri.toString();
                activeWatchTorrentId = "";
                activeWatchFileIndex = 0;
                activeWatchFormat = "";
                activeWatchImdbId = "";
                activeWatchTmdbId = 0L;
                activeWatchSeason = 0;
                activeWatchEpisode = 0;
                if (activeWatchTitle != null) {
                    var epMatcher = java.util.regex.Pattern.compile("(?i)S(\\d{1,2})[ ._-]*E(\\d{1,3})")
                            .matcher(activeWatchTitle);
                    if (epMatcher.find()) {
                        try {
                            activeWatchSeason = Integer.parseInt(epMatcher.group(1));
                            activeWatchEpisode = Integer.parseInt(epMatcher.group(2));
                        } catch (NumberFormatException ignored) {}
                    }
                }
                var savedLib = PhoneWatchStore.findBestEntry(this, activeWatchKey,
                        (activeWatchSeason > 0 || activeWatchEpisode > 0) ? "episode" : "movie",
                        activeWatchImdbId, activeWatchTmdbId, activeWatchSeason, activeWatchEpisode, activeWatchTitle);
                if (savedLib != null) {
                    if (savedLib.imdbId != null) activeWatchImdbId = savedLib.imdbId;
                    activeWatchTmdbId = savedLib.tmdbId;
                    activeWatchSeason = savedLib.season;
                    activeWatchEpisode = savedLib.episode;
                }
                resumePosition = savedLib != null && !savedLib.watched && savedLib.positionMs > 0
                        ? (int) Math.min(Integer.MAX_VALUE, savedLib.positionMs)
                        : (library.libraryPlaybackKey == null ? 0 : library.libraryProgress().getInt(library.libraryPlaybackKey, 0));
                if (position >= 0) resumePosition = position;
            }
            lastCastPosition = resumePosition;
            resumeSentAt = 0;
            pendingCastTitle = videoTitle(uri);
            playbackTitle.setText(getString(org.proninyaroslav.libretorrent.R.string.cinema_cast_opening_video));
            showPlayback(true);
            try {
                int vidW = 0;
                int vidH = 0;
                int vidRot = 0;
                try (var mmr = new android.media.MediaMetadataRetriever()) {
                    mmr.setDataSource(this, uri);
                    String wStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String hStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    String rStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                    if (wStr != null) vidW = Integer.parseInt(wStr.trim());
                    if (hStr != null) vidH = Integer.parseInt(hStr.trim());
                    if (rStr != null) vidRot = Integer.parseInt(rStr.trim());
                } catch (Throwable ignored) {}
                socket.send(new org.json.JSONObject().put("type", "CAST")
                        .put("path", videoServer.path)
                        .put("title", activeWatchTitle)
                        .put("exclude_from_history", isExcluded)
                        .put("source_type", isExcluded ? "phone_local_media" : "phone_library")
                        .put("stream_key", isExcluded ? "" : activeWatchKey)
                        .put("media_type", activeWatchSeason > 0 || activeWatchEpisode > 0 ? "episode" : "movie")
                        .put("imdb_id", activeWatchImdbId)
                        .put("tmdb_id", activeWatchTmdbId)
                        .put("season", activeWatchSeason)
                        .put("episode", activeWatchEpisode)
                        .put("video_width", vidW)
                        .put("video_height", vidH)
                        .put("video_rotation", vidRot)
                        .put("resume_ms", isExcluded ? 0L : (long) Math.max(0, resumePosition)).toString());
            } catch (org.json.JSONException | org.java_websocket.exceptions.WebsocketNotConnectedException ignored) {
                command("CAST", "path", videoServer.path);
            }
            castStartedAtMs = android.os.SystemClock.elapsedRealtime();
            tvCastPlaybackObserved = false;
            showCastNotification(pendingCastTitle, "video");
        } catch (java.io.IOException error) {
            notifyStatus("Could not start video sharing");
        }
    }

    void playAddonTorrent(String magnet, int fileIndex, String title, String imdb, int season, int episode) {
        var torrentPort = PhoneModuleBridges.torrentCast();
        if (!connected || socket == null || !socket.isOpen() || fetchingAddonTorrent
                || !torrentPort.isAvailable()
                || !com.meedx.providers.StremioAddons.isTorrentMagnet(magnet)) return;
        var requestingSocket = socket;
        fetchingAddonTorrent = true;
        watchSyncIo.execute(() -> {
            PhoneModuleBridges.PreparedAddonTorrent prepared = null;
            try {
                prepared = torrentPort.openAddonTorrent(magnet, fileIndex);
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            var result = prepared;
            main.post(() -> {
                fetchingAddonTorrent = false;
                if (isDestroyed() || socket != requestingSocket || !requestingSocket.isOpen()) {
                    if (result != null && result.temporary()) torrentPort.deleteTemporaryCast(result.id());
                    return;
                }
                if (result == null) {
                    notifyStatus(getString(org.proninyaroslav.libretorrent.R.string.addon_torrent_failed));
                    return;
                }
                cleanupPreviousTemporaryTorrent(result.id());
                getIntent().putExtra("torrent_cast_id", result.id()).putExtra("torrent_cast_index", result.fileIndex())
                        .putExtra("torrent_cast_title", cleanCastTitle(title))
                        .putExtra("torrent_cast_temporary", result.temporary())
                        .putExtra("torrent_addon", true)
                        .putExtra("torrent_addon_imdb", imdb.matches("tt[0-9]+") ? imdb : "")
                        .putExtra("torrent_addon_season", Math.max(0, season))
                        .putExtra("torrent_addon_episode", Math.max(0, episode));
                pendingTorrentCast = true;
                if (hubForeground) showPlayback(true);
                prepareTorrentCast();
            });
        });
    }

    private void prepareTorrentCast() {
        if (!pendingTorrentCast) return;
        var torrentPort = PhoneModuleBridges.torrentCast();
        if (!torrentPort.isAvailable()) {
            torrentCastFailed("Torrent engine unavailable");
            return;
        }
        syncCastKeepAliveService();
        String id = getIntent().getStringExtra("torrent_cast_id");
        int index = getIntent().getIntExtra("torrent_cast_index", -1);
        String title = getIntent().getStringExtra("torrent_cast_title");
        boolean temporary = getIntent().getBooleanExtra("torrent_cast_temporary", false);
        torrentPort.ensureStarted();
        int generation = ++torrentCastGeneration;
        playbackTitle.setText(getString(org.proninyaroslav.libretorrent.R.string.cinema_cast_preparing_buffer, 0));
        new Thread(() -> {
            long deadline = android.os.SystemClock.elapsedRealtime() + 90000;
            try {
                while (generation == torrentCastGeneration && android.os.SystemClock.elapsedRealtime() < deadline) {
                    int percent = torrentPort.prepareBufferPercent(id, index);
                    if (percent >= 0) {
                        if (percent == 100) {
                            var readyStream = torrentPort.resolveReadyStream(id, index, temporary);
                            if (readyStream != null) {
                                main.post(() -> {
                                    if (generation == torrentCastGeneration && !isDestroyed())
                                        launchTorrentCast(readyStream, title);
                                });
                                return;
                            }
                        }
                        main.post(() -> {
                            if (generation == torrentCastGeneration)
                                playbackTitle.setText(getString(org.proninyaroslav.libretorrent.R.string.cinema_cast_preparing_buffer, percent));
                        });
                    }
                    Thread.sleep(500);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException error) {
                android.util.Log.w("CinemaMedia", "Could not prepare torrent cast", error);
            }
            main.post(() -> {
                if (generation == torrentCastGeneration && !isDestroyed()) torrentCastFailed("Torrent buffer unavailable");
            });
        }, "cinema-torrent-buffer").start();
    }

    private void launchTorrentCast(PhoneModuleBridges.ByteRangeStreamSource stream, String title) {
        if (!connected || socket == null || !socket.isOpen()) {
            torrentCastFailed("TV is disconnected");
            return;
        }
        try {
            music.stopMusicForVideo(true);
            boolean temporary = getIntent().getBooleanExtra("torrent_cast_temporary", false);
            cleanupPreviousTemporaryTorrent(temporary ? stream.streamId() : "");
            String host = socket.getLocalSocketAddress().getAddress().getHostAddress();
            if (videoServer != null) videoServer.stop();
            videoServer = PhoneVideoServer.forRangeStream(host, stream, title);
            activeTempTorrentId = temporary ? stream.streamId() : "";
            activeWatchExcluded = false;
            videoServer.start();
            pendingTorrentCast = false;
            library.libraryPlaybackPath = videoServer.path;
            library.libraryPlaybackKey = null;
            activeWatchKey = "torrent:" + stream.streamId() + ":" + stream.fileIndex();
            activeWatchKind = "torrent";
            activeWatchTitle = title;
            activeWatchUri = "";
            activeWatchTorrentId = stream.streamId();
            activeWatchFileIndex = stream.fileIndex();
            activeWatchFormat = "torrent";
            boolean addonTorrent = getIntent().getBooleanExtra("torrent_addon", false);
            activeWatchImdbId = addonTorrent ? getIntent().getStringExtra("torrent_addon_imdb") : "";
            activeWatchTmdbId = 0L;
            activeWatchSeason = addonTorrent ? getIntent().getIntExtra("torrent_addon_season", 0) : 0;
            activeWatchEpisode = addonTorrent ? getIntent().getIntExtra("torrent_addon_episode", 0) : 0;
            var savedTorrent = PhoneWatchStore.findBestEntry(this, activeWatchKey, "movie",
                    activeWatchImdbId, activeWatchTmdbId, 0, 0, title);
            if (savedTorrent != null && !addonTorrent) {
                if (savedTorrent.imdbId != null) activeWatchImdbId = savedTorrent.imdbId;
                activeWatchTmdbId = savedTorrent.tmdbId;
                activeWatchSeason = savedTorrent.season;
                activeWatchEpisode = savedTorrent.episode;
            }
            long torrentResume = savedTorrent != null && !savedTorrent.watched ? savedTorrent.positionMs : 0L;
            pendingCastTitle = title;
            playbackTitle.setText(title);
            socket.send(new org.json.JSONObject().put("type", "CAST").put("path", videoServer.path)
                    .put("torrent", true).put("title", title)
                    .put("stream_key", activeWatchKey)
                    .put("media_type", activeWatchSeason > 0 || activeWatchEpisode > 0 ? "episode" : "movie")
                    .put("imdb_id", activeWatchImdbId)
                    .put("tmdb_id", activeWatchTmdbId)
                    .put("season", activeWatchSeason)
                    .put("episode", activeWatchEpisode)
                    .put("subtitle_language", addonTorrent ? "ar" : "")
                    .put("addon_subtitles", addonTorrent)
                    .put("resume_ms", torrentResume).toString());
            castStartedAtMs = android.os.SystemClock.elapsedRealtime();
            tvCastPlaybackObserved = false;
            showCastNotification(title, "video");
        } catch (java.io.IOException | org.json.JSONException
                 | org.java_websocket.exceptions.WebsocketNotConnectedException error) {
            torrentCastFailed("Could not start torrent sharing");
        }
    }

    private void torrentCastFailed(String message) {
        if (pendingTorrentCast) discardPendingTorrent();
        pendingTorrentCast = false;
        pendingCastTitle = null;
        playbackTitle.setText(message);
        notifyStatus(message);
        syncCastKeepAliveService();
    }

    private void discardPendingTorrent() {
        if (!getIntent().getBooleanExtra("torrent_cast_temporary", false)) return;
        String id = getIntent().getStringExtra("torrent_cast_id");
        PhoneModuleBridges.torrentCast().deleteTemporaryCast(id);
    }

    String videoTitle(android.net.Uri uri) {
        if ("file".equals(uri.getScheme())) return uri.getLastPathSegment();
        String column = android.provider.OpenableColumns.DISPLAY_NAME;
        try (var rows = getContentResolver().query(uri, new String[]{column}, null, null, null)) {
            if (rows != null && rows.moveToFirst()) {
                String title = rows.getString(0);
                if (title != null && !title.isBlank()) return title;
            }
        } catch (IllegalArgumentException | SecurityException ignored) {}
        return "Selected video";
    }

    private long lastRemoteAccessibilityNotice = -10000;

    private void remoteAccessibilityNotice() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastRemoteAccessibilityNotice < 8000) return;
        lastRemoteAccessibilityNotice = now;
        notifyStatus("For system controls, enable Local Cinema in TV Accessibility settings");
    }

    private void sendKeyHold() {
        sendCommand("KEY", "key", 23, true);
    }

    void command(String type, String field, Object value) {
        sendCommand(type, field, value, false);
    }

    private boolean sendCommand(String type, String field, Object value, boolean held) {
        var current = socket;
        if (!connected || current == null || !current.isOpen()) return false;
        try {
            var payload = new org.json.JSONObject().put("type", type).put(field, value);
            if (held) payload.put("held", true);
            current.send(payload.toString());
            if ("KEY".equals(type)) android.util.Log.d("TV_REMOTE_TX", "KEY " + value + " held=" + held);
            if ("TYPE_TEXT".equals(type) || "PASTE_TEXT".equals(type)) {
                android.util.Log.d("TV_REMOTE_TX", type + " length=" + value.toString().length());
            }
            return true;
        } catch (org.json.JSONException | org.java_websocket.exceptions.WebsocketNotConnectedException error) {
            if (socket == current && connected) {
                resetPairingAttempt();
                scheduleReconnect(getString(org.proninyaroslav.libretorrent.R.string.cinema_disconnected));
            }
            return false;
        }
    }

    private void linked(LinearLayout row, String label, String description, Runnable action) {
        var control = button(label, action, false);
        control.setContentDescription(description);
        control.setEnabled(connected);
        control.setPadding(0, 0, 0, 0);
        linkControls.add(control);
        row.addView(control, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void showTvApps(org.json.JSONArray apps) {
        if (apps == null || apps.length() == 0 || joystick == null) return;
        var entries = new java.util.ArrayList<org.json.JSONObject>();
        for (int i = 0; i < apps.length(); i++) {
            var entry = apps.optJSONObject(i);
            if (entry != null) entries.add(entry);
        }
        entries.sort(java.util.Comparator.comparing(entry -> entry.optString("label"), String.CASE_INSENSITIVE_ORDER));
        var list = new android.widget.ListView(this);
        var dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        list.setAdapter(new android.widget.ArrayAdapter<org.json.JSONObject>(this, android.R.layout.simple_list_item_1, entries) {
            @Override public View getView(int position, View old, android.view.ViewGroup parent) {
                var item = getItem(position);
                var line = (TextView) super.getView(position, old, parent);
                line.setText(item.optString("label"));
                android.graphics.drawable.Drawable image = null;
                try {
                    byte[] bytes = android.util.Base64.decode(item.optString("icon"), android.util.Base64.DEFAULT);
                    var bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap != null) {
                        image = new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
                        image.setBounds(0, 0, dp(28), dp(28));
                    }
                } catch (IllegalArgumentException ignored) {}
                line.setCompoundDrawablesRelative(image, null, null, null);
                line.setCompoundDrawablePadding(dp(14));
                return line;
            }
        });
        list.setContentDescription(getString(org.proninyaroslav.libretorrent.R.string.cinema_remote_tv_apps));
        list.setOnItemClickListener((parent, view, position, id) -> {
            command("OPEN_TV_APP", "package", entries.get(position).optString("package"));
            dialog.dismiss();
        });
        dialog.setContentView(list);
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        dialog.show();
    }

    private void sendText() {
        if (message == null) return;
        String value = message.getText().toString();
        if (sendCommand(value.isEmpty() ? "SUBMIT_ENTER" : "TYPE_AND_SUBMIT", "text", value, false)) {
            submittedText = value;
            getSystemService(android.view.inputmethod.InputMethodManager.class)
                    .hideSoftInputFromWindow(message.getWindowToken(), 0);
        }
    }

    private void sendRemoteKey(int key) {
        if (key == 3 || key == 4)
            joystick.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        command("KEY", "key", key);
    }

    private void setConnected(boolean value, String status) {
        connected = value;
        if (!value) {
            boolean keepServers = hasActiveCastSession();
            syncRemoteVolume(false, false);
            volumeAvailable = false;
            main.removeCallbacks(sendVolume);
            renderVolume();
            music.syncMusicVolumeUi();
            CinemaRemoteBridge.updateRemoteCapabilities(false, false);
            if (!keepServers) {
                library.stopServer();
                if (videoServer != null) {
                    videoServer.stop();
                    videoServer = null;
                }
            }
            syncCastKeepAliveService();
        }
        main.removeCallbacks(playbackPoll);
        if (value) main.post(playbackPoll);
        else timeline.setEnabled(false);
        if (send != null) send.setEnabled(value);
        if (message != null) message.setEnabled(value);
        if (joystick != null) {
            joystick.setEnabled(value);
            joystick.invalidate();
        }
        for (var control : linkControls) control.setEnabled(value);
        FloatingTvDpadController.updateActiveSocket(value ? socket : null, value);
        CinemaRemoteBridge.updateConnection(value ? socket : null, value);
        connection.onConnectedChanged(value);
    }

    void setWebLive(boolean live) {
        if (webLive == live) return;
        webLive = live;
        if (goLive == null) return;
        goLive.setVisibility(live ? View.VISIBLE : View.GONE);
        playbackJumps.setVisibility(live ? View.GONE : View.VISIBLE);
        timeline.setVisibility(live ? View.GONE : View.VISIBLE);
        ((View) playbackTime.getParent()).setVisibility(live ? View.GONE : View.VISIBLE);
    }

    private void displayTime(int position, int duration) {
        playbackTime.setText(formatTime(position));
        playbackDuration.setText(formatTime(duration));
    }

    static String formatTime(int milliseconds) {
        int seconds = Math.max(0, milliseconds) / 1000;
        if (seconds < 3600) return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", seconds / 3600,
                (seconds / 60) % 60, seconds % 60);
    }

    boolean safeSend(org.java_websocket.client.WebSocketClient primary, String payload) {
        if (payload == null) return false;
        org.java_websocket.client.WebSocketClient target = primary;
        if (target == null || !target.isOpen()) {
            target = CinemaRemoteBridge.getConnectedSocket();
        }
        if (target == null || !target.isOpen()) {
            CinemaRemoteBridge.ensurePairedConnection(this);
            return false;
        }
        try {
            target.send(payload);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void notifyStatus(String value) {
        CinemaSnackBanner.show(this, value);
    }

    @Override protected void onDestroy() {
        if (activeInstance == this) activeInstance = null;
        if (screenReceiverRegistered) {
            try { unregisterReceiver(screenStateReceiver); } catch (Exception ignored) {}
            screenReceiverRegistered = false;
        }
        connection.unsubscribeTrakt();
        CinemaRemoteBridge.detachHub(this);
        torrentCastGeneration++;
        if (pendingTorrentCast) discardPendingTorrent();
        cleanupPreviousTemporaryTorrent("");
        clearCastNotification();
        MediaPlaybackNotificationService.stop(this);
        library.stopServer();
        syncRemoteVolume(false, false);
        main.removeCallbacks(hideHardwareVolume);
        main.removeCallbacksAndMessages(null);
        watchSyncIo.shutdown();
        browserPause = null;
        resetPairingAttempt();
        super.onDestroy();
    }

    void add(LinearLayout row, String text, Runnable onClick) {
        var position = new LinearLayout.LayoutParams(0, -2, 1);
        position.setMarginStart(dp(row.getChildCount() == 0 ? 0 : 6));
        row.addView(button(text, onClick, false), position);
    }

    private void preview(LinearLayout row, String text, String description) {
        var button = button(text, () -> {}, false);
        button.setContentDescription(description);
        button.setEnabled(false);
        if (text.length() <= 2) {
            button.setMinWidth(0);
            button.setPadding(0, 0, 0, 0);
            row.addView(button, new LinearLayout.LayoutParams(dp(48), dp(48)));
        } else {
            row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
    }

    LinearLayout card(LinearLayout page, String title) {
        return card(page, title, null, CinemaGlassUi.Accent.SUBTLE);
    }

    LinearLayout card(LinearLayout page, String title, android.view.View action) {
        return card(page, title, action, CinemaGlassUi.Accent.SUBTLE);
    }

    LinearLayout card(LinearLayout page, String title, android.view.View action, CinemaGlassUi.Accent accent) {
        var card = column();
        card.setBackground(CinemaGlassUi.glassCard(this, accent, 18));
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        var position = new LinearLayout.LayoutParams(-1, -2);
        position.topMargin = dp(12);
        page.addView(card, position);
        TextView heading = label(title, 20);
        heading.setTextColor(0xfff2ede7);
        heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        var headingPosition = new LinearLayout.LayoutParams(-1, -2);
        headingPosition.bottomMargin = dp(8);
        if (action == null) {
            card.addView(heading, headingPosition);
        } else {
            var header = new LinearLayout(this);
            header.setGravity(android.view.Gravity.CENTER_VERTICAL);
            header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
            header.addView(action, new LinearLayout.LayoutParams(-2, -2));
            card.addView(header, headingPosition);
        }
        return card;
    }

    LinearLayout column() {
        var column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    LinearLayout row(LinearLayout card) {
        var row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    TextView label(String text, int size) {
        var label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        return label;
    }

    MaterialButton button(String text, Runnable click, boolean primary) {
        var button = new MaterialButton(this, null, primary
                ? com.google.android.material.R.attr.materialButtonStyle
                : com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(text);
        button.setMinWidth(0);
        button.setSingleLine(false);
        button.setMaxLines(3);
        button.setEllipsize(null);
        if (!primary) {
            button.setTextColor(new android.content.res.ColorStateList(
                    new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                    new int[]{0xff77736c, GOLD}));
        }
        if (primary) {
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GOLD));
            button.setTextColor(0xff181818);
        }
        button.setMinHeight(dp(48));
        button.setCornerRadius(dp(14));
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setOnClickListener(view -> click.run());
        return button;
    }

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

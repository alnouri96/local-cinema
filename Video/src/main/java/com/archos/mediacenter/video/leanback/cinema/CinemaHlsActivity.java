package com.archos.mediacenter.video.leanback.cinema;

import com.archos.mediacenter.video.player.CinemaSubtitleText;
import com.archos.mediacenter.video.player.CinemaDpadSeek;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AlertDialog;

import com.archos.mediacenter.video.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.List;

/** Uses Android's native HLS / ExoPlayer playback path with full subtitle search & phone pickup. */
public final class CinemaHlsActivity extends Activity {
    static final String LIVE_CHANNEL = "catalog_live_channel";
    static final String CHANNEL_USER_AGENT = "channel_ua";
    static final String CHANNEL_REFERER = "channel_referer";
    static final String CHANNEL_HEADERS = "channel_headers";
    static final String FALLBACK_URLS = "channel_fallback_urls";
    static final String FALLBACK_LABELS = "channel_fallback_labels";
    static final String FALLBACK_FORMATS = "channel_fallback_formats";
    static final String FALLBACK_HEADERS = "channel_fallback_headers";
    private static final String DEFAULT_HTTP_UA =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int LIVE_MODE_AUTO = 0;
    private static final int LIVE_MODE_PROGRESSIVE = 1;
    private static final int LIVE_MODE_HLS = 2;
    private static final int REQ_SUBSOURCE = 913;
    private static final int REQ_ONLINE_SUB = 915;
    private static final int REQ_LOCAL_SUB = 916;

    private record LiveCandidate(String url, String label, String format, Bundle headers) {}
    private final java.util.List<LiveCandidate> liveCandidates = new java.util.ArrayList<>();
    private int activeLiveCandidateIndex = 0;
    private int liveStreamMode = LIVE_MODE_AUTO;
    private TextView osdLiveSourceButton;

    private VideoView video;
    private androidx.media3.ui.AspectRatioFrameLayout aspectContainer;
    private View adaptiveSurface;
    private int lastUnappliedRotation = 0;
    private float lastEffectiveWidth = 0f;
    private float lastEffectiveHeight = 0f;
    private int manualRotationDegrees = 0;
    private int rawVideoWidth = 1920;
    private int rawVideoHeight = 1080;
    private int rawVideoRotation = 0;
    private int rawUnappliedRotation = 0;
    private int intentVideoWidth = 0;
    private int intentVideoHeight = 0;
    private int intentVideoRotation = 0;
    private float rawVideoPixelRatio = 1.0f;
    private int currentSpeedPercent = 100;
    private TextView osdRotateButton;
    private TextView osdSpeedButton;
    private TextView osdPlayPauseButton;
    private TextView osdSubtitleButton;
    private TextView osdLiveBadge;
    private androidx.media3.exoplayer.ExoPlayer adaptivePlayer;
    private android.media.MediaPlayer player;
    private com.archos.mediacenter.video.player.SubtitleTextView videoViewSubtitles;
    private com.archos.mediacenter.video.player.SubtitleTextView exoSubtitles;
    private com.archos.mediacenter.video.player.SubtitleTextView externalSubtitleView;
    private com.archos.mediacenter.video.player.SubtitleTextView secondarySubtitleView;
    private LinearLayout osdBar;
    private FrameLayout rootContainer;
    private CinemaSubtitleTimingOverlay subtitleTimingOverlay;
    private CinemaPlaybackFilters playbackFilters;
    private int subtitleTrack = -1;
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private final android.os.Handler progressHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService progressIo = java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean pendingResumeApplied;
    private boolean userSoughtBelowThreshold;
    private boolean initialResumeSeekInFlight;
    private boolean initialLiveSeekApplied;
    private int adaptiveRetryCount = 0;
    private static final int MAX_ADAPTIVE_RETRIES = 5;
    private TextView liveChannelStatus;
    private final Runnable channelWatchdog = () -> handleAdaptiveError(null);

    boolean isCatalogChannel() {
        return getIntent().getBooleanExtra(LIVE_CHANNEL, false);
    }

    private boolean isLiveChannelPlayback() {
        if (isCatalogChannel()) return true;
        Intent i = getIntent();
        if (i != null) {
            String mt = i.getStringExtra("media_type");
            if (mt != null) {
                String m = mt.trim().toLowerCase(java.util.Locale.ROOT);
                if ("tv".equals(m) || "live".equals(m) || "channel".equals(m) || "channels".equals(m)
                        || "sport".equals(m) || "sports".equals(m) || "iptv".equals(m) || "radio".equals(m)) {
                    return true;
                }
            }
            if (i.getBooleanExtra("is_live_cast", false)) return true;
        }
        return false;
    }
    private Runnable pendingRetryRunnable;
    private String currentFormat;
    private final CinemaDpadSeek dpadSeek = new CinemaDpadSeek();
    private LinearLayout seekHud;
    private android.widget.ProgressBar seekBar;
    private TextView seekTime;
    private long seekHudUntil;
    private final Runnable updateSeekHud = new Runnable() {
        @Override public void run() {
            if (seekHud == null || seekHud.getVisibility() != View.VISIBLE) return;
            if (android.os.SystemClock.uptimeMillis() >= seekHudUntil) {
                seekHud.setVisibility(View.GONE);
                return;
            }
            updateSeekTime(position());
            progressHandler.postDelayed(this, 250);
        }
    };
    private boolean playbackStarted;
    private CinemaTorrentStream localTorrent;
    private androidx.appcompat.app.AlertDialog torrentPreparation;
    private String currentAudioUrl;
    private String currentCastId = "";
    private float subtitleSizeSp = 20f;
    private int subtitleColor = android.graphics.Color.WHITE;
    private int subtitleBottomMarginDp = 32;
    private boolean subtitleOpaqueBg = false;
    private int subtitleBgOpacity = 70;
    private int subtitleOutlineLevel = 0;
    private int subtitleOutlineColor = android.graphics.Color.BLACK;
    private boolean subtitleVisible = true;
    private long subtitleDelayMs = 0L;
    private byte[] lastSubtitleBytes;
    private String lastSubtitleExt;
    private boolean externalSubtitlesEnabled = false;
    private int subtitleRevision;
    private java.util.List<ExternalCue> externalCues = java.util.Collections.emptyList();
    private boolean secondarySubtitlesEnabled = false;
    private java.util.List<ExternalCue> secondaryCues = java.util.Collections.emptyList();
    private static final int SUB_MODE_DUAL = 0;
    private static final int SUB_MODE_PRIMARY = 1;
    private static final int SUB_MODE_SECONDARY = 2;
    private static final int DUAL_POS_STACKED_BOTTOM = 0;
    private static final int DUAL_POS_SPLIT_PRIMARY_BOTTOM = 1;
    private static final int DUAL_POS_SPLIT_PRIMARY_TOP = 2;
    private int subDisplayMode = SUB_MODE_DUAL;
    private int dualSubtitlePosition = DUAL_POS_SPLIT_PRIMARY_BOTTOM;
    private LinearLayout bottomSubtitleStack;
    private LinearLayout topSubtitleStack;

    private record ExternalCue(long startMs, long endMs, String text) {}

    private final Runnable subtitleTicker = new Runnable() {
        @Override public void run() {
            if (isDestroyed() || isFinishing() || (!externalSubtitlesEnabled && !secondarySubtitlesEnabled)) return;
            updateExternalSubtitleNow();
            progressHandler.postDelayed(this, 100L);
        }
    };

    private final Runnable hideOsd = () -> {
        if (osdBar != null) osdBar.setVisibility(View.GONE);
    };

    private static final long PERIODIC_SAVE_INTERVAL_MS = 5_000L;
    private final Runnable periodicProgressSave = new Runnable() {
        @Override public void run() {
            if (isDestroyed() || isFinishing()) return;
            if (playing()) {
                playbackStarted = true;
                saveStreamProgressAtAsync(null, false, false);
            }
            progressHandler.postDelayed(this, PERIODIC_SAVE_INTERVAL_MS);
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (playing()) toggle();
        }
    };

    private boolean isLiveCast() {
        Intent i = getIntent();
        if (i == null) return false;
        if (i.getBooleanExtra("is_live_cast", false)
                || i.getBooleanExtra("exclude_from_history", false)
                || "phone_local_media".equals(i.getStringExtra("source_type"))) {
            return true;
        }
        Uri data = i.getData();
        return CinemaWatchStore.isEphemeralLiveCast(
                data != null ? data.toString() : "",
                i.getStringExtra("title"),
                i.getStringExtra("media_type"),
                i.getStringExtra("imdb_id"),
                i.getLongExtra("tmdb_id", 0L));
    }

    private void saveStreamProgressAsync(boolean completed) {
        saveStreamProgressAtAsync(null, completed, true);
    }

    private void saveStreamProgressAtAsync(Long explicitPosMs, boolean completed) {
        saveStreamProgressAtAsync(explicitPosMs, completed, true);
    }

    private boolean addonFrameRendered;
    private boolean addonPlaybackFailed;
    private long addonPlayedMs;
    private long addonPositionMs;
    private long addonSampledAt;

    private void resetAddonPlaybackSample(long positionMs) {
        addonPositionMs = positionMs;
        addonSampledAt = android.os.SystemClock.elapsedRealtime();
    }

    private boolean hasRealAddonProgress(long currentPosition) {
        if (!addonFrameRendered || addonPlaybackFailed || currentPosition <= 0
                || !CinemaWatchStore.includeAddonContinue(this)) return false;
        long advance = currentPosition - addonPositionMs;
        long elapsed = android.os.SystemClock.elapsedRealtime() - addonSampledAt;
        if (advance > 0 && advance <= elapsed * 3 + 500) addonPlayedMs += advance;
        resetAddonPlaybackSample(currentPosition);
        return currentPosition >= 1500 && addonPlayedMs >= 1500;
    }

    private void saveStreamProgressAtAsync(Long explicitPosMs, boolean completed, boolean pushRemote) {
        if (!playbackStarted && !playing()) return;
        if (live() || isLiveCast()) return;
        Uri data = getIntent() != null ? getIntent().getData() : null;
        if (data == null) return;
        String streamKey = getIntent().getStringExtra("stream_key");
        if (streamKey == null || streamKey.isBlank()) streamKey = data.toString();
        long dur = duration();
        long rawPos = explicitPosMs != null ? Math.max(0L, explicitPosMs) : position();
        if (streamKey.startsWith("addon:") && (explicitPosMs != null || !hasRealAddonProgress(rawPos))) return;
        long pos = completed && dur > 0 ? dur : rawPos;
        if (dur <= 0 || pos < 0) return;
        String format = getIntent().getStringExtra("format");
        String title = getIntent().getStringExtra("title");
        if (title == null || title.isBlank()) {
            title = data.getLastPathSegment() != null ? data.getLastPathSegment() : "Stream";
        }
        String mediaType = getIntent().getStringExtra("media_type");
        String imdbId = getIntent().getStringExtra("imdb_id");
        long tmdbId = getIntent().getLongExtra("tmdb_id", 0L);
        int season = getIntent().getIntExtra("season", 0);
        int episode = getIntent().getIntExtra("episode", 0);
        final String keyFinal = streamKey;
        int torrentFile = getIntent().getIntExtra("torrent_file_index", CinemaTorrentStream.savedFileIndex(data.toString()));
        final String uriFinal = localTorrent != null && torrentFile >= 0
                ? CinemaTorrentStream.nativeMagnet(data.toString()) + "&lcfile=" + torrentFile : data.toString();
        final String titleFinal = title;
        final boolean soughtBelow85 = userSoughtBelowThreshold;
        final boolean reachedThreshold = completed || (dur > 0 && pos * 100L >= dur * 85L);
        final android.content.Context app = getApplicationContext();
        final String poster = getIntent().getStringExtra("addon_poster");
        final String heading = getIntent().getStringExtra("addon_heading");
        progressIo.execute(() -> {
            if (keyFinal.startsWith("addon:") && !CinemaWatchStore.includeAddonContinue(app)) return;
            CinemaWatchStore.StreamEntry before = CinemaWatchStore.getStream(app, keyFinal);
            boolean wasWatched = before != null && before.watched();
            if (wasWatched && pos <= 1000 && !soughtBelow85 && !reachedThreshold) {
                return;
            }
            if (poster != null && !poster.isBlank() && CinemaWatchStore.getIdentity(app, keyFinal) == null) {
                CinemaWatchStore.saveIdentity(app, keyFinal, null, mediaType, imdbId, tmdbId, season,
                        episode, heading, 0, poster, "", "addon");
            }
            CinemaWatchStore.saveStreamProgress(
                    app, keyFinal, uriFinal, format, titleFinal, mediaType, imdbId, tmdbId, season, episode, pos, dur);
            if (pushRemote || reachedThreshold) {
                CinemaWatchStore.StreamEntry after = CinemaWatchStore.getStream(app, keyFinal);
                CinemaWatchStore.pushPlaybackScrobbleAsync(app, 0L, uriFinal, titleFinal, mediaType, imdbId, tmdbId, season, episode, pos, dur, reachedThreshold);
                if (after != null && (after.watched() != wasWatched || reachedThreshold)) {
                    CinemaLinkServer.broadcastWatchSync(CinemaWatchStore.buildSyncRecordForStream(after));
                    CinemaWatchStore.syncWithTraktAsync(app);
                }
            }
        });
    }

    private void applyInitialResumeIfNeeded() {
        if (pendingResumeApplied || live() || isLiveCast() || getIntent() == null) return;
        long dur = duration();
        if (dur <= 0) return;
        pendingResumeApplied = true;
        long resumeMs = getIntent().getLongExtra("resume_ms", 0L);
        if (resumeMs > 0 && resumeMs < dur - 1000L) {
            initialResumeSeekInFlight = true;
            seekInternal((int) Math.min(Integer.MAX_VALUE, resumeMs), false);
            return;
        }
        if (getIntent().getBooleanExtra("start_from_beginning", false)) return;
        Uri data = getIntent().getData();
        String streamKey = getIntent().getStringExtra("stream_key");
        if ((streamKey == null || streamKey.isBlank()) && data != null) streamKey = data.toString();
        final String keyFinal = streamKey;
        final String title = getIntent().getStringExtra("title");
        final String mediaType = getIntent().getStringExtra("media_type");
        final String imdbId = getIntent().getStringExtra("imdb_id");
        final long tmdbId = getIntent().getLongExtra("tmdb_id", 0L);
        final int season = getIntent().getIntExtra("season", 0);
        final int episode = getIntent().getIntExtra("episode", 0);
        final android.content.Context app = getApplicationContext();
        progressIo.execute(() -> {
            long saved = CinemaWatchStore.findBestStreamResumeMs(app, keyFinal, mediaType, imdbId, tmdbId, season, episode, title);
            if (saved > 0 && saved < dur - 1000L) {
                progressHandler.post(() -> {
                    if (!isDestroyed() && !isFinishing()) {
                        initialResumeSeekInFlight = true;
                        seekInternal((int) Math.min(Integer.MAX_VALUE, saved), false);
                    }
                });
            }
        });
    }

    private void keepScreenOn(boolean on) {
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setKeepScreenOn(on);
        if (video != null) video.setKeepScreenOn(on);
        if (adaptiveSurface != null) adaptiveSurface.setKeepScreenOn(on);
    }

    private void updatePlaybackScreen(androidx.media3.common.Player engine) {
        int state = engine.getPlaybackState();
        keepScreenOn(!isFinishing() && engine.getPlayWhenReady()
                && (state == androidx.media3.common.Player.STATE_READY
                    || state == androidx.media3.common.Player.STATE_BUFFERING));
    }

    private void initMediaSession() {
        if (mediaSession != null) return;
        mediaSession = new MediaSessionCompat(this, "CinemaHlsActivity");
        String title = getIntent() != null ? getIntent().getStringExtra("title") : null;
        if (title != null && !title.isBlank()) {
            mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .build());
        }
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { if (!playing()) toggle(); }
            @Override public void onPause() { if (playing()) toggle(); }
            @Override public void onStop() {
                if (playing()) toggle();
                keepScreenOn(false);
                updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED);
            }
        });
        updateMediaSessionState(PlaybackStateCompat.STATE_NONE);
    }

    private void updateMediaSessionState(int state) {
        if (mediaSession == null) return;
        boolean active = state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_PAUSED;
        if (mediaSession.isActive() != active) mediaSession.setActive(active);
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_STOP;
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, Math.max(0, position()), state == PlaybackStateCompat.STATE_PLAYING ? 1.0f : 0.0f)
                .build());
    }

    private void requestVideoAudioFocus() {
        if (audioManager == null) audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                .build())
                        .setOnAudioFocusChangeListener(focusListener)
                        .build();
            }
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonVideoAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(focusListener);
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        var defaultPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        int defSize = 36;
        int defVpos = 64;
        int defColor = android.graphics.Color.WHITE;
        try { defSize = getResources().getInteger(com.archos.mediacenter.video.R.integer.player_pref_subtitle_size_default); } catch (Exception ignored) {}
        try { defVpos = getResources().getInteger(com.archos.mediacenter.video.R.integer.player_pref_subtitle_vpos_default); } catch (Exception ignored) {}
        try { defColor = android.graphics.Color.parseColor(getString(com.archos.mediacenter.video.R.string.subtitle_color_default)); } catch (Exception ignored) {}
        subtitleSizeSp = Math.max(14f, Math.min(38f, 14f + (defaultPrefs.getInt(
                com.archos.mediacenter.video.player.PlayerActivity.KEY_SUBTITLE_SIZE, defSize) * 0.17f)));
        subtitleColor = defaultPrefs.getInt(
                com.archos.mediacenter.video.player.PlayerActivity.KEY_SUBTITLE_COLOR, defColor);
        subtitleBottomMarginDp = Math.max(16, Math.min(180, defaultPrefs.getInt(
                com.archos.mediacenter.video.player.PlayerActivity.KEY_SUBTITLE_VPOS, defVpos) / 2));
        subtitleOpaqueBg = defaultPrefs.getBoolean(
                com.archos.mediacenter.video.player.PlayerActivity.KEY_SUBTITLE_BG,
                com.meedx.providers.UnifiedSubtitleConfig.opaqueBg(this));
        subtitleBgOpacity = com.meedx.providers.UnifiedSubtitleConfig.normalizeBgOpacity(
                defaultPrefs.getInt(com.archos.mediacenter.video.player.PlayerActivity.KEY_SUBTITLE_BG_OPACITY,
                        com.meedx.providers.UnifiedSubtitleConfig.bgOpacity(this)));
        subtitleOutlineLevel = Math.max(0, Math.min(3, defaultPrefs.getInt(
                com.archos.mediacenter.video.player.PlayerActivity.KEY_SUBTITLE_OUTLINE_LEVEL,
                com.meedx.providers.UnifiedSubtitleConfig.outlineLevel(this))));
        subtitleOutlineColor = com.meedx.providers.UnifiedSubtitleConfig.normalizeOutlineColor(
                defaultPrefs.getInt(com.archos.mediacenter.video.player.PlayerActivity.KEY_SUBTITLE_OUTLINE_COLOR,
                        com.meedx.providers.UnifiedSubtitleConfig.outlineColor(this)));
        dualSubtitlePosition = Math.max(0, Math.min(2,
                defaultPrefs.getInt("pref_dual_sub_position", DUAL_POS_SPLIT_PRIMARY_BOTTOM)));
        manualRotationDegrees = normalizeRotationDegrees(getIntent().getIntExtra("manual_rotation", 0));
        boolean rememberSpeed = defaultPrefs.getBoolean("pref_remember_video_speed", false);
        int savedSpeedPct = rememberSpeed
                ? defaultPrefs.getInt("pref_video_speed", 100)
                : Math.round(defaultPrefs.getFloat(
                        getString(com.archos.mediacenter.video.R.string.save_audio_speed_setting_pref_key), 1.0f) * 100f);
        if (savedSpeedPct >= 50 && savedSpeedPct <= 200) {
            currentSpeedPercent = savedSpeedPct;
        }
        var root = new FrameLayout(this);
        rootContainer = root;
        root.setBackgroundColor(android.graphics.Color.BLACK);
        currentFormat = getIntent().getStringExtra("format");
        currentAudioUrl = getIntent().getStringExtra("audio_url");
        currentCastId = getIntent().getStringExtra("cast_id");
        if (currentCastId == null) currentCastId = "";
        Uri dataUri = getIntent().getData();
        if ("torrent".equals(currentFormat) && dataUri != null
                && com.meedx.providers.StremioAddons.isTorrentMagnet(dataUri.toString())) {
            localTorrent = CinemaTorrentStream.takePrepared(getIntent().getStringExtra(CinemaTorrentStream.PREPARED_TOKEN));
            if (localTorrent == null) {
                root.setBackgroundColor(0xff171717);
                setContentView(root);
                var torrent = new CinemaTorrentStream(this, dataUri.toString(),
                        getIntent().getIntExtra("torrent_file_index", CinemaTorrentStream.savedFileIndex(dataUri.toString())));
                torrentPreparation = CinemaAddonPicker.prepareTorrent(this, torrent, ready -> {
                    torrentPreparation = null;
                    localTorrent = ready;
                    root.setBackgroundColor(android.graphics.Color.BLACK);
                    startAdaptive(root, currentFormat, currentAudioUrl);
                    findArabicAddonSubtitles();
                    initMediaSession();
                }, this::finish);
                return;
            }
        }
        String dataPath = dataUri != null && dataUri.getPath() != null
                ? dataUri.getPath().toLowerCase(java.util.Locale.ROOT) : "";
        boolean isHttpScheme = dataUri != null
                && ("http".equalsIgnoreCase(dataUri.getScheme()) || "https".equalsIgnoreCase(dataUri.getScheme()));
        if ("torrent".equals(currentFormat) || "dash".equals(currentFormat) || "audio".equals(currentFormat)
                || "split".equals(currentFormat) || "addon".equals(currentFormat)
                || "portrait_cast".equals(currentFormat) || "phone_local_media".equals(currentFormat)
                || "adaptive_file".equals(currentFormat)
                || (currentAudioUrl != null && !currentAudioUrl.isEmpty())
                || "hls".equals(currentFormat)
                || isHttpScheme
                || dataPath.endsWith(".m3u8") || dataPath.endsWith(".mpd")) {
            startAdaptive(root, currentFormat, currentAudioUrl);
            findArabicAddonSubtitles();
            initMediaSession();
            return;
        }
        aspectContainer = new androidx.media3.ui.AspectRatioFrameLayout(this);
        aspectContainer.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
        root.addView(aspectContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        video = new VideoView(this);
        aspectContainer.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        int extraW = getIntent().getIntExtra("video_width", 0);
        int extraH = getIntent().getIntExtra("video_height", 0);
        int extraRot = getIntent().getIntExtra("video_rotation", 0);
        float initRatio = computeDisplayAspectRatio(extraW, extraH, extraRot, 1.0f);
        if (initRatio > 0f) aspectContainer.setAspectRatio(initRatio);
        videoViewSubtitles = new com.archos.mediacenter.video.player.SubtitleTextView(this);
        videoViewSubtitles.setGravity(Gravity.CENTER);
        videoViewSubtitles.setTextColor(subtitleColor);
        videoViewSubtitles.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        videoViewSubtitles.setTextSize(subtitleSizeSp);
        videoViewSubtitles.setMaxWidth(Math.max(CinemaUi.dp(this, 260),
                getResources().getDisplayMetrics().widthPixels - CinemaUi.dp(this, 48)));
        videoViewSubtitles.setSubtitleBackground(subtitleOpaqueBg, subtitleBgOpacity);
        videoViewSubtitles.setOutline(subtitleOutlineLevel, subtitleOutlineColor);
        videoViewSubtitles.setVisibility(View.GONE);
        var position = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        position.bottomMargin = Math.round(subtitleBottomMarginDp * getResources().getDisplayMetrics().density);
        root.addView(videoViewSubtitles, position);
        attachExternalSubtitleOverlay(root);
        attachOsdBar(root);
        setContentView(root);
        initMediaSession();
        video.setOnPreparedListener(prepared -> {
            CinemaLinkServer.playbackChanged();
            player = prepared;
            subtitleTrack = -1;
            videoViewSubtitles.setText(null);
            prepared.setOnVideoSizeChangedListener((mp, vw, vh) -> {
                if (vw > 0 && vh > 0 && aspectContainer != null) {
                    aspectContainer.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
                    aspectContainer.setAspectRatio((float) vw / (float) vh);
                }
            });
            prepared.setOnTimedTextListener((source, cue) -> {
                String text = cue == null ? null : CinemaSubtitleText.displayText(cue.getText());
                if (playbackFilters != null) playbackFilters.observeSubtitle(text, -1, -1);
                videoViewSubtitles.setText(text);
            });
            applyInitialResumeIfNeeded();
            if (currentSpeedPercent != 100) {
                speed(currentSpeedPercent / 100f);
            }
            requestVideoAudioFocus();
            keepScreenOn(true);
            updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING);
            progressHandler.removeCallbacks(periodicProgressSave);
            progressHandler.postDelayed(periodicProgressSave, 15_000L);
            String subUrl = getIntent().getStringExtra("subtitle_url");
            if (subUrl != null && !subUrl.isEmpty()) {
                loadRemoteSubtitleAsync(subUrl, false);
            }
            String sub2Url = getIntent().getStringExtra("secondary_subtitle_url");
            if (sub2Url != null && !sub2Url.isEmpty()) {
                loadRemoteSubtitleAsync(sub2Url, true);
            }
        });
        video.setOnCompletionListener(mp -> {
            CinemaLinkServer.playbackChanged();
            saveStreamProgressAsync(true);
            keepScreenOn(false);
            updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED);
            abandonVideoAudioFocus();
        });
        video.setOnErrorListener((mp, what, extra) -> {
            android.util.Log.w("CinemaHls", "Playback failed: " + what + "/" + extra);
            keepScreenOn(false);
            updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED);
            abandonVideoAudioFocus();
            finish();
            return true;
        });
        video.setVideoURI(getIntent().getData());
        video.start();
    }

    private void attachOsdBar(FrameLayout root) {
        float d = getResources().getDisplayMetrics().density;
        osdBar = new LinearLayout(this);
        osdBar.setOrientation(LinearLayout.HORIZONTAL);
        osdBar.setGravity(Gravity.CENTER);
        osdBar.setPadding(Math.round(14 * d), Math.round(10 * d), Math.round(14 * d), Math.round(10 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xe618181d);
        bg.setCornerRadius(16 * d);
        bg.setStroke(Math.round(1.5f * d), 0xffe5b94c);
        osdBar.setBackground(bg);
        osdBar.setVisibility(View.GONE);

        boolean ar = CinemaUi.arabic(this);
        osdLiveBadge = CinemaUi.text(this, 13, true);
        osdLiveBadge.setText(ar ? "● مباشر" : "● LIVE");
        osdLiveBadge.setTextColor(0xffff6b6b);
        osdLiveBadge.setPadding(Math.round(12 * d), Math.round(6 * d), Math.round(12 * d), Math.round(6 * d));
        osdLiveBadge.setBackground(CinemaUi.rounded(0x33ef4444, Math.round(10 * d), 0x88ef4444));
        var liveBadgeLp = new LinearLayout.LayoutParams(-2, -2);
        liveBadgeLp.setMarginEnd(Math.round(8 * d));
        osdLiveBadge.setVisibility(View.GONE);
        osdBar.addView(osdLiveBadge, liveBadgeLp);

        osdPlayPauseButton = osdButton(osdPlayPauseLabel(), this::toggle);
        osdBar.addView(osdPlayPauseButton);
        osdLiveSourceButton = osdButton(osdLiveSourceLabel(ar), this::openLiveSourceMenuFromOsd);
        osdLiveSourceButton.setVisibility(liveCandidates.size() > 1 ? View.VISIBLE : View.GONE);
        osdBar.addView(osdLiveSourceButton);
        osdSubtitleButton = osdButton(ar ? "💬 الترجمة" : "💬 Subtitles", this::openSubtitleMenuFromRemote);
        osdBar.addView(osdSubtitleButton);
        osdRotateButton = osdButton(osdRotateLabel(ar), this::cycleManualRotationFromOsd);
        osdBar.addView(osdRotateButton);
        osdSpeedButton = osdButton(osdSpeedLabel(ar), this::openSpeedMenuFromOsd);
        osdBar.addView(osdSpeedButton);
        if (!isLiveChannelPlayback() && !"audio".equals(getIntent().getStringExtra("media_type"))) {
            for (CinemaPlaybackOption option : CinemaPlaybackOption.values()) {
                String prefix = option == CinemaPlaybackOption.FAMILY_WATCH ? "🛡 " : "⏭ ";
                TextView button = osdButton(prefix + getString(option.title), () -> {
                    option.toggle(this);
                    refreshOsdLabels();
                    Toast.makeText(this,
                            getString(option.title) + ": " + option.state(this),
                            Toast.LENGTH_SHORT).show();
                });
                button.setEnabled(option.available());
                button.setAlpha(option.available() ? 1f : 0.48f);
                button.setTag(option);
                osdBar.addView(button);
            }
        }
        refreshOsdLabels();

        var lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Math.round(72 * d);
        root.addView(osdBar, lp);
        attachSeekHud(root);
        updateLiveControlsVisibility();
    }

    private void updateLiveControlsVisibility() {
        boolean isLive = isLiveChannelPlayback();
        int seekVis = isLive ? View.GONE : View.VISIBLE;
        if (osdSpeedButton != null) {
            osdSpeedButton.setVisibility(seekVis);
            osdSpeedButton.setFocusable(!isLive);
            osdSpeedButton.setEnabled(!isLive);
        }
        if (osdSubtitleButton != null) {
            osdSubtitleButton.setVisibility(seekVis);
            osdSubtitleButton.setFocusable(!isLive);
            osdSubtitleButton.setEnabled(!isLive);
        }
        if (osdLiveBadge != null) {
            osdLiveBadge.setVisibility(isLive ? View.VISIBLE : View.GONE);
        }
        if (isLive) {
            if (seekHud != null) {
                progressHandler.removeCallbacks(updateSeekHud);
                seekHud.setVisibility(View.GONE);
            }
            if (externalSubtitleView != null) externalSubtitleView.setVisibility(View.GONE);
            if (secondarySubtitleView != null) secondarySubtitleView.setVisibility(View.GONE);
            if (exoSubtitles != null) exoSubtitles.setVisibility(View.GONE);
            if (videoViewSubtitles != null) videoViewSubtitles.setVisibility(View.GONE);
        }
    }

    private void attachSeekHud(FrameLayout root) {
        seekHud = new LinearLayout(this);
        seekHud.setOrientation(LinearLayout.VERTICAL);
        seekHud.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        seekHud.setPadding(CinemaUi.dp(this, 16), CinemaUi.dp(this, 6),
                CinemaUi.dp(this, 16), CinemaUi.dp(this, 10));
        seekHud.setBackground(CinemaUi.rounded(0xe618181d, CinemaUi.dp(this, 12), 0xff544026));
        seekHud.setVisibility(View.GONE);
        seekTime = CinemaUi.text(this, 14, true);
        seekTime.setGravity(Gravity.CENTER);
        seekHud.addView(seekTime, new LinearLayout.LayoutParams(-1, -2));
        seekBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffe5a93b));
        seekBar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        seekHud.addView(seekBar, new LinearLayout.LayoutParams(-1, CinemaUi.dp(this, 10)));
        var size = new FrameLayout.LayoutParams(Math.min(CinemaUi.dp(this, 420),
                getResources().getDisplayMetrics().widthPixels * 3 / 4), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        size.bottomMargin = CinemaUi.dp(this, 24);
        root.addView(seekHud, size);
    }

    private void updateSeekTime(int position) {
        int duration = duration();
        if (duration <= 0) return;
        seekBar.setMax(duration);
        seekBar.setProgress(position);
        seekTime.setText(android.text.format.DateUtils.formatElapsedTime(position / 1000L) + " / "
                + android.text.format.DateUtils.formatElapsedTime(duration / 1000L));
    }

    private boolean seekFromRemote(KeyEvent event) {
        if (seekHud == null || isLiveChannelPlayback()) return false;
        if (osdBar != null && osdBar.getVisibility() == View.VISIBLE && osdBar.hasFocus()) return false;
        int duration = duration();
        if (duration <= 0 || (adaptivePlayer != null && !adaptivePlayer.isCurrentMediaItemSeekable())) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) return true;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

        int step = dpadSeek.offset(event);
        if (step == 0) return true;
        int current = position();
        int target = CinemaDpadSeek.clamp(duration, current, step);
        if (current != target) seek(target);
        seekHudUntil = android.os.SystemClock.uptimeMillis() + 2500;
        seekHud.setVisibility(View.VISIBLE);
        seekHud.bringToFront();
        updateSeekTime(target);
        progressHandler.removeCallbacks(updateSeekHud);
        progressHandler.postDelayed(updateSeekHud, 250);
        return true;
    }

    private String osdLiveSourceLabel(boolean ar) {
        if (liveCandidates.size() <= 1) return ar ? "📡 المصدر" : "📡 Source";
        return (ar ? "📡 المصدر " : "📡 Source ") + (activeLiveCandidateIndex + 1) + "/" + liveCandidates.size();
    }

    private void openLiveSourceMenuFromOsd() {
        if (liveCandidates.size() <= 1 || isFinishing() || isDestroyed()) return;
        boolean ar = CinemaUi.arabic(this);
        String[] items = new String[liveCandidates.size()];
        for (int i = 0; i < liveCandidates.size(); i++) {
            var c = liveCandidates.get(i);
            String label = (c.label() != null && !c.label().isBlank())
                    ? c.label() : (ar ? "المصدر " : "Source ") + (i + 1);
            items[i] = (i == activeLiveCandidateIndex ? "✓  " : "    ") + label;
        }
        CinemaUi.dialog(this)
                .setTitle(ar ? "مصادر القناة" : "Channel Sources")
                .setItems(items, (d, which) -> {
                    if (which >= 0 && which < liveCandidates.size() && which != activeLiveCandidateIndex) {
                        switchLiveCandidate(which, LIVE_MODE_AUTO);
                    }
                })
                .setNegativeButton(R.string.addon_back, null)
                .show();
    }

    private String osdRotateLabel(boolean ar) {
        if (manualRotationDegrees == 0) return ar ? "⟳ تدوير" : "⟳ Rotate";
        return "⟳ " + manualRotationDegrees + "°";
    }

    private String osdSpeedLabel(boolean ar) {
        if (currentSpeedPercent == 100) return ar ? "⚡ السرعة" : "⚡ Speed";
        return "⚡ " + ((currentSpeedPercent % 10 == 0)
                ? String.format(java.util.Locale.US, "%.1f×", currentSpeedPercent / 100f)
                : String.format(java.util.Locale.US, "%.2f×", currentSpeedPercent / 100f));
    }

    private boolean isEffectivelyPlaying() {
        if (adaptivePlayer != null) {
            return adaptivePlayer.getPlayWhenReady()
                    && adaptivePlayer.getPlaybackState() != androidx.media3.common.Player.STATE_ENDED;
        }
        return video != null && video.isPlaying();
    }

    private String osdPlayPauseLabel() {
        return getString(isEffectivelyPlaying() ? R.string.cinema_osd_pause : R.string.cinema_osd_play);
    }

    void refreshOsdLabels() {
        boolean ar = CinemaUi.arabic(this);
        if (osdPlayPauseButton != null) {
            osdPlayPauseButton.setText(osdPlayPauseLabel());
        }
        if (osdLiveSourceButton != null) {
            osdLiveSourceButton.setText(osdLiveSourceLabel(ar));
            osdLiveSourceButton.setVisibility(liveCandidates.size() > 1 ? View.VISIBLE : View.GONE);
        }
        if (osdRotateButton != null) osdRotateButton.setText(osdRotateLabel(ar));
        if (osdSpeedButton != null) osdSpeedButton.setText(osdSpeedLabel(ar));
        if (osdBar != null) {
            for (CinemaPlaybackOption option : CinemaPlaybackOption.values()) {
                TextView button = osdBar.findViewWithTag(option);
                if (button != null) {
                    String prefix = option == CinemaPlaybackOption.FAMILY_WATCH ? "🛡 " : "⏭ ";
                    button.setText(prefix + getString(option.title) + " · " + option.state(this));
                }
            }
        }
    }

    private void cycleManualRotationFromOsd() {
        int next = (manualRotationDegrees + 90) % 360;
        setManualRotation(next);
        boolean ar = CinemaUi.arabic(this);
        Toast.makeText(this,
                (ar ? "تدوير الفيديو: " : "Video rotation: ") + manualRotationDegrees + "°",
                Toast.LENGTH_SHORT).show();
    }

    void openSpeedMenuFromOsd() {
        if (isFinishing() || isDestroyed()) return;
        boolean ar = CinemaUi.arabic(this);
        var sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        boolean remember = sp.getBoolean("pref_remember_video_speed", false);
        int[] speeds = {50, 75, 100, 110, 125, 150, 175, 200};
        String[] baseLabels = {"0.5×", "0.75×", "1.0×", "1.1×", "1.25×", "1.5×", "1.75×", "2.0×"};
        String[] items = new String[speeds.length + 1];
        for (int i = 0; i < speeds.length; i++) {
            items[i] = (speeds[i] == currentSpeedPercent ? "✓  " : "    ") + baseLabels[i];
        }
        items[speeds.length] = (remember ? "✓  " : "○  ")
                + (ar ? "حفظ كسرعة مفضلة دائماً" : "Save as Preferred Speed");
        CinemaUi.dialog(this)
                .setTitle(ar ? "سرعة التشغيل (0.5× – 2.0×)" : "Playback Speed (0.5× – 2.0×)")
                .setItems(items, (d, which) -> {
                    if (which == speeds.length) {
                        boolean nextRem = !remember;
                        CinemaLinkServer.savePreferredPlaybackSpeed(this, currentSpeedPercent, nextRem);
                        Toast.makeText(this, nextRem
                                ? (ar ? "تم حفظ السرعة المفضلة ✓" : "Preferred playback speed saved ✓")
                                : (ar ? "تم إلغاء تثبيت السرعة المفضلة" : "Preferred speed cleared"),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int chosen = speeds[which];
                    speed(chosen / 100f);
                    if (sp.getBoolean("pref_remember_video_speed", false)) {
                        CinemaLinkServer.savePreferredPlaybackSpeed(this, chosen, true);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private TextView osdButton(String label, Runnable action) {
        float d = getResources().getDisplayMetrics().density;
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextSize(14);
        btn.setTextColor(0xfff4f4f6);
        btn.setPadding(Math.round(14 * d), Math.round(8 * d), Math.round(14 * d), Math.round(8 * d));
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        GradientDrawable focusBg = new GradientDrawable();
        focusBg.setColor(0xff26272e);
        focusBg.setCornerRadius(12 * d);
        btn.setBackground(focusBg);
        btn.setOnFocusChangeListener((v, has) -> {
            GradientDrawable fd = new GradientDrawable();
            fd.setColor(has ? 0xffe5b94c : 0xff26272e);
            fd.setCornerRadius(12 * d);
            btn.setBackground(fd);
            btn.setTextColor(has ? 0xff141517 : 0xfff4f4f6);
            if (has) scheduleHideOsd();
        });
        btn.setOnClickListener(v -> {
            scheduleHideOsd();
            action.run();
            if (v.getTag() instanceof CinemaPlaybackOption) refreshOsdLabels();
        });
        var lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(Math.round(4 * d));
        lp.setMarginEnd(Math.round(4 * d));
        btn.setLayoutParams(lp);
        return btn;
    }

    private void showOsdAndFocus(View preferred) {
        if (osdBar == null) return;
        updateLiveControlsVisibility();
        refreshOsdLabels();
        osdBar.setVisibility(View.VISIBLE);
        osdBar.bringToFront();
        if (preferred != null && preferred.getVisibility() == View.VISIBLE && preferred.isFocusable()) {
            preferred.requestFocus();
        } else {
            for (int i = 0; i < osdBar.getChildCount(); i++) {
                View child = osdBar.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE && child.isFocusable()) {
                    child.requestFocus();
                    break;
                }
            }
        }
        scheduleHideOsd();
    }

    private void scheduleHideOsd() {
        progressHandler.removeCallbacks(hideOsd);
        progressHandler.postDelayed(hideOsd, 3500L);
    }

    private boolean dismissPlaybackOverlay() {
        if (osdBar != null && osdBar.getVisibility() == View.VISIBLE) {
            progressHandler.removeCallbacks(hideOsd);
            hideOsd.run();
            return true;
        }
        if (seekHud != null && seekHud.getVisibility() == View.VISIBLE) {
            progressHandler.removeCallbacks(updateSeekHud);
            seekHud.setVisibility(View.GONE);
            return true;
        }
        return false;
    }

    @Override public void onBackPressed() {
        if (isLiveChannelPlayback()) {
            finish();
            return;
        }
        if (dismissPlaybackOverlay()) return;
        finish();
    }

    private boolean consumedOverlayBack;

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (subtitleTimingOverlay != null && subtitleTimingOverlay.handleKeyEvent(event)) {
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && !isLiveChannelPlayback()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                consumedOverlayBack = dismissPlaybackOverlay();
            }
            if (consumedOverlayBack) {
                if (event.getAction() == KeyEvent.ACTION_UP) consumedOverlayBack = false;
                return true;
            }
        }
        if (isLiveChannelPlayback() && (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_REWIND
                || event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_STEP_FORWARD
                || event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD)) {
            return true;
        }
        if (CinemaDpadSeek.isHorizontal(event.getKeyCode()) && seekFromRemote(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            toggle();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
            if (!playing()) toggle();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (playing()) toggle();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_CAPTIONS) {
            if (isLiveChannelPlayback()) return true;
            openSubtitleMenuFromRemote();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (osdBar != null && osdBar.getVisibility() != View.VISIBLE) {
                showOsdAndFocus(isLiveChannelPlayback() ? osdPlayPauseButton : osdSubtitleButton);
                return true;
            }
        }
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                && (osdBar == null || osdBar.getVisibility() != View.VISIBLE)) {
            toggle();
            showOsdAndFocus(osdPlayPauseButton);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public void openSubtitleMenuFromRemote() {
        if (isFinishing() || isDestroyed() || isLiveChannelPlayback()) return;
        runOnUiThread(this::showSubtitleMenuDialog);
    }

    private void showLiveSubtitleTimingOverlay() {
        if (isFinishing() || isDestroyed() || isLiveChannelPlayback() || rootContainer == null) return;
        dismissPlaybackOverlay();
        if (subtitleTimingOverlay == null) {
            subtitleTimingOverlay = new CinemaSubtitleTimingOverlay(this, rootContainer);
        }
        subtitleTimingOverlay.show(subtitleDelayMs, new CinemaSubtitleTimingOverlay.Listener() {
            @Override
            public void onDelayChanged(long newDelayMs) {
                subtitleDelayMs = newDelayMs;
                if (externalSubtitlesEnabled || secondarySubtitlesEnabled) {
                    updateExternalSubtitleNow();
                } else {
                    String intentSub = getIntent() != null ? getIntent().getStringExtra("subtitle_url") : null;
                    if (intentSub != null && (intentSub.startsWith("http://") || intentSub.startsWith("https://"))) {
                        loadRemoteSubtitleAsync(intentSub, false);
                    }
                }
            }

            @Override
            public void onDismissed(long finalDelayMs) {
                subtitleDelayMs = finalDelayMs;
            }
        });
    }

    private void showSubtitleMenuDialog() {
        if (isLiveChannelPlayback()) return;
        boolean ar = CinemaUi.arabic(this);
        String streamTitle = getIntent() != null ? getIntent().getStringExtra("title") : null;
        if (streamTitle == null || streamTitle.isBlank()) streamTitle = "Video";
        final String titleFinal = streamTitle;

        boolean hasSecondary = !secondaryCues.isEmpty();
        Uri data = getIntent() != null ? getIntent().getData() : null;
        String headingExtra = getIntent() != null ? getIntent().getStringExtra("addon_heading") : null;
        String imdbExtra = getIntent() != null ? getIntent().getStringExtra("imdb_id") : null;
        String englishTitleExtra = getIntent() != null ? getIntent().getStringExtra("english_title") : null;
        int releaseYearExtra = getIntent() != null ? getIntent().getIntExtra("release_year", -1) : -1;
        int seasonExtra = getIntent() != null ? getIntent().getIntExtra("season", -1) : -1;
        int episodeExtra = getIntent() != null ? getIntent().getIntExtra("episode", -1) : -1;
        String videoFilenameExtra = getIntent() != null ? getIntent().getStringExtra("video_filename") : null;
        long videoSizeExtra = getIntent() != null ? getIntent().getLongExtra("video_size", 0L) : 0L;
        String videoHashExtra = getIntent() != null ? getIntent().getStringExtra("video_hash") : null;
        String addonItemIdExtra = getIntent() != null ? getIntent().getStringExtra("addon_item_id") : null;

        var hub = CinemaSubtitleHubDialog.create(this)
                .title(getString(com.archos.mediacenter.video.R.string.cinema_sub_hub_short))
                .liveTimingShortcut(subtitleDelayMs, this::showLiveSubtitleTimingOverlay);

        boolean anyTrackActive = false;
        // External / Stream active tracks remain directly visible at top level with crisp compact labels
        if (!externalCues.isEmpty()) {
            boolean primActive = subtitleVisible && externalSubtitlesEnabled && subDisplayMode != SUB_MODE_SECONDARY;
            if (primActive) anyTrackActive = true;
            hub.addTrack(
                    CinemaSubtitleLabels.formatStreamSubtitleLabel(this, titleFinal,
                            getString(com.archos.mediacenter.video.R.string.cinema_sub_active_primary),
                            com.meedx.providers.UnifiedSubtitleConfig.primaryLanguage(this), "SRT"),
                    primActive,
                    () -> setDualSubtitleMode(SUB_MODE_PRIMARY));
        }
        if (hasSecondary) {
            String secName = CinemaSubtitleLabels.formatStreamSubtitleLabel(this, titleFinal,
                    getString(com.archos.mediacenter.video.R.string.cinema_sub_active_secondary),
                    com.meedx.providers.UnifiedSubtitleConfig.secondaryLanguage(this), "SRT");
            hub.activeSecondaryTrack(secName, () -> applyPhoneSubtitleStyle("sub_clear_secondary", 0));
        }
        // Embedded stream tracks grouped cleanly into the Embedded Video Subtitles dropdown
        if (adaptivePlayer != null) {
            var exoTracks = getSubtitleTracksJson();
            for (int i = 0; i < exoTracks.length(); i++) {
                var obj = exoTracks.optJSONObject(i);
                if (obj == null) continue;
                int idx = obj.optInt("index", i);
                String lbl = obj.optString("label", "");
                String lng = obj.optString("language", "");
                boolean sel = obj.optBoolean("selected", false) && externalCues.isEmpty();
                if (sel) anyTrackActive = true;
                String cleanLabel = CinemaSubtitleLabels.formatEmbeddedLabel(this, lng, lbl);
                String langCanon = CinemaSubtitleLabels.resolveEmbeddedLanguageCanonical(lng, lbl);
                hub.addEmbeddedTrack(cleanLabel, langCanon, sel, () -> selectEmbeddedSubtitleTrack(idx));
            }
        }

        Runnable launchSmartSearch = () -> {
            Intent search = new Intent(this, SubsourceSearchActivity.class);
            search.putExtra("title", titleFinal);
            if (englishTitleExtra != null && !englishTitleExtra.isBlank()) search.putExtra("english_title", englishTitleExtra);
            if (releaseYearExtra > 0) search.putExtra("release_year", releaseYearExtra);
            if (headingExtra != null && !headingExtra.isBlank()) search.putExtra("addon_heading", headingExtra);
            if (imdbExtra != null && !imdbExtra.isBlank()) search.putExtra("imdb_id", imdbExtra);
            if (seasonExtra > 0) search.putExtra("season", seasonExtra);
            if (episodeExtra > 0) search.putExtra("episode", episodeExtra);
            if (videoFilenameExtra != null && !videoFilenameExtra.isBlank()) search.putExtra("video_filename", videoFilenameExtra);
            if (videoSizeExtra > 0L) search.putExtra("video_size", videoSizeExtra);
            if (videoHashExtra != null && !videoHashExtra.isBlank()) search.putExtra("video_hash", videoHashExtra);
            if (addonItemIdExtra != null && !addonItemIdExtra.isBlank()) search.putExtra("addon_item_id", addonItemIdExtra);
            search.putExtra("both_providers", true);
            search.putExtra("video_id", -1L);
            if (data != null) search.setData(data);
            startActivityForResult(search, REQ_SUBSOURCE);
        };

        Runnable launchMovieSourcePicker = () -> startActivityForResult(
                CinemaFileBrowserActivity.subtitlePickerIntent(this, data, false), REQ_LOCAL_SUB);

        Runnable fetchOnlineNow = () -> CinemaAutoSubtitleFetcher.fetchNow(
                this, data, titleFinal, englishTitleExtra, imdbExtra, releaseYearExtra,
                seasonExtra > 0 || episodeExtra > 0, seasonExtra, episodeExtra,
                videoFilenameExtra, videoSizeExtra, videoHashExtra, addonItemIdExtra,
                savedUri -> {
                    if (savedUri != null) {
                        onActivityResult(REQ_SUBSOURCE, Activity.RESULT_OK, new Intent().setData(savedUri));
                    }
                },
                launchSmartSearch);

        int activeMode = CinemaSubtitleHubDialog.hubModeFromCentral(this);
        boolean noMatchYet = !anyTrackActive && activeMode != CinemaSubtitleHubDialog.MODE_OFF;
        int currentSizeVal = Math.max(18, Math.min(80, Math.round((subtitleSizeSp - 14f) / 0.35f)));

        hub.subtitleMode(activeMode, noMatchYet,
                        () -> {
                            CinemaSubtitleHubDialog.saveCentralMode(this,
                                    com.meedx.providers.UnifiedSubtitleConfig.SUB_MODE_AUTO);
                            subtitleRevision++;
                            findArabicAddonSubtitles();
                        },
                        () -> {
                            CinemaSubtitleHubDialog.saveCentralMode(this,
                                    com.meedx.providers.UnifiedSubtitleConfig.SUB_MODE_MOVIE_SOURCE);
                            launchMovieSourcePicker.run();
                        },
                        fetchOnlineNow,
                        () -> {
                            CinemaSubtitleHubDialog.saveCentralMode(this,
                                    com.meedx.providers.UnifiedSubtitleConfig.SUB_MODE_OFF);
                            clearSubtitles();
                        })
                .onSearchOnline(launchSmartSearch)
                .onPickMovieSource(launchMovieSourcePicker)
                .onPickFromPhone(() -> {
                    boolean sent = CinemaLinkServer.requestPhoneSubtitle(titleFinal);
                    Toast.makeText(this, sent
                            ? (ar ? "افتح هاتفك لاختيار ملف الترجمة (.srt/.vtt/.ass)" : "Check your Phone to select a subtitle file")
                            : (ar ? "الهاتف غير متصل الآن" : "Phone controller not connected"),
                            Toast.LENGTH_LONG).show();
                })
                .dualControls(true, hasSecondary,
                        () -> {
                            boolean nowOn = com.meedx.providers.UnifiedSubtitleConfig.dualSubtitlesEnabled(this);
                            setDualSubtitleMode(nowOn ? SUB_MODE_DUAL : SUB_MODE_PRIMARY);
                            if (nowOn && secondaryCues.isEmpty()) {
                                findArabicAddonSubtitles();
                            }
                        },
                        () -> {
                            dualSubtitlePosition = com.meedx.providers.UnifiedSubtitleConfig.dualSubPosition(this);
                            applyDualSubtitleLayout();
                            applyAllSubtitleStyles();
                            updateExternalSubtitleNow();
                        },
                        null,
                        () -> applyPhoneSubtitleStyle("sub_clear_secondary", 0))
                .liveAppearance(subtitleDelayMs, currentSizeVal, subtitleColor, subtitleOpaqueBg,
                        subtitleBgOpacity, subtitleOutlineLevel, subtitleOutlineColor, subtitleVisible,
                        (action, value) -> {
                            applyPhoneSubtitleStyle(action, value);
                            persistLiveStyleToUnified();
                        })
                .show();
    }

    private void persistLiveStyleToUnified() {
        int sizeVal = Math.max(18, Math.min(80, Math.round((subtitleSizeSp - 14f) / 0.35f)));
        com.meedx.providers.UnifiedSubtitleConfig.saveAppearance(
                this, sizeVal, subtitleBottomMarginDp, subtitleColor, subtitleOpaqueBg,
                subtitleBgOpacity, subtitleOutlineLevel, subtitleOutlineColor, subtitleDelayMs);
        CinemaSubtitleSyncBridge.onTvSubtitleConfigChanged(this);
    }

    private boolean dualSubtitlesAllowed() {
        return com.meedx.providers.UnifiedSubtitleConfig.dualSubtitlesEnabled(this);
    }

    private void setDualSubtitleMode(int mode) {
        subDisplayMode = mode;
        subtitleVisible = true;
        externalSubtitlesEnabled = !externalCues.isEmpty() && mode != SUB_MODE_SECONDARY;
        secondarySubtitlesEnabled = !secondaryCues.isEmpty() && mode != SUB_MODE_PRIMARY;
        if (adaptivePlayer != null && externalCues.isEmpty()) {
            // In case primary is an ExoPlayer HLS subtitle track
            adaptivePlayer.setTrackSelectionParameters(adaptivePlayer.getTrackSelectionParameters()
                    .buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, mode == SUB_MODE_SECONDARY)
                    .build());
        }
        applyAllSubtitleStyles();
        updateExternalSubtitleNow();
        if (externalSubtitlesEnabled || secondarySubtitlesEnabled) {
            progressHandler.removeCallbacks(subtitleTicker);
            progressHandler.post(subtitleTicker);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode != REQ_SUBSOURCE && requestCode != REQ_ONLINE_SUB && requestCode != REQ_LOCAL_SUB) return;
        Uri subUri = data.getData();
        progressIo.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(subUri);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in == null) return;
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() > 2 * 1024 * 1024) break;
                    out.write(buf, 0, n);
                }
                byte[] clean = SubtitleStorage.utf8(out.toByteArray());
                String name = subUri.getLastPathSegment() != null ? subUri.getLastPathSegment().toLowerCase(java.util.Locale.ROOT) : "";
                String ext = name.endsWith(".vtt") ? "vtt" : (name.endsWith(".ass") || name.endsWith(".ssa")) ? "ass" : "srt";
                runOnUiThread(() -> setSubtitle(ext, clean));
            } catch (Exception error) {
                android.util.Log.w("CinemaHls", "Could not load selected subtitle", error);
            }
        });
    }

    private void findArabicAddonSubtitles() {
        var intent = getIntent();
        if (!intent.getBooleanExtra("addon_subtitles", false)
                || !android.text.TextUtils.isEmpty(intent.getStringExtra("subtitle_url"))) return;
        if (com.meedx.providers.UnifiedSubtitleConfig.preferredSubtitleMode(this)
                == com.meedx.providers.UnifiedSubtitleConfig.SUB_MODE_OFF) return;
        if (!com.meedx.providers.UnifiedSubtitleConfig.autoSubtitlesEnabled(this)) return;
        final String initialImdb = intent.getStringExtra("imdb_id");
        final String title = intent.getStringExtra("title");
        final String englishTitle = intent.getStringExtra("english_title");
        final int releaseYear = intent.getIntExtra("release_year", -1);
        final String addonItemId = intent.getStringExtra("addon_item_id");
        final String addonBaseUrl = intent.getStringExtra("addon_base_url");
        final String mediaType = intent.getStringExtra("media_type");
        final String videoFilename = intent.getStringExtra("video_filename");
        final long videoSize = intent.getLongExtra("video_size", 0L);
        final String videoHash = intent.getStringExtra("video_hash");
        final java.util.ArrayList<String> inlineSubUrls = intent.getStringArrayListExtra("stream_subtitle_urls");
        final java.util.ArrayList<String> inlineSubLangs = intent.getStringArrayListExtra("stream_subtitle_langs");
        int season = intent.getIntExtra("season", 0);
        int episode = intent.getIntExtra("episode", 0);
        int revision = subtitleRevision;
        String primaryCanon = com.meedx.providers.UnifiedSubtitleConfig.primaryLanguage(this);
        String primaryIso = CinemaLanguagePreferences.toIso2(primaryCanon);
        if (primaryIso.isEmpty()) primaryIso = "ar";
        String secondaryIso = CinemaLanguagePreferences.toIso2(CinemaLanguagePreferences.secondarySubtitleLanguage(this));
        boolean wantDual = dualSubtitlesAllowed() && !secondaryIso.isEmpty() && !secondaryIso.equalsIgnoreCase(primaryIso);
        final String pIso = primaryIso;
        final String pCanon = primaryCanon;
        final String sIso = secondaryIso;
        new Thread(() -> {
            java.util.LinkedHashSet<String> primaryUrls = new java.util.LinkedHashSet<>();

            // 1. Check inline stream subtitles attached directly to the Stremio stream object
            if (inlineSubUrls != null && !inlineSubUrls.isEmpty()) {
                for (int i = 0; i < inlineSubUrls.size(); i++) {
                    String u = inlineSubUrls.get(i);
                    String l = (inlineSubLangs != null && i < inlineSubLangs.size()) ? inlineSubLangs.get(i) : "";
                    if (u != null && !u.isBlank() && pCanon.equals(com.meedx.providers.UnifiedSubtitleConfig.canonicalLangName(l))) {
                        primaryUrls.add(u);
                    }
                }
            }

            // 2. Check the originating add-on's /subtitles/{type}/{itemId}[/{extra}].json directly
            if (primaryUrls.isEmpty() && addonBaseUrl != null && !addonBaseUrl.isBlank()
                    && addonItemId != null && !addonItemId.isBlank()) {
                for (var sub : com.meedx.providers.StremioAddons.fetchAddonSubtitles(
                        addonBaseUrl, mediaType, addonItemId, videoFilename, videoSize, videoHash)) {
                    if (pCanon.equals(com.meedx.providers.UnifiedSubtitleConfig.canonicalLangName(sub.lang()))) {
                        primaryUrls.add(sub.url());
                    }
                }
            }

            // 3. Resolve IMDb ID from English hint (e.g. Persian Stremio meta.title_en) if missing, then query Stremio/OpenSubtitles
            String imdb = (initialImdb != null && initialImdb.matches("tt[0-9]+")) ? initialImdb : "";
            if (imdb.isEmpty() && ((englishTitle != null && !englishTitle.isBlank()) || (title != null && !title.isBlank()))) {
                var rq = com.meedx.providers.UnifiedSubtitleEngine.resolveQuery(
                        getApplicationContext(), title, englishTitle, "", releaseYear,
                        season > 0 || episode > 0, season, episode);
                if (rq.imdbId != null && rq.imdbId.matches("tt[0-9]+")) imdb = rq.imdbId;
            }
            String subLookupId = !imdb.isEmpty() ? imdb
                    : (addonItemId != null && addonItemId.startsWith("kitsu:") ? addonItemId : "");
            if (!subLookupId.isEmpty()) {
                primaryUrls.addAll(com.meedx.providers.StremioAddons.subtitleUrls(
                        getApplicationContext(), subLookupId, season, episode, pIso, videoFilename, videoSize, videoHash));
            }

            for (String url : primaryUrls) {
                if (isDestroyed() || isFinishing()) return;
                try {
                    var subtitle = CinemaSubtitleCodec.downloadUnpackAndCache(this, url);
                    if (parseExternalCues(subtitle.ext(), subtitle.utf8Bytes()).isEmpty()) continue;
                    runOnUiThread(() -> {
                        if (!isDestroyed() && !isFinishing() && revision == subtitleRevision) {
                            setSubtitle(subtitle.ext(), subtitle.utf8Bytes());
                        }
                    });
                    break;
                } catch (Exception ignored) {}
            }
            if (wantDual && !subLookupId.isEmpty() && !isDestroyed() && !isFinishing()) {
                var secondaryUrls = com.meedx.providers.StremioAddons.subtitleUrls(
                        getApplicationContext(), subLookupId, season, episode, sIso, videoFilename, videoSize, videoHash);
                for (String url : secondaryUrls) {
                    if (isDestroyed() || isFinishing()) return;
                    try {
                        var subtitle = CinemaSubtitleCodec.downloadUnpackAndCache(this, url);
                        if (parseExternalCues(subtitle.ext(), subtitle.utf8Bytes()).isEmpty()) continue;
                        runOnUiThread(() -> {
                            if (!isDestroyed() && !isFinishing() && revision == subtitleRevision) {
                                setSubtitle(subtitle.ext(), subtitle.utf8Bytes(), true);
                            }
                        });
                        break;
                    } catch (Exception ignored) {}
                }
            }
        }, "addon-multi-subtitle").start();
    }

    private void loadRemoteSubtitleAsync(String subtitleUrl, boolean isSecondary) {
        if (subtitleUrl == null || subtitleUrl.isBlank()) return;
        progressIo.execute(() -> {
            try {
                var decoded = CinemaSubtitleCodec.downloadUnpackAndCache(this, subtitleUrl);
                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing()) {
                        setSubtitle(decoded.ext(), decoded.utf8Bytes(), isSecondary);
                    }
                });
            } catch (Exception e) {
                android.util.Log.w("CinemaHls", "Could not load remote subtitle", e);
            }
        });
    }

    int getManualRotationDegrees() {
        return manualRotationDegrees;
    }

    int getCurrentSpeedPercent() {
        return currentSpeedPercent;
    }

    void setManualRotation(int degrees) {
        int norm = normalizeRotationDegrees(degrees);
        manualRotationDegrees = (norm == 90 || norm == 180 || norm == 270) ? norm : 0;
        refreshOsdLabels();
        if (manualRotationDegrees != 0) ensureTexturePresentation();
        updateAdaptivePresentation();
    }

    private static boolean needsTexturePresentation(int width, int height, int rotation) {
        return rotation != 0 || width > 0 && height > width;
    }

    private TextureView newVideoTexture() {
        var texture = new TextureView(this);
        texture.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) {
                applyTextureViewTransform(texture, lastUnappliedRotation, lastEffectiveWidth, lastEffectiveHeight);
            }
        });
        return texture;
    }

    private void ensureTexturePresentation() {
        if (!(adaptiveSurface instanceof SurfaceView surface) || aspectContainer == null) return;
        var texture = newVideoTexture();
        adaptiveSurface = texture;
        if (adaptivePlayer != null) {
            adaptivePlayer.clearVideoSurfaceView(surface);
            adaptivePlayer.setVideoTextureView(texture);
        }
        aspectContainer.removeView(surface);
        aspectContainer.addView(texture, 0, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        texture.setKeepScreenOn(getWindow().getDecorView().getKeepScreenOn());
    }

    private void updateAdaptivePresentation() {
        int w = rawVideoWidth > 0 ? rawVideoWidth : (intentVideoWidth > 0 ? intentVideoWidth : 1920);
        int h = rawVideoHeight > 0 ? rawVideoHeight : (intentVideoHeight > 0 ? intentVideoHeight : 1080);
        float par = rawVideoPixelRatio > 0f ? rawVideoPixelRatio : 1.0f;
        int containerRot = rawVideoRotation != 0 ? rawVideoRotation : intentVideoRotation;

        float baseW;
        float baseH;
        if (rawUnappliedRotation == 90 || rawUnappliedRotation == 270) {
            baseW = h;
            baseH = w * par;
        } else if ((containerRot == 90 || containerRot == 270) && w > h) {
            // Buffer dimensions stayed landscape while MediaCodec/SurfaceFlinger rotates output to portrait.
            baseW = h;
            baseH = w * par;
        } else {
            baseW = w * par;
            baseH = h;
        }

        float dispW = (manualRotationDegrees == 90 || manualRotationDegrees == 270) ? baseH : baseW;
        float dispH = (manualRotationDegrees == 90 || manualRotationDegrees == 270) ? baseW : baseH;
        if (dispW <= 0f || dispH <= 0f) return;

        int transformRot = normalizeRotationDegrees(rawUnappliedRotation + manualRotationDegrees);
        lastUnappliedRotation = transformRot;
        lastEffectiveWidth = dispW;
        lastEffectiveHeight = dispH;
        applyVideoSurfacePresentation(dispW / dispH, transformRot, dispW, dispH);
    }
    private static int normalizeRotationDegrees(int degrees) {
        int normalized = degrees % 360;
        return normalized < 0 ? normalized + 360 : normalized;
    }

    private float computeDisplayAspectRatio(int width, int height, int rawRotation, float rawPixelRatio) {
        if (width <= 0 || height <= 0) return 0f;
        int rotation = normalizeRotationDegrees(rawRotation);
        float pixelRatio = rawPixelRatio > 0f ? rawPixelRatio : 1.0f;
        float parWidth = width * pixelRatio;
        float effectiveWidth = (rotation == 90 || rotation == 270) ? height : parWidth;
        float effectiveHeight = (rotation == 90 || rotation == 270) ? parWidth : height;
        if (effectiveHeight <= 0f) return 0f;
        lastUnappliedRotation = rotation;
        lastEffectiveWidth = effectiveWidth;
        lastEffectiveHeight = effectiveHeight;
        return effectiveWidth / effectiveHeight;
    }

    private void applyVideoSurfacePresentation(float aspectRatio, int rotation, float effectiveWidth, float effectiveHeight) {
        if (aspectContainer != null) {
            aspectContainer.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
            if (aspectRatio > 0f && Float.isFinite(aspectRatio)) {
                aspectContainer.setAspectRatio(aspectRatio);
            }
        }
        if (adaptiveSurface instanceof TextureView textureView) {
            applyTextureViewTransform(textureView, rotation, effectiveWidth, effectiveHeight);
        } else if (adaptiveSurface instanceof SurfaceView surfaceView) {
            // MediaCodec + SurfaceFlinger handle buffer rotation on SurfaceView natively;
            // keep View rotation at 0 so the SurfaceView bounds match RESIZE_MODE_FIT cleanly.
            if (surfaceView.getRotation() != 0f) surfaceView.setRotation(0f);
        }
    }

    private void applyTextureViewTransform(TextureView textureView, int rotation, float effectiveWidth, float effectiveHeight) {
        if (textureView == null || effectiveWidth <= 0f || effectiveHeight <= 0f) return;
        int vw = textureView.getWidth();
        int vh = textureView.getHeight();
        if (vw <= 0 || vh <= 0) return;
        int normRot = normalizeRotationDegrees(rotation);
        float cx = vw * 0.5f;
        float cy = vh * 0.5f;
        float targetAspect = effectiveWidth / effectiveHeight;
        float viewAspect = (float) vw / (float) vh;
        float targetW = viewAspect > targetAspect ? (vh * targetAspect) : vw;
        float targetH = viewAspect > targetAspect ? vh : (vw / targetAspect);
        Matrix matrix = new Matrix();
        if (normRot != 0) {
            matrix.postRotate(normRot, cx, cy);
        }
        float rotatedBaseW = (normRot == 90 || normRot == 270) ? vh : vw;
        float rotatedBaseH = (normRot == 90 || normRot == 270) ? vw : vh;
        float scaleX = rotatedBaseW > 0f ? (targetW / rotatedBaseW) : 1.0f;
        float scaleY = rotatedBaseH > 0f ? (targetH / rotatedBaseH) : 1.0f;
        matrix.postScale(scaleX, scaleY, cx, cy);
        textureView.setTransform(matrix);
    }

    private void attachExternalSubtitleOverlay(FrameLayout root) {
        int hPad = Math.round(24 * getResources().getDisplayMetrics().density);
        topSubtitleStack = new LinearLayout(this);
        topSubtitleStack.setOrientation(LinearLayout.VERTICAL);
        topSubtitleStack.setGravity(Gravity.CENTER_HORIZONTAL);
        topSubtitleStack.setPadding(hPad, 0, hPad, 0);
        var topLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        topLp.topMargin = Math.round(24 * getResources().getDisplayMetrics().density);
        root.addView(topSubtitleStack, topLp);

        bottomSubtitleStack = new LinearLayout(this);
        bottomSubtitleStack.setOrientation(LinearLayout.VERTICAL);
        bottomSubtitleStack.setGravity(Gravity.CENTER_HORIZONTAL);
        bottomSubtitleStack.setPadding(hPad, 0, hPad, 0);
        var botLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        botLp.bottomMargin = Math.round(subtitleBottomMarginDp * getResources().getDisplayMetrics().density);
        root.addView(bottomSubtitleStack, botLp);

        int maxSubW = Math.max(CinemaUi.dp(this, 260),
                getResources().getDisplayMetrics().widthPixels - CinemaUi.dp(this, 48));
        externalSubtitleView = new com.archos.mediacenter.video.player.SubtitleTextView(this);
        externalSubtitleView.setGravity(Gravity.CENTER);
        externalSubtitleView.setTextColor(subtitleColor);
        externalSubtitleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        externalSubtitleView.setTextSize(subtitleSizeSp);
        externalSubtitleView.setMaxWidth(maxSubW);
        externalSubtitleView.setSubtitleBackground(subtitleOpaqueBg, subtitleBgOpacity);
        externalSubtitleView.setOutline(subtitleOutlineLevel, subtitleOutlineColor);
        externalSubtitleView.setVisibility(View.GONE);

        secondarySubtitleView = new com.archos.mediacenter.video.player.SubtitleTextView(this);
        secondarySubtitleView.setGravity(Gravity.CENTER);
        secondarySubtitleView.setTextColor(0xfffef08a);
        secondarySubtitleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        secondarySubtitleView.setTextSize(Math.max(14f, subtitleSizeSp - 2f));
        secondarySubtitleView.setMaxWidth(maxSubW);
        secondarySubtitleView.setSubtitleBackground(subtitleOpaqueBg, subtitleBgOpacity);
        secondarySubtitleView.setOutline(subtitleOutlineLevel, subtitleOutlineColor);
        secondarySubtitleView.setVisibility(View.GONE);

        applyDualSubtitleLayout();
    }

    private void applyDualSubtitleLayout() {
        if (topSubtitleStack == null || bottomSubtitleStack == null
                || externalSubtitleView == null || secondarySubtitleView == null) {
            return;
        }
        if (externalSubtitleView.getParent() instanceof ViewGroup vg) vg.removeView(externalSubtitleView);
        if (secondarySubtitleView.getParent() instanceof ViewGroup vg) vg.removeView(secondarySubtitleView);
        topSubtitleStack.removeAllViews();
        bottomSubtitleStack.removeAllViews();

        if (bottomSubtitleStack.getLayoutParams() instanceof FrameLayout.LayoutParams botLp) {
            botLp.bottomMargin = Math.round(subtitleBottomMarginDp * getResources().getDisplayMetrics().density);
            bottomSubtitleStack.setLayoutParams(botLp);
        }
        int gapPx = Math.round(8 * getResources().getDisplayMetrics().density);

        if (dualSubtitlePosition == DUAL_POS_STACKED_BOTTOM) {
            var secLp = new LinearLayout.LayoutParams(-2, -2);
            secLp.bottomMargin = gapPx;
            bottomSubtitleStack.addView(secondarySubtitleView, secLp);
            bottomSubtitleStack.addView(externalSubtitleView, new LinearLayout.LayoutParams(-2, -2));
        } else if (dualSubtitlePosition == DUAL_POS_SPLIT_PRIMARY_TOP) {
            topSubtitleStack.addView(externalSubtitleView, new LinearLayout.LayoutParams(-2, -2));
            bottomSubtitleStack.addView(secondarySubtitleView, new LinearLayout.LayoutParams(-2, -2));
        } else {
            topSubtitleStack.addView(secondarySubtitleView, new LinearLayout.LayoutParams(-2, -2));
            bottomSubtitleStack.addView(externalSubtitleView, new LinearLayout.LayoutParams(-2, -2));
        }
    }

    boolean matchesCastId(String castId) {
        return currentCastId == null || currentCastId.isEmpty() || currentCastId.equals(castId);
    }

    void clearSubtitlesFromRemote() {
        clearSubtitles();
    }

    private androidx.media3.common.MediaItem buildAdaptiveMediaItem(String subtitleUriString) {
        var item = new androidx.media3.common.MediaItem.Builder().setUri(localTorrent == null ? getIntent().getData() : localTorrent.playbackUri());
        if ("dash".equals(currentFormat)) item.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD);
        if ("hls".equals(currentFormat)) item.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8);
        if (subtitleUriString != null && !subtitleUriString.isEmpty()) {
            String explicitMime = getIntent().getStringExtra("subtitle_mime");
            String explicitLang = getIntent().getStringExtra("subtitle_language");
            String lowerSub = subtitleUriString.toLowerCase(java.util.Locale.ROOT);
            String subMime;
            if (explicitMime != null && !explicitMime.isBlank()) {
                String m = explicitMime.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
                subMime = switch (m) {
                    case "application/x-subrip", "text/srt" -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP;
                    case "text/x-ssa", "text/x-ass" -> androidx.media3.common.MimeTypes.TEXT_SSA;
                    case "application/vnd.apple.mpegurl", "application/x-mpegurl" -> androidx.media3.common.MimeTypes.APPLICATION_M3U8;
                    default -> m;
                };
            } else {
                subMime = lowerSub.contains(".srt") ? androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
                        : (lowerSub.contains(".ass") || lowerSub.contains(".ssa")) ? androidx.media3.common.MimeTypes.TEXT_SSA
                        : androidx.media3.common.MimeTypes.TEXT_VTT;
            }
            String subLang = (explicitLang != null && !explicitLang.isBlank()) ? explicitLang.trim() : "ar";
            var subCfg = new androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUriString))
                    .setMimeType(subMime)
                    .setLanguage(subLang)
                    .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                    .build();
            item.setSubtitleConfigurations(List.of(subCfg));
        }
        return item.build();
    }

    private View torrentLoadingOverlay(FrameLayout root) {
        var loading = new android.widget.LinearLayout(this);
        loading.setOrientation(android.widget.LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER);
        loading.setBackgroundColor(0xff171717);
        var spinner = new android.widget.ProgressBar(this);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xffe5a93b));
        int size = CinemaUi.dp(this, 32);
        loading.addView(spinner, new android.widget.LinearLayout.LayoutParams(size, size));
        var label = CinemaUi.text(this, 17, true);
        label.setText(com.archos.mediacenter.video.R.string.addon_torrent_prepare);
        label.setPadding(0, CinemaUi.dp(this, 12), 0, 0);
        loading.addView(label);
        root.addView(loading, new FrameLayout.LayoutParams(-1, -1));
        return loading;
    }

    private void initLiveCandidates() {
        liveCandidates.clear();
        activeLiveCandidateIndex = 0;
        liveStreamMode = LIVE_MODE_AUTO;
        if (!isCatalogChannel() || getIntent() == null) return;
        var urls = getIntent().getStringArrayListExtra(FALLBACK_URLS);
        var labels = getIntent().getStringArrayListExtra(FALLBACK_LABELS);
        var formats = getIntent().getStringArrayListExtra(FALLBACK_FORMATS);
        var headersList = getIntent().getParcelableArrayListExtra(FALLBACK_HEADERS);
        if (urls != null && !urls.isEmpty()) {
            for (int i = 0; i < urls.size(); i++) {
                String u = urls.get(i);
                if (u == null || u.isBlank()) continue;
                String lbl = labels != null && i < labels.size() ? labels.get(i) : "";
                String fmt = formats != null && i < formats.size() ? formats.get(i) : "addon";
                Bundle hdr = headersList != null && i < headersList.size() && headersList.get(i) instanceof Bundle b ? b : null;
                liveCandidates.add(new LiveCandidate(u, lbl, fmt, hdr));
            }
        }
        if (liveCandidates.isEmpty() && getIntent().getData() != null) {
            liveCandidates.add(new LiveCandidate(
                    getIntent().getData().toString(),
                    getIntent().getStringExtra("title"),
                    currentFormat == null ? "addon" : currentFormat,
                    getIntent().getBundleExtra(CHANNEL_HEADERS)));
        }
    }

    private androidx.media3.datasource.DefaultHttpDataSource.Factory buildHttpDataSourceFactory(
            String uaOverride, String refererOverride, Bundle extraHeaders, boolean liveChannel) {
        var factory = new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(liveChannel ? 7500 : 8500)
                .setReadTimeoutMs(liveChannel ? 11_000 : 12_500);
        String ua = uaOverride;
        var props = new java.util.LinkedHashMap<String, String>();
        if (extraHeaders != null) {
            for (String key : extraHeaders.keySet()) {
                String val = extraHeaders.getString(key);
                if (key == null || val == null || key.isBlank() || val.isBlank()) continue;
                if ("user-agent".equalsIgnoreCase(key)) {
                    if (ua == null || ua.isBlank()) ua = val;
                } else {
                    props.put(key, val);
                }
            }
        }
        if (refererOverride != null && !refererOverride.isBlank()
                && props.keySet().stream().noneMatch(k -> "referer".equalsIgnoreCase(k) || "referrer".equalsIgnoreCase(k))) {
            props.put("Referer", refererOverride);
        }
        factory.setUserAgent(ua != null && !ua.isBlank() ? ua : DEFAULT_HTTP_UA);
        if (!props.isEmpty()) {
            factory.setDefaultRequestProperties(props);
        }
        return factory;
    }

    private androidx.media3.exoplayer.source.MediaSource buildLiveMediaSource(LiveCandidate candidate, int mode) {
        Bundle hdrs = candidate.headers();
        String ua = hdrs != null ? hdrs.getString("User-Agent", getIntent().getStringExtra(CHANNEL_USER_AGENT))
                : getIntent().getStringExtra(CHANNEL_USER_AGENT);
        String ref = hdrs != null ? hdrs.getString("Referer", getIntent().getStringExtra(CHANNEL_REFERER))
                : getIntent().getStringExtra(CHANNEL_REFERER);
        var httpFactory = buildHttpDataSourceFactory(ua, ref, hdrs, true);
        var errorPolicy = new androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(1);
        Uri uri = Uri.parse(candidate.url());
        var itemBuilder = new androidx.media3.common.MediaItem.Builder().setUri(uri);
        if (mode == LIVE_MODE_PROGRESSIVE) {
            return new androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                    httpFactory, new androidx.media3.extractor.DefaultExtractorsFactory())
                    .setLoadErrorHandlingPolicy(errorPolicy)
                    .createMediaSource(itemBuilder.build());
        }
        if (mode == LIVE_MODE_HLS) {
            itemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8);
            return new androidx.media3.exoplayer.hls.HlsMediaSource.Factory(httpFactory)
                    .setAllowChunklessPreparation(true)
                    .setLoadErrorHandlingPolicy(errorPolicy)
                    .createMediaSource(itemBuilder.build());
        }
        String fmt = candidate.format();
        if ("dash".equals(fmt)) itemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD);
        else if ("hls".equals(fmt)) itemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8);
        return new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
                .setDataSourceFactory(httpFactory)
                .setLoadErrorHandlingPolicy(errorPolicy)
                .createMediaSource(itemBuilder.build());
    }

    private boolean currentLiveCandidateLooksHls() {
        if (activeLiveCandidateIndex < 0 || activeLiveCandidateIndex >= liveCandidates.size()) {
            return "hls".equals(currentFormat);
        }
        var c = liveCandidates.get(activeLiveCandidateIndex);
        if ("hls".equals(c.format())) return true;
        String lower = c.url().toLowerCase(java.util.Locale.ROOT);
        return lower.contains(".m3u8");
    }

    private void switchLiveCandidate(int index, int mode) {
        if (adaptivePlayer == null || index < 0 || index >= liveCandidates.size()) return;
        activeLiveCandidateIndex = index;
        liveStreamMode = mode;
        adaptiveRetryCount = 0;
        addonFrameRendered = false;
        if (pendingRetryRunnable != null) {
            progressHandler.removeCallbacks(pendingRetryRunnable);
            pendingRetryRunnable = null;
        }
        progressHandler.removeCallbacks(channelWatchdog);
        if (liveChannelStatus != null) {
            liveChannelStatus.setText(R.string.addon_live_connecting);
            liveChannelStatus.setVisibility(View.VISIBLE);
        }
        refreshOsdLabels();
        adaptivePlayer.stop();
        adaptivePlayer.setMediaSource(buildLiveMediaSource(liveCandidates.get(index), mode));
        adaptivePlayer.prepare();
        adaptivePlayer.play();
    }

    private static boolean isFormatMismatchError(androidx.media3.common.PlaybackException error) {
        if (error == null) return false;
        if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
                || error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) {
            return true;
        }
        Throwable cause = error.getCause();
        while (cause != null) {
            if (cause instanceof androidx.media3.common.ParserException) return true;
            String msg = cause.getMessage();
            if (msg != null && (msg.contains("#EXTM3U") || msg.contains("UnrecognizedInputFormat"))) return true;
            cause = cause.getCause();
        }
        return false;
    }

    private void startAdaptive(FrameLayout root, String format, String audio) {
        initLiveCandidates();
        aspectContainer = new androidx.media3.ui.AspectRatioFrameLayout(this);
        aspectContainer.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
        root.addView(aspectContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        boolean useTextureView = manualRotationDegrees != 0
                || getIntent().getBooleanExtra("use_texture_view", false)
                || needsTexturePresentation(getIntent().getIntExtra("video_width", 0),
                    getIntent().getIntExtra("video_height", 0), normalizeRotationDegrees(getIntent().getIntExtra("video_rotation", 0)));
        adaptiveSurface = useTextureView ? newVideoTexture() : new SurfaceView(this);
        aspectContainer.addView(adaptiveSurface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        exoSubtitles = new com.archos.mediacenter.video.player.SubtitleTextView(this);
        exoSubtitles.setGravity(Gravity.CENTER);
        exoSubtitles.setTextColor(subtitleColor);
        exoSubtitles.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        exoSubtitles.setTextSize(subtitleSizeSp);
        exoSubtitles.setMaxWidth(Math.max(CinemaUi.dp(this, 260),
                getResources().getDisplayMetrics().widthPixels - CinemaUi.dp(this, 48)));
        exoSubtitles.setSubtitleBackground(subtitleOpaqueBg, subtitleBgOpacity);
        exoSubtitles.setOutline(subtitleOutlineLevel, subtitleOutlineColor);
        exoSubtitles.setVisibility(View.GONE);
        var subLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        subLp.bottomMargin = Math.round(subtitleBottomMarginDp * getResources().getDisplayMetrics().density);
        root.addView(exoSubtitles, subLp);
        attachExternalSubtitleOverlay(root);
        attachOsdBar(root);
        View torrentLoading = localTorrent == null ? null : torrentLoadingOverlay(root);
        if (isCatalogChannel()) {
            liveChannelStatus = CinemaUi.text(this, 18, true);
            liveChannelStatus.setText(R.string.addon_live_connecting);
            liveChannelStatus.setTextColor(0xffe5a93b);
            liveChannelStatus.setGravity(Gravity.CENTER);
            liveChannelStatus.setPadding(CinemaUi.dp(this, 20), CinemaUi.dp(this, 12),
                    CinemaUi.dp(this, 20), CinemaUi.dp(this, 12));
            liveChannelStatus.setBackground(CinemaUi.rounded(0xff171717, CinemaUi.dp(this, 12), 0xff54432c));
            root.addView(liveChannelStatus, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        }
        setContentView(root);
        intentVideoWidth = getIntent().getIntExtra("video_width", 0);
        intentVideoHeight = getIntent().getIntExtra("video_height", 0);
        intentVideoRotation = normalizeRotationDegrees(getIntent().getIntExtra("video_rotation", 0));
        if (intentVideoWidth > 0 && intentVideoHeight > 0) {
            rawVideoWidth = intentVideoWidth;
            rawVideoHeight = intentVideoHeight;
            rawVideoRotation = intentVideoRotation;
            rawUnappliedRotation = 0;
            updateAdaptivePresentation();
        }
        var mediaSourceFactory = new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
                .setLoadErrorHandlingPolicy(new androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(localTorrent == null ? 5 : 1));
        if (localTorrent != null) {
            mediaSourceFactory.setDataSourceFactory(
                    new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                            .setAllowCrossProtocolRedirects(true)
                            .setReadTimeoutMs(65_000));
        } else if (isCatalogChannel()) {
            var factory = buildHttpDataSourceFactory(
                    getIntent().getStringExtra(CHANNEL_USER_AGENT),
                    getIntent().getStringExtra(CHANNEL_REFERER),
                    getIntent().getBundleExtra(CHANNEL_HEADERS),
                    true);
            mediaSourceFactory.setDataSourceFactory(factory).setLoadErrorHandlingPolicy(
                    new androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(1));
        } else {
            var httpFactory = buildHttpDataSourceFactory(
                    getIntent().getStringExtra(CHANNEL_USER_AGENT),
                    getIntent().getStringExtra(CHANNEL_REFERER),
                    getIntent().getBundleExtra(CHANNEL_HEADERS),
                    false);
            mediaSourceFactory.setDataSourceFactory(
                    new androidx.media3.datasource.DefaultDataSource.Factory(this, httpFactory));
        }
        var loadControl = new androidx.media3.exoplayer.DefaultLoadControl.Builder();
        if (isCatalogChannel()) {
            loadControl.setBufferDurationsMs(4500, 20_000, 1000, 2000);
        } else if (localTorrent == null) {
            loadControl.setBufferDurationsMs(15_000, 50_000, 2500, 5000)
                    .setPrioritizeTimeOverSizeThresholds(true);
        } else {
            loadControl.setBufferDurationsMs(6000, 20_000, 700, 1500)
                    .setTargetBufferBytes(8 * 1024 * 1024);
        }
        var builder = new androidx.media3.exoplayer.ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl.build());
        adaptivePlayer = builder.build();
        boolean applyPreferredAudio = localTorrent != null || getIntent().getBooleanExtra("addon_subtitles", false);
        String[] preferredAudio = applyPreferredAudio ? CinemaPreferredAudio.languages(this) : new String[0];
        if (preferredAudio.length > 0) {
            adaptivePlayer.setTrackSelectionParameters(adaptivePlayer.getTrackSelectionParameters()
                    .buildUpon().setPreferredAudioLanguages(preferredAudio).build());
        }
        adaptivePlayer.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);
        adaptivePlayer.setHandleAudioBecomingNoisy(true);
        adaptivePlayer.setVideoScalingMode(androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        int height = getIntent().getIntExtra("height", 0);
        if (height > 0) adaptivePlayer.setTrackSelectionParameters(adaptivePlayer.getTrackSelectionParameters()
                .buildUpon().setMaxVideoSize(Integer.MAX_VALUE, height).build());
        if (adaptiveSurface instanceof TextureView textureView) {
            adaptivePlayer.setVideoTextureView(textureView);
        } else if (adaptiveSurface instanceof SurfaceView surfaceView) {
            adaptivePlayer.setVideoSurfaceView(surfaceView);
        }

        String subUrl = getIntent().getStringExtra("subtitle_url");
        String sub2Url = getIntent().getStringExtra("secondary_subtitle_url");
        if (com.archos.mediacenter.video.player.CinemaSubtitleText.isStoryboardTrackHint(subUrl)) {
            subUrl = null;
        }
        if (com.archos.mediacenter.video.player.CinemaSubtitleText.isStoryboardTrackHint(sub2Url)) {
            sub2Url = null;
        }
        if (getIntent().getBooleanExtra("suppress_auto_subtitles", false)
                && (subUrl == null || subUrl.isBlank())) {
            adaptivePlayer.setTrackSelectionParameters(adaptivePlayer.getTrackSelectionParameters()
                    .buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                    .build());
        }
        boolean subIsHlsPlaylist = subUrl != null && subUrl.toLowerCase(java.util.Locale.ROOT).contains(".m3u8");
        if (isCatalogChannel() && !liveCandidates.isEmpty() && (audio == null || audio.isEmpty())) {
            adaptivePlayer.setMediaSource(buildLiveMediaSource(liveCandidates.get(activeLiveCandidateIndex), liveStreamMode));
        } else {
            var mediaItem = buildAdaptiveMediaItem(subIsHlsPlaylist ? subUrl : null);
            if (audio == null || audio.isEmpty()) {
                adaptivePlayer.setMediaItem(mediaItem);
            } else {
                adaptivePlayer.setMediaSource(new androidx.media3.exoplayer.source.MergingMediaSource(
                        mediaSourceFactory.createMediaSource(mediaItem),
                        mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(audio))));
            }
        }
        if (subUrl != null && !subUrl.isBlank() && !subIsHlsPlaylist) {
            loadRemoteSubtitleAsync(subUrl, false);
        }
        if (sub2Url != null && !sub2Url.isBlank()) {
            loadRemoteSubtitleAsync(sub2Url, true);
        }
        adaptivePlayer.addListener(new androidx.media3.common.Player.Listener() {
            @Override public void onCues(androidx.media3.common.text.CueGroup cueGroup) {
                if (externalSubtitlesEnabled) {
                    exoSubtitles.setText(null);
                    return;
                }
                if (cueGroup == null || cueGroup.cues.isEmpty()) {
                    exoSubtitles.setText(null);
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (var cue : cueGroup.cues) {
                    String text = CinemaSubtitleText.displayText(cue.text);
                    if (!text.isBlank()) {
                        if (playbackFilters != null) playbackFilters.observeSubtitle(text, -1, -1);
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(text);
                    }
                }
                exoSubtitles.setText(sb.length() > 0 ? sb.toString() : null);
            }

            @Override public void onMetadata(androidx.media3.common.Metadata metadata) {
                if (playbackFilters != null) playbackFilters.addChapterMetadata(metadata);
            }

            @Override public void onRenderedFirstFrame() {
                if (liveChannelStatus != null) {
                    liveChannelStatus.setVisibility(View.GONE);
                    adaptiveRetryCount = 0;
                    progressHandler.removeCallbacks(channelWatchdog);
                }
                if (!addonFrameRendered || addonPlaybackFailed) {
                    addonFrameRendered = true;
                    addonPlaybackFailed = false;
                    resetAddonPlaybackSample(position());
                }
                if (torrentLoading != null) torrentLoading.setVisibility(View.GONE);
            }

            @Override public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                loadChapterMetadata(tracks);
            }

            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize) {
                if (videoSize == null || videoSize.width <= 0 || videoSize.height <= 0) return;
                rawVideoWidth = videoSize.width;
                rawVideoHeight = videoSize.height;
                rawUnappliedRotation = normalizeRotationDegrees(videoSize.unappliedRotationDegrees);
                rawVideoPixelRatio = videoSize.pixelWidthHeightRatio > 0f ? videoSize.pixelWidthHeightRatio : 1.0f;
                androidx.media3.common.Format fmt = adaptivePlayer != null ? adaptivePlayer.getVideoFormat() : null;
                int containerRot = fmt != null ? normalizeRotationDegrees(fmt.rotationDegrees) : 0;
                rawVideoRotation = containerRot != 0 ? containerRot : intentVideoRotation;
                if (needsTexturePresentation(videoSize.width, videoSize.height, rawVideoRotation)) {
                    ensureTexturePresentation();
                }
                updateAdaptivePresentation();
            }

            @Override public void onPositionDiscontinuity(
                    androidx.media3.common.Player.PositionInfo oldPosition,
                    androidx.media3.common.Player.PositionInfo newPosition,
                    int reason) {
                CinemaLinkServer.playbackChanged();
                resetAddonPlaybackSample(newPosition.positionMs);
                if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK) {
                    if (initialResumeSeekInFlight) {
                        initialResumeSeekInFlight = false;
                        return;
                    }
                    long dur = duration();
                    if (dur > 0 && newPosition.positionMs >= 0 && newPosition.positionMs * 100L < dur * 85L) {
                        userSoughtBelowThreshold = true;
                    }
                    saveStreamProgressAtAsync(newPosition.positionMs, false);
                }
            }

            @Override public void onEvents(androidx.media3.common.Player player, androidx.media3.common.Player.Events events) {
                updatePlaybackScreen(player);
            }

            @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                refreshOsdLabels();
            }

            @Override public void onIsPlayingChanged(boolean isPlaying) {
                refreshOsdLabels();
                if (isPlaying) {
                    playbackStarted = true;
                    if (torrentLoading != null && adaptivePlayer.getVideoFormat() == null) {
                        torrentLoading.setVisibility(View.GONE);
                    }
                }
                CinemaLinkServer.playbackChanged();
                int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING
                        : (adaptivePlayer.getPlaybackState() == androidx.media3.common.Player.STATE_ENDED
                                ? PlaybackStateCompat.STATE_STOPPED
                                : (adaptivePlayer.getPlaybackState() == androidx.media3.common.Player.STATE_IDLE
                                        ? PlaybackStateCompat.STATE_NONE : PlaybackStateCompat.STATE_PAUSED));
                updateMediaSessionState(state);
                if (isPlaying) {
                    applyInitialResumeIfNeeded();
                    progressHandler.removeCallbacks(periodicProgressSave);
                    progressHandler.postDelayed(periodicProgressSave, PERIODIC_SAVE_INTERVAL_MS);
                    if (externalSubtitlesEnabled || secondarySubtitlesEnabled) {
                        progressHandler.removeCallbacks(subtitleTicker);
                        progressHandler.post(subtitleTicker);
                    }
                } else {
                    progressHandler.removeCallbacks(periodicProgressSave);
                    saveStreamProgressAsync(adaptivePlayer.getPlaybackState() == androidx.media3.common.Player.STATE_ENDED);
                }
            }

            @Override public void onPlaybackStateChanged(int playbackState) {
                CinemaLinkServer.playbackChanged();
                if (liveChannelStatus != null && playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                    liveChannelStatus.setVisibility(View.VISIBLE);
                    liveChannelStatus.setText(addonFrameRendered ? R.string.addon_live_reconnecting : R.string.addon_live_connecting);
                    progressHandler.removeCallbacks(channelWatchdog);
                    progressHandler.postDelayed(channelWatchdog, 18_000L);
                }
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    updateLiveControlsVisibility();
                    if (liveChannelStatus != null && addonFrameRendered) {
                        progressHandler.removeCallbacks(channelWatchdog);
                        liveChannelStatus.setVisibility(View.GONE);
                    }
                    if (liveChannelStatus == null || addonFrameRendered) adaptiveRetryCount = 0;
                    if (pendingRetryRunnable != null) {
                        progressHandler.removeCallbacks(pendingRetryRunnable);
                        pendingRetryRunnable = null;
                    }
                    if (!initialLiveSeekApplied && (isLiveChannelPlayback() || adaptivePlayer.isCurrentMediaItemLive())) {
                        initialLiveSeekApplied = true;
                        if (adaptivePlayer.isCurrentMediaItemLive()) {
                            adaptivePlayer.seekToDefaultPosition();
                        }
                    } else {
                        applyInitialResumeIfNeeded();
                    }
                } else if (playbackState == androidx.media3.common.Player.STATE_ENDED
                        || playbackState == androidx.media3.common.Player.STATE_IDLE) {
                    keepScreenOn(false);
                    updateMediaSessionState(playbackState == androidx.media3.common.Player.STATE_IDLE
                            ? PlaybackStateCompat.STATE_NONE : PlaybackStateCompat.STATE_STOPPED);
                    if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                        saveStreamProgressAsync(true);
                    }
                }
            }

            @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                addonPlaybackFailed = true;
                if (localTorrent != null) {
                    Toast.makeText(CinemaHlsActivity.this, CinemaTorrentStream.errorMessage(error), Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                handleAdaptiveError(error);
            }
        });
        adaptivePlayer.prepare();
        if (currentSpeedPercent != 100) {
            adaptivePlayer.setPlaybackSpeed(currentSpeedPercent / 100f);
        }
        adaptivePlayer.play();
    }

    private static boolean isBehindLiveWindow(androidx.media3.common.PlaybackException error) {
        if (error == null) return false;
        if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return true;
        Throwable cause = error.getCause();
        while (cause != null) {
            if (cause instanceof androidx.media3.exoplayer.source.BehindLiveWindowException) return true;
            cause = cause.getCause();
        }
        return false;
    }

    private void handleAdaptiveError(androidx.media3.common.PlaybackException error) {
        if (adaptivePlayer == null || isDestroyed() || isFinishing()) return;
        if (liveChannelStatus != null) {
            progressHandler.removeCallbacks(channelWatchdog);
            liveChannelStatus.setText(R.string.addon_live_reconnecting);
            liveChannelStatus.setVisibility(View.VISIBLE);
        }
        String errName = error != null ? error.getErrorCodeName() : "UNKNOWN";
        if (isBehindLiveWindow(error)) {
            android.util.Log.w("CinemaWebPlayer", "Behind live window, recovering to live edge");
            adaptiveRetryCount = 0;
            adaptivePlayer.seekToDefaultPosition();
            adaptivePlayer.prepare();
            adaptivePlayer.play();
            return;
        }
        if (!addonFrameRendered && localTorrent == null && isFormatMismatchError(error)) {
            if (isCatalogChannel() && !liveCandidates.isEmpty() && liveStreamMode == LIVE_MODE_AUTO) {
                int fallbackMode = currentLiveCandidateLooksHls() ? LIVE_MODE_PROGRESSIVE : LIVE_MODE_HLS;
                android.util.Log.w("CinemaWebPlayer", "Live format mismatch (" + errName
                        + "), switching immediately to mode=" + fallbackMode);
                switchLiveCandidate(activeLiveCandidateIndex, fallbackMode);
                return;
            }
            if (!isCatalogChannel() && liveStreamMode == LIVE_MODE_AUTO && getIntent() != null && getIntent().getData() != null) {
                String dataStr = getIntent().getData().toString();
                boolean looksHls = "hls".equals(currentFormat) || dataStr.toLowerCase(java.util.Locale.ROOT).contains(".m3u8");
                liveStreamMode = looksHls ? LIVE_MODE_PROGRESSIVE : LIVE_MODE_HLS;
                var vodCandidate = new LiveCandidate(dataStr, getIntent().getStringExtra("title"),
                        currentFormat == null ? "addon" : currentFormat, getIntent().getBundleExtra(CHANNEL_HEADERS));
                android.util.Log.w("CinemaWebPlayer", "VOD HTTP format mismatch (" + errName
                        + "), retrying with mode=" + liveStreamMode);
                adaptivePlayer.stop();
                adaptivePlayer.setMediaSource(buildLiveMediaSource(vodCandidate, liveStreamMode));
                adaptivePlayer.prepare();
                adaptivePlayer.play();
                return;
            }
        }
        int maxRetries = isCatalogChannel() ? (liveCandidates.size() > 1 ? 1 : 2) : MAX_ADAPTIVE_RETRIES;
        if (adaptiveRetryCount < maxRetries) {
            long delayMs = Math.min(8000L, 1000L * (1L << adaptiveRetryCount));
            adaptiveRetryCount++;
            android.util.Log.w("CinemaWebPlayer", "Playback error " + errName
                    + ", retry " + adaptiveRetryCount + "/" + maxRetries + " in " + delayMs + "ms");
            if (adaptiveRetryCount == 1 && !isCatalogChannel()) {
                Toast.makeText(this, "جاري استعادة البث...", Toast.LENGTH_SHORT).show();
            }
            if (pendingRetryRunnable != null) progressHandler.removeCallbacks(pendingRetryRunnable);
            pendingRetryRunnable = () -> {
                pendingRetryRunnable = null;
                if (adaptivePlayer == null || isDestroyed() || isFinishing()) return;
                if (adaptivePlayer.isCurrentMediaItemLive() || isLiveChannelPlayback()) adaptivePlayer.seekToDefaultPosition();
                if (liveChannelStatus != null) {
                    adaptivePlayer.stop();
                    addonFrameRendered = false;
                }
                adaptivePlayer.prepare();
                adaptivePlayer.play();
            };
            progressHandler.postDelayed(pendingRetryRunnable, delayMs);
            return;
        }
        if (isCatalogChannel() && activeLiveCandidateIndex + 1 < liveCandidates.size()) {
            int nextIndex = activeLiveCandidateIndex + 1;
            android.util.Log.w("CinemaWebPlayer", "Switching to alternate live stream "
                    + (nextIndex + 1) + "/" + liveCandidates.size());
            switchLiveCandidate(nextIndex, LIVE_MODE_AUTO);
            return;
        }
        android.util.Log.w("CinemaWebPlayer", "Playback failed after retries: " + errName);
        keepScreenOn(false);
        updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED);
        if (isCatalogChannel()) Toast.makeText(this, R.string.addon_live_source_failed, Toast.LENGTH_LONG).show();
        else Toast.makeText(this, "تعذّر تشغيل البث حالياً", Toast.LENGTH_SHORT).show();
        finish();
    }

    boolean live() { return adaptivePlayer != null && adaptivePlayer.isCurrentMediaItemLive(); }

    void goLive() {
        if (live()) adaptivePlayer.seekToDefaultPosition();
    }

    boolean playing() {
        if (adaptivePlayer != null) return adaptivePlayer.isPlaying();
        return video != null && video.isPlaying();
    }

    boolean hasPlaybackSession() {
        if (isFinishing()) return false;
        if (adaptivePlayer != null) {
            int state = adaptivePlayer.getPlaybackState();
            return state == androidx.media3.common.Player.STATE_BUFFERING
                    || state == androidx.media3.common.Player.STATE_READY;
        }
        return video != null && (playing() || duration() > 0 && position() < duration());
    }

    int position() {
        if (adaptivePlayer != null) return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, adaptivePlayer.getCurrentPosition()));
        if (video != null) return Math.max(0, video.getCurrentPosition());
        return 0;
    }

    int duration() {
        if (adaptivePlayer != null) return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, adaptivePlayer.getDuration()));
        if (video != null) return Math.max(0, video.getDuration());
        return 0;
    }

    void seek(int position) {
        seekInternal(position, true);
    }

    private void seekInternal(int position, boolean userInitiated) {
        int clamped = Math.max(0, position);
        long dur = duration();
        if (userInitiated && dur > 0 && (long) clamped * 100L < dur * 85L) {
            userSoughtBelowThreshold = true;
        }
        if (adaptivePlayer != null) adaptivePlayer.seekTo(clamped);
        else if (video != null) video.seekTo(clamped);
        CinemaLinkServer.playbackChanged();
        if (userInitiated && dur > 0) {
            saveStreamProgressAtAsync((long) clamped, false);
        }
    }

    void toggle() {
        if (adaptivePlayer != null) {
            adaptivePlayer.setPlayWhenReady(!adaptivePlayer.getPlayWhenReady());
            refreshOsdLabels();
            return;
        }
        if (video == null) return;
        CinemaLinkServer.playbackChanged();
        if (playing()) {
            video.pause();
            keepScreenOn(false);
            updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED);
            abandonVideoAudioFocus();
            saveStreamProgressAsync(false);
        } else {
            requestVideoAudioFocus();
            video.start();
            keepScreenOn(true);
            updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING);
        }
        refreshOsdLabels();
    }

    void speed(float speed) {
        currentSpeedPercent = Math.max(50, Math.min(200, Math.round(speed * 100f)));
        refreshOsdLabels();
        if (adaptivePlayer != null) {
            adaptivePlayer.setPlaybackSpeed(speed);
            return;
        }
        if (player == null || video == null) return;
        boolean wasPlaying = playing();
        try {
            player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed));
            if (!wasPlaying) video.pause();
        } catch (IllegalArgumentException | IllegalStateException error) {
            android.util.Log.w("CinemaHls", "Could not change playback speed", error);
        }
    }

    private void clearSubtitles() {
        subtitleRevision++;
        lastSubtitleBytes = null;
        lastSubtitleExt = null;
        subtitleDelayMs = 0L;
        externalSubtitlesEnabled = false;
        secondarySubtitlesEnabled = false;
        externalCues = java.util.Collections.emptyList();
        secondaryCues = java.util.Collections.emptyList();
        progressHandler.removeCallbacks(subtitleTicker);
        if (externalSubtitleView != null) {
            externalSubtitleView.setText(null);
            externalSubtitleView.setVisibility(View.GONE);
        }
        if (secondarySubtitleView != null) {
            secondarySubtitleView.setText(null);
            secondarySubtitleView.setVisibility(View.GONE);
        }
        if (exoSubtitles != null) {
            exoSubtitles.setText(null);
            exoSubtitles.setVisibility(subtitleVisible ? View.VISIBLE : View.GONE);
        }
        if (videoViewSubtitles != null) {
            videoViewSubtitles.setText(null);
            videoViewSubtitles.setVisibility(subtitleVisible ? View.VISIBLE : View.GONE);
        }
        if (adaptivePlayer != null) {
            // Disable embedded/intent text track without calling setMediaItem/prepare
            adaptivePlayer.setTrackSelectionParameters(adaptivePlayer.getTrackSelectionParameters()
                    .buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                    .build());
        } else if (player != null && subtitleTrack >= 0) {
            try { player.deselectTrack(subtitleTrack); } catch (Exception ignored) {}
            subtitleTrack = -1;
        }
    }

    private void applyAllSubtitleStyles() {
        if (bottomSubtitleStack != null && bottomSubtitleStack.getLayoutParams() instanceof FrameLayout.LayoutParams botLp) {
            botLp.bottomMargin = Math.round(subtitleBottomMarginDp * getResources().getDisplayMetrics().density);
            bottomSubtitleStack.setLayoutParams(botLp);
        }
        applySubtitleViewStyle(exoSubtitles);
        applySubtitleViewStyle(videoViewSubtitles);
        applySubtitleViewStyle(externalSubtitleView);
        applySecondarySubtitleViewStyle();
    }

    void updateCastTitle(String newTitle) {
        if (newTitle == null || newTitle.isBlank()) return;
        if (getIntent() != null) getIntent().putExtra("title", newTitle);
        if (mediaSession != null) {
            mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, newTitle)
                    .build());
        }
    }

    org.json.JSONArray getSubtitleTracksJson() {
        var arr = new org.json.JSONArray();
        if (adaptivePlayer == null) return arr;
        try {
            var groups = adaptivePlayer.getCurrentTracks().getGroups();
            int idx = 0;
            for (var group : groups) {
                if (group.getType() != androidx.media3.common.C.TRACK_TYPE_TEXT) continue;
                for (int i = 0; i < group.length; i++) {
                    var fmt = group.getTrackFormat(i);
                    String label = fmt.label != null ? fmt.label : (fmt.language != null ? fmt.language : "Track " + (idx + 1));
                    if (com.archos.mediacenter.video.player.CinemaSubtitleText.isStoryboardTrackHint(
                            label + " " + (fmt.id != null ? fmt.id : "") + " " + (fmt.sampleMimeType != null ? fmt.sampleMimeType : ""))) {
                        idx++;
                        continue;
                    }
                    arr.put(new org.json.JSONObject()
                            .put("index", idx)
                            .put("label", label)
                            .put("language", fmt.language != null ? fmt.language : "")
                            .put("selected", group.isTrackSelected(i)));
                    idx++;
                }
            }
        } catch (Exception ignored) {}
        return arr;
    }

    void selectEmbeddedSubtitleTrack(int targetIndex) {
        subtitleRevision++;
        if (adaptivePlayer == null) return;
        try {
            if (targetIndex < 0) {
                adaptivePlayer.setTrackSelectionParameters(adaptivePlayer.getTrackSelectionParameters()
                        .buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build());
                if (osdBar != null && osdBar.getVisibility() == View.VISIBLE) scheduleHideOsd();
                return;
            }
            var groups = adaptivePlayer.getCurrentTracks().getGroups();
            int idx = 0;
            for (var group : groups) {
                if (group.getType() != androidx.media3.common.C.TRACK_TYPE_TEXT) continue;
                for (int i = 0; i < group.length; i++) {
                    if (idx == targetIndex) {
                        externalSubtitlesEnabled = false;
                        if (externalSubtitleView != null) externalSubtitleView.setVisibility(View.GONE);
                        subtitleVisible = true;
                        adaptivePlayer.setTrackSelectionParameters(adaptivePlayer.getTrackSelectionParameters()
                                .buildUpon()
                                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(new androidx.media3.common.TrackSelectionOverride(
                                        group.getMediaTrackGroup(), java.util.List.of(i)))
                                .build());
                        applyAllSubtitleStyles();
                        if (osdBar != null && osdBar.getVisibility() == View.VISIBLE) scheduleHideOsd();
                        return;
                    }
                    idx++;
                }
            }
        } catch (Exception ignored) {}
    }

    void selectSecondaryEmbeddedSubtitleTrack(int targetIndex) {
        if (targetIndex < 0) {
            secondarySubtitlesEnabled = false;
            secondaryCues = java.util.Collections.emptyList();
            if (secondarySubtitleView != null) {
                secondarySubtitleView.setText(null);
                secondarySubtitleView.setVisibility(View.GONE);
            }
            if (osdBar != null && osdBar.getVisibility() == View.VISIBLE) scheduleHideOsd();
            return;
        }
        if (externalSubtitlesEnabled && !externalCues.isEmpty()) {
            secondaryCues = new java.util.ArrayList<>(externalCues);
            secondarySubtitlesEnabled = dualSubtitlesAllowed();
            subDisplayMode = secondarySubtitlesEnabled ? SUB_MODE_DUAL : SUB_MODE_PRIMARY;
            selectEmbeddedSubtitleTrack(targetIndex);
            applyAllSubtitleStyles();
            updateExternalSubtitleNow();
            return;
        }
        Toast.makeText(this,
                CinemaUi.label(this,
                        "اختر ترجمة خارجية كأساسية لدمج المدمجة كترجمة مزدوجة",
                        "Select an external/stream subtitle as Primary to pair an embedded Dual track"),
                Toast.LENGTH_LONG).show();
    }

    void applyPhoneSubtitleStyle(String action, int value) {
        if (action == null) return;
        switch (action) {
            case "color" -> subtitleColor = value;
            case "size" -> subtitleSizeSp = Math.max(12f, Math.min(48f, subtitleSizeSp + (value * 1.5f)));
            case "position" -> subtitleBottomMarginDp = Math.max(8, Math.min(180, subtitleBottomMarginDp + (value * 2)));
            case "background" -> {
                subtitleOpaqueBg = !subtitleOpaqueBg;
                if (subtitleOpaqueBg && subtitleBgOpacity == 0) subtitleBgOpacity = 70;
            }
            case "bg_opacity" -> {
                subtitleBgOpacity = value >= 0
                        ? com.meedx.providers.UnifiedSubtitleConfig.normalizeBgOpacity(value)
                        : com.meedx.providers.UnifiedSubtitleConfig.nextBgOpacity(subtitleBgOpacity);
                subtitleOpaqueBg = subtitleBgOpacity > 0;
            }
            case "outline" -> subtitleOutlineLevel = value >= 0
                    ? Math.max(0, Math.min(3, value))
                    : com.meedx.providers.UnifiedSubtitleConfig.nextOutlineLevel(subtitleOutlineLevel);
            case "outline_color" -> {
                subtitleOutlineColor = com.meedx.providers.UnifiedSubtitleConfig.normalizeOutlineColor(value);
                if (subtitleOutlineLevel == 0) subtitleOutlineLevel = 2;
            }
            case "visible" -> subtitleVisible = !subtitleVisible;
            case "dual_position" -> {
                dualSubtitlePosition = Math.max(0, Math.min(2, value));
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                        .edit().putInt("pref_dual_sub_position", dualSubtitlePosition).apply();
                applyDualSubtitleLayout();
            }
            case "sub_mode" -> {
                if (value == 3) {
                    dualSubtitlePosition = (dualSubtitlePosition + 1) % 3;
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                            .edit().putInt("pref_dual_sub_position", dualSubtitlePosition).apply();
                    subtitleVisible = true;
                    applyDualSubtitleLayout();
                } else {
                    setDualSubtitleMode(Math.max(0, Math.min(2, value)));
                    return;
                }
            }
            case "sub_clear_secondary" -> {
                secondaryCues = java.util.Collections.emptyList();
                secondarySubtitlesEnabled = false;
                if (secondarySubtitleView != null) {
                    secondarySubtitleView.setText(null);
                    secondarySubtitleView.setVisibility(View.GONE);
                }
                if (externalSubtitlesEnabled) subDisplayMode = SUB_MODE_PRIMARY;
            }
            case "delay" -> {
                subtitleDelayMs += value;
                if (externalSubtitlesEnabled || secondarySubtitlesEnabled) {
                    updateExternalSubtitleNow();
                } else {
                    String intentSub = getIntent() != null ? getIntent().getStringExtra("subtitle_url") : null;
                    if (intentSub != null && (intentSub.startsWith("http://") || intentSub.startsWith("https://"))) {
                        loadRemoteSubtitleAsync(intentSub, false);
                    }
                }
            }
            case "delay_reset" -> {
                subtitleDelayMs = 0L;
                if (externalSubtitlesEnabled || secondarySubtitlesEnabled) {
                    updateExternalSubtitleNow();
                }
            }
            case "reset" -> {
                subtitleDelayMs = 0L;
                subtitleSizeSp = 20f;
                subtitleColor = android.graphics.Color.WHITE;
                subtitleBottomMarginDp = 32;
                subtitleOpaqueBg = false;
                subtitleBgOpacity = 70;
                subtitleOutlineLevel = 0;
                subtitleOutlineColor = android.graphics.Color.BLACK;
                subtitleVisible = true;
                if (externalSubtitlesEnabled || secondarySubtitlesEnabled) {
                    updateExternalSubtitleNow();
                }
            }
        }
        applyAllSubtitleStyles();
        if (externalSubtitlesEnabled && externalSubtitleView != null) {
            if (exoSubtitles != null) exoSubtitles.setVisibility(View.GONE);
            if (videoViewSubtitles != null) videoViewSubtitles.setVisibility(View.GONE);
        }
    }

    /** Reloads subtitle appearance from UnifiedSubtitleConfig when synced live from Phone. */
    void reloadUnifiedSubtitleAppearance() {
        subtitleSizeSp = Math.max(14f, Math.min(38f, 14f + (com.meedx.providers.UnifiedSubtitleConfig.textSize(this) * 0.17f)));
        subtitleColor = com.meedx.providers.UnifiedSubtitleConfig.textColor(this);
        subtitleBottomMarginDp = Math.max(16, Math.min(180, com.meedx.providers.UnifiedSubtitleConfig.vpos(this) / 2));
        subtitleOpaqueBg = com.meedx.providers.UnifiedSubtitleConfig.opaqueBg(this);
        subtitleBgOpacity = com.meedx.providers.UnifiedSubtitleConfig.bgOpacity(this);
        subtitleOutlineLevel = com.meedx.providers.UnifiedSubtitleConfig.outlineLevel(this);
        subtitleOutlineColor = com.meedx.providers.UnifiedSubtitleConfig.outlineColor(this);
        dualSubtitlePosition = com.meedx.providers.UnifiedSubtitleConfig.dualSubPosition(this);
        applyDualSubtitleLayout();
        applyAllSubtitleStyles();
        updateExternalSubtitleNow();
    }

    private void applySubtitleViewStyle(com.archos.mediacenter.video.player.SubtitleTextView view) {
        if (view == null) return;
        boolean hasText = view.getText() != null && view.getText().toString().trim().length() > 0;
        if (view == externalSubtitleView) {
            boolean showPrimary = externalSubtitlesEnabled && !externalCues.isEmpty() && subDisplayMode != SUB_MODE_SECONDARY;
            view.setVisibility(showPrimary && subtitleVisible && hasText ? View.VISIBLE : View.GONE);
        } else if (externalSubtitlesEnabled) {
            view.setVisibility(View.GONE);
        } else {
            view.setVisibility(subtitleVisible && hasText ? View.VISIBLE : View.GONE);
        }
        view.setTextColor(subtitleColor);
        view.setTextSize(subtitleSizeSp);
        view.setSubtitleBackground(subtitleOpaqueBg, subtitleBgOpacity);
        view.setOutline(subtitleOutlineLevel, subtitleOutlineColor);
        if (view.getLayoutParams() instanceof FrameLayout.LayoutParams lp) {
            lp.bottomMargin = Math.round(subtitleBottomMarginDp * getResources().getDisplayMetrics().density);
            view.setLayoutParams(lp);
        }
    }

    private void applySecondarySubtitleViewStyle() {
        if (secondarySubtitleView == null) return;
        boolean showSec = secondarySubtitlesEnabled && !secondaryCues.isEmpty()
                && subDisplayMode != SUB_MODE_PRIMARY;
        boolean hasText = secondarySubtitleView.getText() != null && secondarySubtitleView.getText().toString().trim().length() > 0;
        secondarySubtitleView.setVisibility(showSec && subtitleVisible && hasText ? View.VISIBLE : View.GONE);
        secondarySubtitleView.setTextSize(Math.max(14f, subtitleSizeSp - 2f));
        secondarySubtitleView.setSubtitleBackground(subtitleOpaqueBg, subtitleBgOpacity);
        secondarySubtitleView.setOutline(subtitleOutlineLevel, subtitleOutlineColor);
    }

    void setSubtitle(String extension, byte[] data) {
        setSubtitle(extension, data, false);
    }

    void setSubtitle(String extension, byte[] data, boolean isSecondary) {
        if (data == null || data.length == 0) return;
        String resolvedExt = CinemaSubtitleCodec.sniffExtension(data, "." + (extension == null ? "" : extension));
        var parsed = parseExternalCues(resolvedExt, data);
        if (parsed.isEmpty()) return;
        subtitleVisible = true;
        if (isSecondary) {
            secondaryCues = parsed;
            secondarySubtitlesEnabled = dualSubtitlesAllowed();
            subDisplayMode = !secondarySubtitlesEnabled ? SUB_MODE_PRIMARY
                    : externalSubtitlesEnabled ? SUB_MODE_DUAL : SUB_MODE_SECONDARY;
            applyAllSubtitleStyles();
            updateExternalSubtitleNow();
            progressHandler.removeCallbacks(subtitleTicker);
            progressHandler.post(subtitleTicker);
            if (osdBar != null && osdBar.getVisibility() == View.VISIBLE) scheduleHideOsd();
            return;
        }
        subtitleRevision++;
        lastSubtitleExt = resolvedExt;
        lastSubtitleBytes = data;
        if (playbackFilters != null) playbackFilters.setLoadedSubtitleBytes(data);
        externalCues = parsed;
        externalSubtitlesEnabled = true;
        subDisplayMode = secondarySubtitlesEnabled ? SUB_MODE_DUAL : SUB_MODE_PRIMARY;
        if (exoSubtitles != null) {
            exoSubtitles.setText(null);
            exoSubtitles.setVisibility(View.GONE);
        }
        if (videoViewSubtitles != null) {
            videoViewSubtitles.setText(null);
            videoViewSubtitles.setVisibility(View.GONE);
        }
        if (adaptivePlayer != null) {
            adaptivePlayer.setTrackSelectionParameters(adaptivePlayer.getTrackSelectionParameters()
                    .buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                    .build());
        }
        applyAllSubtitleStyles();
        updateExternalSubtitleNow();
        progressHandler.removeCallbacks(subtitleTicker);
        progressHandler.post(subtitleTicker);
        if (osdBar != null && osdBar.getVisibility() == View.VISIBLE) scheduleHideOsd();
    }

    private void loadRemoteSecondarySubtitleAsync(String subUrl) {
        loadRemoteSubtitleAsync(subUrl, true);
    }

    private void applySubtitleOverlayWithoutRestart(String extension, byte[] rawData) {
        setSubtitle(extension, rawData, false);
    }

    private void updateExternalSubtitleNow() {
        long posMs = position();
        long lookupMs = posMs - subtitleDelayMs;
        if (posMs < 0 || lookupMs < 0) {
            if (externalSubtitleView != null) {
                externalSubtitleView.setText(null);
                externalSubtitleView.setVisibility(View.GONE);
            }
            if (secondarySubtitleView != null) {
                secondarySubtitleView.setText(null);
                secondarySubtitleView.setVisibility(View.GONE);
            }
            return;
        }
        String primaryTxt = (externalSubtitlesEnabled && !externalCues.isEmpty() && subDisplayMode != SUB_MODE_SECONDARY)
                ? findActiveCueText(externalCues, lookupMs) : null;
        String secondaryTxt = (secondarySubtitlesEnabled && !secondaryCues.isEmpty() && subDisplayMode != SUB_MODE_PRIMARY)
                ? findActiveCueText(secondaryCues, lookupMs) : null;

        if (externalSubtitleView != null) {
            externalSubtitleView.setText(primaryTxt);
            boolean showP = subtitleVisible && primaryTxt != null && !primaryTxt.isEmpty();
            externalSubtitleView.setVisibility(showP ? View.VISIBLE : View.GONE);
        }
        if (secondarySubtitleView != null) {
            secondarySubtitleView.setText(secondaryTxt);
            boolean showS = subtitleVisible && secondaryTxt != null && !secondaryTxt.isEmpty();
            secondarySubtitleView.setVisibility(showS ? View.VISIBLE : View.GONE);
        }
    }

    private String findActiveCueText(java.util.List<ExternalCue> cues, long lookupMs) {
        StringBuilder sb = null;
        for (int i = 0; i < cues.size(); i++) {
            ExternalCue c = cues.get(i);
            if (c.startMs() > lookupMs) break;
            if (lookupMs <= c.endMs() && !c.text().isEmpty()) {
                if (playbackFilters != null) playbackFilters.observeSubtitle(c.text(),
                        c.startMs() + subtitleDelayMs, c.endMs() + subtitleDelayMs);
                if (sb == null) sb = new StringBuilder(c.text());
                else sb.append('\n').append(c.text());
            }
        }
        return sb != null ? sb.toString() : null;
    }

    private static java.util.List<ExternalCue> parseExternalCues(String extension, byte[] rawData) {
        if (rawData == null || rawData.length == 0) return java.util.Collections.emptyList();
        String text = new String(rawData, java.nio.charset.StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        if (com.archos.mediacenter.video.player.CinemaSubtitleText.isEntirelyStoryboardVtt(text)) {
            return java.util.Collections.emptyList();
        }
        var cues = new java.util.ArrayList<ExternalCue>();
        if ("ass".equals(extension) || "ssa".equals(extension)) {
            for (String line : text.split("\\r?\\n")) {
                if (!line.startsWith("Dialogue:")) continue;
                String[] parts = line.substring(9).split(",", 10);
                if (parts.length < 10) continue;
                long start = parseAssTimeMs(parts[1].trim());
                long end = parseAssTimeMs(parts[2].trim());
                String body = CinemaSubtitleText.displayText(parts[9]).trim();
                if (start >= 0 && end >= start && !body.isEmpty()) {
                    cues.add(new ExternalCue(start, end, body));
                }
            }
            cues.sort(java.util.Comparator.comparingLong(ExternalCue::startMs));
            return cues;
        }
        var timeLinePattern = java.util.regex.Pattern.compile(
                "(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[.,](\\d{3})");
        String[] lines = text.split("\\r?\\n");
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            var m = timeLinePattern.matcher(line);
            if (!m.find()) {
                i++;
                continue;
            }
            long start = parseSrtVttGroupMs(m.group(1), m.group(2), m.group(3), m.group(4));
            long end = parseSrtVttGroupMs(m.group(5), m.group(6), m.group(7), m.group(8));
            i++;
            StringBuilder body = new StringBuilder();
            while (i < lines.length) {
                String content = lines[i].trim();
                if (content.isEmpty()) break;
                if (timeLinePattern.matcher(content).find()) break;
                String cleaned = CinemaSubtitleText.displayText(content.replaceAll("<[^>]+>", ""))
                        .replace("&amp;", "&").replace("&lt;", "<")
                        .replace("&gt;", ">").replace("&nbsp;", " ")
                        .trim();
                if (!cleaned.isEmpty()) {
                    if (body.length() > 0) body.append('\n');
                    body.append(cleaned);
                }
                i++;
            }
            if (start >= 0 && end >= start && body.length() > 0) {
                cues.add(new ExternalCue(start, end, body.toString()));
            }
        }
        cues.sort(java.util.Comparator.comparingLong(ExternalCue::startMs));
        return cues;
    }

    private static long parseSrtVttGroupMs(String hrsStr, String minsStr, String secsStr, String msStr) {
        long h = hrsStr != null ? Long.parseLong(hrsStr) : 0L;
        long m = Long.parseLong(minsStr);
        long s = Long.parseLong(secsStr);
        long ms = Long.parseLong(msStr);
        return ((h * 3600L + m * 60L + s) * 1000L) + ms;
    }

    private static long parseAssTimeMs(String raw) {
        var m = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{2,3})").matcher(raw);
        if (!m.find()) return -1L;
        long h = Long.parseLong(m.group(1));
        long mins = Long.parseLong(m.group(2));
        long s = Long.parseLong(m.group(3));
        String frac = m.group(4);
        long ms = frac.length() == 2 ? Long.parseLong(frac) * 10L : Long.parseLong(frac);
        return ((h * 3600L + mins * 60L + s) * 1000L) + ms;
    }

    private void loadChapterMetadata(androidx.media3.common.Tracks tracks) {
        if (playbackFilters == null) return;
        for (var group : tracks.getGroups()) {
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i)) playbackFilters.addChapterMetadata(group.getTrackFormat(i).metadata);
            }
        }
    }

    @Override public void finish() {
        if (!isFinishing()) {
            final String resultKey = com.archos.mediacenter.video.utils.PlayUtils.EXTRA_EPISODE_RESULT;
            final String indexKey = com.archos.mediacenter.video.utils.PlayUtils.EXTRA_EPISODE_INDEX;
            if (getIntent().getBooleanExtra(resultKey, false)) {
                int index = getIntent().getIntExtra(indexKey, -1);
                long[] ids = getIntent().getLongArrayExtra(com.archos.mediacenter.video.utils.PlayUtils.EXTRA_EPISODE_IDS);
                if (ids != null && index >= 0 && index < ids.length) {
                    int total = duration();
                    int elapsed = position();
                    if (total > 0 && elapsed > 0) {
                        long bookmark = elapsed * 100L >= total * 85L
                                ? com.archos.mediacenter.video.player.PlayerActivity.LAST_POSITION_END : elapsed;
                        CinemaMediaTime.rememberState(ids[index], getIntent().getData(), total, bookmark);
                    }
                    setResult(RESULT_OK, new Intent().putExtra("current_episode_id", ids[index]).putExtra(indexKey, index));
                }
            }
            View content = findViewById(android.R.id.content);
            if (content != null) content.setVisibility(View.INVISIBLE);
            getWindow().setWindowAnimations(0);
        }
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onResume() {
        super.onResume();
        if (isFinishing() || isLiveChannelPlayback()) return;
        if (playbackFilters == null) {
            playbackFilters = new CinemaPlaybackFilters(this, this::position, this::duration,
                    () -> playing() && !live(),
                    target -> seekInternal(target, false),
                    muted -> {
                        float vol = muted ? 0f : 1f;
                        if (adaptivePlayer != null) adaptivePlayer.setVolume(vol);
                        else if (player != null) {
                            try { player.setVolume(vol, vol); } catch (Exception ignored) {}
                        }
                    });
        }
        playbackFilters.setMedia(getIntent().getData(), getIntent().getLongExtra("cinema_filter_video_id", -1), getIntent());
        if (lastSubtitleBytes != null && lastSubtitleBytes.length > 0) {
            playbackFilters.setLoadedSubtitleBytes(lastSubtitleBytes);
        }
        playbackFilters.resume();
        if (adaptivePlayer != null) loadChapterMetadata(adaptivePlayer.getCurrentTracks());
    }

    @Override protected void onPause() {
        if (playbackFilters != null) playbackFilters.pause();
        saveStreamProgressAsync(false);
        super.onPause();
    }

    @Override protected void onStop() {
        progressHandler.removeCallbacks(periodicProgressSave);
        progressHandler.removeCallbacks(subtitleTicker);
        progressHandler.removeCallbacks(hideOsd);
        progressHandler.removeCallbacks(updateSeekHud);
        if (seekHud != null) seekHud.setVisibility(View.GONE);
        if (pendingRetryRunnable != null) {
            progressHandler.removeCallbacks(pendingRetryRunnable);
            pendingRetryRunnable = null;
        }
        saveStreamProgressAsync(false);
        keepScreenOn(false);
        updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED);
        abandonVideoAudioFocus();
        if (adaptivePlayer != null) adaptivePlayer.pause();
        else if (video != null) video.pause();
        player = null;
        super.onStop();
    }

    @Override protected void onDestroy() {
        progressHandler.removeCallbacksAndMessages(null);
        saveStreamProgressAsync(false);
        progressIo.shutdown();
        keepScreenOn(false);
        abandonVideoAudioFocus();
        updateMediaSessionState(PlaybackStateCompat.STATE_NONE);
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        if (torrentPreparation != null) torrentPreparation.dismiss();
        if (adaptivePlayer != null) adaptivePlayer.release();
        else if (video != null) video.stopPlayback();
        if (localTorrent != null) localTorrent.release();
        super.onDestroy();
    }
}

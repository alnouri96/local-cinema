package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.proninyaroslav.libretorrent.R;

final class TvHubMusicController {
    final TvHubActivity host;

    LinearLayout musicPlaybackScreen;
    boolean musicPlaybackOpened;
    LinearLayout musicShortcut;
    android.widget.ImageView musicMiniArtImage;
    TextView musicMiniArtFallback;
    TextView musicMiniTitle;
    TextView musicMiniSub;
    MaterialButton musicMiniPlayPause;
    MaterialButton musicMiniPrev;
    MaterialButton musicMiniNext;

    private MaterialButton favoriteTrack;
    private String countedTrackPath;
    java.util.List<LocalMediaPanel.Item> audioQueue = java.util.List.of();
    int audioIndex;
    int repeat;
    boolean shuffle;
    volatile String audioPath;
    int sourceAudioDuration;
    String audioArtistName = "";
    boolean audioQueueEnded;
    String endedAudioPath;
    final java.util.ArrayDeque<Integer> audioHistory = new java.util.ArrayDeque<>();
    int audioSpeedPercent = 100;
    boolean audioMode;
    android.net.Uri activeAudioUri;
    boolean audioPlaying;
    int audioResumePosition;

    LinearLayout musicVolumeCard;
    android.widget.SeekBar musicVolumeSlider;
    MaterialButton musicVolumeBadge;
    MaterialButton musicMuteButton;
    android.widget.ImageView musicArtImage;
    TextView musicArtFallback;
    TextView musicSheetTitle;
    TextView musicSheetArtist;
    TextView musicTime;
    TextView musicDuration;
    android.widget.SeekBar musicTimeline;
    MaterialButton musicPlayPause;
    MaterialButton musicShuffleBtn;
    MaterialButton musicRepeatBtn;
    MaterialButton musicSpeedBtn;
    MaterialButton musicQueueToggleBtn;
    TextView musicQueueCountBadge;
    LinearLayout musicArtworkPane;
    LinearLayout musicQueuePane;
    LinearLayout musicQueueContainer;
    boolean musicQueueMode;
    boolean musicScrubbing;
    int currentMusicPosMs;

    final Runnable musicMiniAutoHideRunnable;

    TvHubMusicController(TvHubActivity host) {
        this.host = host;
        this.musicMiniAutoHideRunnable = () -> {
            if (musicShortcut != null && isAutoHideMiniPlayerEnabled() && audioMode && !musicPlaybackOpened) {
                musicShortcut.animate().alpha(0f).setDuration(220).withEndAction(() -> {
                    if (musicShortcut != null && isAutoHideMiniPlayerEnabled() && audioMode && !musicPlaybackOpened) {
                        musicShortcut.setVisibility(View.GONE);
                        musicShortcut.setAlpha(1f);
                        host.updateTabBottomPadding();
                    }
                }).start();
            }
        };
    }

    LinearLayout createMusicMiniBar() {
        var bar = new LinearLayout(host);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(host.dp(8), host.dp(6), host.dp(8), host.dp(6));
        var dock = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff262016, 0xff17171b, 0xff101014});
        dock.setCornerRadius(host.dp(18));
        dock.setStroke(host.dp(1), 0x80e5b94c);
        bar.setBackground(dock);

        var artBox = new android.widget.FrameLayout(host);
        var artBg = new GradientDrawable();
        artBg.setColor(0xff1a1b22);
        artBg.setCornerRadius(host.dp(10));
        artBg.setStroke(host.dp(1), 0x88e5b94c);
        artBox.setBackground(artBg);
        artBox.setClipToOutline(true);

        musicMiniArtFallback = new TextView(host);
        musicMiniArtFallback.setText("♪");
        musicMiniArtFallback.setTextSize(17);
        musicMiniArtFallback.setTextColor(TvHubActivity.GOLD);
        musicMiniArtFallback.setGravity(Gravity.CENTER);
        artBox.addView(musicMiniArtFallback, new android.widget.FrameLayout.LayoutParams(-1, -1));

        musicMiniArtImage = new android.widget.ImageView(host);
        musicMiniArtImage.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        musicMiniArtImage.setVisibility(View.GONE);
        artBox.addView(musicMiniArtImage, new android.widget.FrameLayout.LayoutParams(-1, -1));

        var artLp = new LinearLayout.LayoutParams(host.dp(40), host.dp(40));
        artLp.setMarginEnd(host.dp(4));
        bar.addView(artBox, artLp);

        var textCol = new LinearLayout(host);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setPadding(host.dp(6), 0, host.dp(6), 0);
        textCol.setClickable(true);
        textCol.setFocusable(true);
        musicMiniTitle = new TextView(host);
        musicMiniTitle.setText(host.l("مشغّل الموسيقى", "Music Player"));
        musicMiniTitle.setTextColor(0xfffafafa);
        musicMiniTitle.setTextSize(13);
        musicMiniTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        musicMiniTitle.setSingleLine(true);
        musicMiniTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(musicMiniTitle);

        musicMiniSub = new TextView(host);
        musicMiniSub.setText("");
        musicMiniSub.setTextColor(0xffd4b55b);
        musicMiniSub.setTextSize(11);
        musicMiniSub.setSingleLine(true);
        musicMiniSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(musicMiniSub);

        textCol.setOnClickListener(v -> showMusicSheet(true));
        bar.setOnClickListener(v -> showMusicSheet(true));
        bar.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        musicMiniPrev = host.playbackIcon("", host.l("السابق", "Previous"),
                org.proninyaroslav.libretorrent.R.drawable.ic_skip_previous_24, this::previousAudio, false);
        musicMiniPrev.setIconSize(host.dp(20));
        bar.addView(musicMiniPrev, new LinearLayout.LayoutParams(host.dp(36), host.dp(38)));

        musicMiniPlayPause = host.playbackIcon("", host.l("تشغيل/إيقاف", "Play/Pause"),
                org.proninyaroslav.libretorrent.R.drawable.cinema_control_play, this::toggleCurrentAudioPlayPause, true);
        musicMiniPlayPause.setIconSize(host.dp(20));
        musicMiniPlayPause.setCornerRadius(host.dp(19));
        var ppLp = new LinearLayout.LayoutParams(host.dp(38), host.dp(38));
        ppLp.setMarginStart(host.dp(2));
        ppLp.setMarginEnd(host.dp(2));
        bar.addView(musicMiniPlayPause, ppLp);

        musicMiniNext = host.playbackIcon("", host.l("التالي", "Next"),
                org.proninyaroslav.libretorrent.R.drawable.ic_skip_next_24, () -> advanceAudio(false), false);
        musicMiniNext.setIconSize(host.dp(20));
        bar.addView(musicMiniNext, new LinearLayout.LayoutParams(host.dp(36), host.dp(38)));

        var stop = host.playbackIcon("", host.l("إيقاف البث", "Stop casting"),
                org.proninyaroslav.libretorrent.R.drawable.cinema_control_stop, this::stopMusicPlayback, false);
        stop.setIconSize(host.dp(18));
        bar.addView(stop, new LinearLayout.LayoutParams(host.dp(36), host.dp(38)));

        return bar;
    }

    boolean isAutoHideMiniPlayerEnabled() {
        return host.getSharedPreferences("cinema_link", Context.MODE_PRIVATE).getBoolean("auto_hide_mini_player", false);
    }

    void scheduleMusicMiniAutoHide() {
        host.main.removeCallbacks(musicMiniAutoHideRunnable);
        if (audioMode && isAutoHideMiniPlayerEnabled() && !musicPlaybackOpened) {
            host.main.postDelayed(musicMiniAutoHideRunnable, 5000L);
        }
    }

    void revealMusicMiniBar() {
        if (!audioMode || musicShortcut == null) return;
        musicShortcut.animate().cancel();
        musicShortcut.setAlpha(1f);
        if (musicShortcut.getVisibility() != View.VISIBLE) {
            musicShortcut.setVisibility(View.VISIBLE);
            host.updateTabBottomPadding();
        }
        scheduleMusicMiniAutoHide();
    }

    void setMusicShortcutVisible(boolean visible) {
        if (musicShortcut == null) return;
        host.main.removeCallbacks(musicMiniAutoHideRunnable);
        musicShortcut.animate().cancel();
        musicShortcut.setAlpha(1f);
        int target = visible ? View.VISIBLE : View.GONE;
        if (visible && host.playbackShortcut != null) host.playbackShortcut.setVisibility(View.GONE);
        if (musicShortcut.getVisibility() != target) {
            musicShortcut.setVisibility(target);
            host.updateTabBottomPadding();
        }
        if (visible) scheduleMusicMiniAutoHide();
    }

    void onAudioSettingsChanged() {
        var settings = host.getSharedPreferences("cinema_link", Context.MODE_PRIVATE);
        if (host.connected && host.socket != null && host.socket.isOpen()) {
            host.command("SET_AUDIO_HUD_TIMEOUT", "timeout_ms", settings.getInt("audio_hud_timeout", 5000));
        }
        if (audioMode) {
            if (isAutoHideMiniPlayerEnabled()) scheduleMusicMiniAutoHide();
            else revealMusicMiniBar();
        }
    }

    void applyAudioSpeed(int pct) {
        if (musicSpeedBtn != null) {
            musicSpeedBtn.setText((pct == 100 ? "1×" : (pct / 100f) + "×"));
        }
        host.command("SPEED", "percent", pct);
    }

    String repeatLabel() {
        return new String[]{host.getString(org.proninyaroslav.libretorrent.R.string.cinema_repeat_off),
                host.l("تكرار الكل", "Repeat All"), host.l("تكرار مقطع", "Repeat One")}[repeat];
    }

    void loadAudioPrefs() {
        var mp = host.getSharedPreferences("cinema_music", Context.MODE_PRIVATE);
        shuffle = mp.getBoolean("pref_audio_shuffle", false);
        repeat = Math.max(0, Math.min(2, mp.getInt("pref_audio_repeat", 0)));
        audioSpeedPercent = mp.getInt("pref_audio_speed", 100);
        host.rememberVideoSpeed = mp.getBoolean("pref_remember_video_speed", false);
        host.videoSpeedPercent = host.rememberVideoSpeed ? mp.getInt("pref_video_speed", 100) : 100;
        int savedVol = mp.getInt("pref_audio_volume", -1);
        if (savedVol >= 0) {
            host.volumePercent = Math.max(0, Math.min(100, savedVol));
            host.lastAudibleVolume = Math.max(1, host.volumePercent);
            host.volumeMuted = (host.volumePercent == 0);
        }
    }

    void saveAudioPrefs() {
        var ed = host.getSharedPreferences("cinema_music", Context.MODE_PRIVATE).edit()
                .putBoolean("pref_audio_shuffle", shuffle)
                .putInt("pref_audio_repeat", repeat)
                .putInt("pref_audio_speed", audioSpeedPercent)
                .putBoolean("pref_remember_video_speed", host.rememberVideoSpeed)
                .putInt("pref_video_speed", host.rememberVideoSpeed ? host.videoSpeedPercent : 100);
        if (audioMode && host.volumeAvailable) {
            ed.putInt("pref_audio_volume", host.volumeMuted ? 0 : host.volumePercent);
        }
        ed.apply();
    }

    void showMusicSheet(boolean open) {
        if (open && musicPlaybackOpened && musicPlaybackScreen != null
                && musicPlaybackScreen.getVisibility() == View.VISIBLE) return;
        musicPlaybackOpened = open;
        if (open) {
            host.playbackOpened = false;
            if (host.playbackScreen != null) host.playbackScreen.setVisibility(View.GONE);
            host.main.removeCallbacks(musicMiniAutoHideRunnable);
            renderMusicQueueList();
            syncMusicVolumeUi();
        } else {
            setMusicQueueMode(false);
        }
        if (!open && host.returnToNavTab != 0) {
            int backTab = host.returnToNavTab;
            host.returnToNavTab = 0;
            if (host.hub != null) host.hub.setVisibility(View.VISIBLE);
            if (musicPlaybackScreen != null) musicPlaybackScreen.setVisibility(View.GONE);
            if (audioMode) setMusicShortcutVisible(true);
            else host.updateTabBottomPadding();
            host.startActivity(new Intent(host, org.proninyaroslav.libretorrent.MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(org.proninyaroslav.libretorrent.MainActivity.EXTRA_SELECT_TAB, backTab));
            host.overridePendingTransition(0, 0);
            return;
        }
        if (host.hub != null) host.hub.setVisibility(open ? View.INVISIBLE : View.VISIBLE);
        if (musicPlaybackScreen != null) {
            musicPlaybackScreen.animate().cancel();
            musicPlaybackScreen.setVisibility(open ? View.VISIBLE : View.GONE);
        }
        View incoming = open ? musicPlaybackScreen : musicShortcut;
        if (!open) {
            if (audioMode && musicShortcut != null && musicShortcut.getVisibility() != View.VISIBLE)
                setMusicShortcutVisible(true);
            else if (audioMode) scheduleMusicMiniAutoHide();
            else host.updateTabBottomPadding();
        }
        if (incoming != null && incoming.getVisibility() == View.VISIBLE) {
            incoming.animate().cancel();
            incoming.setTranslationY(open ? host.dp(10) : host.dp(3));
            incoming.animate().translationY(0f).setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
    }

    void stopVideoForMusic() {
        host.torrentCastGeneration++;
        host.pendingTorrentCast = false;
        host.cleanupPreviousTemporaryTorrent("");
        if (TvHubActivity.videoServer != null && !TvHubActivity.videoServer.isAudioServer()) {
            TvHubActivity.videoServer.stop();
            TvHubActivity.videoServer = null;
        }
        if (host.connected && host.socket != null && host.socket.isOpen() && !audioMode) {
            host.command("STOP_CAST", "ms", 0);
        }
        host.resumableVideo = null;
        host.pendingCastTitle = null;
        host.browserCastTitle = null;
        TvHubActivity.browserPause = null;
        host.reconnectPlay = null;
        host.photoPath = null;
        host.activeWatchKey = null;
        host.library.libraryPlaybackPath = null;
        host.library.libraryPlaybackKey = null;
        host.playbackOpened = false;
        if (host.playbackScreen != null) host.playbackScreen.setVisibility(View.GONE);
        host.setPlaybackShortcutVisible(false);
    }

    void stopMusicForVideo(boolean tvReplacingWithNewCast) {
        boolean hadAudio = audioMode || activeAudioUri != null;
        host.main.removeCallbacks(musicMiniAutoHideRunnable);
        if (TvHubActivity.videoServer != null && TvHubActivity.videoServer.isAudioServer()) {
            TvHubActivity.videoServer.stop();
            TvHubActivity.videoServer = null;
        }
        if (hadAudio && !tvReplacingWithNewCast && host.connected && host.socket != null && host.socket.isOpen()) {
            host.command("STOP_CAST", "ms", 0);
        }
        audioMode = false;
        audioPath = null;
        endedAudioPath = null;
        audioQueueEnded = false;
        musicQueueMode = false;
        musicPlaybackOpened = false;
        if (musicPlaybackScreen != null) musicPlaybackScreen.setVisibility(View.GONE);
        setMusicShortcutVisible(false);
        updateAudioSelection(null, false);
        host.castArtwork = null;
        if (musicMiniArtImage != null) {
            musicMiniArtImage.setImageDrawable(null);
            musicMiniArtImage.setVisibility(View.GONE);
        }
        if (musicMiniArtFallback != null) musicMiniArtFallback.setVisibility(View.VISIBLE);
        if (!tvReplacingWithNewCast && !host.playbackOpened && host.hub != null) {
            host.hub.setVisibility(View.VISIBLE);
        }
        host.updateTabBottomPadding();
        if (!tvReplacingWithNewCast) {
            host.syncCastKeepAliveService();
        }
    }

    boolean hasActiveSession() {
        return audioMode && (!audioQueueEnded || audioPlaying);
    }

    void stopMusicPlayback() {
        int navReturn = host.returnToNavTab;
        host.returnToNavTab = 0;
        stopMusicForVideo(false);
        host.clearCastNotification();
        if (navReturn != 0 && !host.playbackOpened) {
            host.startActivity(new Intent(host, org.proninyaroslav.libretorrent.MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(org.proninyaroslav.libretorrent.MainActivity.EXTRA_SELECT_TAB, navReturn));
            host.overridePendingTransition(0, 0);
        }
    }

    android.net.Uri activeAudioUri() {
        return activeAudioUri;
    }

    boolean isAudioPlaying() {
        return audioPlaying;
    }

    void updateAudioSelection(android.net.Uri uri, boolean playing) {
        if (java.util.Objects.equals(activeAudioUri, uri) && audioPlaying == playing) return;
        activeAudioUri = uri;
        audioPlaying = playing;
        if (host.localMedia != null) host.localMedia.updateAudioRows(uri, playing);
        if (musicPlaybackOpened) renderMusicQueueList();
    }

    void openAudio(java.util.List<LocalMediaPanel.Item> tracks, int index) {
        startAudioFromList(tracks, index, true);
    }

    void toggleOrOpenAudio(java.util.List<LocalMediaPanel.Item> tracks, int index) {
        if (tracks != null && index >= 0 && index < tracks.size()
                && tracks.get(index).uri().equals(activeAudioUri) && audioMode) {
            toggleCurrentAudioPlayPause();
            return;
        }
        startAudioFromList(tracks, index, false);
    }

    void startAudioFromList(java.util.List<LocalMediaPanel.Item> tracks, int index, boolean openSheet) {
        if (tracks == null || tracks.isEmpty() || index < 0 || index >= tracks.size()) return;
        if (tracks.get(index).uri().equals(activeAudioUri) && audioMode) {
            if (openSheet) showMusicSheet(true);
            else toggleCurrentAudioPlayPause();
            return;
        }
        var prefs = host.getSharedPreferences("cinema_music", 0);
        var selected = tracks.get(index);
        audioQueue = prefs.contains("audio_queue_sort")
                ? AudioSort.apply(tracks, prefs.getInt("audio_queue_sort", 1), prefs) : java.util.List.copyOf(tracks);
        audioHistory.clear();
        playAudio(audioQueue.indexOf(selected), openSheet);
    }

    void toggleCurrentAudioPlayPause() {
        if (!audioMode) return;
        if (host.connected && host.socket != null && host.socket.isOpen()) {
            host.command("TOGGLE_PLAY", "ms", 0);
        }
    }

    void playAudio(int index) {
        playAudio(index, false);
    }

    void playAudio(int index, boolean openSheet) {
        if (audioQueue.isEmpty() || index < 0 || index >= audioQueue.size()) return;
        audioIndex = index;
        startAudioTrack(audioQueue.get(index).uri(), openSheet);
    }

    void advanceAudio(boolean automatic) {
        if (audioQueue.isEmpty()) return;
        if (automatic && repeat == 2) { playAudio(audioIndex, false); return; }
        if (audioQueue.size() == 1 && automatic && repeat == 0) {
            finishAudioQueue();
            return;
        }
        int next = audioIndex + 1;
        if (shuffle && audioQueue.size() > 1) {
            next = (audioIndex + 1 + new java.util.Random().nextInt(audioQueue.size() - 1)) % audioQueue.size();
        } else if (next >= audioQueue.size()) {
            if (automatic && repeat == 0) {
                finishAudioQueue();
                return;
            }
            next = 0;
        }
        audioHistory.push(audioIndex);
        playAudio(next, false);
    }

    void finishAudioQueue() {
        audioQueueEnded = true;
        updateAudioSelection(activeAudioUri, false);
        currentMusicPosMs = 0;
        if (musicTimeline != null) musicTimeline.setProgress(0);
        displayMusicTime(0, host.remoteDuration);
        host.updateCastSession(0, host.remoteDuration, false);
        updateMusicButtons(false, activeAudioUri != null);
        host.clearCastNotification();
    }

    void onAudioEnded(String path) {
        if (!audioMode || !path.equals(audioPath) || path.equals(endedAudioPath)) return;
        endedAudioPath = path;
        android.util.Log.i("CinemaAudio", "Track ended; advancing queue from index " + audioIndex);
        advanceAudio(true);
    }

    void previousAudio() {
        if (audioQueue.isEmpty()) return;
        int curPos = musicTimeline != null ? musicTimeline.getProgress() : currentMusicPosMs;
        if (curPos > 5000) seekCurrentAudio(0);
        else playAudio(audioHistory.isEmpty() ? Math.floorMod(audioIndex - 1, audioQueue.size()) : audioHistory.pop(), false);
    }

    void seekCurrentAudio(int ms) {
        int clamped = Math.max(0, ms);
        currentMusicPosMs = clamped;
        host.command("SEEK", "ms", clamped);
    }

    void setMusicQueueMode(boolean showQueue) {
        musicQueueMode = showQueue;
        if (musicArtworkPane != null) musicArtworkPane.setVisibility(showQueue ? View.GONE : View.VISIBLE);
        if (musicQueuePane != null) musicQueuePane.setVisibility(showQueue ? View.VISIBLE : View.GONE);
        if (showQueue) renderMusicQueueList();
    }

    private void showPlaybackSettings() {
        var panel = host.column();
        panel.setPaddingRelative(host.dp(20), host.dp(8), host.dp(20), 0);
        var preferences = host.getSharedPreferences("cinema_link", Context.MODE_PRIVATE);
        var labels = host.getResources().getStringArray(R.array.pref_tv_audio_hud_timeout_entries);
        var values = host.getResources().getStringArray(R.array.pref_tv_audio_hud_timeout_values);
        var timeout = new MaterialButton(host, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        timeout.setAllCaps(false);
        timeout.setTextColor(TvHubActivity.GOLD);
        var title = host.getString(R.string.pref_tv_audio_hud_timeout_title);
        var lookup = java.util.Arrays.asList(values);
        Runnable updateLabel = () -> {
            int selected = lookup.indexOf(String.valueOf(preferences.getInt("audio_hud_timeout", 5000)));
            timeout.setText(selected < 0 ? title : host.getString(R.string.cinema_music_setting_value, title, labels[selected]));
        };
        updateLabel.run();
        timeout.setOnClickListener(view -> new MaterialAlertDialogBuilder(host)
                .setTitle(title)
                .setSingleChoiceItems(labels, lookup.indexOf(String.valueOf(preferences.getInt("audio_hud_timeout", 5000))),
                        (dialog, which) -> {
                            preferences.edit().putInt("audio_hud_timeout", Integer.parseInt(values[which])).apply();
                            onAudioSettingsChanged();
                            updateLabel.run();
                            dialog.dismiss();
                        })
                .setNegativeButton(android.R.string.cancel, null).show());
        panel.addView(timeout, new LinearLayout.LayoutParams(-1, -2));
        new MaterialAlertDialogBuilder(host).setTitle(R.string.cinema_music_playback_settings)
                .setView(panel).setPositiveButton(android.R.string.ok, null).show();
    }

    void buildMusicSheet(LinearLayout page) {
        page.setPadding(host.dp(14), host.dp(8), host.dp(14), 0);

        // Top Drag Handle + Header Bar (Clean Samsung Music Dark & Gold — NO Output: TV badge)
        var handle = new View(host);
        var pill = new GradientDrawable();
        pill.setColor(0xff77736c);
        pill.setCornerRadius(host.dp(2));
        handle.setBackground(pill);
        var handleLp = new LinearLayout.LayoutParams(host.dp(38), host.dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = host.dp(6);
        page.addView(handle, handleLp);

        var header = host.row(page);
        header.setGravity(Gravity.CENTER_VERTICAL);
        var collapse = host.playbackIcon("", host.l("تصغير", "Minimize music player"),
                org.proninyaroslav.libretorrent.R.drawable.cinema_control_down, () -> showMusicSheet(false), false);
        header.addView(collapse, new LinearLayout.LayoutParams(host.dp(44), host.dp(42)));

        var headerTitle = host.label(host.l("مشغّل الموسيقى", "Music Player"), 15);
        headerTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        headerTitle.setTextColor(0xfff5f5f5);
        headerTitle.setSingleLine(true);
        headerTitle.setMaxLines(1);
        headerTitle.setPadding(host.dp(8), 0, host.dp(6), 0);
        header.addView(headerTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        var volBtn = host.playbackIcon("", host.getString(org.proninyaroslav.libretorrent.R.string.cinema_volume),
                org.proninyaroslav.libretorrent.R.drawable.cinema_control_volume, this::toggleMusicVolumeCard, false);
        volBtn.setIconSize(host.dp(20));
        header.addView(volBtn, new LinearLayout.LayoutParams(host.dp(42), host.dp(42)));

        musicSpeedBtn = host.button((audioSpeedPercent == 100 ? "1×" : (audioSpeedPercent / 100f) + "×"),
                host::showSpeed, false);
        musicSpeedBtn.setTextSize(11);
        musicSpeedBtn.setSingleLine(true);
        musicSpeedBtn.setMinWidth(0);
        musicSpeedBtn.setMinimumWidth(0);
        musicSpeedBtn.setPadding(host.dp(10), 0, host.dp(10), 0);
        var spLp = new LinearLayout.LayoutParams(-2, host.dp(36));
        spLp.setMarginStart(host.dp(4));
        header.addView(musicSpeedBtn, spLp);

        var settings = host.playbackIcon("", host.getString(R.string.cinema_music_playback_settings),
                R.drawable.ic_settings_24px, this::showPlaybackSettings, false);
        settings.setIconSize(host.dp(20));
        var settingsSize = new LinearLayout.LayoutParams(host.dp(42), host.dp(42));
        settingsSize.setMarginStart(host.dp(4));
        header.addView(settings, settingsSize);

        var drag = new android.view.View.OnTouchListener() {
            private float startX;
            private float startY;

            @Override public boolean onTouch(View view, android.view.MotionEvent event) {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    startX = event.getRawX();
                    startY = event.getRawY();
                    return true;
                }
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                    float distance = event.getRawY() - startY;
                    if (distance >= host.dp(64) && distance > Math.abs(event.getRawX() - startX) * 1.4f) {
                        showMusicSheet(false);
                    } else {
                        view.performClick();
                    }
                }
                return true;
            }
        };
        handle.setOnTouchListener(drag);
        header.setOnTouchListener(drag);

        buildMusicVolumeCard(page);

        // Switchable Middle Content (Artwork Stage OR Scrollable Playing Queue)
        var stage = new android.widget.FrameLayout(host);
        var stageLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        stageLp.topMargin = host.dp(4);
        stageLp.bottomMargin = host.dp(6);
        page.addView(stage, stageLp);

        // A. Artwork & Track Details Pane (Samsung Music Now Playing center stage)
        musicArtworkPane = host.column();
        musicArtworkPane.setGravity(Gravity.CENTER);
        musicArtworkPane.setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(8));
        stage.addView(musicArtworkPane, new android.widget.FrameLayout.LayoutParams(-1, -1));
        musicArtworkPane.setOnTouchListener(drag);

        var artwork = new PlaybackArtworkView(host, 1f);
        artwork.setOnTouchListener(drag);

        musicArtFallback = host.label("♪", 72);
        musicArtFallback.setGravity(Gravity.CENTER);
        musicArtFallback.setTextColor(TvHubActivity.GOLD);
        artwork.addArtwork(musicArtFallback);

        musicArtImage = new android.widget.ImageView(host);
        musicArtImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        musicArtImage.setVisibility(View.GONE);
        artwork.addArtwork(musicArtImage);

        var artLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        artLp.bottomMargin = host.dp(12);
        musicArtworkPane.addView(artwork, artLp);

        musicSheetTitle = host.label("", 20);
        musicSheetTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        musicSheetTitle.setTextColor(0xfffafafa);
        musicSheetTitle.setSingleLine(true);
        musicSheetTitle.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        musicSheetTitle.setMarqueeRepeatLimit(-1);
        musicSheetTitle.setSelected(true);
        musicSheetTitle.setGravity(Gravity.CENTER);
        musicArtworkPane.addView(musicSheetTitle, new LinearLayout.LayoutParams(-1, -2));

        musicSheetArtist = host.label("", 13);
        musicSheetArtist.setTextColor(0xffb8b8b8);
        musicSheetArtist.setGravity(Gravity.CENTER);
        var arLp = new LinearLayout.LayoutParams(-1, -2);
        arLp.topMargin = host.dp(4);
        arLp.bottomMargin = host.dp(20);
        musicArtworkPane.addView(musicSheetArtist, arLp);

        // Samsung Music secondary action strip: Queue Toggle | TV Status Pill | Stop
        var actionStrip = new LinearLayout(host);
        actionStrip.setOrientation(LinearLayout.HORIZONTAL);
        actionStrip.setGravity(Gravity.CENTER);
        musicArtworkPane.addView(actionStrip, new LinearLayout.LayoutParams(-1, -2));

        musicQueueToggleBtn = host.button(host.l("≡ القائمة", "≡ Queue"), () -> setMusicQueueMode(true), false);
        musicQueueToggleBtn.setTextSize(12);
        musicQueueToggleBtn.setSingleLine(true);
        musicQueueToggleBtn.setPadding(host.dp(14), 0, host.dp(14), 0);
        actionStrip.addView(musicQueueToggleBtn, new LinearLayout.LayoutParams(-2, host.dp(42)));

        var stopChip = host.button(host.l("■ إيقاف", "■ Stop"), this::stopMusicPlayback, false);
        stopChip.setTextSize(12);
        stopChip.setSingleLine(true);
        stopChip.setTextColor(0xfff87171);
        stopChip.setPadding(host.dp(14), 0, host.dp(14), 0);
        var scLp = new LinearLayout.LayoutParams(-2, host.dp(42));
        scLp.setMarginStart(host.dp(12));
        actionStrip.addView(stopChip, scLp);
        favoriteTrack = host.button("", () -> {
            if (host.localMedia == null || activeAudioUri == null) return;
            host.localMedia.toggleFavorite(activeAudioUri);
            host.localMedia.refreshFavorites();
            updateFavoriteTrack();
        }, false);
        favoriteTrack.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        favoriteTrack.setIconPadding(0);
        favoriteTrack.setPadding(0, 0, 0, 0);
        favoriteTrack.setStrokeWidth(0);
        var favoriteLp = new LinearLayout.LayoutParams(host.dp(48), host.dp(48));
        favoriteLp.gravity = Gravity.CENTER_HORIZONTAL;
        favoriteLp.topMargin = host.dp(8);
        musicArtworkPane.addView(favoriteTrack, favoriteLp);
        updateFavoriteTrack();

        // B. Independently Scrollable Playing Queue Pane (keeps top bar + bottom controls fixed!)
        musicQueuePane = host.column();
        musicQueuePane.setVisibility(View.GONE);
        stage.addView(musicQueuePane, new android.widget.FrameLayout.LayoutParams(-1, -1));

        var qHeaderRow = host.row(musicQueuePane);
        qHeaderRow.setPadding(host.dp(4), host.dp(6), host.dp(4), host.dp(8));
        var qTitle = host.label(host.l("قائمة التشغيل", "Playing Queue"), 16);
        qTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        qTitle.setTextColor(TvHubActivity.GOLD);
        qTitle.setSingleLine(true);
        qTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        qHeaderRow.addView(qTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        var sort = host.button(host.l("فرز", "Sort by"),
                () -> AudioSort.choose(host, "audio_queue_sort", this::sortQueue), false);
        sort.setTextSize(11);
        sort.setMinWidth(0);
        sort.setPadding(host.dp(8), 0, host.dp(8), 0);
        qHeaderRow.addView(sort, new LinearLayout.LayoutParams(-2, host.dp(36)));

        musicQueueCountBadge = host.label("0 / 0", 13);
        musicQueueCountBadge.setTextColor(0xffc9c5be);
        var qcLp = new LinearLayout.LayoutParams(-2, -2);
        qcLp.setMarginEnd(host.dp(10));
        qHeaderRow.addView(musicQueueCountBadge, qcLp);

        var backToArtBtn = host.button(host.l("الغلاف ♪", "Now Playing ♪"), () -> setMusicQueueMode(false), false);
        backToArtBtn.setTextSize(12);
        backToArtBtn.setSingleLine(true);
        backToArtBtn.setMinWidth(0);
        backToArtBtn.setMinimumWidth(0);
        backToArtBtn.setPadding(host.dp(12), 0, host.dp(12), 0);
        qHeaderRow.addView(backToArtBtn, new LinearLayout.LayoutParams(-2, host.dp(38)));

        var queueScroll = new ScrollView(host);
        queueScroll.setVerticalScrollBarEnabled(true);
        musicQueuePane.addView(queueScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        musicQueueContainer = host.column();
        musicQueueContainer.setPadding(host.dp(2), host.dp(2), host.dp(2), host.dp(8));
        queueScroll.addView(musicQueueContainer, new android.widget.FrameLayout.LayoutParams(-1, -2));

        // C. Fixed Samsung Music Bottom Transport & SeekBar Dock (never scrolls off screen!)
        var bottomDock = host.column();
        bottomDock.setBackground(CinemaGlassUi.glassDock(host, CinemaGlassUi.Accent.MUSIC));
        bottomDock.setPadding(host.dp(14), host.dp(10), host.dp(14), host.dp(14));
        page.addView(bottomDock, new LinearLayout.LayoutParams(-1, -2));

        musicTimeline = new PlaybackSeekBar(host);
        musicTimeline.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) { musicScrubbing = true; }
            @Override public void onProgressChanged(android.widget.SeekBar bar, int value, boolean user) {
                if (user) displayMusicTime(value, bar.getMax());
            }
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {
                seekCurrentAudio(bar.getProgress());
                musicScrubbing = false;
            }
        });
        musicTimeline.setEnabled(false);
        var mtLp = new LinearLayout.LayoutParams(-1, host.dp(36));
        bottomDock.addView(musicTimeline, mtLp);

        var times = host.row(bottomDock);
        times.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        times.setPadding(host.dp(6), 0, host.dp(6), host.dp(6));
        musicTime = host.label("00:00", 12);
        musicTime.setTextColor(0xffc9c5be);
        musicTime.setFontFeatureSettings("tnum");
        musicDuration = host.label("00:00", 12);
        musicDuration.setTextColor(0xffc9c5be);
        musicDuration.setFontFeatureSettings("tnum");
        musicDuration.setGravity(Gravity.END);
        times.addView(musicTime, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(musicDuration);

        var transport = host.row(bottomDock);
        transport.setGravity(Gravity.CENTER);
        musicShuffleBtn = host.button(shuffle ? host.l("عشوائي ✓", "Shuffle ✓") : host.l("عشوائي", "Shuffle"), () -> {
            shuffle = !shuffle;
            musicShuffleBtn.setText(shuffle ? host.l("عشوائي ✓", "Shuffle ✓") : host.l("عشوائي", "Shuffle"));
            saveAudioPrefs();
        }, false);
        musicShuffleBtn.setTextSize(11);
        musicShuffleBtn.setSingleLine(true);
        musicShuffleBtn.setPadding(host.dp(6), 0, host.dp(6), 0);
        transport.addView(musicShuffleBtn, new LinearLayout.LayoutParams(0, host.dp(42), 1f));

        var prevBtn = host.playbackIcon("", host.l("السابق", "Previous"),
                org.proninyaroslav.libretorrent.R.drawable.ic_skip_previous_24, this::previousAudio, false);
        transport.addView(prevBtn, new LinearLayout.LayoutParams(host.dp(48), host.dp(48)));

        musicPlayPause = host.playbackIcon("", host.l("تشغيل", "Play"),
                org.proninyaroslav.libretorrent.R.drawable.cinema_control_play, this::toggleCurrentAudioPlayPause, true);
        musicPlayPause.setCornerRadius(host.dp(30));
        var playLp = new LinearLayout.LayoutParams(host.dp(60), host.dp(60));
        playLp.setMarginStart(host.dp(6));
        playLp.setMarginEnd(host.dp(6));
        transport.addView(musicPlayPause, playLp);

        var nextBtn = host.playbackIcon("", host.l("التالي", "Next"),
                org.proninyaroslav.libretorrent.R.drawable.ic_skip_next_24, () -> advanceAudio(false), false);
        transport.addView(nextBtn, new LinearLayout.LayoutParams(host.dp(48), host.dp(48)));

        musicRepeatBtn = host.button(repeatLabel(), () -> {
            repeat = (repeat + 1) % 3;
            musicRepeatBtn.setText(repeatLabel());
            saveAudioPrefs();
        }, false);
        musicRepeatBtn.setTextSize(11);
        musicRepeatBtn.setSingleLine(true);
        musicRepeatBtn.setPadding(host.dp(6), 0, host.dp(6), 0);
        transport.addView(musicRepeatBtn, new LinearLayout.LayoutParams(0, host.dp(42), 1f));
    }

    void buildMusicVolumeCard(LinearLayout parent) {
        musicVolumeCard = host.column();
        musicVolumeCard.setBackground(CinemaGlassUi.glassCard(host, CinemaGlassUi.Accent.MUSIC, 16));
        musicVolumeCard.setPadding(host.dp(8), host.dp(6), host.dp(8), host.dp(6));
        var lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = host.dp(6);
        parent.addView(musicVolumeCard, lp);

        var controls = host.row(musicVolumeCard);
        musicMuteButton = host.playbackIcon("", host.l("كتم الصوت", "Mute"),
                org.proninyaroslav.libretorrent.R.drawable.cinema_control_volume, () -> {
                    host.setVolume(host.volumeMuted && host.volumePercent == 0 ? host.lastAudibleVolume : host.volumePercent, !host.volumeMuted);
                    syncMusicVolumeUi();
                }, false);
        controls.addView(musicMuteButton, new LinearLayout.LayoutParams(host.dp(44), host.dp(44)));

        musicVolumeSlider = new android.widget.SeekBar(host);
        musicVolumeSlider.setMax(100);
        var gold = android.content.res.ColorStateList.valueOf(TvHubActivity.GOLD);
        musicVolumeSlider.setProgressTintList(gold);
        musicVolumeSlider.setThumbTintList(gold);
        musicVolumeSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) {
                host.adjustingVolume = true;
                bar.getParent().requestDisallowInterceptTouchEvent(true);
            }
            @Override public void onProgressChanged(android.widget.SeekBar bar, int value, boolean user) {
                if (!user) return;
                host.setVolume(value, value == 0);
                if (musicVolumeBadge != null) musicVolumeBadge.setText(value + "%");
            }
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {
                host.adjustingVolume = false;
                host.main.removeCallbacks(host.sendVolume);
                host.sendVolumeRequest();
            }
        });
        controls.addView(musicVolumeSlider, new LinearLayout.LayoutParams(0, host.dp(44), 1f));

        musicVolumeBadge = host.button("—", () -> {}, false);
        musicVolumeBadge.setTextSize(12);
        musicVolumeBadge.setPadding(0, 0, 0, 0);
        controls.addView(musicVolumeBadge, new LinearLayout.LayoutParams(host.dp(54), host.dp(44)));
        musicVolumeCard.setVisibility(View.GONE);
    }

    void toggleMusicVolumeCard() {
        if (musicVolumeCard == null) return;
        if (musicVolumeCard.isShown()) {
            musicVolumeCard.animate().alpha(0).setDuration(130).withEndAction(() ->
                    musicVolumeCard.setVisibility(View.GONE)).start();
        } else {
            syncMusicVolumeUi();
            musicVolumeCard.animate().cancel();
            musicVolumeCard.setAlpha(0);
            musicVolumeCard.setVisibility(View.VISIBLE);
            musicVolumeCard.animate().alpha(1).setDuration(150).start();
        }
    }

    void syncMusicVolumeUi() {
        if (musicVolumeSlider == null || musicVolumeBadge == null) return;
        musicVolumeSlider.setEnabled(host.volumeAvailable);
        if (!host.adjustingVolume) musicVolumeSlider.setProgress(host.volumeMuted ? 0 : host.volumePercent);
        musicVolumeBadge.setText(host.volumeAvailable ? (host.volumeMuted ? "0%" : host.volumePercent + "%") : "—");
    }

    void displayMusicTime(int position, int duration) {
        if (musicTime != null) musicTime.setText(TvHubActivity.formatTime(position));
        if (musicDuration != null) musicDuration.setText(TvHubActivity.formatTime(duration));
    }

    void updateMusicButtons(boolean playing, boolean enabled) {
        int iconRes = playing
                ? org.proninyaroslav.libretorrent.R.drawable.cinema_control_pause
                : org.proninyaroslav.libretorrent.R.drawable.cinema_control_play;
        if (musicPlayPause != null) {
            musicPlayPause.setIconResource(iconRes);
            musicPlayPause.setEnabled(enabled);
        }
        if (musicMiniPlayPause != null) {
            musicMiniPlayPause.setIconResource(iconRes);
            musicMiniPlayPause.setEnabled(enabled);
        }
    }

    private void sortQueue(int order) {
        if (audioQueue.isEmpty()) return;
        var selected = audioQueue.get(audioIndex);
        var original = audioQueue;
        var prefs = host.getSharedPreferences("cinema_music", 0);
        audioQueue = AudioSort.apply(original, order, prefs);
        audioIndex = audioQueue.indexOf(selected);
        var history = new java.util.ArrayList<>(audioHistory);
        audioHistory.clear();
        for (int previous : history) audioHistory.addLast(audioQueue.indexOf(original.get(previous)));
        renderMusicQueueList();
    }

    private void updateFavoriteTrack() {
        if (favoriteTrack == null) return;
        boolean favorite = host.localMedia != null && host.localMedia.isFavorite(activeAudioUri);
        favoriteTrack.setIconResource(favorite
                ? org.proninyaroslav.libretorrent.R.drawable.cinema_favorite_filled
                : org.proninyaroslav.libretorrent.R.drawable.cinema_favorite_outline);
        favoriteTrack.setEnabled(activeAudioUri != null);
        favoriteTrack.setContentDescription(host.getString(favorite
                ? org.proninyaroslav.libretorrent.R.string.cinema_music_remove_favorite
                : org.proninyaroslav.libretorrent.R.string.cinema_music_add_favorite));
    }

    void renderMusicQueueList() {
        if (musicQueueCountBadge != null) {
            int pos = audioQueue.isEmpty() ? 0 : Math.min(audioQueue.size(), audioIndex + 1);
            musicQueueCountBadge.setText(pos + " / " + audioQueue.size());
        }
        if (musicQueueToggleBtn != null) {
            musicQueueToggleBtn.setText(audioQueue.isEmpty()
                    ? host.l("≡ القائمة", "≡ Queue")
                    : host.l("≡ القائمة · " + (audioIndex + 1) + "/" + audioQueue.size(),
                            "≡ Queue · " + (audioIndex + 1) + "/" + audioQueue.size()));
        }
        if (musicQueueContainer == null) return;
        musicQueueContainer.removeAllViews();
        if (audioQueue.isEmpty()) return;
        int total = Math.min(audioQueue.size(), 120);
        for (int i = 0; i < total; i++) {
            final int trackIdx = i;
            var item = audioQueue.get(i);
            boolean current = (i == audioIndex && item.uri().equals(activeAudioUri));
            var rowView = new LinearLayout(host);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            rowView.setGravity(Gravity.CENTER_VERTICAL);
            rowView.setPadding(host.dp(12), host.dp(9), host.dp(12), host.dp(9));
            rowView.setBackground(CinemaGlassUi.glassPill(host,
                    current ? CinemaGlassUi.Accent.MUSIC : CinemaGlassUi.Accent.SUBTLE, 14));

            var badge = host.label(current ? (audioPlaying ? "♫" : "⏸") : String.valueOf(i + 1), 13);
            badge.setTextColor(current ? TvHubActivity.GOLD : 0xff9a958d);
            badge.setGravity(Gravity.CENTER);
            rowView.addView(badge, new LinearLayout.LayoutParams(host.dp(28), -2));

            var col = host.column();
            var tView = host.label(item.title(), 13);
            tView.setTypeface(current ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            tView.setTextColor(current ? TvHubActivity.GOLD : 0xfff2ede7);
            tView.setSingleLine(true);
            tView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(tView);
            if (item.duration() > 0) {
                var dView = host.label(TvHubActivity.formatTime((int) item.duration()), 11);
                dView.setTextColor(0xff9a958d);
                col.addView(dView);
            }
            var cLp = new LinearLayout.LayoutParams(0, -2, 1f);
            cLp.setMarginStart(host.dp(6));
            rowView.addView(col, cLp);

            rowView.setOnClickListener(v -> {
                if (trackIdx != audioIndex) {
                    audioHistory.push(audioIndex);
                    playAudio(trackIdx, false);
                } else {
                    toggleCurrentAudioPlayPause();
                }
            });
            var lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = host.dp(6);
            musicQueueContainer.addView(rowView, lp);
        }
    }

    void castLocalMedia(android.net.Uri uri, String type) {
        if ("image".equals(type)) { host.castPhoto(uri); return; }
        if ("video".equals(type)) {
            host.castVideo(uri, -1, true);
            return;
        }
        startAudioTrack(uri, true);
    }

    void startAudioTrack(android.net.Uri uri, boolean openSheet) {
        if (!host.connected || host.socket == null || !host.socket.isOpen()) {
            host.notifyStatus(host.l("اتصل بالشاشة أولاً لتشغيل الصوت", "Connect to the TV first to play audio"));
            return;
        }
        host.photoPath = null;
        stopVideoForMusic();
        audioMode = true;
        host.activeWatchExcluded = true;
        host.activeWatchKind = "phone_local_media";
        host.activeWatchKey = null;
        host.library.libraryPlaybackKey = null;

        var track = audioQueue.isEmpty() || audioIndex < 0 || audioIndex >= audioQueue.size()
                ? null : audioQueue.get(audioIndex);
        String title = track != null && track.uri().equals(uri) ? track.title() : host.videoTitle(uri);
        String artist = track != null && track.uri().equals(uri) ? track.artist() : "";
        audioArtistName = artist.isBlank() && track != null ? track.folder() : artist;
        sourceAudioDuration = track == null ? 0 : (int) Math.min(Integer.MAX_VALUE, track.duration());
        host.remoteDuration = sourceAudioDuration;
        currentMusicPosMs = 0;

        if (musicSheetTitle != null) musicSheetTitle.setText(title);
        if (musicSheetArtist != null) musicSheetArtist.setText(audioArtistName);
        if (musicMiniTitle != null) musicMiniTitle.setText(title);
        if (musicMiniSub != null) musicMiniSub.setText(audioArtistName.isBlank() ? host.connectedTvName : audioArtistName);
        if (musicArtImage != null) musicArtImage.setVisibility(View.GONE);
        if (musicArtFallback != null) musicArtFallback.setVisibility(View.VISIBLE);
        if (musicMiniArtImage != null) {
            musicMiniArtImage.setImageDrawable(null);
            musicMiniArtImage.setVisibility(View.GONE);
        }
        if (musicMiniArtFallback != null) musicMiniArtFallback.setVisibility(View.VISIBLE);
        if (musicTimeline != null) {
            musicTimeline.setMax(Math.max(1, sourceAudioDuration));
            musicTimeline.setProgress(0);
            musicTimeline.setEnabled(sourceAudioDuration > 0);
        }
        displayMusicTime(0, sourceAudioDuration);
        host.castArtwork = null;
        if (openSheet) showMusicSheet(true);
        else {
            renderMusicQueueList();
            setMusicShortcutVisible(true);
        }

        try {
            if (TvHubActivity.videoServer == null || !TvHubActivity.videoServer.isAudioServer()) {
                if (TvHubActivity.videoServer != null) TvHubActivity.videoServer.stop();
                String serverHost = host.socket.getLocalSocketAddress().getAddress().getHostAddress();
                TvHubActivity.videoServer = new PhoneVideoServer(serverHost, host.getContentResolver(), null);
                TvHubActivity.videoServer.start();
            }
            String path = TvHubActivity.videoServer.offerAudio(uri);
            audioPath = path;
            endedAudioPath = null;
            audioQueueEnded = false;
            host.showCastNotification(title, "audio");
            updateMusicButtons(false, false);
            updateAudioSelection(uri, false);
            updateFavoriteTrack();
            countedTrackPath = null;
            host.socket.send(new org.json.JSONObject().put("type", "CAST_MEDIA").put("media_type", "audio")
                    .put("exclude_from_history", true).put("source_type", "phone_local_media")
                    .put("path", path).put("title", title).put("artist", artist).toString());
            if (audioSpeedPercent != 100) {
                host.main.postDelayed(() -> host.command("SPEED", "percent", audioSpeedPercent), 350);
            }
            int savedVol = host.getSharedPreferences("cinema_music", Context.MODE_PRIVATE).getInt("pref_audio_volume", -1);
            if (savedVol >= 0 && host.volumeAvailable) {
                host.setVolume(savedVol, savedVol == 0);
            }
            new Thread(() -> readAudioArtwork(uri, path), "cinema-audio-art").start();
        } catch (Exception error) {
            stopMusicPlayback();
            host.notifyStatus(host.l("تعذّر بدء تشغيل الصوت على الشاشة", "Could not start TV audio stream"));
        }
    }

    void readAudioArtwork(android.net.Uri uri, String path) {
        try (var metadata = new android.media.MediaMetadataRetriever()) {
            metadata.setDataSource(host, uri);
            byte[] image = metadata.getEmbeddedPicture();
            if (image == null) return;
            var options = new android.graphics.BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(image, 0, image.length, options);
            options.inJustDecodeBounds = false;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2;
            var bitmap = android.graphics.BitmapFactory.decodeByteArray(image, 0, image.length, options);
            host.main.post(() -> {
                if (path.equals(audioPath) && bitmap != null) {
                    host.castArtwork = bitmap;
                    if (musicArtImage != null && musicArtFallback != null) {
                        musicArtImage.setImageBitmap(bitmap);
                        musicArtImage.setVisibility(View.VISIBLE);
                        musicArtFallback.setVisibility(View.GONE);
                    }
                    if (musicMiniArtImage != null && musicMiniArtFallback != null) {
                        musicMiniArtImage.setImageBitmap(bitmap);
                        musicMiniArtImage.setVisibility(View.VISIBLE);
                        musicMiniArtFallback.setVisibility(View.GONE);
                    }
                    int pos = musicTimeline != null ? musicTimeline.getProgress() : currentMusicPosMs;
                    host.updateCastSession(pos, host.remoteDuration, host.castPlaying);
                    MediaPlaybackNotificationService.syncFromBridge(host);
                }
            });
        } catch (RuntimeException | java.io.IOException ignored) {}
    }

    void onPlaybackAudioMessage(org.json.JSONObject response) {
        if (!audioMode || !response.optString("path").equals(audioPath)) return;
        host.setWebLive(false);
        if (response.optBoolean("ended")) {
            onAudioEnded(response.optString("path"));
            return;
        }
        if (audioQueueEnded) {
            audioQueueEnded = false;
            endedAudioPath = null;
            host.showCastNotification(response.optString("title", host.videoTitle(activeAudioUri)), "audio");
        }
        int duration = response.optInt("duration");
        if (duration <= 0 || sourceAudioDuration > 0
                && Math.abs((long) duration - sourceAudioDuration) > Math.max(5000, sourceAudioDuration / 10)) {
            duration = sourceAudioDuration;
        }
        boolean playing = response.optBoolean("playing");
        if (playing && activeAudioUri != null && !java.util.Objects.equals(countedTrackPath, audioPath)) {
            countedTrackPath = audioPath;
            var prefs = host.getSharedPreferences("cinema_music", 0);
            String key = AudioSort.playCountKey(activeAudioUri);
            prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply();
        }
        int pos = response.optInt("ms");
        if (duration > 0 && audioResumePosition > 0) {
            host.command("SEEK", "ms", Math.min(audioResumePosition, duration - 1));
            audioResumePosition = 0;
        }
        host.remoteDuration = duration;
        if (duration > 0) {
            host.lastCastPosition = pos;
            currentMusicPosMs = pos;
        }
        if (!musicScrubbing && musicTimeline != null) {
            musicTimeline.setEnabled(duration > 0);
            musicTimeline.setMax(Math.max(1, duration));
            musicTimeline.setProgress(pos);
            displayMusicTime(pos, duration);
        }
        host.updateCastSession(pos, duration, playing);
        updateAudioSelection(activeAudioUri, playing);
        updateMusicButtons(playing, playing || duration > 0 || activeAudioUri != null);
        host.syncRemoteVolume(duration > 0 && host.volumeAvailable, playing);
    }
}

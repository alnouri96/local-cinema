package com.archos.mediacenter.video.leanback.cinema;

import android.content.Intent;
import android.graphics.BitmapFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.Player;
import androidx.media3.common.MediaItem;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import android.os.Bundle;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.archos.mediacenter.video.leanback.LeanbackActivity;

@UnstableApi
public final class CinemaLocalMediaActivity extends LeanbackActivity {
    private ImageView image;
    static final java.util.Map<String, byte[]> incomingPhotos = new java.util.concurrent.ConcurrentHashMap<>();
    private android.graphics.Bitmap photo;
    private float zoom = 1, offsetX, offsetY, angle;
    private boolean fill;
    private ExoPlayer audio;
    private TextView audioTitleView, audioArtistView, audioTime;
    private android.widget.ProgressBar audioProgress;
    private AudioReactiveCanvasView visualizer;
    private Runnable fade;
    private String lastEndedTrack;
    private LinearLayout hud;
    private ImageView playbackIcon;
    private final Runnable hideHud = () -> {
        if (hud == null) return;
        hud.animate().alpha(0f).translationY(CinemaUi.dp(this, 24)).setDuration(300)
                .withEndAction(() -> hud.setVisibility(View.INVISIBLE)).start();
    };
    private java.io.File cachedAudio;
    private MediaSessionCompat mediaSession;
    private int generation;
    private boolean prepared;
    private boolean ended;
    private final android.os.Handler timer = new android.os.Handler(android.os.Looper.getMainLooper());

    private void keepScreenOn(boolean on) {
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setKeepScreenOn(on);
        if (visualizer != null) visualizer.setKeepScreenOn(on);
    }

    private void initMediaSession() {
        if (mediaSession != null) return;
        mediaSession = new MediaSessionCompat(this, "CinemaLocalMediaActivity");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() {
                if (audio != null && !audio.isPlaying()) audioCommand("TOGGLE_PLAY", 0);
            }
            @Override public void onPause() {
                if (audio != null && audio.isPlaying()) audioCommand("TOGGLE_PLAY", 0);
            }
            @Override public void onStop() {
                finish();
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
        long pos = audio == null || ended ? 0 : Math.max(0, audio.getCurrentPosition());
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, pos, state == PlaybackStateCompat.STATE_PLAYING ? 1.0f : 0.0f)
                .build());
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        show(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (audio != null && "audio".equals(intent.getStringExtra("kind"))) {
            playAudio(intent);
        } else show(intent);
    }

    private void show(Intent intent) {
        int request = ++generation;
        releaseAudio();
        image = null;
        photo = null;
        zoom = 1;
        offsetX = offsetY = angle = 0;
        fill = false;
        var root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(android.graphics.Color.BLACK);
        setContentView(root);
        var uri = intent.getData();
        if (uri == null) return;
        if ("image".equals(intent.getStringExtra("kind"))) {
            image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.MATRIX);
            image.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> applyPhoto());
            root.addView(image, new android.widget.FrameLayout.LayoutParams(-1, -1));
            new Thread(() -> {
                java.net.HttpURLConnection connection = null;
                try {
                    byte[] pushed = incomingPhotos.remove(uri.getPath());
                    if (pushed != null) {
                        var bitmap = BitmapFactory.decodeByteArray(pushed, 0, pushed.length);
                        runOnUiThread(() -> displayPhoto(request, bitmap));
                        return;
                    }
                    connection = (java.net.HttpURLConnection) new java.net.URL(uri.toString()).openConnection();
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(10000);
                    try (var stream = connection.getInputStream()) {
                        var bitmap = BitmapFactory.decodeStream(stream);
                        runOnUiThread(() -> displayPhoto(request, bitmap));
                    }
                } catch (java.io.IOException error) {
                    runOnUiThread(() -> { if (generation == request && !isDestroyed()) showError(); });
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }, "cinema-photo").start();
            return;
        }

        visualizer = new AudioReactiveCanvasView(this);
        root.addView(visualizer, new android.widget.FrameLayout.LayoutParams(-1, -1));
        hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.VERTICAL);
        int inset = CinemaUi.dp(this, 16);
        hud.setPadding(inset, inset, inset, inset);
        hud.setBackground(CinemaUi.rounded(0xe61c1c1e, inset, 0));
        var placement = new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        placement.setMargins(inset, 0, inset, inset);
        root.addView(hud, placement);
        var title = audioTitleView = CinemaUi.text(this, 22, true);
        title.setText(intent.getStringExtra("title"));
        title.setTextColor(0xffe5b84b);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setTextDirection(View.TEXT_DIRECTION_RTL);
        title.setSelected(true);
        hud.addView(title, new LinearLayout.LayoutParams(-1, -2));
        var artist = audioArtistView = CinemaUi.text(this, 14, false);
        artist.setText(intent.getStringExtra("artist"));
        hud.addView(artist);

        var timeline = new LinearLayout(this);
        timeline.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        timeline.setGravity(Gravity.CENTER_VERTICAL);
        timeline.setPadding(0, CinemaUi.dp(this, 8), 0, 0);
        hud.addView(timeline, new LinearLayout.LayoutParams(-1, -2));
        playbackIcon = new ImageView(this);
        playbackIcon.setScaleType(ImageView.ScaleType.CENTER);
        timeline.addView(playbackIcon, new LinearLayout.LayoutParams(CinemaUi.dp(this, 36), CinemaUi.dp(this, 26)));
        var progress = audioProgress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffe5b84b));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff2e2b23));
        var track = new LinearLayout.LayoutParams(0, CinemaUi.dp(this, 4), 1);
        track.setMargins(inset, 0, inset, 0);
        timeline.addView(progress, track);
        var time = audioTime = CinemaUi.text(this, 14, false);
        timeline.addView(time);
        updatePlaybackIcon(false);
        var extractors = new androidx.media3.extractor.DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true);
        var source = new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this, extractors);
        var load = new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(5000, 25000, 500, 1500).build();
        initMediaSession();
        audio = new ExoPlayer.Builder(this).setMediaSourceFactory(source).setLoadControl(load).build();
        audio.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), true);
        audio.setHandleAudioBecomingNoisy(true);
        audio.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                prepared = state == Player.STATE_READY || state == Player.STATE_ENDED;
                if (state == Player.STATE_READY) {
                    android.util.Log.i("CinemaAudio", "Ready durationMs=" + audio.getDuration());
                } else if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
                    ended = state == Player.STATE_ENDED;
                    keepScreenOn(false);
                    updateMediaSessionState(state == Player.STATE_IDLE
                            ? PlaybackStateCompat.STATE_NONE : PlaybackStateCompat.STATE_STOPPED);
                    if (state == Player.STATE_ENDED) {
                        String id = trackPath();
                        if (!id.equals(lastEndedTrack)) {
                            lastEndedTrack = id;
                            CinemaLinkServer.audioEnded(id);
                        }
                        updateAudioHud();
                    }
                }
            }

            @Override public void onEvents(Player player, Player.Events events) {
                int state = player.getPlaybackState();
                keepScreenOn(!isFinishing() && player.getPlayWhenReady()
                        && (state == Player.STATE_READY || state == Player.STATE_BUFFERING));
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                updateMediaSessionState(playing ? PlaybackStateCompat.STATE_PLAYING
                        : (ended ? PlaybackStateCompat.STATE_STOPPED : PlaybackStateCompat.STATE_PAUSED));
                updatePlaybackIcon(playing);
                if (playing && fade != null) timer.post(fade);
            }

            @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                android.util.Log.w("CinemaAudio", "Playback error: " + error.getErrorCodeName());
                keepScreenOn(false);
                updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED);
                showError();
            }
        });
        visualizer.attach(audio);
        timer.post(new Runnable() {
            @Override public void run() {
                if (audio == null) return;
                updateAudioHud();
                timer.postDelayed(this, 500);
            }
        });
        playAudio(intent);
    }

    private void playAudio(Intent intent) {
        if (fade != null) timer.removeCallbacks(fade);
        audio.stop();
        prepared = false;
        ended = false;
        lastEndedTrack = null;
        if (cachedAudio != null) cachedAudio.delete();
        String cachedPath = intent.getStringExtra("cached_audio");
        cachedAudio = cachedPath == null ? null : new java.io.File(cachedPath);
        var uri = cachedAudio == null ? intent.getData() : android.net.Uri.fromFile(cachedAudio);
        audioTitleView.setText(intent.getStringExtra("title"));
        audioArtistView.setText(intent.getStringExtra("artist"));
        audioArtistView.setVisibility(audioArtistView.length() > 0 ? View.VISIBLE : View.GONE);
        audio.setVolume(0.25f);
        fade = new Runnable() {
            private int step;
            @Override public void run() {
                if (audio == null || fade != this) return;
                audio.setVolume(Math.min(1f, 0.25f + ++step * 0.15f));
                if (step < 5) timer.postDelayed(this, 30);
                else fade = null;
            }
        };
        audio.setMediaItem(MediaItem.fromUri(uri));
        audio.prepare();
        audio.play();
        updateAudioHud();
        showHud();
    }

    private String trackPath() {
        String path = getIntent().getStringExtra("track_path");
        return path == null ? getIntent().getData().getPath() : path;
    }

    private long duration() {
        return audio == null || audio.getDuration() == C.TIME_UNSET ? 0 : Math.max(0, audio.getDuration());
    }

    private void updateAudioHud() {
        long duration = duration();
        long position = ended ? 0 : audio.getCurrentPosition();
        audioProgress.setMax((int) Math.min(Integer.MAX_VALUE, duration));
        audioProgress.setProgress((int) Math.min(Integer.MAX_VALUE, position));
        audioTime.setText(android.text.format.DateUtils.formatElapsedTime(position / 1000)
                + " / " + android.text.format.DateUtils.formatElapsedTime(duration / 1000));
    }

    void showHud() {
        if (hud == null) return;
        timer.removeCallbacks(hideHud);
        hud.animate().cancel();
        int timeout = getSharedPreferences("cinema_link", MODE_PRIVATE).getInt("audio_hud_timeout", 5000);
        if (timeout < 0) {
            hud.setVisibility(View.GONE);
            return;
        }
        hud.setVisibility(View.VISIBLE);
        hud.animate().alpha(1f).translationY(0f).setDuration(180).start();
        if (timeout > 0) timer.postDelayed(hideHud, timeout);
    }

    private void updatePlaybackIcon(boolean playing) {
        if (playbackIcon == null) return;
        playbackIcon.setImageResource(playing
                ? com.archos.mediacenter.video.R.drawable.cinema_music_pause
                : com.archos.mediacenter.video.R.drawable.cinema_music_play);
        playbackIcon.setContentDescription(playing ? "Playing" : "Paused");
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int key = event.getKeyCode();
        if (prepared && audio != null && (key == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || key == android.view.KeyEvent.KEYCODE_ENTER)) {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                audioCommand("TOGGLE_PLAY", 0);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    void rotate() {
        angle = (angle + 90f) % 360f;
        applyPhoto();
    }

    private void displayPhoto(int request, android.graphics.Bitmap bitmap) {
        if (generation != request || isDestroyed()) return;
        if (bitmap == null) {
            showError();
            return;
        }
        photo = bitmap;
        image.setImageBitmap(bitmap);
        applyPhoto();
    }

    void photoCommand(String path, boolean stop, float scale, float x, float y, float degrees, boolean fill) {
        if (image == null || getIntent().getData() == null || !path.equals(getIntent().getData().getPath())) return;
        if (stop) {
            finish();
            return;
        }
        zoom = Math.max(1, Math.min(5, scale));
        offsetX = Math.max(-1, Math.min(1, x));
        offsetY = Math.max(-1, Math.min(1, y));
        angle = Math.floorMod(Math.round(degrees / 90f), 4) * 90;
        this.fill = fill;
        applyPhoto();
    }

    private void applyPhoto() {
        if (image == null || photo == null || image.getWidth() == 0) return;
        float width = photo.getWidth(), height = photo.getHeight();
        double radians = Math.toRadians(angle);
        float boundsWidth = (float) (width * Math.abs(Math.cos(radians)) + height * Math.abs(Math.sin(radians)));
        float boundsHeight = (float) (width * Math.abs(Math.sin(radians)) + height * Math.abs(Math.cos(radians)));
        float horizontal = image.getWidth() / boundsWidth, vertical = image.getHeight() / boundsHeight;
        float factor = zoom * (fill ? Math.max(horizontal, vertical) : Math.min(horizontal, vertical));
        var matrix = new android.graphics.Matrix();
        matrix.postTranslate(-width / 2, -height / 2);
        matrix.postRotate(angle);
        matrix.postScale(factor, factor);
        matrix.postTranslate(image.getWidth() / 2f + offsetX * boundsWidth * factor / 2,
                image.getHeight() / 2f + offsetY * boundsHeight * factor / 2);
        image.setImageMatrix(matrix);
    }

    private void showError() {
        android.widget.Toast.makeText(this, "Could not open media from the phone", android.widget.Toast.LENGTH_LONG).show();
    }

    void audioCommand(String type, long value) {
        if (audio == null) return;
        if ("STOP_PLAYBACK".equals(type)) { finish(); return; }
        if (!prepared) return;
        switch (type) {
            case "TOGGLE_PLAY" -> {
                if (audio.isPlaying()) audio.pause();
                else {
                    if (ended) {
                        audio.seekTo(0);
                        lastEndedTrack = null;
                    }
                    ended = false;
                    audio.play();
                }
                updatePlaybackIcon(audio.isPlaying());
                showHud();
            }
            case "SEEK", "JUMP" -> {
                long position = "JUMP".equals(type) ? audio.getCurrentPosition() + value : value;
                audio.seekTo((int) Math.max(0, Math.min(duration(), position)));
                ended = false;
            }
            case "SPEED" -> {
                if (value >= 50 && value <= 200) {
                    audio.setPlaybackSpeed(value / 100f);
                }
            }
        }
    }

    org.json.JSONObject audioState() throws org.json.JSONException {
        if (audio == null) return null;
        return new org.json.JSONObject().put("type", "PLAYBACK").put("audio", true)
                .put("path", trackPath()).put("title", getIntent().getStringExtra("title"))
                .put("ms", ended ? 0 : audio.getCurrentPosition())
                .put("duration", duration())
                .put("playing", prepared && audio.isPlaying()).put("ended", ended);
    }

    boolean displayingPhoto() {
        return image != null && !isFinishing();
    }

    private void releaseAudio() {
        prepared = false;
        ended = false;
        keepScreenOn(false);
        try {
            updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED);
        } catch (Exception ignored) {}
        if (mediaSession != null) {
            try { mediaSession.setActive(false); } catch (Exception ignored) {}
            try { mediaSession.release(); } catch (Exception ignored) {}
            mediaSession = null;
        }
        timer.removeCallbacksAndMessages(null);
        if (hud != null) hud.animate().cancel();
        hud = null;
        playbackIcon = null;
        if (visualizer != null) {
            try { visualizer.attach(null); } catch (Exception ignored) {}
            visualizer = null;
        }
        if (cachedAudio != null) {
            try { cachedAudio.delete(); } catch (Exception ignored) {}
            cachedAudio = null;
        }
        if (audio != null) {
            try { audio.stop(); } catch (Exception ignored) {}
            try { audio.release(); } catch (Exception ignored) {}
            audio = null;
        }
    }

    @Override protected void onStop() {
        keepScreenOn(false);
        if (audio != null && audio.isPlaying()) {
            audio.pause();
        }
        updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED);
        super.onStop();
    }

    @Override protected void onDestroy() {
        generation++;
        releaseAudio();
        super.onDestroy();
    }
}

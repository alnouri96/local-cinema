package com.archos.mediacenter.video.leanback.cinema;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.activity.result.contract.ActivityResultContracts;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.leanback.LeanbackActivity;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.archos.mediacenter.video.leanback.cinema.CinemaArtSettingsSheet.*;

public final class CinemaArtModeActivity extends LeanbackActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService images = Executors.newSingleThreadExecutor();
    private final Runnable slide = () -> next(true);
    private final Runnable sleep = this::finish;
    private final Runnable reposition = this::shiftArt;
    private final java.util.Random random = new java.util.Random();
    private CinemaArtFrameView frame;
    private TextClock clock;
    private TextView empty;
    private SharedPreferences settings;
    private List<String> paths = List.of();
    private int index = -1, errors, generation;
    private Future<?> decode;
    private boolean started, showingImage;
    private int sleepIndex = -1;

    private final androidx.activity.result.ActivityResultLauncher<Intent> picker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                var selection = result.getData().getStringArrayListExtra(CinemaFileBrowserActivity.SELECTED_IMAGES);
                if (selection == null || selection.isEmpty()) return;
                paths = List.copyOf(selection);
                settings.edit().putString("images", new JSONArray(paths).toString()).apply();
                index = -1;
                errors = 0;
                showingImage = false;
                if (started) next(false);
            });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        settings = getSharedPreferences("cinema_art", MODE_PRIVATE);
        try {
            var saved = new JSONArray(settings.getString("images", "[]"));
            var list = new ArrayList<String>();
            for (int i = 0; i < saved.length(); i++) list.add(saved.getString(i));
            paths = List.copyOf(list);
        } catch (org.json.JSONException ignored) {}

        var root = new FrameLayout(this);
        root.setLayoutDirection(CinemaUi.arabic(this) ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(CinemaTokens.CANVAS);
        frame = new CinemaArtFrameView(this);
        root.addView(frame, new FrameLayout.LayoutParams(-1, -1));
        clock = new TextClock(this);
        clock.setTextSize(20);
        clock.setTextColor(0xcaffffff);
        clock.setShadowLayer(3, 0, 1, 0xcc000000);
        var corner = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        corner.topMargin = CinemaUi.dp(this, 26);
        corner.setMarginEnd(CinemaUi.dp(this, 34));
        root.addView(clock, corner);
        empty = CinemaUi.text(this, 17, true);
        empty.setText(R.string.cinema_art_choose_images);
        empty.setPaddingRelative(CinemaUi.dp(this, 24), 0, CinemaUi.dp(this, 24), 0);
        empty.setGravity(Gravity.CENTER);
        empty.setOnClickListener(v -> chooseImages());
        CinemaUi.focusable(empty);
        root.addView(empty, new FrameLayout.LayoutParams(-2, CinemaUi.dp(this, 56), Gravity.CENTER));
        setContentView(root);
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        update();
        if (!showingImage && !paths.isEmpty()) next(false);
        if (paths.isEmpty()) empty.requestFocus();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void chooseImages() {
        picker.launch(new Intent(this, CinemaFileBrowserActivity.class).putExtra(CinemaFileBrowserActivity.IMAGE_MODE, true));
    }

    private void update() {
        frame.appearance(settings.getInt(FRAME, 0), settings.getInt(TONE, 0), settings.getInt(BRIGHTNESS, 85),
                settings.getInt(FRAME_SIZE, CinemaArtFrameView.DEFAULT_SIZE));
        clock.setVisibility(settings.getBoolean(CLOCK, false) ? View.VISIBLE : View.GONE);
        int requested = settings.getInt(SLEEP, 0);
        if (requested != sleepIndex) {
            sleepIndex = requested;
            main.removeCallbacks(sleep);
            if (SLEEP_MINUTES[requested] > 0) main.postDelayed(sleep, SLEEP_MINUTES[requested] * 60_000L);
        }
        main.removeCallbacks(reposition);
        if (settings.getBoolean(SHIFT, true)) main.postDelayed(reposition, 60_000L);
        else {
            frame.shift(0, 0);
            clock.setTranslationX(0);
            clock.setTranslationY(0);
        }
        scheduleSlide();
    }

    private void shiftArt() {
        int x = random.nextInt(7) - 3;
        int y = random.nextInt(7) - 3;
        frame.shift(x, y);
        clock.setTranslationX(-x);
        clock.setTranslationY(-y);
        main.postDelayed(reposition, 60_000L);
    }

    private void scheduleSlide() {
        main.removeCallbacks(slide);
        if (started && paths.size() > 1 && errors < paths.size())
            main.postDelayed(slide, INTERVAL_SECONDS[settings.getInt(INTERVAL, 1)] * 1000L);
    }

    private void next(boolean transition) {
        if (!started || paths.isEmpty()) return;
        if (decode != null) decode.cancel(true);
        int token = ++generation;
        index = (index + 1) % paths.size();
        Uri source = Uri.parse(paths.get(index));
        var manager = getSystemService(android.app.ActivityManager.class);
        int maxEdge = manager != null && manager.getMemoryClass() < 192 ? 2048 : 3840;
        decode = images.submit(() -> {
            android.graphics.Bitmap bitmap = null;
            try { bitmap = CinemaArtStorage.decode(this, source, maxEdge); } catch (Exception ignored) {}
            var result = bitmap;
            main.post(() -> {
                if (!started || token != generation || isDestroyed()) {
                    if (result != null) result.recycle();
                    return;
                }
                if (result != null) {
                    showingImage = true;
                    errors = 0;
                    empty.setVisibility(View.GONE);
                    frame.display(result, transition);
                    scheduleSlide();
                } else if (++errors < paths.size()) {
                    next(transition);
                } else {
                    empty.setText(R.string.cinema_art_unavailable);
                    empty.setVisibility(View.VISIBLE);
                    empty.requestFocus();
                }
            });
        });
    }

    @Override public boolean onKeyUp(int key, KeyEvent event) {
        if (empty.getVisibility() != View.VISIBLE && (key == KeyEvent.KEYCODE_DPAD_CENTER
                || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_MENU)) {
            openSettings();
            return true;
        }
        if (key == KeyEvent.KEYCODE_MENU) {
            openSettings();
            return true;
        }
        return super.onKeyUp(key, event);
    }

    private void openSettings() {
        main.removeCallbacks(slide);
        CinemaArtSettingsSheet.show(this, settings, this::chooseImages, this::update);
        scheduleSlide();
    }

    @Override protected void onStop() {
        started = false;
        generation++;
        if (decode != null) decode.cancel(true);
        main.removeCallbacks(slide);
        main.removeCallbacks(reposition);
        super.onStop();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        images.shutdownNow();
        super.onDestroy();
    }
}

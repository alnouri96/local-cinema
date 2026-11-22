package org.proninyaroslav.libretorrent.cinema_hub;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.os.ConfigurationCompat;

import com.google.android.material.button.MaterialButton;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;
import org.proninyaroslav.libretorrent.R;

import java.lang.ref.WeakReference;
import java.net.URI;

/**
 * Global floating TV D-Pad orb & interactive remote controller usable across ALL Phone screens
 * (TV Hub, Browser, Torrents, RSS, Settings), with auto-connect, dual-color Touchpad/D-Pad
 * slide toggle, persistent TV keyboard bar, customizable shortcut button, and full haptics.
 */
public final class FloatingTvDpadController {
    private static final String PREF_NAME = "cinema_floating_dpad_v1";
    private static final String KEY_EDGE_RIGHT = "edge_right";
    private static final String KEY_Y_FRACTION = "y_fraction";
    private static final String KEY_CUSTOM_SHORTCUT = "custom_shortcut";
    private static final int GOLD = 0xffe5b94c;
    private static final int TOUCH_ACCENT = 0xff3b82f6;
    private static final int DPAD_ACCENT = 0xfff59e0b;
    private static final String TAG_OVERLAY = "floating_tv_dpad_overlay";

    private static volatile WebSocketClient sharedRemoteSocket;
    private static volatile boolean sharedRemoteConnected;
    private static volatile WebSocketClient externalSocketOverride;
    private static volatile boolean externalConnectedOverride;
    private static volatile String pendingJsonCommand;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private static WeakReference<FloatingOverlayView> activeOverlay = new WeakReference<>(null);
    private static WeakReference<View> activeAdbDot = new WeakReference<>(null);
    private static WeakReference<TextView> activeAdbLabel = new WeakReference<>(null);
    private static WeakReference<View> activeWebDot = new WeakReference<>(null);
    private static WeakReference<TextView> activeWebLabel = new WeakReference<>(null);

    private FloatingTvDpadController() {}

    static Activity currentForegroundActivity() {
        return currentActivity.get();
    }

    public static void updateActiveSocket(WebSocketClient ws, boolean connected) {
        externalSocketOverride = ws;
        externalConnectedOverride = connected && ws != null && ws.isOpen();
        onRemoteConnectionChanged();
    }

    private static void applyLedState(View dot, TextView label, boolean active, int activeColor) {
        if (dot != null && dot.getBackground() instanceof GradientDrawable gd) {
            gd.setColor(active ? activeColor : 0xff4b5563);
        }
        if (label != null) {
            label.setTextColor(active ? 0xffffffff : 0xffc9c5bc);
        }
    }

    public static void onRemoteConnectionChanged() {
        MAIN.post(() -> {
            boolean connected = isRemoteConnected();
            FloatingOverlayView overlay = activeOverlay.get();
            if (overlay != null) overlay.updateStatusDot(connected);
            applyLedState(activeAdbDot.get(), activeAdbLabel.get(),
                    connected && CinemaRemoteBridge.isAdbRemoteActive(), 0xff22c55e);
            applyLedState(activeWebDot.get(), activeWebLabel.get(),
                    connected && CinemaRemoteBridge.isWebRemoteActive(), 0xff3b82f6);
            if (connected) flushPendingCommand();
        });
    }

    public static void attach(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        currentActivity = new WeakReference<>(activity);
        CinemaRemoteBridge.ensurePairedConnection(activity);
        View contentView = activity.findViewById(android.R.id.content);
        if (!(contentView instanceof FrameLayout root)) return;

        View existing = root.findViewWithTag(TAG_OVERLAY);
        if (existing != null) root.removeView(existing);

        FloatingOverlayView overlay = new FloatingOverlayView(activity);
        overlay.setTag(TAG_OVERLAY);
        activeOverlay = new WeakReference<>(overlay);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        onRemoteConnectionChanged();
    }

    public static void detach(Activity activity) {
        if (activity == null) return;
        if (currentActivity.get() == activity) currentActivity.clear();
        CinemaRemoteBridge.onRemoteUiPaused();
        View contentView = activity.findViewById(android.R.id.content);
        if (contentView instanceof FrameLayout root) {
            View existing = root.findViewWithTag(TAG_OVERLAY);
            if (existing != null) root.removeView(existing);
        }
    }

    private static final class FloatingOverlayView extends FrameLayout {
        private final Activity activity;
        private final SharedPreferences prefs;
        private final FrameLayout orbContainer;
        private final View statusDot;
        private float downRawY;
        private float startY;
        private boolean dragging;
        private final int touchSlop;

        FloatingOverlayView(Activity activity) {
            super(activity);
            this.activity = activity;
            this.prefs = activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
            setLayoutDirection(activity.getResources().getConfiguration().getLayoutDirection());
            setElevation(dp(16));
            setClipChildren(false);
            setClipToPadding(false);

            orbContainer = new FrameLayout(activity);
            orbContainer.setVisibility(INVISIBLE);
            orbContainer.setElevation(dp(8));
            orbContainer.setClipChildren(false);

            GradientDrawable orbBg = new GradientDrawable();
            orbBg.setShape(GradientDrawable.OVAL);
            orbBg.setColor(0xe618181e);
            orbBg.setStroke(Math.max(dp(1), Math.round(getResources().getDisplayMetrics().density * 1.5f)), GOLD);
            orbContainer.setBackground(orbBg);

            ImageView icon = new ImageView(activity);
            icon.setImageResource(R.drawable.ic_remote_dpad);
            icon.setColorFilter(GOLD);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            orbContainer.addView(icon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));

            statusDot = new View(activity);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(isRemoteConnected() ? 0xff34d399 : 0xffef4444);
            dotBg.setStroke(Math.round(getResources().getDisplayMetrics().density * 1.4f), 0xff18181e);
            statusDot.setBackground(dotBg);
            var dotLp = new FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP | Gravity.END);
            dotLp.topMargin = dp(2);
            dotLp.setMarginEnd(dp(2));
            orbContainer.addView(statusDot, dotLp);

            orbContainer.setContentDescription(activity.getString(R.string.cinema_remote_open));
            orbContainer.setOnClickListener(v -> showRemoteSheet(activity, this::syncLayout));
            orbContainer.setFocusable(true);
            orbContainer.setOnTouchListener((v, event) -> handleTouchHandle(event));

            var rootLp = new LayoutParams(dp(46), dp(46), Gravity.TOP | Gravity.LEFT);
            addView(orbContainer, rootLp);
            addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> syncLayout());
        }

        void updateStatusDot(boolean connected) {
            if (statusDot.getBackground() instanceof GradientDrawable gd) {
                gd.setColor(connected ? 0xff34d399 : 0xffef4444);
            }
        }

        private boolean handleTouchHandle(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    requestDisallowInterceptTouchEvent(true);
                    downRawY = event.getRawY();
                    startY = orbContainer.getY();
                    dragging = false;
                    orbContainer.animate().scaleX(0.90f).scaleY(0.90f).setDuration(70).start();
                    triggerHaptic(orbContainer, false);
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    float dy = event.getRawY() - downRawY;
                    if (Math.abs(dy) > touchSlop) dragging = true;
                    if (dragging) {
                        float minY = dp(64);
                        float maxY = Math.max(minY, getHeight() - orbContainer.getHeight() - dp(76));
                        orbContainer.setY(Math.max(minY, Math.min(maxY, startY + dy)));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    requestDisallowInterceptTouchEvent(false);
                    orbContainer.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                    if (dragging) {
                        float avail = Math.max(1f, getHeight() - orbContainer.getHeight());
                        float frac = Math.max(0.08f, Math.min(0.86f, orbContainer.getY() / avail));
                        prefs.edit().putFloat(KEY_Y_FRACTION, frac).apply();
                    } else {
                        orbContainer.performClick();
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    requestDisallowInterceptTouchEvent(false);
                    if (dragging) orbContainer.setY(startY);
                    orbContainer.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                    return true;
                }
            }
            return false;
        }

        void syncLayout() {
            if (getWidth() <= orbContainer.getWidth() || getHeight() <= orbContainer.getHeight()
                    || orbContainer.getWidth() == 0) return;
            boolean edgeRight = prefs.getBoolean(KEY_EDGE_RIGHT, true);
            float yFrac = prefs.getFloat(KEY_Y_FRACTION, 0.72f);
            int available = getHeight() - orbContainer.getHeight();
            float targetY = Math.max(dp(64), Math.min(available - dp(76), available * yFrac));
            float targetX = edgeRight ? getWidth() - orbContainer.getWidth() - dp(10) : dp(10);
            orbContainer.setX(targetX);
            orbContainer.setY(Math.min(available, targetY));
            orbContainer.setVisibility(VISIBLE);
            updateStatusDot(isRemoteConnected());
        }

        private int dp(int v) {
            return Math.round(v * getResources().getDisplayMetrics().density);
        }
    }

    // ---- Customizable Shortcut Definitions ----

    private record ShortcutOption(String id, int keyCode, int iconRes, String arLabel, String enLabel) {}

    private static ShortcutOption[] shortcutOptions() {
        return new ShortcutOption[]{
                new ShortcutOption("source", 178, R.drawable.ic_remote_input, "المصادر (HDMI / Source)", "Input Source (HDMI)"),
                new ShortcutOption("settings", 176, R.drawable.ic_remote_settings, "الإعدادات (Settings)", "TV Settings"),
                new ShortcutOption("search", 84, R.drawable.ic_remote_search, "البحث (Search)", "Search"),
                new ShortcutOption("play_pause", 85, R.drawable.ic_remote_play_pause, "تشغيل / إيقاف مؤقت", "Play / Pause"),
                new ShortcutOption("info", 165, R.drawable.ic_remote_info, "معلومات (Info)", "Info"),
                new ShortcutOption("mute", 164, R.drawable.ic_remote_mute, "كتم الصوت (Mute)", "Mute Audio")
        };
    }

    private static ShortcutOption resolveShortcut(SharedPreferences prefs) {
        String saved = prefs.getString(KEY_CUSTOM_SHORTCUT, "source");
        for (ShortcutOption opt : shortcutOptions()) {
            if (opt.id.equals(saved)) return opt;
        }
        return shortcutOptions()[0];
    }

    // ---- Remote Control Sheet & Shared WebSocket Sender ----

    private static void showRemoteSheet(Activity activity, Runnable onSideChanged) {
        ensureRemoteSocketConnected(activity);
        boolean ar = isArabic(activity);
        SharedPreferences prefs = activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        boolean connectedNow = isRemoteConnected();
        boolean adbActiveNow = connectedNow && CinemaRemoteBridge.isAdbRemoteActive();
        boolean webActiveNow = connectedNow && CinemaRemoteBridge.isWebRemoteActive();

        LinearLayout remote = new LinearLayout(activity);
        remote.setOrientation(LinearLayout.VERTICAL);
        remote.setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 18), dp(activity, 12));
        remote.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        remote.setBackground(remoteSurface(activity, 24, 0xff5b472f));

        var remoteWindow = new android.app.Dialog(activity);
        remoteWindow.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        var remoteLayout = new FloatingRemoteLayout(activity, prefs, remote, remoteWindow::dismiss);

        // 1. Header Bar: Power left | "TV Remote" + status stacked centered | QR + Cast + Swap + Close right
        LinearLayout topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER);
        remote.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        ImageView powerBtn = headerIcon(activity, R.drawable.ic_remote_power, 0xfffca5a5, R.string.cinema_remote_power_label);

        attachInteractiveTouch(powerBtn, () -> new AlertDialog.Builder(activity)
                .setMessage(ar ? "إطفاء / قفل شاشة التلفاز؟" : "Lock the TV screen?")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> sendKey(activity, 223))
                .show(), null);
        topBar.addView(powerBtn, headerSize(activity));

        LinearLayout headerTitleCol = new LinearLayout(activity);
        headerTitleCol.setOrientation(LinearLayout.VERTICAL);
        headerTitleCol.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = new TextView(activity);
        title.setText(R.string.cinema_remote_title);
        title.setMaxLines(2);
        title.setTextSize(13);
        title.setTextColor(GOLD);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitleCol.addView(title, new LinearLayout.LayoutParams(-2, -2));

        var titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.setMarginStart(dp(activity, 4));
        titleLp.setMarginEnd(dp(activity, 4));

        ImageView qrBtn = headerIcon(activity, R.drawable.ic_remote_qr, GOLD, R.string.cinema_remote_scan_tv);
        attachInteractiveTouch(qrBtn, () -> {
            remoteWindow.dismiss();
            android.content.Intent qrIntent = new android.content.Intent(activity, TvHubActivity.class)
                    .putExtra("start_qr_scan", true)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            activity.startActivity(qrIntent);
        }, null);
        topBar.addView(qrBtn, headerSize(activity));

        ImageView nowCastBtn = headerIcon(activity, R.drawable.ic_remote_casting_up, GOLD, R.string.cinema_remote_cast_control);
        attachInteractiveTouch(nowCastBtn, () -> {
            remoteWindow.dismiss();
            android.content.Intent castIntent = new android.content.Intent(activity, TvHubActivity.class)
                    .putExtra("open_playback_controls", true)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            activity.startActivity(castIntent);
        }, null);
        topBar.addView(nowCastBtn, headerSize(activity));

        ImageView swapBtn = headerIcon(activity, R.drawable.ic_remote_swap, GOLD, R.string.cinema_remote_switch_edge);
        attachInteractiveTouch(swapBtn, () -> {
            boolean nextRight = !prefs.getBoolean(KEY_EDGE_RIGHT, true);
            prefs.edit().putBoolean(KEY_EDGE_RIGHT, nextRight).apply();
            if (onSideChanged != null) onSideChanged.run();
            CinemaSnackBanner.show(activity, nextRight
                    ? (ar ? "تم نقل أيقونة الريموت إلى يمين الشاشة" : "Moved Remote orb to Right edge")
                    : (ar ? "تم نقل أيقونة الريموت إلى يسار الشاشة" : "Moved Remote orb to Left edge"));
        }, null);
        topBar.addView(swapBtn, headerSize(activity));

        ImageView closeBtn = headerIcon(activity, R.drawable.ic_remote_close, GOLD, R.string.cinema_close);
        attachInteractiveTouch(closeBtn, remoteWindow::dismiss, null);
        topBar.addView(closeBtn, headerSize(activity));

        // 1b. Connection Status + ADB/Web badges aligned left directly underneath the Power button
        LinearLayout statusRow = new LinearLayout(activity);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4));
        var srLp = new LinearLayout.LayoutParams(-1, -2);
        srLp.topMargin = dp(activity, 4);
        srLp.bottomMargin = dp(activity, 10);
        remote.addView(statusRow, srLp);


        View[] adbViews = addStatusLedChip(activity, statusRow, "ADB", 0xff22c55e, adbActiveNow);
        activeAdbDot = new WeakReference<>(adbViews[0]);
        activeAdbLabel = new WeakReference<>((TextView) adbViews[1]);

        View[] webViews = addStatusLedChip(activity, statusRow, "Web", 0xff3b82f6, webActiveNow);
        statusRow.addView(headerTitleCol, titleLp);
        TextView speedButton = new TextView(activity);
        speedButton.setTextColor(GOLD);
        speedButton.setTextSize(15);
        speedButton.setBackground(remoteSurface(activity, 11, GOLD));
        speedButton.setGravity(Gravity.CENTER);
        statusRow.addView(speedButton, headerSize(activity));

        ImageView editButton = headerIcon(activity, R.drawable.cinema_remote_resize, GOLD, R.string.cinema_remote_edit);
        attachInteractiveTouch(editButton, () -> remoteLayout.setEditing(true), null);
        statusRow.addView(editButton, headerSize(activity));

        setupTransportChip(activity, prefs, adbViews[1], "adb", R.string.cinema_remote_select_adb);
        setupTransportChip(activity, prefs, webViews[1], "web", R.string.cinema_remote_select_web);

        activeWebDot = new WeakReference<>(webViews[0]);
        activeWebLabel = new WeakReference<>((TextView) webViews[1]);

        // Persistent Full-Width TV Keyboard Text Bar (clean separation below status)
        LinearLayout textRow = new LinearLayout(activity);
        textRow.setOrientation(LinearLayout.HORIZONTAL);
        textRow.setGravity(Gravity.CENTER_VERTICAL);
        textRow.setPadding(dp(activity, 12), dp(activity, 4), dp(activity, 6), dp(activity, 4));
        textRow.setBackground(CinemaGlassUi.glassPill(activity, CinemaGlassUi.Accent.SUBTLE, 14));
        var trLp = new LinearLayout.LayoutParams(-1, dp(activity, 46));
        trLp.topMargin = dp(activity, 2);
        remote.addView(textRow, trLp);
        remoteLayout.setSlideRegion(textRow);

        EditText textInput = new LiveTvEditor(activity);
        textInput.setHint(ar ? "اكتب مباشرة على التلفاز…" : "Type directly on TV…");
        textInput.setSingleLine(true);
        textInput.setBackground(null);
        textInput.setTextSize(14f);
        textInput.setPadding(dp(activity, 4), 0, dp(activity, 6), 0);
        textInput.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        textInput.setTextColor(0xfff4f4f6);
        textInput.setHintTextColor(0xff9a948c);
        textRow.addView(textInput, new LinearLayout.LayoutParams(0, -1, 1f));
        textInput.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            sendCommand(activity, "SUBMIT_ENTER", "text", "");
            return true;
        });

        // 2. Row 1: Apps Grid | Dual-Color Swipe/Tap [Touchpad <-> D-Pad] | Customizable Shortcut
        LinearLayout row1 = new LinearLayout(activity);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);
        var row1Lp = new LinearLayout.LayoutParams(-1, -2);
        row1Lp.topMargin = dp(activity, 8);
        remote.addView(row1, row1Lp);

        ImageView appsBtn = pillIconButton(activity, R.drawable.ic_remote_apps, 0xff16171b, GOLD, GOLD, 22, 22);
        appsBtn.setContentDescription(activity.getString(R.string.cinema_apps));
        attachInteractiveTouch(appsBtn, () -> sendCommand(activity, "TV_APPS", "query", ""), null);
        row1.addView(appsBtn, new LinearLayout.LayoutParams(dp(activity, 58), dp(activity, 42)));

        // Swipeable + Tappable Dual-Color Toggle Capsule (Blue = Touchpad, Amber/Orange = D-Pad)
        int capW = dp(activity, 132);
        int capH = dp(activity, 44);
        int capPad = dp(activity, 3);
        int thumbW = (capW - capPad * 2) / 2;

        FrameLayout modeCapsule = new FrameLayout(activity);
        modeCapsule.setPadding(capPad, capPad, capPad, capPad);
        modeCapsule.setBackground(pillDrawable(0xff16171b, TOUCH_ACCENT, 22, activity));
        var capLp = new LinearLayout.LayoutParams(capW, capH);
        capLp.setMarginStart(dp(activity, 14));
        capLp.setMarginEnd(dp(activity, 14));
        row1.addView(modeCapsule, capLp);

        View sliderThumb = new View(activity);
        sliderThumb.setBackground(pillDrawable(TOUCH_ACCENT, TOUCH_ACCENT, 19, activity));
        modeCapsule.addView(sliderThumb, new FrameLayout.LayoutParams(thumbW, -1, Gravity.START));

        LinearLayout capIcons = new LinearLayout(activity);
        capIcons.setOrientation(LinearLayout.HORIZONTAL);
        modeCapsule.addView(capIcons, new FrameLayout.LayoutParams(-1, -1));

        ImageView touchModeBtn = new ImageView(activity);
        touchModeBtn.setImageResource(R.drawable.ic_remote_touchpad);
        touchModeBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        touchModeBtn.setPadding(dp(activity, 12), dp(activity, 7), dp(activity, 12), dp(activity, 7));
        capIcons.addView(touchModeBtn, new LinearLayout.LayoutParams(0, -1, 1f));

        ImageView dpadModeBtn = new ImageView(activity);
        dpadModeBtn.setImageResource(R.drawable.ic_remote_dpad);
        dpadModeBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        dpadModeBtn.setPadding(dp(activity, 12), dp(activity, 7), dp(activity, 12), dp(activity, 7));
        capIcons.addView(dpadModeBtn, new LinearLayout.LayoutParams(0, -1, 1f));

        // Customizable Long-Press Shortcut Button
        final ShortcutOption[] currentShortcut = {resolveShortcut(prefs)};
        ImageView customBtn = pillIconButton(activity, currentShortcut[0].iconRes, 0xff16171b, GOLD, GOLD, 22, 22);
        customBtn.setContentDescription(ar ? currentShortcut[0].arLabel : currentShortcut[0].enLabel);
        attachInteractiveTouch(customBtn,
                () -> sendKey(activity, currentShortcut[0].keyCode),
                () -> showShortcutPicker(activity, prefs, chosen -> {
                    currentShortcut[0] = chosen;
                    customBtn.setImageResource(chosen.iconRes);
                    customBtn.setContentDescription(ar ? chosen.arLabel : chosen.enLabel);
                }));
        row1.addView(customBtn, new LinearLayout.LayoutParams(dp(activity, 58), dp(activity, 42)));

        // 3. Row 2: Rewind -10s | Wide Solid Gold Play/Pause | Fast Forward +10s
        LinearLayout row2 = new LinearLayout(activity);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);
        var row2Lp = new LinearLayout.LayoutParams(-1, -2);
        row2Lp.topMargin = dp(activity, 8);
        remote.addView(row2, row2Lp);

        ImageView rewBtn = pillIconButton(activity, R.drawable.ic_remote_rewind, 0xff16171b, GOLD, GOLD, 22, 22);
        attachRepeatingKey(rewBtn, () -> sendKey(activity, 89));
        row2.addView(rewBtn, new LinearLayout.LayoutParams(dp(activity, 64), dp(activity, 42)));

        ImageView playBtn = pillIconButton(activity, R.drawable.ic_remote_play_pause, GOLD, GOLD, 0xff141517, 26, 22);
        playBtn.setContentDescription(activity.getString(R.string.cinema_play_pause));
        attachInteractiveTouch(playBtn, () -> sendKey(activity, 85), null);
        var playLp = new LinearLayout.LayoutParams(dp(activity, 128), dp(activity, 44));
        playLp.setMarginStart(dp(activity, 14));
        playLp.setMarginEnd(dp(activity, 14));
        row2.addView(playBtn, playLp);

        ImageView fwdBtn = pillIconButton(activity, R.drawable.ic_remote_forward, 0xff16171b, GOLD, GOLD, 22, 22);
        attachRepeatingKey(fwdBtn, () -> sendKey(activity, 90));
        row2.addView(fwdBtn, new LinearLayout.LayoutParams(dp(activity, 64), dp(activity, 42)));

        // 5. Main flexible Touchpad (Blue border) / Circular D-Pad (Warm Orange/Amber) Container
        final RemoteJoystick[] joystickRef = new RemoteJoystick[1];
        joystickRef[0] = new RemoteJoystick(activity, key -> {
            if (joystickRef[0].isPointer()) sendPointer(activity, "click", 0, 0);
            else if (key == -23) sendCommand(activity, "KEY_HOLD", "key", 23);
            else sendCommand(activity, "KEY", "key", key);
        });
        joystickRef[0].setOnTouchpadSeekListener(deltaMs -> sendCommand(activity, "TOUCHPAD_SEEK", "ms", deltaMs));
        joystickRef[0].setEnabled(true);
        String speedKey = "touchpad_repeat_speed";
        String speedTitle = ar ? "سرعة التاتش باد" : "Touchpad speed";
        String[] speeds = ar ? new String[]{"هادئة", "متوسطة", "سريعة"}
                : new String[]{"Slow", "Medium", "Fast"};
        int[] chosenSpeed = {Math.max(0, Math.min(2, prefs.getInt(speedKey, 0)))};
        Runnable updateSpeed = () -> {
            int speed = chosenSpeed[0];
            joystickRef[0].setTouchpadSpeed(speed);
            speedButton.setText((speed + 1) + "×");
            speedButton.setContentDescription(speedTitle + " · " + speeds[speed]);
            speedButton.setTooltipText(speedTitle);
        };
        updateSpeed.run();
        speedButton.setOnClickListener(v -> {
            android.widget.PopupMenu menu = new android.widget.PopupMenu(activity, speedButton);
            for (int i = 0; i < speeds.length; i++) {
                menu.getMenu().add(1, i, i, speeds[i]).setCheckable(true);
            }
            menu.getMenu().setGroupCheckable(1, true, true);
            menu.getMenu().findItem(chosenSpeed[0]).setChecked(true);
            menu.setOnMenuItemClickListener(item -> {
                chosenSpeed[0] = item.getItemId();
                prefs.edit().putInt(speedKey, chosenSpeed[0]).apply();
                updateSpeed.run();
                return true;
            });
            menu.show();
        });

        FrameLayout padHost = new FrameLayout(activity);
        var stickLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        stickLp.topMargin = dp(activity, 10);
        stickLp.bottomMargin = dp(activity, 10);
        remote.addView(padHost, stickLp);

        padHost.addView(joystickRef[0], new FrameLayout.LayoutParams(-1, -1));

        ImageView pointerBtn = pillIconButton(activity, R.drawable.ic_remote_mouse, 0xff222328, 0x55f4f4f6, 0xfff4f4f6, 20, 10);
        pointerBtn.setContentDescription(ar ? "تفعيل مؤشر الفأرة الهوائي" : "Toggle air mouse pointer");
        attachInteractiveTouch(pointerBtn, () -> {
            boolean enabled = !joystickRef[0].isPointer();
            joystickRef[0].setPointer(enabled, (dx, dy) -> sendPointer(activity, "move", dx, dy));
            pointerBtn.setBackground(pillDrawable(enabled ? TOUCH_ACCENT : 0xff222328,
                    enabled ? TOUCH_ACCENT : 0x55f4f4f6, 10, activity));
            pointerBtn.setColorFilter(enabled ? 0xffffffff : 0xfff4f4f6);
            sendPointer(activity, enabled ? "show" : "hide", 0, 0);
        }, null);
        var ptrLp = new FrameLayout.LayoutParams(dp(activity, 40), dp(activity, 40), Gravity.TOP | Gravity.END);
        ptrLp.topMargin = dp(activity, 10);
        ptrLp.setMarginEnd(dp(activity, 10));
        padHost.addView(pointerBtn, ptrLp);

        // 6. Bottom Navigation Row (44dp height + 12dp bottom margin): Menu (82) | Home (3) | Back (4)
        LinearLayout bottomRow = new LinearLayout(activity);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER);
        var botLp = new LinearLayout.LayoutParams(-1, dp(activity, 44));
        botLp.bottomMargin = dp(activity, 12);
        remote.addView(bottomRow, botLp);

        ImageView menuBtn = pillIconButton(activity, R.drawable.ic_remote_menu, 0xff16171b, GOLD, GOLD, 22, 22);
        attachInteractiveTouch(menuBtn, () -> sendKey(activity, KeyEvent.KEYCODE_MENU), null);
        bottomRow.addView(menuBtn, new LinearLayout.LayoutParams(0, -1, 1f));

        ImageView homeBtn = pillIconButton(activity, R.drawable.ic_remote_home, 0xff16171b, GOLD, GOLD, 22, 22);
        attachInteractiveTouch(homeBtn, () -> sendKey(activity, 3), null);
        var homeLp = new LinearLayout.LayoutParams(0, -1, 1f);
        homeLp.setMarginStart(dp(activity, 10));
        homeLp.setMarginEnd(dp(activity, 10));
        bottomRow.addView(homeBtn, homeLp);

        ImageView backBtn = pillIconButton(activity, R.drawable.ic_remote_back, 0xff16171b, GOLD, GOLD, 22, 22);
        attachInteractiveTouch(backBtn, () -> sendKey(activity, 4), null);
        bottomRow.addView(backBtn, new LinearLayout.LayoutParams(0, -1, 1f));

        java.util.function.Consumer<Boolean> applyMode = touchpad -> {
            if (joystickRef[0].isPointer()) sendPointer(activity, "hide", 0, 0);
            joystickRef[0].setPointer(false, null);
            pointerBtn.setBackground(pillDrawable(0xff222328, 0x55f4f4f6, 10, activity));
            pointerBtn.setColorFilter(0xfff4f4f6);
            pointerBtn.setVisibility(touchpad ? View.VISIBLE : View.GONE);
            joystickRef[0].setTouchpad(touchpad);

            int accent = touchpad ? TOUCH_ACCENT : DPAD_ACCENT;
            modeCapsule.setBackground(pillDrawable(0xff16171b, accent, 22, activity));
            sliderThumb.setBackground(pillDrawable(accent, accent, 19, activity));
            sliderThumb.animate().translationX(touchpad ? 0f : thumbW).setDuration(170).start();
            touchModeBtn.setColorFilter(touchpad ? 0xffffffff : 0xff8ea4c8);
            dpadModeBtn.setColorFilter(!touchpad ? 0xff141517 : 0xffb89b5e);
        };
        applyMode.accept(true);

        // Capsule supports BOTH Tap on either side AND Horizontal Swipe/Slide
        final float[] capDownX = new float[1];
        final float[] capStartTx = new float[1];
        final boolean[] capSwiping = new boolean[1];
        int capSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        modeCapsule.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    capDownX[0] = ev.getX();
                    capStartTx[0] = sliderThumb.getTranslationX();
                    capSwiping[0] = false;
                    modeCapsule.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).start();
                    triggerHaptic(modeCapsule, false);
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    float dx = ev.getX() - capDownX[0];
                    if (Math.abs(dx) > capSlop) capSwiping[0] = true;
                    if (capSwiping[0]) {
                        float nextTx = Math.max(0f, Math.min((float) thumbW, capStartTx[0] + dx));
                        sliderThumb.setTranslationX(nextTx);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    modeCapsule.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                    boolean nextTouchpad;
                    if (capSwiping[0]) {
                        nextTouchpad = sliderThumb.getTranslationX() < thumbW / 2f;
                    } else {
                        nextTouchpad = ev.getX() < v.getWidth() / 2f;
                    }
                    if (nextTouchpad != joystickRef[0].isTouchpad()) {
                        triggerHaptic(modeCapsule, true);
                    }
                    applyMode.accept(nextTouchpad);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    modeCapsule.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                    applyMode.accept(joystickRef[0].isTouchpad());
                    return true;
                }
            }
            return false;
        });

        remoteWindow.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { sendKey(activity, 24); return true; }
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { sendKey(activity, 25); return true; }
            }
            return false;
        });
        remoteWindow.setContentView(remoteLayout);
        android.view.Window window = remoteWindow.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.28f);
        }
        remoteWindow.setOnDismissListener(d -> {
            if (joystickRef[0].isPointer()) sendPointer(activity, "hide", 0, 0);
            activeAdbDot.clear();
            activeAdbLabel.clear();
            activeWebDot.clear();
            activeWebLabel.clear();
        });
        remoteWindow.show();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    private static void setupTransportChip(Activity activity, SharedPreferences prefs, View label,
                                           String transport, int description) {
        View chip = (View) label.getParent();
        chip.setContentDescription(activity.getString(description));
        chip.setTooltipText(activity.getString(description));
        chip.setFocusable(true);
        chip.setOnClickListener(view -> {
            if ("adb".equals(transport) && !CinemaRemoteBridge.isAdbRemoteActive()) {
                CinemaSnackBanner.show(activity, activity.getString(R.string.cinema_remote_adb_unavailable));
                return;
            }
            prefs.edit().putString("preferred_remote_transport", transport).apply();
            CinemaSnackBanner.show(activity, activity.getString(description));
        });
    }

    private static GradientDrawable remoteSurface(Activity activity, int radius, int border) {
        var surface = new GradientDrawable();
        surface.setColor(0xff171717);
        surface.setCornerRadius(dp(activity, radius));
        surface.setStroke(dp(activity, 1), border);
        return surface;
    }

    private static ImageView headerIcon(Activity activity, int icon, int tint, int description) {
        ImageView button = iconButton(activity, icon, tint, 24);
        button.setBackground(remoteSurface(activity, 11, GOLD));
        button.setContentDescription(activity.getString(description));
        button.setTooltipText(activity.getString(description));
        return button;
    }

    private static LinearLayout.LayoutParams headerSize(Activity activity) {
        var size = new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48));
        size.setMarginStart(dp(activity, 4));
        return size;
    }

    private static View[] addStatusLedChip(Activity activity, LinearLayout parent, String label, int activeColor, boolean active) {
        LinearLayout chip = new LinearLayout(activity);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(remoteSurface(activity, 11, GOLD));
        parent.addView(chip, headerSize(activity));

        View dot = new View(activity);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dot.setBackground(dotBg);
        var dLp = new LinearLayout.LayoutParams(dp(activity, 7), dp(activity, 7));
        dLp.setMarginEnd(dp(activity, 5));
        chip.addView(dot, dLp);

        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        chip.addView(tv);

        applyLedState(dot, tv, active, activeColor);
        return new View[]{dot, tv};
    }

    private static void showShortcutPicker(Activity activity, SharedPreferences prefs,
                                           java.util.function.Consumer<ShortcutOption> onPicked) {
        boolean ar = isArabic(activity);
        ShortcutOption[] opts = shortcutOptions();
        String current = prefs.getString(KEY_CUSTOM_SHORTCUT, "source");

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff1a1b20);
        bg.setCornerRadius(dp(activity, 18));
        bg.setStroke(dp(activity, 1), GOLD);
        box.setBackground(bg);

        TextView head = new TextView(activity);
        head.setText(ar ? "تخصيص زر الاختصار السريع" : "Customize Shortcut Button");
        head.setTextColor(GOLD);
        head.setTextSize(16);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setGravity(ar ? Gravity.RIGHT : Gravity.LEFT);
        var headLp = new LinearLayout.LayoutParams(-1, -2);
        headLp.bottomMargin = dp(activity, 10);
        box.addView(head, headLp);

        AlertDialog dialog = new AlertDialog.Builder(activity).setView(box).create();
        for (ShortcutOption opt : opts) {
            boolean selected = opt.id.equals(current);
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
            row.setBackground(pillDrawable(selected ? 0xff282418 : 0xff222329, selected ? GOLD : 0x33ffffff, 12, activity));

            ImageView iv = new ImageView(activity);
            iv.setImageResource(opt.iconRes);
            iv.setColorFilter(selected ? GOLD : 0xfff4f4f6);
            var ivLp = new LinearLayout.LayoutParams(dp(activity, 20), dp(activity, 20));
            ivLp.setMarginEnd(dp(activity, 12));
            row.addView(iv, ivLp);

            TextView tv = new TextView(activity);
            tv.setText((selected ? "✓  " : "") + (ar ? opt.arLabel : opt.enLabel));
            tv.setTextColor(selected ? GOLD : 0xfff4f4f6);
            tv.setTextSize(14);
            row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));

            attachInteractiveTouch(row, () -> {
                prefs.edit().putString(KEY_CUSTOM_SHORTCUT, opt.id).apply();
                onPicked.accept(opt);
                dialog.dismiss();
            }, null);

            var rLp = new LinearLayout.LayoutParams(-1, -2);
            rLp.bottomMargin = dp(activity, 8);
            box.addView(row, rLp);
        }

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
    }

    private static final class LiveTvEditor extends EditText {
        private int tvCursor;
        private boolean changing;
        private final Runnable syncCursor = () -> {
            int cursor = getSelectionEnd();
            if (!changing && isAttachedToWindow() && cursor >= 0 && cursor != tvCursor
                    && sendEdit(cursor - tvCursor, 0, "")) tvCursor = cursor;
        };

        LiveTvEditor(Context context) {
            super(context);
            addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {
                    changing = true;
                    removeCallbacks(syncCursor);
                }
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                    String insert = value.subSequence(start, start + count).toString();
                    if (sendEdit(start - tvCursor, before, insert)) tvCursor = start + count;
                }
                @Override public void afterTextChanged(android.text.Editable value) {
                    changing = false;
                    post(syncCursor);
                }
            });
        }

        @Override protected void onSelectionChanged(int start, int end) {
            super.onSelectionChanged(start, end);
            if (syncCursor != null && !changing) {
                removeCallbacks(syncCursor);
                post(syncCursor);
            }
        }

        @Override public boolean onKeyDown(int key, KeyEvent event) {
            if (key == KeyEvent.KEYCODE_DEL && getSelectionStart() == 0 && getSelectionEnd() == 0) {
                sendCommand(getContext(), "KEY", "key", KeyEvent.KEYCODE_DEL);
                return true;
            }
            return super.onKeyDown(key, event);
        }

        @Override public android.view.inputmethod.InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            var connection = super.onCreateInputConnection(outAttrs);
            if (connection == null) return null;
            return new android.view.inputmethod.InputConnectionWrapper(connection, false) {
                private boolean deleteAtStart(int before, int after) {
                    if (before != 1 || after != 0 || getSelectionStart() != 0 || getSelectionEnd() != 0) return false;
                    sendCommand(getContext(), "KEY", "key", KeyEvent.KEYCODE_DEL);
                    return true;
                }
                @Override public boolean deleteSurroundingText(int before, int after) {
                    return deleteAtStart(before, after) || super.deleteSurroundingText(before, after);
                }
                @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) {
                    return deleteAtStart(before, after) || super.deleteSurroundingTextInCodePoints(before, after);
                }
            };
        }

        private boolean sendEdit(int move, int delete, String insert) {
            WebSocketClient socket = activeSocket();
            if (socket == null) {
                ensureRemoteSocketConnected(getContext());
                return false;
            }
            try {
                socket.send(new JSONObject().put("type", "EDIT_TEXT").put("move", move)
                        .put("delete", delete).put("text", insert).toString());
                return true;
            } catch (Exception unavailable) { return false; }
        }
    }

    private static void attachRepeatingKey(View target, Runnable key) {
        Runnable repeat = new Runnable() {
            @Override public void run() {
                if (!target.isAttachedToWindow() || !target.isPressed()) return;
                key.run();
                target.postDelayed(this, 230);
            }
        };
        target.setOnClickListener(v -> key.run());
        target.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    v.setPressed(true);
                    triggerHaptic(v, false);
                    key.run();
                    v.postDelayed(repeat, 420);
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (e.getX() < 0 || e.getX() > v.getWidth() || e.getY() < 0 || e.getY() > v.getHeight()) {
                        v.setPressed(false);
                        v.removeCallbacks(repeat);
                    }
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.setPressed(false);
                    v.removeCallbacks(repeat);
                }
            }
            return true;
        });
    }

    private static void attachInteractiveTouch(View target, Runnable onClick, Runnable onLongClick) {
        final int slop = ViewConfiguration.get(target.getContext()).getScaledTouchSlop();
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] longTriggered = new boolean[1];
        final float[] downXY = new float[2];
        final Runnable longTask = () -> {
            if (onLongClick != null) {
                longTriggered[0] = true;
                triggerHaptic(target, true);
                target.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                onLongClick.run();
            }
        };
        target.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    longTriggered[0] = false;
                    downXY[0] = ev.getX();
                    downXY[1] = ev.getY();
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(65).start();
                    triggerHaptic(v, false);
                    if (onLongClick != null) {
                        handler.postDelayed(longTask, ViewConfiguration.getLongPressTimeout());
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (Math.hypot(ev.getX() - downXY[0], ev.getY() - downXY[1]) > slop) {
                        handler.removeCallbacks(longTask);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longTask);
                    v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                    if (!longTriggered[0] && ev.getX() >= -slop && ev.getY() >= -slop
                            && ev.getX() <= v.getWidth() + slop && ev.getY() <= v.getHeight() + slop) {
                        if (onClick != null) onClick.run();
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longTask);
                    v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                    return true;
                }
            }
            return false;
        });
    }

    private static void triggerHaptic(View v, boolean strong) {
        if (v == null) return;
        try {
            v.performHapticFeedback(strong ? HapticFeedbackConstants.LONG_PRESS : HapticFeedbackConstants.KEYBOARD_TAP);
            Vibrator vib = v.getContext().getSystemService(Vibrator.class);
            if (vib != null && vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(strong ? 26 : 18, strong ? 130 : 90));
                } else {
                    vib.vibrate(strong ? 26 : 18);
                }
            }
        } catch (Exception ignored) {}
    }

    private static ImageView iconButton(Context c, int resId, int tint, int iconDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(resId);
        iv.setColorFilter(tint);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = Math.max(dp(c, 6), (dp(c, 40) - dp(c, iconDp)) / 2);
        iv.setPadding(pad, pad, pad, pad);
        return iv;
    }

    private static ImageView pillIconButton(Context c, int resId, int fill, int stroke, int tint, int iconDp, int radiusDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(resId);
        iv.setColorFilter(tint);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iv.setBackground(pillDrawable(fill, stroke, radiusDp, c));
        int pad = Math.max(dp(c, 6), (dp(c, 40) - dp(c, iconDp)) / 2);
        iv.setPadding(pad, pad, pad, pad);
        return iv;
    }

    private static GradientDrawable pillDrawable(int fill, int stroke, int radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(Math.round(c.getResources().getDisplayMetrics().density * 1.35f), stroke);
        return d;
    }

    private static boolean isRemoteConnected() {
        if (externalConnectedOverride && externalSocketOverride != null && externalSocketOverride.isOpen()) {
            return true;
        }
        WebSocketClient bridge = CinemaRemoteBridge.getConnectedSocket();
        if (bridge != null && bridge.isOpen()) return true;
        return sharedRemoteConnected && sharedRemoteSocket != null && sharedRemoteSocket.isOpen();
    }

    private static WebSocketClient activeSocket() {
        if (externalConnectedOverride && externalSocketOverride != null && externalSocketOverride.isOpen()) {
            return externalSocketOverride;
        }
        WebSocketClient bridge = CinemaRemoteBridge.getConnectedSocket();
        if (bridge != null && bridge.isOpen()) return bridge;
        if (sharedRemoteConnected && sharedRemoteSocket != null && sharedRemoteSocket.isOpen()) {
            return sharedRemoteSocket;
        }
        return null;
    }

    private static void flushPendingCommand() {
        String pending = pendingJsonCommand;
        if (pending == null) return;
        WebSocketClient ws = activeSocket();
        if (ws != null && ws.isOpen()) {
            pendingJsonCommand = null;
            try { ws.send(pending); } catch (Exception ignored) {}
        }
    }

    private static void ensureRemoteSocketConnected(Context context) {
        if (context == null) return;
        CinemaRemoteBridge.ensurePairedConnection(context);
    }

    static void showQuickTvApps(JSONArray apps) {
        Activity act = currentActivity.get();
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        boolean ar = isArabic(act);
        if (apps == null || apps.length() == 0) {
            CinemaSnackBanner.show(act, ar ? "لا توجد تطبيقات متاحة على الشاشة أو لم يتم الرد بعد" : "No TV apps returned yet");
            return;
        }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(act, 16), dp(act, 14), dp(act, 16), dp(act, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff1a1b20);
        bg.setCornerRadius(dp(act, 18));
        bg.setStroke(dp(act, 1), GOLD);
        box.setBackground(bg);

        TextView head = new TextView(act);
        head.setText(act.getString(R.string.cinema_apps));
        head.setTextColor(GOLD);
        head.setTextSize(16);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        var hLp = new LinearLayout.LayoutParams(-1, -2);
        hLp.bottomMargin = dp(act, 10);
        box.addView(head, hLp);

        android.widget.ScrollView sv = new android.widget.ScrollView(act);
        LinearLayout listCol = new LinearLayout(act);
        listCol.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listCol);
        box.addView(sv, new LinearLayout.LayoutParams(-1, Math.min(dp(act, 420), apps.length() * dp(act, 52))));

        AlertDialog dialog = new AlertDialog.Builder(act).setView(box).create();
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            String label = app != null ? app.optString("label", "") : "";
            String pkg = app != null ? app.optString("package", "") : "";
            if (label.isEmpty() || pkg.isEmpty()) continue;
            TextView item = new TextView(act);
            item.setText(label);
            item.setTextColor(0xfff4f4f6);
            item.setTextSize(14);
            item.setPadding(dp(act, 14), dp(act, 12), dp(act, 14), dp(act, 12));
            item.setBackground(pillDrawable(0xff23242b, 0x33ffffff, 12, act));
            attachInteractiveTouch(item, () -> {
                sendCommand(act, "OPEN_TV_APP", "package", pkg);
                dialog.dismiss();
            }, null);
            var iLp = new LinearLayout.LayoutParams(-1, -2);
            iLp.bottomMargin = dp(act, 8);
            listCol.addView(item, iLp);
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
    }

    private static void sendKey(Context c, int keyCode) {
        sendCommand(c, "KEY", "key", keyCode);
    }

    private static void sendCommand(Context c, String type, String field, Object val) {
        try {
            var command = new JSONObject().put("type", type).put(field, val);
            if ("KEY".equals(type) || "KEY_HOLD".equals(type)) {
                String transport = c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        .getString("preferred_remote_transport", "");
                if (!transport.isEmpty()) command.put("remote_transport", transport);
            }
            String json = command.toString();
            WebSocketClient ws = activeSocket();
            if (ws == null) {
                pendingJsonCommand = json;
                ensureRemoteSocketConnected(c);
                return;
            }
            ws.send(json);
        } catch (Exception ignored) {}
    }

    private static void sendPointer(Context c, String action, double dx, double dy) {
        WebSocketClient ws = activeSocket();
        if (ws == null) {
            ensureRemoteSocketConnected(c);
            return;
        }
        try {
            ws.send(new JSONObject().put("type", "REMOTE_POINTER")
                    .put("action", action).put("dx", dx).put("dy", dy).toString());
        } catch (Exception ignored) {}
    }

    private static boolean isArabic(Context c) {
        var locale = ConfigurationCompat.getLocales(c.getResources().getConfiguration()).get(0);
        return locale != null && "ar".equals(locale.getLanguage());
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}

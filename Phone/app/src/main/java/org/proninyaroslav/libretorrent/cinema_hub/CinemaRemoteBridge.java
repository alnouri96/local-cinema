package org.proninyaroslav.libretorrent.cinema_hub;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.os.ConfigurationCompat;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.net.URI;
import java.util.UUID;

/**
 * Shared Phone <-> TV remote control & playback state bridge.
 * Coordinates hardware Volume +/- keys across ALL Phone screens,
 * background notification transport buttons, and TV WebSocket state.
 */
public final class CinemaRemoteBridge {
    private static final int GOLD = 0xffe5b94c;
    private static final String TAG_VOL_HUD = "cinema_remote_volume_hud";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<TvHubActivity> hubRef = new WeakReference<>(null);
    private static volatile WebSocketClient hubSocket;
    private static volatile boolean hubConnected;
    private static volatile WebSocketClient bgSocket;
    private static volatile boolean bgConnected;
    private static volatile boolean adbRemoteActive;
    private static volatile boolean webRemoteActive;
    private static Runnable bgDeadline;
    private static Runnable bgRetry;
    private static volatile boolean bgReconnecting;

    static boolean isReconnecting() {
        TvHubActivity hub = hubRef.get();
        return bgReconnecting || (hub != null && hub.isReconnecting());
    }

    static void onRemoteUiPaused() {
        MAIN.postDelayed(() -> {
            if (FloatingTvDpadController.currentForegroundActivity() == null) enterIdleSleep();
        }, 800);
    }

    private static volatile int volumePercent = 30;
    private static volatile boolean volumeMuted = false;
    private static volatile boolean volumeAvailable = false;
    private static volatile long volumeRequestedAt = 0L;

    private static volatile boolean castActive = false;
    private static boolean backgroundSawVideo;
    private static volatile String castKind = "";
    private static volatile String castTitle = "";
    private static volatile String castSubtitle = "";
    private static volatile boolean castPlaying = false;
    private static volatile long castPosMs = 0L;
    private static volatile long castDurMs = 0L;
    private static volatile Bitmap castArt = null;

    private static WeakReference<FrameLayout> activeHudHost = new WeakReference<>(null);
    private static Runnable hideHudTask;
    private static final java.util.Set<Runnable> playbackListeners = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public static void addPlaybackListener(Runnable r) {
        if (r != null) playbackListeners.add(r);
    }

    public static void removePlaybackListener(Runnable r) {
        if (r != null) playbackListeners.remove(r);
    }

    private static void notifyPlaybackListeners() {
        MAIN.post(() -> {
            for (Runnable r : java.util.List.copyOf(playbackListeners)) {
                try { r.run(); } catch (Exception ignored) {}
            }
        });
    }

    private CinemaRemoteBridge() {}

    public static void attachHub(TvHubActivity hub) {
        hubRef = new WeakReference<>(hub);
    }

    public static void detachHub(TvHubActivity hub) {
        if (hubRef.get() == hub) {
            hubRef = new WeakReference<>(null);
        }
    }

    public static void updateConnection(WebSocketClient ws, boolean isConnected) {
        hubSocket = ws;
        hubConnected = isConnected && ws != null && ws.isOpen();
        if (hubConnected && bgSocket != null && bgSocket != ws) {
            try { bgSocket.close(); } catch (Exception ignored) {}
            bgSocket = null;
            bgConnected = false;
        }
        if (!hubConnected && !bgConnected) {
            adbRemoteActive = false;
            webRemoteActive = false;
        }
        FloatingTvDpadController.onRemoteConnectionChanged();
    }

    public static void updateRemoteCapabilities(boolean adbActive, boolean webActive) {
        adbRemoteActive = adbActive;
        webRemoteActive = webActive;
        FloatingTvDpadController.onRemoteConnectionChanged();
    }

    public static boolean isAdbRemoteActive() {
        return adbRemoteActive && isTvControlReady();
    }

    public static boolean isWebRemoteActive() {
        return webRemoteActive && isTvControlReady();
    }

    public static void updateVolumeState(int percent, boolean muted, boolean available) {
        volumeAvailable = available;
        if (percent >= 0 && SystemClock.elapsedRealtime() - volumeRequestedAt > 300) {
            volumePercent = Math.max(0, Math.min(100, percent));
            volumeMuted = muted;
        }
    }

    public static void updatePlaybackState(boolean active, String kind, String title, String subtitle,
                                           boolean playing, long posMs, long durMs, Bitmap art) {
        castActive = active;
        castKind = kind == null ? "" : kind;
        castTitle = title == null ? "" : title;
        castSubtitle = subtitle == null ? "" : subtitle;
        castPlaying = playing;
        castPosMs = Math.max(0L, posMs);
        castDurMs = Math.max(0L, durMs);
        if (art != null) castArt = art;
        if (!active) castArt = null;
        notifyPlaybackListeners();
    }

    public static boolean isCastActive() { return castActive; }
    public static String getCastKind() { return castKind; }
    public static String getCastTitle() { return castTitle; }
    public static String getCastSubtitle() { return castSubtitle; }
    public static boolean isCastPlaying() { return castPlaying; }
    public static long getCastPosMs() { return castPosMs; }
    public static long getCastDurMs() { return castDurMs; }
    public static Bitmap getCastArt() { return castArt; }
    public static int getVolumePercent() { return volumeMuted ? 0 : volumePercent; }

    static android.net.Network pairingWifi(Context context, java.net.InetAddress target) {
        var manager = context.getSystemService(android.net.ConnectivityManager.class);
        if (manager == null) return null;
        android.net.Network fallback = null;
        for (var network : manager.getAllNetworks()) {
            var capabilities = manager.getNetworkCapabilities(network);
            if (capabilities == null || !capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (fallback == null || network.equals(manager.getActiveNetwork())) fallback = network;
            var properties = manager.getLinkProperties(network);
            if (properties == null) continue;
            for (var address : properties.getLinkAddresses()) {
                if (address.getAddress() instanceof java.net.Inet4Address
                        && new android.net.IpPrefix(address.getAddress(), address.getPrefixLength()).contains(target))
                    return network;
            }
        }
        return fallback;
    }

    public static WebSocketClient getConnectedSocket() {
        if (hubConnected && hubSocket != null && hubSocket.isOpen()) return hubSocket;
        if (bgConnected && bgSocket != null && bgSocket.isOpen()) return bgSocket;
        return null;
    }

    public static boolean isTvControlReady() {
        return (hubConnected && hubSocket != null && hubSocket.isOpen())
                || (bgConnected && bgSocket != null && bgSocket.isOpen())
                || castActive;
    }

    /** Intercepts physical Volume +/- buttons on ANY Activity when TV is connected or casting. */
    public static boolean dispatchHardwareVolumeKey(Activity activity, KeyEvent event) {
        if (activity == null || event == null) return false;
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }
        boolean hasPairing = activity.getSharedPreferences("cinema_link", Context.MODE_PRIVATE)
                .getString("pairing", null) != null;
        if (!hasPairing || (!isTvControlReady() && !castActive)) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            adjustTvVolume(activity, code == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1, activity);
        }
        return true;
    }

    public static void adjustTvVolume(Context context, int direction, Activity hudActivity) {
        if (direction == 0) {
            if (hudActivity != null) showVolumeOverlay(hudActivity, getVolumePercent());
            return;
        }
        TvHubActivity hub = hubRef.get();
        if (hub != null && !hub.isDestroyed()) {
            hub.adjustTvVolumeFromBridge(Integer.signum(direction));
            if (hudActivity != null && hudActivity != hub) {
                showVolumeOverlay(hudActivity, getVolumePercent());
            }
            return;
        }
        volumePercent = Math.max(0, Math.min(100, (volumeMuted ? 0 : volumePercent) + Integer.signum(direction) * 5));
        volumeMuted = volumePercent == 0;
        volumeRequestedAt = SystemClock.elapsedRealtime();

        WebSocketClient ws = activeSocket(context);
            if (ws != null && ws.isOpen()) {
                try {
                    if (volumeAvailable) {
                        ws.send(new JSONObject().put("type", "SET_VOLUME")
                                .put("percentage", volumePercent)
                                .put("muted", volumeMuted).toString());
                    } else {
                        ws.send(new JSONObject().put("type", "KEY")
                                .put("key", direction > 0 ? 24 : 25).toString());
                    }
                    ws.send(new JSONObject().put("type", "PLAYBACK").put("ms", 0).toString());
                } catch (Exception ignored) {}
            }
        if (hudActivity != null) {
            showVolumeOverlay(hudActivity, volumePercent);
        }
        MediaPlaybackNotificationService.syncFromBridge(context);
    }

    public static void sendSeek(Context context, long positionMs) {
        TvHubActivity hub = hubRef.get();
        if (hub != null && !hub.isDestroyed()) {
            hub.commandFromBridge("SEEK", "ms", positionMs);
            return;
        }
        sendJson(context, new JSONObject(), obj -> {
            obj.put("type", "SEEK");
            obj.put("ms", positionMs);
        });
    }

    public static void handleNotificationAction(Context context, String action) {
        if (action == null) return;
        TvHubActivity hub = hubRef.get();
        if (hub != null && !hub.isDestroyed()) {
            MAIN.post(() -> hub.handleNotificationBridgeAction(action));
            return;
        }
        switch (action) {
            case MediaPlaybackNotificationService.ACTION_TOGGLE -> {
                castPlaying = !castPlaying;
                sendJson(context, new JSONObject(), obj -> {
                    obj.put("type", "TOGGLE_PLAY");
                    obj.put("ms", 0);
                });
                MediaPlaybackNotificationService.syncFromBridge(context);
            }
            case MediaPlaybackNotificationService.ACTION_PREV -> sendJson(context, new JSONObject(), obj -> {
                obj.put("type", "JUMP");
                obj.put("ms", -10000);
            });
            case MediaPlaybackNotificationService.ACTION_NEXT -> sendJson(context, new JSONObject(), obj -> {
                obj.put("type", "JUMP");
                obj.put("ms", 10000);
            });
            case MediaPlaybackNotificationService.ACTION_STOP -> {
                castActive = false;
                castPlaying = false;
                sendJson(context, new JSONObject(), obj -> {
                    obj.put("type", "STOP_CAST");
                    obj.put("ms", 0);
                });
                MediaPlaybackNotificationService.stop(context);
            }
        }
    }

    public static void enterIdleSleep() {
        if (castActive) return;
        cancelBgCallbacks();
        bgReconnecting = false;
        WebSocketClient ws = bgSocket;
        bgSocket = null;
        bgConnected = false;
        if (ws != null) {
            try {
                if (ws.isOpen()) ws.close(1000, "Idle sleep");
                else if (ws.getSocket() != null) ws.getSocket().close();
            } catch (Exception ignored) {}
        }
        if (!hubConnected) {
            adbRemoteActive = false;
            webRemoteActive = false;
        }
        FloatingTvDpadController.onRemoteConnectionChanged();
    }

    private static boolean isDeviceScreenLockedOrOff(Context context) {
        if (context == null) return false;
        android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return true;
        android.app.KeyguardManager km = (android.app.KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    public static void ensurePairedConnection(Context context) {
        if (context == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> ensurePairedConnection(context));
            return;
        }
        if (!castActive && isDeviceScreenLockedOrOff(context)) return;
        TvHubActivity hub = hubRef.get();
        if (hub != null && !hub.isDestroyed() && !hub.isFinishing()) {
            hub.reconnectIfNeededOnForeground();
            return;
        }
        if (bgReconnecting || bgDeadline != null) return;
        if (bgConnected && bgSocket != null && bgSocket.isOpen()) {
            WebSocketClient current = bgSocket;
            bgDeadline = () -> onBgConnectionFailed(context, current, 0);
            MAIN.postDelayed(bgDeadline, 1800);
            try { current.send("{\"type\":\"PLAYBACK\"}"); }
            catch (RuntimeException e) { onBgConnectionFailed(context, current, 0); }
            return;
        }
        connectBackground(context.getApplicationContext(), 0);
    }

    private static void connectBackground(Context context, int attempt) {
        if (!castActive && (isDeviceScreenLockedOrOff(context) || FloatingTvDpadController.currentForegroundActivity() == null)) {
            enterIdleSleep();
            return;
        }
        try {
            SharedPreferences sp = context.getSharedPreferences("cinema_link", Context.MODE_PRIVATE);
            String raw = sp.getString("pairing", null);
            if (raw == null || raw.isBlank()) return;
            JSONObject cfg = new JSONObject(raw);
            String host = cfg.optString("ip", cfg.optString("tv_ip", cfg.optString("host", "")));
            int port = cfg.optInt("port", cfg.optInt("ws_port", 8765));
            String token = cfg.optString("token", "");
            if (host.isEmpty() || token.isEmpty()) return;
            String phoneId = sp.getString("phone_id", null);
            if (phoneId == null) {
                phoneId = UUID.randomUUID().toString();
                sp.edit().putString("phone_id", phoneId).apply();
            }
            final String fid = phoneId;
            final Context appContext = context.getApplicationContext();
            WebSocketClient client = new WebSocketClient(URI.create("ws://" + host + ":" + port), new Draft_6455(), null, 4000) {
                @Override public void onOpen(ServerHandshake handshake) {
                    try {
                        String model = android.os.Build.MODEL;
                        String name = model.startsWith("SM-S938") ? "Samsung Galaxy S25 Ultra" : model;
                        send(new JSONObject().put("type", "REGISTER_PHONE")
                                .put("token", token)
                                .put("phone_id", fid)
                                .put("phone_name", name)
                                .put("scanned", false)
                                .put("lightweight_reconnect", true).toString());
                    } catch (Exception ignored) {}
                }
                @Override public void onMessage(String message) {
                    MAIN.post(() -> {
                    if (bgSocket != this) return;
                    try {
                        JSONObject msg = new JSONObject(message);
                        String t = msg.optString("type");
                        if ("CONNECTED".equals(t)) {
                            bgConnected = true;
                            bgReconnecting = false;
                            cancelBgCallbacks();
                            updateRemoteCapabilities(
                                    msg.optBoolean("adb_enabled", false),
                                    msg.optBoolean("web_remote_active", true));
                            send(new JSONObject().put("type", "PLAYBACK").put("ms", 0).toString());
                            LocalCinemaBackupManager.onTvHandshakeConnected(
                                    appContext,
                                    this,
                                    msg.optLong("tv_settings_updated_at", 0L),
                                    msg.optString("tv_settings_hash", ""));
                        } else if ("REMOTE_STATUS".equals(t)) {
                            updateRemoteCapabilities(
                                    msg.optBoolean("adb_enabled", false),
                                    msg.optBoolean("web_remote_active", true));
                        } else if ("TV_SETTINGS_CHANGED".equals(t)) {
                            LocalCinemaBackupManager.onTvSettingsChangedNotification(
                                    appContext,
                                    this,
                                    msg.optLong("tv_settings_updated_at", 0L),
                                    msg.optString("tv_settings_hash", ""));
                        } else if ("PLAYBACK".equals(t)) {
                            cancelBgCallbacks();
                            int vol = msg.optInt("volume", -1);
                            boolean avail = vol >= 0 && !msg.optBoolean("volumeFixed");
                            if (avail) updateVolumeState(vol, msg.optBoolean("muted"), true);
                            if (!hubConnected) {
                                boolean video = msg.optBoolean("active") && !msg.optBoolean("audio")
                                        && !msg.optBoolean("photo");
                                if (video) {
                                    backgroundSawVideo = true;
                                    String title = msg.optString("title");
                                    if (title.isBlank()) title = "Video on TV";
                                    boolean playing = msg.optBoolean("isPlaying", msg.optBoolean("playing"));
                                    boolean changed = !"video".equals(castKind) || !title.equals(castTitle)
                                            || playing != castPlaying || castDurMs != msg.optLong("durationMs");
                                    updatePlaybackState(true, "video", title, "", playing,
                                            msg.optLong("positionMs", msg.optLong("ms")),
                                            msg.optLong("durationMs", msg.optLong("duration")), null);
                                    if (changed) MediaPlaybackNotificationService.syncFromBridge(appContext);
                                } else if (!msg.optBoolean("audio") && !msg.optBoolean("photo")
                                        && backgroundSawVideo && "video".equals(castKind)) {
                                    backgroundSawVideo = false;
                                    updatePlaybackState(false, "", "", "", false, 0, 0, null);
                                    MediaPlaybackNotificationService.stop(appContext);
                                }
                            }

                        } else if ("TV_APPS".equals(t)) {
                            org.json.JSONArray apps = msg.optJSONArray("apps");
                            MAIN.post(() -> FloatingTvDpadController.showQuickTvApps(apps));
                        } else if ("REQUEST_PHONE_SUBTITLE".equals(t)) {
                            String titleHint = msg.optString("title", "");
                            MAIN.post(() -> {
                                android.content.Intent i = new android.content.Intent(appContext, TvHubActivity.class)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                        .putExtra("open_subtitle_picker", true)
                                        .putExtra("subtitle_title", titleHint);
                                appContext.startActivity(i);
                            });
                        } else if ("TV_BACKUP_DATA".equals(t)) {
                            if (!LocalCinemaBackupManager.handleRequestedTvBackup(this, msg)) {
                                LocalCinemaBackupManager.onLiveTvBackupReceived(appContext, msg, true);
                            }
                        } else if ("TV_BACKUP_RESTORED".equals(t) && msg.optBoolean("ok", false)) {
                            LocalCinemaBackupManager.onTvRestoreConfirmed(appContext, msg);
                        }
                    } catch (Exception ignored) {}
                    });
                }
                @Override public void onClose(int code, String reason, boolean remote) {
                    MAIN.post(() -> onBgConnectionFailed(appContext, this, code == 1008 ? 2 : attempt));
                }
                @Override public void onError(Exception ex) {
                    MAIN.post(() -> onBgConnectionFailed(appContext, this, attempt));
                }
            };
            var wifi = pairingWifi(context, java.net.InetAddress.getByName(host));
            if (wifi != null) client.setSocketFactory(wifi.getSocketFactory());
            bgSocket = client;
            bgReconnecting = true;
            FloatingTvDpadController.onRemoteConnectionChanged();
            bgDeadline = () -> onBgConnectionFailed(appContext, client, attempt);
            MAIN.postDelayed(bgDeadline, 3500);
            client.setConnectionLostTimeout(8);
            client.connect();
        } catch (Exception ignored) { enterIdleSleep(); }
    }

    private static void cancelBgCallbacks() {
        if (bgDeadline != null) MAIN.removeCallbacks(bgDeadline);
        if (bgRetry != null) MAIN.removeCallbacks(bgRetry);
        bgDeadline = null;
        bgRetry = null;
    }

    private static void onBgConnectionFailed(Context context, WebSocketClient client, int attempt) {
        if (bgSocket != client) return;
        cancelBgCallbacks();
        bgSocket = null;
        bgConnected = false;
        client.close();
        if (!hubConnected) {
            adbRemoteActive = false;
            webRemoteActive = false;
        }
        bgReconnecting = !hubConnected && attempt < 2 && !isDeviceScreenLockedOrOff(context)
                && FloatingTvDpadController.currentForegroundActivity() != null;
        if (bgReconnecting) {
            bgRetry = () -> connectBackground(context, attempt + 1);
            MAIN.postDelayed(bgRetry, 500L * (attempt + 1));
        }
        FloatingTvDpadController.onRemoteConnectionChanged();
    }

    private interface JsonPopulator {
        void fill(JSONObject o) throws Exception;
    }

    private static void sendJson(Context context, JSONObject base, JsonPopulator pop) {
        WebSocketClient ws = activeSocket(context);
        if (ws != null && ws.isOpen()) {
            try {
                pop.fill(base);
                ws.send(base.toString());
            } catch (Exception ignored) {}
        }
    }

    private static WebSocketClient activeSocket(Context context) {
        if (hubConnected && hubSocket != null && hubSocket.isOpen()) return hubSocket;
        if (bgConnected && bgSocket != null && bgSocket.isOpen()) return bgSocket;
        if (context != null) ensurePairedConnection(context.getApplicationContext());
        return null;
    }

    private static void showVolumeOverlay(Activity activity, int percent) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        MAIN.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout host)) return;
            activeHudHost = new WeakReference<>(host);

            LinearLayout hud = host.findViewWithTag(TAG_VOL_HUD);
            if (hud == null) {
                float d = activity.getResources().getDisplayMetrics().density;
                hud = new LinearLayout(activity);
                hud.setTag(TAG_VOL_HUD);
                hud.setOrientation(LinearLayout.VERTICAL);
                hud.setPadding(Math.round(16 * d), Math.round(10 * d), Math.round(16 * d), Math.round(10 * d));
                hud.setElevation(14 * d);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xf01a1a1f);
                bg.setStroke(Math.max(1, Math.round(d)), GOLD);
                bg.setCornerRadius(16 * d);
                hud.setBackground(bg);

                TextView label = new TextView(activity);
                label.setId(android.R.id.text1);
                label.setTextColor(0xfff4f4f6);
                label.setTextSize(13);
                label.setTypeface(Typeface.DEFAULT_BOLD);
                label.setGravity(Gravity.CENTER);
                hud.addView(label);

                ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
                bar.setId(android.R.id.progress);
                bar.setMax(100);
                bar.setProgressTintList(android.content.res.ColorStateList.valueOf(GOLD));
                var bLp = new LinearLayout.LayoutParams(Math.round(180 * d), Math.round(6 * d));
                bLp.topMargin = Math.round(6 * d);
                hud.addView(bar, bLp);

                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                lp.topMargin = Math.round(52 * d);
                host.addView(hud, lp);
            }

            boolean ar = isArabic(activity);
            TextView tv = hud.findViewById(android.R.id.text1);
            ProgressBar pb = hud.findViewById(android.R.id.progress);
            if (tv != null) {
                tv.setText((ar ? "🔊 صوت الشاشة: " : "🔊 TV Volume: ") + percent + "%");
            }
            if (pb != null) {
                pb.setProgress(percent);
            }
            hud.setVisibility(View.VISIBLE);
            hud.setAlpha(1f);
            if (hideHudTask != null) MAIN.removeCallbacks(hideHudTask);
            final LinearLayout ref = hud;
            hideHudTask = () -> {
                if (ref.getParent() != null) {
                    ref.animate().alpha(0f).setDuration(180).withEndAction(() -> ref.setVisibility(View.GONE)).start();
                }
            };
            MAIN.postDelayed(hideHudTask, 1800);
        });
    }

    private static boolean isArabic(Context c) {
        var locale = ConfigurationCompat.getLocales(c.getResources().getConfiguration()).get(0);
        return locale != null && "ar".equalsIgnoreCase(locale.getLanguage());
    }
}

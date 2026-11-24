package com.archos.mediacenter.video.leanback.cinema;

import com.meedx.providers.ApiKeyStore;
import com.meedx.providers.LiveChannelFavorites;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;
import java.net.InetSocketAddress;

public final class CinemaLinkServer extends WebSocketServer {
    private static volatile CinemaLinkServer instance;
    private static final int PORT_ATTEMPTS = 10;
    private final android.content.SharedPreferences prefs;
    private volatile String token;
    private final int port;
    private final java.io.File prefsCache;
    private volatile boolean ready;
    private volatile boolean failed;
    private final int attempt;
    private final Application application;
    private final Application.ActivityLifecycleCallbacks activityCallbacks;
    public static final String ACTION_WATCH_SYNC_UPDATED = "com.archos.mediacenter.video.WATCH_SYNC_UPDATED";
    public static final String ACTION_REMOTE_STATUS_UPDATED = "com.archos.mediacenter.video.REMOTE_STATUS_UPDATED";
    public static final String ACTION_API_SYNC_UPDATED = "com.archos.mediacenter.video.API_SYNC_UPDATED";
    private static final String PREF_WEB_REMOTE_ENABLED = "web_remote_enabled";
    private static final java.util.Set<String> BACKUP_LINK_OPTIONS = java.util.Set.of(
            "auto_connect", "select_single", "background_at_boot", PREF_WEB_REMOTE_ENABLED);
    private final java.util.concurrent.ExecutorService syncIo = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ExecutorService remoteIo = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile String cachedDescriptorKey = "";
    private volatile CinemaWatchStore.PlaybackDescriptor cachedDescriptor;
    private final java.util.concurrent.ConcurrentHashMap<String, WebSocket> phones = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<WebSocket, String> backupPeers = new java.util.concurrent.ConcurrentHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Activity foreground;
    private java.lang.ref.WeakReference<Activity> pausedPlayer = new java.lang.ref.WeakReference<>(null);
    private WebSocket pendingPlay;
    private static java.lang.ref.WeakReference<Activity> initialActivity = new java.lang.ref.WeakReference<>(null);
    private static java.lang.ref.WeakReference<EditText> dialogInput = new java.lang.ref.WeakReference<>(null);

    public static void registerDialogField(EditText field) {
        dialogInput = new java.lang.ref.WeakReference<>(field);
    }

    private CinemaLinkServer(Application app, int port, int attempt) {
        super(new InetSocketAddress("0.0.0.0", port));
        this.port = port;
        this.attempt = attempt;
        application = app;
        prefsCache = app.getCacheDir();
        libraryContext = app;
        com.meedx.providers.TraktSyncClient.migrateAndCleanCredentials(app);
        setReuseAddr(true);
        prefs = app.getSharedPreferences("cinema_link", 0);
        token = prefs.getString("token", null);
        if (token == null) refreshCode();
        setConnectionLostTimeout(12);
        activityCallbacks = new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {
                foreground = a;
                playbackChanged();
                if (pausedPlayer.get() == a && pendingPlay != null) {
                    WebSocket socket = pendingPlay;
                    pendingPlay = null;
                    main.post(() -> {
                        if (foreground == a && socket.isOpen()) {
                            handleCommand(socket, "{\"type\":\"TOGGLE_PLAY\"}");
                        }
                    });
                }
            }
            public void onActivityPaused(Activity a) {
                if (foreground == a) foreground = null;
                playbackChanged();
                if (!a.isFinishing() && (a instanceof com.archos.mediacenter.video.player.PlayerActivity
                        || a instanceof CinemaLocalMediaActivity)) pausedPlayer = new java.lang.ref.WeakReference<>(a);
            }
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) {}
        };
        app.registerActivityLifecycleCallbacks(activityCallbacks);
    }

    public static synchronized void startFor(Activity activity) {
        initialActivity = new java.lang.ref.WeakReference<>(activity);
        if (instance != null) instance.foreground = activity;
        androidx.core.content.ContextCompat.startForegroundService(activity,
                new android.content.Intent(activity, TvBackgroundConnectionService.class));
    }

    static synchronized void startFor(Application application) {
        if (instance != null) return;
        int port = application.getSharedPreferences("cinema_link", 0).getInt("port", 8765);
        instance = new CinemaLinkServer(application, port, 0);
        instance.foreground = initialActivity.get();
        initialActivity.clear();
        instance.start();
    }

    static boolean backgroundEnabled(android.content.Context context) {
        return context.getSharedPreferences("cinema_link", 0).getBoolean("background_at_boot", true);
    }

    static synchronized void stopServer() {
        var server = instance;
        instance = null;
        if (server != null) server.disposeServer();
    }

    private void disposeServer() {
        ready = false;
        main.removeCallbacksAndMessages(null);
        application.unregisterActivityLifecycleCallbacks(activityCallbacks);
        new Thread(() -> {
            try {
                stop(1500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "cinema-link-shutdown").start();
    }

    private static synchronized void retryBind(CinemaLinkServer server) {
        if (instance != server) return;
        if (server.attempt + 1 >= PORT_ATTEMPTS || server.port == 65535) {
            server.failed = true;
            return;
        }
        server.disposeServer();
        instance = new CinemaLinkServer(server.application, server.port + 1, server.attempt + 1);
        instance.foreground = server.foreground;
        instance.start();
    }

    static String availability() {
        var server = instance;
        if (server != null && server.failed) return "Could not start TV pairing server";
        if (server == null || !server.ready) return "Initializing server…";
        return "Connect the TV to Wi-Fi or Ethernet";
    }

    private boolean canLaunch(WebSocket socket) {
        return socket.isOpen() && (foreground != null || android.os.Build.VERSION.SDK_INT < 29
                || android.provider.Settings.canDrawOverlays(libraryContext)
                || TvRemoteAccessibilityService.available());
    }

    private void launch(android.content.Intent intent) {
        if (foreground != null) foreground.startActivity(intent);
        else if (TvRemoteAccessibilityService.launchApp(intent)) return;
        else libraryContext.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void stopActivePlayerForNewCast() {
        if (foreground instanceof CinemaHlsActivity hls) {
            hls.finish();
        } else if (foreground instanceof com.archos.mediacenter.video.player.PlayerActivity pa) {
            pa.stopAndFinishForNewCast();
        } else if (com.archos.mediacenter.video.player.PlayerService.sPlayerService != null) {
            com.archos.mediacenter.video.player.PlayerService.sPlayerService.stopAndSaveVideoState();
        }
    }

    static void refresh() {
        if (instance != null) instance.refreshCode();
    }

    private void refreshCode() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        token = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
        prefs.edit().putString("token", token).apply();
    }

    public static String payload() throws org.json.JSONException {
        var server = instance;
        if (server == null || !server.ready) return null;
        String host = NetworkUtils.getLocalIpv4Address(server.application);
        if (host == null) return null;
        return new JSONObject().put("app", "LocalCinemaTV").put("tv_name", android.os.Build.MODEL)
                .put("ip", host).put("port", server.getPort()).put("token", server.token).toString();
    }

    public static boolean isServerRunning() {
        CinemaLinkServer s = instance;
        return s != null && s.ready && !s.failed && NetworkUtils.getLocalIpv4Address(s.application) != null;
    }

    public static boolean isWebRemoteEnabled(android.content.Context context) {
        if (context == null) return true;
        return context.getApplicationContext().getSharedPreferences("cinema_link", 0)
                .getBoolean(PREF_WEB_REMOTE_ENABLED, true);
    }

    public static void setWebRemoteEnabled(android.content.Context context, boolean enabled) {
        if (context == null) return;
        android.content.Context appCtx = context.getApplicationContext();
        appCtx.getSharedPreferences("cinema_link", 0).edit().putBoolean(PREF_WEB_REMOTE_ENABLED, enabled).apply();
        broadcastRemoteStatus(appCtx);
    }

    public static void broadcastRemoteStatus(android.content.Context context) {
        if (context != null) {
            try {
                context.sendBroadcast(new android.content.Intent(ACTION_REMOTE_STATUS_UPDATED)
                        .setPackage(context.getPackageName()));
            } catch (Exception ignored) {}
        }
        CinemaLinkServer s = instance;
        if (s == null) return;
        try {
            String msg = new JSONObject()
                    .put("type", "REMOTE_STATUS")
                    .put("accessibility_enabled", TvRemoteAccessibilityService.available())
                    .put("adb_enabled", TvLocalAdbBridge.isReady())
                    .put("web_remote_active", isWebRemoteEnabled(s.libraryContext))
                    .put("remote_tier", TvRemotePermissionKeeper.tierName(s.libraryContext))
                    .toString();
            for (WebSocket ws : s.phones.values()) {
                if (ws != null && ws.isOpen()) ws.send(msg);
            }
        } catch (Exception ignored) {}
    }

    public static boolean hasOnlineController() {
        CinemaLinkServer s = instance;
        if (s == null) return false;
        String activeId = s.prefs.getString("active", "");
        return !activeId.isEmpty() && online(activeId);
    }

    public static boolean requestPhoneSubtitle(String title) {
        try {
            return sendPacketToController(new JSONObject().put("type", "REQUEST_PHONE_SUBTITLE")
                    .put("title", title == null ? "" : title));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean requestPhoneFullSync() {
        try {
            return sendPacketToController(new JSONObject().put("type", "REQUEST_FULL_SYNC"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean sendPacketToController(JSONObject payload) {
        CinemaLinkServer s = instance;
        if (s == null || payload == null) return false;
        String activeId = s.prefs.getString("active", "");
        WebSocket target = activeId.isEmpty() ? null : s.phones.get(activeId);
        if (target == null || !target.isOpen()) {
            for (WebSocket ws : s.phones.values()) {
                if (ws != null && ws.isOpen()) {
                    target = ws;
                    break;
                }
            }
        }
        if (target == null || !target.isOpen()) return false;
        try {
            target.send(payload.toString());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean online(String id) {
        WebSocket socket = instance == null ? null : instance.phones.get(id);
        return socket != null && socket.isOpen();
    }

    static void forget(String id) {
        if (instance == null) return;
        CinemaLinkServer server = instance;
        synchronized (server) {
            server.prefs.edit().remove("phone:" + id).apply();
            server.refreshCode();
            if (id.equals(server.prefs.getString("active", ""))) {
                server.prefs.edit().remove("active").apply();
                server.selectSingle();
            }
            var socket = server.phones.remove(id);
            if (socket != null) socket.close(1008);
        }
    }

    private void selectSingle() {
        String curActive = prefs.getString("active", "");
        if (!curActive.isEmpty() && online(curActive)) return;
        var ids = new java.util.ArrayList<String>();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("phone:")) ids.add(key.substring(6));
        }
        if (ids.size() == 1) {
            prefs.edit().putString("active", ids.get(0)).apply();
            return;
        }
        for (String candidate : phones.keySet()) {
            if (online(candidate)) {
                prefs.edit().putString("active", candidate).apply();
                return;
            }
        }
        if (!ids.isEmpty()) prefs.edit().putString("active", ids.get(0)).apply();
    }

    private boolean sameToken(String expected, String supplied) {
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private synchronized void register(WebSocket socket, JSONObject command) throws org.json.JSONException {
        String supplied = command.optString("token");
        String id = command.optString("phone_id");
        if (!id.matches("[a-f0-9-]{36}")) {
            socket.close(1008);
            return;
        }
        String raw = prefs.getString("phone:" + id, null);
        JSONObject previous = raw == null ? new JSONObject() : new JSONObject(raw);
        boolean known = sameToken(previous.optString("token", "!"), supplied);
        if (command.optBoolean("backup_only")) {
            if (!known) {
                socket.close(1008, "Pairing rejected");
                return;
            }
            backupPeers.put(socket, id);
            socket.setAttachment(id);
            socket.send(new JSONObject().put("type", "CONNECTED").put("backup_only", true).toString());
            return;
        }
        if ((!known && !sameToken(token, supplied))
                || (!command.optBoolean("scanned") && (!known || !prefs.getBoolean("auto_connect", true)))) {
            android.util.Log.w("CinemaLink", "Pairing registration rejected");
            socket.close(1008, "Pairing rejected");
            return;
        }
        String name = command.optString("phone_name", "Cinema Manager").trim();
        if (name.length() > 80) name = name.substring(0, 80);
        var profile = new JSONObject().put("name", name).put("token", supplied)
                .put("last", System.currentTimeMillis());
        prefs.edit().putString("phone:" + id, profile.toString()).apply();
        socket.setAttachment(id);
        var old = phones.put(id, socket);
        if (old != null && old != socket) {
            photoUploads.remove(old);
            cancelAudioUpload(old);
            if (pendingPlay == old) pendingPlay = null;
            // Detach old socket BEFORE closing so its async onClose cannot touch the new session
            // or trigger spurious offline status updates, and safely reap half-open Doze sockets.
            old.setAttachment(null);
            try {
                if (old.isOpen()) {
                    old.close(1000, "Replaced by newer connection");
                } else {
                    old.closeConnection(1000, "Replaced by newer connection");
                }
            } catch (Throwable ignored) {
                try {
                    old.closeConnection(1000, "Replaced by newer connection");
                } catch (Throwable ignored2) {}
            }
            final WebSocket stale = old;
            main.postDelayed(() -> {
                try {
                    if (!stale.isClosed()) {
                        stale.closeConnection(1000, "Stale socket cleanup");
                    }
                } catch (Throwable ignored) {}
            }, 1500);
        }
        String curActive = prefs.getString("active", "");
        if (curActive.isEmpty() || !online(curActive) || command.optBoolean("scanned")) {
            prefs.edit().putString("active", id).apply();
        } else {
            selectSingle();
        }
        boolean lightweightReconnect = command.optBoolean("lightweight_reconnect", false);
        android.util.Log.i("CinemaLink", "Phone registered" + (lightweightReconnect ? " (lightweight reconnect)" : ""));
        TvRemotePermissionKeeper.ensureAccessibilityEnabled(libraryContext);
        TvLocalAdbBridge.reconnectIfEnabledAsync(libraryContext);
        socket.send(new JSONObject().put("type", "CONNECTED").put("name", android.os.Build.MODEL)
                .put("controller", id.equals(prefs.getString("active", "")))
                .put("accessibility_enabled", TvRemoteAccessibilityService.available())
                .put("adb_enabled", TvLocalAdbBridge.isReady())
                .put("web_remote_active", isWebRemoteEnabled(libraryContext))
                .put("remote_tier", TvRemotePermissionKeeper.tierName(libraryContext)).toString());
        broadcastRemoteStatus(libraryContext);
        main.post(() -> sendPlayback(socket));
        syncIo.execute(() -> sendFavoriteChannels(socket, LiveChannelFavorites.snapshot(libraryContext)));
        if (!lightweightReconnect) {
            sendWatchSyncSnapshot(socket);
            sendApiKeysSyncSnapshot(socket);
        }
    }

    private org.json.JSONObject buildApiKeysSyncPayload(android.content.Context context) {
        if (context == null) return null;
        try {
            var obj = new JSONObject().put("type", "API_KEYS_SYNC");
            String tid = com.meedx.providers.ProviderKeys.get(context, com.meedx.providers.ProviderKeys.TRAKT_ID);
            String tsec = com.meedx.providers.ProviderKeys.get(context, com.meedx.providers.ProviderKeys.TRAKT_SECRET);
            String tacc = com.meedx.providers.ProviderKeys.get(context, com.meedx.providers.ProviderKeys.TRAKT_ACCESS_TOKEN);
            String tref = com.meedx.providers.ProviderKeys.get(context, com.meedx.providers.ProviderKeys.TRAKT_REFRESH_TOKEN);
            String texp = com.meedx.providers.ProviderKeys.get(context, com.meedx.providers.ProviderKeys.TRAKT_EXPIRES_AT);
            String tuser = com.meedx.providers.ProviderKeys.get(context, com.meedx.providers.ProviderKeys.TRAKT_USERNAME);
            String tslug = com.meedx.providers.ProviderKeys.get(context, com.meedx.providers.ProviderKeys.TRAKT_SLUG);
            String sub = com.meedx.providers.ProviderKeys.get(context, com.meedx.providers.ProviderKeys.SUBSOURCE);
            if (sub.isBlank()) {
                try {
                    java.util.List<String> keys = new ApiKeyStore(context, "subsource").read();
                    if (keys != null && !keys.isEmpty() && keys.get(0) != null && !keys.get(0).isBlank()) {
                        sub = keys.get(0).trim();
                    }
                } catch (Exception ignored) {}
            }
            String os = com.meedx.providers.ProviderKeys.get(context, com.meedx.providers.ProviderKeys.OPEN_SUBTITLES);
            String subdl = com.meedx.providers.ProviderKeys.get(context, com.meedx.providers.ProviderKeys.SUBDL);
            String tmdb = com.meedx.providers.ProviderKeys.get(context, com.meedx.providers.ProviderKeys.TMDB);

            if (!tid.isBlank()) obj.put("trakt_id", tid);
            if (!tsec.isBlank()) obj.put("trakt_secret", tsec);
            if (!tacc.isBlank()) obj.put("trakt_access_token", tacc);
            if (!tref.isBlank()) obj.put("trakt_refresh_token", tref);
            if (!texp.isBlank()) obj.put("trakt_expires_at", texp);
            if (!tuser.isBlank()) obj.put("trakt_username", tuser);
            if (!tslug.isBlank()) obj.put("trakt_slug", tslug);
            if (!sub.isBlank()) obj.put("subsource_key", sub);
            if (!os.isBlank()) obj.put("opensubtitles_key", os);
            if (!subdl.isBlank()) obj.put("subdl_key", subdl);
            if (!tmdb.isBlank()) obj.put("tmdb_key", tmdb);
            CinemaSubtitleSyncBridge.ensureBootstrapped(context);
            obj.put("subtitle_config", com.meedx.providers.UnifiedSubtitleConfig.exportSyncJson(context));
            return obj.length() > 1 ? obj : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void sendApiKeysSyncSnapshot(WebSocket socket) {
        if (socket == null || !socket.isOpen() || libraryContext == null) return;
        syncIo.execute(() -> {
            try {
                JSONObject obj = buildApiKeysSyncPayload(libraryContext);
                if (obj != null && socket.isOpen()) {
                    socket.send(obj.toString());
                }
            } catch (Exception ignored) {}
        });
    }

    public static boolean hasConnectedPhone() {
        CinemaLinkServer server = instance;
        if (server == null) return false;
        for (WebSocket ws : server.phones.values()) {
            if (ws != null && ws.isOpen()) return true;
        }
        return false;
    }

    public static void broadcastApiKeys(android.content.Context context) {
        broadcastApiKeysSync(context);
    }

    public static void broadcastSubtitleConfig(android.content.Context context) {
        CinemaLinkServer server = instance;
        if (server == null || context == null) return;
        android.content.Context app = context.getApplicationContext();
        server.syncIo.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("type", "SUBTITLE_CONFIG_SYNC")
                        .put("subtitle_config", com.meedx.providers.UnifiedSubtitleConfig.exportSyncJson(app));
                String text = payload.toString();
                for (WebSocket ws : server.phones.values()) {
                    if (ws != null && ws.isOpen()) {
                        try { ws.send(text); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    public static void broadcastApiKeysSync(android.content.Context context) {
        CinemaLinkServer server = instance;
        if (server == null || context == null) return;
        android.content.Context app = context.getApplicationContext();
        server.syncIo.execute(() -> {
            try {
                JSONObject obj = server.buildApiKeysSyncPayload(app);
                if (obj == null) return;
                String payload = obj.toString();
                for (WebSocket phone : server.phones.values()) {
                    if (phone != null && phone.isOpen()) {
                        try { phone.send(payload); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    static final String ACTION_ADDONS_CHANGED = "com.meedx.localcinema.tv.ADDONS_CHANGED";

    private static WebSocket addonPhone() {
        CinemaLinkServer server = instance;
        if (server == null) return null;
        WebSocket phone = server.phones.get(server.prefs.getString("active", ""));
        return phone != null && phone.isOpen() ? phone : null;
    }

    static boolean requestAddonSync() {
        WebSocket phone = addonPhone();
        if (phone == null) return false;
        try {
            phone.send(new JSONObject().put("type", "REQUEST_ADDONS_SYNC").toString());
            return true;
        } catch (Exception ignored) { return false; }
    }

    static void sendAddonEnabled(String manifestUrl, boolean enabled) {
        WebSocket phone = addonPhone();
        if (phone == null) return;
        try {
            phone.send(new JSONObject().put("type", "ADDON_ENABLED")
                    .put("manifest_url", manifestUrl).put("enabled", enabled).toString());
        } catch (Exception ignored) {}
    }

    static void sendAddonRemoved(String manifestUrl) {
        WebSocket phone = addonPhone();
        if (phone == null) return;
        try {
            phone.send(new JSONObject().put("type", "ADDON_REMOVED").put("manifest_url", manifestUrl).toString());
        } catch (Exception ignored) {}
    }

    private void notifyAddonsChanged(boolean fromPhone) {
        if (fromPhone) libraryContext.getSharedPreferences("cinema_addon_state", 0)
                .edit().putBoolean("from_phone", true).apply();
        libraryContext.sendBroadcast(new android.content.Intent(ACTION_ADDONS_CHANGED).setPackage(libraryContext.getPackageName()));
    }

    public static void broadcastAddonsSync(android.content.Context context) {
        CinemaLinkServer server = instance;
        if (server == null || context == null) return;
        android.content.Context app = context.getApplicationContext();
        server.syncIo.execute(() -> {
            try {
                org.json.JSONArray addons = com.meedx.providers.StremioAddons.exportJson(app);
                String payload = new JSONObject().put("type", "ADDONS_SYNC").put("addons", addons).toString();
                for (WebSocket phone : server.phones.values()) {
                    if (phone != null && phone.isOpen()) {
                        try { phone.send(payload); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    public static void broadcastFavoriteChannels(android.content.Context context) {
        CinemaLinkServer server = instance;
        if (server == null) return;
        android.content.Context app = context.getApplicationContext();
        server.syncIo.execute(() -> {
            var state = LiveChannelFavorites.snapshot(app);
            for (WebSocket phone : server.phones.values()) server.sendFavoriteChannels(phone, state);
        });
    }

    public static void broadcastLiveTvPreferences(android.content.Context context) {
        CinemaLinkServer server = instance;
        if (server == null || context == null) return;
        android.content.Context app = context.getApplicationContext();
        server.syncIo.execute(() -> {
            try {
                String payload = new JSONObject()
                        .put("type", com.meedx.providers.LiveTvPreferences.PREFS_PACKET)
                        .put(com.meedx.providers.LiveTvPreferences.COUNTRY_FIELD,
                                com.meedx.providers.LiveTvPreferences.country(app))
                        .put(com.meedx.providers.LiveTvPreferences.FAVORITE_COUNTRIES_FIELD,
                                com.meedx.providers.LiveTvPreferences.exportFavoriteCountriesJson(app))
                        .toString();
                for (WebSocket phone : server.phones.values()) {
                    if (phone != null && phone.isOpen()) {
                        try { phone.send(payload); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void sendFavoriteChannels(WebSocket socket, JSONObject state) {
        if (socket == null || !socket.isOpen() || state.length() == 0) return;
        try {
            socket.send(new JSONObject().put("type", LiveChannelFavorites.PACKET)
                    .put(LiveChannelFavorites.FIELD, state).toString());
        } catch (Exception ignored) {}
    }

    public static void savePreferredPlaybackSpeed(android.content.Context context, int speedPercent, boolean remember) {
        if (context == null) return;
        int clamped = (speedPercent >= 50 && speedPercent <= 200) ? speedPercent : 100;
        var sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        String legacyKey = context.getString(com.archos.mediacenter.video.R.string.save_audio_speed_setting_pref_key);
        sp.edit()
                .putBoolean("pref_remember_video_speed", remember)
                .putInt("pref_video_speed", remember ? clamped : 100)
                .putBoolean(com.archos.mediacenter.video.utils.VideoPreferencesCommon.KEY_PLAYBACK_SPEED, true)
                .putFloat(legacyKey, remember ? (clamped / 100f) : 1.0f)
                .apply();
    }

    public static void saveDualSubtitlePosition(android.content.Context context, int mode) {
        if (context == null) return;
        int clamped = Math.max(0, Math.min(2, mode));
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
                .edit()
                .putInt("pref_dual_sub_position", clamped)
                .apply();
    }

    public static void broadcastWatchSync(android.content.Context context) {
        CinemaLinkServer server = instance;
        if (server == null || context == null) return;
        android.content.Context app = context.getApplicationContext();
        server.syncIo.execute(() -> {
            try {
                org.json.JSONArray records = CinemaWatchStore.exportRecentSyncRecords(app, 50);
                org.json.JSONArray watchlist = CinemaWatchStore.exportWatchlistJson(app);
                if (records.length() == 0 && watchlist.length() == 0) return;
                String payload = new JSONObject()
                        .put("type", "WATCH_SYNC")
                        .put("records", records)
                        .put("watchlist", watchlist)
                        .toString();
                for (WebSocket phone : server.phones.values()) {
                    if (phone != null && phone.isOpen()) {
                        try { phone.send(payload); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void sendWatchSyncSnapshot(WebSocket socket) {
        if (socket == null || !socket.isOpen()) return;
        syncIo.execute(() -> {
            try {
                org.json.JSONArray records = CinemaWatchStore.exportRecentSyncRecords(libraryContext, 100);
                org.json.JSONArray watchlist = CinemaWatchStore.exportWatchlistJson(libraryContext);
                if ((records.length() > 0 || watchlist.length() > 0) && socket.isOpen()) {
                    socket.send(new JSONObject()
                            .put("type", "WATCH_SYNC")
                            .put("records", records)
                            .put("watchlist", watchlist)
                            .toString());
                }
            } catch (Exception ignored) {}
        });
    }

    public static void broadcastWatchSync(JSONObject record) {
        CinemaLinkServer server = instance;
        if (server == null || record == null) return;
        server.syncIo.execute(() -> {
            try {
                org.json.JSONArray records = new org.json.JSONArray().put(record);
                String payload = new JSONObject().put("type", "WATCH_SYNC").put("records", records).toString();
                for (WebSocket phone : server.phones.values()) {
                    if (phone != null && phone.isOpen()) {
                        try { phone.send(payload); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void handleWatchSync(JSONObject command) {
        org.json.JSONArray records = command.optJSONArray("records");
        if (records == null || records.length() == 0) return;
        syncIo.execute(() -> {
            boolean anyChanged = false;
            for (int i = 0; i < records.length(); i++) {
                JSONObject rec = records.optJSONObject(i);
                if (rec != null && CinemaWatchStore.applyRemoteSyncRecord(libraryContext, rec)) {
                    anyChanged = true;
                }
            }
            if (anyChanged && libraryContext != null) {
                libraryContext.sendBroadcast(new android.content.Intent(ACTION_WATCH_SYNC_UPDATED)
                        .setPackage(libraryContext.getPackageName()));
            }
        });
    }

    private void handleAddonsSync(WebSocket socket, JSONObject command) {
        org.json.JSONArray addons = command.optJSONArray("addons");
        if (addons == null || libraryContext == null) return;
        syncIo.execute(() -> {
            boolean snapshot = command.optBoolean("authoritative", false);
            if (snapshot) com.meedx.providers.StremioAddons.replaceAuthoritativeJson(libraryContext, addons);
            else com.meedx.providers.StremioAddons.mergeRemoteJson(libraryContext, addons);
            notifyAddonsChanged(true);
            try {
                if (socket.isOpen()) socket.send(new JSONObject().put("type", "ADDONS_ACK")
                        .put("count", com.meedx.providers.StremioAddons.list(libraryContext).size()).toString());
            } catch (Exception ignored) {}
        });
    }

    private void handleInstallAddonOnTv(WebSocket socket, JSONObject command) {
        if (libraryContext == null || command == null) return;
        String rawUrl = command.optString("manifest_url", "").trim();
        if (rawUrl.isEmpty()) return;
        syncIo.execute(() -> {
            try {
                String normalized = com.meedx.providers.StremioAddons.normalizeManifestUrl(rawUrl);
                var installed = com.meedx.providers.StremioAddons.list(libraryContext).stream()
                        .filter(a -> normalized.equals(a.manifestUrl)).findFirst().orElse(null);
                var added = installed == null
                        ? com.meedx.providers.StremioAddons.fetchAndAdd(libraryContext, normalized)
                        : installed;
                if (!added.enabled) {
                    com.meedx.providers.StremioAddons.setEnabled(libraryContext, added.manifestUrl, true);
                }
                notifyAddonsChanged(true);
                String name = (added.name != null && !added.name.isBlank()) ? added.name : added.id;
                int total = com.meedx.providers.StremioAddons.list(libraryContext).size();
                main.post(() -> {
                    try {
                        boolean ar = CinemaUi.arabic(libraryContext);
                        android.widget.Toast.makeText(libraryContext,
                                ar ? ("تم تثبيت الإضافة على الشاشة: " + name) : ("Addon installed on TV: " + name),
                                android.widget.Toast.LENGTH_LONG).show();
                    } catch (Exception ignored) {}
                });
                if (socket != null && socket.isOpen()) {
                    socket.send(new JSONObject()
                            .put("type", "ADDON_TV_INSTALLED")
                            .put("ok", true)
                            .put("name", name)
                            .put("manifest_url", added.manifestUrl)
                            .put("count", total)
                            .toString());
                }
            } catch (Exception e) {
                android.util.Log.w("CinemaLink", "INSTALL_ADDON_ON_TV failed", e);
                if (socket != null && socket.isOpen()) {
                    try {
                        socket.send(new JSONObject()
                                .put("type", "ADDON_TV_INSTALLED")
                                .put("ok", false)
                                .put("error", String.valueOf(e.getMessage()))
                                .toString());
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private void handleFullSyncState(WebSocket socket, JSONObject command) {
        if (libraryContext == null || command == null) return;
        syncIo.execute(() -> {
            applyApiKeysSyncBlocking(command);
            String countryField = com.meedx.providers.LiveTvPreferences.COUNTRY_FIELD;
            String country = command.optString(countryField);
            boolean liveCountryApplied = com.meedx.providers.LiveTvPreferences.saveCountry(libraryContext, country);
            com.meedx.providers.LiveTvPreferences.importFavoriteCountriesJson(
                    libraryContext, command.optJSONArray(com.meedx.providers.LiveTvPreferences.FAVORITE_COUNTRIES_FIELD));
            JSONObject incomingFavorites = command.optJSONObject(LiveChannelFavorites.FIELD);
            if (LiveChannelFavorites.merge(libraryContext, incomingFavorites)) broadcastFavoriteChannels(libraryContext);
            org.json.JSONArray addons = command.optJSONArray("addons");
            if (addons != null) {
                com.meedx.providers.StremioAddons.replaceAuthoritativeJson(libraryContext, addons);
                notifyAddonsChanged(true);
            }
            org.json.JSONArray watchRecords = command.optJSONArray("watch_records");
            boolean watchChanged = false;
            if (watchRecords != null) {
                CinemaWatchStore.applyTraktItemsFromSyncRecords(libraryContext, watchRecords);
                for (int i = 0; i < watchRecords.length(); i++) {
                    JSONObject rec = watchRecords.optJSONObject(i);
                    if (rec != null && CinemaWatchStore.applyRemoteSyncRecord(libraryContext, rec)) {
                        watchChanged = true;
                    }
                }
                if (watchRecords.length() > 0) watchChanged = true;
            }
            org.json.JSONArray watchlist = command.optJSONArray("watchlist");
            if (watchlist != null) {
                CinemaWatchStore.applySyncedWatchlistFromPhone(libraryContext, watchlist);
                watchChanged = true;
            }
            if (command.has("remember_playback_speed") || command.has("preferred_playback_speed_percent")) {
                savePreferredPlaybackSpeed(
                        libraryContext,
                        command.optInt("preferred_playback_speed_percent", 100),
                        command.optBoolean("remember_playback_speed", false));
            }
            if (command.has("dual_sub_position")) {
                int dualPos = Math.max(0, Math.min(2, command.optInt("dual_sub_position", 1)));
                saveDualSubtitlePosition(libraryContext, dualPos);
                com.meedx.providers.UnifiedSubtitleConfig.setDualSubPosition(libraryContext, dualPos);
                main.post(() -> {
                    if (foreground instanceof com.archos.mediacenter.video.player.PlayerActivity activity) {
                        activity.applyPhoneSubtitleStyle("dual_position", dualPos);
                    } else if (foreground instanceof CinemaHlsActivity hls) {
                        hls.applyPhoneSubtitleStyle("dual_position", dualPos);
                    }
                });
            }
            JSONObject incomingSubCfg = command.optJSONObject("subtitle_config");
            if (incomingSubCfg != null) {
                if (com.meedx.providers.UnifiedSubtitleConfig.importSyncJson(libraryContext, incomingSubCfg, false)) {
                    CinemaSubtitleSyncBridge.applyUnifiedToTvPrefs(libraryContext);
                }
            }
            long now = System.currentTimeMillis();
            String phoneName = command.optString("phone_name", "");
            prefs.edit()
                    .putLong("last_phone_sync_at", now)
                    .putString("last_phone_sync_name", phoneName)
                    .commit();
            if (watchChanged) {
                libraryContext.sendBroadcast(new android.content.Intent(ACTION_WATCH_SYNC_UPDATED)
                        .setPackage(libraryContext.getPackageName()));
            }
            libraryContext.sendBroadcast(new android.content.Intent(ACTION_API_SYNC_UPDATED)
                    .setPackage(libraryContext.getPackageName()));
            if (socket != null && socket.isOpen()) {
                try {
                    var ack = new JSONObject()
                            .put("type", "FULL_SYNC_ACK")
                            .put("ok", true)
                            .put("trakt_connected", com.meedx.providers.TraktSyncClient.isConnected(libraryContext))
                            .put("trakt_username", com.meedx.providers.TraktSyncClient.getConnectedUsername(libraryContext))
                            .put("addons_count", com.meedx.providers.StremioAddons.list(libraryContext).size())
                            .put("subtitle_config", com.meedx.providers.UnifiedSubtitleConfig.exportSyncJson(libraryContext))
                            .put("synced_at", now);
                    if (liveCountryApplied) ack.put(countryField, country);
                    if (incomingFavorites != null) ack.put(LiveChannelFavorites.FIELD, LiveChannelFavorites.snapshot(libraryContext));
                    socket.send(ack.toString());
                } catch (Exception ignored) {}
            }
        });
    }

    private void handleSubtitleConfigSync(JSONObject command) {
        if (libraryContext == null || command == null) return;
        JSONObject cfg = command.optJSONObject("subtitle_config");
        if (cfg == null) return;
        syncIo.execute(() -> {
            com.meedx.providers.UnifiedSubtitleConfig.importSyncJson(libraryContext, cfg, true);
            CinemaSubtitleSyncBridge.applyUnifiedToTvPrefs(libraryContext);
            int dualPos = com.meedx.providers.UnifiedSubtitleConfig.dualSubPosition(libraryContext);
            saveDualSubtitlePosition(libraryContext, dualPos);
            libraryContext.sendBroadcast(new android.content.Intent(ACTION_API_SYNC_UPDATED)
                    .setPackage(libraryContext.getPackageName()));
            main.post(() -> {
                if (foreground instanceof com.archos.mediacenter.video.player.PlayerActivity activity) {
                    activity.applyPhoneSubtitleStyle("dual_position", dualPos);
                } else if (foreground instanceof CinemaHlsActivity hls) {
                    hls.applyPhoneSubtitleStyle("dual_position", dualPos);
                }
            });
        });
    }

    private void handleApiKeysSync(JSONObject command) {
        if (libraryContext == null || command == null) return;
        syncIo.execute(() -> {
            applyApiKeysSyncBlocking(command);
            libraryContext.sendBroadcast(new android.content.Intent(ACTION_API_SYNC_UPDATED)
                    .setPackage(libraryContext.getPackageName()));
        });
    }

    private void applyApiKeysSyncBlocking(JSONObject command) {
        if (libraryContext == null || command == null) return;
        boolean traktChanged = false;
        String traktId = com.meedx.providers.TraktSyncClient.normalizeClientId(command.optString("trakt_id", ""));
        if (!traktId.isEmpty()) {
            com.meedx.providers.ProviderKeys.save(libraryContext, com.meedx.providers.ProviderKeys.TRAKT_ID, traktId);
            traktChanged = true;
        }
        if (command.has("trakt_secret")) {
            String traktSecret = command.optString("trakt_secret", "").trim();
            com.meedx.providers.ProviderKeys.save(libraryContext, com.meedx.providers.ProviderKeys.TRAKT_SECRET, traktSecret);
            traktChanged = true;
        }
        String access = command.optString("trakt_access_token", "").trim();
        if (!access.isEmpty()) {
            com.meedx.providers.ProviderKeys.save(libraryContext, com.meedx.providers.ProviderKeys.TRAKT_ACCESS_TOKEN, access);
            traktChanged = true;
        }
        String refresh = command.optString("trakt_refresh_token", "").trim();
        if (!refresh.isEmpty()) {
            com.meedx.providers.ProviderKeys.save(libraryContext, com.meedx.providers.ProviderKeys.TRAKT_REFRESH_TOKEN, refresh);
            traktChanged = true;
        }
        String expires = command.optString("trakt_expires_at", "").trim();
        if (!expires.isEmpty()) {
            com.meedx.providers.ProviderKeys.save(libraryContext, com.meedx.providers.ProviderKeys.TRAKT_EXPIRES_AT, expires);
            traktChanged = true;
        }
        String username = command.optString("trakt_username", "").trim();
        if (!username.isEmpty()) {
            com.meedx.providers.ProviderKeys.save(libraryContext, com.meedx.providers.ProviderKeys.TRAKT_USERNAME, username);
            traktChanged = true;
        }
        String slug = command.optString("trakt_slug", "").trim();
        if (!slug.isEmpty()) {
            com.meedx.providers.ProviderKeys.save(libraryContext, com.meedx.providers.ProviderKeys.TRAKT_SLUG, slug);
            traktChanged = true;
        }
        if (command.optBoolean("trakt_signout", false)) {
            com.meedx.providers.TraktSyncClient.signOut(libraryContext);
            traktChanged = true;
        }
        if (command.has("opensubtitles_key")) {
            String osKey = command.optString("opensubtitles_key", "").trim();
            com.meedx.providers.ProviderKeys.save(libraryContext, com.meedx.providers.ProviderKeys.OPEN_SUBTITLES, osKey);
        }
        if (command.has("subdl_key")) {
            String subdlKey = command.optString("subdl_key", "").trim();
            com.meedx.providers.ProviderKeys.save(libraryContext, com.meedx.providers.ProviderKeys.SUBDL, subdlKey);
        }
        if (command.has("tmdb_key")) {
            String tmdbKey = command.optString("tmdb_key", "").trim();
            com.meedx.providers.ProviderKeys.save(libraryContext, com.meedx.providers.ProviderKeys.TMDB, tmdbKey);
        }
        if (command.has("subsource_key")) {
            String subsourceKey = command.optString("subsource_key", "").trim();
            com.meedx.providers.ProviderKeys.save(libraryContext, com.meedx.providers.ProviderKeys.SUBSOURCE, subsourceKey);
            try {
                ApiKeyStore store = new ApiKeyStore(libraryContext, "subsource");
                if (subsourceKey.isEmpty()) {
                    store.save(java.util.Collections.emptyList());
                } else {
                    java.util.List<String> keys = new java.util.ArrayList<>(store.read());
                    keys.remove(subsourceKey);
                    keys.add(0, subsourceKey);
                    store.save(keys);
                }
            } catch (Exception ignored) {}
        }
        com.meedx.providers.TraktSyncClient.migrateAndCleanCredentials(libraryContext);
        if (traktChanged) {
            com.archos.mediacenter.utils.trakt.Trakt.resetProviderSession(libraryContext);
            if (com.meedx.providers.TraktSyncClient.isConnected(libraryContext)) {
                CinemaWatchStore.syncWithTraktAsync(libraryContext);
            }
        }
    }

    private void handleExportTvBackup(WebSocket socket) {
        if (libraryContext == null || socket == null || !socket.isOpen()) return;
        syncIo.execute(() -> {
            try {
                var sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(libraryContext);
                JSONObject tvPrefs = serializeSharedPrefs(sp);
                for (CinemaPlaybackOption option : CinemaPlaybackOption.values()) {
                    tvPrefs.put(option.key, new JSONObject().put("t", "b").put("v", option.enabled(libraryContext)));
                }
                JSONObject linkOptions = new JSONObject();
                for (String key : BACKUP_LINK_OPTIONS) linkOptions.put(key, prefs.getBoolean(key, true));
                linkOptions.put("audio_hud_timeout", prefs.getInt("audio_hud_timeout", 5000));
                linkOptions.put("local_adb_enabled", TvLocalAdbBridge.isEnabled(libraryContext));
                JSONObject out = new JSONObject()
                        .put("type", "TV_BACKUP_DATA")
                        .put("tv_model", android.os.Build.MODEL)
                        .put("exported_at", System.currentTimeMillis())
                        .put("tv_prefs", tvPrefs)
                        .put("tv_link_options", linkOptions)
                        .put(com.meedx.providers.LiveTvPreferences.COUNTRY_FIELD,
                                com.meedx.providers.LiveTvPreferences.country(libraryContext))
                        .put(com.meedx.providers.LiveTvPreferences.FAVORITE_COUNTRIES_FIELD,
                                com.meedx.providers.LiveTvPreferences.exportFavoriteCountriesJson(libraryContext))
                        .put("addons", com.meedx.providers.StremioAddons.exportJson(libraryContext))
                        .put("subtitle_config", com.meedx.providers.UnifiedSubtitleConfig.exportSyncJson(libraryContext))
                        .put("watch_records", CinemaWatchStore.exportRecentSyncRecords(libraryContext, 500));
                JSONObject apiKeys = buildApiKeysSyncPayload(libraryContext);
                if (apiKeys != null) {
                    apiKeys.remove("type");
                    out.put("api_keys", apiKeys);
                }
                if (socket.isOpen()) socket.send(out.toString());
            } catch (Exception e) {
                android.util.Log.w("CinemaLink", "EXPORT_TV_BACKUP failed", e);
            }
        });
    }

    private void handleRestoreTvBackup(WebSocket socket, JSONObject command) {
        if (libraryContext == null || command == null) return;
        syncIo.execute(() -> {
            try {
                JSONObject tvPrefs = command.optJSONObject("tv_prefs");
                boolean langChanged = false;
                if (tvPrefs != null) {
                    var sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(libraryContext);
                    String oldLang = sp.getString(com.archos.mediacenter.video.utils.VideoPreferencesCommon.KEY_UI_LANG, "");
                    applySharedPrefs(sp, tvPrefs);
                    String newLang = sp.getString(com.archos.mediacenter.video.utils.VideoPreferencesCommon.KEY_UI_LANG, "");
                    langChanged = !java.util.Objects.equals(oldLang, newLang);
                }
                JSONObject linkOptions = command.optJSONObject("tv_link_options");
                if (linkOptions != null) {
                    var editor = prefs.edit();
                    for (String key : BACKUP_LINK_OPTIONS) {
                        if (linkOptions.opt(key) instanceof Boolean enabled) editor.putBoolean(key, enabled);
                    }
                    if (linkOptions.has("audio_hud_timeout")) {
                        int timeout = linkOptions.optInt("audio_hud_timeout", 5000);
                        editor.putInt("audio_hud_timeout", timeout == -1 || timeout == 0
                                ? timeout : Math.max(1000, Math.min(60000, timeout)));
                    }
                    editor.apply();
                    if (linkOptions.opt("local_adb_enabled") instanceof Boolean enabled) {
                        TvLocalAdbBridge.setEnabled(libraryContext, enabled);
                        if (enabled) TvLocalAdbBridge.reconnectIfEnabledAsync(libraryContext);
                    }
                }
                JSONObject apiKeys = command.optJSONObject("api_keys");
                if (apiKeys != null) applyApiKeysSyncBlocking(apiKeys);
                String restoredCountry = command.optString(com.meedx.providers.LiveTvPreferences.COUNTRY_FIELD, "");
                if (!restoredCountry.isBlank()) com.meedx.providers.LiveTvPreferences.saveCountry(libraryContext, restoredCountry);
                com.meedx.providers.LiveTvPreferences.importFavoriteCountriesJson(
                        libraryContext, command.optJSONArray(com.meedx.providers.LiveTvPreferences.FAVORITE_COUNTRIES_FIELD));

                org.json.JSONArray addons = command.optJSONArray("addons");
                if (addons != null) com.meedx.providers.StremioAddons.mergeRemoteJson(libraryContext, addons);

                JSONObject restoredSubCfg = command.optJSONObject("subtitle_config");
                if (restoredSubCfg != null) {
                    com.meedx.providers.UnifiedSubtitleConfig.importSyncJson(libraryContext, restoredSubCfg, true);
                    CinemaSubtitleSyncBridge.applyUnifiedToTvPrefs(libraryContext);
                }

                org.json.JSONArray watchRecords = command.optJSONArray("watch_records");
                if (watchRecords != null) {
                    for (int i = 0; i < watchRecords.length(); i++) {
                        JSONObject rec = watchRecords.optJSONObject(i);
                        if (rec != null) CinemaWatchStore.applyRemoteSyncRecord(libraryContext, rec, false);
                    }
                }
                CinemaMetadataPrefetcher.clearAllMetadataCaches(libraryContext);
                libraryContext.sendBroadcast(new android.content.Intent(ACTION_WATCH_SYNC_UPDATED)
                        .setPackage(libraryContext.getPackageName()));

                boolean refreshLocale = langChanged;
                main.post(() -> {
                    try {
                        if (refreshLocale && libraryContext.getApplicationContext() instanceof com.archos.mediacenter.video.CustomApplication app) {
                            app.loadLocale();
                        }
                        if (foreground instanceof CinemaHlsActivity hls) {
                            hls.refreshOsdLabels();
                        } else if (foreground instanceof com.archos.mediacenter.video.player.PlayerActivity player) {
                            player.refreshCinemaPlaybackOptions();
                        } else if (foreground != null && !foreground.isFinishing()) {
                            foreground.recreate();
                        }
                    } catch (Exception ignored) {}
                });
                broadcastRemoteStatus(libraryContext);
                if (socket != null && socket.isOpen()) {
                    socket.send(new JSONObject().put("type", "TV_BACKUP_RESTORED").put("ok", true).toString());
                }
            } catch (Exception e) {
                android.util.Log.w("CinemaLink", "RESTORE_TV_BACKUP failed", e);
                if (socket != null && socket.isOpen()) {
                    try {
                        socket.send(new JSONObject().put("type", "TV_BACKUP_RESTORED")
                                .put("ok", false).put("error", String.valueOf(e.getMessage())).toString());
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private static JSONObject serializeSharedPrefs(android.content.SharedPreferences sp) {
        JSONObject root = new JSONObject();
        if (sp == null) return root;
        for (java.util.Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (key == null || val == null) continue;
            try {
                if (val instanceof Boolean b) {
                    root.put(key, new JSONObject().put("t", "b").put("v", b));
                } else if (val instanceof Integer i) {
                    root.put(key, new JSONObject().put("t", "i").put("v", i));
                } else if (val instanceof Long l) {
                    root.put(key, new JSONObject().put("t", "l").put("v", l));
                } else if (val instanceof Float f) {
                    root.put(key, new JSONObject().put("t", "f").put("v", (double) f));
                } else if (val instanceof String s) {
                    root.put(key, new JSONObject().put("t", "s").put("v", s));
                } else if (val instanceof java.util.Set<?> set) {
                    org.json.JSONArray arr = new org.json.JSONArray();
                    for (Object item : set) {
                        if (item != null) arr.put(item.toString());
                    }
                    root.put(key, new JSONObject().put("t", "set").put("v", arr));
                }
            } catch (Exception ignored) {}
        }
        return root;
    }

    private static void applySharedPrefs(android.content.SharedPreferences sp, JSONObject src) {
        if (sp == null || src == null) return;
        android.content.SharedPreferences.Editor ed = sp.edit();
        java.util.Iterator<String> keys = src.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (com.archos.mediacenter.video.utils.VideoPreferencesCommon.KEY_SMBJ.equals(key)) continue;
            JSONObject wrapper = src.optJSONObject(key);
            if (wrapper == null) continue;
            String t = wrapper.optString("t", "");
            switch (t) {
                case "b" -> ed.putBoolean(key, wrapper.optBoolean("v", false));
                case "i" -> ed.putInt(key, wrapper.optInt("v", 0));
                case "l" -> ed.putLong(key, wrapper.optLong("v", 0L));
                case "f" -> ed.putFloat(key, (float) wrapper.optDouble("v", 0.0));
                case "s" -> ed.putString(key, wrapper.optString("v", ""));
                case "set" -> {
                    org.json.JSONArray arr = wrapper.optJSONArray("v");
                    if (arr != null) {
                        java.util.Set<String> set = new java.util.HashSet<>();
                        for (int i = 0; i < arr.length(); i++) {
                            String val = arr.optString(i, null);
                            if (val != null) set.add(val);
                        }
                        ed.putStringSet(key, set);
                    }
                }
            }
        }
        ed.putBoolean(com.archos.mediacenter.video.utils.VideoPreferencesCommon.KEY_SMBJ, false).apply();
    }

    @Override public void onStart() {
        ready = true;
        android.util.Log.i("CinemaLink", "Listening on port " + getPort());
        broadcastRemoteStatus(libraryContext);
    }
    @Override public void onOpen(WebSocket socket, ClientHandshake headers) {
        main.postDelayed(() -> {
            if (socket.getAttachment() == null && !socket.isClosed()) {
                try { socket.close(1008); } catch (Throwable ignored) {}
            }
        }, 5000);
    }
    @Override public void onClose(WebSocket socket, int code, String reason, boolean remote) {
        backupPeers.remove(socket);
        photoUploads.remove(socket);
        cancelAudioUpload(socket);
        if (pendingPlay == socket) pendingPlay = null;
        String id = socket.getAttachment();
        if (id != null && phones.remove(id, socket)) {
            broadcastRemoteStatus(libraryContext);
        }
    }
    @Override public void onError(WebSocket socket, Exception error) {
        android.util.Log.w("CinemaLink", "WebSocket error on port " + port, error);
        if (socket == null && !ready) {
            if (error instanceof java.net.BindException) main.post(() -> retryBind(this));
            else failed = true;
        } else if (socket != null && !socket.isOpen()) {
            String id = socket.getAttachment();
            if (id != null && phones.remove(id, socket)) {
                broadcastRemoteStatus(libraryContext);
            }
        }
    }

    @Override public void onMessage(WebSocket socket, String message) {
        int limit = message.startsWith("{\"type\":\"SUBTITLE\"") ? 2200000
                : ((message.contains("\"RESTORE_TV_BACKUP\"") || message.contains("\"TV_BACKUP_DATA\"")) ? (2 * 1024 * 1024)
                : ((message.contains("\"FULL_SYNC_STATE\"") || message.contains("\"WATCH_SYNC\"") || message.contains("\"ADDONS_SYNC\"") || message.contains("\"API_KEYS_SYNC\"")) ? 1024000 : 16384));
        if (message.length() > limit) { socket.close(1009); return; }
        try {
            var command = new JSONObject(message);
            String type = command.getString("type");
            if (socket.getAttachment() == null) {
                if ("REGISTER_PHONE".equals(type)) register(socket, command);
                else socket.close(1008);
                return;
            }
            String id = socket.getAttachment();
            if (backupPeers.containsKey(socket)) {
                if (!prefs.contains("phone:" + id)) {
                    socket.close(1008, "Pairing rejected");
                    return;
                }
                if ("EXPORT_TV_BACKUP".equals(type)) handleExportTvBackup(socket);
                else if ("RESTORE_TV_BACKUP".equals(type)) handleRestoreTvBackup(socket, command);
                else socket.close(1008, "Backup session");
                return;
            }
            if (phones.get(id) != socket) return;
            if ("FULL_SYNC_STATE".equals(type)) {
                handleFullSyncState(socket, command);
                return;
            }
            if ("WATCH_SYNC".equals(type)) {
                handleWatchSync(command);
                return;
            }
            if ("ADDONS_SYNC".equals(type)) {
                handleAddonsSync(socket, command);
                return;
            }
            if ("INSTALL_ADDON_ON_TV".equals(type)) {
                handleInstallAddonOnTv(socket, command);
                return;
            }
            if ("API_KEYS_SYNC".equals(type)) {
                handleApiKeysSync(command);
                return;
            }
            if ("SUBTITLE_CONFIG_SYNC".equals(type)) {
                handleSubtitleConfigSync(command);
                return;
            }
            if ("EXPORT_TV_BACKUP".equals(type)) {
                handleExportTvBackup(socket);
                return;
            }
            if ("RESTORE_TV_BACKUP".equals(type)) {
                handleRestoreTvBackup(socket, command);
                return;
            }
            if (!id.equals(prefs.getString("active", "")) && !"PLAYBACK".equals(type)) {
                String curActive = prefs.getString("active", "");
                if (curActive.isEmpty() || !online(curActive)) {
                    prefs.edit().putString("active", id).apply();
                } else {
                    socket.send(new JSONObject().put("type", "NOT_CONTROLLER").toString());
                    return;
                }
            }
            if (java.util.Set.of("TOGGLE_PLAY", "SEEK", "JUMP", "SPEED", "STOP_PLAYBACK").contains(type)) {
                main.post(() -> {
                    if (foreground instanceof CinemaLocalMediaActivity media) {
                        try {
                            media.audioCommand(type, command.optLong("SPEED".equals(type) ? "percent" : "ms"));
                        } catch (IllegalStateException error) {
                            android.util.Log.w("CinemaLink", "Audio command failed", error);
                        }
                    } else {
                        handleCommand(socket, message);
                    }
                });
                return;
            }
            handleCommand(socket, message);
        } catch (org.json.JSONException | IllegalArgumentException error) {
            socket.close(1008);
        }
    }

    private record PhotoUpload(String path, java.io.ByteArrayOutputStream bytes) {}
    private final java.util.Map<WebSocket, PhotoUpload> photoUploads = new java.util.concurrent.ConcurrentHashMap<>();

    private void receivePhoto(WebSocket socket, JSONObject command) throws org.json.JSONException {
        String path = command.getString("path");
        if (!path.matches("/video/[a-f0-9]{32}")) return;
        if (command.optBoolean("start")) photoUploads.put(socket, new PhotoUpload(path, new java.io.ByteArrayOutputStream()));
        PhotoUpload upload = photoUploads.get(socket);
        if (upload == null || !path.equals(upload.path())) return;
        byte[] data = android.util.Base64.decode(command.getString("data"), android.util.Base64.DEFAULT);
        if (upload.bytes().size() + data.length > 16 * 1024 * 1024) {
            photoUploads.remove(socket);
            return;
        }
        upload.bytes().write(data, 0, data.length);
        if (!command.optBoolean("end")) return;
        photoUploads.remove(socket);
        byte[] bytes = upload.bytes().toByteArray();
        main.post(() -> {
            if (!canLaunch(socket)) return;
            CinemaLocalMediaActivity.incomingPhotos.clear();
            CinemaLocalMediaActivity.incomingPhotos.put(path, bytes);
            var intent = new android.content.Intent(libraryContext, CinemaLocalMediaActivity.class)
                    .setData(android.net.Uri.parse("http://localhost" + path))
                    .putExtra("kind", "image").addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            launch(intent);
        });
    }

    private record AudioUpload(String path, java.io.File file, java.io.FileOutputStream stream) {}
    private final java.util.Map<WebSocket, AudioUpload> audioUploads = new java.util.HashMap<>();

    private synchronized void cancelAudioUpload(WebSocket socket) {
        AudioUpload upload = audioUploads.remove(socket);
        if (upload == null) return;
        try { upload.stream().close(); } catch (java.io.IOException ignored) {}
        upload.file().delete();
    }

    private synchronized void receiveAudio(WebSocket socket, JSONObject command) throws org.json.JSONException {
        String path = command.getString("path");
        if (!path.matches("/video/[a-f0-9]{32}")) return;
        try {
            if (command.optBoolean("start")) {
                cancelAudioUpload(socket);
                var file = java.io.File.createTempFile("cinema-track-", ".media", prefsCache);
                audioUploads.put(socket, new AudioUpload(path, file, new java.io.FileOutputStream(file)));
            }
            AudioUpload upload = audioUploads.get(socket);
            if (upload == null || !path.equals(upload.path())) return;
            if (!command.optBoolean("end")) {
                byte[] bytes = android.util.Base64.decode(command.getString("data"), android.util.Base64.DEFAULT);
                if (upload.stream().getChannel().position() + bytes.length > 128L * 1024 * 1024) {
                    cancelAudioUpload(socket);
                    return;
                }
                upload.stream().write(bytes);
                return;
            }
            upload.stream().close();
            audioUploads.remove(socket);
            main.post(() -> {
                if (!canLaunch(socket)) {
                    upload.file().delete();
                    return;
                }
                var intent = new android.content.Intent(libraryContext, CinemaLocalMediaActivity.class)
                        .setData(android.net.Uri.parse("http://localhost" + path)).putExtra("kind", "audio")
                        .putExtra("cached_audio", upload.file().getAbsolutePath()).putExtra("track_path", path)
                        .putExtra("title", command.optString("title")).putExtra("artist", command.optString("artist"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                launch(intent);
            });
        } catch (java.io.IOException error) {
            cancelAudioUpload(socket);
            android.util.Log.w("CinemaLink", "Could not cache audio", error);
        }
    }

    static final String MOBILE_LIBRARY_CHANGED = "com.meedx.localcinema.tv.MOBILE_LIBRARY_CHANGED";
    private final java.util.concurrent.Executor libraryWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private android.content.Context libraryContext;

    private void updateMobileLibrary(WebSocket socket, JSONObject command, String type) {
        String phone = socket.getAttachment();
        if (phone == null || !phone.matches("[a-f0-9-]{36}")) return;
        String prefix = "mobile:" + phone + ":";
        libraryWorker.execute(() -> {
            if (!phone.equals(prefs.getString("active", ""))) return;
            var db = com.archos.mediacenter.utils.ShortcutDbAdapter.VIDEO;
            if ("SYNC_LIBRARY_FOLDERS".equals(type)) {
                var ids = command.optJSONArray("folder_ids");
                if (ids == null) return;
                var keep = new java.util.HashSet<String>();
                for (int i = 0; i < ids.length(); i++) keep.add(prefix + ids.optString(i));
                for (String key : prefs.getAll().keySet()) {
                    if (key.startsWith(prefix) && !keep.contains(key)) removeMobileFolder(key);
                }
            } else {
                String id = command.optString("folder_id");
                if (!id.matches("[a-f0-9-]{36}")) return;
                String key = prefix + id;
                if ("UNPUBLISH_LIBRARY_FOLDER".equals(type)) {
                    removeMobileFolder(key);
                } else {
                    var url = android.net.Uri.parse(command.optString("webdav_url"));
                    if (!"http".equals(url.getScheme())
                            || !privateIpv4(url.getHost()) || url.getPort() != 8080
                            || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
                            || url.getPath() == null || !url.getPath().matches("/webdav/[a-f0-9-]{36}/" + id + "/")) return;
                    String name = command.optString("folder_name").trim();
                    if (name.isEmpty() || name.length() > 160) return;
                    String uri = url.buildUpon().scheme("webdav").build().toString();
                    String previous = prefs.getString(key, "");
                    if (!previous.isEmpty() && !previous.equals(uri)) removeMobileFolder(key);
                    String displayFolder = "Movies".equalsIgnoreCase(name) ? "TV Movies" : name;
                    String label = (command.optBoolean("usb") ? "Mobile USB" : "Mobile") + " (" + displayFolder + ")";
                    long shortcutId = db.isShortcut(libraryContext, uri);
                    if (shortcutId <= 0) {
                        db.addShortcut(libraryContext, new com.archos.mediacenter.utils.ShortcutDbAdapter.Shortcut(
                                label, uri));
                    } else {
                        db.renameShortcut(libraryContext, shortcutId, label);
                    }
                    prefs.edit().putString(key, uri).apply();
                    com.archos.mediaprovider.NetworkScanner.scanVideos(libraryContext, uri);
                }
            }
            libraryContext.sendBroadcast(new android.content.Intent(MOBILE_LIBRARY_CHANGED)
                    .setPackage(libraryContext.getPackageName()));
        });
    }

    private static boolean privateIpv4(String host) {
        if (host == null || !host.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")) return false;
        try {
            return java.net.InetAddress.getByName(host) instanceof java.net.Inet4Address address
                    && address.isSiteLocalAddress();
        } catch (java.net.UnknownHostException invalid) {
            return false;
        }
    }

    private void removeMobileFolder(String key) {
        String uri = prefs.getString(key, "");
        if (!uri.isEmpty()) {
            com.archos.mediacenter.utils.ShortcutDbAdapter.VIDEO.deleteShortcut(libraryContext, uri);
            com.archos.mediaprovider.NetworkScanner.removeVideos(libraryContext, uri);
        }
        prefs.edit().remove(key).apply();
    }

    private void handleCommand(WebSocket socket, String message) {
        try {
            var command = new JSONObject(message);
            String type = command.getString("type");
            if ("EDIT_TEXT".equals(type)) {
                int move = command.optInt("move");
                int delete = command.optInt("delete");
                String text = command.optString("text");
                if (move >= -2000 && move <= 2000 && delete >= 0 && delete <= 2000 && text.length() <= 2000) {
                    main.post(() -> {
                        if (activeRemote(socket)) editRemoteText(move, delete, text);
                    });
                }
            } else if ("PASTE_TEXT".equals(type) || "TYPE_TEXT".equals(type)) {
                String text = command.getString("text");
                boolean manual = "PASTE_TEXT".equals(type);
                android.util.Log.d("TV_REMOTE_RX", type + " length=" + text.length());
                if (text.length() <= 2000) main.post(() -> {
                    if (activeRemote(socket)) paste(socket, text, manual);
                });
            } else if ("TYPE_AND_SUBMIT".equals(type) || "SUBMIT_ENTER".equals(type)) {
                String text = command.optString("text");
                if (text.length() <= 2000) main.post(() -> submitRemote(socket, text));
            } else if ("REMOTE_POINTER".equals(type)) {
                String action = command.optString("action");
                double dx = Math.max(-1, Math.min(1, command.optDouble("dx", 0)));
                double dy = Math.max(-1, Math.min(1, command.optDouble("dy", 0)));
                main.post(() -> {
                    if (activeRemote(socket) && !TvRemoteAccessibilityService.pointerCommand(action, dx, dy))
                        socket.send("{\"type\":\"REMOTE_ACCESSIBILITY_REQUIRED\"}");
                });
            } else if ("TV_APPS".equals(type)) {
                sendTvApps(socket);
            } else if ("OPEN_TV_APP".equals(type)) {
                String pkg = command.optString("package");
                main.post(() -> openTvApp(socket, pkg));
            } else if ("SUBTITLE".equals(type)) {
                String dataB64 = command.optString("data", "");
                String extension = command.optString("extension", "");
                String subUrl = command.optString("subtitle_url", "");
                String castId = command.optString("cast_id", "");
                boolean isSecondary = command.optBoolean("secondary", false);
                syncIo.execute(() -> {
                    try {
                        CinemaSubtitleCodec.DecodedSubtitle decoded;
                        if (!dataB64.isBlank()) {
                            byte[] raw = android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT);
                            if (raw.length > 20 * 1024 * 1024) return;
                            decoded = CinemaSubtitleCodec.decodeAndCache(libraryContext, raw, "phone." + extension);
                        } else if (!subUrl.isBlank() && validWebUrl(android.net.Uri.parse(subUrl))) {
                            decoded = CinemaSubtitleCodec.downloadUnpackAndCache(libraryContext, subUrl);
                        } else {
                            return;
                        }
                        main.post(() -> {
                            if (foreground instanceof CinemaHlsActivity hls) {
                                if (castId.isEmpty() || hls.matchesCastId(castId)) {
                                    hls.setSubtitle(decoded.ext(), decoded.utf8Bytes(), isSecondary);
                                }
                            } else if (foreground instanceof com.archos.mediacenter.video.player.PlayerActivity activity) {
                                activity.applyRemoteDecodedSubtitle(decoded, isSecondary);
                            }
                        });
                    } catch (Exception error) {
                        android.util.Log.w("CinemaLink", "Could not apply phone subtitle", error);
                    }
                });
            } else if ("SUBTITLE_CLEAR".equals(type)) {
                String castId = command.optString("cast_id", "");
                main.post(() -> {
                    if (foreground instanceof CinemaHlsActivity hls
                            && (castId.isEmpty() || hls.matchesCastId(castId))) {
                        hls.clearSubtitlesFromRemote();
                    } else if (foreground instanceof com.archos.mediacenter.video.player.PlayerActivity activity) {
                        activity.clearCastSubtitlesFromRemote();
                    }
                });
            } else if ("SUBTITLE_TRACK_SELECT".equals(type)) {
                int trackIdx = command.optInt("track_index", -1);
                boolean isSecondary = command.optBoolean("secondary", false);
                main.post(() -> {
                    if (foreground instanceof CinemaHlsActivity hls) {
                        if (isSecondary) hls.selectSecondaryEmbeddedSubtitleTrack(trackIdx);
                        else hls.selectEmbeddedSubtitleTrack(trackIdx);
                    } else if (foreground instanceof com.archos.mediacenter.video.player.PlayerActivity activity) {
                        if (isSecondary) activity.selectCinemaSecondarySubtitleTrack(trackIdx);
                        else activity.selectCinemaSubtitleTrack(trackIdx);
                    }
                });
            } else if ("UPDATE_CAST_METADATA".equals(type)) {
                String newTitle = command.optString("title", "").trim();
                if (!newTitle.isEmpty()) {
                    main.post(() -> {
                        cachedDescriptor = null;
                        cachedDescriptorKey = "";
                        if (foreground instanceof CinemaHlsActivity hls) {
                            hls.updateCastTitle(newTitle);
                        } else if (foreground instanceof com.archos.mediacenter.video.player.PlayerActivity activity) {
                            activity.updateCinemaPlaybackTitle(newTitle);
                        }
                        if (socket.isOpen()) sendPlayback(socket);
                    });
                }
            } else if ("SUBTITLE_STYLE".equals(type)) {
                String action = command.getString("action");
                int value = command.optInt("value");
                if ("dual_position".equals(action)) {
                    saveDualSubtitlePosition(libraryContext, value);
                }
                main.post(() -> {
                    if (foreground instanceof com.archos.mediacenter.video.player.PlayerActivity activity) {
                        activity.applyPhoneSubtitleStyle(action, value);
                    } else if (foreground instanceof CinemaHlsActivity hls) {
                        hls.applyPhoneSubtitleStyle(action, value);
                    }
                });
            } else if ("SPEED".equals(type) || "STOP_PLAYBACK".equals(type) || "STOP_CAST".equals(type)) {
                int speed = command.optInt("percent");
                if ("SPEED".equals(type) && command.has("remember")) {
                    savePreferredPlaybackSpeed(libraryContext, speed, command.optBoolean("remember", false));
                }
                main.post(() -> {
                    if ("STOP_CAST".equals(type)) {
                        if (!activeRemote(socket)) return;
                        if (foreground instanceof CinemaHlsActivity hls) {
                            hls.finish();
                            return;
                        }
                        if (com.archos.mediacenter.video.player.PlayerService.sPlayerService != null)
                            com.archos.mediacenter.video.player.PlayerService.sPlayerService.stopAndSaveVideoState();
                        if (foreground instanceof CinemaLocalMediaActivity media) media.finish();
                        else if (foreground instanceof com.archos.mediacenter.video.player.PlayerActivity player) player.finish();
                        return;
                    }
                    if (foreground instanceof CinemaHlsActivity hls) {
                        if ("STOP_PLAYBACK".equals(type)) hls.finish();
                        else if (speed >= 50 && speed <= 200) hls.speed(speed / 100f);
                        return;
                    }
                    if (activePlayer() == null) return;
                    if ("STOP_PLAYBACK".equals(type)) foreground.finish();
                    else if (speed >= 50 && speed <= 200 &&
                            com.archos.mediacenter.video.player.PlayerService.sPlayerService != null)
                        com.archos.mediacenter.video.player.PlayerService.sPlayerService.setAudioSpeed(speed / 100f, false);
                });
            } else if ("SET_VOLUME".equals(type)) {
                int percentage = Math.max(0, Math.min(100, command.getInt("percentage")));
                boolean muted = command.optBoolean("muted");
                main.post(() -> setPhoneVolume(socket, percentage, muted));
            } else if ("GO_LIVE".equals(type)) {
                main.post(() -> {
                    if (activeRemote(socket) && foreground instanceof CinemaHlsActivity hls) {
                        hls.goLive();
                        sendPlayback(socket);
                    }
                });
            } else if ("CYCLE_AUDIO_TRACK".equals(type)) {
                main.post(() -> {
                    if (foreground instanceof com.archos.mediacenter.video.player.PlayerActivity activity) {
                        activity.switchAudioTrack();
                    }
                });
            } else if ("PLAYBACK".equals(type)) {
                main.post(() -> sendPlayback(socket));
            } else if ("SEEK".equals(type) || "JUMP".equals(type) || "TOGGLE_PLAY".equals(type) || "TOUCHPAD_SEEK".equals(type)) {
                long value = command.optLong("ms");
                main.post(() -> {
                    if (foreground instanceof CinemaHlsActivity hls) {
                        if ("TOGGLE_PLAY".equals(type)) hls.toggle();
                        else if (hls.duration() > 0) {
                            long delta = ("JUMP".equals(type) || "TOUCHPAD_SEEK".equals(type))
                                    ? Math.max(-60000, Math.min(60000, value)) : value;
                            long target = ("JUMP".equals(type) || "TOUCHPAD_SEEK".equals(type))
                                    ? hls.position() + delta : value;
                            hls.seek((int) Math.max(0, Math.min(hls.duration(), target)));
                        }
                        sendPlayback(socket);
                        return;
                    }
                    if ("TOGGLE_PLAY".equals(type) && foreground == null && canLaunch(socket)) {
                        Activity previous = pausedPlayer.get();
                        if (previous != null && !previous.isFinishing() && !previous.isDestroyed()) {
                            pendingPlay = socket;
                            var resume = new android.content.Intent(libraryContext, previous.getClass())
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                            | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            launch(resume);
                            main.postDelayed(() -> {
                                if (pendingPlay == socket) pendingPlay = null;
                            }, 3000);
                        }
                        return;
                    }
                    var engine = activePlayer();
                    if (engine == null || engine.getDuration() <= 0) {
                        if ("TOUCHPAD_SEEK".equals(type) && activeRemote(socket) && value != 0) {
                            int fallbackKey = value > 0
                                    ? android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                                    : android.view.KeyEvent.KEYCODE_DPAD_LEFT;
                            dispatchKey(fallbackKey, false);
                        }
                        return;
                    }
                    if ("TOGGLE_PLAY".equals(type)) {
                        int state = com.archos.mediacenter.video.player.PlayerController.STATE_NORMAL;
                        if (engine.isPlaying()) engine.pause(state);
                        else engine.start(state);
                    } else {
                        long target = ("JUMP".equals(type) || "TOUCHPAD_SEEK".equals(type))
                                ? engine.getCurrentPosition() + Math.max(-60000, Math.min(60000, value))
                                : value;
                        engine.seekTo((int) Math.max(0, Math.min(engine.getDuration(), target)));
                    }
                    sendPlayback(socket);
                });
            } else if ("KEY".equals(type) || "KEY_HOLD".equals(type)) {
                int key = command.getInt("key");
                boolean held = "KEY_HOLD".equals(type) || (key == 23 && command.optBoolean("held"));
                android.util.Log.d("TV_REMOTE_RX", type + " " + key + " held=" + held);
                String transport = command.optString("remote_transport");
                main.post(() -> {
                    if (!activeRemote(socket)) return;
                    if ("adb".equals(transport) && TvLocalAdbBridge.isReady()) {
                        dispatchAdbKeyWithAccFallback(key, held);
                        return;
                    }
                    if (!held && remotePlayback(socket, key)) return;
                    if (!dispatchKey(key, held, !"web".equals(transport))
                            && !TvLocalAdbBridge.isReady()
                            && !TvRemoteAccessibilityService.available()
                            && (foreground == null || (!foreground.hasWindowFocus() && findFocusedInAppWindow() == null))) {
                        socket.send("{\"type\":\"REMOTE_ACCESSIBILITY_REQUIRED\"}");
                    }
                });
            } else if ("CAST_WEB".equals(type)) {
                var url = android.net.Uri.parse(command.getString("url"));
                String format = command.optString("format");
                String audio = command.optString("audio_url");
                String subtitleUrl = command.optString("subtitle_url", "");
                String secondarySubtitleUrl = command.optString("secondary_subtitle_url", "");
                boolean urlOk = validWebUrl(url) || ("addon".equals(format)
                        && ("http".equals(url.getScheme()) || "https".equals(url.getScheme()))
                        && url.getHost() != null && url.getUserInfo() == null);
                if (!urlOk || (!audio.isEmpty() && !validWebUrl(android.net.Uri.parse(audio)))) return;
                if (!subtitleUrl.isEmpty() && !validWebUrl(android.net.Uri.parse(subtitleUrl))) subtitleUrl = "";
                if (!secondarySubtitleUrl.isEmpty() && !validWebUrl(android.net.Uri.parse(secondarySubtitleUrl))) secondarySubtitleUrl = "";
                final String validSubtitleUrl = subtitleUrl;
                final String validSecondarySubtitleUrl = secondarySubtitleUrl;
                main.post(() -> {
                    if (!canLaunch(socket)) {
                        if (socket.isOpen()) socket.send("{\"type\":\"WEB_CAST_FAILED\"}");
                        return;
                    }
                    stopActivePlayerForNewCast();
                    var previous = foreground;
                    String path = url.getPath();
                    String lowerPath = path != null ? path.toLowerCase(java.util.Locale.ROOT) : "";
                    int vidW = command.optInt("video_width", 0);
                    int vidH = command.optInt("video_height", 0);
                    int vidRot = ((command.optInt("video_rotation", 0) % 360) + 360) % 360;
                    boolean needsAdaptiveFit = !audio.isEmpty()
                            || java.util.Set.of("dash", "hls", "audio", "split", "addon", "portrait_cast", "phone_local_media", "adaptive_file").contains(format)
                            || lowerPath.endsWith(".m3u8") || lowerPath.endsWith(".mpd")
                            || vidRot != 0 || (vidW > 0 && vidH > 0 && vidW < vidH);
                    Class<?> playerType = needsAdaptiveFit
                            ? CinemaHlsActivity.class : com.archos.mediacenter.video.player.PlayerActivity.class;
                    String resolvedFormat = (format == null || format.isEmpty()) && (vidRot != 0 || (vidW > 0 && vidH > 0 && vidW < vidH))
                            ? "portrait_cast" : format;
                    long resumeMs = command.optLong("resume_ms", 0L);
                    String streamKey = command.optString("stream_key", "");
                    String mediaType = command.optString("media_type", "");
                    String imdbId = command.optString("imdb_id", "");
                    long tmdbId = command.optLong("tmdb_id", 0L);
                    int season = command.optInt("season", 0);
                    int episode = command.optInt("episode", 0);
                    String title = command.optString("title", "");
                    String subtitleMime = command.optString("subtitle_mime", "");
                    String subtitleLanguage = command.optString("subtitle_language", "");
                    String secondarySubtitleMime = command.optString("secondary_subtitle_mime", "");
                    String secondarySubtitleLanguage = command.optString("secondary_subtitle_language", "");
                    String castId = command.optString("cast_id", "");
                    boolean liveCast = command.optBoolean("is_live", false);
                    String sourceType = command.optString("source_type", "");
                    boolean excludeHistory = command.optBoolean("exclude_from_history", false)
                            || "phone_local_media".equals(sourceType)
                            || url.toString().contains(":8766/");
                    if (excludeHistory || liveCast) {
                        resumeMs = 0L;
                    } else if (resumeMs <= 0) {
                        resumeMs = CinemaWatchStore.findBestStreamResumeMs(
                                libraryContext, streamKey.isBlank() ? url.toString() : streamKey,
                                mediaType, imdbId, tmdbId, season, episode, title);
                    }
                    var launchIntent = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                            url, libraryContext, playerType).putExtra("audio_url", audio).putExtra("format", resolvedFormat)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .putExtra("is_live_cast", liveCast)
                            .putExtra("exclude_from_history", excludeHistory)
                            .putExtra("source_type", sourceType)
                            .putExtra("video_width", vidW)
                            .putExtra("video_height", vidH)
                            .putExtra("video_rotation", vidRot)
                            .putExtra("subtitle_url", validSubtitleUrl)
                            .putExtra("subtitle_mime", subtitleMime)
                            .putExtra("subtitle_language", subtitleLanguage)
                            .putExtra("addon_subtitles", command.optBoolean("addon_subtitles"))
                            .putExtra("secondary_subtitle_url", validSecondarySubtitleUrl)
                            .putExtra("secondary_subtitle_mime", secondarySubtitleMime)
                            .putExtra("secondary_subtitle_language", secondarySubtitleLanguage)
                            .putExtra("cast_id", castId)
                            .putExtra("title", title)
                            .putExtra("stream_key", excludeHistory ? "" : streamKey)
                            .putExtra("media_type", mediaType)
                            .putExtra("imdb_id", imdbId)
                            .putExtra("tmdb_id", tmdbId)
                            .putExtra("season", season)
                            .putExtra("episode", episode)
                            .putExtra("resume_ms", resumeMs)
                            .putExtra("height", Math.max(0, Math.min(4320, command.optInt("height"))));
                    if (playerType == com.archos.mediacenter.video.player.PlayerActivity.class) {
                        if (excludeHistory || liveCast) {
                            launchIntent.putExtra(com.archos.mediacenter.video.player.PlayerActivity.RESUME,
                                    com.archos.mediacenter.video.player.PlayerService.RESUME_NO);
                        } else if (resumeMs > 0) {
                            launchIntent.putExtra(com.archos.mediacenter.video.player.PlayerActivity.RESUME,
                                    com.archos.mediacenter.video.player.PlayerService.RESUME_FROM_LAST_POS);
                            launchIntent.putExtra("position", (int) Math.min(Integer.MAX_VALUE, resumeMs));
                        }
                    }
                    launch(launchIntent);
                    confirmWebPlayback(socket, previous, 0);
                });
            } else if ("PUBLISH_LIBRARY_FOLDER".equals(type) || "UNPUBLISH_LIBRARY_FOLDER".equals(type)
                    || "SYNC_LIBRARY_FOLDERS".equals(type)) {
                updateMobileLibrary(socket, command, type);
            } else if ("SET_AUDIO_HUD_TIMEOUT".equals(type)) {
                int timeout = command.getInt("timeout_ms");
                if (timeout != -1 && timeout != 0 && timeout != 3000 && timeout != 5000 && timeout != 10000 && timeout != 15000) return;
                main.post(() -> {
                    prefs.edit().putInt("audio_hud_timeout", timeout).apply();
                    if (foreground instanceof CinemaLocalMediaActivity media) media.showHud();
                });
            } else if ("AUDIO_DATA".equals(type)) {
                receiveAudio(socket, command);
            } else if ("PHOTO_DATA".equals(type)) {
                receivePhoto(socket, command);
            } else if ("CAST_MEDIA".equals(type)) {
                String path = command.getString("path");
                String kind = command.getString("media_type");
                if (!path.matches("/video/[a-f0-9]{32}") || !("image".equals(kind) || "audio".equals(kind))) return;
                var peer = socket.getRemoteSocketAddress().getAddress();
                if (!(peer instanceof java.net.Inet4Address)) return;
                var uri = android.net.Uri.parse("http://" + peer.getHostAddress() + ":8766" + path);
                String title = command.optString("title");
                main.post(() -> {
                    if (!canLaunch(socket)) return;
                    stopActivePlayerForNewCast();
                    var intent = new android.content.Intent(libraryContext, CinemaLocalMediaActivity.class)
                            .setData(uri).putExtra("kind", kind).putExtra("title", title)
                            .putExtra("artist", command.optString("artist"))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    launch(intent);
                });
            } else if ("PHOTO_TRANSFORM".equals(type) || "STOP_PHOTO".equals(type)) {
                String path = command.getString("path");
                float scale = (float) command.optDouble("scale", 1);
                float x = (float) command.optDouble("offsetX", 0);
                float y = (float) command.optDouble("offsetY", 0);
                float angle = (float) command.optDouble("rotation", 0);
                if (!Float.isFinite(scale) || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(angle)) return;
                main.post(() -> {
                    if (socket.isOpen() && foreground instanceof CinemaLocalMediaActivity media) {
                        media.photoCommand(path, "STOP_PHOTO".equals(type), scale, x, y, angle, command.optBoolean("fill"));
                    }
                });
            } else if ("ROTATE_IMAGE".equals(type)) {
                main.post(() -> {
                    if (foreground instanceof CinemaLocalMediaActivity media) media.rotate();
                });
            } else if ("ROTATE_VIDEO".equals(type)) {
                int deg = ((command.optInt("degrees", 0) % 360) + 360) % 360;
                main.post(() -> {
                    if (foreground instanceof CinemaHlsActivity hls) {
                        hls.setManualRotation(deg);
                        sendPlayback(socket);
                    } else if (foreground instanceof com.archos.mediacenter.video.player.PlayerActivity pa
                            && pa.getIntent() != null && pa.getIntent().getData() != null) {
                        long curPos = 0L;
                        var activePlayer = com.archos.mediacenter.video.player.Player.sPlayer;
                        if (activePlayer != null) {
                            curPos = Math.max(0L, activePlayer.getCurrentPosition());
                        }
                        android.net.Uri uri = pa.getIntent().getData();
                        String title = pa.getCinemaPlaybackTitle();
                        stopActivePlayerForNewCast();
                        var intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, uri, libraryContext, CinemaHlsActivity.class)
                                .putExtra("format", "adaptive_file")
                                .putExtra("title", title == null ? "" : title)
                                .putExtra("use_texture_view", true)
                                .putExtra("manual_rotation", deg)
                                .putExtra("resume_ms", curPos)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        launch(intent);
                    }
                });
            } else if ("CAST".equals(type)) {
                String path = command.getString("path");
                boolean torrent = command.optBoolean("torrent");
                String title = command.optString("title");
                int vidW = command.optInt("video_width", 0);
                int vidH = command.optInt("video_height", 0);
                int vidRot = ((command.optInt("video_rotation", 0) % 360) + 360) % 360;
                String castSourceType = command.optString("source_type", "");
                boolean castExcludeHistory = command.optBoolean("exclude_from_history", false)
                        || "phone_local_media".equals(castSourceType);
                boolean hasRotation = vidRot != 0;
                boolean isPortrait = !torrent && (hasRotation || (vidW > 0 && vidH > 0 && vidW < vidH));
                boolean is4k = !torrent && (vidW >= 2560 || vidH >= 1440);
                boolean useHlsPlayer = torrent || isPortrait || is4k || castExcludeHistory;
                if (path.matches("/video/[a-f0-9]{32}")) {
                    String peer = socket.getRemoteSocketAddress().getAddress().getHostAddress();
                    main.post(() -> {
                        if (!canLaunch(socket)) return;
                        stopActivePlayerForNewCast();
                        var intent = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("http://" + peer + ":8766" + path),
                                libraryContext, useHlsPlayer ? CinemaHlsActivity.class
                                        : com.archos.mediacenter.video.player.PlayerActivity.class)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        long resumeMs = command.optLong("resume_ms", 0L);
                        String streamKey = command.optString("stream_key", "");
                        String sourceType = command.optString("source_type", "");
                        boolean excludeHistory = command.optBoolean("exclude_from_history", false)
                                || "phone_local_media".equals(sourceType);
                        String mediaType = command.optString("media_type", "");
                        String imdbId = command.optString("imdb_id", "");
                        long tmdbId = command.optLong("tmdb_id", 0L);
                        int season = command.optInt("season", 0);
                        int episode = command.optInt("episode", 0);
                        if (excludeHistory) {
                            resumeMs = 0L;
                            streamKey = "";
                        } else if (resumeMs <= 0 && useHlsPlayer) {
                            resumeMs = CinemaWatchStore.findBestStreamResumeMs(
                                    libraryContext, streamKey, mediaType, imdbId, tmdbId, season, episode, title);
                        }
                        intent.putExtra("exclude_from_history", excludeHistory)
                                .putExtra("source_type", sourceType)
                                .putExtra("is_live_cast", excludeHistory
                                        || (!"phone_library".equals(sourceType) && !"library".equals(sourceType)
                                                && imdbId.isEmpty() && tmdbId <= 0))
                                .putExtra("title", title)
                                .putExtra("stream_key", streamKey)
                                .putExtra("media_type", mediaType)
                                .putExtra("imdb_id", imdbId)
                                .putExtra("tmdb_id", tmdbId)
                                .putExtra("season", season)
                                .putExtra("episode", episode);
                        if (useHlsPlayer) {
                            String fmt = excludeHistory ? "phone_local_media"
                                    : (torrent ? "torrent" : (isPortrait ? "portrait_cast" : "adaptive_file"));
                            intent.putExtra("subtitle_language", command.optString("subtitle_language"))
                                    .putExtra("addon_subtitles", command.optBoolean("addon_subtitles"))
                                    .putExtra("format", fmt)
                                    .putExtra("video_width", vidW)
                                    .putExtra("video_height", vidH)
                                    .putExtra("video_rotation", vidRot)
                                    .putExtra("resume_ms", resumeMs);
                        } else if (excludeHistory) {
                            intent.putExtra(com.archos.mediacenter.video.player.PlayerActivity.RESUME,
                                    com.archos.mediacenter.video.player.PlayerService.RESUME_NO);
                        } else if (resumeMs > 0) {
                            intent.putExtra(com.archos.mediacenter.video.player.PlayerActivity.RESUME,
                                    com.archos.mediacenter.video.player.PlayerService.RESUME_FROM_LAST_POS);
                            intent.putExtra("position", (int) Math.min(Integer.MAX_VALUE, resumeMs));
                        }
                        launch(intent);
                    });
                }
            }
        } catch (org.json.JSONException | IllegalArgumentException error) {
            socket.close(1008);
        }
    }

    private static boolean validWebUrl(android.net.Uri uri) {
        String scheme = uri != null ? uri.getScheme() : null;
        return ("https".equals(scheme) || "http".equals(scheme)) && uri.getHost() != null && uri.getUserInfo() == null;
    }

    private void confirmWebPlayback(WebSocket socket, Activity previous, int attempt) {
        if (!socket.isOpen()) return;
        var player = activePlayer();
        boolean started = foreground instanceof CinemaHlsActivity hls
                ? hls.position() > 0 && hls.playing()
                : player != null && player.getCurrentPosition() > 0 && player.isPlaying();
        if (foreground != previous && started) {
            socket.send("{\"type\":\"WEB_CAST_STARTED\"}");
        } else if (attempt < 40) {
            main.postDelayed(() -> confirmWebPlayback(socket, previous, attempt + 1), 500);
        } else {
            socket.send("{\"type\":\"WEB_CAST_FAILED\"}");
        }
    }

    private com.archos.mediacenter.video.player.Player activePlayer() {
        return foreground instanceof com.archos.mediacenter.video.player.PlayerActivity
                ? com.archos.mediacenter.video.player.Player.sPlayer : null;
    }

    private android.widget.TextView volumeIndicator;
    private final Runnable removeVolumeIndicator = () -> {
        if (volumeIndicator != null && volumeIndicator.getParent() instanceof android.view.ViewGroup parent) {
            parent.removeView(volumeIndicator);
        }
        volumeIndicator = null;
    };

    private void showVolumeIndicator(int value) {
        if (!(foreground.getWindow().getDecorView() instanceof android.widget.FrameLayout decor)) return;
        if (volumeIndicator == null || volumeIndicator.getParent() != decor) {
            removeVolumeIndicator.run();
            volumeIndicator = CinemaUi.text(foreground, 16, true);
            volumeIndicator.setTextColor(CinemaTokens.GOLD);
            volumeIndicator.setBackground(CinemaUi.rounded(0xff1c1c1e, CinemaUi.dp(foreground, 16), CinemaTokens.GOLD));
            int padding = CinemaUi.dp(foreground, 12);
            volumeIndicator.setPadding(padding, padding, padding, padding);
            var position = new android.widget.FrameLayout.LayoutParams(-2, -2,
                    android.view.Gravity.TOP | android.view.Gravity.END);
            position.topMargin = padding * 2;
            position.setMarginEnd(padding * 2);
            decor.addView(volumeIndicator, position);
        }
        volumeIndicator.setText("♪  " + value + "%");
        main.removeCallbacks(removeVolumeIndicator);
        main.postDelayed(removeVolumeIndicator, 2000);
    }

    private void setPhoneVolume(WebSocket socket, int percentage, boolean muted) {
        if (!socket.isOpen() || foreground == null) return;
        var audio = foreground.getSystemService(android.media.AudioManager.class);
        int stream = android.media.AudioManager.STREAM_MUSIC;
        int min = android.os.Build.VERSION.SDK_INT >= 28 ? audio.getStreamMinVolume(stream) : 0;
        int max = audio.getStreamMaxVolume(stream);
        if (!audio.isVolumeFixed()) {
            int index = min + Math.round((max - min) * percentage / 100f);
            audio.setStreamVolume(stream, index, 0);
            if (audio.isStreamMute(stream) != (muted || percentage == 0)) {
                audio.adjustStreamVolume(stream, muted || percentage == 0
                        ? android.media.AudioManager.ADJUST_MUTE : android.media.AudioManager.ADJUST_UNMUTE, 0);
            }
        }
        if (!audio.isVolumeFixed()) showVolumeIndicator(audio.isStreamMute(stream) || max <= min ? 0
                : Math.round(100f * (audio.getStreamVolume(stream) - min) / (max - min)));
        sendPlayback(socket);
    }

    public static void playbackChanged() {
        CinemaLinkServer server = instance;
        if (server == null || server.phones.isEmpty()) return;
        server.main.removeCallbacks(server.broadcastPlayback);
        server.main.postDelayed(server.broadcastPlayback, 120);
    }

    private final Runnable broadcastPlayback = () -> phones.values().forEach(this::sendPlayback);

    private void sendPlayback(WebSocket socket) {
        if (foreground instanceof CinemaLocalMediaActivity media) {
            try {
                var state = media.audioState();
                if (state != null && socket.isOpen()) {
                    var manager = (android.media.AudioManager) media.getSystemService(android.content.Context.AUDIO_SERVICE);
                    int stream = android.media.AudioManager.STREAM_MUSIC;
                    state.put("volume", Math.round(100f * manager.getStreamVolume(stream) / Math.max(1, manager.getStreamMaxVolume(stream))));
                    state.put("muted", manager.isStreamMute(stream));
                    socket.send(state.toString());
                    return;
                }
            } catch (org.json.JSONException ignored) {}
        }
        if (!socket.isOpen()) return;
        var player = activePlayer();
        var audio = libraryContext.getSystemService(android.media.AudioManager.class);
        int stream = android.media.AudioManager.STREAM_MUSIC;
        int min = audio != null && android.os.Build.VERSION.SDK_INT >= 28 ? audio.getStreamMinVolume(stream) : 0;
        int max = audio == null ? min : audio.getStreamMaxVolume(stream);
        int volume = max <= min ? 0 : Math.round(100f * (audio.getStreamVolume(stream) - min) / (max - min));
        try {
            long posMs = foreground instanceof CinemaHlsActivity hls ? hls.position()
                    : player == null ? 0 : Math.max(0, player.getCurrentPosition());
            long durMs = foreground instanceof CinemaHlsActivity hls ? Math.max(0, hls.duration())
                    : player == null ? 0 : Math.max(0, player.getDuration());
            String pathStr = (foreground instanceof com.archos.mediacenter.video.player.PlayerActivity
                    || foreground instanceof CinemaHlsActivity) && foreground.getIntent().getData() != null
                    ? foreground.getIntent().getData().getPath() : "";
            String titleStr = foreground instanceof com.archos.mediacenter.video.player.PlayerActivity activity
                    ? activity.getCinemaPlaybackTitle() : foreground instanceof CinemaHlsActivity screen
                    ? screen.getIntent().getStringExtra("title") : "";
            boolean isPlaying = foreground instanceof CinemaHlsActivity hls ? hls.playing()
                    : player != null && player.isPlaying();
            boolean active = foreground instanceof CinemaHlsActivity hls ? hls.hasPlaybackSession()
                    : player != null && !foreground.isFinishing() && durMs > 0 && posMs < durMs;
            android.net.Uri mediaUri = active && foreground.getIntent() != null ? foreground.getIntent().getData() : null;

            String streamKey = "";
            String mediaType = "movie";
            String imdbId = "";
            long tmdbId = 0L;
            int season = 0;
            int episode = 0;
            boolean watched = durMs > 0 && posMs * 100L >= durMs * 85L;

            if (foreground instanceof CinemaHlsActivity hls) {
                var hi = hls.getIntent();
                String k = hi != null ? hi.getStringExtra("stream_key") : null;
                streamKey = (k != null && !k.isBlank()) ? k : (hi != null && hi.getData() != null ? hi.getData().toString() : "");
                String mt = hi != null ? hi.getStringExtra("media_type") : null;
                if (mt != null && !mt.isBlank()) mediaType = mt;
                String im = hi != null ? hi.getStringExtra("imdb_id") : null;
                if (im != null) imdbId = im;
                tmdbId = hi != null ? hi.getLongExtra("tmdb_id", 0L) : 0L;
                season = hi != null ? hi.getIntExtra("season", 0) : 0;
                episode = hi != null ? hi.getIntExtra("episode", 0) : 0;
                if (season > 0 || episode > 0) mediaType = "episode";
                CinemaWatchStore.StreamEntry se = CinemaWatchStore.getStream(libraryContext, streamKey);
                if (se != null) {
                    if (imdbId.isBlank() && se.imdbId() != null) imdbId = se.imdbId();
                    if (tmdbId <= 0) tmdbId = se.tmdbId();
                    if (se.watched()) watched = true;
                }
            } else if (foreground instanceof com.archos.mediacenter.video.player.PlayerActivity) {
                var ps = com.archos.mediacenter.video.player.PlayerService.sPlayerService;
                long vid = ps != null ? ps.getCurrentVideoId() : -1L;
                String uriStr = foreground.getIntent().getData() != null ? foreground.getIntent().getData().toString() : "";
                String cacheKey = vid + "|" + uriStr;
                CinemaWatchStore.PlaybackDescriptor desc = cachedDescriptor;
                if (desc == null || !cacheKey.equals(cachedDescriptorKey)) {
                    desc = CinemaWatchStore.resolvePlaybackDescriptor(libraryContext, vid, uriStr, titleStr);
                    cachedDescriptor = desc;
                    cachedDescriptorKey = cacheKey;
                }
                if (desc != null) {
                    streamKey = desc.streamKey();
                    mediaType = desc.mediaType();
                    imdbId = desc.imdbId();
                    tmdbId = desc.tmdbId();
                    season = desc.season();
                    episode = desc.episode();
                    if (desc.watched()) watched = true;
                }
            }

            boolean excludeHistory = foreground != null && foreground.getIntent() != null
                    && (foreground.getIntent().getBooleanExtra("exclude_from_history", false)
                    || "phone_local_media".equals(foreground.getIntent().getStringExtra("source_type")));
            String sourceType = foreground != null && foreground.getIntent() != null
                    ? foreground.getIntent().getStringExtra("source_type") : "";
            if (excludeHistory) {
                streamKey = "";
            }
            var subTracks = foreground instanceof CinemaHlsActivity hls ? hls.getSubtitleTracksJson()
                    : (foreground instanceof com.archos.mediacenter.video.player.PlayerActivity pa ? pa.getCinemaSubtitleTracksJson()
                    : new org.json.JSONArray());
            String poster = active && foreground instanceof CinemaHlsActivity
                    ? foreground.getIntent().getStringExtra("addon_poster") : null;
            socket.send(new JSONObject().put("type", "PLAYBACK")
                    .put("poster_url", poster == null ? "" : poster)
                    .put("volume", audio == null ? -1 : volume)
                    .put("muted", audio != null && audio.isStreamMute(stream))
                    .put("volumeFixed", audio != null && audio.isVolumeFixed())
                    .put("ms", posMs)
                    .put("positionMs", posMs)
                    .put("durationMs", durMs)
                    .put("isPlaying", isPlaying)
                    .put("active", active)
                    .put("photo", foreground instanceof CinemaLocalMediaActivity media && media.displayingPhoto())
                    .put("url", mediaUri == null ? "" : mediaUri.toString())
                    .put("live", foreground instanceof CinemaHlsActivity livePlayer && livePlayer.live())
                    .put("live_channel", foreground instanceof CinemaHlsActivity channel && channel.isCatalogChannel())
                    .put("duration", durMs)
                    .put("path", pathStr)
                    .put("title", titleStr == null ? "" : titleStr)
                    .put("playing", isPlaying)
                    .put("exclude_from_history", excludeHistory)
                    .put("source_type", sourceType == null ? "" : sourceType)
                    .put("stream_key", streamKey)
                    .put("media_type", mediaType)
                    .put("imdb_id", imdbId)
                    .put("tmdb_id", tmdbId)
                    .put("season", season)
                    .put("episode", episode)
                    .put("subtitle_tracks", subTracks)
                    .put("speed_percent", foreground instanceof CinemaHlsActivity hls ? hls.getCurrentSpeedPercent()
                            : (com.archos.mediacenter.video.player.PlayerService.sPlayerService != null
                            ? Math.round(com.archos.mediacenter.video.player.PlayerService.sPlayerService.getAudioSpeed() * 100f) : 100))
                    .put("rotation_degrees", foreground instanceof CinemaHlsActivity hls ? hls.getManualRotationDegrees() : 0)
                    .put("watched", watched).toString());
        } catch (org.json.JSONException | org.java_websocket.exceptions.WebsocketNotConnectedException ignored) {}
    }

    private void submitRemote(WebSocket socket, String text) {
        if (!activeRemote(socket)) return;
        if (foreground != null && foreground.hasWindowFocus()) {
            if (!text.isEmpty() && !paste(socket, text, false)) {
                socket.send("{\"type\":\"NO_FIELD\"}");
                return;
            }
            main.postDelayed(() -> {
                if (!activeRemote(socket)) return;
                boolean handled = dispatchKey(android.view.KeyEvent.KEYCODE_ENTER, false);
                if (!handled) handled = dispatchKey(android.view.KeyEvent.KEYCODE_SEARCH, false);
                if (!handled) handled = TvRemoteAccessibilityService.submitEditor();
                if (socket.isOpen()) socket.send(handled ? "{\"type\":\"SUBMITTED\"}" : "{\"type\":\"SUBMIT_UNAVAILABLE\"}");
            }, text.isEmpty() ? 0 : 100);
            return;
        }
        syncIo.execute(() -> {
            if (!activeRemote(socket)) return;
            boolean pasted = text.isEmpty() || pasteExternalSync(text);
            if (!pasted) {
                if (socket.isOpen()) {
                    socket.send((!TvLocalAdbBridge.isReady() && !TvRemoteAccessibilityService.available())
                            ? "{\"type\":\"REMOTE_ACCESSIBILITY_REQUIRED\"}"
                            : "{\"type\":\"NO_FIELD\"}");
                }
                return;
            }
            try { if (!text.isEmpty()) Thread.sleep(100); } catch (InterruptedException ignored) {}
            boolean submitted = (TvLocalAdbBridge.isReady() && TvLocalAdbBridge.sendKeyEvent(libraryContext, android.view.KeyEvent.KEYCODE_ENTER))
                    || TvRemoteAccessibilityService.submitEditor();
            if (socket.isOpen()) socket.send(submitted ? "{\"type\":\"SUBMITTED\"}" : "{\"type\":\"SUBMIT_UNAVAILABLE\"}");
        });
    }

    private android.content.Intent tvAppIntent(String pkg) {
        var manager = libraryContext.getPackageManager();
        var intent = manager.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : manager.getLaunchIntentForPackage(pkg);
    }

    private void sendTvApps(WebSocket socket) throws org.json.JSONException {
        if (!activeRemote(socket)) return;
        var manager = libraryContext.getPackageManager();
        var apps = new java.util.TreeMap<String, android.content.pm.ResolveInfo>();
        for (String category : new String[]{android.content.Intent.CATEGORY_LEANBACK_LAUNCHER,
                android.content.Intent.CATEGORY_LAUNCHER}) {
            var filter = new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(category);
            for (var available : manager.queryIntentActivities(filter, 0)) {
                apps.putIfAbsent(available.activityInfo.packageName, available);
            }
        }
        var list = new org.json.JSONArray();
        for (var entry : apps.entrySet()) {
            if (list.length() >= 80) break;
            var app = entry.getValue();
            var item = new JSONObject().put("package", entry.getKey()).put("label", app.loadLabel(manager).toString());
            var icon = app.loadIcon(manager);
            if (icon != null) {
                var bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888);
                var bounds = icon.copyBounds();
                icon.setBounds(0, 0, 32, 32);
                icon.draw(new android.graphics.Canvas(bitmap));
                icon.setBounds(bounds);
                var bytes = new java.io.ByteArrayOutputStream();
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes);
                bitmap.recycle();
                item.put("icon", android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP));
            }
            list.put(item);
        }
        if (activeRemote(socket)) socket.send(new JSONObject().put("type", "TV_APPS").put("apps", list).toString());
    }

    private void openTvApp(WebSocket socket, String pkg) {
        if (!activeRemote(socket) || !pkg.matches("[a-zA-Z0-9_.]{1,220}")) return;
        var intent = tvAppIntent(pkg);
        if (intent == null && "com.android.tv.settings".equals(pkg)) {
            intent = new android.content.Intent(android.provider.Settings.ACTION_SETTINGS);
        }
        if (intent == null) return;
        try {
            if (foreground != null && foreground.hasWindowFocus()) {
                launch(intent);
                return;
            }
        } catch (android.content.ActivityNotFoundException | SecurityException error) {
            socket.send("{\"type\":\"TV_APP_UNAVAILABLE\"}");
            return;
        }
        final var fallbackIntent = intent;
        syncIo.execute(() -> {
            if (TvLocalAdbBridge.isReady() && TvLocalAdbBridge.launchPackage(libraryContext, pkg)) {
                android.util.Log.d("TV_REMOTE_RX", "Opened TV app via local ADB " + pkg);
                return;
            }
            main.post(() -> {
                try {
                    if (TvRemoteAccessibilityService.launchApp(fallbackIntent)) return;
                    if (canLaunch(socket)) {
                        launch(fallbackIntent);
                        return;
                    }
                    if (!TvRemoteAccessibilityService.available() && !TvLocalAdbBridge.isReady()) {
                        socket.send("{\"type\":\"REMOTE_ACCESSIBILITY_REQUIRED\"}");
                    } else {
                        socket.send("{\"type\":\"TV_APP_UNAVAILABLE\"}");
                    }
                } catch (android.content.ActivityNotFoundException | SecurityException error) {
                    socket.send("{\"type\":\"TV_APP_UNAVAILABLE\"}");
                }
            });
        });
    }

    static void audioEnded(String path) {
        var server = instance;
        if (server == null) return;
        var peer = server.phones.get(server.prefs.getString("active", ""));
        if (peer == null || !peer.isOpen()) return;
        try {
            peer.send(new JSONObject().put("type", "TRACK_ENDED").put("path", path).toString());
            android.util.Log.i("CinemaAudio", "STATE_ENDED sent to active controller");
        } catch (org.json.JSONException | org.java_websocket.exceptions.WebsocketNotConnectedException ignored) {}
    }

    private boolean activeRemote(WebSocket socket) {
        String id = socket.getAttachment();
        return isWebRemoteEnabled(libraryContext)
                && socket.isOpen() && id != null && phones.get(id) == socket
                && id.equals(prefs.getString("active", ""));
    }

    private boolean remotePlayback(WebSocket socket, int key) {
        if (key != 85 && key != 89 && key != 90) return false;
        String action = key == 85 ? "TOGGLE_PLAY" : "JUMP";
        int offset = key == 89 ? -10000 : key == 90 ? 10000 : 0;
        if (foreground instanceof CinemaLocalMediaActivity media) {
            try {
                media.audioCommand(action, offset);
            } catch (IllegalStateException error) {
                android.util.Log.w("TV_REMOTE_RX", "Audio playback command unavailable");
            }
        } else if (foreground instanceof CinemaHlsActivity || activePlayer() != null) {
            handleCommand(socket, "{\"type\":\"" + action + "\",\"ms\":" + offset + "}");
        } else return false;
        return true;
    }

    private boolean dispatchKey(int key, boolean held) {
        return dispatchKey(key, held, true);
    }

    private boolean dispatchKey(int key, boolean held, boolean allowAdb) {
        Activity activity = foreground;
        if (key == 85 || key == 89 || key == 90) {
            var audio = libraryContext.getSystemService(android.media.AudioManager.class);
            long time = android.os.SystemClock.uptimeMillis();
            audio.dispatchMediaKeyEvent(new android.view.KeyEvent(time, time, 0, key, 0));
            audio.dispatchMediaKeyEvent(new android.view.KeyEvent(time, time, 1, key, 0));
            return true;
        }
        if (key == 24 || key == 25 || key == 164) {
            var audio = libraryContext.getSystemService(android.media.AudioManager.class);
            audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                    key == 24 ? android.media.AudioManager.ADJUST_RAISE
                    : key == 25 ? android.media.AudioManager.ADJUST_LOWER
                    : android.media.AudioManager.ADJUST_TOGGLE_MUTE,
                    android.media.AudioManager.FLAG_SHOW_UI);
            return true;
        }
        if (key == android.view.KeyEvent.KEYCODE_HOME) {
            if (!held && (allowAdb && TvLocalAdbBridge.isReady())) {
                dispatchAdbKeyWithAccFallback(key, held);
                return true;
            }
            if (TvRemoteAccessibilityService.dispatch(key, held)) return true;
            if (activity == null) return false;
            activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME));
            return true;
        }
        if (key == android.view.KeyEvent.KEYCODE_SETTINGS) {
            if (!held && (allowAdb && TvLocalAdbBridge.isReady())) {
                dispatchAdbKeyWithAccFallback(key, held);
                return true;
            }
            if (TvRemoteAccessibilityService.dispatch(key, held)) return true;
            try {
                libraryContext.startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception ignored) {}
            injectSystemKeyAsync(key);
            return true;
        }
        if (key == android.view.KeyEvent.KEYCODE_MENU) {
            if (activity instanceof CinemaHlsActivity hls && activity.hasWindowFocus()) {
                hls.openSubtitleMenuFromRemote();
                return true;
            }
            if (activity != null && activity.hasWindowFocus()) {
                long t = android.os.SystemClock.uptimeMillis();
                boolean handled = activity.dispatchKeyEvent(new android.view.KeyEvent(t, t, 0, key, 0));
                handled |= activity.dispatchKeyEvent(new android.view.KeyEvent(t, t + 15, 1, key, 0));
                if (handled) return true;
            }
            if (!held && (allowAdb && TvLocalAdbBridge.isReady())) {
                dispatchAdbKeyWithAccFallback(key, held);
                return true;
            }
            if (TvRemoteAccessibilityService.dispatch(key, held)) return true;
            injectSystemKeyAsync(key);
            return true;
        }
        if (key == android.view.KeyEvent.KEYCODE_TV_INPUT || key == android.view.KeyEvent.KEYCODE_INFO
                || key == android.view.KeyEvent.KEYCODE_SEARCH) {
            if (activity != null && activity.hasWindowFocus()) {
                long t = android.os.SystemClock.uptimeMillis();
                boolean handled = activity.dispatchKeyEvent(new android.view.KeyEvent(t, t, 0, key, 0));
                handled |= activity.dispatchKeyEvent(new android.view.KeyEvent(t, t + 15, 1, key, 0));
                if (handled) return true;
            }
            if (!held && (allowAdb && TvLocalAdbBridge.isReady())) {
                dispatchAdbKeyWithAccFallback(key, held);
                return true;
            }
            if (TvRemoteAccessibilityService.dispatch(key, held)) return true;
            injectSystemKeyAsync(key);
            return true;
        }
        if (activity == null || !activity.hasWindowFocus()) {
            android.view.View dialogOrPopup = activity != null ? findFocusedInAppWindow() : null;
            if (dialogOrPopup != null) {
                return dispatchKeyToViewRoot(dialogOrPopup, key, held);
            }
            if (!held && (key >= 19 && key <= 23 || key == 4 || key == 66)
                    && TvRemoteAccessibilityService.dispatch(key, false)) {
                return true;
            }
            if ((allowAdb && TvLocalAdbBridge.isReady())) {
                dispatchAdbKeyWithAccFallback(key, held);
                return true;
            }
            if (!held && (key >= 19 && key <= 23 || key == 4 || key == 66)
                    && TvRemoteImeService.send(key)) return true;
            return TvRemoteAccessibilityService.dispatch(key, held);
        }
        if (key == android.view.KeyEvent.KEYCODE_BACK) {
            activity.onBackPressed();
            return true;
        }
        int direction = switch (key) {
            case 19 -> android.view.View.FOCUS_UP;
            case 20 -> android.view.View.FOCUS_DOWN;
            case 21 -> android.view.View.FOCUS_LEFT;
            case 22 -> android.view.View.FOCUS_RIGHT;
            default -> 0;
        };
        if (direction == 0 && key != 23 && key != 66 && key != 67 && key != 82 && key != 84) {
            return TvRemoteAccessibilityService.dispatch(key, held);
        }
        return dispatchKeyToViewRoot(activity.getWindow().getDecorView(), key, held);
    }

    private android.view.View findFocusedInAppWindow() {
        try {
            java.util.List<android.view.View> views = null;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                views = android.view.inspector.WindowInspector.getGlobalWindowViews();
            } else {
                Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
                Object wmg = wmgClass.getMethod("getInstance").invoke(null);
                @SuppressWarnings("unchecked")
                java.util.List<android.view.View> list = (java.util.List<android.view.View>) wmgClass.getMethod("getWindowViews").invoke(wmg);
                views = list;
            }
            if (views != null) {
                for (int i = views.size() - 1; i >= 0; i--) {
                    android.view.View v = views.get(i);
                    if (v != null && v.isAttachedToWindow() && v.getVisibility() == android.view.View.VISIBLE && v.hasWindowFocus()) {
                        return v;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean dispatchKeyToViewRoot(android.view.View rootView, int key, boolean held) {
        if (rootView == null) return false;
        if (held && key == android.view.KeyEvent.KEYCODE_DPAD_CENTER) {
            for (android.view.View view = rootView.findFocus(); view != null; ) {
                if (view.isEnabled() && view.isLongClickable() && view.performLongClick()) return true;
                view = view.getParent() instanceof android.view.View parent ? parent : null;
            }
        }
        int direction = switch (key) {
            case 19 -> android.view.View.FOCUS_UP;
            case 20 -> android.view.View.FOCUS_DOWN;
            case 21 -> android.view.View.FOCUS_LEFT;
            case 22 -> android.view.View.FOCUS_RIGHT;
            default -> 0;
        };
        long down = android.os.SystemClock.uptimeMillis();
        android.view.View focused = rootView.findFocus();
        boolean handled = rootView.dispatchKeyEvent(new android.view.KeyEvent(down, down, 0, key, 0));
        if (!held) {
            boolean released = releaseViewKey(rootView, key, down);
            if (!handled && !released && direction != 0) {
                if (focused == null) return rootView.restoreDefaultFocus();
                android.view.View next = focused.focusSearch(direction);
                if (next != null && next != focused) return next.requestFocusFromTouch();
            }
            return handled || released;
        }
        int timeout = android.view.ViewConfiguration.getLongPressTimeout();
        main.postDelayed(() -> {
            if (!rootView.isAttachedToWindow() || !rootView.hasWindowFocus()) return;
            long now = android.os.SystemClock.uptimeMillis();
            rootView.dispatchKeyEvent(new android.view.KeyEvent(down, now, 0, key, 1,
                    0, -1, 0, android.view.KeyEvent.FLAG_LONG_PRESS));
        }, timeout);
        main.postDelayed(() -> releaseViewKey(rootView, key, down), timeout + 80L);
        return handled;
    }

    private boolean releaseViewKey(android.view.View rootView, int key, long down) {
        if (rootView == null || !rootView.isAttachedToWindow()) return false;
        long now = android.os.SystemClock.uptimeMillis();
        int flags = rootView.hasWindowFocus() ? 0 : android.view.KeyEvent.FLAG_CANCELED;
        return rootView.dispatchKeyEvent(new android.view.KeyEvent(down, now, 1, key, 0, 0, -1, 0, flags));
    }

    private void injectSystemKeyAsync(int keyCode) {
        remoteIo.execute(() -> {
            try {
                new android.app.Instrumentation().sendKeyDownUpSync(keyCode);
            } catch (Throwable ignored) {}
        });
    }

    private void dispatchAdbKeyWithAccFallback(int key, boolean held) {
        remoteIo.execute(() -> {
            if (!TvLocalAdbBridge.sendKeyEvent(libraryContext, key, held)) {
                main.post(() -> {
                    if (!TvRemoteAccessibilityService.dispatch(key, held) && !TvRemoteAccessibilityService.available()) {
                        TvRemotePermissionKeeper.ensureAccessibilityEnabled(libraryContext);
                    }
                    broadcastRemoteStatus(libraryContext);
                });
            }
        });
    }

    private boolean pasteExternalSync(String text) {
        if (TvLocalAdbBridge.isReady() && TvLocalAdbBridge.sendTextAscii(libraryContext, text)) {
            return true;
        }
        return TvRemoteAccessibilityService.typeText(text);
    }

    private void editRemoteText(int move, int delete, String text) {
        EditText field = dialogInput.get();
        if (field == null || !field.hasWindowFocus() || !field.isShown()) {
            field = foreground != null && foreground.getCurrentFocus() instanceof EditText edit ? edit : null;
        }
        if (field != null && field.hasWindowFocus() && field.isEnabled()) {
            var value = field.getText();
            int caret = Math.max(0, field.getSelectionEnd());
            int start = Math.max(0, Math.min(value.length(), caret + move));
            int end = Math.min(value.length(), start + delete);
            if (move == 0 && delete == 0 && !text.isEmpty() && field.getSelectionStart() >= 0) {
                start = Math.min(field.getSelectionStart(), caret);
                end = Math.max(field.getSelectionStart(), caret);
            }
            value.replace(start, end, text);
            field.setSelection(Math.min(value.length(), start + text.length()));
        } else if (!TvRemoteImeService.edit(move, delete, text)) {
            TvRemoteAccessibilityService.editText(move, delete, text);
        }
    }

    private boolean paste(WebSocket socket, String text, boolean manual) {
        EditText field = dialogInput.get();
        if (field == null || !field.hasWindowFocus() || !field.isShown()) {
            field = foreground != null && foreground.getCurrentFocus() instanceof EditText input ? input : null;
        }
        if (field == null && foreground != null) {
            var search = foreground.findViewById(androidx.leanback.R.id.lb_search_text_editor);
            if (search instanceof EditText input && input.isShown()) field = input;
        }
        boolean accepted = field != null && field.hasWindowFocus() && field.isEnabled();
        if (accepted) {
            if (!android.text.TextUtils.equals(field.getText(), text)) {
                field.setText(text);
                field.setSelection(field.length());
            }
            if (manual) field.getContext().getSystemService(android.view.inputmethod.InputMethodManager.class)
                    .hideSoftInputFromWindow(field.getWindowToken(), 0);
            if (manual && socket.isOpen()) socket.send("{\"type\":\"PASTED\"}");
            return true;
        }
        if (TvLocalAdbBridge.isReady()) {
            if (manual) {
                syncIo.execute(() -> {
                    boolean ok = pasteExternalSync(text);
                    if (socket.isOpen()) socket.send(ok ? "{\"type\":\"PASTED\"}"
                            : !TvRemoteAccessibilityService.available() && !TvLocalAdbBridge.isReady()
                            ? "{\"type\":\"REMOTE_ACCESSIBILITY_REQUIRED\"}" : "{\"type\":\"NO_FIELD\"}");
                });
                return true;
            }
            return pasteExternalSync(text);
        }
        accepted = TvRemoteAccessibilityService.typeText(text);
        if (manual && socket.isOpen()) socket.send(accepted ? "{\"type\":\"PASTED\"}"
                : !TvRemoteAccessibilityService.available() && (foreground == null || !foreground.hasWindowFocus())
                ? "{\"type\":\"REMOTE_ACCESSIBILITY_REQUIRED\"}" : "{\"type\":\"NO_FIELD\"}");
        return accepted;
    }
}

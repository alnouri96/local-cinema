package com.meedx.providers;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Zero-hardcoded-secrets Trakt v2 client supporting Device OAuth Flow (PKCE),
 * token refresh, scrobble, and two-way sync using encrypted ProviderKeys & trakt_sync_prefs.
 */
public final class TraktSyncClient {
    private static final String TAG = "TraktSyncClient";
    private static final String API = "https://api.trakt.tv";
    private static final String CONFIG_URL = "https://meedx.pages.dev/api/config.json";
    public static final String DEFAULT_PUBLIC_CLIENT_ID = "ysZZKQ4X6LcHghcKf1ReOIsuzELNIcygal7ZUBYSwSE";
    public static final String PKCE_REDIRECT_URI = "localcinema://trakt-callback";
    private static final String TYPO_PUBLIC_CLIENT_ID = "ysZZKQ4X6LcHghckf1ReOIsuzELNIcyga17ZUBYSwSE";
    private static final ExecutorService DEVICE_AUTH_IO = Executors.newCachedThreadPool();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Object DEVICE_POLL_LOCK = new Object();
    private static final CopyOnWriteArrayList<DeviceAuthListener> AUTH_LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile DeviceAuthSnapshot currentAuthSnapshot =
            new DeviceAuthSnapshot(DeviceAuthState.IDLE, "", "", "", "", "", 0L, 5, "");
    private static volatile ActiveAuthSession activeAuthSession;
    private static volatile boolean immediatePollRequested;
    private static volatile String lastHandledPkceCode = "";
    private static final String ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    public enum DeviceAuthState { IDLE, REQUESTING, PENDING, AUTHORIZED, EXPIRED, DENIED, ERROR, CANCELLED }

    public static final class DeviceAuthSnapshot {
        public final DeviceAuthState state;
        public final String userCode;
        public final String verificationUrl;
        public final String username;
        public final String slug;
        public final String errorMessage;
        public final long expiresAtMs;
        public final int intervalSec;
        public final String clientId;

        public DeviceAuthSnapshot(DeviceAuthState state, String userCode, String verificationUrl,
                                  String username, String slug, String errorMessage,
                                  long expiresAtMs, int intervalSec, String clientId) {
            this.state = state;
            this.userCode = userCode == null ? "" : userCode;
            this.verificationUrl = verificationUrl == null ? "" : verificationUrl;
            this.username = username == null ? "" : username;
            this.slug = slug == null ? "" : slug;
            this.errorMessage = errorMessage == null ? "" : errorMessage;
            this.expiresAtMs = expiresAtMs;
            this.intervalSec = intervalSec;
            this.clientId = clientId == null ? "" : clientId;
        }
    }

    public interface DeviceAuthListener {
        void onDeviceAuthStateChanged(DeviceAuthSnapshot snapshot);
    }

    private static final class ActiveAuthSession {
        final long id;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile String deviceCode = "";
        ActiveAuthSession(long id) { this.id = id; }
    }

    public static final class TraktHttpException extends IOException {
        public final int statusCode;
        public final String serverBody;
        public TraktHttpException(int statusCode, String serverBody) {
            super(serverBody != null && !serverBody.isBlank()
                    ? ("Trakt HTTP " + statusCode + ": " + serverBody)
                    : ("Trakt HTTP " + statusCode));
            this.statusCode = statusCode;
            this.serverBody = serverBody == null ? "" : serverBody;
        }
    }

    public static final class DeviceCode {
        public final String deviceCode;
        public final String userCode;
        public final String verificationUrl;
        public final int expiresIn;
        public final int intervalSec;

        public DeviceCode(String deviceCode, String userCode, String verificationUrl, int expiresIn, int intervalSec) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUrl = verificationUrl;
            this.expiresIn = expiresIn;
            this.intervalSec = intervalSec;
        }
    }

    public enum PollResult { AUTHORIZED, PENDING, SLOW_DOWN, EXPIRED, DENIED }

    /** Thrown when neither a local Client ID nor a cloud config Client ID is available yet. */
    public static final class MissingClientIdException extends IOException {
        public MissingClientIdException() {
            super("Trakt Client ID is not configured");
        }
    }

    public static final class SyncItem {
        public final String localKey;
        public final String mediaType;
        public final String imdbId;
        public final long tmdbId;
        public final int season;
        public final int episode;
        public final long positionMs;
        public final long durationMs;
        public final boolean watched;
        public final boolean removed;
        public final long updatedAtMs;

        public SyncItem(String localKey, String mediaType, String imdbId, long tmdbId,
                        int season, int episode, long positionMs, long durationMs,
                        boolean watched, boolean removed, long updatedAtMs) {
            this.localKey = localKey;
            this.mediaType = mediaType;
            this.imdbId = imdbId;
            this.tmdbId = tmdbId;
            this.season = season;
            this.episode = episode;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.watched = watched;
            this.removed = removed;
            this.updatedAtMs = updatedAtMs;
        }
    }

    private TraktSyncClient() {}

    public static String normalizeClientId(String raw) {
        return DEFAULT_PUBLIC_CLIENT_ID;
    }

    public static void migrateAndCleanCredentials(Context context) {
        if (context == null) return;
        ProviderKeys.migrateTraktCredentials(context.getApplicationContext());
    }

    public static String maskClientId(String raw) {
        String clean = normalizeClientId(raw);
        if (clean.length() <= 10) return clean;
        return clean.substring(0, 6) + "…" + clean.substring(clean.length() - 4);
    }

    public static String getEffectiveClientId(Context context) {
        if (context != null) {
            migrateAndCleanCredentials(context.getApplicationContext());
        }
        return DEFAULT_PUBLIC_CLIENT_ID;
    }

    public static boolean isCustomClientId(Context context) {
        return false;
    }

    public static String getConnectedUsername(Context context) {
        if (context == null) return "";
        return ProviderKeys.get(context.getApplicationContext(), ProviderKeys.TRAKT_USERNAME);
    }

    public static String getConnectedSlug(Context context) {
        if (context == null) return "";
        return ProviderKeys.get(context.getApplicationContext(), ProviderKeys.TRAKT_SLUG);
    }

    public static boolean hasCredentials(Context context) {
        return true;
    }

    public static synchronized String resolveClientId(Context context) {
        if (context != null) {
            Context app = context.getApplicationContext();
            migrateAndCleanCredentials(app);
            ProviderKeys.save(app, ProviderKeys.TRAKT_ID, DEFAULT_PUBLIC_CLIENT_ID);
            ProviderKeys.save(app, ProviderKeys.TRAKT_SECRET, "");
        }
        return DEFAULT_PUBLIC_CLIENT_ID;
    }

    public static boolean isConnected(Context context) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        migrateAndCleanCredentials(app);
        return hasCredentials(app)
                && !ProviderKeys.get(app, ProviderKeys.TRAKT_ACCESS_TOKEN).isBlank();
    }

    public static void signOut(Context context) {
        cancelDeviceAuth();
        if (context == null) return;
        Context app = context.getApplicationContext();
        ProviderKeys.save(app, ProviderKeys.TRAKT_ACCESS_TOKEN, "");
        ProviderKeys.save(app, ProviderKeys.TRAKT_REFRESH_TOKEN, "");
        ProviderKeys.save(app, ProviderKeys.TRAKT_EXPIRES_AT, "");
        ProviderKeys.save(app, ProviderKeys.TRAKT_USERNAME, "");
        ProviderKeys.save(app, ProviderKeys.TRAKT_SLUG, "");
        ProviderKeys.save(app, ProviderKeys.TRAKT_PKCE_VERIFIER, "");
        ProviderKeys.save(app, ProviderKeys.TRAKT_PKCE_STATE, "");
    }

    private static String randomAlphanum(int len) {
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHANUM.charAt(rng.nextInt(ALPHANUM.length())));
        }
        return sb.toString();
    }

    /** Generates a new OAuth 2.0 PKCE authorize URL (S256) and persists 64-char verifier/state via .commit(). */
    public static String createPkceAuthorizeUrl(Context context) {
        Context app = context.getApplicationContext();
        migrateAndCleanCredentials(app);
        cancelDeviceAuth();
        ProviderKeys.save(app, ProviderKeys.TRAKT_ID, DEFAULT_PUBLIC_CLIENT_ID);
        ProviderKeys.save(app, ProviderKeys.TRAKT_SECRET, "");

        String verifier = randomAlphanum(64);
        String state = randomAlphanum(32);
        int flags = Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;
        String challenge;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            challenge = Base64.encodeToString(digest, flags);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable for PKCE", e);
        }
        ProviderKeys.save(app, ProviderKeys.TRAKT_PKCE_VERIFIER, verifier);
        ProviderKeys.save(app, ProviderKeys.TRAKT_PKCE_STATE, state);
        lastHandledPkceCode = "";

        return "https://trakt.tv/oauth/authorize?response_type=code"
                + "&client_id=" + Uri.encode(DEFAULT_PUBLIC_CLIENT_ID)
                + "&redirect_uri=" + Uri.encode(PKCE_REDIRECT_URI)
                + "&state=" + Uri.encode(state)
                + "&code_challenge=" + Uri.encode(challenge)
                + "&code_challenge_method=S256";
    }

    /** Completes OAuth 2.0 PKCE callback (`localcinema://trakt-callback?code=...&state=...`) and emits listener snapshot. */
    public static void handlePkceCallbackAsync(Context context, Uri callbackUri, Runnable onAuthorizedBgHook) {
        if (context == null || callbackUri == null) return;
        final Context app = context.getApplicationContext();
        final String err = callbackUri.getQueryParameter("error");
        final String errDesc = callbackUri.getQueryParameter("error_description");
        final String code = callbackUri.getQueryParameter("code");
        final String state = callbackUri.getQueryParameter("state");

        if (err != null && !err.isBlank()) {
            String msg = (errDesc != null && !errDesc.isBlank()) ? errDesc : ("Trakt authorization rejected: " + err);
            emitAuthSnapshot(new DeviceAuthSnapshot(DeviceAuthState.DENIED, "", "", "", "", msg, 0L, 5, DEFAULT_PUBLIC_CLIENT_ID));
            return;
        }
        if (code == null || code.isBlank()) {
            emitAuthSnapshot(new DeviceAuthSnapshot(DeviceAuthState.ERROR, "", "", "", "",
                    "Missing authorization code in callback", 0L, 5, DEFAULT_PUBLIC_CLIENT_ID));
            return;
        }
        final String cleanCode = code.trim();
        if (cleanCode.equals(lastHandledPkceCode)) {
            return;
        }
        lastHandledPkceCode = cleanCode;

        emitAuthSnapshot(new DeviceAuthSnapshot(DeviceAuthState.REQUESTING, "", "", "", "", "", 0L, 5, DEFAULT_PUBLIC_CLIENT_ID));
        DEVICE_AUTH_IO.execute(() -> {
            try {
                migrateAndCleanCredentials(app);
                String expectedState = ProviderKeys.get(app, ProviderKeys.TRAKT_PKCE_STATE);
                String verifier = ProviderKeys.get(app, ProviderKeys.TRAKT_PKCE_VERIFIER);
                ProviderKeys.save(app, ProviderKeys.TRAKT_PKCE_STATE, "");
                ProviderKeys.save(app, ProviderKeys.TRAKT_PKCE_VERIFIER, "");

                if (!expectedState.isBlank() && state != null && !state.isBlank() && !expectedState.equals(state.trim())) {
                    throw new IOException("Trakt login state mismatch. Please tap Connect via Browser again.");
                }
                if (verifier.isBlank()) {
                    throw new IOException("PKCE session expired. Please tap Connect via Browser again.");
                }

                JSONObject res = exchangePkceCode(cleanCode, verifier);
                saveTokenResponse(app, DEFAULT_PUBLIC_CLIENT_ID, res);

                String username = getConnectedUsername(app);
                String slug = getConnectedSlug(app);
                if (onAuthorizedBgHook != null) {
                    try { onAuthorizedBgHook.run(); } catch (Exception ignored) {}
                }
                emitAuthSnapshot(new DeviceAuthSnapshot(
                        DeviceAuthState.AUTHORIZED, "", "", username, slug, "",
                        System.currentTimeMillis() + 7776000_000L, 5, DEFAULT_PUBLIC_CLIENT_ID));
            } catch (Exception ex) {
                Log.w(TAG, "PKCE token exchange failed: " + ex.getMessage());
                emitAuthSnapshot(new DeviceAuthSnapshot(
                        DeviceAuthState.ERROR, "", "", "", "",
                        ex.getMessage() != null ? ex.getMessage() : "Trakt PKCE exchange failed",
                        0L, 5, DEFAULT_PUBLIC_CLIENT_ID));
            }
        });
    }

    private static JSONObject exchangePkceCode(String cleanCode, String verifier) throws Exception {
        JSONObject req = new JSONObject()
                .put("code", cleanCode)
                .put("client_id", DEFAULT_PUBLIC_CLIENT_ID)
                .put("redirect_uri", PKCE_REDIRECT_URI)
                .put("grant_type", "authorization_code")
                .put("code_verifier", verifier);
        String jsonBody = req.toString();
        String formBody = "code=" + Uri.encode(cleanCode)
                + "&client_id=" + Uri.encode(DEFAULT_PUBLIC_CLIENT_ID)
                + "&redirect_uri=" + Uri.encode(PKCE_REDIRECT_URI)
                + "&grant_type=authorization_code"
                + "&code_verifier=" + Uri.encode(verifier);

        Exception firstErr = null;
        String[] endpoints = {
                API + "/oauth/token",
                "https://auth.trakt.tv/oauth/token",
                "https://trakt.tv/oauth/token"
        };
        for (String ep : endpoints) {
            try {
                String raw = request("POST", ep, DEFAULT_PUBLIC_CLIENT_ID, null, jsonBody);
                return parseJsonObjectStrict(raw, "Invalid token response from Trakt");
            } catch (Exception e) {
                if (firstErr == null) firstErr = e;
            }
            try {
                String rawForm = requestForm("POST", ep, DEFAULT_PUBLIC_CLIENT_ID, formBody);
                return parseJsonObjectStrict(rawForm, "Invalid token response from Trakt");
            } catch (Exception ignored) {}
        }
        throw firstErr != null ? firstErr : new IOException("Trakt PKCE token exchange failed");
    }

    public static DeviceAuthSnapshot getDeviceAuthSnapshot() {
        return currentAuthSnapshot;
    }

    public static void subscribeDeviceAuth(DeviceAuthListener listener) {
        if (listener != null) AUTH_LISTENERS.addIfAbsent(listener);
    }

    public static void unsubscribeDeviceAuth(DeviceAuthListener listener) {
        if (listener != null) AUTH_LISTENERS.remove(listener);
    }

    private static void emitAuthSnapshot(DeviceAuthSnapshot snap) {
        currentAuthSnapshot = snap;
        MAIN_HANDLER.post(() -> {
            for (DeviceAuthListener l : AUTH_LISTENERS) {
                try { l.onDeviceAuthStateChanged(snap); } catch (Exception ignored) {}
            }
        });
    }

    public static void triggerImmediateDevicePoll() {
        ActiveAuthSession cur = activeAuthSession;
        if (cur == null || cur.cancelled.get() || cur.deviceCode.isBlank()) return;
        synchronized (DEVICE_POLL_LOCK) {
            immediatePollRequested = true;
            DEVICE_POLL_LOCK.notifyAll();
        }
    }

    public static void cancelDeviceAuth() {
        ActiveAuthSession cur = activeAuthSession;
        activeAuthSession = null;
        if (cur != null) {
            cur.cancelled.set(true);
            synchronized (DEVICE_POLL_LOCK) {
                DEVICE_POLL_LOCK.notifyAll();
            }
        }
        if (currentAuthSnapshot.state == DeviceAuthState.PENDING
                || currentAuthSnapshot.state == DeviceAuthState.REQUESTING) {
            emitAuthSnapshot(new DeviceAuthSnapshot(DeviceAuthState.CANCELLED, "", "", "", "", "", 0L, 5, ""));
        }
    }

    /**
     * ApplicationContext-scoped persistent Trakt Device OAuth runner.
     * Continues running cleanly when the user switches to the browser and checks immediately on return.
     */
    public static void startBackgroundDeviceAuth(Context context, Runnable onAuthorizedBgHook) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        final ActiveAuthSession prev = activeAuthSession;
        if (prev != null) prev.cancelled.set(true);
        final ActiveAuthSession session = new ActiveAuthSession(System.nanoTime());
        activeAuthSession = session;
        synchronized (DEVICE_POLL_LOCK) {
            immediatePollRequested = false;
            DEVICE_POLL_LOCK.notifyAll();
        }
        emitAuthSnapshot(new DeviceAuthSnapshot(DeviceAuthState.REQUESTING, "", "", "", "", "", 0L, 5, getEffectiveClientId(app)));

        DEVICE_AUTH_IO.execute(() -> {
            try {
                String clientId = resolveClientId(app);
                DeviceCode dc = requestDeviceCode(app);
                if (session.cancelled.get() || activeAuthSession != session) return;
                session.deviceCode = dc.deviceCode;
                long deadlineMs = System.currentTimeMillis() + (dc.expiresIn * 1000L);
                long intervalMs = Math.max(3, dc.intervalSec) * 1000L;
                emitAuthSnapshot(new DeviceAuthSnapshot(
                        DeviceAuthState.PENDING, dc.userCode, dc.verificationUrl,
                        "", "", "", deadlineMs, dc.intervalSec, clientId));

                int consecutiveErrors = 0;
                while (!session.cancelled.get() && activeAuthSession == session
                        && System.currentTimeMillis() < deadlineMs) {
                    synchronized (DEVICE_POLL_LOCK) {
                        if (!immediatePollRequested) {
                            long remaining = Math.max(0L, deadlineMs - System.currentTimeMillis());
                            long waitMs = Math.min(intervalMs, remaining);
                            if (waitMs > 0L) {
                                try {
                                    DEVICE_POLL_LOCK.wait(waitMs);
                                } catch (InterruptedException ignored) {
                                    return;
                                }
                            }
                        }
                        immediatePollRequested = false;
                    }
                    if (session.cancelled.get() || activeAuthSession != session) return;
                    if (System.currentTimeMillis() >= deadlineMs) break;

                    try {
                        PollResult res = pollDeviceToken(app, dc.deviceCode);
                        consecutiveErrors = 0;
                        if (res == PollResult.AUTHORIZED) {
                            String username = getConnectedUsername(app);
                            String slug = getConnectedSlug(app);
                            activeAuthSession = null;
                            if (onAuthorizedBgHook != null) {
                                try { onAuthorizedBgHook.run(); } catch (Exception ignored) {}
                            }
                            emitAuthSnapshot(new DeviceAuthSnapshot(
                                    DeviceAuthState.AUTHORIZED, dc.userCode, dc.verificationUrl,
                                    username, slug, "", deadlineMs, dc.intervalSec, clientId));
                            return;
                        } else if (res == PollResult.SLOW_DOWN) {
                            intervalMs = Math.min(30_000L, intervalMs * 2L);
                        } else if (res == PollResult.EXPIRED) {
                            activeAuthSession = null;
                            emitAuthSnapshot(new DeviceAuthSnapshot(
                                    DeviceAuthState.EXPIRED, dc.userCode, dc.verificationUrl,
                                    "", "", "Activation code expired", deadlineMs, dc.intervalSec, clientId));
                            return;
                        } else if (res == PollResult.DENIED) {
                            activeAuthSession = null;
                            emitAuthSnapshot(new DeviceAuthSnapshot(
                                    DeviceAuthState.DENIED, dc.userCode, dc.verificationUrl,
                                    "", "", "Authorization was denied", deadlineMs, dc.intervalSec, clientId));
                            return;
                        }
                    } catch (Exception pollErr) {
                        consecutiveErrors++;
                        Log.w(TAG, "Device token poll error (" + consecutiveErrors + "): " + pollErr.getMessage());
                        if (consecutiveErrors > 2) {
                            activeAuthSession = null;
                            String msg = pollErr.getMessage() != null ? pollErr.getMessage() : "Trakt poll error";
                            emitAuthSnapshot(new DeviceAuthSnapshot(
                                    DeviceAuthState.ERROR, dc.userCode, dc.verificationUrl,
                                    "", "", msg, deadlineMs, dc.intervalSec, clientId));
                            return;
                        }
                        try { Thread.sleep(1500L); } catch (InterruptedException ignored) { return; }
                    }
                }

                if (!session.cancelled.get() && activeAuthSession == session) {
                    activeAuthSession = null;
                    emitAuthSnapshot(new DeviceAuthSnapshot(
                            DeviceAuthState.EXPIRED, dc.userCode, dc.verificationUrl,
                            "", "", "Activation code expired", deadlineMs, dc.intervalSec, clientId));
                }
            } catch (Exception initErr) {
                if (!session.cancelled.get() && activeAuthSession == session) {
                    activeAuthSession = null;
                    String msg = initErr.getMessage() != null ? initErr.getMessage() : "Failed to request Trakt code";
                    emitAuthSnapshot(new DeviceAuthSnapshot(
                            DeviceAuthState.ERROR, "", "", "", "", msg, 0L, 5, getEffectiveClientId(app)));
                }
            }
        });
    }

    public static DeviceCode requestDeviceCode(Context context) throws Exception {
        Context app = context.getApplicationContext();
        String clientId = resolveClientId(app);
        JSONObject req = new JSONObject().put("client_id", clientId);
        JSONObject res = parseJsonObjectStrict(
                request("POST", API + "/oauth/device/code", clientId, null, req.toString()),
                "Trakt returned an unexpected non-JSON response");
        return new DeviceCode(
                res.getString("device_code"),
                res.getString("user_code"),
                res.optString("verification_url", "https://trakt.tv/activate"),
                res.optInt("expires_in", 600),
                Math.max(2, res.optInt("interval", 5)));
    }

    public static PollResult pollDeviceToken(Context context, String deviceCode) throws Exception {
        Context app = context.getApplicationContext();
        String clientId = resolveClientId(app);
        JSONObject req = new JSONObject()
                .put("code", deviceCode)
                .put("client_id", clientId);
        HttpURLConnection conn = open("POST", API + "/oauth/device/token", clientId, null, req.toString());
        try {
            int status = conn.getResponseCode();
            if (status == 200) {
                saveTokenResponse(app, clientId, parseJsonObjectStrict(
                        readBody(conn.getInputStream()),
                        "Invalid token response from Trakt"));
                return PollResult.AUTHORIZED;
            }
            if (status == 400 || status == 401 || status == 404) {
                // Pending authorizations return 400/404 with an empty/"pending" body. A client
                // authentication rejection (PKCE-only app refusing device tokens) must not loop forever.
                String body = status == 404 ? "" : readErrorBodySafe(conn);
                String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
                if (status == 401 || lower.contains("client_secret") || lower.contains("invalid_client")
                        || lower.contains("unauthorized_client")) {
                    throw new IOException("Trakt rejected Connect with Code for this app (PKCE-only). "
                            + "Please use \"Connect via Browser\" instead.");
                }
                Log.d(TAG, "pollDeviceToken pending HTTP " + status);
                return PollResult.PENDING;
            }
            if (status == 429) return PollResult.SLOW_DOWN;
            if (status == 410) return PollResult.EXPIRED;
            if (status == 418) return PollResult.DENIED;
            String errBody = readErrorBodySafe(conn);
            throw new TraktHttpException(status, errBody);
        } finally {
            conn.disconnect();
        }
    }

    public static synchronized String ensureAccessToken(Context context) throws Exception {
        Context app = context.getApplicationContext();
        String clientId = resolveClientId(app);
        String token = ProviderKeys.get(app, ProviderKeys.TRAKT_ACCESS_TOKEN);
        if (token.isBlank()) throw new IOException("Trakt account not connected");
        long expiresAt = 0L;
        try { expiresAt = Long.parseLong(ProviderKeys.get(app, ProviderKeys.TRAKT_EXPIRES_AT)); }
        catch (NumberFormatException ignored) {}
        if (expiresAt > 0L && System.currentTimeMillis() > expiresAt - 3600_000L) {
            String refresh = ProviderKeys.get(app, ProviderKeys.TRAKT_REFRESH_TOKEN);
            if (!refresh.isBlank()) {
                JSONObject req = new JSONObject()
                        .put("refresh_token", refresh)
                        .put("client_id", clientId)
                        .put("redirect_uri", PKCE_REDIRECT_URI)
                        .put("grant_type", "refresh_token");
                JSONObject res = parseJsonObjectStrict(
                        request("POST", API + "/oauth/token", clientId, null, req.toString()),
                        "Invalid refresh response from Trakt");
                saveTokenResponse(app, clientId, res);
                token = res.getString("access_token");
            }
        }
        return token;
    }

    private static void saveTokenResponse(Context context, String clientId, JSONObject res) throws Exception {
        Context app = context.getApplicationContext();
        String access = res.getString("access_token");
        String refresh = res.optString("refresh_token", "");
        long created = res.optLong("created_at", System.currentTimeMillis() / 1000L);
        long expiresIn = res.optLong("expires_in", 7776000L);
        long expiresAtMs = (created + expiresIn) * 1000L;
        ProviderKeys.save(app, ProviderKeys.TRAKT_ID, clientId);
        ProviderKeys.save(app, ProviderKeys.TRAKT_ACCESS_TOKEN, access);
        if (!refresh.isBlank()) ProviderKeys.save(app, ProviderKeys.TRAKT_REFRESH_TOKEN, refresh);
        ProviderKeys.save(app, ProviderKeys.TRAKT_EXPIRES_AT, Long.toString(expiresAtMs));
        fetchAndSaveUsername(app, clientId, access);
    }

    public static String fetchAndSaveUsername(Context context) {
        if (context == null || !isConnected(context)) return "";
        Context app = context.getApplicationContext();
        String existing = getConnectedUsername(app);
        if (!existing.isBlank()) return existing;
        try {
            String cid = resolveClientId(app);
            String token = ProviderKeys.get(app, ProviderKeys.TRAKT_ACCESS_TOKEN);
            return fetchAndSaveUsername(app, cid, token);
        } catch (Exception ignored) {
            return existing;
        }
    }

    private static String fetchAndSaveUsername(Context context, String clientId, String accessToken) {
        if (context == null || clientId == null || clientId.isBlank() || accessToken == null || accessToken.isBlank()) return "";
        Context app = context.getApplicationContext();
        String username = "";
        String slug = "";
        try {
            JSONObject me = parseJsonObjectSafe(request("GET", API + "/users/me", clientId, accessToken, null));
            if (me != null) {
                username = me.optString("username", "").trim();
                JSONObject ids = me.optJSONObject("ids");
                slug = ids != null ? ids.optString("slug", "").trim() : "";
                if (username.isEmpty()) username = me.optString("name", "").trim();
            }
        } catch (Exception ignored) {}
        if (username.isEmpty() || slug.isEmpty()) {
            try {
                JSONObject profile = parseJsonObjectSafe(request("GET", API + "/users/settings", clientId, accessToken, null));
                JSONObject user = profile != null ? profile.optJSONObject("user") : null;
                if (user != null) {
                    if (username.isEmpty()) username = user.optString("username", "").trim();
                    if (username.isEmpty()) username = user.optString("name", "").trim();
                    JSONObject ids = user.optJSONObject("ids");
                    if (slug.isEmpty() && ids != null) slug = ids.optString("slug", "").trim();
                }
            } catch (Exception ignored) {}
        }
        if (!username.isEmpty()) {
            ProviderKeys.save(app, ProviderKeys.TRAKT_USERNAME, username);
        }
        if (!slug.isEmpty()) {
            ProviderKeys.save(app, ProviderKeys.TRAKT_SLUG, slug);
        }
        return !username.isEmpty() ? username : ProviderKeys.get(app, ProviderKeys.TRAKT_USERNAME);
    }

    /** Push pending watch history & playback progress items to Trakt. Returns synced local keys. */
    public static List<String> pushPending(Context context, List<SyncItem> items) throws Exception {
        List<String> synced = new ArrayList<>();
        if (items == null || items.isEmpty()) return synced;
        String clientId = resolveClientId(context);
        String token = ensureAccessToken(context);

        JSONArray addMovies = new JSONArray();
        JSONArray addShows = new JSONArray();
        JSONArray removeMovies = new JSONArray();
        JSONArray removeShows = new JSONArray();
        List<String> historyKeys = new ArrayList<>();
        List<String> removeKeys = new ArrayList<>();

        for (SyncItem item : items) {
            boolean hasImdb = item.imdbId != null && item.imdbId.matches("tt[0-9]+");
            if (!hasImdb && item.tmdbId <= 0) continue;
            JSONObject ids = new JSONObject();
            if (hasImdb) ids.put("imdb", item.imdbId);
            if (item.tmdbId > 0) ids.put("tmdb", item.tmdbId);
            boolean isEp = "episode".equalsIgnoreCase(item.mediaType) || item.season > 0 || item.episode > 0;
            String iso = toIsoUtc(item.updatedAtMs > 0 ? item.updatedAtMs : System.currentTimeMillis());

            if (item.watched) {
                if (isEp) {
                    addShows.put(new JSONObject().put("ids", ids).put("seasons", new JSONArray()
                            .put(new JSONObject().put("number", item.season).put("episodes", new JSONArray()
                                    .put(new JSONObject().put("number", item.episode).put("watched_at", iso))))));
                } else {
                    addMovies.put(new JSONObject().put("ids", ids).put("watched_at", iso));
                }
                historyKeys.add(item.localKey);
            } else if (item.removed) {
                if (isEp) {
                    removeShows.put(new JSONObject().put("ids", ids).put("seasons", new JSONArray()
                            .put(new JSONObject().put("number", item.season).put("episodes", new JSONArray()
                                    .put(new JSONObject().put("number", item.episode))))));
                } else {
                    removeMovies.put(new JSONObject().put("ids", ids));
                }
                removeKeys.add(item.localKey);
            } else if (item.durationMs > 0 && item.positionMs > 0) {
                double progress = Math.max(1.0, Math.min(84.9, (item.positionMs * 100.0) / item.durationMs));
                JSONObject scrobble = new JSONObject().put("progress", progress);
                if (isEp) {
                    scrobble.put("show", new JSONObject().put("ids", ids));
                    scrobble.put("episode", new JSONObject().put("season", item.season).put("number", item.episode));
                } else {
                    scrobble.put("movie", new JSONObject().put("ids", ids));
                }
                try {
                    request("POST", API + "/scrobble/pause", clientId, token, scrobble.toString());
                    synced.add(item.localKey);
                } catch (Exception ignored) {}
            }
        }

        if (addMovies.length() > 0 || addShows.length() > 0) {
            JSONObject body = new JSONObject();
            if (addMovies.length() > 0) body.put("movies", addMovies);
            if (addShows.length() > 0) body.put("shows", addShows);
            request("POST", API + "/sync/history", clientId, token, body.toString());
            synced.addAll(historyKeys);
        }
        if (removeMovies.length() > 0 || removeShows.length() > 0) {
            JSONObject body = new JSONObject();
            if (removeMovies.length() > 0) body.put("movies", removeMovies);
            if (removeShows.length() > 0) body.put("shows", removeShows);
            request("POST", API + "/sync/history/remove", clientId, token, body.toString());
            synced.addAll(removeKeys);
        }
        return synced;
    }

    /** Pull watched movies, watched episodes, and playback progress from Trakt in WATCH_SYNC JSON format. */
    public static JSONArray pullRecords(Context context) throws Exception {
        String clientId = resolveClientId(context);
        String token = ensureAccessToken(context);
        JSONArray out = new JSONArray();

        // 1. Watched movies
        JSONArray watchedMovies = parseJsonArrayStrict(request("GET", API + "/sync/watched/movies", clientId, token, null));
        for (int i = 0; i < watchedMovies.length() && out.length() < 120; i++) {
            JSONObject row = watchedMovies.optJSONObject(i);
            JSONObject movie = row != null ? row.optJSONObject("movie") : null;
            if (movie == null) continue;
            JSONObject ids = movie.optJSONObject("ids");
            String imdb = ids != null ? ids.optString("imdb", "") : "";
            long tmdb = ids != null ? ids.optLong("tmdb", 0L) : 0L;
            long ts = parseIsoUtc(row.optString("last_watched_at", ""));
            out.put(new JSONObject()
                    .put("media_type", "movie")
                    .put("title", movie.optString("title", ""))
                    .put("year", movie.optInt("year", 0))
                    .put("imdb_id", imdb)
                    .put("tmdb_id", tmdb)
                    .put("season", 0)
                    .put("episode", 0)
                    .put("position_ms", 0L)
                    .put("duration_ms", 0L)
                    .put("watched", true)
                    .put("removed_from_continue", false)
                    .put("kind", "trakt")
                    .put("updated_at", ts));
        }

        // 2. Watched shows/episodes
        JSONArray watchedShows = parseJsonArrayStrict(request("GET", API + "/sync/watched/shows", clientId, token, null));
        for (int i = 0; i < watchedShows.length() && out.length() < 250; i++) {
            JSONObject row = watchedShows.optJSONObject(i);
            JSONObject show = row != null ? row.optJSONObject("show") : null;
            if (show == null) continue;
            JSONObject ids = show.optJSONObject("ids");
            String imdb = ids != null ? ids.optString("imdb", "") : "";
            long tmdb = ids != null ? ids.optLong("tmdb", 0L) : 0L;
            String showTitle = show.optString("title", "");
            int showYear = show.optInt("year", 0);
            JSONArray seasons = row.optJSONArray("seasons");
            if (seasons == null) continue;
            for (int s = 0; s < seasons.length(); s++) {
                JSONObject seasonObj = seasons.optJSONObject(s);
                if (seasonObj == null) continue;
                int sNum = seasonObj.optInt("number", 0);
                JSONArray eps = seasonObj.optJSONArray("episodes");
                if (eps == null) continue;
                for (int e = 0; e < eps.length(); e++) {
                    JSONObject epObj = eps.optJSONObject(e);
                    if (epObj == null) continue;
                    int eNum = epObj.optInt("number", 0);
                    long ts = parseIsoUtc(epObj.optString("last_watched_at", row.optString("last_watched_at", "")));
                    out.put(new JSONObject()
                            .put("media_type", "episode")
                            .put("title", showTitle)
                            .put("year", showYear)
                            .put("imdb_id", imdb)
                            .put("tmdb_id", tmdb)
                            .put("season", sNum)
                            .put("episode", eNum)
                            .put("position_ms", 0L)
                            .put("duration_ms", 0L)
                            .put("watched", true)
                            .put("removed_from_continue", false)
                            .put("kind", "trakt")
                            .put("updated_at", ts));
                }
            }
        }

        // 3. Playback progress
        JSONArray playback = parseJsonArrayStrict(request("GET", API + "/sync/playback", clientId, token, null));
        for (int i = 0; i < playback.length(); i++) {
            JSONObject row = playback.optJSONObject(i);
            if (row == null) continue;
            double pct = row.optDouble("progress", 0.0);
            if (!Double.isFinite(pct) || pct < 3.0 || pct >= 85.0) continue;
            String type = row.optString("type", "movie");
            long ts = parseIsoUtc(row.optString("paused_at", ""));
            long estDur = 3600_000L;
            long estPos = Math.round(estDur * pct / 100.0);
            if ("episode".equals(type)) {
                JSONObject show = row.optJSONObject("show");
                JSONObject ep = row.optJSONObject("episode");
                JSONObject ids = show != null ? show.optJSONObject("ids") : null;
                if (ep == null || ep.optInt("season", -1) < 0 || ep.optInt("number", 0) <= 0
                        || ts <= 0 || ids == null
                        || (!ids.optString("imdb", "").matches("tt[0-9]+") && ids.optLong("tmdb", 0L) <= 0)) continue;
                out.put(new JSONObject()
                        .put("media_type", "episode")
                        .put("title", show != null ? show.optString("title", "") : "")
                        .put("year", show != null ? show.optInt("year", 0) : 0)
                        .put("imdb_id", ids != null ? ids.optString("imdb", "") : "")
                        .put("tmdb_id", ids != null ? ids.optLong("tmdb", 0L) : 0L)
                        .put("season", ep != null ? ep.optInt("season", 0) : 0)
                        .put("episode", ep != null ? ep.optInt("number", 0) : 0)
                        .put("progress_pct", pct)
                        .put("position_ms", estPos)
                        .put("duration_ms", estDur)
                        .put("watched", false)
                        .put("removed_from_continue", false)
                        .put("kind", "trakt")
                        .put("updated_at", ts));
            } else {
                JSONObject movie = row.optJSONObject("movie");
                JSONObject ids = movie != null ? movie.optJSONObject("ids") : null;
                out.put(new JSONObject()
                        .put("media_type", "movie")
                        .put("title", movie != null ? movie.optString("title", "") : "")
                        .put("year", movie != null ? movie.optInt("year", 0) : 0)
                        .put("imdb_id", ids != null ? ids.optString("imdb", "") : "")
                        .put("tmdb_id", ids != null ? ids.optLong("tmdb", 0L) : 0L)
                        .put("season", 0)
                        .put("episode", 0)
                        .put("progress_pct", pct)
                        .put("position_ms", estPos)
                        .put("duration_ms", estDur)
                        .put("watched", false)
                        .put("removed_from_continue", false)
                        .put("kind", "trakt")
                        .put("updated_at", ts));
            }
        }
        return out;
    }

    /** Pulls Trakt Watchlist movies and shows using OAuth token. */
    public static JSONArray pullWatchlist(Context context) throws Exception {
        String clientId = resolveClientId(context);
        String token = ensureAccessToken(context);
        JSONArray out = new JSONArray();

        JSONArray movies = parseJsonArrayStrict(request("GET", API + "/sync/watchlist/movies", clientId, token, null));
        for (int i = 0; i < movies.length(); i++) {
            JSONObject row = movies.optJSONObject(i);
            JSONObject movie = row == null ? null : row.optJSONObject("movie");
            JSONObject ids = movie == null ? null : movie.optJSONObject("ids");
            if (movie == null || ids == null) continue;
            out.put(new JSONObject()
                    .put("media_type", "movie")
                    .put("title", movie.optString("title", ""))
                    .put("year", movie.optInt("year", 0))
                    .put("imdb_id", ids.optString("imdb", ""))
                    .put("tmdb_id", ids.optLong("tmdb", 0L))
                    .put("updated_at", parseIsoUtc(row.optString("listed_at", ""))));
        }

        JSONArray shows = parseJsonArrayStrict(request("GET", API + "/sync/watchlist/shows", clientId, token, null));
        for (int i = 0; i < shows.length(); i++) {
            JSONObject row = shows.optJSONObject(i);
            JSONObject show = row == null ? null : row.optJSONObject("show");
            JSONObject ids = show == null ? null : show.optJSONObject("ids");
            if (show == null || ids == null) continue;
            out.put(new JSONObject()
                    .put("media_type", "show")
                    .put("title", show.optString("title", ""))
                    .put("year", show.optInt("year", 0))
                    .put("imdb_id", ids.optString("imdb", ""))
                    .put("tmdb_id", ids.optLong("tmdb", 0L))
                    .put("updated_at", parseIsoUtc(row.optString("listed_at", ""))));
        }
        return out;
    }

    /** Adds or removes a movie or show on the user's Trakt Watchlist. */
    public static boolean syncWatchlistItem(Context context, boolean isShow, String imdbId, long tmdbId, boolean add) {
        if (!isConnected(context)) return false;
        boolean hasImdb = imdbId != null && imdbId.matches("tt[0-9]+");
        if (!hasImdb && tmdbId <= 0) return false;
        try {
            String clientId = resolveClientId(context);
            String token = ensureAccessToken(context);
            JSONObject ids = new JSONObject();
            if (hasImdb) ids.put("imdb", imdbId);
            if (tmdbId > 0) ids.put("tmdb", tmdbId);
            JSONObject body = new JSONObject().put(isShow ? "shows" : "movies",
                    new JSONArray().put(new JSONObject().put("ids", ids)));
            request("POST", API + (add ? "/sync/watchlist" : "/sync/watchlist/remove"), clientId, token, body.toString());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Public Trakt rating endpoint (returns null if no user Client ID configured). */
    public static Double fetchPublicRating(Context context, String imdbId, boolean isShow) {
        if (context == null || imdbId == null || !imdbId.matches("tt[0-9]+")) return null;
        String clientId = getEffectiveClientId(context);
        if (clientId.isBlank()) return null;
        try {
            String path = (isShow ? "/shows/" : "/movies/") + imdbId + "/ratings";
            JSONObject obj = parseJsonObjectSafe(request("GET", API + path, clientId, null, null));
            if (obj == null) return null;
            double r = obj.optDouble("rating", 0.0);
            return r > 0 && r <= 10.0 ? r : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONObject parseJsonObjectSafe(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{")) return null;
        try {
            return new JSONObject(trimmed);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONObject parseJsonObjectStrict(String raw, String fallbackError) throws Exception {
        String trimmed = raw == null ? "" : raw.trim();
        if (!trimmed.startsWith("{")) {
            throw new IOException(fallbackError);
        }
        return new JSONObject(trimmed);
    }

    private static JSONArray parseJsonArrayStrict(String raw) throws Exception {
        String trimmed = raw == null ? "" : raw.trim();
        if (!trimmed.startsWith("[")) {
            throw new IOException("Trakt returned an unexpected non-JSON array response");
        }
        return new JSONArray(trimmed);
    }

    private static HttpURLConnection open(String method, String url, String clientId, String token, String jsonBody)
            throws IOException {
        String cleanClientId = normalizeClientId(clientId);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(7000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "LocalCinema/1.0");
        conn.setRequestProperty("trakt-api-version", "2");
        conn.setRequestProperty("trakt-api-key", cleanClientId);
        if (token != null && !token.isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (jsonBody != null) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
        }
        return conn;
    }

    private static String request(String method, String url, String clientId, String token, String jsonBody)
            throws IOException {
        HttpURLConnection conn = open(method, url, clientId, token, jsonBody);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new TraktHttpException(code, readErrorBodySafe(conn));
            }
            return readBody(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static String requestForm(String method, String url, String clientId, String formBody)
            throws IOException {
        String cleanClientId = normalizeClientId(clientId);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(7000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", "LocalCinema/1.0");
        conn.setRequestProperty("trakt-api-version", "2");
        conn.setRequestProperty("trakt-api-key", cleanClientId);
        if (formBody != null) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(formBody.getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new TraktHttpException(code, readErrorBodySafe(conn));
            }
            return readBody(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    /** Removes matching playback progress items from Trakt's /sync/playback (Continue Watching). */
    public static String findEpisodeImdbId(Context context, String showImdbId, int season, int episode) {
        if (showImdbId == null || !showImdbId.matches("tt[0-9]{5,12}") || season < 0 || episode <= 0) return "";
        try {
            String url = API + "/shows/" + showImdbId + "/seasons/" + season + "/episodes/" + episode;
            var result = new JSONObject(request("GET", url, resolveClientId(context), null, null));
            var ids = result.optJSONObject("ids");
            return ids == null ? "" : ids.optString("imdb", "");
        } catch (Exception unavailable) {
            return "";
        }
    }

    public static boolean removePlaybackProgress(Context context, String imdbId, long tmdbId, int season, int episode) {
        if (context == null || !isConnected(context)) return false;
        boolean hasImdb = imdbId != null && imdbId.matches("tt[0-9]+");
        if (!hasImdb && tmdbId <= 0) return false;
        boolean removedAny = false;
        try {
            String clientId = resolveClientId(context);
            String token = ensureAccessToken(context);
            JSONArray playback = parseJsonArrayStrict(request("GET", API + "/sync/playback", clientId, token, null));
            for (int i = 0; i < playback.length(); i++) {
                JSONObject row = playback.optJSONObject(i);
                if (row == null) continue;
                long playbackId = row.optLong("id", 0L);
                if (playbackId <= 0) continue;
                String type = row.optString("type", "movie");
                JSONObject target = "episode".equals(type) ? row.optJSONObject("show") : row.optJSONObject("movie");
                JSONObject ids = target != null ? target.optJSONObject("ids") : null;
                if (ids == null) continue;
                String rowImdb = ids.optString("imdb", "");
                long rowTmdb = ids.optLong("tmdb", 0L);
                boolean idMatch = (hasImdb && imdbId.equalsIgnoreCase(rowImdb))
                        || (tmdbId > 0 && tmdbId == rowTmdb);
                if (!idMatch) continue;
                if ("episode".equals(type) && (season > 0 || episode > 0)) {
                    JSONObject ep = row.optJSONObject("episode");
                    int rS = ep != null ? ep.optInt("season", 0) : 0;
                    int rE = ep != null ? ep.optInt("number", 0) : 0;
                    if (season > 0 && rS != season) continue;
                    if (episode > 0 && rE != episode) continue;
                }
                HttpURLConnection del = open("DELETE", API + "/sync/playback/" + playbackId, clientId, token, null);
                try {
                    int code = del.getResponseCode();
                    if ((code >= 200 && code < 300) || code == 404) {
                        removedAny = true;
                    }
                } finally {
                    del.disconnect();
                }
            }
        } catch (Exception ignored) {}
        return removedAny;
    }

    /** Immediately scrobbles playback state (start, pause, stop) to Trakt. */
    public static boolean scrobble(Context context, String action, boolean isEpisode,
                                   String imdbId, long tmdbId, int season, int episode, double progressPct) {
        if (context == null || !isConnected(context)) return false;
        boolean hasImdb = imdbId != null && imdbId.matches("tt[0-9]+");
        if (!hasImdb && tmdbId <= 0) return false;
        String safeAction = ("start".equals(action) || "stop".equals(action)) ? action : "pause";
        double pct = Math.max(0.0, Math.min(100.0, progressPct));
        try {
            String clientId = resolveClientId(context);
            String token = ensureAccessToken(context);
            JSONObject ids = new JSONObject();
            if (hasImdb) ids.put("imdb", imdbId);
            if (tmdbId > 0) ids.put("tmdb", tmdbId);
            JSONObject body = new JSONObject().put("progress", pct);
            if (isEpisode || season > 0 || episode > 0) {
                body.put("show", new JSONObject().put("ids", ids));
                body.put("episode", new JSONObject()
                        .put("season", Math.max(0, season))
                        .put("number", Math.max(0, episode)));
            } else {
                body.put("movie", new JSONObject().put("ids", ids));
            }
            HttpURLConnection conn = open("POST", API + "/scrobble/" + safeAction, clientId, token, body.toString());
            try {
                int code = conn.getResponseCode();
                return (code >= 200 && code < 300) || code == 409;
            } finally {
                conn.disconnect();
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Immediately marks/unmarks a movie or episode watched in Trakt history. */
    public static boolean syncHistoryItem(Context context, boolean isEpisode,
                                          String imdbId, long tmdbId, int season, int episode, boolean watched) {
        if (context == null || !isConnected(context)) return false;
        boolean hasImdb = imdbId != null && imdbId.matches("tt[0-9]+");
        if (!hasImdb && tmdbId <= 0) return false;
        try {
            String clientId = resolveClientId(context);
            String token = ensureAccessToken(context);
            JSONObject ids = new JSONObject();
            if (hasImdb) ids.put("imdb", imdbId);
            if (tmdbId > 0) ids.put("tmdb", tmdbId);
            JSONObject body = new JSONObject();
            if (isEpisode || season > 0 || episode > 0) {
                JSONObject epObj = new JSONObject().put("number", Math.max(0, episode));
                if (watched) epObj.put("watched_at", toIsoUtc(System.currentTimeMillis()));
                body.put("shows", new JSONArray().put(new JSONObject()
                        .put("ids", ids)
                        .put("seasons", new JSONArray().put(new JSONObject()
                                .put("number", Math.max(0, season))
                                .put("episodes", new JSONArray().put(epObj))))));
            } else {
                JSONObject mov = new JSONObject().put("ids", ids);
                if (watched) mov.put("watched_at", toIsoUtc(System.currentTimeMillis()));
                body.put("movies", new JSONArray().put(mov));
            }
            request("POST", API + (watched ? "/sync/history" : "/sync/history/remove"), clientId, token, body.toString());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String readErrorBodySafe(HttpURLConnection conn) {
        try {
            InputStream err = conn.getErrorStream();
            if (err == null) return "";
            String raw = readBody(err).trim();
            if (raw.isEmpty()) return "";
            JSONObject obj = parseJsonObjectSafe(raw);
            if (obj != null) {
                String desc = obj.optString("error_description", obj.optString("error", "")).trim();
                if (!desc.isEmpty()) return desc.length() > 160 ? desc.substring(0, 160) : desc;
            }
            String clean = raw.replaceAll("\\s+", " ").trim();
            return clean.length() > 160 ? clean.substring(0, 160) : clean;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readBody(InputStream in) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > 4 * 1024 * 1024) throw new IOException("Trakt response too large");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String toIsoUtc(long epochMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(epochMs));
    }

    private static long parseIsoUtc(String iso) {
        if (iso == null || iso.isBlank()) return System.currentTimeMillis();
        try {
            String clean = iso.replaceFirst("\\.[0-9]+Z$", "Z");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = sdf.parse(clean);
            return d != null ? d.getTime() : System.currentTimeMillis();
        } catch (Exception ignored) {
            return System.currentTimeMillis();
        }
    }
}

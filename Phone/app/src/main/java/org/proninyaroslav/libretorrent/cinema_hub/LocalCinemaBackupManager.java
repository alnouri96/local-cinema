package org.proninyaroslav.libretorrent.cinema_hub;

import android.accounts.Account;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

import androidx.preference.PreferenceManager;

import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.Scopes;
import com.google.android.gms.common.api.Scope;
import com.meedx.providers.ApiKeyStore;
import com.meedx.providers.LiveChannelFavorites;
import com.meedx.providers.LiveTvPreferences;
import com.meedx.providers.ProviderKeys;
import com.meedx.providers.StremioAddons;
import com.meedx.providers.TraktSyncClient;
import com.meedx.providers.UnifiedSubtitleConfig;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unified backup, restore & automatic cloud sync manager covering separated {@code tv_settings}
 * and {@code phone_settings} sections, Timestamp Conflict Guard (Cloud vs Live TV), 20-second
 * debounced silent Google Drive uploads, handshake reconciliation, and 6-hour periodic sync.
 *
 * <p>Strictly adheres to {@code Phone/AGENTS.md}: zero LibreTorrent or CinemaBrowser imports.
 */
public final class LocalCinemaBackupManager {
    public static final String DRIVE_BACKUP_FILENAME = "local_cinema_unified_backup.json";
    private static final String CONFIG_URL = "https://meedx.pages.dev/api/config.json";
    private static final String DEFAULT_GOOGLE_CLIENT_ID =
            "1064494488628-a9kgptmostvcj3r82hqsqcq1tq8vr517.apps.googleusercontent.com";
    /** Compatible with Android OAuth Client ID + Google Drive REST API v3 (drive.appdata + drive.file). */
    private static final String DRIVE_SCOPE_APPDATA = Scopes.DRIVE_APPFOLDER;
    private static final String DRIVE_SCOPE_FILE = Scopes.DRIVE_FILE;
    private static final String DRIVE_AUTHORITY_INITIAL_URI = "content://com.google.android.apps.docs.storage/document";

    private static final String SYNC_META_PREFS = "cinema_cloud_sync";
    private static final String KEY_PHONE_UPDATED_AT = "phone_settings_updated_at";
    private static final String KEY_PHONE_HASH = "phone_settings_hash";
    private static final String KEY_CACHED_TV_UPDATED_AT = "cached_tv_settings_updated_at";
    private static final String KEY_CACHED_TV_HASH = "cached_tv_settings_hash";
    private static final String KEY_LAST_DRIVE_HASH = "last_drive_uploaded_combined_hash";
    private static final String KEY_LAST_DRIVE_UPLOAD_AT = "last_drive_uploaded_at";
    private static final String KEY_DELETED_BACKUP_BASELINE = "deleted_backup_baseline";
    private static final String KEY_CLOUD_FILE_ID = "cloudBackupFileId";
    private static final String KEY_CLOUD_DIRTY = "cloud_sync_dirty";

    public static final long DEBOUNCE_UPLOAD_MS = 20_000L;

    private static final AtomicBoolean AUTO_SYNC_INITIALIZED = new AtomicBoolean(false);
    private static final AtomicBoolean APPLYING_INCOMING_RESTORE = new AtomicBoolean(false);
    private static final AtomicBoolean UPLOAD_IN_FLIGHT = new AtomicBoolean(false);
    private static final List<SharedPreferences> WATCHED_PHONE_STORES =
            Collections.synchronizedList(new ArrayList<>());
    private static volatile SharedPreferences.OnSharedPreferenceChangeListener phonePrefsListener;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile Runnable pendingPhoneHashCheckTask;
    private static volatile Runnable pendingDebouncedDriveUploadTask;

    private static volatile long lastLanSyncAttemptMs = 0L;
    private static final java.util.concurrent.ExecutorService LAN_SYNC_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    public enum TvReconcileOutcome {
        PUSHED_TO_TV,
        PULLED_NEWER_FROM_TV,
        ALREADY_IN_SYNC,
        QUEUED_FOR_OFFLINE_TV,
        NONE
    }

    public static final class DriveAuthRecoverableException extends Exception {
        public final Intent recoveryIntent;
        public DriveAuthRecoverableException(Intent recoveryIntent) {
            super("Google Drive authorization required");
            this.recoveryIntent = recoveryIntent;
        }
    }

    public static final class RestoreResult {
        public final JSONObject tvRestoreCommand;
        public final boolean hasTvPrefs;
        public final int watchRecordsRestored;
        public final boolean apiKeysRestored;
        public final long tvSettingsUpdatedAt;
        public final String tvSettingsHash;

        public RestoreResult(JSONObject tvRestoreCommand, boolean hasTvPrefs,
                             int watchRecordsRestored, boolean apiKeysRestored) {
            this(tvRestoreCommand, hasTvPrefs, watchRecordsRestored, apiKeysRestored, 0L, "");
        }

        public RestoreResult(JSONObject tvRestoreCommand, boolean hasTvPrefs,
                             int watchRecordsRestored, boolean apiKeysRestored,
                             long tvSettingsUpdatedAt, String tvSettingsHash) {
            this.tvRestoreCommand = tvRestoreCommand;
            this.hasTvPrefs = hasTvPrefs;
            this.watchRecordsRestored = watchRecordsRestored;
            this.apiKeysRestored = apiKeysRestored;
            this.tvSettingsUpdatedAt = tvSettingsUpdatedAt;
            this.tvSettingsHash = tvSettingsHash == null ? "" : tvSettingsHash;
        }
    }

    private LocalCinemaBackupManager() {}

    // ---- Auto-Sync Initialization, Change Tracking & 20s Debounced Cloud Upload ----

    public static void initAutoSync(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        CinemaCloudSyncWorker.ensurePeriodicScheduled(app);
        if (!AUTO_SYNC_INITIALIZED.compareAndSet(false, true)) return;

        phonePrefsListener = (sp, key) -> {
            if (APPLYING_INCOMING_RESTORE.get()) return;
            if (key != null && ("open_tabs".equals(key)
                    || "selected_tab".equals(key)
                    || "visit_details".equals(key)
                    || "history".equals(key)
                    || "full_sync_dirty".equals(key)
                    || "phone_id".equals(key)
                    || key.startsWith("cached_tv_addons"))) {
                return;
            }
            notifyPhoneSettingsMutated(app);
        };

        String[] storeNames = {
                "cinema_browser", "cinema_link", "cinema_subtitles", "unified_subtitle_config",
                "meedx_provider_keys", "trakt_sync_prefs", "meedx_stremio_addons",
                "subsource_secure", "omdb_secure", "cinema_library", "cinema_music",
                "live_tv_prefs", "live_channel_favorites"
        };
        SharedPreferences defSp = PreferenceManager.getDefaultSharedPreferences(app);
        defSp.registerOnSharedPreferenceChangeListener(phonePrefsListener);
        WATCHED_PHONE_STORES.add(defSp);
        for (String name : storeNames) {
            SharedPreferences sp = app.getSharedPreferences(name, Context.MODE_PRIVATE);
            sp.registerOnSharedPreferenceChangeListener(phonePrefsListener);
            WATCHED_PHONE_STORES.add(sp);
        }

        LAN_SYNC_IO.execute(() -> bootstrapPhoneMetadataIfNeeded(app));
    }

    private static SharedPreferences syncMeta(Context context) {
        return context.getApplicationContext().getSharedPreferences(SYNC_META_PREFS, Context.MODE_PRIVATE);
    }

    public static long getPhoneSettingsUpdatedAt(Context context) {
        return context == null ? 0L : syncMeta(context).getLong(KEY_PHONE_UPDATED_AT, 0L);
    }

    public static long getCachedTvSettingsUpdatedAt(Context context) {
        if (context == null) return 0L;
        long metaAt = syncMeta(context).getLong(KEY_CACHED_TV_UPDATED_AT, 0L);
        if (metaAt > 0L) return metaAt;
        JSONObject cached = loadCachedTvBackup(context);
        return extractTvUpdatedAtFromLivePacket(cached);
    }

    public static String getCachedTvSettingsHash(Context context) {
        if (context == null) return "";
        String metaHash = syncMeta(context).getString(KEY_CACHED_TV_HASH, "");
        if (metaHash != null && !metaHash.isEmpty()) return metaHash;
        JSONObject cached = loadCachedTvBackup(context);
        return cached != null ? cached.optString("tv_settings_hash", "") : "";
    }

    private static void bootstrapPhoneMetadataIfNeeded(Context app) {
        try {
            SharedPreferences meta = syncMeta(app);
            String curHash = meta.getString(KEY_PHONE_HASH, "");
            long curAt = meta.getLong(KEY_PHONE_UPDATED_AT, 0L);
            String computed = computePhoneSettingsHash(app);
            if (!computed.isEmpty() && curHash.isEmpty()) {
                meta.edit()
                        .putLong(KEY_PHONE_UPDATED_AT, curAt > 0L ? curAt : System.currentTimeMillis())
                        .putString(KEY_PHONE_HASH, computed)
                        .commit();
            }
            JSONObject cachedTv = loadCachedTvBackup(app);
            if (cachedTv != null && meta.getLong(KEY_CACHED_TV_UPDATED_AT, 0L) == 0L) {
                long tvAt = extractTvUpdatedAtFromLivePacket(cachedTv);
                String tvHash = cachedTv.optString("tv_settings_hash", "");
                if (tvAt > 0L || !tvHash.isEmpty()) {
                    meta.edit()
                            .putLong(KEY_CACHED_TV_UPDATED_AT, tvAt)
                            .putString(KEY_CACHED_TV_HASH, tvHash)
                            .commit();
                }
            }
        } catch (Exception ignored) {}
    }

    public static void notifyPhoneSettingsMutated(Context context) {
        if (context == null || APPLYING_INCOMING_RESTORE.get()) return;
        final Context app = context.getApplicationContext();
        Runnable prev = pendingPhoneHashCheckTask;
        if (prev != null) MAIN_HANDLER.removeCallbacks(prev);
        Runnable next = () -> LAN_SYNC_IO.execute(() -> {
            if (APPLYING_INCOMING_RESTORE.get()) return;
            String newHash = computePhoneSettingsHash(app);
            if (newHash.isEmpty()) return;
            SharedPreferences meta = syncMeta(app);
            String prevHash = meta.getString(KEY_PHONE_HASH, "");
            if (newHash.equals(prevHash)) return;
            long now = System.currentTimeMillis();
            meta.edit()
                    .putLong(KEY_PHONE_UPDATED_AT, now)
                    .putString(KEY_PHONE_HASH, newHash)
                    .putBoolean(KEY_CLOUD_DIRTY, true)
                    .commit();
            scheduleDebouncedCloudUpload(app, "phone_settings_changed");
        });
        pendingPhoneHashCheckTask = next;
        MAIN_HANDLER.postDelayed(next, 300L);
    }

    /**
     * Schedules a silent Google Drive backup upload 20 seconds after the most recent Phone or TV
     * setting mutation, coalescing rapid consecutive edits into one clean upload.
     */
    public static void scheduleDebouncedCloudUpload(Context context, String reason) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        syncMeta(app).edit().putBoolean(KEY_CLOUD_DIRTY, true).apply();

        Runnable prev = pendingDebouncedDriveUploadTask;
        if (prev != null) MAIN_HANDLER.removeCallbacks(prev);
        Runnable task = () -> LAN_SYNC_IO.execute(() -> executeSilentCloudUploadIfNeeded(app, false));
        pendingDebouncedDriveUploadTask = task;
        MAIN_HANDLER.postDelayed(task, DEBOUNCE_UPLOAD_MS);
        CinemaCloudSyncWorker.scheduleDebouncedFallback(app, 25L);
    }

    /** Called by {@link CinemaCloudSyncWorker} every 6 hours and on out-of-process debounce fallback. */
    public static synchronized void runBackgroundSyncPass(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        initAutoSync(app);

        // 1. Reconcile with paired TV over LAN if reachable
        try {
            JSONObject pairing = readPairingConfig(app);
            if (pairing != null) {
                JSONObject liveTv = fetchLiveTvBackupOverLan(app, 3200);
                if (liveTv != null) {
                    long liveTvAt = extractTvUpdatedAtFromLivePacket(liveTv);
                    String liveTvHash = liveTv.optString("tv_settings_hash", "");
                    JSONObject pendingRestore = loadPendingTvRestore(app);
                    if (pendingRestore != null) {
                        long pendingAt = pendingRestore.optLong("tv_settings_updated_at", 0L);
                        if (liveTvAt > pendingAt) {
                            clearPendingTvRestore(app);
                            onLiveTvBackupReceived(app, liveTv, false);
                            syncMeta(app).edit().putBoolean(KEY_CLOUD_DIRTY, true).commit();
                        } else {
                            RestoreResult res = new RestoreResult(pendingRestore, true, 0, true, pendingAt, "");
                            pushTvRestoreOverLan(app, res, 4500);
                        }
                    } else {
                        long cachedAt = getCachedTvSettingsUpdatedAt(app);
                        String cachedHash = getCachedTvSettingsHash(app);
                        if (liveTvAt == 0L && cachedAt > 0L) {
                            JSONObject cachedTv = loadCachedTvBackup(app);
                            JSONObject pushCmd = buildTvRestoreCommandFromTvBlock(app, cachedTv, cachedAt);
                            if (pushCmd != null) {
                                pushTvRestoreOverLan(app, new RestoreResult(pushCmd, true, 0, true, cachedAt, cachedHash), 4500);
                            }
                        } else if (liveTvAt > cachedAt
                                || (liveTvAt > 0L && !liveTvHash.isEmpty() && !liveTvHash.equals(cachedHash))) {
                            onLiveTvBackupReceived(app, liveTv, false);
                            syncMeta(app).edit().putBoolean(KEY_CLOUD_DIRTY, true).commit();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // 2. Upload any pending Phone or TV mutations to Google Drive silently
        executeSilentCloudUploadIfNeeded(app, true);
    }

    private static synchronized void executeSilentCloudUploadIfNeeded(Context app, boolean fromWorker) {
        if (app == null) return;
        GoogleSignInAccount account = getSignedInGoogleAccount(app);
        if (account == null || account.getAccount() == null) return;
        if (!UPLOAD_IN_FLIGHT.compareAndSet(false, true)) return;
        try {
            SharedPreferences meta = syncMeta(app);
            String phoneHash = computePhoneSettingsHash(app);
            if (!phoneHash.isEmpty() && !phoneHash.equals(meta.getString(KEY_PHONE_HASH, ""))) {
                meta.edit()
                        .putLong(KEY_PHONE_UPDATED_AT, System.currentTimeMillis())
                        .putString(KEY_PHONE_HASH, phoneHash)
                        .putBoolean(KEY_CLOUD_DIRTY, true)
                        .commit();
            }
            JSONObject liveTv = null;
            if (!fromWorker && readPairingConfig(app) != null && loadPendingTvRestore(app) == null) {
                liveTv = fetchLiveTvBackupOverLan(app, 2800);
                if (liveTv != null) {
                    onLiveTvBackupReceived(app, liveTv, false);
                }
            }
            JSONObject tvSource = liveTv != null ? liveTv : loadCachedTvBackup(app);
            String tvHash = getCachedTvSettingsHash(app);
            String combinedHash = phoneHash + "|" + tvHash;
            if (combinedHash.equals(meta.getString(KEY_DELETED_BACKUP_BASELINE, ""))) {
                return;
            }
            boolean dirty = meta.getBoolean(KEY_CLOUD_DIRTY, false);
            if (!dirty && combinedHash.equals(meta.getString(KEY_LAST_DRIVE_HASH, ""))) {
                return;
            }

            // Timestamp Conflict Guard before cloud write: never clobber a newer cloud tv_settings block
            long localTvAt = tvSource != null ? extractTvUpdatedAtFromLivePacket(tvSource) : 0L;
            try {
                String existingCloudRaw = downloadFromGoogleDrive(app, account);
                if (existingCloudRaw != null && existingCloudRaw.trim().startsWith("{")) {
                    JSONObject existingRoot = new JSONObject(existingCloudRaw.trim());
                    long cloudTvAt = extractTvUpdatedAtFromRoot(existingRoot);
                    if (cloudTvAt > localTvAt) {
                        JSONObject cloudTvBlock = extractTvSectionFromRoot(existingRoot);
                        if (cloudTvBlock != null) {
                            tvSource = cloudTvBlock;
                            tvHash = cloudTvBlock.optString("hash", cloudTvBlock.optString("tv_settings_hash", tvHash));
                            combinedHash = phoneHash + "|" + tvHash;
                        }
                    }
                }
            } catch (Exception ignored) {
                // No existing cloud backup yet or transient error; proceed with upload
            }

            JSONObject unified = buildUnifiedBackup(app, tvSource);
            uploadToGoogleDrive(app, account, unified.toString(2));
            meta.edit()
                    .putBoolean(KEY_CLOUD_DIRTY, false)
                    .putString(KEY_LAST_DRIVE_HASH, combinedHash)
                    .putLong(KEY_LAST_DRIVE_UPLOAD_AT, System.currentTimeMillis())
                    .commit();
        } catch (Exception ignored) {
            // Silent retry on next periodic / debounced pass
        } finally {
            UPLOAD_IN_FLIGHT.set(false);
        }
    }

    // ---- Handshake Reconciliation & Live TV Change Notifications ----

    public static void onTvSettingsChangedNotification(Context context,
                                                       WebSocketClient ws,
                                                       long tvUpdatedAt,
                                                       String tvHash) {
        if (context == null || ws == null || !ws.isOpen()) return;
        Context app = context.getApplicationContext();
        long cachedAt = getCachedTvSettingsUpdatedAt(app);
        String cachedHash = getCachedTvSettingsHash(app);
        if (tvUpdatedAt > cachedAt || (tvHash != null && !tvHash.isEmpty() && !tvHash.equals(cachedHash))) {
            LAN_SYNC_IO.execute(() -> {
                try {
                    if (ws.isOpen()) {
                        ws.send(new JSONObject().put("type", "EXPORT_TV_BACKUP").toString());
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    public static void onTvHandshakeConnected(Context context,
                                              WebSocketClient ws,
                                              long liveTvUpdatedAt,
                                              String liveTvHash) {
        if (context == null || ws == null || !ws.isOpen()) return;
        final Context app = context.getApplicationContext();
        initAutoSync(app);
        LAN_SYNC_IO.execute(() -> {
            try {
                if (!ws.isOpen()) return;
                JSONObject pendingRestore = loadPendingTvRestore(app);
                if (pendingRestore != null) {
                    long pendingAt = pendingRestore.optLong("tv_settings_updated_at", 0L);
                    if (liveTvUpdatedAt > pendingAt) {
                        // Guard: Live TV is newer than queued cloud restore -> Pull from TV instead of overwriting!
                        clearPendingTvRestore(app);
                        ws.send(new JSONObject().put("type", "EXPORT_TV_BACKUP").toString());
                    } else {
                        // Guard: TV is fresh (0) or older than cloud -> Push restore to TV
                        ws.send(pendingRestore.toString());
                    }
                    return;
                }

                long cachedTvAt = getCachedTvSettingsUpdatedAt(app);
                String cachedTvHash = getCachedTvSettingsHash(app);
                if (liveTvUpdatedAt == 0L && cachedTvAt > 0L) {
                    // Fresh/empty TV connected to a Phone holding backed-up TV settings -> Push to TV
                    JSONObject cachedTv = loadCachedTvBackup(app);
                    JSONObject pushCmd = buildTvRestoreCommandFromTvBlock(app, cachedTv, cachedTvAt);
                    if (pushCmd != null && ws.isOpen()) {
                        ws.send(pushCmd.toString());
                    }
                } else if (liveTvUpdatedAt > cachedTvAt
                        || (liveTvUpdatedAt > 0L && liveTvHash != null && !liveTvHash.isEmpty() && !liveTvHash.equals(cachedTvHash))
                        || cachedTvAt == 0L) {
                    // Live TV has newer settings (or Phone has no TV cache yet) -> Pull from TV
                    ws.send(new JSONObject().put("type", "EXPORT_TV_BACKUP").toString());
                }
            } catch (Exception ignored) {}
        });
    }

    public static void onLiveTvBackupReceived(Context context, JSONObject tvBackupData, boolean scheduleCloudUpload) {
        if (context == null || tvBackupData == null) return;
        Context app = context.getApplicationContext();
        String prevHash = getCachedTvSettingsHash(app);
        long prevAt = getCachedTvSettingsUpdatedAt(app);

        cacheTvBackup(app, tvBackupData);
        long incomingAt = extractTvUpdatedAtFromLivePacket(tvBackupData);
        String incomingHash = tvBackupData.optString("tv_settings_hash", "");

        // Mirror shared keys/addons/subtitle config from newer TV snapshot onto Phone without triggering echo
        APPLYING_INCOMING_RESTORE.set(true);
        try {
            JSONObject apiKeys = tvBackupData.optJSONObject("api_keys");
            if (apiKeys != null) applyApiKeysToPhone(app, apiKeys);
            JSONArray addons = tvBackupData.optJSONArray("addons");
            if (addons != null && addons.length() > 0) {
                StremioAddons.mergeRemoteJson(app, addons);
            }
            String country = tvBackupData.optString(LiveTvPreferences.COUNTRY_FIELD, "");
            if (!country.isBlank()) LiveTvPreferences.saveCountry(app, country);
            LiveTvPreferences.importFavoriteCountriesJson(
                    app, tvBackupData.optJSONArray(LiveTvPreferences.FAVORITE_COUNTRIES_FIELD));
            JSONObject favCh = tvBackupData.optJSONObject(LiveChannelFavorites.FIELD);
            if (favCh != null) LiveChannelFavorites.merge(app, favCh);
            JSONObject subCfg = tvBackupData.optJSONObject("subtitle_config");
            if (subCfg != null && UnifiedSubtitleConfig.importSyncJson(app, subCfg, false)) {
                app.getSharedPreferences("cinema_subtitles", Context.MODE_PRIVATE).edit()
                        .putBoolean("dual_subs_enabled", UnifiedSubtitleConfig.dualSubtitlesEnabled(app))
                        .putInt("dual_sub_position", UnifiedSubtitleConfig.dualSubPosition(app))
                        .apply();
            }
            String updatedPhoneHash = computePhoneSettingsHash(app);
            if (!updatedPhoneHash.isEmpty()) {
                syncMeta(app).edit().putString(KEY_PHONE_HASH, updatedPhoneHash).commit();
            }
        } catch (Exception ignored) {
        } finally {
            APPLYING_INCOMING_RESTORE.set(false);
        }

        boolean changed = incomingAt > prevAt
                || (!incomingHash.isEmpty() && !incomingHash.equals(prevHash));
        if (scheduleCloudUpload && changed) {
            scheduleDebouncedCloudUpload(app, "tv_backup_pulled");
        }
    }

    public static void onTvRestoreConfirmed(Context context, JSONObject ackMsg) {
        if (context == null) return;
        clearPendingTvRestore(context);
        if (ackMsg != null) {
            long at = ackMsg.optLong("tv_settings_updated_at", 0L);
            String hash = ackMsg.optString("tv_settings_hash", "");
            SharedPreferences.Editor ed = syncMeta(context).edit();
            if (at > 0L) ed.putLong(KEY_CACHED_TV_UPDATED_AT, at);
            if (!hash.isEmpty()) ed.putString(KEY_CACHED_TV_HASH, hash);
            ed.apply();
        }
    }

    /**
     * Enforces Timestamp Conflict Guard when restoring a unified backup (from Google Drive or JSON):
     * - If Live TV is reachable and {@code liveTv.updatedAt > cloud.tv_settings.updatedAt}:
     *   NEVER overwrites TV; instead PULLS the newer live TV snapshot onto Phone and schedules a cloud update.
     * - If Live TV is empty ({@code updatedAt == 0}) or Cloud is newer:
     *   PUSHES {@code result.tvRestoreCommand} onto TV immediately.
     * - If TV is offline:
     *   Queues {@code pending_tv_restore.json} (carrying {@code tv_settings_updated_at}) so Handshake Guard
     *   verifies timestamps before applying when TV next connects.
     */
    public static TvReconcileOutcome reconcileRestoredTvSettingsBlocking(Context context,
                                                                         RestoreResult result,
                                                                         WebSocketClient activeSocket) {
        if (context == null || result == null || result.tvRestoreCommand == null) {
            return TvReconcileOutcome.NONE;
        }
        Context app = context.getApplicationContext();
        long cloudTvAt = result.tvSettingsUpdatedAt;

        JSONObject liveTv = fetchLiveTvBackupOverLan(app, 4500);
        if (liveTv != null) {
            long liveTvAt = extractTvUpdatedAtFromLivePacket(liveTv);
            String liveTvHash = liveTv.optString("tv_settings_hash", "");
            if (liveTvAt > cloudTvAt) {
                clearPendingTvRestore(app);
                onLiveTvBackupReceived(app, liveTv, true);
                return TvReconcileOutcome.PULLED_NEWER_FROM_TV;
            }
            if (liveTvAt > 0L && liveTvAt == cloudTvAt
                    && !result.tvSettingsHash.isEmpty()
                    && result.tvSettingsHash.equals(liveTvHash)) {
                clearPendingTvRestore(app);
                onLiveTvBackupReceived(app, liveTv, false);
                return TvReconcileOutcome.ALREADY_IN_SYNC;
            }
            savePendingTvRestore(app, result.tvRestoreCommand);
            if (activeSocket != null && activeSocket.isOpen()) {
                try {
                    activeSocket.send(result.tvRestoreCommand.toString());
                    return TvReconcileOutcome.PUSHED_TO_TV;
                } catch (Exception ignored) {}
            }
            if (pushTvRestoreOverLan(app, result, 5500)) {
                return TvReconcileOutcome.PUSHED_TO_TV;
            }
            return TvReconcileOutcome.QUEUED_FOR_OFFLINE_TV;
        }

        // Fallback if activeSocket is open even though sideband LAN probe timed out
        savePendingTvRestore(app, result.tvRestoreCommand);
        if (activeSocket != null && activeSocket.isOpen() && cloudTvAt > 0L && getCachedTvSettingsUpdatedAt(app) <= cloudTvAt) {
            try {
                activeSocket.send(result.tvRestoreCommand.toString());
                return TvReconcileOutcome.PUSHED_TO_TV;
            } catch (Exception ignored) {}
        }
        return TvReconcileOutcome.QUEUED_FOR_OFFLINE_TV;
    }

    // ---- File Caching & Serialization ----

    public static String defaultLocalFilename() {
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        return "LocalCinema-Backup-" + date + ".json";
    }

    private static final Object TV_CACHE_LOCK = new Object();

    public static void cacheTvBackup(Context context, JSONObject tvBackupData) {
        if (context == null || tvBackupData == null) return;
        try {
            android.util.AtomicFile file = new android.util.AtomicFile(new File(context.getFilesDir(), "last_tv_backup.json"));
            synchronized (TV_CACHE_LOCK) {
                FileOutputStream stream = file.startWrite();
                try {
                    stream.write(tvBackupData.toString().getBytes(StandardCharsets.UTF_8));
                    file.finishWrite(stream);
                } catch (IOException error) {
                    file.failWrite(stream);
                    throw error;
                }
                long tvAt = extractTvUpdatedAtFromLivePacket(tvBackupData);
                String tvHash = tvBackupData.optString("tv_settings_hash",
                        tvBackupData.optString("hash", ""));
                SharedPreferences.Editor ed = syncMeta(context).edit();
                if (tvAt > 0L) ed.putLong(KEY_CACHED_TV_UPDATED_AT, tvAt);
                if (!tvHash.isEmpty()) ed.putString(KEY_CACHED_TV_HASH, tvHash);
                ed.commit();
            }
        } catch (Exception ignored) {}
    }

    public static JSONObject loadCachedTvBackup(Context context) {
        if (context == null) return null;
        try {
            android.util.AtomicFile file = new android.util.AtomicFile(new File(context.getFilesDir(), "last_tv_backup.json"));
            synchronized (TV_CACHE_LOCK) {
                return new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void savePendingTvRestore(Context context, JSONObject cmd) {
        if (context == null || cmd == null) return;
        try {
            File f = new File(context.getFilesDir(), "pending_tv_restore.json");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(cmd.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    public static JSONObject loadPendingTvRestore(Context context) {
        if (context == null) return null;
        try {
            File f = new File(context.getFilesDir(), "pending_tv_restore.json");
            if (!f.isFile()) return null;
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void clearPendingTvRestore(Context context) {
        if (context == null) return;
        try {
            File f = new File(context.getFilesDir(), "pending_tv_restore.json");
            if (f.isFile()) //noinspection ResultOfMethodCallIgnored
                f.delete();
        } catch (Exception ignored) {}
    }

    private static long extractTvUpdatedAtFromLivePacket(JSONObject pkt) {
        if (pkt == null) return 0L;
        long at = pkt.optLong("tv_settings_updated_at", 0L);
        if (at > 0L) return at;
        at = pkt.optLong("updated_at", 0L);
        if (at > 0L) return at;
        JSONObject nested = pkt.optJSONObject("tv_settings");
        if (nested != null) {
            at = nested.optLong("updated_at", 0L);
            if (at > 0L) return at;
        }
        return 0L;
    }

    private static long extractTvUpdatedAtFromRoot(JSONObject root) {
        if (root == null) return 0L;
        JSONObject tvSettings = root.optJSONObject("tv_settings");
        if (tvSettings != null) {
            long at = tvSettings.optLong("updated_at", tvSettings.optLong("tv_settings_updated_at", 0L));
            if (at > 0L) return at;
        }
        JSONObject tv = root.optJSONObject("tv");
        if (tv != null) {
            long at = tv.optLong("updated_at", tv.optLong("tv_settings_updated_at", tv.optLong("exported_at", 0L)));
            if (at > 0L) return at;
        }
        return 0L;
    }

    private static JSONObject extractTvSectionFromRoot(JSONObject root) {
        if (root == null) return null;
        JSONObject tvSettings = root.optJSONObject("tv_settings");
        if (tvSettings != null) return tvSettings;
        return root.optJSONObject("tv");
    }

    public static JSONObject buildUnifiedBackup(Context context, JSONObject liveTvBackup) throws Exception {
        Context app = context.getApplicationContext();
        JSONObject tvSource = liveTvBackup != null ? liveTvBackup : loadCachedTvBackup(app);
        long now = System.currentTimeMillis();

        String phoneHash = computePhoneSettingsHash(app);
        long phoneUpdatedAt = getPhoneSettingsUpdatedAt(app);
        if (phoneUpdatedAt <= 0L) phoneUpdatedAt = now;

        long tvUpdatedAt = tvSource != null ? extractTvUpdatedAtFromLivePacket(tvSource) : getCachedTvSettingsUpdatedAt(app);
        String tvHash = tvSource != null
                ? tvSource.optString("tv_settings_hash", tvSource.optString("hash", getCachedTvSettingsHash(app)))
                : getCachedTvSettingsHash(app);

        JSONObject root = new JSONObject();
        root.put("format", "local_cinema_unified_backup");
        root.put("version", 2);
        root.put("created_at", now);
        root.put("phone_model", Build.MODEL);
        root.put("includes_live_tv", liveTvBackup != null);
        root.put("includes_cached_tv", tvSource != null);

        // Unified API Keys (Phone + TV fallback, including OMDb)
        JSONObject apiKeys = exportApiKeys(app);
        JSONObject tvKeys = tvSource != null ? tvSource.optJSONObject("api_keys") : null;
        if (tvKeys != null) {
            Iterator<String> ki = tvKeys.keys();
            while (ki.hasNext()) {
                String k = ki.next();
                String v = tvKeys.optString(k, "").trim();
                if (!v.isEmpty() && apiKeys.optString(k, "").isBlank()) {
                    apiKeys.put(k, v);
                }
            }
        }
        root.put("api_keys", apiKeys);

        // Unified Addons (prefer TV full snapshot with all configured manifest URLs when available)
        JSONArray tvAddons = tvSource != null ? tvSource.optJSONArray("addons") : null;
        JSONArray phoneAddons = StremioAddons.exportFullSnapshotJson(app);
        JSONArray addons = (tvAddons != null && tvAddons.length() > 0) ? tvAddons : phoneAddons;
        root.put("addons", addons);

        // Unified Live TV Preferred Countries & Favorites
        JSONObject liveTvPrefs = new JSONObject();
        liveTvPrefs.put(LiveTvPreferences.COUNTRY_FIELD, LiveTvPreferences.country(app));
        liveTvPrefs.put(LiveTvPreferences.FAVORITE_COUNTRIES_FIELD,
                LiveTvPreferences.exportFavoriteCountriesJson(app));
        root.put("live_tv_prefs", liveTvPrefs);

        // Unified Central Subtitle Config (newest snapshot across Phone & TV)
        JSONObject phoneSubCfg = UnifiedSubtitleConfig.exportSyncJson(app);
        JSONObject tvSubCfg = tvSource != null ? tvSource.optJSONObject("subtitle_config") : null;
        JSONObject unifiedSubCfg = (tvSubCfg != null
                && tvSubCfg.optLong("updated_at", 0L) > phoneSubCfg.optLong("updated_at", 0L))
                ? tvSubCfg : phoneSubCfg;
        root.put("subtitle_config", unifiedSubCfg);

        // ---- Separated Section 1: phone_settings (with independent updatedAt & hash) ----
        JSONObject browserHistory = exportBrowserHistory(app);
        JSONObject browserPrefs = serializeBrowserPrefs(app.getSharedPreferences("cinema_browser", Context.MODE_PRIVATE));
        JSONObject linkPrefs = serializePrefs(app.getSharedPreferences("cinema_link", Context.MODE_PRIVATE), true);
        JSONObject pairingObj = readPairingConfig(app);
        JSONObject subPrefs = serializePrefs(app.getSharedPreferences("cinema_subtitles", Context.MODE_PRIVATE), false);
        JSONObject libPrefs = serializePrefs(app.getSharedPreferences("cinema_library", Context.MODE_PRIVATE), false);
        JSONObject musicPrefs = serializePrefs(app.getSharedPreferences("cinema_music", Context.MODE_PRIVATE), false);
        JSONObject defaultPrefs = serializePrefs(PreferenceManager.getDefaultSharedPreferences(app), false);

        JSONObject phoneSettings = new JSONObject();
        phoneSettings.put("updated_at", phoneUpdatedAt);
        phoneSettings.put("hash", phoneHash);
        phoneSettings.put("phone_model", Build.MODEL);
        phoneSettings.put("default_prefs", defaultPrefs);
        phoneSettings.put("library_prefs", libPrefs);
        phoneSettings.put("music_prefs", musicPrefs);
        phoneSettings.put("subtitle_prefs", subPrefs);
        phoneSettings.put("subtitle_config", phoneSubCfg);
        phoneSettings.put("link_prefs", linkPrefs);
        if (pairingObj != null) {
            JSONObject safePairing = new JSONObject();
            safePairing.put("ip", pairingObj.optString("ip", pairingObj.optString("tv_ip", "")));
            safePairing.put("port", pairingObj.optInt("port", pairingObj.optInt("ws_port", 8765)));
            safePairing.put("tv_name", pairingObj.optString("tv_name", ""));
            phoneSettings.put("paired_tv_endpoint", safePairing);
        }
        phoneSettings.put("browser_prefs", browserPrefs);
        phoneSettings.put("browser_history", browserHistory);
        phoneSettings.put("trakt_and_keys", exportApiKeys(app));
        root.put("phone_settings", phoneSettings);
        root.put("phone", phoneSettings); // backward-compatible alias

        // ---- Separated Section 2: tv_settings (with independent updatedAt & hash) ----
        if (tvSource != null) {
            JSONObject tvSettings = new JSONObject();
            tvSettings.put("updated_at", tvUpdatedAt);
            tvSettings.put("tv_settings_updated_at", tvUpdatedAt);
            tvSettings.put("hash", tvHash);
            tvSettings.put("tv_settings_hash", tvHash);
            tvSettings.put("tv_model", tvSource.optString("tv_model", ""));
            tvSettings.put("exported_at", tvSource.optLong("exported_at", now));
            if (tvSource.has("tv_prefs")) tvSettings.put("tv_prefs", tvSource.optJSONObject("tv_prefs"));
            if (tvSource.has("family_skip_settings")) tvSettings.put("family_skip_settings", tvSource.optJSONObject("family_skip_settings"));
            if (tvSource.has("tv_link_options")) tvSettings.put("tv_link_options", tvSource.optJSONObject("tv_link_options"));
            tvSettings.put("api_keys", tvKeys != null ? tvKeys : apiKeys);
            tvSettings.put("addons", tvAddons != null ? tvAddons : addons);
            if (tvSource.has("addons_order")) tvSettings.put("addons_order", tvSource.optJSONArray("addons_order"));
            if (tvSource.has("addon_continue_watching")) {
                tvSettings.put("addon_continue_watching", tvSource.optBoolean("addon_continue_watching", true));
            }
            if (tvSource.has("smb_shares")) tvSettings.put("smb_shares", tvSource.optJSONObject("smb_shares"));
            if (tvSource.has(LiveTvPreferences.COUNTRY_FIELD)) {
                tvSettings.put(LiveTvPreferences.COUNTRY_FIELD, tvSource.optString(LiveTvPreferences.COUNTRY_FIELD, ""));
            }
            if (tvSource.has(LiveTvPreferences.FAVORITE_COUNTRIES_FIELD)) {
                tvSettings.put(LiveTvPreferences.FAVORITE_COUNTRIES_FIELD,
                        tvSource.optJSONArray(LiveTvPreferences.FAVORITE_COUNTRIES_FIELD));
            }
            if (tvSource.has(LiveChannelFavorites.FIELD)) {
                tvSettings.put(LiveChannelFavorites.FIELD, tvSource.optJSONObject(LiveChannelFavorites.FIELD));
            }
            tvSettings.put("subtitle_config", tvSubCfg != null ? tvSubCfg : unifiedSubCfg);
            if (tvSource.has("watch_records")) tvSettings.put("watch_records", tvSource.optJSONArray("watch_records"));
            root.put("tv_settings", tvSettings);
            root.put("tv", tvSettings); // backward-compatible alias
        }
        return root;
    }

    private static JSONObject buildTvRestoreCommandFromTvBlock(Context context, JSONObject tv, long tvUpdatedAt) throws Exception {
        if (tv == null) return null;
        JSONObject cmd = new JSONObject().put("type", "RESTORE_TV_BACKUP");
        cmd.put("tv_settings_updated_at", tvUpdatedAt > 0L ? tvUpdatedAt : System.currentTimeMillis());
        if (tv.optJSONObject("tv_prefs") != null) cmd.put("tv_prefs", tv.optJSONObject("tv_prefs"));
        if (tv.optJSONObject("family_skip_settings") != null) {
            cmd.put("family_skip_settings", tv.optJSONObject("family_skip_settings"));
        }
        if (tv.optJSONObject("tv_link_options") != null) cmd.put("tv_link_options", tv.optJSONObject("tv_link_options"));
        if (tv.optJSONObject("api_keys") != null) cmd.put("api_keys", tv.optJSONObject("api_keys"));
        else if (context != null) cmd.put("api_keys", exportApiKeys(context));
        if (tv.optJSONArray("addons") != null) cmd.put("addons", tv.optJSONArray("addons"));
        if (tv.optJSONArray("addons_order") != null) cmd.put("addons_order", tv.optJSONArray("addons_order"));
        if (tv.has("addon_continue_watching")) {
            cmd.put("addon_continue_watching", tv.optBoolean("addon_continue_watching", true));
        }
        if (tv.optJSONObject("smb_shares") != null) cmd.put("smb_shares", tv.optJSONObject("smb_shares"));
        String country = tv.optString(LiveTvPreferences.COUNTRY_FIELD, "");
        if (!country.isBlank()) cmd.put(LiveTvPreferences.COUNTRY_FIELD, country);
        if (tv.optJSONArray(LiveTvPreferences.FAVORITE_COUNTRIES_FIELD) != null) {
            cmd.put(LiveTvPreferences.FAVORITE_COUNTRIES_FIELD,
                    tv.optJSONArray(LiveTvPreferences.FAVORITE_COUNTRIES_FIELD));
        }
        if (tv.optJSONObject(LiveChannelFavorites.FIELD) != null) {
            cmd.put(LiveChannelFavorites.FIELD, tv.optJSONObject(LiveChannelFavorites.FIELD));
        }
        if (tv.optJSONObject("subtitle_config") != null) cmd.put("subtitle_config", tv.optJSONObject("subtitle_config"));
        return cmd;
    }

    public static RestoreResult restoreUnifiedBackup(Context context, String rawJson) throws Exception {
        Context app = context.getApplicationContext();
        String trimmed = rawJson == null ? "" : rawJson.trim();
        if (!trimmed.startsWith("{")) {
            throw new IOException("Selected file is not a valid JSON backup");
        }
        JSONObject root = new JSONObject(trimmed);
        String fmt = root.optString("format", "");
        if (!"local_cinema_unified_backup".equals(fmt)
                && !root.has("phone_settings") && !root.has("tv_settings")
                && !root.has("phone") && !root.has("tv") && !root.has("api_keys")) {
            throw new IOException("Unrecognized backup file format");
        }

        JSONObject phone = root.optJSONObject("phone_settings");
        if (phone == null) phone = root.optJSONObject("phone");
        JSONObject tv = extractTvSectionFromRoot(root);
        long cloudTvUpdatedAt = extractTvUpdatedAtFromRoot(root);
        String cloudTvHash = tv != null ? tv.optString("hash", tv.optString("tv_settings_hash", "")) : "";

        APPLYING_INCOMING_RESTORE.set(true);
        boolean apiKeysRestored;
        int restoredRecords = 0;
        JSONObject restoredSubCfg;
        JSONObject liveTvPrefs = root.optJSONObject("live_tv_prefs");
        JSONObject apiKeys = root.optJSONObject("api_keys");
        if (apiKeys == null && phone != null) apiKeys = phone.optJSONObject("trakt_and_keys");
        JSONArray addons = root.optJSONArray("addons");

        try {
            // 1. Restore API Keys on Phone
            apiKeysRestored = applyApiKeysToPhone(app, apiKeys);

            // 2. Restore Addons on Phone
            if (addons != null) {
                StremioAddons.mergeRemoteJson(app, addons);
            }

            // 2b. Restore Live TV Preferred Countries on Phone
            if (liveTvPrefs != null) {
                String restoredCountry = liveTvPrefs.optString(LiveTvPreferences.COUNTRY_FIELD, "");
                if (!restoredCountry.isBlank()) LiveTvPreferences.saveCountry(app, restoredCountry);
                LiveTvPreferences.importFavoriteCountriesJson(
                        app, liveTvPrefs.optJSONArray(LiveTvPreferences.FAVORITE_COUNTRIES_FIELD));
            }

            // 2c. Restore Central Unified Subtitle Config on Phone
            restoredSubCfg = root.optJSONObject("subtitle_config");
            if (restoredSubCfg == null && phone != null) restoredSubCfg = phone.optJSONObject("subtitle_config");
            if (restoredSubCfg == null && tv != null) restoredSubCfg = tv.optJSONObject("subtitle_config");
            if (restoredSubCfg != null) {
                UnifiedSubtitleConfig.importSyncJson(app, restoredSubCfg, true);
                app.getSharedPreferences("cinema_subtitles", Context.MODE_PRIVATE).edit()
                        .putBoolean("dual_subs_enabled", UnifiedSubtitleConfig.dualSubtitlesEnabled(app))
                        .putInt("dual_sub_position", UnifiedSubtitleConfig.dualSubPosition(app))
                        .apply();
            }

            // 3. Restore Phone Preferences (zero Trakt/watch list caching on Phone)
            if (phone != null) {
                if (phone.has("default_prefs")) {
                    applyPrefs(PreferenceManager.getDefaultSharedPreferences(app), phone.optJSONObject("default_prefs"));
                }
                if (phone.has("library_prefs")) {
                    applyPrefs(app.getSharedPreferences("cinema_library", Context.MODE_PRIVATE), phone.optJSONObject("library_prefs"));
                }
                if (phone.has("music_prefs")) {
                    applyPrefs(app.getSharedPreferences("cinema_music", Context.MODE_PRIVATE), phone.optJSONObject("music_prefs"));
                }
                if (phone.has("subtitle_prefs")) {
                    applyPrefs(app.getSharedPreferences("cinema_subtitles", Context.MODE_PRIVATE), phone.optJSONObject("subtitle_prefs"));
                }
                if (restoredSubCfg != null) {
                    app.getSharedPreferences("cinema_subtitles", Context.MODE_PRIVATE).edit()
                            .putBoolean("dual_subs_enabled", UnifiedSubtitleConfig.dualSubtitlesEnabled(app))
                            .putInt("dual_sub_position", UnifiedSubtitleConfig.dualSubPosition(app))
                            .apply();
                }
                if (phone.has("link_prefs")) {
                    applyPrefs(app.getSharedPreferences("cinema_link", Context.MODE_PRIVATE), phone.optJSONObject("link_prefs"));
                }
                if (phone.has("browser_prefs")) {
                    applyBrowserPrefs(app.getSharedPreferences("cinema_browser", Context.MODE_PRIVATE), phone.optJSONObject("browser_prefs"));
                }
                if (phone.has("browser_history")) {
                    mergeBrowserHistory(app, phone.optJSONObject("browser_history"));
                } else if (phone.has("browser_prefs")) {
                    mergeBrowserHistoryFromLegacyPrefs(app, phone.optJSONObject("browser_prefs"));
                }
                long restoredPhoneAt = phone.optLong("updated_at", System.currentTimeMillis());
                String restoredPhoneHash = computePhoneSettingsHash(app);
                syncMeta(app).edit()
                        .putLong(KEY_PHONE_UPDATED_AT, restoredPhoneAt)
                        .putString(KEY_PHONE_HASH, restoredPhoneHash)
                        .commit();
            }
        } finally {
            APPLYING_INCOMING_RESTORE.set(false);
        }

        // 4. Prepare TV Restore Payload (carrying tv_settings_updated_at for Conflict Guard)
        JSONObject tvRestoreCmd = new JSONObject()
                .put("type", "RESTORE_TV_BACKUP")
                .put("tv_settings_updated_at", cloudTvUpdatedAt > 0L ? cloudTvUpdatedAt : root.optLong("created_at", System.currentTimeMillis()));
        boolean hasTvPrefs = false;
        if (tv != null && tv.optJSONObject("tv_prefs") != null) {
            tvRestoreCmd.put("tv_prefs", tv.optJSONObject("tv_prefs"));
            hasTvPrefs = true;
        }
        if (tv != null && tv.optJSONObject("family_skip_settings") != null) {
            tvRestoreCmd.put("family_skip_settings", tv.optJSONObject("family_skip_settings"));
        }
        if (tv != null && tv.optJSONObject("tv_link_options") != null) {
            tvRestoreCmd.put("tv_link_options", tv.optJSONObject("tv_link_options"));
        }
        if (liveTvPrefs != null) {
            String restoredCountry = liveTvPrefs.optString(LiveTvPreferences.COUNTRY_FIELD, "");
            if (!restoredCountry.isBlank()) tvRestoreCmd.put(LiveTvPreferences.COUNTRY_FIELD, restoredCountry);
            JSONArray favCountries = liveTvPrefs.optJSONArray(LiveTvPreferences.FAVORITE_COUNTRIES_FIELD);
            if (favCountries != null) tvRestoreCmd.put(LiveTvPreferences.FAVORITE_COUNTRIES_FIELD, favCountries);
        } else if (tv != null) {
            String c = tv.optString(LiveTvPreferences.COUNTRY_FIELD, "");
            if (!c.isBlank()) tvRestoreCmd.put(LiveTvPreferences.COUNTRY_FIELD, c);
            if (tv.optJSONArray(LiveTvPreferences.FAVORITE_COUNTRIES_FIELD) != null) {
                tvRestoreCmd.put(LiveTvPreferences.FAVORITE_COUNTRIES_FIELD,
                        tv.optJSONArray(LiveTvPreferences.FAVORITE_COUNTRIES_FIELD));
            }
        }
        if (tv != null && tv.optJSONObject(LiveChannelFavorites.FIELD) != null) {
            tvRestoreCmd.put(LiveChannelFavorites.FIELD, tv.optJSONObject(LiveChannelFavorites.FIELD));
        }
        JSONObject tvKeys = tv != null && tv.optJSONObject("api_keys") != null ? tv.optJSONObject("api_keys") : apiKeys;
        if (tvKeys != null && tvKeys.length() > 0) {
            tvRestoreCmd.put("api_keys", tvKeys);
        }
        JSONArray tvAddons = (tv != null && tv.optJSONArray("addons") != null) ? tv.optJSONArray("addons") : addons;
        if (tvAddons != null) {
            tvRestoreCmd.put("addons", tvAddons);
        }
        if (tv != null && tv.optJSONArray("addons_order") != null) {
            tvRestoreCmd.put("addons_order", tv.optJSONArray("addons_order"));
        }
        if (tv != null && tv.has("addon_continue_watching")) {
            tvRestoreCmd.put("addon_continue_watching", tv.optBoolean("addon_continue_watching", true));
        }
        if (tv != null && tv.optJSONObject("smb_shares") != null) {
            tvRestoreCmd.put("smb_shares", tv.optJSONObject("smb_shares"));
        }
        if (restoredSubCfg != null) {
            tvRestoreCmd.put("subtitle_config", restoredSubCfg);
        }
        // TV is authoritative for Continue Watching & Trakt sync — never push watch_records from Phone onto TV.
        if (tv != null && loadCachedTvBackup(app) == null) {
            cacheTvBackup(app, tv);
        }
        return new RestoreResult(tvRestoreCmd, hasTvPrefs, restoredRecords, apiKeysRestored, cloudTvUpdatedAt, cloudTvHash);
    }

    public static String readUriText(Context context, Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException("Cannot open file");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > 12 * 1024 * 1024) throw new IOException("Backup file too large");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static void writeUriText(Context context, Uri uri, String content) throws IOException {
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("Cannot write file");
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---- Google Sign-In & Google Drive REST API v3 ----

    public static String resolveGoogleClientId(Context context) {
        if (context == null) return DEFAULT_GOOGLE_CLIENT_ID;
        String existing = ProviderKeys.get(context, ProviderKeys.GOOGLE_CLIENT_ID);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(CONFIG_URL).openConnection();
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(6500);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "LocalCinema/1.0");
            if (conn.getResponseCode() == 200) {
                String body = readStream(conn.getInputStream()).trim();
                if (body.startsWith("{")) {
                    JSONObject cfg = new JSONObject(body);
                    String cid = cfg.optString("googleClientId",
                            cfg.optString("google_client_id",
                                    cfg.optString("googleDriveClientId", ""))).trim();
                    if (!cid.isEmpty() && !cid.startsWith("<")) {
                        ProviderKeys.save(context, ProviderKeys.GOOGLE_CLIENT_ID, cid);
                        return cid;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return !existing.isBlank() ? existing.trim() : DEFAULT_GOOGLE_CLIENT_ID;
    }

    public static GoogleSignInClient buildGoogleSignInClient(Context context) {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(DRIVE_SCOPE_APPDATA), new Scope(DRIVE_SCOPE_FILE))
                .build();
        return GoogleSignIn.getClient(context, gso);
    }

    public static GoogleSignInAccount getSignedInGoogleAccount(Context context) {
        if (context == null) return null;
        return GoogleSignIn.getLastSignedInAccount(context);
    }

    private static String obtainDriveToken(Context context, GoogleSignInAccount account) throws Exception {
        Account acct = account != null ? account.getAccount() : null;
        if (acct == null && account != null && account.getEmail() != null) {
            acct = new Account(account.getEmail(), "com.google");
        }
        if (acct == null) throw new IOException("Google account email unavailable");
        String scope = "oauth2:" + DRIVE_SCOPE_APPDATA + " " + DRIVE_SCOPE_FILE;
        try {
            return GoogleAuthUtil.getToken(context, acct, scope);
        } catch (UserRecoverableAuthException e) {
            throw new DriveAuthRecoverableException(e.getIntent());
        }
    }

    public static final class CloudBackupInspection {
        public final boolean exists;
        public final String rawJson;
        public final int savedSitesCount;
        public final int watchCount;
        public final int addonCount;
        public final int apiKeyCount;
        public final long createdAtMs;
        public final long tvSettingsUpdatedAtMs;
        public final long phoneSettingsUpdatedAtMs;

        CloudBackupInspection(boolean exists, String rawJson, int savedSitesCount,
                              int watchCount, int addonCount, int apiKeyCount,
                              long createdAtMs, long tvSettingsUpdatedAtMs, long phoneSettingsUpdatedAtMs) {
            this.exists = exists;
            this.rawJson = rawJson;
            this.savedSitesCount = savedSitesCount;
            this.watchCount = watchCount;
            this.addonCount = addonCount;
            this.apiKeyCount = apiKeyCount;
            this.createdAtMs = createdAtMs;
            this.tvSettingsUpdatedAtMs = tvSettingsUpdatedAtMs;
            this.phoneSettingsUpdatedAtMs = phoneSettingsUpdatedAtMs;
        }
    }

    public static CloudBackupInspection inspectGoogleDriveBackup(Context context, GoogleSignInAccount account)
            throws Exception {
        if (context == null || account == null) {
            return new CloudBackupInspection(false, null, 0, 0, 0, 0, 0L, 0L, 0L);
        }
        String raw = downloadFromGoogleDrive(context, account);
        if (raw == null || raw.isBlank()) {
            return new CloudBackupInspection(false, null, 0, 0, 0, 0, 0L, 0L, 0L);
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{")) {
            return new CloudBackupInspection(false, null, 0, 0, 0, 0, 0L, 0L, 0L);
        }
        try {
            JSONObject root = new JSONObject(trimmed);
            JSONObject apiKeys = root.optJSONObject("api_keys");
            int keyCount = 0;
            if (apiKeys != null) {
                var it = apiKeys.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (!apiKeys.optString(k, "").isBlank()) keyCount++;
                }
            }
            JSONArray addons = root.optJSONArray("addons");
            int addonCount = addons != null ? addons.length() : 0;

            JSONObject phone = root.optJSONObject("phone_settings");
            if (phone == null) phone = root.optJSONObject("phone");
            JSONObject tv = extractTvSectionFromRoot(root);
            int watchCount = 0;
            if (tv != null && addonCount == 0 && tv.optJSONArray("addons") != null) {
                addonCount = tv.optJSONArray("addons").length();
            }

            int sitesCount = 0;
            if (phone != null) {
                JSONObject bh = phone.optJSONObject("browser_history");
                JSONArray sites = bh != null ? bh.optJSONArray("sites") : null;
                if (sites != null) {
                    sitesCount = sites.length();
                } else {
                    JSONObject bp = phone.optJSONObject("browser_prefs");
                    JSONObject legacySites = bp != null ? bp.optJSONObject("sites") : null;
                    if (legacySites != null) {
                        try {
                            sitesCount = new JSONArray(legacySites.optString("v", "[]")).length();
                        } catch (Exception ignored) {}
                    }
                }
            }

            boolean hasCustomPrefs = phone != null && (
                    (phone.optJSONObject("library_prefs") != null && phone.optJSONObject("library_prefs").length() > 0)
                            || (phone.optJSONObject("music_prefs") != null && phone.optJSONObject("music_prefs").length() > 0)
                            || (phone.optJSONObject("link_prefs") != null && phone.optJSONObject("link_prefs").length() > 0)
                            || (phone.optJSONObject("browser_prefs") != null && phone.optJSONObject("browser_prefs").length() > 1)
                            || (phone.optJSONObject("default_prefs") != null && phone.optJSONObject("default_prefs").length() > 1)
            );
            boolean hasTvPrefs = tv != null && tv.optJSONObject("tv_prefs") != null && tv.optJSONObject("tv_prefs").length() > 0;
            JSONObject subCfgInspect = root.optJSONObject("subtitle_config");
            boolean hasSubCfg = subCfgInspect != null && subCfgInspect.optLong("updated_at", 0L) > 0L;
            boolean valid = sitesCount > 0 || watchCount > 0 || addonCount > 0 || keyCount > 0 || hasCustomPrefs || hasTvPrefs || hasSubCfg;
            long createdAt = root.optLong("created_at", 0L);
            long tvAt = extractTvUpdatedAtFromRoot(root);
            long phAt = phone != null ? phone.optLong("updated_at", createdAt) : createdAt;
            return new CloudBackupInspection(valid, valid ? raw : null, sitesCount, watchCount, addonCount, keyCount, createdAt, tvAt, phAt);
        } catch (Exception ignored) {
            return new CloudBackupInspection(false, null, 0, 0, 0, 0, 0L, 0L, 0L);
        }
    }

    public static synchronized String uploadToGoogleDrive(Context context, GoogleSignInAccount account, String jsonPayload)
            throws Exception {
        String token = obtainDriveToken(context, account);
        String id;
        try {
            id = uploadToDriveSpace(token, jsonPayload, "appDataFolder", true);
        } catch (IOException authOrScopeErr) {
            String msg = String.valueOf(authOrScopeErr.getMessage());
            if (msg.contains("401")) {
                try { GoogleAuthUtil.clearToken(context, token); } catch (Exception ignored) {}
                token = obtainDriveToken(context, account);
                id = uploadToDriveSpace(token, jsonPayload, "appDataFolder", true);
            } else if (msg.contains("403") || msg.contains("400") || msg.contains("404")) {
                id = uploadToDriveSpace(token, jsonPayload, "drive", false);
            } else {
                throw authOrScopeErr;
            }
        }
        if (context != null) {
            try {
                String phHash = computePhoneSettingsHash(context.getApplicationContext());
                String tvHash = getCachedTvSettingsHash(context.getApplicationContext());
                syncMeta(context).edit()
                        .putBoolean(KEY_CLOUD_DIRTY, false)
                        .remove(KEY_DELETED_BACKUP_BASELINE)
                        .putString(KEY_CLOUD_FILE_ID, id)
                        .putString(KEY_LAST_DRIVE_HASH, phHash + "|" + tvHash)
                        .putLong(KEY_LAST_DRIVE_UPLOAD_AT, System.currentTimeMillis())
                        .apply();
            } catch (Exception ignored) {}
        }
        return id;
    }

    public static synchronized void deleteCloudBackups(Context context, GoogleSignInAccount account)
            throws Exception {
        String token = obtainDriveToken(context, account);
        List<String> ids = new ArrayList<>();
        for (String space : new String[]{"appDataFolder", "drive"}) {
            String pageToken = "";
            do {
                JSONObject page = listDriveBackups(token, space, pageToken, true);
                JSONArray files = page.getJSONArray("files");
                for (int i = 0; i < files.length(); i++) {
                    ids.add(files.getJSONObject(i).getString("id"));
                }
                pageToken = page.optString("nextPageToken", "");
            } while (!pageToken.isEmpty());
        }

        // Gather all IDs before deleting so the result set stays stable across list pages.
        String baseline = computePhoneSettingsHash(context) + "|" + getCachedTvSettingsHash(context);
        for (String id : ids) {
            HttpURLConnection conn = openDriveConnection(token,
                    "https://www.googleapis.com/drive/v3/files/" + Uri.encode(id));
            try {
                conn.setRequestMethod("DELETE");
                int code = conn.getResponseCode();
                if (code != 204 && code != 404) throw new IOException("Drive DELETE HTTP " + code);
            } finally {
                conn.disconnect();
            }
        }
        if (!syncMeta(context).edit()
                .remove(KEY_LAST_DRIVE_UPLOAD_AT)
                .remove(KEY_LAST_DRIVE_HASH)
                .remove(KEY_CLOUD_FILE_ID)
                .putBoolean(KEY_CLOUD_DIRTY, false)
                .putString(KEY_DELETED_BACKUP_BASELINE, baseline)
                .commit()) {
            throw new IOException("Failed to save cloud backup deletion state");
        }
    }

    private static String uploadToDriveSpace(String token, String jsonPayload, String space, boolean appData)
            throws Exception {
        String existingId = findDriveFileId(token, space);

        if (existingId != null) {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "https://www.googleapis.com/upload/drive/v3/files/" + existingId + "?uploadType=media").openConnection();
            conn.setRequestMethod("PATCH");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            }
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("Drive update HTTP " + code);
                return existingId;
            } finally {
                conn.disconnect();
            }
        } else {
            String boundary = "----LocalCinema_" + UUID.randomUUID();
            JSONObject meta = new JSONObject().put("name", DRIVE_BACKUP_FILENAME);
            if (appData) meta.put("parents", new JSONArray().put("appDataFolder"));
            String metadata = meta.toString();
            String body = "--" + boundary + "\r\n"
                    + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                    + metadata + "\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                    + jsonPayload + "\r\n"
                    + "--" + boundary + "--\r\n";

            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "multipart/related; boundary=" + boundary);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            try {
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("Drive upload HTTP " + code);
                JSONObject res = new JSONObject(readStream(conn.getInputStream()));
                return res.optString("id", "");
            } finally {
                conn.disconnect();
            }
        }
    }

    public static String downloadFromGoogleDrive(Context context, GoogleSignInAccount account) throws Exception {
        String token = obtainDriveToken(context, account);
        String fileId = findDriveFileId(token, "appDataFolder");
        if (fileId == null) {
            fileId = findDriveFileId(token, "drive");
        }
        if (fileId == null) {
            throw new IOException("NO_DRIVE_BACKUP_FOUND");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://www.googleapis.com/drive/v3/files/" + fileId + "?alt=media").openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        try {
            int code = conn.getResponseCode();
            if (code == 401) {
                try { GoogleAuthUtil.clearToken(context, token); } catch (Exception ignored) {}
                String fresh = obtainDriveToken(context, account);
                conn.disconnect();
                conn = (HttpURLConnection) new URL(
                        "https://www.googleapis.com/drive/v3/files/" + fileId + "?alt=media").openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("Authorization", "Bearer " + fresh);
                code = conn.getResponseCode();
            }
            if (code < 200 || code >= 300) throw new IOException("Drive download HTTP " + code);
            return readStream(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    /** Connects directly to paired TV over LAN to pull live TV backup before upload or restore check. */
    private record PendingTvBackup(WebSocketClient socket,
                                   java.util.concurrent.CompletableFuture<JSONObject> response) {}

    private static final java.util.concurrent.ConcurrentHashMap<String, PendingTvBackup> TV_BACKUP_REQUESTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    static boolean handleRequestedTvBackup(WebSocketClient socket, JSONObject response) {
        String id = response.optString("request_id");
        if (id.isEmpty()) return false;
        PendingTvBackup request = TV_BACKUP_REQUESTS.get(id);
        if (request != null && request.socket() == socket && response.optJSONObject("tv_prefs") != null) {
            request.response().complete(response);
        }
        return true;
    }

    public static JSONObject fetchLiveTvBackupOverLan(Context context, int timeoutMs) {
        WebSocketClient active = CinemaRemoteBridge.getConnectedSocket();
        if (active != null) {
            String id = UUID.randomUUID().toString();
            var future = new java.util.concurrent.CompletableFuture<JSONObject>();
            TV_BACKUP_REQUESTS.put(id, new PendingTvBackup(active, future));
            try {
                active.send(new JSONObject().put("type", "EXPORT_TV_BACKUP").put("request_id", id).toString());
                JSONObject live = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                android.util.Log.i("CinemaBackup", "Received TV settings over the active connection");
                cacheTvBackup(context, live);
                return live;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception ignored) {
                android.util.Log.i("CinemaBackup", "Active TV backup request failed: " + ignored.getClass().getSimpleName());
                // Recover through the paired backup connection if the active socket stopped responding.
            } finally {
                TV_BACKUP_REQUESTS.remove(id);
            }
        }
        JSONObject pairing = readPairingConfig(context);
        if (pairing == null) return null;
        String host = pairing.optString("ip", pairing.optString("tv_ip", pairing.optString("host", "")));
        int port = pairing.optInt("port", pairing.optInt("ws_port", 8765));
        String token = pairing.optString("token", "");
        if (host.isEmpty() || token.isEmpty()) return null;

        AtomicReference<JSONObject> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        WebSocketClient client = null;
        try {
            String phoneId = resolvePhoneId(context);
            String hello = new JSONObject().put("type", "REGISTER_PHONE")
                    .put("token", token).put("phone_id", phoneId)
                    .put("phone_name", Build.MODEL).put("scanned", false)
                    .put("lightweight_reconnect", true).put("backup_only", true).toString();
            client = new WebSocketClient(URI.create("ws://" + host + ":" + port), new Draft_6455(), null, 3500) {
                @Override public void onOpen(ServerHandshake handshake) { send(hello); }
                @Override public void onMessage(String message) {
                    try {
                        JSONObject msg = new JSONObject(message);
                        String t = msg.optString("type");
                        if ("CONNECTED".equals(t)) {
                            send(new JSONObject().put("type", "EXPORT_TV_BACKUP").toString());
                        } else if ("TV_BACKUP_DATA".equals(t) && msg.optJSONObject("tv_prefs") != null) {
                            android.util.Log.i("CinemaBackup", "Received TV settings over the backup connection");
                            cacheTvBackup(context, msg);
                            result.set(msg);
                            latch.countDown();
                        }
                    } catch (Exception ignored) {}
                }
                @Override public void onClose(int code, String reason, boolean remote) {
                    if (result.get() == null) android.util.Log.i("CinemaBackup", "TV backup session closed: " + code);
                    latch.countDown();
                }
                @Override public void onError(Exception ex) {
                    android.util.Log.i("CinemaBackup", "TV backup connection failed: " + ex.getClass().getSimpleName());
                    latch.countDown();
                }
            };
            var wifi = CinemaRemoteBridge.pairingWifi(context, java.net.InetAddress.getByName(host));
            if (wifi != null) client.setSocketFactory(wifi.getSocketFactory());
            client.connect();
            latch.await(Math.max(1500, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        } finally {
            if (client != null) try { client.close(); } catch (Exception ignored) {}
        }
        return result.get();
    }

    /** Connects directly to paired TV over LAN from Settings/anywhere and applies restored backup immediately. */
    public static boolean pushTvRestoreOverLan(Context context, RestoreResult restore, int timeoutMs) {
        if (restore == null || restore.tvRestoreCommand == null) return false;
        JSONObject pairing = readPairingConfig(context);
        if (pairing == null) return false;
        String host = pairing.optString("ip", pairing.optString("tv_ip", pairing.optString("host", "")));
        int port = pairing.optInt("port", pairing.optInt("ws_port", 8765));
        String token = pairing.optString("token", "");
        if (host.isEmpty() || token.isEmpty()) return false;

        AtomicBoolean ok = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        WebSocketClient client = null;
        try {
            String phoneId = resolvePhoneId(context);
            String hello = new JSONObject().put("type", "REGISTER_PHONE")
                    .put("token", token).put("phone_id", phoneId)
                    .put("phone_name", Build.MODEL).put("scanned", false)
                    .put("lightweight_reconnect", true).put("backup_only", true).toString();
            client = new WebSocketClient(URI.create("ws://" + host + ":" + port), new Draft_6455(), null, 3500) {
                @Override public void onOpen(ServerHandshake handshake) { send(hello); }
                @Override public void onMessage(String message) {
                    try {
                        JSONObject msg = new JSONObject(message);
                        String t = msg.optString("type");
                        if ("CONNECTED".equals(t)) {
                            long liveTvAt = msg.optLong("tv_settings_updated_at", 0L);
                            long cmdAt = restore.tvRestoreCommand.optLong("tv_settings_updated_at", 0L);
                            if (liveTvAt > cmdAt && cmdAt > 0L) {
                                // Safety net: Live TV became newer right before push -> abort overwrite!
                                clearPendingTvRestore(context);
                                latch.countDown();
                                return;
                            }
                            send(restore.tvRestoreCommand.toString());
                        } else if ("TV_BACKUP_RESTORED".equals(t)) {
                            if (msg.optBoolean("ok", true)) {
                                ok.set(true);
                                onTvRestoreConfirmed(context, msg);
                            }
                            latch.countDown();
                        }
                    } catch (Exception ignored) {}
                }
                @Override public void onClose(int code, String reason, boolean remote) { latch.countDown(); }
                @Override public void onError(Exception ex) { latch.countDown(); }
            };
            var wifi = CinemaRemoteBridge.pairingWifi(context, java.net.InetAddress.getByName(host));
            if (wifi != null) client.setSocketFactory(wifi.getSocketFactory());
            client.connect();
            latch.await(Math.max(1500, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        } finally {
            if (client != null) try { client.close(); } catch (Exception ignored) {}
        }
        return ok.get();
    }

    private static JSONObject readPairingConfig(Context context) {
        if (context == null) return null;
        String raw = context.getSharedPreferences("cinema_link", Context.MODE_PRIVATE).getString("pairing", null);
        if (raw == null || raw.isBlank()) return null;
        try { return new JSONObject(raw); } catch (Exception e) { return null; }
    }

    private static String resolvePhoneId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("cinema_link", Context.MODE_PRIVATE);
        String phoneId = prefs.getString("phone_id", null);
        if (phoneId == null) {
            phoneId = UUID.randomUUID().toString();
            prefs.edit().putString("phone_id", phoneId).apply();
        }
        return phoneId;
    }

    private static String findDriveFileId(String token, String space) throws Exception {
        JSONObject page = listDriveBackups(token, space, "", false);
        JSONArray files = page == null ? null : page.optJSONArray("files");
        return files != null && files.length() > 0 ? files.getJSONObject(0).optString("id", null) : null;
    }

    private static JSONObject listDriveBackups(String token, String space, String pageToken,
                                               boolean includeTrashed) throws Exception {
        String query = "name='" + DRIVE_BACKUP_FILENAME + "'";
        if (!includeTrashed) query += " and trashed=false";
        String url = "https://www.googleapis.com/drive/v3/files?spaces=" + space
                + "&q=" + Uri.encode(query) + "&orderBy=modifiedTime%20desc"
                + "&fields=nextPageToken,files(id)";
        if (!pageToken.isEmpty()) url += "&pageToken=" + Uri.encode(pageToken);

        HttpURLConnection conn = openDriveConnection(token, url);
        try {
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) return new JSONObject(readStream(conn.getInputStream()));
            if (includeTrashed) throw new IOException("Drive list HTTP " + code);
            return null;
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openDriveConnection(String token, String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        return conn;
    }

    public static Intent buildDriveInitialPickerCreateIntent() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, DRIVE_BACKUP_FILENAME);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(DRIVE_AUTHORITY_INITIAL_URI));
        }
        return intent;
    }

    public static Intent buildDriveInitialPickerOpenIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(DRIVE_AUTHORITY_INITIAL_URI));
        }
        return intent;
    }

    // ---- Internal Helpers & Deterministic Section Hash ----

    private static String computePhoneSettingsHash(Context app) {
        if (app == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            TreeMap<String, String> sorted = new TreeMap<>();

            SharedPreferences browserSp = app.getSharedPreferences("cinema_browser", Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : browserSp.getAll().entrySet()) {
                String k = e.getKey();
                if (k == null || e.getValue() == null) continue;
                if ("open_tabs".equals(k) || "selected_tab".equals(k) || "visit_details".equals(k) || "history".equals(k)) {
                    continue;
                }
                sorted.put("br:" + k, String.valueOf(e.getValue()));
            }
            SharedPreferences linkSp = app.getSharedPreferences("cinema_link", Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : linkSp.getAll().entrySet()) {
                String k = e.getKey();
                if (k == null || e.getValue() == null) continue;
                if ("full_sync_dirty".equals(k) || "phone_id".equals(k) || k.startsWith("cached_tv_addons")) continue;
                sorted.put("lnk:" + k, String.valueOf(e.getValue()));
            }
            SharedPreferences subSp = app.getSharedPreferences("cinema_subtitles", Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : subSp.getAll().entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    sorted.put("sub:" + e.getKey(), String.valueOf(e.getValue()));
                }
            }
            JSONObject uSub = UnifiedSubtitleConfig.exportSyncJson(app);
            uSub.remove("updated_at");
            sorted.put("usub", uSub.toString());

            sorted.put("k:trakt_user", ProviderKeys.get(app, ProviderKeys.TRAKT_USERNAME));
            sorted.put("k:trakt_acc", ProviderKeys.get(app, ProviderKeys.TRAKT_ACCESS_TOKEN));
            sorted.put("k:tmdb", ProviderKeys.get(app, ProviderKeys.TMDB));
            sorted.put("k:subsource", ProviderKeys.get(app, ProviderKeys.SUBSOURCE));
            sorted.put("k:os", ProviderKeys.get(app, ProviderKeys.OPEN_SUBTITLES));
            sorted.put("k:subdl", ProviderKeys.get(app, ProviderKeys.SUBDL));
            sorted.put("k:omdb", readFirstApiKey(app, "omdb"));
            sorted.put("addons", StremioAddons.exportFullSnapshotJson(app).toString());
            sorted.put("live_country", LiveTvPreferences.country(app));
            sorted.put("live_fav", LiveTvPreferences.exportFavoriteCountriesJson(app).toString());

            StringBuilder sb = new StringBuilder(2048);
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            byte[] dig = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(dig.length * 2);
            for (byte b : dig) hex.append(String.format(Locale.US, "%02x", b));
            return hex.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readFirstApiKey(Context context, String provider) {
        if (context == null) return "";
        try {
            List<String> keys = new ApiKeyStore(context, provider).read();
            if (keys != null && !keys.isEmpty() && keys.get(0) != null && !keys.get(0).isBlank()) {
                return keys.get(0).trim();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static JSONObject exportApiKeys(Context context) throws Exception {
        TraktSyncClient.migrateAndCleanCredentials(context);
        JSONObject keys = new JSONObject();
        putIfPresent(keys, "trakt_id", ProviderKeys.get(context, ProviderKeys.TRAKT_ID));
        putIfPresent(keys, "trakt_secret", ProviderKeys.get(context, ProviderKeys.TRAKT_SECRET));
        putIfPresent(keys, "trakt_access_token", ProviderKeys.get(context, ProviderKeys.TRAKT_ACCESS_TOKEN));
        putIfPresent(keys, "trakt_refresh_token", ProviderKeys.get(context, ProviderKeys.TRAKT_REFRESH_TOKEN));
        putIfPresent(keys, "trakt_expires_at", ProviderKeys.get(context, ProviderKeys.TRAKT_EXPIRES_AT));
        putIfPresent(keys, "trakt_username", ProviderKeys.get(context, ProviderKeys.TRAKT_USERNAME));
        putIfPresent(keys, "trakt_slug", ProviderKeys.get(context, ProviderKeys.TRAKT_SLUG));
        String subKey = ProviderKeys.get(context, ProviderKeys.SUBSOURCE);
        if (subKey.isBlank()) subKey = readFirstApiKey(context, "subsource");
        putIfPresent(keys, "subsource_key", subKey);
        putIfPresent(keys, "omdb_key", readFirstApiKey(context, "omdb"));
        putIfPresent(keys, "opensubtitles_key", ProviderKeys.get(context, ProviderKeys.OPEN_SUBTITLES));
        putIfPresent(keys, "subdl_key", ProviderKeys.get(context, ProviderKeys.SUBDL));
        putIfPresent(keys, "tmdb_key", ProviderKeys.get(context, ProviderKeys.TMDB));
        putIfPresent(keys, "google_client_id", ProviderKeys.get(context, ProviderKeys.GOOGLE_CLIENT_ID));
        return keys;
    }

    public static JSONObject buildFullSyncStateJson(Context context) {
        if (context == null) return new JSONObject();
        Context app = context.getApplicationContext();
        TraktSyncClient.migrateAndCleanCredentials(app);
        JSONObject payload = new JSONObject();
        try {
            payload.put("type", "FULL_SYNC_STATE");
            payload.put("phone_name", Build.MODEL == null ? "Phone" : Build.MODEL);
            payload.put("trakt_id", ProviderKeys.get(app, ProviderKeys.TRAKT_ID));
            payload.put("trakt_secret", ProviderKeys.get(app, ProviderKeys.TRAKT_SECRET));
            payload.put("trakt_access_token", ProviderKeys.get(app, ProviderKeys.TRAKT_ACCESS_TOKEN));
            payload.put("trakt_refresh_token", ProviderKeys.get(app, ProviderKeys.TRAKT_REFRESH_TOKEN));
            payload.put("trakt_expires_at", ProviderKeys.get(app, ProviderKeys.TRAKT_EXPIRES_AT));
            payload.put("trakt_username", ProviderKeys.get(app, ProviderKeys.TRAKT_USERNAME));
            payload.put("trakt_slug", ProviderKeys.get(app, ProviderKeys.TRAKT_SLUG));
            payload.put("trakt_signout", !TraktSyncClient.isConnected(app));
            payload.put("subsource_key", ProviderKeys.get(app, ProviderKeys.SUBSOURCE));
            String omdbKey = readFirstApiKey(app, "omdb");
            if (!omdbKey.isBlank()) payload.put("omdb_key", omdbKey);
            payload.put("opensubtitles_key", ProviderKeys.get(app, ProviderKeys.OPEN_SUBTITLES));
            payload.put("subdl_key", ProviderKeys.get(app, ProviderKeys.SUBDL));
            payload.put("tmdb_key", ProviderKeys.get(app, ProviderKeys.TMDB));
            JSONArray addons = StremioAddons.exportJson(app);
            payload.put("addons", addons != null ? addons : new JSONArray());
            SharedPreferences musicPrefs = app.getSharedPreferences("cinema_music", Context.MODE_PRIVATE);
            payload.put("preferred_playback_speed_percent", musicPrefs.getInt("pref_video_speed", 100));
            payload.put("remember_playback_speed", musicPrefs.getBoolean("pref_remember_video_speed", false));
            SharedPreferences subPrefs = app.getSharedPreferences("cinema_subtitles", Context.MODE_PRIVATE);
            payload.put("dual_sub_position", Math.max(0, Math.min(2, subPrefs.getInt("dual_sub_position", 1))));
            payload.put("subtitle_config", UnifiedSubtitleConfig.exportSyncJson(app));
        } catch (Exception ignored) {}
        return payload;
    }

    private static void putIfPresent(JSONObject obj, String key, String val) throws Exception {
        if (val != null && !val.isBlank()) obj.put(key, val.trim());
    }

    private static boolean applyApiKeysToPhone(Context context, JSONObject keys) {
        if (keys == null) return false;
        boolean any = false;
        any |= saveKeyIfPresent(context, ProviderKeys.TRAKT_ID, keys.optString("trakt_id", ""));
        any |= saveKeyIfPresent(context, ProviderKeys.TRAKT_SECRET, keys.optString("trakt_secret", ""));
        any |= saveKeyIfPresent(context, ProviderKeys.TRAKT_ACCESS_TOKEN, keys.optString("trakt_access_token", ""));
        any |= saveKeyIfPresent(context, ProviderKeys.TRAKT_REFRESH_TOKEN, keys.optString("trakt_refresh_token", ""));
        any |= saveKeyIfPresent(context, ProviderKeys.TRAKT_EXPIRES_AT, keys.optString("trakt_expires_at", ""));
        any |= saveKeyIfPresent(context, ProviderKeys.TRAKT_USERNAME, keys.optString("trakt_username", ""));
        any |= saveKeyIfPresent(context, ProviderKeys.TRAKT_SLUG, keys.optString("trakt_slug", ""));
        any |= saveKeyIfPresent(context, ProviderKeys.SUBSOURCE, keys.optString("subsource_key", ""));
        String omdb = keys.optString("omdb_key", "").trim();
        if (!omdb.isEmpty()) {
            try {
                new ApiKeyStore(context, "omdb").save(List.of(omdb));
                any = true;
            } catch (Exception ignored) {}
        }
        any |= saveKeyIfPresent(context, ProviderKeys.OPEN_SUBTITLES, keys.optString("opensubtitles_key", ""));
        any |= saveKeyIfPresent(context, ProviderKeys.SUBDL, keys.optString("subdl_key", ""));
        any |= saveKeyIfPresent(context, ProviderKeys.TMDB, keys.optString("tmdb_key", ""));
        any |= saveKeyIfPresent(context, ProviderKeys.GOOGLE_CLIENT_ID, keys.optString("google_client_id", ""));
        TraktSyncClient.migrateAndCleanCredentials(context);
        return any;
    }

    private static boolean saveKeyIfPresent(Context context, String prefKey, String value) {
        if (value == null || value.isBlank()) return false;
        ProviderKeys.save(context, prefKey, value.trim());
        return true;
    }

    public static void syncActivePairingOverLan(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastLanSyncAttemptMs < 15_000L) return;
        lastLanSyncAttemptMs = now;
        WebSocketClient active = CinemaRemoteBridge.getConnectedSocket();
        if (active == null || !active.isOpen()) {
            CinemaRemoteBridge.ensurePairedConnection(app);
            return;
        }
        LAN_SYNC_IO.execute(() -> {
            try {
                if (active.isOpen()) {
                    active.send(new JSONObject().put("type", "EXPORT_TV_BACKUP").toString());
                }
            } catch (Exception ignored) {}
        });
    }

    private static JSONObject serializeBrowserPrefs(SharedPreferences sp) {
        JSONObject root = serializePrefs(sp, false);
        root.remove("open_tabs");
        root.remove("selected_tab");
        root.remove("history");
        root.remove("visit_details");
        root.remove("sites");
        return root;
    }

    private static void applyBrowserPrefs(SharedPreferences sp, JSONObject src) {
        if (sp == null || src == null) return;
        try {
            JSONObject filtered = new JSONObject(src.toString());
            filtered.remove("open_tabs");
            filtered.remove("selected_tab");
            filtered.remove("history");
            filtered.remove("visit_details");
            filtered.remove("sites");
            applyPrefs(sp, filtered);
        } catch (Exception ignored) {}
    }

    private static JSONObject exportBrowserHistory(Context context) {
        JSONObject out = new JSONObject();
        SharedPreferences sp = context.getSharedPreferences("cinema_browser", Context.MODE_PRIVATE);
        try {
            out.put("history", new JSONArray(sp.getString("history", "[]")));
        } catch (Exception e) {
            try { out.put("history", new JSONArray()); } catch (Exception ignored) {}
        }
        try {
            out.put("visit_details", new JSONObject(sp.getString("visit_details", "{}")));
        } catch (Exception e) {
            try { out.put("visit_details", new JSONObject()); } catch (Exception ignored) {}
        }
        try {
            out.put("sites", new JSONArray(sp.getString("sites", "[]")));
        } catch (Exception e) {
            try { out.put("sites", new JSONArray()); } catch (Exception ignored) {}
        }
        return out;
    }

    private static void mergeBrowserHistoryFromLegacyPrefs(Context context, JSONObject legacyPrefs) {
        if (legacyPrefs == null) return;
        try {
            JSONObject wrapper = new JSONObject();
            JSONObject hWrap = legacyPrefs.optJSONObject("history");
            if (hWrap != null && "s".equals(hWrap.optString("t"))) {
                wrapper.put("history", new JSONArray(hWrap.optString("v", "[]")));
            }
            JSONObject dWrap = legacyPrefs.optJSONObject("visit_details");
            if (dWrap != null && "s".equals(dWrap.optString("t"))) {
                wrapper.put("visit_details", new JSONObject(dWrap.optString("v", "{}")));
            }
            JSONObject sWrap = legacyPrefs.optJSONObject("sites");
            if (sWrap != null && "s".equals(sWrap.optString("t"))) {
                wrapper.put("sites", new JSONArray(sWrap.optString("v", "[]")));
            }
            if (wrapper.length() > 0) mergeBrowserHistory(context, wrapper);
        } catch (Exception ignored) {}
    }

    private static void mergeBrowserHistory(Context context, JSONObject remote) {
        if (context == null || remote == null) return;
        SharedPreferences sp = context.getSharedPreferences("cinema_browser", Context.MODE_PRIVATE);
        try {
            JSONObject localDetails = new JSONObject(sp.getString("visit_details", "{}"));
            JSONObject remoteDetails = remote.optJSONObject("visit_details");
            if (remoteDetails != null) {
                Iterator<String> it = remoteDetails.keys();
                while (it.hasNext()) {
                    String url = it.next();
                    JSONObject rEntry = remoteDetails.optJSONObject(url);
                    if (rEntry == null) continue;
                    JSONObject lEntry = localDetails.optJSONObject(url);
                    if (lEntry == null || rEntry.optLong("time", 0L) >= lEntry.optLong("time", 0L)) {
                        localDetails.put(url, rEntry);
                    }
                }
            }

            JSONArray localHistory = new JSONArray(sp.getString("history", "[]"));
            JSONArray remoteHistory = remote.optJSONArray("history");
            List<String> mergedUrls = new ArrayList<>();
            Set<String> seenUrls = new HashSet<>();
            for (int i = 0; i < localHistory.length(); i++) {
                String u = localHistory.optString(i, "").trim();
                if (!u.isEmpty() && seenUrls.add(u)) mergedUrls.add(u);
            }
            if (remoteHistory != null) {
                for (int i = 0; i < remoteHistory.length(); i++) {
                    String u = remoteHistory.optString(i, "").trim();
                    if (!u.isEmpty() && seenUrls.add(u)) mergedUrls.add(u);
                }
            }
            final JSONObject detailsRef = localDetails;
            mergedUrls.sort((a, b) -> {
                JSONObject ea = detailsRef.optJSONObject(a);
                JSONObject eb = detailsRef.optJSONObject(b);
                long ta = ea != null ? ea.optLong("time", 0L) : 0L;
                long tb = eb != null ? eb.optLong("time", 0L) : 0L;
                return Long.compare(tb, ta);
            });
            JSONArray outHistory = new JSONArray();
            for (int i = 0; i < Math.min(mergedUrls.size(), 100); i++) {
                outHistory.put(mergedUrls.get(i));
            }

            JSONArray localSites = new JSONArray(sp.getString("sites", "[]"));
            JSONArray remoteSites = remote.optJSONArray("sites");
            java.util.LinkedHashMap<String, JSONObject> siteMap = new java.util.LinkedHashMap<>();
            for (int i = 0; i < localSites.length(); i++) {
                JSONObject s = localSites.optJSONObject(i);
                String u = s != null ? s.optString("url", "").trim() : "";
                if (!u.isEmpty()) siteMap.put(u, s);
            }
            if (remoteSites != null) {
                for (int i = 0; i < remoteSites.length(); i++) {
                    JSONObject s = remoteSites.optJSONObject(i);
                    String u = s != null ? s.optString("url", "").trim() : "";
                    if (!u.isEmpty() && !siteMap.containsKey(u)) siteMap.put(u, s);
                }
            }
            JSONArray outSites = new JSONArray();
            for (JSONObject s : siteMap.values()) outSites.put(s);

            var edit = sp.edit()
                    .putString("history", outHistory.toString())
                    .putString("visit_details", localDetails.toString())
                    .putString("sites", outSites.toString());
            if (remoteSites != null && remoteSites.length() > 0) {
                edit.putBoolean("default_sites_v2", true);
            }
            edit.apply();
            PhoneModuleBridges.browser().notifyBookmarksUpdated();
        } catch (Exception ignored) {}
    }

    private static JSONObject serializePrefs(SharedPreferences sp, boolean skipPairingToken) {
        JSONObject root = new JSONObject();
        if (sp == null) return root;
        for (Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (key == null || val == null) continue;
            if (skipPairingToken && "pairing".equals(key)) continue;
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
                } else if (val instanceof Set<?> set) {
                    JSONArray arr = new JSONArray();
                    for (Object item : set) {
                        if (item != null) arr.put(item.toString());
                    }
                    root.put(key, new JSONObject().put("t", "set").put("v", arr));
                }
            } catch (Exception ignored) {}
        }
        return root;
    }

    private static void applyPrefs(SharedPreferences sp, JSONObject src) {
        if (sp == null || src == null) return;
        SharedPreferences.Editor ed = sp.edit();
        Iterator<String> keys = src.keys();
        while (keys.hasNext()) {
            String key = keys.next();
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
                    JSONArray arr = wrapper.optJSONArray("v");
                    if (arr != null) {
                        Set<String> set = new HashSet<>();
                        for (int i = 0; i < arr.length(); i++) {
                            String val = arr.optString(i, null);
                            if (val != null) set.add(val);
                        }
                        ed.putStringSet(key, set);
                    }
                }
            }
        }
        ed.apply();
    }

    private static String readStream(InputStream in) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > 12 * 1024 * 1024) throw new IOException("Drive response too large");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

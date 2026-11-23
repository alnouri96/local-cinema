package org.proninyaroslav.libretorrent.cinema_hub;

import android.accounts.Account;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
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
import com.meedx.providers.ProviderKeys;
import com.meedx.providers.StremioAddons;
import com.meedx.providers.TraktSyncClient;

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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unified backup & restore manager covering Phone + TV preferences, API keys, addons,
 * watch history/progress, and watchlist — supporting local SAF JSON export/import and
 * Google Account (GoogleSignIn + Drive REST API v3) with system Drive picker fallback.
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

        public RestoreResult(JSONObject tvRestoreCommand, boolean hasTvPrefs,
                             int watchRecordsRestored, boolean apiKeysRestored) {
            this.tvRestoreCommand = tvRestoreCommand;
            this.hasTvPrefs = hasTvPrefs;
            this.watchRecordsRestored = watchRecordsRestored;
            this.apiKeysRestored = apiKeysRestored;
        }
    }

    private LocalCinemaBackupManager() {}

    public static String defaultLocalFilename() {
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        return "LocalCinema-Backup-" + date + ".json";
    }

    public static void cacheTvBackup(Context context, JSONObject tvBackupData) {
        if (context == null || tvBackupData == null) return;
        try {
            File f = new File(context.getFilesDir(), "last_tv_backup.json");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(tvBackupData.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    public static JSONObject loadCachedTvBackup(Context context) {
        if (context == null) return null;
        try {
            File f = new File(context.getFilesDir(), "last_tv_backup.json");
            if (!f.isFile()) return null;
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
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

    public static JSONObject buildUnifiedBackup(Context context, JSONObject liveTvBackup) throws Exception {
        JSONObject tvSource = liveTvBackup != null ? liveTvBackup : loadCachedTvBackup(context);
        JSONObject root = new JSONObject();
        root.put("format", "local_cinema_unified_backup");
        root.put("version", 1);
        root.put("created_at", System.currentTimeMillis());
        root.put("phone_model", Build.MODEL);
        root.put("includes_live_tv", liveTvBackup != null);
        root.put("includes_cached_tv", tvSource != null);

        // Unified API Keys (Phone + TV fallback)
        JSONObject apiKeys = exportApiKeys(context);
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

        // Unified Addons
        JSONArray addons = StremioAddons.exportJson(context);
        root.put("addons", addons);

        // Unified Live TV Preferred Countries
        JSONObject liveTvPrefs = new JSONObject();
        liveTvPrefs.put(com.meedx.providers.LiveTvPreferences.COUNTRY_FIELD,
                com.meedx.providers.LiveTvPreferences.country(context));
        liveTvPrefs.put(com.meedx.providers.LiveTvPreferences.FAVORITE_COUNTRIES_FIELD,
                com.meedx.providers.LiveTvPreferences.exportFavoriteCountriesJson(context));
        root.put("live_tv_prefs", liveTvPrefs);

        // Phone block
        JSONObject phone = new JSONObject();
        phone.put("watch_records", PhoneWatchStore.exportRecentSyncRecords(context, 1500));
        phone.put("watchlist", exportPhoneWatchlist(context));
        phone.put("default_prefs", serializePrefs(PreferenceManager.getDefaultSharedPreferences(context), false));
        phone.put("library_prefs", serializePrefs(context.getSharedPreferences("cinema_library", Context.MODE_PRIVATE), false));
        phone.put("music_prefs", serializePrefs(context.getSharedPreferences("cinema_music", Context.MODE_PRIVATE), false));
        phone.put("link_prefs", serializePrefs(context.getSharedPreferences("cinema_link", Context.MODE_PRIVATE), true));
        phone.put("browser_prefs", serializeBrowserPrefs(context.getSharedPreferences("cinema_browser", Context.MODE_PRIVATE)));
        phone.put("browser_history", exportBrowserHistory(context));
        root.put("phone", phone);

        // TV block
        if (tvSource != null) {
            JSONObject tv = new JSONObject();
            tv.put("tv_model", tvSource.optString("tv_model", ""));
            tv.put("exported_at", tvSource.optLong("exported_at", 0L));
            if (tvSource.has("tv_prefs")) tv.put("tv_prefs", tvSource.optJSONObject("tv_prefs"));
            if (tvSource.has("tv_link_options")) tv.put("tv_link_options", tvSource.optJSONObject("tv_link_options"));
            if (tvKeys != null) tv.put("api_keys", tvKeys);
            if (tvSource.has("addons")) tv.put("addons", tvSource.optJSONArray("addons"));
            if (tvSource.has("watch_records")) tv.put("watch_records", tvSource.optJSONArray("watch_records"));
            root.put("tv", tv);
        }
        return root;
    }

    public static RestoreResult restoreUnifiedBackup(Context context, String rawJson) throws Exception {
        String trimmed = rawJson == null ? "" : rawJson.trim();
        if (!trimmed.startsWith("{")) {
            throw new IOException("Selected file is not a valid JSON backup");
        }
        JSONObject root = new JSONObject(trimmed);
        String fmt = root.optString("format", "");
        if (!"local_cinema_unified_backup".equals(fmt)
                && !root.has("phone") && !root.has("tv") && !root.has("api_keys")) {
            throw new IOException("Unrecognized backup file format");
        }

        // 1. Restore API Keys on Phone
        JSONObject apiKeys = root.optJSONObject("api_keys");
        boolean apiKeysRestored = applyApiKeysToPhone(context, apiKeys);

        // 2. Restore Addons on Phone
        JSONArray addons = root.optJSONArray("addons");
        if (addons != null) {
            StremioAddons.mergeRemoteJson(context, addons);
        }

        // 2b. Restore Live TV Preferred Countries on Phone
        JSONObject liveTvPrefs = root.optJSONObject("live_tv_prefs");
        if (liveTvPrefs != null) {
            String restoredCountry = liveTvPrefs.optString(com.meedx.providers.LiveTvPreferences.COUNTRY_FIELD, "");
            if (!restoredCountry.isBlank()) com.meedx.providers.LiveTvPreferences.saveCountry(context, restoredCountry);
            com.meedx.providers.LiveTvPreferences.importFavoriteCountriesJson(
                    context, liveTvPrefs.optJSONArray(com.meedx.providers.LiveTvPreferences.FAVORITE_COUNTRIES_FIELD));
        }

        // 3. Restore Phone Watch Records, Watchlist, & Preferences
        int restoredRecords = 0;
        JSONObject phone = root.optJSONObject("phone");
        if (phone != null) {
            JSONArray phoneWatch = phone.optJSONArray("watch_records");
            if (phoneWatch != null) {
                for (int i = 0; i < phoneWatch.length(); i++) {
                    JSONObject rec = phoneWatch.optJSONObject(i);
                    if (rec != null && PhoneWatchStore.applyRemoteSyncRecord(context, rec, false)) {
                        restoredRecords++;
                    }
                }
            }
            JSONArray wl = phone.optJSONArray("watchlist");
            if (wl != null && wl.length() > 0) {
                PhoneWatchStore.saveWatchlist(context, wl);
            }
            if (phone.has("default_prefs")) {
                applyPrefs(PreferenceManager.getDefaultSharedPreferences(context), phone.optJSONObject("default_prefs"));
            }
            if (phone.has("library_prefs")) {
                applyPrefs(context.getSharedPreferences("cinema_library", Context.MODE_PRIVATE), phone.optJSONObject("library_prefs"));
            }
            if (phone.has("music_prefs")) {
                applyPrefs(context.getSharedPreferences("cinema_music", Context.MODE_PRIVATE), phone.optJSONObject("music_prefs"));
            }
            if (phone.has("link_prefs")) {
                applyPrefs(context.getSharedPreferences("cinema_link", Context.MODE_PRIVATE), phone.optJSONObject("link_prefs"));
            }
            if (phone.has("browser_prefs")) {
                applyBrowserPrefs(context.getSharedPreferences("cinema_browser", Context.MODE_PRIVATE), phone.optJSONObject("browser_prefs"));
            }
            if (phone.has("browser_history")) {
                mergeBrowserHistory(context, phone.optJSONObject("browser_history"));
            } else if (phone.has("browser_prefs")) {
                mergeBrowserHistoryFromLegacyPrefs(context, phone.optJSONObject("browser_prefs"));
            }
        }

        // 4. Prepare TV Restore Payload
        JSONObject tv = root.optJSONObject("tv");
        JSONObject tvRestoreCmd = new JSONObject().put("type", "RESTORE_TV_BACKUP");
        boolean hasTvPrefs = false;
        if (tv != null && tv.optJSONObject("tv_prefs") != null) {
            tvRestoreCmd.put("tv_prefs", tv.optJSONObject("tv_prefs"));
            hasTvPrefs = true;
        }
        if (tv != null && tv.optJSONObject("tv_link_options") != null) {
            tvRestoreCmd.put("tv_link_options", tv.optJSONObject("tv_link_options"));
        }
        if (liveTvPrefs != null) {
            String restoredCountry = liveTvPrefs.optString(com.meedx.providers.LiveTvPreferences.COUNTRY_FIELD, "");
            if (!restoredCountry.isBlank()) tvRestoreCmd.put(com.meedx.providers.LiveTvPreferences.COUNTRY_FIELD, restoredCountry);
            JSONArray favCountries = liveTvPrefs.optJSONArray(com.meedx.providers.LiveTvPreferences.FAVORITE_COUNTRIES_FIELD);
            if (favCountries != null) tvRestoreCmd.put(com.meedx.providers.LiveTvPreferences.FAVORITE_COUNTRIES_FIELD, favCountries);
        }
        JSONObject tvKeys = tv != null && tv.optJSONObject("api_keys") != null ? tv.optJSONObject("api_keys") : apiKeys;
        if (tvKeys != null && tvKeys.length() > 0) {
            tvRestoreCmd.put("api_keys", tvKeys);
        }
        JSONArray tvAddons = (tv != null && tv.optJSONArray("addons") != null) ? tv.optJSONArray("addons") : addons;
        if (tvAddons != null) {
            tvRestoreCmd.put("addons", tvAddons);
        }
        JSONArray tvWatch = (tv != null && tv.optJSONArray("watch_records") != null)
                ? tv.optJSONArray("watch_records")
                : (phone != null ? phone.optJSONArray("watch_records") : null);
        if (tvWatch != null) {
            for (int i = 0; i < tvWatch.length(); i++) {
                JSONObject rec = tvWatch.optJSONObject(i);
                if (rec != null && PhoneWatchStore.applyRemoteSyncRecord(context, rec, false)) {
                    restoredRecords++;
                }
            }
            tvRestoreCmd.put("watch_records", tvWatch);
        }
        return new RestoreResult(tvRestoreCmd, hasTvPrefs, restoredRecords, apiKeysRestored);
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

    /**
     * Resolves Google OAuth Client ID from encrypted local override or remote meedx.pages.dev/api/config.json
     * (ignoring HTML responses), falling back to the built-in public Android OAuth Client ID.
     */
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

        CloudBackupInspection(boolean exists, String rawJson, int savedSitesCount,
                              int watchCount, int addonCount, int apiKeyCount, long createdAtMs) {
            this.exists = exists;
            this.rawJson = rawJson;
            this.savedSitesCount = savedSitesCount;
            this.watchCount = watchCount;
            this.addonCount = addonCount;
            this.apiKeyCount = apiKeyCount;
            this.createdAtMs = createdAtMs;
        }
    }

    /**
     * Checks Google Drive AppData / Drive folder for a valid non-empty backup without
     * modifying cloud or local state. Safe to invoke immediately after Google Sign-In.
     */
    public static CloudBackupInspection inspectGoogleDriveBackup(Context context, GoogleSignInAccount account)
            throws Exception {
        if (context == null || account == null) {
            return new CloudBackupInspection(false, null, 0, 0, 0, 0, 0L);
        }
        String raw = downloadFromGoogleDrive(context, account);
        if (raw == null || raw.isBlank()) {
            return new CloudBackupInspection(false, null, 0, 0, 0, 0, 0L);
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{")) {
            return new CloudBackupInspection(false, null, 0, 0, 0, 0, 0L);
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

            JSONObject phone = root.optJSONObject("phone");
            JSONObject tv = root.optJSONObject("tv");
            int watchCount = 0;
            if (phone != null) {
                JSONArray pw = phone.optJSONArray("watch_records");
                if (pw != null) watchCount += pw.length();
                JSONArray pwl = phone.optJSONArray("watchlist");
                if (pwl != null) watchCount += pwl.length();
            }
            if (tv != null) {
                JSONArray tw = tv.optJSONArray("watch_records");
                if (tw != null) watchCount += tw.length();
                if (addonCount == 0 && tv.optJSONArray("addons") != null) {
                    addonCount = tv.optJSONArray("addons").length();
                }
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
            boolean valid = sitesCount > 0 || watchCount > 0 || addonCount > 0 || keyCount > 0 || hasCustomPrefs || hasTvPrefs;
            long createdAt = root.optLong("created_at", 0L);
            return new CloudBackupInspection(valid, valid ? raw : null, sitesCount, watchCount, addonCount, keyCount, createdAt);
        } catch (Exception ignored) {
            return new CloudBackupInspection(false, null, 0, 0, 0, 0, 0L);
        }
    }

    public static String uploadToGoogleDrive(Context context, GoogleSignInAccount account, String jsonPayload)
            throws Exception {
        String token = obtainDriveToken(context, account);
        try {
            return uploadToDriveSpace(token, jsonPayload, "appDataFolder", true);
        } catch (IOException authOrScopeErr) {
            String msg = String.valueOf(authOrScopeErr.getMessage());
            if (msg.contains("401")) {
                try { GoogleAuthUtil.clearToken(context, token); } catch (Exception ignored) {}
                token = obtainDriveToken(context, account);
                return uploadToDriveSpace(token, jsonPayload, "appDataFolder", true);
            }
            if (msg.contains("403") || msg.contains("400") || msg.contains("404")) {
                return uploadToDriveSpace(token, jsonPayload, "drive", false);
            }
            throw authOrScopeErr;
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

    /** Connects directly to paired TV over LAN to pull live TV backup before upload. */
    public static JSONObject fetchLiveTvBackupOverLan(Context context, int timeoutMs) {
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
                        } else if ("TV_BACKUP_DATA".equals(t)) {
                            cacheTvBackup(context, msg);
                            result.set(msg);
                            latch.countDown();
                        }
                    } catch (Exception ignored) {}
                }
                @Override public void onClose(int code, String reason, boolean remote) { latch.countDown(); }
                @Override public void onError(Exception ex) { latch.countDown(); }
            };
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
                            send(restore.tvRestoreCommand.toString());
                        } else if ("TV_BACKUP_RESTORED".equals(t)) {
                            if (msg.optBoolean("ok", true)) {
                                ok.set(true);
                                clearPendingTvRestore(context);
                            }
                            latch.countDown();
                        }
                    } catch (Exception ignored) {}
                }
                @Override public void onClose(int code, String reason, boolean remote) { latch.countDown(); }
                @Override public void onError(Exception ex) { latch.countDown(); }
            };
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

    private static String findDriveFileId(String token, String spaces) throws Exception {
        String q = android.net.Uri.encode("name='" + DRIVE_BACKUP_FILENAME + "' and trashed=false");
        String url = "https://www.googleapis.com/drive/v3/files?spaces=" + spaces
                + "&q=" + q + "&orderBy=modifiedTime%20desc&fields=files(id%2Cname%2CmodifiedTime%2Csize)";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            JSONObject obj = new JSONObject(readStream(conn.getInputStream()));
            JSONArray files = obj.optJSONArray("files");
            if (files != null && files.length() > 0) {
                return files.getJSONObject(0).optString("id", null);
            }
            return null;
        } finally {
            conn.disconnect();
        }
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

    // ---- Internal Helpers ----

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
        putIfPresent(keys, "subsource_key", ProviderKeys.get(context, ProviderKeys.SUBSOURCE));
        putIfPresent(keys, "opensubtitles_key", ProviderKeys.get(context, ProviderKeys.OPEN_SUBTITLES));
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
            payload.put("opensubtitles_key", ProviderKeys.get(app, ProviderKeys.OPEN_SUBTITLES));
            payload.put("tmdb_key", ProviderKeys.get(app, ProviderKeys.TMDB));
            JSONArray addons = StremioAddons.exportJson(app);
            payload.put("addons", addons != null ? addons : new JSONArray());
            JSONArray watchRecords = PhoneWatchStore.exportRecentSyncRecords(app, 600);
            payload.put("watch_records", watchRecords != null ? watchRecords : new JSONArray());
            JSONArray watchlist = PhoneWatchStore.exportWatchlistJson(app);
            payload.put("watchlist", watchlist != null ? watchlist : new JSONArray());
            SharedPreferences musicPrefs = app.getSharedPreferences("cinema_music", Context.MODE_PRIVATE);
            payload.put("preferred_playback_speed_percent", musicPrefs.getInt("pref_video_speed", 100));
            payload.put("remember_playback_speed", musicPrefs.getBoolean("pref_remember_video_speed", false));
            SharedPreferences subPrefs = app.getSharedPreferences("cinema_subtitles", Context.MODE_PRIVATE);
            payload.put("dual_sub_position", Math.max(0, Math.min(2, subPrefs.getInt("dual_sub_position", 1))));
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
        any |= saveKeyIfPresent(context, ProviderKeys.OPEN_SUBTITLES, keys.optString("opensubtitles_key", ""));
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

    private static JSONArray exportPhoneWatchlist(Context context) {
        JSONArray out = new JSONArray();
        List<PhoneWatchStore.WatchlistEntry> list = PhoneWatchStore.listWatchlist(context);
        for (PhoneWatchStore.WatchlistEntry w : list) {
            try {
                out.put(new JSONObject()
                        .put("media_type", w.mediaType)
                        .put("title", w.title)
                        .put("year", w.year)
                        .put("imdb_id", w.imdbId)
                        .put("tmdb_id", w.tmdbId)
                        .put("updated_at", w.updatedAt));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static volatile long lastLanSyncAttemptMs = 0L;
    private static final java.util.concurrent.ExecutorService LAN_SYNC_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor();

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
                JSONObject pendingRestore = loadPendingTvRestore(app);
                if (pendingRestore != null && active.isOpen()) active.send(pendingRestore.toString());
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
            // Merge visit_details keeping latest timestamp per URL
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

            // Merge history URLs preserving uniqueness (remote + local, sorted newest-first where timestamps exist)
            JSONArray localHistory = new JSONArray(sp.getString("history", "[]"));
            JSONArray remoteHistory = remote.optJSONArray("history");
            List<String> mergedUrls = new java.util.ArrayList<>();
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

            // Merge sites/bookmarks deduplicating by normalized URL
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
            CinemaBrowser.notifyBookmarksUpdated();
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

package com.meedx.providers;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class ProviderKeys {
    public static final String TMDB = "tmdb";
    public static final String TRAKT_ID = "trakt_id";
    public static final String TRAKT_SECRET = "trakt_secret";
    public static final String TRAKT_ACCESS_TOKEN = "trakt_access_token";
    public static final String TRAKT_REFRESH_TOKEN = "trakt_refresh_token";
    public static final String TRAKT_EXPIRES_AT = "trakt_expires_at";
    public static final String TRAKT_USERNAME = "trakt_username";
    public static final String TRAKT_SLUG = "trakt_slug";
    public static final String TRAKT_PKCE_VERIFIER = "trakt_pkce_verifier";
    public static final String TRAKT_PKCE_STATE = "trakt_pkce_state";
    public static final String OPEN_SUBTITLES = "opensubtitles";
    public static final String SUBSOURCE = "subsource_api_key";
    public static final String GOOGLE_CLIENT_ID = "google_client_id";

    private static final String STORE = "meedx_provider_keys";
    private static final String TRAKT_UNIFIED_PREFS = "trakt_sync_prefs";
    private static final String TYPO_TRAKT_ID = "ysZZKQ4X6LcHghckf1ReOIsuzELNIcyga17ZUBYSwSE";
    public static final String FIXED_TRAKT_ID = "ysZZKQ4X6LcHghcKf1ReOIsuzELNIcygal7ZUBYSwSE";
    private static final String ALIAS = "meedx_provider_keys_v1";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private ProviderKeys() {}

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    private static SharedPreferences unifiedTraktPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(TRAKT_UNIFIED_PREFS, Context.MODE_PRIVATE);
    }

    private static String unifiedKeyFor(String name) {
        if (name == null) return null;
        return switch (name) {
            case TRAKT_ID -> "trakt_client_id";
            case TRAKT_SECRET -> "trakt_client_secret";
            case TRAKT_ACCESS_TOKEN -> "trakt_access_token";
            case TRAKT_REFRESH_TOKEN -> "trakt_refresh_token";
            case TRAKT_EXPIRES_AT -> "trakt_expires_at";
            case TRAKT_USERNAME -> "trakt_username";
            case TRAKT_SLUG -> "trakt_slug";
            case TRAKT_PKCE_VERIFIER -> "trakt_pkce_verifier";
            case TRAKT_PKCE_STATE -> "trakt_pkce_state";
            default -> null;
        };
    }

    private static String normalizeValue(String name, String value) {
        String trimmed = value == null ? "" : value.trim();
        if (TRAKT_ID.equals(name)) {
            return FIXED_TRAKT_ID;
        }
        if (TRAKT_SECRET.equals(name)) {
            return "";
        }
        return trimmed;
    }

    public static synchronized void migrateTraktCredentials(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        unifiedTraktPrefs(app).edit()
                .putString("trakt_client_id", FIXED_TRAKT_ID)
                .remove("trakt_client_secret")
                .commit();
        preferences(app).edit().remove(TRAKT_SECRET).commit();
        String storedId = getRawInternal(app, TRAKT_ID);
        if (!FIXED_TRAKT_ID.equals(storedId)) {
            save(app, TRAKT_ID, FIXED_TRAKT_ID);
        }
    }

    private static SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
        if (existing != null) return existing;
        if (!create) throw new GeneralSecurityException("Key unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private static String getRawInternal(Context context, String name) {
        if (context == null || name == null) return "";
        String unifiedKey = unifiedKeyFor(name);
        if (TRAKT_PKCE_VERIFIER.equals(name) || TRAKT_PKCE_STATE.equals(name)) {
            String uVal = unifiedTraktPrefs(context).getString(unifiedKey, "").trim();
            if (!uVal.isEmpty()) return uVal;
        }
        String encoded = preferences(context).getString(name, "");
        if (!encoded.isEmpty()) {
            try {
                String[] parts = encoded.split(":", 2);
                if (parts.length == 2) {
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, key(false),
                            new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
                    cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
                    return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8).trim();
                }
            } catch (GeneralSecurityException | IOException | RuntimeException ignored) {}
        }
        if (unifiedKey != null) {
            return unifiedTraktPrefs(context).getString(unifiedKey, "").trim();
        }
        return "";
    }

    public static synchronized String get(Context context, String name) {
        if (context == null || name == null) return "";
        String val = getRawInternal(context, name);
        if (TRAKT_SECRET.equals(name)) {
            String curId = normalizeValue(TRAKT_ID, getRawInternal(context, TRAKT_ID));
            if (FIXED_TRAKT_ID.equals(curId)) {
                if (!val.isEmpty()) save(context, TRAKT_SECRET, "");
                return "";
            }
            return val;
        }
        String fixed = normalizeValue(name, val);
        if (!fixed.equals(val) || (TRAKT_ID.equals(name) && val.isEmpty())) {
            save(context, name, fixed);
        }
        return fixed;
    }

    public static synchronized boolean save(Context context, String name, String value) {
        if (context == null || name == null) return false;
        String normalized = normalizeValue(name, value);
        if (TRAKT_SECRET.equals(name)) {
            String curId = normalizeValue(TRAKT_ID, getRawInternal(context, TRAKT_ID));
            if (FIXED_TRAKT_ID.equals(curId)) {
                normalized = "";
            }
        }
        String unifiedKey = unifiedKeyFor(name);
        if (unifiedKey != null) {
            SharedPreferences.Editor uEdit = unifiedTraktPrefs(context).edit();
            if (normalized.isEmpty()) uEdit.remove(unifiedKey);
            else uEdit.putString(unifiedKey, normalized);
            uEdit.commit();
        }
        if (TRAKT_ID.equals(name) && FIXED_TRAKT_ID.equals(normalized)) {
            unifiedTraktPrefs(context).edit().remove("trakt_client_secret").commit();
            preferences(context).edit().remove(TRAKT_SECRET).commit();
        }
        SharedPreferences.Editor edit = preferences(context).edit();
        if (normalized.isEmpty()) return edit.remove(name).commit();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(cipher.doFinal(normalized.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
            return edit.putString(name, encoded).commit();
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            return false;
        }
    }

    public static String missing(Context context) {
        return "ar".equals(context.getResources().getConfiguration().locale.getLanguage())
                ? "أضف مفتاح API الخاص بك من الإعدادات لتفعيل جلب البيانات والترجمات"
                : "Add your API key in Settings to enable metadata and subtitle lookup.";
    }

    public static String require(Context context, String name) throws IOException {
        String value = get(context, name);
        if (value.isEmpty()) throw new IOException("Provider API key is not configured");
        return value;
    }
}

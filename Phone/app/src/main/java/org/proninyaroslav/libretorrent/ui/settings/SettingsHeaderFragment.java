/*
 * Copyright (C) 2025 Yaroslav Pronin <proninyaroslav@mail.ru>
 *
 * This file is part of LibreTorrent.
 *
 * LibreTorrent is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LibreTorrent is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LibreTorrent.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.proninyaroslav.libretorrent.ui.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.os.ConfigurationCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;

import org.json.JSONObject;
import org.proninyaroslav.libretorrent.R;
import org.proninyaroslav.libretorrent.cinema_hub.CinemaSnackBanner;
import org.proninyaroslav.libretorrent.cinema_hub.LocalCinemaBackupManager;
import org.proninyaroslav.libretorrent.cinema_hub.PhonePermissionsWizardDialog;
import org.proninyaroslav.libretorrent.core.utils.Utils;
import org.proninyaroslav.libretorrent.databinding.FragmentPreferenceBinding;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsHeaderFragment extends CustomPreferenceFragment {
    private static final String TAG = SettingsHeaderFragment.class.getSimpleName();

    private static final String TAG_CUR_OPEN_ITEM_KEY = "cur_open_item_key";

    public static final String KEY_OPEN_REFERENCE_REQUEST = TAG + "_open_preference";
    public static final String KEY_RESULT_PREFERENCE_KEY = "preference_key";

    private AppCompatActivity activity;
    private SettingsHeaderAdapter adapter;
    private String curOpenItemKey;
    private GoogleAccountHeaderPreference googleAccountPref;
    private StorageCacheHeaderPreference storageCachePref;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final int CLOUD_SIGN_IN_CHECK = 0;
    private static final int CLOUD_UPLOAD = 1;
    private static final int CLOUD_RESTORE = 2;
    private int pendingCloudMode = CLOUD_SIGN_IN_CHECK;
    private String pendingFallbackJson;

    private final ActivityResultLauncher<Intent> googleSignInLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::onGoogleSignInResult);

    private final ActivityResultLauncher<Intent> googleConsentLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            res -> {
                if (res.getResultCode() == Activity.RESULT_OK) {
                    var acct = LocalCinemaBackupManager.getSignedInGoogleAccount(requireContext());
                    if (acct != null) {
                        if (pendingCloudMode == CLOUD_SIGN_IN_CHECK) {
                            checkCloudBackupAfterSignIn(acct);
                        } else {
                            executeGoogleDriveTransfer(acct, pendingCloudMode == CLOUD_UPLOAD);
                        }
                    }
                } else {
                    setGoogleStatus(l("تم إلغاء منح صلاحية Google Drive.", "Google Drive permission cancelled."), false);
                }
            });

    private final ActivityResultLauncher<Intent> driveFallbackCreateLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            res -> {
                if (res.getResultCode() != Activity.RESULT_OK || res.getData() == null || res.getData().getData() == null) return;
                var uri = res.getData().getData();
                String payload = pendingFallbackJson;
                if (payload == null) return;
                io.execute(() -> {
                    try {
                        LocalCinemaBackupManager.writeUriText(requireContext(), uri, payload);
                        main.post(() -> {
                            if (isAdded()) {
                                String msg = l("تم حفظ النسخة الاحتياطية في Google Drive ✓", "Unified backup saved to Google Drive ✓");
                                setGoogleStatus(msg, false);
                            }
                        });
                    } catch (Exception e) {
                        main.post(() -> {
                            if (isAdded()) setGoogleStatus(l("فشل الحفظ: ", "Save failed: ") + e.getMessage(), true);
                        });
                    }
                });
            });

    private final ActivityResultLauncher<Intent> driveFallbackOpenLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            res -> {
                if (res.getResultCode() != Activity.RESULT_OK || res.getData() == null || res.getData().getData() == null) return;
                var uri = res.getData().getData();
                io.execute(() -> {
                    try {
                        String content = LocalCinemaBackupManager.readUriText(requireContext(), uri);
                        applyUnifiedRestoreBlocking(content);
                    } catch (Exception e) {
                        main.post(() -> {
                            if (isAdded()) setGoogleStatus(l("تعذّر الاسترجاع: ", "Restore failed: ") + e.getMessage(), true);
                        });
                    }
                });
            });

    private final ActivityResultLauncher<String> localBackupCreate = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri == null || !isAdded()) return;
                Context context = requireContext().getApplicationContext();
                setGoogleStatus(l("جارٍ تجهيز النسخة المحلية…", "Preparing local backup…"), false);
                io.execute(() -> {
                    try {
                        JSONObject liveTv = LocalCinemaBackupManager.fetchLiveTvBackupOverLan(context, 4500);
                        JSONObject backup = LocalCinemaBackupManager.buildUnifiedBackup(context, liveTv);
                        LocalCinemaBackupManager.writeUriText(context, uri, backup.toString(2));
                        main.post(() -> { if (isAdded()) setGoogleStatus(l("تم حفظ النسخة المحلية.", "Local backup saved."), false); });
                    } catch (Exception e) {
                        main.post(() -> { if (isAdded()) setGoogleStatus(l("تعذّر الحفظ: ", "Save failed: ") + e.getMessage(), true); });
                    }
                });
            });

    private final ActivityResultLauncher<String[]> localBackupOpen = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null || !isAdded()) return;
                Context context = requireContext().getApplicationContext();
                setGoogleStatus(l("جارٍ استعادة النسخة المحلية…", "Restoring local backup…"), false);
                io.execute(() -> {
                    try {
                        applyUnifiedRestoreBlocking(LocalCinemaBackupManager.readUriText(context, uri), context);
                    } catch (Exception e) {
                        main.post(() -> { if (isAdded()) setGoogleStatus(l("تعذّر الاسترجاع: ", "Restore failed: ") + e.getMessage(), true); });
                    }
                });
            });

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        if (context instanceof AppCompatActivity a) {
            activity = a;
        }
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);

        if (savedInstanceState != null) {
            curOpenItemKey = savedInstanceState.getString(TAG_CUR_OPEN_ITEM_KEY);
        }
        if (curOpenItemKey != null) {
            markAsOpen(curOpenItemKey);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putString(TAG_CUR_OPEN_ITEM_KEY, curOpenItemKey);

        super.onSaveInstanceState(outState);
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.pref_headers);
        googleAccountPref = new GoogleAccountHeaderPreference(
                requireContext(),
                this::startSettingsGoogleSignInCheck,
                () -> startSettingsGoogleBackup(true),
                () -> startSettingsGoogleBackup(false),
                this::signOutGoogle,
                () -> localBackupCreate.launch(LocalCinemaBackupManager.defaultLocalFilename()),
                () -> localBackupOpen.launch(new String[]{"application/json", "text/plain", "*/*"}));
        googleAccountPref.setOrder(-100);
        getPreferenceScreen().addPreference(googleAccountPref);

        storageCachePref = new StorageCacheHeaderPreference(requireContext());
        storageCachePref.setOrder(-90);
        getPreferenceScreen().addPreference(storageCachePref);

        var tvSync = new Preference(requireContext());
        tvSync.setKey("sync_settings_with_tv");
        tvSync.setTitle(R.string.cinema_sync_with_tv);
        tvSync.setSummary(R.string.cinema_sync_tv_description);
        tvSync.setOrder(-110);
        tvSync.setOnPreferenceClickListener(preference -> {
            org.proninyaroslav.libretorrent.cinema_hub.TvHubActivity.openSyncSettings(requireContext());
            return true;
        });
        getPreferenceScreen().addPreference(tvSync);

        var language = new Preference(requireContext());
        language.setKey("app_language");
        language.setTitle(R.string.locale_app);
        language.setIcon(R.drawable.ic_language_24);
        language.setOrder(500);
        language.setOnPreferenceClickListener(p -> {
            var current = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags();
            int checked = current.startsWith("ar") ? 1 : current.startsWith("en") ? 2 : 0;
            var names = new String[]{getString(R.string.locale_system), getString(R.string.locale_ar), getString(R.string.locale_en)};
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.locale_app)
                    .setSingleChoiceItems(names, checked, (dialog, index) -> {
                        dialog.dismiss();
                        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                                androidx.core.os.LocaleListCompat.forLanguageTags(new String[]{"", "ar", "en"}[index]));
                    }).setNegativeButton(android.R.string.cancel, null).show();
            return true;
        });
        getPreferenceScreen().addPreference(language);

        var updates = new Preference(requireContext());
        updates.setKey("meedx_updates");
        updates.setTitle(com.meedx.updates.AppUpdates.title(requireContext()));
        updates.setSummary(com.meedx.updates.AppUpdates.displayVersion(org.proninyaroslav.libretorrent.BuildConfig.VERSION_NAME));
        updates.setIcon(R.drawable.ic_system_update_24);
        updates.setOrder(502);
        updates.setOnPreferenceClickListener(p -> {
            com.meedx.updates.AppUpdates.settings(requireActivity(), false,
                new com.meedx.updates.AppUpdates.NoteLabels(R.string.cinema_update_patch_notes,
                        R.string.cinema_update_notes_missing));
            return true;
        });
        getPreferenceScreen().addPreference(updates);

        var permissions = new Preference(requireContext());
        permissions.setKey("permissions_wizard");
        permissions.setTitle(l("صلاحيات التطبيق والبطارية", "App Permissions & Battery Setup"));
        permissions.setSummary(l("التحقق من الصلاحيات الأساسية للخلفية والشبكة والتخزين",
                "Verify background, network, and storage permissions"));
        permissions.setIcon(R.drawable.ic_verified_shield_24);
        permissions.setOrder(503);
        permissions.setOnPreferenceClickListener(p -> {
            PhonePermissionsWizardDialog.show(requireActivity());
            return true;
        });
        getPreferenceScreen().addPreference(permissions);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        var binding = FragmentPreferenceBinding.bind(view);
        binding.appBar.setTitle(R.string.settings);
        binding.appBar.setNavigationIcon(null);

        if (activity == null) {
            activity = (AppCompatActivity) requireActivity();
        }

        for (var i = 0; i < getPreferenceScreen().getPreferenceCount(); i++) {
            var preference = getPreferenceScreen().getPreference(i);
            String key = preference.getKey();
            if ("google_account_cloud_backup".equals(key)
                    || "cinema_storage_and_cache".equals(key)
                    || "sync_settings_with_tv".equals(key)
                    || "external_api_keys".equals(key)
                    || "meedx_updates".equals(key)
                    || "app_language".equals(key)
                    || "permissions_wizard".equals(key)) continue;
            preference.setOnPreferenceClickListener((p) -> {
                markAsOpen(p.getKey());
                var bundle = new Bundle();
                bundle.putString(KEY_RESULT_PREFERENCE_KEY, p.getKey());
                getParentFragmentManager().setFragmentResult(KEY_OPEN_REFERENCE_REQUEST, bundle);
                return true;
            });
        }

        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (googleAccountPref != null) {
            googleAccountPref.setLiveStatus("", false);
        }
        if (storageCachePref != null) {
            storageCachePref.refreshBreakdown();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void startSettingsGoogleSignInCheck() {
        if (!isAdded()) return;
        pendingCloudMode = CLOUD_SIGN_IN_CHECK;
        GoogleSignInAccount existing = LocalCinemaBackupManager.getSignedInGoogleAccount(requireContext());
        if (existing != null && existing.getAccount() != null) {
            checkCloudBackupAfterSignIn(existing);
            return;
        }
        setGoogleStatus(l("جارٍ تحضير تسجيل الدخول بحساب Google...", "Preparing Google Sign-In..."), false);
        io.execute(() -> {
            LocalCinemaBackupManager.resolveGoogleClientId(requireContext());
            main.post(() -> {
                if (!isAdded()) return;
                try {
                    var client = LocalCinemaBackupManager.buildGoogleSignInClient(requireContext());
                    googleSignInLauncher.launch(client.getSignInIntent());
                } catch (Exception e) {
                    setGoogleStatus(l("تعذّر فتح شاشة تسجيل الدخول: ", "Unable to launch Google Sign-In: ")
                            + e.getMessage(), true);
                }
            });
        });
    }

    private void startSettingsGoogleBackup(boolean upload) {
        if (!isAdded()) return;
        pendingCloudMode = upload ? CLOUD_UPLOAD : CLOUD_RESTORE;
        GoogleSignInAccount existing = LocalCinemaBackupManager.getSignedInGoogleAccount(requireContext());
        if (existing != null && existing.getAccount() != null) {
            executeGoogleDriveTransfer(existing, upload);
            return;
        }
        setGoogleStatus(l("جارٍ تحضير تسجيل الدخول بحساب Google...", "Preparing Google Sign-In..."), false);
        io.execute(() -> {
            LocalCinemaBackupManager.resolveGoogleClientId(requireContext());
            main.post(() -> {
                if (!isAdded()) return;
                try {
                    var client = LocalCinemaBackupManager.buildGoogleSignInClient(requireContext());
                    googleSignInLauncher.launch(client.getSignInIntent());
                } catch (Exception e) {
                    triggerGooglePickerFallback(upload);
                }
            });
        });
    }

    private void onGoogleSignInResult(ActivityResult res) {
        if (!isAdded()) return;
        try {
            var task = GoogleSignIn.getSignedInAccountFromIntent(res.getData());
            var acct = task.getResult(ApiException.class);
            if (acct != null) {
                if (pendingCloudMode == CLOUD_SIGN_IN_CHECK) {
                    checkCloudBackupAfterSignIn(acct);
                } else {
                    if (googleAccountPref != null) googleAccountPref.setLiveStatus("", false);
                    executeGoogleDriveTransfer(acct, pendingCloudMode == CLOUD_UPLOAD);
                }
                return;
            }
        } catch (ApiException apiEx) {
            if (apiEx.getStatusCode() == CommonStatusCodes.CANCELED) {
                setGoogleStatus(l("تم إلغاء تسجيل الدخول بحساب Google.", "Google sign-in cancelled."), false);
                return;
            }
        } catch (Exception ignored) {}
        if (pendingCloudMode == CLOUD_SIGN_IN_CHECK) {
            setGoogleStatus(l("تعذّر تسجيل الدخول بحساب Google. يمكنك المحاولة مرة أخرى.",
                    "Google sign-in did not complete. Please try again."), true);
            return;
        }
        triggerGooglePickerFallback(pendingCloudMode == CLOUD_UPLOAD);
    }

    private void checkCloudBackupAfterSignIn(GoogleSignInAccount account) {
        if (!isAdded() || account == null) return;
        setGoogleStatus(l("تم تسجيل الدخول (" + account.getEmail() + ") — جارٍ فحص النسخة السحابية دون أي تعديل تلقائي...",
                "Signed in (" + account.getEmail() + ") — checking for existing cloud backup without auto-uploading..."), false);
        io.execute(() -> {
            try {
                var check = LocalCinemaBackupManager.inspectGoogleDriveBackup(requireContext(), account);
                main.post(() -> {
                    if (!isAdded()) return;
                    if (!check.exists || check.rawJson == null) {
                        setGoogleStatus(l("تم تسجيل الدخول ✓ — لا توجد نسخة سحابية صالحة محفوظة بعد. بياناتك وصفحاتك الحالية سليمة دون أي تعديل.",
                                "Signed in ✓ — no cloud backup found yet. Your current data & saved sites were kept intact."), false);
                        return;
                    }
                    String details = l(
                            "تم العثور على نسخة احتياطية سحابية — هل تريد استعادتها الآن؟\n\n"
                                    + "• الصفحات المحفوظة: " + check.savedSitesCount + "\n"
                                    + "• سجل المشاهدة: " + check.watchCount + "\n"
                                    + "• الإضافات: " + check.addonCount + " · المفاتيح: " + check.apiKeyCount + "\n\n"
                                    + "لن يتم الكتابة فوق نسختك السحابية عند اختيار الإبقاء على البيانات الحالية.",
                            "Cloud backup found — restore it now?\n\n"
                                    + "• Saved browser sites: " + check.savedSitesCount + "\n"
                                    + "• Watch items: " + check.watchCount + "\n"
                                    + "• Addons: " + check.addonCount + " · API keys: " + check.apiKeyCount + "\n\n"
                                    + "Keeping current data will not overwrite your cloud backup.");
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                            .setTitle(l("تم العثور على نسخة احتياطية سحابية", "Cloud Backup Found"))
                            .setMessage(details)
                            .setPositiveButton(l("استعادة الإعدادات والصفحات", "Restore Settings & Saved Sites"), (d, w) -> {
                                setGoogleStatus(l("جارٍ استعادة الإعدادات والصفحات المحفوظة من Google Drive...",
                                        "Restoring settings & saved sites from Google Drive..."), false);
                                io.execute(() -> {
                                    try {
                                        applyUnifiedRestoreBlocking(check.rawJson);
                                    } catch (Exception err) {
                                        main.post(() -> {
                                            if (isAdded()) setGoogleStatus(l("تعذّر الاستعادة: ", "Restore failed: ") + err.getMessage(), true);
                                        });
                                    }
                                });
                            })
                            .setNegativeButton(l("الإبقاء على البيانات الحالية", "Keep Current Data"), (d, w) ->
                                    setGoogleStatus(l("تم تسجيل الدخول ✓ — تم الإبقاء على بياناتك الحالية دون تغيير.",
                                            "Signed in ✓ — kept current data unchanged."), false))
                            .setCancelable(true)
                            .show();
                });
            } catch (LocalCinemaBackupManager.DriveAuthRecoverableException rec) {
                main.post(() -> {
                    if (isAdded() && rec.recoveryIntent != null) {
                        googleConsentLauncher.launch(rec.recoveryIntent);
                    } else if (isAdded()) {
                        setGoogleStatus(l("تم تسجيل الدخول ✓ — امنح صلاحية Google Drive لفحص النسخة أو استعد يدوياً.",
                                "Signed in ✓ — grant Google Drive access to check your cloud backup."), false);
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (isAdded()) {
                        setGoogleStatus(l("تم تسجيل الدخول ✓ — لا توجد نسخة سحابية متاحة حالياً (تم الحفاظ على بياناتك).",
                                "Signed in ✓ — no cloud backup available yet (local data preserved)."), false);
                    }
                });
            }
        });
    }

    private void executeGoogleDriveTransfer(GoogleSignInAccount account, boolean upload) {
        if (!isAdded()) return;
        if (upload) {
            setGoogleStatus(l("جارٍ الاتصال بالشاشة لجلب إعداداتها ورفع النسخة الشاملة إلى Google Drive...",
                    "Contacting TV & uploading unified backup to Google Drive..."), false);
            io.execute(() -> {
                try {
                    JSONObject liveTv = LocalCinemaBackupManager.fetchLiveTvBackupOverLan(requireContext(), 4500);
                    if (liveTv == null && requireContext().getSharedPreferences("cinema_link", Context.MODE_PRIVATE)
                            .contains("pairing")) {
                        main.post(() -> {
                            if (isAdded()) setGoogleStatus(l("تعذّر جلب إعدادات الشاشة. شغّل الشاشة وحاول مجددًا.",
                                    "Could not read live TV settings. Turn on the TV and retry."), true);
                        });
                        return;
                    }
                    JSONObject unified = LocalCinemaBackupManager.buildUnifiedBackup(requireContext(), liveTv);
                    String json = unified.toString(2);
                    LocalCinemaBackupManager.uploadToGoogleDrive(requireContext(), account, json);
                    boolean hadLive = liveTv != null;
                    main.post(() -> {
                        if (isAdded()) {
                            String msg = l("تم رفع النسخة الشاملة إلى حساب Google (" + account.getEmail() + ") بنجاح ✓"
                                            + (hadLive ? "\n(تم تضمين أحدث إعدادات الشاشة والهاتف معاً)"
                                            : "\n(تم تضمين إعدادات الهاتف)"),
                                    "Uploaded unified backup to Google (" + account.getEmail() + ") ✓"
                                            + (hadLive ? "\n(Included live TV + Phone data)"
                                            : "\n(Included Phone data)"));
                            setGoogleStatus(msg, false);
                        }
                    });
                } catch (LocalCinemaBackupManager.DriveAuthRecoverableException rec) {
                    main.post(() -> {
                        if (isAdded() && rec.recoveryIntent != null) {
                            googleConsentLauncher.launch(rec.recoveryIntent);
                        } else if (isAdded()) {
                            triggerGooglePickerFallback(true);
                        }
                    });
                } catch (Exception e) {
                    main.post(() -> {
                        if (isAdded()) triggerGooglePickerFallback(true);
                    });
                }
            });
        } else {
            setGoogleStatus(l("جارٍ البحث عن النسخة الشاملة في Google Drive...", "Searching Google Drive for unified backup..."), false);
            io.execute(() -> {
                try {
                    String content = LocalCinemaBackupManager.downloadFromGoogleDrive(requireContext(), account);
                    applyUnifiedRestoreBlocking(content);
                } catch (LocalCinemaBackupManager.DriveAuthRecoverableException rec) {
                    main.post(() -> {
                        if (isAdded() && rec.recoveryIntent != null) {
                            googleConsentLauncher.launch(rec.recoveryIntent);
                        } else if (isAdded()) {
                            triggerGooglePickerFallback(false);
                        }
                    });
                } catch (Exception e) {
                    main.post(() -> {
                        if (isAdded()) {
                            if ("NO_DRIVE_BACKUP_FOUND".equals(e.getMessage())) {
                                setGoogleStatus(l("لا توجد نسخة في مجلد التطبيق — جارٍ فتح منتقي Google Drive لاختيار ملف يدوياً...",
                                        "No app-folder backup found — opening Google Drive picker..."), false);
                            }
                            triggerGooglePickerFallback(false);
                        }
                    });
                }
            });
        }
    }

    private void applyUnifiedRestoreBlocking(String content) throws Exception {
        applyUnifiedRestoreBlocking(content, requireContext().getApplicationContext());
    }

    private void applyUnifiedRestoreBlocking(String content, Context context) throws Exception {
        var result = LocalCinemaBackupManager.restoreUnifiedBackup(context, content);
        if (result.tvRestoreCommand != null) {
            LocalCinemaBackupManager.savePendingTvRestore(context, result.tvRestoreCommand);
        }
        boolean pushedLive = result.tvRestoreCommand != null
                && LocalCinemaBackupManager.pushTvRestoreOverLan(context, result, 5500);
        main.post(() -> {
            if (!isAdded()) return;
            String msg = l("تم استرجاع النسخة الاحتياطية بنجاح ✓ (" + result.watchRecordsRestored + " سجل مشاهدة"
                            + (result.apiKeysRestored ? " + المفاتيح والإضافات" : "") + ")",
                    "Unified backup restored ✓ (" + result.watchRecordsRestored + " watch items"
                            + (result.apiKeysRestored ? " + API keys & addons" : "") + ")")
                    + (pushedLive
                    ? l("\nتم إرسال وتطبيق إعدادات النسخة على الشاشة مباشرة عبر الشبكة ✓",
                        "\nRestored & pushed settings directly to TV over LAN ✓")
                    : (result.tvRestoreCommand != null
                    ? l("\nتعذّر تطبيق إعدادات الشاشة. أعد المحاولة عندما تتصل الشاشة.",
                        "\nTV settings could not be applied. Retry when the TV is online.")
                    : ""));
            setGoogleStatus(msg, false);
        });
    }

    private void triggerGooglePickerFallback(boolean upload) {
        if (!isAdded()) return;
        if (upload) {
            setGoogleStatus(l("تم فتح منتقي Google Drive لحفظ النسخة مباشرة في حسابك.",
                    "Opening Google Drive picker to save backup directly."), false);
            io.execute(() -> {
                try {
                    JSONObject liveTv = LocalCinemaBackupManager.fetchLiveTvBackupOverLan(requireContext(), 4500);
                    JSONObject tvSrc = liveTv != null ? liveTv : LocalCinemaBackupManager.loadCachedTvBackup(requireContext());
                    String json = LocalCinemaBackupManager.buildUnifiedBackup(requireContext(), tvSrc).toString(2);
                    pendingFallbackJson = json;
                    main.post(() -> {
                        if (isAdded()) {
                            driveFallbackCreateLauncher.launch(LocalCinemaBackupManager.buildDriveInitialPickerCreateIntent());
                        }
                    });
                } catch (Exception e) {
                    main.post(() -> {
                        if (isAdded()) setGoogleStatus(l("تعذّر إنشاء النسخة: ", "Backup error: ") + e.getMessage(), true);
                    });
                }
            });
        } else {
            setGoogleStatus(l("اختر ملف local_cinema_unified_backup.json من Google Drive.",
                    "Select local_cinema_unified_backup.json from Google Drive."), false);
            try {
                driveFallbackOpenLauncher.launch(LocalCinemaBackupManager.buildDriveInitialPickerOpenIntent());
            } catch (Exception e) {
                setGoogleStatus(l("تعذّر فتح منتقي الملفات.", "Unable to open picker."), true);
            }
        }
    }

    private void signOutGoogle() {
        if (!isAdded()) return;
        try {
            LocalCinemaBackupManager.buildGoogleSignInClient(requireContext())
                    .signOut()
                    .addOnCompleteListener(task -> {
                        if (!isAdded()) return;
                        String msg = l("تم تسجيل الخروج من حساب Google ✓", "Signed out of Google account ✓");
                        setGoogleStatus(msg, false);
                    });
        } catch (Exception e) {
            setGoogleStatus(l("تعذّر تسجيل الخروج: ", "Sign-out failed: ") + e.getMessage(), true);
        }
    }

    private void setGoogleStatus(String msg, boolean isError) {
        if (googleAccountPref != null) {
            googleAccountPref.setLiveStatus(msg, isError);
        }
    }

    private boolean isArabic() {
        var locales = ConfigurationCompat.getLocales(getResources().getConfiguration());
        var loc = locales.isEmpty() ? java.util.Locale.getDefault() : locales.get(0);
        return loc != null && "ar".equalsIgnoreCase(loc.getLanguage());
    }

    private String l(String ar, String en) {
        return isArabic() ? ar : en;
    }

    @NonNull
    @Override
    protected SettingsHeaderAdapter onCreateAdapter(@NonNull PreferenceScreen preferenceScreen) {
        adapter = new SettingsHeaderAdapter(preferenceScreen);
        adapter.setSelectable(Utils.isTwoPane(activity));
        return adapter;
    }

    private void markAsOpen(String key) {
        curOpenItemKey = key;
        if (adapter != null) {
            adapter.markAsOpen(key);
        }
    }
}

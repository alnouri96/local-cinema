/*
 * Copyright (C) 2016-2025 Yaroslav Pronin <proninyaroslav@mail.ru>
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

package org.proninyaroslav.libretorrent;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.proninyaroslav.libretorrent.core.RepositoryHelper;
import org.proninyaroslav.libretorrent.core.settings.SettingsRepository;
import org.proninyaroslav.libretorrent.core.utils.Utils;
import org.proninyaroslav.libretorrent.databinding.DialogLinkifyTextBinding;
import org.proninyaroslav.libretorrent.receiver.NotificationReceiver;
import org.proninyaroslav.libretorrent.ui.NavBarFragment;
import org.proninyaroslav.libretorrent.ui.NavBarFragmentDirections;
import org.proninyaroslav.libretorrent.ui.PermissionDeniedDialog;
import org.proninyaroslav.libretorrent.ui.PermissionManager;
import org.proninyaroslav.libretorrent.ui.base.ThemeActivity;
import org.proninyaroslav.libretorrent.ui.home.HomeViewModel;

public class MainActivity extends ThemeActivity {
    private static final String TAG = MainActivity.class.getSimpleName();

    private static final String KEY_PERMISSION_DENIED_DIALOG_REQUEST = TAG + "_permission_denied";

    public static final String ACTION_ADD_TORRENT_SHORTCUT = "org.proninyaroslav.libretorrent.ADD_TORRENT_SHORTCUT";
    public static final String ACTION_OPEN_TORRENT_DETAILS = "com.meedx.localcinema.manager.ACTION_OPEN_TORRENT_DETAILS";
    public static final String KEY_TORRENT_ID = "torrent_id";
    public static final String EXTRA_SELECT_TAB = "cinema_select_tab";
    private static boolean launchedInitialHub;

    private NavController navController;
    private org.proninyaroslav.libretorrent.cinema_hub.CinemaBrowser sharedCinemaBrowser;

    public org.proninyaroslav.libretorrent.cinema_hub.CinemaBrowser getOrCreateCinemaBrowser(
            android.widget.FrameLayout host,
            android.view.View upstream,
            java.util.function.Consumer<android.net.Uri> addTorrent,
            Runnable pickExtension) {
        if (sharedCinemaBrowser == null) {
            sharedCinemaBrowser = new org.proninyaroslav.libretorrent.cinema_hub.CinemaBrowser(
                    host, upstream, addTorrent, pickExtension);
        } else {
            sharedCinemaBrowser.reattachHost(host, upstream, addTorrent, pickExtension);
        }
        return sharedCinemaBrowser;
    }
    private HomeViewModel viewModel;
    private PermissionManager permissionManager;
    private SettingsRepository pref;

    @NonNull
    public NavController getRootNavController() {
        return navController;
    }

    @Nullable
    public NavBarFragment findNavBarFragment(@NonNull Fragment fragment) {
        var currentFragment = fragment;
        while (currentFragment != null) {
            if (currentFragment instanceof NavBarFragment navBar) {
                return navBar;
            } else {
                currentFragment = currentFragment.getParentFragment();
            }
        }
        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setLayoutDirection(getResources().getConfiguration().getLayoutDirection());
        applyTabWindowTransitions(this);
        com.meedx.updates.AppUpdates.startup(this, false,
                new com.meedx.updates.AppUpdates.NoteLabels(R.string.cinema_update_patch_notes,
                        R.string.cinema_update_notes_missing));

        Utils.enableEdgeToEdge(this);

        var intentAction = getIntent().getAction();
        if (intentAction != null && intentAction.equals(NotificationReceiver.NOTIFY_ACTION_SHUTDOWN_APP)) {
            finish();
            return;
        }

        org.proninyaroslav.libretorrent.cinema_hub.CinemaCacheManager.performStartupMaintenance(getApplicationContext());
        org.proninyaroslav.libretorrent.core.model.TorrentEngine.getInstance(getApplicationContext()).startIfPending();

        getSupportFragmentManager().setFragmentResultListener(
                KEY_PERMISSION_DENIED_DIALOG_REQUEST,
                this,
                (requestKey, result) -> {
                    var resultValue = (PermissionDeniedDialog.Result)
                            result.getSerializable(PermissionDeniedDialog.KEY_RESULT_VALUE);
                    if (resultValue != null) {
                        switch (resultValue) {
                            case RETRY -> permissionManager.requestPermissions();
                            case DENIED -> permissionManager.setDoNotAskStorage(true);
                        }
                    }
                }
        );

        ViewModelProvider provider = new ViewModelProvider(this);
        viewModel = provider.get(HomeViewModel.class);
        permissionManager = new PermissionManager(this, new PermissionManager.Callback() {
            @Override
            public void onStorageResult(boolean isGranted, boolean shouldRequestStoragePermission) {
                if (!isGranted && shouldRequestStoragePermission) {
                    var action = NavBarFragmentDirections
                            .actionPermissionDeniedDialog(KEY_PERMISSION_DENIED_DIALOG_REQUEST);
                    navController.navigate(action);
                }
            }

            @Override
            public void onNotificationResult(boolean isGranted, boolean shouldRequestNotificationPermission) {
                permissionManager.setDoNotAskNotifications(!isGranted);
                if (isGranted) {
                    viewModel.restartForegroundNotification();
                }
            }
        });
        pref = RepositoryHelper.getSettingsRepository(getApplicationContext());

        setContentView(R.layout.activity_main);

        showManageAllFilesWarningDialog();

        var fm = getSupportFragmentManager();
        var navHostFragment = (NavHostFragment) fm.findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment != null) {
            navController = navHostFragment.getNavController();
        }

        var dest = navController.getCurrentDestination();
        var dialogExists = dest != null && dest.getId() == R.id.permissionDeniedDialog;
        boolean wizardPending = !androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(org.proninyaroslav.libretorrent.cinema_hub.PhonePermissionsWizardDialog.PREF_SHOWN, false);
        if (wizardPending) {
            getWindow().getDecorView().post(() ->
                    org.proninyaroslav.libretorrent.cinema_hub.PhonePermissionsWizardDialog.maybeShowFirstLaunch(this));
        } else if (!permissionManager.checkPermissions() && !dialogExists) {
            permissionManager.requestPermissions();
        }

        getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                var navHost = getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
                if (navHost != null) {
                    var child = navHost.getChildFragmentManager().getPrimaryNavigationFragment();
                    if (child instanceof org.proninyaroslav.libretorrent.ui.NavBarFragment navBar
                            && navBar.handleBackPressed()) {
                        return;
                    }
                    for (var f : navHost.getChildFragmentManager().getFragments()) {
                        if (f instanceof org.proninyaroslav.libretorrent.ui.NavBarFragment navBar
                                && f.isVisible() && navBar.handleBackPressed()) {
                            return;
                        }
                    }
                }
                if (navController == null) {
                    return;
                }
                if (!navController.navigateUp()) {
                    finish();
                }
            }
        });

        boolean hasExternalData = getIntent().getData() != null
                || getIntent().hasExtra(Intent.EXTRA_TEXT)
                || getIntent().hasExtra(Intent.EXTRA_STREAM)
                || getIntent().hasExtra(KEY_TORRENT_ID)
                || getIntent().hasExtra(EXTRA_SELECT_TAB);
        if (savedInstanceState == null && !launchedInitialHub && !hasExternalData) {
            launchedInitialHub = true;
            startActivity(new Intent(this, org.proninyaroslav.libretorrent.cinema_hub.TvHubActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
            overridePendingTransition(0, 0);
        }
    }

    public static void applyTabWindowTransitions(android.app.Activity activity) {
        activity.getWindow().setWindowAnimations(0);
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0, 0xff101113);
            activity.overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0, 0xff101113);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sharedCinemaBrowser != null) {
            sharedCinemaBrowser.onHostResumed();
        }
        org.proninyaroslav.libretorrent.cinema_hub.FloatingTvDpadController.attach(this);
        org.proninyaroslav.libretorrent.cinema_hub.LocalCinemaBackupManager.syncActivePairingOverLan(this);
    }

    @Override
    protected void onPause() {
        if (sharedCinemaBrowser != null) {
            sharedCinemaBrowser.onHostPaused();
        }
        org.proninyaroslav.libretorrent.cinema_hub.FloatingTvDpadController.detach(this);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (intent.getAction() != null
                && intent.getAction().equals(NotificationReceiver.NOTIFY_ACTION_SHUTDOWN_APP)) {
            finish();
            return;
        }

        setIntent(intent);

        var navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment != null) {
            for (var fragment : navHostFragment.getChildFragmentManager().getFragments()) {
                if (fragment instanceof NavBarFragment navBarFragment) {
                    if (intent.hasExtra(EXTRA_SELECT_TAB)) {
                        int tabId = intent.getIntExtra(EXTRA_SELECT_TAB, 0);
                        intent.removeExtra(EXTRA_SELECT_TAB);
                        if (tabId != 0) navBarFragment.selectBottomTab(tabId);
                    }
                    if (intent.hasExtra("open_url")) {
                        String openUrl = intent.getStringExtra("open_url");
                        intent.removeExtra("open_url");
                        navBarFragment.openBrowserUrl(openUrl);
                    }
                    navBarFragment.handleImplicitIntent();
                    break;
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (sharedCinemaBrowser != null) {
            sharedCinemaBrowser.dispose();
            sharedCinemaBrowser = null;
        }
        if (viewModel != null) {
            viewModel.requestStopEngine();
        }

        super.onDestroy();
    }

    public void showManageAllFilesWarningDialog() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || Utils.hasManageExternalStoragePermission(getApplicationContext())
                || !pref.showManageAllFilesWarningDialog()) {
            return;
        }

        pref.showManageAllFilesWarningDialog(false);

        var binding = DialogLinkifyTextBinding.inflate(
                LayoutInflater.from(this),
                null,
                false
        );
        binding.message.append(getString(R.string.manage_all_files_warning_dialog_description));
        new MaterialAlertDialogBuilder(this)
                .setIcon(R.drawable.ic_warning_24px)
                .setTitle(R.string.manage_all_files_warning_dialog_title)
                .setView(binding.getRoot())
                .setPositiveButton(R.string.ok, (dialog, which) -> dialog.dismiss())
                .show();
    }
}

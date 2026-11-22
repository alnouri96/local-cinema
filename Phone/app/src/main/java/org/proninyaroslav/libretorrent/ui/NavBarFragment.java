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

package org.proninyaroslav.libretorrent.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;
import androidx.transition.TransitionManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.motion.MotionUtils;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.navigationrail.NavigationRailView;
import com.google.android.material.transition.MaterialFade;

import org.proninyaroslav.libretorrent.FeedNavDirections;
import org.proninyaroslav.libretorrent.MainActivity;
import org.proninyaroslav.libretorrent.R;
import org.proninyaroslav.libretorrent.core.utils.Utils;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class NavBarFragment extends Fragment {
    private static final String TAG = NavBarFragment.class.getSimpleName();

    private MainActivity activity;
    private NavController navController;
    private NavigationRailView navRail = null;
    private org.proninyaroslav.libretorrent.cinema_hub.CinemaBrowser cinemaBrowser;
    private final androidx.activity.result.ActivityResultLauncher<String[]> extensionPicker =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (cinemaBrowser != null && uri != null) cinemaBrowser.installExtensionFile(uri);
                    });
    private BottomNavigationView bottomNav = null;
    private PlaybackBubble playbackBubble;
    private boolean feedsFromBrowser;
    private final Runnable miniCastListener = this::refreshMiniCastBar;
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private NavBarFragmentViewModel viewModel;
    private final CompositeDisposable disposables = new CompositeDisposable();

    public void setNavRailHeaderView(@NonNull View view) {
        if (!isAdded() || navRail == null) {
            return;
        }
        animateHeaderTransition();
        navRail.addHeaderView(view);
    }

    public void removeNavRailHeaderView() {
        if (!isAdded() || navRail == null) {
            return;
        }
        animateHeaderTransition();
        navRail.removeHeaderView();
    }

    public void removeDrawerNavigationView() {
        if (!isAdded() || drawerLayout == null || navigationView == null) {
            return;
        }
        navigationView.removeAllViews();
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
    }

    @NonNull
    public DrawerLayout getDrawerLayout() {
        return drawerLayout;
    }

    @NonNull
    public NavigationView getNavigationView() {
        return navigationView;
    }

    private NavigationBarView getNavigationBarView() {
        return navRail == null ? bottomNav : navRail;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        if (context instanceof MainActivity a) {
            activity = a;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_nav_bar, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (activity == null) {
            activity = (MainActivity) requireActivity();
        }

        viewModel = new ViewModelProvider(this).get(NavBarFragmentViewModel.class);

        var navHostFragment = (NavHostFragment) getChildFragmentManager()
                .findFragmentById(R.id.nav_bar_host_fragment);
        if (navHostFragment != null) {
            navController = navHostFragment.getNavController();
        }

        drawerLayout = view.findViewById(R.id.drawer_layout);
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        navigationView = view.findViewById(R.id.navigation_view);
        navRail = view.findViewById(R.id.navigation_rail);
        bottomNav = view.findViewById(R.id.bottom_navigation);
        setupMiniCastBar(view);
        if (navRail != null) {
            NavigationUI.setupWithNavController(navRail, navController);
        }
        if (bottomNav != null) {
            bottomNav.getMenu().removeItem(R.id.feed_nav);
            NavigationUI.setupWithNavController(bottomNav, navController);
            cinemaBrowser = activity.getOrCreateCinemaBrowser(
                    view.findViewById(R.id.cinema_content), view.findViewById(R.id.nav_bar_host_fragment),
                    uri -> handleUri(uri, null), () -> extensionPicker.launch(new String[]{"*/*"}));
            cinemaBrowser.setOpenFeedsAction(this::openBrowserFeeds);
            bottomNav.setLabelVisibilityMode(com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED);
            bottomNav.setItemHorizontalTranslationEnabled(false);
            bottomNav.setOnItemSelectedListener(item -> {
                feedsFromBrowser = false;
                if (item.getItemId() == R.id.cinema_hub_nav) {
                    org.proninyaroslav.libretorrent.cinema_hub.TvHubActivity.open(requireContext());
                    return false;
                }
                if (item.getItemId() == R.id.cinema_browser_nav) {
                    cinemaBrowser.open();
                    return true;
                }
                cinemaBrowser.hide();
                var options = new androidx.navigation.NavOptions.Builder()
                        .setLaunchSingleTop(true)
                        .setRestoreState(true)
                        .setPopUpTo(androidx.navigation.NavGraph.Companion.findStartDestination(
                                navController.getGraph()).getId(), false, true)
                        .build();
                navController.navigate(item.getItemId(), null, options);
                return true;
            });
            bottomNav.setOnItemReselectedListener(item -> {
                if (item.getItemId() == R.id.cinema_browser_nav) cinemaBrowser.open();
            });
        }

        activity.getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (handleBackPressed()) {
                    return;
                }
                if (navController == null || activity == null) {
                    return;
                }
                if (!navController.navigateUp() && !activity.getRootNavController().navigateUp()) {
                    activity.finish();
                }
            }
        });

        handleImplicitIntent();
        int initialTab = activity.getIntent().getIntExtra(MainActivity.EXTRA_SELECT_TAB, 0);
        if (initialTab != 0) {
            activity.getIntent().removeExtra(MainActivity.EXTRA_SELECT_TAB);
            selectBottomTab(initialTab);
        }
        String pendingUrl = activity.getIntent().getStringExtra("open_url");
        if (pendingUrl != null) {
            activity.getIntent().removeExtra("open_url");
            openBrowserUrl(pendingUrl);
        }
    }

    public void openBrowserUrl(String url) {
        if (url == null || url.isBlank()) return;
        if (bottomNav != null) bottomNav.setSelectedItemId(R.id.cinema_browser_nav);
        if (cinemaBrowser != null) {
            cinemaBrowser.open();
            cinemaBrowser.openUrl(url);
        }
    }

    private void openBrowserFeeds() {
        if (navController.getCurrentDestination() == null
                || navController.getCurrentDestination().getId() != R.id.feedFragment) {
            navController.navigate(R.id.feed_nav, null, new androidx.navigation.NavOptions.Builder()
                    .setLaunchSingleTop(true).build());
        }
        feedsFromBrowser = true;
        cinemaBrowser.hide();
    }

    public boolean handleBackPressed() {
        if (feedsFromBrowser && navController.getCurrentDestination() != null
                && navController.getCurrentDestination().getId() == R.id.feedFragment) {
            feedsFromBrowser = false;
            navController.navigateUp();
            bottomNav.setSelectedItemId(R.id.cinema_browser_nav);
            cinemaBrowser.open();
            return true;
        }
        if (cinemaBrowser != null && cinemaBrowser.isShowing()) {
            if (!cinemaBrowser.goBack() && bottomNav != null) {
                bottomNav.setSelectedItemId(R.id.home_nav);
            }
            return true;
        }
        return false;
    }

    public void selectBottomTab(@IdRes int menuId) {
        if (bottomNav != null && menuId != 0 && menuId != R.id.cinema_hub_nav) {
            bottomNav.setSelectedItemId(menuId);
            if (menuId == R.id.cinema_browser_nav && cinemaBrowser != null) {
                cinemaBrowser.open();
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshMiniCastBar();
    }

    @Override
    public void onDestroyView() {
        org.proninyaroslav.libretorrent.cinema_hub.CinemaRemoteBridge.removePlaybackListener(miniCastListener);
        if (playbackBubble != null) playbackBubble.detach();
        playbackBubble = null;
        if (cinemaBrowser != null) {
            cinemaBrowser.detachHost();
            cinemaBrowser = null;
        }
        super.onDestroyView();
    }

    private void setupMiniCastBar(@NonNull View root) {
        android.widget.FrameLayout content = root.findViewById(R.id.cinema_content);
        if (content == null) return;
        playbackBubble = new PlaybackBubble(content, () -> {
            int currentTab = bottomNav != null ? bottomNav.getSelectedItemId() : R.id.home_nav;
            org.proninyaroslav.libretorrent.cinema_hub.TvHubActivity.openPlaybackFromTab(requireContext(), currentTab);
        });
        org.proninyaroslav.libretorrent.cinema_hub.CinemaRemoteBridge.addPlaybackListener(miniCastListener);
        refreshMiniCastBar();
    }

    private void refreshMiniCastBar() {
        if (playbackBubble != null && isAdded()) playbackBubble.refresh();
    }

    public void handleImplicitIntent() {
        var i = activity.getIntent();
        Uri uri = null;
        // Implicit intent with path to torrent file, http, magnet link or RSS/Atom feed
        if (i.getData() != null) {
            uri = i.getData();
        } else if (i.hasExtra(Intent.EXTRA_TEXT)) {
            var text = i.getStringExtra(Intent.EXTRA_TEXT);
            uri = TextUtils.isEmpty(text) ? null : Uri.parse(text);
        } else if (i.hasExtra(Intent.EXTRA_STREAM) && i.getExtras() != null) {
            uri = (Uri) i.getExtras().get(Intent.EXTRA_STREAM);
        }
        if (uri != null) {
            handleUri(uri, i.getType());
            // Avoid looping
            activity.setIntent(new Intent());
        }
    }

    private void handleUri(Uri uri, @Nullable String mimeType) {
        if (mimeType != null && Utils.matchFeedMimeType(mimeType)
                || uri.getPath() != null && Utils.matchFeedFilePath(uri.getPath())) {
            var action = FeedNavDirections.actionAddFeedDialog(uri);
            navController.navigate(action);
        } else {
            var action = NavBarFragmentDirections.actionAddTorrent(uri);
            activity.getRootNavController().navigate(action);
        }
    }

    private void animateHeaderTransition() {
        var duration = MotionUtils.resolveThemeDuration(
                requireContext(),
                R.attr.motionDurationShort3,
                400
        );
        var fade = new MaterialFade();
        fade.setDuration(duration);
        TransitionManager.beginDelayedTransition(navRail, fade);
    }

    @Override
    public void onStart() {
        super.onStart();

        subscribeUnreadFeedsBadge();
    }

    private void subscribeUnreadFeedsBadge() {
        disposables.add(viewModel.observeUnreadFeedsCount()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((count) -> setBadge(R.id.feed_nav, count),
                        (Throwable t) -> Log.e(TAG, "Getting unread feed list error: " +
                                Log.getStackTraceString(t)))
        );
    }

    private void setBadge(@IdRes int menuId, int count) {
        var navBar = getNavigationBarView();
        if (navBar == null || navBar.getMenu().findItem(menuId) == null) {
            return;
        }
        var badge = navBar.getOrCreateBadge(menuId);
        if (count == 0) {
            badge.setVisible(false);
            badge.clearNumber();
        } else {
            badge.setVisible(true);
            badge.setNumber(count);
        }
    }

    @Override
    public void onStop() {
        super.onStop();

        disposables.clear();
    }
}

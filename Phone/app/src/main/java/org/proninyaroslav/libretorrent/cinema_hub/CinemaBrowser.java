package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.function.Consumer;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;
import org.mozilla.geckoview.WebResponse;

public final class CinemaBrowser {
    private static final String UBO_ID = "uBlock0@raymondhill.net";
    private static final String[] NAMES = {
            "YTS", "Torrenting", "UIndex", "Knaben", "SubSource", "TMDB",
            "Cinemana", "Share Shabakaty"
    };
    private static final String[] BOOKMARKS = {
            "https://en.yts.lu/", "https://www.torrenting.com/featured", "https://uindex.org/",
            "https://knaben.info/", "https://subsource.net", "https://www.themoviedb.org/",
            "https://cinemana.shabakaty.com/home", "https://share.shabakaty.com/index"
    };
    private static final String TORRENT_SEARCH = "https://knaben.org/search/";
    private static GeckoRuntime runtime;
    private static WebExtension blocker;
    private static boolean extensionLoading;
    private static String extensionStatus = "uBlock Origin is starting…";
    private final java.util.Set<GeckoSession> extensionTabs =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final java.util.Map<String, WebExtension.Action> extensionActions =
            new java.util.HashMap<>();
    private final java.util.Map<String, WebExtension> managedExtensions =
            new java.util.HashMap<>();

    final Context context;
    private FrameLayout host;
    private View upstream;
    private Consumer<Uri> addTorrent;
    final SharedPreferences settings;
    MaterialButton videoList;
    private Runnable openFeeds = () -> {};
    private final BrowserDownloadManager downloadManager;
    private final Runnable downloadListener = this::refreshDownloadsState;
    com.google.android.material.bottomsheet.BottomSheetDialog downloadsDialog;
    private LinearLayout downloadsContainer;
    private String renderedSitesSignature = "";
    private String renderedRecentSignature = "";
    MaterialButton tabsButton;

    private MaterialButton more;
    private MaterialButton back;
    private MaterialButton forward;
    private android.widget.ProgressBar progress;
    private LinearLayout recent;
    private LinearLayout siteGrid;
    private LinearLayout navigationBar;
    private static final java.util.Set<java.lang.ref.WeakReference<CinemaBrowser>> ACTIVE_INSTANCES =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (sp, key) -> {
                if ("sites".equals(key) || "history".equals(key)) {
                    new Handler(Looper.getMainLooper()).post(this::refreshBookmarksUi);
                }
            };

    private record Site(String name, String url) {}
    private final java.util.Set<GeckoSession> privateTabs = new java.util.HashSet<>();
    private final java.util.Map<GeckoSession, String> tabStates = new java.util.WeakHashMap<>();
    private final java.util.Map<GeckoSession, GeckoSession> openerTabs = new java.util.WeakHashMap<>();
    private java.util.Set<String> malwareHosts = java.util.Set.of();
    final java.util.Map<GeckoSession, String> locations = new java.util.HashMap<>();
    private final java.util.Map<GeckoSession, Integer> navigation = new java.util.HashMap<>();
    final ArrayList<GeckoSession> tabs = new ArrayList<>();
    final ArrayList<String> titles = new ArrayList<>();
    private LinearLayout page;
    private GeckoView browser;
    private View bookmarkPage;
    GeckoSession homeTab;
    private EditText address;
    private android.widget.TextView bookmarkStar;
    private Runnable pickExtension;
    private BrowserExtensions extensions;
    GeckoSession active;
    private WebExtension.Action action;
    private AlertDialog blockerPopup;
    private boolean showing;
    private int tabIndex;
    private final CinemaBrowserSettingsSheet settingsSheet = new CinemaBrowserSettingsSheet(this);
    private final CinemaBrowserMediaBridge mediaBridge = new CinemaBrowserMediaBridge(this);

    public CinemaBrowser(FrameLayout host, View upstream, Consumer<Uri> addTorrent, Runnable pickExtension) {
        this.context = host.getContext();
        this.host = host;
        this.upstream = upstream;
        this.addTorrent = addTorrent;
        this.pickExtension = pickExtension;
        settings = context.getSharedPreferences("cinema_browser", Context.MODE_PRIVATE);
        downloadManager = BrowserDownloadManager.get(this.context);
        settings.registerOnSharedPreferenceChangeListener(prefsListener);
        ACTIVE_INSTANCES.add(new java.lang.ref.WeakReference<>(this));
    }

    public static void notifyBookmarksUpdated() {
        new Handler(Looper.getMainLooper()).post(() -> {
            synchronized (ACTIVE_INSTANCES) {
                var it = ACTIVE_INSTANCES.iterator();
                while (it.hasNext()) {
                    var ref = it.next();
                    var inst = ref.get();
                    if (inst == null) {
                        it.remove();
                    } else {
                        inst.refreshBookmarksUi();
                    }
                }
            }
        });
    }

    private void refreshBookmarksUi() {
        if (page == null) return;
        renderSites(true);
        renderRecent(true);
        updateBookmarkStar();
    }

    public void setOpenFeedsAction(Runnable action) { openFeeds = action; }

    public void detachHost() {
        openFeeds = () -> {};
        hide();
        if (page != null && page.getParent() instanceof android.view.ViewGroup parent) {
            parent.removeView(page);
        }
    }

    public void reattachHost(FrameLayout newHost, View newUpstream,
                             Consumer<Uri> newAddTorrent, Runnable newPickExtension) {
        if (newHost == null || newUpstream == null) return;
        this.host = newHost;
        this.upstream = newUpstream;
        this.addTorrent = newAddTorrent;
        this.pickExtension = newPickExtension;
        this.extensions = null;
        if (page != null) {
            if (page.getParent() instanceof android.view.ViewGroup parent && parent != newHost) {
                parent.removeView(page);
            }
            if (page.getParent() == null) {
                newHost.addView(page, new FrameLayout.LayoutParams(-1, -1));
            }
            page.setVisibility(showing ? View.VISIBLE : View.GONE);
            newUpstream.setVisibility(showing ? View.INVISIBLE : View.VISIBLE);
            androidx.core.view.ViewCompat.requestApplyInsets(page);
        }
    }

    public void home() {
        if (!showing) return;
        if (homeTab == null) homeTab = newBookmarkedTab();
        activate(tabs.indexOf(homeTab));
    }

    public boolean goBack() {
        if (active == null) return false;
        if ((navigation.getOrDefault(active, 0) & 1) != 0) {
            active.goBack();
            return true;
        }
        if (active == homeTab) return false;
        var opener = openerTabs.get(active);
        if (opener != null && tabs.contains(opener) && opener != active) {
            var closing = active;
            activate(tabs.indexOf(opener));
            disposeTab(closing);
            mediaBridge.updateDiscovery();
            saveTabs();
            return true;
        }
        home();
        return true;
    }

    private MaterialButton topControl(LinearLayout row, int iconRes, String description, Runnable click) {
        var control = button("", click);
        stylePillIconButton(control, iconRes, false);
        control.setContentDescription(description);
        var lp = new LinearLayout.LayoutParams(-2, dp(42));
        lp.setMarginStart(dp(6));
        row.addView(control, lp);
        return control;
    }

    void stylePillIconButton(MaterialButton btn, int iconRes, boolean activeGold) {
        btn.setMinWidth(dp(42));
        btn.setMinimumWidth(dp(42));
        btn.setMinHeight(dp(42));
        btn.setMinimumHeight(dp(42));
        btn.setCornerRadius(dp(14));
        btn.setStrokeWidth(dp(1));
        btn.setStrokeColor(android.content.res.ColorStateList.valueOf(activeGold ? 0xffe5b84b : 0x33ffffff));
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(activeGold ? 0xffe5b84b : 0xff1d1e22));
        int fg = activeGold ? 0xff141517 : 0xfff4f4f6;
        btn.setTextColor(fg);
        btn.setTextSize(13f);
        if (iconRes != 0) {
            btn.setIconResource(iconRes);
            btn.setIconTint(android.content.res.ColorStateList.valueOf(fg));
            btn.setIconSize(dp(18));
            btn.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            boolean hasText = btn.getText() != null && btn.getText().length() > 0;
            btn.setIconPadding(hasText ? dp(4) : 0);
            btn.setPadding(hasText ? dp(10) : 0, 0, hasText ? dp(10) : 0, 0);
        } else {
            btn.setPadding(dp(8), 0, dp(8), 0);
        }
    }

    private void showBrowserMenu() {
        new AlertDialog.Builder(context).setTitle(context.getString(org.proninyaroslav.libretorrent.R.string.cinema_browser))
                .setItems(new String[]{context.getString(org.proninyaroslav.libretorrent.R.string.cinema_bookmarks), context.getString(org.proninyaroslav.libretorrent.R.string.cinema_history), "Protection", context.getString(org.proninyaroslav.libretorrent.R.string.cinema_reload)}, (dialog, index) -> {
                    switch (index) {
                        case 0 -> home();
                        case 1 -> showHistory();
                        case 2 -> showSettings();
                        case 3 -> active.reload();
                    }
                }).show();
    }

    GeckoRuntime runtime() {
        return runtime;
    }

    private void showVideos() {
        mediaBridge.showVideos();
    }

    private void showMore() {
        var menu = new androidx.appcompat.widget.PopupMenu(context, more);
        int activeDl = downloadManager.activeCount();
        String dlLabel = "⬇ " + l("التحميلات", "Downloads") + (activeDl > 0 ? " (" + activeDl + ")" : "");
        menuItem(menu, dlLabel, this::showDownloadsSheet);
        menuItem(menu, "⟳ " + l("إعادة فحص الفيديو", "Rescan for video"), () -> {
            if (active == homeTab) {
                CinemaSnackBanner.show(context, l("افتح صفحة تحتوي على فيديو أولاً", "Open a video page first"));
                return;
            }
            mediaBridge.rescanActiveSession();
            CinemaSnackBanner.show(context, l("جاري إعادة فحص الصفحة والروابط...", "Rescanning page & frames for video..."));
        });
        menuItem(menu, context.getString(org.proninyaroslav.libretorrent.R.string.cinema_reload), () -> active.reload());
        menuItem(menu, context.getString(org.proninyaroslav.libretorrent.R.string.cinema_bookmarks), this::home);
        menuItem(menu, context.getString(org.proninyaroslav.libretorrent.R.string.cinema_history), this::showHistory);
        menuItem(menu, context.getString(org.proninyaroslav.libretorrent.R.string.cinema_share_page), this::sharePage);
        menu.getMenu().add(context.getString(org.proninyaroslav.libretorrent.R.string.cinema_desktop_mode)).setCheckable(true)
                .setChecked(settings.getBoolean("desktop", false))
                .setOnMenuItemClickListener(item -> {
                    setDesktopMode(!item.isChecked());
                    return true;
                });
        menu.getMenu().add(context.getString(org.proninyaroslav.libretorrent.R.string.cinema_search_all_torrents)).setOnMenuItemClickListener(item -> {
            var query = new EditText(context);
            query.setSingleLine(true);
            query.setHint(org.proninyaroslav.libretorrent.R.string.cinema_browser_search_knaben);
            new AlertDialog.Builder(context).setTitle(context.getString(org.proninyaroslav.libretorrent.R.string.cinema_search_all_torrents)).setView(query)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(org.proninyaroslav.libretorrent.R.string.cinema_action_search, (dialog, which) -> {
                        String term = query.getText().toString().trim();
                        if (!term.isEmpty()) openPage(TORRENT_SEARCH + Uri.encode(term));
                    }).show();
            return true;
        });
        menuItem(menu, context.getString(org.proninyaroslav.libretorrent.R.string.cinema_extensions), this::showExtensions);
        menuItem(menu, context.getString(org.proninyaroslav.libretorrent.R.string.cinema_browser_settings), this::showSettings);
        menu.show();
    }

    private void menuItem(androidx.appcompat.widget.PopupMenu menu, String label, Runnable action) {
        menu.getMenu().add(label).setOnMenuItemClickListener(item -> {
            action.run();
            return true;
        });
    }

    public boolean isShowing() { return showing; }

    public static void clearBrowserCacheOnly() {
        if (runtime != null) {
            try {
                runtime.getStorageController().clearData(StorageController.ClearFlags.ALL_CACHES);
            } catch (Throwable ignored) {}
        }
    }

    public void onHostPaused() {
        if (newWindowPrompt != null) newWindowPrompt.dismiss();
        if (active != null) {
            mediaBridge.pauseIfBackgroundNotAllowed(active);
        }
        deactivateAllTabSessions();
    }

    public void onHostResumed() {
        if (showing && active != null && active != homeTab) {
            resumeBrowserRendering();
        }
    }

    private void deactivateAllTabSessions() {
        for (var tab : tabs) {
            if (tab != null) {
                try { tab.setActive(false); } catch (Throwable ignored) {}
            }
        }
    }

    public void hide() {
        if (!showing) return;
        showing = false;
        if (newWindowPrompt != null) newWindowPrompt.dismiss();
        mediaBridge.pauseIfBackgroundNotAllowed(active);
        upstream.setVisibility(View.VISIBLE);
        page.setVisibility(View.GONE);
        if (runtime != null && active != null) {
            runtime.getWebExtensionController().setTabActive(active, false);
        }
        deactivateAllTabSessions();
        if (browser != null) browser.releaseSession();
    }

    private void resumeBrowserRendering() {
        GeckoView view = browser;
        if (view == null) return;
        view.postOnAnimation(() -> {
            if (browser != view || !view.isAttachedToWindow() || !view.isShown()
                    || active == null || view.getSession() != active
                    || (bookmarkPage != null && bookmarkPage.isShown())) return;
            active.setActive(true);
            view.invalidate();
        });
    }

    public void dispose() {
        downloadManager.removeListener(downloadListener);
        if (downloadsDialog != null && downloadsDialog.isShowing()) downloadsDialog.dismiss();
        if (blockerPopup != null) blockerPopup.dismiss();
        for (var ext : managedExtensions.values()) {
            ext.setActionDelegate(null);
            ext.setTabDelegate(null);
        }
        managedExtensions.clear();
        extensionActions.clear();
        if (blocker != null) {
            blocker.setActionDelegate(null);
            blocker.setTabDelegate(null);
        }
        if (browser != null) browser.releaseSession();
        for (var tab : tabs) tab.close();
        tabs.clear();
        privateTabs.clear();
        tabStates.clear();
        locations.clear();
        navigation.clear();
        mediaBridge.clearAllOnDispose();
        renderedSitesSignature = "";
        renderedRecentSignature = "";
    }

    public void open() {
        if (page == null) {
            init();
        } else if (page.getParent() == null && host != null) {
            host.addView(page, new FrameLayout.LayoutParams(-1, -1));
        }
        applyRuntimeRenderingSettings();
        showing = true;
        downloadManager.addListener(downloadListener);
        refreshDownloadsState();
        renderSites(false);
        renderRecent(false);
        upstream.setVisibility(View.INVISIBLE);
        page.setVisibility(View.VISIBLE);
        if (active != null && tabs.contains(active)) {
            tabIndex = tabs.indexOf(active);
        }
        activate(tabIndex);
    }

    private void init() {
        if (runtime == null) {
            var builder = new GeckoRuntimeSettings.Builder()
                    .fontInflation(false)
                    .automaticFontSizeAdjustment(false)
                    .fontSizeFactor(1.0f)
                    .doubleTapZoomingEnabled(true)
                    .inputAutoZoomEnabled(false)
                    .loginAutofillEnabled(true)
                    .allowInsecureConnections(settings.getBoolean("https", true)
                            ? GeckoRuntimeSettings.HTTPS_ONLY : GeckoRuntimeSettings.ALLOW_ALL);
            String cfgPath = CinemaCacheManager.ensureGeckoConfigFile(context);
            if (cfgPath != null && !cfgPath.isBlank()) {
                builder.configFilePath(cfgPath);
            }
            runtime = GeckoRuntime.create(context.getApplicationContext(), builder.build());
        }
        setHttpsMode();
        applyRuntimeRenderingSettings();
        runtime.getSettings().setLoginAutofillEnabled(true);
        runtime.getSettings().getContentBlocking().setSafeBrowsing(org.mozilla.geckoview.ContentBlocking.SafeBrowsing.DEFAULT);
        try (var stream = new java.util.zip.GZIPInputStream(context.getAssets().open("zajil-malware.hosts"));
                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream))) {
            var hosts = new java.util.HashSet<String>();
            String line;
            while ((line = reader.readLine()) != null) if (!line.startsWith("#") && !line.isBlank()) hosts.add(line);
            malwareHosts = hosts;
        } catch (java.io.IOException error) {
            android.util.Log.w("CinemaBrowser", "Bundled malware list unavailable", error);
        }
        ensureBlocker();
        page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(page, (view, insets) -> {
            var bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()
                    | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, 0);
            return insets;
        });
        address = new EditText(context) {
            @Override public int getAutofillType() {
                return View.AUTOFILL_TYPE_NONE;
            }

            @Override public void autofill(android.view.autofill.AutofillValue value) {
                // Credentials belong to GeckoView's virtual web form fields.
            }
        };
        address.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        address.setTextDirection(View.TEXT_DIRECTION_LTR);
        address.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        address.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        address.setBackground(null);
        address.setPadding(dp(14), 0, dp(8), 0);
        address.setTextSize(14f);
        address.setSingleLine();
        address.setHint(context.getString(org.proninyaroslav.libretorrent.R.string.cinema_type_an_address_or_search));
        address.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setOnEditorActionListener((view, id, event) -> {
            if (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == android.view.KeyEvent.ACTION_UP) submit();
                return true;
            }
            if (id != EditorInfo.IME_ACTION_SEARCH) return false;
            submit();
            return true;
        });
        var search = new LinearLayout(context);
        search.setGravity(android.view.Gravity.CENTER_VERTICAL);
        var addressBar = new LinearLayout(context);
        addressBar.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        addressBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        addressBar.setBackground(surface(0xff252529, 24));
        addressBar.addView(address, new LinearLayout.LayoutParams(0, -1, 1));
        bookmarkStar = new android.widget.TextView(context);
        bookmarkStar.setGravity(android.view.Gravity.CENTER);
        bookmarkStar.setTextSize(24);
        var highlight = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, highlight, true))
            bookmarkStar.setBackgroundResource(highlight.resourceId);
        bookmarkStar.setVisibility(View.GONE);
        bookmarkStar.setOnClickListener(view -> toggleBookmark());
        var starSize = new LinearLayout.LayoutParams(dp(36), dp(36));
        starSize.setMarginEnd(dp(5));
        addressBar.addView(bookmarkStar, starSize);
        search.addView(addressBar, new LinearLayout.LayoutParams(0, dp(44), 1));
        search.setPadding(dp(12), dp(4), dp(12), dp(4));
        topControl(search, org.proninyaroslav.libretorrent.R.drawable.ic_rss_feed_24px,
                context.getString(org.proninyaroslav.libretorrent.R.string.cinema_feeds), () -> openFeeds.run());
        videoList = topControl(search, org.proninyaroslav.libretorrent.R.drawable.ic_cinema_cast_play,
                "Video list", this::showVideos);
        videoList.setEnabled(false);
        videoList.setSingleLine(true);
        videoList.setIconTint(android.content.res.ColorStateList.valueOf(0xff6f727b));
        page.addView(search);
        progress = new android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.INVISIBLE);
        page.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        browser = new GeckoView(context) {
            @Override public void onAttachedToWindow() {
                super.onAttachedToWindow();
                resumeBrowserRendering();
            }

            @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
                super.onWindowFocusChanged(hasWindowFocus);
                if (hasWindowFocus) resumeBrowserRendering();
            }

            @Override protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(visibility);
                if (visibility == View.VISIBLE) resumeBrowserRendering();
            }

            @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                resumeBrowserRendering();
            }
        };
        // Keep the compositor in the Android view hierarchy when dialogs or tabs overlap the page.
        browser.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW);
        androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(browser,
                new androidx.core.view.WindowInsetsAnimationCompat.Callback(
                        androidx.core.view.WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    @Override public androidx.core.view.WindowInsetsCompat onProgress(
                            androidx.core.view.WindowInsetsCompat insets,
                            java.util.List<androidx.core.view.WindowInsetsAnimationCompat> animations) {
                        return insets;
                    }

                    @Override public void onEnd(androidx.core.view.WindowInsetsAnimationCompat animation) {
                        if ((animation.getTypeMask() & androidx.core.view.WindowInsetsCompat.Type.ime()) != 0)
                            resumeBrowserRendering();
                    }
                });
        browser.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        browser.setAutofillEnabled(true);
        browser.setFocusable(true);
        browser.setFocusableInTouchMode(true);
        if (context instanceof android.app.Activity hostActivity) {
            browser.setActivityContextDelegate(() -> hostActivity);
        }
        var content = new FrameLayout(context);
        content.addView(browser, new FrameLayout.LayoutParams(-1, -1));
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        var scroll = new android.widget.ScrollView(context);
        scroll.setBackgroundColor(0xff101113);
        var links = new LinearLayout(context);
        links.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(16 * context.getResources().getDisplayMetrics().density);
        links.setPadding(inset, inset, inset, inset);
        scroll.addView(links);
        var title = new android.widget.TextView(context);
        title.setText(context.getString(org.proninyaroslav.libretorrent.R.string.cinema_sites));
        title.setTextSize(20);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        links.addView(title);
        siteGrid = new LinearLayout(context);
        siteGrid.setOrientation(LinearLayout.VERTICAL);
        links.addView(siteGrid);
        renderSites();
        recent = new LinearLayout(context);
        recent.setOrientation(LinearLayout.VERTICAL);
        links.addView(recent);
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        bookmarkPage = scroll;
        navigationBar = new LinearLayout(context);
        navigationBar.setPadding(dp(8), dp(4), dp(8), dp(4));
        back = browserControl(navigationBar, org.proninyaroslav.libretorrent.R.drawable.ic_cinema_back,
                context.getString(org.proninyaroslav.libretorrent.R.string.cinema_back), this::goBack);
        forward = browserControl(navigationBar, org.proninyaroslav.libretorrent.R.drawable.ic_cinema_forward,
                "Forward", () -> active.goForward());
        browserControl(navigationBar, org.proninyaroslav.libretorrent.R.drawable.ic_cinema_home,
                context.getString(org.proninyaroslav.libretorrent.R.string.cinema_browser_home), this::home);
        tabsButton = browserControlText(navigationBar, "1", "Tabs", this::showTabs);
        more = browserControl(navigationBar, org.proninyaroslav.libretorrent.R.drawable.ic_cinema_more,
                context.getString(org.proninyaroslav.libretorrent.R.string.cinema_browser_settings), this::showMore);
        page.addView(navigationBar);
        host.addView(page, new FrameLayout.LayoutParams(-1, -1));
        androidx.core.view.ViewCompat.requestApplyInsets(page);
        mediaBridge.installDiscovery();
        homeTab = newBookmarkedTab();
        restoreTabs();
    }

    private GeckoSession newBookmarkedTab() {
        var tab = newTab(context.getString(org.proninyaroslav.libretorrent.R.string.cinema_bookmarks), null);
        tab.open(runtime);
        return tab;
    }

    public void openUrl(String url) {
        if (url != null && !url.isBlank()) {
            openPage(url);
        }
    }

    private void openPage(String url) {
        boolean desktopOn = isDesktopMode();
        String target = desktopOn ? desktopifyUrl(url) : url;
        if (active == homeTab) {
            newTab(Uri.parse(target).getHost(), target);
            activate(tabs.size() - 1);
        } else {
            applyDesktopSettingsToTab(active, desktopOn);
            if (desktopOn && !target.equals(url)) {
                active.load(new GeckoSession.Loader().uri(target).flags(GeckoSession.LOAD_FLAGS_BYPASS_CACHE));
            } else {
                active.loadUri(target);
            }
        }
    }

    private void submit() {
        String query = address.getText().toString().trim();
        if (query.isEmpty()) return;
        context.getSystemService(android.view.inputmethod.InputMethodManager.class)
                .hideSoftInputFromWindow(address.getWindowToken(), 0);
        page.setFocusableInTouchMode(true);
        page.requestFocus();
        if (query.startsWith("https://") || query.startsWith("http://") || query.startsWith("magnet:?")) {
            if (!routeTorrent(query, false)) openPage(query);
            return;
        }
        if (query.matches("[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+(?::[0-9]+)?(?:/[^ ]*)?")) {
            openPage("https://" + query);
        } else {
            openPage("https://www.google.com/search?q=" + Uri.encode(query));
        }
    }

    private GeckoSession newTab(String title, String uri) {
        return newTab(title, uri, false);
    }

    private GeckoSession newTab(String title, String uri, boolean privateMode) {
        boolean desktopOn = isDesktopMode();
        var tab = new GeckoSession(new GeckoSessionSettings.Builder()
                .userAgentMode(desktopMode())
                .userAgentOverride(desktopOn ? DESKTOP_USER_AGENT : null)
                .viewportMode(viewportMode())
                .displayMode(GeckoSessionSettings.DISPLAY_MODE_BROWSER)
                .usePrivateMode(privateMode).useTrackingProtection(true).build());
        mediaBridge.attachMediaSessionDelegate(tab);
        tab.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public void onLocationChange(GeckoSession session, String url,
                    java.util.List<GeckoSession.PermissionDelegate.ContentPermission> permissions, Boolean gesture) {
                if (url == null || !tabs.contains(session)) return;
                if (session == homeTab || "about:blank".equals(url)) return;
                if (!isWebPage(url) && locations.containsKey(session)) return;
                String previous = locations.put(session, url);
                if (!url.equals(previous)) {
                    mediaBridge.clearSessionDiscoveryState(session);
                }
                if (session == active) mediaBridge.updateDiscovery();
                if (session == active) {
                    if (!address.hasFocus()) address.setText(url);
                    updateBookmarkStar();
                }
            }

            @Override public void onCanGoBack(GeckoSession session, boolean allowed) {
                updateNavigation(session, 1, allowed);
            }

            @Override public void onCanGoForward(GeckoSession session, boolean allowed) {
                updateNavigation(session, 2, allowed);
            }

            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session, LoadRequest request) {
                if ("moz-extension".equals(Uri.parse(request.uri).getScheme())
                        && extensionTabs.contains(session)) {
                    return GeckoResult.allow();
                }
                if (routeTorrent(request.uri, false)) return GeckoResult.deny();
                Uri destination = Uri.parse(request.uri);
                String scheme = destination.getScheme();
                if ("stremio".equalsIgnoreCase(scheme)) {
                    if (session == active && request.hasUserGesture && !request.isRedirect) {
                        context.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, destination,
                                context, TvHubActivity.class));
                    }
                    return GeckoResult.deny();
                }
                String domain = destination.getHost();
                if (settings.getBoolean("malware", true) && blockedHost(domain)) {
                    if (session == active) new AlertDialog.Builder(context).setTitle(context.getString(org.proninyaroslav.libretorrent.R.string.cinema_blocked_unsafe_site))
                            .setMessage(domain).setPositiveButton(android.R.string.ok, null).show();
                    return GeckoResult.deny();
                }
                if ("data".equals(scheme) && request.uri.startsWith("data:text/html")) return GeckoResult.allow();
                if (!"http".equals(scheme) && !"https".equals(scheme)) return GeckoResult.deny();
                if (request.target == TARGET_WINDOW_NEW && settings.getBoolean("popups", true)) {
                    if (session == active && showing && destination.getHost() != null
                            && destination.getUserInfo() == null) {
                        return offerNewWindow(session, request.uri, destination.getHost());
                    }
                    return GeckoResult.deny();
                }
                if (settings.getBoolean("redirects", true) && request.isRedirect && !request.hasUserGesture) {
                    Uri previous = request.triggerUri == null ? null : Uri.parse(request.triggerUri);
                    if (previous != null && !sameSite(previous, destination)) return GeckoResult.deny();
                }
                if (!request.isRedirect && session == active && BrowserDownloadManager.isDirectVideoUrl(request.uri, null)) {
                    int idx = tabs.indexOf(session);
                    String hint = idx >= 0 ? titles.get(idx) : "";
                    new Handler(Looper.getMainLooper()).post(() -> promptDirectVideoDownload(request.uri, null, -1L, hint));
                    return GeckoResult.deny();
                }
                return GeckoResult.allow();
            }

            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) {
                boolean approved = approvedWindowSource == session && uri.equals(approvedWindowUrl);
                if (approved) {
                    approvedWindowSource = null;
                    approvedWindowUrl = null;
                }
                if (!approved && settings.getBoolean("popups", true)) return GeckoResult.fromValue(null);
                var next = newTab(context.getString(org.proninyaroslav.libretorrent.R.string.cinema_new_tab), null, privateTabs.contains(session));
                openerTabs.put(next, session);
                new Handler(Looper.getMainLooper()).post(() -> activate(tabs.indexOf(next)));
                return GeckoResult.fromValue(next);
            }

        });
        tab.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession session, String url) {
                if (url == null || !tabs.contains(session) || session == homeTab || "about:blank".equals(url)) return;
                mediaBridge.clearSessionDiscoveryState(session);
                if (session == active) {
                    mediaBridge.updateDiscovery();
                    mediaBridge.refreshVideoSheetIfShowing(session);
                }
            }

            @Override public void onProgressChange(GeckoSession session, int value) {
                if (session == active) {
                    progress.setProgress(value);
                    progress.setVisibility(value < 100 ? View.VISIBLE : View.INVISIBLE);
                }
            }

            @Override public void onPageStop(GeckoSession session, boolean success) {
                if (success && tabs.contains(session)) {
                    if (session != homeTab) {
                        mediaBridge.sendZoomToSession(session, getBrowserZoomPercent());
                    }
                    if (!privateTabs.contains(session) && locations.containsKey(session)) {
                        rememberVisit(locations.get(session));
                    }
                }
            }

            @Override public void onSessionStateChange(GeckoSession session, GeckoSession.SessionState state) {
                if (privateTabs.contains(session) || session == homeTab || !tabs.contains(session)) return;
                tabStates.put(session, state.toString());
                saveTabs();
            }
        });
        tab.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onTitleChange(GeckoSession session, String title) {
                int index = tabs.indexOf(session);
                if (index >= 0 && session != homeTab && title != null && !title.isBlank()) {
                    titles.set(index, title);
                    if (!privateTabs.contains(session)) {
                        String url = locations.get(session);
                        if (url != null && history().contains(url)) updateVisitDetails(url, title, false);
                    }
                    mediaBridge.onTitleChanged(session, title);
                }
            }
            @Override public void onExternalResponse(GeckoSession session, WebResponse response) {
                var type = response.headers.get("content-type");
                var disp = response.headers.get("content-disposition");
                long len = -1L;
                try {
                    String rawLen = response.headers.get("content-length");
                    if (rawLen != null) len = Long.parseLong(rawLen.trim());
                } catch (Exception ignored) {}
                if (routeTorrent(response.uri, type != null && type.startsWith("application/x-bittorrent"))) {
                    if (response.body != null) {
                        try { response.body.close(); } catch (java.io.IOException ignored) {}
                    }
                    return;
                }
                boolean directVideo = BrowserDownloadManager.isDirectVideoUrl(response.uri, type)
                        || (disp != null && disp.toLowerCase(java.util.Locale.ROOT).contains("attachment")
                        && type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("video/"));
                if (directVideo) {
                    if (response.body != null) {
                        try { response.body.close(); } catch (java.io.IOException ignored) {}
                    }
                    int idx = tabs.indexOf(session);
                    String hint = idx >= 0 ? titles.get(idx) : "";
                    long finalLen = len;
                    new Handler(Looper.getMainLooper()).post(() -> promptDirectVideoDownload(response.uri, disp, finalLen, hint));
                }
            }
        });
        tabs.add(tab);
        if (privateMode) privateTabs.add(tab);
        mediaBridge.registerDiscovery(tab);
        titles.add(title);
        if (uri != null) {
            tab.open(runtime);
            tab.loadUri(uri);
        }
        return tab;
    }

    private GeckoSession makeBackgroundTab(String title) {
        return newTab(title, null);
    }

    private static boolean sameSite(Uri from, Uri to) {
        String left = from.getHost();
        String right = to.getHost();
        if (left == null || right == null) return false;
        return left.replaceFirst("^www\\.", "").equalsIgnoreCase(right.replaceFirst("^www\\.", ""));
    }

    private boolean routeTorrent(String value, boolean torrentMimeType) {
        if (value == null) return false;
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        String path = uri.getPath();
        boolean website = "https".equals(scheme) || "http".equals(scheme);
        if (value.startsWith("magnet:?") || website && (torrentMimeType
                || path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".torrent"))) {
            if ("http".equals(scheme) && settings.getBoolean("https", true)) {
                uri = uri.buildUpon().scheme("https").build();
            }
            if (showing) {
                Uri torrent = uri;
                new Handler(Looper.getMainLooper()).post(() -> addTorrent.accept(torrent));
            }
            return true;
        }
        return false;
    }

    private void activate(int index) {
        if (!showing || index < 0 || index >= tabs.size()) return;
        GeckoSession nextTab = tabs.get(index);
        if (active != null) {
            if (active != nextTab) {
                mediaBridge.pauseIfBackgroundNotAllowed(active);
                try { active.setActive(false); } catch (Throwable ignored) {}
            }
            runtime.getWebExtensionController().setTabActive(active, false);
        }
        for (var tab : tabs) {
            if (tab != null && tab != nextTab) {
                try { tab.setActive(false); } catch (Throwable ignored) {}
            }
        }
        browser.releaseSession();
        tabIndex = index;
        active = nextTab;
        applyDesktopSettingsToTab(nextTab, isDesktopMode());
        browser.setSession(active);
        boolean home = active == homeTab;
        try { active.setActive(!home); } catch (Throwable ignored) {}
        bookmarkPage.setVisibility(home ? View.VISIBLE : View.GONE);
        if (home) {
            page.setFocusableInTouchMode(true);
            page.requestFocus();
        } else {
            browser.requestFocus();
            mediaBridge.sendZoomToSession(active, getBrowserZoomPercent());
        }
        address.setText(locations.getOrDefault(active, ""));
        updateBookmarkStar();
        progress.setVisibility(View.INVISIBLE);
        updateNavigation(active, 0, false);
        mediaBridge.updateDiscovery();
        if (active == homeTab) {
            renderSites(false);
            renderRecent(false);
        }
        runtime.getWebExtensionController().setTabActive(active, true);
        action = null;
        attachAllExtensionDelegates();
        saveTabs();
    }

    private void saveTabs() {
        var saved = new org.json.JSONArray();
        int selected = 0;
        for (int i = 0; i < tabs.size(); i++) {
            var tab = tabs.get(i);
            if (tab == homeTab || privateTabs.contains(tab)) continue;
            String url = locations.get(tab);
            if (url != null && !isWebPage(url)) url = null;
            try {
                saved.put(new org.json.JSONObject().put("title", titles.get(i))
                        .put("url", url).put("state", tabStates.get(tab)));
                if (tab == active) selected = saved.length();
            } catch (org.json.JSONException ignored) {}
        }
        settings.edit().putString("open_tabs", saved.toString()).putInt("selected_tab", selected).apply();
    }

    private void restoreTabs() {
        try {
            var saved = new org.json.JSONArray(settings.getString("open_tabs", "[]"));
            for (int i = 0; i < Math.min(saved.length(), 30); i++) {
                var entry = saved.getJSONObject(i);
                String url = entry.optString("url", "");
                var tab = newTab(entry.optString("title", context.getString(org.proninyaroslav.libretorrent.R.string.cinema_new_tab)), null);
                tab.open(runtime);
                if (!url.isEmpty()) locations.put(tab, url);
                String state = entry.optString("state", "");
                if (!state.isEmpty()) {
                    var restored = GeckoSession.SessionState.fromString(state);
                    if (restored != null) {
                        tabStates.put(tab, state);
                        tab.restoreState(restored);
                        applyDesktopSettingsToTab(tab, isDesktopMode());
                        continue;
                    }
                }
                applyDesktopSettingsToTab(tab, isDesktopMode());
                if (!url.isEmpty()) tab.loadUri(url);
            }
            tabIndex = Math.min(settings.getInt("selected_tab", 0), tabs.size() - 1);
        } catch (org.json.JSONException ignored) {}
    }

    private ArrayList<Site> sites() {
        var items = new ArrayList<Site>();
        String saved = settings.getString("sites", null);
        if (saved != null) {
            try {
                var entries = new org.json.JSONArray(saved);
                for (int i = 0; i < entries.length(); i++) {
                    var entry = entries.getJSONObject(i);
                    items.add(new Site(entry.getString("name"), entry.getString("url")));
                }
                if (!settings.getBoolean("default_sites_v2", false)) {
                    boolean changed = false;
                    for (int i = 0; i < NAMES.length; i++) {
                        String key = bookmarkKey(BOOKMARKS[i]);
                        boolean exists = key != null && items.stream().anyMatch(s -> key.equals(bookmarkKey(s.url())));
                        if (!exists) {
                            items.add(new Site(NAMES[i], BOOKMARKS[i]));
                            changed = true;
                        }
                    }
                    settings.edit().putBoolean("default_sites_v2", true).apply();
                    if (changed) {
                        var merged = new org.json.JSONArray();
                        for (Site site : items) {
                            merged.put(new org.json.JSONObject(java.util.Map.of("name", site.name(), "url", site.url())));
                        }
                        settings.edit().putString("sites", merged.toString()).apply();
                    }
                }
                return items;
            } catch (org.json.JSONException error) {
                items.clear();
            }
        }
        for (int i = 0; i < NAMES.length; i++) items.add(new Site(NAMES[i], BOOKMARKS[i]));
        settings.edit().putBoolean("default_sites_v2", true).apply();
        return items;
    }

    private void saveSites(java.util.List<Site> items) {
        var entries = new org.json.JSONArray();
        for (Site site : items) {
            entries.put(new org.json.JSONObject(java.util.Map.of("name", site.name(), "url", site.url())));
        }
        settings.edit().putString("sites", entries.toString()).apply();
        renderSites();
        updateBookmarkStar();
    }

    private static String bookmarkKey(String url) {
        if (url == null || !isWebPage(url)) return null;
        var page = Uri.parse(url);
        String host = page.getHost().toLowerCase(java.util.Locale.ROOT).replaceFirst("^www\\.", "");
        int port = page.getPort();
        return port < 0 || ("https".equalsIgnoreCase(page.getScheme()) ? port == 443 : port == 80)
                ? host : host + ":" + port;
    }

    private void updateBookmarkStar() {
        String key = active == homeTab ? null : bookmarkKey(locations.get(active));
        bookmarkStar.setVisibility(key == null ? View.GONE : View.VISIBLE);
        if (key == null) return;
        boolean saved = sites().stream().anyMatch(site -> key.equals(bookmarkKey(site.url())));
        bookmarkStar.setText(saved ? "★" : "☆");
        bookmarkStar.setTextColor(saved ? 0xffe5b84b : 0xffa1a1aa);
        bookmarkStar.setContentDescription(saved ? "Remove from Bookmarks" : "Save to Bookmarks");
    }

    private void toggleBookmark() {
        String url = locations.get(active);
        String key = bookmarkKey(url);
        if (active == homeTab || key == null) return;
        var items = sites();
        String removedUrl = null;
        for (Site s : items) {
            if (key.equals(bookmarkKey(s.url()))) {
                removedUrl = s.url();
                break;
            }
        }
        boolean removed = items.removeIf(site -> key.equals(bookmarkKey(site.url())));
        if (!removed) {
            var page = Uri.parse(url);
            String host = page.getHost();
            int index = tabs.indexOf(active);
            String title = index >= 0 ? CinemaBrowserMediaBridge.cleanVideoTitle(titles.get(index)) : "";
            if (title.isBlank() || title.equals(context.getString(org.proninyaroslav.libretorrent.R.string.cinema_new_tab)) || title.equals("Private tab")) title = host;
            items.add(new Site(title, page.buildUpon().fragment(null).build().toString()));
        } else if (removedUrl != null) {
            SiteFaviconManager.deleteIfUnused(context, removedUrl, remainingKnownSiteUrls(items));
        }
        saveSites(items);
        bookmarkStar.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        CinemaSnackBanner.show(context, removed
                ? context.getString(org.proninyaroslav.libretorrent.R.string.cinema_removed_from_bookmarks)
                : context.getString(org.proninyaroslav.libretorrent.R.string.cinema_saved_to_bookmarks));
    }

    private java.util.List<String> remainingKnownSiteUrls(java.util.List<Site> currentSites) {
        var urls = new java.util.ArrayList<String>();
        for (Site s : currentSites) urls.add(s.url());
        urls.addAll(history());
        return urls;
    }

    private void bindFavicon(String url, android.widget.ImageView iconView, View fallbackView) {
        android.graphics.Bitmap immediate = SiteFaviconManager.getImmediate(url);
        if (immediate != null) {
            iconView.setImageBitmap(immediate);
            fallbackView.setVisibility(View.INVISIBLE);
            return;
        }
        SiteFaviconManager.load(context, url, bitmap -> {
            if (bitmap != null) {
                iconView.setImageBitmap(bitmap);
                fallbackView.setVisibility(View.INVISIBLE);
            }
        });
    }

    private void renderSites() {
        renderSites(true);
    }

    private void renderSites(boolean force) {
        var items = sites();
        SiteFaviconManager.preload(context, remainingKnownSiteUrls(items));
        StringBuilder sig = new StringBuilder();
        for (Site s : items) sig.append(s.name()).append('|').append(s.url()).append('\n');
        int columns = Math.max(3, Math.min(4, (context.getResources().getConfiguration().screenWidthDp - 32) / 80));
        sig.append("cols=").append(columns);
        String nextSig = sig.toString();
        if (!force && siteGrid.getChildCount() > 0 && nextSig.equals(renderedSitesSignature)) {
            return;
        }
        renderedSitesSignature = nextSig;
        siteGrid.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i <= items.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(context);
                siteGrid.addView(row);
            }
            int index = i;
            Site site = i == items.size() ? null : items.get(i);
            var tile = new LinearLayout(context);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            tile.setFocusable(true);
            var icon = new android.widget.TextView(context);
            icon.setText(site == null ? "+" : site.name().substring(0, 1).toUpperCase(java.util.Locale.ROOT));
            icon.setTextSize(24);
            icon.setTextColor(0xffdddddd);
            icon.setGravity(android.view.Gravity.CENTER);
            var iconFrame = new FrameLayout(context);
            var frame = surface(0xff1c1c1e, 16);
            frame.setStroke(dp(1), 0xff2c2c2e);
            iconFrame.setBackground(frame);
            iconFrame.addView(icon, new FrameLayout.LayoutParams(-1, -1));
            tile.addView(iconFrame, new LinearLayout.LayoutParams(dp(52), dp(52)));
            if (site != null) {
                var image = new android.widget.ImageView(context);
                image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                iconFrame.addView(image, new FrameLayout.LayoutParams(dp(34), dp(34), android.view.Gravity.CENTER));
                bindFavicon(site.url(), image, icon);
            }
            var label = new android.widget.TextView(context);
            label.setText(site == null ? "Add" : site.name());
            label.setTextSize(12);
            label.setGravity(android.view.Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tile.addView(label, new LinearLayout.LayoutParams(-1, dp(38)));
            tile.setOnClickListener(view -> {
                if (site == null) editSite(-1, "");
                else openPage(site.url());
            });
            if (site != null) tile.setOnLongClickListener(view -> {
                new AlertDialog.Builder(context).setTitle(site.name())
                        .setItems(new String[]{"Edit", "Remove"}, (dialog, choice) -> {
                            if (choice == 0) editSite(index, "");
                            else {
                                String removedUrl = items.get(index).url();
                                items.remove(index);
                                SiteFaviconManager.deleteIfUnused(context, removedUrl, remainingKnownSiteUrls(items));
                                saveSites(items);
                            }
                        }).show();
                return true;
            });
            var slot = new LinearLayout.LayoutParams(0, dp(98), 1);
            slot.topMargin = dp(8);
            row.addView(tile, slot);
        }
        int missing = (columns - (items.size() + 1) % columns) % columns;
        for (int i = 0; i < missing; i++) row.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1));
    }

    private void editSite(int index, String initialUrl) {
        var items = sites();
        Site existing = index < 0 ? null : items.get(index);
        var form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), 0, dp(20), 0);
        var name = new EditText(context);
        name.setSingleLine(true);
        name.setHint(org.proninyaroslav.libretorrent.R.string.cinema_bookmark_name);
        var url = new EditText(context);
        url.setSingleLine(true);
        url.setHint(org.proninyaroslav.libretorrent.R.string.cinema_bookmark_url);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(existing == null ? initialUrl : existing.url());
        if (existing != null) name.setText(existing.name());
        form.addView(name);
        form.addView(url);
        var dialog = new AlertDialog.Builder(context).setTitle(existing == null ? context.getString(org.proninyaroslav.libretorrent.R.string.cinema_add_site) : "Edit site")
                .setView(form).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(existing == null ? "Add" : "Save", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = url.getText().toString().trim();
            if (!value.contains("://")) value = "https://" + value;
            try {
                var parsed = new java.net.URI(value);
                if (parsed.getHost() == null || parsed.getUserInfo() != null ||
                        !("https".equalsIgnoreCase(parsed.getScheme()) || "http".equalsIgnoreCase(parsed.getScheme()))) {
                    throw new java.net.URISyntaxException(value, "Expected a website address");
                }
                String title = name.getText().toString().trim();
                var site = new Site(title.isEmpty() ? parsed.getHost() : title, parsed.toASCIIString());
                if (index < 0) items.add(site);
                else items.set(index, site);
                saveSites(items);
                dialog.dismiss();
            } catch (java.net.URISyntaxException invalid) {
                url.setError("Enter a valid website address");
            }
        });
    }

    android.graphics.drawable.GradientDrawable surface(int color, int radius) {
        var background = new android.graphics.drawable.GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private void showTabs() {
        var sheet = new BrowserTabsSheet(context, new BrowserTabsSheet.Actions() {
            @Override public void select(GeckoSession tab) { activate(tabs.indexOf(tab)); }
            @Override public void toggleAudio(GeckoSession tab) { mediaBridge.muteAndPauseTab(tab); }
            @Override public void close(GeckoSession tab) { closeTab(tab); }
            @Override public void closeOthers() { closeTabs(true); }
            @Override public void closeAll() { closeTabs(false); }
            @Override public void open(boolean privateMode) { openNewTab(privateMode); }
            @Override public java.util.List<BrowserTabsSheet.Tab> tabs() {
                var entries = new ArrayList<BrowserTabsSheet.Tab>();
                for (int index = 0; index < tabs.size(); index++) {
                    var tab = tabs.get(index);
                    String cleanTitle = mediaBridge.getSessionCleanTitle(tab);
                    String displayTitle = !cleanTitle.isBlank() ? cleanTitle : titles.get(index);
                    entries.add(new BrowserTabsSheet.Tab(tab, displayTitle, locations.get(tab),
                            tab == active, tab == homeTab, privateTabs.contains(tab), mediaBridge.isTabAudible(tab)));
                }
                return entries;
            }
        });
        sheet.show();
    }

    private AlertDialog newWindowPrompt;
    private GeckoSession approvedWindowSource;
    private String approvedWindowUrl;

    private GeckoResult<AllowOrDeny> offerNewWindow(GeckoSession source, String url, String hostName) {
        if (newWindowPrompt != null && newWindowPrompt.isShowing()) return GeckoResult.deny();
        var decision = new GeckoResult<AllowOrDeny>();
        var accepted = new java.util.concurrent.atomic.AtomicBoolean();
        newWindowPrompt = new AlertDialog.Builder(context)
                .setTitle(context.getString(org.proninyaroslav.libretorrent.R.string.cinema_open_requested_tab))
                .setMessage(hostName)
                .setNegativeButton(org.proninyaroslav.libretorrent.R.string.cinema_popup_dismiss, null)
                .setPositiveButton(org.proninyaroslav.libretorrent.R.string.cinema_popup_open_page, (dialog, which) -> {
                    if (!showing || active != source) return;
                    approvedWindowSource = source;
                    approvedWindowUrl = url;
                    accepted.set(true);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (approvedWindowSource == source && url.equals(approvedWindowUrl)) {
                            approvedWindowSource = null;
                            approvedWindowUrl = null;
                        }
                    }, 3000);
                    decision.complete(AllowOrDeny.ALLOW);
                }).create();
        newWindowPrompt.setOnDismissListener(dialog -> {
            if (newWindowPrompt == dialog) newWindowPrompt = null;
            if (!accepted.get()) decision.complete(AllowOrDeny.DENY);
        });
        newWindowPrompt.show();
        var surface = new android.graphics.drawable.GradientDrawable();
        surface.setColor(0xff171717);
        surface.setCornerRadius(dp(20));
        surface.setStroke(dp(1), 0x66e5a93b);
        if (newWindowPrompt.getWindow() != null) newWindowPrompt.getWindow().setBackgroundDrawable(surface);
        newWindowPrompt.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setTextColor(0xffe5a93b);
        newWindowPrompt.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setTextColor(0xffe6dfd3);
        return decision;
    }

    private void showSettings() {
        settingsSheet.show();
    }

    void performClearBrowsingData() {
        settings.edit().remove("history").remove("visit_details").apply();
        dispose();
        titles.clear();
        active = null;
        tabIndex = 0;
        runtime.getStorageController().clearData(StorageController.ClearFlags.ALL)
                .accept(ignored -> resetTabs("Browsing data cleared"),
                        error -> resetTabs("Could not clear browsing data"));
    }

    private void resetTabs(String text) {
        homeTab = newBookmarkedTab();
        activate(0);
        CinemaSnackBanner.show(context, text);
    }

    void setHttpsMode() {
        runtime.getSettings().setAllowInsecureConnections(settings.getBoolean("https", true)
                ? GeckoRuntimeSettings.HTTPS_ONLY : GeckoRuntimeSettings.ALLOW_ALL);
    }

    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0";

    boolean isDesktopMode() {
        return settings.getBoolean("desktop", false);
    }

    int getBrowserZoomPercent() {
        int saved = settings.getInt("browser_zoom_percent", 100);
        int clamped = Math.max(50, Math.min(150, saved));
        return Math.round(clamped / 5f) * 5;
    }

    void setBrowserZoomPercent(int percent) {
        int clamped = Math.round(Math.max(50, Math.min(150, percent)) / 5f) * 5;
        settings.edit().putInt("browser_zoom_percent", clamped).apply();
        applyRuntimeRenderingSettings();
        mediaBridge.broadcastZoom(clamped);
    }

    private void applyRuntimeRenderingSettings() {
        if (runtime == null) return;
        var rs = runtime.getSettings();
        rs.setFontInflationEnabled(false);
        rs.setAutomaticFontSizeAdjustment(false);
        rs.setFontSizeFactor(1.0f);
        rs.setDoubleTapZoomingEnabled(true);
        rs.setInputAutoZoomEnabled(false);
    }

    private void applyDesktopSettingsToTab(GeckoSession tab, boolean enabled) {
        if (tab == null) return;
        var s = tab.getSettings();
        int wantUa = enabled ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                : GeckoSessionSettings.USER_AGENT_MODE_MOBILE;
        String wantOverride = enabled ? DESKTOP_USER_AGENT : null;
        int wantVp = enabled ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                : GeckoSessionSettings.VIEWPORT_MODE_MOBILE;
        if (s.getUserAgentMode() != wantUa) s.setUserAgentMode(wantUa);
        if (!java.util.Objects.equals(s.getUserAgentOverride(), wantOverride)) {
            s.setUserAgentOverride(wantOverride);
        }
        if (s.getViewportMode() != wantVp) s.setViewportMode(wantVp);
        if (s.getDisplayMode() != GeckoSessionSettings.DISPLAY_MODE_BROWSER) {
            s.setDisplayMode(GeckoSessionSettings.DISPLAY_MODE_BROWSER);
        }
    }

    private static String desktopifyUrl(String url) {
        if (url == null || url.isBlank()) return url;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) return url;
            String lower = host.toLowerCase(java.util.Locale.ROOT);
            String nextHost = null;
            if (lower.equals("m.youtube.com")) nextHost = "www.youtube.com";
            else if (lower.equals("mobile.twitter.com") || lower.equals("mobile.x.com")) nextHost = "x.com";
            else if (lower.equals("m.facebook.com")) nextHost = "www.facebook.com";
            else if (lower.equals("m.imdb.com")) nextHost = "www.imdb.com";
            else if (lower.equals("m.twitch.tv")) nextHost = "www.twitch.tv";
            else if (lower.endsWith(".m.wikipedia.org")) nextHost = lower.replace(".m.wikipedia.org", ".wikipedia.org");
            else if (lower.startsWith("m.") && lower.indexOf('.', 2) > 2) nextHost = lower.substring(2);
            if (nextHost == null || nextHost.equals(host)) return url;
            return uri.buildUpon().encodedAuthority(uri.getPort() > 0 ? nextHost + ":" + uri.getPort() : nextHost)
                    .build().toString();
        } catch (Exception ignored) {
            return url;
        }
    }

    void setDesktopMode(boolean enabled) {
        settings.edit().putBoolean("desktop", enabled).apply();
        applyRuntimeRenderingSettings();
        for (var tab : tabs) {
            applyDesktopSettingsToTab(tab, enabled);
        }
        if (active != null && active != homeTab) {
            String cur = locations.get(active);
            String target = enabled && cur != null ? desktopifyUrl(cur) : cur;
            if (target != null && isWebPage(target)) {
                active.load(new GeckoSession.Loader()
                        .uri(target)
                        .flags(GeckoSession.LOAD_FLAGS_BYPASS_CACHE | GeckoSession.LOAD_FLAGS_REPLACE_HISTORY));
            } else {
                active.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE);
            }
        }
        CinemaSnackBanner.show(context, enabled
                ? l("وضع سطح المكتب مفعّل", "Desktop Mode ON")
                : l("وضع الهاتف مفعّل", "Mobile Mode ON"));
    }

    private int desktopMode() {
        return isDesktopMode() ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                : GeckoSessionSettings.USER_AGENT_MODE_MOBILE;
    }

    private int viewportMode() {
        return isDesktopMode() ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                : GeckoSessionSettings.VIEWPORT_MODE_MOBILE;
    }

    private void ensureBlocker() {
        var controller = runtime.getWebExtensionController();
        controller.setPromptDelegate(new WebExtensionController.PromptDelegate() {
            @Override public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
                    WebExtension extension, String[] permissions, String[] origins, String[] dataPermissions) {
                if (UBO_ID.equals(extension.id))
                    return GeckoResult.fromValue(new WebExtension.PermissionPromptResponse(true, false, false));
                return extensions().confirmInstall(extension, permissions, origins, dataPermissions);
            }
        });
        if (extensionLoading) return;
        extensionLoading = true;
        controller.list().accept(installed -> {
            WebExtension ubo = null;
            if (installed != null) {
                for (var extension : installed) {
                    if (extension == null || extension.isBuiltIn) continue;
                    managedExtensions.put(extension.id, extension);
                    attachExtensionDelegates(extension);
                    if (UBO_ID.equals(extension.id)) ubo = extension;
                }
            }
            if (ubo != null) {
                ready(ubo);
                return;
            }
            if (settings.getBoolean("ubo_user_removed", false)) {
                extensionLoading = false;
                return;
            }
            controller.install("resource://android/assets/ublock-origin.xpi").accept(this::ready, this::extensionFailed);
        }, this::extensionFailed);
    }

    private BrowserExtensions extensions() {
        if (extensions == null) {
            extensions = new BrowserExtensions(
                    context,
                    runtime.getWebExtensionController(),
                    pickExtension,
                    UBO_ID,
                    this::openExtensionSettingsOrPopup,
                    (addon, enabled) -> {
                        if (addon == null) return;
                        if (enabled) {
                            managedExtensions.put(addon.id, addon);
                            if (UBO_ID.equals(addon.id)) blocker = addon;
                            attachExtensionDelegates(addon);
                        } else {
                            extensionActions.remove(addon.id);
                            if (UBO_ID.equals(addon.id)) action = null;
                        }
                    },
                    addonId -> {
                        managedExtensions.remove(addonId);
                        extensionActions.remove(addonId);
                        if (UBO_ID.equals(addonId)) {
                            blocker = null;
                            action = null;
                            settings.edit().putBoolean("ubo_user_removed", true).apply();
                        }
                    },
                    addon -> {
                        if (addon == null) return;
                        managedExtensions.put(addon.id, addon);
                        if (UBO_ID.equals(addon.id)) {
                            settings.edit().putBoolean("ubo_user_removed", false).apply();
                            ready(addon);
                        } else {
                            attachExtensionDelegates(addon);
                        }
                    },
                    () -> {
                        settings.edit().putBoolean("ubo_user_removed", false).apply();
                        runtime.getWebExtensionController()
                                .install("resource://android/assets/ublock-origin.xpi")
                                .accept(ext -> {
                                    ready(ext);
                                    extensions().refreshIfShowing();
                                    CinemaSnackBanner.show(context, l("تم تثبيت uBlock Origin ✓", "uBlock Origin installed ✓"));
                                }, err -> {
                                    extensions().refreshIfShowing();
                                    CinemaSnackBanner.show(context, l("تعذّر تثبيت uBlock Origin", "Could not install uBlock Origin"));
                                });
                    });
        }
        return extensions;
    }

    private void showExtensions() { extensions().show(); }

    public void installExtensionFile(Uri uri) { extensions().installFile(uri); }

    private void ready(WebExtension extension) {
        if (extension == null) return;
        blocker = extension;
        managedExtensions.put(extension.id, extension);
        extensionLoading = false;
        extensionStatus = "uBlock Origin installed";
        attachExtensionDelegates(extension);
    }

    private void extensionFailed(Throwable error) {
        extensionLoading = false;
        extensionStatus = "uBlock Origin could not be installed";
        android.util.Log.w("CinemaBrowser", extensionStatus, error);
    }

    private void attachAllExtensionDelegates() {
        for (var ext : java.util.List.copyOf(managedExtensions.values())) {
            attachExtensionDelegates(ext);
        }
        if (blocker != null && !managedExtensions.containsKey(blocker.id)) {
            attachExtensionDelegates(blocker);
        }
    }

    private void attachExtensionDelegates(WebExtension ext) {
        if (ext == null) return;
        String extTitle = ext.metaData != null && ext.metaData.name != null && !ext.metaData.name.isBlank()
                ? ext.metaData.name : ext.id;
        ext.setTabDelegate(new WebExtension.TabDelegate() {
            @Override public GeckoResult<GeckoSession> onNewTab(WebExtension extension,
                    WebExtension.CreateTabDetails details) {
                var dashboard = makeBackgroundTab(extTitle);
                extensionTabs.add(dashboard);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (blockerPopup != null) blockerPopup.dismiss();
                    activate(tabs.indexOf(dashboard));
                });
                return GeckoResult.fromValue(dashboard);
            }
        });
        var delegate = new WebExtension.ActionDelegate() {
            @Override public void onBrowserAction(WebExtension extension, GeckoSession session, WebExtension.Action update) {
                if (session == null || session == active) {
                    if (update != null) {
                        extensionActions.put(ext.id, update);
                        if (UBO_ID.equals(ext.id)) action = update;
                    }
                }
            }

            @Override public GeckoResult<GeckoSession> onTogglePopup(WebExtension extension, WebExtension.Action update) {
                var popup = new GeckoSession();
                var view = new GeckoView(context);
                view.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW);
                var popupLayout = new FrameLayout(context);
                int height = Math.round(450 * context.getResources().getDisplayMetrics().density);
                popupLayout.addView(view, new FrameLayout.LayoutParams(-1, height));
                var dialog = new AlertDialog.Builder(context).setTitle(extTitle).setView(popupLayout)
                        .setPositiveButton(android.R.string.ok, null).create();
                blockerPopup = dialog;
                dialog.setOnDismissListener(window -> {
                    if (blockerPopup == dialog) blockerPopup = null;
                    view.releaseSession();
                    popup.close();
                });
                dialog.show();
                popup.open(runtime);
                view.setSession(popup);
                return GeckoResult.fromValue(popup);
            }
        };
        ext.setActionDelegate(delegate);
        if (active != null) {
            active.getWebExtensionController().setActionDelegate(ext, delegate);
        }
    }

    private void openExtensionSettingsOrPopup(WebExtension addon, boolean preferOptionsPage) {
        if (addon == null) return;
        if (addon.metaData != null && !addon.metaData.enabled) {
            CinemaSnackBanner.show(context, l("فعّل الإضافة أولاً", "Enable this extension first"));
            return;
        }
        managedExtensions.put(addon.id, addon);
        attachExtensionDelegates(addon);
        String extName = addon.metaData != null && addon.metaData.name != null && !addon.metaData.name.isBlank()
                ? addon.metaData.name : addon.id;
        String optionsUrl = (addon.metaData != null && addon.metaData.optionsPageUrl != null && !addon.metaData.optionsPageUrl.isBlank())
                ? addon.metaData.optionsPageUrl
                : (UBO_ID.equals(addon.id) && addon.metaData != null && addon.metaData.baseUrl != null
                        ? addon.metaData.baseUrl + "dashboard.html" : "");
        WebExtension.Action act = extensionActions.get(addon.id);
        if (act == null && UBO_ID.equals(addon.id)) act = action;

        if (preferOptionsPage && !optionsUrl.isBlank()) {
            openExtensionTab(extName, optionsUrl);
            return;
        }
        if (act != null && active != null && active != homeTab) {
            act.click();
            return;
        }
        if (!optionsUrl.isBlank()) {
            openExtensionTab(extName, optionsUrl);
            return;
        }
        if (act != null) {
            act.click();
            return;
        }
        CinemaSnackBanner.show(context, l("لا توجد صفحة إعدادات لهذه الإضافة", "No popup or settings page for this extension"));
    }

    private void openExtensionTab(String title, String url) {
        if (url == null || url.isBlank()) return;
        var tab = makeBackgroundTab(title);
        extensionTabs.add(tab);
        tab.open(runtime);
        tab.loadUri(url);
        activate(tabs.indexOf(tab));
    }

    private void openNewTab(boolean privateMode) {
        var tab = newTab(privateMode ? "Private tab" : context.getString(org.proninyaroslav.libretorrent.R.string.cinema_new_tab), null, privateMode);
        tab.open(runtime);
        activate(tabs.indexOf(tab));
        address.requestFocus();
    }

    private void closeTab(GeckoSession tab) {
        if (!tabs.contains(tab)) return;
        boolean replaceActive = tab == active;
        disposeTab(tab);
        if (replaceActive) {
            if (homeTab != null) disposeTab(homeTab);
            home();
        } else {
            tabIndex = tabs.indexOf(active);
            mediaBridge.updateDiscovery();
            saveTabs();
        }
    }

    private void closeTabs(boolean keepActive) {
        for (var tab : new ArrayList<>(tabs)) {
            if (!keepActive || tab != active) disposeTab(tab);
        }
        if (!keepActive) home();
        else {
            tabIndex = tabs.indexOf(active);
            mediaBridge.updateDiscovery();
            saveTabs();
        }
    }

    private void disposeTab(GeckoSession tab) {
        int index = tabs.indexOf(tab);
        if (index < 0) return;
        if (tab == active) {
            runtime.getWebExtensionController().setTabActive(tab, false);
            browser.releaseSession();
            active = null;
        }
        tabs.remove(index);
        titles.remove(index);
        if (tab == homeTab) homeTab = null;
        extensionTabs.remove(tab);
        privateTabs.remove(tab);
        locations.remove(tab);
        navigation.remove(tab);
        mediaBridge.removeSession(tab);
        tabStates.remove(tab);
        openerTabs.remove(tab);
        tab.close();
    }

    private void sharePage() {
        String url = locations.get(active);
        if (url == null) return;
        var intent = new android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, url);
        context.startActivity(android.content.Intent.createChooser(intent, null));
    }

    private java.util.List<String> history() {
        var result = new ArrayList<String>();
        try {
            var items = new org.json.JSONArray(settings.getString("history", "[]"));
            var hosts = new java.util.HashSet<String>();
            for (int i = 0; i < items.length(); i++) {
                String url = items.getString(i);
                String host = visitHost(url);
                if (host != null && hosts.add(host)) result.add(url);
            }
        } catch (org.json.JSONException ignored) {}
        return result;
    }

    private void rememberVisit(String url) {
        String host = visitHost(url);
        if (host == null) return;
        var items = history();
        items.removeIf(item -> host.equals(visitHost(item)));
        items.add(0, url);
        settings.edit().putString("history", new org.json.JSONArray(items.subList(0, Math.min(50, items.size()))).toString()).apply();
        updateVisitDetails(url, null, true);

    }

    private static String visitHost(String url) {
        if (!isWebPage(url)) return null;
        var page = Uri.parse(url);
        String host = page.getHost().toLowerCase(java.util.Locale.ROOT).replaceFirst("^www\\.", "");
        String path = page.getPath();
        if (page.getQueryParameter("q") != null
                && ("duckduckgo.com".equals(host) || "/search".equals(path) || "/url".equals(path))) return null;
        return host;
    }

    private static boolean isWebPage(String url) {
        var page = Uri.parse(url);
        return page.getHost() != null && page.getUserInfo() == null
                && ("https".equalsIgnoreCase(page.getScheme()) || "http".equalsIgnoreCase(page.getScheme()));
    }

    private void renderRecent() {
        renderRecent(true);
    }

    private void renderRecent(boolean force) {
        var items = history();
        String nextSig = items.isEmpty() ? "" : String.join("\n", items.subList(0, Math.min(5, items.size())));
        if (!force && nextSig.equals(renderedRecentSignature)
                && (items.isEmpty() || recent.getChildCount() > 0)) {
            return;
        }
        renderedRecentSignature = nextSig;
        recent.removeAllViews();
        recent.setBackground(null);
        recent.setPadding(0, 0, 0, 0);
        if (items.isEmpty()) return;
        var header = new LinearLayout(context);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        var title = new android.widget.TextView(context);
        title.setText(context.getString(org.proninyaroslav.libretorrent.R.string.cinema_visited_lately));
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(historyAction(context.getString(org.proninyaroslav.libretorrent.R.string.cinema_history), 0xffe5b84b, this::showHistory));
        recent.setPadding(dp(16), dp(16), dp(16), dp(16));
        recent.setBackground(surface(0xff1c1c1e, 22));
        recent.addView(header);
        for (String url : items.subList(0, Math.min(5, items.size()))) {
            if (recent.getChildCount() > 1) {
                var divider = new View(context);
                divider.setBackgroundColor(0xff303035);
                recent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            }
            recent.addView(historyRow(url, false, () -> openPage(url), null));
        }
    }

    private void showHistory() {
        var dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(context);
        var panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(10), dp(20), dp(20));
        panel.setBackground(surface(0xff18181b, 24));
        var grip = new View(context);
        grip.setBackground(surface(0xff48484e, 3));
        var gripLayout = new LinearLayout.LayoutParams(dp(38), dp(4));
        gripLayout.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        panel.addView(grip, gripLayout);
        var header = new LinearLayout(context);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        panel.addView(header);
        var title = historyText(context.getString(org.proninyaroslav.libretorrent.R.string.cinema_history), 22, 0xfff5f5f5, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        var close = historyAction("×", 0xfff5f5f5, dialog::dismiss);
        close.setContentDescription(close.getContext().getString(org.proninyaroslav.libretorrent.R.string.cinema_browser_close_history));
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        var privacy = historyText("The pages you opened in Local Cinema's browser, on your phone only. Nothing is kept in private mode.", 13, 0xff9e9ea4, false);
        privacy.setLineSpacing(dp(3), 1f);
        privacy.setPadding(0, dp(8), 0, dp(12));
        panel.addView(privacy);
        var scroll = new android.widget.ScrollView(context);
        int height = context.getResources().getDisplayMetrics().heightPixels;
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, Math.min(dp(440), height / 2)));
        var rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        var clear = historyAction("Clear all history", 0xfff26b6b, () -> {
            settings.edit().remove("history").remove("visit_details").apply();
            dialog.dismiss();
            renderRecent();
        });
        clear.setBackground(surface(0xff26262a, 24));
        panel.addView(clear, new LinearLayout.LayoutParams(-1, dp(48)));
        renderHistoryRows(rows, clear, dialog);
        dialog.setContentView(panel);
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        dialog.show();
    }

    private org.json.JSONObject visitDetails() {
        try { return new org.json.JSONObject(settings.getString("visit_details", "{}")); }
        catch (org.json.JSONException ignored) { return new org.json.JSONObject(); }
    }

    private void updateVisitDetails(String url, String title, boolean visited) {
        var details = visitDetails();
        var entry = details.optJSONObject(url);
        if (entry == null) entry = new org.json.JSONObject();
        try {
            if (visited) entry.put("time", System.currentTimeMillis());
            if (title != null) entry.put("title", title);
            details.put(url, entry);
            var retained = new org.json.JSONObject();
            for (String item : history()) {
                if (details.has(item)) retained.put(item, details.get(item));
            }
            settings.edit().putString("visit_details", retained.toString()).apply();
        } catch (org.json.JSONException ignored) {}
    }

    private void renderHistoryRows(LinearLayout rows, View clear, android.app.Dialog dialog) {
        rows.removeAllViews();
        var items = privateTabs.contains(active) ? java.util.List.<String>of() : history();
        clear.setEnabled(!items.isEmpty());
        if (items.isEmpty()) rows.addView(historyText("No browsing history", 14, 0xff9e9ea4, false));
        for (String url : items) {
            var row = historyRow(url, true, () -> {
                dialog.dismiss();
                openPage(url);
            }, () -> {
                var remaining = history();
                remaining.remove(url);
                var details = visitDetails();
                details.remove(url);
                settings.edit().putString("history", new org.json.JSONArray(remaining).toString())
                        .putString("visit_details", details.toString()).apply();
                renderHistoryRows(rows, clear, dialog);
                renderRecent();
            });
            var layout = new LinearLayout.LayoutParams(-1, -2);
            layout.bottomMargin = dp(8);
            rows.addView(row, layout);
        }
    }

    private View historyRow(String url, boolean full, Runnable open, Runnable remove) {
        var row = new LinearLayout(context);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(full ? dp(12) : 0, dp(10), full ? dp(4) : 0, dp(10));
        if (full) row.setBackground(surface(0xff222226, 16));
        row.setFocusable(true);
        row.setOnClickListener(view -> open.run());
        var frame = new FrameLayout(context);
        frame.setBackground(surface(0xff262629, 12));
        int size = dp(full ? 38 : 40);
        row.addView(frame, new LinearLayout.LayoutParams(size, size));
        var fallback = historyText("◷", 22, 0xff9e9ea4, false);
        fallback.setGravity(android.view.Gravity.CENTER);
        frame.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        var icon = new android.widget.ImageView(context);
        icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        frame.addView(icon, new FrameLayout.LayoutParams(dp(28), dp(28), android.view.Gravity.CENTER));
        bindFavicon(url, icon, fallback);
        var text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        var textLayout = new LinearLayout.LayoutParams(0, -2, 1);
        textLayout.setMarginStart(dp(12));
        row.addView(text, textLayout);
        String host = Uri.parse(url).getHost();
        var entry = visitDetails().optJSONObject(url);
        String name = entry == null ? host : entry.optString("title", host);
        long time = entry == null ? 0 : entry.optLong("time");
        String subtitle = full && time > 0
                ? android.text.format.DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(),
                        android.text.format.DateUtils.MINUTE_IN_MILLIS) + ", " + host : host;
        text.addView(historyLine(name, full ? 14 : 14.5f, 0xfff5f5f5, true));
        text.addView(historyLine(subtitle, full ? 12 : 12.5f, 0xffa1a1aa, false));
        if (remove != null) {
            var delete = historyAction("×", 0xffa1a1aa, remove);
            delete.setContentDescription(context.getString(org.proninyaroslav.libretorrent.R.string.cinema_browser_remove_history, name));
            row.addView(delete, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        return row;
    }

    private android.widget.TextView historyLine(String value, float size, int color, boolean bold) {
        var view = historyText(value, size, color, bold);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }

    android.widget.TextView historyText(String value, float size, int color, boolean bold) {
        var view = new android.widget.TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    android.widget.Button historyAction(String value, int color, Runnable click) {
        var view = new android.widget.Button(context);
        view.setText(value);
        view.setAllCaps(false);
        view.setTextColor(color);
        view.setTextSize(14);
        view.setMinHeight(dp(48));
        view.setMinimumWidth(0);
        view.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x30ffffff), null, surface(0xffffffff, 16)));
        view.setOnClickListener(button -> click.run());
        return view;
    }

    private boolean blockedHost(String domain) {
        if (domain == null) return false;
        String host = domain.toLowerCase(java.util.Locale.ROOT).replaceFirst("\\.$", "");
        while (true) {
            if (malwareHosts.contains(host)) return true;
            int dot = host.indexOf('.');
            if (dot < 0) return false;
            host = host.substring(dot + 1);
        }
    }

    private void updateNavigation(GeckoSession session, int flag, boolean allowed) {
        int state = navigation.getOrDefault(session, 0);
        state = allowed ? state | flag : state & ~flag;
        navigation.put(session, state);
        if (session != active) return;
        back.setEnabled(session != homeTab);
        forward.setEnabled((state & 2) != 0);
    }

    private MaterialButton browserControl(LinearLayout row, int iconRes, String label, Runnable action) {
        var control = button("", action);
        stylePillIconButton(control, iconRes, false);
        control.setContentDescription(label);
        var position = new LinearLayout.LayoutParams(0, dp(42), 1);
        position.setMargins(dp(4), 0, dp(4), 0);
        row.addView(control, position);
        return control;
    }

    private MaterialButton browserControlText(LinearLayout row, String text, String label, Runnable action) {
        var control = button(text, action);
        stylePillIconButton(control, 0, false);
        control.setContentDescription(label);
        var position = new LinearLayout.LayoutParams(0, dp(42), 1);
        position.setMargins(dp(4), 0, dp(4), 0);
        row.addView(control, position);
        return control;
    }

    int dp(int size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }

    boolean isArabic() {
        var locales = androidx.core.os.ConfigurationCompat.getLocales(context.getResources().getConfiguration());
        var loc = locales.isEmpty() ? java.util.Locale.getDefault() : locales.get(0);
        return loc != null && "ar".equalsIgnoreCase(loc.getLanguage());
    }

    String l(String ar, String en) {
        return isArabic() ? ar : en;
    }

    private void refreshDownloadsState() {
        if (downloadsDialog != null && downloadsDialog.isShowing() && downloadsContainer != null) {
            renderDownloadsContainer();
        }
    }

    private void promptDirectVideoDownload(String url, String contentDisposition, long contentLength, String titleHint) {
        promptDirectVideoDownload(url, contentDisposition, contentLength, titleHint, null, null, null);
    }

    void promptDirectVideoDownload(String url, String contentDisposition, long contentLength,
                                   String titleHint, String explicitReferer, String explicitUa, String explicitCookie) {
        String suggestedName = BrowserDownloadManager.inferFilename(url, contentDisposition, titleHint);
        String sizeText = contentLength > 0
                ? BrowserDownloadManager.formatBytes(contentLength)
                : l("سيتم تحديده تلقائياً عند البدء", "Will be detected on start");
        String message = l("اسم الملف: ", "Filename: ") + suggestedName
                + "\n" + l("الحجم: ", "Size: ") + sizeText
                + "\n" + l("مسار الحفظ: ", "Save path: ") + "Local Cinema/Downloading";
        new AlertDialog.Builder(context)
                .setTitle(l("تم اكتشاف رابط فيديو مباشر", "Direct Video Link Detected"))
                .setMessage(message)
                .setPositiveButton(l("تحميل للجهاز", "Download"), (d, w) -> {
                    String referer = (explicitReferer != null && !explicitReferer.isBlank()) ? explicitReferer
                            : (active != null ? java.util.Objects.toString(locations.get(active), "") : "");
                    String ua = (explicitUa != null && !explicitUa.isBlank()) ? explicitUa : "";
                    if (ua.isBlank()) {
                        try {
                            String u = android.webkit.WebSettings.getDefaultUserAgent(context);
                            if (u != null) ua = u;
                        } catch (Throwable ignored) {}
                    }
                    String ck = explicitCookie == null ? "" : explicitCookie;
                    downloadManager.enqueue(url, suggestedName, contentLength, referer, ua, ck);
                    CinemaSnackBanner.show(context,
                            l("بدأ تحميل: ", "Downloading: ") + suggestedName,
                            l("التحميلات", "Downloads"),
                            this::showDownloadsSheet);
                })
                .setNeutralButton(l("بث على الشاشة", "Cast to TV"), (d, w) ->
                        TvHubActivity.castFromBrowser(context, url, null, "mp4", suggestedName, 0,
                                null, explicitReferer, "", explicitUa, explicitCookie, null))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDownloadsSheet() {
        if (downloadsDialog != null && downloadsDialog.isShowing()) {
            renderDownloadsContainer();
            return;
        }
        var dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(context);
        downloadsDialog = dialog;
        var panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutDirection(isArabic() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        panel.setPadding(dp(16), dp(10), dp(16), dp(16));
        var shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(0xff141517);
        float radius = dp(24);
        shape.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        panel.setBackground(shape);

        var grip = new View(context);
        grip.setBackground(surface(0xff48484e, 3));
        var gripLp = new LinearLayout.LayoutParams(dp(38), dp(4));
        gripLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        panel.addView(grip, gripLp);

        var header = new LinearLayout(context);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        var heading = new LinearLayout(context);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.addView(historyText(l("مدير تحميلات المتصفح", "Browser Downloads"), 18, 0xfff5c518, true));
        heading.addView(historyText("Local Cinema/Downloading · " + l("ضغط مطوّل للحذف النهائي من الجهاز", "Long-press any row to delete file from device"),
                11, 0xffa1a1aa, false));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(historyAction("×", 0xfffafafa, dialog::dismiss), new LinearLayout.LayoutParams(dp(44), dp(44)));
        panel.addView(header);

        var scroll = new android.widget.ScrollView(context);
        downloadsContainer = new LinearLayout(context);
        downloadsContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(downloadsContainer);
        int maxH = Math.round(context.getResources().getDisplayMetrics().heightPixels * 0.65f);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, maxH));

        dialog.setOnDismissListener(d -> {
            if (downloadsDialog == dialog) {
                downloadsDialog = null;
                downloadsContainer = null;
            }
        });
        renderDownloadsContainer();
        dialog.setContentView(panel);
        dialog.show();
        if (panel.getParent() instanceof View parent) parent.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
    }

    void renderDownloadsContainer() {
        if (downloadsContainer == null) return;
        downloadsContainer.removeAllViews();
        var downloadable = new ArrayList<CinemaBrowserMediaBridge.VideoOption>();
        if (active != null && active != homeTab) {
            for (var opt : mediaBridge.videoOptions(active)) {
                if (!"hls".equalsIgnoreCase(opt.kind()) && !"dash".equalsIgnoreCase(opt.kind())
                        && opt.url() != null && opt.url().startsWith("http")) {
                    downloadable.add(opt);
                }
            }
        }
        if (!downloadable.isEmpty()) {
            var capSec = historyText(l("فيديوهات الصفحة الحالية القابلة للتحميل", "Current Page Downloadable Videos")
                    + " (" + downloadable.size() + ")", 13, 0xffe5b84b, true);
            capSec.setPadding(0, dp(8), 0, dp(6));
            downloadsContainer.addView(capSec);
            String pageTitle = downloadable.stream().map(CinemaBrowserMediaBridge.VideoOption::title).filter(t -> !t.isBlank())
                    .findFirst().orElse("Video");
            for (var opt : downloadable) {
                var row = new LinearLayout(context);
                row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setBackground(surface(0xff222226, 12));
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                var lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = dp(6);
                var q = historyText(opt.quality(), 12, 0xff18181b, true);
                q.setBackground(surface(0xffe5b84b, 7));
                q.setPadding(dp(7), dp(3), dp(7), dp(3));
                row.addView(q);
                String extra = opt.sizeBytes() > 0 ? " · " + BrowserDownloadManager.formatBytes(opt.sizeBytes()) : "";
                var info = historyText(opt.format() + extra, 12, 0xfffafafa, false);
                info.setPadding(dp(8), 0, dp(6), 0);
                row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(historyAction("⬇", 0xfff5c518, () ->
                        promptDirectVideoDownload(opt.url(), null, opt.sizeBytes() > 0 ? opt.sizeBytes() : -1L,
                                pageTitle, opt.referer(), opt.userAgent(), opt.cookie())),
                        new LinearLayout.LayoutParams(dp(40), dp(36)));
                downloadsContainer.addView(row, lp);
            }
        }
        var all = downloadManager.listAll();
        var activeDlList = new ArrayList<BrowserDownloadManager.DownloadItem>();
        var completed = new ArrayList<BrowserDownloadManager.DownloadItem>();
        for (var it : all) {
            if (BrowserDownloadManager.STATUS_COMPLETED.equals(it.status)) completed.add(it);
            else activeDlList.add(it);
        }
        if (all.isEmpty() && downloadable.isEmpty()) {
            var empty = historyText(l("لا توجد تحميلات حتى الآن.\nاضغط على أي رابط فيديو مباشر (MP4 / MKV / WebM) أو زر ⬇ داخل قائمة الفيديو للتحميل إلى Local Cinema/Downloading.",
                    "No downloads yet.\nTap any direct video link (MP4 / MKV / WebM) or ⬇ in the video sheet to save to Local Cinema/Downloading."),
                    13, 0xffa1a1aa, false);
            empty.setPadding(dp(4), dp(20), dp(4), dp(20));
            downloadsContainer.addView(empty);
            return;
        }
        if (!activeDlList.isEmpty()) {
            var sec = historyText(l("جاري التحميل", "In Progress") + " (" + activeDlList.size() + ")", 13, 0xfff5c518, true);
            sec.setPadding(0, dp(10), 0, dp(6));
            downloadsContainer.addView(sec);
            for (var item : activeDlList) downloadsContainer.addView(buildDownloadRow(item, false));
        }
        if (!completed.isEmpty()) {
            var sec = historyText(l("مكتمل", "Completed") + " (" + completed.size() + ")", 13, 0xff4ade80, true);
            sec.setPadding(0, dp(14), 0, dp(6));
            downloadsContainer.addView(sec);
            for (var item : completed) downloadsContainer.addView(buildDownloadRow(item, true));
        }
    }

    private LinearLayout buildDownloadRow(BrowserDownloadManager.DownloadItem item, boolean completed) {
        var card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        var bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xff1e2024);
        bg.setStroke(dp(1), completed ? 0xff2f4a37 : 0xff3b372c);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        var lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);

        var top = new LinearLayout(context);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        var nameTv = historyText(item.filename, 14, 0xfffafafa, true);
        nameTv.setMaxLines(2);
        nameTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        top.addView(nameTv, new LinearLayout.LayoutParams(0, -2, 1));

        if (!completed) {
            boolean running = BrowserDownloadManager.STATUS_RUNNING.equals(item.status);
            var toggle = historyAction(running ? "⏸" : "▶", 0xfff5c518, () -> {
                if (running) downloadManager.pause(item.id);
                else downloadManager.resume(item.id);
            });
            top.addView(toggle, new LinearLayout.LayoutParams(dp(40), dp(40)));
        } else {
            var play = historyAction("▶", 0xff4ade80, () -> {
                if (downloadsDialog != null) downloadsDialog.dismiss();
                TvHubActivity.castDownloadedFile(context, item.filePath, item.filename);
            });
            top.addView(play, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }

        var removeRec = historyAction("✕", 0xffa1a1aa, () -> {
            if (completed) {
                downloadManager.removeRecordOnly(item.id);
            } else {
                downloadManager.deleteRecordAndFile(item.id);
                CinemaSnackBanner.show(context, l("تم إلغاء التحميل وحذف الملف غير المكتمل نهائياً ✓",
                        "Download cancelled and incomplete file deleted ✓"));
            }
        });
        removeRec.setContentDescription(completed
                ? l("إزالة السجل", "Remove record")
                : l("إلغاء وحذف الملف غير المكتمل", "Cancel and delete incomplete file"));
        top.addView(removeRec, new LinearLayout.LayoutParams(dp(40), dp(40)));
        card.addView(top);

        if (!completed) {
            var bar = new android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setIndeterminate(item.totalBytes <= 0 && BrowserDownloadManager.STATUS_RUNNING.equals(item.status));
            bar.setProgress(item.progressPercent());
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xfff5c518));
            var barLp = new LinearLayout.LayoutParams(-1, dp(5));
            barLp.topMargin = dp(6);
            barLp.bottomMargin = dp(4);
            card.addView(bar, barLp);

            String stateLabel = BrowserDownloadManager.STATUS_FAILED.equals(item.status)
                    ? l("فشل: ", "Failed: ") + item.error
                    : BrowserDownloadManager.STATUS_PAUSED.equals(item.status)
                    ? l("متوقف مؤقتاً", "Paused")
                    : (BrowserDownloadManager.formatBytes(item.speedBytesPerSec) + "/s");
            String sizes = item.totalBytes > 0
                    ? item.progressPercent() + "% · " + BrowserDownloadManager.formatBytes(item.downloadedBytes)
                    + " / " + BrowserDownloadManager.formatBytes(item.totalBytes)
                    : BrowserDownloadManager.formatBytes(item.downloadedBytes);
            var meta = historyText(sizes + " · " + stateLabel, 12,
                    BrowserDownloadManager.STATUS_FAILED.equals(item.status) ? 0xffff8a8a : 0xffd0d4da, false);
            card.addView(meta);
        } else {
            var meta = historyText(l("مكتمل ✓ · ", "Completed ✓ · ")
                    + BrowserDownloadManager.formatBytes(Math.max(item.totalBytes, item.downloadedBytes)),
                    12, 0xff86efac, false);
            card.addView(meta);
        }

        card.setOnLongClickListener(v -> {
            new AlertDialog.Builder(context)
                    .setTitle(l("حذف الملف نهائياً من الجهاز؟", "Delete file permanently from device?"))
                    .setMessage(item.filename + "\n" + item.filePath)
                    .setPositiveButton(l("حذف نهائياً", "Delete Permanently"), (d, w) -> {
                        boolean deleted = downloadManager.deleteRecordAndFile(item.id);
                        CinemaSnackBanner.show(context, deleted
                                ? l("تم حذف الملف والسجل نهائياً ✓", "File and record permanently deleted ✓")
                                : l("تمت إزالة السجل.", "Record removed."));
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
        return card;
    }

    private MaterialButton button(String name, Runnable onClick) {
        var button = new MaterialButton(context);
        button.setText(name);
        button.setOnClickListener(view -> onClick.run());
        return button;
    }
}

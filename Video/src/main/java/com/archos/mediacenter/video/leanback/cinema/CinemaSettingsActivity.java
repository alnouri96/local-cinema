package com.archos.mediacenter.video.leanback.cinema;

import com.meedx.updates.ProjectLinks;

import com.meedx.providers.OpenMetadata;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.preference.PreferenceManager;
import com.archos.mediacenter.video.CustomApplication;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import java.util.Collections;
import java.util.List;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

public class CinemaSettingsActivity extends LeanbackActivity {
    private LinearLayout content;
    private ScrollView scrollView;
    private SharedPreferences prefs;
    private boolean homePage;
    private boolean rootPage;
    private boolean resumedOnce;
    private String pageTitle;
    private String currentPageId = "root";
    private String persistedFocusKey;
    private int persistedFocusIndex = -1;
    private int persistedScrollY = -1;
    private static final String PREF_SETTINGS_PAGE = "cinema_settings_last_page";
    private static final String PREF_SETTINGS_KEY = "cinema_settings_last_key";
    private static final String PREF_SETTINGS_INDEX = "cinema_settings_last_index";
    private static final String PREF_SETTINGS_SCROLL_Y = "cinema_settings_last_scroll_y";
    private Runnable backTarget;
    private Runnable currentRender;
    private boolean restoringPageFocus;
    private final java.util.Map<String, String> savedFocusKeys = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> savedFocusPositions = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> savedScrollPositions = new java.util.HashMap<>();
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.content.BroadcastReceiver syncReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context ctx, android.content.Intent intent) {
            if (!isFinishing() && !isDestroyed() && currentRender != null) currentRender.run();
        }
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        com.meedx.providers.TraktSyncClient.migrateAndCleanCredentials(this);
        prefs=PreferenceManager.getDefaultSharedPreferences(this);
        androidx.core.content.ContextCompat.registerReceiver(this, syncReceiver,
                settingsSyncActions(),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        restorePersistedSettingsPage();
    }

    private void seedParentBreadcrumbsFor(String pageId) {
        if (pageId == null || "root".equals(pageId)) return;
        switch (pageId) {
            case "language" -> savedFocusKeys.putIfAbsent("root", focusKey(l("اللغة وأولوية اللغات", "Language & Priorities")));
            case "appearance" -> savedFocusKeys.putIfAbsent("root", focusKey(l("المظهر وتخصيص الشاشة الرئيسية", "Appearance & Home Screen")));
            case "playback_audio" -> savedFocusKeys.putIfAbsent("root", focusKey(l("التشغيل والصوت والترجمة", "Playback, Audio & Subtitles")));
            case "cinema_engine" -> {
                savedFocusKeys.putIfAbsent("playback_audio", focusKey(getString(com.archos.mediacenter.video.R.string.cinema_cinema_engine)));
                savedFocusKeys.putIfAbsent("root", focusKey(l("التشغيل والصوت والترجمة", "Playback, Audio & Subtitles")));
            }
            case "playback_filters" -> savedFocusKeys.putIfAbsent("root", focusKey(l("المشاهدة العائلية وتخطي الإعلانات", "Family Watch & Skip Ads")));
            case "library_posters" -> savedFocusKeys.putIfAbsent("root", focusKey(l("المكتبة والبوسترات والتخزين", "Library, Posters & Storage")));
            case "storage_cache" -> {
                savedFocusKeys.putIfAbsent("library_posters", focusKey(l("التخزين وتنظيف الكاش الآمن", "Storage & Safe Cache Cleaner")));
                savedFocusKeys.putIfAbsent("root", focusKey(l("المكتبة والبوسترات والتخزين", "Library, Posters & Storage")));
            }
            case "metadata", "posters" -> {
                savedFocusKeys.putIfAbsent("library_posters", focusKey(l("مصدر البيانات وتحديث البوسترات", "Metadata Source & Poster Refresh")));
                savedFocusKeys.putIfAbsent("root", focusKey(l("المكتبة والبوسترات والتخزين", "Library, Posters & Storage")));
            }
            case "libraries" -> savedFocusKeys.putIfAbsent("root", focusKey(l("المكتبة والبوسترات والتخزين", "Library, Posters & Storage")));
            case "connections" -> savedFocusKeys.putIfAbsent("root", focusKey(l("الاتصال ومزامنة الهاتف", "Connections & Phone Sync")));
            case "remote" -> {
                savedFocusKeys.putIfAbsent("connections", focusKey(l("ريموت الهاتف والأذونات", "Phone Remote & Permissions")));
                savedFocusKeys.putIfAbsent("root", focusKey(l("الاتصال ومزامنة الهاتف", "Connections & Phone Sync")));
            }
            case "phone_sync" -> {
                savedFocusKeys.putIfAbsent("connections", focusKey(l("حالة مزامنة الهاتف", "Phone Sync Status")));
                savedFocusKeys.putIfAbsent("root", focusKey(l("الاتصال ومزامنة الهاتف", "Connections & Phone Sync")));
            }
            case "trakt" -> {
                savedFocusKeys.putIfAbsent("phone_sync", focusKey(l("حالة ومزامنة Trakt", "Trakt Sync Status")));
                savedFocusKeys.putIfAbsent("connections", focusKey(l("حالة مزامنة الهاتف", "Phone Sync Status")));
                savedFocusKeys.putIfAbsent("root", focusKey(l("الاتصال ومزامنة الهاتف", "Connections & Phone Sync")));
            }
            case "system" -> savedFocusKeys.putIfAbsent("root", focusKey(l("النظام والصلاحيات والتحديثات", "System, Permissions & Updates")));
            case "about" -> {
                savedFocusKeys.putIfAbsent("system", focusKey(getString(R.string.cinema_about_licenses)));
                savedFocusKeys.putIfAbsent("root", focusKey(l("النظام والصلاحيات والتحديثات", "System, Permissions & Updates")));
            }
            case "network" -> {
                savedFocusKeys.putIfAbsent("system", focusKey(getString(com.archos.mediacenter.video.R.string.cinema_network)));
                savedFocusKeys.putIfAbsent("root", focusKey(l("النظام والصلاحيات والتحديثات", "System, Permissions & Updates")));
            }
        }
    }

    private void restorePersistedSettingsPage() {
        String savedPage = prefs.getString(PREF_SETTINGS_PAGE, "root");
        String resolvedPage = savedPage == null ? "root" : savedPage;
        persistedFocusKey = prefs.getString(PREF_SETTINGS_KEY, null);
        persistedFocusIndex = prefs.getInt(PREF_SETTINGS_INDEX, -1);
        persistedScrollY = prefs.getInt(PREF_SETTINGS_SCROLL_Y, -1);
        if (persistedFocusKey != null && !persistedFocusKey.isBlank()) {
            savedFocusKeys.put(resolvedPage, persistedFocusKey);
        }
        if (persistedFocusIndex >= 0) {
            savedFocusPositions.put(resolvedPage, persistedFocusIndex);
        }
        if (persistedScrollY >= 0) {
            savedScrollPositions.put(resolvedPage, persistedScrollY);
        }
        seedParentBreadcrumbsFor(resolvedPage);
        switch (resolvedPage) {
            case "storage_cache" -> storageAndCachePage();
            case "language" -> languageGroup();
            case "library_posters" -> libraryAndPostersGroup();
            case "playback_audio" -> playbackAudioGroup();
            case "playback_filters" -> playbackFiltersPage();
            case "libraries" -> librariesPage();
            case "metadata" -> metadataPage();
            case "connections" -> connectionsGroup();
            case "phone_sync" -> phoneSyncStatusPage();
            case "system" -> systemGroup();
            case "about" -> aboutLicenses();
            case "network" -> networkPage();
            case "cinema_engine" -> cinemaEngine();
            case "trakt" -> traktSettings();
            case "appearance" -> appearance();
            case "remote" -> remoteSettings();
            case "posters" -> posterSettings();
            default -> showRoot();
        }
    }

    private void persistSettingsFocusState() {
        if (prefs == null) return;
        saveCurrentFocusState();
        String pid = currentPageId != null ? currentPageId : "root";
        String focusKeyVal = savedFocusKeys.get(pid);
        int focusIdx = savedFocusPositions.getOrDefault(pid, -1);
        int scrollY = scrollView != null ? scrollView.getScrollY() : savedScrollPositions.getOrDefault(pid, 0);
        SharedPreferences.Editor ed = prefs.edit()
                .putString(PREF_SETTINGS_PAGE, pid)
                .putInt(PREF_SETTINGS_INDEX, focusIdx)
                .putInt(PREF_SETTINGS_SCROLL_Y, Math.max(0, scrollY));
        if (focusKeyVal != null && !focusKeyVal.isBlank()) ed.putString(PREF_SETTINGS_KEY, focusKeyVal);
        else ed.remove(PREF_SETTINGS_KEY);
        ed.apply();
    }
    private static android.content.IntentFilter settingsSyncActions() {
        var filter = new android.content.IntentFilter(CinemaLinkServer.ACTION_API_SYNC_UPDATED);
        filter.addAction(CinemaLinkServer.ACTION_ADDONS_CHANGED);
        return filter;
    }

    @Override public void onPause(){
        persistSettingsFocusState();
        super.onPause();
    }
    @Override public void onResume(){
        super.onResume();
        if(!resumedOnce){resumedOnce=true;return;}
        if(currentRender!=null)currentRender.run();
    }
    @Override protected void onDestroy(){
        try { unregisterReceiver(syncReceiver); } catch (Exception ignored) {}
        io.shutdownNow();mainHandler.removeCallbacksAndMessages(null);super.onDestroy();
    }
    private void saveCurrentFocusState(){
        if(restoringPageFocus||currentPageId==null||content==null)return;
        View focused=getCurrentFocus();
        if(focused!=null&&focused.getParent()==content){
            int idx=content.indexOfChild(focused);
            if(idx>=0)savedFocusPositions.put(currentPageId,idx);
            String keyVal = focused.getTag() instanceof String t ? t
                    : (focused instanceof TextView tv ? focusKey(tv.getText().toString()) : null);
            if(keyVal!=null&&!keyVal.isBlank())savedFocusKeys.put(currentPageId,keyVal);
        }
        if(scrollView!=null)savedScrollPositions.put(currentPageId,Math.max(0,scrollView.getScrollY()));
    }
    private void page(String name){
        final String pid = currentPageId != null ? currentPageId : "root";
        seedParentBreadcrumbsFor(pid);
        pageTitle=name;
        rootPage=false;
        restoringPageFocus=true;
        ScrollView scroll=new ScrollView(this);scroll.setBackground(CinemaUi.surface());scrollView=scroll;
        if(arabic(this))scroll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(this,CinemaTokens.UTILITY_GUTTER),dp(this,CinemaTokens.SAFE),dp(this,CinemaTokens.UTILITY_GUTTER),dp(this,CinemaTokens.SAFE));
        content.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        scroll.addView(content);setContentView(scroll);
        TextView title=text(this,CinemaTokens.PAGE_TITLE,true);title.setText(name);title.setPadding(0,0,0,dp(this,18));content.addView(title);
        LinearLayout pageContent=content;
        String restore=persistedFocusKey!=null?persistedFocusKey:savedFocusKeys.get(pid);
        int savedIndex=persistedFocusIndex>=0?persistedFocusIndex:savedFocusPositions.getOrDefault(pid,1);
        int savedScrollY=persistedScrollY>=0?persistedScrollY:savedScrollPositions.getOrDefault(pid,0);
        persistedFocusKey=null;
        persistedFocusIndex=-1;
        persistedScrollY=-1;
        final String restoreKey = restore;
        androidx.core.view.OneShotPreDrawListener.add(scroll, () -> {
            if(content!=pageContent||isFinishing()||isDestroyed())return;
            pageContent.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
            View target=null;
            if(restoreKey!=null&&!restoreKey.isBlank()){
                for(int n=1;n<pageContent.getChildCount();n++){
                    View child=pageContent.getChildAt(n);
                    String childKey = child.getTag() instanceof String t ? t : (child instanceof TextView tv ? focusKey(tv.getText().toString()) : null);
                    if(child.isFocusable() && childKey != null && childKey.equals(restoreKey)){
                        target=child;
                        break;
                    }
                }
            }
            if(target==null&&savedIndex>0&&savedIndex<pageContent.getChildCount()){
                View candidate=pageContent.getChildAt(savedIndex);
                if(candidate.isFocusable())target=candidate;
            }
            if(target==null){
                int bestDist=Integer.MAX_VALUE;
                for(int n=1;n<pageContent.getChildCount();n++){
                    View child=pageContent.getChildAt(n);
                    if(!child.isFocusable())continue;
                    int d=Math.abs(n-savedIndex);
                    if(d<bestDist){bestDist=d;target=child;}
                }
            }
            if(savedScrollY>0){
                scroll.scrollTo(0,savedScrollY);
            }
            if(target!=null){
                final View finalTarget = target;
                finalTarget.requestFocus();
                int scrollH = scroll.getHeight();
                if (scrollH > 0) {
                    int curY = scroll.getScrollY();
                    int top = finalTarget.getTop() - dp(this, 18);
                    int bottom = finalTarget.getBottom() + dp(this, 18);
                    if (top < curY) scroll.scrollTo(0, Math.max(0, top));
                    else if (bottom > curY + scrollH) scroll.scrollTo(0, Math.max(0, bottom - scrollH));
                    else if (savedScrollY > 0) scroll.scrollTo(0, savedScrollY);
                }
            }
            restoringPageFocus=false;
            saveCurrentFocusState();
        });
    }
    private String focusKey(String label){return label.replaceFirst("^[✓✕○]\\s*", "").split(" · ",2)[0].trim();}
    private void button(String title,Runnable action){
        rowButton(R.drawable.cinema_ic_system, title, null, null, action);
    }
    private void iconButton(int iconRes, String title, String subtitle, Runnable action){
        rowButton(iconRes, title, subtitle, null, action);
    }
    private void rowButton(int iconRes, String title, String subtitle, Boolean toggleState, Runnable action){
        final String ownerPageId = currentPageId;
        final String cardKey = focusKey(title);
        final LinearLayout[] holder = {null};
        Runnable rememberFocus = () -> {
            if (restoringPageFocus) return;
            if (ownerPageId != null && holder[0] != null && content != null && holder[0].getParent() == content) {
                int idx = content.indexOfChild(holder[0]);
                if (idx >= 0) savedFocusPositions.put(ownerPageId, idx);
                savedFocusKeys.put(ownerPageId, cardKey);
                if (scrollView != null) savedScrollPositions.put(ownerPageId, Math.max(0, scrollView.getScrollY()));
            }
        };
        holder[0] = CinemaUi.settingsCard(this, content, iconRes, title, subtitle, toggleState, rememberFocus, action == null ? null : () -> {
            if (ownerPageId != null && holder[0] != null && content != null && holder[0].getParent() == content) {
                int idx = content.indexOfChild(holder[0]);
                if (idx >= 0) savedFocusPositions.put(ownerPageId, idx);
                savedFocusKeys.put(ownerPageId, cardKey);
                if (scrollView != null) savedScrollPositions.put(ownerPageId, Math.max(0, scrollView.getScrollY()));
            }
            action.run();
        });
        if (action == null) {
            holder[0].setClickable(false);
            holder[0].setAlpha(0.5f);
        }
        holder[0].setTag(cardKey);
    }
    private void note(String value){TextView t=text(this,CinemaTokens.LABEL,false);t.setText(value);t.setPadding(0,dp(this,8),0,dp(this,14));content.addView(t);}
    private void sectionHeader(String title) {
        TextView header = text(this, 14, true);
        header.setText(title);
        header.setTextColor(CinemaTokens.GOLD);
        header.setFocusable(false);
        header.setClickable(false);
        header.setPaddingRelative(dp(this, 6), dp(this, 14), dp(this, 6), dp(this, 6));
        content.addView(header);
    }
    private String l(String ar,String en){return label(this,ar,en);}
    private void advanced(){CinemaNovaLegacyPrefs.openLegacyAdvancedPlayerSettings(this);}
    private String storageSubtitleCache;

    private String formatMb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void refreshStorageSummaryAsync(Runnable onUpdated) {
        io.execute(() -> {
            CinemaCacheMaintenance.Breakdown b = CinemaCacheMaintenance.inspectBreakdown(getApplicationContext());
            String summary = l("الكاش الآمن القابل للتنظيف: ", "Safe reclaimable cache: ")
                    + CinemaCacheMaintenance.formatSize(b.totalBytes())
                    + (b.torrentTempBytes() > 0L
                            ? l(" · تورنت مؤقت: ", " · Torrent temp: ") + CinemaCacheMaintenance.formatSize(b.torrentTempBytes())
                            : l(" · البوسترات: ", " · Posters: ") + CinemaCacheMaintenance.formatSize(b.postersAndImagesBytes()));
            mainHandler.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    storageSubtitleCache = summary;
                    if (onUpdated != null) onUpdated.run();
                }
            });
        });
    }

    private void showRoot(){
        currentPageId="root";homePage=false;backTarget=null;currentRender=this::showRoot;
        page(getString(com.archos.mediacenter.video.R.string.cinema_settings));
        rootPage=true;
        if (storageSubtitleCache == null) {
            storageSubtitleCache = l("التحقق من حجم البوسترات والكاش المؤقت…", "Calculating posters & cache size…");
        }
        refreshStorageSummaryAsync(null);

        sectionHeader(l("الواجهة واللغة", "Interface & Language"));
        iconButton(R.drawable.cinema_globe,
                l("اللغة وأولوية اللغات", "Language & Priorities"),
                l("التطبيق: ", "App: ") + currentAppLanguageLabel()
                        + l(" · البوسترات: ", " · Posters: ") + currentMetadataLangLabel(),
                this::languageGroup);
        iconButton(R.drawable.cinema_ic_appearance,
                l("المظهر وتخصيص الشاشة الرئيسية", "Appearance & Home Screen"),
                l("ترتيب الأقسام والإعلانات · البوسترات · الساعة والطقس", "Sections & Trailers order · Posters · Clock & Weather"),
                this::appearance);

        sectionHeader(l("التشغيل والمحتوى", "Playback & Content"));
        iconButton(R.drawable.cinema_play_movie,
                l("التشغيل والصوت والترجمة", "Playback, Audio & Subtitles"),
                l("أولوية الصوت · محرّك السينما · الترجمة · المشغّل المتقدم", "Audio Priority · Cinema Engine · Subtitles · Advanced Player"),
                this::playbackAudioGroup);
        iconButton(R.drawable.cinema_ic_shield,
                l("المشاهدة العائلية وتخطي الإعلانات", "Family Watch & Skip Ads"),
                l("المشاهدة العائلية: ", "Family Watch: ") + CinemaPlaybackOption.FAMILY_WATCH.state(this)
                        + l(" · تخطّي الإعلانات: ", " · Skip Ads: ") + CinemaPlaybackOption.SKIP_ADS.state(this),
                this::playbackFiltersPage);
        iconButton(R.drawable.cinema_addons, getString(R.string.addon_hub_title),
                getString(R.string.addon_settings_description), this::addonsSettings);
        iconButton(R.drawable.cinema_folder,
                l("المكتبة والبوسترات والتخزين", "Library, Posters & Storage"),
                l("مصادر SMB · البوسترات (", "SMB Sources · Posters (") + currentAutoRefreshLabel()
                        + l(") · التخزين والكاش", ") · Storage & Cache"),
                this::libraryAndPostersGroup);

        sectionHeader(l("الاتصال والنظام", "Connections & System"));
        iconButton(R.drawable.cinema_ic_addons,
                l("الاتصال ومزامنة الهاتف", "Connections & Phone Sync"),
                l("ربط الهاتف · الريموت وADB المحلي · المزامنة وTrakt", "Link Phone · Remote & Local ADB · Sync & Trakt"),
                this::connectionsGroup);
        iconButton(R.drawable.cinema_ic_system,
                l("النظام والصلاحيات والتحديثات", "System, Permissions & Updates"),
                com.meedx.updates.AppUpdates.displayVersion(com.archos.mediacenter.video.BuildConfig.VERSION_NAME)
                        + l(" · التحديثات · معالج الصلاحيات · التراخيص", " · Updates · Permissions Wizard · Licenses"),
                this::systemGroup);
    }

    private void storageAndCachePage() {
        homePage = false;
        backTarget = this::libraryAndPostersGroup;
        currentPageId = "storage_cache";
        currentRender = this::storageAndCachePage;
        page(l("التخزين وتنظيف الكاش الآمن", "Storage & Safe Cache Cleaner"));

        boolean rtl = arabic(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        card.setBackground(rounded(0xff1d1e21, dp(this, 14), 0xff4c4637));
        card.setPadding(dp(this, 18), dp(this, 16), dp(this, 18), dp(this, 16));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.bottomMargin = dp(this, 14);
        content.addView(card, cardLp);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));

        TextView heading = text(this, 17, true);
        heading.setText(l("تحليل الكاش الآمن للشاشة", "Safe Cache Breakdown"));
        heading.setTextColor(CinemaTokens.GOLD);
        heading.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        headerRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView totalBadge = text(this, 13, true);
        totalBadge.setText(l("جارٍ الحساب…", "Calculating…"));
        totalBadge.setTextColor(0xff141517);
        totalBadge.setBackground(rounded(CinemaTokens.GOLD, dp(this, 10), 0));
        totalBadge.setPadding(dp(this, 12), dp(this, 4), dp(this, 12), dp(this, 4));
        headerRow.addView(totalBadge);

        LinearLayout rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        rowsBox.setBackground(rounded(0xff151619, dp(this, 10), 0xff2c2d33));
        rowsBox.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(-1, -2);
        boxLp.topMargin = dp(this, 10);
        card.addView(rowsBox, boxLp);

        TextView torrentVal = addCacheBreakdownRow(rowsBox, l("ملفات بث التورنت المؤقتة", "Temp Torrent Buffers"), false);
        TextView updatesVal = addCacheBreakdownRow(rowsBox, l("حزم التحديثات المؤقتة (APK)", "Cached Update APKs"), true);
        TextView postersVal = addCacheBreakdownRow(rowsBox, l("كاش البوسترات والصور والبيانات", "Posters, Images & Metadata"), true);
        TextView miscVal = addCacheBreakdownRow(rowsBox, l("ملفات مؤقتة أخرى", "Other Temporary Files"), true);

        TextView safetyNote = text(this, 12, false);
        safetyNote.setText(l(
                "تنظيف آمن بالكامل: يحذف الملفات المؤقتة وبقايا البث فقط، ولا يمس ربط الهاتف أو الإضافات أو حساب Trakt أو تقدم المشاهدة أو الإعدادات.",
                "100% Safe: clears temporary buffers and caches only. Phone Link, add-ons, Trakt, watch progress, and settings stay untouched."));
        safetyNote.setTextColor(0xffb4b4bd);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.topMargin = dp(this, 10);
        card.addView(safetyNote, noteLp);

        final CinemaCacheMaintenance.Breakdown[] latest = {new CinemaCacheMaintenance.Breakdown(0L, 0L, 0L, 0L)};
        Runnable reloadSizes = () -> io.execute(() -> {
            CinemaCacheMaintenance.Breakdown b = CinemaCacheMaintenance.inspectBreakdown(getApplicationContext());
            latest[0] = b;
            storageSubtitleCache = l("الكاش الآمن القابل للتنظيف: ", "Safe reclaimable cache: ")
                    + CinemaCacheMaintenance.formatSize(b.totalBytes());
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                totalBadge.setText(CinemaCacheMaintenance.formatSize(b.totalBytes()));
                torrentVal.setText(CinemaCacheMaintenance.formatSize(b.torrentTempBytes()));
                updatesVal.setText(CinemaCacheMaintenance.formatSize(b.updateApkBytes()));
                postersVal.setText(CinemaCacheMaintenance.formatSize(b.postersAndImagesBytes()));
                miscVal.setText(CinemaCacheMaintenance.formatSize(b.miscTempBytes()));
            });
        });
        reloadSizes.run();

        sectionHeader(l("التنظيف اليدوي الآمن", "Manual Safe Cleanup"));
        iconButton(R.drawable.cinema_ic_system,
                l("تنظيف الكاش الآمن الآن", "Clean Safe Cache Now"),
                l("تحرير بقايا التورنت وحزم التحديث والصور والملفات المؤقتة فوراً دون المساس ببياناتك",
                        "Free torrent remnants, old update APKs, image caches & temp files safely"),
                () -> dialog(this)
                        .setTitle(l("تنظيف الكاش الآمن الآن؟", "Clean Safe Cache Now?"))
                        .setMessage(l("سيتم تحرير حوالي ", "Approximately ")
                                + CinemaCacheMaintenance.formatSize(latest[0].totalBytes())
                                + l(" من الكاش والملفات المؤقتة فقط.\n\nتبقى الإعدادات، ربط الهاتف، الإضافات، Trakt، وتقدم المشاهدة محفوظة بالكامل.",
                                    " of temporary caches and buffers will be cleared.\n\nSettings, Phone Link, add-ons, Trakt, and watch progress will NOT be affected."))
                        .setPositiveButton(l("تنظيف الآن", "Clean Now"), (d, w) -> io.execute(() -> {
                            long freed = CinemaCacheMaintenance.clearSafeCacheBlocking(getApplicationContext());
                            mainHandler.post(() -> {
                                android.widget.Toast.makeText(this,
                                        l("تم تنظيف الكاش الآمن وتحرير ", "Safe cache cleaned — freed ")
                                                + CinemaCacheMaintenance.formatSize(freed) + " ✓",
                                        android.widget.Toast.LENGTH_LONG).show();
                                reloadSizes.run();
                            });
                        }))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());

        iconButton(R.drawable.cinema_ic_posters,
                l("مسح كاش البوسترات والبيانات فقط", "Clear Posters & Metadata Cache Only"),
                l("حذف صور البوسترات وملفات TMDB المخبأة فقط وإعادة تنزيلها بنظافة",
                        "Delete cached poster images & TMDB JSON files only and re-fetch cleanly"),
                () -> dialog(this)
                        .setTitle(l("مسح كاش البوسترات والبيانات", "Clear Posters & Metadata Cache"))
                        .setMessage(l("سيتم حذف صور البوسترات وبيانات TMDB المخبأة وإعادة تنزيلها دون المساس بسجل المشاهدة أو الإعدادات.",
                                "Cached poster images and TMDB metadata JSON files will be deleted and reloaded. Watch history and settings remain untouched."))
                        .setPositiveButton(l("مسح الآن", "Clear Now"), (d, w) -> io.execute(() -> {
                            CinemaMetadataPrefetcher.clearPostersAndMetadataCache(getApplicationContext());
                            CinemaMetadataPrefetcher.runAsync(getApplicationContext(), true);
                            mainHandler.post(() -> {
                                android.widget.Toast.makeText(this,
                                        l("تم مسح كاش البوسترات وجارٍ إعادة التحميل بنظافة", "Posters & metadata cache cleared; reloading cleanly"),
                                        android.widget.Toast.LENGTH_SHORT).show();
                                reloadSizes.run();
                            });
                        }))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());

        sectionHeader(l("الصيانة الذاتية الذكية", "Smart Self-Maintenance"));
        boolean smartOn = CinemaCacheMaintenance.isSmartEnabled(this);
        CinemaCacheMaintenance.AdaptiveProfile profile = CinemaCacheMaintenance.adaptiveProfile(this);
        long lastRun = CinemaCacheMaintenance.lastRunMs(this);
        long lastFreed = CinemaCacheMaintenance.lastFreedBytes(this);
        String smartSub = l("الحد التكيّفي لهذا الجهاز: ", "Adaptive cap for this TV: ")
                + CinemaCacheMaintenance.formatSize(profile.imageCapBytes())
                + (lastRun > 0L
                        ? l(" · آخر صيانة حرّرت: ", " · Last run freed: ") + CinemaCacheMaintenance.formatSize(lastFreed)
                        : l(" · يزيل بقايا التورنت والتحديثات تلقائياً", " · Auto-prunes orphan torrents & stale APKs"));
        rowButton(R.drawable.cinema_ic_shield,
                l("الصيانة الذاتية الذكية التلقائية", "Smart Auto Self-Maintenance"),
                smartSub,
                smartOn,
                () -> {
                    CinemaCacheMaintenance.setSmartEnabled(this, !smartOn);
                    storageAndCachePage();
                });

        iconButton(R.drawable.cinema_ic_updates,
                l("تشغيل الصيانة الذكية الآن", "Run Smart Maintenance Now"),
                l("حذف بقايا التورنت وحزم التحديث القديمة وتقليص الكاش الزائد مع إبقاء البوسترات الحديثة",
                        "Prune orphan torrent buffers, stale APKs & excess cache while keeping recent posters"),
                () -> io.execute(() -> {
                    long freed = CinemaCacheMaintenance.runSmartMaintenanceBlocking(getApplicationContext(), true);
                    mainHandler.post(() -> {
                        android.widget.Toast.makeText(this,
                                freed > 0L
                                        ? l("اكتملت الصيانة الذكية · تم تحرير ", "Smart maintenance complete · freed ")
                                                + CinemaCacheMaintenance.formatSize(freed) + " ✓"
                                        : l("التخزين منظّم ونظيف بالفعل ✓", "Storage is already clean & optimized ✓"),
                                android.widget.Toast.LENGTH_LONG).show();
                        storageAndCachePage();
                    });
                }));
    }

    private TextView addCacheBreakdownRow(LinearLayout parent, String labelText, boolean topMargin) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        if (topMargin) lp.topMargin = dp(this, 6);
        parent.addView(row, lp);

        TextView lbl = text(this, 13, false);
        lbl.setText(labelText);
        lbl.setTextColor(0xffe4e4e7);
        lbl.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        row.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView val = text(this, 13, true);
        val.setText("…");
        val.setTextColor(CinemaTokens.GOLD);
        row.addView(val);
        return val;
    }

    private void languageGroup(){
        currentPageId="language";homePage=false;backTarget=this::showRoot;currentRender=this::languageGroup;
        page(l("اللغة وأولوية اللغات", "Language & Priorities"));
        iconButton(R.drawable.cinema_globe,
                l("لغة التطبيق", "App Language"),
                l("اللغة الحالية: ", "Current language: ") + currentAppLanguageLabel(),
                this::appLanguage);
        iconButton(R.drawable.cinema_ic_posters,
                l("لغة البوسترات والتقييمات", "Posters & Metadata Language"),
                l("المختار حالياً: ", "Selected: ") + currentMetadataLangLabel(),
                this::metadataLanguage);
    }

    private void libraryAndPostersGroup(){
        currentPageId="library_posters";homePage=false;backTarget=this::showRoot;currentRender=this::libraryAndPostersGroup;
        page(l("المكتبة والبوسترات والتخزين", "Library, Posters & Storage"));
        iconButton(R.drawable.cinema_network, getString(com.archos.mediacenter.video.R.string.cinema_manage_library_sources),
                l("مجلدات الشبكة (SMB) والتخزين الداخلي وUSB", "SMB network shares, internal & USB video folders"),
                () -> startActivity(new Intent(this, CinemaNetworkActivity.class)));
        iconButton(R.drawable.cinema_ic_posters, l("مصدر البيانات وتحديث البوسترات", "Metadata Source & Poster Refresh"),
                l("المصدر: ", "Source: ") + (OpenMetadata.enabled(this) ? l("مصادر مفتوحة", "Open Providers") : "TMDb")
                        + l(" · التحديث التلقائي: ", " · Auto-refresh: ") + currentAutoRefreshLabel(),
                this::metadataPage);
        iconButton(R.drawable.cinema_ic_posters, getString(com.archos.mediacenter.video.R.string.cinema_rating_sources),
                l("تحديث قاعدة بيانات IMDb وإعداد مفتاح OMDb", "Update IMDb dataset & configure OMDb key"),
                () -> startActivity(new Intent(this, CinemaRatingsActivity.class)));
        iconButton(R.drawable.cinema_folder, getString(com.archos.mediacenter.video.R.string.cinema_advanced_library_lists),
                l("استعراض وإدارة قوائم الفيديو المخصّصة", "Browse and manage custom video lists"),
                () -> CinemaNovaLegacyPrefs.openLegacyCustomVideoLists(this));
        iconButton(R.drawable.cinema_ic_system,
                l("التخزين وتنظيف الكاش الآمن", "Storage & Safe Cache Cleaner"),
                storageSubtitleCache != null ? storageSubtitleCache
                        : l("تحليل وتنظيف الكاش المؤقت والصيانة الذاتية الذكية", "Inspect & clean temp cache and smart self-maintenance"),
                this::storageAndCachePage);
    }

    private void playbackAudioGroup(){
        currentPageId="playback_audio";homePage=false;backTarget=this::showRoot;currentRender=this::playbackAudioGroup;
        page(l("التشغيل والصوت والترجمة", "Playback, Audio & Subtitles"));
        iconButton(R.drawable.cinema_ic_audio, getString(R.string.addon_preferred_audio),
                CinemaPreferredAudio.label(this), () -> CinemaPreferredAudio.choose(this, this::playbackAudioGroup));
        iconButton(R.drawable.cinema_ic_audio, getString(com.archos.mediacenter.video.R.string.cinema_cinema_engine),
                currentDisplayModeLabel() + " · " + (CinemaEngineBoundary.getDisplaySwitchDelayMs(this) / 1000.0) + " s", this::cinemaEngine);
        iconButton(R.drawable.cinema_subtitles,
                l("إعدادات الترجمة الموحّدة ومصادرها", "Unified Subtitle Settings & Sources"),
                com.meedx.providers.UnifiedSubtitleConfig.searchSelectionSummary(this, arabic(this))
                        + " · " + com.meedx.providers.UnifiedSubtitleConfig.languageDisplay(
                                com.meedx.providers.UnifiedSubtitleConfig.primaryLanguage(this), arabic(this))
                        + l(" · المظهر والمزامنة مع الهاتف", " · Appearance & Phone Sync"),
                () -> SubtitleSources.settings(this));
        iconButton(R.drawable.cinema_ic_appearance,
                l("مظهر وموضع وتوقيت الترجمة", "Subtitle Appearance, Position & Timing"),
                com.meedx.providers.UnifiedSubtitleConfig.appearanceSummary(this, arabic(this)),
                () -> CinemaSubtitleAppearancePanel.showStandaloneDialog(this, this::playbackAudioGroup));
        iconButton(R.drawable.cinema_play_movie, getString(com.archos.mediacenter.video.R.string.cinema_advanced_video_and_audio_settings),
                l("إعدادات فك الترميز والمزامنة والمشغّل المتقدمة والاستيراد/التصدير", "Hardware decoding, A/V, player preferences & import/export"), this::advanced);
    }

    private void playbackFiltersPage() {
        homePage = false;
        backTarget = this::showRoot;
        currentPageId = "playback_filters";
        currentRender = this::playbackFiltersPage;
        page(l("المشاهدة العائلية وتخطي الإعلانات", "Family Watch & Skip Ads"));
        for (CinemaPlaybackOption option : CinemaPlaybackOption.values()) {
            if (!option.available()) {
                rowButton(option.icon, getString(option.title), option.state(this), null, null);
                continue;
            }
            String source = option == CinemaPlaybackOption.FAMILY_WATCH
                    ? l("يتخطّى المشاهد المحددة في CleanStream وMovieHavenDB ‏(CC BY 4.0) وملفات .mcf، ويقرأ أوصاف المشاهد في الترجمة عند غياب التوقيتات",
                        "Uses CleanStream, MovieHavenDB (CC BY 4.0), .mcf files, and caption descriptions when timings are unavailable")
                    : l("يتخطّى الإعلانات والمقدمات والفواصل المحددة على الإنترنت أو في فصول الفيديو وملفات .edl",
                        "Skips marked ads, intros and breaks using online timings, chapters or .edl files");
            rowButton(option.icon, getString(option.title), source, option.enabled(this), () -> {
                option.toggle(this);
                CinemaLinkServer.notifyTvSettingsMutated(this);
                playbackFiltersPage();
            });
        }
    }

    private void librariesPage(){
        currentPageId="libraries";homePage=false;backTarget=this::libraryAndPostersGroup;currentRender=this::librariesPage;
        page(getString(com.archos.mediacenter.video.R.string.cinema_libraries));
        iconButton(R.drawable.cinema_network, getString(com.archos.mediacenter.video.R.string.cinema_manage_library_sources),
                l("إضافة أو تعديل مجلدات الفيديو على الشبكة والتخزين", "Add or manage network & storage video folders"),
                ()->startActivity(new Intent(this,CinemaNetworkActivity.class)));
        iconButton(R.drawable.cinema_ic_posters, getString(com.archos.mediacenter.video.R.string.cinema_rating_sources),
                l("تحديث قاعدة بيانات IMDb وإعداد مفتاح OMDb", "Update IMDb dataset & configure OMDb key"),
                ()->startActivity(new Intent(this,CinemaRatingsActivity.class)));
        iconButton(R.drawable.cinema_folder, getString(com.archos.mediacenter.video.R.string.cinema_advanced_library_lists),
                l("استعراض وإدارة قوائم الفيديو المخصّصة", "Browse and manage custom video lists"),
                ()->CinemaNovaLegacyPrefs.openLegacyCustomVideoLists(this));
    }

    private void metadataPage(){
        currentPageId="metadata";homePage=false;backTarget=this::libraryAndPostersGroup;currentRender=this::metadataPage;
        page(l("مصدر البيانات وتحديث البوسترات", "Metadata Source & Poster Refresh"));
        metadataSourceRow();
        autoRefreshMetadataRow(this::metadataPage);
        iconButton(R.drawable.cinema_ic_posters, getString(com.archos.mediacenter.video.R.string.cinema_fetch_missing_posters),
                l("تنزيل البوسترات الناقصة فقط دون مسح الكاش", "Download missing posters without clearing cache"),
                () -> requestPosterRefresh("missing"));
        iconButton(R.drawable.cinema_ic_updates, l("تحديث البوسترات والبيانات يدوياً الآن", "Refresh Posters & Metadata Now"),
                l("إعادة تحميل جميع البوسترات والسير والتقييمات", "Force refresh posters, cast & multi-ratings"), () ->
                CinemaUi.dialog(this).setTitle(l("تحديث البوسترات والبيانات يدوياً الآن", "Refresh Posters & Metadata Now"))
                        .setMessage(getString(com.archos.mediacenter.video.R.string.cinema_library_posters_will_be_downloaded_again_from_tmdb))
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> requestPosterRefresh("all"))
                        .setNegativeButton(android.R.string.cancel, null).show());
    }

    private String compactPhoneSyncSummary() {
        boolean traktLinked = com.meedx.providers.TraktSyncClient.isConnected(this);
        long addonsCount = com.meedx.providers.StremioAddons.list(this).stream().filter(a -> a.enabled).count();
        boolean hasSubKey = !com.meedx.providers.ProviderKeys.get(this, com.meedx.providers.ProviderKeys.SUBSOURCE).isBlank()
                || !com.meedx.providers.ProviderKeys.get(this, com.meedx.providers.ProviderKeys.OPEN_SUBTITLES).isBlank();
        boolean hasTmdb = !com.meedx.providers.ProviderKeys.get(this, com.meedx.providers.ProviderKeys.TMDB).isBlank();
        return (traktLinked ? l("Trakt: متصل ✓", "Trakt: Connected ✓")
                : l("Trakt: غير متصل", "Trakt: Offline"))
                + " · " + l("الإضافات: ", "Addons: ") + addonsCount
                + " · " + l("المفاتيح: ", "Keys: ") + ((hasSubKey || hasTmdb) ? "✓" : l("تلقائي", "Auto"));
    }

    private void connectionsGroup(){
        currentPageId="connections";homePage=false;backTarget=this::showRoot;currentRender=this::connectionsGroup;
        com.meedx.providers.TraktSyncClient.migrateAndCleanCredentials(this);
        page(l("الاتصال ومزامنة الهاتف", "Connections & Phone Sync"));
        iconButton(R.drawable.cinema_qr,
                l("ربط الهاتف (QR ورمز الاقتران)", "Link Phone (QR & Pairing Code)"),
                l("مسح رمز QR أو استخدام رمز الاقتران للتحكم والمزامنة", "Scan QR code or pair Phone companion"),
                () -> startActivity(new Intent(this, CinemaPhoneLinkActivity.class)));
        iconButton(R.drawable.cinema_ic_remote,
                l("ريموت الهاتف والأذونات", "Phone Remote & Permissions"),
                l("حماية الخلفية (TCL وبقية الشاشات) · الوصول · الكيبورد", "Sleep guard · Accessibility · ADB · Keyboard"),
                this::remoteSettings);
        iconButton(R.drawable.cinema_ic_addons,
                l("حالة مزامنة الهاتف", "Phone Sync Status"),
                compactPhoneSyncSummary(),
                this::phoneSyncStatusPage);
    }

    private void phoneSyncStatusPage() {
        currentPageId="phone_sync";homePage=false;backTarget=this::connectionsGroup;currentRender=this::phoneSyncStatusPage;
        com.meedx.providers.TraktSyncClient.migrateAndCleanCredentials(this);
        page(l("حالة مزامنة الهاتف", "Phone Sync Status"));
        var linkPrefs = getSharedPreferences("cinema_link", 0);
        long lastSyncAt = linkPrefs.getLong("last_phone_sync_at", 0L);
        String phoneName = linkPrefs.getString("last_phone_sync_name", "");
        String syncHeader = lastSyncAt > 0
                ? l("آخر مزامنة من الهاتف" + (phoneName.isBlank() ? "" : " (" + phoneName + ")") + " ✓",
                    "Last synced from Phone" + (phoneName.isBlank() ? "" : " (" + phoneName + ")") + " ✓")
                : l("أدر التوصيلات وحساب Trakt والمفاتيح والإضافات من تطبيق الهاتف (TV Hub ← Connection).",
                    "Manage Trakt, addons & API keys from Phone (TV Hub -> Connection). Synced data persists offline here.");
        boolean traktLinked = com.meedx.providers.TraktSyncClient.isConnected(this);
        long addonsCount = com.meedx.providers.StremioAddons.list(this).stream().filter(a -> a.enabled).count();
        boolean hasSubKey = !com.meedx.providers.ProviderKeys.get(this, com.meedx.providers.ProviderKeys.SUBSOURCE).isBlank()
                || !com.meedx.providers.ProviderKeys.get(this, com.meedx.providers.ProviderKeys.OPEN_SUBTITLES).isBlank();
        boolean hasTmdb = !com.meedx.providers.ProviderKeys.get(this, com.meedx.providers.ProviderKeys.TMDB).isBlank();
        note(syncHeader + "\n"
                + (traktLinked ? l("Trakt: متصل ✓", "Trakt: Connected ✓") : "Trakt: " + l("غير متصل", "Disconnected"))
                + "  ·  " + l("الإضافات: ", "Addons: ") + addonsCount
                + "  ·  " + l("الترجمة: ", "Subtitles: ") + (hasSubKey ? l("محفوظة ✓", "Saved ✓") : l("تلقائي مجاني", "Free Auto"))
                + "  ·  TMDb: " + (hasTmdb ? l("محفوظ ✓", "Saved ✓") : l("مفتوح", "Open")));

        iconButton(R.drawable.cinema_ic_updates,
                l("مزامنة من الهاتف الآن", "Sync from Phone Now"),
                CinemaLinkServer.hasOnlineController()
                        ? l("طلب سحب آخر إعدادات Trakt والإضافات والمفاتيح من الهاتف المتصل", "Pull latest Trakt, addons & keys from connected Phone now")
                        : l("الهاتف غير متصل الآن — افتح تطبيق الهاتف ثم أعد المحاولة", "Phone is currently offline — open Phone app to push sync"),
                () -> {
                    boolean requested = CinemaLinkServer.requestPhoneFullSync();
                    CinemaLinkServer.broadcastApiKeysSync(getApplicationContext());
                    if (requested) {
                        android.widget.Toast.makeText(this,
                                l("تم إرسال طلب المزامنة الفورية إلى الهاتف ✓", "Requested full sync from Phone ✓"),
                                android.widget.Toast.LENGTH_SHORT).show();
                        mainHandler.postDelayed(() -> {
                            if (!isFinishing() && !isDestroyed()) phoneSyncStatusPage();
                        }, 1200);
                    } else {
                        android.widget.Toast.makeText(this,
                                l("يرجى فتح تطبيق الهاتف (TV Hub) والاقتران بالشاشة", "Please open Phone app (TV Hub) while connected"),
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });
        iconButton(R.drawable.cinema_ic_trakt,
                l("حالة ومزامنة Trakt", "Trakt Sync Status"),
                traktLinked
                        ? l("متصل من الهاتف ✓", "Synced from Phone ✓")
                        : l("غير متصل — اربطه بضغطة واحدة من تطبيق الهاتف", "Disconnected — link in 1 tap from Phone app"),
                this::traktSettings);
    }

    private void systemGroup(){
        currentPageId="system";homePage=false;backTarget=this::showRoot;currentRender=this::systemGroup;
        page(l("النظام والصلاحيات والتحديثات", "System, Permissions & Updates"));
        note("Local Cinema · Android TV\nMEEDX\n"+com.meedx.updates.AppUpdates.displayVersion(com.archos.mediacenter.video.BuildConfig.VERSION_NAME));
        iconButton(R.drawable.cinema_ic_updates,
                l("التحديثات ومعلومات الإصدار", "Updates & Version"),
                com.meedx.updates.AppUpdates.displayVersion(com.archos.mediacenter.video.BuildConfig.VERSION_NAME),
                ()->com.meedx.updates.AppUpdates.settings(this,true,
                new com.meedx.updates.AppUpdates.NoteLabels(com.archos.mediacenter.video.R.string.cinema_update_patch_notes,
                        com.archos.mediacenter.video.R.string.cinema_update_notes_missing)));
        iconButton(R.drawable.cinema_ic_shield, l("معالج الصلاحيات والخدمات (التخزين والبطارية والريموت)",
                "Permissions & Services Wizard"),
                l("التحقق من إدارة الملفات والبطارية والوصول وحماية TCL وADB المحلي", "Verify All Files Access, Battery, Accessibility, TCL & Local ADB"),
                () -> TvPermissionsWizardDialog.show(this));
        iconButton(R.drawable.cinema_ic_system, getString(R.string.cinema_about_licenses),
                getString(R.string.cinema_open_source_free), this::aboutLicenses);
    }

    private void aboutLicenses() {
        homePage = false;
        backTarget = this::systemGroup;
        currentPageId = "about";
        currentRender = this::aboutLicenses;
        page(getString(R.string.cinema_about_licenses));
        note(getString(R.string.cinema_open_source_free));
        iconButton(R.drawable.cinema_ic_system, getString(R.string.cinema_source_code), "GitHub",
                () -> ProjectLinks.open(this, ProjectLinks.SOURCE, R.string.cinema_project_link_unavailable));
        iconButton(R.drawable.cinema_ic_shield, getString(R.string.cinema_third_party_licenses), "THIRD_PARTY.md",
                () -> ProjectLinks.open(this, ProjectLinks.LICENSES, R.string.cinema_project_link_unavailable));
    }

    private void networkPage(){
        currentPageId="network";homePage=false;backTarget=this::systemGroup;currentRender=this::networkPage;
        page(getString(com.archos.mediacenter.video.R.string.cinema_network));
        iconButton(R.drawable.cinema_network, getString(com.archos.mediacenter.video.R.string.cinema_smb_files_and_network_sources),
                l("تعديل مجلدات الشبكة المتصلة", "Configure SMB & local network shares"), ()->startActivity(new Intent(this,CinemaNetworkActivity.class)));
    }

    private void cinemaEngine(){
        currentPageId="cinema_engine";homePage=false;backTarget=this::playbackAudioGroup;currentRender=this::cinemaEngine;
        page(getString(com.archos.mediacenter.video.R.string.cinema_cinema_engine));
        var profile=CinemaEngineBoundary.inspectHardware(this,
                getWindow().getDecorView().getDisplay());
        note(getString(com.archos.mediacenter.video.R.string.cinema_engine_hardware_summary,
                profile.ramBytes()/1073741824.0,availability(profile.dolbyVision()),availability(profile.hdr10Plus()),
                availability(profile.profile7Decoder()),profile.ultraEligible()?getString(com.archos.mediacenter.video.R.string.cinema_hardware_eligible):getString(com.archos.mediacenter.video.R.string.cinema_below_memory_threshold)));
        String[] modes={getString(com.archos.mediacenter.video.R.string.cinema_off),getString(com.archos.mediacenter.video.R.string.cinema_refresh_rate_only),getString(com.archos.mediacenter.video.R.string.cinema_refresh_rate_and_resolution)};
        iconButton(R.drawable.cinema_play_movie, getString(com.archos.mediacenter.video.R.string.cinema_display_matching),
                l("المختار حالياً: ", "Selected: ") + currentDisplayModeLabel(), ()->
                dialog(this).setItems(modes,(d,n)->{CinemaEngineBoundary.setDisplayMode(this,n);cinemaEngine();}).show());
        String[] delays=new String[11];
        for(int n=0;n<delays.length;n++)delays[n]=String.format(java.util.Locale.US,"%.1f s",n/2.0);
        iconButton(R.drawable.cinema_ic_timer, getString(com.archos.mediacenter.video.R.string.cinema_display_switch_delay),
                l("التأخير الحالي: ", "Current delay: ") + (CinemaEngineBoundary.getDisplaySwitchDelayMs(this)/1000.0)+" s",()->
                dialog(this).setItems(delays,(d,n)->{CinemaEngineBoundary.setDisplaySwitchDelayMs(this,500*n);cinemaEngine();}).show());
        boolean dynAudio = CinemaNovaLegacyPrefs.isDynamicAudioDelayEnabled(this);
        rowButton(R.drawable.cinema_ic_audio, getString(com.archos.mediacenter.video.R.string.cinema_automatic_audio_sync),
                null, dynAudio, () -> {
                    CinemaNovaLegacyPrefs.setDynamicAudioDelayEnabled(this, !dynAudio);
                    cinemaEngine();
                });
        note(profile.displayModesSummary());
    }

    private String currentAppLanguageLabel() {
        String current = CustomApplication.getUiLocale(this);
        if (current.startsWith("ar")) return getString(R.string.locale_ar);
        if (current.startsWith("en")) return getString(R.string.locale_en);
        return getString(R.string.locale_system);
    }

    private String currentMetadataLangLabel() {
        String stored = prefs.getString(MetadataLanguage.PREF, "");
        if ("ar-SA".equals(stored)) return getString(R.string.locale_ar);
        if ("en-US".equals(stored)) return getString(R.string.locale_en);
        return getString(R.string.locale_match);
    }

    private String currentAutoRefreshLabel() {
        int cur = prefs.getInt(CinemaMetadataPrefetcher.PREF_AUTO_REFRESH_DAYS, 0);
        return switch (cur) {
            case 1 -> l("يومياً", "Every Day");
            case 2 -> l("كل يومين", "Every 2 Days");
            case 7 -> l("أسبوعياً", "Every Week");
            case 30 -> l("شهرياً", "Every Month");
            default -> l("معطل", "Off");
        };
    }

    private String currentSubtitleSourceLabel() {
        return switch (SubtitleSources.selected(this)) {
            case 1 -> "OpenSubtitles";
            case 2 -> l("Subsource + OpenSubtitles (كلاهما)", "Both Sources");
            default -> "Subsource + " + l("الترجمة المفتوحة", "Free Auto");
        };
    }

    private String currentBackdropLabel() {
        return "high".equals(prefs.getString(HomePreferences.BACKDROP, "balanced"))
                ? getString(com.archos.mediacenter.video.R.string.cinema_high_1920)
                : getString(com.archos.mediacenter.video.R.string.cinema_balanced_1280);
    }

    private String currentDisplayModeLabel() {
        String[] modes={getString(com.archos.mediacenter.video.R.string.cinema_off),getString(com.archos.mediacenter.video.R.string.cinema_refresh_rate_only),getString(com.archos.mediacenter.video.R.string.cinema_refresh_rate_and_resolution)};
        int mode=CinemaEngineBoundary.getDisplayMode(this);
        return mode>=0&&mode<modes.length?modes[mode]:getString(com.archos.mediacenter.video.R.string.cinema_existing_player_setting);
    }

    private String availability(boolean supported){
        return supported?getString(com.archos.mediacenter.video.R.string.cinema_supported):getString(com.archos.mediacenter.video.R.string.cinema_not_advertised);
    }

    private void appLanguage() {
        String current = CustomApplication.getUiLocale(this);
        String[] tags = {"", "ar", "en"};
        String[] labels = {getString(R.string.locale_system), getString(R.string.locale_ar), getString(R.string.locale_en)};
        int selected = current.startsWith("ar") ? 1 : current.startsWith("en") ? 2 : 0;
        dialog(this).setTitle(R.string.locale_app).setSingleChoiceItems(labels, selected, (d, index) -> {
            d.dismiss();
            String tag = tags[index];
            if (index == selected) return;
            var langEditor = prefs.edit();
            CinemaNovaLegacyPrefs.writeUiLanguage(langEditor, tag);
            langEditor.putString(CinemaPosters.REFRESH, "missing").apply();
            CinemaMetadataPrefetcher.clearLanguageTextCachesOnly(this);
            CinemaMetadataPrefetcher.runAsync(this, false);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.app.LocaleManager manager = getSystemService(android.app.LocaleManager.class);
                if (manager != null) manager.setApplicationLocales(android.os.LocaleList.forLanguageTags(tag));
            } else {
                ((CustomApplication) getApplication()).loadLocale();
            }
            boolean targetArabic = "ar".equals(tag) || (tag.isEmpty() && java.util.Locale.getDefault().getLanguage().equals("ar"));
            String dlgTitle = targetArabic ? "إعادة تشغيل التطبيق" : "Restart Application";
            String dlgMsg = targetArabic
                    ? "تم تغيير لغة التطبيق. يلزم إعادة تشغيل التطبيق الآن لتطبيق اللغة الجديدة واتجاه الواجهة (من اليمين لليسار) بالكامل."
                    : "App language has been updated. Please restart the app now to apply the new language and layout direction completely.";
            String btnRestart = targetArabic ? "إعادة التشغيل الآن" : "Restart Now";
            String btnLater = targetArabic ? "لاحقاً" : "Later";
            dialog(this)
                    .setTitle(dlgTitle)
                    .setMessage(dlgMsg)
                    .setPositiveButton(btnRestart, (rd, rw) -> restartApplicationNow())
                    .setNegativeButton(btnLater, (rd, rw) -> {
                        if (currentRender != null) currentRender.run();
                    })
                    .setCancelable(false)
                    .show();
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void restartApplicationNow() {
        try {
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(getPackageName());
            if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch != null && launch.getComponent() != null) {
                Intent restart = Intent.makeRestartActivityTask(launch.getComponent());
                startActivity(restart);
                finishAffinity();
                Runtime.getRuntime().exit(0);
                return;
            }
        } catch (Exception ignored) {}
        recreate();
    }

    private void traktSettings() {
        currentPageId="trakt";homePage = false;backTarget=this::phoneSyncStatusPage;currentRender=this::traktSettings;
        com.meedx.providers.TraktSyncClient.migrateAndCleanCredentials(this);
        page(label(this, "مزامنة Trakt", "Trakt Sync"));
        boolean connected = com.meedx.providers.TraktSyncClient.isConnected(this);
        String phoneName = getSharedPreferences("cinema_link", 0).getString("last_phone_sync_name", "");
        note(connected
                ? label(this,
                        "الحالة: متصل بـ Trakt ✓"
                                + (phoneName.isBlank() ? "" : " · مزامن من " + phoneName)
                                + "\nالجلسة محفوظة محلياً وتعمل وتُجدد التوكن تلقائياً حتى بدون اتصال الهاتف.",
                        "Status: Synced & Connected ✓"
                                + (phoneName.isBlank() ? "" : " (" + phoneName + ")")
                                + "\nSession persists offline on TV and refreshes tokens automatically.")
                : label(this,
                        "الحالة: غير متصل.\nافتح تطبيق الهاتف ← TV Hub ← تبويب Connection واضغط «ربط حساب Trakt» لتنتقل الجلسة تلقائياً وتُحفظ هنا دائماً.",
                        "Status: Disconnected.\nOpen Phone app -> TV Hub -> Connection and tap Connect Trakt Account. Your session will sync and persist permanently on TV."));
        iconButton(R.drawable.cinema_ic_updates,
                label(this, "مزامنة Trakt الآن", "Sync Trakt Now"),
                connected ? l("مزامنة سجل المشاهدة وقائمة المشاهدة والتقدم الآن", "Push & pull watch history, progress & watchlist now")
                        : l("اربط الحساب من الهاتف أولاً لتفعيل المزامنة", "Link your Trakt account on Phone first"), () -> {
            if (!com.meedx.providers.TraktSyncClient.isConnected(this)) {
                android.widget.Toast.makeText(this,
                        label(this, "اربط Trakt من تطبيق الهاتف أولاً", "Connect Trakt from the Phone app first"),
                        android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            android.widget.Toast.makeText(this,
                    label(this, "جاري المزامنة مع Trakt...", "Syncing with Trakt..."),
                    android.widget.Toast.LENGTH_SHORT).show();
            io.execute(() -> {
                try {
                    int count = CinemaWatchStore.syncWithTrakt(getApplicationContext());
                    mainHandler.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        android.widget.Toast.makeText(this,
                                label(this, "تمت المزامنة: ", "Synced records: ") + count,
                                android.widget.Toast.LENGTH_SHORT).show();
                        traktSettings();
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            android.widget.Toast.makeText(this,
                                    label(this, "تعذّرت مزامنة Trakt: ", "Trakt sync failed: ") + e.getMessage(),
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
        });
        if (!connected) {
            iconButton(R.drawable.cinema_qr,
                    getString(com.archos.mediacenter.video.R.string.cinema_link_phone),
                    l("اقترن بالهاتف لإدارة الربط بضغطة واحدة", "Pair Phone to connect Trakt in one tap"),
                    () -> startActivity(new Intent(this, CinemaPhoneLinkActivity.class)));
        }
        button(label(this, "فصل / إعادة ضبط Trakt على الشاشة", "Reset / Unlink Trakt"), () -> {
            com.meedx.providers.TraktSyncClient.signOut(this);
            com.archos.mediacenter.utils.trakt.Trakt.resetProviderSession(this);
            CinemaLinkServer.broadcastApiKeysSync(this);
            android.widget.Toast.makeText(this,
                    label(this, "تم مسح جلسة Trakt وإعادة ضبط الإعدادات", "Trakt session unlinked & reset"),
                    android.widget.Toast.LENGTH_SHORT).show();
            traktSettings();
        });
    }

    private void addonsSettings() { new CinemaAddonsManagerDialog(this, io, mainHandler, () -> {}).show(); }

    private void metadataSourceRow() {
        String[] options = {label(this, "مصادر مفتوحة تلقائياً · بدون مفتاح", "Automatic open providers · No key"),
                label(this, "مفتاح TMDb خاص", "Personal TMDb API key")};
        boolean open = OpenMetadata.enabled(this);
        iconButton(R.drawable.cinema_ic_posters,
                l("مصدر البيانات والملخصات", "Metadata & Synopsis Source"),
                l("المختار حالياً: ", "Selected: ") + options[open ? 0 : 1], () ->
                dialog(this).setTitle(label(this, "مصدر المعلومات", "Metadata source"))
                        .setSingleChoiceItems(options, open ? 0 : 1, (d, index) -> {
                            prefs.edit().putBoolean(OpenMetadata.PRIVATE_TMDB, index == 1).apply();
                            d.dismiss();
                            metadataPage();
                            if (index == 1) com.meedx.providers.ProviderSettings.edit(this,
                                    CinemaPhoneLink::withPasteBadge, com.meedx.providers.ProviderKeys.TMDB);
                        }).show());
        if (!open) iconButton(R.drawable.cinema_ic_key, l("مفتاح TMDb API", "TMDb API Key"),
                !com.meedx.providers.ProviderKeys.get(this, com.meedx.providers.ProviderKeys.TMDB).isBlank()
                        ? l("محفوظ ✓", "Saved ✓") : l("غير مضبوط", "Not configured"), () ->
                com.meedx.providers.ProviderSettings.edit(this, CinemaPhoneLink::withPasteBadge,
                        com.meedx.providers.ProviderKeys.TMDB, ignored -> metadataPage()));
    }

    private void metadataLanguage() {
        String[] values = {"", "ar-SA", "en-US"};
        String[] labels = {
                l("تلقائي حسب لغة الجهاز / التطبيق", "Auto — Follow Device/App Language"),
                getString(R.string.locale_ar),
                getString(R.string.locale_en)
        };
        int selected = java.util.Arrays.asList(values).indexOf(prefs.getString(MetadataLanguage.PREF, ""));
        dialog(this).setTitle(l("لغة البوسترات والتقييمات", "Posters & Metadata Language"))
                .setSingleChoiceItems(labels, Math.max(0, selected), (d, index) -> {
                    d.dismiss();
                    if (selected == index) return;
                    prefs.edit()
                            .putString(MetadataLanguage.PREF, values[index])
                            .putString(CinemaPosters.REFRESH, "missing")
                            .apply();
                    CinemaMetadataPrefetcher.clearLanguageTextCachesOnly(this);
                    CinemaMetadataPrefetcher.runAsync(this, false);
                    android.widget.Toast.makeText(this,
                            l("جارٍ تحديث البوسترات والملخصات والتقييمات باللغة المختارة...", "Refreshing posters, synopses & ratings in selected language..."),
                            android.widget.Toast.LENGTH_SHORT).show();
                    if (currentRender != null) currentRender.run();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void autoRefreshMetadataRow(Runnable onChanged) {
        int[] daysValues = {0, 1, 2, 7, 30};
        String[] dayLabels = {
                l("معطل", "Off"),
                l("يومياً", "Every Day"),
                l("كل يومين", "Every 2 Days"),
                l("أسبوعياً", "Every Week"),
                l("شهرياً", "Every Month")
        };
        int cur = prefs.getInt(CinemaMetadataPrefetcher.PREF_AUTO_REFRESH_DAYS, 0);
        int selected = 0;
        for (int i = 0; i < daysValues.length; i++) {
            if (daysValues[i] == cur) {
                selected = i;
                break;
            }
        }
        String labelText = l("تحديث تلقائي للبوسترات والبيانات", "Auto-Refresh Metadata & Posters");
        String subText = l("المختار حالياً: ", "Selected: ") + dayLabels[selected];
        final int initIdx = selected;
        iconButton(R.drawable.cinema_ic_timer, labelText, subText, () ->
                dialog(this).setTitle(l("تحديث تلقائي للبوسترات والبيانات", "Auto-Refresh Metadata & Posters"))
                        .setSingleChoiceItems(dayLabels, initIdx, (d, index) -> {
                            d.dismiss();
                            prefs.edit()
                                    .putInt(CinemaMetadataPrefetcher.PREF_AUTO_REFRESH_DAYS, daysValues[index])
                                    .putLong(CinemaMetadataPrefetcher.PREF_LAST_AUTO_REFRESH_MS, System.currentTimeMillis())
                                    .apply();
                            if (onChanged != null) onChanged.run();
                        }).setNegativeButton(android.R.string.cancel, null).show());
    }

    private void appearance(){
        currentPageId="appearance";homePage=false;backTarget=this::showRoot;currentRender=this::appearance;
        page(l("المظهر وتخصيص الشاشة الرئيسية", "Appearance & Home Screen"));

        sectionHeader(l("أقسام الشاشة الرئيسية وأولوية الترتيب", "Home Screen Sections & Priority Order"));
        List<String> available = HomePreferences.defaultAvailableKeys(prefs);
        List<String> sectionOrder = HomePreferences.sectionOrder(prefs, available);
        for (int idx = 0; idx < sectionOrder.size(); idx++) {
            final String key = sectionOrder.get(idx);
            final int pos = idx;
            String title = CinemaHomeFragment.sectionTitle(this, key);
            boolean shown = HomePreferences.isSectionVisible(prefs, key);
            int iconRes = HomePreferences.isTrailerKey(key) ? R.drawable.cinema_play_movie
                    : (HomePreferences.isLibKey(key) ? R.drawable.cinema_folder : R.drawable.cinema_ic_appearance);
            String kindTag = HomePreferences.isTrailerKey(key)
                    ? l("قسم إعلانات", "Trailers Section")
                    : (HomePreferences.isLibKey(key) ? l("مجلد مكتبة", "Library Folder") : l("صف رئيسي", "Home Row"));
            String sub = l("الأولوية #", "Priority #") + (pos + 1) + " · " + kindTag
                    + l(" · اضغط للتحكم بالظهور والترتيب", " · Tap to toggle or reorder");
            rowButton(iconRes, title, sub, shown, () -> showHomeSectionActionsDialog(sectionOrder, key, title, pos, shown));
        }
        iconButton(R.drawable.cinema_ic_updates,
                l("استعادة الترتيب والإعدادات الافتراضية", "Restore Default Order & Layout"),
                l("إرجاع ترتيب الأقسام والبوسترات إلى الوضع الافتراضي", "Reset home sections, visibility & poster layout to default"),
                () -> {
                    HomePreferences.reset(prefs);
                    appearance();
                });

        sectionHeader(l("البوسترات والخلفيات", "Posters & Backdrops"));
        iconButton(R.drawable.cinema_ic_posters, getString(com.archos.mediacenter.video.R.string.cinema_posters_per_row),
                l("العدد الحالي: ", "Selected: ") + prefs.getInt(HomePreferences.COUNT, 6),
                () -> CinemaUi.dialog(this).setTitle(getString(com.archos.mediacenter.video.R.string.cinema_posters_per_row))
                        .setItems(new String[]{"4", "5", "6", "7", "8", "9"}, (d, w) -> {
                            prefs.edit().putInt(HomePreferences.COUNT, w + 4).apply();
                            appearance();
                        }).show());
        iconButton(R.drawable.cinema_ic_posters, getString(com.archos.mediacenter.video.R.string.cinema_poster_size),
                l("الحجم الحالي: ", "Selected: ") + posterSizeLabel(),
                () -> CinemaUi.dialog(this).setTitle(getString(com.archos.mediacenter.video.R.string.cinema_poster_size))
                        .setItems(new String[]{
                                getString(com.archos.mediacenter.video.R.string.cinema_small),
                                getString(com.archos.mediacenter.video.R.string.cinema_medium),
                                getString(com.archos.mediacenter.video.R.string.cinema_large)}, (d, w) -> {
                            prefs.edit().putString(HomePreferences.SIZE, new String[]{"small", "medium", "large"}[w]).apply();
                            appearance();
                        }).show());
        toggle(R.drawable.cinema_play_movie, getString(com.archos.mediacenter.video.R.string.cinema_transitions), HomePreferences.MOTION, true, this::appearance);
        iconButton(R.drawable.cinema_ic_posters, getString(com.archos.mediacenter.video.R.string.cinema_backdrop_quality),
                l("الجودة الحالية: ", "Current quality: ") + currentBackdropLabel(),
                () -> CinemaUi.dialog(this).setTitle(getString(com.archos.mediacenter.video.R.string.cinema_backdrop_quality))
                        .setItems(new String[]{
                                getString(com.archos.mediacenter.video.R.string.cinema_balanced_1280),
                                getString(com.archos.mediacenter.video.R.string.cinema_high_1920)}, (d, w) -> {
                            prefs.edit().putString(HomePreferences.BACKDROP, w == 0 ? "balanced" : "high").apply();
                            appearance();
                        }).show());

        sectionHeader(l("الساعة والطقس", "Clock & Weather"));
        toggle(R.drawable.cinema_ic_timer, l("إظهار الساعة والتاريخ", "Show Clock & Date"), HomePreferences.CLOCK, true, this::appearance);
        iconButton(R.drawable.cinema_ic_timer, l("حجم الساعة", "Clock Size"),
                l("الحجم الحالي: ", "Current size: ") + currentClockSizeLabel(), () -> {
                    String[] vals = {"large", "medium", "small"};
                    String[] names = {
                            l("كبيرة (Large)", "Large"),
                            l("متوسطة (Medium)", "Medium"),
                            l("صغيرة (Small · افتراضي)", "Small (Default)")
                    };
                    int sel = java.util.Arrays.asList(vals).indexOf(prefs.getString(HomePreferences.CLOCK_SIZE, "small"));
                    CinemaUi.dialog(this).setTitle(l("حجم الساعة", "Clock Size"))
                            .setSingleChoiceItems(names, Math.max(0, sel), (d, w) -> {
                                d.dismiss();
                                prefs.edit().putString(HomePreferences.CLOCK_SIZE, vals[w]).apply();
                                appearance();
                            }).setNegativeButton(android.R.string.cancel, null).show();
                });
        iconButton(R.drawable.cinema_ic_appearance, l("شفافية خلفية الساعة", "Clock Background Opacity"),
                l("الشفافية الحالية: ", "Current opacity: ") + currentClockOpacityLabel(), () -> {
                    int[] ops = {0, 25, 50, 75, 100};
                    String[] opNames = {
                            l("شفاف بالكامل (0%)", "Fully Transparent (0%)"),
                            l("شفافية عالية (25%)", "High Transparency (25%)"),
                            l("متوسط (50% · الافتراضي)", "Medium (50% · Default)"),
                            l("داكن (75%)", "Dark (75%)"),
                            l("معتم (100%)", "Opaque (100%)")
                    };
                    int cur = prefs.getInt(HomePreferences.CLOCK_OPACITY, 50);
                    int sel = 2;
                    for (int i = 0; i < ops.length; i++) if (ops[i] == cur) { sel = i; break; }
                    CinemaUi.dialog(this).setTitle(l("شفافية خلفية الساعة", "Clock Background Opacity"))
                            .setSingleChoiceItems(opNames, sel, (d, w) -> {
                                d.dismiss();
                                prefs.edit().putInt(HomePreferences.CLOCK_OPACITY, ops[w]).apply();
                                appearance();
                            }).setNegativeButton(android.R.string.cancel, null).show();
                });
        toggle(R.drawable.cinema_ic_timer, l("إظهار التحية (صباح الخير / مساء الخير)", "Show Greeting (Good Morning / Good Evening)"),
                HomePreferences.CLOCK_GREETING, true, this::appearance);
        toggle(R.drawable.cinema_ic_timer, l("إظهار أيقونة الشمس/القمر والطقس", "Show Sun/Moon & Weather"),
                HomePreferences.CLOCK_WEATHER, true, this::appearance);
    }

    private void showHomeSectionActionsDialog(List<String> order, String key, String title, int index, boolean shown) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();
        labels.add(shown ? l("إخفاء القسم من الشاشة الرئيسية", "Hide from Home Screen")
                : l("إظهار القسم في الشاشة الرئيسية", "Show on Home Screen"));
        actions.add(() -> HomePreferences.setSectionVisible(prefs, key, !shown));
        if (index > 0) {
            labels.add(l("⬆ نقل للأعلى تماماً (الأولوية الأولى)", "⬆ Move to Top (Priority #1)"));
            actions.add(() -> {
                java.util.List<String> updated = new java.util.ArrayList<>(order);
                updated.remove(index);
                updated.add(0, key);
                HomePreferences.saveSectionOrder(prefs, updated);
            });
            labels.add(getString(com.archos.mediacenter.video.R.string.cinema_move_up));
            actions.add(() -> {
                java.util.List<String> updated = new java.util.ArrayList<>(order);
                Collections.swap(updated, index, index - 1);
                HomePreferences.saveSectionOrder(prefs, updated);
            });
        }
        if (index < order.size() - 1) {
            labels.add(getString(com.archos.mediacenter.video.R.string.cinema_move_down));
            actions.add(() -> {
                java.util.List<String> updated = new java.util.ArrayList<>(order);
                Collections.swap(updated, index, index + 1);
                HomePreferences.saveSectionOrder(prefs, updated);
            });
        }
        CinemaUi.dialog(this).setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, w) -> {
                    if (w >= 0 && w < actions.size()) {
                        actions.get(w).run();
                        appearance();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
    private String currentClockSizeLabel() {
        return switch (prefs.getString(HomePreferences.CLOCK_SIZE, "small")) {
            case "large" -> l("كبيرة", "Large");
            case "medium" -> l("متوسطة", "Medium");
            default -> l("صغيرة", "Small");
        };
    }
    private String currentClockOpacityLabel() {
        int op = prefs.getInt(HomePreferences.CLOCK_OPACITY, 50);
        return switch (op) {
            case 0 -> l("شفاف بالكامل (0%)", "Transparent (0%)");
            case 25 -> l("25%", "25%");
            case 75 -> l("75%", "75%");
            case 100 -> l("معتم (100%)", "Opaque (100%)");
            default -> l("متوسط (50%)", "Medium (50%)");
        };
    }
    private void toggle(String title,String key,boolean fallback,Runnable refresh){
        toggle(R.drawable.cinema_ic_system, title, key, fallback, refresh);
    }
    private void toggle(int iconRes, String title, String key, boolean fallback, Runnable refresh){
        boolean on = prefs.getBoolean(key, fallback);
        rowButton(iconRes, title, null, on, ()->{prefs.edit().putBoolean(key,!on).apply();refresh.run();});
    }
    private void remoteSettings() {
        currentPageId="remote";homePage = false;backTarget=this::connectionsGroup;currentRender=this::remoteSettings;
        page(getString(com.archos.mediacenter.video.R.string.cinema_phone_remote));
        var connection = getSharedPreferences("cinema_link", MODE_PRIVATE);
        boolean webRemote = CinemaLinkServer.isWebRemoteEnabled(this);
        rowButton(R.drawable.cinema_ic_remote,
                l("الريموت عبر الويب (Web Remote)", "Web Remote Control (Web)"),
                webRemote
                        ? l("مُفعّل — يستقبل أوامر التنقل واللمس عبر WebSocket فوراً", "ON — Receiving live WebSocket remote commands")
                        : l("متوقف — أزرار الريموت عبر الويب معطّلة", "OFF — WebSocket remote navigation disabled"),
                webRemote, () -> {
                    CinemaLinkServer.setWebRemoteEnabled(this, !webRemote);
                    remoteSettings();
                });
        boolean boot = connection.getBoolean("background_at_boot", true);
        rowButton(R.drawable.cinema_ic_remote,
                getString(com.archos.mediacenter.video.R.string.cinema_start_connection_at_boot),
                l("بدء خادم ربط الهاتف تلقائياً عند تشغيل الشاشة", "Start Phone Link service on TV boot"),
                boot, () -> {
                    connection.edit().putBoolean("background_at_boot", !boot).apply();
                    remoteSettings();
                });
        if (hasTclGuard()) tclGuardButton();
        otherTvProtectionButton();
        boolean accEnabled = TvRemoteAccessibilityService.available();
        rowButton(R.drawable.cinema_ic_shield,
                getString(com.archos.mediacenter.video.R.string.cinema_accessibility_settings),
                accEnabled ? getString(com.archos.mediacenter.video.R.string.cinema_system_wide_remote_control_enabled)
                        : getString(com.archos.mediacenter.video.R.string.cinema_enable_local_cinema_in_accessibility_settings),
                accEnabled,
                () -> openRemoteSystemSettings(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
        localAdbPermissionRow();
        iconButton(R.drawable.cinema_ic_remote,
                getString(com.archos.mediacenter.video.R.string.cinema_enable_remote_keyboard_bridge),
                l("السماح بالكتابة من كيبورد الهاتف داخل الشاشة", "Allow typing from Phone keyboard onto TV"),
                () -> openRemoteSystemSettings(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS));
        iconButton(R.drawable.cinema_ic_remote,
                getString(com.archos.mediacenter.video.R.string.cinema_choose_tv_keyboard),
                l("تحديد لوحة مفاتيح Local Cinema كإدخال افتراضي", "Select Local Cinema IME input method"),
                () -> getSystemService(android.view.inputmethod.InputMethodManager.class).showInputMethodPicker());
    }

    private void localAdbPermissionRow() {
        boolean adbOk = TvLocalAdbBridge.isReady();
        rowButton(R.drawable.cinema_ic_remote,
                l("تفعيل ADB المحلي (التحكم الهجين بالنظام)", "Enable Local ADB Bridge (Hybrid Remote)"),
                l("يتيح التحكم الكامل بالنظام والتطبيقات الخارجية مثل SmartTube واللانشر، وإبقاء صلاحيات الريموت مفعّلة تلقائياً بعد التحديثات.",
                        "Enables full system & external app control (SmartTube, custom launchers) and keeps remote permissions enabled after updates."),
                adbOk,
                () -> TvRemotePermissionKeeper.showRemoteManagerDialog(this, () -> {
                    if (currentRender != null && !isFinishing() && !isDestroyed()) {
                        runOnUiThread(currentRender);
                    }
                }));
    }

    private void otherTvProtectionButton() {
        boolean batteryOk = false;
        try {
            var pm = getSystemService(android.os.PowerManager.class);
            batteryOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception ignored) {}
        rowButton(R.drawable.cinema_ic_shield,
                l("منع السكون وحماية الخلفية (لبقية الشاشات: Sony, Xiaomi, Hisense, Google TV)",
                        "Sleep & Background Protection (Other TVs: Sony, Xiaomi, Hisense, Google TV)"),
                l("استثناء توفير الطاقة وإعدادات التشغيل التلقائي لنظام Android TV",
                        "Battery unrestricted mode & Autostart setup for non-TCL TVs"),
                batteryOk,
                this::showOtherTvProtectionDialog);
    }

    private void showOtherTvProtectionDialog() {
        String message = l(
                "لضمان بقاء ربط الهاتف والمزامنة نشطين دون إيقاف من النظام على شاشات Android TV / Google TV:\n\n"
                        + "1. استثناء توفير الطاقة: اختر Local Cinema واضبطه على «عدم التحسين» أو «غير مقيد» (Don't optimize / Unrestricted).\n\n"
                        + "2. إعدادات التطبيق والتشغيل التلقائي: في شاشات Xiaomi وMi Box فعّل «التشغيل التلقائي (Autostart)»، وفي Sony وHisense وGoogle TV راجع الأذونات وحالة البطارية.",
                "Keep Phone Link and sync active on Sony, Xiaomi, Hisense, or Google TV:\n\n"
                        + "1. Battery Optimization: select Local Cinema and choose \"Don't optimize\" / \"Unrestricted\".\n\n"
                        + "2. App Info & Autostart: enable Autostart on Xiaomi/Mi Box or check App battery & background permissions on Sony/Hisense/Google TV.");
        dialog(this)
                .setTitle(l("حماية الخلفية لبقية الشاشات", "Other TVs Background Protection"))
                .setMessage(message)
                .setNeutralButton(l("1. إعدادات توفير الطاقة", "1. Battery Settings"), (d, w) -> requestSystemBatteryExemption())
                .setPositiveButton(l("2. إعدادات التطبيق والتشغيل التلقائي", "2. App Info / Autostart"), (d, w) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        openRemoteSystemSettings(android.provider.Settings.ACTION_SETTINGS);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void requestSystemBatteryExemption() {
        try {
            Intent direct = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName()));
            if (direct.resolveActivity(getPackageManager()) != null) {
                startActivity(direct);
                return;
            }
        } catch (Exception ignored) {}
        openRemoteSystemSettings(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
    }

    private boolean hasTclGuard() {
        try {
            getPackageManager().getPackageInfo("com.tcl.guard", 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private void tclGuardButton() {
        iconButton(R.drawable.cinema_ic_shield,
                getString(com.archos.mediacenter.video.R.string.cinema_tcl_guard_title),
                getString(com.archos.mediacenter.video.R.string.cinema_tcl_guard_summary),
                this::showTclGuardDialog);
    }

    private void showTclGuardDialog() {
        dialog(this)
                .setTitle(com.archos.mediacenter.video.R.string.cinema_tcl_guard_title)
                .setMessage(com.archos.mediacenter.video.R.string.cinema_tcl_guard_steps)
                .setPositiveButton(com.archos.mediacenter.video.R.string.cinema_tcl_guard_open, (d, w) -> openTclGuard())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openTclGuard() {
        try {
            var pm = getPackageManager();
            Intent intent = pm.getLeanbackLaunchIntentForPackage("com.tcl.guard");
            if (intent == null) intent = pm.getLaunchIntentForPackage("com.tcl.guard");
            if (intent != null) {
                startActivity(intent);
                return;
            }
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:com.tcl.guard")));
        } catch (Exception e) {
            openRemoteSystemSettings(android.provider.Settings.ACTION_SETTINGS);
        }
    }

    private void openRemoteSystemSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (android.content.ActivityNotFoundException error) {
            android.widget.Toast.makeText(this, getString(com.archos.mediacenter.video.R.string.cinema_this_setting_is_unavailable_on_this_tv), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void posterSettings() {
        currentPageId="posters";homePage = false;backTarget=this::libraryAndPostersGroup;currentRender=this::posterSettings;
        page(getString(com.archos.mediacenter.video.R.string.cinema_refresh_posters));
        autoRefreshMetadataRow(this::posterSettings);
        iconButton(R.drawable.cinema_ic_posters, getString(com.archos.mediacenter.video.R.string.cinema_fetch_missing_posters),
                l("تنزيل البوسترات الناقصة فقط دون مسح الكاش", "Download missing posters without clearing cache"),
                () -> requestPosterRefresh("missing"));
        iconButton(R.drawable.cinema_ic_updates, l("تحديث البوسترات والبيانات يدوياً الآن", "Refresh Posters & Metadata Now"),
                l("إعادة تحميل جميع البوسترات والسير والتقييمات", "Force reload all posters, cast & ratings"), () ->
                CinemaUi.dialog(this).setTitle(l("تحديث البوسترات والبيانات يدوياً الآن", "Refresh Posters & Metadata Now"))
                        .setMessage(getString(com.archos.mediacenter.video.R.string.cinema_library_posters_will_be_downloaded_again_from_tmdb))
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> requestPosterRefresh("all"))
                        .setNegativeButton(android.R.string.cancel, null).show());
    }

    private void requestPosterRefresh(String mode) {
        if ("all".equals(mode)) {
            CinemaMetadataPrefetcher.clearAllMetadataCaches(this);
            prefs.edit()
                    .putString(CinemaPosters.REFRESH, mode)
                    .putLong(CinemaMetadataPrefetcher.PREF_LAST_AUTO_REFRESH_MS, System.currentTimeMillis())
                    .apply();
            CinemaMetadataPrefetcher.runAsync(this, true);
        } else {
            prefs.edit().putString(CinemaPosters.REFRESH, mode).apply();
            CinemaMetadataPrefetcher.runAsync(this, false);
        }
        finish();
    }

    private void home(){
        appearance();
    }
    private String posterSizeLabel(){return switch(prefs.getString(HomePreferences.SIZE,"medium")){case "small"->getString(com.archos.mediacenter.video.R.string.cinema_small);case "large"->getString(com.archos.mediacenter.video.R.string.cinema_large);default->getString(com.archos.mediacenter.video.R.string.cinema_medium);};}
    @Override public void onBackPressed(){saveCurrentFocusState();if(homePage){appearance();return;}if(backTarget!=null){Runnable r=backTarget;backTarget=null;r.run();return;}if(!rootPage){showRoot();return;}super.onBackPressed();}
}

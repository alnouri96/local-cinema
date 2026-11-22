package org.proninyaroslav.libretorrent.cinema_hub;

import com.meedx.providers.LiveChannelFavorites;
import androidx.preference.PreferenceManager;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.zxing.integration.android.IntentIntegrator;

final class TvHubConnectionController {
    final TvHubActivity host;

    TextView addonsSummaryView;
    TextView addonsLiveStatusView;
    TextView traktStatusView;
    TextView traktLiveStatusView;
    TextView subsourceStatusView;
    TextView openSubStatusView;
    TextView tmdbStatusView;
    TextView tvConnectionSummaryView;
    TextView lastSyncAckView;
    MaterialButton syncAllToTvBtn;
    LinearLayout addonsInlineContainer;
    MaterialButton traktConnectBtn;
    MaterialButton traktCodeConnectBtn;
    MaterialButton traktSyncBtn;
    MaterialButton traktDisconnectBtn;

    private static final String ADDONS_EXPANDED = "connection_addons_expanded";
    private TextView favoriteChannelsSummaryView;
    String lastRenderedAddonsSignature;
    String lastRenderedGooglePhotoUrl = "__UNSET__";
    String lastOpenedTraktDeviceCode = "";
    long lastFullSyncAckAt = 0L;
    String lastFullSyncAckSummary = "";
    long lastHandledTraktAuthSyncMs = 0L;
    final com.meedx.providers.TraktSyncClient.DeviceAuthListener traktDeviceListener = this::onTraktDeviceAuthSnapshot;

    LinearLayout clipboardBannerCard;
    LinearLayout pairingCardRef;
    TextView clipboardBannerUrlView;
    String detectedClipboardUrl;
    String dismissedClipboardUrl;

    android.widget.ImageView googleAvatarView;
    TextView googleNameView;
    TextView googleAccountStatusView;
    MaterialButton googleUploadBtn;
    MaterialButton googleRestoreBtn;
    MaterialButton googleSignOutBtn;
    TextView backupLiveStatusView;
    String pendingUnifiedBackupJson;
    static final int CLOUD_SIGN_IN_CHECK = 0;
    static final int CLOUD_UPLOAD = 1;
    static final int CLOUD_RESTORE = 2;
    int pendingCloudMode = CLOUD_SIGN_IN_CHECK;
    volatile java.util.concurrent.CompletableFuture<org.json.JSONObject> pendingTvBackupFuture;

    TvHubConnectionController(TvHubActivity host) {
        this.host = host;
    }

    void subscribeTrakt() {
        com.meedx.providers.TraktSyncClient.subscribeDeviceAuth(traktDeviceListener);
    }

    void unsubscribeTrakt() {
        com.meedx.providers.TraktSyncClient.unsubscribeDeviceAuth(traktDeviceListener);
    }

    void onResume() {
        com.meedx.providers.TraktSyncClient.migrateAndCleanCredentials(host);
        com.meedx.providers.TraktSyncClient.triggerImmediateDevicePoll();
        var authSnap = com.meedx.providers.TraktSyncClient.getDeviceAuthSnapshot();
        if (authSnap != null && authSnap.state != com.meedx.providers.TraktSyncClient.DeviceAuthState.IDLE) {
            onTraktDeviceAuthSnapshot(authSnap);
        }
        // Strict sync rule: no automatic sync on resume (prevents flicker & redundant requests).
        refreshAddonsAndTraktCard();
        host.main.post(this::checkSmartClipboard);
    }

    void onConnectedChanged(boolean value) {
        if (pairingCardRef != null && pairingCardRef.getBackground() instanceof CinemaGlassUi.GlowingGlassDrawable gd) {
            gd.setAccent(value ? CinemaGlassUi.Accent.CONNECTION_ONLINE : CinemaGlassUi.Accent.CONNECTION_OFFLINE);
        }
        refreshConnectionSummaryUi();
    }

    void onFavoriteChannels(org.json.JSONObject state) {
        if (LiveChannelFavorites.merge(host, state)) refreshFavoriteChannelsSummary();
    }

    private void refreshFavoriteChannelsSummary() {
        if (favoriteChannelsSummaryView == null) return;
        int count = LiveChannelFavorites.active(host).size();
        favoriteChannelsSummaryView.setText(host.getString(
                org.proninyaroslav.libretorrent.R.string.cinema_tv_favorite_channels_count, count));
        favoriteChannelsSummaryView.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
    }

    void onFullSyncAck(org.json.JSONObject response) {
        onFavoriteChannels(response.optJSONObject(LiveChannelFavorites.FIELD));
        lastFullSyncAckAt = response.optLong("synced_at", System.currentTimeMillis());
        boolean traktConnected = response.optBoolean("trakt_connected") || !response.optString("trakt_username").isBlank();
        int ackAddons = response.optInt("addons_count", 0);
        lastFullSyncAckSummary = host.l(
                "الشاشة استلمت المزامنة ✓ · الإضافات: " + ackAddons
                        + (traktConnected ? " · Trakt: Connected ✓" : ""),
                "TV confirmed sync ✓ · Addons: " + ackAddons
                        + (traktConnected ? " · Trakt: Connected ✓" : ""));
        String syncedCountry = response.optString(com.meedx.providers.LiveTvPreferences.COUNTRY_FIELD);
        if (!syncedCountry.isBlank()) {
            lastFullSyncAckSummary += " · " + host.getString(org.proninyaroslav.libretorrent.R.string.cinema_sync_tv_confirmed_country,
                    com.meedx.providers.LiveTvPreferences.countryName(host, syncedCountry));
        }
        refreshAddonsAndTraktCard();
    }

    void onTvBackupData(org.json.JSONObject response) {
        LocalCinemaBackupManager.cacheTvBackup(host, response);
        var fut = pendingTvBackupFuture;
        if (fut != null) fut.complete(response);
    }

    void onTvBackupRestored(org.json.JSONObject response) {
        if (response.optBoolean("ok", false)) {
            LocalCinemaBackupManager.clearPendingTvRestore(host);
            setBackupLiveStatus(host.l("تم استرجاع وتطبيق إعدادات النسخة على الشاشة بنجاح ✓",
                    "TV backup settings restored & applied on TV ✓"), false);
        } else {
            setBackupLiveStatus(host.l("تعذّر تطبيق بعض إعدادات الشاشة: ",
                    "TV restore error: ") + response.optString("error", ""), true);
        }
    }

    void onGoogleConsentResult(androidx.activity.result.ActivityResult res) {
        if (res.getResultCode() == android.app.Activity.RESULT_OK) {
            var acct = LocalCinemaBackupManager.getSignedInGoogleAccount(host);
            if (acct != null) {
                if (pendingCloudMode == CLOUD_SIGN_IN_CHECK) {
                    checkCloudBackupAfterSignIn(acct);
                } else {
                    executeGoogleDriveTransfer(acct, pendingCloudMode == CLOUD_UPLOAD);
                }
            }
        } else if (pendingCloudMode == CLOUD_SIGN_IN_CHECK) {
            refreshAddonsAndTraktCard();
            setBackupLiveStatus(host.l("تم تسجيل الدخول ✓ — يمكنك الاستعادة أو الرفع يدوياً في أي وقت.",
                    "Signed in ✓ — you can restore or upload manually anytime."), false);
        } else {
            triggerGoogleDrivePickerFallback(pendingCloudMode == CLOUD_UPLOAD);
        }
    }

    void scanTv() {
        if (host.scanningPairing) return;
        host.pendingQrScanLaunch = false;
        host.scanningPairing = true;
        host.suppressNextResumeReconnect = true;
        host.main.removeCallbacks(host.reconnect);
        new IntentIntegrator(host).setCaptureActivity(TvQrScannerActivity.class)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setBeepEnabled(false).initiateScan();
    }

    void enterPairingCode() {
        var code = new EditText(host);
        code.setHint(host.l("ألصق رمز الاقتران (JSON) من الشاشة", "Paste the TV pairing JSON"));
        code.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new androidx.appcompat.app.AlertDialog.Builder(host).setTitle(host.getString(org.proninyaroslav.libretorrent.R.string.cinema_enter_pairing_code))
                .setView(code).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(host.l("اتصال", "Connect"), (dialog, which) -> host.connect(code.getText().toString(), true))
                .show();
    }

    void buildStream(LinearLayout page) {
        // Smart Clipboard Addon Banner (hidden until an addon manifest URL is copied)
        clipboardBannerCard = host.card(page, host.l("تم اكتشاف رابط إضافة في الحافظة", "Addon URL Detected in Clipboard"));
        clipboardBannerCard.setVisibility(View.GONE);
        clipboardBannerUrlView = host.label("", 12);
        clipboardBannerUrlView.setTextColor(TvHubActivity.GOLD);
        clipboardBannerCard.addView(clipboardBannerUrlView);
        clipboardBannerCard.addView(host.label(host.l(
                "تم اكتشاف رابط إضافة في الحافظة — اضغط لتثبيته الآن ومزامنته مع الشاشة.",
                "An addon manifest URL was detected in your clipboard — tap to install and sync with TV."), 13));
        var clipBtns = host.row(clipboardBannerCard);
        var installClipBtn = host.button(host.l("تثبيت الآن", "Install Now"), () -> {
            String u = detectedClipboardUrl;
            if (u != null && !u.isEmpty()) installAddonUrl(u);
        }, true);
        clipBtns.addView(installClipBtn, new LinearLayout.LayoutParams(0, -2, 1));
        var dismissClipBtn = host.button(host.l("تجاهل", "Dismiss"), () -> {
            dismissedClipboardUrl = detectedClipboardUrl;
            clipboardBannerCard.setVisibility(View.GONE);
        }, false);
        var dLp = new LinearLayout.LayoutParams(-2, -2);
        dLp.setMarginStart(host.dp(8));
        clipBtns.addView(dismissClipBtn, dLp);

        // Card 1: Connected TV & Pairing
        var pairingCard = host.card(page, host.l("1. الشاشة والاقتران", "1. TV & Pairing"),
                null, host.connected ? CinemaGlassUi.Accent.CONNECTION_ONLINE : CinemaGlassUi.Accent.CONNECTION_OFFLINE);
        pairingCardRef = pairingCard;
        tvConnectionSummaryView = host.label("", 13);
        pairingCard.addView(tvConnectionSummaryView);
        lastSyncAckView = host.label("", 12);
        lastSyncAckView.setTextColor(0xff9de0b8);
        lastSyncAckView.setVisibility(View.GONE);
        var ackLp = new LinearLayout.LayoutParams(-1, -2);
        ackLp.topMargin = host.dp(4);
        pairingCard.addView(lastSyncAckView, ackLp);
        favoriteChannelsSummaryView = host.label("", 12);
        pairingCard.addView(favoriteChannelsSummaryView);
        refreshFavoriteChannelsSummary();
        var pairRow = host.row(pairingCard);
        var qrBtn = host.button(host.getString(org.proninyaroslav.libretorrent.R.string.cinema_scan_tv_qr), this::scanTv, true);
        qrBtn.setSingleLine(true);
        pairRow.addView(qrBtn, new LinearLayout.LayoutParams(0, host.dp(48), 1));
        var codeBtn = host.button(host.getString(org.proninyaroslav.libretorrent.R.string.cinema_pair_code), this::enterPairingCode, false);
        codeBtn.setSingleLine(true);
        var codeLp = new LinearLayout.LayoutParams(0, host.dp(48), 1);
        codeLp.setMarginStart(host.dp(8));
        pairRow.addView(codeBtn, codeLp);
        syncAllToTvBtn = host.button(host.l("مزامنة الآن", "Sync Now"),
                () -> sendFullSyncStateToTv(true), true);
        var syncAllLp = new LinearLayout.LayoutParams(-1, -2);
        syncAllLp.topMargin = host.dp(8);
        pairingCard.addView(syncAllToTvBtn, syncAllLp);
        // Card 2: Trakt Account & Sync
        var traktCard = host.card(page, host.l("2. حساب ومزامنة Trakt", "2. Trakt Account & Sync"),
                null, CinemaGlassUi.Accent.TRAKT);
        traktStatusView = host.label("", 14);
        traktCard.addView(traktStatusView);
        traktLiveStatusView = createLiveStatusBanner();
        var tLiveLp = new LinearLayout.LayoutParams(-1, -2);
        tLiveLp.topMargin = host.dp(8);
        traktCard.addView(traktLiveStatusView, tLiveLp);
        traktConnectBtn = host.button(host.l("الربط عبر المتصفح", "Connect via Browser"),
                this::startTraktDeviceConnect, true);
        var b1Lp = new LinearLayout.LayoutParams(-1, -2);
        b1Lp.topMargin = host.dp(8);
        traktCard.addView(traktConnectBtn, b1Lp);
        traktCodeConnectBtn = host.button(host.l("الربط برمز التفعيل (Code)", "Connect with Code"),
                this::startTraktCodeConnect, false);
        var b2Lp = new LinearLayout.LayoutParams(-1, -2);
        b2Lp.topMargin = host.dp(6);
        traktCard.addView(traktCodeConnectBtn, b2Lp);
        var traktActionsRow = host.row(traktCard);
        traktSyncBtn = host.button(host.l("مزامنة الآن", "Sync Now"), () -> syncTraktNowAsync(false), true);
        traktActionsRow.addView(traktSyncBtn, new LinearLayout.LayoutParams(0, -2, 1));
        traktDisconnectBtn = host.button(host.l("فصل الحساب", "Disconnect"), () -> {
            com.meedx.providers.TraktSyncClient.signOut(host);
            sendFullSyncStateToTv(false);
            setTraktLiveStatus(host.l("تم فصل حساب Trakt ومزامنة الشاشة ✓", "Trakt disconnected & synced to TV ✓"), false);
            refreshAddonsAndTraktCard();
        }, false);
        var discLp = new LinearLayout.LayoutParams(0, -2, 1);
        discLp.setMarginStart(host.dp(8));
        traktActionsRow.addView(traktDisconnectBtn, discLp);

        // Card 3: Subsource & TMDb API Keys
        var apiKeysCard = host.card(page, host.l("3. مفاتيح Subsource و TMDb والترجمة", "3. Subsource & TMDb API Keys"));
        apiKeysCard.addView(host.label(host.l(
                "البوسترات والترجمة المجانية تعمل تلقائياً بدون حساب؛ أي مفتاح تحفظه هنا يُرسل للشاشة فوراً.",
                "Open posters & free subtitles work automatically. Optional API keys saved here sync straight to TV."), 12));
        subsourceStatusView = host.label("", 13);
        var ssStLp = new LinearLayout.LayoutParams(-1, -2);
        ssStLp.topMargin = host.dp(6);
        apiKeysCard.addView(subsourceStatusView, ssStLp);
        var ssBtnLp = new LinearLayout.LayoutParams(-1, -2);
        ssBtnLp.topMargin = host.dp(4);
        apiKeysCard.addView(host.button(host.l("مفتاح Subsource API", "Subsource API Key"),
                () -> promptSingleApiKey(
                        host.l("مفتاح Subsource API", "Subsource API Key"),
                        host.l("ألصق مفتاح Subsource هنا", "Paste Subsource API Key"),
                        com.meedx.providers.ProviderKeys.SUBSOURCE,
                        "https://subsource.net/"), false), ssBtnLp);

        openSubStatusView = host.label("", 13);
        var osStLp = new LinearLayout.LayoutParams(-1, -2);
        osStLp.topMargin = host.dp(8);
        apiKeysCard.addView(openSubStatusView, osStLp);
        var osBtnLp = new LinearLayout.LayoutParams(-1, -2);
        osBtnLp.topMargin = host.dp(4);
        apiKeysCard.addView(host.button(host.l("مفتاح OpenSubtitles API (اختياري)", "OpenSubtitles API Key (Optional)"),
                () -> promptSingleApiKey(
                        host.l("مفتاح OpenSubtitles API", "OpenSubtitles API Key"),
                        host.l("ألصق مفتاح OpenSubtitles هنا", "Paste OpenSubtitles API Key"),
                        com.meedx.providers.ProviderKeys.OPEN_SUBTITLES,
                        "https://www.opensubtitles.com/en/consumers"), false), osBtnLp);

        tmdbStatusView = host.label("", 13);
        var tmStLp = new LinearLayout.LayoutParams(-1, -2);
        tmStLp.topMargin = host.dp(8);
        apiKeysCard.addView(tmdbStatusView, tmStLp);
        var tmBtnLp = new LinearLayout.LayoutParams(-1, -2);
        tmBtnLp.topMargin = host.dp(4);
        apiKeysCard.addView(host.button(host.l("مفتاح TMDb API (v3)", "TMDb API Key (v3)"),
                () -> promptSingleApiKey(
                        host.l("مفتاح TMDb API (v3)", "TMDb API Key (v3)"),
                        host.l("ألصق مفتاح TMDb هنا", "Paste TMDb API Key"),
                        com.meedx.providers.ProviderKeys.TMDB,
                        "https://www.themoviedb.org/settings/api"), false), tmBtnLp);

        // Card 4: External Addons (Stremio / Nuvio)
        var addonsCard = host.card(page, host.l("4. الإضافات الخارجية (Stremio / Nuvio)", "4. External Addons (Stremio / Nuvio)"),
                null, CinemaGlassUi.Accent.ADDONS);
        var addonsHeading = host.row(addonsCard);
        addonsHeading.setGravity(Gravity.CENTER_VERTICAL);
        addonsSummaryView = host.label("", 14);
        addonsHeading.addView(addonsSummaryView, new LinearLayout.LayoutParams(0, -2, 1));
        var toggle = new android.widget.ImageButton(host);
        toggle.setImageResource(org.proninyaroslav.libretorrent.R.drawable.ic_addon_disclosure);
        toggle.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        toggle.setBackground(CinemaGlassUi.glassPill(host, CinemaGlassUi.Accent.SUBTLE, 11));
        var toggleSize = new LinearLayout.LayoutParams(host.dp(48), host.dp(48));
        toggleSize.setMarginStart(host.dp(4));
        addonsHeading.addView(toggle, toggleSize);
        addonsLiveStatusView = createLiveStatusBanner();
        var aLiveLp = new LinearLayout.LayoutParams(-1, -2);
        aLiveLp.topMargin = host.dp(8);
        addonsCard.addView(addonsLiveStatusView, aLiveLp);
        addonsInlineContainer = host.column();
        var inlLp = new LinearLayout.LayoutParams(-1, -2);
        inlLp.topMargin = host.dp(8);
        addonsCard.addView(addonsInlineContainer, inlLp);
        lastRenderedAddonsSignature = null;
        var disclosurePrefs = PreferenceManager.getDefaultSharedPreferences(host);
        boolean expanded = disclosurePrefs.getBoolean(ADDONS_EXPANDED, false);
        showInstalledAddons(toggle, expanded);
        toggle.setOnClickListener(v -> {
            boolean show = addonsInlineContainer.getVisibility() != View.VISIBLE;
            disclosurePrefs.edit().putBoolean(ADDONS_EXPANDED, show).apply();
            android.transition.TransitionManager.beginDelayedTransition(addonsCard,
                    new android.transition.AutoTransition().setDuration(180));
            showInstalledAddons(toggle, show);
            if (show) refreshAddonsAndTraktCard();
        });
        var a1Lp = new LinearLayout.LayoutParams(-1, -2);
        a1Lp.topMargin = host.dp(8);
        addonsCard.addView(host.button(host.getString(org.proninyaroslav.libretorrent.R.string.addon_curated),
                this::promptCuratedAddons, true), a1Lp);
        var addonToolsRow = host.row(addonsCard);
        addonToolsRow.addView(host.button(host.getString(org.proninyaroslav.libretorrent.R.string.addon_search_stream), this::promptAddonStreamSearch, false),
                new LinearLayout.LayoutParams(0, -2, 1));
        var pasteLp = new LinearLayout.LayoutParams(0, -2, 1);
        pasteLp.setMarginStart(host.dp(8));
        addonToolsRow.addView(host.button(host.getString(org.proninyaroslav.libretorrent.R.string.addon_paste_url), this::promptAddAddon, false), pasteLp);
        var addonDirRow = host.row(addonsCard);
        addonDirRow.addView(host.button(host.l("دليل Stremio Addons", "Stremio Directory"),
                () -> host.openBrowser("https://stremio-addons.net/"), false),
                new LinearLayout.LayoutParams(0, -2, 1));

        // Card 5: Phone Permissions Wizard
        var permCard = host.card(page, host.l("5. معالج صلاحيات الهاتف", "5. Phone Permissions Wizard"));
        permCard.addView(host.label(host.l(
                "تأكّد من صلاحيات التخزين، العرض فوق التطبيقات، واستثناء البطارية لضمان ثبات البث المباشر والريموت العائم.",
                "Verify storage, floating remote overlay, notifications, and battery optimization for rock-solid casting."), 13));
        var permBtnLp = new LinearLayout.LayoutParams(-1, -2);
        permBtnLp.topMargin = host.dp(8);
        permCard.addView(host.button(host.l("فتح معالج الصلاحيات والأذونات", "Open Permissions Wizard"),
                () -> PhonePermissionsWizardDialog.show(host), false), permBtnLp);

        refreshAddonsAndTraktCard();
    }

    void sendWatchSyncSnapshot() {
        host.watchSyncIo.execute(() -> {
            try {
                var records = PhoneWatchStore.exportRecentSyncRecords(host, 100);
                var ws = host.socket;
                if (records.length() > 0 && ws != null && ws.isOpen()) {
                    ws.send(new org.json.JSONObject().put("type", "WATCH_SYNC").put("records", records).toString());
                }
            } catch (Exception ignored) {}
        });
    }

    void sendWatchSyncRecord(PhoneWatchStore.Entry entry) {
        if (entry == null) return;
        host.watchSyncIo.execute(() -> {
            try {
                var obj = PhoneWatchStore.entryToJson(entry);
                var ws = host.socket;
                if (obj != null && ws != null && ws.isOpen()) {
                    ws.send(new org.json.JSONObject().put("type", "WATCH_SYNC")
                            .put("records", new org.json.JSONArray().put(obj)).toString());
                }
            } catch (Exception ignored) {}
        });
    }

    void handleWatchSync(org.json.JSONObject msg) {
        if (msg == null) return;
        var records = msg.optJSONArray("records");
        var wl = msg.optJSONArray("watchlist");
        if ((records == null || records.length() == 0) && wl == null) return;
        host.watchSyncIo.execute(() -> {
            boolean anyChanged = false;
            if (records != null) {
                for (int i = 0; i < records.length(); i++) {
                    var rec = records.optJSONObject(i);
                    if (rec != null && PhoneWatchStore.applyRemoteSyncRecord(host, rec)) {
                        anyChanged = true;
                    }
                }
            }
            if (wl != null) {
                PhoneWatchStore.saveWatchlist(host, wl);
                anyChanged = true;
            }
            if (anyChanged) {
                host.main.post(() -> {
                    if (!host.isDestroyed()) host.library.refreshIfReady();
                });
            }
        });
    }

    String pairedTvIp() {
        try {
            String raw = host.getSharedPreferences("cinema_link", 0).getString("pairing", null);
            if (raw != null && !raw.isBlank()) {
                var json = new org.json.JSONObject(raw);
                String ip = json.optString("ip", json.optString("tv_ip", "")).trim();
                if (!ip.isEmpty()) return ip;
            }
        } catch (Exception ignored) {}
        return "";
    }

    void refreshConnectionSummaryUi() {
        if (tvConnectionSummaryView != null) {
            String ip = pairedTvIp();
            if (host.connected || CinemaRemoteBridge.getConnectedSocket() != null) {
                String tvLabel = (host.connectedTvName == null || host.connectedTvName.isBlank()) ? "TV" : host.connectedTvName;
                tvConnectionSummaryView.setText(host.l("✓ متصل بـ: ", "✓ Connected: ") + tvLabel
                        + (ip.isEmpty() ? "" : " (" + ip + ")"));
                tvConnectionSummaryView.setTextColor(0xff9de0b8);
            } else if (host.isReconnecting()) {
                tvConnectionSummaryView.setText(host.getString(org.proninyaroslav.libretorrent.R.string.cinema_connecting_to_tv));
                tvConnectionSummaryView.setTextColor(0xffe5a93b);
            } else if (!ip.isEmpty()) {
                tvConnectionSummaryView.setText(host.l("غير متصل · آخر شاشة مقترنة: ", "Disconnected · Paired IP: ") + ip);
                tvConnectionSummaryView.setTextColor(0xffd7d2ca);
            } else {
                tvConnectionSummaryView.setText(host.l("غير متصل بشاشة بعد · امسح QR أو أدخل الرمز",
                        "Not connected to a TV yet · Scan QR or enter pairing code"));
                tvConnectionSummaryView.setTextColor(0xffd7d2ca);
            }
        }
        if (lastSyncAckView != null) {
            if (lastFullSyncAckAt > 0 && !lastFullSyncAckSummary.isBlank()) {
                String t = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                        .format(new java.util.Date(lastFullSyncAckAt));
                lastSyncAckView.setText(lastFullSyncAckSummary + " (" + t + ")");
                lastSyncAckView.setVisibility(View.VISIBLE);
            } else {
                lastSyncAckView.setVisibility(View.GONE);
            }
        }
    }

    private void showInstalledAddons(android.widget.ImageButton toggle, boolean expanded) {
        int label = expanded ? org.proninyaroslav.libretorrent.R.string.addon_collapse_installed
                : org.proninyaroslav.libretorrent.R.string.addon_expand_installed;
        toggle.setContentDescription(host.getString(label));
        toggle.animate().rotation(expanded ? 180f : 0f).setDuration(150).start();
        addonsInlineContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    void refreshAddonsAndTraktCard() {
        refreshConnectionSummaryUi();
        if (addonsSummaryView != null) {
            var list = com.meedx.providers.StremioAddons.list(host);
            long enabled = list.stream().filter(a -> a.enabled).count();
            addonsSummaryView.setText(list.isEmpty()
                    ? host.l("لا توجد إضافات مضافة بعد. أضف رابط manifest.json.", "No addons installed yet. Add a Stremio / Nuvio manifest.json URL.")
                    : host.l(enabled + " / " + list.size() + " مفعّلة (تُزامن مع الشاشة)", enabled + " / " + list.size() + " enabled (synced with TV)"));
            if (addonsInlineContainer != null && addonsInlineContainer.getVisibility() == View.VISIBLE) {
                StringBuilder sigBuilder = new StringBuilder();
                for (var a : list) {
                    sigBuilder.append(a.manifestUrl).append('|').append(a.enabled).append('|').append(a.name).append('|').append(a.resources).append(';');
                }
                String sig = sigBuilder.toString();
                if (!sig.equals(lastRenderedAddonsSignature)) {
                    lastRenderedAddonsSignature = sig;
                    addonsInlineContainer.removeAllViews();
                    var ordered = new java.util.ArrayList<>(list);
                    ordered.sort(java.util.Comparator.comparing(
                            com.meedx.providers.StremioAddons::subtitleOnly));
                    Boolean subtitleGroup = null;
                    for (var addon : ordered) {
                        boolean subtitle = com.meedx.providers.StremioAddons.subtitleOnly(addon);
                        if (subtitleGroup == null || subtitleGroup != subtitle) {
                            subtitleGroup = subtitle;
                            var group = host.label(host.getString(subtitle
                                    ? org.proninyaroslav.libretorrent.R.string.addon_group_subtitles
                                    : org.proninyaroslav.libretorrent.R.string.addon_group_streaming), 13);
                            group.setTextColor(0xffe5a93b);
                            group.setPadding(0, host.dp(10), 0, 0);
                            addonsInlineContainer.addView(group);
                        }
                        var r = new LinearLayout(host);
                        r.setOrientation(LinearLayout.HORIZONTAL);
                        r.setGravity(Gravity.CENTER_VERTICAL);
                        r.setPadding(host.dp(10), host.dp(8), host.dp(8), host.dp(8));
                        r.setBackground(CinemaGlassUi.glassPill(host,
                                addon.enabled ? CinemaGlassUi.Accent.ADDONS : CinemaGlassUi.Accent.SUBTLE, 12));
                        var rLp = new LinearLayout.LayoutParams(-1, -2);
                        rLp.topMargin = host.dp(6);
                        addonsInlineContainer.addView(r, rLp);

                        var col = host.column();
                        String name = (addon.name == null || addon.name.isBlank()) ? addon.id : addon.name;
                        var nameTv = host.label((addon.enabled ? "● " : "○ ") + name, 13);
                        nameTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                        nameTv.setTextColor(addon.enabled ? 0xffefe7d6 : 0xff9a958d);
                        col.addView(nameTv);
                        var urlTv = host.label(com.meedx.providers.StremioAddons.localizedDescription(addon.description, host), 11);
                        urlTv.setSingleLine(true);
                        urlTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        urlTv.setTextColor(0xff8e8981);
                        col.addView(urlTv);
                        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

                        var sw = new MaterialSwitch(host);
                        sw.setChecked(addon.enabled);
                        sw.setOnCheckedChangeListener((b, isChecked) -> {
                            com.meedx.providers.StremioAddons.setEnabled(host, addon.manifestUrl, isChecked);
                            sendAddonsSyncSnapshot();
                            refreshAddonsAndTraktCard();
                        });
                        r.addView(sw);

                        var delBtn = host.button("✕", () -> {
                            com.meedx.providers.StremioAddons.remove(host, addon.manifestUrl);
                            sendAddonsSyncSnapshot();
                            setAddonsLiveStatus(host.getString(org.proninyaroslav.libretorrent.R.string.addon_removed), false);
                            refreshAddonsAndTraktCard();
                        }, false);
                        var delLp = new LinearLayout.LayoutParams(host.dp(44), host.dp(40));
                        delLp.setMarginStart(host.dp(6));
                        r.addView(delBtn, delLp);
                    }
                }
            }
        }
        if (traktStatusView != null) {
            boolean linked = com.meedx.providers.TraktSyncClient.isConnected(host);
            traktStatusView.setText(linked
                    ? host.l("متصل ✓",
                            "Connected ✓")
                    : host.l("غير متصل · اختر الربط عبر المتصفح أو برمز التفعيل",
                            "Disconnected · Connect via Browser or with Code"));
            traktStatusView.setTextColor(linked ? 0xff9de0b8 : 0xffefe7d6);
            if (traktConnectBtn != null) {
                traktConnectBtn.setEnabled(true);
                traktConnectBtn.setVisibility(linked ? View.GONE : View.VISIBLE);
            }
            if (traktCodeConnectBtn != null) {
                traktCodeConnectBtn.setEnabled(true);
                traktCodeConnectBtn.setVisibility(linked ? View.GONE : View.VISIBLE);
            }
            if (traktSyncBtn != null) {
                traktSyncBtn.setVisibility(linked ? View.VISIBLE : View.GONE);
                traktSyncBtn.setEnabled(linked);
            }
            if (traktDisconnectBtn != null) traktDisconnectBtn.setVisibility(linked ? View.VISIBLE : View.GONE);
        }
        if (subsourceStatusView != null) {
            boolean hasSub = !com.meedx.providers.ProviderKeys.get(host, com.meedx.providers.ProviderKeys.SUBSOURCE).isBlank();
            subsourceStatusView.setText(hasSub
                    ? host.l("Subsource API: محفوظ ويُزامن للشاشة ✓", "Subsource API: Saved & synced to TV ✓")
                    : host.l("Subsource API: غير مضبوط (الترجمة المجانية تعمل)", "Subsource API: Not set (free auto subtitles active)"));
        }
        if (openSubStatusView != null) {
            boolean hasOs = !com.meedx.providers.ProviderKeys.get(host, com.meedx.providers.ProviderKeys.OPEN_SUBTITLES).isBlank();
            openSubStatusView.setText(hasOs
                    ? host.l("OpenSubtitles API: محفوظ ويُزامن للشاشة ✓", "OpenSubtitles API: Saved & synced to TV ✓")
                    : host.l("OpenSubtitles API: غير مضبوط (اختياري)", "OpenSubtitles API: Not configured (optional)"));
        }
        if (tmdbStatusView != null) {
            boolean hasTmdb = !com.meedx.providers.ProviderKeys.get(host, com.meedx.providers.ProviderKeys.TMDB).isBlank();
            tmdbStatusView.setText(hasTmdb
                    ? host.l("TMDb API: محفوظ ويُزامن للشاشة ✓", "TMDb API: Saved & synced to TV ✓")
                    : host.l("TMDb API: المصادر المفتوحة مفعّلة افتراضياً", "TMDb API: Open zero-config metadata active"));
        }
        if (googleAccountStatusView != null) {
            var acct = LocalCinemaBackupManager.getSignedInGoogleAccount(host);
            boolean hasPendingTv = LocalCinemaBackupManager.loadPendingTvRestore(host) != null;
            if (googleAvatarView != null) {
                String photoKey = acct != null && acct.getPhotoUrl() != null ? acct.getPhotoUrl().toString() : "";
                if (!photoKey.equals(lastRenderedGooglePhotoUrl)) {
                    lastRenderedGooglePhotoUrl = photoKey;
                    var glideReq = !photoKey.isEmpty()
                            ? com.bumptech.glide.Glide.with(host).load(acct.getPhotoUrl())
                            : com.bumptech.glide.Glide.with(host).load(org.proninyaroslav.libretorrent.R.drawable.ic_person_account_24);
                    glideReq.circleCrop()
                            .placeholder(org.proninyaroslav.libretorrent.R.drawable.ic_person_account_24)
                            .error(org.proninyaroslav.libretorrent.R.drawable.ic_person_account_24)
                            .into(googleAvatarView);
                }
            }
            if (googleNameView != null) {
                String name = acct != null && acct.getDisplayName() != null && !acct.getDisplayName().isBlank()
                        ? acct.getDisplayName()
                        : (acct != null && acct.getEmail() != null ? acct.getEmail()
                        : host.l("حساب Google غير مرتبط", "Google Account Not Signed In"));
                googleNameView.setText(name);
                googleNameView.setTextColor(acct != null ? TvHubActivity.GOLD : 0xfff0f0f2);
            }
            String acctLine = acct != null && acct.getEmail() != null
                    ? acct.getEmail() + host.l(" · متصل ✓", " · Connected ✓")
                    : host.l("سجّل الدخول لحفظ واسترجاع النسخة الشاملة على Google Drive.",
                        "Sign in to back up & restore Phone + TV settings on Google Drive.");
            if (hasPendingTv) {
                acctLine += "\n" + host.l("تنبيه: توجد نسخة شاشة بانتظار الإطلاق فور اتصال الشاشة.",
                        "Pending: TV restore queued for next TV connection.");
            }
            googleAccountStatusView.setText(acctLine);
            googleAccountStatusView.setTextColor(acct != null ? 0xff9de0b8 : 0xffd7d2ca);
            if (googleUploadBtn != null) {
                googleUploadBtn.setText(acct != null
                        ? host.l("رفع النسخة الآن إلى Google (الهاتف + الشاشة)", "Upload Backup Now (Phone + TV)")
                        : host.l("تسجيل الدخول بحساب Google", "Sign In with Google"));
            }
            if (googleSignOutBtn != null) {
                googleSignOutBtn.setVisibility(acct != null ? View.VISIBLE : View.GONE);
            }
        }
    }

    void signOutGoogleAccount() {
        try {
            LocalCinemaBackupManager.buildGoogleSignInClient(host)
                    .signOut()
                    .addOnCompleteListener(task -> {
                        if (!host.isDestroyed()) {
                            refreshAddonsAndTraktCard();
                            String msg = host.l("تم تسجيل الخروج من حساب Google ✓", "Signed out of Google account ✓");
                            setBackupLiveStatus(msg, false);
                            host.notifyStatus(msg);
                        }
                    });
        } catch (Exception e) {
            setBackupLiveStatus(host.l("تعذّر تسجيل الخروج: ", "Sign-out failed: ") + e.getMessage(), true);
        }
    }

    void promptSingleApiKey(String title, String hint, String keyName) {
        promptSingleApiKey(title, hint, keyName, null);
    }

    void promptSingleApiKey(String title, String hint, String keyName, String websiteUrl) {
        var box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(host.dp(16), host.dp(8), host.dp(16), 0);
        if (websiteUrl != null && !websiteUrl.isBlank()) {
            var openBtn = host.button(host.l("فتح الموقع للحصول على المفتاح", "Open Website to Get Key"),
                    () -> host.openBrowser(websiteUrl), false);
            var obLp = new LinearLayout.LayoutParams(-1, -2);
            obLp.bottomMargin = host.dp(8);
            box.addView(openBtn, obLp);
        }
        var input = new EditText(host);
        input.setHint(hint);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String existing = com.meedx.providers.ProviderKeys.get(host, keyName);
        if (existing != null) input.setText(existing);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        new androidx.appcompat.app.AlertDialog.Builder(host)
                .setTitle(title)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(host.l("مسح", "Clear"), (d, w) -> {
                    com.meedx.providers.ProviderKeys.save(host, keyName, "");
                    sendFullSyncStateToTv(false);
                    host.notifyStatus(host.l("تم مسح المفتاح ومزامنة الشاشة ✓", "Key cleared & synced to TV ✓"));
                    refreshAddonsAndTraktCard();
                })
                .setPositiveButton(host.l("حفظ ومزامنة للشاشة", "Save & Sync to TV"), (d, w) -> {
                    String val = input.getText().toString().trim();
                    com.meedx.providers.ProviderKeys.save(host, keyName, val);
                    sendFullSyncStateToTv(false);
                    host.notifyStatus(host.connected
                            ? host.l("تم الحفظ والإرسال للشاشة تلقائياً ✓", "Saved & synced to TV automatically ✓")
                            : host.l("تم الحفظ محلياً (سيُرسل للشاشة عند الاتصال)", "Saved locally (will sync on TV connect)"));
                    refreshAddonsAndTraktCard();
                }).show();
    }

    boolean isFullSyncDirty() {
        return host.getSharedPreferences("cinema_link", Context.MODE_PRIVATE).getBoolean("full_sync_dirty", false);
    }

    void markFullSyncDirty(boolean dirty) {
        host.getSharedPreferences("cinema_link", Context.MODE_PRIVATE).edit().putBoolean("full_sync_dirty", dirty).apply();
    }

    /** Pushes only locally-pending Trakt changes (no pull, no TV broadcast, no UI refresh). */
    void pushPendingTraktOnlyAsync() {
        if (!com.meedx.providers.TraktSyncClient.isConnected(host)) return;
        host.watchSyncIo.execute(() -> {
            try {
                var pending = PhoneWatchStore.listPendingTraktItems(host);
                if (pending == null || pending.isEmpty()) return;
                var pushed = com.meedx.providers.TraktSyncClient.pushPending(host, pending);
                PhoneWatchStore.clearPendingTraktSync(host, pushed);
            } catch (Exception ignored) {}
        });
    }

    boolean sendFullSyncStateBlocking() {
        return sendFullSyncStateBlocking(false);
    }

    private boolean sendFullSyncStateBlocking(boolean userTriggered) {
        try {
            var ws = host.socket;
            if (ws == null || !ws.isOpen()) { markFullSyncDirty(true); return false; }
            var payload = LocalCinemaBackupManager.buildFullSyncStateJson(host.getApplicationContext());
            if (payload.length() == 0) return false;
            if (userTriggered && com.meedx.providers.LiveTvPreferences.store(host)
                    .contains(com.meedx.providers.LiveTvPreferences.COUNTRY_FIELD)) {
                payload.put(com.meedx.providers.LiveTvPreferences.COUNTRY_FIELD,
                        com.meedx.providers.LiveTvPreferences.country(host));
            }
            if (userTriggered) payload.put(LiveChannelFavorites.FIELD, LiveChannelFavorites.snapshot(host));
            ws.send(payload.toString());
            markFullSyncDirty(false);
            return true;
        } catch (Exception ignored) {
            markFullSyncDirty(true);
            return false;
        }
    }

    void sendFullSyncStateToTv(boolean userTriggered) {
        if (userTriggered && (!host.connected || host.socket == null || !host.socket.isOpen())) {
            host.notifyStatus(host.l("يرجى الاتصال بالشاشة أولاً عبر QR أو رمز الاقتران.", "Connect to TV first via QR or pairing code."));
            return;
        }
        if (userTriggered) {
            host.notifyStatus(host.l("جارٍ إرسال حزمة المزامنة الكاملة للشاشة...", "Sending full sync state to TV..."));
        }
        host.watchSyncIo.execute(() -> sendFullSyncStateBlocking(userTriggered));
    }

    void showLiveCountrySync() {
        if (host.isFinishing() || host.isDestroyed()) return;
        String[] codes = com.meedx.providers.LiveTvPreferences.countryCodes(host);
        String[] labels = java.util.Arrays.stream(codes)
                .map(code -> com.meedx.providers.LiveTvPreferences.countryName(host, code)).toArray(String[]::new);
        int initial = java.util.Arrays.asList(codes).indexOf(com.meedx.providers.LiveTvPreferences.country(host));
        var dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
                .setTitle(org.proninyaroslav.libretorrent.R.string.cinema_sync_tv_country)
                .setSingleChoiceItems(labels, initial, (d, which) -> {})
                .setPositiveButton(org.proninyaroslav.libretorrent.R.string.cinema_sync_tv_apply, (d, button) -> {
                    int selected = ((androidx.appcompat.app.AlertDialog) d).getListView().getCheckedItemPosition();
                    if (selected >= 0) {
                        com.meedx.providers.LiveTvPreferences.saveCountry(host, codes[selected]);
                        sendFullSyncStateToTv(true);
                    }
                }).setNegativeButton(android.R.string.cancel, null);
        dialog.show();
    }

    void sendApiKeysSyncSnapshot(boolean traktSignout) {
        sendFullSyncStateToTv(false);
    }

    void sendAddonsSyncSnapshot() {
        if (!host.connected || host.socket == null || !host.socket.isOpen()) return;
        try {
            host.socket.send(new org.json.JSONObject().put("type", "ADDONS_SYNC")
                    .put("authoritative", true).put("addons", com.meedx.providers.StremioAddons.exportJson(host)).toString());
        } catch (Exception ignored) {}
    }

    void onAddonsAck(int count) {
        setAddonsLiveStatus(host.getString(org.proninyaroslav.libretorrent.R.string.addon_synced, count), false);
    }

    void onTvAddonEnabled(String url, boolean enabled) {
        com.meedx.providers.StremioAddons.setEnabled(host, url, enabled);
        refreshAddonsAndTraktCard();
    }

    void handleAddonsSync(org.json.JSONArray addons) {
        if (addons == null) return;
        host.watchSyncIo.execute(() -> {
            if (com.meedx.providers.StremioAddons.mergeRemoteJson(host, addons)) {
                host.main.post(() -> {
                    if (!host.isDestroyed()) refreshAddonsAndTraktCard();
                });
            }
        });
    }

    void handleApiKeysSyncFromTv(org.json.JSONObject msg) {
        if (msg == null) return;
        host.watchSyncIo.execute(() -> {
            boolean changed = false;
            boolean traktChanged = false;
            com.meedx.providers.TraktSyncClient.migrateAndCleanCredentials(host);
            String tvTid = com.meedx.providers.TraktSyncClient.normalizeClientId(msg.optString("trakt_id", ""));
            String localTid = com.meedx.providers.ProviderKeys.get(host, com.meedx.providers.ProviderKeys.TRAKT_ID);
            if (!tvTid.isEmpty() && localTid.isBlank()) {
                com.meedx.providers.ProviderKeys.save(host, com.meedx.providers.ProviderKeys.TRAKT_ID, tvTid);
                localTid = tvTid;
                changed = true;
                traktChanged = true;
            }
            String tvTsec = msg.optString("trakt_secret", "").trim();
            if (!tvTsec.isEmpty()
                    && !com.meedx.providers.TraktSyncClient.DEFAULT_PUBLIC_CLIENT_ID.equals(localTid)
                    && com.meedx.providers.ProviderKeys.get(host, com.meedx.providers.ProviderKeys.TRAKT_SECRET).isBlank()) {
                com.meedx.providers.ProviderKeys.save(host, com.meedx.providers.ProviderKeys.TRAKT_SECRET, tvTsec);
                changed = true;
                traktChanged = true;
            }
            String tvAcc = msg.optString("trakt_access_token", "").trim();
            String localAcc = com.meedx.providers.ProviderKeys.get(host, com.meedx.providers.ProviderKeys.TRAKT_ACCESS_TOKEN);
            if (!tvAcc.isEmpty() && localAcc.isBlank() && (tvTid.isEmpty() || tvTid.equals(localTid) || localTid.isBlank())) {
                com.meedx.providers.ProviderKeys.save(host, com.meedx.providers.ProviderKeys.TRAKT_ACCESS_TOKEN, tvAcc);
                String tvRef = msg.optString("trakt_refresh_token", "").trim();
                if (!tvRef.isEmpty()) {
                    com.meedx.providers.ProviderKeys.save(host, com.meedx.providers.ProviderKeys.TRAKT_REFRESH_TOKEN, tvRef);
                }
                String tvExp = msg.optString("trakt_expires_at", "").trim();
                if (!tvExp.isEmpty()) {
                    com.meedx.providers.ProviderKeys.save(host, com.meedx.providers.ProviderKeys.TRAKT_EXPIRES_AT, tvExp);
                }
                changed = true;
                traktChanged = true;
            }
            String tvUser = msg.optString("trakt_username", "").trim();
            if (!tvUser.isEmpty() && com.meedx.providers.ProviderKeys.get(host, com.meedx.providers.ProviderKeys.TRAKT_USERNAME).isBlank()) {
                com.meedx.providers.ProviderKeys.save(host, com.meedx.providers.ProviderKeys.TRAKT_USERNAME, tvUser);
                changed = true;
                traktChanged = true;
            }
            String tvSlug = msg.optString("trakt_slug", "").trim();
            if (!tvSlug.isEmpty() && com.meedx.providers.ProviderKeys.get(host, com.meedx.providers.ProviderKeys.TRAKT_SLUG).isBlank()) {
                com.meedx.providers.ProviderKeys.save(host, com.meedx.providers.ProviderKeys.TRAKT_SLUG, tvSlug);
                changed = true;
            }
            String tvSub = msg.optString("subsource_key", "").trim();
            if (!tvSub.isEmpty() && com.meedx.providers.ProviderKeys.get(host, com.meedx.providers.ProviderKeys.SUBSOURCE).isBlank()) {
                com.meedx.providers.ProviderKeys.save(host, com.meedx.providers.ProviderKeys.SUBSOURCE, tvSub);
                changed = true;
            }
            String tvOs = msg.optString("opensubtitles_key", "").trim();
            if (!tvOs.isEmpty() && com.meedx.providers.ProviderKeys.get(host, com.meedx.providers.ProviderKeys.OPEN_SUBTITLES).isBlank()) {
                com.meedx.providers.ProviderKeys.save(host, com.meedx.providers.ProviderKeys.OPEN_SUBTITLES, tvOs);
                changed = true;
            }
            String tvTmdb = msg.optString("tmdb_key", "").trim();
            if (!tvTmdb.isEmpty() && com.meedx.providers.ProviderKeys.get(host, com.meedx.providers.ProviderKeys.TMDB).isBlank()) {
                com.meedx.providers.ProviderKeys.save(host, com.meedx.providers.ProviderKeys.TMDB, tvTmdb);
                changed = true;
            }
            com.meedx.providers.TraktSyncClient.migrateAndCleanCredentials(host);
            if (changed) {
                boolean notifyTrakt = traktChanged;
                host.main.post(() -> {
                    if (host.isDestroyed()) return;
                    refreshAddonsAndTraktCard();
                    if (notifyTrakt) {
                        setTraktLiveStatus(host.l("تمت مزامنة إعدادات وحساب Trakt تلقائياً من الشاشة ✓",
                                "Synced Trakt account & keys automatically from TV ✓"), false);
                        if (com.meedx.providers.TraktSyncClient.isConnected(host)) {
                            syncTraktNowAsync(true);
                        }
                    }
                });
            }
        });
    }

    void setBackupLiveStatus(String text, boolean isError) {
        if (backupLiveStatusView != null) {
            updateBanner(backupLiveStatusView, text, isError);
        } else if (text != null && !text.isBlank()) {
            host.notifyStatus(text);
        }
    }

    void flushPendingTvRestore() {
        host.watchSyncIo.execute(() -> {
            var pending = LocalCinemaBackupManager.loadPendingTvRestore(host);
            var ws = host.socket;
            if (pending == null || ws == null || !ws.isOpen()) return;
            try {
                ws.send(pending.toString());
                host.main.post(() -> {
                    if (!host.isDestroyed()) {
                        setBackupLiveStatus(host.l("تم إرسال النسخة الاحتياطية المنتظرة إلى الشاشة تلقائياً ✓",
                                "Queued backup restore automatically pushed to TV ✓"), false);
                        refreshAddonsAndTraktCard();
                    }
                });
            } catch (Exception ignored) {}
        });
    }

    void collectUnifiedBackupAsync(java.util.function.Consumer<String> onJsonReady) {
        boolean tvConnected = host.connected && host.socket != null && host.socket.isOpen();
        setBackupLiveStatus(tvConnected
                ? host.l("جارٍ جمع إعدادات الشاشة والهاتف لإنشاء النسخة الموحّدة...",
                    "Gathering live TV & Phone settings into a unified backup...")
                : host.l("الشاشة غير متصلة حالياً — جارٍ تحضير نسخة الهاتف + آخر نسخة محفوظة من الشاشة...",
                    "TV disconnected — preparing Phone backup + last cached TV settings..."), false);
        host.watchSyncIo.execute(() -> {
            org.json.JSONObject liveTv = null;
            var ws = host.socket;
            if (ws != null && ws.isOpen()) {
                var future = new java.util.concurrent.CompletableFuture<org.json.JSONObject>();
                pendingTvBackupFuture = future;
                try {
                    ws.send(new org.json.JSONObject().put("type", "EXPORT_TV_BACKUP").toString());
                    liveTv = future.get(5500, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                } finally {
                    pendingTvBackupFuture = null;
                }
            }
            try {
                org.json.JSONObject unified = LocalCinemaBackupManager.buildUnifiedBackup(host, liveTv);
                String formatted = unified.toString(2);
                boolean includedLive = unified.optBoolean("includes_live_tv", false);
                boolean includedCached = unified.optBoolean("includes_cached_tv", false);
                host.main.post(() -> {
                    if (host.isDestroyed()) return;
                    if (!includedLive && !includedCached) {
                        setBackupLiveStatus(host.l("تم تجهيز نسخة الهاتف (لم تتصل الشاشة بعد — اتصل بها لاحقاً لتضمين إعداداتها).",
                                "Phone backup ready (TV hasn't been synced yet — connect TV to include its settings too)."), false);
                    } else if (!includedLive) {
                        setBackupLiveStatus(host.l("الشاشة غير متصلة الآن — تم دمج إعدادات الهاتف مع آخر نسخة محفوظة للشاشة.",
                                "TV is currently offline — combined Phone data with last cached TV snapshot."), false);
                    }
                    onJsonReady.accept(formatted);
                });
            } catch (Exception e) {
                host.main.post(() -> {
                    if (!host.isDestroyed()) {
                        setBackupLiveStatus(host.l("تعذّر تجهيز النسخة الاحتياطية:\n",
                                "Failed to prepare backup:\n") + readableError(e), true);
                    }
                });
            }
        });
    }

    void startLocalBackupExport() {
        collectUnifiedBackupAsync(json -> {
            pendingUnifiedBackupJson = json;
            host.launchBackupCreateLocal(LocalCinemaBackupManager.defaultLocalFilename());
        });
    }

    void onLocalBackupSaveUri(android.net.Uri uri) {
        if (uri == null || pendingUnifiedBackupJson == null) return;
        String payload = pendingUnifiedBackupJson;
        host.watchSyncIo.execute(() -> {
            try {
                LocalCinemaBackupManager.writeUriText(host, uri, payload);
                pendingUnifiedBackupJson = null;
                host.main.post(() -> {
                    if (!host.isDestroyed()) {
                        setBackupLiveStatus(host.l("تم حفظ النسخة الاحتياطية الموحّدة (الهاتف + الشاشة) بنجاح ✓",
                                "Unified backup (Phone + TV) saved successfully ✓"), false);
                    }
                });
            } catch (Exception e) {
                host.main.post(() -> {
                    if (!host.isDestroyed()) {
                        setBackupLiveStatus(host.l("فشل حفظ ملف النسخة الاحتياطية:\n",
                                "Failed to write backup file:\n") + readableError(e), true);
                    }
                });
            }
        });
    }

    void startLocalBackupRestore() {
        host.launchBackupOpenLocal(new String[]{"application/json", "text/plain", "*/*"});
    }

    void onLocalBackupOpenUri(android.net.Uri uri) {
        if (uri == null) return;
        setBackupLiveStatus(host.l("جارٍ قراءة واسترجاع النسخة الاحتياطية...", "Reading and restoring backup file..."), false);
        host.watchSyncIo.execute(() -> {
            try {
                String content = LocalCinemaBackupManager.readUriText(host, uri);
                applyUnifiedRestoreJsonBlocking(content);
            } catch (Exception e) {
                host.main.post(() -> {
                    if (!host.isDestroyed()) {
                        setBackupLiveStatus(host.l("فشل استرجاع النسخة الاحتياطية:\n",
                                "Failed to restore backup:\n") + readableError(e), true);
                    }
                });
            }
        });
    }

    void applyUnifiedRestoreJsonBlocking(String content) throws Exception {
        var result = LocalCinemaBackupManager.restoreUnifiedBackup(host, content);
        var ws = host.socket;
        boolean pushedNow = false;
        if (result.tvRestoreCommand != null) {
            LocalCinemaBackupManager.savePendingTvRestore(host, result.tvRestoreCommand);
            if (ws != null && ws.isOpen()) {
                ws.send(result.tvRestoreCommand.toString());
                pushedNow = true;
            }
        }
        boolean finalPushed = pushedNow;
        host.main.post(() -> {
            if (host.isDestroyed()) return;
            refreshAddonsAndTraktCard();
            host.library.refreshIfReady();
            String msg;
            if (finalPushed) {
                msg = host.l("تم استرجاع بيانات الهاتف وإرسال إعدادات النسخة إلى الشاشة فوراً ✓",
                        "Phone restored & TV backup pushed immediately to connected TV ✓");
            } else {
                msg = host.l("تم استرجاع بيانات الهاتف بنجاح ✓\nحُفظت إعدادات الشاشة في قائمة الانتظار وستنطلق تلقائياً فور اتصالك بالتلفاز.",
                        "Phone restored ✓\nTV restore queued in pending_tv_restore and will push automatically on next TV connect.");
            }
            setBackupLiveStatus(msg, false);
        });
    }

    void onGooglePrimaryButtonClicked() {
        var existing = LocalCinemaBackupManager.getSignedInGoogleAccount(host);
        if (existing == null || existing.getAccount() == null) {
            startGoogleSignInCheck();
        } else {
            startGoogleCloudAction(true);
        }
    }

    void startGoogleSignInCheck() {
        pendingCloudMode = CLOUD_SIGN_IN_CHECK;
        var existing = LocalCinemaBackupManager.getSignedInGoogleAccount(host);
        if (existing != null && existing.getAccount() != null) {
            checkCloudBackupAfterSignIn(existing);
            return;
        }
        setBackupLiveStatus(host.l("جارٍ تحضير تسجيل الدخول بحساب Google...", "Preparing Google Sign-In..."), false);
        host.watchSyncIo.execute(() -> {
            LocalCinemaBackupManager.resolveGoogleClientId(host);
            host.main.post(() -> {
                if (host.isDestroyed()) return;
                refreshAddonsAndTraktCard();
                try {
                    var client = LocalCinemaBackupManager.buildGoogleSignInClient(host);
                    host.launchGoogleSignIn(client.getSignInIntent());
                } catch (Exception e) {
                    setBackupLiveStatus(host.l("تعذّر فتح شاشة تسجيل الدخول: ", "Unable to launch Google Sign-In: ")
                            + e.getMessage(), true);
                }
            });
        });
    }

    void startGoogleCloudAction(boolean upload) {
        pendingCloudMode = upload ? CLOUD_UPLOAD : CLOUD_RESTORE;
        var existing = LocalCinemaBackupManager.getSignedInGoogleAccount(host);
        if (existing != null && existing.getAccount() != null) {
            executeGoogleDriveTransfer(existing, upload);
            return;
        }
        setBackupLiveStatus(host.l("جارٍ تحضير الاتصال بحساب Google Drive...", "Preparing Google Drive sign-in..."), false);
        host.watchSyncIo.execute(() -> {
            LocalCinemaBackupManager.resolveGoogleClientId(host);
            host.main.post(() -> {
                if (host.isDestroyed()) return;
                refreshAddonsAndTraktCard();
                try {
                    var client = LocalCinemaBackupManager.buildGoogleSignInClient(host);
                    host.launchGoogleSignIn(client.getSignInIntent());
                } catch (Exception e) {
                    triggerGoogleDrivePickerFallback(upload);
                }
            });
        });
    }

    void onGoogleSignInResult(androidx.activity.result.ActivityResult res) {
        try {
            var task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(res.getData());
            var acct = task.getResult(com.google.android.gms.common.api.ApiException.class);
            if (acct != null) {
                refreshAddonsAndTraktCard();
                if (pendingCloudMode == CLOUD_SIGN_IN_CHECK) {
                    checkCloudBackupAfterSignIn(acct);
                } else {
                    executeGoogleDriveTransfer(acct, pendingCloudMode == CLOUD_UPLOAD);
                }
                return;
            }
        } catch (com.google.android.gms.common.api.ApiException apiEx) {
            if (apiEx.getStatusCode() == com.google.android.gms.common.api.CommonStatusCodes.CANCELED) {
                setBackupLiveStatus(host.l("تم إلغاء تسجيل الدخول بحساب Google.", "Google sign-in cancelled."), false);
                return;
            }
        } catch (Exception ignored) {}
        if (pendingCloudMode == CLOUD_SIGN_IN_CHECK) {
            setBackupLiveStatus(host.l("تعذّر إتمام تسجيل الدخول بحساب Google.",
                    "Google sign-in did not complete."), true);
            return;
        }
        triggerGoogleDrivePickerFallback(pendingCloudMode == CLOUD_UPLOAD);
    }

    void checkCloudBackupAfterSignIn(com.google.android.gms.auth.api.signin.GoogleSignInAccount account) {
        if (account == null || host.isDestroyed()) return;
        refreshAddonsAndTraktCard();
        setBackupLiveStatus(host.l("تم تسجيل الدخول (" + account.getEmail() + ") — جارٍ فحص وجود نسخة سحابية دون الكتابة فوق أي بيانات...",
                "Signed in (" + account.getEmail() + ") — checking for existing cloud backup without auto-uploading..."), false);
        host.watchSyncIo.execute(() -> {
            try {
                var check = LocalCinemaBackupManager.inspectGoogleDriveBackup(host, account);
                host.main.post(() -> {
                    if (host.isDestroyed()) return;
                    if (!check.exists || check.rawJson == null) {
                        setBackupLiveStatus(host.l("تم تسجيل الدخول ✓ — لا توجد نسخة سحابية محفوظة بعد. تم الإبقاء على بياناتك وصفحاتك الحالية.",
                                "Signed in ✓ — no cloud backup saved yet. Your current data & saved sites were preserved."), false);
                        refreshAddonsAndTraktCard();
                        return;
                    }
                    String details = host.l(
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
                    new androidx.appcompat.app.AlertDialog.Builder(host)
                            .setTitle(host.l("تم العثور على نسخة احتياطية سحابية", "Cloud Backup Found"))
                            .setMessage(details)
                            .setPositiveButton(host.l("استعادة الإعدادات والصفحات", "Restore Settings & Saved Sites"), (d, w) -> {
                                setBackupLiveStatus(host.l("جارٍ استعادة الإعدادات والصفحات المحفوظة من Google Drive...",
                                        "Restoring settings & saved sites from Google Drive..."), false);
                                host.watchSyncIo.execute(() -> {
                                    try {
                                        applyUnifiedRestoreJsonBlocking(check.rawJson);
                                    } catch (Exception err) {
                                        host.main.post(() -> {
                                            if (!host.isDestroyed()) setBackupLiveStatus(host.l("تعذّر الاستعادة: ", "Restore failed: ") + err.getMessage(), true);
                                        });
                                    }
                                });
                            })
                            .setNegativeButton(host.l("الإبقاء على البيانات الحالية", "Keep Current Data"), (d, w) -> {
                                setBackupLiveStatus(host.l("تم تسجيل الدخول ✓ — تم الإبقاء على بياناتك الحالية دون تغيير.",
                                        "Signed in ✓ — kept current data unchanged."), false);
                                refreshAddonsAndTraktCard();
                            })
                            .setCancelable(true)
                            .show();
                });
            } catch (LocalCinemaBackupManager.DriveAuthRecoverableException rec) {
                host.main.post(() -> {
                    if (!host.isDestroyed() && rec.recoveryIntent != null) {
                        host.launchGoogleConsent(rec.recoveryIntent);
                    } else if (!host.isDestroyed()) {
                        setBackupLiveStatus(host.l("تم تسجيل الدخول ✓ — امنح صلاحية Drive لفحص النسخة السحابية.",
                                "Signed in ✓ — grant Drive permission to check your cloud backup."), false);
                    }
                });
            } catch (Exception e) {
                host.main.post(() -> {
                    if (!host.isDestroyed()) {
                        setBackupLiveStatus(host.l("تم تسجيل الدخول ✓ — لا توجد نسخة سحابية متاحة حالياً (تم الحفاظ على بياناتك).",
                                "Signed in ✓ — no cloud backup available yet (local data preserved)."), false);
                        refreshAddonsAndTraktCard();
                    }
                });
            }
        });
    }

    void executeGoogleDriveTransfer(com.google.android.gms.auth.api.signin.GoogleSignInAccount account, boolean upload) {
        if (upload) {
            collectUnifiedBackupAsync(json -> {
                setBackupLiveStatus(host.l("جارٍ رفع النسخة الموحّدة إلى حساب Google Drive...",
                        "Uploading unified backup to Google Drive..."), false);
                host.watchSyncIo.execute(() -> {
                    try {
                        LocalCinemaBackupManager.uploadToGoogleDrive(host, account, json);
                        host.main.post(() -> {
                            if (!host.isDestroyed()) {
                                setBackupLiveStatus(host.l("تم رفع النسخة الموحّدة إلى حساب Google (" + account.getEmail() + ") بنجاح ✓",
                                        "Uploaded unified backup to Google (" + account.getEmail() + ") ✓"), false);
                                refreshAddonsAndTraktCard();
                            }
                        });
                    } catch (LocalCinemaBackupManager.DriveAuthRecoverableException rec) {
                        host.main.post(() -> {
                            if (!host.isDestroyed() && rec.recoveryIntent != null) {
                                host.launchGoogleConsent(rec.recoveryIntent);
                            } else {
                                triggerGoogleDrivePickerFallback(true);
                            }
                        });
                    } catch (Exception e) {
                        host.main.post(() -> {
                            if (!host.isDestroyed()) triggerGoogleDrivePickerFallback(true);
                        });
                    }
                });
            });
        } else {
            setBackupLiveStatus(host.l("جارٍ البحث عن النسخة الموحّدة في حساب Google Drive...",
                    "Searching Google Drive for unified backup..."), false);
            host.watchSyncIo.execute(() -> {
                try {
                    String content = LocalCinemaBackupManager.downloadFromGoogleDrive(host, account);
                    applyUnifiedRestoreJsonBlocking(content);
                } catch (LocalCinemaBackupManager.DriveAuthRecoverableException rec) {
                    host.main.post(() -> {
                        if (!host.isDestroyed() && rec.recoveryIntent != null) {
                            host.launchGoogleConsent(rec.recoveryIntent);
                        } else {
                            triggerGoogleDrivePickerFallback(false);
                        }
                    });
                } catch (Exception e) {
                    host.main.post(() -> {
                        if (!host.isDestroyed()) {
                            if ("NO_DRIVE_BACKUP_FOUND".equals(e.getMessage())) {
                                setBackupLiveStatus(host.l("لا توجد نسخة محفوظة في التطبيق السحابي لحسابك — جارٍ فتح منتقي Google Drive لاختيار ملف يدوياً...",
                                        "No app-folder backup found — opening Google Drive picker so you can select any backup file..."), false);
                            }
                            triggerGoogleDrivePickerFallback(false);
                        }
                    });
                }
            });
        }
    }

    void triggerGoogleDrivePickerFallback(boolean upload) {
        if (upload) {
            setBackupLiveStatus(host.l(
                    "الوضع السريع عبر Google Drive: تعذّر الربط التلقائي المباشر (أو لم يُفعّل OAuth Client ID بعد)، لذا تم فتح منتقي النظام الموجه إلى Google Drive لحفظ النسخة مباشرة في حسابك.",
                    "Smart Google Drive Picker: Direct OAuth is unavailable yet (or not configured), opening Android's file picker aimed at Google Drive so you can save directly."), false);
            collectUnifiedBackupAsync(json -> {
                pendingUnifiedBackupJson = json;
                try {
                    host.launchDriveFallbackCreate(LocalCinemaBackupManager.buildDriveInitialPickerCreateIntent());
                } catch (Exception e) {
                    host.launchBackupCreateLocal(LocalCinemaBackupManager.DRIVE_BACKUP_FILENAME);
                }
            });
        } else {
            setBackupLiveStatus(host.l(
                    "الوضع السريع عبر Google Drive: اختر ملف local_cinema_unified_backup.json من Google Drive لاسترجاعه للهاتف وإرساله للشاشة تلقائياً.",
                    "Smart Google Drive Picker: Select local_cinema_unified_backup.json from Google Drive to restore Phone & TV automatically."), false);
            try {
                host.launchDriveFallbackOpen(LocalCinemaBackupManager.buildDriveInitialPickerOpenIntent());
            } catch (Exception e) {
                startLocalBackupRestore();
            }
        }
    }

    void checkSmartClipboard() {
        if (clipboardBannerCard == null || host.isDestroyed()) return;
        try {
            var cm = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return;
            var clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence seq = clip.getItemAt(0).coerceToText(host);
            if (seq == null) return;
            String text = seq.toString().trim();
            String lower = text.toLowerCase(java.util.Locale.ROOT);
            boolean looksLikeAddon = (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("stremio://"))
                    && lower.endsWith("/manifest.json");
            if (looksLikeAddon
                    && !text.equals(dismissedClipboardUrl)
                    && !com.meedx.providers.StremioAddons.isInstalled(host, text)) {
                detectedClipboardUrl = text;
                clipboardBannerUrlView.setText(text);
                clipboardBannerCard.setVisibility(View.VISIBLE);
            } else if (detectedClipboardUrl != null && com.meedx.providers.StremioAddons.isInstalled(host, detectedClipboardUrl)) {
                clipboardBannerCard.setVisibility(View.GONE);
            }
        } catch (Exception ignored) {}
    }

    boolean handleAddonIntent(android.content.Intent intent) {
        if (intent == null || !android.content.Intent.ACTION_VIEW.equals(intent.getAction())
                || intent.getData() == null || !"stremio".equalsIgnoreCase(intent.getData().getScheme())) return false;
        String raw = intent.getDataString();
        intent.setData(null);
        try {
            String url = com.meedx.providers.StremioAddons.normalizeManifestUrl(raw);
            String domain = android.net.Uri.parse(url).getHost();
            new androidx.appcompat.app.AlertDialog.Builder(host)
                    .setTitle(org.proninyaroslav.libretorrent.R.string.addon_install_link)
                    .setMessage(host.getString(org.proninyaroslav.libretorrent.R.string.addon_install_link_host, domain))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> installAddonUrl(url)).show();
        } catch (java.io.IOException invalid) {
            android.widget.Toast.makeText(host, org.proninyaroslav.libretorrent.R.string.addon_install_failed,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    void installAddonUrl(String raw) {
        if (raw == null || raw.isBlank()) return;
        setAddonsLiveStatus(host.getString(org.proninyaroslav.libretorrent.R.string.addon_installing), false);
        host.watchSyncIo.execute(() -> {
            try {
                var installed = com.meedx.providers.StremioAddons.list(host).stream().filter(
                        addon -> addon.manifestUrl.equals(raw)).findFirst().orElse(null);
                var added = installed == null ? com.meedx.providers.StremioAddons.fetchAndAdd(host, raw) : installed;
                if (!added.enabled) com.meedx.providers.StremioAddons.setEnabled(host, added.manifestUrl, true);
                host.main.post(() -> {
                    if (host.isDestroyed()) return;
                    sendAddonsSyncSnapshot();
                    if (clipboardBannerCard != null && raw.equals(detectedClipboardUrl)) clipboardBannerCard.setVisibility(View.GONE);
                    setAddonsLiveStatus(host.getString(org.proninyaroslav.libretorrent.R.string.addon_installed, added.name), false);
                    refreshAddonsAndTraktCard();
                });
            } catch (Exception ignored) {
                host.main.post(() -> {
                    if (!host.isDestroyed()) setAddonsLiveStatus(host.getString(org.proninyaroslav.libretorrent.R.string.addon_install_failed), true);
                });
            }
        });
    }

    void promptCuratedAddons() {
        host.watchSyncIo.execute(() -> {
            var curated = com.meedx.providers.StremioAddons.fetchCurated(host);
            host.main.post(() -> {
                if (host.isDestroyed() || host.isFinishing() || curated.isEmpty()) return;
                var choices = curated.stream().map(item -> new CinemaGlassUi.Choice(
                        item.name + (item.description.isBlank() ? "" : "\n" + item.description),
                        com.meedx.providers.StremioAddons.isInstalled(host, item.manifestUrl)
                                ? org.proninyaroslav.libretorrent.R.drawable.ic_download_done_24px
                                : org.proninyaroslav.libretorrent.R.drawable.ic_download_24px)).toList();
                CinemaGlassUi.showChoices(host, org.proninyaroslav.libretorrent.R.string.addon_curated, choices, -1,
                        index -> installAddonUrl(curated.get(index).manifestUrl));
            });
        });
    }

    void promptAddAddon() {
        var input = new EditText(host);
        input.setHint(org.proninyaroslav.libretorrent.R.string.addon_url_hint);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        if (detectedClipboardUrl != null) input.setText(detectedClipboardUrl);
        showAddonForm(org.proninyaroslav.libretorrent.R.string.addon_paste_url, org.proninyaroslav.libretorrent.R.string.addon_save, java.util.List.of(input),
                () -> installAddonUrl(input.getText().toString().trim()));
    }

    private void showAddonForm(int title, int action, java.util.List<View> fields, Runnable submit) {
        var dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(host);
        var box = host.column();
        box.setPadding(host.dp(18), host.dp(18), host.dp(18), host.dp(20));
        box.setBackground(CinemaGlassUi.glassDock(host, CinemaGlassUi.Accent.ADDONS));
        var heading = host.label(host.getString(title), 20);
        heading.setTextColor(0xffe5a93b);
        box.addView(heading);
        for (View field : fields) box.addView(field, new LinearLayout.LayoutParams(-1, -2));
        box.addView(host.button(host.getString(action), () -> {
            dialog.dismiss();
            submit.run();
        }, true), new LinearLayout.LayoutParams(-1, host.dp(52)));
        dialog.setContentView(box);
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        dialog.show();
    }

    void manageAddonsDialog() {
        var list = com.meedx.providers.StremioAddons.list(host);
        if (list.isEmpty()) {
            host.notifyStatus(host.l("لا توجد إضافات مضافة بعد", "No addons installed yet"));
            return;
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            var a = list.get(i);
            labels[i] = (a.enabled ? "✓ " : "○ ") + a.name
                    + (a.version != null && !a.version.isEmpty() ? " v" + a.version : "");
        }
        new androidx.appcompat.app.AlertDialog.Builder(host)
                .setTitle(host.l("إدارة الإضافات", "Manage Addons"))
                .setItems(labels, (d, which) -> {
                    var a = list.get(which);
                    String[] opts = {a.enabled ? host.l("تعطيل", "Disable") : host.l("تفعيل", "Enable"), host.l("حذف", "Remove")};
                    new androidx.appcompat.app.AlertDialog.Builder(host)
                            .setTitle(a.name)
                            .setItems(opts, (d2, act) -> {
                                if (act == 0) {
                                    com.meedx.providers.StremioAddons.setEnabled(host, a.manifestUrl, !a.enabled);
                                } else {
                                    com.meedx.providers.StremioAddons.remove(host, a.manifestUrl);
                                }
                                sendAddonsSyncSnapshot();
                                refreshAddonsAndTraktCard();
                            }).setNegativeButton(android.R.string.cancel, null).show();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    void promptAddonStreamSearch() {
        var query = new EditText(host);
        query.setSingleLine(true);
        query.setHint(org.proninyaroslav.libretorrent.R.string.addon_query_hint);
        var season = new EditText(host);
        season.setHint(org.proninyaroslav.libretorrent.R.string.addon_season_hint);
        season.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        var episode = new EditText(host);
        episode.setHint(org.proninyaroslav.libretorrent.R.string.addon_episode_hint);
        episode.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        showAddonForm(org.proninyaroslav.libretorrent.R.string.addon_search_stream, org.proninyaroslav.libretorrent.R.string.addon_find_streams, java.util.List.of(query, season, episode), () -> {
            String text = query.getText().toString().trim();
            if (text.isEmpty()) return;
            int s = android.text.TextUtils.isEmpty(season.getText()) ? -1 : parseEpisodeInput(season);
            int e = android.text.TextUtils.isEmpty(episode.getText()) ? 0 : parseEpisodeInput(episode);
            if (e < 0 || s < -1) return;
            resolveAndPickAddonStream(text, s, e);
        });
    }

    private static int parseEpisodeInput(EditText field) {
        try { return Integer.parseInt(field.getText().toString()); }
        catch (NumberFormatException ignored) { return -2; }
    }

    void resolveAndPickAddonStream(String query, int season, int episode) {
        setAddonsLiveStatus(host.getString(org.proninyaroslav.libretorrent.R.string.addon_searching), false);
        host.watchSyncIo.execute(() -> {
            if (query.matches("tt[0-9]+")) {
                findAddonStreams(query, query, season, episode);
                return;
            }
            var titles = com.meedx.providers.StremioAddons.browse(host, null, null, query).stream()
                    .filter(item -> (season <= 0 && episode == 0) || "series".equals(item.type())).limit(20).toList();
            host.main.post(() -> {
                if (host.isDestroyed() || host.isFinishing()) return;
                if (titles.isEmpty()) {
                    setAddonsLiveStatus(host.getString(org.proninyaroslav.libretorrent.R.string.addon_no_results), true);
                    return;
                }
                var choices = titles.stream().map(item -> new CinemaGlassUi.Choice(item.title() + " · "
                        + host.getString("series".equals(item.type()) ? org.proninyaroslav.libretorrent.R.string.addon_series : org.proninyaroslav.libretorrent.R.string.addon_movie),
                        org.proninyaroslav.libretorrent.R.drawable.ic_cinema_cast_play)).toList();
                CinemaGlassUi.showChoices(host, org.proninyaroslav.libretorrent.R.string.addon_find_streams, choices, -1, index -> {
                    var item = titles.get(index);
                    boolean series = "series".equals(item.type());
                    host.watchSyncIo.execute(() -> findAddonStreams(item.title(),
                            com.meedx.providers.StremioAddons.streamId(host, item),
                            series ? (season < 0 ? 1 : season) : -1, series ? Math.max(1, episode) : 0));
                });
            });
        });
    }

    private void findAddonStreams(String title, String imdb, int season, int episode) {
        boolean series = season > 0 || episode > 0;
        int s = season < 0 ? (series ? 1 : 0) : season;
        int e = series ? Math.max(1, episode) : 0;
        var streams = com.meedx.providers.StremioAddons.resolveStreams(host, series ? "series" : "movie", imdb, s, e);
        host.main.post(() -> {
            if (host.isDestroyed() || host.isFinishing()) return;
            if (streams.isEmpty()) {
                setAddonsLiveStatus(host.getString(org.proninyaroslav.libretorrent.R.string.addon_no_streams), true);
                return;
            }
            var choices = streams.stream().map(stream -> new CinemaGlassUi.Choice(stream.displayLabel(host),
                    org.proninyaroslav.libretorrent.R.drawable.ic_cinema_cast_play)).toList();
            CinemaGlassUi.showChoices(host, title, choices, -1, index -> castAddonStreamToTv(
                    streams.get(index), title, series ? "episode" : "movie", imdb, s, e));
        });
    }

    void castAddonStreamToTv(com.meedx.providers.StremioAddons.Stream stream,
                             String title, String mediaType, String imdbId, int season, int episode) {
        if (!host.connected || host.socket == null || !host.socket.isOpen()) {
            setAddonsLiveStatus(host.l("يرجى الاتصال بالشاشة أولاً ثم إعادة المحاولة.",
                    "Connect to the TV first before casting."), true);
            return;
        }
        if (stream.torrent()) {
            host.playAddonTorrent(stream.url, stream.fileIndex, title, imdbId, season, episode);
            return;
        }
        host.music.stopMusicForVideo(true);
        host.cleanupPreviousTemporaryTorrent("");
        String streamKey = "episode".equals(mediaType)
                ? ("addon:" + imdbId + ":s" + season + "e" + episode)
                : ("addon:" + imdbId);
        var saved = PhoneWatchStore.findBestEntry(host, streamKey, mediaType, imdbId, 0L, season, episode, title);
        long resumeMs = saved != null && !saved.watched ? saved.positionMs : 0L;
        host.activeWatchKind = "web";
        host.activeWatchKey = streamKey;
        host.activeWatchTitle = title;
        host.activeWatchUri = stream.url;
        host.activeWatchTorrentId = "";
        host.activeWatchFileIndex = 0;
        host.activeWatchFormat = "addon";
        host.activeWatchImdbId = imdbId;
        host.activeWatchTmdbId = 0L;
        host.activeWatchSeason = season;
        host.activeWatchEpisode = episode;
        host.browserCastTitle = title;
        try {
            host.socket.send(new org.json.JSONObject().put("type", "CAST_WEB")
                    .put("url", stream.url).put("audio_url", "")
                    .put("format", stream.format).put("subtitle_language", "ar").put("addon_subtitles", true)
                    .put("title", title)
                    .put("stream_key", streamKey)
                    .put("media_type", mediaType)
                    .put("imdb_id", imdbId)
                    .put("tmdb_id", 0L)
                    .put("season", season)
                    .put("episode", episode)
                    .put("resume_ms", resumeMs).toString());
            host.showPlayback(true);
        } catch (Exception e) {
            host.notifyStatus("Could not cast addon stream");
        }
    }

    void startTraktDeviceConnect() {
        String authUrl = com.meedx.providers.TraktSyncClient.createPkceAuthorizeUrl(host);
        setTraktLiveStatus(host.l(
                "جارٍ فتح متصفح تسجيل الدخول الآمن إلى Trakt (OAuth 2.0 PKCE)... وافق ثم ارجع تلقائياً للتطبيق.",
                "Opening Trakt sign-in in browser (OAuth 2.0 PKCE)... Approve and return automatically."), false);
        host.openBrowser(authUrl);
    }

    void startTraktCodeConnect() {
        lastOpenedTraktDeviceCode = "";
        setTraktLiveStatus(host.l("جارٍ إنشاء رمز تفعيل Trakt...", "Requesting Trakt activation code..."), false);
        com.meedx.providers.TraktSyncClient.startBackgroundDeviceAuth(host, this::sendFullSyncStateBlocking);
    }

    void handleTraktPkceIntent(Intent intent) {
        if (intent == null) return;
        android.net.Uri data = intent.getData();
        if (data == null || !"localcinema".equals(data.getScheme()) || !"trakt-callback".equals(data.getHost())) {
            return;
        }
        intent.setData(null);
        if (host.tabLayout != null && host.tabLayout.getTabCount() > TvHubActivity.CONNECTION_TAB
                && host.tabLayout.getSelectedTabPosition() != TvHubActivity.CONNECTION_TAB) {
            var connTab = host.tabLayout.getTabAt(TvHubActivity.CONNECTION_TAB);
            if (connTab != null) connTab.select();
        }
        setTraktLiveStatus(host.l("جارٍ استكمال ربط حساب Trakt ومزامنة الشاشة...",
                "Completing Trakt PKCE linking & syncing TV..."), false);
        com.meedx.providers.TraktSyncClient.handlePkceCallbackAsync(host, data, this::sendFullSyncStateBlocking);
    }

    void onTraktDeviceAuthSnapshot(com.meedx.providers.TraktSyncClient.DeviceAuthSnapshot snap) {
        if (snap == null) return;
        host.main.post(() -> {
            if (host.isDestroyed() || host.isFinishing()) return;
            switch (snap.state) {
                case REQUESTING -> setTraktLiveStatus(
                        host.l("جارٍ الاتصال بـ Trakt...", "Connecting to Trakt..."), false);
                case PENDING -> {
                    String code = snap.userCode == null ? "" : snap.userCode.trim();
                    String url = (snap.verificationUrl == null || snap.verificationUrl.isBlank())
                            ? "https://trakt.tv/activate" : snap.verificationUrl.trim();
                    if (!code.isEmpty()) {
                        setTraktLiveStatus(host.l(
                                "رمز التفعيل: " + code + " (تم نسخه للحافظة ✓)\nالصق الرمز في المتصفح ووافق ثم ارجع للتطبيق...",
                                "Activation Code: " + code + " (Copied to clipboard ✓)\nPaste in browser, approve, and return here..."), false);
                        if (!code.equals(lastOpenedTraktDeviceCode)) {
                            lastOpenedTraktDeviceCode = code;
                            try {
                                var cm = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cm != null) {
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Trakt Code", code));
                                }
                            } catch (Exception ignored) {}
                            host.openBrowser(url);
                        }
                    } else {
                        setTraktLiveStatus(
                                host.l("بانتظار موافقتك في متصفح Trakt...", "Waiting for approval in Trakt browser..."), false);
                    }
                }
                case AUTHORIZED -> {
                    lastOpenedTraktDeviceCode = "";
                    setTraktLiveStatus(host.l("تم ربط حساب Trakt بنجاح ✓",
                            "Trakt linked successfully ✓"), false);
                    refreshAddonsAndTraktCard();
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastHandledTraktAuthSyncMs > 4000L) {
                        lastHandledTraktAuthSyncMs = now;
                        sendFullSyncStateToTv(false);
                        syncTraktNowAsync(false);
                    }
                }
                case EXPIRED, DENIED, ERROR -> {
                    String err = (snap.errorMessage != null && !snap.errorMessage.isBlank())
                            ? snap.errorMessage
                            : host.l("فشل ربط Trakt. يرجى المحاولة مرة أخرى.", "Trakt linking failed. Please try again.");
                    setTraktLiveStatus(err, true);
                }
                case CANCELLED -> setTraktLiveStatus(host.l("تم إلغاء عملية ربط Trakt.", "Trakt linking cancelled."), false);
                case IDLE -> {}
            }
        });
    }

    void syncTraktNowAsync(boolean silent) {
        if (!com.meedx.providers.TraktSyncClient.isConnected(host)) {
            if (!silent) {
                setTraktLiveStatus(host.l("اربط حساب Trakt من زر التفعيل أعلاه أولاً.",
                        "Connect your Trakt account above first."), true);
            }
            return;
        }
        if (!silent) {
            setTraktLiveStatus(host.l("جارٍ المزامنة مع Trakt (دفع التغييرات وجلب السجل وقائمة المشاهدة)...",
                    "Syncing with Trakt (pushing progress & pulling history/watchlist)..."), false);
        }
        host.watchSyncIo.execute(() -> {
            try {
                var pending = PhoneWatchStore.listPendingTraktItems(host);
                var pushed = com.meedx.providers.TraktSyncClient.pushPending(host, pending);
                PhoneWatchStore.clearPendingTraktSync(host, pushed);
                var pulled = com.meedx.providers.TraktSyncClient.pullRecords(host);
                int applied = PhoneWatchStore.applyTraktPullRecords(host, pulled);
                var wl = com.meedx.providers.TraktSyncClient.pullWatchlist(host);
                PhoneWatchStore.saveWatchlist(host, wl);
                if (applied > 0 || !pushed.isEmpty() || wl.length() > 0) {
                    sendFullSyncStateBlocking();
                }
                host.main.post(() -> {
                    if (host.isDestroyed()) return;
                    if (!silent || applied > 0 || !pushed.isEmpty() || wl.length() > 0) {
                        setTraktLiveStatus(host.l("اكتملت مزامنة Trakt ✓ (رفع: " + pushed.size()
                                + " · تحديث: " + applied + " · قائمة المشاهدة: " + wl.length() + ")",
                                "Trakt sync complete ✓ (" + pushed.size() + " pushed, " + applied
                                + " updated, " + wl.length() + " watchlist)"), false);
                    }
                    refreshAddonsAndTraktCard();
                    host.library.refreshIfReady();
                });
            } catch (Exception e) {
                host.main.post(() -> {
                    if (!host.isDestroyed() && !silent) {
                        setTraktLiveStatus(host.l("تعذّرت مزامنة Trakt:\n",
                                "Trakt sync failed:\n") + readableError(e), true);
                    }
                });
            }
        });
    }

    TextView createLiveStatusBanner() {
        var tv = new TextView(host);
        tv.setTextSize(13);
        tv.setSingleLine(false);
        tv.setMaxLines(8);
        tv.setEllipsize(null);
        tv.setLineSpacing(host.dp(2), 1f);
        tv.setTextColor(0xfff2ede7);
        tv.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10));
        tv.setBackground(CinemaGlassUi.glassPill(host, CinemaGlassUi.Accent.SUBTLE, 12));
        tv.setVisibility(View.GONE);
        return tv;
    }

    void setTraktLiveStatus(String text, boolean isError) {
        updateBanner(traktLiveStatusView, text, isError);
    }

    void setAddonsLiveStatus(String text, boolean isError) {
        updateBanner(addonsLiveStatusView, text, isError);
    }

    void updateBanner(TextView banner, String text, boolean isError) {
        if (banner == null) return;
        if (text == null || text.isBlank()) {
            banner.setVisibility(View.GONE);
            return;
        }
        banner.setBackground(CinemaGlassUi.glassPill(host,
                isError ? CinemaGlassUi.Accent.ERROR : CinemaGlassUi.Accent.TRAKT, 12));
        banner.setTextColor(isError ? 0xffffc7c7 : 0xffefe7d6);
        banner.setText(text);
        banner.setVisibility(View.VISIBLE);
    }

    String readableError(Throwable t) {
        if (t == null || t.getMessage() == null || t.getMessage().isBlank()) {
            return host.l("حدث خطأ غير متوقع أثناء الاتصال.", "Unexpected network error.");
        }
        String m = t.getMessage().trim();
        if (m.contains("<!doctype") || m.contains("<html") || m.contains("cannot be converted to JSONObject")) {
            return host.l("استجابة غير متوقعة من الخادم (HTML بدلاً من JSON).", "Server returned HTML instead of JSON.");
        }
        if (m.contains("Unable to resolve host") || m.contains("Failed to connect") || m.contains("timeout")) {
            return host.l("تعذّر الاتصال بالخادم — تحقّق من الإنترنت أو الـ DNS الخاص.", "Network unreachable or timed out. Check your internet or Private DNS.");
        }
        return m;
    }
}

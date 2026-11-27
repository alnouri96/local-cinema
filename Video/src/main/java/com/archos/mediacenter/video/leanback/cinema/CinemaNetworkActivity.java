package com.archos.mediacenter.video.leanback.cinema;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.archos.filecorelibrary.FileUtils;
import com.archos.filecorelibrary.samba.NetworkCredentialsDatabase.Credential;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.browser.adapters.object.NonIndexedVideo;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import com.archos.mediacenter.video.leanback.details.VideoDetailsFragment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.arabic;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.dp;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.focusable;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.label;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.motion;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.rounded;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.text;

/**
 * Isolated Local Cinema Network & Library Sources Hub ({@code #171717} / {@code #E5A93B} / {@code #FFFFFF}).
 *
 * <p>Replaces legacy Nova {@code NetworkRootActivity}/{@code NetworkRootFragment},
 * {@code NetworkShortcutDetailsActivity}, {@code NetworkServerCredentialsDialog}, and
 * {@code RescanActivity} with an unclipped, RTL/LTR-aware TV experience including in-place
 * SMB/UPnP/WebDAV directory browsing, credential management, and one-tap folder indexing.
 */
public final class CinemaNetworkActivity extends LeanbackActivity implements CinemaNetworkController.Listener {
    public static final String EXTRA_BROWSE_URI = "cinema_browse_uri";
    public static final String EXTRA_BROWSE_NAME = "cinema_browse_name";

    private static final int CANVAS = 0xFF171717;
    private static final int CARD_SURFACE = 0xFF1E1E22;
    private static final int CARD_BORDER = 0xFF2F2F36;
    private static final int GOLD = 0xFFE5A93B;
    private static final int GOLD_BG = 0xFF282115;
    private static final int GOLD_BORDER = 0xFF5A4723;
    private static final int ON_GOLD = 0xFF171717;
    private static final int TEXT_WHITE = 0xFFFFFFFF;
    private static final int TEXT_MUTED = 0xFFB8B8C2;

    private boolean rtl;
    private CinemaNetworkController controller;

    // Hub root views
    private FrameLayout root;
    private LinearLayout hubPage;
    private TextView scanStatusBadge;
    private TextView lanIpBadge;
    private TextView scanAllBtn;
    private LinearLayout indexedSection, smbSection, upnpSection, savedSection;
    private LinearLayout indexedContainer, smbContainer, upnpContainer, savedContainer;
    private TextView smbEmptyNote, upnpEmptyNote;

    // In-place network folder browser overlay
    private record FolderCrumb(String title, Uri uri) {}
    private final ArrayDeque<FolderCrumb> browseStack = new ArrayDeque<>();
    private LinearLayout browserOverlay;
    private TextView browserTitleTv, browserPathTv, browserStatusTv, browserIndexBtn, browserCredBtn;
    private LinearLayout browserListContainer;
    private int browseGeneration;

    private List<CinemaNetworkController.SourceEntry> latestIndexed = List.of();
    private List<CinemaNetworkController.SourceEntry> latestSmb = List.of();
    private List<CinemaNetworkController.SourceEntry> latestUpnp = List.of();
    private List<CinemaNetworkController.SourceEntry> latestSaved = List.of();
    private boolean initialFocusApplied;
    private static final int REQ_BROWSE_FOLDER = 7411;
    private LinearLayout toolbarRow;
    private ScrollView hubScrollView;
    private String focusedCardKey;
    private String focusedCardUri;
    private int focusedToolbarIndex = -1;
    private int savedHubScrollY = 0;
    private boolean pendingReturnRestore = false;

    private String l(String ar, String en) { return label(this, ar, en); }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rtl = arabic(this);
        controller = new CinemaNetworkController(this, this);

        root = new FrameLayout(this);
        root.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(CANVAS);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        setContentView(root);

        buildHubPage();
        CinemaNavigation.attach(this, root);
        String directUri = getIntent() != null ? getIntent().getStringExtra(EXTRA_BROWSE_URI) : null;
        if (directUri != null && !directUri.isBlank()) {
            String directName = getIntent().getStringExtra(EXTRA_BROWSE_NAME);
            openNetworkBrowser(directName, Uri.parse(directUri));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        controller.start();
        refreshIpBadge();
        onScannerStateChanged(controller.isScannerActive());
    }

    @Override
    public void onResume() {
        super.onResume();
        if (pendingReturnRestore && (browserOverlay == null || browserOverlay.getVisibility() != View.VISIBLE)) {
            restoreSavedHubFocusAndScroll();
        }
    }

    @Override
    public void onPause() {
        if (hubScrollView != null && (browserOverlay == null || browserOverlay.getVisibility() != View.VISIBLE)) {
            savedHubScrollY = Math.max(0, hubScrollView.getScrollY());
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        controller.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        browseGeneration++;
        controller.destroy();
        super.onDestroy();
    }

    private void buildHubPage() {
        hubPage = new LinearLayout(this);
        hubPage.setOrientation(LinearLayout.VERTICAL);
        hubPage.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        hubPage.setClipChildren(true);
        hubPage.setClipToPadding(true);
        int startEdge = dp(this, CinemaTokens.GUTTER);
        int endEdge = dp(this, 44);
        hubPage.setPaddingRelative(startEdge, dp(this, 22), endEdge, dp(this, 18));
        root.addView(hubPage, new FrameLayout.LayoutParams(-1, -1));

        // 1. Header Row
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setBackgroundColor(CANVAS);
        headerRow.setElevation(dp(this, 4));
        headerRow.setClipChildren(false);
        headerRow.setClipToPadding(false);
        hubPage.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView pageTitle = text(this, CinemaTokens.PAGE_TITLE, true);
        pageTitle.setText(l("الشبكة ومصادر المكتبة", "Network & Library Sources"));
        pageTitle.setTextColor(TEXT_WHITE);
        pageTitle.setSingleLine(true);
        pageTitle.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        titleCol.addView(pageTitle);

        TextView subtitle = text(this, 13, false);
        subtitle.setText(l("مشاركات SMB وخوادم UPnP والمجلدات المفهرسة والاتصال اليدوي بالخوادم",
                "SMB shares, UPnP/DLNA servers, indexed library folders & manual connections"));
        subtitle.setTextColor(TEXT_MUTED);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        var subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(this, 3);
        titleCol.addView(subtitle, subLp);
        headerRow.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

        scanStatusBadge = buildStatusPill("");
        scanStatusBadge.setVisibility(View.GONE);
        var scanLp = new LinearLayout.LayoutParams(-2, -2);
        scanLp.setMarginStart(dp(this, 10));
        headerRow.addView(scanStatusBadge, scanLp);

        lanIpBadge = buildStatusPill("LAN");
        var ipLp = new LinearLayout.LayoutParams(-2, -2);
        ipLp.setMarginStart(dp(this, 10));
        headerRow.addView(lanIpBadge, ipLp);

        // 2. Top Action Toolbar (padding >= 10dp, clipChildren=false)
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(CANVAS);
        toolbar.setElevation(dp(this, 4));
        toolbar.setClipChildren(false);
        toolbar.setClipToPadding(false);
        toolbar.setPadding(dp(this, 4), dp(this, 12), dp(this, 4), dp(this, 10));
        hubPage.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        toolbarRow = toolbar;

        scanAllBtn = addToolbarAction(toolbar,
                l("⟳  فحص المكتبة الآن", "⟳  Scan Library Now"),
                () -> controller.rescanAll());
        addToolbarAction(toolbar,
                l("＋  اتصال يدوي بخادم", "＋  Add Network Server"),
                () -> showServerCredentialsDialog(null, null));
        addToolbarAction(toolbar,
                l("⚙  الفحص التلقائي", "⚙  Auto-Scan Settings"),
                this::showAutoScanDialog);
        addToolbarAction(toolbar,
                l("📁  التخزين الداخلي وUSB", "📁  Local & USB Files"),
                () -> startActivity(new Intent(this, CinemaFileBrowserActivity.class)));

        // 3. Hardware-clipped Scroll Viewport preventing scrolled sections from bleeding over header/toolbar
        FrameLayout viewportClipHost = new FrameLayout(this);
        viewportClipHost.setClipChildren(true);
        viewportClipHost.setClipToPadding(true);
        viewportClipHost.setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS);
        viewportClipHost.setClipToOutline(true);
        var vpLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        vpLp.topMargin = dp(this, 4);
        hubPage.addView(viewportClipHost, vpLp);

        ScrollView scrollViewport = new ScrollView(this);
        hubScrollView = scrollViewport;
        scrollViewport.setVerticalScrollBarEnabled(false);
        scrollViewport.setClipChildren(true);
        scrollViewport.setClipToPadding(true);
        scrollViewport.setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS);
        scrollViewport.setClipToOutline(true);
        viewportClipHost.addView(scrollViewport, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout sectionsColumn = new LinearLayout(this);
        sectionsColumn.setOrientation(LinearLayout.VERTICAL);
        sectionsColumn.setClipChildren(false);
        sectionsColumn.setClipToPadding(false);
        int pad = dp(this, 12);
        sectionsColumn.setPadding(pad, pad, pad, dp(this, 28));
        scrollViewport.addView(sectionsColumn, new FrameLayout.LayoutParams(-1, -2));

        // Section 1: Indexed Library Folders
        indexedSection = createSectionBlock(sectionsColumn,
                l("المجلدات المفهرسة في المكتبة", "Indexed Library Folders"),
                l("المجلدات المربوطة بصفوف الأفلام والمسلسلات في الشاشة الرئيسية",
                        "Folders actively indexed into Home Movies & TV Shows"));
        indexedContainer = createCardsColumn(indexedSection);

        // Section 2: Discovered SMB Shares
        smbSection = createSectionBlock(sectionsColumn,
                l("مشاركات الشبكة المكتشفة (SMB)", "Discovered SMB Network Shares"),
                l("اكتشاف تلقائي لأجهزة الكمبيوتر ووحدات NAS على نفس الشبكة المحلية",
                        "Live LAN discovery of PCs and NAS shares over SMB"));
        smbEmptyNote = createEmptyHint(smbSection,
                l("جارٍ البحث عن مشاركات SMB على الشبكة المحلية… يمكنك أيضاً إضافة خادم يدوياً بالأعلى.",
                        "Scanning LAN for SMB shares… You can also add a server manually above."));
        smbContainer = createCardsColumn(smbSection);

        // Section 3: UPnP / DLNA Servers
        upnpSection = createSectionBlock(sectionsColumn,
                l("خوادم الوسائط (UPnP / DLNA)", "UPnP / DLNA Media Servers"),
                l("خوادم البث المحلي المتوافقة مع DLNA", "Discovered DLNA / UPnP media servers on LAN"));
        upnpEmptyNote = createEmptyHint(upnpSection,
                l("لا توجد خوادم UPnP / DLNA نشطة حالياً على الشبكة.",
                        "No UPnP / DLNA media servers detected on LAN right now."));
        upnpContainer = createCardsColumn(upnpSection);

        // Section 4: Saved Network Shortcuts
        savedSection = createSectionBlock(sectionsColumn,
                l("الخوادم والاتصالات المحفوظة", "Saved Network Servers"),
                l("اختصارات FTP / SFTP / WebDAV / SMB المحفوظة للوصول السريع",
                        "Saved FTP, SFTP, WebDAV & SMB server shortcuts"));
        savedContainer = createCardsColumn(savedSection);
        savedSection.setVisibility(View.GONE);
        applyFocusRouting();
    }

    private TextView buildStatusPill(String textVal) {
        TextView pill = text(this, 12, true);
        pill.setText(textVal);
        pill.setTextColor(GOLD);
        pill.setBackground(rounded(GOLD_BG, dp(this, 12), GOLD_BORDER));
        pill.setPaddingRelative(dp(this, 12), dp(this, 5), dp(this, 12), dp(this, 5));
        return pill;
    }

    private TextView addToolbarAction(LinearLayout bar, String labelText, Runnable click) {
        final int btnIndex = bar.getChildCount();
        TextView btn = text(this, 13.5f, true);
        btn.setText(labelText);
        btn.setSingleLine(true);
        btn.setGravity(Gravity.CENTER);
        btn.setPaddingRelative(dp(this, 16), dp(this, 10), dp(this, 16), dp(this, 10));
        focusable(btn);
        btn.setMinimumHeight(dp(this, 44));
        btn.setId(View.generateViewId());
        btn.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && bar == toolbarRow && !pendingReturnRestore) {
                focusedToolbarIndex = btnIndex;
            }
        });
        btn.setOnClickListener(v -> click.run());
        var lp = new LinearLayout.LayoutParams(-2, dp(this, 44));
        lp.setMarginEnd(dp(this, 10));
        bar.addView(btn, lp);
        return btn;
    }

    private LinearLayout createSectionBlock(LinearLayout parent, String titleText, String subtitleText) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setClipChildren(false);
        block.setClipToPadding(false);
        var blockLp = new LinearLayout.LayoutParams(-1, -2);
        blockLp.bottomMargin = dp(this, 18);
        parent.addView(block, blockLp);

        TextView head = text(this, 17, true);
        head.setText(titleText);
        head.setTextColor(GOLD);
        head.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        block.addView(head, new LinearLayout.LayoutParams(-1, -2));

        TextView sub = text(this, 12, false);
        sub.setText(subtitleText);
        sub.setTextColor(TEXT_MUTED);
        sub.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        var subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(this, 2);
        subLp.bottomMargin = dp(this, 8);
        block.addView(sub, subLp);
        return block;
    }

    private TextView createEmptyHint(LinearLayout section, String message) {
        TextView tv = text(this, 13, false);
        tv.setText(message);
        tv.setTextColor(TEXT_MUTED);
        tv.setBackground(rounded(0xFF1A1A1E, dp(this, 12), CARD_BORDER));
        tv.setPaddingRelative(dp(this, 16), dp(this, 12), dp(this, 16), dp(this, 12));
        tv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        section.addView(tv, new LinearLayout.LayoutParams(-1, -2));
        return tv;
    }

    private LinearLayout createCardsColumn(LinearLayout section) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        section.addView(col, new LinearLayout.LayoutParams(-1, -2));
        return col;
    }

    private void refreshIpBadge() {
        String ip = NetworkUtils.getLocalIpv4Address(this);
        if (lanIpBadge != null) {
            lanIpBadge.setText(ip != null && !ip.isBlank()
                    ? l("الشبكة: ", "LAN: ") + ip
                    : l("غير متصل بالشبكة", "Offline"));
        }
    }

    private static String cardKeyFor(CinemaNetworkController.SourceEntry entry) {
        String idPart = entry.uri() != null ? entry.uri().toString() : entry.name();
        return entry.kind().name() + "|" + idPart;
    }

    private static boolean isDescendantOf(View child, ViewGroup parent) {
        if (child == null || parent == null) return false;
        for (android.view.ViewParent p = child.getParent(); p != null; p = p.getParent()) {
            if (p == parent) return true;
        }
        return false;
    }

    private void rebuildSectionSafely(LinearLayout container, Runnable populateAction) {
        boolean overlayVisible = browserOverlay != null && browserOverlay.getVisibility() == View.VISIBLE;
        View currentFocus = getCurrentFocus();
        boolean hadFocusInContainer = !overlayVisible && isDescendantOf(currentFocus, container);
        int scrollBefore = hubScrollView != null ? hubScrollView.getScrollY() : savedHubScrollY;
        if (toolbarRow != null && (hadFocusInContainer || pendingReturnRestore)) {
            toolbarRow.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        try {
            container.removeAllViews();
            populateAction.run();
        } finally {
            if (toolbarRow != null) {
                toolbarRow.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            }
        }
        applyFocusRouting();
        if (overlayVisible) return;
        if (pendingReturnRestore) {
            restoreSavedHubFocusAndScroll();
        } else if (hadFocusInContainer) {
            restoreCardFocusInPlace(scrollBefore);
        }
    }

    @Override
    public void onIndexedFoldersUpdated(List<CinemaNetworkController.SourceEntry> indexed) {
        if (indexed.equals(latestIndexed) && indexedContainer.getChildCount() > 0) {
            if (pendingReturnRestore) restoreSavedHubFocusAndScroll();
            else ensureInitialFocus();
            return;
        }
        latestIndexed = List.copyOf(indexed);
        rebuildSectionSafely(indexedContainer, () -> {
            if (indexed.isEmpty()) {
                TextView empty = createEmptyHint(indexedContainer,
                        l("لا توجد مجلدات مفهرسة بعد. اختر أي مشاركة SMB بالأسفل أو افتح مجلداً واضغط «فهرسة في المكتبة».",
                                "No indexed library folders yet. Open an SMB share below and choose «Index in Library»."));
                empty.setFocusable(false);
            } else {
                for (var item : indexed) {
                    indexedContainer.addView(buildSourceCard(item), cardParams());
                }
            }
        });
        ensureInitialFocus();
    }

    @Override
    public void onSmbSharesUpdated(List<CinemaNetworkController.SourceEntry> smbShares) {
        smbEmptyNote.setVisibility(smbShares.isEmpty() ? View.VISIBLE : View.GONE);
        if (smbShares.equals(latestSmb) && smbContainer.getChildCount() == smbShares.size()) {
            if (pendingReturnRestore) restoreSavedHubFocusAndScroll();
            else ensureInitialFocus();
            return;
        }
        latestSmb = List.copyOf(smbShares);
        rebuildSectionSafely(smbContainer, () -> {
            for (var item : smbShares) {
                smbContainer.addView(buildSourceCard(item), cardParams());
            }
        });
        ensureInitialFocus();
    }

    @Override
    public void onUpnpServersUpdated(List<CinemaNetworkController.SourceEntry> upnpServers) {
        upnpEmptyNote.setVisibility(upnpServers.isEmpty() ? View.VISIBLE : View.GONE);
        if (upnpServers.equals(latestUpnp) && upnpContainer.getChildCount() == upnpServers.size()) {
            return;
        }
        latestUpnp = List.copyOf(upnpServers);
        rebuildSectionSafely(upnpContainer, () -> {
            for (var item : upnpServers) {
                upnpContainer.addView(buildSourceCard(item), cardParams());
            }
        });
    }

    @Override
    public void onSavedShortcutsUpdated(List<CinemaNetworkController.SourceEntry> shortcuts) {
        savedSection.setVisibility(shortcuts.isEmpty() ? View.GONE : View.VISIBLE);
        if (shortcuts.equals(latestSaved) && savedContainer.getChildCount() == shortcuts.size()) {
            return;
        }
        latestSaved = List.copyOf(shortcuts);
        rebuildSectionSafely(savedContainer, () -> {
            for (var item : shortcuts) {
                savedContainer.addView(buildSourceCard(item), cardParams());
            }
        });
    }

    @Override
    public void onScannerStateChanged(boolean scanning) {
        if (scanStatusBadge == null) return;
        scanStatusBadge.setVisibility(scanning ? View.VISIBLE : View.GONE);
        if (scanning) {
            scanStatusBadge.setText(l("⟳ جارٍ فحص المكتبة…", "⟳ Scanning Library…"));
        }
    }

    private void ensureInitialFocus() {
        if (initialFocusApplied || pendingReturnRestore
                || (browserOverlay != null && browserOverlay.getVisibility() == View.VISIBLE)) return;
        if (indexedContainer.getChildCount() > 0 && indexedContainer.getChildAt(0).isFocusable()) {
            initialFocusApplied = true;
            root.post(() -> {
                if (!pendingReturnRestore && indexedContainer.getChildCount() > 0 && indexedContainer.getChildAt(0).isFocusable()) {
                    indexedContainer.getChildAt(0).requestFocus();
                }
            });
        } else if (smbContainer.getChildCount() > 0 && smbContainer.getChildAt(0).isFocusable()) {
            initialFocusApplied = true;
            root.post(() -> {
                if (!pendingReturnRestore && smbContainer.getChildCount() > 0 && smbContainer.getChildAt(0).isFocusable()) {
                    smbContainer.getChildAt(0).requestFocus();
                }
            });
        } else if (scanAllBtn != null && latestIndexed != null) {
            root.post(() -> {
                if (!initialFocusApplied && !pendingReturnRestore && getCurrentFocus() == null) {
                    scanAllBtn.requestFocus();
                }
            });
        }
    }

    private LinearLayout.LayoutParams cardParams() {
        var lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(this, 10);
        return lp;
    }

    private View buildSourceCard(CinemaNetworkController.SourceEntry entry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumHeight(dp(this, 64));
        card.setPaddingRelative(dp(this, 16), dp(this, 12), dp(this, 16), dp(this, 12));
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setLongClickable(true);

        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focusedBg = rounded(0xFF262116, dp(this, 14), GOLD);
        focusedBg.setStroke(dp(this, 2.5f), GOLD);
        GradientDrawable idleBg = rounded(CARD_SURFACE, dp(this, 14), CARD_BORDER);
        idleBg.setStroke(Math.max(1, dp(this, 1)), CARD_BORDER);
        bg.addState(new int[]{android.R.attr.state_focused}, focusedBg);
        bg.addState(new int[]{}, idleBg);
        card.setBackground(bg);

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(rounded(GOLD_BG, dp(this, 10), GOLD_BORDER));
        ImageView icon = new ImageView(this);
        int iconRes = switch (entry.kind()) {
            case INDEXED_FOLDER -> "PHONE".equals(entry.protocolBadge())
                    ? R.drawable.ic_tablet_cellphone : R.drawable.cinema_folder;
            case SMB_SHARE, SAVED_SERVER, UPNP_SERVER -> R.drawable.cinema_network;
        };
        var dr = ContextCompat.getDrawable(this, iconRes);
        if (dr != null) {
            dr = dr.mutate();
            dr.setTint(GOLD);
            icon.setImageDrawable(dr);
        }
        iconBox.addView(icon, new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER));
        var iconLp = new LinearLayout.LayoutParams(dp(this, 42), dp(this, 42));
        iconLp.setMarginEnd(dp(this, 14));
        card.addView(iconBox, iconLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView nameTv = text(this, 16, true);
        nameTv.setText(entry.name());
        nameTv.setTextColor(TEXT_WHITE);
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);
        nameTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        textCol.addView(nameTv, new LinearLayout.LayoutParams(-1, -2));

        TextView pathTv = text(this, 12, false);
        pathTv.setText(entry.hostOrPathSubtitle());
        pathTv.setTextColor(TEXT_MUTED);
        pathTv.setSingleLine(true);
        pathTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        pathTv.setTextDirection(View.TEXT_DIRECTION_LTR);
        pathTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        var pathLp = new LinearLayout.LayoutParams(-1, -2);
        pathLp.topMargin = dp(this, 3);
        textCol.addView(pathTv, pathLp);
        card.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        if (entry.indexed()) {
            TextView idxBadge = text(this, 11.5f, true);
            idxBadge.setText(l("مفهرس ✓", "Indexed ✓"));
            idxBadge.setTextColor(ON_GOLD);
            idxBadge.setBackground(rounded(GOLD, dp(this, 8), 0));
            idxBadge.setPaddingRelative(dp(this, 9), dp(this, 4), dp(this, 9), dp(this, 4));
            var idxLp = new LinearLayout.LayoutParams(-2, -2);
            idxLp.setMarginStart(dp(this, 8));
            card.addView(idxBadge, idxLp);
        }

        TextView protoTv = text(this, 11.5f, true);
        protoTv.setText(entry.protocolBadge());
        protoTv.setTextColor(GOLD);
        protoTv.setBackground(rounded(0xFF16161A, dp(this, 8), CARD_BORDER));
        protoTv.setPaddingRelative(dp(this, 9), dp(this, 4), dp(this, 9), dp(this, 4));
        var protoLp = new LinearLayout.LayoutParams(-2, -2);
        protoLp.setMarginStart(dp(this, 8));
        card.addView(protoTv, protoLp);

        final String cardKey = cardKeyFor(entry);
        final String uriStr = entry.uri() != null ? entry.uri().toString() : entry.name();
        card.setTag(cardKey);
        card.setOnFocusChangeListener((v, hasFocus) -> {
            nameTv.setTextColor(hasFocus ? GOLD : TEXT_WHITE);
            v.animate().scaleX(hasFocus ? 1.02f : 1f).scaleY(hasFocus ? 1.02f : 1f)
                    .setDuration(motion(this) ? 100 : 0).start();
            if (hasFocus && !pendingReturnRestore) {
                focusedCardKey = cardKey;
                focusedCardUri = uriStr;
                focusedToolbarIndex = -1;
                if (hubScrollView != null) savedHubScrollY = Math.max(0, hubScrollView.getScrollY());
            }
        });

        card.setOnClickListener(v -> {
            focusedCardKey = cardKey;
            focusedCardUri = uriStr;
            focusedToolbarIndex = -1;
            savedHubScrollY = hubScrollView != null ? Math.max(0, hubScrollView.getScrollY()) : 0;
            pendingReturnRestore = true;
            openInFileBrowser(entry.name(), entry.uri());
        });
        card.setOnLongClickListener(v -> {
            showSourceActions(v, entry);
            return true;
        });
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                showSourceActions(v, entry);
                return true;
            }
            return false;
        });
        return card;
    }

    private void showSourceActions(View anchor, CinemaNetworkController.SourceEntry entry) {
        List<CinemaActionMenu.Action> actions = new ArrayList<>();
        actions.add(new CinemaActionMenu.Action(
                R.drawable.cinema_folder,
                l("تصفح المجلد والملفات", "Browse Folder & Files"),
                l("استعراض المجلدات الفرعية والفيديوهات", "Open subfolders and play videos"),
                () -> openInFileBrowser(entry.name(), entry.uri())));

        boolean isUpnp = entry.kind() == CinemaNetworkController.Kind.UPNP_SERVER;
        if (!isUpnp) {
            if (entry.indexed() || controller.isFolderIndexed(entry.uri())) {
                actions.add(new CinemaActionMenu.Action(
                        R.drawable.ic_menu_refresh,
                        l("إعادة فحص وتحديث المجلد الآن", "Rescan Folder Now"),
                        l("البحث عن الأفلام والحلقات المضافة حديثاً", "Scan for newly added movies and episodes"),
                        () -> {
                            controller.rescanFolder(entry.uri());
                            Toast.makeText(this, R.string.cinema_library_refresh_started, Toast.LENGTH_SHORT).show();
                        }));
                actions.add(new CinemaActionMenu.Action(
                        R.drawable.cinema_ic_system,
                        l("إزالة من المكتبة المفهرسة", "Remove from Indexed Library"),
                        l("إلغاء فهرسة المجلد مع الحفاظ على الملفات الأصلية", "Unindex source without deleting any video files"),
                        () -> confirmRemoveIndexed(entry)));
            } else {
                actions.add(new CinemaActionMenu.Action(
                        R.drawable.cinema_check,
                        l("إضافة وفهرسة في المكتبة", "Add & Index in Library"),
                        l("إدراج أفلام ومسلسلات هذا المجلد تلقائياً في الرئيسية", "Automatically index movies & series into Home"),
                        () -> controller.indexFolder(entry.uri(), entry.name(), () ->
                                Toast.makeText(this, getString(R.string.indexed_folder_added, entry.name()), Toast.LENGTH_SHORT).show())));
            }

            String scheme = entry.uri() != null && entry.uri().getScheme() != null
                    ? entry.uri().getScheme().toLowerCase(Locale.ROOT) : "";
            if (!"file".equals(scheme)) {
                actions.add(new CinemaActionMenu.Action(
                        R.drawable.cinema_ic_key,
                        l("بيانات الدخول (اسم المستخدم وكلمة المرور)", "Server Credentials"),
                        l("تعيين أو تعديل بيانات المصادقة للمشاركة المحمية", "Configure username & password for protected share"),
                        () -> showShareAuthDialog(entry.uri(), null)));
            }
        }

        if (entry.kind() == CinemaNetworkController.Kind.SAVED_SERVER) {
            actions.add(new CinemaActionMenu.Action(
                    R.drawable.cinema_ic_system,
                    l("حذف الاتصال المحفوظ", "Remove Saved Server"),
                    entry.hostOrPathSubtitle(),
                    () -> controller.removeSavedServer(entry.uri(), () ->
                            Toast.makeText(this, getString(R.string.shortcut_removed, entry.name()), Toast.LENGTH_SHORT).show())));
        }

        CinemaActionMenu.show(anchor, R.drawable.cinema_network, entry.name(),
                actions.toArray(new CinemaActionMenu.Action[0]));
    }

    private void confirmRemoveIndexed(CinemaNetworkController.SourceEntry entry) {
        CinemaUi.showDialogWithButtonFocus(CinemaUi.dialog(this)
                .setTitle(R.string.cinema_remove_library_source)
                .setMessage(R.string.cinema_only_the_app_library_source_will_be_removed_video_files_and_the_network_folder_will_be_preserved)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        controller.removeIndexedFolder(entry.id(), entry.uri(), () ->
                                Toast.makeText(this, getString(R.string.indexed_folder_removed, entry.name()), Toast.LENGTH_SHORT).show())),
                android.content.DialogInterface.BUTTON_POSITIVE);
    }

    // =========================================================================
    // Built-in Dark/Gold Network Share & Subfolder Browser
    // =========================================================================

    private void openNetworkBrowser(String rootName, Uri rootUri) {
        if (rootUri == null) return;
        browseStack.clear();
        browseStack.push(new FolderCrumb(rootName != null ? rootName : FileUtils.getName(rootUri), rootUri));

        if (browserOverlay == null) {
            browserOverlay = new LinearLayout(this);
            browserOverlay.setOrientation(LinearLayout.VERTICAL);
            browserOverlay.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            browserOverlay.setBackgroundColor(CANVAS);
            browserOverlay.setClickable(true);
            browserOverlay.setClipChildren(true);
            browserOverlay.setClipToPadding(true);
            int startEdge = dp(this, CinemaTokens.GUTTER);
            browserOverlay.setPaddingRelative(startEdge, dp(this, 22), dp(this, 44), dp(this, 18));

            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(CANVAS);
            bar.setElevation(dp(this, 4));
            bar.setClipChildren(false);
            bar.setClipToPadding(false);
            bar.setPadding(dp(this, 10), dp(this, 6), dp(this, 10), dp(this, 10));
            browserOverlay.addView(bar, new LinearLayout.LayoutParams(-1, -2));

            addToolbarAction(bar, getString(R.string.cinema_back), this::stepBackBrowser);

            LinearLayout headCol = new LinearLayout(this);
            headCol.setOrientation(LinearLayout.VERTICAL);
            headCol.setPaddingRelative(dp(this, 8), 0, dp(this, 12), 0);
            browserTitleTv = text(this, 20, true);
            browserTitleTv.setTextColor(GOLD);
            browserTitleTv.setSingleLine(true);
            browserTitleTv.setEllipsize(TextUtils.TruncateAt.END);
            browserTitleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            headCol.addView(browserTitleTv);

            browserPathTv = text(this, 12, false);
            browserPathTv.setTextColor(TEXT_MUTED);
            browserPathTv.setSingleLine(true);
            browserPathTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            browserPathTv.setTextDirection(View.TEXT_DIRECTION_LTR);
            browserPathTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            headCol.addView(browserPathTv);
            bar.addView(headCol, new LinearLayout.LayoutParams(0, -2, 1f));

            browserIndexBtn = addToolbarAction(bar, "", this::toggleCurrentBrowserFolderIndex);
            browserCredBtn = addToolbarAction(bar, l("🔑 بيانات الدخول", "🔑 Credentials"), () -> {
                FolderCrumb cur = browseStack.peek();
                if (cur != null) showShareAuthDialog(cur.uri(), this::loadCurrentBrowserFolder);
            });

            browserStatusTv = text(this, 13, false);
            browserStatusTv.setTextColor(TEXT_MUTED);
            browserStatusTv.setBackgroundColor(CANVAS);
            browserStatusTv.setPaddingRelative(dp(this, 8), dp(this, 4), dp(this, 8), dp(this, 6));
            browserOverlay.addView(browserStatusTv, new LinearLayout.LayoutParams(-1, -2));

            FrameLayout bScrollClip = new FrameLayout(this);
            bScrollClip.setClipChildren(true);
            bScrollClip.setClipToPadding(true);
            bScrollClip.setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS);
            bScrollClip.setClipToOutline(true);
            browserOverlay.addView(bScrollClip, new LinearLayout.LayoutParams(-1, 0, 1f));

            ScrollView bScroll = new ScrollView(this);
            bScroll.setVerticalScrollBarEnabled(false);
            bScroll.setClipChildren(true);
            bScroll.setClipToPadding(true);
            bScroll.setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS);
            bScroll.setClipToOutline(true);
            bScrollClip.addView(bScroll, new FrameLayout.LayoutParams(-1, -1));

            browserListContainer = new LinearLayout(this);
            browserListContainer.setOrientation(LinearLayout.VERTICAL);
            browserListContainer.setClipChildren(false);
            browserListContainer.setClipToPadding(false);
            int pad = dp(this, 12);
            browserListContainer.setPadding(pad, pad, pad, dp(this, 24));
            bScroll.addView(browserListContainer, new FrameLayout.LayoutParams(-1, -2));

            root.addView(browserOverlay, new FrameLayout.LayoutParams(-1, -1));
        }

        hubPage.setVisibility(View.INVISIBLE);
        browserOverlay.setVisibility(View.VISIBLE);
        loadCurrentBrowserFolder();
    }

    private void closeNetworkBrowser() {
        browseGeneration++;
        browseStack.clear();
        if (browserOverlay != null) browserOverlay.setVisibility(View.GONE);
        hubPage.setVisibility(View.VISIBLE);
        pendingReturnRestore = true;
        controller.reloadShortcutsAndIndexed();
        restoreSavedHubFocusAndScroll();
    }

    private void stepBackBrowser() {
        if (browseStack.size() > 1) {
            browseStack.pop();
            loadCurrentBrowserFolder();
        } else {
            closeNetworkBrowser();
        }
    }

    private void updateBrowserIndexButton() {
        FolderCrumb cur = browseStack.peek();
        if (cur == null || browserIndexBtn == null) return;
        String scheme = cur.uri().getScheme() == null ? "" : cur.uri().getScheme().toLowerCase(Locale.ROOT);
        if ("upnp".equals(scheme)) {
            browserIndexBtn.setVisibility(View.GONE);
            browserCredBtn.setVisibility(View.GONE);
            return;
        }
        browserIndexBtn.setVisibility(View.VISIBLE);
        browserCredBtn.setVisibility("file".equals(scheme) ? View.GONE : View.VISIBLE);
        boolean indexed = controller.isFolderIndexed(cur.uri());
        browserIndexBtn.setText(indexed
                ? l("✓ مفهرس · إزالة", "✓ Indexed · Remove")
                : l("＋ فهرسة المجلد في المكتبة", "＋ Index Folder in Library"));
    }

    private void toggleCurrentBrowserFolderIndex() {
        FolderCrumb cur = browseStack.peek();
        if (cur == null) return;
        if (controller.isFolderIndexed(cur.uri())) {
            controller.removeIndexedFolder(-1L, cur.uri(), () -> {
                Toast.makeText(this, getString(R.string.indexed_folder_removed, cur.title()), Toast.LENGTH_SHORT).show();
                updateBrowserIndexButton();
            });
        } else {
            controller.indexFolder(cur.uri(), cur.title(), () -> {
                Toast.makeText(this, getString(R.string.indexed_folder_added, cur.title()), Toast.LENGTH_SHORT).show();
                updateBrowserIndexButton();
            });
        }
    }

    private void loadCurrentBrowserFolder() {
        FolderCrumb cur = browseStack.peek();
        if (cur == null) {
            closeNetworkBrowser();
            return;
        }
        final int token = ++browseGeneration;
        var crumbs = new ArrayList<String>();
        for (FolderCrumb c : browseStack) crumbs.add(0, c.title());
        browserTitleTv.setText(String.join("  ›  ", crumbs));
        browserPathTv.setText(CinemaNetworkController.stripCredentials(cur.uri().toString()));
        updateBrowserIndexButton();

        browserListContainer.removeAllViews();
        browserStatusTv.setText(getString(R.string.cinema_loading_files));

        controller.listDirectory(cur.uri(), res -> {
            if (token != browseGeneration || browserOverlay == null || browserOverlay.getVisibility() != View.VISIBLE) {
                return;
            }
            if (res.authRequired()) {
                browserStatusTv.setText(l("يتطلب هذا المجلد اسم مستخدم وكلمة مرور للوصول.",
                        "Authentication required for this network share."));
                showShareAuthDialog(cur.uri(), this::loadCurrentBrowserFolder);
                return;
            }
            if (res.errorMessage() != null && res.items().isEmpty()) {
                browserStatusTv.setText(getString(R.string.cinema_could_not_read_this_source_check_permissions_and_connection));
                if (browserIndexBtn != null && browserIndexBtn.getVisibility() == View.VISIBLE) {
                    browserIndexBtn.requestFocus();
                }
                return;
            }
            if (res.items().isEmpty()) {
                browserStatusTv.setText(getString(R.string.cinema_empty_folder));
                if (browserIndexBtn != null && browserIndexBtn.getVisibility() == View.VISIBLE) {
                    browserIndexBtn.requestFocus();
                }
                return;
            }

            browserStatusTv.setText(res.items().size() + l(" عنصر · اضغط مطولاً على أي مجلد لفهرسته مباشرة",
                    " items · Hold OK on any folder to index directly"));
            View firstRow = null;
            for (CinemaFileOperations.Item item : res.items()) {
                View row = buildBrowserEntryRow(item);
                browserListContainer.addView(row, cardParams());
                if (firstRow == null) firstRow = row;
            }
            routeVerticalList(browserListContainer, browserIndexBtn != null && browserIndexBtn.getVisibility() == View.VISIBLE ? browserIndexBtn : null);
            if (firstRow != null) {
                final View focusTarget = firstRow;
                focusTarget.post(focusTarget::requestFocus);
            }
        });
    }

    private View buildBrowserEntryRow(CinemaFileOperations.Item item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(this, 56));
        row.setPaddingRelative(dp(this, 16), dp(this, 10), dp(this, 16), dp(this, 10));
        row.setId(View.generateViewId());
        row.setFocusable(true);
        row.setClickable(true);
        row.setLongClickable(item.directory());

        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focusedBg = rounded(GOLD, dp(this, 12), 0xFFFFE394);
        focusedBg.setStroke(dp(this, 2), 0xFFFFE394);
        GradientDrawable idleBg = rounded(CARD_SURFACE, dp(this, 12), CARD_BORDER);
        idleBg.setStroke(Math.max(1, dp(this, 1)), CARD_BORDER);
        bg.addState(new int[]{android.R.attr.state_focused}, focusedBg);
        bg.addState(new int[]{}, idleBg);
        row.setBackground(bg);

        ImageView icon = new ImageView(this);
        int iconRes = item.directory() ? R.drawable.cinema_folder
                : item.isVideo() ? R.drawable.cinema_play_movie
                : item.isSubtitle() ? R.drawable.cinema_subtitles : R.drawable.cinema_ic_system;
        var dr = ContextCompat.getDrawable(this, iconRes);
        if (dr != null) {
            dr = dr.mutate();
            dr.setTint(GOLD);
            icon.setImageDrawable(dr);
        }
        var iconLp = new LinearLayout.LayoutParams(dp(this, 24), dp(this, 24));
        iconLp.setMarginEnd(dp(this, 14));
        row.addView(icon, iconLp);

        TextView nameTv = text(this, 15, true);
        nameTv.setText(item.name());
        nameTv.setTextColor(TEXT_WHITE);
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        nameTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        row.addView(nameTv, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView metaTv = text(this, 12, false);
        metaTv.setTextColor(TEXT_MUTED);
        if (item.directory()) {
            metaTv.setText(controller.isFolderIndexed(item.uri()) ? l("مفهرس ✓", "Indexed ✓") : l("مجلد", "Folder"));
        } else if (item.size() > 0L) {
            metaTv.setText(item.size() >= 1073741824L
                    ? String.format(Locale.US, "%.2f GB", item.size() / 1073741824.0)
                    : String.format(Locale.US, "%.1f MB", item.size() / 1048576.0));
        }
        var metaLp = new LinearLayout.LayoutParams(-2, -2);
        metaLp.setMarginStart(dp(this, 10));
        row.addView(metaTv, metaLp);

        row.setOnFocusChangeListener((v, hasFocus) -> {
            nameTv.setTextColor(hasFocus ? ON_GOLD : TEXT_WHITE);
            metaTv.setTextColor(hasFocus ? 0xFF2D230E : TEXT_MUTED);
            if (icon.getDrawable() != null) icon.getDrawable().setTint(hasFocus ? ON_GOLD : GOLD);
            v.animate().scaleX(hasFocus ? 1.018f : 1f).scaleY(hasFocus ? 1.018f : 1f)
                    .setDuration(motion(this) ? 95 : 0).start();
        });

        row.setOnClickListener(v -> {
            if (item.directory()) {
                browseStack.push(new FolderCrumb(item.name(), item.uri()));
                loadCurrentBrowserFolder();
            } else if (item.isVideo()) {
                NonIndexedVideo video = new NonIndexedVideo(item.uri(), item.uri(), item.name(), null);
                startActivity(new Intent(this, CinemaDetailsActivity.class)
                        .setData(item.uri())
                        .putExtra(VideoDetailsFragment.EXTRA_VIDEO, video));
            } else {
                Toast.makeText(this, R.string.cinema_this_file_type_cannot_be_opened, Toast.LENGTH_SHORT).show();
            }
        });

        if (item.directory()) {
            row.setOnLongClickListener(v -> {
                boolean already = controller.isFolderIndexed(item.uri());
                if (already) {
                    controller.removeIndexedFolder(-1L, item.uri(), () -> {
                        Toast.makeText(this, getString(R.string.indexed_folder_removed, item.name()), Toast.LENGTH_SHORT).show();
                        metaTv.setText(l("مجلد", "Folder"));
                    });
                } else {
                    controller.indexFolder(item.uri(), item.name(), () -> {
                        Toast.makeText(this, getString(R.string.indexed_folder_added, item.name()), Toast.LENGTH_SHORT).show();
                        metaTv.setText(l("مفهرس ✓", "Indexed ✓"));
                    });
                }
                return true;
            });
        }
        return row;
    }

    // =========================================================================
    // Dark/Gold SMB/NAS Share Authentication & Manual Server Dialogs
    // =========================================================================

    private void showShareAuthDialog(Uri shareUri, Runnable onAuthenticated) {
        if (shareUri == null) return;
        Credential existing = controller.getSavedCredential(shareUri);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        int p = dp(this, 20);
        form.setPadding(p, dp(this, 12), p, dp(this, 8));

        TextView hostLabel = text(this, 12.5f, false);
        hostLabel.setText(CinemaNetworkController.stripCredentials(shareUri.toString()));
        hostLabel.setTextColor(GOLD);
        hostLabel.setTextDirection(View.TEXT_DIRECTION_LTR);
        form.addView(hostLabel, new LinearLayout.LayoutParams(-1, -2));

        EditText userEt = createStyledInput(form, l("اسم المستخدم (اتركه فارغاً للضيف Guest)", "Username (leave blank for Guest)"),
                existing != null ? existing.getUsername() : "", false);
        EditText passEt = createStyledInput(form, l("كلمة المرور", "Password"),
                existing != null ? existing.getPassword() : "", true);
        EditText domainEt = createStyledInput(form, l("النطاق / Workgroup (اختياري)", "Domain / Workgroup (optional)"),
                existing != null ? existing.getDomain() : "", false);

        View pasteBadge = CinemaPhoneLink.pasteBadge(this);
        var pasteLp = new LinearLayout.LayoutParams(-2, -2);
        pasteLp.topMargin = dp(this, 8);
        form.addView(pasteBadge, pasteLp);

        AlertDialog dlg = CinemaUi.dialog(this)
                .setTitle(l("بيانات الدخول للمشاركة", "Share Credentials"))
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(l("حفظ واتصال", "Save & Connect"), (d, w) -> {
                    controller.saveCredentialsForUri(
                            shareUri,
                            userEt.getText().toString(),
                            passEt.getText().toString(),
                            domainEt.getText().toString(),
                            true);
                    if (onAuthenticated != null) onAuthenticated.run();
                })
                .create();
        dlg.show();
        userEt.requestFocus();
    }

    private void showServerCredentialsDialog(Uri editUri, Runnable afterConnect) {
        var sp = PreferenceManager.getDefaultSharedPreferences(this);
        // Protocol map -> UriUtils index: SMB=4, SMBj=5, WebDAV=6, WebDAVS=7, FTP=0, FTPS=2, SFTP=1
        int[] protoTypeCodes = {4, 6, 7, 1, 0, 2};
        String[] protoLabels = {"SMB (Windows / NAS)", "WebDAV (HTTP)", "WebDAVS (HTTPS)", "SFTP (SSH)", "FTP", "FTPS"};
        final int[] selectedProtoPos = {0};
        int savedType = sp.getInt("NET_LATEST_TYPE", 4);
        for (int i = 0; i < protoTypeCodes.length; i++) {
            if (protoTypeCodes[i] == savedType) { selectedProtoPos[0] = i; break; }
        }

        ScrollView formScroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        int p = dp(this, 20);
        form.setPadding(p, dp(this, 10), p, dp(this, 8));
        formScroll.addView(form, new FrameLayout.LayoutParams(-1, -2));

        TextView protoBtn = text(this, 14, true);
        protoBtn.setText(l("البروتوكول: ", "Protocol: ") + protoLabels[selectedProtoPos[0]]);
        protoBtn.setPaddingRelative(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
        focusable(protoBtn);
        protoBtn.setOnClickListener(v -> CinemaUi.dialog(this)
                .setTitle(l("اختر بروتوكول الشبكة", "Select Network Protocol"))
                .setSingleChoiceItems(protoLabels, selectedProtoPos[0], (pd, which) -> {
                    selectedProtoPos[0] = which;
                    protoBtn.setText(l("البروتوكول: ", "Protocol: ") + protoLabels[which]);
                    pd.dismiss();
                }).show());
        form.addView(protoBtn, new LinearLayout.LayoutParams(-1, -2));

        EditText hostEt = createStyledInput(form, l("عنوان الخادم أو IP (مثال: 192.168.1.50)", "Server Host or IP (e.g. 192.168.1.50)"),
                editUri != null && editUri.getHost() != null ? editUri.getHost() : sp.getString("NET_LATEST_ADDRESS", ""), false);
        int initPort = editUri != null ? editUri.getPort() : sp.getInt("NET_LATEST_PORT", -1);
        EditText portEt = createStyledInput(form, l("المنفذ Port (اتركه فارغاً للافتراضي)", "Port (leave blank for default)"),
                initPort > 0 ? Integer.toString(initPort) : "", false);
        portEt.setInputType(InputType.TYPE_CLASS_NUMBER);

        EditText pathEt = createStyledInput(form, l("المجلد / المسار (مثال: /Movies)", "Share / Path (e.g. /Movies)"),
                editUri != null && editUri.getPath() != null ? editUri.getPath() : sp.getString("NET_LATEST_PATH", "/"), false);
        EditText userEt = createStyledInput(form, l("اسم المستخدم (اختياري في SMB)", "Username (optional for SMB guest)"),
                sp.getString("NET_LATEST_USERNAME", ""), false);
        EditText passEt = createStyledInput(form, l("كلمة المرور", "Password"), "", true);

        View pasteBadge = CinemaPhoneLink.pasteBadge(this);
        var pasteLp = new LinearLayout.LayoutParams(-2, -2);
        pasteLp.topMargin = dp(this, 8);
        form.addView(pasteBadge, pasteLp);

        AlertDialog dlg = CinemaUi.dialog(this)
                .setTitle(l("إضافة اتصال بخادم شبكة", "Connect to Network Server"))
                .setView(formScroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(l("اتصال وحفظ", "Connect & Save"), (d, w) -> {
                    String host = hostEt.getText().toString().trim();
                    if (host.isEmpty()) {
                        Toast.makeText(this, R.string.invalid_host, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int port = -1;
                    String portRaw = portEt.getText().toString().trim();
                    if (!portRaw.isEmpty()) {
                        try { port = Integer.parseInt(portRaw); } catch (NumberFormatException ignored) {}
                    }
                    var spec = new CinemaNetworkController.ServerSpec(
                            protoTypeCodes[selectedProtoPos[0]],
                            host,
                            port,
                            pathEt.getText().toString(),
                            userEt.getText().toString(),
                            passEt.getText().toString(),
                            "",
                            true);
                    Uri built = controller.connectAndSaveManualServer(spec);
                    if (built != null) {
                        if (afterConnect != null) afterConnect.run();
                        openNetworkBrowser(host, built);
                    }
                })
                .create();
        dlg.show();
        if (dlg.getWindow() != null) {
            int w = Math.min(dp(this, 560), Math.round(getResources().getDisplayMetrics().widthPixels * 0.68f));
            dlg.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        hostEt.requestFocus();
    }

    private EditText createStyledInput(LinearLayout parent, String hint, String initialValue, boolean password) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(0xFF8A8A96);
        et.setText(initialValue == null ? "" : initialValue);
        et.setTextColor(TEXT_WHITE);
        et.setTextSize(14f);
        et.setSingleLine(true);
        et.setTextDirection(View.TEXT_DIRECTION_LTR);
        et.setPaddingRelative(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
        if (password) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, rounded(0xFF242119, dp(this, 10), GOLD));
        bg.addState(new int[]{}, rounded(0xFF141418, dp(this, 10), CARD_BORDER));
        et.setBackground(bg);
        var lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(this, 8);
        parent.addView(et, lp);
        return et;
    }

    private void showAutoScanDialog() {
        boolean onOpen = controller.isRescanOnOpenEnabled();
        String[] items = {
                (onOpen ? "✓  " : "○  ") + l("فحص المجلدات تلقائياً عند فتح التطبيق", "Auto-rescan folders when opening app"),
                "⟳  " + l("بدء فحص شامل لجميع المجلدات الآن", "Run full manual scan of all library folders now")
        };
        CinemaUi.dialog(this)
                .setTitle(l("إعدادات فحص الشبكة والمكتبة", "Library & Network Scan Settings"))
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        controller.setRescanOnOpenEnabled(!onOpen);
                        Toast.makeText(this,
                                !onOpen ? l("تم تفعيل الفحص عند بدء التشغيل ✓", "Scan on startup enabled ✓")
                                        : l("تم إيقاف الفحص عند بدء التشغيل", "Scan on startup disabled"),
                                Toast.LENGTH_SHORT).show();
                    } else if (which == 1) {
                        controller.rescanAll();
                    }
                })
                .setNegativeButton(R.string.addon_back, null)
                .show();
    }

    // =========================================================================
    // D-pad focus routing + folder opening via the unified file browser
    // =========================================================================

    private void openInFileBrowser(String name, Uri uri) {
        if (uri == null) return;
        Intent intent = new Intent(this, CinemaFileBrowserActivity.class)
                .putExtra(CinemaFileBrowserActivity.EXTRA_START_URI, uri.toString())
                .putExtra(CinemaFileBrowserActivity.EXTRA_START_NAME, name);
        startActivityForResult(intent, REQ_BROWSE_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_BROWSE_FOLDER || resultCode != RESULT_OK || data == null) return;
        Uri picked = data.getData();
        if (picked == null) return;
        String name = FileUtils.getName(picked);
        NonIndexedVideo video = new NonIndexedVideo(picked, picked, name != null ? name : "", null);
        startActivity(new Intent(this, CinemaDetailsActivity.class)
                .setData(picked)
                .putExtra(VideoDetailsFragment.EXTRA_VIDEO, video));
    }

    private void blockEndSide(View v) {
        if (rtl) v.setNextFocusLeftId(v.getId()); else v.setNextFocusRightId(v.getId());
    }

    /** Wires a vertical list: first row Up -> {@code upTarget}; last row Down stops; end side stays inside. */
    private void routeVerticalList(LinearLayout container, View upTarget) {
        List<View> rows = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            View c = container.getChildAt(i);
            if (c.isFocusable()) rows.add(c);
        }
        routeRows(rows, upTarget);
    }

    private void routeRows(List<View> rows, View upTarget) {
        for (int i = 0; i < rows.size(); i++) {
            View cur = rows.get(i);
            if (cur.getId() == View.NO_ID) cur.setId(View.generateViewId());
        }
        for (int i = 0; i < rows.size(); i++) {
            View cur = rows.get(i);
            View prev = i > 0 ? rows.get(i - 1) : upTarget;
            View next = i < rows.size() - 1 ? rows.get(i + 1) : cur;
            cur.setNextFocusUpId(prev != null ? prev.getId() : cur.getId());
            cur.setNextFocusDownId(next.getId());
            blockEndSide(cur);
        }
    }

    private List<View> collectFocusableHubCards() {
        List<View> cards = new ArrayList<>();
        LinearLayout[] containers = {indexedContainer, smbContainer, upnpContainer, savedContainer};
        for (LinearLayout c : containers) {
            if (c == null) continue;
            View section = (View) c.getParent();
            if (section != null && section.getVisibility() == View.GONE) continue;
            for (int i = 0; i < c.getChildCount(); i++) {
                View child = c.getChildAt(i);
                if (child.isFocusable()) cards.add(child);
            }
        }
        return cards;
    }

    private View findMatchingHubCard(List<View> cards) {
        if (focusedCardKey != null) {
            for (View c : cards) {
                if (focusedCardKey.equals(c.getTag())) return c;
            }
        }
        if (focusedCardUri != null) {
            String suffix = "|" + focusedCardUri;
            for (View c : cards) {
                if (c.getTag() instanceof String t && t.endsWith(suffix)) return c;
            }
        }
        return null;
    }

    private void restoreCardFocusInPlace(int targetScrollY) {
        List<View> cards = collectFocusableHubCards();
        View match = findMatchingHubCard(cards);
        if (match == null && !cards.isEmpty()) match = cards.get(0);
        final View focusTarget = match;
        if (focusTarget != null) {
            focusTarget.requestFocus();
            if (hubScrollView != null && targetScrollY > 0) {
                hubScrollView.scrollTo(0, targetScrollY);
                hubScrollView.post(() -> {
                    if (hubScrollView != null) hubScrollView.scrollTo(0, targetScrollY);
                });
            }
        }
    }

    private void restoreSavedHubFocusAndScroll() {
        if (root == null) return;
        final int targetScrollY = savedHubScrollY;
        androidx.core.view.OneShotPreDrawListener.add(root, () -> {
            if (isFinishing() || isDestroyed()
                    || (browserOverlay != null && browserOverlay.getVisibility() == View.VISIBLE)) {
                return;
            }
            if (focusedToolbarIndex >= 0 && toolbarRow != null && focusedToolbarIndex < toolbarRow.getChildCount()) {
                View btn = toolbarRow.getChildAt(focusedToolbarIndex);
                if (hubScrollView != null) hubScrollView.scrollTo(0, targetScrollY);
                btn.requestFocus();
                pendingReturnRestore = false;
                return;
            }
            List<View> cards = collectFocusableHubCards();
            View match = findMatchingHubCard(cards);
            if (match != null) {
                if (hubScrollView != null && targetScrollY > 0) {
                    hubScrollView.scrollTo(0, targetScrollY);
                }
                match.requestFocus();
                if (hubScrollView != null && targetScrollY > 0) {
                    hubScrollView.post(() -> {
                        if (hubScrollView != null) hubScrollView.scrollTo(0, targetScrollY);
                    });
                }
                pendingReturnRestore = false;
            } else if (!cards.isEmpty() && focusedCardKey == null) {
                cards.get(0).requestFocus();
                pendingReturnRestore = false;
            }
        });
    }

    private void applyFocusRouting() {
        if (toolbarRow == null) return;
        List<View> cards = collectFocusableHubCards();
        View firstCard = cards.isEmpty() ? null : cards.get(0);
        int btnCount = toolbarRow.getChildCount();
        for (int i = 0; i < btnCount; i++) {
            View b = toolbarRow.getChildAt(i);
            if (b.getId() == View.NO_ID) b.setId(View.generateViewId());
        }
        for (int i = 0; i < btnCount; i++) {
            View b = toolbarRow.getChildAt(i);
            b.setNextFocusUpId(b.getId());
            b.setNextFocusDownId(firstCard != null ? firstCard.getId() : b.getId());
            View prev = i > 0 ? toolbarRow.getChildAt(i - 1) : null;
            View next = i < btnCount - 1 ? toolbarRow.getChildAt(i + 1) : b;
            if (rtl) {
                if (prev != null) b.setNextFocusRightId(prev.getId());
                b.setNextFocusLeftId(next.getId());
            } else {
                if (prev != null) b.setNextFocusLeftId(prev.getId());
                b.setNextFocusRightId(next.getId());
            }
        }
        View upTarget = (focusedToolbarIndex >= 0 && focusedToolbarIndex < btnCount)
                ? toolbarRow.getChildAt(focusedToolbarIndex) : scanAllBtn;
        routeRows(cards, upTarget);
    }
    @Override
    public void onBackPressed() {
        if (browserOverlay != null && browserOverlay.getVisibility() == View.VISIBLE) {
            stepBackBrowser();
            return;
        }
        super.onBackPressed();
    }
}

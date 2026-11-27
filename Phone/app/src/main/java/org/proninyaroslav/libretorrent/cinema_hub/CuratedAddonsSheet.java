package org.proninyaroslav.libretorrent.cinema_hub;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.meedx.providers.StremioAddons;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Curated Stremio Add-on Presets bottom sheet on Phone:
 * - Queries and reflects live TV installation status with a clear "مثبّتة ✓" / "Installed ✓" badge.
 * - Updates card state immediately on install and blocks duplicate 1-click installs.
 * - Uses NestedScrollView + BottomSheetBehavior coordination so list scrolling never accidentally drags/closes the sheet.
 */
final class CuratedAddonsSheet {
    private static final int BG_INK = 0xff171717;
    private static final int CARD_BG = 0xff201e1b;
    private static final int CARD_BG_INSTALLED = 0xff1b241f;
    private static final int CARD_STROKE = 0xff493d2b;
    private static final int CARD_STROKE_INSTALLED = 0xff3b6950;
    private static final int GOLD = 0xffe5a93b;
    private static final int INSTALLED_FG = 0xfffde68a;
    private static final int INSTALLED_BG = 0x3310b981;
    private static final int INSTALLED_BORDER = 0xaa34d399;

    private record ActiveSheetState(
            TvHubActivity host,
            TvHubConnectionController controller,
            BottomSheetDialog dialog,
            NestedScrollView scrollView,
            LinearLayout listContainer,
            List<StremioAddons.CuratedAddon> items,
            boolean rtl) {}

    private static WeakReference<ActiveSheetState> activeStateRef = new WeakReference<>(null);
    private static final Set<String> installingManifestUrls = Collections.synchronizedSet(new HashSet<>());

    private CuratedAddonsSheet() {}

    static void onAddonInstallResult(String normalizedUrl, boolean ok) {
        if (normalizedUrl == null || normalizedUrl.isBlank()) {
            installingManifestUrls.clear();
        } else {
            installingManifestUrls.remove(normalizedUrl.toLowerCase(Locale.ROOT));
        }
        refreshActiveIfShowing();
    }

    static void refreshActiveIfShowing() {
        ActiveSheetState state = activeStateRef.get();
        if (state == null || state.host() == null || state.host().isDestroyed()
                || state.dialog() == null || !state.dialog().isShowing()) {
            return;
        }
        int savedScrollY = state.scrollView().getScrollY();
        renderCards(state);
        if (savedScrollY > 0) {
            state.scrollView().post(() -> state.scrollView().scrollTo(0, savedScrollY));
        }
    }

    static void show(TvHubActivity host, TvHubConnectionController controller, List<StremioAddons.CuratedAddon> items) {
        if (host == null || host.isFinishing() || host.isDestroyed() || items == null || items.isEmpty()) return;
        ActiveSheetState prev = activeStateRef.get();
        if (prev != null && prev.dialog() != null && prev.dialog().isShowing()) {
            prev.dialog().dismiss();
        }

        boolean rtl = host.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        var dialog = new BottomSheetDialog(host);
        var behavior = dialog.getBehavior();

        var root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setPaddingRelative(host.dp(16), host.dp(14), host.dp(16), host.dp(18));

        var sheetBg = new GradientDrawable();
        sheetBg.setColor(BG_INK);
        float topCorner = host.dp(24);
        sheetBg.setCornerRadii(new float[]{topCorner, topCorner, topCorner, topCorner, 0f, 0f, 0f, 0f});
        sheetBg.setStroke(Math.max(1, host.dp(1)), 0xff69532c);
        root.setBackground(sheetBg);

        var headerArea = new LinearLayout(host);
        headerArea.setOrientation(LinearLayout.VERTICAL);
        headerArea.setOnTouchListener((v, ev) -> {
            behavior.setDraggable(true);
            return false;
        });

        var handle = new View(host);
        var handleBg = new GradientDrawable();
        handleBg.setColor(0xff7b7368);
        handleBg.setCornerRadius(host.dp(2));
        handle.setBackground(handleBg);
        var handleLp = new LinearLayout.LayoutParams(host.dp(40), host.dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = host.dp(12);
        headerArea.addView(handle, handleLp);

        var heading = host.label(host.l("الإضافات الجاهزة للشاشة", "Curated TV Add-ons"), 19);
        heading.setTextColor(GOLD);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setAccessibilityHeading(true);
        headerArea.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        var subHeading = host.label(host.l(
                "اضغط للتثبيت الفوري على التلفاز، أو لفتح صفحة الإعداد في متصفح التطبيق والتقاط رابط التثبيت تلقائياً.",
                "Tap for 1-click TV install, or open configure pages in CinemaBrowser to capture stremio:// automatically."), 12);
        subHeading.setTextColor(0xffc9c2b6);
        var subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = host.dp(4);
        subLp.bottomMargin = host.dp(12);
        headerArea.addView(subHeading, subLp);
        root.addView(headerArea, new LinearLayout.LayoutParams(-1, -2));

        var scroll = new NestedScrollView(host) {
            private boolean startedAtTop;
            private float downRawY;

            @Override
            protected void onScrollChanged(int l, int t, int oldl, int oldt) {
                super.onScrollChanged(l, t, oldl, oldt);
                if (t > 0) {
                    behavior.setDraggable(false);
                }
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    startedAtTop = getScrollY() == 0 && !canScrollVertically(-1);
                    downRawY = ev.getRawY();
                    if (!startedAtTop) {
                        behavior.setDraggable(false);
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                } else if (action == MotionEvent.ACTION_MOVE) {
                    float dy = ev.getRawY() - downRawY;
                    boolean pullingDownFromTop = startedAtTop && getScrollY() == 0 && !canScrollVertically(-1) && dy > 0;
                    behavior.setDraggable(pullingDownFromTop);
                    if (!pullingDownFromTop && getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    behavior.setDraggable(getScrollY() == 0 && !canScrollVertically(-1));
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        ViewCompat.setNestedScrollingEnabled(scroll, true);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setVerticalScrollBarEnabled(false);

        var list = new LinearLayout(host);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new NestedScrollView.LayoutParams(-1, -2));
        int maxSheetHeight = Math.round(host.getResources().getDisplayMetrics().heightPixels * 0.68f);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, maxSheetHeight));

        var state = new ActiveSheetState(host, controller, dialog, scroll, list, items, rtl);
        activeStateRef = new WeakReference<>(state);
        renderCards(state);

        dialog.setOnDismissListener(d -> {
            if (activeStateRef.get() == state) {
                activeStateRef.clear();
            }
        });
        dialog.setContentView(root);
        behavior.setSkipCollapsed(true);
        behavior.setDraggable(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        View sheetView = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheetView != null) sheetView.setBackgroundColor(Color.TRANSPARENT);
        dialog.show();
    }

    private static void renderCards(ActiveSheetState state) {
        LinearLayout list = state.listContainer();
        list.removeAllViews();
        for (StremioAddons.CuratedAddon item : state.items()) {
            list.addView(buildAddonCard(state, item));
        }
    }

    private static View buildAddonCard(ActiveSheetState state, StremioAddons.CuratedAddon item) {
        TvHubActivity host = state.host();
        TvHubConnectionController controller = state.controller();
        boolean rtl = state.rtl();
        boolean installed = controller.isCuratedAddonInstalled(item);
        String normManifest = TvHubConnectionController.normalizeManifestSafe(item.manifestUrl);
        boolean installing = !installed && !normManifest.isEmpty() && installingManifestUrls.contains(normManifest);

        var card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        card.setClickable(true);
        card.setFocusable(true);
        card.setPaddingRelative(host.dp(14), host.dp(11), host.dp(14), host.dp(11));

        var cardBg = new GradientDrawable();
        cardBg.setColor(installed ? CARD_BG_INSTALLED : CARD_BG);
        cardBg.setCornerRadius(host.dp(14));
        cardBg.setStroke(Math.max(1, host.dp(1)), installed ? CARD_STROKE_INSTALLED : CARD_STROKE);
        card.setBackground(cardBg);

        // Top row: Category Badge + Installed / Action Status Badge
        var badgeRow = new LinearLayout(host);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setGravity(Gravity.CENTER_VERTICAL);

        String catLabel = rtl ? item.categoryAr : item.categoryEn;
        int catColor = categoryAccent(item.categoryEn);
        badgeRow.addView(pill(host, catLabel, catColor, (catColor & 0x00ffffff) | 0x24000000,
                (catColor & 0x00ffffff) | 0x55000000));

        badgeRow.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1f));

        boolean needsCfg = item.requiresConfigure();
        if (installed) {
            String instText = host.l("مثبّتة ✓", "Installed ✓");
            badgeRow.addView(pill(host, instText, INSTALLED_FG, INSTALLED_BG, INSTALLED_BORDER));
        } else if (installing) {
            String waitText = host.l("⏳ جارٍ التثبيت…", "⏳ Installing…");
            badgeRow.addView(pill(host, waitText, GOLD, 0x33e5a93b, 0x88e5a93b));
        } else {
            String modeLabel = needsCfg
                    ? host.l("⚙ إعداد بالمتصفح", "⚙ Configure")
                    : host.l("⚡ تثبيت فوري", "⚡ Direct TV");
            int modeColor = needsCfg ? 0xff93c5fd : GOLD;
            int modeBg = needsCfg ? 0x263b82f6 : 0x26e5a93b;
            badgeRow.addView(pill(host, modeLabel, modeColor, modeBg, (modeColor & 0x00ffffff) | 0x55000000));
        }
        card.addView(badgeRow, new LinearLayout.LayoutParams(-1, -2));

        // Bilingual Title Row: Arabic (RTL) + English (LTR)
        var titleRow = new LinearLayout(host);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        var titleRowLp = new LinearLayout.LayoutParams(-1, -2);
        titleRowLp.topMargin = host.dp(7);

        String arClean = extractArabicPart(item.nameAr, item.nameEn);
        String enClean = item.nameEn;

        var primaryTitle = new TextView(host);
        primaryTitle.setTextSize(15f);
        primaryTitle.setTypeface(Typeface.DEFAULT_BOLD);
        primaryTitle.setTextColor(0xffffffff);
        primaryTitle.setSingleLine(true);
        primaryTitle.setEllipsize(TextUtils.TruncateAt.END);

        var secondaryTitle = new TextView(host);
        secondaryTitle.setTextSize(12.5f);
        secondaryTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        secondaryTitle.setTextColor(installed ? 0xffa7f3d0 : 0xffe8c574);
        secondaryTitle.setSingleLine(true);
        secondaryTitle.setEllipsize(TextUtils.TruncateAt.END);

        if (rtl) {
            primaryTitle.setText(arClean);
            primaryTitle.setTextDirection(View.TEXT_DIRECTION_RTL);
            secondaryTitle.setText(enClean);
            secondaryTitle.setTextDirection(View.TEXT_DIRECTION_LTR);
        } else {
            primaryTitle.setText(enClean);
            primaryTitle.setTextDirection(View.TEXT_DIRECTION_LTR);
            secondaryTitle.setText(arClean);
            secondaryTitle.setTextDirection(View.TEXT_DIRECTION_RTL);
        }

        titleRow.addView(primaryTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        if (!arClean.equalsIgnoreCase(enClean) && !arClean.isBlank() && !enClean.isBlank()) {
            var secLp = new LinearLayout.LayoutParams(-2, -2);
            secLp.setMarginStart(host.dp(8));
            titleRow.addView(secondaryTitle, secLp);
        }
        card.addView(titleRow, titleRowLp);

        // Single-line short description
        String descText = rtl ? item.descriptionAr : item.descriptionEn;
        if (!descText.isBlank()) {
            var desc = new TextView(host);
            desc.setText(descText);
            desc.setTextSize(12.5f);
            desc.setTextColor(0xffc9c3b8);
            desc.setSingleLine(true);
            desc.setMaxLines(1);
            desc.setEllipsize(TextUtils.TruncateAt.END);
            desc.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
            var descLp = new LinearLayout.LayoutParams(-1, -2);
            descLp.topMargin = host.dp(4);
            card.addView(desc, descLp);
        }

        String displayTitle = rtl ? arClean : enClean;
        card.setOnClickListener(v -> {
            if (needsCfg) {
                state.dialog().dismiss();
                host.openInAppBrowser(item.configureUrl);
                return;
            }
            if (controller.isCuratedAddonInstalled(item)) {
                CinemaSnackBanner.show(host, host.l(
                        "الإضافة «" + displayTitle + "» مثبّتة ومفعّلة بالفعل على الشاشة ✓",
                        "\"" + displayTitle + "\" is already installed on TV ✓"));
                return;
            }
            if (!normManifest.isEmpty() && installingManifestUrls.contains(normManifest)) {
                return;
            }
            if (!normManifest.isEmpty()) {
                installingManifestUrls.add(normManifest);
                refreshActiveIfShowing();
            }
            boolean dispatched = controller.sendAddonInstallToTv(item.manifestUrl, displayTitle);
            if (!dispatched && !normManifest.isEmpty()) {
                installingManifestUrls.remove(normManifest);
                refreshActiveIfShowing();
            }
        });

        var lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = host.dp(8);
        card.setLayoutParams(lp);
        return card;
    }

    private static String extractArabicPart(String nameAr, String nameEn) {
        if (nameAr == null || nameAr.isBlank()) return nameEn == null ? "" : nameEn;
        int dotIdx = nameAr.indexOf('·');
        if (dotIdx >= 0 && dotIdx + 1 < nameAr.length()) {
            String right = nameAr.substring(dotIdx + 1).trim();
            if (!right.isEmpty()) return right;
        }
        return nameAr.trim();
    }

    private static int categoryAccent(String categoryEn) {
        String c = categoryEn == null ? "" : categoryEn.toLowerCase(Locale.ROOT);
        if (c.contains("subtitle")) return 0xff5eead4;
        if (c.contains("http") || c.contains("direct")) return 0xff7dd3fc;
        if (c.contains("live") || c.contains("sport")) return 0xfffca5a5;
        if (c.contains("catalog") || c.contains("anime") || c.contains("meta")) return 0xffd8b4fe;
        if (c.contains("backup")) return 0xfffdba74;
        return GOLD;
    }

    private static TextView pill(TvHubActivity host, String text, int fgColor, int bgColor, int strokeColor) {
        var tv = new TextView(host);
        tv.setText(text);
        tv.setTextSize(11.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(fgColor);
        tv.setSingleLine(true);
        tv.setPaddingRelative(host.dp(10), host.dp(3), host.dp(10), host.dp(3));
        var bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(host.dp(10));
        bg.setStroke(Math.max(1, host.dp(1)), strokeColor);
        tv.setBackground(bg);
        return tv;
    }
}

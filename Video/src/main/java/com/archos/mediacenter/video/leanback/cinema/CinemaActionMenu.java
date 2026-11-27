package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.leanback.search.VideoSearchActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.arabic;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.dp;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.label;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.motion;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.rounded;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.text;

/**
 * Anchored side panel & contextual action sheet for TV navigation.
 * Renders an in-window soft dim scrim + #171717/#1E1E1E surface with header, vector icons,
 * unclipped >= 12dp focus padding, #E5A93B gold focus cards, and exact focus restoration.
 */
final class CinemaActionMenu {
    private static final String TAG_OVERLAY = "cinema_action_panel_overlay";
    private static final int GOLD_ACCENT = 0xffe5a93b;
    private static final int PANEL_SURFACE = 0xff19191c;
    private static final int PANEL_BORDER = 0xff2e2e2e;
    private static final int CARD_IDLE = 0xff222227;
    private static final int CARD_BORDER = 0xff303036;
    private static final int ON_GOLD = 0xff141517;

    record Action(int iconRes, String label, String subtitle, Runnable command, Runnable longCommand) {
        Action(String label, Runnable command) {
            this(R.drawable.cinema_ic_system, label, null, command, null);
        }

        Action(int iconRes, String label, Runnable command) {
            this(iconRes, label, null, command, null);
        }

        Action(int iconRes, String label, String subtitle, Runnable command) {
            this(iconRes, label, subtitle, command, null);
        }
    }

    private CinemaActionMenu() {}

    static boolean isShowing(ViewGroup root) {
        return root != null && root.findViewWithTag(TAG_OVERLAY) != null;
    }

    static boolean isPanelDescendant(View view, ViewGroup root) {
        if (view == null || root == null) return false;
        View overlay = root.findViewWithTag(TAG_OVERLAY);
        if (overlay == null) return false;
        for (ViewParent p = view.getParent(); p != null; p = p.getParent()) {
            if (p == overlay) return true;
        }
        return view == overlay;
    }

    static boolean dismiss(ViewGroup root) {
        return dismiss(root, true);
    }

    static boolean dismiss(ViewGroup root, boolean restoreAnchorFocus) {
        if (root == null) return false;
        View existing = root.findViewWithTag(TAG_OVERLAY);
        if (existing == null) return false;
        Object dismissHook = existing.getTag(R.id.text1);
        if (dismissHook instanceof java.util.function.Consumer<?> consumer) {
            @SuppressWarnings("unchecked")
            var typed = (java.util.function.Consumer<Boolean>) consumer;
            typed.accept(restoreAnchorFocus);
            return true;
        }
        root.removeView(existing);
        return true;
    }

    /** Opens the standard TV Search submenu docked beside an expanded sidebar rail. */
    static void showSearchSidebarPanel(Activity activity, FrameLayout root, View anchor,
                                       View railContainer, Runnable onOpen, Runnable onClose) {
        if (activity == null || root == null || anchor == null) return;
        List<Action> actions = new ArrayList<>();
        actions.add(new Action(
                R.drawable.cinema_search,
                label(activity, "البحث الشامل في المكتبة", "Search Entire Library"),
                label(activity, "أفلام ومسلسلات وملفات الشبكة والتخزين", "Movies, TV shows & local/SMB files"),
                () -> activity.startActivity(new Intent(activity, VideoSearchActivity.class)
                        .putExtra(VideoSearchActivity.EXTRA_SEARCH_MODE, VideoSearchActivity.SEARCH_MODE_ALL))));
        actions.add(new Action(
                R.drawable.cinema_play_movie,
                label(activity, "البحث في الأفلام", "Search Movies"),
                label(activity, "الأفلام المفهرسة فقط", "Indexed movies only"),
                () -> activity.startActivity(new Intent(activity, VideoSearchActivity.class)
                        .putExtra(VideoSearchActivity.EXTRA_SEARCH_MODE, VideoSearchActivity.SEARCH_MODE_MOVIE))));
        actions.add(new Action(
                R.drawable.cinema_ic_posters,
                label(activity, "البحث في المسلسلات", "Search TV Shows"),
                label(activity, "المسلسلات والحلقات", "Series and episodes"),
                () -> activity.startActivity(new Intent(activity, VideoSearchActivity.class)
                        .putExtra(VideoSearchActivity.EXTRA_SEARCH_MODE, VideoSearchActivity.SEARCH_MODE_EPISODE))));
        actions.add(new Action(
                R.drawable.cinema_addons,
                label(activity, "البحث في الإضافات والقنوات", "Search Add-ons & Live TV"),
                label(activity, "كتالوجات Stremio والقنوات المباشرة", "Stremio catalogs & live channels"),
                () -> activity.startActivity(new Intent(activity, CinemaAddonsActivity.class))));
        actions.add(new Action(
                R.drawable.cinema_subtitles,
                label(activity, "البحث الذكي عن الترجمة", "Smart Subtitle Search"),
                label(activity, "بحث موحّد في جميع مصادر وإضافات الترجمة", "Search across all subtitle sources & add-ons"),
                () -> activity.startActivity(new Intent(activity, SubsourceSearchActivity.class)
                        .putExtra("both_providers", true))));
        showSidebarPanel(root, anchor, railContainer, R.drawable.cinema_search,
                activity.getString(R.string.cinema_search), onOpen, onClose, actions);
    }

    /** Shows an anchored side panel docked next to the expanded sidebar rail without collapsing it. */
    static void showSidebarPanel(FrameLayout root, View anchor, View railContainer,
                                 int headerIconRes, String headerTitle,
                                 Runnable onOpen, Runnable onClose, List<Action> actions) {
        showInternal(root, anchor, railContainer, true, headerIconRes, headerTitle, onOpen, onClose, actions);
    }

    static void show(View anchor, Action... actions) {
        show(anchor, R.drawable.cinema_ic_system,
                label(anchor.getContext(), "خيارات إضافية", "More Actions"), actions);
    }

    static void show(View anchor, int headerIconRes, String headerTitle, Action... actions) {
        FrameLayout hostRoot = resolveHostFrame(anchor);
        if (hostRoot == null || actions == null || actions.length == 0) return;
        showInternal(hostRoot, anchor, null, false, headerIconRes, headerTitle,
                null, null, Arrays.asList(actions));
    }

    private static FrameLayout resolveHostFrame(View anchor) {
        if (anchor == null) return null;
        Context ctx = anchor.getContext();
        if (ctx instanceof Activity act) {
            View content = act.findViewById(android.R.id.content);
            if (content instanceof FrameLayout fl) return fl;
        }
        View root = anchor.getRootView();
        if (root != null) {
            View content = root.findViewById(android.R.id.content);
            if (content instanceof FrameLayout fl) return fl;
            if (root instanceof FrameLayout fl) return fl;
        }
        return null;
    }

    private static void showInternal(FrameLayout root, View anchor, View railContainer, boolean sidebarDocked,
                                     int headerIconRes, String headerTitle,
                                     Runnable onOpen, Runnable onClose, List<Action> actions) {
        if (root == null || anchor == null || actions == null || actions.isEmpty()) return;
        dismiss(root, false);

        Context context = root.getContext();
        boolean rtl = arabic(context);
        if (onOpen != null) onOpen.run();

        // Keep active sidebar tile visibly highlighted while its submenu is open
        android.graphics.drawable.Drawable savedAnchorBg = anchor.getBackground();
        float savedRailZ = railContainer != null ? railContainer.getTranslationZ() : 0f;
        if (sidebarDocked) {
            GradientDrawable activePill = new GradientDrawable();
            activePill.setColor(0x38e5a93b);
            activePill.setCornerRadius(dp(context, 8));
            activePill.setStroke(Math.max(1, dp(context, 1.5f)), GOLD_ACCENT);
            anchor.setBackground(activePill);
            if (anchor instanceof TextView tv) {
                tv.setTextColor(GOLD_ACCENT);
                tv.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(GOLD_ACCENT));
            }
        }

        FrameLayout overlay = new FrameLayout(context);
        overlay.setTag(TAG_OVERLAY);
        overlay.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);
        overlay.setClickable(true);
        overlay.setFocusable(false);
        overlay.setBackgroundColor(sidebarDocked ? 0xbd0d0e11 : 0xc80d0e11);
        overlay.setElevation(dp(context, 12));

        // Elevate expanded sidebar above the background scrim so sidebar + docked panel form one cohesive unit
        if (sidebarDocked && railContainer != null) {
            railContainer.setTranslationZ(dp(context, 14));
        }

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        panel.setClipChildren(true);
        panel.setClipToPadding(true);
        panel.setClipToOutline(true);
        panel.setClickable(true);
        panel.setElevation(dp(context, 18));

        GradientDrawable panelBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff1e1e22, CinemaTokens.CANVAS});
        panelBg.setCornerRadius(dp(context, 18));
        panelBg.setStroke(Math.max(1, dp(context, 1)), PANEL_BORDER);
        panel.setBackground(panelBg);

        // Header: icon + title + count pill + subtle 1dp #2E2E2E divider
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPaddingRelative(dp(context, 18), dp(context, 15), dp(context, 18), dp(context, 13));

        ImageView headerIcon = new ImageView(context);
        var hDraw = ContextCompat.getDrawable(context, headerIconRes != 0 ? headerIconRes : R.drawable.cinema_ic_system);
        if (hDraw != null) {
            hDraw = hDraw.mutate();
            hDraw.setTint(GOLD_ACCENT);
            headerIcon.setImageDrawable(hDraw);
        }
        var hIconLp = new LinearLayout.LayoutParams(dp(context, 22), dp(context, 22));
        hIconLp.setMarginEnd(dp(context, 12));
        header.addView(headerIcon, hIconLp);

        TextView titleTv = text(context, 18, true);
        titleTv.setText(headerTitle == null ? "" : headerTitle);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        titleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        header.addView(titleTv, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView countBadge = text(context, 12, true);
        countBadge.setText(String.valueOf(actions.size()));
        countBadge.setTextColor(GOLD_ACCENT);
        countBadge.setGravity(Gravity.CENTER);
        countBadge.setPaddingRelative(dp(context, 9), dp(context, 3), dp(context, 9), dp(context, 3));
        countBadge.setBackground(rounded(0xff25221b, dp(context, 10), 0xff4a3d24));
        var badgeLp = new LinearLayout.LayoutParams(-2, -2);
        badgeLp.setMarginStart(dp(context, 8));
        header.addView(countBadge, badgeLp);
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        View divider = new View(context);
        divider.setBackgroundColor(PANEL_BORDER);
        var divLp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(context, 1)));
        divLp.setMarginStart(dp(context, 14));
        divLp.setMarginEnd(dp(context, 14));
        panel.addView(divider, divLp);

        // Scrollable viewport clips vertical overflow cleanly while inner listBox keeps >= 12dp unclipped focus padding
        int focusPad = dp(context, 12);
        ScrollView scroll = new ScrollView(context);
        scroll.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        scroll.setFillViewport(false);

        LinearLayout listBox = new LinearLayout(context);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        listBox.setClipChildren(false);
        listBox.setClipToPadding(false);
        listBox.setPadding(focusPad, focusPad, focusPad, focusPad);
        scroll.addView(listBox, new FrameLayout.LayoutParams(-1, -2));

        int screenH = context.getResources().getDisplayMetrics().heightPixels;
        int maxScrollH = Math.round(screenH * 0.74f);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2) {
            {
                height = -2;
            }
        });

        final boolean[] closed = {false};
        java.util.function.Consumer<Boolean> closePanel = restoreFocus -> {
            if (closed[0]) return;
            closed[0] = true;
            if (sidebarDocked) {
                anchor.setBackground(savedAnchorBg);
                if (anchor instanceof TextView tv) {
                    CinemaUi.iconOnly(tv);
                    int iconSize = dp(context, CinemaTokens.ICON);
                    var drawables = tv.getCompoundDrawablesRelative();
                    if (drawables[0] != null) drawables[0].setBounds(0, 0, iconSize, iconSize);
                }
                if (railContainer != null) {
                    railContainer.setTranslationZ(savedRailZ);
                }
            }
            if (Boolean.TRUE.equals(restoreFocus) && anchor.isAttachedToWindow()) {
                anchor.requestFocus();
            }
            if (onClose != null) onClose.run();
            if (overlay.getParent() instanceof ViewGroup parent) {
                parent.removeView(overlay);
            }
        };
        overlay.setTag(R.id.text1, closePanel);
        overlay.setOnClickListener(v -> closePanel.accept(true));

        List<LinearLayout> rowViews = new ArrayList<>(actions.size());
        for (int idx = 0; idx < actions.size(); idx++) {
            Action action = actions.get(idx);
            LinearLayout card = buildActionCard(context, rtl, action, scroll, focusPad, closePanel);
            card.setId(View.generateViewId());
            var lp = new LinearLayout.LayoutParams(-1, -2);
            if (idx < actions.size() - 1) lp.bottomMargin = dp(context, 8);
            listBox.addView(card, lp);
            rowViews.add(card);
        }

        // Clamp vertical & horizontal D-pad inside the panel so focus never leaks into background views
        for (int idx = 0; idx < rowViews.size(); idx++) {
            LinearLayout cur = rowViews.get(idx);
            LinearLayout prev = idx > 0 ? rowViews.get(idx - 1) : cur;
            LinearLayout next = idx < rowViews.size() - 1 ? rowViews.get(idx + 1) : cur;
            cur.setNextFocusUpId(prev.getId());
            cur.setNextFocusDownId(next.getId());
            cur.setNextFocusLeftId(cur.getId());
            cur.setNextFocusRightId(cur.getId());
        }

        int panelW = dp(context, sidebarDocked ? 356 : 368);
        FrameLayout.LayoutParams panelLp;
        if (sidebarDocked) {
            panelLp = new FrameLayout.LayoutParams(
                    panelW, -2, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            int dockedOffset = dp(context, CinemaTokens.RAIL_LEFT + CinemaTokens.RAIL_OPEN + 12);
            if (rtl) panelLp.rightMargin = dockedOffset;
            else panelLp.leftMargin = dockedOffset;
            panelLp.topMargin = dp(context, 28);
            panelLp.bottomMargin = dp(context, 28);
        } else {
            panelLp = new FrameLayout.LayoutParams(panelW, -2, Gravity.CENTER);
        }
        overlay.addView(panel, panelLp);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        // Constrain scroll height cleanly when there are many library folders
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (scroll.getHeight() <= 0) return;
                scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (scroll.getHeight() > maxScrollH) {
                    var lp = scroll.getLayoutParams();
                    lp.height = maxScrollH;
                    scroll.setLayoutParams(lp);
                }
            }
        });

        if (motion(context)) {
            overlay.setAlpha(0f);
            panel.setTranslationX(rtl ? dp(context, 14) : -dp(context, 14));
            overlay.animate().alpha(1f).setDuration(130).start();
            panel.animate().translationX(0f).setDuration(150)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        }

        if (!rowViews.isEmpty()) {
            LinearLayout first = rowViews.get(0);
            first.requestFocus();
            first.post(() -> {
                if (!closed[0] && first.isAttachedToWindow()) first.requestFocus();
            });
        }
    }

    private static LinearLayout buildActionCard(Context context, boolean rtl, Action action,
                                                ScrollView scroll, int focusPad,
                                                java.util.function.Consumer<Boolean> closePanel) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 54));
        row.setPaddingRelative(dp(context, 14), dp(context, 11), dp(context, 14), dp(context, 11));
        row.setFocusable(true);
        row.setClickable(true);
        row.setLongClickable(action.longCommand() != null);

        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focusedBg = rounded(GOLD_ACCENT, dp(context, 12), 0xffffd875);
        focusedBg.setStroke(dp(context, 2), 0xffffe394);
        GradientDrawable idleBg = rounded(CARD_IDLE, dp(context, 12), CARD_BORDER);
        idleBg.setStroke(Math.max(1, dp(context, 1)), CARD_BORDER);
        bg.addState(new int[]{android.R.attr.state_focused}, focusedBg);
        bg.addState(new int[]{}, idleBg);
        row.setBackground(bg);

        FrameLayout iconBox = new FrameLayout(context);
        GradientDrawable iconBoxIdle = rounded(0xff2b2b32, dp(context, 9), 0);
        GradientDrawable iconBoxFocus = rounded(0x26141517, dp(context, 9), 0);
        iconBox.setBackground(iconBoxIdle);
        ImageView iconView = new ImageView(context);
        var drawable = ContextCompat.getDrawable(context,
                action.iconRes() != 0 ? action.iconRes() : R.drawable.cinema_ic_system);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setTint(GOLD_ACCENT);
            iconView.setImageDrawable(drawable);
        }
        var innerIconLp = new FrameLayout.LayoutParams(dp(context, 20), dp(context, 20), Gravity.CENTER);
        iconBox.addView(iconView, innerIconLp);
        var iconBoxLp = new LinearLayout.LayoutParams(dp(context, 36), dp(context, 36));
        iconBoxLp.setMarginEnd(dp(context, 12));
        row.addView(iconBox, iconBoxLp);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleTv = text(context, 15, true);
        titleTv.setText(action.label());
        titleTv.setTextColor(Color.WHITE);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        titleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        textCol.addView(titleTv, new LinearLayout.LayoutParams(-1, -2));

        TextView subTv = null;
        if (action.subtitle() != null && !action.subtitle().isBlank()) {
            subTv = text(context, 12, false);
            subTv.setText(action.subtitle());
            subTv.setTextColor(0xffa6a6b2);
            subTv.setSingleLine(true);
            subTv.setEllipsize(TextUtils.TruncateAt.END);
            subTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            var subLp = new LinearLayout.LayoutParams(-1, -2);
            subLp.topMargin = dp(context, 2);
            textCol.addView(subTv, subLp);
        }
        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView chevron = text(context, 15, true);
        chevron.setText(rtl ? "‹" : "›");
        chevron.setTextColor(0xff8e8e9a);
        var chevLp = new LinearLayout.LayoutParams(-2, -2);
        chevLp.setMarginStart(dp(context, 8));
        row.addView(chevron, chevLp);

        final TextView finalSub = subTv;
        row.setOnFocusChangeListener((v, hasFocus) -> {
            titleTv.setTextColor(hasFocus ? ON_GOLD : Color.WHITE);
            if (finalSub != null) finalSub.setTextColor(hasFocus ? 0xff2d2410 : 0xffa6a6b2);
            chevron.setTextColor(hasFocus ? ON_GOLD : 0xff8e8e9a);
            iconBox.setBackground(hasFocus ? iconBoxFocus : iconBoxIdle);
            if (iconView.getDrawable() != null) {
                iconView.getDrawable().setTint(hasFocus ? ON_GOLD : GOLD_ACCENT);
            }
            v.animate().scaleX(hasFocus ? 1.02f : 1f).scaleY(hasFocus ? 1.02f : 1f)
                    .setDuration(motion(context) ? 100 : 0).start();
            if (hasFocus) {
                scroll.post(() -> {
                    int top = v.getTop();
                    int bottom = v.getBottom() + focusPad;
                    int curY = scroll.getScrollY();
                    int visH = scroll.getHeight();
                    if (visH > 0) {
                        if (bottom > curY + visH) scroll.smoothScrollTo(0, bottom - visH);
                        else if (top < curY + focusPad) scroll.smoothScrollTo(0, Math.max(0, top - focusPad));
                    }
                });
            }
        });

        row.setOnClickListener(v -> {
            closePanel.accept(true);
            if (action.command() != null) action.command().run();
        });
        if (action.longCommand() != null) {
            row.setOnLongClickListener(v -> {
                closePanel.accept(true);
                action.longCommand().run();
                return true;
            });
        }
        int backToSidebarKey = rtl ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
        row.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    closePanel.accept(true);
                }
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == backToSidebarKey) {
                closePanel.accept(true);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MENU && action.longCommand() != null) {
                closePanel.accept(true);
                action.longCommand().run();
                return true;
            }
            return false;
        });
        return row;
    }
}

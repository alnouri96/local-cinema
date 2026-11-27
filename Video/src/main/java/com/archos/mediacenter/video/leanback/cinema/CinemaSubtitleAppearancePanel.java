package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.player.SubtitleTextView;
import com.meedx.providers.UnifiedSubtitleConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.arabic;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.dialog;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.dp;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.label;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.rounded;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.text;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.widenSubtitleDialog;

/**
 * Single Unified Subtitle Appearance & Timing Panel shared identically across:
 * - Main Settings / Unified Subtitle Settings (SubsourceSettingsActivity / CinemaSettingsActivity)
 * - Movie Details, Series Details & Add-on Episodes (via CinemaSubtitleHubDialog)
 * - Live Playback in Native Player (PlayerActivity) & Adaptive HLS/Torrent Player (CinemaHlsActivity)
 *
 * Features:
 * - WYSIWYG Live Bilingual Preview (Arabic + English) powered by SubtitleTextView
 * - Compact Color Picker button opening CinemaSubtitleColorPickerDialog (9 colors)
 * - Word-bounded Dark Background toggle + 0%..100% Opacity stepper (10% steps)
 * - Unified Outline / Stroke card combining Size (None/Thin/Medium/Thick) + Color (5 colors)
 * - Dedicated Subtitle Timing row with automatic translucent menu mode during live playback
 *   so video & live captions remain clearly visible behind the controls until Back restores full opacity.
 */
public final class CinemaSubtitleAppearancePanel {

    public static final class Config {
        public boolean livePlayback;
        public boolean hasExplicitState;
        public long initialDelayMs;
        public int initialSize = 36;
        public int initialVpos = 64;
        public int initialColor = Color.WHITE;
        public boolean initialOpaqueBg = false;
        public int initialBgOpacity = 70;
        public int initialOutlineLevel = 0;
        public int initialOutlineColor = Color.BLACK;
        public boolean initialVisible = true;
        public CinemaSubtitleHubDialog.StyleCallback styleCallback;
        public Runnable onOpenLiveTimingOverlay;
    }

    public static final class Handle {
        public final View firstFocusable;
        private AlertDialog hostDialog;
        private Drawable originalWindowBg;
        private float originalDimAmount = 0.6f;
        private boolean translucentTimingActive;
        private final List<View> nonTimingViews = new ArrayList<>();
        private Runnable restoreUiCallback;

        Handle(View firstFocusable) {
            this.firstFocusable = firstFocusable;
        }

        /** Binds the hosting AlertDialog so Live Timing adjustments can make menus translucent over video. */
        public void attachHostDialog(AlertDialog dlg, List<View> externalSiblingViews) {
            this.hostDialog = dlg;
            if (externalSiblingViews != null) {
                for (View v : externalSiblingViews) {
                    if (v != null && !nonTimingViews.contains(v)) {
                        nonTimingViews.add(v);
                    }
                }
            }
            if (dlg == null) return;
            dlg.setOnKeyListener((d, keyCode, event) -> {
                if (translucentTimingActive
                        && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BUTTON_B)) {
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        setTranslucentTimingMode(false);
                    }
                    return true;
                }
                return false;
            });
        }

        void setTranslucentTimingMode(boolean active) {
            if (hostDialog == null || translucentTimingActive == active) return;
            translucentTimingActive = active;
            Window win = hostDialog.getWindow();
            if (win != null) {
                if (active) {
                    if (originalWindowBg == null && win.getDecorView() != null) {
                        originalWindowBg = win.getDecorView().getBackground();
                    }
                    WindowManager.LayoutParams lp = win.getAttributes();
                    originalDimAmount = lp.dimAmount;
                    lp.dimAmount = 0.04f;
                    win.setAttributes(lp);
                    win.setBackgroundDrawable(rounded(0x30171717, dp(hostDialog.getContext(), 16), 0x66E5A93B));
                } else {
                    WindowManager.LayoutParams lp = win.getAttributes();
                    lp.dimAmount = originalDimAmount > 0f ? originalDimAmount : 0.55f;
                    win.setAttributes(lp);
                    if (originalWindowBg != null) {
                        win.setBackgroundDrawable(originalWindowBg);
                    } else {
                        win.setBackgroundDrawable(rounded(0xF2171717, dp(hostDialog.getContext(), 16), 0x884A4435));
                    }
                }
            }
            float targetAlpha = active ? 0.10f : 1.0f;
            for (View v : nonTimingViews) {
                if (v != null) v.animate().alpha(targetAlpha).setDuration(140).start();
            }
            if (restoreUiCallback != null) restoreUiCallback.run();
        }
    }

    private CinemaSubtitleAppearancePanel() {}

    /** Builds a default StyleCallback that reads/writes directly to UnifiedSubtitleConfig + TV prefs + Phone sync. */
    public static CinemaSubtitleHubDialog.StyleCallback defaultConfigStyleCallback(Activity activity) {
        return (action, value) -> {
            if (activity == null || action == null) return;
            int sz = UnifiedSubtitleConfig.textSize(activity);
            int vp = UnifiedSubtitleConfig.vpos(activity);
            int col = UnifiedSubtitleConfig.textColor(activity);
            boolean bg = UnifiedSubtitleConfig.opaqueBg(activity);
            int bgOp = UnifiedSubtitleConfig.bgOpacity(activity);
            int outLvl = UnifiedSubtitleConfig.outlineLevel(activity);
            int outCol = UnifiedSubtitleConfig.outlineColor(activity);
            long del = UnifiedSubtitleConfig.defaultDelayMs(activity);
            switch (action) {
                case "size" -> sz = Math.max(18, Math.min(80, sz + value));
                case "position" -> vp = Math.max(16, Math.min(200, vp + value));
                case "color" -> col = value;
                case "background" -> bg = !bg;
                case "bg_opacity" -> {
                    bgOp = value >= 0
                            ? UnifiedSubtitleConfig.normalizeBgOpacity(value)
                            : UnifiedSubtitleConfig.nextBgOpacity(bgOp);
                    if (bgOp > 0) bg = true;
                }
                case "outline" -> outLvl = value >= 0
                        ? Math.max(0, Math.min(3, value))
                        : UnifiedSubtitleConfig.nextOutlineLevel(outLvl);
                case "outline_color" -> {
                    outCol = UnifiedSubtitleConfig.normalizeOutlineColor(value);
                    if (outLvl == 0) outLvl = 2;
                }
                case "delay" -> del = Math.max(-30000L, Math.min(30000L, del + value));
                case "delay_reset" -> del = 0L;
                case "reset" -> {
                    sz = 36;
                    vp = 64;
                    col = Color.WHITE;
                    bg = false;
                    bgOp = 70;
                    outLvl = 0;
                    outCol = Color.BLACK;
                    del = 0L;
                }
                default -> { return; }
            }
            UnifiedSubtitleConfig.saveAppearance(activity, sz, vp, col, bg, bgOp, outLvl, outCol, del);
            CinemaSubtitleSyncBridge.onTvSubtitleConfigChanged(activity);
        };
    }

    /** Opens the standalone Unified Subtitle Appearance & Timing dialog (used in Settings). */
    public static AlertDialog showStandaloneDialog(Activity a, Runnable onDismissed) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return null;
        CinemaSubtitleSyncBridge.ensureBootstrapped(a);
        boolean ar = arabic(a);

        ScrollView scroll = new ScrollView(a);
        scroll.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        body.setPadding(dp(a, 18), dp(a, 10), dp(a, 18), dp(a, 14));
        scroll.addView(body);

        Config cfg = new Config();
        cfg.livePlayback = false;
        cfg.styleCallback = defaultConfigStyleCallback(a);

        final AlertDialog[] dlgRef = {null};
        Handle handle = attach(a, body, cfg, () -> dlgRef[0]);

        AlertDialog dlg = dialog(a)
                .setTitle(label(a, "مظهر وموضع وتوقيت الترجمة", "Subtitle Appearance, Position & Timing"))
                .setView(scroll)
                .setPositiveButton(label(a, "تم", "Done"), null)
                .create();
        dlgRef[0] = dlg;
        handle.attachHostDialog(dlg, null);
        dlg.setOnShowListener(d -> {
            widenSubtitleDialog(dlg.getWindow());
            if (handle.firstFocusable != null) handle.firstFocusable.requestFocus();
        });
        dlg.setOnDismissListener(d -> {
            CinemaSubtitleSyncBridge.onTvSubtitleConfigChanged(a);
            if (onDismissed != null) onDismissed.run();
        });
        dlg.show();
        return dlg;
    }

    /** Attaches the complete Unified Appearance & Timing section to {@code parent}. */
    public static Handle attach(
            Activity a,
            LinearLayout parent,
            Config cfg,
            java.util.function.Supplier<AlertDialog> hostDialogSupplier) {
        boolean ar = arabic(a);
        CinemaSubtitleHubDialog.StyleCallback cb = cfg.styleCallback != null
                ? cfg.styleCallback : defaultConfigStyleCallback(a);

        TextView headingTv = text(a, 13, true);
        headingTv.setText(ar ? "المظهر الفوري والتوقيت" : "Live Appearance & Timing");
        headingTv.setTextColor(CinemaTokens.GOLD);
        headingTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        headingTv.setPaddingRelative(dp(a, 4), dp(a, 8), dp(a, 4), dp(a, 6));
        parent.addView(headingTv);

        final int[] sizeState = {cfg.hasExplicitState && cfg.initialSize > 0
                ? cfg.initialSize : UnifiedSubtitleConfig.textSize(a)};
        final int[] vposState = {cfg.hasExplicitState && cfg.initialVpos > 0
                ? cfg.initialVpos : UnifiedSubtitleConfig.vpos(a)};
        final int[] colorState = {cfg.hasExplicitState
                ? cfg.initialColor : UnifiedSubtitleConfig.textColor(a)};
        final boolean[] bgState = {cfg.hasExplicitState
                ? cfg.initialOpaqueBg : UnifiedSubtitleConfig.opaqueBg(a)};
        final int[] bgOpacityState = {cfg.hasExplicitState
                ? UnifiedSubtitleConfig.normalizeBgOpacity(cfg.initialBgOpacity)
                : UnifiedSubtitleConfig.bgOpacity(a)};
        final int[] outlineLevelState = {cfg.hasExplicitState
                ? Math.max(0, Math.min(3, cfg.initialOutlineLevel))
                : UnifiedSubtitleConfig.outlineLevel(a)};
        final int[] outlineColorState = {cfg.hasExplicitState
                ? UnifiedSubtitleConfig.normalizeOutlineColor(cfg.initialOutlineColor)
                : UnifiedSubtitleConfig.outlineColor(a)};
        final boolean[] visibleState = {cfg.hasExplicitState ? cfg.initialVisible : true};
        final long[] delayState = {cfg.hasExplicitState
                ? cfg.initialDelayMs : UnifiedSubtitleConfig.defaultDelayMs(a)};

        // 1. Live Bilingual WYSIWYG Preview Box
        LinearLayout previewFrame = new LinearLayout(a);
        previewFrame.setOrientation(LinearLayout.VERTICAL);
        previewFrame.setGravity(Gravity.CENTER);
        previewFrame.setBackground(rounded(0xff0d0e11, dp(a, 10), 0xff3b3629));
        previewFrame.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10));
        LinearLayout.LayoutParams frameLp = new LinearLayout.LayoutParams(-1, -2);
        frameLp.bottomMargin = dp(a, 10);

        LinearLayout previewPill = new LinearLayout(a);
        previewPill.setOrientation(LinearLayout.VERTICAL);
        previewPill.setGravity(Gravity.CENTER);
        previewPill.setBackgroundColor(Color.TRANSPARENT);

        SubtitleTextView previewAr = new SubtitleTextView(a);
        previewAr.setText(a.getString(R.string.cinema_sub_preview_ar));
        previewAr.setTypeface(Typeface.DEFAULT_BOLD);
        previewAr.setGravity(Gravity.CENTER);
        previewAr.setMaxLines(2);
        previewPill.addView(previewAr, new LinearLayout.LayoutParams(-2, -2));

        SubtitleTextView previewEn = new SubtitleTextView(a);
        previewEn.setText(a.getString(R.string.cinema_sub_preview_en));
        previewEn.setTypeface(Typeface.DEFAULT_BOLD);
        previewEn.setGravity(Gravity.CENTER);
        previewEn.setMaxLines(2);
        LinearLayout.LayoutParams enLp = new LinearLayout.LayoutParams(-2, -2);
        enLp.topMargin = dp(a, 2);
        previewPill.addView(previewEn, enLp);

        TextView previewHiddenTv = text(a, 13, true);
        previewHiddenTv.setText(a.getString(R.string.cinema_sub_preview_hidden));
        previewHiddenTv.setTextColor(0xff9aa0aa);
        previewHiddenTv.setGravity(Gravity.CENTER);
        previewHiddenTv.setVisibility(View.GONE);

        previewFrame.addView(previewPill, new LinearLayout.LayoutParams(-2, -2));
        previewFrame.addView(previewHiddenTv, new LinearLayout.LayoutParams(-2, -2));
        parent.addView(previewFrame, frameLp);

        final Runnable[] refreshUi = {null};
        final Handle[] handleHolder = {null};

        Runnable exitTranslucentIfActive = () -> {
            if (handleHolder[0] != null && handleHolder[0].translucentTimingActive) {
                handleHolder[0].setTranslucentTimingMode(false);
            }
        };

        // 2. Row 1: Visibility (if live playback) + Compact Color Picker Button + Font Size + Vertical Position
        LinearLayout row1 = chipRow(a, parent);
        View firstFocus;

        TextView visibleChip = null;
        if (cfg.livePlayback) {
            visibleChip = addStatefulChip(a, row1, "", 1.1f,
                    () -> visibleState[0],
                    () -> {
                        exitTranslucentIfActive.run();
                        visibleState[0] = !visibleState[0];
                        cb.onAction("visible", 0);
                        if (refreshUi[0] != null) refreshUi[0].run();
                    }, exitTranslucentIfActive);
        }

        ColorPickerButtonRef textColorBtn = addColorPickerButton(a, row1, 1.45f, () -> {
            exitTranslucentIfActive.run();
        });
        textColorBtn.root.setOnClickListener(v -> {
            exitTranslucentIfActive.run();
            CinemaSubtitleColorPickerDialog.showTextColorPicker(a, colorState[0], picked -> {
                colorState[0] = picked;
                cb.onAction("color", picked);
                if (refreshUi[0] != null) refreshUi[0].run();
            }, textColorBtn.root);
        });

        firstFocus = visibleChip != null ? visibleChip : textColorBtn.root;

        TextView sizeMinusChip = addActionChip(a, row1, "A−", 0.7f, () -> {
            exitTranslucentIfActive.run();
            sizeState[0] = Math.max(18, sizeState[0] - 2);
            cb.onAction("size", -2);
            if (refreshUi[0] != null) refreshUi[0].run();
        }, exitTranslucentIfActive);
        TextView sizePlusChip = addActionChip(a, row1, "A+", 0.7f, () -> {
            exitTranslucentIfActive.run();
            sizeState[0] = Math.min(80, sizeState[0] + 2);
            cb.onAction("size", 2);
            if (refreshUi[0] != null) refreshUi[0].run();
        }, exitTranslucentIfActive);
        addActionChip(a, row1, "↑", 0.65f, () -> {
            exitTranslucentIfActive.run();
            vposState[0] = Math.min(200, vposState[0] + 5);
            cb.onAction("position", 5);
            if (refreshUi[0] != null) refreshUi[0].run();
        }, exitTranslucentIfActive);
        addActionChip(a, row1, "↓", 0.65f, () -> {
            exitTranslucentIfActive.run();
            vposState[0] = Math.max(16, vposState[0] - 5);
            cb.onAction("position", -5);
            if (refreshUi[0] != null) refreshUi[0].run();
        }, exitTranslucentIfActive);

        // 3. Row 2: Dark Background Card + 0%..100% Opacity Stepper (10% steps)
        LinearLayout bgRow = chipRow(a, parent);
        TextView bgToggleChip = addStatefulChip(a, bgRow, "", 1.5f,
                () -> bgState[0],
                () -> {
                    exitTranslucentIfActive.run();
                    bgState[0] = !bgState[0];
                    if (bgState[0] && bgOpacityState[0] == 0) {
                        bgOpacityState[0] = 70;
                        cb.onAction("bg_opacity", 70);
                    } else {
                        cb.onAction("background", 0);
                    }
                    if (refreshUi[0] != null) refreshUi[0].run();
                }, exitTranslucentIfActive);
        addActionChip(a, bgRow, "\u202A−10%\u202C", 0.75f, () -> {
            exitTranslucentIfActive.run();
            int next = UnifiedSubtitleConfig.stepBgOpacity(bgOpacityState[0], -10);
            bgOpacityState[0] = next;
            bgState[0] = next > 0;
            cb.onAction("bg_opacity", next);
            if (!bgState[0]) cb.onAction("background", 0);
            if (refreshUi[0] != null) refreshUi[0].run();
        }, exitTranslucentIfActive);
        TextView opacityValueChip = addStatefulChip(a, bgRow, "", 1.25f,
                () -> bgState[0] && bgOpacityState[0] > 0,
                () -> {
                    exitTranslucentIfActive.run();
                    int next = UnifiedSubtitleConfig.nextBgOpacity(bgOpacityState[0]);
                    bgOpacityState[0] = next;
                    bgState[0] = next > 0;
                    cb.onAction("bg_opacity", next);
                    if (refreshUi[0] != null) refreshUi[0].run();
                }, exitTranslucentIfActive);
        addActionChip(a, bgRow, "\u202A+10%\u202C", 0.75f, () -> {
            exitTranslucentIfActive.run();
            int next = UnifiedSubtitleConfig.stepBgOpacity(bgOpacityState[0], 10);
            if (next == 0) next = 10;
            bgOpacityState[0] = next;
            bgState[0] = true;
            cb.onAction("bg_opacity", next);
            if (refreshUi[0] != null) refreshUi[0].run();
        }, exitTranslucentIfActive);

        // 4. Row 3: Unified Outline / Stroke Section (Outline Size + Outline Color together)
        LinearLayout outlineRow = chipRow(a, parent);
        TextView outlineSizeChip = addStatefulChip(a, outlineRow, "", 1.35f,
                () -> outlineLevelState[0] > 0,
                () -> {
                    exitTranslucentIfActive.run();
                    int nextOut = UnifiedSubtitleConfig.nextOutlineLevel(outlineLevelState[0]);
                    outlineLevelState[0] = nextOut;
                    cb.onAction("outline", nextOut);
                    if (refreshUi[0] != null) refreshUi[0].run();
                }, exitTranslucentIfActive);

        ColorPickerButtonRef outlineColorBtn = addColorPickerButton(a, outlineRow, 1.35f, exitTranslucentIfActive);
        outlineColorBtn.root.setOnClickListener(v -> {
            exitTranslucentIfActive.run();
            CinemaSubtitleColorPickerDialog.showOutlineColorPicker(a, outlineColorState[0], picked -> {
                outlineColorState[0] = picked;
                if (outlineLevelState[0] == 0) outlineLevelState[0] = 2;
                cb.onAction("outline_color", picked);
                if (refreshUi[0] != null) refreshUi[0].run();
            }, outlineColorBtn.root);
        });

        // 5. Row 4: Dedicated Subtitle Timing Box (with translucent live-video mode) + Reset All
        LinearLayout timingBox = new LinearLayout(a);
        timingBox.setOrientation(LinearLayout.VERTICAL);
        timingBox.setClipChildren(false);
        timingBox.setClipToPadding(false);
        timingBox.setBackground(rounded(0xff1a1b20, dp(a, 10), 0x66E5A93B));
        timingBox.setPadding(dp(a, 10), dp(a, 8), dp(a, 10), dp(a, 8));
        LinearLayout.LayoutParams tBoxLp = new LinearLayout.LayoutParams(-1, -2);
        tBoxLp.bottomMargin = dp(a, 8);
        parent.addView(timingBox, tBoxLp);

        TextView timingHeaderTv = text(a, 12, true);
        timingHeaderTv.setTextColor(CinemaTokens.GOLD);
        timingHeaderTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        timingHeaderTv.setPaddingRelative(dp(a, 4), 0, dp(a, 4), dp(a, 6));
        timingBox.addView(timingHeaderTv);

        LinearLayout timingRow = new LinearLayout(a);
        timingRow.setOrientation(LinearLayout.HORIZONTAL);
        timingRow.setClipChildren(false);
        timingRow.setClipToPadding(false);
        timingBox.addView(timingRow, new LinearLayout.LayoutParams(-1, -2));

        Runnable activateLiveTranslucent = () -> {
            if (cfg.livePlayback && handleHolder[0] != null) {
                handleHolder[0].setTranslucentTimingMode(true);
            }
        };

        addActionChip(a, timingRow, ar ? "\u202A−0.1\u202C ث" : "\u202A−0.1s\u202C", 0.9f, () -> {
            delayState[0] -= 100L;
            cb.onAction("delay", -100);
            activateLiveTranslucent.run();
            if (refreshUi[0] != null) refreshUi[0].run();
        }, null);

        addActionChip(a, timingRow, ar ? "\u202A+0.1\u202C ث" : "\u202A+0.1s\u202C", 0.9f, () -> {
            delayState[0] += 100L;
            cb.onAction("delay", 100);
            activateLiveTranslucent.run();
            if (refreshUi[0] != null) refreshUi[0].run();
        }, null);

        addActionChip(a, timingRow, ar ? "تصفير التوقيت" : "Reset Timing", 1.15f, () -> {
            delayState[0] = 0L;
            cb.onAction("delay_reset", 0);
            activateLiveTranslucent.run();
            if (refreshUi[0] != null) refreshUi[0].run();
        }, null);

        if (cfg.livePlayback && cfg.onOpenLiveTimingOverlay != null) {
            addActionChip(a, timingRow, ar ? "شريط التوقيت الشفاف" : "Live Timing Bar", 1.25f, () -> {
                AlertDialog hostDlg = hostDialogSupplier != null ? hostDialogSupplier.get() : null;
                if (hostDlg != null) hostDlg.dismiss();
                cfg.onOpenLiveTimingOverlay.run();
            }, null);
        }

        // Row 5: Reset All Appearance & Timing
        LinearLayout resetRow = chipRow(a, parent);
        addActionChip(a, resetRow, ar ? "تصفير المظهر والتوقيت للافتراضي" : "Reset All Appearance & Timing", 1f, () -> {
            exitTranslucentIfActive.run();
            visibleState[0] = true;
            sizeState[0] = 36;
            vposState[0] = 64;
            colorState[0] = Color.WHITE;
            bgState[0] = false;
            bgOpacityState[0] = 70;
            outlineLevelState[0] = 0;
            outlineColorState[0] = Color.BLACK;
            delayState[0] = 0L;
            cb.onAction("reset", 0);
            if (refreshUi[0] != null) refreshUi[0].run();
        }, exitTranslucentIfActive);

        Handle handle = new Handle(firstFocus);
        handle.nonTimingViews.add(headingTv);
        handle.nonTimingViews.add(previewFrame);
        handle.nonTimingViews.add(row1);
        handle.nonTimingViews.add(bgRow);
        handle.nonTimingViews.add(outlineRow);
        handle.nonTimingViews.add(resetRow);
        handleHolder[0] = handle;

        final TextView finalVisibleChip = visibleChip;
        refreshUi[0] = () -> {
            float arSp = Math.max(12.5f, Math.min(23.5f, 12.5f + (sizeState[0] - 18) * 0.21f));
            float enSp = Math.max(11.5f, Math.min(21.5f, arSp - 1.5f));
            previewAr.setTextSize(arSp);
            previewEn.setTextSize(enSp);
            previewAr.setTextColor(colorState[0]);
            previewEn.setTextColor(colorState[0]);
            previewAr.setSubtitleBackground(bgState[0], bgOpacityState[0]);
            previewEn.setSubtitleBackground(bgState[0], bgOpacityState[0]);
            previewAr.setOutline(outlineLevelState[0], outlineColorState[0]);
            previewEn.setOutline(outlineLevelState[0], outlineColorState[0]);

            if (visibleState[0]) {
                previewPill.setVisibility(View.VISIBLE);
                previewHiddenTv.setVisibility(View.GONE);
            } else {
                previewPill.setVisibility(View.GONE);
                previewHiddenTv.setVisibility(View.VISIBLE);
            }

            if (finalVisibleChip != null) {
                finalVisibleChip.setText(visibleState[0]
                        ? a.getString(R.string.cinema_sub_visible_on)
                        : a.getString(R.string.cinema_sub_visible_off));
                styleStatefulChip(a, finalVisibleChip, visibleState[0], finalVisibleChip.hasFocus());
            }

            sizeMinusChip.setText("A−");
            sizePlusChip.setText("A+ (" + sizeState[0] + ")");

            String colName = UnifiedSubtitleConfig.textColorLabel(colorState[0], ar);
            textColorBtn.dot.setBackground(CinemaSubtitleColorPickerDialog.createColorDot(a, colorState[0]));
            textColorBtn.label.setText((ar ? "اللون: " : "Color: ") + colName + " ▾");
            styleColorPickerButton(a, textColorBtn, textColorBtn.root.hasFocus());

            bgToggleChip.setText(bgState[0]
                    ? a.getString(R.string.cinema_sub_bg_dark_on)
                    : a.getString(R.string.cinema_sub_bg_trans_on));
            styleStatefulChip(a, bgToggleChip, bgState[0], bgToggleChip.hasFocus());

            int shownOp = bgState[0] ? bgOpacityState[0] : 0;
            opacityValueChip.setText((ar ? "الشفافية: " : "Opacity: ") + shownOp + "%");
            styleStatefulChip(a, opacityValueChip, bgState[0] && bgOpacityState[0] > 0, opacityValueChip.hasFocus());

            String outLvlName = UnifiedSubtitleConfig.outlineLevelLabel(outlineLevelState[0], ar);
            outlineSizeChip.setText((ar ? "الإطار: " : "Outline: ") + outLvlName);
            styleStatefulChip(a, outlineSizeChip, outlineLevelState[0] > 0, outlineSizeChip.hasFocus());

            String outColName = UnifiedSubtitleConfig.outlineColorLabel(outlineColorState[0], ar);
            outlineColorBtn.dot.setBackground(CinemaSubtitleColorPickerDialog.createColorDot(a, outlineColorState[0]));
            outlineColorBtn.label.setText((ar ? "لون الإطار: " : "Stroke: ") + outColName + " ▾");
            outlineColorBtn.root.setAlpha(outlineLevelState[0] > 0 ? 1f : 0.78f);
            styleColorPickerButton(a, outlineColorBtn, outlineColorBtn.root.hasFocus());

            String formattedDelay = CinemaSubtitleTimingOverlay.formatDelaySeconds(a, delayState[0]);
            if (handle.translucentTimingActive) {
                timingHeaderTv.setText(ar
                        ? ("توقيت الترجمة الحيّ: " + formattedDelay + "  ·  اضغط رجوع لاستعادة القائمة")
                        : ("Live Subtitle Timing: " + formattedDelay + "  ·  Press Back to restore menu"));
            } else {
                timingHeaderTv.setText(ar
                        ? ("توقيت الترجمة: " + formattedDelay + (cfg.livePlayback ? "  ·  تشفّ القائمة تلقائياً لرؤية الفيديو" : ""))
                        : ("Subtitle Timing: " + formattedDelay + (cfg.livePlayback ? "  ·  Menu fades automatically to view video" : "")));
            }
        };
        handle.restoreUiCallback = refreshUi[0];
        refreshUi[0].run();
        return handle;
    }

    private static LinearLayout chipRow(Activity a, LinearLayout parent) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(a, 8);
        parent.addView(row, lp);
        return row;
    }

    private static TextView addActionChip(
            Activity a,
            LinearLayout row,
            String labelText,
            float weight,
            Runnable click,
            Runnable onFocusedCallback) {
        TextView btn = text(a, 13, true);
        btn.setText(labelText);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setPadding(dp(a, 8), dp(a, 10), dp(a, 8), dp(a, 10));
        btn.setBackground(rounded(0xff202127, dp(a, 10), 0xff3b372c));
        btn.setTextColor(CinemaTokens.TEXT);
        btn.setOnFocusChangeListener((v, has) -> {
            if (has && onFocusedCallback != null) onFocusedCallback.run();
            btn.setBackground(rounded(has ? CinemaTokens.FOCUS : 0xff202127, dp(a, 10),
                    has ? CinemaTokens.FOCUS : 0xff3b372c));
            btn.setTextColor(has ? 0xff141517 : CinemaTokens.TEXT);
        });
        btn.setOnClickListener(v -> {
            if (click != null) click.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, weight);
        lp.setMarginEnd(dp(a, 6));
        row.addView(btn, lp);
        return btn;
    }

    private static TextView addStatefulChip(
            Activity a,
            LinearLayout row,
            String initialText,
            float weight,
            BooleanSupplier isActive,
            Runnable click,
            Runnable onFocusedCallback) {
        TextView btn = text(a, 13, true);
        btn.setText(initialText);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setPadding(dp(a, 8), dp(a, 10), dp(a, 8), dp(a, 10));
        styleStatefulChip(a, btn, isActive.getAsBoolean(), false);
        btn.setOnFocusChangeListener((v, has) -> {
            if (has && onFocusedCallback != null) onFocusedCallback.run();
            styleStatefulChip(a, btn, isActive.getAsBoolean(), has);
        });
        btn.setOnClickListener(v -> {
            if (click != null) click.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, weight);
        lp.setMarginEnd(dp(a, 6));
        row.addView(btn, lp);
        return btn;
    }

    private static void styleStatefulChip(Activity a, TextView btn, boolean active, boolean focused) {
        if (focused) {
            btn.setBackground(rounded(CinemaTokens.FOCUS, dp(a, 10), CinemaTokens.FOCUS));
            btn.setTextColor(0xff141517);
        } else if (active) {
            btn.setBackground(rounded(0xff272318, dp(a, 10), 0xbbE5A93B));
            btn.setTextColor(0xfff8e2a6);
        } else {
            btn.setBackground(rounded(0xff202127, dp(a, 10), 0xff3b372c));
            btn.setTextColor(CinemaTokens.TEXT);
        }
    }

    private record ColorPickerButtonRef(LinearLayout root, View dot, TextView label) {}

    private static ColorPickerButtonRef addColorPickerButton(
            Activity a,
            LinearLayout row,
            float weight,
            Runnable onFocusedCallback) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER);
        root.setFocusable(true);
        root.setClickable(true);
        root.setPadding(dp(a, 10), dp(a, 10), dp(a, 10), dp(a, 10));

        View dot = new View(a);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(a, 14), dp(a, 14));
        dotLp.setMarginEnd(dp(a, 7));
        root.addView(dot, dotLp);

        TextView tv = text(a, 13, true);
        tv.setSingleLine(true);
        root.addView(tv);

        ColorPickerButtonRef ref = new ColorPickerButtonRef(root, dot, tv);
        styleColorPickerButton(a, ref, false);
        root.setOnFocusChangeListener((v, has) -> {
            if (has && onFocusedCallback != null) onFocusedCallback.run();
            styleColorPickerButton(a, ref, has);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, weight);
        lp.setMarginEnd(dp(a, 6));
        row.addView(root, lp);
        return ref;
    }

    private static void styleColorPickerButton(Activity a, ColorPickerButtonRef ref, boolean focused) {
        if (focused) {
            ref.root.setBackground(rounded(CinemaTokens.FOCUS, dp(a, 10), CinemaTokens.FOCUS));
            ref.label.setTextColor(0xff141517);
        } else {
            ref.root.setBackground(rounded(0xff25221a, dp(a, 10), 0xaaE5A93B));
            ref.label.setTextColor(0xfff8e2a6);
        }
    }
}

package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.archos.filecorelibrary.FileEditor;
import com.archos.filecorelibrary.FileEditorFactory;
import com.archos.filecorelibrary.FileUtils;
import com.archos.filecorelibrary.MetaFile2;
import com.archos.mediacenter.filecoreextension.upnp2.RawListerFactoryWithUpnp;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.browser.adapters.object.Episode;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.NonIndexedVideo;
import com.archos.mediacenter.video.browser.adapters.object.Tvshow;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.utils.DbUtils;
import com.archos.mediacenter.video.utils.VideoMetadata;
import com.archos.mediaprovider.video.VideoStore;
import com.archos.mediaprovider.video.VideoStoreInternal;
import com.meedx.providers.OpenMetadata;
import com.meedx.providers.UnifiedSubtitleConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.arabic;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.dp;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.label;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.motion;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.rounded;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.text;

/**
 * Unified Local Cinema «More / Technical Details & Media Actions» modal sheet.
 *
 * <p>Pure {@code #171717} / {@code #E5A93B} / {@code #FFFFFF} bilingual RTL/LTR experience
 * with zero focus clipping, full container technical readout, and permanent Delete from Source.
 */
public final class CinemaMediaMoreDialog {
    private static final String TAG = "CinemaMediaMoreDialog";
    private static final Pattern SUBTITLE_SUFFIX = Pattern.compile(
            "\\.(?:[a-z0-9]+-\\d+-(?:[0-9a-f]+-){4}[0-9a-f]+\\.)?"
                    + "(?:[a-z]{2,3}(?:-[a-z]{2,4})?|forced|default|sdh|cc)"
                    + "(?:[.-](?:forced|default|sdh|cc))*");
    private static final int CANVAS_BG = 0xFF171717;
    private static final int SURFACE_CARD = 0xFF1E1E22;
    private static final int SURFACE_INNER = 0xFF141417;
    private static final int BORDER_SUBTLE = 0xFF2E2E34;
    private static final int GOLD = 0xFFE5A93B;
    private static final int GOLD_SOFT_BG = 0xFF272116;
    private static final int GOLD_SOFT_BORDER = 0xFF564423;
    private static final int ON_GOLD = 0xFF171717;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0xFFB8B8C2;

    private static final ExecutorService PROBE_IO = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CinemaMediaMoreDialog() {}

    public record ExtraAction(int iconRes, String title, String subtitle, Runnable action) {}

    public static final class Options {
        private final Activity activity;
        private Video video;
        private Tvshow show;
        private VideoMetadata preloadedMetadata;
        private OpenMetadata.Item publicMetadata;
        private String displayTitle;
        private Uri fallbackUri;
        private int seasonHint;
        private int episodeHint;
        private Runnable onOpenDetails;
        private Runnable onOpenAddonStreams;
        private Runnable onChangePoster;
        private Runnable onStateChanged;
        private final List<ExtraAction> extraActions = new ArrayList<>();

        public Options(Activity activity) {
            this.activity = activity;
        }

        public Options video(Video v) { this.video = v; return this; }
        public Options show(Tvshow s) { this.show = s; return this; }
        public Options metadata(VideoMetadata m) { this.preloadedMetadata = m; return this; }
        public Options publicMetadata(OpenMetadata.Item m) { this.publicMetadata = m; return this; }
        public Options displayTitle(String t) { this.displayTitle = t; return this; }
        public Options fallbackUri(Uri u) { this.fallbackUri = u; return this; }
        public Options seasonEpisode(int season, int episode) {
            this.seasonHint = season;
            this.episodeHint = episode;
            return this;
        }
        public Options onPlayResume(Runnable r) { return this; }
        public Options onPlayFromBeginning(Runnable r) { return this; }
        public Options onOpenDetails(Runnable r) { this.onOpenDetails = r; return this; }
        public Options onOpenAudioTracks(Runnable r) { return this; }
        public Options onOpenSubtitles(Runnable r) { return this; }
        public Options onOpenAddonStreams(Runnable r) { this.onOpenAddonStreams = r; return this; }
        public Options onChangePoster(Runnable r) { this.onChangePoster = r; return this; }
        public Options onStateChanged(Runnable r) { this.onStateChanged = r; return this; }
        public Options addExtraAction(int iconRes, String title, String subtitle, Runnable action) {
            if (title != null && action != null) {
                this.extraActions.add(new ExtraAction(iconRes, title, subtitle, action));
            }
            return this;
        }

        public void show() {
            CinemaMediaMoreDialog.present(this);
        }
    }

    public static Options with(Activity activity) {
        return new Options(activity);
    }

    /** Direct entry point for {@code PlayerActivity} Info action without launching Nova's legacy VideoInfoActivity. */
    public static void showFromPlayer(
            Activity playerActivity,
            Uri mediaUri,
            long videoId,
            String title,
            VideoMetadata liveMetadata,
            Runnable onAudioTracks,
            Runnable onSubtitles,
            Runnable onDismissed) {
        if (playerActivity == null || playerActivity.isFinishing() || playerActivity.isDestroyed()) return;
        Video synthetic = mediaUri != null
                ? new NonIndexedVideo(mediaUri, mediaUri, title != null ? title : "", null)
                : null;
        Options opts = new Options(playerActivity)
                .video(synthetic)
                .fallbackUri(mediaUri)
                .displayTitle(title)
                .metadata(liveMetadata)
                .onStateChanged(onDismissed);
        present(opts, onDismissed, videoId);
    }

    private static void present(Options opts) {
        present(opts, null, opts.video != null ? opts.video.getId() : -1L);
    }

    private static void present(Options opts, Runnable onDismissCallback, long explicitVideoId) {
        Activity activity = opts.activity;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        boolean rtl = arabic(activity);

        FrameLayout dialogHost = new FrameLayout(activity);
        dialogHost.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        dialogHost.setClipChildren(false);
        dialogHost.setClipToPadding(false);
        dialogHost.setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8));

        LinearLayout shell = new LinearLayout(activity);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        shell.setClipChildren(true);
        shell.setClipToPadding(true);
        shell.setClipToOutline(true);
        GradientDrawable shellBg = rounded(CANVAS_BG, dp(activity, 18), BORDER_SUBTLE);
        shell.setBackground(shellBg);
        shell.setElevation(dp(activity, 20));
        dialogHost.addView(shell, new FrameLayout.LayoutParams(-1, -2));

        // 1. Clean Header Bar (no non-functional gear icon)
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPaddingRelative(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 14));

        LinearLayout titleCol = new LinearLayout(activity);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView titleTv = text(activity, 19, true);
        titleTv.setText(resolveHeaderTitle(activity, opts));
        titleTv.setTextColor(TEXT_PRIMARY);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        titleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        titleCol.addView(titleTv, new LinearLayout.LayoutParams(-1, -2));

        TextView subHeaderTv = text(activity, 12, false);
        subHeaderTv.setText(label(activity, "التفاصيل التقنية للمصدر والإجراءات",
                "Technical Media Details & Actions"));
        subHeaderTv.setTextColor(TEXT_SECONDARY);
        subHeaderTv.setSingleLine(true);
        subHeaderTv.setEllipsize(TextUtils.TruncateAt.END);
        subHeaderTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        var subHeaderLp = new LinearLayout.LayoutParams(-1, -2);
        subHeaderLp.topMargin = dp(activity, 2);
        titleCol.addView(subHeaderTv, subHeaderLp);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

        String sourceProto = resolveSourceProtocolBadge(activity, opts);
        TextView protoBadge = text(activity, 12, true);
        protoBadge.setText(sourceProto);
        protoBadge.setTextColor(GOLD);
        protoBadge.setBackground(rounded(GOLD_SOFT_BG, dp(activity, 10), GOLD_SOFT_BORDER));
        protoBadge.setPaddingRelative(dp(activity, 10), dp(activity, 4), dp(activity, 10), dp(activity, 4));
        var protoLp = new LinearLayout.LayoutParams(-2, -2);
        protoLp.setMarginStart(dp(activity, 10));
        header.addView(protoBadge, protoLp);
        shell.addView(header, new LinearLayout.LayoutParams(-1, -2));

        View divider = new View(activity);
        divider.setBackgroundColor(BORDER_SUBTLE);
        shell.addView(divider, new LinearLayout.LayoutParams(-1, Math.max(1, dp(activity, 1))));

        // 2. Clipped Scroll Viewport -> Unclipped Inner Content (padding >= 12dp so focus rings never clip)
        ScrollView scroll = new ScrollView(activity);
        scroll.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);

        int focusPad = dp(activity, 14);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        body.setPadding(focusPad, dp(activity, 12), focusPad, focusPad);
        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        // 3. Technical File & Stream Card
        LinearLayout techCard = new LinearLayout(activity);
        techCard.setOrientation(LinearLayout.VERTICAL);
        techCard.setBackground(rounded(SURFACE_CARD, dp(activity, 14), BORDER_SUBTLE));
        techCard.setPaddingRelative(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12));
        var techCardLp = new LinearLayout.LayoutParams(-1, -2);
        techCardLp.bottomMargin = dp(activity, 12);
        body.addView(techCard, techCardLp);

        TextView fileNameTv = text(activity, 14, true);
        fileNameTv.setTextColor(GOLD);
        fileNameTv.setSingleLine(true);
        fileNameTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        fileNameTv.setTextDirection(View.TEXT_DIRECTION_LTR);
        fileNameTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        fileNameTv.setText(resolveFileName(activity, opts));
        techCard.addView(fileNameTv, new LinearLayout.LayoutParams(-1, -2));

        TextView sourcePathTv = text(activity, 12, false);
        sourcePathTv.setTextColor(TEXT_SECONDARY);
        sourcePathTv.setSingleLine(true);
        sourcePathTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        sourcePathTv.setTextDirection(View.TEXT_DIRECTION_LTR);
        sourcePathTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        sourcePathTv.setText(resolveReadableLocation(activity, opts));
        var pathLp = new LinearLayout.LayoutParams(-1, -2);
        pathLp.topMargin = dp(activity, 3);
        techCard.addView(sourcePathTv, pathLp);

        HorizontalScrollView chipsScroll = new HorizontalScrollView(activity);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setFocusable(false);
        chipsScroll.setClipChildren(false);
        chipsScroll.setClipToPadding(false);
        var chipsScrollLp = new LinearLayout.LayoutParams(-1, -2);
        chipsScrollLp.topMargin = dp(activity, 10);
        techCard.addView(chipsScroll, chipsScrollLp);

        LinearLayout badgesRow = new LinearLayout(activity);
        badgesRow.setOrientation(LinearLayout.HORIZONTAL);
        badgesRow.setGravity(Gravity.CENTER_VERTICAL);
        chipsScroll.addView(badgesRow, new FrameLayout.LayoutParams(-2, -2));

        TextView tracksSummaryTv = text(activity, 12, false);
        tracksSummaryTv.setTextColor(0xFFDADAE2);
        tracksSummaryTv.setMaxLines(2);
        tracksSummaryTv.setEllipsize(TextUtils.TruncateAt.END);
        tracksSummaryTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        var tracksLp = new LinearLayout.LayoutParams(-1, -2);
        tracksLp.topMargin = dp(activity, 8);
        techCard.addView(tracksSummaryTv, tracksLp);

        populateTechnicalSection(activity, opts, opts.preloadedMetadata, badgesRow, tracksSummaryTv, fileNameTv);

        // 4. Contextual Non-Duplicate Actions (dismiss with Remote Back button)
        AlertDialog dialog = CinemaUi.dialog(activity).setView(dialogHost).create();
        if (onDismissCallback != null) {
            dialog.setOnDismissListener(d -> onDismissCallback.run());
        }

        List<LinearLayout> focusRows = new ArrayList<>();
        Video v = opts.video;
        boolean isSyntheticRemote = v != null && v.getFilePath() != null
                && (v.getFilePath().startsWith("trakt://") || v.getFilePath().startsWith("addon:"));

        if (opts.onOpenDetails != null) {
            addActionRow(activity, rtl, body, scroll, focusPad, focusRows, dialog,
                    R.drawable.cinema_play_movie,
                    label(activity, "فتح صفحة العرض", "Open Media Page"),
                    label(activity, "عرض القصة وطاقم التمثيل والتقييمات", "Synopsis, cast, ratings & trailers"),
                    opts.onOpenDetails);
        }

        // Addon Streams shortcut
        if (opts.onOpenAddonStreams != null) {
            addActionRow(activity, rtl, body, scroll, focusPad, focusRows, dialog,
                    R.drawable.cinema_addons,
                    label(activity, "مصادر الإضافات (Nuvio / Stremio)", "Addon Streams (Nuvio / Stremio)"),
                    label(activity, "جلب مصادر بث بديلة وعالية الجودة", "Fetch alternative streams from installed add-ons"),
                    opts.onOpenAddonStreams);
        } else if (v != null || opts.show != null) {
            addActionRow(activity, rtl, body, scroll, focusPad, focusRows, dialog,
                    R.drawable.cinema_addons,
                    label(activity, "مصادر الإضافات (Nuvio / Stremio)", "Addon Streams (Nuvio / Stremio)"),
                    label(activity, "البحث عن مصادر بث عبر الإضافات المثبتة", "Search streams across installed Stremio add-ons"),
                    () -> CinemaAddonPicker.show(activity, PROBE_IO, MAIN,
                            v != null ? v : opts.show,
                            opts.publicMetadata,
                            v instanceof Episode ep ? ep.getSeasonNumber() : opts.seasonHint,
                            v instanceof Episode ep ? ep.getEpisodeNumber() : Math.max(1, opts.episodeHint)));
        }

        // Extra caller-specific actions (e.g., Episode Details from Series overview)
        for (ExtraAction extra : opts.extraActions) {
            addActionRow(activity, rtl, body, scroll, focusPad, focusRows, dialog,
                    extra.iconRes(), extra.title(), extra.subtitle(), extra.action());
        }

        // Remove from Continue Watching (only when mid-playback)
        if (v != null && !isSyntheticRemote && v.getFilePath() != null && !v.getFilePath().isBlank()) {
            long resumeMs = CinemaMediaTime.knownResume(v.getId(), v.getFilePath(), v.getResumeMs());
            if (resumeMs > 0L && !CinemaPlaybackRouter.isCompletedBookmark(resumeMs)) {
                addActionRow(activity, rtl, body, scroll, focusPad, focusRows, dialog,
                        R.drawable.cinema_restart,
                        activity.getString(R.string.cinema_remove_from_continue_watching),
                        label(activity, "مسح نقطة التوقف الحالية من متابعة المشاهدة", "Clear saved playback progress"),
                        () -> clearContinueProgressAsync(activity, v, opts.publicMetadata, opts.onStateChanged));
            }
        }

        if (opts.onChangePoster != null) {
            addActionRow(activity, rtl, body, scroll, focusPad, focusRows, dialog,
                    R.drawable.cinema_ic_posters,
                    activity.getString(R.string.cinema_refresh_poster_choose_another_poster),
                    label(activity, "اختيار بوستر بديل وتحديث الصورة الفنية", "Pick another poster artwork"),
                    opts.onChangePoster);
        }

        // Delete from Source (Local storage / USB / SMB / WebDAV / FTP) with confirmation
        boolean fromPlayer = activity.getClass().getSimpleName().contains("Player");
        Uri deletableUri = resolveDeletableUri(v, opts.fallbackUri);
        if (v != null && !isSyntheticRemote && !fromPlayer && deletableUri != null) {
            addActionRow(activity, rtl, body, scroll, focusPad, focusRows, dialog,
                    android.R.drawable.ic_menu_delete,
                    label(activity, "حذف من المصدر", "Delete from Source"),
                    label(activity, "حذف الملف نهائياً من التخزين أو مشاركة الشبكة",
                            "Permanently delete the file from its storage or network share"),
                    () -> confirmDeleteFromSource(activity, v, deletableUri, opts.onStateChanged));
        }

        // Lock D-pad focus cleanly inside the sheet
        for (int i = 0; i < focusRows.size(); i++) {
            LinearLayout cur = focusRows.get(i);
            LinearLayout prev = i > 0 ? focusRows.get(i - 1) : cur;
            LinearLayout next = i < focusRows.size() - 1 ? focusRows.get(i + 1) : cur;
            cur.setNextFocusUpId(prev.getId());
            cur.setNextFocusDownId(next.getId());
            cur.setNextFocusLeftId(cur.getId());
            cur.setNextFocusRightId(cur.getId());
        }

        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            int screenH = activity.getResources().getDisplayMetrics().heightPixels;
            int targetW = Math.min(dp(activity, 640), Math.round(screenW * 0.72f));
            window.setLayout(targetW, ViewGroup.LayoutParams.WRAP_CONTENT);

            int maxScrollH = Math.round(screenH * 0.72f);
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
        }

        if (!focusRows.isEmpty()) {
            LinearLayout first = focusRows.get(0);
            first.requestFocus();
            first.post(() -> {
                if (dialog.isShowing() && first.isAttachedToWindow()) first.requestFocus();
            });
        }

        // Probe container metadata asynchronously if not yet available
        if (opts.preloadedMetadata == null && v != null && !isSyntheticRemote) {
            PROBE_IO.execute(() -> {
                VideoMetadata probed = CinemaEngineBoundary.probeMediaMetadata(activity, v);
                if (probed == null) return;
                MAIN.post(() -> {
                    if (!dialog.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
                    populateTechnicalSection(activity, opts, probed, badgesRow, tracksSummaryTv, fileNameTv);
                });
            });
        }
    }

    private static Uri resolveDeletableUri(Video v, Uri fallbackUri) {
        if (v == null) return null;
        Uri uri = v.getFileUri();
        String path = v.getFilePath();
        if (uri == null && path != null && !path.isBlank()) {
            uri = path.startsWith("/") ? Uri.fromFile(new File(path)) : Uri.parse(path);
        }
        if (uri == null) uri = fallbackUri;
        if (uri == null) return null;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.isEmpty() && uri.getPath() != null && uri.getPath().startsWith("/")) {
            return Uri.fromFile(new File(uri.getPath()));
        }
        if ("http".equals(scheme) || "https".equals(scheme) || "trakt".equals(scheme) || "addon".equals(scheme)) {
            return null;
        }
        return uri;
    }

    private static void populateTechnicalSection(
            Context ctx,
            Options opts,
            VideoMetadata meta,
            LinearLayout badgesRow,
            TextView tracksSummaryTv,
            TextView fileNameTv) {
        badgesRow.removeAllViews();
        boolean rtl = arabic(ctx);
        Video v = opts.video;

        // 1. Duration & Watch Progress
        long dur = meta != null && meta.getDuration() > 0
                ? meta.getDuration()
                : (v != null ? CinemaMediaTime.known(v.getId(), v.getFilePath(), v.getDurationMs()) : 0L);
        long res = v != null ? CinemaMediaTime.knownResume(v.getId(), v.getFilePath(), v.getResumeMs()) : 0L;
        if (dur > 0L) {
            addTechPill(ctx, badgesRow, "⏱ " + CinemaMediaTime.format(dur), false);
        }
        if (v != null && (v.isWatched() || CinemaPlaybackRouter.isCompletedBookmark(res))) {
            addTechPill(ctx, badgesRow, label(ctx, "تمت المشاهدة ✓", "Watched ✓"), true);
        }
        if (dur > 0L && res > 0L && !CinemaPlaybackRouter.isCompletedBookmark(res)) {
            int pct = (int) Math.min(99L, Math.max(1L, res * 100L / dur));
            addTechPill(ctx, badgesRow, label(ctx, "التقدم ", "Progress ") + pct + "%", true);
        }

        // 2. Resolution & Video Codec / FPS
        if (meta != null && meta.getVideoWidth() > 0 && meta.getVideoHeight() > 0) {
            String resBadge = CinemaEngineBoundary.formatResolutionBadge(meta.getVideoWidth(), meta.getVideoHeight());
            if (!resBadge.isBlank()) addTechPill(ctx, badgesRow, resBadge, true);
        }
        if (meta != null && meta.getVideoTrack() != null) {
            var vt = meta.getVideoTrack();
            if (vt.format != null && !vt.format.isBlank()) {
                addTechPill(ctx, badgesRow, vt.format.toUpperCase(Locale.ROOT), false);
            }
            if (vt.fpsRate > 0 && vt.fpsScale > 0) {
                float fps = (float) vt.fpsRate / vt.fpsScale;
                addTechPill(ctx, badgesRow, String.format(Locale.US, "%.3f fps", fps).replaceAll("\\.?0+ fps$", " fps"), false);
            }
        }

        // 3. File Size
        long bytes = meta != null && meta.getFileSize() > 0 ? meta.getFileSize() : (v != null ? v.getSize() : 0L);
        if (bytes <= 0L && v != null && v.getFilePath() != null && v.getFilePath().startsWith("/")) {
            try {
                File f = new File(v.getFilePath());
                if (f.isFile()) bytes = f.length();
            } catch (Exception ignored) {}
        }
        if (bytes > 0L) {
            String sizeLabel = bytes >= 1073741824L
                    ? String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
                    : String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
            addTechPill(ctx, badgesRow, sizeLabel, false);
        }

        if (opts.show != null && v == null) {
            addTechPill(ctx, badgesRow,
                    opts.show.getSeasonCount() + (rtl ? " مواسم" : " Seasons"), true);
        }

        if (badgesRow.getChildCount() == 0) {
            addTechPill(ctx, badgesRow, label(ctx, "وسائط جاهزة للتشغيل", "Media Ready"), false);
        }

        // 4. Audio & Subtitle Tracks Summary
        List<String> summaryParts = new ArrayList<>();
        if (meta != null) {
            int audioCount = meta.getAudioTrackNb();
            if (audioCount > 0) {
                List<String> audioDesc = new ArrayList<>();
                for (int i = 0; i < Math.min(audioCount, 3); i++) {
                    var at = meta.getAudioTrack(i);
                    if (at == null) continue;
                    String lang = (at.language == null || at.language.isBlank() || "und".equalsIgnoreCase(at.language))
                            ? ""
                            : UnifiedSubtitleConfig.languageDisplay(at.language, rtl);
                    String fmt = joinNonEmpty(at.format, at.channels);
                    String entry = joinNonEmpty(lang, fmt);
                    if (!entry.isBlank()) audioDesc.add(entry);
                }
                String prefix = label(ctx, "الصوت (", "Audio (") + audioCount + "): ";
                summaryParts.add(prefix + (audioDesc.isEmpty() ? label(ctx, "متوفر", "Available") : String.join(" · ", audioDesc)));
            }
            int subCount = meta.getSubtitleTrackNb();
            if (subCount > 0) {
                summaryParts.add(label(ctx, "الترجمات المدمجة/المرفقة: ", "Detected Subtitles: ") + subCount);
            }
        }
        if (summaryParts.isEmpty()) {
            if (v != null && v.getFileUri() != null) {
                int savedSub = CinemaTrackChoices.embedded(ctx, v.getFileUri());
                String subState = savedSub == -1
                        ? label(ctx, "إيقاف", "Off")
                        : label(ctx, "تلقائي / مفعّل", "Auto / Enabled");
                summaryParts.add(label(ctx, "أولوية الصوت: ", "Audio Priority: ") + CinemaPreferredAudio.label(ctx)
                        + "   ·   " + label(ctx, "الترجمة: ", "Subtitles: ") + subState);
            } else {
                summaryParts.add(label(ctx, "أولوية الصوت: ", "Audio Priority: ") + CinemaPreferredAudio.label(ctx));
            }
        }
        tracksSummaryTv.setText(String.join("\n", summaryParts));
    }

    private static void addTechPill(Context ctx, LinearLayout row, String value, boolean goldAccent) {
        TextView pill = text(ctx, 11.5f, true);
        pill.setText(value);
        pill.setTextColor(goldAccent ? GOLD : TEXT_PRIMARY);
        pill.setBackground(rounded(goldAccent ? GOLD_SOFT_BG : SURFACE_INNER,
                dp(ctx, 8), goldAccent ? GOLD_SOFT_BORDER : BORDER_SUBTLE));
        pill.setPaddingRelative(dp(ctx, 9), dp(ctx, 4), dp(ctx, 9), dp(ctx, 4));
        var lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(ctx, 8));
        row.addView(pill, lp);
    }

    private static void addActionRow(
            Context ctx,
            boolean rtl,
            LinearLayout parent,
            ScrollView scroll,
            int focusPad,
            List<LinearLayout> focusRows,
            AlertDialog dialog,
            int iconRes,
            String title,
            String subtitle,
            Runnable command) {
        LinearLayout row = new LinearLayout(ctx);
        row.setId(View.generateViewId());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ctx, 52));
        row.setPaddingRelative(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10));
        row.setFocusable(true);
        row.setClickable(true);

        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focusedBg = rounded(GOLD, dp(ctx, 12), 0xFFFFE394);
        focusedBg.setStroke(dp(ctx, 2), 0xFFFFE394);
        GradientDrawable idleBg = rounded(SURFACE_CARD, dp(ctx, 12), BORDER_SUBTLE);
        idleBg.setStroke(Math.max(1, dp(ctx, 1)), BORDER_SUBTLE);
        bg.addState(new int[]{android.R.attr.state_focused}, focusedBg);
        bg.addState(new int[]{}, idleBg);
        row.setBackground(bg);

        FrameLayout iconBox = new FrameLayout(ctx);
        GradientDrawable iconIdle = rounded(0xFF282830, dp(ctx, 9), 0);
        GradientDrawable iconFocus = rounded(0x26171717, dp(ctx, 9), 0);
        iconBox.setBackground(iconIdle);

        ImageView iconView = new ImageView(ctx);
        var dr = ContextCompat.getDrawable(ctx, iconRes != 0 ? iconRes : R.drawable.cinema_ic_system);
        if (dr != null) {
            dr = dr.mutate();
            dr.setTint(GOLD);
            iconView.setImageDrawable(dr);
        }
        iconBox.addView(iconView, new FrameLayout.LayoutParams(dp(ctx, 19), dp(ctx, 19), Gravity.CENTER));
        var iconBoxLp = new LinearLayout.LayoutParams(dp(ctx, 34), dp(ctx, 34));
        iconBoxLp.setMarginEnd(dp(ctx, 12));
        row.addView(iconBox, iconBoxLp);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView titleTv = text(ctx, 14.5f, true);
        titleTv.setText(title);
        titleTv.setTextColor(TEXT_PRIMARY);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        titleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        textCol.addView(titleTv, new LinearLayout.LayoutParams(-1, -2));

        TextView subTv = null;
        if (subtitle != null && !subtitle.isBlank()) {
            subTv = text(ctx, 11.5f, false);
            subTv.setText(subtitle);
            subTv.setTextColor(TEXT_SECONDARY);
            subTv.setSingleLine(true);
            subTv.setEllipsize(TextUtils.TruncateAt.END);
            subTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            var subLp = new LinearLayout.LayoutParams(-1, -2);
            subLp.topMargin = dp(ctx, 2);
            textCol.addView(subTv, subLp);
        }
        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView chevron = text(ctx, 15, true);
        chevron.setText(rtl ? "‹" : "›");
        chevron.setTextColor(TEXT_SECONDARY);
        var chevLp = new LinearLayout.LayoutParams(-2, -2);
        chevLp.setMarginStart(dp(ctx, 8));
        row.addView(chevron, chevLp);

        final TextView finalSub = subTv;
        row.setOnFocusChangeListener((v, hasFocus) -> {
            titleTv.setTextColor(hasFocus ? ON_GOLD : TEXT_PRIMARY);
            if (finalSub != null) finalSub.setTextColor(hasFocus ? 0xFF2D230E : TEXT_SECONDARY);
            chevron.setTextColor(hasFocus ? ON_GOLD : TEXT_SECONDARY);
            iconBox.setBackground(hasFocus ? iconFocus : iconIdle);
            if (iconView.getDrawable() != null) {
                iconView.getDrawable().setTint(hasFocus ? ON_GOLD : GOLD);
            }
            v.animate().scaleX(hasFocus ? 1.018f : 1f).scaleY(hasFocus ? 1.018f : 1f)
                    .setDuration(motion(ctx) ? 95 : 0).start();
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
            dialog.dismiss();
            if (command != null) command.run();
        });

        var lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(ctx, 8);
        parent.addView(row, lp);
        focusRows.add(row);
    }

    private static void confirmDeleteFromSource(Activity activity, Video video, Uri targetUri, Runnable onDone) {
        CinemaUi.showDialogWithButtonFocus(CinemaUi.dialog(activity)
                .setTitle(label(activity, "حذف من المصدر", "Delete from Source"))
                .setMessage(label(activity, "هل أنت متأكد من حذف الملف نهائياً من المصدر؟",
                        "Are you sure you want to permanently delete this file from its source?"))
                .setNegativeButton(label(activity, "إلغاء", "Cancel"), null)
                .setPositiveButton(label(activity, "حذف", "Delete"),
                        (d, w) -> deleteFromSourceAsync(activity, video, targetUri, onDone)),
                android.content.DialogInterface.BUTTON_NEGATIVE);
    }

    private static void deleteFromSourceAsync(Activity activity, Video video, Uri targetUri, Runnable onDone) {
        Context app = activity.getApplicationContext();
        PROBE_IO.execute(() -> {
            Uri source = targetUri.normalizeScheme();
            boolean smb = "smb".equals(source.getScheme()) || "smbj".equals(source.getScheme());
            boolean deleted;
            try {
                String name = video.getFileNameWithExtension() != null && !video.getFileNameWithExtension().isBlank()
                        ? video.getFileNameWithExtension() : video.getName();
                deleted = smb ? deleteSmbFile(app, source)
                        : CinemaFileOperations.deleteItem(app, new CinemaFileOperations.Item(
                                name, targetUri, false, 0L, video.getSize()));
            } catch (Exception e) {
                Log.w(TAG, "Source deletion failed: " + e.getClass().getSimpleName());
                deleted = false;
            }
            boolean cleanupComplete = true;
            if (deleted) {
                if (smb) {
                    String indexedPath = video.getFilePath();
                    if (indexedPath == null || indexedPath.isBlank()) indexedPath = targetUri.toString();
                    boolean indexRemoved = removeScannedFile(app, indexedPath);
                    cleanupComplete = deleteSmbSubtitles(app, source) && indexRemoved;
                }
                try { CinemaMediaTime.clearResume(video.getId(), video.getFilePath(), targetUri); } catch (Throwable ignored) {}
                try { if (CinemaWatchlistActivity.contains(app, video)) CinemaWatchlistActivity.toggle(app, video); } catch (Throwable ignored) {}
                try {
                    app.sendBroadcast(new Intent(CinemaLibraryScan.ACTION_CHANGED).setPackage(app.getPackageName()));
                } catch (Throwable ignored) {}
            }
            boolean fileDeleted = deleted;
            boolean fullyDeleted = cleanupComplete;
            MAIN.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (!fileDeleted) {
                    Toast.makeText(activity, label(activity,
                            "تعذّر حذف الملف من المصدر (تحقق من الصلاحيات والاتصال)",
                            "Could not delete the file from its source (check permissions & connection)"),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                String message = fullyDeleted
                        ? label(activity, "تم حذف الملف من المصدر ✓", "File deleted from source ✓")
                        : label(activity, "حُذف الفيديو، وتعذّر إكمال تنظيف الترجمة أو سجل المكتبة",
                                "Video deleted, but subtitle or library cleanup was incomplete");
                Toast.makeText(activity, message, fullyDeleted ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                if (activity instanceof CinemaDetailsActivity) {
                    activity.finish();
                } else if (onDone != null) {
                    onDone.run();
                }
            });
        });
    }

    private static boolean deleteSmbFile(Context context, Uri uri) throws Exception {
        String path = uri.getEncodedPath();
        if (path == null || path.endsWith("/") || uri.getPathSegments().size() < 2) return false;
        FileEditor editor = FileEditorFactory.getFileEditorForUrl(uri, context);
        Boolean result = editor.delete();
        // JCIFS returns null after a successful delete. Check that the file is gone.
        return Boolean.TRUE.equals(result) || (result == null && !editor.exists());
    }

    private static boolean deleteSmbSubtitles(Context context, Uri videoUri) {
        String videoName = FileUtils.getName(videoUri);
        String videoStem = fileStem(videoName);
        try {
            Uri parent = FileUtils.getParentUrl(videoUri);
            List<MetaFile2> siblings = RawListerFactoryWithUpnp.getRawListerForUrl(parent).getFileList();
            if (siblings == null) return false;

            List<String> otherVideoStems = new ArrayList<>();
            for (MetaFile2 sibling : siblings) {
                String name = sibling.getName();
                if (!sibling.isDirectory() && CinemaFileOperations.isVideoName(name)) {
                    otherVideoStems.add(fileStem(name));
                }
            }

            boolean complete = deleteMatchingSubtitles(context, siblings, videoStem, otherVideoStems);
            for (MetaFile2 sibling : siblings) {
                if (sibling.isDirectory() && List.of("subs", "subtitles", "sub")
                        .contains(sibling.getName().toLowerCase(Locale.ROOT))) {
                    List<MetaFile2> subtitles = RawListerFactoryWithUpnp
                            .getRawListerForUrl(sibling.getUri()).getFileList();
                    if (!deleteMatchingSubtitles(context, subtitles, videoStem, otherVideoStems)) complete = false;
                }
            }
            return complete;
        } catch (Exception e) {
            Log.w(TAG, "SMB subtitle listing failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean deleteMatchingSubtitles(Context context, List<MetaFile2> files,
            String videoStem, List<String> otherVideoStems) {
        if (files == null) return false;
        boolean complete = true;
        for (MetaFile2 file : files) {
            String name = file.getName();
            if (file.isDirectory() || !CinemaFileOperations.isSubtitleName(name)) continue;
            String subtitleStem = fileStem(name);
            if (!matchesSubtitleStem(subtitleStem, videoStem)
                    || otherVideoStems.stream().anyMatch(stem -> stem.length() >= videoStem.length()
                            && matchesSubtitleStem(subtitleStem, stem))) continue;
            Uri uri = file.getUri();
            try {
                if (!deleteSmbFile(context, uri) || !removeScannedFile(context, uri.toString())) complete = false;
            } catch (Exception e) {
                Log.w(TAG, "SMB subtitle deletion failed: " + e.getClass().getSimpleName());
                complete = false;
            }
        }
        return complete;
    }

    private static String fileStem(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT);
    }

    private static boolean matchesSubtitleStem(String subtitleStem, String videoStem) {
        return subtitleStem.equals(videoStem) || (subtitleStem.startsWith(videoStem)
                && SUBTITLE_SUFFIX.matcher(subtitleStem.substring(videoStem.length())).matches());
    }

    private static boolean removeScannedFile(Context context, String path) {
        try {
            context.getContentResolver().delete(VideoStoreInternal.FILES_SCANNED,
                    VideoStore.MediaColumns.DATA + " IN (?,?)", new String[]{path, Uri.decode(path)});
            return true;
        } catch (Exception e) {
            Log.w(TAG, "SMB media index cleanup failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private static void clearContinueProgressAsync(
            Activity activity,
            Video video,
            OpenMetadata.Item pubMeta,
            Runnable onDone) {
        Context app = activity.getApplicationContext();
        boolean preserveWatched = video.isWatched() || CinemaPlaybackRouter.isCompletedBookmark(video.getResumeMs());
        CinemaMediaTime.clearResume(video.getId(), video.getFilePath(), video.getFileUri());
        if (preserveWatched) {
            CinemaMediaTime.rememberState(video.getId(), video.getFileUri(), video.getDurationMs(), CinemaPlaybackRouter.BOOKMARK_COMPLETED);
        }
        CinemaWatchStore.recordContinueTombstone(app, "", video.getId() > 0 ? "v:" + video.getId() : "",
                video.getFilePath(), video.getName(),
                pubMeta != null ? pubMeta.imdb() : "", video instanceof Movie m ? m.getOnlineId() : 0L,
                video instanceof Episode ep ? ep.getSeasonNumber() : 0,
                video instanceof Episode ep ? ep.getEpisodeNumber() : 0);
        if (onDone != null) onDone.run();
        Toast.makeText(activity, R.string.cinema_removed_from_continue_watching, Toast.LENGTH_SHORT).show();
        PROBE_IO.execute(() -> {
            try {
                DbUtils.clearResumeOnly(app, video);
                CinemaLinkServer.broadcastWatchSync(CinemaWatchStore.buildSyncRecordForVideo(
                        app, video, 0L, preserveWatched, true));
                var identity = CinemaWatchStore.resolvePlaybackDescriptor(
                        app, video.getId(), video.getFilePath(), video.getName());
                String imdb = (pubMeta != null && pubMeta.imdb() != null && !pubMeta.imdb().isBlank())
                        ? pubMeta.imdb()
                        : (identity != null ? identity.imdbId() : null);
                long tmdb = identity != null ? identity.tmdbId()
                        : (video instanceof Movie m ? m.getOnlineId() : 0L);
                int s = identity != null ? identity.season() : 0;
                int e = identity != null ? identity.episode() : 0;
                CinemaWatchStore.recordContinueTombstone(
                        app, identity != null ? identity.canonicalKey() : "",
                        video.getId() > 0 ? "v:" + video.getId() : "",
                        video.getFilePath(), video.getName(), imdb, tmdb, s, e);
                CinemaWatchStore.clearMatchingContinue(app, imdb, tmdb, s, e);
                CinemaWatchStore.removePlaybackProgressFromTraktAsync(
                        app, video.getId(), video.getFilePath(), video.getName(), imdb, tmdb, s, e);
            } catch (Exception ignored) {}
        });
    }

    private static String resolveHeaderTitle(Context ctx, Options opts) {
        if (opts.displayTitle != null && !opts.displayTitle.isBlank()) return opts.displayTitle;
        if (opts.video instanceof Episode ep) {
            String show = ep.getShowName() != null && !ep.getShowName().isBlank() ? ep.getShowName() + " · " : "";
            return show + String.format(Locale.US, "S%02d E%02d", ep.getSeasonNumber(), ep.getEpisodeNumber());
        }
        if (opts.video != null && opts.video.getName() != null && !opts.video.getName().isBlank()) {
            return opts.video.getName();
        }
        if (opts.show != null && opts.show.getName() != null) return opts.show.getName();
        return label(ctx, "خيارات الوسائط", "Media Options");
    }

    private static String resolveFileName(Context ctx, Options opts) {
        if (opts.video != null) {
            String fn = opts.video.getFileNameWithExtension();
            if (fn != null && !fn.isBlank()) return fn;
        }
        Uri u = opts.video != null && opts.video.getFileUri() != null
                ? opts.video.getFileUri()
                : (opts.show != null && opts.show.getFileUri() != null ? opts.show.getFileUri() : opts.fallbackUri);
        if (u != null && u.getLastPathSegment() != null && !u.getLastPathSegment().isBlank()) {
            return Uri.decode(u.getLastPathSegment());
        }
        return resolveHeaderTitle(ctx, opts);
    }

    private static String resolveReadableLocation(Context ctx, Options opts) {
        String raw = opts.video != null ? opts.video.getFilePath() : null;
        if ((raw == null || raw.isBlank()) && opts.show != null && opts.show.getFileUri() != null) {
            raw = opts.show.getFileUri().toString();
        }
        if ((raw == null || raw.isBlank()) && opts.fallbackUri != null) {
            raw = opts.fallbackUri.toString();
        }
        if (raw == null || raw.isBlank()) {
            return label(ctx, "المكتبة المحلية", "Local Library");
        }
        String decoded = Uri.decode(raw);
        decoded = decoded.replaceFirst("://[^/@]+@", "://");
        int slash = decoded.lastIndexOf('/');
        if (slash > 0 && !decoded.endsWith("/")) {
            return decoded.substring(0, slash);
        }
        return decoded;
    }

    private static String resolveSourceProtocolBadge(Context ctx, Options opts) {
        String raw = opts.video != null ? opts.video.getFilePath() : null;
        if ((raw == null || raw.isBlank()) && opts.show != null && opts.show.getFileUri() != null) {
            raw = opts.show.getFileUri().toString();
        }
        if ((raw == null || raw.isBlank()) && opts.fallbackUri != null) {
            raw = opts.fallbackUri.toString();
        }
        if (raw == null || raw.isBlank()) return "LOCAL";
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.startsWith("smb://") || lower.startsWith("smbj://")) return "SMB";
        if (lower.startsWith("upnp://")) return "UPnP / DLNA";
        if (lower.startsWith("webdav://") || lower.startsWith("webdavs://")) {
            return lower.contains(":8080") ? label(ctx, "مكتبة الهاتف", "Phone Library") : "WebDAV";
        }
        if (lower.startsWith("ftp://") || lower.startsWith("ftps://")
                || lower.startsWith("sftp://") || lower.startsWith("sshj://")) return "FTP / SFTP";
        if (lower.startsWith("http://") || lower.startsWith("https://")) return "STREAM";
        if (lower.startsWith("trakt://")) return "TRAKT";
        if (lower.startsWith("/storage/") && !lower.startsWith("/storage/emulated")) return "USB";
        return label(ctx, "تخزين محلي", "Local");
    }

    private static String joinNonEmpty(String a, String b) {
        boolean hasA = a != null && !a.isBlank();
        boolean hasB = b != null && !b.isBlank();
        if (hasA && hasB) return a + " · " + b;
        return hasA ? a : (hasB ? b : "");
    }
}

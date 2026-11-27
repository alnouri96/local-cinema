package com.archos.mediacenter.video.player;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.preference.PreferenceManager;
import com.archos.filecorelibrary.FileEditorFactory;
import com.archos.mediacenter.video.leanback.cinema.CinemaSubtitleCodec;
import com.archos.mediacenter.video.leanback.cinema.CinemaUi;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

/** Independent pure-Java cue overlay supporting SRT, WebVTT, and ASS/SSA for Phone casts and dual subtitles. */
final class CinemaSecondarySubtitle implements AutoCloseable {
    private static final Pattern TIMING = Pattern.compile(
            "(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[.,](\\d{3})");
    private static final Pattern ASS_TIME = Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{2,3})");
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private record Cue(long start, long end, String text) {}
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io = Executors.newSingleThreadExecutor();
    private final Context context;
    private final SubtitleTextView overlay;
    private final IntSupplier position;
    private final boolean primaryMode;
    private final Runnable update = this::updateCue;
    private List<Cue> cues = List.of();
    private int generation;
    private boolean active;
    private String shown = "";
    private Uri video;

    private float sizeSp;
    private int bottomMarginDp;
    private int textColor;
    private boolean opaqueBg = false;
    private int bgOpacity = 70;
    private int outlineLevel = 0;
    private int outlineColor = Color.BLACK;
    private boolean visible = true;
    private long delayMs = 0L;
    private String trackLabel = "";
    private String trackPath = "";
    private String trackLangCanon = "";
    private CinemaSecondarySubtitle partner;

    CinemaSecondarySubtitle(RelativeLayout root, IntSupplier position) {
        this(root, position, false);
    }

    CinemaSecondarySubtitle(RelativeLayout root, IntSupplier position, boolean primaryMode) {
        this.context = root.getContext();
        this.position = position;
        this.primaryMode = primaryMode;
        this.sizeSp = primaryMode ? 22f : 22f;
        this.bottomMarginDp = primaryMode ? 36 : 24;
        this.textColor = primaryMode ? com.meedx.providers.UnifiedSubtitleConfig.textColor(context) : 0xfffef08a;
        this.opaqueBg = com.meedx.providers.UnifiedSubtitleConfig.opaqueBg(context);
        this.bgOpacity = com.meedx.providers.UnifiedSubtitleConfig.bgOpacity(context);
        this.outlineLevel = com.meedx.providers.UnifiedSubtitleConfig.outlineLevel(context);
        this.outlineColor = com.meedx.providers.UnifiedSubtitleConfig.outlineColor(context);
        overlay = new SubtitleTextView(context);
        overlay.setId(View.generateViewId());
        overlay.setGravity(Gravity.CENTER);
        overlay.setTextColor(textColor);
        overlay.setTypeface(Typeface.DEFAULT_BOLD);
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        overlay.setVisibility(View.GONE);
        root.addView(overlay);
        applyAppearance();
    }

    int getOverlayId() {
        return overlay.getId();
    }

    void setPartner(CinemaSecondarySubtitle other) {
        this.partner = other;
        applyAppearance();
    }

    void refreshLayout() {
        this.opaqueBg = com.meedx.providers.UnifiedSubtitleConfig.opaqueBg(context);
        this.bgOpacity = com.meedx.providers.UnifiedSubtitleConfig.bgOpacity(context);
        this.outlineLevel = com.meedx.providers.UnifiedSubtitleConfig.outlineLevel(context);
        this.outlineColor = com.meedx.providers.UnifiedSubtitleConfig.outlineColor(context);
        if (primaryMode) this.textColor = com.meedx.providers.UnifiedSubtitleConfig.textColor(context);
        applyAppearance();
    }

    boolean hasCues() {
        return !cues.isEmpty();
    }

    String getTrackLabel() {
        return trackLabel == null ? "" : trackLabel;
    }

    String getTrackPath() {
        return trackPath == null ? "" : trackPath;
    }

    String getTrackLangCanon() {
        return trackLangCanon == null ? "" : trackLangCanon;
    }

    void setTrackMeta(String label, String path, String langCanon) {
        this.trackLabel = label == null ? "" : label;
        this.trackPath = path == null ? "" : path;
        this.trackLangCanon = langCanon == null ? "" : langCanon;
    }

    long getDelayMs() {
        return delayMs;
    }

    void setDelayMs(long newDelayMs) {
        delayMs = newDelayMs;
        shown = "";
        updateCue();
    }

    void setVideo(Uri uri) {
        if (java.util.Objects.equals(video, uri)) return;
        video = uri;
        clear();
    }

    void load(Uri uri) {
        int request = ++generation;
        io.execute(() -> {
            try {
                byte[] raw = readUriBytes(uri);
                var decoded = CinemaSubtitleCodec.decodeAndCache(context, raw, uri.toString());
                List<Cue> parsed = parseCues(decoded.ext(), decoded.utf8Bytes());
                handler.post(() -> {
                    if (request != generation) return;
                    cues = parsed;
                    visible = true;
                    applyAppearance();
                    if (partner != null) partner.refreshLayout();
                    schedule();
                });
            } catch (Exception failure) {
                handler.post(() -> {
                    if (request == generation && !primaryMode) {
                        Toast.makeText(context,
                                CinemaUi.label(context, "تعذّر قراءة ملف الترجمة الثانية", "Could not read the secondary subtitle"),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    void loadDecoded(String ext, byte[] utf8Bytes) {
        int request = ++generation;
        io.execute(() -> {
            try {
                List<Cue> parsed = parseCues(ext, utf8Bytes);
                handler.post(() -> {
                    if (request != generation) return;
                    cues = parsed;
                    visible = true;
                    shown = "";
                    applyAppearance();
                    if (partner != null) partner.refreshLayout();
                    schedule();
                });
            } catch (Exception ignored) {}
        });
    }

    private byte[] readUriBytes(Uri uri) throws Exception {
        var editor = FileEditorFactory.getFileEditorForUrl(uri, context);
        try (InputStream in = "content".equals(uri.getScheme()) ? context.getContentResolver().openInputStream(uri)
                : ("file".equals(uri.getScheme()) && uri.getPath() != null) ? new java.io.FileInputStream(uri.getPath())
                : editor == null ? null : editor.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException("Missing subtitle stream");
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1; ) {
                if (Thread.currentThread().isInterrupted() || out.size() + n > MAX_BYTES) {
                    throw new IOException("Subtitle too large");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static List<Cue> parseCues(String ext, byte[] rawUtf8) throws IOException {
        if (rawUtf8 == null || rawUtf8.length == 0) throw new IOException("Empty subtitle");
        String text = new String(rawUtf8, StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n').replace("\ufeff", "");
        if (CinemaSubtitleText.isEntirelyStoryboardVtt(text)) {
            throw new IOException("Storyboard VTT ignored");
        }
        List<Cue> parsed = new ArrayList<>();
        if ("ass".equalsIgnoreCase(ext) || "ssa".equalsIgnoreCase(ext) || text.contains("Dialogue:")) {
            for (String line : text.split("\n")) {
                if (!line.startsWith("Dialogue:")) continue;
                String[] parts = line.substring(9).split(",", 10);
                if (parts.length < 10) continue;
                long start = parseAssTimeMs(parts[1].trim());
                long end = parseAssTimeMs(parts[2].trim());
                String body = CinemaSubtitleText.displayText(parts[9]).trim();
                if (start >= 0 && end > start && !body.isEmpty()) {
                    parsed.add(new Cue(start, end, body));
                }
            }
        } else {
            String[] lines = text.split("\n");
            int i = 0;
            while (i < lines.length) {
                String line = lines[i].trim();
                var match = TIMING.matcher(line);
                if (!match.find()) {
                    i++;
                    continue;
                }
                long start = parseGroupMs(match.group(1), match.group(2), match.group(3), match.group(4));
                long end = parseGroupMs(match.group(5), match.group(6), match.group(7), match.group(8));
                i++;
                StringBuilder body = new StringBuilder();
                while (i < lines.length) {
                    String content = lines[i].trim();
                    if (content.isEmpty() || TIMING.matcher(content).find()) break;
                    String cleaned = CinemaSubtitleText.displayText(content.replaceAll("<[^>]+>", ""))
                            .replace("&amp;", "&").replace("&lt;", "<")
                            .replace("&gt;", ">").replace("&nbsp;", " ")
                            .trim();
                    if (!cleaned.isEmpty()) {
                        if (body.length() > 0) body.append('\n');
                        body.append(cleaned);
                    }
                    i++;
                }
                if (start >= 0 && end > start && body.length() > 0) {
                    parsed.add(new Cue(start, end, body.toString()));
                }
            }
        }
        if (parsed.isEmpty()) throw new IOException("No text cues");
        parsed.sort(Comparator.comparingLong(Cue::start));
        return parsed;
    }

    private static long parseGroupMs(String hrsStr, String minsStr, String secsStr, String msStr) {
        long h = hrsStr != null ? Long.parseLong(hrsStr) : 0L;
        long m = Long.parseLong(minsStr);
        long s = Long.parseLong(secsStr);
        long ms = Long.parseLong(msStr);
        return ((h * 3600L + m * 60L + s) * 1000L) + ms;
    }

    private static long parseAssTimeMs(String raw) {
        var m = ASS_TIME.matcher(raw);
        if (!m.find()) return -1L;
        long h = Long.parseLong(m.group(1));
        long mins = Long.parseLong(m.group(2));
        long s = Long.parseLong(m.group(3));
        String frac = m.group(4);
        long ms = frac.length() == 2 ? Long.parseLong(frac) * 10L : Long.parseLong(frac);
        return ((h * 3600L + mins * 60L + s) * 1000L) + ms;
    }

    private void updateCue() {
        if (!active || cues.isEmpty() || !visible) {
            if (!visible) overlay.setVisibility(View.GONE);
            return;
        }
        long now = position.getAsInt() - delayMs;
        if (now < 0) {
            overlay.setText(null);
            overlay.setVisibility(View.GONE);
            handler.postDelayed(update, 100);
            return;
        }
        StringBuilder current = new StringBuilder();
        for (Cue cue : cues) {
            if (cue.start() > now) break;
            if (cue.end() > now) {
                if (current.length() > 0) current.append('\n');
                current.append(cue.text());
            }
        }
        String value = current.toString();
        if (!value.equals(shown)) {
            shown = value;
            overlay.setText(value.isEmpty() ? null : value);
            overlay.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        }
        handler.postDelayed(update, 100);
    }

    void setActive(boolean value) {
        active = value;
        schedule();
    }

    private void schedule() {
        handler.removeCallbacks(update);
        if (active && !cues.isEmpty() && visible) handler.post(update);
    }

    void clear() {
        generation++;
        cues = List.of();
        shown = "";
        delayMs = 0L;
        trackLabel = "";
        trackPath = "";
        trackLangCanon = "";
        overlay.setText(null);
        overlay.setVisibility(View.GONE);
        handler.removeCallbacks(update);
        if (partner != null) partner.refreshLayout();
    }

    boolean applyStyle(String action, int value) {
        if (action == null) return false;
        switch (action) {
            case "color" -> {
                if (primaryMode) textColor = value;
            }
            case "size" -> sizeSp = Math.max(12f, Math.min(48f, sizeSp + (value * 1.5f)));
            case "position" -> bottomMarginDp = Math.max(8, Math.min(200, bottomMarginDp + (value * 2)));
            case "background" -> opaqueBg = !opaqueBg;
            case "bg_opacity" -> {
                bgOpacity = value >= 0
                        ? com.meedx.providers.UnifiedSubtitleConfig.normalizeBgOpacity(value)
                        : com.meedx.providers.UnifiedSubtitleConfig.nextBgOpacity(bgOpacity);
                opaqueBg = bgOpacity > 0;
            }
            case "outline" -> outlineLevel = value >= 0
                    ? Math.max(0, Math.min(3, value))
                    : com.meedx.providers.UnifiedSubtitleConfig.nextOutlineLevel(outlineLevel);
            case "outline_color" -> {
                outlineColor = com.meedx.providers.UnifiedSubtitleConfig.normalizeOutlineColor(value);
                if (outlineLevel == 0) outlineLevel = 2;
            }
            case "visible" -> visible = !visible;
            case "delay" -> delayMs += value;
            case "delay_reset" -> delayMs = 0L;
            case "reset" -> {
                delayMs = 0L;
                sizeSp = 22f;
                bottomMarginDp = primaryMode ? 36 : 24;
                textColor = primaryMode ? Color.WHITE : 0xfffef08a;
                opaqueBg = false;
                bgOpacity = 70;
                outlineLevel = 0;
                outlineColor = Color.BLACK;
                visible = true;
            }
            default -> {
                return false;
            }
        }
        applyAppearance();
        shown = "";
        updateCue();
        return true;
    }

    void showAppearance() {
        var prefs = PreferenceManager.getDefaultSharedPreferences(context);
        CinemaUi.dialog(context).setTitle(CinemaUi.label(context, "مظهر الترجمة الثانية", "Secondary subtitle appearance"))
                .setItems(new String[]{CinemaUi.label(context, "الحجم", "Size"), CinemaUi.label(context, "الموضع", "Position")}, (dialog, which) -> {
                    if (which == 0) {
                        CinemaUi.dialog(context).setTitle(CinemaUi.label(context, "الحجم", "Size"))
                                .setItems(new String[]{"18", "22", "26", "30", "34"}, (d, n) -> {
                                    prefs.edit().putInt("cinema_secondary_size", 18 + 4 * n).apply();
                                    sizeSp = 18 + 4 * n;
                                    applyAppearance();
                                }).show();
                    } else {
                        CinemaUi.dialog(context).setTitle(CinemaUi.label(context, "الموضع", "Position"))
                                .setItems(new String[]{CinemaUi.label(context, "أعلى", "Top"), CinemaUi.label(context, "وسط", "Center"), CinemaUi.label(context, "أسفل", "Bottom")}, (d, n) -> {
                                    int mappedDualPos = n == 2 ? 0 : 1;
                                    prefs.edit().putInt("cinema_secondary_position", n)
                                            .putInt("pref_dual_sub_position", mappedDualPos).apply();
                                    applyAppearance();
                                    if (partner != null) partner.refreshLayout();
                                }).show();
                    }
                }).show();
    }

    private void applyAppearance() {
        var prefs = PreferenceManager.getDefaultSharedPreferences(context);
        float sz = primaryMode ? sizeSp : prefs.getInt("cinema_secondary_size", Math.round(sizeSp));
        overlay.setTextSize(sz);
        overlay.setTextColor(textColor);
        overlay.setTypeface(Typeface.DEFAULT_BOLD);
        overlay.setMaxWidth(Math.max(CinemaUi.dp(context, 240),
                context.getResources().getDisplayMetrics().widthPixels - CinemaUi.dp(context, 48)));
        overlay.setSubtitleBackground(opaqueBg, bgOpacity);
        overlay.setOutline(outlineLevel, outlineColor);
        int inset = CinemaUi.dp(context, 24);
        int dualPos = Math.max(0, Math.min(2, prefs.getInt("pref_dual_sub_position", 1)));
        boolean pairedWithCastPrimary = partner != null && partner.hasCues();
        var layout = new RelativeLayout.LayoutParams(-2, -2);
        layout.addRule(RelativeLayout.CENTER_HORIZONTAL);
        if (dualPos == 0) {
            if (primaryMode) {
                layout.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                layout.setMargins(inset, inset, inset, CinemaUi.dp(context, bottomMarginDp));
            } else if (pairedWithCastPrimary && partner.getOverlayId() != View.NO_ID) {
                layout.addRule(RelativeLayout.ABOVE, partner.getOverlayId());
                layout.alignWithParent = true;
                layout.setMargins(inset, inset, inset, CinemaUi.dp(context, 8));
            } else {
                // Native SubtitleManager renders at bottom — place secondary clearly above it without overlap
                int nativeVpos = prefs.getInt(PlayerActivity.KEY_SUBTITLE_VPOS, 64);
                int safeBottomDp = Math.max(132, nativeVpos + 74);
                layout.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                layout.setMargins(inset, inset, inset, CinemaUi.dp(context, safeBottomDp));
            }
        } else if (dualPos == 2 && pairedWithCastPrimary) {
            if (primaryMode) {
                layout.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                layout.setMargins(inset, inset, inset, inset);
            } else {
                layout.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                layout.setMargins(inset, inset, inset, CinemaUi.dp(context, bottomMarginDp));
            }
        } else {
            // Default split (or native primary at bottom): secondary ALWAYS stays at TOP to prevent overlap!
            if (primaryMode) {
                layout.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                layout.setMargins(inset, inset, inset, CinemaUi.dp(context, bottomMarginDp));
            } else {
                layout.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                layout.setMargins(inset, CinemaUi.dp(context, 26), inset, inset);
            }
        }
        overlay.setLayoutParams(layout);
        if (!visible) overlay.setVisibility(View.GONE);
    }

    @Override public void close() {
        clear();
        io.shutdownNow();
    }
}

package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.view.Display;

import androidx.preference.PreferenceManager;

import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.info.VideoInfoCommonClass;
import com.archos.mediacenter.video.player.CinemaDisplayModes;
import com.archos.mediacenter.video.player.HardwareCapabilityProfiler;
import com.archos.mediacenter.video.player.SurfaceController;
import com.archos.mediacenter.video.utils.VideoMetadata;

/**
 * Isolated Playback Quality, Display Mode, HDR Capability & Media Probing Boundary
 * for Local Cinema TV ({@code Engine v2} Readiness).
 *
 * <p>Shields UI and settings screens from direct references to legacy {@code player/} internals
 * ({@link CinemaDisplayModes}, {@link HardwareCapabilityProfiler}, {@link SurfaceController},
 * and {@link VideoInfoCommonClass}).
 */
public final class CinemaEngineBoundary {
    public static final String KEY_DISPLAY_MODE = "cinema_display_mode";
    public static final String KEY_DISPLAY_DELAY_MS = "cinema_display_delay_ms";

    public static final int DISPLAY_MODE_LEGACY_DEFAULT = -1;
    public static final int DISPLAY_MODE_OFF = 0;
    public static final int DISPLAY_MODE_REFRESH_ONLY = 1;
    public static final int DISPLAY_MODE_REFRESH_AND_RESOLUTION = 2;
    public static final int DEFAULT_DISPLAY_DELAY_MS = 1500;

    public static final int ASPECT_ORIGINAL = SurfaceController.VideoFormat.ORIGINAL;
    public static final int ASPECT_STRETCH = SurfaceController.VideoFormat.STRETCH_XY;
    public static final int ASPECT_ZOOM_FULL = SurfaceController.VideoFormat.FULL_SCREEN;
    public static final int ASPECT_AUTO = SurfaceController.VideoFormat.AUTO;

    private CinemaEngineBoundary() {}

    public record HardwareSummary(
            long ramBytes,
            boolean dolbyVision,
            boolean hdr10Plus,
            boolean profile7Decoder,
            boolean ultraEligible,
            String displayModesSummary) {}

    public static int getDisplayMode(Context context) {
        if (context == null) return DISPLAY_MODE_LEGACY_DEFAULT;
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(KEY_DISPLAY_MODE, DISPLAY_MODE_LEGACY_DEFAULT);
    }

    public static void setDisplayMode(Context context, int mode) {
        if (context == null) return;
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putInt(KEY_DISPLAY_MODE, mode)
                .apply();
    }

    public static int getDisplaySwitchDelayMs(Context context) {
        if (context == null) return DEFAULT_DISPLAY_DELAY_MS;
        int raw = PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(KEY_DISPLAY_DELAY_MS, DEFAULT_DISPLAY_DELAY_MS);
        return Math.max(0, Math.min(5000, raw));
    }

    public static void setDisplaySwitchDelayMs(Context context, int delayMs) {
        if (context == null) return;
        int clamped = Math.max(0, Math.min(5000, delayMs));
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putInt(KEY_DISPLAY_DELAY_MS, clamped)
                .apply();
    }

    /** Inspects TV RAM, HDR formats, Dolby Vision Profile 7, and HDMI display modes. */
    public static HardwareSummary inspectHardware(Context context, Display display) {
        HardwareCapabilityProfiler.Profile p = HardwareCapabilityProfiler.inspect(context, display);
        return new HardwareSummary(
                p.ramBytes(),
                p.dolbyVision(),
                p.hdr10Plus(),
                p.profile7Decoder(),
                p.ultraEligible(),
                p.displayModes());
    }

    /** Selects the optimal HDMI {@link Display.Mode} for the active frame rate and resolution policy. */
    public static Display.Mode selectDisplayMode(
            Display.Mode current,
            Display.Mode[] supportedModes,
            float fps,
            int videoWidth,
            int videoHeight,
            int cinemaDisplayMode) {
        if (current == null || supportedModes == null || cinemaDisplayMode <= DISPLAY_MODE_OFF) {
            return current;
        }
        int targetW = cinemaDisplayMode == DISPLAY_MODE_REFRESH_AND_RESOLUTION ? videoWidth : 0;
        int targetH = cinemaDisplayMode == DISPLAY_MODE_REFRESH_AND_RESOLUTION ? videoHeight : 0;
        return CinemaDisplayModes.select(current, supportedModes, fps, targetW, targetH);
    }

    /**
     * Probes container, video/audio/subtitle tracks, dimensions, codec, and duration
     * for a local or network {@link Video} on a worker thread.
     */
    public static VideoMetadata probeMediaMetadata(Context context, Video video) {
        if (context == null || video == null) return null;
        try {
            VideoMetadata existing = video.getMetadata();
            if (existing != null && existing.getVideoTrack() != null) {
                return existing;
            }
            return VideoInfoCommonClass.retrieveMetadata(video, context.getApplicationContext());
        } catch (Exception ignored) {
            return video.getMetadata();
        }
    }

    /** Formats a human-readable resolution tier badge (4K UHD, 1080p FHD, 720p HD, SD). */
    public static String formatResolutionBadge(int width, int height) {
        if (width <= 0 || height <= 0) return "";
        int longEdge = Math.max(width, height);
        String tier;
        if (longEdge >= 3800 || height >= 2100) tier = "4K UHD";
        else if (longEdge >= 2500 || height >= 1400) tier = "1440p QHD";
        else if (longEdge >= 1900 || height >= 1040) tier = "1080p FHD";
        else if (longEdge >= 1260 || height >= 700) tier = "720p HD";
        else tier = "SD";
        return tier + " · " + width + "×" + height;
    }
}

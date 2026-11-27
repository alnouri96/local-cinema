package com.archos.mediacenter.video.player;

import java.util.Locale;
import java.util.regex.Pattern;

public final class CinemaSubtitleText {
    private static final Pattern OVERRIDE = Pattern.compile(
            "\\{(?:[^{}\\r\\n]*\\\\[^{}\\r\\n]*|\\s*[1-4]?[ca]\\s*&H[0-9a-fA-F]+&?[^{}\\r\\n]*)\\}");
    private static final Pattern XYWH_PATTERN = Pattern.compile("(?i)#xywh=\\s*\\d+");
    private static final Pattern IMAGE_CUE_LINE = Pattern.compile(
            "(?i)^(?:https?://\\S+|/?[^\\s<>\"'{}|^`\\\\]+)\\.(?:jpe?g|png|webp|gif|bmp|avif|svg)(?:[?#]\\S*)?$");
    private static final Pattern STORYBOARD_TRACK_HINT = Pattern.compile(
            "(?i)(?:#xywh=|\\.(?:jpe?g|png|webp|gif|bmp|avif)(?:$|[?#])|(?:^|[/_.-])(?:storyboard|sprites?|thumbnails?|thumbs|trickplay|bif)(?:$|[/_.-]))");

    private CinemaSubtitleText() {}

    public static boolean isStoryboardTrackHint(String urlOrLabel) {
        if (urlOrLabel == null || urlOrLabel.isBlank()) return false;
        return STORYBOARD_TRACK_HINT.matcher(urlOrLabel.trim()).find();
    }

    public static boolean isStoryboardOrImageLine(CharSequence rawLine) {
        if (rawLine == null) return false;
        String s = rawLine.toString().trim();
        if (s.isEmpty()) return false;
        if (XYWH_PATTERN.matcher(s).find()) return true;
        String stripped = s.replaceAll("<[^>]+>", "").trim();
        if (stripped.isEmpty()) return false;
        if (XYWH_PATTERN.matcher(stripped).find()) return true;
        return IMAGE_CUE_LINE.matcher(stripped).matches();
    }

    public static boolean isEntirelyStoryboardVtt(String rawSubText) {
        if (rawSubText == null || rawSubText.isBlank()) return false;
        boolean sawCueTiming = false;
        boolean sawDialogue = false;
        boolean sawStoryboard = false;
        String[] lines = rawSubText.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String raw : lines) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            String upper = t.toUpperCase(Locale.ROOT);
            if (upper.startsWith("WEBVTT") || upper.startsWith("NOTE") || upper.startsWith("STYLE")
                    || upper.startsWith("REGION") || t.matches("^\\d+$")) {
                continue;
            }
            if (t.contains("-->")) {
                sawCueTiming = true;
                continue;
            }
            if (isStoryboardOrImageLine(t)) {
                sawStoryboard = true;
            } else {
                sawDialogue = true;
                break;
            }
        }
        return sawStoryboard && (!sawDialogue || !sawCueTiming);
    }

    public static String displayText(CharSequence text) {
        if (text == null) return "";
        String normalized = OVERRIDE.matcher(text).replaceAll("")
                .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", "\u00a0");
        if (isStoryboardOrImageLine(normalized)) return "";
        if (normalized.indexOf('\n') < 0 && !XYWH_PATTERN.matcher(normalized).find()) {
            return normalized;
        }
        String[] parts = normalized.split("\\r?\\n");
        StringBuilder out = new StringBuilder(normalized.length());
        for (String part : parts) {
            if (isStoryboardOrImageLine(part)) continue;
            if (out.length() > 0) out.append('\n');
            out.append(part);
        }
        return out.toString();
    }
}

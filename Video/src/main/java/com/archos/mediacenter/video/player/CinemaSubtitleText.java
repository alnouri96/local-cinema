package com.archos.mediacenter.video.player;

import java.util.regex.Pattern;

public final class CinemaSubtitleText {
    private static final Pattern OVERRIDE = Pattern.compile(
            "\\{(?:[^{}\\r\\n]*\\\\[^{}\\r\\n]*|\\s*[1-4]?[ca]\\s*&H[0-9a-fA-F]+&?[^{}\\r\\n]*)\\}");

    private CinemaSubtitleText() {}

    public static String displayText(CharSequence text) {
        if (text == null) return "";
        return OVERRIDE.matcher(text).replaceAll("")
                .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", "\u00a0");
    }
}

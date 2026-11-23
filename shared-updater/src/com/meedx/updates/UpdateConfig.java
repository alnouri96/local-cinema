package com.meedx.updates;

public final class UpdateConfig {
    public static final String UPDATE_JSON_URL =
            "https://meedx.pages.dev/api/version.json";

    static boolean isOfficialDownload(String url) {
        if (url == null) return false;
        // The Pages feed supplies hashes and bilingual notes for public GitHub release assets.
        return url.matches("https://github[.]com/alnouri96/(?:local-cinema|meedx)/releases/download/"
                + "v[0-9][0-9A-Za-z.-]*/LocalCinema-[A-Za-z0-9.-]+[.]apk");
    }

    private UpdateConfig() {}
}

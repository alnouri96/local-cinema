package com.meedx.updates;

public final class UpdateConfig {
    public static final String UPDATE_JSON_URL =
            "https://meedx.pages.dev/api/v2/version.json";

    public static final String DOWNLOADS_SITE_URL =
            "https://meedx.pages.dev/downloads";

    public static final String DOWNLOADS_SITE_DISPLAY =
            "meedx.pages.dev/downloads";

    public static final String DOWNLOADER_CODE = "3213660";

    static boolean isOfficialDownload(String url) {
        if (url == null) return false;
        return url.matches("https://github[.]com/alnouri96/local-cinema/releases/download/"
                + "v[0-9][0-9A-Za-z.-]*/LocalCinema-[A-Za-z0-9.-]+[.]apk")
                || url.matches("https://meedx[.]pages[.]dev/(?:tv|phone|downloads/.*[.]apk)");
    }

    private UpdateConfig() {}
}

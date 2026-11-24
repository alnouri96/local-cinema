package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.preference.PreferenceManager;

import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.leanback.details.VideoDetailsFragment;
import com.archos.mediacenter.video.utils.SubtitlesDownloaderActivity2;
import com.meedx.providers.ProviderKeys;

public final class SubtitleSources {
    static final String MODE = "cinema_subtitle_source";
    private SubtitleSources() {}

    public static int selected(Context context) {
        int value = PreferenceManager.getDefaultSharedPreferences(context).getInt(MODE, 0);
        return value >= 0 && value <= 2 ? value : 0;
    }

    static String title(Context context) {
        return CinemaUi.label(context, "إعدادات الترجمة", "Subtitle Settings");
    }

    static String label(Context context) {
        boolean ar = CinemaUi.arabic(context);
        return (ar ? "بحث ذكي · " : "Smart Search · ")
                + com.meedx.providers.UnifiedSubtitleConfig.searchSelectionSummary(context, ar);
    }

    static void settings(Activity activity) {
        activity.startActivity(new Intent(activity, SubsourceSettingsActivity.class));
    }

    static void search(Activity activity, Video video) {
        Intent intent = new Intent(activity, SubsourceSearchActivity.class);
        if (video != null) intent.putExtra(VideoDetailsFragment.EXTRA_VIDEO, video);
        intent.putExtra("both_providers", true);
        activity.startActivity(intent);
    }

    private static void start(Activity activity, Video video, boolean openSubtitles) {
        if (openSubtitles && ProviderKeys.get(activity, ProviderKeys.OPEN_SUBTITLES).isEmpty()) {
            activity.startActivity(new Intent(activity, SubsourceSearchActivity.class)
                    .putExtra(VideoDetailsFragment.EXTRA_VIDEO, video).putExtra("both_providers", true));
            return;
        }
        Intent intent;
        if (openSubtitles) {
            intent = new Intent(activity, SubtitlesDownloaderActivity2.class)
                    .putExtra(SubtitlesDownloaderActivity2.FILE_URL, video.getFilePath());
        } else {
            intent = new Intent(activity, SubsourceSearchActivity.class)
                    .putExtra(VideoDetailsFragment.EXTRA_VIDEO, video);
        }
        activity.startActivity(intent);
    }

    static void missing(Activity activity, String source) {
        CinemaUi.dialog(activity).setTitle(source)
                .setMessage(CinemaUi.label(activity, "أضف مفتاح API لهذا المصدر من إعدادات الترجمة.",
                        "Add this provider's API key in subtitle settings."))
                .setPositiveButton(title(activity), (d, w) -> settings(activity))
                .setNegativeButton(android.R.string.cancel, null).show();
    }
}

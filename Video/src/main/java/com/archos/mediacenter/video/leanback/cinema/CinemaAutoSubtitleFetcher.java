package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.net.Uri;
import android.widget.Toast;

import com.archos.mediacenter.video.R;
import com.meedx.providers.UnifiedSubtitleConfig;
import com.meedx.providers.UnifiedSubtitleEngine;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Shared DRY one-click "Auto-Download from Internet Now" runner used by all
 * CinemaSubtitleHubDialog callers (PlayerActivity, CinemaHlsActivity, Details, Series).
 * Resolves metadata accurately, picks the highest-scoring subtitle in the preferred language,
 * saves it cleanly via SubtitleStorage, and delivers the saved URI (or triggers fallback search).
 */
public final class CinemaAutoSubtitleFetcher {
    private CinemaAutoSubtitleFetcher() {}

    public static void fetchNow(
            Activity activity,
            Uri targetVideoUri,
            String rawTitle,
            String englishTitleHint,
            String knownImdb,
            int knownYear,
            boolean isSeries,
            int season,
            int episode,
            Consumer<Uri> onSavedUri,
            Runnable onNoMatchFallback) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Toast.makeText(activity, R.string.cinema_sub_auto_fetching_now, Toast.LENGTH_SHORT).show();

        final String preferredLang = UnifiedSubtitleConfig.primaryLanguage(activity);
        final Set<String> sources = UnifiedSubtitleConfig.enabledSources(activity);
        final String fileName = (targetVideoUri != null && targetVideoUri.getLastPathSegment() != null)
                ? targetVideoUri.getLastPathSegment() : (rawTitle == null ? "" : rawTitle);

        new Thread(() -> {
            try {
                UnifiedSubtitleEngine.ResolvedQuery rq = UnifiedSubtitleEngine.resolveQuery(
                        activity.getApplicationContext(),
                        rawTitle, englishTitleHint, knownImdb, knownYear, isSeries, season, episode);
                List<UnifiedSubtitleEngine.Candidate> found = UnifiedSubtitleEngine.search(
                        activity.getApplicationContext(), rq, List.of(preferredLang), sources, fileName);

                if (found.isEmpty() || targetVideoUri == null) {
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        Toast.makeText(activity, R.string.cinema_sub_auto_fetch_none, Toast.LENGTH_SHORT).show();
                        if (onNoMatchFallback != null) onNoMatchFallback.run();
                    });
                    return;
                }

                UnifiedSubtitleEngine.Candidate best = found.get(0);
                List<UnifiedSubtitleEngine.SubtitleFile> files = UnifiedSubtitleEngine.download(
                        activity.getApplicationContext(), best, rq.season, rq.episode);
                if (files.isEmpty()) {
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        Toast.makeText(activity, R.string.cinema_sub_auto_fetch_none, Toast.LENGTH_SHORT).show();
                        if (onNoMatchFallback != null) onNoMatchFallback.run();
                    });
                    return;
                }

                UnifiedSubtitleEngine.SubtitleFile chosenFile = files.get(0);
                try (SubtitleStorage storage = new SubtitleStorage(activity)) {
                    long subId = 0L;
                    try { subId = Long.parseLong(best.externalId); } catch (Exception ignored) {}
                    SubtitleStorage.Saved saved = storage.save(
                            targetVideoUri,
                            new SubtitleStorage.Entry(chosenFile.name, chosenFile.utf8Bytes),
                            subId,
                            best.languageCanon,
                            best.sourceBadge,
                            () -> !activity.isFinishing() && !activity.isDestroyed());
                    if (subId > 0L) CinemaAutoSubtitles.remember(activity, targetVideoUri, subId);
                    Uri savedUri = saved.uri();
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        Toast.makeText(activity,
                                CinemaUi.label(activity, "تم تحميل وتفعيل الترجمة من ", "Subtitle applied from ")
                                        + best.sourceBadge + " ✓",
                                Toast.LENGTH_LONG).show();
                        if (onSavedUri != null) onSavedUri.accept(savedUri);
                    });
                }
            } catch (Exception ignored) {
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    Toast.makeText(activity, R.string.cinema_sub_auto_fetch_none, Toast.LENGTH_SHORT).show();
                    if (onNoMatchFallback != null) onNoMatchFallback.run();
                });
            }
        }, "cinema-auto-sub-now").start();
    }
}

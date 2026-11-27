package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.archos.mediacenter.video.browser.adapters.object.Episode;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.player.PlayerActivity;
import com.archos.mediacenter.video.player.PlayerService;
import com.archos.mediacenter.video.utils.PlayUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Single playback routing boundary for Local Cinema TV (Engine v2 Readiness).
 *
 * <p>Decouples Library, Home, Details, Series, Add-ons, Live TV, and Watchlist screens from
 * legacy {@link PlayUtils}, {@link PlayerActivity}, {@link PlayerService}, and
 * {@link CinemaHlsActivity} intent wiring so the underlying playback engine can be swapped
 * from this single point in {@code engine-v2}.
 */
public final class CinemaPlaybackRouter {
    /** Canonical MediaDB bookmark value representing a completed (100% watched) item. */
    public static final int BOOKMARK_COMPLETED = PlayerActivity.LAST_POSITION_END;

    /** Canonical MediaDB bookmark value representing an unstarted / reset item. */
    public static final int BOOKMARK_UNKNOWN = PlayerActivity.LAST_POSITION_UNKNOWN;

    private CinemaPlaybackRouter() {}

    public static boolean isCompletedBookmark(long bookmarkOrResume) {
        return bookmarkOrResume == BOOKMARK_COMPLETED;
    }

    /** Launches local, SMB, USB, or UPnP movie/video playback through the active engine. */
    public static void playVideo(Context context, Video video, boolean fromBeginning, long knownResumeMs) {
        if (context == null || video == null) return;
        int resumeMode = (fromBeginning || knownResumeMs <= 0L)
                ? PlayerActivity.RESUME_NO
                : PlayerActivity.RESUME_FROM_LAST_POS;
        int resumePos = fromBeginning ? 0 : (int) Math.max(0L, knownResumeMs);
        PlayUtils.startVideo(context, video, resumeMode, false, resumePos, null, -1L);
    }

    /** Launches an indexed or folder-grouped series episode with full season queue context. */
    public static void playEpisode(
            Context context,
            Episode episode,
            List<Episode> seasonEpisodes,
            String showTitle,
            String showImdb,
            boolean fromBeginning,
            boolean bingeMode,
            long knownResumeMs,
            int requestCode) {
        if (context == null || episode == null) return;
        PlayerService.requestBingePlayMode(bingeMode);
        int resumeMode = (fromBeginning || knownResumeMs <= 0L)
                ? PlayerActivity.RESUME_NO
                : PlayerActivity.RESUME_FROM_LAST_POS;
        int resumePos = fromBeginning ? 0 : (int) Math.max(0L, knownResumeMs);
        PlayUtils.startEpisodeForResult(
                context,
                episode,
                seasonEpisodes != null ? seasonEpisodes : List.of(episode),
                showTitle,
                resumeMode,
                false,
                resumePos,
                -1L,
                requestCode,
                showImdb);
    }

    /** Extracts the episode index returned by the active episode player result intent. */
    public static int extractReturnedEpisodeIndex(Intent data) {
        return data == null ? -1 : data.getIntExtra(PlayUtils.EXTRA_EPISODE_INDEX, -1);
    }

    /** Immutable launch descriptor for VOD streams, Stremio add-ons, torrents, and remote casts. */
    public record StreamRequest(
            Uri uri,
            String format,
            String title,
            String streamKey,
            String mediaType,
            String imdbId,
            long tmdbId,
            int season,
            int episode,
            long resumeMs,
            String englishTitle,
            int releaseYear,
            String addonItemId,
            String addonBaseUrl,
            String addonPoster,
            String addonHeading,
            String videoFilename,
            long videoSizeBytes,
            String videoHash,
            int torrentFileIndex,
            String preparedTorrentToken,
            String subtitleIso2,
            boolean addonSubtitles,
            ArrayList<String> streamSubtitleUrls,
            ArrayList<String> streamSubtitleLangs,
            String userAgent,
            String referer,
            Bundle headers) {

        public static StreamRequest basicVod(
                Uri uri,
                String format,
                String title,
                String streamKey,
                String mediaType,
                String imdbId,
                long tmdbId,
                int season,
                int episode,
                long resumeMs) {
            return new StreamRequest(
                    uri, format, title, streamKey, mediaType, imdbId, tmdbId,
                    season, episode, resumeMs, null, 0, null, null, null, null,
                    null, 0L, null, -1, null, null, false, null, null, null, null, null);
        }
    }

    /** Launches an adaptive HLS/DASH, HTTP progressive, or torrent VOD stream. */
    public static void playStream(Context context, StreamRequest req) {
        if (context == null || req == null || req.uri() == null) return;
        Intent play = new Intent(Intent.ACTION_VIEW, req.uri(), context, CinemaHlsActivity.class);
        if (req.format() != null) play.putExtra("format", req.format());
        if (req.title() != null) play.putExtra("title", req.title());
        if (req.streamKey() != null) play.putExtra("stream_key", req.streamKey());
        if (req.mediaType() != null) play.putExtra("media_type", req.mediaType());
        if (req.imdbId() != null) play.putExtra("imdb_id", req.imdbId());
        if (req.tmdbId() > 0L) play.putExtra("tmdb_id", req.tmdbId());
        if (req.season() > 0) play.putExtra("season", req.season());
        if (req.episode() > 0) play.putExtra("episode", req.episode());
        play.putExtra("resume_ms", Math.max(0L, req.resumeMs()));
        if (req.torrentFileIndex() >= 0) play.putExtra("torrent_file_index", req.torrentFileIndex());
        if (req.subtitleIso2() != null && !req.subtitleIso2().isBlank()) {
            play.putExtra("subtitle_language", req.subtitleIso2());
        }
        if (req.addonSubtitles()) play.putExtra("addon_subtitles", true);
        if (req.englishTitle() != null && !req.englishTitle().isBlank()) {
            play.putExtra("english_title", req.englishTitle());
        }
        if (req.releaseYear() > 0) play.putExtra("release_year", req.releaseYear());
        if (req.addonItemId() != null) play.putExtra("addon_item_id", req.addonItemId());
        if (req.addonBaseUrl() != null) play.putExtra("addon_base_url", req.addonBaseUrl());
        if (req.addonPoster() != null) play.putExtra("addon_poster", req.addonPoster());
        if (req.addonHeading() != null) play.putExtra("addon_heading", req.addonHeading());
        if (req.videoFilename() != null) play.putExtra("video_filename", req.videoFilename());
        if (req.videoSizeBytes() > 0L) play.putExtra("video_size", req.videoSizeBytes());
        if (req.videoHash() != null) play.putExtra("video_hash", req.videoHash());
        if (req.preparedTorrentToken() != null && !req.preparedTorrentToken().isBlank()) {
            play.putExtra(CinemaTorrentStream.PREPARED_TOKEN, req.preparedTorrentToken());
        }
        if (req.streamSubtitleUrls() != null && !req.streamSubtitleUrls().isEmpty()) {
            play.putStringArrayListExtra("stream_subtitle_urls", req.streamSubtitleUrls());
            if (req.streamSubtitleLangs() != null) {
                play.putStringArrayListExtra("stream_subtitle_langs", req.streamSubtitleLangs());
            }
        }
        attachHeaders(play, req.userAgent(), req.referer(), req.headers());
        if (!(context instanceof Activity)) {
            play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(play);
    }

    /** Immutable launch descriptor for Live TV / IPTV / catalog channel playback. */
    public record LiveChannelRequest(
            Uri uri,
            String format,
            String title,
            String streamKey,
            String mediaType,
            String posterUrl,
            String heading,
            String userAgent,
            String referer,
            Bundle headers,
            ArrayList<String> fallbackUrls,
            ArrayList<String> fallbackLabels,
            ArrayList<String> fallbackFormats,
            ArrayList<Bundle> fallbackHeaders) {}

    /** Launches a Live TV / IPTV channel with custom headers and automatic failover candidates. */
    public static void playLiveChannel(Context context, LiveChannelRequest req) {
        if (context == null || req == null || req.uri() == null) return;
        Intent play = new Intent(Intent.ACTION_VIEW, req.uri(), context, CinemaHlsActivity.class)
                .putExtra("format", req.format() == null ? "hls" : req.format())
                .putExtra("addon_subtitles", false)
                .putExtra("is_live_cast", true)
                .putExtra(CinemaHlsActivity.LIVE_CHANNEL, true);
        if (req.title() != null) play.putExtra("title", req.title());
        if (req.streamKey() != null) play.putExtra("stream_key", req.streamKey());
        if (req.mediaType() != null) play.putExtra("media_type", req.mediaType());
        if (req.posterUrl() != null) play.putExtra("addon_poster", req.posterUrl());
        if (req.heading() != null) play.putExtra("addon_heading", req.heading());
        attachHeaders(play, req.userAgent(), req.referer(), req.headers());
        if (req.fallbackUrls() != null && !req.fallbackUrls().isEmpty()) {
            play.putStringArrayListExtra(CinemaHlsActivity.FALLBACK_URLS, req.fallbackUrls());
            if (req.fallbackLabels() != null) {
                play.putStringArrayListExtra(CinemaHlsActivity.FALLBACK_LABELS, req.fallbackLabels());
            }
            if (req.fallbackFormats() != null) {
                play.putStringArrayListExtra(CinemaHlsActivity.FALLBACK_FORMATS, req.fallbackFormats());
            }
            if (req.fallbackHeaders() != null) {
                play.putParcelableArrayListExtra(CinemaHlsActivity.FALLBACK_HEADERS, req.fallbackHeaders());
            }
        }
        if (!(context instanceof Activity)) {
            play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(play);
    }

    public static Bundle mapToHeadersBundle(Map<String, String> headers) {
        Bundle b = new Bundle();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null
                        && !e.getKey().isBlank() && !e.getValue().isBlank()) {
                    b.putString(e.getKey(), e.getValue());
                }
            }
        }
        return b;
    }

    private static void attachHeaders(Intent play, String userAgent, String referer, Bundle headers) {
        if (userAgent != null && !userAgent.isBlank()) {
            play.putExtra(CinemaHlsActivity.CHANNEL_USER_AGENT, userAgent);
        }
        if (referer != null && !referer.isBlank()) {
            play.putExtra(CinemaHlsActivity.CHANNEL_REFERER, referer);
        }
        if (headers != null && !headers.isEmpty()) {
            play.putExtra(CinemaHlsActivity.CHANNEL_HEADERS, headers);
        }
    }
}

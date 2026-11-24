package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.widget.Toast;
import com.archos.mediacenter.video.R;

import com.archos.mediacenter.video.browser.adapters.object.Base;
import com.archos.mediacenter.video.browser.adapters.object.Episode;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Tvshow;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.meedx.providers.OpenMetadata;
import com.meedx.providers.StremioAddons;

import java.util.List;
import java.util.concurrent.ExecutorService;

/** Shared TV Addon stream resolver and picker for CinemaDetailsActivity and CinemaSeriesActivity. */
final class CinemaAddonPicker {
    private CinemaAddonPicker() {}

    static boolean requiresSources(Video video) {
        String path = video.getFilePath();
        return path != null && (path.startsWith("trakt://") || path.startsWith("addon://"));
    }

    static void show(Activity activity, ExecutorService io, Handler mainHandler,
                     Base item, OpenMetadata.Item publicMetadata, int seasonOverride, int episodeOverride) {
        if (activity == null || item == null) return;
        if (StremioAddons.list(activity).stream().noneMatch(addon -> addon.enabled && StremioAddons.mayStream(addon))) {
            Toast.makeText(activity,
                    CinemaUi.label(activity, "فعّل إضافة Stremio / Nuvio من الإعدادات أولاً",
                            "Enable a Stremio / Nuvio addon in Settings first"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Context app = activity.getApplicationContext();
        request(activity, io, mainHandler, () -> {
            boolean isSeries = item instanceof Tvshow || item instanceof Episode
                    || (!(item instanceof Movie) && (seasonOverride > 0 || episodeOverride > 0));
            String mediaType = isSeries ? "series" : "movie";
            int season = item instanceof Episode ep ? Math.max(0, ep.getSeasonNumber()) : Math.max(1, seasonOverride);
            int episode = item instanceof Episode ep ? Math.max(1, ep.getEpisodeNumber()) : Math.max(1, episodeOverride);
            String title = item instanceof Episode ep
                    ? (ep.getShowName() != null && !ep.getShowName().isBlank() ? ep.getShowName() : item.getName())
                    + " S" + season + "E" + episode
                    : item.getName();

            String imdbId = resolveImdbId(app, item, publicMetadata, isSeries);
            long tmdbId = 0L;
            try {
                if (item instanceof Movie m) tmdbId = m.getOnlineId();
                else if (item instanceof Tvshow s) {
                    var st = s.getFullScraperTags(app);
                    if (st != null) tmdbId = st.getOnlineId();
                } else if (item instanceof Episode ep) {
                    var et = ep.getFullScraperTags(app);
                    if (et != null) tmdbId = et.getOnlineId();
                }
            } catch (Exception ignored) {}

            return new Selection(title, imdbId, tmdbId, mediaType, isSeries ? season : 0, isSeries ? episode : 0, null);
        }, () -> true);
    }

    record Selection(String title, String id, long tmdb, String type, int season, int episode,
                     StremioAddons.Addon only, String poster, String heading) {
        Selection(String title, String id, long tmdb, String type, int season, int episode, StremioAddons.Addon only) {
            this(title, id, tmdb, type, season, episode, only, "", title);
        }
    }

    private static androidx.appcompat.app.AlertDialog loading(Activity activity, int message) {
        var row = new android.widget.LinearLayout(activity);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int inset = CinemaUi.dp(activity, 20);
        row.setPaddingRelative(inset, inset, inset, inset);
        var spin = new android.widget.ProgressBar(activity);
        spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xffe5a93b));
        row.addView(spin, new android.widget.LinearLayout.LayoutParams(CinemaUi.dp(activity, 30), CinemaUi.dp(activity, 30)));
        var status = CinemaUi.text(activity, 17, true);
        status.setText(message);
        status.setPaddingRelative(inset, 0, 0, 0);
        row.addView(status, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        return CinemaUi.dialog(activity).setView(row).setNegativeButton(android.R.string.cancel, null).create();
    }

    static <T> java.util.concurrent.Future<?> load(Activity activity, ExecutorService io, Handler handler, int message,
            java.util.concurrent.Callable<T> fetch, java.util.function.Consumer<T> ready) {
        var dialog = loading(activity, message);
        dialog.show();
        var work = submitWhileShowing(io, handler, dialog, () -> {
            T value = null;
            try { value = fetch.call(); } catch (Exception ignored) {}
            T result = value;
            handler.post(() -> {
                if (!dialog.isShowing() || activity.isDestroyed() || activity.isFinishing()) return;
                dialog.setOnDismissListener(null);
                dialog.dismiss();
                ready.accept(result);
            });
        });
        dialog.setOnDismissListener(d -> work.cancel(true));
        return work;
    }

    static java.util.concurrent.Future<?> request(Activity activity, ExecutorService io, Handler handler,
            java.util.concurrent.Callable<Selection> identify, java.util.function.BooleanSupplier active) {
        var loading = loading(activity, R.string.addon_fetching_streams);
        loading.show();
        var app = activity.getApplicationContext();
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> running = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable cancel = () -> {
            closed.set(true);
            var future = running.get();
            if (future != null) future.cancel(true);
        };
        loading.setOnDismissListener(d -> cancel.run());
        var work = submitWhileShowing(io, handler, loading, () -> {
            Selection selection = null;
            try { selection = identify.call(); } catch (Exception ignored) {}
            Selection chosen = selection == null ? null : new Selection(selection.title(),
                    StremioAddons.canonicalStreamId(app, selection.type(), selection.id(), selection.tmdb()),
                    selection.tmdb(), selection.type(), selection.season(), selection.episode(),
                    selection.only(), selection.poster(), selection.heading());
            if (Thread.currentThread().isInterrupted()) return;
            if (chosen == null || chosen.id() == null || chosen.id().isBlank()) {
                handler.post(() -> {
                    if (!loading.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
                    loading.dismiss();
                    Toast.makeText(activity, R.string.addon_missing_id, Toast.LENGTH_LONG).show();
                });
                return;
            }
            String imdb = validImdb(chosen.id()) ? chosen.id() : "";
            boolean isLiveAddonChannel = StremioAddons.isLiveChannelType(chosen.type())
                    || StremioAddons.isLiveChannelAddon(chosen.only());
            StremioAddons.AddonMetaHint metaHint = (!isLiveAddonChannel && chosen.only() != null
                    && (imdb.isEmpty() || com.meedx.providers.UnifiedSubtitleEngine.containsNonLatin(chosen.title())))
                    ? StremioAddons.fetchAddonMetaHint(chosen.only(), chosen.type(), chosen.id())
                    : null;
            if (imdb.isEmpty() && metaHint != null && validImdb(metaHint.imdbId())) {
                imdb = metaHint.imdbId();
            }
            final String resolvedImdb = imdb;
            final String resolvedEnglishTitle = metaHint != null ? metaHint.titleEn() : "";
            final int resolvedYear = metaHint != null ? metaHint.year() : -1;
            final String addonBaseUrl = chosen.only() != null && chosen.only().baseUrl != null ? chosen.only().baseUrl : "";
            String streamKey = "addon:" + chosen.id() + (chosen.episode() > 0 ? ":s" + chosen.season() + "e" + chosen.episode() : "");
            String mediaType = isLiveAddonChannel ? chosen.type() : (chosen.episode() > 0 ? "episode" : "movie");
            long resume = isLiveAddonChannel ? 0L : CinemaWatchStore.findBestStreamResumeMs(app, streamKey, mediaType, resolvedImdb, chosen.tmdb(),
                    chosen.season(), chosen.episode(), chosen.title());
            if (isLiveAddonChannel) {
                var resolved = StremioAddons.resolveStreams(app, chosen.type(), chosen.id(), 0, 0, chosen.only());
                if (Thread.currentThread().isInterrupted()) return;
                var directStreams = new java.util.ArrayList<StremioAddons.Stream>();
                var seenUrls = new java.util.HashSet<String>();
                for (var s : resolved) {
                    if (s == null || s.torrent() || s.url == null || s.url.isBlank()) continue;
                    if (seenUrls.add(s.url)) directStreams.add(s);
                }
                handler.post(() -> {
                    if (closed.get() || activity.isDestroyed() || activity.isFinishing()) return;
                    if (loading.isShowing()) {
                        loading.setOnDismissListener(null);
                        loading.dismiss();
                    }
                    if (!active.getAsBoolean()) return;
                    if (directStreams.isEmpty()) {
                        Toast.makeText(activity, R.string.addon_live_source_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    launchDirectLiveChannel(activity, chosen, mediaType, streamKey, directStreams);
                });
                return;
            }
            var available = new java.util.concurrent.atomic.AtomicReference<List<StremioAddons.Stream>>(List.of());
            var sheet = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<List<StremioAddons.Stream>>>();
            StremioAddons.resolveStreams(app, chosen.type(), chosen.id(), chosen.season(), chosen.episode(), chosen.only(),
                    batch -> {
                        available.set(batch);
                        handler.post(() -> {
                        if (closed.get() || activity.isDestroyed() || activity.isFinishing()) return;
                        if (!active.getAsBoolean()) { loading.dismiss(); return; }
                        if (sheet.get() == null) {
                            if (!loading.isShowing()) return;
                            loading.setOnDismissListener(null);
                            loading.dismiss();
                            sheet.set(showSources(activity, io, handler, chosen, false, batch, (stream, closeSources) -> {
                                if (!active.getAsBoolean()) return;
                                trySource(activity, io, handler, stream, available::get, new java.util.HashSet<>(), active, (source, ready) -> {
                                    Intent play = new Intent(Intent.ACTION_VIEW, Uri.parse(source.url), activity, CinemaHlsActivity.class)
                                        .putExtra("format", source.format).putExtra("torrent_file_index", source.fileIndex)
                                        .putExtra("subtitle_language", CinemaLanguagePreferences.toIso2(CinemaLanguagePreferences.primarySubtitleLanguage(activity)))
                                        .putExtra("addon_subtitles", true)
                                        .putExtra("title", chosen.title()).putExtra("stream_key", streamKey)
                                        .putExtra("media_type", mediaType).putExtra("imdb_id", resolvedImdb)
                                        .putExtra("english_title", resolvedEnglishTitle)
                                        .putExtra("release_year", resolvedYear)
                                        .putExtra("addon_item_id", chosen.id())
                                        .putExtra("addon_base_url", addonBaseUrl)
                                        .putExtra("tmdb_id", chosen.tmdb()).putExtra("season", chosen.season())
                                        .putExtra("episode", chosen.episode()).putExtra("resume_ms", resume)
                                        .putExtra("addon_poster", chosen.poster()).putExtra("addon_heading", chosen.heading());
                                    if (source.subtitles != null && !source.subtitles.isEmpty()) {
                                        var subUrls = new java.util.ArrayList<String>(source.subtitles.size());
                                        var subLangs = new java.util.ArrayList<String>(source.subtitles.size());
                                        for (var sub : source.subtitles) {
                                            subUrls.add(sub.url());
                                            subLangs.add(sub.lang());
                                        }
                                        play.putStringArrayListExtra("stream_subtitle_urls", subUrls);
                                        play.putStringArrayListExtra("stream_subtitle_langs", subLangs);
                                    }
                                    attachStreamHeaders(play, source);
                                    if (ready != null) play.putExtra(CinemaTorrentStream.PREPARED_TOKEN, ready.offerToPlayer());
                                    closeSources.run();
                                    activity.startActivity(play);
                                });
                            }, cancel));
                        } else {
                            sheet.get().accept(batch);
                        }
                        });
                    });
            handler.post(() -> {
                if (closed.get() || activity.isDestroyed() || activity.isFinishing() || !loading.isShowing()) return;
                loading.dismiss();
                if (active.getAsBoolean()) Toast.makeText(activity, R.string.addon_no_streams, Toast.LENGTH_LONG).show();
            });
        });
        running.set(work);
        return work;
    }

    private static void launchDirectLiveChannel(Activity activity, Selection chosen, String mediaType,
                                                String streamKey, List<StremioAddons.Stream> streams) {
        var primary = streams.get(0);
        Intent play = new Intent(Intent.ACTION_VIEW, Uri.parse(primary.url), activity, CinemaHlsActivity.class)
                .putExtra("format", primary.format)
                .putExtra("addon_subtitles", false)
                .putExtra("title", chosen.title())
                .putExtra("stream_key", streamKey)
                .putExtra("media_type", mediaType)
                .putExtra("is_live_cast", true)
                .putExtra(CinemaHlsActivity.LIVE_CHANNEL, true)
                .putExtra("addon_poster", chosen.poster())
                .putExtra("addon_heading", chosen.heading());
        attachStreamHeaders(play, primary);
        if (streams.size() > 1) {
            var urls = new java.util.ArrayList<String>(streams.size());
            var labels = new java.util.ArrayList<String>(streams.size());
            var formats = new java.util.ArrayList<String>(streams.size());
            var headersList = new java.util.ArrayList<android.os.Bundle>(streams.size());
            for (int i = 0; i < Math.min(streams.size(), 12); i++) {
                var s = streams.get(i);
                urls.add(s.url);
                String lbl = s.title != null && !s.title.isBlank() ? s.title : s.addonName + " #" + (i + 1);
                if (s.quality != null && !s.quality.isBlank() && !lbl.contains(s.quality)) {
                    lbl = s.quality + " · " + lbl;
                }
                labels.add(lbl);
                formats.add(s.format == null ? "addon" : s.format);
                headersList.add(headersBundle(s));
            }
            play.putStringArrayListExtra(CinemaHlsActivity.FALLBACK_URLS, urls);
            play.putStringArrayListExtra(CinemaHlsActivity.FALLBACK_LABELS, labels);
            play.putStringArrayListExtra(CinemaHlsActivity.FALLBACK_FORMATS, formats);
            play.putParcelableArrayListExtra(CinemaHlsActivity.FALLBACK_HEADERS, headersList);
        }
        activity.startActivity(play);
    }

    private static void attachStreamHeaders(Intent play, StremioAddons.Stream source) {
        if (source == null) return;
        if (source.userAgent != null && !source.userAgent.isBlank()) {
            play.putExtra(CinemaHlsActivity.CHANNEL_USER_AGENT, source.userAgent);
        }
        if (source.referer != null && !source.referer.isBlank()) {
            play.putExtra(CinemaHlsActivity.CHANNEL_REFERER, source.referer);
        }
        android.os.Bundle bundle = headersBundle(source);
        if (!bundle.isEmpty()) {
            play.putExtra(CinemaHlsActivity.CHANNEL_HEADERS, bundle);
        }
    }

    private static android.os.Bundle headersBundle(StremioAddons.Stream source) {
        var bundle = new android.os.Bundle();
        if (source == null) return bundle;
        if (source.headers != null) {
            for (var entry : source.headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null
                        && !entry.getKey().isBlank() && !entry.getValue().isBlank()) {
                    bundle.putString(entry.getKey(), entry.getValue());
                }
            }
        }
        if (source.userAgent != null && !source.userAgent.isBlank() && !bundle.containsKey("User-Agent")) {
            bundle.putString("User-Agent", source.userAgent);
        }
        if (source.referer != null && !source.referer.isBlank() && !bundle.containsKey("Referer")) {
            bundle.putString("Referer", source.referer);
        }
        return bundle;
    }

    private static void trySource(Activity activity, ExecutorService io, Handler handler,
            StremioAddons.Stream selected, java.util.function.Supplier<List<StremioAddons.Stream>> available,
            java.util.Set<String> attempted, java.util.function.BooleanSupplier active,
            java.util.function.BiConsumer<StremioAddons.Stream, CinemaTorrentStream> play) {
        if (!active.getAsBoolean() || activity.isFinishing() || activity.isDestroyed()) return;
        attempted.add(sourceKey(selected));
        java.util.function.IntConsumer failed = message -> {
            if (!active.getAsBoolean()) return;
            var next = available.get().stream().filter(source -> !attempted.contains(sourceKey(source)))
                    .findFirst().orElse(null);
            if (next == null || attempted.size() >= 3 || message == R.string.addon_torrent_storage) {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(activity, activity.getString(R.string.addon_switching_source, next.addonName), Toast.LENGTH_SHORT).show();
            trySource(activity, io, handler, next, available, attempted, active, play);
        };
        if (selected.torrent()) {
            prepareTorrent(activity, new CinemaTorrentStream(activity, selected.url, selected.fileIndex),
                    ready -> {
                        if (active.getAsBoolean()) play.accept(selected, ready);
                        else ready.release();
                    }, () -> {}, failed);
        } else {
            load(activity, io, handler, R.string.addon_direct_check,
                    () -> StremioAddons.isPlayableDirectStream(selected), playable -> {
                        if (!active.getAsBoolean()) return;
                        if (Boolean.TRUE.equals(playable)) play.accept(selected, null);
                        else failed.accept(R.string.addon_direct_invalid);
                    });
        }
    }

    private static String sourceKey(StremioAddons.Stream source) {
        if (!source.torrent()) return source.url;
        var hash = java.util.regex.Pattern.compile("(?i)btih:([a-z0-9]+)").matcher(source.url);
        return (hash.find() ? hash.group(1).toLowerCase(java.util.Locale.ROOT) : source.url) + ":" + source.fileIndex;
    }

    private static java.util.concurrent.Future<?> submitWhileShowing(ExecutorService io, Handler handler,
            androidx.appcompat.app.AlertDialog dialog, Runnable work) {
        var task = new java.util.concurrent.FutureTask<Void>(work, null) {
            @Override protected void done() {
                if (isCancelled()) handler.post(() -> {
                    if (dialog.isShowing()) dialog.dismiss();
                });
            }
        };
        io.execute(task);
        return task;
    }

    static androidx.appcompat.app.AlertDialog prepareTorrent(Activity activity, CinemaTorrentStream torrent,
            java.util.function.Consumer<CinemaTorrentStream> onReady, Runnable onAbort) {
        return prepareTorrent(activity, torrent, onReady, onAbort,
                message -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
    }

    private static androidx.appcompat.app.AlertDialog prepareTorrent(Activity activity, CinemaTorrentStream torrent,
            java.util.function.Consumer<CinemaTorrentStream> onReady, Runnable onAbort, java.util.function.IntConsumer onFailure) {
        var status = CinemaUi.text(activity, 16, false);
        status.setText(com.archos.mediacenter.video.R.string.addon_torrent_buffering);
        var metrics = CinemaUi.text(activity, 14, true);
        metrics.setTextColor(0xffe5a93b);
        metrics.setPadding(0, CinemaUi.dp(activity, 4), 0, CinemaUi.dp(activity, 8));
        var progress = new android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffe5a93b));
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xffe5a93b));
        var content = new android.widget.LinearLayout(activity);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        int inset = CinemaUi.dp(activity, 24);
        content.setPadding(inset, inset / 2, inset, inset);
        content.addView(status);
        content.addView(metrics);
        content.addView(progress, new android.widget.LinearLayout.LayoutParams(-1, CinemaUi.dp(activity, 20)));
        var dialog = CinemaUi.dialog(activity)
                .setTitle(com.archos.mediacenter.video.R.string.addon_torrent_prepare)
                .setView(content).setNegativeButton(android.R.string.cancel, null).create();
        var main = new Handler(activity.getMainLooper());
        Runnable work = () -> {
            try {
                torrent.prepareWithStatus(state -> main.post(() -> {
                    if (!dialog.isShowing()) return;
                    String speed = android.text.format.Formatter.formatShortFileSize(activity, state.downloadBytesPerSec()) + "/s";
                    metrics.setText(activity.getString(R.string.addon_torrent_live_metrics,
                            state.seeds(), state.peers(), speed));
                    if (state.metadataReady() && state.percent() > 0) {
                        status.setText(activity.getString(R.string.addon_torrent_progress, state.percent()));
                        progress.setIndeterminate(false);
                        progress.setProgress(state.percent());
                    } else {
                        status.setText(R.string.addon_torrent_buffering);
                    }
                }));
                main.post(() -> {
                    if (!dialog.isShowing() || activity.isFinishing() || activity.isDestroyed()) {
                        torrent.release();
                        return;
                    }
                    dialog.setOnDismissListener(null);
                    dialog.dismiss();
                    onReady.accept(torrent);
                });
            } catch (java.io.IOException | RuntimeException | LinkageError failure) {
                torrent.release();
                main.post(() -> {
                    if (!dialog.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
                    dialog.setOnDismissListener(null);
                    dialog.dismiss();
                    onFailure.accept(CinemaTorrentStream.errorMessage(failure));
                });
            }
        };
        var thread = new Thread(work, "tv-torrent-prepare");
        dialog.setOnDismissListener(d -> {
            thread.interrupt();
            torrent.release();
            if (!activity.isDestroyed() && !activity.isFinishing()) onAbort.run();
        });
        dialog.show();
        thread.start();
        return dialog;
    }

    private static java.util.function.Consumer<List<StremioAddons.Stream>> showSources(Activity activity, ExecutorService io,
            Handler handler, Selection chosen, boolean isLiveChannel, List<StremioAddons.Stream> initial,
            java.util.function.BiConsumer<StremioAddons.Stream, Runnable> play, Runnable cancel) {
        String title = chosen.title();
        var streams = new java.util.ArrayList<>(initial);
        var container = new android.widget.LinearLayout(activity);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setLayoutDirection(CinemaUi.arabic(activity) ? android.view.View.LAYOUT_DIRECTION_RTL : android.view.View.LAYOUT_DIRECTION_LTR);
        container.setClipChildren(false);
        container.setClipToPadding(false);

        var header = new android.widget.LinearLayout(activity);
        header.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);
        header.setClipToPadding(false);
        header.setPaddingRelative(CinemaUi.dp(activity, 18), CinemaUi.dp(activity, 12),
                CinemaUi.dp(activity, 18), CinemaUi.dp(activity, 8));
        var headingView = CinemaUi.text(activity, 19, true);
        headingView.setText(activity.getString(R.string.addon_streams_for, title));
        headingView.setTextColor(android.graphics.Color.WHITE);
        headingView.setMaxLines(2);
        headingView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(headingView, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));

        android.widget.TextView trailerBtn = null;
        if (!isLiveChannel) {
            trailerBtn = sourceHeaderPill(activity, activity.getString(R.string.cinema_trailer_short), R.drawable.cinema_trailer);
            var trLp = new android.widget.LinearLayout.LayoutParams(-2, CinemaUi.dp(activity, 38));
            trLp.setMarginStart(CinemaUi.dp(activity, 8));
            header.addView(trailerBtn, trLp);
        }
        var langBtn = sourceHeaderPill(activity, activity.getString(R.string.cinema_audio_lang_short), R.drawable.cinema_ic_audio);
        var lgLp = new android.widget.LinearLayout.LayoutParams(-2, CinemaUi.dp(activity, 38));
        lgLp.setMarginStart(CinemaUi.dp(activity, 8));
        header.addView(langBtn, lgLp);
        container.addView(header, new android.widget.LinearLayout.LayoutParams(-1, -2));

        var list = new android.widget.ListView(activity);
        list.setId(android.view.View.generateViewId());
        list.setBackgroundColor(0xff171717);
        list.setDivider(null);
        list.setPadding(CinemaUi.dp(activity, 12), CinemaUi.dp(activity, 6), CinemaUi.dp(activity, 12), CinemaUi.dp(activity, 8));
        list.setClipToPadding(false);
        var focus = new android.graphics.drawable.GradientDrawable();
        focus.setColor(android.graphics.Color.TRANSPARENT);
        focus.setCornerRadius(CinemaUi.dp(activity, 12));
        focus.setStroke(CinemaUi.dp(activity, 2), 0xffe5a93b);
        list.setSelector(focus);
        list.setDrawSelectorOnTop(true);
        container.addView(list, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));

        if (trailerBtn != null) {
            trailerBtn.setNextFocusDownId(list.getId());
            list.setNextFocusUpId(trailerBtn.getId());
        } else {
            list.setNextFocusUpId(langBtn.getId());
        }
        langBtn.setNextFocusDownId(list.getId());

        var adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return streams.size(); }
            @Override public StremioAddons.Stream getItem(int position) { return streams.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public android.view.View getView(int position, android.view.View reused, android.view.ViewGroup parent) {
                android.widget.LinearLayout card;
                if (reused instanceof android.widget.LinearLayout existing) card = existing;
                else {
                    card = new android.widget.LinearLayout(activity);
                    card.setOrientation(android.widget.LinearLayout.VERTICAL);
                    card.setPaddingRelative(CinemaUi.dp(activity, 18), CinemaUi.dp(activity, 10),
                            CinemaUi.dp(activity, 18), CinemaUi.dp(activity, 10));
                    card.setBackground(CinemaUi.rounded(0xff24211b, CinemaUi.dp(activity, 12), 0xff403524));
                    var heading = new android.widget.LinearLayout(activity);
                    heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    heading.addView(sourceBadge(activity), new android.widget.LinearLayout.LayoutParams(0, -2, 1));
                    heading.addView(sourceBadge(activity));
                    card.addView(heading);
                    var details = new android.widget.LinearLayout(activity);
                    for (int i = 0; i < 3; i++) details.addView(sourceBadge(activity),
                            new android.widget.LinearLayout.LayoutParams(0, -2, i == 0 ? 2 : 1));
                    var detailSize = new android.widget.LinearLayout.LayoutParams(-1, -2);
                    detailSize.topMargin = CinemaUi.dp(activity, 6);
                    card.addView(details, detailSize);
                }
                var stream = getItem(position);
                String seeds = stream.seeds >= 0 ? activity.getString(R.string.addon_seeds_count, stream.seeds)
                        : stream.torrent() ? activity.getString(R.string.addon_seeds_unknown) : "";
                int seedColor = stream.seeds < 0 ? 0xffb9b5ae : stream.seeds == 0 ? 0xfffca5a5
                        : stream.seeds < 10 ? 0xffffba66 : 0xff4ade80;
                int[] providerColors = {0xffe5a93b, 0xff93c5fd, 0xffc4b5fd, 0xff5eead4, 0xfff9a8d4, 0xfffdba74};
                var heading = (android.widget.LinearLayout) card.getChildAt(0);
                bindBadge(heading, 0, stream.addonName,
                        providerColors[Math.floorMod(stream.addonName.hashCode(), providerColors.length)]);
                bindBadge(heading, 1, seeds, seedColor);
                String language = stream.languages.isEmpty() ? activity.getString(R.string.addon_language_unknown)
                        : stream.languages.stream().map(code -> language(activity, code))
                        .collect(java.util.stream.Collectors.joining(" / "));
                String size = stream.sizeBytes > 0 ? android.text.format.Formatter.formatShortFileSize(activity, stream.sizeBytes)
                        : activity.getString(R.string.addon_size_unknown);
                var details = (android.widget.LinearLayout) card.getChildAt(1);
                bindBadge(details, 0, stream.quality, 0xfff5d388);
                bindBadge(details, 1, language, 0xffa5d8ff);
                bindBadge(details, 2, size, 0xffddd5c5);
                card.setContentDescription(android.text.TextUtils.join(" · ", java.util.List.of(
                        stream.addonName, seeds, stream.quality, language, size)));
                return card;
            }
        };
        list.setAdapter(adapter);
        var dialog = CinemaUi.dialog(activity).setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.addon_source_health, null).create();
        langBtn.setOnClickListener(v -> CinemaLanguagePreferences.showDialog(activity, null));
        if (trailerBtn != null) {
            trailerBtn.setOnClickListener(v -> {
                String trailerTitle = chosen.heading() != null && !chosen.heading().isBlank() ? chosen.heading() : title;
                String imdbId = validImdb(chosen.id()) ? chosen.id() : "";
                boolean isSeries = "series".equalsIgnoreCase(chosen.type()) || chosen.episode() > 0;
                load(activity, io, handler, R.string.addon_trailer_loading, () ->
                        com.meedx.providers.OpenTrailersProvider.resolveTrailerUrl(activity.getApplicationContext(),
                                trailerTitle, 0, imdbId, isSeries), url -> {
                    CinemaYouTubeLauncher.openUriOrSearch(activity,
                            url == null || url.isBlank() ? null : Uri.parse(url), trailerTitle, 0);
                });
            });
        }
        dialog.setOnDismissListener(d -> cancel.run());
        list.setOnItemClickListener((parent, row, index, id) -> {
            var stream = adapter.getItem(index);
            play.accept(stream, dialog::dismiss);
        });
        dialog.show();
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener(view -> {
            String[] health = StremioAddons.list(activity).stream().filter(addon -> addon.enabled && StremioAddons.mayStream(addon))
                    .map(addon -> {
                        var result = StremioAddons.getStreamHealth(addon);
                        if (result == null) return activity.getString(R.string.addon_health_waiting, addon.name);
                        return result.unavailable()
                                ? activity.getString(R.string.addon_connection_failed, addon.name, result.millis())
                                : activity.getString(R.string.addon_health_sources, addon.name, result.results(), result.millis());
                    }).toArray(String[]::new);
            CinemaUi.dialog(activity).setTitle(R.string.addon_source_health).setItems(health, null)
                    .setNegativeButton(R.string.addon_back, null).show();
        });
        if (dialog.getWindow() != null) {
            var metrics = activity.getResources().getDisplayMetrics();
            dialog.getWindow().setLayout(Math.min(CinemaUi.dp(activity, 720), (int) (metrics.widthPixels * .88f)),
                    (int) (metrics.heightPixels * .78f));
        }
        list.requestFocus();
        list.setSelection(0);
        return batch -> {
            if (!dialog.isShowing()) return;
            int index = list.getSelectedItemPosition();
            var selected = index >= 0 && index < streams.size() ? streams.get(index) : null;
            streams.clear();
            streams.addAll(batch);
            adapter.notifyDataSetChanged();
            if (selected != null) list.setSelection(Math.max(0, streams.indexOf(selected)));
        };
    }

    private static android.widget.TextView sourceHeaderPill(Activity host, String label, int iconRes) {
        var pill = CinemaUi.text(host, 13, true);
        pill.setId(android.view.View.generateViewId());
        pill.setText(label);
        pill.setTextColor(android.graphics.Color.WHITE);
        pill.setGravity(android.view.Gravity.CENTER);
        pill.setSingleLine();
        pill.setFocusable(true);
        pill.setClickable(true);
        pill.setPaddingRelative(CinemaUi.dp(host, 12), 0, CinemaUi.dp(host, 12), 0);
        var drawable = host.getDrawable(iconRes);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setTint(0xffe5a93b);
            int sz = CinemaUi.dp(host, 16);
            drawable.setBounds(0, 0, sz, sz);
            pill.setCompoundDrawablesRelative(drawable, null, null, null);
            pill.setCompoundDrawablePadding(CinemaUi.dp(host, 6));
        }
        var states = new android.graphics.drawable.StateListDrawable();
        var focusedBg = CinemaUi.rounded(0xff3d2e14, CinemaUi.dp(host, 12), 0xffe5a93b);
        focusedBg.setStroke(CinemaUi.dp(host, 2), 0xffe5a93b);
        states.addState(new int[]{android.R.attr.state_focused}, focusedBg);
        states.addState(new int[]{}, CinemaUi.rounded(0xff25211c, CinemaUi.dp(host, 12), 0xff5a4a33));
        pill.setBackground(states);
        pill.setOnFocusChangeListener((v, focused) -> {
            pill.setTextColor(focused ? 0xffe5a93b : android.graphics.Color.WHITE);
            v.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f).setDuration(100).start();
        });
        return pill;
    }

    private static android.widget.TextView sourceBadge(Activity host) {
        var badge = CinemaUi.text(host, 13, true);
        badge.setMaxLines(2);
        badge.setEllipsize(android.text.TextUtils.TruncateAt.END);
        badge.setPaddingRelative(CinemaUi.dp(host, 9), CinemaUi.dp(host, 3),
                CinemaUi.dp(host, 9), CinemaUi.dp(host, 3));
        badge.setGravity(android.view.Gravity.CENTER_VERTICAL);
        badge.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return badge;
    }

    private static void bindBadge(android.widget.LinearLayout row, int index, String text, int color) {
        var badge = (android.widget.TextView) row.getChildAt(index);
        badge.setVisibility(text.isBlank() ? android.view.View.GONE : android.view.View.VISIBLE);
        badge.setText(text);
        badge.setTextColor(color);
        badge.setBackground(CinemaUi.rounded(0xff24211d, CinemaUi.dp(row.getContext(), 7),
                (color & 0x00ffffff) | 0x55000000));
        var size = (android.widget.LinearLayout.LayoutParams) badge.getLayoutParams();
        size.setMarginEnd(index == row.getChildCount() - 1 ? 0 : CinemaUi.dp(row.getContext(), 6));
    }

    private static String language(Activity activity, String code) {
        if ("multi".equals(code)) return activity.getString(R.string.addon_language_multi);
        return com.meedx.providers.UnifiedSubtitleConfig.languageDisplay(code, CinemaUi.arabic(activity));
    }

    private static String resolveImdbId(Context app, Base item, OpenMetadata.Item publicMetadata, boolean isSeries) {
        if (publicMetadata != null && validImdb(publicMetadata.imdb())) {
            return publicMetadata.imdb();
        }
        if (item instanceof Episode ep) {
            CinemaWatchStore.PlaybackDescriptor desc = CinemaWatchStore.resolvePlaybackDescriptor(
                    app, ep.getId(), ep.getFileUri() != null ? ep.getFileUri().toString() : null, ep.getName());
            if (desc != null && validImdb(desc.imdbId())) return desc.imdbId();
            CinemaWatchStore.Identity epId = CinemaWatchStore.getIdentity(app, "v:" + ep.getId());
            if (epId != null && validImdb(epId.imdbId())) return epId.imdbId();
        } else if (item instanceof Tvshow s) {
            CinemaWatchStore.Identity showId = CinemaWatchStore.getIdentity(app, "s:" + s.getTvshowId());
            if (showId != null && validImdb(showId.imdbId())) return showId.imdbId();
        } else if (item instanceof Video v) {
            CinemaWatchStore.Identity vidId = CinemaWatchStore.getIdentity(app, "v:" + v.getId());
            if (vidId != null && validImdb(vidId.imdbId())) return vidId.imdbId();
        }
        try {
            var tags = item.getFullScraperTags(app);
            if (!isSeries && tags != null && validImdb(tags.getImdbId())) return tags.getImdbId();
            if (tags != null && item instanceof Tvshow && validImdb(tags.getImdbId())) return tags.getImdbId();
        } catch (Exception ignored) {}
        CinemaPosters.Query q = CinemaPosters.query(item);
        String queryTitle = (item instanceof Episode ep && ep.getShowName() != null && !ep.getShowName().isBlank())
                ? ep.getShowName() : q.title();
        OpenMetadata.Item found = OpenMetadata.lookup(app,
                new OpenMetadata.Query(queryTitle, q.year(), isSeries), null, null, 0, 0);
        return found != null && validImdb(found.imdb()) ? found.imdb() : null;
    }

    private static boolean validImdb(String s) {
        return s != null && s.matches("tt[0-9]+");
    }
}

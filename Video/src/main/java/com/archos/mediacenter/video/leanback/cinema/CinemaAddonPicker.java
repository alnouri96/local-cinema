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
            String streamKey = "addon:" + chosen.id() + (chosen.episode() > 0 ? ":s" + chosen.season() + "e" + chosen.episode() : "");
            String mediaType = chosen.episode() > 0 ? "episode" : "movie";
            long resume = CinemaWatchStore.findBestStreamResumeMs(app, streamKey, mediaType, imdb, chosen.tmdb(),
                    chosen.season(), chosen.episode(), chosen.title());
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
                            sheet.set(showSources(activity, chosen.title(), batch, (stream, closeSources) -> {
                                if (!active.getAsBoolean()) return;
                                trySource(activity, io, handler, stream, available::get, new java.util.HashSet<>(), active, (source, ready) -> {
                                    Intent play = new Intent(Intent.ACTION_VIEW, Uri.parse(source.url), activity, CinemaHlsActivity.class)
                                        .putExtra("format", source.format).putExtra("torrent_file_index", source.fileIndex)
                                        .putExtra("subtitle_language", "ar").putExtra("addon_subtitles", true)
                                        .putExtra("title", chosen.title()).putExtra("stream_key", streamKey)
                                        .putExtra("media_type", mediaType).putExtra("imdb_id", imdb)
                                        .putExtra("tmdb_id", chosen.tmdb()).putExtra("season", chosen.season())
                                        .putExtra("episode", chosen.episode()).putExtra("resume_ms", resume)
                                        .putExtra("addon_poster", chosen.poster()).putExtra("addon_heading", chosen.heading());
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
        var progress = new android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffe5a93b));
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xffe5a93b));
        var content = new android.widget.LinearLayout(activity);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        int inset = CinemaUi.dp(activity, 24);
        content.setPadding(inset, inset / 2, inset, inset);
        content.addView(status);
        content.addView(progress, new android.widget.LinearLayout.LayoutParams(-1, CinemaUi.dp(activity, 20)));
        var dialog = CinemaUi.dialog(activity)
                .setTitle(com.archos.mediacenter.video.R.string.addon_torrent_prepare)
                .setView(content).setNegativeButton(android.R.string.cancel, null).create();
        var main = new Handler(activity.getMainLooper());
        Runnable work = () -> {
            try {
                torrent.prepare(percent -> main.post(() -> {
                    if (!dialog.isShowing() || percent == 0) return;
                    status.setText(activity.getString(com.archos.mediacenter.video.R.string.addon_torrent_progress, percent));
                    progress.setIndeterminate(false);
                    progress.setProgress(percent);
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

    private static java.util.function.Consumer<List<StremioAddons.Stream>> showSources(Activity activity, String title,
            List<StremioAddons.Stream> initial, java.util.function.BiConsumer<StremioAddons.Stream, Runnable> play, Runnable cancel) {
        var streams = new java.util.ArrayList<>(initial);
        var list = new android.widget.ListView(activity);
        list.setBackgroundColor(0xff171717);
        list.setDivider(null);
        list.setPadding(0, CinemaUi.dp(activity, 5), 0, CinemaUi.dp(activity, 6));
        list.setClipToPadding(false);
        var focus = new android.graphics.drawable.GradientDrawable();
        focus.setColor(android.graphics.Color.TRANSPARENT);
        focus.setCornerRadius(CinemaUi.dp(activity, 12));
        focus.setStroke(CinemaUi.dp(activity, 2), 0xffe5a93b);
        list.setSelector(focus);
        list.setDrawSelectorOnTop(true);

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
        var dialog = CinemaUi.dialog(activity).setTitle(activity.getString(
                com.archos.mediacenter.video.R.string.addon_streams_for, title)).setView(list)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.addon_source_health, null).create();
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
        var locale = activity.getResources().getConfiguration().getLocales().get(0);
        return java.util.Locale.forLanguageTag(code).getDisplayLanguage(locale);
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

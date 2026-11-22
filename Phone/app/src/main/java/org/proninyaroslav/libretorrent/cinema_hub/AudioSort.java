package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.SharedPreferences;

import org.proninyaroslav.libretorrent.R;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntConsumer;

final class AudioSort {
    private AudioSort() {}

    static void choose(TvHubActivity host, String preference, IntConsumer onSorted) {
        var prefs = host.getSharedPreferences("cinema_music", 0);
        var choices = List.of(
                new CinemaGlassUi.Choice(host.getString(R.string.cinema_sort_name), R.drawable.ic_sort_24px),
                new CinemaGlassUi.Choice(host.getString(R.string.drawer_sorting_date_added), R.drawable.ic_calendar_month_24px),
                new CinemaGlassUi.Choice(host.getString(R.string.cinema_sort_most_played), R.drawable.ic_play_arrow_24px),
                new CinemaGlassUi.Choice(host.getString(R.string.cinema_sort_duration), R.drawable.ic_schedule_24px),
                new CinemaGlassUi.Choice(host.getString(R.string.drawer_sorting_size), R.drawable.ic_storage_24px));
        int selected = prefs.getInt(preference, 1);
        if (selected < 0 || selected >= choices.size()) selected = 1;
        CinemaGlassUi.showChoices(host, R.string.cinema_media_sort, choices, selected, index -> {
            prefs.edit().putInt(preference, index).apply();
            onSorted.accept(index);
        });
    }

    static List<LocalMediaPanel.Item> apply(List<LocalMediaPanel.Item> items, int order, SharedPreferences prefs) {
        Collator collator = Collator.getInstance();
        Comparator<LocalMediaPanel.Item> byTitle = Comparator.comparing(LocalMediaPanel.Item::title, collator);
        Comparator<LocalMediaPanel.Item> comparator = switch (order) {
            case 0 -> byTitle;
            case 2 -> Comparator.<LocalMediaPanel.Item>comparingInt(
                    item -> prefs.getInt(playCountKey(item.uri()), 0)).reversed();
            case 3 -> Comparator.comparingLong(LocalMediaPanel.Item::duration).reversed();
            case 4 -> Comparator.comparingLong(LocalMediaPanel.Item::size).reversed();
            default -> Comparator.comparingLong(LocalMediaPanel.Item::dateAdded).reversed();
        };
        return items.stream().sorted(comparator.thenComparing(byTitle)).toList();
    }

    static String playCountKey(android.net.Uri uri) {
        return "play_count:" + uri;
    }
}

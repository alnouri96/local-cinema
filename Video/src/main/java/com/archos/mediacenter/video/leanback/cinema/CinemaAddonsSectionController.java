package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.os.Handler;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import com.archos.mediacenter.video.R;
import com.meedx.providers.StremioAddons;

import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

final class CinemaAddonsSectionController {
    private static final int EDGE = 136;
    private final Activity host;
    private final ExecutorService io;
    private final Handler main;
    private final Runnable movies;
    private final Consumer<Boolean> live;
    private final Consumer<StremioAddons.Addon> browse;
    private LinearLayout row;

    CinemaAddonsSectionController(Activity host, ExecutorService io, Handler main, Runnable movies,
                                  Consumer<Boolean> live, Consumer<StremioAddons.Addon> browse) {
        this.host = host;
        this.io = io;
        this.main = main;
        this.movies = movies;
        this.live = live;
        this.browse = browse;
    }

    void attach(LinearLayout root, String selected) {
        var scroll = new HorizontalScrollView(host);
        scroll.setHorizontalScrollBarEnabled(false);
        row = new LinearLayout(host);
        row.setPadding(0, CinemaUi.dp(host, 14), 0, CinemaUi.dp(host, 14));
        scroll.addView(row);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        refresh(selected);
    }

    void refresh(String selected) {
        if (row == null) return;
        View previous = row.getFocusedChild();
        if (selected == null && previous != null) selected = (String) previous.getTag();
        row.removeAllViews();
        add(host.getString(R.string.addon_tr_movies), R.drawable.cinema_tr_movies_poster, null, movies);
        add(host.getString(R.string.addon_tv_live), R.drawable.cinema_live_tv, null, () -> live.accept(false));
        add(host.getString(R.string.addon_iptv), R.drawable.cinema_live_playlist, null, () -> live.accept(true));
        String settings = host.getString(R.string.addon_manage);
        add(settings, R.drawable.cinema_addon_settings, null, () -> manager().show());
        for (var addon : StremioAddons.list(host)) {
            add(addon.name, R.drawable.cinema_addons, addon, () -> {
                if (addon.enabled && StremioAddons.mayCatalog(addon)) browse.accept(addon);
                else manager().showAddon(addon);
            });
        }
        View focus = selected == null ? null : row.findViewWithTag(selected);
        (focus == null ? row.getChildAt(0) : focus).requestFocus();
    }

    private CinemaAddonsManagerDialog manager() {
        View focus = row.getFocusedChild();
        String key = focus == null ? null : (String) focus.getTag();
        return new CinemaAddonsManagerDialog(host, io, main, () -> refresh(key));
    }

    private void add(String name, int icon, StremioAddons.Addon addon, Runnable open) {
        View tile = CinemaAddonTilePresenter.create(host, name, icon, addon, io, main, EDGE, open);
        tile.setTag(addon == null ? name : addon.manifestUrl);
        var size = new LinearLayout.LayoutParams(CinemaUi.dp(host, EDGE), CinemaUi.dp(host, EDGE));
        size.setMarginEnd(CinemaUi.dp(host, 16));
        row.addView(tile, size);
    }
}

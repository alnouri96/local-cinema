package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.archos.mediacenter.video.R;
import com.meedx.providers.StremioAddons;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Manages the 6-column square (1:1) Add-ons hub grid, custom D-pad tile reordering,
 * and the top-right header controls (Reorder toggle + Settings gear icon).
 */
final class CinemaAddonsSectionController {
    private static final int GRID_COLUMNS = 6;
    private static final String KEY_TV_LIVE = "builtin:tv_live";
    private static final String KEY_IPTV = "builtin:iptv";

    private record TileItem(String key, String name, int icon, StremioAddons.Addon addon, Runnable open) {}

    private final Activity host;
    private final ExecutorService io;
    private final Handler main;
    private final Consumer<StremioAddons.Addon> openStreamAddon;
    private final Consumer<Boolean> live;
    private final Consumer<StremioAddons.Addon> browseCatalogAddon;

    private final List<TileItem> tiles = new ArrayList<>();
    private RecyclerView grid;
    private TileAdapter adapter;
    private TextView reorderHint;
    private TextView reorderToggleBtn;
    private ImageButton gearBtn;
    private String movingKey;
    private String lastFocusedKey;

    CinemaAddonsSectionController(Activity host, ExecutorService io, Handler main,
                                  Consumer<StremioAddons.Addon> openStreamAddon,
                                  Consumer<Boolean> live,
                                  Consumer<StremioAddons.Addon> browseCatalogAddon) {
        this.host = host;
        this.io = io;
        this.main = main;
        this.openStreamAddon = openStreamAddon;
        this.live = live;
        this.browseCatalogAddon = browseCatalogAddon;
    }

    /** Places the Reorder button and Settings gear icon in the top-right corner of the Add-ons header. */
    void attachHeaderActions(LinearLayout header) {
        boolean rtl = CinemaUi.arabic(host);
        int dp = CinemaUi.dp(host, 1);

        LinearLayout actionGroup = new LinearLayout(host);
        actionGroup.setOrientation(LinearLayout.HORIZONTAL);
        actionGroup.setGravity(Gravity.CENTER_VERTICAL);
        actionGroup.setClipChildren(false);
        actionGroup.setClipToPadding(false);

        reorderToggleBtn = CinemaUi.text(host, 14, true);
        reorderToggleBtn.setId(View.generateViewId());
        reorderToggleBtn.setGravity(Gravity.CENTER);
        reorderToggleBtn.setPaddingRelative(dp * 14, 0, dp * 14, 0);
        reorderToggleBtn.setFocusable(true);
        reorderToggleBtn.setClickable(true);
        styleReorderButton(false);
        reorderToggleBtn.setOnFocusChangeListener((v, focused) -> styleReorderButton(focused));
        reorderToggleBtn.setOnClickListener(v -> {
            if (movingKey != null) {
                exitReorderMode();
            } else if (!tiles.isEmpty()) {
                String target = lastFocusedKey != null ? lastFocusedKey : tiles.get(0).key();
                enterReorderMode(target);
            }
        });
        var reorderLp = new LinearLayout.LayoutParams(-2, dp * 44);
        reorderLp.setMarginEnd(dp * 10);
        actionGroup.addView(reorderToggleBtn, reorderLp);

        gearBtn = new ImageButton(host);
        gearBtn.setId(View.generateViewId());
        gearBtn.setImageResource(R.drawable.cinema_addon_settings);
        gearBtn.setColorFilter(CinemaTokens.GOLD);
        gearBtn.setContentDescription(host.getString(R.string.addon_manage));
        gearBtn.setFocusable(true);
        gearBtn.setClickable(true);
        gearBtn.setBackground(CinemaUi.rounded(0xff25211c, dp * 14, 0xff524332));
        gearBtn.setOnFocusChangeListener((v, focused) -> {
            GradientDrawable ring = CinemaUi.rounded(Color.TRANSPARENT, dp * 14, 0);
            ring.setStroke(dp * 3, CinemaTokens.GOLD);
            v.setForeground(focused ? ring : null);
            v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                    .setDuration(CinemaUi.motion(host) ? CinemaTokens.FOCUS_MS : 0).start();
        });
        gearBtn.setOnClickListener(v -> manager().show());
        actionGroup.addView(gearBtn, new LinearLayout.LayoutParams(dp * 46, dp * 44));

        // Ensure header actions appear at the physical top-right corner with clean breathing room
        var groupLp = new LinearLayout.LayoutParams(-2, -2);
        if (rtl) {
            groupLp.setMarginEnd(dp * 16);
            header.addView(actionGroup, 0, groupLp);
        } else {
            groupLp.setMarginStart(dp * 16);
            header.addView(actionGroup, groupLp);
        }
    }

    private void styleReorderButton(boolean focused) {
        if (reorderToggleBtn == null) return;
        boolean active = movingKey != null;
        reorderToggleBtn.setText(active
                ? host.getString(R.string.addon_reorder_done)
                : host.getString(R.string.addon_reorder_action));
        int bg = focused ? CinemaTokens.GOLD : active ? 0xff3d2f18 : 0xff25211c;
        int border = focused || active ? CinemaTokens.GOLD : 0xff524332;
        reorderToggleBtn.setBackground(CinemaUi.rounded(bg, CinemaUi.dp(host, 14), border));
        reorderToggleBtn.setTextColor(focused ? 0xff141517 : active ? CinemaTokens.GOLD : Color.WHITE);
    }

    void attach(LinearLayout root, String selected) {
        reorderHint = CinemaUi.text(host, 13, false);
        reorderHint.setTextColor(0xffc9beaa);
        reorderHint.setPadding(CinemaUi.dp(host, 4), CinemaUi.dp(host, 6), CinemaUi.dp(host, 4), CinemaUi.dp(host, 4));
        reorderHint.setText(R.string.addon_hub_reorder_hint);
        root.addView(reorderHint, new LinearLayout.LayoutParams(-1, -2));

        grid = new RecyclerView(host);
        grid.setId(View.generateViewId());
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        int pad = CinemaUi.dp(host, 10);
        grid.setPadding(pad, CinemaUi.dp(host, 12), pad, CinemaUi.dp(host, 18));
        grid.setItemAnimator(null);
        grid.setLayoutManager(new GridLayoutManager(host, GRID_COLUMNS));
        adapter = new TileAdapter();
        grid.setAdapter(adapter);
        var viewport = new FrameLayout(host);
        viewport.setClipChildren(true);
        viewport.setClipToPadding(true);
        viewport.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1f));
        refresh(selected);
    }

    boolean isReordering() {
        return movingKey != null;
    }

    void exitReorderMode() {
        if (movingKey == null) return;
        String keep = movingKey;
        movingKey = null;
        styleReorderButton(reorderToggleBtn != null && reorderToggleBtn.hasFocus());
        if (reorderHint != null) reorderHint.setText(R.string.addon_hub_reorder_hint);
        if (adapter != null) adapter.notifyDataSetChanged();
        focusKey(keep);
        Toast.makeText(host, R.string.addon_reorder_saved, Toast.LENGTH_SHORT).show();
    }

    private void enterReorderMode(String key) {
        movingKey = key;
        styleReorderButton(reorderToggleBtn != null && reorderToggleBtn.hasFocus());
        if (reorderHint != null) reorderHint.setText(R.string.addon_reorder_active_hint);
        if (adapter != null) adapter.notifyDataSetChanged();
        focusKey(key);
    }

    void refresh(String selected) {
        if (grid == null) return;
        if (selected == null) selected = lastFocusedKey;

        List<TileItem> raw = new ArrayList<>();
        raw.add(new TileItem(KEY_TV_LIVE, host.getString(R.string.addon_tv_live),
                R.drawable.cinema_live_tv, null, () -> live.accept(false)));
        raw.add(new TileItem(KEY_IPTV, host.getString(R.string.addon_iptv),
                R.drawable.cinema_live_playlist, null, () -> live.accept(true)));

        for (var addon : StremioAddons.list(host)) {
            raw.add(new TileItem(addon.manifestUrl, addon.name, R.drawable.cinema_addons, addon, () -> {
                if (!addon.enabled) {
                    manager().showAddon(addon);
                } else if (StremioAddons.mayCatalog(addon)) {
                    browseCatalogAddon.accept(addon);
                } else if (StremioAddons.mayStream(addon)) {
                    openStreamAddon.accept(addon);
                } else {
                    manager().showAddon(addon);
                }
            }));
        }

        tiles.clear();
        tiles.addAll(CinemaAddonsOrderStore.applyOrder(host, raw, TileItem::key));
        adapter.notifyDataSetChanged();
        focusKey(selected);
    }

    private void focusKey(String key) {
        if (tiles.isEmpty() || grid == null) return;
        int target = 0;
        if (key != null) {
            for (int i = 0; i < tiles.size(); i++) {
                if (key.equals(tiles.get(i).key())) { target = i; break; }
            }
        }
        int pos = target;
        grid.scrollToPosition(pos);
        grid.post(() -> {
            if (host.isDestroyed() || host.isFinishing()) return;
            var holder = grid.findViewHolderForAdapterPosition(pos);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private void moveTile(int fromIndex, int toIndex) {
        if (fromIndex < 0 || toIndex < 0 || fromIndex >= tiles.size() || toIndex >= tiles.size() || fromIndex == toIndex) {
            return;
        }
        String key = tiles.get(fromIndex).key();
        TileItem moved = tiles.remove(fromIndex);
        tiles.add(toIndex, moved);
        persistOrder();
        adapter.notifyDataSetChanged();
        focusKey(key);
    }

    private void persistOrder() {
        List<String> keys = new ArrayList<>(tiles.size());
        for (TileItem item : tiles) keys.add(item.key());
        CinemaAddonsOrderStore.save(host, keys);
    }

    private void showTileMenu(int position) {
        if (position < 0 || position >= tiles.size()) return;
        TileItem item = tiles.get(position);
        boolean rtl = CinemaUi.arabic(host);

        var labels = new ArrayList<String>();
        var actions = new ArrayList<Runnable>();

        int leftDelta = rtl ? 1 : -1;
        int rightDelta = rtl ? -1 : 1;

        labels.add(host.getString(R.string.addon_reorder_interactive));
        actions.add(() -> enterReorderMode(item.key()));

        if (position + rightDelta >= 0 && position + rightDelta < tiles.size()) {
            labels.add(host.getString(R.string.addon_move_right));
            actions.add(() -> moveTile(position, position + rightDelta));
        }
        if (position + leftDelta >= 0 && position + leftDelta < tiles.size()) {
            labels.add(host.getString(R.string.addon_move_left));
            actions.add(() -> moveTile(position, position + leftDelta));
        }
        if (position - GRID_COLUMNS >= 0) {
            labels.add(host.getString(R.string.cinema_move_up));
            actions.add(() -> moveTile(position, position - GRID_COLUMNS));
        }
        if (position + GRID_COLUMNS < tiles.size()) {
            labels.add(host.getString(R.string.cinema_move_down));
            actions.add(() -> moveTile(position, position + GRID_COLUMNS));
        }
        if (item.addon() != null) {
            labels.add(host.getString(R.string.addon_installed_settings));
            actions.add(() -> manager().showAddon(item.addon()));
        }

        CinemaUi.dialog(host)
                .setTitle(item.name())
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(R.string.addon_back, null)
                .show();
    }

    private CinemaAddonsManagerDialog manager() {
        String key = lastFocusedKey;
        return new CinemaAddonsManagerDialog(host, io, main, () -> refresh(key));
    }

    private final class TileAdapter extends RecyclerView.Adapter<TileHolder> {
        @Override public int getItemCount() { return tiles.size(); }

        @Override public TileHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            FrameLayout cell = new FrameLayout(host);
            cell.setClipChildren(false);
            cell.setClipToPadding(false);
            int pad = CinemaUi.dp(host, 7);
            cell.setPadding(pad, pad, pad, pad);
            cell.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return new TileHolder(cell);
        }

        @Override public void onBindViewHolder(TileHolder holder, int position) {
            TileItem item = tiles.get(position);
            boolean moving = item.key().equals(movingKey);
            holder.cell.removeAllViews();
            FrameLayout tile = CinemaAddonTilePresenter.createSquareTile(
                    host, item.name(), item.icon(), item.addon(), io, main, moving, () -> {
                        if (movingKey != null) {
                            exitReorderMode();
                        } else {
                            item.open().run();
                        }
                    });
            tile.setTag(item.key());
            tile.setOnFocusChangeListener((v, focused) -> {
                var ring = CinemaUi.rounded(Color.TRANSPARENT, CinemaUi.dp(host, 16), 0);
                ring.setStroke(CinemaUi.dp(host, 3), CinemaTokens.GOLD);
                v.setForeground(focused ? ring : null);
                float scale = focused ? 1.05f : 1f;
                v.animate().scaleX(scale).scaleY(scale)
                        .setDuration(CinemaUi.motion(host) ? CinemaTokens.FOCUS_MS : 0).start();
                if (focused) lastFocusedKey = item.key();
            });
            tile.setOnLongClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) showTileMenu(pos);
                return true;
            });
            tile.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return false;
                if (keyCode == KeyEvent.KEYCODE_MENU) {
                    showTileMenu(pos);
                    return true;
                }
                if (movingKey != null && movingKey.equals(item.key())) {
                    boolean rtl = CinemaUi.arabic(host);
                    int target = pos;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) target = pos + (rtl ? 1 : -1);
                    else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) target = pos + (rtl ? -1 : 1);
                    else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) target = pos - GRID_COLUMNS;
                    else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) target = pos + GRID_COLUMNS;
                    else if (keyCode == KeyEvent.KEYCODE_BACK) { exitReorderMode(); return true; }
                    else return false;
                    if (target >= 0 && target < tiles.size()) moveTile(pos, target);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && pos < GRID_COLUMNS && gearBtn != null) {
                    gearBtn.requestFocus();
                    return true;
                }
                return CinemaUi.moveVerticalGridFocus(grid, v, event, null);
            });
            holder.cell.addView(tile, new FrameLayout.LayoutParams(-1, -2));
        }
    }

    private static final class TileHolder extends RecyclerView.ViewHolder {
        final FrameLayout cell;
        TileHolder(FrameLayout cell) {
            super(cell);
            this.cell = cell;
        }
    }
}

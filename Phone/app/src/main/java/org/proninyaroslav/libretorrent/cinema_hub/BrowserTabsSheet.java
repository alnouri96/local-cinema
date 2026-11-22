package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.util.List;
import org.mozilla.geckoview.GeckoSession;

final class BrowserTabsSheet {
    private static final int GOLD = 0xffe5b84b;
    private static final int INK = 0xff18181b;
    private static final int TEXT = 0xfffafafa;
    private static final int MUTED = 0xffa1a1aa;
    private final Context context;
    private final Actions actions;
    private final BottomSheetDialog dialog;
    private final RecyclerView list;
    private final TabAdapter adapter;
    private final TextView count;
    private TextView closeOthers;

    record Tab(GeckoSession session, String title, String url, boolean active, boolean home, boolean privateMode, boolean audioPlaying) {}

    interface Actions {
        List<Tab> tabs();
        void select(GeckoSession tab);
        void toggleAudio(GeckoSession tab);
        void close(GeckoSession tab);
        void closeOthers();
        void closeAll();
        void open(boolean privateMode);
    }

    BrowserTabsSheet(Context context, Actions actions) {
        this.context = context;
        this.actions = actions;
        dialog = new BottomSheetDialog(context);
        var panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(10), dp(16), dp(12));
        var surface = shape(INK, 24);
        float radius = dp(24);
        surface.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        panel.setBackground(surface);
        var handle = new View(context);
        handle.setBackground(shape(0xff48484e, 4));
        var grip = new LinearLayout.LayoutParams(dp(36), dp(4));
        grip.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(handle, grip);

        var header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        var title = text(context.getString(org.proninyaroslav.libretorrent.R.string.cinema_browser_tabs), 20, TEXT, true);
        header.addView(title);
        count = text("", 12, INK, true);
        count.setGravity(Gravity.CENTER);
        count.setMinWidth(dp(26));
        count.setPadding(dp(5), 0, dp(5), 0);
        count.setBackground(shape(GOLD, 14));
        var badge = new LinearLayout.LayoutParams(-2, dp(26));
        badge.setMarginStart(dp(8));
        header.addView(count, badge);
        header.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1));
        header.addView(closeButton("Close browser tabs", 0xff2a2a2e, dialog::dismiss));
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(54)));

        var bar = new LinearLayout(context);
        quickAction(bar, context.getString(org.proninyaroslav.libretorrent.R.string.cinema_new_tab_label), GOLD, INK, () -> open(false));
        quickAction(bar, "◉ Private", 0xff26262a, TEXT, () -> open(true));
        closeOthers = quickAction(bar, context.getString(org.proninyaroslav.libretorrent.R.string.cinema_close_others), 0xff26262a, TEXT, () -> {
            actions.closeOthers();
            refresh(true);
        });
        quickAction(bar, context.getString(org.proninyaroslav.libretorrent.R.string.cinema_close_all), 0xff2a1f21, 0xfff26b6b, () -> {
            actions.closeAll();
            dialog.dismiss();
        });
        panel.addView(bar);

        list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        adapter = new TabAdapter();
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        int height = context.getResources().getDisplayMetrics().heightPixels;
        var viewport = new LinearLayout.LayoutParams(-1, Math.min(dp(500), height / 2));
        viewport.topMargin = dp(10);
        panel.addView(list, viewport);
        attachSwipe();
        dialog.setContentView(panel);
        dialog.setOnShowListener(ignored -> {
            if (panel.getParent() instanceof View parent) parent.setBackgroundColor(Color.TRANSPARENT);
            dialog.getBehavior().setDraggable(false);
            dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        });
    }

    void show() {
        refresh(true);
        dialog.show();
    }

    private void open(boolean privateMode) {
        dialog.dismiss();
        actions.open(privateMode);
    }

    private void refresh(boolean seeActive) {
        adapter.tabs = actions.tabs();
        adapter.notifyDataSetChanged();
        count.setText(Integer.toString(adapter.tabs.size()));
        closeOthers.setEnabled(adapter.tabs.size() > 1);
        closeOthers.setAlpha(closeOthers.isEnabled() ? 1f : .55f);
        if (seeActive) {
            for (int i = 0; i < adapter.tabs.size(); i++) {
                if (adapter.tabs.get(i).active()) {
                    list.scrollToPosition(i);
                    break;
                }
            }
        }
    }

    private void attachSwipe() {
        var paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override public boolean onMove(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder item,
                                            @NonNull RecyclerView.ViewHolder target) { return false; }

            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
                int position = holder.getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION) return;
                list.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                actions.close(adapter.tabs.get(position).session());
                refresh(false);
            }

            @Override public void onChildDraw(@NonNull Canvas canvas, @NonNull RecyclerView recycler,
                    @NonNull RecyclerView.ViewHolder holder, float dx, float dy, int state, boolean active) {
                View item = holder.itemView;
                if (dx != 0) {
                    paint.setColor(0xff512b31);
                    canvas.drawRoundRect(item.getLeft(), item.getTop(), item.getRight(), item.getBottom(), dp(16), dp(16), paint);
                    paint.setColor(0xffffa0a0);
                    paint.setTextSize(dp(24));
                    paint.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText("×", dx > 0 ? item.getLeft() + dp(24) : item.getRight() - dp(24),
                            (item.getTop() + item.getBottom()) / 2f + dp(8), paint);
                }
                super.onChildDraw(canvas, recycler, holder, dx, dy, state, active);
            }
        }).attachToRecyclerView(list);
    }

    private TextView quickAction(LinearLayout bar, String label, int fill, int foreground, Runnable run) {
        var button = text(label, 11.5f, foreground, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(shape(fill, 14));
        button.setOnClickListener(view -> run.run());
        var size = new LinearLayout.LayoutParams(0, dp(48), 1);
        if (bar.getChildCount() != 0) size.setMarginStart(dp(5));
        bar.addView(button, size);
        return button;
    }

    private FrameLayout closeButton(String label, int fill, Runnable run) {
        var target = new FrameLayout(context);
        target.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        target.setContentDescription(label);
        target.setOnClickListener(view -> run.run());
        var cross = text("×", 22, TEXT, false);
        cross.setGravity(Gravity.CENTER);
        cross.setBackground(shape(fill, 17));
        target.addView(cross, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
        return target;
    }

    private final class TabAdapter extends RecyclerView.Adapter<TabHolder> {
        private List<Tab> tabs = List.of();

        @Override public int getItemCount() { return tabs.size(); }

        @NonNull @Override public TabHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new TabHolder(new LinearLayout(context));
        }

        @Override public void onBindViewHolder(@NonNull TabHolder holder, int position) {
            holder.bind(tabs.get(position));
        }
    }

    private final class TabHolder extends RecyclerView.ViewHolder {
        final LinearLayout card;
        final TextView iconText;
        final ImageView icon;
        final TextView title;
        final TextView domain;
        final TextView active;
        final TextView audioBtn;
        final FrameLayout close;
        Tab bound;

        TabHolder(LinearLayout card) {
            super(card);
            this.card = card;
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(12), dp(10), dp(4), dp(10));
            var size = new RecyclerView.LayoutParams(-1, -2);
            size.bottomMargin = dp(8);
            card.setLayoutParams(size);
            var iconFrame = new FrameLayout(context);
            iconFrame.setBackground(shape(0xff2a2a2e, 10));
            iconText = text("", 18, GOLD, true);
            iconText.setGravity(Gravity.CENTER);
            iconFrame.addView(iconText, new FrameLayout.LayoutParams(-1, -1));
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iconFrame.addView(icon, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
            card.addView(iconFrame, new LinearLayout.LayoutParams(dp(38), dp(38)));

            var copy = new LinearLayout(context);
            copy.setOrientation(LinearLayout.VERTICAL);
            var copySize = new LinearLayout.LayoutParams(0, -2, 1);
            copySize.setMarginStart(dp(10));
            card.addView(copy, copySize);
            title = text("", 14.5f, TEXT, true);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            copy.addView(title);
            domain = text("", 12, MUTED, false);
            domain.setMaxLines(1);
            domain.setEllipsize(TextUtils.TruncateAt.END);
            copy.addView(domain);
            audioBtn = text("🔊", 14, GOLD, true);
            audioBtn.setGravity(Gravity.CENTER);
            var audioBg = shape(0xff2c2618, 12);
            audioBg.setStroke(dp(1), GOLD);
            audioBtn.setBackground(audioBg);
            audioBtn.setContentDescription(audioBtn.getContext().getString(org.proninyaroslav.libretorrent.R.string.cinema_browser_mute_tab));
            var audioLp = new LinearLayout.LayoutParams(dp(38), dp(34));
            audioLp.setMarginEnd(dp(6));
            audioBtn.setOnClickListener(view -> {
                if (getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
                actions.toggleAudio(bound.session());
                refresh(false);
            });
            card.addView(audioBtn, audioLp);
            active = text(context.getString(org.proninyaroslav.libretorrent.R.string.cinema_active), 10, GOLD, true);
            active.setPadding(dp(6), dp(2), dp(6), dp(2));
            active.setBackground(shape(0xff383023, 8));
            card.addView(active);
            close = closeButton("Close tab", 0xff2e2e33, () -> {
                if (getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
                actions.close(bound.session());
                refresh(false);
            });
            card.addView(close);
            card.setOnClickListener(view -> {
                if (getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
                actions.select(bound.session());
                dialog.dismiss();
            });
        }

        void bind(Tab tab) {
            bound = tab;
            var border = shape(0xff222226, 16);
            border.setStroke(dp(tab.active() ? 2 : 1), tab.active() ? GOLD : 0xff2e2e32);
            card.setBackground(border);
            card.setSelected(tab.active());
            title.setText(tab.title() == null || tab.title().isBlank() ? context.getString(org.proninyaroslav.libretorrent.R.string.cinema_new_tab) : tab.title());
            String host = tab.url() == null ? null : Uri.parse(tab.url()).getHost();
            domain.setText(host != null ? host : tab.home() ? context.getString(org.proninyaroslav.libretorrent.R.string.cinema_browser_home) : tab.privateMode() ? "Private browsing" : "");
            domain.setVisibility(domain.length() == 0 ? View.GONE : View.VISIBLE);
            active.setVisibility(tab.active() ? View.VISIBLE : View.GONE);
            audioBtn.setVisibility(tab.audioPlaying() ? View.VISIBLE : View.GONE);
            close.setContentDescription(close.getContext().getString(org.proninyaroslav.libretorrent.R.string.cinema_browser_close_named_tab, title.getText()));
            icon.setImageDrawable(null);
            iconText.setVisibility(View.VISIBLE);
            iconText.setText(tab.home() ? "⌂" : tab.privateMode() ? "◉"
                    : host == null || host.isEmpty() ? "•" : host.substring(0, 1).toUpperCase(java.util.Locale.ROOT));
            if (!tab.privateMode() && host != null) {
                SiteFaviconManager.loadCached(context, tab.url(), bitmap -> {
                    if (bound != tab) return;
                    icon.setImageBitmap(bitmap);
                    iconText.setVisibility(View.INVISIBLE);
                });
            }
        }
    }

    private TextView text(String value, float size, int color, boolean bold) {
        var text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(null, Typeface.BOLD);
        return text;
    }

    private GradientDrawable shape(int color, int radius) {
        var shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

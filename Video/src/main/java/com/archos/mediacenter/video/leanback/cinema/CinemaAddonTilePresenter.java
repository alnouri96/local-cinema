package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.archos.mediacenter.video.R;
import com.meedx.providers.StremioAddons;
import com.squareup.picasso.Picasso;

import java.util.concurrent.ExecutorService;

/** Square (1:1) glassmorphic add-on tile with official logo, status dot, and unclipped gold focus ring. */
final class CinemaAddonTilePresenter {
    private CinemaAddonTilePresenter() {}

    static FrameLayout createSquareTile(Context context, String name, int fallbackIcon, StremioAddons.Addon addon,
                                        ExecutorService io, Handler main, boolean moving, Runnable open) {
        var tile = new FrameLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = View.MeasureSpec.getSize(widthSpec);
                int exact = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
                super.onMeasure(exact, exact);
            }
        };
        configureTile(tile, name, fallbackIcon, addon, io, main, 140, moving, open);
        return tile;
    }

    static FrameLayout create(Context context, String name, int icon, StremioAddons.Addon addon,
                              ExecutorService io, Handler main, int edge, Runnable open) {
        var tile = new FrameLayout(context);
        configureTile(tile, name, icon, addon, io, main, edge, false, open);
        return tile;
    }

    private static void configureTile(FrameLayout tile, String name, int fallbackIcon, StremioAddons.Addon addon,
                                      ExecutorService io, Handler main, int edgeHint, boolean moving, Runnable open) {
        Context context = tile.getContext();
        var brand = addon == null ? null : StremioAddons.builtIn(addon);
        int accent = brand != null ? brand.accent() : addon == null ? 0xff3b2d1b
                : Color.HSVToColor(new float[]{Math.floorMod(addon.id.hashCode(), 360), .42f, .28f});
        int radius = CinemaUi.dp(context, 16);
        var surface = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{accent, CinemaTokens.CANVAS});
        surface.setCornerRadius(radius);
        surface.setStroke(CinemaUi.dp(context, moving ? 2 : 1), moving ? CinemaTokens.GOLD : 0xff4c4235);
        tile.setBackground(surface);
        tile.setClipToOutline(true);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setLongClickable(true);
        tile.setOnClickListener(v -> { if (open != null) open.run(); });

        var focusRing = CinemaUi.rounded(Color.TRANSPARENT, radius, 0);
        focusRing.setStroke(CinemaUi.dp(context, 3), CinemaTokens.GOLD);
        tile.setOnFocusChangeListener((v, focused) -> {
            v.setForeground(focused ? focusRing : null);
            float scale = focused ? 1.05f : 1f;
            v.animate().scaleX(scale).scaleY(scale)
                    .setDuration(CinemaUi.motion(context) ? CinemaTokens.FOCUS_MS : 0).start();
        });

        LinearLayout inner = new LinearLayout(context);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER);
        inner.setPadding(CinemaUi.dp(context, 8), CinemaUi.dp(context, 10),
                CinemaUi.dp(context, 8), CinemaUi.dp(context, 8));
        tile.addView(inner, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout logoFrame = new FrameLayout(context);
        logoFrame.setClipToOutline(true);
        logoFrame.setBackground(CinemaUi.rounded(0x33101012, CinemaUi.dp(context, 12), 0));
        int logoBox = CinemaUi.dp(context, Math.min(56, Math.max(44, edgeHint * 2 / 5)));
        var logoBoxLp = new LinearLayout.LayoutParams(logoBox, logoBox);
        logoBoxLp.bottomMargin = CinemaUi.dp(context, 6);
        inner.addView(logoFrame, logoBoxLp);

        ImageView logo = new ImageView(context);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int logoPad = CinemaUi.dp(context, 5);
        logo.setPadding(logoPad, logoPad, logoPad, logoPad);
        logoFrame.addView(logo, new FrameLayout.LayoutParams(-1, -1));

        int art = brand == null ? 0 : context.getResources().getIdentifier(brand.artwork(), "drawable", context.getPackageName());
        logo.setImageResource(art == 0 ? fallbackIcon : art);
        if (addon != null && art == 0 && io != null && main != null) {
            io.submit(() -> {
                String url = StremioAddons.manifestLogo(addon);
                main.post(() -> {
                    if (logo.isAttachedToWindow() && (url.startsWith("https://") || url.startsWith("http://"))) {
                        Picasso.get().load(url).resize(logoBox, logoBox).centerInside()
                                .placeholder(fallbackIcon).error(fallbackIcon).into(logo);
                    }
                });
            });
        }

        TextView caption = CinemaUi.text(context, 13, true);
        caption.setText(name);
        caption.setTextColor(Color.WHITE);
        caption.setGravity(Gravity.CENTER);
        caption.setMaxLines(2);
        caption.setLineSpacing(CinemaUi.dp(context, 1), 1f);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        inner.addView(caption, new LinearLayout.LayoutParams(-1, -2));

        if (addon == null) {
            tile.setContentDescription(name);
        } else {
            int state = addon.enabled ? R.string.addon_enabled_label : R.string.addon_disabled_label;
            tile.setContentDescription(context.getString(R.string.addon_tile_state, addon.name, context.getString(state)));
            View dot = new View(context);
            GradientDrawable indicator = new GradientDrawable();
            indicator.setShape(GradientDrawable.OVAL);
            indicator.setColor(addon.enabled ? 0xff48d581 : 0xff9a938b);
            dot.setBackground(indicator);
            int size = CinemaUi.dp(context, 9);
            var position = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
            position.topMargin = CinemaUi.dp(context, 10);
            position.setMarginEnd(CinemaUi.dp(context, 10));
            tile.addView(dot, position);
            tile.setActivated(addon.enabled);
        }

        if (moving) {
            TextView moveBadge = CinemaUi.text(context, 11, true);
            moveBadge.setText("◄ ▲ ▼ ►");
            moveBadge.setTextColor(0xFF141517);
            moveBadge.setGravity(Gravity.CENTER);
            moveBadge.setPadding(CinemaUi.dp(context, 6), CinemaUi.dp(context, 2),
                    CinemaUi.dp(context, 6), CinemaUi.dp(context, 2));
            moveBadge.setBackground(CinemaUi.rounded(CinemaTokens.GOLD, CinemaUi.dp(context, 8), 0));
            var badgeLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            badgeLp.topMargin = CinemaUi.dp(context, 8);
            badgeLp.setMarginStart(CinemaUi.dp(context, 8));
            tile.addView(moveBadge, badgeLp);
        }
    }
}

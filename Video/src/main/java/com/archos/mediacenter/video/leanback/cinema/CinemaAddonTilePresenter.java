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

import com.archos.mediacenter.video.R;
import com.meedx.providers.StremioAddons;
import com.squareup.picasso.Picasso;

import java.util.concurrent.ExecutorService;

final class CinemaAddonTilePresenter {
    private CinemaAddonTilePresenter() {}

    static FrameLayout create(Context context, String name, int icon, StremioAddons.Addon addon,
                              ExecutorService io, Handler main, int edge, Runnable open) {
        var tile = new FrameLayout(context);
        var brand = addon == null ? null : StremioAddons.builtIn(addon);
        int accent = brand != null ? brand.accent() : addon == null ? 0xff423220
                : Color.HSVToColor(new float[]{Math.floorMod(addon.id.hashCode(), 360), .48f, .32f});
        var surface = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{accent, CinemaTokens.CANVAS});
        surface.setCornerRadius(CinemaUi.dp(context, 16));
        surface.setStroke(CinemaUi.dp(context, 1), 0xff51463a);
        tile.setBackground(surface);
        tile.setFocusable(true);
        tile.setOnClickListener(v -> open.run());
        var focusRing = CinemaUi.rounded(Color.TRANSPARENT, CinemaUi.dp(context, 16), 0);
        focusRing.setStroke(CinemaUi.dp(context, 3), 0xffe5a93b);
        tile.setOnFocusChangeListener((v, focused) -> v.setForeground(focused ? focusRing : null));

        var logo = new ImageView(context);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int logoSize = CinemaUi.dp(context, edge / 2);
        var image = new FrameLayout.LayoutParams(logoSize, logoSize, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        image.topMargin = CinemaUi.dp(context, edge / 7);
        tile.addView(logo, image);
        int art = brand == null ? 0 : context.getResources().getIdentifier(brand.artwork(), "drawable", context.getPackageName());
        logo.setImageResource(art == 0 ? icon : art);
        if (addon != null && art == 0) {
            io.submit(() -> {
                String url = StremioAddons.manifestLogo(addon);
                main.post(() -> {
                    if (logo.isAttachedToWindow() && url.startsWith("https://"))
                        Picasso.get().load(url).resize(logoSize, logoSize).centerInside().placeholder(icon).error(icon).into(logo);
                });
            });
        }

        var caption = CinemaUi.text(context, 15, true);
        caption.setText(name);
        caption.setTextColor(Color.WHITE);
        caption.setGravity(Gravity.CENTER);
        caption.setMaxLines(2);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setPaddingRelative(CinemaUi.dp(context, 5), 0, CinemaUi.dp(context, 5), 0);
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(caption, new FrameLayout.LayoutParams(-1, CinemaUi.dp(context, edge / 3), Gravity.BOTTOM));

        if (addon == null) tile.setContentDescription(name);
        else {
            int state = addon.enabled ? R.string.addon_enabled_label : R.string.addon_disabled_label;
            tile.setContentDescription(context.getString(R.string.addon_tile_state, addon.name, context.getString(state)));
            var dot = new View(context);
            var indicator = new GradientDrawable();
            indicator.setShape(GradientDrawable.OVAL);
            indicator.setColor(addon.enabled ? 0xff48d581 : 0xffa29b94);
            dot.setBackground(indicator);
            int size = CinemaUi.dp(context, 9);
            var position = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
            position.topMargin = CinemaUi.dp(context, 10);
            position.setMarginEnd(CinemaUi.dp(context, 10));
            tile.addView(dot, position);
            tile.setActivated(addon.enabled);
        }
        return tile;
    }
}

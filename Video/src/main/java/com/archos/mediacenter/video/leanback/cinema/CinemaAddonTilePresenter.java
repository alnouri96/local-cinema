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
            loadRemoteLogo(addon, logo, fallbackIcon, logoBox, io, main);
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
            View dot = new View(context);
            dot.setTag("addon_health_dot");
            int size = CinemaUi.dp(context, 10);
            var position = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
            position.topMargin = CinemaUi.dp(context, 10);
            position.setMarginEnd(CinemaUi.dp(context, 10));
            tile.addView(dot, position);
            tile.setActivated(addon.enabled);
            applyHealthState(context, tile, addon, StremioAddons.cachedHealthState(addon));
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

    static void applyHealthState(Context context, FrameLayout tile, StremioAddons.Addon addon,
                                 StremioAddons.AddonHealthState health) {
        if (tile == null || addon == null) return;
        View dot = tile.findViewWithTag("addon_health_dot");
        int dotColor;
        String statusText;
        if (!addon.enabled) {
            dotColor = 0xff6b7280;
            statusText = context.getString(R.string.addon_disabled_label);
        } else if (health == StremioAddons.AddonHealthState.OFFLINE) {
            dotColor = 0xffef4444;
            statusText = context.getString(R.string.addon_unavailable);
        } else if (health == StremioAddons.AddonHealthState.DEGRADED) {
            dotColor = 0xfff59e0b;
            statusText = context.getString(R.string.addon_unavailable);
        } else if (health == StremioAddons.AddonHealthState.HEALTHY) {
            dotColor = 0xff48d581;
            statusText = context.getString(R.string.addon_enabled_label);
        } else {
            dotColor = 0xff9a938b;
            statusText = context.getString(R.string.addon_enabled_label);
        }
        if (dot != null) {
            GradientDrawable indicator = new GradientDrawable();
            indicator.setShape(GradientDrawable.OVAL);
            indicator.setColor(dotColor);
            indicator.setStroke(Math.max(1, CinemaUi.dp(context, 1)), 0x66000000);
            dot.setBackground(indicator);
        }
        tile.setContentDescription(context.getString(R.string.addon_tile_state, addon.name, statusText));
    }

    /** Creates a rounded logo badge bound to the add-on's real built-in or manifest artwork, with a sleek fallback icon. */
    static FrameLayout createLogoBadge(Context context, StremioAddons.Addon addon, int fallbackIcon,
                                       int sizeDp, ExecutorService io, Handler main) {
        int boxPx = CinemaUi.dp(context, sizeDp);
        var brand = addon == null ? null : StremioAddons.builtIn(addon);
        int bg = brand != null ? brand.accent() : 0xff26272d;
        var logoFrame = new FrameLayout(context);
        logoFrame.setClipToOutline(true);
        logoFrame.setBackground(CinemaUi.rounded(bg, CinemaUi.dp(context, 10), 0xff3d3f47));

        var logo = new ImageView(context);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = CinemaUi.dp(context, 5);
        logo.setPadding(pad, pad, pad, pad);
        logoFrame.addView(logo, new FrameLayout.LayoutParams(-1, -1));

        int art = brand == null ? 0 : context.getResources().getIdentifier(brand.artwork(), "drawable", context.getPackageName());
        if (art != 0) {
            logo.setImageResource(art);
        } else {
            var fallback = androidx.core.content.ContextCompat.getDrawable(context, fallbackIcon);
            if (fallback != null) {
                fallback = fallback.mutate();
                fallback.setTint(0xffe5a93b);
                logo.setImageDrawable(fallback);
            }
            if (addon != null) {
                String cachedLogo = addon.logo == null ? "" : addon.logo.trim();
                if (cachedLogo.startsWith("https://") || cachedLogo.startsWith("http://")) {
                    logo.clearColorFilter();
                    Picasso.get().load(cachedLogo).resize(boxPx, boxPx).centerInside()
                            .placeholder(fallbackIcon).error(fallbackIcon).into(logo);
                } else if (io != null && main != null) {
                    loadRemoteLogo(addon, logo, fallbackIcon, boxPx, io, main);
                }
            }
        }
        return logoFrame;
    }

    private static void loadRemoteLogo(StremioAddons.Addon addon, ImageView logo, int fallbackIcon,
                                       int boxPx, ExecutorService io, Handler main) {
        io.submit(() -> {
            String url = StremioAddons.manifestLogo(addon);
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
            main.post(() -> {
                if (logo.isAttachedToWindow()) {
                    logo.clearColorFilter();
                    Picasso.get().load(url).resize(boxPx, boxPx).centerInside()
                            .placeholder(fallbackIcon).error(fallbackIcon).into(logo);
                }
            });
        });
    }
}

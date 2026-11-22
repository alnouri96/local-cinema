package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * OpenBot-inspired dark frosted glassmorphism surface with subtle corner ambient glows
 * and dynamic multi-color glowing borders that adapt to section/connection state while
 * keeping foreground text razor-sharp and high-contrast.
 */
final class CinemaGlassUi {

    enum Accent {
        CONNECTION_ONLINE,
        CONNECTION_OFFLINE,
        TRAKT,
        ADDONS,
        LIBRARY_ACTIVE,
        MUSIC,
        ERROR,
        SUBTLE
    }

    private CinemaGlassUi() {}

    static Drawable glassCard(Context context, Accent accent, int radiusDp) {
        return new GlowingGlassDrawable(context, accent, radiusDp, false);
    }

    static Drawable glassPill(Context context, Accent accent, int radiusDp) {
        return new GlowingGlassDrawable(context, accent, radiusDp, false);
    }

    static Drawable glassDock(Context context, Accent accent) {
        return new GlowingGlassDrawable(context, accent, 24, true);
    }

    record Choice(String label, int icon) {}

    static void showChoices(TvHubActivity host, int title, List<Choice> choices, int selected, IntConsumer onSelected) {
        showChoices(host, host.getString(title), choices, selected, onSelected);
    }

    static void showChoices(TvHubActivity host, String title, List<Choice> choices, int selected, IntConsumer onSelected) {
        int gold = 0xffe5a93b;
        int ink = 0xff171717;
        var dialog = new BottomSheetDialog(host);
        var box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPaddingRelative(host.dp(16), host.dp(18), host.dp(16), host.dp(16));

        var surface = new GradientDrawable();
        surface.setColor(ink);
        float corner = host.dp(24);
        surface.setCornerRadii(new float[]{corner, corner, corner, corner, 0, 0, 0, 0});
        surface.setStroke(host.dp(1), 0xff69532c);
        box.setBackground(surface);

        var heading = host.label(title, 20);
        heading.setTextColor(gold);
        heading.setAccessibilityHeading(true);
        heading.setPaddingRelative(host.dp(4), 0, host.dp(4), host.dp(12));
        box.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        var scroll = new ScrollView(host);
        var rows = new LinearLayout(host);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        int maxHeight = Math.round(host.getResources().getDisplayMetrics().heightPixels * .62f);
        int rowHeight = choices.stream().anyMatch(choice -> choice.label().contains("\n")) ? 92 : 52;
        box.addView(scroll, new LinearLayout.LayoutParams(-1, Math.min(maxHeight, host.dp(rowHeight + 6) * choices.size())));

        for (int i = 0; i < choices.size(); i++) {
            int index = i;
            Choice choice = choices.get(i);
            boolean current = index == selected;
            int foreground = current ? ink : 0xfff2ede7;
            var button = new MaterialButton(host, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            button.setText(choice.label());
            button.setAllCaps(false);
            button.setMaxLines(rowHeight == 52 ? 1 : 4);
            button.setEllipsize(TextUtils.TruncateAt.END);
            button.setTextColor(foreground);
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            button.setPaddingRelative(host.dp(14), 0, host.dp(14), 0);
            button.setIconResource(choice.icon());
            button.setIconGravity(MaterialButton.ICON_GRAVITY_START);
            button.setIconTint(ColorStateList.valueOf(current ? ink : gold));
            button.setIconPadding(host.dp(12));
            button.setCornerRadius(host.dp(14));
            button.setInsetTop(0);
            button.setInsetBottom(0);
            button.setBackgroundTintList(ColorStateList.valueOf(current ? gold : 0xff22201c));
            button.setStrokeColor(ColorStateList.valueOf(current ? gold : 0xff534637));
            button.setSelected(current);
            button.setOnClickListener(v -> {
                dialog.dismiss();
                onSelected.accept(index);
            });
            var params = new LinearLayout.LayoutParams(-1, host.dp(rowHeight));
            params.bottomMargin = host.dp(6);
            rows.addView(button, params);
        }

        dialog.setContentView(box);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        var sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        if (selected > 0 && selected < choices.size()) {
            scroll.post(() -> scroll.scrollTo(0, rows.getChildAt(selected).getTop()));
        }
        dialog.show();
    }

    static Accent resolveAccentForSection(Accent base, boolean active) {
        return active ? base : Accent.SUBTLE;
    }

    static final class GlowingGlassDrawable extends Drawable {
        private final float density;
        private Accent accent;
        private final float radiusPx;
        private final boolean topCornersOnly;

        private final RectF fillRect = new RectF();
        private final RectF strokeRect = new RectF();
        private final Path fillPath = new Path();
        private final Path strokePath = new Path();

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint topSpecularPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowCorner1Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowCorner2Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outerHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private float strokeWidthPx;
        private int alpha = 255;

        GlowingGlassDrawable(Context context, Accent accent, int radiusDp, boolean topCornersOnly) {
            this.density = context.getResources().getDisplayMetrics().density;
            this.accent = accent == null ? Accent.SUBTLE : accent;
            this.radiusPx = radiusDp * density;
            this.topCornersOnly = topCornersOnly;
            this.strokeWidthPx = Math.max(1.25f, 1.4f * density);

            fillPaint.setStyle(Paint.Style.FILL);
            topSpecularPaint.setStyle(Paint.Style.FILL);
            glowCorner1Paint.setStyle(Paint.Style.FILL);
            glowCorner2Paint.setStyle(Paint.Style.FILL);

            outerHaloPaint.setStyle(Paint.Style.STROKE);
            outerHaloPaint.setStrokeWidth(strokeWidthPx * 2.4f);

            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(strokeWidthPx);
        }

        Accent getAccent() {
            return accent;
        }

        void setAccent(Accent next) {
            Accent resolved = next == null ? Accent.SUBTLE : next;
            if (this.accent == resolved) return;
            this.accent = resolved;
            rebuildShaders(getBounds());
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            rebuildShaders(bounds);
        }

        private float[] radiiArray(float r) {
            return topCornersOnly
                    ? new float[]{r, r, r, r, 0f, 0f, 0f, 0f}
                    : new float[]{r, r, r, r, r, r, r, r};
        }

        private int[] borderPalette(Accent a) {
            return switch (a) {
                case CONNECTION_ONLINE -> new int[]{
                        0xdd10b981, 0xdd38bdf8, 0xede5b94c, 0xdd22c55e, 0xdd10b981
                };
                case CONNECTION_OFFLINE -> new int[]{
                        0xccf59e0b, 0xaa64748b, 0xcc9a7b38, 0x99475569, 0xccf59e0b
                };
                case TRAKT -> new int[]{
                        0xeee5b94c, 0xddf59e0b, 0xddec4899, 0xdda855f7, 0xeee5b94c
                };
                case ADDONS -> new int[]{
                        0xdd8b5cf6, 0xdd3b82f6, 0xdd06b6d4, 0xdd6366f1, 0xdd8b5cf6
                };
                case LIBRARY_ACTIVE -> new int[]{
                        0xeee5b94c, 0xddfbbf24, 0xdd22d3ee, 0xdd10b981, 0xeee5b94c
                };
                case MUSIC -> new int[]{
                        0xeee5b94c, 0xddfde68a, 0xddd97706, 0xdda78bfa, 0xeee5b94c
                };
                case ERROR -> new int[]{
                        0xeeef4444, 0xddf43f5e, 0xddf59e0b, 0xeeef4444
                };
                case SUBTLE -> new int[]{
                        0x44ffffff, 0x4ae5b94c, 0x4060a5fa, 0x38a78bfa, 0x44ffffff
                };
            };
        }

        private int[] cornerGlowColors(Accent a) {
            return switch (a) {
                case CONNECTION_ONLINE -> new int[]{0x2a10b981, 0x2438bdf8};
                case CONNECTION_OFFLINE -> new int[]{0x24f59e0b, 0x1c64748b};
                case TRAKT -> new int[]{0x28e5b94c, 0x22ec4899};
                case ADDONS -> new int[]{0x288b5cf6, 0x2206b6d4};
                case LIBRARY_ACTIVE -> new int[]{0x2ce5b94c, 0x2222d3ee};
                case MUSIC -> new int[]{0x2ce5b94c, 0x22a78bfa};
                case ERROR -> new int[]{0x2eef4444, 0x24f59e0b};
                case SUBTLE -> new int[]{0x16e5b94c, 0x143b82f6};
            };
        }

        private int haloColor(Accent a) {
            return switch (a) {
                case CONNECTION_ONLINE -> 0x2210b981;
                case CONNECTION_OFFLINE -> 0x1cf59e0b;
                case TRAKT -> 0x22ec4899;
                case ADDONS -> 0x223b82f6;
                case LIBRARY_ACTIVE -> 0x26e5b94c;
                case MUSIC -> 0x24e5b94c;
                case ERROR -> 0x2cef4444;
                case SUBTLE -> 0x10e5b94c;
            };
        }

        private void rebuildShaders(Rect b) {
            if (b == null || b.width() <= 0 || b.height() <= 0) return;
            float w = b.width();
            float h = b.height();
            float inset = strokeWidthPx * 0.6f;

            fillRect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
            strokeRect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);

            float clampedR = Math.min(radiusPx, Math.min(fillRect.width(), fillRect.height()) * 0.5f);
            float[] rad = radiiArray(clampedR);
            fillPath.reset();
            fillPath.addRoundRect(fillRect, rad, Path.Direction.CW);
            strokePath.reset();
            strokePath.addRoundRect(strokeRect, rad, Path.Direction.CW);

            int topSurface = accent == Accent.ERROR ? 0xf022161a : 0xee171a24;
            int botSurface = accent == Accent.ERROR ? 0xeb181014 : 0xe810121a;
            fillPaint.setShader(new LinearGradient(
                    fillRect.left, fillRect.top, fillRect.right * 0.35f, fillRect.bottom,
                    topSurface, botSurface, Shader.TileMode.CLAMP));

            float specularH = Math.max(24f * density, h * 0.42f);
            topSpecularPaint.setShader(new LinearGradient(
                    fillRect.left, fillRect.top, fillRect.left, fillRect.top + specularH,
                    new int[]{0x18ffffff, 0x05ffffff, 0x00ffffff},
                    new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));

            int[] cg = cornerGlowColors(accent);
            float gR1 = Math.max(80f * density, Math.min(w, h) * 0.72f);
            glowCorner1Paint.setShader(new RadialGradient(
                    fillRect.left + w * 0.08f, fillRect.top + h * 0.08f, gR1,
                    new int[]{cg[0], 0x00000000}, null, Shader.TileMode.CLAMP));

            float gR2 = Math.max(70f * density, Math.min(w, h) * 0.65f);
            glowCorner2Paint.setShader(new RadialGradient(
                    fillRect.right - w * 0.08f, fillRect.bottom - h * 0.08f, gR2,
                    new int[]{cg[1], 0x00000000}, null, Shader.TileMode.CLAMP));

            int[] palette = borderPalette(accent);
            borderPaint.setShader(new SweepGradient(b.exactCenterX(), b.exactCenterY(), palette, null));
            outerHaloPaint.setColor(haloColor(accent));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;

            int save = canvas.save();
            canvas.clipPath(fillPath);
            canvas.drawRect(fillRect, fillPaint);
            canvas.drawRect(fillRect, glowCorner1Paint);
            canvas.drawRect(fillRect, glowCorner2Paint);
            canvas.drawRect(fillRect, topSpecularPaint);
            canvas.drawPath(strokePath, outerHaloPaint);
            canvas.restoreToCount(save);

            canvas.drawPath(strokePath, borderPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            if (this.alpha == alpha) return;
            this.alpha = alpha;
            fillPaint.setAlpha(alpha);
            topSpecularPaint.setAlpha(alpha);
            glowCorner1Paint.setAlpha(alpha);
            glowCorner2Paint.setAlpha(alpha);
            outerHaloPaint.setAlpha(alpha);
            borderPaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fillPaint.setColorFilter(colorFilter);
            borderPaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}

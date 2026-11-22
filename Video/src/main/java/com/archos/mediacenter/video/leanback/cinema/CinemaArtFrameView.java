package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

final class CinemaArtFrameView extends FrameLayout {
    static final int FULL_BLEED = 3;
    static final int DEFAULT_SIZE = 100, MIN_SIZE = 25, MAX_SIZE = 200, SIZE_STEP = 5;
    // Center the aperture measured in the 475 × 272 reference (430 × 226).
    private static final float REFERENCE_SIDE = (475f - 430f) / (2f * 475f);
    private static final float REFERENCE_TOP = (272f - 226f) / (2f * 272f);
    private static final int[] MATTE = {0xfff5f3ec, 0xffe5d5b7, CinemaTokens.CANVAS, 0xff000000};
    private final FrameLayout artwork;
    private final ImageView[] layers = new ImageView[2];
    private final View shadow;
    private final View dimmer;
    private int current;
    private int matte;
    private int matteSize = DEFAULT_SIZE;

    CinemaArtFrameView(Context context) {
        super(context);
        setBackgroundColor(CinemaTokens.CANVAS);
        artwork = new FrameLayout(context);
        addView(artwork, new LayoutParams(-1, -1));
        for (int i = 0; i < layers.length; i++) {
            layers[i] = new ImageView(context);
            artwork.addView(layers[i], new LayoutParams(-1, -1));
        }
        shadow = new View(context);
        var top = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x50000000, 0});
        var depth = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                CinemaUi.rounded(0, 0, 0x44000000), top});
        depth.setLayerGravity(1, android.view.Gravity.FILL_HORIZONTAL | android.view.Gravity.TOP);
        depth.setLayerHeight(1, CinemaUi.dp(context, 5));
        shadow.setBackground(depth);
        artwork.addView(shadow, new LayoutParams(0, 0, android.view.Gravity.CENTER));
        artwork.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> positionArtwork());
        dimmer = new View(context);
        dimmer.setBackgroundColor(0xff000000);
        addView(dimmer, new LayoutParams(-1, -1));
    }

    void appearance(int frame, int tone, int brightness, int size) {
        matte = frame;
        matteSize = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
        setBackgroundColor(MATTE[frame]);
        updateMatte(getWidth(), getHeight());
        var matrix = new ColorMatrix();
        if (tone == 1) matrix.setScale(1.04f, 1f, .88f, 1f);
        if (tone == 2) {
            matrix.setSaturation(.67f);
            var fade = new ColorMatrix(new float[]{
                    .88f, 0, 0, 0, 14, 0, .88f, 0, 0, 14, 0, 0, .86f, 0, 13, 0, 0, 0, 1, 0});
            matrix.postConcat(fade);
        }
        if (tone == 3) matrix.setSaturation(.72f);
        var filter = new ColorMatrixColorFilter(matrix);
        for (ImageView layer : layers) layer.setColorFilter(filter);
        dimmer.setAlpha(1f - (brightness / 100f) * (tone == 3 ? .62f : 1f));
    }

    private void updateMatte(int width, int height) {
        var inset = (LayoutParams) artwork.getLayoutParams();
        float scale = matte == FULL_BLEED ? 0 : matteSize / 100f;
        int horizontal = Math.round(width * REFERENCE_SIDE * scale);
        int vertical = Math.round(height * REFERENCE_TOP * scale);
        if (inset.leftMargin != horizontal || inset.topMargin != vertical) {
            inset.setMargins(horizontal, vertical, horizontal, vertical);
            artwork.setLayoutParams(inset);
        }
        positionArtwork();
    }

    private void positionArtwork() {
        int openingWidth = artwork.getWidth();
        int openingHeight = artwork.getHeight();
        if (openingWidth == 0 || openingHeight == 0) return;
        float defaultOpeningRatio = (float) getWidth() / getHeight()
                * (1f - 2f * REFERENCE_SIDE) / (1f - 2f * REFERENCE_TOP);
        for (ImageView layer : layers) {
            var image = layer.getDrawable();
            boolean fill = matte == FULL_BLEED;
            if (!fill && image != null) {
                float imageRatio = (float) image.getIntrinsicWidth() / image.getIntrinsicHeight();
                // Fill the measured opening for nearby aspect ratios and fit substantially different ones.
                fill = Math.min(defaultOpeningRatio, imageRatio) / Math.max(defaultOpeningRatio, imageRatio) >= .90f;
            }
            layer.setScaleType(fill ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        }

        var active = layers[current];
        var image = active.getDrawable();
        if (matte == FULL_BLEED || image == null) {
            shadow.setVisibility(View.INVISIBLE);
            return;
        }
        int width = openingWidth;
        int height = openingHeight;
        if (active.getScaleType() == ImageView.ScaleType.FIT_CENTER) {
            float scale = Math.min((float) openingWidth / image.getIntrinsicWidth(),
                    (float) openingHeight / image.getIntrinsicHeight());
            width = Math.round(image.getIntrinsicWidth() * scale);
            height = Math.round(image.getIntrinsicHeight() * scale);
        }
        var size = (LayoutParams) shadow.getLayoutParams();
        if (size.width != width || size.height != height) {
            size.width = width;
            size.height = height;
            shadow.setLayoutParams(size);
        }
        shadow.setVisibility(View.VISIBLE);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateMatte(width, height);
    }

    void display(Bitmap bitmap, boolean slide) {
        var outgoing = layers[current];
        current = 1 - current;
        var incoming = layers[current];
        incoming.animate().cancel();
        outgoing.animate().cancel();
        incoming.setImageBitmap(bitmap);
        incoming.bringToFront();
        shadow.bringToFront();
        positionArtwork();
        if (slide && outgoing.getDrawable() != null && CinemaUi.motion(getContext())) {
            incoming.setAlpha(0f);
            incoming.animate().alpha(1f).setDuration(620).withEndAction(() -> outgoing.setImageDrawable(null)).start();
        } else {
            incoming.setAlpha(1f);
            outgoing.setImageDrawable(null);
        }
    }

    void shift(int x, int y) {
        artwork.setTranslationX(x);
        artwork.setTranslationY(y);
    }
}

package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.Context;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.widget.SeekBar;

/** Shared timeline for TV audio, local and web video, and torrent casts. */
final class PlaybackSeekBar extends SeekBar {
    PlaybackSeekBar(Context context) {
        super(context);
        int gold = 0xffe5b84b;
        var track = new LayerDrawable(new android.graphics.drawable.Drawable[]{
                shape(0xff3a3a3c, pixels(3)),
                new ClipDrawable(shape(gold, pixels(3)), Gravity.LEFT, ClipDrawable.HORIZONTAL)
        });
        track.setId(0, android.R.id.background);
        track.setId(1, android.R.id.progress);
        for (int layer = 0; layer < 2; layer++) {
            track.setLayerHeight(layer, pixels(6));
            track.setLayerGravity(layer, Gravity.FILL_HORIZONTAL | Gravity.CENTER_VERTICAL);
        }
        setProgressTintList(null);
        setProgressBackgroundTintList(null);
        setThumbTintList(null);
        setProgressDrawable(track);
        var thumb = shape(gold, pixels(10));
        thumb.setSize(pixels(20), pixels(20));
        setThumb(thumb);
        setMinimumHeight(pixels(48));
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        setPadding(pixels(14), 0, pixels(14), 0);
    }

    private GradientDrawable shape(int color, float radius) {
        var shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }

    private int pixels(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}

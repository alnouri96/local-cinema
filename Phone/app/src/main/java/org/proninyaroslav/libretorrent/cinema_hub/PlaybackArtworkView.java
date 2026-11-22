package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.widget.FrameLayout;
import android.view.Gravity;
import android.view.View;

final class PlaybackArtworkView extends FrameLayout {
    private final FrameLayout frame;
    private final float aspectRatio;

    PlaybackArtworkView(Context context, float aspectRatio) {
        super(context);
        this.aspectRatio = aspectRatio;
        float density = getResources().getDisplayMetrics().density;
        float radius = 20 * density;
        float stroke = density;
        var background = new android.graphics.drawable.GradientDrawable();
        background.setColor(0xff171717);
        background.setCornerRadius(radius);
        frame = new FrameLayout(context) {
            private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                border.setColor(0x99e5a93b);
                border.setStyle(Paint.Style.STROKE);
                border.setStrokeWidth(stroke);
                float inset = stroke / 2;
                canvas.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset,
                        radius - inset, radius - inset, border);
            }
        };
        frame.setClipToOutline(true);
        frame.setBackground(background);
        addView(frame, new LayoutParams(0, 0, Gravity.CENTER));
    }

    void addArtwork(View artwork) {
        frame.addView(artwork, new LayoutParams(-1, -1));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int slotWidth = MeasureSpec.getSize(widthSpec);
        int slotHeight = MeasureSpec.getSize(heightSpec);
        var size = frame.getLayoutParams();
        size.height = Math.min(slotHeight, Math.round(slotWidth / aspectRatio));
        size.width = Math.round(size.height * aspectRatio);
        super.onMeasure(widthSpec, heightSpec);
    }
}

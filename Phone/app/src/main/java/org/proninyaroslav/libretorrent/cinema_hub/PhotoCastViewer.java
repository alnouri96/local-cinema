package org.proninyaroslav.libretorrent.cinema_hub;

import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;

import java.util.List;

final class PhotoCastViewer {
    private final TvHubActivity activity;
    private final List<Uri> photos;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final android.app.Dialog dialog;
    private final ImageView image;
    private final LinearLayout header, strip;
    private final HorizontalScrollView filmstrip;
    private final ProgressBar progress;
    private final java.util.concurrent.ExecutorService decoder = java.util.concurrent.Executors.newFixedThreadPool(2);
    private int index, generation, interval;
    private Bitmap bitmap;
    private final android.util.LruCache<Uri, Bitmap> previews = new android.util.LruCache<>(3);
    private boolean casting = true, fill, multiTouch;
    private float scale = 1, x, y, angle;
    private long lastSent, slideStart;
    private final Runnable send = () -> transmit("PHOTO_TRANSFORM");
    private final Runnable slideshow = new Runnable() {
        @Override public void run() {
            if (interval == 0) return;
            long elapsed = android.os.SystemClock.uptimeMillis() - slideStart;
            if (elapsed >= interval) {
                select(Math.floorMod(index + 1, photos.size()));
                elapsed = 0;
            }
            progress.setProgress((int) elapsed);
            handler.postDelayed(this, 50);
        }
    };

    PhotoCastViewer(TvHubActivity activity, List<Uri> photos, int index) {
        this.activity = activity;
        this.photos = photos;
        this.index = index;
        dialog = new android.app.Dialog(activity, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(0xff121212);
        image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.MATRIX);
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));
        image.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> apply());
        header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(12), dp(12), 0);
        root.addView(header, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        header.addView(action("‹", "Back", dialog::dismiss));
        header.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        header.addView(action("↻", "Rotate 90°", () -> {
            angle = (angle + 90) % 360;
            scale = 1;
            x = y = 0;
            apply();
            transmit("PHOTO_TRANSFORM");
        }));
        header.addView(action("■", "Stop Casting", () -> {
            if (casting) {
                transmit("STOP_PHOTO");
                casting = false;
                activity.clearCastNotification();
            }
        }));
        var more = action("⋮", "Photo options", () -> {});
        more.setOnClickListener(v -> menu(more));
        header.addView(more);

        var bottom = new LinearLayout(activity);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(8), 0, dp(8), dp(12));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffe5b84b));
        progress.setVisibility(View.GONE);
        bottom.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        filmstrip = new HorizontalScrollView(activity);
        filmstrip.setHorizontalScrollBarEnabled(false);
        strip = new LinearLayout(activity);
        filmstrip.addView(strip);
        bottom.addView(filmstrip);
        installGestures();
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            handler.removeCallbacksAndMessages(null);
            decoder.shutdownNow();
            generation++;
            activity.onPhotoViewerDismissed(this);
        });
    }

    boolean isCasting() {
        return casting;
    }

    void onTvPhotoEnded() {
        casting = false;
    }

    boolean isSlideshowActive() {
        return interval > 0;
    }

    void show() {
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
        select(index);
    }

    private void select(int next) {
        index = next;
        slideStart = android.os.SystemClock.uptimeMillis();
        int request = ++generation;
        handler.removeCallbacks(send);
        scale = 1;
        x = y = angle = 0;

        Uri uri = photos.get(index);
        if (casting) {
            casting = activity.castPhoto(uri);
            if (casting && fill) transmit("PHOTO_TRANSFORM");
        }
        decoder.execute(() -> {
            Bitmap cached = previews.get(uri);
            Bitmap loaded = cached == null ? decode(uri, 2560) : cached;
            handler.post(() -> {
                if (request != generation || !dialog.isShowing()) return;
                if (loaded == null) {
                    CinemaSnackBanner.show(activity, "Could not open this photo");
                    return;
                }
                bitmap = loaded;
                image.setImageBitmap(bitmap);
                apply();
            });
        });
        for (int neighbor : new int[]{index + 1, index - 1}) {
            if (neighbor < 0 || neighbor >= photos.size()) continue;
            Uri nearby = photos.get(neighbor);
            decoder.execute(() -> {
                if (previews.get(nearby) != null) return;
                Bitmap preview = decode(nearby, 2560);
                if (preview != null) previews.put(nearby, preview);
            });
        }
        strip.removeAllViews();
        for (int i = Math.max(0, index - 6); i < Math.min(photos.size(), index + 7); i++) {
            int position = i;
            var thumb = new ImageView(activity);
            thumb.setContentDescription(activity.getString(org.proninyaroslav.libretorrent.R.string.cinema_photo_index, i + 1, photos.size()));
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setOnClickListener(v -> select(position));
            var border = new android.graphics.drawable.GradientDrawable();
            border.setColor(0xff242424);
            if (i == index) border.setStroke(dp(2), 0xffe5b84b);
            thumb.setBackground(border);
            thumb.setPadding(dp(3), dp(3), dp(3), dp(3));
            var dims = new LinearLayout.LayoutParams(dp(58), dp(58));
            dims.setMarginEnd(dp(4));
            strip.addView(thumb, dims);
            decoder.execute(() -> {
                if (request != generation) return;
                Bitmap preview = decode(photos.get(position), 120);
                handler.post(() -> { if (request == generation) thumb.setImageBitmap(preview); });
            });
        }
        filmstrip.post(() -> filmstrip.scrollTo(dp(62) * Math.max(0, Math.min(6, index) - 2), 0));
    }

    private Bitmap decode(Uri uri, int limit) {
        try {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(activity.getContentResolver(), uri), (decoder, info, source) -> {
                var size = info.getSize();
                float ratio = Math.min(1f, limit / (float) Math.max(size.getWidth(), size.getHeight()));
                decoder.setTargetSize(Math.max(1, Math.round(size.getWidth() * ratio)), Math.max(1, Math.round(size.getHeight() * ratio)));
            });
        } catch (java.io.IOException | RuntimeException failure) {
            return null;
        }
    }

    private void apply() {
        if (bitmap == null || image.getWidth() == 0) return;
        float width = bitmap.getWidth(), height = bitmap.getHeight();
        double radians = Math.toRadians(angle);
        float rotatedWidth = (float) (width * Math.abs(Math.cos(radians)) + height * Math.abs(Math.sin(radians)));
        float rotatedHeight = (float) (width * Math.abs(Math.sin(radians)) + height * Math.abs(Math.cos(radians)));
        float factor = scale * Math.min(image.getWidth() / rotatedWidth, image.getHeight() / rotatedHeight);
        var matrix = new Matrix();
        matrix.postTranslate(-width / 2, -height / 2);
        matrix.postRotate(angle);
        matrix.postScale(factor, factor);
        matrix.postTranslate(image.getWidth() / 2f + x * rotatedWidth * factor / 2,
                image.getHeight() / 2f + y * rotatedHeight * factor / 2);
        image.setImageMatrix(matrix);
    }

    private void transmit(String type) {
        if (!casting) return;
        lastSent = android.os.SystemClock.uptimeMillis();
        activity.photoCommand(type, scale, x, y, angle, fill);
    }

    private void changed() {
        apply();
        handler.removeCallbacks(send);
        handler.postDelayed(send, Math.max(0, 40 - (android.os.SystemClock.uptimeMillis() - lastSent)));
    }

    private void installGestures() {
        var pinch = new android.view.ScaleGestureDetector(activity,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(android.view.ScaleGestureDetector detector) {
                        scale = Math.max(1f, Math.min(5f, scale * detector.getScaleFactor()));
                        if (scale == 1f) x = y = 0;
                        changed();
                        return true;
                    }
                });
        pinch.setQuickScaleEnabled(false);
        var gestures = new android.view.GestureDetector(activity, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                int visible = header.isShown() ? View.INVISIBLE : View.VISIBLE;
                header.setVisibility(visible);
                filmstrip.setVisibility(visible);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                scale = scale > 1 ? 1 : 2.5f;
                x = scale == 1 ? 0 : clamp(1 - 2 * e.getX() / image.getWidth());
                y = scale == 1 ? 0 : clamp(1 - 2 * e.getY() / image.getHeight());
                changed();
                return true;
            }
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (!multiTouch && scale > 1) {
                    x = clamp(x - 2 * dx / (image.getWidth() * scale));
                    y = clamp(y - 2 * dy / (image.getHeight() * scale));
                    changed();
                }
                return true;
            }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || multiTouch || scale != 1 || Math.abs(b.getX() - a.getX()) < dp(40)
                        || Math.abs(vx) <= Math.abs(vy)) return false;
                int next = index + (vx < 0 ? 1 : -1);
                if (next >= 0 && next < photos.size()) select(next);
                return true;
            }
        });
        image.setOnTouchListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) multiTouch = false;
            if (e.getPointerCount() > 1) multiTouch = true;
            pinch.onTouchEvent(e);
            gestures.onTouchEvent(e);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(send);
                transmit("PHOTO_TRANSFORM");
            }
            return true;
        });
    }

    private void menu(View anchor) {
        var menu = new androidx.appcompat.widget.PopupMenu(activity, anchor);
        menu.getMenu().add(interval == 0 ? "Start Slideshow" : "Stop Slideshow").setOnMenuItemClickListener(i -> {
            if (interval != 0) {
                interval = 0;
                handler.removeCallbacks(slideshow);
                progress.setVisibility(View.GONE);
            } else new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                    .setTitle(org.proninyaroslav.libretorrent.R.string.cinema_slideshow_interval).setItems(new String[]{
                    activity.getString(org.proninyaroslav.libretorrent.R.string.cinema_slideshow_seconds, 3),
                    activity.getString(org.proninyaroslav.libretorrent.R.string.cinema_slideshow_seconds, 5),
                    activity.getString(org.proninyaroslav.libretorrent.R.string.cinema_slideshow_seconds, 10)}, (d, n) -> {
                        interval = new int[]{3000, 5000, 10000}[n];
                        slideStart = android.os.SystemClock.uptimeMillis();
                        progress.setMax(interval);
                        progress.setVisibility(View.VISIBLE);
                        handler.post(slideshow);
                    }).show();
            return true;
        });
        menu.getMenu().add("Reset Zoom 100%").setOnMenuItemClickListener(i -> {
            scale = 1;
            x = y = 0;
            changed();
            return true;
        });
        menu.getMenu().add(fill ? "Fit on TV" : "Fill on TV").setOnMenuItemClickListener(i -> {
            fill = !fill;
            transmit("PHOTO_TRANSFORM");
            return true;
        });
        menu.getMenu().add(casting ? "Stop Casting" : "Cast to TV").setOnMenuItemClickListener(i -> {
            if (casting) {
                transmit("STOP_PHOTO");
                casting = false;
                activity.clearCastNotification();
            } else {
                casting = activity.castPhoto(photos.get(index));
                transmit("PHOTO_TRANSFORM");
            }
            return true;
        });
        menu.getMenu().add("Details").setOnMenuItemClickListener(i -> {
            var uri = photos.get(index);
            String details = uri.toString();
            try (var cursor = activity.getContentResolver().query(uri,
                    new String[]{"_display_name", "_size", "width", "height", "relative_path"}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    details = cursor.getString(0) + "\n" + cursor.getInt(2) + " × " + cursor.getInt(3)
                            + "\n" + android.text.format.Formatter.formatFileSize(activity, cursor.getLong(1))
                            + "\n" + cursor.getString(4);
                }
            } catch (RuntimeException ignored) {}
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity).setTitle(org.proninyaroslav.libretorrent.R.string.cinema_photo_details)
                    .setMessage(details).setPositiveButton(org.proninyaroslav.libretorrent.R.string.cinema_remote_done, null).show();
            return true;
        });
        menu.show();
    }

    private TextView action(String glyph, String name, Runnable click) {
        var view = new TextView(activity);
        view.setText(glyph);
        view.setTextSize(25);
        view.setTextColor(0xffe5b84b);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(name);
        var surface = new android.graphics.drawable.GradientDrawable();
        surface.setColor(0xe61c1c1e);
        surface.setCornerRadius(dp(24));
        view.setBackground(surface);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        view.setOnClickListener(v -> click.run());
        return view;
    }

    private static float clamp(float value) { return Math.max(-1, Math.min(1, value)); }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}

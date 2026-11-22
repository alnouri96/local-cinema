package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.graphics.*;
import androidx.media3.exoplayer.ExoPlayer;
import android.media.audiofx.Visualizer;
import android.view.View;

final class AudioReactiveCanvasView extends View {
    private static final int BARS = 96;
    private static final int GOLD = 0xffe5b84b;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Path emblem = new Path();
    private final Path wave = new Path();
    private final float[] levels = new float[BARS];
    private final float[] cos = new float[BARS], sin = new float[BARS];
    private final float[] wx = new float[BARS], wy = new float[BARS];
    private final float[] ripples = {-10, -10, -10, -10};
    private final Shader metal = new LinearGradient(-75, -85, 80, 85,
            new int[]{0xfff7d774, GOLD, 0xffb88924}, null, Shader.TileMode.CLAMP);
    private final Shader aura = new RadialGradient(0, 0, 1,
            new int[]{0x38e5b84b, 0x1ae5b84b, 0x00e5b84b},
            new float[]{0, .42f, 1}, Shader.TileMode.CLAMP);
    private final Shader spectrumGold = new RadialGradient(0, 0, 180,
            new int[]{GOLD, GOLD, 0xfffff0b3}, new float[]{0, .67f, 1}, Shader.TileMode.CLAMP);
    private volatile byte[] spectrum;
    private Visualizer capture;
    private ExoPlayer player;
    private float energy, phase, lastBeat = -1;
    private long lastFrame;
    private int rippleIndex;

    AudioReactiveCanvasView(Context context) {
        super(context);
        for (int i = 0; i < BARS; i++) {
            double angle = i * 2 * Math.PI / BARS;
            cos[i] = (float) Math.cos(angle);
            sin[i] = (float) Math.sin(angle);
        }
        emblem.moveTo(74, 1);
        emblem.lineTo(-6, -49);
        emblem.cubicTo(23, -22, 25, 11, 4, 42);
        emblem.close();
    }

    void attach(ExoPlayer player) {
        releaseCapture();
        this.player = player;
        try {
            capture = new Visualizer(player.getAudioSessionId());
            capture.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            capture.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                public void onWaveFormDataCapture(Visualizer source, byte[] data, int rate) {}
                public void onFftDataCapture(Visualizer source, byte[] data, int rate) {
                    spectrum = data.clone();
                }
            }, Visualizer.getMaxCaptureRate(), false, true);
            if (capture.setEnabled(true) != Visualizer.SUCCESS) releaseCapture();
        } catch (RuntimeException unavailable) {
            releaseCapture();
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xff08080a);
        long now = android.os.SystemClock.uptimeMillis();
        float elapsed = Math.min(.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float density = getResources().getDisplayMetrics().density;
        float unit = Math.min(density * 1.1f, getHeight() / 420f);
        boolean playing = false;
        try { playing = player != null && player.isPlaying(); } catch (IllegalStateException ignored) {}
        if (playing) phase += elapsed;
        byte[] fft = spectrum;
        float beat = 0;
        if (playing) {
            beat = fft == null ? (float) Math.pow(.5 + .5 * Math.cos(phase * 2 * Math.PI / .62), 6)
                    : (band(fft, 1) + band(fft, 2) + band(fft, 3)) / 3;
            if (beat > .52f && phase - lastBeat > .27f) {
                ripples[rippleIndex] = phase;
                rippleIndex = (rippleIndex + 1) % ripples.length;
                lastBeat = phase;
            }
        }
        energy += (beat - energy) * (1f - (float)Math.exp(-elapsed * (beat > energy ? 16 : 6)));

        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() * .45f);
        canvas.scale(unit, unit);
        drawAtmosphere(canvas, getWidth() / unit, getHeight() / unit);
        drawSpectrum(canvas, fft, playing, elapsed);
        drawLogo(canvas);
        canvas.restore();
        if (isShown() && player != null) postInvalidateOnAnimation();
    }

    private static float band(byte[] fft, int bin) {
        int i = bin * 2;
        if (i + 1 >= fft.length) return 0;
        return Math.min(1, (float)Math.hypot(fft[i], fft[i + 1]) / 68f);
    }

    private void drawAtmosphere(Canvas canvas, float width, float height) {
        paint.setStyle(Paint.Style.FILL);
        canvas.save();
        canvas.scale(width * (.46f + energy * .07f), height * (.72f + energy * .08f));
        paint.setShader(aura);
        paint.setAlpha((int)(170 + energy * 85));
        canvas.drawCircle(0, 0, 1, paint);
        canvas.restore();
        paint.setShader(null);
        paint.setColor(GOLD);
        for (int i = 0; i < 54; i++) {
            float x = ((i * .618034f + phase * (.008f + i % 4 * .003f)) % 1 - .5f) * width;
            float y = ((i * .414214f + phase * .009f) % 1 - .45f) * height;
            paint.setAlpha((int)(24 + i % 6 * 9 + energy * 40));
            canvas.drawCircle(x * (1 + .045f * energy), y * (1 + .045f * energy), .65f + i % 5 * .43f, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        for (float start : ripples) {
            float age = (phase - start) / 2.3f;
            if (age >= 1f) continue;
            paint.setAlpha((int)(166 * (1 - age) * (1 - age)));
            canvas.drawCircle(0, 0, 104 + age * width * .53f, paint);
        }
    }

    private void drawSpectrum(Canvas canvas, byte[] fft, boolean playing, float elapsed) {
        float base = 116 + 16 * energy;
        float blend = 1f - (float)Math.exp(-elapsed * 12);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(1.65f);
        paint.setColor(GOLD);
        paint.setShader(spectrumGold);
        for (int i = 0; i < BARS; i++) {
            int folded = Math.min(i, BARS - 1 - i);
            float target = !playing ? 0 : fft == null
                    ? energy * (.48f + .45f * (float)Math.sin(i * .23 + phase * 5))
                    : band(fft, 1 + folded * folded / 19);
            levels[i] += (target - levels[i]) * blend;
            float length = 4 + levels[i] * 43;
            paint.setAlpha(226);
            canvas.drawLine(cos[i] * base, sin[i] * base, cos[i] * (base + length), sin[i] * (base + length), paint);
            paint.setAlpha(94);
            float inner = base - 4 - length * .22f;
            canvas.drawLine(cos[i] * (base - 4), sin[i] * (base - 4), cos[i] * inner, sin[i] * inner, paint);
            float crest = base + 55 + levels[i] * 21 + 5 * (float)Math.sin(i * Math.PI / 12 + phase * 3) * energy;
            wx[i] = cos[i] * crest;
            wy[i] = sin[i] * crest;
        }
        paint.setShader(null);
        wave.rewind();
        wave.moveTo(wx[0], wy[0]);
        for (int i = 0; i < BARS; i++) {
            int p = (i + BARS - 1) % BARS, n = (i + 1) % BARS, nn = (i + 2) % BARS;
            wave.cubicTo(wx[i] + (wx[n] - wx[p]) / 6, wy[i] + (wy[n] - wy[p]) / 6,
                    wx[n] - (wx[nn] - wx[i]) / 6, wy[n] - (wy[nn] - wy[i]) / 6, wx[n], wy[n]);
        }
        wave.close();
        paint.setStrokeWidth(7);
        paint.setAlpha(22);
        canvas.drawPath(wave, paint);
        paint.setStrokeWidth(1.6f);
        paint.setAlpha(156);
        canvas.drawPath(wave, paint);
    }

    private void drawLogo(Canvas canvas) {
        canvas.save();
        canvas.scale(1 + .16f * energy, 1 + .16f * energy);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff0c0c0e);
        canvas.drawCircle(0, 0, 100, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xffe5b84b);
        paint.setAlpha((int)(22 + energy * 56));
        paint.setStrokeWidth(9);
        canvas.drawCircle(0, 0, 101, paint);
        paint.setColor(GOLD);
        paint.setShader(metal);
        paint.setStrokeWidth(3.2f);
        canvas.drawCircle(0, 0, 100, paint);
        paint.setStrokeWidth(4.8f);
        for (int i = 0; i < 4; i++) {
            float step = i * 9f;
            wave.rewind();
            wave.moveTo(-54, -55 + step);
            wave.cubicTo(-26, -55 + step, -7 - step * .45f, -39 + step * .65f, 13 - step * .82f, -19 + step * .58f);
            wave.moveTo(-48 + step, 58 - step * .42f);
            wave.cubicTo(-46 + step, 17 - step * .34f, -23 + step * .56f, -3 + step * .31f, 24, -17 + step * .35f);
            canvas.drawPath(wave, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(emblem, paint);
        paint.setShader(null);
        canvas.restore();
    }

    private void releaseCapture() {
        if (capture != null) {
            capture.release();
            capture = null;
        }
    }

    @Override protected void onDetachedFromWindow() {
        releaseCapture();
        player = null;
        super.onDetachedFromWindow();
    }
}

// Copyright 2017 Archos SA
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.archos.mediacenter.video.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.Surface;

import androidx.appcompat.widget.AppCompatTextView;

import com.meedx.providers.UnifiedSubtitleConfig;

/**
 * Shared subtitle TextView that draws:
 * 1) A tight word/line-bounded rounded dark background box ONLY around non-empty text (never full-screen).
 * 2) A smooth ROUND-join text outline (Levels 0..3) that preserves connected Arabic ligatures without gaps or spikes.
 * 3) Clean cinematic drop shadow when outline is off.
 */
public class SubtitleTextView extends AppCompatTextView {

    private Surface mExternalSurface = null;

    private int mOutlineLevel = 0; // 0=none, 1=thin, 2=medium, 3=thick
    private int mOutlineColor = Color.BLACK;
    private boolean mBgEnabled = false;
    private int mBgOpacity = 70;   // 0..100 in 10% steps
    private boolean mAdaptive = false;
    private int mAdaptiveText = Color.WHITE;
    private CharSequence mOriginalStyled;

    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBgRect = new RectF();
    private final Rect r = new Rect();
    private final int[] location = new int[2];

    public SubtitleTextView(Context context) {
        this(context, null);
    }

    public SubtitleTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(Color.TRANSPARENT);
        applyPaddingAndShadow();
    }

    private float dpF(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private int dpI(float v) {
        return Math.round(dpF(v));
    }

    private CharSequence displayText(CharSequence source) {
        if (!mAdaptive || !(source instanceof android.text.Spanned)) return source;
        android.text.SpannableString text = new android.text.SpannableString(source);
        for (android.text.style.ForegroundColorSpan span : text.getSpans(0, text.length(), android.text.style.ForegroundColorSpan.class)) {
            text.removeSpan(span);
        }
        return text;
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        mOriginalStyled = text;
        super.setText(displayText(text), type);
    }

    public void setAdaptiveColor(boolean enabled, int color) {
        if (mAdaptive == enabled && (!enabled || mAdaptiveText == color)) return;
        mAdaptive = enabled;
        mAdaptiveText = color;
        if (mOriginalStyled != null) super.setText(displayText(mOriginalStyled), BufferType.SPANNABLE);
        if (enabled) super.setTextColor(color);
        applyPaddingAndShadow();
        invalidate();
    }

    public void setOutlineState(boolean outline) {
        setOutlineLevel(outline ? (mOutlineLevel > 0 ? mOutlineLevel : 2) : 0);
    }

    public void setOutlineLevel(int level) {
        int clamped = Math.max(0, Math.min(3, level));
        if (mOutlineLevel == clamped) return;
        mOutlineLevel = clamped;
        applyPaddingAndShadow();
        invalidate();
    }

    public int getOutlineLevel() {
        return mOutlineLevel;
    }

    public void setOutlineColor(int color) {
        int norm = UnifiedSubtitleConfig.normalizeOutlineColor(color);
        if (mOutlineColor == norm) return;
        mOutlineColor = norm;
        invalidate();
    }

    public int getOutlineColor() {
        return mOutlineColor;
    }

    public void setOutline(int level, int color) {
        int clamped = Math.max(0, Math.min(3, level));
        int normColor = UnifiedSubtitleConfig.normalizeOutlineColor(color);
        if (mOutlineLevel == clamped && mOutlineColor == normColor) return;
        mOutlineLevel = clamped;
        mOutlineColor = normColor;
        applyPaddingAndShadow();
        invalidate();
    }

    public void setSubtitleBackground(boolean enabled, int opacityPercent) {
        int norm = UnifiedSubtitleConfig.normalizeBgOpacity(opacityPercent);
        if (mBgEnabled == enabled && mBgOpacity == norm) return;
        mBgEnabled = enabled;
        mBgOpacity = norm;
        setBackgroundColor(Color.TRANSPARENT);
        applyPaddingAndShadow();
        invalidate();
    }

    public boolean isSubtitleBackgroundEnabled() {
        return mBgEnabled;
    }

    public int getSubtitleBackgroundOpacity() {
        return mBgOpacity;
    }

    private void applyPaddingAndShadow() {
        int padH = dpI((mBgEnabled && mBgOpacity > 0) ? 14f : 8f);
        int padV = dpI((mBgEnabled && mBgOpacity > 0) ? 6f : 4f);
        if (getPaddingLeft() != padH || getPaddingTop() != padV
                || getPaddingRight() != padH || getPaddingBottom() != padV) {
            setPadding(padH, padV, padH, padV);
        }
        int effOutline = mAdaptive ? Math.max(2, mOutlineLevel) : mOutlineLevel;
        if (effOutline > 0) {
            setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        } else if (mBgEnabled && mBgOpacity > 0) {
            setShadowLayer(dpF(1.5f), 0f, dpF(1f), 0xaa000000);
        } else {
            setShadowLayer(dpF(3.5f), 0f, dpF(1.5f), 0xf0000000);
        }
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (mExternalSurface != null) {
            try {
                Canvas c = mExternalSurface.lockCanvas(null);
                c.save();
                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                c.restore();
                mExternalSurface.unlockCanvasAndPost(c);
            } catch (Exception ignored) {
            }
        }
    }

    public void setRenderingSurface(Surface s) {
        mExternalSurface = s;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Canvas c = canvas;
        if (mExternalSurface != null) {
            try {
                canvas.getClipBounds(r);
                getLocationOnScreen(location);
                r.offsetTo(location[0], location[1]);
                c = mExternalSurface.lockCanvas(null);
                c.save();
                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                c.clipRect(r);
                c.translate(location[0], location[1]);
            } catch (Exception ignored) {
            }
        }

        CharSequence txt = getText();
        boolean hasVisibleText = txt != null && txt.length() > 0 && !txt.toString().trim().isEmpty();
        Layout layout = getLayout();

        if (hasVisibleText && layout != null && layout.getLineCount() > 0) {
            int padLeft = getCompoundPaddingLeft();
            int padTop = getCompoundPaddingTop();
            int vSpace = getHeight() - padTop - getCompoundPaddingBottom();
            int vOffset = 0;
            int vGrav = getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
            if (vGrav == Gravity.BOTTOM) {
                vOffset = Math.max(0, vSpace - layout.getHeight());
            } else if (vGrav == Gravity.CENTER_VERTICAL) {
                vOffset = Math.max(0, (vSpace - layout.getHeight()) / 2);
            }

            // 1. Word-bounded rounded dark background box (tightly wraps actual measured lines only)
            if (mBgEnabled && mBgOpacity > 0) {
                float minLeft = Float.MAX_VALUE;
                float maxRight = 0f;
                int firstLine = -1;
                int lastLine = -1;
                for (int i = 0; i < layout.getLineCount(); i++) {
                    int start = layout.getLineStart(i);
                    int end = layout.getLineEnd(i);
                    if (end <= start) continue;
                    String seg = txt.subSequence(start, end).toString().trim();
                    if (seg.isEmpty()) continue;
                    float l = layout.getLineLeft(i);
                    float ri = layout.getLineRight(i);
                    if (ri <= l) continue;
                    if (firstLine < 0) firstLine = i;
                    lastLine = i;
                    if (l < minLeft) minLeft = l;
                    if (ri > maxRight) maxRight = ri;
                }
                if (firstLine >= 0 && maxRight > minLeft) {
                    float boxPadH = dpF(11f);
                    float boxPadV = dpF(4.5f);
                    float left = Math.max(0f, padLeft + minLeft - boxPadH);
                    float right = Math.min(getWidth(), padLeft + maxRight + boxPadH);
                    float top = Math.max(0f, padTop + vOffset + layout.getLineTop(firstLine) - boxPadV);
                    float bottom = Math.min(getHeight(), padTop + vOffset + layout.getLineBottom(lastLine) + boxPadV);
                    float radius = dpF(8f);
                    mBgPaint.setStyle(Paint.Style.FILL);
                    mBgPaint.setColor(UnifiedSubtitleConfig.bgColorWithOpacity(true, mBgOpacity));
                    mBgRect.set(left, top, right, bottom);
                    c.drawRoundRect(mBgRect, radius, radius, mBgPaint);
                }
            }

            // 2. Smooth ROUND-join outline drawn directly from HarfBuzz-shaped Layout (Arabic-safe, zero ligature breaks)
            int effOutline = mAdaptive ? Math.max(2, mOutlineLevel) : mOutlineLevel;
            if (effOutline > 0) {
                TextPaint paint = getPaint();
                Paint.Style oldStyle = paint.getStyle();
                float oldStrokeW = paint.getStrokeWidth();
                Paint.Join oldJoin = paint.getStrokeJoin();
                Paint.Cap oldCap = paint.getStrokeCap();
                int oldColor = paint.getColor();

                int strokeColor = mAdaptive
                        ? (androidx.core.graphics.ColorUtils.calculateLuminance(mAdaptiveText) < 0.5 ? Color.WHITE : Color.BLACK)
                        : mOutlineColor;
                float strokePx = dpF(UnifiedSubtitleConfig.outlineStrokeDp(effOutline));

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(strokePx);
                paint.setColor(strokeColor);
                paint.clearShadowLayer();

                c.save();
                c.translate(padLeft, padTop + vOffset);
                layout.draw(c);
                c.restore();

                paint.setStyle(oldStyle);
                paint.setStrokeWidth(oldStrokeW);
                paint.setStrokeJoin(oldJoin);
                paint.setStrokeCap(oldCap);
                paint.setColor(oldColor);
            }
        }

        // 3. Solid crisp foreground fill pass
        super.onDraw(c);

        if (c != canvas) {
            c.restore();
            mExternalSurface.unlockCanvasAndPost(c);
        }
    }
}

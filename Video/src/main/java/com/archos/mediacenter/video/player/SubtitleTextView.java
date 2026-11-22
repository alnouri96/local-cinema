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
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.Surface;

import androidx.appcompat.widget.AppCompatTextView;

public class SubtitleTextView extends AppCompatTextView {

    private static final String TAG = "SubtitleTextView";
   
    private Surface mExternalSurface = null;

    private static boolean mOutline = false;
    private boolean mAdaptive;private int mAdaptiveText=Color.WHITE;private CharSequence mOriginalStyled;
    private CharSequence displayText(CharSequence source){if(!mAdaptive||!(source instanceof android.text.Spanned))return source;android.text.SpannableString text=new android.text.SpannableString(source);for(android.text.style.ForegroundColorSpan span:text.getSpans(0,text.length(),android.text.style.ForegroundColorSpan.class))text.removeSpan(span);return text;}
    @Override public void setText(CharSequence text,android.widget.TextView.BufferType type){mOriginalStyled=text;super.setText(displayText(text),type);}
    public void setAdaptiveColor(boolean enabled,int color){if(mAdaptive==enabled&&(!enabled||mAdaptiveText==color))return;mAdaptive=enabled;mAdaptiveText=color;if(mOriginalStyled!=null)super.setText(displayText(mOriginalStyled),android.widget.TextView.BufferType.SPANNABLE);if(enabled)super.setTextColor(color);invalidate();}

   
    public SubtitleTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setOutlineState(boolean outline) { mOutline = outline; }
    
    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (mExternalSurface != null)  {
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

    private Rect r = new Rect();
    private int[] location = new int[2];

    @Override
    protected void onDraw(Canvas canvas) {
        Canvas c = canvas;
        if (mExternalSurface != null) {
            try {
                canvas.getClipBounds(r);
                getLocationOnScreen(location);
                r.offsetTo(location[0],location[1]);
                c = mExternalSurface.lockCanvas(null);
                c.save();
                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                c.clipRect(r);
                c.translate(location[0],location[1]);
            } catch (Exception ignored) {
            }
        }
        // disable for now nice outline since it is too slow on lowend CPU/GPU (e.g. RK3128)
        if (mOutline || mAdaptive) {
            TextPaint paint = getPaint();
            int color = getCurrentTextColor();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.MITER);
            paint.setStrokeMiter(1.0f);
            paint.setStrokeWidth(4.0f);
            setTextColor(mAdaptive&&androidx.core.graphics.ColorUtils.calculateLuminance(mAdaptiveText)<0.5?Color.WHITE:Color.BLACK);
            super.onDraw(c);
            paint.setStyle(Paint.Style.FILL);
            setTextColor(color);
        }
        super.onDraw(c);
        if (c != canvas) {
            c.restore();
            mExternalSurface.unlockCanvasAndPost(c);
        }
    }
}

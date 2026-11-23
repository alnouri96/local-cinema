package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.TextView;

/** Shared remote-friendly visual primitives. No media or network work happens here. */
public final class CinemaUi {
    private CinemaUi() {}
    public static final String DUAL_SUBS_ENABLED = "pref_dual_subs_enabled";

    public static android.widget.ArrayAdapter<String> subtitleRows(Context context, String[] items) {
        return new android.widget.ArrayAdapter<>(context, android.R.layout.simple_list_item_1, items) {
            @Override public android.view.View getView(int position, android.view.View convert,
                    android.view.ViewGroup parent) {
                var row = (android.widget.TextView) super.getView(position, convert, parent);
                row.setSingleLine(true);
                row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                return row;
            }
        };
    }

    public static void widenSubtitleDialog(android.view.Window window) {
        if (window != null) {
            int width = Math.round(window.getContext().getResources().getDisplayMetrics().widthPixels * 0.86f);
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    static void addFixedSlot(android.widget.LinearLayout parent, android.view.View child, int width, int height) {
        var slot = new android.widget.FrameLayout(parent.getContext());
        parent.addView(slot, new android.widget.LinearLayout.LayoutParams(width, height));
        slot.addView(child, new android.widget.FrameLayout.LayoutParams(-1, -1));
    }

    public static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }
    public static boolean arabic(Context c) {
        return c.getResources().getConfiguration().locale.getLanguage().equals("ar");
    }
    public static String label(Context c, String ar, String en) { return arabic(c) ? ar : en; }
    public static GradientDrawable rounded(int color, int radius, int border) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(radius);
        if (border != 0) d.setStroke(2, border);
        return d;
    }
    public static GradientDrawable posterFocusRing(Context context,float strokeDp) {
        GradientDrawable ring=rounded(Color.TRANSPARENT,dp(context,CinemaTokens.POSTER_RADIUS),0);
        ring.setStroke(dp(context,strokeDp),0xffe5a93b);
        return ring;
    }
    public static void setPosterFocus(View view,boolean focused) {
        view.setForeground(focused?posterFocusRing(view.getContext(),3f):null);
        animatePosterFocus(view,focused);
    }
    public static void animatePosterFocus(View view,boolean focused) {
        float scale=focused?1.06f:1f;
        view.animate().scaleX(scale).scaleY(scale)
                .setDuration(motion(view.getContext())?CinemaTokens.FOCUS_MS:0).start();
    }
    static TextView episodeCountBadge(Context context) {
        TextView badge=text(context,CinemaTokens.CAPTION,true);
        badge.setGravity(android.view.Gravity.CENTER);
        badge.setMinWidth(dp(context,20));
        badge.setPadding(dp(context,4),0,dp(context,4),0);
        GradientDrawable background=rounded(0xe6000000,0,0);
        int radius=dp(context,CinemaTokens.POSTER_RADIUS);
        background.setCornerRadii(new float[]{0,0,0,0,0,0,radius,radius});
        badge.setBackground(background);
        return badge;
    }

    public static TextView text(Context c, float size, boolean bold) {
        TextView t = new TextView(c);
        t.setTextColor(CinemaTokens.TEXT); t.setTextSize(size);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setFontFeatureSettings("tnum");
        t.setIncludeFontPadding(false);
        t.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTransformationMethod(new android.text.method.TransformationMethod(){
            public CharSequence getTransformation(CharSequence source,View view){return digits(source);}
            public void onFocusChanged(View view,CharSequence source,boolean focused,int direction,android.graphics.Rect previous){}
        });
        return t;
    }
    public static String digits(CharSequence source){if(source==null)return "";StringBuilder out=new StringBuilder(source);for(int n=0;n<out.length();n++){char ch=out.charAt(n);if(ch>='٠'&&ch<='٩')out.setCharAt(n,(char)('0'+ch-'٠'));else if(ch>='۰'&&ch<='۹')out.setCharAt(n,(char)('0'+ch-'۰'));}return out.toString();}
    public static void navigationLabel(TextView v,String name,boolean expanded){
        v.setText(expanded?name:"");v.setGravity(android.view.Gravity.CENTER_VERTICAL|(expanded?android.view.Gravity.START:android.view.Gravity.CENTER_HORIZONTAL));
        v.setPadding(dp(v.getContext(),expanded?10:6),0,dp(v.getContext(),expanded?8:6),0);v.setCompoundDrawablePadding(dp(v.getContext(),expanded?12:0));
    }
    public static void iconOnly(View v){
        focusable(v);StateListDrawable bg=new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},rounded(CinemaTokens.PRESSED,dp(v.getContext(),6),0));
        bg.addState(new int[]{android.R.attr.state_focused},rounded(CinemaTokens.FOCUS,dp(v.getContext(),6),0));
        bg.addState(new int[]{},rounded(Color.TRANSPARENT,0,0));v.setBackground(bg);
    }
    public static void focusable(View v) {
        v.setFocusable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{-android.R.attr.state_enabled}, rounded(0x66292929, dp(v.getContext(), CinemaTokens.CONTROL_RADIUS), 0));
        bg.addState(new int[]{android.R.attr.state_pressed}, rounded(CinemaTokens.PRESSED, dp(v.getContext(), CinemaTokens.CONTROL_RADIUS), 0));
        bg.addState(new int[]{android.R.attr.state_focused}, rounded(CinemaTokens.FOCUS, dp(v.getContext(), CinemaTokens.CONTROL_RADIUS), 0));
        bg.addState(new int[]{}, rounded(CinemaTokens.SURFACE, dp(v.getContext(), CinemaTokens.CONTROL_RADIUS), 0));
        v.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x44d4af37),bg,null));
        v.setMinimumHeight(dp(v.getContext(),CinemaTokens.CONTROL_HEIGHT));
        v.setClickable(true);
        v.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){
                super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.Button");
            }
        });
        android.animation.StateListAnimator states=new android.animation.StateListAnimator();
        states.addState(new int[]{android.R.attr.state_pressed},scale(v,0.97f,CinemaTokens.PRESS_MS));
        states.addState(new int[]{},scale(v,1f,CinemaTokens.PANEL_MS));v.setStateListAnimator(states);
        if (v instanceof TextView) {
            android.content.res.ColorStateList colors = new android.content.res.ColorStateList(
                    new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{android.R.attr.state_pressed},new int[]{android.R.attr.state_focused}, new int[]{}}, new int[]{0xff858585,CinemaTokens.ON_FOCUS,CinemaTokens.ON_FOCUS,CinemaTokens.TEXT});
            ((TextView)v).setTextColor(colors);
            ((TextView)v).setCompoundDrawableTintList(colors);
        }
    }
    private static android.animation.Animator scale(View view,float value,int duration){
        android.animation.AnimatorSet set=new android.animation.AnimatorSet();
        set.playTogether(android.animation.ObjectAnimator.ofFloat(view,"scaleX",value),android.animation.ObjectAnimator.ofFloat(view,"scaleY",value));
        set.setDuration(motion(view.getContext())?duration:0);set.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));return set;
    }
    public static boolean motion(Context c){
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(c).getBoolean(HomePreferences.MOTION,true)
                && android.provider.Settings.Global.getFloat(c.getContentResolver(),android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0;
    }
    public static void icon(TextView view,int resource){
        android.graphics.drawable.Drawable d=androidx.core.content.ContextCompat.getDrawable(view.getContext(),resource).mutate();
        int size=dp(view.getContext(),CinemaTokens.ICON);d.setBounds(0,0,size,size);
        view.setCompoundDrawablesRelative(d,null,null,null);view.setCompoundDrawablePadding(dp(view.getContext(),12));
        view.setCompoundDrawableTintList(view.getTextColors());
    }
    public static android.graphics.drawable.Drawable surface(){
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff292929,CinemaTokens.CANVAS});
    }
    public static androidx.appcompat.app.AlertDialog.Builder dialog(Context c){
        return new androidx.appcompat.app.AlertDialog.Builder(c,com.archos.mediacenter.video.R.style.CinemaDialogTheme){
            @Override public androidx.appcompat.app.AlertDialog create(){
                androidx.appcompat.app.AlertDialog dialog=super.create();
                dialog.setOnShowListener(ignored->{
                    for (int which : new int[]{-1, -2, -3}) {
                        var button = dialog.getButton(which);
                        if (button == null || button.getVisibility() != View.VISIBLE) continue;
                        button.setAllCaps(false);
                        button.setTextColor(0xffe5a93b);
                        var states = new StateListDrawable();
                        states.addState(new int[]{android.R.attr.state_focused}, rounded(0xff392b18, dp(c, 18), 0xffe5a93b));
                        states.addState(new int[]{}, rounded(0xff24201a, dp(c, 18), 0xff54432c));
                        button.setBackground(states);
                    }
                    android.widget.ListView list=dialog.getListView();
                    if(list!=null){
                        list.setSelector(rounded(CinemaTokens.FOCUS,dp(c,12),0));
                        list.setDrawSelectorOnTop(false);
                        Runnable styleRows=()->{
                            int position=Math.max(0,list.getSelectedItemPosition());
                            for(int n=0;n<list.getChildCount();n++){
                                View child=list.getChildAt(n);TextView text=child instanceof TextView?(TextView)child:child.findViewById(android.R.id.text1);
                                if (text instanceof android.widget.CheckedTextView checked) {
                                    checked.setCheckMarkTintList(android.content.res.ColorStateList.valueOf(
                                            n+list.getFirstVisiblePosition()==position ? 0xff53350f : 0xffe5a93b));
                                }
                                if(text!=null)text.setTextColor(n+list.getFirstVisiblePosition()==position?CinemaTokens.ON_FOCUS:CinemaTokens.TEXT);
                            }
                        };
                        list.getViewTreeObserver().addOnGlobalLayoutListener(styleRows::run);
                        list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
                            @Override public void onItemSelected(android.widget.AdapterView<?> parent,View selected,int position,long id){
                                styleRows.run();list.post(styleRows);
                            }
                            @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
                        });
                        list.requestFocusFromTouch();list.setSelection(0);
                    }
                });return dialog;
            }
        };
    }

    static boolean moveVerticalGridFocus(androidx.recyclerview.widget.RecyclerView grid, View current,
                                         android.view.KeyEvent event, View footer) {
        int key = event.getKeyCode();
        if (event.getAction() != android.view.KeyEvent.ACTION_DOWN
                || (key != android.view.KeyEvent.KEYCODE_DPAD_UP && key != android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                || !(grid.getLayoutManager() instanceof androidx.recyclerview.widget.GridLayoutManager layout)) return false;
        var holder = grid.findContainingViewHolder(current);
        if (holder == null || holder.getBindingAdapterPosition() < 0) return false;
        int position = holder.getBindingAdapterPosition();
        int span = layout.getSpanCount();
        boolean down = key == android.view.KeyEvent.KEYCODE_DPAD_DOWN;
        int target = position + (down ? span : -span);
        if (target < 0) return false;
        int count = layout.getItemCount();
        if (down && target >= count) {
            if (position / span == (count - 1) / span) {
                if (footer != null && footer.isShown() && footer.isFocusable()) footer.requestFocus();
                return true;
            }
            target = count - 1;
        }
        var targetView = layout.findViewByPosition(target);
        if (targetView != null && targetView.isShown() && targetView.requestFocus()) return true;
        grid.scrollToPosition(target);
        int next = target;
        grid.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                grid.getViewTreeObserver().removeOnPreDrawListener(this);
                View item = layout.findViewByPosition(next);
                if (item != null) item.requestFocus();
                return true;
            }
        });
        return true;
    }

    public static void focusDialogControl(androidx.appcompat.app.AlertDialog dialog, View control) {
        if (control == null || control.requestFocusFromTouch()) return;
        control.post(() -> {
            if (dialog.isShowing() && control.isShown()) control.requestFocusFromTouch();
        });
    }

    public static void showDialogWithButtonFocus(androidx.appcompat.app.AlertDialog.Builder builder, int button) {
        var dialog = builder.show();
        focusDialogControl(dialog, dialog.getButton(button));
    }

    public static void confirmStopPlayback(android.app.Activity player) {
        if (player.isFinishing() || player.isDestroyed()) return;
        showDialogWithButtonFocus(dialog(player)
                .setTitle(com.archos.mediacenter.video.R.string.cinema_confirm_stop_playback)
                .setNegativeButton(com.archos.mediacenter.video.R.string.addon_back, null)
                .setPositiveButton(com.archos.mediacenter.video.R.string.stop, (dialog, button) -> player.finish()),
                android.content.DialogInterface.BUTTON_POSITIVE);
    }

    public static android.graphics.drawable.Drawable createCinemaCheckDrawable(Context c) {
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_checked}, new CheckBoxDrawable(c, true));
        sld.addState(new int[]{}, new CheckBoxDrawable(c, false));
        return sld;
    }

    /** Direct non-stateful checkbox drawable safe for ImageView (never reset by ImageView drawable state). */
    public static android.graphics.drawable.Drawable createCinemaCheckDrawable(Context c, boolean checked) {
        return new CheckBoxDrawable(c, checked);
    }

    /** Direct non-stateful radio indicator drawable safe for ImageView. */
    public static android.graphics.drawable.Drawable createCinemaRadioDrawable(Context c, boolean selected) {
        return new RadioDotDrawable(c, selected);
    }

    private static final class CheckBoxDrawable extends android.graphics.drawable.Drawable {
        private final int sizePx;
        private final float radiusPx;
        private final float strokePx;
        private final boolean checked;
        private final android.graphics.Paint boxPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint borderPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint checkPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        CheckBoxDrawable(Context c, boolean checked) {
            this.checked = checked;
            this.sizePx = dp(c, 22);
            this.radiusPx = dp(c, 6);
            this.strokePx = dp(c, 2f);
            boxPaint.setStyle(android.graphics.Paint.Style.FILL);
            boxPaint.setColor(checked ? 0xFFE5A93B : 0xFF18181D);
            borderPaint.setStyle(android.graphics.Paint.Style.STROKE);
            borderPaint.setStrokeWidth(strokePx);
            borderPaint.setColor(checked ? 0xFFE5A93B : 0xFFA6ABB6);
            checkPaint.setStyle(android.graphics.Paint.Style.STROKE);
            checkPaint.setStrokeWidth(dp(c, 2.8f));
            checkPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            checkPaint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            checkPaint.setColor(0xFF141517);
        }

        @Override public int getIntrinsicWidth() { return sizePx; }
        @Override public int getIntrinsicHeight() { return sizePx; }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float inset = strokePx / 2f + 1f;
            android.graphics.RectF rect = new android.graphics.RectF(
                    b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
            canvas.drawRoundRect(rect, radiusPx, radiusPx, boxPaint);
            canvas.drawRoundRect(rect, radiusPx, radiusPx, borderPaint);
            if (checked) {
                float w = b.width();
                float h = b.height();
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(b.left + w * 0.24f, b.top + h * 0.52f);
                path.lineTo(b.left + w * 0.43f, b.top + h * 0.72f);
                path.lineTo(b.left + w * 0.77f, b.top + h * 0.28f);
                canvas.drawPath(path, checkPaint);
            }
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static final class RadioDotDrawable extends android.graphics.drawable.Drawable {
        private final int sizePx;
        private final float strokePx;
        private final boolean selected;
        private final android.graphics.Paint ringPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint dotPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        RadioDotDrawable(Context c, boolean selected) {
            this.selected = selected;
            this.sizePx = dp(c, 22);
            this.strokePx = dp(c, 2.2f);
            ringPaint.setStyle(android.graphics.Paint.Style.STROKE);
            ringPaint.setStrokeWidth(strokePx);
            ringPaint.setColor(selected ? 0xFFE5A93B : 0xFFA6ABB6);
            dotPaint.setStyle(android.graphics.Paint.Style.FILL);
            dotPaint.setColor(0xFFE5A93B);
        }

        @Override public int getIntrinsicWidth() { return sizePx; }
        @Override public int getIntrinsicHeight() { return sizePx; }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float radius = Math.min(b.width(), b.height()) / 2f - strokePx;
            canvas.drawCircle(cx, cy, Math.max(2f, radius), ringPaint);
            if (selected) {
                canvas.drawCircle(cx, cy, Math.max(2f, radius * 0.54f), dotPaint);
            }
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    public static void stylePreferenceScreen(View rootView, androidx.recyclerview.widget.RecyclerView rv) {
        if (rv == null) return;
        Context c = rv.getContext();
        rv.setBackgroundColor(0xFF121215);
        rv.setPadding(dp(c, 8), dp(c, 6), dp(c, 8), dp(c, 12));
        rv.setClipToPadding(false);
        if (rootView != null) {
            rootView.setBackgroundColor(0xFF121215);
            stylePreferenceHeaderViews(rootView);
        }
        Runnable refreshAll = () -> {
            for (int i = 0; i < rv.getChildCount(); i++) {
                stylePreferenceRow(rv.getChildAt(i));
            }
        };
        rv.addOnChildAttachStateChangeListener(new androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener() {
            @Override public void onChildViewAttachedToWindow(View view) {
                stylePreferenceRow(view);
                if (view.getTag(0x7f0b0001) == null) {
                    view.setTag(0x7f0b0001, Boolean.TRUE);
                    view.setOnFocusChangeListener((v, hasFocus) -> stylePreferenceRow(v));
                }
            }
            @Override public void onChildViewDetachedFromWindow(View view) {}
        });
        rv.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (oldFocus != null && oldFocus.getParent() == rv) stylePreferenceRow(oldFocus);
            if (newFocus != null && newFocus.getParent() == rv) stylePreferenceRow(newFocus);
        });
        if (rv.getAdapter() != null) {
            try {
                rv.getAdapter().registerAdapterDataObserver(new androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
                    @Override public void onChanged() { rv.post(refreshAll); }
                    @Override public void onItemRangeChanged(int positionStart, int itemCount) { rv.post(refreshAll); }
                    @Override public void onItemRangeChanged(int positionStart, int itemCount, Object payload) { rv.post(refreshAll); }
                    @Override public void onItemRangeInserted(int positionStart, int itemCount) { rv.post(refreshAll); }
                });
            } catch (Exception ignored) {}
        }
        rv.post(refreshAll);
    }

    private static void stylePreferenceHeaderViews(View v) {
        if (v == null) return;
        int id = v.getId();
        if (id != View.NO_ID) {
            try {
                String name = v.getResources().getResourceEntryName(id);
                if (name.contains("decor_title_container") || name.contains("lb_preference_decor_title")) {
                    v.setBackgroundColor(0xFF1E1E24);
                }
                if (name.contains("decor_title") && v instanceof TextView tv) {
                    tv.setTextColor(0xFFF4F4F6);
                }
            } catch (Exception ignored) {}
        }
        if (v instanceof android.view.ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                stylePreferenceHeaderViews(vg.getChildAt(i));
            }
        }
    }

    private static void stylePreferenceRow(View row) {
        if (row == null) return;
        Context c = row.getContext();
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        android.widget.CompoundButton toggle = findCompoundButton(row);
        if (toggle != null) {
            if (toggle instanceof android.widget.CheckBox && !(toggle.getButtonDrawable() instanceof CheckBoxDrawable)) {
                toggle.setButtonDrawable(createCinemaCheckDrawable(c));
                toggle.setButtonTintList(null);
            }
            if (toggle instanceof android.widget.Switch sw) {
                sw.setThumbTintList(android.content.res.ColorStateList.valueOf(sw.isChecked() ? 0xFFE5A93B : 0xFFA0A0AB));
                sw.setTrackTintList(android.content.res.ColorStateList.valueOf(sw.isChecked() ? 0x55E5A93B : 0x44686873));
            }
        }
        boolean focusable = row.isFocusable();
        boolean focused = row.hasFocus() || row.isSelected();
        boolean checked = toggle != null && toggle.isChecked();
        int stateKey = (focusable ? 1 : 0) | (focused ? 2 : 0) | (row.isEnabled() ? 4 : 0) | (checked ? 8 : 0);
        if (Integer.valueOf(stateKey).equals(row.getTag(0x7f0b0002))) {
            return;
        }
        row.setTag(0x7f0b0002, stateKey);
        if (!focusable) {
            row.setBackground(null);
            if (title != null) {
                title.setTextColor(0xFFE5B94C);
                title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            }
            if (summary != null) {
                summary.setTextColor(0xFFA0A0AB);
                summary.setAlpha(1f);
            }
            return;
        }
        row.setAlpha(1f);
        if (focused) {
            GradientDrawable fg = rounded(0xFFE5B94C, dp(c, 8), 0);
            row.setBackground(fg);
            if (title != null) {
                title.setTextColor(0xFF121215);
                title.setAlpha(1f);
            }
            if (summary != null) {
                summary.setTextColor(0xFF121215);
                summary.setAlpha(1f);
            }
        } else {
            row.setBackground(rounded(0xFF171717, dp(c, 8), 0));
            if (title != null) {
                title.setTextColor(0xFFF4F4F6);
                title.setAlpha(row.isEnabled() ? 1f : 0.78f);
            }
            if (summary != null) {
                summary.setTextColor(0xFFA0A0AB);
                summary.setAlpha(row.isEnabled() ? 1f : 0.78f);
            }
        }
    }

    private static android.widget.CompoundButton findCompoundButton(View v) {
        if (v instanceof android.widget.CompoundButton cb) return cb;
        if (v instanceof android.view.ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                android.widget.CompoundButton found = findCompoundButton(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    public static void styleNetworkCard(View cardView, View rootLayout, View imageContainer, TextView primary, TextView secondary) {
        if (cardView == null) return;
        Context c = cardView.getContext();
        int rad = dp(c, 8);
        cardView.setBackground(rounded(0xFF1E1E24, rad, 0));
        cardView.setClipToOutline(true);
        if (rootLayout != null) rootLayout.setBackgroundColor(0xFF1E1E24);
        if (imageContainer != null) imageContainer.setBackgroundColor(0xFF16161B);
        if (primary != null) primary.setTextColor(0xFFF4F4F6);
        if (secondary != null) secondary.setTextColor(0xFFA0A0AB);
        StateListDrawable fg = new StateListDrawable();
        GradientDrawable focusedRing = rounded(Color.TRANSPARENT, rad, 0);
        focusedRing.setStroke(dp(c, 2f), 0xFFE5B94C);
        GradientDrawable idleRing = rounded(Color.TRANSPARENT, rad, 0);
        idleRing.setStroke(dp(c, 1f), 0xFF2E2E36);
        fg.addState(new int[]{android.R.attr.state_focused}, focusedRing);
        fg.addState(new int[]{android.R.attr.state_selected}, focusedRing);
        fg.addState(new int[]{}, idleRing);
        cardView.setForeground(fg);
    }

    /** Shared luxury dark/gold TV settings card matching CinemaSettingsActivity styling. */
    public static android.widget.LinearLayout settingsCard(
            Context context,
            android.widget.LinearLayout container,
            int iconRes,
            String title,
            String subtitle,
            Boolean toggleState,
            Runnable onFocusHook,
            Runnable action) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(context);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(context, 18), dp(context, 13), dp(context, 18), dp(context, 13));

        android.widget.ImageView icon = new android.widget.ImageView(context);
        if (iconRes != 0) icon.setImageResource(iconRes);
        icon.setColorFilter(CinemaTokens.GOLD);
        android.widget.LinearLayout.LayoutParams iconLp =
                new android.widget.LinearLayout.LayoutParams(dp(context, 24), dp(context, 24));
        iconLp.setMarginEnd(dp(context, 14));
        row.addView(icon, iconLp);

        android.widget.LinearLayout textCol = new android.widget.LinearLayout(context);
        textCol.setOrientation(android.widget.LinearLayout.VERTICAL);
        TextView titleTv = text(context, CinemaTokens.BODY, true);
        titleTv.setText(title);
        titleTv.setMaxLines(2);
        titleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        textCol.addView(titleTv);

        TextView subTv = null;
        if (subtitle != null && !subtitle.isBlank()) {
            subTv = text(context, CinemaTokens.CAPTION, false);
            subTv.setText(subtitle);
            subTv.setMaxLines(2);
            subTv.setTextColor(0xffd4b55b);
            subTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            subTv.setPadding(0, dp(context, 3), 0, 0);
            textCol.addView(subTv);
        }
        row.addView(textCol, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));

        if (toggleState != null) {
            TextView badgeTv = text(context, CinemaTokens.CAPTION, true);
            boolean on = toggleState;
            badgeTv.setText(on ? label(context, "✓ مفعّل", "✓ Enabled") : label(context, "✕ متوقف", "✕ Off"));
            badgeTv.setTextColor(on ? 0xff9de0b8 : 0xfff29a94);
            badgeTv.setBackground(rounded(on ? 0xff1b3326 : 0xff331e1e, dp(context, 8), 0));
            badgeTv.setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4));
            android.widget.LinearLayout.LayoutParams bLp =
                    new android.widget.LinearLayout.LayoutParams(-2, -2);
            bLp.setMarginStart(dp(context, 12));
            row.addView(badgeTv, bLp);
        }

        final TextView finalSub = subTv;
        Runnable styleRest = () -> {
            row.setBackground(rounded(0xff1d1e21, dp(context, 10), 0xff34322c));
            titleTv.setTextColor(CinemaTokens.TEXT);
            if (finalSub != null) finalSub.setTextColor(0xffd4b55b);
            icon.setColorFilter(CinemaTokens.GOLD);
        };
        styleRest.run();

        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                row.setBackground(rounded(CinemaTokens.FOCUS, dp(context, 10), CinemaTokens.FOCUS));
                titleTv.setTextColor(0xff141517);
                if (finalSub != null) finalSub.setTextColor(0xff3a3114);
                icon.setColorFilter(0xff141517);
                if (onFocusHook != null) onFocusHook.run();
            } else {
                styleRest.run();
            }
        });
        row.setOnClickListener(v -> {
            if (action != null) action.run();
        });

        android.widget.LinearLayout.LayoutParams p = new android.widget.LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(context, 10);
        container.addView(row, p);
        return row;
    }
}

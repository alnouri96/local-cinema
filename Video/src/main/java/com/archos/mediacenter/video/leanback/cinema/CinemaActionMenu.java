package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

final class CinemaActionMenu {
    record Action(String label,Runnable command) {}

    private CinemaActionMenu() {}

    static void show(View anchor,Action... actions){
        Context context=anchor.getContext();
        boolean rtl=arabic(context);
        int inset=dp(context,8);
        android.widget.ScrollView scroll=new android.widget.ScrollView(context);
        scroll.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(inset,inset,inset,dp(context,20));
        LinearLayout menu=new LinearLayout(context);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        scroll.addView(menu,new android.widget.FrameLayout.LayoutParams(-1,-2));
        int screenH=context.getResources().getDisplayMetrics().heightPixels;
        int maxH=Math.round(screenH*0.75f);
        PopupWindow popup=new PopupWindow(scroll,dp(context,300),-2,true);
        popup.setHeight(Math.min(maxH, dp(context, 72) * Math.max(1, actions.length) + dp(context, 36)));
        popup.setBackgroundDrawable(rounded(CinemaTokens.DRAWER,inset,0xff3a3322));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(context,12));
        for(int idx=0;idx<actions.length;idx++){
            Action action=actions[idx];
            TextView row=text(context,CinemaTokens.LABEL,true);
            row.setText(action.label());
            row.setTextDirection(rtl?View.TEXT_DIRECTION_RTL:View.TEXT_DIRECTION_LTR);
            row.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL|(rtl?android.view.Gravity.RIGHT:android.view.Gravity.LEFT));
            row.setPadding(dp(context,16),dp(context,11),dp(context,16),dp(context,11));
            focusable(row);
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
            params.bottomMargin=idx==actions.length-1?dp(context,4):dp(context,6);
            menu.addView(row,params);
            row.setOnFocusChangeListener((v,hasFocus)->{
                if(hasFocus){
                    scroll.post(()->{
                        int top=v.getTop();
                        int bottom=v.getBottom()+dp(context,24);
                        int curY=scroll.getScrollY();
                        int visH=scroll.getHeight();
                        if(visH>0){
                            if(bottom>curY+visH)scroll.smoothScrollTo(0,bottom-visH);
                            else if(top<curY+inset)scroll.smoothScrollTo(0,Math.max(0,top-inset));
                        }
                    });
                }
            });
            row.setOnClickListener(v->{
                popup.dismiss();
                action.command().run();
            });
            row.setOnKeyListener((v,key,event)->{
                if(event.getAction()!=KeyEvent.ACTION_DOWN||
                        (key!=KeyEvent.KEYCODE_DPAD_LEFT&&key!=KeyEvent.KEYCODE_DPAD_RIGHT))return false;
                popup.dismiss();
                return true;
            });
        }
        popup.setOnDismissListener(anchor::requestFocus);
        int[] loc=new int[2];
        anchor.getLocationInWindow(loc);
        int popupH=popup.getHeight();
        int spaceBelow=screenH-(loc[1]+anchor.getHeight());
        int yOff=spaceBelow>=popupH+dp(context,16)?dp(context,6):Math.max(-(loc[1]-dp(context,24)), spaceBelow-popupH-dp(context,16));
        popup.showAsDropDown(anchor,0,yOff);
        menu.post(()->{
            if(menu.getChildCount()>0)menu.getChildAt(0).requestFocus();
        });
    }
}

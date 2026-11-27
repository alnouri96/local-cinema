package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.leanback.MainActivityLeanback;
import com.archos.mediacenter.video.leanback.search.VideoSearchActivity;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

/** Compact secondary-screen navigation. Home owns the actual library shortcuts. */
final class CinemaNavigation {
    static boolean moveVertically(LinearLayout rail,View item,int key,android.view.KeyEvent event){
        if(key!=android.view.KeyEvent.KEYCODE_DPAD_UP&&key!=android.view.KeyEvent.KEYCODE_DPAD_DOWN)return false;
        if(event.getAction()==android.view.KeyEvent.ACTION_DOWN){
            int step=key==android.view.KeyEvent.KEYCODE_DPAD_UP?-1:1;
            for(int index=rail.indexOfChild(item)+step;index>=0&&index<rail.getChildCount();index+=step){
                View candidate=rail.getChildAt(index);
                if(candidate.isShown()&&candidate.isEnabled()&&candidate.requestFocus())break;
            }
        }
        return true;
    }

    static View attach(Activity activity,FrameLayout root){
        boolean rtl=arabic(activity);
        View scrim=new View(activity);scrim.setBackgroundColor(CinemaTokens.RAIL_DETAILS_SCRIM);scrim.setVisibility(View.GONE);root.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout rail=new LinearLayout(activity);rail.setOrientation(LinearLayout.VERTICAL);rail.setPadding(dp(activity,4),dp(activity,8),dp(activity,4),dp(activity,8));
        rail.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        rail.setBackground(rounded(CinemaTokens.RAIL_BACKGROUND,dp(activity,CinemaTokens.CONTROL_RADIUS),0));
        FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(dp(activity,CinemaTokens.RAIL_CLOSED),-2,(rtl?Gravity.RIGHT:Gravity.LEFT)|Gravity.CENTER_VERTICAL);
        if(rtl)position.rightMargin=dp(activity,CinemaTokens.RAIL_LEFT);
        else position.leftMargin=dp(activity,CinemaTokens.RAIL_LEFT);
        root.addView(rail,position);
        Class<?>[] targets={CinemaSettingsActivity.class,MainActivityLeanback.class,VideoSearchActivity.class,CinemaWatchlistActivity.class,CinemaWatchlistActivity.class,CinemaPhoneLinkActivity.class,null};
        int[] icons={R.drawable.ic_cog_outline,R.drawable.cinema_home,R.drawable.cinema_search,R.drawable.cinema_watchlist,R.drawable.cinema_check,R.drawable.ic_tablet_cellphone,R.drawable.ic_menu_refresh};
        String[] names={label(activity,"الإعدادات","Settings"),label(activity,"الرئيسية","Home"),label(activity,"البحث","Search"),label(activity,"قائمة المشاهدة","Watchlist"),label(activity,"تمت المشاهدة","Watched"),label(activity,"ربط الهاتف","Link phone"),label(activity,"فحص المكتبة","Scan")};
        View[] previous={null};boolean[] expanded={false};boolean[] submenuOpen={false};android.animation.ValueAnimator[] animation={null};
        int exitRailKey=rtl?android.view.KeyEvent.KEYCODE_DPAD_LEFT:android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
        for(int n=0;n<targets.length;n++){
            final int idx=n;
            final Class<?> target=targets[n];TextView button=text(activity,CinemaTokens.LABEL,false);button.setContentDescription(names[n]);button.setTag(names[n]);button.setGravity(Gravity.CENTER_VERTICAL);button.setSingleLine(true);button.setPadding(dp(activity,10),0,dp(activity,8),0);iconOnly(button);button.setMinimumHeight(dp(activity,CinemaTokens.RAIL_ROW_HEIGHT));icon(button,icons[n]);navigationLabel(button,names[n],false);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(activity,CinemaTokens.RAIL_ROW_HEIGHT));lp.bottomMargin=dp(activity,4);rail.addView(button,lp);
            button.setLongClickable(true);
            button.setOnClickListener(v->{
                if(target==null){CinemaHomeFragment.triggerDirectScan(activity);return;}
                if(target==VideoSearchActivity.class){
                    CinemaActionMenu.showSearchSidebarPanel(activity,root,button,rail,
                            ()->submenuOpen[0]=true,()->submenuOpen[0]=false);
                    return;
                }
                Intent intent=new Intent(activity,target);
                if(idx==4)intent.putExtra(CinemaWatchlistActivity.EXTRA_WATCHED,true);
                if(target==MainActivityLeanback.class){
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    intent.putExtra(CinemaHomeFragment.EXTRA_RETURN_HOME_TOP,true);
                }
                activity.startActivity(intent);
            });
            button.setOnLongClickListener(v->{
                if(target==null||target==CinemaNetworkActivity.class){CinemaHomeFragment.triggerDirectScan(activity);return true;}
                return false;
            });
            button.setOnKeyListener((v,key,event)->{if(moveVertically(rail,v,key,event))return true;if(event.getAction()==android.view.KeyEvent.ACTION_DOWN&&key==android.view.KeyEvent.KEYCODE_MENU&&(target==null||target==CinemaNetworkActivity.class)){CinemaHomeFragment.triggerDirectScan(activity);return true;}if(event.getAction()==android.view.KeyEvent.ACTION_DOWN&&(key==exitRailKey||key==android.view.KeyEvent.KEYCODE_BACK)&&previous[0]!=null){previous[0].requestFocus();return true;}return false;});
        }
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus,newFocus)->{
            boolean inside=submenuOpen[0]||CinemaActionMenu.isPanelDescendant(newFocus,root);
            if(!inside){for(android.view.ViewParent p=newFocus==null?null:newFocus.getParent();p!=null;p=p.getParent())if(p==rail){inside=true;break;}}
            if(inside&&oldFocus!=null&&oldFocus.getParent()!=rail&&!CinemaActionMenu.isPanelDescendant(oldFocus,root))previous[0]=oldFocus;
            if(expanded[0]==inside)return;expanded[0]=inside;
            rail.setBackground(rounded(inside?CinemaTokens.DRAWER:CinemaTokens.RAIL_BACKGROUND,dp(activity,CinemaTokens.CONTROL_RADIUS),0));
            if(animation[0]!=null)animation[0].cancel();
            animation[0]=android.animation.ValueAnimator.ofInt(rail.getWidth(),dp(activity,inside?CinemaTokens.RAIL_OPEN:CinemaTokens.RAIL_CLOSED));
            animation[0].setDuration(motion(activity)?CinemaTokens.PANEL_MS:0);animation[0].setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
            animation[0].addUpdateListener(a->{rail.getLayoutParams().width=(Integer)a.getAnimatedValue();rail.requestLayout();});animation[0].start();
            scrim.setVisibility(View.VISIBLE);scrim.animate().alpha(inside?1:0).setDuration(motion(activity)?CinemaTokens.PANEL_MS:0).withEndAction(()->{if(!expanded[0])scrim.setVisibility(View.GONE);}).start();
            for(int n=0;n<rail.getChildCount();n++){TextView row=(TextView)rail.getChildAt(n);navigationLabel(row,(String)row.getTag(),inside);}
        });
        return rail.getChildAt(0);
    }
}

package com.archos.mediacenter.video.player;

import android.view.Display;

public final class CinemaDisplayModes {
    private CinemaDisplayModes(){}

    public static Display.Mode select(Display.Mode current,Display.Mode[] modes,float fps,int width,int height){
        Display.Mode best=current;
        double bestScore=Double.POSITIVE_INFINITY;
        for(Display.Mode mode:modes){
            boolean currentResolution=mode.getPhysicalWidth()==current.getPhysicalWidth()
                    &&mode.getPhysicalHeight()==current.getPhysicalHeight();
            boolean videoResolution=width>0&&height>0&&mode.getPhysicalWidth()==width&&mode.getPhysicalHeight()==height;
            if(!currentResolution&&!videoResolution)continue;
            float rate=mode.getRefreshRate();
            if(rate+0.02f<fps)continue;
            double error=Math.abs(rate-fps*Math.round(rate/fps));
            if(error>0.02)continue;
            double score=(videoResolution?0:1000)+error*100
                    +Math.abs(rate-fps)*0.001+(mode.getModeId()==current.getModeId()?0:0.0001);
            if(score<bestScore){bestScore=score;best=mode;}
        }
        return best;
    }
}

package com.archos.mediacenter.video.player;

import android.graphics.*;
import android.os.*;
import android.view.*;
import androidx.preference.PreferenceManager;
import com.archos.mediacenter.video.R;

/** Small in-memory video samples only. Never captures UI, writes frames, or changes decoder settings. */
public final class AdaptiveSubtitleContrast implements AutoCloseable {
    public static final String KEY="cinema_subtitle_auto_contrast";
    private final View root;private final SubtitleManager subtitles;
    private final Handler main=new Handler(Looper.getMainLooper());private final HandlerThread worker=new HandlerThread("subtitle-contrast");private final Handler analysis;
    private final Bitmap sample=Bitmap.createBitmap(96,54,Bitmap.Config.ARGB_8888);
    private boolean running,closed,inFlight;private int generation,current=Color.WHITE,candidate=Color.WHITE,stable;
    private volatile String diagnostic="disabled";private Runnable listener;
    public AdaptiveSubtitleContrast(View root,SubtitleManager subtitles){this.root=root;this.subtitles=subtitles;worker.start();analysis=new Handler(worker.getLooper());}
    public boolean enabled(){return PreferenceManager.getDefaultSharedPreferences(root.getContext()).getBoolean(KEY,false);}
    public int color(){return enabled()?current:subtitles.getColor();}
    public String diagnostic(){return diagnostic;}
    public void listener(Runnable value){listener=value;}
    public void setEnabled(boolean value){PreferenceManager.getDefaultSharedPreferences(root.getContext()).edit().putBoolean(KEY,value).apply();generation++;stable=0;subtitles.setAdaptiveColor(value,value?current:subtitles.getColor());if(!value)diagnostic="disabled";if(listener!=null)listener.run();}
    public void start(){if(closed)return;running=true;main.removeCallbacks(tick);main.post(tick);}
    public void stop(){running=false;generation++;main.removeCallbacks(tick);}
    public static int choose(double luminance,int previous){return previous==Color.BLACK?(luminance<0.14?Color.WHITE:Color.BLACK):(luminance>0.22?Color.BLACK:Color.WHITE);}
    private final Runnable tick=new Runnable(){@Override public void run(){if(!running||closed)return;if(enabled()&&!inFlight&&!subtitles.isGraphicSubtitle())capture();else if(!enabled())subtitles.setAdaptiveColor(false,subtitles.getColor());main.postDelayed(this,650);}};
    private void capture(){
        View video=root.findViewById(R.id.surface_view);View texture=root.findViewById(R.id.gl_surface_view);if(texture instanceof TextureView&&texture.isShown())video=texture;
        if(video==null||!video.isShown()||video.getWidth()==0||video.getHeight()==0){fallback("video surface unavailable");return;}
        Rect text=new Rect(),screen=new Rect();int[] location=new int[2];video.getLocationOnScreen(location);screen.set(location[0],location[1],location[0]+video.getWidth(),location[1]+video.getHeight());
        if(!subtitles.textBounds(text)){text.set(screen.left+screen.width()/5,screen.bottom-screen.height()/5,screen.right-screen.width()/5,screen.bottom-screen.height()/16);}
        if(!Rect.intersects(text,screen)){apply(0,"letterbox");return;}
        int x1=Math.max(0,Math.min(95,(text.left-screen.left)*96/Math.max(1,screen.width()))),x2=Math.max(x1+1,Math.min(96,(text.right-screen.left)*96/Math.max(1,screen.width())));
        int y1=Math.max(0,Math.min(53,(text.top-screen.top)*54/Math.max(1,screen.height()))),y2=Math.max(y1+1,Math.min(54,(text.bottom-screen.top)*54/Math.max(1,screen.height())));
        Rect band=new Rect(x1,y1,x2,y2);int token=generation;inFlight=true;
        try{
            if(video instanceof SurfaceView&&Build.VERSION.SDK_INT>=24){PixelCopy.request((SurfaceView)video,sample,status->{if(status==PixelCopy.SUCCESS)analyze(band,token);else main.post(()->{inFlight=false;if(running&&!closed&&generation==token)fallback("surface copy unavailable");});},analysis);}
            else if(video instanceof TextureView){Bitmap result=((TextureView)video).getBitmap(sample);if(result!=null)analysis.post(()->analyze(band,token));else{inFlight=false;fallback("texture unavailable");}}
            else{inFlight=false;fallback("capture unsupported");}
        }catch(RuntimeException unavailable){inFlight=false;fallback("capture unavailable");}
    }
    private void analyze(Rect band,int token){double sum=0;int count=0;for(int y=band.top;y<band.bottom;y++)for(int x=band.left;x<band.right;x++){int rgb=sample.getPixel(x,y);sum+=androidx.core.graphics.ColorUtils.calculateLuminance(rgb);count++;}double luminance=count==0?0:sum/count;main.post(()->{inFlight=false;if(running&&!closed&&generation==token&&enabled())apply(luminance,"video");});}
    private void apply(double luminance,String source){int previous=current;int desired=choose(luminance,current);if(desired==candidate)stable++;else{candidate=desired;stable=1;}if(desired!=current&&(stable>=2||luminance<0.04||luminance>0.65)){current=desired;stable=0;}subtitles.setAdaptiveColor(true,current);diagnostic=String.format(java.util.Locale.US,"%s; luminance=%.3f; color=%s",source,luminance,current==Color.BLACK?"black":"white");if(listener!=null&&previous!=current)listener.run();}
    private void fallback(String reason){int previous=current;current=Color.WHITE;subtitles.setAdaptiveColor(true,current);diagnostic=reason+"; outlined white fallback";if(listener!=null&&previous!=current)listener.run();}
    @Override public void close(){stop();closed=true;listener=null;worker.quitSafely();}
}

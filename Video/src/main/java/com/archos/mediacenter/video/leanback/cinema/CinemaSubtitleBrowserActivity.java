package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.archos.filecorelibrary.MetaFile2;
import com.archos.mediacenter.filecoreextension.upnp2.RawListerFactoryWithUpnp;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

/** Asynchronous file/SMB browser; selecting a subtitle never renames the original. */
public class CinemaSubtitleBrowserActivity extends LeanbackActivity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Uri video,current;private boolean secondary;private LinearLayout rows;private TextView status;private int generation;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);secondary=getIntent().getBooleanExtra("secondary_subtitle",false);video=getIntent().getData();if(video==null){finish();return;}
        ScrollView scroll=new ScrollView(this);scroll.setBackground(CinemaUi.surface());rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);rows.setPadding(dp(this,CinemaTokens.UTILITY_GUTTER),dp(this,CinemaTokens.SAFE),dp(this,CinemaTokens.UTILITY_GUTTER),dp(this,CinemaTokens.SAFE));scroll.addView(rows);setContentView(scroll);
        current=parent(video);load();
    }
    private Uri parent(Uri uri){String path=uri.getPath();if(path==null||path.equals("/"))return uri;int end=path.endsWith("/")?path.length()-1:path.length();int slash=path.lastIndexOf('/',end-1);return uri.buildUpon().path(slash<0?"/":path.substring(0,slash+1)).build();}
    private void button(String title,Runnable action){TextView t=text(this,CinemaTokens.BODY,false);t.setText(title);t.setSingleLine(true);t.setEllipsize(android.text.TextUtils.TruncateAt.END);t.setPadding(dp(this,15),dp(this,11),dp(this,15),dp(this,11));focusable(t);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(this,8);rows.addView(t,p);t.setOnClickListener(v->action.run());}
    private void load(){
        int token=++generation;rows.removeAllViews();status=text(this,CinemaTokens.BODY,true);status.setText(label(this,"اختيار ترجمة من المجلد الحالي","Choose a subtitle in the current folder")+"\n"+current.getPath());rows.addView(status);
        Uri target=current;
        button("↑  ..",()->{current=parent(current);load();});
        io.execute(()->{
            try{
                List<MetaFile2> files=new ArrayList<>(RawListerFactoryWithUpnp.getRawListerForUrl(target).getFileList());
                files.removeIf(f->!f.isDirectory()&&!f.getName().toLowerCase(Locale.ROOT).matches(secondary?".*\\.(srt|vtt)$":".*\\.(srt|ass|ssa|vtt|sub|idx)$"));
                files.sort(Comparator.comparing((MetaFile2 f)->!f.isDirectory()).thenComparing(MetaFile2::getName,String.CASE_INSENSITIVE_ORDER));
                handler.post(()->{if(isDestroyed()||token!=generation)return;for(MetaFile2 file:files)button((file.isDirectory()?"▤  ":"▱  ")+file.getName()+(file.isDirectory()||file.length()<=0?"":" · "+android.text.format.Formatter.formatShortFileSize(this,file.length())),()->{
                    if(file.isDirectory()){current=file.getUri();load();}
                    else if(secondary){setResult(Activity.RESULT_OK,new Intent().setData(file.getUri()));finish();}
                    else io.execute(()->{try(SubtitleStorage storage=new SubtitleStorage(this)){storage.bind(video,file.getUri());handler.post(()->{if(!isDestroyed()){setResult(Activity.RESULT_OK,new Intent().setData(file.getUri()));finish();}});}catch(Exception e){handler.post(()->{if(!isDestroyed())status.setText(label(this,"تعذر حفظ اختيار الترجمة","Could not save subtitle selection"));});}});
                });if(rows.getChildCount()>1)rows.getChildAt(1).requestFocus();});
            }catch(Exception e){handler.post(()->{if(!isDestroyed()&&token==generation)status.setText(label(this,"تعذر فتح المجلد. تحقق من الاتصال وصلاحيات SMB.","Cannot open folder. Check the connection and SMB permissions."));});}
        });
    }
    @Override public void onDestroy(){generation++;handler.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy();}
}

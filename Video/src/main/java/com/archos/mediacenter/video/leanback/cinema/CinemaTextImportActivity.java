package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

/** Fallback for TV firmware lacking DocumentsUI, using only already-granted storage access. */
public final class CinemaTextImportActivity extends LeanbackActivity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private File folder,root;private LinearLayout rows;private int generation;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        root=android.os.Environment.getExternalStorageDirectory();folder=android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        if(!folder.isDirectory())folder=root;
        ScrollView scroll=new ScrollView(this);scroll.setBackground(CinemaUi.surface());rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);rows.setPadding(dp(this,CinemaTokens.UTILITY_GUTTER),dp(this,CinemaTokens.SAFE),dp(this,CinemaTokens.UTILITY_GUTTER),dp(this,CinemaTokens.SAFE));scroll.addView(rows);setContentView(scroll);load();
    }
    private void button(String title,Runnable action){TextView t=text(this,CinemaTokens.BODY,false);t.setText(title);t.setPadding(dp(this,15),dp(this,11),dp(this,15),dp(this,11));focusable(t);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(this,8);rows.addView(t,p);t.setOnClickListener(v->action.run());}
    private void load(){
        int token=++generation;rows.removeAllViews();TextView title=text(this,20,true);title.setText(label(this,"استيراد مفاتيح من TXT","Import keys from TXT")+"\n"+folder.getAbsolutePath());rows.addView(title);
        if(!folder.equals(root))button("↑  ..",()->{folder=folder.getParentFile();load();});
        File target=folder;io.execute(()->{File[] files=target.listFiles(f->f.isDirectory()||f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".txt"));
            if(files!=null)Arrays.sort(files,Comparator.comparing((File f)->!f.isDirectory()).thenComparing(File::getName,String.CASE_INSENSITIVE_ORDER));
            main.post(()->{if(isDestroyed()||token!=generation)return;
                if(files==null){TextView message=text(this,15,false);message.setText(label(this,"لا يسمح النظام بقراءة هذا المجلد. اختر مجلداً آخر.","The system does not allow reading this folder. Choose another folder."));rows.addView(message);return;}
                for(File f:files)button((f.isDirectory()?"▤  ":"▱  ")+f.getName(),()->{if(f.isDirectory()){folder=f;load();}else{setResult(Activity.RESULT_OK,new Intent().putExtra("text_file_path",f.getAbsolutePath()));finish();}});
                if(rows.getChildCount()>1)rows.getChildAt(1).requestFocus();
            });
        });
    }
    @Override public void onDestroy(){generation++;main.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy();}
}

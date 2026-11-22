package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.archos.filecorelibrary.FileEditorFactory;
import com.archos.mediacenter.filecoreextension.upnp2.RawListerFactoryWithUpnp;
import com.archos.mediacenter.utils.ShortcutDbAdapter;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import com.squareup.picasso.Picasso;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

public final class CinemaFileBrowserActivity extends LeanbackActivity {
    private record Entry(String name,Uri uri,boolean directory,long modified,long size) {
        String type(){
            int dot=name.lastIndexOf('.');
            return dot<0?"":java.util.Objects.toString(MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substring(dot+1).toLowerCase(java.util.Locale.ROOT)),"");
        }
        boolean photo(){return !directory&&type().startsWith("image/");}
    }

    private final java.util.concurrent.ExecutorService folders=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ExecutorService images=Executors.newSingleThreadExecutor();
    private final ArrayDeque<Entry> path=new ArrayDeque<>();
    private final ArrayList<Entry> entries=new ArrayList<>();
    private FrameLayout root,viewer;
    private ImageView picture;
    private TextView location,status,photoCaption,layoutButton;
    private RecyclerView files;
    private Files adapter;
    private int generation,imageGeneration,sort,photoIndex;
    private boolean grid;
    private Uri selected;
    private List<Entry> photos=List.of();
    private float zoom=1;

    private String l(String ar,String en){return label(this,ar,en);}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        var prefs=PreferenceManager.getDefaultSharedPreferences(this);
        grid=prefs.getBoolean("cinema_files_grid",true);
        sort=prefs.getInt("cinema_files_sort",0);
        root=new FrameLayout(this);
        root.setBackgroundColor(CinemaTokens.CANVAS);
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int gutter=dp(this,CinemaTokens.UTILITY_GUTTER);
        page.setPadding(gutter,dp(this,16),gutter,dp(this,16));
        root.addView(page,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);

        LinearLayout tools=new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(tools,new LinearLayout.LayoutParams(-1,-2));
        control(tools,getString(com.archos.mediacenter.video.R.string.cinema_back),this::onBackPressed);
        location=text(this,CinemaTokens.ROW_TITLE,true);
        location.setSingleLine();
        location.setPadding(dp(this,12),0,dp(this,12),0);
        tools.addView(location,new LinearLayout.LayoutParams(0,-2,1));
        control(tools,getString(com.archos.mediacenter.video.R.string.cinema_sort),this::sortMenu);
        layoutButton=control(tools,layoutLabel(),()->{
            grid=!grid;
            prefs.edit().putBoolean("cinema_files_grid",grid).apply();
            layoutButton.setText(layoutLabel());
            updateLayout();
        });
        control(tools,getString(com.archos.mediacenter.video.R.string.cinema_network_files),()->startActivity(new Intent(this,
                com.archos.mediacenter.video.leanback.network.NetworkRootActivity.class)));
        control(tools,getString(com.archos.mediacenter.video.R.string.cinema_system_video_picker),()->setResultAndFinish(null));
        status=text(this,CinemaTokens.LABEL,false);
        page.addView(status);
        files=new RecyclerView(this);
        files.setId(View.generateViewId());
        files.setClipToPadding(false);
        files.setPadding(0,dp(this,8),0,dp(this,8));
        files.setItemAnimator(null);
        adapter=new Files();
        files.setAdapter(adapter);
        page.addView(files,new LinearLayout.LayoutParams(-1,0,1));
        updateLayout();
        load();
    }

    private TextView control(LinearLayout tools,String name,Runnable click){
        TextView button=text(this,CinemaTokens.LABEL,true);
        button.setText(name);
        button.setPadding(dp(this,12),0,dp(this,12),0);
        button.setGravity(Gravity.CENTER);
        focusable(button);
        var params=new LinearLayout.LayoutParams(-2,-2);
        params.rightMargin=dp(this,8);
        tools.addView(button,params);
        button.setOnClickListener(v->click.run());
        return button;
    }

    private String layoutLabel(){return grid?getString(com.archos.mediacenter.video.R.string.cinema_list_view):getString(com.archos.mediacenter.video.R.string.cinema_grid_view);}

    private void updateLayout(){
        int columns=Math.max(2,(getResources().getDisplayMetrics().widthPixels-dp(this,96))/dp(this,122));
        files.setLayoutManager(new GridLayoutManager(this,grid?columns:1));
        adapter.notifyDataSetChanged();
        focusEntry(generation);
    }

    private void sortMenu(){
        dialog(this).setTitle(getString(com.archos.mediacenter.video.R.string.cinema_sort_files)).setItems(new String[]{
                getString(com.archos.mediacenter.video.R.string.cinema_name_a_z),getString(com.archos.mediacenter.video.R.string.cinema_name_z_a),
                getString(com.archos.mediacenter.video.R.string.cinema_newest_first),getString(com.archos.mediacenter.video.R.string.cinema_oldest_first),getString(com.archos.mediacenter.video.R.string.cinema_largest_first)
        },(d,which)->{
            sort=which;
            PreferenceManager.getDefaultSharedPreferences(this).edit().putInt("cinema_files_sort",sort).apply();
            orderEntries();
            adapter.notifyDataSetChanged();
        }).show();
    }

    private void load(){
        int token=++generation;
        Entry folder=path.peek();
        location.setText(folder==null?getString(com.archos.mediacenter.video.R.string.cinema_file_browser):folder.name());
        entries.clear();
        adapter.notifyDataSetChanged();
        status.setText(getString(com.archos.mediacenter.video.R.string.cinema_loading_files));
        folders.execute(()->{
            List<Entry> result=new ArrayList<>();
            String error=null;
            try{
                if(folder==null){
                    File storage=Environment.getExternalStorageDirectory();
                    result.add(new Entry(getString(com.archos.mediacenter.video.R.string.cinema_internal_storage),Uri.fromFile(storage),true,0,0));
                    if(android.os.Build.VERSION.SDK_INT>=30){
                        var manager=getSystemService(android.os.storage.StorageManager.class);
                        if(manager!=null)for(var volume:manager.getStorageVolumes()){
                            File directory=volume.getDirectory();
                            if(!volume.isPrimary()&&directory!=null)
                                result.add(new Entry(volume.getDescription(this),Uri.fromFile(directory),true,0,0));
                        }
                    }
                    try(var cursor=ShortcutDbAdapter.VIDEO.queryAllShortcuts(this)){
                        if(cursor!=null)while(cursor.moveToNext()){
                            String source=cursor.getString(cursor.getColumnIndexOrThrow(ShortcutDbAdapter.KEY_PATH));
                            String name=cursor.getString(cursor.getColumnIndexOrThrow(ShortcutDbAdapter.KEY_NAME));
                            result.add(new Entry(name==null?Uri.parse(source).getLastPathSegment():name,Uri.parse(source),true,0,0));
                        }
                    }
                }else{
                    for(var file:RawListerFactoryWithUpnp.getRawListerForUrl(folder.uri()).getFileList()){
                        String name=file.getName();
                        if(name==null||name.equals(".")||name.equals(".."))continue;
                        boolean directory=file.isDirectory();
                        result.add(new Entry(name,file.getUri(),directory,file.lastModified(),directory?0:file.getComputedLength()));
                    }
                }
            }catch(Exception failure){error=getString(com.archos.mediacenter.video.R.string.cinema_could_not_read_this_source_check_permissions_and_connection);}
            String message=error;
            runOnUiThread(()->{
                if(isDestroyed()||token!=generation)return;
                entries.addAll(result);
                if(folder!=null)orderEntries();
                adapter.notifyDataSetChanged();
                status.setText(message!=null?message:entries.isEmpty()?getString(com.archos.mediacenter.video.R.string.cinema_empty_folder):"");
                focusEntry(token);
            });
        });
    }

    private void orderEntries(){
        Comparator<Entry> name=Comparator.comparing(Entry::name,String.CASE_INSENSITIVE_ORDER);
        Comparator<Entry> order=switch(sort){
            case 1->name.reversed();
            case 2->Comparator.comparingLong(Entry::modified).reversed();
            case 3->Comparator.comparingLong(e->e.modified()>0?e.modified():Long.MAX_VALUE);
            case 4->Comparator.comparingLong(Entry::size).reversed();
            default->name;
        };
        entries.sort(Comparator.comparing(Entry::directory).reversed().thenComparing(order).thenComparing(name));
    }

    private void focusEntry(int token){
        if(entries.isEmpty())return;
        int position=0;
        for(int n=0;n<entries.size();n++)if(entries.get(n).uri().equals(selected)){position=n;break;}
        int target=position;
        files.scrollToPosition(target);
        androidx.core.view.OneShotPreDrawListener.add(files,()->{
            if(token!=generation||viewer!=null)return;
            var holder=files.findViewHolderForAdapterPosition(target);
            if(holder!=null)holder.itemView.requestFocus();
        });
    }

    private void open(Entry entry){
        if(entry.directory()){
            path.push(entry);
            selected=null;
            load();
        }else if(entry.photo()){
            photos=entries.stream().filter(Entry::photo).collect(java.util.stream.Collectors.toList());
            photoIndex=photos.indexOf(entry);
            showViewer();
        }else if(entry.type().startsWith("video/")||entry.name().matches("(?i).*\\.(mkv|avi|mp4|m4v|mov|ts|m2ts|webm|mpg|mpeg|wmv|vob|3gp|flv)$")){
            setResultAndFinish(entry.uri());
        }else{
            android.widget.Toast.makeText(this,getString(com.archos.mediacenter.video.R.string.cinema_this_file_type_cannot_be_opened),android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void setResultAndFinish(Uri uri){
        Intent result=new Intent().setData(uri);
        if(uri==null)result.putExtra("system_picker",true);
        setResult(Activity.RESULT_OK,result);
        finish();
    }

    private void showViewer(){
        viewer=new FrameLayout(this);
        viewer.setBackgroundColor(0xff000000);
        picture=new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        viewer.addView(picture,new FrameLayout.LayoutParams(-1,-1));
        photoCaption=text(this,CinemaTokens.LABEL,true);
        photoCaption.setBackgroundColor(0xaa000000);
        photoCaption.setPadding(dp(this,16),dp(this,8),dp(this,16),dp(this,8));
        viewer.addView(photoCaption,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        viewer.setFocusableInTouchMode(true);
        root.addView(viewer,new FrameLayout.LayoutParams(-1,-1));
        viewer.requestFocus();
        loadPhoto();
    }

    private void loadPhoto(){
        int token=++imageGeneration;
        Entry entry=photos.get(photoIndex);
        selected=entry.uri();
        zoom=1;
        picture.setImageDrawable(null);
        transformPhoto(0,0);
        photoCaption.setText(entry.name()+"   "+(photoIndex+1)+" / "+photos.size()+"   ·   "+
                getString(com.archos.mediacenter.video.R.string.cinema_ch_ch_to_zoom));
        images.execute(()->{
            Bitmap bitmap=null;
            try{
                BitmapFactory.Options options=new BitmapFactory.Options();
                options.inJustDecodeBounds=true;
                try(InputStream input=openPhoto(entry.uri())){BitmapFactory.decodeStream(input,null,options);}
                options.inSampleSize=1;
                while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>4096)options.inSampleSize*=2;
                options.inJustDecodeBounds=false;
                try(InputStream input=openPhoto(entry.uri())){bitmap=BitmapFactory.decodeStream(input,null,options);}
            }catch(Exception ignored){ }
            Bitmap decoded=bitmap;
            runOnUiThread(()->{
                if(token!=imageGeneration||viewer==null){if(decoded!=null)decoded.recycle();return;}
                if(decoded!=null)picture.setImageBitmap(decoded);
                else photoCaption.setText(getString(com.archos.mediacenter.video.R.string.cinema_could_not_open_image)+" · "+entry.name());
            });
        });
    }

    private InputStream openPhoto(Uri uri)throws Exception{
        if("content".equals(uri.getScheme()))return getContentResolver().openInputStream(uri);
        return FileEditorFactory.getFileEditorForUrl(uri,this).getInputStream();
    }

    private void transformPhoto(float x,float y){
        picture.setScaleX(zoom);
        picture.setScaleY(zoom);
        var drawable=picture.getDrawable();
        float maxX=0,maxY=0;
        if(drawable!=null){
            float fit=Math.min((float)picture.getWidth()/drawable.getIntrinsicWidth(),
                    (float)picture.getHeight()/drawable.getIntrinsicHeight());
            maxX=Math.max(0,(drawable.getIntrinsicWidth()*fit*zoom-picture.getWidth())/2);
            maxY=Math.max(0,(drawable.getIntrinsicHeight()*fit*zoom-picture.getHeight())/2);
        }
        picture.setTranslationX(Math.max(-maxX,Math.min(maxX,x)));
        picture.setTranslationY(Math.max(-maxY,Math.min(maxY,y)));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(viewer==null)return super.dispatchKeyEvent(event);
        int key=event.getKeyCode();
        if(key==KeyEvent.KEYCODE_BACK)return super.dispatchKeyEvent(event);
        if(event.getAction()!=KeyEvent.ACTION_DOWN)return true;
        switch(key){
            case KeyEvent.KEYCODE_CHANNEL_UP,KeyEvent.KEYCODE_CHANNEL_DOWN->{
                zoom=Math.max(1,Math.min(4,zoom+(key==KeyEvent.KEYCODE_CHANNEL_UP?.25f:-.25f)));
                transformPhoto(picture.getTranslationX(),picture.getTranslationY());
            }
            case KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN->{
                if(zoom>1){
                    int step=dp(this,32);
                    transformPhoto(picture.getTranslationX()+(key==KeyEvent.KEYCODE_DPAD_LEFT?step:key==KeyEvent.KEYCODE_DPAD_RIGHT?-step:0),
                            picture.getTranslationY()+(key==KeyEvent.KEYCODE_DPAD_UP?step:key==KeyEvent.KEYCODE_DPAD_DOWN?-step:0));
                }else if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){
                    int next=photoIndex+(key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1);
                    if(next>=0&&next<photos.size()){photoIndex=next;loadPhoto();}
                }
            }
            case KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->{zoom=1;transformPhoto(0,0);}
            default->{return super.dispatchKeyEvent(event);}
        }
        return true;
    }

    @Override public void onBackPressed(){
        if(viewer!=null){
            imageGeneration++;
            root.removeView(viewer);
            viewer=null;
            picture=null;
            focusEntry(generation);
        }else if(!path.isEmpty()){
            selected=path.pop().uri();
            load();
        }else super.onBackPressed();
    }

    @Override public void onDestroy(){
        generation++;
        imageGeneration++;
        folders.shutdownNow();
        images.shutdownNow();
        super.onDestroy();
    }

    private final class Files extends RecyclerView.Adapter<FileCard>{
        @Override public int getItemCount(){return entries.size();}
        @Override public int getItemViewType(int position){return grid?1:0;}
        @Override public FileCard onCreateViewHolder(ViewGroup parent,int mode){
            boolean tile=mode==1;
            LinearLayout row=new LinearLayout(CinemaFileBrowserActivity.this);
            row.setOrientation(tile?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
            row.setGravity(tile?Gravity.CENTER:Gravity.CENTER_VERTICAL);
            row.setPadding(dp(CinemaFileBrowserActivity.this,8),dp(CinemaFileBrowserActivity.this,6),
                    dp(CinemaFileBrowserActivity.this,8),dp(CinemaFileBrowserActivity.this,6));
            focusable(row);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1,dp(CinemaFileBrowserActivity.this,tile?112:48)));
            ImageView icon=new ImageView(CinemaFileBrowserActivity.this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int iconSize=dp(CinemaFileBrowserActivity.this,tile?54:24);
            row.addView(icon,new LinearLayout.LayoutParams(iconSize,iconSize));
            TextView name=text(CinemaFileBrowserActivity.this,CinemaTokens.LABEL,false);
            name.setMaxLines(tile?2:1);
            name.setGravity(tile?Gravity.CENTER:Gravity.LEFT|Gravity.CENTER_VERTICAL);
            name.setPadding(dp(CinemaFileBrowserActivity.this,8),dp(CinemaFileBrowserActivity.this,4),0,0);
            row.addView(name,new LinearLayout.LayoutParams(tile?-1:0,-2,tile?0:1));
            return new FileCard(row,icon,name);
        }
        @Override public void onBindViewHolder(FileCard card,int position){
            Entry entry=entries.get(position);
            card.name.setText(entry.name());
            card.itemView.setContentDescription(entry.name());
            Picasso.get().cancelRequest(card.icon);
            int icon=entry.directory()?R.drawable.cinema_folder:entry.photo()?android.R.drawable.ic_menu_gallery:R.drawable.cinema_play_movie;
            card.icon.setImageResource(icon);
            if(grid&&entry.photo()&&"file".equals(entry.uri().getScheme()))
                Picasso.get().load(entry.uri()).resize(dp(CinemaFileBrowserActivity.this,54),dp(CinemaFileBrowserActivity.this,54))
                        .centerCrop().placeholder(icon).error(icon).into(card.icon);
            card.itemView.setOnFocusChangeListener((view,focused)->{
                card.name.setTextColor(focused?CinemaTokens.ON_FOCUS:CinemaTokens.TEXT);
                if(focused)selected=entry.uri();
            });
            card.name.setTextColor(card.itemView.hasFocus()?CinemaTokens.ON_FOCUS:CinemaTokens.TEXT);
            card.itemView.setOnClickListener(v->open(entry));
        }
        @Override public void onViewRecycled(FileCard card){Picasso.get().cancelRequest(card.icon);}
    }

    private static final class FileCard extends RecyclerView.ViewHolder{
        final ImageView icon;
        final TextView name;
        FileCard(View root,ImageView icon,TextView name){super(root);this.icon=icon;this.name=name;}
    }
}

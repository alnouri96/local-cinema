package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
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
import com.archos.mediacenter.filecoreextension.upnp2.RawListerFactoryWithUpnp;
import com.archos.mediacenter.utils.ShortcutDbAdapter;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import com.squareup.picasso.Picasso;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

public final class CinemaFileBrowserActivity extends LeanbackActivity {
    static final String IMAGE_MODE = "cinema_image_mode";
    static final String SELECTED_IMAGES = "cinema_selected_images";
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
    private final java.util.LinkedHashMap<Uri, CinemaArtStorage.Photo> marked = new java.util.LinkedHashMap<>();
    private final android.util.LruCache<Uri, Bitmap> thumbnails = new android.util.LruCache<>(4 * 1024 * 1024) {
        @Override protected int sizeOf(Uri uri, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private List<CinemaArtStorage.Photo> clipboard = List.of();
    private boolean imageMode, transferring;
    private final androidx.activity.result.ActivityResultLauncher<Intent> folderPicker = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null
                        && result.getData().getData() != null && !clipboard.isEmpty()) {
                    transferPhotos(clipboard, result.getData().getData(), false);
                }
            });
    private TextView selectionStatus;
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

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        imageMode = getIntent().getBooleanExtra(IMAGE_MODE, false);
        rootPermission();
        var prefs=PreferenceManager.getDefaultSharedPreferences(this);
        grid=prefs.getBoolean("cinema_files_grid",true);
        sort=prefs.getInt("cinema_files_sort",0);
        root=new FrameLayout(this);
        root.setLayoutDirection(CinemaUi.arabic(this) ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
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
        if (imageMode) addPhotoTools(page);
        else {
            control(tools,getString(R.string.cinema_network_files),()->startActivity(new Intent(this,
                    com.archos.mediacenter.video.leanback.network.NetworkRootActivity.class)));
            control(tools,getString(R.string.cinema_system_video_picker),()->setResultAndFinish(null));
        }
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

    private void rootPermission() {
        if (!imageMode) return;
        String permission = android.os.Build.VERSION.SDK_INT >= 33 ? android.Manifest.permission.READ_MEDIA_IMAGES
                : android.os.Build.VERSION.SDK_INT < 29 ? android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{permission}, 17);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == 17 && files != null) load();
    }

    private void addPhotoTools(LinearLayout page) {
        var tools = new LinearLayout(this);
        page.addView(tools, new LinearLayout.LayoutParams(-1, -2));
        control(tools, getString(R.string.cinema_photo_use_selection), this::returnPhotos);
        control(tools, getString(R.string.cinema_photo_copy), () -> {
            if (transferring || marked.isEmpty()) return;
            clipboard = List.copyOf(marked.values());
            marked.clear();
            adapter.notifyDataSetChanged();
            showSelection();
        });
        control(tools, getString(R.string.cinema_photo_paste), this::pastePhotos);
        control(tools, getString(R.string.cinema_photo_delete), this::deletePhotos);
        selectionStatus = text(this, CinemaTokens.LABEL, false);
        page.addView(selectionStatus);
        showSelection();
    }

    private void showSelection() {
        selectionStatus.setText(getString(R.string.cinema_photo_selection, marked.size(), clipboard.size()));
    }

    private void returnPhotos() {
        if (marked.isEmpty() || transferring) return;
        var paths = new ArrayList<String>();
        for (Uri uri : marked.keySet()) paths.add(uri.toString());
        setResult(RESULT_OK, new Intent().putStringArrayListExtra(SELECTED_IMAGES, paths));
        finish();
    }

    private void pastePhotos() {
        if (transferring || clipboard.isEmpty()) return;
        var folder = path.peek();
        var destinations = new ArrayList<String>();
        destinations.add(getString(R.string.cinema_photo_gallery));
        destinations.add(getString(R.string.cinema_photo_choose_folder));
        if (folder != null && !CinemaArtStorage.GALLERY.equals(folder.uri()))
            destinations.add(getString(R.string.cinema_photo_current_folder));
        dialog(this).setTitle(R.string.cinema_photo_paste).setItems(destinations.toArray(new String[0]), (dialog, button) -> {
            if (button == 1) {
                try {
                    folderPicker.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
                } catch (android.content.ActivityNotFoundException missing) {
                    android.widget.Toast.makeText(this, R.string.cinema_photo_folder_unavailable,
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            } else transferPhotos(clipboard, button == 0 ? CinemaArtStorage.GALLERY : folder.uri(), false);
        }).show();
    }

    private void deletePhotos() {
        if (transferring || marked.isEmpty() || path.isEmpty()
                || !CinemaArtStorage.GALLERY.equals(path.peek().uri())) return;
        List<CinemaArtStorage.Photo> gallerySelection = entries.stream().filter(entry -> marked.containsKey(entry.uri()))
                .map(entry -> marked.get(entry.uri())).collect(java.util.stream.Collectors.toList());
        if (gallerySelection.isEmpty()) return;
        dialog(this).setTitle(R.string.cinema_photo_delete)
                .setMessage(getString(R.string.cinema_photo_delete_confirm, gallerySelection.size()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.cinema_photo_delete,
                        (dialog, button) -> transferPhotos(gallerySelection, CinemaArtStorage.GALLERY, true)).show();
    }

    private void transferPhotos(List<CinemaArtStorage.Photo> sources, Uri target, boolean deleting) {
        transferring = true;
        var progress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(sources.size());
        var modal = dialog(this).setTitle(deleting ? R.string.cinema_photo_delete : R.string.cinema_photo_paste)
                .setView(progress).setCancelable(false).show();
        folders.execute(() -> {
            int completed = 0;
            var finished = new ArrayList<Uri>();
            for (var source : sources) {
                if (Thread.currentThread().isInterrupted()) break;
                try {
                    if (deleting) {
                        if (!CinemaArtStorage.delete(this, source.uri())) continue;
                    } else CinemaArtStorage.copy(this, source, target);
                    finished.add(source.uri());
                    completed++;
                } catch (Exception ignored) {
                    // Leave completed copies and the clipboard available for recovery.
                } finally {
                    runOnUiThread(() -> progress.incrementProgressBy(1));
                }
            }
            int successes = completed;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                if (deleting) marked.keySet().removeAll(finished);
                else clipboard = sources.stream().filter(photo -> !finished.contains(photo.uri()))
                        .collect(java.util.stream.Collectors.toList());
                transferring = false;
                modal.dismiss();
                showSelection();
                load();
                android.widget.Toast.makeText(this, getString(R.string.cinema_photo_transfer_result,
                        successes, sources.size()), android.widget.Toast.LENGTH_LONG).show();
            });
        });
    }

    private TextView control(LinearLayout tools,String name,Runnable click){
        TextView button=text(this,CinemaTokens.LABEL,true);
        button.setText(name);
        button.setPadding(dp(this,12),0,dp(this,12),0);
        button.setGravity(Gravity.CENTER);
        focusable(button);
        var params=new LinearLayout.LayoutParams(-2,-2);
        params.setMarginEnd(dp(this,8));
        button.setMinHeight(dp(this,48));
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
                    if (imageMode) result.add(new Entry(getString(R.string.cinema_photo_gallery),
                            CinemaArtStorage.GALLERY, true, 0, 0));
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
                } else if (CinemaArtStorage.GALLERY.equals(folder.uri())) {
                    for (var photo : CinemaArtStorage.gallery(this))
                        result.add(new Entry(photo.name(), photo.uri(), false, photo.changed(), photo.size()));
                }else{
                    for(var file:RawListerFactoryWithUpnp.getRawListerForUrl(folder.uri()).getFileList()){
                        String name=file.getName();
                        if(name==null||name.equals(".")||name.equals(".."))continue;
                        boolean directory=file.isDirectory();
                        if (imageMode && !directory && CinemaArtStorage.mime(name).isEmpty()) continue;
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
            if(holder!=null)holder.itemView.requestFocusFromTouch();
        });
    }

    private void open(Entry entry){
        if(entry.directory()){
            path.push(entry);
            selected=null;
            load();
        } else if (imageMode) {
            if (marked.remove(entry.uri()) == null) marked.put(entry.uri(),
                    new CinemaArtStorage.Photo(entry.name(), entry.uri(), entry.size(), entry.modified()));
            showSelection();
            adapter.notifyItemChanged(entries.indexOf(entry));
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
                bitmap = CinemaArtStorage.decode(this, entry.uri(), 3840);
            }catch(Exception ignored){ }
            Bitmap decoded=bitmap;
            runOnUiThread(()->{
                if(token!=imageGeneration||viewer==null){if(decoded!=null)decoded.recycle();return;}
                if(decoded!=null)picture.setImageBitmap(decoded);
                else photoCaption.setText(getString(com.archos.mediacenter.video.R.string.cinema_could_not_open_image)+" · "+entry.name());
            });
        });
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
                    int direction = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                    if (root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) direction = -direction;
                    int next = photoIndex + direction;
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
            name.setGravity(tile?Gravity.CENTER:Gravity.START|Gravity.CENTER_VERTICAL);
            name.setPadding(dp(CinemaFileBrowserActivity.this,8),dp(CinemaFileBrowserActivity.this,4),0,0);
            row.addView(name,new LinearLayout.LayoutParams(tile?-1:0,-2,tile?0:1));
            return new FileCard(row,icon,name);
        }
        @Override public void onBindViewHolder(FileCard card,int position){
            Entry entry=entries.get(position);
            boolean checked = marked.containsKey(entry.uri());
            card.name.setText(checked ? getString(R.string.cinema_photo_marked, entry.name()) : entry.name());
            card.itemView.setContentDescription(card.name.getText());
            card.itemView.setActivated(checked);
            if (card.work != null) card.work.cancel(false);
            card.uri = entry.uri();
            Picasso.get().cancelRequest(card.icon);
            int icon=entry.directory()?R.drawable.cinema_folder:entry.photo()?android.R.drawable.ic_menu_gallery:R.drawable.cinema_play_movie;
            card.icon.setImageResource(icon);
            if (entry.photo()) {
                Bitmap cached = thumbnails.get(entry.uri());
                if (cached != null) card.icon.setImageBitmap(cached);
                else if ("file".equals(entry.uri().getScheme()) || "content".equals(entry.uri().getScheme())) {
                    int size = dp(CinemaFileBrowserActivity.this, grid ? 54 : 32);
                    Picasso.get().load(entry.uri()).resize(size, size).centerInside().placeholder(icon).error(icon).into(card.icon);
                } else if (imageMode) {
                    card.work = images.submit(() -> {
                        try {
                            var thumbnail = CinemaArtStorage.decode(CinemaFileBrowserActivity.this, entry.uri(), 144);
                            if (thumbnail == null) return;
                            thumbnails.put(entry.uri(), thumbnail);
                            runOnUiThread(() -> {
                                if (entry.uri().equals(card.uri) && !isDestroyed()) card.icon.setImageBitmap(thumbnail);
                            });
                        } catch (Exception ignored) {}
                    });
                }
            }
            card.itemView.setOnFocusChangeListener((view,focused)->{
                card.name.setTextColor(focused?CinemaTokens.ON_FOCUS:CinemaTokens.TEXT);
                if(focused)selected=entry.uri();
            });
            card.name.setTextColor(card.itemView.hasFocus()?CinemaTokens.ON_FOCUS:CinemaTokens.TEXT);
            card.itemView.setOnClickListener(v->open(entry));
        }
        @Override public void onViewRecycled(FileCard card) {
            Picasso.get().cancelRequest(card.icon);
            if (card.work != null) card.work.cancel(false);
            card.uri = null;
        }
    }

    private static final class FileCard extends RecyclerView.ViewHolder{
        final ImageView icon;
        final TextView name;
        Uri uri;
        java.util.concurrent.Future<?> work;
        FileCard(View root,ImageView icon,TextView name){super(root);this.icon=icon;this.name=name;}
    }
}

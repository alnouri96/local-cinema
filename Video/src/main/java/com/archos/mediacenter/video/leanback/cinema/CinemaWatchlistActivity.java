package com.archos.mediacenter.video.leanback.cinema;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Episode;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import com.archos.mediacenter.video.leanback.MainActivityLeanback;
import com.archos.mediacenter.video.leanback.details.VideoDetailsFragment;
import com.squareup.picasso.Picasso;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

/** Poster collection backed by the same per-media watchlist as the details action. */
public class CinemaWatchlistActivity extends LeanbackActivity {
    public static final String KEY="cinema_watchlist";
    public static final String EXTRA_WATCHED="cinema_watched_mode";
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService artIo=Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> artworkTask;
    private Runnable pendingArt;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final List<Video> videos=new ArrayList<>();
    private final Map<String,Uri> posters=new HashMap<>();
    private final Map<String,CinemaArtwork.Art> artwork=new HashMap<>();
    private FrameLayout root;private ImageView backdrop;private RecyclerView grid;private TextView count,status,retry,syncBtn;
    private Posters adapter;private boolean loading=true;private int generation,columns,posterWidth,posterHeight;
    private record WatchedSeason(long showId,int number) {}
    private final java.util.Map<String,WatchedSeason> watchedSeasons=new java.util.HashMap<>();
    private final java.util.Map<String,CinemaWatchStore.TraktItem> traktEntries=new java.util.HashMap<>();
    private static volatile long lastAutoSyncMs=0L;
    private final android.content.BroadcastReceiver syncReceiver=new android.content.BroadcastReceiver(){
        @Override public void onReceive(android.content.Context ctx,Intent intent){if(!isDestroyed())load();}
    };
    private String selectedPath;private boolean layoutCheck,watchedMode;
    public static boolean contains(android.content.Context c,Video v){
        if (c == null || v == null) return false;
        return PreferenceManager.getDefaultSharedPreferences(c).getStringSet(KEY,Set.of()).contains(v.getFilePath())
                || CinemaWatchStore.isInTraktWatchlist(c, v);
    }
    public static boolean toggle(android.content.Context c,Video v){
        var prefs=PreferenceManager.getDefaultSharedPreferences(c);Set<String> set=new HashSet<>(prefs.getStringSet(KEY,Set.of()));
        boolean currentlyIn = contains(c, v);
        boolean added = !currentlyIn;
        if (added) set.add(v.getFilePath());
        else set.remove(v.getFilePath());
        prefs.edit().putStringSet(KEY,set).apply();
        syncSingleWatchlistToTrakt(c, v, added);
        return added;
    }
    static void toggleEpisodes(android.content.Context context,List<Episode> episodes){
        if(episodes.isEmpty())return;
        var prefs=PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> saved=new HashSet<>(prefs.getStringSet(KEY,Set.of()));
        var paths=episodes.stream().map(Episode::getFilePath).collect(java.util.stream.Collectors.toList());
        boolean currentlyIn = saved.containsAll(paths) || CinemaWatchStore.isInTraktWatchlist(context, episodes.get(0));
        boolean added = !currentlyIn;
        if(!added){saved.removeAll(paths);}
        else{saved.addAll(paths);}
        prefs.edit().putStringSet(KEY,saved).apply();
        syncSingleWatchlistToTrakt(context, episodes.get(0), added);
    }
    private static void syncSingleWatchlistToTrakt(android.content.Context context, Video v, boolean add) {
        if (context == null || v == null) return;
        android.content.Context app = context.getApplicationContext();
        String title = v instanceof Episode ep && ep.getShowName() != null && !ep.getShowName().isBlank()
                ? ep.getShowName() : v.getName();
        int year = v instanceof Movie m ? m.getYear() : 0;
        new Thread(() -> {
            try {
                boolean isShow = v instanceof Episode;
                CinemaWatchStore.PlaybackDescriptor desc = CinemaWatchStore.resolvePlaybackDescriptor(
                        app, v.getId(), v.getFilePath(), title);
                String imdb = desc != null ? desc.imdbId() : "";
                long tmdb = desc != null ? desc.tmdbId() : 0L;
                if (isShow && (imdb.isEmpty() || tmdb <= 0) && v.getId() > 0) {
                    try (android.database.Cursor c = app.getContentResolver().query(
                            com.archos.mediaprovider.video.VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                            new String[]{
                                    com.archos.mediaprovider.video.VideoStore.Video.VideoColumns.SCRAPER_S_IMDB_ID,
                                    com.archos.mediaprovider.video.VideoStore.Video.VideoColumns.SCRAPER_S_ONLINE_ID
                            },
                            com.archos.mediaprovider.video.VideoStore.Video.VideoColumns._ID + "=?",
                            new String[]{Long.toString(v.getId())}, null)) {
                        if (c != null && c.moveToFirst()) {
                            if (imdb.isEmpty() && c.getString(0) != null) imdb = c.getString(0);
                            if (tmdb <= 0) tmdb = c.getLong(1);
                        }
                    } catch (Exception ignored) {}
                }
                CinemaWatchStore.updateLocalTraktWatchlistRow(app, isShow, title, imdb, tmdb, year, add);
                CinemaLinkServer.broadcastWatchSync(app);
                app.sendBroadcast(new Intent(CinemaLinkServer.ACTION_WATCH_SYNC_UPDATED).setPackage(app.getPackageName()));
                if (com.meedx.providers.TraktSyncClient.isConnected(app)) {
                    if (com.meedx.providers.TraktSyncClient.syncWatchlistItem(app, isShow, imdb, tmdb, add)) {
                        CinemaWatchStore.syncWithTraktAsync(app);
                    }
                }
            } catch (Exception ignored) {}
        }, "trakt-watchlist-push").start();
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);if(state!=null)selectedPath=state.getString("selected_path");
        androidx.core.content.ContextCompat.registerReceiver(this, syncReceiver,
                new android.content.IntentFilter(CinemaLinkServer.ACTION_WATCH_SYNC_UPDATED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        layoutCheck=com.archos.mediacenter.video.BuildConfig.DEBUG&&getIntent().getBooleanExtra("layout_check",false);
        watchedMode=getIntent().getBooleanExtra(EXTRA_WATCHED,false);
        boolean rtl=arabic(this);
        root=new FrameLayout(this);root.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);root.setBackgroundColor(CinemaTokens.CANVAS);root.setFocusableInTouchMode(true);setContentView(root);root.requestFocus();
        backdrop=new ImageView(this);backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);root.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
        View shade=new View(this);shade.setBackground(new GradientDrawable(rtl?GradientDrawable.Orientation.RIGHT_LEFT:GradientDrawable.Orientation.LEFT_RIGHT,new int[]{CinemaTokens.CANVAS,0xdd171717,0xbb171717}));root.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        View bottom=new View(this);bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00171717,CinemaTokens.CANVAS}));root.addView(bottom,new FrameLayout.LayoutParams(-1,-1));
        TextView heading=text(this,CinemaTokens.PAGE_TITLE,true);heading.setGravity(rtl?Gravity.RIGHT:Gravity.LEFT);heading.setText(layoutCheck?"Watchlist · layout check":watchedMode?label(this,"تمت المشاهدة","Watched"):label(this,"قائمة المشاهدة","Watchlist"));
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|(rtl?Gravity.RIGHT:Gravity.LEFT));
        if(rtl)hp.rightMargin=dp(this,CinemaTokens.GUTTER);else hp.leftMargin=dp(this,CinemaTokens.GUTTER);
        hp.topMargin=dp(this,CinemaTokens.SAFE);root.addView(heading,hp);
        LinearLayout headerRight=new LinearLayout(this);headerRight.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);headerRight.setOrientation(LinearLayout.HORIZONTAL);headerRight.setGravity(Gravity.CENTER_VERTICAL);
        count=text(this,CinemaTokens.LABEL,false);count.setTextColor(CinemaTokens.SECONDARY);
        LinearLayout.LayoutParams cntLp=new LinearLayout.LayoutParams(-2,-2);cntLp.setMarginEnd(dp(this,16));headerRight.addView(count,cntLp);
        syncBtn=text(this,CinemaTokens.LABEL,true);syncBtn.setText(label(this,"⟳ تحديث · مزامنة Trakt","⟳ Refresh · Trakt Sync"));
        syncBtn.setGravity(Gravity.CENTER);syncBtn.setPadding(dp(this,14),0,dp(this,14),0);
        focusable(syncBtn);CinemaUi.icon(syncBtn,com.archos.mediacenter.video.R.drawable.ic_menu_refresh);syncBtn.setCompoundDrawablePadding(dp(this,8));
        syncBtn.setOnClickListener(v->refreshFromTraktAndReload());
        syncBtn.setOnKeyListener((v,key,event)->{if(event.getAction()==KeyEvent.ACTION_DOWN&&key==KeyEvent.KEYCODE_DPAD_DOWN&&!videos.isEmpty()){focus(0,2);return true;}return false;});
        headerRight.addView(syncBtn,new LinearLayout.LayoutParams(-2,-2));
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|(rtl?Gravity.LEFT:Gravity.RIGHT));
        if(rtl)cp.leftMargin=dp(this,48);else cp.rightMargin=dp(this,48);
        cp.topMargin=dp(this,24);root.addView(headerRight,cp);
        int available=getResources().getDisplayMetrics().widthPixels-dp(this,2*CinemaTokens.GUTTER);
        columns=Math.max(1,available/dp(this,136));posterWidth=(available-dp(this,16)*(columns-1))/columns;posterHeight=posterWidth*3/2;
        grid=new RecyclerView(this);grid.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);grid.setLayoutManager(new GridLayoutManager(this,columns));grid.setItemAnimator(null);grid.setClipChildren(false);grid.setClipToPadding(false);grid.setPadding(dp(this,8),dp(this,8),dp(this,8),dp(this,CinemaTokens.SAFE));
        grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);grid.setFocusable(false);
        FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(-1,-1);gp.leftMargin=dp(this,CinemaTokens.GUTTER-8);gp.rightMargin=dp(this,CinemaTokens.GUTTER-8);gp.topMargin=dp(this,88);root.addView(grid,gp);
        grid.addItemDecoration(new RecyclerView.ItemDecoration(){@Override public void getItemOffsets(android.graphics.Rect out,View view,RecyclerView parent,RecyclerView.State state){int p=parent.getChildAdapterPosition(view);int column=Math.max(0,p)%columns,gap=dp(CinemaWatchlistActivity.this,16);out.left=column*gap/columns;out.right=gap-(column+1)*gap/columns;out.bottom=dp(CinemaWatchlistActivity.this,24);}});
        adapter=new Posters();grid.setAdapter(adapter);
        LinearLayout message=new LinearLayout(this);message.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);message.setGravity(rtl?Gravity.RIGHT:Gravity.LEFT);message.setOrientation(LinearLayout.VERTICAL);message.setPaddingRelative(dp(this,CinemaTokens.GUTTER),dp(this,112),dp(this,48),0);root.addView(message,new FrameLayout.LayoutParams(-1,-2));
        status=text(this,CinemaTokens.BODY,false);status.setGravity(rtl?Gravity.RIGHT:Gravity.LEFT);status.setTextColor(CinemaTokens.SECONDARY);message.addView(status,new LinearLayout.LayoutParams(-1,-2));
        retry=text(this,CinemaTokens.LABEL,true);retry.setText(com.archos.mediacenter.video.R.string.cinema_watchlist_retry);retry.setGravity(Gravity.CENTER);focusable(retry);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(dp(this,160),dp(this,CinemaTokens.CONTROL_HEIGHT));rp.gravity=rtl?Gravity.RIGHT:Gravity.LEFT;rp.topMargin=dp(this,16);message.addView(retry,rp);retry.setVisibility(View.GONE);retry.setOnClickListener(v->load());
        CinemaNavigation.attach(this,root);
    }
    @Override public void onResume(){
        super.onResume();
        load();
        long now = System.currentTimeMillis();
        if (now - lastAutoSyncMs > 60_000L && com.meedx.providers.TraktSyncClient.isConnected(getApplicationContext())) {
            lastAutoSyncMs = now;
            CinemaWatchStore.syncWithTraktAsync(getApplicationContext());
        }
    }
    private void load(){
        int token=++generation;Set<String> saved=new HashSet<>(PreferenceManager.getDefaultSharedPreferences(this).getStringSet(KEY,Set.of()));
        boolean hasTraktConnected=com.meedx.providers.TraktSyncClient.isConnected(getApplicationContext());
        loading=videos.isEmpty()&&(watchedMode||hasTraktConnected||!saved.isEmpty()||layoutCheck);status.setText("");retry.setVisibility(View.GONE);adapter.notifyDataSetChanged();
        io.execute(()->{try{
            ArrayList<Video> loaded=new ArrayList<>();
            var grouped=new java.util.HashMap<String,WatchedSeason>();
            var nextTrakt=new java.util.HashMap<String,CinemaWatchStore.TraktItem>();
            var seenNormTitles=new java.util.HashSet<String>();
            if(watchedMode){
                String watchedFilter="(" + com.archos.mediaprovider.video.VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN + "=1 OR "
                        + com.archos.mediaprovider.video.VideoStore.Video.VideoColumns.BOOKMARK + "=" + com.archos.mediacenter.video.player.PlayerActivity.LAST_POSITION_END + ") AND "
                        + com.archos.mediaprovider.video.VideoStore.Video.VideoColumns.ARCHOS_HIDDEN_BY_USER + "=0";
                String watchedOrder=com.archos.mediaprovider.video.VideoStore.Video.VideoColumns.ARCHOS_LAST_TIME_PLAYED + " DESC, _id DESC";
                try(Cursor cursor=getContentResolver().query(com.archos.mediaprovider.video.VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                        com.archos.mediacenter.video.browser.loader.VideoLoader.mProjection,watchedFilter,null,watchedOrder)){
                    if(cursor==null)throw new IllegalStateException();
                    VideoCursorMapper mapper=new VideoCursorMapper();mapper.bindColumns(cursor);
                    var seasons=new java.util.HashSet<WatchedSeason>();
                    int showColumn=cursor.getColumnIndexOrThrow(com.archos.mediaprovider.video.VideoStore.Video.VideoColumns.SCRAPER_SHOW_ID);
                    while(cursor.moveToNext()){
                        Video video=(Video)mapper.bind(cursor);
                        if(video instanceof Episode episode&&cursor.getLong(showColumn)>0){
                            var season=new WatchedSeason(cursor.getLong(showColumn),episode.getSeasonNumber());
                            if(!seasons.add(season))continue;
                            grouped.put(video.getFilePath(),season);
                            seenNormTitles.add("s:"+CinemaWatchStore.normalizeTitle(episode.getShowName())+":"+episode.getSeasonNumber());
                        } else {
                            seenNormTitles.add("m:"+CinemaWatchStore.normalizeTitle(video.getName()));
                        }
                        loaded.add(video);
                    }
                }
            }else if(!saved.isEmpty()||layoutCheck){
                StringBuilder filter=new StringBuilder("_data IN (");for(int n=0;n<saved.size();n++){if(n>0)filter.append(',');filter.append('?');}filter.append(')');
                try(Cursor cursor=getContentResolver().query(com.archos.mediaprovider.video.VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                        com.archos.mediacenter.video.browser.loader.VideoLoader.mProjection,layoutCheck?null:filter.toString(),layoutCheck?null:saved.toArray(new String[0]),"_id DESC")){
                    if(cursor==null)throw new IllegalStateException();VideoCursorMapper mapper=new VideoCursorMapper();mapper.bindColumns(cursor);while(cursor.moveToNext()&&(!layoutCheck||loaded.size()<18)){
                        Video v=(Video)mapper.bind(cursor);
                        loaded.add(v);
                        String kName = v instanceof Episode ep ? ep.getShowName() : v.getName();
                        seenNormTitles.add(CinemaWatchStore.normalizeTitle(kName));
                    }
                }
            }
            if(!layoutCheck){
                var traktRows = CinemaWatchStore.listTraktItems(getApplicationContext(), watchedMode ? "watched" : "watchlist");
                long synthId = 920_000_000L;
                for(CinemaWatchStore.TraktItem ti : traktRows){
                    String norm = CinemaWatchStore.normalizeTitle(ti.title());
                    if(watchedMode){
                        String k = ("episode".equals(ti.mediaType()) || ti.season()>0)
                                ? ("s:"+norm+":"+ti.season()) : ("m:"+norm);
                        if(!seenNormTitles.add(k)) continue;
                    } else {
                        if(!norm.isEmpty() && !seenNormTitles.add(norm)) continue;
                    }
                    String vPath = "trakt://" + ti.itemKey();
                    nextTrakt.put(vPath, ti);
                    Video v;
                    if("episode".equals(ti.mediaType()) || "show".equals(ti.mediaType()) || ti.season()>0 || ti.episode()>0){
                        String epLabel = ti.season()>0 ? ("S"+String.format(Locale.US,"%02dE%02d",ti.season(),ti.episode())) : "Trakt";
                        v = new Episode(synthId, synthId, Math.max(1, ti.season()), Math.max(1, ti.episode()), epLabel,
                                0L, 0f, "", "", ti.title(), vPath, null, null, 0, 0,
                                0, 0, watchedMode, true, false, false, ti.tmdbId(), ti.updatedAt()/1000L,
                                0, 0, "", "", "", "", 0, 1, 0L);
                    } else {
                        v = new Movie(synthId, vPath, ti.title(), Math.max(0L, ti.tmdbId()), "", ti.year(), 0f, "", null, 0, 0,
                                0, 0, watchedMode, true, false, false, ti.tmdbId(), ti.updatedAt()/1000L,
                                0, 0, "", "", "", "", 0, 1, 0L, 0L);
                    }
                    synthId++;
                    loaded.add(v);
                }
            }
            handler.post(()->{if(isDestroyed()||token!=generation)return;loading=false;watchedSeasons.clear();watchedSeasons.putAll(grouped);traktEntries.clear();traktEntries.putAll(nextTrakt);videos.clear();videos.addAll(loaded);adapter.notifyDataSetChanged();count.setText(digits(videos.size()+(arabic(this)?(videos.size()==1?" عنصر":" عناصر"):(videos.size()==1?" item":" items"))));
                if(videos.isEmpty()){backdrop.setImageDrawable(null);status.setText(watchedMode?label(this,"لا توجد عناوين تمت مشاهدتها بعد.","No watched titles yet."):label(this,"لم تضف عناصر بعد. أضفها من صفحة التفاصيل.","No items yet. Add them from a video's details."));retry.setText(label(this,"فتح المكتبة","Browse library"));retry.setVisibility(View.VISIBLE);retry.setOnClickListener(v->startActivity(new Intent(this,MainActivityLeanback.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)));retry.requestFocus();}
                else {
                    for(Video v : videos){
                        if(v.getFilePath()!=null && v.getFilePath().startsWith("trakt://")) prefetchTraktArt(v);
                    }
                    int chosen=0;for(int n=0;n<videos.size();n++)if(videos.get(n).getFilePath().equals(selectedPath))chosen=n;focus(chosen,3);
                }
            });
        }catch(Exception failure){handler.post(()->{if(isDestroyed()||token!=generation)return;loading=false;videos.clear();count.setText("");adapter.notifyDataSetChanged();status.setText(watchedMode?label(this,"تعذر قراءة قائمة الأفلام المشاهدة","Could not read watched movies"):label(this,"تعذر قراءة قائمة المشاهدة","Could not read the watchlist"));retry.setText(label(this,"إعادة المحاولة","Retry"));retry.setVisibility(View.VISIBLE);retry.setOnClickListener(v->load());retry.requestFocus();});}});
    }
    private void focus(int position,int attempts){grid.scrollToPosition(position);grid.post(()->{if(isDestroyed())return;var holder=grid.findViewHolderForAdapterPosition(position);if(holder!=null)holder.itemView.requestFocus();else if(attempts>0)focus(position,attempts-1);});}
    private GradientDrawable focusRing(){return posterFocusRing(this,3);}
    private String displayName(Video video){
        CinemaWatchStore.TraktItem ti = traktEntries.get(video.getFilePath());
        if(ti!=null){
            String t = ti.title();
            if(ti.year()>0 && !watchedMode) t += " ("+ti.year()+")";
            if("episode".equals(ti.mediaType()) && ti.season()>0) t += "\n"+String.format(Locale.US,"S%02d E%02d",ti.season(),ti.episode());
            return t;
        }
        var season=watchedSeasons.get(video.getFilePath());
        if(season!=null&&video instanceof Episode episode)return episode.getShowName()+"\n"+(season.number()==0?label(this,"حلقات خاصة","Specials"):label(this,"الموسم ","Season ")+season.number());
        return video instanceof Episode episode?episode.getShowName()+"\n"+String.format(Locale.US,"S%02d E%02d · %s",episode.getSeasonNumber(),episode.getEpisodeNumber(),episode.getName()):video.getName();}
    private void open(Video video){
        CinemaWatchStore.TraktItem ti = traktEntries.get(video.getFilePath());
        if(ti!=null){
            openTraktItemMenu(video, ti);
            return;
        }
        var season=watchedSeasons.get(video.getFilePath());
        if(season!=null&&video instanceof Episode episode){
            var show=new com.archos.mediacenter.video.browser.adapters.object.Tvshow(season.showId(),episode.getShowName(),
                    episode.getPosterUri(),0,0,0,episode.getFilePath());
            startActivity(new Intent(this,CinemaSeriesActivity.class)
                    .putExtra(com.archos.mediacenter.video.leanback.tvshow.TvshowFragment.EXTRA_TVSHOW,show)
                    .putExtra("open_season",season.number()));
            return;
        }
        if(video instanceof Episode || CinemaHomeFragment.isSeriesOrEpisodeFile(video,video.getFilePath())){
            String showTitle=video instanceof Episode ep?ep.getShowName():CinemaPosters.query(video,true).title();
            var show=new com.archos.mediacenter.video.browser.adapters.object.Tvshow(0L,showTitle,
                    video.getPosterUri(),1,0,0,video.getFilePath());
            startActivity(new Intent(this,CinemaSeriesActivity.class)
                    .putExtra(com.archos.mediacenter.video.leanback.tvshow.TvshowFragment.EXTRA_TVSHOW,show));
            return;
        }
        startActivity(new Intent(this,CinemaDetailsActivity.class).putExtra(VideoDetailsFragment.EXTRA_VIDEO,video));}
    private void openTraktItemMenu(Video video, CinemaWatchStore.TraktItem ti){
        boolean isSeries = "show".equals(ti.mediaType()) || "episode".equals(ti.mediaType()) || ti.season()>0;
        java.util.List<String> opts = new java.util.ArrayList<>();
        if(com.meedx.providers.StremioAddons.hasEnabled(this) && ti.imdbId()!=null && ti.imdbId().matches("tt[0-9]+")){
            opts.add(label(this,"تشغيل عبر الإضافات (Nuvio / Stremio)","Stream via Addons"));
        }
        opts.add(watchedMode?label(this,"إزالة من تمت المشاهدة في Trakt","Mark as Unwatched on Trakt")
                :label(this,"إزالة من قائمة المشاهدة في Trakt","Remove from Trakt Watchlist"));
        CinemaUi.dialog(this).setTitle(ti.title()).setItems(opts.toArray(new String[0]),(d,w)->{
            String chosen = opts.get(w);
            if(chosen.contains("Addons") || chosen.contains("الإضافات")){
                var meta = new com.meedx.providers.OpenMetadata.Item(ti.title(), ti.year()>0?String.valueOf(ti.year()):"",
                        "", "", "", ti.imdbId(), null, null, "");
                CinemaAddonPicker.show(this, io, handler, video, meta, Math.max(1,ti.season()), Math.max(1,ti.episode()));
            } else {
                android.widget.Toast.makeText(this, label(this,"جاري التحديث في Trakt...","Updating Trakt..."), android.widget.Toast.LENGTH_SHORT).show();
                io.execute(()->{
                    if(watchedMode){
                        com.meedx.providers.TraktSyncClient.syncHistoryItem(getApplicationContext(), isSeries, ti.imdbId(), ti.tmdbId(), Math.max(1,ti.season()), Math.max(1,ti.episode()), false);
                    } else {
                        com.meedx.providers.TraktSyncClient.syncWatchlistItem(getApplicationContext(), isSeries, ti.imdbId(), ti.tmdbId(), false);
                    }
                    try { CinemaWatchStore.syncWithTrakt(getApplicationContext()); } catch (Exception ignored) {}
                    handler.post(()->{ if(!isDestroyed()) load(); });
                });
            }
        }).setNegativeButton(android.R.string.cancel,null).show();
    }
    private void actions(Video video){
        CinemaWatchStore.TraktItem ti = traktEntries.get(video.getFilePath());
        if(ti!=null){
            openTraktItemMenu(video, ti);
            return;
        }
        String removeLabel=watchedMode?label(this,"إزالة من تمت المشاهدة","Mark as Unwatched"):label(this,"إزالة من قائمة المشاهدة","Remove from Watchlist");
        CinemaUi.dialog(this).setTitle(video.getName()).setItems(layoutCheck?new String[]{"Open details"}:new String[]{label(this,"فتح التفاصيل","Open details"),removeLabel},(d,index)->{
            if(index==0)open(video);
            else if(!layoutCheck){
                if(watchedMode){
                    io.execute(()->{var season=watchedSeasons.get(video.getFilePath());
                        if(season!=null)com.archos.mediacenter.video.utils.DbUtils.markAsNotRead(getApplicationContext(),
                                new com.archos.mediacenter.video.browser.adapters.object.Season(season.showId(),video.getName(),
                                        video.getPosterUri(),season.number(),0,0));
                        else com.archos.mediacenter.video.utils.DbUtils.markAsNotRead(getApplicationContext(),video);
                        handler.post(this::load);});
                }else{
                    if(contains(this,video))toggle(this,video);load();
                }
            }
        }).setNegativeButton(android.R.string.cancel,null).show();
    }
    private void prefetchTraktArt(Video video){
        String path = video.getFilePath();
        CinemaWatchStore.TraktItem ti = traktEntries.get(path);
        if(ti==null || artwork.containsKey(path)) return;
        artIo.submit(()->{
            try{
                boolean isShow = "show".equals(ti.mediaType()) || "episode".equals(ti.mediaType()) || ti.season()>0;
                CinemaArtwork.Art fetched = null;
                if(ti.tmdbId()>0){
                    fetched = CinemaArtwork.load(getApplicationContext(), ti.tmdbId(), isShow, MetadataLanguage.resolve(this));
                }
                if(fetched==null || fetched.poster()==null){
                    var meta = com.meedx.providers.OpenMetadata.lookup(getApplicationContext(),
                            new com.meedx.providers.OpenMetadata.Query(ti.title(), ti.year(), isShow),
                            ti.imdbId(), "", Math.max(0,ti.season()), Math.max(0,ti.episode()),
                            MetadataLanguage.resolve(this));
                    if(meta!=null && ((meta.poster()!=null && !meta.poster().isBlank()) || (meta.backdrop()!=null && !meta.backdrop().isBlank()))){
                        fetched = new CinemaArtwork.Art(
                                meta.logo(),
                                meta.poster(),
                                meta.backdrop(),
                                meta.plot() == null ? "" : meta.plot(),
                                meta.genres() == null ? "" : meta.genres(),
                                java.util.List.of(),
                                null,
                                0.0,
                                0,
                                ti.title(),
                                null,
                                ti.year(),
                                0,
                                null,
                                ti.imdbId());
                    }
                }
                CinemaArtwork.Art finalArt = fetched;
                if(finalArt==null) return;
                handler.post(()->{
                    if(isDestroyed()) return;
                    artwork.put(path, finalArt);
                    if(finalArt.poster()!=null && !finalArt.poster().isBlank()){
                        posters.put(path, Uri.parse(finalArt.poster()));
                        for(int i=0;i<videos.size();i++)if(videos.get(i).getFilePath().equals(path))adapter.notifyItemChanged(i,"artwork");
                    }
                    if(path.equals(selectedPath) && finalArt.backdrop()!=null && !finalArt.backdrop().isBlank()){
                        Picasso.get().load(finalArt.backdrop()).resize(1280,720).centerCrop().into(backdrop);
                    }
                });
            }catch(Exception ignored){}
        });
    }
    private void select(Video video){
        selectedPath=video.getFilePath();var art=artwork.get(selectedPath);Uri image=art!=null&&art.backdrop()!=null?Uri.parse(art.backdrop()):null;
        Picasso.get().cancelRequest(backdrop);if(image!=null)Picasso.get().load(image).resize(1280,720).centerCrop().into(backdrop);else backdrop.setImageDrawable(null);
        if(pendingArt!=null)handler.removeCallbacks(pendingArt);
        if(art!=null)return;
        if(traktEntries.containsKey(selectedPath)){prefetchTraktArt(video);return;}
        if(artworkTask!=null)artworkTask.cancel(true);
        String path=selectedPath;pendingArt=()->{if(isDestroyed()||!path.equals(selectedPath))return;artworkTask=artIo.submit(()->{try{CinemaArtwork.Art fetched;
            if(video instanceof Movie film)fetched=CinemaArtwork.load(getApplicationContext(),film.getOnlineId(),false,MetadataLanguage.resolve(this));
            else if(video instanceof Episode){var tags=video.getFullScraperTags(getApplicationContext());if(!(tags instanceof com.archos.mediascraper.EpisodeTags ep))return;var show=com.archos.mediascraper.TagsFactory.buildShowTags(getApplicationContext(),ep.getShowId());if(show==null)return;fetched=CinemaArtwork.load(getApplicationContext(),show.getOnlineId(),true,MetadataLanguage.resolve(this));}
            else return;handler.post(()->{if(isDestroyed()||fetched==null)return;artwork.put(path,fetched);if(fetched.poster()!=null){posters.put(path,Uri.parse(fetched.poster()));for(int i=0;i<videos.size();i++)if(videos.get(i).getFilePath().equals(path))adapter.notifyItemChanged(i,"artwork");}if(path.equals(selectedPath)&&fetched.backdrop()!=null)Picasso.get().load(fetched.backdrop()).resize(1280,720).centerCrop().into(backdrop);});}catch(Exception ignored){/* Cached library poster remains usable offline. */}});};handler.postDelayed(pendingArt,CinemaTokens.FOCUS_MS);
    }
    private final class Card extends RecyclerView.ViewHolder {
        final FrameLayout poster;final ImageView image;final TextView title;final View progress;
        Card(LinearLayout body,FrameLayout poster,ImageView image,TextView title,View progress){super(body);this.poster=poster;this.image=image;this.title=title;this.progress=progress;}
    }
    private final class Posters extends RecyclerView.Adapter<Card>{
        Posters(){setHasStableIds(true);}
        @Override public long getItemId(int p){return loading?Long.MIN_VALUE+p:videos.get(p).getId();}
        @Override public int getItemCount(){return loading?columns:videos.size();}
        @Override public Card onCreateViewHolder(ViewGroup parent,int type){
            LinearLayout body=new LinearLayout(CinemaWatchlistActivity.this);body.setOrientation(LinearLayout.VERTICAL);body.setClipChildren(false);body.setClipToPadding(false);focusable(body);body.setBackgroundColor(Color.TRANSPARENT);body.setLayoutParams(new RecyclerView.LayoutParams(-1,-2));
            FrameLayout poster=new FrameLayout(CinemaWatchlistActivity.this);poster.setBackground(rounded(CinemaTokens.PLAYER_PANEL,dp(CinemaWatchlistActivity.this,CinemaTokens.POSTER_RADIUS),0));poster.setClipToOutline(true);body.addView(poster,new LinearLayout.LayoutParams(-1,posterHeight));
            ImageView image=new ImageView(CinemaWatchlistActivity.this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);poster.addView(image,new FrameLayout.LayoutParams(-1,-1));
            View progress=new View(CinemaWatchlistActivity.this);progress.setBackgroundColor(CinemaTokens.GOLD);poster.addView(progress,new FrameLayout.LayoutParams(0,dp(CinemaWatchlistActivity.this,3),Gravity.BOTTOM));
            TextView title=text(CinemaWatchlistActivity.this,CinemaTokens.LABEL,false);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);title.setGravity(Gravity.START|Gravity.TOP);title.setPadding(0,dp(CinemaWatchlistActivity.this,8),0,0);body.addView(title,new LinearLayout.LayoutParams(-1,Math.max(dp(CinemaWatchlistActivity.this,48),title.getLineHeight()*2+dp(CinemaWatchlistActivity.this,12))));
            return new Card(body,poster,image,title,progress);
        }
        @Override public void onBindViewHolder(Card h,int p){
            Picasso.get().cancelRequest(h.image);h.image.setImageDrawable(null);h.itemView.animate().cancel();h.itemView.setScaleX(1);h.itemView.setScaleY(1);h.poster.setForeground(null);
            h.itemView.setFocusable(!loading);h.itemView.setClickable(!loading);h.title.setTextColor(h.itemView.hasFocus()?CinemaTokens.FOCUS:CinemaTokens.TEXT);h.title.setText(loading?"":displayName(videos.get(p)));h.progress.getLayoutParams().width=0;
            if(loading){h.itemView.setContentDescription(h.itemView.getContext().getString(com.archos.mediacenter.video.R.string.cinema_watchlist_loading));return;}
            Video video=videos.get(p);h.itemView.setContentDescription(displayName(video));
            Uri cachedPoster=CinemaArtwork.getCachedPoster(CinemaWatchlistActivity.this,"v:"+video.getId(),video.getPosterUri());
            Uri poster=posters.getOrDefault(video.getFilePath(),cachedPoster);
            if(poster!=null)Picasso.get().load(poster).resize(posterWidth,posterHeight).centerCrop().into(h.image);
            long duration=CinemaMediaTime.known(video.getId(),video.getFilePath(),video.getDurationMs()),resume=CinemaMediaTime.knownResume(video.getId(),video.getFilePath(),video.getResumeMs());if(duration>0&&resume>0)h.progress.getLayoutParams().width=(int)Math.min(posterWidth,(long)posterWidth*resume/duration);
            h.progress.requestLayout();h.itemView.setOnClickListener(v->open(video));h.itemView.setOnLongClickListener(v->{actions(video);return true;});
            h.itemView.setOnFocusChangeListener((v,focused)->{h.poster.setForeground(focused?focusRing():null);animatePosterFocus(h.poster,focused);h.title.setTextColor(focused?CinemaTokens.FOCUS:CinemaTokens.TEXT);if(focused)select(video);});
            h.itemView.setOnKeyListener((v,key,event)->{if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;int position=h.getBindingAdapterPosition();if(position<0)return false;if(key==KeyEvent.KEYCODE_MENU){actions(video);return true;}if(key==KeyEvent.KEYCODE_DPAD_UP&&position<columns)return syncBtn!=null&&syncBtn.requestFocus();boolean rtl=arabic(CinemaWatchlistActivity.this);int enterRail=rtl?KeyEvent.KEYCODE_DPAD_RIGHT:KeyEvent.KEYCODE_DPAD_LEFT;int outerEdge=rtl?KeyEvent.KEYCODE_DPAD_LEFT:KeyEvent.KEYCODE_DPAD_RIGHT;if(key==enterRail&&position%columns==0){ArrayList<View> targets=new ArrayList<>();root.findViewsWithText(targets,label(CinemaWatchlistActivity.this,"الإعدادات","Settings"),View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);if(!targets.isEmpty())targets.get(0).requestFocus();return true;}if(key==outerEdge&&(position%columns==columns-1||position==videos.size()-1))return true;return false;});
            h.poster.setForeground(h.itemView.hasFocus()?focusRing():null);animatePosterFocus(h.poster,h.itemView.hasFocus());
        }
        @Override public void onBindViewHolder(Card h,int p,List<Object> payloads){if(!payloads.isEmpty()&&!loading){Uri uri=posters.get(videos.get(p).getFilePath());if(uri!=null)Picasso.get().load(uri).resize(posterWidth,posterHeight).centerCrop().into(h.image);}else super.onBindViewHolder(h,p,payloads);}
        @Override public void onViewRecycled(Card h){Picasso.get().cancelRequest(h.image);h.itemView.animate().cancel();super.onViewRecycled(h);}
    }
    private void refreshFromTraktAndReload(){
        if(syncBtn!=null)syncBtn.setEnabled(false);
        android.widget.Toast.makeText(this,label(this,"جارٍ التحديث والمزامنة...","Refreshing & syncing..."),android.widget.Toast.LENGTH_SHORT).show();
        io.execute(()->{
            String msg;
            try{
                if(com.meedx.providers.TraktSyncClient.isConnected(getApplicationContext())){
                    int countSynced=CinemaWatchStore.syncWithTrakt(getApplicationContext());
                    msg=label(this,"تم التحديث (مزامنة: ","Refreshed (synced: ")+countSynced+")";
                }else{
                    CinemaLinkServer.broadcastWatchSync(getApplicationContext());
                    msg=label(this,"تم تحديث القائمة","List refreshed");
                }
            }catch(Exception e){
                msg=label(this,"تعذّرت مزامنة Trakt: ","Trakt sync failed: ")+e.getMessage();
            }
            String finalMsg=msg;
            handler.post(()->{
                if(isDestroyed())return;
                if(syncBtn!=null)syncBtn.setEnabled(true);
                android.widget.Toast.makeText(this,finalMsg,android.widget.Toast.LENGTH_SHORT).show();
                load();
            });
        });
    }

    @Override public void onSaveInstanceState(Bundle state){state.putString("selected_path",selectedPath);super.onSaveInstanceState(state);}
    @Override public void onDestroy(){try{unregisterReceiver(syncReceiver);}catch(Exception ignored){}generation++;handler.removeCallbacksAndMessages(null);Picasso.get().cancelRequest(backdrop);if(artworkTask!=null)artworkTask.cancel(true);artIo.shutdownNow();io.shutdownNow();super.onDestroy();}
}

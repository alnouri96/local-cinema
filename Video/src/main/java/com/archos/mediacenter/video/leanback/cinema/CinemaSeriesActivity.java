package com.archos.mediacenter.video.leanback.cinema;

import com.meedx.providers.OpenMetadata;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.*;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import com.archos.mediacenter.video.browser.adapters.object.*;
import com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper;
import com.archos.mediacenter.video.browser.loader.EpisodesLoader;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import com.archos.mediacenter.video.leanback.details.VideoDetailsFragment;
import com.archos.mediacenter.video.leanback.tvshow.TvshowFragment;
import com.archos.mediacenter.video.player.PlayerActivity;
import com.archos.mediacenter.video.player.PlayerService;
import com.archos.mediacenter.video.utils.DbUtils;
import com.archos.mediacenter.video.utils.PlayUtils;
import com.squareup.picasso.Picasso;
import java.util.*;
import java.util.concurrent.*;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

/** Native cinematic series/seasons browser backed by NOVA's actual episode catalogue. */
public final class CinemaSeriesActivity extends LeanbackActivity implements LoaderManager.LoaderCallbacks<Cursor> {
    private Tvshow show;private ImageView backdrop,logo,seasonCardImage;
    private TextView title,plot,watch,seasonHeading,seasonsSectionTitle,trailerButton,restartChip,listChip,seenChip,subsChip,moreChip;
    private android.net.Uri trailer;
    private LinearLayout seasons,infoRow,ratingsRow,episodes,seasonPostersRow,actionsRow,cast;
    private ScrollView mainScroll;
    private HorizontalScrollView episodeScroll,seasonPostersScroll;
    private final TreeMap<Integer,List<Episode>> data=new TreeMap<>();
    private final ExecutorService io=Executors.newFixedThreadPool(2);private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private long onlineId;private int imageGeneration;private final Map<Integer,ImageView> episodeImages=new HashMap<>();
    private final Map<Integer,ImageView> seasonPosterImages=new HashMap<>();private final Map<Integer,Uri> seasonStills=new HashMap<>();
    private record SeasonScore(double rating,int votes) {}
    private final Map<Integer,SeasonScore> episodeScores=new HashMap<>();
    private SeasonScore seasonScore;
    private String seasonOverview;
    private Episode focusedSeasonEpisode;
    private Integer openedSeasonFromShow;
    private Integer selectedShowSeason;
    private FrameLayout highlightedSeasonCard,highlightedEpisodeCard;
    private int selectedSeason=-1;private boolean showMode=true;private Episode next;private String selectedEpisode;private boolean initial=true;
    private final Map<Integer,Uri> officialSeasonPosters=new HashMap<>();
    private static final int EPISODE_CARD_WIDTH=268,EPISODE_CARD_HEIGHT=150;
    private static final int SEASON_POSTER_WIDTH=152,SEASON_POSTER_HEIGHT=228;
    private static final int SEASON_MINI_WIDTH=100;
    private String seriesBackdropUrl;private CinemaArtwork.Art seriesArt;
    private OpenMetadata.Item publicMetadata;
    private com.meedx.providers.MultiRatingsProvider.Ratings multiRatings;
    private Double resolvedImdb,resolvedTmdb;private int resolvedImdbVotes,resolvedTmdbVotes;private Integer resolvedCritics,resolvedMetacritic;
    private final Map<Integer,String> episodeOverviews=new HashMap<>();private final Set<Long> probedEpisodes=new HashSet<>();
    private final Map<String,String> episodeMetaTitles=new HashMap<>();
    private final Map<String,Uri> episodeMetaThumbs=new HashMap<>();
    private final Map<String,String> episodeMetaOverviews=new HashMap<>();
    private final Map<String,Double> episodeMetaRatings=new HashMap<>();
    private final Map<String,String> episodeMetaRatingSources=new HashMap<>();
    private final Map<String,Integer> episodeMetaRuntimes=new HashMap<>();
    private final Map<String,Integer> episodeMetaYears=new HashMap<>();
    private final Map<Integer,Integer> seasonYears=new HashMap<>();
    private final Map<Long,List<String>> episodeTechCache=new HashMap<>();
    private String enrichedImdbId;
    private boolean seenBusy;
    private final Map<Integer,Uri> seasonTrailers=new HashMap<>();
    private final java.util.Set<Integer> seasonTrailerAttempted=new java.util.HashSet<>();
    private String launchedEpisode;
    private String returningEpisode;
    private boolean rebuildingEpisodes;
    private boolean suppressTabFocusSwitch=false;

    private String seasonTitleText(int season){
        return season==0?label(this,"حلقات خاصة","Specials"):label(this,"الموسم "+season,"Season "+season);
    }

    private boolean rtl;
    @Override public void onCreate(Bundle state){super.onCreate(state);show=(Tvshow)getIntent().getSerializableExtra(TvshowFragment.EXTRA_TVSHOW);if(show==null){finish();return;}
        rtl=CinemaUi.arabic(this);
        if(state!=null){selectedSeason=state.getInt("season",-1);selectedEpisode=state.getString("episode");launchedEpisode=state.getString("launchedEpisode");returningEpisode=state.getString("returningEpisode");showMode=state.getBoolean("showMode",true);if(state.containsKey("show_season"))selectedShowSeason=state.getInt("show_season");}
        else if(getIntent().hasExtra("open_season")){selectedSeason=getIntent().getIntExtra("open_season",-1);showMode=false;}
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(CinemaTokens.CANVAS);root.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);setContentView(root);
        backdrop=new ImageView(this);backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);root.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
        View shade=new View(this);shade.setBackground(new GradientDrawable(rtl?GradientDrawable.Orientation.RIGHT_LEFT:GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xf2171717,0xcc171717,0x66171717}));root.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        View floor=new View(this);floor.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00171717,CinemaTokens.CANVAS}));root.addView(floor,new FrameLayout.LayoutParams(-1,-1));
        ScrollView scroll=new ScrollView(this);mainScroll=scroll;scroll.setClipChildren(false);scroll.setClipToPadding(false);root.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setClipChildren(false);column.setClipToPadding(false);column.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);column.setGravity(rtl?Gravity.RIGHT:Gravity.LEFT);column.setPaddingRelative(dp(this,CinemaTokens.GUTTER),dp(this,10),dp(this,48),dp(this,24));scroll.addView(column);

        HorizontalScrollView seasonScroll=new HorizontalScrollView(this);seasonScroll.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);seasonScroll.setFillViewport(true);seasonScroll.setHorizontalScrollBarEnabled(false);seasonScroll.setClipChildren(false);seasonScroll.setClipToPadding(false);seasonScroll.setPadding(0,dp(this,4),0,dp(this,4));column.addView(seasonScroll,new LinearLayout.LayoutParams(-1,-2));
        seasons=new LinearLayout(this);seasons.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);seasons.setGravity(Gravity.CENTER_VERTICAL|(rtl?Gravity.RIGHT:Gravity.LEFT));seasons.setClipChildren(false);seasons.setClipToPadding(false);
        seasons.setPadding(dp(this,4),dp(this,3),dp(this,4),dp(this,3));
        GradientDrawable pillBg=rounded(0x28000000,dp(this,20),0);pillBg.setStroke(dp(this,1),0x22FFFFFF);
        seasons.setBackground(pillBg);
        seasonScroll.addView(seasons);

        FrameLayout identity=new FrameLayout(this);identity.setClipChildren(false);identity.setClipToPadding(false);LinearLayout.LayoutParams identityParams=new LinearLayout.LayoutParams(dp(this,500),-2);identityParams.topMargin=dp(this,4);identityParams.bottomMargin=dp(this,2);column.addView(identity,identityParams);
        title=text(this,CinemaTokens.FILM_TITLE,true);title.setMaxLines(2);title.setTextSize(28);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setMinimumHeight(dp(this,38));title.setText(show.getName());title.setGravity(Gravity.CENTER_VERTICAL|(rtl?Gravity.RIGHT:Gravity.LEFT));identity.addView(title,new FrameLayout.LayoutParams(-1,-2));
        logo=new ImageView(this);logo.setAdjustViewBounds(true);logo.setMaxWidth(dp(this,320));logo.setMaxHeight(dp(this,50));logo.setScaleType(ImageView.ScaleType.FIT_CENTER);logo.setVisibility(View.GONE);identity.addView(logo,new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|(rtl?Gravity.RIGHT:Gravity.LEFT)));

        seasonHeading=text(this,CinemaTokens.ROW_TITLE,true);seasonHeading.setSingleLine(true);seasonHeading.setEllipsize(android.text.TextUtils.TruncateAt.END);seasonHeading.setGravity(rtl?Gravity.RIGHT:Gravity.LEFT);seasonHeading.setPadding(0,dp(this,2),0,0);CinemaUi.addFixedSlot(column,seasonHeading,-1,dp(this,26));

        infoRow=new LinearLayout(this);infoRow.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);infoRow.setOrientation(LinearLayout.HORIZONTAL);infoRow.setGravity(Gravity.CENTER_VERTICAL|(rtl?Gravity.RIGHT:Gravity.LEFT));infoRow.setMinimumHeight(dp(this,22));infoRow.setPadding(0,dp(this,1),0,dp(this,1));infoRow.setVisibility(View.INVISIBLE);CinemaUi.addFixedSlot(column,infoRow,-1,dp(this,26));
        ratingsRow=new LinearLayout(this);ratingsRow.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);ratingsRow.setOrientation(LinearLayout.HORIZONTAL);ratingsRow.setGravity(Gravity.CENTER_VERTICAL|(rtl?Gravity.RIGHT:Gravity.LEFT));ratingsRow.setMinimumHeight(dp(this,22));ratingsRow.setPadding(0,0,0,dp(this,2));ratingsRow.setVisibility(View.INVISIBLE);CinemaUi.addFixedSlot(column,ratingsRow,-1,dp(this,24));

        plot=text(this,CinemaTokens.LABEL,false);plot.setText(show.getPlot());plot.setMaxLines(2);plot.setGravity(rtl?Gravity.RIGHT:Gravity.LEFT);plot.setLineSpacing(dp(this,2),1);plot.setEllipsize(android.text.TextUtils.TruncateAt.END);CinemaUi.addFixedSlot(column,plot,dp(this,520),dp(this,40));

        episodeScroll=new HorizontalScrollView(this);episodeScroll.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);episodeScroll.setFillViewport(true);episodeScroll.setHorizontalScrollBarEnabled(false);episodeScroll.setClipChildren(false);episodeScroll.setClipToPadding(false);episodeScroll.setPaddingRelative(dp(this,10),dp(this,12),dp(this,16),dp(this,12));LinearLayout.LayoutParams episodeParams=new LinearLayout.LayoutParams(-1,-2);episodeParams.topMargin=dp(this,2);column.addView(episodeScroll,episodeParams);episodes=new LinearLayout(this);episodes.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);episodes.setGravity(Gravity.CENTER_VERTICAL|(rtl?Gravity.RIGHT:Gravity.LEFT));episodes.setClipChildren(false);episodes.setClipToPadding(false);episodeScroll.addView(episodes);

        LinearLayout actions=new LinearLayout(this);actionsRow=actions;actions.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);actions.setOrientation(LinearLayout.HORIZONTAL);actions.setGravity(Gravity.CENTER_VERTICAL|(rtl?Gravity.RIGHT:Gravity.LEFT));actions.setClipChildren(false);actions.setClipToPadding(false);actions.setPaddingRelative(dp(this,2),dp(this,4),0,dp(this,4));LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-1,-2);actionParams.topMargin=dp(this,4);
        watch=CinemaActionChip.create(this,actions,label(this,"جاري قراءة الحلقات…","Reading episodes…"),com.archos.mediacenter.video.R.drawable.cinema_play_movie,()->{
            if(showMode){Episode e=currentEpisode();if(e!=null)playSeason(e.getSeasonNumber());}
            else if(focusedSeasonEpisode==null)playSeason(selectedSeason);
            else playEpisode(focusedSeasonEpisode,false,false);
        });
        watch.setEnabled(false);
        restartChip=CinemaActionChip.create(this,actions,this.getString(com.archos.mediacenter.video.R.string.cinema_restart),com.archos.mediacenter.video.R.drawable.cinema_restart,()->{Episode e=currentEpisode();if((showMode&&selectedShowSeason!=null)||(!showMode&&focusedSeasonEpisode==null)){List<Episode> season=actionEpisodes();if(!season.isEmpty())playEpisode(season.get(0),true,true);}else if(e!=null)playEpisode(e,true,showMode);});
        listChip=CinemaActionChip.create(this,actions,this.getString(com.archos.mediacenter.video.R.string.cinema_add_to_watchlist),com.archos.mediacenter.video.R.drawable.cinema_watchlist,()->{CinemaWatchlistActivity.toggleEpisodes(this,actionEpisodes());refreshActions();});
        trailerButton=CinemaActionChip.create(this,actions,this.getString(com.archos.mediacenter.video.R.string.cinema_watch_series_trailer),com.archos.mediacenter.video.R.drawable.cinema_trailer,this::launchSeriesTrailer);
        trailerButton.setVisibility(View.VISIBLE);
        CinemaActionChip.create(this,actions,this.getString(com.archos.mediacenter.video.R.string.cinema_audio_tracks),com.archos.mediacenter.video.R.drawable.cinema_ic_audio,()->CinemaPreferredAudio.choose(this,()->{}));
        seenChip=CinemaActionChip.create(this,actions,this.getString(com.archos.mediacenter.video.R.string.cinema_mark_as_watched),
                com.archos.mediacenter.video.R.drawable.cinema_check,this::toggleWatched);
        subsChip=CinemaActionChip.create(this,actions,this.getString(com.archos.mediacenter.video.R.string.cinema_subtitles_cinemaseriesactivity),com.archos.mediacenter.video.R.drawable.cinema_subtitles,()->{});
        subsChip.setOnClickListener(v->subtitleMenu(subsChip));
        CinemaActionChip.create(this,actions,this.getString(com.archos.mediacenter.video.R.string.cinema_pref_languages_title),com.archos.mediacenter.video.R.drawable.cinema_globe,()->CinemaLanguagePreferences.showDialog(this,null));
        moreChip=CinemaActionChip.create(this,actions,this.getString(com.archos.mediacenter.video.R.string.cinema_more),com.archos.mediacenter.video.R.drawable.cinema_more,()->{});
        moreChip.setOnClickListener(v->moreMenu(moreChip));
        for(int n=0;n<actions.getChildCount();n++){
            View chip=actions.getChildAt(n);
            chip.setOnKeyListener((v,key,event)->{
                if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
                key=logicalDirKey(key);
                if(key==KeyEvent.KEYCODE_DPAD_UP){
                    if(showMode)focusSelectedSeasonCard();
                    else focusCurrentEpisodeCard();
                    return true;
                }
                if(key==KeyEvent.KEYCODE_DPAD_LEFT){
                    int cur=actions.indexOfChild(v);
                    for(int i=cur-1;i>=0;i--){
                        View prev=actions.getChildAt(i);
                        if(prev.getVisibility()==View.VISIBLE&&prev.isFocusable()){prev.requestFocus();break;}
                    }
                    return true;
                }
                if(key==KeyEvent.KEYCODE_DPAD_RIGHT){
                    int cur=actions.indexOfChild(v);
                    for(int i=cur+1;i<actions.getChildCount();i++){
                        View nxt=actions.getChildAt(i);
                        if(nxt.getVisibility()==View.VISIBLE&&nxt.isFocusable()){nxt.requestFocus();break;}
                    }
                    return true;
                }
                if(key==KeyEvent.KEYCODE_DPAD_DOWN){
                    if(cast.getChildCount()>0){cast.getChildAt(0).requestFocus();return true;}
                }
                return false;
            });
        }

        LinearLayout.LayoutParams seasonsGap=new LinearLayout.LayoutParams(-1,-2);seasonsGap.topMargin=dp(this,4);
        seasonsSectionTitle=text(this,CinemaTokens.ROW_TITLE,true);seasonsSectionTitle.setGravity(rtl?Gravity.RIGHT:Gravity.LEFT);seasonsSectionTitle.setVisibility(View.GONE);column.addView(seasonsSectionTitle,seasonsGap);
        seasonPostersScroll=new HorizontalScrollView(this);seasonPostersScroll.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);seasonPostersScroll.setFillViewport(true);seasonPostersScroll.setHorizontalScrollBarEnabled(false);seasonPostersScroll.setClipChildren(false);seasonPostersScroll.setClipToPadding(false);seasonPostersScroll.setPaddingRelative(dp(this,10),dp(this,12),dp(this,16),dp(this,14));seasonPostersScroll.setVisibility(View.GONE);seasonPostersScroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);column.addView(seasonPostersScroll,new LinearLayout.LayoutParams(-1,-2));
        seasonPostersRow=new LinearLayout(this);seasonPostersRow.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);seasonPostersRow.setOrientation(LinearLayout.HORIZONTAL);seasonPostersRow.setGravity(Gravity.CENTER_VERTICAL|(rtl?Gravity.RIGHT:Gravity.LEFT));seasonPostersRow.setClipChildren(false);seasonPostersRow.setClipToPadding(false);seasonPostersRow.setVisibility(View.GONE);seasonPostersScroll.addView(seasonPostersRow);
        column.addView(actions,actionParams);

        LinearLayout.LayoutParams castGap=new LinearLayout.LayoutParams(-1,-2);castGap.topMargin=dp(this,14);
        TextView castTitle=text(this,CinemaTokens.ROW_TITLE,true);castTitle.setGravity(rtl?Gravity.RIGHT:Gravity.LEFT);castTitle.setText(this.getString(com.archos.mediacenter.video.R.string.cinema_cast_crew));column.addView(castTitle,castGap);
        HorizontalScrollView castScroll=new HorizontalScrollView(this);castScroll.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);castScroll.setFillViewport(true);castScroll.setHorizontalScrollBarEnabled(false);castScroll.setClipChildren(false);castScroll.setClipToPadding(false);castScroll.setPaddingRelative(dp(this,8),dp(this,10),dp(this,16),dp(this,12));column.addView(castScroll,new LinearLayout.LayoutParams(-1,-2));
        cast=new LinearLayout(this);cast.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);cast.setOrientation(LinearLayout.HORIZONTAL);cast.setGravity(rtl?Gravity.RIGHT:Gravity.LEFT);cast.setClipChildren(false);cast.setClipToPadding(false);castScroll.addView(cast);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus,newFocus)->updateCardHighlights(newFocus));
        settingsNav=null;refreshShowMetadata();LoaderManager.getInstance(this).initLoader(1,null,this);loadArtwork();registerLibraryRefresh();
    }

    private void openEpisodeDetails(int season, Episode episode) {
        if (episode == null) return;
        selectedEpisode = Long.toString(episode.getId());
        List<Episode> seasonList = data.getOrDefault(season, List.of());
        int idx = 0;
        for (int i = 0; i < seasonList.size(); i++) {
            if (seasonList.get(i).getId() == episode.getId()) { idx = i; break; }
        }
        String showTitle = publicMetadata != null && !publicMetadata.title().isBlank() ? publicMetadata.title()
                : (seriesArt != null && seriesArt.title() != null && !seriesArt.title().isBlank() ? seriesArt.title() : show.getName());
        Intent intent = new Intent(this, CinemaDetailsActivity.class);
        intent.putExtra(VideoDetailsFragment.EXTRA_VIDEO, episode);
        intent.putExtra(CinemaDetailsActivity.EXTRA_SEASON_EPISODES, new ArrayList<>(seasonList));
        intent.putExtra(CinemaDetailsActivity.EXTRA_EPISODE_INDEX, idx);
        intent.putExtra(CinemaDetailsActivity.EXTRA_SHOW_TITLE, showTitle);
        String imdb = enrichedImdbId != null && !enrichedImdbId.isBlank() ? enrichedImdbId
                : (publicMetadata != null ? publicMetadata.imdb() : null);
        if (imdb != null && !imdb.isBlank()) intent.putExtra(CinemaDetailsActivity.EXTRA_SHOW_IMDB, imdb);
        startActivityForResult(intent, 412);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        super.onActivityResult(requestCode, resultCode, resultData);
        if (requestCode == 914 && resultCode == RESULT_OK && resultData != null && resultData.getData() != null) {
            Episode ep = currentEpisode();
            Uri subUri = resultData.getData();
            if (ep != null && ep.getFileUri() != null) {
                io.execute(() -> {
                    try (SubtitleStorage storage = new SubtitleStorage(this)) {
                        storage.bind(ep.getFileUri(), subUri);
                        handler.post(() -> {
                            if (!isDestroyed()) Toast.makeText(this,
                                    getString(com.archos.mediacenter.video.R.string.cinema_subtitle_choice_saved_for_playback),
                                    Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception ignored) {}
                });
            }
            return;
        }
        if (requestCode != 412 && requestCode != 415) return;
        returningEpisode = requestCode == 415 ? launchedEpisode : null;
        launchedEpisode = null;
        if (resultCode == RESULT_OK && resultData != null) {
            long epId = resultData.getLongExtra("current_episode_id", -1L);
            if (epId > 0) returningEpisode = Long.toString(epId);
        }
        if (returningEpisode != null) {
            selectedEpisode = returningEpisode;
            // Restore against the cards already on screen. The loader refreshes their progress.
            if (!showMode) focusCurrentEpisodeCard();
        }
        Loader<Cursor> loader = LoaderManager.getInstance(this).getLoader(1);
        if (loader != null) loader.onContentChanged();
    }

    private void playEpisode(Episode e,boolean fromBeginning,boolean binge){
        if(e==null)return;
        if (CinemaAddonPicker.requiresSources(e)) {
            CinemaAddonPicker.show(this, io, handler, e, publicMetadata, e.getSeasonNumber(), e.getEpisodeNumber());
            return;
        }
        selectedEpisode=Long.toString(e.getId());
        launchedEpisode=selectedEpisode;
        PlayerService.requestBingePlayMode(binge);
        long r=fromBeginning?0:episodeResume(e);
        List<Episode> seasonList=data.getOrDefault(e.getSeasonNumber(),List.of());
        String showTitle=publicMetadata!=null&&!publicMetadata.title().isBlank()?publicMetadata.title()
                :(seriesArt!=null&&seriesArt.title()!=null&&!seriesArt.title().isBlank()?seriesArt.title():show.getName());
        PlayUtils.startEpisodeForResult(this,e,seasonList,showTitle,fromBeginning||r<=0?PlayerActivity.RESUME_NO:PlayerActivity.RESUME_FROM_LAST_POS,false,fromBeginning?0:(int)Math.max(0,r),-1,415,enrichedImdbId != null ? enrichedImdbId : (publicMetadata != null ? publicMetadata.imdb() : null));
    }

    private void playSeason(int season){
        List<Episode> list=data.getOrDefault(season,List.of());
        if(list.isEmpty())return;
        Episode start=null;
        for(Episode ep:list)if(episodeResume(ep)>0){start=ep;break;}
        if(start==null)for(Episode ep:list)if(!ep.isWatched()&&episodeResume(ep)!=PlayerActivity.LAST_POSITION_END){start=ep;break;}
        if(start==null)start=list.get(0);
        selectedEpisode=Long.toString(start.getId());
        playEpisode(start,false,true);
    }

    private void subtitleMenu(View anchor){
        Episode episode=currentEpisode();
        if(episode==null)return;
        CinemaActionMenu.show(anchor,
                new CinemaActionMenu.Action(this.getString(com.archos.mediacenter.video.R.string.cinema_choose_from_episode_folder),()->{
                    Intent intent=new Intent(this,CinemaFileBrowserActivity.class);
                    intent.putExtra(CinemaFileBrowserActivity.EXTRA_FILTER_MODE, CinemaFileBrowserActivity.FILTER_SUBTITLES_ONLY);
                    startActivityForResult(intent, 914);
                }),
                new CinemaActionMenu.Action(this.getString(com.archos.mediacenter.video.R.string.cinema_pref_languages_title),
                        () -> CinemaLanguagePreferences.showDialog(this, null)),
                new CinemaActionMenu.Action(SubtitleSources.label(this), () -> SubtitleSources.search(this, episode)),
                new CinemaActionMenu.Action(SubtitleSources.title(this), () -> SubtitleSources.settings(this)));
    }

    private void moreMenu(View anchor){
        Episode episode=currentEpisode();
        CinemaActionMenu.Action addonStreams=new CinemaActionMenu.Action(
                label(this,"مصادر الإضافات (Nuvio / Stremio)","Addon Streams (Nuvio / Stremio)"),
                ()->CinemaAddonPicker.show(this,io,handler,episode!=null?episode:show,publicMetadata,
                        episode!=null?episode.getSeasonNumber():selectedSeason,
                        episode!=null?episode.getEpisodeNumber():1));
        CinemaActionMenu.Action seriesDetails=new CinemaActionMenu.Action(
                this.getString(com.archos.mediacenter.video.R.string.cinema_more_details_and_advanced_actions),()->{
                    Intent intent=new Intent(this,com.archos.mediacenter.video.leanback.tvshow.TvshowActivity.class);
                    intent.putExtra(TvshowFragment.EXTRA_TVSHOW,show);
                    intent.putExtra("cinema_legacy_series",true);
                    startActivity(intent);
                });
        if(episode==null){
            CinemaActionMenu.show(anchor,addonStreams,seriesDetails);
            return;
        }
        CinemaActionMenu.show(anchor,addonStreams,seriesDetails,new CinemaActionMenu.Action(
                this.getString(com.archos.mediacenter.video.R.string.cinema_episode_details_tracks),()->{
                    Intent intent=new Intent(this,CinemaDetailsActivity.class);
                    intent.putExtra(VideoDetailsFragment.EXTRA_VIDEO,episode);
                    startActivity(intent);
                }));
    }

    private static final class FolderSeriesEpisodesLoader extends com.archos.mediacenter.video.browser.loader.VideoLoader {
        private final String folderPrefix;
        private final List<String> titleTokens;
        private final long showId;

        FolderSeriesEpisodesLoader(android.content.Context context, Uri fileUri, String seriesName, long showId) {
            super(context);
            this.showId = showId;
            String rawPath = fileUri != null ? fileUri.toString() : null;
            this.folderPrefix = CinemaPosters.seriesRootPath(rawPath);
            List<String> tokens = new ArrayList<>();
            if (seriesName != null) {
                for (String w : seriesName.replaceAll("[^\\p{L}\\p{N}]+", " ").trim().split("\\s+")) {
                    if (w.length() >= 2) tokens.add(w);
                }
            }
            this.titleTokens = tokens;
            init();
        }

        private static String esc(String s) {
            return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        }

        @Override public String getSelection() {
            String common = super.getSelection();
            StringBuilder filter = new StringBuilder();
            if (folderPrefix != null || showId > 0) {
                String data = com.archos.mediaprovider.video.VideoStore.MediaColumns.DATA;
                filter.append("(");
                if (folderPrefix != null) {
                    filter.append(data).append(" LIKE ? ESCAPE '!' OR ").append(data).append(" LIKE ? ESCAPE '!'");
                }
                if (folderPrefix != null && showId > 0) filter.append(" OR ");
                if (showId > 0) {
                    filter.append(com.archos.mediaprovider.video.VideoStore.Video.VideoColumns.SCRAPER_SHOW_ID)
                          .append("=").append(showId);
                }
                filter.append(")");
            } else if (!titleTokens.isEmpty()) {
                filter.append("(");
                for (int i = 0; i < titleTokens.size(); i++) {
                    if (i > 0) filter.append(" AND ");
                    filter.append(com.archos.mediaprovider.video.VideoStore.MediaColumns.DATA)
                          .append(" LIKE ? ESCAPE '!'");
                }
                filter.append(")");
            } else {
                filter.append("1=0");
            }
            return common.isEmpty() ? filter.toString() : "(" + common + ") AND " + filter;
        }

        @Override public String[] getSelectionArgs() {
            if (folderPrefix != null) {
                return new String[]{ esc(folderPrefix) + "%", esc(Uri.decode(folderPrefix)) + "%" };
            }
            if (folderPrefix == null && showId <= 0 && !titleTokens.isEmpty()) {
                String[] args = new String[titleTokens.size()];
                for (int i = 0; i < titleTokens.size(); i++) args[i] = "%" + esc(titleTokens.get(i)) + "%";
                return args;
            }
            return new String[0];
        }

        @Override public String getSortOrder() {
            return com.archos.mediaprovider.video.VideoStore.MediaColumns.DATA + " ASC LIMIT 400";
        }
    }

    @Override public Loader<Cursor> onCreateLoader(int id,Bundle args){
        return new FolderSeriesEpisodesLoader(this,show.getFileUri(),CinemaPosters.query(show,true).title(),show.getTvshowId());
    }
    @Override public void onLoadFinished(Loader<Cursor> loader,Cursor cursor){
        View old=getCurrentFocus();String seasonFocus=old!=null&&old.getParent()==seasons&&old.getTag()!=null?old.getTag().toString():null;
        boolean episodeFocus=old!=null&&old.getParent()==episodes,actionFocus=old!=null&&old.getParent()==actionsRow;
        boolean castFocus=old!=null&&old.getParent()==cast;
        int castIdx=castFocus?cast.indexOfChild(old):-1;
        Object posterSeason=old!=null&&old.getParent()==seasonPostersRow?old.getTag():null;
        if (!initial && !showMode && (episodeFocus || returningEpisode != null)) {
            int scrollY = mainScroll.getScrollY();
            androidx.core.view.OneShotPreDrawListener.add(mainScroll, () -> {
                if (!showMode) mainScroll.scrollTo(0, scrollY);
            });
        }
        data.clear();next=null;List<Episode> all=new ArrayList<>();Set<String> seenPaths=new HashSet<>();
        String targetSeriesTitle=CinemaPosters.query(show,true).title();
        String normTarget=targetSeriesTitle.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+","");
        if(cursor!=null){
            VideoCursorMapper mapper=new VideoCursorMapper();mapper.bindColumns(cursor);cursor.moveToPosition(-1);
            while(cursor.moveToNext()){
                Object item=mapper.bind(cursor);
                if(item instanceof Episode episode){
                    if(!seenPaths.add(episodePathKey(episode.getFilePath())))continue;
                    data.computeIfAbsent(episode.getSeasonNumber(),key->new ArrayList<>()).add(episode);
                }else if(item instanceof Video v){
                    if(!seenPaths.add(episodePathKey(v.getFilePath())))continue;
                    if(!normTarget.isEmpty()){
                        if(!CinemaHomeFragment.isSeriesOrEpisodeFile(v, v.getFilePath())) continue;
                        String candTitle=CinemaPosters.query(v,true).title().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+","");
                        if(!candTitle.equals(normTarget) && !candTitle.contains(normTarget) && !normTarget.contains(candTitle))continue;
                    }
                    int[] se=CinemaPosters.extractSeasonEpisode(v.getFilePath(),v.getName());
                    int seasonNum=se[0];
                    int episodeNum=se[1];
                    if(episodeNum==1 && data.containsKey(seasonNum)){
                        for(Episode existing:data.get(seasonNum)){
                            if(existing.getEpisodeNumber()==1 && !existing.getFilePath().equals(v.getFilePath())){
                                episodeNum=data.get(seasonNum).size()+1;
                                break;
                            }
                        }
                    }
                    String epLabel=String.format(Locale.US,"S%02dE%02d",seasonNum,episodeNum);
                    Episode synthetic=new Episode(v.getId(),v.getId(),seasonNum,episodeNum,epLabel,0L,0f,null,
                            v.getDescriptionBody(),targetSeriesTitle.isBlank()?show.getName():targetSeriesTitle,
                            v.getFilePath(),v.getPosterUri(),v.getPosterUri(),v.getDurationMs(),v.getResumeMs(),
                            v.is3D()?1:0,v.getGuessedDefinition(),v.isWatched(),v.isTraktLibrary(),v.hasSubs(),
                            v.isUserHidden(),0L,v.getLastPlayed(),-1,-1,v.getCalculatedBestAudioFormat(),
                            v.getCalculatedVideoFormat(),v.getGuessedAudioFormat(),v.getGuessedVideoFormat(),
                            v.getCalculatedBestAudiotrack(),v.getOccurencies(),v.getSize());
                    data.computeIfAbsent(seasonNum,key->new ArrayList<>()).add(synthetic);
                }
            }
        }
        for(var entry:data.entrySet()){
            entry.getValue().sort(Comparator.comparingInt(Episode::getEpisodeNumber).thenComparingLong(Episode::getId));
            all.addAll(entry.getValue());
        }
        for(Episode episode:all)if(episodeResume(episode)>0){next=episode;break;}if(next==null)for(Episode episode:all)if(episodeResume(episode)!=PlayerActivity.LAST_POSITION_END){next=episode;break;}if(next==null&&!all.isEmpty())next=all.get(0);
        Episode returned = null;
        if (returningEpisode != null) {
            for (Episode episode : all) {
                if (returningEpisode.equals(Long.toString(episode.getId()))) {
                    returned = episode;
                    selectedEpisode = returningEpisode;
                    selectedSeason = episode.getSeasonNumber();
                    showMode = false;
                    break;
                }
            }
        }
        if (selectedEpisode == null && next != null) selectedEpisode = Long.toString(next.getId());
        suppressTabFocusSwitch=true;
        try{
            refreshActions();
            seasons.removeAllViews();
            TextView showTab=seasonTab(seasons,this.getString(com.archos.mediacenter.video.R.string.cinema_show),()->{if(!showMode)selectShowMode(false);},()->selectShowMode(true));
            showTab.setTag("show");
            for(int season:data.keySet()){
                TextView tab=seasonTab(seasons,seasonTitleText(season),()->{if(showMode||selectedSeason!=season)selectSeason(season,false);},()->selectSeason(season,true));
                tab.setTag(String.valueOf(season));
            }
            buildSeasonPosterCards();
            if(!data.containsKey(selectedSeason))selectedSeason=next!=null?next.getSeasonNumber():data.isEmpty()?-1:data.firstKey();
            if(showMode){selectShowMode(false);loadOtherSeasonImages();}
            else selectSeason(selectedSeason,false);
            if(publicMetadata!=null&&publicMetadata.imdb()!=null&&!publicMetadata.imdb().isBlank()
                    &&!publicMetadata.imdb().equals(enrichedImdbId)){
                enrichSeriesFromCinemetaAndTmdb(publicMetadata.imdb());
            }
            if (returned != null) {
                initial = false;
                returningEpisode = null;
                focusCurrentEpisodeCard();
            }
            else if(initial){initial=false;if(showMode)focusActiveTopTab();else if(next!=null)watch.requestFocus();}
            else if(actionFocus&&old.isShown()){old.requestFocus();}
            else if(castFocus&&castIdx>=0&&cast.getChildCount()>0){cast.getChildAt(Math.min(castIdx,cast.getChildCount()-1)).requestFocus();}
            else if(episodeFocus&&!showMode){focusCurrentEpisodeCard();}
            else if(posterSeason!=null&&showMode){
                for(int n=0;n<seasonPostersRow.getChildCount();n++){
                    View card=seasonPostersRow.getChildAt(n);
                    if(posterSeason.equals(card.getTag())){card.requestFocus();break;}
                }
            }
            else if(seasonFocus!=null){for(int n=0;n<seasons.getChildCount();n++)if(seasonFocus.equals(seasons.getChildAt(n).getTag())){seasons.getChildAt(n).requestFocus();break;}}
            else if(getCurrentFocus()==null&&next!=null){watch.requestFocus();}
        }finally{
            suppressTabFocusSwitch=false;
        }
    }
    @Override public void onLoaderReset(Loader<Cursor> loader){data.clear();episodes.removeAllViews();seasonPostersRow.removeAllViews();}

    private void styleTopTabs(){
        String activeTag=showMode?"show":String.valueOf(selectedSeason);
        for(int n=0;n<seasons.getChildCount();n++){
            View tab=seasons.getChildAt(n);if(tab.getTag()==null)continue;
            boolean active=activeTag.equals(tab.getTag());
            tab.setSelected(active);
            tab.setForeground(null);
        }
    }

    private static int parseAirYear(String rawDate){
        if(rawDate==null||rawDate.length()<4)return 0;
        String sub=rawDate.trim().substring(0,4);
        if(sub.matches("(?:19|20)[0-9]{2}")){
            try{return Integer.parseInt(sub);}catch(Exception ignored){}
        }
        return 0;
    }

    private int seasonYear(int season){
        Integer sy=seasonYears.get(season);
        if(sy!=null&&sy>0)return sy;
        for(Episode e:data.getOrDefault(season,List.of())){
            Integer ey=episodeMetaYears.get(season+":"+e.getEpisodeNumber());
            if(ey!=null&&ey>0)return ey;
        }
        return seriesYear();
    }

    private int episodeYear(int season,Episode ep){
        if(ep!=null){
            Integer ey=episodeMetaYears.get(season+":"+ep.getEpisodeNumber());
            if(ey!=null&&ey>0)return ey;
        }
        return seasonYear(season);
    }

    private String formatEpisodeCount(long count){
        if(!arabic(this))return count+(count==1?" Episode":" Episodes");
        if(count==1)return "حلقة واحدة";
        if(count==2)return "حلقتان";
        if(count>=3&&count<=10)return count+" حلقات";
        return count+" حلقة";
    }

    private int seriesYear(){
        int year=seriesArt!=null&&seriesArt.year()>0?seriesArt.year():show.getYear();
        if (publicMetadata != null && publicMetadata.year() != null && !publicMetadata.year().isBlank()) {
            try { year = Integer.parseInt(publicMetadata.year().replaceAll("[^0-9].*$", "")); } catch (Exception ignored) {}
        }
        return year;
    }

    private int seriesRuntimeMin(){
        int rtMin=seriesArt!=null?seriesArt.runtime():0;
        if(rtMin<=0){
            Episode e=currentEpisode();
            long d=episodeDuration(e);
            if(d>0)rtMin=(int)Math.round(d/60000.0);
        }
        return rtMin;
    }

    private String seriesGenres(){
        return seriesArt!=null&&seriesArt.genres()!=null&&!seriesArt.genres().isBlank()
                ?seriesArt.genres():(publicMetadata!=null?publicMetadata.genres():null);
    }

    private String seriesCert(){
        String cert=seriesArt!=null?seriesArt.certification():null;
        return (cert==null||cert.isBlank())?"TV-MA":cert;
    }

    private String savedSeriesOverview(){
        if(seriesArt!=null&&seriesArt.overview()!=null&&!seriesArt.overview().isBlank())return seriesArt.overview();
        if(publicMetadata!=null&&publicMetadata.plot()!=null&&!publicMetadata.plot().isBlank())return publicMetadata.plot();
        return show.getPlot();
    }

    private void renderSeriesRatings(Double seasonTmdbOverride){
        Double tmdbScore=(seasonTmdbOverride!=null&&seasonTmdbOverride>0&&seasonTmdbOverride<=10.0)
                ?seasonTmdbOverride:(resolvedTmdb!=null&&resolvedTmdb>0?resolvedTmdb:null);
        String cert=seriesCert(),genres=seriesGenres();
        if (OpenMetadata.enabled(this)) {
            CinemaRatingBadge.renderMulti(this, ratingsRow, multiRatings, publicMetadata,
                    resolvedImdb, tmdbScore, resolvedCritics, resolvedMetacritic);
        } else {
            double tmdbVal=tmdbScore!=null?tmdbScore:0.0;
            CinemaRatingBadge.renderAll(this,ratingsRow,cert,genres,resolvedImdb,resolvedImdbVotes,
                    resolvedCritics,tmdbVal,resolvedTmdbVotes);
        }
        ratingsRow.setVisibility(ratingsRow.getChildCount()>0?View.VISIBLE:View.INVISIBLE);
    }

    private void refreshShowMetadata(){
        if(!showMode){
            refreshSeasonSummary();
            return;
        }
        int year=seriesYear(),rtMin=seriesRuntimeMin();
        String runtime=rtMin>0?CinemaMediaTime.format(rtMin*60000L):null;
        CinemaRatingBadge.renderInfoLine(this,infoRow,year,runtime,seriesGenres(),seriesCert());
        infoRow.setVisibility(infoRow.getChildCount()>0?View.VISIBLE:View.INVISIBLE);
        renderSeriesRatings(null);
    }

    private void buildSeasonPosterCards(){
        seasonPostersRow.removeAllViews();
        highlightedSeasonCard=null;
        seasonPosterImages.clear();
        seasonsSectionTitle.setText(this.getString(com.archos.mediacenter.video.R.string.cinema_seasons));
        for(int season:data.keySet()){
            String seasonTitle=seasonTitleText(season);
            LinearLayout wrapper=new LinearLayout(this);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.setGravity(Gravity.CENTER_HORIZONTAL);
            wrapper.setClipChildren(false);
            wrapper.setClipToPadding(false);
            wrapper.setFocusable(true);
            wrapper.setClickable(true);
            LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(dp(this,SEASON_POSTER_WIDTH),-2);
            wp.setMarginEnd(dp(this,18));
            seasonPostersRow.addView(wrapper,wp);

            FrameLayout card=new FrameLayout(this);
            card.setClipToOutline(true);
            GradientDrawable fallbackBg=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xff2a2b32,0xff18191e});
            fallbackBg.setCornerRadius(dp(this,10));
            fallbackBg.setStroke(dp(this,1),0x33ffffff);
            card.setBackground(fallbackBg);
            wrapper.addView(card,new LinearLayout.LayoutParams(dp(this,SEASON_POSTER_WIDTH),dp(this,SEASON_POSTER_HEIGHT)));

            TextView placeholder=text(this,22,true);
            placeholder.setText(season==0?"S0":"S"+season);
            placeholder.setTextColor(0x66E5B94C);
            placeholder.setGravity(Gravity.CENTER);
            card.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));

            ImageView img=new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img,new FrameLayout.LayoutParams(-1,-1));
            seasonPosterImages.put(season,img);
            bindSeasonImage(season,img);
            long count=data.get(season).stream().map(Episode::getEpisodeNumber).distinct().count();
            TextView badge=episodeCountBadge(this);
            badge.setText(Long.toString(count));
            card.addView(badge,new FrameLayout.LayoutParams(-2,dp(this,20),Gravity.TOP|Gravity.RIGHT));

            TextView cap=text(this,14,true);
            cap.setText(seasonTitle);
            cap.setSingleLine(true);
            cap.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cap.setGravity(Gravity.CENTER);
            cap.setPadding(dp(this,2),dp(this,12),dp(this,2),dp(this,4));
            wrapper.addView(cap,new LinearLayout.LayoutParams(-1,-2));

            wrapper.setOnFocusChangeListener((v,focused)->{
                cap.setTextColor(focused?CinemaTokens.GOLD:CinemaTokens.TEXT);
                if(focused){
                    selectedShowSeason=season;
                    highlightedSeasonCard=highlightCard(highlightedSeasonCard,card);
                    refreshActions();
                } else {
                    updateCardHighlights(getCurrentFocus());
                }
            });
            wrapper.setTag(season);
            if(Integer.valueOf(season).equals(selectedShowSeason))highlightedSeasonCard=highlightCard(highlightedSeasonCard,card);
            wrapper.setOnClickListener(v->{openedSeasonFromShow=season;selectSeason(season,true);});
            wrapper.setOnKeyListener((v,key,event)->{
                if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
                key=logicalDirKey(key);
                if(key==KeyEvent.KEYCODE_DPAD_UP){focusActiveTopTab();return true;}
                if(key==KeyEvent.KEYCODE_DPAD_RIGHT){int i=seasonPostersRow.indexOfChild(v);if(i+1<seasonPostersRow.getChildCount())seasonPostersRow.getChildAt(i+1).requestFocus();return true;}if(key==KeyEvent.KEYCODE_DPAD_LEFT){int i=seasonPostersRow.indexOfChild(v);if(i>0)seasonPostersRow.getChildAt(i-1).requestFocus();return true;}
                if(key==KeyEvent.KEYCODE_DPAD_DOWN){watch.requestFocus();return true;}
                return false;
            });
        }
        if(rtl&&seasonPostersScroll!=null)seasonPostersScroll.post(()->seasonPostersScroll.scrollTo(Math.max(0,seasonPostersRow.getWidth()-seasonPostersScroll.getWidth()),0));
        enforceModeVisibility();
        if(onlineId>0)loadOtherSeasonImages();
    }

    private void enforceModeVisibility(){
        if(!showMode){
            if(seasonsSectionTitle!=null&&seasonsSectionTitle.getVisibility()!=View.GONE)seasonsSectionTitle.setVisibility(View.GONE);
            if(seasonPostersScroll!=null){
                if(seasonPostersScroll.getVisibility()!=View.GONE)seasonPostersScroll.setVisibility(View.GONE);
                seasonPostersScroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
            if(seasonPostersRow!=null&&seasonPostersRow.getVisibility()!=View.GONE)seasonPostersRow.setVisibility(View.GONE);
            if(episodeScroll!=null&&episodeScroll.getVisibility()!=View.VISIBLE)episodeScroll.setVisibility(View.VISIBLE);
        }else{
            if(episodeScroll!=null&&episodeScroll.getVisibility()!=View.GONE)episodeScroll.setVisibility(View.GONE);
            int vis=data.isEmpty()?View.GONE:View.VISIBLE;
            if(seasonsSectionTitle!=null&&seasonsSectionTitle.getVisibility()!=vis)seasonsSectionTitle.setVisibility(vis);
            if(seasonPostersScroll!=null){
                if(seasonPostersScroll.getVisibility()!=vis)seasonPostersScroll.setVisibility(vis);
                seasonPostersScroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
            }
            if(seasonPostersRow!=null&&seasonPostersRow.getVisibility()!=vis)seasonPostersRow.setVisibility(vis);
        }
        if(actionsRow!=null&&actionsRow.getVisibility()!=View.VISIBLE)actionsRow.setVisibility(View.VISIBLE);
    }

    private int logicalDirKey(int key){
        if(!rtl)return key;
        if(key==KeyEvent.KEYCODE_DPAD_LEFT)return KeyEvent.KEYCODE_DPAD_RIGHT;
        if(key==KeyEvent.KEYCODE_DPAD_RIGHT)return KeyEvent.KEYCODE_DPAD_LEFT;
        return key;
    }

    private void selectShowMode(boolean moveFocusToContent){
        boolean prevSuppress=suppressTabFocusSwitch;
        suppressTabFocusSwitch=true;
        try{
            showMode=true;
            openedSeasonFromShow=null;
            focusedSeasonEpisode=null;
            styleTopTabs();
            seasonHeading.setVisibility(View.GONE);
            if(moveFocusToContent){
                int vis=data.isEmpty()?View.GONE:View.VISIBLE;
                if(seasonsSectionTitle!=null)seasonsSectionTitle.setVisibility(vis);
                if(seasonPostersScroll!=null){
                    seasonPostersScroll.setVisibility(vis);
                    seasonPostersScroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
                }
                if(seasonPostersRow!=null)seasonPostersRow.setVisibility(vis);
                focusSelectedSeasonCard();
            }
            enforceModeVisibility();
            refreshShowMetadata();
            String seriesOverview=savedSeriesOverview();
            if(seriesOverview!=null&&!seriesOverview.isBlank())plot.setText(seriesOverview);
            refreshActions();
        }finally{
            suppressTabFocusSwitch=prevSuppress;
        }
    }

    private void selectSeason(int season,boolean focusEpisode){
        boolean prevSuppress=suppressTabFocusSwitch;
        suppressTabFocusSwitch=true;
        try{
            showMode=false;
            selectedSeason=season;episodeImages.clear();episodeOverviews.clear();
            episodeScores.clear();seasonScore=null;seasonOverview=null;focusedSeasonEpisode=null;
            seasonCardImage=null;
            highlightedEpisodeCard=null;
            String seasonTitle=seasonTitleText(season);
            styleTopTabs();
            seasonHeading.setVisibility(View.GONE);
            if(episodeScroll!=null&&episodeScroll.getVisibility()!=View.VISIBLE)episodeScroll.setVisibility(View.VISIBLE);
            seasonHeading.setText(seasonTitle);
            rebuildingEpisodes = true;
            episodes.removeAllViews();

            List<Episode> seasonEpisodes=data.getOrDefault(season,List.of());
            // First card in row: clean 2:3 mini Season poster card ("All Episodes" continuous playback on click)
            if(!seasonEpisodes.isEmpty()){
                FrameLayout sCard=new FrameLayout(this);sCard.setFocusable(true);sCard.setClickable(true);sCard.setClipToOutline(true);sCard.setBackground(rounded(CinemaTokens.SURFACE,dp(this,8),0));sCard.setTag("season_card");
                LinearLayout.LayoutParams sSize=new LinearLayout.LayoutParams(dp(this,SEASON_MINI_WIDTH),dp(this,EPISODE_CARD_HEIGHT));sSize.setMarginEnd(dp(this,16));episodes.addView(sCard,sSize);
                seasonCardImage=new ImageView(this);seasonCardImage.setScaleType(ImageView.ScaleType.CENTER_CROP);sCard.addView(seasonCardImage,new FrameLayout.LayoutParams(-1,-1));
                bindSeasonImage(season,seasonCardImage);
                TextView sBadge=text(this,11,true);sBadge.setText(seasonTitle);sBadge.setSingleLine(true);sBadge.setGravity(Gravity.CENTER);sBadge.setPadding(dp(this,7),dp(this,3),dp(this,7),dp(this,3));sBadge.setBackground(rounded(0xCC121212,dp(this,6),0));
                FrameLayout.LayoutParams sbParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);sbParams.setMargins(0,dp(this,8),dp(this,8),0);sCard.addView(sBadge,sbParams);
                sCard.setContentDescription(seasonTitle+label(this," · جميع الحلقات"," · All Episodes"));
                highlightedEpisodeCard=highlightCard(highlightedEpisodeCard,sCard);
                sCard.setOnFocusChangeListener((v,focused)->{
                    if(focused && !rebuildingEpisodes){
                        highlightedEpisodeCard=highlightCard(highlightedEpisodeCard,sCard);
                        focusedSeasonEpisode=null;
                        selectedEpisode=null;
                        refreshSeasonSummary();
                        refreshActions();
                    }
                });
                sCard.setOnClickListener(v->playSeason(season));
                sCard.setOnKeyListener((v,key,event)->{
                    if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
                    key=logicalDirKey(key);
                    if(key==KeyEvent.KEYCODE_DPAD_DOWN){watch.requestFocus();return true;}
                    if(key==KeyEvent.KEYCODE_DPAD_RIGHT){int i=episodes.indexOfChild(v);if(i+1<episodes.getChildCount())episodes.getChildAt(i+1).requestFocus();return true;}if(key==KeyEvent.KEYCODE_DPAD_LEFT){int i=episodes.indexOfChild(v);if(i>0)episodes.getChildAt(i-1).requestFocus();return true;}
                    if(key==KeyEvent.KEYCODE_DPAD_UP){focusActiveTopTab();return true;}
                    return false;
                });
            }
            View restore=null,inProgressCard=null,firstEpisodeCard=null;
            Episode inProgressEp=null,firstEp=null;
            for(Episode episode:seasonEpisodes){
                FrameLayout card=new FrameLayout(this);card.setFocusable(true);card.setClickable(true);card.setClipToOutline(true);card.setBackground(rounded(CinemaTokens.SURFACE,dp(this,8),0));LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(dp(this,EPISODE_CARD_WIDTH),dp(this,EPISODE_CARD_HEIGHT));size.setMarginEnd(dp(this,16));episodes.addView(card,size);
                if(firstEpisodeCard==null){firstEpisodeCard=card;firstEp=episode;}
                ImageView image=new ImageView(this);episodeImages.put(episode.getEpisodeNumber(),image);image.setScaleType(ImageView.ScaleType.CENTER_CROP);card.addView(image,new FrameLayout.LayoutParams(-1,-1));
                Uri epPic=episode.getPictureUri()!=null?episode.getPictureUri():episodeMetaThumbs.get(season+":"+episode.getEpisodeNumber());
                int epDecodeW = Math.min(dp(this, EPISODE_CARD_WIDTH), 480);
                int epDecodeH = Math.min(dp(this, EPISODE_CARD_HEIGHT), 270);
                if(epPic!=null)Picasso.get().load(epPic).resize(epDecodeW,epDecodeH).centerCrop().into(image);
                else if(seriesBackdropUrl!=null)Picasso.get().load(Uri.parse(seriesBackdropUrl)).resize(epDecodeW,epDecodeH).centerCrop().into(image);
                String displayName=resolveEpisodeDisplayTitle(season,episode);
                TextView badge=text(this,12,true);badge.setText("E"+episode.getEpisodeNumber());badge.setSingleLine(true);badge.setGravity(Gravity.CENTER);badge.setPadding(dp(this,8),dp(this,3),dp(this,8),dp(this,3));badge.setBackground(rounded(0xCC121212,dp(this,6),0));FrameLayout.LayoutParams badgeParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);badgeParams.setMargins(0,dp(this,8),dp(this,8),0);card.addView(badge,badgeParams);
                long epRes=episodeResume(episode),epDur=episodeDuration(episode);
                boolean epWatched=episode.isWatched()||epRes==PlayerActivity.LAST_POSITION_END;
                if(epRes>0&&!epWatched&&inProgressCard==null){inProgressCard=card;inProgressEp=episode;}
                if(epWatched){
                    TextView check=text(this,12,true);check.setText("✓");check.setTextColor(0xff101113);check.setGravity(Gravity.CENTER);check.setBackground(rounded(CinemaTokens.GOLD,dp(this,11),0));
                    FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(this,22),dp(this,22),Gravity.TOP|Gravity.START);cp.setMargins(dp(this,8),dp(this,8),0,0);card.addView(check,cp);
                }else if(epRes>0&&epDur>0){
                    int w=(int)Math.min(dp(this,EPISODE_CARD_WIDTH),(long)dp(this,EPISODE_CARD_WIDTH)*epRes/epDur);
                    View prog=new View(this);prog.setBackgroundColor(CinemaTokens.GOLD);card.addView(prog,new FrameLayout.LayoutParams(w,dp(this,3),Gravity.BOTTOM));
                }
                card.setContentDescription(displayName);card.setTag(Long.toString(episode.getId()));
                if(Long.toString(episode.getId()).equals(selectedEpisode)){
                    restore=card;
                    focusedSeasonEpisode=episode;
                    highlightedEpisodeCard=highlightCard(highlightedEpisodeCard,card);
                }
                card.setOnFocusChangeListener((v,focused)->{
                    if(focused && !rebuildingEpisodes){
                        highlightedEpisodeCard=highlightCard(highlightedEpisodeCard,card);
                        focusedSeasonEpisode=episode;
                        selectedEpisode=Long.toString(episode.getId());
                        refreshSeasonSummary();
                        refreshActions();
                    }
                });
                card.setOnClickListener(v->playEpisode(episode,false,false));
                card.setLongClickable(true);
                card.setOnLongClickListener(v->{episodeContextMenu(episode);return true;});
                card.setOnKeyListener((v,key,event)->{
                    if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
                    key=logicalDirKey(key);
                    if(key==KeyEvent.KEYCODE_MENU){episodeContextMenu(episode);return true;}
                    if(key==KeyEvent.KEYCODE_DPAD_DOWN){watch.requestFocus();return true;}
                    if(key==KeyEvent.KEYCODE_DPAD_RIGHT){int i=episodes.indexOfChild(v);if(i+1<episodes.getChildCount())episodes.getChildAt(i+1).requestFocus();return true;}if(key==KeyEvent.KEYCODE_DPAD_LEFT){int i=episodes.indexOfChild(v);if(i>0)episodes.getChildAt(i-1).requestFocus();return true;}
                    if(key==KeyEvent.KEYCODE_DPAD_UP){focusActiveTopTab();return true;}
                    return false;
                });
            }
            View targetFocus=restore!=null?restore:(inProgressCard!=null?inProgressCard:firstEpisodeCard);
            if(targetFocus==null&&episodes.getChildCount()>0)targetFocus=episodes.getChildAt(0);
            if(focusEpisode&&restore==null){
                Episode chosen=inProgressEp!=null?inProgressEp:firstEp;
                if(chosen!=null){
                    focusedSeasonEpisode=chosen;
                    selectedEpisode=Long.toString(chosen.getId());
                    if(targetFocus instanceof FrameLayout fc)highlightedEpisodeCard=highlightCard(highlightedEpisodeCard,fc);
                }
            }
            rebuildingEpisodes = false;
            if(focusEpisode&&targetFocus!=null){
                targetFocus.requestFocus();
            }
            enforceModeVisibility();
            loadSeasonImages();
            refreshSeasonSummary();
            refreshActions();
            final View finalTarget=targetFocus;
            final View finalFirstEpCard=firstEpisodeCard;
            if(episodeScroll!=null){
                episodeScroll.post(()->{
                    if(isDestroyed()||episodeScroll==null)return;
                    if(finalTarget!=null&&focusEpisode){
                        if(!showMode&&selectedSeason==season){
                            View cur=getCurrentFocus();
                            if(cur==null||cur==mainScroll||cur.getParent()==seasons)finalTarget.requestFocus();
                        }
                        if(finalTarget==finalFirstEpCard){
                            if(rtl)episodeScroll.scrollTo(Math.max(0,episodes.getWidth()-episodeScroll.getWidth()),0);
                            else episodeScroll.scrollTo(0,0);
                        }else{
                            episodeScroll.requestChildFocus(episodes,finalTarget);
                        }
                    }
                });
            }
        }finally{
            rebuildingEpisodes = false;
            suppressTabFocusSwitch=prevSuppress;
        }
    }

    private String resolveEpisodeDisplayTitle(int season, Episode ep) {
        if (ep == null) return "";
        String key = season + ":" + ep.getEpisodeNumber();
        String metaEpTitle = episodeMetaTitles.get(key);
        if (metaEpTitle != null && !metaEpTitle.isBlank()) return metaEpTitle.trim();
        String raw = ep.getEpisodeName();
        if (raw != null && !raw.isBlank() && !raw.trim().matches("(?i)S\\d+E\\d+")) {
            return raw.trim();
        }
        return label(this, "الحلقة " + ep.getEpisodeNumber(), "Episode " + ep.getEpisodeNumber());
    }

    private String formatEpisodeHeading(int season, Episode ep) {
        String prefix = label(this,
                "الموسم " + season + " · الحلقة " + ep.getEpisodeNumber(),
                "Season " + season + " · E" + ep.getEpisodeNumber());
        String key = season + ":" + ep.getEpisodeNumber();
        String titlePart = episodeMetaTitles.get(key);
        if (titlePart == null || titlePart.isBlank()) {
            String raw = ep.getEpisodeName();
            if (raw != null && !raw.isBlank() && !raw.trim().matches("(?i)S\\d+E\\d+")
                    && !raw.trim().matches("(?i)E\\d+")) {
                titlePart = raw.trim();
            }
        }
        return (titlePart != null && !titlePart.isBlank()) ? prefix + " — " + titlePart.trim() : prefix;
    }

    private void bindSeasonImage(int season,ImageView image){
        if(image==null)return;
        boolean gridPoster=seasonPosterImages.containsValue(image);
        int targetW=gridPoster?SEASON_POSTER_WIDTH:SEASON_MINI_WIDTH;
        int targetH=gridPoster?SEASON_POSTER_HEIGHT:EPISODE_CARD_HEIGHT;
        List<Uri> candidates=new ArrayList<>();
        Uri officialPoster=officialSeasonPosters.get(season);
        if(officialPoster!=null)candidates.add(officialPoster);
        if(seriesArt!=null&&seriesArt.poster()!=null&&!seriesArt.poster().isBlank()){
            Uri sp=Uri.parse(seriesArt.poster());
            if(!candidates.contains(sp))candidates.add(sp);
        }
        if(show!=null&&show.getPosterUri()!=null&&!candidates.contains(show.getPosterUri())){
            candidates.add(show.getPosterUri());
        }
        Uri still=seasonStills.get(season);
        if(still!=null)candidates.add(still);
        for(Episode episode:data.getOrDefault(season,List.of())){
            Uri picture=episode.getPictureUri()!=null?episode.getPictureUri():episodeMetaThumbs.get(season+":"+episode.getEpisodeNumber());
            if(picture!=null&&!candidates.contains(picture))candidates.add(picture);
        }
        if(seriesBackdropUrl!=null)candidates.add(Uri.parse(seriesBackdropUrl));
        image.setTag(candidates);
        Picasso.get().cancelRequest(image);
        image.setImageDrawable(null);
        loadSeasonImageCandidate(image,candidates,0,targetW,targetH);
    }

    private void loadSeasonImageCandidate(ImageView image,List<Uri> candidates,int index,int targetW,int targetH){
        if(index>=candidates.size())return;
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        boolean portrait = targetH >= targetW;
        int decW = Math.min(dp(this, targetW), portrait ? 300 : 480);
        int decH = Math.min(dp(this, targetH), portrait ? 450 : 270);
        Picasso.get().load(candidates.get(index))
                .resize(decW,decH).centerCrop()
                .into(image,new com.squareup.picasso.Callback(){
                    @Override public void onSuccess(){}
                    @Override public void onError(Exception error){
                        if(!isDestroyed()&&image.getTag()==candidates)
                            loadSeasonImageCandidate(image,candidates,index+1,targetW,targetH);
                    }
                });
    }

    private void renderEpisodeRatings(Episode ep){
        ratingsRow.removeAllViews();
        String key=selectedSeason+":"+ep.getEpisodeNumber();
        Double epMetaScore=episodeMetaRatings.get(key);
        String epMetaSource=episodeMetaRatingSources.get(key);
        SeasonScore tmdbEp=episodeScores.get(ep.getEpisodeNumber());
        if(epMetaScore!=null&&epMetaScore>0&&"IMDb".equals(epMetaSource)){
            CinemaRatingBadge.addImdb(this,ratingsRow,epMetaScore,0);
        }
        if(tmdbEp!=null&&tmdbEp.rating()>0&&tmdbEp.rating()<=10.0){
            CinemaRatingBadge.addTmdb(this,ratingsRow,tmdbEp.rating(),tmdbEp.votes());
        }
        if(epMetaScore!=null&&epMetaScore>0&&"TVmaze".equals(epMetaSource)){
            CinemaRatingBadge.addTvmaze(this,ratingsRow,epMetaScore);
        }
        if(ratingsRow.getChildCount()==0){
            renderSeriesRatings(seasonScore!=null&&seasonScore.rating()>0?seasonScore.rating():null);
            return;
        }
        ratingsRow.setVisibility(View.VISIBLE);
    }

    private void renderEpisodeInfoRow(Episode ep){
        long durMs=episodeDuration(ep);
        if(durMs<=0){
            Integer metaRt=episodeMetaRuntimes.get(selectedSeason+":"+ep.getEpisodeNumber());
            if(metaRt!=null&&metaRt>0)durMs=metaRt*60000L;
        }
        String runtime=durMs>0?CinemaMediaTime.format(durMs):null;
        CinemaRatingBadge.renderInfoLine(this,infoRow,episodeYear(selectedSeason,ep),runtime,null,seriesCert());
        List<String> specs=episodeTechCache.get(ep.getId());
        if(specs==null){
            specs=extractEpisodeTechSpecs(ep,ep.getMetadata());
            if(!specs.isEmpty())episodeTechCache.put(ep.getId(),specs);
            probeEpisodeSpecsAsync(ep);
        }
        for(String spec:specs){
            CinemaRatingBadge.addPillBox(this,infoRow,spec,0x22FFFFFF,0x55FFFFFF,true);
        }
        infoRow.setVisibility(infoRow.getChildCount()>0?View.VISIBLE:View.INVISIBLE);
    }

    private void probeEpisodeSpecsAsync(Episode ep){
        if(ep==null||!probedEpisodes.add(ep.getId()))return;
        io.execute(()->{
            try{
                com.archos.mediacenter.video.utils.VideoMetadata md=ep.getMetadata();
                if(md==null||md.getVideoTrack()==null){
                    md=com.archos.mediacenter.video.info.VideoInfoCommonClass.retrieveMetadata(ep,getApplicationContext());
                }
                List<String> specs=extractEpisodeTechSpecs(ep,md);
                long probedDur=md!=null&&md.getDuration()>0?md.getDuration():0L;
                handler.post(()->{
                    if(isDestroyed()||isFinishing())return;
                    if(!specs.isEmpty())episodeTechCache.put(ep.getId(),specs);
                    if(probedDur>0&&ep.getFilePath()!=null)CinemaMediaTime.remember(ep.getFilePath(),probedDur);
                    if(!showMode&&focusedSeasonEpisode!=null&&focusedSeasonEpisode.getId()==ep.getId()){
                        renderEpisodeInfoRow(ep);
                    }
                });
            }catch(Exception ignored){}
        });
    }

    private List<String> extractEpisodeTechSpecs(Episode ep,com.archos.mediacenter.video.utils.VideoMetadata md){
        List<String> tags=new ArrayList<>();
        int w=md!=null?md.getVideoWidth():0,h=md!=null?md.getVideoHeight():0;
        String res=null;
        if(w>=3800||h>=2100)res="4K";
        else if(w>=1720||h>=1040)res="1080p";
        else if(w>=1200||h>=720)res="720p";
        else{
            int def=ep.getNormalizedDefinition();
            if(def==com.archos.mediaprovider.video.VideoStore.Video.VideoColumns.ARCHOS_DEFINITION_4K)res="4K";
            else if(def==com.archos.mediaprovider.video.VideoStore.Video.VideoColumns.ARCHOS_DEFINITION_1080P)res="1080p";
            else if(def==com.archos.mediaprovider.video.VideoStore.Video.VideoColumns.ARCHOS_DEFINITION_720P)res="720p";
        }
        String nameUpper=((ep.getFilePath()==null?"":ep.getFilePath())+" "+ep.getName()).toUpperCase(Locale.US);
        if(res==null){
            if(nameUpper.contains("2160P")||nameUpper.contains("4K"))res="4K";
            else if(nameUpper.contains("1080P"))res="1080p";
            else if(nameUpper.contains("720P"))res="720p";
        }
        if(res!=null&&!tags.contains(res))tags.add(res);

        String vfmt=md!=null&&md.getVideoTrack()!=null&&md.getVideoTrack().format!=null
                ?md.getVideoTrack().format:ep.getCalculatedVideoFormat();
        if(vfmt==null||vfmt.isBlank())vfmt=ep.getGuessedVideoFormat();
        boolean hasDv=false;
        String codec=null;
        if(vfmt!=null&&!vfmt.isBlank()){
            String u=vfmt.toUpperCase(Locale.US);
            if(u.contains("DOLBY")||u.contains("DOVI")||u.equals("DV"))hasDv=true;
            else if(u.contains("HEVC")||u.contains("H265")||u.contains("H.265"))codec="HEVC";
            else if(u.contains("AV1"))codec="AV1";
            else if(u.contains("H264")||u.contains("H.264")||u.contains("AVC"))codec="H.264";
            else if(!u.equals("UNKNOWN"))codec=vfmt.trim();
        }
        if(codec==null&&(nameUpper.contains("H 265")||nameUpper.contains("H265")||nameUpper.contains("X265")||nameUpper.contains("HEVC"))){
            codec="HEVC";
        }else if(codec==null&&(nameUpper.contains("H 264")||nameUpper.contains("H264")||nameUpper.contains("X264"))){
            codec="H.264";
        }
        if(codec!=null&&!tags.contains(codec))tags.add(codec);

        if(hasDv||nameUpper.contains("DOVI")||nameUpper.contains("DOLBY VISION")||nameUpper.matches(".*\\bDV\\b.*")){
            if(!tags.contains("Dolby Vision"))tags.add("Dolby Vision");
        }else if(nameUpper.contains("HDR10+")){
            if(!tags.contains("HDR10+"))tags.add("HDR10+");
        }else if(nameUpper.contains("HDR")){
            if(!tags.contains("HDR"))tags.add("HDR");
        }

        String afmt=null,ach=null;
        if(md!=null&&md.getAudioTrackNb()>0){
            int sel=Math.max(0,Math.min(ep.getCalculatedBestAudiotrack(),md.getAudioTrackNb()-1));
            var at=md.getAudioTrack(sel);
            if(at!=null){afmt=at.format;ach=at.channels;}
        }
        if(afmt==null||afmt.isBlank())afmt=ep.getCalculatedBestAudioFormat();
        if(afmt==null||afmt.isBlank())afmt=ep.getGuessedAudioFormat();
        StringBuilder audio=new StringBuilder();
        if(afmt!=null&&!afmt.isBlank()&&!afmt.equalsIgnoreCase("Unknown"))audio.append(afmt.trim().toUpperCase(Locale.US));
        else if(nameUpper.contains("ATMOS"))audio.append("ATMOS");
        else if(nameUpper.contains("DDP")||nameUpper.contains("EAC3"))audio.append("EAC3");
        if(ach!=null&&!ach.isBlank())audio.append(audio.length()>0?" ":"").append(ach.trim());
        else if(audio.length()>0&&nameUpper.contains("5 1"))audio.append(" 5.1");
        if(audio.length()>0&&tags.size()<5){
            String audioStr=audio.toString();
            if(!tags.contains(audioStr))tags.add(audioStr);
        }
        return tags;
    }

    private void refreshSeasonSummary(){
        if(showMode){
            refreshShowMetadata();
            return;
        }
        if(focusedSeasonEpisode==null){
            List<Episode> sEps=data.getOrDefault(selectedSeason,List.of());
            long count=sEps.stream().map(Episode::getEpisodeNumber).distinct().count();
            seasonHeading.setText(seasonTitleText(selectedSeason)+" · "+formatEpisodeCount(count));
            seasonHeading.setVisibility(View.VISIBLE);
            int rtMin=seriesRuntimeMin();
            String runtime=rtMin>0?CinemaMediaTime.format(rtMin*60000L):null;
            CinemaRatingBadge.renderInfoLine(this,infoRow,seasonYear(selectedSeason),runtime,seriesGenres(),seriesCert());
            infoRow.setVisibility(infoRow.getChildCount()>0?View.VISIBLE:View.INVISIBLE);
            renderSeriesRatings(seasonScore!=null&&seasonScore.rating()>0?seasonScore.rating():null);
            String overview=seasonOverview;
            if(overview==null||overview.isBlank())overview=savedSeriesOverview();
            plot.setText(overview==null?"":overview);
            return;
        }
        Episode ep=focusedSeasonEpisode;
        seasonHeading.setText(formatEpisodeHeading(selectedSeason,ep));
        seasonHeading.setVisibility(View.VISIBLE);
        renderEpisodeInfoRow(ep);
        renderEpisodeRatings(ep);
        String overview=episodeOverviews.get(ep.getEpisodeNumber());
        if(overview==null||overview.isBlank())overview=episodeMetaOverviews.get(selectedSeason+":"+ep.getEpisodeNumber());
        if(overview==null||overview.isBlank())overview=ep.getDescriptionBody();
        if(overview==null||overview.isBlank())overview=seasonOverview;
        if(overview==null||overview.isBlank())overview=savedSeriesOverview();
        plot.setText(overview==null?"":overview);
    }

    private void loadSeasonImages(){
        loadSeasonImages(selectedSeason,++imageGeneration);
    }

    private void loadOtherSeasonImages(){
        for(int season:data.keySet())if(!officialSeasonPosters.containsKey(season)&&!seasonStills.containsKey(season))loadSeasonImages(season,-1);
    }

    private void loadSeasonImages(int season,int token){
        if(onlineId<=0||season<0)return;
        List<Integer> indexedEpisodes=new ArrayList<>();
        for(Episode episode:data.getOrDefault(season,List.of()))indexedEpisodes.add(episode.getEpisodeNumber());
        long id=onlineId;
        String language=MetadataLanguage.resolve(this);
        io.execute(()->{
            try{
                org.json.JSONObject json=CinemaMetadataCache.get(getApplicationContext(),"tv/"+id+"/season/"+season,language);
                org.json.JSONObject fallbackEn=null;
                if(!language.startsWith("en")&&json.optString("overview","").isBlank()){
                    try{fallbackEn=CinemaMetadataCache.get(getApplicationContext(),"tv/"+id+"/season/"+season,"en-US");}catch(Exception ignored){}
                }
                final org.json.JSONObject finalFallbackEn=fallbackEn;

                String posterPath=json.optString("poster_path");
                org.json.JSONArray list=json.optJSONArray("episodes");
                org.json.JSONArray enList=finalFallbackEn!=null?finalFallbackEn.optJSONArray("episodes"):null;
                handler.post(()->{
                    if(isDestroyed())return;
                    if(posterPath.startsWith("/"))officialSeasonPosters.put(season,Uri.parse("https://image.tmdb.org/t/p/w500"+posterPath));
                    for(int n=0;list!=null&&n<list.length();n++){
                        org.json.JSONObject entry=list.optJSONObject(n);
                        if(entry==null||!indexedEpisodes.contains(entry.optInt("episode_number",-1)))continue;
                        String path=entry.optString("still_path");
                        if(!path.startsWith("/"))continue;
                        seasonStills.put(season,Uri.parse("https://image.tmdb.org/t/p/w500"+path));
                        break;
                    }
                    bindSeasonImage(season,seasonPosterImages.get(season));
                    if(!showMode&&season==selectedSeason)bindSeasonImage(season,seasonCardImage);
                    enforceModeVisibility();
                    if(showMode||token!=imageGeneration||season!=selectedSeason)return;
                    String sOv=json.optString("overview");
                    if((sOv==null||sOv.isBlank())&&finalFallbackEn!=null)sOv=finalFallbackEn.optString("overview");
                    if(sOv!=null&&!sOv.isBlank())seasonOverview=sOv;
                    int sYear=parseAirYear(json.optString("air_date"));
                    if(sYear>0)seasonYears.put(season,sYear);
                    seasonScore=new SeasonScore(json.optDouble("vote_average",0),0);
                    for(int n=0;list!=null&&n<list.length();n++){
                        org.json.JSONObject entry=list.optJSONObject(n);
                        if(entry==null)continue;
                        int number=entry.optInt("episode_number",-1);
                        int epYr=parseAirYear(entry.optString("air_date"));
                        if(epYr>0){
                            episodeMetaYears.put(season+":"+number,epYr);
                            if(!seasonYears.containsKey(season))seasonYears.put(season,epYr);
                        }
                        String epOv=entry.optString("overview");
                        if((epOv==null||epOv.isBlank())&&enList!=null&&n<enList.length()){
                            org.json.JSONObject enEntry=enList.optJSONObject(n);
                            if(enEntry!=null&&enEntry.optInt("episode_number",-1)==number)epOv=enEntry.optString("overview");
                        }
                        if(epOv!=null&&!epOv.isBlank())episodeOverviews.put(number,epOv);
                        String epName=entry.optString("name");
                        if(epName!=null&&!epName.isBlank())episodeMetaTitles.putIfAbsent(season+":"+number,epName);
                        int epRt=entry.optInt("runtime",0);
                        if(epRt>0)episodeMetaRuntimes.putIfAbsent(season+":"+number,epRt);
                        episodeScores.put(number,new SeasonScore(entry.optDouble("vote_average",0),entry.optInt("vote_count",0)));
                        ImageView image=episodeImages.get(number);
                        String still=entry.optString("still_path");
                        if(image!=null&&still.startsWith("/"))
                            Picasso.get().load("https://image.tmdb.org/t/p/w500"+still).resize(dp(this,EPISODE_CARD_WIDTH),dp(this,EPISODE_CARD_HEIGHT)).centerCrop().into(image);
                    }
                    refreshSeasonSummary();
                });
            }catch(Exception ignored){/* Keep the indexed episode artwork and synopsis. */}
        });
    }

    private Episode currentEpisode(){
        if(showMode){
            if(selectedShowSeason==null)return next;
            List<Episode> season=data.getOrDefault(selectedShowSeason,List.of());
            if(next!=null&&next.getSeasonNumber()==selectedShowSeason)return next;
            return season.isEmpty()?next:season.get(0);
        }
        List<Episode> season=data.getOrDefault(selectedSeason,List.of());
        if(selectedEpisode!=null)for(Episode episode:season)
            if(Long.toString(episode.getId()).equals(selectedEpisode))return episode;
        if(next!=null&&next.getSeasonNumber()==selectedSeason)return next;
        return season.isEmpty()?null:season.get(0);
    }

    private long episodeDuration(Episode e){return e==null?0:CinemaMediaTime.known(e.getId(),e.getFilePath(),e.getDurationMs());}
    private long episodeResume(Episode e){return e==null?0:CinemaMediaTime.knownResume(e.getId(),e.getFilePath(),e.getResumeMs());}

    /** Actions (play, restart, watchlist, watched, subtitles) target the focused episode, like the movie screen. */
    private List<Episode> actionEpisodes(){
        if(!showMode)return data.getOrDefault(selectedSeason,List.of());
        if(selectedShowSeason!=null)return data.getOrDefault(selectedShowSeason,List.of());
        List<Episode> all=new ArrayList<>();
        for(List<Episode> season:data.values())all.addAll(season);
        return all;
    }

    private boolean allWatched(List<Episode> episodes){
        return !episodes.isEmpty()&&episodes.stream().allMatch(e->e.isWatched()||episodeResume(e)==PlayerActivity.LAST_POSITION_END);
    }

    private void episodeContextMenu(Episode episode){
        if(episode==null)return;
        boolean epWatched=episode.isWatched()||episodeResume(episode)==PlayerActivity.LAST_POSITION_END;
        java.util.List<String> labels=new java.util.ArrayList<>();
        labels.add(getString(epWatched?com.archos.mediacenter.video.R.string.cinema_mark_as_unwatched:com.archos.mediacenter.video.R.string.cinema_mark_as_watched_cinemaseriesactivity));
        if(episodeResume(episode)>0)labels.add(getString(com.archos.mediacenter.video.R.string.cinema_remove_from_continue_watching));
        CinemaUi.dialog(this).setTitle(episodeLabel(episode)).setItems(labels.toArray(new String[0]),(d,w)->io.execute(()->{
            try{
                if(w==0){
                    if(epWatched)DbUtils.markAsNotRead(getApplicationContext(),episode);
                    else DbUtils.markAsRead(getApplicationContext(),episode);
                }else{
                    DbUtils.markAsNotRead(getApplicationContext(),episode);
                    CinemaWatchStore.removePlaybackProgressFromTraktAsync(
                            getApplicationContext(), episode.getId(), episode.getFilePath(),
                            show.getName(), enrichedImdbId, 0L,
                            episode.getSeasonNumber(), episode.getEpisodeNumber());
                }
                boolean markedWatched=w==0&&!epWatched;
                CinemaLinkServer.broadcastWatchSync(CinemaWatchStore.buildSyncRecordForVideo(
                        getApplicationContext(),episode,0L,markedWatched,!markedWatched));
                CinemaWatchStore.pushVideoWatchedToTraktAsync(getApplicationContext(),episode,markedWatched);
                CinemaWatchStore.syncWithTraktAsync(getApplicationContext());
            }catch(Exception ignored){}
            handler.post(()->{if(!isDestroyed())LoaderManager.getInstance(this).restartLoader(1,null,this);});
        })).setNegativeButton(android.R.string.cancel,null).show();
    }

    private void toggleWatched(){
        if(seenBusy)return;
        List<Episode> target=List.copyOf(actionEpisodes());
        if(target.isEmpty())return;
        seenBusy=true;
        final boolean restoreSeen=getCurrentFocus()==seenChip||seenChip.hasFocus();
        boolean watched=!allWatched(target);
        io.execute(()->{
            try{
                var updated=new java.util.HashSet<Integer>();
                for(Episode episode:target){
                    int number=episode.getSeasonNumber();
                    if(show.getTvshowId()>0){
                        if(updated.add(number)){
                            var season=new com.archos.mediacenter.video.browser.adapters.object.Season(show.getTvshowId(),
                                    show.getName(),show.getPosterUri(),number,0,0);
                            if(watched)DbUtils.markAsRead(getApplicationContext(),season);
                            else DbUtils.markAsNotRead(getApplicationContext(),season);
                        }
                    }else{
                        if(watched)DbUtils.markAsRead(getApplicationContext(),episode);
                        else DbUtils.markAsNotRead(getApplicationContext(),episode);
                    }
                    CinemaMediaTime.rememberState(episode.getId(),episode.getFileUri(),episodeDuration(episode),
                            watched?PlayerActivity.LAST_POSITION_END:PlayerActivity.LAST_POSITION_UNKNOWN);
                    CinemaLinkServer.broadcastWatchSync(CinemaWatchStore.buildSyncRecordForVideo(
                            getApplicationContext(),episode,0L,watched,!watched));
                    CinemaWatchStore.pushVideoWatchedToTraktAsync(getApplicationContext(),episode,watched);
                }
                CinemaWatchStore.syncWithTraktAsync(getApplicationContext());
            }catch(Exception failure){
                handler.post(()->{if(!isDestroyed())Toast.makeText(this,this.getString(com.archos.mediacenter.video.R.string.cinema_could_not_save_watched_status),Toast.LENGTH_LONG).show();});
            }finally{
                handler.post(()->{
                    if(!isDestroyed()){
                        seenBusy=false;
                        if(restoreSeen)seenChip.requestFocus();
                        LoaderManager.getInstance(this).restartLoader(1,null,this);
                    }
                });
            }
        });
    }

    private void refreshActions(){
        enforceModeVisibility();
        Episode e=currentEpisode();
        watch.setEnabled(e!=null);
        CinemaActionChip.setLabel(watch,e==null?this.getString(com.archos.mediacenter.video.R.string.cinema_no_indexed_episodes):showMode?this.getString(com.archos.mediacenter.video.R.string.cinema_watch_show):focusedSeasonEpisode==null?this.getString(com.archos.mediacenter.video.R.string.cinema_watch_season):episodeLabel(e));
        CinemaActionChip.setIcon(watch,e!=null&&episodeResume(e)>0?com.archos.mediacenter.video.R.drawable.cinema_resume:com.archos.mediacenter.video.R.drawable.cinema_play_movie);
        if(e!=null&&episodeResume(e)>0&&episodeDuration(e)<=0&&e.getFilePath()!=null&&probedEpisodes.add(e.getId())){
            Episode target=e;
            io.execute(()->{
                try{
                    var meta=com.archos.mediacenter.video.info.VideoInfoCommonClass.retrieveMetadata(target,getApplicationContext());
                    if(meta!=null&&meta.getDuration()>0){
                        CinemaMediaTime.rememberState(target.getId(),target.getFileUri(),meta.getDuration(),episodeResume(target));
                        handler.post(()->{if(!isDestroyed()){refreshActions();if(showMode)refreshShowMetadata();}});
                    }
                }catch(Exception ignored){}
            });
        }
        if(restartChip!=null){
            restartChip.setEnabled(e!=null);
            String epCode=e!=null&&!showMode?String.format(Locale.US," S%02d E%02d",e.getSeasonNumber(),e.getEpisodeNumber()):"";
            CinemaActionChip.setLabel(restartChip,this.getString(com.archos.mediacenter.video.R.string.cinema_restart)+epCode);
        }
        List<Episode> target=actionEpisodes();
        boolean inList=!target.isEmpty()&&target.stream().allMatch(episode->CinemaWatchlistActivity.contains(this,episode));
        boolean seen=allWatched(target);
        listChip.setEnabled(e!=null);seenChip.setEnabled(e!=null);
        trailerButton.setVisibility(showMode?View.VISIBLE:View.GONE);
        if(showMode){
            if(selectedShowSeason!=null){
                String seasonTrailerText=label(this,"إعلان الموسم","Season Trailer");
                CinemaActionChip.setLabel(trailerButton,seasonTrailerText);
                prefetchSeasonTrailerAsync(selectedShowSeason);
            }else{
                CinemaActionChip.setLabel(trailerButton,this.getString(com.archos.mediacenter.video.R.string.cinema_watch_series_trailer));
            }
        }
        subsChip.setVisibility((showMode||focusedSeasonEpisode==null)?View.GONE:View.VISIBLE);
        moreChip.setVisibility(showMode?View.GONE:View.VISIBLE);
        subsChip.setEnabled(e!=null);
        CinemaActionChip.setLabel(listChip,inList?this.getString(com.archos.mediacenter.video.R.string.cinema_remove_from_watchlist):this.getString(com.archos.mediacenter.video.R.string.cinema_add_to_watchlist_cinemaseriesactivity));
        CinemaActionChip.setLabel(seenChip,seen?this.getString(com.archos.mediacenter.video.R.string.cinema_mark_as_unwatched):this.getString(com.archos.mediacenter.video.R.string.cinema_mark_as_watched_cinemaseriesactivity));
        CinemaActionChip.setActive(listChip,inList);
        CinemaActionChip.setActive(seenChip,seen);
    }

    private void focusActiveTopTab(){
        if(mainScroll!=null)mainScroll.smoothScrollTo(0,0);
        String target=showMode?"show":String.valueOf(selectedSeason);
        for(int n=0;n<seasons.getChildCount();n++){View t=seasons.getChildAt(n);if(target.equals(t.getTag())){t.requestFocus();return;}}
        if(seasons.getChildCount()>0)seasons.getChildAt(0).requestFocus();
    }
    private FrameLayout highlightCard(FrameLayout previous,FrameLayout selected){
        if(previous!=null&&previous!=selected)CinemaUi.setPosterFocus(previous,false);
        updateCardHighlight(selected,getCurrentFocus());
        return selected;
    }

    private void updateCardHighlights(View focus){
        updateCardHighlight(highlightedSeasonCard,focus);
        updateCardHighlight(highlightedEpisodeCard,focus);
    }

    private void updateCardHighlight(FrameLayout card,View focus){
        if(card==null)return;
        boolean onCard=focus==card||(focus!=null&&focus==card.getParent()&&focus.getParent()==seasonPostersRow);
        boolean onActions=focus!=null&&focus.getParent()==actionsRow;
        CinemaUi.setPosterFocus(card,card.isShown()&&(onCard||onActions));
    }

    private void focusSelectedSeasonCard(){
        for(int n=0;n<seasonPostersRow.getChildCount();n++){
            View card=seasonPostersRow.getChildAt(n);
            if(card.getTag().equals(selectedShowSeason)){card.requestFocus();return;}
        }
        if(seasonPostersRow.getChildCount()>0)seasonPostersRow.getChildAt(0).requestFocus();
        else watch.requestFocus();
    }

    private void focusCurrentEpisodeCard(){
        if(selectedEpisode!=null)for(int n=0;n<episodes.getChildCount();n++){
            View card=episodes.getChildAt(n);
            if(!selectedEpisode.equals(card.getTag())) continue;
            card.requestFocus();
            // HorizontalScrollView restores the focused child's rectangle on its next layout.
            episodeScroll.requestChildFocus(episodes,card);
            return;
        }
        for(int n=0;n<episodes.getChildCount();n++){View c=episodes.getChildAt(n);if(!"season_card".equals(c.getTag())){c.requestFocus();return;}}
        if(episodes.getChildCount()>0)episodes.getChildAt(0).requestFocus();
    }
    private TextView seasonTab(LinearLayout parent,String label,Runnable onFocusRun,Runnable onClickRun){
        TextView tab=text(this,13,true);tab.setText(label);tab.setSingleLine(true);tab.setGravity(Gravity.CENTER);tab.setFocusable(true);tab.setClickable(true);
        tab.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused},{android.R.attr.state_selected},{}},
                new int[]{0xFF141414,0xFFE5B94C,CinemaTokens.TEXT}));
        StateListDrawable bg=new StateListDrawable();int r=dp(this,16);
        GradientDrawable selectedBg=rounded(0x33E5B94C,r,0);
        selectedBg.setStroke(dp(this,2),0xFFE5B94C);
        bg.addState(new int[]{android.R.attr.state_pressed},rounded(CinemaTokens.PRESSED,r,0));
        bg.addState(new int[]{android.R.attr.state_focused},rounded(0xFFE5B94C,r,0));
        bg.addState(new int[]{android.R.attr.state_selected},selectedBg);
        bg.addState(new int[]{},rounded(Color.TRANSPARENT,r,0));
        tab.setBackground(bg);tab.setPadding(dp(this,16),0,dp(this,16),0);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(this,32));p.setMarginEnd(dp(this,4));parent.addView(tab,p);
        tab.setOnClickListener(v->onClickRun.run());
        tab.setOnFocusChangeListener((v,focused)->{
            if(focused && !suppressTabFocusSwitch){
                if(mainScroll!=null)mainScroll.smoothScrollTo(0,0);
                onFocusRun.run();
            }
        });
        tab.setOnKeyListener((v,key,event)->{
            if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
            key=logicalDirKey(key);
            int idx=parent.indexOfChild(tab);
            if(key==KeyEvent.KEYCODE_DPAD_RIGHT){
                if(idx+1<parent.getChildCount())parent.getChildAt(idx+1).requestFocus();
                return true; // Lock end edge inside the top bar
            }
            if(key==KeyEvent.KEYCODE_DPAD_LEFT){
                if(idx>0){parent.getChildAt(idx-1).requestFocus();return true;}
                return false;
            }
            if(key==KeyEvent.KEYCODE_DPAD_DOWN){
                if(showMode)focusSelectedSeasonCard();
                else focusCurrentEpisodeCard();
                return true;
            }
            return false;
        });
        return tab;
    }

    private String episodeLabel(Episode e){
        long resume=episodeResume(e),total=episodeDuration(e);
        String s=String.format(Locale.US,"S%02d E%02d",e.getSeasonNumber(),e.getEpisodeNumber());
        if(resume<=0)return this.getString(com.archos.mediacenter.video.R.string.cinema_watch_cinemaseriesactivity).trim()+" "+s;
        String out=this.getString(com.archos.mediacenter.video.R.string.cinema_resume_cinemaseriesactivity).trim()+" "+s;
        String rem=CinemaMediaTime.formatRemaining(total,resume);
        if(rem!=null)out+="  ·  "+rem+" "+this.getString(com.archos.mediacenter.video.R.string.cinema_left).trim();
        return out;
    }

    private GradientDrawable castRing(){GradientDrawable ring=rounded(Color.TRANSPARENT,dp(this,52),0);ring.setStroke(dp(this,3),CinemaTokens.FOCUS);return ring;}

    private View settingsNav;
    private void actorPhoto(CinemaArtwork.Person person){
        actorPhoto(person, null);
    }
    private void actorPhoto(CinemaArtwork.Person person, String personId){
        String displayName = person.name() == null ? "" : person.name().trim();
        if (displayName.isEmpty()) return;
        String cleanName = OpenMetadata.cleanPersonName(displayName);
        if (cleanName.isEmpty()) cleanName = displayName;
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER_HORIZONTAL);box.setClipChildren(false);box.setClipToPadding(false);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(this,115),-2);p.setMarginEnd(dp(this,14));p.topMargin=dp(this,12);cast.addView(box,p);
        FrameLayout avatarWrap=new FrameLayout(this);
        avatarWrap.setBackground(rounded(0xff292929,dp(this,50),0));
        avatarWrap.setClipToOutline(true);
        TextView fallback=text(this,28,true);
        fallback.setGravity(Gravity.CENTER);
        fallback.setTextColor(CinemaTokens.GOLD);
        fallback.setText(cleanName.substring(0,1).toUpperCase(Locale.ROOT));
        avatarWrap.addView(fallback,new FrameLayout.LayoutParams(-1,-1));
        ImageView photo=new ImageView(this);photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarWrap.addView(photo,new FrameLayout.LayoutParams(-1,-1));
        box.addView(avatarWrap,new FrameLayout.LayoutParams(dp(this,96),dp(this,96)));
        final String[] resolvedPhoto = new String[]{person.image()};
        if(person.image()!=null && !person.image().isBlank()){
            Picasso.get().load(person.image()).resize(dp(this,96),dp(this,96)).centerCrop().into(photo);
        } else {
            OpenMetadata.lookupPersonPhotoAsync(getApplicationContext(), cleanName, url -> {
                if (isDestroyed() || isFinishing() || url == null || url.isBlank()) return;
                resolvedPhoto[0] = url;
                Picasso.get().load(url).resize(dp(this,96),dp(this,96)).centerCrop().into(photo);
            });
        }
        TextView text=text(this,12,false);text.setText(person.role()!=null&&!person.role().isBlank()?cleanName+"\n"+person.role():cleanName);text.setMaxLines(3);text.setGravity(Gravity.CENTER);text.setPadding(0,dp(this,6),0,0);box.addView(text);box.setFocusable(true);box.setClickable(true);box.setContentDescription(cleanName);box.setOnFocusChangeListener((v,focused)->{avatarWrap.setForeground(focused?castRing():null);avatarWrap.animate().scaleX(focused?1.06f:1).scaleY(focused?1.06f:1).setDuration(CinemaTokens.FOCUS_MS).start();});
        final String actorClean = cleanName;
        box.setOnClickListener(v->CinemaActorActivity.open(this,actorClean,person.role(),resolvedPhoto[0],personId));
        box.setOnKeyListener((v,key,event)->{
            if(event.getAction()==KeyEvent.ACTION_DOWN){key=logicalDirKey(key);if(key==KeyEvent.KEYCODE_DPAD_RIGHT){int i=cast.indexOfChild(v);if(i+1<cast.getChildCount())return cast.getChildAt(i+1).requestFocus();return true;}if(key==KeyEvent.KEYCODE_DPAD_LEFT){int i=cast.indexOfChild(v);if(i>0)return cast.getChildAt(i-1).requestFocus();return true;}if(key==KeyEvent.KEYCODE_DPAD_DOWN)return true;}
            if(event.getAction()==KeyEvent.ACTION_DOWN&&key==KeyEvent.KEYCODE_DPAD_UP){
                return watch.requestFocus();
            }
            return false;
        });
    }

    private void loadArtwork(){io.execute(()->{try{
        var tags=show.getFullScraperTags(getApplicationContext());
        if (OpenMetadata.enabled(this)) {
            loadOpenMetadata(tags == null ? null : tags.getImdbId());
            return;
        }
        long id=tags!=null?tags.getOnlineId():0;
        CinemaArtwork.Art art=CinemaArtwork.load(getApplicationContext(),id,true,MetadataLanguage.resolve(this));
        handler.post(()->{
            if(isDestroyed()||art==null)return;
            onlineId=id;seriesArt=art;
            if(showMode)loadOtherSeasonImages();
            else loadSeasonImages();
            if(art.trailer()!=null){trailer=android.net.Uri.parse(art.trailer());refreshActions();}
            title.setText(art.title());
            refreshShowMetadata();
            if(art.overview()!=null&&!art.overview().isBlank()&&showMode)plot.setText(art.overview());
            if(art.backdrop()!=null){
                seriesBackdropUrl=art.backdrop();
                Picasso.get().load(art.backdrop()).resize(1280,720).centerCrop().into(backdrop);
                seasonPosterImages.forEach(this::bindSeasonImage);
                if(!showMode)bindSeasonImage(selectedSeason,seasonCardImage);
            }
            if(art.logo()!=null){Picasso.get().load(art.logo()).resize(dp(this,320),dp(this,50)).centerInside().onlyScaleDown().into(logo,new com.squareup.picasso.Callback(){public void onSuccess(){if(!isDestroyed()){title.setVisibility(View.GONE);logo.setVisibility(View.VISIBLE);}}public void onError(Exception e){if(!isDestroyed()){logo.setVisibility(View.GONE);title.setVisibility(View.VISIBLE);}}});}
            if(!art.cast().isEmpty()){
                View cur=getCurrentFocus();
                int focusedCastIdx=(cur!=null&&cur.getParent()==cast)?cast.indexOfChild(cur):-1;
                suppressTabFocusSwitch=true;
                try{
                    cast.removeAllViews();
                    for(CinemaArtwork.Person person:art.cast())actorPhoto(person);
                    if(focusedCastIdx>=0&&cast.getChildCount()>0){
                        cast.getChildAt(Math.min(focusedCastIdx,cast.getChildCount()-1)).requestFocus();
                    }else if(cur!=null&&cur.isShown()&&!cur.hasFocus()){
                        cur.requestFocus();
                    }
                }finally{suppressTabFocusSwitch=false;}
            }
            if(art.rating()>0){resolvedTmdb=art.rating();resolvedTmdbVotes=art.votes();}
        });
        if(art!=null){
            String imdb=art.imdbId();if((imdb==null||imdb.isBlank())&&tags!=null)imdb=tags.getImdbId();
            CinemaImdbRatings.Rating local=null;CinemaOmdbRatings.Scores remote=null;
            try{local=CinemaImdbRatings.lookup(getApplicationContext(),imdb);}catch(Exception ignored){}
            try{remote=CinemaOmdbRatings.load(getApplicationContext(),imdb);}catch(Exception ignored){}
            CinemaImdbRatings.Rating score=local;CinemaOmdbRatings.Scores extra=remote;
            handler.post(()->{
                if(isDestroyed()||isFinishing())return;
                resolvedImdb=CinemaRatingBadge.imdbValue(score,extra);
                resolvedImdbVotes=score!=null?score.votes():extra==null?0:extra.votes();
                resolvedCritics=extra!=null?extra.critics():null;
                resolvedMetacritic=extra!=null?extra.metacritic():null;
                refreshShowMetadata();
            });
        }
    }catch(Exception ignored){handler.post(()->{if(!isDestroyed())title.setText(show.getName());});}});}

    private void saveSeriesAndEpisodeIdentities(OpenMetadata.Item item, int year) {
        if (item == null || item.imdb() == null || item.imdb().isBlank()) return;
        var showTags = show.getFullScraperTags(getApplicationContext());
        long showOnlineId = showTags != null ? showTags.getOnlineId() : 0L;
        String cleanTitle = !item.title().isBlank() ? item.title() : show.getName();
        CinemaWatchStore.saveForBase(getApplicationContext(), show, item.imdb(), showOnlineId,
                cleanTitle, year, item.poster(), item.backdrop(), item.attribution());
        for (List<Episode> seasonList : data.values()) {
            for (Episode ep : seasonList) {
                if (ep == null) continue;
                String fileUri = ep.getFileUri() != null ? ep.getFileUri().toString() : ep.getFilePath();
                CinemaWatchStore.saveIdentity(getApplicationContext(), "v:" + ep.getId(), fileUri, "episode",
                        item.imdb(), showOnlineId, ep.getSeasonNumber(), ep.getEpisodeNumber(),
                        cleanTitle, year, item.poster(), item.backdrop(), item.attribution());
            }
        }
        handler.post(() -> { if (!isDestroyed()) refreshActions(); });
    }

    private void loadOpenMetadata(String imdb) {
        CinemaPosters.Query query = CinemaPosters.query(show);
        OpenMetadata.Query oq = new OpenMetadata.Query(query.title(), query.year(), query.show());

        OpenMetadata.Item cached = OpenMetadata.getCachedOnly(getApplicationContext(), oq, imdb);
        if (cached != null) {
            int yc = query.year();
            try { if (cached.year() != null && !cached.year().isBlank()) yc = Integer.parseInt(cached.year()); } catch (NumberFormatException ignored) {}
            saveSeriesAndEpisodeIdentities(cached, yc);
            Double knownImdb = "IMDb".equals(cached.ratingSource()) ? cached.score() : null;
            Double knownMal = "MAL".equals(cached.ratingSource()) ? cached.score() : null;
            var cachedMulti = com.meedx.providers.MultiRatingsProvider.getCached(
                    getApplicationContext(), cached.imdb(), true, knownImdb, knownMal);
            applyOpenMetadataToUi(cached, cachedMulti);
            boolean missingTmdbKeyed = cachedMulti != null && cachedMulti.tmdb == null
                    && !com.meedx.providers.ProviderKeys.get(getApplicationContext(), com.meedx.providers.ProviderKeys.TMDB).isBlank();
            if (cachedMulti == null || cachedMulti.tvmaze == null || missingTmdbKeyed) {
                io.execute(() -> fetchMultiRatingsAsync(cached));
            } else {
                io.execute(() -> loadSupplementarySeriesScores(cached.imdb()));
            }
            final int fyc = yc;
            io.execute(() -> resolveSeriesTrailerAsync(cached.title(), fyc, cached.imdb()));
            return;
        }

        OpenMetadata.Item found = OpenMetadata.lookup(getApplicationContext(), oq, imdb, null, 0, 0);
        if (found == null) return;
        int y = query.year();
        try { if (found.year() != null && !found.year().isBlank()) y = Integer.parseInt(found.year()); } catch (NumberFormatException ignored) {}
        saveSeriesAndEpisodeIdentities(found, y);
        Double knownImdb = "IMDb".equals(found.ratingSource()) ? found.score() : null;
        Double knownMal = "MAL".equals(found.ratingSource()) ? found.score() : null;
        var cachedMulti = com.meedx.providers.MultiRatingsProvider.getCached(
                getApplicationContext(), found.imdb(), true, knownImdb, knownMal);
        applyOpenMetadataToUi(found, cachedMulti);
        io.execute(() -> fetchMultiRatingsAsync(found));
        int finalY = y;
        io.execute(() -> resolveSeriesTrailerAsync(found.title(), finalY, found.imdb()));
    }

    private void loadSupplementarySeriesScores(String imdbId) {
        if (imdbId == null || imdbId.isBlank()) return;
        CinemaImdbRatings.Rating local = null;
        CinemaOmdbRatings.Scores remote = null;
        try { local = CinemaImdbRatings.lookup(getApplicationContext(), imdbId); } catch (Exception ignored) {}
        try { remote = CinemaOmdbRatings.load(getApplicationContext(), imdbId); } catch (Exception ignored) {}
        CinemaImdbRatings.Rating finalLocal = local;
        CinemaOmdbRatings.Scores finalRemote = remote;
        handler.post(() -> {
            if (isDestroyed() || isFinishing()) return;
            if (resolvedImdb == null) resolvedImdb = CinemaRatingBadge.imdbValue(finalLocal, finalRemote);
            if (finalLocal != null) resolvedImdbVotes = finalLocal.votes();
            else if (finalRemote != null && finalRemote.votes() > 0) resolvedImdbVotes = finalRemote.votes();
            if (finalRemote != null) {
                if (finalRemote.critics() != null) resolvedCritics = finalRemote.critics();
                if (finalRemote.metacritic() != null) resolvedMetacritic = finalRemote.metacritic();
            }
            refreshShowMetadata();
        });
    }

    private void resolveSeriesTrailerAsync(String titleStr, int year, String imdbId) {
        String ytUrl = com.meedx.providers.OpenTrailersProvider.resolveTrailerUrl(
                getApplicationContext(), titleStr, year, imdbId, true);
        if (ytUrl != null && !ytUrl.isBlank()) {
            handler.post(() -> {
                if (!isDestroyed() && !isFinishing()) {
                    trailer = android.net.Uri.parse(ytUrl);
                    refreshActions();
                }
            });
        }
    }

    private void prefetchSeasonTrailerAsync(int season) {
        if (season < 0 || seasonTrailers.containsKey(season) || !seasonTrailerAttempted.add(season)) return;
        final String lang = MetadataLanguage.resolve(this);
        io.execute(() -> {
            try {
                long tmdbId = ensureSeriesOnlineId();
                if (tmdbId <= 0) return;
                String ytUrl = CinemaArtwork.loadSeasonTrailer(getApplicationContext(), tmdbId, season, lang);
                if (ytUrl != null && !ytUrl.isBlank()) {
                    handler.post(() -> {
                        if (!isDestroyed() && !isFinishing()) seasonTrailers.put(season, Uri.parse(ytUrl));
                    });
                }
            } catch (Throwable ignored) {}
        });
    }

    private long ensureSeriesOnlineId() {
        if (onlineId > 0) return onlineId;
        try {
            var tags = show.getFullScraperTags(getApplicationContext());
            if (tags != null && tags.getOnlineId() > 0) {
                onlineId = tags.getOnlineId();
                return onlineId;
            }
            if (!com.meedx.providers.ProviderKeys.get(getApplicationContext(), com.meedx.providers.ProviderKeys.TMDB).isBlank()) {
                List<CinemaPosters.Choice> found = CinemaPosters.search(getApplicationContext(), CinemaPosters.query(show, true), 0, false);
                if (!found.isEmpty() && found.get(0).id() > 0) onlineId = found.get(0).id();
            }
        } catch (Throwable ignored) {}
        return onlineId;
    }

    private void launchSeriesTrailer() {
        CinemaPosters.Query q = CinemaPosters.query(show);
        String searchTitle = publicMetadata != null && !publicMetadata.title().isBlank() ? publicMetadata.title()
                : (seriesArt != null && seriesArt.title() != null ? seriesArt.title() : q.title());
        int year = q.year();
        try {
            if (publicMetadata != null && !publicMetadata.year().isBlank()) {
                year = Integer.parseInt(publicMetadata.year().replaceAll("[^0-9]", ""));
            }
        } catch (Exception ignored) {}
        final int finalYear = year;
        final String finalTitle = searchTitle;
        if (showMode && selectedShowSeason != null) {
            final int targetSeason = selectedShowSeason;
            Uri cachedSeason = seasonTrailers.get(targetSeason);
            if (cachedSeason != null) {
                CinemaYouTubeLauncher.openUriOrSearch(this, cachedSeason, finalTitle + " " + seasonTitleText(targetSeason), finalYear);
                return;
            }
            final String lang = MetadataLanguage.resolve(this);
            final String imdb = enrichedImdbId != null ? enrichedImdbId : (publicMetadata != null ? publicMetadata.imdb() : null);
            io.execute(() -> {
                String ytUrl = null;
                try {
                    long tmdbId = ensureSeriesOnlineId();
                    if (tmdbId > 0) {
                        ytUrl = CinemaArtwork.loadSeasonTrailer(getApplicationContext(), tmdbId, targetSeason, lang);
                        if (ytUrl != null && !ytUrl.isBlank()) {
                            final String foundSeasonUrl = ytUrl;
                            handler.post(() -> {
                                if (!isDestroyed() && !isFinishing()) seasonTrailers.put(targetSeason, Uri.parse(foundSeasonUrl));
                            });
                        }
                    }
                    if ((ytUrl == null || ytUrl.isBlank()) && trailer != null) {
                        ytUrl = trailer.toString();
                    }
                    if ((ytUrl == null || ytUrl.isBlank()) && tmdbId > 0) {
                        ytUrl = CinemaArtwork.loadSeriesTrailer(getApplicationContext(), tmdbId, lang);
                    }
                    if (ytUrl == null || ytUrl.isBlank()) {
                        ytUrl = com.meedx.providers.OpenTrailersProvider.resolveTrailerUrl(
                                getApplicationContext(), finalTitle, finalYear, imdb, true);
                    }
                } catch (Throwable ignored) {}
                final String finalUrl = ytUrl;
                handler.post(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    if (finalUrl != null && !finalUrl.isBlank()) {
                        CinemaYouTubeLauncher.openUriOrSearch(this, Uri.parse(finalUrl), finalTitle, finalYear);
                    } else {
                        CinemaYouTubeLauncher.openUriOrSearch(this, null, finalTitle, finalYear);
                    }
                });
            });
            return;
        }
        CinemaYouTubeLauncher.openUriOrSearch(this, trailer, finalTitle, finalYear);
    }

    private void applyOpenMetadataToUi(OpenMetadata.Item item,
                                       com.meedx.providers.MultiRatingsProvider.Ratings initialMulti) {
        handler.post(() -> {
            if (isDestroyed() || isFinishing()) return;
            publicMetadata = item;
            if ("IMDb".equals(item.ratingSource())) resolvedImdb = item.score();
            if (initialMulti != null) multiRatings = initialMulti;
            title.setText(item.title());
            if (item.logo() != null && !item.logo().isBlank()) {
                Picasso.get().load(item.logo()).resize(dp(this, 320), dp(this, 50)).centerInside().onlyScaleDown().into(logo, new com.squareup.picasso.Callback() {
                    @Override public void onSuccess() {
                        if (!isDestroyed()) {
                            title.setVisibility(View.GONE);
                            logo.setVisibility(View.VISIBLE);
                        }
                    }
                    @Override public void onError(Exception e) {
                        if (!isDestroyed()) {
                            logo.setVisibility(View.GONE);
                            title.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
            int itemYear = 0;
            try { if (item.year() != null && !item.year().isBlank()) itemYear = Integer.parseInt(item.year()); } catch (Exception ignored) {}
            seriesArt = new CinemaArtwork.Art(item.logo(), item.poster(), item.backdrop(),
                    item.plot() == null ? "" : item.plot(), item.genres() == null ? "" : item.genres(),
                    List.of(), null, 0.0, 0,
                    item.title(), null, itemYear, 0, null, item.imdb());
            if (showMode && !item.plot().isBlank()) plot.setText(item.plot() + "\n" + item.attribution());
            String image = item.backdrop() == null ? item.poster() : item.backdrop();
            if (image != null) {
                seriesBackdropUrl = image;
                Picasso.get().load(image).resize(1280,720).centerCrop().into(backdrop);
            }
            seasonPosterImages.forEach(this::bindSeasonImage);
            if (!showMode && seasonCardImage != null) bindSeasonImage(selectedSeason, seasonCardImage);
            enforceModeVisibility();
            if (!item.cast().isEmpty()) {
                View cur = getCurrentFocus();
                int focusedCastIdx = (cur != null && cur.getParent() == cast) ? cast.indexOfChild(cur) : -1;
                suppressTabFocusSwitch = true;
                try {
                    cast.removeAllViews();
                    for (OpenMetadata.CastMember cm : item.cast()) {
                        actorPhoto(new CinemaArtwork.Person(cm.name(), cm.role(), cm.image()), cm.personId());
                    }
                    if (focusedCastIdx >= 0 && cast.getChildCount() > 0) {
                        cast.getChildAt(Math.min(focusedCastIdx, cast.getChildCount() - 1)).requestFocus();
                    } else if (cur != null && cur.isShown() && !cur.hasFocus()) {
                        cur.requestFocus();
                    }
                } finally {
                    suppressTabFocusSwitch = false;
                }
            }
            refreshShowMetadata();
            if (item.imdb() != null && !item.imdb().isBlank() && !item.imdb().equals(enrichedImdbId)) {
                enrichSeriesFromCinemetaAndTmdb(item.imdb());
            }
        });
    }

    private void enrichSeriesFromCinemetaAndTmdb(String imdbId) {
        if (imdbId == null || imdbId.isBlank()) return;
        enrichedImdbId = imdbId;
        io.execute(() -> {
            try {
                if (onlineId <= 0 && !com.meedx.providers.ProviderKeys.get(getApplicationContext(), com.meedx.providers.ProviderKeys.TMDB).isBlank()) {
                    var tags = show.getFullScraperTags(getApplicationContext());
                    if (tags != null && tags.getOnlineId() > 0) {
                        onlineId = tags.getOnlineId();
                    } else {
                        try {
                            List<CinemaPosters.Choice> found = CinemaPosters.search(getApplicationContext(), CinemaPosters.query(show, true), 0, false);
                            if (!found.isEmpty() && found.get(0).id() > 0) onlineId = found.get(0).id();
                        } catch (Throwable ignored) {}
                    }
                }
                CinemaArtwork.Art tmdbSeriesArt = null;
                if (onlineId > 0) {
                    try {
                        tmdbSeriesArt = CinemaArtwork.load(getApplicationContext(), onlineId, true, MetadataLanguage.resolve(this));
                    } catch (Throwable ignored) {}
                }
                final CinemaArtwork.Art fetchedTmdbArt = tmdbSeriesArt;
                Map<Integer, String> tvMazeSeasonPosters = OpenMetadata.fetchSeasonPosters(getApplicationContext(), imdbId);
                List<OpenMetadata.EpisodeMeta> eps = OpenMetadata.fetchCinemetaEpisodes(getApplicationContext(), imdbId);
                if ((!eps.isEmpty() || !tvMazeSeasonPosters.isEmpty() || fetchedTmdbArt != null) && !isDestroyed()) {
                    handler.post(() -> {
                        if (isDestroyed() || isFinishing()) return;
                        for (var sp : tvMazeSeasonPosters.entrySet()) {
                            if (sp.getValue() != null && !sp.getValue().isBlank()) {
                                officialSeasonPosters.putIfAbsent(sp.getKey(), Uri.parse(sp.getValue()));
                            }
                        }
                        if (fetchedTmdbArt != null) {
                            if (fetchedTmdbArt.rating() > 0 && fetchedTmdbArt.rating() <= 10.0) {
                                resolvedTmdb = fetchedTmdbArt.rating();
                                resolvedTmdbVotes = fetchedTmdbArt.votes();
                            }
                            if (fetchedTmdbArt.trailer() != null && trailer == null) {
                                trailer = Uri.parse(fetchedTmdbArt.trailer());
                                refreshActions();
                            }
                            CinemaArtwork.Art cur = seriesArt;
                            if (cur != null) {
                                seriesArt = new CinemaArtwork.Art(
                                        cur.logo() != null ? cur.logo() : fetchedTmdbArt.logo(),
                                        cur.poster() != null ? cur.poster() : fetchedTmdbArt.poster(),
                                        cur.backdrop() != null ? cur.backdrop() : fetchedTmdbArt.backdrop(),
                                        !cur.overview().isBlank() ? cur.overview() : fetchedTmdbArt.overview(),
                                        !cur.genres().isBlank() ? cur.genres() : fetchedTmdbArt.genres(),
                                        cur.cast(),
                                        cur.trailer() != null ? cur.trailer() : fetchedTmdbArt.trailer(),
                                        fetchedTmdbArt.rating(), fetchedTmdbArt.votes(),
                                        !cur.title().isBlank() ? cur.title() : fetchedTmdbArt.title(),
                                        cur.director() != null ? cur.director() : fetchedTmdbArt.director(),
                                        cur.year() > 0 ? cur.year() : fetchedTmdbArt.year(),
                                        cur.runtime() > 0 ? cur.runtime() : fetchedTmdbArt.runtime(),
                                        (cur.certification() != null && !cur.certification().isBlank()) ? cur.certification() : fetchedTmdbArt.certification(),
                                        cur.imdbId() != null ? cur.imdbId() : fetchedTmdbArt.imdbId());
                            } else {
                                seriesArt = fetchedTmdbArt;
                            }
                        }
                        for (OpenMetadata.EpisodeMeta em : eps) {
                            String key = em.season() + ":" + em.episode();
                            if (!em.title().isEmpty()) episodeMetaTitles.put(key, em.title());
                            if (!em.overview().isEmpty()) episodeMetaOverviews.put(key, em.overview());
                            if (em.rating() != null && em.rating() > 0) {
                                episodeMetaRatings.put(key, em.rating());
                                if (em.ratingSource() != null) episodeMetaRatingSources.put(key, em.ratingSource());
                            }
                            if (em.runtimeMinutes() != null && em.runtimeMinutes() > 0) {
                                episodeMetaRuntimes.put(key, em.runtimeMinutes());
                            }
                            if (em.year() > 0) {
                                episodeMetaYears.put(key, em.year());
                                seasonYears.putIfAbsent(em.season(), em.year());
                            }
                            if (!em.thumbnail().isEmpty()) {
                                Uri thumb = Uri.parse(em.thumbnail());
                                episodeMetaThumbs.put(key, thumb);
                                List<Episode> sEps = data.get(em.season());
                                if (sEps != null) {
                                    for (Episode localEp : sEps) {
                                        if (localEp.getEpisodeNumber() == em.episode()) {
                                            seasonStills.putIfAbsent(em.season(), thumb);
                                            if (em.season() == selectedSeason && !showMode) {
                                                ImageView iv = episodeImages.get(em.episode());
                                                if (iv != null) Picasso.get().load(thumb).resize(dp(this,EPISODE_CARD_WIDTH),dp(this,EPISODE_CARD_HEIGHT)).centerCrop().into(iv);
                                            }
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                        seasonPosterImages.forEach(this::bindSeasonImage);
                        if (!showMode && seasonCardImage != null) bindSeasonImage(selectedSeason, seasonCardImage);
                        refreshShowMetadata();
                        enforceModeVisibility();
                    });
                }
                if (onlineId > 0 && !isDestroyed()) {
                    handler.post(() -> {
                        if (isDestroyed()) return;
                        if (showMode) loadOtherSeasonImages();
                        else loadSeasonImages();
                    });
                }
            } catch (Throwable ignored) {}
        });
    }

    private void fetchMultiRatingsAsync(OpenMetadata.Item item) {
        Double knownImdb = "IMDb".equals(item.ratingSource()) ? item.score() : null;
        Double knownMal = "MAL".equals(item.ratingSource()) ? item.score() : null;
        var fetchedMulti = com.meedx.providers.MultiRatingsProvider.fetch(
                getApplicationContext(), item.imdb(), true, knownImdb, knownMal);
        loadSupplementarySeriesScores(item.imdb());
        handler.post(() -> {
            if (isDestroyed() || isFinishing()) return;
            multiRatings = fetchedMulti;
            if (fetchedMulti != null && fetchedMulti.tmdb != null && resolvedTmdb == null) {
                resolvedTmdb = fetchedMulti.tmdb;
            }
            refreshShowMetadata();
        });
    }

    @Override public void onBackPressed(){
        if(!showMode&&openedSeasonFromShow!=null){
            int season=openedSeasonFromShow;
            openedSeasonFromShow=null;
            selectShowMode(false);
            for(int n=0;n<seasonPostersRow.getChildCount();n++){
                View card=seasonPostersRow.getChildAt(n);
                if(Integer.valueOf(season).equals(card.getTag())){card.requestFocus();return;}
            }
            focusActiveTopTab();
            return;
        }
        super.onBackPressed();
    }

    @Override public void onSaveInstanceState(Bundle state){state.putInt("season",selectedSeason);state.putString("episode",selectedEpisode);state.putString("launchedEpisode",launchedEpisode);state.putString("returningEpisode",returningEpisode);state.putBoolean("showMode",showMode);if(selectedShowSeason!=null)state.putInt("show_season",selectedShowSeason);super.onSaveInstanceState(state);}
    @Override public void onResume(){super.onResume();if(!initial)refreshActions();}
    /** Host-agnostic, decoded, lower-case path key so IP/hostname duplicates collapse. */
    static String episodePathKey(String path){
        if(path==null)return "";
        String p=Uri.decode(path).replace('\\','/').toLowerCase(Locale.ROOT);
        int scheme=p.indexOf("://");
        if(scheme>0){int slash=p.indexOf('/',scheme+3);p=slash<0?"":p.substring(slash);}
        return p.replaceAll("/+$","");
    }
    private final Runnable libraryRefreshTask=()->{if(!isDestroyed())LoaderManager.getInstance(CinemaSeriesActivity.this).restartLoader(1,null,CinemaSeriesActivity.this);};
    private final android.content.BroadcastReceiver libraryRefresh=new android.content.BroadcastReceiver(){
        @Override public void onReceive(android.content.Context c,Intent i){
            handler.removeCallbacks(libraryRefreshTask);handler.postDelayed(libraryRefreshTask,600);
        }
    };
    private void registerLibraryRefresh(){
        android.content.IntentFilter plain=new android.content.IntentFilter(com.archos.mediaprovider.ArchosMediaIntent.ACTION_VIDEO_SCANNER_SCAN_FINISHED);
        plain.addAction(CinemaLibraryScan.ACTION_CHANGED);
        android.content.IntentFilter withData=new android.content.IntentFilter(com.archos.mediaprovider.ArchosMediaIntent.ACTION_VIDEO_SCANNER_SCAN_FINISHED);
        for(String scheme:new String[]{"smb","smbj","file","content","webdav"})withData.addDataScheme(scheme);
        androidx.core.content.ContextCompat.registerReceiver(this,libraryRefresh,plain,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        androidx.core.content.ContextCompat.registerReceiver(this,libraryRefresh,withData,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    @Override public void onDestroy(){try{unregisterReceiver(libraryRefresh);}catch(Exception ignored){}handler.removeCallbacksAndMessages(null);io.shutdownNow();Picasso.get().cancelRequest(backdrop);Picasso.get().cancelRequest(logo);super.onDestroy();}
}

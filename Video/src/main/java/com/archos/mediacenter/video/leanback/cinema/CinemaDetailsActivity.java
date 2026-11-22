package com.archos.mediacenter.video.leanback.cinema;

import com.meedx.providers.OpenMetadata;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.archos.mediacenter.video.browser.adapters.object.Episode;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import com.archos.mediacenter.video.leanback.details.VideoDetailsActivity;
import com.archos.mediacenter.video.leanback.details.VideoDetailsFragment;
import com.archos.mediacenter.video.player.PlayerActivity;
import com.archos.mediacenter.video.utils.DbUtils;
import com.archos.mediacenter.video.utils.PlayUtils;
import com.archos.mediascraper.BaseTags;
import com.archos.mediascraper.ScraperImage;
import com.archos.mediascraper.ScraperTrailer;
import com.squareup.picasso.Picasso;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

/** Keeps NOVA's playback and database actions; artwork lookup never delays Play. */
public class CinemaDetailsActivity extends LeanbackActivity {
    public static final String EXTRA_SEASON_EPISODES = "cinema_season_episodes";
    public static final String EXTRA_EPISODE_INDEX = "cinema_episode_index";
    public static final String EXTRA_SHOW_TITLE = "cinema_show_title";
    public static final String EXTRA_SHOW_IMDB = "cinema_show_imdb";
    private static final int REQ_EPISODE_PLAYER = 701;

    private Video video;
    private java.util.ArrayList<Episode> seasonEpisodes;
    private int episodeIndex = -1;
    private String showTitle;
    private String showImdb;
    private ImageView backdrop;
    private ImageView logo;
    private TextView nameView,actionHint,fileView,episodeProgressPill;
    private View episodeProgressFill;
    private FrameLayout episodeProgressTrack;
    private TextView description,director,watchButton,seenButton,trailerButton,playButton,nextEpisodeButton,seasonListButton;
    private LinearLayout infoRow,cast,technical,ratings;
    private com.archos.mediacenter.video.utils.VideoMetadata trackMetadata;private TextView audioChip;private String videoCapsule;
    private boolean watched;
    private Uri trailer;
    private long measuredDuration;private CinemaArtwork.Art detailsArt;
    private OpenMetadata.Item publicMetadata;
    private com.meedx.providers.MultiRatingsProvider.Ratings multiRatings;
    private Double resolvedImdb;private int resolvedImdbVotes;private Integer resolvedCritics,resolvedMetacritic;private boolean seenBusy;
    private final ExecutorService io=Executors.newFixedThreadPool(2);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String l(String ar,String en){return label(this,ar,en);}
    private boolean rtl;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        video=(Video)getIntent().getSerializableExtra(VideoDetailsFragment.EXTRA_VIDEO);
        if(video==null){finish();return;}watched=video.isWatched();
        @SuppressWarnings("unchecked")
        java.util.ArrayList<Episode> epsExtra=(java.util.ArrayList<Episode>)getIntent().getSerializableExtra(EXTRA_SEASON_EPISODES);
        seasonEpisodes=epsExtra;
        episodeIndex=getIntent().getIntExtra(EXTRA_EPISODE_INDEX,-1);
        showTitle=getIntent().getStringExtra(EXTRA_SHOW_TITLE);
        showImdb=getIntent().getStringExtra(EXTRA_SHOW_IMDB);
        if(video instanceof Episode ep && seasonEpisodes!=null && episodeIndex<0){
            for(int i=0;i<seasonEpisodes.size();i++){
                if(seasonEpisodes.get(i).getId()==ep.getId()){episodeIndex=i;break;}
            }
        }
        rtl=CinemaUi.arabic(this);
        FrameLayout root=new FrameLayout(this);root.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);root.setBackgroundColor(CinemaTokens.CANVAS);setContentView(root);
        backdrop=new ImageView(this);backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);root.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
        View shade=new View(this);shade.setBackground(new GradientDrawable(rtl?GradientDrawable.Orientation.RIGHT_LEFT:GradientDrawable.Orientation.LEFT_RIGHT,new int[]{CinemaTokens.CANVAS,0xd9171717,0x18171717}));root.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        View bottom=new View(this);bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00171717,0x55171717,CinemaTokens.CANVAS}));root.addView(bottom,new FrameLayout.LayoutParams(-1,-1));
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);root.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);content.setGravity(rtl?Gravity.RIGHT:Gravity.LEFT);content.setPaddingRelative(dp(this,CinemaTokens.GUTTER),dp(this,48),dp(this,48),dp(this,32));scroll.addView(content);
        FrameLayout identity=new FrameLayout(this);LinearLayout.LayoutParams identityLp=new LinearLayout.LayoutParams(dp(this,500),dp(this,80));identityLp.bottomMargin=dp(this,6);content.addView(identity,identityLp);
        TextView name=text(this,CinemaTokens.FILM_TITLE,true);nameView=name;name.setVisibility(View.INVISIBLE);name.setText(formatHeaderTitle(video));name.setMaxLines(2);name.setTextSize(30);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setMinimumHeight(dp(this,48));name.setGravity(Gravity.CENTER_VERTICAL|(rtl?Gravity.RIGHT:Gravity.LEFT));identity.addView(name,new FrameLayout.LayoutParams(-1,-1));
        logo=new ImageView(this);logo.setScaleType(rtl?ImageView.ScaleType.FIT_END:ImageView.ScaleType.FIT_START);logo.setVisibility(View.GONE);identity.addView(logo,new FrameLayout.LayoutParams(dp(this,360),dp(this,64),rtl?Gravity.RIGHT:Gravity.LEFT));
        LinearLayout infoWrap=new LinearLayout(this);infoWrap.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);infoWrap.setOrientation(LinearLayout.HORIZONTAL);infoWrap.setGravity(Gravity.CENTER_VERTICAL|(rtl?Gravity.RIGHT:Gravity.LEFT));content.addView(infoWrap,new LinearLayout.LayoutParams(-1,dp(this,30)));
        infoRow=new LinearLayout(this);infoRow.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);infoRow.setOrientation(LinearLayout.HORIZONTAL);infoRow.setGravity(Gravity.CENTER_VERTICAL|(rtl?Gravity.RIGHT:Gravity.LEFT));infoRow.setPadding(0,dp(this,4),0,dp(this,6));infoWrap.addView(infoRow);
        episodeProgressPill=text(this,11,true);episodeProgressPill.setTextColor(0xff101113);episodeProgressPill.setPadding(dp(this,8),dp(this,2),dp(this,8),dp(this,2));episodeProgressPill.setBackground(rounded(CinemaTokens.GOLD,dp(this,10),0));episodeProgressPill.setVisibility(View.GONE);LinearLayout.LayoutParams pillLp=new LinearLayout.LayoutParams(-2,-2);pillLp.setMarginStart(dp(this,8));infoWrap.addView(episodeProgressPill,pillLp);
        episodeProgressTrack=new FrameLayout(this);episodeProgressTrack.setBackground(rounded(0x33ffffff,dp(this,2),0));episodeProgressTrack.setClipToOutline(true);episodeProgressTrack.setVisibility(View.INVISIBLE);
        episodeProgressFill=new View(this);episodeProgressFill.setBackgroundColor(CinemaTokens.GOLD);episodeProgressTrack.addView(episodeProgressFill,new FrameLayout.LayoutParams(0,-1));
        LinearLayout.LayoutParams barLp=new LinearLayout.LayoutParams(dp(this,320),dp(this,4));barLp.bottomMargin=dp(this,6);content.addView(episodeProgressTrack,barLp);
        ratings=new LinearLayout(this);ratings.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);ratings.setOrientation(LinearLayout.HORIZONTAL);ratings.setGravity(Gravity.CENTER_VERTICAL|(rtl?Gravity.RIGHT:Gravity.LEFT));ratings.setPadding(0,0,0,dp(this,10));ratings.setVisibility(View.GONE);CinemaUi.addFixedSlot(content,ratings,-1,dp(this,28));
        description=text(this,CinemaTokens.LABEL,false);description.setText(video.getDescriptionBody());description.setMaxLines(3);description.setGravity(rtl?Gravity.RIGHT:Gravity.LEFT);description.setLineSpacing(dp(this,2),1);description.setEllipsize(android.text.TextUtils.TruncateAt.END);CinemaUi.addFixedSlot(content,description,dp(this,450),dp(this,60));
        director=text(this,CinemaTokens.CAPTION,false);director.setTextColor(CinemaTokens.SECONDARY);director.setGravity(rtl?Gravity.RIGHT:Gravity.LEFT);director.setPadding(0,dp(this,8),0,dp(this,8));director.setMaxLines(1);director.setEllipsize(android.text.TextUtils.TruncateAt.END);CinemaUi.addFixedSlot(content,director,-1,dp(this,34));
        HorizontalScrollView actionsScroll=new HorizontalScrollView(this);actionsScroll.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);actionsScroll.setFillViewport(true);actionsScroll.setHorizontalScrollBarEnabled(false);LinearLayout actionLine=new LinearLayout(this);actionLine.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);actionLine.setGravity(Gravity.CENTER_VERTICAL);actionLine.setOrientation(LinearLayout.HORIZONTAL);content.addView(actionLine,new LinearLayout.LayoutParams(-1,dp(this,54)));
        actionLine.addView(actionsScroll,new LinearLayout.LayoutParams(-2,-2));
        LinearLayout actions=new LinearLayout(this);actions.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);actions.setOrientation(LinearLayout.HORIZONTAL);actions.setGravity(Gravity.CENTER_VERTICAL|(rtl?Gravity.RIGHT:Gravity.LEFT));actionsScroll.addView(actions);
        TextView play=chipAction(actions,playLabel(),com.archos.mediacenter.video.R.drawable.cinema_play_movie,()->play(false));playButton=play;
        applyPlayIcon();
        iconAction(actions,getString(com.archos.mediacenter.video.R.string.cinema_restart),com.archos.mediacenter.video.R.drawable.cinema_restart,()->play(true));
        if(video instanceof Episode){
            nextEpisodeButton=iconAction(actions,l("الحلقة التالية","Next Episode"),com.archos.mediacenter.video.R.drawable.cinema_play_movie,()->{
                if(seasonEpisodes!=null && episodeIndex>=0 && episodeIndex+1<seasonEpisodes.size()){
                    switchToEpisode(episodeIndex+1);
                }
            });
            seasonListButton=iconAction(actions,l("حلقات الموسم","Season Episodes"),com.archos.mediacenter.video.R.drawable.cinema_more,this::finish);
            refreshEpisodeNavigationButtons();
        }
        watchButton=iconAction(actions,getString(com.archos.mediacenter.video.R.string.cinema_add_to_watchlist),com.archos.mediacenter.video.R.drawable.cinema_watchlist,()->{CinemaWatchlistActivity.toggle(this,video);updateLabels();});
        seenButton=iconAction(actions,getString(com.archos.mediacenter.video.R.string.cinema_mark_as_watched),com.archos.mediacenter.video.R.drawable.cinema_check,()->{
            if(seenBusy)return;
            seenBusy=true;
            View focusedBefore=getCurrentFocus();
            final boolean restoreSeen=focusedBefore==seenButton||seenButton.hasFocus();
            boolean desired=!watched;
            io.execute(()->{try{if(desired)DbUtils.markAsRead(getApplicationContext(),video);else DbUtils.markAsNotRead(getApplicationContext(),video);
                CinemaLinkServer.broadcastWatchSync(CinemaWatchStore.buildSyncRecordForVideo(
                        getApplicationContext(),video,0L,desired,!desired));
                CinemaWatchStore.pushVideoWatchedToTraktAsync(getApplicationContext(),video,desired);
                CinemaWatchStore.syncWithTraktAsync(getApplicationContext());
                handler.post(()->{if(!isDestroyed()){seenBusy=false;watched=desired;applyPlayIcon();updateLabels();if(restoreSeen||getCurrentFocus()==null)seenButton.requestFocus();}});
            }catch(Exception e){handler.post(()->{if(!isDestroyed()){seenBusy=false;if(restoreSeen)seenButton.requestFocus();Toast.makeText(this,getString(com.archos.mediacenter.video.R.string.cinema_could_not_save_watched_status),Toast.LENGTH_LONG).show();}});}});
        });
        if (com.meedx.providers.StremioAddons.list(this).stream().anyMatch(
                addon -> addon.enabled && com.meedx.providers.StremioAddons.mayStream(addon))) {
            chipAction(actions, getString(com.archos.mediacenter.video.R.string.addon_streams),
                    com.archos.mediacenter.video.R.drawable.cinema_addons,
                    () -> CinemaAddonPicker.show(this, io, handler, video, publicMetadata, 0, 0));
        }
        trailerButton=iconAction(actions,"Trailer",com.archos.mediacenter.video.R.drawable.cinema_trailer,this::launchTrailer);
        String trailerLabel=video instanceof com.archos.mediacenter.video.browser.adapters.object.Episode?getString(com.archos.mediacenter.video.R.string.cinema_watch_series_trailer):getString(com.archos.mediacenter.video.R.string.cinema_watch_trailer);
        trailerButton.setContentDescription(trailerLabel);trailerButton.setTooltipText(trailerLabel);trailerButton.setVisibility(View.VISIBLE);
        TextView subtitles=iconAction(actions,getString(com.archos.mediacenter.video.R.string.cinema_subtitles_cinemadetailsactivity),com.archos.mediacenter.video.R.drawable.cinema_subtitles,()->{});
        subtitles.setOnClickListener(v->subtitleMenu(subtitles));
        TextView more=iconAction(actions,getString(com.archos.mediacenter.video.R.string.cinema_more),com.archos.mediacenter.video.R.drawable.cinema_more,()->{});
        more.setOnClickListener(v->moreMenu(more));
        actionHint=text(this,CinemaTokens.CAPTION,false);actionHint.setTextColor(CinemaTokens.SECONDARY);
        // Action names are no longer shown under the buttons (Plex style); descriptions stay for accessibility.
        updateLabels();play.requestFocus();
        technical=new LinearLayout(this);technical.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);technical.setGravity((rtl?Gravity.LEFT:Gravity.RIGHT)|Gravity.CENTER_VERTICAL);technical.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams techParams=new LinearLayout.LayoutParams(0,-2,1);techParams.setMarginStart(dp(this,16));actionLine.addView(technical,techParams);
        if(technical.getChildCount()==0)chip(technical,getString(com.archos.mediacenter.video.R.string.cinema_reading_file_information));
        LinearLayout.LayoutParams castGap=new LinearLayout.LayoutParams(-1,-2);castGap.topMargin=dp(this,16);
        TextView castTitle=text(this,CinemaTokens.ROW_TITLE,true);castTitle.setGravity(rtl?Gravity.RIGHT:Gravity.LEFT);castTitle.setText(getString(com.archos.mediacenter.video.R.string.cinema_cast_crew));content.addView(castTitle,castGap);
        HorizontalScrollView castScroll=new HorizontalScrollView(this);castScroll.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);castScroll.setFillViewport(true);castScroll.setHorizontalScrollBarEnabled(false);castScroll.setClipChildren(false);castScroll.setClipToPadding(false);content.addView(castScroll,new LinearLayout.LayoutParams(-1,-2));
        cast=new LinearLayout(this);cast.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);cast.setOrientation(LinearLayout.HORIZONTAL);cast.setGravity(rtl?Gravity.RIGHT:Gravity.LEFT);cast.setClipChildren(false);cast.setClipToPadding(false);castScroll.addView(cast);
        TextView file=text(this,12,false);fileView=file;file.setGravity(rtl?Gravity.RIGHT:Gravity.LEFT);file.setText(formatFileSummary(video.getSize()));file.setPadding(0,dp(this,18),0,dp(this,12));content.addView(file);
        if(rtl){
            actionsScroll.post(()->actionsScroll.scrollTo(Math.max(0,actions.getWidth()-actionsScroll.getWidth()),0));
            castScroll.post(()->castScroll.scrollTo(Math.max(0,cast.getWidth()-castScroll.getWidth()),0));
        }
        settingsNav=null;refreshInfo();applyPlayIcon();loadTags();loadTechnical();
    }

    private String formatHeaderTitle(Video v) {
        if (v instanceof Episode ep) {
            String baseShow = (showTitle != null && !showTitle.isBlank()) ? showTitle.trim() : ep.getShowName();
            String code = String.format(Locale.US, "S%02d E%02d", ep.getSeasonNumber(), ep.getEpisodeNumber());
            String epName = ep.getEpisodeName();
            if (epName != null && !epName.isBlank() && !epName.trim().matches("(?i)S\\d+E\\d+")) {
                return (baseShow != null && !baseShow.isBlank() ? baseShow + " · " : "") + code + " — " + epName.trim();
            }
            return (baseShow != null && !baseShow.isBlank() ? baseShow + " · " : "") + code;
        }
        return v.getName();
    }

    private void refreshEpisodeNavigationButtons() {
        if (nextEpisodeButton != null) {
            boolean hasNext = seasonEpisodes != null && episodeIndex >= 0 && episodeIndex + 1 < seasonEpisodes.size();
            nextEpisodeButton.setVisibility(hasNext ? View.VISIBLE : View.GONE);
            nextEpisodeButton.setEnabled(hasNext);
        }
        if (seasonListButton != null) {
            seasonListButton.setVisibility(video instanceof Episode ? View.VISIBLE : View.GONE);
        }
    }

    private void refreshEpisodeProgressBadge() {
        if (episodeProgressPill == null || episodeProgressTrack == null) return;
        if (!(video instanceof Episode)) {
            episodeProgressPill.setVisibility(View.GONE);
            episodeProgressTrack.setVisibility(View.INVISIBLE);
            return;
        }
        long dur = effectiveDuration();
        long res = effectiveResume();
        int pct;
        if (watched || res == PlayerActivity.LAST_POSITION_END) {
            pct = 100;
        } else if (dur > 0 && res > 0) {
            pct = (int) Math.min(99, Math.max(1, res * 100L / dur));
        } else {
            pct = 0;
        }
        if (pct == 100) {
            episodeProgressPill.setText(l("تمت المشاهدة ✓ · 100%", "Watched ✓ · 100%"));
            episodeProgressPill.setVisibility(View.VISIBLE);
            episodeProgressTrack.setVisibility(View.VISIBLE);
            episodeProgressFill.getLayoutParams().width = dp(this, 320);
            episodeProgressFill.requestLayout();
        } else if (pct > 0) {
            episodeProgressPill.setText(l("نسبة المشاهدة: ", "Watched: ") + pct + "%");
            episodeProgressPill.setVisibility(View.VISIBLE);
            episodeProgressTrack.setVisibility(View.VISIBLE);
            episodeProgressFill.getLayoutParams().width = Math.max(dp(this, 6), dp(this, 320) * pct / 100);
            episodeProgressFill.requestLayout();
        } else {
            episodeProgressPill.setText(l("لم تُشاهد بعد · 0%", "Unwatched · 0%"));
            episodeProgressPill.setVisibility(View.VISIBLE);
            episodeProgressTrack.setVisibility(View.INVISIBLE);
        }
    }

    private void switchToEpisode(int newIndex) {
        if (seasonEpisodes == null || newIndex < 0 || newIndex >= seasonEpisodes.size()) return;
        episodeIndex = newIndex;
        Episode nextEp = seasonEpisodes.get(newIndex);
        video = nextEp;
        watched = nextEp.isWatched();
        measuredDuration = 0L;
        detailsArt = null;
        trackMetadata = null;
        videoCapsule = null;
        setResult(RESULT_OK, new Intent().putExtra("current_episode_id", nextEp.getId()));
        nameView.setVisibility(View.VISIBLE);
        nameView.setText(formatHeaderTitle(nextEp));
        description.setText(nextEp.getDescriptionBody());
        if (fileView != null) fileView.setText(formatFileSummary(nextEp.getSize()));
        applyPlayIcon();
        updateLabels();
        refreshInfo();
        refreshEpisodeNavigationButtons();
        loadTags();
        loadTechnical();
        reloadVideoFromDb();
    }
    private void moreMenu(View anchor){
        java.util.List<CinemaActionMenu.Action> actions=new java.util.ArrayList<>();
        actions.add(new CinemaActionMenu.Action(l("مصادر الإضافات (Nuvio / Stremio)", "Addon Streams (Nuvio / Stremio)"),
                () -> CinemaAddonPicker.show(this, io, handler, video, publicMetadata, 0, 0)));
        if(effectiveResume()>0){
            actions.add(new CinemaActionMenu.Action(getString(com.archos.mediacenter.video.R.string.cinema_remove_from_continue_watching),()->{
                io.execute(()->{
                    try{
                        DbUtils.markAsNotRead(getApplicationContext(),video);
                        CinemaLinkServer.broadcastWatchSync(CinemaWatchStore.buildSyncRecordForVideo(
                                getApplicationContext(),video,0L,false,true));
                        CinemaWatchStore.removePlaybackProgressFromTraktAsync(
                                getApplicationContext(), video.getId(), video.getFilePath(), video.getName(),
                                publicMetadata != null ? publicMetadata.imdb() : null,
                                video instanceof Movie m ? m.getOnlineId() : 0L, 0, 0);
                        CinemaWatchStore.pushVideoWatchedToTraktAsync(getApplicationContext(),video,false);
                        CinemaWatchStore.syncWithTraktAsync(getApplicationContext());
                        handler.post(()->{if(!isDestroyed()){watched=false;applyPlayIcon();updateLabels();Toast.makeText(this,getString(com.archos.mediacenter.video.R.string.cinema_removed_from_continue_watching),Toast.LENGTH_SHORT).show();}});
                    }catch(Exception ignored){}
                });
            }));
        }
        actions.add(new CinemaActionMenu.Action(getString(com.archos.mediacenter.video.R.string.cinema_more_details_and_advanced_actions),()->{
            Intent intent=new Intent(this,VideoDetailsActivity.class);
            intent.putExtra(VideoDetailsFragment.EXTRA_VIDEO,video);
            intent.putExtra(VideoDetailsFragment.EXTRA_FORCE_VIDEO_SELECTION,true);
            startActivity(intent);
        }));
        CinemaActionMenu.show(anchor,actions.toArray(new CinemaActionMenu.Action[0]));
    }

    private void subtitleMenu(View anchor){
        CinemaActionMenu.show(anchor,
                new CinemaActionMenu.Action(getString(com.archos.mediacenter.video.R.string.cinema_embedded_subtitles),this::embeddedSubtitles),
                new CinemaActionMenu.Action(getString(com.archos.mediacenter.video.R.string.cinema_choose_from_video_folder),()->{
                    Intent intent=new Intent(this,CinemaSubtitleBrowserActivity.class);
                    intent.setData(video.getFileUri());
                    startActivity(intent);
                }),
                new CinemaActionMenu.Action(SubtitleSources.label(this), () -> SubtitleSources.search(this, video)),
                new CinemaActionMenu.Action(SubtitleSources.title(this), () -> SubtitleSources.settings(this)));
    }

    private void embeddedSubtitles(){
        if(trackMetadata==null){Toast.makeText(this,getString(com.archos.mediacenter.video.R.string.cinema_file_tracks_are_still_loading),Toast.LENGTH_SHORT).show();return;}
        java.util.List<String> names=new java.util.ArrayList<>();java.util.List<Integer> ordinals=new java.util.ArrayList<>();
        names.add(getString(com.archos.mediacenter.video.R.string.cinema_off_cinemadetailsactivity));ordinals.add(-1);names.add(getString(com.archos.mediacenter.video.R.string.cinema_automatic));ordinals.add(-2);int ordinal=0;
        for(int n=0;n<trackMetadata.getSubtitleTrackNb();n++){var track=trackMetadata.getSubtitleTrack(n);if(track.isExternal)continue;names.add((ordinal+1)+" · "+join(language(track.language),track.name));ordinals.add(ordinal++);}
        if(ordinal==0)names.add(getString(com.archos.mediacenter.video.R.string.cinema_no_embedded_subtitles_in_this_file));
        CinemaUi.dialog(this).setTitle(getString(com.archos.mediacenter.video.R.string.cinema_embedded_subtitles_cinemadetailsactivity)).setItems(names.toArray(new String[0]),(dialog,index)->{if(index>=ordinals.size())return;CinemaTrackChoices.embedded(this,video.getFileUri(),ordinals.get(index));Toast.makeText(this,getString(com.archos.mediacenter.video.R.string.cinema_subtitle_choice_saved_for_playback),Toast.LENGTH_SHORT).show();}).show();
    }
    private String language(String code){if(code==null||code.isBlank()||code.equals("und"))return getString(com.archos.mediacenter.video.R.string.cinema_unknown_language);return new Locale(code).getDisplayLanguage(Locale.ENGLISH);}
    private String audioLabel(int index){var track=trackMetadata.getAudioTrack(index);return join(language(track.language),join(track.format,track.channels));}
    private void audioTracks(){
        if(trackMetadata==null||trackMetadata.getAudioTrackNb()==0)return;
        String[] names=new String[trackMetadata.getAudioTrackNb()];for(int n=0;n<names.length;n++)names[n]=(n+1)+" · "+audioLabel(n);
        CinemaUi.dialog(this).setTitle(getString(com.archos.mediacenter.video.R.string.cinema_audio_tracks)).setItems(names,(dialog,index)->{CinemaTrackChoices.audio(this,video.getFileUri(),index);renderTechnical();if(audioChip!=null)audioChip.requestFocus();Toast.makeText(this,getString(com.archos.mediacenter.video.R.string.cinema_audio_track_saved_for_playback),Toast.LENGTH_SHORT).show();}).show();
    }
    private void renderTechnical(){
        technical.removeAllViews();audioChip=null;if(trackMetadata==null)return;int count=trackMetadata.getAudioTrackNb();
        if(count>0){int selected=CinemaTrackChoices.audio(this,video.getFileUri());if(selected<0||selected>=count)selected=Math.max(0,Math.min(video.getCalculatedBestAudiotrack(),count-1));audioChip=chip(technical,audioLabel(selected));focusable(audioChip);audioChip.setMinimumHeight(0);android.graphics.drawable.StateListDrawable background=new android.graphics.drawable.StateListDrawable();background.addState(new int[]{android.R.attr.state_focused},rounded(CinemaTokens.FOCUS,dp(this,8),0));background.addState(new int[]{android.R.attr.state_pressed},rounded(CinemaTokens.PRESSED,dp(this,8),0));background.addState(new int[]{},rounded(CinemaTokens.CAPSULE,dp(this,8),0));audioChip.setBackground(background);audioChip.setContentDescription(getString(com.archos.mediacenter.video.R.string.cinema_change_audio_track)+audioLabel(selected));audioChip.setOnClickListener(v->audioTracks());}
        TextView resolution=videoCapsule!=null&&!videoCapsule.isBlank()?chip(technical,videoCapsule):null;
        if(audioChip!=null&&resolution!=null){TextView sound=audioChip;technical.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){if(technical.getViewTreeObserver().isAlive())technical.getViewTreeObserver().removeOnPreDrawListener(this);if(sound.getParent()!=technical||resolution.getParent()!=technical)return true;int height=Math.max(sound.getHeight(),resolution.getHeight());boolean changed=false;if(height>0&&sound.getHeight()!=height){sound.setMinimumHeight(height);changed=true;}if(height>0&&resolution.getHeight()!=height){resolution.setMinimumHeight(height);changed=true;}return !changed;}});}
        if(technical.getChildCount()==0)chip(technical,getString(com.archos.mediacenter.video.R.string.cinema_file_information_unavailable));
    }
    private long effectiveDuration(){
        long d=measuredDuration>0?measuredDuration:CinemaMediaTime.known(video.getId(),video.getFilePath(),video.getDurationMs());
        if(d<=0&&detailsArt!=null&&detailsArt.runtime()>0)d=detailsArt.runtime()*60000L;
        return d;
    }
    private long effectiveResume(){
        return CinemaMediaTime.knownResume(video.getId(),video.getFilePath(),video.getResumeMs());
    }
    private void refreshInfo(){
        if(infoRow==null)return;long duration=measuredDuration>0?measuredDuration:CinemaMediaTime.known(video.getId(),video.getFilePath(),video.getDurationMs());
        int year=detailsArt!=null&&detailsArt.year()>0?detailsArt.year():video instanceof Movie movie?movie.getYear():0;
        if (publicMetadata != null && !publicMetadata.year().isBlank()) year = Integer.parseInt(publicMetadata.year());
        String time=duration>0?CinemaMediaTime.format(duration):detailsArt!=null&&detailsArt.runtime()>0?CinemaMediaTime.format(detailsArt.runtime()*60000L):null;
        String cert=detailsArt!=null&&detailsArt.certification()!=null&&!detailsArt.certification().isBlank()?detailsArt.certification():video instanceof Movie m?m.getContentRating():null;
        String genres=detailsArt!=null?detailsArt.genres():null;
        CinemaRatingBadge.renderInfoLine(this,infoRow,year,time,genres,cert);
        double tmdb=detailsArt!=null?detailsArt.rating():video instanceof Movie m?m.getRating():0;
        int tmdbVotes=detailsArt!=null?detailsArt.votes():100;
        if (OpenMetadata.enabled(this)) CinemaRatingBadge.renderMulti(this, ratings, multiRatings, publicMetadata,
                resolvedImdb, tmdb > 0 ? tmdb : null, resolvedCritics, resolvedMetacritic);
        else CinemaRatingBadge.renderAll(this,ratings,cert,genres,resolvedImdb,resolvedImdbVotes,resolvedCritics,tmdb,tmdbVotes);
    }
    private String join(String a,String b){return (a==null?"":a)+(a!=null&&!a.isBlank()&&b!=null&&!b.isBlank()?"  ·  ":"")+(b==null?"":b);}
    /** "Resume · 1h 12m left" when a resume point exists and the duration is known, otherwise "Watch". */
    private String playLabel(){
        long resume=effectiveResume(),total=effectiveDuration();
        if(resume<=0)return getString(com.archos.mediacenter.video.R.string.cinema_watch);
        String base=getString(com.archos.mediacenter.video.R.string.cinema_resume);
        String rem=CinemaMediaTime.formatRemaining(total,resume);
        return rem==null?base:base+"  ·  "+rem+getString(com.archos.mediacenter.video.R.string.cinema_left);
    }
    private void applyPlayIcon(){
        if(playButton==null)return;
        setChipIcon(playButton,effectiveResume()>0?com.archos.mediacenter.video.R.drawable.cinema_resume:com.archos.mediacenter.video.R.drawable.cinema_play_movie);
        CinemaActionChip.setLabel(playButton,playLabel());
        refreshEpisodeProgressBadge();
    }
    private TextView button(LinearLayout parent,String name,Runnable action){
        TextView t=text(this,14,true);t.setText(name);t.setGravity(Gravity.CENTER);t.setPadding(dp(this,16),dp(this,12),dp(this,16),dp(this,12));focusable(t);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.rightMargin=dp(this,8);parent.addView(t,p);t.setOnClickListener(v->action.run());return t;
    }
    private TextView iconAction(LinearLayout parent,String name,int icon,Runnable action){return chipAction(parent,name,icon,action);}
    private TextView chipAction(LinearLayout parent,String name,int icon,Runnable action){return CinemaActionChip.create(this,parent,name,icon,action);}
    private void setChipIcon(TextView t,int icon){CinemaActionChip.setIcon(t,icon);}
    private void syncChip(TextView t){CinemaActionChip.sync(t);}
    private void roundAction(View view){android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();states.addState(new int[]{android.R.attr.state_focused},rounded(CinemaTokens.FOCUS,dp(this,24),0));states.addState(new int[]{android.R.attr.state_pressed},rounded(CinemaTokens.PRESSED,dp(this,24),0));states.addState(new int[]{},rounded(Color.TRANSPARENT,dp(this,24),0));view.setBackground(states);}
    private android.graphics.drawable.GradientDrawable castRing(){android.graphics.drawable.GradientDrawable ring=rounded(Color.TRANSPARENT,dp(this,52),0);ring.setStroke(dp(this,3),CinemaTokens.FOCUS);return ring;}
    private void updateLabels(){
        boolean inList=CinemaWatchlistActivity.contains(this,video);
        CinemaActionChip.setLabel(watchButton, inList?getString(com.archos.mediacenter.video.R.string.cinema_remove_from_watchlist):getString(com.archos.mediacenter.video.R.string.cinema_add_to_watchlist_cinemadetailsactivity));
        watchButton.setSelected(inList);
        CinemaActionChip.setLabel(seenButton, watched?getString(com.archos.mediacenter.video.R.string.cinema_mark_as_unwatched):getString(com.archos.mediacenter.video.R.string.cinema_mark_as_watched_cinemadetailsactivity));
        seenButton.setSelected(watched);
        CinemaActionChip.setActive(watchButton, inList);
        CinemaActionChip.setActive(seenButton, watched);
    }
    private TextView chip(LinearLayout row,String value){
        TextView t=text(this,CinemaTokens.CAPTION,false);t.setText(value);t.setMaxLines(2);t.setMaxWidth(dp(this,180));t.setGravity(Gravity.CENTER_VERTICAL);
        t.setFocusable(false);t.setClickable(false);
        t.setPadding(dp(this,8),dp(this,6),dp(this,8),dp(this,6));t.setBackground(rounded(CinemaTokens.CAPSULE,dp(this,8),0));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.setMarginEnd(dp(this,8));row.addView(t,lp);return t;
    }
    private String formatFileSummary(long bytes){
        long b=bytes;
        if(b<=0&&video!=null&&video.getFilePath()!=null){try{File f=new File(video.getFilePath());if(f.isFile())b=f.length();}catch(Exception ignored){}}
        String name=video!=null?video.getFileNameWithExtension():"";
        if(b<=0)return name;
        String size=b>=1073741824L?String.format(Locale.US,"%.2f GB",b/1073741824.0):String.format(Locale.US,"%.1f MB",b/1048576.0);
        return name+"\n"+size;
    }
    private void loadTechnical(){io.execute(()->{try{
        com.archos.mediacenter.video.utils.VideoMetadata metadata=video.getMetadata();if(metadata==null||metadata.getVideoTrack()==null)metadata=com.archos.mediacenter.video.info.VideoInfoCommonClass.retrieveMetadata(video,getApplicationContext());
        com.archos.mediacenter.video.utils.VideoMetadata resolved=metadata;
        handler.post(()->{if(isDestroyed()||isFinishing())return;trackMetadata=resolved;if(resolved!=null){if(resolved.getDuration()>0){measuredDuration=resolved.getDuration();CinemaMediaTime.remember(video.getFilePath(),measuredDuration);refreshInfo();applyPlayIcon();}if(fileView!=null&&resolved.getFileSize()>0)fileView.setText(formatFileSummary(resolved.getFileSize()));String dimensions=resolved.getVideoWidth()>0&&resolved.getVideoHeight()>0?resolved.getVideoWidth()+" × "+resolved.getVideoHeight():null;videoCapsule=join(dimensions,resolved.getVideoTrack()==null?null:resolved.getVideoTrack().format);}renderTechnical();});
    }catch(Exception failure){handler.post(()->{if(!isDestroyed()){technical.removeAllViews();chip(technical,getString(com.archos.mediacenter.video.R.string.cinema_file_information_unavailable));}});}});}
    private void play(boolean beginning){
        if (CinemaAddonPicker.requiresSources(video)) {
            CinemaAddonPicker.show(this, io, handler, video, publicMetadata, 0, 0);
            return;
        }
        long r=effectiveResume();
        int resumeMode=beginning||r<=0?PlayerActivity.RESUME_NO:PlayerActivity.RESUME_FROM_LAST_POS;
        int resumePos=beginning?0:(int)Math.max(0,r);
        if(video instanceof Episode ep && seasonEpisodes!=null && !seasonEpisodes.isEmpty()){
            PlayUtils.startEpisodeForResult(this,ep,seasonEpisodes,showTitle,resumeMode,false,resumePos,-1,REQ_EPISODE_PLAYER,showImdb);
            return;
        }
        PlayUtils.startVideo(this,video,resumeMode,false,resumePos,null,-1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EPISODE_PLAYER && resultCode == RESULT_OK && data != null && seasonEpisodes != null) {
            int newIdx = data.getIntExtra(PlayUtils.EXTRA_EPISODE_INDEX, -1);
            if (newIdx >= 0 && newIdx < seasonEpisodes.size() && newIdx != episodeIndex) {
                switchToEpisode(newIdx);
                return;
            }
        }
        reloadVideoFromDb();
    }
    private void loadTags(){
        io.execute(()->{
            try{
                BaseTags tags=video.getFullScraperTags(getApplicationContext());
                if (OpenMetadata.enabled(this)) {
                    loadOpenMetadata(tags == null ? null : tags.getImdbId());
                    return;
                }
                if(tags==null){handler.post(this::showFallbackTitle);return;}
                List<ScraperImage> backgrounds=tags.getAllBackdropsInDb(getApplicationContext());
                Uri image=null;if(backgrounds!=null&&!backgrounds.isEmpty()){ScraperImage b=backgrounds.get(0);String path=b.getLargeFile();image=path!=null&&new File(path).isFile()?Uri.fromFile(new File(path)):b.getLargeUrl()==null?null:Uri.parse(b.getLargeUrl());}
                Uri verifiedTrailer=null;
                // Legacy records lack an official flag. Only the verified TMDB
                // response below may expose the new trailer button.
                Uri art=image,official=verifiedTrailer;
                handler.post(()->{
                    if(isDestroyed()||isFinishing())return;
                    if(art!=null)Picasso.get().load(art).resize(1280,720).centerCrop().into(backdrop);
                    if(tags.getPlot()!=null)description.setText(tags.getPlot());
                    if(tags.getDirectorsFormatted()!=null)director.setText(getString(com.archos.mediacenter.video.R.string.cinema_directed_by)+tags.getDirectorsFormatted());
                    if (official != null) { trailer = official; trailerButton.setVisibility(View.VISIBLE); }
                    String actors=tags.getActorsFormatted();
                    if(actors!=null&&!actors.isBlank())for(String actor:actors.split(",")){if(cast.getChildCount()>=16)break;actor(actor.trim());}
                });
                CinemaArtwork.Art fetched=null;
                if(video instanceof Movie movie)fetched=CinemaArtwork.load(getApplicationContext(),movie.getOnlineId(),false,MetadataLanguage.resolve(this));
                else if(video instanceof com.archos.mediacenter.video.browser.adapters.object.Episode episode&&tags instanceof com.archos.mediascraper.EpisodeTags episodeTags){
                    com.archos.mediascraper.ShowTags show=com.archos.mediascraper.TagsFactory.buildShowTags(getApplicationContext(),episodeTags.getShowId());
                    if(show!=null)fetched=CinemaArtwork.loadEpisode(getApplicationContext(),show.getOnlineId(),episode.getSeasonNumber(),episode.getEpisodeNumber(),MetadataLanguage.resolve(this));
                }
                if(fetched!=null){
                    CinemaArtwork.Art enriched=fetched;
                    if(enriched!=null)handler.post(()->{
                        if(isDestroyed()||isFinishing())return;
                        if(enriched.title()!=null&&!enriched.title().isBlank()){nameView.setText(enriched.title());nameView.setVisibility(View.VISIBLE);}
                        if(enriched.director()!=null&&!enriched.director().isBlank())director.setText(getString(com.archos.mediacenter.video.R.string.cinema_directed_by)+enriched.director());
                        if(enriched.logo()!=null&&!(video instanceof com.archos.mediacenter.video.browser.adapters.object.Episode)){logo.setVisibility(View.VISIBLE);Picasso.get().load(enriched.logo()).resize(dp(this,360),dp(this,100)).centerInside().into(logo,new com.squareup.picasso.Callback(){
                            @Override public void onSuccess(){if(!isDestroyed())nameView.setVisibility(View.GONE);}
                            @Override public void onError(Exception e){if(!isDestroyed())logo.setVisibility(View.GONE);}
                        });}
                        if(enriched.backdrop()!=null)Picasso.get().load(enriched.backdrop()).resize(1280,720).centerCrop().into(backdrop);
                        if(enriched.overview()!=null&&!enriched.overview().isBlank())description.setText(enriched.overview());
                        detailsArt=enriched;refreshInfo();applyPlayIcon();
                        if(enriched.trailer()!=null){trailer=Uri.parse(enriched.trailer());trailerButton.setVisibility(View.VISIBLE);}
                        if(!enriched.cast().isEmpty()){cast.removeAllViews();for(CinemaArtwork.Person person:enriched.cast())actorPhoto(person);}
                    });
                    String imdb=enriched.imdbId();if(imdb==null||imdb.isBlank())imdb=tags.getImdbId();
                    CinemaImdbRatings.Rating local=null;CinemaOmdbRatings.Scores remote=null;
                    try{local=CinemaImdbRatings.lookup(getApplicationContext(),imdb);}catch(Exception ignored){}
                    try{remote=CinemaOmdbRatings.load(getApplicationContext(),imdb);}catch(Exception ignored){}
                    CinemaImdbRatings.Rating score=local;CinemaOmdbRatings.Scores extra=remote;
                    handler.post(()->{if(isDestroyed()||isFinishing())return;
                        resolvedImdb=CinemaRatingBadge.imdbValue(score,extra);
                        resolvedImdbVotes=score!=null?score.votes():extra==null?0:extra.votes();
                        resolvedCritics=extra!=null?extra.critics():null;
                        refreshInfo();
                    });
                    if(video instanceof Movie movie&&enriched.trailer()==null&&!"en".equals(getResources().getConfiguration().locale.getLanguage())){
                        CinemaArtwork.Art english=CinemaArtwork.load(getApplicationContext(),movie.getOnlineId(),false,"en");
                        if(english!=null&&english.trailer()!=null)handler.post(()->{
                            if(!isDestroyed()&&!isFinishing()){trailer=Uri.parse(english.trailer());trailerButton.setVisibility(View.VISIBLE);}
                        });
                    }
                }
            }catch(Exception ignored){/* Missing scraper data must not block playback. */}finally{handler.post(this::showFallbackTitle);}
        });
    }
    private void loadOpenMetadata(String imdb) {
        CinemaPosters.Query query = CinemaPosters.query(video);
        if (query.show()) imdb = (showImdb != null && !showImdb.isBlank()) ? showImdb : null;
        int s = video instanceof com.archos.mediacenter.video.browser.adapters.object.Episode ep ? ep.getSeasonNumber() : 0;
        int e = video instanceof com.archos.mediacenter.video.browser.adapters.object.Episode ep ? ep.getEpisodeNumber() : 0;
        String fileUri = video.getFileUri() != null ? video.getFileUri().toString() : null;
        OpenMetadata.Query oq = new OpenMetadata.Query(query.title(), query.year(), query.show());
        BaseTags fallbackTags = video.getFullScraperTags(getApplicationContext());
        String localActors = fallbackTags != null ? fallbackTags.getActorsFormatted() : null;

        OpenMetadata.Item cached = OpenMetadata.getCachedOnly(getApplicationContext(), oq, imdb);
        if (cached != null) {
            int yc = query.year();
            try { if (cached.year() != null && !cached.year().isBlank()) yc = Integer.parseInt(cached.year()); } catch (NumberFormatException ignored) {}
            CinemaWatchStore.saveForBase(getApplicationContext(), video, cached.imdb(),
                    video instanceof Movie m ? m.getOnlineId() : 0L, cached.title(), yc,
                    cached.poster(), cached.backdrop(), cached.attribution());
            Double knownImdb = "IMDb".equals(cached.ratingSource()) ? cached.score() : null;
            Double knownMal = "MAL".equals(cached.ratingSource()) ? cached.score() : null;
            var cachedMulti = com.meedx.providers.MultiRatingsProvider.getCached(
                    getApplicationContext(), cached.imdb(), query.show(), knownImdb, knownMal);
            applyOpenMetadataToUi(cached, cachedMulti, localActors);
            boolean missingTmdbKeyed = cachedMulti != null && cachedMulti.tmdb == null
                    && !com.meedx.providers.ProviderKeys.get(getApplicationContext(), com.meedx.providers.ProviderKeys.TMDB).isBlank();
            if (cachedMulti == null || missingTmdbKeyed || (query.show() && cachedMulti.tvmaze == null)) {
                io.execute(() -> fetchMultiRatingsAsync(cached, query.show()));
            }
            final int fyc = yc;
            io.execute(() -> resolveOpenTrailerAsync(cached.title(), fyc, cached.imdb(), query.show()));
            return;
        }

        OpenMetadata.Item found = OpenMetadata.lookup(getApplicationContext(), oq, imdb, fileUri, s, e);
        if (found == null) {
            if (localActors != null && !localActors.isBlank()) {
                handler.post(() -> {
                    if (isDestroyed() || isFinishing() || cast.getChildCount() > 0) return;
                    for (String actorName : localActors.split(",")) {
                        if (cast.getChildCount() >= 16) break;
                        actor(actorName.trim());
                    }
                });
            }
            return;
        }
        int y = query.year();
        try { if (found.year() != null && !found.year().isBlank()) y = Integer.parseInt(found.year()); } catch (NumberFormatException ignored) {}
        CinemaWatchStore.saveForBase(getApplicationContext(), video, found.imdb(),
                video instanceof Movie m ? m.getOnlineId() : 0L, found.title(), y,
                found.poster(), found.backdrop(), found.attribution());
        Double knownImdb = "IMDb".equals(found.ratingSource()) ? found.score() : null;
        Double knownMal = "MAL".equals(found.ratingSource()) ? found.score() : null;
        var cachedMulti = com.meedx.providers.MultiRatingsProvider.getCached(
                getApplicationContext(), found.imdb(), query.show(), knownImdb, knownMal);
        applyOpenMetadataToUi(found, cachedMulti, localActors);
        io.execute(() -> fetchMultiRatingsAsync(found, query.show()));
        final int finalY = y;
        io.execute(() -> resolveOpenTrailerAsync(found.title(), finalY, found.imdb(), query.show()));
    }

    private void resolveOpenTrailerAsync(String titleStr, int year, String imdbId, boolean isShow) {
        String ytUrl = com.meedx.providers.OpenTrailersProvider.resolveTrailerUrl(
                getApplicationContext(), titleStr, year, imdbId, isShow);
        if (ytUrl != null && !ytUrl.isBlank()) {
            handler.post(() -> {
                if (!isDestroyed() && !isFinishing()) {
                    trailer = Uri.parse(ytUrl);
                    trailerButton.setVisibility(View.VISIBLE);
                }
            });
        }
    }

    private void launchTrailer() {
        CinemaPosters.Query q = CinemaPosters.query(video);
        String searchTitle = publicMetadata != null && !publicMetadata.title().isBlank() ? publicMetadata.title()
                : (detailsArt != null && detailsArt.title() != null ? detailsArt.title() : q.title());
        int year = q.year();
        try {
            if (publicMetadata != null && !publicMetadata.year().isBlank()) {
                year = Integer.parseInt(publicMetadata.year().replaceAll("[^0-9]", ""));
            }
        } catch (Exception ignored) {}
        CinemaYouTubeLauncher.openUriOrSearch(this, trailer, searchTitle, year);
    }

    private void applyOpenMetadataToUi(OpenMetadata.Item item,
                                       com.meedx.providers.MultiRatingsProvider.Ratings initialMulti,
                                       String localActors) {
        handler.post(() -> {
            if (isDestroyed() || isFinishing()) return;
            publicMetadata = item;
            if ("IMDb".equals(item.ratingSource())) resolvedImdb = item.score();
            if (initialMulti != null) multiRatings = initialMulti;
            if (!(video instanceof Episode)) {
                nameView.setText(item.title());
            } else {
                nameView.setText(formatHeaderTitle(video));
            }
            if (!item.plot().isBlank() && !(video instanceof Episode)) {
                description.setText(item.plot() + "\n" + item.attribution());
            }
            nameView.setVisibility(View.VISIBLE);
            String image = item.backdrop() == null ? item.poster() : item.backdrop();
            if (image != null) Picasso.get().load(image).resize(1280,720).centerCrop().into(backdrop);
            if (video instanceof Episode ep && item.imdb() != null && !item.imdb().isBlank()) {
                final Episode targetEp = ep;
                io.execute(() -> {
                    for (OpenMetadata.EpisodeMeta em : OpenMetadata.fetchCinemetaEpisodes(getApplicationContext(), item.imdb())) {
                        if (em.season() == targetEp.getSeasonNumber() && em.episode() == targetEp.getEpisodeNumber()) {
                            handler.post(() -> {
                                if (isDestroyed() || isFinishing() || video != targetEp) return;
                                String baseShow = (showTitle != null && !showTitle.isBlank()) ? showTitle : item.title();
                                String code = String.format(Locale.US, "S%02d E%02d", targetEp.getSeasonNumber(), targetEp.getEpisodeNumber());
                                String epTitle = !em.title().isBlank() ? code + " — " + em.title() : code;
                                nameView.setText((baseShow != null && !baseShow.isBlank() ? baseShow + " · " : "") + epTitle);
                                if (!em.overview().isBlank()) description.setText(em.overview());
                                else if (!item.plot().isBlank()) description.setText(item.plot());
                                if (!em.thumbnail().isBlank()) {
                                    Picasso.get().load(em.thumbnail()).resize(1280, 720).centerCrop().into(backdrop);
                                }
                                if (em.runtimeMinutes() != null && em.runtimeMinutes() > 0 && measuredDuration <= 0) {
                                    measuredDuration = em.runtimeMinutes() * 60_000L;
                                    applyPlayIcon();
                                }
                                refreshInfo();
                            });
                            break;
                        }
                    }
                });
            }
            if (!item.cast().isEmpty()) {
                cast.removeAllViews();
                for (OpenMetadata.CastMember cm : item.cast()) {
                    actorPhoto(new CinemaArtwork.Person(cm.name(), cm.role(), cm.image()), cm.personId());
                }
            } else if (localActors != null && !localActors.isBlank() && cast.getChildCount() == 0) {
                for (String actorName : localActors.split(",")) {
                    if (cast.getChildCount() >= 16) break;
                    actor(actorName.trim());
                }
            }
            refreshInfo();
        });
    }

    private void fetchMultiRatingsAsync(OpenMetadata.Item item, boolean isShow) {
        Double knownImdb = "IMDb".equals(item.ratingSource()) ? item.score() : null;
        Double knownMal = "MAL".equals(item.ratingSource()) ? item.score() : null;
        var fetchedMulti = com.meedx.providers.MultiRatingsProvider.fetch(
                getApplicationContext(), item.imdb(), isShow, knownImdb, knownMal);
        if (item.imdb() != null && !item.imdb().isBlank()) {
            CinemaImdbRatings.Rating local = null;
            CinemaOmdbRatings.Scores remote = null;
            try { local = CinemaImdbRatings.lookup(getApplicationContext(), item.imdb()); } catch (Exception ignored) {}
            try { remote = CinemaOmdbRatings.load(getApplicationContext(), item.imdb()); } catch (Exception ignored) {}
            CinemaImdbRatings.Rating fl = local;
            CinemaOmdbRatings.Scores fr = remote;
            handler.post(() -> {
                if (isDestroyed() || isFinishing()) return;
                if (resolvedImdb == null) resolvedImdb = CinemaRatingBadge.imdbValue(fl, fr);
                if (fr != null) {
                    if (fr.critics() != null) resolvedCritics = fr.critics();
                    if (fr.metacritic() != null) resolvedMetacritic = fr.metacritic();
                }
                refreshInfo();
            });
        }
        handler.post(() -> {
            if (isDestroyed() || isFinishing()) return;
            multiRatings = fetchedMulti;
            refreshInfo();
        });
    }

    private void showFallbackTitle(){if(!isDestroyed()&&nameView.getVisibility()==View.INVISIBLE){nameView.setText(video.getName());nameView.setVisibility(View.VISIBLE);}}
    private View settingsNav;
    private void lockCastFocus(View item){
        item.setOnKeyListener((v,k,e)->{
            if(e.getAction()!=android.view.KeyEvent.ACTION_DOWN)return k==android.view.KeyEvent.KEYCODE_DPAD_RIGHT||k==android.view.KeyEvent.KEYCODE_DPAD_LEFT;
            int idx=cast.indexOfChild(v);
            int logical=k;
            if(rtl){
                if(k==android.view.KeyEvent.KEYCODE_DPAD_LEFT)logical=android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
                else if(k==android.view.KeyEvent.KEYCODE_DPAD_RIGHT)logical=android.view.KeyEvent.KEYCODE_DPAD_LEFT;
            }
            if(logical==android.view.KeyEvent.KEYCODE_DPAD_RIGHT){if(idx+1<cast.getChildCount())return cast.getChildAt(idx+1).requestFocus();return true;}
            if(logical==android.view.KeyEvent.KEYCODE_DPAD_LEFT){if(idx>0)return cast.getChildAt(idx-1).requestFocus();return true;}
            return false;
        });
    }
    private void actor(String name){
        if(name.isEmpty())return;
        String cleanName = OpenMetadata.cleanPersonName(name);
        actorPhoto(new CinemaArtwork.Person(cleanName.isEmpty() ? name : cleanName, null, null), null);
    }
    private void actorPhoto(CinemaArtwork.Person person){
        actorPhoto(person, null);
    }
    private void actorPhoto(CinemaArtwork.Person person, String personId){
        String displayName = person.name() == null ? "" : person.name().trim();
        if (displayName.isEmpty()) return;
        String cleanName = OpenMetadata.cleanPersonName(displayName);
        if (cleanName.isEmpty()) cleanName = displayName;
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER_HORIZONTAL);box.setClipChildren(false);box.setClipToPadding(false);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(this,115),-2);p.setMarginEnd(dp(this,14));p.topMargin=dp(this,14);cast.addView(box,p);
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
        box.addView(avatarWrap,new LinearLayout.LayoutParams(dp(this,96),dp(this,96)));
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
        TextView text=text(this,12,false);text.setText(person.role()!=null&&!person.role().isBlank()?cleanName+"\n"+person.role():cleanName);text.setMaxLines(3);text.setGravity(Gravity.CENTER);text.setPadding(0,dp(this,6),0,0);box.addView(text);box.setFocusable(true);box.setClickable(true);lockCastFocus(box);box.setContentDescription(cleanName);box.setOnFocusChangeListener((v,focused)->{avatarWrap.setForeground(focused?castRing():null);avatarWrap.animate().scaleX(focused?1.06f:1).scaleY(focused?1.06f:1).setDuration(CinemaTokens.FOCUS_MS).start();});
        final String actorClean = cleanName;
        box.setOnClickListener(v->CinemaActorActivity.open(this,actorClean,person.role(),resolvedPhoto[0],personId));
    }
    private void reloadVideoFromDb(){
        if(video==null||!video.isIndexed()||playButton==null)return;
        long id=video.getId();
        io.execute(()->{try(android.database.Cursor cursor=getContentResolver().query(com.archos.mediaprovider.video.VideoStore.Video.Media.EXTERNAL_CONTENT_URI,com.archos.mediacenter.video.browser.loader.VideoLoader.mProjection,"_id=?",new String[]{Long.toString(id)},null)){if(cursor!=null&&cursor.moveToFirst()){var mapper=new com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper();mapper.bindColumns(cursor);Video refreshed=(Video)mapper.bind(cursor);handler.post(()->{if(isDestroyed())return;video=refreshed;watched=video.isWatched()||effectiveResume()==PlayerActivity.LAST_POSITION_END;applyPlayIcon();updateLabels();refreshInfo();});}}catch(Exception ignored){}});
    }
    @Override public void onResume(){super.onResume();if(video==null||playButton==null)return;watched=video.isWatched()||effectiveResume()==PlayerActivity.LAST_POSITION_END;applyPlayIcon();updateLabels();refreshInfo();reloadVideoFromDb();handler.postDelayed(()->{if(!isDestroyed())reloadVideoFromDb();},450);}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);io.shutdownNow();if(backdrop!=null)Picasso.get().cancelRequest(backdrop);if(logo!=null)Picasso.get().cancelRequest(logo);super.onDestroy();}
}

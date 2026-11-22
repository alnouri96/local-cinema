package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import androidx.preference.PreferenceManager;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.browser.adapters.object.Episode;
import com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper;
import com.archos.mediascraper.BaseTags;
import org.json.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Worker-only automatic download. Requires verified identity and release evidence. */
public final class CinemaAutoSubtitles {
    private CinemaAutoSubtitles(){}
    public record Outcome(SubtitleStorage.Saved saved,String reason){}
    private static Outcome skip(String reason){return new Outcome(null,reason);}
    public static boolean arabic(String code,String name){
        String c=code==null?"":code.trim().toLowerCase(Locale.ROOT);
        String n=name==null?"":name.toLowerCase(Locale.ROOT);
        return c.equals("ar")||c.equals("ara")||c.startsWith("ar-")||n.contains("arabic")||n.contains("العربية")||n.contains("عربي");
    }
    public static String preferredLanguage(Context context){
        return PreferenceManager.getDefaultSharedPreferences(context).getString("subsource_language","arabic");
    }

    static String languageLabel(String language){
        if(language.isEmpty())return "All languages / كل اللغات";
        if("arabic".equalsIgnoreCase(language))return "العربية";
        return Character.toUpperCase(language.charAt(0))+language.substring(1);
    }

    static void chooseLanguage(Context context,String current,boolean allowAll,java.util.function.Consumer<String> selected){
        List<String> values=new ArrayList<>(List.of("arabic","english","french","spanish","german","turkish","persian","kurdish","chinese","japanese","korean"));
        if(allowAll)values.add(1,"");
        if(!values.contains(current))values.add(current);
        String[] labels=new String[values.size()+1];
        for(int n=0;n<values.size();n++)labels[n]=languageLabel(values.get(n));
        labels[values.size()]=CinemaUi.label(context,"لغة أخرى…","Other language…");
        CinemaUi.dialog(context).setTitle(CinemaUi.label(context,"لغة الترجمة","Subtitle language"))
                .setItems(labels,(dialog,which)->{
                    if(which<values.size()){
                        selected.accept(values.get(which));
                        return;
                    }
                    android.widget.EditText input=new android.widget.EditText(context);
                    input.setSingleLine(true);
                    input.setHint(com.archos.mediacenter.video.R.string.cinema_subtitle_custom_language_hint);
                    CinemaUi.dialog(context).setTitle(labels[which]).setView(input)
                            .setNegativeButton(android.R.string.cancel,null)
                            .setPositiveButton(android.R.string.ok,(d,w)->{
                                String value=input.getText().toString().trim().toLowerCase(Locale.ROOT);
                                if(value.matches("[a-z][a-z -]{1,39}"))selected.accept(value);
                            }).show();
                }).show();
    }

    public static boolean matchesPreferredLanguage(Context context,String code,String name){
        String preferred=preferredLanguage(context);
        if("arabic".equals(preferred))return arabic(code,name);
        if(name!=null&&name.toLowerCase(Locale.ROOT).contains(preferred))return true;
        if(code==null||code.isBlank())return false;
        String display=Locale.forLanguageTag(code.replace('_','-')).getDisplayLanguage(Locale.ENGLISH);
        return preferred.equalsIgnoreCase(display)||preferred.equalsIgnoreCase(code);
    }
    public static long selected(Context c,Uri uri){return PreferenceManager.getDefaultSharedPreferences(c).getLong(SubtitleStorage.preferenceKey(uri)+"_subsource_id",-1);}
    public static void remember(Context c,Uri uri,long id){PreferenceManager.getDefaultSharedPreferences(c).edit().putLong(SubtitleStorage.preferenceKey(uri)+"_subsource_id",id).commit();}
    public static synchronized Outcome fetch(Context c,Uri uri,long videoId,BooleanSupplier current) throws Exception {
        if(!current.getAsBoolean()||!PreferenceManager.getDefaultSharedPreferences(c).getBoolean("subsource_enabled",true))return skip("disabled-or-cancelled");
        try(SubtitleStorage storage=new SubtitleStorage(c)){if(storage.lookup(uri)!=null)return skip("already-bound");}
        List<String> keys=new ApiKeyStore(c).read();
        Video video=null;
        try(Cursor cursor=c.getContentResolver().query(com.archos.mediaprovider.video.VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                com.archos.mediacenter.video.browser.loader.VideoLoader.mProjection,"_id=?",new String[]{Long.toString(videoId)},null)){
            if(cursor!=null&&cursor.moveToFirst()){VideoCursorMapper mapper=new VideoCursorMapper();mapper.bindColumns(cursor);video=(Video)mapper.bind(cursor);}
        }
        if(video==null)return skip("video-not-indexed");
        int season=-1,episode=-1;BaseTags tags=video.getFullScraperTags(c);String imdb=tags==null?null:tags.getImdbId();
        if(video instanceof Episode ep){season=ep.getSeasonNumber();episode=ep.getEpisodeNumber();}
        if(tags instanceof com.archos.mediascraper.EpisodeTags ep){var show=com.archos.mediascraper.TagsFactory.buildShowTags(c,ep.getShowId());if(show!=null){imdb=show.getImdbId();if(imdb==null||imdb.isBlank()){var art=CinemaArtwork.load(c,show.getOnlineId(),true,"en");if(art!=null)imdb=art.imdbId();}}}
        if((imdb==null||imdb.isBlank())&&video instanceof com.archos.mediacenter.video.browser.adapters.object.Movie film){var art=CinemaArtwork.load(c,film.getOnlineId(),false,"en");if(art!=null)imdb=art.imdbId();}
        if(imdb==null||!imdb.matches("tt[0-9]+")){
            var q = CinemaPosters.query(video);
            var open = com.meedx.providers.OpenMetadata.lookup(c, new com.meedx.providers.OpenMetadata.Query(q.title(), q.year(), q.show()), null, null, 0, 0);
            if(open!=null)imdb=open.imdb();
        }
        if(imdb==null||!imdb.matches("tt[0-9]+"))return skip("no-verified-imdb-id");
        if(keys.isEmpty()){
            var freeSubs = com.meedx.providers.ZeroConfigSubtitles.search(imdb, season >= 0 || episode >= 0, season, episode, preferredLanguage(c));
            for(var item : freeSubs){
                if(!current.getAsBoolean())return skip("cancelled");
                try{
                    byte[] raw = com.meedx.providers.ZeroConfigSubtitles.download(item.url);
                    byte[] clean = SubtitleStorage.utf8(raw);
                    try(SubtitleStorage storage=new SubtitleStorage(c)){
                        var saved = storage.save(uri, new SubtitleStorage.Entry("subtitle.srt", clean), 0L, current);
                        return new Outcome(saved, "zero-config-free");
                    }
                }catch(Exception ignored){}
            }
            return skip("no-free-subtitle-match");
        }
        SubsourceClient api=new SubsourceClient(c,keys.get(0));
        JSONArray titles=api.json("/movies/search?searchType=imdb&imdb="+SubsourceClient.encode(imdb)+(season>=0?"&season="+season:"")).getJSONArray("data");
        long movie=-1;int count=0;Set<Long> seen=new HashSet<>();
        for(int i=0;i<titles.length();i++){JSONObject t=titles.getJSONObject(i);long id=t.optLong("movieId",-1);if(id>=0&&seen.add(id)&&imdb.equals(t.optString("imdbId"))&&(season<0||t.optInt("season",-1)==season)){movie=id;count++;}}
        if(count!=1)return skip("ambiguous-or-missing-title");
        String language=preferredLanguage(c);
        long best=-1;String bestRelease="";SubtitleMatch.Result rank=null;
        for(int page=1,pages=1;page<=pages;page++){
            if(!current.getAsBoolean())return skip("cancelled");
            JSONObject response=api.json("/subtitles?movieId="+movie+"&language="+SubsourceClient.encode(language)+"&limit=100&page="+page+"&sort=rating");
            JSONObject pagination=response.optJSONObject("pagination");pages=pagination==null?1:pagination.optInt("pages",1);if(pages>10)return skip("too-many-pages-review-manually");
            JSONArray subs=response.getJSONArray("data");
            for(int i=0;i<subs.length();i++){
                JSONObject sub=subs.getJSONObject(i);if(!language.equalsIgnoreCase(sub.optString("language")))continue;
                JSONArray releases=sub.optJSONArray("releaseInfo");if(releases==null)releases=new JSONArray().put(sub.optString("releaseInfo",""));
                JSONObject votes=sub.optJSONObject("rating");
                for(int r=0;r<releases.length();r++){
                    SubtitleMatch.Result candidate=SubtitleMatch.rank(video.getFileNameWithExtension(),releases.optString(r),true,season,episode,null,null,null,null,votes==null?0:votes.optInt("good"),votes==null?0:votes.optInt("bad"));
                    if(!candidate.episodeConflict()&&(candidate.confidence()!=SubtitleMatch.Confidence.UNCERTAIN)&&(rank==null||candidate.score()>rank.score())){best=sub.optLong("subtitleId",-1);rank=candidate;bestRelease=releases.optString(r);}
                }
            }
        }
        if(best<0||!current.getAsBoolean())return skip("no-confident-release-or-cancelled");
        List<SubtitleStorage.Entry> entries=SubtitleStorage.unpack(api.download(best));
        SubtitleStorage.Entry chosen=null;
        if(entries.size()==1){
            chosen=entries.get(0);
            var fileMatch=SubtitleMatch.rank(video.getFileNameWithExtension(),chosen.name(),true,season,episode,null,null,null,null,0,0);
            if(fileMatch.episodeConflict()||fileMatch.score()<0)return skip("archive-identity-conflict");
        }
        else {int score=Integer.MIN_VALUE;boolean tie=false;
            for(var entry:entries){var match=SubtitleMatch.rank(video.getFileNameWithExtension(),entry.name(),true,season,episode,null,null,null,null,0,0);if(match.episodeConflict())continue;if(match.score()>score){chosen=entry;score=match.score();tie=false;}else if(match.score()==score)tie=true;}
            if(tie||score<40)return skip("ambiguous-archive");
        }
        if(chosen==null||!current.getAsBoolean())return skip("cancelled");
        try(SubtitleStorage storage=new SubtitleStorage(c)){var saved=storage.save(uri,chosen,best,current);remember(c,uri,best);PreferenceManager.getDefaultSharedPreferences(c).edit().putString(SubtitleStorage.preferenceKey(uri)+"_subsource_release",bestRelease).commit();return new Outcome(saved,saved.besideVideo()?"downloaded-beside-video":"downloaded-private-fallback");}
    }
}

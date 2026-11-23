package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Episode;
import com.archos.mediacenter.video.browser.adapters.object.NonIndexedVideo;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import com.archos.mediacenter.video.leanback.details.VideoDetailsFragment;
import com.archos.mediascraper.BaseTags;
import com.meedx.providers.ApiKeyStore;
import com.meedx.providers.SubtitleMatch;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

public class SubsourceSearchActivity extends LeanbackActivity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService openIo = Executors.newSingleThreadExecutor();
    private LinearLayout openResults;
    private boolean bothProviders;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Video video;private String imdb;private int season=-1,episode=-1,year=-1;
    private Double videoFps;
    private String searchTitle;
    private String downloadRelease;
    private String language;
    private TextView languageButton;
    private EditText query;private TextView status;private LinearLayout results;
    private SubsourceClient api;private boolean busy;private int requestGeneration;
    private final List<Match> matches=new ArrayList<>();
    private record Match(long id,String release,String language,SubtitleMatch.Result rank){}
    private String l(String ar,String en){return label(this,ar,en);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);bothProviders=getIntent().getBooleanExtra("both_providers",false);language=CinemaAutoSubtitles.preferredLanguage(this);video=(Video)getIntent().getSerializableExtra(VideoDetailsFragment.EXTRA_VIDEO);
        ScrollView scroll=new ScrollView(this);scroll.setBackground(CinemaUi.surface());LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(this,CinemaTokens.UTILITY_GUTTER),dp(this,CinemaTokens.SAFE),dp(this,CinemaTokens.UTILITY_GUTTER),dp(this,CinemaTokens.SAFE));scroll.addView(content);setContentView(scroll);
        TextView title=text(this,CinemaTokens.PAGE_TITLE,true);title.setText(bothProviders ? SubtitleSources.label(this) : l("بحث عن الترجمة","Search Subtitles"));content.addView(title);
        status=text(this,CinemaTokens.LABEL,false);status.setPadding(0,dp(this,12),0,dp(this,12));content.addView(status);
        query=new EditText(this);query.setTextColor(android.graphics.Color.WHITE);query.setSingleLine(true);query.setHintTextColor(0xff8899aa);query.setHint(l("اسم الفيلم أو المسلسل","Movie or show title"));content.addView(CinemaPhoneLink.pasteBadge(this));content.addView(query);
        languageButton=button(content,languageLabel(),this::chooseLanguage);
        button(content,l("بحث","Search"),this::search);
        button(content,SubtitleSources.title(this),()->startActivity(new Intent(this,SubsourceSettingsActivity.class)));
        openResults = new LinearLayout(this);
        openResults.setOrientation(LinearLayout.VERTICAL);
        content.addView(openResults);
        results=new LinearLayout(this);results.setOrientation(LinearLayout.VERTICAL);content.addView(results);
        status.setText(l("جارٍ قراءة معلومات الفيديو…","Reading video information…"));
        io.execute(()->{
            try{
                if(video==null){long id=getIntent().getLongExtra("video_id",-1);
                    if(id>=0){try(Cursor c=getContentResolver().query(com.archos.mediaprovider.video.VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                            com.archos.mediacenter.video.browser.loader.VideoLoader.mProjection,"_id=?",new String[]{Long.toString(id)},null)){
                        if(c!=null&&c.moveToFirst()){VideoCursorMapper m=new VideoCursorMapper();m.bindColumns(c);video=(Video)m.bind(c);}}}
                    if(video==null&&getIntent().getData()!=null)video=new NonIndexedVideo(getIntent().getData(),getIntent().getData(),getIntent().getStringExtra("title"),null);
                }
                if(video==null)throw new Exception();
                String intentTitle=cleanTitle(getIntent().getStringExtra("title"));
                String intentHeading=cleanTitle(getIntent().getStringExtra("addon_heading"));
                String intentImdb=cleanTitle(getIntent().getStringExtra("imdb_id"));
                int intentSeason=getIntent().getIntExtra("season",-1);
                int intentEpisode=getIntent().getIntExtra("episode",-1);
                if(intentImdb.matches("tt[0-9]+"))imdb=intentImdb;
                if(intentSeason>0)season=intentSeason;
                if(intentEpisode>0)episode=intentEpisode;
                if(video instanceof Movie movie)year=movie.getYear();
                if(video instanceof Episode ep){season=ep.getSeasonNumber();episode=ep.getEpisodeNumber();}
                BaseTags tags=video.getFullScraperTags(getApplicationContext());if(tags!=null&&(imdb==null||imdb.isBlank()))imdb=tags.getImdbId();
                searchTitle=cleanTitle(video.getName());
                if(video instanceof Movie movie&&!arabic(this))try{CinemaArtwork.Art art=CinemaArtwork.load(getApplicationContext(),movie.getOnlineId(),false,"en");if(art!=null&&!cleanTitle(art.title()).isEmpty())searchTitle=cleanTitle(art.title());}catch(Exception ignored){}
                if(tags instanceof com.archos.mediascraper.EpisodeTags ep){
                    com.archos.mediascraper.ShowTags show=com.archos.mediascraper.TagsFactory.buildShowTags(getApplicationContext(),ep.getShowId());
                    if(show!=null){if(imdb==null||imdb.isBlank())imdb=show.getImdbId();if(!cleanTitle(show.getTitle()).isEmpty())searchTitle=cleanTitle(show.getTitle());}
                }
                Uri uri=video.getFileUri();
                boolean isHttp=uri!=null&&("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()));
                if(!video.hasScraperData()&&!isHttp){
                    com.archos.mediascraper.preprocess.SearchInfo parsed=com.archos.mediascraper.preprocess.SearchPreprocessor.instance().parseFileBased(uri,uri);
                    if(parsed instanceof com.archos.mediascraper.preprocess.MovieSearchInfo movie){String n=cleanTitle(movie.getName());if(!n.isEmpty())searchTitle=n;try{year=Integer.parseInt(movie.getYear());}catch(Exception ignored){}}
                    if(parsed instanceof com.archos.mediascraper.preprocess.TvShowSearchInfo show){String n=cleanTitle(show.getShowName());if(!n.isEmpty())searchTitle=n;if(season<0)season=show.getSeason();if(episode<0)episode=show.getEpisode();}
                }
                if(!intentHeading.isEmpty()&&(searchTitle.isEmpty()||isHttp||"video".equalsIgnoreCase(searchTitle)))searchTitle=intentHeading;
                else if(!intentTitle.isEmpty()&&(searchTitle.isEmpty()||isHttp||"video".equalsIgnoreCase(searchTitle)))searchTitle=stripEpisodeSuffix(intentTitle);
                if(searchTitle.isEmpty())searchTitle=stripEpisodeSuffix(intentTitle);
                if(video.isIndexed())try(Cursor fps=getContentResolver().query(com.archos.mediaprovider.video.VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                        new String[]{com.archos.mediaprovider.video.VideoStore.Video.VideoColumns.ARCHOS_FRAMES_PER_THOUSAND_SECONDS},"_id=?",new String[]{Long.toString(video.getId())},null)){
                    if(fps!=null&&fps.moveToFirst()&&fps.getDouble(0)>0)videoFps=fps.getDouble(0)/1000.0;
                }catch(Exception ignored){/* Unknown frame rate is not treated as evidence. */}
                handler.post(()->{
                    if(isDestroyed())return;
                    query.setText(searchTitle);
                    query.setSelection(query.getText().length());
                    if(!searchTitle.isEmpty()||(imdb!=null&&imdb.matches("tt[0-9]+")))search();
                    else{status.setText(l("اكتب اسم الفيلم أو المسلسل ثم اضغط بحث","Type the movie or show title and press Search"));query.requestFocus();}
                });
            }catch(Exception e){message(l("تعذر قراءة معلومات الفيديو","Could not read video information"));}
        });
    }
    private static String cleanTitle(String raw){if(raw==null)return "";String t=raw.trim();return (t.isEmpty()||"null".equalsIgnoreCase(t))?"":t;}
    private static String stripEpisodeSuffix(String title){return title==null?"":title.replaceAll("(?i)\\s*S\\d{1,2}\\s*E\\d{1,3}.*$","").trim();}
    private TextView button(LinearLayout parent,String text,Runnable action){TextView t=CinemaUi.text(this,CinemaTokens.BODY,false);t.setText(text);t.setPadding(dp(this,16),dp(this,11),dp(this,16),dp(this,11));focusable(t);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(this,8);parent.addView(t,p);t.setOnClickListener(v->action.run());return t;}
    private String languageLabel(){
        return l("لغة البحث · ","Search language · ")+CinemaAutoSubtitles.languageLabel(language);
    }

    private void chooseLanguage(){
        if(busy)return;
        CinemaAutoSubtitles.chooseLanguage(this,language,true,selected->{
            language=selected;
            languageButton.setText(languageLabel());
            search();
        });
    }
    private void message(String text){handler.post(()->{if(!isDestroyed())status.setText(text);});}
    private void fail(Exception e){if(e instanceof SubsourceClient.ApiException a)message(a.userMessage(this));else message(l("تعذرت العملية. تحقق من الشبكة وصلاحية البيانات.","The operation failed. Check the connection and data."));}
    private void search(){
        if(busy||video==null)return;
        String title=query.getText().toString().trim();if(title.isEmpty())return;
        busy=true;results.removeAllViews();openResults.removeAllViews();status.setText(l("جارٍ البحث في المصادر المفتوحة والمحفوظة…","Searching open & configured subtitle sources…"));int token=++requestGeneration;
        searchZeroAndOpen(title, token);
        io.execute(()->{
            try{
                List<String> keys=new ApiKeyStore(this).read();
                if(keys.isEmpty()){
                    handler.post(()->{busy=false;});
                    return;
                }
                api=new SubsourceClient(getApplicationContext(),keys.get(0));
                boolean useImdb=imdb!=null&&imdb.matches("tt[0-9]+")&&title.equals(searchTitle);
                String path=useImdb?"/movies/search?searchType=imdb&imdb="+SubsourceClient.encode(imdb):"/movies/search?searchType=text&q="+SubsourceClient.encode(title);
                if(!useImdb&&year>0)path+="&year="+year;
                if(season>=0)path+="&season="+season;
                JSONArray found=api.json(path).getJSONArray("data");
                List<JSONObject> unique=new ArrayList<>();Set<Long> ids=new HashSet<>();
                for(int n=0;n<found.length();n++){JSONObject movie=found.getJSONObject(n);long id=movie.optLong("movieId",-1);if(id>=0&&ids.add(id)&&(!useImdb||imdb.equals(movie.optString("imdbId"))))unique.add(movie);}
                handler.post(()->{
                    if(isDestroyed()||token!=requestGeneration)return;
                    busy=false;
                    List<JSONObject> exact=new ArrayList<>();for(JSONObject candidate:unique){boolean identity=useImdb&&imdb!=null&&imdb.equals(candidate.optString("imdbId"));boolean rightSeason=season<0||candidate.optInt("season",-1)==season;if(identity&&rightSeason)exact.add(candidate);}
                    if(exact.size()==1){loadSubtitles(exact.get(0).optLong("movieId"),1,true);return;}
                    status.setText(unique.isEmpty()?l("لم تُعثر نتائج مطابقة","No matching titles found"):l("اختر العمل الصحيح والسنة","Choose the correct title and year"));
                    for(JSONObject movie:unique)button(results,movie.optString("title")+" · "+movie.optInt("releaseYear")+(movie.optInt("season",0)>0?" · S"+movie.optInt("season"):""),()->loadSubtitles(movie.optLong("movieId"),1,true));
                    if(results.getChildCount()>0)results.getChildAt(0).requestFocus();
                });
            }catch(Exception e){fail(e);handler.post(()->busy=false);}
        });
    }
    private void searchZeroAndOpen(String title, int token) {
        openResults.removeAllViews();
        String key = com.meedx.providers.ProviderKeys.get(this, com.meedx.providers.ProviderKeys.OPEN_SUBTITLES);
        String identity = title.equals(searchTitle) ? imdb : null;
        String chosenLanguage = language;
        boolean isSeries = season >= 0 || episode >= 0;
        openIo.execute(() -> {
            // 1. Keyless Zero-Account open subtitles (OpenSubtitles v3 + Wyzie)
            try {
                String resolvedImdb = identity;
                if (resolvedImdb == null || !resolvedImdb.matches("tt[0-9]+")) {
                    com.meedx.providers.OpenMetadata.Item found = com.meedx.providers.OpenMetadata.lookup(
                            new com.meedx.providers.OpenMetadata.Query(title, year > 0 ? year : 0, isSeries), null);
                    if (found != null && found.imdb() != null && found.imdb().matches("tt[0-9]+")) {
                        resolvedImdb = found.imdb();
                    }
                }
                List<com.meedx.providers.ZeroConfigSubtitles.Item> freeList =
                        com.meedx.providers.ZeroConfigSubtitles.search(resolvedImdb, isSeries, season, episode, chosenLanguage);
                handler.post(() -> {
                    if (isDestroyed() || isFinishing() || token != requestGeneration) return;
                    for (com.meedx.providers.ZeroConfigSubtitles.Item item : freeList) {
                        button(openResults, "[" + item.source + "] " + CinemaAutoSubtitles.languageLabel(item.language)
                                + "\n" + item.release, () -> downloadZeroConfig(item));
                    }
                    if (!freeList.isEmpty()) {
                        status.setText(l("نتائج مجانية جاهزة للتحميل الفوري (بدون حساب).",
                                "Free subtitles ready for instant download (Zero-Account)."));
                        if (openResults.getChildCount() > 0 && results.getChildCount() == 0) {
                            openResults.getChildAt(0).requestFocus();
                        }
                    }
                });
            } catch (Exception ignored) {}

            // 2. Optional user OpenSubtitles API results when key is configured
            if (!key.isEmpty()) {
                try {
                    List<OpenSubtitleSearch.Match> found = OpenSubtitleSearch.search(
                            key, title, identity, season, episode, chosenLanguage);
                    handler.post(() -> {
                        if (isDestroyed() || isFinishing() || token != requestGeneration) return;
                        for (OpenSubtitleSearch.Match match : found) {
                            button(openResults, "[OpenSubtitles API] " + CinemaAutoSubtitles.languageLabel(match.language())
                                    + "\n" + match.name(), () -> confirmOpen(match));
                        }
                    });
                } catch (Exception ignored) {}
            } else {
                handler.post(() -> {
                    if (isDestroyed() || isFinishing() || token != requestGeneration) return;
                    if (openResults.getChildCount() == 0 && results.getChildCount() == 0 && !busy) {
                        status.setText(l("لم يتم العثور على ترجمات مطابقة حالياً.", "No matching subtitles found."));
                    }
                });
            }
        });
    }

    private void downloadZeroConfig(com.meedx.providers.ZeroConfigSubtitles.Item item) {
        if (busy) return;
        busy = true;
        downloadRelease = item.release;
        status.setText(l("جارٍ تنزيل الترجمة…", "Downloading subtitle…"));
        openIo.execute(() -> {
            try {
                byte[] raw = com.meedx.providers.ZeroConfigSubtitles.download(item.url);
                byte[] clean = SubtitleStorage.utf8(raw);
                String safeName = item.release != null && item.release.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(srt|vtt|ass|ssa)$")
                        ? item.release : "subtitle.srt";
                handler.post(() -> {
                    busy = false;
                    if (!isDestroyed()) save(new SubtitleStorage.Entry(safeName, clean), 0L);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    busy = false;
                    if (!isDestroyed()) status.setText(l("تعذّر تنزيل الترجمة المختارة.", "Could not download selected subtitle."));
                });
            }
        });
    }

    private TextView textLabel(String message) {
        TextView view = text(this, CinemaTokens.LABEL, false);
        view.setText(message);
        return view;
    }

    private void confirmOpen(OpenSubtitleSearch.Match match) {
        CinemaUi.dialog(this).setTitle("[OpenSubtitles]").setMessage(match.name())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(l("تحميل", "Download"), (dialog, index) -> {
                    Intent intent = new Intent(this, com.archos.mediacenter.video.utils.SubtitlesDownloaderActivity2.class);
                    intent.putExtra(com.archos.mediacenter.video.utils.SubtitlesDownloaderActivity2.FILE_URL, video.getFilePath());
                    intent.putExtra("cinema_open_subtitle_id", match.id());
                    intent.putExtra("cinema_open_subtitle_name", match.name());
                    intent.putExtra("cinema_open_subtitle_language", match.language());
                    startActivity(intent);
                }).show();
    }

    private void loadSubtitles(long movieId,int page,boolean reset){
        if(busy)return;busy=true;status.setText(l("جارٍ قراءة الترجمات…","Loading subtitles…"));if(reset)matches.clear();
        String filter=language.isEmpty()?"":"&language="+SubsourceClient.encode(language);
        io.execute(()->{
            try{
                JSONObject response=api.json("/subtitles?movieId="+movieId+filter+"&limit=100&page="+page+"&sort=rating");JSONArray subs=response.getJSONArray("data");
                for(int n=0;n<subs.length();n++){
                    JSONObject sub=subs.getJSONObject(n);
                    String resultLanguage=sub.optString("language");
                    JSONArray releases=sub.optJSONArray("releaseInfo");if(releases==null)releases=new JSONArray().put(sub.optString("releaseInfo",""));
                    Match best=null;
                    for(int r=0;r<releases.length();r++){
                        String release=releases.optString(r,"");JSONObject votes=sub.optJSONObject("rating");
                        Double fps=null;try{double f=Double.parseDouble(sub.optString("framerate"));if(f>0)fps=f;}catch(Exception ignored){}
                        SubtitleMatch.Result rank=SubtitleMatch.rank(video.getFileNameWithExtension(),release,true,season,episode,videoFps,fps,null,null,votes==null?0:votes.optInt("good"),votes==null?0:votes.optInt("bad"));
                        Match candidate=new Match(sub.optLong("subtitleId",-1),release,resultLanguage,rank);if(!rank.episodeConflict()&&(best==null||rank.score()>best.rank.score()))best=candidate;
                    }
                    if(best!=null&&best.id>=0){Match add=best;matches.removeIf(m->m.id==add.id);matches.add(best);}
                }
                long selected=CinemaAutoSubtitles.selected(this,video.getFileUri());
                matches.sort(Comparator.comparingInt((Match m)->m.language.equals("ar")?1:0).reversed().thenComparing(Comparator.comparingInt((Match m)->m.id==selected?1:0).reversed()).thenComparing(Comparator.comparingInt((Match m)->m.rank.score()).reversed()));JSONObject pagination=response.optJSONObject("pagination");int pages=pagination==null?page:pagination.optInt("pages",page);
                List<Match> snapshot=new ArrayList<>(matches);
                Uri savedSelection;
                try(SubtitleStorage storage=new SubtitleStorage(this)){savedSelection=storage.lookup(video.getFileUri());}

                handler.post(()->{
                    if(isDestroyed())return;results.removeAllViews();status.setText(snapshot.isEmpty()?l("لا توجد ترجمة مطابقة لهذه الحلقة أو الفيلم","No matching subtitles for this episode or movie"):l("النتائج المحملة مرتبة حسب أدلة تطابق الإصدار. المعلومات الناقصة تعني توافقاً غير مؤكد.","Loaded results are ranked by release evidence. Missing information means uncertain compatibility."));
                    if(savedSelection!=null)button(results,"Selected subtitle · saved\n"+PreferenceManager.getDefaultSharedPreferences(this).getString(SubtitleStorage.preferenceKey(video.getFileUri())+"_subsource_release",savedSelection.getLastPathSegment()),()->{setResult(Activity.RESULT_OK,new Intent().setData(savedSelection));finish();});
                    for(Match m:snapshot)if(m.id!=selected||savedSelection==null)button(results,"[Subsource] "+(m.id==selected?"Selected · ":"")+confidence(m.rank.confidence())+" · "+CinemaAutoSubtitles.languageLabel(m.language)+"\n"+m.release,()->confirm(m));
                    if(page<pages)button(results,l("تحميل المزيد من النتائج","Load more results"),()->loadSubtitles(movieId,page+1,false));
                    if(results.getChildCount()>0)results.getChildAt(0).requestFocus();
                });
            }catch(Exception e){fail(e);}finally{handler.post(()->busy=false);}
        });
    }
    private String confidence(SubtitleMatch.Confidence c){return switch(c){case EXCELLENT->l("توافق ممتاز","Excellent match");case HIGH->l("توافق مرتفع","High match");case MEDIUM->l("توافق متوسط","Medium match");case UNCERTAIN->l("توافق غير مؤكد","Uncertain match");};}
    private void confirm(Match match){
        CinemaUi.dialog(this).setTitle(confidence(match.rank.confidence())).setMessage(match.release+"\n"+(match.rank.confidence()==SubtitleMatch.Confidence.UNCERTAIN?l("لا توجد معلومات كافية لتأكيد تطابق الإصدار.","Not enough information to confirm a release match."):""))
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(l("تحميل","Download"),(d,w)->download(match)).show();
    }
    private void download(Match match){
        downloadRelease=match.release;
        if(busy)return;busy=true;status.setText(l("جارٍ تنزيل الترجمة المختارة…","Downloading selected subtitle…"));
        io.execute(()->{try{
            List<SubtitleStorage.Entry> files=SubtitleStorage.unpack(api.download(match.id));
            handler.post(()->{if(isDestroyed())return;if(files.size()==1)save(files.get(0),match.id);else{
                String[] names=new String[files.size()];for(int n=0;n<files.size();n++)names[n]=files.get(n).name();
                CinemaUi.dialog(this).setTitle(l("اختر ملف الحلقة أو الفيلم","Choose the episode or movie file")).setItems(names,(d,w)->save(files.get(w),match.id)).setNegativeButton(android.R.string.cancel,null).show();
            }});
        }catch(Exception e){fail(e);}finally{handler.post(()->busy=false);}});
    }
    private void save(SubtitleStorage.Entry file,long id){
        status.setText(l("جارٍ حفظ الترجمة…","Saving subtitle…"));
        io.execute(()->{try(SubtitleStorage storage=new SubtitleStorage(this)){
            SubtitleStorage.Saved saved=storage.save(video.getFileUri(),file,id);
            CinemaAutoSubtitles.remember(this,video.getFileUri(),id);
            if(downloadRelease!=null)PreferenceManager.getDefaultSharedPreferences(this).edit().putString(SubtitleStorage.preferenceKey(video.getFileUri())+"_subsource_release",downloadRelease).commit();
            handler.post(()->{if(isDestroyed())return;CinemaUi.dialog(this).setTitle(l("تم حفظ الترجمة","Subtitle saved"))
                    .setMessage((saved.besideVideo()?l("بجانب الفيديو:\n","Beside the video:\n"):l("في التخزين المحلي للتطبيق لأن المصدر لا يسمح بالكتابة:\n","In private app storage because the source is not writable:\n"))+saved.uri())
                    .setPositiveButton(android.R.string.ok,(d,w)->{setResult(Activity.RESULT_OK,new Intent().setData(saved.uri()));finish();}).show();});
        }catch(Exception e){fail(e);}});
    }
    @Override public void onDestroy(){requestGeneration++;handler.removeCallbacksAndMessages(null);io.shutdownNow();openIo.shutdownNow();super.onDestroy();}
}

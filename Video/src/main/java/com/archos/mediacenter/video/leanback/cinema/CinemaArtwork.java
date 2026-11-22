package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional TMDB artwork enrichment using NOVA's existing metadata source and key. Worker only. */
public final class CinemaArtwork {
    private CinemaArtwork(){}
    public record Person(String name,String role,String image){}
    public record Art(String logo,String poster,String backdrop,String overview,String genres,List<Person> cast,String trailer,double rating,int votes,String title,String director,int year,int runtime,String certification,String imdbId){}
    private static final Map<String,Art> MEMORY=new LinkedHashMap<>();
    private static final Map<String,Long> TIMES=new LinkedHashMap<>();
    private static final Map<String,Long> FAILURES=new LinkedHashMap<>();
    private static final Map<String,Uri> QUICK_POSTERS=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String,Uri> DUAL_POSTERS=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String,Art> QUICK_HERO=new java.util.concurrent.ConcurrentHashMap<>();
    private static final android.util.LruCache<String,android.graphics.Bitmap> POSTER_BITMAPS=
            new android.util.LruCache<String,android.graphics.Bitmap>(16*1024*1024){
                @Override protected int sizeOf(String key,android.graphics.Bitmap value){
                    return value.getAllocationByteCount();
                }
            };
    private static volatile boolean quickLoaded=false;

    public static android.graphics.Bitmap getMemoryPosterBitmap(Uri uri){
        if(uri==null)return null;
        android.graphics.Bitmap bmp=POSTER_BITMAPS.get(uri.toString());
        return bmp!=null&&!bmp.isRecycled()?bmp:null;
    }

    public static void putMemoryPosterBitmap(Uri uri,android.graphics.Bitmap bitmap){
        if(uri==null||bitmap==null||bitmap.isRecycled())return;
        if(bitmap.getAllocationByteCount()<=4*1024*1024){
            POSTER_BITMAPS.put(uri.toString(),bitmap);
        }
    }

    private static synchronized void ensureQuickLoaded(Context c){
        if(quickLoaded||c==null)return;
        quickLoaded=true;
        try{
            Context app=c.getApplicationContext();
            android.content.SharedPreferences dualSp=app.getSharedPreferences("cinema_posters_cache",Context.MODE_PRIVATE);
            for(Map.Entry<String,?> entry:dualSp.getAll().entrySet()){
                if(entry.getValue() instanceof String val&&!val.isEmpty()){
                    DUAL_POSTERS.put(entry.getKey(),Uri.parse(val));
                }
            }
            android.content.SharedPreferences sp=app.getSharedPreferences("cinema_quick_cache_v2",Context.MODE_PRIVATE);
            for(Map.Entry<String,?> entry:sp.getAll().entrySet()){
                String k=entry.getKey();
                if(!(entry.getValue() instanceof String val)||val.isEmpty())continue;
                if(k.startsWith("p:")){
                    Uri u=Uri.parse(val);
                    String pureKey=k.substring(2);
                    QUICK_POSTERS.put(pureKey,u);
                    recordDualVariant(null,pureKey,u);
                }else if(k.startsWith("h:")){
                    try{
                        JSONObject o=new JSONObject(val);
                        Art a=new Art(
                            o.optString("logo",null),
                            o.optString("poster",null),
                            o.optString("backdrop",null),
                            o.optString("overview",""),
                            o.optString("genres",""),
                            List.of(),
                            o.optString("trailer",null),
                            o.optDouble("rating",0),
                            o.optInt("votes",0),
                            o.optString("title",null),
                            o.optString("director",null),
                            o.optInt("year",0),
                            o.optInt("runtime",0),
                            o.optString("cert",null),
                            o.optString("imdb",null)
                        );
                        QUICK_HERO.put(k.substring(2),a);
                    }catch(Exception ignored){}
                }
            }
        }catch(Exception ignored){}
    }

    private static boolean validCachedUri(Uri uri){
        if(uri==null)return false;
        if("file".equals(uri.getScheme())){
            String p=uri.getPath();
            return p!=null&&new File(p).isFile();
        }
        return true;
    }

    private static void recordDualVariant(Context c, String fullKey, Uri uri){
        if(fullKey==null||uri==null)return;
        String lang=null, media=null;
        if(fullKey.startsWith("ar-SA:")){ lang="ar"; media=fullKey.substring(6); }
        else if(fullKey.startsWith("en-US:")){ lang="en"; media=fullKey.substring(6); }
        else if(fullKey.contains(":")){
            int idx=fullKey.indexOf(':');
            String prefix=fullKey.substring(0,idx);
            if(prefix.startsWith("ar")||prefix.startsWith("en")){
                lang=prefix.startsWith("ar")?"ar":"en";
                media=fullKey.substring(idx+1);
            }
        }
        if(lang==null||media==null||media.isEmpty())return;
        String langSlot=lang+":"+media;
        String defSlot="def:"+media;
        Uri prevLang=DUAL_POSTERS.put(langSlot,uri);
        Uri prevDef=DUAL_POSTERS.putIfAbsent(defSlot,uri);
        if(c!=null&&(!uri.equals(prevLang)||prevDef==null)){
            var ed=c.getApplicationContext().getSharedPreferences("cinema_posters_cache",Context.MODE_PRIVATE).edit();
            ed.putString(langSlot,uri.toString());
            if(prevDef==null)ed.putString(defSlot,uri.toString());
            ed.apply();
        }
    }

    public static synchronized void putDualPosters(Context c, String mediaKey, Uri arUri, Uri enUri){
        if(c==null||mediaKey==null||mediaKey.isEmpty())return;
        ensureQuickLoaded(c);
        var ed=c.getApplicationContext().getSharedPreferences("cinema_posters_cache",Context.MODE_PRIVATE).edit();
        boolean dirty=false;
        if(arUri!=null){
            DUAL_POSTERS.put("ar:"+mediaKey,arUri);
            ed.putString("ar:"+mediaKey,arUri.toString());
            dirty=true;
        }
        if(enUri!=null){
            DUAL_POSTERS.put("en:"+mediaKey,enUri);
            DUAL_POSTERS.put("def:"+mediaKey,enUri);
            ed.putString("en:"+mediaKey,enUri.toString());
            ed.putString("def:"+mediaKey,enUri.toString());
            dirty=true;
        }else if(arUri!=null&&!DUAL_POSTERS.containsKey("def:"+mediaKey)){
            DUAL_POSTERS.put("def:"+mediaKey,arUri);
            ed.putString("def:"+mediaKey,arUri.toString());
            dirty=true;
        }
        if(dirty)ed.apply();
    }

    public static boolean hasExactLanguagePoster(Context c, String key){
        if(c==null||key==null)return false;
        ensureQuickLoaded(c);
        Uri cached=QUICK_POSTERS.get(key);
        return cached!=null&&validCachedUri(cached);
    }

    public static Uri getCachedPoster(Context c,String key,Uri fallback){
        if(key==null)return fallback;
        ensureQuickLoaded(c);
        Uri cached=QUICK_POSTERS.get(key);
        if(cached!=null){
            if(!validCachedUri(cached)){
                removeCachedPoster(c,key,cached);
                return fallback;
            }
            return cached;
        }
        return fallback;
    }

    /** Returns an instant cached poster (exact language first, then other language / default in 0ms). */
    public static Uri getCachedPosterInstant(Context c, String exactKey, String rawMediaKey, Uri fallback) {
        if (c == null) return fallback;
        ensureQuickLoaded(c);
        Uri exact = getCachedPoster(c, exactKey, null);
        if (exact != null) return exact;
        if (rawMediaKey != null && !rawMediaKey.isEmpty()) {
            boolean wantArabic = exactKey != null && exactKey.startsWith("ar");
            String[] slots = wantArabic
                    ? new String[]{"en-US:" + rawMediaKey, "ar:" + rawMediaKey, "en:" + rawMediaKey, "def:" + rawMediaKey}
                    : new String[]{"ar-SA:" + rawMediaKey, "en:" + rawMediaKey, "def:" + rawMediaKey, "ar:" + rawMediaKey};
            for (String s : slots) {
                Uri u = QUICK_POSTERS.get(s);
                if (u == null) u = DUAL_POSTERS.get(s);
                if (validCachedUri(u)) return u;
            }
        }
        return fallback;
    }

    public static synchronized void putCachedPoster(Context c,String key,Uri uri){
        if(key==null||uri==null)return;
        ensureQuickLoaded(c);
        Uri prev=QUICK_POSTERS.put(key,uri);
        if(!uri.equals(prev)&&c!=null){
            c.getApplicationContext().getSharedPreferences("cinema_quick_cache_v2",Context.MODE_PRIVATE)
                    .edit().putString("p:"+key,uri.toString()).apply();
        }
        recordDualVariant(c,key,uri);
    }

    static synchronized void removeCachedPoster(Context context, String key, Uri expected) {
        ensureQuickLoaded(context);
        if (QUICK_POSTERS.remove(key, expected)) {
            context.getSharedPreferences("cinema_quick_cache_v2", Context.MODE_PRIVATE)
                    .edit().remove("p:" + key).apply();
        }
    }

    public static synchronized void clearAllCachedArtwork(Context context) {
        synchronized (MEMORY) {
            MEMORY.clear();
            TIMES.clear();
            FAILURES.clear();
        }
        QUICK_POSTERS.clear();
        DUAL_POSTERS.clear();
        QUICK_HERO.clear();
        POSTER_BITMAPS.evictAll();
        if (context != null) {
            Context app = context.getApplicationContext();
            app.getSharedPreferences("cinema_quick_cache_v2", Context.MODE_PRIVATE).edit().clear().apply();
            app.getSharedPreferences("cinema_posters_cache", Context.MODE_PRIVATE).edit().clear().apply();
            CinemaMetadataCache.clear(app);
            try {
                File[] posters = new File(app.getFilesDir(), "posters").listFiles();
                if (posters != null) {
                    for (File f : posters) {
                        com.squareup.picasso.Picasso.get().invalidate(Uri.fromFile(f));
                        f.delete();
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    public static Art getCachedHero(Context c,String key){
        if(key==null)return null;
        ensureQuickLoaded(c);
        Art art=QUICK_HERO.get(key);
        if(art!=null&&key.startsWith("en")&&(com.meedx.providers.OpenMetadata.containsArabic(art.title())||com.meedx.providers.OpenMetadata.containsArabic(art.overview()))){
            QUICK_HERO.remove(key);
            if(c!=null){
                c.getApplicationContext().getSharedPreferences("cinema_quick_cache_v2",Context.MODE_PRIVATE)
                        .edit().remove("h:"+key).apply();
            }
            return null;
        }
        return art;
    }

    public static Art loadForItem(Context c,com.archos.mediacenter.video.browser.adapters.object.Base b,long id,boolean show,String language){
        if(c==null||b==null)return null;
        if(!com.meedx.providers.OpenMetadata.enabled(c)&&id>0){
            try{
                Art tmdb=load(c,id,show,language);
                if(tmdb!=null)return tmdb;
            }catch(Exception ignored){}
        }
        try{
            CinemaPosters.Query q=CinemaPosters.query(b);
            if(q.title().isBlank())return null;
            String langCode=language!=null&&language.startsWith("ar")?"ar":"en";
            Uri fileUri=b instanceof com.archos.mediacenter.video.browser.adapters.object.Video v?v.getFileUri()
                    :(b instanceof com.archos.mediacenter.video.browser.adapters.object.Tvshow s?s.getFileUri():null);
            com.meedx.providers.OpenMetadata.Item om=com.meedx.providers.OpenMetadata.lookup(
                    c,new com.meedx.providers.OpenMetadata.Query(q.title(),q.year(),show),
                    null,fileUri!=null?fileUri.toString():null,0,0,langCode);
            if((om==null||om.poster()==null)&&"ar".equals(langCode)){
                com.meedx.providers.OpenMetadata.Item enOm=com.meedx.providers.OpenMetadata.lookup(
                        c,new com.meedx.providers.OpenMetadata.Query(q.title(),q.year(),show),
                        null,fileUri!=null?fileUri.toString():null,0,0,"en");
                if(enOm!=null)om=enOm;
            }
            if(om==null&&id<=0){
                try{
                    List<CinemaPosters.Choice> choices=CinemaPosters.search(c,q,0,false);
                    if(!choices.isEmpty()&&choices.get(0).id()>0){
                        Art tmdb=load(c,choices.get(0).id(),show,language);
                        if(tmdb!=null)return tmdb;
                    }
                }catch(Exception ignored){}
            }
            if(om==null)return null;
            List<Person> people=new ArrayList<>();
            if(om.cast()!=null){
                for(com.meedx.providers.OpenMetadata.CastMember cm:om.cast()){
                    people.add(new Person(cm.name(),cm.role(),cm.image()));
                }
            }
            int parsedYear=q.year();
            try{if(om.year()!=null&&!om.year().isBlank())parsedYear=Integer.parseInt(om.year());}catch(Exception ignored){}
            double rating=om.score()!=null?om.score():0.0;
            return new Art(om.logo(),om.poster(),om.backdrop(),om.plot()==null?"":om.plot(),
                    om.genres()==null?"":om.genres(),people,null,rating,0,om.title(),null,parsedYear,0,null,om.imdb());
        }catch(Exception ignored){
            return null;
        }
    }

    public static void putCachedHero(Context c,String key,Art art){
        if(key==null||art==null)return;
        ensureQuickLoaded(c);
        QUICK_HERO.put(key,art);
        if(c!=null){
            try{
                JSONObject o=new JSONObject();
                if(art.logo()!=null)o.put("logo",art.logo());
                if(art.poster()!=null)o.put("poster",art.poster());
                if(art.backdrop()!=null)o.put("backdrop",art.backdrop());
                if(art.overview()!=null)o.put("overview",art.overview());
                if(art.genres()!=null)o.put("genres",art.genres());
                if(art.trailer()!=null)o.put("trailer",art.trailer());
                o.put("rating",art.rating());
                o.put("votes",art.votes());
                if(art.title()!=null)o.put("title",art.title());
                if(art.director()!=null)o.put("director",art.director());
                o.put("year",art.year());
                o.put("runtime",art.runtime());
                if(art.certification()!=null)o.put("cert",art.certification());
                if(art.imdbId()!=null)o.put("imdb",art.imdbId());
                c.getApplicationContext().getSharedPreferences("cinema_quick_cache_v2",Context.MODE_PRIVATE)
                        .edit().putString("h:"+key,o.toString()).apply();
            }catch(Exception ignored){}
        }
    }

    private static String image(String size,String path){return path==null||path.isEmpty()||path.equals("null")?null:"https://image.tmdb.org/t/p/"+size+path;}
    public static Art load(Context c,long id,boolean show,String language) throws Exception {
        if(id<=0)return null;String type=show?"tv":"movie",cacheKey=type+"-"+id+"-"+language+"-"+androidx.preference.PreferenceManager.getDefaultSharedPreferences(c).getString(HomePreferences.BACKDROP,"balanced");
        synchronized(MEMORY){if(MEMORY.containsKey(cacheKey)&&TIMES.getOrDefault(cacheKey,0L)>System.currentTimeMillis()-86400000)return MEMORY.get(cacheKey);if(FAILURES.getOrDefault(cacheKey,0L)>System.currentTimeMillis())return null;}
        try{
            JSONObject json=CinemaMetadataCache.get(c,type+"/"+id,language);
            JSONObject images=json.optJSONObject("images");String logo=null,poster=null,backdrop=null;
            if(images!=null){
                JSONArray postersArr=images.optJSONArray("posters");
                logo=best(images.optJSONArray("logos"),language);
                poster=best(postersArr,language);
                backdrop=best(images.optJSONArray("backdrops"),language);
                String arPath=best(postersArr,"ar-SA");
                String enPath=best(postersArr,"en-US");
                if(enPath==null)enPath=json.optString("poster_path",null);
                putDualPosters(c,"tmdb:"+type+":"+id,
                        arPath!=null?Uri.parse(image("w500",arPath)):null,
                        enPath!=null?Uri.parse(image("w500",enPath)):null);
            }
            if(logo==null)try{JSONObject alternatives=CinemaMetadataCache.get(c,type+"/"+id+"/images",language);logo=best(alternatives.optJSONArray("logos"),language);}catch(Exception ignored){}
            if(poster==null)poster=json.optString("poster_path",null);if(backdrop==null)backdrop=json.optString("backdrop_path",null);
            List<Person> people=new ArrayList<>();JSONObject credits=json.optJSONObject("credits");JSONArray actors=credits==null?null:credits.optJSONArray("cast");
            if(actors!=null)for(int n=0;n<Math.min(12,actors.length());n++){JSONObject person=actors.getJSONObject(n);people.add(new Person(person.optString("name"),person.optString("character"),image("w342",person.optString("profile_path",null))));}
            String director=null;JSONArray crew=credits==null?null:credits.optJSONArray("crew");
            if(crew!=null)for(int n=0;n<crew.length();n++){JSONObject person=crew.getJSONObject(n);if("Director".equals(person.optString("job"))){director=person.optString("name");break;}}
            if(director==null&&show){JSONArray creators=json.optJSONArray("created_by");if(creators!=null&&creators.length()>0)director=creators.getJSONObject(0).optString("name",null);}
            if(crew!=null)for(String job:new String[]{"Director","Screenplay","Director of Photography","Original Music Composer"}){
                for(int n=0;n<crew.length();n++){JSONObject person=crew.getJSONObject(n);if(!job.equals(person.optString("job")))continue;String name=person.optString("name");boolean existing=false;
                    for(int m=0;m<people.size();m++)if(people.get(m).name().equals(name)){Person old=people.get(m);people.set(m,new Person(old.name(),old.role()+" · "+job,old.image()));existing=true;break;}
                    if(!existing&&people.size()<16)people.add(new Person(name,job,image("w342",person.optString("profile_path",null))));break;
                }
            }
            JSONObject videos=json.optJSONObject("videos");String trailer=officialTrailer(videos==null?null:videos.optJSONArray("results"));
            String overview = json.optString("overview");
            if (!language.startsWith("en") && (trailer == null || overview.isBlank() || poster == null)) {
                try {
                    Art english = load(c, id, show, "en-US");
                    if (english != null) {
                        if (trailer == null) trailer = english.trailer();
                        if (overview.isBlank()) overview = english.overview();
                        if (poster == null && english.poster() != null) poster = Uri.parse(english.poster()).getLastPathSegment();
                        if (poster != null && !poster.startsWith("/")) poster = "/" + poster;
                    }
                } catch (Exception ignored) { /* Display the available local metadata. */ }
            }
            StringBuilder genres=new StringBuilder();JSONArray genreArray=json.optJSONArray("genres");if(genreArray!=null)for(int n=0;n<genreArray.length();n++){if(n>0)genres.append(", ");genres.append(genreArray.getJSONObject(n).optString("name"));}
            int rt=json.optInt("runtime",0);if(rt<=0&&show){JSONArray rts=json.optJSONArray("episode_run_time");if(rts!=null&&rts.length()>0)rt=rts.optInt(0,0);}
            Art art=new Art(image("original",logo),image("w500",poster),image(androidx.preference.PreferenceManager.getDefaultSharedPreferences(c).getString(HomePreferences.BACKDROP,"balanced").equals("high")?"original":"w1280",backdrop),overview,genres.toString(),people,trailer,json.optDouble("vote_average",0),json.optInt("vote_count",0),json.optString(show?"name":"title"),director,year(json.optString(show?"first_air_date":"release_date")),rt,certification(json,show),json.optJSONObject("external_ids")==null?null:json.getJSONObject("external_ids").optString("imdb_id",null));
            synchronized(MEMORY){if(MEMORY.size()>=512){String first=MEMORY.keySet().iterator().next();MEMORY.remove(first);TIMES.remove(first);}MEMORY.put(cacheKey,art);TIMES.put(cacheKey,System.currentTimeMillis());}return art;
        }catch(Exception e){synchronized(MEMORY){if(FAILURES.size()>=32)FAILURES.clear();FAILURES.put(cacheKey,System.currentTimeMillis()+300000);}throw e;}
    }
    public static Art loadEpisode(Context c,long show,int season,int episode,String language)throws Exception{
        if(show<=0||season<0||episode<0)return null;
        Art series=load(c,show,true,language);JSONObject json=CinemaMetadataCache.get(c,"tv/"+show+"/season/"+season+"/episode/"+episode,language);
        List<Person> cast=new ArrayList<>();JSONArray guest=json.optJSONArray("guest_stars");if(guest!=null)for(int n=0;n<Math.min(8,guest.length());n++){JSONObject p=guest.getJSONObject(n);cast.add(new Person(p.optString("name"),p.optString("character"),image("w342",p.optString("profile_path",null))));}
        if(series!=null)for(Person p:series.cast()){if(cast.size()>=16)break;cast.add(p);}
        String director=null;JSONArray crew=json.optJSONArray("crew");if(crew!=null)for(int n=0;n<crew.length();n++)if("Director".equals(crew.getJSONObject(n).optString("job"))){director=crew.getJSONObject(n).optString("name");break;}
        String still=image("original",json.optString("still_path",null));
        return new Art(series==null?null:series.logo(),series==null?null:series.poster(),still==null&&series!=null?series.backdrop():still,json.optString("overview"),series==null?"":series.genres(),cast,series==null?null:series.trailer(),json.optDouble("vote_average",0),json.optInt("vote_count",0),String.format(java.util.Locale.US,"S%02d E%02d · %s",season,episode,json.optString("name")),director,year(json.optString("air_date")),json.optInt("runtime",0),series==null?null:series.certification(),json.optJSONObject("external_ids")==null?null:json.getJSONObject("external_ids").optString("imdb_id",null));
    }
    public static String loadSeasonTrailer(Context c, long tvId, int season, String language) {
        if (c == null || tvId <= 0 || season < 0) return null;
        String[] langs = (language != null && !language.startsWith("en"))
                ? new String[]{language, "en-US"}
                : new String[]{language == null || language.isBlank() ? "en-US" : language};
        for (String lang : langs) {
            try {
                JSONObject json = CinemaMetadataCache.get(c, "tv/" + tvId + "/season/" + season + "/videos", lang);
                String t = officialTrailer(json.optJSONArray("results"));
                if (t != null) return t;
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static String loadSeriesTrailer(Context c, long tvId, String language) {
        if (c == null || tvId <= 0) return null;
        String[] langs = (language != null && !language.startsWith("en"))
                ? new String[]{language, "en-US"}
                : new String[]{language == null || language.isBlank() ? "en-US" : language};
        for (String lang : langs) {
            try {
                JSONObject json = CinemaMetadataCache.get(c, "tv/" + tvId + "/videos", lang);
                String t = officialTrailer(json.optJSONArray("results"));
                if (t != null) return t;
            } catch (Exception ignored) {}
        }
        return null;
    }

    static String officialTrailer(JSONArray videos){
        if(videos==null)return null;
        String anyTrailer=null,anyTeaser=null;
        for(int n=0;n<videos.length();n++){
            JSONObject video=videos.optJSONObject(n);
            if(video==null||!"YouTube".equals(video.optString("site")))continue;
            String key=video.optString("key");
            if(!key.matches("[A-Za-z0-9_-]{11}"))continue;
            String type=video.optString("type");
            if(video.optBoolean("official")&&"Trailer".equals(type))return "https://www.youtube.com/watch?v="+key;
            if(anyTrailer==null&&"Trailer".equals(type))anyTrailer="https://www.youtube.com/watch?v="+key;
            else if(anyTeaser==null&&"Teaser".equals(type))anyTeaser="https://www.youtube.com/watch?v="+key;
        }
        return anyTrailer!=null?anyTrailer:anyTeaser;
    }
    private static int year(String date){try{return Integer.parseInt(date.substring(0,4));}catch(Exception e){return 0;}}
    private static String certification(JSONObject json,boolean show){
        JSONObject source=json.optJSONObject(show?"content_ratings":"release_dates");JSONArray countries=source==null?null:source.optJSONArray("results");if(countries==null)return null;
        String fallback=null;
        for(int n=0;n<countries.length();n++){JSONObject country=countries.optJSONObject(n);if(country==null)continue;
            boolean us="US".equals(country.optString("iso_3166_1"));
            if(show){
                String r=country.optString("rating","");
                if(!r.isBlank()){if(us)return r;if(fallback==null)fallback=r;}
            }else{
                JSONArray releases=country.optJSONArray("release_dates");if(releases!=null)for(int m=0;m<releases.length();m++){String value=releases.optJSONObject(m).optString("certification","");if(!value.isBlank()){if(us)return value;if(fallback==null)fallback=value;}}
            }
        }
        return fallback;
    }
    private static byte[] read(InputStream in) throws Exception {try(ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>2*1024*1024)throw new java.io.IOException("Metadata too large");out.write(buffer,0,n);}return out.toByteArray();}}
    static String best(JSONArray images,String preferredLanguage){
        if(images==null)return null;JSONObject best=null;double bestScore=-1;
        for(int n=0;n<images.length();n++){JSONObject image=images.optJSONObject(n);if(image==null)continue;String language=image.optString("iso_639_1","");
            if(preferredLanguage.startsWith("en")&&"ar".equalsIgnoreCase(language))continue;
            double score=(language.equals("ar")&&preferredLanguage.startsWith("ar")?200:language.equals("en")?100:language.isEmpty()||language.equals("null")?50:0)+image.optDouble("vote_average",0)+Math.min(2,image.optInt("width",0)/1000.0);
            if(score>bestScore){best=image;bestScore=score;}
        }
        return best==null?null:best.optString("file_path",null);
    }
}

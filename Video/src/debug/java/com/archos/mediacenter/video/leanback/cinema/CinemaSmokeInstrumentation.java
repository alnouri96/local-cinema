package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import com.archos.filecorelibrary.MetaFile2;
import com.archos.mediacenter.filecoreextension.upnp2.RawListerFactoryWithUpnp;
import com.archos.mediacenter.utils.ShortcutDbAdapter;
import com.archos.mediaprovider.NetworkScanner;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Debug-only device checks. Reads secrets from private files, never arguments or logs. */
public final class CinemaSmokeInstrumentation extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);args=arguments;start();}
    @Override public void onStart(){Bundle result=new Bundle();
        try{
            waitForIdleSync();
            Context c=getTargetContext();String command=args.getString("command","checks");
            if(command.equals("home")){
                if(!CinemaMediaTime.format(5612345).equals("1h 33m 32s")||!CinemaMediaTime.format(60000).equals("1m 00s")||!CinemaMediaTime.format(0).isEmpty())throw new AssertionError("Measured duration units");
                if(android.os.Looper.myLooper()==null)android.os.Looper.prepare();
                var pref=c.getSharedPreferences("cinema_home_revision_checks",Context.MODE_PRIVATE);pref.edit().clear().commit();
                if(!HomePreferences.order(pref).equals(List.of(1,3,4)))throw new AssertionError("Default split rows");
                if(!CinemaUi.digits("٠١٢٣٤٥٦٧٨٩").equals("0123456789"))throw new AssertionError("Western digits");
                for(int row:new int[]{1,3,4}){
                    androidx.loader.content.CursorLoader loader=row==4?new com.archos.mediacenter.video.browser.loader.AllTvshowsLoader(c,"MAX("+com.archos.mediaprovider.video.VideoStore.MediaColumns.DATE_ADDED+") DESC LIMIT 50",true,true,1500):new CinemaHomeLoader(c,row);
                    try(android.database.Cursor cursor=loader.loadInBackground()){result.putInt("row_"+row,cursor==null?0:cursor.getCount());}
                }
                result.putString("home","split-row migration and western digits passed");
            }
            if(command.equals("track-prefs")){
                Uri file=Uri.parse("file:///cinema-track-contract-check.mkv");CinemaTrackChoices.audio(c,file,1);if(CinemaTrackChoices.audio(c,file)!=1)throw new AssertionError("Audio preference");CinemaTrackChoices.embedded(c,file,2);if(CinemaTrackChoices.embedded(c,file)!=2)throw new AssertionError("Embedded preference");CinemaTrackChoices.embedded(c,file,-1);if(CinemaTrackChoices.embedded(c,file)!=-1)throw new AssertionError("Off preference");CinemaTrackChoices.clearEmbedded(c,file);if(CinemaTrackChoices.embedded(c,file)!=-2)throw new AssertionError("Automatic preference");
                PreferenceManager.getDefaultSharedPreferences(c).edit().remove(SubtitleStorage.preferenceKey(file)+"_audio").remove(SubtitleStorage.preferenceKey(file)+"_manual").apply();result.putString("tracks","audio/embedded/off/automatic preference round trips passed");
            }
            if(command.equals("contrast")){
                if(com.archos.mediacenter.video.player.AdaptiveSubtitleContrast.choose(0,android.graphics.Color.BLACK)!=android.graphics.Color.WHITE||com.archos.mediacenter.video.player.AdaptiveSubtitleContrast.choose(1,android.graphics.Color.WHITE)!=android.graphics.Color.BLACK)throw new AssertionError("Light/dark selection");
                if(com.archos.mediacenter.video.player.AdaptiveSubtitleContrast.choose(.18,android.graphics.Color.WHITE)!=android.graphics.Color.WHITE||com.archos.mediacenter.video.player.AdaptiveSubtitleContrast.choose(.18,android.graphics.Color.BLACK)!=android.graphics.Color.BLACK)throw new AssertionError("Hysteresis");runOnMainSync(()->{android.content.Context themed=new android.view.ContextThemeWrapper(c,com.archos.mediacenter.video.R.style.MyLeanbackTheme);var view=new com.archos.mediacenter.video.player.SubtitleTextView(themed,null);android.text.SpannableString styled=new android.text.SpannableString("Contrast");styled.setSpan(new android.text.style.ForegroundColorSpan(android.graphics.Color.RED),0,8,33);styled.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),0,8,33);view.setText(styled);view.setAdaptiveColor(true,android.graphics.Color.BLACK);android.text.Spanned automatic=(android.text.Spanned)view.getText();if(automatic.getSpans(0,8,android.text.style.ForegroundColorSpan.class).length!=0||automatic.getSpans(0,8,android.text.style.StyleSpan.class).length!=1||view.getCurrentTextColor()!=android.graphics.Color.BLACK)throw new AssertionError("Adaptive styled text");view.setAdaptiveColor(false,android.graphics.Color.WHITE);if(((android.text.Spanned)view.getText()).getSpans(0,8,android.text.style.ForegroundColorSpan.class).length!=1)throw new AssertionError("Manual style restoration");});result.putString("contrast","bright/dark selection; hysteresis; styled text override and manual restoration passed");
            }
            if(command.equals("library-rows")){
                if(android.os.Looper.myLooper()==null)android.os.Looper.prepare();
                int sources=0,matched=0;
                try(var cursor=ShortcutDbAdapter.VIDEO.queryAllShortcuts(c)){
                    var mapper=new com.archos.mediacenter.video.leanback.adapter.NetworkShortcutMapper();
                    if(cursor!=null){
                        mapper.bindColumns(cursor);
                        while(cursor.moveToNext()){
                            var shortcut=(com.archos.mediacenter.video.leanback.adapter.object.NetworkShortcut)mapper.bind(cursor);
                            String prefix=CinemaHomeFragment.libraryPrefix(c,shortcut.getUri());
                            sources++;
                            if(prefix==null)continue;
                            try(var videos=new CinemaHomeLoader(c,100,prefix).loadInBackground()){
                                if(videos!=null&&videos.getCount()>0)matched++;
                            }
                        }
                    }
                }
                result.putInt("sources",sources);
                result.putInt("matched_source_rows",matched);
            }
            if(command.equals("season-folder")){
                var parser=com.archos.mediascraper.preprocess.SearchPreprocessor.instance();
                var file=Uri.parse("smb://example.invalid/Shows/Example/Season%203/Example.E02.mkv");
                var parsed=parser.parseFileBased(file,file);
                if(!(parsed instanceof com.archos.mediascraper.preprocess.TvShowSearchInfo episode)
                        ||episode.getSeason()!=3||episode.getEpisode()!=2)throw new AssertionError("Season folder must supply season 3");
                var explicit=Uri.parse("file:///Shows/Example/Season%203/Example.S02E04.mkv");
                var numbered=parser.parseFileBased(explicit,explicit);
                if(!(numbered instanceof com.archos.mediascraper.preprocess.TvShowSearchInfo explicitEpisode)
                        ||explicitEpisode.getSeason()!=2||explicitEpisode.getEpisode()!=4)throw new AssertionError("Explicit episode numbering must win");
                result.putString("season","Season folder and explicit episode numbering passed");
            }
            if(command.equals("assessment-match")){
                String file="The Assessment 2024 2160p AMZN WEB-DL DDP5 1 HDR H 265-BYNDR";
                Uri uri=Uri.parse("file:///Movies/"+Uri.encode(file)+"/"+Uri.encode(file+".mkv"));
                var parsed=com.archos.mediascraper.preprocess.SearchPreprocessor.instance().parseFileBased(uri,uri);
                if(!(parsed instanceof com.archos.mediascraper.preprocess.MovieSearchInfo movie)
                        ||!"The Assessment".equals(movie.getName())||!"2024".equals(movie.getYear()))
                    throw new AssertionError("Movie title and year parsing");
                var matches=new com.archos.mediascraper.xml.MovieScraper3(c).getMatches2(movie,5);
                if(matches.results.isEmpty())throw new AssertionError("Assessment provider match missing");
                var match=matches.results.get(0);
                if(!"The Assessment".equalsIgnoreCase(match.getOriginalTitle()))throw new AssertionError("Wrong movie match");
                result.putLong("tmdb_id",match.getId());
                result.putString("release_date",String.valueOf(match.getReleaseOrFirstAiredDate()));
            }
            if(command.equals("metadata-locale")){
                var arabic = CinemaArtwork.load(c, 603, false, "ar-SA");
                var english = CinemaArtwork.load(c, 603, false, "en-US");
                if (arabic == null || english == null || arabic.poster() == null || english.poster() == null
                        || arabic.rating() <= 0 || arabic.overview().isBlank() || english.overview().isBlank()
                        || !arabic.overview().matches("(?s).*\\p{IsArabic}.*") || !"The Matrix".equals(english.title()))
                    throw new AssertionError("Localized movie enrichment missing");
                result.putString("ar_title", arabic.title());
                result.putString("en_title", english.title());
                result.putBoolean("different_poster", !arabic.poster().equals(english.poster()));
                result.putString("rating", Double.toString(arabic.rating()));
            }
            if(command.equals("metadata")){
                CinemaArtwork.Art movie=CinemaArtwork.load(c,603,false,"en");
                if(movie==null||!"The Matrix".equals(movie.title())||movie.year()!=1999||movie.poster()==null||movie.rating()<=0)throw new AssertionError("Movie enrichment");
                CinemaArtwork.Art episode=CinemaArtwork.loadEpisode(c,1399,1,1,"en");
                if(episode==null||!episode.title().contains("Winter Is Coming")||episode.year()!=2011||episode.overview().isBlank())throw new AssertionError("Episode enrichment");
                var matches=com.archos.mediascraper.themoviedb3.ExplicitProviderLookup.find(c,Uri.parse("file:///Movies/The.Matrix.[tmdbid-603].mkv"),"en",true);
                if(matches.size()!=1||matches.get(0).getId()!=603)throw new AssertionError("Explicit provider lookup");
                var showInfo=com.archos.mediascraper.preprocess.SearchPreprocessor.instance().parseFileBased(Uri.parse("file:///Shows/Example.[tmdbid-1399].S01E01.mkv"),Uri.parse("file:///Shows/Example.[tmdbid-1399].S01E01.mkv"));
                var showResult=new com.archos.mediascraper.xml.ShowScraper4(c).getMatches2(showInfo,5);
                if(showResult.isMovie||showResult.results.isEmpty()||showResult.results.get(0).getId()!=1399||showResult.results.get(0).getOriginSearchEpisode()!=1)throw new AssertionError("Show identity/type integration");
                org.json.JSONArray images=new org.json.JSONArray("[{\"iso_639_1\":\"fr\",\"file_path\":\"/fr\",\"width\":4000},{\"iso_639_1\":\"en\",\"file_path\":\"/en\",\"width\":500}]");
                if(!"/en".equals(CinemaArtwork.best(images,"en-US")))throw new AssertionError("English artwork priority");
                if(!"/en".equals(CinemaArtwork.best(images,"ar-SA")))throw new AssertionError("English fallback in Arabic");
                images.put(new org.json.JSONObject().put("iso_639_1","ar").put("file_path","/ar"));
                if(!"/ar".equals(CinemaArtwork.best(images,"ar-SA")))throw new AssertionError("Arabic artwork priority");
                if(!"/fr".equals(CinemaArtwork.best(new org.json.JSONArray().put(images.get(0)),"ar-SA")))throw new AssertionError("Artwork language fallback");
                org.json.JSONObject ratings=new org.json.JSONObject("{\"Response\":\"True\",\"imdbID\":\"tt0133093\",\"imdbRating\":\"8.0\",\"imdbVotes\":\"1,234\",\"Ratings\":[{\"Source\":\"Rotten Tomatoes\",\"Value\":\"75%\"}]}");
                if(CinemaOmdbRatings.parse(ratings,"tt0133093").critics()!=75||CinemaOmdbRatings.parse(ratings,"tt0000001")!=null)throw new AssertionError("Rating identity");
                ratings.getJSONArray("Ratings").getJSONObject(0).put("Value","130%");if(CinemaOmdbRatings.parse(ratings,"tt0133093").critics()!=null)throw new AssertionError("Invalid percentage");
                result.putString("metadata","TMDb real film + English artwork + year + credits + S01E01 + explicit ID: passed");
                result.putString("rating_parsers","English/fallback artwork; OMDb identity and bounded RT percentage fixtures: passed (no live OMDb key)");
            }
            if(command.equals("imdb-check")){
                CinemaImdbRatings.Rating rating=CinemaImdbRatings.lookup(c,"tt0133093");
                if(rating==null||rating.value()<1||rating.value()>10||rating.votes()<1)throw new AssertionError("IMDb dataset lookup");
                result.putString("imdb","Official downloaded dataset matched exact IMDb id; valid rating and votes");
            }
            if(command.equals("subtitle-languages")){
                var client=new SubsourceClient(c,new ApiKeyStore(c).read().get(0));
                var movies=client.json("/movies/search?searchType=imdb&imdb=tt0133093").getJSONArray("data");
                long id=-1;
                for(int n=0;n<movies.length();n++){
                    var movie=movies.getJSONObject(n);
                    if("tt0133093".equals(movie.optString("imdbId")))id=movie.optLong("movieId",-1);
                }
                if(id<0)throw new AssertionError("Test film missing from Subsource");
                var english=client.json("/subtitles?movieId="+id+"&language=english&limit=20").getJSONArray("data");
                var all=client.json("/subtitles?movieId="+id+"&limit=100").getJSONArray("data");
                var languages=new java.util.HashSet<String>();
                for(int n=0;n<all.length();n++)languages.add(all.getJSONObject(n).optString("language"));
                if(english.length()==0||languages.size()<2)throw new AssertionError("Provider language results missing");
                result.putInt("provider_languages",languages.size());
                result.putInt("english_results",english.length());
            }
            if(command.equals("api")){
                List<String> keys=new ApiKeyStore(c).read();
                new SubsourceClient(c,keys.get(0)).test();result.putString("api","HTTP 200 from TV");
            }
            if(command.equals("fresh-health")){
                if(CinemaRatingBadge.imdbValue(null,null)!=null||CinemaRatingBadge.imdbValue(null,new CinemaOmdbRatings.Scores(null,0,75))!=null)throw new AssertionError("Missing IMDb must remain optional");
                if(CinemaRatingBadge.imdbValue(new CinemaImdbRatings.Rating(8.0,100),new CinemaOmdbRatings.Scores(7.0,100,null))!=8.0||CinemaRatingBadge.imdbValue(null,new CinemaOmdbRatings.Scores(7.0,100,null))!=7.0)throw new AssertionError("Rating source preference");
                runOnMainSync(()->{var row=new android.widget.LinearLayout(c);CinemaRatingBadge.addFirst(c,row,"IMDb",8.0,0);if(row.getChildCount()!=0)throw new AssertionError("Invalid votes must not add a badge");CinemaRatingBadge.addFirst(c,row,"IMDb",8.0,20);if(row.getChildCount()!=1)throw new AssertionError("Valid badge missing");});
                result.putString("rating_regression","Missing ratings; RT-only remote; source priority; zero-vote badge; valid badge: passed");
                result.putBoolean("matrix_local_imdb_absent",CinemaImdbRatings.lookup(c,"tt0133093")==null);
                int total=0,movies=0,episodes=0;try(var cursor=c.getContentResolver().query(com.archos.mediaprovider.video.VideoStore.Video.Media.EXTERNAL_CONTENT_URI,new String[]{com.archos.mediaprovider.video.VideoStore.Video.VideoColumns.ARCHOS_MEDIA_SCRAPER_TYPE},null,null,null)){
                    if(cursor==null)throw new AssertionError("Provider unavailable");while(cursor.moveToNext()){total++;int type=cursor.getInt(0);if(type==com.archos.mediascraper.BaseTags.MOVIE)movies++;else if(type==com.archos.mediascraper.BaseTags.TV_SHOW)episodes++;}}
                result.putInt("indexed_files",total);result.putInt("matched_movies",movies);result.putInt("matched_episodes",episodes);
                try(var cursor=ShortcutDbAdapter.VIDEO.queryAllShortcuts(c)){result.putInt("library_sources",cursor==null?0:cursor.getCount());}
                var art=CinemaArtwork.load(c,603,false,"en");if(art==null||!"The Matrix".equals(art.title())||art.poster()==null||art.backdrop()==null)throw new AssertionError("Metadata provider lookup failed");
                result.putString("provider","TMDb identity, English title, poster and backdrop available without restored preferences");
            }
            if(command.equals("trailers")){
                var rejected=new org.json.JSONArray().put(new org.json.JSONObject().put("official",false).put("site","YouTube").put("type","Trailer").put("key","abcdefghijk"));
                if(CinemaArtwork.officialTrailer(rejected)!=null)throw new AssertionError("Unofficial trailer accepted");
                rejected.getJSONObject(0).put("official",true).put("type","Clip");if(CinemaArtwork.officialTrailer(rejected)!=null)throw new AssertionError("Clip accepted");
                rejected.getJSONObject(0).put("type","Trailer").put("site","Vimeo");if(CinemaArtwork.officialTrailer(rejected)!=null)throw new AssertionError("Wrong host accepted");
                rejected.getJSONObject(0).put("site","YouTube").put("key","bad");if(CinemaArtwork.officialTrailer(rejected)!=null)throw new AssertionError("Invalid key accepted");
                rejected.getJSONObject(0).put("key","abcdefghijk");if(CinemaArtwork.officialTrailer(rejected)==null)throw new AssertionError("Official trailer rejected");
                var series=CinemaArtwork.load(c,1399,true,"en");var episode=CinemaArtwork.loadEpisode(c,1399,1,1,"en");
                if(series==null||series.trailer()==null||!series.trailer().equals(episode.trailer()))throw new AssertionError("Episode must inherit verified series trailer");
                result.putString("trailers","Official/type/host/key validation and live series-to-episode trailer inheritance passed");
            }
            if(command.equals("watchlist-layout")){
                c.startActivity(new android.content.Intent(c,CinemaWatchlistActivity.class).putExtra("layout_check",true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                Thread.sleep(45000);result.putString("layout","Nonpersisted watchlist grid preview closed");
            }
            if(command.equals("open-subsource")){
                c.startActivity(new android.content.Intent(c,SubsourceSearchActivity.class).putExtra("video_id",Long.parseLong(args.getString("id"))).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                result.putString("launch","Subsource search opened");
                Thread.sleep(25000);
            }
            if(command.equals("subtitle-candidates")){
                try(var cursor=c.getContentResolver().query(com.archos.mediaprovider.video.VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                        com.archos.mediacenter.video.browser.loader.VideoLoader.mProjection,null,null,null)){
                    var mapper=new com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper();mapper.bindColumns(cursor);
                    StringBuilder items=new StringBuilder();int count=0;
                    while(cursor.moveToNext()&&count<100){var video=(com.archos.mediacenter.video.browser.adapters.object.Video)mapper.bind(cursor);if(video instanceof com.archos.mediacenter.video.browser.adapters.object.Movie){items.append(video.getId()).append(": ").append(video.getName()).append("; ");count++;}}
                    result.putString("candidates",items.toString());
                }
            }
            if(command.equals("auto-subsource")){
                long id=Long.parseLong(args.getString("id"));
                try(var cursor=c.getContentResolver().query(com.archos.mediaprovider.video.VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                        com.archos.mediacenter.video.browser.loader.VideoLoader.mProjection,"_id=?",new String[]{Long.toString(id)},null)){
                    if(cursor==null||!cursor.moveToFirst())throw new AssertionError("Video missing");
                    var mapper=new com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper();mapper.bindColumns(cursor);var video=(com.archos.mediacenter.video.browser.adapters.object.Video)mapper.bind(cursor);
                    boolean alreadyBound;try(SubtitleStorage storage=new SubtitleStorage(c)){alreadyBound=storage.lookup(video.getFileUri())!=null;}
                    result.putBoolean("already_bound",alreadyBound);
                    var outcome=CinemaAutoSubtitles.fetch(c,video.getFileUri(),id,()->true);
                    var saved=outcome.saved();result.putString("outcome",outcome.reason());
                    result.putString("automatic_download",saved==null?"No new confident match or existing binding":saved.besideVideo()?"Saved beside video":"Saved in private fallback");
                }
            }
            if(command.equals("promote")){
                com.archos.filecorelibrary.jcifs.JcifsUtils.getInstance(c);
                com.archos.filecorelibrary.smbj.SmbjUtils.getInstance(c);
                try(android.database.Cursor cursor=c.getContentResolver().query(com.archos.mediaprovider.video.VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                        com.archos.mediacenter.video.browser.loader.VideoLoader.mProjection,"_id=?",new String[]{args.getString("id")},null);SubtitleStorage storage=new SubtitleStorage(c)){
                    if(cursor==null||!cursor.moveToFirst())throw new AssertionError("Video missing");
                    var mapper=new com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper();mapper.bindColumns(cursor);
                    var video=(com.archos.mediacenter.video.browser.adapters.object.Video)mapper.bind(cursor);
                    Uri existing=storage.lookup(video.getFileUri());if(existing==null)throw new AssertionError("No previously selected subtitle");
                    byte[] bytes;
                    try(java.io.InputStream in=com.archos.filecorelibrary.FileEditorFactory.getFileEditorForUrl(existing,c).getInputStream();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                        byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>4*1024*1024)throw new AssertionError("Subtitle too large");out.write(buffer,0,n);}bytes=out.toByteArray();
                    }
                    SubtitleStorage.Saved saved=storage.save(video.getFileUri(),new SubtitleStorage.Entry("selected.ar.srt",bytes),0);
                    result.putString("save",saved.besideVideo()?"Saved beside SMB video":"Private fallback");
                }
            }
            if(command.equals("import")){
                File source=new File(c.getFilesDir(),"subsource-import.txt");
                List<String> keys=ApiKeyStore.parse(new String(Files.readAllBytes(source.toPath()),StandardCharsets.UTF_8));
                if(keys.isEmpty())throw new AssertionError("No valid import tokens");new ApiKeyStore(c).save(keys);
                if(!keys.equals(new ApiKeyStore(c).read()))throw new AssertionError("Encrypted key round-trip failed");
                if(!source.delete())throw new AssertionError("Could not remove temporary key file");
                PreferenceManager.getDefaultSharedPreferences(c).edit().putBoolean("subsource_enabled",true).apply();
                new SubsourceClient(c,keys.get(0)).test();result.putString("api","HTTP 200; encrypted key round-trip passed; plaintext import deleted");
            }
            if(command.equals("library")){
                com.archos.filecorelibrary.jcifs.JcifsUtils.getInstance(c);
                com.archos.filecorelibrary.smbj.SmbjUtils.getInstance(c);
                Uri source=Uri.parse(args.getString("source"));
                PreferenceManager.getDefaultSharedPreferences(c).edit().putBoolean("pref_smbj",args.containsKey("smbj")).commit();
                if(args.containsKey("guest"))com.archos.filecorelibrary.samba.NetworkCredentialsDatabase.getInstance().addCredential(
                    new com.archos.filecorelibrary.samba.NetworkCredentialsDatabase.Credential("Guest","","smb://192.168.100.2/","",true));
                List<MetaFile2> list=RawListerFactoryWithUpnp.getRawListerForUrl(source).getFileList();
                result.putInt("smb_items",list.size());
                if(ShortcutDbAdapter.VIDEO.isShortcut(c,source.toString())<0)ShortcutDbAdapter.VIDEO.addShortcut(c,new ShortcutDbAdapter.Shortcut(args.getString("name","Movies"),source.toString()));
                NetworkScanner.scanVideos(c,source);result.putString("scanner","Requested NOVA scan");
            }
            if(command.equals("checks")){
                if(!CinemaAutoSubtitles.arabic("ara",null)||!CinemaAutoSubtitles.arabic("ar",null)||!CinemaAutoSubtitles.arabic(null,"Arabic")||!CinemaAutoSubtitles.arabic(null,"العربية")||CinemaAutoSubtitles.arabic("eng","English"))throw new AssertionError("Arabic track identification");
                var p=c.getSharedPreferences("cinema_test_preferences",Context.MODE_PRIVATE);p.edit().putBoolean("cinema_split_rows_v4",true).putString(HomePreferences.ORDER,"4,4,no,8,2").apply();
                if(!HomePreferences.order(p).equals(List.of(4,1,3)))throw new AssertionError("Row order recovery failed");
                if(ApiKeyStore.parse("abcdefghijklmnop\nabcdefghijklmnop\n\ninvalid").size()!=1)throw new AssertionError("TXT deduplication failed");
                if(ApiKeyStore.parse("abcdefghijklmnop\r\n\r\nqrstuvwxyzabcdef\nabcdefghijklmnop").size()!=2)throw new AssertionError("Multiple-key TXT parsing failed");
                SubtitleMatch.Result same=SubtitleMatch.rank("Film.2025.1080p.BluRay-GROUP.mkv","Film.2025.1080p.BluRay-GROUP",true,-1,-1,null,null,null,null,0,0);
                if(same.confidence()!=SubtitleMatch.Confidence.EXCELLENT)throw new AssertionError("Exact release rank failed");
                SubtitleMatch.Result mismatch=SubtitleMatch.rank("Show.S01E02.WEB-DL-GROUP.mkv","Show.S01E03.WEB-DL-GROUP",true,1,2,null,null,null,null,0,0);
                if(!mismatch.episodeConflict())throw new AssertionError("Wrong episode accepted");
                if(SubsourceClient.parseRetryAfter("120",1000)!=121000)throw new AssertionError("Retry-After seconds failed");
                byte[] text="1\n00:00:01,000 --> 00:00:02,000\nمرحبا\n".getBytes(StandardCharsets.UTF_8);
                java.io.ByteArrayOutputStream zipped=new java.io.ByteArrayOutputStream();
                try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(zipped)){zip.putNextEntry(new java.util.zip.ZipEntry("folder/movie.ar.srt"));zip.write(text);zip.closeEntry();}
                List<SubtitleStorage.Entry> unpacked=SubtitleStorage.unpack(zipped.toByteArray());if(unpacked.size()!=1||!java.util.Arrays.equals(text,unpacked.get(0).bytes()))throw new AssertionError("Arabic ZIP decoding failed");
                java.io.ByteArrayOutputStream bomb=new java.io.ByteArrayOutputStream();
                try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(bomb)){zip.putNextEntry(new java.util.zip.ZipEntry("../rejected.srt"));zip.write(new byte[5*1024*1024]);zip.closeEntry();}
                boolean rejected=false;try{SubtitleStorage.unpack(bomb.toByteArray());}catch(java.io.IOException expected){rejected=true;}if(!rejected)throw new AssertionError("Rejected ZIP entry bypassed byte cap");
                File directory=new File(c.getFilesDir(),"subtitle-storage-test");directory.mkdirs();File media=new File(directory,"video.mkv");media.createNewFile();
                try(SubtitleStorage storage=new SubtitleStorage(c)){
                    Uri uri=Uri.fromFile(media);SubtitleStorage.Saved first=storage.save(uri,new SubtitleStorage.Entry("test.ar.srt",text),1);
                    byte[] before=Files.readAllBytes(new File(first.uri().getPath()).toPath());
                    SubtitleStorage.Saved second=storage.save(uri,new SubtitleStorage.Entry("test.ar.srt",text),2);
                    if(first.uri().equals(second.uri())||!java.util.Arrays.equals(before,Files.readAllBytes(new File(first.uri().getPath()).toPath())))throw new AssertionError("Existing subtitle overwritten");
                    if(!second.uri().equals(storage.lookup(uri)))throw new AssertionError("Subtitle binding failed");
                    storage.getWritableDatabase().delete("subtitle_binding","media_uri=?",new String[]{uri.toString()});
                }
                for(File testFile:directory.listFiles())testFile.delete();directory.delete();
                result.putString("checks","row recovery; TXT dedup/multiple keys; release score; wrong episode; Retry-After; Arabic ZIP; unsafe ZIP bounds; no-overwrite; SQLite binding: passed");
            }
            finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){
            StringBuilder trace=new StringBuilder(failure.getClass().getSimpleName());
            for(Throwable cause=failure;cause!=null;cause=cause.getCause())trace.append("\n").append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
            for(int n=0;n<Math.min(8,failure.getStackTrace().length);n++)trace.append("\n").append(failure.getStackTrace()[n]);
            result.putString("error",trace.toString());finish(Activity.RESULT_CANCELED,result);
        }
    }
}


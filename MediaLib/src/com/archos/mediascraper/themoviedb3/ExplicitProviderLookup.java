package com.archos.mediascraper.themoviedb3;

import android.content.Context;
import android.net.Uri;
import com.archos.mediascraper.SearchResult;
import com.archos.mediascraper.preprocess.ProviderIdentity;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Independent implementation of identifier-first resolution; invoked on NOVA's scraper worker. */
public final class ExplicitProviderLookup {
    private ExplicitProviderLookup(){}
    public static List<SearchResult> find(Context c,Uri file,String language,boolean movie)throws Exception{
        ProviderIdentity identity=ProviderIdentity.parse(file==null?null:file.getPath());
        if(!identity.explicit)return null;
        if(identity.tmdb==null&&identity.imdb==null)return Collections.emptyList();
        String path=identity.tmdb!=null?(movie?"movie/":"tv/")+identity.tmdb:"find/"+identity.imdb;
        String query=identity.tmdb!=null?"&append_to_response=external_ids":"&external_source=imdb_id";
        HttpURLConnection connection=(HttpURLConnection)new URL("https://api.themoviedb.org/3/"+path+"?api_key="+Uri.encode(com.meedx.providers.ProviderKeys.require(c, com.meedx.providers.ProviderKeys.TMDB))+"&language="+Uri.encode(language==null?"en":language)+query).openConnection();
        connection.setConnectTimeout(6000);connection.setReadTimeout(7000);connection.setInstanceFollowRedirects(false);
        try{
            if(connection.getResponseCode()==404)return Collections.emptyList();if(connection.getResponseCode()!=200)throw new IOException("Provider lookup unavailable");
            JSONObject body;try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();if(out.size()+n>2*1024*1024)throw new IOException("Provider response too large");out.write(buffer,0,n);}body=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));}
            if(identity.tmdb==null){JSONArray matches=body.optJSONArray(movie?"movie_results":"tv_results");if(matches==null||matches.length()!=1)return Collections.emptyList();body=matches.getJSONObject(0);}
            if(identity.tmdb!=null&&body.optLong("id")!=identity.tmdb)return Collections.emptyList();
            if(identity.tmdb!=null&&identity.imdb!=null){JSONObject ids=body.optJSONObject("external_ids");String actual=body.optString("imdb_id",ids==null?"":ids.optString("imdb_id"));if(!identity.imdb.equals(actual))return Collections.emptyList();}
            SearchResult result=new SearchResult();if(movie)result.setMovie();else result.setTvShow();result.setId(body.getInt("id"));result.setTitle(body.optString(movie?"title":"name"));result.setOriginalTitle(body.optString(movie?"original_title":"original_name"));result.setLanguage(language);result.setPosterPath(body.optString("poster_path",null));result.setBackdropPath(body.optString("backdrop_path",null));
            String date=body.optString(movie?"release_date":"first_air_date");if(date.matches("[0-9]{4}-.*"))result.setYear(date.substring(0,4));result.setLevenshteinDistance(0);return Collections.singletonList(result);
        }finally{connection.disconnect();}
    }
}

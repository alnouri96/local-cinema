package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Optional, separately keyed OMDb provider. No Jellyfin keys, website scraping or score inference. */
final class CinemaOmdbRatings {
    record Scores(Double imdb, int votes, Integer critics, Integer metacritic) {
        Scores(Double imdb, int votes, Integer critics) {
            this(imdb, votes, critics, null);
        }
    }
    static Scores parse(JSONObject body,String expected){
        if(!"True".equalsIgnoreCase(body.optString("Response"))||!expected.equals(body.optString("imdbID")))return null;
        Double imdb=null;int votes=0;Integer critics=null;Integer metacritic=null;
        try{double value=Double.parseDouble(body.optString("imdbRating"));int count=Integer.parseInt(body.optString("imdbVotes").replace(",",""));if(Double.isFinite(value)&&value>0&&value<=10&&count>0){imdb=value;votes=count;}}catch(Exception ignored){}
        try{String meta=body.optString("Metascore","").trim();if(meta.matches("[0-9]{1,3}")){int mc=Integer.parseInt(meta);if(mc<=100)metacritic=mc;}}catch(Exception ignored){}
        JSONArray array=body.optJSONArray("Ratings");if(array!=null)for(int n=0;n<array.length();n++){JSONObject rating=array.optJSONObject(n);if(rating==null)continue;String src=rating.optString("Source");String value=rating.optString("Value");if("Rotten Tomatoes".equals(src)&&value.matches("[0-9]{1,3}%")){int percent=Integer.parseInt(value.substring(0,value.length()-1));if(percent<=100)critics=percent;}else if(metacritic==null&&"Metacritic".equals(src)&&value.matches("[0-9]{1,3}/100")){int mc=Integer.parseInt(value.substring(0,value.indexOf('/')));if(mc<=100)metacritic=mc;}}
        return new Scores(imdb,votes,critics,metacritic);
    }
    static Scores load(Context c,String id)throws Exception{
        if(id==null||!id.matches("tt[0-9]{7,10}"))return null;
        java.util.List<String> keys=new ApiKeyStore(c,"omdb").read();if(keys.isEmpty())return null;
        String key=keys.get(0);String hash=android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)),android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP).substring(0,16);
        File cache=new File(c.getCacheDir(),"omdb-"+hash+"-"+id+".json");
        if(cache.isFile()&&cache.lastModified()>System.currentTimeMillis()-86400000L)try(InputStream in=new FileInputStream(cache)){return parse(new JSONObject(new String(read(in),StandardCharsets.UTF_8)),id);}
        HttpURLConnection connection=(HttpURLConnection)new URL("https://www.omdbapi.com/?apikey="+Uri.encode(key)+"&i="+id+"&r=json").openConnection();connection.setConnectTimeout(6000);connection.setReadTimeout(7000);connection.setInstanceFollowRedirects(false);
        try{if(connection.getResponseCode()!=200)return null;JSONObject json;try(InputStream in=connection.getInputStream()){json=new JSONObject(new String(read(in),StandardCharsets.UTF_8));}Scores result=parse(json,id);if(result!=null){android.util.AtomicFile atomic=new android.util.AtomicFile(cache);FileOutputStream out=null;try{out=atomic.startWrite();out.write(json.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(IOException error){if(out!=null)atomic.failWrite(out);}}return result;}finally{connection.disconnect();}
    }
    private static byte[] read(InputStream in)throws IOException{try(ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>256*1024)throw new IOException("Rating response too large");out.write(buffer,0,n);}return out.toByteArray();}}
}

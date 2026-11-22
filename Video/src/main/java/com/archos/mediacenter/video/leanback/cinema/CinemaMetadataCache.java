package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Typed provider paths, atomic disk cache and stale-on-error. Call only from workers. */
final class CinemaMetadataCache {
    private static final long DAY=86400000L;
    private static long retryAfter;
    static synchronized JSONObject get(Context c,String path,String language)throws Exception{
        if(!path.matches("(?:movie|tv)/[1-9][0-9]*(?:/season/[0-9]+(?:/episode/[0-9]+)?)?(?:/(?:images|videos))?"))throw new IOException("Unsupported metadata path");
        if(!language.matches("[a-zA-Z-]{2,12}"))language="en";
        File directory=new File(c.getCacheDir(),"cinema_meta_cache");directory.mkdirs();
        File file=new File(directory,path.replace('/','-')+"-"+language+".json");
        if(!file.isFile()){
            File legacy=new File(new File(c.getCacheDir(),"cinema-metadata-v4"),file.getName());
            if(legacy.isFile())file=legacy;
        }
        JSONObject cached=null;
        if(file.isFile())try(InputStream in=new AtomicFile(file).openRead()){cached=new JSONObject(new String(read(in),StandardCharsets.UTF_8));}catch(Exception ignored){}
        if(cached!=null&&file.lastModified()>System.currentTimeMillis()-DAY)return cached;
        if(retryAfter>System.currentTimeMillis()){if(cached!=null)return cached;throw new IOException("Metadata rate limited");}
        String append=path.endsWith("/videos")?"":path.contains("/episode/")?"images,credits,external_ids":path.contains("/season/")?"images,credits,videos":path.startsWith("tv/")?"images,credits,videos,external_ids,content_ratings":"images,credits,videos,external_ids,release_dates";
        String endpoint="https://api.themoviedb.org/3/"+path+"?api_key="+Uri.encode(com.meedx.providers.ProviderKeys.require(c, com.meedx.providers.ProviderKeys.TMDB))+"&include_image_language=ar,en,null"+(path.endsWith("/images")||path.endsWith("/videos")?"":"&language="+Uri.encode(language)+"&append_to_response="+append)+(path.endsWith("/videos")?"&language="+Uri.encode(language):"");
        File writeTarget=new File(directory,path.replace('/','-')+"-"+language+".json");
        HttpURLConnection connection=(HttpURLConnection)new URL(endpoint).openConnection();connection.setConnectTimeout(6000);connection.setReadTimeout(7000);connection.setInstanceFollowRedirects(false);
        try{
            int status=connection.getResponseCode();if(status==429)retryAfter=SubsourceClient.parseRetryAfter(connection.getHeaderField("Retry-After"),System.currentTimeMillis());if(status!=200){if(status>=500&&cached!=null&&file.lastModified()>System.currentTimeMillis()-30*DAY)return cached;throw new IOException("Metadata unavailable: "+status);}
            byte[] bytes;try(InputStream in=connection.getInputStream()){bytes=read(in);}JSONObject result=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            AtomicFile atomic=new AtomicFile(writeTarget);FileOutputStream out=null;try{out=atomic.startWrite();out.write(bytes);atomic.finishWrite(out);}catch(IOException error){if(out!=null)atomic.failWrite(out);}
            File[] entries=directory.listFiles((d,name)->name.endsWith(".json"));if(entries!=null&&entries.length>512){java.util.Arrays.sort(entries,java.util.Comparator.comparingLong(File::lastModified));for(int n=0;n<entries.length-512;n++)entries[n].delete();}
            return result;
        }catch(IOException failure){if(cached!=null&&file.lastModified()>System.currentTimeMillis()-30*DAY&&!String.valueOf(failure.getMessage()).contains("404")&&!String.valueOf(failure.getMessage()).contains("401"))return cached;throw failure;}
        finally{connection.disconnect();}
    }

    static synchronized void clear(Context c) {
        if (c == null) return;
        for (String dirName : new String[]{"cinema_meta_cache", "cinema-metadata-v4"}) {
            try {
                File dir = new File(c.getCacheDir(), dirName);
                File[] files = dir.listFiles();
                if (files != null) for (File f : files) f.delete();
            } catch (Exception ignored) {}
        }
    }
    private static byte[] read(InputStream in)throws IOException{try(ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();if(out.size()+n>2*1024*1024)throw new IOException("Metadata too large");out.write(buffer,0,n);}return out.toByteArray();}}
}

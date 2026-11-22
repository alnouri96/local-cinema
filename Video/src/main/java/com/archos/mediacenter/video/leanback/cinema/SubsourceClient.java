package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Official API only; no request/response logging and no automatic backup-key rotation. */
public final class SubsourceClient {
    public static final String BASE="https://api.subsource.net/api/v1";
    private final String key;
    private final SharedPreferences limits;
    private static final Object RATE_LOCK=new Object();
    private static long lastRequest;
    private static final Map<String,Cache> CACHE=new LinkedHashMap<>();
    private record Cache(long expires,JSONObject data){}
    public SubsourceClient(Context c,String key){this.key=key;limits=c.getSharedPreferences("subsource_limits",Context.MODE_PRIVATE);}
    public static String encode(String value){try{return URLEncoder.encode(value,StandardCharsets.UTF_8.name());}catch(Exception e){throw new IllegalArgumentException();}}
    public static final class ApiException extends IOException {
        public final int status;public final long retryAt;
        ApiException(int status,long retryAt){super("Subsource HTTP "+status);this.status=status;this.retryAt=retryAt;}
        public String userMessage(Context c){return switch(status){
            case 401 -> CinemaUi.label(c,"المفتاح غير صالح أو انتهت صلاحيته (401)","Invalid or expired key (401)");
            case 403 -> CinemaUi.label(c,"هذا المفتاح غير مصرح له (403)","Key not authorized (403)");
            case 429 -> CinemaUi.label(c,"تم بلوغ حد الاستخدام. حاول لاحقاً؛ لن يُستخدم مفتاح آخر لتجاوز الحد.","Rate limit reached. Try later; keys will not rotate to bypass limits.");
            default -> CinemaUi.label(c,"تعذر الاتصال بخدمة Subsource. الرمز: ","Subsource request failed. Status: ")+status;
        };}
    }
    public void test() throws Exception {json("/movies/search?searchType=imdb&imdb=tt0133093",false);}
    public JSONObject json(String path) throws Exception {return json(path,true);}
    private JSONObject json(String path,boolean cached) throws Exception {
        // Cache is per API key, not shared across accounts; it is never serialized or logged.
        String cacheKey=key+path;
        synchronized(CACHE){Cache c=CACHE.get(cacheKey);if(cached&&c!=null&&c.expires>System.currentTimeMillis())return c.data;}
        JSONObject data=new JSONObject(new String(request(path,4*1024*1024),StandardCharsets.UTF_8));
        if(!data.optBoolean("success",false))throw new IOException("Invalid Subsource response");
        synchronized(CACHE){if(CACHE.size()>=32)CACHE.remove(CACHE.keySet().iterator().next());CACHE.put(cacheKey,new Cache(System.currentTimeMillis()+600000,data));}
        return data;
    }
    public byte[] download(long id) throws Exception {return request("/subtitles/"+id+"/download",20*1024*1024);}
    private void reserve() throws Exception {
        synchronized(RATE_LOCK){
            long now=System.currentTimeMillis();long blocked=limits.getLong("blocked_until",0);
            if(now<blocked)throw new ApiException(429,blocked);
            long minute=now/60000,hour=now/3600000,day=now/86400000;
            int perMinute=limits.getLong("minute",-1)==minute?limits.getInt("minute_count",0):0;
            int perHour=limits.getLong("hour",-1)==hour?limits.getInt("hour_count",0):0;
            int perDay=limits.getLong("day",-1)==day?limits.getInt("day_count",0):0;
            if(perMinute>=60||perHour>=1800||perDay>=7200)throw new ApiException(429,now+60000);
            long wait=1100-(now-lastRequest);if(wait>0)Thread.sleep(wait);
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            lastRequest=System.currentTimeMillis();
            limits.edit().putLong("minute",minute).putInt("minute_count",perMinute+1).putLong("hour",hour).putInt("hour_count",perHour+1).putLong("day",day).putInt("day_count",perDay+1).apply();
        }
    }
    private byte[] request(String path,int maxBytes) throws Exception {
        if(!path.startsWith("/")||path.contains("://")||path.contains("\r")||path.contains("\n"))throw new IllegalArgumentException();
        reserve();
        HttpURLConnection connection=(HttpURLConnection)new URL(BASE+path).openConnection();
        connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("X-API-Key",key);connection.setRequestProperty("Accept",path.endsWith("/download")?"application/zip":"application/json");
        try{
            int code=connection.getResponseCode();
            if(code!=200){
                long retryAt=0;
                if(code==429){retryAt=parseRetryAfter(connection.getHeaderField("Retry-After"),System.currentTimeMillis());limits.edit().putLong("blocked_until",retryAt).apply();}
                throw new ApiException(code,retryAt);
            }
            if(connection.getContentLengthLong()>maxBytes)throw new IOException("Response too large");
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException();if(out.size()+n>maxBytes)throw new IOException("Response too large");out.write(buffer,0,n);}
                long expected=connection.getContentLengthLong();if(expected>=0&&out.size()!=expected)throw new IOException("Incomplete download");return out.toByteArray();
            }
        }finally{connection.disconnect();}
    }
    public static long parseRetryAfter(String value,long now){
        if(value!=null)try{return now+Math.max(1,Math.min(86400,Long.parseLong(value)))*1000;}catch(NumberFormatException ignored){
            try{SimpleDateFormat f=new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z",Locale.US);f.setTimeZone(TimeZone.getTimeZone("GMT"));return Math.max(now+1000,f.parse(value).getTime());}catch(Exception ignoredDate){}
        }
        return now+60000;
    }
}

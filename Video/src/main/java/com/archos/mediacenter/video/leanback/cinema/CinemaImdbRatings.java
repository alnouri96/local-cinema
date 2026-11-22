package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/** Official personal/non-commercial dataset. Worker only; never bundled or republished. */
final class CinemaImdbRatings {
    static final String CREDIT="Information courtesy of IMDb (https://www.imdb.com). Used with permission.";
    static final String TERMS="https://help.imdb.com/article/imdb/general-information/can-i-use-imdb-data-in-my-software/G5JTRESSHJBBHTGX";
    record Rating(double value,int votes){}
    private static final java.util.Map<String,Rating> MEMORY=new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> MISSING=java.util.concurrent.ConcurrentHashMap.newKeySet();
    static File file(Context c){return new File(c.getFilesDir(),"imdb-personal-ratings.tsv.gz");}
    static synchronized Rating lookup(Context c,String id)throws IOException{
        if(id==null||!id.matches("tt[0-9]{7,10}")||!file(c).isFile())return null;
        Rating cached=MEMORY.get(id);if(cached!=null)return cached;
        if(MISSING.contains(id))return null;
        File selected=new File(new File(c.getCacheDir(),"imdb-selected"),id+".json");
        if(selected.isFile()&&selected.length()<4096)try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(selected),StandardCharsets.UTF_8))){
            org.json.JSONObject json=new org.json.JSONObject(reader.readLine());
            if(id.equals(json.optString("id"))&&json.optLong("dataset") ==file(c).lastModified()){
                if(json.optBoolean("missing")){MISSING.add(id);return null;}
                double value=json.getDouble("rating");int votes=json.getInt("votes");
                if(Double.isFinite(value)&&value>=1&&value<=10&&votes>0){Rating valueFromDisk=new Rating(value,votes);MEMORY.put(id,valueFromDisk);return valueFromDisk;}
            }
        }catch(Exception ignored){}
        try(InputStream in=new GZIPInputStream(new FileInputStream(file(c)));BufferedReader reader=new BufferedReader(new InputStreamReader(new Bounded(in,128L*1024*1024),StandardCharsets.UTF_8))){
            if(!"tconst\taverageRating\tnumVotes".equals(reader.readLine()))throw new IOException("Invalid IMDb header");
            String line;while((line=reader.readLine())!=null){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();if(!line.startsWith(id+"\t"))continue;
                String[] parts=line.split("\t");try{double value=Double.parseDouble(parts[1]);int votes=Integer.parseInt(parts[2]);if(!Double.isFinite(value)||value<1||value>10||votes<1)return null;Rating result=new Rating(value,votes);if(MEMORY.size()>128)MEMORY.clear();MEMORY.put(id,result);persist(c,id,result);return result;}catch(RuntimeException bad){throw new IOException("Invalid IMDb row");}
            }
        }if(MISSING.size()>128)MISSING.clear();MISSING.add(id);persist(c,id,null);return null;
    }
    private static void persist(Context c,String id,Rating rating){
        try{File directory=new File(c.getCacheDir(),"imdb-selected");directory.mkdirs();org.json.JSONObject json=new org.json.JSONObject().put("id",id).put("dataset",file(c).lastModified()).put("missing",rating==null);
            if(rating!=null)json.put("rating",rating.value()).put("votes",rating.votes());AtomicFile atomic=new AtomicFile(new File(directory,id+".json"));FileOutputStream out=null;
            try{out=atomic.startWrite();out.write(json.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(IOException e){if(out!=null)atomic.failWrite(out);}
            File[] entries=directory.listFiles();if(entries!=null&&entries.length>128){java.util.Arrays.sort(entries,java.util.Comparator.comparingLong(File::lastModified));for(int n=0;n<entries.length-128;n++)entries[n].delete();}
        }catch(Exception ignored){/* A failed cache write must not hide a verified rating. */}
    }
    static synchronized void download(Context c)throws IOException{
        HttpURLConnection connection=(HttpURLConnection)new URL("https://datasets.imdbws.com/title.ratings.tsv.gz").openConnection();
        connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setInstanceFollowRedirects(false);
        File temporary=new File(c.getCacheDir(),"imdb-download.tmp");
        try{
            if(connection.getResponseCode()!=200)throw new IOException("IMDb download unavailable");
            try(InputStream in=new Bounded(connection.getInputStream(),32L*1024*1024);OutputStream out=new FileOutputStream(temporary)){byte[] buffer=new byte[32768];int count;while((count=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();out.write(buffer,0,count);}}
            // Read the entire gzip to verify its CRC and bounds before replacing a working copy.
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(new Bounded(new GZIPInputStream(new FileInputStream(temporary)),128L*1024*1024),StandardCharsets.UTF_8))){if(!"tconst\taverageRating\tnumVotes".equals(reader.readLine()))throw new IOException("Invalid IMDb header");char[] buffer=new char[16384];while(reader.read(buffer)!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();}}
            AtomicFile atomic=new AtomicFile(file(c));FileOutputStream target=null;
            try{target=atomic.startWrite();try(InputStream in=new FileInputStream(temporary)){byte[] buffer=new byte[32768];int count;while((count=in.read(buffer))!=-1)target.write(buffer,0,count);}atomic.finishWrite(target);MEMORY.clear();MISSING.clear();}
            catch(IOException failure){if(target!=null)atomic.failWrite(target);throw failure;}
        }finally{connection.disconnect();temporary.delete();}
    }
    private static final class Bounded extends FilterInputStream{
        private long remaining;Bounded(InputStream in,long limit){super(in);remaining=limit;}
        @Override public int read()throws IOException{int n=super.read();if(n>=0&&--remaining<0)throw new IOException("Dataset too large");return n;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException{int n=in.read(bytes,offset,length);if(n>0&&(remaining-=n)<0)throw new IOException("Dataset too large");return n;}
    }
}

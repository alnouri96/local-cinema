package com.archos.mediacenter.video.leanback.cinema;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import com.archos.filecorelibrary.FileEditor;
import com.archos.filecorelibrary.FileEditorFactory;
import com.archos.filecorelibrary.MetaFile2Factory;
import com.archos.mediacenter.video.browser.subtitlesmanager.SubtitleManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** All methods perform I/O and must be called on a worker. No original video is modified. */
public final class SubtitleStorage extends SQLiteOpenHelper {
    public record Entry(String name,byte[] bytes){}
    public record Saved(Uri uri,boolean besideVideo){}
    private final Context context;
    public SubtitleStorage(Context c){super(c.getApplicationContext(),"cinema_subtitles.db",null,1);context=c.getApplicationContext();}
    @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE subtitle_binding (media_uri TEXT PRIMARY KEY, subtitle_uri TEXT NOT NULL)");}
    @Override public void onUpgrade(SQLiteDatabase db,int old,int version){}
    public Uri lookup(Uri video){
        try(Cursor c=getReadableDatabase().query("subtitle_binding",new String[]{"subtitle_uri"},"media_uri=?",new String[]{video.toString()},null,null,null)){
            return c.moveToFirst()?Uri.parse(c.getString(0)):null;
        }
    }
    public static String preferenceKey(Uri video){
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(video.toString().getBytes(StandardCharsets.UTF_8));StringBuilder key=new StringBuilder("cinema_subtitle_");for(byte b:digest)key.append(String.format(Locale.US,"%02x",b));return key.toString();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public void bind(Uri video,Uri subtitle){CinemaTrackChoices.clearEmbedded(context,video);ContentValues v=new ContentValues();v.put("media_uri",video.toString());v.put("subtitle_uri",subtitle.toString());getWritableDatabase().insertWithOnConflict("subtitle_binding",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().putString(preferenceKey(video)+"_name",subtitle.getLastPathSegment()).putBoolean(preferenceKey(video)+"_manual",false).commit();SubtitleManager.invalidateCache(video);}
    public static List<Entry> unpack(byte[] archive) throws Exception {
        List<Entry> entries=new ArrayList<>();
        // The documented API returns ZIP. Reject HTML/error pages and unsupported archives.
        if(archive.length<4||archive[0]!='P'||archive[1]!='K')throw new IOException("Expected ZIP archive");
        int count=0,total=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(archive))){ZipEntry item;
            while((item=zip.getNextEntry())!=null){
                if(++count>256)throw new IOException("Too many archive entries");
                String name=item.getName().replace('\\','/');
                boolean safe=!item.isDirectory()&&!name.startsWith("/")&&!name.contains("../")&&!name.contains(":");
                String leaf=name.substring(name.lastIndexOf('/')+1);
                boolean subtitle=safe&&leaf.toLowerCase(Locale.ROOT).matches(".*\\.(srt|ass|ssa|vtt)$");
                try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[8192];int n;
                    int expanded=0;
                    while((n=zip.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException();expanded+=n;total+=n;if(total>20*1024*1024||expanded>4*1024*1024)throw new IOException("Expanded archive too large");if(subtitle)out.write(buffer,0,n);}
                    if(subtitle&&out.size()>0)entries.add(new Entry(leaf,utf8(out.toByteArray())));
                }
            }
        }
        if(entries.isEmpty())throw new IOException("No supported text subtitles in archive");return entries;
    }
    public static byte[] utf8(byte[] bytes) throws Exception {
        String text;
        if(bytes.length>=2&&bytes[0]==(byte)0xff&&bytes[1]==(byte)0xfe)text=new String(bytes,StandardCharsets.UTF_16LE);
        else if(bytes.length>=2&&bytes[0]==(byte)0xfe&&bytes[1]==(byte)0xff)text=new String(bytes,StandardCharsets.UTF_16BE);
        else try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException e){text=new String(bytes,Charset.forName("windows-1256"));}
        if(text.indexOf('\0')>=0)throw new IOException("Binary subtitle is not supported");
        text=text.replace("\uFEFF","");
        if(!text.contains("-->")&&!text.contains("Dialogue:")&&!text.contains("[Script Info]"))throw new IOException("Invalid text subtitle");
        return text.getBytes(StandardCharsets.UTF_8);
    }
    public Saved save(Uri video,Entry entry,long subtitleId) throws Exception {
        return save(video,entry,subtitleId,"ar","",()->true);
    }
    public Saved save(Uri video,Entry entry,long subtitleId,java.util.function.BooleanSupplier current) throws Exception {
        return save(video,entry,subtitleId,"ar","",current);
    }
    public Saved save(Uri video,Entry entry,long subtitleId,String languageCode,String sourceName,java.util.function.BooleanSupplier current) throws Exception {
        if(!current.getAsBoolean())throw new java.util.concurrent.CancellationException();
        String filename=video.getLastPathSegment();if(filename==null)filename="video";
        int dot=filename.lastIndexOf('.');String stem=dot>0?filename.substring(0,dot):filename;
        int extDot=entry.name.lastIndexOf('.');
        String extension=extDot>=0?entry.name.substring(extDot).toLowerCase(Locale.ROOT):".srt";
        String iso2=com.meedx.providers.UnifiedSubtitleConfig.toIso2(languageCode);
        if(iso2==null||iso2.isBlank())iso2="ar";
        String name=stem+"."+iso2+extension;Uri saved=null;
        String scheme=video.getScheme();
        if("file".equals(scheme)||(scheme!=null&&scheme.startsWith("smb"))){
            String path=video.getPath();if(path!=null&&path.contains("/")){
                Uri parent=video.buildUpon().path(path.substring(0,path.lastIndexOf('/')+1)).build();
                try{
                    if(!MetaFile2Factory.getMetaFileForUrl(parent).canWrite())throw new IOException("Read-only source");
                    Uri target=parent.buildUpon().appendPath(name).build();
                    FileEditor editor=FileEditorFactory.getFileEditorForUrl(target,context);
                    if(editor.exists()){
                        String tag = (sourceName != null && !sourceName.isBlank())
                                ? sourceName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "")
                                : "sub";
                        target=parent.buildUpon().appendPath(stem+"."+tag+"-"+subtitleId+"-"+UUID.randomUUID()+"."+iso2+extension).build();
                        editor=FileEditorFactory.getFileEditorForUrl(target,context);
                        if(editor.exists())throw new IOException("Target already exists");
                    }
                    boolean created=false;
                    try(OutputStream out=editor.getOutputStreamExclusive()){
                        if(out==null)throw new IOException("No write permission");created=true;out.write(entry.bytes);out.flush();
                    }catch(Exception failure){
                        if(created)try{editor.delete();}catch(Exception cleanupFailed){/* The share may have disconnected. */}
                        throw failure;
                    }
                    saved=target;
                }catch(Exception ignored){/* Auth/read-only errors fall back to private app storage. */}
            }
        }
        boolean beside=saved!=null;
        if(saved==null){
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(video.toString().getBytes(StandardCharsets.UTF_8));StringBuilder key=new StringBuilder();for(byte b:digest)key.append(String.format(Locale.US,"%02x",b));
            File dir=new File(context.getFilesDir(),"subtitles/"+key);if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create subtitle folder");
            File target=new File(dir,UUID.randomUUID()+"."+iso2+extension);if(!target.createNewFile())throw new IOException("Target already exists");
            try(FileOutputStream out=new FileOutputStream(target)){out.write(entry.bytes);out.getFD().sync();}
            saved=Uri.fromFile(target);
        }
        // A manual choice or media switch during network I/O must not replace its binding.
        if(!current.getAsBoolean())throw new java.util.concurrent.CancellationException();
        CinemaSubtitleLabels.recordSavedSource(context, saved, sourceName, languageCode, entry.name, beside);
        bind(video,saved);return new Saved(saved,beside);
    }
}

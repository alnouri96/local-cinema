// Copyright 2017 Archos SA
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.archos.filecorelibrary;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import com.archos.filecorelibrary.contentstorage.DocumentUriBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileUtils {

    private static final Logger log = LoggerFactory.getLogger(FileUtils.class);

    final static char SEP = '/';
    final static String SEPARATOR = "/";

    public static String getParentUrl(String url) {
        int index;

        if (url.endsWith(SEPARATOR)) {
            index = url.lastIndexOf(SEPARATOR, url.length()-2);
        }
        else {
            index = url.lastIndexOf(SEPARATOR);
        }

        if (index<=0) {
            return null;
        }
        return url.substring(0, index + 1);
    }

    public static Boolean isNovaOwnedFile(File file) {
        if (file == null) return false;
        return file.getPath().startsWith(FileUtilsQ.publicAppDirectory) || file.getPath().startsWith(FileUtilsQ.privateAppDirectory);
    }

    public static Uri prefixPublicNfoPosterUri(Uri uri) {
        // keep the full path of remote storage and prefix it with publicAppDirectory + "/nfoPoster/"
        // full path is kept to cope with extSdCard or USB storage for which we cannot detect the root path easily
        // BUT ONLY IF it has not been granted MANAGE_EXTERNAL_STORAGE yet
        if (uri == null) return null;
        if (! canManageExternalStorage()) {
            String path = encodeUri(uri).getPath();
            if (!path.startsWith(FileUtilsQ.publicAppDirectory)) {
                String scheme = uri.getScheme();
                if (scheme != null && !scheme.equals(""))
                    scheme = scheme + "://";
                else scheme = "";
                path = path.replaceFirst("/", FileUtilsQ.publicAppDirectory + "/nfoPoster/");
                if (log.isDebugEnabled()) log.debug("prefixPublicNfoPosterUri: {} --> {}", uri, path);
                return Uri.parse(scheme + path);
            } else return uri;
        } else return uri;
    }

    public static Uri relocateNfoAppPublicDirForNfoJpgFiles(Uri uri) {
        // converts local file uri for jpg and nfo ONLY to public application uri to avoid Android Q storage restrictions
        // and creates the relocate directory if not existing
        // BUT ONLY IF it has not been granted MANAGE_EXTERNAL_STORAGE yet
        if (uri == null) return null;
        String lowerCasePath = uri.getPath();
        if (lowerCasePath == null) {
            log.error("relocateNfoAppPublicDirForNfoJpgFiles: uri path is null for {}", uri);
            return uri;
        }
        lowerCasePath = lowerCasePath.toLowerCase();
        if (lowerCasePath.endsWith(".nfo") || lowerCasePath.endsWith(".jpg")) return relocateNfoAppPublicDir(uri);
        else return uri;
    }

    public static Uri relocateNfoAppPublicDir(Uri uri) {
        // converts local file uri for a directory if localstorage only to public application uri to avoid Android Q storage restrictions
        // and creates the relocate directory if not existing
        // BUT ONLY IF it has not been granted MANAGE_EXTERNAL_STORAGE yet
        if (uri == null) return null;
        Uri relocatedUri = uri;
        if (! canManageExternalStorage()) {
            String lowerCasePath;
            String relocatedPath = uri.getPath();
            // always relocate jpg/nfo in private app dir due to SAF/Q which might cause a migration issue
            if (("file".equals(uri.getScheme()) || relocatedUri.toString().startsWith("/"))) {
                if (log.isTraceEnabled()) log.trace("relocateNfoAppPublicDir: relocatedPath {}, {}/nfoPoster", relocatedPath, FileUtilsQ.publicAppDirectory);
                if (!uri.getPath().startsWith(FileUtilsQ.publicAppDirectory + "/nfoPoster")) // avoid double prefixing
                    relocatedUri = prefixPublicNfoPosterUri(relocatedUri);
                Uri relocatedDir = removeLastSegment(relocatedUri);
                File dir = new File(relocatedDir.getPath());
                try {
                    dir.mkdirs();
                } catch (Exception e) {
                    log.error("relocateNfoAppPublicDir: cannot recreate tree structure for {}", dir.getPath());
                }
            }
            if (log.isDebugEnabled()) log.debug("relocateNfoAppPublicDir: {} -> {} = {}", uri, relocatedUri, relocatedUri.getPath());
        }
        return relocatedUri;
    }

    public static Uri getParentUrl(Uri uri) {
        if("content".equals(uri.getScheme())){
            return Uri.parse(DocumentUriBuilder.getParentUriStringAndFileName(uri).first);
        }
        return removeLastSegment(uri);
    }

    public static String removeFileSlashSlash(String url) {  // remove "file://"
        if(url.startsWith("file://"))
            return url.substring("file://".length());
        else return url;
    }

    public static Uri removeLastSegment(Uri uri){
        int index;
        String str = uri.toString();
        if (log.isTraceEnabled()) log.trace("removeLastSegment input: {}", str);
        if (str.endsWith(SEPARATOR))
            index = str.lastIndexOf(SEPARATOR, str.length()-2);
        else index = str.lastIndexOf(SEPARATOR);
        if (index <= 0) return null;
        if (log.isTraceEnabled()) log.trace("removeLastSegment output: {}", str.substring(0, index + 1));
        // MUST keep the trailing "/" for samba
        return Uri.parse(str.substring(0, index + 1));
    }

    /**
     * Computes the BUCKET_ID (See {@link android.provider.MediaStore.Video.VideoColumns#BUCKET_ID})
     * for this file. The id is used in our and Android's database to identify the
     * folder of a file/directory. All files / subdirectories in a directory have
     * the same id. The folder in which all these files reside has the bucket id
     * of it's parent folder. <p>
     * Note: Since the bucket id is a hash over the lowercased parent path, chances
     * are that it's not 100% perfect. Hash collision and case sensitive filenames
     * can in theory lead to some confusion.
     *
     * @return the bucket id or -1 if not available
     */
    public static int getBucketId(Uri uri) {
        // Here we do use language independent toLowerCase
        String path = uri.getPath();
        return path != null ? path.toLowerCase(Locale.ROOT).hashCode() : -1;
    }

    public static boolean isLocal(Uri uri){
        if (uri == null) return false;
        return uri.getScheme()==null||"file".equals(uri.getScheme())||"content".equalsIgnoreCase(uri.getScheme())||uri.toString().startsWith("/");
    }

    public static String getFileNameWithoutExtension(Uri uri){
        String name = getName(uri);
        return stripExtensionFromName(name);
    }

    public static String stripExtensionFromName(String name){
        if (name != null) {
            int dotPos = name.lastIndexOf('.');
            if (dotPos > 0) {
                name = name.substring(0, dotPos);
            }
        }
        return name;
    }

    public static String getExtension(String filename) {
        if (filename == null)
            return null;
        int dotPos = filename.lastIndexOf('.');
        if (dotPos >= 0 && dotPos < filename.length()) {
            return filename.substring(dotPos + 1).toLowerCase();
        }
        return null;
    }

    //useful for uris in content://
    public static Uri getRealUriFromVideoURI(Context context, Uri contentUri) {
        if (contentUri == null || !"content".equalsIgnoreCase(contentUri.getScheme()))
            return contentUri;

        if (log.isDebugEnabled()) log.debug("getRealUriFromVideoURI: contentUri.getHost()={}", contentUri.getHost());

        // 1. Check direct filesystem path candidates in contentUri.getPath() (e.g. FileProvider from FX File Explorer, Solid Explorer, USB paths)
        String rawPath = contentUri.getPath();
        if (rawPath != null && !rawPath.isEmpty()) {
            Uri direct = resolveReadableFileCandidate(rawPath);
            if (direct != null) return direct;
            String decodedPath = Uri.decode(rawPath);
            if (decodedPath != null && !decodedPath.equals(rawPath)) {
                Uri decodedDirect = resolveReadableFileCandidate(decodedPath);
                if (decodedDirect != null) return decodedDirect;
            }
        }

        // 2. Check DocumentsContract document IDs (ExternalStorageProvider for Internal Storage & USB drives, DownloadsProvider, MediaDocumentsProvider)
        if (context != null) {
            try {
                if (android.provider.DocumentsContract.isDocumentUri(context, contentUri)) {
                    String docId = android.provider.DocumentsContract.getDocumentId(contentUri);
                    if (docId != null) {
                        if (docId.startsWith("raw:")) {
                            File rawFile = new File(docId.substring(4));
                            if (rawFile.exists() && rawFile.canRead()) return Uri.fromFile(rawFile);
                        } else if (docId.contains(":")) {
                            String[] split = docId.split(":", 2);
                            String type = split[0];
                            String rel = split.length > 1 ? split[1] : "";
                            if ("primary".equalsIgnoreCase(type)) {
                                File primaryFile = new File(Environment.getExternalStorageDirectory(), rel);
                                if (primaryFile.exists() && primaryFile.canRead()) return Uri.fromFile(primaryFile);
                            } else if ("home".equalsIgnoreCase(type)) {
                                File homeFile = new File(new File(Environment.getExternalStorageDirectory(), "Documents"), rel);
                                if (homeFile.exists() && homeFile.canRead()) return Uri.fromFile(homeFile);
                            } else if ("video".equalsIgnoreCase(type)) {
                                Uri mediaUri = queryMediaFileUri(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "_id=?", new String[]{rel});
                                if (mediaUri != null) return mediaUri;
                            } else {
                                // External USB drive / SD card volume UUID
                                File storageVol = new File("/storage/" + type, rel);
                                if (storageVol.exists() && storageVol.canRead()) return Uri.fromFile(storageVol);
                                File mediaRwVol = new File("/mnt/media_rw/" + type, rel);
                                if (mediaRwVol.exists() && mediaRwVol.canRead()) return Uri.fromFile(mediaRwVol);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (log.isDebugEnabled()) log.debug("getRealUriFromVideoURI: DocumentsContract resolution failed for {}", contentUri, e);
            }
        }

        // 3. Query ContentResolver for DATA (_data) column safely
        if (context != null) {
            Uri queried = queryMediaFileUri(context, contentUri, null, null);
            if (queried != null) return queried;
        }
        return contentUri;
    }

    private static Uri resolveReadableFileCandidate(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return null;
        String[] prefixes = { "", "/root", "/external_files", "/external", "/storage_root", "/files_root", "/device_root" };
        for (String prefix : prefixes) {
            if (!prefix.isEmpty() && rawPath.startsWith(prefix + "/")) {
                String stripped = rawPath.substring(prefix.length());
                File f = new File(stripped);
                if (f.isFile() && f.canRead()) return Uri.fromFile(f);
                File ext = new File(Environment.getExternalStorageDirectory(), stripped.startsWith("/") ? stripped.substring(1) : stripped);
                if (ext.isFile() && ext.canRead()) return Uri.fromFile(ext);
            }
        }
        File direct = new File(rawPath);
        if (direct.isFile() && direct.canRead()) return Uri.fromFile(direct);
        for (String marker : new String[]{ "/storage/", "/mnt/", "/sdcard/" }) {
            int idx = rawPath.indexOf(marker);
            if (idx > 0) {
                File sub = new File(rawPath.substring(idx));
                if (sub.isFile() && sub.canRead()) return Uri.fromFile(sub);
            }
        }
        return null;
    }

    private static Uri queryMediaFileUri(Context context, Uri uri, String selection, String[] selectionArgs) {
        String[] proj = { MediaStore.Video.Media.DATA };
        try (Cursor cursor = context.getContentResolver().query(uri, proj, selection, selectionArgs, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            int columnIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATA);
            if (columnIndex < 0) return null;
            String data = cursor.getString(columnIndex);
            if (data == null || data.isEmpty()) return null;
            File f = new File(data);
            if (f.isFile() && f.canRead()) return Uri.fromFile(f);
            Uri parsed = Uri.parse(data);
            if (parsed.getPath() != null && new File(parsed.getPath()).canRead()) {
                return parsed.getScheme() == null ? Uri.fromFile(new File(parsed.getPath())) : parsed;
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) log.debug("queryMediaFileUri failed for {}", uri, e);
        }
        return null;
    }

    public static Uri getContentUriFromFileURI(Context context, Uri dataUri) {
        if(!"file".equals(dataUri.getScheme()))
            return null;
        dataUri = Uri.parse(dataUri.getPath());
        Cursor cursor = null;
        try {

            String where= MediaStore.MediaColumns.DATA +"= ?";
            String[] whereArg = { dataUri.toString() };
            String[] proj = { BaseColumns._ID};
            cursor = context.getContentResolver().query(MediaStore.Files.getContentUri("external"),  proj, where, whereArg, null);
            if(cursor==null) {
                return null;
            }
            int column_index = cursor.getColumnIndex( BaseColumns._ID);
            cursor.moveToFirst();
            if(cursor.getCount()==0
                    ||column_index<0||cursor.getInt(column_index)==-1) {
                cursor.close();
                return null;
            }
            String uri = MediaStore.Files.getContentUri("external").toString()+"/"+cursor.getInt(column_index);
            cursor.close();
            return Uri.parse(uri);
        } finally {

        }
    }

    // provides the basename
    public static String getName(Uri uri){
        if(uri!=null) {
            // note that getLastPathSegment() does not return the last segment it could omit the fragment i.e. for /toto/tata/agent#327.mp4 provide only agent
            String name = uri.getLastPathSegment() + (uri.getFragment() != null ? "#" + uri.getFragment() : "");
            if (name == null || name.isEmpty()) {
                if (uri.toString().lastIndexOf("/") >= 0 && uri.toString().lastIndexOf("/") < (uri.toString().length() - 1))
                    name = uri.toString().substring(uri.toString().lastIndexOf("/") + 1);
                else
                    name = uri.toString();
            }
            if(name!=null&&"content".equals(uri.getScheme())){
                String[] parts = name.split(":");
                name = parts[parts.length-1];

            }
            return name;
        }
        return null;
    }

    public static String getName(String file) {
        if (log.isDebugEnabled()) log.debug("getName input: {}", file);
        if (file.lastIndexOf(SEPARATOR) >= 0 && file.lastIndexOf(SEPARATOR) < (file.length() - 1))
            file = file.substring(file.lastIndexOf(SEPARATOR) + 1);
        if (log.isDebugEnabled()) log.debug("getName result: {}", file);
        return file;
    }

    public static boolean isSlowRemote(Uri uri) {
        return "ftps".equals(uri.getScheme())||"ftp".equals(uri.getScheme())||
                "sftp".equals(uri.getScheme())||"sshj".equals(uri.getScheme())||
                "webdav".equals(uri.getScheme())||"webdavs".equals(uri.getScheme());
    }

    // TODO: when adding new share check also in VideoOpenHelper the _id increment due to 1e9 android 13 change
    public static boolean isNetworkShare(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        String normalized = scheme.toLowerCase(Locale.ROOT);
        return "smb".equals(normalized) ||
                "upnp".equals(normalized) ||
                "ftps".equals(normalized) || "ftp".equals(normalized) || "sftp".equals(normalized) ||
                "webdav".equals(normalized) || "webdavs".equals(normalized) ||
                "smbj".equals(normalized) || "sshj".equals(normalized);
    }

    public static boolean isNetworkShare(String path) {
        return isNetworkShare(Uri.parse(path));
    }

    /**
     * returns true if is really on a share (for example smb://quatro/sda(...) or ftp://bla.fr(...))
     * @param parent
     * @return
     */
    public static boolean isOnAShare(Uri parent) {
        return "smb".equals(parent.getScheme())&&parent.getPath()!=null
                &&!parent.getPath().equals("/")
                &&!parent.getPath().isEmpty()||
                !"smb".equals(parent.getScheme());
    }

    // get sharename for a smb uri i.e. get first path after hostname
    public static String getShareName(Uri uri) {
        if (uri == null) return null;
        List<String> pathSegments = uri.getPathSegments();
        if (pathSegments != null && !pathSegments.isEmpty()) {
            if (log.isDebugEnabled()) log.debug("getShareName: uri={} -> {} -> {}", uri, Arrays.toString(pathSegments.toArray()), pathSegments.get(0));
            return pathSegments.get(0);
        } else {
            log.warn("getShareName: returns null since getPathSegments is null for uri={}", uri);
        }
        return null;
    }

    // get filePath for a smb uri i.e. get all path after hostname and sharename
    public static String getFilePath(Uri uri) {
        if (uri == null) return null;
        // need to encode uri to escape problematic caracters like '#'
        Uri encodedUri = encodeUri(uri);
        List<String> pathSegments = encodedUri.getPathSegments();
        if (pathSegments != null && !pathSegments.isEmpty()) {
            List<String> pathSegmentsWithoutFirst = pathSegments.subList(1, pathSegments.size());
            String filePath = "/" + String.join("/", pathSegmentsWithoutFirst);
            if (log.isDebugEnabled()) log.debug("getFilePath: uri={} -> {} -> {}", uri, Arrays.toString(pathSegmentsWithoutFirst.toArray()), filePath);
            return filePath;
        }
        if (pathSegments != null && pathSegments.isEmpty()) return "/";
        return null;
    }

    public static String getParentDirectoryPath(String inputPath) {
        String parentDirectory = "";
        String regEx = "(.*\\/)[^\\/]+$";
        Pattern regexPattern = Pattern.compile(regEx);
        Matcher match = regexPattern.matcher(inputPath);
        if (match.find()) parentDirectory = match.group(1);
        return parentDirectory;
    }

    public static Uri encodeUri(Uri uri) {
        String uriString = uri.toString();
        String uriEncodedString ="";
        if (uri.getScheme() != null) {
            uriEncodedString += uri.getScheme() + "://";
            uriString = uriString.substring((uri.getScheme() + "://").length());
            // Extract the authority (host:port) to not recode the : in the port
            String authority = uriString.split("/")[0];
            if (authority.contains(":")) {
                String[] parts = authority.split(":");
                uriEncodedString += parts[0] + ":" + parts[1]; // Keep the port as is
                uriString = uriString.substring(authority.length());
            } else {
                uriEncodedString += authority;
                uriString = uriString.substring(authority.length());
            }
        }
        int i= 0;
        for(String seg : uriString.split("/")){ //split instead of using uri. get path segments because when weird characters such as # %, path segments don't work properly
            seg = seg.replace("/","");
            if(i!=0 && uriString.startsWith("/")) {
                uriEncodedString += "/";
            }
            uriEncodedString+=Uri.encode(seg);
            i++;
        }
        if(uriEncodedString.startsWith("//")) {
            uriEncodedString = uriEncodedString.substring(1);
        }
        if (log.isDebugEnabled()) log.debug("encodeUri: results in uriEncodedString={}", uriEncodedString);
        return Uri.parse(uriEncodedString);
    }

    public static Uri buildChildUri(Uri parent, String childName){
        if("content".equals(parent.getScheme())){
            return Uri.parse(DocumentUriBuilder.buildDocumentUriUsingTree(parent).toString()+Uri.encode("/")+childName);
        }
        return Uri.withAppendedPath(parent, childName);
    }

    // dump intent into string for debug purposes
    public static String intentToString(Intent intent) {
        if (intent == null)
            return "";
        StringBuilder stringBuilder = new StringBuilder("Package: ")
                .append(intent.getPackage())
                .append("; action: ")
                .append(intent.getAction())
                .append("; type: ")
                .append(intent.getType())
                .append("; component: ")
                .append(intent.getComponent())
                .append("; data string: ")
                .append(intent.getDataString())
                .append("; extras: ");
        Bundle extras = intent.getExtras();
        if (extras == null)
            stringBuilder.append(" null");
        else if (extras.isEmpty())
            stringBuilder.append(" not null but empty");
        else
            for (String key : intent.getExtras().keySet())
                stringBuilder.append(key).append("=").append(intent.getExtras().get(key)).append(", ");
        stringBuilder.append( " categories: ");
        Set<String> categories = intent.getCategories();
        if (categories == null)
            stringBuilder.append(" null");
        else if (categories.isEmpty())
            stringBuilder.append(" not null but empty");
        else
            for (String category : categories)
                stringBuilder.append(category).append(", ");
        return stringBuilder.toString();
    }

    // dump database to storage
    public static void backupDatabase(Context context, String dbFileName) {
        try {
            File sdCard = context.getExternalFilesDir(null);
            // dump into /sdcard/Android/data/org.courville.nova/files
            File dataDir = Environment.getDataDirectory();

            String packageName = context.getApplicationInfo().packageName;

            if (sdCard.canWrite()) {
                String currentDbPath = String.format("//data//%s//databases//%s",
                        packageName, dbFileName);
                String backupDbPath = String.format("%s-%s", packageName, dbFileName);
                File currentDb = new File(dataDir, currentDbPath);
                File backupDb = new File(sdCard, backupDbPath);

                if (currentDb.exists()) {
                    FileChannel src = new FileInputStream(currentDb).getChannel();
                    FileChannel dst = new FileOutputStream(backupDb).getChannel();
                    dst.transferFrom(src, 0, src.size());
                    src.close();
                    dst.close();
                }
            }
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    // returns permissions listed in the manifest file
    public static String[] getPermissions(Context context) {
        try {
            return context
                    .getPackageManager()
                    .getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS)
                    .requestedPermissions;
        } catch (PackageManager.NameNotFoundException e) {
            log.error("getPermissions: caught PackageManager.NameNotFoundException", e);
            return new String[0];
        }
    }

    // checks if permission listed in the manifest file
    public static boolean hasPermission(String permission, Context context) {
        return Arrays.asList(getPermissions(context)).contains(permission);
    }

    public static boolean hasManageExternalStoragePermission(Context context) {
        return hasPermission("android.permission.MANAGE_EXTERNAL_STORAGE", context);
    }

    // canManageExternalStorage does say if it can have full storage access but not if read/write permission is granted!
    // do not rely only on hasManageExternalStoragePermission since on ADTV12 you can have MANAGE_EXTERNAL_STORAGE in manifest but no way to grant it via ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
    public static boolean canManageExternalStorage() {
        boolean result;
        if (Build.VERSION.SDK_INT<Build.VERSION_CODES.M) {
            if (log.isTraceEnabled()) log.trace("canManageExternalStorage: API<23 -> true");
            return true;
        } else {
            if(Build.VERSION.SDK_INT>=30) {
                result = Environment.isExternalStorageManager();
                if (log.isTraceEnabled()) log.trace("canManageExternalStorage: API>=30 -> {}", result);
                return result;
            } else {
                if (log.isTraceEnabled()) log.trace("canManageExternalStorage: 23<=API<=29 -> true");
                return true;
            }
        }
    }

    public static boolean canReadExternalStorage(Context context) {
        boolean result = false;
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.getPackageName()));
        boolean externalStoragePermissionGrantable = intent.resolveActivity(context.getPackageManager()) != null;
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.M) {
            if (log.isDebugEnabled()) log.debug("canReadExternalStorage: API<23 -> true");
            return true;
        } else {
            if (Build.VERSION.SDK_INT>29 && hasManageExternalStoragePermission(context) && externalStoragePermissionGrantable) {
                result = Environment.isExternalStorageManager();
                if (log.isDebugEnabled()) log.debug("canReadExternalStorage: API>29 -> {}", result);
                return result;
            } else {
                if(Build.VERSION.SDK_INT<33) {
                    result = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
                    if (log.isDebugEnabled()) log.debug("canReadExternalStorage: 22<API<30 -> {}", result);
                } else {
                    result = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
                    if (log.isDebugEnabled()) log.debug("canReadExternalStorage: 32<API -> {}", result);
                }
                return result;
            }
        }
    }

    public static void caughtException(Throwable e, String method, String exceptionType) {
        if (log.isTraceEnabled()) log.error("{}: caught {} ", method, exceptionType, e);
        else log.warn("{}: caught {}", method, exceptionType);
    }
}

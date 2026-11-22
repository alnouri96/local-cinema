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

package com.archos.mediacenter.utils;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.util.Log;


import com.archos.filecorelibrary.FileUtils;
import com.archos.filecorelibrary.samba.SambaDiscovery;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public enum ShortcutDbAdapter {

    VIDEO(ShortcutDbAdapter.DATABASE_VIDEO_TABLE, ShortcutDbAdapter.DATABASE_CREATE_VIDEO5);

    private static final String TAG = "ShortcutDbAdapter";
    private final static boolean DBG = false;

    // To be incremented each time the architecture of the database is changed
    private static final int DATABASE_VERSION = 5;

    public static final String KEY_PATH = "path";
    public static final String KEY_IPPATH = "ippath";
    public static final String KEY_FRIENDLY_URI= "friendly_url";
    // indicates if a folder needs to be rescanned automatically
    public static final String KEY_RESCAN= "rescan";

    public static final String KEY_NAME = "name";
    public static final String KEY_ROWID = "_id";

    private static final String DATABASE_NAME = "shortcuts_db";
    private static final String DATABASE_VIDEO_TABLE = "shortcuts_table_video";

    private static final String DATABASE_CREATE_VIDEO =
        "create table shortcuts_table_video (_id integer primary key autoincrement, " + KEY_PATH + " text not null, " +  KEY_NAME + " text not null, " + KEY_IPPATH + " text" + ", "+KEY_FRIENDLY_URI+" text);";

    private static final String DATABASE_CREATE_VIDEO5 =
            "create table shortcuts_table_video (_id integer primary key autoincrement, "
                    + KEY_PATH + " text not null, "
                    + KEY_NAME + " text not null, "
                    + KEY_IPPATH + " text" + ", "
                    + KEY_FRIENDLY_URI + " text" + ", "
                    + KEY_RESCAN + " INTEGER DEFAULT (1)" +
                    ");";


    private static final String[] SHORTCUT_COLS = { KEY_ROWID, KEY_PATH, KEY_IPPATH, KEY_NAME, KEY_FRIENDLY_URI, KEY_RESCAN };

    private DatabaseHelper mDbHelper;
    // The path is the only info the other classes need to know.
    // The database id is only needed locally for the database management
    private SQLiteDatabase mDb;
    private final String mDatabaseTable;

    public boolean isDbOpen() {
        return mDb != null && mDb.isOpen();
    }

    public static boolean isSmbScheme(String scheme) {
        return "smb".equalsIgnoreCase(scheme) || "smbj".equalsIgnoreCase(scheme);
    }

    /** Removes trailing slashes on non-root network paths while preserving readable segment casing. */
    public static String normalizeStoredUri(String rawUri) {
        if (rawUri == null) return "";
        String trimmed = rawUri.trim();
        if (trimmed.isEmpty()) return "";
        Uri uri = Uri.parse(trimmed);
        String scheme = uri.getScheme();
        if (scheme == null) return trimmed;
        List<String> segments = uri.getPathSegments();
        if (segments != null && !segments.isEmpty()) {
            while (trimmed.endsWith("/") && trimmed.length() > scheme.length() + 4) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    /**
     * Produces a canonical comparison key for network/SMB URIs:
     * unifies smb/smbj, resolves SMB share name to IP when known, drops default port 445,
     * strips trailing slashes, decodes segments, and lowercases everything.
     */
    public static String canonicalUriKey(String rawUri) {
        if (rawUri == null) return "";
        String trimmed = rawUri.trim();
        if (trimmed.isEmpty()) return "";
        Uri uri = Uri.parse(trimmed);
        String scheme = uri.getScheme();
        if (scheme == null || scheme.isEmpty()) {
            return trimmed.replaceAll("/+$", "").toLowerCase(Locale.ROOT);
        }
        String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        if (isSmbScheme(lowerScheme)) {
            lowerScheme = "smb";
        }
        String host = uri.getHost();
        String lowerHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if ("smb".equals(lowerScheme) && !lowerHost.isEmpty()) {
            String ip = SambaDiscovery.getIpFromShareName(host);
            if (ip != null && !ip.isEmpty()) {
                lowerHost = ip.toLowerCase(Locale.ROOT);
            }
        }
        int port = uri.getPort();
        if ("smb".equals(lowerScheme) && port == 445) {
            port = -1;
        }
        List<String> segments = uri.getPathSegments();
        StringBuilder pathBuilder = new StringBuilder();
        if (segments != null) {
            for (String seg : segments) {
                if (seg == null || seg.isEmpty()) continue;
                String decoded = Uri.decode(seg);
                if (decoded == null || decoded.isEmpty()) continue;
                pathBuilder.append('/').append(decoded.toLowerCase(Locale.ROOT));
            }
        }
        StringBuilder out = new StringBuilder(lowerScheme).append("://").append(lowerHost);
        if (port > 0) out.append(':').append(port);
        out.append(pathBuilder);
        return out.toString();
    }

    public static boolean isSameCanonicalUri(String first, String second) {
        String a = canonicalUriKey(first);
        String b = canonicalUriKey(second);
        return !a.isEmpty() && a.equals(b);
    }

    /** Returns true if {@code ancestorUri} equals or is a parent directory of {@code candidateUri}. */
    public static boolean isSameOrAncestorUri(String ancestorUri, String candidateUri) {
        String ak = canonicalUriKey(ancestorUri);
        String ck = canonicalUriKey(candidateUri);
        if (ak.isEmpty() || ck.isEmpty()) return false;
        return ck.equals(ak) || ck.startsWith(ak + "/");
    }

    /**
     * Removes duplicate indexed folders (same canonical SMB URI) and any nested child folders
     * whose ancestor is already indexed, preserving the original parent entry.
     */
    public synchronized int cleanupDuplicateShortcuts(Context context) {
        if (context == null) return 0;
        int removed = 0;
        try (SQLiteDatabase db = new DatabaseHelper(context.getApplicationContext()).getWritableDatabase();
             Cursor c = db.query(mDatabaseTable, SHORTCUT_COLS, null, null, null, null, KEY_ROWID + " ASC")) {
            if (c == null) return 0;
            class Row {
                final long id;
                final String key;
                Row(long id, String path) {
                    this.id = id;
                    this.key = canonicalUriKey(path);
                }
            }
            List<Row> rows = new ArrayList<>();
            int idCol = c.getColumnIndexOrThrow(KEY_ROWID);
            int pathCol = c.getColumnIndexOrThrow(KEY_PATH);
            while (c.moveToNext()) {
                rows.add(new Row(c.getLong(idCol), c.getString(pathCol)));
            }
            Map<String, Row> uniqueByKey = new LinkedHashMap<>();
            List<Long> toDelete = new ArrayList<>();
            for (Row row : rows) {
                if (row.key.isEmpty()) continue;
                if (uniqueByKey.containsKey(row.key)) {
                    toDelete.add(row.id);
                } else {
                    uniqueByKey.put(row.key, row);
                }
            }
            List<Row> surviving = new ArrayList<>(uniqueByKey.values());
            for (Row child : surviving) {
                for (Row parent : surviving) {
                    if (parent.id == child.id) continue;
                    if (parent.key.length() < child.key.length() && child.key.startsWith(parent.key + "/")) {
                        toDelete.add(child.id);
                        break;
                    }
                }
            }
            for (Long id : toDelete) {
                removed += db.delete(mDatabaseTable, KEY_ROWID + " = ?", new String[]{Long.toString(id)});
            }
        } catch (Exception e) {
            Log.w(TAG, "cleanupDuplicateShortcuts error", e);
        }
        return removed;
    }

    public boolean isHimselfOrAncestorShortcut(Context context, String path) {
        if (context == null || path == null || path.isEmpty()) return false;
        try (SQLiteDatabase db = new DatabaseHelper(context.getApplicationContext()).getReadableDatabase();
             Cursor c = db.query(mDatabaseTable, new String[]{KEY_PATH}, null, null, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    if (isSameOrAncestorUri(c.getString(0), path)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "isHimselfOrAncestorShortcut error", e);
        }
        return false;
    }


    public static class Shortcut implements Serializable{
        private final String mName;
        private final String mUri;
        private final String mFriendlyUri;
        private Long mId;
        private boolean mRescan;

        public String getUri() {
            return mUri;
        }
        public String getName() {
            return mName;
        }
        public boolean getRescan() {
            return mRescan;
        }

        public Long getID() {
            return mId;
        }
        public void setID(Long id) {
            mId = id;
        }
        public String getFriendlyUri() {
            return mFriendlyUri;
        }
        public Shortcut(String name, String uri, String friendlyUri){
            mName = name;
            mUri = uri;
            mId = Long.valueOf(-1);
            mRescan = true;
            if(friendlyUri!=null&&!friendlyUri.isEmpty())
                mFriendlyUri = friendlyUri;
            else
                mFriendlyUri = mUri;
        }
        public Shortcut(String name, String uri){
            this(name, uri, uri);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Shortcut shortcut = (Shortcut) o;

            return !(mUri != null ? !mUri.equals(shortcut.mUri) : shortcut.mUri != null);

        }

        @Override
        public int hashCode() {
            return mUri != null ? mUri.hashCode() : 0;
        }
    }
    ShortcutDbAdapter(String databaseTable, String databaseCreate) {
        mDatabaseTable = databaseTable;
    }

    /*
     * Open the shortcut database
     */
    private void open(Context ct) throws SQLException {
        if(mDbHelper==null&&ct!=null)
            mDbHelper = new DatabaseHelper(ct.getApplicationContext());
        if(mDbHelper!=null)
            mDb = mDbHelper.getWritableDatabase();
    }

    /*
     * Close the shortcut database
     */
    public void close() {
        if (mDb != null && mDb.isOpen()) {
            mDb.close();
        }
        if (mDbHelper != null) {
            mDbHelper.close();
        }
    }

    /**
     * NEW 2015: better have direct access to the cursor
     * @return
     */
    public Cursor queryAllShortcuts(Context context) {
        cleanupDuplicateShortcuts(context);
        return getAllShortcuts(context, null, null);
    }

    public int numberOfShortcuts(Context context) {
        Cursor shortcuts = getAllShortcuts(context, null, null);
        if (shortcuts == null)
            return 0;
        else {
            int count = shortcuts.getCount();
            shortcuts.close();
            return count;
        }
    }

    /**
     * NEW 2015: better have direct access to the DB
     * This new version does not need the ipPath parameter, it just use the regular path + a name
     * @return true if it succeeded
     */
    public boolean addShortcut(Context context, Shortcut shortcut) {
        if (context == null || shortcut == null || shortcut.getUri() == null) return false;
        cleanupDuplicateShortcuts(context);
        String storedUri = normalizeStoredUri(shortcut.getUri());
        String storedFriendly = shortcut.getFriendlyUri() != null && !shortcut.getFriendlyUri().isEmpty()
                ? normalizeStoredUri(shortcut.getFriendlyUri())
                : storedUri;
        String candKey = canonicalUriKey(storedUri);
        if (candKey.isEmpty()) return false;
        try (SQLiteDatabase db = new DatabaseHelper(context.getApplicationContext()).getWritableDatabase()) {
            List<Long> descendantIds = new ArrayList<>();
            try (Cursor c = db.query(mDatabaseTable, new String[]{KEY_ROWID, KEY_PATH}, null, null, null, null, KEY_ROWID + " ASC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        long existingId = c.getLong(0);
                        String existingPath = c.getString(1);
                        String existKey = canonicalUriKey(existingPath);
                        if (existKey.isEmpty()) continue;
                        if (candKey.equals(existKey) || candKey.startsWith(existKey + "/")) {
                            shortcut.setID(existingId);
                            return false;
                        }
                        if (existKey.startsWith(candKey + "/")) {
                            descendantIds.add(existingId);
                        }
                    }
                }
            }
            for (Long childId : descendantIds) {
                db.delete(mDatabaseTable, KEY_ROWID + " = ?", new String[]{Long.toString(childId)});
            }
            ContentValues val = new ContentValues(5);
            val.put(KEY_PATH, storedUri);
            val.put(KEY_IPPATH, storedUri);
            val.put(KEY_NAME, shortcut.getName());
            val.put(KEY_RESCAN, shortcut.getRescan());
            val.put(KEY_FRIENDLY_URI, storedFriendly);
            long id = db.insert(mDatabaseTable, null, val);
            boolean success = (id != -1);
            shortcut.setID(id);
            return success;
        }
    }

    /**
     * NEW 2015: better have direct access to the DB
     * @return true if it succeeded
     */
    public void renameShortcut(Context context, long shortcutId, String name) {
        try (SQLiteDatabase db = new DatabaseHelper(context).getWritableDatabase()) {
            ContentValues values = new ContentValues(1);
            values.put(KEY_NAME, name);
            db.update(mDatabaseTable, values, KEY_ROWID + " = ? AND " + KEY_NAME + " <> ?",
                    new String[]{Long.toString(shortcutId), name});
        }
    }

    public boolean deleteShortcut(Context context, long shortcutId) {
        open(context);
        SQLiteDatabase db = new DatabaseHelper(context).getWritableDatabase();
        boolean success = (db.delete(mDatabaseTable, KEY_ROWID + " = ?", new String[]{Long.toString(shortcutId)}) > 0);

        db.close();
        return success;
    }

    /**
     * NEW 2015: better have direct access to the DB
     * @return the (first) shortcut ID if this path is in the shortcut database
     */
    public long isShortcut(Context context, String path) {
        if (context == null || path == null || path.isEmpty()) return -1;
        String targetKey = canonicalUriKey(path);
        long id = -1;
        try (SQLiteDatabase db = new DatabaseHelper(context.getApplicationContext()).getReadableDatabase();
             Cursor c = db.query(mDatabaseTable, new String[]{KEY_ROWID, KEY_PATH}, null, null, null, null, KEY_ROWID + " ASC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    String rowPath = c.getString(1);
                    if (path.equals(rowPath) || (!targetKey.isEmpty() && targetKey.equals(canonicalUriKey(rowPath)))) {
                        id = c.getLong(0);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "isShortcut error", e);
        }
        return id;
    }

    public boolean deleteShortcut(Context context, String uri) {
        if (context == null || uri == null || uri.isEmpty()) return false;
        String targetKey = canonicalUriKey(uri);
        int deleted = 0;
        try (SQLiteDatabase db = new DatabaseHelper(context.getApplicationContext()).getWritableDatabase()) {
            deleted = db.delete(mDatabaseTable, KEY_PATH + " = ? OR " + KEY_IPPATH + " = ?", new String[]{uri, uri});
            if (!targetKey.isEmpty()) {
                List<Long> matchingIds = new ArrayList<>();
                try (Cursor c = db.query(mDatabaseTable, new String[]{KEY_ROWID, KEY_PATH}, null, null, null, null, null)) {
                    if (c != null) {
                        while (c.moveToNext()) {
                            if (targetKey.equals(canonicalUriKey(c.getString(1)))) {
                                matchingIds.add(c.getLong(0));
                            }
                        }
                    }
                }
                for (Long id : matchingIds) {
                    deleted += db.delete(mDatabaseTable, KEY_ROWID + " = ?", new String[]{Long.toString(id)});
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "deleteShortcut error", e);
        }
        return deleted > 0;
    }

    public Cursor getAllShortcuts(Context context, String where, String [] whereArgs) {
        try {
            open(context);
            if (mDb == null || !mDb.isOpen()) {
                Log.e(TAG, "getAllShortcuts: Database is closed or null!");
                return null;
            }
            return mDb.query(mDatabaseTable,
                    SHORTCUT_COLS,
                    where,
                    whereArgs,
                    null,
                    null,
                    null);
        }
        catch (SQLiteException | NullPointerException e) {
            // The table corresponding to this type does not exist yet
            Log.w(TAG, "getAllShortcuts: Exception occurred", e);
            return null;
        }
    }

    public void setRescanShortcut(Context context, boolean toRescan, String shortcutName) {
        // mark shortcut to be considered for automatic/global rescan
        open(context);
        ContentValues cv = new ContentValues(1);
        cv.put(KEY_RESCAN, toRescan ? 1 : 0);
        mDb.update(mDatabaseTable, cv, KEY_NAME + " = ?", new String[]{shortcutName});
        close();
    }

    public List<Shortcut> cursorToShortcutList(Cursor cursor){
        List<Shortcut>shortcuts = new ArrayList<>();
        if(cursor.getCount()>0){
            cursor.moveToFirst();
            do{
                shortcuts.add(cursorToShortcut(cursor));
            }while (cursor.moveToNext());
        }
        return shortcuts;
    }
    private Shortcut cursorToShortcut(Cursor cursor){
        int rowIdColumnIndex = cursor.getColumnIndex(KEY_ROWID);
        int pathColumnIndex = cursor.getColumnIndex(KEY_PATH);
        int nameColumnIndex = cursor.getColumnIndex(KEY_NAME);
        int friendlyUriColumnIndex = cursor.getColumnIndex(KEY_FRIENDLY_URI);
        String path = cursor.getString(pathColumnIndex);
        String name = cursor.getString(nameColumnIndex);
        String friendlyuri = cursor.getString(friendlyUriColumnIndex);
        Shortcut shortcut = new Shortcut(name, path, friendlyuri);
        long rowId = cursor.getLong(rowIdColumnIndex);
        shortcut.setID(rowId);
        return shortcut;
    }
    private static class DatabaseHelper extends SQLiteOpenHelper {

        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            if (DBG) Log.d(TAG, "onCreate: creating db " + DATABASE_VIDEO_TABLE);
            // This method is only called once when the database is created for the first time
            db.execSQL(DATABASE_CREATE_VIDEO5);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.d(TAG, "onUpgrade: upgrading database from version " + oldVersion + " to " + newVersion);
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE "+DATABASE_VIDEO_TABLE+" ADD COLUMN " + KEY_IPPATH + " TEXT");
            }
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE "+DATABASE_VIDEO_TABLE+" ADD COLUMN " + KEY_NAME + " TEXT");
            }
            if (oldVersion < 4) {
                db.execSQL("ALTER TABLE "+DATABASE_VIDEO_TABLE+" ADD COLUMN " + KEY_FRIENDLY_URI + " TEXT");
            }
            if (oldVersion < 5) {
                db.execSQL("ALTER TABLE "+DATABASE_VIDEO_TABLE+" ADD COLUMN " + KEY_RESCAN + " INTEGER DEFAULT (1)");
            }
        }
    }


}

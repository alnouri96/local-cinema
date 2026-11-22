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

package com.archos.mediacenter.video.browser;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.BaseColumns;

import androidx.loader.content.CursorLoader;

import com.archos.mediacenter.utils.ShortcutDbAdapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public enum ShortcutDb {

    STATIC();

    private static final Logger log = LoggerFactory.getLogger(ShortcutDb.class);

    private static final String DATABASE_NAME = "shortcuts2_db";
    private static final String TABLE_NAME = "shortcuts";

    // To be incremented each time the architecture of the database is changed
    private static final int DATABASE_VERSION = 2;

    public static final String KEY_URI = "uri";
    public static final String KEY_SHORTCUT_NAME = "shortcut_name";
    public static final String KEY_FRIENDLY_URI = "friendly_uri";
    private static final String[] SHORTCUT_COLS = { BaseColumns._ID, KEY_URI, KEY_SHORTCUT_NAME, KEY_FRIENDLY_URI };

    private DatabaseHelper mDbHelper;
    private synchronized SQLiteDatabase getDb(Context context) {
        if (mDbHelper == null)
            mDbHelper = new DatabaseHelper(context.getApplicationContext());
        return mDbHelper.getWritableDatabase();
    }

    /**
     * get a CursorLoader to get all columns of all shortcuts
     * @param context
     * @return
     */
    public CursorLoader getAllShortcutsCursorLoader(Context context) {
        return new CursorLoader(context) {
            @Override
            public Cursor loadInBackground() {
                return getCursorAllShortcuts(getContext());
            }
        };
    }

    public long isShortcut(Context context, String path) {
        if (context == null || path == null || path.isEmpty()) return -1;
        String targetKey = ShortcutDbAdapter.canonicalUriKey(path);
        long id = -1;
        SQLiteDatabase db = getDb(context);
        try (Cursor c = db.query(TABLE_NAME, new String[]{BaseColumns._ID, KEY_URI}, null, null, null, null, BaseColumns._ID + " ASC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    String rowUri = c.getString(1);
                    if (path.equals(rowUri) || (!targetKey.isEmpty() && targetKey.equals(ShortcutDbAdapter.canonicalUriKey(rowUri)))) {
                        id = c.getLong(0);
                        break;
                    }
                }
            }
        } catch (SQLiteException e) {
            log.warn("isShortcut: caught SQLiteException", e);
        }
        return id;
    }

    /** Removes duplicate shortcuts sharing the same canonical URI, preserving the oldest entry. */
    public synchronized int cleanupDuplicateShortcuts(Context context) {
        if (context == null) return 0;
        int removed = 0;
        SQLiteDatabase db = getDb(context);
        try (Cursor c = db.query(TABLE_NAME, new String[]{BaseColumns._ID, KEY_URI}, null, null, null, null, BaseColumns._ID + " ASC")) {
            if (c == null) return 0;
            java.util.Set<String> seen = new java.util.HashSet<>();
            List<Long> toDelete = new ArrayList<>();
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String key = ShortcutDbAdapter.canonicalUriKey(c.getString(1));
                if (key.isEmpty()) continue;
                if (!seen.add(key)) {
                    toDelete.add(id);
                }
            }
            for (Long id : toDelete) {
                removed += db.delete(TABLE_NAME, BaseColumns._ID + "=?", new String[]{Long.toString(id)});
            }
        } catch (SQLiteException e) {
            log.warn("cleanupDuplicateShortcuts: caught SQLiteException", e);
        }
        return removed;
    }

    public class Shortcut implements Serializable{
        public String name;
        public String uri;
        public String friendlyUri;
    }
    /**
     * get Cursor with all columns of all shortcuts
     * @param context
     * @return
     */
    public Cursor getCursorAllShortcuts(Context context) {
        if (context == null) return null;
        cleanupDuplicateShortcuts(context);
        try {
            SQLiteDatabase db = getDb(context);
            return db.query(TABLE_NAME,
                    SHORTCUT_COLS,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        catch (SQLiteException e) {
            // The table corresponding to this type does not exist yet
            log.warn("getCursorAllShortcuts: caught SQLiteException", e);
            return null;
        }
    }

    public int numberOfShortcuts(Context context) {
        Cursor shortcuts = getCursorAllShortcuts(context);
        if (shortcuts == null)
            return 0;
        else {
            int count = shortcuts.getCount();
            shortcuts.close();
            return count;
        }
    }

    /**
     * get a CursorLoader to get all columns of all shortcuts
     * @param context
     * @return
     */
    public List<Shortcut> getAllShortcuts(Context context) {
        Cursor shortcutsCursor = getCursorAllShortcuts(context);
        List<Shortcut> shortcuts = new ArrayList<Shortcut>();
        if (shortcutsCursor == null)
            return shortcuts;
        int nameColumn = shortcutsCursor.getColumnIndex(KEY_SHORTCUT_NAME);
        int uriColumn = shortcutsCursor.getColumnIndex(KEY_URI);
        int friendlyUriColumn = shortcutsCursor.getColumnIndex(KEY_FRIENDLY_URI);
        if (shortcutsCursor.getCount()>0){
            shortcutsCursor.moveToFirst();
            do {
                Shortcut shortcut = new Shortcut();
                shortcut.uri = shortcutsCursor.getString(uriColumn);
                shortcut.name = shortcutsCursor.getString(nameColumn);
                shortcut.friendlyUri = shortcutsCursor.getString(friendlyUriColumn);
                shortcuts.add(shortcut);
            } while(shortcutsCursor.moveToNext());
        }
        shortcutsCursor.close();
        return shortcuts;
    }

    /**
     * Insert a new shortcut into the database
     * @param uri: string obtained with android.net.Uri.toString()
     * @param name
     * @return true if the insert succeeded
     */
    public boolean insertShortcut(Context context, Uri uri, String name, String friendlyUri) {
        if (context == null || uri == null) return false;
        cleanupDuplicateShortcuts(context);
        String storedUri = ShortcutDbAdapter.normalizeStoredUri(uri.toString());
        if (isShortcut(context, storedUri) >= 0) return false;
        if (log.isDebugEnabled()) log.debug("insertShortcut {} {}", storedUri, name);
        if (name == null) name = storedUri;
        String storedFriendly = friendlyUri != null && !friendlyUri.isEmpty()
                ? ShortcutDbAdapter.normalizeStoredUri(friendlyUri) : storedUri;
        ContentValues initialValues = new ContentValues(3);
        initialValues.put(KEY_URI, storedUri);
        initialValues.put(KEY_SHORTCUT_NAME, name);
        initialValues.put(KEY_FRIENDLY_URI, storedFriendly);

        SQLiteDatabase db = getDb(context);
        return (db.insert(TABLE_NAME, null, initialValues)!=-1);
    }

    /**
     * Remove the shortcut(s) corresponding to the provided Uri
     * 
     * @param uri 
     * @return the number of shortcuts removed
     */
    public int removeShortcut(Context context, Uri uri) {
        if (context == null || uri == null) return 0;
        String raw = uri.toString();
        String targetKey = ShortcutDbAdapter.canonicalUriKey(raw);
        SQLiteDatabase db = getDb(context);
        int result = db.delete(TABLE_NAME, KEY_URI + "=?", new String[]{raw});
        if (!targetKey.isEmpty()) {
            List<Long> matchingIds = new ArrayList<>();
            try (Cursor c = db.query(TABLE_NAME, new String[]{BaseColumns._ID, KEY_URI}, null, null, null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        if (targetKey.equals(ShortcutDbAdapter.canonicalUriKey(c.getString(1)))) {
                            matchingIds.add(c.getLong(0));
                        }
                    }
                }
            }
            for (Long id : matchingIds) {
                result += db.delete(TABLE_NAME, BaseColumns._ID + "=?", new String[]{Long.toString(id)});
            }
        }
        if (log.isDebugEnabled()) log.debug("removeShortcut {} mDb.delete returns {}", raw, result);

        return result;
    }

    /**
     * Remove the shortcut(s) corresponding to the provided DB id
     * @return true if it erased something
     */
    public boolean removeShortcut(Context context, long id) {
        final String selection = BaseColumns._ID+"=?";
        final String[] selectionArgs = new String[] {Long.toString(id)};

        SQLiteDatabase db = getDb(context);
        int result = db.delete(TABLE_NAME, selection, selectionArgs);
        if (log.isDebugEnabled()) log.debug("removeShortcut {} mDb.delete returns {}", id, result);

        return result>0;
    }

    private class DatabaseHelper extends SQLiteOpenHelper {

        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // This method is only called once when the database is created for the first time
            db.execSQL("create table " + TABLE_NAME + "( "
                    + BaseColumns._ID + " integer primary key autoincrement, "
                    + KEY_URI + " text not null, "
                    + KEY_SHORTCUT_NAME + " text not null, "
                    + KEY_FRIENDLY_URI + " text );");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            log.info("onUpgrade: upgrading database from version " + oldVersion + " to " + newVersion);
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE "+TABLE_NAME+" ADD COLUMN " + KEY_FRIENDLY_URI + " TEXT");
            }
        }
    }
}

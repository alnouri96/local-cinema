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

import java.io.InputStream;
import java.io.OutputStream;

import android.content.Context;
import android.net.Uri;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides methods to edit files, and everything about metafile2 that requires a connection
 * @author alexandre
 * 
 * every action can rise an exception such as
 * FileNotFoundException
 * AuthenticationException
 * UnknownHostException
 * IOException, 
 * and a custom Exception (used in my case for permission exception)
 */
public abstract class FileEditor {

    private static final Logger log = LoggerFactory.getLogger(FileEditor.class);

    protected Uri mUri;
    private static final int MAX_COUNT = 32768;
    private static final ThreadLocal<Integer> sManualSmbMkdirDepth = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };

    /** Marks the current thread as executing an explicit user-requested SMB directory creation. */
    public static void beginManualSmbOperation() {
        Integer depth = sManualSmbMkdirDepth.get();
        sManualSmbMkdirDepth.set((depth != null ? depth : 0) + 1);
    }

    /** Ends an explicit user-requested SMB directory creation scope on the current thread. */
    public static void endManualSmbOperation() {
        Integer depth = sManualSmbMkdirDepth.get();
        int next = (depth != null ? depth : 0) - 1;
        if (next <= 0) {
            sManualSmbMkdirDepth.remove();
        } else {
            sManualSmbMkdirDepth.set(next);
        }
    }

    /** Returns true only when the caller explicitly authorized an SMB directory write for a user action. */
    public static boolean isManualSmbOperation() {
        Integer depth = sManualSmbMkdirDepth.get();
        return depth != null && depth > 0;
    }

    public FileEditor(Uri uri){
        mUri = uri;
    }
    public Uri getUri() { return mUri; }
    public boolean touchFile() { return false; };
    public boolean mkdir() { return false; };
    public abstract InputStream getInputStream() throws Exception;
    public abstract InputStream getInputStream(long from) throws Exception;
    public OutputStream getOutputStream() throws Exception { return null; };
    /** Create a new file without replacing an existing target. Unsupported protocols fail closed. */
    public OutputStream getOutputStreamExclusive() throws Exception { throw new UnsupportedOperationException("Exclusive create unavailable"); }
    public Boolean delete() throws Exception { return null; };
    public boolean rename(String newName) { return false; };
    public boolean move(Uri uri) { return false; };
    public abstract boolean exists();
    public long length() throws Exception { return -1; }

    /**
     * Use it for file (and just file) copy
     * @param target
     * @return
     * @throws Exception
     */
    public boolean copyFileTo(Uri target, Context ct) throws Exception {
        FileEditor targetEditor = FileEditorFactory.getFileEditorForUrl(target, ct);
        OutputStream out = targetEditor.getOutputStream();
        if (log.isDebugEnabled()) log.debug("copyFileTo: {}->{}", mUri, target);
        InputStream in = getInputStream();
        if(in!=null&&out!=null) {
            long position = 0;
            byte buf[] = new byte[MAX_COUNT];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
                position += (long) len;
            }
            out.close();
            in.close();
            return true;
        }
        return false;
    }
}

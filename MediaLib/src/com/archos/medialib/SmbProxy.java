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

package com.archos.medialib;

import static com.archos.filecorelibrary.FileUtils.encodeUri;

import android.net.Uri;

import com.archos.filecorelibrary.MetaFile2;
import com.archos.filecorelibrary.MetaFile2Factory;
import com.archos.filecorelibrary.MimeUtils;
import com.archos.filecorelibrary.StreamOverHttp;
import com.archos.mediacenter.filecoreextension.UriUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

public class SmbProxy extends Proxy{

    private static final Logger log = LoggerFactory.getLogger(SmbProxy.class);
    private StreamOverHttp mStream;

    protected SmbProxy(Uri uri) {
        super(uri);
    }
    public static boolean needToStream(String scheme){
            return "smb".equalsIgnoreCase(scheme) ||
                    "ftp".equalsIgnoreCase(scheme) ||
                    "ftps".equalsIgnoreCase(scheme) ||
                    "sftp".equalsIgnoreCase(scheme) ||
                    "sshj".equalsIgnoreCase(scheme) ||
                    "webdav".equalsIgnoreCase(scheme) ||
                    "webdavs".equalsIgnoreCase(scheme) ||
                    "smbj".equalsIgnoreCase(scheme) ||
                    UriUtils.isContentUri(Uri.parse(scheme+"://test"));
    }
    protected Uri start() {
        stop();
        boolean isContent = "content".equalsIgnoreCase(mUri.getScheme());
        Uri encodedUri = isContent ? mUri : encodeUri(mUri);
        String ext = com.archos.filecorelibrary.FileUtils.getExtension(encodedUri.getLastPathSegment());
        String mimeType = ext != null ? MimeUtils.guessMimeTypeFromExtension(ext) : null;
        MetaFile2 file = null;
        try {
            if (!isContent) {
                try {
                    file = MetaFile2Factory.getMetaFileForUrl(mUri);
                } catch (Exception e) {
                    // this is not really an error
                    if (log.isTraceEnabled()) log.trace("start: error getting metafile for url {}", mUri, e);
                }
            }
            if(file != null) {
                mStream = new StreamOverHttp(file, mimeType);
            } else {
                // sftp at least requires encodedUri, while content:// must preserve its exact document URI encoding
                mStream = new StreamOverHttp(encodedUri, mimeType);
            }
        } catch (IOException e) {
            return null;
        }
        String streamName = file != null ? file.getName() : com.archos.filecorelibrary.FileUtils.getName(mUri);
        if (streamName == null || streamName.isEmpty()) streamName = "video";
        return mStream.getUri(streamName);
    }
    
    public void stop() {
        if (mStream != null) {
            mStream.close();
            mStream = null;
        } 
    }
    
    public static SmbProxy setDataSource(Uri uri, IMediaPlayer mp, Map<String, String> headers) throws IOException {
        SmbProxy smbProxy = new SmbProxy(uri);
        Uri newUri = smbProxy.start();
        if (newUri != null) {
            if(headers!=null)
                mp.setDataSource2(newUri.toString(), headers);
            else
                mp.setDataSource(newUri.toString());
            return smbProxy;
        } else {
            throw new IOException();
        }
    }
    
    public static SmbProxy setDataSource(Uri uri, IMediaMetadataRetriever mr, Map<String, String> headers) throws IllegalArgumentException {
        SmbProxy smbProxy = new SmbProxy(uri);
        Uri newUri = smbProxy.start();
        if (newUri != null) {
            mr.setDataSource(newUri.toString(), headers);
            return smbProxy;
        } else {
            throw new IllegalArgumentException();
        }
    }

    public int doesCurrentFileExists() {
        return mStream.doesCurrentFileExists();
    }
}

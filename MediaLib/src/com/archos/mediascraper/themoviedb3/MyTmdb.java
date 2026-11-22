// Copyright 2020 Courville Software
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

package com.archos.mediascraper.themoviedb3;

import com.archos.mediascraper.ScraperCache;
import com.uwetrottmann.tmdb2.Tmdb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.OkHttpClient;
import android.content.Context;
import java.io.IOException;
import com.meedx.providers.ProviderKeys;

public class MyTmdb extends Tmdb {

    private static final Logger log = LoggerFactory.getLogger(MyTmdb.class);
    private final static boolean CACHE = true;
    private final Cache mCache;

    private final Context context;

    public MyTmdb(Context context, Cache cache) {
        super("");
        this.context = context;
        this.mCache = cache;
    }

    @Override
    protected void setOkHttpClientDefaults(OkHttpClient.Builder builder) {
        super.setOkHttpClientDefaults(builder);
        builder.addInterceptor(chain -> {
            okhttp3.Request request = chain.request();
            if (!"api.themoviedb.org".equals(request.url().host())) {
                throw new IOException("Unexpected metadata host");
            }
            if (context == null) throw new IOException("Metadata provider is not initialized");
            String key = ProviderKeys.require(context, ProviderKeys.TMDB);
            okhttp3.HttpUrl url = request.url().newBuilder().setQueryParameter("api_key", key).build();
            return chain.proceed(request.newBuilder().url(url).build());
        });
        if (CACHE && mCache != null) {
            builder.cache(mCache).addNetworkInterceptor(new ScraperCache.CacheInterceptor());
            if (log.isTraceEnabled()) {
                builder.addInterceptor(new ScraperCache.isCacheResponding());
            }
        }
        builder.connectTimeout(ScraperCache.CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        builder.readTimeout(ScraperCache.READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }
}

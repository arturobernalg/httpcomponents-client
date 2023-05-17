package org.apache.hc.client5.http;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.apache.hc.client5.http.cache.HttpCacheCASOperation;
import org.apache.hc.client5.http.cache.HttpCacheEntry;
import org.apache.hc.client5.http.cache.HttpCacheStorage;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.cache.CacheConfig;
import org.apache.hc.client5.http.impl.cache.CachingHttpClients;

public class HttpClientFactory {

    private static SurespotHttpCacheStorage mCacheStorage;

    private static HttpCacheStorage getHttpCacheStorage() {
        if (mCacheStorage == null) {
            mCacheStorage = new SurespotHttpCacheStorage();
        }
        return mCacheStorage;
    }

    public static HttpClient createHttpClient() {

        return CachingHttpClients.custom()
                .setHttpCacheStorage(getHttpCacheStorage())
                .setCacheConfig(getDiskCacheConfig())
                .build();
    }

    public static HashMap<String, HttpCacheEntry> mCache = new HashMap<>();

    public static class SurespotHttpCacheStorage implements HttpCacheStorage {

        @Override
        public HttpCacheEntry getEntry(String arg0) {
            System.out.println("getting entry: " + arg0);
            return mCache.get(arg0);
        }

        @Override
        public void putEntry(String key, HttpCacheEntry entry) {
            System.out.println("putting entry: " + key);
            mCache.put(key, entry);
            //	System.out.println(mCache.toString());
        }

        @Override
        public void removeEntry(String arg0) {
            System.out.println("removing entry: " + arg0);
            mCache.remove(arg0);
            //System.out.println(mCache.toString());
        }

        @Override
        public void updateEntry(String key, HttpCacheCASOperation casOperation) {
            System.out.println("updating entry: " + key);
            HttpCacheEntry entry = getEntry(key);
            putEntry(key,entry);
        }

        @Override
        public Map<String, HttpCacheEntry> getEntries(Collection<String> keys) {
            return null;
        }

    }

    public static CacheConfig getDiskCacheConfig() {
        return CacheConfig.custom()
                .setMaxCacheEntries(500)
                .setMaxObjectSize(1000000)
                .build();
    }

    public static void dumpCache() {
        for (Map.Entry<String, HttpCacheEntry> entry : mCache.entrySet()) {
            System.out.println("cache key:  " + entry.getKey() + " content length:  " + entry.getValue().getResource().length());
        }
    }
}

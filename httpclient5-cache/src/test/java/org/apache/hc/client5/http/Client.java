package org.apache.hc.client5.http;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.hc.client5.http.cache.HttpCacheCASOperation;
import org.apache.hc.client5.http.cache.HttpCacheEntry;
import org.apache.hc.client5.http.cache.HttpCacheStorage;
import org.apache.hc.client5.http.cache.HttpCacheUpdateException;
import org.apache.hc.client5.http.cache.ResourceIOException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.cache.CacheConfig;
import org.apache.hc.client5.http.impl.cache.CachingHttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.io.entity.EntityUtils;

public class Client {

    public static void main(String[] args) throws Exception {

        SurespotHttpCacheStorage cacheStorage = new SurespotHttpCacheStorage();

        CloseableHttpClient client = CachingHttpClientBuilder.create()
                .setCacheConfig(CacheConfig.custom()
                        .setMaxCacheEntries(50)
                        .setMaxObjectSize(1000000)
                        .build()
                )
                .setHttpCacheStorage(cacheStorage)
                .build();

        client.execute(new HttpGet("http://localhost:8000/assets/1"), response -> {
            EntityUtils.consume(response.getEntity());
            return null;
        });
        client.execute(new HttpGet("http://localhost:8000/assets/2"), response -> {
            EntityUtils.consume(response.getEntity());
            return null;
        });
        client.execute(new HttpGet("http://localhost:8000/assets/1"), response -> {
            EntityUtils.consume(response.getEntity());
            return null;
        });
        client.execute(new HttpGet("http://localhost:8000/assets/2"), response -> {
            EntityUtils.consume(response.getEntity());
            return null;
        });

        cacheStorage.dumpCache();
    }

    public static class SurespotHttpCacheStorage implements HttpCacheStorage {

        private final HashMap<String, HttpCacheEntry> mCache = new HashMap<>();

        @Override
        public HttpCacheEntry getEntry(String arg0) {
            System.out.println("getting entry: " + arg0);
            return mCache.get(arg0);
        }

        @Override
        public void putEntry(String key, HttpCacheEntry entry) {
            System.out.println("putting entry: " + key);
            mCache.put(key, entry);
        }

        @Override
        public void removeEntry(String arg0) {
            System.out.println("removing entry: " + arg0);
            mCache.remove(arg0);
        }

        @Override
        public void updateEntry(String key, HttpCacheCASOperation casOperation) throws ResourceIOException, HttpCacheUpdateException {
            System.out.println("updating entry: " + key);
            HttpCacheEntry entry = getEntry(key);

            //I believe we should not update non existent entries in the "update method", but it throws exceptions otherwise
            //if (entry != null) {
            putEntry(key, casOperation.execute(entry));
//			} else {
//				throw new HttpCacheUpdateException("key not found: " + arg0);
//			}
            //System.out.println(mCache.toString());

        }

        @Override
        public Map<String, HttpCacheEntry> getEntries(Collection<String> keys) throws ResourceIOException {
            Set<String> set = new HashSet<>(keys);
            return mCache.entrySet().stream()
                    .filter(e -> set.contains(e.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        public void dumpCache() {
            for (Map.Entry<String, HttpCacheEntry> entry : mCache.entrySet()) {
                System.out.println("cache key:  " + entry.getKey() + " content length:  " + entry.getValue().getResource().length());
            }
        }

    }
}

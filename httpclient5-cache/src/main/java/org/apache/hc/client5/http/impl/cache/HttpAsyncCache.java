/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.client5.http.impl.cache;

import java.time.Instant;
import java.util.Collection;

import org.apache.hc.client5.http.cache.HttpCacheEntry;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.util.ByteArrayBuffer;

interface HttpAsyncCache {

    /**
     * Returns a result with either a fully matching {@link HttpCacheEntry}
     * a partial match with a list of known variants or null if no match could be found.
     */
    Cancellable match(HttpHost host, HttpRequest request, FutureCallback<CacheMatch> callback);

    /**
     * Retrieves variant {@link HttpCacheEntry}s for the given hit.
     */
    Cancellable getVariants(
            CacheHit hit, FutureCallback<Collection<CacheHit>> callback);

    /**
     * Stores {@link HttpRequest} / {@link HttpResponse} exchange details in the cache.
     */
    Cancellable store(
            HttpHost host,
            HttpRequest request,
            HttpResponse originResponse,
            ByteArrayBuffer content,
            Instant requestSent,
            Instant responseReceived,
            FutureCallback<CacheHit> callback);

    /**
     * Updates {@link HttpCacheEntry} using details from a 304 {@link HttpResponse} and
     * updates the root entry if the given cache entry represents a variant.
     */
    Cancellable update(
            CacheHit stale,
            HttpRequest request,
            HttpResponse originResponse,
            Instant requestSent,
            Instant responseReceived,
            FutureCallback<CacheHit> callback);

    /**
     * Updates {@link HttpCacheEntry} using details from a 304 {@link HttpResponse}.
     */
    Cancellable update(
            CacheHit stale,
            HttpResponse originResponse,
            Instant requestSent,
            Instant responseReceived,
            FutureCallback<CacheHit> callback);

    /**
     * Stores {@link HttpRequest} / {@link HttpResponse} exchange details in the cache
     * re-using the resource of the existing {@link HttpCacheEntry}.
     */
    Cancellable storeReusing(
            CacheHit hit,
            HttpHost host,
            HttpRequest request,
            HttpResponse originResponse,
            Instant requestSent,
            Instant responseReceived,
            FutureCallback<CacheHit> callback);

    /**
     * Evicts {@link HttpCacheEntry}s invalidated by the given message exchange.
     */
    Cancellable evictInvalidatedEntries(
            HttpHost host, HttpRequest request, HttpResponse response, FutureCallback<Boolean> callback);

}

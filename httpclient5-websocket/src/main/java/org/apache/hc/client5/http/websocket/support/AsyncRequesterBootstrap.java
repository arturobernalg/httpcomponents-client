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
package org.apache.hc.client5.http.websocket.support;

import org.apache.hc.core5.function.Callback;
import org.apache.hc.core5.function.Decorator;
import org.apache.hc.core5.http.ConnectionReuseStrategy;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.CharCodingConfig;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.impl.Http1StreamListener;
import org.apache.hc.core5.http.impl.HttpProcessors;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncRequester;
import org.apache.hc.core5.http.impl.nio.ClientHttp1IOEventHandlerFactory;
import org.apache.hc.core5.http.impl.nio.ClientHttp1StreamDuplexerFactory;
import org.apache.hc.core5.http.nio.ssl.BasicClientTlsStrategy;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.http.protocol.HttpProcessor;
import org.apache.hc.core5.pool.ConnPoolListener;
import org.apache.hc.core5.pool.DefaultDisposalCallback;
import org.apache.hc.core5.pool.LaxConnPool;
import org.apache.hc.core5.pool.ManagedConnPool;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.pool.StrictConnPool;
import org.apache.hc.core5.reactor.IOEventHandlerFactory;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.IOSessionListener;
import org.apache.hc.core5.util.Timeout;

/**
 * Bootstrap wrapper identical in spirit to HttpAsyncRequester bootstrap, but
 * exposes both requester and the connection pool (so the WebSocketRequester can be wired without reflection).
 *
 * @since 5.6
 */
public final class AsyncRequesterBootstrap {

    private IOReactorConfig ioReactorConfig;
    private Http1Config http1Config;
    private CharCodingConfig charCodingConfig;
    private HttpProcessor httpProcessor;
    private ConnectionReuseStrategy connStrategy;
    private int defaultMaxPerRoute;
    private int maxTotal;
    private Timeout timeToLive;
    private PoolReusePolicy poolReusePolicy;
    private PoolConcurrencyPolicy poolConcurrencyPolicy;
    private TlsStrategy tlsStrategy;
    private Timeout handshakeTimeout;
    private Decorator<IOSession> ioSessionDecorator;
    private Callback<Exception> exceptionCallback;
    private IOSessionListener sessionListener;
    private Http1StreamListener streamListener;
    private ConnPoolListener<HttpHost> connPoolListener;

    private AsyncRequesterBootstrap() {
    }

    public static AsyncRequesterBootstrap bootstrap() {
        return new AsyncRequesterBootstrap();
    }

    public AsyncRequesterBootstrap setIOReactorConfig(final IOReactorConfig v) {
        this.ioReactorConfig = v;
        return this;
    }

    public AsyncRequesterBootstrap setHttp1Config(final Http1Config v) {
        this.http1Config = v;
        return this;
    }

    public AsyncRequesterBootstrap setCharCodingConfig(final CharCodingConfig v) {
        this.charCodingConfig = v;
        return this;
    }

    public AsyncRequesterBootstrap setHttpProcessor(final HttpProcessor v) {
        this.httpProcessor = v;
        return this;
    }

    public AsyncRequesterBootstrap setConnectionReuseStrategy(final ConnectionReuseStrategy v) {
        this.connStrategy = v;
        return this;
    }

    public AsyncRequesterBootstrap setDefaultMaxPerRoute(final int v) {
        this.defaultMaxPerRoute = v;
        return this;
    }

    public AsyncRequesterBootstrap setMaxTotal(final int v) {
        this.maxTotal = v;
        return this;
    }

    public AsyncRequesterBootstrap setTimeToLive(final Timeout v) {
        this.timeToLive = v;
        return this;
    }

    public AsyncRequesterBootstrap setPoolReusePolicy(final PoolReusePolicy v) {
        this.poolReusePolicy = v;
        return this;
    }

    public AsyncRequesterBootstrap setPoolConcurrencyPolicy(final PoolConcurrencyPolicy v) {
        this.poolConcurrencyPolicy = v;
        return this;
    }

    public AsyncRequesterBootstrap setTlsStrategy(final TlsStrategy v) {
        this.tlsStrategy = v;
        return this;
    }

    public AsyncRequesterBootstrap setTlsHandshakeTimeout(final Timeout v) {
        this.handshakeTimeout = v;
        return this;
    }

    public AsyncRequesterBootstrap setIOSessionDecorator(final Decorator<IOSession> v) {
        this.ioSessionDecorator = v;
        return this;
    }

    public AsyncRequesterBootstrap setExceptionCallback(final Callback<Exception> v) {
        this.exceptionCallback = v;
        return this;
    }

    public AsyncRequesterBootstrap setIOSessionListener(final IOSessionListener v) {
        this.sessionListener = v;
        return this;
    }

    public AsyncRequesterBootstrap setStreamListener(final Http1StreamListener v) {
        this.streamListener = v;
        return this;
    }

    public AsyncRequesterBootstrap setConnPoolListener(final ConnPoolListener<HttpHost> v) {
        this.connPoolListener = v;
        return this;
    }

    /**
     * Returns both requester and pool.
     */
    public Result createWithPool() {
        final ManagedConnPool<HttpHost, IOSession> connPool;
        final PoolConcurrencyPolicy conc = poolConcurrencyPolicy != null ? poolConcurrencyPolicy : PoolConcurrencyPolicy.STRICT;
        final PoolReusePolicy reuse = poolReusePolicy != null ? poolReusePolicy : PoolReusePolicy.LIFO;
        final Timeout ttl = timeToLive != null ? timeToLive : Timeout.DISABLED;

        switch (conc) {
            case LAX:
                connPool = new LaxConnPool<>(
                        defaultMaxPerRoute > 0 ? defaultMaxPerRoute : 20,
                        ttl, reuse, new DefaultDisposalCallback<>(), connPoolListener);
                break;
            case STRICT:
            default:
                connPool = new StrictConnPool<>(
                        defaultMaxPerRoute > 0 ? defaultMaxPerRoute : 20,
                        maxTotal > 0 ? maxTotal : 50,
                        ttl, reuse, new DefaultDisposalCallback<>(), connPoolListener);
        }

        final HttpProcessor proc = httpProcessor != null ? httpProcessor : HttpProcessors.client();
        final Http1Config h1 = http1Config != null ? http1Config : Http1Config.DEFAULT;
        final CharCodingConfig coding = charCodingConfig != null ? charCodingConfig : CharCodingConfig.DEFAULT;

        final ClientHttp1StreamDuplexerFactory duplexerFactory =
                new ClientHttp1StreamDuplexerFactory(proc, h1, coding, connStrategy, null, null, streamListener);

        final TlsStrategy tls = tlsStrategy != null ? tlsStrategy : new BasicClientTlsStrategy();
        final IOEventHandlerFactory iohFactory = new ClientHttp1IOEventHandlerFactory(duplexerFactory, tls, handshakeTimeout);

        final HttpAsyncRequester requester = new HttpAsyncRequester(
                ioReactorConfig, iohFactory, ioSessionDecorator, exceptionCallback,
                sessionListener, connPool, tls, handshakeTimeout);

        return new Result(requester, connPool);
    }

    /**
     * Convenience: requester only.
     */
    public HttpAsyncRequester create() {
        return createWithPool().requester;
    }

    /**
     * Tuple of requester + pool.
     */
    public static final class Result {
        public final HttpAsyncRequester requester;
        public final ManagedConnPool<HttpHost, IOSession> connPool;

        public Result(final HttpAsyncRequester requester, final ManagedConnPool<HttpHost, IOSession> connPool) {
            this.requester = requester;
            this.connPool = connPool;
        }
    }
}

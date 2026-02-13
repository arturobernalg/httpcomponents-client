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
package org.apache.hc.client5.http.impl.async;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.IntConsumer;

import org.apache.hc.client5.http.AuthenticationStrategy;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.auth.AuthExchange;
import org.apache.hc.client5.http.auth.AuthSchemeFactory;
import org.apache.hc.client5.http.auth.AuthenticationException;
import org.apache.hc.client5.http.auth.ChallengeType;
import org.apache.hc.client5.http.auth.CredentialsProvider;
import org.apache.hc.client5.http.auth.MalformedChallengeException;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.AuthCacheKeeper;
import org.apache.hc.client5.http.impl.auth.AuthenticationHandler;
import org.apache.hc.client5.http.impl.auth.BasicAuthCache;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.concurrent.CallbackContribution;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.function.Callback;
import org.apache.hc.core5.function.Resolver;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.http.nio.AsyncClientExchangeHandler;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.apache.hc.core5.http.nio.RequestChannel;
import org.apache.hc.core5.http.nio.command.RequestExecutionCommand;
import org.apache.hc.core5.http.nio.command.ShutdownCommand;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpProcessor;
import org.apache.hc.core5.http2.nio.command.PingCommand;
import org.apache.hc.core5.http2.nio.support.BasicPingHandler;
import org.apache.hc.core5.http2.ssl.ApplicationProtocol;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.io.ModalCloseable;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.reactor.AbstractIOSessionPool;
import org.apache.hc.core5.reactor.Command;
import org.apache.hc.core5.reactor.ConnectionInitiator;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.ProtocolIOSession;
import org.apache.hc.core5.reactor.ssl.TransportSecurityLayer;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

class InternalH2ConnPool implements ModalCloseable {

    private static final int CONNECT_MAX_AUTH_ATTEMPTS = 3;

    private final SessionPool sessionPool;
    private volatile Resolver<HttpHost, ConnectionConfig> connectionConfigResolver;

    InternalH2ConnPool(
            final ConnectionInitiator connectionInitiator,
            final Resolver<HttpHost, InetSocketAddress> addressResolver,
            final TlsStrategy tlsStrategy) {
        this(connectionInitiator, addressResolver, tlsStrategy, null, null, RequestConfig.DEFAULT, null, null, null, true);
    }

    InternalH2ConnPool(
            final ConnectionInitiator connectionInitiator,
            final Resolver<HttpHost, InetSocketAddress> addressResolver,
            final TlsStrategy tlsStrategy,
            final HttpProcessor proxyHttpProcessor,
            final AuthenticationStrategy proxyAuthStrategy,
            final RequestConfig defaultRequestConfig,
            final CredentialsProvider credentialsProvider,
            final Lookup<AuthSchemeFactory> authSchemeRegistry,
            final SchemePortResolver schemePortResolver,
            final boolean authCachingDisabled) {

        final RequestConfig baseRequestConfig = defaultRequestConfig != null ? defaultRequestConfig : RequestConfig.DEFAULT;

        final boolean hasProxyConfig = proxyHttpProcessor != null || proxyAuthStrategy != null ||
                credentialsProvider != null || authSchemeRegistry != null ||
                schemePortResolver != null;

        final ProxySupport proxySupport = hasProxyConfig ? new ProxySupport(
                proxyHttpProcessor,
                proxyAuthStrategy,
                baseRequestConfig,
                credentialsProvider,
                authSchemeRegistry,
                schemePortResolver,
                authCachingDisabled) : null;

        this.sessionPool = new SessionPool(connectionInitiator, addressResolver, tlsStrategy, proxySupport);
    }

    @Override
    public void close(final CloseMode closeMode) {
        sessionPool.close(closeMode);
    }

    @Override
    public void close() {
        sessionPool.close();
    }

    private ConnectionConfig resolveConnectionConfig(final HttpHost httpHost) {
        final Resolver<HttpHost, ConnectionConfig> resolver = this.connectionConfigResolver;
        final ConnectionConfig connectionConfig = resolver != null ? resolver.resolve(httpHost) : null;
        return connectionConfig != null ? connectionConfig : ConnectionConfig.DEFAULT;
    }

    public Future<IOSession> getSession(
            final HttpRoute route,
            final Timeout connectTimeout,
            final FutureCallback<IOSession> callback) {
        final ConnectionConfig connectionConfig = resolveConnectionConfig(route.getTargetHost());
        final Timeout actualConnectTimeout = connectTimeout != null ? connectTimeout : connectionConfig.getConnectTimeout();

        return sessionPool.getSession(
                route,
                actualConnectTimeout,
                new CallbackContribution<IOSession>(callback) {

                    @Override
                    public void completed(final IOSession ioSession) {
                        final Timeout socketTimeout = connectionConfig.getSocketTimeout();
                        if (socketTimeout != null) {
                            ioSession.setSocketTimeout(socketTimeout);
                        }
                        callback.completed(ioSession);
                    }

                });
    }

    public void closeIdle(final TimeValue idleTime) {
        sessionPool.closeIdle(idleTime);
    }

    public void setConnectionConfigResolver(final Resolver<HttpHost, ConnectionConfig> connectionConfigResolver) {
        this.connectionConfigResolver = connectionConfigResolver;
    }

    public TimeValue getValidateAfterInactivity() {
        return sessionPool.validateAfterInactivity;
    }

    public void setValidateAfterInactivity(final TimeValue timeValue) {
        sessionPool.validateAfterInactivity = timeValue;
    }

    private static final class ProxySupport {
        final AuthenticationHandler authenticator;
        final AuthCacheKeeper authCacheKeeper;
        final AuthenticationStrategy proxyAuthStrategy;
        final HttpProcessor proxyHttpProcessor;
        final RequestConfig defaultRequestConfig;
        final CredentialsProvider credentialsProvider;
        final Lookup<AuthSchemeFactory> authSchemeRegistry;
        final SchemePortResolver schemePortResolver;

        ProxySupport(
                final HttpProcessor proxyHttpProcessor,
                final AuthenticationStrategy proxyAuthStrategy,
                final RequestConfig defaultRequestConfig,
                final CredentialsProvider credentialsProvider,
                final Lookup<AuthSchemeFactory> authSchemeRegistry,
                final SchemePortResolver schemePortResolver,
                final boolean authCachingDisabled) {
            this.proxyHttpProcessor = proxyHttpProcessor;
            this.proxyAuthStrategy = proxyAuthStrategy;
            this.defaultRequestConfig = defaultRequestConfig;
            this.credentialsProvider = credentialsProvider;
            this.authSchemeRegistry = authSchemeRegistry;
            this.schemePortResolver = schemePortResolver;
            this.authenticator = new AuthenticationHandler();
            this.authCacheKeeper = !authCachingDisabled && schemePortResolver != null
                    ? new AuthCacheKeeper(schemePortResolver)
                    : null;
        }
    }

    static final class ProxyConnectHandler implements AsyncClientExchangeHandler {

        private final HttpHost proxy;
        private final NamedEndpoint tunnelTarget;
        private final int tunnelPort;
        private final Timeout timeout;
        private final TlsStrategy tlsStrategy;
        private final HttpClientContext context;
        private final AuthExchange proxyAuthExchange;
        private final ProxySupport proxySupport;
        private final int maxAttempts;
        private final int attempt;
        private final ProtocolIOSession ioSession;
        private final FutureCallback<IOSession> resultCallback;
        private final IntConsumer retryStrategy;

        private volatile boolean challenged;
        private volatile boolean ok;
        private volatile boolean finalized;

        ProxyConnectHandler(
                final HttpHost proxy,
                final NamedEndpoint tunnelTarget,
                final int tunnelPort,
                final Timeout timeout,
                final TlsStrategy tlsStrategy,
                final HttpClientContext context,
                final AuthExchange proxyAuthExchange,
                final ProxySupport proxySupport,
                final int maxAttempts,
                final int attempt,
                final ProtocolIOSession ioSession,
                final IntConsumer retryStrategy,
                final FutureCallback<IOSession> resultCallback) {
            this.proxy = proxy;
            this.tunnelTarget = tunnelTarget;
            this.tunnelPort = tunnelPort;
            this.timeout = timeout;
            this.tlsStrategy = tlsStrategy;
            this.context = context;
            this.proxyAuthExchange = proxyAuthExchange;
            this.proxySupport = proxySupport;
            this.maxAttempts = maxAttempts;
            this.attempt = attempt;
            this.ioSession = ioSession;
            this.retryStrategy = retryStrategy;
            this.resultCallback = resultCallback;
        }

        @Override
        public void produceRequest(final RequestChannel requestChannel, final HttpContext httpContext) throws HttpException, IOException {
            final String authority = tunnelTarget.getHostName() + ":" + tunnelPort;
            final HttpRequest connect = new BasicHttpRequest(Method.CONNECT, new HttpHost(tunnelTarget.getHostName(), tunnelPort), authority);
            connect.setVersion(HttpVersion.HTTP_1_1);
            connect.setHeader(HttpHeaders.HOST, authority);

            if (proxySupport != null) {
                if (proxySupport.proxyHttpProcessor != null) {
                    proxySupport.proxyHttpProcessor.process(connect, null, context);
                }
                if (proxySupport.authenticator != null && proxySupport.proxyAuthStrategy != null) {
                    proxySupport.authenticator.addAuthResponse(proxy, ChallengeType.PROXY, connect, proxyAuthExchange, context);
                }
            }
            requestChannel.sendRequest(connect, null, context);
        }

        @Override
        public void consumeResponse(final HttpResponse response, final EntityDetails entityDetails, final HttpContext httpContext) throws HttpException, IOException {
            context.setResponse(response);
            if (proxySupport != null && proxySupport.proxyHttpProcessor != null) {
                proxySupport.proxyHttpProcessor.process(response, entityDetails, context);
            }

            final int status = response.getCode();
            if (status < HttpStatus.SC_SUCCESS) {
                throw new HttpException("Unexpected response to CONNECT request: " + new StatusLine(response));
            }

            if (proxySupport != null && proxySupport.authenticator != null && proxySupport.proxyAuthStrategy != null) {
                try {
                    challenged = SessionPool.needAuthentication(
                            proxyAuthExchange, proxy, response, context,
                            proxySupport.authenticator, proxySupport.authCacheKeeper, proxySupport.proxyAuthStrategy);
                } catch (final AuthenticationException | MalformedChallengeException ex) {
                    throw new HttpException(ex.getMessage(), ex);
                }
            }

            if (!challenged) {
                if (status == HttpStatus.SC_OK) {
                    ok = true;
                    if (entityDetails == null) {
                        finalizeExchange();
                    }
                } else {
                    throw new HttpException("Tunnel refused: " + new StatusLine(response));
                }
            } else if (entityDetails == null) {
                finalizeExchange();
            }
        }

        private void finalizeExchange() {
            if (finalized) {
                return;
            }
            finalized = true;

            if (challenged) {
                if (attempt + 1 >= maxAttempts) {
                    failTerminal(new HttpException("Proxy authentication failed: CONNECT retry limit exceeded"));
                    return;
                }
                retryStrategy.accept(attempt + 1);
                return;
            }

            if (!ok) {
                return;
            }

            // Upgrade Logic
            if (tlsStrategy != null) {
                if (!(ioSession instanceof TransportSecurityLayer)) {
                    failTerminal(new IllegalStateException("TLS upgrade unsupported by the transport session"));
                    return;
                }
                tlsStrategy.upgrade(
                        (TransportSecurityLayer) ioSession,
                        tunnelTarget,
                        null,
                        timeout,
                        new FutureCallback<TransportSecurityLayer>() {
                            @Override
                            public void completed(final TransportSecurityLayer transportSecurityLayer) {
                                switchProtocol();
                            }

                            @Override
                            public void failed(final Exception ex) {
                                failTerminal(ex);
                            }

                            @Override
                            public void cancelled() {
                                resultCallback.cancelled();
                            }
                        });
            } else {
                switchProtocol();
            }
        }

        private void switchProtocol() {
            ioSession.switchProtocol(ApplicationProtocol.HTTP_2.id, new FutureCallback<ProtocolIOSession>() {
                @Override
                public void completed(final ProtocolIOSession protocolSession) {
                    resultCallback.completed(protocolSession);
                }

                @Override
                public void failed(final Exception ex) {
                    failTerminal(ex);
                }

                @Override
                public void cancelled() {
                    resultCallback.cancelled();
                }
            });
        }

        private void failTerminal(final Exception ex) {
            ioSession.close(CloseMode.IMMEDIATE);
            resultCallback.failed(ex);
        }

        @Override
        public void releaseResources() {
        }

        @Override
        public void failed(final Exception cause) {
            failTerminal(cause);
        }

        @Override
        public void cancel() {
            resultCallback.cancelled();
        }

        @Override
        public void produce(final DataStreamChannel dataStreamChannel) throws IOException {
        }

        @Override
        public int available() {
            return 0;
        }

        @Override
        public void consumeInformation(final HttpResponse response, final HttpContext httpContext) throws HttpException, IOException {
        }

        @Override
        public void updateCapacity(final CapacityChannel capacityChannel) throws IOException {
            capacityChannel.update(Integer.MAX_VALUE);
        }

        @Override
        public void consume(final ByteBuffer src) throws IOException {
            if (src != null) {
                src.position(src.limit());
            }
        }

        @Override
        public void streamEnd(final List<? extends Header> trailers) throws HttpException, IOException {
            finalizeExchange();
        }
    }
    static final class SessionPool extends AbstractIOSessionPool<HttpRoute> {

        private final ConnectionInitiator connectionInitiator;
        private final Resolver<HttpHost, InetSocketAddress> addressResolver;
        private final TlsStrategy tlsStrategy;
        private final ProxySupport proxySupport;

        private volatile TimeValue validateAfterInactivity = TimeValue.NEG_ONE_MILLISECOND;

        SessionPool(
                final ConnectionInitiator connectionInitiator,
                final Resolver<HttpHost, InetSocketAddress> addressResolver,
                final TlsStrategy tlsStrategy,
                final ProxySupport proxySupport) {
            this.connectionInitiator = connectionInitiator;
            this.addressResolver = addressResolver;
            this.tlsStrategy = tlsStrategy;
            this.proxySupport = proxySupport;
        }

        @Override
        protected Future<IOSession> connectSession(
                final HttpRoute route,
                final Timeout connectTimeout,
                final FutureCallback<IOSession> callback) {

            final HttpHost target = route.getTargetHost();
            final HttpHost proxy = route.getProxyHost();
            final HttpHost firstHop = proxy != null ? proxy : target;

            final InetSocketAddress localAddress = route.getLocalSocketAddress();
            final InetSocketAddress remoteAddress = addressResolver.resolve(firstHop);

            return connectionInitiator.connect(
                    firstHop,
                    remoteAddress,
                    localAddress,
                    connectTimeout,
                    route,
                    new CallbackContribution<IOSession>(callback) {
                        @Override
                        public void completed(final IOSession ioSession) {
                            if (route.isTunnelled()) {
                                handleTunneling(route, firstHop, ioSession, connectTimeout, callback);
                            } else {
                                upgradeTls(route, ioSession, connectTimeout, callback);
                            }
                        }
                    });
        }

        private void handleTunneling(
                final HttpRoute route,
                final HttpHost firstHop,
                final IOSession ioSession,
                final Timeout connectTimeout,
                final FutureCallback<IOSession> callback) {

            if (tlsStrategy != null && URIScheme.HTTPS.same(firstHop.getSchemeName()) && ioSession instanceof TransportSecurityLayer) {
                // If the PROXY itself is HTTPS, we must upgrade TLS *before* establishing the tunnel
                doTlsUpgrade(firstHop, ioSession, connectTimeout, new FutureCallback<IOSession>() {
                    @Override
                    public void completed(final IOSession result) {
                        createTunnel(route, result, connectTimeout, callback);
                    }

                    @Override
                    public void failed(final Exception ex) {
                        callback.failed(ex);
                    }

                    @Override
                    public void cancelled() {
                        callback.cancelled();
                    }
                });
            } else {
                createTunnel(route, ioSession, connectTimeout, callback);
            }
        }

        private void createTunnel(
                final HttpRoute route,
                final IOSession ioSession,
                final Timeout timeout,
                final FutureCallback<IOSession> callback) {

            final HttpHost proxy = route.getProxyHost();
            if (proxy == null) {
                callback.failed(new IllegalStateException("Tunnel route does not have a proxy"));
                return;
            }

            final NamedEndpoint tunnelTarget = route.getTargetName() != null ? route.getTargetName() : route.getTargetHost();
            if (!(ioSession instanceof ProtocolIOSession)) {
                callback.failed(new IllegalStateException("Protocol switch to HTTP/2 is not supported"));
                return;
            }

            final ProtocolIOSession protocolIOSession = (ProtocolIOSession) ioSession;
            final Timeout previousTimeout = protocolIOSession.getSocketTimeout();
            if (timeout != null) {
                protocolIOSession.setSocketTimeout(timeout);
            }

            final FutureCallback<IOSession> restoringCallback = new FutureCallback<IOSession>() {
                @Override
                public void completed(final IOSession result) {
                    protocolIOSession.setSocketTimeout(previousTimeout);
                    callback.completed(result);
                }

                @Override
                public void failed(final Exception ex) {
                    protocolIOSession.setSocketTimeout(previousTimeout);
                    callback.failed(ex);
                }

                @Override
                public void cancelled() {
                    protocolIOSession.setSocketTimeout(previousTimeout);
                    callback.cancelled();
                }
            };

            final HttpClientContext connectContext = HttpClientContext.create();
            if (proxySupport != null) {
                connectContext.setRequestConfig(RequestConfig.copy(proxySupport.defaultRequestConfig).setProxy(proxy).build());
                connectContext.setCredentialsProvider(proxySupport.credentialsProvider);
                connectContext.setAuthSchemeRegistry(proxySupport.authSchemeRegistry);
                if (proxySupport.authCacheKeeper != null) {
                    connectContext.setAuthCache(new BasicAuthCache());
                }
            } else {
                connectContext.setRequestConfig(RequestConfig.copy(RequestConfig.DEFAULT).setProxy(proxy).build());
            }

            final AuthExchange proxyAuthExchange = connectContext.getAuthExchange(proxy);
            if (proxySupport != null && proxySupport.authCacheKeeper != null) {
                proxySupport.authCacheKeeper.loadPreemptively(proxy, null, proxyAuthExchange, connectContext);
            }

            final int tunnelPort;
            try {
                tunnelPort = resolveTunnelPort(route, tunnelTarget, proxySupport != null ? proxySupport.schemePortResolver : null);
            } catch (final HttpException ex) {
                restoringCallback.failed(ex);
                return;
            }

            executeProxyConnect(
                    0,
                    CONNECT_MAX_AUTH_ATTEMPTS,
                    protocolIOSession,
                    proxy,
                    tunnelTarget,
                    tunnelPort,
                    timeout,
                    route.isSecure() ? tlsStrategy : null,
                    connectContext,
                    proxyAuthExchange,
                    proxySupport,
                    restoringCallback);
        }

        private void executeProxyConnect(
                final int attempt,
                final int maxAttempts,
                final ProtocolIOSession ioSession,
                final HttpHost proxy,
                final NamedEndpoint tunnelTarget,
                final int tunnelPort,
                final Timeout timeout,
                final TlsStrategy tlsStrategy,
                final HttpClientContext context,
                final AuthExchange proxyAuthExchange,
                final ProxySupport proxySupport,
                final FutureCallback<IOSession> callback) {

            ioSession.enqueue(new RequestExecutionCommand(
                    new ProxyConnectHandler(
                            proxy,
                            tunnelTarget,
                            tunnelPort,
                            timeout,
                            tlsStrategy,
                            context,
                            proxyAuthExchange,
                            proxySupport,
                            maxAttempts,
                            attempt,
                            ioSession,
                            nextAttempt -> executeProxyConnect(
                                    nextAttempt,
                                    maxAttempts,
                                    ioSession,
                                    proxy,
                                    tunnelTarget,
                                    tunnelPort,
                                    timeout,
                                    tlsStrategy,
                                    context,
                                    proxyAuthExchange,
                                    proxySupport,
                                    callback),
                            callback
                    ),
                    context
            ), Command.Priority.NORMAL);
        }

        private static int resolveTunnelPort(
                final HttpRoute route,
                final NamedEndpoint tunnelTarget,
                final SchemePortResolver schemePortResolver) throws HttpException {
            final int explicit = tunnelTarget.getPort();
            if (explicit > 0) return explicit;
            final HttpHost targetHost = route.getTargetHost();
            final int targetPort = targetHost.getPort();
            if (targetPort > 0) return targetPort;
            if (schemePortResolver != null) {
                final int resolved = schemePortResolver.resolve(targetHost);
                if (resolved > 0) return resolved;
            }
            if (route.isSecure() || URIScheme.HTTPS.same(targetHost.getSchemeName())) return 443;
            if (URIScheme.HTTP.same(targetHost.getSchemeName())) return 80;
            throw new HttpException("Tunnel target port is undefined: " + tunnelTarget.getHostName());
        }

        static boolean needAuthentication(
                final AuthExchange proxyAuthExchange,
                final HttpHost proxy,
                final HttpResponse response,
                final HttpClientContext context,
                final AuthenticationHandler authenticator,
                final AuthCacheKeeper authCacheKeeper,
                final AuthenticationStrategy proxyAuthStrategy) throws AuthenticationException, MalformedChallengeException {

            final RequestConfig config = context.getRequestConfigOrDefault();
            if (config.isAuthenticationEnabled()) {
                final boolean proxyAuthRequested = authenticator.isChallenged(proxy, ChallengeType.PROXY, response, proxyAuthExchange, context);
                final boolean proxyMutualAuthRequired = authenticator.isChallengeExpected(proxyAuthExchange);

                if (authCacheKeeper != null) {
                    if (proxyAuthRequested) {
                        authCacheKeeper.updateOnChallenge(proxy, null, proxyAuthExchange, context);
                    } else {
                        authCacheKeeper.updateOnNoChallenge(proxy, null, proxyAuthExchange, context);
                    }
                }

                if (proxyAuthRequested || proxyMutualAuthRequired) {
                    final boolean updated = authenticator.handleResponse(proxy, ChallengeType.PROXY, response, proxyAuthStrategy, proxyAuthExchange, context);
                    if (authCacheKeeper != null) {
                        authCacheKeeper.updateOnResponse(proxy, null, proxyAuthExchange, context);
                    }
                    return updated;
                }
            }
            return false;
        }

        private void upgradeTls(
                final HttpRoute route,
                final IOSession ioSession,
                final Timeout timeout,
                final FutureCallback<IOSession> callback) {
            final HttpHost target = route.getTargetHost();
            if (tlsStrategy != null && URIScheme.HTTPS.same(target.getSchemeName()) && ioSession instanceof TransportSecurityLayer) {
                final NamedEndpoint tlsEndpoint = route.getTargetName() != null ? route.getTargetName() : target;
                doTlsUpgrade(tlsEndpoint, ioSession, timeout, callback);
            } else {
                callback.completed(ioSession);
            }
        }

        private void doTlsUpgrade(
                final NamedEndpoint tlsEndpoint,
                final IOSession ioSession,
                final Timeout timeout,
                final FutureCallback<IOSession> callback) {

            if (!(ioSession instanceof TransportSecurityLayer)) {
                callback.failed(new IllegalStateException("TLS upgrade unsupported by the transport session"));
                return;
            }

            tlsStrategy.upgrade(
                    (TransportSecurityLayer) ioSession,
                    tlsEndpoint,
                    null,
                    timeout,
                    new FutureCallback<TransportSecurityLayer>() {
                        @Override
                        public void completed(final TransportSecurityLayer transportSecurityLayer) {
                            callback.completed(ioSession);
                        }

                        @Override
                        public void failed(final Exception ex) {
                            callback.failed(ex);
                        }

                        @Override
                        public void cancelled() {
                            callback.cancelled();
                        }
                    });
            ioSession.setSocketTimeout(timeout);
        }

        @Override
        protected void validateSession(final IOSession ioSession, final Callback<Boolean> callback) {
            if (!ioSession.isOpen()) {
                callback.execute(false);
                return;
            }
            final TimeValue timeValue = validateAfterInactivity;
            if (TimeValue.isNonNegative(timeValue)) {
                final long lastAccessTime = Math.min(ioSession.getLastReadTime(), ioSession.getLastWriteTime());
                final long deadline = lastAccessTime + timeValue.toMilliseconds();
                if (deadline <= System.currentTimeMillis()) {
                    final Timeout socketTimeout = ioSession.getSocketTimeout();
                    ioSession.enqueue(new PingCommand(new BasicPingHandler(result -> {
                        ioSession.setSocketTimeout(socketTimeout);
                        callback.execute(result);
                    })), Command.Priority.NORMAL);
                    return;
                }
            }
            callback.execute(true);
        }

        @Override
        protected void closeSession(final IOSession ioSession,
                                    final CloseMode closeMode) {
            if (closeMode == CloseMode.GRACEFUL) {
                ioSession.enqueue(ShutdownCommand.GRACEFUL, Command.Priority.NORMAL);
            } else {
                ioSession.close(closeMode);
            }
        }
    }

}

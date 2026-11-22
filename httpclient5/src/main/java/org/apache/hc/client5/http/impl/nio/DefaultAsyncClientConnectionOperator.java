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

package org.apache.hc.client5.http.impl.nio;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.AddressOrderingStrategy;
import org.apache.hc.client5.http.AsyncDnsResolver;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.UnsupportedSchemeException;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.ConnPoolSupport;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.nio.AsyncClientConnectionOperator;
import org.apache.hc.client5.http.nio.ManagedAsyncClientConnection;
import org.apache.hc.client5.http.routing.RoutingSupport;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.concurrent.CallbackContribution;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.concurrent.ComplexCancellable;
import org.apache.hc.core5.concurrent.ComplexFuture;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.reactor.ConnectionInitiator;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.ssl.TransportSecurityLayer;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Internal
public class DefaultAsyncClientConnectionOperator implements AsyncClientConnectionOperator {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAsyncClientConnectionOperator.class);

    private final SchemePortResolver schemePortResolver;
    private final MultihomeIOSessionRequester sessionRequester;
    private final Lookup<TlsStrategy> tlsStrategyLookup;

    /**
     * Constructs a new {@code DefaultAsyncClientConnectionOperator}.
     *
     * <p><strong>Note:</strong> this class is marked {@code @Internal}; rely on it
     * only if you are prepared for incompatible changes in a future major
     * release.  Typical client code should use the high-level builders in
     * {@code HttpAsyncClients} instead.</p>
     */
    protected DefaultAsyncClientConnectionOperator(
            final Lookup<TlsStrategy> tlsStrategyLookup,
            final SchemePortResolver schemePortResolver,
            final DnsResolver dnsResolver) {
        this(tlsStrategyLookup, schemePortResolver, dnsResolver, false, null);
    }

    /**
     * @since 5.7
     */
    protected DefaultAsyncClientConnectionOperator(
            final Lookup<TlsStrategy> tlsStrategyLookup,
            final SchemePortResolver schemePortResolver,
            final DnsResolver dnsResolver,
            final boolean happyEyeballsEnabled,
            final TimeValue connectionAttemptDelay) {
        this(tlsStrategyLookup, schemePortResolver, dnsResolver, happyEyeballsEnabled, connectionAttemptDelay, null);
    }

    /**
     * @since 5.7
     */
    protected DefaultAsyncClientConnectionOperator(
            final Lookup<TlsStrategy> tlsStrategyLookup,
            final SchemePortResolver schemePortResolver,
            final DnsResolver dnsResolver,
            final boolean happyEyeballsEnabled,
            final TimeValue connectionAttemptDelay,
            final AsyncDnsResolver asyncDnsResolver) {
        this(tlsStrategyLookup, schemePortResolver, dnsResolver, happyEyeballsEnabled, connectionAttemptDelay, asyncDnsResolver, null);
    }

    /**
     * Same as {@link #DefaultAsyncClientConnectionOperator(Lookup, SchemePortResolver,
     * DnsResolver, boolean, TimeValue, AsyncDnsResolver)}, but additionally lets the caller
     * control how addresses from independently-resolved A/AAAA batches are merged into a
     * Happy Eyeballs race once both {@code happyEyeballsEnabled} and {@code asyncDnsResolver}
     * apply; see {@link AddressOrderingStrategy}.
     *
     * @since 5.7
     */
    protected DefaultAsyncClientConnectionOperator(
            final Lookup<TlsStrategy> tlsStrategyLookup,
            final SchemePortResolver schemePortResolver,
            final DnsResolver dnsResolver,
            final boolean happyEyeballsEnabled,
            final TimeValue connectionAttemptDelay,
            final AsyncDnsResolver asyncDnsResolver,
            final AddressOrderingStrategy addressOrderingStrategy) {
        this.tlsStrategyLookup = Args.notNull(tlsStrategyLookup, "TLS strategy lookup");
        this.schemePortResolver = schemePortResolver != null ? schemePortResolver : DefaultSchemePortResolver.INSTANCE;
        this.sessionRequester = new MultihomeIOSessionRequester(
                dnsResolver, happyEyeballsEnabled, connectionAttemptDelay, asyncDnsResolver, addressOrderingStrategy);
    }

    @Override
    public Future<ManagedAsyncClientConnection> connect(
            final ConnectionInitiator connectionInitiator,
            final HttpHost host,
            final SocketAddress localAddress,
            final Timeout connectTimeout,
            final Object attachment,
            final FutureCallback<ManagedAsyncClientConnection> callback) {
        return connect(connectionInitiator, host, null, localAddress, connectTimeout,
            attachment, null, callback);
    }

    @Override
    public Future<ManagedAsyncClientConnection> connect(
        final ConnectionInitiator connectionInitiator,
        final HttpHost endpointHost,
        final NamedEndpoint endpointName,
        final SocketAddress localAddress,
        final Timeout connectTimeout,
        final Object attachment,
        final HttpContext context,
        final FutureCallback<ManagedAsyncClientConnection> callback) {
        return connect(connectionInitiator, endpointHost, null, endpointName, localAddress, connectTimeout, attachment,
            context, callback);
    }

    @Override
    public Future<ManagedAsyncClientConnection> connect(
            final ConnectionInitiator connectionInitiator,
            final HttpHost endpointHost,
            final Path unixDomainSocket,
            final NamedEndpoint endpointName,
            final SocketAddress localAddress,
            final Timeout connectTimeout,
            final Object attachment,
            final HttpContext context,
            final FutureCallback<ManagedAsyncClientConnection> callback) {
        Args.notNull(connectionInitiator, "Connection initiator");
        Args.notNull(endpointHost, "Host");
        final ComplexFuture<ManagedAsyncClientConnection> future = new ComplexFuture<>(callback);
        final HttpHost remoteEndpoint = RoutingSupport.normalize(endpointHost, schemePortResolver);
        final SocketAddress remoteAddress;
        if (unixDomainSocket == null) {
            final InetAddress remoteInetAddress = endpointHost.getAddress();
            remoteAddress = remoteInetAddress != null ?
                new InetSocketAddress(remoteInetAddress, remoteEndpoint.getPort()) : null;
        } else {
            remoteAddress = createUnixSocketAddress(unixDomainSocket);
        }
        final TlsConfig tlsConfig = attachment instanceof TlsConfig ? (TlsConfig) attachment : TlsConfig.DEFAULT;

        onBeforeSocketConnect(context, endpointHost);
        if (LOG.isDebugEnabled()) {
            LOG.debug("{} connecting {}->{} ({})", endpointHost, localAddress, remoteAddress, connectTimeout);
        }

        // Holds the connection produced by whichever attempt the session requester ultimately
        // accepts: with Happy Eyeballs racing or multiple resolved addresses, the validator
        // below may run more than once, against a different session each time, before one is
        // accepted.
        final AtomicReference<DefaultManagedAsyncClientConnection> acceptedConnection = new AtomicReference<>();

        // The session request may complete before connect returns, and its callback can register the
        // TLS handshake with the future. Install a holder first so the session request cannot replace it.
        final ComplexCancellable sessionCancellable = new ComplexCancellable();
        future.setDependency(sessionCancellable);
        // sessionFuture does not exist yet at the point the validator below is built, since it is
        // itself an argument of the call that creates it; cancelSessionFuture is the one stable
        // reference the validator can capture, resolved through this holder once connect() returns.
        final AtomicReference<Future<IOSession>> sessionFutureRef = new AtomicReference<>();
        final Cancellable cancelSessionFuture = () -> {
            final Future<IOSession> sf = sessionFutureRef.get();
            return sf != null && sf.cancel(true);
        };
        // Set before connect() is called, not only after: a mocked or otherwise synchronous
        // ConnectionInitiator can invoke the validator below before connect() returns, which
        // would already have moved sessionCancellable's dependency on to "close this
        // connection" by the time connect() returns. Only the validator itself is ever
        // allowed to move the dependency again after this, so whichever runs first always
        // wins and nothing here clobbers it back.
        sessionCancellable.setDependency(cancelSessionFuture);
        final Future<IOSession> sessionFuture = sessionRequester.connect(
                connectionInitiator,
                remoteEndpoint,
                remoteAddress,
                localAddress,
                connectTimeout,
                tlsConfig.getHttpVersionPolicy(),
                (session, validated) -> {
                    // Validates a connected session before the requester accepts it as final:
                    // on a TLS failure here, the requester tries the next address, if one
                    // remains, instead of this method surfacing a connected but unusable
                    // session. See MultihomeIOSessionRequester.SessionValidator.
                    final DefaultManagedAsyncClientConnection connection = new DefaultManagedAsyncClientConnection(session);
                    onAfterSocketConnect(context, endpointHost);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("{} {} connected {}->{}", ConnPoolSupport.getId(connection), endpointHost,
                                connection.getLocalAddress(), connection.getRemoteAddress());
                    }
                    final TlsStrategy tlsStrategy = tlsStrategyLookup != null ? tlsStrategyLookup.lookup(endpointHost.getSchemeName()) : null;
                    if (tlsStrategy != null) {
                        // Guards against a TlsStrategy that, in violation of its contract,
                        // reports an outcome through the callback below and then also throws
                        // out of #upgrade (the catch block below): only the first of the two
                        // is ever reported, and the second is a safe no-op rather than a
                        // double-advance of the session requester's fallback.
                        final AtomicBoolean settled = new AtomicBoolean();
                        try {
                            final Timeout socketTimeout = connection.getSocketTimeout();
                            final Timeout handshakeTimeout = tlsConfig.getHandshakeTimeout() != null ? tlsConfig.getHandshakeTimeout() : connectTimeout;
                            final NamedEndpoint tlsName = endpointName != null ? endpointName : endpointHost;
                            // The session request for this specific attempt is already done, so
                            // cancelling it would not stop the handshake: track closing this
                            // connection instead, for as long as this attempt is the one in
                            // flight. On rejection below, tracking reverts to sessionFuture,
                            // which the requester may still be using to try another address.
                            sessionCancellable.setDependency(() -> {
                                connection.close(CloseMode.IMMEDIATE);
                                return true;
                            });
                            onBeforeTlsHandshake(context, endpointHost);
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("{} {} upgrading to TLS", ConnPoolSupport.getId(connection), tlsName);
                            }
                            tlsStrategy.upgrade(
                                    connection,
                                    tlsName,
                                    attachment,
                                    handshakeTimeout,
                                    new FutureCallback<TransportSecurityLayer>() {

                                        @Override
                                        public void completed(final TransportSecurityLayer transportSecurityLayer) {
                                            if (!settled.compareAndSet(false, true)) {
                                                return;
                                            }
                                            // Report the result before invoking the hook below, so a hook
                                            // that throws can never orphan a result that has already been
                                            // accepted.
                                            connection.setSocketTimeout(socketTimeout);
                                            acceptedConnection.set(connection);
                                            validated.completed(session);
                                            onAfterTlsHandshake(context, endpointHost);
                                            if (LOG.isDebugEnabled()) {
                                                LOG.debug("{} {} upgraded to TLS", ConnPoolSupport.getId(connection), tlsName);
                                            }
                                        }

                                        @Override
                                        public void failed(final Exception ex) {
                                            if (!settled.compareAndSet(false, true)) {
                                                return;
                                            }
                                            sessionCancellable.setDependency(cancelSessionFuture);
                                            validated.failed(ex);
                                        }

                                        @Override
                                        public void cancelled() {
                                            if (!settled.compareAndSet(false, true)) {
                                                return;
                                            }
                                            // Unlike #failed, a cancellation is always terminal:
                                            // the session requester's own future is already
                                            // being cancelled by this same call chain (through
                                            // #validated, below), which also cancels the outer
                                            // future that depends on it, so there is no later
                                            // attempt for sessionCancellable to be redirected to.
                                            validated.cancelled();
                                        }

                                    });
                        } catch (final Exception ex) {
                            if (!settled.compareAndSet(false, true)) {
                                return;
                            }
                            sessionCancellable.setDependency(cancelSessionFuture);
                            validated.failed(ex);
                        }
                    } else {
                        acceptedConnection.set(connection);
                        validated.completed(session);
                    }
                },
                new FutureCallback<IOSession>() {

                    @Override
                    public void completed(final IOSession session) {
                        // The validator above has already built and TLS-upgraded, if
                        // configured, the connection for whichever attempt was accepted.
                        future.completed(acceptedConnection.get());
                    }

                    @Override
                    public void failed(final Exception ex) {
                        future.failed(ex);
                    }

                    @Override
                    public void cancelled() {
                        future.cancel();
                    }

                });
        // Only resolves what cancelSessionFuture cancels; does not itself move
        // sessionCancellable's dependency, which the validator may already have moved on.
        sessionFutureRef.set(sessionFuture);
        return future;
    }

        // The IOReactor does not support AFUNIXSocketChannel from JUnixSocket, so if a Unix domain socket was configured,
    // we must use JEP 380 sockets and addresses.
    private static SocketAddress createUnixSocketAddress(final Path socketPath) {
        try {
            final Class<?> addressClass = Class.forName("java.net.UnixDomainSocketAddress");
            final Method ofMethod = addressClass.getMethod("of", Path.class);
            return (SocketAddress) ofMethod.invoke(null, socketPath);
        } catch (final ReflectiveOperationException ex) {
            throw new UnsupportedOperationException("Async Unix domain socket support requires Java 16 or later", ex);
        }
    }

    @Override
    public void upgrade(
            final ManagedAsyncClientConnection connection,
            final HttpHost host,
            final Object attachment) {
        upgrade(connection, host, null, attachment, null, null);
    }

    @Override
    public void upgrade(
            final ManagedAsyncClientConnection connection,
            final HttpHost endpointHost,
            final NamedEndpoint endpointName,
            final Object attachment,
            final HttpContext context,
            final FutureCallback<ManagedAsyncClientConnection> callback) {
        final String newProtocol = URIScheme.HTTP.same(endpointHost.getSchemeName()) ? URIScheme.HTTPS.id : endpointHost.getSchemeName();
        final TlsStrategy tlsStrategy = tlsStrategyLookup != null ? tlsStrategyLookup.lookup(newProtocol) : null;
        if (tlsStrategy != null) {
            final NamedEndpoint tlsName = endpointName != null ? endpointName : endpointHost;
            if (LOG.isDebugEnabled()) {
                LOG.debug("{} {} upgrading to TLS", ConnPoolSupport.getId(connection), tlsName);
            }
            tlsStrategy.upgrade(
                    connection,
                    tlsName,
                    attachment,
                    null,
                    new CallbackContribution<TransportSecurityLayer>(callback) {

                        @Override
                        public void completed(final TransportSecurityLayer transportSecurityLayer) {
                            if (callback != null) {
                                callback.completed(connection);
                            }
                        }

                    });
        } else {
            callback.failed(new UnsupportedSchemeException(newProtocol + " protocol is not supported"));
        }
    }

    protected void onBeforeSocketConnect(final HttpContext httpContext, final HttpHost endpointHost) {
    }

    protected void onAfterSocketConnect(final HttpContext httpContext, final HttpHost endpointHost) {
    }

    protected void onBeforeTlsHandshake(final HttpContext httpContext, final HttpHost endpointHost) {
    }

    protected void onAfterTlsHandshake(final HttpContext httpContext, final HttpHost endpointHost) {
    }

}

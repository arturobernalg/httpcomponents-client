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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hc.client5.http.async.AsyncExecCallback;
import org.apache.hc.client5.http.async.AsyncExecChain;
import org.apache.hc.client5.http.async.AsyncExecChainHandler;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.nio.AsyncDataConsumer;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.util.Timeout;

/**
 * Per-message / per-HTTP/2-stream timeout.
 *
 * <p>Uses {@link RequestConfig#getH2StreamTimeout()} as a hard deadline
 * for the whole exchange (request + response) without changing the
 * underlying socket timeout.</p>
 *
 * <p>On expiry, the handler fails the exchange with a {@link TimeoutException}
 * and cancels the I/O via {@link AsyncExecChain.Scope#cancellableDependency}.
 * For HTTP/2 this is expected to translate to a stream reset while keeping
 * the connection reusable.</p>
 *
 * @since 5.6
 */
@Contract(threading = ThreadingBehavior.STATELESS)
@Internal
public final class H2StreamTimeoutAsyncExec implements AsyncExecChainHandler {

    @Override
    public void execute(
            final HttpRequest request,
            final AsyncEntityProducer entityProducer,
            final AsyncExecChain.Scope scope,
            final AsyncExecChain chain,
            final AsyncExecCallback callback) throws HttpException, IOException {

        if (scope == null || scope.scheduler == null) {
            chain.proceed(request, entityProducer, scope, callback);
            return;
        }

        final HttpClientContext clientContext = scope.clientContext != null
                ? scope.clientContext
                : HttpClientContext.create();

        final RequestConfig requestConfig = clientContext.getRequestConfigOrDefault();
        final Timeout streamTimeout = requestConfig.getH2StreamTimeout();
        if (streamTimeout == null || !streamTimeout.isEnabled()) {
            chain.proceed(request, entityProducer, scope, callback);
            return;
        }

        final AtomicBoolean finished = new AtomicBoolean(false);

        // Wrap downstream callback to ensure we only complete/fail once.
        final AsyncExecCallback guardedCallback = new AsyncExecCallback() {

            @Override
            public AsyncDataConsumer handleResponse(
                    final HttpResponse response,
                    final EntityDetails entityDetails) throws HttpException, IOException {
                return callback.handleResponse(response, entityDetails);
            }

            @Override
            public void handleInformationResponse(final HttpResponse response)
                    throws HttpException, IOException {
                callback.handleInformationResponse(response);
            }

            @Override
            public void completed() {
                if (finished.compareAndSet(false, true)) {
                    callback.completed();
                }
            }

            @Override
            public void failed(final Exception cause) {
                if (finished.compareAndSet(false, true)) {
                    callback.failed(cause);
                }
            }
        };

        // Schedule a "timeout execution" using the scheduler.
        // We do NOT store any Cancellable here; scheduler returns void.
        scope.scheduler.scheduleExecution(
                request,
                entityProducer,
                scope,
                (timeoutRequest, timeoutEntityProducer, timeoutScope, timeoutCallback) -> {

                    if (finished.compareAndSet(false, true)) {
                        timeoutScope.cancellableDependency.cancel();
                        timeoutCallback.failed(new TimeoutException("HTTP/2 stream timeout " + streamTimeout));
                    } else {
                        // Exchange already finished; nothing to do.
                        timeoutCallback.completed();
                    }
                },
                new AsyncExecCallback() {

                    @Override
                    public AsyncDataConsumer handleResponse(
                            final HttpResponse response,
                            final EntityDetails entityDetails) {
                        // Not used for the timeout path.
                        return null;
                    }

                    @Override
                    public void handleInformationResponse(final HttpResponse response) {
                        // Not used for the timeout path.
                    }

                    @Override
                    public void completed() {
                        // No-op; we only care about failed(...) from the timeout chain.
                    }

                    @Override
                    public void failed(final Exception cause) {
                        // Timeout chain decided to fail; propagate if we won the race.
                        if (finished.compareAndSet(false, true)) {
                            callback.failed(cause);
                        }
                    }
                },
                streamTimeout);

        // Proceed with normal execution using the guarded callback.
        chain.proceed(request, entityProducer, scope, guardedCallback);
    }

}

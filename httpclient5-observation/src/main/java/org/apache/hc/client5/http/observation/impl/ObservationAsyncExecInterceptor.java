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

package org.apache.hc.client5.http.observation.impl;

import java.io.IOException;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.async.AsyncExecCallback;
import org.apache.hc.client5.http.async.AsyncExecChain;
import org.apache.hc.client5.http.async.AsyncExecChainHandler;
import org.apache.hc.client5.http.observation.ObservingOptions;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.nio.AsyncDataConsumer;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;

/**
 * Asynchronous execution chain handler for observing HTTP client requests
 * using Micrometer Observation API.
 *
 * @since 5.6
 */
public final class ObservationAsyncExecInterceptor implements AsyncExecChainHandler {

    private final ObservationRegistry registry;
    private final ObservingOptions opts;

    public ObservationAsyncExecInterceptor(final ObservationRegistry registry, final ObservingOptions opts) {
        this.registry = registry;
        this.opts = opts;
    }

    @Override
    public void execute(final HttpRequest request, final AsyncEntityProducer entityProducer, final AsyncExecChain.Scope scope,
                        final AsyncExecChain chain, final AsyncExecCallback callback) throws IOException, HttpException {

        if (!opts.spanSampling.test(request.getRequestUri())) {
            // observation disabled for this URI
            chain.proceed(request, entityProducer, scope, callback);
            return;
        }

        final Observation obs = Observation
                .createNotStarted("http.client.request", registry)
                .contextualName(request.getMethod() + " " + request.getRequestUri())
                .lowCardinalityKeyValue("http.method", request.getMethod())
                .lowCardinalityKeyValue("net.peer.name",
                        scope.route.getTargetHost().getHostName())
                .start();

        final AsyncExecCallback wrapped = new AsyncExecCallback() {

            @Override
            public AsyncDataConsumer handleResponse(final HttpResponse response, final EntityDetails entity)
                    throws HttpException, IOException {

                obs.lowCardinalityKeyValue("http.status_code",
                        Integer.toString(response.getCode()));
                return callback.handleResponse(response, entity);
            }

            @Override
            public void handleInformationResponse(final HttpResponse response) throws HttpException, IOException {
                callback.handleInformationResponse(response);
            }

            @Override
            public void completed() {
                obs.stop();
                callback.completed();
            }

            @Override
            public void failed(final Exception cause) {
                obs.error(cause);
                obs.stop();
                callback.failed(cause);
            }
        };

        chain.proceed(request, entityProducer, scope, wrapped);
    }
}
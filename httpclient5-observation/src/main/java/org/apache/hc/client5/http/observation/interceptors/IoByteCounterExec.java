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
package org.apache.hc.client5.http.observation.interceptors;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.client5.http.observation.ObservingOptions;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.io.entity.EntityUtils;

public final class IoByteCounterExec implements ExecChainHandler {

    private final Counter.Builder sentBuilder;
    private final Counter.Builder recvBuilder;
    private final ObservingOptions opts;

    public IoByteCounterExec(final ObservationRegistry reg, final ObservingOptions opts) {
        this.opts = opts;
        this.sentBuilder = Counter.builder("http.client.bytes_sent")
                .tags("component", "httpclient");
        this.recvBuilder = Counter.builder("http.client.bytes_received")
                .tags("component", "httpclient");
    }

    @Override
    public ClassicHttpResponse execute(final ClassicHttpRequest request,
                                       final ExecChain.Scope scope,
                                       final ExecChain chain) throws IOException, HttpException {

        final ClassicHttpResponse response = chain.proceed(request, scope);

        final long sent = request.getEntity() != null ? request.getEntity().getContentLength() : 0;
        final long recv = response.getEntity() != null ? response.getEntity().getContentLength() : 0;
        if (response.getEntity() != null) {
            EntityUtils.consume(response.getEntity());  // ensure CL known / free resources
        }

        /* ---- build tag list without switch-expression ---- */
        final List<Tag> tags = new ArrayList<>(3);
        tags.add(Tag.of("method", request.getMethod()));
        if (opts.tagLevel == ObservingOptions.TagLevel.EXTENDED) {
            tags.add(Tag.of("target", scope.route.getTargetHost().getHostName()));
        }
        /* --------------------------------------------------- */

        sentBuilder.tags(tags)
                .register(io.micrometer.core.instrument.Metrics.globalRegistry)
                .increment(sent);

        recvBuilder.tags(tags)
                .register(io.micrometer.core.instrument.Metrics.globalRegistry)
                .increment(recv);

        return response;
    }
}

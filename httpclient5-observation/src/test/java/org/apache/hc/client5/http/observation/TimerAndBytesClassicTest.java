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
package org.apache.hc.client5.http.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.junit.jupiter.api.Test;

class TimerAndBytesClassicTest {

    @Test
    void emitsLatencyAndByteMeters() throws Exception {
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final ObservationRegistry reg = ObservationRegistry.create();

        final HttpClientBuilder b = HttpClients.custom();
        HttpClientObservationSupport.enable(b, reg);   // default = tracing+metrics
        try (final CloseableHttpClient client = b.build()) {

            final ClassicHttpResponse resp = client.executeOpen(
                    null, ClassicRequestBuilder.get("https://httpbin.org/get").build(), null);
            assertEquals(200, resp.getCode());
            resp.close();
        }

        /* -- verify meters present -- */
        assertTrue(meters.getMeters().stream()
                .anyMatch(m -> m.getId().getName().equals("http.client.request")));
        assertTrue(meters.getMeters().stream()
                .anyMatch(m -> m.getId().getName().equals("http.client.bytes_received")));
    }
}

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
package org.apache.hc.client5.testing.sync;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.socket.VsockAddress;
import org.apache.hc.client5.http.socket.VsockSocketFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class VsockIntegrationTests {

    @Test
    void testVsockRequestExecution() throws Exception {
        final String cidProp = System.getProperty("hc5.vsock.cid");
        final String portProp = System.getProperty("hc5.vsock.port");
        final String uri = System.getProperty("hc5.vsock.uri");
        Assumptions.assumeTrue(cidProp != null && portProp != null && uri != null,
            "Set -Dhc5.vsock.cid, -Dhc5.vsock.port and -Dhc5.vsock.uri to enable");
        Assumptions.assumeTrue(VsockSocketFactory.isAvailable(), "junixsocket-vsock not available");

        final int cid = Integer.parseInt(cidProp);
        final int port = Integer.parseInt(portProp);
        final VsockAddress vsockAddress = VsockAddress.of(cid, port);
        final RequestConfig requestConfig = RequestConfig.custom()
                .setVsockAddress(vsockAddress)
                .build();

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            final HttpGet request = new HttpGet(uri);
            request.setConfig(requestConfig);
            try (CloseableHttpResponse response = client.execute(request)) {
                Assertions.assertTrue(response.getCode() > 0, "Expected a valid HTTP status code");
            }
        }
    }
}

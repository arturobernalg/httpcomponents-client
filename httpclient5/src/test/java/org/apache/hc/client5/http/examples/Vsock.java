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
package org.apache.hc.client5.http.examples;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.socket.VsockAddress;

/**
 * Simple VSOCK client example.
 *
 * <p>Test server helper:</p>
 * <pre>
 * #!/bin/sh
 * printf %b "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nok\n"
 * </pre>
 *
 * <p>Run the server:</p>
 * <pre>
 * socat -v VSOCK-LISTEN:5000,fork SYSTEM:/tmp/vsock_reply.sh
 * </pre>
 */
public class Vsock {
    public static void main(final String[] args) throws IOException {
        if (args.length == 0 || "-h".equals(args[0]) || "--help".equals(args[0])) {
            usage(System.out);
            return;
        } else if (args.length != 3) {
            usage(System.err);
            return;
        }

        final int cid = Integer.parseInt(args[0]);
        final int port = Integer.parseInt(args[1]);
        final String uri = args[2];
        final VsockAddress vsockAddress = VsockAddress.of(cid, port);

        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            final HttpGet httpGet = new HttpGet(uri);
            httpGet.setConfig(RequestConfig.custom().setVsockAddress(vsockAddress).build());
            client.execute(httpGet, classicHttpResponse -> {
                final InputStream inputStream = classicHttpResponse.getEntity().getContent();
                final byte[] buf = new byte[8192];
                int len;
                while ((len = inputStream.read(buf)) > 0) {
                    System.out.write(buf, 0, len);
                }
                return null;
            });
        }
    }

    private static void usage(final PrintStream printStream) {
        printStream.println("Usage: Vsock [cid] [port] [uri]");
        printStream.println();
        printStream.println("Examples:");
        printStream.println("Vsock 2 5000 'http://localhost/'");
    }
}

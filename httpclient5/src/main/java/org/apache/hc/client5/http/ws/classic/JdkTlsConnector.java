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

package org.apache.hc.client5.http.ws.classic;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.Objects;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.apache.hc.core5.util.Timeout;

/**
 * JSSE connector: direct TCP connect and optional TLS with SNI + hostname verification.
 * <p>Construct via {@link JdkTlsConnectorBuilder}.
 */
public final class JdkTlsConnector implements WebSocketConnector {

    private final Timeout connectTimeout;
    private final SSLContext sslContext;
    private final boolean verifyHostname;
    private final SocketAddress localBind;
    private final Proxy proxy;

    JdkTlsConnector(
            final SSLContext sslContext,
            final Timeout connectTimeout,
            final boolean verifyHostname,
            final SocketAddress localBind,
            final Proxy proxy) {
        this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
        this.connectTimeout = connectTimeout != null ? connectTimeout : Timeout.DISABLED;
        this.verifyHostname = verifyHostname;
        this.localBind = localBind;
        this.proxy = proxy;
    }

    @Override
    public Socket connect(final URI uri) throws IOException {
        final boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
        final String host = Objects.requireNonNull(uri.getHost(), "URI host");
        final int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);

        // 1) create and (optionally) bind
        final Socket s = (proxy == null ? new Socket() : new Socket(proxy));
        if (localBind != null) {
            s.bind(localBind);
        }

        // 2) connect with timeout (0 == infinite per Socket API)
        final int connectMs = toMillisInt(connectTimeout);
        s.connect(new InetSocketAddress(host, port), connectMs);
        s.setTcpNoDelay(true);

        if (!secure) {
            return s;
        }

        // 3) layer TLS over the connected plain socket
        final SSLSocketFactory sf = sslContext.getSocketFactory();
        final SSLSocket ssl = (SSLSocket) sf.createSocket(s, host, port, true);

        // 4) SNI + hostname verification via SSLParameters
        final SSLParameters params = ssl.getSSLParameters();
        try {
            params.setServerNames(Collections.singletonList(new SNIHostName(host)));
        } catch (final IllegalArgumentException ignore) {
            // host may be an IP literal; SNI not applicable
        }
        if (verifyHostname) {
            params.setEndpointIdentificationAlgorithm("HTTPS");
        }
        ssl.setSSLParameters(params);

        // 5) handshake (throws on failure)
        ssl.startHandshake();
        return ssl;
    }

    private static int toMillisInt(final Timeout t) {
        if (t == null) {
            return 0; // infinite
        }
        final long ms = t.toMilliseconds();
        if (ms <= 0L) {
            return 0; // infinite per Socket#connect
        }
        return ms > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ms;
    }
}

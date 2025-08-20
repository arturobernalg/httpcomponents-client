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

import java.net.Proxy;
import java.net.SocketAddress;
import java.util.Objects;

import javax.net.ssl.SSLContext;

import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.ssl.SSLInitializationException;
import org.apache.hc.core5.util.Timeout;

/**
 * Builder for {@link JdkTlsConnector}. Uses {@link SSLContexts} / {@link SSLContextBuilder}
 * to obtain an initialized {@link SSLContext}.
 */
public final class JdkTlsConnectorBuilder {

    private Timeout connectTimeout = Timeout.ofSeconds(10);
    private SSLContext sslContext;
    //private SSLContextBuilder sslContextBuilder;
    private boolean verifyHostname = true;
    private SocketAddress localBind;
    private Proxy proxy = Proxy.NO_PROXY;

    public static JdkTlsConnectorBuilder create() {
        return new JdkTlsConnectorBuilder();
    }

    public JdkTlsConnectorBuilder connectTimeout(final Timeout t) {
        this.connectTimeout = t != null ? t : Timeout.DISABLED;
        return this;
    }

    /**
     * Supply a pre-built (initialized) SSLContext.
     */
    public JdkTlsConnectorBuilder sslContext(final SSLContext ctx) {
        this.sslContext = Objects.requireNonNull(ctx);
        return this;
    }

    /**
     * Supply an SSLContextBuilder to build a custom context (trust/key material, providers, etc.).
     */
//    public JdkTlsConnectorBuilder sslContextBuilder(final SSLContextBuilder b) {
//        this.sslContextBuilder = Objects.requireNonNull(b);
//        return this;
//    }

    public JdkTlsConnectorBuilder verifyHostname(final boolean b) {
        this.verifyHostname = b;
        return this;
    }

    public JdkTlsConnectorBuilder localBind(final SocketAddress a) {
        this.localBind = a;
        return this;
    }

    public JdkTlsConnectorBuilder proxy(final Proxy p) {
        this.proxy = Objects.requireNonNull(p);
        return this;
    }

    public JdkTlsConnector build() {
//        SSLContext ctx;
//        if (sslContext != null) {
//            // assume initialized; JSSE will throw on use if not
//            ctx = sslContext;
//        } else if (sslContextBuilder != null) {
//            try {
//                ctx = sslContextBuilder.build(); // initialized
//            } catch (final Exception e) {
//                throw new IllegalStateException("Unable to build SSLContext", e);
//            }
//        } else {
//            // Prefer system default (respects system properties); fallback to default-initialized
//            try {
//                ctx = SSLContexts.createSystemDefault(); // initialized
//            } catch (final SSLInitializationException e) {
//                try {
//                    ctx = SSLContexts.createDefault(); // initialized
//                } catch (final SSLInitializationException e2) {
//                    throw new IllegalStateException("Unable to obtain default SSLContext", e2);
//                }
//            }
//        }
        return new JdkTlsConnector(sslContext != null ? sslContext : SSLContexts.createSystemDefault() , connectTimeout, verifyHostname, localBind, proxy);
    }
}

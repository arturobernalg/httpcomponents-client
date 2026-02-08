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

package org.apache.hc.client5.http.socket;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.SocketAddress;

import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A factory for AF_VSOCK sockets based on the optional junixsocket-vsock module.
 *
 * @since 5.7
 */
@Contract(threading = ThreadingBehavior.STATELESS)
@Internal
public final class VsockSocketFactory {
    private static final Logger LOG = LoggerFactory.getLogger(VsockSocketFactory.class);
    private static final String JUNIXSOCKET_SOCKET_CLASS = "org.newsclub.net.unix.vsock.AFVSOCKSocket";
    private static final String JUNIXSOCKET_SOCKET_CLASS_FALLBACK = "org.newsclub.net.vsock.AFVSOCKSocket";
    private static final String JUNIXSOCKET_ADDRESS_CLASS = "org.newsclub.net.unix.AFVSOCKSocketAddress";
    private static final String JUNIXSOCKET_ADDRESS_CLASS_FALLBACK = "org.newsclub.net.vsock.AFVSOCKSocketAddress";

    private enum Implementation {
        JUNIXSOCKET,
        NONE
    }

    private static final Implementation IMPLEMENTATION = detectImplementation();

    private static Implementation detectImplementation() {
        try {
            loadClass(JUNIXSOCKET_SOCKET_CLASS, JUNIXSOCKET_SOCKET_CLASS_FALLBACK);
            LOG.debug("Using JUnixSocket AF_VSOCK implementation");
            return Implementation.JUNIXSOCKET;
        } catch (final ClassNotFoundException e) {
            LOG.debug("No AF_VSOCK implementation found");
            return Implementation.NONE;
        }
    }

    /**
     * Checks if AF_VSOCK support is available.
     *
     * @return true if AF_VSOCK support is available, false otherwise
     */
    public static boolean isAvailable() {
        return IMPLEMENTATION != Implementation.NONE;
    }

    /**
     * Default instance of {@link VsockSocketFactory}.
     */
    private static final VsockSocketFactory INSTANCE = new VsockSocketFactory();

    /**
     * Gets the singleton instance of {@link VsockSocketFactory}.
     *
     * @return the singleton instance
     */
    public static VsockSocketFactory getSocketFactory() {
        return INSTANCE;
    }

    public SocketAddress createSocketAddress(final VsockAddress vsockAddress) {
        if (!isAvailable()) {
            throw new UnsupportedOperationException("AF_VSOCK support is not available");
        }
        Args.notNull(vsockAddress, "Vsock address");

        try {
            final Class<?> addressClass = loadClass(JUNIXSOCKET_ADDRESS_CLASS, JUNIXSOCKET_ADDRESS_CLASS_FALLBACK);
            return (SocketAddress) createAddress(addressClass, vsockAddress.getCid(), vsockAddress.getPort());
        } catch (final ReflectiveOperationException ex) {
            throw new RuntimeException("Could not create VSOCK SocketAddress", ex);
        }
    }

    public Socket createSocket() throws IOException {
        if (!isAvailable()) {
            throw new UnsupportedOperationException("AF_VSOCK support is not available");
        }

        try {
            final Class<?> socketClass = loadClass(JUNIXSOCKET_SOCKET_CLASS, JUNIXSOCKET_SOCKET_CLASS_FALLBACK);
            final Method newInstanceMethod = socketClass.getMethod("newInstance");
            return (Socket) newInstanceMethod.invoke(null);
        } catch (final Exception e) {
            throw new IOException("Failed to create AF_VSOCK socket", e);
        }
    }

    public Socket connectSocket(
            final Socket socket,
            final VsockAddress vsockAddress,
            final TimeValue connectTimeout
    ) throws IOException {
        Args.notNull(vsockAddress, "Vsock address");

        final Socket sock = socket != null ? socket : createSocket();
        final SocketAddress address = createSocketAddress(vsockAddress);
        final int connTimeoutMs = TimeValue.isPositive(connectTimeout) ? connectTimeout.toMillisecondsIntBound() : 0;

        try {
            sock.connect(address, connTimeoutMs);
            return sock;
        } catch (final IOException ex) {
            try {
                sock.close();
            } catch (final IOException ignore) {
            }
            throw ex;
        }
    }

    private static Class<?> loadClass(final String... candidates) throws ClassNotFoundException {
        ClassNotFoundException last = null;
        for (final String candidate : candidates) {
            try {
                return Class.forName(candidate);
            } catch (final ClassNotFoundException ex) {
                last = ex;
            }
        }
        throw last != null ? last : new ClassNotFoundException("No class candidates provided");
    }

    private static Object createAddress(final Class<?> addressClass, final int cid, final int port)
            throws ReflectiveOperationException {
        try {
            return addressClass.getMethod("of", int.class, int.class).invoke(null, cid, port);
        } catch (final NoSuchMethodException ignore) {
        } catch (final InvocationTargetException ex) {
            throw new ReflectiveOperationException(ex.getCause() != null ? ex.getCause() : ex);
        }
        try {
            return addressClass.getMethod("ofPortAndCID", int.class, int.class).invoke(null, port, cid);
        } catch (final NoSuchMethodException ignore) {
        } catch (final InvocationTargetException ex) {
            throw new ReflectiveOperationException(ex.getCause() != null ? ex.getCause() : ex);
        }
        try {
            return addressClass.getMethod("ofPortAndCID", int.class, int.class, int.class)
                    .invoke(null, -1, port, cid);
        } catch (final InvocationTargetException ex) {
            throw new ReflectiveOperationException(ex.getCause() != null ? ex.getCause() : ex);
        }
    }
}

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
package org.apache.hc.client5.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

class DohDnsResolverTest {

    private static final String DNS_MEDIA_TYPE = "application/dns-message";
    private static final char[] STORE_PASS = "password".toCharArray();
    private static final String KEYSTORE_RESOURCE = "/doh-test.p12";

    private static SSLSocketFactory originalSocketFactory;
    private static HostnameVerifier originalHostnameVerifier;

    @BeforeAll
    static void installTestTlsTrust() throws Exception {
        // Configure HttpsURLConnection to trust our test CA/cert.
        originalSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        originalHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();

        final SSLContext clientContext = createClientSslContextFromTestKeystore();
        HttpsURLConnection.setDefaultSSLSocketFactory(clientContext.getSocketFactory());

        // Keep this tight: accept only localhost / loopback for the test server.
        HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
            @Override
            public boolean verify(final String hostname, final SSLSession session) {
                return "localhost".equalsIgnoreCase(hostname)
                        || "127.0.0.1".equals(hostname)
                        || "::1".equals(hostname);
            }
        });
    }

    @AfterAll
    static void restoreTlsTrust() {
        if (originalSocketFactory != null) {
            HttpsURLConnection.setDefaultSSLSocketFactory(originalSocketFactory);
        }
        if (originalHostnameVerifier != null) {
            HttpsURLConnection.setDefaultHostnameVerifier(originalHostnameVerifier);
        }
    }

    @Test
    void resolvePostReturnsIpv4AndIpv6Records() throws Exception {
        final AtomicReference<String> methodRef = new AtomicReference<>();
        try (MockDohServer server = MockDohServer.start(methodRef, queryBytes -> {
            final int queryType = readQuestionType(queryBytes);
            if (queryType == 1) {
                return buildResponse(queryBytes, 0, records(
                        cnameRecord("www.example.test", "edge.example.test"),
                        addressRecord(1, "edge.example.test", new byte[]{(byte) 203, 0, 113, 10})
                ));
            }
            if (queryType == 28) {
                return buildResponse(queryBytes, 0, records(
                        cnameRecord("www.example.test", "edge.example.test"),
                        addressRecord(28, "edge.example.test", new byte[]{
                                0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x2a
                        })
                ));
            }
            return buildResponse(queryBytes, 0, new ArrayList<>());
        })) {
            final DohDnsResolver resolver = DohDnsResolver.builder()
                    .setServiceUri(server.getEndpointUri())
                    .build();

            final InetAddress[] addresses = resolver.resolve("www.example.test");

            assertEquals("POST", methodRef.get());
            assertEquals(2, addresses.length);
            assertArrayEquals(new byte[]{(byte) 203, 0, 113, 10}, addresses[0].getAddress());
            assertArrayEquals(new byte[]{
                    0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x2a
            }, addresses[1].getAddress());
        }
    }

    @Test
    void resolveCanonicalHostnameReturnsCname() throws Exception {
        final AtomicReference<String> methodRef = new AtomicReference<>();
        try (MockDohServer server = MockDohServer.start(methodRef, queryBytes -> buildResponse(queryBytes, 0, records(
                cnameRecord("www.example.test", "edge.example.test"),
                addressRecord(1, "edge.example.test", new byte[]{(byte) 203, 0, 113, 11})
        )))) {
            final DohDnsResolver resolver = DohDnsResolver.builder()
                    .setServiceUri(server.getEndpointUri())
                    .setResolveIpv6(false)
                    .build();

            final String canonicalHost = resolver.resolveCanonicalHostname("www.example.test");
            assertEquals("edge.example.test", canonicalHost);
        }
    }

    @Test
    void resolveGetModeUsesDnsQueryParameter() throws Exception {
        final AtomicReference<String> methodRef = new AtomicReference<>();
        try (MockDohServer server = MockDohServer.start(methodRef, queryBytes -> buildResponse(queryBytes, 0, records(
                addressRecord(1, "www.example.test", new byte[]{(byte) 198, 51, 100, 10})
        )))) {
            final DohDnsResolver resolver = DohDnsResolver.builder()
                    .setServiceUri(server.getEndpointUri())
                    .setUseGet(true)
                    .setResolveIpv6(false)
                    .build();

            final InetAddress[] addresses = resolver.resolve("www.example.test");

            assertEquals("GET", methodRef.get());
            assertEquals(1, addresses.length);
            assertArrayEquals(new byte[]{(byte) 198, 51, 100, 10}, addresses[0].getAddress());
        }
    }

    @Test
    void resolveThrowsUnknownHostOnNxDomain() throws Exception {
        try (MockDohServer server = MockDohServer.start(new AtomicReference<>(),
                queryBytes -> buildResponse(queryBytes, 3, new ArrayList<>()))) {

            final DohDnsResolver resolver = DohDnsResolver.builder()
                    .setServiceUri(server.getEndpointUri())
                    .setResolveIpv6(false)
                    .build();

            assertThrows(UnknownHostException.class, () -> resolver.resolve("does-not-exist.example.test"));
        }
    }

    @Test
    void disallowHttpSchemeByDefault() {
        assertThrows(IllegalArgumentException.class, () -> DohDnsResolver.builder()
                .setServiceUri(URI.create("http://localhost/dns-query"))
                .build());
    }

    // ---------------- helpers ----------------

    private static SSLContext createClientSslContextFromTestKeystore() throws Exception {
        final KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = DohDnsResolverTest.class.getResourceAsStream(KEYSTORE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing test keystore resource " + KEYSTORE_RESOURCE);
            }
            ks.load(in, STORE_PASS);
        }
        final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        final SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static SSLContext createServerSslContextFromTestKeystore() throws Exception {
        final KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = DohDnsResolverTest.class.getResourceAsStream(KEYSTORE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing test keystore resource " + KEYSTORE_RESOURCE);
            }
            ks.load(in, STORE_PASS);
        }
        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, STORE_PASS);

        final SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    private static byte[] extractQueryBytesFromGet(final String rawQuery) {
        if (rawQuery == null) {
            throw new IllegalArgumentException("Missing query string");
        }
        for (final String parameter : rawQuery.split("&")) {
            if (parameter.startsWith("dns=")) {
                return Base64.getUrlDecoder().decode(parameter.substring(4));
            }
        }
        throw new IllegalArgumentException("Missing dns query parameter");
    }

    private static List<DnsRecord> records(final DnsRecord... records) {
        return new ArrayList<>(Arrays.asList(records));
    }

    private static DnsRecord addressRecord(final int type, final String ownerName, final byte[] addressBytes) {
        return new DnsRecord(type, ownerName, addressBytes);
    }

    private static DnsRecord cnameRecord(final String ownerName, final String targetName) {
        return new DnsRecord(5, ownerName, encodeDomainName(targetName));
    }

    private static byte[] buildResponse(final byte[] queryBytes, final int rcode, final List<DnsRecord> answerRecords) {
        final int questionCount = readUnsignedShort(queryBytes, 4);

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(256);
        writeUnsignedShort(outputStream, readUnsignedShort(queryBytes, 0));          // ID
        writeUnsignedShort(outputStream, 0x8180 | (rcode & 0x0f));                  // standard response + rcode
        writeUnsignedShort(outputStream, questionCount);                            // QDCOUNT
        writeUnsignedShort(outputStream, answerRecords.size());                     // ANCOUNT
        writeUnsignedShort(outputStream, 0);                                        // NSCOUNT
        writeUnsignedShort(outputStream, 0);                                        // ARCOUNT

        outputStream.write(queryBytes, 12, queryBytes.length - 12);                 // question section

        for (final DnsRecord answerRecord : answerRecords) {
            final byte[] owner = encodeDomainName(answerRecord.name);
            outputStream.write(owner, 0, owner.length);

            writeUnsignedShort(outputStream, answerRecord.type);                    // TYPE
            writeUnsignedShort(outputStream, 1);                                    // CLASS IN
            writeInt(outputStream, 60);                                             // TTL
            writeUnsignedShort(outputStream, answerRecord.rData.length);            // RDLENGTH
            outputStream.write(answerRecord.rData, 0, answerRecord.rData.length);   // RDATA
        }
        return outputStream.toByteArray();
    }

    private static int readQuestionType(final byte[] queryBytes) {
        int offset = 12;
        while (offset < queryBytes.length && queryBytes[offset] != 0) {
            offset += (queryBytes[offset] & 0xff) + 1;
        }
        if (offset + 3 >= queryBytes.length) {
            throw new IllegalArgumentException("Invalid DNS query");
        }
        offset++;
        return readUnsignedShort(queryBytes, offset);
    }

    private static byte[] encodeDomainName(final String hostName) {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final String[] labels = hostName.split("\\.");
        for (final String label : labels) {
            final byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            outputStream.write(bytes.length);
            outputStream.write(bytes, 0, bytes.length);
        }
        outputStream.write(0);
        return outputStream.toByteArray();
    }

    private static int readUnsignedShort(final byte[] bytes, final int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static void writeUnsignedShort(final ByteArrayOutputStream outputStream, final int value) {
        outputStream.write((value >>> 8) & 0xff);
        outputStream.write(value & 0xff);
    }

    private static void writeInt(final ByteArrayOutputStream outputStream, final int value) {
        outputStream.write((value >>> 24) & 0xff);
        outputStream.write((value >>> 16) & 0xff);
        outputStream.write((value >>> 8) & 0xff);
        outputStream.write(value & 0xff);
    }

    @FunctionalInterface
    private interface QueryHandler {
        byte[] handle(byte[] queryBytes) throws Exception;
    }

    private static final class MockDohServer implements AutoCloseable {

        private final HttpsServer server;
        private final Executor executor;

        private MockDohServer(final HttpsServer server, final Executor executor) {
            this.server = server;
            this.executor = executor;
        }

        static MockDohServer start(
                final AtomicReference<String> methodRef,
                final QueryHandler queryHandler) throws Exception {

            final SSLContext serverContext = createServerSslContextFromTestKeystore();

            final HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(serverContext));

            final Executor executor = Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "mock-doh-server");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(executor);

            server.createContext("/dns-query", exchange -> handle(exchange, methodRef, queryHandler));
            server.start();

            return new MockDohServer(server, executor);
        }

        URI getEndpointUri() {
            final int port = server.getAddress().getPort();
            // Use localhost; HostnameVerifier above accepts it.
            return URI.create("https://localhost:" + port + "/dns-query");
        }

        @Override
        public void close() {
            server.stop(0);
            // Executor thread is daemon.
        }

        private static void handle(
                final HttpExchange exchange,
                final AtomicReference<String> methodRef,
                final QueryHandler queryHandler) throws IOException {

            final String method = exchange.getRequestMethod();
            methodRef.set(method);

            final byte[] queryBytes;
            if ("GET".equalsIgnoreCase(method)) {
                queryBytes = extractQueryBytesFromGet(exchange.getRequestURI().getRawQuery());
            } else if ("POST".equalsIgnoreCase(method)) {
                queryBytes = readFully(exchange.getRequestBody());
            } else {
                send(exchange, 405, "text/plain", "Method Not Allowed".getBytes(StandardCharsets.US_ASCII));
                return;
            }

            try {
                final byte[] responseBody = queryHandler.handle(queryBytes);
                send(exchange, 200, DNS_MEDIA_TYPE, responseBody);
            } catch (final Exception ex) {
                final byte[] error = ex.getMessage() != null
                        ? ex.getMessage().getBytes(StandardCharsets.US_ASCII)
                        : new byte[0];
                send(exchange, 500, "text/plain", error);
            }
        }

        private static void send(final HttpExchange exchange, final int code, final String contentType, final byte[] body)
                throws IOException {
            final Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType);
            headers.set("Content-Length", Integer.toString(body.length));
            exchange.sendResponseHeaders(code, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }

        private static byte[] readFully(final InputStream inputStream) throws IOException {
            final ByteArrayOutputStream out = new ByteArrayOutputStream(256);
            final byte[] buf = new byte[1024];
            int r;
            while ((r = inputStream.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        }
    }

    private static final class DnsRecord {
        private final int type;
        private final String name;
        private final byte[] rData;

        DnsRecord(final int type, final String name, final byte[] rData) {
            this.type = type;
            this.name = name;
            this.rData = rData;
        }
    }
}
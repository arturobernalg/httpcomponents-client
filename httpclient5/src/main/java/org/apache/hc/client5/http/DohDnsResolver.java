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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.net.InetAddressUtils;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.TextUtils;
import org.apache.hc.core5.util.Timeout;

/**
 * {@link DnsResolver} implementation that performs DNS lookups using DNS-over-HTTPS (DoH).
 * <p>
 * This resolver is opt-in and can be provided via
 * {@code PoolingHttpClientConnectionManagerBuilder#setDnsResolver(DnsResolver)} and
 * {@code PoolingAsyncClientConnectionManagerBuilder#setDnsResolver(DnsResolver)}.
 * </p>
 * <p>
 * The DoH endpoint URI must use {@code https}.
 * </p>
 * <p>
 * Bootstrap note: resolving the DoH service host itself is performed by the platform resolver
 * before this resolver can send requests to the DoH endpoint.
 * </p>
 *
 * @since 5.7
 */
@Contract(threading = ThreadingBehavior.SAFE)
public final class DohDnsResolver implements DnsResolver {

    private static final int DNS_HEADER_SIZE = 12;
    private static final int DNS_CLASS_IN = 1;

    private static final int DNS_TYPE_A = 1;
    private static final int DNS_TYPE_CNAME = 5;
    private static final int DNS_TYPE_AAAA = 28;

    private static final int DNS_FLAG_QR = 0x8000;

    private static final int DNS_RCODE_MASK = 0x000f;
    private static final int DNS_RCODE_NXDOMAIN = 3;

    /**
     * DoH clients using application/dns-message SHOULD use ID 0 to maximize cache friendliness.
     */
    private static final int DNS_QUERY_ID = 0;

    private static final String DNS_MEDIA_TYPE = "application/dns-message";
    private static final int DEFAULT_MAX_RESPONSE_SIZE = 8 * 1024;

    private static final int MAX_NAME_POINTER_JUMPS = 128;
    private static final int MAX_CNAME_CHAIN = 16;

    private final URI serviceUri;
    private final boolean useGet;
    private final boolean resolveIpv4;
    private final boolean resolveIpv6;
    private final Timeout connectTimeout;
    private final Timeout responseTimeout;
    private final int maxResponseSize;
    private final Map<String, String> requestHeaders;

    private DohDnsResolver(final Builder builder) {
        this.serviceUri = Args.notNull(builder.serviceUri, "DoH service URI");
        Args.check(serviceUri.getScheme() != null, "DoH service URI must have a scheme");
        Args.check(!TextUtils.isBlank(serviceUri.getHost()), "DoH service URI must have a host");
        Args.check("https".equalsIgnoreCase(serviceUri.getScheme()), "DoH service URI must use https");

        this.useGet = builder.useGet;

        this.resolveIpv4 = builder.resolveIpv4;
        this.resolveIpv6 = builder.resolveIpv6;
        Args.check(resolveIpv4 || resolveIpv6, "At least one address family must be enabled");

        this.connectTimeout = builder.connectTimeout;
        this.responseTimeout = builder.responseTimeout;

        this.maxResponseSize = builder.maxResponseSize;
        Args.positive(maxResponseSize, "Max DoH response size");

        this.requestHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(builder.requestHeaders));
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public InetAddress[] resolve(final String host) throws UnknownHostException {
        final ResolutionResult resolutionResult = resolveInternal(host);
        if (resolutionResult.addresses.isEmpty()) {
            throw new UnknownHostException(host);
        }
        return resolutionResult.addresses.toArray(new InetAddress[0]);
    }

    @Override
    public String resolveCanonicalHostname(final String host) throws UnknownHostException {
        if (host == null) {
            return null;
        }
        final ResolutionResult resolutionResult = resolveInternal(host);
        if (resolutionResult.canonicalName != null) {
            return resolutionResult.canonicalName;
        }
        if (resolutionResult.addresses.isEmpty()) {
            throw new UnknownHostException(host);
        }
        return normalizeHost(host);
    }

    private ResolutionResult resolveInternal(final String host) throws UnknownHostException {
        final String normalizedHost = normalizeHost(host);
        if (InetAddressUtils.isIPv4(normalizedHost) || InetAddressUtils.isIPv6(normalizedHost)) {
            return new ResolutionResult(Collections.singletonList(InetAddress.getByName(normalizedHost)), normalizedHost);
        }

        final LinkedHashSet<InetAddress> addresses = new LinkedHashSet<>();
        String canonicalName = null;

        if (resolveIpv4) {
            final DnsQueryResult result = executeQuery(normalizedHost, DNS_TYPE_A);
            addresses.addAll(result.addresses);
            if (canonicalName == null) {
                canonicalName = result.canonicalName;
            }
        }
        if (resolveIpv6) {
            final DnsQueryResult result = executeQuery(normalizedHost, DNS_TYPE_AAAA);
            addresses.addAll(result.addresses);
            if (canonicalName == null) {
                canonicalName = result.canonicalName;
            }
        }

        return new ResolutionResult(new ArrayList<>(addresses), canonicalName);
    }

    private DnsQueryResult executeQuery(final String host, final int queryType) throws UnknownHostException {
        final int queryId = DNS_QUERY_ID;
        final byte[] query = buildQuery(host, queryId, queryType);
        final byte[] response = executeHttpExchange(host, query);
        return parseResponse(host, queryType, queryId, response);
    }

    private byte[] executeHttpExchange(final String host, final byte[] queryBytes) throws UnknownHostException {
        HttpURLConnection connection = null;
        try {
            final URI requestUri = useGet ? buildGetRequestUri(serviceUri, queryBytes) : serviceUri;
            final URL requestUrl = requestUri.toURL();

            connection = (HttpURLConnection) requestUrl.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);

            if (connectTimeout != null) {
                connection.setConnectTimeout(connectTimeout.toMillisecondsIntBound());
            }
            if (responseTimeout != null) {
                connection.setReadTimeout(responseTimeout.toMillisecondsIntBound());
            }

            connection.setRequestProperty("Accept", DNS_MEDIA_TYPE);
            connection.setRequestProperty("Accept-Encoding", "identity");
            for (final Map.Entry<String, String> header : requestHeaders.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }

            if (useGet) {
                connection.setRequestMethod("GET");
            } else {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", DNS_MEDIA_TYPE);
                connection.setFixedLengthStreamingMode(queryBytes.length);
                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(queryBytes);
                }
            }

            final int statusCode = connection.getResponseCode();
            if (statusCode / 100 != 2) {
                throw createUnknownHostException(host, null,
                        "DoH service " + serviceUri + " returned HTTP status " + statusCode);
            }

            final String contentType = connection.getHeaderField("Content-Type");
            if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith(DNS_MEDIA_TYPE)) {
                throw createUnknownHostException(host, null,
                        "DoH service " + serviceUri + " returned unexpected content type " + contentType);
            }

            try (InputStream inputStream = connection.getInputStream()) {
                return readFully(inputStream, maxResponseSize, host);
            }
        } catch (final IOException ex) {
            throw createUnknownHostException(host, ex, host);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static URI buildGetRequestUri(final URI baseServiceUri, final byte[] queryBytes) throws UnknownHostException {
        final String encodedQuery = Base64.getUrlEncoder().withoutPadding().encodeToString(queryBytes);
        final String existingQuery = baseServiceUri.getRawQuery();
        final String dnsParameter = "dns=" + encodedQuery;
        final String newRawQuery = TextUtils.isBlank(existingQuery) ? dnsParameter : existingQuery + "&" + dnsParameter;
        try {
            return new URI(
                    baseServiceUri.getScheme(),
                    baseServiceUri.getRawAuthority(),
                    baseServiceUri.getRawPath(),
                    newRawQuery,
                    baseServiceUri.getRawFragment());
        } catch (final URISyntaxException ex) {
            throw createUnknownHostException(baseServiceUri.getHost(), ex, "Invalid DoH request URI");
        }
    }

    private static byte[] readFully(final InputStream inputStream, final int maxSize, final String host)
            throws IOException {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(512);
        final byte[] buffer = new byte[1024];
        int bytesRead;
        int totalBytes = 0;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            totalBytes += bytesRead;
            if (totalBytes > maxSize) {
                throw createUnknownHostException(host, null, "DoH response exceeded " + maxSize + " bytes");
            }
            outputStream.write(buffer, 0, bytesRead);
        }
        return outputStream.toByteArray();
    }

    private static DnsQueryResult parseResponse(
            final String host,
            final int queryType,
            final int expectedQueryId,
            final byte[] responseBytes) throws UnknownHostException {

        if (responseBytes.length < DNS_HEADER_SIZE) {
            throw createUnknownHostException(host, null, "Invalid DoH response: too short");
        }

        final int responseId = readUnsignedShort(responseBytes, 0);
        if (responseId != expectedQueryId) {
            throw createUnknownHostException(host, null, "Invalid DoH response: transaction ID mismatch");
        }

        final int flags = readUnsignedShort(responseBytes, 2);
        if ((flags & DNS_FLAG_QR) == 0) {
            throw createUnknownHostException(host, null, "Invalid DoH response: expected a DNS response message");
        }

        final int rcode = flags & DNS_RCODE_MASK;
        if (rcode == DNS_RCODE_NXDOMAIN) {
            throw new UnknownHostException(host);
        }
        if (rcode != 0) {
            throw createUnknownHostException(host, null, "DoH query failed with DNS RCODE " + rcode);
        }

        final int questionCount = readUnsignedShort(responseBytes, 4);
        final int answerCount = readUnsignedShort(responseBytes, 6);
        final int authorityCount = readUnsignedShort(responseBytes, 8);
        final int additionalCount = readUnsignedShort(responseBytes, 10);

        if (questionCount != 1) {
            throw createUnknownHostException(host, null, "Invalid DoH response: expected exactly one DNS question");
        }

        int offset = DNS_HEADER_SIZE;

        final int[] questionOffset = new int[]{offset};
        final String questionName = parseDomainName(responseBytes, questionOffset);
        offset = questionOffset[0];

        if (offset + 4 > responseBytes.length) {
            throw createUnknownHostException(host, null, "Invalid DoH response: truncated DNS question");
        }

        final int qType = readUnsignedShort(responseBytes, offset);
        final int qClass = readUnsignedShort(responseBytes, offset + 2);
        offset += 4;

        if (!host.equalsIgnoreCase(questionName) || qType != queryType || qClass != DNS_CLASS_IN) {
            throw createUnknownHostException(host, null, "Invalid DoH response: mismatched DNS question section");
        }

        final List<InetAddress> addresses = new ArrayList<>();
        String currentName = host;
        int cnameDepth = 0;

        // ANSWER section only: avoids accidentally accepting unrelated glue records.
        for (int i = 0; i < answerCount; i++) {
            final int[] nameOffset = new int[]{offset};
            final String rrName = parseDomainName(responseBytes, nameOffset);
            offset = nameOffset[0];

            if (offset + 10 > responseBytes.length) {
                throw createUnknownHostException(host, null, "Invalid DoH response: truncated DNS resource record");
            }

            final int type = readUnsignedShort(responseBytes, offset);
            final int rrClass = readUnsignedShort(responseBytes, offset + 2);
            final int rDataLength = readUnsignedShort(responseBytes, offset + 8);
            final int rDataOffset = offset + 10;

            offset = rDataOffset + rDataLength;
            if (offset > responseBytes.length) {
                throw createUnknownHostException(host, null, "Invalid DoH response: invalid DNS RDATA length");
            }
            if (rrClass != DNS_CLASS_IN) {
                continue;
            }

            if (type == DNS_TYPE_CNAME && rrName.equalsIgnoreCase(currentName)) {
                if (cnameDepth >= MAX_CNAME_CHAIN) {
                    throw createUnknownHostException(host, null, "Invalid DoH response: CNAME chain too deep");
                }
                final int[] cnameOffset = new int[]{rDataOffset};
                currentName = parseDomainName(responseBytes, cnameOffset);
                cnameDepth++;
                continue;
            }

            if (type == queryType && rrName.equalsIgnoreCase(currentName)) {
                if (type == DNS_TYPE_A && rDataLength == 4) {
                    addresses.add(InetAddress.getByAddress(host, copyBytes(responseBytes, rDataOffset, 4)));
                } else if (type == DNS_TYPE_AAAA && rDataLength == 16) {
                    addresses.add(InetAddress.getByAddress(host, copyBytes(responseBytes, rDataOffset, 16)));
                }
            }
        }

        // Consume authority + additional to validate framing (but ignore content).
        final int remaining = authorityCount + additionalCount;
        for (int i = 0; i < remaining; i++) {
            final int[] nameOffset = new int[]{offset};
            parseDomainName(responseBytes, nameOffset);
            offset = nameOffset[0];
            if (offset + 10 > responseBytes.length) {
                throw createUnknownHostException(host, null, "Invalid DoH response: truncated DNS resource record");
            }
            final int rDataLength = readUnsignedShort(responseBytes, offset + 8);
            offset += 10 + rDataLength;
            if (offset > responseBytes.length) {
                throw createUnknownHostException(host, null, "Invalid DoH response: invalid DNS RDATA length");
            }
        }

        final String canonicalName = currentName.equalsIgnoreCase(host) ? null : currentName;
        return new DnsQueryResult(addresses, canonicalName);
    }

    private static byte[] buildQuery(final String host, final int queryId, final int queryType)
            throws UnknownHostException {

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(128);

        writeUnsignedShort(outputStream, queryId);
        writeUnsignedShort(outputStream, 0x0100); // standard query, recursion desired
        writeUnsignedShort(outputStream, 1);      // QDCOUNT
        writeUnsignedShort(outputStream, 0);      // ANCOUNT
        writeUnsignedShort(outputStream, 0);      // NSCOUNT
        writeUnsignedShort(outputStream, 0);      // ARCOUNT

        final String[] labels = host.split("\\.");
        for (final String label : labels) {
            final byte[] labelBytes = label.getBytes(StandardCharsets.US_ASCII);
            if (labelBytes.length == 0 || labelBytes.length > 63) {
                throw createUnknownHostException(host, null, "Invalid host label in " + host);
            }
            outputStream.write(labelBytes.length);
            outputStream.write(labelBytes, 0, labelBytes.length);
        }
        outputStream.write(0);

        writeUnsignedShort(outputStream, queryType);
        writeUnsignedShort(outputStream, DNS_CLASS_IN);

        return outputStream.toByteArray();
    }

    private static String normalizeHost(final String host) throws UnknownHostException {
        if (TextUtils.isBlank(host)) {
            throw new UnknownHostException(host);
        }

        final String trimmedHost = stripIpv6Brackets(host.trim());
        if (InetAddressUtils.isIPv4(trimmedHost) || InetAddressUtils.isIPv6(trimmedHost)) {
            return trimmedHost;
        }

        String normalizedHost = trimmedHost;
        if (!TextUtils.isAllASCII(normalizedHost)) {
            try {
                normalizedHost = IDN.toASCII(normalizedHost);
            } catch (final IllegalArgumentException ex) {
                throw createUnknownHostException(host, ex, host);
            }
        }

        if (normalizedHost.endsWith(".")) {
            normalizedHost = normalizedHost.substring(0, normalizedHost.length() - 1);
        }
        if (TextUtils.isBlank(normalizedHost)) {
            throw new UnknownHostException(host);
        }

        return normalizedHost.toLowerCase(Locale.ROOT);
    }

    private static String stripIpv6Brackets(final String host) {
        if (host.length() > 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static int readUnsignedShort(final byte[] bytes, final int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static void writeUnsignedShort(final ByteArrayOutputStream outputStream, final int value) {
        outputStream.write((value >>> 8) & 0xff);
        outputStream.write(value & 0xff);
    }

    private static byte[] copyBytes(final byte[] bytes, final int offset, final int length) {
        final byte[] out = new byte[length];
        System.arraycopy(bytes, offset, out, 0, length);
        return out;
    }

    private static String parseDomainName(final byte[] bytes, final int[] offsetHolder) throws UnknownHostException {
        final StringBuilder stringBuilder = new StringBuilder();
        final int end = bytes.length;
        int offset = offsetHolder[0];
        int returnOffset = -1;
        int pointerJumps = 0;

        while (true) {
            if (offset >= end) {
                throw createUnknownHostException(null, null, "Invalid DoH response: truncated DNS name");
            }
            final int len = bytes[offset] & 0xff;

            if ((len & 0xc0) == 0xc0) {
                if (offset + 1 >= end) {
                    throw createUnknownHostException(null, null, "Invalid DoH response: truncated DNS compression pointer");
                }
                final int pointer = ((len & 0x3f) << 8) | (bytes[offset + 1] & 0xff);
                if (pointer >= end) {
                    throw createUnknownHostException(null, null, "Invalid DoH response: invalid DNS compression pointer");
                }
                if (returnOffset < 0) {
                    returnOffset = offset + 2;
                }
                offset = pointer;
                pointerJumps++;
                if (pointerJumps > MAX_NAME_POINTER_JUMPS) {
                    throw createUnknownHostException(null, null, "Invalid DoH response: DNS compression pointer loop");
                }
                continue;
            }

            offset++;
            if (len == 0) {
                break;
            }
            if ((len & 0xc0) != 0) {
                throw createUnknownHostException(null, null, "Invalid DoH response: invalid DNS label");
            }
            if (offset + len > end) {
                throw createUnknownHostException(null, null, "Invalid DoH response: truncated DNS label");
            }

            if (stringBuilder.length() > 0) {
                stringBuilder.append('.');
            }
            stringBuilder.append(new String(bytes, offset, len, StandardCharsets.US_ASCII));
            offset += len;
        }

        offsetHolder[0] = returnOffset >= 0 ? returnOffset : offset;
        return stringBuilder.toString();
    }

    private static UnknownHostException createUnknownHostException(
            final String host,
            final Exception cause,
            final String message) {
        final UnknownHostException unknownHostException = new UnknownHostException(message != null ? message : host);
        if (cause != null) {
            unknownHostException.initCause(cause);
        }
        return unknownHostException;
    }

    private static final class ResolutionResult {
        final List<InetAddress> addresses;
        final String canonicalName;

        ResolutionResult(final List<InetAddress> addresses, final String canonicalName) {
            this.addresses = addresses;
            this.canonicalName = canonicalName;
        }
    }

    private static final class DnsQueryResult {
        final List<InetAddress> addresses;
        final String canonicalName;

        DnsQueryResult(final List<InetAddress> addresses, final String canonicalName) {
            this.addresses = addresses;
            this.canonicalName = canonicalName;
        }
    }

    public static final class Builder {
        private URI serviceUri;
        private boolean useGet;
        private boolean resolveIpv4 = true;
        private boolean resolveIpv6 = true;
        private Timeout connectTimeout;
        private Timeout responseTimeout;
        private int maxResponseSize = DEFAULT_MAX_RESPONSE_SIZE;
        private final Map<String, String> requestHeaders = new LinkedHashMap<>();

        Builder() {
        }

        public Builder setServiceUri(final URI serviceUri) {
            this.serviceUri = serviceUri;
            return this;
        }

        public Builder setUseGet(final boolean useGet) {
            this.useGet = useGet;
            return this;
        }

        public Builder setResolveIpv4(final boolean resolveIpv4) {
            this.resolveIpv4 = resolveIpv4;
            return this;
        }

        public Builder setResolveIpv6(final boolean resolveIpv6) {
            this.resolveIpv6 = resolveIpv6;
            return this;
        }

        public Builder setConnectTimeout(final Timeout connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder setResponseTimeout(final Timeout responseTimeout) {
            this.responseTimeout = responseTimeout;
            return this;
        }

        public Builder setMaxResponseSize(final int maxResponseSize) {
            this.maxResponseSize = maxResponseSize;
            return this;
        }

        public Builder addRequestHeader(final String name, final String value) {
            requestHeaders.put(Args.notBlank(name, "Header name"), Args.notNull(value, "Header value"));
            return this;
        }

        public DohDnsResolver build() {
            return new DohDnsResolver(this);
        }
    }
}
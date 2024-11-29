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

package org.apache.hc.client5.http.impl.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.hc.client5.http.auth.AuthChallenge;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.AuthenticationException;
import org.apache.hc.client5.http.auth.ChallengeType;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.junit.jupiter.api.Test;

/**
 * Tests for strict SCRAM over HTTP (RFC 7804).
 * <p>
 * Package-private class & methods.
 *
 * @since 5.6
 */
class TestScramScheme {

    static final String REALM = "test-realm";
    static final String USER = "user";
    static final String PASS = "pencil";
    static final HttpHost HOST = new HttpHost("http", "example.com");

    // -------- helpers --------

    static String b64enc(final byte[] data) {
        return Base64.getEncoder().withoutPadding().encodeToString(data);
    }

    static byte[] b64dec(final String s) {
        return Base64.getDecoder().decode(s);
    }

    static String b64decStr(final String s) {
        return new String(b64dec(s), StandardCharsets.UTF_8);
    }

    static Map<String, String> parseAttrs(final String s) {
        final Map<String, String> m = new LinkedHashMap<>();
        int i = 0;
        while (i < s.length()) {
            if (i + 2 > s.length() || s.charAt(i + 1) != '=') throw new IllegalArgumentException("Bad attr at " + i);
            final String k = String.valueOf(s.charAt(i));
            i += 2;
            final StringBuilder v = new StringBuilder();
            while (i < s.length()) {
                final char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    break;
                }
                if (c == '=' && i + 2 < s.length()) {
                    final String esc = s.substring(i, i + 3);
                    if ("=2C".equals(esc)) {
                        v.append(',');
                        i += 3;
                        continue;
                    }
                    if ("=3D".equals(esc)) {
                        v.append('=');
                        i += 3;
                        continue;
                    }
                }
                v.append(c);
                i++;
            }
            m.put(k, v.toString());
        }
        return m;
    }

    // Replace your old splitHeader(...) with this dynamic version:
    static Map<String, String> splitHeader(final String header) {
        final int sp = header.indexOf(' ');
        if (sp < 0) {
            throw new IllegalArgumentException("Malformed auth header: no scheme token space");
        }
        final String s = header.substring(sp + 1).trim();
        final Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        while (i < s.length()) {
            final int eq = s.indexOf('=', i);
            final int comma = s.indexOf(',', i);
            if (eq != -1 && (comma == -1 || eq < comma)) {
                final String key = s.substring(i, eq).trim().toLowerCase(Locale.ROOT);
                i = eq + 1;
                final int next = s.indexOf(',', i);
                final String raw = (next == -1) ? s.substring(i).trim() : s.substring(i, next).trim();
                out.put(key, stripQuotes(raw));
                i = (next == -1) ? s.length() : next + 1;
            } else {
                final String key = (comma == -1 ? s.substring(i) : s.substring(i, comma))
                        .trim().toLowerCase(Locale.ROOT);
                if (!key.isEmpty()) out.put(key, null);
                i = (comma == -1) ? s.length() : comma + 1;
            }
        }
        return out;
    }


    static String stripQuotes(final String v) {
        if (v == null) return null;
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"')
            return v.substring(1, v.length() - 1);
        return v;
    }

    static String extractClientNonceFromAuthz(final String authz) {
        final String clientFirst = b64decStr(splitHeader(authz).get("data"));
        final String clientFirstBare = clientFirst.substring("n,,".length());
        return parseAttrs(clientFirstBare).get("r");
    }

    // -------- tests --------

    @Test
    void strictScram_fullRoundtrip_completes() throws Exception {
        final ScramScheme scheme = new ScramScheme();
        final BasicCredentialsProvider creds = new BasicCredentialsProvider();
        creds.setCredentials(new AuthScope(HOST, REALM, scheme.getName()),
                new UsernamePasswordCredentials(USER, PASS.toCharArray()));
        final HttpClientContext ctx = HttpClientContext.create();

        // 401 announce
        scheme.processChallenge(HOST, true,
                new AuthChallenge(ChallengeType.TARGET, scheme.getName(),
                        new BasicNameValuePair("realm", REALM)),
                ctx);
        assertTrue(scheme.isResponseReady(HOST, creds, ctx));

        // client-first (data quoted)
        final String authz1 = scheme.generateAuthResponse(HOST, null, ctx);
        final Map<String, String> h1 = splitHeader(authz1);
        assertTrue(h1.containsKey("data"));
        final String clientFirst = b64decStr(h1.get("data"));
        assertTrue(clientFirst.startsWith("n,,"));
        final String clientFirstBare = clientFirst.substring("n,,".length());
        final String cnonce = parseAttrs(clientFirstBare).get("r");
        assertNotNull(cnonce);

        // server-first
        final String serverFirst = "r=" + cnonce + "SRV,s=" + b64enc("testsalt".getBytes(StandardCharsets.UTF_8)) + ",i=4096";
        scheme.processChallenge(HOST, true,
                new AuthChallenge(ChallengeType.TARGET, scheme.getName(),
                        new BasicNameValuePair("realm", REALM),
                        new BasicNameValuePair("sid", "s1"),
                        new BasicNameValuePair("data", b64enc(serverFirst.getBytes(StandardCharsets.UTF_8)))),
                ctx);

        // client-final
        final String authz2 = scheme.generateAuthResponse(HOST, null, ctx);
        final Map<String, String> h2 = splitHeader(authz2);
        assertEquals("s1", h2.get("sid"));
        final Map<String, String> cf = parseAttrs(b64decStr(h2.get("data")));
        assertEquals("biws", cf.get("c"));

        // 2xx: echo exactly expected v
        final String expectedV = (String) ctx.getAttribute(ScramScheme.class.getName() + ".expectedV");
        assertNotNull(expectedV);
        scheme.processChallenge(HOST, false,
                new AuthChallenge(ChallengeType.TARGET, scheme.getName(),
                        new BasicNameValuePair("data", b64enc(("v=" + expectedV).getBytes(StandardCharsets.UTF_8)))),
                ctx);

        assertTrue(scheme.isChallengeComplete());
        assertNull(ctx.getAttribute(ScramScheme.class.getName() + ".expectedV"));
    }

    @Test
    void strictScram_serverError_eParam_fails() throws Exception {
        final ScramScheme scheme = new ScramScheme();
        final BasicCredentialsProvider creds = new BasicCredentialsProvider();
        creds.setCredentials(new AuthScope(HOST, REALM, scheme.getName()),
                new UsernamePasswordCredentials(USER, PASS.toCharArray()));
        final HttpClientContext ctx = HttpClientContext.create();

        scheme.processChallenge(HOST, true,
                new AuthChallenge(ChallengeType.TARGET, scheme.getName(),
                        new BasicNameValuePair("realm", REALM)),
                ctx);
        assertTrue(scheme.isResponseReady(HOST, creds, ctx));

        final String authz1 = scheme.generateAuthResponse(HOST, null, ctx);
        final String cnonce = extractClientNonceFromAuthz(authz1);

        final String serverFirst = "r=" + cnonce + "X,s=" + b64enc("salt".getBytes(StandardCharsets.UTF_8)) + ",i=4096";
        scheme.processChallenge(HOST, true,
                new AuthChallenge(ChallengeType.TARGET, scheme.getName(),
                        new BasicNameValuePair("sid", "s1"),
                        new BasicNameValuePair("data", b64enc(serverFirst.getBytes(StandardCharsets.UTF_8)))),
                ctx);

        scheme.generateAuthResponse(HOST, null, ctx);

        final AuthenticationException ex = assertThrows(AuthenticationException.class, () ->
                scheme.processChallenge(HOST, false,
                        new AuthChallenge(ChallengeType.TARGET, scheme.getName(),
                                new BasicNameValuePair("data", b64enc("e=invalid-proof".getBytes(StandardCharsets.UTF_8)))),
                        ctx));
        assertTrue(ex.getMessage().contains("server error"));
    }

    @Test
    void strictScram_invalidServerNonce_rejectedByClient() throws Exception {
        final ScramScheme scheme = new ScramScheme();
        final BasicCredentialsProvider creds = new BasicCredentialsProvider();
        creds.setCredentials(new AuthScope(HOST, REALM, scheme.getName()),
                new UsernamePasswordCredentials(USER, PASS.toCharArray()));
        final HttpClientContext ctx = HttpClientContext.create();

        scheme.processChallenge(HOST, true,
                new AuthChallenge(ChallengeType.TARGET, scheme.getName(),
                        new BasicNameValuePair("realm", REALM)),
                ctx);
        assertTrue(scheme.isResponseReady(HOST, creds, ctx));

        final String authz1 = scheme.generateAuthResponse(HOST, null, ctx);
        final String cnonce = extractClientNonceFromAuthz(authz1);

        // nonce must start with client nonce; this one doesn't
        final String badServerFirst = "r=X" + cnonce + ",s=" + b64enc("salt".getBytes(StandardCharsets.UTF_8)) + ",i=4096";

        final AuthenticationException ex = assertThrows(AuthenticationException.class, () ->
                scheme.processChallenge(HOST, true,
                        new AuthChallenge(ChallengeType.TARGET, scheme.getName(),
                                new BasicNameValuePair("data", b64enc(badServerFirst.getBytes(StandardCharsets.UTF_8)))),
                        ctx));

        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("nonce"));
    }

    @Test
    void strictScram_lowIterations_warnsButSucceeds() throws Exception {
        final ScramScheme scheme = new ScramScheme(); // warn-only default
        final BasicCredentialsProvider creds = new BasicCredentialsProvider();
        creds.setCredentials(new AuthScope(HOST, REALM, scheme.getName()),
                new UsernamePasswordCredentials(USER, PASS.toCharArray()));
        final HttpClientContext ctx = HttpClientContext.create();

        scheme.processChallenge(HOST, true,
                new AuthChallenge(ChallengeType.TARGET, scheme.getName(),
                        new BasicNameValuePair("realm", REALM)),
                ctx);
        assertTrue(scheme.isResponseReady(HOST, creds, ctx));

        final String authz1 = scheme.generateAuthResponse(HOST, null, ctx);
        final String cnonce = extractClientNonceFromAuthz(authz1);

        // low i=1024 -> warn, not fail
        final String serverFirst = "r=" + cnonce + "Z,s=" + b64enc("salt-iter".getBytes(StandardCharsets.UTF_8)) + ",i=1024";
        scheme.processChallenge(HOST, true,
                new AuthChallenge(ChallengeType.TARGET, scheme.getName(),
                        new BasicNameValuePair("sid", "sid-low"),
                        new BasicNameValuePair("data", b64enc(serverFirst.getBytes(StandardCharsets.UTF_8)))),
                ctx);

        final String authz2 = scheme.generateAuthResponse(HOST, null, ctx);
        final Map<String, String> cf = parseAttrs(b64decStr(splitHeader(authz2).get("data")));
        assertEquals("biws", cf.get("c"));

        final String expectedV = (String) ctx.getAttribute(ScramScheme.class.getName() + ".expectedV");
        assertNotNull(expectedV);

        scheme.processChallenge(HOST, false,
                new AuthChallenge(ChallengeType.TARGET, scheme.getName(),
                        new BasicNameValuePair("data", b64enc(("v=" + expectedV).getBytes(StandardCharsets.UTF_8)))),
                ctx);

        assertTrue(scheme.isChallengeComplete());
    }

    @Test
    void strictScram_lowIterations_enforced_fails() throws Exception {
        final ScramScheme scheme = new ScramScheme(4096, 4096, null);

        final BasicCredentialsProvider creds = new BasicCredentialsProvider();
        creds.setCredentials(new AuthScope(HOST, REALM, scheme.getName()),
                new UsernamePasswordCredentials(USER, PASS.toCharArray()));
        final HttpClientContext ctx = HttpClientContext.create();

        scheme.processChallenge(HOST, true,
                new AuthChallenge(ChallengeType.TARGET, scheme.getName(),
                        new BasicNameValuePair("realm", REALM)),
                ctx);
        assertTrue(scheme.isResponseReady(HOST, creds, ctx));

        final String authz1 = scheme.generateAuthResponse(HOST, null, ctx);
        final String cnonce = extractClientNonceFromAuthz(authz1);

        final String serverFirst = "r=" + cnonce + "Z,s=" + b64enc("salt-iter".getBytes(StandardCharsets.UTF_8)) + ",i=1024";

        final AuthenticationException ex = assertThrows(AuthenticationException.class, () ->
                scheme.processChallenge(HOST, true,
                        new AuthChallenge(ChallengeType.TARGET, scheme.getName(),
                                new BasicNameValuePair("sid", "sid-low"),
                                new BasicNameValuePair("data", b64enc(serverFirst.getBytes(StandardCharsets.UTF_8)))),
                        ctx));

        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("iteration"));
        assertTrue(ex.getMessage().contains("1024"));
        assertTrue(ex.getMessage().contains("4096"));
    }

    @Test
    void strictScram_minIterations_equal_succeeds() throws Exception {
        final ScramScheme scheme = new ScramScheme(4096, 4096, null);

        final BasicCredentialsProvider creds = new BasicCredentialsProvider();
        creds.setCredentials(new AuthScope(HOST, REALM, scheme.getName()),
                new UsernamePasswordCredentials(USER, PASS.toCharArray()));
        final HttpClientContext ctx = HttpClientContext.create();

        scheme.processChallenge(HOST, true,
                new AuthChallenge(ChallengeType.TARGET, scheme.getName(),
                        new BasicNameValuePair("realm", REALM)),
                ctx);
        assertTrue(scheme.isResponseReady(HOST, creds, ctx));

        final String authz1 = scheme.generateAuthResponse(HOST, null, ctx);
        final String cnonce = extractClientNonceFromAuthz(authz1);

        final String serverFirst = "r=" + cnonce + "SRV,s=" + b64enc("testsalt".getBytes(StandardCharsets.UTF_8)) + ",i=4096";
        scheme.processChallenge(HOST, true,
                new AuthChallenge(ChallengeType.TARGET, scheme.getName(),
                        new BasicNameValuePair("sid", "s-ok"),
                        new BasicNameValuePair("data", b64enc(serverFirst.getBytes(StandardCharsets.UTF_8)))),
                ctx);

        final String authz2 = scheme.generateAuthResponse(HOST, null, ctx);
        final Map<String, String> cf = parseAttrs(b64decStr(splitHeader(authz2).get("data")));
        assertEquals("biws", cf.get("c"));

        final String expectedV = (String) ctx.getAttribute(ScramScheme.class.getName() + ".expectedV");
        assertNotNull(expectedV);

        scheme.processChallenge(HOST, false,
                new AuthChallenge(ChallengeType.TARGET, scheme.getName(),
                        new BasicNameValuePair("data", b64enc(("v=" + expectedV).getBytes(StandardCharsets.UTF_8)))),
                ctx);

        assertTrue(scheme.isChallengeComplete());
    }
}

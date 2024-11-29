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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.hc.client5.http.auth.AuthChallenge;
import org.apache.hc.client5.http.auth.AuthScheme;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.AuthenticationException;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.CredentialsProvider;
import org.apache.hc.client5.http.auth.MalformedChallengeException;
import org.apache.hc.client5.http.auth.StandardAuthScheme;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Args;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Strict HTTP SCRAM client implementing {@code SCRAM-SHA-256} as defined by RFC&nbsp;7804
 * (HTTP authentication) and RFC&nbsp;5802/7677 (SASL SCRAM).
 * <p>
 * Notes:
 * <ul>
 *     <li>No channel binding for HTTP (GS2 header {@code "n,,"}; {@code c=biws}).</li>
 *     <li>Client validates server nonce prefix, iteration count, and server signature ({@code v})
 *     in {@code Authentication-Info} on a successful (non-401/407) response.</li>
 *     <li>Username is SASLprep’d as query; password is SASLprep’d as stored string.</li>
 *     <li>{@code data="..."} attribute is a quoted-string per RFC 7804.</li>
 * </ul>
 *
 * @since 5.6
 */
public final class ScramScheme implements AuthScheme {

    private static final Logger LOG = LoggerFactory.getLogger(ScramScheme.class);

    /**
     * Context key to stash the expected server {@code v=} until 2xx verification.
     */
    private static final String CTX_EXPECTED_V = ScramScheme.class.getName() + ".expectedV";

    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private enum State {
        INIT,
        ANNOUNCED,            // after 401 challenge w/o data
        CLIENT_FIRST_SENT,    // after Authorization with client-first
        SERVER_FIRST_RCVD,    // after 401 with data (r,s,i)
        CLIENT_FINAL_SENT,    // after Authorization with client-final (p=...)
        COMPLETE,             // after 2xx with matching v
        FAILED
    }

    private final SecureRandom secureRandom;
    private final int warnMinIterations;
    private final int minIterationsRequired;

    private State state = State.INIT;
    private boolean complete;

    private String realm;
    private String sid;

    private String username;     // SASLprep (query)
    private char[] password;     // SASLprep (stored)
    private Principal principal;

    private String clientNonce;
    private String clientFirstBare;
    private String serverFirstRaw;
    private String serverNonce;
    private byte[] salt;
    private int iterations;


    /**
     * Default policy: warn if {@code i < 4096}, no hard enforcement; SHA-256 only.
     *
     * @since 5.6
     */
    public ScramScheme() {
        this(4096, 0, null);
    }

    /**
     * Constructor with custom iteration policy.
     *
     * @param warnMinIterations     warn if iteration count is lower than this (0 disables warnings)
     * @param minIterationsRequired fail if iteration count is lower than this (0 disables enforcement)
     * @param rnd                   optional secure random source (null uses system default)
     * @since 5.6
     */
    public ScramScheme(final int warnMinIterations, final int minIterationsRequired, final SecureRandom rnd) {
        this.warnMinIterations = Math.max(0, warnMinIterations);
        this.minIterationsRequired = Math.max(0, minIterationsRequired);
        this.secureRandom = rnd != null ? rnd : new SecureRandom();
    }

    /**
     * Convenience constructor for warn-only policy.
     *
     * @since 5.6
     */
    public ScramScheme(final int warnMinIterations, final SecureRandom rnd) {
        this(warnMinIterations, 0, rnd);
    }

    /**
     * @since 5.6
     */
    @Override
    public String getName() {
        return StandardAuthScheme.SCRAM_SHA_256;
    }

    /**
     * @since 5.6
     */
    @Override
    public boolean isConnectionBased() {
        return false;
    }

    /**
     * SCRAM must read successful (non-401/407) responses to verify the server signature ({@code v=}).
     *
     * @since 5.6
     */
    @Override
    public boolean isChallengeExpected() {
        return true;
    }

    /**
     * Legacy entry point: wraps {@link AuthenticationException} as {@link MalformedChallengeException}.
     *
     * @since 5.6
     */
    @Override
    public void processChallenge(final AuthChallenge authChallenge, final HttpContext context)
            throws MalformedChallengeException {
        try {
            processChallenge(null, true, authChallenge, context);
        } catch (final AuthenticationException ex) {
            throw new MalformedChallengeException(ex.getMessage(), ex);
        }
    }

    /**
     * Handles both 401 challenges (with or without {@code data}) and 2xx {@code Authentication-Info}.
     *
     * @since 5.6
     */
    @Override
    public void processChallenge(
            final HttpHost host,
            final boolean challenged,
            final AuthChallenge authChallenge,
            final HttpContext context) throws MalformedChallengeException, AuthenticationException {

        Args.notNull(context, "HTTP context");

        if (authChallenge == null) {
            if (!challenged) {
                // 2xx with no info: nothing to do
                return;
            }
            throw new MalformedChallengeException("Null SCRAM challenge");
        }
        if (!StandardAuthScheme.SCRAM_SHA_256.equalsIgnoreCase(authChallenge.getSchemeName())) {
            throw new MalformedChallengeException("Unexpected scheme: " + authChallenge.getSchemeName());
        }

        final Map<String, String> params = toParamMap(authChallenge.getParams());

        if (challenged) {
            // --- 401 path ---

            final String data = params.get("data");
            if (data == null) {
                // initial announce (no data)
                this.realm = params.get("realm");
                this.state = State.ANNOUNCED;
                this.complete = false;
                return;
            }

            // server-first (data present)
            final String decoded = b64ToString(data);
            this.serverFirstRaw = decoded;
            final Map<String, String> attrs = parseAttrs(decoded);

            final String r = attrs.get("r");
            final String s = attrs.get("s");
            final String i = attrs.get("i");
            if (r == null || s == null || i == null) {
                this.state = State.FAILED;
                throw new MalformedChallengeException("SCRAM server-first missing r/s/i");
            }
            if (this.clientNonce == null || !r.startsWith(this.clientNonce)) {
                this.state = State.FAILED;
                throw new AuthenticationException("SCRAM server nonce does not start with client nonce");
            }

            this.sid = params.get("sid");
            try {
                this.salt = B64D.decode(s);
            } catch (final IllegalArgumentException e) {
                this.state = State.FAILED;
                throw new MalformedChallengeException("Invalid base64 salt", e);
            }
            try {
                this.iterations = Integer.parseInt(i);
            } catch (final NumberFormatException e) {
                this.state = State.FAILED;
                throw new MalformedChallengeException("Invalid iteration count", e);
            }

            if (this.minIterationsRequired > 0 && this.iterations < this.minIterationsRequired) {
                this.state = State.FAILED;
                throw new AuthenticationException(
                        "SCRAM iteration count below required minimum: " + this.iterations + " < " + this.minIterationsRequired);
            }
            if (this.warnMinIterations > 0 && this.iterations < this.warnMinIterations && LOG.isWarnEnabled()) {
                LOG.warn("SCRAM iteration count ({}) lower than recommended ({})", this.iterations, warnMinIterations);
            }

            this.serverNonce = r;
            this.state = State.SERVER_FIRST_RCVD;
            this.complete = false;
            return;
        }

        // --- 2xx path (Authentication-Info) ---
        final String data = params.get("data");
        if (data == null) {
            return;
        }
        final String decoded = b64ToString(data);
        final Map<String, String> attrs = parseAttrs(decoded);
        final String e = attrs.get("e");
        if (e != null) {
            this.state = State.FAILED;
            throw new AuthenticationException("SCRAM server error: " + e);
        }
        final String v = attrs.get("v");
        if (v == null) {
            return;
        }
        final String expected = (String) context.getAttribute(CTX_EXPECTED_V);
        context.setAttribute(CTX_EXPECTED_V, null); // clear immediately
        if (expected == null || !expected.equals(v)) {
            this.state = State.FAILED;
            throw new AuthenticationException("SCRAM server signature mismatch");
        }
        this.complete = true;
        this.state = State.COMPLETE;
    }

    /**
     * @since 5.6
     */
    @Override
    public boolean isChallengeComplete() {
        return this.complete || this.state == State.COMPLETE || this.state == State.FAILED;
    }

    /**
     * @since 5.6
     */
    @Override
    public String getRealm() {
        return this.realm;
    }

    /**
     * @since 5.6
     */
    @Override
    public boolean isResponseReady(
            final HttpHost host,
            final CredentialsProvider credentialsProvider,
            final HttpContext context) throws AuthenticationException {

        Args.notNull(credentialsProvider, "CredentialsProvider");
        final HttpClientContext clientContext = HttpClientContext.cast(context);
        final AuthScope scope = new AuthScope(host, this.realm, getName());
        final Credentials creds = credentialsProvider.getCredentials(scope, clientContext);
        if (!(creds instanceof UsernamePasswordCredentials)) {
            return false;
        }
        final UsernamePasswordCredentials up = (UsernamePasswordCredentials) creds;

        // SASLprep: username as query, password as stored
        try {
            final String preppedUser = SaslPrep.INSTANCE.prepAsQueryString(up.getUserName());
            final String preppedPass = SaslPrep.INSTANCE.prepAsStoredString(new String(up.getUserPassword()));
            this.username = preppedUser;
            this.password = preppedPass.toCharArray();
        } catch (final Exception e) {
            throw new AuthenticationException("SASLprep failed: " + e.getMessage(), e);
        }

        this.principal = new SimplePrincipal(this.username);

        switch (this.state) {
            case ANNOUNCED:
            case SERVER_FIRST_RCVD:
                return true;
            default:
                return false;
        }
    }

    /**
     * @since 5.6
     */
    @Override
    public String generateAuthResponse(
            final HttpHost host,
            final HttpRequest request,
            final HttpContext context) throws AuthenticationException {

        switch (this.state) {
            case ANNOUNCED:
                return buildClientFirst();
            case SERVER_FIRST_RCVD:
                return buildClientFinalAndExpectV(context);
            default:
                this.state = State.FAILED;
                throw new AuthenticationException("SCRAM state out of sequence: " + this.state);
        }
    }

    /**
     * Returns the user {@link Principal} whose credentials are used.
     *
     * @since 5.6
     */
    @Override
    public Principal getPrincipal() {
        return this.principal;
    }

    // ---------------- internals ----------------

    private String buildClientFirst() {
        this.clientNonce = genNonce();
        final String gs2 = "n,,";
        final String escUser = escapeUser(this.username);
        this.clientFirstBare = "n=" + escUser + ",r=" + this.clientNonce;
        final String data = stringToB64(gs2 + this.clientFirstBare);

        final StringBuilder sb = new StringBuilder(64);
        sb.append(StandardAuthScheme.SCRAM_SHA_256).append(' ');
        if (this.realm != null) {
            sb.append("realm=\"").append(this.realm).append("\", ");
        }
        sb.append("data=\"").append(data).append('"'); // quoted per RFC 7804
        this.state = State.CLIENT_FIRST_SENT;
        this.complete = false;
        return sb.toString();
    }

    private String buildClientFinalAndExpectV(final HttpContext context) throws AuthenticationException {
        byte[] salted = null;
        byte[] clientKey = null;
        byte[] storedKey = null;
        byte[] clientSignature = null;
        byte[] clientProof = null;
        byte[] serverKey = null;
        byte[] serverSignature = null;

        try {
            // HTTP SCRAM: no channel binding -> gs2 "n,," and c=biws
            final String cBindB64 = "biws"; // base64("n,,")
            final String clientFinalNoProof = "c=" + cBindB64 + ",r=" + this.serverNonce;
            final String authMessage = this.clientFirstBare + "," + this.serverFirstRaw + "," + clientFinalNoProof;

            salted = hiPBKDF2(this.password, this.salt, this.iterations, 32);
            clientKey = hmac(salted, "Client Key");
            storedKey = sha256(clientKey);
            clientSignature = hmac(storedKey, authMessage);
            clientProof = xor(clientKey, clientSignature);
            final String pB64 = B64.encodeToString(clientProof);

            serverKey = hmac(salted, "Server Key");
            serverSignature = hmac(serverKey, authMessage);
            final String vB64 = B64.encodeToString(serverSignature);
            context.setAttribute(CTX_EXPECTED_V, vB64);

            final String clientFinal = clientFinalNoProof + ",p=" + pB64;
            final String data = stringToB64(clientFinal);

            final StringBuilder sb = new StringBuilder(64);
            sb.append(StandardAuthScheme.SCRAM_SHA_256).append(' ');
            if (this.sid != null) {
                sb.append("sid=").append(this.sid).append(", ");
            }
            sb.append("data=\"").append(data).append('"'); // quoted

            this.state = State.CLIENT_FINAL_SENT;
            this.complete = false;
            return sb.toString();
        } catch (final GeneralSecurityException e) {
            this.state = State.FAILED;
            throw new AuthenticationException("SCRAM crypto failure: " + e.getMessage(), e);
        } finally {
            if (this.password != null) {
                for (int i = 0; i < this.password.length; i++) {
                    this.password[i] = 0;
                }
            }
            zero(salted);
            zero(clientKey);
            zero(storedKey);
            zero(clientSignature);
            zero(clientProof);
            zero(serverKey);
            zero(serverSignature);
        }
    }

    private static void zero(final byte[] a) {
        if (a != null) {
            Arrays.fill(a, (byte) 0);
        }
    }

    private static Map<String, String> toParamMap(final List<NameValuePair> pairs) {
        final Map<String, String> m = new HashMap<>();
        if (pairs != null) {
            for (final NameValuePair p : pairs) {
                if (p != null && p.getName() != null) {
                    m.put(p.getName().toLowerCase(Locale.ROOT), p.getValue());
                }
            }
        }
        return m;
    }

    // Parse "k=v(,k=v)*" with RFC 5802 escapes: "=2C" -> ",", "=3D" -> "="
    private static Map<String, String> parseAttrs(final String s) throws MalformedChallengeException {
        final Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        while (i < s.length()) {
            if (i + 2 > s.length() || s.charAt(i + 1) != '=') {
                throw new MalformedChallengeException("Bad SCRAM attr at index " + i);
            }
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
            out.put(k, v.toString());
        }
        return out;
    }

    private String genNonce() {
        final byte[] buf = new byte[16];
        this.secureRandom.nextBytes(buf);
        final StringBuilder sb = new StringBuilder(buf.length * 2);
        for (final byte b : buf) {
            final int v = b & 0xff;
            if (v < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    private static String escapeUser(final String user) {
        final StringBuilder sb = new StringBuilder(user.length() + 8);
        for (int i = 0; i < user.length(); i++) {
            final char c = user.charAt(i);
            if (c == ',') {
                sb.append("=2C");
            } else if (c == '=') {
                sb.append("=3D");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static byte[] hiPBKDF2(final char[] password, final byte[] salt, final int iterations, final int dkLen)
            throws GeneralSecurityException {
        final PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, dkLen * 8);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    private static byte[] hmac(final byte[] key, final String msg) throws GeneralSecurityException {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(final byte[] in) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(in);
    }

    private static byte[] xor(final byte[] a, final byte[] b) {
        final int len = Math.min(a.length, b.length);
        final byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }

    private static String stringToB64(final String s) {
        return B64.encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64ToString(final String b64) throws MalformedChallengeException {
        try {
            return new String(B64D.decode(b64), StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException e) {
            throw new MalformedChallengeException("Bad base64 'data' value", e);
        }
    }

    private static final class SimplePrincipal implements Principal {
        private final String name;

        private SimplePrincipal(final String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return this.name;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }
}

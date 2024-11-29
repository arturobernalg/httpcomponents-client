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

import org.apache.hc.client5.http.auth.AuthChallenge;
import org.apache.hc.client5.http.auth.AuthScheme;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.AuthenticationException;
import org.apache.hc.client5.http.auth.ChallengeType;
import org.apache.hc.client5.http.auth.CredentialsProvider;
import org.apache.hc.client5.http.auth.MalformedChallengeException;
import org.apache.hc.client5.http.auth.StandardAuthScheme;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.message.ParserCursor;
import org.apache.hc.core5.util.CharArrayBuffer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.net.ssl.SSLSession;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestScramScheme {

    private static AuthChallenge parse(final String s) throws ParseException {
        final CharArrayBuffer buffer = new CharArrayBuffer(s.length());
        buffer.append(s);
        final ParserCursor cursor = new ParserCursor(0, buffer.length());
        final List<AuthChallenge> authChallenges = AuthChallengeParser.INSTANCE.parse(ChallengeType.TARGET, buffer, cursor);
        Assertions.assertEquals(1, authChallenges.size());
        return authChallenges.get(0);
    }

    @Test
    void testScramAuthenticationEmptyChallenge() throws Exception {
        final AuthChallenge authChallenge = parse(StandardAuthScheme.SCRAM);
        final AuthScheme authscheme = new ScramScheme();
        Assertions.assertThrows(MalformedChallengeException.class, () ->
                authscheme.processChallenge(authChallenge, null));
    }

    @Test
    void testScramAuthenticationWithDefaultCreds() throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);
        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "username", "password".toCharArray())
                .build();

        final int nonceLength = 256 / 8;
        final SecureRandom mockSecureRandom = mock(SecureRandom.class);
        doAnswer(invocation -> {
            final byte[] nonceBytes = invocation.getArgument(0);
            final byte[] fixedBytes = "fixed_nonce_value".getBytes(StandardCharsets.US_ASCII);
            final byte[] generatedBytes = new byte[nonceLength];
            System.arraycopy(fixedBytes, 0, generatedBytes, 0, Math.min(generatedBytes.length, fixedBytes.length));
            System.arraycopy(generatedBytes, 0, nonceBytes, 0, generatedBytes.length);
            return fixedBytes;
        }).when(mockSecureRandom).nextBytes(any(byte[].class));

        final ScramScheme authscheme = new ScramScheme(mockSecureRandom);

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=" + "salt_value" + ", i=" + "4096, algorithm=SCRAM-SHA-256";
        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        context.setServerProof("fdgNw/69mkQAGimN+xwBEhhUXjnUBK65LZQ7ODT1YaA=");
        authscheme.processChallenge(authChallenge, context);

        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));
        final String response = authscheme.generateAuthResponse(host, request, context);
        Assertions.assertNotNull(response);
        Assertions.assertTrue(authscheme.isChallengeComplete());
        Assertions.assertFalse(authscheme.isConnectionBased());
    }

    @Test
    void testScramAuthenticationInvalidServerProof() throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);
        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "username", "password".toCharArray())
                .build();
        final int nonceLength = 256 / 8;
        final SecureRandom mockSecureRandom = mock(SecureRandom.class);
        doAnswer(invocation -> {
            final byte[] nonceBytes = invocation.getArgument(0);
            final byte[] fixedBytes = "fixed_nonce_value".getBytes(StandardCharsets.US_ASCII);
            final byte[] generatedBytes = new byte[nonceLength];
            System.arraycopy(fixedBytes, 0, generatedBytes, 0, Math.min(generatedBytes.length, fixedBytes.length));
            System.arraycopy(generatedBytes, 0, nonceBytes, 0, generatedBytes.length);
            return fixedBytes;
        }).when(mockSecureRandom).nextBytes(any(byte[].class));

        final ScramScheme authscheme = new ScramScheme(mockSecureRandom);

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=" + "salt_value" + ", i=" + "4096, algorithm=SCRAM-SHA-256";
        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        context.setServerProof("INVALID_PROOF");  // Incorrect server proof
        authscheme.processChallenge(authChallenge, context);

        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));
        Assertions.assertThrows(AuthenticationException.class, () ->
                authscheme.generateAuthResponse(host, request, context));
    }


    @Test
    void testScramAuthenticationInvalidChallengeParameters() throws Exception {
        final ScramScheme authscheme = new ScramScheme();

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", s=salt_value, i=4096, algorithm=SCRAM-SHA-256";
        final AuthChallenge authChallenge = parse(challenge);
        Assertions.assertThrows(MalformedChallengeException.class, () ->
                authscheme.processChallenge(authChallenge, null));
    }

    @Test
    void testScramAuthenticationUsernameWithInvalidCharacters()
            throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);
        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "invalid:username", "password".toCharArray())
                .build();
        final int nonceLength = 256 / 8;
        final SecureRandom mockSecureRandom = mock(SecureRandom.class);
        doAnswer(invocation -> {
            final byte[] nonceBytes = invocation.getArgument(0);
            final byte[] fixedBytes = "fixed_nonce_value".getBytes(StandardCharsets.US_ASCII);
            final byte[] generatedBytes = new byte[nonceLength];
            System.arraycopy(fixedBytes, 0, generatedBytes, 0, Math.min(generatedBytes.length, fixedBytes.length));
            System.arraycopy(generatedBytes, 0, nonceBytes, 0, generatedBytes.length);
            return fixedBytes;
        }).when(mockSecureRandom).nextBytes(any(byte[].class));

        final ScramScheme authscheme = new ScramScheme(mockSecureRandom);

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=salt_value, i=4096, algorithm=SCRAM-SHA-256";
        final AuthChallenge authChallenge = parse(challenge);
        final HttpClientContext context = HttpClientContext.create();
        authscheme.processChallenge(authChallenge, context);
        authscheme.isResponseReady(host, credentialsProvider, context);
        Assertions.assertThrows(AuthenticationException.class, () -> authscheme.generateAuthResponse(host, request, context));
    }

    @Test
    void testScramAuthenticationScramPlusMechanismEmptyChannelBindingType() throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);
        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "username", "password".toCharArray())
                .build();
        final int nonceLength = 256 / 8;
        final SecureRandom mockSecureRandom = mock(SecureRandom.class);
        doAnswer(invocation -> {
            final byte[] nonceBytes = invocation.getArgument(0);
            final byte[] fixedBytes = "fixed_nonce_value".getBytes(StandardCharsets.US_ASCII);
            final byte[] generatedBytes = new byte[nonceLength];
            System.arraycopy(fixedBytes, 0, generatedBytes, 0, Math.min(generatedBytes.length, fixedBytes.length));
            System.arraycopy(generatedBytes, 0, nonceBytes, 0, generatedBytes.length);
            return fixedBytes;
        }).when(mockSecureRandom).nextBytes(any(byte[].class));

        final ScramScheme authscheme = new ScramScheme(mockSecureRandom);

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=" + "salt_value" + ", i=" + "4096, algorithm=SCRAM-SHA-256-PLUS";
        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        context.setSSLSession(Mockito.mock(SSLSession.class));

        context.setServerProof("BI1UFV2y4dtE2BeHo6AjPUsRQzND31TfzGYbUWN73uI=");
        authscheme.processChallenge(authChallenge, context);

        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));
        Assertions.assertThrows(AuthenticationException.class, () -> authscheme.generateAuthResponse(host, request, context));
    }

    @Test
    void testScramAuthenticationScramPlusMechanismEmptyChannelBindingTypeNullServerProof() throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);
        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "username", "password".toCharArray())
                .build();
        final int nonceLength = 256 / 8;
        final SecureRandom mockSecureRandom = mock(SecureRandom.class);
        doAnswer(invocation -> {
            final byte[] nonceBytes = invocation.getArgument(0);
            final byte[] fixedBytes = "fixed_nonce_value".getBytes(StandardCharsets.US_ASCII);
            final byte[] generatedBytes = new byte[nonceLength];
            System.arraycopy(fixedBytes, 0, generatedBytes, 0, Math.min(generatedBytes.length, fixedBytes.length));
            System.arraycopy(generatedBytes, 0, nonceBytes, 0, generatedBytes.length);
            return fixedBytes;
        }).when(mockSecureRandom).nextBytes(any(byte[].class));

        final ScramScheme authscheme = new ScramScheme(mockSecureRandom);

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=" + "salt_value" + ", i=" + "4096, algorithm=SCRAM-SHA-256-PLUS";
        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        context.setSSLSession(Mockito.mock(SSLSession.class));

        authscheme.processChallenge(authChallenge, context);

        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));
        Assertions.assertThrows(AuthenticationException.class, () -> authscheme.generateAuthResponse(host, request, context));
    }

    @Test
    void testScramAuthenticationScramPlusMechanism() throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);
        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "username", "password".toCharArray())
                .build();

        final int nonceLength = 256 / 8;

        final SecureRandom mockSecureRandom = mock(SecureRandom.class);
        doAnswer(invocation -> {
            final byte[] nonceBytes = invocation.getArgument(0);
            final byte[] fixedBytes = "fixed_nonce_value".getBytes(StandardCharsets.US_ASCII);
            final byte[] generatedBytes = new byte[nonceLength];
            System.arraycopy(fixedBytes, 0, generatedBytes, 0, Math.min(generatedBytes.length, fixedBytes.length));
            System.arraycopy(generatedBytes, 0, nonceBytes, 0, generatedBytes.length);
            return fixedBytes;
        }).when(mockSecureRandom).nextBytes(any(byte[].class));

        final ScramScheme authscheme = new ScramScheme(mockSecureRandom);

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=" + "salt_value" + ", i=" + "4096, algorithm=SCRAM-SHA-256-PLUS";
        final AuthChallenge authChallenge = parse(challenge);


        // Mock SSLSession and X509Certificate
        final SSLSession mockSession = mock(SSLSession.class);
        final java.security.cert.X509Certificate mockCert = mock(X509Certificate.class);
        when(mockSession.getPeerCertificates()).thenReturn(new X509Certificate[]{mockCert});
        when(mockCert.getSigAlgName()).thenReturn("SHA256withRSA");
        final byte[] exampleCertBytes = "mocked-cert-encoded".getBytes(StandardCharsets.UTF_8);
        when(mockCert.getEncoded()).thenReturn(exampleCertBytes);

        final HttpClientContext context = HttpClientContext.create();
        context.setSSLSession(mockSession);

        context.setServerProof("XlohQrjda2oV5tONVh0SpxC6dEqumjFSAcMopsdKpEM=");
        context.setChannelBindingType("tls-server-end-point");
        authscheme.processChallenge(authChallenge, context);

        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));
        final String response = authscheme.generateAuthResponse(host, request, context);
        Assertions.assertNotNull(response);
        Assertions.assertTrue(authscheme.isChallengeComplete());
        Assertions.assertFalse(authscheme.isConnectionBased());
    }



    @Test
    void testScramAuthenticationScramPlusMechanismTlsExporter() throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);
        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "username", "password".toCharArray())
                .build();

        final int nonceLength = 256 / 8;

        final SecureRandom mockSecureRandom = mock(SecureRandom.class);
        doAnswer(invocation -> {
            final byte[] nonceBytes = invocation.getArgument(0);
            final byte[] fixedBytes = "fixed_nonce_value".getBytes(StandardCharsets.US_ASCII);
            final byte[] generatedBytes = new byte[nonceLength];
            System.arraycopy(fixedBytes, 0, generatedBytes, 0, Math.min(generatedBytes.length, fixedBytes.length));
            System.arraycopy(generatedBytes, 0, nonceBytes, 0, generatedBytes.length);
            return fixedBytes;
        }).when(mockSecureRandom).nextBytes(any(byte[].class));


        final ScramScheme authscheme = new ScramScheme(mockSecureRandom);

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=" + "salt_value" + ", i=" + "4096, algorithm=SCRAM-SHA-256-PLUS";
        final AuthChallenge authChallenge = parse(challenge);

        final SSLSession mockSession = mock(SSLSession.class);
        final java.security.cert.X509Certificate mockCert = mock(X509Certificate.class);
        when(mockSession.getPeerCertificates()).thenReturn(new X509Certificate[]{mockCert});
        when(mockCert.getSigAlgName()).thenReturn("SHA256withRSA");
        final byte[] exampleCertBytes = "mocked-cert-encoded".getBytes(StandardCharsets.UTF_8);
        when(mockCert.getEncoded()).thenReturn(exampleCertBytes);

        final HttpClientContext context = HttpClientContext.create();
        context.setSSLSession(mockSession);

        context.setServerProof("r+h2OpGwnV4VPOu39zGKvXpj8i+UowgX0L/0aWcjUM0=");
        context.setChannelBindingType("tls-exporter");
        authscheme.processChallenge(authChallenge, context);

        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));

        Assertions.assertThrows(AuthenticationException.class, () -> authscheme.generateAuthResponse(host, request, context));

    }


    @Test
    void testScramAuthenticationScramPlusWithTlsUnique() throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);
        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "username", "password".toCharArray())
                .build();
        final int nonceLength = 256 / 8;
        final SecureRandom mockSecureRandom = mock(SecureRandom.class);
        doAnswer(invocation -> {
            final byte[] nonceBytes = invocation.getArgument(0);
            final byte[] fixedBytes = "fixed_nonce_value".getBytes(StandardCharsets.US_ASCII);
            final byte[] generatedBytes = new byte[nonceLength];
            System.arraycopy(fixedBytes, 0, generatedBytes, 0, Math.min(generatedBytes.length, fixedBytes.length));
            System.arraycopy(generatedBytes, 0, nonceBytes, 0, generatedBytes.length);
            return fixedBytes;
        }).when(mockSecureRandom).nextBytes(any(byte[].class));

        final ScramScheme authscheme = new ScramScheme(mockSecureRandom);

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=" + "salt_value" + ", i=" + "4096, algorithm=SCRAM-SHA-256-PLUS";
        final AuthChallenge authChallenge = parse(challenge);

        // Mock the SSLSession, X509Certificate, and tlsUnique
        final SSLSession mockSession = mock(SSLSession.class);
        final java.security.cert.X509Certificate mockCert = mock(X509Certificate.class);
        when(mockSession.getPeerCertificates()).thenReturn(new X509Certificate[]{mockCert});
        when(mockCert.getSigAlgName()).thenReturn("SHA256withRSA");
        final byte[] exampleCertBytes = "mocked-cert-encoded".getBytes(StandardCharsets.UTF_8);
        when(mockCert.getEncoded()).thenReturn(exampleCertBytes);

        final HttpClientContext context = HttpClientContext.create();
        context.setSSLSession(mockSession);

        // Setting the TLS Unique value for channel binding
        final byte[] tlsUniqueMock = "mocked-tls-unique-value".getBytes(StandardCharsets.UTF_8);
        context.setTlsUnique(tlsUniqueMock);
        context.setChannelBindingType("tls-unique");

        // Set server proof to pass validation
        context.setServerProof("5zv82UxYxYrDCv2CIy+eCCT2lpQHDLimSzSy38KlNWQ=");

        authscheme.processChallenge(authChallenge, context);
        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));

        // Generate the authentication response
        final String response = authscheme.generateAuthResponse(host, request, context);
        Assertions.assertNotNull(response);
        Assertions.assertTrue(authscheme.isChallengeComplete());
        Assertions.assertFalse(authscheme.isConnectionBased());

        Assertions.assertTrue(response.contains(response), "Response should contain the correct channel binding value");
    }


    @Test
    void testScramAuthenticationScramPlusWithUnknownExporter() throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);
        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "username", "password".toCharArray())
                .build();

        final int nonceLength = 256 / 8;
        final SecureRandom mockSecureRandom = mock(SecureRandom.class);
        doAnswer(invocation -> {
            final byte[] nonceBytes = invocation.getArgument(0);
            final byte[] fixedBytes = "fixed_nonce_value".getBytes(StandardCharsets.US_ASCII);
            final byte[] generatedBytes = new byte[nonceLength];
            System.arraycopy(fixedBytes, 0, generatedBytes, 0, Math.min(generatedBytes.length, fixedBytes.length));
            System.arraycopy(generatedBytes, 0, nonceBytes, 0, generatedBytes.length);
            return fixedBytes;
        }).when(mockSecureRandom).nextBytes(any(byte[].class));


        final ScramScheme authscheme = new ScramScheme(mockSecureRandom);

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=" + "salt_value" + ", i=" + "4096, algorithm=SCRAM-SHA-256-PLUS";
        final AuthChallenge authChallenge = parse(challenge);

        // Mock the SSLSession, X509Certificate, and tlsUnique
        final SSLSession mockSession = mock(SSLSession.class);
        final java.security.cert.X509Certificate mockCert = mock(X509Certificate.class);
        when(mockSession.getPeerCertificates()).thenReturn(new X509Certificate[]{mockCert});
        when(mockCert.getSigAlgName()).thenReturn("SHA256withRSA");
        final byte[] exampleCertBytes = "mocked-cert-encoded".getBytes(StandardCharsets.UTF_8);
        when(mockCert.getEncoded()).thenReturn(exampleCertBytes);

        final HttpClientContext context = HttpClientContext.create();
        context.setSSLSession(mockSession);

        // Setting the TLS Unique value for channel binding
        final byte[] tlsUniqueMock = "mocked-tls-exporter-value".getBytes(StandardCharsets.UTF_8);
        context.setTlsUnique(tlsUniqueMock);
        context.setChannelBindingType("unknown-exporter");

        // Set server proof to pass validation
        context.setServerProof("2j0a/n53iM+ynGefMDCYdY0pkkDacOgq9q7ZAQxXpgQ=");

        authscheme.processChallenge(authChallenge, context);
        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));

        Assertions.assertThrows(AuthenticationException.class, () -> authscheme.generateAuthResponse(host, request, context));
    }

    @Test
    void testScramAuthenticationNotReady() throws Exception {
        final HttpHost host = new HttpHost("somehost", 80);
        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "username", "password".toCharArray())
                .build();

        final int nonceLength = 256 / 8;
        final SecureRandom mockSecureRandom = mock(SecureRandom.class);
        doAnswer(invocation -> {
            final byte[] nonceBytes = invocation.getArgument(0);
            final byte[] fixedBytes = "fixed_nonce_value".getBytes(StandardCharsets.US_ASCII);
            final byte[] generatedBytes = new byte[nonceLength];
            System.arraycopy(fixedBytes, 0, generatedBytes, 0, Math.min(generatedBytes.length, fixedBytes.length));
            System.arraycopy(generatedBytes, 0, nonceBytes, 0, generatedBytes.length);
            return fixedBytes;
        }).when(mockSecureRandom).nextBytes(any(byte[].class));

        final ScramScheme authscheme = new ScramScheme(mockSecureRandom);
        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm1\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=" + "salt_value" + ", i=" + "4096, algorithm=SCRAM-SHA-256";
        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        context.setServerProof("UV96i6tuUtnRX5Ao8b0ZXtmaD+VMb4ca2I/HP0S+PPA=");
        authscheme.processChallenge(authChallenge, context);

        Assertions.assertFalse(authscheme.isResponseReady(host, credentialsProvider, context));
        Assertions.assertNull(authscheme.getPrincipal());

    }


    @Test
    void testScramAuthenticationScramPlusMechanismNullSSL() throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);
        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "username", "password".toCharArray())
                .build();
        final int nonceLength = 256 / 8;
        final SecureRandom mockSecureRandom = mock(SecureRandom.class);
        doAnswer(invocation -> {
            final byte[] nonceBytes = invocation.getArgument(0);
            final byte[] fixedBytes = "fixed_nonce_value".getBytes(StandardCharsets.US_ASCII);
            final byte[] generatedBytes = new byte[nonceLength];
            System.arraycopy(fixedBytes, 0, generatedBytes, 0, Math.min(generatedBytes.length, fixedBytes.length));
            System.arraycopy(generatedBytes, 0, nonceBytes, 0, generatedBytes.length);
            return fixedBytes;
        }).when(mockSecureRandom).nextBytes(any(byte[].class));

        final ScramScheme authscheme = new ScramScheme(mockSecureRandom);

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=" + "salt_value" + ", i=" + "4096, algorithm=SCRAM-SHA-256-PLUS";
        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        context.setSSLSession(null);

        context.setServerProof("BI1UFV2y4dtE2BeHo6AjPUsRQzND31TfzGYbUWN73uI=");
        authscheme.processChallenge(authChallenge, context);

        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));
        Assertions.assertThrows(AuthenticationException.class, () -> authscheme.generateAuthResponse(host, request, context));
    }


    @Test
    void testScramAuthenticationScramPlusWithNullTlsUnique() throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);
        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "username", "password".toCharArray())
                .build();

        final int nonceLength = 256 / 8;
        final SecureRandom mockSecureRandom = mock(SecureRandom.class);
        doAnswer(invocation -> {
            final byte[] nonceBytes = invocation.getArgument(0);
            final byte[] fixedBytes = "fixed_nonce_value".getBytes(StandardCharsets.US_ASCII);
            final byte[] generatedBytes = new byte[nonceLength];
            System.arraycopy(fixedBytes, 0, generatedBytes, 0, Math.min(generatedBytes.length, fixedBytes.length));
            System.arraycopy(generatedBytes, 0, nonceBytes, 0, generatedBytes.length);
            return fixedBytes;
        }).when(mockSecureRandom).nextBytes(any(byte[].class));

        final ScramScheme authscheme = new ScramScheme(mockSecureRandom);

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=" + "salt_value" + ", i=" + "4096, algorithm=SCRAM-SHA-256-PLUS";
        final AuthChallenge authChallenge = parse(challenge);

        // Mock the SSLSession, X509Certificate, and tlsUnique
        final SSLSession mockSession = mock(SSLSession.class);
        final java.security.cert.X509Certificate mockCert = mock(X509Certificate.class);
        when(mockSession.getPeerCertificates()).thenReturn(new X509Certificate[]{mockCert});
        when(mockCert.getSigAlgName()).thenReturn("SHA256withRSA");
        final byte[] exampleCertBytes = "mocked-cert-encoded".getBytes(StandardCharsets.UTF_8);
        when(mockCert.getEncoded()).thenReturn(exampleCertBytes);

        final HttpClientContext context = HttpClientContext.create();
        context.setSSLSession(mockSession);

        context.setTlsUnique(null);
        context.setChannelBindingType("tls-unique");

        context.setServerProof("2j0a/n53iM+ynGefMDCYdY0pkkDacOgq9q7ZAQxXpgQ=");

        authscheme.processChallenge(authChallenge, context);
        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));

        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));
        Assertions.assertThrows(AuthenticationException.class, () -> authscheme.generateAuthResponse(host, request, context));
    }


    @Test
    void testScramAuthenticationNullServerProof() throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);
        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "username", "password".toCharArray())
                .build();
        final int nonceLength = 256 / 8;
        final SecureRandom mockSecureRandom = mock(SecureRandom.class);
        doAnswer(invocation -> {
            final byte[] nonceBytes = invocation.getArgument(0);
            final byte[] fixedBytes = "fixed_nonce_value".getBytes(StandardCharsets.US_ASCII);
            final byte[] generatedBytes = new byte[nonceLength];
            System.arraycopy(fixedBytes, 0, generatedBytes, 0, Math.min(generatedBytes.length, fixedBytes.length));
            System.arraycopy(generatedBytes, 0, nonceBytes, 0, generatedBytes.length);
            return fixedBytes;
        }).when(mockSecureRandom).nextBytes(any(byte[].class));

        final ScramScheme authscheme = new ScramScheme(mockSecureRandom);

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=" + "salt_value" + ", i=" + "4096, algorithm=SCRAM-SHA-256";
        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        context.setServerProof(null);
        authscheme.processChallenge(authChallenge, context);

        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));
        Assertions.assertThrows(AuthenticationException.class, () ->
                authscheme.generateAuthResponse(host, request, context));
    }
}
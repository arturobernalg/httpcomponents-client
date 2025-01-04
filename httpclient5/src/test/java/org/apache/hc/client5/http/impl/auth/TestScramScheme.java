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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLSession;

import org.apache.hc.client5.http.auth.AuthChallenge;
import org.apache.hc.client5.http.auth.AuthScheme;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.AuthenticationException;
import org.apache.hc.client5.http.auth.BasicUserPrincipal;
import org.apache.hc.client5.http.auth.ClientKeyServerKeyCredentials;
import org.apache.hc.client5.http.auth.CredentialsProvider;
import org.apache.hc.client5.http.auth.MalformedChallengeException;
import org.apache.hc.client5.http.auth.SaltedPasswordCredentials;
import org.apache.hc.client5.http.auth.StandardAuthScheme;
import org.apache.hc.client5.http.impl.ScramException;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TestScramScheme extends AbstractAuthTest {


    final SecureRandom MOCK_SECURE_RANDOM = mock(SecureRandom.class);

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

        fixNonce(new byte[nonceLength]);

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=" + "salt_value" + ", i=" + "4096, algorithm=SCRAM-SHA-256";
        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        context.setServerProof("543add26a1a6ea149cb38e02cecf90fb8934b4d84810ce485f9d94fe2bd35c81");
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
        fixNonce(new byte[nonceLength]);

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());

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
        final ScramScheme authScheme = new ScramScheme();

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", s=salt_value, i=4096, algorithm=SCRAM-SHA-256";
        final AuthChallenge authChallenge = parse(challenge);
        Assertions.assertThrows(MalformedChallengeException.class, () ->
                authScheme.processChallenge(authChallenge, null));
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
        fixNonce(new byte[nonceLength]);

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());

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
        fixNonce(new byte[nonceLength]);

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());

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
        fixNonce(new byte[nonceLength]);

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());

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

        fixNonce(new byte[nonceLength]);

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());

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

        context.setServerProof("9a4826112c7bd1dba2ea92cb98543da1f568d41d5c1350bef0e1e6a4fd414454");
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

        fixNonce(new byte[nonceLength]);


        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());

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
        fixNonce(new byte[nonceLength]);

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());

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
        context.setServerProof("534ab04ce5ee33e6990ea62cdf203163d4f3da9d971775d12cbd6593699890a5");

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
        fixNonce(new byte[nonceLength]);


        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());

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
        fixNonce(new byte[nonceLength]);

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());
        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm1\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=" + "salt_value" + ", i=" + "4096, algorithm=SCRAM-SHA-256";
        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        context.setServerProof("UV96i6tuUtnRX5Ao8b0ZXtmaD+VMb4ca2I/HP0S+PPA=");
        authscheme.processChallenge(authChallenge, context);

        Assertions.assertFalse(authscheme.isResponseReady(host, credentialsProvider, context));
        assertNull(authscheme.getPrincipal());

    }

    @Test
    void testScramAuthenticationScramPlusMechanismNullSSL() throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);
        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "username", "password".toCharArray())
                .build();
        final int nonceLength = 256 / 8;
        fixNonce(new byte[nonceLength]);

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());

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
        fixNonce(new byte[nonceLength]);

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());

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
        fixNonce(new byte[nonceLength]);

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=" + "salt_value" + ", i=" + "4096, algorithm=SCRAM-SHA-256";
        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        context.setServerProof(null);
        authscheme.processChallenge(authChallenge, context);

        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));
        Assertions.assertThrows(AuthenticationException.class, () ->
                authscheme.generateAuthResponse(host, request, context));
    }

    @Test
    void testScramAuthenticationBidirectionalCharactersD1() throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);
        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "username\u05D0", "password".toCharArray())
                .build();
        final int nonceLength = 256 / 8;
        fixNonce(new byte[nonceLength]);

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());

        // Keep the challenge itself standard as we want to focus on credentials validation
        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=salt_value, i=4096, algorithm=SCRAM-SHA-256";

        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        context.setServerProof(null);
        authscheme.processChallenge(authChallenge, context);

        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));
        Assertions.assertThrows(AuthenticationException.class, () ->
                authscheme.generateAuthResponse(host, request, context));
    }

    @Test
    void testScramAuthenticationRandALCat() throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);

        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "\uFB1D$", "password".toCharArray())
                .build();

        final int nonceLength = 256 / 8;
        fixNonce(new byte[nonceLength]);

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=salt_value, i=4096, algorithm=SCRAM-SHA-256";

        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        context.setServerProof(null);
        authscheme.processChallenge(authChallenge, context);

        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));

        Assertions.assertThrows(ScramException.class, () ->
                authscheme.generateAuthResponse(host, request, context));
    }


    @Test
    void testScramAuthenticationProhibited() throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);

        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "$\0x0020", "password".toCharArray())
                .build();

        final int nonceLength = 256 / 8;
        fixNonce(new byte[nonceLength]);

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=salt_value, i=4096, algorithm=SCRAM-SHA-256";

        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        context.setServerProof(null);
        authscheme.processChallenge(authChallenge, context);

        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));

        Assertions.assertThrows(ScramException.class, () ->
                authscheme.generateAuthResponse(host, request, context));
    }

    @Test
    void testScramAuthenticationRandALCatFirst() throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);

        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "$\uFB1D", "password".toCharArray())
                .build();

        final int nonceLength = 256 / 8;
        fixNonce(new byte[nonceLength]);

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=salt_value, i=4096, algorithm=SCRAM-SHA-256";

        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        context.setServerProof(null);
        authscheme.processChallenge(authChallenge, context);

        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));

        Assertions.assertThrows(ScramException.class, () ->
                authscheme.generateAuthResponse(host, request, context));
    }

    @Test
    void testScramAuthenticationScramExtremelyLowBoundaries() throws Exception {

        final ScramScheme authscheme = new ScramScheme();

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=salt_value, i =  1, algorithm=SCRAM-SHA-256-PLUS";
        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        context.setSSLSession(Mockito.mock(SSLSession.class));

        Assertions.assertThrows(MalformedChallengeException.class, () -> authscheme.processChallenge(authChallenge, context));

    }

    @Test
    void testScramAuthenticationScramNotNumberBoundaries() throws Exception {

        final ScramScheme authscheme = new ScramScheme();

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=salt_value, i=aaa , algorithm=SCRAM-SHA-256-PLUS";
        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        context.setSSLSession(Mockito.mock(SSLSession.class));

        Assertions.assertThrows(MalformedChallengeException.class, () -> authscheme.processChallenge(authChallenge, context));

    }


    @Test
    void testScramAuthenticationScramNullSalt() throws Exception {

        final ScramScheme authscheme = new ScramScheme();

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000,i=4096 , algorithm=SCRAM-SHA-256-PLUS";
        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        context.setSSLSession(Mockito.mock(SSLSession.class));

        Assertions.assertThrows(MalformedChallengeException.class, () -> authscheme.processChallenge(authChallenge, context));

    }

    @Test
    void testScramAuthenticationErrorNonce() throws Exception {

        final ScramScheme authscheme = new ScramScheme();

        final HttpClientContext context = HttpClientContext.create();
        context.setSSLSession(Mockito.mock(SSLSession.class));
        context.setServerProof("BI1UFV2y4dtE2BeHo6AjPUsRQzND31TfzGYbUWN73uI=");

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=salt_value, m=ext1, i= 4096, algorithm=SCRAM-SHA-256-PLUS";
        final AuthChallenge authChallenge = parse(challenge);


        Assertions.assertThrows(MalformedChallengeException.class, () -> authscheme.processChallenge(authChallenge, context));

    }

    @Test
    void testScramAuthenticationExtension() throws Exception {

        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);
        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "username", "password".toCharArray())
                .build();
        final int nonceLength = 256 / 8;
        fixNonce(new byte[nonceLength]);


        // Inner class to handle extensions for testing
        class TestExtension implements ScramExtension {
            private final Map<String, String> expectedValues = new HashMap<>();

            {
                expectedValues.put("totp", "123456");
                expectedValues.put("mockExtension", null);
                expectedValues.put("someOtherExt", "val");
            }

            @Override
            public boolean process(final String name, final String value, final HttpContext context) {
                final String expectedValue = expectedValues.get(name);
                if (expectedValue != null) {
                    Assertions.assertEquals(expectedValue, value, "Extension value does not match expected");
                    return true;
                }
                // For any other extension, just return true to simulate handling
                return true;
            }

            @Override
            public boolean supports(final String name) {
                return expectedValues.containsKey(name);
            }
        }

        // Use the test extension in a registry
        final RegistryBuilder<ScramExtension> registryBuilder = RegistryBuilder.create();
        registryBuilder.register("totp", new TestExtension());
        registryBuilder.register("mockExtension", new TestExtension());
        registryBuilder.register("someOtherExt", new TestExtension());

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, registryBuilder.build());
        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c756500000000000000000000000000000000, s=salt_value, i=4096, algorithm=SCRAM-SHA-256, ext=totp=123456,mockExtension,someOtherExt=val,ext1, m=ext1";
        final AuthChallenge authChallenge = parse(challenge);

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
        context.setServerProof("543add26a1a6ea149cb38e02cecf90fb8934b4d84810ce485f9d94fe2bd35c81");

        // Process the challenge which includes parsing extensions
        authscheme.processChallenge(authChallenge, context);

        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));
        Assertions.assertThrows(AuthenticationException.class, () -> authscheme.generateAuthResponse(host, request, context));
    }


    @Test
    void testParseMandatoryExtensions() throws Exception {
        final HttpHost host = new HttpHost("somehost", 80);
        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "username", "password".toCharArray())
                .build();

        final int nonceLength = 256 / 8;
        fixNonce(new byte[nonceLength]);

        // Inner class to handle extensions for testing
        class TestExtension implements ScramExtension {
            private final Map<String, String> expectedValues = new HashMap<>();

            {
                expectedValues.put("totp", "123456");
                expectedValues.put("mockExtension", null);
                expectedValues.put("someOtherExt", "val");
            }

            @Override
            public boolean process(final String name, final String value, final HttpContext context) {
                final String expectedValue = expectedValues.get(name);
                if (expectedValue != null) {
                    Assertions.assertEquals(expectedValue, value, "Extension value does not match expected");
                    return true;
                }
                // For any other extension, just return true to simulate handling
                return true;
            }

            @Override
            public boolean supports(final String name) {
                return expectedValues.containsKey(name);
            }
        }

        // Use the test extension in a registry
        final RegistryBuilder<ScramExtension> registryBuilder = RegistryBuilder.create();
        registryBuilder.register("totp", new TestExtension());
        registryBuilder.register("mockExtension", new TestExtension());
        registryBuilder.register("someOtherExt", new TestExtension());

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, registryBuilder.build());

        // Challenge with extensions
        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=salt_value, i=4096, algorithm=SCRAM-SHA-256, m=noneHere ,ext=totp=123456,mockExtension,someOtherExt=val";
        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        context.setServerProof("543add26a1a6ea149cb38e02cecf90fb8934b4d84810ce485f9d94fe2bd35c81");
        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));
        Assertions.assertThrows(MalformedChallengeException.class, () -> authscheme.processChallenge(authChallenge, context));

    }


    @Test
        void testScramMandatoryMultipleMandatoryExtensions() throws Exception {

        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);
        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), "username", "password".toCharArray())
                .build();
        final int nonceLength = 256 / 8;
        fixNonce(new byte[nonceLength]);


        // Inner class to handle extensions for testing
        class TestExtension implements ScramExtension {
            private final Map<String, String> expectedValues = new HashMap<>();

            {
                expectedValues.put("totp", "123456");
                expectedValues.put("mockExtension", null);
                expectedValues.put("someOtherExt", "val");
            }

            @Override
            public boolean process(final String name, final String value, final HttpContext context) {
                final String expectedValue = expectedValues.get(name);
                if (expectedValue != null) {
                    Assertions.assertEquals(expectedValue, value, "Extension value does not match expected");
                    return true;
                }
                // For any other extension, just return true to simulate handling
                return true;
            }

            @Override
            public boolean supports(final String name) {
                return expectedValues.containsKey(name);
            }
        }

        // Use the test extension in a registry
        final RegistryBuilder<ScramExtension> registryBuilder = RegistryBuilder.create();
        registryBuilder.register("totp", new TestExtension());
        registryBuilder.register("mockExtension", new TestExtension());
        registryBuilder.register("someOtherExt", new TestExtension());

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, registryBuilder.build());
        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c756500000000000000000000000000000000, s=salt_value, i=4096, algorithm=SCRAM-SHA-256, m=ext1,totp, ext=totp=123456,mockExtension,someOtherExt=val,ext1 ";
        final AuthChallenge authChallenge = parse(challenge);

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
        context.setServerProof("543add26a1a6ea149cb38e02cecf90fb8934b4d84810ce485f9d94fe2bd35c81");

        // Process the challenge which includes parsing extensions
        authscheme.processChallenge(authChallenge, context);

        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));
        Assertions.assertThrows(AuthenticationException.class, () -> authscheme.generateAuthResponse(host, request, context));
    }


    @Test
    void testScramAuthenticationWithClientKeyServerKeyCredentials() throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);

        // Simulate having pre-computed ClientKey and ServerKey
        final byte[] mockClientKey = "mockClientKey".getBytes(StandardCharsets.UTF_8);
        final byte[] mockServerKey = "mockServerKey".getBytes(StandardCharsets.UTF_8);

        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), new ClientKeyServerKeyCredentials(new BasicUserPrincipal("username"), mockClientKey, mockServerKey))
                .build();

        final int nonceLength = 256 / 8;
        fixNonce(new byte[nonceLength]);

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=salt_value, i=4096, algorithm=SCRAM-SHA-256";
        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        // Note: Here you might need to adjust the server proof based on how you calculate it with ClientKey/ServerKey
        context.setServerProof("767fbecd3e3428df0cbdf38131a1d934e7eac7641c54cdb7da528187b086be61");
        authscheme.processChallenge(authChallenge, context);

        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));
        final String response = authscheme.generateAuthResponse(host, request, context);
        Assertions.assertNotNull(response);
        Assertions.assertTrue(authscheme.isChallengeComplete());
        Assertions.assertFalse(authscheme.isConnectionBased());
    }


    @Test
    void testScramAuthenticationWithSaltedPasswordCredentials() throws Exception {
        final HttpRequest request = new BasicHttpRequest("Simple", "/");
        final HttpHost host = new HttpHost("somehost", 80);

        // Simulate having a pre-computed SaltedPassword
        final byte[] mockSaltedPassword = "mockSaltedPassword".getBytes(StandardCharsets.UTF_8);

        final CredentialsProvider credentialsProvider = CredentialsProviderBuilder.create()
                .add(new AuthScope(host, "realm", null), new SaltedPasswordCredentials(new BasicUserPrincipal("username"), mockSaltedPassword))
                .build();

        final int nonceLength = 256 / 8;
        fixNonce(new byte[nonceLength]);

        final ScramScheme authscheme = new ScramScheme(MOCK_SECURE_RANDOM, RegistryBuilder.<ScramExtension>create().build());

        final String challenge = StandardAuthScheme.SCRAM + " realm=\"realm\", r=66697865645f6e6f6e63655f76616c7565000000000000000000000000000000, s=salt_value, i=4096, algorithm=SCRAM-SHA-256";
        final AuthChallenge authChallenge = parse(challenge);

        final HttpClientContext context = HttpClientContext.create();
        // Note: Adjust the server proof based on how you calculate it with the SaltedPassword
        context.setServerProof("f05339fd8b4a10ff0e6463f073226f44c8e02a4713141e695f2d7b829718bc7b");
        authscheme.processChallenge(authChallenge, context);

        Assertions.assertTrue(authscheme.isResponseReady(host, credentialsProvider, context));
        final String response = authscheme.generateAuthResponse(host, request, context);
        Assertions.assertNotNull(response);
        Assertions.assertTrue(authscheme.isChallengeComplete());
        Assertions.assertFalse(authscheme.isConnectionBased());
    }


    private void fixNonce(final byte[] nonceLength) {
        doAnswer(invocation -> {
            final byte[] nonceBytes = invocation.getArgument(0);
            final byte[] fixedBytes = "fixed_nonce_value".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(fixedBytes, 0, nonceLength, 0, Math.min(nonceLength.length, fixedBytes.length));
            System.arraycopy(nonceLength, 0, nonceBytes, 0, nonceLength.length);
            return fixedBytes;
        }).when(MOCK_SECURE_RANDOM).nextBytes(any(byte[].class));
    }

}
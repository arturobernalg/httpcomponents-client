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

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import org.apache.hc.client5.http.auth.AuthChallenge;
import org.apache.hc.client5.http.auth.AuthScheme;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.AuthenticationException;
import org.apache.hc.client5.http.auth.ClientKeyServerKeyCredentials;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.CredentialsProvider;
import org.apache.hc.client5.http.auth.MalformedChallengeException;
import org.apache.hc.client5.http.auth.SaltedPasswordCredentials;
import org.apache.hc.client5.http.auth.StandardAuthScheme;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.utils.Base64;
import org.apache.hc.client5.http.utils.ByteArrayBuilder;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.CharArrayBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>SCRAM (Salted Challenge Response Authentication Mechanism) is a secure authentication scheme designed for
 * robust password-based authentication. This implementation supports various SCRAM mechanisms such as SCRAM-SHA-1,
 * SCRAM-SHA-256, SCRAM-SHA-512, and their respective channel-binding variants (e.g., SCRAM-SHA-256-PLUS).</p>
 *
 * <p>This class provides an implementation of the {@link AuthScheme} interface, enabling the generation of client
 * authentication responses to server-provided challenges. It ensures secure hashing, HMAC-based integrity checks,
 * and optional channel binding for enhanced protection against attacks like MitM (Man-in-the-Middle).</p>
 *
 * <h2>Usage</h2>
 * <p>Instances of {@code ScramScheme} generate client authentication responses, manage the state of the SCRAM
 * authentication exchange, and validate server responses. Channel binding is supported for mechanisms like
 * {@code tls-unique} or {@code tls-server-end-point} when applicable.</p>
 *
 * <h2>Extensions</h2>
 * <p>SCRAM extensions provide additional flexibility and functionality during authentication. Extensions are
 * communicated through key-value pairs in the challenge and processed using registered {@link ScramExtension}
 * implementations. Extensions can add features like one-time passwords or other custom mechanisms.</p>
 * <p>Examples of extensions:</p>
 * <ul>
 *   <li>{@code ext=totp=123456} - A time-based one-time password.</li>
 *   <li>{@code ext=mockExtension} - A custom extension without a value.</li>
 *   <li>{@code ext=someOtherExt=val} - A custom extension with a specific value.</li>
 * </ul>
 *
 * <p>To handle extensions, implement the {@link ScramExtension} interface and register them using the
 * {@link RegistryBuilder}.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is not thread-safe. Each instance of {@code ScramScheme} should be used in a single thread of execution.
 * For concurrent usage, create separate instances per thread.</p>
 *
 * <h2>Supported Mechanisms</h2>
 * <p>Supported SCRAM mechanisms include:
 * <ul>
 *   <li>SCRAM-SHA-1</li>
 *   <li>SCRAM-SHA-256</li>
 *   <li>SCRAM-SHA-512</li>
 *   <li>SCRAM-SHA-256-PLUS</li>
 *   <li>SCRAM-SHA-512-PLUS</li>
 * </ul>
 * These mechanisms provide secure password-based authentication with salted hashing and optional channel-binding
 * for enhanced security.</p>
 *
 * <h2>Important Considerations</h2>
 * <p>The SCRAM scheme uses iterative hashing and HMAC-based computations to secure credentials, making it highly
 * resistant to attacks. Implementors should ensure proper UTF-8 encoding and normalization of usernames and passwords
 * to prevent issues with differing Unicode representations.</p>
 *
 * @since 5.5
 */

public class ScramScheme implements AuthScheme, Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(ScramScheme.class);

    /**
     * Serialization version UID for this class.
     */
    private static final long serialVersionUID = 2326371464523991412L;

    /**
     * Map to store the SCRAM challenge parameters.
     */
    private final Map<String, String> paramMap;

    /**
     * Client-generated nonce used in the SCRAM authentication.
     */
    private String clientNonce;

    /**
     * Salt value for the user's credentials, obtained from the server challenge.
     */
    private String salt;

    /**
     * The iteration count used in the PBKDF2 calculation, as specified in the server challenge.
     */
    private int iterationCount;

    /**
     * The stored key derived from the client key, used to validate the server.
     */
    private byte[] storedKey;

    /**
     * Indicates whether the SCRAM challenge/response exchange is complete.
     */
    private boolean complete = false;

    /**
     * The SCRAM mechanism being used (e.g., SCRAM-SHA-256, SCRAM-SHA-256-PLUS).
     */
    private ScramMechanism mechanism;

    /**
     * Secure random generator used for generating the client nonce.
     */
    private final SecureRandom secureRandom;

    /**
     * User's credentials (username and password) used for SCRAM authentication.
     */
    private Credentials credentials;

    /**
     * Buffer for storing the bare part of the client-first-message.
     */
    private transient ByteArrayBuilder clientFirstMessage;

    /**
     * Buffer for storing the server-first-message.
     */
    private final ByteArrayBuilder serverFirstMessage;

    /**
     * Holds the current state of the SCRAM authentication process.
     * <p>
     * This field is used to track which phase of the SCRAM authentication
     * process the client is currently in. Managing the state helps in ensuring
     * the correct sequence of messages, avoiding potential security issues
     * and maintaining consistency throughout the SCRAM exchange.
     * <p>
     * The use of state management mitigates risks associated with incorrect or
     * mixed-up sequences, ensuring the protocol proceeds according to SCRAM's
     * specification.
     */
    private ScramState state;

    private final Lookup<ScramExtension> extensionRegistry;

    List<NameValuePair> extensions = new ArrayList<>();
    List<NameValuePair> mandatoryExtensions = new ArrayList<>();
    private static final Set<String> RESERVED_KEYWORDS;

    static {
        final Set<String> keywords = new HashSet<>();
        keywords.add("realm");
        keywords.add("algorithm");
        keywords.add("nonce");
        keywords.add("ext");
        keywords.add("i");
        keywords.add("s");
        keywords.add("c");
        keywords.add("v");
        keywords.add("r");
        keywords.add("p");
        RESERVED_KEYWORDS = Collections.unmodifiableSet(keywords);
    }


    /**
     * Internal constructor that allows the use of a custom {@link SecureRandom} instance.
     * This constructor is intended for testing purposes only and should not be used in production.
     *
     * @param secureRandom The {@link SecureRandom} instance used to generate the client nonce.
     *                     This constructor is provided for testing purposes and allows deterministic
     *                     generation of nonces. Using a fixed source of randomness may compromise security in
     *                     production systems. Therefore, this constructor should not be used in any production code.
     */
    @Internal
    ScramScheme(final SecureRandom secureRandom, final Lookup<ScramExtension> extensionRegistry) {
        this.extensionRegistry = extensionRegistry;
        this.paramMap = new HashMap<>();
        this.secureRandom = secureRandom;
        this.state = ScramState.INITIAL;
        serverFirstMessage = new ByteArrayBuilder(128);
    }

    /**
     * Default constructor that initializes the SCRAM scheme using a secure, system-provided
     * {@link SecureRandom} instance. This constructor should be used for production use cases.
     * <p>
     * The {@link SecureRandom} instance used here ensures that all generated nonces are
     * truly random and secure, making the SCRAM authentication process robust against
     * replay attacks and ensuring confidentiality.
     * </p>
     */
    public ScramScheme(final Lookup<ScramExtension> extensionRegistry) {
        this(new SecureRandom(), extensionRegistry);
    }

    /**
     * Default constructor that initializes the SCRAM scheme using a secure, system-provided
     * {@link SecureRandom} instance. This constructor should be used for production use cases.
     * <p>
     * The {@link SecureRandom} instance used here ensures that all generated nonces are
     * truly random and secure, making the SCRAM authentication process robust against
     * replay attacks and ensuring confidentiality.
     * </p>
     */
    public ScramScheme() {
        this(new SecureRandom(), RegistryBuilder.<ScramExtension>create().build());
    }


    @Override
    public void processChallenge(final AuthChallenge authChallenge, final HttpContext context) throws MalformedChallengeException {
        if (state != ScramState.INITIAL && state != ScramState.CLIENT_FIRST_SENT) {
            this.state = ScramState.FAILED;
            throw new MalformedChallengeException("SCRAM state out of sequence: expected CLIENT_FIRST_SENT but was " + state);
        }

        this.paramMap.clear();

        final List<NameValuePair> params = authChallenge.getParams();

        if (params != null) {
            for (int i = 0; i < params.size(); i++) {
                final NameValuePair param = params.get(i);
                final String paramName = param.getName().toLowerCase(Locale.ROOT);
                final String paramValue = param.getValue().toLowerCase(Locale.ROOT);

                if ("m".equals(paramName)) {
                    // Parse mandatory extensions
                    mandatoryExtensions.add(new BasicNameValuePair(param.getValue(), null));

                    // Continue iterating until next reserved keyword
                    for (int j = i + 1; j < params.size(); j++) {
                        final NameValuePair nextParam = params.get(j);
                        final String nextName = nextParam.getName().toLowerCase(Locale.ROOT);

                        if (RESERVED_KEYWORDS.contains(nextName)) {
                            break;
                        }

                        mandatoryExtensions.add(new BasicNameValuePair(nextParam.getName(), null));
                        i = j;  // Advance outer loop to skip already parsed entries
                    }
                } else if ("ext".equals(paramName)) {
                    // Parse optional extensions
                    final String[] extList = param.getValue().split(",");
                    for (final String ext : extList) {
                        final String[] keyValue = ext.split("=", 2);
                        if (keyValue.length == 2) {
                            extensions.add(new BasicNameValuePair(keyValue[0].trim(), keyValue[1].trim()));
                        } else {
                            extensions.add(new BasicNameValuePair(keyValue[0].trim(), null));
                        }
                    }

                    // Continue iterating for more extensions until a reserved keyword is found
                    for (int j = i + 1; j < params.size(); j++) {
                        final NameValuePair nextParam = params.get(j);
                        final String nextName = nextParam.getName().toLowerCase(Locale.ROOT);

                        if (RESERVED_KEYWORDS.contains(nextName)) {
                            break;
                        }
                        extensions.add(nextParam);
                        i = j;
                    }
                } else {
                    this.paramMap.put(paramName, paramValue);
                }
            }
        }

        // Validate mandatory extensions
        if (!mandatoryExtensions.isEmpty()) {
            for (final NameValuePair mandatory : mandatoryExtensions) {
                final String mandatoryName = mandatory.getName();
                if (extensions.stream().noneMatch(ext -> ext.getName().equalsIgnoreCase(mandatoryName))) {
                    this.state = ScramState.FAILED;
                    throw new MalformedChallengeException("Mandatory extension '" + mandatoryName + "' not supported.");
                }
            }
        }

        if (this.paramMap.isEmpty()) {
            throw new MalformedChallengeException("Missing SCRAM auth parameters");
        }

        if (!this.paramMap.containsKey("r") || !this.paramMap.containsKey("s") || !this.paramMap.containsKey("i")) {
            this.state = ScramState.FAILED;
            throw new MalformedChallengeException("Missing mandatory SCRAM auth parameters: 'r', 's', or 'i'");
        }

        final String algorithm = this.paramMap.get("algorithm");
        mechanism = ScramMechanism.fromString(algorithm);
        salt = paramMap.get("s");
        final String serverNonce = paramMap.get("r");

        try {
            iterationCount = Integer.parseInt(paramMap.get("i"));
            if (iterationCount < mechanism.getIterationCount()) {
                this.state = ScramState.FAILED;
                throw new MalformedChallengeException("Iteration count too low.");
            }
        } catch (final NumberFormatException e) {
            this.state = ScramState.FAILED;
            throw new MalformedChallengeException("Invalid iteration count format in SCRAM challenge");
        }

        generateClientNonce(mechanism);

        if (salt == null || !validateServerNonce(serverNonce, clientNonce)) {
            this.state = ScramState.FAILED;
            throw new MalformedChallengeException("Invalid SCRAM challenge parameters: Invalid salt or server nonce");
        }

        serverFirstMessage.append("r=").append(serverNonce).append(",s=").append(salt).append(",i=").append(String.valueOf(iterationCount));

        this.state = ScramState.SERVER_FIRST_RECEIVED;
        this.complete = true;
    }


    @Override
    public boolean isChallengeComplete() {
        return this.complete;
    }

    @Override
    public String getRealm() {
        return this.paramMap.get("realm");
    }

    @Override
    public boolean isResponseReady(final HttpHost host, final CredentialsProvider credentialsProvider, final HttpContext context) throws AuthenticationException {
        Args.notNull(host, "Auth host");
        Args.notNull(credentialsProvider, "CredentialsProvider");

        final HttpClientContext clientContext = HttpClientContext.cast(context);
        final AuthScope authScope = new AuthScope(host, getRealm(), getName());
        final Credentials credentials = credentialsProvider.getCredentials(authScope, clientContext);

        if (credentials != null) {
            if (credentials instanceof UsernamePasswordCredentials) {
                this.credentials = credentials;
                return true;
            } else if (credentials instanceof ClientKeyServerKeyCredentials) {
                this.credentials = credentials;
                return true;
            } else if (credentials instanceof SaltedPasswordCredentials) {
                this.credentials = credentials;
                return true;
            }
        }

        // No credentials found
        this.credentials = null;
        return false;
    }

    @Override
    public Principal getPrincipal() {
        return this.credentials != null ? this.credentials.getUserPrincipal() : null;
    }

    @Override
    public String getName() {
        return StandardAuthScheme.SCRAM;
    }


    @Override
    public boolean isConnectionBased() {
        return false;
    }

    @Override
    public String generateAuthResponse(final HttpHost host, final HttpRequest request, final HttpContext context) throws AuthenticationException {
        if (state != ScramState.SERVER_FIRST_RECEIVED) {
            this.state = ScramState.FAILED;
            throw new AuthenticationException("SCRAM state out of sequence: expected SERVER_FIRST_RECEIVED but was " + state);
        }

        if (credentials == null) {
            throw new AuthenticationException("User credentials not set");
        }

        final String username;
        final byte[] clientKey;
        final byte[] saltedPassword;

        if (credentials instanceof UsernamePasswordCredentials) {
            // Traditional username and password
            final UsernamePasswordCredentials upCredentials = (UsernamePasswordCredentials) credentials;
            try {
                username = SaslPrep.INSTANCE.prepAsQueryString(upCredentials.getUserName());
                final String password = SaslPrep.INSTANCE.prepAsStoredString(new String(upCredentials.getUserPassword()));
                saltedPassword = calculateHi(password.getBytes(StandardCharsets.UTF_8), Base64.decodeBase64(salt), iterationCount, mechanism.getHmacFunction());
                clientKey = hmac(saltedPassword, "Client Key", mechanism.getHmacFunction());
                final MessageDigest digester = createMessageDigest(mechanism.getHashFunction());
                storedKey = digester.digest(clientKey);
            } catch (final IllegalArgumentException e) {
                this.state = ScramState.FAILED;
                throw new AuthenticationException("Error preparing credentials: " + e.getMessage(), e);
            }
        } else if (credentials instanceof ClientKeyServerKeyCredentials) {
            final ClientKeyServerKeyCredentials csCredentials = (ClientKeyServerKeyCredentials) credentials;
            username = csCredentials.getUserPrincipal().getName();
            clientKey = csCredentials.getClientKey();
            storedKey = computeStoredKeyFromClientKey(clientKey);
        } else if (credentials instanceof SaltedPasswordCredentials) {
            final SaltedPasswordCredentials spCredentials = (SaltedPasswordCredentials) credentials;
            username = spCredentials.getUserPrincipal().getName();
            saltedPassword = spCredentials.getSaltedPassword();
            clientKey = hmac(saltedPassword, "Client Key", mechanism.getHmacFunction());
            final MessageDigest digester = createMessageDigest(mechanism.getHashFunction());
            storedKey = digester.digest(clientKey);
        } else {
            this.state = ScramState.FAILED;
            throw new AuthenticationException("Unsupported credential type for SCRAM: " + credentials.getClass().getName());
        }

        if (clientFirstMessage == null) {
            clientFirstMessage = new ByteArrayBuilder(128);
        } else {
            clientFirstMessage.reset();
        }

        // Determine GS2 header based on channel binding availability
        final String gs2Header;
        final HttpClientContext clientContext = HttpClientContext.cast(context);
        final SSLSession sslSession = clientContext.getSSLSession();

        if (mechanism.isPlus()) {
            if (sslSession != null) {
                final String channelBindingType = clientContext.getChannelBindingType();
                if (channelBindingType != null) {
                    // If channel binding is actually used, set the correct type
                    gs2Header = "p=" + channelBindingType + ",,";
                } else {
                    // If channel binding is supported but not used (no type set)
                    gs2Header = "y,,";
                }
            } else {
                // If no TLS session but mechanism is PLUS, fallback to 'y,,' indicating support without binding
                //gs2Header = "y,,";
                throw new AuthenticationException("Channel binding required but no TLS session available");
            }
        } else {
            // Non-PLUS mechanism, no channel binding
            gs2Header = "n,,";
        }

        clientFirstMessage.append(gs2Header);
        clientFirstMessage.append("n=").append(username).append(",r=").append(clientNonce);

        final byte[] clientFirstMessageBytes = clientFirstMessage.toByteArray();
        if (clientFirstMessageBytes.length == 0) {
            this.state = ScramState.FAILED;
            throw new AuthenticationException("Client-first message is empty, GS2 header missing.");
        }

        final byte gs2HeaderByte = clientFirstMessageBytes[0];
        if (gs2HeaderByte != 'n' && gs2HeaderByte != 'y' && gs2HeaderByte != 'p') {
            this.state = ScramState.FAILED;
            throw new AuthenticationException("Invalid GS2 header in client-first message: must start with 'y', 'n', or 'p'");
        }

        // Construct c= (Base64-encoded GS2 header + channel binding data)
        final String channelBinding;
        if (mechanism.isPlus()) {
            if (sslSession != null) {
                final String channelBindingType = clientContext.getChannelBindingType();
                if (channelBindingType == null) {
                    this.state = ScramState.FAILED;
                    throw new AuthenticationException("Channel binding type not set in context for SCRAM-PLUS mechanism");
                }

                switch (channelBindingType) {
                    case "tls-server-end-point":
                        final byte[] tlsServerEndPoint;
                        try {
                            tlsServerEndPoint = computeTlsServerEndPoint(sslSession);
                        } catch (final CertificateEncodingException | SSLPeerUnverifiedException e) {
                            this.state = ScramState.FAILED;
                            throw new AuthenticationException("Failed to compute tls-server-end-point: " + e.getMessage(), e);
                        }
                        channelBinding = Base64.encodeBase64String((gs2Header + new String(tlsServerEndPoint, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
                        break;

                    case "tls-unique":
                        final byte[] tlsUnique = clientContext.getTlsUnique();
                        if (tlsUnique == null) {
                            this.state = ScramState.FAILED;
                            throw new AuthenticationException("tlsUnique value not available in context for SCRAM-PLUS mechanism");
                        }
                        channelBinding = Base64.encodeBase64String((gs2Header + new String(tlsUnique, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
                        break;

                    default:
                        this.state = ScramState.FAILED;
                        throw new AuthenticationException("Unsupported channel binding type: " + channelBindingType);
                }
            } else {
                // If no TLS session but mechanism was originally PLUS, we use 'y,,' for the header
                channelBinding = Base64.encodeBase64String("y,,".getBytes(StandardCharsets.UTF_8));
            }
        } else {
            channelBinding = Base64.encodeBase64String("n,,".getBytes(StandardCharsets.UTF_8));
        }

        // Build authMessage
        final String serverNonce = paramMap.get("r");
        final String authMessage = new String(clientFirstMessageBytes) + "," + "r=" + serverNonce + ",s=" + paramMap.get("s") + ",i=" + paramMap.get("i") + "," + "c=" + channelBinding;

        // Compute client proof
        final byte[] clientSignature = hmac(storedKey, authMessage, mechanism.getHmacFunction());
        final byte[] clientProof = xor(clientKey, clientSignature);

        // Construct final response
        final CharArrayBuffer responseBuffer = new CharArrayBuffer(128);
        responseBuffer.append(mechanism.getSchemeName()); // Add mechanism name (e.g., SCRAM-SHA-256)
        responseBuffer.append(" ");
        responseBuffer.append("c=");
        responseBuffer.append(channelBinding); // Add c=
        responseBuffer.append(",r=");
        responseBuffer.append(serverNonce); // Add r=
        responseBuffer.append(",p=");
        responseBuffer.append(Base64.encodeBase64String(clientProof)); // Add p=

        final String serverProof = HttpClientContext.cast(context).getServerProof();
        if (serverProof == null || !serverProof.matches("[0-9a-fA-F]+")) {
            throw new AuthenticationException("Server proof not available or invalid in context for validation");
        }

        if (this.extensions != null) {
            for (final NameValuePair ext : this.extensions) {
                final String extName = ext.getName();
                final String extValue = ext.getValue();
                if (!handleExtension(extName, extValue, context)) {
                    if (LOG.isWarnEnabled()) {
                        LOG.warn("Unhandled extension: {}", extName);
                    }
                }
            }
        }


        validateServerProof(serverProof, authMessage, mechanism);
        this.state = ScramState.CLIENT_PROOF_SENT;

        return new String(responseBuffer.toCharArray());
    }

    /**
     * Handles an extension during the SCRAM authentication process. This method performs the following:
     *
     * <p>Extensions are additional attributes sent in the SCRAM challenge that may enhance or modify the authentication process.
     * Extensions can have a name-value pair format (e.g., "totp=123456") or be name-only (e.g., "mockExtension").</p>
     *
     * @param name    The name of the extension to be processed. Must not be {@code null}.
     * @param value   The value of the extension, or {@code null} if the extension does not have a value.
     * @param context The {@link HttpContext} in which the extension is being processed. Must not be {@code null}.
     * @return {@code true} if the extension was successfully processed, {@code false} otherwise.
     * @throws AuthenticationException if there is an error while processing the extension or a mandatory extension is not supported.
     */
    private boolean handleExtension(final String name, final String value, final HttpContext context) throws AuthenticationException {
        final ScramExtension extension = extensionRegistry.lookup(name.toLowerCase(Locale.ROOT));
        if (extension != null) {
            if (!extension.process(name, value, context)) {
                if (LOG.isWarnEnabled()) {
                    LOG.warn("SCRAM extension '{}' failed processing", name);
                }
                return false;
            }
            return true;
        }

        if (LOG.isWarnEnabled()) {
            LOG.warn("Unsupported SCRAM extension: {}", name);
        }
        return false;
    }


    /**
     * Validates the server proof received from the server during the authentication process. This step
     * verifies that the server is in possession of the correct credentials and ensures the integrity of the SCRAM exchange.
     *
     * @param serverProof The server proof value received from the server during authentication. Must not be {@code null}.
     * @param authMessage The authentication message constructed from the client and server messages.
     * @param mechanism   The SCRAM mechanism being used, which determines the hash and HMAC functions.
     * @throws AuthenticationException if the server proof validation fails, meaning the server's proof cannot be verified.
     */
    private void validateServerProof(final String serverProof, final String authMessage, final ScramMechanism mechanism) throws AuthenticationException {

        final byte[] expectedServerSignature;
        try {
            expectedServerSignature = hmac(storedKey, authMessage, mechanism.getHmacFunction());
        } catch (final Exception e) {
            this.state = ScramState.FAILED;
            throw new AuthenticationException("Failed to compute expected server signature: " + e.getMessage(), e);
        }

        final byte[] serverProofBytes = hexStringToByteArray(serverProof);

        if (!signatureAreEquals(serverProofBytes, expectedServerSignature)) {
            this.state = ScramState.FAILED;
            if (LOG.isDebugEnabled()) {
                LOG.debug("Server proof validation failed. The provided server signature does not match the expected signature. Expected: {}, Received: {}", Base64.encodeBase64String(expectedServerSignature), serverProof);
            }
            throw new AuthenticationException("Server proof validation failed. The provided server signature does not match the expected signature.");
        }

        this.state = ScramState.AUTHENTICATED;
    }

    /**
     * Compares two byte arrays for equality in a way that avoids timing attacks.
     * <p>
     * This method performs a constant-time comparison of the two byte arrays, meaning it
     * ensures that the comparison time does not depend on the content of the arrays.
     * This approach is particularly useful when comparing cryptographic signatures to
     * prevent attackers from gaining insight into the comparison process through timing.
     * </p>
     *
     * @param a the first byte array to compare, may be null
     * @param b the second byte array to compare, may be null
     * @return {@code true} if both byte arrays are non-null, of the same length, and contain
     * the same elements in the same order; {@code false} otherwise
     */
    private boolean signatureAreEquals(final byte[] a, final byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    private static byte[] hexStringToByteArray(final String hex) {
        final int length = hex.length();
        final byte[] data = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }


    /**
     * Generates a new client nonce using the provided {@link SecureRandom} instance.
     * The client nonce is used in the SCRAM authentication process to introduce entropy and prevent replay attacks.
     * <p>
     * The nonce is generated with a length based on the {@link ScramMechanism#getKeyLength()} value. This length is dynamically determined
     * based on the SCRAM mechanism being used, ensuring that it matches the requirements for the corresponding hash function.
     */
    private void generateClientNonce(final ScramMechanism mechanism) {
        final byte[] nonceBytes = new byte[mechanism.getKeyLength() / 8];
        secureRandom.nextBytes(nonceBytes);
        this.clientNonce = DigestScheme.formatHex(nonceBytes);
    }


    /**
     * Initializes a {@link Mac} instance for the given key and algorithm.
     *
     * @param key       The key used to initialize the {@link Mac} instance.
     * @param algorithm The algorithm name used for the {@link Mac} instance (e.g., "HmacSHA256").
     * @return An initialized {@link Mac} instance.
     * @throws UnsupportedDigestAlgorithmException if the given algorithm or key is not supported.
     * @since 5.5
     */
    private Mac initializeMac(final byte[] key, final String algorithm) {
        final Mac mac;
        try {
            mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key, algorithm));
        } catch (final NoSuchAlgorithmException e) {
            this.state = ScramState.FAILED;
            throw new UnsupportedDigestAlgorithmException("Unsupported algorithm in HTTP SCRAM authentication: " + algorithm);
        } catch (final InvalidKeyException e) {
            this.state = ScramState.FAILED;
            throw new UnsupportedDigestAlgorithmException("Invalid key used for SCRAM authentication: " + e.getMessage(), e);
        }
        return mac;
    }

    /**
     * Generates an HMAC value using the given key, message, and algorithm.
     *
     * @param key       The key used to compute the HMAC.
     * @param message   The message to be signed.
     * @param algorithm The HMAC algorithm name (e.g., "HmacSHA256").
     * @return A byte array representing the HMAC value.
     */
    private byte[] hmac(final byte[] key, final byte[] message, final String algorithm) {
        final Mac mac = initializeMac(key, algorithm);
        return mac.doFinal(message);
    }

    /**
     * Generates an HMAC value using the given key and message.
     *
     * @param key       The key used to compute the HMAC.
     * @param message   The message to be signed.
     * @param algorithm The HMAC algorithm name (e.g., "HmacSHA256").
     * @return A byte array representing the HMAC value.
     * @since 5.5
     */
    private byte[] hmac(final byte[] key, final String message, final String algorithm) {
        return hmac(key, message.getBytes(StandardCharsets.UTF_8), algorithm);
    }

    /**
     * Computes the iterative hash (Hi function) as described in RFC  for SCRAM authentication.
     * This method performs the PBKDF2-like iteration to generate a salted password.
     *
     * @param str           The input string to be hashed (e.g., the password).
     * @param salt          The salt value to use in hashing.
     * @param iterations    The number of iterations to perform.
     * @param hmacAlgorithm The HMAC algorithm to use (e.g., "HmacSHA256").
     */
    private byte[] calculateHi(final byte[] str, final byte[] salt, final int iterations, final String hmacAlgorithm) {
        final Mac mac = initializeMac(str, hmacAlgorithm);

        mac.update(salt);
        mac.update(ByteBuffer.allocate(4).putInt(1).array());
        byte[] u = mac.doFinal();

        byte[] result = Arrays.copyOf(u, u.length);

        for (int i = 1; i < iterations; i++) {
            mac.reset();
            mac.update(u);
            u = mac.doFinal();
            result = xor(result, u);
        }
        return result;
    }

    private byte[] computeStoredKeyFromClientKey(final byte[] clientKey) {
        final MessageDigest digester = createMessageDigest(mechanism.getHashFunction());
        return digester.digest(clientKey);
    }


    /**
     * Performs a byte-wise XOR operation on two byte arrays.
     *
     * @param a The first byte array.
     * @param b The second byte array.
     * @return A new byte array that is the result of XOR-ing the two input arrays.
     */
    private byte[] xor(final byte[] a, final byte[] b) {
        final byte[] result = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = (byte) (a[i] ^ b[i]);
        }
        return result;
    }

    /**
     * Normalizes a given signature algorithm name by converting it to a consistent format.
     * Specifically, it trims and converts the name to uppercase, and if it finds the substring "WITH",
     * it takes the part before "WITH". Furthermore, it ensures that SHA algorithms have the correct format.
     *
     * @param signatureAlgorithm The signature algorithm name to normalize, typically extracted from an X509 certificate.
     * @return A normalized algorithm name (e.g., "SHA-256").
     * @throws UnsupportedOperationException if the signature algorithm provided is not supported.
     */
    private String normalizeAlgorithmName(final String signatureAlgorithm) {
        String normalized = signatureAlgorithm.toUpperCase(Locale.ROOT);

        final int index = normalized.indexOf("WITH");
        if (index > 0) {
            normalized = normalized.substring(0, index).trim();
        }

        if (normalized.startsWith("SHA") && !normalized.startsWith("SHA-")) {
            if (normalized.length() > 3) {
                normalized = "SHA-" + normalized.substring(3);
            }
        }
        switch (normalized) {
            case "MD5":
                return "MD5";
            case "SHA-1":
            case "SHA1":
                return "SHA-1";
            case "SHA-256":
            case "SHA256":
                return "SHA-256";
            case "SHA-384":
            case "SHA384":
                return "SHA-384";
            case "SHA-512":
            case "SHA512":
                return "SHA-512";
            default:
                throw new UnsupportedOperationException("Unsupported signature algorithm: " + signatureAlgorithm);
        }
    }

    /**
     * Creates a {@link MessageDigest} instance for the given algorithm.
     *
     * @param alg The name of the algorithm for which to create the {@link MessageDigest} instance.
     * @return A {@link MessageDigest} instance for the specified algorithm.
     * @throws UnsupportedDigestAlgorithmException if the specified algorithm is not supported.
     */
    private MessageDigest createMessageDigest(final String alg) throws UnsupportedDigestAlgorithmException {
        try {
            return MessageDigest.getInstance(alg);
        } catch (final Exception e) {
            this.state = ScramState.FAILED;
            throw new UnsupportedDigestAlgorithmException("Unsupported algorithm in HTTP SCRAM authentication: " + alg);
        }
    }

    /**
     * Validate if the server nonce starts with the client nonce.
     * Designed for performance-critical contexts where every cycle counts.
     *
     * @param serverNonce the server-provided nonce
     * @param clientNonce the client-provided nonce
     * @return {@code true} if the serverNonce starts with the clientNonce, otherwise {@code false}
     */
    private boolean validateServerNonce(final String serverNonce, final String clientNonce) {
        if (serverNonce == null || clientNonce == null || serverNonce.length() < clientNonce.length()) {
            return false;
        }

        final char[] serverChars = serverNonce.toCharArray();
        final char[] clientChars = clientNonce.toCharArray();

        for (int i = 0; i < clientChars.length; i++) {
            if (serverChars[i] != clientChars[i]) {
                return false;
            }
        }

        return true;
    }


    /**
     * Computes the TLS server endpoint channel binding using the provided {@link SSLSession}.
     * This method extracts the server certificate from the SSL session, normalizes its signature algorithm,
     * and then creates a digest based on the hash function associated with the given SCRAM mechanism.
     *
     * @param sslSession The SSL session from which to obtain the server certificate.
     * @return A byte array representing the server's endpoint digest.
     * @throws CertificateEncodingException if an encoding issue occurs while accessing the server certificate.
     * @throws SSLPeerUnverifiedException   if the SSL peer cannot be verified.
     */
    private byte[] computeTlsServerEndPoint(final SSLSession sslSession) throws CertificateEncodingException, SSLPeerUnverifiedException {
        final X509Certificate serverCert = (X509Certificate) sslSession.getPeerCertificates()[0];
        final String normalizedAlgorithm = normalizeAlgorithmName(serverCert.getSigAlgName());
        final ScramMechanism mechanism = ScramMechanism.fromDigestAlgorithm(normalizedAlgorithm);
        final MessageDigest digester = createMessageDigest(mechanism.getHashFunction());
        if (digester == null) {
            return new byte[0];
        }
        return digester.digest(serverCert.getEncoded());
    }

    /**
     * Retrieves the client nonce used during SCRAM authentication.
     * The client nonce is a unique value generated to add entropy to the SCRAM authentication process,
     * preventing replay attacks.
     *
     * @return The client nonce as a {@link String}.
     */
    @Internal
    public String getClientNonce() {
        return clientNonce;
    }

    /**
     * Represents different SCRAM mechanisms that are supported.
     * This enumeration includes variants such as SCRAM-SHA-1, SCRAM-SHA-256, and their "-PLUS" extensions for channel binding.
     */
    private enum ScramMechanism {
        SCRAM_SHA_1("SCRAM-SHA-1", "SHA-1", "HmacSHA1", 160, 4096), SCRAM_SHA_1_PLUS("SCRAM-SHA-1-PLUS", "SHA-1", "HmacSHA1", 160, 4096), SCRAM_SHA_224("SCRAM-SHA-224", "SHA-224", "HmacSHA224", 224, 4096), SCRAM_SHA_224_PLUS("SCRAM-SHA-224-PLUS", "SHA-224", "HmacSHA224", 224, 4096), SCRAM_SHA_256("SCRAM-SHA-256", "SHA-256", "HmacSHA256", 256, 4096), SCRAM_SHA_256_PLUS("SCRAM-SHA-256-PLUS", "SHA-256", "HmacSHA256", 256, 4096), SCRAM_SHA_384("SCRAM-SHA-384", "SHA-384", "HmacSHA384", 384, 4096), SCRAM_SHA_384_PLUS("SCRAM-SHA-384-PLUS", "SHA-384", "HmacSHA384", 384, 4096), SCRAM_SHA_512("SCRAM-SHA-512", "SHA-512", "HmacSHA512", 512, 10000), SCRAM_SHA_512_PLUS("SCRAM-SHA-512-PLUS", "SHA-512", "HmacSHA512", 512, 10000);

        private final String schemeName;
        private final String hashFunction;
        private final String hmacFunction;
        private final int keyLength;
        private final int iterationCount;
        private final boolean channelBinding;

        // Cache maps for lookup
        private static final Map<String, ScramMechanism> SCHEME_NAME_CACHE = new HashMap<>();
        private static final Map<String, ScramMechanism> DIGEST_ALGORITHM_CACHE = new HashMap<>();

        // Static block to populate cache maps
        static {
            for (final ScramMechanism mechanism : ScramMechanism.values()) {
                SCHEME_NAME_CACHE.put(mechanism.schemeName.toLowerCase(Locale.ROOT), mechanism);
                DIGEST_ALGORITHM_CACHE.put(mechanism.hashFunction.toLowerCase(Locale.ROOT), mechanism);
            }
        }

        ScramMechanism(final String schemeName, final String hashFunction, final String hmacFunction, final int keyLength, final int iterationCount) {
            this.schemeName = schemeName;
            this.hashFunction = hashFunction;
            this.hmacFunction = hmacFunction;
            this.keyLength = keyLength;
            this.channelBinding = schemeName.endsWith("-PLUS");
            this.iterationCount = iterationCount;
        }

        /**
         * Retrieves the SCRAM mechanism scheme name.
         *
         * @return The name of the SCRAM mechanism (e.g., "SCRAM-SHA-256").
         */
        private String getSchemeName() {
            return schemeName;
        }

        /**
         * Retrieves the hash function associated with this SCRAM mechanism.
         *
         * @return The hash function name (e.g., "SHA-256").
         */
        private String getHashFunction() {
            return hashFunction;
        }

        /**
         * Retrieves the HMAC function associated with this SCRAM mechanism.
         *
         * @return The HMAC function name (e.g., "HmacSHA256").
         */
        private String getHmacFunction() {
            return hmacFunction;
        }

        /**
         * Retrieves the key length of the SCRAM mechanism.
         *
         * @return The key length in bits (e.g., 256).
         */
        private int getKeyLength() {
            return keyLength;
        }

        /**
         * Checks if the SCRAM mechanism supports channel binding.
         *
         * @return {@code true} if the mechanism supports channel binding; {@code false} otherwise.
         */
        private boolean isPlus() {
            return channelBinding;
        }

        /**
         * Retrieves the iteration count for the SCRAM mechanism.
         *
         * @return The iteration count (e.g., 4096 or 10000).
         */
        private int getIterationCount() {
            return iterationCount;
        }

        /**
         * Converts a scheme name into a corresponding {@link ScramMechanism} instance.
         *
         * @param schemeName The name of the SCRAM mechanism scheme (e.g., "SCRAM-SHA-256").
         * @return The corresponding {@link ScramMechanism} instance.
         * @throws UnsupportedDigestAlgorithmException if the provided scheme name is not supported.
         */
        private static ScramMechanism fromString(final String schemeName) {
            if (schemeName == null || schemeName.isEmpty()) {
                throw new UnsupportedDigestAlgorithmException("SCRAM mechanism name must not be null or empty");
            }
            final ScramMechanism mechanism = SCHEME_NAME_CACHE.get(schemeName.toLowerCase(Locale.ROOT));
            if (mechanism == null) {
                throw new UnsupportedDigestAlgorithmException("Unsupported algorithm: " + schemeName);
            }
            return mechanism;
        }

        /**
         * Converts a digest algorithm name into a corresponding {@link ScramMechanism} instance.
         *
         * @param digestAlgorithm The digest algorithm name (e.g., "SHA-256").
         * @return The corresponding {@link ScramMechanism} instance.
         * @throws UnsupportedDigestAlgorithmException if the provided digest algorithm is not supported.
         */
        private static ScramMechanism fromDigestAlgorithm(final String digestAlgorithm) {
            if (digestAlgorithm == null || digestAlgorithm.isEmpty()) {
                throw new UnsupportedDigestAlgorithmException("Digest algorithm name must not be null or empty");
            }
            final ScramMechanism mechanism = DIGEST_ALGORITHM_CACHE.get(digestAlgorithm.toLowerCase(Locale.ROOT));
            if (mechanism == null) {
                throw new UnsupportedDigestAlgorithmException("Unsupported digest algorithm: " + digestAlgorithm);
            }
            return mechanism;
        }
    }


    /**
     * Represents the current state of the SCRAM authentication process.
     * The state transitions are used to enforce the proper order of the
     * SCRAM authentication steps and to avoid any incorrect or out-of-order
     * processing.
     * <p>
     * Possible values:
     * <ul>
     *   <li>{@link #INITIAL} - Initial state before any message is sent. The process begins here.</li>
     *   <li>{@link #CLIENT_FIRST_SENT} - After the client sends the first message (client-first message).</li>
     *   <li>{@link #SERVER_FIRST_RECEIVED} - After the server sends the first challenge response (server-first message).</li>
     *   <li>{@link #CLIENT_PROOF_SENT} - After the client sends the final proof message to the server (client-final message).</li>
     *   <li>{@link #AUTHENTICATED} - Indicates that the authentication was successful, and the client has been authenticated.</li>
     *   <li>{@link #FAILED} - Indicates that the authentication process failed due to an error or incorrect response.</li>
     * </ul>
     * The state mechanism is crucial to maintaining the correct flow and preventing
     * unintended or out-of-sequence operations, which can lead to authentication failures.
     */
    private enum ScramState {
        INITIAL,             // Initial state before any message is sent.
        CLIENT_FIRST_SENT,   // After the client sends the first message.
        SERVER_FIRST_RECEIVED, // After receiving the server challenge.
        CLIENT_PROOF_SENT,   // After sending the client proof.
        AUTHENTICATED,       // After successful authentication.
        FAILED               // If the process fails at any step.
    }

}

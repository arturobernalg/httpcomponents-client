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
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.CredentialsProvider;
import org.apache.hc.client5.http.auth.MalformedChallengeException;
import org.apache.hc.client5.http.auth.StandardAuthScheme;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.utils.Base64;
import org.apache.hc.client5.http.utils.ByteArrayBuilder;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.CharArrayBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <p>SCRAM (Salted Challenge Response Authentication Mechanism) is an authentication scheme as described in
 * <a href="https://tools.ietf.org/html/rfc5802">RFC 5802</a> and <a href="https://tools.ietf.org/html/rfc7677">RFC 7677</a>.
 * This implementation specifically supports SCRAM-SHA-1, SCRAM-SHA-256, SCRAM-SHA-512, and their respective channel-binding
 * versions known as SCRAM-PLUS (e.g., SCRAM-SHA-256-PLUS).</p>
 *
 * <p>This class provides an implementation of the {@link AuthScheme} interface that can be used for authenticating
 * HTTP requests in a secure manner. SCRAM is particularly useful for mitigating dictionary attacks and other
 * weaknesses inherent in basic authentication schemes.</p>
 *
 * <h2>Usage</h2>
 * <p>Instances of {@code ScramScheme} should be used to generate SCRAM client authentication responses to
 * server-provided challenges. It uses a secure random value for client nonce generation, computes client and server
 * proofs, and ensures channel integrity with support for SCRAM-PLUS mechanisms like {@code tls-unique} or
 * {@code tls-server-end-point}.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is not thread-safe. Each instance of {@code ScramScheme} should be used with a single thread of execution.
 * For concurrent usage, create separate instances for each thread.</p>
 *
 * <h2>Supported Mechanisms</h2>
 * <p>SCRAM mechanisms supported by this implementation are:
 * <ul>
 *   <li>SCRAM-SHA-1</li>
 *   <li>SCRAM-SHA-256</li>
 *   <li>SCRAM-SHA-512</li>
 *   <li>SCRAM-SHA-256-PLUS</li>
 *   <li>SCRAM-SHA-512-PLUS</li>
 * </ul>
 * These mechanisms allow for secure password-based authentication with salted hashing and optionally use
 * channel-binding information to prevent Man-in-the-Middle (MitM) attacks.</p>
 *
 * <h2>Important Considerations</h2>
 * <p>The SCRAM scheme requires the client to hash and HMAC the password multiple times, making it computationally
 * infeasible for attackers to reverse-engineer the password. Channel-binding is optional but recommended where
 * secure TLS sessions are used. Implementors should ensure that usernames and passwords are UTF-8 encoded and
 * properly normalized to prevent issues with Unicode representation differences.</p>
 *
 * @since 5.5
 * @see <a href="https://tools.ietf.org/html/rfc5802">RFC 5802 - SCRAM: Salted Challenge Response Authentication Mechanism</a>
 * @see <a href="https://tools.ietf.org/html/rfc7677">RFC 7677 - SCRAM-SHA-256 and SCRAM-SHA-256-PLUS</a>
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
     * Buffer used for building authentication messages.
     */
    private transient ByteArrayBuilder buffer;

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
    private UsernamePasswordCredentials credentials;

    /**
     * Buffer for storing the bare part of the client-first-message.
     */
    private final ByteArrayBuilder clientFirstMessageBare;

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


    /**
     * Internal constructor that allows the use of a custom {@link SecureRandom} instance.
     * This constructor is intended for testing purposes only and should not be used in production.
     *
     * @param secureRandom The {@link SecureRandom} instance used to generate the client nonce.
     * This constructor is provided for testing purposes and allows deterministic
     * generation of nonces. Using a fixed source of randomness may compromise security in
     * production systems. Therefore, this constructor should not be used in any production code.
     */
    @Internal
    ScramScheme(final SecureRandom secureRandom) {
        this.paramMap = new HashMap<>();
        this.secureRandom = secureRandom;
        this.state = ScramState.INITIAL;
        buffer = new ByteArrayBuilder(128);
        clientFirstMessageBare = new ByteArrayBuilder(128);
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
    public ScramScheme() {
        this(new SecureRandom());
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
            for (final NameValuePair param : params) {
                this.paramMap.put(param.getName().toLowerCase(Locale.ROOT), param.getValue());
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
                throw new MalformedChallengeException("Iteration count too low. Must be at least 4096.");
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

        if (paramMap.containsKey("m")) {
            this.state = ScramState.FAILED;
            throw new MalformedChallengeException("Reserved attribute 'm' is not allowed as per RFC 5802");
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
    public boolean isResponseReady(final HttpHost host, final CredentialsProvider credentialsProvider,
                                   final HttpContext context)
            throws AuthenticationException {
        Args.notNull(host, "Auth host");
        Args.notNull(credentialsProvider, "CredentialsProvider");

        final HttpClientContext clientContext = HttpClientContext.cast(context);
        final AuthScope authScope = new AuthScope(host, getRealm(), getName());
        final Credentials credentials = credentialsProvider.getCredentials(authScope, clientContext);
        if (credentials instanceof UsernamePasswordCredentials) {
            this.credentials = (UsernamePasswordCredentials) credentials;
            return true;
        }

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
        final String password;
        try {
            username = SaslPrep.INSTANCE.prepAsQueryString(credentials.getUserName());
            password = SaslPrep.INSTANCE.prepAsStoredString(new String(credentials.getUserPassword()));
        } catch (final IllegalArgumentException e) {
            this.state = ScramState.FAILED;
            throw new AuthenticationException("Invalid character in user credential after SASLprep: " + e.getMessage());
        }

        if (buffer == null) {
            buffer = new ByteArrayBuilder(128);
        } else {
            buffer.reset();
        }

        clientFirstMessageBare.reset();

        // Add GS2 Header first
        final String gs2Header = mechanism.isPlus() ? "p=tls-server-end-point,," : "n,,";
        clientFirstMessageBare.append(gs2Header);

        // Append username and client nonce
        clientFirstMessageBare.append("n=").append(username).append(",r=").append(clientNonce);

        // Validate the GS2 header
        final byte[] clientFirstMessageBytes = clientFirstMessageBare.toByteArray();
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
            final HttpClientContext clientContext = HttpClientContext.cast(context);
            final SSLSession sslSession = clientContext.getSSLSession();
            if (sslSession == null) {
                this.state = ScramState.FAILED;
                throw new AuthenticationException("TLS session not available for SCRAM-PLUS mechanism");
            }

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
                    channelBinding = Base64.encodeBase64String(("p=tls-server-end-point,," + new String(tlsServerEndPoint, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
                    break;

                case "tls-unique":
                    final byte[] tlsUnique = clientContext.getTlsUnique();
                    if (tlsUnique == null) {
                        this.state = ScramState.FAILED;
                        throw new AuthenticationException("tlsUnique value not available in context for SCRAM-PLUS mechanism");
                    }
                    channelBinding = Base64.encodeBase64String(("p=tls-unique,," + new String(tlsUnique, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
                    break;

                default:
                    this.state = ScramState.FAILED;
                    throw new AuthenticationException("Unsupported channel binding type: " + channelBindingType);
            }
        } else {
            channelBinding = Base64.encodeBase64String("n,,".getBytes(StandardCharsets.UTF_8));
        }

        // Build authMessage
        final String serverNonce = paramMap.get("r");
        final String authMessage = new String(clientFirstMessageBare.toByteArray()) + "," +
                "r=" + serverNonce + ",s=" + paramMap.get("s") + ",i=" + paramMap.get("i") + "," +
                "c=" + channelBinding;

        // Compute client proof
        final byte[] clientKey;
        final byte[] saltedPassword;
        try {
            saltedPassword = calculateHi(password.getBytes(StandardCharsets.UTF_8), Base64.decodeBase64(salt),
                    iterationCount, mechanism.getHmacFunction());
            clientKey = hmac(saltedPassword, "Client Key", mechanism.getHmacFunction());
            final MessageDigest digester = createMessageDigest(mechanism.getHashFunction());
            storedKey = digester.digest(clientKey);
        } catch (final Exception e) {
            this.state = ScramState.FAILED;
            throw new AuthenticationException("Error computing client keys for SCRAM mechanism", e);
        }

        final byte[] clientSignature = hmac(storedKey, authMessage, mechanism.getHmacFunction());
        final byte[] clientProof = xor(clientKey, clientSignature);

        // Construct final response
        final CharArrayBuffer responseBuffer = new CharArrayBuffer(128);
        responseBuffer.append(StandardAuthScheme.SCRAM); // Add mechanism name (e.g., SCRAM-SHA-256)
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

        validateServerProof(serverProof, authMessage, mechanism);
        this.state = ScramState.CLIENT_PROOF_SENT;

        return new String(responseBuffer.toCharArray());
    }



    /**
     * Validates the server proof received from the server during the authentication process. This step
     * verifies that the server is in possession of the correct credentials and ensures the integrity of the SCRAM exchange.
     *
     * @param serverProof The server proof value received from the server during authentication. Must not be {@code null}.
     * @param authMessage The authentication message constructed from the client and server messages.
     * @param mechanism The SCRAM mechanism being used, which determines the hash and HMAC functions.
     * @throws AuthenticationException if the server proof validation fails, meaning the server's proof cannot be verified.
     */
    private void validateServerProof(final String serverProof, final String authMessage, final ScramMechanism mechanism)
            throws AuthenticationException {

        final byte[] expectedServerSignature;
        try {
            expectedServerSignature = hmac(storedKey, authMessage, mechanism.getHmacFunction());
        } catch (final Exception e) {
            this.state = ScramState.FAILED;
            throw new AuthenticationException("Failed to compute expected server signature: " + e.getMessage(), e);
        }


        final byte[] serverProofBytes = hexStringToByteArray(serverProof);

        if (!signatureAreEquals(serverProofBytes,expectedServerSignature)) {
            this.state = ScramState.FAILED;
            if (LOG.isDebugEnabled()) {
                LOG.debug("Server proof validation failed. The provided server signature does not match the expected signature. Expected: {}, Received: {}",
                        Base64.encodeBase64String(expectedServerSignature), serverProof);
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
     *         the same elements in the same order; {@code false} otherwise
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
     * @param key The key used to initialize the {@link Mac} instance.
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
            throw new UnsupportedDigestAlgorithmException(
                    "Unsupported algorithm in HTTP SCRAM authentication: " + algorithm);
        } catch (final InvalidKeyException e) {
            this.state = ScramState.FAILED;
            throw new UnsupportedDigestAlgorithmException("Invalid key used for SCRAM authentication: " + e.getMessage(), e);
        }
        return mac;
    }

    /**
     * Generates an HMAC value using the given key, message, and algorithm.
     *
     * @param key The key used to compute the HMAC.
     * @param message The message to be signed.
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
     * @param key The key used to compute the HMAC.
     * @param message The message to be signed.
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
     * Computes the ServerKey for SCRAM authentication.
     *
     * @param saltedPassword The salted password derived from the client's password and server-provided salt.
     * @param mechanism The SCRAM mechanism being used (e.g., SCRAM-SHA-256).
     * @return The ServerKey as a byte array.
     */
    private byte[] computeServerKey(final byte[] saltedPassword, final ScramMechanism mechanism) {
        return hmac(saltedPassword, "Server Key", mechanism.getHmacFunction());
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
    private MessageDigest createMessageDigest(final String alg)
            throws UnsupportedDigestAlgorithmException {
        try {
            return MessageDigest.getInstance(alg);
        } catch (final Exception e) {
            this.state = ScramState.FAILED;
            throw new UnsupportedDigestAlgorithmException(
                    "Unsupported algorithm in HTTP SCRAM authentication: " + alg);
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
     * @throws SSLPeerUnverifiedException if the SSL peer cannot be verified.
     */
    private byte[] computeTlsServerEndPoint(final SSLSession sslSession)
            throws CertificateEncodingException, SSLPeerUnverifiedException {
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
     *
     */
    private enum ScramMechanism {
        SCRAM_SHA_1("SCRAM-SHA-1","SHA-1","HmacSHA1", 160,4096),
        SCRAM_SHA_1_PLUS("SCRAM-SHA-1-PLUS","SHA-1","HmacSHA1",160,4096),
        SCRAM_SHA_224("SCRAM-SHA-224","SHA-224","HmacSHA224",224,4096),
        SCRAM_SHA_224_PLUS("SCRAM-SHA-224-PLUS","SHA-224","HmacSHA224",224, 4096),
        SCRAM_SHA_256("SCRAM-SHA-256","SHA-256","HmacSHA256",256,4096),
        SCRAM_SHA_256_PLUS("SCRAM-SHA-256-PLUS","SHA-256","HmacSHA256",256, 4096),
        SCRAM_SHA_384("SCRAM-SHA-384","SHA-384","HmacSHA384",384,4096),
        SCRAM_SHA_384_PLUS("SCRAM-SHA-384-PLUS","SHA-384","HmacSHA384",384, 4096),
        SCRAM_SHA_512("SCRAM-SHA-512","SHA-512","HmacSHA512",512,10000),
        SCRAM_SHA_512_PLUS("SCRAM-SHA-512-PLUS","SHA-512","HmacSHA512",512,10000);

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
                SCHEME_NAME_CACHE.put(mechanism.schemeName.toLowerCase(), mechanism);
                DIGEST_ALGORITHM_CACHE.put(mechanism.hashFunction.toLowerCase(), mechanism);
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
            final ScramMechanism mechanism = SCHEME_NAME_CACHE.get(schemeName.toLowerCase());
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
            final ScramMechanism mechanism = DIGEST_ALGORITHM_CACHE.get(digestAlgorithm.toLowerCase());
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
     *
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

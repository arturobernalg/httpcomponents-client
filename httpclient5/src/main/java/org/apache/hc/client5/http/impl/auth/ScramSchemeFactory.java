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

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.hc.client5.http.auth.AuthScheme;
import org.apache.hc.client5.http.auth.AuthSchemeFactory;
import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.http.config.Registry;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.protocol.HttpContext;

/**
 * Factory class for creating instances of {@link ScramScheme}, used for SCRAM (Salted Challenge Response Authentication Mechanism)
 * authentication in HTTP clients.
 *
 * <p>
 * This factory implements the {@link AuthSchemeFactory} interface and provides a mechanism for creating SCRAM authentication schemes.
 * The factory is stateless and threadsafe, meaning multiple threads can safely use a single instance of the factory.
 * </p>
 *
 * <p>
 * Usage of SCRAM authentication helps achieve secure challenge-response authentication over HTTP, preventing
 * replay attacks by including random nonces (numbers used only once). This factory facilitates the seamless
 * creation of {@link ScramScheme} instances, ensuring consistency across different parts of an HTTP client
 * authentication flow.
 * </p>
 *
 * <h2>Extension Support</h2>
 * <p>
 * This factory supports the registration of extensions through a {@link Registry} of {@link ScramExtension} instances.
 * Extensions allow customization of the SCRAM authentication process by providing additional parameters or behaviors
 * specific to the authentication flow. Registered extensions can be processed dynamically based on the challenge
 * received from the server.
 * </p>
 *
 * <h2>Supported Mechanisms</h2>
 * <p>
 * SCRAM mechanisms supported by this factory include:
 * <ul>
 *   <li>SCRAM-SHA-1</li>
 *   <li>SCRAM-SHA-256</li>
 *   <li>SCRAM-SHA-512</li>
 *   <li>SCRAM-SHA-256-PLUS</li>
 *   <li>SCRAM-SHA-512-PLUS</li>
 * </ul>
 * These mechanisms provide secure password-based authentication with support for salted hashing and optional
 * channel binding for additional security.
 * </p>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is thread-safe and can be used in concurrent environments. The factory maintains a stateless design
 * to ensure consistency and safety when creating SCRAM authentication schemes.
 * </p>
 *
 * @see ScramScheme
 * @see AuthSchemeFactory
 * @since 5.5
 */
@Contract(threading = ThreadingBehavior.STATELESS)
public class ScramSchemeFactory implements AuthSchemeFactory {

    /**
     * Default instance of {@link ScramSchemeFactory} with an empty extension registry.
     *
     * <p>
     * This singleton instance can be reused across different parts of the code where
     * SCRAM-based authentication is required. The instance is stateless and does not
     * maintain any session-specific information.
     * </p>
     */
    public static final AuthSchemeFactory INSTANCE = new ScramSchemeFactory();

    private final Registry<ScramExtension> extensionRegistry;

    /**
     * Default constructor for {@link ScramSchemeFactory}.
     *
     * <p>
     * This constructor initializes an instance of {@link ScramSchemeFactory} with an empty extension registry.
     * Typically, this constructor is invoked implicitly when using the {@link #INSTANCE} reference.
     * </p>
     */
    public ScramSchemeFactory() {
        this(new LinkedHashMap<>());
    }

    /**
     * Constructs a {@link ScramSchemeFactory} with a pre-defined set of SCRAM extensions.
     *
     * <p>
     * This constructor allows for customization of the SCRAM authentication process by registering
     * {@link ScramExtension} instances. Extensions are specified as a map where the key is the extension name
     * and the value is the {@link ScramExtension} implementation.
     * </p>
     *
     * @param extensionMap A {@link LinkedHashMap} containing extension names and their corresponding implementations.
     *                     If no extensions are required, an empty map can be provided.
     */
    public ScramSchemeFactory(final LinkedHashMap<String, ScramExtension> extensionMap) {
        final RegistryBuilder<ScramExtension> builder = RegistryBuilder.create();
        for (final Map.Entry<String, ScramExtension> entry : extensionMap.entrySet()) {
            builder.register(entry.getKey(), entry.getValue());
        }
        this.extensionRegistry = builder.build();
    }

    /**
     * Creates a new instance of {@link ScramScheme}.
     *
     * <p>
     * This method is responsible for returning a new instance of {@link ScramScheme},
     * which is used for performing SCRAM-based authentication in HTTP clients. The
     * {@link ScramScheme} instance supports processing SCRAM extensions as registered
     * in the factory.
     * </p>
     *
     * @param context the {@link HttpContext} for which the {@link AuthScheme} is created.
     *                This may contain additional parameters or attributes used in the
     *                authentication process.
     * @return An instance of {@link ScramScheme} that can be used for SCRAM-based
     * authentication, including support for registered extensions.
     */
    @Override
    public AuthScheme create(final HttpContext context) {
        return new ScramScheme(extensionRegistry);
    }
}

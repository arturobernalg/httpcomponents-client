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

import org.apache.hc.client5.http.auth.AuthScheme;
import org.apache.hc.client5.http.auth.AuthSchemeFactory;
import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
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
 * <p>
 * SCRAM is typically used with mechanisms such as SHA-256 or SHA-512 to provide an added layer of security.
 * This class provides support for SCRAM mechanisms including standard SCRAM-SHA-1, SCRAM-SHA-256, and their
 * respective variants.
 * </p>
 *
 * @see ScramScheme
 * @see AuthSchemeFactory
 * @see <a href="https://tools.ietf.org/html/rfc5802">RFC 5802: Salted Challenge Response Authentication Mechanism (SCRAM)</a>
 * @since 5.5
 */
@Contract(threading = ThreadingBehavior.STATELESS)
public class ScramSchemeFactory implements AuthSchemeFactory {

    /**
     * Default instance of {@link ScramSchemeFactory} with a null Charset.
     *
     * <p>
     * This singleton instance can be reused across different parts of the code where
     * SCRAM-based authentication is required. The instance is stateless and thus
     * does not maintain any session information.
     * </p>
     */
    public static final AuthSchemeFactory INSTANCE = new ScramSchemeFactory();

    /**
     * Default constructor for {@link ScramSchemeFactory}.
     *
     * <p>
     * This constructor initializes an instance of {@link ScramSchemeFactory}.
     * Typically, this constructor is invoked implicitly when using the {@link #INSTANCE} reference.
     * </p>
     */
    public ScramSchemeFactory() {
    }

    /**
     * Creates a new instance of {@link ScramScheme}.
     *
     * <p>
     * This method is responsible for returning a new instance of {@link ScramScheme},
     * which is used for performing SCRAM-based authentication in HTTP clients.
     * The {@link ScramScheme} instance provides the necessary methods to complete
     * the challenge-response cycle using credentials provided in the {@link HttpContext}.
     * </p>
     *
     * @param context the {@link HttpContext} for which the {@link AuthScheme} is created.
     *                This may contain additional parameters or attributes used in the
     *                authentication process.
     * @return An instance of {@link ScramScheme} that can be used for SCRAM-based
     * authentication.
     */
    @Override
    public AuthScheme create(final HttpContext context) {
        return new ScramScheme();
    }
}

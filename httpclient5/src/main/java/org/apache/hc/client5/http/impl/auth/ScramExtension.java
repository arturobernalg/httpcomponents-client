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

import org.apache.hc.client5.http.auth.AuthenticationException;
import org.apache.hc.core5.http.protocol.HttpContext;

/**
 * Represents a SCRAM extension that provides additional functionality during the SCRAM authentication process.
 * <p>
 * Extensions are optional features that can enhance the SCRAM authentication mechanism by providing additional
 * parameters or behaviors. Each extension is identified by a unique name and may include an associated value.
 * </p>
 * <p>
 * Implementations of this interface should define the behavior for handling specific SCRAM extensions and
 * indicate which extensions they support.
 * </p>
 *
 * @since 5.5
 */
public interface ScramExtension {

    /**
     * Processes the extension during the SCRAM authentication process.
     * <p>
     * This method is invoked for each extension specified in the server challenge. The implementation should handle
     * the extension logic and modify the provided {@link HttpContext} as needed.
     * </p>
     *
     * @param name    The name of the extension. Must not be {@code null}.
     * @param value   The value associated with the extension, or {@code null} if the extension does not have a value.
     * @param context The context of the authentication, providing access to the state or other necessary data.
     *                Must not be {@code null}.
     * @return {@code true} if the extension was processed successfully, or {@code false} if the extension is not handled.
     * @throws AuthenticationException If the extension processing encounters an error.
     */
    boolean process(String name, String value, HttpContext context) throws AuthenticationException;

    /**
     * Checks if this extension supports the given extension name.
     * <p>
     * This method is used to determine whether the implementation can handle a specific SCRAM extension based
     * on its name.
     * </p>
     *
     * @param name The name of the extension to check. Must not be {@code null}.
     * @return {@code true} if this extension supports the given name, {@code false} otherwise.
     */
    boolean supports(String name);
}

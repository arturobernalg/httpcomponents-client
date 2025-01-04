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

package org.apache.hc.client5.http.auth;

import java.io.Serializable;
import java.security.Principal;
import java.util.Arrays;
import java.util.Objects;

import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.util.Args;

/**
 * Credentials representation based on pre-computed ClientKey and ServerKey for SCRAM authentication.
 * This class is used when these keys are already available, which is not typical for standard SCRAM
 * as they are usually derived from a password during the authentication process.
 *
 * @since 5.3
 */
@Contract(threading = ThreadingBehavior.IMMUTABLE)
public class ClientKeyServerKeyCredentials implements Credentials, Serializable {

    private static final long serialVersionUID = 1L;

    private final Principal principal;
    private final byte[] clientKey;
    private final byte[] serverKey;

    /**
     * Constructs a new set of ClientKey and ServerKey credentials.
     *
     * @param principal The user principal
     * @param clientKey The client key for SCRAM authentication
     * @param serverKey The server key for SCRAM verification
     */
    public ClientKeyServerKeyCredentials(final Principal principal, final byte[] clientKey, final byte[] serverKey) {
        super();
        this.principal = Args.notNull(principal, "User principal");
        this.clientKey = clientKey != null ? Arrays.copyOf(clientKey, clientKey.length) : null;
        this.serverKey = serverKey != null ? Arrays.copyOf(serverKey, serverKey.length) : null;
    }

    @Override
    public Principal getUserPrincipal() {
        return this.principal;
    }

    public byte[] getClientKey() {
        return this.clientKey != null ? Arrays.copyOf(this.clientKey, this.clientKey.length) : null;
    }

    public byte[] getServerKey() {
        return this.serverKey != null ? Arrays.copyOf(this.serverKey, this.serverKey.length) : null;
    }

    @Override
    public char[] getPassword() {
        // No password in this context
        return null;
    }

    @Override
    public int hashCode() {
        return this.principal.hashCode();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof ClientKeyServerKeyCredentials)) return false;
        final ClientKeyServerKeyCredentials that = (ClientKeyServerKeyCredentials) o;
        return Objects.equals(this.principal, that.principal)
                && Arrays.equals(this.clientKey, that.clientKey)
                && Arrays.equals(this.serverKey, that.serverKey);
    }

    @Override
    public String toString() {
        return this.principal.toString();
    }
}
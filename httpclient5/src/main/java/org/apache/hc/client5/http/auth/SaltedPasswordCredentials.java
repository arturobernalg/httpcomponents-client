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
 * Credentials representation for SCRAM authentication using a pre-computed SaltedPassword.
 * This class is used when the salted password is already available, which is not typical for standard SCRAM
 * as it's usually derived from the user's password during authentication.
 *
 * @since 5.3
 */
@Contract(threading = ThreadingBehavior.IMMUTABLE)
public class SaltedPasswordCredentials implements Credentials, Serializable {

    private static final long serialVersionUID = 1L;

    private final Principal principal;
    private final byte[] saltedPassword;

    /**
     * Constructs a new set of SaltedPassword credentials.
     *
     * @param principal      The user principal
     * @param saltedPassword The salted password for SCRAM authentication
     */
    public SaltedPasswordCredentials(final Principal principal, final byte[] saltedPassword) {
        super();
        this.principal = Args.notNull(principal, "User principal");
        this.saltedPassword = saltedPassword != null ? Arrays.copyOf(saltedPassword, saltedPassword.length) : null;
    }

    @Override
    public Principal getUserPrincipal() {
        return this.principal;
    }

    public byte[] getSaltedPassword() {
        return this.saltedPassword != null ? Arrays.copyOf(this.saltedPassword, this.saltedPassword.length) : null;
    }

    @Override
    public char[] getPassword() {
        // No direct password, this is a processed form of it.
        return null;
    }

    @Override
    public int hashCode() {
        return this.principal.hashCode();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof SaltedPasswordCredentials)) return false;
        final SaltedPasswordCredentials that = (SaltedPasswordCredentials) o;
        return Objects.equals(this.principal, that.principal)
                && Arrays.equals(this.saltedPassword, that.saltedPassword);
    }

    @Override
    public String toString() {
        return this.principal.toString();
    }
}
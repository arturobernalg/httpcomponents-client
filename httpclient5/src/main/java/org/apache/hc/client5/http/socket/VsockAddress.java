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

package org.apache.hc.client5.http.socket;

import java.util.Objects;

import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.util.Args;

/**
 * AF_VSOCK address identifying a VM socket endpoint.
 *
 * @since 5.7
 */
@Contract(threading = ThreadingBehavior.IMMUTABLE)
public final class VsockAddress {

    public static final int VMADDR_CID_ANY = -1;
    public static final int VMADDR_CID_HYPERVISOR = 0;
    public static final int VMADDR_CID_LOCAL = 1;
    public static final int VMADDR_CID_HOST = 2;

    private final int cid;
    private final int port;

    private VsockAddress(final int cid, final int port) {
        this.cid = cid;
        this.port = port;
    }

    public static VsockAddress of(final int cid, final int port) {
        Args.check(cid >= VMADDR_CID_ANY, "CID must be >= %d", VMADDR_CID_ANY);
        Args.notNegative(port, "Port");
        return new VsockAddress(cid, port);
    }

    public int getCid() {
        return cid;
    }

    public int getPort() {
        return port;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof VsockAddress)) {
            return false;
        }
        final VsockAddress that = (VsockAddress) obj;
        return this.cid == that.cid && this.port == that.port;
    }

    @Override
    public int hashCode() {
        return Objects.hash(cid, port);
    }

    @Override
    public String toString() {
        return "vsock://" + cid + ":" + port;
    }
}

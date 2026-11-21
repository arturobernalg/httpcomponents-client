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
package org.apache.hc.client5.http;

import org.apache.hc.core5.annotation.Experimental;
import org.apache.hc.core5.concurrent.Cancellable;

/**
 * Optional DNS resolver SPI capable of resolving a host's address families (A / AAAA)
 * independently and asynchronously, reporting each family's result to
 * {@link DnsResolutionCallback} as soon as it becomes available rather than blocking
 * until every family has been queried (RFC 8305, Section 3).
 *
 * <p>This is a strictly optional extension of {@link DnsResolver}: when none is
 * configured, resolution falls back to the plain, synchronous {@link DnsResolver},
 * which remains the primary resolution SPI. {@link DnsResolver} cannot itself
 * implement this interface by running its blocking lookup on a separate thread:
 * that would still serialize both families behind one blocking call and misrepresent
 * it as an independent asynchronous resolution of each. Implement this interface only
 * when the underlying resolution mechanism genuinely queries A and AAAA records
 * independently, for example an asynchronous DNS client.</p>
 *
 * <p>This SPI is considered experimental and may be discontinued in the future.</p>
 *
 * @since 5.7
 */
@Experimental
public interface AsyncDnsResolver {

    /**
     * Starts resolving {@code host}. Implementations call {@link DnsResolutionCallback#resolved}
     * once for each address family that resolves successfully, as soon as that family's
     * result becomes available, independently of any other family still outstanding, and
     * call {@link DnsResolutionCallback#failed} once for each family that fails or does not
     * apply. Once every attempted family has either resolved or failed, implementations call
     * {@link DnsResolutionCallback#completed()} exactly once.
     *
     * @param host the host name to resolve.
     * @param port the port to attach to resolved addresses.
     * @param callback receives each family's result as it becomes available.
     * @return a handle that cancels any outstanding queries.
     */
    Cancellable resolve(String host, int port, DnsResolutionCallback callback);

}

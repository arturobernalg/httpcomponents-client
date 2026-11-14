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

import java.net.InetSocketAddress;
import java.util.List;

import org.apache.hc.core5.annotation.Experimental;

/**
 * Receives the results of an {@link AsyncDnsResolver} resolution, one address family
 * at a time, as each becomes available.
 *
 * <p>This SPI is considered experimental and may be discontinued in the future.</p>
 *
 * @since 5.7
 */
@Experimental
public interface DnsResolutionCallback {

    /**
     * Called once a single address family (A or AAAA) has resolved successfully, as soon
     * as that family's result is available and independently of any other family still
     * being resolved. May be called more than once for the same resolution, once per
     * family that resolves successfully; never called with an empty or {@code null} list.
     *
     * @param addresses the addresses resolved for one address family.
     */
    void resolved(List<InetSocketAddress> addresses);

    /**
     * Called once a single address family has failed to resolve or does not apply to
     * the target host. May be called more than once, once per family that fails,
     * including after {@link #resolved(List)} has already provided addresses for a
     * different family.
     *
     * @param cause the reason this family could not be resolved.
     */
    void failed(Exception cause);

    /**
     * Called exactly once, after every attempted address family has either resolved
     * or failed, to signal that no further results will arrive for this resolution.
     */
    void completed();

}

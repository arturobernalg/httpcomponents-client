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
import java.util.ArrayList;
import java.util.List;

import org.apache.hc.core5.annotation.Experimental;

/**
 * Merges a batch of newly resolved addresses for one IP family into a Happy Eyeballs race's
 * still-pending address list, which already holds addresses of both families (RFC 8305).
 * Used by the asynchronous, independent A/AAAA resolution path, where a race is fed addresses
 * as each family resolves rather than all at once.
 *
 * <p>
 * An implementation MUST interleave the two families rather than place one ahead of the
 * other: the race itself, not this ordering, decides which address wins by connecting first.
 * Returning one family's addresses as a contiguous block ahead of the other's would leave
 * those later addresses untried until every earlier one has already failed, defeating the
 * purpose of racing more than one candidate (RFC 8305 Section 3).
 * </p>
 *
 * @since 5.7
 */
@Experimental
@FunctionalInterface
public interface AddressOrderingStrategy {

    /**
     * Merges {@code ipv6} and {@code ipv4} into one race-friendly address list. Must not
     * return {@code null}; a caller that cannot tolerate this throwing falls back to
     * {@link #simple()} instead of losing either argument's addresses.
     *
     * @param ipv6 pending IPv6 addresses, in their current order.
     * @param ipv4 pending IPv4 addresses, in their current order.
     * @return the merged list, with both families interleaved.
     */
    List<InetSocketAddress> interleave(List<InetSocketAddress> ipv6, List<InetSocketAddress> ipv4);

    /**
     * Alternates between the two families, starting with IPv6, preserving each family's
     * existing relative order. This is the default used when no strategy is configured:
     * it reproduces the behavior Happy Eyeballs racing had before RFC 6724-aware ordering
     * was introduced.
     *
     * @return the simple alternating strategy.
     */
    static AddressOrderingStrategy simple() {
        return (ipv6, ipv4) -> {
            final List<InetSocketAddress> result = new ArrayList<>(ipv6.size() + ipv4.size());
            final int max = Math.max(ipv6.size(), ipv4.size());
            for (int i = 0; i < max; i++) {
                if (i < ipv6.size()) {
                    result.add(ipv6.get(i));
                }
                if (i < ipv4.size()) {
                    result.add(ipv4.get(i));
                }
            }
            return result;
        };
    }

    /**
     * Sorts the combined candidates by RFC 6724 destination selection rules, using the same
     * comparator {@link AddressSelectingDnsResolver} applies, then interleaves families
     * starting from whichever one that sort ranks first (the interleave algorithm of RFC 8305
     * Section 4). Orders the same addresses {@link AddressSelectingDnsResolver} would with
     * {@code ProtocolFamilyPreference.INTERLEAVE}; it differs from that resolver's other
     * preference modes only in ordering, not in which addresses are returned.
     *
     * @return the destination-selection-aware strategy.
     */
    static AddressOrderingStrategy destinationOrdered() {
        return (ipv6, ipv4) -> {
            final List<InetSocketAddress> combined = new ArrayList<>(ipv6.size() + ipv4.size());
            combined.addAll(ipv6);
            combined.addAll(ipv4);
            return AddressSelectingDnsResolver.sortAndInterleave(combined);
        };
    }

}

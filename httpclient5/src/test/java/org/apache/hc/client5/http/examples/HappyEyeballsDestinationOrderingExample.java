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

package org.apache.hc.client5.http.examples;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.AddressOrderingStrategy;
import org.apache.hc.client5.http.AsyncDnsResolver;
import org.apache.hc.client5.http.DnsResolutionCallback;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.impl.nio.MultihomeConnectionInitiator;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

/**
 * Demonstrates, with a real {@link MultihomeConnectionInitiator}, that enabling Happy
 * Eyeballs racing alone does not make an independently-resolved A/AAAA merge RFC
 * 6724-aware: the merge either alternates families blindly (today's default), or, with an
 * {@link AddressOrderingStrategy#destinationOrdered()} configured, follows RFC 6724 Section
 * 6's actual rule-based ordering for the two resolved addresses used below, a global IPv4
 * address and a Teredo (RFC 6724 §2.1's precedence and label policy entry for
 * {@code 2001::/32}, precedence 5) IPv6 address, which RFC 6724 ranks the IPv4 address ahead
 * of, not behind.
 *
 * <p>A (IPv4) is resolved first, then AAAA (IPv6) shortly after, within RFC 8305 Section 3's
 * Resolution Delay window, so both addresses are merged into the race together, through
 * exactly the code path this example is about, before the first attempt ever starts.</p>
 *
 * <p>Every attempt below fails deliberately (there is nothing to actually connect to); this
 * example exists to print the order addresses were raced in, not to establish a real
 * connection.</p>
 */
public final class HappyEyeballsDestinationOrderingExample {

    public static void main(final String[] args) throws Exception {
        final InetSocketAddress v6Teredo = new InetSocketAddress(InetAddress.getByName("2001::1"), 443);
        final InetSocketAddress v4Global = new InetSocketAddress(InetAddress.getByName("192.0.2.1"), 443);

        System.out.println("Before (no AddressOrderingStrategy configured, today's default):");
        printRaceOrder(race(null, v6Teredo, v4Global));

        System.out.println();
        System.out.println("After (AddressOrderingStrategy.destinationOrdered()):");
        printRaceOrder(race(AddressOrderingStrategy.destinationOrdered(), v6Teredo, v4Global));
    }

    private static List<SocketAddress> race(
            final AddressOrderingStrategy addressOrderingStrategy,
            final InetSocketAddress v6,
            final InetSocketAddress v4) throws Exception {

        final List<SocketAddress> attempted = new CopyOnWriteArrayList<>();
        final AtomicReference<DnsResolutionCallback> callbackRef = new AtomicReference<>();

        // A fake AsyncDnsResolver, standing in for a real asynchronous DNS client capable of
        // resolving A and AAAA independently; driven explicitly below instead of resolving
        // both immediately, so A genuinely arrives before AAAA, as RFC 8305 Section 3 expects.
        final AsyncDnsResolver asyncDnsResolver = (host, port, callback) -> {
            callbackRef.set(callback);
            return () -> true;
        };

        // A fake ConnectionInitiator: records the order addresses were raced in and fails
        // every attempt immediately, since there is nothing to actually connect to here.
        final MultihomeConnectionInitiator connectionInitiator = new MultihomeConnectionInitiator(
                (remoteEndpoint, remoteAddress, localAddress, timeout, attachment, callback) -> {
                    attempted.add(remoteAddress);
                    callback.failed(new java.net.ConnectException("refused (example only)"));
                    return CompletableFuture.completedFuture(null);
                },
                SystemDefaultDnsResolver.INSTANCE,
                true,
                // The RFC 8305 Section 8 minimum: keeps this example's inter-attempt spacing
                // (not the Resolution Delay, which is fixed at 50ms) short to run quickly.
                TimeValue.ofMilliseconds(10),
                asyncDnsResolver,
                addressOrderingStrategy);

        final CompletableFuture<IOSession> outcome = new CompletableFuture<>();
        connectionInitiator.connect(
                new HttpHost("example.invalid", 443),
                null,
                Timeout.ofSeconds(1),
                null,
                new FutureCallback<IOSession>() {
                    @Override
                    public void completed(final IOSession result) {
                        outcome.complete(result);
                    }

                    @Override
                    public void failed(final Exception ex) {
                        outcome.completeExceptionally(ex);
                    }

                    @Override
                    public void cancelled() {
                        outcome.cancel(false);
                    }
                });

        // A arrives first; it is buffered for up to the 50ms Resolution Delay, waiting for
        // AAAA, rather than raced on its own straight away (RFC 8305 Section 3).
        callbackRef.get().resolved(Collections.singletonList(v4));
        // AAAA arrives well within that window: both addresses are merged into the race's
        // pending list together, through AddressOrderingStrategy, before anything races.
        Thread.sleep(5);
        callbackRef.get().resolved(Collections.singletonList(v6));
        callbackRef.get().completed();

        // Every attempt fails deliberately; wait for the race to exhaust both addresses.
        try {
            outcome.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (final Exception expected) {
            // Expected: there is nothing to actually connect to in this example.
        }
        return attempted;
    }

    private static void printRaceOrder(final List<SocketAddress> attempted) {
        int i = 1;
        for (final SocketAddress address : attempted) {
            final InetSocketAddress isa = (InetSocketAddress) address;
            final String family = isa.getAddress() instanceof java.net.Inet6Address ? "IPv6" : "IPv4";
            System.out.println("  " + (i++) + ". " + family + " " + isa.getAddress().getHostAddress());
        }
        if (attempted.size() == 2) {
            final boolean v4First = !(((InetSocketAddress) attempted.get(0)).getAddress() instanceof java.net.Inet6Address);
            System.out.println("  -> " + (v4First ? "IPv4 raced first" : "IPv6 raced first"));
        }
    }

    private HappyEyeballsDestinationOrderingExample() {
    }
}

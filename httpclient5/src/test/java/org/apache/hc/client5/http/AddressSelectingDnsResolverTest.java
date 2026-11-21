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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

import org.apache.hc.client5.http.config.ProtocolFamilyPreference;
import org.apache.hc.client5.http.impl.InMemoryDnsResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AddressSelectingDnsResolverTest {

    private static final AddressSelectingDnsResolver.SourceAddressResolver NO_SOURCE_ADDR =
            (final InetSocketAddress dest) -> null;

    private InMemoryDnsResolver delegate;

    @BeforeEach
    void setUp() {
        delegate = new InMemoryDnsResolver();
    }

    @Test
    void ipv4Only_filtersOutIPv6() throws Exception {
        final InetAddress v4 = inet("203.0.113.10"); // TEST-NET-3
        final InetAddress v6 = inet("2001:db8::10"); // documentation prefix

        delegate.add("dual.example", v6, v4);

        final AddressSelectingDnsResolver r =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.IPV4_ONLY, NO_SOURCE_ADDR);

        final InetAddress[] ordered = r.resolve("dual.example");
        assertEquals(1, ordered.length);
        assertInstanceOf(Inet4Address.class, ordered[0]);
        assertEquals(v4, ordered[0]);
    }

    @Test
    void ipv6Only_filtersOutIPv4() throws Exception {
        final InetAddress v4 = inet("192.0.2.1");     // TEST-NET-1
        final InetAddress v6 = inet("2001:db8::1");

        delegate.add("dual.example", v4, v6);

        final AddressSelectingDnsResolver r =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.IPV6_ONLY, NO_SOURCE_ADDR);

        final InetAddress[] ordered = r.resolve("dual.example");
        assertEquals(1, ordered.length);
        assertInstanceOf(Inet6Address.class, ordered[0]);
        assertEquals(v6, ordered[0]);
    }

    @Test
    void ipv4Only_throwsWhenNoIPv4Candidates() throws Exception {
        final InetAddress v6a = inet("2001:db8::1");
        final InetAddress v6b = inet("2001:db8::2");

        delegate.add("v6only.example", v6a, v6b);

        final AddressSelectingDnsResolver r =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.IPV4_ONLY, NO_SOURCE_ADDR);

        // Consistent with every other DnsResolver in this codebase: "nothing left to
        // resolve to" is reported by throwing, never by returning null.
        assertThrows(UnknownHostException.class, () -> r.resolve("v6only.example"));
    }

    @Test
    void default_hasNoFamilyBias() throws Exception {
        final InetAddress v6a = inet("2001:db8::1");
        final InetAddress v6b = inet("2001:db8::2");
        final InetAddress v4a = inet("192.0.2.1");
        final InetAddress v4b = inet("203.0.113.10");

        delegate.add("dual.example", v6a, v6b, v4a, v4b);

        final AddressSelectingDnsResolver r1 =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.DEFAULT, NO_SOURCE_ADDR);
        final AddressSelectingDnsResolver r2 =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.DEFAULT, NO_SOURCE_ADDR);

        final InetAddress[] out1 = r1.resolve("dual.example");
        final InetAddress[] out2 = r2.resolve("dual.example");

        assertArrayEquals(out1, out2);
        assertEquals(4, out1.length);
    }

    @Test
    void interleave_alternatesFamilies_preservingRelativeOrder_whenRfcSortIsNoop() throws Exception {
        final InetAddress v6a = inet("2001:db8::1");
        final InetAddress v6b = inet("2001:db8::2");
        final InetAddress v4a = inet("192.0.2.1");
        final InetAddress v4b = inet("203.0.113.10");

        // With NO_SOURCE_ADDR, RFC sort becomes a stable no-op; deterministic interleave.
        delegate.add("dual.example", v6a, v6b, v4a, v4b);

        final AddressSelectingDnsResolver r =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.INTERLEAVE, NO_SOURCE_ADDR);

        final InetAddress[] out = r.resolve("dual.example");
        assertEquals(Arrays.asList(v6a, v4a, v6b, v4b), Arrays.asList(out));
    }

    @Test
    void preferIpv6_groupsAllV6First_preservingRelativeOrder_whenRfcSortIsNoop() throws Exception {
        final InetAddress v4a = inet("192.0.2.1");
        final InetAddress v6a = inet("2001:db8::1");
        final InetAddress v4b = inet("203.0.113.10");
        final InetAddress v6b = inet("2001:db8::2");

        delegate.add("dual.example", v4a, v6a, v4b, v6b);

        final AddressSelectingDnsResolver preferV6 =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.PREFER_IPV6, NO_SOURCE_ADDR);

        final InetAddress[] out = preferV6.resolve("dual.example");
        assertEquals(Arrays.asList(v6a, v6b, v4a, v4b), Arrays.asList(out));
        assertInstanceOf(Inet6Address.class, out[0]);
    }

    @Test
    void multicastDestinationIsDeprioritizedNotDropped() throws Exception {
        // RFC 6724 Rule 1 deprioritizes an unusable destination, it does not remove
        // it. A multicast destination is never probed for a source address (see
        // isProbableDestination()): its source is undefined, so Rule 1 sorts it
        // behind a destination with a real, defined source, but it is still present
        // in the result, listed first in the delegate's own order.
        final InetAddress multicastV6 = inet("ff02::1");
        final InetAddress v6 = inet("2001:db8::1");
        final InetAddress v6Src = inet("2001:db8::abcd");

        delegate.add("mcast.example", multicastV6, v6);

        final AddressSelectingDnsResolver.SourceAddressResolver stub = dest ->
                v6.equals(dest.getAddress()) ? v6Src : fail("must not probe a multicast destination");

        final AddressSelectingDnsResolver r =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.DEFAULT, stub);

        final InetAddress[] out = r.resolve("mcast.example");
        assertEquals(Arrays.asList(v6, multicastV6), Arrays.asList(out));
    }

    @Test
    void wildcardDestinationIsDeprioritizedNotDropped() throws Exception {
        final InetAddress wildcard = inet("0.0.0.0");
        final InetAddress v4 = inet("192.0.2.1");
        final InetAddress v4Src = inet("192.0.2.100");

        delegate.add("wild.example", wildcard, v4);

        final AddressSelectingDnsResolver.SourceAddressResolver stub = dest ->
                v4.equals(dest.getAddress()) ? v4Src : fail("must not probe a wildcard destination");

        final AddressSelectingDnsResolver r =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.DEFAULT, stub);

        final InetAddress[] out = r.resolve("wild.example");
        assertEquals(Arrays.asList(v4, wildcard), Arrays.asList(out));
    }

    // -------------------------------------------------------------------------
    // Direct tests for classifyScope(..) and Scope.fromValue(..)
    // -------------------------------------------------------------------------

    @Test
    void classifyScope_ipv6Loopback_isLinkLocal() throws Exception {
        // ::1 maps to link-local scope; interface-local (0x1) is multicast-only (RFC 4291 §2.7).
        assertEquals(AddressSelectingDnsResolver.Scope.LINK_LOCAL,
                AddressSelectingDnsResolver.classifyScope(inet("::1")));
    }

    @Test
    void classifyScope_ipv4Loopback_isLinkLocal() throws Exception {
        // IPv4 127/8 maps to link-local, NOT interface-local (RFC 6724 §3.1).
        assertEquals(AddressSelectingDnsResolver.Scope.LINK_LOCAL,
                AddressSelectingDnsResolver.classifyScope(inet("127.0.0.1")));
        assertEquals(AddressSelectingDnsResolver.Scope.LINK_LOCAL,
                AddressSelectingDnsResolver.classifyScope(inet("127.255.255.255")));
    }

    @Test
    void classifyScope_linkLocal() throws Exception {
        // IPv4 169.254/16 → link-local.
        assertEquals(AddressSelectingDnsResolver.Scope.LINK_LOCAL,
                AddressSelectingDnsResolver.classifyScope(inet("169.254.0.1")));
        // IPv6 fe80::/10 → link-local.
        assertEquals(AddressSelectingDnsResolver.Scope.LINK_LOCAL,
                AddressSelectingDnsResolver.classifyScope(inet("fe80::1")));
    }

    @Test
    void classifyScope_ipv4Private_isGlobal() throws Exception {
        // RFC 6724: all IPv4 except 127/8 and 169.254/16 are global,
        // including RFC1918 (10/8, 172.16/12, 192.168/16) and 100.64/10.
        assertEquals(AddressSelectingDnsResolver.Scope.GLOBAL,
                AddressSelectingDnsResolver.classifyScope(inet("10.0.0.1")));
        assertEquals(AddressSelectingDnsResolver.Scope.GLOBAL,
                AddressSelectingDnsResolver.classifyScope(inet("172.16.0.1")));
        assertEquals(AddressSelectingDnsResolver.Scope.GLOBAL,
                AddressSelectingDnsResolver.classifyScope(inet("192.168.1.1")));
        assertEquals(AddressSelectingDnsResolver.Scope.GLOBAL,
                AddressSelectingDnsResolver.classifyScope(inet("100.64.0.1")));
    }

    @Test
    void classifyScope_ipv6SiteLocal() throws Exception {
        // IPv6 deprecated site-local (fec0::/10) still classified as site-local per RFC 6724.
        assertEquals(AddressSelectingDnsResolver.Scope.SITE_LOCAL,
                AddressSelectingDnsResolver.classifyScope(inet("fec0::1")));
    }

    @Test
    void classifyScope_global() throws Exception {
        assertEquals(AddressSelectingDnsResolver.Scope.GLOBAL,
                AddressSelectingDnsResolver.classifyScope(inet("8.8.8.8")));
        assertEquals(AddressSelectingDnsResolver.Scope.GLOBAL,
                AddressSelectingDnsResolver.classifyScope(inet("2003::1")));
    }

    @Test
    void classifyScope_ipv6Multicast_usesLowNibbleScope() throws Exception {
        // ff01::1 -> scope 0x1 -> INTERFACE_LOCAL
        assertEquals(AddressSelectingDnsResolver.Scope.INTERFACE_LOCAL,
                AddressSelectingDnsResolver.classifyScope(inet("ff01::1")));
        // ff02::1 -> scope 0x2 -> LINK_LOCAL
        assertEquals(AddressSelectingDnsResolver.Scope.LINK_LOCAL,
                AddressSelectingDnsResolver.classifyScope(inet("ff02::1")));
        // ff04::1 -> scope 0x4 -> ADMIN_LOCAL
        assertEquals(AddressSelectingDnsResolver.Scope.ADMIN_LOCAL,
                AddressSelectingDnsResolver.classifyScope(inet("ff04::1")));
        // ff05::1 -> scope 0x5 -> SITE_LOCAL
        assertEquals(AddressSelectingDnsResolver.Scope.SITE_LOCAL,
                AddressSelectingDnsResolver.classifyScope(inet("ff05::1")));
        // ff08::1 -> scope 0x8 -> ORG_LOCAL
        assertEquals(AddressSelectingDnsResolver.Scope.ORG_LOCAL,
                AddressSelectingDnsResolver.classifyScope(inet("ff08::1")));
        // ff0e::1 -> scope 0xe -> GLOBAL
        assertEquals(AddressSelectingDnsResolver.Scope.GLOBAL,
                AddressSelectingDnsResolver.classifyScope(inet("ff0e::1")));
    }

    @Test
    void scopeFromValue_mapsKnownConstants() {
        assertEquals(AddressSelectingDnsResolver.Scope.INTERFACE_LOCAL,
                AddressSelectingDnsResolver.Scope.fromValue(0x1));
        assertEquals(AddressSelectingDnsResolver.Scope.LINK_LOCAL,
                AddressSelectingDnsResolver.Scope.fromValue(0x2));
        assertEquals(AddressSelectingDnsResolver.Scope.ADMIN_LOCAL,
                AddressSelectingDnsResolver.Scope.fromValue(0x4));
        assertEquals(AddressSelectingDnsResolver.Scope.SITE_LOCAL,
                AddressSelectingDnsResolver.Scope.fromValue(0x5));
        assertEquals(AddressSelectingDnsResolver.Scope.ORG_LOCAL,
                AddressSelectingDnsResolver.Scope.fromValue(0x8));
        assertEquals(AddressSelectingDnsResolver.Scope.GLOBAL,
                AddressSelectingDnsResolver.Scope.fromValue(0xe));
    }

    @Test
    void scopeFromValue_throwsOnUnknownValue() {
        assertThrows(IllegalArgumentException.class,
                () -> AddressSelectingDnsResolver.Scope.fromValue(0x0));
        assertThrows(IllegalArgumentException.class,
                () -> AddressSelectingDnsResolver.Scope.fromValue(0x3));
        assertThrows(IllegalArgumentException.class,
                () -> AddressSelectingDnsResolver.Scope.fromValue(0xf));
    }

    @Test
    void rfcRule2_prefersMatchingScope() throws Exception {
        final InetAddress aDst = inet("2001:db8::1");
        final InetAddress bDst = inet("2001:db8::2");

        // A matches scope (GLOBAL == GLOBAL); B mismatches (GLOBAL != LINK_LOCAL)
        final InetAddress aSrc = inet("2001:db8::abcd");
        final InetAddress bSrc = inet("fe80::1");

        delegate.add("t.example", bDst, aDst);

        final AddressSelectingDnsResolver r =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.DEFAULT, sourceMap(aDst, aSrc, bDst, bSrc));

        final InetAddress[] out = r.resolve("t.example");
        assertEquals(Arrays.asList(aDst, bDst), Arrays.asList(out));
    }

    @Test
    void rfcRule5_prefersMatchingLabel() throws Exception {
        final InetAddress aDst = inet("2001:db8::1");        // label 5 (2001::/32)
        final InetAddress bDst = inet("2001:db8::2");        // label 5

        final InetAddress aSrc = inet("2001:db8::abcd");     // label 5 -> matches A
        final InetAddress bSrc = inet("::ffff:192.0.2.1");   // label 4 -> does not match B

        delegate.add("t.example", bDst, aDst);

        final AddressSelectingDnsResolver r =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.DEFAULT, sourceMap(aDst, aSrc, bDst, bSrc));

        final InetAddress[] out = r.resolve("t.example");
        assertEquals(Arrays.asList(aDst, bDst), Arrays.asList(out));
    }

    @Test
    void rfcRule6_prefersHigherPrecedence() throws Exception {
        final InetAddress aDst = inet("::1");            // precedence 50 (policy ::1)
        final InetAddress bDst = inet("2001:db8::1");    // precedence 5  (policy 2001::/32)

        final InetAddress aSrc = inet("::1");
        final InetAddress bSrc = inet("2001:db8::abcd");

        delegate.add("t.example", bDst, aDst);

        final AddressSelectingDnsResolver r =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.DEFAULT, sourceMap(aDst, aSrc, bDst, bSrc));

        final InetAddress[] out = r.resolve("t.example");
        assertEquals(Arrays.asList(aDst, bDst), Arrays.asList(out));
    }

    @Test
    void rfcRule8_prefersSmallerScope_whenPrecedenceAndLabelTie() throws Exception {
        // Both fall to ::/0 policy -> precedence 40, label 1, but different scopes.
        final InetAddress aDst = inet("fe80::1");  // LINK_LOCAL scope (0x2)
        final InetAddress bDst = inet("2003::1");  // GLOBAL scope     (0xe)

        final InetAddress aSrc = inet("fe80::2");  // LINK_LOCAL, label 1
        final InetAddress bSrc = inet("2003::2");  // GLOBAL, label 1

        delegate.add("t.example", bDst, aDst);

        final AddressSelectingDnsResolver r =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.DEFAULT, sourceMap(aDst, aSrc, bDst, bSrc));

        final InetAddress[] out = r.resolve("t.example");
        assertEquals(Arrays.asList(aDst, bDst), Arrays.asList(out));
    }

    @Test
    void rfcRule9_prefersLongestMatchingPrefix_ipv6Only() throws Exception {
        // Both in same policy (::/0 -> prec 40, label 1), same scope (GLOBAL).
        // A's source shares a longer prefix with its destination than B's, even
        // after capping at a realistic /48 source prefix (RFC 6724 §2.2) — so this
        // is not just a same-subnet tie, A genuinely has more matching bits within
        // what the source's own prefix allows.
        final InetAddress aDst = inet("2003::1");
        final InetAddress bDst = inet("2003:0:1::1");

        // Capped at /48: aSrc/aDst share 48 bits (same /48); bSrc/bDst share 13.
        final InetAddress aSrc = inet("2003::2");
        final InetAddress bSrc = inet("2004::2");

        delegate.add("t.example", bDst, aDst);

        final AddressSelectingDnsResolver r = new AddressSelectingDnsResolver(
                delegate, ProtocolFamilyPreference.DEFAULT, sourceMap(aDst, aSrc, bDst, bSrc),
                fixedPrefixLength(48));

        final InetAddress[] out = r.resolve("t.example");
        assertEquals(Arrays.asList(aDst, bDst), Arrays.asList(out));
    }

    @Test
    void rfcRule9_commonPrefixCappedAtSourcePrefix_tiesPreserveDnsOrder_ipv6() throws Exception {
        // Both destinations are in the same /64 as their (same) source, differing
        // only in interface-ID bits beyond the source's own /64 prefix (RFC 6724
        // §2.2): CommonPrefixLen must cap at 64 for both, so they tie and Rule 10
        // preserves the delegate's original (DNS) order — not a reorder by raw
        // bit-width, which would spuriously prefer whichever shares more bits
        // past the subnet boundary.
        final InetAddress aDst = inet("2003::1:0:0:10");
        final InetAddress bDst = inet("2003::1:0:0:2");
        final InetAddress src = inet("2003::1:0:0:1");

        delegate.add("t.example", aDst, bDst);

        final AddressSelectingDnsResolver r = new AddressSelectingDnsResolver(
                delegate, ProtocolFamilyPreference.DEFAULT, sourceMap(aDst, src, bDst, src),
                fixedPrefixLength(64));

        final InetAddress[] out = r.resolve("t.example");
        assertEquals(Arrays.asList(aDst, bDst), Arrays.asList(out));
    }

    @Test
    void rfcRule9_commonPrefixCappedAtSourcePrefix_tiesPreserveDnsOrder_ipv4() throws Exception {
        // Same idea for IPv4: both destinations share the source's /24 subnet but
        // differ in host bits, so DNS round-robin order must survive instead of
        // being overridden by whichever host address happens to share more bits.
        final InetAddress aDst = inet("10.0.1.10");
        final InetAddress bDst = inet("10.0.1.60");
        final InetAddress src = inet("10.0.1.50");

        delegate.add("t.example", aDst, bDst);

        final AddressSelectingDnsResolver r = new AddressSelectingDnsResolver(
                delegate, ProtocolFamilyPreference.DEFAULT, sourceMap(aDst, src, bDst, src),
                fixedPrefixLength(24));

        final InetAddress[] out = r.resolve("t.example");
        assertEquals(Arrays.asList(aDst, bDst), Arrays.asList(out));
    }

    private static AddressSelectingDnsResolver.SourcePrefixLengthResolver fixedPrefixLength(final int len) {
        return source -> len;
    }

    @Test
    void classifyScope_unknownMulticastNibble_fallsBackToGlobal() throws Exception {
        // ff03::1 -> scope nibble 0x3, which is not a known Scope constant.
        assertEquals(AddressSelectingDnsResolver.Scope.GLOBAL,
                AddressSelectingDnsResolver.classifyScope(inet("ff03::1")));
    }

    @Test
    void addr_fmt_simpleName() throws Exception {
        assertEquals("null", AddressSelectingDnsResolver.addr(null));

        final InetAddress v4 = inet("192.0.2.1");
        final InetAddress v6 = inet("2001:db8::1");

        assertEquals("IPv4(" + v4.getHostAddress() + ")", AddressSelectingDnsResolver.addr(v4));
        assertEquals("IPv6(" + v6.getHostAddress() + ")", AddressSelectingDnsResolver.addr(v6));

        assertEquals(Arrays.asList("IPv6(" + v6.getHostAddress() + ")", "IPv4(" + v4.getHostAddress() + ")"),
                AddressSelectingDnsResolver.fmt(new InetAddress[]{v6, v4}));

        assertEquals(Arrays.asList("IPv4(" + v4.getHostAddress() + ")", "IPv6(" + v6.getHostAddress() + ")"),
                AddressSelectingDnsResolver.fmt(Arrays.asList(v4, v6)));

    }

    private static InetAddress inet(final String s) {
        try {
            return InetAddress.getByName(s);
        } catch (final UnknownHostException ex) {
            throw new AssertionError(ex);
        }
    }

    private static AddressSelectingDnsResolver.SourceAddressResolver sourceMap(
            final InetAddress aDst, final InetAddress aSrc,
            final InetAddress bDst, final InetAddress bSrc) {
        return (final InetSocketAddress dest) -> {
            final InetAddress d = dest.getAddress();
            if (aDst.equals(d)) {
                return aSrc;
            }
            if (bDst.equals(d)) {
                return bSrc;
            }
            return null;
        };
    }


    @Test
    void ipv4Only_filtersSingleV6Address() throws Exception {
        // Regression: a single IPv6 address must still be filtered when IPV4_ONLY is set.
        final InetAddress v6 = inet("2001:db8::1");
        delegate.add("v6.example", v6);

        final AddressSelectingDnsResolver r =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.IPV4_ONLY, NO_SOURCE_ADDR);

        assertThrows(UnknownHostException.class, () -> r.resolve("v6.example"));
    }

    @Test
    void resolveHostPort_appliesRfc6724Ordering() throws Exception {
        final InetAddress v4 = inet("192.0.2.1");
        final InetAddress v6 = inet("2001:db8::1");

        delegate.add("dual.example", v4, v6);

        final AddressSelectingDnsResolver r =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.IPV4_ONLY, NO_SOURCE_ADDR);

        final List<InetSocketAddress> out = r.resolve("dual.example", 443);
        assertEquals(1, out.size());
        assertEquals(new InetSocketAddress(v4, 443), out.get(0));
    }

    @Test
    void resolveHostPort_throwsWhenAllFilteredOut() throws Exception {
        // Only IPv6 addresses, but IPV4_ONLY is requested.
        // Must throw rather than returning an unresolved address that bypasses filtering.
        delegate.add("v6.example", inet("2001:db8::1"));

        final AddressSelectingDnsResolver r =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.IPV4_ONLY, NO_SOURCE_ADDR);

        assertThrows(UnknownHostException.class, () -> r.resolve("v6.example", 443));
    }

    @Test
    void rfcRule9_appliesToIpv4Pairs() throws Exception {
        // Both IPv4, same policy (::ffff:0:0/96 → prec 35, label 4), same scope (GLOBAL).
        // Rule 9 should prefer the address whose source shares a longer prefix.
        final InetAddress aDst = inet("192.0.2.1");
        final InetAddress bDst = inet("203.0.113.1");

        // aSrc shares 24 bits with aDst (192.0.2.x); bSrc shares only 8 bits with bDst (203 vs 203).
        final InetAddress aSrc = inet("192.0.2.100");
        final InetAddress bSrc = inet("203.0.114.1");

        delegate.add("t.example", bDst, aDst);

        final AddressSelectingDnsResolver r =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.DEFAULT, sourceMap(aDst, aSrc, bDst, bSrc));

        final InetAddress[] out = r.resolve("t.example");
        // aSrc-aDst share more prefix bits than bSrc-bDst, so aDst should come first.
        assertEquals(Arrays.asList(aDst, bDst), Arrays.asList(out));
    }

    @Test
    void ipAttrOf_ipv4_usesMappedPolicyPrecedenceAndLabel() throws Exception {
        // RFC 6724 §2.1 Table 2: ::ffff:0:0/96 -> precedence 35, label 4.
        //
        // Regression: InetAddress.getByName("::ffff:0:0") collapses to a 4-byte
        // Inet4Address on the JDK. Network#contains() always compares IPv4
        // destinations against their 16-byte mapped form, so a policy entry built
        // from that collapsed 4-byte address never matched any IPv4 destination;
        // every IPv4 address silently fell through to the ::/0 default
        // (precedence 40, label 1) instead.
        final AddressSelectingDnsResolver.Attr attr =
                AddressSelectingDnsResolver.ipAttrOf(inet("192.0.2.1"));
        assertEquals(35, attr.precedence);
        assertEquals(4, attr.label);
    }

    @Test
    void networkContains_ipv6Prefix32() throws Exception {
        final AddressSelectingDnsResolver.Network p32 =
                new AddressSelectingDnsResolver.Network(inet("2001:db8::").getAddress(), 32);

        assertTrue(p32.contains(inet("2001:db8::1")));
        assertTrue(p32.contains(inet("2001:db8:ffff::1")));

        assertFalse(p32.contains(inet("2001:db9::1")));
        assertFalse(p32.contains(inet("2000:db8::1")));
    }

    @Test
    void networkContains_ipv4IsMatchedViaV4MappedWhenPrefixIsV6Mapped96() throws Exception {
        // Build ::ffff:0:0 as raw 16 bytes. Do NOT use InetAddress.getByName(..) here:
        // the JDK may normalize it to an Inet4Address, yielding a 4-byte array.
        final byte[] v6mapped = new byte[16];
        v6mapped[10] = (byte) 0xff;
        v6mapped[11] = (byte) 0xff;

        final AddressSelectingDnsResolver.Network p96 =
                new AddressSelectingDnsResolver.Network(v6mapped, 96);

        assertTrue(p96.contains(inet("192.0.2.1")));
        assertTrue(p96.contains(inet("203.0.113.10")));

        // A pure IPv6 address must not match that v4-mapped prefix.
        assertFalse(p96.contains(inet("2001:db8::1")));
    }

    @Test
    void networkContains_nonByteAlignedPrefix7Boundary() throws Exception {
        // fc00::/7 (ULA) is in the policy table.
        final AddressSelectingDnsResolver.Network p7 =
                new AddressSelectingDnsResolver.Network(inet("fc00::").getAddress(), 7);

        // Inside /7: fc00:: and fd00:: (since /7 covers fc00..fdff)
        assertTrue(p7.contains(inet("fc00::1")));
        assertTrue(p7.contains(inet("fd00::1")));

        // Just outside /7: fe00:: (top bits 11111110 vs 1111110x)
        assertFalse(p7.contains(inet("fe00::1")));
        assertFalse(p7.contains(inet("2001:db8::1")));
    }

    @Test
    void sortByRfc6724_globalIpv6IsPreferredOverGlobalIpv4() throws Exception {
        // End-to-end regression for the ::ffff:0:0/96 construction bug: a plain global
        // IPv4 destination (precedence 35) must rank behind a plain global IPv6
        // destination (precedence 40, the ::/0 default), not ahead of it as it would
        // when the broken policy entry silently fell through to precedence 40 too.
        final InetAddress v4Dst = inet("192.0.2.1");
        final InetAddress v6Dst = inet("2003::1");

        final InetAddress v4Src = inet("192.0.2.100");
        final InetAddress v6Src = inet("2003::2");

        delegate.add("t.example", v4Dst, v6Dst);

        final AddressSelectingDnsResolver r = new AddressSelectingDnsResolver(
                delegate, ProtocolFamilyPreference.DEFAULT, sourceMap(v6Dst, v6Src, v4Dst, v4Src));

        final InetAddress[] out = r.resolve("t.example");
        assertEquals(Arrays.asList(v6Dst, v4Dst), Arrays.asList(out));
    }

    @Test
    void preferIpv4_groupsAllV4First_preservingRelativeOrder_whenRfcSortIsNoop() throws Exception {
        final InetAddress v4a = inet("192.0.2.1");
        final InetAddress v6a = inet("2001:db8::1");
        final InetAddress v4b = inet("203.0.113.10");
        final InetAddress v6b = inet("2001:db8::2");

        delegate.add("dual.example", v6a, v4a, v6b, v4b);

        final AddressSelectingDnsResolver preferV4 =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.PREFER_IPV4, NO_SOURCE_ADDR);

        final InetAddress[] out = preferV4.resolve("dual.example");
        assertEquals(Arrays.asList(v4a, v4b, v6a, v6b), Arrays.asList(out));
        assertInstanceOf(Inet4Address.class, out[0]);
    }

    @Test
    void rfcRule1_bothSourcesUnusable_orderIsPreserved() throws Exception {
        final InetAddress aDst = inet("2001:db8::1");
        final InetAddress bDst = inet("2001:db8::2");

        delegate.add("t.example", aDst, bDst);

        // Every destination resolves to no usable source address.
        final AddressSelectingDnsResolver r =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.DEFAULT, NO_SOURCE_ADDR);

        final InetAddress[] out = r.resolve("t.example");
        assertEquals(Arrays.asList(aDst, bDst), Arrays.asList(out));
    }

    @Test
    void rfcRule1_onlyOneSourceUsable_usableDestinationIsPreferred() throws Exception {
        final InetAddress usableDst = inet("2001:db8::1");
        final InetAddress unusableDst = inet("2001:db8::2");

        delegate.add("t.example", unusableDst, usableDst);

        final AddressSelectingDnsResolver.SourceAddressResolver onlyOneUsable = dest -> {
            if (usableDst.equals(dest.getAddress())) {
                return inet("2001:db8::abcd");
            }
            return null;
        };

        final AddressSelectingDnsResolver r =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.DEFAULT, onlyOneUsable);

        final InetAddress[] out = r.resolve("t.example");
        assertEquals(Arrays.asList(usableDst, unusableDst), Arrays.asList(out));
    }

    @Test
    void resolve_singleCandidateOfRequestedFamily_returnedWithoutRfcSort() throws Exception {
        // The RFC6724 sort (and therefore source-address inference) must be skipped
        // entirely when only one candidate is left after filtering.
        final InetAddress v4 = inet("192.0.2.1");
        delegate.add("single.example", v4);

        final AddressSelectingDnsResolver.SourceAddressResolver failing = dest -> {
            throw new AssertionError("source inference must not run for a single candidate");
        };

        final AddressSelectingDnsResolver r =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.DEFAULT, failing);

        final InetAddress[] out = r.resolve("single.example");
        assertEquals(1, out.length);
        assertEquals(v4, out[0]);
    }

    @Test
    void resolve_doesNotMutateDelegateReturnedArray() throws Exception {
        final InetAddress v6 = inet("2001:db8::1");
        final InetAddress v4 = inet("192.0.2.1");
        final InetAddress[] delegateArray = {v6, v4};
        delegate.add("dual.example", delegateArray);

        final InetAddress[] before = delegateArray.clone();

        final AddressSelectingDnsResolver r =
                new AddressSelectingDnsResolver(delegate, ProtocolFamilyPreference.INTERLEAVE, NO_SOURCE_ADDR);
        r.resolve("dual.example");

        assertArrayEquals(before, delegateArray, "resolve() must not mutate the delegate's array in place");
    }

    @Test
    void commonPrefixLen_cappedAtSourcesOwnNetworkPrefixLength() throws Exception {
        // RFC 6724 §2.2: CommonPrefixLen(S, D) is capped at the length of S's own
        // prefix, not the full address width. A destination built from a copy of a
        // real local source address's own bits matches it bit-for-bit (the full
        // address width, raw), but the interface-ID portion beyond the source's own
        // prefix length must not count.
        final InterfaceAddress real = findRealInterfaceAddress();
        Assumptions.assumeTrue(real != null, "no usable local interface address found in this environment");

        final InetAddress src = real.getAddress();
        final int prefixLen = real.getNetworkPrefixLength();
        final InetAddress dst = InetAddress.getByAddress(src.getAddress().clone());

        assertEquals(prefixLen, AddressSelectingDnsResolver.commonPrefixLen(src, dst, prefixLen));
    }

    @Test
    void commonPrefixLen_fallsBackToUncappedWhenPrefixLengthIsUnknown() throws Exception {
        // -1 means the caller could not determine a real prefix length to cap at
        // (e.g. the source is not bound to any local interface); the uncapped
        // bit-wise common prefix is used instead.
        final InetAddress src = inet("2003::2");
        final InetAddress dst = inet("2003::1");

        assertEquals(126, AddressSelectingDnsResolver.commonPrefixLen(src, dst, -1));
    }

    @Test
    void rule9_realLocalSource_sameSubnetDestinationsTie_viaDefaultPrefixLookup() throws Exception {
        // End-to-end regression: commonPrefixLen_cappedAtSourcesOwnNetworkPrefixLength
        // above calls commonPrefixLen() directly with an externally-computed prefix
        // length, so it never exercises the real sourcePrefixLength(InetAddress)
        // lookup wired through sortByRfc6724(). This test does, via the real
        // DEFAULT_SOURCE_PREFIX_LENGTH_RESOLVER (no fixedPrefixLength(..) override
        // and no sourcePrefixLengthResolver override): if that lookup silently
        // returned -1 for every source, aDst (an exact copy of src, uncapped common
        // prefix = full width) would strictly outrank bDst (differs at the very
        // first host bit, uncapped common prefix = prefixLen) and the delegate's
        // order would be reordered instead of tying and being preserved.
        final InterfaceAddress real = findRealInterfaceAddress();
        Assumptions.assumeTrue(real != null, "no usable local interface address found in this environment");

        final InetAddress src = real.getAddress();
        final int prefixLen = real.getNetworkPrefixLength();
        final int fullWidth = src instanceof Inet6Address ? 128 : 32;
        Assumptions.assumeTrue(fullWidth - prefixLen >= 1, "source has no spare host bits in this environment");

        final InetAddress aDst = InetAddress.getByAddress(src.getAddress().clone());
        final byte[] bBytes = src.getAddress().clone();
        bBytes[prefixLen / 8] ^= (byte) (0x80 >>> (prefixLen % 8));
        final InetAddress bDst = InetAddress.getByAddress(bBytes);

        // bDst first: under the real (capped) lookup both tie at prefixLen and this
        // order is preserved; under the mutant (-1, uncapped) aDst's far higher raw
        // common-prefix count would strictly win and reorder to [aDst, bDst].
        delegate.add("t.example", bDst, aDst);

        final AddressSelectingDnsResolver r = new AddressSelectingDnsResolver(
                delegate, ProtocolFamilyPreference.DEFAULT, sourceMap(aDst, src, bDst, src));

        final InetAddress[] out = r.resolve("t.example");
        assertEquals(Arrays.asList(bDst, aDst), Arrays.asList(out));
    }

    private static InterfaceAddress findRealInterfaceAddress() throws SocketException {
        final Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
        while (ifaces.hasMoreElements()) {
            for (final InterfaceAddress ia : ifaces.nextElement().getInterfaceAddresses()) {
                final InetAddress addr = ia.getAddress();
                final int fullWidth = addr instanceof Inet6Address ? 128 : 32;
                if (ia.getNetworkPrefixLength() > 0 && ia.getNetworkPrefixLength() < fullWidth) {
                    return ia;
                }
            }
        }
        return null;
    }

    @Test
    void resolveHostPort_consultsDelegatesOwnTwoArgOverride() throws Exception {
        // AddressSelectingDnsResolver's resolve(host, port) must call
        // delegate.resolve(host, port), not its own resolve(host): a delegate that
        // overrides the 2-argument method to return candidates the 1-argument method
        // alone would not otherwise be silently bypassed.
        final InetAddress v4 = inet("192.0.2.1");
        final InetAddress v6 = inet("2001:db8::1");

        final DnsResolver delegateWithOwnTwoArgOverride = new DnsResolver() {
            @Override
            public InetAddress[] resolve(final String host) {
                return new InetAddress[] {v6};
            }

            @Override
            public String resolveCanonicalHostname(final String host) {
                return host;
            }

            @Override
            public List<InetSocketAddress> resolve(final String host, final int port) {
                // Distinct, non-requested ports: proves the result is the delegate's
                // own entries, not ones reconstructed from the requested port.
                return Arrays.asList(new InetSocketAddress(v6, 8443), new InetSocketAddress(v4, 9443));
            }
        };

        final AddressSelectingDnsResolver r = new AddressSelectingDnsResolver(
                delegateWithOwnTwoArgOverride, ProtocolFamilyPreference.DEFAULT, NO_SOURCE_ADDR);

        final List<InetSocketAddress> out = r.resolve("dual.example", 443);
        assertEquals(2, out.size());
        assertEquals(new InetSocketAddress(v6, 8443), out.get(0));
        assertEquals(new InetSocketAddress(v4, 9443), out.get(1));
    }

    @Test
    void resolveHostPort_preservesDelegatesDistinctPortsPerAddress() throws Exception {
        // Regression: resolve(host, port) must not reconstruct InetSocketAddress
        // entries from the requested port, overwriting whatever port the delegate
        // actually assigned to each address (e.g. via SRV-style per-target ports).
        final InetAddress v4a = inet("192.0.2.1");
        final InetAddress v4b = inet("192.0.2.2");

        final DnsResolver delegateWithDistinctPorts = new DnsResolver() {
            @Override
            public InetAddress[] resolve(final String host) {
                return new InetAddress[] {v4a, v4b};
            }

            @Override
            public String resolveCanonicalHostname(final String host) {
                return host;
            }

            @Override
            public List<InetSocketAddress> resolve(final String host, final int port) {
                return Arrays.asList(new InetSocketAddress(v4a, 9443), new InetSocketAddress(v4b, 9444));
            }
        };

        final AddressSelectingDnsResolver r = new AddressSelectingDnsResolver(
                delegateWithDistinctPorts, ProtocolFamilyPreference.DEFAULT, NO_SOURCE_ADDR);

        final List<InetSocketAddress> out = r.resolve("multi.example", 443);
        assertEquals(2, out.size());
        assertTrue(out.contains(new InetSocketAddress(v4a, 9443)));
        assertTrue(out.contains(new InetSocketAddress(v4b, 9444)));
    }

    @Test
    void resolveHostPort_singleCandidate_keepsDelegatesPort() throws Exception {
        // A single candidate must bypass sortByRfc6724() entirely (see
        // resolve_singleCandidateOfRequestedFamily_returnedWithoutRfcSort), and that
        // short-circuit must return the delegate's own entry unmodified rather than
        // rebuilding an InetSocketAddress from the requested port.
        final InetAddress v4 = inet("192.0.2.1");

        final DnsResolver delegateWithOwnPort = new DnsResolver() {
            @Override
            public InetAddress[] resolve(final String host) {
                return new InetAddress[] {v4};
            }

            @Override
            public String resolveCanonicalHostname(final String host) {
                return host;
            }

            @Override
            public List<InetSocketAddress> resolve(final String host, final int port) {
                return Arrays.asList(new InetSocketAddress(v4, 9443));
            }
        };

        final AddressSelectingDnsResolver r = new AddressSelectingDnsResolver(
                delegateWithOwnPort, ProtocolFamilyPreference.DEFAULT, NO_SOURCE_ADDR);

        final List<InetSocketAddress> out = r.resolve("single.example", 443);
        assertEquals(1, out.size());
        assertEquals(new InetSocketAddress(v4, 9443), out.get(0));
    }

    @Test
    void resolveHostPort_appliesRfc6724Ordering_whenDelegateOrderDiffersFromRfcOrder() throws Exception {
        // Regression: resolveHostPort_appliesRfc6724Ordering (above) uses IPV4_ONLY
        // with a single result, so it never proves sortByRfc6724() actually runs on
        // the two-argument path. Here the delegate returns IPv4 before IPv6, with
        // distinct, trackable ports, and DEFAULT preference should still rank the
        // global IPv6 destination first (precedence 40 vs 35) while preserving each
        // address's own port through the re-attach step.
        final InetAddress v4 = inet("192.0.2.1");
        final InetAddress v6 = inet("2003::1");
        final InetAddress v4Src = inet("192.0.2.100");
        final InetAddress v6Src = inet("2003::2");

        final DnsResolver delegateWrongOrder = new DnsResolver() {
            @Override
            public InetAddress[] resolve(final String host) {
                return new InetAddress[] {v4, v6};
            }

            @Override
            public String resolveCanonicalHostname(final String host) {
                return host;
            }

            @Override
            public List<InetSocketAddress> resolve(final String host, final int port) {
                return Arrays.asList(new InetSocketAddress(v4, 9443), new InetSocketAddress(v6, 8443));
            }
        };

        final AddressSelectingDnsResolver r = new AddressSelectingDnsResolver(
                delegateWrongOrder, ProtocolFamilyPreference.DEFAULT, sourceMap(v6, v6Src, v4, v4Src));

        final List<InetSocketAddress> out = r.resolve("dual.example", 443);
        assertEquals(Arrays.asList(new InetSocketAddress(v6, 8443), new InetSocketAddress(v4, 9443)), out);
    }

    @Test
    void resolveHostPort_throwsWhenDelegateReturnsOnlyUnresolvedEntry() throws Exception {
        // Regression: DnsResolver.resolve(String) may return null by contract, and
        // the default resolve(String, int) then yields a single createUnresolved(..)
        // entry. That entry must be filtered out like any other unusable candidate,
        // not returned as-is: an unresolved address would let downstream code
        // re-resolve the hostname itself and bypass family filtering entirely.
        final DnsResolver unresolvingDelegate = new DnsResolver() {
            @Override
            public InetAddress[] resolve(final String host) {
                return null;
            }

            @Override
            public String resolveCanonicalHostname(final String host) {
                return host;
            }

            @Override
            public List<InetSocketAddress> resolve(final String host, final int port) {
                return Arrays.asList(InetSocketAddress.createUnresolved(host, port));
            }
        };

        final AddressSelectingDnsResolver r = new AddressSelectingDnsResolver(
                unresolvingDelegate, ProtocolFamilyPreference.DEFAULT, NO_SOURCE_ADDR);

        assertThrows(UnknownHostException.class, () -> r.resolve("unresolved.example", 443));
    }

    @Test
    void resolveHostPort_dropsUnresolvedEntry_whenMixedWithResolved() throws Exception {
        // Regression: a delegate's two-arg result mixing an unresolved entry with a
        // genuinely resolved one must drop the former and return the latter, not
        // throw a NullPointerException trying to classify the unresolved entry.
        final InetAddress v4 = inet("192.0.2.1");

        final DnsResolver mixedDelegate = new DnsResolver() {
            @Override
            public InetAddress[] resolve(final String host) {
                return new InetAddress[] {v4};
            }

            @Override
            public String resolveCanonicalHostname(final String host) {
                return host;
            }

            @Override
            public List<InetSocketAddress> resolve(final String host, final int port) {
                return Arrays.asList(InetSocketAddress.createUnresolved("other.example", port),
                        new InetSocketAddress(v4, port));
            }
        };

        final AddressSelectingDnsResolver r = new AddressSelectingDnsResolver(
                mixedDelegate, ProtocolFamilyPreference.DEFAULT, NO_SOURCE_ADDR);

        final List<InetSocketAddress> out = r.resolve("mixed.example", 443);
        assertEquals(Arrays.asList(new InetSocketAddress(v4, 443)), out);
    }

    @Test
    void resolveHostPort_preservesScopeIdOrdering_forSameAddressOnDifferentZones() throws Exception {
        // Regression: the sort-then-re-attach step previously matched sorted results
        // back to their original InetSocketAddress entries via InetAddress.equals(),
        // which ignores the IPv6 scope id. For the same link-local address on two
        // different zones, where only one zone has a usable source, that silently
        // attached the wrong zone's port to the preferred (Rule-1-usable) entry.
        final byte[] linkLocal = inet("fe80::1").getAddress();
        final InetAddress zone2 = Inet6Address.getByAddress(null, linkLocal, 2);
        final InetAddress zone3 = Inet6Address.getByAddress(null, linkLocal, 3);
        final InetAddress usableSrc = inet("fe80::100");

        final AddressSelectingDnsResolver.SourceAddressResolver onlyZone3Usable = dest -> {
            final InetAddress d = dest.getAddress();
            if (d instanceof Inet6Address && ((Inet6Address) d).getScopeId() == 3) {
                return usableSrc;
            }
            return null;
        };

        final DnsResolver delegateTwoZones = new DnsResolver() {
            @Override
            public InetAddress[] resolve(final String host) {
                return new InetAddress[] {zone2, zone3};
            }

            @Override
            public String resolveCanonicalHostname(final String host) {
                return host;
            }

            @Override
            public List<InetSocketAddress> resolve(final String host, final int port) {
                return Arrays.asList(new InetSocketAddress(zone2, 80), new InetSocketAddress(zone3, 81));
            }
        };

        final AddressSelectingDnsResolver r = new AddressSelectingDnsResolver(
                delegateTwoZones, ProtocolFamilyPreference.DEFAULT, onlyZone3Usable);

        final List<InetSocketAddress> out = r.resolve("linklocal.example", 443);
        assertEquals(Arrays.asList(new InetSocketAddress(zone3, 81), new InetSocketAddress(zone2, 80)), out);
    }

}
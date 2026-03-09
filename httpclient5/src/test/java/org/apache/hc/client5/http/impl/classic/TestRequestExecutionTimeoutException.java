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
package org.apache.hc.client5.http.impl.classic;

import java.net.SocketTimeoutException;

import org.apache.hc.core5.util.Deadline;
import org.apache.hc.core5.util.DeadlineTimeoutException;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RequestExecutionTimeoutException}, the exception thrown when
 * a request exceeds its configured execution deadline (HTTPCLIENT-1074).
 */
class TestRequestExecutionTimeoutException {

    // --- type hierarchy ---

    @Test
    void testIsSocketTimeoutException() {
        final RequestExecutionTimeoutException ex =
                RequestExecutionTimeoutException.from(Deadline.calculate(Timeout.ofSeconds(5)));
        Assertions.assertInstanceOf(SocketTimeoutException.class, ex);
    }

    // --- message ---

    @Test
    void testMessage() {
        final RequestExecutionTimeoutException ex =
                RequestExecutionTimeoutException.from(Deadline.calculate(Timeout.ofSeconds(5)));
        Assertions.assertEquals("Request execution deadline exceeded", ex.getMessage());
    }

    // --- cause wiring ---

    @Test
    void testFromExpiredDeadlineAddsDeadlineTimeoutExceptionAsCause() {
        // A past deadline → cause should be a DeadlineTimeoutException
        final Deadline expired = Deadline.fromUnixMilliseconds(System.currentTimeMillis() - 1000);
        final RequestExecutionTimeoutException ex = RequestExecutionTimeoutException.from(expired);

        Assertions.assertInstanceOf(DeadlineTimeoutException.class, ex.getCause());
    }

    @Test
    void testFromFutureDeadlineAlsoAddsDeadlineTimeoutExceptionAsCause() {
        // Even a future (non-max) deadline gets a DeadlineTimeoutException cause so the
        // deadline value is always diagnosable from the exception chain.
        final Deadline future = Deadline.calculate(Timeout.ofSeconds(60));
        final RequestExecutionTimeoutException ex = RequestExecutionTimeoutException.from(future);

        Assertions.assertInstanceOf(DeadlineTimeoutException.class, ex.getCause());
    }

    @Test
    void testFromMaxValueDeadlineHasNoCause() {
        // MAX_VALUE means "no deadline" → no DeadlineTimeoutException appended
        final RequestExecutionTimeoutException ex =
                RequestExecutionTimeoutException.from(Deadline.MAX_VALUE);
        Assertions.assertNull(ex.getCause());
    }

    @Test
    void testFromNullDeadlineHasNoCause() {
        final RequestExecutionTimeoutException ex = RequestExecutionTimeoutException.from(null);
        Assertions.assertNull(ex.getCause());
    }

    @Test
    void testFromDeadlineWithExplicitCauseUsesThatCause() {
        // When an explicit cause (e.g. a SocketTimeoutException that triggered the check)
        // is supplied it must be used directly, not replaced by a DeadlineTimeoutException.
        final Deadline expired = Deadline.fromUnixMilliseconds(System.currentTimeMillis() - 1000);
        final SocketTimeoutException trigger = new SocketTimeoutException("read timed out");

        final RequestExecutionTimeoutException ex =
                RequestExecutionTimeoutException.from(expired, trigger);

        Assertions.assertSame(trigger, ex.getCause());
    }

    @Test
    void testFromDeadlineWithNullExplicitCauseFallsBackToDeadlineTimeoutException() {
        // Passing null as the explicit cause must behave exactly like the single-arg factory.
        final Deadline expired = Deadline.fromUnixMilliseconds(System.currentTimeMillis() - 1000);

        final RequestExecutionTimeoutException ex =
                RequestExecutionTimeoutException.from(expired, null);

        Assertions.assertInstanceOf(DeadlineTimeoutException.class, ex.getCause());
    }

    // --- deadline snapshot ---

    @Test
    void testGetDeadlineIsNullWhenConstructedWithNullDeadline() {
        final RequestExecutionTimeoutException ex = RequestExecutionTimeoutException.from(null);
        Assertions.assertNull(ex.getDeadline());
    }

    @Test
    void testGetDeadlineValueMatchesOriginal() {
        final long epochMs = System.currentTimeMillis() + 5_000;
        final Deadline original = Deadline.fromUnixMilliseconds(epochMs);

        final RequestExecutionTimeoutException ex = RequestExecutionTimeoutException.from(original);

        Assertions.assertNotNull(ex.getDeadline());
        Assertions.assertEquals(epochMs, ex.getDeadline().getValue());
    }

    @Test
    void testGetDeadlineIsAFrozenCopyNotTheSameObject() {
        // The exception stores a frozen snapshot, not the live Deadline instance, so
        // mutations to the original cannot change the value captured in the exception.
        final long epochMs = System.currentTimeMillis() + 5_000;
        final Deadline original = Deadline.fromUnixMilliseconds(epochMs);

        final RequestExecutionTimeoutException ex = RequestExecutionTimeoutException.from(original);

        Assertions.assertNotSame(original, ex.getDeadline());
    }

    @Test
    void testGetDeadlineIsNotExpiredForFutureDeadline() {
        // A frozen snapshot of a future deadline must still report as not expired.
        final Deadline future = Deadline.calculate(Timeout.ofSeconds(60));
        final RequestExecutionTimeoutException ex = RequestExecutionTimeoutException.from(future);

        Assertions.assertFalse(ex.getDeadline().isExpired());
    }
}

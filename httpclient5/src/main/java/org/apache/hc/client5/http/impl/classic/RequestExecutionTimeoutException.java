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

import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.util.Deadline;
import org.apache.hc.core5.util.DeadlineTimeoutException;

@Internal
public final class RequestExecutionTimeoutException extends SocketTimeoutException {

    private static final long serialVersionUID = 1L;

    private final Deadline deadline;

    public static RequestExecutionTimeoutException from(final Deadline deadline) {
        return new RequestExecutionTimeoutException(copy(deadline), null);
    }

    static RequestExecutionTimeoutException from(final Deadline deadline, final Throwable cause) {
        return new RequestExecutionTimeoutException(copy(deadline), cause);
    }

    private static Deadline copy(final Deadline deadline) {
        return deadline != null ? Deadline.fromUnixMilliseconds(deadline.getValue()).freeze() : null;
    }

    private RequestExecutionTimeoutException(final Deadline deadline, final Throwable cause) {
        super("Request execution deadline exceeded");
        this.deadline = deadline;
        if (cause != null) {
            initCause(cause);
        } else if (deadline != null && !deadline.isMax()) {
            initCause(DeadlineTimeoutException.from(deadline));
        }
    }

    Deadline getDeadline() {
        return deadline;
    }
}
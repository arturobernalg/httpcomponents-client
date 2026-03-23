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
package org.apache.hc.client5.http.impl.async;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.util.Args;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link FutureCallback} decorator that dispatches callback invocations
 * to a user-supplied {@link Executor}.
 * <p>
 * If the executor rejects the task (for instance, because it has been shut down),
 * the callback is invoked directly on the calling thread as a safety fallback
 * so that the completion signal is never silently lost.
 * </p>
 *
 * @param <T> the result type of the callback.
 *
 * @since 5.7
 */
final class CallbackExecutorFutureCallback<T> implements FutureCallback<T> {

    private static final Logger LOG = LoggerFactory.getLogger(CallbackExecutorFutureCallback.class);

    private final FutureCallback<T> callback;
    private final Executor executor;

    CallbackExecutorFutureCallback(final FutureCallback<T> callback, final Executor executor) {
        this.callback = Args.notNull(callback, "Callback");
        this.executor = Args.notNull(executor, "Executor");
    }

    @Override
    public void completed(final T result) {
        try {
            executor.execute(() -> callback.completed(result));
        } catch (final RejectedExecutionException ex) {
            LOG.debug("Callback executor rejected task; invoking callback on the calling thread", ex);
            callback.completed(result);
        }
    }

    @Override
    public void failed(final Exception ex) {
        try {
            executor.execute(() -> callback.failed(ex));
        } catch (final RejectedExecutionException rejectedEx) {
            LOG.debug("Callback executor rejected task; invoking callback on the calling thread", rejectedEx);
            callback.failed(ex);
        }
    }

    @Override
    public void cancelled() {
        try {
            executor.execute(callback::cancelled);
        } catch (final RejectedExecutionException ex) {
            LOG.debug("Callback executor rejected task; invoking callback on the calling thread", ex);
            callback.cancelled();
        }
    }

}

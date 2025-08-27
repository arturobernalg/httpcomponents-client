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
package org.apache.hc.client5.http.impl;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;

import org.apache.hc.core5.annotation.Internal;

@Internal
public final class VirtualThreadSupport {

    private VirtualThreadSupport() {
    }

    public static boolean isAvailable() {
        try {
            Class.forName("java.lang.Thread$Builder$OfVirtual", false,
                    VirtualThreadSupport.class.getClassLoader());
            Class.forName("java.lang.Thread").getMethod("ofVirtual");
            return true;
        } catch (final Throwable t) {
            return false;
        }
    }

    /**
     * Prefer JDK’s per-task executors when present; otherwise fail.
     */
    public static ExecutorService newVirtualThreadPerTaskExecutor(final String namePrefix) {
        if (!isAvailable()) {
            throw new UnsupportedOperationException("Virtual threads are not available on this runtime");
        }
        try {
            final Class<?> executors = Class.forName("java.util.concurrent.Executors");
            try {
                final Method m = executors.getMethod("newThreadPerTaskExecutor", ThreadFactory.class);
                final ThreadFactory vtFactory = newVirtualThreadFactory(namePrefix);
                return (ExecutorService) m.invoke(null, vtFactory);
            } catch (final NoSuchMethodException ignore) {
                final Method m = executors.getMethod("newVirtualThreadPerTaskExecutor");
                return (ExecutorService) m.invoke(null);
            }
        } catch (final Throwable t) {
            throw new UnsupportedOperationException("Failed to initialize virtual thread per-task executor", t);
        }
    }

    public static ThreadFactory newVirtualThreadFactory(final String namePrefix) {
        if (!isAvailable()) {
            throw new UnsupportedOperationException("Virtual threads are not available on this runtime");
        }
        try {
            final Class<?> threadClass = Class.forName("java.lang.Thread");
            final Method ofVirtual = threadClass.getMethod("ofVirtual");
            Object builder = ofVirtual.invoke(null);
            final Class<?> ofVirtualClass = Class.forName("java.lang.Thread$Builder$OfVirtual");
            try {
                final Method name2 = ofVirtualClass.getMethod("name", String.class, long.class);
                builder = name2.invoke(builder, namePrefix, 0L);
            } catch (final NoSuchMethodException e1) {
                try {
                    final Method name1 = ofVirtualClass.getMethod("name", String.class);
                    builder = name1.invoke(builder, namePrefix);
                } catch (final NoSuchMethodException e2) {
                    // ignore
                }
            }
            final Method factoryMethod = ofVirtualClass.getMethod("factory");
            return (ThreadFactory) factoryMethod.invoke(builder);
        } catch (final Throwable t) {
            throw new UnsupportedOperationException("Failed to initialize virtual thread factory", t);
        }
    }
}

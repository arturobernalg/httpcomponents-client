/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information regarding
 * copyright ownership.  The ASF licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 * ====================================================================
 */
package org.apache.hc.client5.http.ws.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.ws.io.DuplexChannel;

/**
 * DuplexChannel over an IOSession (async reactor).
 * Notes:
 * - HttpCore 5.x provides IOSession#read/write for raw I/O on the session.
 * - If you’re on an older minor where read/write are not available, adapt
 * via the underlying ByteChannel: ((ByteChannel) iosession.channel()).read(dst).
 */
public final class IOSessionDuplexChannel implements DuplexChannel {

    private final IOSession session;

    public IOSessionDuplexChannel(final IOSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    @Override
    public int read(final ByteBuffer dst) throws IOException {
        // Preferred on modern HttpCore 5.x:
        return session.read(dst);
        // Fallback, if needed:
        // return ((java.nio.channels.ByteChannel) session.channel()).read(dst);
    }

    @Override
    public int write(final ByteBuffer src) throws IOException {
        // Preferred on modern HttpCore 5.x:
        return session.write(src);
        // Fallback, if needed:
        // return ((java.nio.channels.ByteChannel) session.channel()).write(src);
    }

    @Override
    public void flush() {
        // Reactor manages socket flush; nothing to do.
    }

    @Override
    public void close() {
        session.close(CloseMode.GRACEFUL);
    }

    @Override
    public boolean isOpen() {
        return session.isOpen();
    }
}

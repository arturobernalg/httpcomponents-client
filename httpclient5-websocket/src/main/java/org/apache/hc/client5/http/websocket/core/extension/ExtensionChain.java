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
package org.apache.hc.client5.http.websocket.core.extension;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple single-step chain; if multiple extensions are added they are applied in order.
 * Only the FIRST extension can contribute the RSV bit (RSV1 in practice).
 */
public final class ExtensionChain {
    private final List<Extension> exts = new ArrayList<>();

    public void add(final Extension e) {
        if (e != null) {
            exts.add(e);
        }
    }

    public boolean isEmpty() {
        return exts.isEmpty();
    }

    /**
     * Encode one fragment through the chain; note RSV flag for the first extension.
     */
    public Enc encode(final byte[] data, final boolean first, final boolean fin) {
        if (exts.isEmpty()) {
            return new Enc(data, false);
        }
        byte[] out = data;
        boolean setRsv1 = false;
        boolean firstExt = true;
        for (final Extension e : exts) {
            final Extension.Encoded res = e.encode(out, first, fin);
            out = res.payload;
            if (first && firstExt && res.setRsvOnFirst) {
                setRsv1 = true;
            }
            firstExt = false;
        }
        return new Enc(out, setRsv1);
    }

    /**
     * Decode a full message (reverse order if stacking).
     */
    public byte[] decode(final byte[] data) throws Exception {
        byte[] out = data;
        for (int i = exts.size() - 1; i >= 0; i--) {
            out = exts.get(i).decode(out);
        }
        return out;
    }

    /**
     * Result of a chain encode step.
     */
    public static final class Enc {
        public final byte[] payload;
        public final boolean setRsv1;

        public Enc(final byte[] payload, final boolean setRsv1) {
            this.payload = payload;
            this.setRsv1 = setRsv1;
        }
    }
}

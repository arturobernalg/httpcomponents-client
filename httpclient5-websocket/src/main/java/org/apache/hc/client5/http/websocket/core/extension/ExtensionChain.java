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
     * App-thread encoder chain.
     */
    public EncodeChain newEncodeChain() {
        final List<Extension.Encoder> encs = new ArrayList<>(exts.size());
        for (final Extension e : exts) {
            encs.add(e.newEncoder());
        }
        return new EncodeChain(encs);
    }

    /**
     * I/O-thread decoder chain.
     */
    public DecodeChain newDecodeChain() {
        final List<Extension.Decoder> decs = new ArrayList<>(exts.size());
        for (final Extension e : exts) {
            decs.add(e.newDecoder());
        }
        return new DecodeChain(decs);
    }

    // ----------------------

    public static final class EncodeChain {
        private final List<Extension.Encoder> encs;

        public EncodeChain(final List<Extension.Encoder> encs) {
            this.encs = encs;
        }

        /**
         * Encode one fragment through the chain; note RSV flag for the first extension.
         */
        public Enc encode(final byte[] data, final boolean first, final boolean fin) {
            if (encs.isEmpty()) {
                return new Enc(data, false);
            }
            byte[] out = data;
            boolean setRsv1 = false;
            boolean firstExt = true;
            for (final Extension.Encoder e : encs) {
                final Extension.Encoded res = e.encode(out, first, fin);
                out = res.payload;
                if (first && firstExt && res.setRsvOnFirst) {
                    setRsv1 = true;
                }
                firstExt = false;
            }
            return new Enc(out, setRsv1);
        }

        public static final class Enc {
            public final byte[] payload;
            public final boolean setRsv1;

            public Enc(final byte[] payload, final boolean setRsv1) {
                this.payload = payload;
                this.setRsv1 = setRsv1;
            }
        }
    }

    public static final class DecodeChain {
        private final List<Extension.Decoder> decs;

        public DecodeChain(final List<Extension.Decoder> decs) {
            this.decs = decs;
        }

        /**
         * Decode a full message (reverse order if stacking).
         */
        public byte[] decode(final byte[] data) throws Exception {
            byte[] out = data;
            for (int i = decs.size() - 1; i >= 0; i--) {
                out = decs.get(i).decode(out);
            }
            return out;
        }
    }
}


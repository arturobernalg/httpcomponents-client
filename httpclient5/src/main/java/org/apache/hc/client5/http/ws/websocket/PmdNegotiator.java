/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF)...
 * ====================================================================
 */
package org.apache.hc.client5.http.ws.websocket;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * RFC 7692 permessage-deflate negotiator.
 * Uses a tiny tokenizer (CSV + params + quoted-string) instead of ad-hoc splits.
 */
public final class PmdNegotiator {

    /**
     * PMD extension token (case-insensitive).
     */
    private static final String EXT_TOKEN = "permessage-deflate";

    /**
     * Known PMD parameters.
     */
    public enum Param {
        CLIENT_NO_CONTEXT_TAKEOVER("client_no_context_takeover"),
        SERVER_NO_CONTEXT_TAKEOVER("server_no_context_takeover"),
        CLIENT_MAX_WINDOW_BITS("client_max_window_bits"); // value is optional in requests, number in responses

        public final String token;

        Param(final String token) {
            this.token = token;
        }

        static Param fromToken(final String t) {
            final String x = t.toLowerCase(Locale.ROOT);
            for (final Param p : values()) {
                if (p.token.equals(x)) return p;
            }
            return null;
        }
    }

    private PmdNegotiator() {
    }

    /**
     * Builds the Sec-WebSocket-Extensions request value for permessage-deflate.
     * Returns {@code null} if PMD is not enabled.
     */
    public static String buildRequestHeader(final boolean enabled,
                                            final EnumSet<Param> params,
                                            final Integer clientMaxWindowBits) {
        if (!enabled) return null;
        final StringBuilder sb = new StringBuilder(EXT_TOKEN);
        if (params != null && params.contains(Param.CLIENT_NO_CONTEXT_TAKEOVER)) {
            sb.append("; ").append(Param.CLIENT_NO_CONTEXT_TAKEOVER.token);
        }
        if (params != null && params.contains(Param.SERVER_NO_CONTEXT_TAKEOVER)) {
            sb.append("; ").append(Param.SERVER_NO_CONTEXT_TAKEOVER.token);
        }
        if (clientMaxWindowBits != null) {
            sb.append("; ").append(Param.CLIENT_MAX_WINDOW_BITS.token).append('=').append(clientMaxWindowBits.intValue());
        }
        return sb.toString();
    }

    /**
     * Parses the Sec-WebSocket-Extensions response header values.
     *
     * @param extHdrs list of header values (each may contain comma-separated extensions)
     * @return negotiated result (accepted flag + params)
     */
    public static Result parseResponse(final List<String> extHdrs) {
        boolean accepted = false;
        final EnumSet<Param> params = EnumSet.noneOf(Param.class);
        Integer clientMaxWindowBits = null;

        if (extHdrs != null) {
            for (final String value : extHdrs) {
                if (value == null) continue;

                // Split by comma into extension items, honoring quoted-string
                for (final String item : Tokenizer.splitCsv(value)) {
                    if (item.isEmpty()) continue;

                    // First token up to ';' is the extension name
                    final int sc = Tokenizer.indexOfChar(item, ';');
                    final String extName = (sc >= 0 ? item.substring(0, sc) : item).trim();
                    if (!EXT_TOKEN.equalsIgnoreCase(extName)) {
                        continue; // some other extension
                    }
                    accepted = true;

                    // Parse parameters "a;b;c=1;d=\"quoted\""
                    final String paramTail = sc >= 0 ? item.substring(sc + 1) : "";
                    for (final String rawParam : Tokenizer.splitParams(paramTail)) {
                        if (rawParam.isEmpty()) continue;

                        final Tokenizer.NameValue nv = Tokenizer.splitNameValue(rawParam);
                        final Param p = Param.fromToken(nv.name);
                        if (p == null) {
                            continue; // unknown PMD param: ignore
                        }
                        switch (p) {
                            case CLIENT_NO_CONTEXT_TAKEOVER:
                                params.add(Param.CLIENT_NO_CONTEXT_TAKEOVER);
                                break;
                            case SERVER_NO_CONTEXT_TAKEOVER:
                                params.add(Param.SERVER_NO_CONTEXT_TAKEOVER);
                                break;
                            case CLIENT_MAX_WINDOW_BITS:
                                if (nv.value != null && !nv.value.isEmpty()) {
                                    try {
                                        clientMaxWindowBits = Integer.valueOf(nv.value);
                                    } catch (final Exception ignore) {
                                        // ignore malformed value
                                    }
                                } else {
                                    // parameter present without value: allowed by RFC on requests; servers should send a value.
                                    // We leave clientMaxWindowBits as-is.
                                }
                                break;
                        }
                    }
                }
            }
        }
        return new Result(accepted, params, clientMaxWindowBits);
    }

    /**
     * Negotiation outcome.
     */
    public static final class Result {
        private final boolean accepted;
        private final EnumSet<Param> params;
        private final Integer clientMaxWindowBits;

        public Result(final boolean accepted,
                      final EnumSet<Param> params,
                      final Integer clientMaxWindowBits) {
            this.accepted = accepted;
            this.params = params == null ? EnumSet.noneOf(Param.class) : EnumSet.copyOf(params);
            this.clientMaxWindowBits = clientMaxWindowBits;
        }

        public boolean isAccepted() {
            return accepted;
        }

        public EnumSet<Param> getParams() {
            return EnumSet.copyOf(params);
        }

        public Integer getClientMaxWindowBits() {
            return clientMaxWindowBits;
        }
    }

    private static final class Tokenizer {
        static int indexOfChar(final String s, final char ch) {
            boolean inQuotes = false;
            boolean escape = false;
            for (int i = 0; i < s.length(); i++) {
                final char c = s.charAt(i);
                if (escape) {
                    escape = false;
                    continue;
                }
                if (c == '\\') {
                    escape = true;
                    continue;
                }
                if (c == '"') {
                    inQuotes = !inQuotes;
                    continue;
                }
                if (!inQuotes && c == ch) return i;
            }
            return -1;
        }

        /**
         * Split comma-separated list honoring quoted strings.
         */
        static List<String> splitCsv(final String s) {
            final List<String> out = new ArrayList<>();
            final StringBuilder cur = new StringBuilder();
            boolean inQuotes = false;
            boolean escape = false;
            for (int i = 0; i < s.length(); i++) {
                final char c = s.charAt(i);
                if (escape) {
                    cur.append(c);
                    escape = false;
                    continue;
                }
                if (c == '\\') {
                    cur.append(c);
                    escape = true;
                    continue;
                }
                if (c == '"') {
                    cur.append(c);
                    inQuotes = !inQuotes;
                    continue;
                }
                if (!inQuotes && c == ',') {
                    out.add(cur.toString().trim());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            out.add(cur.toString().trim());
            return out;
        }

        /**
         * Split semicolon-separated params honoring quotes.
         */
        static List<String> splitParams(final String s) {
            final List<String> out = new ArrayList<>();
            final StringBuilder cur = new StringBuilder();
            boolean inQuotes = false;
            boolean escape = false;
            for (int i = 0; i < s.length(); i++) {
                final char c = s.charAt(i);
                if (escape) {
                    cur.append(c);
                    escape = false;
                    continue;
                }
                if (c == '\\') {
                    cur.append(c);
                    escape = true;
                    continue;
                }
                if (c == '"') {
                    cur.append(c);
                    inQuotes = !inQuotes;
                    continue;
                }
                if (!inQuotes && c == ';') {
                    final String token = cur.toString().trim();
                    if (!token.isEmpty()) out.add(token);
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            final String last = cur.toString().trim();
            if (!last.isEmpty()) out.add(last);
            return out;
        }

        static NameValue splitNameValue(final String p) {
            final int eq = indexOfChar(p, '=');
            if (eq < 0) {
                return new NameValue(p.trim().toLowerCase(Locale.ROOT), null);
            }
            final String name = p.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String val = p.substring(eq + 1).trim();
            val = unquote(val);
            return new NameValue(name, val);
        }

        static String unquote(final String v) {
            if (v == null) return null;
            if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
                final String inner = v.substring(1, v.length() - 1);
                final StringBuilder sb = new StringBuilder(inner.length());
                boolean escape = false;
                for (int i = 0; i < inner.length(); i++) {
                    final char c = inner.charAt(i);
                    if (escape) {
                        sb.append(c);
                        escape = false;
                        continue;
                    }
                    if (c == '\\') {
                        escape = true;
                        continue;
                    }
                    sb.append(c);
                }
                return sb.toString();
            }
            return v;
        }

        static final class NameValue {
            final String name;
            final String value;

            NameValue(final String n, final String v) {
                this.name = n;
                this.value = v;
            }
        }
    }
}

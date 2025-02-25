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

package org.apache.hc.client5.http.entity.mime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.net.PercentCodec;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.Asserts;

/**
 * Builder for individual {@link org.apache.hc.client5.http.entity.mime.FormBodyPart}s.
 *
 * @since 4.4
 */
public class FormBodyPartBuilder {

    private String name;
    private ContentBody body;
    private final Header header;
    private HttpMultipartMode mode;

    public static FormBodyPartBuilder create(final String name, final ContentBody body) {
        return new FormBodyPartBuilder(name, body);
    }

    public static FormBodyPartBuilder create() {
        return new FormBodyPartBuilder();
    }

    FormBodyPartBuilder(final String name, final ContentBody body) {
        this();
        this.name = name;
        this.body = body;
    }

    FormBodyPartBuilder() {
        this.header = new Header();
    }

    public FormBodyPartBuilder setName(final String name) {
        this.name = name;
        return this;
    }

    public FormBodyPartBuilder setBody(final ContentBody body) {
        this.body = body;
        return this;
    }

    /**
     * @since 4.6
     */
    public FormBodyPartBuilder addField(final String name, final String value, final List<NameValuePair> parameters) {
        Args.notNull(name, "Field name");
        this.header.addField(new MimeField(name, value, parameters));
        return this;
    }

    public FormBodyPartBuilder addField(final String name, final String value) {
        Args.notNull(name, "Field name");
        this.header.addField(new MimeField(name, value));
        return this;
    }

    public FormBodyPartBuilder setField(final String name, final String value) {
        Args.notNull(name, "Field name");
        this.header.setField(new MimeField(name, value));
        return this;
    }

    /**
     * Sets the multipart mode for this builder, determining how filenames are encoded in the
     * {@code Content-Disposition} header. The mode affects whether the {@code filename*} parameter
     * is included for non-ISO-8859-1 filenames.
     * <p>
     * In {@link HttpMultipartMode#LEGACY} mode, only the {@code filename} parameter is included
     * with the raw value, mimicking pre-RFC 7578 behavior. In {@link HttpMultipartMode#STRICT}
     * or {@link HttpMultipartMode#EXTENDED} modes, the {@code filename*} parameter is added
     * with RFC 5987 encoding for filenames containing non-ISO-8859-1 characters.
     * </p>
     * <p>
     * If {@code mode} is {@code null}, it defaults to {@link HttpMultipartMode#STRICT}.
     * </p>
     *
     * @param mode the {@link HttpMultipartMode} to use, or {@code null} for default behavior
     * @return this builder instance for method chaining
     * @since 5.5
     */
    public FormBodyPartBuilder setMode(final HttpMultipartMode mode) {
        this.mode = mode != null ? mode : HttpMultipartMode.STRICT;
        return this;
    }

    public FormBodyPartBuilder removeFields(final String name) {
        Args.notNull(name, "Field name");
        this.header.removeFields(name);
        return this;
    }

    /**
     * Determines whether the given string can be encoded in ISO-8859-1 without loss of data.
     * This is used to decide whether the {@code filename} parameter can be used as-is or if
     * the {@code filename*} parameter is needed for non-ISO-8859-1 characters.
     *
     * @param input the string to check, must not be {@code null}
     * @return {@code true} if the string can be encoded in ISO-8859-1, {@code false} otherwise
     * @since 5.5
     */
    private static boolean canEncodeToISO8859_1(final String input) {
        return StandardCharsets.ISO_8859_1.newEncoder().canEncode(input);
    }

    /**
     * Encodes the given filename according to RFC 5987, prefixing it with {@code UTF-8''} and
     * applying percent-encoding to non-ASCII characters. This is used for the {@code filename*}
     * parameter in the {@code Content-Disposition} header when non-ISO-8859-1 characters are present.
     *
     * @param filename the filename to encode, must not be {@code null}
     * @return the RFC 5987-encoded string, e.g., {@code UTF-8''example%20text}
     * @since 5.5
     */
    private static String encodeRFC5987(final String filename) {
        return "UTF-8''" + PercentCodec.RFC5987.encode(filename);
    }

    public FormBodyPart build() {
        Asserts.notBlank(this.name, "Name");
        Asserts.notNull(this.body, "Content body");
        final Header headerCopy = new Header();
        final List<MimeField> fields = this.header.getFields();
        for (final MimeField field: fields) {
            headerCopy.addField(field);
        }
        if (headerCopy.getField(MimeConsts.CONTENT_DISPOSITION) == null) {
            final List<NameValuePair> fieldParameters = new ArrayList<>();
            fieldParameters.add(new BasicNameValuePair(MimeConsts.FIELD_PARAM_NAME, this.name));
            if (this.body.getFilename() != null) {
                final String filename = this.body.getFilename();
                fieldParameters.add(new BasicNameValuePair(MimeConsts.FIELD_PARAM_FILENAME, filename));
                // Add filename* only if non-ISO-8859-1 and not in LEGACY mode
                if (mode != HttpMultipartMode.LEGACY && !canEncodeToISO8859_1(filename)) {
                    fieldParameters.add(new BasicNameValuePair(MimeConsts.FIELD_PARAM_FILENAME_START, encodeRFC5987(filename)));
                }
            }
            headerCopy.addField(new MimeField(MimeConsts.CONTENT_DISPOSITION, "form-data", fieldParameters));
        }
        if (headerCopy.getField(MimeConsts.CONTENT_TYPE) == null) {
            final ContentType contentType;
            if (body instanceof AbstractContentBody) {
                contentType = ((AbstractContentBody) body).getContentType();
            } else {
                contentType = null;
            }
            if (contentType != null) {
                headerCopy.addField(new MimeField(MimeConsts.CONTENT_TYPE, contentType.toString()));
            } else {
                final StringBuilder buffer = new StringBuilder();
                buffer.append(this.body.getMimeType()); // MimeType cannot be null
                if (this.body.getCharset() != null) { // charset may legitimately be null
                    buffer.append("; charset=");
                    buffer.append(this.body.getCharset());
                }
                headerCopy.addField(new MimeField(MimeConsts.CONTENT_TYPE, buffer.toString()));
            }
        }
        return new FormBodyPart(this.name, this.body, headerCopy);
    }

}

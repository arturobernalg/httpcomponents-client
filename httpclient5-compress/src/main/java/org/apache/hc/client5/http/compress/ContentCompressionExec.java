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

package org.apache.hc.client5.http.compress;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.client5.http.compress.entity.DecompressingEntity;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HeaderElement;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.message.BasicHeaderValueParser;
import org.apache.hc.core5.http.message.MessageSupport;
import org.apache.hc.core5.http.message.ParserCursor;
import org.apache.hc.core5.util.Args;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Contract(threading = ThreadingBehavior.STATELESS)
@Internal
public final class ContentCompressionExec implements ExecChainHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ContentCompressionExec.class);


    private final Header acceptEncoding;
    private final Function<String, Function<InputStream, InputStream>> decoderFunction;
    private final boolean ignoreUnknown;

    public ContentCompressionExec(final List<String> acceptEncoding,
                                  final Function<String, Function<InputStream, InputStream>> decoderFunction,
                                  final boolean ignoreUnknown) {
        this.acceptEncoding = MessageSupport.headerOfTokens(HttpHeaders.ACCEPT_ENCODING,
                acceptEncoding != null ? acceptEncoding : new ArrayList<>(CompressorFactory.INSTANCE.getInputStreamCompressorNames()));
        this.decoderFunction = decoderFunction != null ? decoderFunction : name -> CompressorFactory.INSTANCE.getCompressorInput(name, true);

        if (LOG.isDebugEnabled()) {
            CompressorFactory.INSTANCE.getInputStreamCompressorNames().forEach(name -> {
               LOG.debug("Available decoder: {}", name);
            });
        }

        this.ignoreUnknown = ignoreUnknown;
    }

    public ContentCompressionExec(final boolean ignoreUnknown) {
        this(null, null, ignoreUnknown);
    }

    public ContentCompressionExec() {
        this(null, null, true);
    }


    @Override
    public ClassicHttpResponse execute(
            final ClassicHttpRequest request,
            final ExecChain.Scope scope,
            final ExecChain chain) throws IOException, HttpException {
        Args.notNull(request, "HTTP request");
        Args.notNull(scope, "Scope");

        final HttpClientContext clientContext = scope.clientContext;
        final RequestConfig requestConfig = clientContext.getRequestConfigOrDefault();

        /* Signal support for Accept-Encoding transfer encodings. */
        if (!request.containsHeader(HttpHeaders.ACCEPT_ENCODING) && requestConfig.isContentCompressionEnabled()) {
            request.addHeader(acceptEncoding);
        }

        final ClassicHttpResponse response = chain.proceed(request, scope);

        final HttpEntity entity = response.getEntity();
        // entity can be null in case of 304 Not Modified, 204 No Content or similar
        // check for zero length entity.
        if (requestConfig.isContentCompressionEnabled() && entity != null && entity.getContentLength() != 0) {
            final String contentEncoding = entity.getContentEncoding();
            if (contentEncoding != null) {
                final ParserCursor cursor = new ParserCursor(0, contentEncoding.length());
                final HeaderElement[] codecs = BasicHeaderValueParser.INSTANCE.parseElements(contentEncoding, cursor);
                for (final HeaderElement codec : codecs) {
                    final String codecName = codec.getName().toLowerCase(Locale.ROOT);
                    final Function<InputStream, InputStream> decompress = decoderFunction.apply(contentEncoding.toLowerCase(Locale.ROOT));
                    if (decompress != null) {
                        response.setEntity(new DecompressingEntity(response.getEntity(), decompress));
                        response.removeHeaders(HttpHeaders.CONTENT_LENGTH);
                        response.removeHeaders(HttpHeaders.CONTENT_ENCODING);
                        response.removeHeaders(HttpHeaders.CONTENT_MD5);
                    } else {
                        if (!CompressionAlgorithm.IDENTITY.isSame(codecName) && !ignoreUnknown) {
                            throw new HttpException("Unsupported Content-Encoding: " + codec.getName());
                        }
                    }
                }
            }
        }
        return response;
    }

}

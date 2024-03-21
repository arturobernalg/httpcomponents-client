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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.function.Function;

import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.client5.http.impl.ChainElement;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.config.NamedElementChain;


public class CompressHttpClientBuilder extends HttpClientBuilder {


    private boolean contentCompressionDisabled;
    private LinkedHashMap<String, Function<InputStream, InputStream>> contentDecoderMap;

    public static CompressHttpClientBuilder create() {
        return new CompressHttpClientBuilder();
    }

    protected CompressHttpClientBuilder() {
        super();

    }

    public final CompressHttpClientBuilder setContentDecoder(
            final LinkedHashMap<String, Function<InputStream, InputStream>> contentDecoderMap) {
        this.contentDecoderMap = contentDecoderMap;
        return this;
    }

    public final HttpClientBuilder disableContentCompression() {
        contentCompressionDisabled = true;
        return this;
    }


    @Override
    protected void customizeExecChain(final NamedElementChain<ExecChainHandler> execChainDefinition) {
        if (!contentCompressionDisabled) {
            if (contentDecoderMap != null) {
                final Function<String, Function<InputStream, InputStream>> decoderFunction = encoding -> contentDecoderMap.getOrDefault(encoding, inputStream -> inputStream);
                execChainDefinition.addFirst(new ContentCompressionExec(new ArrayList<>(contentDecoderMap.keySet()), decoderFunction, true), ChainElement.COMPRESS.name());
            } else {
                execChainDefinition.addFirst(new ContentCompressionExec(), ChainElement.COMPRESS.name());
            }
        }
    }

}

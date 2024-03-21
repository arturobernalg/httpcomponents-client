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
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public interface CompressorProvider {

    // Gets a function that can decompress data for a given compression type.
    Function<InputStream, InputStream> getCompressorInput(String compressionType);

    // Gets all available decompression functions mapped by compression type.
    Map<String, Function<InputStream, InputStream>> getAllCompressorInput();

    // Gets a function that can compress data for a given compression type.
    Function<OutputStream, OutputStream> getCompressorOutputStream(String compressionType);

    // Checks if a given compression type is supported for input (decompression).
    boolean isSupportedInput(String compressionType);

    // Checks if a given compression type is supported for output (compression).
    boolean isSupportedOutput(String compressionType);

    // Gets the names of all supported compression types for input (decompression).
    Set<String> getInputStreamCompressorNames();

    // Gets the names of all supported compression types for output (compression).
    Set<String> getOutputStreamCompressorNames();
}

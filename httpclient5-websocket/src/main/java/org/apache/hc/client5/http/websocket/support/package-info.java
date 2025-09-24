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

/**
 * Internal support for the WebSocket client built on Apache HttpCore 5.
 * <p>
 * Provides helpers to bootstrap an {@code HttpAsyncRequester} with access to its
 * {@code ManagedConnPool}, and a requester that exposes the underlying
 * {@code ProtocolIOSession} for clean WebSocket protocol switching without reflection.
 * <p>
 * This package is internal and may change without notice; use the public client API instead.
 *
 * @since 1.0
 */
package org.apache.hc.client5.http.websocket.support;

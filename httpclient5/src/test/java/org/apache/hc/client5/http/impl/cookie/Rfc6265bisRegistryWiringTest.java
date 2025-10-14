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
package org.apache.hc.client5.http.impl.cookie;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.apache.hc.client5.http.cookie.CookieSpec;
import org.apache.hc.client5.http.cookie.CookieSpecFactory;
import org.apache.hc.client5.http.cookie.Rfc6265bisCookieSpec;
import org.apache.hc.client5.http.cookie.Rfc6265bisCookieSpecFactory;
import org.apache.hc.client5.http.impl.CookieSpecSupport;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.junit.jupiter.api.Test;

final class Rfc6265bisRegistryWiringTest {

    @Test
    void register_and_create_factory() {
        final RegistryBuilder<CookieSpecFactory> builder = CookieSpecSupport.createDefaultBuilder();
        builder.register("rfc6265bis", new Rfc6265bisCookieSpecFactory());
        final Lookup<CookieSpecFactory> registry = builder.build();

        final CookieSpecFactory factory = registry.lookup("rfc6265bis");
        assertNotNull(factory, "factory must be registered");

        final CookieSpec spec = factory.create(null);
        assertNotNull(spec);
        assertInstanceOf(Rfc6265bisCookieSpec.class, spec);
    }
}

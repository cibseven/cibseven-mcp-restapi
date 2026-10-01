/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.mcp.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuthorizationHeaderRelayProviderTest {

    private final AuthorizationHeaderRelayProvider provider = new AuthorizationHeaderRelayProvider();

    @AfterEach
    void resetRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void currentRequestWith(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void relaysBearerHeaderUnchanged() {
        currentRequestWith("Bearer cib-seven-jwt");
        assertThat(provider.authHeaders(null))
                .containsExactly(Map.entry("Authorization", "Bearer cib-seven-jwt"));
    }

    @Test
    void relaysAnySchemeUnchanged() {
        currentRequestWith("Basic ZGVtbzpkZW1v");
        assertThat(provider.authHeaders(null))
                .containsExactly(Map.entry("Authorization", "Basic ZGVtbzpkZW1v"));
    }

    @Test
    void relaysNothingWithoutAuthorizationHeader() {
        currentRequestWith(null);
        assertThat(provider.authHeaders(null)).isEmpty();
    }

    @Test
    void relaysNothingOutsideAnHttpRequest() {
        assertThat(provider.authHeaders(null)).isEmpty();
    }
}

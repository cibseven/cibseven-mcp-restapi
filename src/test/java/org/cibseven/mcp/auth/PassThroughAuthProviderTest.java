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

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class PassThroughAuthProviderTest {

    private final PassThroughAuthProvider provider = new PassThroughAuthProvider();

    private static Jwt jwt(String tokenValue) {
        return Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .claim("sub", "someone")
                .build();
    }

    @Test
    void forwardsBearerFromJwtAuthenticationToken() {
        Map<String, String> headers = provider.authHeaders(new JwtAuthenticationToken(jwt("the-token")));
        assertThat(headers).containsExactly(Map.entry("Authorization", "Bearer the-token"));
    }

    @Test
    void forwardsBearerFromJwtCredentials() {
        TestingAuthenticationToken auth = new TestingAuthenticationToken("principal", jwt("cred-token"));
        Map<String, String> headers = provider.authHeaders(auth);
        assertThat(headers).containsExactly(Map.entry("Authorization", "Bearer cred-token"));
    }

    @Test
    void returnsEmptyMapWhenNoJwtPresent() {
        assertThat(provider.authHeaders(null)).isEmpty();
        assertThat(provider.authHeaders(new TestingAuthenticationToken("p", "not-a-jwt"))).isEmpty();
    }
}

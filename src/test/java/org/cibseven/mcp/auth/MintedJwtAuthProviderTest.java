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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class MintedJwtAuthProviderTest {

    private UserIdResolver resolver;
    private CibSevenJwtMinter minter;
    private MintedJwtAuthProvider provider;

    @BeforeEach
    void setUp() {
        resolver = mock(UserIdResolver.class);
        minter = mock(CibSevenJwtMinter.class);
        provider = new MintedJwtAuthProvider(resolver, minter);
    }

    @Test
    void resolvesUserAndMintsBearer() {
        Jwt entra = Jwt.withTokenValue("entra-token")
                .header("alg", "RS256")
                .claim("oid", "147ae876-233e-4b00-b9f9-000000000000")
                .build();
        when(resolver.resolve(entra)).thenReturn("OlegSk");
        when(minter.mint("OlegSk")).thenReturn("minted-token");

        Map<String, String> headers = provider.authHeaders(new JwtAuthenticationToken(entra));

        assertThat(headers).containsExactly(Map.entry("Authorization", "Bearer minted-token"));
    }

    @Test
    void returnsEmptyMapWithoutInboundJwt() {
        assertThat(provider.authHeaders(null)).isEmpty();
        assertThat(provider.authHeaders(new TestingAuthenticationToken("p", "creds"))).isEmpty();
        verifyNoInteractions(resolver, minter);
    }

    @Test
    void propagatesResolverFailure() {
        Jwt entra = Jwt.withTokenValue("t").header("alg", "RS256").claim("oid", "x").build();
        when(resolver.resolve(any())).thenThrow(new IllegalStateException("no mapping"));

        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> provider.authHeaders(new JwtAuthenticationToken(entra)))
                .withMessageContaining("no mapping");
    }
}

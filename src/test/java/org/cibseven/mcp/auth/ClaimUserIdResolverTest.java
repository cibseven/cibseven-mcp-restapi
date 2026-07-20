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
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class ClaimUserIdResolverTest {

    @Test
    void resolvesDefaultClaim() {
        Jwt token = Jwt.withTokenValue("t").header("alg", "RS256")
                .claim("preferred_username", "OlegSk").build();
        assertThat(new ClaimUserIdResolver("preferred_username").resolve(token)).isEqualTo("OlegSk");
    }

    @Test
    void resolvesCustomClaim() {
        Jwt token = Jwt.withTokenValue("t").header("alg", "RS256")
                .claim("upn", "someone@example.org").build();
        assertThat(new ClaimUserIdResolver("upn").resolve(token)).isEqualTo("someone@example.org");
    }

    @Test
    void failsWithActionableMessageWhenClaimMissing() {
        Jwt token = Jwt.withTokenValue("t").header("alg", "RS256")
                .claim("sub", "irrelevant").build();
        assertThatIllegalStateException()
                .isThrownBy(() -> new ClaimUserIdResolver("preferred_username").resolve(token))
                .withMessageContaining("preferred_username");
    }
}

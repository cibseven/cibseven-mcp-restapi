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

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Translates the validated external identity into a freshly minted CIB seven JWT.
 *
 * <p>Use when engine-rest is reached the same way the LDAP-bound webclient reaches it:
 * HMAC JWT signed with the shared secret, with group/authorization resolution left to
 * the engine's (LDAP) identity provider. engine-rest needs no OAuth2 configuration and
 * never sees the Entra token — which also removes the audience-validation concern.</p>
 */
@Component
@ConditionalOnProperty(name = "cibseven.mcp.engine-rest.auth", havingValue = "minted-jwt")
public class MintedJwtAuthProvider implements EngineRestAuthProvider {

    private static final Logger logger = LoggerFactory.getLogger(MintedJwtAuthProvider.class);

    private final UserIdResolver userIdResolver;
    private final CibSevenJwtMinter minter;

    public MintedJwtAuthProvider(UserIdResolver userIdResolver, CibSevenJwtMinter minter) {
        this.userIdResolver = userIdResolver;
        this.minter = minter;
    }

    @Override
    public Map<String, String> authHeaders(Authentication authentication) {
        Jwt entra = EngineRestAuthProvider.inboundJwt(authentication);
        if (entra == null) {
            logger.debug("No Entra JWT on Authentication (type={}); no engine-rest auth header set",
                authentication == null ? "null" : authentication.getClass().getSimpleName());
            return Map.of();
        }
        String userId = userIdResolver.resolve(entra);
        logger.debug("Resolved CIB seven userId={} for oid={}; minting engine-rest JWT",
            userId, entra.getClaimAsString("oid"));
        return Map.of("Authorization", "Bearer " + minter.mint(userId));
    }
}

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Default {@link UserIdResolver}: takes the CIB seven userId directly from a single
 * configurable claim of the Entra access token.
 *
 * <p>Correct only when that claim equals the stored LDAP userId exactly. Verify with
 * {@code SELECT DISTINCT user_id_ FROM act_ru_authorization} against what the chosen
 * claim actually contains for a test user. If they differ, replace this bean with a
 * Graph/directory-backed resolver keyed on {@code oid}.</p>
 *
 * <p>Annotated {@link ConditionalOnMissingBean} so a custom resolver in the host
 * application simply overrides it without any further wiring.</p>
 */
@Component
@ConditionalOnProperty(name = "cibseven.mcp.engine-rest.auth", havingValue = "minted-jwt")
@ConditionalOnMissingBean(UserIdResolver.class)
public class ClaimUserIdResolver implements UserIdResolver {

    private static final Logger logger = LoggerFactory.getLogger(ClaimUserIdResolver.class);

    private final String claimName;

    public ClaimUserIdResolver(
            @Value("${cibseven.mcp.engine-rest.minted-jwt.user-id-claim:preferred_username}") String claimName) {
        this.claimName = claimName;
    }

    @Override
    public String resolve(Jwt entraToken) {
        String userId = entraToken.getClaimAsString(claimName);
        if (userId == null || userId.isBlank()) {
            logger.warn("Entra token has no usable '{}' claim to map to a CIB seven userId", claimName);
            throw new IllegalStateException(
                "Entra token has no usable '" + claimName + "' claim to map to a CIB seven userId. "
              + "Configure cibseven.mcp.engine-rest.minted-jwt.user-id-claim, or provide a "
              + "UserIdResolver bean that resolves oid via Graph/directory.");
        }
        // Authorization matching in CIB seven is case-sensitive. Do NOT normalise case here
        // unless you have confirmed the stored userIds use that exact casing.
        logger.debug("Resolved CIB seven userId={} from claim '{}'", userId, claimName);
        return userId;
    }
}

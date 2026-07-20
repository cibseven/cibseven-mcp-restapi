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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * {@link UserIdResolver} that ignores the inbound token entirely and always resolves to
 * one fixed, configured CIB seven userId.
 *
 * <p><strong>Development and testing only.</strong> With this resolver active, every
 * authenticated MCP caller is impersonated as the same user towards engine-rest —
 * per-user authorization, task assignment and history attribution are all collapsed
 * onto that single identity. Never enable it in an environment reachable by more than
 * the person whose userId is configured.</p>
 *
 * <p>Its legitimate uses are the local-development on-ramp for {@code minted-jwt} mode
 * (no Graph app registration or claim mapping required) and exercising the full
 * OAuth2 → minted-jwt → engine-rest pipeline against a test engine when the caller's
 * directory identity cannot be mapped yet (for example, a cloud-only account without
 * on-premises AD sync).</p>
 *
 * <p>Selected via {@code cibseven.mcp.engine-rest.minted-jwt.resolver=static}; the
 * fixed identity is configured with
 * {@code cibseven.mcp.engine-rest.minted-jwt.static.user-id}.</p>
 */
@Component
@ConditionalOnProperty(name = "cibseven.mcp.engine-rest.minted-jwt.resolver", havingValue = "static")
public class StaticUserIdResolver implements UserIdResolver {

    private static final Logger logger = LoggerFactory.getLogger(StaticUserIdResolver.class);

    private final String userId;

    public StaticUserIdResolver(
            @Value("${cibseven.mcp.engine-rest.minted-jwt.static.user-id:}") String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException(
                "cibseven.mcp.engine-rest.minted-jwt.static.user-id must be set when resolver=static");
        }
        this.userId = userId;
        logger.warn("StaticUserIdResolver active: EVERY caller will be impersonated as userId={} "
                + "towards engine-rest. This resolver is for development/testing only.", userId);
    }

    @Override
    public String resolve(Jwt entraToken) {
        return userId;
    }
}

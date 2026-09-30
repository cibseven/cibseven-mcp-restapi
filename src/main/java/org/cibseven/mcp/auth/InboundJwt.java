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

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Extracts the validated inbound JWT from the MCP request principal, the single
 * extraction rule shared by the OAuth2-based {@link EngineRestAuthProvider}s.
 *
 * <p>Kept out of {@link EngineRestAuthProvider} on purpose: the interface must only
 * depend on {@code spring-security-core}, so that it can also be implemented when the
 * OAuth2 classes ({@code spring-security-oauth2-jose}) are not on the classpath
 * (see {@link AuthorizationHeaderRelayProvider}).</p>
 */
final class InboundJwt {

    private InboundJwt() {
    }

    /**
     * @param authentication the inbound MCP request principal, may be {@code null}
     * @return the inbound {@link Jwt}, or {@code null} when the principal carries none
     */
    static Jwt from(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken();
        }
        if (authentication != null && authentication.getCredentials() instanceof Jwt jwt) {
            return jwt;
        }
        return null;
    }
}

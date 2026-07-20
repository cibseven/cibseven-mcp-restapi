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
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Strategy that decides how an MCP server authenticates its outgoing calls to a
 * CIB seven engine-rest backend, based on the already-validated MCP request principal.
 *
 * <p>Deliberately client-agnostic: it returns HTTP headers as a map rather than
 * touching any concrete HTTP client builder, so the same strategy survives a future
 * migration from OkHttp to {@code RestClient} or anything else. The caller applies
 * the returned headers (replace semantics) to whatever request it is building.</p>
 *
 * <p>Implementations are selected per deployment via the
 * {@code cibseven.mcp.engine-rest.auth} property, allowing one MCP binary to serve
 * the heterogeneous CIB seven landscape:</p>
 * <ul>
 *   <li>{@code passthrough} — engine-rest is itself an OAuth2 resource server;
 *       forward the validated bearer token unchanged (default, legacy behaviour).</li>
 *   <li>{@code minted-jwt} — engine-rest is reached from an LDAP-bound webclient using
 *       the shared CIB seven JWT secret; translate the external identity into a
 *       freshly minted, short-lived CIB seven JWT.</li>
 * </ul>
 */
public interface EngineRestAuthProvider {

    /**
     * @param authentication the validated principal of the inbound MCP request
     *                       (typically a {@code JwtAuthenticationToken} carrying the
     *                       Entra access token), may be {@code null}
     * @return headers to set on the outgoing engine-rest request; never {@code null},
     *         empty when no authentication can be derived
     */
    Map<String, String> authHeaders(Authentication authentication);

    /**
     * Extracts the validated inbound JWT from the MCP request principal, the single
     * extraction rule shared by all provider implementations.
     *
     * @param authentication the inbound MCP request principal, may be {@code null}
     * @return the inbound {@link Jwt}, or {@code null} when the principal carries none
     */
    static Jwt inboundJwt(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken();
        }
        if (authentication != null && authentication.getCredentials() instanceof Jwt jwt) {
            return jwt;
        }
        return null;
    }
}

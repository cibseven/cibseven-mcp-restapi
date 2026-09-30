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

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Relays the {@code Authorization} header of the inbound MCP request unchanged to
 * engine-rest, without validating it.
 *
 * <p>Only used when Spring Security is <strong>not</strong> on the classpath (see
 * {@link SecurityImportCommonConfig}): the MCP endpoint is then unprotected and
 * engine-rest alone decides whether the caller's credentials (e.g. a CIB seven JWT
 * set by the MCP client) are valid. The whole header is relayed, whatever its scheme.</p>
 *
 * <p>The header is read from the current servlet request, which is available because
 * Spring AI executes MCP tool calls on the HTTP request thread in servlet
 * applications. The {@link Authentication} argument is ignored: without Spring
 * Security there is none.</p>
 */
public class AuthorizationHeaderRelayProvider implements EngineRestAuthProvider {

    @Override
    public Map<String, String> authHeaders(Authentication authentication) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return Map.of();
        }
        HttpServletRequest request = servletAttributes.getRequest();
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        return authorization == null || authorization.isBlank()
                ? Map.of()
                : Map.of(HttpHeaders.AUTHORIZATION, authorization);
    }
}

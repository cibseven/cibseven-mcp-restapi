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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Forwards the validated inbound bearer token unchanged to engine-rest.
 *
 * <p>Use when engine-rest is itself configured as an OAuth2 resource server against
 * the same issuer (Entra). This reproduces the historical hard-wired behaviour of
 * {@code RESTAPIRequestDirector} and is therefore the default
 * ({@code matchIfMissing = true}), so existing deployments keep working untouched.</p>
 *
 * <p>Note for this mode: engine-rest must validate the token's {@code aud} (audience),
 * not only {@code iss} + signature, and the OAuth2 read-only identity provider must be
 * disabled ({@code camunda.bpm.oauth2.identity-provider.enabled=false}) so that group
 * resolution stays with the (LDAP) identity provider. Both are engine-rest deployment
 * concerns, not the responsibility of this class.</p>
 */
@Component
@ConditionalOnProperty(name = "cibseven.mcp.engine-rest.auth",
                       havingValue = "passthrough", matchIfMissing = true)
public class PassThroughAuthProvider implements EngineRestAuthProvider {

    @Override
    public Map<String, String> authHeaders(Authentication authentication) {
        Jwt jwt = EngineRestAuthProvider.inboundJwt(authentication);
        return jwt == null ? Map.of() : Map.of("Authorization", "Bearer " + jwt.getTokenValue());
    }
}

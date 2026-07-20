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

import org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Protects the MCP endpoint as an OAuth2 resource server (RFC 9728 Protected Resource
 * Metadata included), activated whenever
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} is configured.
 *
 * <p>Secures {@code ${spring.ai.mcp.server.streamable-http.mcp-endpoint}} and serves the
 * {@code /.well-known/oauth-*} metadata documents unauthenticated, as MCP clients need
 * them for discovery before they hold a token.</p>
 */
@Configuration
@ConditionalOnProperty(name = "spring.security.oauth2.resourceserver.jwt.issuer-uri")
public class CommonMcpOAuth2Configuration {

    private final String issuerUrl;
    private final String mcpEndpoint;
    private final String scopesSupported;

    /**
     * @param issuerUrl the OAuth2 issuer whose tokens are accepted
     * @param mcpEndpoint the MCP endpoint path to secure; required — without it the
     *        security matcher could not be built and the endpoint would be unprotected
     * @param scopesSupported optional space- or comma-separated OAuth2 scopes to advertise
     *        in the Protected Resource Metadata (RFC 9728 {@code scopes_supported}). MCP
     *        clients that offer no scope input of their own — e.g. the claude.ai connector —
     *        rely on this to know which scope to request. Without it, such clients send an
     *        {@code /authorize} request with no scope and the authorization server rejects
     *        it before login (Microsoft Entra ID: {@code AADSTS900144}). Empty by default,
     *        which advertises no scopes.
     */
    public CommonMcpOAuth2Configuration(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUrl,
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:}") String mcpEndpoint,
            @Value("${cibseven.mcp.oauth2.scopes-supported:}") String scopesSupported) {
        if (mcpEndpoint == null || mcpEndpoint.isBlank()) {
            throw new IllegalStateException(
                "spring.ai.mcp.server.streamable-http.mcp-endpoint must be set (e.g. /mcp) when "
              + "OAuth2 protection is enabled; otherwise the MCP endpoint cannot be secured.");
        }
        this.issuerUrl = issuerUrl;
        this.mcpEndpoint = mcpEndpoint;
        this.scopesSupported = scopesSupported;
    }

    @Bean
@Order(2)
SecurityFilterChain mcpServerSecurityFilterChain(HttpSecurity http) throws Exception {
    return http
            .securityMatcher(
                    mcpEndpoint,
                    mcpEndpoint + "/**",
                    "/.well-known/oauth-authorization-server",
                    "/.well-known/oauth-protected-resource",
                    "/.well-known/oauth-protected-resource/**")
            // MCP uses authenticated POST requests and bearer tokens, not browser CSRF.
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(
                                "/.well-known/oauth-authorization-server",
                                "/.well-known/oauth-protected-resource",
                                "/.well-known/oauth-protected-resource/**")
                        .permitAll();

                auth.anyRequest().authenticated();
            })
            .with(
                    McpServerOAuth2Configurer.mcpServerOAuth2(),
                    mcpAuthorization -> {
                        mcpAuthorization.authorizationServer(issuerUrl);

                        // Serves protected-resource metadata under:
                        // /.well-known/oauth-protected-resource{mcpEndpoint}
                        mcpAuthorization.resourcePath(mcpEndpoint);

                        if (scopesSupported != null && !scopesSupported.isBlank()) {
                            mcpAuthorization.protectedResourceMetadataCustomizer(builder -> {
                                builder.authorizationServer(issuerUrl);

                                for (String scope :
                                        scopesSupported.trim().split("[\\s,]+")) {
                                    if (!scope.isBlank()) {
                                        builder.scope(scope);
                                    }
                                }
                            });
                        }

                        /*
                         * Do NOT enable session binding for protocol STATELESS.
                         *
                         * Remove:
                         * mcpAuthorization.sessionBinding(
                         *         Customizer.withDefaults());
                         */
                    })
            .build();
}
}
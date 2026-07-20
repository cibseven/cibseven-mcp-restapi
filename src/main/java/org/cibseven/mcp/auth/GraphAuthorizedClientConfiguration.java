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

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * Supplies the {@link OAuth2AuthorizedClientManager} that {@link GraphSamAccountNameResolver}
 * uses to obtain an app-only (client_credentials) token against Microsoft Graph.
 *
 * <p>Service-to-service, so it uses the
 * {@link AuthorizedClientServiceOAuth2AuthorizedClientManager} (no servlet request / session
 * bound to the client) rather than the request-scoped default. Spring Boot does not register
 * such a manager on its own — only the {@link ClientRegistrationRepository} and
 * {@link OAuth2AuthorizedClientService} are auto-configured from
 * {@code spring.security.oauth2.client.*}.</p>
 *
 * <p>Only active when {@code cibseven.mcp.engine-rest.minted-jwt.resolver=graph}, matching the
 * resolver it serves, so passthrough/claim deployments never need a {@code graph} registration.
 * {@code @ConditionalOnMissingBean} lets a host application provide its own manager instead.</p>
 */
@Configuration
@ConditionalOnProperty(name = "cibseven.mcp.engine-rest.minted-jwt.resolver", havingValue = "graph")
public class GraphAuthorizedClientConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OAuth2AuthorizedClientManager graphAuthorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
            new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(
            OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }
}

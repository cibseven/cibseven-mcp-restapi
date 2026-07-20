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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link UserIdResolver} that maps the Entra {@code oid} to the on-premises
 * {@code sAMAccountName} via Microsoft Graph, for deployments where no Entra claim
 * equals the stored CIB seven userId 1:1 (the common case for LDAP-backed engines:
 * {@code onPremisesSamAccountName} is the attribute that matches the stored userId
 * bit- and case-exactly, independent of UPN quirks).
 *
 * <p>Selected by setting {@code cibseven.mcp.engine-rest.minted-jwt.resolver=graph};
 * otherwise the default {@link ClaimUserIdResolver} is used. Requires an
 * {@code OAuth2AuthorizedClientManager} and a {@code graph} client registration
 * (client_credentials, Application permission {@code User.Read.All}, admin-consented),
 * hence the property gate so passthrough/claim deployments never need that wiring.</p>
 */
@Component
@ConditionalOnProperty(name = "cibseven.mcp.engine-rest.minted-jwt.resolver", havingValue = "graph")
public class GraphSamAccountNameResolver implements UserIdResolver {

    private static final Logger logger = LoggerFactory.getLogger(GraphSamAccountNameResolver.class);

    private final RestClient graph;
    private final Cache<String, String> oidToUserId =
        Caffeine.newBuilder()
            .maximumSize(10_000)
            // directory changes therefore take effect with up to this much delay
            .expireAfterWrite(Duration.ofHours(8))
            .build();

    @Autowired
    public GraphSamAccountNameResolver(OAuth2AuthorizedClientManager authorizedClientManager) {
        // App-only token (client_credentials) against Graph; registration 'graph' in
        // spring.security.oauth2.client.* with Application permission User.Read.All (admin-consented).
        this(buildGraphClient(authorizedClientManager));
    }

    /** Visible for tests: allows injecting a pre-built Graph {@link RestClient}. */
    GraphSamAccountNameResolver(RestClient graphClient) {
        this.graph = graphClient;
    }

    private static RestClient buildGraphClient(OAuth2AuthorizedClientManager authorizedClientManager) {
        var interceptor = new OAuth2ClientHttpRequestInterceptor(authorizedClientManager);
        interceptor.setClientRegistrationIdResolver(req -> "graph");
        return RestClient.builder()
            .baseUrl("https://graph.microsoft.com/v1.0")
            .requestInterceptor(interceptor)
            .build();
    }

    @Override
    public String resolve(Jwt entraToken) {
        String oid = entraToken.getClaimAsString("oid");
        if (oid == null || oid.isBlank()) {
            logger.warn("Entra token has no 'oid' claim; cannot resolve a CIB seven userId via Graph");
            throw new IllegalStateException("Entra token carries no 'oid' claim; "
                + "cannot resolve a CIB seven userId via Microsoft Graph");
        }

        logger.debug("Resolving CIB seven userId for oid={} (cache checked first)", oid);
        String userId = oidToUserId.get(oid, this::lookupSamAccountName);
        if (userId == null || userId.isBlank()) {
            logger.warn("Graph returned no onPremisesSamAccountName for oid={}", oid);
            throw new IllegalStateException(
                "onPremisesSamAccountName is empty for oid " + oid
              + " — most likely a cloud-only user without on-premises AD sync; "
              + "no CIB seven userId mapping is possible for this account");
        }
        logger.debug("Resolved oid={} -> userId={}", oid, userId);
        return userId;
    }

    private String lookupSamAccountName(String oid) {
        logger.debug("Cache miss — calling Microsoft Graph for oid={}", oid);
        try {
            GraphUser u = graph.get()
                .uri("/users/{oid}?$select=onPremisesSamAccountName", oid)
                .retrieve()
                .body(GraphUser.class);
            return u != null ? u.onPremisesSamAccountName() : null;
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(
                "Microsoft Graph lookup failed for oid " + oid + ": HTTP " + e.getStatusCode()
              + ". Check the 'graph' client registration (client_credentials, Application "
              + "permission User.Read.All, admin-consented) and that the user exists.", e);
        } catch (RestClientException e) {
            throw new IllegalStateException(
                "Microsoft Graph is unreachable while resolving oid " + oid, e);
        }
    }

    private record GraphUser(String onPremisesSamAccountName) {}
}
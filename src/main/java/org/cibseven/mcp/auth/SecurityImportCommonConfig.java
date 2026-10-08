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
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@ConditionalOnProperty(
        prefix = "cibseven.mcp",
        name = "restapi-mcp",
        havingValue = "true")
public class SecurityImportCommonConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityImportCommonConfig.class);

    @Configuration
    @ConditionalOnClass(name = "org.springframework.security.config.annotation.SecurityConfigurerAdapter")
    @Import({
        CommonMcpOAuth2Configuration.class,   // issuer-uri set: MCP endpoint is an OAuth2 resource server
        IssuerUriRequiredConfiguration.class, // issuer-uri unset: fail startup (misconfiguration)
        // engine-rest auth strategy beans: this library is registered via
        // AutoConfiguration.imports (not component-scanned by the host app), so the
        // @Component providers/resolvers/minter must be imported explicitly to exist
        // as beans. Each keeps its own @ConditionalOnProperty/@ConditionalOnMissingBean,
        // so exactly one EngineRestAuthProvider (and, in minted-jwt mode, one
        // UserIdResolver) is active per deployment.
        PassThroughAuthProvider.class,        // default (auth=passthrough / unset)
        MintedJwtAuthProvider.class,          // auth=minted-jwt
        CibSevenJwtMinter.class,              // auth=minted-jwt
        GraphSamAccountNameResolver.class,    // resolver=graph — listed before the default
        StaticUserIdResolver.class,           // resolver=static (dev/test only) — ditto
        ClaimUserIdResolver.class,            // default resolver, which backs off via
                                              // @ConditionalOnMissingBean when another wins
        GraphAuthorizedClientConfiguration.class  // OAuth2AuthorizedClientManager for graph mode
    })
    static class SecurityEnabledConfig {
        // This class remains empty, it's used only as a holder for the above annotations
    }

    @Configuration
    @ConditionalOnMissingClass("org.springframework.security.config.annotation.SecurityConfigurerAdapter")
    static class SecurityDisabledConfig {

        /**
         * Without Spring Security the MCP endpoint is unprotected and the caller's
         * {@code Authorization} header is relayed as-is, so engine-rest alone validates it.
         * Only {@code passthrough} is possible: {@code minted-jwt} needs a validated
         * inbound identity, which only the OAuth2 resource server provides.
         */
        @Bean
        EngineRestAuthProvider authorizationHeaderRelayProvider(
                @Value("${cibseven.mcp.engine-rest.auth:passthrough}") String engineRestAuth) {
            if (!"passthrough".equals(engineRestAuth)) {
                throw new IllegalStateException(
                    "cibseven.mcp.engine-rest.auth=" + engineRestAuth + " requires Spring Security "
                  + "on the classpath and spring.security.oauth2.resourceserver.jwt.issuer-uri "
                  + "to validate the caller. Without Spring Security only 'passthrough' is supported.");
            }
            logger.warn("Spring Security is not on the classpath: the MCP endpoint is not protected "
                      + "and the caller's Authorization header is relayed unvalidated to engine-rest.");
            return new AuthorizationHeaderRelayProvider();
        }
    }
}
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
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@AutoConfiguration
public class SecurityImportCommonConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityImportCommonConfig.class);

    @Configuration
    @ConditionalOnClass(name = "org.springframework.security.config.annotation.SecurityConfigurerAdapter")
    @Import({
        CommonMcpOAuth2Configuration.class,
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

    @Bean
    @ConditionalOnMissingClass("org.springframework.security.config.annotation.SecurityConfigurerAdapter")
    public CommandLineRunner securityNotAvailableWarning() {
        return args -> logger.warn("Spring Security is not on the classpath. CommonMcpOAuth2Configuration will not be loaded.");
    }
}
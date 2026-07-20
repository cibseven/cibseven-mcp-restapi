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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.config.annotation.SecurityConfigurerAdapter;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

/**
 * Verifies the conditional bean matrix of {@link SecurityImportCommonConfig}: exactly one
 * {@link EngineRestAuthProvider} (and, in minted-jwt mode, exactly one
 * {@link UserIdResolver}) is active per configuration.
 */
class AuthAutoConfigurationTest {

    private static final String VALID_SECRET = Base64.getEncoder().encodeToString(new byte[64]);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SecurityImportCommonConfig.class));

    @Test
    void defaultsToPassThroughProvider() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(EngineRestAuthProvider.class);
            assertThat(context.getBean(EngineRestAuthProvider.class))
                    .isInstanceOf(PassThroughAuthProvider.class);
            assertThat(context).doesNotHaveBean(CibSevenJwtMinter.class);
            assertThat(context).doesNotHaveBean(UserIdResolver.class);
        });
    }

    @Test
    void explicitPassthroughSelectsPassThroughProvider() {
        runner.withPropertyValues("cibseven.mcp.engine-rest.auth=passthrough")
                .run(context -> {
                    assertThat(context).hasSingleBean(EngineRestAuthProvider.class);
                    assertThat(context.getBean(EngineRestAuthProvider.class))
                            .isInstanceOf(PassThroughAuthProvider.class);
                });
    }

    @Test
    void mintedJwtSelectsMinterProviderAndClaimResolver() {
        runner.withPropertyValues(
                        "cibseven.mcp.engine-rest.auth=minted-jwt",
                        "cibseven.webclient.authentication.jwtSecret=" + VALID_SECRET)
                .run(context -> {
                    assertThat(context).hasSingleBean(EngineRestAuthProvider.class);
                    assertThat(context.getBean(EngineRestAuthProvider.class))
                            .isInstanceOf(MintedJwtAuthProvider.class);
                    assertThat(context).hasSingleBean(CibSevenJwtMinter.class);
                    assertThat(context).hasSingleBean(UserIdResolver.class);
                    assertThat(context.getBean(UserIdResolver.class))
                            .isInstanceOf(ClaimUserIdResolver.class);
                });
    }

    @Test
    void graphResolverWinsOverClaimResolver() {
        runner.withPropertyValues(
                        "cibseven.mcp.engine-rest.auth=minted-jwt",
                        "cibseven.webclient.authentication.jwtSecret=" + VALID_SECRET,
                        "cibseven.mcp.engine-rest.minted-jwt.resolver=graph")
                .withBean(OAuth2AuthorizedClientManager.class, () -> mock(OAuth2AuthorizedClientManager.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(UserIdResolver.class);
                    assertThat(context.getBean(UserIdResolver.class))
                            .isInstanceOf(GraphSamAccountNameResolver.class);
                });
    }

    @Test
    void staticResolverWinsOverClaimResolver() {
        runner.withPropertyValues(
                        "cibseven.mcp.engine-rest.auth=minted-jwt",
                        "cibseven.webclient.authentication.jwtSecret=" + VALID_SECRET,
                        "cibseven.mcp.engine-rest.minted-jwt.resolver=static",
                        "cibseven.mcp.engine-rest.minted-jwt.static.user-id=OlegSk")
                .run(context -> {
                    assertThat(context).hasSingleBean(UserIdResolver.class);
                    assertThat(context.getBean(UserIdResolver.class))
                            .isInstanceOf(StaticUserIdResolver.class);
                });
    }

    @Test
    void warnsInsteadOfFailingWithoutSpringSecurityOnClasspath() {
        runner.withClassLoader(new FilteredClassLoader(SecurityConfigurerAdapter.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EngineRestAuthProvider.class);
                    assertThat(context).hasBean("securityNotAvailableWarning");
                    assertThat(context).hasSingleBean(CommandLineRunner.class);
                });
    }
}

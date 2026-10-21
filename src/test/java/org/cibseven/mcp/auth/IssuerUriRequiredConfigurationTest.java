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

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class IssuerUriRequiredConfigurationTest {

    @Test
    void failsStartupWhenSpringSecurityIsPresentWithoutIssuerUri() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SecurityImportCommonConfig.class))
                .withPropertyValues("cibseven.mcp.restapi-mcp=true")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("spring.security.oauth2.resourceserver.jwt.issuer-uri is not set"));
    }

    @Test
    void inactiveWithIssuerUri() {
        // only the condition is under test: loading the OAuth2 chain would fetch the issuer metadata
        new WebApplicationContextRunner()
                .withUserConfiguration(IssuerUriRequiredConfiguration.class)
                .withPropertyValues(
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.com")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(IssuerUriRequiredConfiguration.class);
                });
    }

    @Test
    void inactiveOutsideServletApplications() {
        new ApplicationContextRunner()
                .withUserConfiguration(IssuerUriRequiredConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed());
    }
}

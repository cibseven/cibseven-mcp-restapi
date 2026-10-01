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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.NoneNestedConditions;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Fails application startup when Spring Security is on the classpath but
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} is not configured.
 *
 * <p>With Spring Security present, the MCP endpoint must be an OAuth2 resource server
 * ({@link CommonMcpOAuth2Configuration}); the supported setups are OAuth2 end to end
 * ({@code passthrough}) or OAuth2 inbound with a CIB seven JWT outbound
 * ({@code minted-jwt}). Without the issuer, Spring Boot would silently fall back to
 * its default filter chain (HTTP Basic with a generated password), which is never a
 * meaningful setup for this library. Hosts that do not want Spring Security should
 * leave it off the classpath instead.</p>
 *
 * <p>Restricted to servlet web applications, the only ones a security filter chain
 * applies to.</p>
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Conditional(IssuerUriRequiredConfiguration.IssuerUriMissing.class)
class IssuerUriRequiredConfiguration {

    IssuerUriRequiredConfiguration() {
        throw new IllegalStateException(
            "Spring Security is on the classpath, but "
          + "spring.security.oauth2.resourceserver.jwt.issuer-uri is not set. Configure the "
          + "OAuth2 issuer that protects the MCP endpoint, or remove Spring Security from the "
          + "classpath to relay the caller's Authorization header to engine-rest unvalidated.");
    }

    /** Matches when the issuer-uri that activates {@link CommonMcpOAuth2Configuration} is absent. */
    static class IssuerUriMissing extends NoneNestedConditions {

        IssuerUriMissing() {
            super(ConfigurationPhase.PARSE_CONFIGURATION);
        }

        @ConditionalOnProperty(name = "spring.security.oauth2.resourceserver.jwt.issuer-uri")
        static class IssuerUriPresent {
        }
    }
}

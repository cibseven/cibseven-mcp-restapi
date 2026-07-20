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

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.Test;

class CommonMcpOAuth2ConfigurationTest {

    private static final String ISSUER = "https://login.microsoftonline.com/tenant/v2.0";

    @Test
    void acceptsValidConfiguration() {
        assertThatNoException().isThrownBy(
                () -> new CommonMcpOAuth2Configuration(ISSUER, "/mcp", ""));
    }

    @Test
    void failsFastWhenMcpEndpointMissing() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new CommonMcpOAuth2Configuration(ISSUER, "", ""))
                .withMessageContaining("mcp-endpoint");
        assertThatIllegalStateException()
                .isThrownBy(() -> new CommonMcpOAuth2Configuration(ISSUER, null, ""))
                .withMessageContaining("mcp-endpoint");
    }
}

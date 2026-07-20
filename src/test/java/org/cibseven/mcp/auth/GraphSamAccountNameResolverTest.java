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
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;

class GraphSamAccountNameResolverTest {

    private MockWebServer graphServer;
    private GraphSamAccountNameResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        graphServer = new MockWebServer();
        graphServer.start();
        RestClient graphClient = RestClient.builder()
                .baseUrl(graphServer.url("/v1.0").toString())
                .build();
        resolver = new GraphSamAccountNameResolver(graphClient);
    }

    @AfterEach
    void tearDown() throws IOException {
        graphServer.shutdown();
    }

    private static Jwt tokenWithOid(String oid) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").claim("sub", "s");
        if (oid != null) {
            builder.claim("oid", oid);
        }
        return builder.build();
    }

    private void enqueueJson(int status, String body) {
        graphServer.enqueue(new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    @Test
    void resolvesOidToSamAccountName() throws InterruptedException {
        enqueueJson(200, "{\"onPremisesSamAccountName\":\"OlegSk\"}");

        assertThat(resolver.resolve(tokenWithOid("oid-1"))).isEqualTo("OlegSk");

        var recorded = graphServer.takeRequest();
        assertThat(recorded.getPath()).contains("/users/oid-1");
        assertThat(recorded.getPath()).contains("onPremisesSamAccountName");
    }

    @Test
    void cachesResolvedMappings() {
        enqueueJson(200, "{\"onPremisesSamAccountName\":\"OlegSk\"}");

        assertThat(resolver.resolve(tokenWithOid("oid-cached"))).isEqualTo("OlegSk");
        assertThat(resolver.resolve(tokenWithOid("oid-cached"))).isEqualTo("OlegSk");

        assertThat(graphServer.getRequestCount())
                .as("second resolve must be served from the cache")
                .isEqualTo(1);
    }

    @Test
    void failsForCloudOnlyUserWithoutSamAccountName() {
        enqueueJson(200, "{\"onPremisesSamAccountName\":null}");

        assertThatIllegalStateException()
                .isThrownBy(() -> resolver.resolve(tokenWithOid("oid-cloud-only")))
                .withMessageContaining("cloud-only");
    }

    @Test
    void failsWithActionableMessageOnGraphHttpError() {
        enqueueJson(403, "{\"error\":{\"code\":\"Authorization_RequestDenied\"}}");

        assertThatIllegalStateException()
                .isThrownBy(() -> resolver.resolve(tokenWithOid("oid-403")))
                .withMessageContaining("Graph lookup failed")
                .withMessageContaining("403");
    }

    @Test
    void failsWhenTokenHasNoOid() {
        assertThatIllegalStateException()
                .isThrownBy(() -> resolver.resolve(tokenWithOid(null)))
                .withMessageContaining("oid");
        assertThat(graphServer.getRequestCount()).isZero();
    }
}

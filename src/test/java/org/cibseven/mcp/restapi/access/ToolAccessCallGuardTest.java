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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cibseven.mcp.restapi.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.cibseven.mcp.auth.EngineRestAuthProvider;
import org.cibseven.mcp.restapi.RESTAPIMcpToolsConfig;
import org.cibseven.mcp.restapi.RESTAPIRequestDirector;
import org.cibseven.mcp.restapi.models.HTTPRoute;
import org.cibseven.mcp.restapi.models.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

class ToolAccessCallGuardTest {

    private MockWebServer engine;
    private RESTAPIRequestDirector director;

    @BeforeEach
    void setUp() throws IOException {
        engine = new MockWebServer();
        engine.start();
        EngineRestAuthProvider stubAuth = authentication -> Map.of();
        director = new RESTAPIRequestDirector(engine.url("/engine-rest").toString(), stubAuth);
    }

    @AfterEach
    void tearDown() throws IOException {
        engine.shutdown();
    }

    @Test
    void deniedToolReturnsErrorWithoutCallingTheEngine() throws Exception {
        SyncToolSpecification spec = singleToolWithPolicy((authentication, route) -> false);

        McpSchema.CallToolResult result = spec.callHandler().apply(
                McpTransportContext.EMPTY,
                new McpSchema.CallToolRequest("deleteThing", Map.of("id", "42")));

        assertThat(result.isError()).isTrue();
        assertThat(engine.getRequestCount()).isZero();
    }

    @Test
    void allowedToolReachesTheEngine() throws Exception {
        engine.enqueue(new MockResponse().setResponseCode(204));

        SyncToolSpecification spec = singleToolWithPolicy((authentication, route) -> true);

        McpSchema.CallToolResult result = spec.callHandler().apply(
                McpTransportContext.EMPTY,
                new McpSchema.CallToolRequest("deleteThing", Map.of("id", "42")));

        assertThat(result.isError()).isFalse();
        assertThat(engine.getRequestCount()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private SyncToolSpecification singleToolWithPolicy(ToolAccessPolicy policy) throws Exception {
        HTTPRoute route = new HTTPRoute();
        route.setOperationId("deleteThing");
        route.setPath("/thing/{id}");
        route.setMethod(HttpMethod.DELETE);

        RouteIndex routeIndex = new RouteIndex(Map.of("deleteThing", route));

        ObjectProvider<ToolAccessPolicy> policyProvider = mock(ObjectProvider.class);
        when(policyProvider.getIfAvailable()).thenReturn(policy);

        List<SyncToolSpecification> tools =
                new RESTAPIMcpToolsConfig().getTools(routeIndex, director, policyProvider);

        assertThat(tools).hasSize(1);
        return tools.get(0);
    }
}

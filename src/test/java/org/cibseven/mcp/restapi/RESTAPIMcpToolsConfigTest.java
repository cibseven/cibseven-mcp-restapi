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
package org.cibseven.mcp.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.cibseven.mcp.auth.EngineRestAuthProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RESTAPIMcpToolsConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(RESTAPIMcpToolsConfig.class))
                    .withBean(
                            EngineRestAuthProvider.class,
                            () -> authentication -> Map.of());

    @Test
    void staysInactiveWithoutTheEnableFlag() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(RESTAPIRequestDirector.class);
            assertThat(context).doesNotHaveBean("getTools");
        });
    }

    @Test
    void buildsOneToolPerOperationSkippingOperationIdLessRoutes() {
        runner.withPropertyValues(
                        "cibseven.mcp.restapi-mcp=true",
                        "cibseven.openapi.url=" + FixtureSupport.fixturePath())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .hasSingleBean(RESTAPIRequestDirector.class);

                    @SuppressWarnings("unchecked")
                    List<McpStatelessServerFeatures.SyncToolSpecification> tools =
                            (List<McpStatelessServerFeatures.SyncToolSpecification>)
                                    context.getBean("getTools");

                    // 7 fixture operations, one without operationId -> 6 tools
                    assertThat(tools).hasSize(6);
                    assertThat(tools)
                            .extracting(spec -> spec.tool().name())
                            .containsExactlyInAnyOrder(
                                    "getTask",
                                    "createTask",
                                    "updateTask",
                                    "createDeployment",
                                    "patchTask",
                                    "searchTasks");
                });
    }

    @Test
    void toolCallHandlerReturnsEngineResponse() throws Exception {
        try (MockWebServer engine = new MockWebServer()) {
            engine.start();
            engine.enqueue(new MockResponse().setBody("{\"id\":\"1\"}"));

            engineBackedRunner(engine).run(context -> {
                McpSchema.CallToolResult result =
                        callTool(context, "getTask", Map.of("id", "1"));

                assertThat(result.isError()).isFalse();
                assertThat(textOf(result)).contains("\"id\":\"1\"");
            });
        }
    }

    @Test
    void toolCallHandlerWrapsFailureAsIsError() throws Exception {
        try (MockWebServer engine = new MockWebServer()) {
            engine.start();
            engine.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

            engineBackedRunner(engine).run(context -> {
                McpSchema.CallToolResult result =
                        callTool(context, "getTask", Map.of("id", "1"));

                // a failing engine-rest call must surface as an MCP error result, not an exception
                assertThat(result.isError()).isTrue();
                assertThat(textOf(result)).startsWith("Error:").contains("boom");
            });
        }
    }

    private ApplicationContextRunner engineBackedRunner(MockWebServer engine) {
        return runner.withPropertyValues(
                "cibseven.mcp.restapi-mcp=true",
                "cibseven.openapi.url=" + FixtureSupport.fixturePath(),
                "cibseven.webclient.engineRest.url=" + engine.url("/"),
                "cibseven.webclient.engineRest.path=/engine-rest");
    }

    @SuppressWarnings("unchecked")
    private static McpSchema.CallToolResult callTool(
            ApplicationContext context,
            String toolName,
            Map<String, Object> arguments) {

        List<McpStatelessServerFeatures.SyncToolSpecification> tools =
                (List<McpStatelessServerFeatures.SyncToolSpecification>)
                        context.getBean("getTools");

        McpStatelessServerFeatures.SyncToolSpecification spec = tools.stream()
                .filter(t -> toolName.equals(t.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + toolName));

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name(toolName)
                .arguments(arguments)
                .build();

        return spec.callHandler().apply(null, request);
    }

    private static String textOf(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }
}

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

import org.cibseven.mcp.auth.EngineRestAuthProvider;
import org.junit.jupiter.api.Test;
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

                    // 5 fixture operations, one without operationId -> 4 tools
                    assertThat(tools).hasSize(4);
                    assertThat(tools)
                            .extracting(spec -> spec.tool().name())
                            .containsExactlyInAnyOrder(
                                    "getTask",
                                    "createTask",
                                    "updateTask",
                                    "createDeployment");
                });
    }
}

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
package org.cibseven.mcp.restapi.access;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import tools.jackson.databind.json.JsonMapper;

/**
 * Activates per-user tool-access filtering when — and only when — a {@link ToolAccessPolicy}
 * bean is present. Absent a policy this configuration does nothing and Spring AI's default
 * stateless WebMVC transport is used unchanged, so the library stays fully generic.
 *
 * <p>The filtering is installed by supplying a custom stateless transport. The real
 * {@link WebMvcStatelessServerTransport} is declared here (superseding Spring AI's
 * {@code @ConditionalOnMissingBean} one) and wrapped by an
 * {@link AccessFilteringStatelessTransport} marked {@link Primary}, so the MCP server binds
 * to the wrapper while the router function is served by the real delegate — the same
 * instance the wrapper installs its handler on. This configuration is ordered before
 * Spring AI's stateless WebMVC auto-configuration so its bean definitions win.</p>
 */
@AutoConfiguration(
        beforeName =
                "org.springframework.ai.mcp.server.webmvc.autoconfigure."
                        + "McpServerStatelessWebMvcAutoConfiguration")
@ConditionalOnBean(ToolAccessPolicy.class)
public class ToolAccessControlAutoConfiguration {

    /**
     * The real WebMVC transport, built exactly as Spring AI's auto-configuration would.
     * Declaring it here makes Spring AI's {@code @ConditionalOnMissingBean} definition back
     * off, so this single instance both serves HTTP (via the router function below) and
     * receives the wrapped MCP handler.
     */
    @Bean
    public WebMvcStatelessServerTransport webMvcStatelessServerTransport(
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}")
                    String mcpEndpoint) {

        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()))
                .messageEndpoint(mcpEndpoint)
                .build();
    }

    /**
     * The access-filtering wrapper the MCP server binds to. Marked {@link Primary} because
     * both this and {@link #webMvcStatelessServerTransport} are candidates for the server's
     * {@code McpStatelessServerTransport} injection point.
     */
    @Bean
    @Primary
    public McpStatelessServerTransport accessFilteringStatelessServerTransport(
            WebMvcStatelessServerTransport delegate,
            ToolAccessPolicy toolAccessPolicy,
            RouteIndex routeIndex) {

        return new AccessFilteringStatelessTransport(delegate, toolAccessPolicy, routeIndex);
    }

    /** Serves HTTP from the real delegate transport (bean name matches Spring AI's). */
    @Bean
    public RouterFunction<ServerResponse> webMvcStatelessServerRouterFunction(
            WebMvcStatelessServerTransport webMvcStatelessServerTransport) {
        return webMvcStatelessServerTransport.getRouterFunction();
    }
}

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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cibseven.mcp.auth.EngineRestAuthProvider;
import org.cibseven.mcp.restapi.access.RouteIndex;
import org.cibseven.mcp.restapi.access.ToolAccessPolicy;
import org.cibseven.mcp.restapi.models.HTTPRoute;
import org.cibseven.mcp.restapi.openapi.OpenAPIParser;
import org.cibseven.mcp.restapi.openapi.OpenApiProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/**
 * Auto-configuration that turns the operations of an OpenAPI-described REST API into
 * MCP tools.
 *
 * <p>Activated by {@code cibseven.mcp.restapi-mcp=true}. It loads the OpenAPI document
 * from {@code cibseven.openapi.url}, parses every operation into an {@link HTTPRoute},
 * and registers one MCP tool per operation. The {@code operationId} becomes the tool
 * name.</p>
 *
 * <p>Tool calls are executed against {@code cibseven.webclient.engineRest.url} by the
 * {@link RESTAPIRequestDirector}, using the active
 * {@link EngineRestAuthProvider} strategy for outbound authentication.</p>
 *
 * <p>The OpenAPI document is fetched eagerly at startup. If the configured URL is
 * unreachable, application startup fails. This is intentional because a tool-less MCP
 * server would otherwise appear healthy while being unusable.</p>
 */
@AutoConfiguration
@ConditionalOnProperty(
        prefix = "cibseven.mcp",
        name = "restapi-mcp",
        havingValue = "true")
public class RESTAPIMcpToolsConfig {

    private static final Logger logger =
            LoggerFactory.getLogger(RESTAPIMcpToolsConfig.class);

    private static final JacksonMcpJsonMapper MCP_JSON_MAPPER =
            new JacksonMcpJsonMapper(JsonMapper.builder().build());

    @Bean
    public OpenApiProvider openApiProvider(
            @Value(
                    "${cibseven.openapi.url:"
                            + "https://docs.cibseven.org/rest/cibseven/2.2/swagger/openapi.json}")
                    String swaggerLocation)
            throws IOException {

        return new OpenApiProvider(swaggerLocation);
    }

    @Bean
    public OpenAPIParser openApiParser(OpenApiProvider openApiProvider)
            throws Exception {

        return new OpenAPIParser(openApiProvider.getOpenAPI());
    }

    /**
     * Builds the request director for the configured engine-rest base URL.
     *
     * <p>The base URL is assembled directly from host and path because generic OpenAPI
     * server templating breaks for HTTPS hosts without an explicit port.
     * {@code URI.getPort()} returns {@code -1}, producing an invalid
     * {@code host:-1} URL.</p>
     */
    @Bean
    public RESTAPIRequestDirector restApiRequestDirector(
            @Value("${cibseven.webclient.engineRest.url:http://localhost:8080}")
                    String hostUrl,
            @Value("${cibseven.webclient.engineRest.path:/engine-rest}")
                    String engineRestPath,
            EngineRestAuthProvider authProvider) {

        String sanitizedPath = engineRestPath.replaceAll("^/+|/+$", "");
        String baseUrl =
                (hostUrl.endsWith("/") ? hostUrl : hostUrl + "/")
                        + sanitizedPath;

        return new RESTAPIRequestDirector(baseUrl, authProvider);
    }

    /**
     * Indexes the parsed OpenAPI operations by {@code operationId} (the MCP tool name).
     *
     * <p>Routes without an {@code operationId} are dropped because they do not have a
     * stable tool name. This is the single parse of the OpenAPI document, shared by
     * {@link #getTools} and by the optional access-control filter.</p>
     */
    @Bean
    public RouteIndex routeIndex(OpenAPIParser openApiParser) throws Exception {
        Map<String, HTTPRoute> byOperationId = new LinkedHashMap<>();
        int skipped = 0;

        for (HTTPRoute route : openApiParser.parse()) {
            if (route.getOperationId() == null || route.getOperationId().isEmpty()) {
                logger.warn("Skipping route {} {} — missing operationId",
                        route.getMethod(), route.getPath());
                skipped++;
                continue;
            }
            HTTPRoute previous = byOperationId.putIfAbsent(route.getOperationId(), route);
            if (previous != null) {
                logger.warn("Duplicate operationId '{}' ({} {}) — keeping the first occurrence",
                        route.getOperationId(), route.getMethod(), route.getPath());
            }
        }

        logger.info("Indexed {} OpenAPI operations ({} skipped for missing operationId)",
                byOperationId.size(), skipped);
        return new RouteIndex(byOperationId);
    }

    /**
     * Builds one stateless MCP tool specification per indexed OpenAPI operation.
     *
     * <p>Routes whose tool specification cannot be built are skipped so that one malformed
     * operation does not take down the remaining tool set.</p>
     *
     * <p>When a {@link ToolAccessPolicy} bean is present, each tool call is checked against
     * it first and rejected before any engine round trip if the authenticated user may not
     * invoke that operation. With no policy present the guard is a no-op and the server
     * exposes and forwards every operation as before — keeping the library generic.</p>
     */
    @Bean
    public List<McpStatelessServerFeatures.SyncToolSpecification> getTools(
            RouteIndex routeIndex,
            RESTAPIRequestDirector restApiRequestDirector,
            ObjectProvider<ToolAccessPolicy> toolAccessPolicyProvider)
            throws Exception {

        List<McpStatelessServerFeatures.SyncToolSpecification> tools =
                new ArrayList<>();

        ToolAccessPolicy toolAccessPolicy = toolAccessPolicyProvider.getIfAvailable();

        for (HTTPRoute route : routeIndex.routes()) {

            String flatSchema = route.getFlatParamSchema();

            if (flatSchema == null || flatSchema.isEmpty()) {
                flatSchema = """
                        {
                          "type": "object",
                          "properties": {}
                        }
                        """;
            }

            final String resolvedFlatSchema = flatSchema;

            try {
                McpSchema.Tool tool =
                        McpSchema.Tool.builder(
                                        route.getOperationId(),
                                        MCP_JSON_MAPPER,
                                        resolvedFlatSchema)
                                .title(route.getSummary())
                                .description(route.getDescription())
                                .build();

                McpStatelessServerFeatures.SyncToolSpecification toolSpecification =
                        McpStatelessServerFeatures.SyncToolSpecification.builder()
                                .tool(tool)
                                .callHandler((context, request) -> {
                                    try {
                                        Authentication authentication =
                                                SecurityContextHolder
                                                        .getContext()
                                                        .getAuthentication();

                                        if (toolAccessPolicy != null
                                                && !toolAccessPolicy.isAllowed(
                                                        authentication, route)) {
                                            logger.warn(
                                                    "Rejecting call to '{}' — not authorized"
                                                            + " for principal '{}'",
                                                    route.getOperationId(),
                                                    authentication != null
                                                            ? authentication.getName() : null);
                                            return McpSchema.CallToolResult.builder()
                                                    .addTextContent(
                                                            "Error: not authorized to call "
                                                                    + route.getOperationId())
                                                    .isError(Boolean.TRUE)
                                                    .build();
                                        }

                                        Map<String, Object> requestParameters =
                                                request.arguments();

                                        String response =
                                                restApiRequestDirector.sendSyncRequest(
                                                        route,
                                                        requestParameters,
                                                        authentication);

                                        return McpSchema.CallToolResult.builder()
                                                .addTextContent(response)
                                                .isError(Boolean.FALSE)
                                                .build();
                                    }
                                    catch (Exception exception) {
                                        logger.error(
                                                "Error calling operation: {}",
                                                route.getOperationId(),
                                                exception);

                                        return McpSchema.CallToolResult.builder()
                                                .addTextContent(
                                                        "Error: "
                                                                + exception.getMessage())
                                                .isError(Boolean.TRUE)
                                                .build();
                                    }
                                })
                                .build();

                tools.add(toolSpecification);
            }
            catch (Exception toolError) {
                logger.warn(
                        "Skipping tool for route {} {} — failed to build tool spec",
                        route.getMethod(),
                        route.getPath(),
                        toolError);
            }
        }

        logger.info("Registered {} stateless MCP tools", tools.size());

        return tools;
    }
}

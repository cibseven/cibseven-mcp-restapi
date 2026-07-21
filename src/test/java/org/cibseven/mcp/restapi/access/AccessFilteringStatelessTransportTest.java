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

import java.util.List;
import java.util.Map;

import org.cibseven.mcp.restapi.models.HTTPRoute;
import org.cibseven.mcp.restapi.models.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class AccessFilteringStatelessTransportTest {

    private static final JacksonMcpJsonMapper MAPPER =
            new JacksonMcpJsonMapper(JsonMapper.builder().build());
    private static final String EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private final McpSchema.ListToolsResult twoTools = new McpSchema.ListToolsResult(
            List.of(tool("allowedOp"), tool("deniedOp")), null, null);

    /** Captures the handler the wrapper installs on the delegate transport. */
    private final McpStatelessServerHandler[] installed = new McpStatelessServerHandler[1];

    private final McpStatelessServerTransport delegate = new McpStatelessServerTransport() {
        @Override
        public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
            installed[0] = mcpHandler;
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }
    };

    /** The "real" server handler that always answers tools/list with both tools. */
    private final McpStatelessServerHandler realHandler = new McpStatelessServerHandler() {
        @Override
        public Mono<McpSchema.JSONRPCResponse> handleRequest(
                McpTransportContext ctx, McpSchema.JSONRPCRequest request) {
            return Mono.just(McpSchema.JSONRPCResponse.result(request.id(), twoTools));
        }

        @Override
        public Mono<Void> handleNotification(
                McpTransportContext ctx, McpSchema.JSONRPCNotification notification) {
            return Mono.empty();
        }
    };

    private McpStatelessServerHandler wrapped;

    @BeforeEach
    void setUp() {
        HTTPRoute allowed = route("allowedOp", "/allowed", HttpMethod.GET);
        HTTPRoute denied = route("deniedOp", "/denied", HttpMethod.DELETE);
        RouteIndex routeIndex =
                new RouteIndex(Map.of("allowedOp", allowed, "deniedOp", denied));

        ToolAccessPolicy policy =
                (authentication, route) -> !"deniedOp".equals(route.getOperationId());

        AccessFilteringStatelessTransport transport =
                new AccessFilteringStatelessTransport(delegate, policy, routeIndex);
        transport.setMcpHandler(realHandler);
        wrapped = installed[0];
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void hidesToolsTheAuthenticatedUserMayNotInvoke() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice", "creds", "ROLE_USER"));

        McpSchema.ListToolsResult result = listTools();

        assertThat(result.tools())
                .extracting(McpSchema.Tool::name)
                .containsExactly("allowedOp");
    }

    @Test
    void leavesToolsUnfilteredWhenNoPrincipalIsPresent() {
        SecurityContextHolder.clearContext();

        McpSchema.ListToolsResult result = listTools();

        assertThat(result.tools())
                .extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("allowedOp", "deniedOp");
    }

    @Test
    void passesNonToolListRequestsThroughUnchanged() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice", "creds", "ROLE_USER"));

        McpSchema.JSONRPCResponse response = wrapped.handleRequest(
                McpTransportContext.EMPTY,
                new McpSchema.JSONRPCRequest("ping", "9", null)).block();

        // The real handler returns twoTools verbatim; the wrapper must not touch it.
        assertThat(response.result()).isSameAs(twoTools);
    }

    private McpSchema.ListToolsResult listTools() {
        McpSchema.JSONRPCResponse response = wrapped.handleRequest(
                McpTransportContext.EMPTY,
                new McpSchema.JSONRPCRequest(McpSchema.METHOD_TOOLS_LIST, "1", null)).block();
        assertThat(response).isNotNull();
        return (McpSchema.ListToolsResult) response.result();
    }

    private static McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder(name, MAPPER, EMPTY_SCHEMA).build();
    }

    private static HTTPRoute route(String operationId, String path, HttpMethod method) {
        HTTPRoute route = new HTTPRoute();
        route.setOperationId(operationId);
        route.setPath(path);
        route.setMethod(method);
        return route;
    }
}

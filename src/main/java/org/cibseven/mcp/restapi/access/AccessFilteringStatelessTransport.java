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

import java.util.ArrayList;
import java.util.List;

import org.cibseven.mcp.restapi.models.HTTPRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import reactor.core.publisher.Mono;

/**
 * A {@link McpStatelessServerTransport} decorator that filters the {@code tools/list}
 * response per authenticated user according to a {@link ToolAccessPolicy}.
 *
 * <p>All transport behaviour is delegated to a real transport (the Spring AI
 * {@code WebMvcStatelessServerTransport}); the only interception is on the
 * {@link McpStatelessServerHandler}: for {@code tools/list} the resulting
 * {@link McpSchema.ListToolsResult} is rebuilt without the tools the policy rejects for the
 * current principal. Every other request/notification passes through untouched.</p>
 *
 * <p>The filter runs on the handling thread (the servlet request thread, since Spring AI
 * configures {@code immediateExecution} for the WebMVC transport), so the Spring
 * {@code SecurityContext} carries the authenticated principal — the same mechanism the tool
 * <em>call</em> handler already relies on. When no principal is present (e.g. the endpoint
 * is not OAuth2-protected) no filtering is applied.</p>
 *
 * <p>Hiding a tool from discovery does not by itself stop a client from calling it; the
 * matching {@code tools/call} guard lives in the tool call handler.</p>
 */
public class AccessFilteringStatelessTransport implements McpStatelessServerTransport {

    private static final Logger logger =
            LoggerFactory.getLogger(AccessFilteringStatelessTransport.class);

    private final McpStatelessServerTransport delegate;
    private final ToolAccessPolicy policy;
    private final RouteIndex routeIndex;

    public AccessFilteringStatelessTransport(
            McpStatelessServerTransport delegate,
            ToolAccessPolicy policy,
            RouteIndex routeIndex) {
        this.delegate = delegate;
        this.policy = policy;
        this.routeIndex = routeIndex;
    }

    @Override
    public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
        delegate.setMcpHandler(wrap(mcpHandler));
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }

    private McpStatelessServerHandler wrap(McpStatelessServerHandler handler) {
        return new McpStatelessServerHandler() {

            @Override
            public Mono<McpSchema.JSONRPCResponse> handleRequest(
                    McpTransportContext transportContext,
                    McpSchema.JSONRPCRequest request) {

                Mono<McpSchema.JSONRPCResponse> response =
                        handler.handleRequest(transportContext, request);

                if (!McpSchema.METHOD_TOOLS_LIST.equals(request.method())) {
                    return response;
                }
                return response.map(AccessFilteringStatelessTransport.this::filterToolsList);
            }

            @Override
            public Mono<Void> handleNotification(
                    McpTransportContext transportContext,
                    McpSchema.JSONRPCNotification notification) {
                return handler.handleNotification(transportContext, notification);
            }
        };
    }

    /**
     * Rebuilds a {@code tools/list} result without the tools the policy rejects for the
     * current principal. Error responses, non-tool-list results and unauthenticated
     * requests are returned unchanged. Tools whose backing route is unknown are kept
     * (the call guard and the target API remain the backstop).
     */
    private McpSchema.JSONRPCResponse filterToolsList(McpSchema.JSONRPCResponse response) {
        if (response.error() != null
                || !(response.result() instanceof McpSchema.ListToolsResult listToolsResult)) {
            return response;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return response;
        }

        List<McpSchema.Tool> allowed = new ArrayList<>(listToolsResult.tools().size());
        for (McpSchema.Tool tool : listToolsResult.tools()) {
            HTTPRoute route = routeIndex.get(tool.name());
            if (route == null || policy.isAllowed(authentication, route)) {
                allowed.add(tool);
            }
        }

        if (allowed.size() == listToolsResult.tools().size()) {
            return response;
        }

        logger.debug("Filtered tools/list to {} of {} tools for principal '{}'",
                allowed.size(), listToolsResult.tools().size(), authentication.getName());

        return McpSchema.JSONRPCResponse.result(
                response.id(),
                new McpSchema.ListToolsResult(
                        allowed, listToolsResult.nextCursor(), listToolsResult.meta()));
    }
}

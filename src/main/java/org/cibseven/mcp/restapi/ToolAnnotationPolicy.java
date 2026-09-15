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

import org.cibseven.mcp.restapi.models.HTTPRoute;
import org.cibseven.mcp.restapi.models.HttpMethod;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Derives MCP {@link McpSchema.ToolAnnotations} for an OpenAPI operation from its HTTP
 * method.
 *
 * <p>MCP clients use these hints to group and pre-authorize tools. Claude, for example,
 * splits a connector's tool list into "Read-only tools" and "Write/delete tools" and lets
 * the user approve a whole group at once; tools without annotations all land in a single
 * undifferentiated "Other tools" group. The decisive field is {@code readOnlyHint}, which
 * this policy therefore always sets explicitly rather than leaving to the client's
 * default.</p>
 *
 * <p>Classification is by method alone, which makes {@code POST} the deliberate
 * approximation. REST APIs use {@code POST} both for state changes and for queries whose
 * parameters are too large for a query string — in the CIB seven engine REST API, 36 of
 * 116 {@code POST} operations are pure queries ({@code queryTasks},
 * {@code postExecuteFilterList}, ...). Method alone cannot tell those from
 * {@code deleteProcessInstancesAsyncOperation}, so every {@code POST} is annotated as a
 * write. That is the safe direction, and it matches the MCP default of
 * {@code destructiveHint = true} whenever {@code readOnlyHint} is false. The cost is that
 * those 36 query tools need per-call approval rather than joining the bulk-approvable
 * read-only group; recovering them would require classifying {@code POST} by operation id
 * and path, which is a naming heuristic this policy intentionally does not carry.</p>
 *
 * <p>An application that wants a finer classification supplies its own
 * {@code ToolAnnotationPolicy} bean, which {@link RESTAPIMcpToolsConfig} backs off to.</p>
 */
public class ToolAnnotationPolicy {

    /**
     * Annotates one route.
     *
     * <p>A route with no method is annotated as a non-idempotent destructive write, the
     * most restrictive combination, so that an unparseable operation never ends up in a
     * bulk-approved group.</p>
     */
    public McpSchema.ToolAnnotations annotationsFor(HTTPRoute route) {
        HttpMethod method = route.getMethod();

        if (method == null) {
            return annotations(false, true, false);
        }

        return switch (method) {
            case GET, HEAD, OPTIONS, TRACE -> annotations(true, false, true);
            case DELETE, PUT -> annotations(false, true, true);
            case POST, PATCH -> annotations(false, true, false);
        };
    }

    /**
     * Builds the annotations. {@code openWorldHint} is always {@code false}: the tools talk
     * to one configured REST API, not to an open-ended external world.
     */
    private static McpSchema.ToolAnnotations annotations(
            boolean readOnly,
            boolean destructive,
            boolean idempotent) {

        return McpSchema.ToolAnnotations.builder()
                .readOnlyHint(readOnly)
                .destructiveHint(destructive)
                .idempotentHint(idempotent)
                .openWorldHint(Boolean.FALSE)
                .build();
    }
}

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

import org.cibseven.mcp.restapi.models.HTTPRoute;
import org.springframework.security.core.Authentication;

/**
 * Generic, back-end-agnostic extension point for deciding, per authenticated request,
 * whether a given tool (OpenAPI operation) may be seen and invoked.
 *
 * <p>This library exposes <em>every</em> operation of the configured OpenAPI document as
 * an MCP tool. That keeps the mapping generic, but it means the target API's own
 * authorization is the only gate, applied reactively after the call is made. Supplying a
 * {@code ToolAccessPolicy} bean turns on a proactive gate: tools the policy rejects are
 * hidden from {@code tools/list} and refused at {@code tools/call} before any back-end
 * round trip.</p>
 *
 * <p>The library ships <strong>no</strong> implementation and knows nothing about any
 * particular engine's authorization model — that is deliberate. When no
 * {@code ToolAccessPolicy} bean is present the filtering plumbing stays dormant and the
 * server behaves exactly as before (all tools exposed). A concrete policy (e.g. one that
 * consults the CIB seven engine's per-user authorizations) is contributed by the hosting
 * application.</p>
 *
 * <p>Implementations are consulted on the request thread and may read the Spring
 * {@code SecurityContext}. They decide their own fail-open / fail-closed behaviour for
 * unmapped operations or back-end errors.</p>
 */
@FunctionalInterface
public interface ToolAccessPolicy {

    /**
     * @param authentication the validated principal of the inbound MCP request; never
     *                       {@code null} when the MCP endpoint is OAuth2-protected
     * @param route          the OpenAPI operation backing the tool under consideration
     * @return {@code true} to expose / allow the tool, {@code false} to hide / reject it
     */
    boolean isAllowed(Authentication authentication, HTTPRoute route);
}

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

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import org.cibseven.mcp.restapi.models.HTTPRoute;

/**
 * Immutable lookup from tool name ({@code operationId}) to the {@link HTTPRoute} that backs
 * it. Held as a dedicated type rather than a raw {@code Map<String, HTTPRoute>} bean, both
 * to give the tool-list filter a cheap route lookup and to avoid Spring's
 * {@code Map<String, T>} bean-collection injection convention.
 */
public final class RouteIndex {

    private final Map<String, HTTPRoute> byOperationId;

    public RouteIndex(Map<String, HTTPRoute> byOperationId) {
        this.byOperationId = Collections.unmodifiableMap(byOperationId);
    }

    /** @return the route for the given {@code operationId}, or {@code null} if unknown. */
    public HTTPRoute get(String operationId) {
        return byOperationId.get(operationId);
    }

    /** @return all indexed routes (those carrying an {@code operationId}). */
    public Collection<HTTPRoute> routes() {
        return byOperationId.values();
    }

    public int size() {
        return byOperationId.size();
    }
}

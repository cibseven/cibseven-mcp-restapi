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

import org.cibseven.mcp.restapi.models.HTTPRoute;
import org.cibseven.mcp.restapi.models.HttpMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.modelcontextprotocol.spec.McpSchema;

class ToolAnnotationPolicyTest {

    private final ToolAnnotationPolicy policy = new ToolAnnotationPolicy();

    private static HTTPRoute route(HttpMethod method, String path, String operationId) {
        HTTPRoute httpRoute = new HTTPRoute();
        httpRoute.setMethod(method);
        httpRoute.setPath(path);
        httpRoute.setOperationId(operationId);
        return httpRoute;
    }

    @ParameterizedTest
    @EnumSource(value = HttpMethod.class, names = {"GET", "HEAD", "OPTIONS", "TRACE"})
    void marksSafeMethodsReadOnly(HttpMethod method) {
        McpSchema.ToolAnnotations annotations =
                policy.annotationsFor(route(method, "/task/{id}", "getTask"));

        assertThat(annotations.readOnlyHint()).isTrue();
        assertThat(annotations.destructiveHint()).isFalse();
        assertThat(annotations.idempotentHint()).isTrue();
    }

    /** Both replace a resource in place, and both give the same result when repeated. */
    @ParameterizedTest
    @EnumSource(value = HttpMethod.class, names = {"DELETE", "PUT"})
    void marksReplacingMethodsDestructiveAndIdempotent(HttpMethod method) {
        McpSchema.ToolAnnotations annotations =
                policy.annotationsFor(route(method, "/task/{id}", "updateTask"));

        assertThat(annotations.readOnlyHint()).isFalse();
        assertThat(annotations.destructiveHint()).isTrue();
        assertThat(annotations.idempotentHint()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = HttpMethod.class, names = {"POST", "PATCH"})
    void marksPostAndPatchDestructiveAndNonIdempotent(HttpMethod method) {
        McpSchema.ToolAnnotations annotations =
                policy.annotationsFor(route(method, "/task/create", "createTask"));

        assertThat(annotations.readOnlyHint()).isFalse();
        assertThat(annotations.destructiveHint()).isTrue();
        assertThat(annotations.idempotentHint()).isFalse();
    }

    /**
     * The accepted cost of classifying by method alone. These engine operations only read,
     * but nothing in the method says so, and calling them a write is the safe direction.
     * The assertion is here so the trade-off stays visible rather than looking like a bug.
     */
    @Test
    void treatsQueryStylePostsAsWritesLikeAnyOtherPost() {
        assertThat(policy.annotationsFor(route(HttpMethod.POST, "/task", "queryTasks"))
                        .readOnlyHint())
                .isFalse();
        assertThat(policy.annotationsFor(
                                route(HttpMethod.POST, "/filter/{id}/list", "postExecuteFilterList"))
                        .readOnlyHint())
                .isFalse();
    }

    @Test
    void fallsBackToTheMostRestrictiveAnnotationsForAnUnknownMethod() {
        McpSchema.ToolAnnotations annotations =
                policy.annotationsFor(route(null, "/task", "queryTasks"));

        assertThat(annotations.readOnlyHint()).isFalse();
        assertThat(annotations.destructiveHint()).isTrue();
        assertThat(annotations.idempotentHint()).isFalse();
    }

    /**
     * An absent {@code readOnlyHint} is what makes a client lump every tool into one
     * undifferentiated group, so no method may leave it unset.
     */
    @ParameterizedTest
    @EnumSource(HttpMethod.class)
    void alwaysSetsReadOnlyHintSoClientsCanGroupTools(HttpMethod method) {
        assertThat(policy.annotationsFor(route(method, "/task", "someOperation"))
                        .readOnlyHint())
                .isNotNull();
    }

    @ParameterizedTest
    @EnumSource(HttpMethod.class)
    void closesTheWorldOnEveryTool(HttpMethod method) {
        assertThat(policy.annotationsFor(route(method, "/task", "someOperation"))
                        .openWorldHint())
                .isFalse();
    }
}

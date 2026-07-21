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
package org.cibseven.mcp.restapi.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.cibseven.mcp.restapi.FixtureSupport;
import org.cibseven.mcp.restapi.models.HTTPRoute;
import org.cibseven.mcp.restapi.models.ParameterLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OpenAPIParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static List<HTTPRoute> routes;

    @BeforeAll
    static void parseFixture() throws Exception {
        routes = FixtureSupport.parseFixtureRoutes();
    }

    @Test
    void parsesAllOperationsIncludingOnesWithoutOperationId() {
        // 7 operations in the fixture; the operationId-less one is still parsed as a route
        // (it is skipped later, at tool-building time)
        assertThat(routes).hasSize(7);
        assertThat(routes).extracting(HTTPRoute::getOperationId)
                .containsExactlyInAnyOrder("getTask", "createTask", "updateTask", "createDeployment",
                        "patchTask", "searchTasks", null);
    }

    @Test
    void extractsParametersWithLocations() {
        HTTPRoute getTask = FixtureSupport.route(routes, "getTask");
        assertThat(getTask.getParameters()).hasSize(3);
        assertThat(getTask.getParameters())
                .anySatisfy(p -> {
                    assertThat(p.getName()).isEqualTo("id");
                    assertThat(p.getLocation()).isEqualTo(ParameterLocation.PATH);
                    assertThat(p.isRequired()).isTrue();
                })
                .anySatisfy(p -> {
                    assertThat(p.getName()).isEqualTo("maxResults");
                    assertThat(p.getLocation()).isEqualTo(ParameterLocation.QUERY);
                })
                .anySatisfy(p -> {
                    assertThat(p.getName()).isEqualTo("X-Tenant");
                    assertThat(p.getLocation()).isEqualTo(ParameterLocation.HEADER);
                });
    }

    @Test
    void extractsRequestBodySchema() {
        HTTPRoute createTask = FixtureSupport.route(routes, "createTask");
        assertThat(createTask.getRequestBody()).isNotNull();
        assertThat(createTask.getRequestBody().isRequired()).isTrue();
        JsonNode schema = createTask.getRequestBody().getContentSchema().get("application/json");
        assertThat(schema).isNotNull();
        assertThat(schema.path("properties").has("name")).isTrue();
        assertThat(schema.path("properties").has("assignee")).isTrue();
    }

    @Test
    void flattensCollidingParamWithLocationSuffix() throws Exception {
        HTTPRoute createTask = FixtureSupport.route(routes, "createTask");
        Map<String, Map<String, String>> parameterMap = createTask.getParameterMap();

        // query param 'name' collides with body property 'name' -> suffixed flat name
        assertThat(parameterMap).containsKey("name__QUERY");
        assertThat(parameterMap.get("name__QUERY"))
                .containsEntry("location", "QUERY")
                .containsEntry("openapi_name", "name");
        assertThat(parameterMap.get("name"))
                .containsEntry("location", "BODY")
                .containsEntry("openapi_name", "name");
        assertThat(parameterMap.get("assignee")).containsEntry("location", "BODY");

        JsonNode flatSchema = JSON.readTree(createTask.getFlatParamSchema());
        assertThat(flatSchema.path("type").asText()).isEqualTo("object");
        assertThat(flatSchema.path("properties").has("name__QUERY")).isTrue();
        assertThat(flatSchema.path("properties").has("name")).isTrue();
        // body 'name' is required by the body schema
        assertThat(flatSchema.path("required")).anySatisfy(n -> assertThat(n.asText()).isEqualTo("name"));
    }

    @Test
    void rewritesBodyRefToDefsAndCarriesTransitiveDefinitions() throws Exception {
        // Pins the current $ref handling (see the NOTE/TODO in OpenAPIParser.extractSchemaAsMap):
        // a whole-body $ref becomes a single BODY property referencing #/$defs, and the
        // transitively referenced definitions (TaskDto -> Priority) travel in $defs.
        HTTPRoute updateTask = FixtureSupport.route(routes, "updateTask");
        JsonNode flatSchema = JSON.readTree(updateTask.getFlatParamSchema());

        JsonNode bodyProp = flatSchema.path("properties").path("BODY");
        assertThat(bodyProp.path("$ref").asText()).isEqualTo("#/$defs/TaskDto");
        assertThat(flatSchema.path("$defs").has("TaskDto")).isTrue();
        assertThat(flatSchema.path("$defs").has("Priority")).isTrue();
        assertThat(flatSchema.path("$defs").path("TaskDto")
                .path("properties").path("priority").path("$ref").asText())
                .isEqualTo("#/$defs/Priority");
    }

    @Test
    void extractsPrimarySuccessResponse() {
        HTTPRoute getTask = FixtureSupport.route(routes, "getTask");
        assertThat(getTask.getResponses()).containsKey("200");

        HTTPRoute createTask = FixtureSupport.route(routes, "createTask");
        assertThat(createTask.getResponses()).containsKey("201");
    }

    @Test
    void multipartSchemaIsPreserved() {
        HTTPRoute createDeployment = FixtureSupport.route(routes, "createDeployment");
        assertThat(createDeployment.getRequestBody().getContentSchema())
                .containsKey("multipart/form-data");
    }

    @Test
    void mergesTopLevelAllOfInRequestBody() throws Exception {
        // A body composed with allOf must have every subschema's properties and required
        // fields merged into the one flat body; otherwise the tool exposes an empty schema.
        HTTPRoute patchTask = FixtureSupport.route(routes, "patchTask");
        JsonNode flatSchema = JSON.readTree(patchTask.getFlatParamSchema());

        assertThat(flatSchema.path("properties").has("a")).isTrue();
        assertThat(flatSchema.path("properties").has("b")).isTrue();
        // the allOf wrapper itself must not leak into the flat schema
        assertThat(flatSchema.path("properties").has("allOf")).isFalse();
        assertThat(flatSchema.path("required")).anySatisfy(n -> assertThat(n.asText()).isEqualTo("b"));
    }

    @Test
    void rewritesRefsNestedInsideComposedKeywordsAndContainers() throws Exception {
        // #/components/schemas/... refs must be rewritten to #/$defs/... even when nested
        // inside additionalProperties (maps), anyOf and items (arrays) — not just as a
        // whole-body $ref (covered by rewritesBodyRefToDefsAndCarriesTransitiveDefinitions).
        HTTPRoute searchTasks = FixtureSupport.route(routes, "searchTasks");
        JsonNode flatSchema = JSON.readTree(searchTasks.getFlatParamSchema());
        JsonNode props = flatSchema.path("properties");

        assertThat(props.path("byPriority").path("additionalProperties").path("$ref").asText())
                .isEqualTo("#/$defs/Priority");
        assertThat(props.path("anyPriority").path("anyOf").get(0).path("$ref").asText())
                .isEqualTo("#/$defs/Priority");
        assertThat(props.path("priorities").path("items").path("$ref").asText())
                .isEqualTo("#/$defs/Priority");
        // the referenced definition travels alongside in $defs
        assertThat(flatSchema.path("$defs").has("Priority")).isTrue();
    }
}

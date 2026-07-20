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
package org.cibseven.mcp.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.cibseven.mcp.auth.EngineRestAuthProvider;
import org.cibseven.mcp.restapi.models.HTTPRoute;
import org.cibseven.mcp.restapi.models.UnflattenedArgs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Round-trip test for the flatten/unflatten contract: what
 * {@code SchemaUtils.combineSchemasAndMapParams} flattens into the MCP input schema,
 * {@code RESTAPIRequestDirector.unflattenArguments} must route back to the correct
 * HTTP request parts.
 */
class SchemaRoundTripTest {

    private static List<HTTPRoute> routes;
    private static RESTAPIRequestDirector director;

    @BeforeAll
    static void setUp() throws Exception {
        routes = FixtureSupport.parseFixtureRoutes();
        EngineRestAuthProvider noAuth = authentication -> Map.of();
        director = new RESTAPIRequestDirector("http://localhost/engine-rest", noAuth);
    }

    @Test
    void routesPathQueryAndHeaderArguments() {
        HTTPRoute getTask = FixtureSupport.route(routes, "getTask");

        UnflattenedArgs args = director.unflattenArguments(getTask, Map.of(
                "id", "42",
                "maxResults", "10",
                "X-Tenant", "tenant-1"));

        assertThat(args.pathParams).containsExactly(Map.entry("id", "42"));
        assertThat(args.queryParams).containsExactly(Map.entry("maxResults", "10"));
        assertThat(args.headerParams).containsExactly(Map.entry("X-Tenant", "tenant-1"));
        assertThat(args.body).isNull();
    }

    @Test
    void resolvesCollisionSuffixBackToQueryAndBody() {
        HTTPRoute createTask = FixtureSupport.route(routes, "createTask");

        UnflattenedArgs args = director.unflattenArguments(createTask, Map.of(
                "name__QUERY", "from-query",
                "name", "from-body",
                "assignee", "OlegSk"));

        assertThat(args.queryParams).containsExactly(Map.entry("name", "from-query"));
        assertThat(args.body).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) args.body;
        assertThat(body).containsEntry("name", "from-body").containsEntry("assignee", "OlegSk");
    }

    @Test
    void ignoresNullAndUnknownArguments() {
        HTTPRoute getTask = FixtureSupport.route(routes, "getTask");

        java.util.Map<String, Object> flatArgs = new java.util.HashMap<>();
        flatArgs.put("id", "42");
        flatArgs.put("maxResults", null);
        flatArgs.put("not-in-the-spec", "whatever");

        UnflattenedArgs args = director.unflattenArguments(getTask, flatArgs);

        assertThat(args.pathParams).containsExactly(Map.entry("id", "42"));
        assertThat(args.queryParams).isEmpty();
        assertThat(args.body).isNull();
    }
}

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

import java.nio.file.Paths;
import java.util.List;

import org.cibseven.mcp.restapi.models.HTTPRoute;
import org.cibseven.mcp.restapi.openapi.OpenAPIParser;
import org.cibseven.mcp.restapi.openapi.OpenApiProvider;

/** Shared access to the parsed test fixture spec. */
public final class FixtureSupport {

    private FixtureSupport() {
    }

    public static String fixturePath() {
        return Paths.get("src", "test", "resources", "openapi", "fixture-openapi.json")
                .toAbsolutePath().toString();
    }

    public static List<HTTPRoute> parseFixtureRoutes() throws Exception {
        OpenApiProvider provider = new OpenApiProvider(fixturePath());
        return new OpenAPIParser(provider.getOpenAPI()).parse();
    }

    public static HTTPRoute route(List<HTTPRoute> routes, String operationId) {
        return routes.stream()
                .filter(r -> operationId.equals(r.getOperationId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Fixture route not found: " + operationId));
    }
}

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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import org.cibseven.mcp.restapi.ToolAnnotationPolicy;
import org.cibseven.mcp.restapi.models.HTTPRoute;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The MCP tools generated from a real OpenAPI document (CIB seven 2.2.0, 401 operations) must
 * stay the same as the tools of version 0.0.5: name, title, description, input schema, the
 * mapping of the arguments to the HTTP request, and annotations. It guards dependency changes
 * (swagger-parser, swagger-core, Jackson) and changes of the schema code.
 *
 * <p>{@code golden/cibseven-2.2.0-tools.json.gz} was written by 0.0.5. The names in
 * {@code required} are compared as a set (which a JSON Schema {@code required} list is), so
 * that a parser version that orders them differently does not fail the test.</p>
 */
class ToolContractRegressionTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void toolsOfARealDocumentStayTheSameAsIn005() throws Exception {
        JsonNode golden;
        try (InputStream in = new GZIPInputStream(
                getClass().getResourceAsStream("/golden/cibseven-2.2.0-tools.json.gz"))) {
            golden = JSON.readTree(in);
        }

        // openapi.json of cibseven-engine-rest-openapi 2.2.0 (test dependency), as a file for swagger-parser
        Path spec = Files.createTempFile("cibseven-2.2.0-openapi", ".json");
        try (InputStream in = getClass().getResourceAsStream("/openapi.json")) {
            Files.copy(in, spec, StandardCopyOption.REPLACE_EXISTING);
        }
        List<HTTPRoute> routes = new OpenAPIParser(new OpenApiProvider(spec.toString()).getOpenAPI()).parse();
        Files.delete(spec);
        routes.sort(Comparator.comparing(HTTPRoute::getPath).thenComparing(r -> r.getMethod().name()));

        assertThat(routes).hasSize(golden.size());

        ToolAnnotationPolicy policy = new ToolAnnotationPolicy();
        List<String> different = new ArrayList<>();
        for (int i = 0; i < routes.size(); i++) {
            JsonNode actual = contract(routes.get(i), policy);
            if (!normalized(actual).equals(normalized(golden.get(i)))) {
                different.add(routes.get(i).getMethod() + " " + routes.get(i).getPath());
            }
        }
        assertThat(different).as("operations whose tool differs from 0.0.5").isEmpty();
    }

    private static JsonNode contract(HTTPRoute route, ToolAnnotationPolicy policy) {
        ObjectNode node = JSON.createObjectNode();
        node.put("name", route.getOperationId());
        node.put("method", route.getMethod().name());
        node.put("path", route.getPath());
        node.put("title", route.getSummary());
        node.put("description", route.getDescription());
        node.set("inputSchema", JSON.readTree(route.getFlatParamSchema()));
        node.set("parameterMap", JSON.valueToTree(new TreeMap<>(route.getParameterMap())));
        McpSchema.ToolAnnotations annotations = policy.annotationsFor(route);
        ObjectNode a = node.putObject("annotations");
        a.put("readOnlyHint", annotations.readOnlyHint());
        a.put("destructiveHint", annotations.destructiveHint());
        a.put("idempotentHint", annotations.idempotentHint());
        a.put("openWorldHint", annotations.openWorldHint());
        return node;
    }

    /** Sorts every {@code required} list, so that it is compared as a set. */
    private static JsonNode normalized(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = JSON.createObjectNode();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                JsonNode value = entry.getValue();
                if (entry.getKey().equals("required") && value.isArray()) {
                    List<String> names = new ArrayList<>();
                    value.forEach(n -> names.add(n.asString()));
                    names.sort(null);
                    ArrayNode sorted = copy.putArray("required");
                    names.forEach(sorted::add);
                } else {
                    copy.set(entry.getKey(), normalized(value));
                }
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JSON.createArrayNode();
            node.forEach(element -> copy.add(normalized(element)));
            return copy;
        }
        return node;
    }
}

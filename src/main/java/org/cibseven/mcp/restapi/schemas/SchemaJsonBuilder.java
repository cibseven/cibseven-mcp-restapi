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
package org.cibseven.mcp.restapi.schemas;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Serializes the flattened parameter/property maps produced by
 * {@link SchemaUtils#combineSchemasAndMapParams} into the final JSON-schema string
 * ({@code type}/{@code $defs}/{@code properties}/{@code required}) used as an MCP tool
 * input schema.
 */
public final class SchemaJsonBuilder {

    private SchemaJsonBuilder() {
    }

    public static String buildSchemaJson(
            List<String> required,
            Map<String, JsonNode> defs,
            Map<String, Object> properties
    ) throws Exception {

        ObjectNode root = SchemaUtils.MAPPER.createObjectNode();
        root.put("type", "object");

        // ---- $defs ----
        if (defs != null && !defs.isEmpty()) {
            ObjectNode defsNode = SchemaUtils.MAPPER.createObjectNode();
            for (Map.Entry<String, JsonNode> entry : defs.entrySet()) {
                ObjectNode reducedNode = (ObjectNode) SchemaUtils.replaceRefWithDefs(entry.getValue());
                defsNode.set(entry.getKey(), reducedNode);
            }

            root.set("$defs", defsNode);
        }

        // ---- properties ----
        if (properties != null && !properties.isEmpty()) {
            ObjectNode propsNode = SchemaUtils.MAPPER.createObjectNode();

            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                ObjectNode reducedNode;
                if (entry.getValue() instanceof JsonNode jsonNode && jsonNode.isObject()) {
                    reducedNode = (ObjectNode) SchemaUtils.replaceRefWithDefs(jsonNode);
                } else {
                    throw new IllegalArgumentException(
                            "Properties value must be a JsonNode object: " + entry.getKey()
                    );
                }
                propsNode.set(entry.getKey(), reducedNode);
            }

            root.set("properties", propsNode);
        }
        // Deal with failed validation: schema must have a properties object
        else root.set("properties", SchemaUtils.MAPPER.createObjectNode());

        // ---- required ----
        if (required != null && !required.isEmpty()) {
            ArrayNode requiredArray = SchemaUtils.MAPPER.createArrayNode();
            required.forEach(requiredArray::add);
            root.set("required", requiredArray);
        }

        return SchemaUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }
}

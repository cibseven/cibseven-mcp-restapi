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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.cibseven.mcp.restapi.models.HTTPRoute;
import org.cibseven.mcp.restapi.models.ParameterInfo;
import org.cibseven.mcp.restapi.models.ParameterLocation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Flattens an operation's OpenAPI parameters and request-body properties into the single
 * JSON-schema object that MCP tools expect as their input schema.
 */
public final class SchemaUtils {

    /**
     * Shared Jackson mapper for schema processing. Never reconfigure it — it is used
     * concurrently across the parser, the schema builder and the request director, and
     * configuration changes would affect all of them.
     */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private SchemaUtils() {
    }

    /**
     * Combines path/query/header parameters and request-body properties into one flat
     * JSON-schema {@code object} (the MCP tool input schema) and records, per flat argument
     * name, where it belongs in the HTTP request.
     *
     * <p>Name collisions between body properties and non-body parameters are disambiguated
     * by suffixing the non-body argument with {@code __<LOCATION>} (e.g. {@code id__PATH}).
     * Must stay in sync with the reversing logic in
     * {@code RESTAPIRequestDirector.unflattenArguments}.</p>
     *
     * @param route the parsed operation
     * @param convertRefs whether to rewrite {@code #/components/schemas/...} references to
     *        {@code #/$defs/...} and prune the definitions to those transitively used
     * @return the serialized flat schema plus the flat-argument-name → location map
     */
    public static CombinedSchemaResult combineSchemasAndMapParams(
            HTTPRoute route,
            boolean convertRefs
    ) throws Exception {
        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();
        Map<String, Map<String, String>> parameterMap = new HashMap<>();

        // Track parameter names by location
        Map<String, Set<String>> paramNamesByLocation = Arrays.stream(ParameterLocation.values())
                .collect(Collectors.toMap(
                        loc -> loc.name(),
                        loc -> new HashSet<>()
                ));

        // Step 1: Collect parameter names
        for (ParameterInfo param : route.getParameters()) {
            paramNamesByLocation.get(param.getLocation().name()).add(param.getName());
        }

        JsonNode bodySchema = MAPPER.createObjectNode();
        JsonNode bodyPropsNode = null;
        if (route.getRequestBody() != null &&
                route.getRequestBody().getContentSchema() != null &&
                !route.getRequestBody().getContentSchema().isEmpty()) {

            String contentType = route.getRequestBody().getContentSchema().keySet().iterator().next();

            // Convert refs if needed
            if (convertRefs) {
                bodySchema = replaceRefWithDefs(route.getRequestBody().getContentSchema().get(contentType));
            } else {
                bodySchema = route.getRequestBody().getContentSchema().get(contentType);
            }

            String schemaDesc = bodySchema.path("description").asText(null);
            if (route.getRequestBody().getDescription() != null &&
               (schemaDesc == null || schemaDesc.isEmpty())) {
            	((ObjectNode) bodySchema).put("description", route.getRequestBody().getDescription());
            }

            // Handle allOf at the top level by merging all schemas
            JsonNode allOf = bodySchema.path("allOf");
            if (allOf.isArray() && allOf.size() > 0) {
                ObjectNode mergedProps = MAPPER.createObjectNode();
                Set<String> mergedRequiredSet = new LinkedHashSet<>();

                for (JsonNode subSchema : allOf) {
                    // Note: $ref-only subschemas contribute no inline properties here; their
                    // referenced definitions still travel via $defs and stay resolvable.
                    JsonNode subProps = subSchema.path("properties");
                    if (subProps.isObject()) {
                        subProps.properties().forEach(e -> mergedProps.set(e.getKey(), e.getValue()));
                    }
                    JsonNode subRequired = subSchema.path("required");
                    if (subRequired.isArray()) {
                        subRequired.forEach(n -> mergedRequiredSet.add(n.asText()));
                    }
                }

                ((ObjectNode) bodySchema).set("properties", mergedProps);
                if (!mergedRequiredSet.isEmpty()) {
                	// Remove duplicates while preserving order
                    ArrayNode deduped = MAPPER.createArrayNode();
                    mergedRequiredSet.forEach(deduped::add);
                    ((ObjectNode) bodySchema).set("required", deduped);
                }
                // Remove the allOf since we've merged it
                ((ObjectNode) bodySchema).remove("allOf");
            }

            bodyPropsNode = bodySchema.path("properties");
            if (!bodyPropsNode.isObject()) bodyPropsNode = null;
        }

        // Detect collisions: parameters that exist in both body and path/query/header
        Set<String> allNonBodyParams = new HashSet<>();
        for (Set<String> locParams : paramNamesByLocation.values()) {
            allNonBodyParams.addAll(locParams);
        }
        Set<String> bodyParamNames = new HashSet<>();
        if (bodyPropsNode != null) {
            bodyPropsNode.fieldNames().forEachRemaining(bodyParamNames::add);
        }
        Set<String> collidingParams = new HashSet<>(allNonBodyParams);
        collidingParams.retainAll(bodyParamNames);

        // Add parameters with suffixes for collisions
        for (ParameterInfo param : route.getParameters()) {
            boolean isCollision = collidingParams.contains(param.getName());
            // Add suffix for non-body parameters when collision detected
            String paramName = isCollision ? param.getName() + "__" + param.getLocation() : param.getName();

            if (param.isRequired()) required.add(paramName);

            Map<String, String> mapping = Map.of(
                    "location", param.getLocation().name(),
                    "openapi_name", param.getName()
            );
            // Track parameter mapping
            parameterMap.put(paramName, mapping);

            // Convert refs if needed
            JsonNode paramSchemaNode;
            if (convertRefs) {
                paramSchemaNode = replaceRefWithDefs(param.getSchema(), param.getDescription());
            } else {
                paramSchemaNode = param.getSchema();
            }
            if (paramSchemaNode == null) paramSchemaNode = MAPPER.createObjectNode();
            if (paramSchemaNode.isObject()) {
                ObjectNode paramSchemaObj = (ObjectNode) paramSchemaNode;
                if (!convertRefs && param.getDescription() != null) {
                    String existingDesc = paramSchemaObj.path("description").asText(null);
                    if (existingDesc == null || existingDesc.isEmpty()) {
                        paramSchemaObj.put("description", param.getDescription());
                    }
                }
                String originalDesc = paramSchemaObj.path("description").asText(null);
                String locationDesc = "(" + param.getLocation().name() + " parameter)";
                paramSchemaObj.put("description", originalDesc != null && !originalDesc.isEmpty() ? originalDesc + " " + locationDesc : locationDesc);
            }

            properties.put(paramName, paramSchemaNode);
        }

        // Add request body properties (no suffixes for body parameters)
        if (route.getRequestBody() != null && route.getRequestBody().getContentSchema() != null) {
            String ref = bodySchema.path("$ref").asText(null);
            boolean bodyPropsEmpty = bodyPropsNode == null || bodyPropsNode.isEmpty();
            if (ref != null && !ref.isEmpty() && bodyPropsEmpty) {
            	// The entire body is a reference to a schema
                // We need to expand this inline or keep the ref
                // For simplicity, we'll keep it as a single property
                properties.put(ParameterLocation.BODY.name(), bodySchema);
                if (route.getRequestBody().isRequired()) required.add(ParameterLocation.BODY.name());
                parameterMap.put(ParameterLocation.BODY.name(), Map.of("location", ParameterLocation.BODY.name(), "openapi_name", ParameterLocation.BODY.name()));
            } else if (!bodyPropsEmpty) {
            	// Normal case: body has properties
                final JsonNode finalBodyPropsNode = bodyPropsNode;
                finalBodyPropsNode.properties().forEach(e -> {
                    properties.put(e.getKey(), e.getValue());
                    // Track parameter mapping for body properties
                    parameterMap.put(e.getKey(), Map.of("location", ParameterLocation.BODY.name(), "openapi_name", e.getKey()));
                });
                JsonNode reqNode = bodySchema.path("required");
                if (route.getRequestBody().isRequired() && reqNode.isArray()) {
                    reqNode.forEach(n -> required.add(n.asText()));
                }
            } else {
                // Handle direct array/primitive schemas (like list[str] parameters from FastAPI)
                // Use the schema title as parameter name, fall back to generic name
                String title = bodySchema.path("title").asText(null);
                String paramName = title != null && !title.isEmpty() ? title.toLowerCase() : ParameterLocation.BODY.name().toLowerCase();
                paramName = paramName.replaceAll("[^a-zA-Z0-9_]", "_");
                if (paramName.isEmpty() || Character.isDigit(paramName.charAt(0))) paramName = "body_data";
                properties.put(paramName, bodySchema);
                if (route.getRequestBody().isRequired()) required.add(paramName);
                parameterMap.put(paramName, Map.of("location", ParameterLocation.BODY.name(), "openapi_name", paramName));
            }
        }

        // Add schema definitions if available — convert and prune to transitively used refs only
        Map<String, JsonNode> schemaDefs = route.getRequestSchemas();
        Map<String, JsonNode> prunedDefs = null;
        if (schemaDefs != null && !schemaDefs.isEmpty()) {
            if (convertRefs) {
                // Convert each def recursively
                Map<String, JsonNode> allDefs = new HashMap<>();
                for (Map.Entry<String, JsonNode> entry : schemaDefs.entrySet()) {
                    allDefs.put(entry.getKey(), replaceRefWithDefs(entry.getValue()));
                }
                // Find which $defs are referenced in properties
                Set<String> usedRefs = new HashSet<>();
                for (Object val : properties.values()) {
                    if (val instanceof JsonNode jn) {
                        Deque<JsonNode> stack = new ArrayDeque<>();
                        stack.push(jn);
                        while (!stack.isEmpty()) {
                            JsonNode cur = stack.pop();
                            if (cur == null || cur.isNull() || cur.isMissingNode()) continue;
                            if (cur.isObject()) {
                                JsonNode ref = cur.get("$ref");
                                if (ref != null && ref.isTextual()) {
                                    String r = ref.asText();
                                    if (r.startsWith("#/$defs/")) {
                                        usedRefs.add(r.substring(r.lastIndexOf('/') + 1));
                                    }
                                }
                                cur.properties().forEach(e -> stack.push(e.getValue()));
                            } else if (cur.isArray()) {
                                cur.forEach(stack::push);
                            }
                        }
                    }
                }
                // Expand transitively
                if (!usedRefs.isEmpty()) {
                    boolean changed;
                    do {
                        int before = usedRefs.size();
                        for (String name : new ArrayList<>(usedRefs)) {
                            JsonNode defNode = allDefs.get(name);
                            if (defNode != null) {
                                Deque<JsonNode> stack = new ArrayDeque<>();
                                stack.push(defNode);
                                while (!stack.isEmpty()) {
                                    JsonNode cur = stack.pop();
                                    if (cur == null || cur.isNull() || cur.isMissingNode()) continue;
                                    if (cur.isObject()) {
                                        JsonNode ref = cur.get("$ref");
                                        if (ref != null && ref.isTextual()) {
                                            String r = ref.asText();
                                            if (r.startsWith("#/$defs/")) {
                                                usedRefs.add(r.substring(r.lastIndexOf('/') + 1));
                                            }
                                        }
                                        cur.properties().forEach(e -> stack.push(e.getValue()));
                                    } else if (cur.isArray()) {
                                        cur.forEach(stack::push);
                                    }
                                }
                            }
                        }
                        changed = usedRefs.size() != before;
                    } while (changed);
                    prunedDefs = new HashMap<>();
                    for (String name : usedRefs) {
                        if (allDefs.containsKey(name)) prunedDefs.put(name, allDefs.get(name));
                    }
                }
            } else {
                prunedDefs = schemaDefs;
            }
        }

        // Construct final schema
        String result = SchemaJsonBuilder.buildSchemaJson(required, prunedDefs, properties);

        return new CombinedSchemaResult(result, parameterMap);
    }

    /**
     * Recursively rewrites local {@code #/components/schemas/...} references to
     * {@code #/$defs/...} (the form MCP input schemas use) throughout a schema node.
     *
     * @throws IllegalArgumentException for external/non-local references, which cannot be
     *         represented in a self-contained tool schema
     */
    public static JsonNode replaceRefWithDefs(JsonNode schema) {
        return replaceRefWithDefs(schema, null);
    }

    private static JsonNode replaceRefWithDefs(JsonNode schema, String description) {
        if (schema == null || !schema.isObject()) return schema;

        ObjectNode result = schema.deepCopy();

        JsonNode refNode = result.path("$ref");
        if (!refNode.isMissingNode() && !refNode.isNull() && refNode.isTextual()) {
            String refPath = refNode.asText();
            if (refPath.startsWith("#/components/schemas/")) {
                String schemaName = refPath.substring(refPath.lastIndexOf("/") + 1);
                result.put("$ref", "#/$defs/" + schemaName);
            } else if (!refPath.startsWith("#/")) {
                throw new IllegalArgumentException(
                    "External or non-local reference not supported: " + refPath +
                    ". CIB seven MCP only supports local schema references starting with '#/'." +
                    " Please include all schema definitions within the OpenAPI document."
                );
            }
        } else {
            JsonNode propsNode = result.path("properties");
            if (!propsNode.isMissingNode() && propsNode.isObject()) {
                if (propsNode.has("$ref")) {
                    result.set("properties", replaceRefWithDefs(propsNode));
                } else {
                    ObjectNode newProps = MAPPER.createObjectNode();
                    propsNode.properties().forEach(entry ->
                        newProps.set(entry.getKey(), replaceRefWithDefs(entry.getValue()))
                    );
                    result.set("properties", newProps);
                }
            } else {
                JsonNode itemsNode = result.path("items");
                if (!itemsNode.isMissingNode() && !itemsNode.isNull()) {
                    result.set("items", replaceRefWithDefs(itemsNode));
                }
            }
        }

        for (String section : new String[]{"anyOf", "allOf", "oneOf"}) {
            JsonNode sectionNode = result.path(section);
            if (sectionNode.isArray()) {
                ArrayNode newArray = MAPPER.createArrayNode();
                for (JsonNode item : sectionNode) {
                    newArray.add(replaceRefWithDefs(item));
                }
                result.set(section, newArray);
            }
        }

        JsonNode ap = result.path("additionalProperties");
        if (!ap.isMissingNode() && !ap.isNull() && !ap.isBoolean()) {
            result.set("additionalProperties", replaceRefWithDefs(ap));
        }

        JsonNode propertyNames = result.path("propertyNames");
        if (!propertyNames.isMissingNode() && !propertyNames.isNull() && propertyNames.isObject()) {
            result.set("propertyNames", replaceRefWithDefs(propertyNames));
        }

        JsonNode patternProperties = result.path("patternProperties");
        if (!patternProperties.isMissingNode() && patternProperties.isObject()) {
            ObjectNode newPatternProps = MAPPER.createObjectNode();
            patternProperties.properties().forEach(entry -> {
                JsonNode subSchema = entry.getValue();
                newPatternProps.set(entry.getKey(), subSchema.isObject() ? replaceRefWithDefs(subSchema) : subSchema);
            });
            result.set("patternProperties", newPatternProps);
        }

        if (description != null && !description.isEmpty()) {
            JsonNode existingDesc = result.path("description");
            if (existingDesc.isMissingNode() || existingDesc.isNull() || existingDesc.asText("").isEmpty()) {
                result.put("description", description);
            }
        }

        return result;
    }

}

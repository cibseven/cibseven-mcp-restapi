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

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.cibseven.mcp.restapi.models.HTTPRoute;
import org.cibseven.mcp.restapi.models.HttpMethod;
import org.cibseven.mcp.restapi.models.ParameterInfo;
import org.cibseven.mcp.restapi.models.ParameterLocation;
import org.cibseven.mcp.restapi.models.RequestBodyInfo;
import org.cibseven.mcp.restapi.models.ResponseInfo;
import org.cibseven.mcp.restapi.schemas.CombinedSchemaResult;
import org.cibseven.mcp.restapi.schemas.SchemaUtils;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.swagger.v3.oas.models.responses.*;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.reference.Reference;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.Parameter.StyleEnum;
import io.swagger.v3.oas.models.parameters.RequestBody;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Walks a parsed OpenAPI document and turns every operation into an {@link HTTPRoute}:
 * merged path-item/operation parameters, request body, primary success response, the
 * component schemas the operation transitively references, and the pre-calculated flat
 * MCP input schema (via {@link SchemaUtils#combineSchemasAndMapParams}).
 *
 * <p>Parsing is deliberately resilient: a malformed parameter, body or operation is
 * logged and skipped rather than failing the whole document — one bad operation must
 * not take down the remaining tool set.</p>
 */
public class OpenAPIParser {
    private static final Logger logger = LoggerFactory.getLogger(OpenAPIParser.class);
    // Mixin required due to leakage produced by ObjectMapper: https://github.com/swagger-api/swagger-core/issues/3702
    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .addMixIn(io.swagger.v3.oas.models.media.Schema.class, SwaggerSchemaMixin.class)
        .defaultPropertyInclusion(JsonInclude.Value.ALL_NON_NULL)
        .build();

    private final OpenAPI openapi;

    public OpenAPIParser(OpenAPI openapi) {
    	this.openapi = openapi;
    }
    
    private ParameterLocation convertToParameterLocation(String paramIn) {
        switch (paramIn) {
            case "path":
                return ParameterLocation.PATH;
            case "query":
                return ParameterLocation.QUERY;
            case "header":
                return ParameterLocation.HEADER;
            case "cookie":
                return ParameterLocation.COOKIE;
            default:
                logger.warn("Unknown parameter location: {}, defaulting to 'query'", paramIn);
                return ParameterLocation.QUERY;
        }
    }

    // It seems to be only useful if there are Reference classes
    private Object resolveRef(Object item) throws Exception {
        if (item == null) return null;
        if (item instanceof Reference itemRef) {

	        String refStr = itemRef.getUri();
	        if (refStr == null || !refStr.startsWith("#/")) {
	            throw new IllegalArgumentException(
	                "External or non-local reference not supported: " + refStr
	            );
	        }
	
	        // Strip "#/" and split path
	        String[] parts = refStr.substring(2).split("/");
	        Object target = openapi; // top-level OpenAPI object
	
	        for (String part : parts) {
	            if (target instanceof Map<?, ?> map) {
	                target = map.get(part);
	            } else if (target instanceof List<?> list) {
	                int index = Integer.parseInt(part);
	                target = list.get(index);
	            } else {
	                throw new IllegalArgumentException(
	                    "Cannot traverse part '" + part + "' in reference '" + refStr + "'"
	                );
	            }
	
	            if (target == null) {
	                throw new IllegalArgumentException(
	                    "Reference part '" + part + "' not found in path '" + refStr + "'"
	                );
	            }
	        }
	
	        // Recursively resolve nested references
	        if (target instanceof Reference refTarget) {
	            return resolveRef(refTarget);
	        }	        
	        return target;
        }
        return item;
    }
    
    private JsonNode extractSchemaAsMap(Object schemaObj) {
        try {
            Object resolvedSchema = resolveRef(schemaObj);

            if (resolvedSchema == null) {
               return MAPPER.createObjectNode();
            }

            // NOTE: intentionally serializes the original (possibly $ref-carrying) object,
            // not resolvedSchema — the $refs are rewritten to #/$defs/... downstream and the
            // referenced definitions travel alongside in the route's schema dependencies.
            // Serializing resolvedSchema here would inline every reference and change all
            // generated tool input schemas.
            // TODO(post-release): evaluate serializing resolvedSchema instead (needs a
            // regression comparison of the generated schemas against a full CIB seven spec).
            JsonNode result = MAPPER.valueToTree(schemaObj);

            // Recursively replace $ref with definitions
            return SchemaUtils.replaceRefWithDefs(result);
        } catch (IllegalArgumentException e) {
            if (e.getMessage().contains("External or non-local reference not supported")) {
                throw e;
            }
            logger.error("Failed to extract schema", e);
            return MAPPER.createObjectNode();
        } catch (Exception e) {
            logger.error("Failed to extract schema", e);
            return MAPPER.createObjectNode();
        }
    }

    private List<ParameterInfo> extractParameters(
            List<Parameter> operationParams,
            List<Parameter> pathItemParams
    ) {
        List<ParameterInfo> extractedParams = new ArrayList<>();
        Map<String, Set<String>> seenParams = new HashMap<>(); // name -> set of locations

        List<Object> allParams = new ArrayList<>();
        if (operationParams != null) allParams.addAll(operationParams);
        if (pathItemParams != null) allParams.addAll(pathItemParams);

        for (Object paramOrRef : allParams) {
            try {
                // Resolve $ref if necessary
                Object parameterObj = resolveRef(paramOrRef);

				if (!(parameterObj instanceof Parameter parameter)) {
					logger.warn("Expected Parameter after resolving, got {}. Skipping.",
							parameterObj == null ? "null" : parameterObj.getClass());
					continue;
				}

                // Extract name
                String paramName = parameter.getName();

                // Extract param_in (location)
                String paramInStr = parameter.getIn();

                ParameterLocation paramLocation = convertToParameterLocation(paramInStr);

                // Skip duplicate parameters (same name and location)
                seenParams.putIfAbsent(paramName, new HashSet<>());
                if (seenParams.get(paramName).contains(paramInStr)) {
                    continue;
                }
                seenParams.get(paramName).add(paramInStr);

                // Extract schema
                ObjectNode paramSchemaMap = MAPPER.createObjectNode();
                Schema paramSchemaObj = parameter.getSchema();

                if (paramSchemaObj != null) {
                	// Process schema object
                    paramSchemaMap = (ObjectNode) extractSchemaAsMap(paramSchemaObj);

                    // Handle default value
                    Object resolvedSchema = resolveRef(paramSchemaObj);
                    if (!(resolvedSchema instanceof Reference) && 
                    		(resolvedSchema instanceof Schema resolvedSchemaSchema && resolvedSchemaSchema.getDefault() != null)) {
                    	paramSchemaMap.set("default", MAPPER.valueToTree(resolvedSchemaSchema.getDefault()));
					}
                }
                else if (parameter.getContent() != null && !parameter.getContent().isEmpty()) {
					// Content-based parameter (RFC: 'content' instead of 'schema') — use the first media type
					Map<String, MediaType> content = parameter.getContent();
					MediaType firstMediaType = content.values().iterator().next();
					if (firstMediaType != null && firstMediaType.getSchema() != null) {
						paramSchemaMap = (ObjectNode) extractSchemaAsMap(firstMediaType.getSchema());

						// Handle default value in content schema
						Object resolvedMediaSchema = resolveRef(firstMediaType.getSchema());
						if (!(resolvedMediaSchema instanceof Reference) &&
								(resolvedMediaSchema instanceof Schema resolvedSchema && resolvedSchema.getDefault() != null)) {
							paramSchemaMap.set("default", MAPPER.valueToTree(resolvedSchema.getDefault()));
						}
					}
				}

                // Extract explode and style properties if present
                Boolean explode = parameter.getExplode();
                StyleEnum style = parameter.getStyle();
                
                // Extract description
                String description = parameter.getDescription();

                // Required
                boolean required = parameter.getRequired() != null ? parameter.getRequired() : false;

                // Create ParameterInfo
                ParameterInfo paramInfo = new ParameterInfo(
                        paramName,
                        paramLocation,
                        required,
                        paramSchemaMap,
                        description,
                        explode,
                        style
                );

                extractedParams.add(paramInfo);

            } catch (Exception e) {
                // Resilience: skip the broken parameter, keep the rest of the operation usable
                logger.error("Failed to extract parameter {}", describeForLog(paramOrRef), e);
            }
        }

        return extractedParams;
    }
    
    private RequestBodyInfo extractRequestBody(Object requestBodyOrRef) {
        if (requestBodyOrRef == null) return null;

        try {
            Object requestBodyObj = resolveRef(requestBodyOrRef);

        	if (!(requestBodyObj instanceof RequestBody requestBody)) {
				logger.warn("Expected RequestBody after resolving, got {}. Skipping.",
						requestBodyObj == null ? "null" : requestBodyObj.getClass());
				return null;
			}
        	RequestBodyInfo requestBodyInfo = new RequestBodyInfo();
        	requestBodyInfo.setRequired(requestBody.getRequired() != null ? requestBody.getRequired() : false);
        	requestBodyInfo.setDescription(requestBody.getDescription());

            LinkedHashMap<String, MediaType> content = requestBody.getContent();
            
            // Extract content schemas
            if (content != null && !content.isEmpty()) {
                for (Entry<String, MediaType> entry : content.entrySet()) {
                    String mediaTypeStr = entry.getKey();
                    MediaType mediaType = entry.getValue();
                    if (mediaType != null && mediaType.getSchema() != null) {
                    	JsonNode schemaNode = extractSchemaAsMap(mediaType.getSchema());
                    	requestBodyInfo.getContentSchema().put(mediaTypeStr, schemaNode);
					}
                }
            }

            return requestBodyInfo;

        } catch (IllegalArgumentException e) {
            if (e.getMessage().contains("External or non-local reference not supported")) {
                throw e;
            }
            logger.error("Failed to extract request body {}", describeForLog(requestBodyOrRef), e);
            return null;

        } catch (Exception e) {
            logger.error("Failed to extract request body {}", describeForLog(requestBodyOrRef), e);
            return null;
        }
    }
    
    private boolean isSuccessStatusCode(String statusCode) {
        if (statusCode == null) return false;

        try {
            int codeInt = Integer.parseInt(statusCode);
            return codeInt >= 200 && codeInt < 300;
        } catch (NumberFormatException e) {
            // Handle non-numeric codes like "default" or "2xx"
            String normalized = statusCode.toLowerCase();
            return normalized.equals("default") || normalized.equals("2xx");
        }
    }

    private Map.Entry<String, Object> getPrimarySuccessResponse(ApiResponses operationResponses) {
        if (operationResponses == null || operationResponses.isEmpty()) {
            return null;
        }

        // Priority order: 200, 201, 202, 204, 207
        List<String> priorityCodes = List.of("200", "201", "202", "204", "207");

        // First check priority codes
        for (String code : priorityCodes) {
            if (operationResponses.containsKey(code)) {
                return new AbstractMap.SimpleEntry<>(code, operationResponses.get(code));
            }
        }

        // Then check any other 2xx codes
        for (Map.Entry<String, ApiResponse> entry : operationResponses.entrySet()) {
            String statusCode = entry.getKey();
            if (isSuccessStatusCode(statusCode)) {
                return new AbstractMap.SimpleEntry<>(statusCode, entry.getValue());
            }
        }

        // If no success codes found, return None (tool will have no output schema)
        return null;
    }

    private Map<String, ResponseInfo> extractResponses(ApiResponses apiResponses) {
        Map<String, ResponseInfo> extractedResponses = new HashMap<>();
        if (apiResponses == null || apiResponses.isEmpty()) {
            return extractedResponses;
        }

        // For MCP tools, we only need the primary success response
        Map.Entry<String, Object> primaryResponseEntry = getPrimarySuccessResponse(apiResponses);
        if (primaryResponseEntry == null) {
            logger.warn("No success responses found, tool will have no output schema");
            return extractedResponses;
        }

        String statusCode = primaryResponseEntry.getKey();
        Object respOrRef = primaryResponseEntry.getValue();
        logger.debug("Using primary success response: {}", statusCode);

        try {
            Object responseObj = resolveRef(respOrRef);

            if (!(responseObj instanceof ApiResponse response)) {
                logger.warn("Expected Response after resolving for status code {}, got {}. Returning empty responses.",
                        statusCode, responseObj == null ? "null" : responseObj.getClass());
                return extractedResponses;
            }

            ResponseInfo respInfo = new ResponseInfo(); 
            respInfo.setDescription(response.getDescription());

            // Extract content schemas
            Map<String, MediaType> content = response.getContent();
            if (content != null) {
                for (Map.Entry<String, MediaType> entry : content.entrySet()) {
                    String mediaTypeStr = entry.getKey();
                    MediaType mediaType = entry.getValue();
                    if (mediaType == null) continue;

                    Schema mediaSchema = mediaType.getSchema();
                    if (mediaSchema != null) {
                        try {
                            JsonNode schemaNode = extractSchemaAsMap(mediaSchema);
                            respInfo.getContentSchema().put(mediaTypeStr, schemaNode);
                        } catch (IllegalArgumentException e) {
                            if (e.getMessage().contains("External or non-local reference not supported")) {
                                throw e;
                            }
                            logger.error("Failed to extract schema for media type '{}' in response {}",
                                    mediaTypeStr, statusCode, e);
                        } catch (Exception e) {
                            logger.error("Failed to extract schema for media type '{}' in response {}",
                                    mediaTypeStr, statusCode, e);
                        }
                    } else {
                        // Record the media type even without a schema so MIME
                        // type inference can still use the declared content type.
                        respInfo.getContentSchema().putIfAbsent(mediaTypeStr, MAPPER.createObjectNode());
                    }
                }
            }

            extractedResponses.put(statusCode, respInfo);

        } catch (IllegalArgumentException e) {
            if (e.getMessage().contains("External or non-local reference not supported")) {
                throw e;
            }
            logger.error("Failed to extract response for status code {} from {}",
                    statusCode, describeForLog(respOrRef), e);
        } catch (Exception e) {
            logger.error("Failed to extract response for status code {} from {}",
                    statusCode, describeForLog(respOrRef), e);
        }

        return extractedResponses;
    }
    
    private Set<String> extractSchemaDependencies(
            JsonNode schema,
            Map<String, JsonNode> allSchemas) {
    
    	Set<String> collected = new HashSet<>();

        findRefs(schema, allSchemas, collected);
        return collected;
    }

    private void findRefs(
            JsonNode schema,
            Map<String, JsonNode> allSchemas,
            Set<String> collected
    ) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) return;

        // Check for $ref
        JsonNode refNode = schema.path("$ref");
        if (!refNode.isMissingNode() && !refNode.isNull()) {
            String schemaName = extractSchemaName(refNode.asText());
            if (schemaName != null && !collected.contains(schemaName) && allSchemas.containsKey(schemaName)) {
                collected.add(schemaName);
                findRefs(allSchemas.get(schemaName), allSchemas, collected);
            }
        }

        // Properties
        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            properties.properties().forEach(entry -> findRefs(entry.getValue(), allSchemas, collected));
        }

        // Items
        JsonNode items = schema.path("items");
        if (!items.isMissingNode() && !items.isNull()) {
            findRefs(items, allSchemas, collected);
        }

        // Composed schemas
        for (String composedKey : new String[]{"allOf", "anyOf", "oneOf"}) {
            JsonNode composed = schema.path(composedKey);
            if (composed.isArray()) {
                composed.forEach(s -> findRefs(s, allSchemas, collected));
            }
        }

        // additionalProperties
        JsonNode ap = schema.path("additionalProperties");
        if (ap.isObject()) {
            findRefs(ap, allSchemas, collected);
        }

    }
    
    private String extractSchemaName(String ref) {
        if (ref.startsWith("#/$defs/") || ref.startsWith("#/components/schemas/")) {
            String[] parts = ref.split("/");
            return parts[parts.length - 1];
        }
        return null;
    }
    
    private Map<String, JsonNode> extractInputSchemaDependencies(
            List<ParameterInfo> parameters,
            RequestBodyInfo requestBody,
            Map<String, JsonNode> allSchemas
    ) {
        Set<String> neededSchemas = new HashSet<>();

        // Check parameters for schema references

        for (ParameterInfo param : parameters) {
            if (param.getSchema() != null) {
                Set<String> deps = extractSchemaDependencies(param.getSchema(), allSchemas);
                neededSchemas.addAll(deps);
            }
        }

        // Check request body for schema references
        if (requestBody != null && requestBody.getContentSchema() != null) {
            for (Map.Entry<String, JsonNode> contentSchema : requestBody.getContentSchema().entrySet()) {
                Set<String> deps = extractSchemaDependencies(contentSchema.getValue(), allSchemas);
                neededSchemas.addAll(deps);
            }
        }

        // Return only the needed input schemas
        Map<String, JsonNode> result = new HashMap<>();
        for (String name : neededSchemas) {
            if (allSchemas.containsKey(name)) {
                result.put(name, allSchemas.get(name));
            }
        }

        return result;
    }

    private Map<String, JsonNode> extractOutputSchemaDependencies(
            Map<String, ResponseInfo> responses,
            Map<String, JsonNode> allSchemas
    ) {
        if (responses == null || responses.isEmpty() || allSchemas == null || allSchemas.isEmpty()) {
            return new HashMap<>();
        }

        Set<String> neededSchemas = new HashSet<>();

        for (ResponseInfo response : responses.values()) {
            if (response.getContentSchema() == null) continue;

            for (Map.Entry<String, JsonNode> contentSchema : response.getContentSchema().entrySet()) {
                JsonNode schemaNode = contentSchema.getValue();
                Set<String> deps = extractSchemaDependencies(schemaNode, allSchemas);
                neededSchemas.addAll(deps);
            }
        }

        // Return only the needed output schemas
        Map<String, JsonNode> result = new HashMap<>();
        for (String name : neededSchemas) {
            if (allSchemas.containsKey(name)) {
                result.put(name, allSchemas.get(name));
            }
        }
        
        return result;
    }
    

    public List<HTTPRoute> parse() {
        List<HTTPRoute> routes = new ArrayList<>();

        if (openapi.getPaths() == null || openapi.getPaths().isEmpty()) {
            logger.warn("OpenAPI schema has no paths defined.");
            return routes;
        }

        // Extract component schemas
        Map<String, JsonNode> schemaDefinitions = new HashMap<>();
        if (openapi.getComponents() != null && openapi.getComponents().getSchemas() != null) {
            for (Entry<String, Schema> entry : openapi.getComponents().getSchemas().entrySet()) {
                String name = entry.getKey();
                Object schema = entry.getValue();
                try {
                    if (schema instanceof Reference refSchema) {
                        Object resolvedSchema = resolveRef(refSchema);
                        schemaDefinitions.put(name, extractSchemaAsMap(resolvedSchema));
                    } else {
                        schemaDefinitions.put(name, extractSchemaAsMap(schema));
                    }
                } catch (Exception e) {
                    logger.warn("Failed to extract schema definition '{}'", name, e);
                }
            }
        }
        
        // List of HTTP methods
        List<String> httpMethods = Arrays.stream(HttpMethod.values())
                .map(Enum::name)
                .map(String::toLowerCase)
                .toList(); 

        // Process paths and operations
        for (Entry<String, PathItem> pathEntry : openapi.getPaths().entrySet()) {
            String pathStr = pathEntry.getKey();
            PathItem pathItem = pathEntry.getValue();
            List<Parameter> pathLevelParams = pathItem.getParameters(); // may be null

            for (HttpMethod httpMethod : HttpMethod.values()) {
                Operation operation = getOperationByHttpMethod(pathItem, httpMethod);
                if (operation == null) continue;
                
                try {
                    // Extract parameters
                    List<ParameterInfo> parameters = extractParameters(operation.getParameters(), pathLevelParams);

                    // Extract request body
                    RequestBodyInfo requestBodyInfo = extractRequestBody(operation.getRequestBody());

                    // Extract responses
                    Map<String, ResponseInfo> responses = extractResponses(operation.getResponses());

                    // Extensions starting with "x-"
                   Map<String, Object> extensions = new HashMap<>();
                    if (operation.getExtensions() != null) {
                        for (Map.Entry<String, Object> extEntry : operation.getExtensions().entrySet()) {
                            if (extEntry.getKey().startsWith("x-")) {
                                extensions.put(extEntry.getKey(), extEntry.getValue());
                            }
                        }
                    }

                    // Extract schemas for input and output
                    Map<String, JsonNode> inputSchemas = extractInputSchemaDependencies(parameters, requestBodyInfo, schemaDefinitions);
                    Map<String, JsonNode> outputSchemas = extractOutputSchemaDependencies(responses, schemaDefinitions);

                    // Create initial route
                    HTTPRoute route = new HTTPRoute(
                            pathStr,
                            httpMethod,
                            operation.getOperationId(),
                            operation.getSummary(),
                            operation.getDescription(),
                            operation.getTags() != null ? operation.getTags() : new ArrayList<>(),
                            parameters,
                            requestBodyInfo,
                            responses,
                            inputSchemas,
                            outputSchemas,
                            extensions,
                            openapi.getSpecVersion().toString()
                    );

                    // Pre-calculate schema and parameter mapping
                    try {
                    	CombinedSchemaResult preCalc =
                                SchemaUtils.combineSchemasAndMapParams(route, true);
                        route.setFlatParamSchema(preCalc.getCombinedSchema());
                        route.setParameterMap(preCalc.getParameterMap());
                    } catch (Exception schemaError) {
                        logger.warn("Failed to pre-calculate input schema / params map for route {} {}",
                                httpMethod.name(), pathStr, schemaError);
                        route.setFlatParamSchema("{\"type\": \"object\", \"properties\": {}}");
                        route.setParameterMap(Map.of());
                    }

                    // TODO(post-release): pre-calculate the response schema to feed the tool output schema

                    routes.add(route);

                } catch (IllegalArgumentException opError) {
                    if (opError.getMessage().contains("External or non-local reference not supported")) {
                        throw opError;
                    }
                    logger.error("Failed to process operation {} {} (ID: {})", httpMethod.name(), pathStr,
                            operation.getOperationId() != null ? operation.getOperationId() : "unknown", opError);
                } catch (Exception opError) {
                    logger.error("Failed to process operation {} {} (ID: {})", httpMethod.name(), pathStr,
                            operation.getOperationId() != null ? operation.getOperationId() : "unknown", opError);
                }
                
            }
        }

        logger.info("Finished parsing. Extracted {} HTTP routes.", routes.size());
        return routes;
    }

    /**
     * Compact, log-friendly description of a possibly-unresolved OpenAPI object:
     * its reference URI when it is a {@link Reference}, otherwise its class name.
     */
    private static String describeForLog(Object openApiObject) {
        if (openApiObject == null) {
            return "null";
        }
        if (openApiObject instanceof Reference ref) {
            return "'" + ref.getUri() + "'";
        }
        return openApiObject.getClass().getSimpleName();
    }

    private Operation getOperationByHttpMethod(PathItem pathItem, HttpMethod method) {
		return switch (method) {
			case GET -> pathItem.getGet();
			case POST -> pathItem.getPost();
			case PUT -> pathItem.getPut();
			case DELETE -> pathItem.getDelete();
			case PATCH -> pathItem.getPatch();
			case OPTIONS -> pathItem.getOptions();
			case HEAD -> pathItem.getHead();
			case TRACE -> pathItem.getTrace();
			default -> null;
		};
	}

}

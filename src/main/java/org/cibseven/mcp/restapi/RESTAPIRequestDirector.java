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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import javax.security.sasl.AuthenticationException;

import com.fasterxml.jackson.databind.JsonNode;
import org.cibseven.mcp.auth.EngineRestAuthProvider;
import org.cibseven.mcp.restapi.models.HTTPRoute;
import org.cibseven.mcp.restapi.models.HttpMethod;
import org.cibseven.mcp.restapi.models.ParameterInfo;
import org.cibseven.mcp.restapi.models.ParameterLocation;
import org.cibseven.mcp.restapi.models.UnflattenedArgs;
import org.cibseven.mcp.restapi.schemas.SchemaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Translates an MCP tool invocation into an HTTP call against the configured REST API.
 *
 * <p>Takes the {@link HTTPRoute} produced by the OpenAPI parser together with the flat
 * argument map supplied by the MCP client, reverses the schema flattening performed by
 * {@link SchemaUtils#combineSchemasAndMapParams} (see {@link #unflattenArguments}), builds
 * the request URL and body, applies the configured {@link EngineRestAuthProvider}, and
 * executes the request with a shared {@link OkHttpClient}.</p>
 *
 * <p>Instances are created by the {@code restApiRequestDirector} bean factory in
 * {@link RESTAPIMcpToolsConfig}; the class is stateless apart from its immutable
 * configuration and is safe for concurrent use.</p>
 */
public class RESTAPIRequestDirector {

    // Shared empty body for methods that require a body (POST/PUT/PATCH) when the spec defines none.
    private static final RequestBody EMPTY_REQUEST_BODY = RequestBody.create(new byte[0], null);

    private static final Logger logger = LoggerFactory.getLogger(RESTAPIRequestDirector.class);

    private final OkHttpClient client;
    private final String baseUrl;
    private final EngineRestAuthProvider authProvider;

    public RESTAPIRequestDirector(String baseUrl, EngineRestAuthProvider authProvider) {
        this.baseUrl = (baseUrl == null || baseUrl.isEmpty()) ? "http://localhost" : baseUrl;
        this.client = new OkHttpClient.Builder()
                // prod hygiene: explicit timeouts instead of OkHttp's defaults, so a stalled
                // engine-rest call cannot pin an MCP request thread indefinitely
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .readTimeout(java.time.Duration.ofSeconds(30))
                .writeTimeout(java.time.Duration.ofSeconds(30))
                .build();
        this.authProvider = authProvider;
    }

    /**
     * Builds the final OkHttp {@link Request} for the given route and flat MCP arguments.
     * The authorization headers from the configured {@link EngineRestAuthProvider} are
     * applied last with replace semantics, so tool arguments cannot override them.
     */
    public Request build(
            HTTPRoute route,
            Map<String, Object> flatArgs,
            Authentication authentication) {

        logger.info("Building request for {} {} with arguments {}",
                route.getMethod(), route.getPath(), flatArgs.keySet());
        logger.debug("Argument values for {}: {}", route.getOperationId(), flatArgs);

        // Step 1: Un-flatten arguments into path, query, header and body parts
        UnflattenedArgs args = unflattenArguments(route, flatArgs);

        logger.debug("Unflattened - path: {}, query: {}, headers: {}, hasBody: {}",
                args.pathParams, args.queryParams, args.headerParams, args.body != null);

        // Step 2: Build base URL with path parameters
        String rawUrl = buildUrl(route.getPath(), args.pathParams, baseUrl);
        HttpUrl parsedUrl = HttpUrl.parse(rawUrl);
        if (parsedUrl == null) {
            throw new IllegalStateException("Cannot parse engine-rest request URL: " + rawUrl);
        }
        HttpUrl.Builder urlBuilder = parsedUrl.newBuilder();

        // Step 3: Append query parameters
        if (args.queryParams != null) {
            args.queryParams.forEach((key, value) -> {
                if (value != null) {
                    urlBuilder.addQueryParameter(key, value.toString());
                }
            });
        }
        HttpUrl url = urlBuilder.build();

        // Step 4: Build the request body
        RequestBody requestBody = buildRequestBody(route, args.body);

        Request.Builder builder = new Request.Builder().url(url);

        HttpMethod method = route.getMethod();
        // Step 5: Set method, following HTTP/OkHttp body semantics so this works for any OpenAPI spec:
        // - GET/HEAD/TRACE: a request body is not allowed.
        // - POST/PUT/PATCH: OkHttp requires a body, so send an empty one when the spec defines none.
        // - DELETE/OPTIONS: a body is optional and passed through when the spec defines one.
        boolean bodyForbidden = HttpMethod.GET.equals(method)
                || HttpMethod.HEAD.equals(method)
                || HttpMethod.TRACE.equals(method);
        boolean bodyRequired = HttpMethod.POST.equals(method)
                || HttpMethod.PUT.equals(method)
                || HttpMethod.PATCH.equals(method);

        if (bodyForbidden) {
            if (requestBody != null) {
                logger.warn("Discarding request body for {} {}: a body is not allowed for this method",
                        method.name(), route.getPath());
            }
            builder.method(method.name(), null);
        }
        else if (requestBody == null && bodyRequired) {
            builder.method(method.name(), EMPTY_REQUEST_BODY);
        }
        else {
            builder.method(method.name(), requestBody);
        }

        // Step 6: Add headers from tool arguments
        if (args.headerParams != null) {
            args.headerParams.forEach(builder::addHeader);
        }

        // Step 7: Apply the configured engine-rest auth strategy last; header() replaces
        // any same-named header, so argument-supplied Authorization values cannot win
        authProvider.authHeaders(authentication).forEach(builder::header);

        Request request = builder.build();
        logger.debug("Prepared engine-rest request: method={}, url={}, hasAuthorization={}",
                request.method(), request.url(), request.header("Authorization") != null);
        return request;
    }

    /**
     * Executes the request built for the given route and returns the response body.
     *
     * @throws AuthenticationException on HTTP 401
     * @throws IOException on any other non-2xx response or transport failure
     */
    public String sendSyncRequest(
            HTTPRoute route,
            Map<String, Object> flatArgs,
            Authentication authentication) throws IOException {
        Request request = build(route, flatArgs, authentication);
        String responseString = "";
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                ResponseBody body = response.body();
                String message = body != null ? body.string() : null;
                if (response.code() == 401) {
                    logger.warn("engine-rest returned 401 for {} {}. WWW-Authenticate={}",
                            request.method(), request.url(), response.header("WWW-Authenticate"));
                    throw new AuthenticationException(
                            message != null && !message.isEmpty() ? message : "Unauthorized");
                }
                throw new IOException(message != null && !message.isEmpty() ? message : "Request failed");
            }
            responseString = response.body() != null ? response.body().string() : null;
        }
        // IOExceptions are surfaced to the MCP client by the tool's call handler
        return responseString;
    }

    /** Serializes the un-flattened body object into an OkHttp {@link RequestBody}, or null. */
    private RequestBody buildRequestBody(HTTPRoute route, Object body) {
        if (body == null) {
            return null;
        }
        String contentType = null;
        if (route.getRequestBody() != null &&
            route.getRequestBody().getContentSchema() != null &&
            !route.getRequestBody().getContentSchema().isEmpty()) {
            contentType = route.getRequestBody().getContentSchema().keySet().iterator().next();
        }
        try {
            if ("multipart/form-data".equals(contentType)) {
                return buildMultipartBody((Map<?, ?>) body);
            }
            return RequestBody.create(
                    SchemaUtils.MAPPER.writeValueAsString(body),
                    MediaType.parse(contentType != null ? contentType : "application/json"));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize request body for "
                    + route.getOperationId(), e);
        }
    }

    /**
     * Builds a multipart/form-data body. The {@code data}/{@code content} entry becomes the
     * file part; an optional {@code filename} entry names it (see SKILL.md for the client
     * contract), otherwise the extension is guessed from the content.
     */
    private MultipartBody buildMultipartBody(Map<?, ?> multipartMap) {
        MultipartBody.Builder multipartBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        boolean addedData = false;
        for (Map.Entry<?, ?> entry : multipartMap.entrySet()) {
            String key = entry.getKey().toString();
            Object val = entry.getValue();
            if (val == null) {
                continue;
            }
            String strVal = val.toString().trim();
            if (key.equals("data") || key.equals("content")) {
                if (addedData) {
                    logger.warn("Skipping '{}': a data/content part was already added to this multipart request", key);
                    continue;
                }
                byte[] bytes = strVal.getBytes(StandardCharsets.UTF_8);
                Object providedFilename = multipartMap.get("filename");
                String filename;
                if (providedFilename != null && !providedFilename.toString().isBlank()) {
                    filename = providedFilename.toString();
                } else {
                    String ext = guessDeploymentExtension(strVal);
                    filename = key + ext;
                    if (ext.isEmpty()) {
                        logger.warn("No filename provided and content type could not be detected — uploading as '{}' with no extension", filename);
                    } else {
                        logger.warn("No filename provided. Extension detected from content, uploading as '{}'", filename);
                    }
                }
                multipartBuilder.addFormDataPart(key, filename,
                        RequestBody.create(bytes, MediaType.parse("application/octet-stream")));
                addedData = true;
            } else if (!"filename".equals(key)) {
                multipartBuilder.addFormDataPart(key, strVal);
            }
        }
        return multipartBuilder.build();
    }

    /**
     * Best-effort extension detection for deployment resources when the MCP client did not
     * supply a filename: sniffs the OMG namespace URIs that BPMN/DMN/CMMN documents declare.
     * Heuristic only — clients should always pass an explicit {@code filename}.
     */
    private static String guessDeploymentExtension(String content) {
        if (content.contains("omg.org/spec/BPMN")) {
            return ".bpmn";
        }
        if (content.contains("omg.org/spec/DMN")) {
            return ".dmn";
        }
        if (content.contains("omg.org/spec/CMMN")) {
            return ".cmmn";
        }
        return "";
    }

    /**
     * Reverses the schema flattening performed by
     * {@link SchemaUtils#combineSchemasAndMapParams}: routes each flat MCP argument back to
     * its OpenAPI location (path/query/header/body) using the route's parameter map, with a
     * best-effort fallback for routes without one.
     *
     * <p>Must stay in sync with the flattening logic in
     * {@code SchemaUtils.combineSchemasAndMapParams} (including the {@code __LOCATION}
     * collision suffix convention).</p>
     */
    UnflattenedArgs unflattenArguments(
            HTTPRoute route,
            Map<String, Object> flatArgs
    ) {
        Map<String, String> pathParams = new HashMap<>();
        Map<String, String> queryParams = new HashMap<>();
        Map<String, String> headerParams = new HashMap<>();
        Map<String, Object> bodyProps = new HashMap<>();

        // Preferred: use the parameter map produced during schema flattening
        if (route.getParameterMap() != null && !route.getParameterMap().isEmpty()) {

            for (Map.Entry<String, Object> entry : flatArgs.entrySet()) {
                String argName = entry.getKey();

                if (entry.getValue() == null) continue;

                if (!route.getParameterMap().containsKey(argName)) {
                    // multipart/form-data bodies are open-ended: clients may send extra form
                    // fields (notably the 'filename' argument documented in SKILL.md) that the
                    // OpenAPI schema does not declare — route them to the body instead of
                    // dropping them
                    if (isMultipartRoute(route)) {
                        bodyProps.put(argName, entry.getValue());
                    } else {
                        logger.warn("Argument '{}' not found in parameter map for {}",
                                argName, route.getOperationId());
                    }
                    continue;
                }

                Map<String, String> mapping = route.getParameterMap().get(argName);
                ParameterLocation location = ParameterLocation.valueOf(mapping.get("location"));
                String openapiName = mapping.get("openapi_name");

                switch (location) {
                    case PATH -> pathParams.put(openapiName, entry.getValue().toString());
                    case QUERY -> queryParams.put(openapiName, entry.getValue().toString());
                    case HEADER -> headerParams.put(openapiName, entry.getValue().toString());
                    case BODY -> bodyProps.put(openapiName, entry.getValue());
                    default -> logger.warn("Unknown parameter location '{}' for {}", location, argName);
                }
            }
        } else {
            // Fallback mapping for routes without a parameter map
            logger.debug("No parameter map available for {}, using fallback mapping",
                    route.getOperationId());

            Map<String, String> paramLocations = new HashMap<>();
            for (ParameterInfo param : route.getParameters()) {
                paramLocations.put(param.getName(), param.getLocation().name());
            }

            for (Map.Entry<String, Object> entry : flatArgs.entrySet()) {
                String argName = entry.getKey();

                if (entry.getValue() == null) continue;

                // Handle suffixed parameters (id__PATH)
                if (argName.contains("__")) {
                    String[] parts = argName.split("__");
                    if (parts.length == 2) {
                        String baseName = parts[0];
                        ParameterLocation location = ParameterLocation.valueOf(parts[1]);

                        switch (location) {
                            case PATH -> pathParams.put(baseName, entry.getValue().toString());
                            case QUERY -> queryParams.put(baseName, entry.getValue().toString());
                            case HEADER -> headerParams.put(baseName, entry.getValue().toString());
                        }
                    }
                }

                // Known parameter?
                if (paramLocations.containsKey(argName)) {
                    ParameterLocation location = ParameterLocation.valueOf(paramLocations.get(argName));

                    switch (location) {
                        case PATH -> pathParams.put(argName, entry.getValue().toString());
                        case QUERY -> queryParams.put(argName, entry.getValue().toString());
                        case HEADER -> headerParams.put(argName, entry.getValue().toString());
                        default -> bodyProps.put(argName, entry.getValue());
                    }
                } else {
                    // Assume body
                    bodyProps.put(argName, entry.getValue());
                }
            }
        }

        // ---- Body construction ----
        Object body = null;

        if (!bodyProps.isEmpty()) {

            if (route.getRequestBody() != null &&
                route.getRequestBody().getContentSchema() != null &&
                !route.getRequestBody().getContentSchema().isEmpty()) {

                String contentType = route.getRequestBody()
                                          .getContentSchema()
                                          .keySet()
                                          .iterator()
                                          .next();

                JsonNode bodySchema = route.getRequestBody().getContentSchema().get(contentType);

                if ("object".equals(bodySchema.path("type").asText(null))) {
                    body = bodyProps;
                } else if (bodyProps.size() == 1) {
                    body = bodyProps.values().iterator().next();
                } else {
                    body = bodyProps;
                }

            } else {
                body = bodyProps;
            }
        }

        return new UnflattenedArgs(pathParams, queryParams, headerParams, body);
    }

    private static boolean isMultipartRoute(HTTPRoute route) {
        return route.getRequestBody() != null &&
                route.getRequestBody().getContentSchema() != null &&
                route.getRequestBody().getContentSchema().containsKey("multipart/form-data");
    }

    /**
     * Substitutes path parameters into the OpenAPI path template (percent-encoding each
     * value as a single path segment) and joins the result onto the base URL.
     */
    String buildUrl(
            String pathTemplate,
            Map<String, String> pathParams,
            String baseUrl
    ) {
        String urlPath = pathTemplate;

        if (pathParams != null) {
            for (Map.Entry<String, String> entry : pathParams.entrySet()) {
                String placeholder = "{" + entry.getKey() + "}";
                if (urlPath.contains(placeholder)) {
                    urlPath = urlPath.replace(placeholder, encodePathSegment(entry.getValue()));
                }
            }
        }

        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }

        if (urlPath.startsWith("/")) {
            urlPath = urlPath.substring(1);
        }

        return baseUrl + urlPath;
    }

    /**
     * Percent-encodes a path-parameter value as one RFC 3986 path segment, so values
     * containing '/', spaces or '%' cannot break out of their segment or produce an
     * unparseable URL.
     */
    private static String encodePathSegment(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (byte b : String.valueOf(value).getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }
}

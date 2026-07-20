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

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.oas.models.OpenAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;

/**
 * Loads and parses the OpenAPI document from the configured location (HTTP(S) URL or
 * local file path), with references resolved. Loading happens eagerly at construction.
 */
public class OpenApiProvider {

	private static final Logger logger =
            LoggerFactory.getLogger(OpenApiProvider.class);
    private final OpenAPI openAPI;

    public OpenApiProvider(String swaggerLocation) throws IOException {
    	logger.info("Loading OpenAPI specification from: {}", swaggerLocation);
        this.openAPI = parseSwaggerFile(swaggerLocation);
    }

    public OpenAPI getOpenAPI() {
        return openAPI;
    }

    private OpenAPI parseSwaggerFile(String swaggerFile) throws IOException {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        return new OpenAPIParser().readLocation(swaggerFile, null, options).getOpenAPI();
    }
}



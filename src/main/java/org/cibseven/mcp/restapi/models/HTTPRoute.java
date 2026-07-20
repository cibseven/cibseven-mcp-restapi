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
package org.cibseven.mcp.restapi.models;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import org.cibseven.mcp.restapi.utilities.BaseModel;

@Data
@NoArgsConstructor
public class HTTPRoute extends BaseModel {
    private String path;
    private HttpMethod method;
    private String operationId;
    private String summary;
    private String description;

    private List<String> tags = new ArrayList<>();
    private List<ParameterInfo> parameters = new ArrayList<>();
    private RequestBodyInfo requestBody;
    // It's a hashMap, but it only contains the primary success response: code, ResponseInfo
    private Map<String, ResponseInfo> responses = new HashMap<>();

    // requestSchemas and responseSchemas were Map<String, JsonSchema>
    private Map<String, JsonNode> requestSchemas = new HashMap<>();
    private Map<String, JsonNode> responseSchemas = new HashMap<>();
    private Map<String, Object> extensions = new HashMap<>();
    private String openapiVersion;

    // Pre-calculated fields for performance
    private String flatParamSchema = "";
    private Map<String, Map<String, String>> parameterMap = new HashMap<>();
    private String flatResponseSchema = "";
    
    public HTTPRoute(
            String path,
            HttpMethod method,
            String operationId,
            String summary,
            String description,
            List<String> tags,
            List<ParameterInfo> parameters,
            RequestBodyInfo requestBody,
            Map<String, ResponseInfo> responses,
            Map<String, JsonNode> requestSchemas,
            Map<String, JsonNode> responseSchemas,
            Map<String, Object> extensions,
            String openapiVersion
    ) {
        this.path = path;
        this.method = method;
        this.operationId = operationId;
        this.summary = summary;
        this.description = description;
        this.tags = tags != null ? tags : new ArrayList<>();
        this.parameters = parameters != null ? parameters : new ArrayList<>();
        this.requestBody = requestBody;
        this.responses = responses != null ? responses : new HashMap<>();
        this.requestSchemas = requestSchemas != null ? requestSchemas : new HashMap<>();
        this.responseSchemas = responseSchemas != null ? responseSchemas : new HashMap<>();
        this.extensions = extensions != null ? extensions : new HashMap<>();
        this.openapiVersion = openapiVersion;
    }
}

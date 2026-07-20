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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.security.sasl.AuthenticationException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.cibseven.mcp.auth.EngineRestAuthProvider;
import org.cibseven.mcp.restapi.models.HTTPRoute;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RESTAPIRequestDirectorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static List<HTTPRoute> routes;

    private MockWebServer engine;
    private RESTAPIRequestDirector director;

    @BeforeAll
    static void parseFixture() throws Exception {
        routes = FixtureSupport.parseFixtureRoutes();
    }

    @BeforeEach
    void setUp() throws IOException {
        engine = new MockWebServer();
        engine.start();
        EngineRestAuthProvider stubAuth = authentication -> Map.of("Authorization", "Bearer test-token");
        String baseUrl = engine.url("/engine-rest").toString();
        director = new RESTAPIRequestDirector(baseUrl, stubAuth);
    }

    @AfterEach
    void tearDown() throws IOException {
        engine.shutdown();
    }

    @Test
    void buildsUrlWithEncodedPathParamAndQuery() throws Exception {
        engine.enqueue(new MockResponse().setBody("ok"));

        String response = director.sendSyncRequest(
                FixtureSupport.route(routes, "getTask"),
                Map.of("id", "a/b c", "maxResults", "10"),
                null);

        assertThat(response).isEqualTo("ok");
        RecordedRequest recorded = engine.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("GET");
        assertThat(recorded.getPath()).startsWith("/engine-rest/task/a%2Fb%20c");
        assertThat(recorded.getPath()).contains("maxResults=10");
    }

    @Test
    void appliesAuthProviderHeaderWithReplaceSemantics() throws Exception {
        engine.enqueue(new MockResponse().setBody("ok"));

        director.sendSyncRequest(FixtureSupport.route(routes, "getTask"), Map.of("id", "1"), null);

        RecordedRequest recorded = engine.takeRequest();
        assertThat(recorded.getHeaders().values("Authorization"))
                .containsExactly("Bearer test-token");
    }

    @Test
    void serializesJsonBody() throws Exception {
        engine.enqueue(new MockResponse().setResponseCode(201).setBody("created"));

        director.sendSyncRequest(
                FixtureSupport.route(routes, "createTask"),
                Map.of("name", "my task", "assignee", "OlegSk"),
                null);

        RecordedRequest recorded = engine.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getHeader("Content-Type")).startsWith("application/json");
        JsonNode body = JSON.readTree(recorded.getBody().readUtf8());
        assertThat(body.path("name").asText()).isEqualTo("my task");
        assertThat(body.path("assignee").asText()).isEqualTo("OlegSk");
    }

    @Test
    void buildsMultipartBodyWithSniffedBpmnFilename() throws Exception {
        engine.enqueue(new MockResponse().setBody("deployed"));

        String bpmn = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"/>";
        director.sendSyncRequest(
                FixtureSupport.route(routes, "createDeployment"),
                Map.of("deployment-name", "demo", "data", bpmn),
                null);

        RecordedRequest recorded = engine.takeRequest();
        assertThat(recorded.getHeader("Content-Type")).startsWith("multipart/form-data");
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("filename=\"data.bpmn\"");
        assertThat(body).contains("name=\"deployment-name\"");
        assertThat(body).contains(bpmn);
    }

    @Test
    void multipartUsesProvidedFilenameOverSniffing() throws Exception {
        engine.enqueue(new MockResponse().setBody("deployed"));

        director.sendSyncRequest(
                FixtureSupport.route(routes, "createDeployment"),
                Map.of("data", "some plain content", "filename", "process.bpmn"),
                null);

        RecordedRequest recorded = engine.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("filename=\"process.bpmn\"");
        // the filename entry itself must not become a separate form part
        assertThat(body).doesNotContain("name=\"filename\"");
    }

    @Test
    void throwsAuthenticationExceptionOn401() {
        engine.enqueue(new MockResponse().setResponseCode(401)
                .setHeader("WWW-Authenticate", "Basic realm=\"default\"")
                .setBody("Unauthorized"));

        assertThatExceptionOfType(AuthenticationException.class)
                .isThrownBy(() -> director.sendSyncRequest(
                        FixtureSupport.route(routes, "getTask"), Map.of("id", "1"), null))
                .withMessageContaining("Unauthorized");
    }

    @Test
    void throwsIOExceptionWithBodyMessageOnServerError() {
        engine.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        assertThatIOException()
                .isThrownBy(() -> director.sendSyncRequest(
                        FixtureSupport.route(routes, "getTask"), Map.of("id", "1"), null))
                .withMessageContaining("boom");
    }
}

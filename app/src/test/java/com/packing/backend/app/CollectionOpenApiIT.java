package com.packing.backend.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.packing.backend.infra.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CollectionOpenApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void exposesCollectionFiltersAsIndividualQueryParameters() throws Exception {
        JsonNode document = openApi();

        assertQueryParameters(document,
                              "/api/v1/projects",
                              "page",
                              "size",
                              "search",
                              "status",
                              "permission",
                              "createdFrom",
                              "createdBefore",
                              "updatedFrom",
                              "updatedBefore",
                              "sort",
                              "direction");
        assertQueryParameters(document,
                              "/api/v1/projects/{projectId}/members",
                              "page",
                              "size",
                              "search",
                              "permission",
                              "addedFrom",
                              "addedBefore",
                              "sort",
                              "direction");
        assertQueryParameters(document,
                              "/api/v1/projects/{projectId}/files",
                              "page",
                              "size",
                              "search",
                              "format",
                              "createdFrom",
                              "createdBefore",
                              "sort",
                              "direction");
        assertQueryParameters(document,
                              "/api/v1/projects/{projectId}/packing-jobs",
                              "page",
                              "size",
                              "search",
                              "status",
                              "createdFrom",
                              "createdBefore",
                              "startedFrom",
                              "startedBefore",
                              "finishedFrom",
                              "finishedBefore",
                              "sort",
                              "direction");
    }

    @Test
    void documentsCollectionParameterDefaultsDescriptionsAndSortChoices() throws Exception {
        JsonNode document = openApi();

        assertParameterDocumentation(document,
                                     "/api/v1/projects",
                                     "createdAt",
                                     "name",
                                     "status",
                                     "permission",
                                     "memberCount",
                                     "createdAt",
                                     "updatedAt");
        assertArrayParameterValues(document,
                                   "/api/v1/projects",
                                   "status",
                                   "ACTIVE",
                                   "DISABLED");
        assertParameterDocumentation(document,
                                     "/api/v1/projects/{projectId}/members",
                                     "addedAt",
                                     "username",
                                     "displayName",
                                     "permission",
                                     "addedAt");
        assertParameterDocumentation(document,
                                     "/api/v1/projects/{projectId}/files",
                                     "createdAt",
                                     "filename",
                                     "format",
                                     "sizeBytes",
                                     "createdAt");
        assertParameterDocumentation(document,
                                     "/api/v1/projects/{projectId}/packing-jobs",
                                     "createdAt",
                                     "status",
                                     "maxRuntimeSeconds",
                                     "engineVersion",
                                     "createdAt",
                                     "startedAt",
                                     "finishedAt",
                                     "resultFileName",
                                     "resultSizeBytes");
    }

    private JsonNode openApi() throws Exception {
        String response = mockMvc.perform(get("/v3/api-docs"))
                                 .andExpect(status().isOk())
                                 .andReturn()
                                 .getResponse()
                                 .getContentAsString();
        return objectMapper.readTree(response);
    }

    private static void assertQueryParameters(JsonNode document, String path, String... expected) {
        Set<String> actual = new LinkedHashSet<>();
        for (JsonNode parameter : document.path("paths")
                                          .path(path)
                                          .path("get")
                                          .path("parameters")) {
            if ("query".equals(parameter.path("in")
                                        .asText())) {
                actual.add(parameter.path("name")
                                    .asText());
            }
        }

        assertThat(actual).containsExactlyInAnyOrder(expected)
                          .doesNotContain("request");
    }

    private static void assertParameterDocumentation(JsonNode document,
                                                     String path,
                                                     String defaultSort,
                                                     String... expectedSorts) {
        JsonNode parameters = document.path("paths")
                                      .path(path)
                                      .path("get")
                                      .path("parameters");
        for (JsonNode parameter : parameters) {
            if ("query".equals(parameter.path("in")
                                        .asText())) {
                assertThat(parameter.path("description")
                                    .asText()).as("%s parameter on %s",
                                                  parameter.path("name")
                                                           .asText(),
                                                  path)
                                              .isNotBlank();
            }
        }

        assertThat(queryParameter(parameters, "page").path("schema")
                                                     .path("default")
                                                     .asText()).isEqualTo("0");
        assertThat(queryParameter(parameters, "size").path("schema")
                                                     .path("default")
                                                     .asText()).isEqualTo("20");

        JsonNode sortSchema = queryParameter(parameters, "sort").path("schema");
        assertThat(sortSchema.path("default")
                             .asText()).isEqualTo(defaultSort);
        Set<String> actualSorts = new LinkedHashSet<>();
        sortSchema.path("enum")
                  .forEach(value -> actualSorts.add(value.asText()));
        assertThat(actualSorts).containsExactlyInAnyOrder(expectedSorts);
    }

    private static JsonNode queryParameter(JsonNode parameters, String name) {
        for (JsonNode parameter : parameters) {
            if (name.equals(parameter.path("name")
                                     .asText())
                    && "query".equals(parameter.path("in")
                                               .asText())) {
                return parameter;
            }
        }
        throw new AssertionError("Missing query parameter: " + name);
    }

    private static void assertArrayParameterValues(JsonNode document,
                                                   String path,
                                                   String parameterName,
                                                   String... expectedValues) {
        JsonNode parameters = document.path("paths")
                                      .path(path)
                                      .path("get")
                                      .path("parameters");
        Set<String> actualValues = new LinkedHashSet<>();
        queryParameter(parameters, parameterName).path("schema")
                                                 .path("items")
                                                 .path("enum")
                                                 .forEach(value -> actualValues.add(value.asText()));

        assertThat(actualValues).containsExactlyInAnyOrder(expectedValues);
    }
}

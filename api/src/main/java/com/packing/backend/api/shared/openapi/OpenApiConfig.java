package com.packing.backend.api.shared.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.packing.backend.api.shared.security.CurrentUser;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String PROBLEM_SCHEMA = "ProblemDetail";

    private static final String BEARER_SCHEME      = "bearerAuth";
    private static final String PROBLEM_MEDIA_TYPE = "application/problem+json";
    private static final String PROBLEM_REF        = "#/components/schemas/" + PROBLEM_SCHEMA;

    static {
        SpringDocUtils.getConfig()
                      .addAnnotationsToIgnore(CurrentUser.class)
                      .replaceWithSchema(JsonNode.class, new ObjectSchema().additionalProperties(true));
    }

    @Bean
    public OpenAPI packingOpenApi() {
        return new OpenAPI()
                            .info(new Info()
                                            .title("3D Packing API")
                                            .version("v1")
                                            .description("Projects, 3D model files and packing jobs."))
                            .components(new Components()
                                                        .addSecuritySchemes(BEARER_SCHEME, bearerScheme())
                                                        .addSchemas(PROBLEM_SCHEMA, problemDetailSchema()))
                            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    @Bean
    public OperationCustomizer errorResponses() {
        return (operation, handlerMethod) -> {
            ApiResponses responses = operation.getResponses();
            addProblem(responses, "400", "Malformed request or failed field validation");
            addProblem(responses, "401", "Missing or invalid bearer token");
            addProblem(responses, "403", "Caller lacks the required permission");
            addProblem(responses, "422", "Rejected by a domain rule");
            addProblem(responses, "500", "Unexpected server error");
            if (hasPathParameter(operation)) {
                addProblem(responses, "404", "Referenced resource does not exist");
            }
            if (!handlerMethod.hasMethodAnnotation(GetMapping.class)) {
                addProblem(responses, "409", "Conflicts with the current state of the resource");
            }
            return operation;
        };
    }

    private SecurityScheme bearerScheme() {
        return new SecurityScheme()
                                   .type(SecurityScheme.Type.HTTP)
                                   .scheme("bearer")
                                   .bearerFormat("JWT")
                                   .description("Firebase ID token.");
    }

    private Schema<?> problemDetailSchema() {
        return new ObjectSchema()
                                 .description("Error body.")
                                 .addProperty("type", new StringSchema().format("uri"))
                                 .addProperty("title", new StringSchema())
                                 .addProperty("status", new IntegerSchema().format("int32"))
                                 .addProperty("detail", new StringSchema())
                                 .addProperty("instance", new StringSchema().format("uri"))
                                 .addProperty("path", new StringSchema())
                                 .addProperty("traceId",
                                              new StringSchema()
                                                                .description("Correlates the response with the server log entry."))
                                 .addProperty("errors",
                                              new MapSchema()
                                                             .additionalProperties(new StringSchema())
                                                             .description("Field name to message, on validation failures."));
    }

    private void addProblem(ApiResponses responses, String status, String description) {
        if (responses.containsKey(status)) {
            return;
        }
        responses.addApiResponse(status,
                                 new ApiResponse()
                                                  .description(description)
                                                  .content(new Content().addMediaType(
                                                                                      PROBLEM_MEDIA_TYPE,
                                                                                      new MediaType().schema(new Schema<>().$ref(PROBLEM_REF)))));
    }

    private boolean hasPathParameter(Operation operation) {
        List<Parameter> parameters = operation.getParameters();
        return parameters != null && parameters.stream()
                                               .anyMatch(parameter -> "path".equals(parameter.getIn()));
    }
}

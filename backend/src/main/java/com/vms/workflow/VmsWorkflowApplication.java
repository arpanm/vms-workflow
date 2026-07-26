package com.vms.workflow;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableScheduling
public class VmsWorkflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(VmsWorkflowApplication.class, args);
    }

    @Bean
    OpenAPI vmsOpenApi() {
        String bearer = "bearerAuth";
        return new OpenAPI()
            .components(new Components().addSecuritySchemes(bearer,
                new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList(bearer))
            .info(new Info()
                .title("VMS Workflow API")
                .version("v1")
                .description("Tenant-secured organization, engagement, project, month, and legacy read APIs."));
    }

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    GlobalOpenApiCustomizer correlationContract() {
        return api -> {
            if (api.getPaths() == null) {
                return;
            }
            api.getPaths().values().forEach(path -> path.readOperations()
                .forEach(operation -> {
                    operation.addParametersItem(new Parameter()
                        .name("X-Correlation-Id")
                        .in("header")
                        .required(false)
                        .description(
                            "Optional UUID request correlation identifier; invalid values are replaced.")
                        .schema(new StringSchema().format("uuid")));
                    if (operation.getResponses() != null) {
                        operation.getResponses().values().forEach(response ->
                            response.addHeaderObject(
                                "X-Correlation-Id",
                                new Header()
                                    .description(
                                        "Server-normalized request correlation UUID.")
                                    .schema(new StringSchema().format("uuid"))));
                    }
                }));
        };
    }
}

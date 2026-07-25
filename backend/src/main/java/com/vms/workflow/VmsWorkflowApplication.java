package com.vms.workflow;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
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
}

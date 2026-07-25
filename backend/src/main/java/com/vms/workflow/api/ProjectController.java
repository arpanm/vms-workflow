package com.vms.workflow.api;

import com.vms.workflow.api.ApiDtos.ProjectView;
import com.vms.workflow.application.CatalogQueryService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final CatalogQueryService queries;

    public ProjectController(CatalogQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    @Operation(summary = "List projects inherited from an authorized engagement")
    List<ProjectView> list(@AuthenticationPrincipal Jwt jwt,
                           @RequestParam UUID engagementId) {
        return queries.projects(jwt.getSubject(), engagementId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a project inherited from an authorized engagement")
    ProjectView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return queries.project(jwt.getSubject(), id);
    }
}

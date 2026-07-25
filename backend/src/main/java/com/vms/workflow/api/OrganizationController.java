package com.vms.workflow.api;

import com.vms.workflow.api.ApiDtos.OrganizationView;
import com.vms.workflow.application.CatalogQueryService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {
    private final CatalogQueryService queries;

    public OrganizationController(CatalogQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    @Operation(summary = "List organizations in the authenticated identity's active memberships")
    List<OrganizationView> list(@AuthenticationPrincipal Jwt jwt) {
        return queries.organizations(jwt.getSubject());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a membership-scoped organization")
    OrganizationView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return queries.organization(jwt.getSubject(), id);
    }
}

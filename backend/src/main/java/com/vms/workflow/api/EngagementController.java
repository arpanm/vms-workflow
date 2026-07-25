package com.vms.workflow.api;

import com.vms.workflow.api.ApiDtos.EngagementView;
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
@RequestMapping("/api/v1/engagements")
public class EngagementController {
    private final CatalogQueryService queries;

    public EngagementController(CatalogQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    @Operation(summary = "List engagements in an authorized participating organization")
    List<EngagementView> list(@AuthenticationPrincipal Jwt jwt,
                              @RequestParam UUID organizationId) {
        return queries.engagements(jwt.getSubject(), organizationId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an engagement visible to a participating organization membership")
    EngagementView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return queries.engagement(jwt.getSubject(), id);
    }
}

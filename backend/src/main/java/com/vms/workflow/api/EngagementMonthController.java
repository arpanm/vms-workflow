package com.vms.workflow.api;

import com.vms.workflow.api.ApiDtos.EngagementMonthView;
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
@RequestMapping("/api/v1/engagement-months")
public class EngagementMonthController {
    private final CatalogQueryService queries;

    public EngagementMonthController(CatalogQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    @Operation(summary = "List months inherited from an authorized engagement")
    List<EngagementMonthView> list(@AuthenticationPrincipal Jwt jwt,
                                   @RequestParam UUID engagementId) {
        return queries.months(jwt.getSubject(), engagementId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a month inherited from an authorized engagement")
    EngagementMonthView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return queries.month(jwt.getSubject(), id);
    }
}

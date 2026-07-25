package com.vms.workflow.api;

import com.vms.workflow.api.ApiDtos.MeView;
import com.vms.workflow.application.CatalogQueryService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {
    private final CatalogQueryService queries;

    public MeController(CatalogQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    @Operation(summary = "Resolve the authenticated identity and active memberships")
    MeView me(@AuthenticationPrincipal Jwt jwt) {
        return queries.me(jwt.getSubject());
    }
}

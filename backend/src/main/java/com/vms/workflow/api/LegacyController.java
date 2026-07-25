package com.vms.workflow.api;

import com.vms.workflow.application.LegacyQueryService;
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
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/legacy")
public class LegacyController {
    private final LegacyQueryService legacy;

    public LegacyController(LegacyQueryService legacy) {
        this.legacy = legacy;
    }

    @GetMapping("/{collection:engagements|requirements|approvals|uat-items|invoices}")
    @Operation(summary = "Read a tenant-scoped immutable legacy import collection")
    List<JsonNode> list(@AuthenticationPrincipal Jwt jwt,
                        @PathVariable String collection,
                        @RequestParam(required = false) UUID organizationId) {
        return legacy.find(jwt.getSubject(), organizationId, collection);
    }
}

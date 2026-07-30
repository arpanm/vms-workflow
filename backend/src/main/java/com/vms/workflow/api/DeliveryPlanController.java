package com.vms.workflow.api;

import com.vms.workflow.api.DeliveryDtos.ApprovalRequest;
import com.vms.workflow.api.DeliveryDtos.CreatePlanRequest;
import com.vms.workflow.api.DeliveryDtos.PlanSummaryView;
import com.vms.workflow.api.DeliveryDtos.PlanView;
import com.vms.workflow.api.DeliveryDtos.RevisionComparisonView;
import com.vms.workflow.api.DeliveryDtos.RevisionRequest;
import com.vms.workflow.application.DeliveryPlanningService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/delivery")
public class DeliveryPlanController {
    private final DeliveryPlanningService delivery;

    public DeliveryPlanController(DeliveryPlanningService delivery) {
        this.delivery = delivery;
    }

    @GetMapping("/plans")
    @Operation(summary = "List the current delivery plan for an authorized engagement month")
    List<PlanSummaryView> plans(@AuthenticationPrincipal Jwt jwt,
                                @RequestParam UUID engagementMonthId) {
        return delivery.plans(jwt.getSubject(), engagementMonthId);
    }

    @GetMapping("/plans/{planId}")
    @Operation(summary = "Read a nested delivery plan with local Linear evidence")
    PlanView plan(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID planId) {
        return delivery.plan(jwt.getSubject(), planId);
    }

    @GetMapping("/plans/{planId}/revision-comparison")
    @Operation(summary = "Compare the current delivery revision to its immutable predecessor")
    RevisionComparisonView revisionComparison(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID planId
    ) {
        return delivery.revisionComparison(jwt.getSubject(), planId);
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create one complete nested draft plan for an engagement month")
    PlanView create(@AuthenticationPrincipal Jwt jwt,
                    @Valid @RequestBody CreatePlanRequest request) {
        return delivery.create(jwt.getSubject(), request);
    }

    @PutMapping("/plans/{planId}")
    @Operation(summary = "Replace the exact current draft or draft revision with a complete plan")
    PlanView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID planId,
                    @RequestHeader("If-Match") int expectedVersion,
                    @Valid @RequestBody CreatePlanRequest request) {
        return delivery.update(jwt.getSubject(), planId, expectedVersion, request);
    }

    @PostMapping("/plans/{planId}/submit")
    @Operation(summary = "Validate completeness, checksum and submit a draft for approval")
    PlanView submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID planId) {
        return delivery.submit(jwt.getSubject(), planId);
    }

    @PostMapping("/plans/{planId}/approvals")
    @Operation(summary = "Record an eligible checksum-signed vote and freeze on quorum")
    PlanView approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID planId,
                     @Valid @RequestBody ApprovalRequest request) {
        return delivery.approve(jwt.getSubject(), planId, request);
    }

    @PostMapping("/plans/{planId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Clone a frozen plan into a reasoned draft revision")
    PlanView revise(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID planId,
                    @Valid @RequestBody RevisionRequest request) {
        return delivery.revise(jwt.getSubject(), planId, request);
    }
}

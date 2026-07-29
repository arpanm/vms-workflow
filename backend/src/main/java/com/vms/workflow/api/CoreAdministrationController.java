package com.vms.workflow.api;

import com.vms.workflow.api.CoreAdministrationDtos.AddContactMemberInput;
import com.vms.workflow.api.CoreAdministrationDtos.ApprovalPolicyView;
import com.vms.workflow.api.CoreAdministrationDtos.ApprovalActionInput;
import com.vms.workflow.api.CoreAdministrationDtos.ApprovalRequestView;
import com.vms.workflow.api.CoreAdministrationDtos.ConfigurationView;
import com.vms.workflow.api.CoreAdministrationDtos.ContactGroupView;
import com.vms.workflow.api.CoreAdministrationDtos.CreateApprovalPolicyInput;
import com.vms.workflow.api.CoreAdministrationDtos.CreateApprovalRequestInput;
import com.vms.workflow.api.CoreAdministrationDtos.CreateContactGroupInput;
import com.vms.workflow.api.CoreAdministrationDtos.CreateDelegationInput;
import com.vms.workflow.api.CoreAdministrationDtos.DelegationView;
import com.vms.workflow.api.CoreAdministrationDtos.EligibleUserView;
import com.vms.workflow.api.CoreAdministrationDtos.EngagementAdministrationView;
import com.vms.workflow.api.CoreAdministrationDtos.MonthTransitionInput;
import com.vms.workflow.api.CoreAdministrationDtos.MonthTransitionView;
import com.vms.workflow.api.CoreAdministrationDtos.PublishApprovalPolicyInput;
import com.vms.workflow.api.CoreAdministrationDtos.PublishConfigurationInput;
import com.vms.workflow.api.CoreAdministrationDtos.RevokeDelegationInput;
import com.vms.workflow.api.CoreAdministrationDtos.ReviseApprovalPolicyInput;
import com.vms.workflow.api.CoreAdministrationDtos.UpdateEngagementInput;
import com.vms.workflow.application.CoreAdministrationService;
import com.vms.workflow.infrastructure.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/core")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid input"),
    @ApiResponse(responseCode = "401", description = "Unauthenticated"),
    @ApiResponse(responseCode = "404", description = "Unavailable or unauthorized scope"),
    @ApiResponse(responseCode = "409", description = "Conflict or stale version")
})
public class CoreAdministrationController {
    private final CoreAdministrationService service;

    public CoreAdministrationController(CoreAdministrationService service) {
        this.service = service;
    }

    @GetMapping("/engagements/{engagementId}")
    @Operation(summary = "Read an authorized engagement administration record")
    EngagementAdministrationView engagement(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID engagementId
    ) {
        return service.engagement(jwt.getSubject(), engagementId);
    }

    @PatchMapping("/engagements/{engagementId}")
    @Operation(summary = "Update engagement master data with optimistic concurrency")
    EngagementAdministrationView updateEngagement(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID engagementId,
        @Valid @RequestBody UpdateEngagementInput input,
        HttpServletRequest request
    ) {
        return service.updateEngagement(
            jwt.getSubject(), engagementId, input,
            CorrelationIdFilter.from(request));
    }

    @GetMapping("/engagements/{engagementId}/configurations")
    @Operation(summary = "List immutable engagement configuration versions")
    List<ConfigurationView> configurations(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID engagementId
    ) {
        return service.configurations(jwt.getSubject(), engagementId);
    }

    @GetMapping("/engagements/{engagementId}/configurations/effective")
    @Operation(summary = "Resolve configuration effective for a represented date")
    ConfigurationView effectiveConfiguration(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID engagementId,
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate effectiveOn
    ) {
        return service.effectiveConfiguration(
            jwt.getSubject(), engagementId, effectiveOn);
    }

    @PostMapping("/engagements/{engagementId}/configurations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Publish an immutable effective-dated configuration")
    ConfigurationView publishConfiguration(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID engagementId,
        @Valid @RequestBody PublishConfigurationInput input,
        HttpServletRequest request
    ) {
        return service.publishConfiguration(
            jwt.getSubject(), engagementId, input,
            CorrelationIdFilter.from(request));
    }

    @GetMapping("/engagements/{engagementId}/contact-groups")
    @Operation(summary = "List versioned contact groups and effective members")
    List<ContactGroupView> contactGroups(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID engagementId
    ) {
        return service.contactGroups(jwt.getSubject(), engagementId);
    }

    @PostMapping("/engagements/{engagementId}/contact-groups")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an engagement contact group")
    ContactGroupView createContactGroup(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID engagementId,
        @Valid @RequestBody CreateContactGroupInput input,
        HttpServletRequest request
    ) {
        return service.createContactGroup(
            jwt.getSubject(), engagementId, input,
            CorrelationIdFilter.from(request));
    }

    @PostMapping("/contact-groups/{groupId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add an effective contact with optimistic concurrency")
    ContactGroupView addContactMember(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID groupId,
        @Valid @RequestBody AddContactMemberInput input,
        HttpServletRequest request
    ) {
        return service.addContactMember(
            jwt.getSubject(), groupId, input,
            CorrelationIdFilter.from(request));
    }

    @GetMapping("/engagements/{engagementId}/eligible-users")
    @Operation(summary = "List minimal active users eligible within a participating organization")
    List<EligibleUserView> eligibleUsers(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID engagementId,
        @RequestParam UUID organizationId
    ) {
        return service.eligibleUsers(
            jwt.getSubject(), engagementId, organizationId);
    }

    @GetMapping("/engagements/{engagementId}/approval-policies")
    @Operation(summary = "List approval policies with executable stage definitions")
    List<ApprovalPolicyView> approvalPolicies(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID engagementId
    ) {
        return service.approvalPolicies(jwt.getSubject(), engagementId);
    }

    @PostMapping("/engagements/{engagementId}/approval-policies")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a draft approval policy and its ordered stages")
    ApprovalPolicyView createApprovalPolicy(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID engagementId,
        @Valid @RequestBody CreateApprovalPolicyInput input,
        HttpServletRequest request
    ) {
        return service.createApprovalPolicy(
            jwt.getSubject(), engagementId, input,
            CorrelationIdFilter.from(request));
    }

    @PostMapping("/approval-policies/{policyId}/publish")
    @Operation(summary = "Validate quorum and publish an immutable approval policy version")
    ApprovalPolicyView publishApprovalPolicy(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID policyId,
        @Valid @RequestBody PublishApprovalPolicyInput input,
        HttpServletRequest request
    ) {
        return service.publishApprovalPolicy(
            jwt.getSubject(), policyId, input,
            CorrelationIdFilter.from(request));
    }

    @PostMapping("/approval-policies/{policyId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a draft revision under the same approval policy identity")
    ApprovalPolicyView reviseApprovalPolicy(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID policyId,
        @Valid @RequestBody ReviseApprovalPolicyInput input,
        HttpServletRequest request
    ) {
        return service.reviseApprovalPolicy(
            jwt.getSubject(), policyId, input,
            CorrelationIdFilter.from(request));
    }

    @GetMapping("/engagements/{engagementId}/approval-requests")
    @Operation(summary = "List governed approval requests in an engagement")
    List<ApprovalRequestView> approvalRequests(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID engagementId
    ) {
        return service.approvalRequests(jwt.getSubject(), engagementId);
    }

    @PostMapping("/engagements/{engagementId}/approval-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an idempotent request from a published approval policy")
    ApprovalRequestView createApprovalRequest(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID engagementId,
        @Valid @RequestBody CreateApprovalRequestInput input,
        HttpServletRequest request
    ) {
        return service.createApprovalRequest(
            jwt.getSubject(), engagementId, input,
            CorrelationIdFilter.from(request));
    }

    @GetMapping("/approval-requests/{requestId}")
    @Operation(summary = "Read a scoped approval request, stages and actions")
    ApprovalRequestView approvalRequest(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID requestId
    ) {
        return service.approvalRequest(jwt.getSubject(), requestId);
    }

    @PostMapping("/approval-requests/{requestId}/actions")
    @Operation(summary = "Record an eligible approval action and evaluate quorum")
    ApprovalRequestView actOnApprovalRequest(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID requestId,
        @Valid @RequestBody ApprovalActionInput input,
        HttpServletRequest request
    ) {
        return service.actOnApprovalRequest(
            jwt.getSubject(), requestId, input,
            CorrelationIdFilter.from(request));
    }

    @GetMapping("/engagements/{engagementId}/delegations")
    @Operation(summary = "List effective-dated delegations")
    List<DelegationView> delegations(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID engagementId
    ) {
        return service.delegations(jwt.getSubject(), engagementId);
    }

    @PostMapping("/engagements/{engagementId}/delegations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a delegation bounded by the authority holder's scope")
    DelegationView createDelegation(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID engagementId,
        @Valid @RequestBody CreateDelegationInput input,
        HttpServletRequest request
    ) {
        return service.createDelegation(
            jwt.getSubject(), engagementId, input,
            CorrelationIdFilter.from(request));
    }

    @PostMapping("/delegations/{delegationId}/revoke")
    @Operation(summary = "Revoke an active delegation with optimistic concurrency")
    DelegationView revokeDelegation(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID delegationId,
        @Valid @RequestBody RevokeDelegationInput input,
        HttpServletRequest request
    ) {
        return service.revokeDelegation(
            jwt.getSubject(), delegationId, input,
            CorrelationIdFilter.from(request));
    }

    @GetMapping("/engagement-months/{monthId}/transitions")
    @Operation(summary = "Read immutable engagement-month transition history")
    List<MonthTransitionView> monthTransitions(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId
    ) {
        return service.monthTransitions(jwt.getSubject(), monthId);
    }

    @PostMapping("/engagement-months/{monthId}/transitions")
    @Operation(summary = "Perform a safe administrative month transition")
    MonthTransitionView transitionMonth(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId,
        @Valid @RequestBody MonthTransitionInput input,
        HttpServletRequest request
    ) {
        return service.transitionMonth(
            jwt.getSubject(), monthId, input,
            CorrelationIdFilter.from(request));
    }
}

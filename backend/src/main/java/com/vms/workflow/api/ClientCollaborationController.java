package com.vms.workflow.api;

import com.vms.workflow.api.CollaborationDtos.ApprovalInput;
import com.vms.workflow.api.CollaborationDtos.AssignmentInput;
import com.vms.workflow.api.CollaborationDtos.ClientUserInput;
import com.vms.workflow.api.CollaborationDtos.ClientUserView;
import com.vms.workflow.api.CollaborationDtos.ClientView;
import com.vms.workflow.api.CollaborationDtos.CommentInput;
import com.vms.workflow.api.CollaborationDtos.CreateWorkItemInput;
import com.vms.workflow.api.CollaborationDtos.DeliveryStatusInput;
import com.vms.workflow.api.CollaborationDtos.EffortInput;
import com.vms.workflow.api.CollaborationDtos.EstimateInput;
import com.vms.workflow.api.CollaborationDtos.OnboardClientInput;
import com.vms.workflow.api.CollaborationDtos.RoleGrantInput;
import com.vms.workflow.api.CollaborationDtos.UpdateWorkItemInput;
import com.vms.workflow.api.CollaborationDtos.WorkItemLinkInput;
import com.vms.workflow.api.CollaborationDtos.WorkItemView;
import com.vms.workflow.application.ClientCollaborationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/collaboration")
public class ClientCollaborationController {
    private final ClientCollaborationService service;

    public ClientCollaborationController(ClientCollaborationService service) {
        this.service = service;
    }

    @PostMapping("/clients")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Onboard a client, engagement, project and thirteen delivery months")
    ClientView onboardClient(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody OnboardClientInput input
    ) {
        return service.onboardClient(jwt.getSubject(), input);
    }

    @GetMapping("/clients/{clientOrganizationId}/users")
    @Operation(summary = "List client users, roles and effective permissions")
    List<ClientUserView> clientUsers(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID clientOrganizationId
    ) {
        return service.clientUsers(jwt.getSubject(), clientOrganizationId);
    }

    @PostMapping("/clients/{clientOrganizationId}/users")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add or reactivate a client user with scoped roles")
    ClientUserView addClientUser(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID clientOrganizationId,
        @Valid @RequestBody ClientUserInput input
    ) {
        return service.addClientUser(
            jwt.getSubject(), clientOrganizationId, input);
    }

    @PostMapping("/clients/{clientOrganizationId}/users/{userId}/role-grants")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Grant an organization, engagement or project role")
    ClientUserView grantRole(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID clientOrganizationId,
        @PathVariable UUID userId,
        @Valid @RequestBody RoleGrantInput input
    ) {
        return service.grantRole(
            jwt.getSubject(), clientOrganizationId, userId, input);
    }

    @GetMapping("/work-items")
    @Operation(summary = "List scoped backlog, current, future, past, assigned or mentioned tasks")
    List<WorkItemView> workItems(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam UUID engagementId,
        @RequestParam(defaultValue = "ALL")
        String bucket,
        @RequestParam(defaultValue = "false")
        boolean assignedToMe,
        @RequestParam(defaultValue = "false")
        boolean mentionedToMe
    ) {
        return service.workItems(
            jwt.getSubject(), engagementId, bucket,
            assignedToMe, mentionedToMe);
    }

    @PostMapping("/work-items")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a collaborative work item")
    WorkItemView createWorkItem(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody CreateWorkItemInput input
    ) {
        return service.createWorkItem(jwt.getSubject(), input);
    }

    @PostMapping("/work-items/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Atomically create up to five hundred client work items")
    List<WorkItemView> bulkCreate(
        @AuthenticationPrincipal Jwt jwt,
        @RequestBody @Size(min = 1, max = 500)
        List<@Valid CreateWorkItemInput> inputs
    ) {
        return service.bulkCreate(jwt.getSubject(), inputs);
    }

    @GetMapping("/work-items/{workItemId}")
    @Operation(summary = "Read a complete collaborative work-item workspace")
    WorkItemView workItem(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID workItemId
    ) {
        return service.workItem(jwt.getSubject(), workItemId);
    }

    @PatchMapping("/work-items/{workItemId}")
    @Operation(summary = "Update task definition using optimistic concurrency")
    WorkItemView updateWorkItem(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID workItemId,
        @Valid @RequestBody UpdateWorkItemInput input
    ) {
        return service.updateWorkItem(jwt.getSubject(), workItemId, input);
    }

    @PatchMapping("/work-items/{workItemId}/delivery-status")
    @Operation(summary = "Update delivery status as an assignee or scoped manager")
    WorkItemView updateDeliveryStatus(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID workItemId,
        @Valid @RequestBody DeliveryStatusInput input
    ) {
        return service.updateDeliveryStatus(jwt.getSubject(), workItemId, input);
    }

    @PostMapping("/work-items/{workItemId}/links")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Attach a typed HTTPS design, product, code or test link")
    WorkItemView addLink(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID workItemId,
        @Valid @RequestBody WorkItemLinkInput input
    ) {
        return service.addLink(jwt.getSubject(), workItemId, input);
    }

    @PostMapping("/work-items/{workItemId}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Assign, transfer or self-claim a task discipline")
    WorkItemView assign(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID workItemId,
        @Valid @RequestBody AssignmentInput input
    ) {
        return service.assign(jwt.getSubject(), workItemId, input);
    }

    @PostMapping("/work-items/{workItemId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Comment and create engagement-scoped user mentions")
    WorkItemView addComment(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID workItemId,
        @Valid @RequestBody CommentInput input
    ) {
        return service.addComment(jwt.getSubject(), workItemId, input);
    }

    @PostMapping("/work-items/{workItemId}/estimates")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Append a per-user estimate and recalculate the total")
    WorkItemView addEstimate(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID workItemId,
        @Valid @RequestBody EstimateInput input
    ) {
        return service.addEstimate(jwt.getSubject(), workItemId, input);
    }

    @DeleteMapping("/work-items/{workItemId}/estimates/{estimateId}")
    @Operation(summary = "Soft-delete an estimate as its owner or a scoped manager")
    WorkItemView deleteEstimate(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID workItemId,
        @PathVariable UUID estimateId
    ) {
        return service.deleteEstimate(
            jwt.getSubject(), workItemId, estimateId);
    }

    @PostMapping("/work-items/{workItemId}/efforts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Append per-user dated actual effort and recalculate the total")
    WorkItemView addEffort(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID workItemId,
        @Valid @RequestBody EffortInput input
    ) {
        return service.addEffort(jwt.getSubject(), workItemId, input);
    }

    @PostMapping("/work-items/{workItemId}/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record PLAN_L1, DELIVERY_L1 or DELIVERY_L2 decision")
    WorkItemView approve(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID workItemId,
        @Valid @RequestBody ApprovalInput input
    ) {
        return service.approve(jwt.getSubject(), workItemId, input);
    }
}

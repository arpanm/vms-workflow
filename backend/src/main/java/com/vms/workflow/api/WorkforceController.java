package com.vms.workflow.api;

import com.vms.workflow.api.WorkforceDtos.AllocationRequest;
import com.vms.workflow.api.WorkforceDtos.AllocationView;
import com.vms.workflow.api.WorkforceDtos.CreateEmployeeRequest;
import com.vms.workflow.api.WorkforceDtos.EmployeeLifecycleRequest;
import com.vms.workflow.api.WorkforceDtos.EmployeeMasterRequest;
import com.vms.workflow.api.WorkforceDtos.AllocationEditRequest;
import com.vms.workflow.api.WorkforceDtos.AllocationEndRequest;
import com.vms.workflow.api.WorkforceDtos.AllocationSplitRequest;
import com.vms.workflow.api.WorkforceDtos.EmployeeView;
import com.vms.workflow.api.WorkforceDtos.LeaveBalanceView;
import com.vms.workflow.api.WorkforceDtos.LeaveRequest;
import com.vms.workflow.api.WorkforceDtos.LeaveRequestView;
import com.vms.workflow.api.WorkforceDtos.PolicyAssignmentRequest;
import com.vms.workflow.api.WorkforceDtos.PolicyAssignmentView;
import com.vms.workflow.application.WorkforceService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

@RestController
@RequestMapping("/api/v1/workforce")
public class WorkforceController {
    private final WorkforceService workforce;

    public WorkforceController(WorkforceService workforce) {
        this.workforce = workforce;
    }

    @GetMapping("/employees")
    @Operation(summary = "List employees in an authorized organization")
    List<EmployeeView> employees(@AuthenticationPrincipal Jwt jwt,
                                 @RequestParam UUID organizationId) {
        return workforce.employees(jwt.getSubject(), organizationId);
    }

    @GetMapping("/employees/{id}")
    @Operation(summary = "Get an authorized employee master view")
    EmployeeView employee(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return workforce.employee(jwt.getSubject(), id);
    }

    @GetMapping("/employees/me")
    @Operation(summary = "Resolve the active employee linked to the attendance self-service identity")
    EmployeeView me(@AuthenticationPrincipal Jwt jwt) {
        return workforce.me(jwt.getSubject());
    }

    @PostMapping("/employees")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an effective-dated employee")
    EmployeeView createEmployee(@AuthenticationPrincipal Jwt jwt,
                                @Valid @RequestBody CreateEmployeeRequest request) {
        return workforce.createEmployee(jwt.getSubject(), request);
    }

    @PatchMapping("/employees/{id}/lifecycle")
    @Operation(summary = "Create a new effective employee lifecycle version")
    EmployeeView changeLifecycle(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                 @Valid @RequestBody EmployeeLifecycleRequest request) {
        return workforce.changeLifecycle(jwt.getSubject(), id, request);
    }

    @PatchMapping("/employees/{id}")
    @Operation(summary = "Create a new effective-dated employee master-field version")
    EmployeeView editEmployee(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                              @Valid @RequestBody EmployeeMasterRequest request) {
        return workforce.editEmployee(jwt.getSubject(), id, request);
    }

    @GetMapping("/employees/{id}/allocations")
    @Operation(summary = "List effective project allocations")
    List<AllocationView> allocations(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return workforce.allocations(jwt.getSubject(), id);
    }

    @PostMapping("/employees/{id}/allocations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a tenant-valid allocation constrained to 100 percent")
    AllocationView createAllocation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                    @Valid @RequestBody AllocationRequest request) {
        return workforce.createAllocation(jwt.getSubject(), id, request);
    }

    @PatchMapping("/employees/{id}/allocations/{allocationId}")
    @Operation(summary = "Edit an effective project allocation")
    AllocationView editAllocation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                  @PathVariable UUID allocationId,
                                  @Valid @RequestBody AllocationEditRequest request) {
        return workforce.editAllocation(jwt.getSubject(), id, allocationId, request);
    }

    @PostMapping("/employees/{id}/allocations/{allocationId}/end")
    @Operation(summary = "End an allocation while preserving its history")
    AllocationView endAllocation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                 @PathVariable UUID allocationId,
                                 @Valid @RequestBody AllocationEndRequest request) {
        return workforce.endAllocation(jwt.getSubject(), id, allocationId, request);
    }

    @PostMapping("/employees/{id}/allocations/{allocationId}/split")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Split an allocation into an ended source and a new bounded allocation")
    AllocationView splitAllocation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                   @PathVariable UUID allocationId,
                                   @Valid @RequestBody AllocationSplitRequest request) {
        return workforce.splitAllocation(jwt.getSubject(), id, allocationId, request);
    }

    @PostMapping("/employees/{id}/policy-assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary =
        "Assign a tenant-valid working calendar and opening leave balance")
    PolicyAssignmentView assignPolicy(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @Valid @RequestBody PolicyAssignmentRequest request
    ) {
        return workforce.assignPolicy(jwt.getSubject(), id, request);
    }

    @GetMapping("/employees/{id}/leave-balances")
    @Operation(summary = "Derive leave balances from the immutable ledger")
    List<LeaveBalanceView> leaveBalances(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return workforce.leaveBalances(jwt.getSubject(), id);
    }

    @GetMapping("/employees/{id}/leave-requests")
    @Operation(summary = "List authorized leave requests")
    List<LeaveRequestView> leaveRequests(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return workforce.leaveRequests(jwt.getSubject(), id);
    }

    @PostMapping("/employees/{id}/leave-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an idempotent leave request with explicit paid/LWP split")
    LeaveRequestView createLeaveRequest(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                        @Valid @RequestBody LeaveRequest request) {
        return workforce.createLeaveRequest(jwt.getSubject(), id, request);
    }
}

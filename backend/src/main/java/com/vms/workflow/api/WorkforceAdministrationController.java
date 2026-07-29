package com.vms.workflow.api;

import com.vms.workflow.api.AttendanceDtos.RegularizationView;
import com.vms.workflow.api.WorkforceAdministrationDtos.CalendarVersionView;
import com.vms.workflow.api.WorkforceAdministrationDtos.AssignShiftInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.DeliverableAllocationInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.DeliverableAllocationView;
import com.vms.workflow.api.WorkforceAdministrationDtos.EmployeeAliasInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.EmployeeAliasView;
import com.vms.workflow.api.WorkforceAdministrationDtos.LeaveBalanceCommandInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.LeaveBalanceCommandView;
import com.vms.workflow.api.WorkforceAdministrationDtos.LeaveDecisionInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.LeaveDecisionView;
import com.vms.workflow.api.WorkforceAdministrationDtos.LeavePolicyView;
import com.vms.workflow.api.WorkforceAdministrationDtos.FinalizeRosterInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.PublishCalendarInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.PublishLeavePolicyInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.PublishShiftPolicyInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.RosterReadinessView;
import com.vms.workflow.api.WorkforceAdministrationDtos.RosterSnapshotView;
import com.vms.workflow.api.WorkforceAdministrationDtos.ShiftAssignmentView;
import com.vms.workflow.api.WorkforceAdministrationDtos.ShiftPolicyView;
import com.vms.workflow.api.WorkforceAdministrationDtos.WorkforceCsvImportInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.WorkforceCsvImportView;
import com.vms.workflow.api.WorkforceDtos.LeaveRequestView;
import com.vms.workflow.application.WorkforceAdministrationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
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
public class WorkforceAdministrationController {
    private final WorkforceAdministrationService administration;

    public WorkforceAdministrationController(
        WorkforceAdministrationService administration
    ) {
        this.administration = administration;
    }

    @GetMapping("/employees/{employeeId}/aliases")
    @Operation(summary = "List effective employee aliases without payroll data")
    List<EmployeeAliasView> aliases(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID employeeId
    ) {
        return administration.aliases(jwt.getSubject(), employeeId);
    }

    @PostMapping("/employees/{employeeId}/aliases")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add immutable effective-dated employee alias evidence")
    EmployeeAliasView addAlias(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID employeeId,
        @Valid @RequestBody EmployeeAliasInput input
    ) {
        return administration.addAlias(jwt.getSubject(), employeeId, input);
    }

    @GetMapping("/employees/{employeeId}/deliverable-allocations")
    @Operation(summary = "List deliverable allocations bounded by project allocation")
    List<DeliverableAllocationView> deliverableAllocations(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID employeeId
    ) {
        return administration.deliverableAllocations(
            jwt.getSubject(), employeeId);
    }

    @PostMapping("/employees/{employeeId}/deliverable-allocations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add an effective deliverable allocation")
    DeliverableAllocationView addDeliverableAllocation(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID employeeId,
        @Valid @RequestBody DeliverableAllocationInput input
    ) {
        return administration.addDeliverableAllocation(
            jwt.getSubject(), employeeId, input);
    }

    @GetMapping("/organizations/{organizationId}/calendars")
    @Operation(summary = "List versioned organization working calendars")
    List<CalendarVersionView> calendars(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID organizationId
    ) {
        return administration.calendars(jwt.getSubject(), organizationId);
    }

    @PostMapping("/organizations/{organizationId}/calendars")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Publish an immutable working calendar version")
    CalendarVersionView publishCalendar(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID organizationId,
        @Valid @RequestBody PublishCalendarInput input
    ) {
        return administration.publishCalendar(
            jwt.getSubject(), organizationId, input);
    }

    @GetMapping("/organizations/{organizationId}/shift-policies")
    @Operation(summary = "List immutable effective-dated shift policy versions")
    List<ShiftPolicyView> shiftPolicies(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID organizationId
    ) {
        return administration.shiftPolicies(jwt.getSubject(), organizationId);
    }

    @PostMapping("/organizations/{organizationId}/shift-policies")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Publish a shift policy supporting split and overnight work")
    ShiftPolicyView publishShiftPolicy(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID organizationId,
        @Valid @RequestBody PublishShiftPolicyInput input
    ) {
        return administration.publishShiftPolicy(
            jwt.getSubject(), organizationId, input);
    }

    @GetMapping("/employees/{employeeId}/shift-assignments")
    @Operation(summary = "List effective employee shift assignments")
    List<ShiftAssignmentView> shiftAssignments(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID employeeId
    ) {
        return administration.shiftAssignments(jwt.getSubject(), employeeId);
    }

    @PostMapping("/employees/{employeeId}/shift-assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Assign a published same-organization shift policy")
    ShiftAssignmentView assignShift(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID employeeId,
        @Valid @RequestBody AssignShiftInput input
    ) {
        return administration.assignShift(jwt.getSubject(), employeeId, input);
    }

    @GetMapping("/engagement-months/{engagementMonthId}/roster-readiness")
    @Operation(summary = "Evaluate roster completeness for every allocated employee-day")
    RosterReadinessView rosterReadiness(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID engagementMonthId
    ) {
        return administration.rosterReadiness(
            jwt.getSubject(), engagementMonthId);
    }

    @GetMapping("/engagement-months/{engagementMonthId}/roster-snapshots")
    @Operation(summary = "List immutable finalized roster snapshot versions")
    List<RosterSnapshotView> rosterSnapshots(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID engagementMonthId
    ) {
        return administration.rosterSnapshots(
            jwt.getSubject(), engagementMonthId);
    }

    @PostMapping("/engagement-months/{engagementMonthId}/roster-snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Finalize a complete day-level allocation and shift roster")
    RosterSnapshotView finalizeRoster(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID engagementMonthId,
        @Valid @RequestBody FinalizeRosterInput input
    ) {
        return administration.finalizeRoster(
            jwt.getSubject(), engagementMonthId, input);
    }

    @GetMapping("/organizations/{organizationId}/leave-policies")
    @Operation(summary = "List effective versioned leave policies")
    List<LeavePolicyView> leavePolicies(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID organizationId
    ) {
        return administration.leavePolicies(jwt.getSubject(), organizationId);
    }

    @PostMapping("/organizations/{organizationId}/leave-policies")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Publish a governed leave policy version")
    LeavePolicyView publishLeavePolicy(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID organizationId,
        @Valid @RequestBody PublishLeavePolicyInput input
    ) {
        return administration.publishLeavePolicy(
            jwt.getSubject(), organizationId, input);
    }

    @PostMapping("/employees/{employeeId}/leave-balance-commands")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record idempotent leave accrual, grant, or adjustment")
    LeaveBalanceCommandView recordBalanceCommand(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID employeeId,
        @Valid @RequestBody LeaveBalanceCommandInput input
    ) {
        return administration.recordBalanceCommand(
            jwt.getSubject(), employeeId, input);
    }

    @PostMapping("/leave-requests/{requestId}/decisions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Approve, reject, or cancel an exact-version leave request")
    LeaveDecisionView decideLeave(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID requestId,
        @Valid @RequestBody LeaveDecisionInput input
    ) {
        return administration.decideLeave(
            jwt.getSubject(), requestId, input);
    }

    @GetMapping("/leave-request-inbox")
    @Operation(summary =
        "List organization leave requests for exact-version review")
    List<LeaveRequestView> leaveRequestInbox(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam UUID organizationId
    ) {
        return administration.leaveRequestInbox(
            jwt.getSubject(), organizationId);
    }

    @GetMapping("/regularization-inbox")
    @Operation(summary = "List organization regularizations for authorized review")
    List<RegularizationView> regularizationInbox(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam UUID organizationId
    ) {
        return administration.regularizationInbox(
            jwt.getSubject(), organizationId);
    }

    @PostMapping("/organizations/{organizationId}/imports")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary =
        "Validate or atomically apply a bounded workforce CSV import")
    WorkforceCsvImportView importCsv(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID organizationId,
        @Valid @RequestBody WorkforceCsvImportInput input
    ) {
        return administration.importCsv(
            jwt.getSubject(), organizationId, input);
    }
}

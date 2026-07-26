package com.vms.workflow.api;

import com.vms.workflow.api.AttendanceDtos.AttendanceDayView;
import com.vms.workflow.api.AttendanceDtos.AttendanceSnapshotView;
import com.vms.workflow.api.AttendanceDtos.CloseSnapshotRequest;
import com.vms.workflow.api.AttendanceDtos.PunchRequest;
import com.vms.workflow.api.AttendanceDtos.PunchView;
import com.vms.workflow.api.AttendanceDtos.RegularizationRequest;
import com.vms.workflow.api.AttendanceDtos.RegularizationView;
import com.vms.workflow.api.AttendanceDtos.ReopenSnapshotRequest;
import com.vms.workflow.application.AttendanceService;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceController {
    private final AttendanceService attendance;

    public AttendanceController(AttendanceService attendance) {
        this.attendance = attendance;
    }

    @PostMapping("/punches")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Capture an immutable idempotent internal attendance punch")
    PunchView punch(@AuthenticationPrincipal Jwt jwt,
                    @Valid @RequestBody PunchRequest request) {
        return attendance.punch(jwt.getSubject(), request);
    }

    @GetMapping("/days")
    @Operation(summary = "Calculate and list authorized attendance days")
    List<AttendanceDayView> days(@AuthenticationPrincipal Jwt jwt,
                                 @RequestParam UUID employeeId,
                                 @RequestParam LocalDate from,
                                 @RequestParam LocalDate to) {
        return attendance.days(jwt.getSubject(), employeeId, from, to);
    }

    @GetMapping("/regularizations")
    @Operation(summary = "List attendance regularization requests")
    List<RegularizationView> regularizations(@AuthenticationPrincipal Jwt jwt,
                                             @RequestParam UUID employeeId) {
        return attendance.regularizations(jwt.getSubject(), employeeId);
    }

    @PostMapping("/regularizations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an idempotent missing-punch or attendance regularization")
    RegularizationView createRegularization(@AuthenticationPrincipal Jwt jwt,
                                            @Valid @RequestBody RegularizationRequest request) {
        return attendance.createRegularization(jwt.getSubject(), request);
    }

    @GetMapping("/month-snapshots")
    @Operation(summary = "List immutable attendance snapshots for an authorized month")
    List<AttendanceSnapshotView> snapshots(@AuthenticationPrincipal Jwt jwt,
                                           @RequestParam UUID engagementMonthId) {
        return attendance.snapshots(jwt.getSubject(), engagementMonthId);
    }

    @PostMapping("/month-snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Close an attendance month into an immutable snapshot")
    AttendanceSnapshotView closeSnapshot(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody CloseSnapshotRequest request) {
        return attendance.closeSnapshot(jwt.getSubject(), request);
    }

    @PostMapping("/month-snapshots/{id}/reopen")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Reopen by creating a superseding immutable snapshot version")
    AttendanceSnapshotView reopenSnapshot(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                          @Valid @RequestBody ReopenSnapshotRequest request) {
        return attendance.reopenSnapshot(jwt.getSubject(), id, request);
    }
}

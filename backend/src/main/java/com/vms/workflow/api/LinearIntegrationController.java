package com.vms.workflow.api;

import com.vms.workflow.api.LinearDtos.IssueCurrentView;
import com.vms.workflow.api.LinearDtos.IssueLinkView;
import com.vms.workflow.api.LinearDtos.IssueSnapshotView;
import com.vms.workflow.api.LinearDtos.LinearHealthView;
import com.vms.workflow.api.LinearDtos.LinearReconciliationRequest;
import com.vms.workflow.api.LinearDtos.LinearReconciliationStatusView;
import com.vms.workflow.api.LinearDtos.LinearReconciliationView;
import com.vms.workflow.api.LinearDtos.LinkIssueRequest;
import com.vms.workflow.api.LinearDtos.WebhookAcceptedView;
import com.vms.workflow.api.LinearDtos.WebhookProcessView;
import com.vms.workflow.application.LinearIntegrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integrations/linear")
public class LinearIntegrationController {
    private final LinearIntegrationService linear;

    public LinearIntegrationController(LinearIntegrationService linear) {
        this.linear = linear;
    }

    @PostMapping("/links")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Link provider-neutral recorded Linear issue metadata to a draft deliverable")
    IssueLinkView link(@AuthenticationPrincipal Jwt jwt,
                       @Valid @RequestBody LinkIssueRequest request) {
        return linear.link(jwt.getSubject(), request);
    }

    @GetMapping("/links/{linkId}/current")
    @Operation(summary = "Read the normalized live issue projection")
    IssueCurrentView current(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID linkId) {
        return linear.current(jwt.getSubject(), linkId);
    }

    @GetMapping("/links/{linkId}/snapshots")
    @Operation(summary = "Read immutable plan/month/historical Linear snapshots")
    List<IssueSnapshotView> snapshots(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable UUID linkId) {
        return linear.snapshots(jwt.getSubject(), linkId);
    }

    @PostMapping("/months/{monthId}/snapshots")
    @Operation(
        summary = "Capture an idempotent month-end snapshot for every linked issue",
        description = "Captures the locally reconciled provider state or an explicit "
            + "unavailable result against the current frozen plan. It never rewrites "
            + "plan-time or earlier month-end evidence."
    )
    List<IssueSnapshotView> captureMonthEnd(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID monthId
    ) {
        return linear.captureMonthEnd(jwt.getSubject(), monthId);
    }

    @GetMapping("/health")
    @Operation(summary = "Read secret-redacted Linear connection and durable queue health")
    LinearHealthView health(@AuthenticationPrincipal Jwt jwt,
                            @RequestParam UUID engagementId) {
        return linear.health(jwt.getSubject(), engagementId);
    }

    @PostMapping("/connections/{connectionId}/reconciliations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Record an authorized terminal provider reconciliation result",
        description = "Appends a terminal reconciliation job and marks retained "
            + "issue truth stale on failure or freshly reconciled on success."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201",
            description = "Terminal result appended or exactly replayed"),
        @ApiResponse(responseCode = "400",
            description = "Missing or invalid header/body contract"),
        @ApiResponse(responseCode = "404",
            description = "Connection is absent or outside authorized scope"),
        @ApiResponse(responseCode = "409",
            description = "Conflicting replay or invalid connection state")
    })
    LinearReconciliationView reconcile(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID connectionId,
        @Parameter(
            description = "Caller-stable command key; exact replay only",
            required = true,
            schema = @Schema(maxLength = 160)
        )
        @Size(max = 160)
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody LinearReconciliationRequest request
    ) {
        return linear.reconcile(
            jwt.getSubject(), connectionId, idempotencyKey, request);
    }

    @GetMapping("/connections/{connectionId}/reconciliation-status")
    @Operation(
        summary = "Read the scoped cursor and retry evidence for scheduled reconciliation",
        description = "Returns checkpoint and latest terminal job metadata only; "
            + "provider credentials and raw responses are never exposed."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "Scoped checkpoint and latest attempt summary"),
        @ApiResponse(responseCode = "404",
            description = "Connection is absent or outside authorized scope")
    })
    LinearReconciliationStatusView reconciliationStatus(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID connectionId
    ) {
        return linear.reconciliationStatus(jwt.getSubject(), connectionId);
    }

    @PostMapping("/deliveries/{deliveryId}/process")
    @Operation(summary = "Process or idempotently replay one durable webhook delivery")
    WebhookProcessView process(@AuthenticationPrincipal Jwt jwt,
                               @PathVariable UUID deliveryId) {
        return linear.process(jwt.getSubject(), deliveryId);
    }

    @PostMapping(
        value = "/webhook/{connectionId}",
        consumes = "application/json",
        produces = "application/json"
    )
    @Operation(
        summary = "Verify and durably enqueue an exact-raw-body Linear webhook",
        description = "Public provider callback. Requires Linear-Signature, Linear-Timestamp "
            + "and Linear-Delivery headers. No credential or secret is returned."
    )
    WebhookAcceptedView webhook(
        @PathVariable UUID connectionId,
        @Parameter(hidden = true) @RequestHeader("Linear-Signature") String signature,
        @Parameter(hidden = true) @RequestHeader("Linear-Timestamp") String timestamp,
        @Parameter(hidden = true) @RequestHeader("Linear-Delivery") String delivery,
        @Parameter(hidden = true) @RequestBody byte[] rawBody
    ) {
        return linear.receiveWebhook(
            connectionId, signature, timestamp, delivery, rawBody);
    }
}

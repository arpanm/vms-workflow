package com.vms.workflow.api;

import com.vms.workflow.api.LinearDtos.IssueCurrentView;
import com.vms.workflow.api.LinearDtos.IssueLinkView;
import com.vms.workflow.api.LinearDtos.IssueSnapshotView;
import com.vms.workflow.api.LinearDtos.LinearHealthView;
import com.vms.workflow.api.LinearDtos.LinkIssueRequest;
import com.vms.workflow.api.LinearDtos.WebhookAcceptedView;
import com.vms.workflow.api.LinearDtos.WebhookProcessView;
import com.vms.workflow.application.LinearIntegrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
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

    @GetMapping("/health")
    @Operation(summary = "Read secret-redacted Linear connection and durable queue health")
    LinearHealthView health(@AuthenticationPrincipal Jwt jwt,
                            @RequestParam UUID engagementId) {
        return linear.health(jwt.getSubject(), engagementId);
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

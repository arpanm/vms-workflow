package com.vms.workflow.api;

import com.vms.workflow.api.DeliveryDtos.CommitmentDeadLetterView;
import com.vms.workflow.api.DeliveryDtos.CommitmentReplayRequest;
import com.vms.workflow.api.DeliveryDtos.CommitmentReplayView;
import com.vms.workflow.application.DeliveryCommitmentOperationsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@RequestMapping("/api/v1/delivery/commitment-operations")
public class DeliveryCommitmentOperationsController {
    private final DeliveryCommitmentOperationsService operations;

    public DeliveryCommitmentOperationsController(
        DeliveryCommitmentOperationsService operations
    ) {
        this.operations = operations;
    }

    @GetMapping
    @Operation(summary = "List a bounded, redacted set of dead-lettered commitment messages")
    List<CommitmentDeadLetterView> deadLetters(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam UUID engagementId,
        @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit
    ) {
        return operations.deadLetters(jwt.getSubject(), engagementId, limit);
    }

    @PostMapping("/{outboxId}/replays")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Append an authorized replay for one dead-lettered commitment",
        description = "Keeps the original terminal outbox row immutable and queues a new "
            + "provider-neutral message with identical frozen content."
    )
    CommitmentReplayView replay(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID outboxId,
        @Parameter(
            description = "Caller-stable command key; exact replay only",
            required = true,
            schema = @Schema(maxLength = 160)
        )
        @Size(max = 160) @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody CommitmentReplayRequest request
    ) {
        return operations.replay(jwt.getSubject(), outboxId, idempotencyKey, request);
    }
}

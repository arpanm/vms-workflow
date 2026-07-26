# F03 code review

**Review state:** do not release F03 as implemented. The locally implemented path has useful foundations (scoped reads, transactional plan creation, raw-byte HMAC comparison, durable delivery/queue rows, and a `Done`-is-projection-only rule), but it does not yet meet the F03 exit gate.

## Release blockers (P0)

### Frozen plans are still mutable after approval

Evidence: [V7__delivery_planning_linear.sql](../../../backend/src/main/resources/db/migration/V7__delivery_planning_linear.sql#L412) protects approvals, baselines, outbox, audit events, snapshots, events, and webhook deliveries, but no trigger protects `delivery_plan_versions`, `delivery_deliverable_versions`, criteria, dependencies, assignments, recipients, approvers, or links. More directly, webhook processing calls `recomputeDeliverableProjection`, which updates `delivery_deliverable_versions` without considering its plan state ([LinearIntegrationService.java](../../../backend/src/main/java/com/vms/workflow/application/LinearIntegrationService.java#L496)). A frozen version is also updated to `SUPERSEDED` during revision ([DeliveryPlanningService.java](../../../backend/src/main/java/com/vms/workflow/application/DeliveryPlanningService.java#L247)).

Impact: direct SQL, a future endpoint, or a webhook can change frozen-version data. The stored baseline checksum does not make those records immutable, so the promised preserved baseline cannot be independently reconstructed.

Fix: model live execution projection separately from frozen deliverable content, or explicitly permit only that column with an audited trigger; add database guards on all version-owned records once `PENDING_APPROVAL`/`FROZEN`/`SUPERSEDED`, and disallow arbitrary state rewrites. Freeze must record a complete immutable content snapshot/checksum, including approvers, recipients, dependencies and links. Revision should preserve the old frozen state and mark lineage on the new version rather than mutate the old evidence state.

### Approval separation of duties and quorum authority are incomplete

Evidence: creation persists caller-supplied approver subjects with a static JSON marker ([DeliveryPlanningService.java](../../../backend/src/main/java/com/vms/workflow/application/DeliveryPlanningService.java#L523)); submit checks only active profile status ([DeliveryPlanningService.java](../../../backend/src/main/java/com/vms/workflow/application/DeliveryPlanningService.java#L460)); approval rejects only `created_by_subject` ([DeliveryPlanningService.java](../../../backend/src/main/java/com/vms/workflow/application/DeliveryPlanningService.java#L192)). It does not reject coordinator/vendor-owner/product-owner self-approval or verify that configured approvers were eligible in the engagement when the plan was submitted. The saved `authority_snapshot` is not an actual authority snapshot.

Impact: a plan author can nominate an ineligible or conflicted subject; a vendor may effectively approve its own commitment where policy prohibits it. This contradicts `T-PLAN-003/004` and the scoped, data-driven quorum requirement.

Fix: resolve eligible approvers from the authorization store at submit time, snapshot role assignment/scope/effective dates/policy version, reject all prohibited conflicts by default, and calculate quorum only over that immutable eligible set. Enforce the same rule at database/service boundary, including duplicates and concurrent approvals.

### Webhook verification uses one application-wide secret, not the connection secret reference

Evidence: the service accepts `vms.linear.webhook-secret` once in its constructor ([LinearIntegrationService.java](../../../backend/src/main/java/com/vms/workflow/application/LinearIntegrationService.java#L47)); `connection()` reads `webhook_secret_ref` only to test for non-null ([LinearIntegrationService.java](../../../backend/src/main/java/com/vms/workflow/application/LinearIntegrationService.java#L547)); `verifySignature()` always uses the global bytes ([LinearIntegrationService.java](../../../backend/src/main/java/com/vms/workflow/application/LinearIntegrationService.java#L580)).

Impact: one secret authenticates every tenant connection, rotation cannot be connection-specific, and a leaked/old secret compromises all callback endpoints.

Fix: introduce a server-only secret-provider abstraction keyed by `connectionId`/reference, cache only short-lived key material, support key rotation, and fail closed when a reference cannot be resolved. Keep the fixture secret strictly test-only and never use a global production fallback.

## High-priority findings (P1)

- The link endpoint authorizes the deliverable but not the submitted connection ([LinearIntegrationService.java](../../../backend/src/main/java/com/vms/workflow/application/LinearIntegrationService.java#L60)). The SQL trigger verifies engagement equality ([V7__delivery_planning_linear.sql](../../../backend/src/main/resources/db/migration/V7__delivery_planning_linear.sql#L385)), not provider organization/team ownership of the issue. The browser supplies issue UUID, title, state, and URL, then overwrites `linear_issue_current` for the connection ([LinearIntegrationService.java](../../../backend/src/main/java/com/vms/workflow/application/LinearIntegrationService.java#L92)). Resolve links server-side through an authorized adapter/recorded fixture and verify workspace/team/connection ownership; require and audit multi-link rationale.
- The canonical checksum omits dependencies and approver configuration/authority ([DeliveryPlanningService.java](../../../backend/src/main/java/com/vms/workflow/application/DeliveryPlanningService.java#L849)). It also relies on JSON serialization ordering for recipients. Canonicalize a typed, sorted full plan document and include every commitment-affecting field.
- Completeness only counts criteria/assignments/dependencies and a nonblank exception ([DeliveryPlanningService.java](../../../backend/src/main/java/com/vms/workflow/application/DeliveryPlanningService.java#L382)). It does not validate recipient contact groups, exception authority, link status/accessibility, target-month boundaries, dependency ownership, or a no-deliverables exception workflow. Implement explicit blockers with stable error codes.
- `process` is a permissioned synchronous HTTP command, not an asynchronous worker ([LinearIntegrationService.java](../../../backend/src/main/java/com/vms/workflow/application/LinearIntegrationService.java#L314)). Move queue claiming, retry/backoff/dead-letter and processing to a worker using `FOR UPDATE SKIP LOCKED`; keep the operator endpoint bounded to enqueue/replay requests.
- The public callback only detects size after Spring has materialized `byte[]` ([LinearIntegrationService.java](../../../backend/src/main/java/com/vms/workflow/application/LinearIntegrationService.java#L254)) and has no rate/request-content limits at the HTTP layer. Apply connector/request limits, content-type enforcement, rate limiting, and a minimal public error response before body allocation.

## Lower-priority findings (P2)

- Add database checks for valid quorum combinations, dependency semantics, category/priority values, one original baseline, and queue state transitions. Current checks are mostly shape checks; lifecycle correctness is application-only.
- Use injected `Clock` rather than `Clock.systemUTC()` fields ([DeliveryPlanningService.java](../../../backend/src/main/java/com/vms/workflow/application/DeliveryPlanningService.java#L54), [LinearIntegrationService.java](../../../backend/src/main/java/com/vms/workflow/application/LinearIntegrationService.java#L45)) so replay and timestamp tests are deterministic.
- `connection.status` is not checked before accepting a callback ([LinearIntegrationService.java](../../../backend/src/main/java/com/vms/workflow/application/LinearIntegrationService.java#L257)). Reject/record callbacks for disabled or action-required connections according to policy.

## Positive controls to retain

`DeliveryAuthorizationService` deliberately converts inaccessible scoped resources to the same `404` ([DeliveryAuthorizationService.java](../../../backend/src/main/java/com/vms/workflow/security/DeliveryAuthorizationService.java#L79)); raw-body HMAC uses `MessageDigest.isEqual`; and the worker logic updates only `execution_projection`, never acceptance/certification. Preserve those controls while closing the blockers.

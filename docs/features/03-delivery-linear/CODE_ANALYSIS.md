# F03 code analysis

## Implemented shape

The vertical adds a plan/version schema, nested draft creation, submit/checksum/approval/freeze/revision calls, stored Linear metadata, raw webhook receipt plus queue rows, and React plan/health routes. Security is predominantly service-scoped: plan/month/deliverable/link/connection lookups resolve engagement then call `AuthorizationStore` ([DeliveryAuthorizationService.java](../../../backend/src/main/java/com/vms/workflow/security/DeliveryAuthorizationService.java#L31)). The webhook is intentionally the one public F03 route ([SecurityConfig.java](../../../backend/src/main/java/com/vms/workflow/security/SecurityConfig.java#L28)).

The implementation is a local recorded-metadata vertical, not a Linear adapter/OAuth/GraphQL/mail vertical. There is no `LinearAdapter`, OAuth/PKCE callback/state, GraphQL client, reconciliation worker, email sender/attempt worker, or month-end snapshot job. The tables describe some of those future capabilities, but the services/controllers do not implement them. This must be communicated as incomplete rather than provider-ready.

## State and evidence analysis

The implemented transition is effectively `DRAFT -> PENDING_APPROVAL -> FROZEN -> SUPERSEDED`, with `REJECTED` terminal on a single reject. `READY_FOR_REVIEW`, `APPROVED`, `CHANGES_REQUESTED`, and `CANCELLED` exist only in the schema/frontend union. The frontend exposes approval for `READY_FOR_REVIEW` even though the backend rejects anything other than `PENDING_APPROVAL` ([delivery.plans.$planId.tsx](../../../src/routes/delivery.plans.$planId.tsx#L334), [DeliveryPlanningService.java](../../../backend/src/main/java/com/vms/workflow/application/DeliveryPlanningService.java#L189)). Either remove unsupported states/actions or implement and test the documented state machine.

Freeze writes a baseline, plan-time snapshots, commitment-outbox row, and audit event in one transaction ([DeliveryPlanningService.java](../../../backend/src/main/java/com/vms/workflow/application/DeliveryPlanningService.java#L283)). That is the right atomic boundary, but its evidence is incomplete: snapshots are copied from existing local current state, no fetch/failure job is run, the outbox has no sender/attempt implementation, and immutable source content is not protected. `Done` does correctly influence `execution_projection` alone; no code in this feature writes delivery acceptance/certification/invoice state.

## Persistence analysis

V7 has useful uniqueness and engagement triggers: one plan per month, stable deliverable code per plan, unique approval per subject, one baseline per version, unique queue delivery, and project/link engagement gates. It lacks a cross-table guarantee that an internal dependency points at a stable deliverable in the same plan/version, a connection's provider organization owns the linked issue, and frozen version content is immutable. SQL constraints should own these invariants because controllers/services are not the only possible writer.

The planned outbox is append-only at its own row level, but status cannot be updated because the migration declares the entire row immutable ([V7__delivery_planning_linear.sql](../../../backend/src/main/resources/db/migration/V7__delivery_planning_linear.sql#L418)). That conflicts with its `PENDING/SENT/RETRY/DEAD_LETTER` state model. Split immutable message payload from mutable delivery state/attempt rows, or narrowly permit status transition fields through a transition trigger.

## Exact frontend/API contract review

There are material contract mismatches:

- Frontend dependency literals are `DELIVERABLE` and `LINEAR_ISSUE` ([contracts.ts](../../../src/features/delivery/contracts.ts#L16)), while backend accepts `INTERNAL` and `LINEAR` ([DeliveryPlanningService.java](../../../backend/src/main/java/com/vms/workflow/application/DeliveryPlanningService.java#L45)). Selecting either non-external UI option yields a backend validation failure.
- Frontend snapshot status expects `SUCCESS`/`FAILED` ([contracts.ts](../../../src/features/delivery/contracts.ts#L215)); backend emits `CAPTURED`/`FETCH_FAILED` ([V7__delivery_planning_linear.sql](../../../backend/src/main/resources/db/migration/V7__delivery_planning_linear.sql#L289)).
- Frontend types several backend nullable strings as non-null, including `commitmentStatus` and current provider-state values. Draft `PlanView` can return a null outbox status ([DeliveryPlanningService.java](../../../backend/src/main/java/com/vms/workflow/application/DeliveryPlanningService.java#L676)).
- The UI says the server validates workspace scope, rejects inaccessible issues and decides multi-link rationale ([delivery.plans.$planId.tsx](../../../src/routes/delivery.plans.$planId.tsx#L526)), but that behavior is not implemented. Correct copy or implement it; presentation must not overstate control effectiveness.

The builder supports only one deliverable/criterion/dependency/assignment, has no approved exception workflow, and asks users to type opaque IDs. It therefore does not fulfil the planned multi-deliverable editor, resolved issue search, revision diff, current-vs-snapshot diff, or admin replay capability.

## Severity disposition

- **P0:** frozen/evidence data is mutable; scoped separation-of-duties authority is incomplete; a global callback secret is reused across connections.
- **P1:** issue workspace/connection ownership is not verified, checksum/completeness are incomplete, worker/outbox/reconciliation paths are absent, and the current frontend/API contract has literal and nullability mismatches.
- **P2:** consolidate the state model, inject clocks, replace opaque-ID entry with authorized selectors, and generate contract fixtures from API schemas.

## External gates

Do not enable/claim live Linear OAuth, GraphQL, webhook registration, or commitment email. Tenant approval is still required for app actor/workspace/team/scopes, secret manager and per-connection callback secret, production GraphQL schema/credentials, mail provider/sender/contact group/controlled mailbox, and sandbox/live acceptance. The current connection fixture marked `EXTERNALLY_BLOCKED` should be presented as readiness only, not live connectivity.

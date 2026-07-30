# F03 Backend API

## Draft replacement

`PUT /api/v1/delivery/plans/{planId}` replaces the exact current `DRAFT`
content, including repeated deliverables, criteria, dependencies, assignments,
approvers and recipient groups. `If-Match` carries the server-provided
`editVersion`; a successful edit advances that lock version. Submitted,
rejected, frozen and superseded versions remain immutable. A frozen version
must first use the reasoned revision command, after which the cloned draft can
be edited through this operation.

All planning and administration routes require a bearer JWT and active
organization/engagement-scoped permission. Inaccessible object IDs return the
same sanitized `404` as unknown IDs. The provider webhook is the only public
F03 route.

## Delivery planning

| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/api/v1/delivery/plans?engagementMonthId={uuid}` | query | `PlanSummaryView[]` |
| GET | `/api/v1/delivery/plans/{planId}` | — | `PlanView` |
| GET | `/api/v1/delivery/plans/{planId}/revision-comparison` | — | `RevisionComparisonView` |
| POST | `/api/v1/delivery/plans` | `CreatePlanRequest` | `201 PlanView` |
| POST | `/api/v1/delivery/plans/{planId}/submit` | — | `PlanView` |
| POST | `/api/v1/delivery/plans/{planId}/approvals` | `ApprovalRequest` | `PlanView` |
| POST | `/api/v1/delivery/plans/{planId}/revisions` | `RevisionRequest` | `201 PlanView` |
| GET | `/api/v1/delivery/commitment-operations?engagementId={uuid}&limit={1..100}` | query | `CommitmentDeadLetterView[]` |
| POST | `/api/v1/delivery/commitment-operations/{outboxId}/replays` | `CommitmentReplayRequest` + `Idempotency-Key` | `201 CommitmentReplayView` |

`CreatePlanRequest` contains `engagementMonthId`, plan title/summary/business
outcomes, coordinator subject, baseline type (`ON_TIME`, `LATE_APPROVED` or
`HISTORICAL_RECONSTRUCTED`), quorum mode (`ANY_ONE`, `ALL` or `N_OF_M`),
positive quorum requirement, approver subjects, all three recipient groups and
one or more nested deliverables.

Each deliverable contains its stable code, title, description, business
objective, project, product/vendor owner subjects, priority, target date,
evidence expectations, dependency-none declaration, risk/assumption text,
delivery category, optional link exception, independently testable acceptance
criteria, dependencies and effective employee assignments.

`ApprovalRequest` contains `decision` (`APPROVE` or `REJECT`) and an optional
comment. `RevisionRequest` requires both reason and impact. A successful
approval signs the submitted checksum. Quorum freezes the exact version,
creates an immutable baseline and plan-time Linear snapshots, and enqueues one
idempotent commitment-outbox record. Frozen content is changed only by creating
a draft revision with stable deliverable lineage.

`PlanView` returns plan/version identity and state, checksum and lineage,
completeness blockers, immutable recipient preview, nested deliverables,
criteria/dependencies/assignments/Linear links, approval evidence, baseline ID
and commitment status. Linear completion is exposed only as
`executionProjection`; it is not acceptance, certification, confirmation or
invoice eligibility.

`RevisionComparisonView` is available when the current version has a stored
predecessor. It returns the exact persisted predecessor/current IDs and
versions, changed top-level commitment fields, and added/removed/changed
deliverable counts. Original plans return empty changes and a null predecessor;
the endpoint never compares browser-selected versions or mutable Linear state.

Commitment dead-letter operations require the separately scoped
`delivery.commitment.replay` permission. List rows are bounded and omit
recipient/message content. A replay is reason-bound and idempotent per original
outbox row: it preserves that terminal row and appends an immutable command plus
a separate `PENDING` outbox row containing the same frozen commitment content.
It does not configure or imply a live email provider.

## Local Linear evidence

| Method | Path | Request | Response |
|---|---|---|---|
| POST | `/api/v1/integrations/linear/links` | `LinkIssueRequest` | `201 IssueLinkView` |
| GET | `/api/v1/integrations/linear/links/{linkId}/current` | — | `IssueCurrentView` |
| GET | `/api/v1/integrations/linear/links/{linkId}/snapshots` | — | `IssueSnapshotView[]` |
| GET | `/api/v1/integrations/linear/health?engagementId={uuid}` | query | `LinearHealthView` |
| GET | `/api/v1/integrations/linear/connections/{connectionId}/reconciliation-status` | — | `LinearReconciliationStatusView` |
| POST | `/api/v1/integrations/linear/deliveries/{deliveryId}/process` | — | `WebhookProcessView` |

`LinkIssueRequest` accepts only a draft `deliverableVersionId`, connection ID,
immutable issue UUID and optional multi-link rationale. Identifier, URL, title,
team/organization and original state are resolved from the server-side adapter
or immutable local fixture and validated against the authorized connection.
Unknown configured state mappings return `UNKNOWN`.

Current and snapshot responses include original provider state, normalized
state, timestamps and payload hash. Snapshot rows are append-only.
`LinearHealthView` reports registration/readiness, stale/linked counts and
durable queue/dead-letter counts without returning credential or webhook-secret
references.

`POST /api/v1/delivery/plans/{planId}/approve` accepts optional
`onBehalfOfSubject`. When present, the backend resolves one active shared-core
delegation for `delivery.plan.approve`, verifies engagement/project scope and
the configured approver, then records both the authority holder and acting
subject plus delegation ID. The checksum and quorum continue to belong to the
configured authority holder; the UI cannot invent delegation authority.

Scheduled delta reconciliation uses a provider-neutral adapter with a maximum
page size of 250, an ordered `(updatedAt, issue UUID)` cursor and a configurable
maximum page count per run. Each terminal page attempt stores cursor bounds,
counts, partial GraphQL errors and a SHA-256 evidence checksum. A GraphQL
data-plus-errors response is recorded as `PARTIAL`, does not advance the cursor,
marks retained state stale and enters bounded retry/dead-letter handling.

## Signed webhook

`POST /api/v1/integrations/linear/webhook/{connectionId}` consumes the exact
raw JSON bytes and requires:

- `Linear-Signature`: lowercase or uppercase hex HMAC-SHA256 of the raw body;
- `Linear-Timestamp`: epoch seconds or an ISO-8601 instant within 60 seconds;
- body `webhookTimestamp`: also within 60 seconds;
- `Linear-Delivery`: a UUID;
- body organization and connection values matching the selected connection.

The receiver rejects empty bodies and payloads above 262,144 bytes. Signature
comparison is constant-time. A valid delivery ID/fingerprint is persisted and
durably queued before `200 WebhookAcceptedView`; current processing is a
separate, permissioned, idempotent command. It is not yet an autonomous
retry/dead-letter worker.

The local receiver resolves current/previous key material from the connection's
`webhook_secret_ref` using server property `vms.linear.webhook-secret-set`.
There is no global fallback. Production enablement must replace the local
configuration adapter with tenant-approved secret-manager dereferencing. No
secret value or reference is returned by the API or documented in OpenAPI.

## Error status

- `400`: invalid DTO, lifecycle decision, signature/timestamp/delivery/body,
  oversized webhook or completeness validation;
- `401`: missing/invalid bearer authentication on secured routes;
- `403`: authenticated principal lacks the atomic scoped permission;
- `404`: sanitized unknown or inaccessible month/plan/deliverable/link/
  connection;
- `409`: lifecycle, duplicate, checksum/quorum, frozen mutation or revision
  conflict.

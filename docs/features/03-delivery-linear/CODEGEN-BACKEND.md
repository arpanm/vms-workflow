# F03 Backend Code Generation

## Result

Release hardening from the independent review is recorded in
`FIXES-BACKEND.md`. Flyway V8–V10 and the current Java services replace several V7
behaviors described below: protected commitment content is database immutable,
live projection is separate/audited, frozen revisions preserve the prior frozen
state, approval authority is server-resolved, link metadata is server-resolved,
and webhook keys are resolved per connection with rotation support.

Implemented a provider-neutral delivery-planning and recorded Linear-evidence
vertical under `backend/`. It extends the F01/F02 JWT and scoped-RBAC boundary.
It makes no live Linear GraphQL/OAuth call and sends no real commitment email.

## Append-only schema

Flyway V7 adds:

- stable delivery plans plus versioned content, approver authority and recipient
  snapshots;
- stable deliverable-code lineage plus criteria, dependencies and effective
  employee assignments;
- checksum-signed approvals, immutable baselines, commitment outbox/attempt and
  delivery audit records;
- Linear connection readiness and secret-reference metadata, versioned state
  mappings, issue links/current projections, append-only events and snapshots;
- durable webhook delivery/queue records and reconciliation job state;
- project/engagement integrity triggers, unique dedupe keys and immutable
  evidence triggers;
- atomic delivery and Linear read/manage/submit/approve/replay permissions with
  role mappings.

V1–V6 remain unchanged. No salary, payroll, rate, markup or employee billing
allocation field is introduced.

## Service behavior

- A nested draft is created for one engagement month with server validation of
  baseline/quorum configuration, projects, active owner/approver subjects,
  contact-group presence, criteria, dependencies and assignments.
- Submission recomputes completeness and a deterministic SHA-256 checksum. The
  current `delivery-commitment-v3` canonical document binds plan and
  engagement-month identity plus prior-version/reason/impact context, so two
  otherwise identical revisions cannot share a commitment checksum.
  Eligible approvals are scoped and creator self-approval is prohibited.
- Quorum atomically freezes the exact version, stores approval and authority
  evidence, creates a baseline and immutable plan-time Linear snapshots,
  renders escaped plain/HTML commitment content and enqueues one idempotent
  outbox record.
- Frozen versions reject in-place changes. A revision supersedes the frozen
  version and clones nested content while retaining stable deliverable IDs and
  prior-version reason/impact lineage.
- `GET /api/v1/delivery/plans/{planId}/revision-comparison` derives the current
  revision delta from stored plan and deliverable rows. It reports changed
  top-level commitment fields and added/removed/changed deliverable counts;
  clients cannot select an arbitrary predecessor or manufacture a diff.
- Recorded issue linking is allowed only on the current draft. Original provider
  state fields are retained and normalized through versioned type/category
  mappings; names are never guessed.
- The webhook receiver verifies the exact bytes, dual timestamps, organization,
  connection, delivery UUID, payload bound and HMAC before mutation. It records
  and queues once, while an authorized processing command updates current state
  and append-only event history idempotently.
- `COMPLETED` affects only deliverable execution projection. No Linear update
  changes acceptance, certification, confirmation, invoice or month business
  status.
- Health explicitly reports `NOT_CONFIGURED`, `ACTION_REQUIRED` and
  externally-blocked provider registration states.
- `DeliveryCommitmentOperationsService` lists at most 100 redacted dead letters
  in an authorized engagement and appends reason-bound, idempotent replay
  commands. The original terminal row is retained; each replay gets a new
  queued outbox row and audit event, without configuring or invoking a live
  email provider.

## Authorization

Authorization resolves each month, plan, deliverable, link or connection to its
engagement, then requires an active JWT subject, organization membership and
atomic permission at organization/engagement/object scope. Plan reads/manages,
submission, approval, Linear reads/manages and replay are distinct permissions.
Object denial is non-disclosing.

Only `/api/v1/integrations/linear/webhook/**` is anonymous. Other `/api/**`
routes remain authenticated, and existing F01/F02 security rules are retained.

## Explicit external boundary

The generated backend does not claim:

- an approved Linear OAuth application, PKCE callback, workspace/team scopes,
  live GraphQL adapter, webhook registration or secret rotation;
- a production secret-manager adapter (the local server configuration resolver
  is per-reference and has no global fallback);
- a live Linear GraphQL/OAuth adapter or production queue transport. The local
  scheduled delta worker, compound cursor, retry/dead-letter checkpoint and
  immutable page-attempt evidence are provider-neutral and disabled by default;
- a selected mail provider, sender/mailbox, real delivery attempts, callbacks
  or inbound-message processing. The local recorded commitment worker provides
  bounded retry/dead-letter behavior only.
- delivery acceptance/certification workflows.

Those require tenant decisions, credentials and external acceptance. Local
planning, baseline, outbox, recorded evidence and durable webhook processing
remain usable without fabricating connectivity.

## Verification

```text
mvn -B -f backend/pom.xml verify
DeliveryApprovalConcurrencyIT: 1 test
DeliveryLinearIT: 14 tests
WorkforceAttendanceIT: 20 tests
ApiTenantSecurityIT: 11 tests
JwtDecoderIT: 3 tests
Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

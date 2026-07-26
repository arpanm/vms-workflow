# F03 backend release-hardening evidence

Date: 2026-07-26
Scope: local Java/Spring/PostgreSQL implementation only. No Supabase, Lovable,
live Linear tenant, OAuth credential, secret-manager call, or mail provider was
used.

## Implemented

- Flyway V8 adds database guards for `PENDING_APPROVAL`, `FROZEN`, and legacy
  `SUPERSEDED` plan content. The plan version, approvers, recipients,
  deliverables, criteria, dependencies, assignments, and issue links reject
  protected inserts/updates/deletes. Only the explicit
  `PENDING_APPROVAL -> FROZEN|REJECTED` lifecycle transition is allowed.
- A revision no longer changes the prior frozen version to `SUPERSEDED`.
  `delivery_plans.current_version_id` points to the new draft while the prior
  version, its baseline, checksum, snapshots, and outbox evidence remain frozen.
- Live execution state moved to `delivery_execution_projections`.
  `delivery_execution_projection_events` is append-only and populated by a
  database audit trigger. Webhook processing never updates
  `delivery_deliverable_versions.execution_projection`; that V7 column is
  retained only for migration compatibility and remains `UNKNOWN` for new rows.
- Submission resolves each nominated approver from active profile, membership,
  role assignment, role permission, organization, engagement/project scope, and
  effective dates. It snapshots assignment IDs, role, organization, scope,
  effective dates, permission, policy version, and capture time before computing
  the checksum. Creator, submitter, coordinator, product-owner, and vendor-owner
  conflicts fail submission. PostgreSQL independently rejects votes without an
  eligible snapshot or with a separation-of-duty conflict.
- Approval serializes on the current plan/version row. Unique votes plus the row
  lock make quorum evaluation and freeze/outbox creation concurrency-safe.
- `WebhookSecretResolver` resolves current and previous keys by the connection's
  `webhook_secret_ref`. Missing references fail closed. Raw keys remain
  server-side configuration, not database columns; the V7 application-wide
  fallback is no longer used.
- Webhook receipt validates body bounds, active connection, delivery UUID,
  header timestamp, and HMAC over exact bytes before JSON parsing. It then
  validates body timestamp, organization, and optional connection. Exact bytes
  are retained in `raw_body` separately from parsed `raw_payload`. Reuse of a
  delivery UUID for another connection or payload is rejected.
- Provider `updatedAt` ordering is enforced under a row lock. Older valid events
  are stored with `STALE_IGNORED`, audited in
  `linear_webhook_audit_events`, and do not regress current state or execution
  projection.
- Link input is reduced to deliverable version, connection, issue UUID, and
  optional rationale. Both resources are authorized. Metadata is loaded from an
  immutable server-recorded fixture boundary and checked against connection
  engagement, provider organization, and team. A second active link requires a
  rationale. Browser-supplied title/URL/state fields are neither in the OpenAPI
  request schema nor trusted if sent as unknown JSON properties.
- The v2 canonical checksum uses length-prefixed fields and stable sorting. It
  covers plan fields, deliverable and stable/version IDs, criteria,
  dependencies, assignments, server-resolved approver authority, sorted
  recipients, link IDs/connection/status/rationale, and immutable plan-time
  snapshot IDs/provider facts/hashes.
- Completeness now reports stable blockers for inaccessible/stale links, short
  exception rationale, target dates outside the engagement month, internal
  dependency ownership, inactive dependency owners, and allocation validity.
  DTOs bound list/text/email/subject/comment/rationale sizes and constrain
  baseline, quorum, decision, priority, category, and dependency enums.

## PostgreSQL integration evidence

### Second-review V9 integrity closure

The post-fix review identified one blocking local defect: V8 protected
version-owned rows but not the stable `delivery_deliverables` row, and its
plan-version trigger did not compare every identity/lifecycle column on the
permitted terminal transition.

Flyway V9 closes this without rewriting V7/V8:

- stable deliverable `id`, `plan_id`, `deliverable_code`, `created_at`, and row
  deletion are rejected once any deliverable-version lineage exists;
- the only state changes are `DRAFT -> PENDING_APPROVAL` and
  `PENDING_APPROVAL -> FROZEN|REJECTED`;
- direct draft-to-frozen/superseded, arbitrary pending/frozen/rejected changes,
  and same-state protected updates fail;
- submit requires unchanged identity/content, a canonical checksum, exactly one
  optimistic increment, submission time, and valid authority/SOD/quorum;
- freeze/reject compares identity, plan/version, all commitment and lineage
  fields, checksum, submission/creation facts, and the expected optimistic
  increment. Only freeze may set a previously-null, non-regressing `frozen_at`.

The direct-SQL integration test attempts stable code/plan rewrites and deletion,
invalid draft/pending/frozen transitions, identity/version/optimistic mutations,
content tampering during freeze, and invalid freeze timestamps. Legitimate
submit, approve/freeze, concurrent quorum, and revision paths still pass.

Targeted:

```text
mvn -B -f backend/pom.xml \
  -Dit.test=DeliveryLinearIT,DeliveryApprovalConcurrencyIT verify
DeliveryApprovalConcurrencyIT: 1
DeliveryLinearIT: 11
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Full backend regression:

```text
mvn -B -f backend/pom.xml verify
JwtDecoderIT: 3
DeliveryApprovalConcurrencyIT: 1
WorkforceAttendanceIT: 20
DeliveryLinearIT: 11
ApiTenantSecurityIT: 11
Tests run: 46, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The expanded tests directly prove:

- protected SQL mutations fail for the plan, deliverable, criteria,
  dependencies, assignments, recipients, approvers, and links;
- webhook completion changes only the separate projection table and creates
  immutable projection audit history;
- two connections use different keys, a previous key remains valid during
  rotation, a cross-connection key fails, exact bytes round-trip, and a
  mismatched delivery-ID reuse fails;
- scoped authority snapshots are real database facts, owner conflict and
  unsnapshotted votes fail, and two concurrent `ALL` votes freeze once with one
  baseline/outbox; a later duplicate fails;
- dependency, approver, recipient, link, and plan-time snapshot mutations each
  change the draft checksum;
- forged link metadata is ignored in favor of server metadata, wrong-team issue
  access fails, and multi-link rationale is mandatory;
- an out-of-order provider event is retained/audited but current state and
  projection do not regress;
- OpenAPI exposes only the four server-resolved link request properties and
  secret material is absent.

### Focused V10 terminal-evidence integrity closure

Flyway V10 closes the three P0 gaps retained by the focused V9 review while
leaving V7-V9 unchanged:

- a correctly shaped `PENDING_APPROVAL -> FROZEN` update now requires an
  eligible, checksum-matching approval quorum, no reject vote, a matching
  baseline with the current deliverable count, the matching idempotent
  commitment outbox row, and a `PLAN_FROZEN` audit actor attributable to a
  matching approve vote;
- `DeliveryPlanningService.freeze` writes baseline, outbox, and attributable
  audit evidence before the final state update inside the existing transaction
  and row lock. The database trigger validates that evidence; any failure rolls
  the evidence and state change back atomically;
- `PENDING_APPROVAL -> REJECTED` requires a signed, checksum-matching `REJECT`
  vote;
- `protected_delivery_version_state` now includes `REJECTED`, so rejected
  approvers, recipients, deliverables, criteria, dependencies, assignments, and
  links reject inserts, updates, and deletes;
- a new trigger requires
  `delivery_deliverables.plan_id = delivery_plan_versions.plan_id` for every
  deliverable-version insert and ownership move. V10 first scans existing rows
  and fails the migration if cross-plan lineage is already present.

The focused direct-SQL tests prove that:

- a shape-correct freeze with zero approvals is rejected;
- a matching approval still cannot freeze without a baseline, then cannot
  freeze with a baseline but no outbox, and cannot freeze without attributable
  audit evidence;
- the complete matching evidence set permits the terminal transition;
- rejection without a reject vote fails, the service rejection succeeds, and
  rejected child update/delete/insert attempts fail;
- cross-plan deliverable-version insertion and later movement both fail;
- the pre-existing legitimate submit/approve/freeze/revision and concurrent
  `ALL` quorum exactly-once paths remain green.

Targeted V10 evidence:

```text
mvn -B -f backend/pom.xml \
  -Dit.test=DeliveryLinearIT,DeliveryApprovalConcurrencyIT verify
DeliveryApprovalConcurrencyIT: 1
DeliveryLinearIT: 14
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Full backend regression after V10:

```text
mvn -B -f backend/pom.xml verify
JwtDecoderIT: 3
DeliveryApprovalConcurrencyIT: 1
WorkforceAttendanceIT: 20
DeliveryLinearIT: 14
ApiTenantSecurityIT: 11
Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Test-run incident retained for the ledger

The first V8 targeted run failed during Flyway test fixture loading because a
new F03 profile reused V1001 UUID `...0212` (`SQLSTATE 23505`). The fixture was
moved to unused IDs. A later combined run passed the concurrency test but one
DeliveryLinear assertion saw two global baselines because the concurrency test
intentionally commits an August plan. The invariant assertion was corrected to
scope baseline/outbox counts to its plan. No production behavior was weakened.

## Exact residual gaps

- A durable autonomous worker is **not implemented**. V8 adds claim timestamps
  and a claim index, but processing is still initiated by the authenticated
  delivery command and does not use `FOR UPDATE SKIP LOCKED`, bounded retry,
  backoff, dead-letter transition, or replay enqueue semantics. These must not
  be reported as passing.
- `byte[]` request binding still means the application-level 256 KiB check runs
  after the servlet container allocates the body. Production deployment still
  needs connector/proxy request-size, encoding, rate, and concurrency limits.
- The configured resolver is a local/server configuration adapter. A production
  secret-manager implementation, cache TTL/revocation policy, and operational
  rotation procedure remain external work.
- The immutable recorded-issue table is a local adapter fixture. Live Linear
  GraphQL/OAuth ownership, pagination, partial error, rate-limit, revocation,
  webhook registration, and reconnect acceptance remain external gates.
- Mail transport/retry/dead-letter/callback, database least-privilege roles,
  real provider request timing tests, and backend-backed browser E2E remain
  outside this backend-only change.

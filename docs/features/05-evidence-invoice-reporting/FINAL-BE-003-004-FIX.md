# F05 Final Backend Findings 003–004 Fix

## Scope

This change resolves `F05-FINAL-BE-003` and `F05-FINAL-BE-004` from
[FINAL_REVIEW-BACKEND.md](FINAL_REVIEW-BACKEND.md). It does not change report
projection/rendering behavior.

## Database keyset and snapshot pagination

- Procurement control-tower rows are queried in PostgreSQL by
  `(month_start_date, engagement_month_id)` keyset with a 51-row bounded query.
- Report export history is queried by `(requested_at, export_id)` keyset with
  the same bounded query.
- The first page records a snapshot timestamp in a signed opaque cursor.
  Subsequent pages exclude rows created after that timestamp, so concurrent
  inserts cannot introduce duplicates or skipped rows.
- Each cursor is HMAC-signed and bound to the route, authenticated subject and
  sorted authorized engagement set. Authorization-scope changes, cross-route
  reuse, cross-user reuse, tampering and expiry are rejected.
- `VMS_FINANCE_CURSOR_SIGNING_SECRET` is required and must contain at least 32
  characters. `VMS_FINANCE_CURSOR_TTL` defaults to 30 minutes.

Automated coverage:

- `FinancePageCursorCodecTest`: signature, actor/resource/scope binding,
  tamper rejection and stale expiry.
- `FinancePaginationIT`: two-page continuity, concurrent insertion exclusion,
  update continuity, stable total count and cross-route/cross-user rejection.

## Legal-hold and scanner-state governance

- `artifact.legal-hold.manage` is granted only to organization/engagement
  administrators and governance reviewers.
- `POST /api/v1/finance/artifacts/{artifactId}/legal-hold` requires active
  scoped permission, a reason code and an idempotency key.
- Every hold change creates an append-only transition containing the prior and
  new state, authority snapshot, actor, correlation ID and reason.
- The artifact trigger accepts a hold mutation only when a matching unapplied
  transition exists in the same database transaction. An after-trigger marks
  that transition applied, preventing reuse.
- Every accepted hold change creates both a domain event/outbox entry and an
  independent immutable audit event.
- Every permitted `PENDING`/`UNKNOWN` scanner completion automatically creates
  an immutable `ARTIFACT_SCAN_STATE_CHANGED` audit record with engine and
  old/new state. Terminal-state rewrites and forensic-field rewrites remain
  rejected by the database trigger.

Automated coverage:

- `FinanceArtifactGovernanceIT`: authorized, audited, idempotent legal hold;
  unauthorized denial; independent scanner-transition audit.
- `FinanceDatabaseControlsIT`: direct-SQL hold mutation, terminal scan rewrite
  and scan-forensics rewrite rejection.

## Verification

- `mvn -B -f backend/pom.xml -DskipTests compile` — passed.
- `mvn -B -f backend/pom.xml -Dtest=FinancePageCursorCodecTest test` — passed,
  2 tests.
- The focused Testcontainers integration run was started but stopped after the
  local Docker engine did not finish starting PostgreSQL within the bounded
  wait. The root regression run must execute the three integration classes
  serially when Docker is responsive.

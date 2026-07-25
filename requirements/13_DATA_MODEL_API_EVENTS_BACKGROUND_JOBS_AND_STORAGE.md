# 13 — Data Model, API/Service Contracts, Events, Background Jobs and Storage

**Version:** 1.0
**Status:** Technical build specification
**Target stack:** Existing React 19 + TanStack Start/Router/Query + TypeScript + Supabase/PostgreSQL/Storage/Edge Functions
**Related:** All functional PRDs, especially 02, 14, 17

---

## 1. Architecture decision

Extend the existing application in place. Do not introduce Java microservices or a separate backend solely because the old conceptual PRD suggested them. Use clear domain modules and server boundaries within the current stack:

- React/TanStack route UI;
- TanStack server functions or Supabase Edge Functions for privileged orchestration/integration endpoints;
- Supabase Auth/federated identity;
- PostgreSQL as transactional source of truth;
- Row-Level Security plus server authorization;
- Supabase Storage for evidence files;
- scheduled functions/`pg_cron` or approved job runner;
- transactional outbox for notifications/events;
- provider adapters for greytHR, Linear and email;
- optional queue infrastructure where reliable async processing exceeds database-job suitability.

Domain separation and contracts must make later service extraction possible without premature microservices.

---

## 2. Schema namespaces/conventions

Recommended logical schemas:

- `iam` — identities, memberships, roles, permissions, delegations.
- `core` — organizations, engagements, projects, months, contacts, policies.
- `workforce` — employees, allocations, calendars, leave, attendance.
- `delivery` — plans, deliverables, Linear links/snapshots, submissions, certifications.
- `evidence` — confirmations, invoices, packages, artifacts.
- `integration` — connections, mappings, sync/webhook/import jobs, staging metadata.
- `audit` — append-only audit/outbox/domain events.
- `reporting` — views/materialized summaries.

Supabase tooling may require/publicly expose selected schemas; expose only safe APIs/views and preserve the logical names if feasible.

Conventions:

- UUID primary keys generated server-side.
- `created_at`, `created_by`, `updated_at`, `updated_by` on mutable masters.
- `valid_from`, `valid_to` on effective-dated records.
- `version`, `supersedes_id`, `status` on versioned business records.
- UTC `timestamptz`; explicit local date/timezone fields where business date matters.
- snake_case database, camelCase TypeScript mapping generated/typed.
- soft archive/status, not destructive delete, after referenced.

---

## 3. Core table catalog

### 3.1 IAM

- `user_profiles`
- `organization_memberships`
- `roles`
- `permissions`
- `role_permissions`
- `role_assignments`
- `delegations`
- `access_reviews`
- `service_accounts`

### 3.2 Organization/engagement

- `organizations`
- `engagements`
- `engagement_config_versions`
- `projects`
- `teams`
- `contact_groups`
- `contact_group_memberships`
- `engagement_role_assignments`
- `approval_policy_versions`
- `approval_policy_stages`
- `approval_requests`
- `approval_actions`
- `engagement_months`
- `month_transition_history`
- `reopen_requests`

### 3.3 Workforce

- `employees`
- `employee_external_aliases`
- `employee_user_links`
- `employee_engagement_assignments`
- `employee_project_allocations`
- `deliverable_employee_assignments`
- `working_calendar_versions`
- `working_calendar_weekdays`
- `holiday_calendar_versions`
- `holidays`
- `employee_calendar_assignments`
- `employee_date_overrides`
- `attendance_policy_versions`
- `leave_policy_versions`
- `leave_types`
- `leave_balance_ledger`
- `leave_requests`
- `leave_request_days`
- `attendance_events`
- `attendance_sessions`
- `attendance_days`
- `attendance_exceptions`
- `regularization_requests`
- `regularization_actions`
- `attendance_snapshot_versions`
- `attendance_snapshot_days`
- `roster_snapshot_versions`
- `roster_snapshot_members`

### 3.4 Delivery/Linear

- `monthly_plans`
- `monthly_plan_versions`
- `deliverables`
- `deliverable_versions`
- `acceptance_criteria`
- `deliverable_dependencies`
- `deliverable_linear_links`
- `linear_issue_current`
- `linear_issue_event_history`
- `linear_issue_snapshots`
- `delivery_submissions`
- `deliverable_delivery_outcomes`
- `delivery_evidence_items`
- `certification_rounds`
- `deliverable_certifications`
- `certification_criterion_results`
- `certification_clarifications`
- `carry_forward_links`
- `monthly_certification_summaries`

### 3.5 Evidence/invoice

- `business_confirmation_requests`
- `business_confirmation_actions`
- `email_messages`
- `email_delivery_attempts`
- `inbound_email_messages`
- `notification_preferences`
- `notification_outbox`
- `evidence_artifacts`
- `evidence_package_versions`
- `evidence_package_items`
- `invoices`
- `invoice_versions`
- `invoice_readiness_runs`
- `invoice_readiness_results`
- `procurement_reviews`
- `payment_status_history`

### 3.6 Integration/import/audit

- `integration_connections`
- `integration_connection_versions`
- `external_employee_mappings`
- `external_type_mappings`
- `sync_jobs`
- `sync_job_batches`
- `sync_failures`
- `webhook_deliveries`
- `import_jobs`
- `import_rows`
- restricted staging tables/buckets per template;
- `domain_events`
- `transactional_outbox`
- `audit_events`
- `security_events`
- `idempotency_keys`

---

## 4. Critical database constraints

- Unique organization membership `(user_id, organization_id)` by active period.
- Unique employee number/work email within organization subject to effective/status rules.
- Unique engagement month `(engagement_id, month_start_date)`.
- Project belongs to engagement and all referenced objects share engagement/organization scope.
- Allocation percent >0 and ≤100; temporal total-overlap validation through constraint trigger/service.
- Attendance event immutable; unique source/idempotency key.
- One open attendance session per employee at a time.
- Attendance sessions cannot overlap.
- One computed attendance day per `(employee_id, local_date, calculation_version/current flag)`.
- Leave/regularization requests cannot overlap incompatible units/date unless superseding.
- Leave ledger entries immutable and unique idempotency keys.
- One current plan/attendance/roster/confirmation/package version per engagement month using partial unique indexes.
- Approved/frozen/closed records blocked from generic update by trigger/service role policy.
- Linear link unique `(deliverable_version_id, linear_issue_uuid)`.
- Webhook delivery/event fingerprint unique.
- Invoice number unique per vendor organization, with correction lineage.
- Package items reference immutable artifact versions and hashes.
- All child records verify same engagement scope, preferably via composite keys or validation functions.

Use database constraints for invariants, not only frontend validation.

---

## 5. RLS and authorization data pattern

- `organization_memberships` and `role_assignments` provide scope claims.
- Security-definer helper functions may evaluate permissions, but must have fixed `search_path`, minimal grants and tests.
- User-facing tables enable RLS with explicit select/insert/update/delete policies.
- Service-role access is limited to trusted server functions; browser never receives service key.
- Storage object paths contain organization/engagement IDs and use matching policies.
- Reporting views must not bypass RLS accidentally; use security-invoker semantics or server-generated exports.
- Audit/integration secret tables are inaccessible to normal client sessions.

Detailed policy requirements are in PRD 14.

---

## 6. Service/API modules

Use typed domain services rather than direct table mutations from pages.

### 6.1 Core/admin

- `OrganizationService`
- `EngagementService`
- `ProjectService`
- `MonthLifecycleService`
- `IdentityAccessService`
- `ApprovalService`
- `ContactGroupService`

### 6.2 Workforce

- `EmployeeService`
- `AllocationService`
- `CalendarService`
- `LeaveLedgerService`
- `LeaveRequestService`
- `AttendanceEventService`
- `AttendanceCalculationService`
- `RegularizationService`
- `AttendanceCloseService`

### 6.3 Delivery

- `PlanService`
- `DeliverableService`
- `LinearIntegrationService`
- `DeliverySubmissionService`
- `CertificationService`
- `CarryForwardService`

### 6.4 Evidence/finance

- `NotificationService`
- `ConfirmationService`
- `EvidencePackageService`
- `InvoiceService`
- `ProcurementReviewService`
- `PaymentStatusService`

### 6.5 Integration/migration

- `GreytHRAdapter`
- `LinearAdapter`
- `EmailAdapter`
- `ImportService`
- `SyncJobService`
- `WebhookService`

Each mutation returns typed result/error codes, current version and audit correlation ID.

---

## 7. API contract principles

- Validate request with shared schemas (e.g. Zod) server-side.
- Never accept organization/role authority solely from client; derive/check against authenticated user.
- Optimistic concurrency: require expected version/ETag for mutable records.
- Idempotency key required for punches, external callbacks, package generation, imports and high-impact submissions.
- Pagination/cursors for lists; no unbounded selects.
- Stable error format:

```json
{
  "code": "ATTENDANCE_OPEN_SESSION_EXISTS",
  "message": "An open attendance session already exists.",
  "correlationId": "...",
  "fieldErrors": {},
  "retryable": false
}
```

- Sensitive provider errors are sanitized for UI; full details in restricted logs.
- Use server-generated signed URLs for authorized file access.

---

## 8. Domain event catalog

Canonical envelope:

```json
{
  "eventId": "uuid",
  "eventType": "attendance.day.finalized.v1",
  "occurredAt": "2026-07-25T06:00:00Z",
  "recordedAt": "2026-07-25T06:00:01Z",
  "organizationId": "uuid",
  "engagementId": "uuid-or-null",
  "actor": {"type": "USER|SERVICE|INTEGRATION", "id": "uuid"},
  "subject": {"type": "attendance_day", "id": "uuid", "version": 3},
  "correlationId": "uuid",
  "causationId": "uuid-or-null",
  "payload": {},
  "schemaVersion": 1
}
```

Key events:

- identity/membership/role/delegation changed;
- engagement/project/config published;
- employee activated/disabled/exited;
- allocation/calendar/leave-balance changed;
- attendance checked in/out, exception opened/resolved, day finalized, snapshot closed/reopened;
- plan submitted/approved/revised;
- Linear link/snapshot/sync failed;
- delivery submitted/certified/clarification requested;
- confirmation requested/received/rejected/expired;
- package generated/superseded;
- invoice uploaded/ready/submitted/reviewed/paid;
- import/sync job completed/failed;
- month transitioned/reopened/closed.

Transactional outbox is written in the same transaction as business state. Consumers are idempotent.

---

## 9. Background job catalog

| Job | Trigger/cadence | Idempotency key/result |
|---|---|---|
| Generate expected attendance days | daily + employee/calendar change | employee/date/policy version |
| Detect missing check-in/open session | scheduled local-time windows | employee/date/exception type |
| Recalculate attendance day | event driven | employee/date/source versions |
| Monthly leave accrual | policy period | employee/type/period/policy version |
| Reminder/escalation dispatcher | frequent schedule | task/stage/reminder number |
| greytHR sync/reconcile | schedule/on demand | connection/entity/range/checkpoint |
| Linear webhook processor | event | delivery ID/event fingerprint |
| Linear delta/nightly reconcile | schedule | connection/window/page checkpoint |
| Plan/month-end Linear snapshot | state transition | plan/month version |
| Attendance/roster snapshot | close transition | month/input version set |
| Email outbox sender | event/retry | outbox message ID |
| Inbound email processor | provider event/poll | provider message ID |
| Evidence package generator | readiness/version change | canonical input manifest hash |
| Readiness evaluator | event/manual | month/invoice/input versions |
| Export generator | user request | export request ID |
| Historical import processor | user job | job/file hash/checkpoint |
| Retention/legal hold processor | scheduled | artifact/policy version |

Jobs expose attempts, progress, next retry, error category and correlation IDs. Bounded retries feed dead-letter queues; admins can replay after correction.

---

## 10. Storage design

Recommended private buckets/logical prefixes:

- `employee-imports/<org>/<job>/...`
- `attendance-evidence/<org>/<engagement>/<month>/...`
- `deliverable-evidence/...`
- `email-evidence/...`
- `invoices/...`
- `evidence-packages/...`
- `exports/...`

Metadata in `evidence_artifacts` includes original/safe display name, MIME, size, hash, classification, uploader/source, scan status, retention/legal hold and object version.

Controls:

- private by default;
- allowlisted MIME/extensions and content sniffing;
- malware scan before available;
- immutable/versioned path for approved evidence;
- signed URLs with short expiry;
- no predictable public URLs;
- quarantine failed files;
- sanitize filenames and escape document content where rendered;
- package generator uses only scan-passed/current-version artifacts.

---

## 11. Canonical serialization and hashing

- Use deterministic JSON canonicalization for snapshot/package manifests.
- Normalize timestamps to UTC ISO 8601, sorted object keys and stable array ordering by defined keys.
- SHA-256 hash of canonical bytes.
- Record hash algorithm/version.
- Signatures/digital signing may be added later; checksum alone proves integrity comparison, not signer identity.
- Rendering engines/version/fonts are recorded for reproducibility without sharing font binaries.

---

## 12. Integration staging and raw data

- Keep provider raw payloads only when required for reconciliation/audit and within retention/privacy policy.
- Restrict access to integration admins/auditors.
- Store payload hash and minimal extracted data where full payload retention is unnecessary.
- Stage imports in isolated tables/buckets; canonical tables are updated only after validation/approval.
- Quarantine schema changes and unknown fields.

---

## 13. Reporting data

- Transactional data remains source of truth.
- Use SQL views/materialized views for dashboard summaries with refresh metadata.
- Do not create an independent analytics truth that can diverge from evidence snapshots.
- Report rows carry current/snapshot version and freshness.
- Heavy analytics/export can use a read replica/warehouse later via versioned contracts.

---

## 14. Observability

For every request/job/integration:

- correlation/trace ID;
- domain/action/user/organization/engagement without sensitive payloads;
- latency, status/error code;
- provider call metrics, retries/rate-limit state;
- queue depth/job lag;
- snapshot/package generation duration/failure;
- audit event write success.

Alerts for failed auth, repeated webhook signature failures, stale sync, outbox backlog, package hash mismatch, cross-tenant authorization attempts and backup failures.

---

## 15. Migration compatibility with existing five tables

Existing `engagements`, `requirements`, `approvals`, `uat_items`, `invoices` are not dropped in the first migration.

- Add organization/tenant/effective/version fields or create new canonical tables and map legacy IDs.
- Treat existing requirements as legacy deliverable candidates only through an explicit migration.
- Treat UAT items as legacy certification evidence, not automatically equivalent to new product-owner certification.
- Existing invoice records can map to invoice versions with source `LEGACY_APP`.
- Legacy routes remain behind a feature flag until data/UX cutover.
- Remove anonymous policies before production workforce data is loaded.

See PRD 17 for exact sequence.

---

## 16. Acceptance criteria

- All high-impact mutations use server-side authorization and typed validation.
- Database rejects duplicate punch idempotency key, overlapping sessions and multiple current month versions.
- Outbox event is created atomically with business transition.
- Failed notification consumer can replay without duplicate email/business action.
- Package input hash changes whenever any included source version changes.
- Signed storage URL cannot be generated by an unauthorized user.
- RLS tests cover every user-facing table/view/storage prefix.
- Background jobs are idempotent and resume from checkpoints.
- Provider secrets never appear in client bundle, normal logs or API responses.
- Existing legacy data remains available through controlled migration/cutover.

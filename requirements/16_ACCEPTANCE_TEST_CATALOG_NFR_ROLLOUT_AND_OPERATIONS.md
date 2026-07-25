# 16 — Acceptance Test Catalog, Non-Functional Requirements, Rollout and Operations

**Version:** 1.0
**Status:** Release-gate specification
**Related:** All PRDs, especially 13-15, 17

---

## 1. Objective

Define the minimum test, reliability, performance, security, accessibility, migration and operational gates required before production use as invoice-supporting evidence.

---

## 2. Test strategy

### 2.1 Layers

- Unit tests for policy/calculation/state-machine functions.
- Database tests for constraints, RLS, triggers and idempotency.
- Contract tests for service schemas and provider adapters.
- Integration tests with greytHR/Linear/email sandbox or recorded fixtures.
- Component tests for forms/tables/permissions/states.
- End-to-end browser tests for persona workflows.
- Migration reconciliation tests.
- Performance/load/soak tests.
- Security/abuse/file tests.
- Accessibility tests plus manual keyboard/screen-reader review.
- Backup/restore/disaster-recovery exercises.

### 2.2 Test data

Use synthetic, non-production data with:

- multiple organizations/engagements/projects;
- active/disabled/exited employees;
- different calendars and source modes;
- full/half/overnight sessions, leave/LWP and conflicts;
- plan revisions and custom Linear states;
- accepted/partial/rejected delivery;
- verified, ambiguous and spoofed confirmations;
- historical June imports;
- package/invoice corrections.

Do not use real salary or production secrets.

### 2.3 Traceability

Every mandatory requirement/user story has:

- requirement ID;
- test IDs;
- automated/manual status;
- result/evidence link;
- release version.

No “implemented” status without passing test evidence or approved exception.

---

## 3. End-to-end acceptance journeys

### E2E-01 — New employee to monthly evidence

1. Admin creates employee, policies, allocation and opening leave.
2. Employee checks in/out across normal month and applies leave/regularization.
3. Attendance closes.
4. Employee appears in roster/attendance package.

Expected: correct source, minutes/status, allocation and audit; no salary data.

### E2E-02 — greytHR-authoritative month

1. Connection capability is certified.
2. Employees/attendance/leave sync.
3. Conflict is resolved.
4. Month snapshot generated.

Expected: internal check-in disabled, source/freshness visible, replay idempotent.

### E2E-03 — Plan to Linear to certification

1. Product owner creates plan/deliverables and links issues.
2. Plan approved/frozen and commitment email sent with Procurement CC.
3. Webhooks update current state; plan snapshot remains.
4. Vendor submits delivery.
5. Product owner certifies with one partial carry-forward.

Expected: versioned baseline/snapshots/certification and carry-forward lineage.

### E2E-04 — Consolidated confirmation

1. Attendance/certification readiness passes.
2. Confirmation request is sent.
3. Eligible product owner uses secure link and confirms exact version.
4. Outcome email sent and invoice becomes eligible for readiness evaluation.

Expected: token/identity/version/audit evidence and no duplicate action on replay.

### E2E-05 — Email reply confirmation

1. Request is sent to monitored thread.
2. Verified eligible sender replies explicitly.
3. Provider message is ingested and matched.
4. Confirmation quorum completes.

Expected: headers/message ID/authentication metadata preserved; ambiguous/unrecognized replies do not confirm.

### E2E-06 — Procurement package/invoice

1. Vendor uploads invoice.
2. Readiness rules pass.
3. Package generated and reviewed.
4. Procurement approves; payment status advances.

Expected: manifest/checksums, role scope, version and complete audit.

### E2E-07 — Post-close correction

1. Closed month attendance correction is requested.
2. Authorized reopen occurs.
3. New attendance snapshot invalidates confirmation/package.
4. New confirmation/package generated.

Expected: prior versions retained/superseded, invoice readiness recalculated.

### E2E-08 — Historical June backfill

1. Masters and June CSVs imported through dry-run.
2. Invalid rows corrected/reprocessed.
3. Original approval email imported; missing confirmation requested retroactively.
4. Package generated.

Expected: represented versus actual timestamps, provenance/confidence, reconciliation counts.

### E2E-09 — Cross-tenant attack

User from Vendor/Engagement A manipulates IDs/API/storage URL for Engagement B.

Expected: denied without record details; security event/audit.

### E2E-10 — Integration outage

Linear/greytHR unavailable near month end.

Expected: last known data/stale warning/retry/fallback workflow, no silent current truth or lost evidence.

---

## 4. Detailed test catalog

### 4.1 Identity/RBAC

- `T-IAM-001` invite valid user.
- `T-IAM-002` expired/revoked invite.
- `T-IAM-003` multi-role scoped permissions.
- `T-IAM-004` disabled user/session.
- `T-IAM-005` delegation active/expired.
- `T-IAM-006` separation of duties.
- `T-IAM-007` direct API/RLS cross-tenant denial.
- `T-IAM-008` service account minimal access.
- `T-IAM-009` report/storage permission parity.
- `T-IAM-010` demo role selector cannot affect production rights.

### 4.2 Employee/allocation/calendar/leave ledger

- `T-WF-001` unique employee number/email.
- `T-WF-002` disable/enable/exit lifecycle.
- `T-WF-003` mid-month allocation split.
- `T-WF-004` over-allocation validation/override.
- `T-WF-005` weekly off/holiday/working override precedence.
- `T-WF-006` policy effective-date history.
- `T-WF-007` monthly accrual idempotency/pro-ration.
- `T-WF-008` balance reserve/consume/release.
- `T-WF-009` excess leave to LWP.
- `T-WF-010` closed-month effective-date protection.

### 4.3 Attendance

- `T-ATT-001` duplicate check-in idempotency.
- `T-ATT-002` open-session rejection.
- `T-ATT-003` multiple sessions/breaks.
- `T-ATT-004` 540/539/270/269-minute boundaries.
- `T-ATT-005` half leave/LWP composition.
- `T-ATT-006` missing checkout no synthesis.
- `T-ATT-007` overnight shift attribution.
- `T-ATT-008` holiday/off-day worked.
- `T-ATT-009` regularization approve/modify/reject.
- `T-ATT-010` admin correction dual control.
- `T-ATT-011` source conflict.
- `T-ATT-012` monthly close blockers/snapshot.
- `T-ATT-013` post-close discrepancy/reopen.

### 4.4 greytHR

- `T-GHR-001` token/connection success/failure/rotation.
- `T-GHR-002` capability matrix prevents unsupported authority mode.
- `T-GHR-003` employee mapping conflict.
- `T-GHR-004` page/checkpoint retry.
- `T-GHR-005` duplicate payload idempotency.
- `T-GHR-006` schema change quarantine.
- `T-GHR-007` stale/outage behavior.
- `T-GHR-008` source switch effective date.
- `T-GHR-009` unexpected payroll fields discarded.
- `T-GHR-010` optional swipe write-back acknowledgement/failure if enabled.

### 4.5 Planning/Linear

- `T-PLAN-001` completeness gate.
- `T-PLAN-002` approval quorum/checksum.
- `T-PLAN-003` frozen edit/revision/diff.
- `T-LIN-001` URL/identifier resolve and duplicate link.
- `T-LIN-002` OAuth state/PKCE/refresh/revoke.
- `T-LIN-003` GraphQL partial errors/pagination.
- `T-LIN-004` webhook valid/invalid/replay/duplicate.
- `T-LIN-005` asynchronous 200 and queue processing.
- `T-LIN-006` state mapping/custom state.
- `T-LIN-007` snapshot immutability/current diff.
- `T-LIN-008` reconciliation after missed webhook.
- `T-LIN-009` deleted/inaccessible issue.
- `T-LIN-010` Done does not certify.

### 4.6 Delivery/certification

- `T-DEL-001` submission completeness/lock.
- `T-DEL-002` evidence scan/block.
- `T-CERT-001` assigned product owner authorization.
- `T-CERT-002` comments for non-accept.
- `T-CERT-003` clarification additive version.
- `T-CERT-004` partial/carry-forward lineage.
- `T-CERT-005` monthly summary determinism.
- `T-CERT-006` timeout escalation no auto-approval.

### 4.7 Email/confirmation

- `T-MSG-001` recipient/CC completeness.
- `T-MSG-002` outbox idempotency/retry/bounce.
- `T-CONF-001` secure token expiry/single-use/version binding.
- `T-CONF-002` unauthorized forwarded link.
- `T-CONF-003` valid email reply/thread/sender.
- `T-CONF-004` spoofed/unrecognized/auto-reply.
- `T-CONF-005` ambiguous parser manual review.
- `T-CONF-006` quorum/conflicting decisions.
- `T-CONF-007` correction creates new request/version.
- `T-CONF-008` manual historical evidence/second review.

### 4.8 Package/invoice/procurement

- `T-PKG-001` complete/blocked readiness rules.
- `T-PKG-002` deterministic manifest/hash.
- `T-PKG-003` supersession after source change.
- `T-PKG-004` signed URL/access audit.
- `T-INV-001` invoice uniqueness/correction lineage.
- `T-INV-002` no salary/markup schema/UI/API.
- `T-PROC-001` review/change/hold/reject.
- `T-PROC-002` exception accepted remains disclosed.
- `T-PAY-001` payment state/history does not alter evidence.

### 4.9 Migration/report/security

- `T-MIG-001` file/header/field/reference validation.
- `T-MIG-002` identical/conflicting duplicates.
- `T-MIG-003` partial/resume/reprocess.
- `T-MIG-004` represented vs recorded dates.
- `T-MIG-005` rollback/reopen semantics.
- `T-REP-001` metric formulas/filter/export metadata.
- `T-REP-002` PII masking/CSV injection.
- `T-SEC-001` file malware/MIME/filename/XSS.
- `T-SEC-002` secret/log redaction.
- `T-SEC-003` audit immutability/integrity.
- `T-DR-001` backup restore and package hash validation.

---

## 5. Non-functional requirements

### 5.1 Scale

Initial configured size: 26 employees, one principal engagement, several projects. Architecture must support without redesign:

- 10,000 employees across organizations;
- 500 engagements;
- 1 million attendance events/month;
- 100,000 linked Linear issues;
- 10,000 monthly evidence packages/year;
- concurrent imports/exports/jobs with tenant isolation.

Scale targets are engineering capacity goals, not licensing commitments.

### 5.2 Performance

- Common dashboard/list initial response p95 ≤2.5 seconds under normal indexed load.
- Check-in/out server acknowledgement p95 ≤1.5 seconds excluding network, with durable write.
- Standard mutations p95 ≤2 seconds excluding provider/file async work.
- Search/filter p95 ≤2 seconds for normal scope.
- Webhook endpoint durable acknowledgment <5 seconds; target p95 <1 second.
- Heavy import/export/package generation asynchronous with progress.
- No page triggers N+1 external Linear/greytHR calls; use stored normalized state.

### 5.3 Availability/reliability

- Core application monthly target 99.9% excluding planned maintenance.
- No acknowledged punch/approval/confirmation lost.
- Exactly-once business effect through idempotent at-least-once processing.
- Job retries bounded; dead-letter/replay visible.
- Graceful degradation during provider outage.
- Database constraints protect invariants under concurrency.

### 5.4 Data recovery

Initial target pending platform validation:

- RPO ≤15 minutes for transactional data;
- RTO ≤4 hours for core application;
- evidence files/package restore verified;
- quarterly restore drill.

Final values require infrastructure approval and are recorded in PRD 20.

### 5.5 Security/privacy

All PRD 14 controls. Zero critical/high unresolved vulnerabilities at release; medium findings require risk acceptance/plan. No anonymous business access.

### 5.6 Accessibility/usability

- WCAG 2.1 AA target.
- Critical employee flows usable on current major mobile browser sizes.
- Governance screens responsive on desktop/tablet.
- Keyboard and screen-reader validation for critical forms/dialogs.
- User-facing errors include action and correlation ID.

### 5.7 Compatibility

- Current stable Chrome/Edge/Safari/Firefox; Android Chrome and iOS Safari for employee flows.
- Timezone/date tests; UTF-8 and names/attachments with Indian-language characters.
- Graceful behavior when browser storage/cookies constrained according to auth requirements.

### 5.8 Maintainability

- TypeScript strict mode and generated database types.
- Domain services, not page-level scattered queries.
- Migrations/version-controlled policies/functions/seeds.
- Automated lint/type/unit/integration/E2E/security checks.
- API/event/template/import schema versions.
- ADRs for material decisions.

---

## 6. Performance/load scenarios

- all 26 employees check in within a five-minute window;
- 10,000-employee future burst;
- nightly greytHR attendance reconciliation;
- Linear webhook burst plus reconciliation;
- large historical attendance import;
- month-end package generation for many engagements;
- report/export while transactional activity continues;
- repeated duplicate/replayed webhook/idempotency traffic;
- soak test scheduled jobs/outbox for 24+ hours.

Validate database query plans/indexes and queue lag.

---

## 7. Rollout plan

### Stage 0 — Engineering baseline

- backup/tag current prototype;
- local/staging Supabase;
- feature flags;
- auth/RLS migration first;
- synthetic data/tests.

### Stage 1 — Internal pilot

- configure ArrowFoundry/Reliance engagement and a small employee group;
- internal attendance source or read-only greytHR comparison;
- one monthly plan/certification/confirmation in staging;
- no production invoice reliance.

### Stage 2 — Parallel workforce run

- all 26 employees;
- compare internal/greytHR or old process for agreed period;
- resolve calendar/mapping/threshold issues;
- train employees/managers.

### Stage 3 — Historical backfill

- June onward in controlled waves;
- reconciliation and retro approvals/confirmations;
- package review with Procurement.

### Stage 4 — Production invoice evidence

- Procurement approves package format/process;
- first live month package submitted;
- enhanced monitoring/support;
- legacy spreadsheet/email process kept as contingency for one cycle, not independent source of truth.

### Stage 5 — Stabilize and expand

- post-implementation review;
- close gaps and tune SLAs;
- onboard additional projects/teams/vendors through configuration.

---

## 8. Cutover and rollback

### Entry criteria

- security/RLS tests pass;
- data migration rehearsal passes;
- contact/approval matrices validated;
- greytHR/Linear/email capabilities and credentials tested;
- Procurement accepts evidence format;
- user training completed;
- backup/rollback ready.

### Rollback triggers

- cross-tenant/security incident;
- evidence integrity/checksum failure;
- systemic attendance loss/miscalculation;
- inability to generate/verify package;
- unrecoverable migration corruption.

### Rollback actions

- disable affected feature flag/integration;
- preserve all new data/events;
- revert application deployment, not destructive data rollback;
- use documented contingency export/process;
- reconcile before resuming.

---

## 9. Operational runbooks

Required before go-live:

1. User access/SSO/invite failure.
2. Employee unable to check in/out.
3. Attendance calculation/source conflict.
4. Leave accrual/reconciliation failure.
5. greytHR auth/sync/schema outage.
6. Linear OAuth revoke/webhook outage/reconciliation.
7. Email send/bounce/reply ingestion failure.
8. Confirmation spoof/ambiguity review.
9. Import failure/resume/rollback.
10. Package generation/hash mismatch.
11. Month reopen and downstream invalidation.
12. Invoice/Procurement change request.
13. Backup restore/disaster recovery.
14. Security incident and evidence preservation.
15. Data correction/privacy request.

Each runbook includes detection, owner, diagnostics, safe actions, escalation, communications and closure evidence.

---

## 10. Monitoring/SLOs

Monitor:

- authentication/RLS denials and anomalies;
- check-in/out success/latency/duplicates;
- unresolved attendance and close readiness;
- job/outbox/dead-letter depth/age;
- greytHR/Linear/email freshness/failures;
- confirmation pending/invalid;
- package generation duration/failures/hash mismatches;
- invoice/Procurement aging;
- storage scan/backup health;
- API/page latency/error rates.

Define alert severity/owner and dashboard links. Alerts must be actionable and deduplicated.

---

## 11. Training and support

Role-specific guides/scripts:

- employee attendance/leave/regularization;
- vendor HR/admin roster/calendar/balance/close;
- vendor delivery submission;
- product owner planning/certification/confirmation;
- Procurement review/package/exception;
- integration admin health/replay;
- governance reopen/historical migration.

Provide in-app contextual help and a support escalation path; do not rely on undocumented tribal knowledge.

---

## 12. Release definition of done

- All P0/P1 mandatory tests pass.
- Security/RLS and evidence-integrity gates pass with no critical exception.
- Production migration/rollback reviewed.
- Provider capability certifications completed or internal fallbacks enabled.
- Historical templates and sample dry-run validated.
- Procurement accepts package contents and confirmation mechanism.
- Monitoring/runbooks/on-call ownership active.
- Documentation/ADRs/schema/types/tests committed.
- No known path creates a fake approval or mutates closed evidence in place.

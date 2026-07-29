# Consolidated Pending Work

This is the single cross-feature queue for work that is not yet closed. It
separates local product development from verification/review and from evidence
that can only be produced in an approved external environment. Detailed
feature task and test catalogs remain authoritative for implementation
requirements; this file answers what is still pending now.

**Last reconciled:** 2026-07-29 (F01–F03 integrated implementation)
**Committed base under active work:** `eda3eb8`
**Current implementation scope:** finish the serialized V1–V38 focused regression,
reconcile the F01–F04 ledgers, and create the requested local-only commit.
**Production release state:** `NO-GO / ACTION_REQUIRED`; local synthetic or
recorded adapters cannot satisfy production provider, legal, identity,
capacity, recovery or human approval gates.

## Current execution point

| Work item | State | Completed evidence | Still required |
|---|---|---|---|
| F01 core administration | `LOCAL PRODUCT COMPLETE` | V34, typed Java APIs, React administration flows, approval engine, F04 governed-reopen bridge and permanent E2E catalog are implemented. Focused PostgreSQL verification passes 45/45. | Complete repository regression, ledger reconciliation and local commit; production identity remains external. |
| F02 workforce/attendance | `LOCAL PRODUCT COMPLETE` | V35 plus V37 implement aliases, allocations, calendars/leave governance, breaks, versioned shift policies, overnight/split-session rules, exact roster readiness and immutable roster snapshots with manager React flows. Focused PostgreSQL passes administration 4/4 and attendance 22/22; browser passes 8/8. | Real greytHR tenant/cutover, production IdP and controlled staging/load acceptance remain external. |
| F03 delivery/Linear | `LOCAL PRODUCT COMPLETE` | V36 plus V38 implement replay/reconciliation operations, revision comparison, delegated approval lineage/quorum de-duplication and bounded cursor reconciliation with checkpoint/retry/partial-error evidence. Focused PostgreSQL passes 19/19; browser passes 9/9. | Exhaustive perimeter/failure-injection expansion remains a test-hardening item. Live Linear/mail acceptance remains external. |
| F04 certification/confirmation | `LOCAL PRODUCT COMPLETE` | V11–V13 vertical plus cross-month certification/confirmation inboxes, operations health and exact-version reasoned replay UI are implemented. The new PostgreSQL inbox/operations case passes 1/1; typecheck and focused Vitest 6/6 pass. | All remaining provider/deployment gates are external. |
| F07 hardening/go-live | `LOCAL ENGINEERING AND COMMIT EVIDENCE COMPLETE` | Product and supply reviews closed; Maven R3/R4 290/290, browser 274/274, system lanes 7/7 + 4/4 + 6/6, exact supply zero findings, and clean-commit migration/supply evidence are bound to `eda3eb8`. | External production identity/provider/legal/capacity/DR/UAT approvals only. |

## Consolidated local execution backlog

This is the ordered, stable-ID list of product work still executable in this
repository. External approvals and production evidence are listed separately
under each feature below.

| ID | Feature | Pending local subtask | Required closure evidence |
|---|---|---|---|
| LOCAL-FINAL-REGRESSION | F01–F03 | Complete the active full Maven/PostgreSQL and final combined browser regression, then bind the evidence to a local commit. | Zero failing local lanes and commit hash |
| LOCAL-F03-PERIMETER | F03 | Complete exhaustive least-privilege/OpenAPI/Swagger and failure-injection coverage beyond the implemented scoped integration suites. | Role matrix, OpenAPI access and failure-injection cases |
| LOCAL-FINAL-001 | Cross-feature | Run final complete regression after all feature commits and update root status, pending, regression and architecture indexes. | Clean worktree, cross-linked evidence and no remote push |

## Open F07 implementation/review findings

| ID | Priority | State | Required closure |
|---|---|---|---|
| PEND-F07-001 | P0 | `IMPLEMENTED / FOCUSED TEST PASS` | Provider probe/attestation authority is immutable and tenant-bound; simulated adapters are non-production only. |
| PEND-F07-002 | P0 | `IMPLEMENTED / FOCUSED TEST PASS` | Provider-fact replay under a different command key has no duplicate business effect. |
| PEND-F07-003 | P0 | `IMPLEMENTED / FOCUSED TEST PASS` | Pre-cutover ingestion cannot mutate authoritative attendance/leave state. |
| PEND-F07-004 | P1 | `IMPLEMENTED / UPGRADE TEST PASS` | V25 upgrade remediates invalid active-certification references with audit evidence. |
| PEND-F07-005 | P1 | `IMPLEMENTED / FOCUSED TEST PASS` | Probe evidence and Java/database commercial/privacy validation are aligned. |
| PEND-F07-006 | P1 | `IMPLEMENTED / FOCUSED TEST PASS` | Linear recovery uses a tenant/actor-bound immutable command ledger and terminal guard. |
| PEND-F07-007 | P1 | `IMPLEMENTED / FOCUSED TEST PASS` | Delivery commitment dispatch has a dedicated least-privilege worker profile and fail-closed production transport. |
| PEND-F07-008 | P1 | `IMPLEMENTED / SCHEMA TEST PASS` | V28–V33 functions use fixed search paths, revoked PUBLIC execution and minimum grants. |
| PEND-F07-009 | P1 | `IMPLEMENTED / HARNESS TEST PASS` | Release mappings bind to the exact greytHR, commitment, migration and recovery cases. |
| PEND-F07-010 | P2 accepted | `IMPLEMENTED / FOCUSED TEST PASS` | Regularization lineage is tenant/date/employee constrained and maximum-length idempotency keys are safe. |
| PEND-F07-011 | P2 accepted | `IMPLEMENTED / FOCUSED TEST PASS` | Freshness/degradation metrics and trusted/untrusted proxy behavior are covered. |

## Pending by feature

### F00 — Foundation

- `LOCAL COMPLETE`: Java/PostgreSQL architecture decision, baseline/rollback
  reference, safe configuration defaults, feature flags, validation commands,
  SDLC harness and repository documentation are committed.
- `EXTERNAL — ACTION_REQUIRED`: encrypted staging source/object backup,
  deployment references, staging legacy rollback smoke and deployed Java
  health/auth smoke. These require a user-selected controlled environment and
  may not be fabricated locally.

### F01 — Identity and core administration

- `LOCAL PRODUCT COMPLETE`: V34 and the typed `/api/v1/core/**` surface close
  engagement/configuration/contact/policy/delegation administration,
  stable-identity policy revisions, server-derived governed-reopen requests,
  immutable eligibility snapshots, idempotent actions, delegated
  self-approval/exact-project/authority-identity quorum enforcement,
  authoritative atomic reopen dispatch, guarded month transitions/history,
  canonical roles, runtime/public database privileges, effective session
  permissions, active-scope navigation and executable OpenAPI/frontend
  compatibility.
- `LOCAL VERIFICATION COMPLETE`: 73 backend unit tests plus 17 focused F01
  PostgreSQL integration tests pass; the focused F01 verify independently
  migrated V1–V34 and V1000–V1005 on PostgreSQL 18.4 with 17/17 passing.
- `EXTERNAL — ACTION_REQUIRED`: approved OIDC tenant/BFF login/logout,
  provisioning/invitation process, claim mapping/MFA/key rotation and deployed
  identity acceptance. Local signed JWT/JWKS tests do not close this.

### F02 — Workforce, attendance and greytHR

- `LOCAL PRODUCT VERTICAL COMPLETE`: V35 provides governed employee aliases,
  bounded deliverable allocations, effective calendars/holidays and leave
  policies, accrual/grant/adjustment commands, exact-version leave decisions,
  regularization review, CSV import controls, and immutable attendance-break
  lineage with overnight-session guards. Manager/admin and self-service UI
  flows are implemented.
- `LOCAL VERIFICATION COMPLETE`: WorkforceAdministrationIT passes 3/3,
  WorkforceAttendanceIT passes 22/22 and the complete workforce browser
  project passes 8/8.
- `LOCAL ENHANCEMENT`: a generalized configurable roster/shift-template rule
  DSL and exhaustive roster-completeness policies remain beyond the effective
  calendar, overnight-session and break controls now implemented.
- `LOCAL F07 SYSTEM PASS`: provider-neutral greytHR
  discovery/probe/sync/reconciliation/cutover, source authority, corrections,
  freshness and exact E2E-02.
- `EXTERNAL — ACTION_REQUIRED`: real greytHR tenant/scopes/credentials,
  26-employee parallel run, reconciliation owner approval and authority
  cutover.

### F03 — Delivery planning and Linear

- `LOCAL PRODUCT VERTICAL COMPLETE`: plan/freeze/revision/baseline, signed
  webhooks, immutable current/snapshot evidence and business-status guardrails
  are implemented. V36 adds revision-aware checksums, server-derived revision
  comparison, scoped reconciliation controls, immutable commitment
  dead-letter replay and integration-health administration.
- `LOCAL VERIFICATION COMPLETE`: DeliveryLinearIT passes 17/17 and the complete
  delivery browser project passes 9/9.
- `LOCAL F07 SYSTEM PASS`: provider recovery command, last-known/stale behavior,
  commitment dispatch worker, retry/lease/dead-letter evidence and E2E-03/10.
- `LOCAL ENHANCEMENT`: delivery-specific use of the shared delegation model,
  scheduled live-provider delta pagination and exhaustive role/OpenAPI/failure
  injection matrices remain. Manual reconciliation/replay and their scoped
  audit trail are implemented.
- `EXTERNAL — ACTION_REQUIRED`: real Linear OAuth/PKCE app, workspace/scopes,
  webhook registration/secret rotation and live email provider/sender/mailbox.

### F04 — Certification and confirmation

- `LOCAL VERTICAL COMPLETE`: Java/PostgreSQL V11–V13, immutable
  submission/certification/confirmation/reopen lineage, provider-neutral
  workers, secure action/reply controls, F05 handoff and React flows are
  committed.
- `PENDING LEDGER RECONCILIATION`: the F04 task checklist predates its reviewed
  fixes and still shows local tasks unchecked; verify each against code/tests
  and mark only evidence-backed completion.
- `LOCAL VERIFICATION COMPLETE`: E2E-03/04/05 pass through
  Vite→Spring Security→Flyway V1–V33→PostgreSQL after F07 worker/role changes.
- `EXTERNAL — ACTION_REQUIRED`: approved sender/provider and controlled
  mailbox, storage/scanner, SSO/OTP/step-up policy, recipient/quorum/delegation
  policy and live acceptance.

### F05 — Evidence, invoice and reporting

- `LOCAL COMPLETE`: immutable package/invoice/procurement/payment/report/export
  vertical and its local system suite are committed.
- `F07 REGRESSION PASS`: finance system is 4/4 (including E2E-06/E2E-09), the
  final browser matrix is 274/274 and the local capacity targets pass.
- `EXTERNAL — ACTION_REQUIRED`: production object storage/scanner/renderer,
  AP/ERP/Procurement acceptance, deployment grants, retention approval and
  production capacity evidence.

### F06 — Historical migration

- `LOCAL COMPLETE`: all 26 local tasks, 14 templates, domain adapters,
  reconciliation/SoD/compensation/retro flows, UI, API and 6-case real local
  system suite are committed.
- `F07 REGRESSION PASS`: migration system is 6/6 (including E2E-08) and the
  final browser matrix is 274/274. Commit-bound current/prior compatibility and
  rollback evidence remains in the final release lane.
- `EXTERNAL — ACTION_REQUIRED`: source-owner mappings/sign-off, approved
  production storage/scanner, controlled 100k-row capacity window, backup/
  restore checkpoint and masked production rehearsal.

### F07 — Hardening and go-live

- `LOCAL ENGINEERING COMPLETE / COMMIT-BOUND EVIDENCE COMPLETE`:
  PEND-F07-001 through PEND-F07-011 are
  implemented with focused evidence; F07/finance/migration systems pass 7/7,
  4/4 and 6/6; capacity passes 73 + 2; browser passes 274/274. Exact
  compatibility, migration-rehearsal and supply evidence is bound to clean
  commit `eda3eb8`.
- `EXTERNAL — ACTION_REQUIRED`: named release/security/data/legal/operations
  approvers; production OIDC/secrets/providers; legal/privacy/retention
  approval; scanner/storage/telemetry/on-call; production-like capacity and
  24-hour soak; accessibility/UAT/training; backup/PITR/DR; greytHR parallel
  run; pilot/cutover approval. Until supplied, the release gate must remain
  `NO-GO`.

## Required final verification sequence

1. `COMPLETE`: focused Java/PostgreSQL gate — 73 unit + 45 integration.
2. `COMPLETE`: ordered F07 E2E-01/02/03/04/05/07/10 — 7/7.
3. `COMPLETE`: definitive Maven R3 passes 73 unit + 217 integration (290/290).
   Preserved R2 passed 215/217 IT; the dedicated worker test database fixed its
   shared-state count.
4. `COMPLETE`: frontend/static gates.
5. `COMPLETE`: full browser matrix — 274/274.
6. `COMPLETE`: finance 4/4 and migration 6/6 local-system lanes.
7. `COMPLETE LOCALLY`: capacity and F07 harness/schema gates; exact
   commit-bound supply-chain/restore/release execution remains.
8. `COMPLETE`: final Terra review closed with no P0–P3 finding.
9. `COMPLETE FOR CURRENT EVIDENCE`: feature/status/regression/root README
   documentation reconciliation.
10. `COMPLETE FOR F07`: local implementation and evidence commits created;
    clean-commit migration/supply binding completed. No remote push.

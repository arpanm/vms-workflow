# Consolidated Pending Work

This is the single cross-feature queue for work that is not yet closed. It
separates local product development from verification/review and from evidence
that can only be produced in an approved external environment. Detailed
feature task and test catalogs remain authoritative for implementation
requirements; this file answers what is still pending now.

**Last reconciled:** 2026-07-29 (final local F07 evidence)
**Committed base under active work:** `cc5049e`
**Current implementation scope:** local commit and exact
commit-bound release evidence after definitive complete Maven R3 passed.
**Production release state:** `NO-GO / ACTION_REQUIRED`; local synthetic or
recorded adapters cannot satisfy production provider, legal, identity,
capacity, recovery or human approval gates.

## Current execution point

| Work item | State | Completed evidence | Still required |
|---|---|---|---|
| F07 real-system workforce and greytHR journeys | `PASSED LOCALLY` | Final V1–V33 serialized system run passed E2E-01, E2E-02 and E2E-03 through Vite, Spring Security, Flyway and PostgreSQL. | Preserve in the final evidence/status reconciliation; production greytHR acceptance remains external. |
| F07 delivery and confirmation journeys | `PASSED LOCALLY` | Final V1–V33 system run passed E2E-03, E2E-04 and E2E-05, including authorization, commitment dispatch and confirmation-to-certification handoff. | Preserve in final evidence/status reconciliation; live provider/mailbox acceptance remains external. |
| F07 correction/outage/recovery journeys | `PASSED LOCALLY` | Final V1–V33 system run passed E2E-07 and E2E-10 with public-API, lineage, canary-abort and recovery assertions. | Preserve in final evidence/status reconciliation; production outage/tabletop acceptance remains external. |
| F07 independent review | `CLOSED` | Final Terra review closed with no P0–P3 finding. | Preserve the review in commit-bound evidence. |
| F07 complete regression | `PASSED LOCALLY` | Frontend typecheck/lint/Vitest/build/diff passes (24 files/92 tests; 0 lint errors/6 warnings); focused backend 73+45; capacity 73+2; systems 7/7, 4/4, 6/6; browser 274/274; Maven R3 290/290. | Preserve R2/R3, then exact commit-bound compatibility/supply-chain/restore/rollback evidence. |
| F07 documentation and Git | `DOCUMENTATION RECONCILED; COMMIT PENDING` | Task/test/API/UI/architecture/review/fix/status/regression documents carry the V1–V33 evidence and root README cross-links. | Root must append the final reviewer result, create the requested local commit, run commit-bound evidence, and never push. |

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

- `VERIFY/CLOSE LOCALLY`: re-audit later migrations/services against the older
  F01 checklist because F03–F07 added contacts, approval, month lifecycle,
  database roles, typed errors and OpenAPI behavior after the F01 commit.
- `PENDING LOCAL PRODUCT`: finish any audit-confirmed gaps in engagement/contact
  administration, approval/delegation administration, active
  organization/engagement/month selector, permission-aware navigation and
  executable OpenAPI/frontend type compatibility.
- `EXTERNAL — ACTION_REQUIRED`: approved OIDC tenant/BFF login/logout,
  provisioning/invitation process, claim mapping/MFA/key rotation and deployed
  identity acceptance. Local signed JWT/JWKS tests do not close this.

### F02 — Workforce, attendance and greytHR

- `LOCAL F07 SYSTEM PASS`: provider-neutral greytHR
  discovery/probe/sync/reconciliation/cutover, source authority, corrections,
  freshness and exact E2E-02.
- `PENDING LOCAL PRODUCT`: employee aliases/deliverable allocations; governed
  leave accrual/grant/adjustment/approval/cancellation and excess-to-LWP
  administration; calendar/policy manager UI; regularization review/correction
  UI; break/overnight-shift rules; workforce CSV import validation; explicit
  manager/admin loading/empty/stale/conflict/denied flows.
- `PENDING VERIFICATION`: complete `T-WF`, `T-ATT`, `T-GHR` catalog audit and
  extend the permanent regression catalog for every implemented subflow.
- `EXTERNAL — ACTION_REQUIRED`: real greytHR tenant/scopes/credentials,
  26-employee parallel run, reconciliation owner approval and authority
  cutover.

### F03 — Delivery planning and Linear

- `PARTIAL LOCAL PRODUCT`: plan/freeze/revision/baseline, signed webhooks,
  immutable current/snapshot evidence and business-status guardrails are
  implemented.
- `LOCAL F07 SYSTEM PASS`: provider recovery command, last-known/stale behavior,
  commitment dispatch worker, retry/lease/dead-letter evidence and E2E-03/10.
- `PENDING LOCAL PRODUCT`: audit and close data-driven quorum/SOD edge cases,
  dependency cycles/allocation coverage, provider adapter/error/pagination
  boundaries, scheduled reconciliation/manual refresh/replay administration,
  plan builder/revision compare/integration-health UI and exhaustive
  least-privilege/OpenAPI tests.
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

- `LOCAL ENGINEERING COMPLETE / COMMIT-BOUND EVIDENCE PENDING`:
  PEND-F07-001 through PEND-F07-011 are
  implemented with focused evidence; F07/finance/migration systems pass 7/7,
  4/4 and 6/6; capacity passes 73 + 2; browser passes 274/274. Finish the
  local implementation commit and commit-bound
  compatibility/supply-chain/restore/rollback evidence.
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
10. `PENDING`: local Git commit only, then commit-bound migration
    compatibility, rollback/post-deploy evidence and a local evidence commit.
    No remote push.

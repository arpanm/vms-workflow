# Consolidated Pending Work

This is the single cross-feature queue for work that is not yet closed. It
separates local product development from verification/review and from evidence
that can only be produced in an approved external environment. Detailed
feature task and test catalogs remain authoritative for implementation
requirements; this file answers what is still pending now.

**Last reconciled:** 2026-07-30 (V44 local-start compatibility repair)
**Implementation commits:** `6f3e9c9` (F01–F07) and `2709447`
(repeatable local database startup/V44); local only, not pushed
**Current implementation scope:** V44 is the latest production migration; V42
is the repeated-reopen invariant, V43 is the durable migration queue and V44
hardens released V39/V40 functions without changing their checksums. Local
`dev:all` startup is restored against a preserved-data-compatible fixture
database. Local implementation is complete. Aggregate
failures and exact recovery results are both preserved below and in the
regression catalog.
**Production release state:** `NO-GO / ACTION_REQUIRED`; local synthetic or
recorded adapters cannot satisfy production provider, legal, identity,
capacity, recovery or human approval gates.

## Current execution point

| Work item | State | Completed evidence | Still required |
|---|---|---|---|
| F01 core administration | `LOCAL PRODUCT COMPLETE` | V34, typed Java APIs, React administration flows, approval engine, F04 governed-reopen bridge and permanent E2E catalog are implemented. Focused PostgreSQL verification passes 45/45; current cross-feature aggregate and recovery evidence is preserved below. | Local commit; production identity remains external. |
| F02 workforce/attendance | `LOCAL PRODUCT COMPLETE` | V35 plus V37 implement aliases, allocations, calendars/leave governance, breaks, versioned shift policies, overnight/split-session rules, exact roster readiness and immutable roster snapshots with manager React flows. Focused PostgreSQL passes administration 4/4 and attendance 22/22; browser passes 8/8. | Real greytHR tenant/cutover, production IdP and controlled staging/load acceptance remain external. |
| F03 delivery/Linear | `LOCAL PRODUCT COMPLETE` | V36/V38/V39 implement replay/reconciliation operations, revision comparison, delegated approval lineage/quorum de-duplication, bounded cursor checkpoints and least-privilege/OpenAPI/failure-injection artifacts. Focused PostgreSQL passes 19/19; browser passes 9/9 and the final three-project locator recovery passes 3/3. | Live Linear/mail acceptance remains external. |
| F04 certification/confirmation | `LOCAL PRODUCT COMPLETE` | V11–V13 vertical plus cross-month certification/confirmation inboxes, operations health and exact-version reasoned replay UI are implemented. The new PostgreSQL inbox/operations case passes 1/1; typecheck and focused Vitest 6/6 pass. | All remaining provider/deployment gates are external. |
| F05 evidence/invoice/reporting | `LOCAL FOCUSED EVIDENCE COMPLETE` | Natural scanner-readiness 1/1, committed package concurrency 2/2, accessibility 3/3, finance recovery 1/1 and isolated system 4/4 passed. | External G4 provider/deployment/Procurement/scale gates. |
| F06 historical migration | `LOCAL FOCUSED EVIDENCE COMPLETE` | V41/V43 implementation, exact migration recovery 1/1 and migration system 6/6 pass. | External cutover/source-owner/storage/scale/DR gates. |
| F07 hardening/go-live | `LOCAL IMPLEMENTATION COMMITTED / RELEASE NO-GO` | V42/V43 product migrations plus additive V44 checksum-safe function hardening; `dev:all` applied 50 migrations and reached Spring/Vite readiness twice, including retained-history validation. Frontend 120/120, system 7/7, self-test 9/9 and schema/operations/SDLC gates pass. Aggregate Maven 340 ended 2 failures+1 error and browser passed 287/292; recovery slices pass but are not clean aggregate reruns. Local implementation commits are `6f3e9c9` and `2709447`; nothing was pushed. | Real 24-hour soak (F07-T057), current recovery-boundary/DR drill (F07-T066/T067), artifact/provenance production (F07-T070), then external approvals. |

## Consolidated local execution backlog

This is the ordered, stable-ID list of product work still executable in this
repository. External approvals and production evidence are listed separately
under each feature below.

| ID | Feature | Pending local subtask | Required closure evidence |
|---|---|---|---|
| LOCAL-FINAL-REGRESSION | Cross-feature | Obtain clean aggregate Maven and browser reruns if required; preserve the failed full rows and passing exact recovery slices. | Clean aggregate evidence without replacing historical failures |
| F07-T057 | F07 | Run the real 24-hour production-like soak. | Timestamped 24-hour result and owner approval |
| F07-T066/T067 | F07 | Execute the current recovery-boundary and disaster-recovery drill. | RTO/RPO, restore and boundary evidence |
| F07-T070 | F07 | Produce commit-bound release artifacts and provenance. | Artifact digests, attestations and exact commit |

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
- `LOCAL COMPLETE`: configurable roster/shift-policy checks now reject missing
  weekday templates, ineligible employment windows, inactive/disabled
  employees, non-effective policies and effective allocations above 100%.
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
- `LOCAL COMPLETE`: delivery uses the shared delegation model, scheduled
  compound-cursor provider pagination/checkpoints and scoped
  least-privilege/OpenAPI/failure-injection artifacts. Manual
  reconciliation/replay and its audit trail remain available to operators.
- `EXTERNAL — ACTION_REQUIRED`: real Linear OAuth/PKCE app, workspace/scopes,
  webhook registration/secret rotation and live email provider/sender/mailbox.

### F04 — Certification and confirmation

- `LOCAL VERTICAL COMPLETE`: Java/PostgreSQL V11–V13, immutable
  submission/certification/confirmation/reopen lineage, provider-neutral
  workers, secure action/reply controls, F05 handoff and React flows are
  committed.
- `LOCAL LEDGER RECONCILED`: the F04 checklist is aligned with the implemented
  provider-neutral paths, V40 scan transitions and V42 repeated governed
  reopen lifecycle; external acceptance remains explicitly unchecked.
- `LOCAL VERIFICATION COMPLETE`: E2E-03/04/05 pass through
  Vite→Spring Security→Flyway V1–V33→PostgreSQL after F07 worker/role changes.
- `EXTERNAL — ACTION_REQUIRED`: approved sender/provider and controlled
  mailbox, storage/scanner, SSO/OTP/step-up policy, recipient/quorum/delegation
  policy and live acceptance.

### F05 — Evidence, invoice and reporting

- `LOCAL COMPLETE`: immutable package/invoice/procurement/payment/report/export
  vertical and its local system suite are committed.
- `LOCAL FOCUSED PASS (2026-07-30)`: scanner-derived natural readiness 1/1,
  committed package concurrency 2/2, accessibility 3/3 and finance system
  4/4 (including E2E-06/E2E-09). Earlier full-suite counts remain historical.
- `EXTERNAL — ACTION_REQUIRED`: production object storage/scanner/renderer,
  AP/ERP/Procurement acceptance, deployment grants, retention approval and
  production capacity evidence.

### F06 — Historical migration

- `LOCAL COMPLETE / FOCUSED PASS`: V41/V43 retro outcomes, ordered month
  lifecycle, row/conflict UI, source declarations, stable codes, durable async
  processing, 100k boundary, CSV/XLSX, OpenAPI/accessibility and correction
  routing are implemented. The current system lane passes 6/6; the exact
  migration recovery selector and two accessibility recoveries pass.
- `EXTERNAL — ACTION_REQUIRED`: source-owner mappings/sign-off, approved
  production storage/scanner, controlled 100k-row capacity window, backup/
  restore checkpoint and masked production rehearsal.
- `LOCAL VERIFICATION LIMIT`: the complete Maven attempt and browser matrix are
  preserved as failed aggregate rows, followed by green exact recovery
  selectors. No clean aggregate rerun is claimed.

### F07 — Hardening and go-live

- `LOCAL IMPLEMENTATION COMPLETE / RELEASE NO-GO`: PEND-F07-001 through
  PEND-F07-011 are implemented. Current evidence includes F07 system 7/7,
  self-test 9/9, operations/migration/rollout/SDLC gates, frontend 120/120 and
  the documented aggregate-plus-recovery backend/browser results. T057,
  T066/T067 and T070 remain locally executable release evidence.
- `EXTERNAL — ACTION_REQUIRED`: named release/security/data/legal/operations
  approvers; production OIDC/secrets/providers; legal/privacy/retention
  approval; scanner/storage/telemetry/on-call; production-like capacity and
  24-hour soak; accessibility/UAT/training; backup/PITR/DR; greytHR parallel
  run; pilot/cutover approval. Until supplied, the release gate must remain
  `NO-GO`.

## Required final verification sequence

1. `COMPLETE`: frontend typecheck/build, lint 0 errors, Vitest 120/120.
2. `COMPLETE`: finance 4/4, migration 6/6 and ordered F07 7/7 system lanes.
3. `PRESERVED SPLIT EVIDENCE`: full Maven executed 340 tests with 2 failures
   and 1 error; exact Finance 1/1, Migration 1/1 and Capacity 2/2 recovery
   selectors pass. A clean 340/340 rerun is not claimed.
4. `PRESERVED SPLIT EVIDENCE`: full browser executed 292 tests with 287
   passing; the exact five failing combinations then pass 5/5. A clean
   292/292 rerun is not claimed.
5. `COMPLETE`: self-test 9/9 and operations, migration-schema, rollout-schema,
   SDLC and diff gates pass. The release-schema wrapper remains environment
   blocked by sandbox `EPERM`; its underlying gates pass.
6. `COMPLETE`: final Terra review found no P0 and its P1/P2 findings were
   fixed with affected recovery evidence.
7. `PENDING LOCAL EVIDENCE`: T057 24-hour soak, T066/T067 current DR drill and
   T070 commit-bound artifacts/provenance.
8. `IN PROGRESS`: final documentation reconciliation and requested local
   commit; no remote push.

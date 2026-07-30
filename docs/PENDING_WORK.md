# Consolidated Pending Work

This is the single cross-feature queue for work that is not yet closed. It
separates local product development from verification/review and from evidence
that can only be produced in an approved external environment. Detailed
feature task and test catalogs remain authoritative for implementation
requirements; this file answers what is still pending now.

**Last reconciled:** 2026-07-30 (final V45 F05–F07 completion pass)
**Implementation commits:** `6f3e9c9` (F01–F07) and `2709447`
(repeatable local database startup/V44); the V45 completion pass is local only
and not pushed
**Current implementation scope:** V45 is the latest production migration; V42
is the repeated-reopen invariant, V43 is the durable migration queue, V44
hardens released V39/V40 functions without changing their checksums and V45
governs finance-artifact retention/disposal. `dev:all` preserves
checksum-incompatible databases and automatically selects a deterministic
compatible database. Local product implementation is complete.
**Production release state:** `NO-GO / ACTION_REQUIRED`; local synthetic or
recorded adapters cannot satisfy production provider, legal, identity,
capacity, recovery or human approval gates.

## Current execution point

| Work item | State | Completed evidence | Still required |
|---|---|---|---|
| F01 core administration | `LOCAL PRODUCT COMPLETE` | V34, typed Java APIs, React administration flows, approval engine, F04 governed-reopen bridge and permanent E2E catalog are implemented. Focused PostgreSQL verification passes 45/45; current cross-feature aggregate evidence is preserved below. | No local product task; production identity remains external. |
| F02 workforce/attendance | `LOCAL PRODUCT COMPLETE` | V35 plus V37 implement aliases, allocations, calendars/leave governance, breaks, versioned shift policies, overnight/split-session rules, exact roster readiness and immutable roster snapshots with manager React flows. Focused PostgreSQL passes administration 4/4 and attendance 22/22; browser passes 8/8. | Real greytHR tenant/cutover, production IdP and controlled staging/load acceptance remain external. |
| F03 delivery/Linear | `LOCAL PRODUCT COMPLETE` | V36/V38/V39 implement replay/reconciliation operations, revision comparison, delegated approval lineage/quorum de-duplication, bounded cursor checkpoints and least-privilege/OpenAPI/failure-injection artifacts. Focused PostgreSQL passes 19/19; browser passes 9/9 and the final three-project locator recovery passes 3/3. | Live Linear/mail acceptance remains external. |
| F04 certification/confirmation | `LOCAL PRODUCT COMPLETE` | V11–V13 vertical plus cross-month certification/confirmation inboxes, operations health and exact-version reasoned replay UI are implemented. The new PostgreSQL inbox/operations case passes 1/1; typecheck and focused Vitest 6/6 pass. | All remaining provider/deployment gates are external. |
| F05 evidence/invoice/reporting | `LOCAL PRODUCT COMPLETE` | V45 governed retention/disposal; backend/shared 33/33, browser 7/7 and system 4/4 pass. | External provider/deployment/Procurement/scale gates. |
| F06 historical migration | `LOCAL PRODUCT COMPLETE` | Scan-before-parse quarantine, effective finalized roster reconciliation and stale-approval protection; backend 32/32, browser 8/8 and system 7/7 pass. | External cutover/source-owner/storage/scale/DR gates. |
| F07 hardening/go-live | `LOCAL IMPLEMENTATION COMPLETE / RELEASE NO-GO` | V42–V45 controls; checksum-compatible `dev:all`; frontend 120/120, Maven 347/347, browser 292/292, system 7/7, self-test 9/9 and SDLC pass. Nothing was pushed. | Real 24-hour soak (F07-T057), verified post-fix DR/reconciliation (F07-T066/T067), exact-clean-commit artifact execution (F07-T070), then external approvals. |

## Consolidated local execution backlog

This is the ordered, stable-ID list of product work still executable in this
repository. External approvals and production evidence are listed separately
under each feature below.

| ID | Feature | Pending local subtask | Required closure evidence |
|---|---|---|---|
| LOCAL-FINAL-REGRESSION | Cross-feature | Complete. Historical failed runs remain in the append-only regression ledger. | Maven 347/347 and browser 292/292 pass |
| F07-T057 | F07 | Run the real 24-hour production-like soak. | Timestamped 24-hour result and owner approval |
| F07-T066/T067 | F07 | Complete and verify the post-fix recovery-boundary/DR rehearsal. Two attempts failed closed on archive validation; the validator was fixed, but the third run was interrupted before reconciliation. | Successful restore, reconciliation and boundary evidence |
| F07-T070 | F07 | Execute the implemented deterministic artifact/provenance manifest on the final clean candidate commit. | Artifact digests, attestations and exact commit |

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
  the clean aggregate backend/browser results. T057, verified T066/T067 and
  exact-clean-commit T070 execution remain release evidence.
- `EXTERNAL — ACTION_REQUIRED`: named release/security/data/legal/operations
  approvers; production OIDC/secrets/providers; legal/privacy/retention
  approval; scanner/storage/telemetry/on-call; production-like capacity and
  24-hour soak; accessibility/UAT/training; backup/PITR/DR; greytHR parallel
  run; pilot/cutover approval. Until supplied, the release gate must remain
  `NO-GO`.

## Required final verification sequence

1. `COMPLETE`: frontend typecheck/build, lint 0 errors, Vitest 120/120.
2. `COMPLETE`: finance 4/4, migration 7/7 and ordered F07 7/7 system lanes.
3. `COMPLETE`: full Maven passes 74 unit + 273 integration = 347/347.
4. `COMPLETE`: full Chromium browser regression passes 292/292.
5. `COMPLETE`: self-test 9/9, SDLC and diff gates pass.
6. `COMPLETE`: final Terra review found no P0 and its P1/P2 findings were
   fixed with affected recovery evidence.
7. `PENDING EVIDENCE`: T057 24-hour soak, successful post-fix T066/T067
   restore/reconciliation and exact-clean-commit T070 execution. The DR and
   manifest implementations are present; evidence is not fabricated.
8. `COMPLETE AFTER THIS PASS`: final documentation reconciliation and one
   requested local commit; no remote push.

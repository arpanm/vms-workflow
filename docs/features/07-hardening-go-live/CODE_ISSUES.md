# F07 — Code Review Issues

## Disposition rules

- `RESOLVED_IN_CODE` means a concrete remediation exists and has focused
  coverage or an executable check.
- `RESOLVED_TEST_ISOLATION` means a failure was traced to shared test-database
  state and the dedicated-database correction passed the definitive full lane.
- `ACTION_REQUIRED` requires an external owner/environment and cannot be
  changed by a local synthetic test.

## Local findings

All P0/P1 findings raised by the backend and operations reviews have a
corresponding remediation in the current working tree. Focused backend,
capacity, system, static, browser and definitive complete Maven gates are
green. Final Terra review is closed with no P0–P3 finding; commit-bound
evidence remains open.

| IDs | State | Required closure evidence |
|---|---|---|
| F07-BE-001–009 | RESOLVED_IN_CODE / FOCUSED_PASS | Focused gate passes 73 unit + 45 integration; these preserved review issues did not fail the complete Maven run. |
| F07-BE-010 | RESOLVED_TEST_ISOLATION | R2 counted two provider effects in two worker cases because the new IT did not have a dedicated database. It now uses `vms_workflow_delivery_commitment_worker`; definitive Maven R3 passes 290/290. |
| F07-OPS-001–007 | RESOLVED_IN_CODE / LOCAL_HARNESS_PASS | Static, schema and harness gates pass. Exact commit-bound supply-chain/restore/release execution remains a release-evidence task, and production DR remains external. |
| F07-UI-001 | RESOLVED / VERIFIED_LOCALLY | Complete browser matrix passes 274/274 after preserved 268/274 and 7/7 rerun history. Manual representative-user accessibility remains external. |

## Product-wide and external blockers

These are intentionally not represented as local code defects:

- production OIDC/BFF, MFA/step-up, logout/revocation and service-account
  governance;
- approved secret manager, malware scanner, private object storage, email and
  provider credentials/callbacks;
- production metrics/logs/traces/paging and named on-call ownership;
- approved retention periods, legal-hold authority, privacy/legal notices and
  data-sharing recipients;
- production-like capacity/24-hour soak, backup/PITR/regional DR and approved
  RPO/RTO;
- named release/security/data/operations/support/rollback approvals, UAT,
  training, Procurement acceptance and support readiness.

They stay `ACTION_REQUIRED` in release evidence. No default value, sample
fixture or local green test closes them.

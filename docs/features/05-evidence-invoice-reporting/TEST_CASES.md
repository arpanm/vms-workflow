# F05 — Evidence, Invoice and Reporting Test Cases

- `T-PKG-001`: readiness identifies each mandatory evidence pillar and blocker.
- `T-PKG-002`: repeated generation is byte/canonical-hash deterministic for the same inputs.
- `T-PKG-003`: correction creates superseding package; prior download remains intact.
- `T-PKG-004`: package contains no prohibited payroll/rate/markup fields.
- `T-INV-001/002`: vendor upload/version uniqueness and readiness-bound submit.
- `T-PROC-001/002`: procurement review and explicit exception require scoped authority/reason/evidence.
- `T-PAY-001`: payment status changes append history and respect role scope.
- `T-REP-001/002`: dashboards/exports apply actor scope, filters, source/version labels and formula-injection protection.
- Storage tests: quarantined files never render/package; signed URLs expire and enforce organization/engagement scope.
- `E2E-06/07/09`: invoice readiness, package supersession/reopen and persona reporting.

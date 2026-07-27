# F05 — Security analysis consolidation

**Status:** Local controls implemented; final security/regression evidence and
external provider acceptance remain pending.

## Implemented defense-in-depth controls

- Server-derived subject, organization, engagement and capability checks;
  client roles/organization/actor input are not trusted.
- Private, scope-bound artifact storage; MIME/size/name validation; hash and
  scan gating; quarantine/unknown states deny package/export/download.
- Immutable evidence/invoice/readiness/payment/review lineage, transactionally
  journaled audit/outbox/idempotency, explicit shares with expiry/revoke and
  authenticated direct attachment downloads.
- Formula-safe CSV/XLSX output, output hash verification, redacted typed errors
  and finance mutation/export/download rate limits with security events.
- No salary, CTC, employee rates, markup, margin, payroll or employee-level
  invoice allocation in F05 contracts or calculations.

## Remaining controls to prove or finish

- Per-report/persona export field-mask parity and authority-snapshot use.
- Natural exception authorization, cross-tenant/SOD and replay denial paths.
- Controlled audited legal-hold/scanner state transitions.
- Database-role/RLS, accessibility, load/DR and full-stack browser evidence.
- Approved storage/scanner/renderer/ERP/SSO/grants/retention configuration in
  an external non-production acceptance environment.

See [SECURITY_ANALYSIS-BACKEND.md](SECURITY_ANALYSIS-BACKEND.md) for the
historical detailed review and [CODE_ISSUES.md](CODE_ISSUES.md) for live
disposition.

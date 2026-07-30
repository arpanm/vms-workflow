# F05 — Test issue register

## Open execution and depth gaps

| ID | Severity | Status | Source | Closure |
| --- | --- | --- | --- | --- |
| F05-TEST-001 | P1 | Open | [backend](TEST_ISSUES-BACKEND.md) | Run fresh compile, focused integration and full Maven verification after shared-target coordination. |
| F05-TEST-002 | P0 | Fix awaiting proof | [backend](TEST_ISSUES-BACKEND.md) | Replace synthetic-only coverage with a natural blocked readiness exception flow and all denial variants. |
| F05-TEST-003 | P1 | Open | [backend](TEST_ISSUES-BACKEND.md) | Prove committed concurrent package/invoice/review/share/export behavior using independent transactions/connections. |
| F05-TEST-004 | P2 | Open | [backend](TEST_ISSUES-BACKEND.md) | Add renderer/storage/checksum/expiry/worker-restart/lease-loss fault cases. |
| F05-FE-TEST-001 | P1 | Open | [frontend](TEST_ISSUES-FRONTEND.md) | Run `f05-finance-chromium` and fix actual browser failures. |
| F05-FE-TEST-002 | P1 | Open | [frontend](TEST_ISSUES-FRONTEND.md) | Run the journeys against packaged Java/PostgreSQL synthetic tenants. |
| F05-FE-TEST-005 | P2 | Open | [frontend](TEST_ISSUES-FRONTEND.md) | Add axe, keyboard/focus and tablet assertions. |
| F05-FE-TEST-006 | P2 | Open | [frontend](TEST_ISSUES-FRONTEND.md) | Inspect real generated export files for masking, formula safety, integrity and expiry. |

## External acceptance

Storage/scanner/rendering, legal hold, AP/ERP, deployed grants, recovery and
Procurement acceptance remain external test cases, not failures that local
code can close. See `T-STOR-006`, `E2E-F05-PROVIDER-001` and
`E2E-F05-PROVIDER-002` in [TEST_CASES.md](TEST_CASES.md).

## 2026-07-30 focused disposition

- Dashboard DTO and >50-row aggregate regressions are implemented and green.
- Export expiry and checksum-mismatch download guards are implemented and green,
  reducing `F05-TEST-004`; renderer/storage-write injection, worker restart,
  concurrent claim and lease-loss cases remain open.
- The permanent real-system dashboard assertion was added to
  `E2E-F05-SYS-001`, but this audit did not rerun the system browser project.
- Natural blocked-readiness exception setup, independent-transaction
  concurrency, performance/DR and external acceptance remain open exactly as
  described above.

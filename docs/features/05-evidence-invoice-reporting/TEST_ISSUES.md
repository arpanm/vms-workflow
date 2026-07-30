# F05 — Test issue register

## Open execution and depth gaps

**2026-07-30 exact execution evidence:**
`mvn -B -f backend/pom.xml -Dtest=FinanceCommittedConcurrencyIT test` passed
**2/2**. `npx playwright test e2e/finance-accessibility.spec.ts
--project=f05-finance-chromium` passed **3/3**.
`mvn -B -f backend/pom.xml -Dtest=FinanceWorkflowIT#quarantinePersistsExactBytesAndExceptionCreatesNewReadinessLineage test`
passed **1/1**. `npm run e2e:finance:system` passed **4/4** against local
Spring/Flyway/PostgreSQL/Vite with signed JWTs. These focused results do not
replace the remaining full-regression, performance/DR or G4 acceptance gates.

| ID | Severity | Status | Source | Closure |
| --- | --- | --- | --- | --- |
| F05-TEST-001 | P1 | Aggregate retained; exact recovery green | [backend](TEST_ISSUES-BACKEND.md) | Integrated Maven executed 340 with 2 failures and 1 error; exact Finance recovery passed 1/1. Do not claim 340/340. |
| F05-TEST-002 | P0 | Resolved locally; 1/1 | [backend](TEST_ISSUES-BACKEND.md) | Natural scanner blocker, non-waivable integrity policy and append-only policy-declared exception lineage pass with exact SOD/expiry/mismatch/revoked-authority/cross-tenant coverage. |
| F05-TEST-003 | P1 | Lease recovery covered; breadth open | [backend](TEST_ISSUES-BACKEND.md) | Live-lease completion fencing and expired-claim restart/idempotency were exercised. Add invoice/review/share concurrency breadth. |
| F05-TEST-004 | P2 | Open | [backend](TEST_ISSUES-BACKEND.md) | Add renderer/storage/checksum/expiry/worker-restart/lease-loss fault cases. |
| F05-FE-TEST-001 | P1 | Split evidence recorded | [frontend](TEST_ISSUES-FRONTEND.md) | Combined browser remains 287/292; exact failed slice passed 5/5. Do not claim a clean aggregate. |
| F05-FE-TEST-002 | P1 | Local system 4/4; external open | [frontend](TEST_ISSUES-FRONTEND.md) | Packaged local Vite/Spring/Flyway/PostgreSQL synthetic-identity lane passed 4/4; production identity/provider acceptance remains G4. |
| F05-FE-TEST-005 | P2 | Resolved locally; 3/3 | [frontend](TEST_ISSUES-FRONTEND.md) | Axe serious/critical, keyboard and tablet assertions passed in the intercepted-browser accessibility lane. |
| F05-FE-TEST-006 | P2 | Open | [frontend](TEST_ISSUES-FRONTEND.md) | Inspect real generated export files for masking, formula safety, integrity and expiry. |

## External acceptance

Storage/scanner/rendering, legal hold, AP/ERP, deployed grants, recovery and
Procurement acceptance remain external test cases, not failures that local
code can close. See `T-STOR-006`, `E2E-F05-PROVIDER-001` and
`E2E-F05-PROVIDER-002` in [TEST_CASES.md](TEST_CASES.md).

## 2026-07-30 focused disposition

- Dashboard DTO and >50-row aggregate regressions are implemented and green.
- Export expiry and checksum-mismatch download guards are implemented and green,
  reducing `F05-TEST-004`; renderer/storage-write injection remains open.
- The latest codegen batch adds non-waivable integrity-rule exception policy,
  live export-lease fencing and expired-claim restart/idempotency coverage.
  These paths were exercised; exact Finance recovery passed **1/1**.
- The permanent real-system dashboard assertion was added to
  `E2E-F05-SYS-001`; the system lane passed **4/4**.
- Independent invoice/review/share concurrency, performance/DR and external
  acceptance remain open exactly as described above.

# F05 — Test automation record

**Gate status:** Historical full-local baseline: backend **154/154**, Vitest
**88/88**, combined intercepted Chromium **69/69**, isolated real-system
Chromium **3/3**, plus typecheck/lint/build.

**2026-07-30 focused additions:**
`mvn -B -f backend/pom.xml -Dtest=FinanceCommittedConcurrencyIT test` passed
**2/2**, including a committed two-caller package-generation race. `npx
playwright test e2e/finance-accessibility.spec.ts --project=f05-finance-chromium`
passed **3/3** (axe serious/critical, keyboard entry and tablet no-overflow).
`mvn -B -f backend/pom.xml -Dtest=FinanceWorkflowIT#quarantinePersistsExactBytesAndExceptionCreatesNewReadinessLineage test`
passed **1/1**, including a scanner-derived `INVOICE_DOCUMENT` blocker. `npm
run e2e:finance:system` passed **4/4** through Vite, signed local JWT/JWKS,
Spring Security/API, Flyway, PostgreSQL and authorization/non-disclosure cases.

**Final integrated disposition:** the workflow regression asserts that
invoice-document integrity blockers cannot be waived and uses a
policy-declared business rule in a newly appended exact readiness lineage. Its
exact recovery selector passed **1/1**. The export-worker regression reclaims an
expired dead-worker lease, reaches `READY`, and asserts one artifact and ready
event across a repeated pass; completion after lease expiry is rejected.

## Implemented suites

| Layer | Coverage | Evidence |
| --- | --- | --- |
| Java unit/integration | canonical serialization, storage/scan/render adapters, Flyway/database guards, F04 contract/invalidation, package/share, invoice/readiness, Procurement/query/exception, payment, exports/retry | [TEST_AUTOMATION-BACKEND.md](TEST_AUTOMATION-BACKEND.md) |
| React/Vitest | API contract, adapters, idempotency, opaque pagination and presentation guards | [TEST_AUTOMATION-FRONTEND.md](TEST_AUTOMATION-FRONTEND.md) |
| Playwright | seven stateful F05 finance journeys via the public HTTP client boundary, including authenticated exception SOD | [TEST_AUTOMATION-FRONTEND.md](TEST_AUTOMATION-FRONTEND.md) |
| Real-system Playwright | three serial journeys through Vite, signed local JWT/JWKS, Spring Security/API, Flyway V1–V16 and isolated PostgreSQL 18: vendor invoice/package/submit, Procurement query/review and AP/restricted-export authorization, then expiry/re-share/revocation/cross-scope denial | [system runner](../../../e2e/system/README.md) |

## Real-system result

`npm run e2e:finance:system` passed **4/4** on 2026-07-30 through the isolated
Vite, signed JWT/JWKS, Spring Security/API, Flyway and PostgreSQL path. This is
local-system evidence, distinct from intercepted-browser coverage and from
production BFF/OIDC/provider acceptance.

The scenario catalog and requirement mapping are maintained in
[TEST_CASES.md](TEST_CASES.md). New end-to-end scenarios must also be added to
the repository-level regression catalog; this F05 document is the detailed
feature traceability source.

## Executed final local commands

```bash
npm run typecheck
npm run lint
npm run test
npm run build
npx playwright test --project=f05-finance-chromium
npm run e2e:finance:system
mvn -B -f backend/pom.xml verify
```

The results above are also recorded in the repository feature-status ledger.

## Boundary

Fixture-backed Playwright validates the browser/API contract. The separate
real-system project proves the bounded local Vite/Spring Security/Flyway/
PostgreSQL path with local metadata storage/scanning and synthetic identities;
it does not prove production OIDC, external storage/scanner/renderer, AP/ERP or
deployment grants. Those provider journeys remain external cases in
`TEST_CASES.md`.

## 2026-07-30 focused completion-audit evidence

This audit ran focused checks, not a new full regression:

- Java main/test compilation: passed.
- finance adapter Vitest: **4/4 passed**; frontend typecheck: passed.
- `FinanceSecurityIT`: **5/5 passed**.
- `FinancePaginationIT`: **2/2 passed**, including 55-row full-scope totals.
- `FinanceExportWorkerIT`: **5/5 passed**, including expired and checksum-
  mismatched download denial.
- `FinanceCommittedConcurrencyIT`: **1/1 passed** outside a test-managed
  transaction; two worker threads committed one artifact/event/outbox effect.

`E2E-F05-SYS-001` contains the permanent live dashboard assertion. Its prior
3/3 result is historical; the updated system lane passed **4/4**.

## Aggregate and recovery ledger

- Integrated Maven: 340 executed, 2 failures and 1 error (preserved).
- Exact Finance recovery:
  `FinanceWorkflowIT#quarantinePersistsExactBytesAndExceptionCreatesNewReadinessLineage`
  passed **1/1**.
- Combined browser: **287/292** (preserved); exact failed slice then passed
  **5/5**.
- F05 accessibility: **3/3** intercepted-browser; F05 finance system:
  **4/4** local-system.

No line above claims a single clean full Maven or full browser run.
Performance/scale, controlled DR, F07-T057 and G4 remain separate gates.

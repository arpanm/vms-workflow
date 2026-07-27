# F05 — Test automation record

**Gate status:** Full local regression is green: backend **154/154**, Vitest
**88/88**, combined intercepted Chromium **69/69**, isolated real-system
Chromium **3/3**, plus typecheck/lint/build.

## Implemented suites

| Layer | Coverage | Evidence |
| --- | --- | --- |
| Java unit/integration | canonical serialization, storage/scan/render adapters, Flyway/database guards, F04 contract/invalidation, package/share, invoice/readiness, Procurement/query/exception, payment, exports/retry | [TEST_AUTOMATION-BACKEND.md](TEST_AUTOMATION-BACKEND.md) |
| React/Vitest | API contract, adapters, idempotency, opaque pagination and presentation guards | [TEST_AUTOMATION-FRONTEND.md](TEST_AUTOMATION-FRONTEND.md) |
| Playwright | seven stateful F05 finance journeys via the public HTTP client boundary, including authenticated exception SOD | [TEST_AUTOMATION-FRONTEND.md](TEST_AUTOMATION-FRONTEND.md) |
| Real-system Playwright | three serial journeys through Vite, signed local JWT/JWKS, Spring Security/API, Flyway V1–V16 and isolated PostgreSQL 18: vendor invoice/package/submit, Procurement query/review and AP/restricted-export authorization, then expiry/re-share/revocation/cross-scope denial | [system runner](../../../e2e/system/README.md) |

## Real-system result

`npm run e2e:finance:system` passed **3/3** on 2026-07-27. The final
isolated run compiled 91 main and 34 test sources, validated and applied 22
migrations including test fixtures/seed, and completed the three Playwright
cases in 13.5 seconds (4.9s, 1.0s and 6.4s). The runner removed its temporary
Maven target, PostgreSQL container and child processes after completion.

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

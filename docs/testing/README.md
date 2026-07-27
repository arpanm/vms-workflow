# Testing and Regression

The repository uses three distinct verification lanes. Evidence from one lane
must not be represented as evidence from another.

| Lane | What it verifies | Command | Current status |
|---|---|---|---|
| Frontend unit/contract | Pure TypeScript behavior and API-client contracts | `npm run test` | **88/88 passing** in the F05 full local quality gate |
| Backend HTTP/database integration | Spring HTTP/security behavior, Flyway and real ephemeral PostgreSQL | `mvn -B -f backend/pom.xml verify` | **154/154 passing** (11 unit + 143 integration) with Testcontainers |
| Browser contract E2E | Real Chromium UI with deterministic, intercepted `/api/v1` responses | `npm run e2e` | **69/69 passing** through F05; still not provider/deployment acceptance |
| Isolated F05 system E2E | Browser through Vite, Spring Security/API, Flyway and an isolated PostgreSQL 18 database using local signed test JWTs/JWKS | `npm run e2e:finance:system` | **3/3 passing**; bounded local system evidence, not production BFF/OIDC/provider acceptance |
| Full-stack system E2E | Browser through a deployed BFF/OIDC provider, Java service and PostgreSQL | Not available yet | Blocked by provider/BFF/provisioning and a controlled E2E environment |

`npm run regression` is the local regression gate. It executes frontend
typecheck/unit/build, the Java/Testcontainers PostgreSQL lane, and the
Playwright browser-contract lane. It does **not** claim full-stack provider E2E.

## Playwright commands

```bash
npx playwright install chromium
npm run e2e
npm run e2e:headed
npm run e2e:report
npm run e2e:finance:system
```

Playwright starts isolated Vite servers with fixed feature flags for demo,
unauthenticated/no-BFF, and configured test-BFF redirect profiles. API fixtures
live under `e2e/fixtures/`. Browser console errors and uncaught page errors fail
the test, except the explicitly expected `401 /api/v1/me` response used to
establish an unauthenticated session.

`e2e:finance:system` is deliberately separate from the intercepted suite. It
starts an isolated F05 environment with local JWKS/test identities, Spring,
Flyway and PostgreSQL, then runs the three `E2E-F05-SYS-*` cases. It does not
use a production identity provider, storage/scanner/renderer, AP/ERP adapter or
deployment grants, so it cannot close those external acceptance gates.

## Adding a regression case

1. Add the case to [E2E_REGRESSION_CASES.md](E2E_REGRESSION_CASES.md) with a
   permanent ID such as `E2E-F02-010`.
2. State the feature, lane, data/setup, steps and observable expected result.
3. Add or extend an `e2e/*.spec.ts` file and include the ID in the test title.
4. Keep fixture responses tenant-safe and free of payroll/rate data.
5. Run the case alone, then `npm run e2e`, then `npm run regression`. For an
   F05 real-system case also run `npm run e2e:finance:system` where applicable.
6. Update [FEATURE_STATUS.md](../FEATURE_STATUS.md) with the result, command,
   date, commit, failures and open issues.

Do not delete or reuse an ID. Mark retired cases `Retired` and link their
replacement so historical test evidence remains intelligible.

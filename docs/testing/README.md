# Testing and Regression

The repository uses three distinct verification lanes. Evidence from one lane
must not be represented as evidence from another.

| Lane | What it verifies | Command | Current status |
|---|---|---|---|
| Frontend unit/contract | Pure TypeScript behavior and API-client contracts | `npm run test` | Automated |
| Backend HTTP/database integration | Spring HTTP/security behavior, Flyway and real ephemeral PostgreSQL | `mvn -B -f backend/pom.xml verify` | Automated with Testcontainers |
| Browser contract E2E | Real Chromium UI with deterministic, intercepted `/api/v1` responses | `npm run e2e` | Automated for F00–F02 |
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
```

Playwright starts isolated Vite servers with fixed feature flags for demo,
unauthenticated/no-BFF, and configured test-BFF redirect profiles. API fixtures
live under `e2e/fixtures/`. Browser console errors and uncaught page errors fail
the test, except the explicitly expected `401 /api/v1/me` response used to
establish an unauthenticated session.

## Adding a regression case

1. Add the case to [E2E_REGRESSION_CASES.md](E2E_REGRESSION_CASES.md) with a
   permanent ID such as `E2E-F02-010`.
2. State the feature, lane, data/setup, steps and observable expected result.
3. Add or extend an `e2e/*.spec.ts` file and include the ID in the test title.
4. Keep fixture responses tenant-safe and free of payroll/rate data.
5. Run the case alone, then `npm run e2e`, then `npm run regression`.
6. Update [FEATURE_STATUS.md](../FEATURE_STATUS.md) with the result, command,
   date, commit, failures and open issues.

Do not delete or reuse an ID. Mark retired cases `Retired` and link their
replacement so historical test evidence remains intelligible.

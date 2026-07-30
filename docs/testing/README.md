# Testing and Regression

The repository uses three distinct verification lanes. Evidence from one lane
must not be represented as evidence from another.

Current cross-feature implementation gaps and test reruns are tracked in
[PENDING_WORK.md](../PENDING_WORK.md); permanent regression cases and future
extensions remain in
[E2E_REGRESSION_CASES.md](E2E_REGRESSION_CASES.md).

| Lane | What it verifies | Command | Current status |
|---|---|---|---|
| Frontend unit/contract | Pure TypeScript behavior and API-client contracts | `npm run typecheck`, `npm run lint`, `npm run test`, `npm run build` | **PASS (current):** typecheck; lint 0 errors/13 warnings; 28 files/**120/120** Vitest in 804 ms; build 3,042 modules in 2.80 s with only the >500 kB advisory. |
| Backend HTTP/database integration | Spring HTTP/security behavior and real ephemeral PostgreSQL | `mvn -B -f backend/pom.xml verify` | Current full run executed 74 unit + 266 integration (340) with **2 failures + 1 error**. Exact recovery passed Finance 1/1, Migration 1/1 and Capacity 2/2; no clean 340/340 claim. |
| Browser contract E2E | Configured Chromium/Firefox/WebKit/mobile UI projects with deterministic intercepted `/api/v1` responses | `npm run e2e` | Current full run **287/292**; exact recovery slice **5/5**. No clean 292/292 claim. |
| Isolated F05 system E2E | Browser through Vite, Spring Security/API, Flyway and an isolated PostgreSQL 18 database using local signed test JWTs/JWKS | `npm run e2e:finance:system` | **4/4 passing**; bounded local system evidence, not production BFF/OIDC/provider acceptance |
| Isolated F06 system E2E | Browser/API through Vite, Spring Security, Flyway and isolated PostgreSQL 18 using local signed test JWTs/JWKS | `npm run e2e:migration:system` | Current **6/6**. |
| F07 local-system E2E | Ordered browser/API workflows through Vite, Spring Security, Flyway and PostgreSQL | F07 system runner | Product workflows through V43 **7/7**: E2E-01/02/03/04/05/07/10. V44 additive hardening and all V1000+ fixtures were subsequently startup-validated by `dev:all` (50 migrations total). |
| F07 accessibility matrix | Shared shell and critical routes across Chromium, Firefox, WebKit, Android and iOS projects with intercepted APIs | `npx playwright test e2e/f07-accessibility.spec.ts` | Final full browser matrix **274/274**; manual representative-user accessibility remains external |
| F07 backend/operations | Java/PostgreSQL hardening plus release, supply-chain, migration, rollout and recovery harness | `mvn -B -f backend/pom.xml verify`, `npm run f07:self-test`, `npm run f07:ops:check` | Self-test 9/9 (45.037 s), operations 15 runbooks/6 alerts, V1–V44 startup validation, rollout schema and SDLC eight-feature check pass. `f07:release:schema` wrapper is unverified due to sandbox bind `EPERM` and a 467 s silent escalated retry; underlying gates pass. |
| Full-stack system E2E | Browser through a deployed BFF/OIDC provider, Java service and PostgreSQL | Not available yet | Blocked by provider/BFF/provisioning and a controlled E2E environment |

`npm run regression` is the local regression gate. It executes frontend
typecheck/unit/build, the Java/Testcontainers PostgreSQL lane, and the
Playwright browser-contract lane. It does **not** claim full-stack provider E2E.

## Playwright commands

```bash
npx playwright install chromium
npx playwright install firefox webkit
npm run e2e
npm run e2e:headed
npm run e2e:report
npm run e2e:finance:system
npm run e2e:migration:system
```

Playwright starts isolated Vite servers with fixed feature flags for demo,
unauthenticated/no-BFF, and configured test-BFF redirect profiles. API fixtures
live under `e2e/fixtures/`. Browser console errors and uncaught page errors fail
the test, except the explicitly expected `401 /api/v1/me` response used to
establish an unauthenticated session.

`e2e:finance:system` is deliberately separate from the intercepted suite. It
starts an isolated F05 environment with local JWKS/test identities, Spring,
Flyway and PostgreSQL, then runs the four current `E2E-F05-SYS-*` cases. It does not
use a production identity provider, storage/scanner/renderer, AP/ERP adapter or
deployment grants, so it cannot close those external acceptance gates.

`e2e:migration:system` uses the same isolated boundary without route
interception. It executes the six `E2E-F06-SYS-*` cases against the real
controller/service/Flyway/PostgreSQL path. It does not claim production
scanner/object-storage, production OIDC/BFF or data-owner rehearsal evidence.

The F07 accessibility file adds stable journeys across desktop,
Safari-equivalent WebKit and mobile projects. Its first combined run passed all
cases on Chromium, WebKit, Android and iOS while Firefox timed out under shared
worker contention; a serialized Firefox rerun passed 6/6. The final complete
browser regression passes 274/274. All records remain in the history.

## Adding a regression case

1. Add the case to [E2E_REGRESSION_CASES.md](E2E_REGRESSION_CASES.md) with a
   permanent ID such as `E2E-F02-010`.
2. State the feature, lane, data/setup, steps and observable expected result.
3. Add or extend an `e2e/*.spec.ts` file and include the ID in the test title.
4. Keep fixture responses tenant-safe and free of payroll/rate data.
5. Run the case alone, then `npm run e2e`, then `npm run regression`. For an
   F05 real-system case also run `npm run e2e:finance:system` where applicable;
   for F07 run all browser projects plus the F05/F06 local-system lanes.
6. Update [FEATURE_STATUS.md](../FEATURE_STATUS.md) with the result, command,
   date, commit, failures and open issues.

Do not delete or reuse an ID. Mark retired cases `Retired` and link their
replacement so historical test evidence remains intelligible.

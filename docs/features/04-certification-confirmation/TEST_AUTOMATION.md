# F04 Test Automation — Consolidated Evidence

**Evidence state:** Agent-run in the active worktree; root-agent final rerun pending.

| Lane | Recorded result | What it proves |
| --- | --- | --- |
| Java/Spring/Testcontainers PostgreSQL | 111 passed: 109 integration and 2 unit | Migrations V1–V13, persistence/authorization/state/job/API contracts and final P1 race/negative paths in local PostgreSQL containers. |
| Frontend unit/API contract | 64 passed | TypeScript API, presentation, formatting, idempotency and route-contract behavior. |
| Playwright Chromium | 59 passed: 33 F04 plus 26 earlier cases | Deterministic browser UI and intercepted API contract only. |

The detailed command history and per-case traceability remain in [TEST_AUTOMATION-BACKEND.md](TEST_AUTOMATION-BACKEND.md), [TEST_AUTOMATION-FRONTEND.md](TEST_AUTOMATION-FRONTEND.md), and [docs/testing/E2E_REGRESSION_CASES.md](../../testing/E2E_REGRESSION_CASES.md). Earlier red runs are preserved there as history; they were not overwritten by the final agent-run result.

## Boundaries

- Testcontainers is local Java/PostgreSQL evidence, not production deployment-grant evidence.
- Playwright intercepts API traffic; it does not prove the Java controller, Spring Security, PostgreSQL, identity provider, email provider, mailbox, storage, or F05 consumer together.
- Live provider and controlled-environment cases stay pending until their external acceptance gates are supplied and executed.

Independent test-quality reviews are [backend](TEST_REVIEW-BACKEND.md) and [frontend](TEST_REVIEW-FRONTEND.md); historical findings are [backend](TEST_ISSUES-BACKEND.md) and [frontend](TEST_ISSUES-FRONTEND.md).

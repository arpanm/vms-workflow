# F01 Frontend Test Review Issues

> Issue snapshot: this file intentionally preserves the gaps identified at
> review time. Use [FIXES.md](FIXES.md) and
> [TEST_AUTOMATION.md](TEST_AUTOMATION.md) for current coverage; unchecked
> product scope in [TASKS.md](TASKS.md) remains open.

| ID | Severity | Missing or insufficient coverage | Required test |
|---|---|---|---|
| F01-TR-001 | Blocker | No end-to-end OIDC/login/callback/logout test exists, and current Java routes/configuration cannot satisfy the SPA flow. | Browser test against the selected OIDC implementation: anonymous deep link → login → callback → original in-app route; logout clears access; 401 returns to login without loop. |
| F01-TR-002 | Resolved blocker | The unsupported requirement creation client and UI have been removed; no legacy write contract is advertised. | Add canonical mutation contract/authorization tests only when a canonical workflow is implemented. |
| F01-TR-003 | Partially resolved high | Frontend unit tests now reject `//`, `/\\`, encoded/repeatedly encoded backslashes, controls and non-relative paths. | Repeat the invariant in the future BFF redirect handler and its browser/integration tests. |
| F01-TR-004 | High | `src/lib/legacy-route.test.ts:5-10` tests only a pure boolean helper, not the generated route's `beforeLoad`, hidden nav, deep-link 404, or disabled-root behavior. | Add browser/router integration tests for each legacy URL and direct navigation with the flag both on and off. |
| F01-TR-005 | High | No browser or Java integration test selects an organization and proves requests cannot blend multiple authorized organizations or cross tenant/object boundaries. | Add `/me`-driven scope tests plus Spring Testcontainers allowed/denied cases for every compatibility collection and canonical endpoint. |
| F01-TR-006 | Medium | API tests do not exercise malformed JSON, wrong content type, unexpected legacy response shape, 204 behavior, retry UX, or query error correlation rendering. | Add API-client and component tests with malformed/error payloads and a schema-contract fixture. |
| F01-TR-007 | Medium | No accessibility/interaction test verifies demo banner semantics, disabled persona switcher in production, sign-out failure feedback, or inert scope controls. | Add Playwright/RTL coverage with production and demo environment matrices. |

## Backend test issues — 26 July 2026

| ID | Severity | Missing or insufficient coverage | Required test |
|---|---|---|---|
| F01-BE-TR-001 | Blocker | Failsafe runs, but all authenticated cases use MockMvc `jwt()` (`ApiTenantSecurityIT.java:45-86`), bypassing the production decoder. A mocked `aud` claim is not audience-validation evidence. | Exercise the filter chain with signed JWTs from an ephemeral JWKS server: valid issuer/audience/time succeeds; wrong issuer/audience/key/algorithm and expired/not-yet-valid tokens fail. |
| F01-BE-TR-002 | Blocker | Only 4 of the F01 catalog/security scenarios run. There are no disabled user, inactive/revoked/future/expired membership, inactive organization, Project-A-vs-Project-B assignment, vendor-certification, or service-account denial cases (`TEST_CASES.md:5-22`). | Seed all actor/state variants and run allowed/denied HTTP tests against Testcontainers PostgreSQL. |
| F01-BE-TR-003 | High | Project and engagement-month list/get endpoints are completely untested, including direct foreign and nonexistent IDs. | Cover own/foreign/nonexistent IDs for every controller and assert uniform non-disclosing responses. |
| F01-BE-TR-004 | High | The database constraints named in T-CORE-001–003 and the cross-engagement project-parent gap are not tested. | Use real SQL/JPA transactions to prove distinct engagement parties, first-of-month, unique month, date integrity, and same-engagement parentage. |
| F01-BE-TR-005 | High | No test verifies database least-privilege roles. Tests connect with the container/bootstrap user (`ApiTenantSecurityIT.java:18-22`). | Migrate as owner, reconnect as runtime role, prove expected SELECT/DML and denial of DDL, cross-schema, migration-table modification, and restricted operations. |
| F01-BE-TR-006 | High | No OpenAPI test checks authentication metadata, endpoint/DTO completeness, ProblemDetail schemas, or generated frontend compatibility. | Fetch `/v3/api-docs` as anonymous/authenticated actors, validate it, snapshot breaking contracts, and compile the generated client. |
| F01-BE-TR-007 | Medium | No tests cover ProblemDetail codes/correlation, malformed parameters, 404-vs-403 object enumeration, conflict, stale version, or invalid transition. | Add an error contract matrix and assert sanitized stable fields for every error family. |
| F01-BE-TR-008 | Medium | No CORS/preflight, CSRF, or session-statelessness test fixes the intended browser security model. | Assert same-origin/no-CORS behavior or an exact allowlist; if cookies are introduced, add CSRF positive/negative tests. |
| F01-BE-TR-009 | Medium | No regression test proves the legacy collection/table allowlist cannot be bypassed despite identifier concatenation. | Try unknown/encoded collection names and verify 404/400 with no SQL execution; retain bound organization parameters. |
| F01-BE-TR-010 | Low | Maven reports Mockito dynamic agent self-attachment warnings on Java 25; future JDKs will disallow this by default. | Configure the supported Mockito agent mechanism and keep the Java 25 build warning-free. |
## 2026-07-29 closure

All local F01 product test issues are closed. The final two fixture defects
were test-only: Northstar was inserted after V34 and therefore required an
explicit foreign configuration row, and PostgreSQL SQLSTATE `55000` maps to a
general Spring data-access runtime exception rather than
`DataIntegrityViolationException`. Both assertions were corrected without
weakening the database controls.

The remaining controlled-environment identity-provider scenarios are tracked
as external acceptance work, not local test failures.

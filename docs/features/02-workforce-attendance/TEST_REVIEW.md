# F02 test review

> Historical pre-fix review snapshot. The final evidence is in the dated
> post-fix section and [TEST_AUTOMATION.md](TEST_AUTOMATION.md).

## Result

The targeted browser command passed: `npm run e2e -- --project=workforce-chromium`
ran six tests successfully in 19.4 seconds. This is useful UI rendering and
mutation-wiring coverage only; it is **not** full-stack evidence.

`mockWorkforceApi` intercepts every `/api/v1/**` request (`e2e/fixtures/workforce-api.ts:87-260`) and
the Playwright server points at demo mode (`playwright.config.ts:38-47`,
`101-106`). No browser request reaches Java, PostgreSQL, JWT enforcement,
Flyway, or a provider boundary.

## Coverage quality

- Good: the E2E suite checks visible workforce navigation, deliberate checkout
  action wiring, client form validation, and absence of month-close mutation
  buttons (`e2e/workforce.spec.ts:9-189`). Browser error gates are also enabled
  (`e2e/fixtures/quality-gates.ts:3-33`).
- Good: integration coverage exercises selected cross-tenant not-found behavior,
  one source-mode draft certification rejection, and append-only snapshots
  (`WorkforceAttendanceIT.java:259-345`).
- Insufficient: no test verifies GET has no writes, command authorization split,
  actual EMPLOYEE-role self-service, multi-day leave apportionment, snapshot
  completeness, certification revocation, or production UI/API contract parity.

The current test documentation appropriately says provider-boundary work remains
(`TEST_AUTOMATION.md:42-52`), but its command/result section should not be used
as evidence that the E2E claims exercise the backend.

See [TEST_ISSUES.md](TEST_ISSUES.md) for test-specific defects and required
coverage additions.

## Post-fix disposition — 2026-07-26

The original assessment above predates the fix suite. The backend integration
class is now transactional and contains 20 order-independent tests in the final
recorded run. New tests cover read-only attendance GET, per-date leave
reconciliation/off-day exclusion, reviewer command denial, self identity,
missing-object non-disclosure, allocated close completeness, inactive
allocation exclusion, closed-leaf lineage and certification revocation.

The frontend final run contains 26 passing unit/contract tests. The complete
browser-contract run contains 18 passing cases, including seven F02 journeys.
The leave journey first proves that 1.5 units over one selected day is blocked
with no POST, then proves the valid two-day request and API-returned split.
Self-service asserts `/employees/me` and no organization/peer-roster request.

These remain intercepted browser-contract tests; Java/PostgreSQL security and
invariants are evidenced only by the Spring/Testcontainers lane. Provider and
deployed full-stack E2E remain unavailable. See
[TEST_AUTOMATION.md](TEST_AUTOMATION.md), [TEST_CASES.md](TEST_CASES.md) and
[FIXES.md](FIXES.md).

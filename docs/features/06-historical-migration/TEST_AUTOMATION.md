# F06 — Test Automation

## Final integrated reconciliation — 2026-07-30

The V43 migration-system automation passes 6/6, with focused
migration/OpenAPI recovery 1/1 and accessibility 3/3. Preserve the failed
Maven 340 (2 failures + 1 error) and browser 287/292 rows alongside their
focused recovery evidence; neither aggregate was rerun clean.

The detailed source is [TEST_CASES.md](TEST_CASES.md). Local lanes are:

- Java unit tests for registry hashes, CSV quoting/BOM/newlines, prohibited
  columns, formula safety and stable validation codes.
- Spring/Testcontainers PostgreSQL tests for V17 constraints, scoped API,
  idempotency, dependency order, dry-run, partial commit, dual SOD sign-off,
  provenance, raw/daily authority, rollback guard and retro timestamps.
- Vitest for UI permission and exact-sign-off presentation logic.
- Playwright `f06-migration-chromium` for `E2E-08` through `E2E-08D`,
  including the immutable partial policy, retro delegation, cross-tenant
  non-disclosure and bounded retry.
- A six-case non-intercepted system lane that drives a real browser through
  Vite, Spring Security/API, Flyway V1–V20 and isolated PostgreSQL 18.
- Full repository `npm run regression` after focused lanes pass.

Definitive pre-closure evidence on 28 July 2026 was 14/14 Java unit, 158/158
Spring/Testcontainers integration, 90/90 Vitest, 74/74 combined intercepted
Playwright and 6/6 real local system Playwright. Exact commands, subsequent
review-fix reruns and any failures remain recorded in
[the feature-status ledger](../../FEATURE_STATUS.md).

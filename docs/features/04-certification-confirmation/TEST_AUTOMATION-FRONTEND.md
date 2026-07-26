# F04 frontend test automation

**Executed:** 2026-07-26
**Scope:** Vitest unit/API-contract automation and deterministic Playwright browser-contract automation for the current React/TanStack F04 implementation.

## Outcome

The automation is installed and executable, but it intentionally does **not** report F04 frontend completion. It has exposed the open review findings rather than weakening assertions:

- Frontend unit suite: **57 passed, 1 failed** across 15 files.
- F04 Playwright project: **14 passed, 10 failed** across 24 stable browser-contract cases.
- Full Playwright regression: **40 passed, 10 failed** across 50 cases. All 26 existing F00–F03 cases passed.
- TypeScript type-check: **passed**.

The failing cases below are product/contract defects. There were no remaining fixture-shape, selector-strictness, or TypeScript harness failures in the final run.

## Automation boundary

`e2e/fixtures/certification-api.ts` supplies deterministic, redacted browser responses for:

- vendor draft, completeness blockers, save, exact-version submit, locked timeline, and clarification lineage;
- reviewer criterion decisions, partial carry-forward, clarification, and summary requests;
- all five readiness pillars, blockers/owners/CTAs, exact recipients/quorum, notification transport, inbound safe metadata, and reopen lineage;
- active, corrected, replayed, expired, and unauthorized confirmation request states;
- typed `404` safe denial and `412` version conflict responses;
- safe scan-passed evidence metadata without file bodies, signed URLs, token values, raw MIME, provider credentials, payroll, rates, or recipient authority claims.

This is a real Chromium UI against intercepted APIs. It does **not** prove Java controllers, Spring Security, PostgreSQL/Flyway, object storage/scan, email providers, inbound mailbox authorization, production SSO/OTP, or F05 execution. Provider gates remain `NOT_CONFIGURED` / `ACTION_REQUIRED`.

The dedicated Playwright project is `f04-certification-chromium`. It fixes the browser timezone to `America/New_York` so offset loss is deterministic rather than dependent on the developer machine.

## Unit and API-contract cases

| ID | Assertion | Final result | Issue |
|---|---|---|---|
| F04-UNIT-API-001 | Month, readiness, and request IDs are URL encoded and scoped | Passed | — |
| F04-UNIT-API-002 | Every mutation carries `If-Match` and an `Idempotency-Key` | Passed | — |
| F04-UNIT-API-003 | Action payload excludes token, token hash, raw MIME, recipient authority, and provider secrets | Passed | — |
| F04-UNIT-API-004 | A retry of the same user intent retains the original idempotency key | **Failed** | `F04-FE-004` |
| F04-UNIT-PRES-001 | Safe denial, conflict, locked, validation, and outage errors classify distinctly | Passed | — |
| F04-UNIT-PRES-002 | Vendor, criterion, certification, and confirmation choices stay explicit | Passed | — |
| F04-UNIT-PRES-003 | Non-simple outcome and non-confirmation narrative requirements remain explicit | Passed | — |
| F04-UNIT-PRES-004 | Every readiness state has presentation output in addition to status text | Passed | — |
| F04-UNIT-FMT-001 | Invalid server date is preserved instead of fabricating a timestamp | Passed | — |
| F04-UNIT-FMT-002 | Machine labels are humanized without changing the underlying status | Passed | — |
| F04-UNIT-FMT-003 | Valid offset instant produces a readable date | Passed | — |

`F04-UNIT-API-004` observed two different UUIDs for identical confirmation action calls. That is the precise retry-intent defect described by `F04-FE-004`.

## Playwright browser-contract cases

| ID | Coverage | Final result | Issue / traceability |
|---|---|---|---|
| E2E-F04-BC-001 | Vendor blockers, fields, scan-passed reference, save request, exact submit, lock, timeline | Passed | `T-F04-UI-001` |
| E2E-F04-BC-002 | Dirty visible edit must be saved before exact submit | **Failed** | `F04-FE-005` |
| E2E-F04-BC-003 | Submitted content remains read-only with clarification/timeline evidence | Passed | `T-F04-UI-001` |
| E2E-F04-BC-004 | Reviewer deep route, assigned inbox/age, three-way context, criteria, clarification, partial carry-forward, no Linear inference | **Failed** | `F04-FE-001`; deeper assertions remain blocked by the missing outlet |
| E2E-F04-BC-005 | Five readiness pillars, source versions/freshness, blocker owner/CTA, no F05 execution | Passed | `T-F04-UI-003`, `T-READY-001` |
| E2E-F04-BC-006 | Offset default is converted into the operator timezone | **Failed** | `F04-FE-009` |
| E2E-F04-BC-007 | Exact recipient/quorum/version request and concurrency headers | Passed | `T-F04-UI-003`, `T-CONF-002` |
| E2E-F04-BC-008 | Safe inbound/manual metadata plus reviewer decision/reason controls | **Failed** | `F04-FE-007` |
| E2E-F04-BC-009 | Exact scope/diff and one attributable successful in-app confirmation action | Passed | `T-F04-UI-004`, `T-CONF-005`, `T-CONF-009` |
| E2E-F04-BC-010 | Non-confirmation required-field error summary and `aria-invalid` feedback | **Failed** | `F04-FE-011` |
| E2E-F04-BC-011 | Explicit correction comment/action and governance outcome | Passed | `T-CONF-010` |
| E2E-F04-BC-012 | Consumed/replayed request exposes one prior result and no new action | Passed | `T-CONF-007` |
| E2E-F04-BC-013 | Expired request is locked and elapsed time never creates an action | Passed | `T-CONF-006`, `T-CONF-011` |
| E2E-F04-BC-014 | Unauthorized deep link returns correlation-bearing non-disclosing denial | Passed | `T-F04-SEC-002`, `T-CONF-006` |
| E2E-F04-BC-015 | Read/delivered/dead-letter/silence transport never confirms | Passed | `T-MSG-005`, `T-CONF-011` |
| E2E-F04-BC-016 | Reopen sends reasoned impact and appends superseding request lineage | Passed | `T-CLOSE-002` |
| E2E-F04-BC-017 | Stale month/readiness is visible and consequential controls are disabled | Passed | `T-F04-UI-006` |
| E2E-F04-BC-018 | `412` refetch rebases controlled fields to current server version | **Failed** | `F04-FE-010` |
| E2E-F04-BC-019 | Server error detail cannot render token/raw-MIME/provider-secret sentinel text | **Failed** | `F04-FE-012` |
| E2E-F04-BC-020 | URL, DOM, local storage, and session storage contain no secret/token/restricted sentinels | Passed | `T-F04-SEC-003`, `T-CONF-004` |
| E2E-F04-BC-021 | Critical confirmation controls are named and keyboard reachable | Passed | `T-F04-UI-006` |
| E2E-F04-BC-022 | Expanded correction form fits a 768 px tablet viewport | **Failed** | `T-F04-UI-006`; additional responsive defect surfaced, no existing review-issue ID |
| E2E-F04-BC-023 | Criterion evidence uses an authorized server-managed selector | **Failed** | `F04-FE-006` |
| E2E-F04-BC-024 | Bound confirmation sources have human-readable names, versions, and hashes | **Failed** | `F04-FE-008` |

## Exact commands and results

| Command | Result |
|---|---|
| `npm run typecheck` | Passed |
| `npm run lint` | Passed with 6 pre-existing fast-refresh warnings and no errors |
| `npm run build` | Passed; Vite emitted the existing large-chunk advisory |
| `npx vitest run src/features/certification/api.test.ts src/features/certification/presentation.test.ts src/features/certification/formatting.test.ts` | 10 passed, 1 failed (`F04-UNIT-API-004`) |
| `npm run test` | 57 passed, 1 failed across 15 files (`F04-UNIT-API-004`) |
| `npx playwright test --project=f04-certification-chromium` | 14 passed, 10 failed; exact failing IDs: `BC-002`, `BC-004`, `BC-006`, `BC-008`, `BC-010`, `BC-018`, `BC-019`, `BC-022`, `BC-023`, `BC-024` |
| `npm run e2e` | 40 passed, 10 failed across 50 cases; the same ten F04 failures; all F00–F03 cases passed |

Playwright traces, screenshots, videos, and the HTML report are retained under `node_modules/.cache/playwright-results` and `node_modules/.cache/playwright-report`.

## Gate interpretation

- `T-F04-UI-001` has partial browser evidence: the valid save/submit/lock path passes, but dirty-submit safety fails.
- `T-F04-UI-002` is blocked by the nested review route and by assignment/age, evidence-selector, and exact browser-contract limitations.
- `T-F04-UI-003` has passing deterministic browser evidence for readiness, exact request preview, transport, and reopen lineage; timezone correctness still fails.
- `T-F04-UI-004` has passing exact diff, confirmation, correction, replay, expired, unauthorized, and no-secret cases; unsafe error-detail rendering fails defense in depth.
- `T-F04-UI-005` remains blocked because inbound/manual review is display-only.
- `T-F04-UI-006` has passing named-control, keyboard, stale, conflict-detection, and safe-denial coverage; required-field feedback, controlled-state rebase, and expanded tablet layout fail.
- `T-F04-REG-001` has browser-only evidence that every pre-F04 Playwright case passed. It is not a replacement for the Java/PostgreSQL regression lane.
- External provider cases and G4 are not executed or claimed.

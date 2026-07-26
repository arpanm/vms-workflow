# F04 frontend remediation evidence

Date: 2026-07-26

Scope: React/TanStack Query/Router code, TypeScript contracts, deterministic
Playwright fixtures, browser quality gates, and the F04 regression ledger. The
browser lane intercepts the API; it does not claim live provider, Java, or
PostgreSQL execution.

## Resolved findings

| Finding                  | Resolution                                                                                                                                                                                                                                                   | Executable evidence                                                          |
| ------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------- |
| `F04-FE-001`             | Split the month route into an outlet layout plus index child, so the product-owner review deep route renders its own workspace.                                                                                                                              | `E2E-F04-BC-004`                                                             |
| `F04-FE-002`             | Aligned every frontend call and fixture to the current secured controller paths, Java DTO shapes, success statuses, ETags, expected-version bodies, `If-Match`, and idempotency headers. No unsupported endpoint is presented as available.                  | `F04-UNIT-API-001`–`003`; `E2E-F04-BC-001`, `004`, `007`–`009`, `016`, `032` |
| `F04-FE-003`             | Added the required F04 Playwright project and 33 vendor, reviewer, governance, confirmation, concurrency, privacy, accessibility, and responsive cases.                                                                                                      | Final F04 lane: 33 passed; full lane: 59 passed                              |
| `F04-FE-004`             | Generate an idempotency key per user intent, retain it across ambiguous transport/5xx failures, and clear it only after a definitive result or changed intent.                                                                                               | `F04-UNIT-API-004`–`005`; `E2E-F04-BC-025`                                   |
| `F04-FE-005`             | Track dirty vendor state; exact submit first saves the visible draft and submits only the submission ID/version returned by that save. Returned blockers stop the chain.                                                                                     | `E2E-F04-BC-001`–`002`                                                       |
| `F04-FE-006`             | Replace arbitrary evidence-ID text with accessible server-managed selectors at deliverable and criterion level. React sends UUIDs only and never stores artifact bytes, locators, signed URLs, MIME, or credentials.                                         | `E2E-F04-BC-001`, `023`                                                      |
| `F04-FE-007`             | Consume the required redacted inbound/manual-review array and implement the exact distinct 201 review endpoints and decision vocabularies. Raw MIME, headers, addresses, subjects, and artifacts remain absent; unauthorized callers receive an empty array. | `E2E-F04-BC-008`, `032`, `033`                                               |
| `F04-FE-008`             | Filter/count the reviewer inbox from `assignedToCurrentActor`; render server-clock SLA age/status/start/due data; render the named immutable confirmation scope manifest with IDs, versions, freshness, and checksums.                                       | `E2E-F04-BC-004`, `024`                                                      |
| `F04-FE-009`             | Convert the server offset instant into `datetime-local` in the browser zone, display that zone, and submit the same instant as UTC.                                                                                                                          | `F04-UNIT-FMT-004`–`006`; `E2E-F04-BC-006`, `031`                            |
| `F04-FE-010`             | Reconcile controlled forms only when the authoritative object/version changes, preserving dirty fields during same-version refetches and rebasing after conflicts. Mutation errors receive focus.                                                            | `E2E-F04-BC-018`                                                             |
| `F04-FE-011`             | Replace silently disabled attempted-submit paths with focusable linked error summaries, inline errors, `aria-invalid`, and `aria-describedby` for reviewer, confirmation, inbound/manual-review, and reopen actions.                                         | `E2E-F04-BC-008`, `010`, `028`–`030`, `032`                                  |
| `F04-FE-012`             | Render only allowlisted problem-code copy and a sanitized correlation ID. Arbitrary server detail is never appended to an F04 page.                                                                                                                          | `E2E-F04-BC-019`                                                             |
| `F04-FE-TEST-001`–`005`  | Use Java-serializable UUIDs and current DTO fields; enforce controller status/header/body semantics in the fixture; model committed-but-lost retry; keep console/page/network secrecy gates installed; add the missing critical journeys.                    | 64 frontend unit tests; 33 F04 and 59 full Playwright cases                  |
| `T-F04-UI-006` follow-up | Make exact-scope cards/tables/identifiers and the application header min-width safe at tablet and phone breakpoints.                                                                                                                                         | `E2E-F04-BC-022`, `027`                                                      |

Readiness CTAs now resolve only to allowlisted application routes. Provider
paths returned by older server records are mapped to the current UI route;
unknown, external, protocol-relative, query-bearing, fragment-bearing, or
backslash paths render as text rather than links.

## Final local frontend gates

```text
npm run typecheck
PASS

npm run lint
PASS — 0 errors; 6 pre-existing Fast Refresh warnings in shared UI modules

npm test
17 files, 64 tests passed

npm run build
PASS

npx playwright test e2e/certification.spec.ts
33 passed, 0 failed

npm run e2e
59 passed, 0 failed
```

The regression ledger preserves the earlier 14/10, 29/2, and 31/2 F04
results before recording the final passing runs.

## Exact residual gates

- Playwright remains a deterministic intercepted browser-contract lane. A
  browser/BFF/Java/PostgreSQL system lane is still required before release.
- Approved SSO/OTP/step-up and secure-link token exchange remain external. No
  token is accepted into a browser URL, storage, log, or React state.
- Controlled sender/mailbox configuration, callback signature material,
  retention approval, live inbound exercises, provider retry/dead-letter
  operation, and G4 acceptance remain `NOT_CONFIGURED` or external.
- Evidence ingestion, scanning, object storage, scoped audited viewing, and
  short-lived access URLs remain unavailable. The UI accurately says artifact
  access is unavailable and does not fabricate a viewer.
- F05 package/invoice execution is out of F04 scope. The UI displays only the
  server-returned readiness handoff state and exposes no package/invoice
  execution control.

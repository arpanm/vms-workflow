# F05 frontend test automation

## Automated unit and contract coverage

The F05 frontend now has focused Vitest coverage under
`src/features/finance`:

- `api.test.ts` verifies opaque cursor and identifier encoding, share/create
  and revoke routes, exact-version package generation headers, UUID
  idempotency keys, separated exception request/authenticated second-approval
  routes and the absence of caller-supplied approver or URL/token grant data.
- `adapters.test.ts` verifies the executable Java dashboard, flattened
  control-tower and report/export responses are normalized without inventing
  source facts.
- `idempotency.test.ts` verifies successful intents settle, ambiguous transport
  failures retain the same key for retry, definitive client failures release
  the key and changed payloads create a new intent.
- `pagination.test.ts` verifies forward opaque-cursor history, duplicate/absent
  cursor rejection and bounded previous-page navigation.
- `presentation.test.ts` verifies payment permission presentation,
  read-only/stale/terminal command blocking, same-application action-path
  allowlisting, invalid date handling and safe status formatting.

Executed on 2026-07-27:

```text
npx vitest run src/features/finance/api.test.ts \
  src/features/finance/adapters.test.ts \
  src/features/finance/idempotency.test.ts \
  src/features/finance/pagination.test.ts \
  src/features/finance/presentation.test.ts

Test Files  5 passed (5)
Tests       22 passed (22)
```

## Stateful Playwright coverage

`e2e/finance.spec.ts` and `e2e/fixtures/finance-api.ts` add the
`f05-finance-chromium` project. The fixture intercepts the authenticated HTTP
boundary, returns the current `FinanceController` response shapes, records
headers/bodies, and mutates invoice, share, review, query, payment and export
state after successful commands. It does not write React state, call component
internals or replace the finance API client.

The browser catalog contains:

| Browser case | Product flow and assertions |
|---|---|
| `E2E-F05-FIN-001` | Finance dashboard/configuration, scoped queue, authorized month list, opaque next/previous cursor |
| `E2E-F05-FIN-002` | First immutable document upload, exact readiness rerun, exact package/readiness invoice submission, `If-Match` and `Idempotency-Key` |
| `E2E-F05-FIN-003` | Package generation, artifact-level manifest, controlled expiring share, audit-visible list and reasoned revoke |
| `E2E-F05-FIN-004` | Procurement exact-version approval, assigned query and append-only payment status/history |
| `E2E-F05-FIN-004B` | Exact-rule exception request, typed requester self-approval denial, distinct authenticated Procurement approval, exact binding/headers and accepted disclosure |
| `E2E-F05-FIN-005` | Report definition/version, current-versus-snapshot filters, asynchronous export queue and idempotency |
| `E2E-F05-FIN-006` | Missing payment capability, read-only command blocking, quarantined download denial and permitted immutable replacement |

The shared Playwright quality gate now scans F05 request/response and browser
console content for restricted token, key and provider-secret markers in
addition to its existing error checks.

The Playwright sources and config were TypeScript-checked without starting a
browser:

```text
npx tsc --noEmit --target ES2022 --module ESNext \
  --moduleResolution Bundler --skipLibCheck \
  --types node,@playwright/test \
  e2e/finance.spec.ts e2e/fixtures/finance-api.ts \
  e2e/fixtures/quality-gates.ts playwright.config.ts
```

The complete seven-case `f05-finance-chromium` project passed on 2026-07-27.
The same project remains a required coordinated CI/release command:

```text
npx playwright test --project=f05-finance-chromium
```

## Frontend implementation exercised

All cursor-backed screens now carry the opaque server cursor without decoding
or synthesizing it: finance months, invoice queue, month/package history,
package share list, package access audit, Procurement control tower and export
queue. Previous navigation uses only cursors already returned during the current
view session.

Package sharing uses the current controller contract:

- list: `GET /api/v1/finance/packages/{packageId}/shares?cursor=…`
- create: `POST /api/v1/finance/packages/{packageId}/shares`
- revoke:
  `POST /api/v1/finance/packages/{packageId}/shares/{shareId}/revoke`

Create requires authenticated recipient subject, `VIEW` or `DOWNLOAD`, future
expiry, reason and explicit confirmation. Revoke requires a reason and explicit
confirmation. Both mutations use a retry-safe idempotency intent. No signed URL,
bearer token, raw evidence or storage identifier is placed in component or
query state.

## Commands run

The frontend and complete F05 mocked-browser verification completed
successfully:

```text
npm run typecheck
npm test
  Test Files  22 passed (22)
  Tests       88 passed (88)
npx eslint src/features/finance/contracts.ts src/features/finance/api.ts \
  src/features/finance/api.test.ts src/features/finance/hooks.ts \
  src/features/finance/components.tsx \
  src/features/finance/procurement-workspace.tsx \
  e2e/finance.spec.ts e2e/fixtures/finance-api.ts
npx playwright test e2e/finance.spec.ts --project f05-finance-chromium
  7 passed
```

No Maven command was run in this frontend follow-up. The Java invoice read
projection was extended with the already-persisted package/readiness IDs so the
browser can approve the exact pending tuple instead of inferring it from live
invoice state; coordinated backend verification remains required.

# F05 frontend code review

Review completed 2026-07-26 against F05 `TASKS.md`, `TEST_CASES.md`, and
requirements 10, 12, 14, 15, 16 and 22. Scope was the current finance React
vertical, its routes/navigation, role-store/API-client changes, and the
available Java API boundary. No product or test code was changed by this
review.

## Release assessment

**NO-GO for F05.** The typed React surface is a useful scaffold: it uses the
authenticated shared API client, keeps raw document bytes and signed URLs out
of React Query, shows text status/freshness/version information, and does not
introduce prohibited salary/rate/markup/payroll fields. `typecheck`, unit test,
and production build pass; lint has no errors but reports eight fast-refresh
warnings, two in `src/features/finance/components.tsx`. Those commands do not
establish F05 behaviour.

| Priority | Finding | Gate |
|---|---|---|
| P0 | The entire `/api/v1/finance` contract used by the UI has no controller/service implementation, and the only Java finance DTO draft has a materially different schema. | Full-stack/local implementation blocker |
| P1 | Invoice-detail rendering discloses payment history without requiring `PAYMENT_VIEW`. | Local frontend defect |
| P1 | Procurement, payment and readiness-changing controls remain enabled for a server-marked read-only or stale invoice. | Local frontend defect |
| P1 | The package viewer omits the immutable manifest-item data required to prove artifact lineage. | Local frontend defect |
| P1 | Required package sharing/revocation controls and contract methods are absent. | Local frontend defect |
| P1 | No F05 frontend unit/component or Playwright coverage was added. | Local verification blocker |
| P1 | Required dashboard queues and cell-level owner CTAs are returned by the client contract but never rendered. | Local frontend defect |
| P1 | Client retry logic can discard an idempotency key after ambiguous HTTP timeout/rate-limit outcomes. | Local frontend defect |
| P2 | All cursor-backed finance tables are permanently limited to their first page. | Local frontend defect |
| P2 | Scan/quarantine states are not actionable, and quarantine prevents a replacement upload. | Local frontend defect |

Detailed evidence and remediation are in [CODE_ISSUES-FRONTEND.md](CODE_ISSUES-FRONTEND.md).

## Contract and boundary observations

The frontend is intentionally presentation-gated only: direct routes continue
to rely on server authorization, which is correct. `role-store.ts` only changes
the demo sidebar when safe demo mode is enabled, and finance calls use the
shared authenticated API client. The client avoids rendering untrusted document
HTML and uses direct authenticated attachment responses rather than retaining
signed URLs.

That boundary cannot yet function. `src/features/finance/api.ts` calls 22
finance endpoints, but there is no `FinanceController` or finance application
service under `backend/src/main/java`. The unconnected `FinanceDtos.java` does
not match `src/features/finance/contracts.ts`: for example, frontend creation
sends nested `representedMetadata` and `documentKind`, whereas the DTO expects
flat `invoiceType`/billing fields and a client-supplied file metadata object;
the DTO's control-tower and workspace shapes also do not match the UI's paged
nine-cell matrix and access view. This is a full-stack/local implementation
gate, not an external-provider delay. Freeze an executable, versioned
OpenAPI/contract first, implement it server-side, then add browser-contract and
real integration coverage before claiming the UI works.

Provider configuration, real private storage/scanning/rendering, short-lived
revocable download authorization, retention/legal hold, AP/ERP reconciliation,
production SSO/grants, performance/load, and deployed accessibility evidence
remain correctly classified as external/full-stack acceptance gates. They are
not substitutes for the local P0/P1 fixes above.

## Requirement coverage notes

- The screens attempt the expected invoice, package, Procurement, payment,
  control-tower and export flows, preserve represented document totals without
  calculation, and display non-colour `StatusBadge` labels.
- Task 17 and `T-F05-UI-001` through `006` remain unproven: there are no finance
  test files in `src`, `e2e`, or Playwright configuration, while the 64 passing
  Vitest tests belong to earlier features.
- Task 14/requirement 12 is incomplete in the UI: `FinanceDashboard.queues`
  and `MatrixCell.actionPath` are defined but ignored, pagination is not
  operable, and therefore users cannot reliably reach all authorized work or
  the supplied remediation paths.
- Task 15/requirements 12 and 14 remains a full-stack gate as export
  generation, masking, formula escaping, auditing, retention and download
  expiry are server assertions. The frontend exposes only a queue scaffold.

## Commands run

- `npm run typecheck` — passed.
- `npm run lint` — passed with 8 fast-refresh warnings; no errors.
- `npm run test` — passed: 17 files, 64 tests; no F05 finance coverage found.
- `npm run build` — passed; Vite reported two large-chunk advisories.

## Superseding evidence addendum — 2026-07-30

F05 accessibility passed **3/3** in the intercepted-browser lane and finance
local-system passed **4/4**. The combined browser attempt remains **287/292**,
with only its exact failed slice subsequently **5/5**; it is not recorded as a
clean 292/292 run. Provider identity and G4 remain external.

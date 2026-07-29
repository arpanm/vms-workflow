# F03 frontend codegen and verification

Date: 2026-07-26

## Implemented coverage

- Aligned `src/features/delivery/contracts.ts` with the Java DTO and persisted
  value sets:
  - dependency types are `INTERNAL | LINEAR | EXTERNAL`;
  - priorities are `P0 | P1 | P2 | P3`;
  - delivery categories, plan states, normalized execution states, commitment
    statuses, link statuses, connection statuses and provider-registration
    statuses are closed unions;
  - `AssignmentView.effectiveTo` and `exceptionReason` are required response
    fields with `null` values when absent;
  - provider state ID/name/type/category and provider update time are nullable;
  - a link's normalized state and fetch time are nullable because the backend
    uses a left join;
  - snapshot statuses match the server values `CAPTURED | FETCH_FAILED |
UNAVAILABLE`;
  - commitment status is nullable before a commitment outbox record exists and
    otherwise matches `PENDING | SENT | RETRY | DEAD_LETTER`.
- Removed browser-supplied Linear identifier, URL, title and provider-state
  fields from `LinkIssueRequest` and the link form. The browser now sends only
  `deliverableVersionId`, `connectionId`, `issueUuid` and optional rationale.
  It never calls Linear or accepts provider credentials.
- Corrected the new-plan dependency and priority controls to send only values
  accepted by the hardened Java DTOs.
- Added state-exact action gating:
  - submit is rendered only for `DRAFT`;
  - issue linking is rendered only for `DRAFT`;
  - approval/rejection controls are rendered only for `PENDING_APPROVAL`;
  - revision is rendered only for `FROZEN`;
  - all non-draft content is presented as read-only.
- The revision card requests a server-derived revision comparison and displays
  changed top-level commitment fields plus added/removed/changed deliverable
  counts. It does not calculate a client-side diff or compare provider state.
- Integration health exposes the existing authorized, idempotent terminal
  reconciliation command with an explicit provider-available/unavailable
  outcome and recorded reason. It never calls Linear from the browser and does
  not represent the command as live provider polling.
- Operators with `delivery.commitment.replay` can view a bounded, redacted
  dead-letter list and queue a reasoned, idempotent commitment replay. The UI
  explains that it preserves original evidence and does not configure/send via
  a live mail provider.
- Added explicit presentation for every plan, commitment, link, normalized
  execution, snapshot, connection and provider-registration state. Frozen,
  superseded, cancelled, rejected, imported historical, stale, broken and
  inaccessible cases have non-success language.
- Nullable provider fields render as unavailable instead of interpolating
  `null`. Stale and inaccessible cards retain and label last-known evidence.
- `COMPLETED`/Linear Done remains execution evidence only. The UI explicitly
  says acceptance and certification require a separate authorized decision,
  and sent/read commitment email never implies approval, acceptance or
  confirmation.
- Added delivery API/presentation tests for:
  - the server-resolved link request shape;
  - nullable assignment and provider response fields;
  - all snapshot/health/commitment/link/plan/normalized state combinations;
  - imported and superseded language;
  - state-valid action predicates;
  - 400/403/409/503 structured API error classification;
  - the 2,000-character rationale bound.

## Verification results

Commands were run from the repository root.

| Check                                                                                                                                                 | Result                                                                                                                                                     |
| ----------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `npm run typecheck`                                                                                                                                   | Passed                                                                                                                                                     |
| `npm run test`                                                                                                                                        | Passed: 12 files, 47 tests                                                                                                                                 |
| `npx eslint src/features/delivery src/routes/delivery.plans.$planId.tsx src/routes/delivery.plans.new.tsx src/routes/delivery.integration-health.tsx` | Passed                                                                                                                                                     |
| `npm run build`                                                                                                                                       | Passed; Vite emitted only the existing large-chunk advisory                                                                                                |
| `npm run lint`                                                                                                                                        | Repository-wide check blocked by out-of-scope `e2e/fixtures/delivery-api.ts:207:7` (`prefer-const`); six existing Fast Refresh warnings were also reported |
| Delivery Playwright                                                                                                                                   | Passed: 8 intercepted F03/cross-feature browser-contract cases after fixture sync                                                                         |

The first typecheck run caught widened string arrays in the exhaustive enum
tests and the old `DELIVERABLE` dependency value. Both were fixed before the
passing result above. The first targeted delivery Vitest run passed 2 files and
18 tests; the final full run passed 12 files and 47 tests.

The intercepted fixture was subsequently aligned to the hardened contract:
`ACTIVE | BROKEN | INACCESSIBLE` links,
`CAPTURED | FETCH_FAILED | UNAVAILABLE` snapshots, nullable/outbox commitment
statuses and server-resolved provider metadata. The passing result remains
browser-contract evidence only: it does not exercise Java, PostgreSQL, BFF or a
live provider.

## Remaining API gaps

- `PlanView` exposes workflow state but no caller-specific capabilities such as
  `canSubmit`, `canApprove`, `canRevise` or `canLink`. The frontend safely gates
  by server state and relies on backend authorization, but it cannot hide an
  otherwise state-valid control for an individually ineligible caller before a
  403/404 response.
- There is no frontend-consumable endpoint in F03 for authorized Linear
  connections or recorded issue candidates. The safe link form therefore
  requires opaque connection and issue UUIDs instead of offering a
  server-resolved picker.
- The frontend contract is still hand-maintained. There is no checked-in
  generated OpenAPI client/schema decoder to reject runtime response drift
  before rendering.
- Health exposes aggregate stale counts but not separate inaccessible counts.
  Commitment replay capability is read from the authenticated permission set;
  the backend remains authoritative for every operation.
- Provider error values are sanitized strings rather than a closed,
  documented error-code union, so the UI can label the error safely but cannot
  provide code-specific remediation.

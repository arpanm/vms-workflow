# F05 frontend code-generation handoff

## Implemented boundary

The React 19/TanStack Router/Query finance vertical consumes authenticated,
server-authorized Java APIs rooted at `/api/v1/finance`. In source,
`src/features/finance/api.ts` uses `/finance` because the shared API client base is
`/api/v1`. The browser never supplies organization, engagement, actor, role or
authority claims. Server-returned `FinancePermission` values gate presentation;
they are not an authorization boundary.

The authoritative frontend DTO and mutation schema is
`src/features/finance/contracts.ts`. It deliberately contains source IDs,
versions, SHA-256/checksum values, provenance, represented/recorded timestamps,
freshness and `LIVE | SNAPSHOT | RECONSTRUCTED` temporal labels. It contains no
provider secret, storage key, signed URL, raw evidence bytes, raw restricted
message, salary/CTC/rate/markup/margin/payroll field, or employee-level invoice
allocation.

### Read APIs

- `GET /api/v1/finance/access` returns scoped permissions and
  `CONFIGURED | NOT_CONFIGURED | ACTION_REQUIRED` status for private storage,
  scanner, renderer and AP/ERP integration.
- `GET /api/v1/finance/months?cursor=…` returns a cursor page of authorized month
  summaries, including the optimistic `version`, freshness and permissions.
- `GET /api/v1/finance/months/{monthId}` returns the consolidated finance month
  workspace: exact F04 contract/source handoff, confirmed-versus-exception
  disposition, current readiness-run ID, blockers, invoice summaries and package
  history.
- `GET /api/v1/finance/invoices?cursor=…&monthId=…` and
  `GET /api/v1/finance/invoices/{invoiceId}` return scoped invoice queue/detail
  data. Detail includes the ETag value, immutable versions, represented invoice
  metadata, safe document metadata/scan state, rule-level readiness, linked
  package, reviews, queries, exceptions and sanitized payment history.
- `GET /api/v1/finance/months/{monthId}/packages?cursor=…`,
  `GET /api/v1/finance/packages/{packageId}`,
  `GET /api/v1/finance/packages/{packageId}/diff?against={packageId}` and
  `GET /api/v1/finance/packages/{packageId}/access-events?cursor=…` expose
  immutable package lineage, manifest sources, version diffs and audited access.
- `GET /api/v1/finance/procurement/control-tower?cursor=…` returns a paginated
  nine-pillar matrix. Each cell carries a non-color state label, owner, CTA,
  source/version, freshness and live/snapshot/reconstructed mode.
- `GET /api/v1/finance/invoices/{invoiceId}/payments` returns the permitted,
  sanitized append-only timeline.
- `GET /api/v1/finance/dashboard` returns persona-scoped metrics with explicit
  unavailable-versus-zero semantics, definition/policy version, source,
  freshness and temporal mode.
- `GET /api/v1/finance/reports?cursor=…` returns the authorized report catalog and
  asynchronous export queue.

All list endpoints use `Page<T> { items, nextCursor, totalCount }`. Cursor and
filter scope is derived and constrained by the server.

### Mutation APIs

Every retry-safe mutation sends a UUID `Idempotency-Key`. Every mutation of an
existing aggregate also sends `If-Match: "{expectedVersion}"` and repeats the
expected version in the typed request for auditable validation.

- `POST /api/v1/finance/invoices` creates `PRIMARY`, `CORRECTION`,
  `CREDIT_NOTE` or `DEBIT_NOTE` represented metadata. Non-primary records carry
  `relatedInvoiceId`; the server validates retained lineage and month/scope.
- `POST /api/v1/finance/invoices/{invoiceId}/documents` and
  `POST /api/v1/finance/invoices/{invoiceId}/documents/replace` accept
  `multipart/form-data`: `file` plus an `application/json` `metadata` part
  containing expected version, classification, retention policy, source and
  reason. Replacement creates an immutable version; it never overwrites.
- `POST /api/v1/finance/invoices/{invoiceId}/readiness-runs` evaluates the exact
  invoice/source/package input set.
- `POST /api/v1/finance/invoices/{invoiceId}/submit` requires exact invoice,
  package version and readiness-run IDs plus acknowledgment and reason.
- `POST /api/v1/finance/months/{monthId}/packages` queues generation against the
  exact month version/readiness run and returns package job state/progress.
- `POST /api/v1/finance/procurement/invoices/{invoiceId}/reviews` records
  `APPROVED_FOR_PROCESSING | CHANGES_REQUESTED | ON_HOLD | REJECTED`. Non-approval
  category/comment requirements are client-aided and server-enforced.
- `POST /api/v1/finance/procurement/invoices/{invoiceId}/queries` creates an
  assigned, due-dated correction query. It cannot mutate upstream facts.
- `POST /api/v1/finance/procurement/invoices/{invoiceId}/exceptions` binds an
  exception to the exact failed rule, readiness run, package version and invoice
  version, with rationale and validity/expiry. It cannot nominate an approver.
- `POST /api/v1/finance/procurement/exceptions/{exceptionId}/second-approval`
  lets a distinct current authenticated Procurement authority approve the exact
  pending invoice/rule/readiness/package/policy tuple. The server rejects
  requester self-approval, expiry, stale version and binding mismatch.
- `POST /api/v1/finance/invoices/{invoiceId}/payments` appends a legal AP/ERP/manual
  status transition with sanitized comment/reference/timestamps. It never moves
  funds or changes invoice/package/readiness evidence.
- `POST /api/v1/finance/exports` queues an exact report/version/format,
  current-or-snapshot mode, authorized filter set and reason.

Package artifacts use
`POST /api/v1/finance/packages/{packageId}/artifacts/{artifactId}/download` and
exports use `POST /api/v1/finance/exports/{exportId}/download`. These endpoints
return an authenticated attachment body directly. The shared client creates a
short-lived browser object URL only for the synchronous save click, immediately
revokes it, and returns no URL to React Query. Pending, unknown, failed,
quarantined, disposed, integrity-failed, revoked or expired content must be
rejected by the server with a typed problem.

Typed error responses use an HTTP status plus safe `code`, `detail` and
`correlationId`. The UI curates non-disclosing handling for 401/403/404,
`VERSION_CONFLICT`, idempotency mismatch, readiness blocked, scan pending,
quarantine, expired download and provider `NOT_CONFIGURED`; unknown failures do
not render server payloads as HTML.

## Routes and flows

- `/finance` is the consolidated authorized month workspace. It provides
  dashboard/configuration state, month and invoice queues, primary/correction/
  credit/debit draft metadata, scan-aware upload and immutable replacement,
  rule-level readiness, exact-version submission, package generation progress,
  retained history/diff, manifest/source provenance, authenticated downloads,
  access history, invoice version lineage and sanitized payment timeline.
- `/finance/procurement` provides the responsive readiness control tower and
  selected-invoice drill-down. It records version-bound reviews, assigned
  queries, expiring exception requests and authenticated distinct second
  approvals, plus append-only payment updates.
  Upstream source content is read-only and owner correction remains a link/work
  assignment.
- `/finance/reports` provides persona-scoped dashboard metrics, report dictionary,
  exact filter/current-versus-snapshot export request and async
  queued/running/retry/dead-letter/ready/expired handling with progress,
  freshness, row count and output checksum.
- `/invoices` redirects to `/finance`; legacy amount aggregation and legacy
  invoice authority are removed.

The demo sidebar shows finance items only to plausible demo personas (vendor,
Procurement, finance, governance and scoped reporting personas). A dedicated
Procurement demo persona was added. Direct navigation is intentionally still
possible: the authenticated API performs the decisive scope/permission check and
safe-denial boundary.

Consequential upload/replace, package generation, invoice submit, Procurement
review/query/exception, payment update and export actions require an accessible
reason/acknowledgment form and display the exact expected server version and
downstream consequence. All controls are labelled, keyboard operable, carry text
status in addition to color, and use responsive cards or horizontally scrollable
captioned tables. Loading, empty, stale, read-only/superseded, scan/quarantine,
permission denial, provider-not-configured, version conflict, export retry/dead
letter and expired download states are explicit.

## Local validation

Executed against the generated route tree:

- `npm run typecheck` — passed.
- `npm run lint` — passed with the repository's existing fast-refresh warnings
  plus the same warning class for the finance component/formatting export; no
  errors.
- `npm run build` — passed. Vite reported the existing large-chunk advisory;
  finance routes are route-split into their own production chunks.

No F05 test or Playwright files were added in this generation stage, as required
by the assigned automation boundary. Backend/OpenAPI contract reconciliation,
MockMvc/JUnit, React component tests and the F05 Playwright catalog remain full
stack/integration gates.

## Honest external and deployment gates

Local UI completion does not configure or accept a real private object store,
malware scanner/quarantine callback, deterministic renderer, retention/legal
hold, AP/ERP adapter, approved Procurement package, SSO production grants or
signed-download policy. Until the server reports approved configuration, the UI
shows `NOT_CONFIGURED` or `ACTION_REQUIRED` and blocks the affected action.

Provider acceptance, restored-object hash verification, real scan/quarantine,
short-expiry/revocation controls, deterministic artifact output, approved
Procurement processing, AP/ERP callback reconciliation, accessibility tooling,
cross-tenant browser security tests and production performance remain the
external/full-stack gates described in `TASKS.md` and `TEST_CASES.md`. A rendered
screen or successful local build is not evidence that those gates passed.

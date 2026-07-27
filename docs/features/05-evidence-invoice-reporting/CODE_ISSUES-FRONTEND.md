# F05 frontend issue register

This register records actionable findings from the 2026-07-26 frontend review.
Locations are in the current working tree. No code was changed by the review.

## F05-FE-001 — P0 — Finance UI has no compatible executable backend contract

**Evidence.** `src/features/finance/api.ts:50-203` invokes the finance API
surface described in `CODEGEN-FRONTEND.md`, yet no `FinanceController` exists
under `backend/src/main/java/com/vms/workflow/api`. The unconnected
`backend/src/main/java/com/vms/workflow/api/FinanceDtos.java:74-118` expects a
flat `InvoiceCreateInput` and client file metadata, while
`src/features/finance/contracts.ts:468-482` and
`src/features/finance/workspace.tsx:717-735` send a nested
`representedMetadata`/`documentKind` request and multipart upload metadata.
The control-tower and workspace DTOs similarly do not match the frontend's
`ControlTowerView` and `FinanceAccessView`.

**Impact.** Every finance route fails against the supplied Java application;
invoice, package, Procurement, payment, report, export, permission and safe
download claims have no executable server authority. A guessed implementation
would risk client/server version, scope and non-disclosure divergence.

**Expected fix.** Freeze a versioned OpenAPI/schema owned by the Java API,
implement the full finance controller/service surface with server-derived scope,
typed errors, ETag/idempotency semantics and pagination, then generate or
contract-test the TypeScript DTOs against it. Do not accept client-provided
file hashes/MIME/size as authoritative.

**Mapped tests.** `T-F05-API-001`, `T-F05-SEC-001`–`004`, `T-INV-001`–`007`,
`T-PROC-001`–`005`, `T-PAY-001`–`003`, `T-REP-002`–`006`, `E2E-06`,
`E2E-09-F05`.

**Gate.** Full-stack/local implementation blocker; not an external provider
acceptance item.

## F05-FE-002 — P1 — Payment history is rendered without the payment-view capability

**Evidence.** `InvoiceView` declares both `permissions` and `paymentTimeline`
(`src/features/finance/contracts.ts:345-356`) and the permission union includes
`PAYMENT_VIEW` (`:14`). `InvoiceWorkspace` renders every payment event at
`src/features/finance/workspace.tsx:523-537` with no `PAYMENT_VIEW` gate.
`ProcurementInvoice` also receives this complete view (`procurement-workspace.tsx:167-239`).

**Impact.** A response assembled incorrectly by a server, cache or future
endpoint change exposes sanitized-but-restricted AP/vendor payment information
to any invoice viewer. UI permission visibility must not be the authorization
boundary, but it must not defeat a designed restricted-field boundary either.

**Expected fix.** Omit payment events in unauthorized API responses and render
the timeline only when the server returns `PAYMENT_VIEW`; return an explicit
non-disclosing restricted-timeline state otherwise. Cover vendor and
cross-scope personas.

**Mapped tests.** `T-PAY-001`, `T-F05-SEC-001`–`004`, `T-F05-UI-003`,
`E2E-09-F05`.

**Gate.** Local frontend defect plus API parity verification.

## F05-FE-003 — P1 — Read-only/stale invoices still expose consequential actions

**Evidence.** `VersionBanner` reports `readOnly` and stale state
(`src/features/finance/components.tsx:272-290`), and document upload/submission
disable themselves (`workspace.tsx:552`, `611`). In contrast, readiness
evaluation is available to any `INVOICE_VIEW` user (`workspace.tsx:593-603`),
and review, query, exception and payment forms only check their permission;
they do not check `invoice.readOnly` or freshness
(`procurement-workspace.tsx:243-331`). The month package-generation form is
also not freshness-gated (`workspace.tsx:290-314`).

**Impact.** A visibly superseded/read-only record still invites an irreversible
action. The server must reject it, but repeated conflict failures are not an
acceptable read-only/superseded workflow and a viewer can attempt readiness
mutation.

**Expected fix.** Introduce explicit command capabilities (including readiness
evaluation), disable/hide every consequential action for read-only, superseded
or stale sources, provide a refresh/reopen/owner CTA, and enforce the same
state/version checks server-side.

**Mapped tests.** `T-INV-005`–`007`, `T-PROC-002`–`005`, `T-PAY-002`,
`T-F05-UI-001`–`003`, `T-F05-UI-006`.

**Gate.** Local frontend defect plus server authorization/state-machine gate.

## F05-FE-004 — P1 — Package viewer does not show immutable manifest items

**Evidence.** `PackageView` carries `manifestItems` with safe name, source,
MIME, object version, size, SHA-256, classification and retention
(`src/features/finance/contracts.ts:242-263`). `PackageWorkspace` renders only
`packageView.sources` and rendered artifacts (`src/features/finance/workspace.tsx:660-695`);
`manifestItems` is never used.

**Impact.** Procurement cannot inspect the mandatory artifact-level lineage
behind the canonical package hash, including immutable object version and
classification/retention. This fails the required manifest/package viewer
instead of merely limiting cosmetic detail.

**Expected fix.** Add a permission-scoped, accessible manifest-item table or
detail view showing all contract fields with non-disclosing labels, pagination
where needed, integrity/scan blockers and retained/superseded state.

**Mapped tests.** `T-PKG-002`–`006`, `T-STOR-002`–`004`, `T-F05-UI-002`,
`T-F05-UI-004`.

**Gate.** Local frontend defect; artifact data and field masking still require
server contract verification.

## F05-FE-005 — P1 — No package share/revoke flow exists

**Evidence.** `src/features/finance/api.ts:117-146` has package history,
detail, diff, access and download calls but no create-share/revoke calls.
`workspace.tsx:642-700` has no corresponding control. The access event model
mentions `SHARED` and `REVOKED` (`contracts.ts:280-289`) but there is no way to
perform either action.

**Impact.** The UI cannot meet the explicit secure external-share requirement:
recipient, exact scope, expiry and revocation cannot be recorded/reviewed. An
access log alone is not a sharing workflow.

**Expected fix.** Add permission-gated share and revoke APIs and controls that
show exact package version, recipient/scope, expiry and downstream consequence;
never put a signed URL in UI state. Include safe expired/revoked outcomes.

**Mapped tests.** `T-STOR-003`, `T-F05-SEC-002`–`004`, `T-F05-UI-003`,
`T-F05-UI-005`, `E2E-09-F05`.

**Gate.** Local frontend defect; signed-download/share semantics are also a
full-stack and provider acceptance gate.

## F05-FE-006 — P1 — F05 frontend acceptance evidence is absent

**Evidence.** There are no finance `*.test.*` files under `src/features/finance`
and no F05 browser tests. `npm run test` passes 17 files/64 tests, all for
other features. The implementation handoff itself confirms no F05 test or
Playwright files were added (`CODEGEN-FRONTEND.md`, “Local validation”).

**Impact.** The frontend has no automated proof for scope non-disclosure,
idempotent replay, stale/version conflict, scan/quarantine, exports, keyboard
behaviour or the specified vendor/Procurement journeys.

**Expected fix.** Add API-client and component tests for all displayed state
and permission branches, plus Playwright contract tests for `T-F05-UI-001`
through `006` using server-shaped safe fixtures. Add real end-to-end coverage
once the P0 contract exists.

**Mapped tests.** `T-F05-UI-001`–`006`, `T-F05-REG-001`–`002`,
`T-F05-SEC-002`, and all frontend-relevant `T-INV`, `T-PROC`, `T-PAY`,
`T-REP` cases.

**Gate.** Local verification blocker.

## F05-FE-007 — P1 — Required dashboard queues and remediation CTAs are dropped

**Evidence.** `FinanceDashboard` declares `queues` with `actionPath`
(`src/features/finance/contracts.ts:425-432`), but both dashboard renders map
only `metrics` (`workspace.tsx:88-104`, `reports-workspace.tsx:60-78`).
`MatrixCell.actionPath` is also declared (`contracts.ts:373-392`) but
`ControlTowerTable` prints no cell CTA (`procurement-workspace.tsx:127-156`).

**Impact.** Confirmation-overdue, attendance-blocker, reopened/superseded,
review/hold, exception and payment-aging queues cannot be surfaced or acted on;
the owner/remediation part of the control tower is missing.

**Expected fix.** Render every authorized queue with count, freshness and a
safe scoped link; render each cell's authorized owner CTA or a clear
non-actionable explanation. Validate all paths server-side and do not expose a
cross-scope identifier in an error.

**Mapped tests.** `T-REP-002`–`003`, `T-F05-UI-002`, `T-F05-UI-004`,
`E2E-09-F05`.

**Gate.** Local frontend defect plus server scope/deep-link verification.

## F05-FE-008 — P2 — Cursor-backed lists have no pagination control

**Evidence.** API methods accept cursors (`src/features/finance/api.ts:53-65`,
`117-137`, `148-149`, `190-191`) and DTOs return `Page<T>`
(`contracts.ts:93-97`), but all hooks call the first page with no cursor and
the month, invoice, package history, access, control-tower and export screens
render only `items` (`hooks.ts:62-75`, `144-165`, `188-190`, `220-222`).

**Impact.** Authorized records beyond the default page disappear without a
loading/error/empty distinction. This violates the required server-paginated
tables and can hide work items or retained history.

**Expected fix.** Carry cursor state in each list/search route, use it in query
keys, provide accessible next/previous or load-more controls and preserve safe
scope on deep links.

**Mapped tests.** `T-REP-002`, `T-F05-API-001`, `T-F05-UI-002`,
`T-F05-UI-004`, `T-F05-PERF-001`.

**Gate.** Local frontend defect.

## F05-FE-009 — P2 — Scan states lack remediation and quarantine blocks replacement

**Evidence.** The invoice detail displays a scan badge only
(`src/features/finance/workspace.tsx:463-477`). `DocumentUpload` treats a
current `QUARANTINED` document as a blanket form blocker
(`workspace.tsx:547-586`), although replacement is the normal immutable
remediation path; package artifacts similarly only show a disabled download
button (`workspace.tsx:683-695`).

**Impact.** A vendor cannot recover through the UI after quarantine, and users
do not receive the required actionable pending/unknown/failed/quarantine state
or correlation reference. A quarantined object must remain inaccessible, but
it must not prevent a separate replacement version.

**Expected fix.** Keep preview/download/package/export blocked for non-passed
objects, but permit a separately authorized replacement upload when invoice
state permits. Show clear scan status, next action and safe correlation ID for
pending, unknown, failed, quarantined and disposed content.

**Mapped tests.** `T-STOR-001`–`004`, `T-INV-003`, `T-F05-UI-001`,
`T-F05-UI-006`, `T-F05-FAIL-001`.

**Gate.** Local frontend defect; actual scanner/quarantine behaviour is a
full-stack/provider acceptance gate.

## F05-FE-010 — P1 — Ambiguous retries can use a new idempotency key

**Evidence.** `FinanceMutationIntentStore.settle` retains a key only for
network (`status === 0`) and 5xx failures (`src/features/finance/idempotency.ts:24-41`).
After a 408 or 429 it clears the intent. A user retry of the same high-impact
form therefore sends a newly generated key even though the original command
could have committed or be in flight.

**Impact.** Create/upload/submit/review/query/exception/payment/export retries
can duplicate a business attempt or create confusing conflict/outbox history,
contrary to the exact-once/idempotency acceptance cases.

**Expected fix.** Retain the original key for every outcome whose commit state
is not conclusively known (at minimum 408, 425, 429 and client abort/transport
loss), expose a “retry same request” action, and clear only after a confirmed
response or an explicit user change/cancel. The server must still fingerprint
the key and reject mismatched payloads.

**Mapped tests.** `T-INV-006`, `T-PROC-005`, `T-PAY-002`,
`T-F05-OUTBOX-001`, `T-F05-FAIL-001`, `T-F05-UI-003`.

**Gate.** Local frontend defect plus server idempotency verification.


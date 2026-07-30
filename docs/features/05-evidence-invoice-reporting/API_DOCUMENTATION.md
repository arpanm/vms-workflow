# F05 — API documentation

The executable authoritative contract is the authenticated Spring OpenAPI
document. This guide maps the functional surface; it uses no production URLs,
credentials, raw evidence, signed URLs or personal commercial data.

## Common contract

- Base path: `/api/v1/finance`.
- Authentication/scope: derived solely from the authenticated subject and
  active server-side assignments.
- Existing-aggregate mutations require numeric `If-Match` and matching
  `expectedVersion`; retry-safe mutations require `Idempotency-Key`.
- Cursor lists return `items`, `nextCursor`, `totalCount`,
  `membershipSnapshotAt` and `temporalMode`. Cursors are HMAC-signed, opaque,
  short-lived and bound to the authenticated actor, exact route and current
  authorized engagement scope; clients must return them unchanged.
- `/months`, `/invoices`, package history, package access events and package
  shares use database keysets with immutable sort tuples, fetch at most
  `pageSize + 1`, and exclude records created after `membershipSnapshotAt`.
  Mutable row values remain `LIVE_AT_READ`; the cursor is a membership
  snapshot, not a historical value snapshot.
- Typed failure payloads carry safe `code`, `detail` and `correlationId`.
  They never contain storage credentials, raw restricted bytes or stack traces.

## Endpoint groups

| Group | Representative routes | Functional contract |
| --- | --- | --- |
| Access and workspaces | `GET /access`, `/months`, `/months/{monthId}` | Configuration gates, scoped month workspace, F04/source disposition and current finance state. |
| Invoices | `GET/POST /invoices`, `GET /invoices/{id}`, `POST /{id}/documents`, `/documents/replace`, `/readiness-runs`, `/submit` | Immutable represented metadata/document lineage, scan-aware upload, readiness and exact-version submission. |
| Packages | `POST /months/{monthId}/packages`, `GET /packages/{id}`, `/diff`, `/access-events`, `/shares`, `POST /shares`, `/shares/{shareId}/revoke` | Deterministic package lineage, access history and explicit expiring/revocable grants. |
| Procurement | `GET /procurement/control-tower`, `POST /procurement/invoices/{id}/reviews`, `/queries`, `/exceptions`, `POST /procurement/exceptions/{exceptionId}/second-approval` | Read-only upstream evidence, version-bound decision/query, two-step exception request/approval with authenticated SOD, and assigned remediation. |
| Payments | `GET /invoices/{id}/payments`, `POST /invoices/{id}/payments` | Authorized append-only AP/ERP/manual status facts; no funds transfer. |
| Reporting/exports | `GET /dashboard`, `/reports`, `POST /exports`, `POST /exports/{id}/download`, replay route | Persona-scoped definitions/metrics, asynchronous private artifacts, progress and authorized recovery. |
| Downloads | Package artifact and export download POST routes | Authorization, scan, integrity, expiry and audit checked by server; attachment body only. |

## State and lineage rules

Invoices, packages, readiness runs, reviews, queries and payments are
append-only or versioned facts. Correct/replacement/credit/debit flows retain
their parent. F04 invalidation or a changed source supersedes F05 derivations;
it never rewrites the prior evidence. Procurement exception is authority-bound
and disclosed, not a rewrite of certification or confirmation.

An exception request binds `invoiceId`, invoice optimistic version, failed
`ruleId`, `readinessRunId`, `packageId`/version, effective policy version,
rationale and expiry. It never accepts an approver identity from the caller.
When the effective policy requires two people, the request remains
`PENDING_SECOND_APPROVAL`. A different signed-in Procurement authority calls
`POST /procurement/exceptions/{exceptionId}/second-approval` with the exact
binding tuple and current invoice `If-Match`. The server derives the approving
actor from authentication, rejects the requester, stale/mismatched lineage and
expired requests, and returns `ACCEPTED` only after it has appended the
exception-derived readiness lineage. Invoice reads expose the immutable
request/package/readiness/policy binding needed by an authorized reviewer.

The Procurement control tower uses the same
`SNAPSHOT_MEMBERSHIP_VALUES_LIVE_AT_READ` contract: the set of month IDs is
fixed at the first-page cutoff while current package, invoice, readiness and
payment projections are read live on every page. Consumers must not describe
those mutable values as an as-of snapshot.

Dashboard metrics use `metricCode`, `displayName`, nullable `value`,
`AVAILABLE | UNAVAILABLE`, dictionary `version`, source/freshness and
`temporalMode`. They are aggregated across the complete current authorized
engagement scope rather than inferred from the first control-tower page, and
are labeled `LIVE`.

## API verification

`FinanceOpenApiIT` and frontend contract tests cover the surface. Final
OpenAPI, header, cursor, authorization and redaction verification is pending
the coordinated F05 regression. See [TEST_AUTOMATION.md](TEST_AUTOMATION.md).

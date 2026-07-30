# F04 API Documentation

## Draft withdrawal and governed evidence (2026-07-30)

- `POST /submissions/{submissionId}/withdraw` is exact-versioned and idempotent; only a `DRAFT` may become `WITHDRAWN`.
- `POST /months/{monthId}/artifacts` accepts multipart bytes and classification. The server safe-names, hashes, and records `PENDING` metadata while keeping local object keys private.
- `POST /artifacts/{artifactId}/scans` explicitly invokes the local scanner. PostgreSQL allows only `PENDING` to `PASSED`, `FAILED`, or `UNKNOWN`; other metadata remains immutable.

Live storage/scanner providers remain external adapters, not fabricated integrations.

## Contract rules

All F04 routes are rooted at `/api/v1/certification`, use authenticated server-resolved authority, and return a server version/`ETag` for versioned resources. Consequential mutations require `If-Match`, `Idempotency-Key`, and the matching expected version in the body. Typed validation/state/version errors are safe Problem Details with a normalized correlation ID; scope and authority denial is non-disclosing.

| Route | Purpose |
| --- | --- |
| `GET /inbox?limit=` | Return a safe, server-scoped cross-month work list with assigned review, evidence-review, readiness, due/overdue, and next-action facts. |
| `GET /operations?limit=` | Return authorized notification, reminder/expiry, and F05-handoff queue health plus actionable durable work; it never returns rendered bodies, recipients, tokens, MIME, or provider secrets. |
| `GET /months/{monthId}` | Scoped certification workspace and confirmation preview. |
| `POST /months/{monthId}/submissions` | Create/save versioned vendor draft. |
| `POST /submissions/{submissionId}/submit` | Lock an exact submission version. |
| `POST /submissions/{submissionId}/clarifications` | Append vendor clarification response. |
| `POST /submissions/{submissionId}/certifications` | Record scoped product-owner decision/criteria. |
| `POST /months/{monthId}/summaries` | Generate explicit, canonical monthly summary. |
| `GET /months/{monthId}/readiness` | Return five-pillar, versioned readiness. |
| `POST /months/{monthId}/confirmation-requests` | Issue exact-scope confirmation request. |
| `GET /confirmation-requests/{requestId}` | Read eligible/redacted request view. |
| `POST /confirmation-requests/{requestId}/actions` | Record authenticated confirmation/correction/rejection. |
| `POST /confirmation-requests/{requestId}/governance-decisions` | Resolve governed conflict. |
| `POST /months/{monthId}/inbound-messages`, `POST /inbound-messages/{messageId}/reviews` | Ingest/review restricted normalized inbound evidence. |
| `POST /months/{monthId}/manual-evidence`, `POST /manual-evidence/{evidenceId}/reviews` | Record and second-review manual evidence. |
| `POST /months/{monthId}/reopen-requests`, `POST /reopen-requests/{reopenRequestId}/decisions` | Request and decide reopen lineage. |
| `POST /months/{monthId}/closures`, `POST /invalidations/{invalidationId}/resolutions` | Close immutable scope and append effective invalidation resolution. |
| `POST /notifications/{notificationId}/replays` | Authorized durable notification replay, bound to an exact month version, reason, and retained idempotency key. |
| `POST /months/{monthId}/policy-versions` | Append/supersede policy version. |
| `POST /submissions/{submissionId}/evidence-exceptions`, `POST /months/{monthId}/attendance-exceptions` | Record separately authorized, scoped exceptions. |

Swagger/OpenAPI is protected by the configured documentation audience. It must contain no plaintext token/hash, provider credentials, raw MIME, signed object URL, or restricted evidence examples. Provider-neutral APIs can show `NOT_CONFIGURED` or `ACTION_REQUIRED`; they do not claim email, storage, or F05 execution.

See [CODEGEN-BACKEND.md](CODEGEN-BACKEND.md) for DTO semantics and [TEST_AUTOMATION.md](TEST_AUTOMATION.md) for API evidence.
